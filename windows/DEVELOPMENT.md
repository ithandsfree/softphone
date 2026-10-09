# IHF Phone for Windows — developer notes

Working notes for anyone contributing to `softphone/windows`. Read `CODE-SPEC.md` first: it says what the build does.
This file says how we work on it, what "beta 1" means, the plan, and a log of each session. Add to the log when you
finish a piece of work so the next person (human or Claude) can pick up without asking.

## Ground rules

- **Desktop only.** Do not build or change `android/`. Uncommitted Android edits in a checkout belong to someone else.
- **No secrets in git.** Live PBX host, enrol tokens, SIP passwords, OAuth client ids, and real numbers stay out of the
  tree. Use `host.local.properties` (gitignored) or environment variables. Placeholders: `pbx.example.com`, `1001`.
  The session logs below say "the live PBX" and use 555 numbers. The unredacted operator copy (hosts, DIDs, backup
  paths) is kept in IHF's private ops docs, not in this public repo.
- **JNA struct:** append fields to `ihf_status` only. Never insert or reorder. Bump `APP_BUILD` when the DLL changes.
- **Audio switches** must restore the previous device pair on failure (see `switch_snd` in `ihf_sip.c`).
- **No modal dialogs** during calls. They freeze the Swing timers, including the call timer.
- **One branch per piece of work**, small commits, `CODE-SPEC.md` updated in the same commit as the behaviour change.
- **PBX changes** (BFF deploy, FreePBX settings, Asterisk reloads) are announced to the owner before they run.
  Read-only checks are fine.

## Beta 1: definition of done

The owner tests on their PBX, adjusts, then gives it to a client. Beta 1 is done when all of these work on a live call:

1. **Two lines registered together**, each with its own status in the title bar and the line rail.
2. **Calls on both lines:** outbound from the chosen line, inbound on either, "Hold and answer" when the other line
   rings during a call, history per line.
3. **Messages on both lines:** threads, unread counts and notifications per line; SMS and MMS.
4. **Line management:** default line, PBX do-not-disturb per line, ringtone per line, line name/colour, remove line.
5. **Setup by email:** the welcome / request-enrol email carries the line registration link and it enrols the desktop
   app through `ihfphone://` (or paste).
6. **Voicemail:** a Voicemail button dials the mailbox of that line's extension on that line, which covers listening
   to messages and recording greetings. Message-waiting count from SIP MWI when the PBX sends it.
7. **Design:** every round-3 desktop screen (`IHF-Phone-design/round-3/png/ihf/desktop-*.png`) is in place: custom
   title bar, segmented line rail, recents with Call / Message / ⋯ and call detail, compact layout, in-call headset
   row with live meter, shortcuts screen with remap, notifications and mini window, settings audio.
8. **Package:** MSI with upgrade, update check, signing step ready for a certificate, crash/diagnostic log export.

Out of scope for beta 1 unless the owner says otherwise: macOS, mailbox OAuth live sign-in (needs client ids), more
than two lines, a voicemail inbox UI (visual voicemail).

## Plan

| # | Milestone | Notes |
|---|---|---|
| M0 | Toolchain on the dev PC | VS 2022 Build Tools + OpenSSL SDK; `native/build-pjsip.ps1` reproducible here |
| M0b | BFF: bring live edits into the repo as config; bearer lifetime only if the 72 h check fails | Server change; owner approves the deploy |
| M1 | Split `PhoneFrame.kt` into screens | No behaviour change. Calls, Messages, Lines, Settings, InCall, Setup, TitleBar |
| M2 | Dual-line voice | Per-line REGISTER status (append fields), second incoming on other line rings instead of 486, Hold and answer, per-line ringtone |
| M3 | Dual-line messaging | Per-line threads and unread, badge and notification name the line |
| M4 | Voicemail | Dial the mailbox for the line's extension; MWI subscription for the count |
| M5 | Round-3 design | Title bar, line rail, recents and detail, compact, in-call row, shortcuts + remap |
| M6 | Setup email end-to-end | Desktop link in the welcome mail, request-enrol from the desktop, second line by email |
| M7 | Beta packaging | MSI, update check, signing hook, diagnostics export, release notes |
| M8 | Community edition (after beta 1) | Briefing doc, then the unbranded build on the community mockups + IHF-build design changes; stock FreePBX only |

## How the app reaches the PBX

Hostnames for real systems live in the private repo `ithandsfree/pbx` (`pbxs/<slug>/softphone.env`), never here.
Copy `SOFTPHONE_API_BASE` and `SOFTPHONE_SIP_DOMAIN` from there into `windows/host.local.properties`.

- **App API (BFF):** a PHP app under `/ihf-softphone/` on the PBX, usually on a **non-admin HTTPS port** (for example
  `:8443`), with bearer token `X-IHF-Token`. That public edge allows `/ihf-softphone/` and **denies `/admin` and
  `/v1/admin/*`**. Admin actions (assign extension, welcome email) only work on the trusted `:443` side.
- **Voice:** PJSIP on the PBX, TLS 5061 then TCP 5060, SIP domain = PBX hostname. Credentials come from
  `GET /v1/lines/{did}/sip-credentials` after enrol, never typed by the user.
- **Texts:** FreePBX SMS Connector behind the BFF (`/v1/lines/{did}/threads`, `/messages`).
- **Deploy the BFF:** from the private repo, `SOFTPHONE_SRC=<this checkout> ./scripts/deploy-client.sh <slug>`
  (runs `api/deploy-to-pbx.sh` as root over SSH). Announce before deploying to a live PBX.

### End to end (read from `api/` and the clients, 2026-10-08)

The BFF bootstraps FreePBX (`/etc/freepbx.conf`) and calls User Manager, the SMS module, Core and the Asterisk
manager in-process. Clients never see FreePBX admin or UCP.

