package net.ithandsfree.softphone.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ithandsfree.softphone.sip.SipRegistrationService

/** Dedupes system notifications; message-sync + SIP keep-alive toggles. */
class NotifyPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _messageSyncEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_MESSAGE_SYNC, true),
    )
    val messageSyncEnabled: StateFlow<Boolean> = _messageSyncEnabled.asStateFlow()

    private val _sipKeepAliveEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SIP_KEEPALIVE, true),
    )
    val sipKeepAliveEnabled: StateFlow<Boolean> = _sipKeepAliveEnabled.asStateFlow()

    fun isMessageSyncEnabled(): Boolean = _messageSyncEnabled.value

    fun setMessageSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGE_SYNC, enabled).apply()
        _messageSyncEnabled.value = enabled
        MessageSyncService.reconcile(appContext)
        if (enabled) {
            SmsPollWorker.schedule(appContext)
            SmsPollWorker.kick(appContext)
        }
    }

    fun isSipKeepAliveEnabled(): Boolean = _sipKeepAliveEnabled.value

    fun setSipKeepAliveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIP_KEEPALIVE, enabled).apply()
        _sipKeepAliveEnabled.value = enabled
        SipRegistrationService.reconcile(appContext)
    }

    /** Last SIP FGS failure reason (cleared on successful startForeground). */
    fun sipFgsFailureReason(): String? =
        prefs.getString(KEY_SIP_FGS_FAIL, null)?.takeIf { it.isNotBlank() }

    fun markSipFgsFailure(reason: String) {
        prefs.edit().putString(KEY_SIP_FGS_FAIL, reason.take(240)).apply()
    }

    fun clearSipFgsFailure() {
        prefs.edit().remove(KEY_SIP_FGS_FAIL).apply()
    }

    fun wasNotified(fingerprint: String): Boolean =
        prefs.getStringSet(KEY_SEEN, emptySet())?.contains(fingerprint) == true

    fun markNotified(fingerprint: String) {
        val cur = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(fingerprint)
        // Bound growth — keep the newest ~200 fingerprints.
        val trimmed = if (cur.size > 200) cur.toList().takeLast(200).toSet() else cur
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    companion object {
        private const val PREFS = "ihf_softphone_notify"
        private const val KEY_SEEN = "notified_fps"
        private const val KEY_MESSAGE_SYNC = "message_sync_enabled"
        private const val KEY_SIP_KEEPALIVE = "sip_keepalive_enabled"
        private const val KEY_SIP_FGS_FAIL = "sip_fgs_failure"
    }
}
