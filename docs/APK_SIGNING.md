# APK signing

Release signing is optional for a debug sideload and required before Play.

Create `android/keystore.properties` (gitignored):

```properties
storeFile=/absolute/path/softphone-release.jks
storePassword=…
keyAlias=softphone
keyPassword=…
```

Or export `IHF_KEYSTORE_FILE`, `IHF_KEYSTORE_PASSWORD`, `IHF_KEY_ALIAS`, `IHF_KEY_PASSWORD`.

`assembleRelease` falls back to the debug key when those values are missing. A debug-signed APK cannot update a release-signed install of the same application id.

Keep a copy of each flavor’s keystore outside this repo. Losing the key means that application id cannot be updated.

```bash
cd android
./gradlew :app:assembleCommunityRelease
./gradlew :app:assembleIhfRelease
```
