package net.ithandsfree.softphone.win

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

/** One destination on the narrow desktop rail. */
internal class RailButton(
    title: String,
    private val id: String,
    private val onInk: Color,
    private val offInk: Color,
    private val onFill: Color,
    private val offFill: Color,
    private val badgeInk: Color,
) : JButton(title) {
    private var chosen = false
    private var count = 0

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        font = Font("Segoe UI", Font.PLAIN, UiScale.px(11))
        toolTipText = title
    }

    fun setChosen(on: Boolean) {
        chosen = on
        repaint()
    }

    fun setCount(value: Int) {
        count = value
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(UiScale.px(72), UiScale.px(64))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = if (chosen) onFill else offFill
        g2.fillRoundRect(4, 2, width - 8, height - 4, 14, 14)
        g2.color = if (chosen) onInk else offInk
        drawMark(g2, width / 2, 22)
        g2.font = font
        val label = text.orEmpty()
        val fm = g2.fontMetrics
        g2.drawString(label, (width - fm.stringWidth(label)) / 2, height - 10)
        if (count > 0) {
            val badge = if (count > 9) "9+" else count.toString()
            g2.font = Font("Segoe UI", Font.BOLD, 10)
            val bw = g2.fontMetrics.stringWidth(badge) + 8
            g2.color = onInk
            g2.fillRoundRect(width - bw - 6, 6, bw, 14, 8, 8)
            g2.color = badgeInk
            g2.drawString(badge, width - bw - 2, 17)
        }
        g2.dispose()
    }

    private fun drawMark(g2: Graphics2D, cx: Int, cy: Int) {
        when (id) {
            "messages" -> g2.drawRoundRect(cx - 8, cy - 6, 16, 12, 4, 4)
            "lines" -> {
                g2.drawOval(cx - 9, cy - 5, 8, 8)
                g2.drawOval(cx + 1, cy - 5, 8, 8)
            }
            "settings" -> g2.drawOval(cx - 6, cy - 6, 12, 12)
            else -> {
                g2.drawRoundRect(cx - 7, cy - 4, 14, 8, 6, 6)
                g2.drawLine(cx - 4, cy + 4, cx - 7, cy + 8)
            }
        }
    }
}
