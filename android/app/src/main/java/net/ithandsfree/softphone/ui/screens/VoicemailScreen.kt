package net.ithandsfree.softphone.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.data.Voicemail
import net.ithandsfree.softphone.data.callerName
import net.ithandsfree.softphone.data.canCallBack
import net.ithandsfree.softphone.data.formatPeerForDisplay
import net.ithandsfree.softphone.data.formatSeconds
import net.ithandsfree.softphone.data.formatVoicemailWhen
import net.ithandsfree.softphone.data.textBlock
import net.ithandsfree.softphone.ui.ClipPlayer
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LineUi
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/**
 * Calls › Voicemail (`voicemail.png`, `voicemail-empty.png`), the same as the desktop: the PBX mailbox of each line,
 * filtered by line. The open message is a player with a scrubber and Call back from <line>, Text, Download and
 * Delete. The empty state names where greetings live and dials the mailbox.
 */
@Composable
fun VoicemailPane(
    vm: SoftphoneViewModel,
    onMessage: (lineId: String, number: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { ClipPlayer(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val progress by player.progress.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Mailboxes load when the tab opens and every minute while it is open.
    LaunchedEffect(Unit) {
        while (true) {
            vm.loadVoicemail()
            delay(60_000)
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val (lineId, id) = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = vm.voicemailAudio(lineId, id, download = true)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.onSuccess { vm.showStatus("Voicemail saved") }
                .onFailure { vm.showStatus("Could not save the voicemail") }
        }
    }

    data class Item(val key: String, val line: LineUi, val m: Voicemail)
    val items = state.lines.flatMap { line ->
        state.voicemail[line.id]?.messages.orEmpty().map { Item("${line.id}/${it.id}", line, it) }
    }.filter { filter == null || it.line.id == filter }
        .sortedWith(compareByDescending<Item> { it.m.new }.thenByDescending { it.m.at })

    fun play(item: Item) {
        open = item.key
        if (player.toggle(item.key)) return
        scope.launch {
            runCatching { vm.voicemailAudio(item.line.id, item.m.id) }
                .onSuccess { bytes ->
                    player.play(item.key, bytes)
                    if (item.m.new) vm.markVoicemailHeard(item.line.id, item.m.id)
                }
                .onFailure { vm.showStatus("Could not play the voicemail") }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.lines.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("All lines", null, filter == null) { filter = null }
                state.lines.forEach { line -> Chip(line.label, c.lineSlot(line.colorSlot), filter == line.id) { filter = line.id } }
            }
        }
        val notice = state.voicemailNotice.values.distinct().joinToString("\n").ifBlank { null }
        notice?.let { Text(it, style = type.caption, color = c.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        when {
            state.voicemailLoading && items.isEmpty() ->
                Text("Loading voicemail…", style = type.body, color = c.muted, modifier = Modifier.padding(20.dp))
            items.isEmpty() -> EmptyVoicemail(vm, state.lines, filter)
            else -> LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.key }) { item ->
                    if (item.key == open) {
                        OpenCard(
                            item.m, item.line,
                            progress = progress.takeIf { it.key == item.key },
                            onPlay = { play(item) },
                            onSeek = { f -> player.seek(item.key, f) },
                            onCall = { vm.placeCall(item.line.id, item.m.number, callerName(item.m.name).orEmpty()) },
                            onText = { onMessage(item.line.id, item.m.number) },
                            onSave = {
                                pendingSave = item.line.id to item.m.id
                                saver.launch("Voicemail ${item.m.number.filter { ch -> ch.isLetterOrDigit() }.ifBlank { "unknown" }}.wav")
                            },
                            onDelete = { confirmDelete = item.key },
                            deleteArmed = confirmDelete == item.key,
                            onConfirmDelete = {
                                confirmDelete = null
                                if (progress.key == item.key) player.stop()
                                open = null
                                vm.deleteVoicemail(item.line.id, item.m.id)
                            },
                        )
                    } else {
                        VoicemailRow(item.m, item.line, onOpen = { open = item.key }, onPlay = { play(item) })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                        CallVoicemailButton(vm, filter)
                    }
                }
            }
        }
    }
    // A second Delete press within 4 s deletes; the PBX keeps no copy.
    LaunchedEffect(confirmDelete) {
        if (confirmDelete != null) {
            delay(4_000)
            confirmDelete = null
        }
    }
}

@Composable
private fun Chip(label: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.goldTint else c.surface)
            .border(1.dp, if (selected) c.gold else c.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = type.label, color = if (selected) c.gold else c.text)
    }
}

