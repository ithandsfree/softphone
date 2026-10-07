package net.ithandsfree.softphone.win

import java.io.File
import java.util.Properties

/** Up to two enrolled lines, plus which one new calls and messages use. */
internal object LineBook {
    private const val MAX = 2

    fun load(): List<EnrolledLine> {
        val saved = read(file())
        if (saved.isNotEmpty()) return saved
        val older = LineStore.load() ?: return emptyList()
        save(listOf(older), older.extension)
        return listOf(older)
    }

    fun defaultExtension(): String {
        val props = Properties()
        val stored = file()
        if (stored.isFile) stored.inputStream().use { props.load(it) }
        return props.getProperty("default").orEmpty()
    }

    fun save(lines: List<EnrolledLine>, defaultExtension: String) {
        val kept = lines.take(MAX)
        val props = Properties()
        props.setProperty("count", kept.size.toString())
        val fallback = kept.firstOrNull()?.extension.orEmpty()
        val chosen = defaultExtension.takeIf { ext -> kept.any { it.extension == ext } } ?: fallback
        props.setProperty("default", chosen)
        kept.forEachIndexed { index, line ->
            props.setProperty("$index.token", line.token)
            props.setProperty("$index.did", line.did)
            props.setProperty("$index.extension", line.extension)
            props.setProperty("$index.secret", line.sipPassword)
            props.setProperty("$index.domain", line.sipDomain)
            props.setProperty("$index.name", line.displayName)
        }
        val stored = file()
        stored.parentFile?.mkdirs()
        stored.outputStream().use { props.store(it, "IHF Phone lines") }
        kept.firstOrNull { it.extension == chosen }?.let { LineStore.save(it) }
    }

    internal fun read(stored: File): List<EnrolledLine> {
        if (!stored.isFile) return emptyList()
        val props = Properties()
        stored.inputStream().use { props.load(it) }
        val count = props.getProperty("count")?.toIntOrNull() ?: return emptyList()
        return (0 until count.coerceAtMost(MAX)).mapNotNull { index ->
            val token = props.getProperty("$index.token").orEmpty()
            val did = props.getProperty("$index.did").orEmpty()
            val extension = props.getProperty("$index.extension").orEmpty()
            val secret = props.getProperty("$index.secret").orEmpty()
            val domain = props.getProperty("$index.domain").orEmpty()
            if (token.isBlank() || did.isBlank() || extension.isBlank() || secret.isBlank() || domain.isBlank()) {
                null
            } else {
                EnrolledLine(
                    token = token,
                    did = did,
                    extension = extension,
                    sipPassword = secret,
                    sipDomain = domain,
                    displayName = props.getProperty("$index.name").orEmpty().ifBlank { extension },
                )
            }
        }
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "lines.properties")
    }
}
