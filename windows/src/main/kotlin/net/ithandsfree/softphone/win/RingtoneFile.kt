package net.ithandsfree.softphone.win

import java.io.File
import javax.sound.sampled.AudioSystem

/** Java can play these directly. MP3 needs a decoder this app does not ship. */
internal fun ringtoneExtension(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "wav", "wave" -> "wav"
        "aiff", "aif" -> "aiff"
        "au" -> "au"
        else -> null
    }
}

internal fun ringtoneReadable(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    return runCatching {
        AudioSystem.getAudioInputStream(file).use { it.format != null }
    }.getOrDefault(false)
}
