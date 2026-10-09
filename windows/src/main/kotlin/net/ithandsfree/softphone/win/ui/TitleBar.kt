package net.ithandsfree.softphone.win.ui

import com.formdev.flatlaf.FlatClientProperties
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/**
 * The window's title bar (`desktop-*.png`): app mark and name, the Ctrl K search-or-dial field, and line status.
 * It is a JMenuBar so FlatLaf embeds it in the native title bar; empty space stays the drag region and the
 * maximise button keeps Windows 11 snap layouts. GPL-2.0.
 */
class TitleBar(
    private val t: Tokens,
    productName: String,
    mark: Icon?,
    val search: JTextField,
) : JMenuBar() {
    /** One line in the status area. */
    data class LineStatus(val extension: String, val color: Color, val registered: Boolean)

    private val status = object : JPanel() {
        // BoxLayout centres each child on the bar's vertical middle; FlowLayout pinned them to the top edge.
        override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, Int.MAX_VALUE)
    }

    /** Pin on top and expand, shown only in the compact window (`desktop-compact.png`). */
    var onPin: () -> Unit = {}
    var onExpand: () -> Unit = {}
    private val pin = IconButton(t, "pin", 32, t.textMuted, "Keep on top") { onPin() }
    private val expand = IconButton(t, "expand", 32, t.textMuted, "Expand to full window") { onExpand() }

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = true
        background = t.ground
        border = EmptyBorder(0, 6, 0, 8)
        val brand = JLabel(productName, mark, JLabel.LEFT)
        brand.font = Type.semibold(Type.LABEL)
        brand.foreground = t.text
        brand.iconTextGap = 8
        add(brand)
        add(Box.createHorizontalGlue())
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search contacts and messages, or type a number")
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.get("search", 16, t.textMuted))
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, Kbd(t, "Ctrl K"))
        search.putClientProperty(FlatClientProperties.STYLE, "arc: 10; margin: 2,8,2,6; borderColor: ${css(t.hairline)}")
        search.font = Type.ui(Type.LABEL)
        search.foreground = t.text
        search.background = t.surface
        search.preferredSize = Dimension(460, 30)
        search.maximumSize = Dimension(460, 30)
        search.minimumSize = Dimension(180, 30)
        add(search)
        add(Box.createHorizontalGlue())
        status.isOpaque = false
        status.layout = BoxLayout(status, BoxLayout.X_AXIS)
        status.alignmentY = CENTER_ALIGNMENT
        brand.alignmentY = CENTER_ALIGNMENT
        search.alignmentY = CENTER_ALIGNMENT
        add(status)
        pin.alignmentY = CENTER_ALIGNMENT
        expand.alignmentY = CENTER_ALIGNMENT
        pin.isVisible = false
        expand.isVisible = false
        listOf(pin, expand).forEach {
            it.isFocusable = false
            it.style("arc: 8; borderWidth: 0; focusWidth: 0; background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}")
        }
        add(pin)
        add(Box.createHorizontalStrut(4))
        add(expand)
        add(Box.createHorizontalStrut(12))
    }


    /** Compact window: search and line status give way to the pin and expand buttons. */
    fun compact(on: Boolean) {
        search.isVisible = !on
        status.isVisible = !on
        pin.isVisible = on
        expand.isVisible = on
        revalidate()
        repaint()
    }

    fun pinned(on: Boolean) {
        pin.setTint(if (on) t.actionText else t.textMuted)
        pin.toolTipText = if (on) "Unpin from top" else "Keep on top"
        pin.getAccessibleContext().accessibleName = pin.toolTipText
    }

    /** The search field's key chip follows the remapped Search key. */
    fun searchKey(label: String) {
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, Kbd(t, label))
    }

    /** Line dots and extensions, then DND or the live call timer. */
    fun show(lines: List<LineStatus>, dnd: Boolean, callTimer: String?) {
        status.removeAll()
        fun gap(w: Int) = status.add(Box.createHorizontalStrut(w))
        if (callTimer != null) {
            status.add(Dot(t.action))
            gap(6)
            status.add(JLabel("On call").apply { font = Type.semibold(Type.LABEL); foreground = t.actionText })
            gap(8)
            status.add(JLabel(callTimer).apply { font = Type.monoMedium(Type.LABEL); foreground = t.actionText })
        } else {
            lines.forEachIndexed { index, line ->
                if (index > 0) gap(12)
                status.add(Dot(if (line.registered) line.color else t.textDisabled))
                gap(6)
                status.add(JLabel(line.extension).apply {
                    font = Type.mono(Type.LABEL)
                    foreground = if (line.registered) t.text else t.textDisabled
                    toolTipText = if (line.registered) "Line ${line.extension} is registered" else "Line ${line.extension} is not registered"
                })
            }
            if (dnd) {
                gap(12)
                status.add(JLabel("DND").apply { font = Type.medium(Type.LABEL); foreground = t.textMuted })
            }
        }
        status.components.forEach { (it as? JComponent)?.alignmentY = CENTER_ALIGNMENT }
        status.revalidate()
        status.repaint()
    }

    private fun css(c: Color) = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
}

/** Small filled status dot. */
class Dot(color: Color, private val diameter: Int = 8) : JComponent() {
    var color: Color = color
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(diameter, diameter)
        maximumSize = preferredSize
        minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(0, (height - diameter) / 2, diameter, diameter)
        g2.dispose()
    }
}

/** Keyboard key chip, as in the mockups' shortcut hints ("Ctrl K", "Enter"). */
class Kbd(private val t: Tokens, text: String, onLight: Boolean = false) : JLabel(text) {
    private val fill = if (onLight) Color(0xE6, 0xEA, 0xF2) else t.raised
    private val edge = if (onLight) Color(0xCB, 0xD3, 0xE3) else t.hairline

    init {
        font = Type.mono(11f)
        foreground = if (onLight) t.surface else t.textMuted
        border = EmptyBorder(1, 6, 1, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
        g2.color = edge
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
        g2.dispose()
        super.paintComponent(g)
    }
}
