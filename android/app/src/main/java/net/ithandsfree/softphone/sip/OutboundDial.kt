package net.ithandsfree.softphone.sip

import net.ithandsfree.softphone.data.normalizeDidDigits

/**
 * Digits / URI user-part to place on the SIP INVITE.
 *
 * - Keeps `*`, `#`, `+`, and short extensions literal.
 * - NANP 10-digit → `1` + NPA-NXX-XXXX (FreePBX outbound often expects 11).
 */
fun outboundDialDigits(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    if (filtered.isBlank()) return filtered
    if (filtered.any { it == '*' || it == '#' || it == '+' }) return filtered
    if (filtered.length < 7) return filtered
    return normalizeDidDigits(filtered)
}
