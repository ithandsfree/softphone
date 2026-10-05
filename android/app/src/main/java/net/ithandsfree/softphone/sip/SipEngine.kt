package net.ithandsfree.softphone.sip

import net.ithandsfree.softphone.data.SoftphoneAccount

/** Snapshot of the active SIP call for UI. */
data class SipCallSnapshot(
    val active: Boolean = false,
    val incoming: Boolean = false,
    val remote: String = "",
    val stateText: String = "",
    val lineId: String? = null,
    val speakerOn: Boolean = false,
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
