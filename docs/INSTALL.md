# Install the softphone

This is the path from a FreePBX server to a working Android phone. Examples use `pbx.example.com`, extension `1001`, and `user@example.com`. Use your own hostname, extension, and User Manager account.

You will do three things:

1. Put the small API (the BFF) on the PBX.
2. Install the **Community Softphone** app on the phone.
3. Enrol one extension.

FreePBX **17 or later** is the supported platform. The phone needs a network path to that PBX (HTTPS for setup and messaging, TCP 5060 for calls).

iOS is not part of this guide.

## Before you start

On the PBX:

- FreePBX 17+ is installed and you can SSH in as `root`.
- The extension you will test (example `1001`) exists under **Applications → Extensions** and uses **PJSIP**.
- That extension is linked to a **User Manager** user. You know that user's username and password.
- The PBX has an HTTPS certificate for the name the phone will use (`pbx.example.com`).
- For SMS or MMS: the FreePBX **SMS Connector** (`sms` module) is installed and the extension has a DID. Voice-only installs can skip this.

On your computer, for the Android build:

- JDK 17
- Android SDK 34
- A phone or emulator running Android 8 (API 26) or newer

Keep FreePBX **Admin** (`/admin`) off the public internet. The softphone only needs its own HTTPS path.

## 1. Get the code

```bash
git clone https://github.com/ithandsfree/softphone.git
cd softphone
```

## 2. Copy the API onto the PBX

The API lives at `/var/www/html/ihf-softphone/` on the PBX. From the `api` directory of this checkout, on a machine that can SSH to the PBX:

```bash
cd api
SOFTPHONE_PBX_HOST=pbx.example.com \
SOFTPHONE_SSH_KEY=~/.ssh/id_ed25519 \
  ./deploy-to-pbx.sh
```

Replace the host and the key path. If the key is already in `ssh-agent`, you can omit `SOFTPHONE_SSH_KEY`.

The script:

- creates `/var/www/html/ihf-softphone/` and `/var/spool/asterisk/ihf-softphone/`
- copies this API (it does **not** upload a local `config.php`)
- creates `config.php` from `config.example.php` when the PBX does not already have one
- requests `https://127.0.0.1/ihf-softphone/index.php/v1/health`

You should see a JSON body containing `"ok": true`. A certificate warning on that local curl is fine. Any other result means Apache or FreePBX did not boot the script; fix that before continuing.

### Copy by hand

Use this when you cloned the repo on the PBX and you are in the `api` directory.

```bash
sudo mkdir -p /var/www/html/ihf-softphone /var/spool/asterisk/ihf-softphone
sudo chown asterisk:asterisk /var/spool/asterisk/ihf-softphone
sudo chmod 0750 /var/spool/asterisk/ihf-softphone
sudo rsync -a --exclude config.php ./ /var/www/html/ihf-softphone/
cd /var/www/html/ihf-softphone
sudo cp -n config.example.php config.php
sudo ln -sfn public/index.php index.php
sudo ln -sfn public/.htaccess .htaccess
sudo chown -R asterisk:asterisk /var/www/html/ihf-softphone /var/spool/asterisk/ihf-softphone
sudo chmod -R a+rX /var/www/html/ihf-softphone
sudo chmod 0640 /var/www/html/ihf-softphone/config.php
```

Run those commands from the `api` directory of your checkout. `cp -n` will not overwrite a `config.php` you have already edited.

## 3. Set the public URL and mail From

Edit **`/var/www/html/ihf-softphone/config.php` on the PBX**. Do not commit that file.

Set at least:

```php
'public_base' => 'https://pbx.example.com/ihf-softphone',
'mail_from' => 'notify@pbx.example.com',
'mail_from_name' => 'Softphone',
```

`public_base` is the site the phone and the welcome email use. It must be the name and port the phone can open. If you publish the API on port 8443 because port 443 is limited to the office LAN, use that port here:

```php
'public_base' => 'https://pbx.example.com:8443/ihf-softphone',
```

`mail_from` must be an address this PBX is allowed to send (SPF or DKIM). It is only required for **Email me a setup link**. You can enrol with a User Manager password without mail.

Leave `admin_token` empty unless you want a break-glass password. Do not put SIP secrets in this file.

## 4. Check the API from outside the PBX

From your computer, not from the PBX loopback:

```bash
curl -sk https://pbx.example.com/ihf-softphone/index.php/v1/health
```

Use the same URL you put in `public_base`, plus `/index.php/v1/health`.

You should see `"ok": true`. If this hangs or returns a certificate name error, fix DNS, the firewall, or the certificate before installing the phone app.

Then log in as the User Manager user. Type the password only in your shell, not into git:

```bash
curl -sk -X POST https://pbx.example.com/ihf-softphone/index.php/v1/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user@example.com","password":"SECRET"}'
```

You should get JSON with a `token` and a `lines` array. If `lines` is empty, that User Manager user has no extension or DID yet. Fix that in FreePBX before opening the app.