private fun title(m: Voicemail): Pair<String, Boolean> {
    val name = callerName(m.name)
    return when {
        name != null -> name to false
        canCallBack(m.number) -> formatPeerForDisplay(m.number) to true
        else -> "Unknown caller" to false
    }
}

@Composable
private fun SubLine(line: LineUi, m: Voicemail) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(c.lineSlot(line.colorSlot), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("${line.label} · ${formatVoicemailWhen(m.at * 1000)}", style = type.caption, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun VoicemailRow(m: Voicemail, line: LineUi, onOpen: () -> Unit, onPlay: () -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val (t, isNumber) = title(m)
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundButton(Icons.Filled.PlayArrow, "Play voicemail from $t", c.surface, c.text, onPlay)
        Column(Modifier.weight(1f)) {
            Text(t, style = if (isNumber) type.number else type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SubLine(line, m)
        }
        Text(formatSeconds(m.duration), style = type.caption, color = c.muted)
        if (m.new) Box(Modifier.size(8.dp).background(c.gold, CircleShape))
    }
}

@Composable
private fun OpenCard(
    m: Voicemail,
    line: LineUi,
    progress: ClipPlayer.Progress?,
    onPlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onCall: () -> Unit,
    onText: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    deleteArmed: Boolean,
    onConfirmDelete: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val (t, isNumber) = title(m)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(c.surface, shape).border(1.dp, c.hairline, shape).padding(16.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t, style = if (isNumber) type.number else type.heading, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SubLine(line, m)
            }
            if (m.new) Box(Modifier.size(8.dp).background(c.gold, CircleShape))
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            val playing = progress?.playing == true
            RoundButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", c.gold, c.goldInk, onPlay)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val dur = progress?.durationMs?.takeIf { it > 0 } ?: (m.duration * 1000)
                val pos = progress?.positionMs ?: 0
                Slider(
                    value = if (dur > 0) pos.toFloat() / dur else 0f,
                    onValueChange = onSeek,
                    enabled = progress != null,
                    colors = SliderDefaults.colors(thumbColor = c.gold, activeTrackColor = c.gold, inactiveTrackColor = c.hairline),
                )
                androidx.compose.foundation.layout.Row {
                    Text(formatSeconds(pos / 1000), style = type.caption, color = c.muted, modifier = Modifier.weight(1f))
                    Text(formatSeconds(dur / 1000), style = type.caption, color = c.muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val block = textBlock(m.number)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(Icons.Filled.Call, "Call back from ${line.label}", primary = true, enabled = canCallBack(m.number), modifier = Modifier.weight(1f), onClick = onCall)
            Action(Icons.AutoMirrored.Filled.Message, "Text", enabled = block == null, onClick = onText)
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(Icons.Filled.Download, "Download", modifier = Modifier.weight(1f), onClick = onSave)
            if (deleteArmed) {
                Action(Icons.Filled.Delete, "Delete", danger = true, modifier = Modifier.weight(1f), onClick = onConfirmDelete)
            } else {
                Action(Icons.Filled.Delete, "Delete", modifier = Modifier.weight(1f), onClick = onDelete)
            }
        }
        if (block != null) {
            Spacer(Modifier.height(8.dp))
            Text(block, style = type.caption, color = c.caption)
        }
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val fill = when {
        !enabled -> c.surface
        danger -> c.coral
        primary -> c.gold
        else -> c.raised
    }
    val ink = when {
        !enabled -> c.disabled
        danger -> c.coralInk
        primary -> c.goldInk
        else -> c.text
    }
    androidx.compose.foundation.layout.Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(fill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ink)
    }
}

@Composable
private fun CallVoicemailButton(vm: SoftphoneViewModel, lineId: String?) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val code = vm.voicemailDialCode(lineId)
    androidx.compose.foundation.layout.Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.hairline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Call voicemail") { vm.callVoicemail(lineId) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Voicemail, contentDescription = null, tint = c.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Call voicemail ($code)", style = type.label, color = c.text)
    }
}

@Composable
private fun EmptyVoicemail(vm: SoftphoneViewModel, lines: List<LineUi>, filter: String?) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val names = lines.joinToString(" or ") { it.label }.ifBlank { "your lines" }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Voicemail, contentDescription = null, tint = c.muted, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("No voicemail", style = type.title, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text(
            "Messages left on $names appear here with the line they were left on. " +
                "Greetings are recorded on the PBX by dialling ${vm.voicemailDialCode(filter)}.",
            style = type.body,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        CallVoicemailButton(vm, filter)
    }
}
