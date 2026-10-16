package net.ithandsfree.softphone.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.CarIcon
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import net.ithandsfree.softphone.R
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ithandsfree.softphone.SoftphoneApp
import net.ithandsfree.softphone.data.CallDirection
import net.ithandsfree.softphone.data.CallRecord
import net.ithandsfree.softphone.data.DeviceContact
import net.ithandsfree.softphone.data.callerName
import net.ithandsfree.softphone.data.canCallBack
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.data.formatVoicemailWhen
import net.ithandsfree.softphone.sip.SipEngine
import net.ithandsfree.softphone.sip.outboundDialDigits

/**
 * Android Auto calling app (category CALLING). The car screen has Recents, Contacts, Voicemail and Search (type a
 * name or a number; the car's keyboard works only when parked, a host rule). Recents call back from the call's own
 * line; new calls use the default line. During a call Android Auto shows its own in-call view, driven by the Telecom
 * integration (TelecomBridge), so answer, hang up, mute and audio route work from the car.
 */
class IhfCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = HomeCarScreen(carContext)
    }
}

/** Places calls for the car screens: same path as the phone's Call button, registering the line first if needed. */
internal class CarDialer(private val carContext: CarContext, private val scope: CoroutineScope) {
    private val app = carContext.applicationContext as SoftphoneApp

    /** The line new calls use: the default line, else the first one. */
    fun defaultLineId(): String? {
        val accounts = runCatching { app.accountStore.list() }.getOrDefault(emptyList())
        val active = runCatching { app.linePrefs.activeLineId() }.getOrNull()
        return (accounts.firstOrNull { it.id == active } ?: accounts.firstOrNull())?.id
    }

    fun place(lineId: String?, number: String, label: String) {
        val digits = outboundDialDigits(number)
        if (digits.isBlank()) return
        if (lineId == null) {
            CarToast.makeText(carContext, "Set up a line in IHF Phone on your phone", CarToast.LENGTH_LONG).show()
            return
        }
        CarToast.makeText(carContext, "Calling $label", CarToast.LENGTH_SHORT).show()
        scope.launch {
            val engine: SipEngine = app.sipEngine
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    engine.setActiveAccount(lineId)
                    if (!engine.isLineRegistered(lineId)) {
                        engine.reregisterAll()
                        val deadline = System.currentTimeMillis() + 8_000L
                        while (System.currentTimeMillis() < deadline && !engine.isLineRegistered(lineId)) delay(250)
                    }
                    engine.call(digits)
                }.exceptionOrNull()
            }
            if (error != null) {
                CarToast.makeText(carContext, "Call failed: ${error.message ?: "line not ready"}", CarToast.LENGTH_LONG).show()
            }
        }
    }
}

/** Base for car screens: a coroutine scope tied to the screen, and the dialer. */
internal abstract class CarScreen(carContext: CarContext) : Screen(carContext) {
    protected val app = carContext.applicationContext as SoftphoneApp
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected val dialer = CarDialer(carContext, scope)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = scope.cancel()
        })
    }

    /** Contacts with at least one number, A to Z (needs the Contacts permission granted on the phone). */
    protected suspend fun loadContacts(): List<DeviceContact> = withContext(Dispatchers.IO) {
        runCatching { app.contactsRepo.loadContacts() }.getOrDefault(emptyList())
            .filter { c -> c.phones.any { canCallBack(it.number) } }
            .sortedBy { it.displayName.lowercase() }
    }

    protected fun contactRow(c: DeviceContact): Row {
        val phones = c.phones.filter { canCallBack(it.number) }
        val detail = if (phones.size == 1) {
            listOf(phones[0].label, formatDidForDisplay(phones[0].number)).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            "${phones.size} numbers"
        }
        return Row.Builder()
            .setTitle(c.displayName.ifBlank { formatDidForDisplay(phones.first().number) })
            .addText(detail)
            .setOnClickListener {
                if (phones.size == 1) {
                    dialer.place(dialer.defaultLineId(), phones[0].number, c.displayName)
                } else {
                    screenManager.push(NumberPickCarScreen(carContext, c))
                }
            }
            .build()
    }

    protected fun searchAction(): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setTitle("Search")
                .setOnClickListener { screenManager.push(SearchCarScreen(carContext)) }
                .build(),
        )
        .build()
}

