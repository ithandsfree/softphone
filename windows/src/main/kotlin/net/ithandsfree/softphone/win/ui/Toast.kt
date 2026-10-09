package net.ithandsfree.softphone.win.ui

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.border.EmptyBorder

/**
 * Short confirmation near the bottom of the window ("Copied (416) 555-0177", `desktop-call-detail.png`).
 * Light pill on the dark window; errors use the danger colour and stay longer. Never modal. GPL-2.0.
 */
class Toast(private val frame: JFrame, private val t: Tokens) {
    private var error = false
    private val pill = object : JLabel("", SwingConstants.CENTER) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (error) t.danger else t.text
            g2.fillRoundRect(0, 0, width, height, 12, 12)
            g2.dispose()
            super.paintComponent(g)
        }
    }
    private val hide = Timer(2600) { pill.isVisible = false }.apply { isRepeats = false }

    init {
        pill.font = Type.medium(Type.LABEL + 1)
        pill.border = EmptyBorder(9, 16, 9, 16)
        pill.isVisible = false
        frame.layeredPane.add(pill, JLayeredPane.POPUP_LAYER)
    }

    fun show(text: String, isError: Boolean = false) {
        if (text.isBlank()) return
        error = isError
        pill.text = text
        pill.icon = if (isError) Icons.get("alert", 16, t.onDanger) else Icons.get("check", 16, t.surface)
        pill.iconTextGap = 8
        pill.foreground = if (isError) t.onDanger else t.surface
        val size = pill.preferredSize
        val width = size.width.coerceAtMost(frame.layeredPane.width - 32)
        pill.setBounds((frame.layeredPane.width - width) / 2, frame.layeredPane.height - size.height - 28, width, size.height)
        pill.isVisible = true
        pill.repaint()
        hide.initialDelay = if (isError) 5200 else 2600
        hide.restart()
    }
}
