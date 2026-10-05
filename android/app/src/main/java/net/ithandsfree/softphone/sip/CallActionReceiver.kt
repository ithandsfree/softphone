package net.ithandsfree.softphone.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.ithandsfree.softphone.SoftphoneApp

/**
 * Decline from the incoming-call notification.
 *
 * Answer uses an Activity PendingIntent ([MainActivity] +
 * [net.ithandsfree.softphone.MainActivity.EXTRA_ANSWER_CALL]) so the in-call UI
 * is brought to the foreground — starting an Activity from this receiver while
 * backgrounded is unreliable on modern Android.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? SoftphoneApp ?: return
        when (intent?.action) {
            ACTION_ANSWER -> {
                // Legacy path if an old notification action is still pending.
                runCatching { app.sipEngine.answer() }
                    .onFailure { Log.w(TAG, "answer from notification failed: ${it.message}") }
                IncomingCallNotifier.cancel(context)
                val open = Intent(context, net.ithandsfree.softphone.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(net.ithandsfree.softphone.MainActivity.EXTRA_INCOMING_CALL, true)
                    putExtra(net.ithandsfree.softphone.MainActivity.EXTRA_ANSWER_CALL, true)
                }
                runCatching { context.startActivity(open) }
                    .onFailure { Log.w(TAG, "open in-call UI failed: ${it.message}") }
            }
            ACTION_DECLINE -> {
                runCatching { app.sipEngine.decline() }
                    .onFailure { Log.w(TAG, "decline from notification failed: ${it.message}") }
                IncomingCallNotifier.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "net.ithandsfree.softphone.sip.ANSWER_CALL"
        const val ACTION_DECLINE = "net.ithandsfree.softphone.sip.DECLINE_CALL"
        private const val TAG = "CallActionReceiver"
    }
}
