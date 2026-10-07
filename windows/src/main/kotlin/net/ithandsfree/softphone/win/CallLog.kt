package net.ithandsfree.softphone.win

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One finished call. Only calls this window actually placed or received are stored. */
internal data class CallEntry(
    val at: Long,
    val kind: String,
    val party: String,
    val seconds: Int,
    val extension: String = "",
)

internal object CallLog {
    private const val LIMIT = 40

    fun load(): List<CallEntry> = read(store())

    fun append(entry: CallEntry) {
        if (entry.party.isBlank()) return
        write(listOf(entry) + load())
    }

    fun remove(entry: CallEntry) {
        write(load().filterNot { it.at == entry.at && it.party == entry.party && it.kind == entry.kind })
    }

    private fun write(rows: List<CallEntry>) {
        val file = store()
        file.parentFile?.mkdirs()
        file.writeText(rows.take(LIMIT).joinToString("\n") { encode(it) })
    }

    internal fun read(file: File): List<CallEntry> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { decode(it) }
    }

    internal fun encode(entry: CallEntry): String {
        val party = entry.party.replace('\t', ' ').replace('\n', ' ').trim()
        val extension = entry.extension.replace('\t', ' ').replace('\n', ' ').trim()
        return "${entry.at}\t${entry.kind}\t$party\t${entry.seconds.coerceAtLeast(0)}\t$extension"
    }

    internal fun decode(line: String): CallEntry? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val at = parts[0].toLongOrNull() ?: return null
        val seconds = parts[3].toIntOrNull() ?: return null
        if (parts[2].isBlank()) return null
        val extension = parts.getOrNull(4).orEmpty().trim()
        return CallEntry(at, parts[1], parts[2], seconds, extension)
    }

    private fun store(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "recents.txt")
    }
}

internal fun formatRecentWhen(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val whenAt = Instant.ofEpochMilli(epochMs).atZone(zone)
    val clock = whenAt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return when (whenAt.toLocalDate()) {
        today -> clock
        today.minusDays(1) -> "Yesterday"
        else -> whenAt.format(DateTimeFormatter.ofPattern("EEE"))
    }
}
