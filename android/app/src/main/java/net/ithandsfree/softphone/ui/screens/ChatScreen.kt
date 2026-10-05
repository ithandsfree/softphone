package net.ithandsfree.softphone.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.ithandsfree.softphone.data.ChatMessage
import net.ithandsfree.softphone.data.SoftphoneAccount
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.NumberText
import net.ithandsfree.softphone.ui.components.ZoomableImageViewer
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun ChatScreen(
    vm: SoftphoneViewModel,
    accountId: String,
    peer: String,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val account = state.accounts.firstOrNull { it.id == accountId }
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    var draft by remember { mutableStateOf("") }
    var attachUri by remember { mutableStateOf<Uri?>(null) }
    var lastFailedBody by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeleteThread by remember { mutableStateOf(false) }
    var pendingDeleteMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // Newest-first + reverseLayout so index 0 sits at the bottom — opens on the
    // latest message (standard chat) without a first-layout scroll race.
    val newestFirst = remember(state.messages) { newestFirstMessages(state.messages) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> attachUri = uri }

    LaunchedEffect(accountId, peer) {
        vm.loadMessages(accountId, peer)
        // Opening the thread marks FreePBX messages read — refresh tab badge soon.
        (context.applicationContext as? net.ithandsfree.softphone.SoftphoneApp)
            ?.refreshInboxUnread()
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            lastFailedBody = draft.ifBlank { lastFailedBody }
            // keep draft for retry UI
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                NumberText(peer, color = c.text)
                Text(account?.label ?: "", style = type.caption, color = c.caption)
            }
            IconButton(onClick = { vm.loadMessages(accountId, peer) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = c.muted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Conversation actions", tint = c.muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete conversation") },
                        onClick = {
                            menuOpen = false
                            confirmDeleteThread = true
                        },
                    )
                }
            }
        }

        state.error?.let { err ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.goldTint)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = c.coral)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text("Couldn't send", style = type.label, color = c.text)
                    Text(err, style = type.caption, color = c.muted)
                }
                Text(
                    "Retry",
                    color = c.gold,
                    style = type.label,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.raised)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .then(
                            Modifier,
                        ),
                )
                // clickable retry via IconButton pattern
                androidx.compose.material3.TextButton(onClick = {
                    val body = lastFailedBody ?: draft
                    if (attachUri != null) {
                        vm.sendMms(accountId, peer, attachUri!!, context.contentResolver)
                    } else if (body.isNotBlank()) {
                        vm.sendSms(accountId, peer, body)
                    }
                    vm.clearError()
                }) { Text("Retry", color = c.gold) }
                androidx.compose.material3.TextButton(onClick = { vm.clearError() }) {
                    Text("Dismiss", color = c.caption)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.busy && state.messages.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.gold)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true,
                ) {
                    items(newestFirst, key = { it.id ?: it.emid ?: it.hashCode() }) { msg ->
                        MessageBubble(
                            msg = msg,
                            account = account,
                            vm = vm,
                            onLongPress = { pendingDeleteMessage = msg },
                        )
                    }
                }
            }
        }

        attachUri?.let { uri ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Attachment",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    "Photo attached · max ~1 MB",
                    style = type.caption,
                    color = c.muted,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                IconButton(onClick = { attachUri = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = c.muted)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = account?.capMms == true,
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = if (account?.capMms == true) c.gold else c.disabled,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (attachUri != null) "Caption (optional)" else "Message", color = c.caption) },
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.hairline,
                    unfocusedBorderColor = c.divider,
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text,
                    cursorColor = c.gold,
                ),
            )
            IconButton(
                onClick = {
                    lastFailedBody = draft
                    val uri = attachUri
                    if (uri != null) {
                        vm.sendMms(accountId, peer, uri, context.contentResolver)
                        attachUri = null
                        draft = ""
                    } else {
                        val body = draft
                        draft = ""
                        vm.sendSms(accountId, peer, body)
                    }
                },
                enabled = (draft.isNotBlank() || attachUri != null) && !state.busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = c.gold)
            }
        }
    }

    if (confirmDeleteThread) {
        AlertDialog(
            onDismissRequest = { confirmDeleteThread = false },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "Deletes this thread from FreePBX SMS for ${account?.label ?: "this line"}. " +
                        "If the server cannot delete it, it will be hidden on this device only.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteThread = false
                        vm.deleteThread(accountId, peer, onDone = onBack)
                    },
                ) { Text("Delete", color = c.coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteThread = false }) { Text("Cancel") }
            },
        )
    }

    pendingDeleteMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMessage = null },
            title = { Text("Delete message?") },
            text = {
                Text(
                    if (msg.id != null) {
                        "Removes this message from FreePBX. If delete is unavailable it is hidden on this device only."
                    } else {
                        "This message has no server id yet — it can only be hidden on this device."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteMessage(accountId, peer, msg)
                        pendingDeleteMessage = null
                    },
                ) { Text("Delete", color = c.coral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessage = null }) { Text("Cancel") }
            },
        )
    }
}

