package net.ithandsfree.softphone.win

/**
 * Enrol paste rules and outbound dial digits, matching the Android client.
 * GPL-2.0. See the LICENSE file at the repository root.
 */

enum class SipSignalling {
    TLS,
    TCP,
    UDP,
}

const val SIP_TLS_PORT = 5061

/** TLS when that transport exists, otherwise TCP. UDP signalling is not a choice. */
fun preferredSignalling(tlsAvailable: Boolean, tcpAvailable: Boolean): SipSignalling? = when {
    tlsAvailable -> SipSignalling.TLS
    tcpAvailable -> SipSignalling.TCP
    else -> null
}

fun sipAccountIdUri(extension: String, domain: String): String =
    "sip:${extension.trim()}@${domain.trim()}"

fun sipRegistrarUri(domain: String, signalling: SipSignalling): String {
    val host = domain.trim()
    if (host.isEmpty()) return "sip:"
    return when (signalling) {
        SipSignalling.TLS -> "sip:$host:$SIP_TLS_PORT;transport=tls"
        SipSignalling.TCP, SipSignalling.UDP -> "sip:$host"
    }
}

/**
 * Group digits the way the Android dialler does. Leaves short codes and
 * anything containing `*`, `#` or `+` literal.
 */
fun formatDialDigits(raw: String): String {
    if (raw.isEmpty() || raw.any { !it.isDigit() }) return raw
    return when {
        raw.length == 10 -> "(${raw.take(3)}) ${raw.substring(3, 6)}-${raw.substring(6)}"
        raw.length == 11 && raw.startsWith("1") ->
            "1 (${raw.substring(1, 4)}) ${raw.substring(4, 7)}-${raw.substring(7)}"
        raw.length == 7 -> "${raw.take(3)}-${raw.substring(3)}"
        else -> raw
    }
}

/**
 * A finished number as people read it (`desktop-*.png`): a NANP number with or without the leading 1 shows as
 * `(416) 555-0177`; extensions, feature codes and other numbers stay as they are.
 */
fun displayNumber(raw: String): String {
    val value = raw.trim()
    val digits = value.removePrefix("+")
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return value
    val local = if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    return if (local.length == 10) "(${local.take(3)}) ${local.substring(3, 6)}-${local.substring(6)}" else value
}

/** Epoch milliseconds from a BFF time (Unix seconds, or already milliseconds). Null when it is not a number. */
fun epochMsOf(raw: String?): Long? {
    val value = raw?.trim()?.toLongOrNull() ?: return null
    if (value <= 0) return null
    return if (value < 100_000_000_000L) value * 1000 else value
}

/** Same number regardless of formatting or the NANP leading 1, for matching a contact or a thread. */
fun sameNumber(a: String, b: String): Boolean {
    fun key(v: String) = v.filter { it.isDigit() }.let { if (it.length == 11 && it.startsWith("1")) it.drop(1) else it }
    val ka = key(a)
    return ka.isNotEmpty() && ka == key(b)
}

/**
 * The PBX call that matches a call this PC logged: same number, and the PBX start + duration within
 * [slackMs] of the local end time (the PC stores when the call ended). Null when none is close enough.
 */
fun matchPbxCall(party: String, endedAtMs: Long, seconds: Int, calls: List<PbxCall>, slackMs: Long = 120_000): PbxCall? =
    calls.filter { sameNumber(it.peer, party) || (it.peer == party && party.isNotBlank()) }
        .map { it to kotlin.math.abs((it.at * 1000 + it.duration * 1000L) - endedAtMs) }
        .filter { it.second <= slackMs + seconds * 1000L }
        .minByOrNull { it.second }?.first

/** Largest type while the entry is a normal number, then step down so a long paste still fits. */
fun dialTypeSize(raw: String): Int {
    val digits = raw.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
    return when {
        digits.length <= 11 -> 52
        digits.length <= 15 -> 40
        else -> 28
    }
}

/** The person or code on a call, without the SIP host. */
fun displayParty(remote: String, fallback: String = ""): String {
    val raw = remote.trim().ifBlank { return fallback }
    val body = raw.substringAfter("sip:", raw).substringBefore("@").substringBefore(";")
    return body.trim().ifBlank { fallback }
}

fun normalizeDidDigits(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return if (digits.length == 10) "1$digits" else digits
}

