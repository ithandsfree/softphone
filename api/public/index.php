<?php
/**
 * IHF Softphone API — Option A BFF (spike)
 *
 * Runs on FreePBX host, bootstraps FreePBX, uses Userman + Sms module.
 * Does NOT expose UCP PHPSESSID to clients; issues its own bearer tokens.
 *
 * Deploy: see ../README.md (path /var/www/html/ihf-softphone/)
 */

declare(strict_types=1);

header('X-Content-Type-Options: nosniff');

$bootstrap_settings['freepbx_auth'] = false;
// Asterisk Manager required for SMS send (insert into sms_messages). Skipping
// astman caused: "Unable to Insert Message into DB Asterisk is not connected".
$bootstrap_settings['skip_astman'] = false;
include '/etc/freepbx.conf';

require dirname(__DIR__) . '/lib/Response.php';
require dirname(__DIR__) . '/lib/TokenStore.php';
require dirname(__DIR__) . '/lib/SmsGateway.php';
require dirname(__DIR__) . '/lib/Entitlements.php';
require dirname(__DIR__) . '/lib/AssignmentStore.php';
require dirname(__DIR__) . '/lib/WelcomeMailer.php';
require dirname(__DIR__) . '/lib/AdminService.php';
require dirname(__DIR__) . '/lib/AdminAuth.php';
require dirname(__DIR__) . '/lib/EnrolRequestLimiter.php';

$configFile = dirname(__DIR__) . '/config.php';
if (!is_readable($configFile)) {
	Response::json(500, ['error' => 'config.php missing; copy config.example.php']);
}
$config = require $configFile;

// CORS for local app testing (tighten later)
if (!empty($config['cors_origin'])) {
	header('Access-Control-Allow-Origin: ' . $config['cors_origin']);
	header('Access-Control-Allow-Headers: Authorization, Content-Type, X-IHF-Token, X-IHF-Admin-Token, X-IHF-Admin-Session');
	header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
	header('Access-Control-Allow-Credentials: true');
}
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
	http_response_code(204);
	exit;
}

$path = parse_path();
$method = $_SERVER['REQUEST_METHOD'];
$smsGw = new SmsGateway(\FreePBX::create());
$tokens = new TokenStore($config['token_file'], (int)$config['token_ttl_seconds']);
$assignments = new AssignmentStore((string)($config['assignments_file'] ?? '/var/spool/asterisk/ihf-softphone/assignments.json'));
$mailer = new WelcomeMailer(
	(string)($config['mail_from'] ?? 'notify@pbx.example.com'),
	(string)($config['mail_from_name'] ?? 'Softphone')
);
$admin = new AdminService($config, $tokens, $assignments, $mailer, $smsGw);
$adminAuth = new AdminAuth($config, $tokens);
$enrolLimiter = new EnrolRequestLimiter(
	(string)($config['enrol_request_limit_file'] ?? '/var/spool/asterisk/ihf-softphone/enrol-request-limits.json'),
	(int)($config['enrol_request_email_cooldown_seconds'] ?? 30),
	(int)($config['enrol_request_ip_max'] ?? 8),
	(int)($config['enrol_request_ip_window_seconds'] ?? 3600)
);

