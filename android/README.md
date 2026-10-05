# Android app

Install walkthrough (PBX and phone): [../docs/INSTALL.md](../docs/INSTALL.md).

Kotlin + Jetpack Compose client for the [BFF](../api/README.md).

**Messaging:** SMS/MMS, multi-line inbox, enrol by email link or token.  
**Voice:** PJSIP / PJSUA2 (`PjsipSipEngine`). See `app/src/main/java/net/ithandsfree/softphone/sip/PjsipNotes.md`.

## Requirements

- JDK 17
- Android SDK 34
- Network path to your FreePBX 17+ host

## Flavors

| Flavor | Application id | Server fields |
| --- | --- | --- |
| `community` | `net.ithandsfree.softphone.community` | User enters BFF URL and SIP domain |
| `ihf` | `net.ithandsfree.softphone` | Placeholders in source (`pbx.example.com`) |

```bash
cd android
./gradlew :app:assembleCommunityDebug
./gradlew :app:testCommunityDebugUnitTest
```

Auth header: `X-IHF-Token`.

### Enrol

- Custom scheme (both flavors): `ihfphone://enroll/{token}`
- HTTPS example: `https://pbx.example.com/ihf-softphone/enrol/{token}/`
- Pasting an HTTPS enrol URL from any host still extracts the token. The manifest app-link host in this tree is the placeholder `pbx.example.com`.

On the **community** flavor, Advanced setup (and Welcome / Enter code) ask for the softphone URL and SIP domain before enrol. Extension `1001` is the example used in docs, not a default account.

Credentials and tokens are stored in EncryptedSharedPreferences.

## Layout

```
app/src/main/java/net/ithandsfree/softphone/
  data/     SoftphoneApi, AccountStore, models
  sip/      SipEngine + PjsipSipEngine
  ui/       navigation, theme, screens
```
