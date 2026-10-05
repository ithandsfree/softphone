# Install the BFF on FreePBX 17+

Step-by-step install of the PBX API and the phone app: [INSTALL.md](INSTALL.md).

This page is the reference for paths, config keys, and endpoints. The BFF is a PHP front controller on the PBX. It bootstraps FreePBX (`/etc/freepbx.conf`) and uses User Manager plus the SMS module.

Web path on the PBX: `/ihf-softphone/`  
Files: `/var/www/html/ihf-softphone/`  
Tokens: `/var/spool/asterisk/ihf-softphone/tokens.json` (mode `0750`, owner `asterisk`)

## Requirements

- FreePBX **17 or later**
- PJSIP extensions
- User Manager
- Commercial `sms` module if you want messaging
- HTTPS certificate for the name phones will use (`pbx.example.com` in the docs)
- Local mail (`sendmail`) if you want welcome / “email me a setup link”

## Configure

```bash
cd api
cp config.example.php config.php
```

Edit `config.php` before the first request:

| Key | Example |
| --- | --- |
| `public_base` | `https://pbx.example.com/ihf-softphone` |
| `mail_from` | `notify@pbx.example.com` |
| `mail_from_name` | `Softphone` |
| `admin_token` | long random secret, or leave empty and use User Manager admin login |
| `default_skin` | `ihf_night`, `carbon_signal`, or `ihf_mist` |

`config.php` stays on the PBX. Do not commit it.

## Deploy

From a workstation that can SSH to the PBX as root:

```bash
cd api
SOFTPHONE_PBX_HOST=pbx.example.com \
SOFTPHONE_SSH_KEY=~/.ssh/id_ed25519 \
  ./deploy-to-pbx.sh
```

The script copies this directory to `/var/www/html/ihf-softphone/`, creates `config.php` from the example if missing, and curls `https://127.0.0.1/ihf-softphone/index.php/v1/health`.

## Admin permission

On the PBX, after FreePBX is bootstrapped:

```bash
php /var/www/html/ihf-softphone/bin/setup-um-admin-perm.php --user=user@example.com
```

That creates the User Manager group **IHF Softphone Admins** and the `ihf_softphone_admin` permission, then adds `--user`. The admin UI is `/ihf-softphone/admin/`. Put it on a trusted HTTPS vhost. A public softphone edge should deny `/admin` and `/v1/admin/*`.

## Check

```bash
curl -sk https://pbx.example.com/ihf-softphone/index.php/v1/health
```

Expect `"ok": true`.

Login (replace the User Manager password locally; do not paste it into git):

```bash
curl -sk -X POST https://pbx.example.com/ihf-softphone/index.php/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user@example.com","password":"SECRET"}'
```

Send the returned token as `X-IHF-Token` on later calls. This header name is what the app sends; Apache on some FreePBX builds strips `Authorization`.

## What the phone calls

| Method | Path | Auth |
| --- | --- | --- |
| GET | `/v1/health` | no |
| POST | `/v1/request-enrol` | no (`{"email":"user@example.com"}`) |
| POST | `/v1/login` | no |
| GET | `/v1/session` | token |
| GET | `/v1/lines` | token |
| GET/PUT | `/v1/lines/{did}/dnd` | token |
| GET | `/v1/lines/{did}/sip-credentials` | token |
| * | `/v1/lines/{did}/…` messages | token |

SIP secrets are returned only on the authenticated HTTPS `sip-credentials` call. They are not put in email or the enrol HTML page.