/** Newest message first for reverseLayout chat lists. */
internal fun newestFirstMessages(messages: List<ChatMessage>): List<ChatMessage> =
    messages.sortedWith(
        compareByDescending<ChatMessage> { it.id ?: Int.MIN_VALUE }
            .thenByDescending { it.timestamp?.toLongOrNull() ?: Long.MIN_VALUE },
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    account: SoftphoneAccount?,
    vm: SoftphoneViewModel,
    onLongPress: () -> Unit = {},
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val outbound = msg.direction.equals("out", ignoreCase = true) ||
        msg.direction.equals("outbound", ignoreCase = true) ||
        (account != null && msg.from == account.did)
    val bubbleColor = if (outbound) c.selected else c.raised
    val textColor = c.text
    val align = if (outbound) Alignment.CenterEnd else Alignment.CenterStart
    // Viewer state lives outside the bubble Column so Dialog is not clipped /
    // gesture-blocked by SelectionContainer or bubble combinedClickable.
    var zoomRequest by remember { mutableStateOf<ImageRequest?>(null) }
    var zoomDescription by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(12.dp),
        ) {
            msg.body?.takeIf { it.isNotBlank() }?.let { body ->
                // Long-press / drag to select and copy an SMS body.
                // Long-press for delete stays on text/timestamp only — NOT on media —
                // so tap-to-zoom is never swallowed by bubble combinedClickable.
                SelectionContainer {
                    Text(
                        body,
                        color = textColor,
                        style = type.body,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onLongPress,
                        ),
                    )
                }
            }
            if (account != null) {
                msg.media.forEach { media ->
                    val rawUrl = media.url
                    val mediaName = media.name.ifBlank {
                        rawUrl?.substringAfterLast('/')?.let { Uri.decode(it) }.orEmpty()
                    }
                    val absolute = when {
                        rawUrl.isNullOrBlank() -> vm.mediaUrl(account, mediaName)
                        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                        else -> vm.mediaUrl(
                            account,
                            mediaName.ifBlank { Uri.decode(rawUrl.substringAfterLast('/')) },
                        )
                    }
                    val headers = vm.tokenHeader(account.id)
                    val ctx = LocalContext.current
                    var loadFailed by remember(absolute) { mutableStateOf(false) }
                    val request = remember(absolute, headers) {
                        ImageRequest.Builder(ctx)
                            .data(absolute)
                            .apply {
                                if (headers != null) addHeader(headers.first, headers.second)
                            }
                            .crossfade(true)
                            .build()
                    }
                    val zoomable = isZoomableChatMedia(media.type, mediaName, rawUrl)
                    if (loadFailed) {
                        Text(
                            "Media unavailable (${mediaName.ifBlank { "attachment" }})",
                            color = c.coral,
                            style = type.caption,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        val label = mediaName.ifBlank { "MMS image" }
                        AsyncImage(
                            model = request,
                            contentDescription = label,
                            contentScale = ContentScale.Fit,
                            onError = { loadFailed = true },
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 240.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .semantics {
                                    contentDescription = if (zoomable) {
                                        "Enlarge $label"
                                    } else {
                                        label
                                    }
                                }
                                .then(
                                    if (zoomable) {
                                        Modifier.clickable {
                                            zoomRequest = request
                                            zoomDescription = label
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            val whenText = msg.datetime ?: msg.timestamp
            if (!whenText.isNullOrBlank()) {
                Text(
                    whenText,
                    style = type.caption,
                    color = c.caption,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                )
            } else if (msg.body.isNullOrBlank() && msg.media.isEmpty()) {
                // Empty bubble still needs a long-press target for delete.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                )
            }
        }
    }

    zoomRequest?.let { model ->
        ZoomableImageViewer(
            model = model,
            contentDescription = zoomDescription,
            onDismiss = {
                zoomRequest = null
                zoomDescription = null
            },
        )
    }
}
