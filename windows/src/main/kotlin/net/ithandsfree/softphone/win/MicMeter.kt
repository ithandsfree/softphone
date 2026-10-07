package net.ithandsfree.softphone.win

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/** A bar for the microphone level, from 0 to 255. The empty track stays visible. */
internal class MicMeter(
    private val track: Color,
    private val fill: Color,
    private val edge: Color,
) : JComponent() {
    var level: Int = 0

    override fun getPreferredSize(): Dimension = Dimension(320, UiScale.px(28))

    override fun getMinimumSize(): Dimension = Dimension(160, UiScale.px(28))

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, UiScale.px(28))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val barHeight = (height - 8).coerceAtLeast(8)
        val top = (height - barHeight) / 2
        g2.color = track
        g2.fillRoundRect(0, top, width, barHeight, 10, 10)
        g2.color = edge
        g2.drawRoundRect(0, top, (width - 1).coerceAtLeast(1), (barHeight - 1).coerceAtLeast(1), 10, 10)
        val span = (width * level.coerceIn(0, 255) / 255).coerceIn(0, width)
        if (span > 0) {
            g2.color = fill
            g2.fillRoundRect(1, top + 1, (span - 2).coerceAtLeast(1), (barHeight - 2).coerceAtLeast(1), 8, 8)
        }
        g2.dispose()
    }
}
