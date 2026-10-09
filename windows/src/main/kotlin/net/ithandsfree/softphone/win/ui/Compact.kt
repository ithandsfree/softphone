package net.ithandsfree.softphone.win.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Compact window parts (`desktop-compact.png`, window narrower than 720 px): the bottom navigation bar and the
 * round call and erase buttons under the keypad. GPL-2.0.
 */

/** Bottom bar: Calls, Messages, Lines, Settings with icon over label; chosen item in the action colour. */
class BottomNav(
    private val t: Tokens,
    items: List<Triple<String, String, String>>,
    private val onPick: (String) -> Unit,
) : JPanel(GridLayout(1, items.size)) {
    private val buttons = linkedMapOf<String, Item>()

    init {
        background = t.ground
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, t.divider),
            javax.swing.border.EmptyBorder(6, 4, 6, 4),
        )
        getAccessibleContext().accessibleName = "Primary"
        items.forEach { (id, title, icon) ->
            val item = Item(id, title, icon)
            buttons[id] = item
            add(item)
        }
    }

    fun choose(id: String) {
        buttons.forEach { (key, item) -> item.chosen = key == id }
    }

    fun badge(id: String, count: Int, danger: Boolean) {
        buttons[id]?.let {
            it.count = count
            it.danger = danger
            it.repaint()
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, 68)

    private inner class Item(private val id: String, private val title: String, private val icon: String) : JButton() {
        var chosen = false
            set(value) {
                field = value
                getAccessibleContext().accessibleDescription = if (value) "Current page" else null
                repaint()
            }
        var count = 0
        var danger = false
        private var hover = false

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            getAccessibleContext().accessibleName = title
            toolTipText = title
            addActionListener { onPick(id) }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
            })
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
                override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (hover && !chosen) {
                g2.color = t.surface
                g2.fillRoundRect(4, 0, width - 8, height, 12, 12)
            }
            if (isFocusOwner) {
                g2.color = t.action
                g2.stroke = BasicStroke(2f)
                g2.drawRoundRect(5, 1, width - 11, height - 3, 12, 12)
            }
            val ink = if (chosen) t.actionText else t.textMuted
            val glyph = Icons.get(icon, 22, ink)
            val ix = (width - 22) / 2
            glyph.paintIcon(this, g2, ix, 6)
            g2.font = if (chosen) Type.semibold(Type.LABEL) else Type.medium(Type.LABEL)
            g2.color = if (chosen) t.actionText else t.textMuted
            val fm = g2.fontMetrics
            g2.drawString(title, (width - fm.stringWidth(title)) / 2, 6 + 22 + 5 + fm.ascent)
            if (count > 0) {
                val text = if (count > 99) "99+" else count.toString()
                g2.font = Type.semibold(10.5f)
                val bw = maxOf(17, g2.fontMetrics.stringWidth(text) + 9)
                val bx = ix + 14
                g2.color = if (danger) t.danger else t.action
                g2.fillRoundRect(bx, 0, bw, 17, 17, 17)
                g2.color = if (danger) t.onDanger else t.onAction
                g2.drawString(text, bx + (bw - g2.fontMetrics.stringWidth(text)) / 2, 13)
            }
            g2.dispose()
        }
    }
}

/**
 * Painted round button: [fill] disc with a soft glow when [glow], the icon in [ink]. Hover lightens, press
 * darkens, and keyboard focus is the 2 px action ring with 2 px offset from the design.
 */
class CircleButton(
    private val t: Tokens,
    private var iconName: String,
    private val diameter: Int,
    private val fill: Color,
    private val ink: Color,
    tip: String,
    private val glow: Boolean = false,
    private val outline: Color? = null,
    action: () -> Unit,
) : JButton() {
    private var hover = false
    private var down = false
    private val pad = if (glow) 10 else 4

    init {
        val size = diameter + pad * 2
        preferredSize = Dimension(size, size)
        maximumSize = preferredSize
        minimumSize = preferredSize
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tip
        getAccessibleContext().accessibleName = tip
        addActionListener { action() }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; down = false; repaint() }
            override fun mousePressed(e: java.awt.event.MouseEvent) { down = true; repaint() }
            override fun mouseReleased(e: java.awt.event.MouseEvent) { down = false; repaint() }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
        })
    }

    /** Swaps the glyph (play ↔ pause). */
    fun glyph(name: String) {
        iconName = name
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val x = (width - diameter) / 2
        val y = (height - diameter) / 2
        if (glow && isEnabled) {
            for (ring in 8 downTo 1) {
                g2.color = Color(fill.red, fill.green, fill.blue, 7 + (8 - ring) * 3)
                g2.fillOval(x - ring, y - ring + 2, diameter + ring * 2, diameter + ring * 2)
            }
        }
        g2.color = when {
            !isEnabled -> t.surface
            down -> blend(fill, Color.BLACK, 0.18f)
            hover -> blend(fill, Color.WHITE, 0.12f)
            else -> fill
        }
        g2.fillOval(x, y, diameter, diameter)
        outline?.let {
            g2.color = it
            g2.stroke = BasicStroke(1f)
            g2.drawOval(x, y, diameter - 1, diameter - 1)
        }
        if (isFocusOwner) {
            g2.color = t.action
            g2.stroke = BasicStroke(2f)
            g2.drawOval(x - 3, y - 3, diameter + 5, diameter + 5)
        }
        val size = if (diameter >= 56) 24 else 20
        Icons.get(iconName, size, if (isEnabled) ink else t.textDisabled)
            .paintIcon(this, g2, x + (diameter - size) / 2, y + (diameter - size) / 2)
        g2.dispose()
    }
}

/** Scroll view that always takes the viewport's width, so rows shrink with a narrow window instead of clipping. */
class WidthTrackingPanel : JPanel(), javax.swing.Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

/**
 * FlowLayout that wraps onto more rows and reports the height it needs, so a row of chips stays whole in a
 * narrow pane (plain FlowLayout asks for one row and the rest is cut off).
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 8, vgap: Int = 8) : java.awt.FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: java.awt.Container): Dimension = size(target)
    override fun minimumLayoutSize(target: java.awt.Container): Dimension = size(target).also { it.width = 0 }

    private fun size(target: java.awt.Container): Dimension = synchronized(target.treeLock) {
        val insets = target.insets
        val parent = target.parent
        val avail = (parent?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 } ?: target.width.takeIf { it > 0 } ?: Int.MAX_VALUE) -
            insets.left - insets.right - hgap * 2 // the same room FlowLayout.layoutContainer uses
        var rowW = 0
        var rowH = 0
        var w = 0
        var h = 0
        target.components.filter { it.isVisible }.forEach { c ->
            val d = c.preferredSize
            if (rowW > 0 && rowW + hgap + d.width > avail) {
                w = maxOf(w, rowW)
                h += rowH + vgap
                rowW = 0
                rowH = 0
            }
            rowW += (if (rowW > 0) hgap else 0) + d.width
            rowH = maxOf(rowH, d.height)
        }
        w = maxOf(w, rowW)
        h += rowH
        // FlowLayout places rows vgap below the top inset and leaves vgap under the last row.
        Dimension(w + insets.left + insets.right, h + vgap * 2 + insets.top + insets.bottom)
    }
}
