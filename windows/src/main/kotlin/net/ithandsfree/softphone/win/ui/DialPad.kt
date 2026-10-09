package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Keypad pane (`desktop-calls.png`): mono number with caret, "Calling from <line> · <number>", the matching
 * contact chip, 3×4 keys, the Call button that names the line, and the keyboard hint. The number field is the
 * frame's own, so typing, paste and Enter keep working. GPL-2.0.
 */
class DialPad(
    private val t: Tokens,
    val number: JTextField,
    private val onKey: (String) -> Unit,
    private val onCall: () -> Unit,
    private val onErase: () -> Unit,
) : JPanel(), javax.swing.Scrollable {
    // Fill the view (glue centres the pad) when it fits; scroll when the window is shorter than the pad.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent?.height ?: 0) > preferredSize.height

    private val from = JLabel(" ", SwingConstants.CENTER)
    private val fromDot = Dot(t.line1, 7)
    private val chip = JPanel(BorderLayout(12, 0))
    private val chipName = JLabel()
    private val chipNumber = JLabel()
    private var chipAvatar: Avatar? = null
    private val call = PillButton(t, "Call", "phone", primary = true, height = 48) { onCall() }
    private val erase = IconButton(t, "back", 36, t.textMuted, "Delete the last digit (Backspace)") { onErase() }
    private val keys = padGrid(t, onKey)
    private val hint = hintRow(t, "Type or paste a number, then", Kbd(t, "Enter"), ".", Kbd(t, "Ctrl 1"), Kbd(t, "Ctrl 2"), "switch line.")

    // Compact window (`desktop-compact.png`): round gold call button with the erase button beside it.
    private val roundCall = CircleButton(t, "phone", 60, t.action, t.onAction, "Call", glow = true) { onCall() }
    private val roundErase = CircleButton(t, "back", 48, t.ground, t.textMuted, "Delete the last digit (Backspace)") { onErase() }
    private val compactRow = JPanel(java.awt.GridLayout(1, 3))
    private var compact = false
    private var hasDigits = false
    private var lineLabel = ""
    private var lineNumberText = ""
    private val gapAfterFrom = Box.createVerticalStrut(14) as Box.Filler
    private val gapBeforeKeys = Box.createVerticalStrut(16) as Box.Filler
    private val gapAfterKeys = Box.createVerticalStrut(20) as Box.Filler
    private val gapAfterCall = Box.createVerticalStrut(18) as Box.Filler

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(24, 24, 24, 24)
        add(Box.createVerticalGlue())

        number.font = Type.mono(Type.NUMBER_XL)
        number.horizontalAlignment = JTextField.CENTER
        number.foreground = t.text
        number.caretColor = t.action
        number.isOpaque = false
        // An explicit border replaces FlatLaf's outlined field border; the number sits on the pane like the mockup.
        number.border = EmptyBorder(0, 40, 0, 0)
        number.background = t.ground
        number.maximumSize = Dimension(460, 52)
        number.preferredSize = Dimension(420, 52)
        erase.style("arc: 10; borderWidth: 0; background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}")
        val numberRow = JPanel(BorderLayout())
        numberRow.isOpaque = false
        numberRow.maximumSize = Dimension(460, 52)
        numberRow.add(number, BorderLayout.CENTER)
        numberRow.add(erase, BorderLayout.EAST)
        numberRow.alignmentX = CENTER_ALIGNMENT
        add(numberRow)
        add(Box.createVerticalStrut(6))

        val fromRow = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0))
        fromRow.isOpaque = false
        from.font = Type.ui(Type.LABEL)
        from.foreground = t.textMuted
        fromRow.add(fromDot)
        fromRow.add(from)
        fromRow.alignmentX = CENTER_ALIGNMENT
        fromRow.maximumSize = Dimension(460, 22)
        add(fromRow)
        add(gapAfterFrom)

        chip.isOpaque = true
        chip.background = t.surface
        chip.border = javax.swing.BorderFactory.createCompoundBorder(
            RoundBorder(t.hairline, 14),
            EmptyBorder(10, 14, 10, 14),
        )
        chip.maximumSize = Dimension(280, 64)
        chip.preferredSize = Dimension(280, 64)
        chip.alignmentX = CENTER_ALIGNMENT
        chipName.font = Type.semibold(Type.BODY - 1)
        chipName.foreground = t.text
        chipNumber.font = Type.mono(Type.CAPTION)
        chipNumber.foreground = t.textMuted
        val chipText = JPanel()
        chipText.layout = BoxLayout(chipText, BoxLayout.Y_AXIS)
        chipText.isOpaque = false
        chipText.add(chipName)
        chipText.add(Box.createVerticalStrut(2))
        chipText.add(chipNumber)
        chip.add(chipText, BorderLayout.CENTER)
        chip.add(JPanel(BorderLayout()).apply { isOpaque = false; add(Kbd(t, "Tab"), BorderLayout.NORTH) }, BorderLayout.EAST)
        chip.isVisible = false
        add(chip)
        add(gapBeforeKeys)

        keys.alignmentX = CENTER_ALIGNMENT
        add(keys)
        add(gapAfterKeys)
        call.alignmentX = CENTER_ALIGNMENT
        call.maximumSize = Dimension(240, 48)
        call.preferredSize = Dimension(240, 48)
        add(call)
        compactRow.isOpaque = false
        compactRow.add(JPanel().apply { isOpaque = false })
        compactRow.add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(roundCall) })
        compactRow.add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(roundErase) })
        compactRow.alignmentX = CENTER_ALIGNMENT
        compactRow.maximumSize = Dimension(316, 84)
        compactRow.preferredSize = Dimension(316, 84)
        compactRow.isVisible = false
        add(compactRow)
        add(gapAfterCall)
        hint.alignmentX = CENTER_ALIGNMENT
        hint.maximumSize = Dimension(520, 24)
        add(hint)
        add(Box.createVerticalGlue())
    }

    /** The line new calls use, and its number for the "Calling from" line. */
    fun line(label: String, lineNumber: String?, colorIndex: Int) {
        fromDot.color = t.line(colorIndex)
        lineLabel = label
        lineNumberText = lineNumber?.let { " · ${displayNumberOrSelf(it)}" }.orEmpty()
        from.text = (if (compact) "From " else "Calling from ") + label + lineNumberText
        call.text = "Call from $label"
        call.toolTipText = "Call from $label (Enter)"
        roundCall.toolTipText = "Call from $label (Enter)"
        roundCall.getAccessibleContext().accessibleName = "Call from $label"
    }

    /**
     * Phone layout for a window under 720 px: 30 px number, wider 52 px keys with 8 px gaps, and the round call
     * and erase buttons instead of the labelled Call button and the keyboard hint.
     */
    fun compact(on: Boolean) {
        if (compact == on) return
        compact = on
        border = if (on) EmptyBorder(6, 20, 6, 20) else EmptyBorder(24, 24, 24, 24)
        number.font = number.font.deriveFont(if (on) 30f else Type.NUMBER_XL)
        number.border = EmptyBorder(0, if (on) 0 else 40, 0, 0)
        fun gap(f: Box.Filler, h: Int) = f.changeShape(Dimension(0, h), Dimension(0, h), Dimension(Short.MAX_VALUE.toInt(), h))
        gap(gapAfterFrom, if (on) 10 else 14)
        gap(gapBeforeKeys, if (on) 8 else 16)
        gap(gapAfterKeys, if (on) 4 else 20)
        gap(gapAfterCall, if (on) 0 else 18)
        number.preferredSize = Dimension(420, if (on) 44 else 52)
        val grid = keys.layout as java.awt.GridLayout
        grid.hgap = if (on) 8 else 12
        grid.vgap = if (on) 8 else 12
        keys.preferredSize = if (on) Dimension(316, 232) else Dimension(240, 264)
        keys.maximumSize = keys.preferredSize
        call.isVisible = !on
        hint.isVisible = !on
        compactRow.isVisible = on
        from.text = (if (on) "From " else "Calling from ") + lineLabel + lineNumberText
        eraseVisible(hasDigits)
        revalidate()
        repaint()
    }

    /** The saved contact for the typed number, or null to hide the chip. */
    fun contact(name: String?, numberText: String?) {
        chip.isVisible = name != null
        if (name == null) return
        chipAvatar?.let { chip.remove(it) }
        chipAvatar = Avatar(t, name, 36).also { chip.add(it, BorderLayout.WEST) }
        chipName.text = name
        chipNumber.text = numberText.orEmpty()
        chip.revalidate()
    }

    fun eraseVisible(on: Boolean) {
        hasDigits = on
        erase.isVisible = on && !compact
        roundErase.isVisible = on && compact
    }

    private fun displayNumberOrSelf(raw: String) = net.ithandsfree.softphone.win.displayNumber(raw)

}

