package net.ithandsfree.softphone.legal

/**
 * Links the store listing and the app must keep separate.
 *
 * Google Play requires [PLAY_PRIVACY_POLICY]. GPL-2.0 does not.
 * GPL-2.0 requires the licence text in the app and a way to get the source,
 * which is [SOURCE_REPOSITORY].
 */
object LegalLinks {
    /** Play Console privacy-policy field. No fragment. */
    const val PLAY_PRIVACY_POLICY = "https://ithandsfree.com/privacy"

    /**
     * In-app Privacy row, and the Play Data safety deletion link.
     * The page is live and contains id="ihf-phone".
     */
    const val PRIVACY_SECTION = "https://ithandsfree.com/privacy#ihf-phone"

    /** Store listing and Settings → Licences. Not a privacy URL. */
    const val SOURCE_REPOSITORY = "https://github.com/ithandsfree/softphone"

    /** Asset packaged into the APK. Verbatim GPL-2.0 text. */
    const val LICENSE_ASSET = "GPL-2.0"

    fun httpsUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        if (!trimmed.startsWith("https://")) return null
        return trimmed
    }

    fun looksLikeGpl2(text: String): Boolean =
        text.contains("GNU GENERAL PUBLIC LICENSE") &&
            text.contains("Version 2, June 1991") &&
            text.contains("END OF TERMS AND CONDITIONS")
}
