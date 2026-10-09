package net.ithandsfree.softphone.win

import java.io.File
import java.util.Properties

/** Remembers the microphone and speaker chosen in Settings. */
internal object AudioPrefs {
    fun captureName(): String = load().getProperty("capture").orEmpty()

    fun playbackName(): String = load().getProperty("playback").orEmpty()

    fun ringerName(): String = load().getProperty("ringer").orEmpty()

    /** Ring on the call speaker as well as the ringer, so a headset still hears the ring. */
    fun alsoRing(): Boolean = load().getProperty("alsoRing") != "false"

    fun ringtoneStyle(): String {
        val saved = load().getProperty("ringtoneStyle").orEmpty()
        if (saved == RingtoneLibrary.CUSTOM && ringtoneFile() != null) return RingtoneLibrary.CUSTOM
        if (RingtoneLibrary.builtIn.any { it.id == saved }) return saved
        return if (ringtoneFile() != null && saved.isBlank()) RingtoneLibrary.CUSTOM else RingtoneLibrary.DEFAULT
    }

    /** Built-in tone chosen for one line, or null for "same as default". */
    fun lineRingtone(extension: String): String? =
        load().getProperty("ringtone.$extension")?.takeIf { id -> RingtoneLibrary.builtIn.any { it.id == id } }

    fun saveLineRingtone(extension: String, id: String?) {
        val props = load()
        if (id.isNullOrBlank()) props.remove("ringtone.$extension") else props.setProperty("ringtone.$extension", id)
        store(props)
    }

    /** Tone for a call on [extension]: that line's choice, else the default from Settings. */
    fun ringtoneStyleFor(extension: String?): String =
        extension?.let { lineRingtone(it) } ?: ringtoneStyle()

    fun ringtoneFile(): File? {
        val path = load().getProperty("ringtone").orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }

    fun ringtoneLabel(): String = ringtoneFile()?.name ?: "Built-in tone"

    fun saveCapture(name: String) {
        val props = load()
        props.setProperty("capture", name)
        store(props)
    }

    fun savePlayback(name: String) {
        val props = load()
        props.setProperty("playback", name)
        store(props)
    }

    fun saveAlsoRing(enabled: Boolean) {
        val props = load()
        props.setProperty("alsoRing", if (enabled) "true" else "false")
        store(props)
    }

    /** Echo cancellation with its noise suppression and gain control (Speex AEC). On unless turned off. */
    fun voiceProcessing(): Boolean = load().getProperty("voiceProcessing") != "false"

    /** Call volume in percent: 100 plays the other person as received; up to 300 boosts a quiet line. */
    fun callVolume(): Int = load().getProperty("callVolume")?.toIntOrNull()?.coerceIn(50, 300) ?: 100

    fun saveCallVolume(percent: Int) {
        val props = load()
        props.setProperty("callVolume", percent.coerceIn(50, 300).toString())
        store(props)
    }

    fun saveVoiceProcessing(enabled: Boolean) {
        val props = load()
        props.setProperty("voiceProcessing", if (enabled) "true" else "false")
        store(props)
    }

    fun keypadTone(): Boolean = load().getProperty("keypadTone") != "false"

    fun saveKeypadTone(enabled: Boolean) {
        val props = load()
        props.setProperty("keypadTone", if (enabled) "true" else "false")
        store(props)
    }

    fun saveRinger(name: String) {
        val props = load()
        props.setProperty("ringer", name)
        store(props)
    }

    /** Keep a copy of the chosen ringtone so it still plays if the original file moves. */
    fun installRingtone(source: File) {
        val ext = ringtoneExtension(source.name) ?: throw IllegalArgumentException("Use a WAV, AIFF, or AU file.")
        val folder = file().parentFile ?: throw IllegalStateException("No folder for the ringtone.")
        folder.mkdirs()
        folder.listFiles()?.filter { it.name.startsWith("ringtone.") }?.forEach { it.delete() }
        val dest = File(folder, "ringtone.$ext")
        source.copyTo(dest, overwrite = true)
        val props = load()
        props.setProperty("ringtone", dest.absolutePath)
        props.setProperty("ringtoneStyle", RingtoneLibrary.CUSTOM)
        store(props)
    }

    fun clearRingtone() {
        val props = load()
        val path = props.getProperty("ringtone").orEmpty()
        if (path.isNotBlank()) {
            val file = File(path)
            val folder = file().parentFile
            val inside = folder != null && runCatching {
                file.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath())
            }.getOrDefault(false)
            if (inside) file.delete()
        }
        props.remove("ringtone")
        props.setProperty("ringtoneStyle", RingtoneLibrary.DEFAULT)
        store(props)
    }

    fun saveRingtoneStyle(id: String) {
        val props = load()
        props.setProperty("ringtoneStyle", id)
        store(props)
    }

    /** Apply a saved choice once PJSIP has opened the sound devices. */
    fun applySaved(): Boolean {
        val devices = SipBridge.audioDevices()
        val playback = devices.firstOrNull { it.name == playbackName() && it.outputs > 0 } ?: return false
        val inputs = devices.filter { it.inputs > 0 }
        val captureName = pairCaptureName(playback.name, inputs.map { it.name })
            ?: captureName().takeIf { saved -> inputs.any { it.name == saved } }
            ?: inputs.firstOrNull()?.name
            ?: return false
        val capture = inputs.firstOrNull { it.name == captureName } ?: return false
        return SipBridge.openDevices(capture.index, playback.index) == 0
    }

    private fun load(): Properties {
        val props = Properties()
        val file = file()
        if (file.isFile) file.inputStream().use { props.load(it) }
        return props
    }

    private fun store(props: Properties) {
        val file = file()
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "IHF Phone audio devices") }
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "audio.properties")
    }
}
