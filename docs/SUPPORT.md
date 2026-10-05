# Platform support

## Supported

**FreePBX 17 and later**, with:

- PJSIP extensions
- User Manager
- FreePBX SMS Connector (same `sms_messages` store UCP uses), when you want SMS/MMS

That is the platform this softphone and BFF are developed against.

## Earlier FreePBX (16 and below)

Not part of this community release. Installs on FreePBX 16 or older are outside the published install path.

## Out of scope

- iOS (shelved)
- Non-FreePBX Asterisk-only stacks as a messaging and enrol product
- Other softphone platforms

Voice REGISTER may succeed on other `chan_pjsip` systems. **Product support** means FreePBX 17+ with this BFF for SMS/MMS and enrol.
