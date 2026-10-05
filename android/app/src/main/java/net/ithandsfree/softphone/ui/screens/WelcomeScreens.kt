package net.ithandsfree.softphone.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.ithandsfree.softphone.BuildConfig
import net.ithandsfree.softphone.R
import net.ithandsfree.softphone.data.looksLikeEnrolToken
import net.ithandsfree.softphone.data.normalizeEnrolToken
import net.ithandsfree.softphone.ui.SoftphoneViewModel
import net.ithandsfree.softphone.ui.theme.IhfThemeAccess
import net.ithandsfree.softphone.ui.theme.InstrumentSerifFamily
import net.ithandsfree.softphone.ui.theme.LocalIhfType
import java.util.Locale

private val FieldShape = RoundedCornerShape(12.dp)
private val IconWellShape = RoundedCornerShape(11.dp)

private fun looksLikeEmail(raw: String): Boolean {
    val s = raw.trim()
    if (s.length < 5 || s.length > 254) return false
    val at = s.indexOf('@')
    if (at <= 0 || at != s.lastIndexOf('@')) return false
    val domain = s.substring(at + 1)
    return domain.contains('.') && !s.contains(' ')
}

@Composable
private fun welcomeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = IhfThemeAccess.colors.gold,
    unfocusedBorderColor = IhfThemeAccess.colors.hairline,
    focusedContainerColor = IhfThemeAccess.colors.surface,
    unfocusedContainerColor = IhfThemeAccess.colors.surface,
    focusedTextColor = IhfThemeAccess.colors.text,
    unfocusedTextColor = IhfThemeAccess.colors.text,
    cursorColor = IhfThemeAccess.colors.gold,
    focusedLabelColor = IhfThemeAccess.colors.muted,
    unfocusedLabelColor = IhfThemeAccess.colors.caption,
    focusedPlaceholderColor = IhfThemeAccess.colors.caption,
    unfocusedPlaceholderColor = IhfThemeAccess.colors.caption,
)

/**
 * Pre-enrol Welcome — design handoff welcome.html.
 * Hero + work email + Email me a setup link; QR / code / Advanced alternatives.
 *
 * Also used for **Add a line** when one+ lines are already enrolled (`addingLine`),
 * so second-line setup is the same kit — not the advanced-only form.
 */