try {
	// Public routes first
	if ($method === 'GET' && ($path === '/v1/health' || $path === '/health' || $path === '/')) {
		Response::json(200, [
			'ok' => true,
			'service' => 'ihf-softphone-api',
			'path' => $path,
			'branding' => [
				'default_skin' => (string)($config['default_skin'] ?? 'ihf_night'),
				'brand_mark' => (string)($config['brand_mark'] ?? 'IHF'),
			],
			'admin' => [
				'ui' => '/ihf-softphone/admin/',
				'assign' => true,
				'licensing' => false,
				'auth' => 'userman',
				'permission' => [
					'module' => $adminAuth->module(),
					'setting' => $adminAuth->setting(),
					'groups' => $adminAuth->groupNames(),
				],
				'token_fallback' => $adminAuth->tokenFallbackEnabled(),
			],
			'request_enrol' => true,
		]);
	}

	// Self-service "Email me a setup link" — no auth; generic check-inbox UX.
	if ($method === 'POST' && ($path === '/v1/request-enrol' || $path === '/request-enrol')) {
		$body = Response::jsonBody();
		$email = strtolower(trim((string)($body['email'] ?? '')));
		if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) {
			Response::json(400, ['error' => 'invalid_email']);
		}
		$ip = (string)($_SERVER['HTTP_X_FORWARDED_FOR'] ?? $_SERVER['REMOTE_ADDR'] ?? '');
		if (strpos($ip, ',') !== false) {
			$ip = trim(explode(',', $ip)[0]);
		}
		$limit = $enrolLimiter->allow($email, $ip);
		if (empty($limit['ok'])) {
			// Still generic — do not reveal whether the address exists.
			Response::json(200, [
				'ok' => true,
				'status' => 'check_inbox',
				'retry_after' => (int)($limit['retry_after'] ?? 30),
			]);
		}
		// Constant-ish work: always attempt lookup/send; response never enumerates.
		$admin->requestEnrolByEmail($email);
		Response::json(200, [
			'ok' => true,
			'status' => 'check_inbox',
			'retry_after' => (int)($config['enrol_request_email_cooldown_seconds'] ?? 30),
		]);
	}

	if (admin_route($method, $path, $config, $admin, $adminAuth)) {
		return;
	}
	route($method, $path, $config, $smsGw, $tokens);
} catch (Throwable $e) {
	Response::json(500, ['error' => 'internal_error', 'detail' => $e->getMessage()]);
}

function parse_path(): string {
	$uri = $_SERVER['REQUEST_URI'] ?? '/';
	$path = parse_url($uri, PHP_URL_PATH) ?: '/';
	// Strip deploy prefix …/ihf-softphone or …/ihf-softphone/index.php
	$path = preg_replace('#^.*/ihf-softphone(?:/index\.php)?#', '', $path) ?: '/';
	if (isset($_GET['r'])) {
		$path = (string)$_GET['r'];
	}
	return '/' . trim($path, '/');
}

