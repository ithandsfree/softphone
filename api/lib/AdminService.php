<?php
/**
 * Softphone admin ops: resolve extension → Userman user, issue enrol token,
 * write HTTPS enrol landing, record assignment, optional welcome email.
 * No charging / licensing management.
 */
class AdminService {
	private $config;
	private $tokens;
	private $assignments;
	private $mailer;
	private $sms;

	public function __construct(array $config, TokenStore $tokens, AssignmentStore $assignments, WelcomeMailer $mailer, SmsGateway $sms) {
		$this->config = $config;
		$this->tokens = $tokens;
		$this->assignments = $assignments;
		$this->mailer = $mailer;
		$this->sms = $sms;
	}

	/** Catalog of Userman users with assigned extensions + SMS DIDs. */
	public function catalog(): array {
		$um = \FreePBX::Userman();
		$users = $um->getAllUsers() ?: [];
		$out = [];
		foreach ($users as $u) {
			$uid = (int)($u['id'] ?? 0);
			if ($uid <= 0) {
				continue;
			}
			$username = (string)($u['username'] ?? '');
			$email = (string)($u['email'] ?? '');
			if ($email === '' && strpos($username, '@') !== false) {
				$email = $username;
			}
			$exts = $this->devicesForUser($um, $uid);
			$dids = [];
			try {
				foreach ($this->sms->didsForUser($uid) as $row) {
					$dids[] = [
						'did' => $row['did'] ?? '',
						'extension' => $row['extension'] ?? null,
					];
				}
			} catch (Throwable $e) {
				$dids = [];
			}
			if ($exts === [] && $dids === []) {
				continue;
			}
			$out[] = [
				'uid' => $uid,
				'username' => $username,
				'displayname' => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
				'email' => $email,
				'default_extension' => (string)($u['default_extension'] ?? ($exts[0] ?? '')),
				'extensions' => $exts,
				'dids' => $dids,
			];
		}
		usort($out, static function ($a, $b) {
			return strnatcasecmp($a['default_extension'] ?: $a['username'], $b['default_extension'] ?: $b['username']);
		});
		return $out;
	}

	/**
	 * Assign softphone to an extension: issue token, enrol HTML, store mapping,
	 * optionally email welcome with install + enrol HTTPS links.
	 *
	 * @param array{extension:string,username?:string,email?:string,label?:string,send_email?:bool,install_url?:string} $in
	 */
	public function assign(array $in): array {
		$ext = AssignmentStore::normExt((string)($in['extension'] ?? ''));
		if ($ext === '') {
			return ['ok' => false, 'error' => 'extension_required'];
		}

		$resolved = $this->resolveUser($ext, isset($in['username']) ? (string)$in['username'] : null);
		if ($resolved === null) {
			return ['ok' => false, 'error' => 'user_not_found', 'detail' => "No Userman user for extension $ext"];
		}
		$uid = (int)$resolved['uid'];
		$username = (string)$resolved['username'];
		$email = trim((string)($in['email'] ?? $resolved['email'] ?? ''));
		$label = trim((string)($in['label'] ?? ("ext $ext")));
		$sendEmail = !empty($in['send_email']);

		$did = $this->primaryDidForExt($uid, $ext);
		$ttl = (int)($this->config['token_ttl_seconds'] ?? 86400);
		$token = $this->tokens->issue($uid, $username);
		$exp = time() + $ttl;
		$expIso = gmdate('c', $exp);

		$publicBase = rtrim((string)($this->config['public_base'] ?? 'https://pbx.example.com/ihf-softphone'), '/');
		$enrolUrl = $publicBase . '/enrol/' . $token . '/';
		$deep = 'ihfphone://enroll/' . $token;
		$installUrl = trim((string)($in['install_url'] ?? ''));
		if ($installUrl === '') {
			$installUrl = $this->defaultInstallUrl($publicBase);
		}

		$this->writeEnrolPage($token, $deep, $label, $expIso, $installUrl);

		$row = [
			'extension' => $ext,
			'uid' => $uid,
			'username' => $username,
			'email' => $email,
			'label' => $label,
			'did' => $did,
			'token' => $token,
			'token_exp' => $exp,
			'token_exp_iso' => $expIso,
			'enrol_url' => $enrolUrl,
			'deep_link' => $deep,
			'install_url' => $installUrl,
			'email_sent_at' => null,
			'email_last_status' => null,
			'license' => null, // beta: no charging / licensing
		];

		$mailResult = null;
		if ($sendEmail) {
			if ($email === '') {
				return ['ok' => false, 'error' => 'email_required', 'detail' => 'send_email set but no email address', 'assignment' => $row];
			}
			$mailResult = $this->mailer->sendWelcome($email, [
				'extension' => $ext,
				'label' => $label,
				'did' => $did ?? '',
				'enrol_url' => $enrolUrl,
				'deep_link' => $deep,
				'install_url' => $installUrl,
				'expires_iso' => $expIso,
			]);
			$row['email_sent_at'] = !empty($mailResult['ok']) ? time() : null;
			$row['email_last_status'] = $mailResult;
		}

		$stored = $this->assignments->put($ext, $row);
		return [
			'ok' => true,
			'assignment' => $this->publicAssignment($stored),
			'email' => $mailResult,
		];
	}

