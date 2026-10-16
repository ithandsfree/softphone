package net.ithandsfree.softphone.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.ithandsfree.softphone.diag.CrashLog

/**
 * Opens an encrypted preferences file. Some phones (seen on Huawei / Android 10 key stores) cannot open a file
 * whose key was lost or is unusable; that used to crash the app on start. Then the file is reset and opened again
 * (the person sets the line up again), and the reason is kept for the crash report instead of closing the app.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"

    fun open(context: Context, name: String): SharedPreferences =
        try {
            create(context, name)
        } catch (e: Exception) {
            Log.w(TAG, "$name could not be opened; resetting it: ${e.message}")
            CrashLog.recordNonFatal(context, "SecurePrefs.open($name) failed, file reset", e)
            context.deleteSharedPreferences(name)
            create(context, name)
        }

    private fun create(context: Context, name: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
