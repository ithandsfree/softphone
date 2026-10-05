package net.ithandsfree.softphone.sip

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import net.ithandsfree.softphone.notify.ForegroundStarts
import net.ithandsfree.softphone.notify.NotifyPrefs

/**
 * Sticky phoneCall foreground service that keeps PJSIP REGISTER alive while
 * the app is backgrounded. Separate from [net.ithandsfree.softphone.notify.MessageSyncService]
 * (dataSync) so SMS polling and SIP reachability can be toggled independently.
 *
 * Uses [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]
 * (not phoneCall). API 34 `phoneCall` FGS requires a Telecom ConnectionService
 * or default-dialer role — without it, startForeground throws and, if we then
 * skip startForeground entirely, Android kills the process
 * (`RemoteServiceException` after enrol on v0.3.5/0.3.6).
 *
 * OEM caveat: Samsung / aggressive battery savers may still suspend SIP unless
 * the user allows unrestricted battery for IHF Phone.
 */
class SipRegistrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REREGISTER -> {
                if (!startAsForegroundSafe()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                reregisterNow()
                ensureLoop()
            }
            else -> {
                if (!startAsForegroundSafe()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** @return false if FGS promotion failed (caller must stopSelf). */
    private fun startAsForegroundSafe(): Boolean {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = (applicationContext as? SoftphoneApp)
            ?.sipEngine
            ?.registrationSummary()
            .orEmpty()
            .ifBlank { getString(R.string.sip_reg_body) }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(getString(R.string.sip_reg_title))
            .setContentText(summary)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val ok = ForegroundStarts.promote(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (ok) {
            NotifyPrefs(this).clearSipFgsFailure()
        } else {
            NotifyPrefs(this).markSipFgsFailure("startForeground failed")
        }
        return ok
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                if (!NotifyPrefs(this@SipRegistrationService).isSipKeepAliveEnabled()) {
                    stopSelf()
                    break
                }
                val app = applicationContext as? SoftphoneApp
                if (app == null) {
                    delay(TICK_MS)
                    continue
                }
                val accounts = runCatching { app.accountStore.list() }.getOrDefault(emptyList())
                val hasSip = accounts.any {
                    it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank()
                }
                if (!hasSip) {
                    stopSelf()
                    break
                }
                runCatching {
                    app.sipEngine.configureAccounts(accounts)
                    if (!app.sipEngine.anyLineRegistered()) {
                        app.sipEngine.reregisterAll()
                    }
                    refreshNotification(app.sipEngine.registrationSummary())
                }.onFailure { Log.w(TAG, "sip keep-alive tick failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    private fun reregisterNow() {
        val app = applicationContext as? SoftphoneApp ?: return
        runCatching {
            app.sipEngine.configureAccounts(app.accountStore.list())
            app.sipEngine.reregisterAll()
            refreshNotification(app.sipEngine.registrationSummary())
        }.onFailure { Log.w(TAG, "forced reregister failed: ${it.message}") }
    }

    private fun refreshNotification(summary: String) {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(getString(R.string.sip_reg_title))
            .setContentText(summary.ifBlank { getString(R.string.sip_reg_body) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    companion object {
        const val ACTION_START = "net.ithandsfree.softphone.sip.START_SIP_REG"
        const val ACTION_STOP = "net.ithandsfree.softphone.sip.STOP_SIP_REG"
        const val ACTION_REREGISTER = "net.ithandsfree.softphone.sip.REREGISTER_SIP"
        const val CHANNEL_ID = "ihf_sip_registration"
        private const val NOTIF_ID = 42030
        private const val TICK_MS = 45_000L
        private const val TAG = "SipRegistrationService"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice registration",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the softphone registered for incoming calls"
                    setShowBadge(false)
                },
            )
        }

        /** Start or stop based on prefs + SIP credentials on enrolled lines. */
        fun reconcile(context: Context) {
            val app = context.applicationContext
            runCatching {
                val enabled = NotifyPrefs(app).isSipKeepAliveEnabled()
                val hasSip = AccountStore(app).list().any {
                    it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank()
                }
                if (enabled && hasSip) {
                    val i = Intent(app, SipRegistrationService::class.java).setAction(ACTION_START)
                    ContextCompat.startForegroundService(app, i)
                } else {
                    app.stopService(
                        Intent(app, SipRegistrationService::class.java).setAction(ACTION_STOP),
                    )
                }
            }.onFailure {
                Log.e(TAG, "reconcile failed: ${it.message}", it)
                NotifyPrefs(app).markSipFgsFailure(it.message ?: it.javaClass.simpleName)
            }
        }

        fun requestReregister(context: Context) {
            val app = context.applicationContext
            runCatching {
                if (!NotifyPrefs(app).isSipKeepAliveEnabled()) return
                val hasSip = AccountStore(app).list().any {
                    it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank()
                }
                if (!hasSip) return
                val i = Intent(app, SipRegistrationService::class.java).setAction(ACTION_REREGISTER)
                ContextCompat.startForegroundService(app, i)
            }.onFailure {
                Log.e(TAG, "requestReregister failed: ${it.message}", it)
                NotifyPrefs(app).markSipFgsFailure(it.message ?: it.javaClass.simpleName)
            }
        }
    }
}
