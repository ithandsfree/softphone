package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.ThreadInfo
import net.ithandsfree.softphone.data.formatPeerForDisplay
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.EmptyState
import net.ithandsfree.softphone.ui.components.LineRail
import net.ithandsfree.softphone.ui.formatSmsTimestamp
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

@Composable
fun MessagesScreen(
    vm: SoftphoneViewModel,
    onOpenChat: (accountId: String, peer: String) -> Unit,
    onCompose: (accountId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    // Viewing line is independent of the default/outbound extension.
    val viewId = state.messagesLineId
    val viewLine = state.lines.firstOrNull { it.id == viewId }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ThreadInfo?>(null) }

    LaunchedEffect(viewId) {
        if (!viewId.isNullOrBlank()) vm.loadThreads(viewId)
    }
    // When the process-wide poller sees new inbound unread, refresh open inbox dots.
    LaunchedEffect(state.inboxUnreadTotal) {
        val id = viewId ?: return@LaunchedEffect
        if (state.inboxUnreadTotal > 0) vm.loadThreads(id)
    }
    LaunchedEffect(Unit) {
        // Thread rows prefer a contact name over the raw DID.
        if (state.contacts.isEmpty() && !state.contactsPermissionDenied) {
            vm.loadDeviceContacts()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Messages",
                style = type.title,
                color = c.text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = { viewId?.let { vm.loadThreads(it) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = c.muted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Message actions", tint = c.muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Mark this line read") },
                        onClick = {
                            menuOpen = false
                            vm.markAllRead(allLines = false)
                        },
                        enabled = viewId != null,
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all lines read") },
                        onClick = {
                            menuOpen = false
                            vm.markAllRead(allLines = true)
                        },
                        enabled = state.accounts.isNotEmpty(),
                    )
                }
            }
        }
        LineRail(
            lines = state.lines,
            activeId = viewId,
            onSelect = { vm.setMessagesLine(it) },
        )

        when {
            viewLine == null -> EmptyState(
                title = "No line selected",
                body = "Enrol an extension to see SMS threads.",
            )
            !viewLine.smsEnabled -> EmptyState(
                title = "SMS is off for this line",
                body = "Your FreePBX admin disabled messaging on this extension. Voice still works when calling is ready.",
            )
            state.offline && state.threads.isEmpty() -> EmptyState(
                title = "You're offline",
                body = "Can't reach the PBX messaging service. Check Wi‑Fi or cellular and try again.",
                primaryLabel = "Retry",
                onPrimary = { viewId?.let { vm.loadThreads(it) } },
            )
            state.busy && state.threads.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = c.gold) }
            state.threads.isEmpty() -> Box(Modifier.fillMaxSize()) {
                EmptyState(
                    title = "No conversations yet",
                    body = "Start a message to a contact or number from this line.",
                    primaryLabel = "New message",
                    onPrimary = { viewId?.let(onCompose) },
                )
            }
            else -> Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(state.threads, key = { it.threadId ?: it.peer }) { thread ->
                        ThreadRow(
                            thread = thread,
                            displayName = vm.resolveContactName(thread.peer),
                            unread = thread.unread > 0,
                            c = c,
                            type = type,
                            onOpen = { viewId?.let { onOpenChat(it, thread.peer) } },
                            onLongPress = { pendingDelete = thread },
                        )
                    }
                }
                FloatingActionButton(
                    onClick = { viewId?.let(onCompose) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
                    containerColor = c.gold,
                    contentColor = c.goldInk,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New message")
                }
            }
        }
    }

    pendingDelete?.let { thread ->
        val title = vm.resolveContactName(thread.peer)
            .ifBlank { formatPeerForDisplay(thread.peer) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "Removes the thread with $title from FreePBX SMS for this line. " +
                        "If the server cannot delete it, it will be hidden on this device only.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = viewId
                        if (id != null) {
                            vm.deleteThread(id, thread.peer, thread.threadId)
                        }
                        pendingDelete = null
                    },
                ) { Text("Delete", color = c.coral) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(
    thread: ThreadInfo,
    displayName: String,
    unread: Boolean,
    c: net.ithandsfree.softphone.ui.theme.IhfColors,
    type: net.ithandsfree.softphone.ui.theme.IhfType,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val whenText = formatSmsTimestamp(thread.lastMessageAt)
    val title = displayName.ifBlank { formatPeerForDisplay(thread.peer) }
    val snippet = thread.snippet?.trim().orEmpty()
    val preview = when {
        snippet.isBlank() -> "No messages yet"
        thread.direction.equals("out", ignoreCase = true) -> "You: $snippet"
        else -> snippet
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(c.raised),
            contentAlignment = Alignment.Center,
        ) {
            val initials = threadInitials(displayName)
            if (initials.isBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    tint = c.muted,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(initials, style = type.label, color = c.muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = type.heading,
                    color = c.text,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (whenText.isNotBlank()) {
                    Text(
                        whenText,
                        style = type.caption,
                        color = if (unread) c.gold else c.caption,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preview,
                    style = type.body,
                    color = when {
                        snippet.isBlank() -> c.caption
                        unread -> c.text
                        else -> c.muted
                    },
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(c.coral),
                    )
                }
            }
        }
    }
}

private fun threadInitials(displayName: String): String =
    displayName
        .split(' ', '-')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
