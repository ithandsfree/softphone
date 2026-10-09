package net.ithandsfree.softphone.win

import java.io.File

/**
 * One enrolled line: BFF session, SIP registration, a single call, and a text.
 * GPL-2.0.
 */
data class EnrolledLine(
    val token: String,
    val did: String,
    val extension: String,
    val sipPassword: String,
    val sipDomain: String,
    val displayName: String,
    /** User Manager sign-in kept for this line, so the app can get a new bearer when the old one expires. */
    val umUsername: String = "",
    val umPassword: String = "",
    /** Optional custom name for the line. Empty shows the extension, which is what the person knows the line by. */
    val label: String = "",
) {
    val signedIn: Boolean get() = umUsername.isNotBlank() && umPassword.isNotBlank()

    override fun toString(): String = "line $extension did $did"
}

class PhoneSession(host: HostConfig) {
    private var host = host
    private var bff: BffClient? = host.apiBase.takeIf { it.isNotBlank() }?.let { BffClient(it) }
    private val book = mutableListOf<EnrolledLine>()
    private var active = 0

    val line: EnrolledLine? get() = book.getOrNull(active)

    fun lines(): List<EnrolledLine> = book.toList()

    /** Community setup types the BFF URL and SIP domain before enrol. */
    fun use(next: HostConfig) {
        if (next.apiBase.isBlank() || next.sipDomain.isBlank()) {
            throw IllegalStateException("Enter the PBX softphone URL and the SIP domain.")
        }
        host = next
        bff = BffClient(next.apiBase)
    }

    private fun api(): BffClient = bff ?: error("Enter the PBX softphone URL and the SIP domain.")

    fun health(): Boolean = api().health()

    fun requestEnrol(email: String): String {
        val resp = api().requestEnrol(email)
        return resp.status ?: "check_inbox"
    }

    fun enrol(raw: String): EnrolledLine {
        val token = normalizeEnrolToken(raw)
        if (!looksLikeEnrolToken(token)) {
            throw IllegalArgumentException("Paste the setup link or the enrol token from the welcome message.")
        }
        val session = api().session(token)
        val enrolled = provision(session)
        remember(enrolled)
        return enrolled
    }

    /**
     * Sets up a line with a User Manager sign-in instead of a setup link. The sign-in is kept (sealed) so the
     * app can get a new bearer when the old one expires, as the Android client does.
     */
    fun signIn(username: String, password: String): EnrolledLine {
        if (username.isBlank() || password.isEmpty()) throw IllegalArgumentException("Enter your username and password.")
        val login = api().login(username, password)
        val enrolled = provision(login).copy(umUsername = username.trim(), umPassword = password)
        remember(enrolled)
        return enrolled
    }

    /** Adds a User Manager sign-in to a line set up from a link, so its messages keep working. */
    fun attachSignIn(extension: String, username: String, password: String): EnrolledLine {
        if (username.isBlank() || password.isEmpty()) throw IllegalArgumentException("Enter your username and password.")
        val index = book.indexOfFirst { it.extension == extension }
        if (index < 0) error("Line $extension is not on this PC.")
        val login = api().login(username, password)
        val target = book[index]
        if (login.lines.none { normalizeDidDigits(it.did) == normalizeDidDigits(target.did) }) {
            error("That account does not have line $extension.")
        }
        val updated = target.copy(token = login.token, umUsername = username.trim(), umPassword = password)
        replace(index, updated)
        return updated
    }

    /**
     * Swaps a line's setup-link token for the server's long-lived device token (BFF 2026-10 and later), so
     * messages keep working with no sign-in. A server that predates device tokens returns no token; the line
     * keeps what it has. Returns the extensions that were upgraded.
     */
    fun upgradeTokens(): List<String> {
        val upgraded = mutableListOf<String>()
        book.toList().forEach { line ->
            val fresh = runCatching { api().session(line.token).token }.getOrNull()
            if (!fresh.isNullOrBlank() && fresh != line.token) {
                val index = book.indexOfFirst { it.extension == line.extension }
                if (index >= 0) {
                    replace(index, book[index].copy(token = fresh))
                    upgraded += line.extension
                }
            }
        }
        return upgraded
    }