1. **Enrol.** Admin "assign" or public `POST /v1/request-enrol {email}` finds the User Manager user and issues one
   token per extension (`TokenStore`, TTL `token_ttl_seconds`), writes `/enrol/<token>/index.html` (opens
   `ihfphone://enroll/<token>`), and mails one welcome with the default extension first and the others as extra
   lines. The response is always `check_inbox` (no account enumeration).
2. **Session.** `GET /v1/session` with the token returns user + `lines` (did, extension, capabilities, dnd).
3. **SIP secret.** `GET /v1/lines/{did}/sip-credentials` reads the device secret from FreePBX Core. Never mailed.
4. **Voice** is direct PJSIP to Asterisk with the extension's own credentials. No BFF in the call path. Every
   contact on the AOR rings (phone and PC fork). Voicemail = the extension's Asterisk mailbox.
5. **Texts** are BFF only: `threads`, `threads/{peer}/messages` (marks read), `POST messages`, `messages/media`
   (1.5 MB), `GET /v1/media/{name}`. No push: clients poll threads. Every route checks `userOwnsDid`.
6. **DID → extension:** User Manager assigned devices when exactly one; else the live hand-coded map (see log).
7. **DND:** FreePBX Donotdisturb module (`*78`/`*76`, UCP), else AstDB `DND/<ext>` + `Custom:DND<ext>`.
8. **Bearer renewal.** Android has two sign-ins: enrol token only, or User Manager username/password, which it
   stores and uses to re-login on 401 (`SoftphoneViewModel.tokenFor`). Windows has the enrol path only and no
   re-login. **Decided 2026-10-08 (owner): option (a)**
   **Security to-do with (a):** `lines.properties` today holds the SIP secret and the bearer in plain text. Before
   storing User Manager passwords too, encrypt these values per Windows user (DPAPI via JNA `Crypt32Util`). — Windows gets the same User Manager sign-in as Android,
   stores the credentials per line, and re-logs in on 401. A server-side long-lived device token may come later.

## Dev environment

- JDK 17 is bundled at `windows/.jdk/` (gitignored). Set `JAVA_HOME` to `windows/.jdk/jdk-17.0.20.1+1`.
- **Gradle "Unable to establish loopback connection":** the JDK's selector pipe fails when the temp path is long or
  a sandbox blocks it. Point temp at a short folder:
  `set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\ai-projects\softphone\.tmp -Djava.io.tmpdir=D:\ai-projects\softphone\.tmp`
- `gradlew.bat test` runs the unit tests (30 on 2026-10-07, all passing).
- Native: `native\build-pjsip.ps1` builds PJSIP 2.17 into `windows/third_party/` (gitignored) and links
  `native/out/ihf_sip.dll`. After the first full build, relink only `ihf_sip.c`.
- Test PBX: `host.local.properties` with `apiBase` and `sipDomain`. Test extension: ask the owner; never commit it.

## Decisions

| Date | Decision | Why |
|---|---|---|
| 2026-10-08 | Owner handed technical ownership to Claude: framework, architecture and security calls are made here and recorded in this table. Live PBX deploys and anything a client sees before owner testing stay with the owner. | Owner request |
| 2026-10-08 | **FlatLaf 3.5.4** (+ flatlaf-extras, jsvg) as the Swing look and feel. | Only way in Swing to draw the round-3 custom title bar *and* keep Windows 11 snap layouts (native decorations). Also gives arcs, focus rings, hover states. Apache-2.0, GPL-compatible. |
| 2026-10-08 | Bundle **Inter, Instrument Serif, IBM Plex Mono** (OFL 1.1) under `resources/fonts`, licences beside them. | Fonts named in `design-tokens.json`. |
| 2026-10-08 | Icons = the design team's SVG symbols (`round-3/_src/head.html`) extracted to `resources/icons/ui/*.svg`, tinted at runtime by `ui.Icons`. | Same artwork as the mockups; no third-party icon licence. |
| 2026-10-08 | New screens live in `win/ui/` as view classes with callbacks; `PhoneFrame` keeps session wiring and shrinks as each old screen is replaced. New code uses design pixels, not `UiScale`. | Splits the 3,400-line frame; Java already applies per-monitor DPI, `UiScale` double-scaled. |
| 2026-10-08 | Answers to round-3 `DECISIONS.md` engineering items: #8 stack = Swing + FlatLaf (not Compose); #9 phone + PC share the extension, the live PBX AOR allows 7 contacts; #10 self-service email link is supported by the BFF (`/v1/request-enrol`). Design source of truth: `D:\ai-projects\IHF-Phone-design\round-3` (same as `round-3.zip`); desktop tray/overlay icons from `desktop-icons/` are already in `resources/icons`. | Owner pointed to the design folder |
| 2026-10-08 | Call history and recordings follow **UCP's Call History permissions** (User Manager), read in the BFF through FreePBX's own `Ucp` / `Cdr` classes; the app never uses UCP cookies. | Owner pointed at UCP; one permission model for admins, no new knobs |
| 2026-10-08 | IHF branding of the welcome mail and enrol page lives in `config.php`, not code. | Public repo stays generic; live PBX keeps its look across deploys |
| 2026-10-08 | Secrets sealed with DPAPI (`SecretBox`); User Manager sign-in with re-login on 401. | See session log. |

## Round-3 design parity

Mockups: `IHF-Phone-design/round-3/png/ihf/desktop-*.png`. Check each against a capture of the running app.

