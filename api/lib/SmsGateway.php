<?php

class SmsGateway {
	private $fpbx;
	private $sms;

	public function __construct($fpbx) {
		$this->fpbx = $fpbx;
		$this->sms = $fpbx->Sms();
	}

	public static function normalizeDid(string $n): string {
		$n = preg_replace('/\D+/', '', $n) ?? '';
		if (strlen($n) === 10) {
			$n = '1' . $n;
		}
		return $n;
	}

	public function didsForUser(int $uid): array {
		$raw = $this->sms->getDIDs($uid) ?: [];
		$out = [];
		foreach ($raw as $did) {
			$did = self::normalizeDid((string)$did);
			$out[] = [
				'id' => $did,
				'did' => $did,
				'e164' => '+' . $did,
				'extension' => $this->guessExtensionForDid($uid, $did),
			];
		}
		return $out;
	}

	/**
	 * Best-effort: match Userman-assigned extension when labels/DIDs align.
	 * Returns null when unknown (Phase 3 provision will set this explicitly).
	 */
	public function guessExtensionForDid(int $uid, string $did): ?string {
		$did = self::normalizeDid($did);
		try {
			$um = \FreePBX::Userman();
			$assigned = [];
			if (method_exists($um, 'getAssignedDevices')) {
				$assigned = $um->getAssignedDevices($uid) ?: [];
			} elseif (method_exists($um, 'getUserExtensions')) {
				$assigned = $um->getUserExtensions($uid) ?: [];
			}
			// Prefer single assigned extension when user has one DID-linked device
			$exts = [];
			foreach ((array)$assigned as $key => $val) {
				if (is_string($key) && ctype_digit($key)) {
					$exts[] = $key;
				} elseif (is_string($val) && ctype_digit($val)) {
					$exts[] = $val;
				} elseif (is_array($val)) {
					foreach (['default_extension', 'extension', 'ext'] as $k) {
						if (!empty($val[$k]) && ctype_digit((string)$val[$k])) {
							$exts[] = (string)$val[$k];
						}
					}
				}
			}
			$exts = array_values(array_unique($exts));
			if (count($exts) === 1) {
				return $exts[0];
			}
		} catch (Throwable $e) {
			// ignore — capability still works without extension
		}
		return null;
	}

	/** FreePBX AstDB DND/<ext> = YES when Do Not Disturb is on. */
	public function isDndActive(?string $extension): bool {
		if ($extension === null || $extension === '') {
			return false;
		}
		try {
			$val = $this->fpbx->astman->database_get('DND', $extension);
			return strtoupper(trim((string)$val)) === 'YES';
		} catch (Throwable $e) {
			return false;
		}
	}

	/**
	 * Resolve the FreePBX extension for a DID the user owns.
	 * Same mapping used for SIP credentials / capability DND reads.
	 */
	public function extensionForDid(int $uid, string $did): ?string {
		$did = self::normalizeDid($did);
		if ($did === '' || !$this->userOwnsDid($uid, $did)) {
			return null;
		}
		$ext = $this->guessExtensionForDid($uid, $did);
		return ($ext !== null && $ext !== '') ? (string)$ext : null;
	}

	/**
	 * Set FreePBX Do Not Disturb for an extension (AstDB + Custom:DND device state).
	 * Matches *78 / *76 so device state Custom:DND{ext} stays in sync.
	 *
	 * @return array{ok:bool,enabled:bool,extension:?string,error?:string}
	 */
	public function setDnd(int $uid, string $did, bool $enabled): array {
		$ext = $this->extensionForDid($uid, $did);
		if ($ext === null) {
			return ['ok' => false, 'enabled' => false, 'extension' => null, 'error' => 'extension_unknown'];
		}
		try {
			// Prefer FreePBX Donotdisturb module when present (keeps UCP / BLF in sync).
			if (method_exists($this->fpbx, 'Donotdisturb')) {
				$dnd = $this->fpbx->Donotdisturb();
				if (is_object($dnd) && method_exists($dnd, 'set')) {
					$dnd->set($ext, $enabled ? 'YES' : '');
					$active = $this->isDndActive($ext);
					return ['ok' => true, 'enabled' => $active, 'extension' => $ext];
				}
			}
		} catch (Throwable $e) {
			// fall through to AstDB + DEVICE_STATE
		}
		try {
			$astman = $this->fpbx->astman;
			if ($enabled) {
				$astman->database_put('DND', $ext, 'YES');
				$this->setCustomDndState($ext, 'BUSY');
			} else {
				$astman->database_del('DND', $ext);
				$this->setCustomDndState($ext, 'NOT_INUSE');
			}
			$active = $this->isDndActive($ext);
			return ['ok' => true, 'enabled' => $active, 'extension' => $ext];
		} catch (Throwable $e) {
			return [
				'ok' => false,
				'enabled' => $this->isDndActive($ext),
				'extension' => $ext,
				'error' => 'dnd_set_failed',
			];
		}
	}

