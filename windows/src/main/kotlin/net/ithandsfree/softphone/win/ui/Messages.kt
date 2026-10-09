package net.ithandsfree.softphone.win.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/** Messages screen parts (`desktop-messages.png`). Views only; PhoneFrame owns the session and the data. GPL-2.0. */

/** What one thread row shows. */
data class ThreadView(
    val title: String,
    val titleIsNumber: Boolean,
    val preview: String,
    val previewIcon: String?,
    val whenText: String,
    val unread: Int,
)

/** Thread list cell: avatar, name, time, preview; unread rows are bold with an action dot. */
class ThreadCell<T>(private val t: Tokens, private val view: (T) -> ThreadView) : JPanel(BorderLayout(12, 0)), ListCellRenderer<T> {
    private var avatar = Avatar(t, "", 40)
    private val name = JLabel()
    private val time = JLabel()
    private val preview = JLabel()
    private val unreadDot = Dot(t.action, 8)

    init {
        border = EmptyBorder(12, 16, 12, 16)
        val top = JPanel(BorderLayout(8, 0))
        top.isOpaque = false
        top.add(name, BorderLayout.CENTER)
        time.font = Type.mono(Type.CAPTION)
        top.add(time, BorderLayout.EAST)
        val bottom = JPanel(BorderLayout(8, 0))
        bottom.isOpaque = false
        bottom.add(preview, BorderLayout.CENTER)
        bottom.add(unreadDot, BorderLayout.EAST)
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(top)
        text.add(Box.createVerticalStrut(4))
        text.add(bottom)
        add(text, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out T>,
        item: T,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val value = view(item)
        remove(avatar)
        avatar = Avatar(t, if (value.titleIsNumber) "" else value.title, 40)
        add(avatar, BorderLayout.WEST)
        background = if (isSelected) t.surface else t.ground
        val bold = value.unread > 0
        name.text = value.title
        name.font = if (value.titleIsNumber) Type.monoMedium(Type.BODY - 0.5f) else Type.semibold(Type.BODY - 0.5f)
        name.foreground = t.text
        time.text = value.whenText
        time.foreground = if (bold) t.actionText else t.textCaption
        preview.text = value.preview.ifBlank { " " }
        preview.icon = value.previewIcon?.let { Icons.get(it, 14, t.textMuted) }
        preview.font = if (bold) Type.medium(Type.LABEL + 1) else Type.ui(Type.LABEL + 1)
        preview.foreground = if (bold) t.text else t.textMuted
        unreadDot.isVisible = bold
        getAccessibleContext().accessibleName =
            "${value.title}, ${value.preview}, ${value.whenText}" + if (bold) ", ${value.unread} unread" else ""
        return this
    }
}

/** Conversation header: avatar, name, number line, Call and Contact buttons. */
class ConversationHeader(private val t: Tokens, onCall: () -> Unit, onContact: () -> Unit) : JPanel(BorderLayout(14, 0)) {
    private var avatar = Avatar(t, "", 40)
    private val name = JLabel(" ")
    private val sub = JLabel(" ")
    val call = IconButton(t, "phone", 44, t.text, "Call", filled = t.surface) { onCall() }
    val contact = IconButton(t, "user", 44, t.text, "Contact") { onContact() }

    init {
        background = t.ground
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, t.divider),
            EmptyBorder(14, 20, 14, 20),
        )
        name.font = Type.semibold(Type.HEADING)
        name.foreground = t.text
        sub.font = Type.mono(Type.CAPTION + 0.5f)
        sub.foreground = t.textMuted
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(Box.createVerticalGlue())
        text.add(name)
        text.add(Box.createVerticalStrut(3))
        text.add(sub)
        text.add(Box.createVerticalGlue())
        add(avatar, BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        buttons.isOpaque = false
        contact.style("arc: 10; borderWidth: 0; background: ${css(t.ground)}; hoverBackground: ${css(t.raised)}")
        buttons.add(call)
        buttons.add(contact)
        add(buttons, BorderLayout.EAST)
    }

    fun show(title: String, isNumber: Boolean, subText: String, callTip: String) {
        remove(avatar)
        avatar = Avatar(t, if (isNumber) "" else title, 40)
        add(avatar, BorderLayout.WEST)
        name.text = title
        name.font = if (isNumber) Type.monoMedium(Type.HEADING) else Type.semibold(Type.HEADING)
        sub.text = subText
        sub.isVisible = subText.isNotBlank()
        call.toolTipText = callTip
        revalidate()
        repaint()
    }
}

/** "● via Business · (416) 555-0133" strip under the conversation header. */
class ViaBar(private val t: Tokens) : JPanel(FlowLayout(FlowLayout.CENTER, 6, 6)) {
    private val dot = Dot(t.line1, 7)
    private val text = JLabel(" ")

    init {
        background = t.surface
        text.font = Type.ui(Type.LABEL)
        text.foreground = t.textMuted
        add(dot)
        add(text)
    }

    fun show(label: String, number: String?, colorIndex: Int) {
        dot.color = t.line(colorIndex)
        text.text = "via $label" + (number?.let { " · $it" } ?: "")
    }
}

