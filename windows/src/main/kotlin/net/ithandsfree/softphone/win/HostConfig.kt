package net.ithandsfree.softphone.win

import java.io.File
import java.util.Base64
import java.util.Properties
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore

/**
 * Public builds keep the placeholder host. A live PBX belongs in the
 * environment or in host.local.properties, which is gitignored.
 */
data class HostConfig(
    val apiBase: String,
    val sipDomain: String,
) {
    val isPlaceholder: Boolean
        get() = apiBase.contains("pbx.example.com") || sipDomain == "pbx.example.com"

    companion object {
        const val PLACEHOLDER_API = "https://pbx.example.com/ihf-softphone/index.php"
        const val PLACEHOLDER_SIP = "pbx.example.com"

        fun load(profile: DistributionProfile, workdir: File = File(".")): HostConfig {
            val props = Properties()
            val installed = installDir()
            val file = sequenceOf(
                File(workdir, "host.local.properties"),
                installed?.let { File(it, "host.local.properties") },
                installed?.parentFile?.let { File(it, "host.local.properties") },
            ).firstOrNull { it != null && it.isFile }
            if (file != null) file.inputStream().use { props.load(it) }
            return resolveHost(
                profile,
                System.getenv("IHF_API_BASE") ?: System.getProperty("ihf.apiBase"),
                System.getenv("IHF_SIP_DOMAIN") ?: System.getProperty("ihf.sipDomain"),
                props.getProperty("apiBase"),
                props.getProperty("sipDomain"),
            )
        }

        private fun installDir(): File? {
            return try {
                val code = HostConfig::class.java.protectionDomain?.codeSource?.location ?: return null
                File(code.toURI()).parentFile
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Writable copy of the Windows trust store. The installed app cannot create native/out under Program Files. */
fun caBundleFile(): File {
    val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    return File(File(root, "IHF Phone"), "ca-bundle.pem")
}

/** PEM bundle of the Windows trust store, for PJSIP to verify the PBX certificate. */
fun writeCaBundle(file: File) {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    val certs = tmf.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
    val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
    val parent = file.parentFile
    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
        error("Could not create ${parent.path}")
    }
    file.writeText(buildString {
        for (cert in certs) {
            if (cert.encoded.isEmpty()) continue
            append("-----BEGIN CERTIFICATE-----\n")
            append(encoder.encodeToString(cert.encoded))
            if (!endsWith("\n")) append('\n')
            append("-----END CERTIFICATE-----\n")
        }
    })
}
