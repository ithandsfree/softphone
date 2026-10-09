package net.ithandsfree.softphone.win.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/**
 * Lines page: line cards on the left (round-3 `lines-home.png`), the chosen line's settings on the right
 * (`line-detail.png`), laid out as two panes for the desktop. Entitlements are read-only and labelled as
 * managed by the admin; the app never shows a toggle it cannot honour. GPL-2.0.
 */
class LinesPage(private val t: Tokens, private val actions: Actions) : JPanel(BorderLayout()) {
    interface Actions {
        fun select(extension: String)
        fun makeDefault(extension: String)
        fun setDnd(extension: String, on: Boolean)
        fun rename(extension: String, label: String)
        fun ringtone(extension: String, id: String?)
        fun playRingtone(extension: String)
        fun signIn(extension: String)
        fun signOut(extension: String)
        fun setUpAgain(extension: String)
        fun remove(extension: String)
        fun addLine()
    }

    data class Caps(val voice: Boolean, val sms: Boolean, val mms: Boolean)

    data class Line(
        val extension: String,
        val label: String,
        val number: String?,
        val colorIndex: Int,
        val isDefault: Boolean,
        val dnd: Boolean?,
        val caps: Caps?,
        val missed: Int,
        val unread: Int,
        val registered: Boolean,
        val registration: String,
        val signedInAs: String?,
        val ringtoneId: String?,
    )

    data class Model(val lines: List<Line>, val selected: String?, val maxLines: Int, val ringtones: List<Pair<String, String>>)

    private val cards = JPanel()
    private val detail = JPanel()
    private var model: Model? = null
    private var confirmRemove: String? = null
    private var renaming: String? = null
    private val left = JPanel()
    private val detailScroll: JScrollPane
    private val back = PillButton(t, "All lines", "chev-l", height = 34) { closeDetail() }
    private var compact = false
    private var showingDetail = false

    init {
        background = t.ground
        left.layout = BorderLayout()
        left.background = t.ground
        left.preferredSize = Dimension(440, 200)
        left.border = javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider)
        val head = JPanel()
        head.layout = BoxLayout(head, BoxLayout.Y_AXIS)
        head.isOpaque = false
        head.border = EmptyBorder(14, 20, 6, 20)
        head.add(JLabel("Lines").apply {
            font = Type.display(Type.TITLE)
            foreground = t.text
            alignmentX = Component.LEFT_ALIGNMENT
        })
        head.add(Box.createVerticalStrut(6))
        head.add(JLabel("<html>Choose a line to change it. Presence is set on your PBX, so your desk phone follows.</html>").apply {
            font = Type.ui(Type.BODY - 1)
            foreground = t.textMuted
            alignmentX = Component.LEFT_ALIGNMENT
        })
        left.add(head, BorderLayout.NORTH)
        cards.layout = BoxLayout(cards, BoxLayout.Y_AXIS)
        cards.background = t.ground
        cards.border = EmptyBorder(12, 20, 20, 20)
        left.add(scroll(cards), BorderLayout.CENTER)
        detail.layout = BoxLayout(detail, BoxLayout.Y_AXIS)
        detail.background = t.ground
        detail.border = EmptyBorder(24, 36, 28, 36)
        add(left, BorderLayout.WEST)
        detailScroll = scroll(detail)
        add(detailScroll, BorderLayout.CENTER)
        back.alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Compact window: the line cards fill the window; choosing one opens its detail with a way back. */
    fun compact(on: Boolean) {
        compact = on
        showingDetail = false
        relayout()
    }

    private fun pick(extension: String) {
        if (compact) showingDetail = true
        actions.select(extension)
        relayout()
    }

    private fun closeDetail() {
        showingDetail = false
        relayout()
    }

