<?php
/**
 * Voicemail for a softphone line, with the same rules as UCP's Voicemail widget:
 *   - User Manager / UCP "Voicemail" settings: enable, assigned (extensions, "self" = the user's default extension),
 *     playback and download (unset means allowed, as in UCP).
 *   - Messages come from the FreePBX Voicemail module (getMessagesByExtension), the data UCP lists.
 *   - Audio is served only for a message that module returns for that extension, and only from a file under the
 *     voicemail spool. File paths never leave the server.
 */
class VoicemailBox {
	/** Folders the app shows: new, then heard. Urgent counts as new, as on a desk phone. */
	private const FOLDERS = ['INBOX', 'Urgent', 'Old'];

	/**
	 * @return array{voicemail:bool,playback:bool,download:bool}
	 */
	public function permissions(int $uid, string $ext): array {
		$none = ['voicemail' => false, 'playback' => false, 'download' => false];
		if (!ctype_digit($ext)) {
			return $none;
		}
		try {
			$ucp = \FreePBX::Ucp();
			if (!$ucp->getCombinedSettingByID($uid, 'Voicemail', 'enable')) {
				return $none;
			}
			$assigned = (array)($ucp->getCombinedSettingByID($uid, 'Voicemail', 'assigned') ?: []);
			if (in_array('self', $assigned, true)) {
				$user = \FreePBX::Userman()->getUserByID($uid);
				$assigned[] = (string)($user['default_extension'] ?? '');
			}
			if (!in_array($ext, array_map('strval', $assigned), true)) {
				return $none;
			}
			if (!\FreePBX::Voicemail()->checkVoicemailEnabled($ext)) {
				return $none;
			}
			$playback = $ucp->getCombinedSettingByID($uid, 'Voicemail', 'playback');
			$download = $ucp->getCombinedSettingByID($uid, 'Voicemail', 'download');
			return [
				'voicemail' => true,
				'playback' => $playback === null ? true : (bool)$playback,
				'download' => $download === null ? true : (bool)$download,
			];
		} catch (Throwable $e) {
			return $none;
		}
	}

	/** @return array<string,array<string,mixed>> raw module messages keyed by id */
	private function raw(string $ext): array {
		$vm = \FreePBX::Voicemail();
		// The module caches the last mailbox per object; never let one extension see another's cache.
		$vm->clearCache();
		$all = $vm->getMessagesByExtension($ext);
		return (array)($all['messages'] ?? []);
	}

	/**
	 * Messages, newest first, new (INBOX, Urgent) before heard (Old).
	 *
	 * @return array{new:int,old:int,messages:list<array<string,mixed>>}
	 */
	public function messages(string $ext, int $limit): array {
		$limit = max(1, min(200, $limit));
		$out = [];
		$new = 0;
		$old = 0;
		foreach ($this->raw($ext) as $id => $m) {
			$folder = (string)($m['folder'] ?? '');
			if (!in_array($folder, self::FOLDERS, true)) {
				continue;
			}
			$isNew = $folder !== 'Old';
			$isNew ? $new++ : $old++;
			[$name, $number] = self::splitCallerId((string)($m['callerid'] ?? ''));
			$out[] = [
				'id' => (string)$id,
				'folder' => $folder,
				'new' => $isNew,
				'urgent' => $folder === 'Urgent',
				'at' => (int)($m['origtime'] ?? 0),
				'number' => $number,
				'name' => $name,
				'duration' => (int)($m['duration'] ?? 0),
			];
		}
		usort($out, static function ($a, $b) {
			return [$b['new'], $b['at']] <=> [$a['new'], $a['at']];
		});
		return ['new' => $new, 'old' => $old, 'messages' => array_slice($out, 0, $limit)];
	}

	/** @return array{new:int,old:int} */
	public function counts(string $ext): array {
		$new = 0;
		$old = 0;
		foreach ($this->raw($ext) as $m) {
			$folder = (string)($m['folder'] ?? '');
			if ($folder === 'Old') {
				$old++;
			} elseif (in_array($folder, self::FOLDERS, true)) {
				$new++;
			}
		}
		return ['new' => $new, 'old' => $old];
	}

