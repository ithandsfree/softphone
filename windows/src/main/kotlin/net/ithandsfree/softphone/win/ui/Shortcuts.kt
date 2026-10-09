package net.ithandsfree.softphone.win.ui

import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/** Keyboard shortcuts: the `?` sheet (`desktop-shortcuts.png`) and the remap rows in Settings. GPL-2.0. */

/**
 * While a Settings row waits for new keys, the frame's key dispatcher hands every key press here first, so the
 * press is captured instead of running an action.
 */
object KeyCapture {
    @Volatile
    var listener: ((KeyEvent) -> Boolean)? = null
}

/** A column on the sheet: "Anywhere", "Calls", … and its rows (label, key chips). */
data class SheetGroup(val title: String, val rows: List<Pair<String, List<String>>>, val note: String? = null)

/**
 * The shortcuts sheet, drawn over the window on a dimmed backdrop (popup layer of the root pane). Two columns of
 * groups. Esc, the close button, or a click outside the card closes it.
 */
class ShortcutSheet private constructor(
    private val t: Tokens,
    groups: List<SheetGroup>,
    private val onClose: () -> Unit,
) : JPanel(GridBagLayout()) {
    private val card = Card(t, 20, t.surface)

    init {
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "Keyboard shortcuts"
        card.layout = BorderLayout(0, 20)
        card.border = EmptyBorder(26, 32, 28, 32)
        val head = JPanel(BorderLayout())
        head.isOpaque = false
        head.add(JLabel("Keyboard shortcuts").apply {
            font = Type.display(Type.TITLE_SM)
            foreground = t.text
        }, BorderLayout.WEST)
        head.add(IconButton(t, "x", 34, t.textMuted, "Close (Esc)") { close() }, BorderLayout.EAST)
        card.add(head, BorderLayout.NORTH)
        val grid = JPanel(GridBagLayout())
        grid.isOpaque = false
        groups.forEachIndexed { index, group ->
            val c = GridBagConstraints()
            c.gridx = index % 2
            c.gridy = index / 2
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            c.anchor = GridBagConstraints.NORTHWEST
            c.insets = Insets(if (c.gridy == 0) 0 else 22, if (c.gridx == 0) 0 else 20, 0, if (c.gridx == 0) 20 else 0)
            grid.add(column(group), c)
        }
        card.add(grid, BorderLayout.CENTER)
        add(card)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (!card.bounds.contains(e.point)) close()
            }
        })
        card.addMouseListener(object : java.awt.event.MouseAdapter() {})
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "close-sheet")
        actionMap.put("close-sheet", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = close()
        })
    }

    private fun column(group: SheetGroup): JComponent {
        val col = JPanel()
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
        col.isOpaque = false
        col.add(sectionLabel(t, group.title))
        col.add(Box.createVerticalStrut(4))
        group.rows.forEach { (label, keys) -> col.add(keyRow(t, label, keys)) }
        group.note?.let {
            col.add(Box.createVerticalStrut(6))
            col.add(JLabel("<html><body style='width:300px'>$it</body></html>").apply {
                font = Type.ui(Type.CAPTION + 0.5f)
                foreground = t.textCaption
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        return col
    }

    override fun doLayout() {
        // Card is 820 wide in the design; narrower windows get a card 24 px in from each side.
        val w = (width - 48).coerceAtMost(820).coerceAtLeast(300)
        val pref = card.preferredSize
        card.setBounds((width - w) / 2, ((height - pref.height) / 2).coerceAtLeast(16), w, pref.height.coerceAtMost(height - 32))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.62f)
        g2.color = Color(2, 5, 12)
        g2.fillRect(0, 0, width, height)
        g2.dispose()
    }

    private fun close() {
        val parent = parent ?: return
        parent.remove(this)
        parent.revalidate()
        parent.repaint()
        onClose()
    }

    companion object {
        /** Shows the sheet over [root]'s content. Calling it again while it is open closes the old one first. */
        fun show(root: JRootPane, t: Tokens, groups: List<SheetGroup>, onClose: () -> Unit = {}): ShortcutSheet {
            val layers = root.layeredPane
            layers.components.filterIsInstance<ShortcutSheet>().forEach { layers.remove(it) }
            val sheet = ShortcutSheet(t, groups, onClose)
            val area = SwingUtilities.convertRectangle(root.contentPane.parent, root.contentPane.bounds, layers)
            sheet.bounds = area
            layers.add(sheet, JLayeredPane.POPUP_LAYER)
            layers.revalidate()
            layers.repaint()
            sheet.requestFocusInWindow()
            return sheet
        }

        fun isOpen(root: JRootPane): Boolean = root.layeredPane.components.any { it is ShortcutSheet }

        fun close(root: JRootPane) {
            root.layeredPane.components.filterIsInstance<ShortcutSheet>().forEach { it.close() }
        }

        /** Keeps the sheet sized to the window after a resize. */
        fun fit(root: JRootPane) {
            val layers = root.layeredPane
            layers.components.filterIsInstance<ShortcutSheet>().forEach {
                it.bounds = SwingUtilities.convertRectangle(root.contentPane.parent, root.contentPane.bounds, layers)
                it.revalidate()
            }
        }
    }
}

/** "Label ………… [Ctrl] [K]" row used on the sheet. */
fun keyRow(t: Tokens, label: String, keys: List<String>): JComponent {
    val row = JPanel(BorderLayout(12, 0))
    row.isOpaque = false
    row.border = EmptyBorder(5, 0, 5, 0)
    row.add(JLabel(label).apply { font = Type.ui(Type.BODY - 1); foreground = t.text }, BorderLayout.CENTER)
    row.add(kbdChips(t, keys), BorderLayout.EAST)
    row.alignmentX = Component.LEFT_ALIGNMENT
    row.maximumSize = Dimension(Int.MAX_VALUE, 34)
    return row
}

/** One chip per key combination ("Ctrl 1", "Ctrl 2"), as in the design. */
fun kbdChips(t: Tokens, keys: List<String>): JComponent {
    val chips = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
    chips.isOpaque = false
    keys.forEach { chips.add(Kbd(t, it)) }
    return chips
}

/**
 * Settings row for one remappable shortcut: the action, its keys, Change, and Reset when it differs from the
 * default. [onCapture] is called with the row when Change is pressed; the frame then calls [prompt] and later
 * [show] or [problem].
 */
class ShortcutEditRow(
    private val t: Tokens,
    val label: String,
    private val onCapture: (ShortcutEditRow) -> Unit,
    private val onReset: (ShortcutEditRow) -> Unit,
) : JPanel(BorderLayout(14, 0)) {
    private val keys = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
    private val message = JLabel(" ")
    private val change = PillButton(t, "Change", height = 32) { onCapture(this) }
    private val reset = PillButton(t, "Reset", height = 32) { onReset(this) }

    init {
        isOpaque = false
        border = EmptyBorder(6, 0, 6, 0)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(JLabel(label).apply { font = Type.ui(Type.BODY - 0.5f); foreground = t.text })
        message.font = Type.ui(Type.CAPTION + 0.5f)
        message.foreground = t.textCaption
        message.isVisible = false
        text.add(message)
        add(text, BorderLayout.CENTER)
        keys.isOpaque = false
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        right.isOpaque = false
        right.add(keys)
        change.getAccessibleContext().accessibleName = "Change the keys for $label"
        reset.getAccessibleContext().accessibleName = "Reset $label to its default keys"
        right.add(change)
        right.add(reset)
        add(right, BorderLayout.EAST)
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(720, 56)
    }

    /** Shows the current keys; [custom] shows Reset. */
    fun display(chord: String, custom: Boolean) {
        keys.removeAll()
        keys.add(Kbd(t, chord))
        reset.isVisible = custom
        change.text = "Change"
        message.isVisible = false
        revalidate()
        repaint()
    }

    /** Waiting for keys: the chip reads "Press keys", and Change becomes Cancel. */
    fun prompt() {
        keys.removeAll()
        keys.add(Kbd(t, "Press keys…").apply { foreground = t.actionText })
        change.text = "Cancel"
        message.text = "Press the new keys, or Esc to keep the old ones"
        message.foreground = t.textCaption
        message.isVisible = true
        revalidate()
        repaint()
    }

    fun problem(text: String) {
        message.text = text
        message.foreground = t.danger
        message.isVisible = true
        revalidate()
    }

    fun note(text: String) {
        message.text = text
        message.foreground = t.textCaption
        message.isVisible = true
        revalidate()
    }
}