@Composable
fun WelcomeScreen(
    vm: SoftphoneViewModel,
    onLinkSent: (email: String) -> Unit,
    onScanQr: () -> Unit,
    onEnterCode: () -> Unit,
    onAdvanced: () -> Unit,
    addingLine: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val skin = IhfThemeAccess.skin
    val keyboard = LocalSoftwareKeyboardController.current
    var email by rememberSaveable { mutableStateOf("") }
    var pbxUrl by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_API_BASE) }
    var inlineError by rememberSaveable { mutableStateOf<String?>(null) }
    val emailOk = looksLikeEmail(email)
    val serverOk = !BuildConfig.SERVER_EDITABLE || pbxUrl.trim().startsWith("https://")

    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    LaunchedEffect(state.enrolRequestDoneFor) {
        val sentFor = state.enrolRequestDoneFor ?: return@LaunchedEffect
        onLinkSent(sentFor)
        vm.clearEnrolRequestResult()
    }
    LaunchedEffect(state.error) {
        if (state.error != null && !state.busy) {
            inlineError = state.error
        }
    }

    fun submit() {
        if (!emailOk || state.busy) return
        if (!serverOk) {
            inlineError = "Enter the HTTPS softphone URL on your PBX"
            return
        }
        if (state.offline) {
            inlineError = "No connection"
            return
        }
        inlineError = null
        keyboard?.hide()
        vm.requestEnrolLink(email.trim(), pbxUrl.trim())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        WelcomeHero(
            heightDp = if (addingLine) 280 else 332,
            brandTitle = BuildConfig.PRODUCT_NAME.ifBlank { skin.productName },
            brandSub = BuildConfig.BRAND_SUB,
            showBack = onBack != null,
            onBack = { onBack?.invoke() },
            headline = {
                Text(
                    buildAnnotatedString {
                        if (addingLine) {
                            append("Add another\n")
                            withStyle(
                                SpanStyle(
                                    color = c.gold,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = InstrumentSerifFamily,
                                ),
                            ) { append("line.") }
                        } else {
                            append("Work from\n")
                            withStyle(
                                SpanStyle(
                                    color = c.gold,
                                    fontStyle = FontStyle.Italic,
                                    fontFamily = InstrumentSerifFamily,
                                ),
                            ) { append("anywhere.") }
                        }
                    },
                    style = type.display.copy(
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontFamily = InstrumentSerifFamily,
                    ),
                    color = c.text,
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!addingLine) {
                FeatureRows()
            } else {
                Text(
                    "Same setup as your first line — email a link for your other extension, " +
                        "or enter a code / scan a QR. Up to two lines on this phone.",
                    style = type.body,
                    color = c.muted,
                )
            }
            if (state.offline) {
                Text(
                    "No connection",
                    style = type.label,
                    color = c.coral,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            if (BuildConfig.SERVER_EDITABLE) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PBX softphone URL", style = type.label, color = c.muted)
                    OutlinedTextField(
                        value = pbxUrl,
                        onValueChange = {
                            pbxUrl = it
                            inlineError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://pbx.example.com/ihf-softphone/index.php") },
                        singleLine = true,
                        shape = FieldShape,
                        colors = welcomeFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Work email", style = type.label, color = c.muted)
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        inlineError = null
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    placeholder = { Text("you@company.com") },
                    singleLine = true,
                    shape = FieldShape,
                    colors = welcomeFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                inlineError?.let {
                    Text(it, style = type.label, color = c.coral)
                }
            }
            Button(
                onClick = { submit() },
                enabled = emailOk && serverOk && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = FieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.gold,
                    contentColor = c.goldInk,
                    disabledContainerColor = c.raised,
                    disabledContentColor = c.disabled,
                ),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = c.goldInk,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "Email me a setup link",
                        style = type.heading.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
            OrDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostAction(
                    modifier = Modifier.weight(1f),
                    label = "Scan QR",
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = c.gold) },
                    onClick = onScanQr,
                )
                GhostAction(
                    modifier = Modifier.weight(1f),
                    label = "Enter code",
                    icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = c.gold) },
                    onClick = onEnterCode,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.MailOutline,
                    contentDescription = null,
                    tint = c.gold,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Text(
                    buildAnnotatedString {
                        append("Already have the email? Open it on this phone and tap ")
                        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) {
                            append("Set up")
                        }
                        append(". The app opens by itself.")
                    },
                    style = type.label.copy(lineHeight = 18.sp),
                    color = c.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            FooterRow(
                left = "Advanced setup",
                leftContentDescription = "Advanced setup",
                onLeft = onAdvanced,
            )
        }
    }
}

/**
 * After request-enrol — welcome-link-sent.html.
 */
