package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * In-call pane (`desktop-in-call.png`): line and number on top, avatar, name, number, timer, then one row of
 * round controls with their shortcut keys, and the headset row with a live meter. The pane takes the detail area
 * so the list stays usable during the call. GPL-2.0.
 */
class InCallPane(private val t: Tokens, private val actions: Actions, dtmf: JComponent) : JPanel() {
    interface Actions {
        fun answer()
        fun decline()
        fun mute()
        fun hold()
        fun transfer()
        fun keypad()
        fun end()
        fun device(anchor: JComponent)
    }

    enum class Mode { RINGING, CALLING, LIVE }

    data class State(
        val mode: Mode,
        val lineLabel: String,
        val lineNumber: String?,
        val lineColorIndex: Int,
        val title: String,
        val titleIsNumber: Boolean,
        val subtitle: String,
        val timer: String,
        val muted: Boolean,
        val held: Boolean,
        val keypadOpen: Boolean,
        val device: String?,
    )

    private val lineDot = Dot(t.line1, 7)
    private val lineText = JLabel(" ")
    /** While ringing: the large line chip, so the ringing line is unmistakable. */
    private val ringChip = LineChip(t, big = true)
    private var avatar = Avatar(t, "", 88)
    private val avatarSlot = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
    private val name = JLabel(" ", SwingConstants.CENTER)
    private val number = JLabel(" ", SwingConstants.CENTER)
    private val timer = JLabel(" ", SwingConstants.CENTER)
    private val mute = RoundControl(t, "mic-off", "Mute", "M") { actions.mute() }
    private val hold = RoundControl(t, "pause", "Hold", "H") { actions.hold() }
    private val transfer = RoundControl(t, "transfer", "Transfer", "T") { actions.transfer() }
    private val keypad = RoundControl(t, "keypad", "Keypad", "K") { actions.keypad() }
    private val end = RoundControl(t, "hangup", "End", "Ctrl D", danger = true) { actions.end() }
    private val answer = RoundControl(t, "phone", "Answer", "Ctrl Enter", answer = true) { actions.answer() }
    private val decline = RoundControl(t, "hangup", "Decline", "Ctrl D", danger = true) { actions.decline() }
    private val liveRow = JPanel(FlowLayout(FlowLayout.CENTER, 10, 0))
    private val ringRow = JPanel(FlowLayout(FlowLayout.CENTER, 64, 0))
    private val deviceRow = DeviceRow(t) { actions.device(it) }
    private var lastKey = ""

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = t.ground
        border = EmptyBorder(32, 24, 28, 24)
        val lineRow = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0))
        lineRow.isOpaque = false
        lineText.font = Type.ui(Type.LABEL + 0.5f)
        lineText.foreground = t.textMuted
        lineRow.add(lineDot)
        lineRow.add(lineText)
        lineRow.add(ringChip)
        ringChip.isVisible = false
        center(lineRow, 40)
        add(lineRow)
        add(Box.createVerticalStrut(18))
        avatarSlot.isOpaque = false
        avatarSlot.add(avatar)
        center(avatarSlot, 90)
        add(avatarSlot)
        add(Box.createVerticalStrut(18))
        name.font = Type.display(Type.DISPLAY)
        name.foreground = t.text
        center(name, 52)
        add(name)
        add(Box.createVerticalStrut(8))
        number.font = Type.mono(Type.BODY - 1)
        number.foreground = t.textMuted
        center(number, 22)
        add(number)
        add(Box.createVerticalStrut(14))
        timer.font = Type.monoMedium(Type.TIMER)
        timer.foreground = t.actionText
        center(timer, 28)
        add(timer)
        add(Box.createVerticalGlue())
        dtmf.alignmentX = CENTER_ALIGNMENT
        add(dtmf)
        add(Box.createVerticalStrut(18))
        ringRow.isOpaque = false
        ringRow.add(decline)
        ringRow.add(answer)
        center(ringRow, 130)
        add(ringRow)
        liveRow.isOpaque = false
        listOf(mute, hold, transfer, keypad, end).forEach { liveRow.add(it) }
        center(liveRow, 130)
        add(liveRow)
        add(Box.createVerticalStrut(20))
        deviceRow.alignmentX = CENTER_ALIGNMENT
        add(deviceRow)
    }

    private fun center(c: JComponent, height: Int) {
        c.alignmentX = CENTER_ALIGNMENT
        c.maximumSize = Dimension(Int.MAX_VALUE, height)
    }

    fun show(s: State) {
        val key = s.toString()
        if (key == lastKey) return
        lastKey = key
        lineDot.color = t.line(s.lineColorIndex)
        lineText.text = s.lineLabel + (s.lineNumber?.let { " · $it" } ?: "")
        val ringing = s.mode == Mode.RINGING
        ringChip.isVisible = ringing
        lineDot.isVisible = !ringing
        lineText.isVisible = !ringing
        if (ringing) {
            ringChip.show(s.lineLabel, s.lineNumber, s.lineColorIndex)
            answer.state(active = false, enabled = true, label = "Answer · ${s.lineLabel}", icon = "phone")
        }
        if (avatar.getClientProperty("for") != s.title) {
            avatarSlot.remove(avatar)
            avatar = Avatar(t, if (s.titleIsNumber) "" else s.title, 88)
            avatar.putClientProperty("for", s.title)
            avatarSlot.add(avatar)
        }
        name.text = s.title
        name.font = if (s.titleIsNumber) Type.mono(Type.TITLE) else Type.display(Type.DISPLAY)
        number.text = s.subtitle.ifBlank { " " }
        timer.text = when {
            s.mode == Mode.RINGING -> "Incoming call"
            s.mode == Mode.CALLING -> "Calling…"
            s.held -> "On hold · ${s.timer}"
            else -> s.timer.ifBlank { " " }
        }
        timer.foreground = if (s.mode == Mode.RINGING) t.answer else t.actionText
        ringRow.isVisible = s.mode == Mode.RINGING
        liveRow.isVisible = s.mode != Mode.RINGING
        val live = s.mode == Mode.LIVE
        mute.state(active = s.muted, enabled = live, label = if (s.muted) "Unmute" else "Mute", icon = if (s.muted) "mic-off" else "mic")
        hold.state(active = s.held, enabled = live, label = if (s.held) "Resume" else "Hold", icon = if (s.held) "play" else "pause")
        transfer.state(active = false, enabled = live, label = "Transfer", icon = "transfer")
        keypad.state(active = s.keypadOpen, enabled = live, label = "Keypad", icon = "keypad")
        deviceRow.show(s.device)
        revalidate()
        repaint()
    }

    fun micLevel(level: Int) = deviceRow.level(level)
}

