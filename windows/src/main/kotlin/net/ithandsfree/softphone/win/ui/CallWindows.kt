package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.border.EmptyBorder
import net.ithandsfree.softphone.win.ui.Type as Fonts

/**
 * Desktop call surfaces (`desktop-notifications.png`): the incoming-call window (bottom right, band in the line's
 * colour) and the mini call window (top right, while the main window is minimised). Both are small rounded windows
 * that stay on top and are never modal. GPL-2.0.
 */
data class CallCard(
    val title: String,
    val titleIsNumber: Boolean,
    val subtitle: String,
    val lineLabel: String,
    val lineColorIndex: Int,
    val lineNumber: String?,
    /** Call waiting (0.1.39): the answer button reads "Hold & answer" (the current call goes on hold). */
    val waiting: Boolean = false,
)

/**
 * Which line is ringing, readable at a glance: a pill tinted in the line's colour with a dot, the line name in
 * capitals and the line's number. Used on the incoming window and on the in-app ringing screen.
 */
class LineChip(private val t: Tokens, private val big: Boolean = true) : JComponent() {
    private var label = ""
    private var detail = ""
    private var color = t.line1

    init {
        getAccessibleContext()
    }

    fun show(lineLabel: String, lineDetail: String?, colorIndex: Int) {
        label = lineLabel.uppercase()
        detail = lineDetail.orEmpty()
        color = t.line(colorIndex)
        toolTipText = "Ringing on $lineLabel" + (lineDetail?.let { " ($it)" } ?: "")
        revalidate()
        repaint()
    }

    private fun labelFont() = Fonts.bold(if (big) 15f else 13f).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.06f))
    private fun detailFont() = Fonts.mono(if (big) 13f else 12f)

    override fun getPreferredSize(): Dimension {
        val lw = getFontMetrics(labelFont()).stringWidth(label)
        val dw = if (detail.isBlank()) 0 else getFontMetrics(detailFont()).stringWidth(detail) + 12
        return Dimension(lw + dw + (if (big) 52 else 40), if (big) 36 else 28)
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = color.alpha(46)
        g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
        g2.color = color.alpha(150)
        g2.drawRoundRect(0, 0, width - 1, height - 1, height, height)
        val dot = if (big) 10 else 8
        var x = if (big) 16 else 12
        g2.color = color
        g2.fillOval(x, (height - dot) / 2, dot, dot)
        x += dot + 10
        g2.font = labelFont()
        g2.color = t.text
        val fm = g2.fontMetrics
        g2.drawString(label, x, (height + fm.ascent - fm.descent) / 2)
        x += fm.stringWidth(label) + 12
        if (detail.isNotBlank()) {
            g2.font = detailFont()
            g2.color = t.textMuted
            val dm = g2.fontMetrics
            g2.drawString(detail, x, (height + dm.ascent - dm.descent) / 2)
        }
        g2.dispose()
    }
}

/** Rounded, borderless always-on-top window. [outline] can carry a line colour. */
open class FloatingWindow(t: Tokens, width: Int, height: Int) : JDialog() {
    var outline: java.awt.Color = t.hairline
        set(value) {
            field = value
            body.repaint()
        }

    protected val body = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = t.surface
            g2.fillRect(0, 0, this.width, this.height)
            g2.color = outline
            g2.stroke = java.awt.BasicStroke(2f)
            g2.drawRoundRect(1, 1, this.width - 3, this.height - 3, 18, 18)
            g2.dispose()
        }
    }

    init {
        isUndecorated = true
        isAlwaysOnTop = true
        isModal = false
        type = Type.UTILITY
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        contentPane = body
        setSize(width, height)
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = clip()
        })
        clip()
    }

    private fun clip() {
        // Per-pixel shape so the corners are round on Windows (no square frame behind them).
        runCatching { shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 18.0, 18.0) }
    }
}

/**
 * Incoming call, bottom right. The ringing line is the first thing read: outline and top band in the line's
 * colour, a large line chip ("● BUSINESS (416) 555-0193"), and the Answer button names the line.
 * Enter / Ctrl Enter answers, Esc / Ctrl D declines.
 */