@Composable
fun WelcomeLinkSentScreen(
    email: String,
    vm: SoftphoneViewModel,
    onBack: () -> Unit,
    onUseAnotherEmail: () -> Unit,
    onHaveCode: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val skin = IhfThemeAccess.skin
    val context = LocalContext.current
    var cooldown by remember { mutableIntStateOf(state.enrolRequestRetryAfter.coerceAtLeast(30)) }

    BackHandler(onBack = onBack)

    LaunchedEffect(email, state.enrolRequestRetryAfter) {
        cooldown = state.enrolRequestRetryAfter.coerceAtLeast(30)
        while (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        WelcomeHero(
            heightDp = 232,
            shortShade = true,
            brandTitle = BuildConfig.PRODUCT_NAME.ifBlank { skin.productName },
            showBack = true,
            onBack = onBack,
            headline = {
                Text(
                    buildAnnotatedString {
                        append("Check your\n")
                        withStyle(
                            SpanStyle(
                                color = c.gold,
                                fontStyle = FontStyle.Italic,
                                fontFamily = InstrumentSerifFamily,
                            ),
                        ) { append("inbox.") }
                    },
                    style = type.display.copy(
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontFamily = InstrumentSerifFamily,
                    ),
                    color = c.text,
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("If ")
                    withStyle(SpanStyle(color = c.text, fontFamily = type.number.fontFamily, fontSize = 14.sp)) {
                        append(email)
                    }
                    append(" has IHF Phone lines, a setup link is on its way. Open it on ")
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) {
                        append("this phone")
                    }
                    append(" and tap ")
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) {
                        append("Set up")
                    }
                    append(".")
                },
                style = type.body.copy(lineHeight = 22.sp),
                color = c.muted,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.surface)
                    .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = c.gold)
                Column {
                    Text(
                        "Works once, expires in 72 hours",
                        style = type.label.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                        color = c.text,
                    )
                    Text("No password in the email.", style = type.caption, color = c.caption)
                }
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_EMAIL)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                ),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = FieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.gold,
                    contentColor = c.goldInk,
                ),
            ) {
                Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open email app", style = type.heading.copy(fontWeight = FontWeight.SemiBold))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (cooldown <= 0 && !state.busy) {
                            vm.requestEnrolLink(email)
                            cooldown = state.enrolRequestRetryAfter.coerceAtLeast(30)
                        }
                    },
                    enabled = cooldown <= 0 && !state.busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = FieldShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, c.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = c.text,
                        disabledContentColor = c.disabled,
                    ),
                ) {
                    if (cooldown > 0) {
                        val mm = cooldown / 60
                        val ss = cooldown % 60
                        Text(
                            "Resend in ${mm}:${String.format(Locale.US, "%02d", ss)}",
                            style = type.label,
                            color = c.muted,
                        )
                    } else {
                        Text("Resend", style = type.label.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
                OutlinedButton(
                    onClick = onUseAnotherEmail,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = FieldShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, c.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                ) {
                    Text("Use another email", style = type.label.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            Text(
                "Nothing after a minute? Check spam, or ask your IT administrator to send it from the PBX.",
                style = type.caption.copy(lineHeight = 18.sp),
                color = c.caption,
            )
            Spacer(Modifier.height(8.dp))
            FooterRow(
                left = "I have a code instead",
                onLeft = onHaveCode,
            )
        }
    }
}

/**
 * Enter setup code / enrol token — welcome-code.html path into existing token enrol.
 */
@Composable
fun WelcomeCodeScreen(
    vm: SoftphoneViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onEmailLink: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val skin = IhfThemeAccess.skin
    var code by rememberSaveable { mutableStateOf("") }
    var pbxUrl by rememberSaveable { mutableStateOf(state.serverBase.ifBlank { BuildConfig.DEFAULT_API_BASE }) }
    var sipDomain by rememberSaveable { mutableStateOf(state.serverSipDomain.ifBlank { BuildConfig.DEFAULT_SIP_DOMAIN }) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val token = normalizeEnrolToken(code)
    val serverOk = !BuildConfig.SERVER_EDITABLE ||
        (pbxUrl.trim().startsWith("https://") && sipDomain.isNotBlank())
    val canSubmit = looksLikeEnrolToken(token) && serverOk

    BackHandler(onBack = onBack)

    LaunchedEffect(state.busy, state.error, state.status, submitted, state.pendingEnrol, state.accounts) {
        if (submitted && !state.busy && state.error == null && state.pendingEnrol == null &&
            (state.status != null || state.accounts.isNotEmpty())
        ) {
            onDone()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        WelcomeHero(
            heightDp = 232,
            shortShade = true,
            brandTitle = BuildConfig.PRODUCT_NAME.ifBlank { skin.productName },
            showBack = true,
            onBack = onBack,
            headline = {
                Text(
                    buildAnnotatedString {
                        append("Enter your\n")
                        withStyle(
                            SpanStyle(
                                color = c.gold,
                                fontStyle = FontStyle.Italic,
                                fontFamily = InstrumentSerifFamily,
                            ),
                        ) { append("code.") }
                    },
                    style = type.display.copy(
                        fontSize = 40.sp,
                        lineHeight = 42.sp,
                        fontFamily = InstrumentSerifFamily,
                    ),
                    color = c.text,
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Paste the setup code or HTTPS enrol link from your email or QR.",
                style = type.body,
                color = c.muted,
            )
            if (BuildConfig.SERVER_EDITABLE) {
                OutlinedTextField(
                    value = pbxUrl,
                    onValueChange = { pbxUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PBX softphone URL") },
                    placeholder = { Text("https://pbx.example.com/ihf-softphone/index.php") },
                    singleLine = true,
                    shape = FieldShape,
                    colors = welcomeFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = sipDomain,
                    onValueChange = { sipDomain = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SIP domain") },
                    placeholder = { Text("pbx.example.com") },
                    singleLine = true,
                    shape = FieldShape,
                    colors = welcomeFieldColors(),
                )
            }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Setup code") },
                singleLine = true,
                shape = FieldShape,
                colors = welcomeFieldColors(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    autoCorrect = false,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSubmit) {
                            submitted = true
                            vm.enrolWithToken(
                                enrolToken = token,
                                label = "",
                                sipExtension = "",
                                sipPassword = "",
                                apiBase = pbxUrl.trim(),
                                sipDomain = sipDomain.trim(),
                            )
                        }
                    },
                ),
            )
            state.error?.let {
                Text(
                    if (it.contains("invalid", ignoreCase = true) || it.contains("expired", ignoreCase = true)) {
                        "That code has expired or was already used."
                    } else {
                        it
                    },
                    style = type.label,
                    color = c.coral,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                    },
                )
                TextButton(onClick = onEmailLink) {
                    Text("Email me a new link", color = c.gold)
                }
            }
            Button(
                onClick = {
                    submitted = true
                    vm.enrolWithToken(
                        enrolToken = token,
                        label = "",
                        sipExtension = "",
                        sipPassword = "",
                        apiBase = pbxUrl.trim(),
                        sipDomain = sipDomain.trim(),
                    )
                },
                enabled = canSubmit && !state.busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = FieldShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.gold,
                    contentColor = c.goldInk,
                    disabledContainerColor = c.raised,
                    disabledContentColor = c.disabled,
                ),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = c.goldInk,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Continue", style = type.heading.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            FooterRow(left = "Back to welcome", onLeft = onBack)
        }
    }
}

/**
 * QR scan entry — requests camera only here; falls back to Enter code when
 * no dedicated scanner is available (paste HTTPS enrol QR contents).
 */
@Composable
fun WelcomeScanScreen(
    onBack: () -> Unit,
    onEnterCode: () -> Unit,
    onTokenScanned: (String) -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
            }
            Text("Scan QR", style = type.titleSm, color = c.text)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Point the camera at the setup QR from your admin email or the PBX assign page.",
            style = type.body,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .size(256.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x0AEEF2F8)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val len = 44.dp.toPx()
                val gold = c.gold
                // Corner brackets
                drawLine(gold, Offset(0f, len), Offset(0f, 0f), stroke)
                drawLine(gold, Offset(0f, 0f), Offset(len, 0f), stroke)
                drawLine(gold, Offset(size.width - len, 0f), Offset(size.width, 0f), stroke)
                drawLine(gold, Offset(size.width, 0f), Offset(size.width, len), stroke)
                drawLine(gold, Offset(0f, size.height - len), Offset(0f, size.height), stroke)
                drawLine(gold, Offset(0f, size.height), Offset(len, size.height), stroke)
                drawLine(gold, Offset(size.width - len, size.height), Offset(size.width, size.height), stroke)
                drawLine(gold, Offset(size.width, size.height - len), Offset(size.width, size.height), stroke)
                drawLine(
                    gold.copy(alpha = 0.7f),
                    Offset(14.dp.toPx(), size.height * 0.46f),
                    Offset(size.width - 14.dp.toPx(), size.height * 0.46f),
                    2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = c.gold.copy(alpha = 0.45f),
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "The camera is used only to read this code.",
            style = type.caption,
            color = c.caption,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                // Prefer a barcode-scanner Intent when present; otherwise Enter code.
                val scan = Intent("com.google.zxing.client.android.SCAN").apply {
                    putExtra("SCAN_MODE", "QR_CODE_MODE")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val resolved = scan.resolveActivity(context.packageManager)
                if (resolved != null) {
                    runCatching {
                        context.startActivity(scan)
                    }.onFailure { onEnterCode() }
                } else {
                    // No external scanner — Enter code accepts pasted QR / HTTPS URL.
                    onEnterCode()
                }
                // Keep path wired for deep-link style tokens pasted after scan apps.
                Unit
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = FieldShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = c.gold,
                contentColor = c.goldInk,
            ),
        ) {
            Text("Open scanner", style = type.heading.copy(fontWeight = FontWeight.SemiBold))
        }
        TextButton(onClick = onEnterCode) {
            Text("Enter code instead", color = c.muted)
        }
        // Silence unused for future camera pipeline wiring.
        @Suppress("UNUSED_VARIABLE")
        val unused = onTokenScanned
    }
}

@Composable
private fun WelcomeHero(
    heightDp: Int,
    brandTitle: String,
    brandSub: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    shortShade: Boolean = false,
    headline: @Composable () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.hero_cloud_phone),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (shortShade) {
                        Brush.horizontalGradient(
                            0f to c.ground.copy(alpha = 0.92f),
                            0.52f to c.ground.copy(alpha = 0.55f),
                            1f to Color.Transparent,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to c.ground.copy(alpha = 0.72f),
                            0.38f to Color.Transparent,
                            0.5f to Color.Transparent,
                            1f to c.ground,
                        )
                    },
                ),
        )
        if (!shortShade) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to c.ground,
                        ),
                    ),
            )
        }
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = if (showBack) 4.dp else 20.dp, top = 12.dp, end = 20.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = c.text,
                    )
                }
            }
            Image(
                painter = painterResource(R.drawable.ic_ihf_emblem),
                contentDescription = null,
                modifier = Modifier.width(42.dp).height(35.dp),
            )
            Column {
                Text(
                    brandTitle,
                    style = type.titleSm.copy(
                        fontSize = if (showBack) 20.sp else 24.sp,
                        fontFamily = InstrumentSerifFamily,
                        lineHeight = 26.sp,
                    ),
                    color = c.text,
                )
                if (brandSub != null) {
                    Text(
                        brandSub,
                        style = type.overline.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp,
                        ),
                        color = c.gold,
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp)
                .padding(bottom = 2.dp),
        ) {
            headline()
        }
    }
}

