# IHF Phone for Windows — code spec

Status for review. Design source is round 3, `IHF-Phone-design/round-3/DESKTOP-SPEC.md` and the `desktop-*.png` screens. This file says what the Swing client actually does. Visible build string: `APP_BUILD` in `AppBuild.kt` (0.1.32). The MSI version is the same number.

Public tree: `softphone/windows`, GPL-2.0. Live PBX hosts, enrol tokens, and mailbox client ids stay out of git. A developer machine points at a PBX with gitignored `host.local.properties` (`apiBase`, `sipDomain`). Placeholders in source are `pbx.example.com` and extension `1001`.

The shipping client is Swing on Java 17, not the Compose Multiplatform note in the design spec. SIP is PJSIP 2.17 behind `ihf_sip.dll`. Android remains the other client. Do not treat this tree as an Android change.

## Architecture

```
PhoneApp (process, link handlers, second-instance relay)
  PhoneFrame (Swing UI)
    PhoneSession (enrol, register, call, SMS, DND)
      BffClient (HTTPS, bearer X-IHF-Token)
      SipBridge (JNA)
        ihf_sip.dll (PJSIP)
    LineBook, CallLog, ContactBook, AudioPrefs, WindowPrefs (files under %LOCALAPPDATA%\IHF Phone)
    DeskSurfaces (tray, incoming window, mini call, ringtone)
```

Signalling is TLS on 5061, then TCP on 5060. SIP over UDP is refused. RTP and SRTP stay UDP. SMS and MMS go through the BFF, not SIP MESSAGE. A 10-digit NANP number is sent with a leading 1. `*`, `#`, and `+` stay literal.

One call at a time. A second incoming call is rejected with 486. Attended transfer uses a consult call beside the held call. Two enrolled lines can be registered together. Outbound calls and messages use the selected line.

## Native contract

`native/ihf_sip.h` is the ABI. `SipBridge.IhfStatus` field order must match the C struct, including the fields appended for consult and for the second line (`active_line`, `reg_b`, `ext_a`, `ext_b`, `call_ext`).

The DLL holds two PJSIP accounts. `ihf_sip_register` fills the slot with the same extension, or the first free slot. A third extension returns an error. `ihf_sip_use_line` selects the account used for the next call. Ringtone playback is `ihf_sip_ring_start` / `ihf_sip_ring_stop`, into the open call speaker.

`ihf_sip_open_devices` and the playback switch open a capture and playback pair. If that open fails, the previous pair is restored so a bad headset switch does not leave the call with no audio device.

This PC cannot link the DLL. The link runs on the Windows dev VM that has Visual Studio 2022 Build Tools, the OpenSSL MD libraries, and the already-built PJSIP tree. Relink `ihf_sip.c` only. Do not rebuild all of PJSIP, and do not link the fat `libpjproject`.

## Local files

All under `%LOCALAPPDATA%\IHF Phone\`:

| File | Contents |
|---|---|
| `line.properties` | The active line, kept so older builds still reopen |
| `lines.properties` | Up to two lines, plus the default extension |
| `recents.txt` | `time, kind, party, seconds, extension`. Older rows have no extension |
| `contacts.txt` | `name` tab `number` |
| `audio.properties` | Mic, speaker, ringer, ringtone, keypad tone, also-ring |
| `window.properties` | Bounds, pin, local do-not-disturb |
| `mailbox.properties` | OAuth refresh tokens, only after a mailbox sign-in |
| `ca-bundle.pem` | Windows trust store exported for PJSIP |

Quit does not clear the lines. An upgrade keeps extension data already stored.

## Behaviour that is in the build

**Setup.** Welcome asks for a work email. The BFF always answers with a generic check-inbox result and does not say whether that mailbox exists. The setup link (`ihfphone://` or the HTTPS enrol URL) or a pasted code enrols the line. A second line uses the same path and does not replace the first. Back returns to the phone when a line is already enrolled. Scan-QR on the desktop opens code entry. There is no camera.

**Lines.** Up to two extensions, each registered TLS-then-TCP. The gold line button is the default for new calls and messages. Ctrl 1 and Ctrl 2 switch lines. Lines page: registration, “Use for calls and messages”, and Do not disturb per extension. DND is `GET`/`PUT /v1/lines/{did}/dnd` and matches PBX `*78` / `*76`. Settings also has a local “Do not disturb” that silences the ringtone on this PC.

**Calls.** Recents on the left, keypad on the right. History filters are All lines, one button per extension, and Missed. Call and Message on each row. Call back and Message use the extension stored on that row when it has one. Detail shows kind, line, time, and duration. In-call controls: Mute, Hold, Transfer, Keypad, Hang up, with M H T K and Ctrl D. Blind transfer and attended transfer (hold, consult, complete or cancel) are implemented. DTMF plays locally when keypad sound is on, and is sent on the call. The call timer runs while the call is confirmed. One incoming window, bottom right, Answer / Decline. No second toast over those buttons. Closing the window leaves the app in the tray. A mini window is used when the main window is minimized during a call.

