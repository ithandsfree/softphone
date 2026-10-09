package net.ithandsfree.softphone.win.ui

import com.formdev.flatlaf.FlatClientProperties
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/** Shared round-3 controls. Colours come from [Tokens]; FlatLaf draws arcs, hover and focus rings. GPL-2.0. */

/** Solid mix of two colours; [amount] 0 = [a], 1 = [b]. */
internal fun blend(a: Color, b: Color, amount: Float): Color = Color(
    (a.red + (b.red - a.red) * amount).toInt(),
    (a.green + (b.green - a.green) * amount).toInt(),
    (a.blue + (b.blue - a.blue) * amount).toInt(),
)

internal fun css(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

/**
 * Base for painted controls (rail items, line segments). A bare JComponent has no AccessibleContext, so Narrator
 * and NVDA would see nothing; this one reports [role] and the name set through `accessibleContext.accessibleName`.
 */
abstract class AccessibleControl(private val role: javax.accessibility.AccessibleRole) : JComponent() {
    override fun getAccessibleContext(): javax.accessibility.AccessibleContext {
        if (accessibleContext == null) accessibleContext = Context()
        return accessibleContext
    }

    private inner class Context : AccessibleJComponent() {
        override fun getAccessibleRole(): javax.accessibility.AccessibleRole = role
    }
}

/** Applies a FlatLaf style string to any component. */
internal fun JComponent.style(value: String) {
    try {
        putClientProperty(FlatClientProperties.STYLE, value)
    } catch (err: RuntimeException) {
        // A style key this component does not know must not stop the app; keep FlatLaf's defaults instead.
        System.err.println("ui style ignored on ${javaClass.simpleName}: ${err.message}")
        putClientProperty(FlatClientProperties.STYLE, null)
    }
}

/** Square icon button (34 px on recent rows, 44 px elsewhere), outlined, icon tinted. */
class IconButton(
    private val t: Tokens,
    private val iconName: String,
    size: Int = 34,
    private val tint: Color = t.text,
    tip: String,
    filled: Color? = null,
    action: () -> Unit,
) : JButton() {
    private val iconSize = if (size >= 44) 20 else 18

    init {
        icon = Icons.get(iconName, iconSize, tint)
        disabledIcon = Icons.get(iconName, iconSize, t.textDisabled)
        toolTipText = tip
        preferredSize = Dimension(size, size)
        minimumSize = preferredSize
        maximumSize = preferredSize
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val fill = filled ?: t.ground
        style(
            "arc: 10; borderWidth: 1; focusWidth: 0; innerFocusWidth: 0; margin: 0,0,0,0;" +
                "background: ${css(fill)}; hoverBackground: ${css(if (filled != null) filled.brighter() else t.raised)};" +
                "pressedBackground: ${css(t.selected)}; borderColor: ${css(t.hairline)};" +
                "focusedBorderColor: ${css(t.action)}; disabledBackground: ${css(fill)}",
        )
        addActionListener { action() }
    }

    fun setTint(color: Color) {
        icon = Icons.get(iconName, iconSize, color)
    }
}

/** Primary (action fill) or secondary (outlined) labelled button. */
class PillButton(
    private val t: Tokens,
    text: String,
    iconName: String? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    height: Int = 44,
    action: () -> Unit,
) : JButton(text) {
    private val fixedHeight = height

    init {
        val fill = when {
            danger -> t.danger
            primary -> t.action
            else -> t.ground
        }
        val ink = when {
            danger -> t.onDanger
            primary -> t.onAction
            else -> t.text
        }
        foreground = ink
        font = if (primary || danger) Type.semibold(Type.BODY) else Type.medium(Type.BODY - 1)
        if (iconName != null) {
            icon = Icons.get(iconName, 18, ink)
            iconTextGap = 10
        }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        style(
            "arc: 12; focusWidth: 2; innerFocusWidth: 0; margin: 0,18,0,18;" +
                "background: ${css(fill)}; foreground: ${css(ink)};" +
                "hoverBackground: ${css(if (primary || danger) fill.brighter() else t.raised)};" +
                "pressedBackground: ${css(if (primary || danger) fill.darker() else t.selected)};" +
                "borderWidth: ${if (primary || danger) 0 else 1}; borderColor: ${css(t.hairline)};" +
                "focusedBorderColor: ${css(t.action)}; focusColor: ${css(t.action)}",
        )
        addActionListener { action() }
    }

    // Width from the current text, font and style margins (measured each time); only the height is fixed.
    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, fixedHeight)
    override fun getMaximumSize(): Dimension = preferredSize
}

/** Toggle chip ("All lines", "Missed"). Selected uses the action tint and colour. */
class Chip(private val t: Tokens, text: String, action: () -> Unit) : JButton(text) {
    var selected2: Boolean = false
        set(value) {
            field = value
            restyle()
        }

