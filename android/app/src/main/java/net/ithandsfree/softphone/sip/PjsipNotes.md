# PJSIP / PJSUA2 integration

Voice uses **PJSIP PJSUA2** (packaged for Android via [PjDroid](https://github.com/k-m-r-dev/PjDroid) `com.pjdroid:pjdroid`).

## License

This softphone is **GPL-2.0**. PJSIP is GPL (with a separate commercial license from Teluu). Publishing this client under GPL-2.0 is the community path. A closed Play build of the same SIP stack would need a Teluu commercial license instead; that is not this repository.

## Wiring

1. Dependency in `app/build.gradle.kts`: `implementation("com.pjdroid:pjdroid:2.2.4")`
2. `PjsipSipEngine` implements `SipEngine` — multi-account REGISTER, dial, answer, hangup.
3. Each account with `sipExtension` + `sipPassword` (+ `sipDomain`) becomes a PJSUA2 `Account`.
   Domain comes from the line, or `BuildConfig.DEFAULT_SIP_DOMAIN` on the `ihf` flavor.
   The `community` flavor leaves the domain blank until the user enters it.
4. **SIP TLS** on port **5061**, with the PBX certificate checked against the
   Android system CAs. TCP is created as well and used only when the TLS
   transport cannot be created. UDP is the last resort.

## FreePBX checklist

- Extension exists under **Applications → Extensions** (PJSIP).
- Secret matches the softphone line’s SIP password.
- Transport matches. The app registers with **SIP TLS 5061** and falls back to **TCP 5060** only if the TLS transport cannot be created.
- Firewall allows the phone to reach the PBX.
- Codec overlap (ulaw/alaw/g722 common).

## DND

The BFF writes FreePBX DND (`*78` / `*76` equivalent: AstDB `DND/<ext>` and `Custom:DND<ext>`).
Do not rely on app-local mute for busy detection.
