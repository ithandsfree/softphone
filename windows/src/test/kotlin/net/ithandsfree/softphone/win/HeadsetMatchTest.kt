package net.ithandsfree.softphone.win

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadsetMatchTest {
    @Test
    fun usbHeadsetSpeakerPairsWithItsMicrophone() {
        val inputs = listOf(
            "Microphone Array (Realtek High Definition Audio)",
            "Microphone (4- Logi USB Headset)",
        )
        assertEquals(
            "Microphone (4- Logi USB Headset)",
            pairCaptureName("Speakers (4- Logi USB Headset)", inputs),
        )
    }

    @Test
    fun unrelatedSpeakerDoesNotStealAnotherMicrophone() {
        assertNull(pairCaptureName("Speakers", listOf("Microphone (4- Logi USB Headset)")))
    }

    @Test
    fun loopWavIsAPlayableHeader() {
        val file = File.createTempFile("ihf-ring", ".wav")
        RingtoneLibrary.writeLoopWav(RingtoneLibrary.DEFAULT, file, seconds = 2)
        val bytes = file.readBytes()
        file.delete()
        assertTrue(bytes.size > 44)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes.copyOfRange(8, 12), Charsets.US_ASCII))
    }
}
