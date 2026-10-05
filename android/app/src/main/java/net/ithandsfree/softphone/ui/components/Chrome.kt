package net.ithandsfree.softphone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LineUi
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun NumberText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = IhfThemeAccess.colors.text,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = LocalIhfType.current.number,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Back bar shared by every pushed screen (Contacts, Chat, Line detail, …) so a
 * sub-screen always has a visible way out, not just the system gesture.
 */
@Composable
fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = type.heading,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = type.caption,
                    color = c.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/**
 * Password field with an eye toggle — operators need to read back SIP/UM
 * secrets they typed on a phone keyboard before submitting.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    supportingText: String? = null,
) {
    val c = IhfThemeAccess.colors
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password,
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide $label" else "Show $label",
                    tint = c.muted,
                )
            }
        },
        supportingText = supportingText?.let { { Text(it, style = LocalIhfType.current.caption) } },
        colors = colors,
    )
}

enum class SoftphoneTab { Calls, Messages, Lines, Settings }

@Composable
fun SoftphoneBottomBar(
    selected: SoftphoneTab,
    messagesUnread: Boolean = false,
    onSelect: (SoftphoneTab) -> Unit,
) {
    val c = IhfThemeAccess.colors
    NavigationBar(
        containerColor = c.surface,
        contentColor = c.text,
    ) {
        TabItem(SoftphoneTab.Calls, "Calls", Icons.Default.Call, selected, onSelect)
        TabItem(
            SoftphoneTab.Messages,
            "Messages",
            Icons.AutoMirrored.Filled.Chat,
            selected,
            onSelect,
            showDot = messagesUnread,
        )
        TabItem(SoftphoneTab.Lines, "Lines", Icons.Default.PhoneAndroid, selected, onSelect)
        TabItem(SoftphoneTab.Settings, "Settings", Icons.Default.Settings, selected, onSelect)
    }
}

@Composable
private fun RowScope.TabItem(
    tab: SoftphoneTab,
    label: String,
    icon: ImageVector,
    selected: SoftphoneTab,
    onSelect: (SoftphoneTab) -> Unit,
    showDot: Boolean = false,
) {
    val c = IhfThemeAccess.colors
    val active = selected == tab
    NavigationBarItem(
        selected = active,
        onClick = { onSelect(tab) },
        icon = {
            Box {
                Icon(icon, contentDescription = label)
                if (showDot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(c.coral),
                    )
                }
            }
        },
        label = {
            Text(
                label,
                style = LocalIhfType.current.caption,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = c.gold,
            selectedTextColor = c.gold,
            unselectedIconColor = c.caption,
            unselectedTextColor = c.caption,
            indicatorColor = c.goldTint,
        ),
    )
}

/**
 * Two-segment line rail. Chip order is enrolment order (stable). Selection only
 * highlights — it must not reorder. Colour slots: emerald = first enrolled, sky =
 * second — never hardcode brand → colour.
 */
@Composable
fun LineRail(
    lines: List<LineUi>,
    activeId: String?,
    otherBadge: RailBadge = RailBadge.None,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    // Keep enrolment order; never sort by selection.
    val ordered = lines.take(2)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ordered.forEach { line ->
            val selected = line.id == activeId
            val slotColor = c.lineSlot(line.colorSlot)
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) c.railActiveBg else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(line.id) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(slotColor),
                    )
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(
                            line.label,
                            color = if (selected) c.text else c.muted,
                            style = type.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        NumberText(
                            text = formatDidForDisplay(line.did.ifBlank { line.ext }),
                            color = if (selected) slotColor else c.caption,
                        )
                    }
                    if (!selected && otherBadge != RailBadge.None) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (otherBadge) {
                                        RailBadge.Missed -> c.coral
                                        RailBadge.Unread -> c.gold
                                        RailBadge.None -> c.gold
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

enum class RailBadge { None, Unread, Missed }