/** @return bool true when the request was handled */
function admin_route(string $method, string $path, array $config, AdminService $admin, AdminAuth $adminAuth): bool {
	$isAdminPath = str_starts_with($path, '/v1/admin/') || str_starts_with($path, '/admin/');
	if (!$isAdminPath) {
		return false;
	}

	// Public admin auth endpoints (no prior session required).
	if ($method === 'POST' && ($path === '/v1/admin/login' || $path === '/admin/login')) {
		$body = Response::jsonBody();
		$result = $adminAuth->login(
			(string)($body['username'] ?? ''),
			(string)($body['password'] ?? '')
		);
		Response::json(!empty($result['ok']) ? 200 : (($result['error'] ?? '') === 'admin_forbidden' ? 403 : 401), $result);
	}
	if ($method === 'POST' && ($path === '/v1/admin/logout' || $path === '/admin/logout')) {
		$adminAuth->logout();
		Response::json(200, ['ok' => true]);
	}
	if ($method === 'GET' && ($path === '/v1/admin/me' || $path === '/admin/me')) {
		$auth = $adminAuth->authenticate();
		if ($auth === null) {
			Response::json(401, [
				'ok' => false,
				'error' => 'admin_unauthorized',
				'token_fallback' => $adminAuth->tokenFallbackEnabled(),
				'permission' => [
					'module' => $adminAuth->module(),
					'setting' => $adminAuth->setting(),
					'groups' => $adminAuth->groupNames(),
				],
			]);
		}
		Response::json(200, [
			'ok' => true,
			'auth' => $auth['auth'],
			'user' => [
				'id' => (int)($auth['uid'] ?? 0),
				'username' => (string)($auth['username'] ?? ''),
			],
			'expires_in' => isset($auth['exp']) ? max(0, (int)$auth['exp'] - time()) : null,
			'token_fallback' => $adminAuth->tokenFallbackEnabled(),
			'permission' => [
				'module' => $adminAuth->module(),
				'setting' => $adminAuth->setting(),
				'groups' => $adminAuth->groupNames(),
			],
		]);
	}

	if ($path !== '/v1/admin/catalog'
		&& $path !== '/v1/admin/assignments'
		&& $path !== '/v1/admin/assign'
		&& $path !== '/v1/admin/welcome-email'
		&& $path !== '/admin/catalog'
		&& $path !== '/admin/assignments'
		&& $path !== '/admin/assign'
		&& $path !== '/admin/welcome-email') {
		Response::json(404, ['error' => 'not_found', 'path' => $path]);
		return true;
	}

	$adminAuth->requireAuth();

	if ($method === 'GET' && ($path === '/v1/admin/catalog' || $path === '/admin/catalog')) {
		Response::json(200, ['ok' => true, 'users' => $admin->catalog()]);
	}
	if ($method === 'GET' && ($path === '/v1/admin/assignments' || $path === '/admin/assignments')) {
		Response::json(200, ['ok' => true, 'assignments' => $admin->listAssignments()]);
	}
	if ($method === 'POST' && ($path === '/v1/admin/assign' || $path === '/admin/assign')) {
		$body = Response::jsonBody();
		$result = $admin->assign($body);
		Response::json(!empty($result['ok']) ? 200 : 400, $result);
	}
	if ($method === 'POST' && ($path === '/v1/admin/welcome-email' || $path === '/admin/welcome-email')) {
		$body = Response::jsonBody();
		$ext = (string)($body['extension'] ?? '');
		$email = isset($body['email']) ? (string)$body['email'] : null;
		$result = $admin->resendWelcome($ext, $email);
		Response::json(!empty($result['ok']) ? 200 : 400, $result);
	}

	Response::json(405, ['error' => 'method_not_allowed', 'path' => $path]);
	return true;
}

