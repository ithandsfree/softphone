package net.ithandsfree.softphone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/**
 * One destination in an [IconNavRow]. [description] is what TalkBack reads, so it
 * may be longer than the on-screen [label].
 */
data class IconNavItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector,
    val description: String = label,
    val badge: Boolean = false,
)

/**
 * Icon-forward segmented nav for sub-destinations inside a tab.
 *
 * The glyph carries the meaning (it survives translation and narrow screens);
 * the short label underneath keeps it learnable on first run.
 */
@Composable
fun <T> IconNavRow(
    items: List<IconNavItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val active = item.value == selected
            Column(
                Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) c.gold else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(item.value) },
                    )
                    .semantics(mergeDescendants = true) { contentDescription = item.description }
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (active) c.goldInk else c.muted,
                        modifier = Modifier.size(24.dp),
                    )
                    if (item.badge) {
                        Box(
                            Modifier
                                .offset(x = 3.dp, y = (-2).dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(c.coral),
                        )
                    }
                }
                Text(
                    item.label,
                    style = type.caption,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) c.goldInk else c.caption,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