/** One message bubble with its time, aligned to the left (received) or right (sent) edge. */
fun bubbleRow(t: Tokens, body: JComponent, inbound: Boolean, caption: String): JComponent {
    val column = JPanel()
    column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
    column.isOpaque = false
    val align = if (inbound) Component.LEFT_ALIGNMENT else Component.RIGHT_ALIGNMENT
    body.alignmentX = align
    column.add(body)
    column.add(Box.createVerticalStrut(5))
    val time = JLabel(caption)
    time.font = Type.mono(Type.CAPTION - 0.5f)
    time.foreground = t.textCaption
    time.alignmentX = align
    column.add(time)
    // A full-width row pinned WEST or EAST. BoxLayout alone puts mixed-alignment children on a shared axis,
    // which drew sent messages on the left (owner screenshot, 2026-10-08).
    val row = JPanel(BorderLayout())
    row.isOpaque = false
    row.border = EmptyBorder(0, 0, 14, 0)
    row.add(column, if (inbound) BorderLayout.WEST else BorderLayout.EAST)
    row.alignmentX = Component.LEFT_ALIGNMENT
    row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
    return row
}

/** Text bubble: received on raised navy, sent on the selected blue; wraps at 420 px. */
class TextBubble(t: Tokens, text: String, inbound: Boolean) : JTextArea(text) {
    private val fill: Color = if (inbound) t.raised else t.selected

    init {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = Type.ui(Type.BODY)
        foreground = t.text
        border = EmptyBorder(10, 14, 10, 14)
        caretColor = fill
        highlighter = javax.swing.text.DefaultHighlighter()
        selectionColor = t.action
        selectedTextColor = t.onAction
        val widest = text.lines().maxOfOrNull { getFontMetrics(font).stringWidth(it) } ?: 0
        val width = (widest + 30).coerceIn(48, 420)
        setSize(width, Short.MAX_VALUE.toInt())
        preferredSize = Dimension(width, preferredSize.height)
        maximumSize = preferredSize
        getAccessibleContext().accessibleName = (if (inbound) "Received: " else "Sent: ") + text
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width, height, 18, 18)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Photo in a rounded frame with the "Photo · 1.2 MB" tag; click opens it large. */
class PhotoBubble(private val t: Tokens, private val tag: String) : JLabel("Loading photo…", SwingConstants.CENTER) {
    init {
        preferredSize = Dimension(220, 150)
        maximumSize = preferredSize
        font = Type.ui(Type.LABEL)
        foreground = t.textMuted
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = "Photo"
    }

    private var photo: java.awt.Image? = null

    /** Shows [image] at most 260 × 200 design pixels, shape kept. The full image is drawn, so it stays sharp on HiDPI. */
    fun show(image: java.awt.Image?) {
        if (image == null) {
            text = "Photo unavailable"
            return
        }
        text = ""
        photo = image
        val iw = image.getWidth(null).coerceAtLeast(1).toDouble()
        val ih = image.getHeight(null).coerceAtLeast(1).toDouble()
        val scale = minOf(260 / iw, 200 / ih, 1.0)
        preferredSize = Dimension((iw * scale).toInt().coerceAtLeast(40), (ih * scale).toInt().coerceAtLeast(40))
        maximumSize = preferredSize
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val clip = java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f)
        g2.color = t.mediaPlaceholder
        g2.fill(clip)
        g2.clip(clip)
        val image = photo
        if (image != null) {
            g2.drawImage(image, 0, 0, width, height, null)
        } else {
            super.paintComponent(g2)
        }
        g2.font = Type.mono(Type.CAPTION - 1)
        val fm = g2.fontMetrics
        val w = fm.stringWidth(tag) + 14
        g2.color = Color(4, 9, 20, 200)
        g2.fillRoundRect(8, height - 28, w, 20, 8, 8)
        g2.color = t.text
        g2.drawString(tag, 15, height - 14)
        g2.dispose()
    }
}

/** High-quality copy of [image] that fits in [maxW] × [maxH] with its shape kept (never enlarged). */
fun fitImage(image: java.awt.image.BufferedImage, maxW: Int, maxH: Int): java.awt.image.BufferedImage {
    val scale = minOf(maxW.toDouble() / image.width, maxH.toDouble() / image.height, 1.0)
    if (scale >= 1.0) return image
    var current = image
    var w = image.width
    var h = image.height
    val targetW = (image.width * scale).toInt().coerceAtLeast(1)
    val targetH = (image.height * scale).toInt().coerceAtLeast(1)
    // Halve in steps, then a final bicubic pass: smoother than one big step, much faster than SCALE_SMOOTH.
    do {
        w = maxOf(targetW, w / 2)
        h = maxOf(targetH, h / 2)
        val type = if (image.colorModel.hasAlpha()) java.awt.image.BufferedImage.TYPE_INT_ARGB else java.awt.image.BufferedImage.TYPE_INT_RGB
        val next = java.awt.image.BufferedImage(w, h, type)
        val g = next.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(current, 0, 0, w, h, null)
        g.dispose()
        current = next
    } while (w != targetW || h != targetH)
    return current
}

