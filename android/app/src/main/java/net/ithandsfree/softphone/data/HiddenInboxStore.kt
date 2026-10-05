package net.ithandsfree.softphone.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local soft-hide for conversations when server delete is unavailable.
 * Hidden peers are filtered from the inbox and labeled in status copy.
 */
class HiddenInboxStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ihf_softphone_hidden_inbox",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun hiddenPeers(accountId: String): Set<String> {
        val raw = prefs.getString(key(accountId), "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(',').map { normalizeDidDigits(it) }.filter { it.isNotBlank() }.toSet()
    }

    fun hide(accountId: String, peer: String) {
        val peerNorm = normalizeDidDigits(peer)
        if (peerNorm.isBlank()) return
        val next = hiddenPeers(accountId) + peerNorm
        prefs.edit().putString(key(accountId), next.joinToString(",")).apply()
    }

    fun unhide(accountId: String, peer: String) {
        val peerNorm = normalizeDidDigits(peer)
        val next = hiddenPeers(accountId) - peerNorm
        prefs.edit().putString(key(accountId), next.joinToString(",")).apply()
    }

    fun clearAccount(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String) = "hidden_$accountId"
}
