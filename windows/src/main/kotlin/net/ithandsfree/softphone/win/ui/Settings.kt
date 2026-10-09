package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.CardLayout
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
import javax.swing.border.EmptyBorder

/**
 * Settings (`desktop-settings-audio.png`): two columns, the lines and the section list on the left, the chosen
 * section on the right. Sections are plain panels the frame builds; this class only lays them out. GPL-2.0.
 */
class SettingsShell(private val t: Tokens, private val onSection: (String) -> Unit) : JPanel(BorderLayout()) {
    data class Section(val id: String, val title: String, val icon: String, val body: JComponent)

    private val nav = JPanel()
    private val lines = JPanel()
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val items = linkedMapOf<String, NavItem>()
    private val left = JPanel()
    private val sectionList = mutableListOf<Section>()
    private val picker = PillButton(t, "Settings", "chev-d", height = 38) { pickSection() }
    private val pickerRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

    init {
        background = t.ground
        left.layout = BorderLayout()
        left.background = t.ground
        left.preferredSize = Dimension(260, 200)
        left.border = javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider)
        nav.layout = BoxLayout(nav, BoxLayout.Y_AXIS)
        nav.isOpaque = false
        nav.border = EmptyBorder(14, 12, 12, 12)
        nav.add(JLabel("Settings").apply {
            font = Type.display(Type.TITLE)
            foreground = t.text
            border = EmptyBorder(0, 6, 10, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        lines.layout = BoxLayout(lines, BoxLayout.Y_AXIS)
        lines.isOpaque = false
        lines.alignmentX = Component.LEFT_ALIGNMENT
        nav.add(lines)
        nav.add(JPanel().apply {
            background = t.divider
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            preferredSize = Dimension(10, 1)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        nav.add(Box.createVerticalStrut(8))
        left.add(nav, BorderLayout.NORTH)
        content.background = t.ground
        add(left, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        picker.horizontalTextPosition = javax.swing.SwingConstants.LEADING
        picker.getAccessibleContext().accessibleName = "Settings section"
        pickerRow.isOpaque = false
        pickerRow.border = EmptyBorder(12, 16, 0, 16)
        pickerRow.add(picker)
        pickerRow.isVisible = false
        add(pickerRow, BorderLayout.NORTH)
    }

    /** Compact window: the section list becomes a drop-down above the section, which gets the full width. */
    fun compact(on: Boolean) {
        left.isVisible = !on
        pickerRow.isVisible = on
        revalidate()
        repaint()
    }

    private fun pickSection() {
        val menu = javax.swing.JPopupMenu()
        sectionList.forEach { s ->
            val item = javax.swing.JMenuItem(s.title, Icons.get(s.icon, 18, t.textMuted))
            item.font = Type.ui(Type.BODY - 1)
            item.addActionListener { show(s.id) }
            menu.add(item)
        }
        menu.show(picker, 0, picker.height + 4)
    }

    fun sections(list: List<Section>) {
        sectionList += list
        list.forEach { s ->
            val item = NavItem(s)
            items[s.id] = item
            nav.add(item)
            nav.add(Box.createVerticalStrut(2))
            val page = JPanel(BorderLayout())
            page.background = t.ground
            val inner = JPanel()
            inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
            inner.background = t.ground
            inner.border = EmptyBorder(26, 36, 28, 36)
            inner.add(JLabel(s.title).apply {
                font = Type.display(Type.TITLE)
                foreground = t.text
                alignmentX = Component.LEFT_ALIGNMENT
            })
            inner.add(Box.createVerticalStrut(20))
            s.body.alignmentX = Component.LEFT_ALIGNMENT
            inner.add(s.body)
            inner.add(Box.createVerticalGlue())
            page.add(JScrollPane(inner).apply {
                border = EmptyBorder(0, 0, 0, 0)
                viewport.background = t.ground
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBar.unitIncrement = 16
            }, BorderLayout.CENTER)
            content.add(page, s.id)
        }
        list.firstOrNull()?.let { show(it.id) }
    }

    fun show(id: String) {
        cards.show(content, id)
        sectionList.firstOrNull { it.id == id }?.let { picker.text = it.title }
        items.forEach { (key, item) -> item.chosen = key == id }
        onSection(id)
    }

    /** "● Business 9333" rows above the sections. */
    fun lines(rows: List<Triple<String, String, Int>>) {
        lines.removeAll()
        rows.forEach { (label, ext, colorIndex) ->
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6))
            row.isOpaque = false
            row.add(Dot(t.line(colorIndex), 8))
            row.add(JLabel(label).apply { font = Type.medium(Type.LABEL + 1); foreground = t.text })
            if (ext != label) row.add(JLabel(ext).apply { font = Type.mono(Type.LABEL); foreground = t.textMuted })
            row.alignmentX = Component.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Int.MAX_VALUE, 34)
            lines.add(row)
        }
        lines.add(Box.createVerticalStrut(8))
        lines.revalidate()
    }

    private inner class NavItem(private val s: Section) : AccessibleControl(javax.accessibility.AccessibleRole.PAGE_TAB) {
        var chosen = false
            set(value) {
                field = value
                repaint()
            }
        private var hover = false

        init {
            preferredSize = Dimension(230, 40)
            maximumSize = Dimension(Int.MAX_VALUE, 40)
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            isFocusable = true
            getAccessibleContext().accessibleName = s.title
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = show(s.id)
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
            })
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER || e.keyCode == java.awt.event.KeyEvent.VK_SPACE) show(s.id)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (chosen || hover) {
                g2.color = if (chosen) t.railActiveBg else t.surface
                g2.fillRoundRect(0, 0, width, height, 12, 12)
            }
            if (isFocusOwner) {
                g2.color = t.action
                g2.drawRoundRect(1, 1, width - 3, height - 3, 12, 12)
            }
            val ink = if (chosen) t.actionText else t.textMuted
            Icons.get(s.icon, 18, ink).paintIcon(this, g2, 12, (height - 18) / 2)
            g2.font = if (chosen) Type.semibold(Type.BODY - 1) else Type.ui(Type.BODY - 1)
            g2.color = if (chosen) t.text else t.textMuted
            g2.drawString(s.title, 42, (height + g2.fontMetrics.ascent - g2.fontMetrics.descent) / 2)
            g2.dispose()
        }
    }
}

