package net.ithandsfree.softphone.win.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Calls › Voicemail (`voicemail.png`, `voicemail-empty.png`): messages from the PBX for both lines, filtered by line.
 * The open row becomes a player with a scrubber and Call back from <line>, Text, Save and Delete. The empty state
 * names where greetings live and dials the mailbox. GPL-2.0.
 */
class VoicemailPane(private val t: Tokens, private val actions: Actions) : JPanel(BorderLayout()) {
    interface Actions {
        fun filter(extension: String?)
        fun open(id: String)
        fun playPause(id: String)
        fun seek(id: String, fraction: Double)
        fun callBack(id: String)
        fun text(id: String)
        fun save(id: String)
        fun delete(id: String)
        fun callVoicemail(extension: String?)
    }

    data class Item(
        val id: String,
        val extension: String,
        val lineLabel: String,
        val lineColorIndex: Int,
        val title: String,
        val titleIsNumber: Boolean,
        val whenText: String,
        val durationText: String,
        val isNew: Boolean,
        /** Why Text is off for this caller or line, or null when it is allowed. */
        val textBlock: String?,
        val canCall: Boolean,
    )

    data class Model(
        val items: List<Item>,
        /** (extension or null for all, label, colour index or -1). */
        val filters: List<Triple<String?, String, Int>>,
        val filter: String?,
        val open: String?,
        /** Shown above the list or in place of it: permission off, PBX unreachable. */
        val notice: String?,
        val loading: Boolean,
        /** Line used by the "Call voicemail" button (the filtered line, else the default line). */
        val mailboxLabel: String?,
        /** The PBX's "My Voicemail" feature code (*97 by default); blank when the admin turned it off. */
        val dialCode: String,
    )

    private val chips = JPanel(WrapLayout(FlowLayout.LEFT, 8, 6))
    private val list = WidthTrackingPanel()
    private var player: Player? = null
    private var deleteArmed: String? = null

    init {
        background = t.ground
        chips.isOpaque = false
        chips.border = EmptyBorder(6, 16, 4, 16)
        add(chips, BorderLayout.NORTH)
        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        list.background = t.ground
        list.border = EmptyBorder(4, 12, 12, 12)
        add(JScrollPane(list).apply {
            border = EmptyBorder(0, 0, 0, 0)
            viewport.background = t.ground
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)
    }

    fun show(m: Model) {
        chips.removeAll()
        if (m.filters.size > 2) {
            m.filters.forEach { (ext, label, colorIndex) ->
                val chip = Chip(t, label) { actions.filter(ext) }
                chip.selected2 = ext == m.filter
                if (colorIndex >= 0) chip.icon = DotIcon(t.line(colorIndex))
                chips.add(chip)
            }
        }
        chips.isVisible = chips.componentCount > 0
        list.removeAll()
        player = null
        val shown = m.items.filter { m.filter == null || it.extension == m.filter }
        m.notice?.let { list.add(left(noticeLabel(it))); list.add(Box.createVerticalStrut(10)) }
        when {
            m.loading && shown.isEmpty() -> list.add(left(caption("Loading voicemail…")))
            shown.isEmpty() -> list.add(emptyState(m))
            else -> {
                shown.forEach { item ->
                    list.add(if (item.id == m.open) openCard(item) else row(item))
                    list.add(Box.createVerticalStrut(if (item.id == m.open) 8 else 0))
                }
                list.add(Box.createVerticalStrut(14))
                list.add(left(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(PillButton(t, callLabel(m), "vm", height = 38) { actions.callVoicemail(m.filter) }.apply { isEnabled = m.dialCode.isNotBlank() })
                    add(Box.createHorizontalStrut(12))
                    add(JLabel("Record greetings or use the mailbox menu").apply {
                        font = Type.ui(Type.CAPTION + 0.5f)
                        foreground = t.textCaption
                    })
                    maximumSize = Dimension(Int.MAX_VALUE, 40)
                }))
            }
        }
        list.add(Box.createVerticalGlue())
        list.revalidate()
        list.repaint()
    }

