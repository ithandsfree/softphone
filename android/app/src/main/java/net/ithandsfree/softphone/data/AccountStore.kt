package net.ithandsfree.softphone.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AccountStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ihf_softphone_accounts",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun list(): List<SoftphoneAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<SoftphoneAccount>>(raw) }.getOrDefault(emptyList())
    }

    fun upsert(account: SoftphoneAccount) {
        // Preserve enrolment order. Replacing-then-appending used to shuffle the
        // Messages line rail (and colour slots) every time capabilities refreshed.
        val current = list()
        val idx = current.indexOfFirst { it.id == account.id }
        val next = if (idx < 0) {
            current + account
        } else {
            current.toMutableList().also { it[idx] = account }
        }
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(next)).apply()
    }

    fun delete(id: String) {
        val next = list().filterNot { it.id == id }
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(next)).apply()
        prefs.edit().remove(tokenKey(id)).apply()
    }

    /** Optional enrol bundle token from deep link (Phase 3 BFF exchange). */
    fun savePendingEnrolToken(token: String?) {
        prefs.edit().putString(KEY_ENROL, token).apply()
    }

    fun pendingEnrolToken(): String? = prefs.getString(KEY_ENROL, null)

    fun clearPendingEnrolToken() {
        prefs.edit().remove(KEY_ENROL).apply()
    }

    fun saveToken(accountId: String, token: String) {
        prefs.edit().putString(tokenKey(accountId), token).apply()
    }

    fun token(accountId: String): String? = prefs.getString(tokenKey(accountId), null)

    fun clearToken(accountId: String) {
        prefs.edit().remove(tokenKey(accountId)).apply()
    }

    private fun tokenKey(accountId: String) = "token_$accountId"

    companion object {
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ENROL = "pending_enrol_token"
    }
}