/**
 * Round 72 px call control (design token call-btn): soft gradient fill, faint top highlight, no hard outline.
 * Active (muted, held) turns solid action colour; End and Decline are danger with a soft glow; Answer is green.
 * Label underneath, shortcut letter in quiet mono under that; the shortcut is also in the tooltip.
 */
class RoundControl(
    private val t: Tokens,
    private var iconName: String,
    private var label: String,
    private val shortcut: String,
    private val danger: Boolean = false,
    private val answer: Boolean = false,
    action: () -> Unit,
) : JPanel() {
    private val button = Disc()
    private val caption = JLabel(label, SwingConstants.CENTER)
    private val key = JLabel(shortcut, SwingConstants.CENTER)
    private var active = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        button.alignmentX = CENTER_ALIGNMENT
        button.addActionListener { action() }
        add(button)
        add(Box.createVerticalStrut(10))
        caption.font = Type.medium(Type.LABEL)
        caption.foreground = t.textMuted
        caption.alignmentX = CENTER_ALIGNMENT
        add(caption)
        add(Box.createVerticalStrut(2))
        key.font = Type.mono(10.5f)
        key.foreground = t.textDisabled
        key.alignmentX = CENTER_ALIGNMENT
        add(key)
        refresh()
    }

    fun state(active: Boolean, enabled: Boolean, label: String, icon: String) {
        this.active = active
        this.label = label
        this.iconName = icon
        caption.text = label
        caption.foreground = if (active) t.actionText else t.textMuted
        button.isEnabled = enabled
        refresh()
    }

    private fun refresh() {
        button.toolTipText = "$label ($shortcut)"
        button.getAccessibleContext().accessibleName = label
        button.repaint()
    }

    /** The painted disc. A JButton underneath keeps keyboard focus, Space/Enter and accessibility. */
    private inner class Disc : JButton() {
        private var hover = false
        private var down = false

        init {
            val size = 72 + GLOW * 2
            preferredSize = Dimension(size, size)
            maximumSize = preferredSize
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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

        override fun contains(x: Int, y: Int): Boolean {
            val c = width / 2.0
            return Math.hypot(x - c, y - c) <= 36
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val d = 72f
            val x = (width - d) / 2f
            val y = (height - d) / 2f
            val on = isEnabled
            val base = when {
                !on -> t.surface
                danger -> t.endCall
                answer -> t.answer
                active -> t.action
                else -> t.raised
            }
            val lifted = when {
                down -> blend(base, Color.BLACK, 0.12f)
                hover && on -> blend(base, Color.WHITE, if (danger || answer || active) 0.10f else 0.07f)
                else -> base
            }
            // Soft glow under coloured buttons (End, Answer, active): concentric translucent rings.
            if (on && (danger || answer || active)) {
                val strength = if (danger) 34 else 22
                for (i in GLOW downTo 1) {
                    g2.color = lifted.alpha((strength * (GLOW - i + 1) / GLOW).coerceAtMost(60))
                    val r = d + i * 2
                    g2.fill(java.awt.geom.Ellipse2D.Float(x - i, y - i + 3, r, r))
                }
            }
            val circle = java.awt.geom.Ellipse2D.Float(x, y, d, d)
            // End/Decline: vivid red to a deeper red, like current phone OSes. Others: a subtle lift.
            val bottom = if (danger && on) {
                if (down) t.endCallDeep.darker() else t.endCallDeep
            } else {
                blend(lifted, Color.BLACK, 0.10f)
            }
            g2.paint = java.awt.GradientPaint(0f, y, blend(lifted, Color.WHITE, if (danger) 0.04f else 0.06f), 0f, y + d, bottom)
            g2.fill(circle)
            // Faint highlight on the top edge only: depth without an outline.
            g2.paint = java.awt.GradientPaint(0f, y, Color(255, 255, 255, if (on) 34 else 14), 0f, y + d / 2, Color(255, 255, 255, 0))
            g2.stroke = java.awt.BasicStroke(1.2f)
            g2.draw(java.awt.geom.Ellipse2D.Float(x + 0.6f, y + 0.6f, d - 1.2f, d - 1.2f))
            if (isFocusOwner) {
                g2.color = t.action
                g2.stroke = java.awt.BasicStroke(2f)
                g2.draw(java.awt.geom.Ellipse2D.Float(x - 4, y - 4, d + 8, d + 8))
            }
            val ink = when {
                !on -> t.textDisabled
                danger -> Color.WHITE
                answer -> t.onAnswer
                active -> t.onAction
                else -> t.text
            }
            Icons.get(iconName, 26, ink).paintIcon(this, g2, (width - 26) / 2, (height - 26) / 2)
            g2.dispose()
        }
    }

    companion object {
        private const val GLOW = 8
    }
}