class IncomingWindow(
    private val t: Tokens,
    private val productName: String,
    private val mark: Icon?,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit,
) : FloatingWindow(t, 408, 262) {
    private var band = t.line1
    private val head = JLabel()
    private val chip = LineChip(t, big = true)
    private var avatar = Avatar(t, "", 52)
    private val avatarSlot = JPanel(BorderLayout())
    private val name = JLabel(" ")
    private val sub = JLabel(" ")
    private val answer = PillButton(t, "Answer", "phone", height = 48) { onAnswer() }
    private val decline = PillButton(t, "Decline", "hangup", height = 48) { onDecline() }

    init {
        title = "$productName incoming call"
        val inner = object : JPanel(BorderLayout(0, 12)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.color = band
                g2.fillRect(0, 0, width, 7)
                g2.dispose()
            }
        }
        inner.isOpaque = false
        inner.border = EmptyBorder(18, 18, 14, 18)
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.isOpaque = false
        head.icon = mark
        head.iconTextGap = 8
        head.font = Fonts.medium(Fonts.CAPTION + 0.5f)
        head.foreground = t.textMuted
        head.text = "$productName · Incoming call"
        head.alignmentX = LEFT_ALIGNMENT
        chip.alignmentX = LEFT_ALIGNMENT
        top.add(head)
        top.add(Box.createVerticalStrut(10))
        top.add(chip)
        val who = JPanel(BorderLayout(14, 0))
        who.isOpaque = false
        avatarSlot.isOpaque = false
        avatarSlot.add(avatar, BorderLayout.CENTER)
        who.add(avatarSlot, BorderLayout.WEST)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        name.foreground = t.text
        sub.font = Fonts.ui(Fonts.LABEL)
        sub.foreground = t.textMuted
        text.add(Box.createVerticalGlue())
        text.add(name)
        text.add(Box.createVerticalStrut(2))
        text.add(sub)
        text.add(Box.createVerticalGlue())
        who.add(text, BorderLayout.CENTER)
        answer.style(
            "arc: 14; borderWidth: 0; background: ${css(t.answer)}; foreground: ${css(t.onAnswer)};" +
                "hoverBackground: ${css(blend(t.answer, java.awt.Color.WHITE, 0.12f))}; focusedBorderColor: ${css(t.text)}",
        )
        answer.foreground = t.onAnswer
        answer.font = Fonts.bold(Fonts.BODY)
        answer.icon = Icons.get("phone", 18, t.onAnswer)
        answer.toolTipText = "Answer (Enter or Ctrl Enter)"
        decline.style(
            "arc: 14; borderWidth: 0; background: ${css(t.endCall)}; foreground: #ffffff;" +
                "hoverBackground: ${css(blend(t.endCall, java.awt.Color.WHITE, 0.1f))}; pressedBackground: ${css(t.endCallDeep)}",
        )
        decline.foreground = java.awt.Color.WHITE
        decline.icon = Icons.get("hangup", 18, java.awt.Color.WHITE)
        decline.toolTipText = "Decline (Esc or Ctrl D)"
        val buttons = JPanel(BorderLayout(10, 0))
        buttons.isOpaque = false
        buttons.add(answer, BorderLayout.CENTER)
        buttons.add(decline, BorderLayout.EAST)
        val hint = hintRow(t, Kbd(t, "Ctrl Enter"), "answers ·", Kbd(t, "Ctrl D"), "declines")
        hint.layout = FlowLayout(FlowLayout.LEFT, 4, 0)
        val south = JPanel(BorderLayout(0, 10))
        south.isOpaque = false
        south.add(buttons, BorderLayout.NORTH)
        south.add(hint, BorderLayout.SOUTH)
        inner.add(top, BorderLayout.NORTH)
        inner.add(who, BorderLayout.CENTER)
        inner.add(south, BorderLayout.SOUTH)
        body.add(inner, BorderLayout.CENTER)
        rootPane.defaultButton = answer
        fun key(stroke: String, name: String, action: () -> Unit) {
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke), name)
            rootPane.actionMap.put(name, object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
            })
        }
        key("ESCAPE", "decline", onDecline)
        key("ctrl D", "decline2", onDecline)
        key("ctrl ENTER", "answer", onAnswer)
        getAccessibleContext().accessibleName = "Incoming call"
    }

    fun show(card: CallCard) {
        band = t.line(card.lineColorIndex)
        outline = band
        chip.show(card.lineLabel, card.lineNumber, card.lineColorIndex)
        answer.text = (if (card.waiting) "Hold & answer · " else "Answer · ") + card.lineLabel
        answer.getAccessibleContext().accessibleName =
            (if (card.waiting) "Hold the current call and answer on " else "Answer on ") + card.lineLabel
        if (avatar.getClientProperty("for") != card.title) {
            avatarSlot.remove(avatar)
            avatar = Avatar(t, if (card.titleIsNumber) "" else card.title, 52)
            avatar.putClientProperty("for", card.title)
            avatarSlot.add(avatar, BorderLayout.CENTER)
        }
        name.text = card.title
        name.font = if (card.titleIsNumber) Fonts.monoMedium(Fonts.HEADING + 3) else Fonts.semibold(Fonts.HEADING + 2)
        sub.text = card.subtitle.ifBlank { " " }
        title = "${card.title} calling ${card.lineLabel}"
        getAccessibleContext().accessibleDescription = "${card.title} is calling ${card.lineLabel}"
        body.revalidate()
        repaint()
    }

    fun focusAnswer() = answer.requestFocusInWindow()
}

