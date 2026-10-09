<?php
/**
 * Copy to config.php on the PBX (not committed with secrets).
 * deploy.sh creates a default if missing.
 */
return [
	// Writable by Apache / asterisk user
	'token_file' => '/var/spool/asterisk/ihf-softphone/tokens.json',
	// Default bearer TTL; self-service enrol emails use enrol_token_ttl_seconds when set.
	'token_ttl_seconds' => 259200, // 72h — matches welcome "expires in 72 hours"
	/**
	 * Device token issued when an app opens the setup link (/v1/session). Sliding: renewed while the app
	 * is in use, so a device never has to sign in again. 180 days of no use ends it.
	 */
	'device_token_ttl_seconds' => 15552000,
	/** How many devices one setup link may set up (a phone and a PC from one email = 2). */
	'setup_link_max_uses' => 3,

	/** POST /v1/login brute-force guard; failures are logged for fail2ban ("ihf-softphone: login failed"). */
	'login_limit_file' => '/var/spool/asterisk/ihf-softphone/login-limits.json',
	'login_max_failures' => 5,        // per username + IP inside the window
	'login_max_failures_ip' => 20,    // per IP, all usernames
	'login_window_seconds' => 900,
	/** Reverse proxies whose X-Forwarded-For is believed. Empty = use the connecting address only. */
	'trusted_proxies' => [],
	// Off: the apps are native and the admin UI is same-origin. Set an origin only for a browser client you run.
	'cors_origin' => '',

	/**
	 * Softphone admin UI (/ihf-softphone/admin/) + /v1/admin/* APIs.
	 *
	 * Auth guideline (keep both):
	 *   1) Primary — FreePBX User Manager / UCP username+password + permission
	 *   2) Fallback — admin_token (X-IHF-Admin-Token) when
	 *      admin_token_fallback_enabled is true (default true; do not drop)
	 *
	 * Beta: assign extension + welcome email only — no licensing / charging.
	 */
	'admin_token' => '',
	/** Keep true unless ops explicitly disables break-glass token auth. */
	'admin_token_fallback_enabled' => true,
	'admin_session_ttl_seconds' => 28800, // 8h short-lived UM admin session
	'admin_cookie_path' => '/ihf-softphone',
	/** Userman module setting checked via getCombinedModuleSettingByID */
	'admin_userman_module' => 'ihfsoftphone',
	'admin_userman_setting' => 'ihf_softphone_admin',
	/** Also allow membership in these Userman groups (GUI-friendly). */
	'admin_userman_groups' => ['IHF Softphone Admins'],
	'assignments_file' => '/var/spool/asterisk/ihf-softphone/assignments.json',
	'public_base' => 'https://pbx.example.com/ihf-softphone',
	// Optional override; when empty, admin picks the newest /dl/<token>/ drop.
	'install_url' => '',
	/**
	 * Envelope From for welcome / request-enrol mail. Use an address your PBX
	 * is allowed to send (SPF/DKIM). Example only — replace before production.
	 */
	'mail_from' => 'notify@pbx.example.com',
	'mail_from_name' => 'Softphone',
	/** Optional welcome-email branding. Empty values are left out of the message. */
	'mail_brand_line' => '',          // small caps under the name, e.g. your company
	'mail_footer_line' => '',         // last line, e.g. "Canadian-hosted Cloud PBX"
	'mail_emblem' => '',              // image inside this directory, e.g. 'brand/emblem.png'

	/**
	 * DID => extension, used only when User Manager does not give exactly one extension for a user's SMS DID.
	 * Example: [ '15555550100' => '1001' ]
	 */
	'did_extension_map' => [],

	/*
	 * Call history and recordings (/v1/lines/{did}/calls) follow the UCP "Call History" settings in
	 * User Manager for each user: enable, assigned extensions, playback, download. Nothing to set here.
	 */

	/** Public POST /v1/request-enrol rate limits (file-backed). */
	'enrol_request_limit_file' => '/var/spool/asterisk/ihf-softphone/enrol-request-limits.json',
	'enrol_request_email_cooldown_seconds' => 30,
	'enrol_request_ip_max' => 8,
	'enrol_request_ip_window_seconds' => 3600,

	/** Product seat rule (v1 softphone): max two extensions per device. */
	'max_extensions' => 2,

	/**
	 * Tenant/brand default skin (Android SoftphoneSkins id).
	 * Clients apply this until the user picks a skin in Settings → Appearance.
	 * Known ids: ihf_night | carbon_signal | ihf_mist
	 */
	'default_skin' => 'ihf_night',
	'brand_mark' => 'IHF',

	/**
	 * Default capability flags per line (Phase 3 admin module will own these).
	 * Per-DID overrides: 'line_capabilities' => [ '15555550100' => ['sms' => false] ]
	 */
	'default_capabilities' => [
		'voice' => true,
		'sms' => true,
		'mms' => true,
		// dnd is live-read from AstDB when extension is known; this is only a fallback
		'dnd' => false,
	],
	'line_capabilities' => [
		// Example: disable MMS on a DID while keeping SMS
		// '15555550199' => ['mms' => false],
	],
];