/** "🎧 Jabra Evolve2 65 · headset ▮▮▮▯ ⌄" row: the call speaker, its live meter, and the device picker. */
class DeviceRow(private val t: Tokens, onPick: (JComponent) -> Unit) : Card(t, 14) {
    private val name = JLabel(" ")
    private val meter = Bars(t)

    init {
        layout = BorderLayout(12, 0)
        border = EmptyBorder(9, 14, 9, 12)
        maximumSize = Dimension(360, 44)
        preferredSize = Dimension(360, 44)
        name.font = Type.medium(Type.LABEL + 1)
        name.foreground = t.text
        name.icon = Icons.get("headset", 18, t.textMuted)
        name.iconTextGap = 10
        add(name, BorderLayout.CENTER)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4))
        right.isOpaque = false
        right.add(meter)
        right.add(JLabel(Icons.get("chev-d", 16, t.textMuted)))
        add(right, BorderLayout.EAST)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Choose the speaker and microphone for this call"
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onPick(this@DeviceRow)
        })
    }

    fun show(device: String?) {
        isVisible = device != null
        val kind = when {
            device == null -> ""
            device.contains("headset", true) || device.contains("jabra", true) || device.contains("logitech", true) ||
                device.contains("plantronics", true) || device.contains("poly", true) || device.contains("usb", true) -> " · headset"
            else -> ""
        }
        // "Wave mapper" is the WinMM name for "whatever Windows uses by default".
        val shown = if (device?.contains("wave mapper", ignoreCase = true) == true) "Windows default" else device.orEmpty()
        name.text = "<html>${shown.replace("<", "&lt;")}<span style='color:${css(t.textCaption)}'>$kind</span></html>"
    }

    fun level(value: Int) = meter.level(value)

    /** Five-bar input meter (0–255 from PJSIP slot 0). */
    private class Bars(private val t: Tokens) : JComponent() {
        private var lit = 0

        init {
            preferredSize = Dimension(44, 16)
        }

        fun level(value: Int) {
            val next = ((value / 255.0) * 5 + 0.4).toInt().coerceIn(0, 5)
            if (next != lit) {
                lit = next
                repaint()
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            repeat(5) { i ->
                g2.color = if (i < lit) t.answer else t.hairline
                g2.fillRoundRect(i * 9, 1, 6, height - 2, 3, 3)
            }
            g2.dispose()
        }
    }
}

