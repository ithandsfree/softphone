package net.ithandsfree.softphone.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-line preferences: default extension + ringtone URI.
 *
 * [activeLineId] is the persisted **default extension** — seeds Messages and
 * Calls Calling-from on app open. Keypad Calling-from and Messages chips are
 * session state in the ViewModel and must not rewrite this preference.
 */
class LinePrefs(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ihf_softphone_line_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Default extension id (app-open seed). Legacy key name kept. */
    fun activeLineId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveLineId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun defaultLineId(): String? = activeLineId()

    fun setDefaultLineId(id: String?) = setActiveLineId(id)

    fun ringtoneUri(accountId: String): String? = prefs.getString(ringtoneKey(accountId), null)

    fun setRingtoneUri(accountId: String, uri: String?) {
        prefs.edit().putString(ringtoneKey(accountId), uri).apply()
    }

    fun clearAccount(accountId: String) {
        prefs.edit().remove(ringtoneKey(accountId)).apply()
        if (activeLineId() == accountId) {
            setActiveLineId(null)
        }
    }

    private fun ringtoneKey(accountId: String) = "ringtone_$accountId"

    companion object {
        private const val KEY_ACTIVE = "active_line_id"
    }
}

data class RingtoneOption(
    val id: String,
    val title: String,
    val uri: Uri?,
)

object RingtoneCatalog {
    /** Built-in choices plus device ringtone list (capped). */
    fun options(context: Context, limit: Int = 24): List<RingtoneOption> {
        val out = mutableListOf(
            RingtoneOption("silent", "Silent", null),
            RingtoneOption(
                "default",
                "System default",
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            ),
        )
        val mgr = RingtoneManager(context)
        mgr.setType(RingtoneManager.TYPE_RINGTONE)
        val cursor = mgr.cursor
        var i = 0
        while (cursor.moveToNext() && i < limit) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "Tone ${i + 1}"
            val uri = mgr.getRingtoneUri(cursor.position)
            out += RingtoneOption(id = uri.toString(), title = title, uri = uri)
            i++
        }
        return out.distinctBy { it.id }
    }

    fun titleFor(context: Context, stored: String?): String {
        if (stored.isNullOrBlank()) return "System default"
        if (stored == "silent") return "Silent"
        return options(context).firstOrNull { it.id == stored }?.title
            ?: runCatching {
                RingtoneManager.getRingtone(context, Uri.parse(stored))?.getTitle(context)
            }.getOrNull()
            ?: "Custom"
    }
}
