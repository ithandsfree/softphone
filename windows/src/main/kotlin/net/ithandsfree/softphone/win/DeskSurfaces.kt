package net.ithandsfree.softphone.win

import java.io.File
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
/** Short original ring. Looped while a call is incoming. */
internal object CallRinger {
    private var clip: Clip? = null
    private var extra: Clip? = null
    private var nativeRing = false

    /** Ring on the open call speaker. A USB headset then hears the ringtone. */
    private fun startOnCallSpeaker(): Boolean {
        val playback = AudioPrefs.playbackName()
        if (playback.isBlank() || SipBridge.loadError != null || !AudioPrefs.applySaved()) return false
        val custom = AudioPrefs.ringtoneFile()
        if (AudioPrefs.ringtoneStyle() == RingtoneLibrary.CUSTOM && custom != null &&
            !custom.extension.equals("wav", ignoreCase = true)
        ) {
            return false
        }
        val wav = if (AudioPrefs.ringtoneStyle() == RingtoneLibrary.CUSTOM && custom != null) {
            custom
        } else {
            val folder = File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir"), "IHF Phone")
            val file = File(folder, "ring-tone.wav")
            runCatching { RingtoneLibrary.writeLoopWav(AudioPrefs.ringtoneStyle(), file) }.getOrNull() ?: return false
            file
        }
        if (SipBridge.ringStart(wav.absolutePath) != 0) return false
        nativeRing = true
        return true
    }

    fun start() {
        if (nativeRing || clip?.isActive == true) return
        if (startOnCallSpeaker()) return
        if (AudioPrefs.playbackName().isNotBlank()) {
            val mixer = pairCaptureName(AudioPrefs.playbackName(), outputNames())
            if (mixer != null) {
                val style = AudioPrefs.ringtoneStyle()
                val custom = if (style == RingtoneLibrary.CUSTOM) AudioPrefs.ringtoneFile() else null
                val created = if (custom != null) {
                    runCatching { clipFromNamedFile(custom, mixer) }.getOrNull()
                } else {
                    runCatching { toneOn(mixer, style) }.getOrNull()
                }
                if (created != null) {
                    clip = created
                    created.loop(Clip.LOOP_CONTINUOUSLY)
                }
            }
            return
        }
        val style = AudioPrefs.ringtoneStyle()
        val custom = if (style == RingtoneLibrary.CUSTOM) AudioPrefs.ringtoneFile() else null
        val fromFile = if (custom != null) runCatching { clipFromFile(custom, AudioPrefs.ringerName()) }.getOrNull() else null
        val created = fromFile
            ?: runCatching { loopingTone(AudioPrefs.ringerName(), style) }.getOrNull()
            ?: runCatching { loopingTone("", RingtoneLibrary.DEFAULT) }.getOrNull()
            ?: return
        clip = created
        created.loop(Clip.LOOP_CONTINUOUSLY)
        if (AudioPrefs.alsoRing()) {
            val speaker = AudioPrefs.playbackName()
            if (speaker.isNotBlank() && !sameMixer(AudioPrefs.ringerName(), speaker)) {
                extra = if (custom != null) {
                    runCatching { clipFromNamedFile(custom, speaker) }.getOrNull()
                } else {
                    runCatching { toneOn(speaker, style) }.getOrNull()
                }
                extra?.loop(Clip.LOOP_CONTINUOUSLY)
            }
        }
    }

    fun stop() {
        if (nativeRing) {
            SipBridge.ringStop()
            nativeRing = false
        }
        listOf(clip, extra).forEach { current ->
            current?.stop()
            current?.close()
        }
        clip = null
        extra = null
    }

    fun outputNames(): List<String> {
        val format = AudioFormat(8000f, 16, 1, true, false)
        val info = DataLine.Info(Clip::class.java, format)
        return AudioSystem.getMixerInfo().mapNotNull { mixerInfo ->
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return@mapNotNull null
            if (runCatching { mixer.isLineSupported(info) }.getOrDefault(false)) mixerInfo.name else null
        }.distinct()
    }