    /** Player position for the open message; called while it plays. */
    fun progress(id: String, positionMs: Long, lengthMs: Long, playing: Boolean) {
        player?.takeIf { it.id == id }?.update(positionMs, lengthMs, playing)
    }

    private fun callLabel(m: Model) = if (m.dialCode.isBlank()) "Call voicemail" else "Call voicemail (${m.dialCode})"

    // ---- pieces ------------------------------------------------------------------------------------------------

    private fun left(c: JComponent) = c.also { it.alignmentX = Component.LEFT_ALIGNMENT }

    private fun caption(text: String) = JLabel("<html>$text</html>").apply {
        font = Type.ui(Type.LABEL)
        foreground = t.textMuted
    }

    private fun noticeLabel(text: String) = JLabel("<html>$text</html>").apply {
        font = Type.ui(Type.LABEL)
        foreground = t.textMuted
        border = EmptyBorder(8, 4, 0, 4)
        maximumSize = Dimension(Int.MAX_VALUE, 80)
    }

    private fun subLine(item: Item): JComponent {
        val sub = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        sub.isOpaque = false
        sub.add(Dot(t.line(item.lineColorIndex), 7))
        sub.add(JLabel("${item.lineLabel} · ${item.whenText}").apply {
            font = Type.ui(Type.LABEL)
            foreground = t.textMuted
        })
        return sub
    }

    private fun titleLabel(item: Item, size: Float) = JLabel(item.title).apply {
        font = if (item.titleIsNumber) Type.monoMedium(size) else Type.semibold(size)
        foreground = t.text
    }

