# Android flavors

`android/app/build.gradle.kts` defines one flavor dimension, `distribution`.

| | `ihf` | `community` |
| --- | --- | --- |
| Application id | `net.ithandsfree.softphone` | `net.ithandsfree.softphone.community` |
| App name | IHF Phone | Community Softphone |
| `DEFAULT_API_BASE` | `https://pbx.example.com/ihf-softphone/index.php` | empty |
| `DEFAULT_SIP_DOMAIN` | `pbx.example.com` | empty |
| `SERVER_EDITABLE` | false | true |
| Default skin | `ihf_night` | `carbon_signal` |

## Community

Welcome, Enter code, and Advanced setup show:

- **PBX softphone URL** — the BFF, for example `https://pbx.example.com/ihf-softphone/index.php`
- **SIP domain** — the host the phone uses for REGISTER, for example `pbx.example.com`

Those values are required before enrol. They are not baked into the APK.

## IHF

The public `ihf` flavor keeps server fields hidden and uses the placeholders above. A hosted build replaces `DEFAULT_API_BASE` and `DEFAULT_SIP_DOMAIN` locally. Do not commit a live hostname back to this repository.

```bash
cd android
./gradlew :app:assembleCommunityDebug
./gradlew :app:assembleIhfDebug
```

Play Store listings are separate (different application ids). Closed testing comes before any paid listing. See [PLAY.md](PLAY.md).