| Screen | State | Notes |
|---|---|---|
| Title bar (all screens) | Done | FlatLaf embedded menu bar: mark, name, Ctrl K search, line dots, DND / On call timer, native snap |
| Nav rail | Done | `ui/NavRail`: icons, coral missed badge, action unread badge, shortcuts key, account presence dot |
| Calls list + keypad (`desktop-calls`) | Done | `LineRail`, `UnderlineTabs`, `Chip`s, `RecentRow`, `DialPad`; one chip per line when two lines (owner requirement) |
| Messages (`desktop-messages`) | Done | `ThreadCell`, `ConversationHeader`, `ViaBar`, bubbles, day separators, `AttachmentCard`, `ComposerBox` |
| In call (`desktop-in-call`) | Done | `InCallPane`, `RoundControl`, `DeviceRow` with meter, `ActiveCallCard` |
| Call detail (`desktop-call-detail`) | Done | `ui/CallDetailPane`: Call back from the call's line + arrow for the other, Message, Copy, Open/Add contact, history card incl. latest text with Open |
| Lines / line management | Done | `ui/LinesPage`: cards (`lines-home`) + detail (`line-detail`): rename, default, PBX DND segmented, admin caps read-only (`GET /v1/lines`), ringtone per line, sign-in/out, set up again, remove with inline confirm |
| Settings · audio (`desktop-settings-audio`) | Done | `ui/SettingsShell` (lines + 6 sections), `Switch`, `LevelBars`; one honest voice-processing switch (Speex AEC = EC + NS + AGC together) via new `ihf_sip_set_ec` |
| Incoming + mini window (`desktop-notifications`) | Done | `ui/IncomingWindow` (line-colour band, Answer/Decline, Ctrl Enter / Ctrl D / Enter / Esc), `ui/MiniCallWindow`; not yet seen on a live incoming call |
| Recordings in call detail | Done (client) | Play / Save per history row; needs the BFF deploy below |
| End call / ringing line (owner feedback) | Done | Vivid red + hang-up glyph; `LineChip`, "Answer · <line>" |
| Shortcuts (`desktop-shortcuts`) | Done | `Keymap` + `ui/ShortcutSheet` (? and rail key), Settings remap with conflict checks, opt-in system-wide keys (`GlobalHotkeys`, RegisterHotKey) |
| Compact < 720 px (`desktop-compact`) | Done (Calls checked) | `ui/BottomNav`, `CircleButton`, `DialPad.compact`, Keypad / Recents / Contacts tabs, pin + expand in title bar; Lines master/detail, Settings section drop-down. Messages, Lines, Settings not yet captured at 400 px |
| Welcome (`desktop-welcome`) | To do | Restyle setup pages |

Dev helpers (outside the repo, in the session scratchpad): `restart.ps1`, `shot.ps1` (DPI-aware window capture),
`keys.ps1`, `click.ps1`. A non-DPI-aware capture crops the window on a scaled display — do not judge layout from it.

## Session log

### 2026-10-08 — end of day: state of the desktop app (0.1.41)
**Confirmed by the owner on live calls:** both lines registered (TLS), calls and texts on both lines, call history with
recordings, branded MSI install/upgrade, mute (microphone only), blind transfer (including from an AI receptionist
hand-off and from a waiting call), Send to my other devices, call waiting (Hold & answer, Swap), voicemail list,
playback and download.

**Built, not yet confirmed on a live call:** call volume, diagnostic log export, attended ("Speak first") transfer, keyboard
remap and system-wide keys, compact window (Messages, Lines and Settings not captured at compact width).

**Open for beta 1 (in order):**
1. Instant voicemail count from the PBX's unsolicited `message-summary` NOTIFYs (PJSUA `on_mwi_info`). Today the app
   polls every 60 s.
2. Welcome / setup screens in the round-3 design. Compact-width check of Messages, Lines and Settings.
3. M7 packaging: update check, signing step (Authenticode certificate decision pending; not an SSL certificate).
4. Ship the BFF's 404-body correction for voicemail (`voicemail_not_found`) with the next BFF deploy.
5. Owner-held idea: one-click "Send to <other line>" on the call-waiting bar.

