<?php
/**
 * Bearer tokens for the softphone BFF, kept in one JSON file.
 *
 * Kinds (row "role"):
 *   - (none)  setup / login token: short-lived (token_ttl_seconds, 72 h by default).
 *   - device  issued when an app opens /v1/session with a setup or login token. Long-lived and sliding:
 *             it is extended when less than half its lifetime is left, so a phone or PC in use never has to
 *             sign in again. Revoked by POST /v1/logout (the app does this when a line is removed).
 *   - admin   admin UI session (AdminAuth); not accepted for app sessions.
 *
 * Every change runs under an exclusive lock so two requests cannot overwrite each other's tokens.
 *
 * At rest only SHA-256 hashes are kept ("h:<hex>" keys), so a copy of the file cannot be replayed as a login.
 * Rows written by older versions under the raw token are moved to their hash on first use.
 */
class TokenStore {
	private $file;
	private $ttl;

	public function __construct(string $file, int $ttlSeconds) {
		$this->file = $file;
		$this->ttl = max(60, $ttlSeconds);
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
	 * @param array{ttl?:int,role?:string} $extra Optional TTL override and role (admin, device).
	 */
	public function issue(int $uid, string $username, array $extra = []): string {
		$token = bin2hex(random_bytes(24));
		$key = self::key($token);
		$ttl = isset($extra['ttl']) ? max(60, (int)$extra['ttl']) : $this->ttl;
		$this->mutate(function (array $data) use ($key, $uid, $username, $ttl, $extra): array {
			$now = time();
			foreach ($data as $k => $v) {
				if (($v['exp'] ?? 0) < $now) {
					unset($data[$k]);
				}
			}
			$row = [
				'uid' => $uid,
				'username' => $username,
				'exp' => $now + $ttl,
				'iat' => $now,
				'ttl' => $ttl,
			];
			if (!empty($extra['role'])) {
				$row['role'] = (string)$extra['role'];
			}
			$data[$key] = $row;
			return $data;
		});
		return $token;
	}

	public function get(string $token): ?array {
		if ($token === '' || strlen($token) > 256) {
			return null;
		}
		$key = self::key($token);
		$data = $this->read();
		$row = $data[$key] ?? null;
		if (!is_array($row) && isset($data[$token]) && is_array($data[$token])) {
			// Written by an older version under the raw token: move it to its hash.
			$row = $data[$token];
			$this->mutate(function (array $data) use ($token, $key): array {
				if (isset($data[$token])) {
					$data[$key] = $data[$token];
					unset($data[$token]);
				}
				return $data;
			});
		}
		if (!is_array($row)) {
			return null;
		}
		$now = time();
		if (($row['exp'] ?? 0) < $now) {
			$this->revoke($token);
			return null;
		}
		// Sliding lifetime for device tokens: extend once less than half is left (about one write per 90 days).
		if (($row['role'] ?? '') === 'device') {
			$ttl = (int)($row['ttl'] ?? 0);
			if ($ttl > 0 && ($row['exp'] - $now) < intdiv($ttl, 2)) {
				$this->mutate(function (array $data) use ($key, $now, $ttl): array {
					if (isset($data[$key])) {
						$data[$key]['exp'] = $now + $ttl;
					}
					return $data;
				});
				$row['exp'] = $now + $ttl;
			}
		}
		return $row;
	}

	/** Counts one more setup use of [token] and returns the new count (0 when unknown). */
	public function countUse(string $token): int {
		if ($token === '') {
			return 0;
		}
		$key = self::key($token);
		$count = 0;
		$this->mutate(function (array $data) use ($key, &$count): array {
			if (isset($data[$key]) && is_array($data[$key])) {
				$data[$key]['uses'] = (int)($data[$key]['uses'] ?? 0) + 1;
				$count = $data[$key]['uses'];
			}
			return $data;
		});
		return $count;
	}

	public function revoke(string $token): void {
		if ($token === '') {
			return;
		}
		$key = self::key($token);
		$this->mutate(function (array $data) use ($token, $key): array {
			unset($data[$key], $data[$token]);
			return $data;
		});
	}

	private static function key(string $token): string {
		return 'h:' . hash('sha256', $token);
	}

	/** The bearer sent with this request (X-IHF-Token, or Authorization: Bearer). */
	public function tokenFromRequest(): string {
		$hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
		if (preg_match('/^Bearer\s+(\S+)$/i', $hdr, $m)) {
			return $m[1];
		}
		// FreePBX Apache strips Authorization on some builds; the app sends this header.
		return (string)($_SERVER['HTTP_X_IHF_TOKEN'] ?? '');
	}

	public function userFromRequest(): ?array {
		$token = $this->tokenFromRequest();
		return $token === '' ? null : $this->get($token);
	}

	private function read(): array {
		$raw = @file_get_contents($this->file);
		$data = json_decode($raw ?: '{}', true);
		return is_array($data) ? $data : [];
	}

	/** Read-modify-write under an exclusive lock on a side file; the JSON is replaced atomically. */
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
