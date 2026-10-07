# Windows softphone

**Desktop** build. Two desktop editions of the same client, matching the Android flavors. GPL-2.0. Voice is PJSIP built from the 2.17 tag. The Android PjDroid binaries are not used. Review notes: [CODE-SPEC.md](CODE-SPEC.md).

| | IHF Phone | Community Softphone |
| --- | --- | --- |
| Who it is for | PBX customers | A FreePBX 17+ server you run yourself |
| Server fields | Hidden | Required: BFF URL and SIP domain |
| Defaults in this tree | `pbx.example.com` | Empty |

A Community Softphone and an IHF Phone on the same PBX call each other and exchange texts through that one BFF. There is no separate Windows-to-Android link.

```
gradlew.bat run
gradlew.bat runCommunity
```

This tree ships placeholder hosts only (`pbx.example.com`, extension `1001`, `user@example.com`). Point a live build at your PBX with environment variables or a gitignored `host.local.properties` file. Do not commit that file.

```
apiBase=https://pbx.example.com/ihf-softphone/index.php
sipDomain=pbx.example.com
```

`IHF_API_BASE` and `IHF_SIP_DOMAIN` override the file.

## First slice

The window enrols one line from a setup link, registers it, places a call, answers a call, and sends a text through the BFF.

## Build

JDK 17, Git, Visual Studio 2022 Build Tools (C++ and the Windows SDK), and the OpenSSL 3 Win64 SDK.

```
native\build-pjsip.ps1
gradlew.bat test
gradlew.bat run
```

`build-pjsip.ps1` clones `https://github.com/pjsip/pjproject` at tag `2.17` into `third_party/` and links `native/out/ihf_sip.dll`. A guest with no sound card still registers. Call audio needs a playback device.