/** Home: Recents (call back from the call's line), Contacts, Voicemail; Search in the header. */
internal class HomeCarScreen(carContext: CarContext) : CarScreen(carContext) {
    /** Redraw when a call starts or ends, so a call that just finished appears in Recents. */
    private val engineObserver = SipEngine.Listener { invalidate() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = app.sipEngine.addObserver(engineObserver)
            override fun onStop(owner: LifecycleOwner) = app.sipEngine.removeObserver(engineObserver)
        })
    }

    /** Recents and Contacts tabs need car host API 6 (current Android Auto); older hosts keep the single list. */
    private val useTabs by lazy {
        runCatching { carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_6 }.getOrDefault(false)
    }
    private var activeTab = TAB_RECENTS
    private var contacts: List<DeviceContact>? = null
    private var contactsRequested = false

    override fun onGetTemplate(): Template {
        if (!useTabs) return singleList()
        if (!contactsRequested) {
            contactsRequested = true
            scope.launch {
                contacts = loadContacts()
                invalidate()
            }
        }
        return runCatching { tabs() }.getOrElse { singleList() }
    }

    private fun lineLabels(): Map<String, String> =
        runCatching { app.accountStore.list() }.getOrDefault(emptyList())
            .associate { it.id to it.displayName.ifBlank { it.umUsername } }

    private fun recentCalls(lineLabel: Map<String, String>, max: Int): List<CallRecord> =
        runCatching { app.callHistory.list() }.getOrDefault(emptyList())
            .filter { canCallBack(it.peerNumber) && it.lineId in lineLabel }
            .distinctBy { it.peerNumber.takeLast(10) }
            .take(max)

    private fun voicemailRow(lineLabel: Map<String, String>): Row? {
        val line = dialer.defaultLineId() ?: return null
        return Row.Builder()
            .setTitle("Voicemail")
            .addText("Call your mailbox on ${lineLabel[line]}")
            .setOnClickListener { dialer.place(line, VOICEMAIL_CODE, "Voicemail") }
            .build()
    }

    /** Tabs: Recents (voicemail first, then calls) and Contacts (search first, then A to Z). */
    private fun tabs(): Template {
        val lineLabel = lineLabels()
        val recentList = ItemList.Builder().setNoItemsMessage("No recent calls")
        voicemailRow(lineLabel)?.let { recentList.addItem(it) }
        recentCalls(lineLabel, TAB_RECENTS_MAX).forEach {
            recentList.addItem(recentRow(it, lineLabel, multiLine = lineLabel.size > 1))
        }

        val list = contacts
        val contactsContent = if (list == null) {
            ListTemplate.Builder().setLoading(true).build()
        } else {
            val items = ItemList.Builder()
            items.addItem(
                Row.Builder()
                    .setTitle("Search contacts")
                    .addText("By name or number, or type a number to call")
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(SearchCarScreen(carContext)) }
                    .build(),
            )
            if (list.isEmpty()) {
                items.addItem(Row.Builder().setTitle("No contacts with a phone number").addText("Allow Contacts for IHF Phone on your phone").build())
            }
            list.take(TAB_CONTACTS_MAX).forEach { items.addItem(contactRow(it)) }
            ListTemplate.Builder().setSingleList(items.build()).build()
        }
        val content = if (activeTab == TAB_CONTACTS) contactsContent else ListTemplate.Builder().setSingleList(recentList.build()).build()

        return TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTab = tabContentId
                invalidate()
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab(TAB_RECENTS, "Recents", R.drawable.ic_car_recents))
            .addTab(tab(TAB_CONTACTS, "Contacts", R.drawable.ic_car_contacts))
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(activeTab)
            .build()
    }

    private fun tab(id: String, title: String, icon: Int): Tab = Tab.Builder()
        .setContentId(id)
        .setTitle(title)
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
        .build()

    /** Older car hosts: one list with Recents, then Contacts and Voicemail; Search in the header. */
    private fun singleList(): Template {
        val lineLabel = lineLabels()
        val recents = recentCalls(lineLabel, MAX_RECENTS)

        val recentList = ItemList.Builder().setNoItemsMessage("No recent calls")
        recents.forEach { r -> recentList.addItem(recentRow(r, lineLabel, multiLine = lineLabel.size > 1)) }

        val more = ItemList.Builder()
        more.addItem(
            Row.Builder()
                .setTitle("Contacts")
                .addText("Call someone from your phone's contacts")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(ContactsCarScreen(carContext)) }
                .build(),
        )
        voicemailRow(lineLabel)?.let { more.addItem(it) }

        @Suppress("DEPRECATION")
        val builder = ListTemplate.Builder()
            .setTitle("IHF Phone")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(searchAction())
        // A section may not be empty (with no calls yet, 0.4.19 showed an error screen here).
        if (recents.isNotEmpty()) builder.addSectionedList(SectionedItemList.create(recentList.build(), "Recents"))
        return builder.addSectionedList(SectionedItemList.create(more.build(), "More")).build()
    }

    private fun recentRow(r: CallRecord, lineLabel: Map<String, String>, multiLine: Boolean): Row {
        val name = callerName(r.peerDisplayName) ?: formatDidForDisplay(r.peerNumber)
        val kind = when (r.direction) {
            CallDirection.Missed -> "Missed"
            CallDirection.Incoming -> "Incoming"
            CallDirection.Outgoing -> "Outgoing"
        }
        val detail = buildString {
            append(kind).append(" · ").append(formatVoicemailWhen(r.startedAt))
            if (multiLine) append(" · ").append(lineLabel[r.lineId])
        }
        return Row.Builder()
            .setTitle(name)
            .addText(detail)
            .setOnClickListener { dialer.place(r.lineId, r.peerNumber, name) }
            .build()
    }

    companion object {
        private const val MAX_RECENTS = 5
        private const val TAB_RECENTS_MAX = 20
        private const val TAB_CONTACTS_MAX = 100
        private const val TAB_RECENTS = "recents"
        private const val TAB_CONTACTS = "contacts"

        /** FreePBX's standard "My Voicemail" feature code. */
        private const val VOICEMAIL_CODE = "*97"
    }
}

