package net.ithandsfree.softphone.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Round-2 tokens. Dark is v1 default; light is defined for a later flip. */
@Immutable
data class IhfColors(
    val ground: Color,
    val surface: Color,
    val raised: Color,
    val selected: Color,
    val hairline: Color,
    val divider: Color,
    val railActiveBg: Color,
    val gold: Color,
    val goldInk: Color,
    val goldDim: Color,
    val goldTint: Color,
    val emerald: Color,
    val sky: Color,
    val coral: Color,
    val coralInk: Color,
    val text: Color,
    val muted: Color,
    val caption: Color,
    val disabled: Color,
) {
    fun lineSlot(slot: LineColorSlot): Color = when (slot) {
        LineColorSlot.Emerald -> emerald
        LineColorSlot.Sky -> sky
    }

    companion object {
        val Dark = IhfColors(
            ground = Color(0xFF040914),
            surface = Color(0xFF0B132B),
            raised = Color(0xFF142040),
            selected = Color(0xFF1F3B7D),
            hairline = Color(0xFF233052),
            divider = Color(0xFF1A2644),
            railActiveBg = Color(0xFF0F2238),
            gold = Color(0xFFD4AF37),
            goldInk = Color(0xFF0B132B),
            goldDim = Color(0xFF9A7B1E),
            goldTint = Color(0xFF2A2410),
            emerald = Color(0xFF35C08A),
            sky = Color(0xFF79B4F2),
            coral = Color(0xFFF2836F),
            coralInk = Color(0xFF040914),
            text = Color(0xFFEEF2F8),
            muted = Color(0xFFAAB6CC),
            caption = Color(0xFF8491AD),
            disabled = Color(0xFF6B7896),
        )

        /** Light mist pack — selectable as [SoftphoneSkins.IhfMist]. */
        val Light = IhfColors(
            ground = Color(0xFFF3F5F9),
            surface = Color(0xFFFFFFFF),
            raised = Color(0xFFE6EAF2),
            selected = Color(0xFFDCE6F7),
            hairline = Color(0xFFCBD3E3),
            divider = Color(0xFFE6EAF2),
            railActiveBg = Color(0xFFEAF0FA),
            gold = Color(0xFFB8931F),
            goldInk = Color(0xFF0B132B),
            goldDim = Color(0xFF8A6E14),
            goldTint = Color(0xFFF7F0D6),
            emerald = Color(0xFF1E8F63),
            sky = Color(0xFF2F6FBF),
            coral = Color(0xFFC8452B),
            coralInk = Color(0xFFFFFFFF),
            text = Color(0xFF0B132B),
            muted = Color(0xFF3A4A6E),
            caption = Color(0xFF5B6A8A),
            disabled = Color(0xFF8491AD),
        )

        /**
         * Alternate dark pack for white-label / A-B brand trials.
         * Token names stay the same (`gold` = primary accent) so screens do not fork.
         */
        val CarbonSignal = IhfColors(
            ground = Color(0xFF0A0E12),
            surface = Color(0xFF141A22),
            raised = Color(0xFF1C2530),
            selected = Color(0xFF2A3A48),
            hairline = Color(0xFF2E3A48),
            divider = Color(0xFF1E2833),
            railActiveBg = Color(0xFF162028),
            gold = Color(0xFFC47A3A),
            goldInk = Color(0xFF0A0E12),
            goldDim = Color(0xFF8F5528),
            goldTint = Color(0xFF2A1C12),
            emerald = Color(0xFF3DB8A0),
            sky = Color(0xFF6B9BC3),
            coral = Color(0xFFE07A62),
            coralInk = Color(0xFF0A0E12),
            text = Color(0xFFE8EEF4),
            muted = Color(0xFFA3B0BE),
            caption = Color(0xFF7E8C9C),
            disabled = Color(0xFF5F6C7A),
        )
    }
}

enum class LineColorSlot { Emerald, Sky }
