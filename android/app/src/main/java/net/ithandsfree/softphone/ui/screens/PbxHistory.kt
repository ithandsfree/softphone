package net.ithandsfree.softphone.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.data.CallRecord
import net.ithandsfree.softphone.data.PbxCall
import net.ithandsfree.softphone.data.formatSeconds
import net.ithandsfree.softphone.data.formatVoicemailWhen
import net.ithandsfree.softphone.data.matchPbxCall
import net.ithandsfree.softphone.data.normalizeDidDigits
import net.ithandsfree.softphone.ui.ClipPlayer
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/**
 * Call details: the PBX's history with this number on the call's line (UCP Call History), newest first. A call the
 * PBX recorded gets Play and Download, following UCP's playback / download permissions. The row matching this
 * Recents entry is marked "This call". Same data as the desktop's call detail.
 */
@Composable
fun PbxHistorySection(vm: SoftphoneViewModel, record: CallRecord) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { ClipPlayer(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val progress by player.progress.collectAsState()
    var pendingSave by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(record.lineId) { vm.loadPbxCalls(record.lineId, force = true) }
    val response = state.pbxCalls[record.lineId] ?: return
    if (!response.permissions.history) return
    val want = normalizeDidDigits(record.peerNumber).takeLast(10)
    val rows = response.calls.filter { normalizeDidDigits(it.peer).takeLast(10) == want }.take(10)
    if (rows.isEmpty()) return
    val thisCall = matchPbxCall(record.peerNumber, record.startedAt, record.durationSec, response.calls)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = vm.recordingAudio(record.lineId, id, download = true)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.onSuccess { vm.showStatus("Recording saved") }
                .onFailure { vm.showStatus("Could not save the recording") }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text("HISTORY WITH THIS NUMBER", style = type.overline, color = c.caption)
        Spacer(Modifier.height(8.dp))
        val shape = RoundedCornerShape(14.dp)
        Column(Modifier.fillMaxWidth().background(c.surface, shape).border(1.dp, c.hairline, shape)) {
            rows.forEach { call ->
                HistoryRow(
                    call = call,
                    isThis = call.id == thisCall?.id,
                    canPlay = call.recording && response.permissions.playback,
                    canSave = call.recording && response.permissions.download,
                    playing = progress.key == call.id && progress.playing,
                    onPlay = {
                        if (!player.toggle(call.id)) {
                            scope.launch {
                                runCatching { vm.recordingAudio(record.lineId, call.id) }
                                    .onSuccess { player.play(call.id, it) }
                                    .onFailure { vm.showStatus("Could not play the recording") }
                            }
                        }
                    },
                    onSave = {
                        pendingSave = call.id
                        saver.launch("Call ${call.peer.filter { it.isDigit() }} ${call.id}.wav")
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    call: PbxCall,
    isThis: Boolean,
    canPlay: Boolean,
    canSave: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val answered = call.disposition.equals("ANSWERED", ignoreCase = true)
    val incoming = call.direction == "in"
    val icon = when {
        incoming && !answered -> Icons.AutoMirrored.Filled.CallMissed
        incoming -> Icons.AutoMirrored.Filled.CallReceived
        else -> Icons.AutoMirrored.Filled.CallMade
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (incoming && !answered) c.coral else c.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(formatVoicemailWhen(call.at * 1000), style = type.label, color = c.text)
            val what = buildString {
                append(if (incoming) (if (answered) "Incoming" else "Missed") else "Outgoing")
                if (call.billsec > 0) append(" · ").append(formatSeconds(call.billsec))
                if (call.recording) append(" · recorded")
                if (isThis) append(" · this call")
            }
            Text(what, style = type.caption, color = if (isThis) c.gold else c.caption)
        }
        if (canPlay) SmallRound(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause recording" else "Play recording", onPlay)
        if (canSave) SmallRound(Icons.Filled.Download, "Download recording", onSave)
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = IhfThemeAccess.colors
    Box(
        Modifier.size(36.dp).background(c.raised, CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = c.text, modifier = Modifier.size(18.dp))
    }
}
