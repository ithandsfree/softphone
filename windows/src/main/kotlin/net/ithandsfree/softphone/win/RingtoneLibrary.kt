package net.ithandsfree.softphone.win

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

internal data class RingtoneChoice(val id: String, val label: String)

/** Original tones. These are not copies of a phone maker's ringtones. */
internal object RingtoneLibrary {
    const val CUSTOM = "custom"
    const val DEFAULT = "two-tone"

    val builtIn = listOf(
        RingtoneChoice("two-tone", "Two tone"),
        RingtoneChoice("classic", "Classic phone"),
        RingtoneChoice("bright", "Bright"),
        RingtoneChoice("soft", "Soft"),
        RingtoneChoice("chime", "Chime"),
        RingtoneChoice("pulse", "Pulse"),
    )

    fun choice(id: String): RingtoneChoice = builtIn.firstOrNull { it.id == id } ?: builtIn.first()

    fun customLabel(fileName: String) = "Your file: $fileName"

    /** 8 kHz 16-bit mono PCM. An unknown id uses Two tone. */
    fun render(id: String): ByteArray {
        val style = builtIn.firstOrNull { it.id == id }?.id ?: DEFAULT
        val seconds = if (style == DEFAULT) 1 else 4
        val data = ByteArray(8000 * seconds * 2)
        when (style) {
            "classic" -> {
                add(data, 0, 2000, 440.0, 5000.0)
                add(data, 0, 2000, 480.0, 5000.0)
            }
            "bright" -> {
                add(data, 0, 180, 988.0, 6000.0)
                add(data, 260, 180, 1318.0, 6000.0)
                add(data, 520, 180, 988.0, 6000.0)
                add(data, 780, 180, 1318.0, 6000.0)
            }
            "soft" -> {
                add(data, 0, 500, 392.0, 2800.0)
                add(data, 700, 500, 349.0, 2800.0)
                add(data, 1400, 700, 330.0, 2400.0)
            }
            "chime" -> {
                add(data, 0, 420, 784.0, 5500.0)
                add(data, 480, 420, 659.0, 5000.0)
                add(data, 960, 700, 523.0, 4500.0)
            }
            "pulse" -> {
                repeat(4) { index ->
                    add(data, index * 280, 120, 660.0, 6500.0)
                }
            }
            else -> {
                add(data, 0, 320, 440.0, 7000.0)
                add(data, 420, 320, 480.0, 7000.0)
            }
        }
        return data
    }

    /** A long WAV so the call speaker can ring for the whole incoming call. */
    fun writeLoopWav(id: String, file: File, seconds: Int = 36) {
        val once = render(id)
        val copies = (seconds * 8000 * 2 / once.size.coerceAtLeast(2)).coerceAtLeast(1)
        val pcm = ByteArray(once.size * copies)
        repeat(copies) { index -> once.copyInto(pcm, index * once.size) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(8000)
        header.putInt(16000)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        file.parentFile?.mkdirs()
        file.writeBytes(header.array() + pcm)
    }

    private fun add(data: ByteArray, startMs: Int, durMs: Int, hz: Double, amp: Double) {
        val rate = 8000
        val start = startMs * rate / 1000
        val count = durMs * rate / 1000
        for (i in 0 until count) {
            val index = (start + i) * 2
            if (index + 1 >= data.size) return
            val existing = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            val signed = (existing shl 16) shr 16
            val sample = (signed + sin(2.0 * PI * hz * i / rate) * amp).toInt().coerceIn(-32767, 32767)
            data[index] = (sample and 0xff).toByte()
            data[index + 1] = (sample shr 8 and 0xff).toByte()
        }
    }
}