/**
 * Digits / URI user-part to place on the SIP INVITE.
 * Keeps `*`, `#`, `+`, and short extensions literal.
 * NANP 10-digit becomes `1` + NPA-NXX-XXXX.
 */
fun outboundDialDigits(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    if (filtered.isBlank()) return filtered
    if (filtered.any { it == '*' || it == '#' || it == '+' }) return filtered
    if (filtered.length < 7) return filtered
    return normalizeDidDigits(filtered)
}

/** Live in-call clock. Shown in gold mono once the call is connected. */
fun formatLiveCallTimer(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
}

fun sipCallUri(raw: String, domain: String, signalling: SipSignalling = SipSignalling.TCP): String {
    val digits = outboundDialDigits(raw)
    val base = if (digits.contains("@")) "sip:$digits" else "sip:$digits@${domain.trim()}"
    if (signalling != SipSignalling.TLS) return base
    if (base.contains(";transport=", ignoreCase = true)) return base
    return "$base;transport=tls"
}

/** A withheld caller has no number to dial or text. */
internal fun recentCanCall(party: String): Boolean {
    val value = party.trim()
    if (value.isBlank()) return false
    if (value.equals("anonymous", ignoreCase = true)) return false
    if (value.equals("unknown", ignoreCase = true)) return false
    if (value.contains("withheld", ignoreCase = true)) return false
    // Voicemail and CDR caller IDs for a blocked number.
    if (value.lowercase() in setOf("unavailable", "restricted", "private", "anonymous caller")) return false
    return true
}

/** Short extensions and feature codes are not text destinations. Null means a text is allowed. */
internal fun recentTextBlock(party: String): String? {
    if (!recentCanCall(party)) return "Caller ID withheld"
    if (party.any { it == '*' || it == '#' }) return "Extensions can't receive texts"
    val digits = party.filter { it.isDigit() }
    if (digits.length in 2..6) return "Extensions can't receive texts"
    if (digits.length < 7) return "No number to text"
    return null
}

/** tel: and sip: links become a number. Anything else is not a dial link. */
internal fun dialTarget(raw: String): String? {
    val value = raw.trim().trim('"')
    val scheme = value.substringBefore(":", "").lowercase()
    if (scheme != "tel" && scheme != "sip") return null
    val body = value.substringAfter(":").substringBefore("?").substringBefore(";").substringBefore("@")
    val number = body.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    return number.ifBlank { null }
}

/** Welcome email field. A real address has a name, an @, and a dot in the domain. */
fun looksLikeEmail(raw: String): Boolean {
    val value = raw.trim()
    val at = value.indexOf('@')
    if (at <= 0 || value.contains(' ')) return false
    val dot = value.indexOf('.', startIndex = at + 1)
    return dot > at + 1 && dot < value.lastIndex
}

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

    val compact = trimmed.filterNot { it.isWhitespace() }
    return compact.trimEnd('/', ')', ']')
}

fun looksLikeEnrolToken(token: String): Boolean =
    token.length >= 40 && token.all { it.isLetterOrDigit() }

/** Voicemail row time: "4:52 PM" today, "Yesterday 4:52 PM", "Mon 9:12 AM" this week, then "Sep 26". */
internal fun formatVoicemailWhen(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), today: java.time.LocalDate = java.time.LocalDate.now(zone)): String {
    if (epochMs <= 0) return ""
    val at = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    val clock = at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
    val day = at.toLocalDate()
    return when {
        day == today -> clock
        day == today.minusDays(1) -> "Yesterday $clock"
        day.isAfter(today.minusDays(7)) -> at.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.US)) + " $clock"
        day.year == today.year -> at.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
        else -> at.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))
    }
}

/** Seconds as "0:41" / "12:05". */
internal fun formatSeconds(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * A caller name worth showing, or null. FreePBX trunks often store "CID:<number>" or the number itself as the caller
 * name (voicemail .txt, CDR); those read as noise, so the number is shown instead.
 */
internal fun callerName(raw: String): String? {
    val name = raw.trim().trim('"')
    if (name.isBlank()) return null
    if (name.startsWith("CID:", ignoreCase = true)) return null
    if (name.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) return null
    if (name.equals("unknown", true) || name.equals("unavailable", true) || name.equals("anonymous", true)) return null
    return name
}
