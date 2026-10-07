# Softphone

GPL-2.0 softphone for **FreePBX 17+**. Two clients share one HTTPS API (BFF).

| Label | Build | Path |
| --- | --- | --- |
| **Android** | Phone app. Kotlin and Jetpack Compose. | [`android/`](android/) |
| **Desktop** | Windows app. Swing and PJSIP built from source. | [`windows/`](windows/) |

Voice registers with PJSIP. SMS and MMS go through the FreePBX SMS Connector, not SIP MESSAGE. Neither client talks to FreePBX admin or UCP cookies. Both use a bearer token from this BFF (`X-IHF-Token`).

License: **GPL-2.0**. See [LICENSE](LICENSE). The store listing source link is this repository. The Play privacy policy is a different URL, recorded in [docs/PLAY.md](docs/PLAY.md).

## Android

Google Play gets two softphone apps from `android/`: IHF Phone (`net.ithandsfree.softphone`) and Community Softphone (`net.ithandsfree.softphone.community`). Issue label: `android`.

## Desktop

The Windows client is `windows/`. Review status, what is built, and what is left are in [windows/CODE-SPEC.md](windows/CODE-SPEC.md). Issue label: `desktop`. The desktop app is not a Play upload.

This repository also posts the BFF (`api/`). The BFF is not a Play upload.

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
| `windows/` | Windows desktop client. Same BFF, PJSIP built from source |
| `api/` | PHP BFF installed on the FreePBX host |
| `docs/` | Install guide, support, flavors |
| `skins-preview/` | HTML preview of the colour packs |

## Install

Follow [docs/INSTALL.md](docs/INSTALL.md). It walks through the PBX API, the Community Softphone APK, and enrolling one extension.

Reference for endpoints and config keys: [docs/BFF_INSTALL.md](docs/BFF_INSTALL.md).
