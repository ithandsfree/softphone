<?php
/**
 * Softphone entitlements / capability flags.
 *
 * Product path: FreePBX admin module (Phase 3) will own these.
 * Until then: config.php defaults + optional per-DID overrides.
 */
class Entitlements {
	public static function maxExtensions(array $config): int {
		$n = (int)($config['max_extensions'] ?? 2);
		return max(1, min(2, $n)); // product rule: max 2 for v1
	}

	public static function defaults(array $config): array {
		$d = $config['default_capabilities'] ?? [];
		return [
			'voice' => (bool)($d['voice'] ?? true),
			'sms' => (bool)($d['sms'] ?? true),
			'mms' => (bool)($d['mms'] ?? true),
			'dnd' => (bool)($d['dnd'] ?? false),
		];
	}

	/**
	 * Merge defaults with optional per-DID overrides from config.
	 * SMS lines returned by the gateway always have sms=true unless overridden off.
	 */
	public static function forDid(array $config, string $did, bool $hasSmsDid = true, bool $dndActive = false): array {
		$did = SmsGateway::normalizeDid($did);
		$caps = self::defaults($config);
		$caps['sms'] = $hasSmsDid ? $caps['sms'] : false;
		$caps['mms'] = $caps['sms'] ? $caps['mms'] : false;
		$caps['dnd'] = $dndActive;
		$overrides = $config['line_capabilities'][$did]
			?? $config['line_capabilities'][ltrim($did, '1')]
			?? null;
		if (is_array($overrides)) {
			foreach (['voice', 'sms', 'mms', 'dnd'] as $k) {
				if (array_key_exists($k, $overrides)) {
					$caps[$k] = (bool)$overrides[$k];
				}
			}
			if (!$caps['sms']) {
				$caps['mms'] = false;
			}
		}
		return $caps;
	}

	public static function enrichLines(array $config, array $lines, callable $dndLookup): array {
		$out = [];
		foreach ($lines as $row) {
			$did = SmsGateway::normalizeDid((string)($row['did'] ?? ''));
			$ext = isset($row['extension']) ? (string)$row['extension'] : null;
			$dnd = $dndLookup($did, $ext);
			$row['capabilities'] = self::forDid($config, $did, true, $dnd);
			$out[] = $row;
		}
		return $out;
	}
}
