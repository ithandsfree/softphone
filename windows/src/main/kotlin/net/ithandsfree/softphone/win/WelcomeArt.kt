package net.ithandsfree.softphone.win

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JTextField
import kotlin.math.roundToInt

/**
 * 1080p is the design size. Larger desktops grow from there so a 1440p or 4K
 * screen does not leave the window looking like a phone widget.
 */
object UiScale {
    val factor: Float = run {
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        (screen.height / 1080f).coerceIn(1f, 1.8f)
    }

    fun px(value: Int): Int = (value * factor).roundToInt().coerceAtLeast(1)
}

/** Welcome art. IHF uses the cloud-phone photo. Community paints its own illustration. GPL-2.0. */
class HeroBanner(
    private val lead: String,
    private val emphasis: String,
    private val compact: Boolean = false,
    private val onBack: (() -> Unit)? = null,
    private val edge: Boolean = false,
    private val brandTitle: String = "IHF Phone",
    private val brandSub: String = "IT HANDS FREE",
    private val theme: PhoneTheme = phoneTheme(Distribution.IHF),
) : javax.swing.JPanel() {
    private val ground = theme.ground
    private val gold = theme.gold
    private val ink = theme.ink
    private val image: BufferedImage? = HeroBanner::class.java.getResourceAsStream("/hero-cloud-phone.jpg")?.use {
        ImageIO.read(it)
    }
    private val emblem: BufferedImage? = HeroBanner::class.java.getResourceAsStream("/ihf-emblem.png")?.use {
        ImageIO.read(it)
    }
    private val heightPx = UiScale.px(if (edge) 720 else if (compact) 240 else 360)
    private val widthPx = UiScale.px(if (edge) 500 else 760)

    init {
        isOpaque = true
        background = ground
        if (onBack != null) {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.x < 56 && e.y < 64) onBack.invoke()
                }
            })
        }
    }

    override fun getPreferredSize(): Dimension = if (edge) Dimension(UiScale.px(520), 120) else Dimension(widthPx, heightPx)
    override fun getMinimumSize(): Dimension = if (edge) Dimension(340, 120) else Dimension(UiScale.px(680), heightPx)
    override fun getMaximumSize(): Dimension = if (edge) Dimension(widthPx, Int.MAX_VALUE) else Dimension(Int.MAX_VALUE, heightPx)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = ground
        g2.fillRect(0, 0, width, height)
        paintPhoto(g2)
        if (!edge) paintShade(g2)
        paintBrand(g2)
        if (edge) paintEdgeCopy(g2) else paintTitle(g2)
        g2.dispose()
    }

    private fun paintPhoto(g2: Graphics2D) {
        if (theme.communityArt) {
            paintCommunity(g2)
            return
        }
        val photo = image ?: return
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        if (edge) {
            paintEdgePhoto(g2, photo)
            return
        }
        val scale = maxOf(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val dw = (photo.width * scale).toInt()
        val dh = (photo.height * scale).toInt()
        val x = width - dw
        val y = ((height - dh) * 0.42f).toInt()
        g2.drawImage(photo, x, y, dw, dh, null)
        val fadeTop = height * 0.72f
        val fade = LinearGradientPaint(
            0f, fadeTop, 0f, height.toFloat(),
            floatArrayOf(0f, 1f),
            arrayOf(Color(0, 0, 0, 0), ground),
        )
        g2.paint = fade
        g2.fillRect(0, fadeTop.toInt(), width, height)
    }

    private fun paintShade(g2: Graphics2D) {
        g2.paint = LinearGradientPaint(
            0f, 0f, 0f, height * 0.38f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(ground.red, ground.green, ground.blue, 184), Color(ground.red, ground.green, ground.blue, 0)),
        )
        g2.fillRect(0, 0, width, (height * 0.45f).toInt())
        if (!compact) return
        g2.paint = LinearGradientPaint(
            0f, 0f, width * 0.72f, 0f,
            floatArrayOf(0f, 0.52f, 1f),
            arrayOf(
                Color(ground.red, ground.green, ground.blue, 235),
                Color(ground.red, ground.green, ground.blue, 140),
                Color(ground.red, ground.green, ground.blue, 0),
            ),
        )
        g2.fillRect(0, 0, width, height)
    }

    private fun paintBrand(g2: Graphics2D) {
        val left = if (onBack != null) UiScale.px(56) else UiScale.px(28)
        if (onBack != null) {
            g2.color = ink
            g2.stroke = java.awt.BasicStroke(UiScale.px(2).toFloat())
            val mid = UiScale.px(40)
            g2.drawLine(UiScale.px(26), mid, UiScale.px(14), mid - UiScale.px(8))
            g2.drawLine(UiScale.px(26), mid, UiScale.px(14), mid + UiScale.px(8))
        }
        val markW = UiScale.px(42)
        val markH = UiScale.px(35)
        if (theme.communityArt) paintBubble(g2, left, UiScale.px(18), markW) else paintEmblem(g2, left, UiScale.px(20), markW, markH)
        g2.font = Font("Georgia", Font.PLAIN, UiScale.px(26))
        g2.color = ink
        g2.drawString(brandTitle, left + markW + UiScale.px(12), UiScale.px(42))
        g2.font = Font("Segoe UI", Font.BOLD, UiScale.px(12))
        g2.color = gold
        g2.drawString(brandSub, left + markW + UiScale.px(12), UiScale.px(60))
    }

    /** The hero is already sized to the on-screen area by the welcome page. */
    private fun visibleSpan(): Int = height.coerceAtLeast(1)

    /**
     * Desktop welcome hero from the round-3 spec. The photo covers the left pane and is
     * shifted up just enough that the handset stays clear of the type and the writing
     * sits on the reflection, with the spec's ground gradient over it.
     */
    private fun paintEdgePhoto(g2: Graphics2D, photo: BufferedImage) {
        val h = visibleSpan()
        val scale = maxOf(width.toFloat() / photo.width, h.toFloat() / photo.height) * 1.22f
        val dw = (photo.width * scale).toInt().coerceAtLeast(1)
        val dh = (photo.height * scale).toInt().coerceAtLeast(1)
        val x = ((width - dw) * 0.60f).toInt()
        val y = ((h - dh) * 0.88f).toInt()
        g2.drawImage(photo, x, y, dw, dh, null)
        val span = h.toFloat().coerceAtLeast(2f)
        g2.paint = LinearGradientPaint(
            0f, 0f, 0f, span,
            floatArrayOf(0f, 0.30f, 0.55f, 1f),
            arrayOf(
                Color(ground.red, ground.green, ground.blue, 140),
                Color(ground.red, ground.green, ground.blue, 0),
                Color(ground.red, ground.green, ground.blue, 51),
                Color(ground.red, ground.green, ground.blue, 230),
            ),
        )
        g2.fillRect(0, 0, width, h)
        if (h < height) {
            g2.color = ground
            g2.fillRect(0, h, width, height - h)
        }
    }

    private fun paintEdgeCopy(g2: Graphics2D) {
        val h = visibleSpan()
        val left = UiScale.px(36)
        val row = UiScale.px(40)
        val gap = UiScale.px(12)
        val bottom = h - UiScale.px(28)
        val second = bottom - row
        val first = second - gap - row
        val limit = (width - left - UiScale.px(36)).coerceAtLeast(1)
        var size = UiScale.px(48)
        var italic = Font("Georgia", Font.ITALIC, size)
        g2.font = italic
        while (size > UiScale.px(30) && g2.fontMetrics.stringWidth(emphasis) > limit) {
            size -= 2
            italic = Font("Georgia", Font.ITALIC, size)
            g2.font = italic
        }
        val emphasisBase = first - UiScale.px(16) - 4
        g2.color = ink
        g2.font = Font("Georgia", Font.PLAIN, size)
        g2.drawString(lead, left, emphasisBase - (size * 1.02f).toInt())
        g2.color = gold
        g2.font = italic
        g2.drawString(emphasis, left, emphasisBase)
        feature(g2, left, first, "Two lines, one app", "Each extension with its own number.", true)
        feature(g2, left, second, "Texts with photos", "Send and receive MMS from the line you choose.", false)
    }

    private fun paintTitle(g2: Graphics2D) {
        val left = UiScale.px(32)
        val limit = (width * if (edge) 0.72f else 0.42f).toInt()
        var size = UiScale.px(if (compact) 36 else 48)
        var italic = Font("Georgia", Font.ITALIC, size)
        g2.font = italic
        while (size > UiScale.px(28) && g2.fontMetrics.stringWidth(emphasis) > limit) {
            size -= 2
            italic = Font("Georgia", Font.ITALIC, size)
            g2.font = italic
        }
        val base = height - UiScale.px(if (edge) 230 else 28)
        g2.color = ink
        g2.font = Font("Georgia", Font.PLAIN, size)
        g2.drawString(lead, left, base - (size * 1.05f).toInt())
        g2.color = gold
        g2.font = italic
        g2.drawString(emphasis, left, base)
    }

    private fun feature(g2: Graphics2D, x: Int, y: Int, title: String, detail: String, lines: Boolean) {
        val box = UiScale.px(36)
        g2.color = theme.surface
        g2.fillRoundRect(x, y, box, box, 10, 10)
        g2.color = theme.hairline
        g2.drawRoundRect(x, y, box, box, 10, 10)
        if (lines) {
            g2.color = theme.emerald
            g2.fillOval(x + 8, y + 14, 10, 10)
            g2.color = theme.sky
            g2.fillOval(x + 16, y + 14, 10, 10)
        } else {
            g2.color = gold
            g2.fillRoundRect(x + 8, y + 12, 18, 12, 3, 3)
        }
        g2.color = ink
        g2.font = Font("Segoe UI", Font.BOLD, UiScale.px(14))
        g2.drawString(title, x + box + UiScale.px(10), y + UiScale.px(15))
        g2.color = theme.caption
        g2.font = Font("Segoe UI", Font.PLAIN, UiScale.px(12))
        val detailX = x + box + UiScale.px(10)
        val clip = g2.clip
        g2.clipRect(detailX, y, (width - detailX - UiScale.px(12)).coerceAtLeast(1), rowGap(box))
        g2.drawString(detail, detailX, y + UiScale.px(30))
        g2.clip = clip
    }

    private fun rowGap(box: Int): Int = (box + 4).coerceAtLeast(1)

    private fun paintCommunity(g2: Graphics2D) {
        g2.color = ground
        g2.fillRect(0, 0, width, height)
        g2.paint = java.awt.RadialGradientPaint(
            width * 0.72f,
            height * 0.28f,
            width.coerceAtLeast(1).toFloat(),
            floatArrayOf(0f, 0.42f, 1f),
            arrayOf(Color(0x1D, 0x3B, 0x3E), Color(0x14, 0x1B, 0x21), ground),
        )
        g2.fillRect(0, 0, width, height)
        g2.color = Color(ink.red, ink.green, ink.blue, 18)
        val cx = (width * 0.72f).toInt()
        val cy = (height * 0.32f).toInt()
        var radius = UiScale.px(80)
        repeat(6) {
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)
            radius += UiScale.px(28)
        }
        g2.stroke = java.awt.BasicStroke(UiScale.px(4).toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g2.color = theme.emerald
        g2.drawArc(-UiScale.px(40), (height * 0.42f).toInt(), (width * 0.7f).toInt(), UiScale.px(80), 10, 140)
        g2.color = theme.sky
        g2.drawArc(-UiScale.px(20), (height * 0.5f).toInt(), (width * 0.62f).toInt(), UiScale.px(70), 15, 130)
        paintBubble(g2, (width * 0.58f).toInt(), (height * 0.22f).toInt(), UiScale.px(88))
    }

    private fun paintBubble(g2: Graphics2D, x: Int, y: Int, size: Int) {
        g2.color = ink
        g2.fillRoundRect(x, y, size, (size * 0.72f).toInt(), size / 3, size / 3)
        val tail = java.awt.Polygon()
        tail.addPoint(x + size / 5, y + (size * 0.62f).toInt())
        tail.addPoint(x + size / 8, y + size)
        tail.addPoint(x + size / 2, y + (size * 0.7f).toInt())
        g2.fillPolygon(tail)
        g2.color = theme.emerald
        g2.fillRoundRect(x + size / 5, y + size / 5, (size * 0.55f).toInt(), size / 8, 8, 8)
        g2.color = theme.sky
        g2.fillRoundRect(x + size / 5, y + size / 2, (size * 0.36f).toInt(), size / 8, 8, 8)
    }

    private fun paintEmblem(g2: Graphics2D, x: Int, y: Int, markW: Int, markH: Int) {
        val mark = emblem ?: return
        g2.drawImage(mark, x, y, markW, markH, null)
    }
}