/** Gold card pinned at the top of Recents during a call, with Return (`desktop-in-call.png`). */
class ActiveCallCard(private val t: Tokens, onReturn: () -> Unit) : JPanel(BorderLayout(12, 0)) {
    private val name = JLabel(" ")
    private val dot = Dot(t.line1, 7)
    private val sub = JLabel(" ")
    private val timer = JLabel(" ")

    init {
        isOpaque = false
        border = EmptyBorder(12, 14, 12, 14)
        maximumSize = Dimension(Int.MAX_VALUE, 64)
        preferredSize = Dimension(340, 64)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(JLabel(Icons.get("phone", 20, t.actionText)), BorderLayout.WEST)
        name.font = Type.semibold(Type.BODY - 0.5f)
        name.foreground = t.text
        sub.font = Type.ui(Type.LABEL)
        sub.foreground = t.textMuted
        timer.font = Type.monoMedium(Type.LABEL)
        timer.foreground = t.actionText
        val subRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        subRow.isOpaque = false
        subRow.add(dot)
        subRow.add(sub)
        subRow.add(timer)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        name.alignmentX = LEFT_ALIGNMENT
        subRow.alignmentX = LEFT_ALIGNMENT
        text.add(name)
        text.add(Box.createVerticalStrut(3))
        text.add(subRow)
        add(text, BorderLayout.CENTER)
        add(JLabel("Return").apply { font = Type.semibold(Type.LABEL); foreground = t.actionText }, BorderLayout.EAST)
        toolTipText = "Return to the call"
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onReturn()
        })
        isVisible = false
    }

    fun show(title: String, lineLabel: String, colorIndex: Int, state: String, clock: String) {
        name.text = title
        dot.color = t.line(colorIndex)
        sub.text = "$lineLabel · $state"
        timer.text = clock
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = t.actionTint
        g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
        g2.color = t.action
        g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
        g2.dispose()
    }
}

