package net.ithandsfree.softphone.sip

import net.ithandsfree.softphone.data.SoftphoneAccount

/** The second call while one is up (call waiting): ringing beside it, or answered and now on hold. */
data class SipWaitingSnapshot(
    /** Stable id for this call (Telecom, call log). */
    val key: Int,
    val remote: String,
    val lineId: String?,
    /** True while it rings; false once answered and on hold (two calls up). */
    val ringing: Boolean,
    val connectedAtElapsed: Long = 0,
)

/** Snapshot of the active SIP call for UI. */
data class SipCallSnapshot(
    val active: Boolean = false,
    val incoming: Boolean = false,
    val remote: String = "",
    val stateText: String = "",
    val lineId: String? = null,
    val speakerOn: Boolean = false,
    /** elapsedRealtime when the call connected. 0 while ringing or idle. */
    val connectedAtElapsed: Long = 0,
    /** Stable id for this call (Telecom, call log). 0 when idle. */
    val key: Int = 0,
    /** Microphone muted (the caller is still heard). */
    val muted: Boolean = false,
    val held: Boolean = false,
    val waiting: SipWaitingSnapshot? = null,
    /** Last blind transfer: SIP status from the PBX NOTIFY (0 = none). */
    val transferCode: Int = 0,
    val transferFinal: Boolean = false,
    val transferText: String = "",
    /** Attended transfer in progress: [waiting] is the caller on hold, this call is to the transfer target. */
    val consulting: Boolean = false,
)

/**
 * SIP registration / calling façade. Messaging works without this.
 *
 * Production engine: [PjsipSipEngine] (PJSUA2 via PjDroid packaging of PJSIP).
 */
interface SipEngine {
    fun start()
    fun stop()
    fun configureAccounts(accounts: List<SoftphoneAccount>)
    fun setActiveAccount(accountId: String?)
    fun call(destination: String)
    fun hangup()
    fun answer()
    fun decline()
    fun setSpeakerphone(on: Boolean)
    fun registrationSummary(): String
    fun callSnapshot(): SipCallSnapshot
    fun setListener(listener: Listener?)

    /** True when [accountId] has an active 2xx REGISTER. */
    fun isLineRegistered(accountId: String): Boolean

    /** True when any configured line is registered. */
    fun anyLineRegistered(): Boolean

    /** Force REGISTER refresh on all soft accounts (network regain / keep-alive). */
    fun reregisterAll()

    /** Mutes or unmutes the microphone on the current call. The caller stays audible. */
    fun setMuted(muted: Boolean) {}

    /** Holds or resumes the current call. */
    fun setHold(hold: Boolean) {}

    /** Sends keypad tones (RFC 2833) on the current call. */
    fun dtmf(digits: String) {}

    /** Blind transfer of the current call. Progress arrives in [SipCallSnapshot.transferCode]. */
    fun transfer(destination: String) {}

    /** Call waiting: hold the current call and answer the ringing one. */
    fun waitingAnswer() {}

    /** Declines the ringing second call (busy: the PBX carries on as for a busy line) or ends the call on hold. */
    fun waitingEnd() {}

    /** Holds the current call and resumes the one on hold. */
    fun swap() {}

    /** Attended transfer, step 1: hold the caller and call [destination] from the same line ("Talk first"). */
    fun consult(destination: String) {}

    /** Attended transfer, step 2: connect the caller on hold to the person on the line, then drop out. */
    fun completeConsult() {}

    /** Attended transfer abandoned: hang up the second call and take the caller off hold. */
    fun cancelConsult() {}

    /**
     * When Android Telecom routes call audio (Bluetooth, car, speaker), the engine must not set the audio mode or
     * speakerphone itself.
     */
    fun setAudioManagedByTelecom(managed: Boolean) {}

    /** Always-on observers (Telecom bridge, foreground service) besides the UI [setListener]. Main thread. */
    fun addObserver(observer: Listener) {}

    fun removeObserver(observer: Listener) {}

    /** "TLS" or "TCP": how SIP signalling reaches the PBX (null before the engine starts). */
    fun signallingLabel(): String? = null

    fun interface Listener {
        /** Registration or call state changed — refresh UI on the main thread. */
        fun onSipChanged()
    }
}

/** No-op engine used only when the native PJSUA2 library fails to load. */
class StubSipEngine : SipEngine {
    private var accounts: List<SoftphoneAccount> = emptyList()
    private var activeId: String? = null
    private var listener: SipEngine.Listener? = null

    override fun start() {}
    override fun stop() {}
    override fun configureAccounts(accounts: List<SoftphoneAccount>) {
        this.accounts = accounts.filter { it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank() }
        listener?.onSipChanged()
    }

    override fun setActiveAccount(accountId: String?) {
        activeId = accountId
        listener?.onSipChanged()
    }

    override fun call(destination: String) {
        throw IllegalStateException("SIP native library unavailable — rebuild with PjDroid/PJSIP")
    }

    override fun hangup() {}
    override fun answer() {}
    override fun decline() {}
    override fun setSpeakerphone(on: Boolean) {}

    override fun registrationSummary(): String {
        val n = accounts.size
        val active = accounts.firstOrNull { it.id == activeId }?.sipExtension ?: "none"
        return "SIP stub: $n account(s), active=$active (native lib missing)"
    }

    override fun callSnapshot(): SipCallSnapshot = SipCallSnapshot()
    override fun setListener(listener: SipEngine.Listener?) {
        this.listener = listener
    }

    override fun isLineRegistered(accountId: String): Boolean = false
    override fun anyLineRegistered(): Boolean = false
    override fun reregisterAll() {}
}
