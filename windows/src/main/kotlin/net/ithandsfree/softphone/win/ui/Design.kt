package net.ithandsfree.softphone.win.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import net.ithandsfree.softphone.win.Distribution
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.UIManager

/**
 * Round-3 desktop design tokens (`IHF-Phone-design/round-3/design-tokens.json`), fonts, and icons.
 * Role names follow the token file: action, line1, line2, danger. Sizes are design pixels; Java's own
 * per-monitor scaling handles high-DPI screens, so nothing here multiplies by a screen factor. GPL-2.0.
 */
data class Tokens(
    val ground: Color,
    val surface: Color,
    val raised: Color,
    val selected: Color,
    val hairline: Color,
    val divider: Color,
    val railActiveBg: Color,
    val action: Color,
    val onAction: Color,
    val actionText: Color,
    val actionTint: Color,
    val line1: Color,
    val line2: Color,
    val danger: Color,
    val onDanger: Color,
    /** Vivid red for End and Decline (danger stays coral for text and badges). */
    val endCall: Color,
    val endCallDeep: Color,
    val ok: Color,
    val answer: Color,
    val onAnswer: Color,
    val text: Color,
    val textMuted: Color,
    val textCaption: Color,
    val textDisabled: Color,
    val dndFill: Color,
    val mediaPlaceholder: Color,
) {
    /** Colour of the line at [index] (0 = first line, 1 = second line). */
    fun line(index: Int): Color = if (index == 1) line2 else line1

    companion object {
        fun of(edition: Distribution): Tokens = when (edition) {
            Distribution.IHF -> Tokens(
                ground = hex(0x040914), surface = hex(0x0B132B), raised = hex(0x142040), selected = hex(0x1F3B7D),
                hairline = hex(0x233052), divider = hex(0x1A2644), railActiveBg = hex(0x0F2238),
                action = hex(0xD4AF37), onAction = hex(0x0B132B), actionText = hex(0xD4AF37), actionTint = hex(0x2A2410),
                line1 = hex(0x35C08A), line2 = hex(0x79B4F2), danger = hex(0xF2836F), onDanger = hex(0x040914),
                endCall = hex(0xE5484D), endCallDeep = hex(0xC2262B),
                ok = hex(0x35C08A), answer = hex(0x35C08A), onAnswer = hex(0x040914),
                text = hex(0xEEF2F8), textMuted = hex(0xAAB6CC), textCaption = hex(0x8491AD), textDisabled = hex(0x6B7896),
                dndFill = hex(0x2B2F45), mediaPlaceholder = hex(0x2A3F7A),
            )
            Distribution.COMMUNITY -> Tokens(
                ground = hex(0x0E1014), surface = hex(0x161920), raised = hex(0x1E222B), selected = hex(0x17433F),
                hairline = hex(0x2A2F3A), divider = hex(0x20242D), railActiveBg = hex(0x132A2A),
                action = hex(0x2BB8A3), onAction = hex(0x04211E), actionText = hex(0x2BB8A3), actionTint = hex(0x112C2A),
                line1 = hex(0x7AA7FF), line2 = hex(0xF2B84B), danger = hex(0xFF7A70), onDanger = hex(0x1A0503),
                endCall = hex(0xE5484D), endCallDeep = hex(0xC2262B),
                ok = hex(0x2BB8A3), answer = hex(0x2BB8A3), onAnswer = hex(0x04211E),
                text = hex(0xECEFF4), textMuted = hex(0xA9B1C1), textCaption = hex(0x8A93A6), textDisabled = hex(0x687084),
                dndFill = hex(0x2B2D38), mediaPlaceholder = hex(0x24414A),
            )
        }

        private fun hex(rgb: Int) = Color(rgb)
    }
}

/** Type scale from the token file. Inter for UI, Instrument Serif for titles, IBM Plex Mono for numbers. */
object Type {
    private val loaded: Map<String, Font> by lazy {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        listOf(
            "Inter-Regular", "Inter-Medium", "Inter-SemiBold", "Inter-Bold",
            "InstrumentSerif-Regular", "IBMPlexMono-Regular", "IBMPlexMono-Medium",
        ).mapNotNull { name ->
            runCatching {
                Type::class.java.getResourceAsStream("/fonts/$name.ttf")!!.use { stream ->
                    Font.createFont(Font.TRUETYPE_FONT, stream).also { env.registerFont(it) }
                }
            }.getOrNull()?.let { name to it }
        }.toMap()
    }

    private fun face(name: String, fallback: String, style: Int, size: Float): Font =
        loaded[name]?.deriveFont(size) ?: Font(fallback, style, size.toInt())

    fun ui(size: Float = 15f) = face("Inter-Regular", "Segoe UI", Font.PLAIN, size)
    fun medium(size: Float = 15f) = face("Inter-Medium", "Segoe UI", Font.PLAIN, size)
    fun semibold(size: Float = 15f) = face("Inter-SemiBold", "Segoe UI Semibold", Font.BOLD, size)
    fun bold(size: Float = 15f) = face("Inter-Bold", "Segoe UI", Font.BOLD, size)
    fun display(size: Float = 32f) = face("InstrumentSerif-Regular", "Georgia", Font.PLAIN, size)
    fun mono(size: Float = 15f) = face("IBMPlexMono-Regular", "Consolas", Font.PLAIN, size)
    fun monoMedium(size: Float = 15f) = face("IBMPlexMono-Medium", "Consolas", Font.PLAIN, size)

