package net.ithandsfree.softphone.sip

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.pjsip.pjsua2.OnCallTransferStatusParam
import org.pjsip.pjsua2.pjsua_call_flag

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
    private var tcpTransportId = -1
    private var tlsTransportId = -1

    private var accountsCfg: List<SoftphoneAccount> = emptyList()
    private var activeId: String? = null
    private val softAccounts = linkedMapOf<String, SoftAccount>()

    /**
     * One SIP call. Two can be up at once (call waiting): [primary] is the call on screen, [other] rings beside it
     * or is on hold after Hold & answer.
     */
    private inner class Slot(val call: SoftCall, val lineId: String?, val incomingOrigin: Boolean) {
        val key = ++nextKey
        var remote = ""
        var stateText = ""
        /** Incoming and not answered yet. */
        var ringing = incomingOrigin
        var connectedAtElapsed = 0L
        var held = false
    }

    private var nextKey = 0
    private var primary: Slot? = null
    private var other: Slot? = null
    /** Attended transfer: the caller on hold while we talk to the transfer target (null when not transferring). */
    private var consultHeld: Slot? = null
    private var speakerOn = false
    private var muted = false
    private var transferCode = 0
    private var transferFinal = false
    private var transferText = ""
    private var audioByTelecom = false
    private var waitingTone: android.media.ToneGenerator? = null
    private val waitingBeep = object : Runnable {
        override fun run() {
            val ringing = synchronized(lock) { other?.ringing == true }
            if (!ringing) return
            runCatching {
                val tone = waitingTone ?: android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                    .also { waitingTone = it }
                tone.startTone(android.media.ToneGenerator.TONE_SUP_CALL_WAITING, 600)
            }
            mainHandler.postDelayed(this, 4_500L)
        }
    }

    /** Kept for the media callback, which needs the call that owns a media slot. */
    private val currentCall: SoftCall? get() = primary?.call


    private var listener: SipEngine.Listener? = null
    private val observers = java.util.concurrent.CopyOnWriteArrayList<SipEngine.Listener>()

    override fun addObserver(observer: SipEngine.Listener) {
        observers += observer
    }

    override fun signallingLabel(): String? = runCatching { preferredSignalling().name }.getOrNull()

    override fun removeObserver(observer: SipEngine.Listener) {
        observers -= observer
    }

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

                // SIP TLS on 5061. TCP 5060 is the only fallback. UDP signalling
                // is not created: it breaks through NAT and the audio goes one way.
                tlsTransportId = createTlsTransport(ep)
                try {
                    val tcp = TransportConfig().apply { port = 0 }
                    tcpTransportId = ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcp)
                } catch (e: Exception) {
                    Log.w(TAG, "TCP transport unavailable: ${e.message}")
                    tcpTransportId = -1
                }
                if (tlsTransportId < 0 && tcpTransportId < 0) {
                    throw IllegalStateException("SIP needs TLS or TCP. UDP is not used.")
                }

                ep.libStart()
                endpoint = ep
                started = true
                // Creating thread is already registered by libCreate; register anyway
                // so subsequent callers on this same thread stay consistent.
                ensurePjThreadLocked()
                Log.i(
                    TAG,
                    "PJSUA2 started (tls=$tlsTransportId tcp=$tcpTransportId)",
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
            if (primary != null) {
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
            val uri = sipCallUri(digits, domain, preferredSignalling())

            val call = SoftCall(acc)
            val prm = CallOpParam(true)
            try {
                call.makeCall(uri, prm)
                primary = Slot(call, lineId, incomingOrigin = false).apply {
                    remote = digits
                    stateText = "Calling"
                }
                resetTransferLocked()
                notifyChanged()
            } catch (e: Exception) {
                runCatching { call.delete() }
                throw e
            }
        }
    }

    override fun hangup() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: return
            runCatching { slot.call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }) }
            endSlotLocked(slot)
            notifyChanged()
        }
    }

    override fun answer() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: return
            slot.call.answer(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK })
            slot.ringing = false
            slot.stateText = "Connected"
            markConnectedLocked(slot)
            // Audio mode must flip on answer even when Activity was backgrounded.
            applySpeakerLocked()
            notifyChanged()
        }
        mainHandler.post { IncomingCallNotifier.cancel(appContext) }
    }

    override fun decline() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: return
            runCatching { slot.call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }) }
            endSlotLocked(slot)
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

    override fun setAudioManagedByTelecom(managed: Boolean) {
        synchronized(lock) {
            audioByTelecom = managed
            if (!managed) applySpeakerLocked()
        }
    }

    override fun setMuted(muted: Boolean) {
        synchronized(lock) {
            ensurePjThreadLocked()
            this.muted = muted
            applyMuteLocked()
            notifyChanged()
        }
    }

    override fun setHold(hold: Boolean) {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: return
            if (slot.ringing || slot.held == hold) return
            if (hold) {
                slot.call.setHold(CallOpParam(true))
            } else {
                slot.call.reinvite(unholdParam())
            }
            slot.held = hold
            notifyChanged()
        }
    }

    override fun dtmf(digits: String) {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: return
            if (slot.ringing || digits.isBlank()) return
            runCatching { slot.call.dialDtmf(digits) }.onFailure { Log.w(TAG, "dtmf failed: ${it.message}") }
        }
    }

    override fun transfer(destination: String) {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = primary ?: throw IllegalStateException("No call to transfer")
            if (slot.ringing) throw IllegalStateException("Answer the call first")
            val digits = outboundDialDigits(destination)
            if (digits.isBlank()) throw IllegalArgumentException("Enter a number to transfer to")
            val acc = softAccounts[slot.lineId] ?: softAccounts[activeId]
                ?: throw IllegalStateException("SIP account not ready")
            val uri = sipCallUri(digits, domainOf(acc.cfg), preferredSignalling())
            resetTransferLocked()
            slot.call.xfer(uri, CallOpParam(true))
            notifyChanged()
        }
    }

    override fun waitingAnswer() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val waiting = other?.takeIf { it.ringing } ?: return
            val current = primary
            if (current != null && !current.held && !current.ringing) {
                current.call.setHold(CallOpParam(true))
                current.held = true
            }
            stopWaitingBeepLocked()
            // Swap first so the CONFIRMED callback finds the answered call on screen.
            primary = waiting
            other = current
            waiting.call.answer(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK })
            waiting.ringing = false
            waiting.stateText = "Connected"
            markConnectedLocked(waiting)
            applySpeakerLocked()
            notifyChanged()
        }
        mainHandler.post { IncomingCallNotifier.cancel(appContext) }
    }

    override fun waitingEnd() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val slot = other ?: return
            val code = if (slot.ringing) pjsip_status_code.PJSIP_SC_BUSY_HERE else pjsip_status_code.PJSIP_SC_OK
            // 486 lets the PBX carry on as for a busy line (other devices, then voicemail).
            runCatching { slot.call.hangup(CallOpParam().apply { statusCode = code }) }
            endSlotLocked(slot)
            notifyChanged()
        }
        mainHandler.post { IncomingCallNotifier.cancel(appContext) }
    }

    override fun swap() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val held = other?.takeIf { !it.ringing } ?: return
            val current = primary
            if (current != null && !current.held) {
                current.call.setHold(CallOpParam(true))
                current.held = true
            }
            held.call.reinvite(unholdParam())
            held.held = false
            primary = held
            other = current
            notifyChanged()
        }
    }

    override fun consult(destination: String) {
        synchronized(lock) {
            ensurePjThreadLocked()
            val caller = primary ?: throw IllegalStateException("No call to transfer")
            if (caller.ringing) throw IllegalStateException("Answer the call first")
            if (other != null) throw IllegalStateException("End the other call first")
            val digits = outboundDialDigits(destination)
            if (digits.isBlank()) throw IllegalArgumentException("Enter a number to transfer to")
            // Same line as the caller's call, so the second call leaves on that line's own route.
            val acc = softAccounts[caller.lineId] ?: softAccounts[activeId]
                ?: throw IllegalStateException("SIP account not ready")
            if (!caller.held) {
                caller.call.setHold(CallOpParam(true))
                caller.held = true
            }
            val call = SoftCall(acc)
            try {
                call.makeCall(sipCallUri(digits, domainOf(acc.cfg), preferredSignalling()), CallOpParam(true))
            } catch (e: Exception) {
                runCatching { call.delete() }
                runCatching { caller.call.reinvite(unholdParam()) }
                caller.held = false
                throw e
            }
            primary = Slot(call, caller.lineId, incomingOrigin = false).apply {
                remote = digits
                stateText = "Calling"
            }
            other = caller
            consultHeld = caller
            resetTransferLocked()
            notifyChanged()
        }
    }

    override fun completeConsult() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val caller = consultHeld ?: return
            val target = listOfNotNull(primary, other).firstOrNull { it !== caller } ?: return
            resetTransferLocked()
            // REFER with Replaces: the PBX joins the caller to the transfer target, then both our legs end.
            caller.call.xferReplaces(target.call, CallOpParam(true))
            notifyChanged()
        }
    }

    override fun cancelConsult() {
        synchronized(lock) {
            ensurePjThreadLocked()
            val caller = consultHeld ?: return
            val target = listOfNotNull(primary, other).firstOrNull { it !== caller }
            consultHeld = null
            if (target != null) {
                runCatching { target.call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK }) }
                endSlotLocked(target)
            }
            if (primary === caller && caller.held) {
                runCatching { caller.call.reinvite(unholdParam()) }
                caller.held = false
            }
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
            val slot = primary ?: return SipCallSnapshot(
                speakerOn = speakerOn,
                transferCode = transferCode,
                transferFinal = transferFinal,
                transferText = transferText,
            )
            ensurePjThreadLocked()
            return SipCallSnapshot(
                active = true,
                incoming = slot.ringing,
                remote = slot.remote.ifBlank { runCatching { slot.call.info.remoteUri }.getOrNull().orEmpty() },
                stateText = slot.stateText.ifBlank { runCatching { slot.call.info.stateText }.getOrNull().orEmpty() },
                lineId = slot.lineId,
                speakerOn = speakerOn,
                connectedAtElapsed = slot.connectedAtElapsed,
                key = slot.key,
                muted = muted,
                held = slot.held,
                waiting = other?.let {
                    SipWaitingSnapshot(it.key, it.remote, it.lineId, it.ringing, it.connectedAtElapsed)
                },
                transferCode = transferCode,
                transferFinal = transferFinal,
                transferText = transferText,
                consulting = consultHeld != null && other === consultHeld,
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

    private fun unholdParam(): CallOpParam = CallOpParam(true).apply {
        opt.flag = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
    }

    /** Hangs up every call (stop). */
    private fun hangupLocked() {
        listOfNotNull(other, primary).forEach { slot ->
            runCatching { slot.call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_DECLINE }) }
            endSlotLocked(slot)
        }
        notifyChanged()
    }

    private fun markConnectedLocked(slot: Slot) {
        if (slot.connectedAtElapsed == 0L) {
            slot.connectedAtElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun resetTransferLocked() {
        transferCode = 0
        transferFinal = false
        transferText = ""
    }

    /**
     * A call ended. When it was the call on screen and a second call exists, that one takes its place: a ringing
     * one becomes a normal incoming call (full-screen ring), a held one stays on hold until Resume.
     */
    private fun endSlotLocked(slot: Slot) {
        runCatching { slot.call.delete() }
        // Either call of an attended transfer ending ends the transfer; the other call stays as an ordinary call.
        if (consultHeld != null && (slot === consultHeld || slot === primary || slot === other)) consultHeld = null
        if (other === slot) {
            if (slot.ringing) stopWaitingBeepLocked()
            other = null
            return
        }
        if (primary !== slot) return
        val next = other
        other = null
        primary = next
        if (next == null) {
            muted = false
            applyMuteLocked()
            speakerOn = false
            applySpeakerLocked()
            mainHandler.post { IncomingCallNotifier.cancel(appContext) }
            return
        }
        if (next.ringing) {
            stopWaitingBeepLocked()
            val acc = softAccounts[next.lineId]
            val label = acc?.cfg?.label?.ifBlank { acc.cfg.sipExtension } ?: ""
            val remote = next.remote
            val lineIdForRing = next.lineId
            mainHandler.post { IncomingCallNotifier.show(appContext, remote, label, lineIdForRing) }
        }
    }

    private fun stopWaitingBeepLocked() {
        mainHandler.removeCallbacks(waitingBeep)
        runCatching { waitingTone?.stopTone() }
    }

    /**
     * Microphone only. The sound device is one bridge port for both mic and speaker: its *Tx* level is what the mic
     * sends into the bridge, its *Rx* level is what the speaker plays. Mute sets Tx to 0, so the caller is still heard.
     */
    private fun applyMuteLocked() {
        val ep = endpoint ?: return
        runCatching {
            val device = ep.audDevManager().captureDevMedia
            device.adjustTxLevel(if (muted && primary != null) 0f else 1f)
            device.adjustRxLevel(1f)
        }
            .onFailure { Log.w(TAG, "mute failed: ${it.message}") }
    }

    private fun applySpeakerLocked() {
        // Android Telecom routes call audio (Bluetooth, car, speaker) once the call is registered with it.
        if (audioByTelecom) return
        runCatching {
            // MODE_IN_COMMUNICATION is required for speakerphone routing on modern Android.
            audioManager.mode =
                if (primary != null) AudioManager.MODE_IN_COMMUNICATION
                else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speakerOn && primary != null
        }.onFailure { Log.w(TAG, "speakerphone set failed: ${it.message}") }
    }

    private fun notifyChanged() {
        mainHandler.post {
            listener?.onSipChanged()
            observers.forEach { runCatching { it.onSipChanged() } }
        }
    }

    private fun domainOf(cfg: SoftphoneAccount): String =
        cfg.sipDomain.ifBlank { BuildConfig.DEFAULT_SIP_DOMAIN }.trim()

    private fun preferredSignalling(): SipSignalling = when {
        tlsTransportId >= 0 -> SipSignalling.TLS
        tcpTransportId >= 0 -> SipSignalling.TCP
        else -> throw IllegalStateException("SIP needs TLS or TCP. UDP is not used.")
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
            // TLS when that transport exists. TCP is the only fallback.
            when (signalling) {
                SipSignalling.TLS -> {
                    acfg.sipConfig.transportId = tlsTransportId
                    acfg.sipConfig.contactUriParams = ";transport=tls"
                }
                SipSignalling.TCP -> acfg.sipConfig.transportId = tcpTransportId
                SipSignalling.UDP -> throw IllegalStateException("UDP SIP is not used.")
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
            val waiting: Boolean
            synchronized(lock) {
                if (primary != null && other != null) {
                    // Two calls already up: busy, so the PBX tries other devices, then voicemail.
                    try {
                        val busy = SoftCall(this, prm.callId)
                        busy.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE })
                        busy.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "busy reject failed", e)
                    }
                    return
                }
                val call = SoftCall(this, prm.callId)
                val slot = Slot(call, cfg.id, incomingOrigin = true).apply {
                    this.remote = runCatching { call.info.remoteUri }.getOrNull().orEmpty()
                    stateText = "Incoming"
                }
                runCatching { call.answer(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_RINGING }) }
                waiting = primary != null
                if (waiting) other = slot else primary = slot
                remote = slot.remote
                label = cfg.label.ifBlank { cfg.sipExtension }
                notifyChanged()
            }
            if (waiting) {
                // Call waiting: a short beep in the call audio; the in-call screen offers Hold & answer.
                mainHandler.post(waitingBeep)
            } else {
                // Full-screen ring — works when Activity is backgrounded / locked.
                mainHandler.post { IncomingCallNotifier.show(appContext, remote, label, cfg.id) }
            }
        }
    }

    private inner class SoftCall : Call {
        constructor(account: SoftAccount) : super(account)
        constructor(account: SoftAccount, callId: Int) : super(account, callId)

        private fun slotLocked(): Slot? = when {
            primary?.call === this -> primary
            other?.call === this -> other
            else -> null
        }

        override fun onCallState(prm: OnCallStateParam?) {
            val ci = runCatching { info }.getOrNull()
            val text = ci?.stateText.orEmpty()
            val state = ci?.state ?: pjsip_inv_state.PJSIP_INV_STATE_NULL
            Log.i(TAG, "call state=$text ($state) status=${ci?.lastStatusCode}")
            synchronized(lock) {
                val slot = slotLocked() ?: return
                slot.stateText = text
                if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                    endSlotLocked(slot)
                } else if (state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                    slot.ringing = false
                    markConnectedLocked(slot)
                }
            }
            notifyChanged()
        }

        /** NOTIFY progress of a blind transfer. After a final 2xx we end our own leg (RFC 5589). */
        override fun onCallTransferStatus(prm: OnCallTransferStatusParam?) {
            if (prm == null) return
            Log.i(TAG, "transfer status ${prm.statusCode} ${prm.reason} final=${prm.finalNotify}")
            synchronized(lock) {
                transferCode = prm.statusCode
                transferFinal = prm.finalNotify
                transferText = prm.reason.orEmpty()
                if (prm.finalNotify && prm.statusCode / 100 == 2) {
                    prm.cont = false
                    // Attended transfer done: our call to the transfer target ends too (the PBX usually ends it).
                    val mine = slotLocked()
                    if (mine != null && mine === consultHeld) {
                        listOfNotNull(primary, other).firstOrNull { it !== mine }?.let { target ->
                            runCatching { target.call.hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK }) }
                        }
                    }
                    // The DISCONNECTED callback ends the slot; deleting the call inside its own callback is unsafe.
                    runCatching { hangup(CallOpParam().apply { statusCode = pjsip_status_code.PJSIP_SC_OK }) }
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
                // Re-apply speaker route and mute after media connects (device can reset on answer).
                synchronized(lock) {
                    applySpeakerLocked()
                    applyMuteLocked()
                }
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
