package net.ithandsfree.softphone.win

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.sin

/** A short local tone for a keypad press. It is not sent to the other person. */
internal object KeyTone {
    private val freqs = mapOf(
        '1' to 697, '2' to 770, '3' to 852,
        '4' to 697, '5' to 770, '6' to 852,
        '7' to 697, '8' to 770, '9' to 852,
        '*' to 941, '0' to 941, '#' to 941,
    )

    fun play(digit: String) {
        if (!AudioPrefs.keypadTone()) return
        val freq = freqs[digit.firstOrNull() ?: return] ?: return
        Thread {
            runCatching {
                val rate = 8000
                val samples = rate / 12
                val data = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val wave = (sin(2.0 * Math.PI * freq * i / rate) * 4000).toInt()
                    data[i * 2] = (wave and 0xff).toByte()
                    data[i * 2 + 1] = ((wave shr 8) and 0xff).toByte()
                }
                val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                line.write(data, 0, data.size)
                line.drain()
                line.close()
            }
        }.apply { isDaemon = true; name = "ihf-key-tone"; start() }
    }
}
