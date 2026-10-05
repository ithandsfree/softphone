<?php
/**
 * Softphone admin auth via FreePBX User Manager + optional admin_token fallback.
 *
 * Guideline (keep both):
 *   1) Primary — UM credentials + module setting / group membership
 *   2) Fallback — static admin_token when admin_token_fallback_enabled (default true)
 */
class AdminAuth {
	public const COOKIE = 'ihf_admin_session';
	public const HEADER = 'HTTP_X_IHF_ADMIN_SESSION';

	/** @var array */
	private $config;
	/** @var TokenStore */
	private $tokens;

	public function __construct(array $config, TokenStore $tokens) {
		$this->config = $config;
		$this->tokens = $tokens;
	}

	public function module(): string {
		$v = $this->config['admin_userman_module'] ?? 'ihfsoftphone';
		return is_string($v) && $v !== '' ? $v : 'ihfsoftphone';
	}

	public function setting(): string {
		$v = $this->config['admin_userman_setting'] ?? 'ihf_softphone_admin';
		return is_string($v) && $v !== '' ? $v : 'ihf_softphone_admin';
	}

	/** @return list<string> */
	public function groupNames(): array {
		$raw = $this->config['admin_userman_groups'] ?? ['IHF Softphone Admins'];
		if (!is_array($raw)) {
			$raw = [$raw];
		}
		$out = [];
		foreach ($raw as $g) {
			$g = trim((string)$g);
			if ($g !== '') {
				$out[] = $g;
			}
		}
		return $out;
	}

	public function adminTtlSeconds(): int {
		return max(300, (int)($this->config['admin_session_ttl_seconds'] ?? 28800)); // 8h
	}

	public function tokenFallbackEnabled(): bool {
		if (!array_key_exists('admin_token_fallback_enabled', $this->config)) {
			return true; // beta default: keep break-glass
		}
		$v = $this->config['admin_token_fallback_enabled'];
		return $v === true || $v === 1 || $v === '1' || $v === 'true';
	}