    private fun clipFromNamedFile(file: File, mixerName: String): Clip? {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val info = DataLine.Info(Clip::class.java, stream.format)
            val line = namedMixer(info, mixerName) ?: return null
            line.open(stream)
            return line
        }
    }

    private fun toneOn(mixerName: String, style: String): Clip? {
        val data = RingtoneLibrary.render(style)
        val format = AudioFormat(8000f, 16, 1, true, false)
        val info = DataLine.Info(Clip::class.java, format)
        val line = namedMixer(info, mixerName) ?: return null
        line.open(format, data, 0, data.size)
        return line
    }

    private fun clipFromFile(file: File, mixerName: String): Clip {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val info = DataLine.Info(Clip::class.java, stream.format)
            val line = clipOn(info, mixerName)
            line.open(stream)
            return line
        }
    }

    private fun loopingTone(mixerName: String, style: String): Clip {
        val rate = 8000
        val data = RingtoneLibrary.render(style)
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(Clip::class.java, format)
        val line = clipOn(info, mixerName)
        line.open(format, data, 0, data.size)
        return line
    }

    private fun clipOn(info: DataLine.Info, mixerName: String): Clip {
        namedMixer(info, mixerName)?.let { return it }
        return AudioSystem.getLine(info) as Clip
    }

    private fun namedMixer(info: DataLine.Info, mixerName: String): Clip? {
        if (mixerName.isBlank()) return null
        val names = AudioSystem.getMixerInfo().map { it.name }
        val picked = matchOutputName(mixerName, names) ?: return null
        val match = AudioSystem.getMixerInfo().firstOrNull { it.name == picked } ?: return null
        val mixer = AudioSystem.getMixer(match)
        if (!mixer.isLineSupported(info)) return null
        return mixer.getLine(info) as Clip
    }

    private fun sameMixer(ringer: String, speaker: String): Boolean {
        if (speaker.isBlank()) return true
        if (ringer.equals(speaker, ignoreCase = true)) return true
        val names = outputNames()
        val left = matchOutputName(ringer, names)
        val right = matchOutputName(speaker, names)
        return left != null && left == right
    }
}

/**
 * Tray, incoming window, and the small call window.
 * The main window can close without stopping the phone.
 */
