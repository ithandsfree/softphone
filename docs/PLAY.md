# Play Store checklist

Not a store submission. This is the order of operations once a flavor is ready to leave sideload.

## Two listings

| Flavor | Application id | Listing |
| --- | --- | --- |
| `ihf` | `net.ithandsfree.softphone` | IHF-branded |
| `community` | `net.ithandsfree.softphone.community` | FreePBX community |

Use a separate upload key for each application id. Keystores stay out of git. See [APK_SIGNING.md](APK_SIGNING.md).

## Before closed testing

- [ ] GPL-2.0 license text ships with the app and the source repo
- [ ] PJSIP used under GPL (this repo), not a closed Teluu build
- [ ] No live hostnames, mailboxes, or extensions in the source that is tagged for the build
- [ ] `community` build leaves the PBX URL and SIP domain empty
- [ ] Privacy policy URL (contacts, microphone, notifications)
- [ ] Data safety form: encrypted on-device credentials, BFF token, optional contacts
- [ ] Target API level matches current Play requirements
- [ ] Closed testing track first; production and any charge come later

## Not in this release

- iOS / App Store
- Selling the community app