    /** Closed row: play button, caller, line · when, length and a new-message dot. */
    private fun row(item: Item): JComponent {
        val row = object : JPanel(BorderLayout(14, 0)) {
            var hover = false
            override fun paintComponent(g: Graphics) {
                if (hover) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = t.surface
                    g2.fillRoundRect(0, 0, width, height, 14, 14)
                    g2.dispose()
                }
            }
        }
        row.isOpaque = false
        row.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, t.divider),
            EmptyBorder(10, 8, 10, 10),
        )
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.getAccessibleContext().accessibleName =
            "${if (item.isNew) "New voicemail" else "Voicemail"} from ${item.title} on ${item.lineLabel}, ${item.whenText}, ${item.durationText}"
        row.add(CircleButton(t, "play", 40, t.surface, t.text, "Play voicemail from ${item.title}", outline = t.hairline) {
            actions.open(item.id)
            actions.playPause(item.id)
        }, BorderLayout.WEST)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(left(titleLabel(item, Type.BODY)))
        text.add(Box.createVerticalStrut(3))
        text.add(left(subLine(item)))
        row.add(text, BorderLayout.CENTER)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        right.add(JLabel(item.durationText).apply { font = Type.mono(Type.LABEL); foreground = t.textMuted })
        if (item.isNew) right.add(Dot(t.action, 8).apply { toolTipText = "New" })
        row.add(JPanel(BorderLayout()).apply { isOpaque = false; add(right, BorderLayout.CENTER) }, BorderLayout.EAST)
        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = actions.open(item.id)
            override fun mouseEntered(e: java.awt.event.MouseEvent) { row.hover = true; row.repaint() }
            override fun mouseExited(e: java.awt.event.MouseEvent) { row.hover = false; row.repaint() }
        })
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, 68)
        return row
    }

    /** Open message: caller, player with scrubber, and the actions. */
    private fun openCard(item: Item): JComponent {
        // Height follows the content (the action row may wrap once the pane's width is known).
        val card = object : Card(t, Space.R_CARD, t.surface) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = EmptyBorder(14, 16, 14, 16)
        card.alignmentX = Component.LEFT_ALIGNMENT

        val head = JPanel(BorderLayout(12, 0))
        head.isOpaque = false
        head.add(Avatar(t, if (item.titleIsNumber) "" else item.title, 38), BorderLayout.WEST)
        val who = JPanel()
        who.layout = BoxLayout(who, BoxLayout.Y_AXIS)
        who.isOpaque = false
        who.add(left(titleLabel(item, Type.BODY + 1)))
        who.add(Box.createVerticalStrut(3))
        who.add(left(subLine(item)))
        head.add(who, BorderLayout.CENTER)
        if (item.isNew) head.add(JPanel(BorderLayout()).apply { isOpaque = false; add(Dot(t.action, 8), BorderLayout.NORTH) }, BorderLayout.EAST)
        card.add(left(head.also { it.maximumSize = Dimension(Int.MAX_VALUE, 52) }))
        card.add(Box.createVerticalStrut(12))

        val p = Player(item)
        player = p
        card.add(left(p))
        card.add(Box.createVerticalStrut(14))

        // Wraps onto a second row in a narrow pane so Download and Delete are never cut off.
        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 8, 6))
        buttons.border = EmptyBorder(0, -8, 0, -8)
        buttons.isOpaque = false
        val call = PillButton(t, "Call back from ${item.lineLabel}", "phone", primary = true, height = 42) { actions.callBack(item.id) }
        call.isEnabled = item.canCall
        if (!item.canCall) call.toolTipText = "Caller ID withheld"
        buttons.add(call)
        val text = PillButton(t, "Text", height = 42) { actions.text(item.id) }
        text.isEnabled = item.textBlock == null
        item.textBlock?.let { text.toolTipText = it }
        buttons.add(text)
        buttons.add(PillButton(t, "Download", "download", height = 42) { actions.save(item.id) }.apply {
            toolTipText = "Save this voicemail as a WAV file"
        })
        if (deleteArmed == item.id) {
            buttons.add(PillButton(t, "Delete", "trash", danger = true, height = 42) {
                deleteArmed = null
                actions.delete(item.id)
            })
        } else {
            buttons.add(IconButton(t, "trash", 42, t.textMuted, "Delete this voicemail") {
                deleteArmed = item.id
                // Second press within 4 s deletes; the PBX keeps no copy, so there is no undo.
                javax.swing.Timer(4000) { if (deleteArmed == item.id) { deleteArmed = null; actions.open(item.id) } }
                    .apply { isRepeats = false; start() }
                actions.open(item.id)
            })
        }
        card.add(left(buttons))
        item.textBlock?.let {
            card.add(Box.createVerticalStrut(10))
            card.add(left(JLabel("<html>$it</html>").apply { font = Type.ui(Type.CAPTION + 0.5f); foreground = t.textCaption }))
        }
        return card
    }

    private fun emptyState(m: Model): JComponent {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.isOpaque = false
        box.border = EmptyBorder(48, 24, 24, 24)
        box.alignmentX = Component.LEFT_ALIGNMENT
        val icon = object : JComponent() {
            init { preferredSize = Dimension(72, 72); maximumSize = preferredSize }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = t.surface
                g2.fillOval(0, 0, 72, 72)
                Icons.get("vm", 28, t.textMuted).paintIcon(this, g2, 22, 22)
                g2.dispose()
            }
        }
        icon.alignmentX = Component.CENTER_ALIGNMENT
        box.add(icon)
        box.add(Box.createVerticalStrut(18))
        box.add(JLabel("No voicemail", SwingConstants.CENTER).apply {
            font = Type.display(Type.TITLE_SM + 4)
            foreground = t.text
            alignmentX = Component.CENTER_ALIGNMENT
        })
        box.add(Box.createVerticalStrut(10))
        val lines = m.filters.filter { it.first != null }.joinToString(" or ") { it.second }.ifBlank { "your lines" }
        box.add(CenteredText(
            "Messages left on $lines appear here with the line they were left on." +
                (if (m.dialCode.isNotBlank()) " Greetings are recorded on the PBX by dialling ${m.dialCode}." else ""),
            t.textMuted,
        ))
        box.add(Box.createVerticalStrut(20))
        box.add(PillButton(t, callLabel(m), "vm", height = 44) { actions.callVoicemail(m.filter) }.apply {
            isVisible = m.dialCode.isNotBlank()
            alignmentX = Component.CENTER_ALIGNMENT
            m.mailboxLabel?.let { toolTipText = "Dials the mailbox of $it" }
        })
        return box
    }

    /** Play / pause disc, a scrubber you can click or drag, and elapsed / total time. */
    private inner class Player(item: Item) : JPanel(BorderLayout(12, 0)) {
        val id = item.id
        private var playing = false
        private val button = CircleButton(t, "play", 44, t.action, t.onAction, "Play") { actions.playPause(id) }
        private val bar = Scrubber { fraction -> actions.seek(id, fraction) }
        private val elapsed = JLabel("0:00").apply { font = Type.mono(Type.CAPTION); foreground = t.textMuted }
        private val total = JLabel(item.durationText).apply { font = Type.mono(Type.CAPTION); foreground = t.textMuted }

        init {
            isOpaque = false
            add(button, BorderLayout.WEST)
            val middle = JPanel(BorderLayout(0, 2))
            middle.isOpaque = false
            middle.add(bar, BorderLayout.CENTER)
            val times = JPanel(BorderLayout())
            times.isOpaque = false
            times.add(elapsed, BorderLayout.WEST)
            times.add(total, BorderLayout.EAST)
            middle.add(times, BorderLayout.SOUTH)
            add(middle, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 56)
        }

        fun update(positionMs: Long, lengthMs: Long, nowPlaying: Boolean) {
            if (playing != nowPlaying) {
                playing = nowPlaying
                button.glyph(if (nowPlaying) "pause" else "play")
                button.toolTipText = if (nowPlaying) "Pause" else "Play"
            }
            elapsed.text = clock(positionMs)
            if (lengthMs > 0) total.text = clock(lengthMs)
            bar.fraction = if (lengthMs > 0) positionMs.toDouble() / lengthMs else 0.0
        }

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }

    private inner class Scrubber(private val onSeek: (Double) -> Unit) : AccessibleControl(javax.accessibility.AccessibleRole.SLIDER) {
        var fraction = 0.0
            set(value) {
                field = value.coerceIn(0.0, 1.0)
                repaint()
            }

        init {
            preferredSize = Dimension(200, 22)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            getAccessibleContext().accessibleName = "Position"
            val mouse = object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) = seekTo(e.x)
                override fun mouseDragged(e: java.awt.event.MouseEvent) = seekTo(e.x)
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        private fun seekTo(x: Int) {
            val inner = (width - 14).coerceAtLeast(1)
            fraction = ((x - 7).toDouble() / inner)
            onSeek(fraction)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val y = height / 2
            val x0 = 7
            val x1 = width - 7
            g2.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.color = t.hairline
            g2.drawLine(x0, y, x1, y)
            val x = x0 + ((x1 - x0) * fraction).toInt()
            g2.color = t.action
            g2.drawLine(x0, y, x, y)
            g2.fillOval(x - 7, y - 7, 14, 14)
            g2.dispose()
        }
    }

    /** Small coloured dot used as a chip icon. */
    private class DotIcon(private val color: java.awt.Color) : javax.swing.Icon {
        override fun getIconWidth() = 8
        override fun getIconHeight() = 8
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, 8, 8)
            g2.dispose()
        }
    }
}

/**
 * Centred paragraph that wraps to the width it is given (HTML labels with a fixed CSS width do not follow Windows
 * display scaling and were cut off on a 125 % screen).
 */

private class CenteredText(text: String, ink: java.awt.Color) : javax.swing.JTextPane() {
    init {
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = EmptyBorder(0, 0, 0, 0)
        font = Type.ui(Type.BODY - 1)
        foreground = ink
        val center = javax.swing.text.SimpleAttributeSet()
        javax.swing.text.StyleConstants.setAlignment(center, javax.swing.text.StyleConstants.ALIGN_CENTER)
        styledDocument.setParagraphAttributes(0, 0, center, false)
        this.text = text
        styledDocument.setParagraphAttributes(0, styledDocument.length, center, false)
        alignmentX = Component.CENTER_ALIGNMENT
        maximumSize = Dimension(360, Int.MAX_VALUE)
    }
}
