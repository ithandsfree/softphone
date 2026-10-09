package net.ithandsfree.softphone.win

import net.ithandsfree.softphone.win.ui.style
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

/** Largest picture file read from a drop or the file picker. MMS needs about 1 MB after resizing anyway. */
private const val MAX_PHOTO_FILE = 25L * 1024 * 1024

/** Decoded original photos kept in memory for the full-size viewer. */
private const val FULL_PHOTOS_KEPT = 8

/** Call volume choices: percent of the audio as received. */
private val CALL_VOLUMES = listOf(100 to "Normal", 150 to "Louder", 200 to "Loud", 300 to "Loudest")

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
    // Empty with a caret, as in the mockup; the accessible name tells a screen reader what it is.
    private val dial = JTextField().apply { getAccessibleContext().accessibleName = "Number to call" }
    private val callState = label("No call")
    private val smsTo = field()
    private val smsBody: JTextArea = net.ithandsfree.softphone.win.ui.HintArea("Write a message", caption).apply {
        getAccessibleContext().accessibleName = "Message"
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
    private val recentRows = net.ithandsfree.softphone.win.ui.WidthTrackingPanel()
    private var recentEntries: List<CallEntry> = emptyList()
    private var recentIndex = -1
    private var missedOnly = false
    private var historyExtension: String? = null
    private var lastCallExt = ""
    private var paintedLines = ""
    private var browsingDuringCall = false
    private var detailOpen = false
    private var undoRecent: CallEntry? = null
    private val findBox = JTextField()
    private val headerStatus = JLabel("No line")
    private lateinit var recentsPane: JPanel
    private var narrowList = false
    private val recentEmpty = label("No recent calls yet.")
    private val allRecentButton = JButton("All lines")
    private val missedRecentButton = JButton("Missed")
    private val lineSwitch = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0))
    private val callTabs = JPanel()
    private val callsBody = JPanel(java.awt.CardLayout())
    private val contactsList = net.ithandsfree.softphone.win.ui.WidthTrackingPanel()
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
    private var micPeak = 0
    private var micPeakAt = 0L
    private lateinit var removeRingtone: JComponent

    private val tokens = net.ithandsfree.softphone.win.ui.Tokens.of(profile.id)
    private lateinit var titleBar: net.ithandsfree.softphone.win.ui.TitleBar
    private lateinit var toast: net.ithandsfree.softphone.win.ui.Toast
    private lateinit var navRail: net.ithandsfree.softphone.win.ui.NavRail
    private val lineRail = net.ithandsfree.softphone.win.ui.LineRail(tokens) { ext -> pickLine(ext) }
    private lateinit var dialPad: net.ithandsfree.softphone.win.ui.DialPad
    private lateinit var callsTabs: net.ithandsfree.softphone.win.ui.UnderlineTabs
    private lateinit var bottomNav: net.ithandsfree.softphone.win.ui.BottomNav
    private lateinit var callsPage: JPanel
    private val callsTitle = JLabel("Calls")
    private var compactMode = false
    private val callTracker = CallTracker()
    private lateinit var callWaitingBar: net.ithandsfree.softphone.win.ui.CallWaitingBar
    private var waitingAnnounced = -1
    private lateinit var voicemailPane: net.ithandsfree.softphone.win.ui.VoicemailPane
    private val vmData = java.util.concurrent.ConcurrentHashMap<String, VoicemailResponse>()
    private val vmNotice = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val vmNew = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var vmFilter: String? = null
    private var vmOpen: String? = null
    @Volatile private var vmLoading = false
    private val vmAudio = LinkedHashMap<String, ByteArray>()
    private var vmProgress: Timer? = null
    private val vmDeleting: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var sendToDevicesButton: JButton? = null
    private var callVolumeCombo: javax.swing.JComboBox<String>? = null
    // Blind transfer in progress: what the person asked for, and since when; refreshCall reports the PBX's answer.
    private var transferWatch: String? = null
    private var transferSince = 0L
    private val shortcutEditRows = linkedMapOf<Shortcut, net.ithandsfree.softphone.win.ui.ShortcutEditRow>()
    private var capturingRow: net.ithandsfree.softphone.win.ui.ShortcutEditRow? = null
    private var lastWideBounds: java.awt.Rectangle? = null
    private lateinit var inCallPane: net.ithandsfree.softphone.win.ui.InCallPane
    private lateinit var callDetailPane: net.ithandsfree.softphone.win.ui.CallDetailPane
    private lateinit var linesPage: net.ithandsfree.softphone.win.ui.LinesPage
    private lateinit var settingsShell: net.ithandsfree.softphone.win.ui.SettingsShell
    private var settingsSection = "audio"
    private val settingsMeter = net.ithandsfree.softphone.win.ui.LevelBars(tokens)
    private val echoCaption = JLabel("Calls the PBX echo test. Speak, and you'll hear yourself back.")
    private var pinToggle: net.ithandsfree.softphone.win.ui.Switch? = null
    private var dndToggle: net.ithandsfree.softphone.win.ui.Switch? = null
    private var linesSelected: String? = null
    private val lineDnd = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val lineCaps = java.util.concurrent.ConcurrentHashMap<String, LineCapabilities>()
    private var dtmfHolder: JComponent? = null
    private val activeCard = net.ithandsfree.softphone.win.ui.ActiveCallCard(tokens) {
        browsingDuringCall = false
        showRoot("app")
        showTab("calls")
        callDetailCards.show(callDetail, "live")
    }
    private var convoLineKey = ""
    private val messagesLineRail =net.ithandsfree.softphone.win.ui.LineRail(tokens) { ext -> pickLine(ext) }
    private val threadSearch = JTextField().apply {
        putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Search threads")
    }
    private var allThreads: List<ThreadInfo> = emptyList()
    private lateinit var convoHeader: net.ithandsfree.softphone.win.ui.ConversationHeader
    private lateinit var viaBar: net.ithandsfree.softphone.win.ui.ViaBar
    private lateinit var attachCard: net.ithandsfree.softphone.win.ui.AttachmentCard
    private var transcriptScroll: JScrollPane? = null
    private val newMessageRow = JPanel(BorderLayout(12, 0))
    private val composerFrom = JLabel(" ")
    private val composerFromDot = net.ithandsfree.softphone.win.ui.Dot(tokens.line1, 7)
    private val allChip = net.ithandsfree.softphone.win.ui.Chip(tokens, "All lines") {
        missedOnly = false
        historyExtension = null
        styleRecentFilters()
        reloadRecents()
    }
    private val missedChip = net.ithandsfree.softphone.win.ui.Chip(tokens, "Missed") {
        missedOnly = true
        historyExtension = null
        styleRecentFilters()
        reloadRecents()
    }

    /** Extension whose link setup is getting a sign-in, or null when the sign-in sets up a new line. */
    private var signInFor: String? = null
    private val signInUser = field()
    private val signInPassword = javax.swing.JPasswordField().apply {
        background = raised
        foreground = ink
        caretColor = gold
        font = uiFont
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(hairline),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
    }
    private val signInIntro = label("Sign in with your user account")
    private val signInError = label(" ").apply { foreground = coral }

    init {
        val windowIcons = DesktopIcons.windowIcons(DesktopIcons.flavor(profile))
        if (windowIcons.isNotEmpty()) iconImages = windowIcons
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        contentPane.background = ground
        root.background = ground
        installTitleBar()
        toast = net.ithandsfree.softphone.win.ui.Toast(this, tokens)
        root.add(welcome(), "welcome")
        root.add(scroll(linkSent()), "sent")
        root.add(scroll(codeEntry()), "code")
        root.add(scroll(signInPage()), "signin")
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
            // Lines saved by a build before 0.1.33 hold plain-text secrets. Seal them now.
            if (LineBook.hasPlainSecrets()) runCatching { LineBook.save(savedLines, LineBook.defaultExtension()) }
            showRoot("app")
            showTab("calls")
            // Rows were built before the lines were restored; redraw them with line names and colours.
            reloadRecents()
            // Setup-link tokens become long-lived device tokens (no sign-in screen for messages).
            work("Lines") { session.upgradeTokens() }
            SwingUtilities.invokeLater { dial.requestFocusInWindow() }
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
                if (SipBridge.snapshot().waitingState == 1) {
                    answerWaiting()
                } else {
                    reveal()
                    work("Answer") { session.answer(); note("Answered") }
                }
            },
            onDecline = {
                work("Decline") {
                    val snap = SipBridge.snapshot()
                    when {
                        snap.waitingState == 1 -> session.waitingEnd()
                        snap.incoming -> session.decline()
                        else -> session.hangup()
                    }
                }
            },
            onHangup = { work("Hang up") { session.hangup() } },
            onMute = { toggleMute() },
            onOpen = { reveal() },
            onQuit = { quitPhone() },
            onPin = { pinned -> isAlwaysOnTop = pinned },
            tokens = tokens,
            mark = brandMark(18),
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
        applyGlobalHotkeys()

        Timer(300) { refreshCall() }.apply { isRepeats = true; start() }
        Timer(80) { paintMicLevel() }.apply { isRepeats = true; start() }
        SwingUtilities.invokeLater { applyWindowShape() }
        // Voicemail: first look shortly after start, then every minute (new-message toast and badges).
        Timer(60_000) { if (session.lines().isNotEmpty()) loadVoicemail(quiet = true) }.apply { initialDelay = 8_000; isRepeats = true; start() }
    }

    /** Design review only: the incoming window for the second line with a sample caller. */
    internal fun previewIncoming() {
        val ext = session.lines().getOrNull(1)?.extension ?: session.line?.extension
        surfaces.previewIncoming(
            net.ithandsfree.softphone.win.ui.CallCard(
                title = "(705) 555-0142",
                titleIsNumber = true,
                subtitle = "Not in contacts",
                lineLabel = lineLabel(ext),
                lineColorIndex = lineIndex(ext) ?: 0,
                lineNumber = session.lines().firstOrNull { it.extension == ext }?.did?.let { displayNumber(it) },
            ),
        )
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
        net.ithandsfree.softphone.win.ui.ShortcutSheet.fit(rootPane)
        if (!isVisible || (extendedState and java.awt.Frame.ICONIFIED) != 0) return
        applyWindowShape()
        if (width < 360 || height < 400) return
        WindowPrefs.saveBounds(x, y, width, height)
        if (width >= 720 && extendedState == java.awt.Frame.NORMAL) lastWideBounds = bounds
    }

    /**
     * Below 720 px the window becomes the phone layout (`desktop-compact.png`): bottom bar instead of the rail,
     * pin and expand in the title bar, and Calls as one pane with Keypad / Recents / Contacts tabs.
     */
    private fun applyWindowShape() {
        if (!::recentsPane.isInitialized || !::callsPage.isInitialized) return
        val compact = width in 1..719
        keypadJump.isVisible = false
        if (compact != compactMode) {
            compactMode = compact
            titleBar.compact(compact)
            titleBar.pinned(WindowPrefs.pinned())
            navRail.isVisible = !compact
            bottomNav.isVisible = compact
            callsTitle.isVisible = !compact
            callsTabs.only(if (compact) listOf("keypad", "recents", "contacts", "voicemail") else listOf("recents", "contacts", "voicemail"))

            dialPad.compact(compact)
            dial.text = dial.text // restyle the number for the new size
            if (::settingsShell.isInitialized) settingsShell.compact(compact)
            if (::linesPage.isInitialized) linesPage.compact(compact)
        }
        val snap = SipBridge.snapshot()
        val callShown = (snap.callActive || snap.incoming) && !browsingDuringCall
        callsPage.removeAll()
        when {
            !compact -> {
                recentsPane.preferredSize = Dimension(net.ithandsfree.softphone.win.ui.Space.LIST_W, 200)
                recentsPane.border = BorderFactory.createMatteBorder(0, 0, 0, 1, tokens.divider)
                recentsPane.isVisible = true
                callsBody.isVisible = true
                callDetail.isVisible = true
                callsPage.add(recentsPane, BorderLayout.WEST)
                callsPage.add(callDetail, BorderLayout.CENTER)
                callsTabs.select(callsView)
                historyFilters.isVisible = callsView == "recents"
            }
            narrowList && !callShown && !detailOpen -> {
                recentsPane.preferredSize = null
                recentsPane.border = EmptyBorder(0, 0, 0, 0)
                recentsPane.isVisible = true
                callsBody.isVisible = true
                historyFilters.isVisible = callsView == "recents"
                callsPage.add(recentsPane, BorderLayout.CENTER)
                callsTabs.select(callsView)
            }
            else -> {
                // Keypad, a recent's detail, or the call: the line rail and tabs stay on top, except during a call.
                recentsPane.preferredSize = null
                recentsPane.border = EmptyBorder(0, 0, 0, 0)
                recentsPane.isVisible = !callShown
                callsBody.isVisible = false
                historyFilters.isVisible = false
                callDetail.isVisible = true
                callsPage.add(recentsPane, BorderLayout.NORTH)
                callsPage.add(callDetail, BorderLayout.CENTER)
                callsTabs.select(if (detailOpen) callsView else "keypad")
            }
        }
        callsPage.revalidate()
        callsPage.repaint()
    }

    /** Compact title bar: pin on top. Same setting as Settings › Appearance and the tray. */
    private fun setPinned(on: Boolean) {
        WindowPrefs.savePin(on)
        isAlwaysOnTop = on
        surfaces.setToggles(on, WindowPrefs.dnd())
        pinToggle?.set(on)
        titleBar.pinned(on)
    }

    /** Compact title bar: expand to the full layout at the last wide size, or 1100 × 760. */
    private fun expandWindow() {
        val wide = lastWideBounds
        if (wide != null && wide.width >= 720) {
            bounds = wide
        } else {
            val screen = graphicsConfiguration.bounds
            val w = 1100.coerceAtMost(screen.width - 40)
            val h = height.coerceAtLeast(760).coerceAtMost(screen.height - 40)
            setBounds((x - (w - width) / 2).coerceIn(screen.x, screen.x + screen.width - w), y.coerceAtLeast(screen.y), w, h)
        }
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
        callExtension()?.let { ext ->
            sendToDevicesButton?.text = "Send to my other devices ($ext)"
            sendToDevicesButton?.toolTipText = "Rings your desk phone, mobile app and any other device on $ext"
        }
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
        val devices = WelcomeButton("Send to my other devices", raised, ink, quiet = true, quietFill = raised, line = hairline, expand = false, focusRing = gold)
        devices.addActionListener { sendToOtherDevices() }
        devices.alignmentX = Component.LEFT_ALIGNMENT
        sendToDevicesButton = devices
        panel.add(title)
        panel.add(Box.createVerticalStrut(10))
        panel.add(devices)
        panel.add(Box.createVerticalStrut(14))
        panel.add(wrappingCopy("Or send it to someone else:"))
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

    /** Click opens the original. Only the last few originals stay decoded; older ones are fetched again. */
    private fun openPhoto(label: JLabel, source: String) {
        label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                work("Photo") {
                    val image = loadPhoto(source) ?: throw IllegalStateException("Photo is not available")
                    SwingUtilities.invokeLater { showLargePhoto(image) }
                }
            }
        })
    }

    /** Original picture for a message, decoded once and kept for the [FULL_PHOTOS_KEPT] most recent. */
    private fun loadPhoto(source: String): java.awt.image.BufferedImage? {
        synchronized(photoFull) { photoFull[source] }?.let { return it as? java.awt.image.BufferedImage }
        val bytes = runCatching { session.media(source) }.getOrNull() ?: return null
        val image = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        synchronized(photoFull) {
            photoFull[source] = image
            // A decoded 12 MP phone photo is ~48 MB; keep only a handful.
            while (photoFull.size > FULL_PHOTOS_KEPT) photoFull.remove(photoFull.keys.first())
        }
        return image
    }

    /** Full-size photo, aspect ratio kept, fitted to 85% of the screen; Esc closes. Never modal (call timer). */
    private fun showLargePhoto(image: java.awt.Image) {
        val dialog = JDialog(this, "Photo")
        dialog.isModal = false
        dialog.contentPane = net.ithandsfree.softphone.win.ui.PhotoViewer(tokens, image)
        val screen = graphicsConfiguration.bounds
        val iw = image.getWidth(null).coerceAtLeast(1).toDouble()
        val ih = image.getHeight(null).coerceAtLeast(1).toDouble()
        val fit = minOf(screen.width * 0.85 / iw, screen.height * 0.85 / ih, 1.0)
        dialog.contentPane.preferredSize = Dimension((iw * fit).toInt().coerceAtLeast(240), (ih * fit).toInt().coerceAtLeast(160))
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "close")
        dialog.rootPane.actionMap.put("close", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = dialog.dispose()
        })
        dialog.isVisible = true
    }

    private fun submitTransfer() {
        val number = transferField.text.trim()
        if (number.isBlank()) return
        transferDialog.isVisible = false
        blindTransfer(number, number)
    }

    /** The extension the current call is on (the line it rang on or was placed from). */
    private fun callExtension(): String? =
        SipBridge.snapshot().callExtension.ifBlank { null } ?: session.line?.extension

    /**
     * "Send to my other devices": a blind transfer to the call's own extension, so the desk phone, the mobile app and
     * any other device on that extension ring and whichever is picked up takes the call.
     */
    private fun sendToOtherDevices() {
        val ext = callExtension() ?: return
        transferDialog.isVisible = false
        blindTransfer(ext, "your other devices on $ext")
    }


    private fun blindTransfer(number: String, shown: String) {
        DiagLog.app("transfer requested to $number")
        transferWatch = shown
        transferSince = System.currentTimeMillis()
        work("Transfer") {
            session.transfer(number)
            note("Sending the call to $shown…")
        }
    }

    /** Called from refreshCall: turns the PBX's transfer NOTIFY into a plain message. */
    private fun watchTransfer(snap: SipBridge.Snapshot) {
        val shown = transferWatch ?: return
        val code = snap.transferCode
        when {
            code in 200..299 -> {
                note("Call sent to $shown")
                transferWatch = null
            }
            code >= 300 && snap.transferFinal -> {
                note("Transfer to $shown failed: $code ${snap.transferText}".trim())
                transferWatch = null
            }
            !snap.callActive -> {
                // The PBX ended our leg without a final NOTIFY: the transfer went through.
                note("Call sent to $shown")
                transferWatch = null
            }
            System.currentTimeMillis() - transferSince > 20_000 -> {
                note("No answer from the PBX about the transfer to $shown. The call is still with you.")
                transferWatch = null
            }
        }
    }

    private fun dispatchShortcut(event: java.awt.event.KeyEvent): Boolean {
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return false
        net.ithandsfree.softphone.win.ui.KeyCapture.listener?.let { return it(event) }
        // Only keys typed into this window; the incoming and mini windows have their own keys.
        val window = event.component as? java.awt.Window ?: SwingUtilities.getWindowAncestor(event.component)
        if (window !== this && window?.owner !== this) return false
        if (net.ithandsfree.softphone.win.ui.ShortcutSheet.isOpen(rootPane)) {
            if (event.keyCode == java.awt.event.KeyEvent.VK_ESCAPE || event.keyChar == '?') {
                net.ithandsfree.softphone.win.ui.ShortcutSheet.close(rootPane)
                return true
            }
            return false
        }
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val typing = focus is javax.swing.text.JTextComponent
        val plain = !event.isControlDown && !event.isAltDown && !event.isMetaDown
        val snap = SipBridge.snapshot()
        val onCall = snap.callActive && !snap.incoming
        val keymap = KeymapStore.current()
        val action = keymap.actionFor(event)
        if (action != null && (!action.inCall || (onCall && !typing)) && (!keymap.chord(action).plain || !typing)) {
            if (runShortcut(action, snap)) return true
        }
        return when {
            plain && !typing && (event.keyChar == '?' || (event.isShiftDown && event.keyCode == java.awt.event.KeyEvent.VK_SLASH)) -> {
                showShortcuts()
                true
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
            event.isControlDown && !event.isShiftDown && !event.isAltDown && event.keyCode == java.awt.event.KeyEvent.VK_Z &&
                undoRecent != null && !typing -> {
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
            plain && !typing && onCall && showDtmf && event.keyChar in "0123456789*#" -> {
                sendDtmf(event.keyChar.toString())
                true
            }
            else -> false
        }
    }

    /** Runs a remappable action; false when it does not apply right now, so the key goes on to the focused field. */
    private fun runShortcut(action: Shortcut, snap: SipBridge.Snapshot): Boolean {
        when (action) {
            Shortcut.SEARCH -> focusDial()
            Shortcut.LINE_1 -> chooseLine(0)
            Shortcut.LINE_2 -> chooseLine(1)
            Shortcut.NEW_MESSAGE -> startNewMessage()
            Shortcut.TOGGLE_DND -> toggleActiveLineDnd()
            Shortcut.TAB_CALLS -> { showRoot("app"); showTab("calls") }
            Shortcut.TAB_MESSAGES -> { showRoot("app"); refreshThreads(); showTab("messages") }
            Shortcut.TAB_LINES -> { showRoot("app"); refreshLine(); showTab("lines") }
            Shortcut.TAB_SETTINGS -> { showRoot("app"); showTab("settings"); loadAudioDevices() }
            Shortcut.CALL_ANSWER -> answerOrCall(snap)
            Shortcut.END_DECLINE -> endOrDecline(snap)
            Shortcut.MUTE -> toggleMute()
            Shortcut.HOLD -> toggleHold()
            Shortcut.TRANSFER -> openTransfer()
            Shortcut.KEYPAD -> toggleDtmfPad()
            Shortcut.NEXT_THREAD -> if (currentTab == "messages") moveThread(1) else return false
            Shortcut.PREVIOUS_THREAD -> if (currentTab == "messages") moveThread(-1) else return false
            Shortcut.GLOBAL_ANSWER, Shortcut.GLOBAL_HANG_UP, Shortcut.GLOBAL_MUTE -> runGlobal(action)
        }
        return true
    }

    /** System-wide hotkeys (from [GlobalHotkeys]); they act on the call whichever app is in front. */
    private fun runGlobal(action: Shortcut) {
        val snap = SipBridge.snapshot()
        when (action) {
            Shortcut.GLOBAL_ANSWER -> if (snap.incoming) work("Answer") { session.answer(); note("Answered") }
            Shortcut.GLOBAL_HANG_UP -> endOrDecline(snap)
            Shortcut.GLOBAL_MUTE -> if (snap.callActive && !snap.incoming) toggleMute()
            else -> Unit
        }
    }

    private fun moveThread(step: Int) {
        val size = threadModel.size
        if (size == 0) return
        val next = if (threadList.selectedIndex < 0) 0 else (threadList.selectedIndex + step).coerceIn(0, size - 1)
        threadList.selectedIndex = next
        threadList.ensureIndexIsVisible(next)
    }

    /** Do not disturb on the PBX for the line that calls and messages use now (Ctrl Shift D by default). */
    private fun toggleActiveLineDnd() {
        val line = session.line ?: return
        val extension = line.extension
        work("Do not disturb") {
            val on = !(lineDnd[extension] ?: session.pbxDnd(line))
            session.setPbxDnd(line, on)
            lineDnd[extension] = on
            note(if (on) "${lineLabel(extension)} is on Do not disturb" else "${lineLabel(extension)} is available")
            loadLineFacts(extension)
        }
    }

    /** (Re)registers the system-wide keys after start and after every change in Settings. */
    private fun applyGlobalHotkeys() {
        val keymap = KeymapStore.current()
        GlobalHotkeys.apply(keymap, onAction = { runGlobal(it) }) { refused ->
            if (refused.isNotEmpty()) {
                note("Another app already uses ${refused.joinToString(", ") { keymap.chord(it).label() }}")
            }
        }
    }

    /** Call waiting: the bar above the call controls, Do not disturb, and the diagnostic log. */
    private fun watchCallWaiting(snap: SipBridge.Snapshot) {
        if (!::callWaitingBar.isInitialized) return
        val ext = snap.waitingExtension
        if (snap.waitingState == 0) {
            waitingAnnounced = -1
        } else if (waitingAnnounced != snap.waitingId) {
            waitingAnnounced = snap.waitingId
            if (snap.waitingState == 1) {
                DiagLog.app("call waiting on $ext")
                // Do not disturb on this PC: the second call goes on as busy (other devices, then voicemail).
                if (WindowPrefs.dnd()) work("Second call") { session.waitingEnd() }
            }
        }
        val showState = if (snap.waitingState == 1 && WindowPrefs.dnd()) 0 else snap.waitingState
        val party = displayParty(snap.waitingRemote, "")
        val person = ContactBook.nameFor(party)
        val caller = person?.name ?: displayNumber(party).ifBlank { "Caller ID withheld" }
        callWaitingBar.show(showState, caller, person == null && party.isNotBlank(), lineLabel(ext), lineIndex(ext) ?: 0)
        callWaitingBar.parent?.isVisible = callWaitingBar.isVisible
    }

    /** Hold & answer: the current call goes on hold and the ringing one is answered. */
    private fun answerWaiting() {
        reveal()
        work("Answer") {
            session.waitingAnswer()
            note("Answered. The first call is on hold.")
        }
    }

    private fun answerOrCall(snap: SipBridge.Snapshot) {
        when {
            snap.waitingState == 1 -> answerWaiting()
            snap.incoming -> work("Answer") { session.answer(); note("Answered") }
            !snap.callActive -> placeCall()
        }
    }

    private fun endOrDecline(snap: SipBridge.Snapshot) {
        when {
            // While a second call rings, End / decline declines that one; the current call stays up.
            snap.waitingState == 1 -> work("Decline") { session.waitingEnd() }
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
        clearPhoto()
        showConversationFor(null)
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
        smsTo.requestFocus()
    }

    /** The `?` sheet, built from the current keymap so remapped keys show as they are. */
    private fun showShortcuts() {
        val keymap = KeymapStore.current()
        fun keys(vararg actions: Shortcut) = actions.map { keymap.chord(it).label() }
        val groups = listOf(
            net.ithandsfree.softphone.win.ui.SheetGroup(
                ShortcutGroup.ANYWHERE.title,
                listOf(
                    "Search or dial" to keys(Shortcut.SEARCH),
                    "Switch to line 1 / line 2" to keys(Shortcut.LINE_1, Shortcut.LINE_2),
                    "New message" to keys(Shortcut.NEW_MESSAGE),
                    "Toggle Do not disturb (active line)" to keys(Shortcut.TOGGLE_DND),
                    "Calls · Messages · Lines · Settings" to tabKeys(keymap),
                    "This sheet" to listOf("?"),
                ),
            ),
            net.ithandsfree.softphone.win.ui.SheetGroup(
                ShortcutGroup.CALLS.title,
                listOf(
                    Shortcut.CALL_ANSWER, Shortcut.END_DECLINE, Shortcut.MUTE, Shortcut.HOLD, Shortcut.TRANSFER, Shortcut.KEYPAD,
                ).map { it.label to keys(it) },
            ),
            net.ithandsfree.softphone.win.ui.SheetGroup(
                ShortcutGroup.MESSAGES.title,
                FIXED_SHORTCUTS.filter { it.first == ShortcutGroup.MESSAGES }.map { it.second to listOf(it.third) } +
                    ("Next / previous thread" to keys(Shortcut.NEXT_THREAD, Shortcut.PREVIOUS_THREAD)),
            ),
            net.ithandsfree.softphone.win.ui.SheetGroup(
                if (keymap.globalEnabled) "System-wide (on)" else ShortcutGroup.SYSTEM.title,
                listOf(Shortcut.GLOBAL_ANSWER, Shortcut.GLOBAL_HANG_UP, Shortcut.GLOBAL_MUTE).map { it.label to keys(it) },
                note = "Turn on in Settings › Keyboard shortcuts. Every shortcut can be remapped there.",
            ),
        )
        net.ithandsfree.softphone.win.ui.ShortcutSheet.show(rootPane, tokens, groups)
    }

    /** "Alt 1 … Alt 4" when the tab keys are still a run; otherwise each one. */
    private fun tabKeys(keymap: Keymap): List<String> {
        val tabs = listOf(Shortcut.TAB_CALLS, Shortcut.TAB_MESSAGES, Shortcut.TAB_LINES, Shortcut.TAB_SETTINGS)
        val labels = tabs.map { keymap.chord(it).label() }
        return if (tabs.all { keymap.isDefault(it) }) listOf(labels.first(), "…", labels.last()) else labels
    }

    private fun quitPhone() {
        CallRinger.stop()
        runCatching { SipBridge.stop() }
        GlobalHotkeys.stop()
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
        grow(body, ghost("Sign in with your user account") { openSignIn(null) }, 8)
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

    private fun openSignIn(extension: String?) {
        signInFor = extension
        signInIntro.text = if (extension == null) {
            "<html><body style='width:400px'>Use the username and password from your administrator. " +
                "This PC keeps them, sealed to your Windows account, so messages keep working.</body></html>"
        } else {
            "<html><body style='width:400px'>Line $extension was set up from a link. Sign in so its messages " +
                "keep working after the link expires. Calls already work.</body></html>"
        }
        signInError.text = " "
        signInPassword.text = ""
        showRoot("signin")
        signInUser.requestFocusInWindow()
    }

    private fun signInPage(): JPanel {
        val page = column()
        page.border = javax.swing.border.EmptyBorder(0, 0, 16, 0)
        page.add(banner("Sign", "in.", compact = true, onBack = { leaveSetup() }))
        val body = column()
        signInIntro.foreground = muted
        body.add(pin(signInIntro))
        body.add(Box.createVerticalStrut(10))
        body.add(pin(label("Username")))
        body.add(pin(signInUser))
        body.add(pin(label("Password")))
        body.add(pin(signInPassword))
        body.add(pin(signInError))
        val submit = primary("Sign in") { submitSignIn() }
        signInPassword.addActionListener { submitSignIn() }
        body.add(pin(submit))
        body.add(Box.createVerticalStrut(8))
        body.add(footer("Back", profile.footerCaption) { leaveSetup() })
        page.add(body)
        return page
    }

    private fun submitSignIn() {
        val user = signInUser.text.trim()
        val password = String(signInPassword.password)
        if (user.isBlank() || password.isEmpty()) {
            signInError.text = "Enter your username and password"
            return
        }
        signInError.text = " "
        val target = signInFor
        io.submit {
            try {
                if (target == null) {
                    val added = session.signIn(user, password)
                    note("Signed in. Line ${added.extension} added")
                    askedRegister = true
                    session.register(added, userAgent = "${profile.userAgentName}/0.1.0 PJSUA")
                    SwingUtilities.invokeLater {
                        signInPassword.text = ""
                        paintedLines = ""
                        showRoot("app")
                        refreshLine()
                        showTab("calls")
                    }
                } else {
                    session.attachSignIn(target, user, password)
                    note("Messages on $target now use your sign-in")
                    SwingUtilities.invokeLater {
                        signInPassword.text = ""
                        paintedLines = ""
                        refreshLine()
                        showRoot("app")
                        showTab("lines")
                    }
                }
            } catch (err: Exception) {
                val shown = when ((err as? BffException)?.httpCode) {
                    401 -> "Username or password is not right"
                    else -> err.message ?: err.javaClass.simpleName
                }
                SwingUtilities.invokeLater { signInError.text = shown }
            }
        }
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
        shell.border = BorderFactory.createMatteBorder(1, 0, 0, 0, tokens.divider)
        val body = JPanel(BorderLayout())
        body.background = ground
        body.add(nav(), BorderLayout.WEST)
        appBody.background = ground
        appBody.add(calls(), "calls")
        appBody.add(messages(), "messages")
        appBody.add(thread(), "thread")
        appBody.add(lines(), "lines")
        appBody.add(settings(), "settings")
        body.add(appBody, BorderLayout.CENTER)
        shell.add(body, BorderLayout.CENTER)
        bottomNav = net.ithandsfree.softphone.win.ui.BottomNav(tokens, navItems()) { id -> pickTab(id) }
        bottomNav.isVisible = false
        shell.add(bottomNav, BorderLayout.SOUTH)
        showTab("calls")
        return shell
    }

    /** Round-3 title bar, embedded in the native one by FlatLaf. Search and line status live here. */
    private fun installTitleBar() {
        findBox.toolTipText = "Search contacts and messages, or type a number (Ctrl K)"
        findBox.addActionListener { searchOrDial() }
        findBox.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = reloadRecents()
        })
        // Vector brand mark from the design package: sharp at every size and DPI (the PNG app icon has a rim
        // and pixelates at 18 px).
        val mark = brandMark(21)
        titleBar = net.ithandsfree.softphone.win.ui.TitleBar(tokens, profile.productName, mark, findBox)
        jMenuBar = titleBar
        titleBar.onPin = { setPinned(!WindowPrefs.pinned()) }
        titleBar.onExpand = { expandWindow() }
        titleBar.searchKey(KeymapStore.current().chord(Shortcut.SEARCH).label())
        rootPane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_TITLE, false)
        rootPane.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_SHOW_ICON, false)
    }

    /** Vector brand mark [height] px tall (title bar, incoming window). */
    private fun brandMark(height: Int): javax.swing.Icon? = runCatching {
        if (profile.id == Distribution.IHF) {
            com.formdev.flatlaf.extras.FlatSVGIcon("brand/ihf-emblem.svg", height * 62 / 52, height)
        } else {
            com.formdev.flatlaf.extras.FlatSVGIcon("brand/community-mark.svg", height * 120 / 112, height)
        }
    }.getOrNull()

    private var titleKey = ""

    /** Repaints the title bar status only when lines, registration, DND or the call timer changed. */
    private fun paintTitleStatus(snap: SipBridge.Snapshot, timer: String?) {
        val lines = session.lines().mapIndexed { index, line ->
            net.ithandsfree.softphone.win.ui.TitleBar.LineStatus(
                line.extension,
                tokens.line(index),
                if (index == 0) snap.registered else snap.secondRegistered,
            )
        }
        val missed = (if (currentTab == "calls") 0 else missedSinceSeen()) + vmNew.values.sum()
        val key = "$lines|${WindowPrefs.dnd()}|$timer|$missed"
        if (key == titleKey) return
        titleKey = key
        titleBar.show(lines, WindowPrefs.dnd(), timer)
        if (::navRail.isInitialized) {
            navRail.presence(
                when {
                    timer != null -> tokens.action
                    WindowPrefs.dnd() -> tokens.danger
                    lines.any { it.registered } -> tokens.answer
                    else -> tokens.textDisabled
                },
            )
            navRail.badge("calls", missed, danger = true)
        }
        if (::bottomNav.isInitialized) bottomNav.badge("calls", missed, danger = true)
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
        callsPage = page
        return page
    }

    private fun recentsColumn(): JPanel {
        val col = JPanel(BorderLayout())
        col.background = ground
        col.preferredSize = Dimension(net.ithandsfree.softphone.win.ui.Space.LIST_W, 200)
        col.minimumSize = Dimension(320, 200)
        col.border = BorderFactory.createMatteBorder(0, 0, 0, 1, tokens.divider)
        val head = JPanel()
        head.layout = BoxLayout(head, BoxLayout.Y_AXIS)
        head.isOpaque = false
        head.border = EmptyBorder(14, 16, 6, 16)
        fun left(c: JComponent) = c.also { it.alignmentX = Component.LEFT_ALIGNMENT }
        head.add(left(callsTitle.apply {
            font = net.ithandsfree.softphone.win.ui.Type.display(net.ithandsfree.softphone.win.ui.Type.TITLE)
            foreground = ink
            border = EmptyBorder(0, 0, 10, 0)
        }))
        head.add(left(lineRail))
        activeCard.alignmentX = Component.LEFT_ALIGNMENT
        head.add(Box.createVerticalStrut(8))
        head.add(activeCard)
        keypadJump.addActionListener {
            narrowList = false
            applyWindowShape()
        }
        callsTabs = net.ithandsfree.softphone.win.ui.UnderlineTabs(
            tokens,
            listOf("keypad" to "Keypad", "recents" to "Recents", "contacts" to "Contacts", "voicemail" to "Voicemail"),
        ) { id ->
            if (id == "keypad") {
                // Compact window only: the keypad replaces the list.
                if (detailOpen) closeRecentDetail()
                narrowList = false
                applyWindowShape()
            } else {
                narrowList = true
                showCallsView(id)
                applyWindowShape()
            }
        }
        callsTabs.only(listOf("recents", "contacts", "voicemail"))
        callsTabs.maximumSize = Dimension(Int.MAX_VALUE, 44)
        head.add(Box.createVerticalStrut(8))
        head.add(left(callsTabs))
        historyFilters.layout = net.ithandsfree.softphone.win.ui.WrapLayout(java.awt.FlowLayout.LEFT, 8, 6)
        historyFilters.isOpaque = false
        historyFilters.border = EmptyBorder(6, 0, 0, 0)
        historyFilters.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        head.add(left(historyFilters))
        col.add(head, BorderLayout.NORTH)
        recentRows.layout = BoxLayout(recentRows, BoxLayout.Y_AXIS)
        recentRows.background = ground
        val scroll = JScrollPane(recentRows)
        scroll.border = EmptyBorder(0, 0, 0, 0)
        scroll.background = ground
        scroll.viewport.background = ground
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.verticalScrollBar.unitIncrement = 16
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
        val voicemail = voicemailView()
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
        dialPad = net.ithandsfree.softphone.win.ui.DialPad(
            tokens,
            dial,
            onKey = { digit ->
                KeyTone.play(digit)
                dial.text += digit
                dial.requestFocusInWindow()
            },
            onCall = { placeCall() },
            onErase = { eraseDialDigit() },
        )
        dial.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private var updating = false
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = restyleDial()
            private fun restyleDial() {
                if (updating) return
                val raw = dial.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
                val shown = formatDialDigits(raw)
                // 36 px design size for a normal number, stepping down for long pastes (52/40/28 in PhoneRules).
                val size = dialTypeSize(raw) * (if (compactMode) 30f else 36f) / 52f
                dial.font = net.ithandsfree.softphone.win.ui.Type.mono(size)
                dialPad.eraseVisible(raw.isNotEmpty())
                val match = if (raw.length >= 3) ContactBook.match(raw).firstOrNull() else null
                dialPad.contact(match?.name, match?.let { displayNumber(it.number) })
                if (dial.text != shown) {
                    SwingUtilities.invokeLater {
                        updating = true
                        dial.text = shown
                        updating = false
                    }
                }
            }
        })
        dial.addActionListener { placeCall() }
        dialPad.eraseVisible(false)
        val box = JPanel(BorderLayout())
        box.background = ground
        // Scrolls only when the window is shorter than the pad; otherwise the glue keeps it centred.
        val scroll = JScrollPane(dialPad)
        scroll.border = EmptyBorder(0, 0, 0, 0)
        scroll.viewport.background = ground
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.verticalScrollBar.unitIncrement = 16
        box.add(scroll, BorderLayout.CENTER)
        callButton.isVisible = false
        return box
    }

    private fun inCallColumn(): JPanel {
        val pad = net.ithandsfree.softphone.win.ui.padGrid(tokens) { digit ->
            KeyTone.play(digit)
            sendDtmf(digit)
        }
        val dtmfSlot = JPanel(BorderLayout())
        dtmfSlot.isOpaque = false
        dtmfSlot.add(pad, BorderLayout.CENTER)
        dtmfSlot.maximumSize = Dimension(240, 264)
        dtmfSlot.isVisible = false
        dtmfHolder = dtmfSlot
        headsetOffer = ghost("Use headset", expand = false) { useOfferedHeadset() }
        headsetOffer.isVisible = false
        inCallPane = net.ithandsfree.softphone.win.ui.InCallPane(tokens, object : net.ithandsfree.softphone.win.ui.InCallPane.Actions {
            override fun answer() = work("Answer") { session.answer(); note("Answered") }
            override fun decline() = work("Decline") {
                if (SipBridge.snapshot().incoming) session.decline() else session.hangup()
            }
            override fun mute() = toggleMute()
            override fun hold() = toggleHold()
            override fun transfer() = openTransfer()
            override fun keypad() = toggleDtmfPad()
            override fun end() = work("Hang up") { session.hangup() }
            override fun device(anchor: JComponent) = pickCallDevice(anchor)
        }, dtmfSlot)
        val box = JPanel(BorderLayout())
        box.background = ground
        callWaitingBar = net.ithandsfree.softphone.win.ui.CallWaitingBar(
            tokens,
            onAnswer = { answerWaiting() },
            onEnd = { work("Second call") { session.waitingEnd() } },
            onSwap = { work("Swap") { session.swapCalls(); note("Swapped calls") } },
        )
        box.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(14, 18, 0, 18)
            add(callWaitingBar, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        box.add(inCallPane, BorderLayout.CENTER)
        val offer = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER))
        offer.isOpaque = false
        offer.add(headsetOffer)
        box.add(offer, BorderLayout.SOUTH)
        return box
    }

    /** Speaker list under the device row; switching never drops the call (previous pair restored on failure). */
    private fun pickCallDevice(anchor: JComponent) {
        if (speakerModel.size == 0) loadAudioDevices()
        val menu = javax.swing.JPopupMenu()
        for (i in 0 until speakerModel.size) {
            val device = speakerModel.getElementAt(i)
            val shown = if (device.name.contains("wave mapper", ignoreCase = true)) "Windows default" else device.name
            val item = javax.swing.JCheckBoxMenuItem(shown, device == speakerModel.selectedItem)
            item.font = net.ithandsfree.softphone.win.ui.Type.ui(net.ithandsfree.softphone.win.ui.Type.LABEL + 1)
            item.addActionListener {
                speakerModel.selectedItem = device
                useSelectedSpeaker()
            }
            menu.add(item)
        }
        if (speakerModel.size == 0) menu.add(javax.swing.JMenuItem("No sound devices found").apply { isEnabled = false })
        menu.addSeparator()
        menu.add(javax.swing.JMenuItem("Call volume").apply { isEnabled = false })
        CALL_VOLUMES.forEach { (percent, label) ->
            val item = javax.swing.JCheckBoxMenuItem(label, AudioPrefs.callVolume() == percent)
            item.font = net.ithandsfree.softphone.win.ui.Type.ui(net.ithandsfree.softphone.win.ui.Type.LABEL + 1)
            item.addActionListener { setCallVolume(percent) }
            menu.add(item)
        }
        menu.show(anchor, 0, -menu.preferredSize.height - 4)
    }

    /** Settings › Advanced: zip the diagnostic logs with a short summary (no passwords or tokens) for support. */
    private fun exportDiagnostics() {
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "Save diagnostic log"
        chooser.selectedFile = java.io.File(
            System.getProperty("user.home"),
            "Desktop${java.io.File.separator}IHF-Phone-log-${java.time.LocalDate.now()}.zip",
        )
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.let { if (it.name.endsWith(".zip", true)) it else java.io.File(it.path + ".zip") }
        val snap = SipBridge.snapshot()
        val summary = buildString {
            appendLine("IHF Phone $APP_BUILD (${profile.productName})")
            appendLine("Saved ${java.time.ZonedDateTime.now()}")
            appendLine("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            appendLine("Java: ${System.getProperty("java.version")}")
            appendLine("Voice engine: ${if (SipBridge.loadError == null) "loaded" else "not loaded"}; transport ${snap.transport?.name ?: "none"}")
            session.lines().forEachIndexed { index, line ->
                val up = if (index == 0) snap.registered else snap.secondRegistered
                appendLine("Line ${index + 1}: ${line.extension} ${if (up) "registered" else "not registered"}${if (line.extension == session.line?.extension) " (default)" else ""}")
            }
            appendLine("Microphone: ${AudioPrefs.captureName().ifBlank { "Windows default" }}")
            appendLine("Speaker: ${AudioPrefs.playbackName().ifBlank { "Windows default" }}")
            appendLine("Call volume: ${AudioPrefs.callVolume()}%; voice processing ${if (AudioPrefs.voiceProcessing()) "on" else "off"}")
            appendLine("Do not disturb on this PC: ${WindowPrefs.dnd()}")
        }
        work("Save diagnostic log") {
            val files = DiagLog.export(target, summary)
            note("Saved $files day${if (files == 1) "" else "s"} of log to ${target.name}")
        }
    }

    /** Saves the call volume and applies it to the call in progress (Settings and the in-call device menu). */
    private fun setCallVolume(percent: Int) {
        AudioPrefs.saveCallVolume(percent)
        SipBridge.setCallVolume(percent)
        val index = CALL_VOLUMES.indexOfFirst { it.first == percent }
        callVolumeCombo?.let { if (index >= 0 && it.selectedIndex != index) it.selectedIndex = index }
        note("Call volume: ${CALL_VOLUMES.firstOrNull { it.first == percent }?.second ?: "$percent%"}")
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

    // ---- Voicemail (Calls › Voicemail) -----------------------------------------------------------------------------

    /** Voicemail tab: the PBX mailbox of each line through the BFF, with UCP's Voicemail permissions. */
    private fun voicemailView(): JComponent {
        voicemailPane = net.ithandsfree.softphone.win.ui.VoicemailPane(tokens, object : net.ithandsfree.softphone.win.ui.VoicemailPane.Actions {
            override fun filter(extension: String?) {
                vmFilter = extension
                paintVoicemail()
            }
            override fun open(id: String) {
                vmOpen = id
                paintVoicemail()
            }
            override fun playPause(id: String) = playVoicemail(id)
            override fun seek(id: String, fraction: Double) {
                if (recordingPlayer.playing == "vm:$id") recordingPlayer.seek(fraction)
            }
            override fun callBack(id: String) {
                val (line, m) = voicemailFor(id) ?: return
                session.choose(line.extension)
                dial.text = m.number
                placeCall()
            }
            override fun text(id: String) {
                val (line, m) = voicemailFor(id) ?: return
                session.choose(line.extension)
                startNewMessage()
                smsTo.text = m.number
            }
            override fun save(id: String) = saveVoicemail(id)
            override fun delete(id: String) = deleteVoicemail(id)
            override fun callVoicemail(extension: String?) {
                val ext = extension ?: session.line?.extension ?: return
                session.choose(ext)
                dial.text = voicemailDialCode(ext)
                placeCall()
            }
        })
        return voicemailPane
    }

    /** The PBX's own "My Voicemail" code for the line; FreePBX's default when the BFF has not said. */
    private fun voicemailDialCode(ext: String?): String =
        vmData[ext.orEmpty()]?.dial?.ifBlank { null }
            ?: vmData.values.firstOrNull { it.dial.isNotBlank() }?.dial
            ?: "*97"

    /** "ext/id" → the line and message. Ids are only unique within one mailbox. */
    private fun voicemailFor(key: String): Pair<EnrolledLine, Voicemail>? {
        val ext = key.substringBefore('/')
        val id = key.substringAfter('/')
        val line = session.lines().firstOrNull { it.extension == ext } ?: return null
        val message = vmData[ext]?.messages?.firstOrNull { it.id == id } ?: return null
        return line to message
    }

    /** Reloads every line's mailbox. [quiet] skips the loading state (the 60 s refresh). */
    private fun loadVoicemail(quiet: Boolean = false) {
        val lines = session.lines()
        if (lines.isEmpty()) return
        if (!quiet && vmData.isEmpty()) {
            vmLoading = true
            paintVoicemail()
        }
        io.submit {
            lines.forEach { line ->
                val label = lineLabel(line.extension)
                try {
                    vmData[line.extension] = session.voicemail(line)
                    vmNotice.remove(line.extension)
                } catch (err: SignInNeeded) {
                    vmNotice[line.extension] = err.message.orEmpty()
                } catch (err: BffException) {
                    vmData.remove(line.extension)
                    vmNotice[line.extension] = when {
                        err.error == "voicemail_disabled" ->
                            "Voicemail for $label is not turned on for your user (UCP › Voicemail). Ask your administrator."
                        err.httpCode == 404 -> "This PBX does not offer the voicemail list yet. You can still dial your mailbox."
                        else -> "Could not load voicemail for $label (${err.error})."
                    }
                } catch (err: Exception) {
                    vmNotice[line.extension] = "Could not load voicemail for $label."
                    DiagLog.app("voicemail $label: ${err.message}")
                }
            }
            vmLoading = false
            SwingUtilities.invokeLater {
                announceNewVoicemail()
                paintVoicemail()
            }
        }
    }

    /** New-message counts: tab text, Calls badge, and a toast when one arrives (not on the first load). */
    private fun announceNewVoicemail() {
        session.lines().forEach { line ->
            val messages = vmData[line.extension]?.messages ?: return@forEach
            val fresh = messages.count { it.new }
            val before = vmNew[line.extension]
            if (before != null && fresh > before) {
                val newest = messages.filter { it.new }.maxByOrNull { it.at }
                val who = newest?.let { m -> ContactBook.nameFor(m.number)?.name ?: callerName(m.name) ?: displayNumber(m.number) }
                note("New voicemail on ${lineLabel(line.extension)}" + (who?.let { " from $it" } ?: ""))
            }
            vmNew[line.extension] = fresh
        }
        val total = vmNew.values.sum()
        if (::callsTabs.isInitialized) callsTabs.rename("voicemail", if (total > 0) "Voicemail · $total" else "Voicemail")
        titleKey = ""
    }

    private fun paintVoicemail() {
        if (!::voicemailPane.isInitialized) return
        val lines = session.lines()
        val rows = lines.flatMapIndexed { index, line ->
            val label = lineLabel(line.extension)
            val caps = lineCaps[line.extension]
            vmData[line.extension]?.messages.orEmpty().map { m ->
                val person = ContactBook.nameFor(m.number)?.name
                val callable = recentCanCall(m.number)
                val title = person ?: callerName(m.name) ?: if (callable) displayNumber(m.number) else "Unknown caller"
                val block = recentTextBlock(m.number) ?: if (caps?.sms == false) "Text is off on $label (admin)." else null
                Triple(m.new, m.at, net.ithandsfree.softphone.win.ui.VoicemailPane.Item(
                    id = "${line.extension}/${m.id}",
                    extension = line.extension,
                    lineLabel = label,
                    lineColorIndex = index,
                    title = title,
                    titleIsNumber = person == null && callerName(m.name) == null && callable,
                    whenText = formatVoicemailWhen(m.at * 1000),
                    durationText = formatSeconds(m.duration),
                    isNew = m.new,
                    textBlock = block,
                    canCall = callable,
                ))
            }
        }.sortedWith(compareByDescending<Triple<Boolean, Long, net.ithandsfree.softphone.win.ui.VoicemailPane.Item>> { it.first }.thenByDescending { it.second })
            .map { it.third }
        val mailboxExt = vmFilter ?: session.line?.extension
        voicemailPane.show(
            net.ithandsfree.softphone.win.ui.VoicemailPane.Model(
                items = rows,
                filters = listOf(Triple<String?, String, Int>(null, "All lines", -1)) +
                    lines.mapIndexed { index, line -> Triple<String?, String, Int>(line.extension, lineLabel(line.extension), index) },
                filter = vmFilter,
                open = vmOpen,
                notice = vmNotice.values.distinct().joinToString("<br>").ifBlank { null },
                loading = vmLoading,
                mailboxLabel = mailboxExt?.let { lineLabel(it) },
                dialCode = voicemailDialCode(mailboxExt),
            ),
        )
    }

    private fun playVoicemail(key: String) {
        val playKey = "vm:$key"
        if (recordingPlayer.playing == playKey) {
            if (recordingPlayer.paused) recordingPlayer.resume() else recordingPlayer.pause()
            startVoicemailProgress()
            return
        }
        val snap = SipBridge.snapshot()
        if (snap.callActive || snap.incoming) {
            note("Finish the call to play voicemail")
            return
        }
        val (line, message) = voicemailFor(key) ?: return
        vmOpen = key
        work("Voicemail") {
            val audio = synchronized(vmAudio) { vmAudio[key] } ?: session.voicemailAudio(line, message.id).also { bytes ->
                synchronized(vmAudio) {
                    vmAudio[key] = bytes
                    while (vmAudio.size > 6) vmAudio.remove(vmAudio.keys.first())
                }
            }
            SwingUtilities.invokeLater {
                recordingPlayer.play(playKey, audio, AudioPrefs.playbackName()) {
                    SwingUtilities.invokeLater { voicemailPane.progress(key, 0, 0, false) }
                }
                startVoicemailProgress()
            }
            if (message.new) {
                session.voicemailHeard(line, message.id)
                loadVoicemail(quiet = true)
            }
        }
    }

    /** Moves the open player's scrubber while a voicemail plays. */
    private fun startVoicemailProgress() {
        if (vmProgress?.isRunning == true) return
        vmProgress = Timer(200) {
            val key = recordingPlayer.playing?.takeIf { it.startsWith("vm:") }?.removePrefix("vm:")
            val p = recordingPlayer.progress()
            if (key == null || p == null) {
                vmProgress?.stop()
                vmOpen?.let { voicemailPane.progress(it, 0, 0, false) }
            } else {
                voicemailPane.progress(key, p.first, p.second, !recordingPlayer.paused)
            }
        }.apply { start() }
    }

    private fun saveVoicemail(key: String) {
        val (line, message) = voicemailFor(key) ?: return
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "Save voicemail"
        val stamp = java.time.Instant.ofEpochSecond(message.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val who = message.number.filter { it.isLetterOrDigit() }.ifBlank { "unknown" }
        chooser.selectedFile = java.io.File("Voicemail $who $stamp.wav")
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.let { if (it.name.endsWith(".wav", true)) it else java.io.File(it.path + ".wav") }
        work("Save voicemail") {
            target.writeBytes(session.voicemailAudio(line, message.id, download = true))
            note("Saved ${target.name}")
        }
    }

    private fun deleteVoicemail(key: String) {
        val (line, message) = voicemailFor(key) ?: return
        // One delete per message: a quick second press must not send a second request.
        if (!vmDeleting.add(key)) return
        if (recordingPlayer.playing == "vm:$key") recordingPlayer.stop()
        work("Delete voicemail") {
            try {
                session.deleteVoicemail(line, message.id)
            } finally {
                vmDeleting.remove(key)
            }
            synchronized(vmAudio) { vmAudio.remove(key) }
            vmData[line.extension]?.let { r -> vmData[line.extension] = r.copy(messages = r.messages.filter { it.id != message.id }) }
            SwingUtilities.invokeLater {
                if (vmOpen == key) vmOpen = null
                paintVoicemail()
            }
            note("Voicemail deleted")
            loadVoicemail(quiet = true)
        }
    }

    private fun showCallsView(name: String) {
        callsView = name
        (callsBody.layout as java.awt.CardLayout).show(callsBody, name)
        historyFilters.isVisible = name == "recents"
        styleCallTabs()
        if (name == "contacts") reloadContacts()
        if (name == "voicemail") loadVoicemail()
    }

    private fun styleCallTabs() {
        if (::callsTabs.isInitialized) callsTabs.select(callsView)
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
        historyFilters.components.filterIsInstance<net.ithandsfree.softphone.win.ui.Chip>().forEach { chip ->
            val token = chip.getClientProperty("history") as? String
            chip.selected2 = when (token) {
                "missed" -> missedOnly
                "all" -> !missedOnly && historyExtension == null
                else -> !missedOnly && historyExtension == token
            }
        }
    }

    /** Name shown for a line: its extension ("9333"), unless the owner gave it a custom name on the Lines page. */
    private fun lineLabel(extension: String?): String {
        val lines = session.lines()
        val index = lines.indexOfFirst { it.extension == extension }
        if (index < 0) return extension ?: "this line"
        return lines[index].label.ifBlank { lines[index].extension }
    }

    private fun lineIndex(extension: String?): Int? =
        session.lines().indexOfFirst { it.extension == extension }.takeIf { it >= 0 }

    /** Line rail, Ctrl 1 / Ctrl 2: the line new calls and messages use. */
    private fun pickLine(extension: String) {
        if (session.line?.extension == extension) return
        session.choose(extension)
        val shown = lineLabel(extension)
        note(if (shown == extension) "Calls and messages use $extension" else "Calls and messages use $shown ($extension)")
        threadSignature = ""
        refreshThreads()
        paintLineControls(SipBridge.snapshot())
    }

    private fun threadView(row: ThreadInfo): net.ithandsfree.softphone.win.ui.ThreadView {
        val person = ContactBook.nameFor(row.peer)
        val title = person?.name ?: displayNumber(row.peer).ifBlank { "Unknown" }
        val snippet = row.snippet.orEmpty().trim()
        val photoOnly = snippet.isBlank() || snippet.equals("photo", ignoreCase = true) || snippet.startsWith("[media")
        return net.ithandsfree.softphone.win.ui.ThreadView(
            title = title,
            titleIsNumber = person == null,
            preview = if (photoOnly) "Photo" else snippet.replace('\n', ' ').take(90),
            previewIcon = if (photoOnly) "image" else null,
            whenText = epochMsOf(row.lastMessageAt)?.let { formatRecentWhen(it) }.orEmpty(),
            unread = row.unread,
        )
    }

    private fun messages(): JPanel {
        val t = tokens
        threadList.fixedCellHeight = 68
        threadList.background = ground
        threadList.border = EmptyBorder(0, 0, 0, 0)
        threadList.cellRenderer = net.ithandsfree.softphone.win.ui.ThreadCell<ThreadInfo>(t) { threadView(it) }
        threadList.getAccessibleContext().accessibleName = "Conversations"

        val listCol = JPanel(BorderLayout())
        listCol.background = ground
        listCol.preferredSize = Dimension(340, 200)
        listCol.minimumSize = Dimension(280, 200)
        listCol.border = BorderFactory.createMatteBorder(0, 0, 0, 1, t.divider)
        val head = JPanel()
        head.layout = BoxLayout(head, BoxLayout.Y_AXIS)
        head.isOpaque = false
        head.border = EmptyBorder(14, 16, 10, 16)
        fun left(c: JComponent) = c.also { it.alignmentX = Component.LEFT_ALIGNMENT }
        val titleRow = JPanel(BorderLayout())
        titleRow.isOpaque = false
        titleRow.add(JLabel("Messages").apply {
            font = net.ithandsfree.softphone.win.ui.Type.display(net.ithandsfree.softphone.win.ui.Type.TITLE)
            foreground = ink
        }, BorderLayout.WEST)
        val compose = net.ithandsfree.softphone.win.ui.IconButton(t, "compose", 40, ink, "New message (Ctrl N)") { startNewMessage() }
        titleRow.add(compose, BorderLayout.EAST)
        titleRow.maximumSize = Dimension(Int.MAX_VALUE, 48)
        head.add(left(titleRow))
        head.add(Box.createVerticalStrut(10))
        head.add(left(messagesLineRail))
        head.add(Box.createVerticalStrut(12))
        threadSearch.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_LEADING_ICON,
            net.ithandsfree.softphone.win.ui.Icons.get("search", 16, t.textMuted))
        threadSearch.font = net.ithandsfree.softphone.win.ui.Type.ui(net.ithandsfree.softphone.win.ui.Type.LABEL + 1)
        threadSearch.maximumSize = Dimension(Int.MAX_VALUE, 38)
        threadSearch.preferredSize = Dimension(300, 38)
        threadSearch.getAccessibleContext().accessibleName = "Search conversations"
        threadSearch.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filterThreads()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filterThreads()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = filterThreads()
        })
        head.add(left(threadSearch))
        listCol.add(head, BorderLayout.NORTH)
        listCol.add(JScrollPane(threadList).apply {
            border = null
            viewport.background = ground
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        convoHeader = net.ithandsfree.softphone.win.ui.ConversationHeader(t, onCall = { callThreadPeer() }, onContact = {
            val number = smsTo.text.trim()
            if (number.isNotBlank() && ContactBook.nameFor(number) == null) saveContact(number)
        })
        newMessageRow.isOpaque = false
        newMessageRow.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, t.divider),
            EmptyBorder(14, 20, 14, 20),
        )
        newMessageRow.add(JLabel("To").apply {
            font = net.ithandsfree.softphone.win.ui.Type.semibold(net.ithandsfree.softphone.win.ui.Type.BODY)
            foreground = muted
        }, BorderLayout.WEST)
        smsTo.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Name or number")
        smsTo.font = net.ithandsfree.softphone.win.ui.Type.mono(net.ithandsfree.softphone.win.ui.Type.BODY)
        smsTo.getAccessibleContext().accessibleName = "To"
        newMessageRow.add(smsTo, BorderLayout.CENTER)
        newMessageRow.isVisible = false
        viaBar = net.ithandsfree.softphone.win.ui.ViaBar(t)
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.isOpaque = false
        listOf(convoHeader, newMessageRow, viaBar).forEach { left(it); top.add(it) }

        transcript.border = EmptyBorder(16, 24, 16, 24)
        val transcriptScroll = JScrollPane(transcript).apply {
            border = null
            viewport.background = ground
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        this.transcriptScroll = transcriptScroll

        smsBody.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "send-text")
        smsBody.inputMap.put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        smsBody.actionMap.put("send-text", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = sendText()
        })
        smsBody.transferHandler = photoTransfer()
        val composer = JPanel()
        composer.layout = BoxLayout(composer, BoxLayout.Y_AXIS)
        composer.background = ground
        composer.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, t.divider),
            EmptyBorder(12, 20, 16, 20),
        )
        composer.transferHandler = photoTransfer()
        attachCard = net.ithandsfree.softphone.win.ui.AttachmentCard(t) { clearPhoto() }
        composer.add(left(attachCard))
        composer.add(Box.createVerticalStrut(10))
        val fromRow = JPanel(BorderLayout())
        fromRow.isOpaque = false
        fromRow.maximumSize = Dimension(Int.MAX_VALUE, 26)
        val fromLeft = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        fromLeft.isOpaque = false
        fromLeft.add(composerFromDot)
        composerFrom.font = net.ithandsfree.softphone.win.ui.Type.medium(net.ithandsfree.softphone.win.ui.Type.LABEL)
        composerFrom.foreground = muted
        fromLeft.add(composerFrom)
        fromRow.add(fromLeft, BorderLayout.WEST)
        fromRow.add(net.ithandsfree.softphone.win.ui.hintRow(t,
            net.ithandsfree.softphone.win.ui.Kbd(t, "Enter"), "send ·",
            net.ithandsfree.softphone.win.ui.Kbd(t, "Shift Enter"), "new line · drop or paste images"), BorderLayout.EAST)
        composer.add(left(fromRow))
        composer.add(Box.createVerticalStrut(8))
        val inputRow = JPanel(BorderLayout(10, 0))
        inputRow.isOpaque = false
        val attach = net.ithandsfree.softphone.win.ui.IconButton(t, "clip", 40, muted, "Attach a photo") { choosePhoto() }
        attach.style("arc: 10; borderWidth: 0; background: ${net.ithandsfree.softphone.win.ui.css(t.ground)}; hoverBackground: ${net.ithandsfree.softphone.win.ui.css(t.raised)}")
        val send = net.ithandsfree.softphone.win.ui.IconButton(t, "send", 48, t.onAction, "Send (Enter)", filled = t.action) { sendText() }
        inputRow.add(JPanel(BorderLayout()).apply { isOpaque = false; add(attach, BorderLayout.SOUTH) }, BorderLayout.WEST)
        inputRow.add(net.ithandsfree.softphone.win.ui.ComposerBox(t, smsBody), BorderLayout.CENTER)
        inputRow.add(JPanel(BorderLayout()).apply { isOpaque = false; add(send, BorderLayout.SOUTH) }, BorderLayout.EAST)
        composer.add(left(inputRow))

        val detail = JPanel(BorderLayout())
        detail.background = ground
        detail.add(top, BorderLayout.NORTH)
        detail.add(transcriptScroll, BorderLayout.CENTER)
        detail.add(composer, BorderLayout.SOUTH)
        showConversationFor(null)

        val page = JPanel(BorderLayout())
        page.background = ground
        page.add(listCol, BorderLayout.WEST)
        page.add(detail, BorderLayout.CENTER)
        return page
    }

    /** Header, via-strip and composer line for an open conversation, or the "To" row for a new message. */
    private fun showConversationFor(peer: String?) {
        val line = session.line
        val label = lineLabel(line?.extension)
        val index = lineIndex(line?.extension) ?: 0
        val lineNumber = line?.did?.takeIf { it.isNotBlank() }?.let { displayNumber(it) }
        composerFromDot.color = tokens.line(index)
        composerFrom.text = "From $label" + (lineNumber?.let { " · $it" } ?: "")
        smsBody.getAccessibleContext().accessibleDescription = "Message from $label"
        viaBar.show(label, lineNumber, index)
        if (peer.isNullOrBlank()) {
            convoHeader.isVisible = false
            newMessageRow.isVisible = true
            viaBar.isVisible = line != null
            return
        }
        val person = ContactBook.nameFor(peer)
        val shown = displayNumber(peer)
        convoHeader.show(
            title = person?.name ?: shown,
            isNumber = person == null,
            subText = if (person != null) shown else "",
            callTip = "Call from $label (Ctrl Enter)",
        )
        convoHeader.contact.isVisible = person == null
        convoHeader.contact.toolTipText = "Add to contacts"
        convoHeader.isVisible = true
        newMessageRow.isVisible = false
        viaBar.isVisible = true
    }

    private fun callThreadPeer() {
        val number = smsTo.text.trim()
        if (number.isBlank()) return
        dial.text = number
        showTab("calls")
        placeCall()
    }

    /** Applies the thread search box to the loaded threads. */
    private fun filterThreads() {
        val query = threadSearch.text.trim()
        val keep = threadList.selectedValue?.peer
        suppressThreadOpen = true
        threadModel.clear()
        allThreads.filter { row ->
            query.isBlank() || row.peer.contains(query.filter { it.isDigit() }.ifBlank { "\u0000" }) ||
                threadView(row).title.contains(query, ignoreCase = true) ||
                row.snippet.orEmpty().contains(query, ignoreCase = true)
        }.forEach { threadModel.addElement(it) }
        val index = (0 until threadModel.size()).firstOrNull { threadModel[it].peer == keep } ?: -1
        if (index >= 0) threadList.selectedIndex = index
        suppressThreadOpen = false
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
        linesPage = net.ithandsfree.softphone.win.ui.LinesPage(tokens, object : net.ithandsfree.softphone.win.ui.LinesPage.Actions {
            override fun select(extension: String) {
                linesSelected = extension
                refreshLinesPage()
            }
            override fun makeDefault(extension: String) = pickLine(extension)
            override fun setDnd(extension: String, on: Boolean) {
                val line = session.lines().firstOrNull { it.extension == extension } ?: return
                lineDnd[extension] = on
                refreshLinesPage()
                work("Do not disturb") {
                    session.setPbxDnd(line, on)
                    note(if (on) "${lineLabel(extension)} is on Do not disturb" else "${lineLabel(extension)} is available")
                    loadLineFacts(extension)
                }
            }
            override fun rename(extension: String, label: String) {
                session.rename(extension, label)
                note("Line $extension is now ${lineLabel(extension)}")
                paintedLines = ""
                convoLineKey = ""
                reloadRecents()
                paintLineControls(SipBridge.snapshot())
                refreshLinesPage()
            }
            override fun ringtone(extension: String, id: String?) {
                AudioPrefs.saveLineRingtone(extension, id)
                refreshLinesPage()
            }
            override fun playRingtone(extension: String) {
                val snap = SipBridge.snapshot()
                if (snap.callActive || snap.incoming) return
                CallRinger.stop()
                CallRinger.lineExtension = extension
                CallRinger.start()
                Timer(2400) { CallRinger.stop() }.apply { isRepeats = false; start() }
            }
            override fun signIn(extension: String) = openSignIn(extension)
            override fun signOut(extension: String) {
                session.signOut(extension)
                note("Signed out of ${lineLabel(extension)}. Calls keep working")
                refreshLinesPage()
            }
            override fun setUpAgain(extension: String) {
                note("Open the setup email for ${lineLabel(extension)}, or sign in")
                showRoot("welcome")
            }
            override fun remove(extension: String) {
                val label = lineLabel(extension)
                work("Remove line") {
                    session.removeLine(extension, "${profile.userAgentName}/0.1.0 PJSUA")
                    lineDnd.remove(extension)
                    lineCaps.remove(extension)
                    SwingUtilities.invokeLater {
                        linesSelected = session.line?.extension
                        paintedLines = ""
                        convoLineKey = ""
                        titleKey = ""
                        reloadRecents()
                        refreshThreads()
                        if (session.lines().isEmpty()) showRoot("welcome") else refreshLine()
                        refreshLinesPage()
                    }
                    note("$label removed from this PC")
                }
            }
            override fun addLine() {
                if (session.lines().size >= 2) return note("This PC already has two lines.")
                showRoot("welcome")
            }
        })
        return linesPage
    }

    /** Fetches DND and the admin's capabilities for each line (or one), then repaints the Lines page. */
    private fun loadLineFacts(only: String? = null) {
        session.lines().filter { only == null || it.extension == only }.forEach { line ->
            work("Lines") {
                val info = runCatching { session.lineInfo(line) }.getOrNull()
                val dnd = runCatching { session.pbxDnd(line) }.getOrNull()
                SwingUtilities.invokeLater {
                    info?.capabilities?.let { lineCaps[line.extension] = it }
                    if (dnd != null) lineDnd[line.extension] = dnd
                    refreshLinesPage()
                }
            }
        }
    }

    private fun refreshLinesPage() {
        if (!::linesPage.isInitialized) return
        val snap = SipBridge.snapshot()
        val lines = session.lines()
        if (linesSelected == null || lines.none { it.extension == linesSelected }) linesSelected = session.line?.extension
        val host = lines.firstOrNull()?.sipDomain.orEmpty()
        linesPage.show(
            net.ithandsfree.softphone.win.ui.LinesPage.Model(
                lines = lines.mapIndexed { index, line ->
                    val up = if (index == 0) snap.registered else snap.secondRegistered
                    val caps = lineCaps[line.extension]
                    net.ithandsfree.softphone.win.ui.LinesPage.Line(
                        extension = line.extension,
                        label = lineLabel(line.extension),
                        number = line.did.takeIf { it.isNotBlank() }?.let { displayNumber(it) },
                        colorIndex = index,
                        isDefault = line.extension == session.line?.extension,
                        dnd = lineDnd[line.extension],
                        caps = caps?.let { net.ithandsfree.softphone.win.ui.LinesPage.Caps(it.voice, it.sms, it.mms) },
                        missed = missedSince(line.extension),
                        unread = if (line.extension == session.line?.extension) knownUnread.coerceAtLeast(0) else 0,
                        registered = up,
                        registration = when {
                            up -> "$host · ${snap.transport?.name ?: "TLS"}"
                            askedRegister -> "Registering with $host"
                            else -> "Not registered"
                        },
                        signedInAs = line.umUsername.takeIf { line.signedIn },
                        ringtoneId = AudioPrefs.lineRingtone(line.extension),
                    )
                },
                selected = linesSelected,
                maxLines = 2,
                ringtones = listOf("" to "Same as default (${RingtoneLibrary.choice(AudioPrefs.ringtoneStyle()).label})") +
                    RingtoneLibrary.builtIn.map { it.id to it.label },
            ),
        )
    }

    /** Round-3 Settings: lines + sections on the left, the chosen section on the right. */
    private fun settings(): JPanel {
        val t = tokens
        val ui = net.ithandsfree.softphone.win.ui.Type
        settingsShell = net.ithandsfree.softphone.win.ui.SettingsShell(t) { id ->
            settingsSection = id
            if (id == "audio") loadAudioDevices()
        }
        fun stack(vararg parts: JComponent): JComponent {
            val p = JPanel()
            p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
            p.isOpaque = false
            parts.forEach { it.alignmentX = Component.LEFT_ALIGNMENT; p.add(it) }
            return p
        }
        fun gap(h: Int) = Box.createVerticalStrut(h) as JComponent
        fun caption(text: String) = JLabel("<html>$text</html>").apply {
            font = ui.ui(ui.CAPTION + 0.5f)
            foreground = t.textCaption
            maximumSize = Dimension(720, 60)
        }
        fun combo(c: JComponent) = c.apply {
            putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 12; padding: 6,10,6,10")
            preferredSize = Dimension(360, 42)
            (this as? javax.swing.JComboBox<*>)?.renderer = deviceRenderer()
        }

        // Audio & devices
        val playTest = net.ithandsfree.softphone.win.ui.PillButton(t, "Play test sound", height = 42) {
            val snap = SipBridge.snapshot()
            if (snap.callActive || snap.incoming) return@PillButton
            CallRinger.stop()
            CallRinger.lineExtension = null
            CallRinger.start()
            Timer(2400) { CallRinger.stop() }.apply { isRepeats = false; start() }
        }
        val (alsoRingRow, _) = net.ithandsfree.softphone.win.ui.switchRow(
            t, "Also ring on the headset, so you hear calls whether it's on or not", AudioPrefs.alsoRing(),
        ) { AudioPrefs.saveAlsoRing(it) }
        val (voiceRow, _) = net.ithandsfree.softphone.win.ui.switchRow(
            t, "Echo cancellation, noise suppression and automatic level", AudioPrefs.voiceProcessing(),
            "These run together in this build. Turn off only if a headset already does its own echo cancelling.",
        ) { on ->
            AudioPrefs.saveVoiceProcessing(on)
            work("Voice processing") { SipBridge.setEchoCancel(on) }
        }
        val (keypadRow, _) = net.ithandsfree.softphone.win.ui.switchRow(t, "Keypad sound", AudioPrefs.keypadTone()) {
            AudioPrefs.saveKeypadTone(it)
        }
        removeRingtone = net.ithandsfree.softphone.win.ui.PillButton(t, "Remove your file", height = 38) {
            AudioPrefs.clearRingtone()
            reloadRingtoneList()
            note("Using Two tone")
        }
        val chooseOwn = net.ithandsfree.softphone.win.ui.PillButton(t, "Choose your own", height = 38) { chooseRingtone() }
        val echoCard = net.ithandsfree.softphone.win.ui.Card(t, 16)
        echoCard.layout = BorderLayout(14, 0)
        echoCard.border = EmptyBorder(16, 18, 16, 16)
        echoCard.add(JLabel(net.ithandsfree.softphone.win.ui.Icons.get("phone", 22, t.actionText)), BorderLayout.WEST)
        echoCard.add(stack(
            JLabel("Test a call").apply { font = ui.semibold(ui.BODY); foreground = t.text },
            gap(3),
            echoCaption.apply { font = ui.ui(ui.CAPTION + 0.5f); foreground = t.textMuted },
        ), BorderLayout.CENTER)
        echoCard.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(net.ithandsfree.softphone.win.ui.PillButton(t, "Call echo test  *43", primary = true, height = 42) {
                dial.text = "*43"
                showTab("calls")
                placeCall()
            }, BorderLayout.CENTER)
        }, BorderLayout.EAST)
        echoCard.maximumSize = Dimension(720, 80)
        val audio = stack(
            net.ithandsfree.softphone.win.ui.settingRow(t, "Microphone", combo(audioCombo(micModel) {
                val picked = micModel.selectedItem as? SipBridge.AudioDevice ?: return@audioCombo
                AudioPrefs.saveCapture(picked.name)
                work("Microphone") { if (SipBridge.setCapture(picked.index) != 0) note("Microphone was not changed") }
            }), settingsMeter),
            net.ithandsfree.softphone.win.ui.settingRow(t, "Speaker", combo(audioCombo(speakerModel) { useSelectedSpeaker() }), playTest),
            net.ithandsfree.softphone.win.ui.settingRow(t, "Ringer", combo(ringerCombo())),
            net.ithandsfree.softphone.win.ui.settingRow(t, "Call volume", combo(javax.swing.JComboBox(
                CALL_VOLUMES.map { (percent, label) -> if (percent == 100) label else "$label ($percent%)" }.toTypedArray(),
            ).apply {
                selectedIndex = CALL_VOLUMES.indexOfFirst { it.first == AudioPrefs.callVolume() }.coerceAtLeast(0)
                toolTipText = "Makes the other person louder on every call. Also in the device menu during a call."
                addActionListener { CALL_VOLUMES.getOrNull(selectedIndex)?.let { (p, _) -> if (p != AudioPrefs.callVolume()) setCallVolume(p) } }
                callVolumeCombo = this
            })),
            alsoRingRow,
            gap(14), net.ithandsfree.softphone.win.ui.divider(t), gap(16),
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "Voice processing"),
            voiceRow,
            keypadRow,
            gap(14), net.ithandsfree.softphone.win.ui.divider(t), gap(16),
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "Ringtone"),
            net.ithandsfree.softphone.win.ui.settingRow(t, "Default ringtone", combo(ringtoneCombo()), chooseOwn, removeRingtone),
            caption("Each line can have its own ringtone on the Lines page. Your own file: WAV, AIFF or AU, up to 8 MB."),
            gap(20),
            echoCard,
            gap(10),
            audioNote.apply { font = ui.ui(ui.CAPTION + 0.5f); foreground = t.textCaption },
        )

        // Notifications
        val (dndRow, dndSwitch) = net.ithandsfree.softphone.win.ui.switchRow(
            t, "Do not disturb on this PC", WindowPrefs.dnd(),
            "Silences the ring and the incoming window here, and sets Do not disturb on the PBX for the default " +
                "line. Each line's PBX setting is on the Lines page.",
        ) { on ->
            WindowPrefs.saveDnd(on)
            if (on) CallRinger.stop()
            surfaces.setToggles(WindowPrefs.pinned(), on)
            work("Do not disturb") { session.setPbxDnd(on) }
        }
        dndToggle = dndSwitch
        val (waitingRow, _) = net.ithandsfree.softphone.win.ui.switchRow(
            t, "Call waiting", WindowPrefs.callWaiting(),
            "A second call rings beside the one you are on (a short beep in your headset), with Hold &amp; answer. " +
                "Off: the second caller hears busy and the PBX tries your other devices, then voicemail.",
        ) { on ->
            WindowPrefs.saveCallWaiting(on)
            SipBridge.setCallWaiting(on)
            DiagLog.app("call waiting ${if (on) "on" else "off"}")
        }
        val notifications = stack(dndRow, gap(10), waitingRow)

        // Keyboard shortcuts
        val keys = shortcutSection { caption(it) }

        // Calling links & startup
        val links = stack(
            caption("Setup links (<b>ihfphone:</b>) always open ${profile.productName}. <b>tel:</b> and <b>sip:</b> links " +
                "open it when no other app owns them. Windows asks before another app is replaced."),
            gap(10),
            caption("Closing the window keeps ${profile.productName} in the tray so calls still ring. Quit from the tray stops ringing on this computer."),
        )

        // Appearance
        val (pinRow, pinSwitch) = net.ithandsfree.softphone.win.ui.switchRow(t, "Keep this window on top", WindowPrefs.pinned()) { on ->
            setPinned(on)
        }
        pinToggle = pinSwitch
        val appearance = stack(pinRow, gap(8), caption("${profile.productName} uses the dark theme on the desktop."))

        // Advanced
        val advanced = stack(
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "Contacts"),
            caption("Import a CSV or vCard, or sign in to a Google or Microsoft 365 mailbox in the browser. The mail app on this PC is not used."),
            gap(10),
            JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Import contacts", "user-plus", height = 40) { importContacts() })
                add(Box.createHorizontalStrut(10))
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Connect Microsoft 365", height = 40) { connectMailbox(MailboxAuth.Provider.MICROSOFT) })
                add(Box.createHorizontalStrut(10))
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Connect Google", height = 40) { connectMailbox(MailboxAuth.Provider.GOOGLE) })
                maximumSize = Dimension(720, 44)
            },
            gap(22),
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "Diagnostics"),
            caption("A log of calls, registration and transfers is kept on this PC for 14 days (no passwords, no call " +
                "audio). If something goes wrong on a call, save it and send it to your administrator."),
            gap(10),
            JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Save diagnostic log…", "download", height = 40) { exportDiagnostics() })
                add(Box.createHorizontalStrut(10))
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Open log folder", height = 40) {
                    runCatching { DiagLog.dir().mkdirs(); java.awt.Desktop.getDesktop().open(DiagLog.dir()) }
                })
                maximumSize = Dimension(720, 44)
            },
            gap(22),
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "Connection"),
            caption("Voice uses SIP over TLS on 5061, then TCP on 5060. UDP signalling is not used. Texts go through the PBX softphone service over HTTPS."),
            gap(22),
            net.ithandsfree.softphone.win.ui.sectionLabel(t, "About"),
            caption("${profile.productName} · build $APP_BUILD · ${profile.brandSub} · GPL-2.0"),
            gap(10),
            JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Privacy", height = 38) { browse("https://ithandsfree.com/privacy#ihf-phone") })
                add(Box.createHorizontalStrut(10))
                add(net.ithandsfree.softphone.win.ui.PillButton(t, "Licences", height = 38) { browse("https://github.com/ithandsfree/softphone") })
                maximumSize = Dimension(720, 42)
            },
        )

        settingsShell.sections(
            listOf(
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("audio", "Audio & devices", "headset", audio),
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("notifications", "Notifications", "bell", notifications),
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("shortcuts", "Keyboard shortcuts", "keyboard", keys),
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("links", "Calling links & startup", "link", links),
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("appearance", "Appearance", "monitor", appearance),
                net.ithandsfree.softphone.win.ui.SettingsShell.Section("advanced", "Advanced", "shield", advanced),
            ),
        )
        return settingsShell
    }

    /** Shows "Windows default" for the WinMM "Wave mapper" device in the pickers. */
    private fun deviceRenderer(): javax.swing.ListCellRenderer<Any?> {
        val base = javax.swing.DefaultListCellRenderer()
        return javax.swing.ListCellRenderer { list, value, index, selected, focus ->
            val text = when (value) {
                is SipBridge.AudioDevice -> value.name
                else -> value?.toString().orEmpty()
            }
            val shown = if (text.contains("wave mapper", ignoreCase = true)) "Windows default" else text
            base.getListCellRendererComponent(list, shown, index, selected, focus)
        }
    }


    /**
     * Settings › Keyboard shortcuts: every remappable key with Change and Reset, grouped as on the sheet, then the
     * opt-in system-wide keys. A change is checked against Windows and text-box keys and against other actions.
     */
    private fun shortcutSection(caption: (String) -> JComponent): JComponent {
        val t = tokens
        val box = JPanel()
        box.layout = BoxLayout(box, BoxLayout.Y_AXIS)
        box.isOpaque = false
        fun add(c: JComponent) { c.alignmentX = Component.LEFT_ALIGNMENT; box.add(c) }
        shortcutEditRows.clear()
        ShortcutGroup.entries.forEach { group ->
            if (group != ShortcutGroup.ANYWHERE) {
                add(Box.createVerticalStrut(12) as JComponent)
                add(net.ithandsfree.softphone.win.ui.divider(t))
                add(Box.createVerticalStrut(14) as JComponent)
            }
            add(net.ithandsfree.softphone.win.ui.sectionLabel(t, group.title))
            if (group == ShortcutGroup.SYSTEM) {
                val (row, _) = net.ithandsfree.softphone.win.ui.switchRow(
                    t, "Use system-wide keys", KeymapStore.current().globalEnabled,
                    "Answer, hang up and mute while another app is in front. They take these keys from every other app, " +
                        "so they are off until you turn them on.",
                ) { on ->
                    KeymapStore.save(KeymapStore.current().withGlobal(on))
                    applyGlobalHotkeys()
                    if (on) note("System-wide keys are on")
                }
                add(row)
            }
            Shortcut.entries.filter { it.group == group }.forEach { action ->
                val row = net.ithandsfree.softphone.win.ui.ShortcutEditRow(t, action.label, onCapture = { captureShortcut(action, it) }) {
                    KeymapStore.save(KeymapStore.current().reset(action))
                    shortcutChanged(action)
                }
                shortcutEditRows[action] = row
                add(row)
            }
            FIXED_SHORTCUTS.filter { it.first == group }.forEach { (_, label, keys) ->
                add(net.ithandsfree.softphone.win.ui.keyRow(t, label, listOf(keys)).apply { maximumSize = Dimension(720, 40) })
            }
        }
        add(Box.createVerticalStrut(16) as JComponent)
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(net.ithandsfree.softphone.win.ui.PillButton(t, "Show the shortcuts sheet", "keyboard", height = 38) { showShortcuts() })
            add(Box.createHorizontalStrut(10))
            add(net.ithandsfree.softphone.win.ui.PillButton(t, "Reset all", height = 38) {
                KeymapStore.save(KeymapStore.current().resetAll())
                Shortcut.entries.forEach { shortcutChanged(it) }
                note("Shortcuts are back to the defaults")
            })
            maximumSize = Dimension(720, 42)
        })
        add(Box.createVerticalStrut(12) as JComponent)
        add(caption(
            "Keys without Ctrl or Alt work only during a call and only when the cursor is not in a text box. " +
                "Recents also use Enter, M, Del, Ctrl Shift C and Shift F10; these are fixed.",
        ))
        Shortcut.entries.forEach { paintShortcutRow(it) }
        return box
    }

    private fun paintShortcutRow(action: Shortcut) {
        val keymap = KeymapStore.current()
        shortcutEditRows[action]?.display(keymap.chord(action).label(), !keymap.isDefault(action))
    }

    private fun shortcutChanged(action: Shortcut) {
        if (action == Shortcut.SEARCH) titleBar.searchKey(KeymapStore.current().chord(action).label())
        paintShortcutRow(action)
        if (action.global) applyGlobalHotkeys()
    }

    /** Change was pressed: the next key press (with its modifiers) becomes the new key, unless it is refused. */
    private fun captureShortcut(action: Shortcut, row: net.ithandsfree.softphone.win.ui.ShortcutEditRow) {
        if (capturingRow === row) {
            endCapture()
            paintShortcutRow(action)
            return
        }
        endCapture()
        capturingRow = row
        row.prompt()
        // System-wide keys are suspended while capturing, so pressing the current one does not hang up a call.
        if (action.global) GlobalHotkeys.stop()
        net.ithandsfree.softphone.win.ui.KeyCapture.listener = listener@{ event ->
            if (Chord.isModifierOnly(event.keyCode)) return@listener true
            val chord = Chord.of(event)
            if (chord == Chord(java.awt.event.KeyEvent.VK_ESCAPE)) {
                endCapture()
                paintShortcutRow(action)
                if (action.global) applyGlobalHotkeys()
                return@listener true
            }
            val keymap = KeymapStore.current()
            val refusal = keymap.refusal(action, chord)
            if (refusal != null) {
                row.problem(refusal)
                return@listener true
            }
            endCapture()
            KeymapStore.save(keymap.with(action, chord))
            shortcutChanged(action)
            row.note("${action.label} is now ${chord.label()}")
            true
        }
    }

    private fun endCapture() {
        net.ithandsfree.softphone.win.ui.KeyCapture.listener = null
        capturingRow = null
    }

    private fun styleCheck(box: javax.swing.JCheckBox) {
        box.background = ground
        box.foreground = ink
        box.font = uiFont
        box.isOpaque = true
        box.alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun navItems() = listOf(
        Triple("calls", "Calls", "phone"),
        Triple("messages", "Messages", "msg"),
        Triple("lines", "Lines", "lines"),
        Triple("settings", "Settings", "settings"),
    )

    /** The rail and the compact bottom bar both land here. */
    private fun pickTab(id: String) {
        if (id == "messages") refreshThreads()
        if (id == "lines") refreshLine()
        if (id == "settings") loadAudioDevices()
        showTab(id)
    }

    private fun nav(): JPanel {
        navRail = net.ithandsfree.softphone.win.ui.NavRail(
            tokens,
            navItems(),
            onPick = { id -> pickTab(id) },
            onShortcuts = { showShortcuts() },
            onAccount = { showTab("lines") },
        )
        return navRail
    }

    private fun refreshCall() {
        val snap = SipBridge.snapshot()
        watchTransfer(snap)
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
        // Every call is followed by its id (call waiting can put two up): own timer, own Recents row when it ends.
        val trackedCalls = buildList {
            if ((snap.callActive || snap.incoming) && snap.callId >= 0) {
                add(CallTracker.Seen(
                    snap.callId,
                    displayParty(snap.remote, dial.text.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }),
                    snap.callExtension.ifBlank { ext },
                    ringing = snap.incoming,
                    connected = connected,
                ))
            }
            if (snap.waitingState > 0 && snap.waitingId >= 0) {
                add(CallTracker.Seen(snap.waitingId, displayParty(snap.waitingRemote, ""), snap.waitingExtension, snap.waitingState == 1, snap.waitingState == 2))
            }
        }
        val finishedCalls = callTracker.update(trackedCalls, System.currentTimeMillis())
        callStartedAt = callTracker.connectedAt(snap.callId) ?: 0L
        watchCallWaiting(snap)
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
        paintTitleStatus(
            snap,
            when {
                connected -> liveTimer.text
                ringing -> "Incoming"
                live -> "Calling"
                else -> null
            },
        )
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
        dtmfHolder?.isVisible = live && showDtmf
        callButton.isVisible = !ringing && !live
        if (onCall && ::inCallPane.isInitialized) {
            val callExt = snap.callExtension.ifBlank { lastCallExt.ifBlank { ext } }
            val callLine = session.lines().firstOrNull { it.extension == callExt }
            val person = ContactBook.nameFor(party)
            val shownParty = person?.name ?: displayNumber(party).ifBlank { "Call" }
            val colorIndex = lineIndex(callExt) ?: 0
            inCallPane.show(
                net.ithandsfree.softphone.win.ui.InCallPane.State(
                    mode = when {
                        ringing -> net.ithandsfree.softphone.win.ui.InCallPane.Mode.RINGING
                        connected -> net.ithandsfree.softphone.win.ui.InCallPane.Mode.LIVE
                        else -> net.ithandsfree.softphone.win.ui.InCallPane.Mode.CALLING
                    },
                    lineLabel = lineLabel(callExt),
                    lineNumber = callLine?.did?.takeIf { it.isNotBlank() }?.let { displayNumber(it) },
                    lineColorIndex = colorIndex,
                    title = shownParty,
                    titleIsNumber = person == null,
                    subtitle = if (person != null) displayNumber(party) else "",
                    timer = liveTimer.text,
                    muted = snap.muted,
                    held = snap.held,
                    keypadOpen = showDtmf,
                    device = (speakerModel.selectedItem as? SipBridge.AudioDevice)?.name,
                ),
            )
            activeCard.show(
                shownParty,
                lineLabel(callExt),
                colorIndex,
                when {
                    ringing -> "ringing"
                    snap.held -> "on hold"
                    connected -> "on call"
                    else -> "calling"
                },
                liveTimer.text,
            )
        }
        // The pinned card shows only while the call pane is not in front (browsing recents or another tab).
        activeCard.isVisible = onCall && (browsingDuringCall || currentTab != "calls")
        if (onCall && party.isNotBlank()) lastParty = party
        if ((snap.callActive || snap.incoming) && snap.callExtension.isNotBlank()) lastCallExt = snap.callExtension
        if (lastCallExt.isBlank() && (snap.callActive || snap.incoming)) lastCallExt = ext
        if (ringing) {
            lastIncoming = true
            CallRinger.lineExtension = snap.callExtension.ifBlank { null }
            if (recordingPlayer.playing != null) {
                recordingPlayer.stop()
                if (::callDetailPane.isInitialized) callDetailPane.playing(null)
            }
        }
        if (connected) lastConnected = true
        val mode = when {
            ringing -> "in"
            live -> "live"
            else -> "idle"
        }
        if (finishedCalls.isNotEmpty()) {
            finishedCalls.forEach { done ->
                CallLog.append(CallEntry(System.currentTimeMillis(), done.kind, done.party, done.seconds, done.extension))
                DiagLog.app("call logged: ${done.kind} ${done.seconds}s on ${done.extension}")
            }
            reloadRecents()
        }
        if ((callMode == "in" || callMode == "live") && mode == "idle") {
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
        // A second call ringing beside the current one uses the same incoming window, with "Hold & answer".
        val waitingRings = snap.waitingState == 1 && !WindowPrefs.dnd()
        val cardParty = if (waitingRings) displayParty(snap.waitingRemote, "") else party
        val cardExt = if (waitingRings) snap.waitingExtension.ifBlank { ext } else snap.callExtension.ifBlank { lastCallExt.ifBlank { ext } }
        val cardPerson = ContactBook.nameFor(cardParty)
        val cardNumber = displayNumber(cardParty)
        surfaces.sync(
            incoming = ringing || waitingRings,
            onCall = live,
            card = net.ithandsfree.softphone.win.ui.CallCard(
                title = cardPerson?.name ?: cardNumber.ifBlank { "Incoming call" },
                titleIsNumber = cardPerson == null && cardNumber.isNotBlank(),
                subtitle = when {
                    cardPerson != null -> cardNumber
                    cardNumber.isBlank() -> "Caller ID withheld"
                    else -> "Not in contacts"
                },
                lineLabel = lineLabel(cardExt),
                lineColorIndex = lineIndex(cardExt) ?: 0,
                lineNumber = session.lines().firstOrNull { it.extension == cardExt }?.did
                    ?.takeIf { it.isNotBlank() }?.let { displayNumber(it) },
                waiting = waitingRings,
            ),
            clock = liveTimer.text.ifBlank { inCallClock.text },
            mainInFront = mainInFront,
            muted = snap.muted,
        )
        if (currentTab == "settings") {
            // The tray can change these too; keep the switches in step.
            pinToggle?.set(WindowPrefs.pinned())
            dndToggle?.set(WindowPrefs.dnd())
            val line = session.line
            echoCaption.text = "Calls the PBX echo test from ${lineLabel(line?.extension)}. Speak, and you'll hear yourself back."
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
                    dndToggle?.set(on)
                    if (::surfaces.isInitialized) surfaces.setToggles(WindowPrefs.pinned(), on)
                    if (on) CallRinger.stop()
                }
            }.apply { isDaemon = true; name = "ihf-dnd" }.start()
        }
        pollTick += 1
        if (pollTick % 15 == 0) refreshThreads(notify = true)
    }

    private fun paintMicLevel() {
        val inCall = callMode == "live" && ::inCallPane.isInitialized
        if (currentTab != "settings" && !inCall) return
        val heard = SipBridge.micLevel()
        if (inCall) inCallPane.micLevel(heard)
        if (currentTab != "settings") return
        val now = System.currentTimeMillis()
        if (heard >= micPeak || now - micPeakAt > 160) {
            micPeak = heard
            micPeakAt = now
        }
        settingsMeter.level = micPeak
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
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Pictures", "jpg", "jpeg", "png", "gif")
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val bytes = readPhotoFile(chooser.selectedFile) ?: return
        stagePhoto(bytes, chooser.selectedFile.name, "chosen")
    }

    private fun stagePhoto(raw: ByteArray, name: String, how: String = "added") {
        work("Photo") {
            val photo = prepareMmsPhoto(raw, name)
            val thumb = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(photo.bytes)) }.getOrNull()
            SwingUtilities.invokeLater {
                pendingPhoto = photo
                attachNote.text = "  Photo · ${photo.bytes.size / 1024} KB  "
                if (::attachCard.isInitialized) {
                    attachCard.show(photo.name, "$how · ${photo.bytes.size / 1024} KB", thumb)
                }
                smsBody.requestFocusInWindow()
            }
        }
    }

    /** Reads a dropped or chosen file, refusing anything far beyond what MMS can carry. */
    private fun readPhotoFile(file: java.io.File): ByteArray? {
        if (!file.isFile) return null
        if (file.length() > MAX_PHOTO_FILE) {
            note("That file is ${file.length() / 1_048_576} MB. Choose a photo under 25 MB.")
            return null
        }
        return file.readBytes()
    }

    private fun clearPhoto() {
        pendingPhoto = null
        attachNote.text = ""
        if (::attachCard.isInitialized) {
            attachCard.isVisible = false
            attachCard.parent?.revalidate()
        }
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
                        stagePhoto(pngBytes(image), "screenshot.png", "pasted")
                        return true
                    }
                }
                if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                    val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>
                    val file = files?.filterIsInstance<java.io.File>()?.firstOrNull()
                    if (file != null) {
                        val bytes = readPhotoFile(file) ?: return false
                        stagePhoto(bytes, file.name, "dropped")
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

    private fun recentRow(entry: CallEntry, index: Int): JComponent {
        val person = ContactBook.nameFor(entry.party)
        val shownNumber = displayNumber(entry.party)
        val title = person?.name ?: shownNumber
        val kind = when (entry.kind) {
            "Missed" -> net.ithandsfree.softphone.win.ui.RecentRow.Kind.MISSED
            "Outgoing" -> net.ithandsfree.softphone.win.ui.RecentRow.Kind.OUTGOING
            else -> net.ithandsfree.softphone.win.ui.RecentRow.Kind.INCOMING
        }
        val lineName = entry.extension.takeIf { it.isNotBlank() }?.let { lineLabel(it) }
        val callLine = lineLabel(entry.extension.ifBlank { session.line?.extension })
        val textBlock = recentTextBlock(entry.party)
        val length = if (entry.seconds > 0) formatLiveCallTimer(entry.seconds) else null
        val whenText = formatRecentWhen(entry.at)
        val item = net.ithandsfree.softphone.win.ui.RecentRow.Item(
            title = title,
            titleIsNumber = person == null && title.any { it.isDigit() },
            kind = kind,
            lineLabel = if (session.lines().size > 1 || lineName != null) lineName else null,
            lineColorIndex = lineIndex(entry.extension),
            kindText = if (kind == net.ithandsfree.softphone.win.ui.RecentRow.Kind.MISSED) "Missed" else "",
            duration = length,
            whenText = whenText,
            callEnabled = recentCanCall(entry.party),
            callTip = if (recentCanCall(entry.party)) "Call from $callLine (Enter)" else "Caller ID withheld",
            messageEnabled = textBlock == null,
            messageTip = textBlock ?: "Message from $callLine (M)",
            accessibleLabel = "${entry.kind} call ${if (entry.kind == "Outgoing") "to" else "from"} $title" +
                (lineName?.let { " on $it" } ?: "") + ", $whenText",
        )
        val selected = index == recentIndex
        return net.ithandsfree.softphone.win.ui.RecentRow(tokens, item, selected, object : net.ithandsfree.softphone.win.ui.RecentRow.Actions {
            override fun open() {
                recentIndex = index
                openRecentDetail(entry)
            }
            override fun call() = callRecent(entry)
            override fun message() = messageRecent(entry)
            override fun more(anchor: JComponent) {
                recentIndex = index
                recentMenu(entry).show(anchor, 0, anchor.height + 4)
            }
            override fun context(e: java.awt.event.MouseEvent) {
                recentIndex = index
                recentMenu(entry).show(e.component, e.x, e.y)
            }
        })
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
        val ext = entry.extension.ifBlank { session.line?.extension.orEmpty() }
        fun item(text: String, icon: String, key: String?, enabled: Boolean = true, action: () -> Unit): javax.swing.JMenuItem {
            val row = javax.swing.JMenuItem(text, net.ithandsfree.softphone.win.ui.Icons.get(icon, 16, if (enabled) ink else tokens.textDisabled))
            row.font = net.ithandsfree.softphone.win.ui.Type.ui(net.ithandsfree.softphone.win.ui.Type.LABEL + 1)
            row.isEnabled = enabled
            row.iconTextGap = 10
            row.border = EmptyBorder(8, 10, 8, 12)
            if (key != null) row.putClientProperty("JMenuItem.acceleratorText", key)
            row.addActionListener { action() }
            menu.add(row)
            return row
        }
        val canCall = recentCanCall(entry.party)
        item("Call back from ${lineLabel(ext)}", "phone", "Enter", canCall) { callRecent(entry) }
        session.lines().filter { it.extension != ext }.forEach { other ->
            item("Call from ${lineLabel(other.extension)}", "phone", null, canCall) {
                session.choose(other.extension)
                dial.text = entry.party
                showTab("calls")
                placeCall()
            }
        }
        val textBlock = recentTextBlock(entry.party)
        item("Message from ${lineLabel(ext)}", "msg", "M", textBlock == null) { messageRecent(entry) }
        if (textBlock != null) {
            menu.add(javax.swing.JMenuItem(textBlock).apply { isEnabled = false; font = net.ithandsfree.softphone.win.ui.Type.ui(12f) })
        }
        menu.addSeparator()
        item("Copy number", "clipboard", "Ctrl Shift C") { copyRecent(entry) }
        val saved = ContactBook.nameFor(entry.party)
        if (canCall && saved == null) item("Add to contacts", "user-plus", null) { saveContact(entry.party) }
        menu.addSeparator()
        item("Remove from recents", "trash", "Del") { removeRecent(entry) }
        menu.border = BorderFactory.createCompoundBorder(
            net.ithandsfree.softphone.win.ui.RoundBorder(tokens.hairline, 12),
            EmptyBorder(6, 6, 6, 6),
        )
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
        val shown = displayNumber(entry.party)
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
        if (compactMode) {
            // Compact: back from a recent's detail returns to the list it was opened from.
            narrowList = !onCall
            applyWindowShape()
        }
    }

    private fun fillRecentDetail(entry: CallEntry) {
        if (!::callDetailPane.isInitialized) return
        val ext = entry.extension.ifBlank { session.line?.extension.orEmpty() }
        val label = lineLabel(ext)
        val line = session.lines().firstOrNull { it.extension == ext }
        val person = ContactBook.nameFor(entry.party)
        val title = person?.name ?: displayNumber(entry.party)
        val firstName = person?.name?.substringBefore(' ')
        val missed = entry.kind == "Missed"
        val canCall = recentCanCall(entry.party)
        val textBlock = recentTextBlock(entry.party)
        val zone = java.time.ZoneId.systemDefault()
        val whenDay = java.time.Instant.ofEpochMilli(entry.at).atZone(zone).toLocalDate()
        val clock = java.time.Instant.ofEpochMilli(entry.at).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
        val whenText = when (whenDay) {
            java.time.LocalDate.now(zone) -> "today $clock"
            java.time.LocalDate.now(zone).minusDays(1) -> "yesterday $clock"
            else -> "${formatRecentWhen(entry.at)} $clock"
        }
        val others = session.lines().filter { it.extension != ext }.map { it.extension to lineLabel(it.extension) }
        val pbx = pbxCalls[ext]
        if (pbx == null || System.currentTimeMillis() - pbx.second > 30_000) loadPbxCalls(ext, entry)
        val pbxCallsForLine = pbx?.first
        val history = CallLog.load().filter { sameNumber(it.party, entry.party) }.take(8).map { past ->
            val pastExt = past.extension.ifBlank { ext }
            val pbxRows = if (pastExt == ext) pbxCallsForLine else pbxCalls[pastExt]?.first
            val match = pbxRows?.let { matchPbxCall(past.party, past.at, past.seconds, it.calls) }
            val canPlay = match?.recording == true && pbxRows.permissions.playback
            val length = if (past.seconds > 0) " · ${formatLiveCallTimer(past.seconds)}" else ""
            net.ithandsfree.softphone.win.ui.CallDetailPane.History(
                icon = when (past.kind) { "Missed" -> "missed"; "Outgoing" -> "out"; else -> "in" },
                text = if (past.kind == "Missed") "Missed call" else "${past.kind}$length",
                mono = false,
                danger = past.kind == "Missed",
                lineLabel = past.extension.takeIf { it.isNotBlank() }?.let { lineLabel(it) },
                lineColorIndex = lineIndex(past.extension),
                whenText = formatRecentWhen(past.at),
                recordingId = if (canPlay) "$pastExt|${match?.id}" else null,
                canDownload = canPlay && pbxRows?.permissions?.download == true,
            )
        }.toMutableList()
        // Latest text with this number on the line the call used, if the thread list is loaded for it.
        if (session.line?.extension == ext) {
            allThreads.firstOrNull { sameNumber(it.peer, entry.party) }?.let { thread ->
                history.add(
                    0,
                    net.ithandsfree.softphone.win.ui.CallDetailPane.History(
                        icon = "msg",
                        text = "“${thread.snippet.orEmpty().take(48)}”",
                        mono = false,
                        danger = false,
                        lineLabel = label,
                        lineColorIndex = lineIndex(ext),
                        whenText = epochMsOf(thread.lastMessageAt)?.let { formatRecentWhen(it) }.orEmpty(),
                        openable = true,
                    ),
                )
            }
        }
        callDetailPane.show(
            net.ithandsfree.softphone.win.ui.CallDetailPane.Model(
                title = title,
                titleIsNumber = person == null,
                subtitle = if (person != null) displayNumber(entry.party) else "",
                statusIcon = when (entry.kind) { "Missed" -> "missed"; "Outgoing" -> "out"; else -> "in" },
                statusText = when (entry.kind) {
                    "Missed" -> "Missed call on"
                    "Outgoing" -> "You called from"
                    else -> "Answered on"
                },
                statusDanger = missed,
                lineLabel = label,
                lineColorIndex = lineIndex(ext) ?: 0,
                lineNumber = line?.did?.takeIf { it.isNotBlank() }?.let { displayNumber(it) },
                whenText = whenText,
                callLabel = "Call back from $label",
                otherLines = others,
                canCall = canCall,
                callTip = if (canCall) "Call back from $label (Enter)" else "Caller ID withheld",
                canMessage = textBlock == null,
                messageTip = textBlock ?: "Message from $label (M)",
                hasContact = person != null,
                explanation = when {
                    others.isEmpty() -> "Calls and texts go from $label."
                    firstName != null && entry.kind != "Outgoing" ->
                        "Calls and texts go from $label, the line $firstName called. The arrow offers ${others.joinToString { it.second }}."
                    else -> "Calls and texts go from $label, the line used for this call. The arrow offers ${others.joinToString { it.second }}."
                },
                historyTitle = "History with ${firstName ?: "this number"}",
                history = history,
            ),
        )
    }

    private fun recentDetail(): JPanel {
        callDetailPane = net.ithandsfree.softphone.win.ui.CallDetailPane(tokens, object : net.ithandsfree.softphone.win.ui.CallDetailPane.Actions {
            override fun callBack() { selectedRecent()?.let { callRecent(it) } }
            override fun callFrom(extension: String) {
                val entry = selectedRecent() ?: return
                session.choose(extension)
                dial.text = entry.party
                placeCall()
            }
            override fun message() { selectedRecent()?.let { messageRecent(it) } }
            override fun copy() { selectedRecent()?.let { copyRecent(it) } }
            override fun contact() { selectedRecent()?.let { saveContact(it.party) } }
            override fun keypad() = closeRecentDetail()
            override fun playRecording(callId: String) = this@PhoneFrame.playRecording(callId)
            override fun saveRecording(callId: String) = this@PhoneFrame.saveRecording(callId)
            override fun openThread() {
                val entry = selectedRecent() ?: return
                showTab("messages")
                allThreads.firstOrNull { sameNumber(it.peer, entry.party) }?.let { openThreadFor(it) }
            }
        })
        return callDetailPane
    }

    /** PBX history per extension with when it was fetched; null response = not permitted (403) or failed. */
    private val pbxCalls = java.util.concurrent.ConcurrentHashMap<String, Pair<CallsResponse?, Long>>()
    private val recordingPlayer = RecordingPlayer()

    /** Fetches the PBX call history for [ext] and redraws [entry]'s detail if it is still open. */
    private fun loadPbxCalls(ext: String, entry: CallEntry) {
        val line = session.lines().firstOrNull { it.extension == ext } ?: return
        pbxCalls[ext] = (pbxCalls[ext]?.first) to System.currentTimeMillis()
        io.submit {
            val result = try {
                session.pbxCalls(line)
            } catch (err: Exception) {
                null // 403 (UCP Call History off for this user/extension) or offline: no recordings shown
            }
            pbxCalls[ext] = result to System.currentTimeMillis()
            SwingUtilities.invokeLater {
                if (detailOpen && selectedRecent() == entry) fillRecentDetail(entry)
            }
        }
    }

    private fun playRecording(key: String) {
        val (ext, callId) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (recordingPlayer.playing == key) {
            recordingPlayer.stop()
            callDetailPane.playing(null)
            return
        }
        val line = session.lines().firstOrNull { it.extension == ext } ?: return
        work("Recording") {
            val audio = session.recording(line, callId)
            SwingUtilities.invokeLater {
                recordingPlayer.play(key, audio, AudioPrefs.playbackName()) { SwingUtilities.invokeLater { callDetailPane.playing(null) } }
                callDetailPane.playing(key)
            }
        }
    }

    private fun saveRecording(key: String) {
        val (ext, callId) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val line = session.lines().firstOrNull { it.extension == ext } ?: return
        val entry = selectedRecent()
        val chooser = javax.swing.JFileChooser()
        chooser.dialogTitle = "Save call recording"
        val stamp = java.time.Instant.ofEpochMilli(entry?.at ?: System.currentTimeMillis())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
        chooser.selectedFile = java.io.File("Call $stamp ${entry?.party?.filter { it.isLetterOrDigit() || it == '+' }.orEmpty()}.wav")
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile
        work("Recording") {
            target.writeBytes(session.recording(line, callId, download = true))
            note("Saved ${target.name}")
        }
    }

    /** Opens a thread and selects it in the list. */
    private fun openThreadFor(thread: ThreadInfo) {
        val index = (0 until threadModel.size()).firstOrNull { threadModel[it].peer == thread.peer }
        if (index != null) threadList.selectedIndex = index else openThread(thread)
    }

    private fun refreshThreads(notify: Boolean = false) {
        if (session.line == null) return
        work("Messages") {
            val rows = session.threads()
            val unread = rows.sumOf { it.unread }
            SwingUtilities.invokeLater {
                val signature = rows.joinToString("\n") { "${it.peer}\t${it.unread}\t${it.snippet.orEmpty()}" }
                if (signature == threadSignature) return@invokeLater
                val before = threadSignature
                threadSignature = signature
                val grew = knownUnread >= 0 && unread > knownUnread
                val keepPeer = threadList.selectedValue?.peer
                // Reload the open conversation when its last message changes. Unread alone misses a reply that
                // the phone on the same line has already opened (it is then read before this PC polls).
                if (!keepPeer.isNullOrBlank() && (currentTab == "messages" || currentTab == "thread")) {
                    val was = before.lineSequence().firstOrNull { it.startsWith("$keepPeer\t") }?.substringAfterLast('\t')
                    val now = rows.firstOrNull { it.peer == keepPeer }?.snippet.orEmpty()
                    if (was != now) {
                        work("Thread") {
                            val messages = session.conversation(keepPeer)
                            SwingUtilities.invokeLater { showTranscript(messages) }
                        }
                    }
                }
                allThreads = rows
                filterThreads()
                knownUnread = unread
                navRail.badge("messages", unread, danger = false)
                if (::bottomNav.isInitialized) bottomNav.badge("messages", unread, danger = false)
                if (notify && grew) {
                    val peer = rows.firstOrNull { it.unread > 0 }?.peer?.ifBlank { null } ?: "a contact"
                    note("New message")
                    surfaces.notifyMessage(peer)
                }
            }
        }
    }

    private fun openThread(thread: ThreadInfo) {
        val peer = thread.peer.ifBlank { "Message" }
        threadTitle.text = peer
        smsTo.text = thread.peer
        showConversationFor(thread.peer)
        transcript.removeAll()
        transcript.revalidate()
        transcript.repaint()
        work("Thread") {
            val rows = session.conversation(thread.peer)
            SwingUtilities.invokeLater { showTranscript(rows) }
        }
    }

    private fun showTranscript(rows: List<MessageInfo>) {
        val t = tokens
        transcript.removeAll()
        if (rows.isEmpty()) {
            transcript.add(net.ithandsfree.softphone.win.ui.daySeparator(t, "No messages yet"))
        }
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        var lastDay: java.time.LocalDate? = null
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
        val shown = net.ithandsfree.softphone.win.ui.collapseRepeats(
            rows.sortedBy { it.epochMs ?: 0L },
            key = { "${it.direction}|${it.body}|${it.media.joinToString { m -> m.name }}" },
            at = { it.epochMs },
        )
        shown.forEach { row ->
            val inbound = row.direction.equals("in", ignoreCase = true)
            val at = row.epochMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
            val day = at?.toLocalDate()
            if (day != null && day != lastDay) {
                lastDay = day
                val dayText = when (day) {
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.US))
                }
                transcript.add(net.ithandsfree.softphone.win.ui.daySeparator(t, dayText))
            }
            val timeText = at?.format(clock) ?: row.datetime.orEmpty()
            if (row.body.isNotBlank()) {
                val bubble = net.ithandsfree.softphone.win.ui.TextBubble(t, row.body, inbound)
                transcript.add(net.ithandsfree.softphone.win.ui.bubbleRow(t, bubble, inbound, timeText))
            }
            row.media.forEach { media ->
                val source = media.name.ifBlank { media.url.orEmpty() }
                val photo = net.ithandsfree.softphone.win.ui.PhotoBubble(t, "Photo")
                transcript.add(net.ithandsfree.softphone.win.ui.bubbleRow(t, photo, inbound, timeText))
                if (source.isBlank()) return@forEach
                val cached = synchronized(photoCache) { photoCache[source] }
                if (cached != null) {
                    photo.show(cached)
                    openPhoto(photo, source)
                    return@forEach
                }
                work("Photo") {
                    val image = loadPhoto(source)
                    // Thumbnail at 2× the bubble size, scaled once off the UI thread, so it is sharp on HiDPI.
                    val thumb = image?.let { net.ithandsfree.softphone.win.ui.fitImage(it, 520, 400) }
                    SwingUtilities.invokeLater {
                        if (image == null || thumb == null) {
                            photo.show(null)
                            return@invokeLater
                        }
                        synchronized(photoCache) {
                            photoCache[source] = thumb
                            while (photoCache.size > 60) photoCache.remove(photoCache.keys.first())
                        }
                        val wasAtEnd = transcriptAtEnd()
                        photo.show(thumb)
                        photo.parent?.parent?.let { row -> (row as? JComponent)?.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height) }
                        transcript.revalidate()
                        openPhoto(photo, source)
                        if (wasAtEnd) scrollTranscriptToEnd()
                    }
                }
            }
        }
        transcript.add(Box.createVerticalGlue())
        transcript.revalidate()
        transcript.repaint()
        // Newest message in view, as in a chat app.
        scrollTranscriptToEnd()
    }

    /** True when the transcript is scrolled to (or within 40 px of) the newest message. */
    private fun transcriptAtEnd(): Boolean {
        val bar = transcriptScroll?.verticalScrollBar ?: return true
        return bar.value + bar.visibleAmount >= bar.maximum - 40
    }

    /** Scrolls after Swing has laid the new rows out; a single invokeLater runs before photo rows have height. */
    private fun scrollTranscriptToEnd() {
        SwingUtilities.invokeLater {
            transcript.validate()
            SwingUtilities.invokeLater {
                transcriptScroll?.verticalScrollBar?.let { it.value = it.maximum }
            }
        }
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
        val key = lines.joinToString(",") { "${it.extension}:${it.label}:${it.signedIn}" }
        if (key != paintedLines) {
            paintedLines = key
            lineSwitch.removeAll()
            historyFilters.removeAll()
            allChip.putClientProperty("history", "all")
            missedChip.putClientProperty("history", "missed")
            historyFilters.add(allChip)
            lines.forEach { enrolled ->
                lineSwitch.add(callingLineButton(enrolled))
                if (lines.size > 1) historyFilters.add(historyLineChip(enrolled.extension))
            }
            historyFilters.add(missedChip)
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
        // Registration and counts on the Lines page; LinesPage.show skips the rebuild when nothing changed.
        if (currentTab == "lines") refreshLinesPage()
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

    private fun historyLineChip(extension: String): JComponent {
        val chip = net.ithandsfree.softphone.win.ui.Chip(tokens, lineLabel(extension)) {
            missedOnly = false
            historyExtension = extension
            styleRecentFilters()
            reloadRecents()
        }
        chip.putClientProperty("history", extension)
        chip.toolTipText = "Calls on $extension"
        return chip
    }

    private fun styleLineButtons() {
        val chosen = session.line?.extension
        lineRail.show(
            session.lines().mapIndexed { index, line ->
                net.ithandsfree.softphone.win.ui.LineRail.Segment(
                    line.extension,
                    lineLabel(line.extension),
                    index,
                    badge = missedSince(line.extension),
                )
            },
            chosen,
        )
        messagesLineRail.show(
            session.lines().mapIndexed { index, line ->
                net.ithandsfree.softphone.win.ui.LineRail.Segment(line.extension, lineLabel(line.extension), index)
            },
            chosen,
        )
        threadSearch.putClientProperty(
            com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,
            "Search ${lineLabel(chosen)} threads",
        )
        // Only when the line changed: this runs on every 300 ms poll.
        val convoKey = "$chosen:${lineLabel(chosen)}"
        if (::viaBar.isInitialized && convoKey != convoLineKey) {
            convoLineKey = convoKey
            showConversationFor(smsTo.text.takeIf { convoHeader.isVisible })
        }
        val index = lineIndex(chosen) ?: 0
        if (::dialPad.isInitialized) {
            val did = session.line?.did?.takeIf { it.isNotBlank() }
            dialPad.line(lineLabel(chosen), did, index)
        }
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
        if (enrolled.signedIn) {
            card.add(pin(mutedCopy("Messages sign in as ${escapeHtml(enrolled.umUsername)}")))
            card.add(pin(ghost("Sign out", expand = false) {
                session.signOut(enrolled.extension)
                note("Signed out of ${enrolled.extension}. Calls keep working")
                paintedLines = ""
                paintLineControls(SipBridge.snapshot())
            }))
        } else {
            card.add(pin(mutedCopy("Set up from a link. Sign in so messages keep working after the link expires.")))
            card.add(pin(ghost("Sign in", expand = false) { openSignIn(enrolled.extension) }))
        }
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

    /** Missed calls on one line since the user last looked at Calls (line rail badge). */
    private fun missedSince(extension: String): Int =
        recentEntries.count { it.kind == "Missed" && it.at > seenMissedAt && it.extension == extension }

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
        if (::titleBar.isInitialized) titleBar.search.isVisible = name == "app"
        rootCards.show(root, name)
    }

    private var currentTab = ""
    private var seenMissedAt = 0L
    private var taskbarLight = false
    private var badgeKey = ""
    private var flashedIncoming = false

    private fun showTab(name: String) {
        if (name != "settings" && capturingRow != null) {
            endCapture()
            Shortcut.entries.forEach { paintShortcutRow(it) }
            applyGlobalHotkeys()
        }
        currentTab = name
        appCards.show(appBody, name)
        if (::navRail.isInitialized) navRail.choose(if (name == "thread") "messages" else name)
        if (::bottomNav.isInitialized) bottomNav.choose(if (name == "thread") "messages" else name)
        if (name == "lines") {
            refreshLinesPage()
            loadLineFacts()
        }
        if (name == "settings" && ::settingsShell.isInitialized) {
            settingsShell.lines(session.lines().mapIndexed { index, line -> Triple(lineLabel(line.extension), line.extension, index) })
        }
    }

    private fun work(name: String, block: () -> Unit) {
        io.submit {
            try {
                block()
            } catch (err: SignInNeeded) {
                note(err.message.orEmpty())
            } catch (err: Exception) {
                DiagLog.app("$name failed: ${err.stackTraceToString().take(1500)}")
                note("$name failed: ${err.message ?: err.javaClass.simpleName}")
            }
        }
    }

    private fun note(line: String) {
        DiagLog.app("shown: $line")
        SwingUtilities.invokeLater {
            status.text = line
            status.foreground = if (line.contains("failed", ignoreCase = true)) coral else muted
            if (::toast.isInitialized) {
                toast.show(line, isError = line.contains("failed", ignoreCase = true) || line.contains("need a sign-in"))
            }
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