    init {
        font = Type.medium(Type.LABEL)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        restyle()
        addActionListener { action() }
    }

    private fun restyle() {
        foreground = if (selected2) t.actionText else t.text
        style(
            "arc: 10; focusWidth: 0; innerFocusWidth: 0; margin: 5,11,5,11; borderWidth: 1;" +
                "background: ${css(if (selected2) t.actionTint else t.ground)};" +
                "hoverBackground: ${css(if (selected2) t.actionTint else t.raised)};" +
                "borderColor: ${css(if (selected2) t.action else t.hairline)}; foreground: ${css(foreground)}",
        )
    }
}

/** Underlined tab row (Recents / Contacts / Voicemail). */
class UnderlineTabs(
    private val t: Tokens,
    private val items: List<Pair<String, String>>,
    private val onPick: (String) -> Unit,
) : JPanel() {
    private var chosen = items.first().first
    private val buttons = items.map { (id, title) ->
        val label = JLabel(title, SwingConstants.CENTER)
        label.font = Type.semibold(Type.BODY - 1)
        label.border = EmptyBorder(10, 4, 12, 4)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                select(id)
                onPick(id)
            }
        })
        id to label
    }

    init {
        layout = java.awt.GridLayout(1, items.size)
        isOpaque = false
        buttons.forEach { add(it.second) }
        select(chosen)
    }

    fun select(id: String) {
        chosen = id
        buttons.forEach { (key, label) -> label.foreground = if (key == id) t.text else t.textMuted }
        repaint()
    }

    val selected: String get() = chosen

    /** Changes a tab's text, e.g. "Voicemail · 2". */
    fun rename(id: String, title: String) {
        buttons.firstOrNull { it.first == id }?.second?.let {
            if (it.text != title) {
                it.text = title
                it.getAccessibleContext().accessibleName = title
            }
        }
    }

    /** Shows only [ids], in their original order (the compact window swaps Voicemail for Keypad). */
    fun only(ids: Collection<String>) {
        val shown = buttons.filter { it.first in ids }
        if (shown.isEmpty()) return
        removeAll()
        (layout as java.awt.GridLayout).columns = shown.size
        shown.forEach { add(it.second) }
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.color = t.divider
        g2.fillRect(0, height - 1, width, 1)
        val visible = components.toList()
        val index = visible.indexOf(buttons.firstOrNull { it.first == chosen }?.second)
        if (index >= 0) {
            val w = width / visible.size
            g2.color = t.action
            g2.fillRect(index * w + 8, height - 2, w - 16, 2)
        }
    }
}

/** Initials or a person glyph in a circle. */
class Avatar(private val t: Tokens, private val name: String, private val diameter: Int = 40) : JComponent() {
    init {
        preferredSize = Dimension(diameter, diameter)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = t.raised
        g2.fillOval(0, 0, diameter - 1, diameter - 1)
        g2.color = t.hairline
        g2.drawOval(0, 0, diameter - 1, diameter - 1)
        val letters = initials(name)
        if (letters.isEmpty()) {
            val icon = Icons.get("user", diameter / 2, t.textMuted)
            icon.paintIcon(this, g2, (diameter - icon.iconWidth) / 2, (diameter - icon.iconHeight) / 2)
        } else {
            g2.font = Type.semibold(diameter * 0.36f)
            g2.color = t.text
            val fm = g2.fontMetrics
            g2.drawString(letters, (diameter - fm.stringWidth(letters)) / 2, (diameter - fm.height) / 2 + fm.ascent)
        }
        g2.dispose()
    }

    companion object {
        /** Two initials from a name; empty for a phone number or blank. */
        fun initials(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
            return when {
                words.isEmpty() -> ""
                words.size == 1 -> words[0].take(2).uppercase()
                else -> "${words.first()[0]}${words.last()[0]}".uppercase()
            }
        }
    }
}

/** Small caps section label ("HISTORY WITH DANA"). */
fun sectionLabel(t: Tokens, text: String): JLabel = JLabel(text.uppercase()).apply {
    font = Type.semibold(Type.CAPTION)
    foreground = t.textCaption
    border = EmptyBorder(0, 0, 8, 0)
}

/** A row of keyboard hints: text, Kbd, text… */
fun hintRow(t: Tokens, vararg parts: Any): JPanel {
    val row = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0))
    row.isOpaque = false
    parts.forEach { part ->
        when (part) {
            is Kbd -> row.add(part)
            is String -> row.add(JLabel(part).apply { font = Type.ui(Type.CAPTION); foreground = t.textCaption })
            is Icon -> row.add(JLabel(part))
        }
    }
    return row
}

/** Transparent panel with an [EmptyBorder]. */
fun clear(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0, layoutManager: java.awt.LayoutManager? = null): JPanel =
    JPanel(layoutManager ?: FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = EmptyBorder(top, left, bottom, right)
    }
