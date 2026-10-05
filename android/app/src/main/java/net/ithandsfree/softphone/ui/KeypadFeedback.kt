package net.ithandsfree.softphone.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import net.ithandsfree.softphone.data.KeypadPrefs

/**
 * DTMF / soft-click tones and short haptics for keypad digit presses.
 * Sound respects STREAM_DTMF volume; haptics skip when the ringer is silent
 * (covers many DND / silent-mode cases).
 */
class KeypadFeedback(
    private val context: Context,
    private val prefs: KeypadPrefs,
) {
    private var toneGen: ToneGenerator? = null

    fun onDigit(digit: Char) {
        if (prefs.soundEnabled.value) playTone(digit)
        if (prefs.vibrateEnabled.value) vibrateShort()
    }

    private fun playTone(digit: Char) {
        val toneType = dtmfTone(digit) ?: return
        try {
            val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_DTMF, 80).also { toneGen = it }
            gen.startTone(toneType, TONE_MS)
        } catch (_: RuntimeException) {
            // ToneGenerator can throw if audio resources are exhausted.
            release()
        }
    }

    private fun vibrateShort() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // Silent / many DND profiles — skip haptics to match system norms.
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val vibrator = vibrator() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(VIBE_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBE_MS)
            }
        } catch (_: SecurityException) {
            // Missing VIBRATE on odd OEM builds — ignore.
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    fun release() {
        toneGen?.release()
        toneGen = null
    }

    companion object {
        private const val TONE_MS = 120
        private const val VIBE_MS = 18L

        private fun dtmfTone(digit: Char): Int? = when (digit) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> null
        }
    }
}
