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
) {
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
        val first = session.lines.firstOrNull { it.did.isNotBlank() }
            ?: throw IllegalStateException("That token has no line.")
        val creds = api().sipCredentials(session.token, first.did)
        val extension = creds.extension?.trim().orEmpty().ifBlank { first.extension?.trim().orEmpty() }
        val secret = creds.secret?.trim().orEmpty()
        if (!creds.ok || extension.isBlank() || secret.isBlank()) {
            throw IllegalStateException(creds.error ?: "SIP credentials were not returned.")
        }
        val domain = host.sipDomain.trim()
        if (domain.isBlank()) throw IllegalStateException("SIP domain is empty.")
        val enrolled = EnrolledLine(
            token = session.token,
            did = first.did,
            extension = extension,
            sipPassword = secret,
            sipDomain = domain,
            displayName = session.user?.displayname ?: session.user?.username ?: extension,
        )
        remember(enrolled)
        return enrolled
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
            book[found] = enrolled
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
        return api().dnd(enrolled.token, enrolled.did)
    }

    fun setPbxDnd(enabled: Boolean) {
        setPbxDnd(line ?: error("Enrol a line first."), enabled)
    }

    fun setPbxDnd(target: EnrolledLine, enabled: Boolean) {
        api().setDnd(target.token, target.did, enabled)
    }

    fun pbxDnd(target: EnrolledLine): Boolean = api().dnd(target.token, target.did)

    fun dtmf(digits: String) {
        val code = SipBridge.dtmf(digits)
        if (code != 0) error(SipBridge.snapshot().lastError.ifBlank { "Keypad tone failed ($code)." })
    }

    fun sendText(to: String, body: String): String {
        val enrolled = line ?: error("Enrol a line first.")
        if (to.isBlank() || body.isBlank()) throw IllegalArgumentException("Enter a number and a message.")
        api().sendSms(enrolled.token, enrolled.did, to.trim(), body)
        return "Sent"
    }

    internal fun sendPhoto(to: String, photo: MmsPhoto): String {
        val enrolled = line ?: error("Enrol a line first.")
        if (to.isBlank()) throw IllegalArgumentException("Enter a number for the photo.")
        api().sendMedia(enrolled.token, enrolled.did, to.trim(), photo)
        return "Sent"
    }

    fun threads(): List<ThreadInfo> {
        val enrolled = line ?: error("Enrol a line first.")
        return api().listThreads(enrolled.token, enrolled.did).threads
    }

    fun conversation(peer: String): List<MessageInfo> {
        val enrolled = line ?: error("Enrol a line first.")
        return api().listMessages(enrolled.token, enrolled.did, peer).messages
    }

    fun media(nameOrUrl: String): ByteArray {
        val enrolled = line ?: error("Enrol a line first.")
        return api().mediaBytes(enrolled.token, nameOrUrl)
    }
}
