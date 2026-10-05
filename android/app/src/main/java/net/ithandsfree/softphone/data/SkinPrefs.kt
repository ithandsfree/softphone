package net.ithandsfree.softphone.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.ui.theme.SkinId
import net.ithandsfree.softphone.ui.theme.SoftphoneSkin
import net.ithandsfree.softphone.ui.theme.SoftphoneSkins

/**
 * Persists the active skin id and whether the user overrode the tenant default.
 *
 * Resolution order:
 * 1. User pick in Settings (source = user)
 * 2. Tenant/BFF branding applied once (source = tenant) when no user override
 * 3. [BuildConfig.DEFAULT_SKIN_ID] (compile-time brand default)
 */
class SkinPrefs(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ihf_softphone_skin_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _skinId = MutableStateFlow(readResolvedId())
    val skinId: StateFlow<String> = _skinId.asStateFlow()

    fun currentSkin(): SoftphoneSkin = SoftphoneSkins.resolve(_skinId.value)

    fun setUserSkin(id: String) {
        val resolved = SkinId.fromId(id).id
        prefs.edit()
            .putString(KEY_SKIN_ID, resolved)
            .putString(KEY_SOURCE, SOURCE_USER)
            .apply()
        _skinId.value = resolved
    }

    /**
     * Apply a tenant/brand default from BFF or build config when the operator
     * has not chosen a skin in Settings.
     */
    fun applyTenantDefault(id: String?): Boolean {
        if (prefs.getString(KEY_SOURCE, null) == SOURCE_USER) return false
        val resolved = SkinId.fromId(id?.ifBlank { null } ?: BuildConfig.DEFAULT_SKIN_ID).id
        prefs.edit()
            .putString(KEY_SKIN_ID, resolved)
            .putString(KEY_SOURCE, SOURCE_TENANT)
            .apply()
        val changed = _skinId.value != resolved
        _skinId.value = resolved
        return changed
    }

    fun source(): String = prefs.getString(KEY_SOURCE, SOURCE_DEFAULT) ?: SOURCE_DEFAULT

    private fun readResolvedId(): String {
        val stored = prefs.getString(KEY_SKIN_ID, null)
        if (!stored.isNullOrBlank()) return SkinId.fromId(stored).id
        return SkinId.fromId(BuildConfig.DEFAULT_SKIN_ID).id
    }

    companion object {
        private const val KEY_SKIN_ID = "skin_id"
        private const val KEY_SOURCE = "skin_source"
        const val SOURCE_USER = "user"
        const val SOURCE_TENANT = "tenant"
        const val SOURCE_DEFAULT = "default"
    }
}
