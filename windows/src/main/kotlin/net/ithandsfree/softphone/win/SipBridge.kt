package net.ithandsfree.softphone.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * JNA bridge to ihf_sip.dll, which is PJSIP built from source for Windows.
 * GPL-2.0. The Android PjDroid binaries are not loaded here.
 */
object SipBridge {
    const val USER_AGENT = "IHF-Softphone/0.1.0 PJSUA"

    data class Snapshot(
        val started: Boolean = false,
        val transport: SipSignalling? = null,
        val tlsUp: Boolean = false,
        val tcpUp: Boolean = false,
        val nullAudio: Boolean = false,
        val registered: Boolean = false,
        val regCode: Int = 0,
        val regReason: String = "",
        val callActive: Boolean = false,
        val incoming: Boolean = false,
        val callState: String = "",
        val remote: String = "",
        val lastError: String = "",
        val muted: Boolean = false,
        val held: Boolean = false,
        val consultActive: Boolean = false,
        val consultState: String = "",
        val consultRemote: String = "",
        val activeLine: Int = 0,
        val secondRegistered: Boolean = false,
        val extensionA: String = "",
        val extensionB: String = "",
        val callExtension: String = "",
    )

    data class AudioDevice(
        val index: Int,
        val inputs: Int,
        val outputs: Int,
        val name: String,
    ) {
        override fun toString(): String = name
    }

    private val native: IhfSip? = try {
        Native.load("ihf_sip", IhfSip::class.java)
    } catch (err: UnsatisfiedLinkError) {
        null
    } catch (err: Exception) {
        null
    }

    val loadError: String? = if (native == null) {
        "Voice library ihf_sip.dll is not loaded. Build it with native/build-pjsip.ps1."
    } else {
        null
    }

    fun start(caFile: File, userAgent: String = USER_AGENT): Int {
        val lib = native ?: return -1
        return lib.ihf_sip_start(userAgent, caFile.absolutePath)
    }

    fun stop() {
        native?.ihf_sip_stop()
    }

    fun snapshot(): Snapshot {
        val lib = native ?: return Snapshot(lastError = loadError.orEmpty())
        val raw = IhfStatus()
        lib.ihf_sip_get_status(raw)
        raw.read()
        val transport = when (raw.transport) {
            1 -> SipSignalling.TLS
            2 -> SipSignalling.TCP
            3 -> SipSignalling.UDP
            else -> null
        }
        return Snapshot(
            started = raw.started != 0,
            transport = transport,
            tlsUp = raw.tlsUp != 0,
            tcpUp = raw.tcpUp != 0,
            nullAudio = raw.nullAudio != 0,
            registered = raw.regActive != 0,
            regCode = raw.regCode,
            regReason = cString(raw.regReason),
            callActive = raw.callActive != 0,
            incoming = raw.incoming != 0,
            callState = cString(raw.callState),
            remote = cString(raw.remote),
            lastError = cString(raw.lastError),
            muted = raw.muted != 0,
            held = raw.held != 0,
            consultActive = raw.consultActive != 0,
            consultState = cString(raw.consultState),
            consultRemote = cString(raw.consultRemote),
            activeLine = raw.activeLine,
            secondRegistered = raw.regB != 0,
            extensionA = cString(raw.extA),
            extensionB = cString(raw.extB),
            callExtension = cString(raw.callExt),
        )
    }