The phone sends the token in the header `X-IHF-Token`. You can repeat the check:

```bash
curl -sk -H "X-IHF-Token: TOKEN" \
  https://pbx.example.com/ihf-softphone/index.php/v1/lines
```

## 5. Let the phone reach the PBX

Allow the phone's network to reach:

| Purpose | Port | Required |
| --- | --- | --- |
| API (setup, SMS, MMS) | TCP 443, or the port in `public_base` | Yes |
| SIP registration and calls | TCP 5060 | Yes, for voice |
| RTP audio | UDP 10000–20000 (FreePBX default) | Yes, for voice |

Prefer **TCP** for SIP. The app tries TCP first. SIP TLS is included in the next build.

Do not open FreePBX Admin, User Manager admin, or the softphone page `/ihf-softphone/admin/` on the public port. If you add a public HTTPS vhost, limit it to `/ihf-softphone/` and deny `/ihf-softphone/admin` and `/v1/admin/*`.

## 6. Build and install the Android app

Use the **community** flavor. It asks for your PBX address. The `ihf` flavor in this repository is a placeholder (`pbx.example.com`) and will not find your server.

```bash
cd android
./gradlew :app:assembleCommunityDebug
```

The APK is:

```text
android/app/build/outputs/apk/community/debug/app-community-debug.apk
```

Install it:

```bash
adb install -r app/build/outputs/apk/community/debug/app-community-debug.apk
```

Or copy that APK to the phone and open it. Android will ask you to allow installs from that source.

On first launch, allow the microphone when you want calls, and notifications when you want incoming calls and messages. Contacts are optional.

The app name is **Community Softphone**. The package is `net.ithandsfree.softphone.community`.

## 7. Enrol one extension

You need two addresses, typed exactly as the phone will use them:

| Field | Example |
| --- | --- |
| PBX softphone URL | `https://pbx.example.com/ihf-softphone/index.php` |
| SIP domain | `pbx.example.com` |

The URL is the API base from step 4, **without** `/v1/health`. The SIP domain is the hostname only, no `https://` and no port.

### Option A — User Manager password

This works without email.

1. Open the app.
2. Tap **Advanced setup**.
3. Enter the **PBX softphone URL** and **SIP domain**.
4. Enter the User Manager username and password.
5. Enter extension `1001` and its SIP secret if you want to place calls now. You can leave the SIP secret blank and add it later; messaging does not need it.
6. Tap **Enrol line**.

### Option B — Email a setup link

This needs working outbound mail from the PBX, and the address must be the User Manager user's email.

1. On the welcome screen, enter the **PBX softphone URL**.
2. Enter the work email and tap **Email me a setup link**.
3. Open that email **on the phone** and tap the setup link, or paste the link into **Enter code**.
4. Enter the SIP domain if the screen asks for it, then continue.
5. Add the SIP secret when you want voice.

After either option, open **Settings**. **SIP status** should show `PJSIP 1001:OK` once the secret is saved and the phone can reach TCP 5060.

Place a short call. If SMS is enabled, send a message from **Messages**.

A phone holds at most two extensions. Enrol the second line from the same welcome flow (**Add another line**).

## 8. Optional: operator page and welcome email

Skip this for a single test user who can already log in.

On the PBX:

```bash
php /var/www/html/ihf-softphone/bin/setup-um-admin-perm.php --user=user@example.com
```

That creates the User Manager group **IHF Softphone Admins** and grants `ihf_softphone_admin`, then adds `--user`.

Open the assign page from a trusted network only:

```text
https://pbx.example.com/ihf-softphone/admin/
```

Sign in with that User Manager account. Assign an extension and send the welcome email from there when mail is working.

## When something fails

| What you see | What to check |
| --- | --- |
| `deploy-to-pbx.sh` says `Set SOFTPHONE_PBX_HOST` | Export the hostname. The script has no built-in server. |
| Health check on the PBX fails, or PHP says FreePBX failed to load | Run the file on the PBX as a user that can read `/etc/freepbx.conf`. The API includes that file. |
| Health works on the PBX but not from your computer | DNS, firewall, or the certificate name does not match `pbx.example.com`. |
| Login returns no `lines` | The User Manager user has no extension, or the extension has no SMS DID when you expected messaging. |
| The phone says it cannot find the softphone URL | The community app field must be the full `https://…/ihf-softphone/index.php` URL, including a non-default port. |
| Enrol succeeds but SIP status stays down | SIP secret, SIP domain, and TCP 5060 from the phone to the PBX. The extension transport in FreePBX must allow TCP. |
| One-way or no audio | RTP UDP 10000–20000 from the phone to the PBX. |
| SMS returns forbidden | SMS Connector is missing, or that line's `sms` capability is off in `config.php`. |
| Setup email never arrives | `mail_from`, local `sendmail`, and SPF/DKIM. Use option A (User Manager password) until mail works. |

Endpoint list and config keys: [BFF_INSTALL.md](BFF_INSTALL.md). Flavor differences: [FLAVORS.md](FLAVORS.md).
