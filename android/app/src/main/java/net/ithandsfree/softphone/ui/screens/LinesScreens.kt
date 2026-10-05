package net.ithandsfree.softphone.ui.screens

import android.media.RingtoneManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.data.RingtoneCatalog
import net.ithandsfree.softphone.data.RingtoneOption
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.data.normalizeDidDigits
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.EmptyState
import net.ithandsfree.softphone.ui.components.NumberText
import net.ithandsfree.softphone.ui.components.SecretTextField
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LineColorSlot
import net.ithandsfree.softphone.ui.theme.LineUi
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun LinesHomeScreen(
    vm: SoftphoneViewModel,
    onAdd: () -> Unit,
    onOpenLine: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val skin = IhfThemeAccess.skin
    val type = LocalIhfType.current

    LaunchedEffect(Unit) {
        vm.refreshAllLineCapabilities()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_ihf_emblem),
                contentDescription = null,
                modifier = Modifier
                    .height(36.dp)
                    .width(42.dp),
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    BuildConfig.PRODUCT_NAME.ifBlank { skin.productName },
                    style = type.titleSm,
                    color = c.gold,
                )
                Text(
                    BuildConfig.BRAND_SUB,
                    style = type.overline,
                    color = c.gold,
                )
            }
            if (state.lines.size < 2) {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "Add line", tint = c.gold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Lines", style = type.heading, color = c.text)
        Text(
            "Presence is set on the PBX for each extension. Default sets which line Messages and Calls open on.",
            style = type.caption,
            color = c.caption,
        )
        Spacer(Modifier.height(16.dp))
        if (state.lines.isEmpty()) {
            EmptyState(
                title = "No lines yet",
                body = "Enrol your first FreePBX extension to start messaging.",
                primaryLabel = "Enrol a line",
                onPrimary = onAdd,
            )
        } else {
            state.lines.forEach { line ->
                LineCard(
                    line = line,
                    active = line.id == state.activeLineId,
                    ringtoneTitle = RingtoneCatalog.titleFor(
                        LocalContext.current,
                        vm.ringtoneUri(line.id),
                    ),
                    busy = state.busy,
                    onOpen = { onOpenLine(line.id) },
                    onActivate = { vm.setDefaultLine(line.id) },
                    onSetDnd = { enabled -> vm.setDnd(line.id, enabled) },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (state.lines.size < 2) {
                Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add second line") }
            }
        }
    }
}

@Composable
private fun LineCard(
    line: LineUi,
    active: Boolean,
    ringtoneTitle: String,
    busy: Boolean,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
    onSetDnd: (Boolean) -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val slot = c.lineSlot(line.colorSlot)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(if (active) c.railActiveBg else c.surface)
            .padding(16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(slot))
            Text(
                line.label,
                style = type.titleSm,
                color = c.text,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            if (active) Text("Default", style = type.label, color = slot)
            else TextButton(onClick = onActivate) { Text("Make default", color = c.gold) }
        }
        NumberText("DID ${formatDidForDisplay(line.did.ifBlank { "—" })}", color = c.muted, modifier = Modifier.padding(top = 8.dp))
        Text("Ext ${line.ext.ifBlank { "—" }}", style = type.caption, color = c.caption)
        Spacer(Modifier.height(12.dp))
        PresenceSegment(
            label = line.label,
            dndEnabled = line.dndEnabled,
            accent = slot,
            enabled = !busy,
            onSelectAvailable = { onSetDnd(false) },
            onSelectDnd = { onSetDnd(true) },
        )
        Text(
            "Changes DND on the PBX for extension ${line.ext.ifBlank { "—" }}",
            style = type.caption,
            color = c.caption,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.NotificationsActive, null, tint = c.muted, modifier = Modifier.size(16.dp))
            Text(
                " Ringtone: $ringtoneTitle",
                style = type.caption,
                color = c.muted,
            )
        }
    }
}