	/**
	 * Public self-service: look up Userman by work email, issue enrol token(s)
	 * for every extension on that account (including additional / second lines),
	 * email HTTPS setup link(s). Safe to call again after a line is already
	 * enrolled — fresh tokens cover remaining DIDs on the device. Never confirms
	 * whether the address exists.
	 *
	 * @return array{ok:bool,sent?:bool,error?:string}
	 */
	public function requestEnrolByEmail(string $email): array {
		$email = strtolower(trim($email));
		if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) {
			return ['ok' => false, 'error' => 'invalid_email'];
		}

		$matches = $this->usersForEmail($email);
		$sent = false;
		foreach ($matches as $user) {
			$exts = $user['extensions'];
			if ($exts === []) {
				continue;
			}
			// Prefer default extension first for the emailed link; still issue
			// tokens for every other extension so admin rows + second-line
			// enrol stay current. Token is uid-scoped — the app binds the next
			// unused DID when the user already has a line on the phone.
			$ordered = $exts;
			$def = $user['default_extension'];
			if ($def !== '' && in_array($def, $exts, true)) {
				$ordered = array_values(array_unique(array_merge([$def], $exts)));
			}
			$extraUrls = [];
			$primaryResult = null;
			foreach ($ordered as $i => $ext) {
				$label = ($i === 0 && $user['displayname'] !== '')
					? $user['displayname']
					: ("ext $ext");
				$result = $this->assign([
					'extension' => $ext,
					'username' => $user['username'],
					'email' => $email,
					'label' => $label,
					// Email once (primary). Extra extension URLs are appended
					// into that same welcome message when present.
					'send_email' => false,
				]);
				if ($i === 0) {
					$primaryResult = $result;
				} elseif (!empty($result['ok']) && !empty($result['assignment']['enrol_url'])) {
					$extraUrls[] = [
						'extension' => $ext,
						'label' => $label,
						'did' => (string)($result['assignment']['did'] ?? ''),
						'enrol_url' => (string)$result['assignment']['enrol_url'],
					];
				}
			}
			if ($primaryResult !== null && !empty($primaryResult['ok']) && !empty($primaryResult['assignment'])) {
				$mailPayload = [
					'extension' => (string)($primaryResult['assignment']['extension'] ?? ''),
					'label' => (string)($primaryResult['assignment']['label'] ?? ''),
					'did' => (string)($primaryResult['assignment']['did'] ?? ''),
					'enrol_url' => (string)($primaryResult['assignment']['enrol_url'] ?? ''),
					'deep_link' => (string)($primaryResult['assignment']['deep_link'] ?? ''),
					'install_url' => (string)($primaryResult['assignment']['install_url'] ?? ''),
					'expires_iso' => (string)($primaryResult['assignment']['token_exp_iso'] ?? ''),
					'extra_lines' => $extraUrls,
				];
				if ($email !== '' && $mailPayload['enrol_url'] !== '') {
					$mailResult = $this->mailer->sendWelcome($email, $mailPayload);
					if (!empty($mailResult['ok'])) {
						$sent = true;
						$extKey = AssignmentStore::normExt((string)$mailPayload['extension']);
						if ($extKey !== '') {
							$row = $this->assignments->get($extKey);
							if (is_array($row)) {
								$row['email_sent_at'] = time();
								$row['email_last_status'] = $mailResult;
								$this->assignments->put($extKey, $row);
							}
						}
					}
				}
			}
		}

