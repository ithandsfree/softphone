package net.ithandsfree.softphone.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class CallDirection {
    Incoming,
    Outgoing,
    Missed,
}

@Serializable
data class CallRecord(
    val id: String = UUID.randomUUID().toString(),
    /** Softphone account / line that handled the call */
    val lineId: String,
    val peerNumber: String,
    val peerDisplayName: String = "",
    val direction: CallDirection,
    /** Epoch millis */
    val startedAt: Long = System.currentTimeMillis(),
    /** Connected talk time; 0 for missed / failed dial */
    val durationSec: Int = 0,
    val answered: Boolean = false,
    /** Optional note e.g. "SIP not registered yet" */
    val note: String = "",
)

/**
 * Local softphone call history. PJSIP callbacks append real events here.
 * Until then, keypad / Call back still write outbound attempts so Recents + detail UX work.
 */
class CallHistoryStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ihf_softphone_call_history",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun list(): List<CallRecord> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<CallRecord>>(raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.startedAt }
    }

    fun get(id: String): CallRecord? = list().firstOrNull { it.id == id }

    fun append(record: CallRecord) {
        val next = (list() + record).sortedByDescending { it.startedAt }.take(MAX)
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    fun delete(id: String) {
        val next = list().filterNot { it.id == id }
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    fun clear() {
        prefs.edit().putString(KEY, "[]").apply()
    }

    companion object {
        private const val KEY = "calls_json"
        private const val MAX = 500
    }
}
