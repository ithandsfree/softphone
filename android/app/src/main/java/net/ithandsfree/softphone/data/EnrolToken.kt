package net.ithandsfree.softphone.data

/**
 * Normalize pasted enrol material from email / SMS / HTTPS landing pages.
 *
 * Accepts bare BFF tokens, `ihfphone://enroll/{token}`, and
 * `https://…/ihf-softphone/enrol/{token}/` (also the older `/ihf/enroll/` spelling).
 * Strips whitespace and trailing punctuation email clients sometimes add.
 */
fun normalizeEnrolToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim().trim('"', '\'', '<', '>', '.', ',', ';')
    if (trimmed.isEmpty()) return ""

    val deepLink = Regex(
        """ihfphone://enroll/([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    ).find(trimmed)
    if (deepLink != null) return deepLink.groupValues[1]

    val httpsEnrol = Regex(
        """https?://[^\s]+/(?:ihf-softphone/enrol|ihf/enroll)/([A-Za-z0-9]+)/?""",
        RegexOption.IGNORE_CASE,
    ).find(trimmed)
    if (httpsEnrol != null) return httpsEnrol.groupValues[1]

    // Bare token (hex from TokenStore::issue) — drop whitespace/newlines from paste.
    val compact = trimmed.filterNot { it.isWhitespace() }
    return compact.trimEnd('/', ')', ']')
}

fun looksLikeEnrolToken(token: String): Boolean =
    // TokenStore issues bin2hex(random_bytes(24)) → 48 hex chars. Reject short pastes.
    token.length >= 40 && token.all { it.isLetterOrDigit() }
