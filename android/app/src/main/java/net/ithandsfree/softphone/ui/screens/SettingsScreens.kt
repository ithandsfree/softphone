package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.KeypadPrefs
import net.ithandsfree.softphone.data.SkinPrefs
import net.ithandsfree.softphone.notify.NotifyPrefs
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType
import net.ithandsfree.softphone.ui.theme.SoftphoneSkin
import net.ithandsfree.softphone.ui.theme.SoftphoneSkins

@Composable
fun SettingsScreen(
    vm: SoftphoneViewModel,
    skinPrefs: SkinPrefs,
    keypadPrefs: KeypadPrefs,
    notifyPrefs: NotifyPrefs,
    onOpenLine: (String) -> Unit,
    onEnrol: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val skinId by skinPrefs.skinId.collectAsState()
    val skin = SoftphoneSkins.resolve(skinId)
    val soundOn by keypadPrefs.soundEnabled.collectAsState()
    val vibeOn by keypadPrefs.vibrateEnabled.collectAsState()
    val messageSyncOn by notifyPrefs.messageSyncEnabled.collectAsState()
    val sipKeepAliveOn by notifyPrefs.sipKeepAliveEnabled.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = type.title, color = c.text)
        Spacer(Modifier.height(16.dp))
        Text("APPEARANCE", style = type.overline, color = c.caption)
        SettingsRow(
            title = "Skin",
            subtitle = "${skin.displayName} · tap to change",
            onClick = onOpenAppearance,
        )
        Spacer(Modifier.height(16.dp))
        Text("VOICE", style = type.overline, color = c.caption)
        SettingsToggleRow(
            title = "Keep registered",
            subtitle = "Background SIP registration for incoming calls " +
                "(quiet “Voice ready” notification). Required for ring when " +
                "minimized. Status: ${state.sipSummary.ifBlank { "—" }}",
            checked = sipKeepAliveOn,
            onCheckedChange = notifyPrefs::setSipKeepAliveEnabled,
        )
        Spacer(Modifier.height(16.dp))
        Text("MESSAGES", style = type.overline, color = c.caption)
        SettingsToggleRow(
            title = "Message sync",
            subtitle = "Background SMS/MMS checks while the app is minimized " +
                "(shows a quiet ongoing notification).",
            checked = messageSyncOn,
            onCheckedChange = notifyPrefs::setMessageSyncEnabled,
        )
        Text(
            "Battery: on Samsung / Pixel / OnePlus, set IHF Phone to " +
                "Unrestricted (Settings → Apps → IHF Phone → Battery). Otherwise " +
                "Doze can drop REGISTER overnight and messages may show offline " +
                "until you open the app.",
            style = type.caption,
            color = c.caption,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("KEYPAD", style = type.overline, color = c.caption)
        SettingsToggleRow(
            title = "Keypad sound",
            subtitle = "DTMF tone when pressing dial-pad digits",
            checked = soundOn,
            onCheckedChange = keypadPrefs::setSoundEnabled,
        )
        SettingsToggleRow(
            title = "Keypad vibration",
            subtitle = "Short haptic on each digit (skipped in silent mode)",
            checked = vibeOn,
            onCheckedChange = keypadPrefs::setVibrateEnabled,
        )
        Spacer(Modifier.height(16.dp))
        Text("DEFAULT EXTENSION", style = type.overline, color = c.caption)
        Text(
            "When you open the app, Messages and Calls start on this line. " +
                "Changing Calling-from on the keypad is temporary and does not change this default. " +
                "Messages chips only change which inbox you view.",
            style = type.caption,
            color = c.caption,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (state.lines.isEmpty()) {
            SettingsRow("No lines yet", "Enrol an extension first", onEnrol)
        } else {
            state.lines.forEach { line ->
                DefaultExtensionRow(
                    label = line.label,
                    selected = line.id == state.activeLineId,
                    onSelect = { vm.setDefaultLine(line.id) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("LINES", style = type.overline, color = c.caption)
        state.lines.forEach { line ->
            SettingsRow(line.label, "Ringtone & messaging") { onOpenLine(line.id) }
        }
        if (state.lines.size < 2) {
            SettingsRow("Enrol another line", "Deep link or UM login", onEnrol)
        }
        Spacer(Modifier.height(16.dp))
        Text("ADVANCED", style = type.overline, color = c.caption)
        SettingsRow("SIP status", state.sipSummary.ifBlank { "PJSIP — not configured" })
        SettingsRow("API", "Softphone BFF")
        if (state.call.active) {
            SettingsRow(
                "Active call",
                "${state.call.stateText} · ${state.call.remote}",
            ) { vm.hangupCall() }
        }
    }
}

@Composable
fun AppearanceScreen(
    skinPrefs: SkinPrefs,
    onBack: () -> Unit,
) {
    val skinId by skinPrefs.skinId.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = c.text,
                )
            }
            Text("Appearance", style = type.titleSm, color = c.text)
        }
        Text(
            "Skins are token packs — one app, many brands. Your pick overrides the tenant default.",
            style = type.body,
            color = c.muted,
        )
        Spacer(Modifier.height(16.dp))
        SoftphoneSkins.all.forEach { pack ->
            SkinOptionRow(
                skin = pack,
                selected = pack.id.id == skinId,
                onSelect = { skinPrefs.setUserSkin(pack.id.id) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SkinOptionRow(
    skin: SoftphoneSkin,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val radius = IhfThemeAccess.radius
    val shape = RoundedCornerShape(radius.card)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.selected else c.surface)
            .border(
                width = 1.dp,
                color = if (selected) c.gold else c.hairline,
                shape = shape,
            )
            .clickable(onClick = onSelect)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkinSwatch(skin)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(skin.displayName, style = type.heading, color = c.text)
            Text(skin.summary, style = type.caption, color = c.caption)
            Text(
                "${skin.brandMark} · ${skin.id.id}",
                style = type.overline,
                color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = c.gold)
        }
    }
}

@Composable
private fun SkinSwatch(skin: SoftphoneSkin) {
    val colors = skin.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(colors.ground)
            .border(1.dp, colors.hairline, shape)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(12.dp)
                .clip(CircleShape)
                .background(colors.gold),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.emerald),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.sky),
        )
    }
}

@Composable
private fun DefaultExtensionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.body, color = c.text, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Default", tint = c.gold)
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = type.body, color = c.text)
        Text(subtitle, style = type.caption, color = c.caption)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = type.body, color = c.text)
            Text(subtitle, style = type.caption, color = c.caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.goldInk,
                checkedTrackColor = c.gold,
                uncheckedThumbColor = c.muted,
                uncheckedTrackColor = c.raised,
            ),
        )
    }
}
