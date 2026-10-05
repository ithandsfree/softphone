package net.ithandsfree.softphone.ui.screens

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
        Text(
            if (showHeader) {
                "PBX directory (Sangoma-style) can be added later as a secondary source."
            } else {
                "Device address book. PBX directory (Sangoma-style) can be added later."
            },
            style = type.caption,
            color = c.caption,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
            else -> LazyColumn {
                items(state.contacts, key = { it.id }) { contact ->
                    ContactRow(contact) { onOpenContact(contact.id) }
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
                                Text("${line.label} · ${formatDidForDisplay(line.did)}", color = c.text)
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
