<?php
/**
 * Simple file-backed rate limit for public request-enrol-by-email.
 * Keys are opaque hashes — never store raw emails in the rate file.
 */
class EnrolRequestLimiter {
	private $file;
	private $perEmailSeconds;
	private $perIpMax;
	private $perIpWindow;

	public function __construct(
		string $file,
		int $perEmailSeconds = 30,
		int $perIpMax = 8,
		int $perIpWindow = 3600
	) {
		$this->file = $file;
		$this->perEmailSeconds = max(5, $perEmailSeconds);
		$this->perIpMax = max(1, $perIpMax);
		$this->perIpWindow = max(60, $perIpWindow);
		$dir = dirname($file);
		if (!is_dir($dir)) {
			@mkdir($dir, 0750, true);
		}
		if (!file_exists($file)) {
			@file_put_contents($file, '{}');
			@chmod($file, 0640);
		}
	}

	/**
	 * @return array{ok:bool,retry_after?:int}
	 */
	public function allow(string $emailNorm, string $ip): array {
		$now = time();
		$data = $this->read();
		$emailKey = 'e:' . hash('sha256', $emailNorm);
		$ipKey = 'i:' . hash('sha256', $ip !== '' ? $ip : 'unknown');

		$lastEmail = (int)($data[$emailKey]['last'] ?? 0);
		if ($lastEmail > 0 && ($now - $lastEmail) < $this->perEmailSeconds) {
			return [
				'ok' => false,
				'retry_after' => $this->perEmailSeconds - ($now - $lastEmail),
			];
		}

		$ipHits = array_values(array_filter(
			(array)($data[$ipKey]['hits'] ?? []),
			function ($t) use ($now) {
				return is_int($t) && ($now - $t) < $this->perIpWindow;
			}
		));
		if (count($ipHits) >= $this->perIpMax) {
			$oldest = min($ipHits);
			return [
				'ok' => false,
				'retry_after' => max(1, $this->perIpWindow - ($now - $oldest)),
			];
		}

		$data[$emailKey] = ['last' => $now];
		$ipHits[] = $now;
		$data[$ipKey] = ['hits' => $ipHits];

		// Prune stale keys
		foreach ($data as $k => $v) {
			if (strpos($k, 'e:') === 0) {
				if (($now - (int)($v['last'] ?? 0)) > 86400) {
					unset($data[$k]);
				}
			} elseif (strpos($k, 'i:') === 0) {
				$hits = array_values(array_filter(
					(array)($v['hits'] ?? []),
					function ($t) use ($now) {
						return is_int($t) && ($now - $t) < $this->perIpWindow;
					}
				));
				if ($hits === []) {
					unset($data[$k]);
				} else {
					$data[$k] = ['hits' => $hits];
				}
			}
		}

		$this->write($data);
		return ['ok' => true];
	}

	private function read(): array {
		$raw = @file_get_contents($this->file);
		$data = json_decode($raw ?: '{}', true);
		return is_array($data) ? $data : [];
	}

	private function write(array $data): void {
		$tmp = $this->file . '.tmp';
		file_put_contents($tmp, json_encode($data));
		@chmod($tmp, 0640);
		rename($tmp, $this->file);
	}
}
