<?php
/**
 * Softphone assignment map (ext → enrol metadata).
 * No licensing / billing — beta ops bookkeeping only.
 */
class AssignmentStore {
	private $file;

	public function __construct(string $file) {
		$this->file = $file;
		$dir = dirname($file);
		if (!is_dir($dir)) {
			@mkdir($dir, 0750, true);
		}
		if (!file_exists($file)) {
			@file_put_contents($file, '{}');
			@chmod($file, 0640);
		}
	}

	/** @return array<string,array> keyed by extension */
	public function all(): array {
		$data = $this->read();
		ksort($data, SORT_NATURAL);
		return $data;
	}

	public function get(string $ext): ?array {
		$ext = self::normExt($ext);
		$data = $this->read();
		return $data[$ext] ?? null;
	}

	/**
	 * @param array<string,mixed> $row
	 */
	public function put(string $ext, array $row): array {
		$ext = self::normExt($ext);
		$data = $this->read();
		$row['extension'] = $ext;
		$row['updated_at'] = time();
		if (empty($row['created_at'])) {
			$row['created_at'] = $data[$ext]['created_at'] ?? time();
		}
		$data[$ext] = $row;
		$this->write($data);
		return $row;
	}

	public function forget(string $ext): void {
		$ext = self::normExt($ext);
		$data = $this->read();
		unset($data[$ext]);
		$this->write($data);
	}

	public static function normExt(string $ext): string {
		return preg_replace('/\D+/', '', $ext) ?? '';
	}

	private function read(): array {
		$raw = @file_get_contents($this->file);
		$data = json_decode($raw ?: '{}', true);
		return is_array($data) ? $data : [];
	}

	private function write(array $data): void {
		$tmp = $this->file . '.tmp';
		file_put_contents($tmp, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES));
		@chmod($tmp, 0640);
		rename($tmp, $this->file);
	}
}
