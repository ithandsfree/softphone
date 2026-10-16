package net.ithandsfree.softphone.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import net.ithandsfree.softphone.data.LinePrefs

/**
 * Rings for an incoming call. Calls go through Android Telecom as a self-managed calling app, and Android does not
 * ring for those: the app must. Plays the line's chosen ringtone (Lines > line > Ringtone; "System default" or
 * unset uses the phone's ringtone), looping, and vibrates, following the phone's ring mode (silent: nothing,
 * vibrate: vibration only). Stops on answer, decline, hang-up or after 90 seconds. Main thread.
 */
object CallRinger {
    private const val TAG = "CallRinger"
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val timeout = Runnable { stop() }
    private val pattern = longArrayOf(0, 800, 400, 800, 400)

    fun start(context: Context, lineId: String?) {
        stop()
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val choice = lineId?.let { runCatching { LinePrefs(app).ringtoneUri(it) }.getOrNull() } ?: "default"
        if (mode == AudioManager.RINGER_MODE_NORMAL && choice != "silent") {
            player = playLooping(app, ringtoneUri(app, choice)) ?: playLooping(app, defaultUri(app))
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) vibrate(app)
        handler.postDelayed(timeout, 90_000L)
    }

    fun stop() {
        handler.removeCallbacks(timeout)
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun ringtoneUri(context: Context, choice: String): Uri =
        if (choice.isBlank() || choice == "default") defaultUri(context) else Uri.parse(choice)

    private fun defaultUri(context: Context): Uri =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_RINGTONE_URI

    private fun playLooping(context: Context, uri: Uri): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
    }.onFailure { Log.w(TAG, "ringtone $uri failed: ${it.message}") }.getOrNull()

    private fun vibrate(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        runCatching {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
            vibrator = v
        }
    }
}
