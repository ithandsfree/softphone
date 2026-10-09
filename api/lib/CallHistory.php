<?php
/**
 * PBX call history and call recordings for a softphone line, with the same rules as UCP's Call History:
 *   - User Manager / UCP "Cdr" settings: enable, assigned (extensions, "self" = the user's default extension),
 *     playback and download (unset means allowed, as in UCP).
 *   - Rows come from the FreePBX CDR module (Cdr::getCalls), the query UCP runs for the extension.
 *   - A recording is only served through Cdr::getRecordByIDExtension, which returns it only when that call
 *     involved the extension. File paths never leave the server.
 */
class CallHistory {
	/** @var SmsGateway */
	private $sms;

	public function __construct(SmsGateway $sms) {
		$this->sms = $sms;
	}

	/**
	 * @return array{history:bool,playback:bool,download:bool}
	 */
	public function permissions(int $uid, string $ext): array {
		$none = ['history' => false, 'playback' => false, 'download' => false];
		if (!ctype_digit($ext)) {
			return $none;
		}
		try {
			$ucp = \FreePBX::Ucp();
			$enabled = $ucp->getCombinedSettingByID($uid, 'Cdr', 'enable');
			if (!$enabled) {
				return $none;
			}
			$assigned = (array)($ucp->getCombinedSettingByID($uid, 'Cdr', 'assigned') ?: []);
			if (in_array('self', $assigned, true)) {
				$user = \FreePBX::Userman()->getUserByID($uid);
				$assigned[] = (string)($user['default_extension'] ?? '');
			}
			if (!in_array($ext, array_map('strval', $assigned), true)) {
				return $none;
			}
			$playback = $ucp->getCombinedSettingByID($uid, 'Cdr', 'playback');
			$download = $ucp->getCombinedSettingByID($uid, 'Cdr', 'download');
			return [
				'history' => true,
				'playback' => $playback === null ? true : (bool)$playback,
				'download' => $download === null ? true : (bool)$download,
			];
		} catch (Throwable $e) {
			return $none;
		}
	}

	/**
	 * Calls for the extension, newest first. One row per call (ring groups write a CDR row per leg; the row
	 * with the recording, then the longest billsec, wins).
	 *
	 * @return list<array<string,mixed>>
	 */
	public function calls(string $ext, int $limit): array {
		$limit = max(1, min(200, $limit));
		$rows = \FreePBX::Cdr()->getCalls($ext, 1, 'date', 'desc', '', $limit * 3);
		$byCall = [];
		foreach ($rows as $r) {
			$key = (string)($r['linkedid'] ?? '') !== '' ? (string)$r['linkedid'] : (string)($r['uniqueid'] ?? '');
			if ($key === '') {
				continue;
			}
			$hasRec = !empty($r['recordingfile']);
			$keep = $byCall[$key] ?? null;
			if ($keep === null
				|| ($hasRec && empty($keep['recordingfile']))
				|| ($hasRec === !empty($keep['recordingfile']) && (int)($r['billsec'] ?? 0) > (int)($keep['billsec'] ?? 0))) {
				$byCall[$key] = $r;
			}
		}
		$out = [];
		foreach ($byCall as $r) {
			$src = (string)($r['src'] ?? '');
			$cnum = (string)($r['cnum'] ?? '');
			$channel = (string)($r['channel'] ?? '');
			$outbound = $src === $ext || $cnum === $ext || preg_match('#/' . preg_quote($ext, '#') . '-#', $channel) === 1;
			$peer = $outbound ? (string)($r['dst'] ?? '') : ($src !== '' ? $src : $cnum);
			$out[] = [
				'id' => (string)($r['uniqueid'] ?? ''),
				'at' => (int)($r['timestamp'] ?? strtotime((string)($r['calldate'] ?? 'now'))),
				'direction' => $outbound ? 'out' : 'in',
				'peer' => $peer,
				'peer_name' => $outbound ? (string)($r['dst_cnam'] ?? '') : (string)($r['cnam'] ?? ''),
				'disposition' => (string)($r['disposition'] ?? ''),
				'duration' => (int)($r['duration'] ?? 0),
				'billsec' => (int)($r['billsec'] ?? 0),
				'recording' => !empty($r['recordingfile']),
				'format' => !empty($r['recordingfile']) ? strtolower(pathinfo((string)$r['recordingfile'], PATHINFO_EXTENSION)) : null,
			];
		}
		usort($out, static function ($a, $b) {
			return $b['at'] <=> $a['at'];
		});
		return array_slice($out, 0, $limit);
	}

	/** Local path of a call's recording when it involved [ext]; null otherwise. */
	public function recordingPath(string $ext, string $id): ?string {
		if (!ctype_digit($ext) || !preg_match('/^[0-9]+[._][0-9]+$/', $id)) {
			return null;
		}
		try {
			// FreePBX 17 cdr: when no row matches the extension this indexes a boolean and Whoops turns the
			// warning into an exception. Not ours to fix; treat it as "no recording for this extension".
			$row = \FreePBX::Cdr()->getRecordByIDExtension($id, $ext);
		} catch (Throwable $e) {
			return null;
		}
		$path = is_array($row) ? (string)($row['recordingfile'] ?? '') : '';
		if ($path === '' || !is_file($path) || !is_readable($path)) {
			return null;
		}
		// Belt and braces: only files under the recordings directory are served.
		$cfg = \FreePBX::Config();
		$base = (string)($cfg->get('MIXMON_DIR') ?: rtrim((string)$cfg->get('ASTSPOOLDIR'), '/') . '/monitor');
		$real = realpath($path);
		$root = realpath($base);
		if ($real === false || $root === false || strpos($real, rtrim($root, '/') . '/') !== 0) {
			return null;
		}
		return $real;
	}

	/** Streams a recording and exits. */
	public static function stream(string $path, bool $download): void {
		$ext = strtolower(pathinfo($path, PATHINFO_EXTENSION));
		$types = ['wav' => 'audio/wav', 'mp3' => 'audio/mpeg', 'ogg' => 'audio/ogg', 'gsm' => 'audio/x-gsm', 'wav49' => 'audio/x-wav'];
		header('Content-Type: ' . ($types[$ext] ?? 'application/octet-stream'));
		header('Content-Length: ' . filesize($path));
		header('Cache-Control: no-store');
		header('X-Content-Type-Options: nosniff');
		header('Content-Disposition: ' . ($download ? 'attachment' : 'inline') . '; filename="' . basename($path) . '"');
		readfile($path);
		exit;
	}
}