/** Draws a photo as large as the window allows without changing its shape, on the ground colour. */
class PhotoViewer(private val t: Tokens, private val image: java.awt.Image) : JComponent() {
    init {
        getAccessibleContext()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.color = t.ground
        g2.fillRect(0, 0, width, height)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val iw = image.getWidth(null).coerceAtLeast(1).toDouble()
        val ih = image.getHeight(null).coerceAtLeast(1).toDouble()
        val scale = minOf(width / iw, height / ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        g2.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null)
        g2.dispose()
    }
}

/**
 * Drops repeats a provider delivered more than once: same direction, same text, within [windowMs].
 * VoIP.ms delivered one reply six times in two seconds on 2026-10-08; the BFF stores every delivery.
 */
fun <T> collapseRepeats(rows: List<T>, key: (T) -> String, at: (T) -> Long?, windowMs: Long = 10_000): List<T> {
    val out = ArrayList<T>(rows.size)
    for (row in rows) {
        val prev = out.lastOrNull()
        val same = prev != null && key(prev) == key(row) &&
            at(prev) != null && at(row) != null && kotlin.math.abs(at(row)!! - at(prev)!!) <= windowMs
        if (!same) out.add(row)
    }
    return out
}

/** "Today" / "Yesterday" / date between days in the transcript. */
fun daySeparator(t: Tokens, text: String): JComponent {
    val label = JLabel(text, SwingConstants.CENTER)
    label.font = Type.ui(Type.CAPTION)
    label.foreground = t.textCaption
    label.border = EmptyBorder(6, 0, 14, 0)
    label.alignmentX = Component.LEFT_ALIGNMENT
    label.maximumSize = Dimension(Int.MAX_VALUE, label.preferredSize.height)
    return label
}

/** Attachment card above the composer: thumbnail, name, size, "sends as MMS", remove. */
class AttachmentCard(private val t: Tokens, onRemove: () -> Unit) : Card(t, 14) {
    private val thumb = JLabel()
    private val name = JLabel()
    private val meta = JLabel()

    init {
        layout = BorderLayout(12, 0)
        border = EmptyBorder(8, 8, 8, 10)
        thumb.preferredSize = Dimension(44, 44)
        name.font = Type.semibold(Type.LABEL + 1)
        name.foreground = t.text
        meta.font = Type.mono(Type.CAPTION - 0.5f)
        meta.foreground = t.textMuted
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(Box.createVerticalGlue())
        text.add(name)
        text.add(Box.createVerticalStrut(3))
        text.add(meta)
        text.add(Box.createVerticalGlue())
        add(thumb, BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
        val remove = IconButton(t, "plus", 30, t.textMuted, "Remove the photo") { onRemove() }
        remove.icon = rotated(Icons.get("plus", 16, t.textMuted))
        remove.style("arc: 8; borderWidth: 0; background: ${css(t.surface)}; hoverBackground: ${css(t.raised)}")
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 7)).apply { isOpaque = false; add(remove) }, BorderLayout.EAST)
        maximumSize = Dimension(340, 62)
        preferredSize = Dimension(320, 62)
        isVisible = false
    }

    fun show(fileName: String, sizeText: String, image: java.awt.Image?) {
        name.text = fileName
        meta.text = "$sizeText · sends as MMS"
        thumb.icon = image?.let { javax.swing.ImageIcon(it.getScaledInstance(44, 44, java.awt.Image.SCALE_SMOOTH)) }
        isVisible = true
        revalidate()
    }

    private fun rotated(icon: Icon): Icon = object : Icon {
        override fun getIconWidth() = icon.iconWidth
        override fun getIconHeight() = icon.iconHeight
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.rotate(Math.toRadians(45.0), x + iconWidth / 2.0, y + iconHeight / 2.0)
            icon.paintIcon(c, g2, x, y)
            g2.dispose()
        }
    }
}

/** Rounded message input that turns its outline gold when focused (`desktop-messages.png`). */
class ComposerBox(private val t: Tokens, val input: JTextArea) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        input.font = Type.ui(Type.BODY)
        input.foreground = t.text
        input.caretColor = t.action
        input.isOpaque = false
        input.lineWrap = true
        input.wrapStyleWord = true
        input.border = EmptyBorder(13, 16, 13, 16)
        input.rows = 1
        add(input, BorderLayout.CENTER)
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = repaint()
            override fun focusLost(e: java.awt.event.FocusEvent) = repaint()
        })
        input.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = grow()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = grow()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = grow()
        })
    }

    private fun grow() {
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, base.height.coerceIn(48, 140))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = t.surface
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.color = if (input.isFocusOwner) t.action else t.hairline
        g2.stroke = java.awt.BasicStroke(if (input.isFocusOwner) 2f else 1f)
        g2.drawRoundRect(1, 1, width - 3, height - 3, 16, 16)
        g2.dispose()
    }
}

/** Placeholder text drawn inside an empty text area. */
class HintArea(private val hint: String, private val hintColor: Color) : JTextArea() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isNotEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = hintColor
        g2.font = font
        g2.drawString(hint, insets.left, insets.top + g2.fontMetrics.ascent)
        g2.dispose()
    }
}