/** Mini call window, top right: line dot, name, line · timer, mute / end / open. */
class MiniCallWindow(
    private val t: Tokens,
    productName: String,
    onMute: () -> Unit,
    onHangup: () -> Unit,
    onOpen: () -> Unit,
) : FloatingWindow(t, 370, 68) {
    private val dot = Dot(t.line1, 9)
    private val name = JLabel(" ")
    private val sub = JLabel(" ")
    private val clock = JLabel(" ")
    private val mute = IconButton(t, "mic", 36, t.text, "Mute (M)") { onMute() }

    init {
        title = "$productName call"
        val inner = JPanel(BorderLayout(12, 0))
        inner.isOpaque = false
        inner.border = EmptyBorder(12, 16, 12, 12)
        inner.add(JPanel(BorderLayout()).apply { isOpaque = false; add(dot, BorderLayout.CENTER) }, BorderLayout.WEST)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        name.font = Fonts.semibold(Fonts.BODY - 0.5f)
        name.foreground = t.text
        sub.font = Fonts.ui(Fonts.CAPTION + 0.5f)
        sub.foreground = t.textMuted
        clock.font = Fonts.monoMedium(Fonts.CAPTION + 0.5f)
        clock.foreground = t.actionText
        val subRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        subRow.isOpaque = false
        subRow.add(sub)
        subRow.add(clock)
        name.alignmentX = LEFT_ALIGNMENT
        subRow.alignmentX = LEFT_ALIGNMENT
        text.add(name)
        text.add(subRow)
        inner.add(text, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        actions.isOpaque = false
        val end = IconButton(t, "hangup", 36, java.awt.Color.WHITE, "Hang up (Ctrl D)", filled = t.endCall) { onHangup() }
        end.style(
            "arc: 999; borderWidth: 0; background: ${css(t.endCall)}; hoverBackground: ${css(blend(t.endCall, java.awt.Color.WHITE, 0.1f))};" +
                "pressedBackground: ${css(t.endCallDeep)}",
        )
        val open = IconButton(t, "expand", 36, t.textMuted, "Open $productName") { onOpen() }
        open.style("arc: 10; borderWidth: 0; background: ${css(t.surface)}; hoverBackground: ${css(t.raised)}")
        actions.add(mute)
        actions.add(end)
        actions.add(open)
        inner.add(actions, BorderLayout.EAST)
        body.add(inner, BorderLayout.CENTER)
        focusableWindowState = false
    }

    fun show(card: CallCard, timer: String, muted: Boolean) {
        dot.color = t.line(card.lineColorIndex)
        name.text = card.title
        name.font = if (card.titleIsNumber) Fonts.monoMedium(Fonts.BODY - 0.5f) else Fonts.semibold(Fonts.BODY - 0.5f)
        sub.text = "${card.lineLabel} ·"
        clock.text = timer
        mute.setTint(if (muted) t.actionText else t.text)
        mute.icon = Icons.get(if (muted) "mic-off" else "mic", 18, if (muted) t.actionText else t.text)
        mute.toolTipText = if (muted) "Unmute (M)" else "Mute (M)"
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