    private fun relayout() {
        removeAll()
        if (!compact) {
            left.preferredSize = Dimension(440, 200)
            left.border = javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider)
            detail.border = EmptyBorder(24, 36, 28, 36)
            add(left, BorderLayout.WEST)
            add(detailScroll, BorderLayout.CENTER)
        } else if (showingDetail) {
            detail.border = EmptyBorder(12, 20, 20, 20)
            add(detailScroll, BorderLayout.CENTER)
        } else {
            left.preferredSize = null
            left.border = EmptyBorder(0, 0, 0, 0)
            add(left, BorderLayout.CENTER)
        }
        if (compact && showingDetail && detail.componentCount > 0 && detail.getComponent(0) !== back) {
            detail.add(back, 0)
            detail.add(Box.createVerticalStrut(12), 1)
        }
        revalidate()
        repaint()
    }

    private fun scroll(c: JComponent) = JScrollPane(c).apply {
        border = EmptyBorder(0, 0, 0, 0)
        viewport.background = t.ground
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
    }

    fun show(m: Model) {
        if (m == model) return
        model = m
        cards.removeAll()
        m.lines.forEach { line ->
            cards.add(lineCard(line, line.extension == m.selected))
            cards.add(Box.createVerticalStrut(12))
        }
        cards.add(addCard(m.lines.size, m.maxLines))
        cards.add(Box.createVerticalGlue())
        cards.revalidate()
        cards.repaint()
        detail.removeAll()
        val chosen = m.lines.firstOrNull { it.extension == m.selected } ?: m.lines.firstOrNull()
        if (chosen == null) {
            detail.add(left(JLabel("No line on this PC yet.").apply { font = Type.ui(Type.BODY); foreground = t.textMuted }))
        } else {
            fillDetail(chosen, m.ringtones)
        }
        detail.add(Box.createVerticalGlue())
        if (compact) relayout()
        detail.revalidate()
        detail.repaint()
    }

    /** Forces the next [show] to rebuild (after a rename or a confirm prompt). */
    private fun redraw() {
        val m = model ?: return
        model = null
        show(m)
    }

    // ---- left: cards -------------------------------------------------------------------------------------

    private fun lineCard(line: Line, selected: Boolean): JComponent {
        val card = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (selected) t.surface else blend(t.ground, t.surface, 0.55f)
                g2.fillRoundRect(0, 0, width - 1, height - 1, 18, 18)
                g2.color = when {
                    line.isDefault -> t.action
                    selected -> t.selected
                    else -> t.hairline
                }
                g2.stroke = BasicStroke(if (selected || line.isDefault) 1.5f else 1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 18, 18)
                g2.dispose()
            }
        }
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.isOpaque = false
        card.border = EmptyBorder(16, 18, 14, 18)
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        card.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = pick(line.extension)
        })
        card.getAccessibleContext().accessibleName = "${line.label} line ${line.extension}" + if (line.isDefault) ", default" else ""

        val top = JPanel(BorderLayout(10, 0))
        top.isOpaque = false
        val name = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        name.isOpaque = false
        name.add(Dot(t.line(line.colorIndex), 10))
        name.add(JLabel(line.label).apply { font = Type.display(Type.TITLE_SM + 2); foreground = t.text })
        top.add(name, BorderLayout.WEST)
        if (line.isDefault) {
            top.add(JLabel("DEFAULT").apply { font = Type.bold(Type.CAPTION); foreground = t.actionText }, BorderLayout.EAST)
        } else {
            val make = PillButton(t, "Make default", height = 34) { actions.makeDefault(line.extension) }
            make.font = Type.semibold(Type.LABEL)
            make.foreground = t.actionText
            make.style("arc: 10; margin: 0,12,0,12; borderColor: ${css(t.action)}; background: ${css(t.surface)}; foreground: ${css(t.actionText)}")
            make.toolTipText = "Use ${line.label} for new calls and messages"
            top.add(make, BorderLayout.EAST)
        }
        card.add(left(top.also { it.maximumSize = Dimension(Int.MAX_VALUE, 44) }))
        card.add(Box.createVerticalStrut(4))
        card.add(left(JLabel("ext ${line.extension}" + (line.number?.let { " · $it" } ?: "")).apply {
            font = Type.mono(Type.LABEL + 0.5f)
            foreground = t.textMuted
        }))
        card.add(Box.createVerticalStrut(12))
        card.add(left(presence(line)))
        card.add(Box.createVerticalStrut(6))
        card.add(left(JLabel(presenceCaption(line)).apply { font = Type.ui(Type.CAPTION); foreground = t.textCaption }))
        card.add(Box.createVerticalStrut(12))
        val bottom = JPanel(BorderLayout())
        bottom.isOpaque = false
        val tags = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        tags.isOpaque = false
        val caps = line.caps
        if (caps != null) {
            if (caps.voice) tags.add(Tag(t, "Voice", true))
            tags.add(Tag(t, if (caps.sms) "SMS" else "SMS off · admin", caps.sms))
            if (caps.sms) tags.add(Tag(t, "MMS", caps.mms))
        }
        bottom.add(tags, BorderLayout.WEST)
        val counts = JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0))
        counts.isOpaque = false
        if (line.missed > 0) counts.add(JLabel("${line.missed} missed").apply { font = Type.semibold(Type.LABEL); foreground = t.danger })
        if (line.unread > 0) counts.add(JLabel("${line.unread} unread").apply { font = Type.semibold(Type.LABEL); foreground = t.actionText })
        bottom.add(counts, BorderLayout.EAST)
        bottom.maximumSize = Dimension(Int.MAX_VALUE, 30)
        card.add(left(bottom))
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return left(card)
    }

    private fun addCard(used: Int, max: Int): JComponent {
        val full = used >= max
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = t.hairline
                g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(5f, 5f), 0f)
                g2.drawRoundRect(1, 1, width - 3, height - 3, 18, 18)
                g2.dispose()
            }
        }
        card.isOpaque = false
        card.border = EmptyBorder(16, 18, 16, 18)
        card.add(JLabel("Add a line", Icons.get("plus", 18, if (full) t.textDisabled else t.textMuted), JLabel.LEFT).apply {
            font = Type.medium(Type.BODY - 1)
            foreground = if (full) t.textDisabled else t.textMuted
            iconTextGap = 10
        }, BorderLayout.WEST)
        card.add(JLabel("$used of $max used").apply { font = Type.mono(Type.CAPTION); foreground = t.textCaption }, BorderLayout.EAST)
        card.maximumSize = Dimension(Int.MAX_VALUE, 58)
        if (!full) {
            card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            card.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = actions.addLine()
            })
            card.toolTipText = "Set up a second extension from its setup email or a sign-in"
        } else {
            card.toolTipText = "This PC already has two lines"
        }
        return left(card)
    }

    private fun presence(line: Line): JComponent {
        val seg = Segmented(t, "Available", "Do not disturb", line.dnd == true, line.dnd != null) { on ->
            actions.setDnd(line.extension, on)
        }
        seg.maximumSize = Dimension(Int.MAX_VALUE, 46)
        return seg
    }

    private fun presenceCaption(line: Line) = when (line.dnd) {
        null -> "Checking the PBX…"
        true -> "Until you turn it off · callers go to voicemail"
        false -> "Set on PBX · applies to every device on ${line.extension}"
    }

    // ---- right: detail -----------------------------------------------------------------------------------

    private fun fillDetail(line: Line, ringtones: List<Pair<String, String>>) {
        val head = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0))
        head.isOpaque = false
        head.add(Dot(t.line(line.colorIndex), 12))
        if (renaming == line.extension) {
            val field = JTextField(line.label, 14)
            field.font = Type.display(Type.TITLE)
            field.getAccessibleContext().accessibleName = "Line name"
            field.addActionListener {
                renaming = null
                actions.rename(line.extension, field.text)
            }
            field.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                        renaming = null
                        redraw()
                    }
                }
            })
            head.add(field)
            head.add(PillButton(t, "Save", primary = true, height = 40) {
                renaming = null
                actions.rename(line.extension, field.text)
            })
            javax.swing.SwingUtilities.invokeLater { field.requestFocusInWindow(); field.selectAll() }
        } else {
            head.add(JLabel(line.label).apply { font = Type.display(Type.DISPLAY); foreground = t.text })
            val edit = IconButton(t, "compose", 36, t.textMuted, "Rename this line") {
                renaming = line.extension
                redraw()
            }
            edit.style("arc: 10; borderWidth: 0; background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}")
            head.add(edit)
        }
        detail.add(left(head.also { it.maximumSize = Dimension(Int.MAX_VALUE, 60) }))
        detail.add(left(JLabel("ext ${line.extension}" + (line.number?.let { " · $it" } ?: "")).apply {
            font = Type.mono(Type.BODY - 1)
            foreground = t.textMuted
            border = EmptyBorder(0, 24, 0, 0)
        }))
        detail.add(Box.createVerticalStrut(22))

        val reg = Card(t, 14)
        reg.layout = BorderLayout(12, 0)
        reg.border = EmptyBorder(14, 16, 14, 16)
        reg.add(Dot(if (line.registered) t.ok else t.textDisabled, 10).let { d ->
            JPanel(BorderLayout()).apply { isOpaque = false; add(d, BorderLayout.CENTER) }
        }, BorderLayout.WEST)
        val regText = JPanel()
        regText.layout = BoxLayout(regText, BoxLayout.Y_AXIS)
        regText.isOpaque = false
        regText.add(JLabel(if (line.registered) "Voice registered" else "Voice not registered").apply {
            font = Type.semibold(Type.BODY - 0.5f)
            foreground = t.text
        })
        regText.add(Box.createVerticalStrut(3))
        regText.add(JLabel(line.registration).apply { font = Type.mono(Type.CAPTION); foreground = t.textMuted })
        reg.add(regText, BorderLayout.CENTER)
        reg.maximumSize = Dimension(560, 64)
        detail.add(left(reg))
        detail.add(Box.createVerticalStrut(24))

        detail.add(left(sectionLabel(t, "Presence")))
        detail.add(left(presence(line).also { it.maximumSize = Dimension(560, 46) }))
        detail.add(Box.createVerticalStrut(6))
        detail.add(left(JLabel("Changes Do Not Disturb on the PBX for extension ${line.extension} (same as *78 / *76).").apply {
            font = Type.ui(Type.CAPTION + 0.5f)
            foreground = t.textCaption
        }))
        detail.add(Box.createVerticalStrut(24))

        detail.add(left(sectionLabel(t, "Messaging · managed by your admin")))
        val caps = line.caps
        detail.add(left(group(
            row("SMS", value(caps?.sms)),
            row("MMS", value(caps?.mms)),
            row("Caller ID", JLabel(line.number ?: "—").apply { font = Type.mono(Type.LABEL + 1); foreground = t.textMuted }),
        )))
        detail.add(Box.createVerticalStrut(24))

        detail.add(left(sectionLabel(t, "Ringing")))
        val combo = JComboBox(ringtones.map { it.second }.toTypedArray())
        combo.font = Type.ui(Type.LABEL + 1)
        combo.selectedIndex = ringtones.indexOfFirst { it.first == (line.ringtoneId ?: "") }.coerceAtLeast(0)
        combo.addActionListener {
            val id = ringtones.getOrNull(combo.selectedIndex)?.first
            actions.ringtone(line.extension, id?.ifBlank { null })
        }
        combo.getAccessibleContext().accessibleName = "Ringtone for ${line.label}"
        val tone = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        tone.isOpaque = false
        tone.add(combo)
        val play = IconButton(t, "play", 36, t.text, "Play this ringtone") { actions.playRingtone(line.extension) }
        tone.add(play)
        detail.add(left(group(row("Ringtone", tone))))
        detail.add(Box.createVerticalStrut(24))

        val danger = confirmRemove == line.extension
        val actionsGroup = mutableListOf<JComponent>()
        if (!line.isDefault) {
            actionsGroup.add(actionRow("check", "Use for calls and messages", t.text) { actions.makeDefault(line.extension) })
        }
        actionsGroup.add(actionRow("refresh", "Set up this line again", t.text) { actions.setUpAgain(line.extension) })
        if (danger) {
            val confirm = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
            confirm.isOpaque = false
            confirm.border = EmptyBorder(10, 16, 10, 16)
            confirm.add(JLabel("Remove ${line.label} from this PC?").apply { font = Type.semibold(Type.BODY - 1); foreground = t.danger })
            confirm.add(PillButton(t, "Remove", danger = true, height = 36) {
                confirmRemove = null
                actions.remove(line.extension)
            })
            confirm.add(PillButton(t, "Keep", height = 36) {
                confirmRemove = null
                redraw()
            })
            actionsGroup.add(confirm)
        } else {
            actionsGroup.add(actionRow("trash", "Remove this line from this PC", t.danger) {
                confirmRemove = line.extension
                redraw()
            })
        }
        detail.add(left(group(*actionsGroup.toTypedArray())))
        detail.add(Box.createVerticalStrut(8))
        detail.add(left(JLabel("Removing only affects this PC. The extension, its voicemail and your phone are unchanged.").apply {
            font = Type.ui(Type.CAPTION + 0.5f)
            foreground = t.textCaption
        }))
    }

    private fun value(on: Boolean?): JComponent = JLabel(
        when (on) {
            null -> "—"
            true -> "On"
            false -> "Off"
        },
    ).apply {
        font = Type.semibold(Type.LABEL + 1)
        foreground = if (on == true) t.ok else t.textMuted
    }

    private fun row(title: String, trailing: JComponent, caption: String? = null): JComponent {
        val r = JPanel(BorderLayout(12, 0))
        r.isOpaque = false
        r.border = EmptyBorder(12, 16, 12, 16)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(JLabel(title).apply { font = Type.ui(Type.BODY - 0.5f); foreground = t.text })
        if (caption != null) {
            text.add(Box.createVerticalStrut(3))
            text.add(JLabel(caption).apply { font = Type.ui(Type.CAPTION); foreground = t.textCaption })
        }
        r.add(text, BorderLayout.CENTER)
        r.add(JPanel(BorderLayout()).apply { isOpaque = false; add(trailing, BorderLayout.CENTER) }, BorderLayout.EAST)
        return r
    }

    private fun actionRow(icon: String, title: String, ink: java.awt.Color, action: () -> Unit): JComponent {
        val r = JLabel(title, Icons.get(icon, 18, if (ink == t.danger) t.danger else t.actionText), JLabel.LEFT)
        r.font = Type.semibold(Type.BODY - 0.5f)
        r.foreground = ink
        r.iconTextGap = 14
        r.border = EmptyBorder(14, 16, 14, 16)
        r.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        r.isFocusable = true
        r.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = action()
        })
        r.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER || e.keyCode == java.awt.event.KeyEvent.VK_SPACE) action()
            }
        })
        return r
    }

    /** Rounded card holding rows separated by dividers. */
    private fun group(vararg rows: JComponent): JComponent {
        val card = Card(t, 16)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        rows.forEachIndexed { index, r ->
            if (index > 0) card.add(JPanel().apply {
                background = t.divider
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                preferredSize = Dimension(10, 1)
            })
            r.alignmentX = Component.LEFT_ALIGNMENT
            r.maximumSize = Dimension(Int.MAX_VALUE, r.preferredSize.height)
            card.add(r)
        }
        card.maximumSize = Dimension(560, card.preferredSize.height)
        return card
    }

    private fun left(c: JComponent) = c.also { it.alignmentX = Component.LEFT_ALIGNMENT }
}

