package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ithandsfree.softphone.data.CallDirection
import net.ithandsfree.softphone.data.CallRecord
import net.ithandsfree.softphone.data.KeypadPrefs
import net.ithandsfree.softphone.data.formatDialDigits
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.data.formatPeerForDisplay
import net.ithandsfree.softphone.ui.KeypadFeedback
import net.ithandsfree.softphone.ui.RecentsFilter
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.EmptyState
import net.ithandsfree.softphone.ui.components.IconNavItem
import net.ithandsfree.softphone.ui.components.IconNavRow
import net.ithandsfree.softphone.ui.components.NumberText
import net.ithandsfree.softphone.ui.formatCallDuration
import net.ithandsfree.softphone.ui.formatCallWhen
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LineUi
import net.ithandsfree.softphone.ui.theme.LocalIhfType

enum class CallsSubTab { Keypad, Recents, Contacts, Voicemail }

/** Icon + short label for each Calls sub-destination. Icons lead so the bar reads the same in any locale. */
private fun callsNavItems(missedBadge: Boolean) = listOf(
    IconNavItem(CallsSubTab.Keypad, "Keypad", Icons.Default.Dialpad, "Keypad, dial a number"),
    IconNavItem(
        CallsSubTab.Recents,
        "Recents",
        Icons.Default.History,
        if (missedBadge) "Recents, call history, missed calls" else "Recents, call history",
        badge = missedBadge,
    ),
    IconNavItem(CallsSubTab.Contacts, "Contacts", Icons.Default.People, "Contacts, address book"),
    IconNavItem(CallsSubTab.Voicemail, "Voicemail", Icons.Default.Voicemail, "Voicemail"),
)

