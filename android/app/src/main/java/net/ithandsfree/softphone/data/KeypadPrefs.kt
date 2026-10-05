package net.ithandsfree.softphone.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dial-pad feedback toggles (sound + haptic). Defaults match common Android
 * phone dialers: tones on, short vibration on.
 */
class KeypadPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _soundEnabled = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _vibrateEnabled = MutableStateFlow(prefs.getBoolean(KEY_VIBRATE, true))
    val vibrateEnabled: StateFlow<Boolean> = _vibrateEnabled.asStateFlow()

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
        _soundEnabled.value = enabled
    }

    fun setVibrateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATE, enabled).apply()
        _vibrateEnabled.value = enabled
    }

    companion object {
        private const val PREFS = "ihf_softphone_keypad"
        private const val KEY_SOUND = "keypad_sound"
        private const val KEY_VIBRATE = "keypad_vibrate"
    }
}