		// Generic success either way — no account enumeration.
		return ['ok' => true, 'sent' => $sent];
	}

	/** @return list<array{uid:int,username:string,displayname:string,default_extension:string,extensions:list<string>}> */
	private function usersForEmail(string $emailNorm): array {
		$um = \FreePBX::Userman();
		$out = [];
		foreach ($um->getAllUsers() ?: [] as $u) {
			$uid = (int)($u['id'] ?? 0);
			if ($uid <= 0) {
				continue;
			}
			$username = (string)($u['username'] ?? '');
			$userEmail = strtolower(trim((string)($u['email'] ?? '')));
			if ($userEmail === '' && strpos($username, '@') !== false) {
				$userEmail = strtolower(trim($username));
			}
			if ($userEmail !== $emailNorm && strtolower($username) !== $emailNorm) {
				continue;
			}
			$exts = $this->devicesForUser($um, $uid);
			$def = (string)($u['default_extension'] ?? '');
			if ($def !== '' && ctype_digit($def) && !in_array($def, $exts, true)) {
				$exts[] = $def;
			}
			if ($exts === []) {
				continue;
			}
			$out[] = [
				'uid' => $uid,
				'username' => $username,
				'displayname' => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
				'default_extension' => $def,
				'extensions' => array_values(array_unique($exts)),
			];
		}
		return $out;
	}

	/** Resend welcome email for an existing assignment (re-issues token). */
	public function resendWelcome(string $ext, ?string $emailOverride = null): array {
		$existing = $this->assignments->get($ext);
		if ($existing === null) {
			return ['ok' => false, 'error' => 'assignment_not_found'];
		}
		$result = $this->assign([
			'extension' => $ext,
			'username' => $existing['username'] ?? null,
			'email' => $emailOverride ?: ($existing['email'] ?? ''),
			'label' => $existing['label'] ?? null,
			'send_email' => true,
		]);
		return $result;
	}

	public function listAssignments(): array {
		$out = [];
		foreach ($this->assignments->all() as $row) {
			$out[] = $this->publicAssignment($row);
		}
		return $out;
	}

	/** @return array<string,mixed> */
	private function publicAssignment(array $row): array {
		// Keep token in admin API responses (operator needs it); never put in email body as raw-only.
		return [
			'extension' => $row['extension'] ?? '',
			'uid' => $row['uid'] ?? null,
			'username' => $row['username'] ?? '',
			'email' => $row['email'] ?? '',
			'label' => $row['label'] ?? '',
			'did' => $row['did'] ?? null,
			'token' => $row['token'] ?? '',
			'token_exp' => $row['token_exp'] ?? null,
			'token_exp_iso' => $row['token_exp_iso'] ?? null,
			'enrol_url' => $row['enrol_url'] ?? '',
			'deep_link' => $row['deep_link'] ?? '',
			'install_url' => $row['install_url'] ?? '',
			'email_sent_at' => $row['email_sent_at'] ?? null,
			'email_last_status' => $row['email_last_status'] ?? null,
			'created_at' => $row['created_at'] ?? null,
			'updated_at' => $row['updated_at'] ?? null,
			'license' => null,
		];
	}

	private function resolveUser(string $ext, ?string $username): ?array {
		$um = \FreePBX::Userman();
		if ($username !== null && trim($username) !== '') {
			$u = $um->getUserByUsername(trim($username));
			if (!$u) {
				return null;
			}
			$email = (string)($u['email'] ?? '');
			$uname = (string)($u['username'] ?? '');
			if ($email === '' && strpos($uname, '@') !== false) {
				$email = $uname;
			}
			return [
				'uid' => (int)$u['id'],
				'username' => $uname,
				'email' => $email,
			];
		}
		foreach ($um->getAllUsers() ?: [] as $u) {
			$uid = (int)($u['id'] ?? 0);
			$devs = $this->devicesForUser($um, $uid);
			$def = (string)($u['default_extension'] ?? '');
			if (in_array($ext, $devs, true) || $def === $ext) {
				$email = (string)($u['email'] ?? '');
				$uname = (string)($u['username'] ?? '');
				if ($email === '' && strpos($uname, '@') !== false) {
					$email = $uname;
				}
				return ['uid' => $uid, 'username' => $uname, 'email' => $email];
			}
		}
		return null;
	}

	private function devicesForUser($um, int $uid): array {
		$assigned = [];
		try {
			if (method_exists($um, 'getAssignedDevices')) {
				$assigned = $um->getAssignedDevices($uid) ?: [];
			}
		} catch (Throwable $e) {
			$assigned = [];
		}
		$exts = [];
		foreach ((array)$assigned as $key => $val) {
			if (is_string($key) && ctype_digit($key)) {
				$exts[] = $key;
			} elseif (is_string($val) && ctype_digit($val)) {
				$exts[] = $val;
			} elseif (is_int($val) || (is_string($val) && preg_match('/^\d+$/', $val))) {
				$exts[] = (string)$val;
			}
		}
		// FreePBX often returns a plain list of extension strings
		if ($exts === [] && $assigned !== []) {
			foreach ($assigned as $val) {
				if (is_string($val) || is_int($val)) {
					$s = (string)$val;
					if (ctype_digit($s)) {
						$exts[] = $s;
					}
				}
			}
		}
		return array_values(array_unique($exts));
	}

	private function primaryDidForExt(int $uid, string $ext): ?string {
		try {
			foreach ($this->sms->didsForUser($uid) as $row) {
				if (($row['extension'] ?? null) === $ext && !empty($row['did'])) {
					return (string)$row['did'];
				}
			}
			$dids = $this->sms->didsForUser($uid);
			if (count($dids) === 1 && !empty($dids[0]['did'])) {
				return (string)$dids[0]['did'];
			}
		} catch (Throwable $e) {
			// fall through
		}
		return $this->sms->didForExtension($ext);
	}

	private function defaultInstallUrl(string $publicBase): string {
		$configured = trim((string)($this->config['install_url'] ?? ''));
		if ($configured !== '') {
			return $configured;
		}
		$publicBase = rtrim($publicBase, '/');
		// Stable sideload pointer: the publish script retargets dl/latest at each drop.
		// A non-empty install_url (Play listing) wins over this.
		foreach ([
			'/var/www/html/ihf-softphone/dl/latest',
			dirname(__DIR__) . '/dl/latest',
		] as $latest) {
			if (is_dir($latest)) {
				return $publicBase . '/dl/latest/';
			}
		}
		$candidates = [
			'/var/www/html/ihf-softphone/dl',
			dirname(__DIR__) . '/dl',
		];
		foreach ($candidates as $dir) {
			if (!is_dir($dir)) {
				continue;
			}
			$best = null;
			$bestCode = -1;
			$entries = @scandir($dir, SCANDIR_SORT_NONE) ?: [];
			foreach ($entries as $name) {
				if ($name === '.' || $name === '..' || $name === 'latest') {
					continue;
				}
				$path = $dir . '/' . $name;
				$index = $path . '/index.html';
				if (!is_dir($path) || !is_file($index)) {
					continue;
				}
				$html = (string)@file_get_contents($index);
				if (!preg_match('/version:\s*[0-9.]+(?:\s*\(code\s+(\d+))?/i', $html, $m)) {
					continue;
				}
				$code = isset($m[1]) && $m[1] !== '' ? (int)$m[1] : 0;
				if ($code >= $bestCode) {
					$bestCode = $code;
					$best = $name;
				}
			}
			if ($best !== null) {
				return $publicBase . '/dl/' . $best . '/';
			}
		}
		return $publicBase . '/';
	}

	private function writeEnrolPage(string $token, string $deep, string $label, string $expIso, string $installUrl): void {
		$template = dirname(__DIR__) . '/enrol/_template.html';
		if (!is_readable($template)) {
			throw new RuntimeException('enrol template missing');
		}
		$html = file_get_contents($template);
		$h = static function (string $v): string {
			return htmlspecialchars($v, ENT_QUOTES | ENT_HTML5, 'UTF-8');
		};
		// Only https:// for the install link; anything else (javascript:, data:) is dropped.
		$install = preg_match('#^https://#i', trim($installUrl)) ? trim($installUrl) : '';
		$product = (string)($this->config['mail_from_name'] ?? 'Softphone');
		$emblem = ltrim(trim((string)($this->config['mail_emblem'] ?? '')), '/');
		$emblemHtml = '';
		if ($emblem !== '' && strpos($emblem, '..') === false && preg_match('#^[A-Za-z0-9._/-]+\.(png|jpg|jpeg|gif|svg)$#i', $emblem)
			&& is_file(dirname(__DIR__) . '/' . $emblem)) {
			// The page lives at enrol/<token>/index.html, two levels below the BFF directory.
			$emblemHtml = '<img src="../../' . $h($emblem) . '" width="42" height="36" alt=""/>';
		}
		$html = str_replace(
			[
				'__TOKEN__', '__DEEP_LINK__', '__LABEL__', '__EXPIRES__', '__INSTALL_URL__',
				'__PRODUCT__', '__BRAND_LINE__', '__FOOTER_LINE__', '__EMBLEM__',
			],
			[
				$h($token), $h($deep), $h($label), $h($expIso), $h($install),
				$h($product), $h(strtoupper((string)($this->config['mail_brand_line'] ?? ''))),
				$h((string)($this->config['mail_footer_line'] ?? '')), $emblemHtml,
			],
			$html
		);

		$base = '/var/www/html/ihf-softphone/enrol';
		if (!is_dir($base)) {
			$base = dirname(__DIR__) . '/enrol';
		}
		$dir = $base . '/' . $token;
		if (!is_dir($dir)) {
			mkdir($dir, 0755, true);
		}
		file_put_contents($dir . '/index.html', $html);
		@chmod($dir . '/index.html', 0644);

		$ht = $base . '/.htaccess';
		if (!is_file($ht)) {
			$htBody = "Options -Indexes\nDirectoryIndex index.html\n"
				. "<IfModule mod_rewrite.c>\n  RewriteEngine Off\n</IfModule>\n";
			@file_put_contents($ht, $htBody);
		}
	}
}
