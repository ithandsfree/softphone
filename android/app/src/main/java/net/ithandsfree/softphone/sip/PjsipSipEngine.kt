package net.ithandsfree.softphone.sip

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.data.SoftphoneAccount
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_media_status

/**
 * Multi-account PJSUA2 engine for FreePBX PJSIP extensions.
 *
 * Native bits come from PjDroid (`com.pjdroid:pjdroid`) which packages PJSIP/PJSUA2.
 * PJSIP itself remains GPL / Teluu dual-license — keep this app private/closed and
 * obtain a Teluu commercial license before public closed distribution.
 */
class PjsipSipEngine(
    private val appContext: Context,
) : SipEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var endpoint: Endpoint? = null
    private var started = false
    private var udpTransportId = -1
    private var tcpTransportId = -1
    private var tlsTransportId = -1

    private var accountsCfg: List<SoftphoneAccount> = emptyList()
    private var activeId: String? = null
    private val softAccounts = linkedMapOf<String, SoftAccount>()

    private var currentCall: SoftCall? = null
    private var callLineId: String? = null
    private var callIncoming = false
    private var callRemote = ""
    private var callStateText = ""
    private var speakerOn = false

    private var listener: SipEngine.Listener? = null

    /**
     * PJSUA2 aborts the process if any PJ API runs on a thread that has not
     * called [Endpoint.libRegisterThread] (SIGABRT in `pj_thread_this`).
     * Kotlin coroutines ([Dispatchers.IO] / DefaultDispatch) hit this from
     * [SipRegistrationService] keep-alive — Samsung Android 16 crash in v0.3.7.
     */
    private fun ensurePjThreadLocked() {
        val ep = endpoint ?: return
        runCatching {
            if (!ep.libIsThreadRegistered()) {
                ep.libRegisterThread(Thread.currentThread().name)
            }
        }.onFailure {
            Log.e(TAG, "libRegisterThread failed on ${Thread.currentThread().name}", it)
        }
    }

    override fun setListener(listener: SipEngine.Listener?) {
        this.listener = listener
    }

    override fun start() {
        synchronized(lock) {
            if (started) return
            try {
                val ep = Endpoint()
                ep.libCreate()
                val epConfig = EpConfig()
                epConfig.uaConfig.userAgent = "${BuildConfig.USER_AGENT_NAME}/${BuildConfig.VERSION_NAME} PJSUA2"
                epConfig.uaConfig.maxCalls = 4
                epConfig.logConfig.level = 4
                epConfig.logConfig.consoleLevel = 4
                ep.libInit(epConfig)

                // SIP TLS on 5061. TCP is the fallback when the TLS transport cannot
                // be created. UDP is only a last resort (carrier NAT / one-way audio).
                tlsTransportId = createTlsTransport(ep)
                try {
                    val tcp = TransportConfig().apply { port = 0 }
                    tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcp)
                } catch (e: Exception) {
                    Log.w(TAG, "TCP transport unavailable: ${e.message}")
                    tcpTransportId = -1
                }
                if (tlsTransportId < 0 && tcpTransportId < 0) {
                    val udp = TransportConfig().apply { port = 0 }
                    udpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, udp)
                    Log.w(TAG, "Falling back to UDP SIP transport — expect NAT/one-way-audio risk")
                } else {
                    udpTransportId = -1
                }

                ep.libStart()
                endpoint = ep
                started = true
                // Creating thread is already registered by libCreate; register anyway
                // so subsequent callers on this same thread stay consistent.
                ensurePjThreadLocked()
                Log.i(
                    TAG,
                    "PJSUA2 started (tls=$tlsTransportId tcp=$tcpTransportId udp=$udpTransportId)",
                )
            } catch (e: Exception) {
                Log.e(TAG, "PJSUA2 start failed", e)
                started = false
                endpoint = null
                throw e
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            ensurePjThreadLocked()
            hangupLocked()
            softAccounts.values.forEach { runCatching { it.delete() } }
            softAccounts.clear()
            val ep = endpoint
            endpoint = null
            started = false
            if (ep != null) {
                runCatching { ep.libDestroy() }
                runCatching { ep.delete() }
            }
            notifyChanged()
        }
    }

    override fun configureAccounts(accounts: List<SoftphoneAccount>) {
        synchronized(lock) {
            try {
                ensureStartedLocked()
            } catch (e: Exception) {
                Log.e(TAG, "PJSUA2 not started — skip account configure", e)
                return
            }
            ensurePjThreadLocked()
            accountsCfg = accounts.filter {
                it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank()
            }
            val keep = accountsCfg.map { it.id }.toSet()
            softAccounts.keys.filter { it !in keep }.toList().forEach { id ->
                softAccounts.remove(id)?.let { runCatching { it.delete() } }
            }
            for (cfg in accountsCfg) {
                val existing = softAccounts[cfg.id]
                if (existing != null) {
                    // Recreate when credentials/domain change.
                    if (existing.matches(cfg)) continue
                    softAccounts.remove(cfg.id)
                    runCatching { existing.delete() }
                }
                try {
                    softAccounts[cfg.id] = SoftAccount(cfg).also { it.createFrom(cfg) }
                } catch (e: Exception) {
                    Log.e(TAG, "Account create failed for ${cfg.sipExtension}", e)
                }
            }
            if (activeId == null || softAccounts[activeId] == null) {
                activeId = softAccounts.keys.firstOrNull()
            }
            softAccounts[activeId]?.let { runCatching { it.setDefault() } }
            notifyChanged()
        }
    }

    override fun setActiveAccount(accountId: String?) {
        synchronized(lock) {
            ensurePjThreadLocked()
            activeId = accountId
            softAccounts[accountId]?.let { runCatching { it.setDefault() } }
            notifyChanged()
        }
    }

    override fun call(destination: String) {
        synchronized(lock) {
            ensureStartedLocked()
            ensurePjThreadLocked()
            if (currentCall != null) {
                throw IllegalStateException("Already in a call — hang up first")
            }
            val digits = outboundDialDigits(destination)
            if (digits.isBlank()) throw IllegalArgumentException("Empty destination")

            val lineId = activeId
                ?: softAccounts.keys.firstOrNull()
                ?: throw IllegalStateException("No SIP account configured")
            val acc = softAccounts[lineId]
                ?: throw IllegalStateException("SIP account not registered yet")
            if (!isRegisteredLocked(acc)) {
                // Kick REGISTER; caller may retry after a short wait.
                runCatching { acc.setRegistration(true) }
                throw IllegalStateException(
                    "SIP not registered (${acc.cfg.sipExtension}) — wait for Voice ready, then try again",
                )
            }
            val domain = domainOf(acc.cfg)
            val uri = if (digits.contains("@")) {
                "sip:$digits"
            } else {
                "sip:$digits@$domain"
            }

            val call = SoftCall(acc)
            val prm = CallOpParam(true)
            try {
                call.makeCall(uri, prm)
                currentCall = call
                callLineId = lineId
                callIncoming = false
                callRemote = digits
                callStateText = "Calling"
                notifyChanged()
            } catch (e: Exception) {
                runCatching { call.delete() }
                currentCall = null
                throw e
            }
        }
    }

    override fun hangup() {
        synchronized(lock) {
            ensurePjThreadLocked()
            hangupLocked()
        }
    }

    override fun answer() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val call = currentCall ?: return
            val prm = CallOpParam().apply {
                statusCode = pjsip_status_code.PJSIP_SC_OK
            }
            call.answer(prm)
            callIncoming = false
            callStateText = "Connected"
            // Audio mode must flip on answer even when Activity was backgrounded.
            applySpeakerLocked()
            notifyChanged()
        }
        mainHandler.post { IncomingCallNotifier.cancel(appContext) }
    }

    override fun decline() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val call = currentCall ?: return
            val prm = CallOpParam().apply {
                statusCode = pjsip_status_code.PJSIP_SC_DECLINE
            }
            runCatching { call.hangup(prm) }
            clearCallLocked()
            notifyChanged()
        }
    }

    override fun setSpeakerphone(on: Boolean) {
        synchronized(lock) {
            speakerOn = on
            applySpeakerLocked()
            notifyChanged()
        }
    }

    override fun registrationSummary(): String {
        synchronized(lock) {
            if (!started) return "PJSIP not started"
            ensurePjThreadLocked()
            if (softAccounts.isEmpty()) {
                val pending = accountsCfg.size
                return if (pending == 0) {
                    "PJSIP: no SIP credentials on lines"
                } else {
                    "PJSIP: $pending account(s) configuring…"
                }
            }
            val parts = softAccounts.map { (id, acc) ->
                val mark = if (id == activeId) "*" else ""
                val info = runCatching { acc.info }.getOrNull()
                val status = when {
                    info == null -> "?"
                    info.regIsActive && info.regStatus / 100 == 2 -> "OK"
                    info.regIsActive -> "reg ${info.regStatus}"
                    else -> info.regStatusText.ifBlank { "off" }
                }
                "${acc.cfg.sipExtension}$mark:$status"
            }
            return "PJSIP " + parts.joinToString(" · ")
        }
    }

    override fun callSnapshot(): SipCallSnapshot {
        synchronized(lock) {
            val call = currentCall ?: return SipCallSnapshot(speakerOn = speakerOn)
            ensurePjThreadLocked()
            return SipCallSnapshot(
                active = true,
                incoming = callIncoming,
                remote = callRemote.ifBlank {
                    runCatching { call.info.remoteUri }.getOrNull().orEmpty()
                },
                stateText = callStateText.ifBlank {
                    runCatching { call.info.stateText }.getOrNull().orEmpty()
                },
                lineId = callLineId,
                speakerOn = speakerOn,
            )
        }
    }

    override fun isLineRegistered(accountId: String): Boolean {
        synchronized(lock) {
            ensurePjThreadLocked()
            val acc = softAccounts[accountId] ?: return false
            return isRegisteredLocked(acc)
        }
    }

    override fun anyLineRegistered(): Boolean {
        synchronized(lock) {
            ensurePjThreadLocked()
            return softAccounts.values.any { isRegisteredLocked(it) }
        }
    }

    override fun reregisterAll() {
        synchronized(lock) {
            ensureStartedLocked()
            ensurePjThreadLocked()
            softAccounts.values.forEach { acc ->
                runCatching { acc.setRegistration(true) }
                    .onFailure { Log.w(TAG, "reregister ${acc.cfg.sipExtension}: ${it.message}") }
            }
            notifyChanged()
        }
    }

    private fun isRegisteredLocked(acc: SoftAccount): Boolean {
        val info = runCatching { acc.info }.getOrNull() ?: return false
        return info.regIsActive && info.regStatus / 100 == 2
    }

    private fun ensureStartedLocked() {
        if (!started) start()
        ensurePjThreadLocked()
    }

    private fun hangupLocked() {
        val call = currentCall ?: return
        runCatching {
            val prm = CallOpParam().apply {
                statusCode = pjsip_status_code.PJSIP_SC_DECLINE
            }
            call.hangup(prm)
        }
        clearCallLocked()
        notifyChanged()
    }

    private fun clearCallLocked() {
        currentCall?.let { runCatching { it.delete() } }
        currentCall = null
        callLineId = null
        callIncoming = false
        callRemote = ""
        callStateText = ""
        speakerOn = false
        applySpeakerLocked()
        mainHandler.post { IncomingCallNotifier.cancel(appContext) }
    }

    private fun applySpeakerLocked() {
        runCatching {
            // MODE_IN_COMMUNICATION is required for speakerphone routing on modern Android.
            audioManager.mode =
                if (currentCall != null) AudioManager.MODE_IN_COMMUNICATION
                else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn && currentCall != null
        }.onFailure { Log.w(TAG, "speakerphone set failed: ${it.message}") }
    }

    private fun notifyChanged() {
        mainHandler.post { listener?.onSipChanged() }
    }

    private fun domainOf(cfg: SoftphoneAccount): String =
        cfg.sipDomain.ifBlank { BuildConfig.DEFAULT_SIP_DOMAIN }.trim()

    private fun preferredSignalling(): SipSignalling = when {
        tlsTransportId >= 0 -> SipSignalling.TLS
        tcpTransportId >= 0 -> SipSignalling.TCP
        else -> SipSignalling.UDP
    }

    private fun createTlsTransport(ep: Endpoint): Int {
        val caFile = writeAndroidCaBundle(appContext)
        if (caFile == null) {
            Log.w(TAG, "SIP TLS skipped — no CA bundle to verify the PBX certificate")
            return -1
        }
        return try {
            val tls = TransportConfig().apply { port = 0 }
            val tlsCfg = tls.tlsConfig
            tlsCfg.caListFile = caFile
            tlsCfg.verifyServer = true
            tlsCfg.msecTimeout = 8_000L
            tls.tlsConfig = tlsCfg
            ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tls)
        } catch (e: Exception) {
            Log.w(TAG, "TLS transport unavailable: ${e.message}")
            -1
        }
    }

    private inner class SoftAccount(
        var cfg: SoftphoneAccount,
    ) : Account() {
        fun matches(other: SoftphoneAccount): Boolean =
            cfg.sipExtension == other.sipExtension &&
                cfg.sipPassword == other.sipPassword &&
                domainOf(cfg) == domainOf(other)

        fun createFrom(account: SoftphoneAccount) {
            cfg = account
            val domain = domainOf(account)
            val ext = account.sipExtension.trim()
            val acfg = AccountConfig()
            val signalling = preferredSignalling()
            acfg.idUri = sipAccountIdUri(ext, domain)
            acfg.regConfig.registrarUri = sipRegistrarUri(domain, signalling)
            acfg.regConfig.registerOnAdd = true
            // Refresh before expiry so cellular NAT bindings stay warm.
            acfg.regConfig.timeoutSec = 300
            acfg.regConfig.delayBeforeRefreshSec = 60
            acfg.regConfig.retryIntervalSec = 30
            acfg.regConfig.firstRetryIntervalSec = 5
            acfg.sipConfig.authCreds.add(
                AuthCredInfo("digest", "*", ext, 0, account.sipPassword),
            )
            // Rewrite Contact/Via/SDP for WAN softphones behind carrier NAT.
            runCatching {
                acfg.natConfig.contactRewriteUse = 1
                acfg.natConfig.viaRewriteUse = 1
                acfg.natConfig.sdpNatRewriteUse = 1
            }.onFailure { Log.w(TAG, "natConfig unavailable: ${it.message}") }
            // TLS when the transport exists. TCP, then UDP, only if it does not.
            when (signalling) {
                SipSignalling.TLS -> {
                    acfg.sipConfig.transportId = tlsTransportId
                    acfg.sipConfig.contactUriParams = ";transport=tls"
                }
                SipSignalling.TCP -> acfg.sipConfig.transportId = tcpTransportId
                SipSignalling.UDP -> acfg.sipConfig.transportId = udpTransportId
            }
            // Optional SDES/SRTP so shared extensions with FreePBX Media Encryption
            // (sdes) still negotiate. Over TLS, SRTP requires secure signalling.
            runCatching {
                acfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                acfg.mediaConfig.srtpSecureSignaling = if (signalling == SipSignalling.TLS) 1 else 0
            }.onFailure { Log.w(TAG, "srtpUse unavailable: ${it.message}") }
            create(acfg, true)
        }

        override fun onRegState(prm: OnRegStateParam?) {
            Log.i(TAG, "reg ${cfg.sipExtension}: code=${prm?.code} ${prm?.reason}")
            notifyChanged()
        }

        override fun onIncomingCall(prm: OnIncomingCallParam?) {
            if (prm == null) return
            val remote: String
            val label: String
            synchronized(lock) {
                if (currentCall != null) {
                    // Busy: reject second call for now.
                    try {
                        val busy = SoftCall(this, prm.callId)
                        val op = CallOpParam().apply {
                            statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
                        }
                        busy.hangup(op)
                        busy.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "busy reject failed", e)
                    }
                    return
                }
                val call = SoftCall(this, prm.callId)
                currentCall = call
                callLineId = cfg.id
                callIncoming = true
                callRemote = runCatching { call.info.remoteUri }.getOrNull().orEmpty()
                callStateText = "Incoming"
                remote = callRemote
                label = cfg.label.ifBlank { cfg.sipExtension }
                notifyChanged()
            }
            // Full-screen ring — works when Activity is backgrounded / locked.
            mainHandler.post {
                IncomingCallNotifier.show(appContext, remote, label)
            }
        }
    }

    private inner class SoftCall : Call {
        constructor(account: SoftAccount) : super(account)
        constructor(account: SoftAccount, callId: Int) : super(account, callId)

        override fun onCallState(prm: OnCallStateParam?) {
            val ci = runCatching { info }.getOrNull()
            val text = ci?.stateText.orEmpty()
            val state = ci?.state ?: pjsip_inv_state.PJSIP_INV_STATE_NULL
            Log.i(TAG, "call state=$text ($state)")
            synchronized(lock) {
                if (currentCall !== this) return
                callStateText = text
                if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                    clearCallLocked()
                }
            }
            notifyChanged()
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam?) {
            try {
                val ci = info
                val ep = endpoint ?: return
                for (i in 0 until ci.media.size) {
                    val mediaInfo = ci.media[i] ?: continue
                    if (mediaInfo.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                    if (mediaInfo.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE &&
                        mediaInfo.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD
                    ) {
                        continue
                    }
                    val aud = getAudioMedia(i.toInt()) ?: continue
                    val playback = ep.audDevManager().playbackDevMedia
                    val capture = ep.audDevManager().captureDevMedia
                    aud.startTransmit(playback)
                    capture.startTransmit(aud)
                }
                // Re-apply speaker route after media connects (device can reset on answer).
                synchronized(lock) { applySpeakerLocked() }
            } catch (e: Exception) {
                Log.e(TAG, "media connect failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PjsipSipEngine"

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("pjsua2")
        }

        fun create(context: Context): SipEngine {
            return try {
                PjsipSipEngine(context.applicationContext).also { it.start() }
            } catch (e: Throwable) {
                Log.e(TAG, "Falling back to StubSipEngine", e)
                StubSipEngine().also { it.start() }
            }
        }
    }
}
