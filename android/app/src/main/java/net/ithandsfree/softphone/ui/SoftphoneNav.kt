package net.ithandsfree.softphone.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.SoftphoneApp
import net.ithandsfree.softphone.data.AccountStore
import net.ithandsfree.softphone.notify.MessageSyncService
import net.ithandsfree.softphone.notify.SmsPollWorker
import net.ithandsfree.softphone.data.CallHistoryStore
import net.ithandsfree.softphone.data.DeviceContactsRepository
import net.ithandsfree.softphone.data.HiddenInboxStore
import net.ithandsfree.softphone.data.KeypadPrefs
import net.ithandsfree.softphone.data.LinePrefs
import net.ithandsfree.softphone.data.SkinPrefs
import net.ithandsfree.softphone.data.normalizeEnrolToken
import net.ithandsfree.softphone.sip.SipEngine
import net.ithandsfree.softphone.sip.SipRegistrationService
import net.ithandsfree.softphone.ui.components.InCallBanner
import net.ithandsfree.softphone.ui.components.SecretTextField
import net.ithandsfree.softphone.ui.components.SoftphoneBottomBar
import net.ithandsfree.softphone.ui.components.SoftphoneTab
import net.ithandsfree.softphone.ui.screens.AppearanceScreen
import net.ithandsfree.softphone.ui.screens.CallDetailScreen
import net.ithandsfree.softphone.ui.screens.CallsHubScreen
import net.ithandsfree.softphone.ui.screens.CallsSubTab
import net.ithandsfree.softphone.ui.screens.ChatScreen
import net.ithandsfree.softphone.ui.screens.ComposeMessageScreen
import net.ithandsfree.softphone.ui.screens.ContactDetailScreen
import net.ithandsfree.softphone.ui.screens.ContactsScreen
import net.ithandsfree.softphone.ui.screens.EnrollScreen
import net.ithandsfree.softphone.ui.screens.LineDetailScreen
import net.ithandsfree.softphone.ui.screens.LinesHomeScreen
import net.ithandsfree.softphone.ui.screens.MessagesScreen
import net.ithandsfree.softphone.ui.screens.SettingsScreen
import net.ithandsfree.softphone.ui.screens.SplashScreen
import net.ithandsfree.softphone.ui.screens.WelcomeCodeScreen
import net.ithandsfree.softphone.ui.screens.WelcomeLinkSentScreen
import net.ithandsfree.softphone.ui.screens.WelcomeScanScreen
import net.ithandsfree.softphone.ui.screens.WelcomeScreen
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import java.io.File

