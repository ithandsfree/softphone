# Softphone

Android softphone and a small HTTPS API (BFF) for **FreePBX 17+**.

Voice registers with PJSIP. SMS and MMS go through the FreePBX SMS Connector, not SIP MESSAGE. The phone never talks to FreePBX admin or UCP cookies; it uses a bearer token from this BFF (`X-IHF-Token`).

License: **GPL-2.0**. See [LICENSE](LICENSE).

## Support

**FreePBX 17 and later**, with PJSIP, User Manager, and the FreePBX SMS Connector.

Earlier FreePBX releases are not a community install. iOS is shelved. Other SIP platforms are out of scope.

Details: [docs/SUPPORT.md](docs/SUPPORT.md).

## Flavors

Two Android product flavors. They do not share an application id, so both can be installed on one phone.

| Flavor | Application id | Server |
| --- | --- | --- |
| `ihf` | `net.ithandsfree.softphone` | Placeholder `pbx.example.com` in this tree. Replace it in your own build if you ship a hosted app. |
| `community` | `net.ithandsfree.softphone.community` | Blank until the user enters the PBX softphone URL and SIP domain. |

This repository does not contain a live PBX hostname, a real extension, or a real mailbox. Examples use `pbx.example.com`, extension `1001`, and `user@example.com`.

See [docs/FLAVORS.md](docs/FLAVORS.md).

## Layout

| Path | Purpose |
| --- | --- |
| `android/` | Kotlin + Jetpack Compose client |
| `api/` | PHP BFF installed on the FreePBX host |
| `docs/` | Support, install, flavors |
| `skins-preview/` | HTML preview of the colour packs |

## Quick start

1. Install the BFF on FreePBX 17+ — [docs/BFF_INSTALL.md](docs/BFF_INSTALL.md).
2. Build the community app — [android/README.md](android/README.md).
3. On the phone, enter `https://pbx.example.com/ihf-softphone/index.php` (your host) and the SIP domain, then enrol with User Manager or an emailed setup link.