	/**
	 * True when the Userman user may operate /ihf-softphone/admin/.
	 * Grant via:
	 *   - module setting ihfsoftphone / ihf_softphone_admin (user or group), or
	 *   - membership in a configured Userman group (default "IHF Softphone Admins").
	 */
	public function userHasPermission(int $uid): bool {
		if ($uid <= 0) {
			return false;
		}
		$um = \FreePBX::Userman();
		$val = $um->getCombinedModuleSettingByID($uid, $this->module(), $this->setting());
		if ($this->isTruthy($val)) {
			return true;
		}
		$userGroups = $um->getGroupsByID($uid);
		if (!is_array($userGroups)) {
			$userGroups = [];
		}
		foreach ($this->groupNames() as $name) {
			$g = $um->getGroupByUsername($name);
			if (!empty($g['id']) && in_array((int)$g['id'], array_map('intval', $userGroups), true)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Login with Userman credentials; returns session payload or error array.
	 * @return array{ok:true,token:string,expires_in:int,user:array}|array{ok:false,error:string,detail?:string}
	 */
	public function login(string $username, string $password): array {
		$username = trim($username);
		if ($username === '' || $password === '') {
			return ['ok' => false, 'error' => 'username_and_password_required'];
		}
		$uid = \FreePBX::Userman()->checkCredentials($username, $password);
		if ($uid === false || $uid === null) {
			return ['ok' => false, 'error' => 'invalid_credentials'];
		}
		$uid = (int)$uid;
		if (!$this->userHasPermission($uid)) {
			return [
				'ok' => false,
				'error' => 'admin_forbidden',
				'detail' => 'User Manager account lacks ihf_softphone_admin (group or module setting)',
			];
		}
		$u = \FreePBX::Userman()->getUserByID($uid);
		$uname = (string)($u['username'] ?? $username);
		$token = $this->tokens->issue($uid, $uname, [
			'role' => 'admin',
			'ttl' => $this->adminTtlSeconds(),
		]);
		$this->setSessionCookie($token);
		return [
			'ok' => true,
			'token' => $token,
			'expires_in' => $this->adminTtlSeconds(),
			'auth' => 'userman',
			'user' => [
				'id' => $uid,
				'username' => $uname,
				'displayname' => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
				'email' => (string)($u['email'] ?? ''),
			],
			'permission' => [
				'module' => $this->module(),
				'setting' => $this->setting(),
				'groups' => $this->groupNames(),
			],
		];
	}

	/** Clear cookie + revoke bearer if present. */
	public function logout(): void {
		$token = $this->rawSessionToken();
		if ($token !== '') {
			$this->tokens->revoke($token);
		}
		$this->clearSessionCookie();
	}

	/**
	 * Resolve admin identity from short-lived UM session or break-glass token.
	 * @return array{auth:string,uid?:int,username?:string,exp?:int}|null
	 */
	public function authenticate(): ?array {
		$session = $this->sessionFromRequest();
		if ($session !== null) {
			return $session;
		}
		if ($this->tokenFallbackEnabled()) {
			$static = $this->staticTokenFromRequest();
			if ($static !== null) {
				return $static;
			}
		}
		return null;
	}

	/** @return array{auth:string,uid:int,username:string,exp:int}|null */
	public function sessionFromRequest(): ?array {
		$token = $this->rawSessionToken();
		if ($token === '') {
			return null;
		}
		$row = $this->tokens->get($token);
		if ($row === null) {
			return null;
		}
		if (($row['role'] ?? '') !== 'admin') {
			return null;
		}
		$uid = (int)($row['uid'] ?? 0);
		// Re-check permission so revoking UM access takes effect before TTL.
		if (!$this->userHasPermission($uid)) {
			$this->tokens->revoke($token);
			return null;
		}
		return [
			'auth' => 'userman',
			'uid' => $uid,
			'username' => (string)($row['username'] ?? ''),
			'exp' => (int)($row['exp'] ?? 0),
		];
	}

	/** @return array{auth:string,uid:int,username:string}|null */
	public function staticTokenFromRequest(): ?array {
		$expected = trim((string)($this->config['admin_token'] ?? ''));
		if ($expected === '') {
			return null;
		}
		$got = (string)($_SERVER['HTTP_X_IHF_ADMIN_TOKEN'] ?? '');
		if ($got === '') {
			$got = (string)($_GET['admin_token'] ?? '');
		}
		if ($got === '' || !hash_equals($expected, $got)) {
			return null;
		}
		return [
			'auth' => 'admin_token',
			'uid' => 0,
			'username' => 'admin_token',
		];
	}

	public function requireAuth(): array {
		$auth = $this->authenticate();
		if ($auth === null) {
			$detail = $this->tokenFallbackEnabled()
				? 'Userman admin login required (or break-glass admin_token)'
				: 'Userman admin login required';
			Response::json(401, ['error' => 'admin_unauthorized', 'detail' => $detail]);
		}
		return $auth;
	}

	public function setSessionCookie(string $token): void {
		$secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
			|| ((int)($_SERVER['SERVER_PORT'] ?? 0) === 443)
			|| ((int)($_SERVER['SERVER_PORT'] ?? 0) === 8443);
		$params = [
			'expires' => time() + $this->adminTtlSeconds(),
			'path' => $this->cookiePath(),
			'secure' => $secure,
			'httponly' => true,
			'samesite' => 'Lax',
		];
		setcookie(self::COOKIE, $token, $params);
	}

	public function clearSessionCookie(): void {
		$secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
			|| ((int)($_SERVER['SERVER_PORT'] ?? 0) === 443)
			|| ((int)($_SERVER['SERVER_PORT'] ?? 0) === 8443);
		$params = [
			'expires' => time() - 3600,
			'path' => $this->cookiePath(),
			'secure' => $secure,
			'httponly' => true,
			'samesite' => 'Lax',
		];
		setcookie(self::COOKIE, '', $params);
	}

	private function cookiePath(): string {
		return (string)($this->config['admin_cookie_path'] ?? '/ihf-softphone');
	}

	private function rawSessionToken(): string {
		$hdr = (string)($_SERVER[self::HEADER] ?? '');
		if ($hdr !== '') {
			return $hdr;
		}
		$authz = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
		if (preg_match('/^Bearer\s+(\S+)$/i', $authz, $m)) {
			// Only treat as admin session when also marked admin in store (checked later).
			return $m[1];
		}
		return (string)($_COOKIE[self::COOKIE] ?? '');
	}

	private function isTruthy($val): bool {
		return $val === true || $val === 1 || $val === '1' || $val === 'true' || $val === 'yes';
	}

	/**
	 * Ensure the named Userman group exists and carries the module permission.
	 * Safe to re-run (idempotent).
	 * @return array{ok:bool,group_id?:int,groupname?:string,created?:bool,detail?:string}
	 */
	public function ensurePermissionGroup(?string $groupname = null): array {
		$name = trim((string)($groupname ?? ($this->groupNames()[0] ?? 'IHF Softphone Admins')));
		if ($name === '') {
			return ['ok' => false, 'detail' => 'empty_group_name'];
		}
		$um = \FreePBX::Userman();
		$existing = $um->getGroupByUsername($name);
		$created = false;
		if (empty($existing['id'])) {
			$ret = $um->addGroup(
				$name,
				'IHF Phone softphone admin operators (assign + welcome email)',
				[]
			);
			if (empty($ret['status']) || empty($ret['id'])) {
				return [
					'ok' => false,
					'detail' => (string)($ret['message'] ?? 'addGroup_failed'),
				];
			}
			$gid = (int)$ret['id'];
			$created = true;
		} else {
			$gid = (int)$existing['id'];
		}
		$um->setModuleSettingByGID($gid, $this->module(), $this->setting(), true);
		return [
			'ok' => true,
			'group_id' => $gid,
			'groupname' => $name,
			'created' => $created,
			'module' => $this->module(),
			'setting' => $this->setting(),
		];
	}

	/** Add a Userman user to the admin group (by username). */
	public function grantUser(string $username, ?string $groupname = null): array {
		$username = trim($username);
		$um = \FreePBX::Userman();
		$user = $um->getUserByUsername($username);
		if (empty($user['id'])) {
			return ['ok' => false, 'error' => 'user_not_found', 'username' => $username];
		}
		$ensured = $this->ensurePermissionGroup($groupname);
		if (empty($ensured['ok'])) {
			return $ensured;
		}
		$gid = (int)$ensured['group_id'];
		$uid = (int)$user['id'];
		// User-level module setting is enough for getCombinedModuleSettingByID.
		$um->setModuleSettingByID($uid, $this->module(), $this->setting(), true);

		$group_updated = false;
		$group_error = null;
		try {
			$group = $um->getGroupByGID($gid);
			$users = array_map('intval', $group['users'] ?? []);
			if (!in_array($uid, $users, true)) {
				$users[] = $uid;
				// nodisplay=true still runs hooks; AMI may be required for Contact Manager.
				$ret = $um->updateGroup(
					$gid,
					$group['groupname'],
					$group['groupname'],
					$group['description'] ?? '',
					$users,
					true
				);
				$group_updated = !empty($ret['status']);
				if (!$group_updated) {
					$group_error = (string)($ret['message'] ?? 'updateGroup_failed');
				}
			} else {
				$group_updated = true;
			}
		} catch (\Throwable $e) {
			$group_error = $e->getMessage();
		}

		return [
			'ok' => true,
			'uid' => $uid,
			'username' => (string)$user['username'],
			'group_id' => $gid,
			'groupname' => (string)$ensured['groupname'],
			'group_updated' => $group_updated,
			'group_error' => $group_error,
			'has_permission' => $this->userHasPermission($uid),
		];
	}
}
