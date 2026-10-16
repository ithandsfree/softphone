# Softphone API (Option A BFF)

Thin HTTPS API on FreePBX that wraps User Manager + the commercial `sms` module (same store as UCP / Clearly Anywhere / SMS Connector).

Android clients authenticate with **User Manager** credentials and receive an opaque token. Use header **`X-IHF-Token: <token>`** (this FreePBX Apache build strips the standard `Authorization` header).

## Deployed base (pbx IHF live)

`https://pbx.example.com:8443/ihf-softphone/index.php` (public softphone edge; `:443` remains trusted/LAN-only)

(lab lab still has a copy at the same path on that host.)

## Endpoints (spike)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/health` | no | Liveness + `branding.default_skin` / `brand_mark` |
| POST | `/v1/login` | no | `{username,password}` → token + lines + entitlements |
| GET | `/v1/session` | yes | Current user + lines for a pre-issued enrol/bearer token |
| GET | `/v1/lines` | token | DIDs + capability flags for user |
| GET | `/v1/lines/{did}/threads` | token | Conversations (+ per-thread `unread`, `total_unread`) |
| GET | `/v1/lines/{did}/threads/{peer}/messages` | token | SMS/MMS (+ media); marks inbound read |
| POST | `/v1/lines/{did}/threads/{peer}/read` | token | Mark inbound messages read |
| POST | `/v1/lines/{did}/read-all` | token | Mark all inbound unread for this DID |
| DELETE | `/v1/lines/{did}/threads/{peer}` | token | Delete conversation (optional `thread_id`) |
| DELETE | `/v1/lines/{did}/messages/{id}` | token | Delete one SMS/MMS row (+ media) |
| GET | `/v1/lines/{did}/sip-credentials` | token | Extension + SIP secret (HTTPS bearer only; for REGISTER) |
| POST | `/v1/lines/{did}/messages` | token | Send SMS `{to,body}` (403 if `sms` off) |
| POST | `/v1/lines/{did}/messages/media` | token | Send MMS multipart `to` + `file` (403 if `mms` off) |
| GET | `/v1/media/{name}` | token | Media bytes (DID ownership checked) |

### Entitlements / capability flags

Login and `/v1/lines` include:

```json
{
  "entitlements": { "max_extensions": 2 },
  "lines": [
    {
      "did": "15555550199",
      "e164": "+15555550199",
      "extension": "1002",
      "capabilities": { "voice": true, "sms": true, "mms": true, "dnd": false }
    }
  ]
}
```

- Defaults + optional per-DID overrides live in `config.php` (`default_capabilities`, `line_capabilities`).
- `dnd` is best-effort from FreePBX AstDB `DND/<extension>` when an extension can be guessed.
- Phase 3 admin module will own these flags; until then use config overrides.
- Tenant skin default: `config.php` keys `default_skin` / `brand_mark` (see `../docs/SKINS.md`).

## Manual test

```bash
curl -sk https://pbx.example.com:8443/ihf-softphone/index.php/v1/health

TOKEN=$(curl -sk -X POST https://pbx.example.com:8443/ihf-softphone/index.php/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"USER","password":"PASS"}' | jq -r .token)

curl -sk -H "X-IHF-Token: $TOKEN" \
  https://pbx.example.com:8443/ihf-softphone/index.php/v1/lines
```

## Spike verification (2026-10-02 on lab)

| Check | Result |
| --- | --- |
| Health | `{"ok":true}` |
| Lines for main UM user | `15555550100` |
| Lines for rsw user | `15555550101`, `15555550199` |
| Threads + messages | OK (peer = remotedid) |
| Media `12513media.jpeg` with token | `200` JPEG |
| Media without token | `401` |
| Media with other user's token | `403` |

## Layout

```
api/
  public/index.php
  lib/{Response,TokenStore,SmsGateway}.php
  config.example.php
  deploy-to-pbx.sh
```

On PBX: `/var/www/html/ihf-softphone/` · tokens: `/var/spool/asterisk/ihf-softphone/tokens.json`

## Security notes (spike)

- Prefer `X-IHF-Token` on this host.
- Tokens are file-backed, 24h TTL default.
- Media requires the SMS message to involve one of the user’s DIDs.
- `/sip-credentials` returns the FreePBX device secret over an authenticated
  HTTPS token only — never put SIP secrets in SMS, email, or enrol HTML.
- Tighten CORS and add rate limits before production.
- Do not commit `config.php` or live tokens.