/** Toggle switch (`desktop-settings-audio.png`): action colour when on. Reports as a check box to Narrator. */
class Switch(private val t: Tokens, on: Boolean, label: String, private val onChange: (Boolean) -> Unit) :
    AccessibleControl(javax.accessibility.AccessibleRole.CHECK_BOX) {
    var on = on
        private set

    init {
        preferredSize = Dimension(44, 26)
        maximumSize = preferredSize
        minimumSize = preferredSize
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = label
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = flip()
        })
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_SPACE || e.keyCode == java.awt.event.KeyEvent.VK_ENTER) flip()
            }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
        })
    }

    fun set(value: Boolean) {
        if (on == value) return
        on = value
        repaint()
    }

    private fun flip() {
        if (!isEnabled) return
        on = !on
        repaint()
        onChange(on)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val h = 24
        val y = (height - h) / 2
        g2.color = when {
            !isEnabled -> t.surface
            on -> t.action
            else -> t.raised
        }
        g2.fillRoundRect(0, y, width - 1, h, h, h)
        if (isFocusOwner) {
            g2.color = t.text
            g2.drawRoundRect(0, y, width - 1, h, h, h)
        }
        val knob = h - 6
        val x = if (on) width - knob - 4 else 3
        g2.color = if (on) t.onAction else t.textMuted
        g2.fillOval(x, y + 3, knob, knob)
        g2.dispose()
    }
}

/** Switch with its label to the right, as one row. */
fun switchRow(t: Tokens, label: String, on: Boolean, caption: String? = null, onChange: (Boolean) -> Unit): Pair<JComponent, Switch> {
    val sw = Switch(t, on, label, onChange)
    val row = JPanel(BorderLayout(14, 0))
    row.isOpaque = false
    row.add(JPanel(BorderLayout()).apply { isOpaque = false; add(sw, BorderLayout.NORTH) }, BorderLayout.WEST)
    val text = JPanel()
    text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
    text.isOpaque = false
    text.add(JLabel(label).apply {
        font = Type.ui(Type.BODY - 0.5f)
        foreground = t.text
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        labelFor = sw
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                sw.set(!sw.on)
                onChange(sw.on)
            }
        })
    })
    if (caption != null) {
        text.add(Box.createVerticalStrut(3))
        text.add(JLabel("<html>$caption</html>").apply { font = Type.ui(Type.CAPTION + 0.5f); foreground = t.textCaption })
    }
    row.add(text, BorderLayout.CENTER)
    row.border = EmptyBorder(6, 0, 6, 0)
    row.maximumSize = Dimension(720, row.preferredSize.height + 4)
    row.alignmentX = Component.LEFT_ALIGNMENT
    return row to sw
}

/** Labelled setting row: label column on the left, the control (and anything beside it) on the right. */
fun settingRow(t: Tokens, label: String, vararg controls: JComponent): JComponent {
    val row = JPanel(BorderLayout(16, 0))
    row.isOpaque = false
    row.add(JLabel(label).apply {
        font = Type.semibold(Type.BODY - 0.5f)
        foreground = t.text
        preferredSize = Dimension(180, 40)
        if (controls.isNotEmpty()) labelFor = controls.first()
    }, BorderLayout.WEST)
    val right = JPanel(BorderLayout(12, 0))
    right.isOpaque = false
    right.add(controls.first(), BorderLayout.CENTER)
    if (controls.size > 1) {
        val extra = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        extra.isOpaque = false
        controls.drop(1).forEach { extra.add(it) }
        right.add(extra, BorderLayout.EAST)
    }
    row.add(right, BorderLayout.CENTER)
    row.border = EmptyBorder(6, 0, 6, 0)
    row.maximumSize = Dimension(720, 52)
    row.alignmentX = Component.LEFT_ALIGNMENT
    return row
}

/** Thin divider used between groups in a section. */
fun divider(t: Tokens): JComponent = JPanel().apply {
    background = t.divider
    maximumSize = Dimension(720, 1)
    preferredSize = Dimension(10, 1)
    alignmentX = Component.LEFT_ALIGNMENT
}

/** Seven-bar input level meter (0–255), green bars like the mockup. */
class LevelBars(private val t: Tokens, private val bars: Int = 8) : JComponent() {
    var level: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 255)
            if (field != clamped) {
                field = clamped
                repaint()
            }
        }

    init {
        preferredSize = Dimension(bars * 9, 20)
        minimumSize = preferredSize
        getAccessibleContext()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val lit = ((level / 255.0) * bars + 0.4).toInt()
        repeat(bars) { i ->
            g2.color = if (i < lit) t.answer else t.hairline
            g2.fillRoundRect(i * 9, 2, 6, height - 4, 3, 3)
        }
        g2.dispose()
    }
}