	/** Project FreePBX Custom:DND{ext} device state (BUSY = DND on). */
	private function setCustomDndState(string $extension, string $state): void {
		$astman = $this->fpbx->astman;
		$func = 'DEVICE_STATE';
		try {
			if (method_exists($this->fpbx, 'Config')) {
				$cfg = $this->fpbx->Config()->get('AST_FUNC_DEVICE_STATE');
				if (is_string($cfg) && $cfg !== '') {
					$func = $cfg;
				}
			}
		} catch (Throwable $e) {
			// keep DEVICE_STATE
		}
		try {
			if (method_exists($astman, 'set_global')) {
				$astman->set_global($func . '(Custom:DND' . $extension . ')', $state);
				return;
			}
		} catch (Throwable $e) {
			// try AMI Command below
		}
		try {
			$astman->Command('devstate change Custom:DND' . $extension . ' ' . $state);
		} catch (Throwable $e) {
			// best-effort — AstDB alone still gates FreePBX call flow
		}
	}

	public function userOwnsDid(int $uid, string $did): bool {
		$did = self::normalizeDid($did);
		foreach ($this->didsForUser($uid) as $row) {
			if ($row['did'] === $did) {
				return true;
			}
		}
		return false;
	}

	public function threads(int $uid, string $did, int $offset = 0, int $limit = 50): array {
		$did = self::normalizeDid($did);
		$data = $this->sms->getUserConversationsByDID($uid, $did, '', 'desc', 'timestamp', $offset, $limit);
		$rows = $data['conversations'] ?? $data['rows'] ?? [];
		if (!is_array($rows)) {
			$rows = [];
		}
		$threads = [];
		foreach ($rows as $r) {
			$local = self::normalizeDid((string)($r['localdid'] ?? $did));
			$peer = self::normalizeDid((string)($r['remotedid'] ?? ''));
			// Fallbacks if adaptor uses from/to relative to direction
			if ($peer === '' || $peer === $local) {
				$a = self::normalizeDid((string)($r['from'] ?? ''));
				$b = self::normalizeDid((string)($r['to'] ?? ''));
				if ($a === $local) {
					$peer = $b;
				} elseif ($b === $local) {
					$peer = $a;
				} else {
					$peer = $a !== '' ? $a : $b;
				}
			}
			$rawId = $r['threadid'] ?? $r['id'] ?? null;
			$rawAt = $r['timestamp'] ?? $r['utime'] ?? $r['date'] ?? null;
			$rawSnippet = $r['body'] ?? $r['message'] ?? null;
			$threads[] = [
				// Always stringify ids/timestamps so Android kotlinx.serialization
				// never sees a bare number where a string field is declared.
				'thread_id' => $rawId === null || $rawId === '' ? null : (string)$rawId,
				'peer' => $peer,
				'local_did' => $local,
				'last_message_at' => $rawAt === null || $rawAt === '' ? null : (string)$rawAt,
				'snippet' => $rawSnippet === null ? null : (string)$rawSnippet,
				'direction' => isset($r['direction']) ? (string)$r['direction'] : null,
			];
		}
		$threads = $this->attachSnippets($threads);
		$threads = $this->attachUnreadCounts($threads);
		$totalUnread = 0;
		foreach ($threads as $t) {
			$totalUnread += (int)($t['unread'] ?? 0);
		}
		return [
			// Integer count (not a quoted string) — Android ThreadsResponse.total is Int.
			'total' => (int)($data['total'] ?? count($threads)),
			'total_unread' => $totalUnread,
			'threads' => $threads,
		];
	}