/** Two-option control: left = off, right = on (Available / Do not disturb). */
class Segmented(
    private val t: Tokens,
    private val offText: String,
    private val onText: String,
    initial: Boolean,
    known: Boolean,
    private val onChange: (Boolean) -> Unit,
) : JPanel(GridLayout(1, 2, 4, 0)) {
    private var on = initial
    private val off = Option(false)
    private val onOpt = Option(true)

    init {
        isOpaque = false
        border = EmptyBorder(4, 4, 4, 4)
        add(off)
        add(onOpt)
        preferredSize = Dimension(360, 46)
        isEnabled = known
        getAccessibleContext().accessibleName = "Presence"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = t.ground
        g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
        g2.dispose()
    }

    private inner class Option(private val value: Boolean) : AccessibleControl(javax.accessibility.AccessibleRole.RADIO_BUTTON) {
        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true
            getAccessibleContext().accessibleName = if (value) onText else offText
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = pick()
            })
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER || e.keyCode == java.awt.event.KeyEvent.VK_SPACE) pick()
                }
            })
        }

        private fun pick() {
            if (!this@Segmented.isEnabled || on == value) return
            on = value
            this@Segmented.repaint()
            onChange(value)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val chosen = on == value && this@Segmented.isEnabled
            if (chosen) {
                g2.color = if (value) t.dndFill else t.selected
                g2.fillRoundRect(0, 0, width - 1, height - 1, 11, 11)
            }
            if (isFocusOwner) {
                g2.color = t.action
                g2.drawRoundRect(1, 1, width - 3, height - 3, 11, 11)
            }
            val text = if (value) onText else offText
            g2.font = if (chosen) Type.semibold(Type.LABEL + 1) else Type.medium(Type.LABEL + 1)
            val fm = g2.fontMetrics
            val glyph = 14
            val total = glyph + 8 + fm.stringWidth(text)
            var x = (width - total) / 2
            val mid = height / 2
            if (value) {
                Icons.get("moon", glyph, if (chosen) t.text else t.textMuted).paintIcon(this, g2, x, mid - glyph / 2)
            } else {
                g2.color = if (chosen) t.ok else t.textDisabled
                g2.fillOval(x + 3, mid - 4, 8, 8)
            }
            x += glyph + 8
            g2.color = if (chosen) t.text else t.textMuted
            g2.drawString(text, x, mid + fm.ascent / 2 - 2)
            g2.dispose()
        }
    }
}

/** Capability tag: "Voice", "SMS"; an off tag is dashed. */
class Tag(private val t: Tokens, text: String, private val on: Boolean) : JLabel(text) {
    init {
        font = Type.semibold(Type.CAPTION)
        foreground = if (on) t.text else t.textMuted
        border = EmptyBorder(4, 9, 4, 9)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (on) {
            g2.color = t.raised
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
        } else {
            g2.color = t.textCaption
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(3f, 3f), 0f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}
