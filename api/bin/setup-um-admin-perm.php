<?php
/**
 * CLI: ensure Userman admin group + ihf_softphone_admin permission.
 *
 * Usage (on PBX):
 *   php /var/www/html/ihf-softphone/bin/setup-um-admin-perm.php \
 *     --user=user@example.com
 */
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
	fwrite(STDERR, "CLI only\n");
	exit(1);
}

$opts = getopt('', ['user::', 'group::', 'module::', 'setting::']);
$user = (string)($opts['user'] ?? getenv('UM_USER') ?: '');
if ($user === '') {
	fwrite(STDERR, "Pass --user=user@example.com (FreePBX User Manager username)\n");
	exit(1);
}
$group = (string)($opts['group'] ?? getenv('UM_GROUP') ?: 'IHF Softphone Admins');
$module = (string)($opts['module'] ?? getenv('UM_MODULE') ?: '');
$setting = (string)($opts['setting'] ?? getenv('UM_SETTING') ?: '');

$bootstrap_settings['freepbx_auth'] = false;
// Contact Manager Userman group hooks need AMI when adding users to a group.
$bootstrap_settings['skip_astman'] = false;
include '/etc/freepbx.conf';

require dirname(__DIR__) . '/lib/TokenStore.php';
require dirname(__DIR__) . '/lib/AdminAuth.php';

$configFile = dirname(__DIR__) . '/config.php';
$softConfig = is_readable($configFile) ? require $configFile : [];
if (!is_array($softConfig)) {
	$softConfig = [];
}
// Do not reuse FreePBX's $config symbol after bootstrap.
$adminConfig = $softConfig;
if ($module !== '') {
	$adminConfig['admin_userman_module'] = $module;
}
if ($setting !== '') {
	$adminConfig['admin_userman_setting'] = $setting;
}
$adminConfig['admin_userman_groups'] = [$group];
// Harden types (Whoops turns array-to-string notices into fatals).
foreach (['admin_userman_module' => 'ihfsoftphone', 'admin_userman_setting' => 'ihf_softphone_admin'] as $k => $default) {
	if (!is_string($adminConfig[$k] ?? null) || $adminConfig[$k] === '') {
		$adminConfig[$k] = $default;
	}
}

$tokens = new TokenStore('/tmp/ihf-admin-setup-tokens.json', 60);
$auth = new AdminAuth($adminConfig, $tokens);

$ensured = $auth->ensurePermissionGroup($group);
echo json_encode(['ensure' => $ensured], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . PHP_EOL;
if (empty($ensured['ok'])) {
	exit(1);
}

$grant = $auth->grantUser($user, $group);
echo json_encode(['grant' => $grant], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . PHP_EOL;
if (empty($grant['ok'])) {
	exit(1);
}

echo 'HAS_PERMISSION=' . ($auth->userHasPermission((int)$grant['uid']) ? '1' : '0') . PHP_EOL;