	/**
	 * FreePBX sms_messages.read is 0/1. Count inbound unread per thread so the
	 * softphone can badge Messages without a second round-trip.
	 *
	 * @param list<array<string,mixed>> $threads
	 * @return list<array<string,mixed>>
	 */
	private function attachUnreadCounts(array $threads): array
	{
		$ids = [];
		foreach ($threads as $t) {
			if (!empty($t['thread_id'])) {
				$ids[] = (string)$t['thread_id'];
			}
		}
		$ids = array_values(array_unique($ids));
		$counts = [];
		if ($ids) {
			try {
				$db = \FreePBX::Database();
				$place = implode(',', array_fill(0, count($ids), '?'));
				$sql = "SELECT threadid, COUNT(*) AS unread FROM sms_messages " .
					"WHERE threadid IN ($place) AND direction = 'in' AND `read` = 0 " .
					'GROUP BY threadid';
				$stmt = $db->prepare($sql);
				$stmt->execute($ids);
				foreach ($stmt->fetchAll(\PDO::FETCH_ASSOC) ?: [] as $row) {
					$counts[(string)$row['threadid']] = (int)$row['unread'];
				}
			} catch (Throwable $e) {
				// leave unread at 0
			}
		}
		foreach ($threads as &$t) {
			$tid = (string)($t['thread_id'] ?? '');
			$t['unread'] = $counts[$tid] ?? 0;
		}
		unset($t);
		return $threads;
	}

	/**
	 * Mark inbound messages in a conversation as read (UCP parity).
	 */
	public function markThreadRead(int $uid, string $did, string $peer): int
	{
		$did = self::normalizeDid($did);
		$peer = self::normalizeDid($peer);
		if ($did === '' || $peer === '') {
			return 0;
		}
		try {
			$db = \FreePBX::Database();
			// Resolve thread via either DID orientation FreePBX stores.
			$sql = 'UPDATE sms_messages SET `read` = 1 ' .
				'WHERE direction = \'in\' AND `read` = 0 AND (' .
				'(`from` = ? AND `to` = ?) OR (`from` = ? AND `to` = ?)' .
				')';
			$stmt = $db->prepare($sql);
			$stmt->execute([$peer, $did, $did, $peer]);
			return (int)$stmt->rowCount();
		} catch (Throwable $e) {
			return 0;
		}
	}

	/**
	 * Mark all inbound unread for one of the user's DIDs (current inbox line).
	 */
	public function markDidRead(int $uid, string $did): int
	{
		$did = self::normalizeDid($did);
		if ($did === '' || !$this->userOwnsDid($uid, $did)) {
			return 0;
		}
		try {
			$db = \FreePBX::Database();
			$sql = 'UPDATE sms_messages SET `read` = 1 ' .
				'WHERE direction = \'in\' AND `read` = 0 AND (`to` = ? OR `from` = ?)';
			$stmt = $db->prepare($sql);
			$stmt->execute([$did, $did]);
			return (int)$stmt->rowCount();
		} catch (Throwable $e) {
			return 0;
		}
	}

	/**
	 * Delete an entire conversation for a DID/peer pair (FreePBX sms_messages).
	 * Prefers threadid when known so we avoid FreePBX's ambiguous OR join.
	 *
	 * @return array{ok:bool,deleted:int,error?:string}
	 */
	public function deleteThread(int $uid, string $did, string $peer, ?string $threadId = null): array
	{
		$did = self::normalizeDid($did);
		$peer = self::normalizeDid($peer);
		if ($did === '' || $peer === '' || !$this->userOwnsDid($uid, $did)) {
			return ['ok' => false, 'deleted' => 0, 'error' => 'forbidden'];
		}
		try {
			$tid = $threadId !== null && $threadId !== '' ? (string)$threadId : $this->resolveThreadId($did, $peer);
			if ($tid !== null && $tid !== '') {
				$ok = (bool)$this->sms->deleteConversationsByThreadID($uid, $tid);
				// FreePBX join can miss rows; fall back to direct DID/peer delete.
				$n = $this->deleteMessagesForPair($did, $peer);
				return ['ok' => $ok || $n > 0, 'deleted' => $n];
			}
			$ok = (bool)$this->sms->deleteConversations($uid, $did, $peer, '');
			$n = $this->deleteMessagesForPair($did, $peer);
			return ['ok' => $ok || $n > 0, 'deleted' => $n];
		} catch (Throwable $e) {
			return ['ok' => false, 'deleted' => 0, 'error' => 'delete_failed'];
		}
	}

