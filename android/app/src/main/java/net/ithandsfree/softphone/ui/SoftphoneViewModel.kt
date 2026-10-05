package net.ithandsfree.softphone.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.data.AccountStore
import net.ithandsfree.softphone.data.ApiException
import net.ithandsfree.softphone.data.CallDirection
import net.ithandsfree.softphone.data.CallHistoryStore
import net.ithandsfree.softphone.data.CallRecord
import net.ithandsfree.softphone.data.ChatMessage
import net.ithandsfree.softphone.data.DeviceContact
import net.ithandsfree.softphone.data.DeviceContactsRepository
import net.ithandsfree.softphone.data.HiddenInboxStore
import net.ithandsfree.softphone.data.LineCapabilities
import net.ithandsfree.softphone.data.LineInfo
import net.ithandsfree.softphone.data.LinePrefs
import net.ithandsfree.softphone.data.SoftphoneAccount
import net.ithandsfree.softphone.data.SoftphoneApi
import net.ithandsfree.softphone.data.ThreadInfo
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.data.looksLikeEnrolToken
import net.ithandsfree.softphone.data.normalizeDidDigits
import net.ithandsfree.softphone.data.normalizeEnrolToken
import net.ithandsfree.softphone.sip.SipCallSnapshot
import net.ithandsfree.softphone.sip.SipEngine
import net.ithandsfree.softphone.sip.outboundDialDigits
import net.ithandsfree.softphone.ui.theme.LineUi
import net.ithandsfree.softphone.ui.theme.toLineUi
import java.io.File
import java.io.FileOutputStream

/**
 * Recents list filter. [Line] is one enrolled extension (not “this line” /
 * whatever happens to be selected for messaging).
 */
sealed class RecentsFilter {
    data object All : RecentsFilter()
    data object Missed : RecentsFilter()
    data class Line(val lineId: String) : RecentsFilter()
}

fun matchesRecentsFilter(rec: CallRecord, filter: RecentsFilter): Boolean = when (filter) {
    RecentsFilter.All -> true
    RecentsFilter.Missed -> rec.direction == CallDirection.Missed
    is RecentsFilter.Line -> rec.lineId == filter.lineId
}

data class UiState(
    val accounts: List<SoftphoneAccount> = emptyList(),
    val lines: List<LineUi> = emptyList(),
    /**
     * Persisted **default extension** — seeds Messages + Calls Calling-from on
     * app open. Changed only from Settings / Lines “Default”, never from the
     * keypad Calling-from chip.
     */
    val activeLineId: String? = null,
    /**
     * Line whose Messages threads are on screen. Session-only — tapping a
     * Messages chip must not change [activeLineId] or reorder the rail.
     */
    val messagesLineId: String? = null,
    /**
     * Keypad **Calling from** line. Session-only — changing it must not rewrite
     * [activeLineId]; resets to the default when the process/app restarts.
     */
    val outboundLineId: String? = null,
    val busy: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val threads: List<ThreadInfo> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    /** Inbound unread across enrolled SMS lines (poll + thread refresh). */
    val inboxUnreadTotal: Int = 0,
    val contacts: List<DeviceContact> = emptyList(),
    val contactsPermissionDenied: Boolean = false,
    val pendingEnrolToken: String? = null,
    /** After UM login, when the user owns multiple DIDs and must pick one. */
    val pendingEnrol: PendingEnrol? = null,
    /** DIDs available for the open line (from BFF), for Change DID. */
    val availableDidsForLine: List<String> = emptyList(),
    val callHistory: List<CallRecord> = emptyList(),
    val recentsFilter: RecentsFilter = RecentsFilter.All,
    val sipSummary: String = "",
    val call: SipCallSnapshot = SipCallSnapshot(),
    val splashDone: Boolean = false,
    /**
     * After enrol / dial, when the line still has no SIP secret — show a clear
     * one-time save dialog (not a forever toast).
     */
    val pendingSipSecretAccountId: String? = null,
    /** Set after a successful public request-enrol (navigates to link-sent). */
    val enrolRequestDoneFor: String? = null,
    /** Resend cooldown seconds returned by BFF (default 30). */
    val enrolRequestRetryAfter: Int = 30,
    /** Community flavor: last PBX URL / SIP domain the user entered. */
    val serverBase: String = "",
    val serverSipDomain: String = "",
)

data class PendingEnrol(
    val label: String,
    val umUsername: String,
    val umPassword: String,
    val sipExtension: String,
    val sipPassword: String,
    val apiBase: String,
    val sipDomain: String = "",
    val token: String,
    val dids: List<String>,
    val lineInfos: List<LineInfo> = emptyList(),
    val maxExtensions: Int = 2,
)

