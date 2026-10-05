package net.ithandsfree.softphone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.sip.SipCallSnapshot
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun InCallBanner(
    call: SipCallSnapshot,
    onHangup: () -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!call.active) return
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val remote = call.remote
        .removePrefix("<")
        .substringAfter(":")
        .substringBefore("@")
        .substringBefore(">")
        .ifBlank { call.remote }
        .let { formatDidForDisplay(it).ifBlank { it } }

    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (call.incoming) "Incoming call" else call.stateText.ifBlank { "In call" },
            style = type.caption,
            color = c.caption,
        )
        Text(remote.ifBlank { "Unknown" }, style = type.title, color = c.text)
        Spacer(Modifier.height(12.dp))
        if (call.incoming) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.coral),
                ) {
                    Text("Decline")
                }
                Button(
                    onClick = onAnswer,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
                ) {
                    Text("Answer")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleSpeaker,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (call.speakerOn) c.goldInk else c.gold,
                        containerColor = if (call.speakerOn) c.gold else c.surface,
                    ),
                ) {
                    Icon(
                        if (call.speakerOn) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = null,
                    )
                    Text(
                        if (call.speakerOn) "  Speaker on" else "  Speaker",
                        maxLines = 1,
                    )
                }
                Button(
                    onClick = onHangup,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.coral, contentColor = c.coralInk),
                ) {
                    Text("Hang up")
                }
            }
        }
    }
}
