package net.ithandsfree.softphone.win

import java.io.File
import java.util.Properties

/** Remembers the enrolled line on this PC so a restart opens the phone, not setup. */
internal object LineStore {
    fun load(): EnrolledLine? = read(file())

    fun save(line: EnrolledLine) {
        write(file(), line)
    }

    internal fun read(file: File): EnrolledLine? {
        if (!file.isFile) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val token = props.getProperty("token").orEmpty()
        val did = props.getProperty("did").orEmpty()
        val extension = props.getProperty("extension").orEmpty()
        val secret = props.getProperty("secret").orEmpty()
        val domain = props.getProperty("domain").orEmpty()
        if (token.isBlank() || did.isBlank() || extension.isBlank() || secret.isBlank() || domain.isBlank()) return null
        return EnrolledLine(
            token = token,
            did = did,
            extension = extension,
            sipPassword = secret,
            sipDomain = domain,
            displayName = props.getProperty("name").orEmpty().ifBlank { extension },
        )
    }

    internal fun write(file: File, line: EnrolledLine) {
        val props = Properties()
        props.setProperty("token", line.token)
        props.setProperty("did", line.did)
        props.setProperty("extension", line.extension)
        props.setProperty("secret", line.sipPassword)
        props.setProperty("domain", line.sipDomain)
        props.setProperty("name", line.displayName)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "IHF Phone line") }
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "line.properties")
    }
}
