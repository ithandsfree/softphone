package net.ithandsfree.softphone

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import net.ithandsfree.softphone.data.normalizeEnrolToken
import net.ithandsfree.softphone.sip.IncomingCallNotifier
import net.ithandsfree.softphone.ui.SoftphoneNav
import net.ithandsfree.softphone.ui.theme.IhfTheme
import net.ithandsfree.softphone.ui.theme.SoftphoneSkins

class MainActivity : ComponentActivity() {
    /**
     * Warm deep links must update Compose state — reading [intent] only in
     * [onCreate] left second-line HTTPS / ihfphone enrol URLs stuck when the
     * activity was already alive ([onNewIntent] / singleTask).
     */
    private val deepLinkEnrolToken = mutableStateOf<String?>(null)
    private val deepLinkDemoSeed = mutableStateOf<Uri?>(null)
    private val openMessagesPeer = mutableStateOf<String?>(null)
    private val showIncomingCall = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDeepLinkIntent(intent)
        val app = application as SoftphoneApp
        setContent {
            val skinId by app.skinPrefs.skinId.collectAsState()
            val skin = SoftphoneSkins.resolve(skinId)
            val enrolToken by deepLinkEnrolToken
            val demoSeed by deepLinkDemoSeed
            val messagesPeer by openMessagesPeer
            val incoming by showIncomingCall
            IhfTheme(skin = skin) {
                SoftphoneNav(
                    store = app.accountStore,
                    linePrefs = app.linePrefs,
                    hiddenInbox = app.hiddenInbox,
                    skinPrefs = app.skinPrefs,
                    keypadPrefs = app.keypadPrefs,
                    contactsRepo = app.contactsRepo,
                    callHistory = app.callHistory,
                    sipEngine = app.sipEngine,
                    cacheDir = cacheDir,
                    deepLinkEnrolToken = enrolToken,
                    deepLinkDemoSeed = demoSeed,
                    openMessagesPeer = messagesPeer,
                    onOpenMessagesPeerConsumed = { openMessagesPeer.value = null },
                    forceShowIncoming = incoming,
                    onIncomingUiShown = { showIncomingCall.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDeepLinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Clear stale ring UI if the INVITE already ended while we were away.
        val app = application as SoftphoneApp
        if (!app.sipEngine.callSnapshot().active) {
            IncomingCallNotifier.cancel(this)
        }
    }

    private fun applyDeepLinkIntent(intent: Intent?) {
        if (intent == null) return
        enrolTokenFrom(intent)?.let { deepLinkEnrolToken.value = it }
        demoSeedFrom(intent)?.let { deepLinkDemoSeed.value = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_MESSAGES, false)) {
            openMessagesPeer.value = intent.getStringExtra(EXTRA_OPEN_PEER)
        }
        val answer = intent.getBooleanExtra(EXTRA_ANSWER_CALL, false)
        val incoming = intent.getBooleanExtra(EXTRA_INCOMING_CALL, false) || answer
        if (answer) {
            // Answer here (Activity context) so media + UI start together.
            // Consume the extra so rotation / redeliver does not re-answer.
            intent.removeExtra(EXTRA_ANSWER_CALL)
            val app = application as SoftphoneApp
            runCatching { app.sipEngine.answer() }
            IncomingCallNotifier.cancel(this)
        }
        if (incoming) {
            showIncomingCall.value = true
            unlockForIncomingCall()
        }
    }

    private fun unlockForIncomingCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        val kg = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            kg?.requestDismissKeyguard(this, null)
        }
    }

    private fun enrolTokenFrom(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        if (data.scheme == "ihfphone" && data.host.equals("demo-seed", ignoreCase = true)) {
            return null
        }
        // Shared normalizer accepts bare tokens, ihfphone://, and HTTPS enrol URLs
        // (with or without trailing slash / :8443).
        return normalizeEnrolToken(data.toString()).takeIf { it.isNotBlank() }
    }

    private fun demoSeedFrom(intent: Intent?): Uri? {
        val data = intent?.data ?: return null
        return data.takeIf {
            it.scheme == "ihfphone" && it.host.equals("demo-seed", ignoreCase = true)
        }
    }

    companion object {
        const val EXTRA_OPEN_MESSAGES = "open_messages"
        const val EXTRA_OPEN_PEER = "open_peer"
        const val EXTRA_INCOMING_CALL = "incoming_call"
        /** Notification Answer action — answer SIP and show Calls in-call UI. */
        const val EXTRA_ANSWER_CALL = "answer_call"
    }
}
