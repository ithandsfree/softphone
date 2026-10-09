package net.ithandsfree.softphone.win.ui

import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Two-segment line control at the top of the list pane (`desktop-calls.png`, `desktop-messages.png`):
 * dot in the line colour, the line's name, its extension in mono, and an optional danger badge. The chosen line
 * is outlined in its colour. Ctrl 1 and Ctrl 2 pick the same segments. GPL-2.0.
 */
class LineRail(private val t: Tokens, private val onPick: (String) -> Unit) : JPanel() {
    data class Segment(val extension: String, val label: String, val colorIndex: Int, val badge: Int = 0)

    private var segments: List<Segment> = emptyList()
    private var chosen: String? = null

    init {
        isOpaque = false
        layout = GridLayout(1, 2, 8, 0)
        preferredSize = Dimension(300, 40)
        maximumSize = Dimension(Int.MAX_VALUE, 40)
    }

    fun show(next: List<Segment>, selected: String?) {
        if (next == segments && selected == chosen) return
        segments = next
        chosen = selected
        removeAll()
        layout = GridLayout(1, next.size.coerceAtLeast(1), 8, 0)
        next.forEach { add(SegmentView(it)) }
        revalidate()
        repaint()
    }

    private inner class SegmentView(private val seg: Segment) : AccessibleControl(javax.accessibility.AccessibleRole.TOGGLE_BUTTON) {
        private var hover = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true
            toolTipText = if (seg.label == seg.extension) "Use ${seg.extension} for calls and messages" else "Use ${seg.label} (${seg.extension}) for calls and messages"
            getAccessibleContext().accessibleName = "${seg.label} line ${seg.extension}"
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onPick(seg.extension)
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
            })
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER || e.keyCode == java.awt.event.KeyEvent.VK_SPACE) onPick(seg.extension)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val on = seg.extension == chosen
            val color = t.line(seg.colorIndex)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val fill = when {
                on -> java.awt.Color(color.red, color.green, color.blue, 34)
                hover -> t.raised
                else -> t.ground
            }
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = if (on) color else t.hairline
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            if (isFocusOwner) {
                g2.color = t.action
                g2.drawRoundRect(2, 2, width - 5, height - 5, 10, 10)
            }
            var x = 12
            val mid = height / 2
            g2.color = color
            g2.fillOval(x, mid - 4, 8, 8)
            x += 16
            g2.font = if (on) Type.semibold(Type.LABEL + 1) else Type.medium(Type.LABEL + 1)
            g2.color = t.text
            val fm = g2.fontMetrics
            val badgeW = if (seg.badge > 0) 26 else 0
            val room = width - x - 10 - badgeW
            // The line name matters more than the extension: in a narrow window the extension goes first.
            val mono = Type.mono(Type.LABEL)
            val extW = g2.getFontMetrics(mono).stringWidth(seg.extension) + 8
            val withExt = seg.label != seg.extension && fm.stringWidth(seg.label) <= room - extW
            val label = fit(seg.label, fm, if (withExt) room - extW else room)
            g2.drawString(label, x, mid + fm.ascent / 2 - 1)
            x += fm.stringWidth(label) + 8
            if (withExt) {
                g2.font = mono
                g2.color = t.textMuted
                g2.drawString(seg.extension, x, mid + g2.fontMetrics.ascent / 2 - 1)
            }
            if (seg.badge > 0) {
                val text = if (seg.badge > 9) "9+" else seg.badge.toString()
                g2.color = t.danger
                g2.fillOval(width - 30, mid - 10, 20, 20)
                g2.font = Type.semibold(11f)
                g2.color = t.onDanger
                g2.drawString(text, width - 20 - g2.fontMetrics.stringWidth(text) / 2, mid + 4)
            }
            g2.dispose()
        }

        private fun fit(text: String, fm: java.awt.FontMetrics, room: Int): String {
            if (fm.stringWidth(text) <= room || room <= 0) return text
            var cut = text
            while (cut.length > 1 && fm.stringWidth("$cut…") > room) cut = cut.dropLast(1)
            return "$cut…"
        }
    }
}