    fun audioDevices(): List<AudioDevice> {
        val lib = native ?: return emptyList()
        return try {
            val rows = IhfAudDev().toArray(32) as Array<IhfAudDev>
            val count = lib.ihf_sip_aud_list(rows[0], rows.size)
            if (count <= 0) return emptyList()
            (0 until count).map { index ->
                rows[index].read()
                AudioDevice(
                    index = rows[index].index,
                    inputs = rows[index].inputs,
                    outputs = rows[index].outputs,
                    name = cString(rows[index].name),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun refreshDevices(): Int = try {
        native?.ihf_sip_refresh_devices() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun openDevices(capture: Int, playback: Int): Int = try {
        native?.ihf_sip_open_devices(capture, playback) ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** 0 to 255. Zero when the line is not registered or the microphone is quiet. */
    fun micLevel(): Int = try {
        native?.ihf_sip_mic_level()?.coerceIn(0, 255) ?: 0
    } catch (_: Throwable) {
        0
    }

    fun setCapture(index: Int): Int = try {
        native?.ihf_sip_set_capture(index) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun setPlayback(index: Int): Int = try {
        native?.ihf_sip_set_playback(index) ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** Play a WAV through the open call speaker, so a USB headset hears the ringtone. */
    fun ringStart(wavPath: String): Int = try {
        native?.ihf_sip_ring_start(wavPath) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun ringStop(): Int = try {
        native?.ihf_sip_ring_stop() ?: -1
    } catch (_: Throwable) {
        0
    }

    fun setMute(muted: Boolean): Int = try {
        native?.ihf_sip_set_mute(if (muted) 1 else 0) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun hold(): Int = try {
        native?.ihf_sip_hold() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun resume(): Int = try {
        native?.ihf_sip_resume() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun transfer(uri: String): Int = try {
        native?.ihf_sip_transfer(uri) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun consult(uri: String): Int = try {
        native?.ihf_sip_consult(uri) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun consultFinish(): Int = try {
        native?.ihf_sip_consult_finish() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun consultCancel(): Int = try {
        native?.ihf_sip_consult_cancel() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun dtmf(digits: String): Int = try {
        native?.ihf_sip_dtmf(digits) ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun register(idUri: String, regUri: String, user: String, password: String, signalling: SipSignalling): Int {
        val lib = native ?: return -1
        return lib.ihf_sip_register(idUri, regUri, user, password, signalling.wire())
    }

    fun useLine(index: Int): Int = native?.ihf_sip_use_line(index) ?: -1

    fun call(uri: String): Int = native?.ihf_sip_call(uri) ?: -1

    fun answer(): Int = native?.ihf_sip_answer() ?: -1

    fun decline(): Int = native?.ihf_sip_decline() ?: -1

    fun hangup(): Int = native?.ihf_sip_hangup() ?: -1

    fun pollLog(): String? {
        val lib = native ?: return null
        val buf = ByteArray(240)
        val n = lib.ihf_sip_poll_log(buf, buf.size)
        if (n <= 0) return null
        return cString(buf).ifBlank { null }
    }

    private fun cString(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, StandardCharsets.UTF_8).trim()
    }

    private fun SipSignalling.wire(): Int = when (this) {
        SipSignalling.TLS -> 1
        SipSignalling.TCP -> 2
        SipSignalling.UDP -> 3
    }

    private interface IhfSip : Library {
        fun ihf_sip_start(userAgent: String, caFile: String): Int
        fun ihf_sip_stop()
        fun ihf_sip_get_status(status: IhfStatus)
        fun ihf_sip_register(idUri: String, regUri: String, user: String, password: String, signalling: Int): Int
        fun ihf_sip_use_line(index: Int): Int
        fun ihf_sip_call(uri: String): Int
        fun ihf_sip_answer(): Int
        fun ihf_sip_decline(): Int
        fun ihf_sip_hangup(): Int
        fun ihf_sip_poll_log(buf: ByteArray, buflen: Int): Int
        fun ihf_sip_aud_list(first: IhfAudDev, maxCount: Int): Int
        fun ihf_sip_refresh_devices(): Int
        fun ihf_sip_open_devices(capture: Int, playback: Int): Int
        fun ihf_sip_mic_level(): Int
        fun ihf_sip_set_capture(index: Int): Int
        fun ihf_sip_set_playback(index: Int): Int
        fun ihf_sip_set_mute(muted: Int): Int
        fun ihf_sip_hold(): Int
        fun ihf_sip_resume(): Int
        fun ihf_sip_transfer(uri: String): Int
        fun ihf_sip_consult(uri: String): Int
        fun ihf_sip_consult_finish(): Int
        fun ihf_sip_consult_cancel(): Int
        fun ihf_sip_dtmf(digits: String): Int
        fun ihf_sip_ring_start(wavPath: String): Int
        fun ihf_sip_ring_stop(): Int
    }

    class IhfAudDev : Structure() {
        @JvmField var index: Int = 0
        @JvmField var inputs: Int = 0
        @JvmField var outputs: Int = 0
        @JvmField var name: ByteArray = ByteArray(128)

        override fun getFieldOrder(): List<String> = listOf("index", "inputs", "outputs", "name")
    }

    class IhfStatus : Structure() {
        @JvmField var started: Int = 0
        @JvmField var transport: Int = 0
        @JvmField var tlsUp: Int = 0
        @JvmField var tcpUp: Int = 0
        @JvmField var nullAudio: Int = 0
        @JvmField var regActive: Int = 0
        @JvmField var regCode: Int = 0
        @JvmField var callActive: Int = 0
        @JvmField var incoming: Int = 0
        @JvmField var callId: Int = -1
        @JvmField var regReason: ByteArray = ByteArray(128)
        @JvmField var callState: ByteArray = ByteArray(64)
        @JvmField var remote: ByteArray = ByteArray(256)
        @JvmField var lastError: ByteArray = ByteArray(512)
        @JvmField var muted: Int = 0
        @JvmField var held: Int = 0
        @JvmField var consultActive: Int = 0
        @JvmField var consultId: Int = -1
        @JvmField var consultState: ByteArray = ByteArray(64)
        @JvmField var consultRemote: ByteArray = ByteArray(256)
        @JvmField var activeLine: Int = 0
        @JvmField var regB: Int = 0
        @JvmField var extA: ByteArray = ByteArray(32)
        @JvmField var extB: ByteArray = ByteArray(32)
        @JvmField var callExt: ByteArray = ByteArray(32)

        override fun getFieldOrder(): List<String> = listOf(
            "started",
            "transport",
            "tlsUp",
            "tcpUp",
            "nullAudio",
            "regActive",
            "regCode",
            "callActive",
            "incoming",
            "callId",
            "regReason",
            "callState",
            "remote",
            "lastError",
            "muted",
            "held",
            "consultActive",
            "consultId",
            "consultState",
            "consultRemote",
            "activeLine",
            "regB",
            "extA",
            "extB",
            "callExt",
        )
    }
}
