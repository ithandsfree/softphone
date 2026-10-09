package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.MouseInfo
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * One recent call (`desktop-calls.png`): direction glyph, name (danger when missed), line dot + line name + kind +
 * length + time, then always-visible Call and Message buttons and a ⋯ button on hover, focus, or selection.
 * Whole row is one focusable item with a full accessible label. GPL-2.0.
 */
class RecentRow(
    private val t: Tokens,
    private val item: Item,
    private val selected: Boolean,
    private val actions: Actions,
) : JPanel(BorderLayout(12, 0)) {
    data class Item(
        val title: String,
        val titleIsNumber: Boolean,
        val kind: Kind,
        val lineLabel: String?,
        val lineColorIndex: Int?,
        val kindText: String,
        val duration: String?,
        val whenText: String,
        val callEnabled: Boolean,
        val callTip: String,
        val messageEnabled: Boolean,
        val messageTip: String,
        val accessibleLabel: String,
    )

    enum class Kind(val icon: String) { MISSED("missed"), INCOMING("in"), OUTGOING("out"), VOICEMAIL("vm") }

    interface Actions {
        fun open()
        fun call()
        fun message()
        fun more(anchor: JComponent)
        fun context(e: MouseEvent)
    }

    private val more = IconButton(t, "more", 34, t.text, "More actions (Shift F10)") { actions.more(moreAnchor()) }
    private var hover = false

    init {
        isOpaque = true
        background = if (selected) t.surface else t.ground
        border = EmptyBorder(10, 16, 10, 8)
        maximumSize = Dimension(Int.MAX_VALUE, 64)
        preferredSize = Dimension(360, 64)
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = item.accessibleLabel

        val glyphColor = if (item.kind == Kind.MISSED) t.danger else t.textMuted
        add(JLabel(Icons.get(item.kind.icon, 18, glyphColor)), BorderLayout.WEST)

        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        val title = JLabel(item.title)
        title.font = if (item.titleIsNumber) Type.monoMedium(Type.BODY) else Type.semibold(Type.BODY)
        title.foreground = if (item.kind == Kind.MISSED) t.danger else t.text
        text.add(title)
        text.add(Box.createVerticalStrut(3))
        text.add(subLine())
        add(text, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        buttons.isOpaque = false
        val callIcon = if (item.kind == Kind.VOICEMAIL) "play" else "phone"
        val call = IconButton(t, callIcon, 34, t.actionText, item.callTip) { actions.call() }
        call.isEnabled = item.callEnabled
        val message = IconButton(t, "msg", 34, t.textMuted, item.messageTip) { actions.message() }
        message.isEnabled = item.messageEnabled
        listOf(call, message, more).forEach { quiet(it) }
        buttons.add(call)
        buttons.add(message)
        buttons.add(more)
        more.isVisible = selected
        add(buttons, BorderLayout.EAST)

        val hoverWatch = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHover(true)
            override fun mouseExited(e: MouseEvent) {
                val p = MouseInfo.getPointerInfo()?.location ?: return setHover(false)
                SwingUtilities.convertPointFromScreen(p, this@RecentRow)
                setHover(contains(p))
            }
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) actions.context(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) actions.context(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e) && e.source == this@RecentRow) {
                    requestFocusInWindow()
                    actions.open()
                }
            }
        }
        addMouseListener(hoverWatch)
        listOf(call, message, more).forEach { it.addMouseListener(hoverWatch) }
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = setHover(true)
            override fun focusLost(e: java.awt.event.FocusEvent) = setHover(hover)
        })
    }

    private fun moreAnchor(): JComponent = more

    private fun setHover(on: Boolean) {
        hover = on
        more.isVisible = on || selected || isFocusOwner
        // Solid colours only: a translucent background on an opaque row leaves stale pixels behind (smearing).
        background = when {
            selected -> t.surface
            on -> blend(t.ground, t.surface, 0.6f)
            else -> t.ground
        }
        repaint()
    }

    /** Recent-row buttons have no outline until hovered, as in the mockup. */
    private fun quiet(button: IconButton) {
        button.style(
            "arc: 10; borderWidth: 1; focusWidth: 0; innerFocusWidth: 0; margin: 0,0,0,0;" +
                "background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}; pressedBackground: ${css(t.selected)};" +
                "borderColor: ${css(t.ground)}; focusedBorderColor: ${css(t.action)}",
        )
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { button.isContentAreaFilled = true }
            override fun mouseExited(e: MouseEvent) { button.isContentAreaFilled = false }
        })
    }

    private fun subLine(): JComponent {
        // In a narrow window the call length gives way first, so the time stays whole.
        val optional = mutableListOf<JComponent>()
        val row = object : JPanel() {
            override fun doLayout() {
                val full = components.sumOf { it.preferredSize.width }
                val show = width <= 0 || full <= width
                // Only touch visibility when it changes: setVisible revalidates, which would lay out again.
                optional.forEach { if (it.isVisible != show) it.isVisible = show }
                super.doLayout()
            }
        }
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT
        fun text(value: String, mono: Boolean = false) = JLabel(value).apply {
            font = if (mono) Type.mono(Type.CAPTION + 0.5f) else Type.ui(Type.LABEL)
            foreground = t.textMuted
        }
        fun dotSep() = text(" · ")
        if (item.lineColorIndex != null) {
            row.add(Dot(t.line(item.lineColorIndex), 7))
            row.add(Box.createHorizontalStrut(6))
        }
        var first = true
        fun part(c: JComponent) {
            if (!first) row.add(dotSep())
            row.add(c)
            first = false
        }
        item.lineLabel?.let { part(text(it)) }
        if (item.kindText.isNotBlank()) part(text(item.kindText))
        item.duration?.let {
            val length = text(it, mono = true)
            if (!first) {
                val sep = dotSep()
                row.add(sep)
                optional += sep
            }
            row.add(length)
            optional += length
            first = false
        }
        part(text(item.whenText, mono = item.whenText.firstOrNull()?.isDigit() == true))
        return row
    }

    companion object {
        /** A thin divider-free list: rows stack with no gaps, like the mockup. */
        fun list(t: Tokens): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = t.ground
            border = BorderFactory.createEmptyBorder()
        }
    }
}