/** Gold or ghost action with the Welcome corner radius. */
class WelcomeButton(
    title: String,
    private val fill: Color,
    private val ink: Color,
    private val quiet: Boolean = false,
    private val quietFill: Color = Color(0x0B, 0x13, 0x2B),
    private val line: Color = Color(0x23, 0x30, 0x52),
    private val disabledInk: Color = Color(0x6B, 0x78, 0x96),
    private val expand: Boolean = true,
    private val focusRing: Color = fill,
    private val compact: Boolean = false,
) : JButton(title) {
    private var hovered = false

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        font = Font("Segoe UI", Font.BOLD, UiScale.px(15))
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(event: MouseEvent) {
                hovered = false
                repaint()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = repaint()
            override fun focusLost(event: FocusEvent) = repaint()
        })
    }

    override fun getPreferredSize(): Dimension {
        if (compact) return Dimension(UiScale.px(92), UiScale.px(52))
        val metrics = font?.let { getFontMetrics(it) }
        val wide = if (metrics == null) 120 else (metrics.stringWidth(text.orEmpty()) + 48).coerceAtLeast(120)
        return Dimension(wide, 56)
    }
    override fun getMaximumSize(): Dimension = if (expand && !compact) Dimension(Int.MAX_VALUE, 56) else preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val bg = when {
            !isEnabled -> quietFill
            quiet -> quietFill
            else -> fill
        }
        g2.color = bg
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        if (quiet || !isEnabled) {
            g2.color = if (hovered && isEnabled) focusRing else line
            g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        }
        g2.color = if (isEnabled) ink else disabledInk
        g2.font = font
        val fm = g2.fontMetrics
        val clip = g2.clip
        g2.clipRect(10, 0, (width - 20).coerceAtLeast(1), height)
        val tx = ((width - fm.stringWidth(text)) / 2).coerceAtLeast(10)
        val ty = (height - fm.height) / 2 + fm.ascent
        g2.drawString(text, tx, ty)
        g2.clip = clip
        if (isFocusOwner) {
            g2.stroke = BasicStroke(2f)
            g2.color = focusRing
            g2.drawRoundRect(3, 3, (width - 7).coerceAtLeast(1), (height - 7).coerceAtLeast(1), 12, 12)
        }
        g2.dispose()
    }
}

/** Inbox row: the mail program's own icon, then its name. */
class MailChoiceButton(
    title: String,
    private val mark: java.awt.Image?,
    private val fill: Color,
    private val ink: Color,
    private val line: Color,
) : JButton(title) {
    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        font = Font("Segoe UI", Font.PLAIN, UiScale.px(15))
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        horizontalAlignment = LEFT
    }

    override fun getPreferredSize(): Dimension = Dimension(360, 52)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 52)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        g2.color = line
        g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        val iconSize = UiScale.px(28)
        val left = UiScale.px(14)
        if (mark != null) {
            g2.drawImage(mark, left, (height - iconSize) / 2, iconSize, iconSize, null)
        }
        g2.color = ink
        g2.font = font
        val fm = g2.fontMetrics
        val textX = left + if (mark != null) iconSize + UiScale.px(12) else 0
        val textY = (height - fm.height) / 2 + fm.ascent
        g2.drawString(text, textX, textY)
        g2.dispose()
    }
}

/** Work-email field with the Android placeholder. */
class PromptField(private val prompt: String) : JTextField() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isNotEmpty()) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = Color(0x84, 0x91, 0xAD)
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawString(prompt, insets.left, (height - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}
