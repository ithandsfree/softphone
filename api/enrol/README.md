# Enrol landing pages

Per-token HTML pages live on the PBX at:

`https://pbx.example.com/ihf-softphone/enrol/<token>/`

`_template.html` is the page skeleton. The BFF fills the token, label, and links when it issues a welcome message. The page does not include a SIP secret.

The Android app also accepts the custom scheme `ihfphone://enroll/<token>` and a pasted HTTPS URL from whatever host you configured in `public_base`.