    /** PBX call history for a line. A 403 means UCP Call History is off for this user or extension. */
    fun pbxCalls(target: EnrolledLine, limit: Int = 100): CallsResponse =
        authed(target) { token -> api().calls(token, target.did, limit) }

    /** A call recording's audio, if the user's UCP permissions allow playback (or download). */
    fun recording(target: EnrolledLine, callId: String, download: Boolean = false): ByteArray =
        authed(target) { token -> api().recording(token, target.did, callId, download) }

    /** Voicemail for a line, if the user's UCP Voicemail settings allow it. */
    fun voicemail(target: EnrolledLine): VoicemailResponse =
        authed(target) { token -> api().voicemail(token, target.did) }

    fun voicemailCount(target: EnrolledLine): VoicemailCount =
        authed(target) { token -> api().voicemailCount(token, target.did) }

    fun voicemailAudio(target: EnrolledLine, id: String, download: Boolean = false): ByteArray =
        authed(target) { token -> api().voicemailAudio(token, target.did, id, download) }

    fun voicemailHeard(target: EnrolledLine, id: String) =
        authed(target) { token -> api().voicemailHeard(token, target.did, id) }

    fun deleteVoicemail(target: EnrolledLine, id: String) =
        authed(target) { token -> api().deleteVoicemail(token, target.did, id) }

    /** What the BFF says about one line: capabilities set by the admin, and live DND. */
    fun lineInfo(target: EnrolledLine): LineInfo? = authed(target) { token ->
        api().lines(token).lines.firstOrNull { normalizeDidDigits(it.did) == normalizeDidDigits(target.did) }
    }

    /**
     * Removes a line from this PC: its stored secrets go with it. Voice restarts so the remaining line keeps the
     * account slot that matches its place in the list (PJSIP has no per-account remove in ihf_sip). Refused during
     * a call. Nothing changes on the PBX.
     */
    fun removeLine(extension: String, userAgent: String) {
        val snap = SipBridge.snapshot()
        if (snap.callActive || snap.incoming) error("Finish the call before removing a line.")
        val index = book.indexOfFirst { it.extension == extension }
        if (index < 0) return
        // Revoke this PC's token on the server first (best effort: offline removal still works locally).
        runCatching { api().logout(book[index].token) }
        val keepDefault = book.getOrNull(active)?.extension?.takeIf { it != extension }
        book.removeAt(index)
        active = book.indexOfFirst { it.extension == keepDefault }.coerceAtLeast(0)
        if (book.isEmpty()) {
            LineBook.save(emptyList(), "")
            LineStore.clear()
        } else {
            LineBook.save(book.toList(), book[active].extension)
        }
        SipBridge.stop()
        if (book.isNotEmpty()) registerAll(userAgent)
    }

    /** Optional custom name for a line. Blank, or the extension itself, goes back to showing the extension. */
    fun rename(extension: String, label: String) {
        val index = book.indexOfFirst { it.extension == extension }
        if (index < 0) return
        val name = label.trim().take(24).takeIf { it != extension }.orEmpty()
        replace(index, book[index].copy(label = name))
    }

    /** Forgets the User Manager sign-in for a line. Calls keep working; messages need a sign-in again later. */
    fun signOut(extension: String) {
        val index = book.indexOfFirst { it.extension == extension }
        if (index < 0) return
        replace(index, book[index].copy(umUsername = "", umPassword = ""))
    }

