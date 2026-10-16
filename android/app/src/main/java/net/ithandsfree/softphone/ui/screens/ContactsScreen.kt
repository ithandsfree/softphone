package net.ithandsfree.softphone.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.ithandsfree.softphone.data.DeviceContact
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.EmptyState
import net.ithandsfree.softphone.ui.components.NumberText
import net.ithandsfree.softphone.ui.components.SubScreenTopBar
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun ContactsScreen(
    vm: SoftphoneViewModel,
    onOpenContact: (Long) -> Unit,
    /** False when hosted inside the Calls hub, which already shows a title and the sub-nav. */
    showHeader: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.loadDeviceContacts()
        else vm.markContactsPermissionDenied()
    }

    LaunchedEffect(Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) vm.loadDeviceContacts()
        else permission.launch(Manifest.permission.READ_CONTACTS)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        if (showHeader) {
            SubScreenTopBar(
                title = "Contacts",
                onBack = onBack ?: {},
                subtitle = "Device address book",
            )
        }
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search name or number", color = c.caption) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.muted) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = c.muted)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.gold,
                unfocusedBorderColor = c.hairline,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text,
                cursorColor = c.gold,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        when {
            state.contactsPermissionDenied -> EmptyState(
                title = "Contacts permission needed",
                body = "Allow access to your device contacts to message people by name. " +
                    "A FreePBX company directory can be wired later without this permission.",
                primaryLabel = "Allow contacts",
                onPrimary = { permission.launch(Manifest.permission.READ_CONTACTS) },
            )
            state.contacts.isEmpty() -> EmptyState(
                title = "No contacts",
                body = "Your device address book is empty, or still loading.",
            )
            else -> ContactList(state.contacts, query, onOpenContact)
        }
    }
}

/** First letter for the A to Z sections; names starting with anything else go under "#". */
private fun sectionOf(name: String): Char {
    val first = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return if (first in 'A'..'Z') first else '#'
}

/**
 * Contacts A to Z with pinned letter headers, filtered by the search box (name or digits), and an A to Z strip on
 * the right: tap or slide along it to jump to a letter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactList(contacts: List<DeviceContact>, query: String, onOpenContact: (Long) -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val q = query.trim()
    val digits = q.filter { it.isDigit() }
    val shown = remember(contacts, q) {
        contacts
            .filter { contact ->
                q.isEmpty() ||
                    contact.displayName.contains(q, ignoreCase = true) ||
                    (digits.length >= 2 && contact.phones.any { it.number.filter(Char::isDigit).contains(digits) })
            }
            .sortedWith(compareBy({ sectionOf(it.displayName) == '#' }, { it.displayName.lowercase() }))
    }
    if (shown.isEmpty()) {
        EmptyState(title = "No match", body = "No contact matches \"$q\".")
        return
    }
    val sections = remember(shown) { shown.groupBy { sectionOf(it.displayName) } }
    // Position of each letter's header in the list (header + rows per section), for the A to Z strip.
    val headerIndex = remember(sections) {
        var i = 0
        sections.mapValues { (_, rows) -> i.also { i += rows.size + 1 } }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 22.dp)) {
            sections.forEach { (letter, rows) ->
                stickyHeader(key = "h$letter") {
                    Text(
                        letter.toString(),
                        style = type.overline,
                        color = c.gold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.ground)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(rows, key = { it.id }) { contact ->
                    ContactRow(contact) { onOpenContact(contact.id) }
                }
            }
        }
        if (q.isEmpty() && sections.size > 1) {
            val letters = ('A'..'Z').toList() + '#'
            var stripHeight by remember { mutableStateOf(1) }
            fun jumpTo(y: Float) {
                val idx = (y / stripHeight * letters.size).toInt().coerceIn(0, letters.lastIndex)
                // The tapped letter, or the next letter that has contacts.
                val target = letters.drop(idx).firstOrNull { it in headerIndex } ?: letters.lastOrNull { it in headerIndex }
                target?.let { headerIndex[it] }?.let { scope.launch { listState.scrollToItem(it) } }
            }
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(22.dp)
                    .onSizeChanged { stripHeight = it.height.coerceAtLeast(1) }
                    .pointerInput(letters) {
                        detectTapGestures { jumpTo(it.y) }
                    }
                    .pointerInput(letters) {
                        detectVerticalDragGestures { change, _ -> jumpTo(change.position.y) }
                    }
                    .semantics { contentDescription = "Jump to letter" },
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                letters.forEach { letter ->
                    Text(
                        letter.toString(),
                        style = type.caption,
                        color = if (letter in headerIndex) c.gold else c.disabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: DeviceContact, onOpen: () -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(contact.displayName, style = type.heading, color = c.text)
            NumberText(
                contact.phones.firstOrNull()?.number.orEmpty(),
                color = c.caption,
            )
        }
        Text("Open", style = type.label, color = c.gold)
    }
}

@Composable
fun ContactDetailScreen(
    vm: SoftphoneViewModel,
    contactId: Long,
    onBack: () -> Unit,
    onMessage: (accountId: String, number: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val contact = state.contacts.firstOrNull { it.id == contactId }
    var action by remember { mutableStateOf<Pair<String, String>?>(null) } // number to mode call|sms

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        SubScreenTopBar(title = contact?.displayName ?: "Contact", onBack = onBack)
        Column(Modifier.padding(16.dp)) {
        Text(contact?.displayName ?: "Contact", style = type.title, color = c.text)
        Spacer(Modifier.height(8.dp))
        val primary = contact?.phones?.firstOrNull()?.number?.filter { it.isDigit() }.orEmpty()
        if (primary.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { action = primary to "call" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
                ) {
                    Icon(Icons.Default.Call, null)
                    Text("  Call")
                }
                OutlinedButton(
                    onClick = { action = primary to "sms" },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Message, null, tint = c.gold)
                    Text("  Message", color = c.gold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Numbers", style = type.overline, color = c.caption)
        contact?.phones?.forEach { phone ->
            val digits = phone.number.filter { it.isDigit() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(phone.label, style = type.label, color = c.muted)
                    NumberText(phone.number, color = c.text)
                }
                TextButton(onClick = { action = digits to "call" }) {
                    Text("Call", color = c.gold)
                }
                TextButton(onClick = { action = digits to "sms" }) {
                    Text("SMS", color = c.gold)
                }
            }
        }
        }
    }

    action?.let { (number, mode) ->
        fun finish(lineId: String) {
            if (mode == "call") {
                vm.placeCall(lineId, number, contact?.displayName.orEmpty())
            } else {
                onMessage(lineId, number)
            }
            action = null
        }
        if (state.lines.size <= 1) {
            val id = state.outboundLineId ?: state.activeLineId ?: state.lines.firstOrNull()?.id
            if (id != null) finish(id)
            else action = null
        } else {
            AlertDialog(
                onDismissRequest = { action = null },
                title = {
                    Text(if (mode == "call") "Call from" else "Message from")
                },
                text = {
                    Column {
                        NumberText(formatDidForDisplay(number), color = c.muted)
                        state.lines.forEach { line ->
                            TextButton(onClick = { finish(line.id) }) {
                                Text(line.label + if (line.did.isNotBlank()) " · ${formatDidForDisplay(line.did)}" else "", color = c.text)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { action = null }) { Text("Cancel") }
                },
            )
        }
    }
}
