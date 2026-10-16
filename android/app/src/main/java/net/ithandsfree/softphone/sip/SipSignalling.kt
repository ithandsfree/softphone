package net.ithandsfree.softphone.sip

/** How this build sends SIP. TLS is preferred. TCP is the only fallback. UDP is not used. */
enum class SipSignalling {
    TLS,
    TCP,
    UDP,
}

const val SIP_TLS_PORT = 5061

fun sipAccountIdUri(extension: String, domain: String): String =
    "sip:${extension.trim()}@${domain.trim()}"

/**
 * Registrar URI for a FreePBX PJSIP extension.
 * TLS uses port 5061. TCP keeps the PBX default port on 5060. UDP is not a choice.
 */
fun sipRegistrarUri(domain: String, signalling: SipSignalling): String {
    val host = domain.trim()
    if (host.isEmpty()) return "sip:"
    return when (signalling) {
        SipSignalling.TLS -> "sip:$host:$SIP_TLS_PORT;transport=tls"
        SipSignalling.TCP, SipSignalling.UDP -> "sip:$host"
    }
}

/**
 * INVITE URI. PJSIP decides the call is secure only when this URI contains
 * `;transport=tls` (or the account route does). The account transport is not
 * enough: without the parameter, SRTP's secure-signalling check rejects the
 * call with PJSIP_ESESSIONINSECURE. Incoming calls skip this because they
 * arrived on the TLS socket. TCP and UDP URIs stay unmarked.
 */
fun sipCallUri(digits: String, domain: String, signalling: SipSignalling): String {
    val base = if (digits.contains("@")) {
        "sip:$digits"
    } else {
        "sip:${digits}@${domain.trim()}"
    }
    if (signalling != SipSignalling.TLS) return base
    if (base.contains(";transport=", ignoreCase = true)) return base
    return "$base;transport=tls"
}

/** PEM bundle PJSIP can pass to OpenSSL as a CA file. */
fun pemEncodeCertificates(encodedCerts: List<ByteArray>): String {
    if (encodedCerts.isEmpty()) return ""
    val encoder = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
    return buildString {
        for (der in encodedCerts) {
            if (der.isEmpty()) continue
            append("-----BEGIN CERTIFICATE-----\n")
            append(encoder.encodeToString(der))
            if (!endsWith("\n")) append('\n')
            append("-----END CERTIFICATE-----\n")
        }
    }
}