/** PBX-backed presence: Available vs Do not disturb (writes FreePBX DND). */
@Composable
fun PresenceSegment(
    label: String,
    dndEnabled: Boolean,
    accent: Color,
    enabled: Boolean,
    onSelectAvailable: () -> Unit,
    onSelectDnd: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Presence for $label"
                stateDescription = "Set on PBX"
            }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .border(1.dp, c.hairline, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(c.raised)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PresenceChip(
            text = "Available",
            selected = !dndEnabled,
            selectedColor = accent,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = onSelectAvailable,
        )
        PresenceChip(
            text = "Do not disturb",
            selected = dndEnabled,
            selectedColor = c.coral,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = onSelectDnd,
        )
    }
}

@Composable
private fun PresenceChip(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Box(
        modifier
            .height(40.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                stateDescription = if (selected) "Selected, set on PBX" else "Not selected"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) selectedColor else c.muted,
        )
    }
}

@Composable
fun LineDetailScreen(
    vm: SoftphoneViewModel,
    accountId: String,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val account = state.accounts.firstOrNull { it.id == accountId }
    val line = state.lines.firstOrNull { it.id == accountId }
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    var showRingtones by remember { mutableStateOf(false) }
    var showDidPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<android.media.Ringtone?>(null) }
    var sipExtDraft by remember(accountId, account?.sipExtension) {
        mutableStateOf(account?.sipExtension.orEmpty())
    }
    var sipSecretDraft by remember(accountId) { mutableStateOf("") }
    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.gold,
        unfocusedBorderColor = c.hairline,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        cursorColor = c.gold,
        focusedLabelColor = c.muted,
        unfocusedLabelColor = c.caption,
    )
    val sipReady = !account?.sipExtension.isNullOrBlank() && !account?.sipPassword.isNullOrBlank()

    LaunchedEffect(accountId) {
        vm.loadAvailableDids(accountId)
        vm.refreshCapabilities(accountId)
    }

    DisposableEffect(Unit) {
        onDispose { playing?.stop() }
    }

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
            Text(line?.label ?: "Line", style = type.heading, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { pendingDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = c.coral)
            }
        }
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("PRESENCE", style = type.overline, color = c.caption)
            Spacer(Modifier.height(8.dp))
            PresenceSegment(
                label = line?.label ?: "line",
                dndEnabled = account?.capDnd == true,
                accent = c.lineSlot(line?.colorSlot ?: LineColorSlot.Emerald),
                enabled = !state.busy,
                onSelectAvailable = { vm.setDnd(accountId, false) },
                onSelectDnd = { vm.setDnd(accountId, true) },
            )
            Text(
                "Changes DND on the PBX for extension ${account?.sipExtension?.ifBlank { "—" } ?: "—"}",
                style = type.caption,
                color = c.caption,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .clickable {
                        vm.loadAvailableDids(accountId)
                        showDidPicker = true
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DID", style = type.caption, color = c.caption)
                    NumberText(
                        formatDidForDisplay(account?.did.orEmpty()),
                        color = c.text,
                    )
                }
                Text("Change", style = type.label, color = c.gold)
            }
            Text(
                "UM ${account?.umUsername}",
                style = type.body,
                color = c.muted,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("SIP CALLING", style = type.overline, color = c.caption)
            Spacer(Modifier.height(8.dp))
            Text(
                if (sipReady) {
                    "Secret saved on this device — dial uses this line’s credentials."
                } else {
                    "No SIP secret yet. Sync from FreePBX over your enrol token, or enter it once."
                },
                style = type.caption,
                color = if (sipReady) c.muted else c.coral,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = sipExtDraft,
                onValueChange = { sipExtDraft = it },
                label = { Text("Extension") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
            )
            Spacer(Modifier.height(8.dp))
            SecretTextField(
                value = sipSecretDraft,
                onValueChange = { sipSecretDraft = it },
                label = if (sipReady) "New SIP secret (leave blank to keep)" else "SIP secret",
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors,
                supportingText = "Stored encrypted on this phone. Never sent by SMS.",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val secret = sipSecretDraft.ifBlank {
                            account?.sipPassword.orEmpty()
                        }
                        vm.saveSipCredentials(accountId, sipExtDraft, secret)
                        sipSecretDraft = ""
                    },
                    enabled = sipExtDraft.isNotBlank() &&
                        (sipSecretDraft.isNotBlank() || sipReady),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.gold,
                        contentColor = c.goldInk,
                    ),
                ) { Text("Save SIP") }
                TextButton(onClick = { vm.syncSipCredentials(accountId) }) {
                    Text("Sync from PBX", color = c.gold)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("RINGTONE", style = type.overline, color = c.caption)
            Spacer(Modifier.height(8.dp))
            Text(
                "Each line can use a different ringtone so you know which DID is ringing.",
                style = type.caption,
                color = c.caption,
            )
            Spacer(Modifier.height(8.dp))
            val current = RingtoneCatalog.titleFor(context, vm.ringtoneUri(accountId))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .clickable { showRingtones = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(current, style = type.body, color = c.text)
                Text("Change", style = type.label, color = c.gold)
            }
            Spacer(Modifier.height(20.dp))
            Text("MESSAGING", style = type.overline, color = c.caption)
            val smsLabel = if (account?.capSms == true) "on" else "off"
            val mmsLabel = if (account?.capMms == true) "on" else "off"
            val voiceLabel = if (account?.capVoice == true) "on" else "off"
            val dndLabel = if (account?.capDnd == true) "DND on" else "Available"
            Text(
                "Voice · $voiceLabel  ·  SMS · $smsLabel  ·  MMS · $mmsLabel  ·  $dndLabel",
                style = type.body,
                color = c.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Voice/SMS/MMS flags come from the BFF. Presence above writes FreePBX DND for this extension.",
                style = type.caption,
                color = c.caption,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (showDidPicker) {
        val usedElsewhere = state.accounts
            .filterNot { it.id == accountId }
            .map { normalizeDidDigits(it.did) }
            .toSet()
        val choices = state.availableDidsForLine.ifEmpty {
            listOfNotNull(account?.did?.let { normalizeDidDigits(it) })
        }
        val selected = normalizeDidDigits(account?.did.orEmpty())
        AlertDialog(
            onDismissRequest = { showDidPicker = false },
            title = { Text("Choose DID") },
            text = {
                Column {
                    Text(
                        "Pick the SMS number for this line.",
                        style = type.caption,
                        color = c.caption,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (choices.isEmpty()) {
                        Text("Loading DIDs…", style = type.body, color = c.muted)
                    }
                    choices.forEach { did ->
                        val taken = did in usedElsewhere
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !taken) {
                                    vm.setDid(accountId, did)
                                    showDidPicker = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = did == selected,
                                onClick = {
                                    if (!taken) {
                                        vm.setDid(accountId, did)
                                        showDidPicker = false
                                    }
                                },
                                enabled = !taken,
                                colors = RadioButtonDefaults.colors(selectedColor = c.gold),
                            )
                            Text(
                                formatDidForDisplay(did) + if (taken) " (other line)" else "",
                                style = type.body,
                                color = if (taken) c.disabled else c.text,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDidPicker = false }) { Text("Close") }
            },
        )
    }

    if (showRingtones) {
        val options = remember { RingtoneCatalog.options(context) }
        var selectedId by remember(accountId) {
            mutableStateOf(vm.ringtoneUri(accountId) ?: "default")
        }
        AlertDialog(
            onDismissRequest = {
                playing?.stop()
                showRingtones = false
            },
            title = { Text("Choose ringtone") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(options, key = { it.id }) { opt ->
                        RingtoneRow(
                            option = opt,
                            selected = opt.id == selectedId,
                            onSelect = {
                                playing?.stop()
                                selectedId = opt.id
                                vm.setRingtone(accountId, opt.id)
                                if (opt.uri != null) {
                                    playing = RingtoneManager.getRingtone(context, opt.uri)?.also { it.play() }
                                } else {
                                    playing = null
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    playing?.stop()
                    showRingtones = false
                }) { Text("Done") }
            },
        )
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Remove ${line?.label}?") },
            text = { Text("Deletes credentials and tokens on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccount(accountId)
                    pendingDelete = false
                    onBack()
                }) { Text("Remove", color = c.coral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RingtoneRow(
    option: RingtoneOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = c.gold),
        )
        Text(option.title, style = LocalIhfType.current.body, color = c.text)
    }
}
