package net.ithandsfree.softphone.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import net.ithandsfree.softphone.MainActivity
import net.ithandsfree.softphone.data.ThreadInfo
import net.ithandsfree.softphone.data.normalizeDidDigits

/**
 * System SMS/MMS alerts via [NotificationCompat]. FCM is not wired in this build;
 * [MessageSyncService] (~30s FGS) + [SmsPollWorker] (15 min / expedited kick)
 * cover background delivery until push exists.
 */
object MessageNotifier {
    const val CHANNEL_ID = "ihf_sms_messages_v2"
    private const val LEGACY_CHANNEL_ID = "ihf_sms_messages"
    private const val SUMMARY_ID = 42001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        // The first channel was private, and Android will not change lock-screen
        // visibility after a channel exists. A new id is the only way onto the lock screen.
        mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New SMS and MMS"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, sound)
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
        accountId: String? = null,
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
            val notificationId = messageNotificationId(lineLabel, thread.peer)
            // Chat style plus Reply and Mark as read: the form Android Auto reads aloud and lets the driver answer
            // by voice. Both actions run in MessageActionReceiver without opening the app.
            val sender = Person.Builder().setName(title).setKey(normalizeDidDigits(thread.peer)).build()
            val style = NotificationCompat.MessagingStyle(Person.Builder().setName(lineLabel.ifBlank { "Me" }).build())
                .addMessage(body, System.currentTimeMillis(), sender)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText(lineLabel)
                .setStyle(style)
                .setContentIntent(pi)
            if (accountId != null) {
                builder.addAction(replyAction(context, accountId, thread.peer, notificationId))
                builder.addInvisibleAction(markReadAction(context, accountId, thread.peer, notificationId))
            }
            val notif = builder
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(CHANNEL_ID)
                .build()
            runCatching { nm.notify(notificationId, notif) }
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
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(SUMMARY_ID, summary) }
        }
    }

    /**
     * Drop the home-screen badge for one thread. Samsung counts the high-importance
     * message notification, and reading the thread in the app does not tap it.
     * Older alerts used a changing id, so active notifications are matched too.
     */
    fun cancelThread(context: Context, lineLabel: String, peer: String) {
        val nm = manager(context) ?: return
        nm.cancel(messageNotificationId(lineLabel, peer))
        val onLine = activeMessages(nm).filter { belongsToLine(it, lineLabel) }
        // Only alerts for this person. Until 0.4.24, when none matched and the line had exactly one alert, that alert
        // was removed: every check walks the read conversations too, so a new text's alert (and the app-icon badge)
        // vanished within seconds of being posted.
        val targets = onLine.filter { samePeer(it.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(), peer) }
        for (sbn in targets) {
            nm.cancel(sbn.tag, sbn.id)
        }
        cancelSummaryIfIdle(nm)
    }

    /** Clear every message alert for one line, including the group summary when nothing remains. */
    fun cancelLine(context: Context, lineLabel: String) {
        val nm = manager(context) ?: return
        for (sbn in activeMessages(nm)) {
            if (belongsToLine(sbn, lineLabel)) nm.cancel(sbn.tag, sbn.id)
        }
        cancelSummaryIfIdle(nm)
    }

    fun cancelAll(context: Context) {
        val nm = manager(context) ?: return
        for (sbn in activeMessages(nm)) {
            nm.cancel(sbn.tag, sbn.id)
        }
        nm.cancel(SUMMARY_ID)
    }

    /** Cancel alerts whose threads are no longer unread. */
    fun dismissRead(context: Context, lineLabel: String, threads: List<ThreadInfo>) {
        if (threads.none { it.unread > 0 }) {
            cancelLine(context, lineLabel)
            return
        }
        for (thread in threads) {
            if (thread.unread <= 0) cancelThread(context, lineLabel, thread.peer)
        }
    }

    private fun manager(context: Context): NotificationManager? =
        context.applicationContext.getSystemService(NotificationManager::class.java)

    private fun activeMessages(nm: NotificationManager) =
        nm.activeNotifications.filter { status ->
            status.id == SUMMARY_ID || status.notification.channelId == CHANNEL_ID
        }

    private fun belongsToLine(status: android.service.notification.StatusBarNotification, lineLabel: String): Boolean {
        if (status.id == SUMMARY_ID) return false
        if (lineLabel.isBlank()) return true
        val sub = status.notification.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        return sub.isBlank() || sub == lineLabel
    }

    private fun samePeer(title: String?, peer: String): Boolean {
        if (title.isNullOrBlank()) return false
        if (title.equals(peer, ignoreCase = true)) return true
        val titleDigits = normalizeDidDigits(title)
        val peerDigits = normalizeDidDigits(peer)
        return titleDigits.isNotBlank() && titleDigits == peerDigits
    }

    private fun cancelSummaryIfIdle(nm: NotificationManager) {
        val children = nm.activeNotifications.any { status ->
            status.id != SUMMARY_ID && status.notification.channelId == CHANNEL_ID
        }
        if (!children) nm.cancel(SUMMARY_ID)
    }

    private fun actionIntent(context: Context, action: String, accountId: String, peer: String, notificationId: Int) =
        Intent(context, MessageActionReceiver::class.java).apply {
            this.action = action
            putExtra(MessageActionReceiver.EXTRA_ACCOUNT, accountId)
            putExtra(MessageActionReceiver.EXTRA_PEER, peer)
            putExtra(MessageActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }

    /** Voice or typed reply, sent as a text from the same line. RemoteInput needs a mutable PendingIntent. */
    private fun replyAction(context: Context, accountId: String, peer: String, notificationId: Int): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId,
            actionIntent(context, MessageActionReceiver.ACTION_REPLY, accountId, peer, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(MessageActionReceiver.KEY_REPLY).setLabel("Reply").build()
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pi)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(context: Context, accountId: String, peer: String, notificationId: Int): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            actionIntent(context, MessageActionReceiver.ACTION_MARK_READ, accountId, peer, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, "Mark as read", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** Stable id so reading a thread can cancel the alert that posted the badge. */
    fun messageNotificationId(lineLabel: String, peer: String): Int {
        val key = lineLabel.trim() + "|" + normalizeDidDigits(peer)
        var id = key.hashCode() and 0x7fffffff
        if (id == 0 || id == SUMMARY_ID) id = SUMMARY_ID + 1
        return id
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
