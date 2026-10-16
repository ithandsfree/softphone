package net.ithandsfree.softphone.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays one voicemail or call recording (WAV from the PBX) with pause, resume and seek. Starting another stops the
 * first. The audio is kept in a cache file only while it plays. Main thread.
 */
class ClipPlayer(private val context: Context) {
    data class Progress(val key: String? = null, val positionMs: Int = 0, val durationMs: Int = 0, val playing: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var file: File? = null
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            runCatching {
                _progress.value = _progress.value.copy(positionMs = p.currentPosition, playing = p.isPlaying)
            }
            if (_progress.value.playing) handler.postDelayed(this, 250)
        }
    }

    fun play(key: String, wav: ByteArray) {
        stop()
        val f = File(context.cacheDir, "clip-${key.hashCode()}.wav").apply { writeBytes(wav) }
        file = f
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setDataSource(f.absolutePath)
        p.setOnCompletionListener { _progress.value = _progress.value.copy(positionMs = 0, playing = false) }
        p.prepare()
        p.start()
        player = p
        _progress.value = Progress(key, 0, p.duration, true)
        handler.post(tick)
    }

    /** Play / pause the loaded clip. False when [key] is not the loaded one. */
    fun toggle(key: String): Boolean {
        val p = player ?: return false
        if (_progress.value.key != key) return false
        if (p.isPlaying) p.pause() else p.start()
        _progress.value = _progress.value.copy(playing = p.isPlaying)
        if (p.isPlaying) handler.post(tick)
        return true
    }

    fun seek(key: String, fraction: Float) {
        val p = player ?: return
        if (_progress.value.key != key) return
        val to = (p.duration * fraction.coerceIn(0f, 1f)).toInt()
        p.seekTo(to)
        _progress.value = _progress.value.copy(positionMs = to)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        file?.delete()
        file = null
        _progress.value = Progress()
    }
}
