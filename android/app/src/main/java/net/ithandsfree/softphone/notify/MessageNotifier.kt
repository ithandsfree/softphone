package net.ithandsfree.softphone.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.ithandsfree.softphone.MainActivity
import net.ithandsfree.softphone.data.ThreadInfo

/**
 * System SMS/MMS alerts via [NotificationCompat]. FCM is not wired in this build;
 * [MessageSyncService] (~30s FGS) + [SmsPollWorker] (15 min / expedited kick)
 * cover background delivery until push exists.
 */
object MessageNotifier {
    const val CHANNEL_ID = "ihf_sms_messages"
    private const val SUMMARY_ID = 42001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        // Upgrade older DEFAULT installs so background alerts still heads-up.
        if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            return
        }
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New SMS and MMS"
                enableVibration(true)
            },
        )
    }

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Post one notification per newly-unread inbound thread. Dedupes against
     * [NotifyPrefs] fingerprints so polling does not spam.
     */
    fun notifyNewThreads(
        context: Context,
        lineLabel: String,
        threads: List<ThreadInfo>,
        resolveName: (peer: String) -> String,
    ) {
        ensureChannel(context)
        if (!canPost(context)) return
        val prefs = NotifyPrefs(context)
        // Unread is inbound-only from the BFF; last-message direction may be "out".
        val fresh = threads.filter { it.unread > 0 }
            .filter { thread ->
                val key = fingerprint(lineLabel, thread)
                !prefs.wasNotified(key)
            }
        if (fresh.isEmpty()) return

        val nm = NotificationManagerCompat.from(context)
        for (thread in fresh.take(5)) {
            val key = fingerprint(lineLabel, thread)
            val title = resolveName(thread.peer).ifBlank { thread.peer }
            val body = thread.snippet?.trim().orEmpty().ifBlank { "New message" }
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_MESSAGES, true)
                putExtra(MainActivity.EXTRA_OPEN_PEER, thread.peer)
            }
            val pi = PendingIntent.getActivity(
                context,
                key.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText(lineLabel)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(CHANNEL_ID)
                .build()
            runCatching { nm.notify(key.hashCode(), notif) }
            prefs.markNotified(key)
        }

        if (fresh.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("IHF Phone")
                .setContentText("${fresh.size} new messages")
                .setStyle(
                    NotificationCompat.InboxStyle().also { style ->
                        fresh.take(5).forEach { t ->
                            val name = resolveName(t.peer).ifBlank { t.peer }
                            style.addLine("$name: ${t.snippet.orEmpty()}")
                        }
                    },
                )
                .setGroup(CHANNEL_ID)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(SUMMARY_ID, summary) }
        }
    }

    private fun fingerprint(lineLabel: String, thread: ThreadInfo): String =
        listOf(
            lineLabel,
            thread.threadId.orEmpty(),
            thread.peer,
            thread.lastMessageAt.orEmpty(),
            thread.unread.toString(),
        ).joinToString("|")
}