/** Rounded 1 px outline for cards and chips. */
class RoundBorder(private val color: java.awt.Color, private val arc: Int) : javax.swing.border.AbstractBorder() {
    override fun paintBorder(c: java.awt.Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc)
        g2.dispose()
    }

    override fun getBorderInsets(c: java.awt.Component) = java.awt.Insets(1, 1, 1, 1)
}

/** Panel whose background is a rounded card (fill + hairline). */
open class Card(private val t: Tokens, private val arc: Int = Space.R_CARD, private val fill: java.awt.Color = t.surface) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = t.hairline
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }
}

internal fun JComponent.centered(): JComponent = apply { alignmentX = JComponent.CENTER_ALIGNMENT }

/** 3×4 keypad (dial pad and in-call tones share it). Keys never take focus, so typing stays in the number. */
fun padGrid(t: Tokens, onKey: (String) -> Unit): JPanel {
    val keys = JPanel(GridLayout(4, 3, 12, 12))
    keys.isOpaque = false
    keys.maximumSize = Dimension(240, 264)
    keys.preferredSize = Dimension(240, 264)
    val letters = mapOf(
        "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+",
    )
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEach { digit ->
        keys.add(PadKey(t, digit, letters[digit].orEmpty(), onKey))
    }
    return keys
}

/** One keypad key: mono digit with its letters underneath. */
class PadKey(private val t: Tokens, private val digit: String, private val letters: String, onKey: (String) -> Unit) : JButton() {
    init {
        preferredSize = Dimension(72, 56)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isFocusable = false
        toolTipText = digit
        getAccessibleContext().accessibleName = if (letters.isBlank()) digit else "$digit $letters"
        style(
            "arc: 12; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;" +
                "background: ${css(t.surface)}; hoverBackground: ${css(t.raised)}; pressedBackground: ${css(t.selected)}",
        )
        addActionListener { onKey(digit) }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = Type.mono(if (digit == "*" || digit == "#") 22f else 24f)
        g2.color = t.text
        val fm = g2.fontMetrics
        val top = if (letters.isBlank()) (height + fm.ascent - fm.descent) / 2 else 30
        g2.drawString(digit, (width - fm.stringWidth(digit)) / 2, top)
        if (letters.isNotBlank()) {
            g2.font = Type.mono(10f)
            g2.color = t.textCaption
            val lm = g2.fontMetrics
            g2.drawString(letters, (width - lm.stringWidth(letters)) / 2, top + 15)
        }
        g2.dispose()
    }
}