    /** Picks the line this setup adds, fetches its SIP secret, and builds the stored line. */
    private fun provision(session: LoginResponse): EnrolledLine {
        val pick = pickLineToAdd(session.lines, book.map { it.did })
            ?: throw IllegalStateException("That account has no line.")
        val creds = api().sipCredentials(session.token, pick.did)
        val extension = creds.extension?.trim().orEmpty().ifBlank { pick.extension?.trim().orEmpty() }
        val secret = creds.secret?.trim().orEmpty()
        if (!creds.ok || extension.isBlank() || secret.isBlank()) {
            throw IllegalStateException(creds.error ?: "SIP credentials were not returned.")
        }
        val domain = host.sipDomain.trim()
        if (domain.isBlank()) throw IllegalStateException("SIP domain is empty.")
        return EnrolledLine(
            token = session.token,
            did = pick.did,
            extension = extension,
            sipPassword = secret,
            sipDomain = domain,
            displayName = session.user?.displayname?.ifBlank { null } ?: session.user?.username ?: extension,
        )
    }

    private val authLock = Any()

    /**
     * Runs a BFF call with the line's bearer. On 401 with a kept sign-in, signs in again, stores the new bearer,
     * and retries once. Without a kept sign-in, a 401 becomes [SignInNeeded].
     */
    private fun <T> authed(target: EnrolledLine, call: (String) -> T): T {
        val current = book.firstOrNull { it.extension == target.extension } ?: target
        try {
            return call(current.token)
        } catch (err: BffException) {
            if (err.httpCode != 401) throw err
        }
        val renewed = synchronized(authLock) {
            val latest = book.firstOrNull { it.extension == target.extension } ?: current
            when {
                latest.token != current.token -> latest
                !latest.signedIn -> throw SignInNeeded(latest.extension)
                else -> {
                    val login = try {
                        api().login(latest.umUsername, latest.umPassword)
                    } catch (err: BffException) {
                        if (err.httpCode == 401) throw SignInNeeded(latest.extension, passwordChanged = true)
                        throw err
                    }
                    val next = latest.copy(token = login.token)
                    val index = book.indexOfFirst { it.extension == latest.extension }
                    if (index >= 0) replace(index, next)
                    next
                }
            }
        }
        return call(renewed.token)
    }

    private fun replace(index: Int, line: EnrolledLine) {
        book[index] = line
        LineBook.save(book.toList(), book.getOrNull(active)?.extension.orEmpty())
    }

    fun restore(saved: EnrolledLine) {
        remember(saved)
    }

    fun restoreAll(saved: List<EnrolledLine>, defaultExtension: String) {
        book.clear()
        book.addAll(saved.take(2))
        val index = book.indexOfFirst { it.extension == defaultExtension }
        active = if (index >= 0) index else 0
    }

    fun choose(extension: String) {
        val index = book.indexOfFirst { it.extension == extension }
        if (index < 0) return
        active = index
        SipBridge.useLine(index)
        LineBook.save(book.toList(), extension)
    }

    private fun remember(enrolled: EnrolledLine) {
        val found = book.indexOfFirst { it.extension == enrolled.extension }
        if (found >= 0) {
            // Setting a line up again from a link keeps a sign-in already stored for it.
            val kept = book[found]
            val withSignIn = if (!enrolled.signedIn && kept.signedIn) {
                enrolled.copy(umUsername = kept.umUsername, umPassword = kept.umPassword)
            } else {
                enrolled
            }
            book[found] = if (withSignIn.label.isBlank()) withSignIn.copy(label = kept.label) else withSignIn
            active = found
        } else if (book.size >= 2) {
            throw IllegalStateException("This phone already has two lines.")
        } else {
            book.add(enrolled)
            active = book.lastIndex
        }
        LineBook.save(book.toList(), enrolled.extension)
    }