	/**
	 * Delete one SMS/MMS row (and its media) when it involves the user's DID.
	 *
	 * @return array{ok:bool,deleted:int,error?:string}
	 */
	public function deleteMessage(int $uid, string $did, int $messageId): array
	{
		$did = self::normalizeDid($did);
		if ($did === '' || $messageId <= 0 || !$this->userOwnsDid($uid, $did)) {
			return ['ok' => false, 'deleted' => 0, 'error' => 'forbidden'];
		}
		try {
			$db = \FreePBX::Database();
			$st = $db->prepare('SELECT id, `from`, `to` FROM sms_messages WHERE id = ?');
			$st->execute([$messageId]);
			$row = $st->fetch(\PDO::FETCH_ASSOC);
			if (!$row) {
				return ['ok' => false, 'deleted' => 0, 'error' => 'not_found'];
			}
			$from = self::normalizeDid((string)$row['from']);
			$to = self::normalizeDid((string)$row['to']);
			if ($from !== $did && $to !== $did) {
				return ['ok' => false, 'deleted' => 0, 'error' => 'forbidden'];
			}
			$db->prepare('DELETE FROM sms_media WHERE mid = ?')->execute([$messageId]);
			$del = $db->prepare('DELETE FROM sms_messages WHERE id = ?');
			$del->execute([$messageId]);
			return ['ok' => true, 'deleted' => (int)$del->rowCount()];
		} catch (Throwable $e) {
			return ['ok' => false, 'deleted' => 0, 'error' => 'delete_failed'];
		}
	}

	/**
	 * SIP extension + secret for a DID the bearer owns.
	 * Uses FreePBX Core::getDevice (same store as Asterisk PJSIP). Never log the secret.
	 *
	 * @return array{extension:string,secret:string,tech?:string}|null
	 */
	public function sipCredentialsForDid(int $uid, string $did): ?array
	{
		$ext = $this->extensionForDid($uid, $did);
		if ($ext === null || $ext === '') {
			return null;
		}
		try {
			$dev = \FreePBX::Core()->getDevice($ext);
			if (!is_array($dev) || empty($dev['secret'])) {
				return null;
			}
			return [
				'extension' => (string)$ext,
				'secret' => (string)$dev['secret'],
				'tech' => isset($dev['tech']) ? (string)$dev['tech'] : null,
			];
		} catch (Throwable $e) {
			return null;
		}
	}

	private function resolveThreadId(string $did, string $peer): ?string
	{
		try {
			$db = \FreePBX::Database();
			$sql = 'SELECT threadid FROM sms_messages WHERE ' .
				'((`from` = ? AND `to` = ?) OR (`from` = ? AND `to` = ?)) ' .
				'AND threadid IS NOT NULL AND threadid <> \'\' ORDER BY id DESC LIMIT 1';
			$st = $db->prepare($sql);
			$st->execute([$peer, $did, $did, $peer]);
			$tid = $st->fetchColumn();
			return $tid === false || $tid === null ? null : (string)$tid;
		} catch (Throwable $e) {
			return null;
		}
	}

	private function deleteMessagesForPair(string $did, string $peer): int
	{
		try {
			$db = \FreePBX::Database();
			// Remove media first for matching mids.
			$idsSql = 'SELECT id FROM sms_messages WHERE ' .
				'(`from` = ? AND `to` = ?) OR (`from` = ? AND `to` = ?)';
			$st = $db->prepare($idsSql);
			$st->execute([$peer, $did, $did, $peer]);
			$ids = array_map('intval', $st->fetchAll(\PDO::FETCH_COLUMN) ?: []);
			if ($ids) {
				$place = implode(',', array_fill(0, count($ids), '?'));
				$db->prepare("DELETE FROM sms_media WHERE mid IN ($place)")->execute($ids);
			}
			$del = $db->prepare(
				'DELETE FROM sms_messages WHERE (`from` = ? AND `to` = ?) OR (`from` = ? AND `to` = ?)'
			);
			$del->execute([$peer, $did, $did, $peer]);
			return (int)$del->rowCount();
		} catch (Throwable $e) {
			return 0;
		}
	}

