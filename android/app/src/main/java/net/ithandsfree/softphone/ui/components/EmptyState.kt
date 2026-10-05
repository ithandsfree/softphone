package net.ithandsfree.softphone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun EmptyState(
    title: String,
    body: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = type.titleSm, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = type.body, color = c.muted, textAlign = TextAlign.Center)
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onPrimary,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.gold,
                    contentColor = c.goldInk,
                ),
            ) { Text(primaryLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel, color = c.muted)
            }
        }
    }
}
