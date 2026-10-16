package net.ithandsfree.softphone.ui.screens

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.delay
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.sip.SipCallSnapshot
import net.ithandsfree.softphone.sip.TelecomBridge
import net.ithandsfree.softphone.ui.formatLiveCallTimer
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/** How the in-call screen names and colours a line, and resolves a caller's name. */
data class InCallLines(
    val label: (String?) -> String,
    val number: (String?) -> String?,
    val colorIndex: (String?) -> Int,
    val contactName: (String) -> String?,
    /** Extension of the call's line, for "Send to my other devices (1002)". */
    val extension: String?,
)

/** Everything the in-call screen can ask for. */
interface InCallActions {
    fun answer()
    fun decline()
    fun hangup()
    fun toggleMute()
    fun toggleHold()
    fun toggleSpeaker()
    fun dtmf(digit: Char)
    fun route(endpoint: CallEndpointCompat)
    fun transfer(destination: String)
    fun consultTransfer(destination: String)
    fun completeTransfer()
    fun cancelTransfer()
    fun sendToOtherDevices()
    fun answerWaiting()
    fun endWaiting()
    fun swap()
    fun minimize()
}

/** Vivid end-call red, the same as the desktop's End button. */
private val EndRed = Color(0xFFE5484D)
private val EndRedDeep = Color(0xFFC2262B)

/**
 * Full-screen call (`in-call.png`): line marker on top, caller in the middle, and every control at the bottom
 * within thumb reach: a 3×2 grid (Mute, Keypad, Speaker / Hold, Transfer, Audio) and the round Hang up. The bottom
 * bar is hidden during a call; the arrow at the top minimises to the app with a "Return to call" bar. Ringing uses
 * the same screen with Decline and "Answer · <line>".
 */
@Composable
fun InCallScreen(
    call: SipCallSnapshot,
    audio: TelecomBridge.Audio,
    lines: InCallLines,
    actions: InCallActions,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val party = partyOf(call.remote)
    val name = lines.contactName(party)
    val number = formatDidForDisplay(party).ifBlank { party }
    val lineColor = if (lines.colorIndex(call.lineId) == 1) c.sky else c.emerald
    var keypadOpen by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var transferOpen by remember { mutableStateOf(false) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Top: minimise, and which line the call is on.
    val topBar: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!call.incoming) {
                    RoundIcon(Icons.Filled.KeyboardArrowDown, "Back to the app; the call continues", c.surface, c.muted, 40.dp) {
                        actions.minimize()
                    }
                } else {
                    Spacer(Modifier.size(40.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(lineColor, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    val lineNumber = lines.number(call.lineId)?.let { " · $it" }.orEmpty()
                    Text(lines.label(call.lineId) + lineNumber, style = type.label, color = c.muted, maxLines = 1)
                }
                Spacer(Modifier.size(40.dp))
            }

            call.waiting?.let { w ->
                Spacer(Modifier.height(12.dp))
                WaitingCard(w, lines, actions, consulting = call.consulting)
            }
    }

    // Middle: who the call is with and the timer, or the keypad.
    val identity: @Composable () -> Unit = {
            if (keypadOpen && !call.incoming) {
                Text(
                    typed.ifBlank { " " },
                    style = type.numberXl,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                DtmfPad { d ->
                    typed += d
                    actions.dtmf(d)
                }
            } else {
                Box(
                    Modifier.size(96.dp).background(c.raised, CircleShape).border(1.dp, c.hairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(name), style = type.title, color = c.text)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    name ?: number.ifBlank { "Unknown caller" },
                    style = type.title,
                    color = c.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (name != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(number, style = type.number, color = c.muted)
                }
                Spacer(Modifier.height(14.dp))
                CallClock(call, type.timer, c.gold, c.muted)
            }
    }

    // Bottom (portrait) or right half (landscape): the call controls and hang up, always on screen.
    val controls: @Composable () -> Unit = {
            if (call.incoming) {
                // Ringing: Decline and Answer at the bottom, the line named on Answer.
                Row(
                    Modifier.fillMaxWidth().padding(bottom = if (landscape) 0.dp else 36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BigCircle(Icons.Filled.CallEnd, "Decline", EndRed, Color.White, onClick = actions::decline)
                    BigCircle(Icons.Filled.Call, "Answer · ${lines.label(call.lineId)}", c.emerald, c.ground, onClick = actions::answer)
                }
            } else {
                ControlGrid(
                    call, audio, keypadOpen, compact = landscape,
                    onKeypad = { keypadOpen = !keypadOpen }, onTransfer = { transferOpen = true }, actions = actions,
                )
                Spacer(Modifier.height(if (landscape) 14.dp else 24.dp))
                BigCircle(Icons.Filled.CallEnd, "Hang up", EndRed, Color.White, showLabel = false, onClick = actions::hangup)
                Spacer(Modifier.height(if (landscape) 0.dp else 28.dp))
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.surface, c.ground, c.ground)))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    topBar()
                    Spacer(Modifier.height(12.dp))
                    identity()
                    Spacer(Modifier.height(12.dp))
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                topBar()
                Spacer(Modifier.weight(1f))
                identity()
                Spacer(Modifier.weight(1f))
                controls()
            }
        }
    }

    if (transferOpen) {
        TransferDialog(lines.extension, onDismiss = { transferOpen = false }, actions = actions)
    }
}