    const val DISPLAY = 40f
    const val TITLE = 32f
    const val TITLE_SM = 26f
    const val HEADING = 17f
    const val BODY = 15f
    const val LABEL = 13f
    const val CAPTION = 12f
    const val NUMBER_XL = 36f
    const val TIMER = 20f
}

/** Icons drawn by the design team (`round-3/_src/head.html`), one SVG per name under `/icons/ui/`. */
object Icons {
    private val base = Color(0xEE, 0xF2, 0xF8)

    fun get(name: String, size: Int = 20, color: Color? = null): FlatSVGIcon {
        val icon = FlatSVGIcon("icons/ui/$name.svg", size, size)
        if (color != null) {
            icon.colorFilter = FlatSVGIcon.ColorFilter { c -> if (c.rgb == base.rgb) color else c }
        }
        return icon
    }
}

/** Space and radius tokens. */
object Space {
    const val SP1 = 4
    const val SP2 = 8
    const val SP3 = 12
    const val SP4 = 16
    const val SP5 = 20
    const val SP6 = 24
    const val SP8 = 32
    const val HIT_MIN = 44
    const val ROW_H = 72
    const val ROW_H_SM = 52
    const val KEY_H = 64
    const val CALL_BTN = 72
    const val R_CHIP = 8
    const val R_CONTROL = 12
    const val R_CARD = 16
    const val RAIL_W = 76
    const val LIST_W = 392
}

/**
 * Installs FlatLaf dark with the edition's tokens. Called once, before the first window. On Windows 10/11 FlatLaf
 * draws the title bar itself and keeps native snap layouts on the maximise button.
 */
fun installDesign(edition: Distribution): Tokens {
    val t = Tokens.of(edition)
    fun css(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    FlatLaf.setGlobalExtraDefaults(
        mapOf(
            "@background" to css(t.ground),
            "@foreground" to css(t.text),
            "@accentColor" to css(t.action),
            "@selectionBackground" to css(t.selected),
            "@selectionForeground" to css(t.text),
            "@disabledForeground" to css(t.textDisabled),
            "Component.borderColor" to css(t.hairline),
            "Component.focusColor" to css(t.action),
            "Component.focusedBorderColor" to css(t.action),
            "Component.focusWidth" to "2",
            "Component.innerFocusWidth" to "0",
            "Component.arc" to "${Space.R_CONTROL}",
            "Button.arc" to "${Space.R_CONTROL}",
            "TextComponent.arc" to "${Space.R_CONTROL}",
            "Button.background" to css(t.surface),
            "Button.hoverBackground" to css(t.raised),
            "Button.borderColor" to css(t.hairline),
            "TextField.background" to css(t.surface),
            "PasswordField.background" to css(t.surface),
            "TextArea.background" to css(t.surface),
            "TextField.placeholderForeground" to css(t.textCaption),
            "Panel.background" to css(t.ground),
            "ScrollPane.background" to css(t.ground),
            "Viewport.background" to css(t.ground),
            "ScrollBar.width" to "10",
            "ScrollBar.thumbArc" to "999",
            "ScrollBar.thumbInsets" to "2,2,2,2",
            "ScrollBar.track" to css(t.ground),
            "ScrollBar.thumb" to css(t.hairline),
            "ScrollBar.showButtons" to "false",
            "List.background" to css(t.ground),
            "PopupMenu.background" to css(t.surface),
            "PopupMenu.borderColor" to css(t.hairline),
            "MenuItem.selectionBackground" to css(t.raised),
            "ToolTip.background" to css(t.text),
            "ToolTip.foreground" to css(t.surface),
            "TitlePane.background" to css(t.ground),
            "TitlePane.inactiveBackground" to css(t.ground),
            "TitlePane.foreground" to css(t.text),
            "TitlePane.inactiveForeground" to css(t.textMuted),
            "TitlePane.unifiedBackground" to "true",
            "TitlePane.menuBarEmbedded" to "true",
            "TitlePane.buttonHoverBackground" to css(t.raised),
            "TitlePane.closeHoverBackground" to "#c42b1c",
            "MenuBar.background" to css(t.ground),
            "MenuBar.borderColor" to css(t.ground),
            "CheckBox.icon.checkmarkColor" to css(t.onAction),
            "CheckBox.icon.selectedBackground" to css(t.action),
            "ComboBox.background" to css(t.surface),
            "ComboBox.buttonBackground" to css(t.surface),
            "ComboBox.popupBackground" to css(t.surface),
            "Separator.foreground" to css(t.divider),
        ),
    )
    FlatDarkLaf.setup()
    UIManager.put("defaultFont", Type.ui(Type.BODY - 1))
    return t
}
