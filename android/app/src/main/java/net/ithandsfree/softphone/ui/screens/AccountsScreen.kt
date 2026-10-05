package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.data.SoftphoneAccount
import net.ithandsfree.softphone.ui.SoftphoneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    vm: SoftphoneViewModel,
    onAdd: () -> Unit,
    onOpenAccount: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SoftphoneAccount?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snack.showSnackbar(it)
            vm.clearError()
        }
    }
    LaunchedEffect(state.status) {
        state.status?.let { snack.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IHF Softphone", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                state.sipSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (state.accounts.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(Modifier.height(12.dp))
                    Text("No extensions yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add a FreePBX User Manager account to sync SMS/MMS.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    )
                    TextButton(onClick = onAdd) { Text("Add account") }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.accounts, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            onOpen = { onOpenAccount(account.id) },
                            onDelete = { pendingDelete = account },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${acc.label}?") },
            text = { Text("Deletes stored credentials and tokens on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccount(acc.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: SoftphoneAccount,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "DID ${account.did.ifBlank { "—" }} · UM ${account.umUsername}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (account.sipExtension.isNotBlank()) {
                    Text(
                        "SIP ${account.sipExtension}@${account.sipDomain.ifBlank { "…" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
