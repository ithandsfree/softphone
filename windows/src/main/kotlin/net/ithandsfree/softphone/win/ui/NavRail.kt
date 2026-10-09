package net.ithandsfree.softphone.win.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * 76 px navigation rail (`desktop-calls.png`): Calls, Messages, Lines, Settings, then the shortcuts key and the
 * account button at the bottom with a presence dot. Badges: danger for missed calls, action for unread. GPL-2.0.
 */
class NavRail(
    private val t: Tokens,
    items: List<Triple<String, String, String>>,
    private val onPick: (String) -> Unit,
    onShortcuts: () -> Unit,
    onAccount: () -> Unit,
) : JPanel() {
    private val buttons = linkedMapOf<String, RailItem>()
    private val account = AccountButton(t, onAccount)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = t.ground
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider),
            EmptyBorder(10, 8, 12, 8),
        )
        preferredSize = Dimension(Space.RAIL_W, 400)
        minimumSize = Dimension(Space.RAIL_W, 200)
        items.forEach { (id, title, iconName) ->
            val item = RailItem(t, id, title, iconName) { onPick(id) }
            buttons[id] = item
            add(item)
            add(Box.createVerticalStrut(4))
        }
        add(Box.createVerticalGlue())
        val keys = IconButton(t, "keyboard", 40, t.textMuted, "Keyboard shortcuts") { onShortcuts() }
        keys.style("arc: 10; borderWidth: 0; background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}")
        keys.alignmentX = CENTER_ALIGNMENT
        add(keys)
        add(Box.createVerticalStrut(14))
        account.alignmentX = CENTER_ALIGNMENT
        add(account)
    }

    fun choose(id: String) {
        buttons.forEach { (key, item) -> item.chosen = key == id }
    }

    /** [danger] badges are coral (missed calls), the others use the action colour (unread texts). */
    fun badge(id: String, count: Int, danger: Boolean) {
        buttons[id]?.let {
            it.count = count
            it.danger = danger
            it.repaint()
        }
    }

    /** Presence dot on the account button. */
    fun presence(color: Color) {
        account.dot = color
        account.repaint()
    }

    private class RailItem(
        private val t: Tokens,
        id: String,
        private val title: String,
        private val iconName: String,
        private val onClick: () -> Unit,
    ) : AccessibleControl(javax.accessibility.AccessibleRole.PUSH_BUTTON) {
        var chosen = false
            set(value) {
                field = value
                repaint()
            }
        var count = 0
        var danger = false
        private var hover = false

        init {
            preferredSize = Dimension(60, 56)
            maximumSize = preferredSize
            alignmentX = CENTER_ALIGNMENT
            toolTipText = title
            isFocusable = true
            name = id
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            getAccessibleContext().accessibleName = title
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
            })
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER || e.keyCode == java.awt.event.KeyEvent.VK_SPACE) onClick()
                }
            })
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
                override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
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
            val icon = Icons.get(iconName, 22, ink)
            icon.paintIcon(this, g2, (width - 22) / 2, 8)
            g2.font = Type.medium(11f)
            g2.color = if (chosen) t.actionText else t.textMuted
            val fm = g2.fontMetrics
            g2.drawString(title, (width - fm.stringWidth(title)) / 2, height - 8)
            if (count > 0) {
                val text = if (count > 9) "9+" else count.toString()
                g2.font = Type.semibold(10f)
                val bw = (g2.fontMetrics.stringWidth(text) + 8).coerceAtLeast(16)
                val bx = width / 2 + 4
                g2.color = if (danger) t.danger else t.action
                g2.fillRoundRect(bx, 4, bw, 16, 16, 16)
                g2.color = if (danger) t.onDanger else t.onAction
                g2.drawString(text, bx + (bw - g2.fontMetrics.stringWidth(text)) / 2, 16)
            }
            g2.dispose()
        }
    }

    private class AccountButton(private val t: Tokens, onClick: () -> Unit) : AccessibleControl(javax.accessibility.AccessibleRole.PUSH_BUTTON) {
        var dot: Color = t.textDisabled

        init {
            preferredSize = Dimension(44, 44)
            maximumSize = preferredSize
            toolTipText = "Account and presence"
            getAccessibleContext().accessibleName = "Account and presence"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = t.surface
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = t.hairline
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            Icons.get("user", 20, t.text).paintIcon(this, g2, (width - 20) / 2, (height - 20) / 2)
            g2.color = t.ground
            g2.fillOval(width - 13, height - 13, 12, 12)
            g2.color = dot
            g2.fillOval(width - 11, height - 11, 8, 8)
            g2.dispose()
        }
    }
}