@Composable
private fun FeatureRows() {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(IconWellShape)
                    .background(c.surface)
                    .border(1.dp, c.hairline, IconWellShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(26.dp)) {
                    drawCircle(c.emerald, radius = 6.dp.toPx(), center = Offset(10.dp.toPx(), size.height / 2))
                    drawCircle(
                        c.sky.copy(alpha = 0.9f),
                        radius = 6.dp.toPx(),
                        center = Offset(16.dp.toPx(), size.height / 2),
                    )
                }
            }
            Column {
                Text("Two lines, one app", style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = c.text)
                Text(
                    "Business and Personal, each with its own number.",
                    style = type.label.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = c.muted,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(IconWellShape)
                    .background(c.surface)
                    .border(1.dp, c.hairline, IconWellShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = c.gold, modifier = Modifier.size(20.dp))
            }
            Column {
                Text("Texts with photos", style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = c.text)
                Text(
                    "Send and receive MMS from the line you choose.",
                    style = type.label.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun OrDivider() {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.divider))
        Text(
            "OR SET UP WITH",
            style = type.caption.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            ),
            color = c.caption,
        )
        Box(Modifier.weight(1f).height(1.dp).background(c.divider))
    }
}

@Composable
private fun GhostAction(
    modifier: Modifier = Modifier,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = FieldShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label, style = type.label.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun FooterRow(
    left: String,
    leftContentDescription: String? = null,
    onLeft: () -> Unit,
) {
    val c = IhfThemeAccess.colors
    val type = LocalIhfType.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onLeft,
            modifier = Modifier
                .height(44.dp)
                .semantics {
                    contentDescription = leftContentDescription ?: left
                },
        ) {
            Text(left, style = type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
        Text(BuildConfig.FOOTER_TAG, style = type.caption, color = c.caption)
    }
}
