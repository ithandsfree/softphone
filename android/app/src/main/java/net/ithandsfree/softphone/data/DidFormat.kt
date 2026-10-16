package net.ithandsfree.softphone.data

/** Normalize to BFF/FreePBX digit form (NANP 10-digit → 1+NPA…). */
fun normalizeDidDigits(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return if (digits.length == 10) "1$digits" else digits
}

/** Display-friendly DID: 5555550100 instead of 15555550100 when NANP. */
fun formatDidForDisplay(raw: String): String {
    val digits = normalizeDidDigits(raw)
    return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
}

/**
 * Group digits the way a dialler does. Leaves short codes, extensions and
 * anything containing `*`, `#` or `+` untouched so keypad entry stays literal.
 */
fun formatDialDigits(raw: String): String {
    if (raw.isEmpty() || raw.any { !it.isDigit() }) return raw
    return when {
        raw.length == 10 ->
            "(${raw.take(3)}) ${raw.substring(3, 6)}-${raw.substring(6)}"
        raw.length == 11 && raw.startsWith("1") ->
            "1 (${raw.substring(1, 4)}) ${raw.substring(4, 7)}-${raw.substring(7)}"
        raw.length == 7 -> "${raw.take(3)}-${raw.substring(3)}"
        else -> raw
    }
}

/** Peer/DID shown in lists: strips the NANP country code, then groups. */
fun formatPeerForDisplay(raw: String): String = formatDialDigits(formatDidForDisplay(raw))

/**
 * A number from the clipboard or a `tel:` link (Google Maps "Call", a web page, an email): "+1 (416) 555-0177",
 * "416.555.0177", "tel:+14165550177;ext=12" → "14165550177" / "4165550177". Keeps * and # for feature codes.
 * Null when there is no number in it (a sentence, a URL).
 */
fun numberFromPaste(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var s = runCatching { java.net.URLDecoder.decode(raw.trim().replace("+", "%2B"), "UTF-8") }.getOrDefault(raw).trim()
    if (s.startsWith("tel:", ignoreCase = true)) s = s.substring(4)
    // Drop an extension part ("x12", "ext. 12", ";ext=12", ",12") and any URI parameters.
    s = s.split(Regex("""(?i)\s*(;|,|\bx\b|\bx(?=\d)|ext\.?|extension)""")).first()
    if (s.length > 40) return null
    if (s.any { it.isLetter() }) return null
    val kept = s.filter { it.isDigit() || it == '*' || it == '#' }
    if (kept.count { it.isDigit() } < 2) return null
    return kept
}
