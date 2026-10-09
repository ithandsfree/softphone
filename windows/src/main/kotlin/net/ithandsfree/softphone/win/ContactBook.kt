package net.ithandsfree.softphone.win

import java.io.File

/** Names and numbers saved on this PC. No sample people are shipped. */
internal object ContactBook {
    data class Person(val name: String, val number: String)

    fun load(): List<Person> {
        val file = file()
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0].trim()
            val number = parts[1].trim()
            if (name.isBlank() || number.isBlank()) null else Person(name, number)
        }
    }

    fun add(name: String, number: String) {
        val cleanName = name.trim().replace('\t', ' ')
        val cleanNumber = number.trim()
        if (cleanName.isBlank() || cleanNumber.isBlank()) return
        val next = load().filterNot { it.number == cleanNumber } + Person(cleanName, cleanNumber)
        write(next)
    }

    /** The saved person for a number, ignoring formatting and the NANP leading 1. */
    fun nameFor(number: String): Person? = load().firstOrNull { sameNumber(it.number, number) }

    fun match(query: String): List<Person> {
        val needle = query.trim()
        if (needle.isBlank()) return emptyList()
        return load().filter {
            it.name.contains(needle, ignoreCase = true) || it.number.contains(needle.filter { ch -> ch.isDigit() }.ifBlank { needle })
        }
    }

    private fun write(people: List<Person>) {
        val file = file()
        file.parentFile?.mkdirs()
        file.writeText(people.joinToString("\n") { "${it.name}\t${it.number}" })
    }

    fun importFile(file: File): Int {
        val text = file.readText()
        val found = if (text.contains("BEGIN:VCARD", ignoreCase = true)) parseVcard(text) else parseCsv(text)
        found.forEach { add(it.name, it.number) }
        return found.size
    }

    private fun parseVcard(text: String): List<Person> {
        val people = mutableListOf<Person>()
        var name = ""
        var number = ""
        fun keep() {
            if (name.isNotBlank() && number.isNotBlank()) people.add(Person(name.trim(), number.trim()))
            name = ""
            number = ""
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.equals("BEGIN:VCARD", ignoreCase = true)) {
                name = ""
                number = ""
            } else if (line.equals("END:VCARD", ignoreCase = true)) {
                keep()
            } else if (line.startsWith("FN", ignoreCase = true)) {
                name = line.substringAfter(':')
            } else if (line.startsWith("TEL", ignoreCase = true) && number.isBlank()) {
                number = line.substringAfter(':')
            }
        }
        return people
    }

    private fun parseCsv(text: String): List<Person> {
        val rows = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (rows.isEmpty()) return emptyList()
        val header = splitCsv(rows.first()).map { it.trim().lowercase() }
        val nameAt = header.indexOfFirst { it == "name" || it == "full name" || it == "fn" }
        val firstAt = header.indexOfFirst { it == "first name" || it == "given name" }
        val lastAt = header.indexOfFirst { it == "last name" || it == "family name" }
        val phoneAt = header.indexOfFirst {
            it.contains("phone") || it.contains("mobile") || it == "tel" || it.contains("value")
        }
        if (phoneAt < 0) return emptyList()
        return rows.drop(1).mapNotNull { row ->
            val cells = splitCsv(row)
            val number = cells.getOrNull(phoneAt).orEmpty()
            val name = when {
                nameAt >= 0 -> cells.getOrNull(nameAt).orEmpty()
                else -> listOfNotNull(cells.getOrNull(firstAt), cells.getOrNull(lastAt)).joinToString(" ")
            }
            if (name.isBlank() || number.isBlank()) null else Person(name.trim(), number.trim())
        }
    }

    private fun splitCsv(row: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        row.forEach { ch ->
            when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        cells.add(current.toString())
        return cells
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "contacts.txt")
    }
}
