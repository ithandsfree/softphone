<?php
/**
 * Brute-force guard for POST /v1/login (User Manager passwords over the public softphone edge).
 *
 * Counts failures only. Locked when either limit is reached inside the window:
 *   - per username + IP (login_max_failures, default 5)
 *   - per IP across all usernames (login_max_failures_ip, default 20)
 * A success clears the username + IP counter. Keys are hashes; no usernames or IPs are stored in clear.
 * Each failure is written to the PHP error log as
 *   "ihf-softphone: login failed user=<name> ip=<addr>"
 * so fail2ban can ban repeat offenders at the firewall.
 */
class LoginLimiter {
	private $file;
	private $maxPerUser;
	private $maxPerIp;
	private $window;

	public function __construct(string $file, int $maxPerUser = 5, int $maxPerIp = 20, int $window = 900) {
		$this->file = $file;
		$this->maxPerUser = max(1, $maxPerUser);
		$this->maxPerIp = max($this->maxPerUser, $maxPerIp);
		$this->window = max(60, $window);
		$dir = dirname($file);
		if (!is_dir($dir)) {
			@mkdir($dir, 0750, true);
		}
	}

	/** Seconds to wait when locked, or 0 when a login may be tried. */
	public function retryAfter(string $username, string $ip): int {
		$now = time();
		$data = $this->read();
		$wait = 0;
		foreach ([[$this->userKey($username, $ip), $this->maxPerUser], [$this->ipKey($ip), $this->maxPerIp]] as [$key, $max]) {
			$hits = $this->live((array)($data[$key] ?? []), $now);
			if (count($hits) >= $max) {
				$wait = max($wait, $this->window - ($now - min($hits)));
			}
		}
		return max(0, $wait);
	}

	public function failed(string $username, string $ip): void {
		error_log('ihf-softphone: login failed user=' . preg_replace('/[^A-Za-z0-9@._+-]/', '?', $username) . ' ip=' . $ip);
		$this->mutate(function (array $data) use ($username, $ip): array {
			$now = time();
			foreach ([$this->userKey($username, $ip), $this->ipKey($ip)] as $key) {
				$hits = $this->live((array)($data[$key] ?? []), $now);
				$hits[] = $now;
				$data[$key] = $hits;
			}
			foreach ($data as $k => $v) {
				if ($this->live((array)$v, $now) === []) {
					unset($data[$k]);
				}
			}
			return $data;
		});
	}

	public function succeeded(string $username, string $ip): void {
		$this->mutate(function (array $data) use ($username, $ip): array {
			unset($data[$this->userKey($username, $ip)]);
			return $data;
		});
	}

	/**
	 * Client address. X-Forwarded-For is only believed when REMOTE_ADDR is one of the configured
	 * trusted_proxies; otherwise anyone could pick their own IP and step around the limits.
	 */
	public static function clientIp(array $config): string {
		$remote = (string)($_SERVER['REMOTE_ADDR'] ?? '');
		$trusted = (array)($config['trusted_proxies'] ?? []);
		if ($remote !== '' && in_array($remote, $trusted, true) && !empty($_SERVER['HTTP_X_FORWARDED_FOR'])) {
			$first = trim(explode(',', (string)$_SERVER['HTTP_X_FORWARDED_FOR'])[0]);
			if (filter_var($first, FILTER_VALIDATE_IP)) {
				return $first;
			}
		}
		return $remote !== '' ? $remote : 'unknown';
	}

	private function userKey(string $username, string $ip): string {
		return 'u:' . hash('sha256', strtolower(trim($username)) . '|' . $ip);
	}

	private function ipKey(string $ip): string {
		return 'i:' . hash('sha256', $ip);
	}

	private function live(array $hits, int $now): array {
		return array_values(array_filter($hits, function ($t) use ($now) {
			return is_int($t) && ($now - $t) < $this->window;
		}));
	}

	private function read(): array {
		$raw = @file_get_contents($this->file);
		$data = json_decode($raw ?: '{}', true);
		return is_array($data) ? $data : [];
	}

	private function mutate(callable $change): void {
		$lock = @fopen($this->file . '.lock', 'c');
		if ($lock !== false) {
			flock($lock, LOCK_EX);
		}
		try {
			$data = $change($this->read());
			$tmp = $this->file . '.tmp';
			file_put_contents($tmp, json_encode($data));
			@chmod($tmp, 0640);
			rename($tmp, $this->file);
		} finally {
			if ($lock !== false) {
				flock($lock, LOCK_UN);
				fclose($lock);
			}
		}
	}
}
