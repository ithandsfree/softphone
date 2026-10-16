package net.ithandsfree.softphone.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android 14+ treats full-screen alerts as special access. Samsung turns the
 * switch off when a sideloaded app is updated, which hides lock-screen calls
 * and messages. The app cannot grant this itself.
 */
object FullScreenAlerts {
    fun applies(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun granted(context: Context): Boolean {
        if (!applies()) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.canUseFullScreenIntent()
    }

    fun settingsIntent(context: Context): Intent {
        val direct = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (direct.resolveActivity(context.packageManager) != null) return direct
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
}