    fun register(
        enrolled: EnrolledLine = line ?: error("Enrol a line first."),
        userAgent: String = SipBridge.USER_AGENT,
    ): SipBridge.Snapshot {
        val missingVoice = SipBridge.loadError
        if (missingVoice != null) error(missingVoice)
        val ca = caBundleFile()
        writeCaBundle(ca)
        val started = SipBridge.start(ca, userAgent)
        val snap = SipBridge.snapshot()
        if (started != 0 || !snap.started) {
            error(snap.lastError.ifBlank { "PJSIP did not start ($started)." })
        }
        AudioPrefs.applySaved()
        if (!AudioPrefs.voiceProcessing()) SipBridge.setEchoCancel(false)
        if (AudioPrefs.callVolume() != 100) SipBridge.setCallVolume(AudioPrefs.callVolume())
        SipBridge.setCallWaiting(WindowPrefs.callWaiting())
        val signalling = preferredSignalling(snap.tlsUp, snap.tcpUp)
            ?: error("SIP needs TLS or TCP. UDP is not used.")
        val idUri = sipAccountIdUri(enrolled.extension, enrolled.sipDomain)
        val regUri = sipRegistrarUri(enrolled.sipDomain, signalling)
        val code = SipBridge.register(idUri, regUri, enrolled.extension, enrolled.sipPassword, signalling)
        if (code != 0) {
            val after = SipBridge.snapshot()
            error(after.lastError.ifBlank { "REGISTER was not sent ($code)." })
        }
        return SipBridge.snapshot()
    }

    fun registerAll(userAgent: String = SipBridge.USER_AGENT): SipBridge.Snapshot {
        val all = lines()
        if (all.isEmpty()) error("Enrol a line first.")
        var snap = register(all.first(), userAgent)
        all.drop(1).forEach { snap = register(it, userAgent) }
        SipBridge.useLine(active)
        return snap
    }

    fun placeCall(destination: String) {
        val enrolled = line ?: error("Enrol a line first.")
        val digits = outboundDialDigits(destination)
        if (digits.isBlank()) throw IllegalArgumentException("Empty destination")
        val index = book.indexOfFirst { it.extension == enrolled.extension }.coerceAtLeast(0)
        SipBridge.useLine(index)
        val snap = SipBridge.snapshot()
        val ready = if (index == 0) snap.registered else snap.secondRegistered
        if (!ready) {
            throw IllegalStateException("SIP not registered (${enrolled.extension}) — wait for Voice ready, then try again")
        }
        val code = SipBridge.call(
            sipCallUri(
                destination,
                enrolled.sipDomain,
                snap.transport ?: error("SIP needs TLS or TCP. UDP is not used."),
            ),
        )
        if (code != 0) {
            val after = SipBridge.snapshot()
            error(after.lastError.ifBlank { "Call failed ($code)." })
        }
    }

    fun answer() {
        val code = SipBridge.answer()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Answer failed ($code)." })
    }

    fun decline() {
        val code = SipBridge.decline()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Decline failed ($code)." })
    }

    fun hangup() {
        val code = SipBridge.hangup()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Hangup failed ($code)." })
    }

    fun hold() {
        val code = SipBridge.hold()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Hold failed ($code)." })
    }

    fun resume() {
        val code = SipBridge.resume()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Resume failed ($code)." })
    }

    fun transfer(destination: String) {
        val enrolled = line ?: error("Enrol a line first.")
        val snap = SipBridge.snapshot()
        val code = SipBridge.transfer(
            sipCallUri(
                destination,
                enrolled.sipDomain,
                snap.transport ?: error("SIP needs TLS or TCP. UDP is not used."),
            ),
        )
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Transfer failed ($code)." })
    }

    fun consult(destination: String) {
        val enrolled = line ?: error("Enrol a line first.")
        val snap = SipBridge.snapshot()
        val code = SipBridge.consult(
            sipCallUri(destination, enrolled.sipDomain, snap.transport ?: error("SIP needs TLS or TCP. UDP is not used.")),
        )
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Could not call the other person ($code)." })
    }

