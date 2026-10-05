package net.ithandsfree.softphone

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.data.AccountStore
import net.ithandsfree.softphone.data.CallHistoryStore
import net.ithandsfree.softphone.data.DeviceContactsRepository
import net.ithandsfree.softphone.data.HiddenInboxStore
import net.ithandsfree.softphone.data.KeypadPrefs
import net.ithandsfree.softphone.data.LinePrefs
import net.ithandsfree.softphone.data.SkinPrefs
import net.ithandsfree.softphone.data.SoftphoneApi
import net.ithandsfree.softphone.notify.MessageNotifier
import net.ithandsfree.softphone.notify.MessageSyncService
import net.ithandsfree.softphone.notify.NotifyPrefs
import net.ithandsfree.softphone.notify.SmsInboxPoller
import net.ithandsfree.softphone.notify.SmsPollWorker
import net.ithandsfree.softphone.sip.IncomingCallNotifier
import net.ithandsfree.softphone.sip.PjsipSipEngine
import net.ithandsfree.softphone.sip.SipEngine
import net.ithandsfree.softphone.sip.SipRegistrationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SoftphoneApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var accountStore: AccountStore
        private set
    lateinit var linePrefs: LinePrefs
        private set
    lateinit var hiddenInbox: HiddenInboxStore
        private set
    lateinit var skinPrefs: SkinPrefs
        private set
    lateinit var keypadPrefs: KeypadPrefs
        private set
    lateinit var contactsRepo: DeviceContactsRepository
        private set
    lateinit var callHistory: CallHistoryStore
        private set
    lateinit var sipEngine: SipEngine
        private set
    lateinit var notifyPrefs: NotifyPrefs
        private set

    /** Aggregate inbound unread across enrolled SMS lines (poll-driven). */
    private val _inboxUnread = MutableStateFlow(0)
    val inboxUnread: StateFlow<Int> = _inboxUnread.asStateFlow()

    /** Bumps when connectivity returns — ViewModel can clear offline + reload. */
    private val _networkEpoch = MutableStateFlow(0L)
    val networkEpoch: StateFlow<Long> = _networkEpoch.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        accountStore = AccountStore(this)
        linePrefs = LinePrefs(this)
        hiddenInbox = HiddenInboxStore(this)
        skinPrefs = SkinPrefs(this)
        keypadPrefs = KeypadPrefs(this)
        notifyPrefs = NotifyPrefs(this)
        contactsRepo = DeviceContactsRepository(this)
        callHistory = CallHistoryStore(this)
        sipEngine = PjsipSipEngine.create(this).also { engine ->
            runCatching {
                engine.configureAccounts(accountStore.list())
                val active = linePrefs.activeLineId()
                if (active != null) engine.setActiveAccount(active)
            }.onFailure { Log.w(TAG, "initial SIP configure failed: ${it.message}") }
        }
        val accounts = runCatching { accountStore.list() }.getOrDefault(emptyList())
        val active = linePrefs.activeLineId()
        if (accounts.isNotEmpty() && (active == null || accounts.none { it.id == active })) {
            linePrefs.setActiveLineId(accounts.first().id)
            runCatching { sipEngine.setActiveAccount(accounts.first().id) }
        }
        MessageNotifier.ensureChannel(this)
        MessageSyncService.ensureSyncChannel(this)
        IncomingCallNotifier.ensureChannel(this)
        SipRegistrationService.ensureChannel(this)
        SmsPollWorker.schedule(this)
        SmsPollWorker.kick(this)
        // FGS start must never abort Application.onCreate (v0.3.5 phoneCall crash).
        runCatching { MessageSyncService.reconcile(this) }
            .onFailure { Log.e(TAG, "MessageSync reconcile failed: ${it.message}") }
        runCatching { SipRegistrationService.reconcile(this) }
            .onFailure { Log.e(TAG, "SipRegistration reconcile failed: ${it.message}") }
        registerNetworkCallback()
        // Tenant skin from BFF health (skipped once the user picks a skin in Settings).
        appScope.launch {
            runCatching {
                SoftphoneApi().fetchBranding()?.defaultSkin?.let { skinPrefs.applyTenantDefault(it) }
            }
        }
        // In-process poll while Application is alive (~30s). When the app is
        // minimized, [MessageSyncService] keeps the process warm and polls too.
        appScope.launch {
            while (isActive) {
                if (!notifyPrefs.isMessageSyncEnabled()) {
                    delay(30_000L)
                    continue
                }
                runCatching {
                    val result = SmsInboxPoller.poll(this@SoftphoneApp, accountStore)
                    _inboxUnread.value = result.totalUnread
                }
                delay(30_000L)
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "network available — reregister SIP + kick message sync")
                        _networkEpoch.value = System.currentTimeMillis()
                        SipRegistrationService.requestReregister(this@SoftphoneApp)
                        SmsPollWorker.kick(this@SoftphoneApp)
                        MessageSyncService.reconcile(this@SoftphoneApp)
                    }

                    override fun onLost(network: Network) {
                        Log.i(TAG, "network lost")
                    }
                },
            )
        }.onFailure { Log.w(TAG, "network callback register failed: ${it.message}") }
    }

    fun publishInboxUnread(total: Int) {
        _inboxUnread.value = total
    }

    fun refreshInboxUnread() {
        appScope.launch {
            runCatching {
                val result = SmsInboxPoller.poll(this@SoftphoneApp, accountStore)
                _inboxUnread.value = result.totalUnread
            }
        }
        MessageSyncService.reconcile(this)
        SipRegistrationService.reconcile(this)
        SmsPollWorker.kick(this)
    }

    companion object {
        private const val TAG = "SoftphoneApp"
    }
}
