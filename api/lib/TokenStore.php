<?php

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
	 * @param array{ttl?:int,role?:string} $extra Optional TTL override and role (e.g. admin).
	 */
	public function issue(int $uid, string $username, array $extra = []): string {
		$token = bin2hex(random_bytes(24));
		$data = $this->read();
		$now = time();
		// prune expired
		foreach ($data as $k => $v) {
			if (($v['exp'] ?? 0) < $now) {
				unset($data[$k]);
			}
		}
		$ttl = isset($extra['ttl']) ? max(60, (int)$extra['ttl']) : $this->ttl;
		$row = [
			'uid' => $uid,
			'username' => $username,
			'exp' => $now + $ttl,
			'iat' => $now,
		];
		if (!empty($extra['role'])) {
			$row['role'] = (string)$extra['role'];
		}
		$data[$token] = $row;
		$this->write($data);
		return $token;
	}

	public function get(string $token): ?array {
		if ($token === '') {
			return null;
		}
		$data = $this->read();
		if (empty($data[$token])) {
			return null;
		}
		if (($data[$token]['exp'] ?? 0) < time()) {
			unset($data[$token]);
			$this->write($data);
			return null;
		}
		return $data[$token];
	}

	public function revoke(string $token): void {
		if ($token === '') {
			return;
		}
		$data = $this->read();
		if (isset($data[$token])) {
			unset($data[$token]);
			$this->write($data);
		}
	}

	public function userFromRequest(): ?array {
		$token = null;
		$hdr = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
		if (preg_match('/^Bearer\s+(\S+)$/i', $hdr, $m)) {
			$token = $m[1];
		}
		// FreePBX Apache on this host strips Authorization; prefer custom header
		if ($token === null || $token === '') {
			$token = $_SERVER['HTTP_X_IHF_TOKEN'] ?? null;
		}
		if ($token === null || $token === '') {
			return null;
		}
		return $this->get($token);
	}

	private function read(): array {
		$raw = @file_get_contents($this->file);
		$data = json_decode($raw ?: '{}', true);
		return is_array($data) ? $data : [];
	}

	private function write(array $data): void {
		$tmp = $this->file . '.tmp';
		file_put_contents($tmp, json_encode($data));
		rename($tmp, $this->file);
	}
}