**Audio.** Microphone, speaker, and ringer are separate settings. The mic meter reads PJSIP slot 0. A headset plugged in during a call offers “Use headset”. Choosing a speaker opens that device together with the microphone of the same headset name, and the ringtone is played through the open call speaker. “Also ring on the headset” is the older Java Sound path. Echo `*43` is dialed like any other number. There is no separate echo button.

**Messages.** Threads and a conversation for the selected line. SMS send. MMS shows images and can send a photo from file, paste, or drop. Click an image to zoom.

**Contacts.** Search in the header (Ctrl K). Import CSV or vCard. `MailboxAuth` is OAuth 2.0 authorization code with PKCE in the system browser, for Microsoft Graph `Contacts.Read` and Google People `contacts.readonly`. No client secret. Client ids are `microsoft.clientId` and `google.clientId` in the gitignored host file, or `IHF_MICROSOFT_CLIENT_ID` and `IHF_GOOGLE_CLIENT_ID`. Those ids are not in the tree, so the buttons report that sign-in is not configured. Classic Outlook and New Outlook are not used.

**Chrome of the window.** Icon rail: Calls, Messages, Lines, Settings, with an unread badge. OS title bar is kept so Windows snap and minimize keep working. Search sits in the app header under that title bar. Bounds, pin-on-top, and tray quit are saved. Link handlers: `ihfphone://` is registered to this app. `sip:` and `tel:` are registered only when empty or already pointing at IHF Phone.

**Package.** Per-machine MSI, install dir `IHF Phone`, bundled Java 17, `ihf_sip.dll`, OpenSSL, and the VC runtime. Start menu group “IT Hands Free”. Upgrade UUID `6f2c1a40-8b3e-4d1a-9c55-7e1b0a2d4f18`. Not code-signed. `jpackage` must be started from 64-bit PowerShell or the shortcut is written under `Program Files (x86)`. After `installDist`, copy `ihf_sip.dll` into the MSI input again so the packaged DLL is the one that was just linked.

## What a reviewer can treat as done

User-confirmed on earlier builds, and still in this tree: enrol, TLS register, outbound and inbound audio, echo, SMS in and out, call timer, incoming answer window, tray, hold, blind transfer, DTMF, recents actions, MMS pictures, ringtones, window size, line still there after quit.

Implemented after that confirmation and covered by unit tests where noted. Not re-confirmed on a handset in the latest MSI:

- Two registered lines, default line, per-line PBX DND, per-extension call history
- Headset speaker paired with that headset’s microphone, previous device restored if the open fails (`HeadsetMatchTest`)
- Ringtone WAV written for the call speaker (`HeadsetMatchTest`)
- Calls list: line rail, Recents / Contacts / Voicemail, All lines / per extension / Missed
- Mailbox OAuth module, including PKCE and contact JSON parsing (`MailboxAuthTest`). Live sign-in is not done
- Attended transfer
- Keypad sound, local contact file, photo zoom, privacy and licence links

## What remains

**Blocked on ids the app does not have.** Microsoft Entra public client id (loopback `http://127.0.0.1`, scope `Contacts.Read`, no secret) and a Google desktop OAuth client id (read-only contacts). Until those are configured, mailbox buttons stay on the “not set up yet” path. Do not invent ids.

**Design, not yet the round-3 screen.** The OS title bar is still the window chrome. The design wants a custom title bar with the name, search, both line dots, and Windows 11 snap on the maximize button. The line rail is extension buttons, not the segmented Business / Personal control drawn in the mock; do not hard-code those mock names. The dial pad does not yet show the contact chip under the number. Recent rows use text buttons, not the icon buttons in the mock. The in-call device row is a combo box, not the headset row with a live meter. Narrow width swaps the list and the keypad. It is not the compact phone layout. There is no shortcut remap and no system-wide hotkeys. Community light-follows-OS is not in this IHF build.

**Voice and calls.** One active call. The design’s “Hold and answer” for the other line is not built. A second incoming call is declined. Voicemail is a tab with an empty state. There is no voicemail API wired. Attended transfer has not been confirmed on a live call. The USB-headset path has not been confirmed on the Logitech device in a live call. The last report on the previous build was ringtone on the PC speakers and no call audio. 0.1.31 and 0.1.32 change that path. It still needs a call on that headset.

**Product.** No auto-update. No Authenticode signing. The installer will show SmartScreen until it is signed. `sip:` and `tel:` are not taken from another app that already owns them.

## Review notes

- GPL-2.0 on this tree and on original artwork. Do not copy the private Android tree into a Play release from here.
- Do not put a live host, a token, or a real phone number into this repo.
- `PhoneFrame.kt` is the UI. New screens should not grow it without a reason. Session and file IO already live beside it.
- JNA struct edits append fields. Reordering or inserting in the middle breaks the running DLL.
- `pjsua_set_snd_dev` closes the current device before opening the next one. Any new audio switch has to restore the previous pair on failure.
- Modal dialogs freeze the call timer. Incoming, transfer, and notices stay non-modal.
- Empty `JLabel`s that are then pinned can stay invisible. Give a label real text and a font before pinning it.
