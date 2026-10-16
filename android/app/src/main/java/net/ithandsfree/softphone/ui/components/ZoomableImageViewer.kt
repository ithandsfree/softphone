package net.ithandsfree.softphone.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import net.ithandsfree.softphone.data.ChatImageExport

/** The picture's address on the BFF, for Save / Share / Copy in the viewer. */
data class PictureActions(val url: String, val header: Pair<String, String>?, val name: String)

/**
 * Full-screen MMS / chat image viewer: pinch-zoom, pan when zoomed,
 * double-tap toggle, close via X / back / tap when at 1x. With [pictureActions], Save, Share and Copy along the bottom.
 */
@Composable
fun ZoomableImageViewer(
    model: Any,
    contentDescription: String?,
    onDismiss: () -> Unit,
    pictureActions: PictureActions? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next <= 1.01f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                        }
                    }
                    .pointerInput(scale) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            // Press and hold must not close the picture (it reads as a tap when unhandled).
                            onLongPress = {},
                            onTap = {
                                if (scale <= 1.01f) onDismiss()
                            },
                        )
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
            if (pictureActions != null) {
                // Along the top, beside Close: at the bottom the bar sat under Android's navigation bar (0.4.23).
                PictureActionBar(
                    pictureActions,
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(end = 52.dp),
                )
            }
        }
    }
}

@Composable
private fun PictureActionBar(actions: PictureActions, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun run(what: String, block: suspend (ChatImageExport.Picture) -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val message = try {
                val picture = ChatImageExport.download(context, actions.url, actions.header, actions.name)
                block(picture)
            } catch (e: Exception) {
                "Couldn't $what the picture (${e.message ?: "network"})"
            }
            busy = false
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        ActionButton("Save", Icons.Default.Download, enabled = !busy) {
            run("save") { picture ->
                if (ChatImageExport.saveToPhotos(context, picture)) {
                    "Saved to Photos (Pictures/IHF Phone)"
                } else {
                    // Android 8 and 9: saving to the gallery needs a storage permission the app does not ask for.
                    ChatImageExport.share(context, picture)
                    null
                }
            }
        }
        ActionButton("Share", Icons.Default.Share, enabled = !busy) {
            run("share") { picture ->
                ChatImageExport.share(context, picture)
                null
            }
        }
        ActionButton("Copy", Icons.Default.ContentCopy, enabled = !busy) {
            run("copy") { picture ->
                ChatImageExport.copy(context, picture)
                // Android 13+ shows its own "Copied" notice.
                if (android.os.Build.VERSION.SDK_INT < 33) "Picture copied" else null
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Text(label, color = Color.White, modifier = Modifier.padding(start = 6.dp))
    }
}
