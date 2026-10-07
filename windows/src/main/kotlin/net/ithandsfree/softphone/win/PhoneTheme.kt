package net.ithandsfree.softphone.win

import java.awt.Color

/**
 * Round-3 colour roles. The same screens read these; only the edition changes.
 * GPL-2.0.
 */
data class PhoneTheme(
    val ground: Color,
    val surface: Color,
    val raised: Color,
    val gold: Color,
    val goldInk: Color,
    val ink: Color,
    val muted: Color,
    val caption: Color,
    val emerald: Color,
    val sky: Color,
    val coral: Color,
    val hairline: Color,
    val disabled: Color,
    val headlineLead: String,
    val headlineEmphasis: String,
    val communityArt: Boolean,
)

fun phoneTheme(id: Distribution): PhoneTheme = when (id) {
    Distribution.IHF -> PhoneTheme(
        ground = Color(0x04, 0x09, 0x14),
        surface = Color(0x0B, 0x13, 0x2B),
        raised = Color(0x14, 0x20, 0x40),
        gold = Color(0xD4, 0xAF, 0x37),
        goldInk = Color(0x0B, 0x13, 0x2B),
        ink = Color(0xEE, 0xF2, 0xF8),
        muted = Color(0xAA, 0xB6, 0xCC),
        caption = Color(0x84, 0x91, 0xAD),
        emerald = Color(0x35, 0xC0, 0x8A),
        sky = Color(0x79, 0xB4, 0xF2),
        coral = Color(0xF2, 0x83, 0x6F),
        hairline = Color(0x23, 0x30, 0x52),
        disabled = Color(0x6B, 0x78, 0x96),
        headlineLead = "Work from",
        headlineEmphasis = "anywhere.",
        communityArt = false,
    )
    Distribution.COMMUNITY -> PhoneTheme(
        ground = Color(0x0E, 0x10, 0x14),
        surface = Color(0x16, 0x19, 0x20),
        raised = Color(0x1E, 0x22, 0x2B),
        gold = Color(0x2B, 0xB8, 0xA3),
        goldInk = Color(0x04, 0x21, 0x1E),
        ink = Color(0xEC, 0xEF, 0xF4),
        muted = Color(0xA9, 0xB1, 0xC1),
        caption = Color(0x8A, 0x93, 0xA6),
        emerald = Color(0x7A, 0xA7, 0xFF),
        sky = Color(0xF2, 0xB8, 0x4B),
        coral = Color(0xFF, 0x7A, 0x70),
        hairline = Color(0x2A, 0x2F, 0x3A),
        disabled = Color(0x68, 0x70, 0x84),
        headlineLead = "Your PBX,",
        headlineEmphasis = "on your desk.",
        communityArt = true,
    )
}
