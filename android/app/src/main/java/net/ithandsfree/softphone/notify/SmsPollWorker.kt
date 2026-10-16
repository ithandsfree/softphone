package net.ithandsfree.softphone.notify

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background SMS inbox poll. Periodic minimum is 15 minutes (WorkManager).
 * [MessageSyncService] covers ~30s while message sync is enabled; this worker
 * is the Doze / process-death backstop. [kick] enqueues an expedited one-shot.
 */
class SmsPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    /**
     * Required for expedited work ([kick]) on Android 11 and older, where WorkManager runs it as a foreground
     * service and asks for this notification. Without it every start crashed with "Not implemented"
     * (a tester's Huawei, Android 10, 2026-10-14). Android 12+ never calls it for expedited work.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        MessageSyncService.ensureSyncChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, MessageSyncService.SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Checking messages")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        MessageNotifier.ensureChannel(applicationContext)
        return try {
            SmsInboxPoller.poll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val FOREGROUND_ID = 42011
        private const val UNIQUE_PERIODIC = "ihf_sms_inbox_poll"
        private const val UNIQUE_KICK = "ihf_sms_inbox_poll_kick"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<SmsPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        /** Immediate / expedited poll (best-effort under quota). */
        fun kick(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<SmsPollWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_KICK,
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}