	/**
	 * The PBX's own "My Voicemail" feature code (FreePBX default *97, admins can change or disable it), so the app
	 * dials whatever this PBX uses. Empty when it is turned off.
	 */
	public static function mailboxCode(): string {
		try {
			$fc = new \featurecode('voicemail', 'myvoicemail');
			return (string)($fc->getCodeActive() ?: '');
		} catch (Throwable $e) {
			return '';
		}
	}

	public static function validId(string $id): bool {
		return preg_match('/^[A-Za-z0-9_.-]{1,80}$/', $id) === 1;
	}

	/** Moves a new message to Old (heard). True when it was found. */
	public function markHeard(string $ext, string $id): bool {
		if (!self::validId($id)) {
			return false;
		}
		$messages = $this->raw($ext);
		if (!isset($messages[$id])) {
			return false;
		}
		if (($messages[$id]['folder'] ?? '') === 'Old') {
			return true;
		}
		$ok = (bool)\FreePBX::Voicemail()->moveMessageByExtensionFolder($id, $ext, 'Old');
		\FreePBX::Voicemail()->clearCache();
		return $ok;
	}

	public function delete(string $ext, string $id): bool {
		if (!self::validId($id)) {
			return false;
		}
		$messages = $this->raw($ext);
		if (!isset($messages[$id])) {
			return false;
		}
		$ok = (bool)\FreePBX::Voicemail()->deleteMessageByID($id, $ext);
		\FreePBX::Voicemail()->clearCache();
		return $ok;
	}

	/** Local audio file for a message of [ext], or null. Prefers PCM wav (the app plays that directly). */
	public function audioPath(string $ext, string $id): ?string {
		if (!self::validId($id)) {
			return null;
		}
		$m = $this->raw($ext)[$id] ?? null;
		if (!is_array($m)) {
			return null;
		}
		$formats = (array)($m['format'] ?? []);
		foreach (['wav', 'WAV', 'gsm'] as $f) {
			if (empty($formats[$f]['filename']) || empty($formats[$f]['path'])) {
				continue;
			}
			$path = rtrim((string)$formats[$f]['path'], '/') . '/' . basename((string)$formats[$f]['filename']);
			$real = realpath($path);
			$root = realpath(rtrim((string)\FreePBX::Config()->get('ASTSPOOLDIR'), '/') . '/voicemail');
			if ($real !== false && $root !== false && strpos($real, rtrim($root, '/') . '/') === 0 && is_readable($real)) {
				return $real;
			}
		}
		return null;
	}

	/**
	 * Streams a message as 16-bit PCM WAV and exits. A GSM or WAV49 file is converted with sox into a temporary
	 * file first, because the clients play PCM WAV only.
	 */
	public static function stream(string $path, string $id, bool $download): void {
		$tmp = null;
		$suffix = pathinfo($path, PATHINFO_EXTENSION);
		if ($suffix !== 'wav') {
			$tmp = tempnam(sys_get_temp_dir(), 'ihfvm');
			$out = $tmp . '.wav';
			$cmd = 'sox ' . escapeshellarg($path) . ' -b 16 -e signed-integer ' . escapeshellarg($out) . ' 2>/dev/null';
			exec($cmd, $ignored, $code);
			@unlink($tmp);
			if ($code !== 0 || !is_file($out)) {
				@unlink($out);
				Response::json(415, ['error' => 'audio_format_not_supported']);
			}
			$path = $out;
			$tmp = $out;
		}
		header('Content-Type: audio/wav');
		header('Content-Length: ' . filesize($path));
		header('Cache-Control: no-store');
		header('X-Content-Type-Options: nosniff');
		header('Content-Disposition: ' . ($download ? 'attachment' : 'inline') . '; filename="voicemail-' . preg_replace('/[^A-Za-z0-9_-]/', '', $id) . '.wav"');
		readfile($path);
		if ($tmp !== null) {
			@unlink($tmp);
		}
		exit;
	}

	/** '"Dana Whitfield" <4165550177>' → ['Dana Whitfield', '4165550177']. */
	public static function splitCallerId(string $raw): array {
		$raw = trim($raw);
		if (preg_match('/^"?([^"<]*?)"?\s*<([^>]*)>$/', $raw, $m)) {
			$name = trim($m[1]);
			$number = trim($m[2]);
			return [$name === $number ? '' : $name, $number];
		}
		return ['', $raw];
	}
}