	/**
	 * FreePBX `getUserConversationsByDID` returns thread headers only (threadid /
	 * remotedid / localdid / timestamp) — never the message body. Without this the
	 * app shows "(no preview)" on every row, so look up the newest message per
	 * thread in one query and fill `snippet` / `direction` from it.
	 */
	private function attachSnippets(array $threads): array
	{
		$ids = [];
		foreach ($threads as $t) {
			if (!empty($t['thread_id']) && $t['snippet'] === null) {
				$ids[] = $t['thread_id'];
			}
		}
		$ids = array_values(array_unique($ids));
		if (!$ids) {
			return $threads;
		}
		$latest = [];
		try {
			$db = \FreePBX::Database();
			$place = implode(',', array_fill(0, count($ids), '?'));
			$sql = 'SELECT m.threadid, m.body, m.direction, m.id ' .
				'FROM sms_messages m ' .
				'INNER JOIN (SELECT threadid, MAX(id) AS last_id FROM sms_messages ' .
				"WHERE threadid IN ($place) GROUP BY threadid) l " .
				'ON l.threadid = m.threadid AND l.last_id = m.id';
			$stmt = $db->prepare($sql);
			$stmt->execute($ids);
			foreach ($stmt->fetchAll(\PDO::FETCH_ASSOC) ?: [] as $row) {
				$latest[(string)$row['threadid']] = $row;
			}
		} catch (Throwable $e) {
			return $threads;
		}
		foreach ($threads as &$t) {
			$row = $latest[(string)($t['thread_id'] ?? '')] ?? null;
			if ($row === null) {
				continue;
			}
			$body = trim(preg_replace('/\s+/u', ' ', (string)($row['body'] ?? '')) ?? '');
			if ($body === '') {
				$body = $this->hasMedia((int)$row['id']) ? 'Attachment' : '';
			}
			if (mb_strlen($body) > 160) {
				$body = mb_substr($body, 0, 159) . '…';
			}
			$t['snippet'] = $body === '' ? null : $body;
			$t['direction'] = $row['direction'] !== null ? (string)$row['direction'] : $t['direction'];
		}
		unset($t);
		return $threads;
	}

	private function hasMedia(int $messageId): bool
	{
		if ($messageId <= 0) {
			return false;
		}
		try {
			$db = \FreePBX::Database();
			$stmt = $db->prepare('SELECT 1 FROM sms_media WHERE mid = ? LIMIT 1');
			$stmt->execute([$messageId]);
			return (bool)$stmt->fetchColumn();
		} catch (Throwable $e) {
			return false;
		}
	}

	public function messages(int $uid, string $fromDid, string $peer): array {
		$fromDid = self::normalizeDid($fromDid);
		$peer = self::normalizeDid($peer);
		$rows = $this->sms->getAllMessages($uid, $fromDid, $peer) ?: [];
		$out = [];
		foreach ($rows as $m) {
			$mid = (int)($m['id'] ?? 0);
			$media = [];
			foreach ($this->sms->getMediaByID($mid) ?: [] as $mm) {
				// FreePBX Sms::getMediaByID returns processed rows: type/link/data
				// Prefer DB `name` (what UCP media= uses); `link` can be a path/alias.
				$fname = basename((string)($mm['name'] ?? ''));
				if ($fname === '') {
					$fname = basename((string)($mm['link'] ?? ''));
				}
				$ext = strtolower(pathinfo($fname, PATHINFO_EXTENSION));
				if ($fname === '' || $ext === 'smil' || (strpos($fname, 'smil-') === 0 && $ext === 'xml')) {
					continue;
				}
				if (($mm['type'] ?? '') === 'text') {
					continue;
				}
				$media[] = [
					'name' => $fname,
					'type' => $mm['type'] ?? null,
					'url' => '/ihf-softphone/index.php/v1/media/' . rawurlencode($fname),
				];
			}
			$out[] = [
				'id' => $mid,
				'emid' => $m['emid'] ?? null,
				'direction' => $m['direction'] ?? null,
				'from' => self::normalizeDid((string)($m['from'] ?? '')),
				'to' => self::normalizeDid((string)($m['to'] ?? '')),
				'body' => $m['body'] ?? '',
				'timestamp' => $m['timestamp'] ?? null,
				'datetime' => $m['tx_rx_datetime'] ?? null,
				'media' => $media,
			];
		}
		return $out;
	}

	public function sendText(int $uid, string $fromDid, string $to, string $message): array {
		$fromDid = self::normalizeDid($fromDid);
		$to = self::normalizeDid($to);
		$adaptor = $this->sms->getAdaptor($fromDid);
		if (!is_object($adaptor)) {
			return ['ok' => false, 'error' => 'adaptor_not_loaded'];
		}
		$u = \FreePBX::Userman()->getUserByID($uid);
		$name = !empty($u['fname']) ? $u['fname'] : ($u['username'] ?? 'user');
		$o = $adaptor->sendMessage($to, $fromDid, $name, $message);
		if (!empty($o['status'])) {
			return [
				'ok' => true,
				'id' => $o['id'] ?? null,
				'emid' => $o['emid'] ?? null,
			];
		}
		return [
			'ok' => false,
			'error' => 'send_failed',
			'message' => strip_tags((string)($o['message'] ?? 'unknown')),
		];
	}