function route(string $method, string $path, array $config, SmsGateway $sms, TokenStore $tokens): void {
	if ($method === 'POST' && ($path === '/v1/login' || $path === '/login')) {
		$body = Response::jsonBody();
		$user = trim((string)($body['username'] ?? ''));
		$pass = (string)($body['password'] ?? '');
		if ($user === '' || $pass === '') {
			Response::json(400, ['error' => 'username_and_password_required']);
		}
		$uid = \FreePBX::Userman()->checkCredentials($user, $pass);
		if ($uid === false || $uid === null) {
			Response::json(401, ['error' => 'invalid_credentials']);
		}
		$uid = (int)$uid;
		$u = \FreePBX::Userman()->getUserByID($uid);
		$token = $tokens->issue($uid, (string)($u['username'] ?? $user));
		$lines = enrich_lines($config, $sms, $uid);
		Response::json(200, [
			'token' => $token,
			'expires_in' => (int)$config['token_ttl_seconds'],
			'user' => [
				'id' => $uid,
				'username' => $u['username'] ?? $user,
				'displayname' => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
			],
			'entitlements' => [
				'max_extensions' => Entitlements::maxExtensions($config),
			],
			'lines' => $lines,
		]);
	}

	// All routes below need Bearer token
	$auth = $tokens->userFromRequest();
	if ($auth === null) {
		Response::json(401, ['error' => 'unauthorized']);
	}
	$uid = (int)$auth['uid'];

	// Token-based enrol / session probe (paste HTTPS enrol token in the app).
	if ($method === 'GET' && ($path === '/v1/session' || $path === '/session')) {
		$u = \FreePBX::Userman()->getUserByID($uid);
		Response::json(200, [
			// Client already holds the bearer; echo empty so JSON shape matches login.
			'token' => '',
			'expires_in' => max(0, (int)(($auth['exp'] ?? time()) - time())),
			'user' => [
				'id' => $uid,
				'username' => (string)($auth['username'] ?? $u['username'] ?? ''),
				'displayname' => trim(($u['fname'] ?? '') . ' ' . ($u['lname'] ?? '')),
			],
			'entitlements' => [
				'max_extensions' => Entitlements::maxExtensions($config),
			],
			'lines' => enrich_lines($config, $sms, $uid),
		]);
	}

	if ($method === 'GET' && ($path === '/v1/lines' || $path === '/lines')) {
		Response::json(200, [
			'entitlements' => [
				'max_extensions' => Entitlements::maxExtensions($config),
			],
			'lines' => enrich_lines($config, $sms, $uid),
		]);
	}

	if ($method === 'GET' && preg_match('#^/v1/lines/([^/]+)/threads$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$limit = isset($_GET['limit']) ? (int)$_GET['limit'] : 50;
		$offset = isset($_GET['offset']) ? (int)$_GET['offset'] : 0;
		$data = $sms->threads($uid, $did, $offset, $limit);
		Response::json(200, $data);
	}

	if ($method === 'GET' && preg_match('#^/v1/lines/([^/]+)/threads/([^/]+)/messages$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		$peer = SmsGateway::normalizeDid(urldecode($m[2]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$msgs = $sms->messages($uid, $did, $peer);
		// Opening a thread clears FreePBX unread (same as UCP).
		$sms->markThreadRead($uid, $did, $peer);
		Response::json(200, ['messages' => $msgs]);
	}

	if ($method === 'POST' && preg_match('#^/v1/lines/([^/]+)/threads/([^/]+)/read$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		$peer = SmsGateway::normalizeDid(urldecode($m[2]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$n = $sms->markThreadRead($uid, $did, $peer);
		Response::json(200, ['ok' => true, 'marked' => $n]);
	}

	// Mark all inbound unread for the current DID (Messages inbox line).
	if ($method === 'POST' && preg_match('#^/v1/lines/([^/]+)/read-all$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$n = $sms->markDidRead($uid, $did);
		Response::json(200, ['ok' => true, 'marked' => $n]);
	}

	// FreePBX Do Not Disturb (AstDB DND/<ext> + Custom:DND device state).
	if ($method === 'GET' && preg_match('#^/v1/lines/([^/]+)/dnd$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$ext = $sms->extensionForDid($uid, $did);
		Response::json(200, [
			'ok' => true,
			'enabled' => $sms->isDndActive($ext),
			'extension' => $ext,
		]);
	}

	if (($method === 'PUT' || $method === 'POST') && preg_match('#^/v1/lines/([^/]+)/dnd$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$body = Response::jsonBody();
		$raw = $body['enabled'] ?? $body['dnd'] ?? null;
		if ($raw === null) {
			Response::json(400, ['error' => 'enabled_required', 'detail' => 'JSON body needs enabled: true|false']);
		}
		if (is_bool($raw)) {
			$enabled = $raw;
		} elseif (is_int($raw) || is_float($raw)) {
			$enabled = ((int)$raw) !== 0;
		} else {
			$s = strtolower(trim((string)$raw));
			if (in_array($s, ['1', 'true', 'yes', 'on'], true)) {
				$enabled = true;
			} elseif (in_array($s, ['0', 'false', 'no', 'off', ''], true)) {
				$enabled = false;
			} else {
				Response::json(400, ['error' => 'enabled_required', 'detail' => 'JSON body needs enabled: true|false']);
			}
		}
		$result = $sms->setDnd($uid, $did, $enabled);
		Response::json(!empty($result['ok']) ? 200 : 502, $result);
	}

	// Authenticated SIP secret exchange for voice REGISTER (HTTPS + bearer only).
	// Never include secrets in SMS / enrol landing pages.
	if ($method === 'GET' && preg_match('#^/v1/lines/([^/]+)/sip-credentials$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$creds = $sms->sipCredentialsForDid($uid, $did);
		if ($creds === null) {
			Response::json(404, ['error' => 'sip_credentials_unavailable']);
		}
		Response::json(200, [
			'ok' => true,
			'extension' => $creds['extension'],
			'secret' => $creds['secret'],
			'tech' => $creds['tech'] ?? null,
		]);
	}

	if ($method === 'DELETE' && preg_match('#^/v1/lines/([^/]+)/threads/([^/]+)$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		$peer = SmsGateway::normalizeDid(urldecode($m[2]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$body = Response::jsonBody();
		$threadId = isset($body['thread_id']) ? (string)$body['thread_id'] : (isset($_GET['thread_id']) ? (string)$_GET['thread_id'] : null);
		$result = $sms->deleteThread($uid, $did, $peer, $threadId);
		Response::json(!empty($result['ok']) ? 200 : 502, $result);
	}

	if ($method === 'DELETE' && preg_match('#^/v1/lines/([^/]+)/messages/(\d+)$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		$mid = (int)$m[2];
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$result = $sms->deleteMessage($uid, $did, $mid);
		$code = 200;
		if (empty($result['ok'])) {
			$code = ($result['error'] ?? '') === 'not_found' ? 404 : 502;
		}
		Response::json($code, $result);
	}

	if ($method === 'POST' && preg_match('#^/v1/lines/([^/]+)/messages$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$caps = capability_for_did($config, $sms, $uid, $did);
		if (empty($caps['sms'])) {
			Response::json(403, ['error' => 'sms_disabled', 'detail' => 'SMS entitlement is off for this line']);
		}
		$body = Response::jsonBody();
		$to = SmsGateway::normalizeDid((string)($body['to'] ?? ''));
		$text = (string)($body['body'] ?? $body['message'] ?? '');
		if ($to === '' || $text === '') {
			Response::json(400, ['error' => 'to_and_body_required']);
		}
		$result = $sms->sendText($uid, $did, $to, $text);
		Response::json($result['ok'] ? 200 : 502, $result);
	}

	if ($method === 'POST' && preg_match('#^/v1/lines/([^/]+)/messages/media$#', $path, $m)) {
		$did = SmsGateway::normalizeDid(urldecode($m[1]));
		if (!$sms->userOwnsDid($uid, $did)) {
			Response::json(403, ['error' => 'did_not_assigned']);
		}
		$caps = capability_for_did($config, $sms, $uid, $did);
		if (empty($caps['mms'])) {
			Response::json(403, ['error' => 'mms_disabled', 'detail' => 'MMS entitlement is off for this line']);
		}
		$to = SmsGateway::normalizeDid((string)($_POST['to'] ?? ''));
		if ($to === '' || empty($_FILES['file'])) {
			Response::json(400, ['error' => 'to_and_file_required']);
		}
		$result = $sms->sendMedia($uid, $did, $to, $_FILES['file']);
		Response::json($result['ok'] ? 200 : 502, $result);
	}

	if ($method === 'GET' && preg_match('#^/v1/media/([^/]+)$#', $path, $m)) {
		$name = urldecode($m[1]);
		$sms->streamMedia($uid, $name);
		exit;
	}

	Response::json(404, ['error' => 'not_found', 'path' => $path]);
}

function enrich_lines(array $config, SmsGateway $sms, int $uid): array {
	$raw = $sms->didsForUser($uid);
	return Entitlements::enrichLines(
		$config,
		$raw,
		function (?string $did, ?string $ext) use ($sms): bool {
			return $sms->isDndActive($ext);
		}
	);
}

function capability_for_did(array $config, SmsGateway $sms, int $uid, string $did): array {
	$did = SmsGateway::normalizeDid($did);
	foreach (enrich_lines($config, $sms, $uid) as $row) {
		if (($row['did'] ?? '') === $did) {
			return $row['capabilities'] ?? Entitlements::forDid($config, $did, true, false);
		}
	}
	return Entitlements::forDid($config, $did, false, false);
}