**Outside the softphone:** transferred calls into the AI receptionist are dropped (Stasis on a Local channel). This is
for the AI receptionist session (brief in IHF's private docs). The softphone needs no change.

**After beta 1:** M8, the community briefing and community edition.

### 2026-10-08 — manual transfer confirmed; AI receptionist gap (Claude)
- Owner on 0.1.41: manual blind transfer from a waiting call to 9333 works. The desktop dropped the transferred call
  and the Fanvil and desktop rang. Unanswered, the call was hung up instead of reaching Solomon: 9333's Follow Me
  no-answer is `from-ai-agent-rwize-workEL`, and on a transferred call that runs Stasis on a Local channel, which the
  AI engine cannot take (call C-000000f4).
- Decision (owner): no further custom dialplan. The softphone + BFF use standard FreePBX only. IHF extras (tenant gate,
  AI receptionist) must work with standard FreePBX behaviour such as Local channels on transfers. Brief written for the
  AI receptionist session: `IHF-website/docs/BRIEF-AI-RECEPTIONIST-LOCAL-CHANNELS.md`.
- Proposed next softphone change (held until the owner says so): a one-click "Send to <other line>" on the call-waiting
  bar.

### 2026-10-08 — 0.1.40 test review, 0.1.41 caller names (Claude)
- Owner on 0.1.40 reported: transfer "did not drop the call", "did not ring the Fanvil", and declining sent the caller
  nowhere instead of to voicemail. Read from the exported log plus the PBX log:
  - The transfer **worked**: REFER, NOTIFY 200 (final), "transfer complete; our leg hung up" at 20:13:45. The app
    logged the target as **903** (`transfer requested to 903`), and Asterisk dialled 903. The call that kept showing
    was that transferred call ringing this desktop again on 903, as a waiting call.
  - The Fanvil (9333 contact :16942) unregistered at 20:13:03 and re-registered at 20:18:03, so it could not ring
    during the test. 903 has no desk phone. Its second contact (:36873, same office IP, answers OPTIONS) is most likely
    the Android app.
  - Decline (486) **did** reach voicemail: the PBX waited for the other 903 contact's 15 s ring time, played the greeting
    at 20:14:02 and saved `903/INBOX/msg0000` at 20:14:24. The caller heard about 8 s of ringing after the decline.
- 0.1.41: `callerName()` hides PBX caller names that are noise ("CID:<number>", digits, unknown). That voicemail's
  callerid was `"CID:15555550193" <14165550177>`. Test added.

### 2026-10-08 — 0.1.40: transfer leaves our leg up (Claude)
- Owner on 0.1.39: Hold & answer works, Swap works (repeatedly), and Mute now silences only the mic. **Bug:** after a
  blind transfer of the current call (with another call on hold), the call stayed "active" on the desktop.
- Diagnostic log 20:05:35: REFER 202, NOTIFY 100 / 180 / **200 (final)**, but Asterisk never sent BYE for the
  transferor leg (call 2). The user hung it up at 20:06:00. The transferred call also rang this desktop on 9333 and got
  486, because both call slots were still taken.
- Fix (native): in `on_call_transfer_status`, a final 2xx NOTIFY now hangs up our leg and stops further NOTIFYs
  (RFC 5589 transferor behaviour, as in pjsua's own sample app). This also covers attended transfer, which uses the same
  callback.
- Voicemail empty state: the HTML label with a fixed CSS width was cut off at 125 % display scaling. It is now a
  centred `JTextPane` that wraps to its width, coloured from the pane's tokens (no IHF palette hard-coded).
- Seen in the log, for later: Asterisk sends unsolicited `message-summary` NOTIFYs (MWI) to both lines. PJSUA answers
  200 and ignores them, so the instant voicemail count needs only an `on_mwi_info` hook.

### 2026-10-08 — 0.1.39: call waiting, voicemail double delete (Claude)
- **Owner report:** during a call a second call rang the other phones but nothing showed on the desktop. The
  diagnostic log shows `second call rejected` (19:49, 19:50, 19:52): the PBX offered the call and the engine answered
  486 (one call at a time). No PBX change needed.
- **Native (append-only):** status gains `waiting_state` (0 none, 1 ringing, 2 the other call on hold),
  `waiting_id`, `waiting_remote`, `waiting_ext`. New API: `ihf_sip_set_call_waiting`, `ihf_sip_waiting_answer` (holds the
  current call, answers the waiting one; the two swap roles *before* `pjsua_call_answer`, so the CONFIRMED callback
  finds the answered call as current), `ihf_sip_waiting_end` (486 for a ringing call, so the PBX carries on as for a busy
  line; BYE for the call on hold), `ihf_sip_swap` (hold current, unhold other, swap). While a call is up, a second
  INVITE gets 180 and a tonegen beep (2 × 220 ms 440 Hz every ~4.5 s) is played to the sound device only, never into the
  call. When the current call ends with a second call present, that call becomes current: a ringing one turns into a
  normal incoming call (the app rings), a held one stays on hold until Resume. No second call during "Speak first"
  (consult), and no consult while two calls are up.
- **App:** `CallTracker` follows each call by id, so each call has its own timer and its own Recents row (the old logic
  logged on the on-call → idle edge only and would lose a call or mix up timers). Tests: `CallTrackerTest`.
  `ui/CallWaitingBar` above the in-call controls: "INCOMING ON <LINE>" + caller with **Hold & answer · <line>**
  (green) and **Decline**; then "ON HOLD · <LINE>" with **Swap** and **End**. The incoming window also opens for a
  waiting call ("Hold & answer · <line>"), with no ringtone (the engine beeps). Ctrl Enter answers it, Ctrl D declines
  it. Do not disturb on this PC declines a waiting call (busy). Settings › Notifications › **Call waiting** (default on,
  `callWaiting` in `window.properties`).
- **Voicemail double delete:** the PBX log showed two DELETEs in the same second (the first succeeded, the second found
  nothing and the app showed `voicemail_delete_failed`). The app now sends one delete per message, and a 404 on delete
  counts as done. The BFF now returns `{"error":"voicemail_not_found"}` with its 404s (repo only, ships with the next
  BFF deploy).
- Not yet tested on a live call: everything in this entry. MSI `IHF-Phone-0.1.39-x64.msi` built.

### 2026-10-08 — 0.1.38: voicemail action row (Claude)
- Owner on 0.1.37: the voicemail list and playback work (903's June message plays, Call back greyed for a withheld
  caller ID). Save and Delete were not visible. The action row was a one-row FlowLayout capped at 46 px, so the third
  and fourth buttons wrapped out of sight in the 460 px list pane.
- Fix: the row uses `WrapLayout`, and the open card's maximum height follows its preferred height. Save is now a labelled
  **Download** button (icon + text). `WrapLayout` now reserves the same `2 × hgap` that `FlowLayout.layoutContainer`
  does, so its height estimate matches the real wrap (this also affects the history chips).
- Not yet seen on screen (the owner's installed app was running); owner to confirm on 0.1.38.

### 2026-10-08 — 0.1.37: voicemail (Claude)
- **BFF** `api/lib/VoicemailBox.php` + routes `/v1/lines/{did}/voicemail[/count|/{id}/audio|/{id}/heard]`, DELETE
  `/v1/lines/{did}/voicemail/{id}` (see `docs/BFF_INSTALL.md`). Only stock FreePBX APIs are used: Voicemail module
  (`getMessagesByExtension`, `moveMessageByExtensionFolder`, `deleteMessageByID`), UCP/User Manager Voicemail settings
  (enable, assigned incl. "self", playback, download), `checkVoicemailEnabled`, and the legacy `featurecode` class for
  the PBX's "My Voicemail" code (`dial` in the list; *97 by default). Audio is served only for a message the module lists
  for that extension, realpath-checked under the spool. GSM/WAV49 are converted to PCM with sox. `clearCache()` runs
  before every read because the module caches one mailbox per object.
  Read-only tested on the live PBX from a scratch folder (903: one Old message, audio found; asking for it through 9333 returns null).
  **Deployed to the live PBX 19:37 (owner approved):** backup `<server backup tgz>`; only
  `lib/VoicemailBox.php` and `public/index.php` changed (live index.php had no drift from the repo). Health ok, voicemail
  401 without a token, admin 403 on 8443, md5 matches, no PHP errors, live text polling 200 across the switch.
- **App** `ui/VoicemailPane` (`voicemail.png`, `voicemail-empty.png`): line chips, closed rows (play, caller,
  line · when, length, new dot), the open card (player with scrubber, Call back from <line>, Text, Save, Delete with a
  4 s confirm because the PBX keeps no copy) and the empty state. The "Call voicemail (<code>)" button uses the
  PBX's code. Playing marks a message heard (moved to Old). Mailboxes reload when the tab opens and every 60 s. A new
  message gives a toast, "Voicemail · n" on the tab and adds to the Calls badge. `RecordingPlayer` gained pause, resume,
  seek and progress. Compact layout shows four tabs (Keypad, Recents, Contacts, Voicemail). Tests: `VoicemailTest`.
- Not done: SIP MWI (the 60 s refresh covers the badge), greetings recorded or uploaded inside the app.
- **Portability rule (owner, 2026-10-08):** the softphone + BFF must install cleanly on someone else's FreePBX. Use
  stock module APIs and settings only. Nothing may depend on IHF's custom dialplan (e.g. the live PBX's `sub-ihf-tenant-gate`
  / `ihf-xfer-dest`, which are IHF PBX config, not part of this package). Read PBX-specific values (feature codes,
  permissions) from FreePBX instead of hard-coding them.
- **After beta 1 (owner, 2026-10-08):** write the community briefing, then build the community edition. It drops the
  IHF branding and uses the community mockups (`IHF-Phone-design/round-3/png/community/`) plus the design changes made
  during the IHF build (title bar, extension labels, transfer to my devices, voicemail, compact, shortcuts).

### 2026-10-08 — 0.1.36: mute fix (Claude)
- Owner confirmed on 0.1.35: the transfer from an AVA hand-off and "Send to my other devices" both work. The branded
  installer is fine.
- **Bug:** Mute also silenced the caller. `ihf_sip_set_mute` used `pjsua_conf_adjust_tx_level(0, …)`. On conference
  slot 0 (the sound device), *tx* is what the speaker/headset plays and *rx* is the microphone, so mute silenced the
  headset. All five places now use `pjsua_conf_adjust_rx_level(0, …)`. A call that ends muted now clears the mute, so the
  next call never starts muted (before this, a muted hang-up left the next call silent in the headset).
- Live check pending: mute during a call, hear the caller, and the caller does not hear you.

### 2026-10-08 — 0.1.35: diagnostics, transfer results, call volume, send to my devices (Claude)
- **Owner report:** low audio and a failed transfer on an AVA hand-off call (10:24, 9333). PBX log: the desktop's
  REFER reached Asterisk and `sub-ihf-tenant-gate` refused it (AVA flag checked before the transfer branches;
  `BLINDTRANSFER` not visible on the Local channel). Fixed on the live PBX with owner approval: see
  `IHF-website/docs/ava-freepbx-log.md` (2026-10-08 tenant gate entry). Live test pending.
- **Diagnostic log** (`DiagLog.kt`): the native log ring buffer (now 512 × 600 chars, one SIP header per line,
  Authorization headers replaced by `[removed]`) is drained into `%LOCALAPPDATA%\IHF Phone\logs\ihf-phone-<date>.log`.
  14 days are kept, with a 25 MB/day cap. App toasts and failed actions (with stack) are logged too. During a call it
  samples the incoming audio level every 250 ms and writes avg/peak every 15 s and at call end; a low peak means the
  audio arrives quiet, a normal peak with "barely hear" points at the PC's device or volume. Call end logs the SIP
  status and PJSIP's call dump (codec, jitter, loss). Settings › Advanced › Diagnostics: Save diagnostic log (zip +
  summary, no secrets), Open log folder. Test: `DiagLogTest`.
- **Native (append-only):** `ihf_status` gains `xfer_code`, `xfer_final`, `xfer_text` (from
  `on_call_transfer_status`); new `ihf_sip_rx_level()` and `ihf_sip_set_rx_gain(percent)` (applied with
  `pjsua_conf_adjust_rx_level` on every call's slot). "Speak first" (consult) now uses the account of the call it
  belongs to, not the line chosen for new calls. DLL relinked with `build-pjsip.ps1`.
- **Transfer feedback:** `blindTransfer` watches the NOTIFY result: "Call sent to …", "Transfer to … failed: 486
  Busy", or a 20 s timeout message. Note: when the PBX itself refuses after answering the Local leg (as the gate did),
  SIP still reports success; the PBX log is the only witness.
- **Send to my other devices:** first button in the transfer dialog. It makes a blind transfer to the call's own
  extension, so the desk phone, mobile app and other devices on it ring.
- **Call volume:** Settings › Audio "Call volume" (Normal 100 %, Louder 150 %, Loud 200 %, Loudest 300 %), also in the
  in-call device menu. Saved as `callVolume` in `audio.properties`.
- Found while checking: the desktop registers both lines over TLS (`;ob` contacts). The plain-TCP 903 contact on the
  PBX is another device at the office.
- Not yet verified on a live call: everything above. MSI `IHF-Phone-0.1.35-x64.msi` built, not installed.

### 2026-10-08 — 0.1.34: shortcut remap, compact window, extension labels (Claude)
- **Keymap** (`Keymap.kt`): every action on the design sheet is a `Shortcut` with a default `Chord`. Saved in
  `%LOCALAPPDATA%\IHF Phone\shortcuts.properties` (only changed keys, `ACTION=ctrl+shift+<KeyEvent code>`).
  Refused keys: Windows/text keys (Ctrl C/V/X/Z/Y/A, Tab, Esc, arrows, digits, Alt F4 …), plain letters outside a
  call, and a key another action already has. A refused key in the file falls back to the default. Tests:
  `KeymapTest`.
- `dispatchShortcut` now matches the keymap first, then the fixed Recents keys. It only handles keys from this
  window or windows it owns. New actions: Ctrl Shift D toggles PBX DND on the active line; Ctrl ↓ / Ctrl ↑ move
  between threads.
- **Sheet** (`ui/ShortcutSheet`): popup layer over the window with a dimmed backdrop, built from the live keymap.
  `?` or the rail keyboard key opens it. Esc, ×, or a click outside closes it.
- **Settings › Keyboard shortcuts**: one `ShortcutEditRow` per action with Change (captures the next key press via
  `ui.KeyCapture`, Esc cancels) and Reset, plus Reset all. System-wide keys sit behind a switch (off by default).
- **System-wide keys** (`GlobalHotkeys.kt`): `RegisterHotKey` on a dedicated message-loop thread. It is restarted
  on every change and stopped on quit. If another app already owns a key, a toast says so. Only letters, digits and
  F-keys are allowed (their Java codes equal Windows VKs).
- **Compact window** (< 720 px, `applyWindowShape`): the nav rail gives way to `ui/BottomNav`, and the title bar
  shows pin + expand instead of search/status (`TitleBar.compact`). Calls becomes one pane: line rail and
  Keypad / Recents / Contacts tabs on top, then the keypad (30 px number, 52 px keys, round gold call button with
  backspace) or the list. Opening a recent's detail or a call shows that pane; Back returns to the list. Expand goes
  back to the last wide size (or 1100 × 760). Lines becomes master/detail with "All lines"; Settings sections
  become a drop-down. `WidthTrackingPanel` keeps recents and contacts within the viewport, and `WrapLayout` wraps
  the history chips. Recent rows drop the call length before the time when space runs out.
- **Line labels (owner decision):** a line is shown by its extension ("9333") unless the owner sets a custom name on
  the Lines page. That way a line moved to another person never carries a wrong name. Renaming to the extension
  or to blank clears the custom name. This PC's old "Business"/"Personal" labels were removed from
  `lines.properties` (backup `lines.properties.bak-labels`). Until 0.1.34 is installed, the installed 0.1.33 shows
  "Line 1"/"Line 2".
- Captured at 400 × 820: keypad matches `desktop-compact.png`, Recents wraps its chips. Not yet captured: Messages,
  Lines, Settings at compact width, the sheet, and the Settings remap rows. The owner needed the PC back, so those
  are for the next session. MSI `IHF-Phone-0.1.34-x64.msi` was built but not installed.

### 2026-10-08 — round-3 design, stages 1–4 (Claude)
- FlatLaf + tokens + fonts + icons; title bar; nav rail; Calls; Messages; In call (see table above).
- Fixed on the way: sent/received bubbles were on the wrong sides (BoxLayout mixed alignment); recents drawn before
  lines were restored; open conversation not reloading when the phone read the reply first; translucent row
  background smearing; conversation header rebuilt every 300 ms poll; dropped files read with no size limit
  (now 25 MB cap before MMS resize); painted controls had no AccessibleContext (crash + invisible to Narrator).
- Verified live on the test line: echo call (`*43`) shows the in-call pane, Ctrl D hangs up (no channel left on PBX).

### 2026-10-08 — design round 2, startup crash, MSI (Claude)
- End/Decline: own tokens `endCall` #E5484D → `endCallDeep` #C2262B, white `icons/ui/hangup.svg` (original drawing;
  Material's call_end is Apache-2.0, not GPL-2.0-compatible). `danger` stays coral for text and badges.
- Ringing line is unmistakable: `ui.LineChip` (tinted pill, LINE NAME + number), line-colour outline and band,
  "Answer · <line>" on the incoming window and the in-app ringing pane. Answer green and line colours never share a
  control. Design preview: `gradlew run -Ppreview=incoming`.
- **Startup crash (root cause of "desktop shortcut does nothing"):** JDK 17 builds NIO selector pipes on AF_UNIX
  sockets in %TEMP%. On the owner's PC anything under the user profile allows bind but blocks connect, and the JDK
  only falls back to TCP when bind fails, so `HttpClient` threw "Unable to establish loopback connection" and the
  app exited (the installed 0.1.32 too; it never started from its shortcut). Fix: `jdk.net.unixdomain.tmpdir`
  points at a folder that never exists → bind fails → loopback TCP pipe (secret-checked). Set in the MSI launcher
  options and first thing in `main()` (`useLoopbackPipes`). Proved with a probe on the failing profile path.
- **Correction:** the earlier note that the shortcuts pointed at `Program Files (x86)` came from a 32-bit
  PowerShell (WOW64 path redirection). Check shortcuts and HKLM from 64-bit PowerShell
  (`C:\Windows\Sysnative\WindowsPowerShell\v1.0\powershell.exe`). The Claude Code PowerShell tool here is 32-bit.
- **MSI (M7 started):** `packaging/build-msi.ps1` + `packaging/IhfPhone.wxs`, WiX 4.0.5 (dotnet tool, .NET 8 SDK),
  no WiX 3 / .NET 3.5. jpackage app image (trimmed runtime incl. `jdk.accessibility`), native DLLs +
  VCRUNTIME140 (UCRT is in Windows 10/11; checked with dumpbin), `host.local.properties` copied in when present.
  Per-machine x64 into `Program Files\IHF Phone`, desktop + Start menu "IT Hands Free" shortcuts, same UpgradeCode
  as the old MSIs (replaced the broken 0.1.32 here), GPL licence page, generated navy/gold WixUI art.
  Verified on this PC: installs (exit 0), Add/Remove shows "IHF Phone 0.1.33 · IT Hands Free", desktop shortcut
  starts the app, second click restores it from minimised, both lines register over TLS. Not code-signed yet.
  The jpackage launcher runs as a parent + child process pair; that is normal.

### 2026-10-08 — BFF deployed to the live PBX (Claude, owner approved)
- Added before deploy (security items 3–6): `LoginLimiter` on `/v1/login` (5 failures per user+IP, 20 per IP,
  15 min, `Retry-After`, fail2ban-friendly log line); tokens stored as SHA-256 only (legacy raw rows migrate on
  first use); setup links limited to `setup_link_max_uses` (3); `cors_origin` off; client IP from `REMOTE_ADDR`
  unless `trusted_proxies` (X-Forwarded-For was spoofable on the enrol limiter too). Behaviour-tested on the
  PBX's PHP in temp files before deploy.
- Deploy: backup `<server backup tgz>` (path also in
  `<server backup note>`), `config.php.bak-20261008`; config keys added via `var_export`
  (25 keys, owner/mode kept); code from an LF-normalised copy through `ithandsfree/pbx` `deploy-client.sh <pbx>`.
- Verified: health ok; every deployed file's md5 matches the staged copy; 8443 still 403 on `/admin` and
  `/v1/admin/*`; live text polling stayed 200 across the switch to hashed tokens; the Windows app upgraded both
  lines to device tokens (one per UCP user, expiring 2027-04-06, sliding); welcome mail renders the IHF branding
  from config.
- Correction: recordings are allowed for **both** lines' own UCP users (9333 → its own user; the earlier
  "903 only" was checked against the wrong user). 9333: 30 of the last 50 calls recorded.
- Rollback: `tar xzf <backup> -C /` restores `/var/www/html/ihf-softphone` and `/var/spool/asterisk/ihf-softphone`.

### 2026-10-08 — settings, call windows, recordings, BFF reconcile (Claude)
- **BFF reconciled with the live PBX (M0b, in repo, not deployed):** live hand edits became config keys —
  `did_extension_map`, `mail_brand_line`, `mail_footer_line`, `mail_emblem` (+ existing `mail_from(_name)`).
  Welcome mail and `enrol/_template.html` are the live IHF dark design with placeholders
  (`__PRODUCT__`, `__BRAND_LINE__`, `__FOOTER_LINE__`, `__EMBLEM__`). Every placeholder is now HTML-escaped and
  the install link must be `https://` (was raw, admin-supplied). Admin UI placeholders and the admin setup script
  stay generic (live had personal defaults). All BFF files lint clean with the PBX's PHP (`php -l` over stdin).
- **Call history + recordings (`lib/CallHistory.php`):** `GET /v1/lines/{did}/calls`,
  `GET /v1/lines/{did}/calls/{id}/recording[?download=1]`. Same rules as UCP: Userman `ucp|Cdr` enable /
  assigned (incl. "self") / playback / download; rows from `Cdr::getCalls`; file only via
  `Cdr::getRecordByIDExtension` + realpath under the monitor dir. Dry-run on the live PBX over stdin: list,
  path, cross-extension refusal and traversal refusal all correct. FreePBX 17 cdr throws (Whoops) instead of
  returning false when the extension does not match — caught.
- Live permissions found: the owner's UCP Call History is assigned **903 only**, so 9333 recordings are refused
  for that user until 9333 is added in User Manager (UCP → Call History). 9333 records all calls; 903 records none.
- Client: `matchPbxCall` links a local history row to its CDR call (number + end time within 2 min);
  `RecordingPlayer` plays on the call speaker; Save honours the download permission.
- Native: `ihf_sip_set_ec(int)` appended to the ABI (relinked on this PC, 0 errors).

- **Device tokens (owner: the setup link must provision voice *and* messages, no sign-in screen):**
  `/v1/session` now answers a setup/login token with a long-lived **device** token (sliding,
  `device_token_ttl_seconds`, 180 d default, extended when < half is left). `POST /v1/logout` revokes the
  presented token; the Windows app calls it when a line is removed. Admin sessions are refused on app routes.
  `TokenStore` writes are now under `flock` (tested on the PBX PHP in a temp file: 200/200 concurrent tokens
  kept; slide, expiry and revoke behave). Windows: `PhoneSession.upgradeTokens()` swaps each line's setup token
  at start; the Lines page "Messages sign-in" section is gone. Android already stores a non-blank `token` from
  `/v1/session`, so a phone set up after the deploy gets a device token; a phone set up earlier by link keeps its
  setup token until it expires, then needs its setup email once (lines added by User Manager login renew already).
- **Timing:** this PC's two lines hold setup tokens issued 2026-10-07 ~19:00 EDT that expire ~2026-10-10 19:00.
  Deploy before then and the next app start upgrades them; after, each line needs its setup email once.

**Deploy plan for the BFF (owner approval needed):**
1. `tar` the live `/var/www/html/ihf-softphone` (minus `dl/`) to `<server backup tgz>`.
2. Add to the live `config.php`: `did_extension_map` (15555550193 ⇒ 9333, 15555550199 ⇒ 903),
   `mail_brand_line` = IT Hands Free, `mail_footer_line` = Canadian-hosted Cloud PBX,
   `mail_emblem` = brand/ihf-emblem.png.
3. `SOFTPHONE_SRC=<this checkout> ./scripts/deploy-client.sh <pbx>` from `ithandsfree/pbx` (keeps `config.php`).
4. Check: `/v1/health`, a texts request for each line, `/v1/lines/{did}/calls`, one recording, a fresh enrol page,
   and that `/v1/session` with a setup token returns a device token (restart the Windows app: lines upgraded).
5. Roll back = untar step 1.

### 2026-10-08 — call detail and Lines page (Claude)
- Call detail and Lines page built (see parity table). Verified on screen against live PBX data: caps SMS/MMS on
  for both lines, DND read per line, default line = Business.
- **Per-line ringtone:** `AudioPrefs.ringtone.<ext>` (built-in ids; blank = default). `CallRinger.lineExtension`
  is set from `snap.callExtension` while ringing, so the ringing line's tone plays. Custom file stays global.
- **Remove line:** `PhoneSession.removeLine` refuses during a call, drops the line and its sealed secrets, then
  `SipBridge.stop()` + `registerAll()` so book index = native account slot again (ihf_sip has no per-account
  remove). Last line removed also clears `line.properties` so the next start shows setup. PBX untouched.
- Removed the old Lines card code that fetched DND from the PBX on every line-list rebuild.
- `PillButton` measured its width before FlatLaf style margins applied (truncated labels); now measured live.

### 2026-10-08 — owner feedback round 1 (Claude)
- Lines named by the owner: 903 = Personal, 9333 = Business (stored as `N.label` in `lines.properties`).
- **SMS delay on 9333 is upstream:** a reply sent at 01:30:00 reached the PBX at 01:33:25 as **six** VoIP.ms webhook
  GETs to `/smsconn/provider.php` within 2 s, each answered `202` (same as the single, on-time deliveries earlier).
  Nothing in the app or BFF delayed it. If it repeats, ask VoIP.ms with the DID and time. The transcript now
  collapses identical same-direction messages within 10 s (`ui.collapseRepeats`); the BFF still stores all rows.
- Photo viewer kept no aspect ratio (width and height clamped separately) — now fitted with shape kept, bicubic,
  Esc closes. Thumbnails drawn from a 2× high-quality copy (`ui.fitImage`), sharp on HiDPI.
- Memory: up to 40 decoded originals were cached (~48 MB each for a 12 MP photo). Now 8 originals
  (`FULL_PHOTOS_KEPT`), thumbnails capped at 60, originals refetched on demand.
- In-call controls redrawn as 72 px gradient discs without outlines (`RoundControl`), glow on End/Answer/active,
  round Answer/Decline. Title-bar mark is the vector `brand/ihf-emblem.svg` (was the rimmed PNG app icon).

### 2026-10-08 — 0.1.33: User Manager sign-in and sealed secrets (Claude)
- Owner confirmed calls both ways on 903 with the locally linked DLL.
- Option (a) built: `BffClient.login`, `PhoneSession.signIn` / `attachSignIn` / `signOut`, and `authed` (re-login
  on 401, retry once; `SignInNeeded` when there is no kept sign-in). Setup page “Sign in with your user account”;
  Lines card shows “Sign in” or “Messages sign in as …” with Sign out.
- `SecretBox` seals SIP secret, bearer and User Manager password with DPAPI (jna-platform `Crypt32Util`).
  Verified on this PC: both lines' secrets in `lines.properties` and `line.properties` are `dpapi:` after start.
- Fixed: link setup always took the user's first DID, so a second link re-added line 1. `pickLineToAdd` now takes
  the first number not on this PC.
- Tests: 35 pass (new `LineSignInTest`, 5). Not yet tested live: the sign-in page, attach on a link line, and
  re-login after a real 401.
- Found: the MSI packaging script is not in the repo (only described in CODE-SPEC). Rebuild it under M7.

### 2026-10-08 — first run with the locally linked DLL (Claude)
- `gradlew run` against the live PBX. This PC already had two lines from an earlier MSI (903 default, 9333), so
  the app reopened them; both registered over **TLS** with the DLL linked on 2026-10-08. Setup-by-email still
  needs testing on a clean profile (rename `%LOCALAPPDATA%\IHF Phone` or use a second Windows user).

### 2026-10-07 — review and setup (Claude)
- Reviewed README, CODE-SPEC, round-3 DESKTOP-SPEC and the zip handed over. Build compiles; 30/30 unit tests pass;
  the shipped `ihf_sip.dll` exports match `ihf_sip.h`.
- Findings to fix along the way: `PhoneFrame.kt` is 3,343 lines; `reg_code`/`reg_reason` are shared by both lines
  in `ihf_sip.c`; second incoming call is always 486 even on the other line; incoming SMS found by polling; no
  voicemail route in the BFF (beta 1 dials the mailbox instead).
- Installed VS 2022 Build Tools (VC tools + Windows 11 SDK) and **OpenSSL 3.5.9 LTS** Win64 SDK on this PC.
  Do not use OpenSSL 4.x: the bundle ships `libssl-3-x64.dll` and PJSIP 2.17 targets 3.x. winget only offers
  versions slproweb still hosts; 3.5.9 was installed from slproweb's MSI after checking its published SHA-256.
- Read the private `ithandsfree/pbx` repo for the connection model (section above).
- `native\build-pjsip.ps1` ran clean on this PC (PJSIP 2.17 + OpenSSL 3.5.9, 0 errors) and relinked
  `native/out/ihf_sip.dll`. The 0.1.32 prebuilt DLL is kept at `.tmp/ihf_sip.prebuilt-0.1.32.dll` for comparison.
  The "vswhere.exe is not recognized" line in the output comes from `vcvars64.bat` and is harmless.
- Read-only look at the live PBX (FreePBX 17.0.33, Asterisk 22.11, Debian 12) over SSH:
  - Test extension's AOR allows 7 contacts, so the desktop can register beside the phone. PBX also listens on UDP
    5060; the app still refuses UDP. Its voicemail box is in context `default`.
  - **The live BFF has hand edits not in this repo** (`AdminService.php`, `SmsGateway.php`, `WelcomeMailer.php`,
    `public/index.php`): a hard-coded DID↔extension map used when the SMS Connector returns no extension, the IHF
    navy/gold welcome email ("On this PC it opens IHF Phone"), a `dl/latest` install link, and live mail defaults.
    **Deploying `api/` from this repo as-is would drop all of that.** Fix first: move the map and branding into
    `config.php` keys, then deploy. Copies of the live files: ask the owner, they are not committed.
  - **To verify: BFF bearer lifetime.** Intended design (owner): the enrol token only provisions the line; after
    that the extension's own credentials carry ongoing use. That holds for SIP (secret fetched once, stored). In the
    code, though, the BFF routes for texts and DND still need `X-IHF-Token`, the token store expires rows after
    `token_ttl_seconds` (72 h), and `/v1/session` echoes `token: ''` so clients keep the enrol token as the bearer.
    On 2026-10-08 00:22 no token on the live PBX had reached 72 h and the 8443 log had zero 401s. First expiries:
    2026-10-08 01:35 EDT onward. Check the 8443 log for 401 on `/v1/lines/*/threads` after that. If 401s appear,
    fix (BFF, backward compatible): on first `/v1/session` exchange the enrol token for a long-lived device token,
    revoke the enrol token, renew on use. If no 401s, find what keeps it alive and record it here.