@Composable
private fun CallClock(call: SipCallSnapshot, style: androidx.compose.ui.text.TextStyle, gold: Color, muted: Color) {
    if (call.connectedAtElapsed <= 0L) {
        Text(if (call.incoming) "Incoming call" else call.stateText.ifBlank { "Calling" }, style = style, color = muted)
        return
    }
    var now by remember(call.connectedAtElapsed) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.connectedAtElapsed) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val shown = formatLiveCallTimer(((now - call.connectedAtElapsed) / 1000L).toInt())
    Text(
        if (call.held) "On hold · $shown" else shown,
        style = style,
        color = if (call.held) muted else gold,
        modifier = Modifier.semantics { contentDescription = "Call time $shown" },
    )
}

@Composable
private fun ControlGrid(
    call: SipCallSnapshot,
    audio: TelecomBridge.Audio,
    keypadOpen: Boolean,
    compact: Boolean = false,
    onKeypad: () -> Unit,
    onTransfer: () -> Unit,
    actions: InCallActions,
) {
    val speakerOn = if (audio.endpoints.isNotEmpty()) audio.onSpeaker else call.speakerOn
    var routeMenu by remember { mutableStateOf(false) }
    val tileHeight = if (compact) 64.dp else 84.dp
    val gap = if (compact) 10.dp else 12.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (call.muted) "Unmute" else "Mute", call.muted, Modifier.weight(1f), tileHeight) {
                actions.toggleMute()
            }
            Tile(Icons.Filled.Dialpad, if (keypadOpen) "Hide keypad" else "Keypad", keypadOpen, Modifier.weight(1f), tileHeight, onClick = onKeypad)
            Tile(Icons.AutoMirrored.Filled.VolumeUp, "Speaker", speakerOn, Modifier.weight(1f), tileHeight) { actions.toggleSpeaker() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(if (call.held) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (call.held) "Resume" else "Hold", call.held, Modifier.weight(1f), tileHeight) {
                actions.toggleHold()
            }
            Tile(Icons.Filled.SwapHoriz, "Transfer", false, Modifier.weight(1f), tileHeight, enabled = call.waiting == null, onClick = onTransfer)
            Box(Modifier.weight(1f)) {
                // Bluetooth / headset only. Speaker on and off is the Speaker button; this one never says "Speaker"
                // (two Speaker buttons on screen confused testers, 2026-10-14).
                val devices = audio.endpoints.filter {
                    it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET
                }
                val onDevice = audio.current?.let { cur -> devices.any { it == cur } } == true
                val shownDevice = audio.current?.takeIf { onDevice } ?: devices.firstOrNull()
                val icon = shownDevice?.let { routeIcon(it) } ?: Icons.Filled.Bluetooth
                val label = shownDevice?.let { routeName(it) } ?: "Bluetooth"
                Tile(icon, label, onDevice, Modifier.fillMaxWidth(), tileHeight, enabled = devices.isNotEmpty()) {
                    val earpiece = audio.endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
                    when {
                        devices.size > 1 -> routeMenu = true // several devices (car and earbuds): pick one
                        onDevice && earpiece != null -> actions.route(earpiece) // back to the phone
                        else -> actions.route(devices.first()) // to the car / headset
                    }
                }
                DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                    (devices + listOfNotNull(audio.endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE })).forEach { e ->
                        DropdownMenuItem(
                            text = { Text(routeName(e) + if (e == audio.current) "  ✓" else "") },
                            leadingIcon = { Icon(routeIcon(e), contentDescription = null) },
                            onClick = {
                                routeMenu = false
                                actions.route(e)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun routeIcon(e: CallEndpointCompat): ImageVector = when (e.type) {
    CallEndpointCompat.TYPE_BLUETOOTH -> Icons.Filled.Bluetooth
    CallEndpointCompat.TYPE_SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    CallEndpointCompat.TYPE_WIRED_HEADSET -> Icons.Filled.Headset
    else -> Icons.Filled.PhoneAndroid
}

/** Bluetooth routes keep the device's own name (the car or headset). */
private fun routeName(e: CallEndpointCompat): String = when (e.type) {
    CallEndpointCompat.TYPE_BLUETOOTH -> e.name.toString().ifBlank { "Bluetooth" }
    CallEndpointCompat.TYPE_SPEAKER -> "Speaker"
    CallEndpointCompat.TYPE_WIRED_HEADSET -> "Headset"
    CallEndpointCompat.TYPE_EARPIECE -> "Phone"
    else -> e.name.toString()
}

@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 84.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val shape = RoundedCornerShape(16.dp)
    val fill = when {
        active -> c.goldTint
        else -> c.surface
    }
    val ink = when {
        !enabled -> c.disabled
        active -> c.gold
        else -> c.text
    }
    Column(
        modifier
            .height(height)
            .background(fill, shape)
            .border(1.dp, if (active) c.gold else c.hairline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label + if (active) ", on" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BigCircle(
    icon: ImageVector,
    label: String,
    fill: Color,
    ink: Color,
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .background(
                    if (fill == EndRed) Brush.verticalGradient(listOf(EndRed, EndRedDeep)) else Brush.verticalGradient(listOf(fill, fill)),
                    CircleShape,
                )
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(32.dp))
        }
        if (showLabel) {
            Spacer(Modifier.height(10.dp))
            Text(label, style = type.label, color = c.text)
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, fill: Color, ink: Color, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .background(fill, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ink)
    }
}

/** Call waiting: "Incoming on 1002" with Hold & answer / Decline, or "On hold" with Swap / End. */
@Composable
private fun WaitingCard(
    w: net.ithandsfree.softphone.sip.SipWaitingSnapshot,
    lines: InCallLines,
    actions: InCallActions,
    consulting: Boolean = false,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val party = partyOf(w.remote)
    val caller = lines.contactName(party) ?: formatDidForDisplay(party).ifBlank { "Caller ID withheld" }
    val lineColor = if (lines.colorIndex(w.lineId) == 1) c.sky else c.emerald
    val label = lines.label(w.lineId)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(if (w.ringing) 2.dp else 1.dp, if (w.ringing) lineColor else c.hairline, shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(lineColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    w.ringing -> "INCOMING ON ${label.uppercase()}"
                    consulting -> "WAITING TO BE TRANSFERRED"
                    else -> "ON HOLD · ${label.uppercase()}"
                },
                style = type.overline,
                color = if (w.ringing) lineColor else c.muted,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(caller, style = type.heading, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (w.ringing) {
                Pill("Hold & answer · $label", c.emerald, c.ground, Modifier.weight(1f), actions::answerWaiting)
                Pill("Decline", c.raised, c.text, Modifier.weight(0.6f), actions::endWaiting)
            } else if (consulting) {
                // Attended transfer: you are talking to the transfer target; the caller waits here.
                Pill("Complete transfer", c.gold, c.ground, Modifier.weight(1f), actions::completeTransfer)
                Pill("Cancel", c.raised, c.text, Modifier.weight(0.6f), actions::cancelTransfer)
            } else {
                Pill("Swap", c.raised, c.text, Modifier.weight(1f), actions::swap)
                Pill("End", c.raised, c.text, Modifier.weight(1f), actions::endWaiting)
            }
        }
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    val type = LocalIhfType.current
    Box(
        modifier
            .height(44.dp)
            .background(fill, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DtmfPad(onDigit: (Char) -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { d ->
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(c.surface, CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Key $d") { onDigit(d) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(d.toString(), style = type.heading.copy(fontSize = 24.sp), color = c.text)
                    }
                }
            }
        }
    }
}

/** Transfer: one tap to the call's own extension (other devices), or any number. */
@Composable
private fun TransferDialog(extension: String?, onDismiss: () -> Unit, actions: InCallActions) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer this call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (extension != null) {
                    TextButton(onClick = {
                        onDismiss()
                        actions.sendToOtherDevices()
                    }) { Text("Send to my other devices ($extension)") }
                    Text("Or send it to someone else:")
                }
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    singleLine = true,
                    label = { Text("Number or extension") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        // Transfer (blind) is the default: one tap sends the caller on. Speak first = attended transfer (same words as the desktop): the caller
        // waits on hold while you call the number, then Complete transfer (or Cancel) on the call screen.
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = number.isNotBlank(), onClick = {
                    onDismiss()
                    actions.consultTransfer(number)
                }) { Text("Speak first") }
                TextButton(enabled = number.isNotBlank(), onClick = {
                    onDismiss()
                    actions.transfer(number)
                }) { Text("Transfer now") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Return to call" bar above the app while the call screen is minimised. */
@Composable
fun ReturnToCallBar(call: SipCallSnapshot, lines: InCallLines, onOpen: () -> Unit) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val party = partyOf(call.remote)
    val who = lines.contactName(party) ?: formatDidForDisplay(party).ifBlank { "Call" }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.emerald)
            .clickable(role = Role.Button, onClickLabel = "Return to call", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = c.ground, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (call.waiting?.ringing == true) "Second call ringing · tap to answer" else "Return to call · $who",
            style = type.label,
            color = c.ground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CallClock(call, type.label, c.ground, c.ground)
    }
}

internal fun partyOf(remote: String): String =
    remote.removePrefix("<").substringAfter(":").substringBefore("@").substringBefore(">").trim()

private fun initials(name: String?): String {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
    return when {
        words.isEmpty() -> "•"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}
