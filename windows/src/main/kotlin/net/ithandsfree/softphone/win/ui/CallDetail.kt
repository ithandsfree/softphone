package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

/**
 * Recent call detail (`desktop-call-detail.png`): who, which line and when; Call back from the line the call
 * used (arrow for the other line), Message, Copy number, Open or Add contact; then the history with that number.
 * Esc or Keypad returns to the keypad. GPL-2.0.
 */
class CallDetailPane(private val t: Tokens, private val actions: Actions) : JPanel(BorderLayout()) {
    interface Actions {
        fun callBack()
        fun callFrom(extension: String)
        fun message()
        fun copy()
        fun contact()
        fun keypad()
        fun openThread()
        fun playRecording(callId: String)
        fun saveRecording(callId: String)
    }

    private var playing: String? = null
    private var lastModel: Model? = null

    /** Shows Stop on the recording that is playing (null when nothing plays). */
    fun playing(callId: String?) {
        if (playing == callId) return
        playing = callId
        lastModel?.let { show(it) }
    }

    data class History(
        val icon: String,
        val text: String,
        val mono: Boolean,
        val danger: Boolean,
        val lineLabel: String?,
        val lineColorIndex: Int?,
        val whenText: String,
        val openable: Boolean = false,
        /** PBX call id when the call has a recording the user may play. */
        val recordingId: String? = null,
        val canDownload: Boolean = false,
    )

    data class Model(
        val title: String,
        val titleIsNumber: Boolean,
        val subtitle: String,
        val statusIcon: String,
        val statusText: String,
        val statusDanger: Boolean,
        val lineLabel: String,
        val lineColorIndex: Int,
        val lineNumber: String?,
        val whenText: String,
        val callLabel: String,
        val otherLines: List<Pair<String, String>>,
        val canCall: Boolean,
        val callTip: String,
        val canMessage: Boolean,
        val messageTip: String,
        val hasContact: Boolean,
        val explanation: String,
        val historyTitle: String,
        val history: List<History>,
    )

    private val body = JPanel()

    init {
        background = t.ground
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.background = t.ground
        body.border = EmptyBorder(32, 40, 24, 40)
        val scroll = JScrollPane(body)
        scroll.border = EmptyBorder(0, 0, 0, 0)
        scroll.viewport.background = t.ground
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.verticalScrollBar.unitIncrement = 16
        add(scroll, BorderLayout.CENTER)
        val hints = hintRow(
            t,
            Kbd(t, "Enter"), "call back ·", Kbd(t, "M"), "message ·", Kbd(t, "Ctrl Shift C"),
            "copy number · right-click any recent for the same actions",
        )
        hints.border = EmptyBorder(8, 0, 16, 0)
        add(hints, BorderLayout.SOUTH)
    }

