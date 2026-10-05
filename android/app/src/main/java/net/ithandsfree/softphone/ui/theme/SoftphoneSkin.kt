package net.ithandsfree.softphone.ui.theme

import androidx.compose.runtime.Immutable

/**
 * Named visual skin packs for the softphone.
 *
 * Skins are design-token packs (colours + brand copy), not forked apps.
 * Add a new pack here + a matching CSS block in `softphone/skins-preview/` —
 * screens keep reading [IhfThemeAccess] / [LocalIhfColors] and need no edits.
 */
enum class SkinId(val id: String) {
    IhfNight("ihf_night"),
    CarbonSignal("carbon_signal"),
    IhfMist("ihf_mist");

    companion object {
        fun fromId(raw: String?): SkinId {
            val key = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == key } ?: IhfNight
        }
    }
}

@Immutable
data class SoftphoneSkin(
    val id: SkinId,
    /** Short label in Settings. */
    val displayName: String,
    /** One-line description under the picker row. */
    val summary: String,
    /** Hero wordmark on splash / branding surfaces. */
    val brandMark: String,
    val productName: String,
    val tagline: String,
    val colors: IhfColors,
    /** Whether Material / system chrome should treat this as a dark surface. */
    val isDark: Boolean,
)

object SoftphoneSkins {
    val IhfNight = SoftphoneSkin(
        id = SkinId.IhfNight,
        displayName = "IHF Night",
        summary = "Navy ground, gold actions — default IHF brand",
        brandMark = "IHF",
        productName = "IHF Phone",
        tagline = "Work from anywhere.",
        colors = IhfColors.Dark,
        isDark = true,
    )

    /**
     * Alternate dark pack for tenant/white-label trials.
     * Charcoal + copper (not purple / cream AI-default palettes).
     */
    val CarbonSignal = SoftphoneSkin(
        id = SkinId.CarbonSignal,
        displayName = "Carbon Signal",
        summary = "Charcoal ground, copper actions — alternate brand pack",
        brandMark = "SIGNAL",
        productName = "Desk",
        tagline = "Lines that stay with you",
        colors = IhfColors.CarbonSignal,
        isDark = true,
    )

    /** Light pack (existing mist tokens) as a selectable skin, not a system-theme flip. */
    val IhfMist = SoftphoneSkin(
        id = SkinId.IhfMist,
        displayName = "IHF Mist",
        summary = "Cool mist surfaces, ink type — light brand pack",
        brandMark = "IHF",
        productName = "IHF Phone",
        tagline = "Work from anywhere.",
        colors = IhfColors.Light,
        isDark = false,
    )

    val all: List<SoftphoneSkin> = listOf(IhfNight, CarbonSignal, IhfMist)

    fun resolve(id: String?): SoftphoneSkin = when (SkinId.fromId(id)) {
        SkinId.IhfNight -> IhfNight
        SkinId.CarbonSignal -> CarbonSignal
        SkinId.IhfMist -> IhfMist
    }

    fun resolve(id: SkinId): SoftphoneSkin = resolve(id.id)
}
