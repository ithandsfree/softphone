package net.ithandsfree.softphone.sip

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pjsip.pjsua2.Endpoint

/**
 * Opens the microphone and speaker through PJSIP the way a call does (a call checks the sound device before it sends
 * the INVITE; failure there is "Audio subsystem not initialized", PJMEDIA_EAUD_INIT). Microphone permission granted.
 */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @Test
    fun soundDeviceOpensWithMicrophonePermission() {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.uiAutomation.grantRuntimePermission(instr.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        // The app starts its voice engine with the process; use that one (PJSIP allows one endpoint).
        Thread.sleep(2000)
        val ep = Endpoint.instance()
        if (!ep.libIsThreadRegistered()) ep.libRegisterThread("AudioDeviceTest")
        run {
            val adm = ep.audDevManager()
            adm.refreshDevs()
            val devices = (0 until adm.devCount.toInt()).map { i -> adm.getDevInfo(i).let { "${it.driver}/${it.name} in=${it.inputCount} out=${it.outputCount}" } }
            Log.i("AudioDeviceTest", "devices: $devices; capture=${adm.captureDev} playback=${adm.playbackDev}")
            val error = runCatching {
                // Connecting to the sound port opens the sound device, as pjsua_call_make_call does.
                adm.captureDevMedia.startTransmit(adm.playbackDevMedia)
                Thread.sleep(1500)
                adm.captureDevMedia.stopTransmit(adm.playbackDevMedia)
            }.exceptionOrNull()
            Log.i("AudioDeviceTest", "open result: ${error?.message ?: "ok"}")
            assertTrue("sound device opened (devices: $devices): ${error?.message}", error == null)
        }
    }
}
