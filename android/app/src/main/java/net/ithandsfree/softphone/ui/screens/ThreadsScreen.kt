package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.ThreadInfo
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.formatSmsTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(
    vm: SoftphoneViewModel,
    accountId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val account = state.accounts.firstOrNull { it.id == accountId }

    LaunchedEffect(accountId) {
        vm.loadThreads(accountId)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snack.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(account?.label ?: "Threads")
                        Text(
                            "DID ${account?.did.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadThreads(accountId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        when {
            state.busy && state.threads.isEmpty() -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.threads.isEmpty() -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No threads for this DID yet")
                }
            }
            else -> {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(pad),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.threads, key = { it.threadId ?: it.peer }) { thread ->
                        ThreadRow(thread) { onOpenChat(thread.peer) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadInfo, onOpen: () -> Unit) {
    val whenText = formatSmsTimestamp(thread.lastMessageAt)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    thread.peer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (whenText.isNotBlank()) {
                    Text(
                        whenText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                thread.snippet?.ifBlank { "(no preview)" } ?: "(no preview)",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
