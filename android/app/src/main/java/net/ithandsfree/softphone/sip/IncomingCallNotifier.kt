package net.ithandsfree.softphone.sip

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
import net.ithandsfree.softphone.MainActivity
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.data.formatDidForDisplay

/**
 * High-priority full-screen incoming-call notification for when the app is
 * backgrounded / the screen is locked. Android 10+ blocks background Activity
 * starts — [NotificationCompat.Builder.setFullScreenIntent] is the supported path.
 */
object IncomingCallNotifier {
    const val CHANNEL_ID = "ihf_incoming_calls"
    const val NOTIF_ID = 42020

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Full-screen ring for softphone INVITEs"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                setSound(Settings.System.DEFAULT_RINGTONE_URI, attrs)
                setBypassDnd(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun show(
        context: Context,
        remoteUri: String,
        lineLabel: String,
    ) {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val display = displayRemote(remoteUri)
        val fullScreen = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_INCOMING_CALL, true)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPi = PendingIntent.getActivity(
            context,
            NOTIF_ID + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_INCOMING_CALL, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answerPi = PendingIntent.getActivity(
            context,
            NOTIF_ID + 2,
            Intent(context, MainActivity::class.java).apply {
                // Activity PendingIntent (not broadcast) so Android brings the
                // in-call UI forward after Answer — BroadcastReceiver startActivity
                // is often blocked when the app is backgrounded.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_INCOMING_CALL, true)
                putExtra(MainActivity.EXTRA_ANSWER_CALL, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declinePi = PendingIntent.getBroadcast(
            context,
            NOTIF_ID + 3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(display)
            .setSubText(lineLabel.ifBlank { null })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(90_000L)
            .setSound(Settings.System.DEFAULT_RINGTONE_URI)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .setContentIntent(contentPi)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(
                android.R.drawable.ic_menu_call,
                context.getString(R.string.incoming_call_answer),
                answerPi,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.incoming_call_decline),
                declinePi,
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    fun displayRemote(remoteUri: String): String {
        val bare = remoteUri
            .removePrefix("<")
            .substringAfter(":")
            .substringBefore("@")
            .substringBefore(">")
            .ifBlank { remoteUri }
        return formatDidForDisplay(bare).ifBlank { bare.ifBlank { "Unknown" } }
    }
}