internal class DeskSurfaces(
    private val owner: JFrame,
    private val productName: String,
    private val theme: PhoneTheme,
    private val onAnswer: () -> Unit,
    private val onDecline: () -> Unit,
    private val onHangup: () -> Unit,
    private val onMute: () -> Unit,
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
    private val onPin: (Boolean) -> Unit,
) {
    private var trayIcon: TrayIcon? = null
    private var lineItem: MenuItem? = null
    private var pinItem: CheckboxMenuItem? = null
    private var dndItem: CheckboxMenuItem? = null
    private var toldTray = false
    private var noticeTimer: javax.swing.Timer? = null
    private val noticeBody = JLabel()
    private val noticeDialog = buildNotice()
    private val incomingParty = JLabel("Incoming")
    private val incomingLine = JLabel("")
    private val miniParty = JLabel("")
    private val miniClock = JLabel("")
    private val miniMute = WelcomeButton("Mute", theme.raised, theme.ink, quiet = true, quietFill = theme.raised, line = theme.hairline, expand = false)
    private val incomingDialog = buildIncoming()
    private val miniDialog = buildMini()

    fun installTray(icon: Image?) {
        if (icon == null || !SystemTray.isSupported()) return
        val menu = PopupMenu()
        val line = MenuItem("No line")
        line.isEnabled = false
        lineItem = line
        val dnd = CheckboxMenuItem("Do not disturb")
        dnd.state = WindowPrefs.dnd()
        dnd.addItemListener {
            WindowPrefs.saveDnd(dnd.state)
            if (dnd.state) CallRinger.stop()
        }
        dndItem = dnd
        val pin = CheckboxMenuItem("Pin on top")
        pin.state = WindowPrefs.pinned()
        pin.addItemListener {
            WindowPrefs.savePin(pin.state)
            onPin(pin.state)
        }
        pinItem = pin
        val open = MenuItem("Open $productName")
        open.addActionListener { onOpen() }
        val quit = MenuItem("Quit (stops ringing on this computer)")
        quit.addActionListener { onQuit() }
        menu.add(line)
        menu.add(dnd)
        menu.addSeparator()
        menu.add(pin)
        menu.add(open)
        menu.addSeparator()
        menu.add(quit)
        val iconOnTray = TrayIcon(icon, productName, menu)
        iconOnTray.isImageAutoSize = false
        iconOnTray.addActionListener { onOpen() }
        runCatching { SystemTray.getSystemTray().add(iconOnTray) }.onSuccess { trayIcon = iconOnTray }
    }

    fun setToggles(pinned: Boolean, dnd: Boolean) {
        if (pinItem?.state != pinned) pinItem?.state = pinned
        if (dndItem?.state != dnd) dndItem?.state = dnd
    }

    fun tooltip(text: String) {
        trayIcon?.toolTip = text
    }

    private var trayKey = ""

    fun applyStatus(
        flavor: String,
        lightTaskbar: Boolean,
        registered: Boolean,
        onCall: Boolean,
        attention: Int,
        extension: String,
        detail: String,
    ) {
        tooltip(detail)
        val quiet = WindowPrefs.dnd()
        lineItem?.label = when {
            extension.isBlank() -> "No line"
            !registered -> "$extension · Not registered"
            quiet -> "$extension · Do not disturb"
            onCall -> "$extension · On a call"
            else -> "$extension · Available"
        }
        val state = when {
            !registered -> "offline"
            onCall -> "oncall"
            quiet -> "dnd"
            attention > 0 -> "missed"
            else -> "available"
        }
        val theme = if (lightTaskbar) "light" else "dark"
        val key = "$flavor|$theme|$state"
        if (key == trayKey) return
        val icon = trayIcon ?: return
        val image = DesktopIcons.trayImage(flavor, theme, state) ?: return
        icon.image = image
        icon.isImageAutoSize = false
        trayKey = key
    }

    /** Hide the main window and leave the phone running. Returns false when there is no tray. */
    fun hideToTray(): Boolean {
        if (trayIcon == null) return false
        owner.isVisible = false
        if (!toldTray) {
            showNotice("Still running in the tray so this PC can ring.")
            toldTray = true
        }
        return true
    }

    fun notifyMessage(peer: String) {
        showNotice("New message from $peer")
    }

    fun announce(body: String) = showNotice(body)

    /** A small notice that leaves on its own. Windows tray toasts stay until they are closed. */
    private fun showNotice(body: String) {
        if (incomingDialog.isVisible) return
        val safe = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        noticeBody.text = "<html><body style='width:260px'>$safe</body></html>"
        noticeDialog.pack()
        noticeDialog.setSize(320, noticeDialog.height.coerceAtLeast(72))
        place(noticeDialog, top = false)
        noticeDialog.isVisible = true
        noticeTimer?.stop()
        noticeTimer = javax.swing.Timer(4_000) { noticeDialog.isVisible = false }.also {
            it.isRepeats = false
            it.start()
        }
    }

    fun sync(incoming: Boolean, onCall: Boolean, party: String, clock: String, extension: String, mainInFront: Boolean, muted: Boolean) {
        miniMute.text = if (muted) "Unmute" else "Mute"
        incomingParty.text = party.ifBlank { "Incoming call" }
        incomingLine.text = if (extension.isBlank()) "Incoming" else "Incoming on $extension"
        if (incoming) {
            if (noticeDialog.isVisible) {
                noticeTimer?.stop()
                noticeDialog.isVisible = false
            }
            if (!incomingDialog.isVisible) {
                place(incomingDialog, top = false)
                incomingDialog.isVisible = true
                incomingDialog.toFront()
                incomingDialog.rootPane.defaultButton?.requestFocusInWindow()
            }
            if (WindowPrefs.dnd()) CallRinger.stop() else CallRinger.start()
        } else if (incomingDialog.isVisible) {
            incomingDialog.isVisible = false
            CallRinger.stop()
        }

        val showMini = onCall && !incoming && !mainInFront
        miniParty.text = party.ifBlank { "On a call" }
        miniClock.text = clock
        if (showMini) {
            if (!miniDialog.isVisible) {
                place(miniDialog, top = true)
                miniDialog.isVisible = true
            }
        } else if (miniDialog.isVisible) {
            miniDialog.isVisible = false
        }
    }

    private fun buildNotice(): JDialog {
        val dialog = JDialog()
        dialog.isAlwaysOnTop = true
        dialog.isModal = false
        dialog.isUndecorated = true
        dialog.type = java.awt.Window.Type.POPUP
        dialog.focusableWindowState = false
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = theme.surface
        panel.border = javax.swing.border.CompoundBorder(
            javax.swing.border.LineBorder(theme.hairline),
            EmptyBorder(12, 14, 12, 14),
        )
        val title = JLabel(productName)
        title.font = Font("Segoe UI", Font.BOLD, UiScale.px(13))
        title.foreground = theme.gold
        title.alignmentX = JLabel.LEFT_ALIGNMENT
        noticeBody.font = Font("Segoe UI", Font.PLAIN, UiScale.px(13))
        noticeBody.foreground = theme.ink
        noticeBody.alignmentX = JLabel.LEFT_ALIGNMENT
        panel.add(title)
        panel.add(Box.createVerticalStrut(4))
        panel.add(noticeBody)
        dialog.contentPane = panel
        val dismiss = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                noticeTimer?.stop()
                dialog.isVisible = false
            }
        }
        panel.addMouseListener(dismiss)
        title.addMouseListener(dismiss)
        noticeBody.addMouseListener(dismiss)
        return dialog
    }

    private fun buildIncoming(): JDialog {
        val dialog = JDialog()
        dialog.title = productName
        dialog.isAlwaysOnTop = true
        dialog.isModal = false
        dialog.type = java.awt.Window.Type.UTILITY
        dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = theme.ground
        panel.border = EmptyBorder(16, 16, 16, 16)
        incomingLine.font = Font("Segoe UI", Font.PLAIN, UiScale.px(13))
        incomingLine.foreground = theme.muted
        incomingLine.alignmentX = JLabel.LEFT_ALIGNMENT
        incomingParty.font = Font("Georgia", Font.PLAIN, UiScale.px(28))
        incomingParty.foreground = theme.ink
        incomingParty.alignmentX = JLabel.LEFT_ALIGNMENT
        val answer = WelcomeButton("Answer", theme.gold, theme.goldInk, expand = false)
        answer.addActionListener { onAnswer() }
        val decline = WelcomeButton("Decline", theme.coral, theme.ground, expand = false)
        decline.addActionListener { onDecline() }
        val row = JPanel()
        row.background = theme.ground
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.alignmentX = JLabel.LEFT_ALIGNMENT
        row.add(answer)
        row.add(Box.createHorizontalStrut(8))
        row.add(decline)
        panel.add(incomingLine)
        panel.add(Box.createVerticalStrut(6))
        panel.add(incomingParty)
        panel.add(Box.createVerticalStrut(16))
        panel.add(row)
        dialog.contentPane = panel
        dialog.rootPane.defaultButton = answer
        dialog.rootPane.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke("ESCAPE"),
            "decline-call",
        )
        dialog.rootPane.actionMap.put("decline-call", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = onDecline()
        })
        dialog.pack()
        dialog.setSize(UiScale.px(380), dialog.height.coerceAtLeast(UiScale.px(180)))
        return dialog
    }

    private fun buildMini(): JDialog {
        val dialog = JDialog()
        dialog.title = productName
        dialog.isAlwaysOnTop = true
        dialog.isModal = false
        dialog.type = java.awt.Window.Type.UTILITY
        dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        val panel = JPanel(BorderLayout(12, 0))
        panel.background = theme.ground
        panel.border = EmptyBorder(12, 14, 12, 14)
        miniParty.font = Font("Segoe UI", Font.BOLD, UiScale.px(14))
        miniParty.foreground = theme.ink
        miniClock.font = Font("Consolas", Font.PLAIN, UiScale.px(13))
        miniClock.foreground = theme.gold
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.background = theme.ground
        text.add(miniParty)
        text.add(miniClock)
        miniMute.addActionListener { onMute() }
        val hang = WelcomeButton("Hang up", theme.coral, theme.ground, expand = false)
        hang.addActionListener { onHangup() }
        val open = WelcomeButton("Open", theme.raised, theme.ink, quiet = true, quietFill = theme.raised, line = theme.hairline, expand = false)
        open.addActionListener { onOpen() }
        val actions = JPanel()
        actions.background = theme.ground
        actions.layout = BoxLayout(actions, BoxLayout.X_AXIS)
        actions.add(miniMute)
        actions.add(Box.createHorizontalStrut(8))
        actions.add(hang)
        actions.add(Box.createHorizontalStrut(8))
        actions.add(open)
        panel.add(text, BorderLayout.CENTER)
        panel.add(actions, BorderLayout.EAST)
        dialog.contentPane = panel
        dialog.pack()
        dialog.setSize(UiScale.px(560), dialog.height.coerceAtLeast(UiScale.px(88)))
        dialog.minimumSize = Dimension(UiScale.px(480), UiScale.px(80))
        return dialog
    }

    private fun place(dialog: JDialog, top: Boolean) {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = screen.x + screen.width - dialog.width - 24
        val y = if (top) screen.y + 24 else screen.y + screen.height - dialog.height - 48
        dialog.setLocation(x.coerceAtLeast(screen.x), y.coerceAtLeast(screen.y))
    }
}