class SoftphoneViewModel(
    private val store: AccountStore,
    private val linePrefs: LinePrefs,
    private val hiddenInbox: HiddenInboxStore,
    private val contactsRepo: DeviceContactsRepository,
    private val callHistoryStore: CallHistoryStore,
    private val sipEngine: SipEngine,
    private val cacheDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        sipEngine.setListener {
            _state.update {
                it.copy(
                    sipSummary = sipEngine.registrationSummary(),
                    call = sipEngine.callSnapshot(),
                )
            }
        }
        refreshAccounts()
        refreshCallHistory()
        _state.update {
            it.copy(
                pendingEnrolToken = store.pendingEnrolToken(),
                sipSummary = sipEngine.registrationSummary(),
                call = sipEngine.callSnapshot(),
            )
        }
    }

    override fun onCleared() {
        sipEngine.setListener(null)
        super.onCleared()
    }

    fun markSplashDone() = _state.update { it.copy(splashDone = true) }

    fun clearEnrolRequestResult() = _state.update { it.copy(enrolRequestDoneFor = null) }

    private fun rememberServer(apiBase: String, sipDomain: String = "") {
        val base = apiBase.trim()
        val domain = sipDomain.trim()
        if (base.isBlank() && domain.isBlank()) return
        _state.update {
            it.copy(
                serverBase = base.ifBlank { it.serverBase },
                serverSipDomain = domain.ifBlank { it.serverSipDomain },
            )
        }
    }

    private fun resolveApiBase(apiBase: String): String {
        val chosen = apiBase.trim().ifBlank { _state.value.serverBase }.ifBlank { BuildConfig.DEFAULT_API_BASE }
        if (chosen.isNotBlank()) rememberServer(chosen)
        return chosen
    }

    private fun resolveSipDomain(sipDomain: String): String {
        val chosen = sipDomain.trim().ifBlank { _state.value.serverSipDomain }.ifBlank { BuildConfig.DEFAULT_SIP_DOMAIN }
        if (chosen.isNotBlank()) rememberServer(apiBase = "", sipDomain = chosen)
        return chosen
    }

    /**
     * Public "Email me a setup link" — BFF looks up Userman email, issues enrol
     * token(s), emails HTTPS link(s). UX is always check-inbox (no enumeration).
     */
    fun requestEnrolLink(email: String, apiBase: String = "") {
        val trimmed = email.trim()
        val base = apiBase.trim().ifBlank { BuildConfig.DEFAULT_API_BASE }
        if (base.isBlank()) {
            _state.update { it.copy(busy = false, error = "Enter the HTTPS softphone URL on your PBX") }
            return
        }
        rememberServer(base)
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    enrolRequestDoneFor = null,
                    offline = false,
                )
            }
            try {
                val api = SoftphoneApi(base)
                val online = runCatching { api.health() }.getOrDefault(false)
                if (!online) {
                    _state.update {
                        it.copy(busy = false, offline = true, error = "No connection")
                    }
                    return@launch
                }
                val resp = api.requestEnrol(trimmed)
                if (!resp.ok && resp.error == "invalid_email") {
                    _state.update {
                        it.copy(busy = false, error = "Enter a valid work email")
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        busy = false,
                        enrolRequestDoneFor = trimmed,
                        enrolRequestRetryAfter = resp.retryAfter.coerceIn(15, 120),
                        error = null,
                    )
                }
            } catch (e: ApiException) {
                if (e.error == "invalid_email") {
                    _state.update {
                        it.copy(busy = false, error = "Enter a valid work email")
                    }
                } else {
                    // Still advance to check-inbox on transport blips after send —
                    // only surface hard client validation failures inline.
                    _state.update {
                        it.copy(
                            busy = false,
                            offline = e.httpCode == 0,
                            error = if (e.httpCode == 0) "No connection" else e.message,
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        offline = true,
                        error = e.message ?: "No connection",
                    )
                }
            }
        }
    }

    fun refreshSip() {
        _state.update {
            it.copy(
                sipSummary = sipEngine.registrationSummary(),
                call = sipEngine.callSnapshot(),
            )
        }
    }

    fun hangupCall() {
        sipEngine.hangup()
        refreshSip()
    }

    fun answerCall() {
        try {
            sipEngine.answer()
            refreshSip()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Answer failed") }
        }
    }

    fun declineCall() {
        sipEngine.decline()
        refreshSip()
    }

    fun toggleSpeakerphone() {
        val next = !_state.value.call.speakerOn
        sipEngine.setSpeakerphone(next)
        refreshSip()
    }

    fun refreshCallHistory() {
        _state.update { it.copy(callHistory = callHistoryStore.list()) }
    }

    fun setRecentsFilter(filter: RecentsFilter) {
        _state.update { it.copy(recentsFilter = filter) }
    }

    fun filteredCallHistory(): List<CallRecord> {
        val s = _state.value
        return s.callHistory.filter { matchesRecentsFilter(it, s.recentsFilter) }
    }

    fun callRecord(id: String): CallRecord? = callHistoryStore.get(id)

    fun deleteCallRecord(id: String) {
        callHistoryStore.delete(id)
        refreshCallHistory()
    }

    /**
     * Place an outbound call on [lineId]. Always writes Recents when the attempt
     * finishes. Requires SIP extension/secret on the line and mic permission.
     * Waits briefly for REGISTER when the line is not yet reachable.
     */
    fun placeCall(
        lineId: String,
        number: String,
        displayName: String = "",
    ): CallRecord? {
        val digits = outboundDialDigits(number)
        if (digits.isBlank()) return null
        val account = store.list().firstOrNull { it.id == lineId } ?: return null
        val sipReady = account.sipExtension.isNotBlank() && account.sipPassword.isNotBlank()
        if (!sipReady) {
            // Try BFF secret exchange first; if that fails, show the save dialog.
            _state.update {
                it.copy(
                    status = "Fetching SIP secret for ${account.label}…",
                    pendingSipSecretAccountId = lineId,
                )
            }
            viewModelScope.launch {
                val fetched = fetchAndStoreSipCredentials(lineId)
                if (fetched) {
                    completePlaceCallAsync(lineId, digits, displayName)
                } else {
                    val note =
                        "This line has no SIP secret yet. Enter it below (or Lines → ${account.label})."
                    val record = CallRecord(
                        lineId = lineId,
                        peerNumber = normalizeDidDigits(digits),
                        peerDisplayName = displayName,
                        direction = CallDirection.Outgoing,
                        durationSec = 0,
                        answered = false,
                        note = note,
                    )
                    callHistoryStore.append(record)
                    refreshCallHistory()
                    _state.update {
                        it.copy(
                            status = note,
                            pendingSipSecretAccountId = lineId,
                            sipSummary = sipEngine.registrationSummary(),
                            call = sipEngine.callSnapshot(),
                        )
                    }
                }
            }
            return null
        }
        viewModelScope.launch {
            completePlaceCallAsync(lineId, digits, displayName)
        }
        return null
    }

    private suspend fun completePlaceCallAsync(
        lineId: String,
        digits: String,
        displayName: String,
    ): CallRecord? {
        sipEngine.setActiveAccount(lineId)
        if (!sipEngine.isLineRegistered(lineId)) {
            _state.update {
                it.copy(status = "Registering ${store.list().firstOrNull { a -> a.id == lineId }?.sipExtension ?: "line"}…")
            }
            sipEngine.reregisterAll()
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline && !sipEngine.isLineRegistered(lineId)) {
                delay(250)
            }
        }
        var note = ""
        var ok = false
        try {
            sipEngine.call(digits)
            ok = true
        } catch (e: Exception) {
            note = e.message ?: "Call failed"
        }
        val record = CallRecord(
            lineId = lineId,
            peerNumber = normalizeDidDigits(digits),
            peerDisplayName = displayName,
            direction = CallDirection.Outgoing,
            durationSec = 0,
            answered = false,
            note = if (ok) "" else note.ifBlank { "Call not connected — check SIP registration" },
        )
        callHistoryStore.append(record)
        refreshCallHistory()
        _state.update {
            it.copy(
                status = if (ok) {
                    "Calling ${displayName.ifBlank { formatDidForDisplay(digits) }}…"
                } else {
                    note.ifBlank { "Call failed — saved to Recents" }
                },
                sipSummary = sipEngine.registrationSummary(),
                call = sipEngine.callSnapshot(),
            )
        }
        return record
    }

    /** Connectivity regained — clear offline banner and reload the open inbox. */
    fun onNetworkRestored() {
        val lineId = _state.value.messagesLineId ?: _state.value.activeLineId
        _state.update { it.copy(offline = false, error = null) }
        if (!lineId.isNullOrBlank()) {
            loadThreads(lineId)
        }
        refreshSip()
    }

    /** Record an inbound/missed event (PJSIP callbacks / push will call this). */
    fun recordInboundCall(
        lineId: String,
        number: String,
        displayName: String = "",
        missed: Boolean,
        durationSec: Int = 0,
    ) {
        callHistoryStore.append(
            CallRecord(
                lineId = lineId,
                peerNumber = normalizeDidDigits(number),
                peerDisplayName = displayName,
                direction = if (missed) CallDirection.Missed else CallDirection.Incoming,
                durationSec = durationSec,
                answered = !missed,
            ),
        )
        refreshCallHistory()
    }

    fun resolveContactName(number: String): String {
        val want = normalizeDidDigits(number)
        if (want.isBlank()) return ""
        return _state.value.contacts.firstOrNull { c ->
            c.phones.any { normalizeDidDigits(it.number) == want }
        }?.displayName.orEmpty()
    }

    fun refreshAccounts() {
        val list = store.list()
        runCatching { sipEngine.configureAccounts(list) }
            .onFailure { android.util.Log.e("SoftphoneVM", "configureAccounts: ${it.message}", it) }
        var active = linePrefs.defaultLineId()
        if (list.isNotEmpty() && (active == null || list.none { it.id == active })) {
            active = list.first().id
            linePrefs.setDefaultLineId(active)
        }
        if (list.isEmpty()) {
            active = null
            linePrefs.setDefaultLineId(null)
        }
        _state.update { s ->
            val messagesLine = when {
                list.isEmpty() -> null
                s.messagesLineId != null && list.any { it.id == s.messagesLineId } -> s.messagesLineId
                else -> active
            }
            val outboundLine = when {
                list.isEmpty() -> null
                s.outboundLineId != null && list.any { it.id == s.outboundLineId } -> s.outboundLineId
                else -> active
            }
            val filter = when (val f = s.recentsFilter) {
                is RecentsFilter.Line -> if (list.any { it.id == f.lineId }) f else RecentsFilter.All
                else -> f
            }
            s.copy(
                accounts = list,
                lines = list.mapIndexed { i, a -> a.toLineUi(i) },
                activeLineId = active,
                messagesLineId = messagesLine,
                outboundLineId = outboundLine,
                recentsFilter = filter,
                sipSummary = sipEngine.registrationSummary(),
                error = null,
            )
        }
    }

    /**
     * Set the persisted **default extension** (app-open Messages + Calls seed).
     * Also aligns session Calling-from / Messages view to the new default.
     * Does not reload message threads until [setMessagesLine] / refresh.
     */
    fun setActiveLine(accountId: String) {
        if (store.list().none { it.id == accountId }) return
        linePrefs.setDefaultLineId(accountId)
        sipEngine.setActiveAccount(accountId)
        _state.update {
            it.copy(
                activeLineId = accountId,
                outboundLineId = accountId,
                messagesLineId = accountId,
                sipSummary = sipEngine.registrationSummary(),
            )
        }
        loadThreads(accountId)
    }

    /** Alias used by Settings / Lines “Default extension”. */
    fun setDefaultLine(accountId: String) = setActiveLine(accountId)

    /**
     * Keypad Calling-from only (session). Does not change the persisted default.
     */
    fun setOutboundLine(accountId: String) {
        if (store.list().none { it.id == accountId }) return
        sipEngine.setActiveAccount(accountId)
        _state.update {
            it.copy(
                outboundLineId = accountId,
                sipSummary = sipEngine.registrationSummary(),
            )
        }
    }

    /**
     * Select which line’s SMS threads to show. Stable chip order; does not change
     * the default extension or keypad Calling-from.
     */
    fun setMessagesLine(accountId: String) {
        if (store.list().none { it.id == accountId }) return
        if (_state.value.messagesLineId == accountId) {
            loadThreads(accountId)
            return
        }
        _state.update {
            it.copy(
                messagesLineId = accountId,
                threads = emptyList(),
            )
        }
        loadThreads(accountId)
    }

    fun activeAccount(): SoftphoneAccount? {
        val id = _state.value.activeLineId ?: return null
        return _state.value.accounts.firstOrNull { it.id == id }
    }

    /** Line used for the next keypad / contacts outbound dial this session. */
    fun outboundAccount(): SoftphoneAccount? {
        val id = _state.value.outboundLineId ?: _state.value.activeLineId ?: return null
        return _state.value.accounts.firstOrNull { it.id == id }
    }

    fun messagesAccount(): SoftphoneAccount? {
        val id = _state.value.messagesLineId ?: return null
        return _state.value.accounts.firstOrNull { it.id == id }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearStatus() = _state.update { it.copy(status = null) }

    fun setPendingEnrolToken(token: String?) {
        val normalized = normalizeEnrolToken(token)
        if (normalized.isBlank()) {
            store.clearPendingEnrolToken()
            _state.update { it.copy(pendingEnrolToken = null) }
        } else {
            store.savePendingEnrolToken(normalized)
            _state.update { it.copy(pendingEnrolToken = normalized) }
        }
    }

    /**
     * Enrol using a pre-issued BFF token from email / HTTPS `/enrol/{token}/` page.
     * Does not require FreePBX User Manager password. SIP secret is optional
     * (SMS works without it; voice needs the secret filled here or later on the line).
     */
    fun enrolWithToken(
        enrolToken: String,
        label: String,
        sipExtension: String,
        sipPassword: String,
        apiBase: String,
        sipDomain: String = "",
        preferDid: String? = null,
    ) {
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, error = null, status = "Checking enrol token…", offline = false, pendingEnrol = null)
            }
            try {
                val token = normalizeEnrolToken(enrolToken)
                if (!looksLikeEnrolToken(token)) {
                    throw ApiException("invalid_enrol_token", 400)
                }
                val base = resolveApiBase(apiBase)
                if (base.isBlank()) {
                    throw ApiException("Enter the HTTPS softphone URL on your PBX", 400)
                }
                val domain = resolveSipDomain(sipDomain)
                val api = SoftphoneApi(base)
                val session = api.session(token)
                val allDids = session.lines.map { normalizeDidDigits(it.did) }.filter { it.isNotBlank() }.distinct()
                if (allDids.isEmpty()) throw ApiException("no_lines", 400)
                val maxExt = session.entitlements?.maxExtensions ?: 2
                if (store.list().size >= maxExt) {
                    throw ApiException("max_extensions_reached ($maxExt)", 403)
                }
                val used = store.list().map { normalizeDidDigits(it.did) }.toSet()
                val unused = allDids.filterNot { it in used }
                val preferred = preferDid?.let { normalizeDidDigits(it) }?.takeIf { it in allDids }
                val umUser = session.user?.username?.takeIf { it.isNotBlank() } ?: "token-enrol"
                val infos = session.lines
                val defaultLabel = label.ifBlank {
                    preferred?.let { "Ext ${infos.firstOrNull { normalizeDidDigits(it.did) == preferred }?.extension.orEmpty()}" }
                        ?: infos.firstOrNull()?.extension?.takeIf { it.isNotBlank() }?.let { "Ext $it" }
                        ?: umUser
                }

                when {
                    preferred != null -> finishEnrol(
                        label = defaultLabel,
                        umUsername = umUser,
                        umPassword = "",
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = token,
                        did = preferred,
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                    unused.size == 1 -> finishEnrol(
                        label = defaultLabel.ifBlank { "Ext ${infos.firstOrNull { normalizeDidDigits(it.did) == unused.first() }?.extension.orEmpty()}" },
                        umUsername = umUser,
                        umPassword = "",
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = token,
                        did = unused.first(),
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                    allDids.size > 1 -> {
                        val choices = unused.ifEmpty { allDids }
                        _state.update {
                            it.copy(
                                busy = false,
                                status = null,
                                pendingEnrol = PendingEnrol(
                                    label = defaultLabel,
                                    umUsername = umUser,
                                    umPassword = "",
                                    sipExtension = sipExtension.trim(),
                                    sipPassword = sipPassword,
                                    apiBase = base,
                                    sipDomain = domain,
                                    token = token,
                                    dids = choices,
                                    lineInfos = infos,
                                    maxExtensions = maxExt,
                                ),
                            )
                        }
                    }
                    else -> finishEnrol(
                        label = defaultLabel,
                        umUsername = umUser,
                        umPassword = "",
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = token,
                        did = unused.firstOrNull() ?: allDids.first(),
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                }
            } catch (e: Exception) {
                val offline = e is java.net.UnknownHostException ||
                    e is java.io.IOException && e.message?.contains("Unable to resolve", true) == true
                val msg = when {
                    e is ApiException && e.httpCode == 401 ->
                        "Enrol token invalid or expired — open the HTTPS enrol link for a fresh token"
                    else -> e.message ?: "enrol_failed"
                }
                _state.update {
                    it.copy(busy = false, offline = offline, error = msg, status = null)
                }
            }
        }
    }

    /**
     * DEBUG-only lab seed via `ihfphone://demo-seed?...`.
     * Skips UM password login; stores a pre-issued BFF token + line binding.
     */
    fun seedFromDemoDeepLink(uri: Uri) {
        if (!BuildConfig.DEBUG) {
            _state.update { it.copy(error = "demo-seed requires a debug build") }
            return
        }
        if (!uri.host.equals("demo-seed", ignoreCase = true)) return
        val token = uri.getQueryParameter("token").orEmpty().trim()
        val did = normalizeDidDigits(uri.getQueryParameter("did").orEmpty())
        if (token.isBlank() || did.isBlank()) {
            _state.update { it.copy(error = "demo-seed needs token + did") }
            return
        }
        val apiBase = uri.getQueryParameter("apiBase")
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_API_BASE
        val label = uri.getQueryParameter("label")?.takeIf { it.isNotBlank() } ?: "Demo line"
        val ext = uri.getQueryParameter("ext").orEmpty()
        val sipSecret = uri.getQueryParameter("sipSecret").orEmpty()
        val umUser = uri.getQueryParameter("um")?.takeIf { it.isNotBlank() } ?: "demo-seed"
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, status = "Seeding demo line…") }
            try {
                val api = SoftphoneApi(apiBase)
                val lines = runCatching { api.lines(token) }.getOrDefault(emptyList())
                val info = lines.firstOrNull { normalizeDidDigits(it.did) == did }
                finishEnrol(
                    label = label,
                    umUsername = umUser,
                    umPassword = "",
                    sipExtension = ext.ifBlank { info?.extension.orEmpty() },
                    sipPassword = sipSecret,
                    apiBase = apiBase,
                    token = token,
                    did = did,
                    lineCountHint = lines.size.coerceAtLeast(1),
                    lineInfos = lines,
                )
                _state.update {
                    it.copy(
                        busy = false,
                        status = "Demo line ready · ${formatDidForDisplay(did)}",
                        splashDone = true,
                    )
                }
                loadThreads()
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = e.message ?: "demo-seed failed")
                }
            }
        }
    }

    fun addAccount(
        label: String,
        umUsername: String,
        umPassword: String,
        sipExtension: String,
        sipPassword: String,
        apiBase: String,
        sipDomain: String = "",
        preferDid: String? = null,
    ) {
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, error = null, status = "Logging in…", offline = false, pendingEnrol = null)
            }
            try {
                val base = resolveApiBase(apiBase)
                if (base.isBlank()) {
                    throw ApiException("Enter the HTTPS softphone URL on your PBX", 400)
                }
                val domain = resolveSipDomain(sipDomain)
                val api = SoftphoneApi(base)
                val login = api.login(umUsername.trim(), umPassword)
                val allDids = login.lines.map { normalizeDidDigits(it.did) }.filter { it.isNotBlank() }.distinct()
                if (allDids.isEmpty()) throw ApiException("no_lines", 400)
                val maxExt = login.entitlements?.maxExtensions ?: 2
                if (store.list().size >= maxExt) {
                    throw ApiException("max_extensions_reached ($maxExt)", 403)
                }

                val used = store.list().map { normalizeDidDigits(it.did) }.toSet()
                val unused = allDids.filterNot { it in used }
                val preferred = preferDid?.let { normalizeDidDigits(it) }?.takeIf { it in allDids }
                val infos = login.lines

                when {
                    preferred != null -> finishEnrol(
                        label = label,
                        umUsername = umUsername.trim(),
                        umPassword = umPassword,
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = login.token,
                        did = preferred,
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                    // Exactly one unused DID → bind it (second enrol on same UM user)
                    unused.size == 1 -> finishEnrol(
                        label = label,
                        umUsername = umUsername.trim(),
                        umPassword = umPassword,
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = login.token,
                        did = unused.first(),
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                    // Multiple DIDs → ask operator which one this line uses
                    allDids.size > 1 -> {
                        val choices = unused.ifEmpty { allDids }
                        _state.update {
                            it.copy(
                                busy = false,
                                status = null,
                                pendingEnrol = PendingEnrol(
                                    label = label,
                                    umUsername = umUsername.trim(),
                                    umPassword = umPassword,
                                    sipExtension = sipExtension.trim(),
                                    sipPassword = sipPassword,
                                    apiBase = base,
                                    sipDomain = domain,
                                    token = login.token,
                                    dids = choices,
                                    lineInfos = infos,
                                    maxExtensions = maxExt,
                                ),
                            )
                        }
                    }
                    else -> finishEnrol(
                        label = label,
                        umUsername = umUsername.trim(),
                        umPassword = umPassword,
                        sipExtension = sipExtension.trim(),
                        sipPassword = sipPassword,
                        apiBase = base,
                        sipDomain = domain,
                        token = login.token,
                        did = unused.firstOrNull() ?: allDids.first(),
                        lineCountHint = allDids.size,
                        lineInfos = infos,
                        maxExtensions = maxExt,
                    )
                }
            } catch (e: Exception) {
                val offline = e is java.net.UnknownHostException ||
                    e is java.io.IOException && e.message?.contains("Unable to resolve", true) == true
                _state.update {
                    it.copy(
                        busy = false,
                        offline = offline,
                        error = e.message ?: "add_failed",
                        status = null,
                    )
                }
            }
        }
    }

    fun confirmPendingEnrol(did: String) {
        val pending = _state.value.pendingEnrol ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                finishEnrol(
                    label = pending.label,
                    umUsername = pending.umUsername,
                    umPassword = pending.umPassword,
                    sipExtension = pending.sipExtension,
                    sipPassword = pending.sipPassword,
                    apiBase = pending.apiBase,
                    sipDomain = pending.sipDomain,
                    token = pending.token,
                    did = normalizeDidDigits(did),
                    lineCountHint = pending.dids.size,
                    lineInfos = pending.lineInfos,
                    maxExtensions = pending.maxExtensions,
                )
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = e.message ?: "enrol_failed", status = null)
                }
            }
        }
    }

    fun cancelPendingEnrol() {
        _state.update { it.copy(pendingEnrol = null, busy = false) }
    }

    private suspend fun finishEnrol(
        label: String,
        umUsername: String,
        umPassword: String,
        sipExtension: String,
        sipPassword: String,
        apiBase: String,
        sipDomain: String = "",
        token: String,
        did: String,
        lineCountHint: Int,
        lineInfos: List<LineInfo> = emptyList(),
        @Suppress("UNUSED_PARAMETER") maxExtensions: Int = 2,
    ) {
        val normalized = normalizeDidDigits(did)
        val info = lineInfos.firstOrNull { normalizeDidDigits(it.did) == normalized }
        val caps = info?.capabilities ?: LineCapabilities()
        val extFromServer = info?.extension?.takeIf { it.isNotBlank() }.orEmpty()
        val account = SoftphoneAccount(
            label = label.ifBlank { umUsername },
            umUsername = umUsername,
            umPassword = umPassword,
            did = normalized,
            sipExtension = sipExtension.ifBlank { extFromServer },
            sipPassword = sipPassword,
            sipDomain = resolveSipDomain(sipDomain),
            apiBaseUrl = apiBase,
            capVoice = caps.voice,
            capSms = caps.sms,
            capMms = caps.mms,
            capDnd = caps.dnd,
        )
        store.upsert(account)
        store.saveToken(account.id, token)
        store.clearPendingEnrolToken()
        if (linePrefs.activeLineId() == null) {
            linePrefs.setActiveLineId(account.id)
        }
        if (linePrefs.ringtoneUri(account.id) == null) {
            linePrefs.setRingtoneUri(account.id, "default")
        }
        refreshAccounts()
        // Token enrol often omits SIP secret — pull from FreePBX over HTTPS bearer,
        // else prompt once so voice is not stuck on a toast.
        var needsSipPrompt = account.sipPassword.isBlank()
        if (needsSipPrompt) {
            needsSipPrompt = !fetchAndStoreSipCredentials(account.id)
        }
        _state.update {
            it.copy(
                busy = false,
                pendingEnrol = null,
                pendingEnrolToken = null,
                pendingSipSecretAccountId = if (needsSipPrompt) account.id else null,
                status = "Added ${account.label} · DID ${formatDidForDisplay(account.did)}" +
                    if (lineCountHint > 1) " (${lineCountHint} DIDs on UM user)" else "",
            )
        }
    }

    /**
     * Persist SIP extension/secret from Settings → Lines (or post-enrol dialog).
     * Reconfigures PJSIP so Calling-from uses the stored secret.
     */
    fun saveSipCredentials(accountId: String, extension: String, secret: String) {
        val acc = store.list().firstOrNull { it.id == accountId } ?: return
        val ext = extension.trim().ifBlank { acc.sipExtension }
        val pass = secret.trim()
        if (pass.isBlank()) {
            _state.update { it.copy(error = "SIP secret cannot be empty") }
            return
        }
        store.upsert(acc.copy(sipExtension = ext, sipPassword = pass))
        refreshAccounts()
        _state.update {
            it.copy(
                pendingSipSecretAccountId = null,
                status = "SIP secret saved for ${acc.label} — you can place calls",
                error = null,
            )
        }
    }

    fun dismissSipSecretPrompt() {
        _state.update { it.copy(pendingSipSecretAccountId = null) }
    }

    fun requestSipSecretPrompt(accountId: String) {
        _state.update { it.copy(pendingSipSecretAccountId = accountId) }
    }

    /** Pull FreePBX device secret over authenticated BFF token; save encrypted. */
    suspend fun fetchAndStoreSipCredentials(accountId: String): Boolean {
        val account = store.list().firstOrNull { it.id == accountId } ?: return false
        return try {
            val api = SoftphoneApi.forAccount(account)
            val token = tokenFor(account)
            val creds = api.sipCredentials(token, account.did)
            val secret = creds.secret?.takeIf { it.isNotBlank() } ?: return false
            val ext = creds.extension?.takeIf { it.isNotBlank() } ?: account.sipExtension
            store.upsert(account.copy(sipExtension = ext, sipPassword = secret))
            refreshAccounts()
            _state.update {
                it.copy(
                    pendingSipSecretAccountId = null,
                    status = "SIP credentials synced for ${account.label}",
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun syncSipCredentials(accountId: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val ok = fetchAndStoreSipCredentials(accountId)
            _state.update {
                it.copy(
                    busy = false,
                    error = if (ok) null else "Could not fetch SIP secret — enter it manually",
                    pendingSipSecretAccountId = if (ok) null else accountId,
                )
            }
        }
    }

    fun setDid(accountId: String, did: String) {
        val acc = store.list().firstOrNull { it.id == accountId } ?: return
        store.upsert(acc.copy(did = normalizeDidDigits(did)))
        refreshAccounts()
        refreshCapabilities(accountId)
        _state.update { it.copy(status = "DID updated to ${formatDidForDisplay(did)}") }
    }

    fun loadAvailableDids(accountId: String) {
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            try {
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                val resp = api.linesResponse(token)
                val dids = resp.lines.map { normalizeDidDigits(it.did) }.filter { it.isNotBlank() }
                applyCapabilities(accountId, resp.lines)
                _state.update { it.copy(availableDidsForLine = dids, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(availableDidsForLine = emptyList(), error = e.message ?: "lines_failed")
                }
            }
        }
    }

    fun refreshCapabilities(accountId: String = _state.value.activeLineId.orEmpty()) {
        if (accountId.isBlank()) return
        viewModelScope.launch { refreshCapabilitiesSuspend(accountId) }
    }

    /** Refresh capability/DND flags for every enrolled line (Lines home). */
    fun refreshAllLineCapabilities() {
        viewModelScope.launch {
            store.list().forEach { acc ->
                refreshCapabilitiesSuspend(acc.id)
            }
        }
    }

    /**
     * Toggle FreePBX Do Not Disturb for this line's extension.
     * Writes AstDB DND/ext + Custom:DNDext via BFF (same as star-78 / star-76).
     */
    fun setDnd(accountId: String, enabled: Boolean) {
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                val resp = api.setDnd(token, account.did, enabled)
                if (!resp.ok) {
                    throw ApiException(resp.error ?: "dnd_set_failed", 502)
                }
                store.upsert(account.copy(capDnd = resp.enabled))
                refreshAccounts()
                // Re-read lines so multi-DID users stay consistent with PBX.
                applyCapabilities(accountId, api.linesResponse(token).lines)
                val label = if (resp.enabled) "Do not disturb" else "Available"
                _state.update {
                    it.copy(
                        busy = false,
                        status = "$label · ext ${resp.extension ?: account.sipExtension} (PBX)",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = e.message ?: "Could not update DND on PBX",
                    )
                }
            }
        }
    }

    private suspend fun refreshCapabilitiesSuspend(accountId: String) {
        val account = store.list().firstOrNull { it.id == accountId } ?: return
        try {
            val api = SoftphoneApi.forAccount(account)
            val token = tokenFor(account)
            applyCapabilities(accountId, api.linesResponse(token).lines)
        } catch (_: Exception) {
            // keep cached flags
        }
    }

    private fun applyCapabilities(accountId: String, lines: List<LineInfo>) {
        val acc = store.list().firstOrNull { it.id == accountId } ?: return
        val info = lines.firstOrNull { normalizeDidDigits(it.did) == normalizeDidDigits(acc.did) }
            ?: return
        val caps = info.capabilities ?: LineCapabilities()
        val ext = info.extension?.takeIf { it.isNotBlank() }
        store.upsert(
            acc.copy(
                capVoice = caps.voice,
                capSms = caps.sms,
                capMms = caps.mms,
                capDnd = caps.dnd,
                sipExtension = acc.sipExtension.ifBlank { ext.orEmpty() },
            ),
        )
        refreshAccounts()
    }

    fun deleteAccount(accountId: String) {
        store.delete(accountId)
        linePrefs.clearAccount(accountId)
        hiddenInbox.clearAccount(accountId)
        refreshAccounts()
    }

    fun ringtoneUri(accountId: String): String? = linePrefs.ringtoneUri(accountId)

    fun setRingtone(accountId: String, optionId: String?) {
        linePrefs.setRingtoneUri(accountId, optionId)
        // Bump status so observers refresh line detail labels immediately
        _state.update { it.copy(status = "Ringtone · ${optionId ?: "default"}") }
    }

    suspend fun tokenFor(account: SoftphoneAccount): String {
        store.token(account.id)?.let { return it }
        if (account.umPassword.isBlank()) {
            throw ApiException(
                "session_expired — re-open your HTTPS enrol link or sign in with User Manager",
                401,
            )
        }
        val api = SoftphoneApi.forAccount(account)
        val login = api.login(account.umUsername, account.umPassword)
        store.saveToken(account.id, login.token)
        if (account.did.isBlank() && login.lines.isNotEmpty()) {
            val used = store.list().filterNot { it.id == account.id }.map { normalizeDidDigits(it.did) }.toSet()
            val pick = login.lines.map { normalizeDidDigits(it.did) }
                .firstOrNull { it !in used }
                ?: login.lines.first().did
            store.upsert(account.copy(did = normalizeDidDigits(pick)))
            refreshAccounts()
        }
        return login.token
    }

    fun loadThreads(accountId: String = _state.value.messagesLineId.orEmpty()) {
        if (accountId.isBlank()) return
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            refreshCapabilitiesSuspend(accountId)
            _state.update { it.copy(busy = true, error = null, offline = false) }
            try {
                val api = SoftphoneApi.forAccount(account)
                var token = tokenFor(account)
                val threads = try {
                    api.threads(token, account.did).threads
                } catch (e: ApiException) {
                    if (e.httpCode == 401) {
                        store.clearToken(account.id)
                        token = tokenFor(account)
                        api.threads(token, account.did).threads
                    } else throw e
                }
                // Viewing SMS must not change the default/outbound SIP line.
                // Aggregate unread across lines is owned by SoftphoneApp's poller;
                // thread rows carry per-conversation unread for in-list dots.
                val hidden = hiddenInbox.hiddenPeers(accountId)
                val visible = if (hidden.isEmpty()) {
                    threads
                } else {
                    threads.filterNot { normalizeDidDigits(it.peer) in hidden }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        threads = visible,
                        status = null,
                        sipSummary = sipEngine.registrationSummary(),
                    )
                }
            } catch (e: Exception) {
                val offline = e is java.io.IOException
                _state.update {
                    it.copy(busy = false, offline = offline, error = e.message ?: "threads_failed")
                }
            }
        }
    }

    fun loadMessages(accountId: String, peer: String) {
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            _state.update { it.copy(busy = true, error = null, messages = emptyList()) }
            try {
                val api = SoftphoneApi.forAccount(account)
                var token = tokenFor(account)
                val msgs = try {
                    api.messages(token, account.did, peer)
                } catch (e: ApiException) {
                    if (e.httpCode == 401) {
                        store.clearToken(account.id)
                        token = tokenFor(account)
                        api.messages(token, account.did, peer)
                    } else throw e
                }
                // BFF marks the thread read on GET messages — clear local unread dots.
                // Aggregate tab badge is refreshed by SoftphoneApp's poller.
                val peerNorm = normalizeDidDigits(peer)
                val updatedThreads = _state.value.threads.map { t ->
                    if (normalizeDidDigits(t.peer) == peerNorm) t.copy(unread = 0) else t
                }
                _state.update {
                    it.copy(
                        busy = false,
                        messages = msgs,
                        threads = updatedThreads,
                        status = "${msgs.size} messages",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "messages_failed") }
            }
        }
    }

    fun setInboxUnreadTotal(total: Int) {
        _state.update { it.copy(inboxUnreadTotal = total.coerceAtLeast(0)) }
    }

    /**
     * Mark inbound unread as read for the current Messages line, or every enrolled
     * SMS line when [allLines] is true.
     */
    fun markAllRead(allLines: Boolean = false) {
        viewModelScope.launch {
            val targets = if (allLines) {
                store.list().filter { it.capSms && it.did.isNotBlank() }
            } else {
                listOfNotNull(
                    store.list().firstOrNull { it.id == _state.value.messagesLineId },
                )
            }
            if (targets.isEmpty()) return@launch
            _state.update { it.copy(busy = true, error = null) }
            var marked = 0
            try {
                for (account in targets) {
                    val api = SoftphoneApi.forAccount(account)
                    val token = tokenFor(account)
                    marked += api.markDidRead(token, account.did).marked
                }
                val viewId = _state.value.messagesLineId
                if (!viewId.isNullOrBlank()) {
                    val cleared = _state.value.threads.map { it.copy(unread = 0) }
                    _state.update {
                        it.copy(
                            busy = false,
                            threads = cleared,
                            inboxUnreadTotal = if (allLines) 0 else it.inboxUnreadTotal,
                            status = if (allLines) {
                                "Marked all lines read ($marked)"
                            } else {
                                "Marked this line read ($marked)"
                            },
                        )
                    }
                } else {
                    _state.update {
                        it.copy(busy = false, status = "Marked read ($marked)")
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, error = e.message ?: "mark_read_failed")
                }
            }
        }
    }

    /**
     * Delete a conversation on the PBX. Falls back to local soft-hide with a clear
     * status when server delete is unavailable.
     */
    fun deleteThread(accountId: String, peer: String, threadId: String? = null, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            _state.update { it.copy(busy = true, error = null) }
            val peerNorm = normalizeDidDigits(peer)
            try {
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                val result = api.deleteThread(token, account.did, peerNorm, threadId)
                if (!result.ok) throw ApiException(result.error ?: "delete_failed", 502)
                _state.update {
                    it.copy(
                        busy = false,
                        threads = it.threads.filterNot { t -> normalizeDidDigits(t.peer) == peerNorm },
                        messages = emptyList(),
                        status = "Conversation deleted",
                    )
                }
                onDone?.invoke()
            } catch (e: Exception) {
                hiddenInbox.hide(accountId, peerNorm)
                _state.update {
                    it.copy(
                        busy = false,
                        threads = it.threads.filterNot { t -> normalizeDidDigits(t.peer) == peerNorm },
                        messages = emptyList(),
                        status = "Hidden on this device (server delete unavailable)",
                        error = null,
                    )
                }
                onDone?.invoke()
            }
        }
    }

    fun deleteMessage(accountId: String, @Suppress("UNUSED_PARAMETER") peer: String, message: ChatMessage) {
        viewModelScope.launch {
            val messageId = message.id
            fun matches(m: ChatMessage): Boolean =
                when {
                    messageId != null && messageId > 0 -> m.id == messageId
                    !message.emid.isNullOrBlank() -> m.emid == message.emid
                    else -> m === message || (
                        m.body == message.body &&
                            m.timestamp == message.timestamp &&
                            m.direction == message.direction
                        )
                }

            if (messageId == null || messageId <= 0) {
                _state.update {
                    it.copy(
                        messages = it.messages.filterNot(::matches),
                        status = "Message hidden on this device (no server id)",
                    )
                }
                return@launch
            }
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                val result = api.deleteMessage(token, account.did, messageId)
                if (!result.ok) throw ApiException(result.error ?: "delete_failed", 502)
                _state.update {
                    it.copy(
                        busy = false,
                        messages = it.messages.filterNot(::matches),
                        status = "Message deleted",
                    )
                }
            } catch (e: Exception) {
                // Soft-hide single message locally when server delete fails.
                _state.update {
                    it.copy(
                        busy = false,
                        messages = it.messages.filterNot(::matches),
                        status = "Message hidden on this device (server delete unavailable)",
                        error = null,
                    )
                }
            }
        }
    }

    fun sendSms(accountId: String, peer: String, body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            if (!account.capSms) {
                _state.update { it.copy(error = "SMS is disabled for this line") }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                api.sendSms(token, account.did, peer, body.trim())
                loadMessages(accountId, peer)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "send_failed") }
            }
        }
    }

    fun sendMms(accountId: String, peer: String, contentUri: Uri, contentResolver: android.content.ContentResolver) {
        viewModelScope.launch {
            val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
            if (!account.capMms) {
                _state.update { it.copy(error = "MMS is disabled for this line") }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            try {
                val tmp = withContext(Dispatchers.IO) {
                    val name = "mms_${System.currentTimeMillis()}.bin"
                    val file = File(cacheDir, name)
                    contentResolver.openInputStream(contentUri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    } ?: throw ApiException("cannot_read_attachment", 400)
                    file
                }
                val maxBytes = 1_000_000L
                if (tmp.length() > maxBytes) {
                    tmp.delete()
                    _state.update {
                        it.copy(busy = false, error = "Attachment too large (max ~1 MB). Resize and try again.")
                    }
                    return@launch
                }
                val api = SoftphoneApi.forAccount(account)
                val token = tokenFor(account)
                api.sendMms(token, account.did, peer, tmp)
                tmp.delete()
                loadMessages(accountId, peer)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "send_media_failed") }
            }
        }
    }

    fun loadDeviceContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = contactsRepo.loadContacts()
                _state.update {
                    it.copy(contacts = list, contactsPermissionDenied = false)
                }
            } catch (_: SecurityException) {
                _state.update { it.copy(contacts = emptyList(), contactsPermissionDenied = true) }
            }
        }
    }

    fun markContactsPermissionDenied() {
        _state.update { it.copy(contactsPermissionDenied = true, contacts = emptyList()) }
    }

    fun mediaUrl(account: SoftphoneAccount, name: String): String =
        SoftphoneApi.forAccount(account).mediaUrl(name)

    fun tokenHeader(accountId: String): Pair<String, String>? {
        val t = store.token(accountId) ?: return null
        return SoftphoneApi.TOKEN_HEADER to t
    }

    companion object {
        fun factory(
            store: AccountStore,
            linePrefs: LinePrefs,
            hiddenInbox: HiddenInboxStore,
            contacts: DeviceContactsRepository,
            callHistory: CallHistoryStore,
            sip: SipEngine,
            cacheDir: File,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SoftphoneViewModel(
                    store,
                    linePrefs,
                    hiddenInbox,
                    contacts,
                    callHistory,
                    sip,
                    cacheDir,
                ) as T
            }
        }
    }
}