	public function sendMedia(int $uid, string $fromDid, string $to, array $file): array {
		$fromDid = self::normalizeDid($fromDid);
		$to = self::normalizeDid($to);
		if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
			return ['ok' => false, 'error' => 'upload_error', 'code' => $file['error'] ?? null];
		}
		$extension = strtolower(pathinfo($file['name'], PATHINFO_EXTENSION));
		$supported = ['png','jpg','jpeg','gif','tiff','pdf','vcf','mp3','wav','ogg','mov','avi','mp4','m4a','ical','ics'];
		if (!in_array($extension, $supported, true)) {
			return ['ok' => false, 'error' => 'unsupported_file_type'];
		}
		if (($file['size'] ?? 0) > 1500000) {
			return ['ok' => false, 'error' => 'file_too_large'];
		}
		$tmpPath = \FreePBX::Config()->get('ASTSPOOLDIR') . '/tmp';
		if (!is_dir($tmpPath)) {
			mkdir($tmpPath, 0777, true);
		}
		$fid = uniqid('sms');
		$dest = $tmpPath . '/' . $fid . '-' . preg_replace('/[^a-zA-Z0-9._-]/', '_', $file['name']);
		if (!move_uploaded_file($file['tmp_name'], $dest)) {
			return ['ok' => false, 'error' => 'move_failed'];
		}
		$adaptor = $this->sms->getAdaptor($fromDid);
		if (!is_object($adaptor)) {
			@unlink($dest);
			return ['ok' => false, 'error' => 'adaptor_not_loaded'];
		}
		$u = \FreePBX::Userman()->getUserByID($uid);
		$name = !empty($u['fname']) ? $u['fname'] : ($u['username'] ?? 'user');
		$o = $adaptor->sendMedia($to, $fromDid, $name, '', [$dest]);
		@unlink($dest);
		if (!empty($o['status'])) {
			return [
				'ok' => true,
				'id' => $o['id'] ?? null,
				'emid' => $o['emid'] ?? null,
			];
		}
		return [
			'ok' => false,
			'error' => 'send_media_failed',
			'message' => strip_tags((string)($o['message'] ?? 'unknown')),
		];
	}

	public function streamMedia(int $uid, string $name): void {
		$name = basename(urldecode($name));
		$db = \FreePBX::Database();
		$stmt = $db->prepare('SELECT mid, name, raw FROM sms_media WHERE name = ?');
		$stmt->execute([$name]);
		$row = $stmt->fetch(\PDO::FETCH_ASSOC);
		// Fallback: some rows store a prefixed/aliased name; try suffix match once.
		if (!$row && $name !== '') {
			$stmt = $db->prepare('SELECT mid, name, raw FROM sms_media WHERE name LIKE ? ORDER BY mid DESC LIMIT 1');
			$stmt->execute(['%' . $name]);
			$row = $stmt->fetch(\PDO::FETCH_ASSOC);
		}
		if (!$row) {
			Response::json(404, ['error' => 'not_found']);
		}
		$mid = (int)$row['mid'];
		$msg = $this->sms->getMessageByID($mid);
		if (empty($msg)) {
			Response::json(404, ['error' => 'message_not_found']);
		}
		$userDids = array_map(function ($r) { return $r['did']; }, $this->didsForUser($uid));
		$from = self::normalizeDid((string)$msg['from']);
		$to = self::normalizeDid((string)$msg['to']);
		if (!in_array($from, $userDids, true) && !in_array($to, $userDids, true)) {
			Response::json(403, ['error' => 'forbidden']);
		}
		$raw = $row['raw'];
		if ($raw === null || $raw === '') {
			Response::json(404, ['error' => 'empty_media']);
		}
		$finfo = new \finfo(FILEINFO_MIME_TYPE);
		$mime = $finfo->buffer($raw) ?: 'application/octet-stream';
		// Help image decoders when finfo is overly generic
		$ext = strtolower(pathinfo((string)$row['name'], PATHINFO_EXTENSION));
		if ($mime === 'application/octet-stream' || $mime === 'text/plain') {
			if ($ext === 'jpg' || $ext === 'jpeg') {
				$mime = 'image/jpeg';
			} elseif ($ext === 'png') {
				$mime = 'image/png';
			} elseif ($ext === 'gif') {
				$mime = 'image/gif';
			} elseif ($ext === 'webp') {
				$mime = 'image/webp';
			}
		}
		header('Content-Type: ' . $mime);
		header('Content-Length: ' . strlen($raw));
		header('Cache-Control: private, max-age=60');
		echo $raw;
	}
}