    /** Call waiting: hold the current call and answer the one ringing beside it. */
    fun waitingAnswer() {
        if (SipBridge.waitingAnswer() != 0) error(SipBridge.snapshot().lastError.ifBlank { "Could not answer the second call." })
    }

    /** Declines the ringing second call (busy: the PBX carries on to other devices, then voicemail), or ends the call on hold. */
    fun waitingEnd() {
        if (SipBridge.waitingEnd() != 0) error(SipBridge.snapshot().lastError.ifBlank { "Could not end the other call." })
    }

    /** Holds the current call and resumes the one on hold. */
    fun swapCalls() {
        if (SipBridge.swapCalls() != 0) error(SipBridge.snapshot().lastError.ifBlank { "Could not swap calls." })
    }

    fun consultFinish() {
        val code = SipBridge.consultFinish()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Transfer was not completed ($code)." })
    }

    fun consultCancel() {
        val code = SipBridge.consultCancel()
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Could not return to the first call ($code)." })
    }

    fun pbxDnd(): Boolean {
        val enrolled = line ?: return false
        return pbxDnd(enrolled)
    }

    fun setPbxDnd(enabled: Boolean) {
        setPbxDnd(line ?: error("Enrol a line first."), enabled)
    }

    fun setPbxDnd(target: EnrolledLine, enabled: Boolean) {
        authed(target) { token -> api().setDnd(token, target.did, enabled) }
    }

    fun pbxDnd(target: EnrolledLine): Boolean = authed(target) { token -> api().dnd(token, target.did) }

    fun dtmf(digits: String) {
        val code = SipBridge.dtmf(digits)
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Keypad tone failed ($code)." })
    }

    fun sendText(to: String, body: String): String {
        val enrolled = line ?: error("Enrol a line first.")
        if (to.isBlank() || body.isBlank()) throw IllegalArgumentException("Enter a number and a message.")
        authed(enrolled) { token -> api().sendSms(token, enrolled.did, to.trim(), body) }
        return "Sent"
    }

    internal fun sendPhoto(to: String, photo: MmsPhoto): String {
        val enrolled = line ?: error("Enrol a line first.")
        if (to.isBlank()) throw IllegalArgumentException("Enter a number for the photo.")
        authed(enrolled) { token -> api().sendMedia(token, enrolled.did, to.trim(), photo) }
        return "Sent"
    }

    fun threads(): List<ThreadInfo> {
        val enrolled = line ?: error("Enrol a line first.")
        return authed(enrolled) { token -> api().listThreads(token, enrolled.did).threads }
    }

    fun conversation(peer: String): List<MessageInfo> {
        val enrolled = line ?: error("Enrol a line first.")
        return authed(enrolled) { token -> api().listMessages(token, enrolled.did, peer).messages }
    }

    fun media(nameOrUrl: String): ByteArray {
        val enrolled = line ?: error("Enrol a line first.")
        return authed(enrolled) { token -> api().mediaBytes(token, nameOrUrl) }
    }
}

/** The BFF refused the line's bearer and the app has no sign-in to get a new one. */
class SignInNeeded(val extension: String, val passwordChanged: Boolean = false) : Exception(
    if (passwordChanged) {
        "The PBX refused the sign-in for line $extension. Set the line up again from its setup email."
    } else {
        "Messages on line $extension need setting up again. Open the setup email for that line on this PC."
    },
)

/**
 * The line a setup adds: the first line with a number that is not already on this PC, else the first line.
 * Matches Android, so the second link in a welcome email adds the second line instead of the first one again.
 */
internal fun pickLineToAdd(offered: List<LineInfo>, onDevice: List<String>): LineInfo? {
    val usable = offered.filter { it.did.isNotBlank() }
    val used = onDevice.map(::normalizeDidDigits).toSet()
    return usable.firstOrNull { normalizeDidDigits(it.did) !in used } ?: usable.firstOrNull()
}
