package net.ithandsfree.softphone.sip

/** How this build sends SIP. TLS is preferred; TCP and UDP are fallbacks. */
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
 * TLS uses port 5061. TCP and UDP keep the PBX default port on 5060.
 */
fun sipRegistrarUri(domain: String, signalling: SipSignalling): String {
    val host = domain.trim()
    if (host.isEmpty()) return "sip:"
    return when (signalling) {
        SipSignalling.TLS -> "sip:$host:$SIP_TLS_PORT;transport=tls"
        SipSignalling.TCP, SipSignalling.UDP -> "sip:$host"
    }
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
