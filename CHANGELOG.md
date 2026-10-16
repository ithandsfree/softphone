# Changelog

Newest first. Android versions are the `versionName` in `android/app/build.gradle.kts`; desktop versions are
`APP_BUILD` in `windows/src/main/kotlin/net/ithandsfree/softphone/win/AppBuild.kt`.

## 2026-10-16: Android 0.4.25, Desktop 0.1.43, BFF

### Android (0.3.12 to 0.4.25)

Calls
- Calls are self-managed Telecom calls: Bluetooth, car and headset controls, speaker routing. The app plays the
  line's ringtone and vibrates (self-managed calls get no system ringtone).
- Incoming-call heads-up banner and full-screen lock-screen call screen on their own notification channel.
- In-call screen with landscape layout; one Speaker control plus Bluetooth/headset.
- Mute mutes the microphone (capture level), not the speaker.
- Blind transfer by default; attended transfer ("Speak first", then Complete or Cancel) through REFER with Replaces.
- Call waiting: hold and answer, swap, end.
- A call that cannot open the microphone says where to allow the Microphone permission and asks again
  (PJSIP `PJMEDIA_EAUD_INIT`).

Android Auto (CALLING category, Car App Library 1.7)
- Recents and Contacts tabs (current car hosts), Search, Voicemail; older hosts keep a single list.
- Message notifications with Reply and Mark as read for the car (MessagingStyle).

Messages
- Picture messages: photos are scaled to JPEG under 1 MB; other supported files are sent as they are.
- Full-screen pictures have Save (Pictures folder), Share and Copy.
- Press and hold a conversation: Call, Mark as read or unread, Delete. Call button inside a conversation.
- Conversations sorted by message time. New-message alerts and the app-icon badge stay until the message is read.

Lines and contacts
- Voice-only lines (an extension without a texting number).
- Contacts search and A to Z index, in the app and in the car; the address book is read up to 10,000 people.

Reliability
- Background services use the Android 14+ foreground-service types `specialUse` (SIP keep-alive) and
  `remoteMessaging` (message check), which have no daily time limit.
- Expedited background work supplies foreground info on Android 11 and older.
- Encrypted preferences recover when a phone's key store cannot open them.
- Crash report: after a crash the app offers to share the report; a start-up step trail and safe mode.
- The device keeps its long-lived token after setup (lines no longer expire after the setup link's lifetime).

On-device tests (`android/app/src/androidTest`): MMS photo preparation, picture Save/Share/Copy, message alerts
with two lines, the picture viewer, bottom tabs after a call, the Android Auto home screen, and opening the
microphone through PJSIP.

### Desktop (0.1.32 to 0.1.43)

- Windows installer (MSI) with a private Java runtime and the PJSIP voice engine.
- Voice-only lines; contacts search with letter headers and a letter bar.
- Attended transfer; voicemail list and playback; call history with recordings where the PBX allows them.
- Photo window: Save picture and Copy.

### BFF (`api/`)

- Setup emails go only to the User Manager email of the extension's owner; re-issuing a link revokes the old one;
  `POST /v1/admin/remove` removes a line and signs out its devices.
- Install buttons per platform in setup emails and pages (`install_android_url`, `install_windows_url`).
- Voice-only lines are listed and allowed on voice routes (`userOwnsLine`).
- Conversations and messages sorted by message time.
- `POST /v1/lines/{did}/threads/{peer}/unread` marks a conversation unread (newest received message).
- Picture messages accept a list of extensions, up to 1.5 MB.
- Login limiter, call history and voicemail endpoints (desktop and Android).
