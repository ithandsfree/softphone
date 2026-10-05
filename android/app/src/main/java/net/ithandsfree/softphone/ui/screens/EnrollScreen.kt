package net.ithandsfree.softphone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.data.formatDidForDisplay
import net.ithandsfree.softphone.data.looksLikeEnrolToken
import net.ithandsfree.softphone.data.normalizeEnrolToken
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.components.SecretTextField
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.LocalIhfType

/**
 * Advanced enrol form (Welcome → Advanced setup, or deep-link token prefill).
 *
 * Preferred product path for Add a line is the Welcome kit (email / code / QR).
 * This screen stays as the staff/manual fallback: paste HTTPS enrol URL or bare
 * token, optional UM username/password, optional SIP secret for calling.
 */
@Composable
fun EnrollScreen(
    vm: SoftphoneViewModel,
    enrolTokenFromDeepLink: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    var label by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var sipExt by remember { mutableStateOf("") }
    var sipPass by remember { mutableStateOf("") }
    var apiBase by remember {
        mutableStateOf(state.serverBase.ifBlank { BuildConfig.DEFAULT_API_BASE })
    }
    var sipDomain by remember {
        mutableStateOf(state.serverSipDomain.ifBlank { BuildConfig.DEFAULT_SIP_DOMAIN })
    }
    var enrolToken by remember {
        mutableStateOf(
            normalizeEnrolToken(enrolTokenFromDeepLink ?: state.pendingEnrolToken.orEmpty()),
        )
    }
    var submitted by remember { mutableStateOf(false) }
    var selectedDid by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.gold,
        unfocusedBorderColor = c.hairline,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        cursorColor = c.gold,
        focusedLabelColor = c.muted,
        unfocusedLabelColor = c.caption,
    )

    LaunchedEffect(enrolTokenFromDeepLink) {
        val normalized = normalizeEnrolToken(enrolTokenFromDeepLink)
        if (normalized.isNotBlank()) {
            vm.setPendingEnrolToken(normalized)
            enrolToken = normalized
        }
    }
    LaunchedEffect(state.pendingEnrol) {
        selectedDid = state.pendingEnrol?.dids?.firstOrNull()
    }
    LaunchedEffect(state.busy, state.error, state.status, submitted, state.pendingEnrol) {
        if (submitted && !state.busy && state.error == null && state.status != null && state.pendingEnrol == null) {
            onDone()
        }
    }

    val pending = state.pendingEnrol
    if (pending != null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.ground)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Choose DID", style = type.title, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "This User Manager account has more than one SMS number. " +
                    "Pick the DID for ${label.ifBlank { pending.label }.ifBlank { "this line" }}" +
                    (if (sipExt.isNotBlank()) " (ext $sipExt)" else "") + ".",
                style = type.body,
                color = c.muted,
            )
            Spacer(Modifier.height(16.dp))
            pending.dids.forEach { did ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectedDid = did }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedDid == did,
                        onClick = { selectedDid = did },
                        colors = RadioButtonDefaults.colors(selectedColor = c.gold),
                    )
                    Text(
                        formatDidForDisplay(did),
                        style = type.number,
                        color = c.text,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (state.busy) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = c.gold)
            } else {
                Button(
                    onClick = {
                        selectedDid?.let {
                            submitted = true
                            vm.confirmPendingEnrol(it)
                        }
                    },
                    enabled = selectedDid != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
                ) { Text("Save line") }
            }
            TextButton(
                onClick = {
                    submitted = false
                    vm.cancelPendingEnrol()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Back", color = c.muted)
            }
        }
        return
    }

    val tokenNorm = normalizeEnrolToken(enrolToken)
    val canTokenEnrol = looksLikeEnrolToken(tokenNorm)
    val canUmEnrol = username.isNotBlank() && password.isNotBlank()
    val serverOk = !BuildConfig.SERVER_EDITABLE ||
        (apiBase.trim().startsWith("https://") && sipDomain.isNotBlank())
    val canSubmit = (canTokenEnrol || canUmEnrol) && serverOk

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            // Shrink the scroll viewport by the keyboard height so focused
            // fields scroll above the IME instead of hiding behind it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Advanced setup", style = type.title, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste the HTTPS enrol link or token from email/SMS. " +
                "That alone is enough to enrol. Optional: FreePBX User Manager login, " +
                "and SIP secret for calling. Up to two extensions per device.",
            style = type.body,
            color = c.muted,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = enrolToken,
            onValueChange = {
                val stored = if (it.contains("://") || it.contains('/')) {
                    normalizeEnrolToken(it).ifBlank { it }
                } else {
                    it
                }
                enrolToken = stored
                vm.setPendingEnrolToken(normalizeEnrolToken(stored).ifBlank { null })
            },
            label = { Text("Enrol token or HTTPS link") },
            placeholder = { Text("Paste from email / SMS") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors,
        )
        if (enrolToken.isNotBlank() && !canTokenEnrol && !canUmEnrol) {
            Text(
                "Token looks incomplete — paste the full HTTPS enrol URL or the full hex token.",
                color = c.coral,
                style = type.body,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Line label (optional)") },
            placeholder = { Text("Personal / Business") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("UM username (optional if token set)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors,
        )
        SecretTextField(
            value = password,
            onValueChange = { password = it },
            label = "UM password (optional if token set)",
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(12.dp))
        if (BuildConfig.SERVER_EDITABLE) {
            Text("Your FreePBX server", style = type.label, color = c.caption)
            OutlinedTextField(
                value = apiBase,
                onValueChange = { apiBase = it },
                label = { Text("Softphone URL") },
                placeholder = { Text("https://pbx.example.com/ihf-softphone/index.php") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors,
            )
            OutlinedTextField(
                value = sipDomain,
                onValueChange = { sipDomain = it },
                label = { Text("SIP domain") },
                placeholder = { Text("pbx.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text("SIP (optional for SMS — needed for calling)", style = type.label, color = c.caption)
        OutlinedTextField(
            value = sipExt,
            onValueChange = { sipExt = it },
            label = { Text("Extension") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors,
        )
        SecretTextField(
            value = sipPass,
            onValueChange = { sipPass = it },
            label = "SIP secret",
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        Spacer(Modifier.height(20.dp))
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = c.gold)
        } else {
            Button(
                onClick = {
                    submitted = true
                    val token = normalizeEnrolToken(enrolToken)
                    if (canUmEnrol) {
                        vm.addAccount(
                            label = label,
                            umUsername = username,
                            umPassword = password,
                            sipExtension = sipExt,
                            sipPassword = sipPass,
                            apiBase = apiBase.trim(),
                            sipDomain = sipDomain.trim(),
                        )
                    } else {
                        vm.enrolWithToken(
                            enrolToken = token,
                            label = label,
                            sipExtension = sipExt,
                            sipPassword = sipPass,
                            apiBase = apiBase.trim(),
                            sipDomain = sipDomain.trim(),
                        )
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.gold, contentColor = c.goldInk),
            ) { Text("Enrol line") }
        }
        if (!canSubmit) {
            Text(
                "Paste an enrol token (or HTTPS enrol URL) — or enter User Manager username and password — to enable Enrol.",
                color = c.muted,
                style = type.body,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        state.error?.let {
            Text(it, color = c.coral, style = type.body, modifier = Modifier.padding(top = 12.dp))
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancel", color = c.muted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
