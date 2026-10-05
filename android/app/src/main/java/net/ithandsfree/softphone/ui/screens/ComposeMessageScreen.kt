package net.ithandsfree.softphone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import net.ithandsfree.softphone.data.DeviceContact
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.NumberText
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/** New SMS/MMS compose for a line. To field supports contacts typeahead + manual digits. */
@Composable
fun ComposeMessageScreen(
    vm: SoftphoneViewModel,
    accountId: String,
    initialTo: String?,
    onBack: () -> Unit,
    onSent: (peer: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val account = state.accounts.firstOrNull { it.id == accountId }
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    var to by remember { mutableStateOf(initialTo.orEmpty()) }
    var toDisplay by remember {
        mutableStateOf(
            initialTo?.takeIf { it.isNotBlank() }?.let { formatDidForDisplay(it) }.orEmpty(),
        )
    }
    var body by remember { mutableStateOf("") }
    var attachUri by remember { mutableStateOf<Uri?>(null) }
    var showSuggestions by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        attachUri = uri
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.loadDeviceContacts()
        else vm.markContactsPermissionDenied()
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.gold,
        unfocusedBorderColor = c.hairline,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        cursorColor = c.gold,
        focusedLabelColor = c.muted,
        unfocusedLabelColor = c.caption,
    )

    LaunchedEffect(Unit) {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) vm.loadDeviceContacts()
        else if (!state.contactsPermissionDenied) {
            permission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    val query = toDisplay.trim()
    val suggestions = remember(query, state.contacts, showSuggestions) {
        if (!showSuggestions || query.isBlank()) emptyList()
        else filterContacts(state.contacts, query).take(8)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .imePadding(),
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
            Column(Modifier.weight(1f)) {
                Text("New message", style = type.heading, color = c.text)
                Text("From ${account?.label ?: "line"} · ${account?.did.orEmpty()}", style = type.caption, color = c.caption)
            }
        }
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = toDisplay,
                onValueChange = { raw ->
                    toDisplay = raw
                    showSuggestions = true
                    // Keep digits-only peer for send; allow letters while searching contacts.
                    val digits = raw.filter { it.isDigit() || it == '+' }
                    if (digits.isNotBlank() && raw.all { it.isDigit() || it == '+' || it.isWhitespace() || it == '-' || it == '(' || it == ')' }) {
                        to = digits.filter { it.isDigit() }
                    } else if (raw.isBlank()) {
                        to = ""
                    }
                },
                label = { Text("To") },
                placeholder = { Text("Name or number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = fieldColors,
                trailingIcon = {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = "Search contacts",
                        tint = c.muted,
                    )
                },
            )
            if (state.contactsPermissionDenied) {
                Text(
                    "Contacts permission off — type a number, or allow contacts to pick by name.",
                    style = type.caption,
                    color = c.caption,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(onClick = { permission.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Allow contacts", color = c.gold)
                }
            }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surface),
                ) {
                    items(suggestions, key = { "${it.contact.id}-${it.number}" }) { hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    to = hit.number.filter { ch -> ch.isDigit() }
                                    toDisplay = "${hit.contact.displayName} · ${formatDidForDisplay(to)}"
                                    showSuggestions = false
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.contact.displayName, style = type.heading, color = c.text)
                                NumberText(
                                    formatDidForDisplay(hit.number.filter { it.isDigit() }),
                                    color = c.caption,
                                )
                            }
                            Text(hit.label, style = type.caption, color = c.muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(if (attachUri != null) "Caption" else "Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                colors = fieldColors,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = account?.capMms == true,
                    colors = ButtonDefaults.buttonColors(containerColor = c.raised, contentColor = c.text),
                ) {
                    Icon(Icons.Default.AttachFile, null)
                    Text(
                        if (account?.capMms == true) "  Attach photo" else "  MMS off",
                        style = type.label,
                    )
                }
            }
            attachUri?.let { uri ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        "MMS · keep under ~1 MB",
                        style = type.caption,
                        color = c.muted,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                    IconButton(onClick = { attachUri = null }) {
                        Icon(Icons.Default.Close, null, tint = c.muted)
                    }
                }
            }
            state.error?.let {
                Text(it, color = c.coral, style = type.body, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(20.dp))
            val peerDigits = to.filter { it.isDigit() }.ifBlank {
                toDisplay.filter { it.isDigit() }
            }
            Button(
                onClick = {
                    val peer = peerDigits
                    if (peer.isBlank()) return@Button
                    val uri = attachUri
                    if (uri != null) {
                        vm.sendMms(accountId, peer, uri, context.contentResolver)
                    } else {
                        vm.sendSms(accountId, peer, body)
                    }
                    onSent(peer)
                },
                enabled = peerDigits.isNotBlank() && (body.isNotBlank() || attachUri != null) && !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Text("  Send", style = type.label)
            }
        }
    }
}

private data class ContactHit(
    val contact: DeviceContact,
    val number: String,
    val label: String,
)

private fun filterContacts(contacts: List<DeviceContact>, query: String): List<ContactHit> {
    val q = query.trim().lowercase()
    val qDigits = query.filter { it.isDigit() }
    if (q.isBlank()) return emptyList()
    val out = mutableListOf<ContactHit>()
    for (contact in contacts) {
        val nameHit = contact.displayName.lowercase().contains(q)
        for (phone in contact.phones) {
            val digits = phone.number.filter { it.isDigit() }
            val numHit = qDigits.isNotBlank() && digits.contains(qDigits)
            if (nameHit || numHit) {
                out.add(ContactHit(contact, phone.number, phone.label))
            }
        }
    }
    return out
}