/** Phone contacts, A to Z. The car limits how many rows it shows; Search reaches everyone. */
private class ContactsCarScreen(carContext: CarContext) : CarScreen(carContext) {
    private var contacts: List<DeviceContact>? = null

    init {
        scope.launch {
            contacts = loadContacts()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val list = contacts
        @Suppress("DEPRECATION")
        val builder = ListTemplate.Builder()
            .setTitle("Contacts")
            .setHeaderAction(Action.BACK)
            .setActionStrip(searchAction())
        if (list == null) return builder.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage("No contacts with a phone number (allow Contacts for IHF Phone)")
        list.take(MAX_ROWS).forEach { items.addItem(contactRow(it)) }
        return builder.setSingleList(items.build()).build()
    }

    companion object {
        private const val MAX_ROWS = 100
    }
}

/** A contact with several numbers: pick which one to call. */
private class NumberPickCarScreen(carContext: CarContext, private val contact: DeviceContact) : CarScreen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        contact.phones.filter { canCallBack(it.number) }.forEach { p ->
            items.addItem(
                Row.Builder()
                    .setTitle(formatDidForDisplay(p.number))
                    .addText(p.label.ifBlank { "Phone" })
                    .setOnClickListener { dialer.place(dialer.defaultLineId(), p.number, contact.displayName) }
                    .build(),
            )
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle(contact.displayName)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/**
 * Search contacts by name or number, or type a number to dial it. The car shows the keyboard only when parked.
 */
private class SearchCarScreen(carContext: CarContext) : CarScreen(carContext) {
    private var contacts: List<DeviceContact> = emptyList()
    private var query = ""

    init {
        scope.launch {
            contacts = loadContacts()
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Type a name or a number")
        val q = query.trim()
        val dialable = outboundDialDigits(q)
        // A typed number (3+ digits, nothing but dial characters) can be called straight away.
        if (dialable.length >= 3 && q.all { it.isDigit() || it in "+*#()- ." }) {
            items.addItem(
                Row.Builder()
                    .setTitle("Call ${formatDidForDisplay(dialable)}")
                    .addText("From your default line")
                    .setOnClickListener { dialer.place(dialer.defaultLineId(), dialable, formatDidForDisplay(dialable)) }
                    .build(),
            )
        }
        if (q.isNotEmpty()) {
            val digits = q.filter { it.isDigit() }
            contacts.asSequence()
                .filter { c ->
                    c.displayName.contains(q, ignoreCase = true) ||
                        (digits.length >= 3 && c.phones.any { it.number.filter(Char::isDigit).contains(digits) })
                }
                .take(MAX_RESULTS)
                .forEach { items.addItem(contactRow(it)) }
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                query = searchText
                invalidate()
            }

            override fun onSearchSubmitted(searchText: String) {
                query = searchText
                invalidate()
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Name or number")
            .setShowKeyboardByDefault(true)
            .setItemList(items.build())
            .build()
    }

    companion object {
        private const val MAX_RESULTS = 20
    }
}