@Composable
fun CallsHubScreen(
    vm: SoftphoneViewModel,
    keypadPrefs: KeypadPrefs,
    tab: CallsSubTab,
    onTabChange: (CallsSubTab) -> Unit,
    onOpenCallDetail: (String) -> Unit,
    onOpenContact: (Long) -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val hasMissed = state.callHistory.any { it.direction == CallDirection.Missed }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Text(
            "Calls",
            style = type.title,
            color = c.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        IconNavRow(
            items = callsNavItems(hasMissed),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp),
        )
        when (tab) {
            // Dialling keeps the caller on the keypad — the in-call banner is the live
            // surface and hanging up leaves them where they started, ready to dial again.
            CallsSubTab.Keypad -> KeypadPane(vm = vm, keypadPrefs = keypadPrefs)
            CallsSubTab.Recents -> RecentsPane(
                vm = vm,
                onOpenDetail = onOpenCallDetail,
                onQuickCall = { record ->
                    vm.placeCall(record.lineId, record.peerNumber, record.peerDisplayName)
                },
            )
            CallsSubTab.Contacts -> ContactsScreen(
                vm = vm,
                onOpenContact = onOpenContact,
                showHeader = false,
            )
            CallsSubTab.Voicemail -> EmptyState(
                title = "Voicemail",
                body = "Voicemail listing comes next. Missed calls still appear under Recents.",
                primaryLabel = "Open Recents",
                onPrimary = { onTabChange(CallsSubTab.Recents) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeypadPane(vm: SoftphoneViewModel, keypadPrefs: KeypadPrefs) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    var digits by remember { mutableStateOf("") }
    var pickLine by remember { mutableStateOf(false) }
    var pickOutboundLine by remember { mutableStateOf(false) }
    val outboundLine = state.lines.firstOrNull {
        it.id == (state.outboundLineId ?: state.activeLineId)
    }
    val context = LocalContext.current
    val feedback = remember(keypadPrefs) { KeypadFeedback(context, keypadPrefs) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The dialled number is what a caller re-reads before pressing call, so
        // it gets the largest type on the screen and only shrinks once the
        // entry grows past a NANP number.
        val dialSize = when {
            digits.length <= 11 -> 40.sp
            digits.length <= 15 -> 32.sp
            else -> 24.sp
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (digits.isEmpty()) {
                Text("Enter a number", style = type.body, color = c.caption)
            } else {
                Text(
                    formatDialDigits(digits),
                    style = type.numberXl.copy(fontSize = dialSize, lineHeight = dialSize * 1.1f),
                    color = c.text,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Outbound line stays a single compact chip — identity without stealing
        // vertical space from the keypad.
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface)
                .clickable(enabled = state.lines.size > 1) { pickOutboundLine = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (outboundLine != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c.lineSlot(outboundLine.colorSlot)),
                )
            }
            Text(
                outboundLine?.label ?: "Select a line",
                style = type.label,
                color = c.muted,
                maxLines = 1,
            )
            if (outboundLine != null) {
                Text(
                    formatPeerForDisplay(outboundLine.did),
                    style = type.number.copy(fontSize = 13.sp),
                    color = c.gold,
                    maxLines = 1,
                )
            }
            if (state.lines.size > 1) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Change line",
                    tint = c.caption,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to "",
        )
        keys.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (key, letters) ->
                    Column(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(c.surface)
                            .clickable {
                                digits += key
                                feedback.onDigit(key.first())
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(key, style = type.numberXl, color = c.text)
                        if (letters.isNotBlank()) {
                            Text(letters, style = type.caption, color = c.caption)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(72.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(c.gold)
                    .clickable(enabled = digits.any { it.isDigit() }) {
                        // Use the Calling-from line already shown above the pad.
                        // Only ask which line when none is set (should be rare).
                        val lineId = state.outboundLineId
                            ?: state.activeLineId
                            ?: state.lines.firstOrNull()?.id
                        when {
                            lineId != null -> {
                                vm.placeCall(lineId, digits, vm.resolveContactName(digits))
                                digits = ""
                            }
                            state.lines.size > 1 -> pickLine = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Call, contentDescription = "Call", tint = c.goldInk)
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { if (digits.isNotEmpty()) digits = digits.dropLast(1) },
                        onLongClick = { digits = "" },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace", tint = c.muted)
            }
        }
    }

    if (pickLine) {
        LinePickerDialog(
            title = "Call from",
            subtitle = formatDidForDisplay(digits),
            lines = state.lines,
            onPick = { lineId ->
                pickLine = false
                vm.placeCall(lineId, digits, vm.resolveContactName(digits))
                digits = ""
            },
            onDismiss = { pickLine = false },
        )
    }
    if (pickOutboundLine) {
        LinePickerDialog(
            title = "Calling from",
            subtitle = "This call only — does not change your default extension",
            lines = state.lines,
            onPick = { lineId ->
                pickOutboundLine = false
                vm.setOutboundLine(lineId)
            },
            onDismiss = { pickOutboundLine = false },
        )
    }
}

@Composable
private fun RecentsPane(
    vm: SoftphoneViewModel,
    onOpenDetail: (String) -> Unit,
    onQuickCall: (CallRecord) -> Unit,
) {
    val state by vm.state.collectAsState()
    val rows = vm.filteredCallHistory()

    LaunchedEffect(Unit) {
        vm.refreshCallHistory()
        if (state.contacts.isEmpty() && !state.contactsPermissionDenied) {
            vm.loadDeviceContacts()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("All lines", state.recentsFilter == RecentsFilter.All) {
                vm.setRecentsFilter(RecentsFilter.All)
            }
            state.lines.forEach { line ->
                val selected = (state.recentsFilter as? RecentsFilter.Line)?.lineId == line.id
                FilterChip(line.label, selected) {
                    vm.setRecentsFilter(RecentsFilter.Line(line.id))
                }
            }
            FilterChip("Missed", state.recentsFilter == RecentsFilter.Missed) {
                vm.setRecentsFilter(RecentsFilter.Missed)
            }
        }
        if (rows.isEmpty()) {
            EmptyState(
                title = "No recent calls",
                body = "Calls you place or receive on either line show up here — with call back and message.",
            )
        } else {
            LazyColumn {
                items(rows, key = { it.id }) { record ->
                    val line = state.lines.firstOrNull { it.id == record.lineId }
                    val name = record.peerDisplayName.ifBlank {
                        vm.resolveContactName(record.peerNumber)
                    }.ifBlank { formatDidForDisplay(record.peerNumber) }
                    RecentRow(
                        record = record,
                        displayName = name,
                        line = line,
                        onOpen = { onOpenDetail(record.id) },
                        onCall = { onQuickCall(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Text(
        label,
        style = type.label,
        color = if (selected) c.goldInk else c.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.gold else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecentRow(
    record: CallRecord,
    displayName: String,
    line: LineUi?,
    onOpen: () -> Unit,
    onCall: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val missed = record.direction == CallDirection.Missed
    val icon: ImageVector = when (record.direction) {
        CallDirection.Missed -> Icons.AutoMirrored.Filled.CallMissed
        CallDirection.Incoming -> Icons.AutoMirrored.Filled.CallReceived
        CallDirection.Outgoing -> Icons.AutoMirrored.Filled.CallMade
    }
    val titleColor = if (missed) c.coral else c.text
    val meta = buildString {
        append(line?.label ?: "Line")
        append(" · ")
        append(
            when (record.direction) {
                CallDirection.Missed -> "Missed"
                CallDirection.Incoming -> "Incoming"
                CallDirection.Outgoing -> "Outgoing"
            },
        )
        formatCallDuration(record.durationSec).takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (missed) c.coral else c.muted)
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                style = type.body,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (line != null) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(c.lineSlot(line.colorSlot)),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(meta, style = type.caption, color = c.caption, maxLines = 1)
            }
        }
        Text(formatCallWhen(record.startedAt), style = type.caption, color = c.caption)
        IconButton(onClick = onCall) {
            Icon(Icons.Default.Phone, contentDescription = "Call back", tint = c.muted)
        }
    }
}

@Composable
fun CallDetailScreen(
    vm: SoftphoneViewModel,
    callId: String,
    onBack: () -> Unit,
    onMessage: (accountId: String, number: String) -> Unit,
    onOpenRecents: () -> Unit,
    onOpenKeypad: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val record = remember(callId, state.callHistory) { vm.callRecord(callId) }
    var pickLineForCall by remember { mutableStateOf(false) }
    var pickLineForSms by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (record == null) {
        EmptyState(
            title = "Call not found",
            body = "This recent call was removed.",
            primaryLabel = "Back",
            onPrimary = onBack,
        )
        return
    }

    val line = state.lines.firstOrNull { it.id == record.lineId }
    val name = record.peerDisplayName.ifBlank {
        vm.resolveContactName(record.peerNumber)
    }.ifBlank { formatDidForDisplay(record.peerNumber) }
    val whenFull = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(record.startedAt))

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.text)
            }
            Text("Call details", style = type.heading, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = c.coral)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(c.raised),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(2).uppercase(),
                    style = type.titleSm,
                    color = c.muted,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(name, style = type.title, color = c.text)
            NumberText(formatDidForDisplay(record.peerNumber), color = c.muted)
            Spacer(Modifier.height(8.dp))
            Text(
                when (record.direction) {
                    CallDirection.Missed -> "Missed call"
                    CallDirection.Incoming -> "Incoming call"
                    CallDirection.Outgoing -> "Outgoing call"
                },
                style = type.label,
                color = if (record.direction == CallDirection.Missed) c.coral else c.muted,
            )
            Text(whenFull, style = type.caption, color = c.caption)
            if (record.durationSec > 0) {
                Text(
                    "Duration ${formatCallDuration(record.durationSec)}",
                    style = type.caption,
                    color = c.caption,
                )
            }
            if (line != null) {
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(c.lineSlot(line.colorSlot)),
                    )
                    Text(
                        "  ${line.label} · ${formatDidForDisplay(line.did)}",
                        style = type.body,
                        color = c.muted,
                    )
                }
            }
            if (record.note.isNotBlank()) {
                Text(
                    record.note,
                    style = type.caption,
                    color = c.goldDim,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (state.lines.size > 1) pickLineForCall = true
                        else vm.placeCall(record.lineId, record.peerNumber, name)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
                ) {
                    Icon(Icons.Default.Call, null)
                    Text("  Call back")
                }
                OutlinedButton(
                    onClick = {
                        if (state.lines.size > 1) pickLineForSms = true
                        else onMessage(record.lineId, record.peerNumber)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Message, null, tint = c.gold)
                    Text("  Message", color = c.gold)
                }
            }
            Spacer(Modifier.height(20.dp))
            // Details is reached from Recents and from the in-call banner, so it always
            // offers a way onward instead of relying on the back arrow alone.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailNavChip(
                    icon = Icons.Default.History,
                    label = "Recents",
                    description = "Back to Recents",
                    onClick = onOpenRecents,
                    modifier = Modifier.weight(1f),
                )
                DetailNavChip(
                    icon = Icons.Default.Dialpad,
                    label = "Keypad",
                    description = "Back to Keypad",
                    onClick = onOpenKeypad,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (pickLineForCall) {
        LinePickerDialog(
            title = "Call back from",
            subtitle = formatDidForDisplay(record.peerNumber),
            lines = state.lines,
            onPick = { lineId ->
                pickLineForCall = false
                vm.placeCall(lineId, record.peerNumber, name)
            },
            onDismiss = { pickLineForCall = false },
        )
    }
    if (pickLineForSms) {
        LinePickerDialog(
            title = "Message from",
            subtitle = formatDidForDisplay(record.peerNumber),
            lines = state.lines,
            onPick = { lineId ->
                pickLineForSms = false
                onMessage(lineId, record.peerNumber)
            },
            onDismiss = { pickLineForSms = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from Recents?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCallRecord(callId)
                    confirmDelete = false
                    onBack()
                }) { Text("Remove", color = c.coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailNavChip(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = type.label, color = c.muted, maxLines = 1)
    }
}

@Composable
fun LinePickerDialog(
    title: String,
    subtitle: String,
    lines: List<LineUi>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                NumberText(subtitle, color = c.muted)
                Spacer(Modifier.height(8.dp))
                lines.forEach { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(line.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(c.lineSlot(line.colorSlot)),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(line.label, style = type.heading, color = c.text)
                            NumberText(formatDidForDisplay(line.did), color = c.caption)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
