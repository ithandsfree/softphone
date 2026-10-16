package net.ithandsfree.softphone.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rules shared with the desktop app (PhoneRules.kt there), so both clients read the same. */

/**
 * A caller name worth showing, or null. FreePBX trunks often store "CID:<number>" or the number itself as the
 * caller name (voicemail .txt, CDR); those read as noise, so the number is shown instead.
 */
fun callerName(raw: String?): String? {
    val name = raw?.trim()?.trim('"').orEmpty()
    if (name.isBlank()) return null
    if (name.startsWith("CID:", ignoreCase = true)) return null
    if (name.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) return null
    if (name.equals("unknown", true) || name.equals("unavailable", true) || name.equals("anonymous", true)) return null
    return name
}

/** False for a withheld or blocked caller ID, which cannot be called back. */
fun canCallBack(number: String?): Boolean {
    val value = number?.trim().orEmpty()
    if (value.isBlank()) return false
    val lower = value.lowercase(Locale.US)
    if (lower in setOf("anonymous", "unknown", "unavailable", "restricted", "private", "anonymous caller")) return false
    if ("withheld" in lower) return false
    return value.any { it.isDigit() }
}

/** Why a number cannot be texted, or null when it can. Short extensions and feature codes are not texted. */
fun textBlock(number: String?): String? {
    if (!canCallBack(number)) return "Caller ID withheld"
    val value = number.orEmpty()
    if (value.any { it == '*' || it == '#' }) return "Extensions can't receive texts"
    val digits = value.filter { it.isDigit() }
    if (digits.length in 2..6) return "Extensions can't receive texts"
    if (digits.length < 7) return "No number to text"
    return null
}

/** Voicemail / recording time: "4:52 PM" today, "Yesterday 4:52 PM", "Mon 9:12 AM" this week, then "Sep 26". */
fun formatVoicemailWhen(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    if (epochMs <= 0) return ""
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val clock = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    val day = at.toLocalDate()
    return when {
        day == today -> clock
        day == today.minusDays(1) -> "Yesterday $clock"
        day.isAfter(today.minusDays(7)) -> at.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) + " $clock"
        day.year == today.year -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        else -> at.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    }
}

/** Seconds as "0:41" / "12:05". */
fun formatSeconds(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * The PBX's CDR row for a call in Recents: same number, and the PBX call ended within two minutes of when this
 * phone logged it. Used to find that call's recording.
 */
fun matchPbxCall(number: String, startedAtMs: Long, durationSec: Int, calls: List<PbxCall>): PbxCall? {
    val want = normalizeDidDigits(number).takeLast(10)
    if (want.isBlank()) return null
    val endMs = startedAtMs + durationSec * 1000L
    return calls
        .filter { normalizeDidDigits(it.peer).takeLast(10) == want }
        .map { it to kotlin.math.abs((it.at + it.duration) * 1000L - endMs) }
        .filter { it.second <= 120_000L }
        .minByOrNull { it.second }
        ?.first
}