@Composable
fun SoftphoneNav(
    store: AccountStore,
    linePrefs: LinePrefs,
    hiddenInbox: HiddenInboxStore,
    skinPrefs: SkinPrefs,
    keypadPrefs: KeypadPrefs,
    contactsRepo: DeviceContactsRepository,
    callHistory: CallHistoryStore,
    sipEngine: SipEngine,
    cacheDir: File,
    deepLinkEnrolToken: String? = null,
    deepLinkDemoSeed: Uri? = null,
    openMessagesPeer: String? = null,
    onOpenMessagesPeerConsumed: () -> Unit = {},
    forceShowIncoming: Boolean = false,
    onIncomingUiShown: () -> Unit = {},
) {
    val nav = rememberNavController()
    val vm: SoftphoneViewModel = viewModel(
        factory = SoftphoneViewModel.factory(
            store,
            linePrefs,
            hiddenInbox,
            contactsRepo,
            callHistory,
            sipEngine,
            cacheDir,
        ),
    )
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    // Users open Calls to dial — Keypad is the landing sub-tab.
    var callsTab by rememberSaveable { mutableStateOf(CallsSubTab.Keypad) }
    var callWasActive by rememberSaveable { mutableStateOf(false) }
    /** True when Welcome kit was opened from Lines/Settings “Add a line”. */
    var welcomeAddingLine by rememberSaveable { mutableStateOf(false) }
    val c = IhfThemeAccess.colors
    val context = LocalContext.current
    val app = context.applicationContext as SoftphoneApp
    val inboxUnread by app.inboxUnread.collectAsState()

    fun openWelcomeKit(addingLine: Boolean) {
        welcomeAddingLine = addingLine
        nav.navigate("onboarding/welcome") {
            launchSingleTop = true
        }
    }

    /** After enrol, splash is often already popped (Welcome cleared it). popUpTo("splash") then crashes. */
    fun navigateHomeAfterEnrol() {
        runCatching {
            nav.navigate("app/messages") {
                popUpTo(nav.graph.id) { inclusive = false }
                launchSingleTop = true
            }
        }.onFailure {
            nav.navigate("app/messages") { launchSingleTop = true }
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — call path still attempted; audio needs grant */ }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // After notification grant, retry FGS (v0.3.5 could crash before this ran).
        if (granted) {
            MessageSyncService.reconcile(context)
            SipRegistrationService.reconcile(context)
        }
    }
    val sipFgsFailedMsg = stringResource(R.string.sip_fgs_failed_snack)
    val sipFgsFailedAction = stringResource(R.string.sip_fgs_failed_action)

    LaunchedEffect(state.accounts, state.splashDone) {
        val needsSip = state.accounts.any {
            it.sipExtension.isNotBlank() && it.sipPassword.isNotBlank()
        }
        if (state.splashDone && needsSip) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (state.splashDone && state.accounts.isNotEmpty() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Enrol / remove line → keep FGS + WorkManager aligned with SMS / SIP.
        if (state.splashDone) {
            MessageSyncService.reconcile(context)
            SipRegistrationService.reconcile(context)
            SmsPollWorker.schedule(context)
            val fail = app.notifyPrefs.sipFgsFailureReason()
            if (fail != null) {
                val result = snack.showSnackbar(
                    message = sipFgsFailedMsg,
                    actionLabel = sipFgsFailedAction,
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(settings) }
                }
                app.notifyPrefs.clearSipFgsFailure()
            }
        }
    }

    LaunchedEffect(inboxUnread) {
        vm.setInboxUnreadTotal(inboxUnread)
    }

    val networkEpoch by app.networkEpoch.collectAsState()
    LaunchedEffect(networkEpoch) {
        if (networkEpoch == 0L) return@LaunchedEffect
        vm.onNetworkRestored()
    }

    LaunchedEffect(forceShowIncoming) {
        if (!forceShowIncoming) return@LaunchedEffect
        vm.markSplashDone()
        vm.refreshSip()
        onIncomingUiShown()
    }

    // Active call → Calls (in-call banner). Hangup → keypad on Calls.
    LaunchedEffect(state.call.active) {
        if (state.call.active) {
            callWasActive = true
            vm.markSplashDone()
            nav.navigate("app/calls") {
                launchSingleTop = true
            }
        } else if (callWasActive) {
            callWasActive = false
            callsTab = CallsSubTab.Keypad
            if (!route.contains("chat/") && !route.contains("compose/")) {
                nav.navigate("app/calls") {
                    popUpTo("app/calls") { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // Second-line (and later) HTTPS / ihfphone enrol while a line is already enrolled.
    // Must navigate even when splash is long gone — prior code only worked cold-start.
    LaunchedEffect(deepLinkEnrolToken) {
        val token = normalizeEnrolToken(deepLinkEnrolToken)
        if (token.isBlank()) return@LaunchedEffect
        vm.setPendingEnrolToken(token)
        vm.markSplashDone()
        nav.navigate("onboarding/enroll/${Uri.encode(token)}") {
            launchSingleTop = true
        }
    }

    LaunchedEffect(deepLinkDemoSeed) {
        val uri = deepLinkDemoSeed ?: return@LaunchedEffect
        vm.seedFromDemoDeepLink(uri)
    }

    LaunchedEffect(openMessagesPeer, state.activeLineId, state.accounts) {
        val peer = openMessagesPeer ?: return@LaunchedEffect
        val lineId = state.activeLineId ?: state.accounts.firstOrNull()?.id ?: return@LaunchedEffect
        vm.markSplashDone()
        nav.navigate("app/messages/chat/$lineId/${Uri.encode(peer)}") {
            launchSingleTop = true
        }
        onOpenMessagesPeerConsumed()
    }

    LaunchedEffect(state.status) {
        state.status?.let {
            snack.showSnackbar(it)
            vm.clearStatus()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            // Chat/Compose/Welcome show inline; snack for shell routes
            if (!route.contains("chat") &&
                !route.contains("compose") &&
                !route.startsWith("onboarding/welcome")
            ) {
                snack.showSnackbar(it)
                vm.clearError()
            }
        }
    }

    val showBottomBar = route.startsWith("app/") &&
        !route.contains("chat/") &&
        !route.contains("compose/") &&
        !route.contains("line/") &&
        !route.contains("contact/") &&
        !route.contains("call/")

    val selectedTab = when {
        route.startsWith("app/calls") -> SoftphoneTab.Calls
        route.startsWith("app/messages") -> SoftphoneTab.Messages
        route.startsWith("app/lines") -> SoftphoneTab.Lines
        route.startsWith("app/settings") -> SoftphoneTab.Settings
        else -> SoftphoneTab.Messages
    }

    Scaffold(
        containerColor = c.ground,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (showBottomBar) {
                SoftphoneBottomBar(
                    selected = selectedTab,
                    messagesUnread = state.inboxUnreadTotal > 0 || inboxUnread > 0,
                ) { tab ->
                    val dest = when (tab) {
                        SoftphoneTab.Calls -> "app/calls"
                        SoftphoneTab.Messages -> "app/messages"
                        SoftphoneTab.Lines -> "app/lines"
                        SoftphoneTab.Settings -> "app/settings"
                    }
                    nav.navigate(dest) {
                        popUpTo("app/messages") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                // Scaffold insets are already applied above, so screens can add
                // imePadding() without double-counting the navigation bar.
                .consumeWindowInsets(pad)
                .background(c.ground),
        ) {
            if (state.call.active) {
                InCallBanner(
                    call = state.call,
                    onHangup = vm::hangupCall,
                    onAnswer = vm::answerCall,
                    onDecline = vm::declineCall,
                    onToggleSpeaker = vm::toggleSpeakerphone,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(c.ground),
            ) {
            NavHost(
                navController = nav,
                startDestination = "splash",
            ) {
                composable("splash") {
                    SplashScreen {
                        vm.markSplashDone()
                        if (state.accounts.isEmpty()) {
                            welcomeAddingLine = false
                            nav.navigate("onboarding/welcome") {
                                popUpTo("splash") { inclusive = true }
                            }
                        } else {
                            nav.navigate("app/messages") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    }
                }
                composable("onboarding/welcome") {
                    WelcomeScreen(
                        vm = vm,
                        addingLine = welcomeAddingLine,
                        onBack = if (welcomeAddingLine) {
                            { nav.popBackStack() }
                        } else {
                            null
                        },
                        onLinkSent = { email ->
                            nav.navigate("onboarding/welcome/sent/${Uri.encode(email)}")
                        },
                        onScanQr = { nav.navigate("onboarding/welcome/scan") },
                        onEnterCode = { nav.navigate("onboarding/welcome/code") },
                        onAdvanced = { nav.navigate("onboarding/enroll") },
                    )
                }
                composable(
                    route = "onboarding/welcome/sent/{email}",
                    arguments = listOf(navArgument("email") { type = NavType.StringType }),
                ) { entry ->
                    val email = Uri.decode(entry.arguments?.getString("email").orEmpty())
                    WelcomeLinkSentScreen(
                        email = email,
                        vm = vm,
                        onBack = { nav.popBackStack("onboarding/welcome", inclusive = false) },
                        onUseAnotherEmail = {
                            nav.popBackStack("onboarding/welcome", inclusive = false)
                        },
                        onHaveCode = {
                            nav.navigate("onboarding/welcome/code") {
                                popUpTo("onboarding/welcome") { inclusive = false }
                            }
                        },
                    )
                }
                composable("onboarding/welcome/code") {
                    WelcomeCodeScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onDone = { navigateHomeAfterEnrol() },
                        onEmailLink = {
                            nav.popBackStack("onboarding/welcome", inclusive = false)
                        },
                    )
                }
                composable("onboarding/welcome/scan") {
                    WelcomeScanScreen(
                        onBack = { nav.popBackStack() },
                        onEnterCode = {
                            nav.navigate("onboarding/welcome/code") {
                                popUpTo("onboarding/welcome/scan") { inclusive = true }
                            }
                        },
                        onTokenScanned = { token ->
                            nav.navigate("onboarding/enroll/${Uri.encode(token)}") {
                                popUpTo("onboarding/welcome") { inclusive = false }
                            }
                        },
                    )
                }
                composable("onboarding/enroll") {
                    EnrollScreen(
                        vm = vm,
                        enrolTokenFromDeepLink = deepLinkEnrolToken ?: state.pendingEnrolToken,
                        onDone = { navigateHomeAfterEnrol() },
                        onCancel = {
                            // Prefer returning to Welcome (Advanced) or prior screen;
                            // only force Lines when there is nothing to pop.
                            if (!nav.popBackStack() && state.accounts.isNotEmpty()) {
                                nav.navigate("app/lines") {
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                }
                composable(
                    route = "onboarding/enroll/{token}",
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "ihfphone://enroll/{token}" },
                        navDeepLink {
                            uriPattern =
                                "https://pbx.example.com:8443/ihf-softphone/enrol/{token}"
                        },
                        navDeepLink {
                            uriPattern =
                                "https://pbx.example.com:8443/ihf-softphone/enrol/{token}/"
                        },
                        navDeepLink {
                            uriPattern =
                                "https://pbx.example.com/ihf-softphone/enrol/{token}"
                        },
                        navDeepLink {
                            uriPattern =
                                "https://pbx.example.com/ihf-softphone/enrol/{token}/"
                        },
                        navDeepLink {
                            uriPattern = "https://pbx.example.com/ihf/enroll/{token}"
                        },
                    ),
                    arguments = listOf(navArgument("token") { type = NavType.StringType }),
                ) { entry ->
                    val token = entry.arguments?.getString("token")
                    EnrollScreen(
                        vm = vm,
                        enrolTokenFromDeepLink = token,
                        onDone = { navigateHomeAfterEnrol() },
                        onCancel = {
                            if (state.accounts.isNotEmpty()) {
                                nav.popBackStack()
                            }
                        },
                    )
                }
                // Legacy route: Add a line used to open Advanced-only form.
                // Keep the destination so old nav calls still work — redirect
                // into the same Welcome kit as cold start.
                composable("onboarding/enroll_manual") {
                    LaunchedEffect(Unit) {
                        welcomeAddingLine = true
                        nav.navigate("onboarding/welcome") {
                            popUpTo("onboarding/enroll_manual") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                composable("app/messages") {
                    MessagesScreen(
                        vm = vm,
                        onOpenChat = { accountId, peer ->
                            nav.navigate("app/messages/chat/$accountId/${Uri.encode(peer)}")
                        },
                        onCompose = { accountId ->
                            nav.navigate("app/messages/compose/$accountId?to=")
                        },
                    )
                }
                composable(
                    "app/messages/chat/{accountId}/{peer}",
                    arguments = listOf(
                        navArgument("accountId") { type = NavType.StringType },
                        navArgument("peer") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val accountId = entry.arguments?.getString("accountId")!!
                    val peer = Uri.decode(entry.arguments?.getString("peer")!!)
                    ChatScreen(vm, accountId, peer, onBack = { nav.popBackStack() })
                }
                composable(
                    route = "app/messages/compose/{accountId}?to={to}",
                    arguments = listOf(
                        navArgument("accountId") { type = NavType.StringType },
                        navArgument("to") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    val accountId = entry.arguments?.getString("accountId")!!
                    val to = entry.arguments?.getString("to")
                    ComposeMessageScreen(
                        vm = vm,
                        accountId = accountId,
                        initialTo = to,
                        onBack = { nav.popBackStack() },
                        onSent = { peer ->
                            nav.navigate("app/messages/chat/$accountId/${Uri.encode(peer)}") {
                                popUpTo("app/messages")
                            }
                        },
                    )
                }

                composable("app/calls") {
                    CallsHubScreen(
                        vm = vm,
                        keypadPrefs = keypadPrefs,
                        tab = callsTab,
                        onTabChange = { callsTab = it },
                        onOpenCallDetail = { id -> nav.navigate("app/calls/call/$id") },
                        onOpenContact = { id -> nav.navigate("app/calls/contact/$id") },
                    )
                }
                composable(
                    "app/calls/call/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    fun backToCalls(tab: CallsSubTab) {
                        callsTab = tab
                        nav.popBackStack("app/calls", inclusive = false)
                    }
                    CallDetailScreen(
                        vm = vm,
                        callId = entry.arguments?.getString("id")!!,
                        onBack = { nav.popBackStack() },
                        onMessage = { accountId, number ->
                            nav.navigate("app/messages/compose/$accountId?to=$number")
                        },
                        onOpenRecents = { backToCalls(CallsSubTab.Recents) },
                        onOpenKeypad = { backToCalls(CallsSubTab.Keypad) },
                    )
                }
                composable("app/calls/contacts") {
                    ContactsScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onOpenContact = { id -> nav.navigate("app/calls/contact/$id") },
                    )
                }
                composable(
                    "app/calls/contact/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id")!!
                    ContactDetailScreen(
                        vm = vm,
                        contactId = id,
                        onBack = { nav.popBackStack() },
                        onMessage = { accountId, number ->
                            nav.navigate("app/messages/compose/$accountId?to=$number")
                        },
                    )
                }

                composable("app/lines") {
                    LinesHomeScreen(
                        vm = vm,
                        onAdd = { openWelcomeKit(addingLine = true) },
                        onOpenLine = { id -> nav.navigate("app/lines/line/$id") },
                    )
                }
                composable(
                    "app/lines/line/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    LineDetailScreen(
                        vm = vm,
                        accountId = entry.arguments?.getString("id")!!,
                        onBack = { nav.popBackStack() },
                    )
                }

                composable("app/settings") {
                    SettingsScreen(
                        vm = vm,
                        skinPrefs = skinPrefs,
                        keypadPrefs = keypadPrefs,
                        notifyPrefs = app.notifyPrefs,
                        onOpenLine = { id -> nav.navigate("app/lines/line/$id") },
                        onEnrol = { openWelcomeKit(addingLine = true) },
                        onOpenAppearance = { nav.navigate("app/settings/appearance") },
                    )
                }
                composable("app/settings/appearance") {
                    AppearanceScreen(
                        skinPrefs = skinPrefs,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            }
        }
    }

    state.pendingSipSecretAccountId?.let { pendingSipId ->
        SipSecretPromptDialog(
            accountId = pendingSipId,
            label = state.accounts.firstOrNull { it.id == pendingSipId }?.label,
            extension = state.accounts.firstOrNull { it.id == pendingSipId }?.sipExtension.orEmpty(),
            onSave = { ext, secret -> vm.saveSipCredentials(pendingSipId, ext, secret) },
            onSync = { vm.syncSipCredentials(pendingSipId) },
            onDismiss = { vm.dismissSipSecretPrompt() },
        )
    }
}

@Composable
private fun SipSecretPromptDialog(
    accountId: String,
    label: String?,
    extension: String,
    onSave: (extension: String, secret: String) -> Unit,
    onSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sipExt by remember(accountId) { mutableStateOf(extension) }
    var sipSecret by remember(accountId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SIP secret") },
        text = {
            Column {
                Text(
                    "Calling needs the FreePBX SIP secret for ${label ?: "this line"}. " +
                        "We can sync it over your enrol token, or you can enter it once — " +
                        "it is saved encrypted on this device.",
                )
                OutlinedTextField(
                    value = sipExt,
                    onValueChange = { sipExt = it },
                    label = { Text("Extension") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SecretTextField(
                    value = sipSecret,
                    onValueChange = { sipSecret = it },
                    label = "SIP secret",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(sipExt, sipSecret) },
                enabled = sipExt.isNotBlank() && sipSecret.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onSync) { Text("Sync from PBX") }
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}
