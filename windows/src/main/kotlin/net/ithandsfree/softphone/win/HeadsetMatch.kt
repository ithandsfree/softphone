package net.ithandsfree.softphone.win

private val genericDeviceWords = setOf(
    "primary", "sound", "driver", "mapper", "speakers", "speaker",
    "microphone", "audio", "device", "earphone", "earphones",
    "headphone", "headphones", "headset", "output", "input",
)

/** Pick the playback device that corresponds to a newly attached mixer. */
internal fun matchOutputName(wanted: String, names: List<String>, previous: Set<String> = emptySet()): String? {
    val exact = names.firstOrNull { it.equals(wanted, ignoreCase = true) }
    if (exact != null) return exact
    val fresh = names.filter { it !in previous }.ifEmpty { names }
    val tokens = wanted.split(Regex("[^A-Za-z0-9]+"))
        .filter { it.length >= 4 && it.lowercase() !in genericDeviceWords }
    val byToken = fresh.firstOrNull { name -> tokens.any { name.contains(it, ignoreCase = true) } }
    if (byToken != null) return byToken
    return fresh.firstOrNull()
}

/** Match a headset speaker to its microphone. No match returns null, never a random device. */
internal fun pairCaptureName(playbackName: String, inputNames: List<String>): String? {
    if (playbackName.isBlank()) return null
    inputNames.firstOrNull { it.equals(playbackName, ignoreCase = true) }?.let { return it }
    val tokens = playbackName.split(Regex("[^A-Za-z0-9]+"))
        .filter { it.length >= 4 && it.lowercase() !in genericDeviceWords }
    if (tokens.isEmpty()) return null
    return inputNames.firstOrNull { name -> tokens.all { name.contains(it, ignoreCase = true) } }
        ?: inputNames.firstOrNull { name -> tokens.any { name.contains(it, ignoreCase = true) } }
}
