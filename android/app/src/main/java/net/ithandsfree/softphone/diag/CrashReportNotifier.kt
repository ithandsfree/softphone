package net.ithandsfree.softphone.diag

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The crash report as a notification: "IHF Phone stopped. Tap to send the report." Tapping opens the share / email
 * sheet, which belongs to another app, so the report can be sent even when IHF Phone keeps closing before its own
 * "Send report" prompt can be used.
 */
object CrashReportNotifier {
    private const val CHANNEL_ID = "ihf_problem_reports"
    private const val NOTIFICATION_ID = 43_001
    private const val MAX_CHARS = 20_000

    fun postIfReport(context: Context) {
        val report = CrashLog.read(context) ?: return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Problem reports", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Lets you send a report to IT Hands Free after IHF Phone stopped"
                },
            )
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "IHF Phone crash report")
            putExtra(Intent.EXTRA_TEXT, report.takeLast(MAX_CHARS))
        }
        val chooser = Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("IHF Phone stopped")
            .setContentText("Tap to send the report to IT Hands Free.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}
