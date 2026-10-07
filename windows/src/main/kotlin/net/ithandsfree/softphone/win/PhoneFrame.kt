package net.ithandsfree.softphone.win

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * Welcome, then Calls, Messages, Lines, and Settings.
 * Colours follow the night skin. GPL-2.0.
 */
class PhoneFrame(
    private val profile: DistributionProfile,
    private var host: HostConfig,
) : JFrame(profile.productName) {
    private val session = PhoneSession(host)
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val theme = phoneTheme(profile.id)
    private val ground = theme.ground
    private val surface = theme.surface
    private val raised = theme.raised
    private val gold = theme.gold
    private val goldInk = theme.goldInk
    private val ink = theme.ink
    private val muted = theme.muted
    private val caption = theme.caption
    private val emerald = theme.emerald
    private val coral = theme.coral
    private val hairline = theme.hairline

    private val serif = Font("Georgia", Font.PLAIN, UiScale.px(32))
    private val uiFont = Font("Segoe UI", Font.PLAIN, UiScale.px(15))
    private val uiBold = Font("Segoe UI", Font.BOLD, UiScale.px(14))
    private val mono = Font("Consolas", Font.PLAIN, UiScale.px(22))

    private val rootCards = CardLayout()
    private val root = JPanel(rootCards)
    private val appCards = CardLayout()
    private val appBody = JPanel(appCards)
    private val status = label("")
    private val voice = label("Voice idle")
    private val liveTimer = label("").apply {
        font = mono
        foreground = gold
    }
    private val dial = DialEntry(caption)
    private val callState = label("No call")
    private val smsTo = field()
    private val smsBody = JTextArea(3, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        background = raised
        foreground = ink
        caretColor = gold
        font = uiFont
        border = EmptyBorder(8, 10, 8, 10)
    }
    private val attachNote = JLabel("").apply {
        font = Font("Segoe UI", Font.PLAIN, 12)
        foreground = muted
        isVisible = false
    }
    private var pendingPhoto: MmsPhoto? = null
    private val messageLine = label("No line")
    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ground
        border = EmptyBorder(16, 16, 16, 16)
    }
    private val threadTitle = label("")
    private val threadBody = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = surface
        foreground = ink
        font = uiFont
        border = EmptyBorder(8, 8, 8, 8)
    }
    private val threadModel = DefaultListModel<ThreadInfo>()
    private val threadList = JList(threadModel).apply {
        background = surface
        foreground = ink
        font = uiFont
        selectionBackground = raised
        selectionForeground = ink
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = 56
    }
    private var pendingEmail = ""
    private val sentBody = JLabel(" ")
    private val resendButton = WelcomeButton("Resend in 0:30", gold, goldInk, quiet = true)
    private var resendLeft = 0
    private var resendTicker: Timer? = null
    private var callStartedAt = 0L
    private var callMode = ""
    private var lastParty = ""
    private var lastIncoming = false
    private var lastConnected = false
    private val recentRows = JPanel()
    private var recentEntries: List<CallEntry> = emptyList()
    private var recentIndex = -1
    private var missedOnly = false
    private var historyExtension: String? = null
    private var lastCallExt = ""
    private var paintedLines = ""
    private var browsingDuringCall = false
    private var detailOpen = false
    private var undoRecent: CallEntry? = null
    private val detailTitle = JLabel("Call")
    private val detailMeta = JLabel("")
    private val detailHistory = JPanel()
    private val findBox = PromptField("Search contacts or dial")
    private val headerStatus = JLabel("No line")
    private lateinit var recentsPane: JPanel
    private var narrowList = true
    private val recentEmpty = label("No recent calls yet.")
    private val allRecentButton = JButton("All lines")
    private val missedRecentButton = JButton("Missed")
    private val lineSwitch = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0))
    private val callTabs = JPanel()
    private val callsBody = JPanel(java.awt.CardLayout())
    private val contactsList = JPanel()
    private var callsView = "recents"
    private val historyFilters = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0))
    private val lineList = JPanel()
    private val keypadJump = JButton("Keypad")
    private var askedRegister = false
    private var knownUnread = -1
    private var threadSignature = ""
    private val photoCache = LinkedHashMap<String, java.awt.Image>()
    private val photoFull = LinkedHashMap<String, java.awt.Image>()
    private var knownOutputs: Set<String> = emptySet()
    private var offeredHeadsetName: String? = null
    private lateinit var headsetOffer: javax.swing.JButton
    private lateinit var welcomeBack: WelcomeButton
    private var pollTick = 0
    private var suppressThreadOpen = false
    private lateinit var surfaces: DeskSurfaces
    private val tabs = linkedMapOf<String, JButton>()

    private val callButton = WelcomeButton("Call", gold, goldInk, expand = false).apply {
        addActionListener { placeCall() }
        preferredSize = Dimension(260, 52)
        toolTipText = "Call (Ctrl Enter)"
    }
    private val lineChip = label("No line")
    private val fromLine = label("")
    private val activeCallRow = WelcomeButton(
        "Return to call",
        raised,
        gold,
        quiet = true,
        quietFill = raised,
        line = gold,
        expand = true,
        focusRing = gold,
    ).apply {
        isVisible = false
        toolTipText = "Return to the call"
        addActionListener {
            browsingDuringCall = false
            showRoot("app")
            showTab("calls")
            callDetailCards.show(callDetail, "live")
        }
    }
    private val inCallParty = JLabel("").apply {
        font = Font("Georgia", Font.PLAIN, UiScale.px(36))
        foreground = ink
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val inCallClock = label("")
    private val callDetailCards = CardLayout()
    private val callDetail = JPanel(callDetailCards)
    private val answerButton = goldButton("Answer") { work("Answer") { session.answer(); note("Answered") } }
    private val declineButton = coralButton("Decline") {
        work("Decline") {
            if (SipBridge.snapshot().incoming) session.decline() else session.hangup()
        }
    }
    private val hangButton = WelcomeButton("Hang up", coral, ground, expand = false, compact = true).apply {
        toolTipText = "End (Ctrl D)"
        addActionListener { work("Hang up") { session.hangup() } }
    }
    private val muteButton = WelcomeButton("Mute", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold, compact = true).apply {
        toolTipText = "Mute (M)"
        addActionListener { toggleMute() }
    }
    private val holdButton = WelcomeButton("Hold", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold, compact = true).apply {
        toolTipText = "Hold (H)"
        addActionListener { toggleHold() }
    }
    private val transferButton = WelcomeButton("Transfer", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold, compact = true).apply {
        toolTipText = "Transfer (T)"
        addActionListener { openTransfer() }
    }
    private val keypadButton = WelcomeButton("Keypad", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold, compact = true).apply {
        toolTipText = "Keypad (K)"
        addActionListener { toggleDtmfPad() }
    }
    private var showDtmf = false
    private val dtmfPad = JPanel(GridLayout(4, 3, 8, 8))
    private val transferField = PromptField("Extension or number")
    private val transferDialog = JDialog()
    private var speakButton: JButton? = null
    private var finishConsultButton: JButton? = null
    private var cancelConsultButton: JButton? = null
    private var dndFetched = false
    private val micModel = javax.swing.DefaultComboBoxModel<SipBridge.AudioDevice>()
    private val speakerModel = javax.swing.DefaultComboBoxModel<SipBridge.AudioDevice>()
    private val ringerModel = javax.swing.DefaultComboBoxModel<String>()
    private val ringtoneModel = javax.swing.DefaultComboBoxModel<String>()
    private var fillingAudio = false
    private val audioNote = label("Sound devices appear after the line registers.")
    private val micMeter = MicMeter(raised, gold, hairline)
    private var micPeak = 0
    private var micPeakAt = 0L
    private var pinBox: javax.swing.JCheckBox? = null
    private var dndBox: javax.swing.JCheckBox? = null
    private lateinit var removeRingtone: JComponent

    init {
        val windowIcons = DesktopIcons.windowIcons(DesktopIcons.flavor(profile))
        if (windowIcons.isNotEmpty()) iconImages = windowIcons
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane.background = ground
        root.background = ground
        root.add(welcome(), "welcome")
        root.add(scroll(linkSent()), "sent")
        root.add(scroll(codeEntry()), "code")
        if (profile.serverEditable) root.add(scroll(advanced()), "advanced")
        root.add(appShell(), "app")
        contentPane.add(root)
        val area = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        minimumSize = Dimension(400.coerceAtMost(area.width), 640.coerceAtMost(area.height))
        val saved = WindowPrefs.bounds()
        if (saved != null) {
            val width = saved.width.coerceIn(minimumSize.width, area.width)
            val height = saved.height.coerceIn(minimumSize.height, area.height)
            val x = saved.x.coerceIn(area.x, (area.x + area.width - width).coerceAtLeast(area.x))
            val y = saved.y.coerceIn(area.y, (area.y + area.height - height).coerceAtLeast(area.y))
            setBounds(x, y, width, height)
        } else {
            setBounds(area)
        }
        isAlwaysOnTop = WindowPrefs.pinned()
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(event: java.awt.event.ComponentEvent) = rememberWindow()
            override fun componentResized(event: java.awt.event.ComponentEvent) = rememberWindow()
        })

        threadList.addListSelectionListener { event ->
            if (suppressThreadOpen || event.valueIsAdjusting) return@addListSelectionListener
            val picked = threadList.selectedValue ?: return@addListSelectionListener
            openThread(picked)
        }
        val savedLines = LineBook.load()
        if (savedLines.isNotEmpty()) {
            session.restoreAll(savedLines, LineBook.defaultExtension())
            showRoot("app")
            showTab("calls")
            refreshLine()
            askedRegister = true
            work("Register") {
                session.registerAll(userAgent = "${profile.userAgentName}/0.1.0 PJSUA")
                note("Registration sent")
                SwingUtilities.invokeLater { refreshLine() }
            }
        } else {
            showRoot(if (profile.serverEditable && (host.apiBase.isBlank() || host.sipDomain.isBlank())) "advanced" else "welcome")
        }

        surfaces = DeskSurfaces(
            owner = this,
            productName = profile.productName,
            theme = theme,
            onAnswer = {
                reveal()
                work("Answer") { session.answer(); note("Answered") }
            },
            onDecline = {
                work("Decline") {
                    if (SipBridge.snapshot().incoming) session.decline() else session.hangup()
                }
            },
            onHangup = { work("Hang up") { session.hangup() } },
            onMute = { toggleMute() },
            onOpen = { reveal() },
            onQuit = { quitPhone() },
            onPin = { pinned -> isAlwaysOnTop = pinned },
        )
        surfaces.installTray(windowIcons.firstOrNull())
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(event: java.awt.event.WindowEvent) {
                if (!surfaces.hideToTray()) quitPhone()
            }
        })

        buildTransferDialog()
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { event ->
            dispatchShortcut(event)
        }

        Timer(300) { refreshCall() }.apply { isRepeats = true; start() }
        Timer(80) { paintMicLevel() }.apply { isRepeats = true; start() }
    }

    internal fun reveal() {
        if ((extendedState and java.awt.Frame.ICONIFIED) != 0) {
            extendedState = java.awt.Frame.NORMAL
        }
        isVisible = true
        extendedState = java.awt.Frame.NORMAL
        val pinned = WindowPrefs.pinned()
        isAlwaysOnTop = true
        toFront()
        requestFocus()
        isAlwaysOnTop = pinned
    }

    private fun rememberWindow() {
        if (!isVisible || (extendedState and java.awt.Frame.ICONIFIED) != 0) return
        if (width < 360 || height < 400) return
        WindowPrefs.saveBounds(x, y, width, height)
        applyWindowShape()
    }

    private fun applyWindowShape() {
        if (!::recentsPane.isInitialized) return
        val narrow = width in 1..719
        keypadJump.isVisible = narrow
        recentsPane.isVisible = !narrow || narrowList
        callDetail.isVisible = !narrow || !narrowList
        recentsPane.revalidate()
    }

    private fun eraseDialDigit() {
        val raw = dial.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
        if (raw.isEmpty()) return
        dial.text = raw.dropLast(1)
    }

    private fun toggleMute() {
        val next = !SipBridge.snapshot().muted
        work("Mute") {
            if (SipBridge.setMute(next) != 0) note("Mute failed")
        }
    }

    private fun toggleHold() {
        val held = SipBridge.snapshot().held
        work(if (held) "Resume" else "Hold") {
            if (held) session.resume() else session.hold()
        }
    }

    private fun toggleDtmfPad() {
        showDtmf = !showDtmf
        dtmfPad.isVisible = showDtmf && SipBridge.snapshot().callActive
        dtmfPad.revalidate()
    }

    private fun sendDtmf(digit: String) {
        work("Keypad") { session.dtmf(digit) }
    }

    private fun openTransfer() {
        if (!SipBridge.snapshot().callActive) return
        transferField.text = ""
        transferDialog.isVisible = true
        transferDialog.toFront()
        transferField.requestFocus()
    }

    private fun buildTransferDialog() {
        transferDialog.title = "Transfer"
        transferDialog.isAlwaysOnTop = true
        transferDialog.isModal = false
        transferDialog.defaultCloseOperation = WindowConstants.HIDE_ON_CLOSE
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = ground
        panel.border = EmptyBorder(16, 16, 16, 16)
        val title = JLabel("Transfer this call")
        title.font = serif
        title.foreground = ink
        title.alignmentX = Component.LEFT_ALIGNMENT
        transferField.background = surface
        transferField.foreground = ink
        transferField.caretColor = gold
        transferField.font = uiFont
        transferField.alignmentX = Component.LEFT_ALIGNMENT
        transferField.maximumSize = Dimension(360, 44)
        val send = WelcomeButton("Transfer now", gold, goldInk, expand = false)
        send.addActionListener { submitTransfer() }
        send.alignmentX = Component.LEFT_ALIGNMENT
        val speak = WelcomeButton("Speak first", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold)
        speak.addActionListener { speakFirst() }
        speak.alignmentX = Component.LEFT_ALIGNMENT
        val finish = WelcomeButton("Complete transfer", gold, goldInk, expand = false)
        finish.isVisible = false
        finish.addActionListener {
            transferDialog.isVisible = false
            work("Transfer") { session.consultFinish(); note("Transfer sent") }
        }
        finish.alignmentX = Component.LEFT_ALIGNMENT
        val back = ghost("Back to the first call", expand = false) {
            transferDialog.isVisible = false
            work("Transfer") { session.consultCancel(); note("Back on the first call") }
        }
        speakButton = speak
        finishConsultButton = finish
        cancelConsultButton = back
        val cancel = ghost("Cancel", expand = false) { transferDialog.isVisible = false }
        panel.add(title)
        panel.add(Box.createVerticalStrut(8))
        panel.add(wrappingCopy("The other person is sent straight to this number."))
        panel.add(Box.createVerticalStrut(12))
        panel.add(transferField)
        panel.add(Box.createVerticalStrut(12))
        panel.add(send)
        panel.add(Box.createVerticalStrut(8))
        panel.add(speak)
        panel.add(Box.createVerticalStrut(8))
        panel.add(finish)
        panel.add(Box.createVerticalStrut(8))
        panel.add(back)
        panel.add(Box.createVerticalStrut(8))
        panel.add(cancel)
        transferDialog.contentPane = panel
        transferField.addActionListener { submitTransfer() }
        transferDialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            javax.swing.KeyStroke.getKeyStroke("ESCAPE"),
            "close-transfer",
        )
        transferDialog.rootPane.actionMap.put("close-transfer", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                transferDialog.isVisible = false
            }
        })
        transferDialog.pack()
        transferDialog.setSize(UiScale.px(380), transferDialog.height.coerceAtLeast(UiScale.px(240)))
        transferDialog.setLocationRelativeTo(this)
    }

    private fun speakFirst() {
        val number = transferField.text.trim()
        if (number.isBlank()) return
        work("Transfer") { session.consult(number); note("Calling the other person. The first call stays on hold.") }
    }

    private fun browse(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    private fun openPhoto(label: JLabel, image: java.awt.Image) {
        label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) = showLargePhoto(image)
        })
    }

    private fun showLargePhoto(image: java.awt.Image) {
        val dialog = JDialog(this, "Photo")
        dialog.isModal = false
        val width = image.getWidth(null).coerceIn(1, 960)
        val height = image.getHeight(null).coerceIn(1, 720)
        val view = JLabel(javax.swing.ImageIcon(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH)))
        dialog.contentPane = JScrollPane(view)
        dialog.setSize((width + 32).coerceAtMost(1000), (height + 48).coerceAtMost(780))
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun submitTransfer() {
        val number = transferField.text.trim()
        if (number.isBlank()) return
        transferDialog.isVisible = false
        work("Transfer") { session.transfer(number); note("Transfer sent") }
    }

    private fun dispatchShortcut(event: java.awt.event.KeyEvent): Boolean {
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return false
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val typing = focus is JTextField || focus is JTextArea
        val ctrl = event.isControlDown
        val alt = event.isAltDown
        val plain = !ctrl && !alt && !event.isMetaDown
        val snap = SipBridge.snapshot()
        val onCall = snap.callActive && !snap.incoming
        return when {
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_ENTER -> {
                answerOrCall(snap)
                true
            }
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_D -> {
                endOrDecline(snap)
                true
            }
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_N -> {
                startNewMessage()
                true
            }
            ctrl && event.keyCode == java.awt.event.KeyEvent.VK_K -> {
                focusDial()
                true
            }
            ctrl && !event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_1 -> {
                chooseLine(0); true
            }
            ctrl && !event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_2 -> {
                chooseLine(1); true
            }
            alt && event.keyCode == java.awt.event.KeyEvent.VK_1 -> {
                showRoot("app"); showTab("calls"); true
            }
            alt && event.keyCode == java.awt.event.KeyEvent.VK_2 -> {
                showRoot("app"); showTab("messages"); true
            }
            alt && event.keyCode == java.awt.event.KeyEvent.VK_3 -> {
                showRoot("app"); showTab("lines"); true
            }
            alt && event.keyCode == java.awt.event.KeyEvent.VK_4 -> {
                showRoot("app"); showTab("settings"); loadAudioDevices(); true
            }
            plain && !typing && (event.keyChar == '?' || (event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_SLASH)) -> {
                showShortcuts()
                true
            }
            plain && !typing && onCall && event.keyCode == java.awt.event.KeyEvent.VK_M -> {
                toggleMute(); true
            }
            plain && !typing && !onCall && currentTab == "calls" && event.keyCode == java.awt.event.KeyEvent.VK_M -> {
                selectedRecent()?.let { messageRecent(it) }; true
            }
            plain && !typing && !onCall && currentTab == "calls" && event.keyCode == java.awt.event.KeyEvent.VK_ENTER -> {
                selectedRecent()?.let { callRecent(it) }; true
            }
            !typing && currentTab == "calls" && event.isControlDown && event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_C -> {
                selectedRecent()?.let { copyRecent(it) }; true
            }
            plain && !typing && currentTab == "calls" && event.keyCode == java.awt.event.KeyEvent.VK_DELETE -> {
                selectedRecent()?.let { removeRecent(it) }; true
            }
            plain && !typing && detailOpen && event.keyCode == java.awt.event.KeyEvent.VK_ESCAPE -> {
                closeRecentDetail(); true
            }
            plain && !typing && detailOpen && event.keyChar in "0123456789*#" -> {
                val onCallNow = snap.callActive || snap.incoming
                closeRecentDetail()
                if (!onCallNow) {
                    dial.text += event.keyChar
                    dial.requestFocusInWindow()
                }
                true
            }
            plain && !typing && currentTab == "calls" && event.keyCode == java.awt.event.KeyEvent.VK_DOWN -> {
                moveRecent(1); true
            }
            plain && !typing && currentTab == "calls" && event.keyCode == java.awt.event.KeyEvent.VK_UP -> {
                moveRecent(-1); true
            }
            plain && event.isControlDown && event.keyCode == java.awt.event.KeyEvent.VK_Z && undoRecent != null -> {
                CallLog.append(undoRecent ?: return false)
                undoRecent = null
                reloadRecents()
                note("Restored")
                true
            }
            !typing && currentTab == "calls" && (
                event.keyCode == java.awt.event.KeyEvent.VK_CONTEXT_MENU ||
                    (event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_F10)
                ) -> {
                selectedRecent()?.let { entry -> recentMenu(entry).show(recentRows, 24, 24) }
                true
            }
            plain && !typing && onCall && event.keyCode == java.awt.event.KeyEvent.VK_H -> {
                toggleHold(); true
            }
            plain && !typing && onCall && event.keyCode == java.awt.event.KeyEvent.VK_T -> {
                openTransfer(); true
            }
            plain && !typing && onCall && event.keyCode == java.awt.event.KeyEvent.VK_K -> {
                toggleDtmfPad(); true
            }
            plain && !typing && onCall && showDtmf && event.keyChar in "0123456789*#" -> {
                sendDtmf(event.keyChar.toString())
                true
            }
            else -> false
        }
    }

    private fun answerOrCall(snap: SipBridge.Snapshot) {
        when {
            snap.incoming -> work("Answer") { session.answer(); note("Answered") }
            !snap.callActive -> placeCall()
        }
    }

    private fun endOrDecline(snap: SipBridge.Snapshot) {
        when {
            snap.incoming -> work("Decline") { session.decline() }
            snap.callActive -> work("Hang up") { session.hangup() }
        }
    }

    private fun focusDial() {
        showRoot("app")
        showTab("calls")
        narrowList = false
        applyWindowShape()
        findBox.requestFocus()
    }

    private fun startNewMessage() {
        showRoot("app")
        showTab("messages")
        threadList.clearSelection()
        smsTo.text = ""
        threadTitle.text = "New message"
        transcript.removeAll()
        transcript.revalidate()
        smsTo.requestFocus()
    }

    private fun showShortcuts() {
        val dialog = JDialog(this, "Keyboard shortcuts")
        dialog.isModal = false
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = ground
        panel.border = EmptyBorder(18, 18, 18, 18)
        val title = JLabel("Keyboard shortcuts")
        title.font = serif
        title.foreground = ink
        title.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(title)
        panel.add(Box.createVerticalStrut(12))
        listOf(
            "Ctrl K  focuses the number",
            "Ctrl N  new message",
            "Ctrl Enter  call or answer",
            "Ctrl D  end or decline",
            "Alt 1 to Alt 4  Calls, Messages, Lines, Settings",
            "Ctrl 1 and Ctrl 2  switch lines",
            "During a call, when you are not typing:",
            "M mute,  H hold,  T transfer,  K keypad",
            "Enter sends a message. Shift Enter starts a new line.",
            "? opens this list",
        ).forEach { line ->
            panel.add(wrappingCopy(line))
            panel.add(Box.createVerticalStrut(6))
        }
        val close = ghost("Close", expand = false) { dialog.dispose() }
        panel.add(Box.createVerticalStrut(8))
        panel.add(close)
        dialog.contentPane = panel
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            javax.swing.KeyStroke.getKeyStroke("ESCAPE"),
            "close-shortcuts",
        )
        dialog.rootPane.actionMap.put("close-shortcuts", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = dialog.dispose()
        })
        dialog.pack()
        dialog.setSize(UiScale.px(460), dialog.height.coerceAtLeast(UiScale.px(360)))
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun quitPhone() {
        CallRinger.stop()
        runCatching { SipBridge.stop() }
        dispose()
        kotlin.system.exitProcess(0)
    }

    private fun welcome(): JPanel {
        val email = promptField("you@company.com")
        val error = label("")
        error.foreground = coral
        val send = WelcomeButton("Email me a setup link", gold, goldInk)
        send.addActionListener {
            error.text = ""
            if (!looksLikeEmail(email.text)) {
                error.text = "Enter a work email"
                return@addActionListener
            }
            requestLink(email.text.trim())
        }
        val paste = promptField("https://… or [SETUP-CODE]")
        val hero = banner(theme.headlineLead, theme.headlineEmphasis, edge = true)
        val body = stack()
        body.border = javax.swing.border.EmptyBorder(0, 0, 0, 0)
        grow(body, JLabel("Set up this computer").apply {
            font = serif
            foreground = ink
        }, 10)
        grow(body, wrappingCopy("Use the setup email from your administrator. If you also use the app on your phone, both can ring at once."))
        grow(body, label("Work email"), 4)
        grow(body, email)
        grow(body, error, 4)
        grow(body, send)
        grow(body, orDivider())
        grow(body, label("Paste a setup link or code"), 4)
        val pasteRow = javax.swing.JPanel(java.awt.BorderLayout(8, 0))
        pasteRow.background = ground
        pasteRow.add(paste, java.awt.BorderLayout.CENTER)
        pasteRow.add(ghost("Continue", expand = false) { enrolFrom(paste.text) }, java.awt.BorderLayout.EAST)
        grow(body, pasteRow)
        welcomeBack = ghost("Back to the phone", expand = false) { leaveSetup() }
        welcomeBack.isVisible = false
        grow(body, welcomeBack, 8)
        grow(body, wrappingCopy("Opening the setup email on this computer and clicking Set up opens the app by itself."))
        val footerRow = footer(
            if (profile.serverEditable) "Advanced setup" else "",
            profile.footerCaption,
        ) { showRoot("advanced") }
        val form = object : JPanel() {
            override fun doLayout() {
                val cap = 440.coerceAtMost((width - 96).coerceAtLeast(280))
                val x = 64.coerceAtMost(width - cap - 28).coerceAtLeast(36)
                val footerH = 28
                val footerY = height - 32 - footerH
                footerRow.setBounds(x, footerY, cap, footerH)
                val bodyH = body.preferredSize.height.coerceAtMost((footerY - 56).coerceAtLeast(1))
                val bodyY = ((footerY - bodyH) / 2).coerceAtLeast(48)
                body.setBounds(x, bodyY, cap, bodyH)
            }
        }
        form.layout = null
        form.background = ground
        form.add(body)
        form.add(footerRow)
        val page = object : JPanel() {
            override fun getPreferredSize(): Dimension = Dimension(1200, 720)
            override fun doLayout() {
                val scale = (graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat().coerceAtLeast(1f)
                val shown = (height / scale).toInt().coerceIn(480, height)
                val heroW = (width * 0.42f).toInt().coerceIn(400, 600)
                hero.setBounds(0, 0, heroW, shown)
                form.setBounds(heroW, 0, (width - heroW).coerceAtLeast(0), shown)
            }
        }
        page.layout = null
        page.background = ground
        page.add(hero)
        page.add(form)
        return page
    }

    private fun linkSent(): JPanel {
        sentBody.foreground = muted
        sentBody.font = uiFont
        resendButton.addActionListener {
            if (pendingEmail.isNotBlank() && resendButton.isEnabled) requestLink(pendingEmail)
        }
        val page = JPanel(java.awt.BorderLayout())
        page.background = ground
        page.add(
            banner("Check your", "inbox.", compact = true, onBack = { showRoot("welcome") }),
            java.awt.BorderLayout.NORTH,
        )
        val body = stack()
        grow(body, sentBody)
        grow(body, noteCard("Works once, expires in 72 hours", "No password in the email."))
        grow(body, primary("Open email app") { openMail() })
        val pair = javax.swing.JPanel(java.awt.GridLayout(1, 2, 8, 0))
        pair.background = ground
        pair.add(resendButton)
        pair.add(ghost("Use another email") { showRoot("welcome") })
        grow(body, pair)
        grow(body, mutedCopy("Nothing after a minute? Check spam, or ask your IT administrator to send it from the PBX."))
        grow(body, ghost("I have a code instead") { showRoot("code") })
        grow(body, footer("", profile.footerCaption) { }, 0)
        page.add(body, java.awt.BorderLayout.CENTER)
        return page
    }

    private fun codeEntry(): JPanel {
        val paste = field()
        val page = column()
        page.border = javax.swing.border.EmptyBorder(0, 0, 16, 0)
        page.add(banner("Enter the", "code.", compact = true, onBack = { leaveSetup() }))
        val body = column()
        body.add(pin(label("Setup link or code")))
        body.add(pin(paste))
        body.add(Box.createVerticalStrut(8))
        body.add(pin(primary("Enrol this line") { enrolFrom(paste.text) }))
        body.add(Box.createVerticalStrut(8))
        body.add(footer("Back", profile.footerCaption) { leaveSetup() })
        page.add(body)
        return page
    }

    private fun advanced(): JPanel {
        val api = field().apply { text = host.apiBase }
        val sip = field().apply { text = host.sipDomain }
        val page = column()
        page.add(brand())
        page.add(Box.createVerticalStrut(18))
        page.add(pin(JLabel("Advanced setup").apply { font = serif; foreground = ink }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(label("PBX softphone URL")))
        page.add(pin(api))
        page.add(pin(label("SIP domain")))
        page.add(pin(sip))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(goldButton("Save server") {
            work("Server") {
                val next = HostConfig(api.text.trim(), sip.text.trim())
                session.use(next)
                host = next
                note("Server saved")
                SwingUtilities.invokeLater { showRoot("welcome") }
            }
        }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghostButton("Back") { showRoot("welcome") }))
        return page
    }

    private fun appShell(): JPanel {
        val shell = JPanel(BorderLayout())
        shell.background = ground
        shell.add(desktopHeader(), BorderLayout.NORTH)
        val body = JPanel(BorderLayout())
        body.background = ground
        body.add(nav(), BorderLayout.WEST)
        appBody.background = ground
        appBody.add(calls(), "calls")
        appBody.add(messages(), "messages")
        appBody.add(thread(), "thread")
        appBody.add(lines(), "lines")
        appBody.add(scroll(settings()), "settings")
        body.add(appBody, BorderLayout.CENTER)
        shell.add(body, BorderLayout.CENTER)
        showTab("calls")
        return shell
    }

    private fun desktopHeader(): JPanel {
        val bar = JPanel(BorderLayout(16, 0))
        bar.background = ground
        bar.border = EmptyBorder(10, 16, 10, 16)
        val brand = JLabel(profile.productName)
        brand.font = Font("Georgia", Font.PLAIN, UiScale.px(18))
        brand.foreground = ink
        bar.add(brand, BorderLayout.WEST)
        findBox.font = uiFont
        findBox.background = raised
        findBox.foreground = ink
        findBox.caretColor = gold
        findBox.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(hairline),
            EmptyBorder(8, 12, 8, 12),
        )
        findBox.toolTipText = "Search contacts and messages, or type a number (Ctrl K)"
        findBox.addActionListener { searchOrDial() }
        findBox.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
        })
        val searchWrap = JPanel(BorderLayout())
        searchWrap.isOpaque = false
        searchWrap.border = EmptyBorder(0, 8, 0, 8)
        searchWrap.add(findBox, BorderLayout.CENTER)
        bar.add(searchWrap, BorderLayout.CENTER)
        headerStatus.font = uiBold
        headerStatus.foreground = gold
        headerStatus.alignmentX = Component.RIGHT_ALIGNMENT
        status.font = Font("Segoe UI", Font.PLAIN, UiScale.px(12))
        status.foreground = caption
        status.alignmentX = Component.RIGHT_ALIGNMENT
        val east = JPanel()
        east.layout = BoxLayout(east, BoxLayout.Y_AXIS)
        east.isOpaque = false
        east.add(headerStatus)
        east.add(status)
        bar.add(east, BorderLayout.EAST)
        return bar
    }

    private fun calls(): JComponent {
        val page = JPanel(BorderLayout())
        page.background = ground
        callDetail.background = ground
        callDetail.add(keypadColumn(), "pad")
        callDetail.add(inCallColumn(), "live")
        callDetail.add(recentDetail(), "detail")
        page.add(recentsColumn().also { recentsPane = it }, BorderLayout.WEST)
        page.add(callDetail, BorderLayout.CENTER)
        return page
    }

    private fun recentsColumn(): JPanel {
        val col = JPanel(BorderLayout())
        col.background = ground
        col.preferredSize = Dimension(UiScale.px(392), 200)
        col.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, hairline),
            EmptyBorder(20, 20, 16, 16),
        )
        val head = stack()
        head.border = EmptyBorder(0, 0, 0, 0)
        grow(head, JLabel("Calls").apply { font = serif; foreground = ink }, 12)
        lineChip.font = uiBold
        lineChip.foreground = emerald
        lineSwitch.isOpaque = false
        lineSwitch.alignmentX = Component.LEFT_ALIGNMENT
        historyFilters.isOpaque = false
        historyFilters.alignmentX = Component.LEFT_ALIGNMENT
        grow(head, lineSwitch, 8)
        activeCallRow.isVisible = false
        grow(head, activeCallRow, 8)
        allRecentButton.addActionListener {
            missedOnly = false
            historyExtension = null
            styleRecentFilters()
            reloadRecents()
        }
        missedRecentButton.addActionListener {
            missedOnly = true
            historyExtension = null
            styleRecentFilters()
            reloadRecents()
        }
        keypadJump.isContentAreaFilled = false
        keypadJump.isBorderPainted = false
        keypadJump.font = uiBold
        keypadJump.foreground = muted
        keypadJump.addActionListener {
            narrowList = false
            applyWindowShape()
        }
        callTabs.layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 0)
        callTabs.isOpaque = false
        callTabs.alignmentX = Component.LEFT_ALIGNMENT
        listOf("recents" to "Recents", "contacts" to "Contacts", "voicemail" to "Voicemail").forEach { (id, title) ->
            val tab = JButton(title)
            tab.putClientProperty("callsView", id)
            tab.isContentAreaFilled = false
            tab.isBorderPainted = false
            tab.isFocusPainted = false
            tab.font = uiBold
            tab.addActionListener { showCallsView(id) }
            callTabs.add(tab)
        }
        grow(head, callTabs, 8)
        grow(head, historyFilters, 8)
        col.add(head, BorderLayout.NORTH)
        recentRows.layout = BoxLayout(recentRows, BoxLayout.Y_AXIS)
        recentRows.background = ground
        val scroll = JScrollPane(recentRows)
        scroll.border = EmptyBorder(0, 0, 0, 0)
        scroll.background = ground
        scroll.viewport.background = ground
        recentEmpty.alignmentX = Component.LEFT_ALIGNMENT
        val recentsBody = JPanel(BorderLayout())
        recentsBody.background = ground
        recentsBody.add(recentEmpty, BorderLayout.NORTH)
        recentsBody.add(scroll, BorderLayout.CENTER)
        contactsList.layout = BoxLayout(contactsList, BoxLayout.Y_AXIS)
        contactsList.background = ground
        val contactsScroll = JScrollPane(contactsList)
        contactsScroll.border = EmptyBorder(0, 0, 0, 0)
        contactsScroll.background = ground
        contactsScroll.viewport.background = ground
        val voicemail = JPanel(BorderLayout())
        voicemail.background = ground
        voicemail.add(label("Voicemail is not on this phone yet."), BorderLayout.NORTH)
        callsBody.background = ground
        callsBody.add(recentsBody, "recents")
        callsBody.add(contactsScroll, "contacts")
        callsBody.add(voicemail, "voicemail")
        col.add(callsBody, BorderLayout.CENTER)
        styleCallTabs()
        reloadRecents()
        return col
    }

    private fun keypadColumn(): JPanel {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.background = ground
        box.border = EmptyBorder(16, 24, 12, 24)
        val recents = ghost("Recents", expand = false) {
            narrowList = true
            applyWindowShape()
        }
        recents.alignmentX = Component.CENTER_ALIGNMENT
        box.add(recents)
        box.add(Box.createVerticalStrut(8))
        dial.font = Font("Consolas", Font.PLAIN, UiScale.px(52))
        dial.preferredSize = Dimension(460, UiScale.px(72))
        dial.maximumSize = Dimension(560, UiScale.px(72))
        dial.horizontalAlignment = JTextField.CENTER
        dial.background = ground
        dial.caretColor = gold
        dial.foreground = ink
        dial.border = EmptyBorder(4, 8, 4, 8)
        dial.alignmentX = Component.CENTER_ALIGNMENT
        dial.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private var updating = false
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            private fun restyleDial() {
                if (updating) return
                val raw = dial.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
                val shown = formatDialDigits(raw)
                val size = UiScale.px(dialTypeSize(raw))
                dial.font = Font("Consolas", Font.PLAIN, size)
                val height = size + UiScale.px(20)
                dial.maximumSize = Dimension(560, height)
                dial.preferredSize = Dimension(460, height)
                if (dial.text != shown) {
                    updating = true
                    dial.text = shown
                    updating = false
                }
            }
        })
        dial.addActionListener { placeCall() }
        fromLine.font = Font("Segoe UI", Font.PLAIN, UiScale.px(13))
        fromLine.foreground = muted
        fromLine.alignmentX = Component.CENTER_ALIGNMENT
        callState.alignmentX = Component.CENTER_ALIGNMENT
        val keys = JPanel(GridLayout(4, 3, 10, 10))
        keys.background = ground
        keys.preferredSize = Dimension(UiScale.px(280), UiScale.px(280))
        keys.minimumSize = Dimension(UiScale.px(240), UiScale.px(240))
        keys.maximumSize = Dimension(UiScale.px(320), UiScale.px(320))
        keys.alignmentX = Component.CENTER_ALIGNMENT
        val letters = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+",
        )
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEach { digit ->
            keys.add(DialKey(digit, letters[digit].orEmpty(), raised, ink, caption, gold) {
                KeyTone.play(digit)
                dial.text += digit
            })
        }
        callButton.alignmentX = Component.CENTER_ALIGNMENT
        callButton.maximumSize = Dimension(260, 52)
        answerButton.alignmentX = Component.CENTER_ALIGNMENT
        declineButton.alignmentX = Component.CENTER_ALIGNMENT
        hangButton.alignmentX = Component.CENTER_ALIGNMENT
        val hint = JLabel("Type or paste a number, then Enter.")
        hint.font = Font("Segoe UI", Font.PLAIN, UiScale.px(12))
        hint.foreground = caption
        hint.alignmentX = Component.CENTER_ALIGNMENT
        box.add(dial)
        box.add(Box.createVerticalStrut(6))
        box.add(fromLine)
        box.add(Box.createVerticalStrut(4))
        box.add(callState)
        box.add(Box.createVerticalStrut(18))
        box.add(keys)
        box.add(Box.createVerticalStrut(16))
        val dialRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 12, 0))
        dialRow.isOpaque = false
        dialRow.alignmentX = Component.CENTER_ALIGNMENT
        dialRow.maximumSize = Dimension(420, 64)
        val erase = ghost("Delete", expand = false) { eraseDialDigit() }
        erase.toolTipText = "Delete the last digit"
        dialRow.add(callButton)
        dialRow.add(erase)
        box.add(dialRow)
        box.add(Box.createVerticalStrut(12))
        box.add(hint)
        return box
    }

    private fun inCallColumn(): JPanel {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.background = ground
        box.border = EmptyBorder(48, 24, 24, 24)
        inCallClock.font = Font("Consolas", Font.PLAIN, UiScale.px(22))
        inCallClock.foreground = gold
        inCallClock.alignmentX = Component.CENTER_ALIGNMENT
        answerButton.alignmentX = Component.CENTER_ALIGNMENT
        declineButton.alignmentX = Component.CENTER_ALIGNMENT
        hangButton.alignmentX = Component.CENTER_ALIGNMENT
        hangButton.preferredSize = Dimension(180, 52)
        hangButton.maximumSize = Dimension(220, 52)
        muteButton.alignmentX = Component.CENTER_ALIGNMENT
        holdButton.alignmentX = Component.CENTER_ALIGNMENT
        transferButton.alignmentX = Component.CENTER_ALIGNMENT
        keypadButton.alignmentX = Component.CENTER_ALIGNMENT
        answerButton.isVisible = false
        declineButton.isVisible = false
        hangButton.isVisible = false
        muteButton.isVisible = false
        holdButton.isVisible = false
        transferButton.isVisible = false
        keypadButton.isVisible = false
        val letters = mapOf(
            "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+",
        )
        dtmfPad.background = ground
        dtmfPad.preferredSize = Dimension(UiScale.px(280), UiScale.px(248))
        dtmfPad.minimumSize = Dimension(UiScale.px(240), UiScale.px(220))
        dtmfPad.maximumSize = Dimension(UiScale.px(320), UiScale.px(280))
        dtmfPad.alignmentX = Component.CENTER_ALIGNMENT
        dtmfPad.isVisible = false
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").forEach { digit ->
            dtmfPad.add(DialKey(digit, letters[digit].orEmpty(), raised, ink, caption, gold) {
                KeyTone.play(digit)
                sendDtmf(digit)
            })
        }
        val controls = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 8, 0))
        controls.background = ground
        controls.alignmentX = Component.CENTER_ALIGNMENT
        controls.maximumSize = Dimension(760, 110)
        hangButton.toolTipText = "End (Ctrl D)"
        controls.add(callControl(muteButton, "M"))
        controls.add(callControl(holdButton, "H"))
        controls.add(callControl(transferButton, "T"))
        controls.add(callControl(keypadButton, "K"))
        controls.add(callControl(hangButton, "Ctrl D"))
        box.add(Box.createVerticalGlue())
        box.add(inCallParty)
        box.add(Box.createVerticalStrut(10))
        box.add(inCallClock)
        box.add(Box.createVerticalStrut(16))
        headsetOffer = ghost("Use headset", expand = false) { useOfferedHeadset() }
        headsetOffer.isVisible = false
        headsetOffer.alignmentX = Component.CENTER_ALIGNMENT
        box.add(headsetOffer)
        box.add(Box.createVerticalStrut(16))
        box.add(answerButton)
        box.add(Box.createVerticalStrut(8))
        box.add(declineButton)
        val callSpeaker = audioCombo(speakerModel) { useSelectedSpeaker() }
        callSpeaker.alignmentX = Component.CENTER_ALIGNMENT
        callSpeaker.maximumSize = Dimension(360, 40)
        box.add(controls)
        box.add(Box.createVerticalStrut(12))
        box.add(callSpeaker)
        box.add(Box.createVerticalStrut(12))
        box.add(dtmfPad)
        box.add(Box.createVerticalGlue())
        return box
    }

    private fun callControl(button: JComponent, shortcut: String): JPanel {
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.isOpaque = false
        button.alignmentX = Component.CENTER_ALIGNMENT
        val caption = JLabel(shortcut)
        caption.font = Font("Segoe UI", Font.PLAIN, UiScale.px(11))
        caption.foreground = this.caption
        caption.alignmentX = Component.CENTER_ALIGNMENT
        box.add(button)
        box.add(Box.createVerticalStrut(4))
        box.add(caption)
        return box
    }

    private fun showCallsView(name: String) {
        callsView = name
        (callsBody.layout as java.awt.CardLayout).show(callsBody, name)
        historyFilters.isVisible = name == "recents"
        styleCallTabs()
        if (name == "contacts") reloadContacts()
    }

    private fun styleCallTabs() {
        callTabs.components.filterIsInstance<JButton>().forEach { button ->
            val on = button.getClientProperty("callsView") == callsView
            button.foreground = if (on) gold else muted
        }
    }

    private fun reloadContacts() {
        contactsList.removeAll()
        val people = ContactBook.load()
        if (people.isEmpty()) {
            contactsList.add(label("No contacts yet. Import a file from Settings."))
        } else {
            people.forEach { contactsList.add(contactRow(it)) }
        }
        contactsList.revalidate()
        contactsList.repaint()
    }

    private fun styleRecentFilters() {
        historyFilters.components.filterIsInstance<JButton>().forEach { button ->
            val token = button.getClientProperty("history") as? String
            val on = when (token) {
                "missed" -> missedOnly
                "all" -> !missedOnly && historyExtension == null
                else -> !missedOnly && historyExtension == token
            }
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.isFocusPainted = false
            button.font = uiBold
            button.foreground = if (on) gold else muted
            button.background = ground
        }
    }

    private fun messages(): JPanel {
        threadList.fixedCellHeight = 64
        threadList.background = ground
        threadList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val row = value as? ThreadInfo
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                label.border = EmptyBorder(8, 12, 8, 12)
                label.font = uiFont
                if (row == null) {
                    label.text = ""
                } else {
                    val preview = row.snippet?.take(80).orEmpty()
                    label.text = "<html><b>${escapeHtml(row.peer.ifBlank { "Unknown" })}</b><br><span style='color:#8491AD'>${escapeHtml(preview)}</span></html>"
                }
                return label
            }
        }
        val listCol = JPanel(BorderLayout())
        listCol.background = ground
        listCol.preferredSize = Dimension(UiScale.px(320), 200)
        listCol.border = BorderFactory.createMatteBorder(0, 0, 0, 1, hairline)
        val head = stack()
        head.border = EmptyBorder(20, 16, 8, 12)
        grow(head, JLabel("Messages").apply { font = serif; foreground = ink }, 8)
        messageLine.font = uiBold
        messageLine.foreground = emerald
        grow(head, messageLine, 8)
        grow(head, ghost("New message", expand = false) {
            threadList.clearSelection()
            smsTo.text = ""
            threadTitle.text = "New message"
            clearPhoto()
            transcript.removeAll()
            transcript.revalidate()
            smsTo.requestFocus()
        }, 0)
        listCol.add(head, BorderLayout.NORTH)
        listCol.add(JScrollPane(threadList).apply {
            border = null
            viewport.background = ground
        }, BorderLayout.CENTER)

        threadTitle.font = Font("Georgia", Font.PLAIN, UiScale.px(28))
        threadTitle.foreground = ink
        val titleRow = JPanel(BorderLayout(8, 0))
        titleRow.background = ground
        titleRow.border = EmptyBorder(16, 20, 8, 16)
        titleRow.add(threadTitle, BorderLayout.CENTER)
        titleRow.add(ghost("Call", expand = false) {
            val number = smsTo.text.trim().ifBlank { threadTitle.text }
            if (number.isBlank() || number == "New message") return@ghost
            dial.text = number
            showTab("calls")
            placeCall()
        }, BorderLayout.EAST)

        smsBody.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "send-text")
        smsBody.actionMap.put("send-text", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = sendText()
        })
        smsBody.transferHandler = photoTransfer()
        val composer = JPanel()
        composer.layout = BoxLayout(composer, BoxLayout.Y_AXIS)
        composer.background = ground
        composer.border = EmptyBorder(8, 16, 16, 16)
        smsTo.maximumSize = Dimension(Int.MAX_VALUE, 40)
        smsTo.alignmentX = Component.LEFT_ALIGNMENT
        val bodyScroll = JScrollPane(smsBody)
        bodyScroll.border = BorderFactory.createLineBorder(hairline)
        bodyScroll.alignmentX = Component.LEFT_ALIGNMENT
        bodyScroll.maximumSize = Dimension(Int.MAX_VALUE, 96)
        val send = WelcomeButton("Send", gold, goldInk, expand = false).apply {
            addActionListener { sendText() }
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val photo = ghost("Photo", expand = false) { choosePhoto() }
        val removePhoto = ghost("Remove", expand = false) { clearPhoto() }
        attachNote.alignmentX = Component.LEFT_ALIGNMENT
        val attachRow = JPanel()
        attachRow.background = ground
        attachRow.alignmentX = Component.LEFT_ALIGNMENT
        attachRow.add(photo)
        attachRow.add(attachNote)
        attachRow.add(removePhoto)
        composer.transferHandler = photoTransfer()
        composer.add(label("To"))
        composer.add(Box.createVerticalStrut(4))
        composer.add(smsTo)
        composer.add(Box.createVerticalStrut(8))
        composer.add(bodyScroll)
        composer.add(Box.createVerticalStrut(8))
        composer.add(attachRow)
        composer.add(Box.createVerticalStrut(8))
        composer.add(send)

        val detail = JPanel(BorderLayout())
        detail.background = ground
        detail.add(titleRow, BorderLayout.NORTH)
        detail.add(JScrollPane(transcript).apply {
            border = null
            viewport.background = ground
        }, BorderLayout.CENTER)
        detail.add(composer, BorderLayout.SOUTH)

        val page = JPanel(BorderLayout())
        page.background = ground
        page.add(listCol, BorderLayout.WEST)
        page.add(detail, BorderLayout.CENTER)
        return page
    }

    private fun thread(): JPanel {
        val page = JPanel(BorderLayout(0, 8))
        page.background = surface
        page.border = EmptyBorder(12, 12, 12, 12)
        val head = column(surface)
        head.add(pin(ghostButton("Messages") {
            threadList.clearSelection()
            showTab("messages")
        }))
        head.add(pin(threadTitle.apply { font = uiBold; foreground = gold }))
        page.add(head, BorderLayout.NORTH)
        page.add(JScrollPane(threadBody).apply { border = BorderFactory.createLineBorder(hairline) }, BorderLayout.CENTER)
        return page
    }

    private fun lines(): JPanel {
        val page = column(ground)
        page.border = EmptyBorder(28, 28, 16, 28)
        page.add(pin(JLabel("Lines").apply { font = serif; foreground = ink }))
        page.add(Box.createVerticalStrut(16))
        lineList.layout = BoxLayout(lineList, BoxLayout.Y_AXIS)
        lineList.background = ground
        lineList.alignmentX = Component.LEFT_ALIGNMENT
        page.add(lineList)
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Add a line", expand = false) {
            if (session.lines().size >= 2) {
                note("This phone already has two lines.")
                return@ghost
            }
            showRoot("welcome")
        }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(wrappingCopy("Add a second extension with the setup link from email. Each line can place calls, send messages, and keep its own call history. One extension can also ring on the phone.")))
        page.putClientProperty("lines", lineList)
        return page
    }

    private fun settings(): JPanel {
        val page = column(ground)
        page.border = EmptyBorder(28, 28, 16, 28)
        page.add(pin(JLabel("Settings").apply { font = serif; foreground = ink }))
        page.add(Box.createVerticalStrut(16))
        page.add(pin(label(profile.productName)))
        page.add(pin(label("Build $APP_BUILD")))
        page.add(pin(label(profile.brandSub)))
        page.add(Box.createVerticalStrut(12))
        val pinBox = javax.swing.JCheckBox("Pin this window on top")
        styleCheck(pinBox)
        pinBox.isSelected = WindowPrefs.pinned()
        pinBox.addActionListener {
            WindowPrefs.savePin(pinBox.isSelected)
            isAlwaysOnTop = pinBox.isSelected
            surfaces.setToggles(pinBox.isSelected, WindowPrefs.dnd())
        }
        page.add(pin(pinBox))
        val dndBox = javax.swing.JCheckBox("Do not disturb")
        styleCheck(dndBox)
        dndBox.isSelected = WindowPrefs.dnd()
        dndBox.addActionListener {
            WindowPrefs.saveDnd(dndBox.isSelected)
            if (dndBox.isSelected) CallRinger.stop()
            surfaces.setToggles(WindowPrefs.pinned(), dndBox.isSelected)
            work("Do not disturb") { session.setPbxDnd(dndBox.isSelected) }
        }
        page.add(pin(dndBox))
        page.add(pin(wrappingCopy("Do not disturb keeps the incoming window quiet here, and sets the same state on the PBX.")))
        this.pinBox = pinBox
        this.dndBox = dndBox
        page.add(Box.createVerticalStrut(12))
        page.add(pin(wrappingCopy("Voice uses SIP TLS on 5061, then TCP on 5060. UDP signalling is not used.")))
        page.add(Box.createVerticalStrut(12))
        page.add(pin(label("Microphone")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(audioCombo(micModel) {
            val picked = micModel.selectedItem as? SipBridge.AudioDevice ?: return@audioCombo
            AudioPrefs.saveCapture(picked.name)
            work("Microphone") { if (SipBridge.setCapture(picked.index) != 0) note("Microphone was not changed") }
        }))
        page.add(Box.createVerticalStrut(10))
        page.add(pin(label("Microphone level")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(micMeter))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(wrappingCopy("Speak, and the gold bar moves. It stays quiet when this PC hears nothing.")))
        page.add(Box.createVerticalStrut(10))
        page.add(pin(label("Speaker")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(audioCombo(speakerModel) { useSelectedSpeaker() }))
        page.add(Box.createVerticalStrut(10))
        page.add(pin(label("Ringer")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(ringerCombo()))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(wrappingCopy("The ringtone plays on this speaker, including a USB headset.")))
        page.add(Box.createVerticalStrut(6))
        val alsoRing = javax.swing.JCheckBox("Also ring on the headset")
        alsoRing.background = ground
        alsoRing.foreground = ink
        alsoRing.font = uiFont
        alsoRing.isOpaque = true
        alsoRing.isSelected = AudioPrefs.alsoRing()
        alsoRing.alignmentX = Component.LEFT_ALIGNMENT
        alsoRing.addActionListener { AudioPrefs.saveAlsoRing(alsoRing.isSelected) }
        page.add(pin(alsoRing))
        page.add(Box.createVerticalStrut(10))
        page.add(pin(label("Ringtone")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(ringtoneCombo()))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Choose your own", expand = false) { chooseRingtone() }))
        page.add(Box.createVerticalStrut(8))
        removeRingtone = ghost("Remove your file", expand = false) {
            AudioPrefs.clearRingtone()
            reloadRingtoneList()
            note("Using Two tone")
        }
        page.add(pin(removeRingtone))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(wrappingCopy("Pick a tone, or add your own WAV, AIFF, or AU file. It loops until the call is answered.")))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(audioNote))
        page.add(Box.createVerticalStrut(12))
        page.add(pin(WelcomeButton("Call echo test", gold, goldInk, expand = false).apply {
            addActionListener {
                dial.text = "*43"
                showTab("calls")
                placeCall()
            }
        }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Play ringtone", expand = false) {
            if (SipBridge.snapshot().incoming) return@ghost
            CallRinger.start()
            Timer(2400) { CallRinger.stop() }.apply { isRepeats = false; start() }
        }))
        page.add(Box.createVerticalStrut(16))
        page.add(pin(label("Contacts")))
        page.add(Box.createVerticalStrut(4))
        page.add(pin(wrappingCopy("Import a CSV or vCard, or sign in to a Google or Microsoft 365 mailbox in the browser. The mail app on this PC is not used.")))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Import contacts", expand = false) { importContacts() }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Connect Microsoft 365", expand = false) { connectMailbox(MailboxAuth.Provider.MICROSOFT) }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Connect Google", expand = false) { connectMailbox(MailboxAuth.Provider.GOOGLE) }))
        page.add(Box.createVerticalStrut(16))
        page.add(pin(ghost("Keyboard shortcuts", expand = false) { showShortcuts() }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(wrappingCopy("Press ? for the shortcut list. During a call, M mutes, H holds, T transfers, and K opens the keypad.")))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(label("Licence GPL-2.0.")))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Privacy", expand = false) {
            browse("https://ithandsfree.com/privacy#ihf-phone")
        }))
        page.add(Box.createVerticalStrut(8))
        page.add(pin(ghost("Licences", expand = false) {
            browse("https://github.com/ithandsfree/softphone")
        }))
        page.add(Box.createVerticalStrut(8))
        val tones = javax.swing.JCheckBox("Keypad sound")
        styleCheck(tones)
        tones.isSelected = AudioPrefs.keypadTone()
        tones.addActionListener { AudioPrefs.saveKeypadTone(tones.isSelected) }
        page.add(pin(tones))
        return page
    }

    private fun styleCheck(box: javax.swing.JCheckBox) {
        box.background = ground
        box.foreground = ink
        box.font = uiFont
        box.isOpaque = true
        box.alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun nav(): JPanel {
        val bar = JPanel()
        bar.layout = BoxLayout(bar, BoxLayout.Y_AXIS)
        bar.background = ground
        bar.border = EmptyBorder(8, 6, 12, 6)
        bar.preferredSize = Dimension(UiScale.px(84), 200)
        listOf("Calls" to "calls", "Messages" to "messages", "Lines" to "lines", "Settings" to "settings")
            .forEach { (title, id) ->
                val button = RailButton(title, id, gold, muted, raised, ground, ink)
                button.alignmentX = Component.CENTER_ALIGNMENT
                button.maximumSize = Dimension(UiScale.px(76), UiScale.px(68))
                button.addActionListener {
                    if (id == "messages") refreshThreads()
                    if (id == "lines") refreshLine()
                    if (id == "settings") loadAudioDevices()
                    showTab(id)
                }
                tabs[id] = button
                bar.add(button)
                bar.add(Box.createVerticalStrut(4))
            }
        return bar
    }

    private fun refreshCall() {
        val snap = SipBridge.snapshot()
        val transport = snap.transport?.name ?: ""
        voice.text = when {
            SipBridge.loadError != null -> "Voice library not built yet"
            session.line == null -> "No line yet"
            !snap.started -> "Voice idle"
            snap.registered || snap.secondRegistered -> {
                val ready = session.lines().mapIndexedNotNull { index, enrolled ->
                    val up = if (index == 0) snap.registered else snap.secondRegistered
                    if (up) enrolled.extension else null
                }
                "Registered ${ready.joinToString(" and ").ifBlank { session.line?.extension.orEmpty() }} via $transport"
            }
            snap.regCode > 0 -> "Register ${snap.regCode} ${snap.regReason}"
            askedRegister -> "Registering via $transport"
            else -> "Voice idle"
        }
        voice.foreground = if (snap.registered || snap.secondRegistered) emerald else muted
        val ext = session.line?.extension.orEmpty()
        val lineIndex = session.lines().indexOfFirst { it.extension == ext }
        val lineReady = if (lineIndex <= 0) snap.registered else snap.secondRegistered
        lineChip.text = if (ext.isBlank()) "No line" else ext
        lineChip.foreground = if (lineReady) emerald else muted
        messageLine.text = lineChip.text
        messageLine.foreground = lineChip.foreground
        fromLine.text = if (ext.isBlank()) "" else "Calling from $ext"
        if (!snap.callActive && !snap.incoming) {
            callButton.text = if (ext.isBlank()) "Call" else "Call from $ext"
        }
        paintLineControls(snap)
        val connected = snap.callActive && snap.callState.equals("CONFIRMED", ignoreCase = true)
        var finishedSeconds = 0
        if (connected) {
            if (callStartedAt == 0L) callStartedAt = System.currentTimeMillis()
            val shown = formatLiveCallTimer(((System.currentTimeMillis() - callStartedAt) / 1000L).toInt())
            liveTimer.text = shown
            callState.text = shown
            callState.font = mono
            callState.foreground = gold
        } else {
            if (callStartedAt != 0L) {
                finishedSeconds = ((System.currentTimeMillis() - callStartedAt) / 1000L).toInt()
            }
            callStartedAt = 0L
            liveTimer.text = ""
            callState.font = uiFont
            callState.foreground = if (snap.incoming) coral else ink
            callState.text = when {
                snap.incoming -> "Incoming"
                snap.callState.equals("DISCONNECTED", ignoreCase = true) -> "Call ended"
                snap.callActive -> "Calling"
                else -> ""
            }
        }
        val party = displayParty(snap.remote, dial.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' })
        val ringing = snap.incoming
        val live = snap.callActive && !ringing
        val onCall = ringing || live
        inCallParty.text = party.ifBlank { "Call" }
        inCallClock.text = when {
            connected && snap.held -> "On hold  ${liveTimer.text}"
            connected -> liveTimer.text
            ringing -> "Incoming"
            live -> "Calling"
            else -> ""
        }
        activeCallRow.isVisible = onCall
        activeCallRow.text = if (onCall) "On call  ${inCallParty.text}  ${inCallClock.text}" else "Return to call"
        headerStatus.text = when {
            connected -> "On call  ${liveTimer.text}"
            ringing -> "Incoming"
            live -> "Calling"
            WindowPrefs.dnd() -> "Do not disturb"
            session.lines().isNotEmpty() -> session.lines().joinToString("   ") { it.extension }
            else -> "No line"
        }
        headerStatus.foreground = if (onCall || WindowPrefs.dnd()) gold else emerald
        title = when {
            ringing -> "${profile.productName} — Incoming"
            connected -> "${profile.productName} — On call ${liveTimer.text}"
            live -> "${profile.productName} — Calling"
            WindowPrefs.dnd() -> "${profile.productName} — Do not disturb"
            else -> profile.productName
        }
        answerButton.isVisible = ringing
        declineButton.isVisible = ringing
        hangButton.isVisible = live
        muteButton.isVisible = live
        muteButton.text = if (snap.muted) "Unmute" else "Mute"
        holdButton.isVisible = live
        holdButton.text = if (snap.held) "Resume" else "Hold"
        transferButton.isVisible = live
        keypadButton.isVisible = live
        if (!live) showDtmf = false
        dtmfPad.isVisible = live && showDtmf
        callButton.isVisible = !ringing && !live
        if (onCall && party.isNotBlank()) lastParty = party
        if ((snap.callActive || snap.incoming) && snap.callExtension.isNotBlank()) lastCallExt = snap.callExtension
        if (lastCallExt.isBlank() && (snap.callActive || snap.incoming)) lastCallExt = ext
        if (ringing) lastIncoming = true
        if (connected) lastConnected = true
        val mode = when {
            ringing -> "in"
            live -> "live"
            else -> "idle"
        }
        if ((callMode == "in" || callMode == "live") && mode == "idle") {
            val kind = when {
                lastIncoming && !lastConnected -> "Missed"
                lastIncoming -> "Incoming"
                else -> "Outgoing"
            }
            CallLog.append(CallEntry(System.currentTimeMillis(), kind, lastParty, finishedSeconds, lastCallExt))
            lastParty = ""
            lastCallExt = ""
            lastIncoming = false
            lastConnected = false
            reloadRecents()
        }
        if (mode != callMode) {
            callMode = mode
            if (mode == "idle") browsingDuringCall = false
            if (onCall) {
                narrowList = false
                applyWindowShape()
            }
            if (callDetail.parent != null) {
                callDetailCards.show(
                    callDetail,
                    when {
                        onCall && !browsingDuringCall -> "live"
                        detailOpen -> "detail"
                        else -> "pad"
                    },
                )
            }
            callButton.parent?.revalidate()
        }
        val callsInFront = currentTab == "calls" && isVisible && (extendedState and java.awt.Frame.ICONIFIED) == 0
        if (callsInFront) markMissedSeen()
        if (pollTick == 0 || pollTick % 200 == 0) taskbarLight = DesktopIcons.taskbarIsLight()
        val attention = missedSinceSeen() + knownUnread.coerceAtLeast(0)
        val registered = snap.registered && session.line != null
        surfaces.applyStatus(
            flavor = DesktopIcons.flavor(profile),
            lightTaskbar = taskbarLight,
            registered = registered,
            onCall = live || ringing,
            attention = attention,
            extension = ext,
            detail = statusDetail(ext, registered, live || ringing),
        )
        applyTaskbarBadge(registered, live || ringing, attention)
        if (ringing && !flashedIncoming) {
            flashedIncoming = true
            runCatching {
                val taskbar = java.awt.Taskbar.getTaskbar()
                if (taskbar.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                    taskbar.requestWindowUserAttention(this)
                }
            }
        }
        if (!ringing) flashedIncoming = false
        val mainInFront = isVisible && (extendedState and java.awt.Frame.ICONIFIED) == 0
        watchHeadsets(live)
        refreshLine()
        surfaces.sync(
            incoming = ringing,
            onCall = live,
            party = party.ifBlank { inCallParty.text },
            clock = inCallClock.text,
            extension = ext,
            mainInFront = mainInFront,
            muted = snap.muted,
        )
        if (currentTab == "settings") {
            val pin = pinBox
            val quiet = dndBox
            if (pin != null && !pin.isFocusOwner && pin.isSelected != WindowPrefs.pinned()) pin.isSelected = WindowPrefs.pinned()
            if (quiet != null && !quiet.isFocusOwner && quiet.isSelected != WindowPrefs.dnd()) quiet.isSelected = WindowPrefs.dnd()
        }
        if (live && speakerModel.size == 0) loadAudioDevices()
        val consulting = snap.consultActive
        speakButton?.isVisible = !consulting
        finishConsultButton?.isVisible = consulting
        cancelConsultButton?.isVisible = consulting
        if (registered && !dndFetched && pollTick == 8) {
            dndFetched = true
            Thread {
                val on = runCatching { session.pbxDnd() }.getOrNull() ?: return@Thread
                SwingUtilities.invokeLater {
                    WindowPrefs.saveDnd(on)
                    dndBox?.isSelected = on
                    if (::surfaces.isInitialized) surfaces.setToggles(WindowPrefs.pinned(), on)
                    if (on) CallRinger.stop()
                }
            }.apply { isDaemon = true; name = "ihf-dnd" }.start()
        }
        pollTick += 1
        if (pollTick % 15 == 0) refreshThreads(notify = true)
    }

    private fun paintMicLevel() {
        if (currentTab != "settings") return
        val heard = SipBridge.micLevel()
        val now = System.currentTimeMillis()
        if (heard >= micPeak || now - micPeakAt > 160) {
            micPeak = heard
            micPeakAt = now
        }
        if (micPeak != micMeter.level) {
            micMeter.level = micPeak
            micMeter.repaint()
        }
    }

    private fun requestLink(email: String) = work("Setup link") {
        session.requestEnrol(email)
        pendingEmail = email
        SwingUtilities.invokeLater {
            sentBody.text = "<html><body style='width:280px'>If $email has ${profile.productName} lines, a setup link is on its way. Open it on <b>this PC</b> and paste the link.</body></html>"
            showRoot("sent")
            startResend()
        }
    }

    private fun startResend() {
        resendLeft = 30
        resendButton.isEnabled = false
        resendButton.text = "Resend in 0:30"
        resendTicker?.stop()
        resendTicker = Timer(1000) {
            resendLeft -= 1
            if (resendLeft <= 0) {
                resendButton.isEnabled = true
                resendButton.text = "Resend"
                resendTicker?.stop()
            } else {
                resendButton.text = "Resend in 0:%02d".format(resendLeft)
            }
            resendButton.repaint()
        }.apply { isRepeats = true; start() }
    }

    private fun openMail() {
        val apps = installedMailApps()
        if (apps.isEmpty()) {
            note("No email app was found. Open your inbox and look for the setup message.")
            return
        }
        if (apps.size == 1) {
            launchMail(apps.first())
            return
        }
        val dialog = javax.swing.JDialog(this, "Choose email app", true)
        val panel = javax.swing.JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = ground
        panel.border = EmptyBorder(18, 18, 18, 18)
        panel.add(JLabel("Choose email app").apply {
            font = uiBold
            foreground = ink
            alignmentX = Component.LEFT_ALIGNMENT
        })
        panel.add(Box.createVerticalStrut(6))
        panel.add(JLabel("Opens your inbox.").apply {
            font = uiFont
            foreground = muted
            alignmentX = Component.LEFT_ALIGNMENT
        })
        panel.add(Box.createVerticalStrut(14))
        for (app in apps) {
            val row = MailChoiceButton(app.label, mailIcon(app), raised, ink, hairline)
            row.addActionListener {
                dialog.dispose()
                launchMail(app)
            }
            row.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(row)
            panel.add(Box.createVerticalStrut(8))
        }
        panel.add(ghost("Cancel") { dialog.dispose() }.apply { alignmentX = Component.LEFT_ALIGNMENT })
        dialog.contentPane = panel
        dialog.isResizable = false
        dialog.pack()
        dialog.setSize(UiScale.px(420), dialog.height)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun launchMail(app: MailAppTarget) {
        val started = runCatching { ProcessBuilder(app.command).start() }.isSuccess
        if (!started) note("Could not open ${app.label}")
    }

    fun acceptExternalLink(raw: String) {
        val number = dialTarget(raw)
        if (number == null) {
            acceptSetupLink(raw)
            return
        }
        reveal()
        showRoot("app")
        showTab("calls")
        dial.text = number
        val snap = SipBridge.snapshot()
        if (snap.callActive || snap.incoming) {
            note("On a call. This number is ready when it ends.")
            return
        }
        placeCall()
    }

    fun acceptSetupLink(raw: String) {
        reveal()
        enrolFrom(raw.trim().trim('"'))
    }

    private fun enrolFrom(raw: String) = work("Enrol") {
        val enrolled = session.enrol(raw)
        LineStore.save(enrolled)
        note("Enrolled extension ${enrolled.extension}")
        askedRegister = true
        session.register(userAgent = "${profile.userAgentName}/0.1.0 PJSUA")
        note("Registration sent for ${enrolled.extension}")
        SwingUtilities.invokeLater {
            showRoot("app")
            refreshLine()
            showTab("calls")
        }
    }

    private fun placeCall() = work("Call") {
        session.placeCall(dial.text)
        note("Calling")
    }

    private fun sendText() {
        val peer = smsTo.text
        val body = smsBody.text
        val photo = pendingPhoto
        work("Text") {
            if (photo == null && body.isBlank()) throw IllegalArgumentException("Enter a message or add a photo.")
            if (photo != null) session.sendPhoto(peer, photo)
            if (body.isNotBlank()) session.sendText(peer, body)
            note("Sent")
            val rows = if (peer.isNotBlank()) session.conversation(peer) else emptyList()
            SwingUtilities.invokeLater {
                smsBody.text = ""
                clearPhoto()
                refreshThreads()
                if (peer.isNotBlank()) showTranscript(rows)
            }
        }
    }

    private fun choosePhoto() {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "Photo"
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        stagePhoto(chooser.selectedFile.readBytes(), chooser.selectedFile.name)
    }

    private fun stagePhoto(raw: ByteArray, name: String) {
        work("Photo") {
            val photo = prepareMmsPhoto(raw, name)
            SwingUtilities.invokeLater {
                pendingPhoto = photo
                attachNote.text = "  Photo · ${photo.bytes.size / 1024} KB  "
                attachNote.isVisible = true
            }
        }
    }

    private fun clearPhoto() {
        pendingPhoto = null
        attachNote.text = ""
        attachNote.isVisible = false
    }

    private fun photoTransfer(): javax.swing.TransferHandler {
        return object : javax.swing.TransferHandler() {
            override fun getSourceActions(component: JComponent) = COPY

            override fun createTransferable(component: JComponent): java.awt.datatransfer.Transferable? {
                val selected = (component as? JTextArea)?.selectedText ?: return null
                return java.awt.datatransfer.StringSelection(selected)
            }

            override fun exportDone(component: JComponent, data: java.awt.datatransfer.Transferable?, action: Int) {
                if (action == MOVE && component is JTextArea) component.replaceSelection("")
            }

            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor) ||
                    support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor) ||
                    support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                val transferable = support.transferable
                if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                    val image = transferable.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image
                    if (image != null) {
                        stagePhoto(pngBytes(image), "photo.png")
                        return true
                    }
                }
                if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                    val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>
                    val file = files?.filterIsInstance<java.io.File>()?.firstOrNull()
                    if (file != null) {
                        stagePhoto(file.readBytes(), file.name)
                        return true
                    }
                }
                if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (text != null && support.component is JTextArea) {
                    (support.component as JTextArea).replaceSelection(text)
                    return true
                }
                }
                return false
            }
        }
    }

    private fun pngBytes(image: java.awt.Image): ByteArray {
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val canvas = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        canvas.createGraphics().apply {
            drawImage(image, 0, 0, java.awt.Color.WHITE, null)
            dispose()
        }
        val output = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(canvas, "png", output)
        return output.toByteArray()
    }

    private fun reloadRecents() {
        recentEntries = CallLog.load()
        if (recentIndex >= recentEntries.size) recentIndex = recentEntries.lastIndex
        val query = findBox.text.trim()
        val shown = recentEntries.mapIndexed { index, entry -> index to entry }
            .filter { (_, entry) ->
                val missedOk = !missedOnly || entry.kind == "Missed"
                val lineOk = historyExtension == null || entry.extension == historyExtension ||
                    (entry.extension.isBlank() && session.lines().size < 2)
                val queryOk = query.isBlank() ||
                    entry.party.contains(query, ignoreCase = true) ||
                    formatDialDigits(entry.party).contains(query, ignoreCase = true)
                missedOk && lineOk && queryOk
            }
        recentRows.removeAll()
        if (query.isNotBlank()) {
            ContactBook.match(query).forEach { person -> recentRows.add(contactRow(person)) }
        }
        shown.forEach { (index, entry) -> recentRows.add(recentRow(entry, index)) }
        recentEmpty.text = when {
            query.isNotBlank() && shown.isEmpty() && ContactBook.match(query).isEmpty() -> "Nothing matches."
            missedOnly && recentEntries.isNotEmpty() && shown.isEmpty() -> "No missed calls."
            historyExtension != null && shown.isEmpty() -> "No calls on $historyExtension."
            else -> "No recent calls yet."
        }
        recentEmpty.isVisible = shown.isEmpty() && (query.isBlank() || ContactBook.match(query).isEmpty())
        recentRows.revalidate()
        recentRows.repaint()
        if (detailOpen) selectedRecent()?.let { fillRecentDetail(it) }
    }

    private fun recentRow(entry: CallEntry, index: Int): JPanel {
        val selected = index == recentIndex
        val row = JPanel(BorderLayout(8, 0))
        row.background = if (selected) raised else ground
        row.border = EmptyBorder(8, 4, 8, 4)
        row.maximumSize = Dimension(Int.MAX_VALUE, 64)
        val missed = entry.kind == "Missed"
        val title = JLabel(formatDialDigits(entry.party))
        title.font = uiBold
        title.foreground = if (missed) coral else ink
        val onLine = entry.extension.ifBlank { "" }
        val second = listOfNotNull(
            onLine.ifBlank { null },
            entry.kind,
            if (entry.seconds > 0) formatLiveCallTimer(entry.seconds) else null,
            formatRecentWhen(entry.at),
        ).joinToString(" · ")
        val meta = JLabel(second)
        meta.font = Font("Segoe UI", Font.PLAIN, UiScale.px(12))
        meta.foreground = caption
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(title)
        text.add(meta)
        val ext = entry.extension.ifBlank { session.line?.extension?.ifBlank { null } ?: "this line" }
        val callTip = if (recentCanCall(entry.party)) "Call back from $ext" else "Caller ID withheld"
        val textBlock = recentTextBlock(entry.party)
        val actions = JPanel()
        actions.isOpaque = false
        actions.add(recentButton("Call", true, recentCanCall(entry.party), callTip) { callRecent(entry) })
        actions.add(recentButton("Message", false, textBlock == null, textBlock ?: "Message from $ext") { messageRecent(entry) })
        actions.add(recentButton("···", false, true, "More actions") { button ->
            recentMenu(entry).show(button, 0, button.height)
        })
        row.add(text, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)
        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(event: java.awt.event.MouseEvent) {
                if (event.isPopupTrigger) showPopup(event)
            }
            override fun mouseReleased(event: java.awt.event.MouseEvent) {
                if (event.isPopupTrigger) showPopup(event)
            }
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(event)) return
                recentIndex = index
                openRecentDetail(entry)
            }
            private fun showPopup(event: java.awt.event.MouseEvent) {
                recentIndex = index
                recentMenu(entry).show(row, event.x, event.y)
                reloadRecents()
            }
        })
        return row
    }

    private fun recentButton(
        title: String,
        primary: Boolean,
        enabled: Boolean,
        tip: String,
        action: (JButton) -> Unit,
    ): JButton {
        val button = JButton(title)
        button.font = Font("Segoe UI", Font.BOLD, 11)
        button.isFocusPainted = false
        button.isEnabled = enabled
        button.toolTipText = tip
        button.foreground = if (primary && enabled) goldInk else ink
        button.background = if (primary && enabled) gold else raised
        button.preferredSize = Dimension(if (title == "···") 36 else 72, 34)
        button.addActionListener { action(button) }
        return button
    }

    private fun recentMenu(entry: CallEntry): javax.swing.JPopupMenu {
        val menu = javax.swing.JPopupMenu()
        val ext = entry.extension.ifBlank { session.line?.extension?.ifBlank { null } ?: "this line" }
        val call = menu.add("Call back from $ext")
        call.isEnabled = recentCanCall(entry.party)
        call.addActionListener { callRecent(entry) }
        val textBlock = recentTextBlock(entry.party)
        val message = menu.add(if (textBlock == null) "Message from $ext" else "Message")
        message.isEnabled = textBlock == null
        message.addActionListener { messageRecent(entry) }
        if (textBlock != null) menu.add(textBlock).isEnabled = false
        menu.addSeparator()
        menu.add("Copy number").addActionListener { copyRecent(entry) }
        if (recentCanCall(entry.party)) menu.add("Add to contacts").addActionListener { saveContact(entry.party) }
        menu.add("Remove from recents").addActionListener { removeRecent(entry) }
        return menu
    }

    private fun contactRow(person: ContactBook.Person): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.background = ground
        row.border = EmptyBorder(8, 4, 8, 4)
        row.maximumSize = Dimension(Int.MAX_VALUE, 64)
        val title = JLabel(person.name)
        title.font = uiBold
        title.foreground = ink
        val meta = JLabel(formatDialDigits(person.number))
        meta.font = Font("Segoe UI", Font.PLAIN, UiScale.px(12))
        meta.foreground = caption
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false
        text.add(title)
        text.add(meta)
        val actions = JPanel()
        actions.isOpaque = false
        actions.add(recentButton("Call", true, true, "Call ${person.name}") {
            dial.text = person.number
            narrowList = false
            applyWindowShape()
            placeCall()
        })
        val block = recentTextBlock(person.number)
        actions.add(recentButton("Message", false, block == null, block ?: "Message ${person.name}") {
            startNewMessage()
            smsTo.text = person.number
        })
        row.add(text, BorderLayout.CENTER)
        row.add(actions, BorderLayout.EAST)
        return row
    }

    private fun searchOrDial() {
        val text = findBox.text.trim()
        val digits = text.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (digits.length < 2) return
        dial.text = digits
        narrowList = false
        applyWindowShape()
        placeCall()
    }

    private fun saveContact(number: String) {
        val dialog = JDialog(this, "Add to contacts")
        dialog.isModal = false
        val name = PromptField("Name")
        val save = WelcomeButton("Save", gold, goldInk, expand = false)
        save.addActionListener {
            if (name.text.isBlank()) return@addActionListener
            ContactBook.add(name.text, number)
            dialog.isVisible = false
            note("Saved ${name.text.trim()}")
            reloadRecents()
        }
        val panel = column(ground)
        panel.border = EmptyBorder(16, 16, 16, 16)
        panel.add(pin(JLabel("Save this number").apply { font = serif; foreground = ink }))
        panel.add(Box.createVerticalStrut(8))
        panel.add(pin(name))
        panel.add(Box.createVerticalStrut(12))
        panel.add(pin(save))
        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
        name.requestFocus()
    }

    private fun selectedRecent(): CallEntry? = recentEntries.getOrNull(recentIndex)

    private fun moveRecent(delta: Int) {
        if (recentEntries.isEmpty()) return
        var index = recentIndex
        if (index < 0) index = if (delta > 0) -1 else recentEntries.size
        repeat(recentEntries.size) {
            index += delta
            if (index !in recentEntries.indices) return
            if (!missedOnly || recentEntries[index].kind == "Missed") {
                recentIndex = index
                reloadRecents()
                return
            }
        }
    }

    private fun callRecent(entry: CallEntry) {
        if (!recentCanCall(entry.party)) {
            note("Caller ID withheld")
            return
        }
        if (entry.extension.isNotBlank()) session.choose(entry.extension)
        dial.text = entry.party
        showTab("calls")
        placeCall()
    }

    private fun messageRecent(entry: CallEntry) {
        val block = recentTextBlock(entry.party)
        if (block != null) {
            note(block)
            return
        }
        if (entry.extension.isNotBlank()) session.choose(entry.extension)
        startNewMessage()
        smsTo.text = entry.party
    }

    private fun copyRecent(entry: CallEntry) {
        val shown = formatDialDigits(entry.party)
        toolkit.systemClipboard.setContents(java.awt.datatransfer.StringSelection(shown), null)
        note("Copied $shown")
    }

    private fun removeRecent(entry: CallEntry) {
        undoRecent = entry
        CallLog.remove(entry)
        if (detailOpen && selectedRecent() == entry) closeRecentDetail()
        reloadRecents()
        note("Removed from recents")
    }

    private fun openRecentDetail(entry: CallEntry) {
        if (SipBridge.snapshot().let { it.callActive || it.incoming }) browsingDuringCall = true
        detailOpen = true
        fillRecentDetail(entry)
        callDetailCards.show(callDetail, "detail")
        reloadRecents()
    }

    private fun closeRecentDetail() {
        detailOpen = false
        browsingDuringCall = false
        val onCall = SipBridge.snapshot().let { it.callActive || it.incoming }
        callDetailCards.show(callDetail, if (onCall) "live" else "pad")
    }

    private fun fillRecentDetail(entry: CallEntry) {
        val ext = entry.extension.ifBlank { session.line?.extension?.ifBlank { null } ?: "this line" }
        detailTitle.text = formatDialDigits(entry.party)
        val whenText = formatRecentWhen(entry.at)
        val length = if (entry.seconds > 0) " · ${formatLiveCallTimer(entry.seconds)}" else ""
        detailMeta.text = "${entry.kind} on $ext · $whenText$length"
        detailMeta.foreground = if (entry.kind == "Missed") coral else muted
        detailHistory.removeAll()
        CallLog.load().filter { it.party == entry.party }.forEach { past ->
            val length = if (past.seconds > 0) " · ${formatLiveCallTimer(past.seconds)}" else ""
            val line = JLabel("${past.kind} · ${formatRecentWhen(past.at)}$length")
            line.font = uiFont
            line.foreground = ink
            line.alignmentX = Component.LEFT_ALIGNMENT
            detailHistory.add(line)
            detailHistory.add(Box.createVerticalStrut(6))
        }
        detailHistory.revalidate()
    }

    private fun recentDetail(): JPanel {
        val page = JPanel()
        page.layout = BoxLayout(page, BoxLayout.Y_AXIS)
        page.background = ground
        page.border = EmptyBorder(28, 32, 24, 32)
        detailTitle.font = Font("Georgia", Font.PLAIN, UiScale.px(32))
        detailTitle.foreground = ink
        detailTitle.alignmentX = Component.LEFT_ALIGNMENT
        detailMeta.font = uiFont
        detailMeta.alignmentX = Component.LEFT_ALIGNMENT
        detailHistory.layout = BoxLayout(detailHistory, BoxLayout.Y_AXIS)
        detailHistory.background = ground
        detailHistory.alignmentX = Component.LEFT_ALIGNMENT
        val keypad = ghost("Keypad", expand = false) { closeRecentDetail() }
        val call = WelcomeButton("Call back", gold, goldInk, expand = false)
        call.addActionListener { selectedRecent()?.let { callRecent(it) } }
        val message = ghost("Message", expand = false) { selectedRecent()?.let { messageRecent(it) } }
        val copy = ghost("Copy number", expand = false) { selectedRecent()?.let { copyRecent(it) } }
        val save = ghost("Add to contacts", expand = false) { selectedRecent()?.let { saveContact(it.party) } }
        val actions = JPanel()
        actions.background = ground
        actions.alignmentX = Component.LEFT_ALIGNMENT
        actions.add(call)
        actions.add(message)
        actions.add(copy)
        actions.add(save)
        page.add(keypad)
        page.add(Box.createVerticalStrut(16))
        page.add(detailTitle)
        page.add(Box.createVerticalStrut(6))
        page.add(detailMeta)
        page.add(Box.createVerticalStrut(16))
        page.add(actions)
        page.add(Box.createVerticalStrut(18))
        page.add(JLabel("History").apply { font = uiBold; foreground = gold; alignmentX = Component.LEFT_ALIGNMENT })
        page.add(Box.createVerticalStrut(8))
        page.add(detailHistory)
        return page
    }

    private fun refreshThreads(notify: Boolean = false) {
        if (session.line == null) return
        work("Messages") {
            val rows = session.threads()
            val unread = rows.sumOf { it.unread }
            SwingUtilities.invokeLater {
                val signature = rows.joinToString("\n") { "${it.peer}\t${it.unread}\t${it.snippet.orEmpty()}" }
                if (signature == threadSignature) return@invokeLater
                threadSignature = signature
                val grew = knownUnread >= 0 && unread > knownUnread
                val keepPeer = threadList.selectedValue?.peer
                suppressThreadOpen = true
                threadModel.clear()
                rows.forEach { threadModel.addElement(it) }
                val keep = rows.indexOfFirst { it.peer == keepPeer }
                if (keep >= 0) threadList.selectedIndex = keep
                suppressThreadOpen = false
                knownUnread = unread
                (tabs["messages"] as? RailButton)?.setCount(unread)
                if (notify && grew) {
                    val peer = rows.firstOrNull { it.unread > 0 }?.peer?.ifBlank { null } ?: "a contact"
                    note("New message")
                    surfaces.notifyMessage(peer)
                    if (!keepPeer.isNullOrBlank() && rows.any { it.peer == keepPeer && it.unread > 0 }) {
                        work("Thread") {
                            val messages = session.conversation(keepPeer)
                            SwingUtilities.invokeLater { showTranscript(messages) }
                        }
                    }
                }
            }
        }
    }

    private fun openThread(thread: ThreadInfo) {
        val peer = thread.peer.ifBlank { "Message" }
        threadTitle.text = peer
        smsTo.text = thread.peer
        work("Thread") {
            val rows = session.conversation(thread.peer)
            SwingUtilities.invokeLater { showTranscript(rows) }
        }
    }

    private fun showTranscript(rows: List<MessageInfo>) {
        transcript.removeAll()
        if (rows.isEmpty()) {
            transcript.add(label("No messages yet."))
        }
        rows.forEach { row ->
            val inbound = row.direction.equals("in", ignoreCase = true)
            val align = if (inbound) Component.LEFT_ALIGNMENT else Component.RIGHT_ALIGNMENT
            val column = JPanel()
            column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
            column.isOpaque = false
            column.alignmentX = align
            if (row.body.isNotBlank()) {
                val bubble = JLabel("<html><body style='width:280px'>${escapeHtml(row.body)}</body></html>")
                bubble.foreground = ink
                bubble.background = if (inbound) surface else raised
                bubble.isOpaque = true
                bubble.border = EmptyBorder(10, 12, 10, 12)
                bubble.alignmentX = align
                column.add(bubble)
            }
            row.media.forEach { media ->
                val photo = JLabel(if (row.body.isBlank()) "Photo" else "")
                photo.foreground = caption
                photo.alignmentX = align
                column.add(Box.createVerticalStrut(6))
                column.add(photo)
                val source = media.name.ifBlank { media.url.orEmpty() }
                if (source.isNotBlank()) {
                    val cached = synchronized(photoCache) { photoCache[source] }
                    if (cached != null) {
                        photo.text = ""
                        photo.icon = javax.swing.ImageIcon(cached)
                        openPhoto(photo, synchronized(photoFull) { photoFull[source] } ?: cached)
                    } else {
                        work("Photo") {
                            val bytes = runCatching { session.media(source) }.getOrNull()
                            val image = bytes?.let { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) }
                            SwingUtilities.invokeLater {
                                if (image == null) {
                                    photo.text = "Photo unavailable"
                                    return@invokeLater
                                }
                                val maxW = 280.0
                                val maxH = 220.0
                                val scale = minOf(maxW / image.width, maxH / image.height, 1.0)
                                val w = (image.width * scale).toInt().coerceAtLeast(1)
                                val h = (image.height * scale).toInt().coerceAtLeast(1)
                                val scaled = image.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)
                                synchronized(photoCache) {
                                    photoCache[source] = scaled
                                    photoFull[source] = image
                                    while (photoCache.size > 40) {
                                        val first = photoCache.keys.first()
                                        photoCache.remove(first)
                                        photoFull.remove(first)
                                    }
                                }
                                photo.text = ""
                                photo.icon = javax.swing.ImageIcon(scaled)
                                openPhoto(photo, image)
                            }
                        }
                    }
                }
            }
            val whenLabel = JLabel(row.datetime.orEmpty())
            whenLabel.font = Font("Segoe UI", Font.PLAIN, UiScale.px(11))
            whenLabel.foreground = caption
            whenLabel.alignmentX = align
            transcript.add(column)
            transcript.add(whenLabel)
            transcript.add(Box.createVerticalStrut(10))
        }
        transcript.revalidate()
        transcript.repaint()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun refreshLine() {
        paintLineControls(SipBridge.snapshot())
    }

    private fun paintLineControls(snap: SipBridge.Snapshot) {
        val lines = session.lines()
        val key = lines.joinToString(",") { it.extension }
        if (key != paintedLines) {
            paintedLines = key
            lineSwitch.removeAll()
            historyFilters.removeAll()
            allRecentButton.putClientProperty("history", "all")
            missedRecentButton.putClientProperty("history", "missed")
            historyFilters.add(allRecentButton)
            lines.forEach { enrolled ->
                lineSwitch.add(callingLineButton(enrolled))
                historyFilters.add(historyLineButton(enrolled.extension))
            }
            historyFilters.add(missedRecentButton)
            historyFilters.add(keypadJump)
            lineList.removeAll()
            if (lines.isEmpty()) lineList.add(pin(label("No line yet")))
            lines.forEach { enrolled -> lineList.add(lineCard(enrolled)) }
            if (historyExtension != null && lines.none { it.extension == historyExtension }) historyExtension = null
        }
        styleLineButtons()
        styleRecentFilters()
        lines.forEachIndexed { index, enrolled ->
            val up = if (index == 0) snap.registered else snap.secondRegistered
            lineList.components.filterIsInstance<JComponent>().forEach { row ->
                if (row.getClientProperty("extension") != enrolled.extension) return@forEach
                val reg = row.getClientProperty("reg") as? JLabel ?: return@forEach
                reg.text = when {
                    up -> "Registered via ${snap.transport?.name ?: "TLS"}"
                    askedRegister -> "Registering"
                    else -> "Not registered"
                }
                reg.foreground = if (up) emerald else muted
            }
        }
        lineSwitch.revalidate()
        historyFilters.revalidate()
        lineList.revalidate()
    }

    private fun callingLineButton(enrolled: EnrolledLine): JButton {
        val button = JButton(enrolled.extension)
        button.putClientProperty("extension", enrolled.extension)
        button.isContentAreaFilled = false
        button.isFocusPainted = false
        button.font = uiBold
        button.addActionListener {
            session.choose(enrolled.extension)
            note("Calls and messages use ${enrolled.extension}")
            threadSignature = ""
            refreshThreads()
            paintLineControls(SipBridge.snapshot())
        }
        return button
    }

    private fun historyLineButton(extension: String): JButton {
        val button = JButton(extension)
        button.putClientProperty("history", extension)
        button.addActionListener {
            missedOnly = false
            historyExtension = extension
            styleRecentFilters()
            reloadRecents()
        }
        return button
    }

    private fun styleLineButtons() {
        val chosen = session.line?.extension
        lineSwitch.components.filterIsInstance<JButton>().forEach { button ->
            val on = button.getClientProperty("extension") == chosen
            button.foreground = if (on) goldInk else ink
            button.background = if (on) gold else raised
            button.isOpaque = true
        }
    }

    private fun lineCard(enrolled: EnrolledLine): JPanel {
        val card = column(ground)
        card.putClientProperty("extension", enrolled.extension)
        val ext = JLabel(enrolled.extension)
        ext.font = Font("Georgia", Font.PLAIN, UiScale.px(40))
        ext.foreground = emerald
        val name = label(enrolled.displayName.ifBlank { "Line" })
        name.foreground = ink
        val reg = label("Registering")
        card.putClientProperty("reg", reg)
        val role = label(if (enrolled.extension == session.line?.extension) "Default line" else "Available")
        card.add(pin(ext))
        card.add(pin(name))
        card.add(pin(reg))
        card.add(pin(role))
        if (enrolled.extension != session.line?.extension) {
            card.add(pin(ghost("Use for calls and messages", expand = false) {
                session.choose(enrolled.extension)
                note("Default line is ${enrolled.extension}")
                paintedLines = ""
                paintLineControls(SipBridge.snapshot())
            }))
        }
        val dnd = javax.swing.JCheckBox("Do not disturb on ${enrolled.extension}")
        styleCheck(dnd)
        dnd.addActionListener {
            work("Do not disturb") { session.setPbxDnd(enrolled, dnd.isSelected) }
        }
        card.add(pin(dnd))
        card.add(Box.createVerticalStrut(12))
        work("Do not disturb") {
            val enabled = runCatching { session.pbxDnd(enrolled) }.getOrDefault(false)
            SwingUtilities.invokeLater { dnd.isSelected = enabled }
        }
        return card
    }

    private fun connectMailbox(provider: MailboxAuth.Provider) {
        work("Contacts") {
            val count = MailboxAuth.connect(provider)
            note(
                if (count == 0) {
                    "${provider.title} returned no phone numbers"
                } else {
                    "Imported $count contacts from ${provider.title}"
                },
            )
        }
    }

    private fun importContacts() {
        val picker = javax.swing.JFileChooser()
        picker.dialogTitle = "Import Google or Outlook contacts"
        if (picker.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        work("Contacts") {
            val count = ContactBook.importFile(picker.selectedFile)
            note(if (count == 0) "No contacts with phone numbers were in that file" else "Imported $count contacts")
        }
    }

    private fun leaveSetup() {
        showRoot(if (session.lines().isEmpty()) "welcome" else "app")
        showTab("lines")
    }

    private fun missedSinceSeen(): Int =
        CallLog.load().count { it.kind == "Missed" && it.at > seenMissedAt }

    private fun markMissedSeen() {
        val latest = CallLog.load().filter { it.kind == "Missed" }.maxOfOrNull { it.at } ?: return
        if (latest > seenMissedAt) seenMissedAt = latest
    }

    private fun statusDetail(extension: String, registered: Boolean, onCall: Boolean): String {
        val missed = missedSinceSeen()
        val unread = knownUnread.coerceAtLeast(0)
        return buildString {
            append(profile.productName)
            if (extension.isNotBlank()) append(" · $extension")
            when {
                !registered -> append(" · not registered")
                onCall -> append(" · on a call")
            }
            if (missed > 0) append(" · $missed missed")
            if (unread > 0) append(" · $unread unread")
        }
    }

    private fun applyTaskbarBadge(registered: Boolean, onCall: Boolean, attention: Int) {
        val name = when {
            session.line == null -> null
            !registered -> "offline"
            attention > 0 -> if (attention > 9) "missed-9plus" else "missed-$attention"
            onCall -> "oncall"
            else -> null
        }
        val key = name ?: "clear"
        if (key == badgeKey) return
        badgeKey = key
        runCatching {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (!taskbar.isSupported(java.awt.Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)) return
            val image = if (name == null) {
                null
            } else {
                val scale = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                DesktopIcons.badgeImage(DesktopIcons.flavor(profile), name, scale)
            }
            taskbar.setWindowIconBadge(this, image)
        }
    }

    private fun showRoot(name: String) {
        if (::welcomeBack.isInitialized) welcomeBack.isVisible = session.lines().isNotEmpty()
        rootCards.show(root, name)
    }

    private var currentTab = ""
    private var seenMissedAt = 0L
    private var taskbarLight = false
    private var badgeKey = ""
    private var flashedIncoming = false

    private fun showTab(name: String) {
        currentTab = name
        appCards.show(appBody, name)
        tabs.forEach { (id, button) ->
            val on = id == name || (name == "thread" && id == "messages")
            (button as? RailButton)?.setChosen(on)
            button.foreground = if (on) gold else muted
            button.background = if (on) raised else ground
        }
    }

    private fun work(name: String, block: () -> Unit) {
        io.submit {
            try {
                block()
            } catch (err: Exception) {
                note("$name failed: ${err.message ?: err.javaClass.simpleName}")
            }
        }
    }

    private fun note(line: String) {
        SwingUtilities.invokeLater {
            status.text = line
            status.foreground = if (line.contains("failed", ignoreCase = true)) coral else muted
        }
    }

    private fun promptField(prompt: String): PromptField {
        val box = PromptField(prompt)
        box.background = surface
        box.foreground = ink
        box.caretColor = gold
        box.font = java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 16)
        box.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(hairline),
            javax.swing.border.EmptyBorder(8, 14, 8, 14),
        )
        box.preferredSize = java.awt.Dimension(120, 56)
        box.minimumSize = java.awt.Dimension(40, 56)
        box.alignmentX = Component.LEFT_ALIGNMENT
        return box
    }

    private fun wrappedLine(text: String, width: Int): JComponent {
        return object : JComponent() {
            override fun getPreferredSize(): Dimension {
                val lines = linesFor(getFontMetrics(uiFont), text, width)
                val height = (lines.size * getFontMetrics(uiFont).height).coerceAtLeast(uiFont.size)
                return Dimension(width, height)
            }
            override fun getMaximumSize() = preferredSize
            override fun getMinimumSize() = preferredSize
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.font = uiFont
                g2.color = muted
                val metrics = g2.fontMetrics
                var y = metrics.ascent
                for (line in linesFor(metrics, text, width)) {
                    g2.drawString(line, 0, y)
                    y += metrics.height
                }
                g2.dispose()
            }
        }.apply { alignmentX = Component.LEFT_ALIGNMENT }
    }

    private fun linesFor(metrics: java.awt.FontMetrics, text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(" ")) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (metrics.stringWidth(trial) <= width) current = trial
            else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun featureRow(title: String, detail: String, lines: Boolean): JPanel {
        val icon = object : JPanel() {
            override fun getPreferredSize() = java.awt.Dimension(40, 40)
            override fun getMaximumSize() = java.awt.Dimension(40, 40)
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = surface
                g2.fillRoundRect(0, 0, 39, 39, 12, 12)
                g2.color = hairline
                g2.drawRoundRect(0, 0, 39, 39, 12, 12)
                if (lines) {
                    g2.color = emerald
                    g2.fillOval(8, 12, 14, 14)
                    g2.color = java.awt.Color(0x79, 0xB4, 0xF2)
                    g2.fillOval(18, 12, 14, 14)
                } else {
                    g2.color = gold
                    g2.drawRoundRect(10, 12, 18, 14, 3, 3)
                    g2.drawLine(13, 22, 17, 17)
                    g2.drawLine(17, 17, 22, 22)
                }
                g2.dispose()
            }
        }
        icon.isOpaque = false
        icon.alignmentY = Component.TOP_ALIGNMENT
        val titleLabel = JLabel(title)
        titleLabel.foreground = ink
        titleLabel.font = uiBold
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        val detailLabel = wrappedLine(detail, UiScale.px(460))
        val copy = JPanel()
        copy.layout = BoxLayout(copy, BoxLayout.Y_AXIS)
        copy.isOpaque = false
        copy.alignmentY = Component.TOP_ALIGNMENT
        copy.add(titleLabel)
        copy.add(detailLabel)
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.background = ground
        row.isOpaque = true
        row.alignmentX = Component.LEFT_ALIGNMENT
        row.add(icon)
        row.add(Box.createHorizontalStrut(12))
        row.add(copy)
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun orDivider(): JComponent {
        return object : JComponent() {
            override fun getPreferredSize() = java.awt.Dimension(200, 24)
            override fun getMaximumSize() = java.awt.Dimension(Int.MAX_VALUE, 24)
            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = hairline
                g2.drawLine(0, height / 2, width, height / 2)
                g2.font = uiBold
                val words = "or"
                val wide = g2.fontMetrics.stringWidth(words)
                val x = (width - wide) / 2
                g2.color = ground
                g2.fillRect(x - 8, 0, wide + 16, height)
                g2.color = caption
                g2.drawString(words, x, height / 2 + g2.fontMetrics.ascent / 2)
                g2.dispose()
            }
        }
    }

    private fun noteCard(title: String, detail: String): JPanel {
        val card = column(surface)
        card.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(hairline),
            javax.swing.border.EmptyBorder(12, 12, 12, 12),
        )
        card.add(JLabel(title).apply { foreground = ink; font = uiBold })
        card.add(JLabel(detail).apply { foreground = caption; font = uiFont })
        card.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 72)
        return card
    }

    private fun footer(left: String, right: String, onLeft: () -> Unit): JPanel {
        val row = JPanel(java.awt.BorderLayout())
        row.background = ground
        if (left.isNotBlank()) {
            val link = JLabel(left)
            link.foreground = muted
            link.font = uiFont
            link.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            link.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onLeft()
            })
            row.add(link, java.awt.BorderLayout.WEST)
        }
        row.add(JLabel(right).apply { foreground = caption; font = uiFont }, java.awt.BorderLayout.EAST)
        row.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 36)
        return row
    }

    private fun brand(): JComponent {
        val block = column()
        block.add(pin(JLabel(profile.productName).apply { font = Font("Georgia", Font.PLAIN, 24); foreground = ink }))
        block.add(pin(JLabel(profile.brandSub).apply { font = uiBold; foreground = gold }))
        return block
    }

    private fun stack(): JPanel {
        val panel = JPanel(java.awt.GridBagLayout())
        panel.background = ground
        panel.border = javax.swing.border.EmptyBorder(8, 20, 20, 20)
        return panel
    }

    private fun grow(panel: JPanel, child: JComponent, gap: Int = 10) {
        val constraints = java.awt.GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = java.awt.GridBagConstraints.RELATIVE
        constraints.weightx = 1.0
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL
        constraints.anchor = java.awt.GridBagConstraints.WEST
        constraints.insets = java.awt.Insets(0, 0, gap, 0)
        panel.add(child, constraints)
    }

    private fun column(bg: Color = ground): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.background = bg
        panel.border = EmptyBorder(16, 16, 16, 16)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        return panel
    }

    private fun scroll(page: JComponent): JScrollPane {
        val wide = object : JPanel(java.awt.BorderLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
            override fun getScrollableTracksViewportWidth(): Boolean = true
            override fun getScrollableTracksViewportHeight(): Boolean = false
            override fun getScrollableBlockIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = 48
            override fun getScrollableUnitIncrement(visible: java.awt.Rectangle, orientation: Int, direction: Int) = 16
        }
        wide.background = ground
        wide.add(page, java.awt.BorderLayout.NORTH)
        val pane = JScrollPane(wide)
        pane.border = null
        pane.viewport.background = ground
        pane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        return pane
    }

    private fun watchHeadsets(onCall: Boolean) {
        if (!::headsetOffer.isInitialized || pollTick % 6 != 0) return
        val names = CallRinger.outputNames()
            .filter { !it.contains("mapper", ignoreCase = true) && !it.contains("Primary Sound Driver", ignoreCase = true) }
            .toSet()
        if (names.isEmpty()) return
        if (knownOutputs.isEmpty()) {
            knownOutputs = names
            return
        }
        val added = names - knownOutputs
        knownOutputs = names
        if (!onCall) {
            headsetOffer.isVisible = false
            return
        }
        val fresh = added.firstOrNull() ?: return
        if (offeredHeadsetName == fresh && headsetOffer.isVisible) return
        offeredHeadsetName = fresh
        headsetOffer.text = "Use headset"
        headsetOffer.toolTipText = fresh
        headsetOffer.isVisible = true
        surfaces.announce("Headset connected. Tap Use headset on the call.")
    }

    private fun useOfferedHeadset() {
        val wanted = offeredHeadsetName ?: return
        headsetOffer.isVisible = false
        work("Headset") {
            val before = SipBridge.audioDevices()
            val previous = before.map { it.name }.toSet()
            val captureName = AudioPrefs.captureName().ifBlank {
                before.firstOrNull { it.inputs > 0 }?.name.orEmpty()
            }
            if (SipBridge.refreshDevices() != 0) {
                note("Headset was seen, but the call audio could not switch")
                return@work
            }
            val devices = SipBridge.audioDevices()
            val playbackName = matchOutputName(wanted, devices.filter { it.outputs > 0 }.map { it.name }, previous)
            val playback = devices.firstOrNull { it.name == playbackName && it.outputs > 0 }
            val capture = devices.firstOrNull { it.inputs > 0 && it.name == captureName }
                ?: devices.firstOrNull { it.inputs > 0 }
            if (playback == null || capture == null || SipBridge.openDevices(capture.index, playback.index) != 0) {
                note("Headset was seen, but the call audio could not switch")
                return@work
            }
            AudioPrefs.savePlayback(playback.name)
            note("Speaker is ${playback.name}")
            SwingUtilities.invokeLater {
                fillingAudio = true
                if ((0 until speakerModel.size).none { speakerModel.getElementAt(it).name == playback.name }) {
                    speakerModel.addElement(playback)
                }
                speakerModel.selectedItem = (0 until speakerModel.size)
                    .map { speakerModel.getElementAt(it) }
                    .firstOrNull { it.name == playback.name }
                fillingAudio = false
            }
        }
    }

    private fun useSelectedSpeaker() {
        val picked = speakerModel.selectedItem as? SipBridge.AudioDevice ?: return
        work("Speaker") {
            val devices = SipBridge.audioDevices()
            val playback = devices.firstOrNull { it.name == picked.name && it.outputs > 0 } ?: picked
            val inputs = devices.filter { it.inputs > 0 }
            val captureName = pairCaptureName(playback.name, inputs.map { it.name })
                ?: AudioPrefs.captureName().takeIf { saved -> inputs.any { it.name == saved } }
                ?: inputs.firstOrNull()?.name
            val capture = inputs.firstOrNull { it.name == captureName }
            val opened = capture != null && SipBridge.openDevices(capture.index, playback.index) == 0
            SwingUtilities.invokeLater {
                if (!opened) {
                    fillingAudio = true
                    selectAudio(speakerModel, AudioPrefs.playbackName())
                    fillingAudio = false
                    note("Speaker was not changed")
                    return@invokeLater
                }
                AudioPrefs.savePlayback(playback.name)
                if (capture != null) {
                    AudioPrefs.saveCapture(capture.name)
                    fillingAudio = true
                    selectAudio(micModel, capture.name)
                    fillingAudio = false
                }
                val ringer = pairCaptureName(playback.name, CallRinger.outputNames())
                if (ringer != null) {
                    AudioPrefs.saveRinger(ringer)
                    fillingAudio = true
                    if ((0 until ringerModel.size).any { ringerModel.getElementAt(it) == ringer }) {
                        ringerModel.selectedItem = ringer
                    }
                    fillingAudio = false
                }
                note("Speaker is ${playback.name}")
                if (SipBridge.snapshot().incoming) {
                    CallRinger.stop()
                    CallRinger.start()
                }
            }
        }
    }

    private fun chooseLine(index: Int) {
        val line = session.lines().getOrNull(index) ?: return
        session.choose(line.extension)
        threadSignature = ""
        refreshThreads()
        paintLineControls(SipBridge.snapshot())
        note("Calls and messages use ${line.extension}")
    }

    private fun audioCombo(
        model: javax.swing.DefaultComboBoxModel<SipBridge.AudioDevice>,
        onPick: () -> Unit,
    ): JComponent {
        val box = javax.swing.JComboBox(model)
        box.font = uiFont
        box.background = raised
        box.foreground = ink
        box.alignmentX = Component.LEFT_ALIGNMENT
        box.addActionListener { if (!fillingAudio) onPick() }
        return box
    }

    private fun ringtoneCombo(): JComponent {
        val box = javax.swing.JComboBox(ringtoneModel)
        box.font = uiFont
        box.background = raised
        box.foreground = ink
        box.alignmentX = Component.LEFT_ALIGNMENT
        box.addActionListener {
            if (fillingAudio) return@addActionListener
            val picked = ringtoneModel.selectedItem as? String ?: return@addActionListener
            val builtIn = RingtoneLibrary.builtIn.firstOrNull { it.label == picked }
            val style = builtIn?.id ?: RingtoneLibrary.CUSTOM
            AudioPrefs.saveRingtoneStyle(style)
            if (SipBridge.snapshot().incoming || SipBridge.snapshot().callActive) return@addActionListener
            CallRinger.stop()
            CallRinger.start()
            Timer(2400) { CallRinger.stop() }.apply { isRepeats = false; start() }
        }
        return box
    }

    private fun reloadRingtoneList() {
        fillingAudio = true
        ringtoneModel.removeAllElements()
        RingtoneLibrary.builtIn.forEach { ringtoneModel.addElement(it.label) }
        val custom = AudioPrefs.ringtoneFile()
        if (custom != null) ringtoneModel.addElement(RingtoneLibrary.customLabel(custom.name))
        val style = AudioPrefs.ringtoneStyle()
        ringtoneModel.selectedItem = if (style == RingtoneLibrary.CUSTOM && custom != null) {
            RingtoneLibrary.customLabel(custom.name)
        } else {
            RingtoneLibrary.choice(style).label
        }
        fillingAudio = false
        if (::removeRingtone.isInitialized) removeRingtone.isVisible = custom != null
    }

    private fun chooseRingtone() {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "Ringtone"
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "WAV, AIFF, or AU",
            "wav",
            "wave",
            "aiff",
            "aif",
            "au",
        )
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        if (ringtoneExtension(file.name) == null) {
            note("Use a WAV, AIFF, or AU file.")
            return
        }
        if (file.length() > 8_000_000) {
            note("Ringtone is over 8 MB.")
            return
        }
        if (!ringtoneReadable(file)) {
            note("That file could not be played.")
            return
        }
        AudioPrefs.installRingtone(file)
        reloadRingtoneList()
        note("Ringtone added to the list")
    }

    private fun ringerCombo(): JComponent {
        val box = javax.swing.JComboBox(ringerModel)
        box.font = uiFont
        box.background = raised
        box.foreground = ink
        box.alignmentX = Component.LEFT_ALIGNMENT
        box.addActionListener {
            if (fillingAudio) return@addActionListener
            val picked = ringerModel.selectedItem as? String ?: return@addActionListener
            AudioPrefs.saveRinger(picked)
        }
        return box
    }

    private fun loadAudioDevices() {
        val devices = SipBridge.audioDevices()
        fillingAudio = true
        micModel.removeAllElements()
        speakerModel.removeAllElements()
        devices.filter { it.inputs > 0 }.forEach { micModel.addElement(it) }
        devices.filter { it.outputs > 0 }.forEach { speakerModel.addElement(it) }
        selectAudio(micModel, AudioPrefs.captureName())
        selectAudio(speakerModel, AudioPrefs.playbackName())
        ringerModel.removeAllElements()
        val ringers = CallRinger.outputNames()
        ringers.forEach { ringerModel.addElement(it) }
        val savedRinger = AudioPrefs.ringerName()
        if (ringers.contains(savedRinger)) ringerModel.selectedItem = savedRinger
        fillingAudio = false
        reloadRingtoneList()
        audioNote.text = if (devices.isEmpty()) {
            "Microphone and speaker appear after the line registers. The ringer list is ready now."
        } else {
            "Microphone and speaker are used for calls. The ringer is only the ringtone."
        }
    }

    private fun selectAudio(model: javax.swing.DefaultComboBoxModel<SipBridge.AudioDevice>, name: String) {
        if (name.isBlank()) return
        for (index in 0 until model.size) {
            val row = model.getElementAt(index)
            if (row.name == name) {
                model.selectedItem = row
                return
            }
        }
    }

    private fun pin(component: JComponent): JComponent {
        component.alignmentX = Component.LEFT_ALIGNMENT
        val height = component.preferredSize.height.coerceAtLeast(1)
        component.minimumSize = Dimension(160, height)
        component.maximumSize = Dimension(Int.MAX_VALUE, height)
        return component
    }

    private fun label(value: String): JLabel {
        val item = JLabel(value)
        item.foreground = muted
        item.font = uiFont
        item.alignmentX = Component.LEFT_ALIGNMENT
        return item
    }

    private fun wrappingCopy(value: String): JComponent {
        return object : JComponent() {
            init {
                alignmentX = Component.LEFT_ALIGNMENT
            }

            override fun getMinimumSize(): Dimension = Dimension(160, lineHeight())

            override fun getPreferredSize(): Dimension {
                val wide = textWidth()
                val lines = linesFor(getFontMetrics(uiFont), value, wide).size.coerceAtLeast(1)
                return Dimension(wide, lines * lineHeight())
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, getPreferredSize().height)

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = muted
                g2.font = uiFont
                val fm = g2.fontMetrics
                val lines = linesFor(fm, value, width.coerceAtLeast(1))
                lines.forEachIndexed { index, line ->
                    g2.drawString(line, 0, fm.ascent + index * fm.height)
                }
                g2.dispose()
                if (width > 80 && height + 1 < lines.size * lineHeight()) revalidate()
            }

            private fun textWidth(): Int {
                if (width > 80) return width
                val parentWidth = parent?.width ?: 0
                if (parentWidth > 120) return parentWidth - 32
                return UiScale.px(640)
            }

            private fun lineHeight(): Int = getFontMetrics(uiFont).height.coerceAtLeast(1)
        }
    }

    private fun mutedCopy(value: String): JLabel {
        val item = JLabel("<html><body style='width:440px'>$value</body></html>")
        item.foreground = muted
        item.font = uiFont
        return item
    }

    private fun field(): JTextField {
        val box = JTextField()
        box.background = raised
        box.foreground = ink
        box.caretColor = gold
        box.font = uiFont
        box.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(hairline),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
        return box
    }

    private fun banner(
        lead: String,
        emphasis: String,
        compact: Boolean = false,
        edge: Boolean = false,
        onBack: (() -> Unit)? = null,
    ): HeroBanner = HeroBanner(
        lead,
        emphasis,
        compact,
        onBack,
        edge,
        profile.productName,
        profile.brandSub,
        theme,
    )

    private fun primary(title: String, action: () -> Unit): WelcomeButton =
        WelcomeButton(title, gold, goldInk).apply { addActionListener { action() } }

    private fun ghost(title: String, expand: Boolean = true, action: () -> Unit): WelcomeButton =
        WelcomeButton(
            title,
            surface,
            ink,
            quiet = true,
            quietFill = surface,
            line = hairline,
            disabledInk = theme.disabled,
            expand = expand,
        ).apply { addActionListener { action() } }

    private fun goldButton(title: String, action: () -> Unit): JButton = button(title, gold, goldInk, action)

    private fun coralButton(title: String, action: () -> Unit): JButton = button(title, coral, ground, action)

    private fun ghostButton(title: String, action: () -> Unit): JButton = button(title, raised, ink, action)

    private class DialEntry(private val hintColor: Color) : JTextField() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isNotEmpty()) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = hintColor
            g2.font = Font("Segoe UI", Font.PLAIN, UiScale.px(16))
            val fm = g2.fontMetrics
            val hint = "Enter a number"
            g2.drawString(hint, (width - fm.stringWidth(hint)) / 2, (height - fm.height) / 2 + fm.ascent)
            g2.dispose()
        }
    }

    private class DialKey(
        private val digit: String,
        private val letters: String,
        private val fill: Color,
        private val ink: Color,
        private val caption: Color,
        private val ring: Color,
        action: () -> Unit,
    ) : JButton() {
        private var hot = false

        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            addActionListener { action() }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(event: java.awt.event.MouseEvent) {
                    hot = true
                    repaint()
                }

                override fun mouseExited(event: java.awt.event.MouseEvent) {
                    hot = false
                    repaint()
                }
            })
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusGained(event: java.awt.event.FocusEvent) = repaint()
                override fun focusLost(event: java.awt.event.FocusEvent) = repaint()
            })
        }

        override fun getPreferredSize(): Dimension = Dimension(UiScale.px(76), UiScale.px(58))

        override fun getMinimumSize(): Dimension = Dimension(UiScale.px(56), UiScale.px(48))

        override fun getMaximumSize(): Dimension = getPreferredSize()

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = if (hot) ring else fill
            g2.fillRoundRect(1, 1, width - 3, height - 3, 16, 16)
            if (isFocusOwner) {
                g2.color = ring
                g2.drawRoundRect(2, 2, width - 5, height - 5, 14, 14)
            }
            g2.color = ink
            val digitSize = if (height >= 48) 22 else 16
            g2.font = Font("Consolas", Font.PLAIN, digitSize)
            val digitWidth = g2.fontMetrics.stringWidth(digit)
            val digitY = if (letters.isEmpty() || height < 48) (height + g2.fontMetrics.ascent) / 2 - 2 else height / 2 - 4
            g2.drawString(digit, (width - digitWidth) / 2, digitY)
            if (letters.isNotEmpty() && height >= 48) {
                g2.color = caption
                g2.font = Font("Segoe UI", Font.PLAIN, 10)
                val letterWidth = g2.fontMetrics.stringWidth(letters)
                g2.drawString(letters, (width - letterWidth) / 2, digitY + 16)
            }
            g2.dispose()
        }
    }

    private fun button(title: String, fill: Color, ink: Color, action: () -> Unit): JButton {
        val button = JButton(title)
        button.background = fill
        button.foreground = ink
        button.font = uiBold
        button.isFocusPainted = false
        button.margin = Insets(10, 8, 10, 8)
        button.alignmentX = Component.LEFT_ALIGNMENT
        button.addActionListener { action() }
        return button
    }
}