internal fun Color.alpha(a: Int) = Color(red, green, blue, a)

/**
 * Call waiting (0.1.39), above the in-call controls. Ringing: the line it rings on and the caller, with
 * "Hold & answer · <line>" and Decline. Answered: the other call "On hold" with Swap and End. Hidden otherwise.
 */
class CallWaitingBar(
    private val t: Tokens,
    private val onAnswer: () -> Unit,
    private val onEnd: () -> Unit,
    private val onSwap: () -> Unit,
) : JPanel(BorderLayout(14, 0)) {
    private var outline: Color = t.hairline
    private val caption = JLabel(" ")
    private val title = JLabel(" ")
    private val dot = Dot(t.line1, 8)
    private val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
    private var key = ""

    init {
        isOpaque = false
        border = EmptyBorder(14, 18, 14, 14)
        caption.font = Type.semibold(Type.LABEL)
        title.font = Type.semibold(Type.BODY + 1)
        title.foreground = t.text
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        top.isOpaque = false
        top.add(dot)
        top.add(caption)
        top.alignmentX = LEFT_ALIGNMENT
        title.alignmentX = LEFT_ALIGNMENT
        text.add(top)
        text.add(Box.createVerticalStrut(4))
        text.add(title)
        add(text, BorderLayout.CENTER)
        buttons.isOpaque = false
        add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(buttons) }, BorderLayout.EAST)
        isVisible = false
        getAccessibleContext().accessibleName = "Second call"
    }

    /** [state]: 0 hide, 1 ringing, 2 on hold. Rebuilt only when something shown changes. */
    fun show(state: Int, caller: String, callerIsNumber: Boolean, lineLabel: String, lineColorIndex: Int) {
        val next = "$state|$caller|$lineLabel|$lineColorIndex"
        if (next == key) return
        key = next
        isVisible = state != 0
        if (state == 0) return
        val color = t.line(lineColorIndex)
        dot.color = color
        outline = if (state == 1) color else t.hairline
        caption.text = if (state == 1) "INCOMING ON ${lineLabel.uppercase()}" else "ON HOLD · ${lineLabel.uppercase()}"
        caption.foreground = if (state == 1) color else t.textMuted
        title.text = caller
        title.font = if (callerIsNumber) Type.monoMedium(Type.BODY + 1) else Type.semibold(Type.BODY + 1)
        buttons.removeAll()
        if (state == 1) {
            buttons.add(PillButton(t, "Hold & answer · $lineLabel", "phone", height = 42) { onAnswer() }.apply {
                style(
                    "arc: 12; focusWidth: 2; innerFocusWidth: 0; margin: 0,18,0,18; borderWidth: 0;" +
                        "background: ${css(t.answer)}; foreground: ${css(t.onAnswer)};" +
                        "hoverBackground: ${css(t.answer.brighter())}; pressedBackground: ${css(t.answer.darker())};" +
                        "focusColor: ${css(t.text)}",
                )
                icon = Icons.get("phone", 18, t.onAnswer)
                font = Type.semibold(Type.BODY)
                toolTipText = "Put the current call on hold and answer this one"
            })
            buttons.add(PillButton(t, "Decline", "hangup", height = 42) { onEnd() }.apply {
                toolTipText = "Send this call on as busy: your other devices, then voicemail"
            })
        } else {
            buttons.add(PillButton(t, "Swap", "transfer", height = 40) { onSwap() }.apply {
                toolTipText = "Hold the current call and talk to $caller"
            })
            buttons.add(PillButton(t, "End", "hangup", height = 40) { onEnd() }.apply {
                toolTipText = "Hang up the call on hold"
            })
        }
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = t.surface
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.color = outline
        g2.stroke = java.awt.BasicStroke(if (outline == t.hairline) 1f else 2f)
        g2.drawRoundRect(1, 1, width - 3, height - 3, 16, 16)
        g2.dispose()
    }
}
