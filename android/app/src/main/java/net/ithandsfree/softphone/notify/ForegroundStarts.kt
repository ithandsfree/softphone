package net.ithandsfree.softphone.notify

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log

/**
 * Promote a started service to foreground **without violating the
 * startForegroundService() contract**.
 *
 * On API 26+, if [android.content.Context.startForegroundService] is used and
 * [Service.startForeground] is never called, Android kills the process with
 * `RemoteServiceException` ("did not then call Service.startForeground").
 * Catching a typed `startForeground(phoneCall)` failure and `stopSelf()` is
 * therefore still a crash — v0.3.5/0.3.6 after enrol.
 *
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL] also needs a Telecom
 * [android.telecom.ConnectionService] on API 34+, which this app does not
 * implement. Prefer [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC].
 */
object ForegroundStarts {
    private const val TAG = "ForegroundStarts"

    /**
     * @return true if the service is now in the foreground.
     */
    fun promote(
        service: Service,
        notificationId: Int,
        notification: Notification,
        preferredType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = linkedSetOf(
                preferredType,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            for (type in types) {
                try {
                    service.startForeground(notificationId, notification, type)
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "startForeground(type=$type) failed: ${t.message}")
                }
            }
        }
        return try {
            @Suppress("DEPRECATION")
            service.startForeground(notificationId, notification)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground (untyped) failed: ${t.message}", t)
            false
        }
    }
}
