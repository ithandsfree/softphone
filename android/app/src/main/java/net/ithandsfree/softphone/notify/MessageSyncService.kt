package net.ithandsfree.softphone.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.ithandsfree.softphone.notify.ForegroundStarts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.MainActivity
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.SoftphoneApp
import net.ithandsfree.softphone.data.AccountStore

/**
 * Sticky foreground service that keeps SMS inbox polling alive while the app
 * is minimized. WorkManager alone (15 min minimum) is too slow for chat; the
 * in-process Application coroutine is killed under Doze / OEM killers.
 *
 * Caveat: Samsung / aggressive OEMs may still pause FGS unless the user
 * disables battery optimization for IHF Phone (Settings → Battery).
 */
class MessageSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!startAsForegroundSafe()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensurePolling()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** @return false if FGS promotion failed (caller must stopSelf). */
    private fun startAsForegroundSafe(): Boolean {
        ensureSyncChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.message_sync_title))
            .setContentText(getString(R.string.message_sync_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return ForegroundStarts.promote(
            this,
            SYNC_NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (!NotifyPrefs(this@MessageSyncService).isMessageSyncEnabled()) {
                    stopSelf()
                    break
                }
                runCatching {
                    val result = SmsInboxPoller.poll(this@MessageSyncService)
                    (applicationContext as? SoftphoneApp)?.publishInboxUnread(result.totalUnread)
                }
                delay(POLL_MS)
            }
        }
    }

    companion object {
        const val ACTION_START = "net.ithandsfree.softphone.notify.START_MESSAGE_SYNC"
        const val ACTION_STOP = "net.ithandsfree.softphone.notify.STOP_MESSAGE_SYNC"
        const val SYNC_CHANNEL_ID = "ihf_message_sync"
        private const val SYNC_NOTIF_ID = 42010
        private const val POLL_MS = 30_000L

        fun ensureSyncChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(SYNC_CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    SYNC_CHANNEL_ID,
                    "Message sync",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps SMS/MMS checks running while the app is in the background"
                    setShowBadge(false)
                },
            )
        }

        /** Start or stop the FGS based on prefs + enrolled SMS lines. */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            runCatching {
                val enabled = NotifyPrefs(app).isMessageSyncEnabled()
                val hasSms = AccountStore(app).list().any { it.capSms && it.did.isNotBlank() }
                if (enabled && hasSms) {
                    val i = Intent(app, MessageSyncService::class.java).setAction(ACTION_START)
                    ContextCompat.startForegroundService(app, i)
                } else {
                    app.stopService(
                        Intent(app, MessageSyncService::class.java).setAction(ACTION_STOP),
                    )
                }
            }.onFailure {
                android.util.Log.e("MessageSyncService", "reconcile failed: ${it.message}", it)
            }
        }
    }
}