    fun show(m: Model) {
        lastModel = m
        body.removeAll()
        fun left(c: JComponent) = c.also { it.alignmentX = Component.LEFT_ALIGNMENT }

        val header = JPanel(BorderLayout(20, 0))
        header.isOpaque = false
        header.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(Avatar(t, if (m.titleIsNumber) "" else m.title, 72), BorderLayout.NORTH)
        }, BorderLayout.WEST)
        val who = JPanel()
        who.layout = BoxLayout(who, BoxLayout.Y_AXIS)
        who.isOpaque = false
        who.add(left(JLabel(m.title).apply {
            font = if (m.titleIsNumber) Type.mono(Type.TITLE) else Type.display(Type.DISPLAY)
            foreground = t.text
        }))
        if (m.subtitle.isNotBlank()) {
            who.add(Box.createVerticalStrut(2))
            who.add(left(JLabel(m.subtitle).apply { font = Type.mono(Type.BODY - 1); foreground = t.textMuted }))
        }
        who.add(Box.createVerticalStrut(8))
        val status = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        status.isOpaque = false
        val tone = if (m.statusDanger) t.danger else t.textMuted
        status.add(JLabel(m.statusText, Icons.get(m.statusIcon, 16, tone), JLabel.LEFT).apply {
            font = Type.medium(Type.LABEL + 1)
            foreground = tone
            iconTextGap = 6
        })
        status.add(Dot(t.line(m.lineColorIndex), 7))
        status.add(JLabel(m.lineLabel).apply { font = Type.semibold(Type.LABEL + 1); foreground = t.text })
        m.lineNumber?.let { status.add(JLabel(it).apply { font = Type.mono(Type.LABEL); foreground = t.textMuted }) }
        status.add(JLabel("· ${m.whenText}").apply { font = Type.mono(Type.LABEL); foreground = tone })
        who.add(left(status))
        header.add(who, BorderLayout.CENTER)
        val keypad = PillButton(t, "Keypad", "keypad", height = 40) { actions.keypad() }
        keypad.font = Type.semibold(Type.LABEL + 1)
        val keypadBox = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        keypadBox.isOpaque = false
        keypadBox.add(keypad)
        keypad.toolTipText = "Back to the keypad (Esc)"
        header.add(keypadBox, BorderLayout.EAST)
        header.maximumSize = Dimension(Int.MAX_VALUE, header.preferredSize.height)
        body.add(left(header))
        body.add(Box.createVerticalStrut(24))

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.isOpaque = false
        val call = PillButton(t, m.callLabel, "phone", primary = true, height = 48) { actions.callBack() }
        call.isEnabled = m.canCall
        call.toolTipText = m.callTip
        row.add(call)
        if (m.otherLines.isNotEmpty()) {
            row.add(Box.createHorizontalStrut(4))
            val more = IconButton(t, "chev-d", 48, t.onAction, "Call from another line", filled = t.action) {}
            more.isEnabled = m.canCall
            more.addActionListener {
                val menu = JPopupMenu()
                m.otherLines.forEach { (ext, label) ->
                    val item = javax.swing.JMenuItem("Call from $label", Icons.get("phone", 16, t.text))
                    item.font = Type.ui(Type.LABEL + 1)
                    item.addActionListener { actions.callFrom(ext) }
                    menu.add(item)
                }
                menu.show(more, 0, more.height + 4)
            }
            row.add(more)
        }
        row.add(Box.createHorizontalStrut(12))
        val message = PillButton(t, "Message", "msg", height = 48) { actions.message() }
        message.isEnabled = m.canMessage
        message.toolTipText = m.messageTip
        row.add(message)
        row.add(Box.createHorizontalStrut(12))
        row.add(PillButton(t, "Copy number", "clipboard", height = 48) { actions.copy() })
        row.add(Box.createHorizontalStrut(12))
        row.add(PillButton(t, if (m.hasContact) "Open contact" else "Add to contacts", if (m.hasContact) "user" else "user-plus", height = 48) {
            actions.contact()
        })
        row.maximumSize = Dimension(Int.MAX_VALUE, 52)
        body.add(left(row))
        body.add(Box.createVerticalStrut(16))
        body.add(left(JLabel(m.explanation).apply { font = Type.ui(Type.BODY - 1); foreground = t.textMuted }))
        body.add(Box.createVerticalStrut(28))
        body.add(left(sectionLabel(t, m.historyTitle)))

        val card = Card(t, 16)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = EmptyBorder(2, 0, 2, 0)
        m.history.forEachIndexed { index, h ->
            if (index > 0) card.add(JPanel().apply {
                background = t.divider
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                preferredSize = Dimension(10, 1)
            })
            card.add(historyRow(h))
        }
        card.maximumSize = Dimension(640, card.preferredSize.height)
        body.add(left(card))
        body.add(Box.createVerticalGlue())
        body.revalidate()
        body.repaint()
    }

    private fun historyRow(h: History): JComponent {
        val row = JPanel(BorderLayout(12, 0))
        row.isOpaque = false
        row.border = EmptyBorder(12, 16, 12, 16)
        val tone = if (h.danger) t.danger else t.text
        row.add(JLabel(h.text, Icons.get(h.icon, 18, if (h.danger) t.danger else t.textMuted), JLabel.LEFT).apply {
            font = if (h.mono) Type.mono(Type.LABEL + 1) else Type.medium(Type.LABEL + 1)
            foreground = tone
            iconTextGap = 12
        }, BorderLayout.CENTER)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        if (h.lineLabel != null && h.lineColorIndex != null) {
            right.add(Dot(t.line(h.lineColorIndex), 7))
            right.add(JLabel(h.lineLabel).apply { font = Type.ui(Type.LABEL); foreground = t.textMuted })
            right.add(Box.createHorizontalStrut(14))
        }
        right.add(JLabel(h.whenText).apply { font = Type.mono(Type.LABEL); foreground = t.textMuted })
        val rec = h.recordingId
        if (rec != null) {
            right.add(Box.createHorizontalStrut(6))
            val isPlaying = playing == rec
            val play = IconButton(
                t,
                if (isPlaying) "square" else "play",
                32,
                t.actionText,
                if (isPlaying) "Stop the recording" else "Play the call recording",
            ) { actions.playRecording(rec) }
            play.getAccessibleContext().accessibleName = if (isPlaying) "Stop recording" else "Play recording"
            right.add(play)
            if (h.canDownload) {
                val save = IconButton(t, "download", 32, t.textMuted, "Save the recording") { actions.saveRecording(rec) }
                save.getAccessibleContext().accessibleName = "Save recording"
                right.add(save)
            }
        }
        if (h.openable) {
            right.add(Box.createHorizontalStrut(6))
            right.add(JLabel("Open").apply {
                font = Type.semibold(Type.LABEL)
                foreground = t.actionText
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) = actions.openThread()
                })
            })
        }
        row.add(right, BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, 48)
        return row
    }
}
