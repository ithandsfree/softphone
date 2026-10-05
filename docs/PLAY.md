# Play Store checklist

Not a store submission. This is the order of operations once a flavor is ready to leave sideload.

Play receives two softphone apps. This repository also contains the BFF (`api/`). The BFF is installed on the FreePBX host. It is not uploaded to Play.

Already in this repo: GPL-2.0 source, the FreePBX 17+ install guide, and the two application ids below.

## Two listings

| Flavor | Application id | Listing |
| --- | --- | --- |
| `ihf` | `net.ithandsfree.softphone` | IHF-branded |
| `community` | `net.ithandsfree.softphone.community` | FreePBX community |

Use a separate upload key for each application id. Keystores stay out of git. See [APK_SIGNING.md](APK_SIGNING.md).

## Before closed testing

Privacy policy and GPL are separate. Google Play requires the privacy URL. GPL-2.0 requires the licence text in the app and a way to get the source. One link does not cover the other.

- [x] GPL-2.0 licence text ships with the app (Settings → Licences) and in this repo (`LICENSE`)
- [x] Source is available, separate from the privacy policy: https://github.com/ithandsfree/softphone
      That URL goes on the store listing and is the in-app Licences → Source code row.
- [x] PJSIP used under GPL-2.0. The Play app is this client. Corresponding source is this repository.
- [ ] No live hostnames, mailboxes, or extensions in the source that is tagged for the build
- [ ] `community` build leaves the PBX URL and SIP domain empty
- [x] IHF listing privacy policy URL, for the Play Console: https://ithandsfree.com/privacy
      Live as of 2026-10-05, including the `ihf-phone` section. This is not a GPL requirement.
- [x] IHF in-app Privacy row and Data safety deletion link: https://ithandsfree.com/privacy#ihf-phone
      The `ihf` flavor opens this from Settings. The `community` flavor hides that row.
- [ ] Community listing privacy policy URL. Do not reuse the IHF policy.
- [ ] Data safety form: encrypted on-device credentials, BFF token, optional contacts
- [x] Target API level matches current Play requirements.
      A new app submitted after 31 August 2026 must target Android 16 (API 36). `compileSdk` and `targetSdk` are 36.
- [ ] Closed testing for both apps; production and any charge come later.
      `:app:bundleIhfRelease` for `net.ithandsfree.softphone`. The `ihf` default server in this tree is `pbx.example.com`.
      `:app:bundleCommunityRelease` for `net.ithandsfree.softphone.community`. That flavor leaves the server blank. It needs its own privacy policy and its own upload key.

## Not in this release

- iOS / App Store
- Selling the community app
