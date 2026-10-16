package net.ithandsfree.softphone.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.data.AccountStore
import net.ithandsfree.softphone.data.ApiException
import net.ithandsfree.softphone.data.SoftphoneAccount
import net.ithandsfree.softphone.data.SoftphoneApi
import net.ithandsfree.softphone.data.normalizeDidDigits

/**
 * Reply and Mark as read on a message notification, without opening the app. Android Auto (Google Assistant /
 * Gemini) uses these to read a business text aloud, send a spoken reply and mark the conversation read.
 */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        val peer = intent.getStringExtra(EXTRA_PEER) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val reply = if (intent.action == ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        if (intent.action == ACTION_REPLY && reply.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = AccountStore(context.applicationContext)
                val account = store.list().firstOrNull { it.id == accountId } ?: return@launch
                val did = normalizeDidDigits(account.did)
                withToken(store, account) { api, token ->
                    if (reply.isNotEmpty()) api.sendSms(token, did, peer, reply)
                    api.markThreadRead(token, did, peer)
                }
                if (notificationId != 0) NotificationManagerCompat.from(context).cancel(notificationId)
            } catch (e: Exception) {
                Log.w(TAG, "message action failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /** Runs [block] with the line's session, signing in again once when the session has expired. */
    private suspend fun withToken(
        store: AccountStore,
        account: SoftphoneAccount,
        block: suspend (SoftphoneApi, String) -> Unit,
    ) {
        val api = SoftphoneApi.forAccount(account)
        val saved = store.token(account.id)
        if (saved != null) {
            try {
                block(api, saved)
                return
            } catch (e: ApiException) {
                if (e.httpCode != 401) throw e
                store.clearToken(account.id)
            }
        }
        if (account.umPassword.isBlank()) throw ApiException("session_expired", 401)
        val login = api.login(account.umUsername, account.umPassword)
        store.saveToken(account.id, login.token)
        block(api, login.token)
    }

    companion object {
        private const val TAG = "MessageAction"
        const val ACTION_REPLY = "net.ithandsfree.softphone.action.MESSAGE_REPLY"
        const val ACTION_MARK_READ = "net.ithandsfree.softphone.action.MESSAGE_MARK_READ"
        const val KEY_REPLY = "reply_text"
        const val EXTRA_ACCOUNT = "account_id"
        const val EXTRA_PEER = "peer"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
