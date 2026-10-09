package net.ithandsfree.softphone.win

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent

/**
 * Plays one call recording or voicemail (PCM WAV from the PBX) on the speaker chosen for calls, or the Windows
 * default. Starting another one stops the first. Pause, resume and seek drive the voicemail scrubber. GPL-2.0.
 */
internal class RecordingPlayer {
    @Volatile var playing: String? = null
        private set
    @Volatile var paused = false
        private set
    private var clip: Clip? = null

    fun play(key: String, wav: ByteArray, speaker: String, onEnd: () -> Unit) {
        stop()
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))
        val info = DataLine.Info(Clip::class.java, stream.format)
        val line = speakerClip(info, speaker) ?: AudioSystem.getLine(info) as Clip
        line.open(stream)
        line.addLineListener { event ->
            // A pause also sends STOP; only the end of the audio (or stop()) finishes the playback.
            if (event.type == LineEvent.Type.STOP && playing == key && !paused) {
                playing = null
                line.close()
                onEnd()
            }
        }
        clip = line
        playing = key
        paused = false
        line.start()
    }

    fun pause() {
        val line = clip ?: return
        if (playing == null || paused) return
        paused = true
        line.stop()
    }

    fun resume() {
        val line = clip ?: return
        if (playing == null || !paused) return
        paused = false
        if (line.framePosition >= line.frameLength) line.framePosition = 0
        line.start()
    }

    /** Position and length in milliseconds of what is loaded, or null when nothing is. */
    fun progress(): Pair<Long, Long>? {
        val line = clip ?: return null
        if (playing == null) return null
        return line.microsecondPosition / 1000 to line.microsecondLength / 1000
    }

    /** Jumps to [fraction] (0–1) of the loaded audio. */
    fun seek(fraction: Double) {
        val line = clip ?: return
        if (playing == null) return
        line.framePosition = (line.frameLength * fraction.coerceIn(0.0, 0.999)).toInt()
    }

    fun stop() {
        playing = null
        paused = false
        clip?.let {
            it.stop()
            it.close()
        }
        clip = null
    }

    private fun speakerClip(info: DataLine.Info, speaker: String): Clip? {
        if (speaker.isBlank()) return null
        val mixer = pairCaptureName(speaker, AudioSystem.getMixerInfo().map { it.name }) ?: return null
        return runCatching {
            val found = AudioSystem.getMixerInfo().first { it.name == mixer }
            AudioSystem.getMixer(found).getLine(info) as Clip
        }.getOrNull()
    }
}
