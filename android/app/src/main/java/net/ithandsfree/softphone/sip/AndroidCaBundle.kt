package net.ithandsfree.softphone.sip

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyStore

/**
 * PJSIP OpenSSL does not read the Android trust store. Write the system CAs
 * to a PEM file so SIP TLS can verify the PBX certificate.
 */
internal fun writeAndroidCaBundle(context: Context): String? {
    val pem = runCatching { androidSystemCaPem() }.getOrElse {
        Log.w("SipCaBundle", "Could not read the Android CA store: ${it.message}")
        return null
    }
    if (pem.isBlank()) return null
    val file = File(context.filesDir, "sip-ca-bundle.pem")
    file.writeText(pem)
    return file.absolutePath
}

internal fun androidSystemCaPem(): String {
    val store = KeyStore.getInstance("AndroidCAStore")
    store.load(null)
    val ders = ArrayList<ByteArray>()
    val aliases = store.aliases()
    while (aliases.hasMoreElements()) {
        val cert = store.getCertificate(aliases.nextElement()) ?: continue
        ders.add(cert.encoded)
    }
    return pemEncodeCertificates(ders)
}
