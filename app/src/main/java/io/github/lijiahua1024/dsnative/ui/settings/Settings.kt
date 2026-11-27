package io.github.lijiahua1024.dsnative.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import io.github.lijiahua1024.dsnative.ui.backdropBlur
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.github.lijiahua1024.dsnative.theme.dsPainterResource as painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.BuildConfig
import io.github.lijiahua1024.dsnative.api.verifyApiKey
import io.github.lijiahua1024.dsnative.api.AccountBalance
import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.balanceCurrencySymbol
import io.github.lijiahua1024.dsnative.api.cacheAccountBalance
import io.github.lijiahua1024.dsnative.api.fetchAccountBalance
import io.github.lijiahua1024.dsnative.api.lastCachedBalance
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.CrashLog
import io.github.lijiahua1024.dsnative.data.DebugLog
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import io.github.lijiahua1024.dsnative.ui.session.DsToast
import kotlinx.coroutines.launch

private fun verifyErrorMessage(e: Exception): String = when (e) {
    is io.github.lijiahua1024.dsnative.api.ApiError -> when {
        e.kind == io.github.lijiahua1024.dsnative.api.ApiErrorKind.NETWORK ->
            Strings.get("commonNetworkErrorToast")
        e.status == 401 -> Strings.get("apiKeyInvalid")
        else -> Strings.get("apiKeyVerificationFailed", "message" to (e.message ?: ""))
    }
    else -> Strings.get("apiKeyInvalid")
}

@Composable
fun ApiKeyScreen(settings: SettingsStore) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ref ApiKeyScreen.module.css: page padding 24, form grid gap 18, max-width 360,
    // whale 52, h1 22, input/button minH48 r16, input 1px borderL2, button primary-fill
    // ref .page spans below-status-bar to screen bottom (nav inset 0) → center sits
    // ~22.5dp lower than Compose's inset-reduced center; matching with top padding
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 22.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.whale), null, tint = c.brandPrimary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(18.dp))
        Text(Strings.get("apiKeyStart"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.labelPrimary,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        Spacer(Modifier.height(18.dp))
        // BasicTextField in a bordered box: OutlinedTextField's fixed internal
        // padding crops the glyphs at the ref's 48dp field height.
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = key, onValueChange = { key = it; error = null },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brandPrimary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (key.isEmpty()) {
                        Text(Strings.get("apiKeyPlaceholder"), fontSize = 16.sp, color = c.labelTertiary)
                    }
                    inner()
                },
            )
        }
        error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = c.stateErrorPrimary, fontSize = 14.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = key.isNotBlank() && !verifying,
            onClick = {
                verifying = true; error = null
                scope.launch {
                    try {
                        verifyApiKey(key.trim())
                        if (!settings.setApiKey(key.trim())) error = Strings.get("apiKeyStorageFailed")
                    } catch (e: Exception) {
                        error = verifyErrorMessage(e)
                    }
                    verifying = false
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.buttonPrimaryFill,
                disabledContainerColor = c.buttonPrimaryFill.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) { Text(if (verifying) Strings.get("apiKeyVerifying") else Strings.get("confirm"), fontSize = 16.sp) }
        Spacer(Modifier.height(18.dp))
        Text(Strings.get("apiKeyGet"), fontSize = 14.sp, color = c.brandText,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            modifier = Modifier.clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
            })
    }
}

private fun settingsPageTitle(page: String): String = when (page) {
    "root" -> Strings.get("settings")
    "account" -> Strings.get("settingsAccountPage")
    "data" -> Strings.get("settingsDataPage")
    "font" -> Strings.get("settingsFontPage")
    "personalization" -> Strings.get("settingsPersonalizationPage")
    "agreements" -> Strings.get("settingsAgreementsPage")
    "export" -> Strings.get("exportAllLabel")
    else -> ""
}

@Composable
fun AndroidSettings(
    settings: SettingsStore,
    chatStore: ChatStore,
    bgAlpha: () -> Float = { 1f },
    contentAlpha: () -> Float = { 1f },
    contentShiftDp: () -> Float = { 0f },
    onRootBackProgress: (Float) -> Unit = {},
    onRootBackCommit: () -> Unit = {},
    onRootBackCancel: () -> Unit = {},
    onBack: () -> Unit,
) {
    val c = DsTheme.colors
    val themePref by settings.themePreference.collectAsState()
    val localePref by settings.localePreference.collectAsState()
    val ttsVoice by settings.ttsVoice.collectAsState()
    var popup by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<io.github.lijiahua1024.dsnative.data.UpdateCheckResult.UpdateAvailable?>(null) }
    val updateScope = rememberCoroutineScope()
    val subScope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val subCtrl = remember {
        io.github.lijiahua1024.dsnative.ui.TwoPhaseController("root", subScope)
    }
    var modalBlurFraction by remember { mutableFloatStateOf(0f) }
    var modalTargetBlurDp by remember { mutableFloatStateOf(0f) }

    androidx.activity.compose.PredictiveBackHandler(
        enabled = popup == null && !confirmLogout && updateDialog == null,
    ) { flow ->
        val pageNow = subCtrl.current
        try {
            flow.collect { ev ->
                if (pageNow == "root") {
                    onRootBackProgress(ev.progress)
                } else {
                    val backTarget = if (pageNow == "export") "data" else "root"
                    subCtrl.beginBackSeek(backTarget, ev.progress)
                }
            }
            if (pageNow == "root") {
                onRootBackCommit()
            } else {
                val backTarget = if (pageNow == "export") "data" else "root"
                subCtrl.commitBackSeek(backTarget)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (pageNow == "root") onRootBackCancel() else subCtrl.cancelBackSeek()
            throw e
        }
    }

    val bg = if (c.dark) Color(0xFF1E1E1E) else Color(0xFFF8F8F8)
    val card = if (c.dark) Color(0xFF292929) else Color(0xFFFFFFFF)
    val muted = if (c.dark) Color(0xFF8E9094) else Color(0xFF8C9097)
    val btnBg = if (c.dark) Color(0xFF3C3C3D) else Color(0xFFEDEDED)

    val navigateSub: (String) -> Unit = { target ->
        subCtrl.navigate(target, isForward = true)
    }
    val navigateBack: () -> Unit = {
        val pageNow = subCtrl.current
        if (pageNow == "root") onBack()
        else subCtrl.navigate(if (pageNow == "export") "data" else "root", isForward = false)
    }

    Box(
        Modifier.fillMaxSize()
            // Isolate underlying Chat screen from touches whenever Settings is visible.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            }
            .drawBehind {
                val a = bgAlpha().coerceIn(0f, 1f)
                if (a > 0f) drawRect(bg.copy(alpha = a))
            }
            .backdropBlur { (modalTargetBlurDp * modalBlurFraction).coerceAtLeast(0f).dp },
    ) {
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = contentAlpha()
                    translationX = contentShiftDp().dp.toPx()
                },
        ) {
            val outPage = subCtrl.outgoing
            val curPage = subCtrl.current
            val layers = remember(outPage, curPage) {
                if (outPage != null && outPage != curPage) listOf(outPage, curPage) else listOf(curPage)
            }

            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(48.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = navigateBack,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(btnBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            Strings.get("back"),
                            tint = c.labelPrimary,
                            modifier = Modifier.size(18.dp).rotate(180f),
                        )
                    }
                }
                layers.forEach { layerPage ->
                    key(layerPage) {
                        Text(
                            settingsPageTitle(layerPage),
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.labelPrimary,
                            modifier = Modifier.align(Alignment.Center).graphicsLayer {
                                alpha = subCtrl.layerAlpha(layerPage)
                                translationX = subCtrl.layerShiftDp(layerPage).dp.toPx()
                            },
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                layers.forEach { layerPage ->
                    key(layerPage) {
                        Column(
                            Modifier.fillMaxSize().graphicsLayer {
                                alpha = subCtrl.layerAlpha(layerPage)
                                translationX = subCtrl.layerShiftDp(layerPage).dp.toPx()
                            },
                        ) {
                            Column(
                                Modifier.weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
                            ) {
                                SettingsLayerBody(
                                    layerPage = layerPage,
                                    c = c,
                                    card = card,
                                    muted = muted,
                                    settings = settings,
                                    chatStore = chatStore,
                                    themePref = themePref,
                                    localePref = localePref,
                                    checkingUpdate = checkingUpdate,
                                    onPage = navigateSub,
                                    onPopup = { popup = it },
                                    onHelp = {
                                        io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(
                                            ctx, "https://static.deepseek.com/faq/index.html", c.dark, "",
                                        )
                                    },
                                    onLogout = { confirmLogout = true },
                                    onModalBlur = { frac ->
                                        modalTargetBlurDp = 0.40f
                                        modalBlurFraction = frac
                                    },
                                    onCheckUpdate = {
                                        if (!checkingUpdate) {
                                            checkingUpdate = true
                                            updateScope.launch {
                                                try {
                                                    when (val res = io.github.lijiahua1024.dsnative.data.UpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)) {
                                                        io.github.lijiahua1024.dsnative.data.UpdateCheckResult.UpToDate ->
                                                            DsToast.show(Strings.get("updateCurrent"))
                                                        is io.github.lijiahua1024.dsnative.data.UpdateCheckResult.UpdateAvailable ->
                                                            updateDialog = res
                                                    }
                                                } catch (_: Exception) {
                                                    DsToast.show(Strings.get("updateCheckFailed"))
                                                } finally {
                                                    checkingUpdate = false
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            if (layerPage == "font") FontPanel(c, card, muted, settings)
                        }
                    }
                }
            }
        }
    }

    when (popup) {
        "locale" -> ChoicePopup(
            c, card, muted, Strings.get("settingsLocalePreference"),
            options = listOf(
                "system" to Strings.get("settingsThemeSystem"),
                "zh-CN" to "中文 (简体中文)",
                "en-US" to "English",
            ),
            selected = when (localePref) {
                io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> "zh-CN"
                io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> "en-US"
                else -> "system"
            },
            onProgress = {
                modalTargetBlurDp = 0.40f
                modalBlurFraction = it
            },
            onConfirm = { v ->
                settings.setLocalePreference(
                    when (v) {
                        "zh-CN" -> io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN
                        "en-US" -> io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US
                        else -> io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM
                    },
                )
                modalBlurFraction = 0f
                popup = null
            },
            onDismiss = {
                modalBlurFraction = 0f
                popup = null
            },
        )
        "theme" -> ChoicePopup(
            c, card, muted, Strings.get("settingsAppearance"),
            options = listOf(
                "system" to Strings.get("settingsThemeSystem"),
                "light" to Strings.get("settingsThemeLight"),
                "dark" to Strings.get("settingsThemeDark"),
            ),
            selected = when (themePref) {
                io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> "light"
                io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> "dark"
                else -> "system"
            },
            onProgress = {
                modalTargetBlurDp = 0.40f
                modalBlurFraction = it
            },
            onConfirm = { v ->
                settings.setThemePreference(
                    when (v) {
                        "light" -> io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT
                        "dark" -> io.github.lijiahua1024.dsnative.data.ThemePreference.DARK
                        else -> io.github.lijiahua1024.dsnative.data.ThemePreference.SYSTEM
                    },
                )
                modalBlurFraction = 0f
                popup = null
            },
            onDismiss = {
                modalBlurFraction = 0f
                popup = null
            },
        )
        "voice" -> VoiceSheet(
            c, card, muted, current = ttsVoice,
            apiKey = settings.apiKey.collectAsState().value,
            onProgress = {
                modalTargetBlurDp = 3.2f
                modalBlurFraction = it
            },
            onConfirm = {
                io.github.lijiahua1024.dsnative.data.ReadAloud.stop()
                if (it != ttsVoice) {
                    DsToast.show(
                        Strings.get(
                            "ttsVoiceSwitched",
                            "voice" to Strings.get(if (it == "stella") "settingsTtsVoiceStella" else "settingsTtsVoiceTide"),
                        ),
                    )
                }
                settings.setTtsVoice(it)
                modalBlurFraction = 0f
                popup = null
            },
            onDismiss = {
                io.github.lijiahua1024.dsnative.data.ReadAloud.stop()
                modalBlurFraction = 0f
                popup = null
            },
        )
    }

    if (confirmLogout) {
        DsConfirmDialog(
            title = Strings.get("settingsLogoutConfirm"),
            body = null,
            confirmText = Strings.get("confirm"),
            cancelText = Strings.get("cancel"),
            danger = true,
            onProgress = {
                modalTargetBlurDp = 0.40f
                modalBlurFraction = it
            },
            onConfirm = {
                settings.setApiKey(null)
                modalBlurFraction = 0f
                confirmLogout = false
            },
            onDismiss = {
                modalBlurFraction = 0f
                confirmLogout = false
            },
        )
    }
    updateDialog?.let { avail ->
        DsConfirmDialog(
            title = Strings.get("updateAvailableTitle", "version" to avail.version),
            body = Strings.get("updateAvailableBody"),
            confirmText = Strings.get("updateOpenReleases"),
            cancelText = Strings.get("cancel"),
            danger = false,
            onProgress = {
                modalTargetBlurDp = 0.40f
                modalBlurFraction = it
            },
            onConfirm = {
                val targetUrl = avail.downloadUrl ?: avail.releaseUrl
                modalBlurFraction = 0f
                updateDialog = null
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                } catch (_: android.content.ActivityNotFoundException) {
                    DsToast.show(Strings.get("updateCheckFailed"))
                }
            },
            onDismiss = {
                modalBlurFraction = 0f
                updateDialog = null
            },
        )
    }
}

@Composable
private fun SettingsLayerBody(
    layerPage: String,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color,
    muted: Color,
    settings: SettingsStore,
    chatStore: ChatStore,
    themePref: io.github.lijiahua1024.dsnative.data.ThemePreference,
    localePref: io.github.lijiahua1024.dsnative.data.LocalePreference,
    checkingUpdate: Boolean,
    onPage: (String) -> Unit,
    onPopup: (String) -> Unit,
    onHelp: () -> Unit,
    onLogout: () -> Unit,
    onModalBlur: (Float) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    when (layerPage) {
        "root" -> SettingsRootPage(
            c, card, muted,
            settings = settings,
            onPage = onPage,
            onPopup = onPopup,
            onHelp = onHelp,
            onLogout = onLogout,
            themePref = themePref,
            localePref = localePref,
            checkingUpdate = checkingUpdate,
            onCheckUpdate = onCheckUpdate,
        )
        "account" -> AccountPage(c, card, muted, settings)
        "data" -> DataPage(c, card, muted, settings, chatStore, onPage = onPage, onModalBlur = onModalBlur)
        "font" -> FontPage(c, card, muted, settings)
        "personalization" -> PersonalizationPage(c, card, muted, settings)
        "agreements" -> AgreementsPage(c, card, muted)
        "export" -> ExportPage(c, card, muted, chatStore)
    }
}

@Composable
private fun SettingsRootPage(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    settings: SettingsStore,
    onPage: (String) -> Unit,
    onPopup: (String) -> Unit,
    onHelp: () -> Unit, onLogout: () -> Unit,
    themePref: io.github.lijiahua1024.dsnative.data.ThemePreference,
    localePref: io.github.lijiahua1024.dsnative.data.LocalePreference,
    checkingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
) {
    val debugMode by settings.debugMode.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogDialog by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(false) }
    // 清空日志后驱动「查看日志」计数立即重算，避免仍显示旧条数。
    var logNonce by remember { mutableStateOf(0) }
    val exportLogLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        val debugText = DebugLog.all().joinToString("\n")
                        val crashText = CrashLog.readAll()
                        val body = buildString {
                            append(debugText)
                            if (crashText.isNotEmpty()) {
                                if (isNotEmpty()) append("\n\n")
                                append("===== crash.log =====\n").append(crashText)
                            }
                        }
                        out.write(body.toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            DsToast.show(Strings.get(if (ok) "debugLogExported" else "exportFailed"))
        }
    }
    val toggleDebug = {
        val next = !debugMode
        settings.setDebugMode(next)
        DebugLog.enabled = next
        DsToast.show(Strings.get(if (next) "debugModeOn" else "debugModeOff"))
    }
    SettingsGroup(Strings.get("settingsGroupAccount"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsAccountPage"), { ProfileGlyph(c.labelPrimary) }) { onPage("account") }
        SettingsRow(c, muted, Strings.get("settingsDataPage"), { Icon(painterResource(R.drawable.ic_data), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) }) { onPage("data") }
    }
    SettingsGroup(Strings.get("settingsGroupApp"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsLocalePreference"),
            { Icon(painterResource(R.drawable.ic_globe_lang), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp)) },
            value = localeLabel(localePref)) { onPopup("locale") }
        SettingsRow(c, muted, Strings.get("settingsAppearance"),
            { Icon(painterResource(if (c.dark) R.drawable.ic_moon else R.drawable.ic_light), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp)) },
            value = themeLabel(themePref)) { onPopup("theme") }
        SettingsRow(c, muted, Strings.get("settingsFontPage"),
            { Icon(painterResource(R.drawable.ic_font_size), null, tint = c.labelPrimary, modifier = Modifier.size(19.dp)) }) { onPage("font") }
        SettingsRow(c, muted, Strings.get("settingsPersonalizationPage"),
            { Icon(painterResource(R.drawable.ic_wand), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp)) }) { onPage("personalization") }
    }
    SettingsGroup(Strings.get("settingsGroupVoice"), card, muted) {
        SettingsRow(c, muted, Strings.get("settingsTtsVoice"),
            { Icon(painterResource(R.drawable.ic_sound), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp)) },
            arrow = false) { onPopup("voice") }
    }
    SettingsGroup(Strings.get("settingsGroupAbout"), card, muted) {
        SettingsRow(
            c,
            muted,
            Strings.get("settingsVersion"),
            { Icon(painterResource(R.drawable.ic_info_circle), null, tint = c.labelPrimary, modifier = Modifier.size(19.dp)) },
            value = if (checkingUpdate) Strings.get("checkingForUpdate") else BuildConfig.VERSION_NAME,
            arrow = false,
            enabled = !checkingUpdate,
            onLongClick = toggleDebug,
        ) {
            onCheckUpdate()
        }
        SettingsRow(c, muted, Strings.get("settingsAgreementsPage"),
            { Icon(painterResource(R.drawable.ic_doc_edit), null, tint = c.labelPrimary, modifier = Modifier.size(20.dp)) }) { onPage("agreements") }
    }
    if (debugMode) {
        val logCount = run { logNonce; DebugLog.size() }
        SettingsGroup(Strings.get("debugLogTitle"), card, muted) {
            SettingsRow(c, muted, Strings.get("debugLogView"),
                value = "$logCount", arrow = false) { showLogDialog = true }
            SettingsRow(c, muted, Strings.get("debugCrashView"), arrow = false) { showCrashDialog = true }
            SettingsRow(c, muted, Strings.get("debugLogExport"), arrow = false) {
                exportLogLauncher.launch("dsnative-debug-${System.currentTimeMillis()}.log")
            }
            SettingsRow(c, muted, Strings.get("debugLogClear"), danger = true, arrow = false) {
                DebugLog.clear()
                logNonce++
                DsToast.show(Strings.get("debugLogCleared"))
            }
        }
    }
    SettingsGroup(null, card, muted, topGap = 7.8.dp) {
        SettingsRow(c, muted, Strings.get("settingsHelp"), { InfoGlyph("?", c.labelPrimary) }) { onHelp() }
    }
    SettingsGroup(null, card, muted, topGap = 7.8.dp) {
        SettingsRow(c, muted, Strings.get("settingsLogout"), danger = true, arrow = false) { onLogout() }
    }
    if (showLogDialog) {
        val logText = remember(showLogDialog) { DebugLog.all().takeLast(400).joinToString("\n") }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text(Strings.get("debugLogTitle")) },
            text = {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    Text(logText.ifEmpty { Strings.get("debugLogEmpty") },
                        fontSize = 12.sp, lineHeight = 18.sp, color = c.labelPrimary)
                }
            },
            confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text(Strings.get("close")) } },
        )
    }
    if (showCrashDialog) {
        val crashText = remember(showCrashDialog) {
            CrashLog.readAll().lines().takeLast(400).joinToString("\n")
        }
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text(Strings.get("debugCrashView")) },
            text = {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    Text(crashText.ifEmpty { Strings.get("debugCrashEmpty") },
                        fontSize = 12.sp, lineHeight = 18.sp, color = c.labelPrimary)
                }
            },
            confirmButton = { TextButton(onClick = { showCrashDialog = false }) { Text(Strings.get("close")) } },
        )
    }
}

@Composable
private fun ProfileGlyph(tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension / 24f
        drawCircle(tint, radius = 4f * s, center = androidx.compose.ui.geometry.Offset(12f * s, 8f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * s))
        drawArc(tint, -180f, 180f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(4.5f * s, 15f * s),
            size = androidx.compose.ui.geometry.Size(15f * s, 12f * s),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * s))
    }
}

@Composable
private fun InfoGlyph(glyph: String, tint: Color) {
    Box(Modifier.size(17.dp).clip(RoundedCornerShape(50))
        .background(Color.Transparent)
        .androidBorder(1.5.dp, tint), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint, lineHeight = 12.sp)
    }
}

private fun Modifier.androidBorder(w: androidx.compose.ui.unit.Dp, color: Color) =
    this.then(Modifier.border(w, color, RoundedCornerShape(50)))

// ref .group h2: muted 13/18 w400, margin 0 16px 8px; groups gap 28.2px
@Composable
private fun SettingsGroup(label: String?, card: Color, muted: Color, topGap: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = topGap)) {
        if (label != null) {
            Box(Modifier.height(22.dp).padding(start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(label, fontSize = 13.sp, color = muted,
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(card)) { content() }
    }
    Spacer(Modifier.height(28.dp))
}

// ref .row: minH48, padding 0 15px 0 16px, gap 12, font 16; icon box 22 (svg 18)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    muted: Color,
    label: String,
    icon: (@Composable () -> Unit)? = null,
    value: String? = null,
    danger: Boolean = false,
    arrow: Boolean = true,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    // ref button.row:active/a.row:active -> background: var(--settings-button)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pressBg = if (c.dark) Color(0xFF303030) else Color(0xFFEBEBEB)
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
        .alpha(if (enabled) 1f else 0.5f)
        .clip(RoundedCornerShape(12.dp))
        .background(if (pressed && enabled) DsTheme.pressTint(pressed, pressBg) else Color.Transparent)
        .combinedClickable(src, null, enabled = enabled, onClick = onClick, onLongClick = onLongClick)
        .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(12.dp))
        }
        Text(label, fontSize = 16.sp, color = if (danger) c.stateErrorPrimary else c.labelPrimary,
            modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 16.sp, color = muted)
        if (value != null && arrow) Spacer(Modifier.width(7.dp))
        if (arrow) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = muted,
            modifier = Modifier.size(15.dp))
    }
}

// ---- subpages (skeleton parity; per-entry polish tracked in .agent/inventory.json) ----

@Composable
private fun AccountPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val savedProfile by settings.accountProfile.collectAsState()
    var draftName by remember(savedProfile) { mutableStateOf(savedProfile.username) }
    var draftAvatar by remember(savedProfile) { mutableStateOf(savedProfile.avatarDataUrl) }
    var loadingAvatar by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingAvatar = true
        scope.launch {
            val encoded = encodeProfileAvatarFromUri(ctx, uri)
            loadingAvatar = false
            if (encoded != null) draftAvatar = encoded
            else DsToast.show(Strings.get("profileAvatarInvalid"))
        }
    }
    val open = { url: String ->
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    val profileChanged = draftName.trim() != savedProfile.username || draftAvatar != savedProfile.avatarDataUrl

    // ref useAccountBalance：进入账号页自动查询已保存 Key 余额；后台静默刷新保留旧值，避免区域跳闪。
    val apiKey by settings.apiKey.collectAsState()
    var balanceNonce by remember { mutableStateOf(0) }
    var balanceData by remember(apiKey) { mutableStateOf(lastCachedBalance(apiKey)) }
    var balanceLoading by remember(apiKey) { mutableStateOf(false) }
    var balanceRefreshing by remember(apiKey) { mutableStateOf(false) }
    var balanceError by remember(apiKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(apiKey, balanceNonce) {
        val key = apiKey
        if (key.isNullOrEmpty()) {
            balanceData = null; balanceError = null; balanceLoading = false; balanceRefreshing = false
            return@LaunchedEffect
        }
        val hadData = balanceData != null
        if (hadData) { balanceRefreshing = true; balanceError = null } else balanceLoading = true
        try {
            balanceData = fetchAccountBalance(key)
            cacheAccountBalance(key, balanceData)
            balanceLoading = false; balanceRefreshing = false; balanceError = null
        } catch (e: Exception) {
            balanceLoading = false; balanceRefreshing = false
            if (balanceData != null) return@LaunchedEffect // 已有旧余额：后台刷新失败保留旧值
            val unauthorized = e is ApiError && (e.status == 401 || e.status == 402)
            balanceError = if (unauthorized) "unauthorized" else "other"
        }
    }
    SettingsGroup(Strings.get("settingsAccountPage"), card, muted) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatarView(draftAvatar, Modifier.size(48.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Strings.get(if (draftAvatar != null) "profileChangeAvatar" else "profileChooseAvatar"),
                    fontSize = 14.sp,
                    color = c.labelPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                        .clickable(enabled = !loadingAvatar) {
                            avatarPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                if (draftAvatar != null) {
                    Text(
                        Strings.get("profileRemoveAvatar"),
                        fontSize = 14.sp,
                        color = c.labelSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .border(1.dp, c.borderL2, RoundedCornerShape(16.dp))
                            .clickable(enabled = !loadingAvatar) { draftAvatar = null }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Strings.get("profileUsername"), fontSize = 15.sp, color = muted)
            androidx.compose.foundation.text.BasicTextField(
                value = draftName,
                onValueChange = { if (it.length <= 40) draftName = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brandPrimary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (draftName.isEmpty()) Text(Strings.get("profileDefaultUsername"), fontSize = 16.sp, color = muted)
                    inner()
                },
            )
            Text(
                Strings.get("profileSave"),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (profileChanged && !loadingAvatar) c.brandPrimary else muted,
                modifier = Modifier.clickable(enabled = profileChanged && !loadingAvatar) {
                    val saved = io.github.lijiahua1024.dsnative.data.AccountProfile(draftName.trim(), draftAvatar)
                    if (settings.setAccountProfile(saved)) {
                        draftName = settings.accountProfile.value.username
                        DsToast.show(Strings.get("profileSaved"))
                    } else {
                        DsToast.show(Strings.get("profileSaveFailed"))
                    }
                },
            )
        }
    }
    // ref account page: 账户 group -> masked-key row + 开放平台 + 开放平台帮助 links
    SettingsGroup(Strings.get("settingsGroupAccount"), card, muted) {
        SettingsRow(c, muted, Strings.get("apiKeyLabel"), { ProfileGlyph(c.labelPrimary) },
            value = "••••••••") { editing = !editing; error = null }
        if (editing) {
            // ref .credentials: inline password field + confirm under the key row
            var value by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .padding(start = 16.dp, end = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = value,
                        onValueChange = { value = it; error = null },
                        singleLine = true,
                        enabled = !verifying,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brandPrimary),
                        modifier = Modifier.weight(1f),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        decorationBox = { inner ->
                            if (value.isEmpty()) Text(Strings.get("apiKeyPlaceholder"), fontSize = 16.sp, color = muted)
                            inner()
                        })
                    Text(
                        if (verifying) Strings.get("apiKeyVerifying") else Strings.get("confirm"),
                        fontSize = 16.sp,
                        color = if (value.isNotBlank() && !verifying) c.brandPrimary else muted,
                        modifier = Modifier.clickable(enabled = value.isNotBlank() && !verifying) {
                            val trimmed = value.trim()
                            verifying = true
                            error = null
                            scope.launch {
                                try {
                                    verifyApiKey(trimmed)
                                    if (settings.setApiKey(trimmed)) {
                                        editing = false
                                        DsToast.show(Strings.get("apiKeyUpdated"))
                                    } else {
                                        error = Strings.get("apiKeyStorageFailed")
                                    }
                                } catch (e: Exception) {
                                    error = verifyErrorMessage(e)
                                }
                                verifying = false
                            }
                        })
                }
                error?.let { msg ->
                    Text(
                        msg,
                        fontSize = 13.sp,
                        color = c.stateErrorPrimary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            }
        }
        if (apiKey != null) {
            // ref BalancePanel：总余额 + 赠金/充值分项，支持手动刷新；后台刷新失败保留旧值。
            val data = balanceData
            val (valueText, valueColor) = when {
                balanceLoading -> Strings.get("balanceLoading") to muted
                balanceError != null -> (
                    if (balanceError == "unauthorized") Strings.get("balanceUnauthorized")
                    else Strings.get("balanceFailed")
                ) to c.stateErrorPrimary
                data != null -> {
                    val symbol = balanceCurrencySymbol(data.currency)
                    (if (data.isAvailable) "${symbol}${data.totalBalance}" else Strings.get("balanceLow")) to
                        (if (data.isAvailable) c.labelPrimary else c.stateErrorPrimary)
                }
                else -> Strings.get("balanceLoading") to muted
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
                val refreshing = balanceRefreshing
                val refreshEnabled = !balanceLoading && !refreshing
                val refreshTransition = rememberInfiniteTransition(label = "balanceRefresh")
                val refreshAngle by refreshTransition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
                    label = "balanceRefreshAngle",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Strings.get("balanceTitle"), fontSize = 16.sp, color = c.labelPrimary,
                        modifier = Modifier.weight(1f))
                    Text(valueText, fontSize = 16.sp, color = valueColor)
                    Spacer(Modifier.width(8.dp))
                    // ref .balanceRefresh：28dp 圆形命中区、16dp 图标；刷新中旋转、禁用半透明。
                    Box(
                        Modifier.size(28.dp).alpha(if (refreshEnabled) 1f else 0.5f)
                            .clickable(enabled = refreshEnabled) { balanceNonce++ },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_refresh), Strings.get("balanceRefresh"),
                            tint = muted,
                            modifier = Modifier.size(16.dp).then(
                                if (refreshing) Modifier.graphicsLayer { rotationZ = refreshAngle } else Modifier,
                            ),
                        )
                    }
                }
                if (data != null) {
                    val symbol = balanceCurrencySymbol(data.currency)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${Strings.get("balanceGranted")} ${symbol}${data.grantedBalance}",
                            fontSize = 13.sp, color = muted)
                        Text("${Strings.get("balanceToppedUp")} ${symbol}${data.toppedUpBalance}",
                            fontSize = 13.sp, color = muted)
                    }
                }
            }
            SettingsRow(c, muted, Strings.get("balanceUsageEntry"),
                icon = { Icon(painterResource(R.drawable.ic_link), null, tint = c.labelPrimary,
                    modifier = Modifier.size(18.dp)) }) {
                open("https://platform.deepseek.com/usage")
            }
        }
        SettingsRow(c, muted, Strings.get("openPlatform")) { open("https://platform.deepseek.com/api_keys") }
        SettingsRow(c, muted, Strings.get("openPlatformHelp")) { open("https://api-docs.deepseek.com/zh-cn/") }
    }
}

private fun formatImportOutcome(outcome: io.github.lijiahua1024.dsnative.data.ImportOutcome): Pair<String, Boolean> =
    when (outcome) {
        is io.github.lijiahua1024.dsnative.data.ImportOutcome.Failure ->
            Strings.get(outcome.errorKey) to true
        is io.github.lijiahua1024.dsnative.data.ImportOutcome.Replace ->
            (if (outcome.count == 1) Strings.get("historyImportedOne")
            else Strings.get("historyImported", "count" to outcome.count)) to false
        is io.github.lijiahua1024.dsnative.data.ImportOutcome.Merge -> {
            val (added, merged, unchanged) = outcome
            val msg = when {
                added == 0 && merged == 0 ->
                    if (unchanged == 0) Strings.get("historyMergedEmpty")
                    else Strings.get("historyMergedNoChange", "unchanged" to unchanged)
                merged == 0 && unchanged == 0 ->
                    if (added == 1) Strings.get("historyMergedAddedOne")
                    else Strings.get("historyMergedAddedOnly", "added" to added)
                added == 0 && unchanged == 0 ->
                    if (merged == 1) Strings.get("historyMergedUpdatedOne")
                    else Strings.get("historyMergedUpdatedOnly", "merged" to merged)
                else -> Strings.get(
                    "historyMergedSummary",
                    "added" to added, "merged" to merged, "unchanged" to unchanged,
                )
            }
            msg to false
        }
    }

@Composable
private fun DataPage(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color,
    muted: Color,
    settings: SettingsStore,
    chatStore: ChatStore,
    onPage: (String) -> Unit,
    onModalBlur: (Float) -> Unit = {},
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importMode by remember { mutableStateOf(io.github.lijiahua1024.dsnative.data.ArchiveImportMode.MERGE) }
    var importOutcome by remember { mutableStateOf<io.github.lijiahua1024.dsnative.data.ImportOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val privacyUrl = "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-privacy-policy.html"
    val importLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !busy) {
            importMode = io.github.lijiahua1024.dsnative.data.ArchiveImportMode.MERGE
            pendingImportUri = uri
        }
    }
    // ref .caption above the groups: muted 13/21, underlined privacy-policy link
    androidx.compose.material3.Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(Strings.get("dataCaptionA"))
            val linkStart = length
            append(Strings.get("dataCaptionLink"))
            addStyle(androidx.compose.ui.text.SpanStyle(
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                linkStart, length)
            append(if (Strings.isZh()) "。" else ".")
        },
        fontSize = 13.sp, lineHeight = 21.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 29.3.dp)
            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl))) })
    // caption text metrics land the first card ~7px higher than the reference; extra ~2.3dp compensates
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("exportAllLabel"), enabled = !busy) { onPage("export") }
    }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("settingsDeleteAll"), danger = true, arrow = false, enabled = !busy) { confirmDeleteAll = true }
    }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("historyImport"), arrow = false, enabled = !busy) {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }
    if (busy) {
        Text(Strings.get("historyImporting"), fontSize = 13.sp, lineHeight = 21.sp,
            color = muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
    } else importOutcome?.let { outcome ->
        val (msg, isError) = formatImportOutcome(outcome)
        Text(msg, fontSize = 13.sp, lineHeight = 21.sp,
            color = if (isError) c.stateErrorPrimary else muted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
    }
    if (confirmDeleteAll) {
        DsConfirmDialog(
            title = Strings.get("deleteAllSessionsModalTitle"),
            body = Strings.get("deleteAllSessionsModalDescription"),
            confirmText = Strings.get("delete"),
            cancelText = Strings.get("cancel"),
            danger = true,
            usePopup = true,
            onProgress = onModalBlur,
            onConfirm = {
                chatStore.removeAllSessions()
                onModalBlur(0f)
                confirmDeleteAll = false
            },
            onDismiss = {
                onModalBlur(0f)
                confirmDeleteAll = false
            },
        )
    }
    pendingImportUri?.let { uri ->
        val importTitle = Strings.get("historyImportConfirmTitle")
        val confirmLbl = Strings.get(
            if (importMode == io.github.lijiahua1024.dsnative.data.ArchiveImportMode.MERGE)
                "historyImportMergeConfirm"
            else "historyImportReplaceConfirm",
        )
        DsConfirmDialog(
            title = importTitle,
            body = null,
            confirmText = confirmLbl,
            cancelText = Strings.get("cancel"),
            danger = importMode == io.github.lijiahua1024.dsnative.data.ArchiveImportMode.REPLACE,
            usePopup = true,
            onProgress = onModalBlur,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val modes = listOf(
                        io.github.lijiahua1024.dsnative.data.ArchiveImportMode.MERGE to "historyImportModeMerge",
                        io.github.lijiahua1024.dsnative.data.ArchiveImportMode.REPLACE to "historyImportModeReplace",
                    )
                    modes.forEach { (m, labelKey) ->
                        val checked = importMode == m
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (checked) c.interactiveBgHover else Color.Transparent)
                                .clickable { importMode = m }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(RoundedCornerShape(50))
                                    .border(2.dp, if (checked) c.brandPrimary else muted, RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(c.brandPrimary))
                            }
                            Text(Strings.get(labelKey), fontSize = 15.sp, color = c.labelPrimary)
                        }
                    }
                    Text(
                        Strings.get(
                            if (importMode == io.github.lijiahua1024.dsnative.data.ArchiveImportMode.MERGE)
                                "historyImportModeMergeDescription"
                            else "historyImportModeReplaceDescription",
                        ),
                        fontSize = 13.sp, lineHeight = 19.sp, color = muted,
                    )
                }
            },
            onConfirm = {
                val chosenMode = importMode
                onModalBlur(0f)
                pendingImportUri = null
                scope.launch {
                    busy = true
                    importOutcome = chatStore.importArchive(ctx, uri, chosenMode)
                    busy = false
                }
            },
            onDismiss = {
                if (!busy) {
                    onModalBlur(0f)
                    pendingImportUri = null
                }
            },
        )
    }
}

@Composable
private fun AgreementsPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color) {
    val ctx = LocalContext.current
    val policies = "https://cdn.deepseek.com/policies/zh-CN"
    val open = { url: String ->
        io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(ctx, url, c.dark) }
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted, Strings.get("agreementTerms")) { open("$policies/deepseek-terms-of-use.html") }
        SettingsRow(c, muted, Strings.get("agreementPrivacy")) { open("$policies/deepseek-privacy-policy.html") }
        SettingsRow(c, muted, Strings.get("agreementThirdParty")) { open("$policies/third-party-info-sharing-list.html") }
        SettingsRow(c, muted, Strings.get("agreementPersonal")) { open("$policies/collected-personal-information.html") }
        SettingsRow(c, muted, Strings.get("agreementPermissions")) {
            open("$policies/app-permissions.html")
        }
    }
}

@Composable
private fun PersonalizationPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val foldThinking by settings.foldThinking.collectAsState()
    // ref: single card with 思考内容自动折叠 switch + caption below
    SettingsGroup(null, card, muted) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .padding(start = 16.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsAutoFold"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            RefSwitch(foldThinking) { settings.setFoldThinking(it) }
        }
    }
    Text(Strings.get("settingsAutoFoldCaption"), fontSize = 16.sp, lineHeight = 24.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 27.dp))
}

@Composable
private fun ExportPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, chatStore: ChatStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by chatStore.sessions.collectAsState()
    var archive by remember { mutableStateOf<String?>(null) }
    var exportFile by remember { mutableStateOf<java.io.File?>(null) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { exportFile?.delete() }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val cached = exportFile
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (cached != null && cached.exists()) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            cached.inputStream().use { it.copyTo(out) }
                        } != null
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    chatStore.exportArchive(ctx, uri)
                }
            }
            if (!ok) DsToast.show(Strings.get("exportFailed"))
            busy = false
        }
    }
    // ref: 导出 row first; 保存到设备 group appears once an archive exists; caption below
    SettingsGroup(null, card, muted) {
        SettingsRow(c, muted,
            if (archive == null) Strings.get("exportLabel") else Strings.get("exportAgain"),
            arrow = false, enabled = !busy) {
            scope.launch {
                busy = true
                val f = java.io.File(ctx.cacheDir, "deepseek-chats-${System.currentTimeMillis()}.zip")
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    chatStore.exportArchive(ctx, android.net.Uri.fromFile(f))
                }
                if (ok) {
                    exportFile?.takeIf { it != f }?.delete()
                    exportFile = f
                    archive = f.name
                } else {
                    f.delete()
                    DsToast.show(Strings.get("exportFailed"))
                }
                busy = false
            }
        }
    }
    if (archive != null) {
        SettingsGroup(null, card, muted) {
            SettingsRow(c, muted, Strings.get("exportSaveDevice"), arrow = false, enabled = !busy) {
                saveLauncher.launch(archive!!)
            }
        }
    }
    Text(
        when {
            busy -> Strings.get("exportBusy")
            archive != null -> archive!!
            else -> Strings.get(if (sessions.isEmpty()) "androidNoChats" else "historyExportDescription")
        },
        fontSize = 13.sp, lineHeight = 21.sp, color = muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 27.dp))
}

@Composable
private fun FontPage(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val fs = 16.sp
    // ref .preview: right bubble + two paragraphs, lineHeight 1.6, scaled by slider value
    Column(Modifier.padding(top = 28.dp, start = 8.dp, end = 0.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(Strings.get("fontPreview"), fontSize = fs, color = c.labelPrimary,
                modifier = Modifier.clip(RoundedCornerShape(22.dp))
                    .background(c.buttonGhostActiveFill)
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
        Text(Strings.get("fontDesc1"), fontSize = fs, lineHeight = fs * 1.6f, color = c.labelPrimary,
            modifier = Modifier.padding(top = 27.7.dp))
        Text(Strings.get("fontDesc2"), fontSize = fs, lineHeight = fs * 1.6f, color = c.labelPrimary,
            modifier = Modifier.padding(top = 26.dp))
    }
}

@Composable
private fun FontPanel(c: io.github.lijiahua1024.dsnative.theme.DsColors, card: Color, muted: Color, settings: SettingsStore) {
    val fontSize by settings.fontSize.collectAsState()
    val systemFontScale = LocalDensity.current.fontScale
    val sliderValue = fontSize
        ?: Math.round((systemFontScale - 1f) / 0.1f + 2f).coerceIn(0, 6)
    // Match official FontPanel metrics: 16dp side margin (1080-48=1032 right edge),
    // elevated top-rounded card, "标准" positioned over notch 2 (1/3 along track).
    Column(Modifier.fillMaxWidth().navigationBarsPadding()
        .softShadow(cornerRadius = 18.dp, blurRadius = 12.dp, offsetY = (-2).dp,
            color = Color.Black.copy(alpha = if (c.dark) 0.25f else 0.03f))
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(card)
        .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 52.dp)) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .padding(start = 12.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get("settingsFollowSystem"), fontSize = 16.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
            RefSwitch(fontSize == null) { settings.setFontSize(if (it) null else 2) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 18.dp, start = 13.dp, end = 13.dp).height(24.dp)) {
            val stdOffset = (maxWidth * (1f / 3f))
            Text("A", fontSize = 13.sp, color = c.labelPrimary, lineHeight = 19.sp,
                modifier = Modifier.align(Alignment.BottomStart))
            val density = LocalDensity.current
            var stdWidthPx by remember { mutableStateOf(0) }
            Text(Strings.get("fontStandard"), fontSize = 12.sp, color = c.labelPrimary, lineHeight = 19.sp,
                modifier = Modifier.align(Alignment.BottomStart)
                    .onSizeChanged { stdWidthPx = it.width }
                    .offset {
                        val cx = with(density) { stdOffset.roundToPx() }
                        androidx.compose.ui.unit.IntOffset(cx - stdWidthPx / 2, 0)
                    })
            Text("A", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary, lineHeight = 20.sp,
                modifier = Modifier.align(Alignment.BottomEnd))
        }
        val thumbR = 13.dp
        val trackCol = if (c.dark) Color(0xFF4A4A4D) else Color(0xFFD4D6DC)
        val tickCol = if (c.dark) Color(0xFF6B6B70) else Color(0xFFB5B8C0)
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp).height(34.dp)
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val pad = 13.dp.toPx()
                        val usable = (size.width - 2 * pad).coerceAtLeast(1f)
                        settings.setFontSize(((p.x - pad) / usable * 6f + 0.5f).toInt().coerceIn(0, 6))
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { ch, _ ->
                        val pad = 13.dp.toPx()
                        val usable = (size.width - 2 * pad).coerceAtLeast(1f)
                        settings.setFontSize(((ch.position.x - pad) / usable * 6f + 0.5f).toInt().coerceIn(0, 6))
                    }
                },
        ) {
            val shadowBlurPx = with(LocalDensity.current) { 5.dp.toPx() }
            val thumbShadowPaint = remember(shadowBlurPx) {
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(46, 0, 0, 0)
                    maskFilter = android.graphics.BlurMaskFilter(shadowBlurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
            }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val r = thumbR.toPx()
                val usable = (size.width - 2 * r).coerceAtLeast(1f)
                val cy = size.height / 2f
                drawLine(trackCol, androidx.compose.ui.geometry.Offset(r, cy),
                    androidx.compose.ui.geometry.Offset(size.width - r, cy), strokeWidth = 1.6.dp.toPx())
                for (i in 0..6) {
                    val x = r + usable * (i / 6f)
                    drawLine(tickCol, androidx.compose.ui.geometry.Offset(x, cy - 5.dp.toPx()),
                        androidx.compose.ui.geometry.Offset(x, cy + 5.dp.toPx()), strokeWidth = 1.4.dp.toPx())
                }
                val tx = r + usable * (sliderValue / 6f)
                // Soft blurred elevation shadow without hard gray border ring
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawCircle(tx, cy + 1.5.dp.toPx(), r, thumbShadowPaint)
                }
                drawCircle(Color.White, radius = r,
                    center = androidx.compose.ui.geometry.Offset(tx, cy))
            }
        }
    }
}

// ref .switch: 紧凑轨道 40x22，#e4e4e4 off / #22c55e on，18px 白色 thumb 2px inset
@Composable
private fun RefSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val frac by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Box(Modifier.size(40.dp, 22.dp).clip(RoundedCornerShape(11.dp))
        .background(if (frac >= 0.5f) Color(0xFF22C55E) else Color(0xFFE4E4E4))
        .clickable { onCheckedChange(!checked) }) {
        Box(Modifier.padding(start = 2.dp, top = 2.dp)
            .graphicsLayer { translationX = (18f * frac).dp.toPx() }
            .size(18.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

private fun localeLabel(l: io.github.lijiahua1024.dsnative.data.LocalePreference) = when (l) {
    io.github.lijiahua1024.dsnative.data.LocalePreference.ZH_CN -> "中文 (简体中文)"
    io.github.lijiahua1024.dsnative.data.LocalePreference.EN_US -> "English"
    io.github.lijiahua1024.dsnative.data.LocalePreference.SYSTEM ->
        if (Strings.isZh()) "中文 (简体中文)" else "English"
}
private fun themeLabel(t: io.github.lijiahua1024.dsnative.data.ThemePreference) = when (t) {
    io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> Strings.get("settingsThemeLight")
    io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> Strings.get("settingsThemeDark")
    else -> Strings.get("settingsThemeSystem")
}

// ref .overlay+.choice: 60% dim, centered card w-56 max290 r20, radio rows h54,
// border 1px line top/bottom, confirm row brand-text 16/48.
@Composable
private fun ChoicePopup(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    title: String,
    icon: (@Composable () -> Unit)? = null,
    options: List<Pair<String, String>>,
    selected: String,
    onProgress: (Float) -> Unit = {},
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var next by remember { mutableStateOf(selected) }
    // 与 SettingsRow 一致的按压底色：浅色 #EBEBEB / 深色 #303030。
    val pressBg = if (c.dark) Color(0xFF303030) else Color(0xFFEBEBEB)
    val ov = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var dragBack by remember { mutableFloatStateOf(0f) }

    fun dismissAnimated(then: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        val start = (ov.value * (1f - dragBack)).coerceIn(0f, 1f)
        scope.launch {
            ov.snapTo(start)
            dragBack = 0f
            ov.animateTo(0f, androidx.compose.animation.core.tween(150))
            then()
        }
    }

    LaunchedEffect(Unit) { ov.animateTo(1f, androidx.compose.animation.core.tween(180)) }
    val eff = (ov.value * (1f - dragBack)).coerceIn(0f, 1f)
    LaunchedEffect(eff) { onProgress(eff) }

    androidx.activity.compose.PredictiveBackHandler(enabled = !closing) { flow ->
        try {
            flow.collect { ev -> dragBack = ev.progress.coerceIn(0f, 1f) }
            dismissAnimated()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val start = dragBack
            scope.launch {
                androidx.compose.animation.core.animate(start, 0f, animationSpec = androidx.compose.animation.core.tween(140)) { v, _ -> dragBack = v }
            }
            throw e
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f * eff))
        .statusBarsPadding()
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { dismissAnimated() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 294.dp).fillMaxWidth()
            .heightIn(max = 560.dp)
            .graphicsLayer {
                val s = 0.94f + 0.06f * eff
                scaleX = s; scaleY = s; alpha = eff
            }
            .clip(RoundedCornerShape(24.dp)).background(card)
            .clickable(enabled = false) {}) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) { icon(); Spacer(Modifier.height(6.dp)) }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary)
            }
            Column(Modifier.fillMaxWidth()
                .drawBehind {
                    val w = 0.7.dp.toPx()
                    drawLine(c.borderL1, androidx.compose.ui.geometry.Offset(0f, 0f),
                        androidx.compose.ui.geometry.Offset(size.width, 0f), w)
                    drawLine(c.borderL1, androidx.compose.ui.geometry.Offset(0f, size.height),
                        androidx.compose.ui.geometry.Offset(size.width, size.height), w)
                }) {
                options.forEach { (id, label) ->
                    val checked = next == id
                    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    Row(Modifier.fillMaxWidth().height(60.dp)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(when {
                            checked -> c.interactiveBgHover
                            pressed -> DsTheme.pressTint(pressed, pressBg)
                            else -> Color.Transparent
                        })
                        .clickable(interactionSource = src, indication = null) { next = id }
                        .padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(50))
                            .border(2.dp, if (checked) c.brandPrimary else muted,
                                RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center) {
                            if (checked) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50))
                                .background(c.brandPrimary))
                        }
                        Text(label, fontSize = 16.sp, color = c.labelPrimary)
                    }
                }
            }
            val confirmSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val confirmPressed by confirmSrc.collectIsPressedAsState()
            Box(Modifier.fillMaxWidth().height(54.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (confirmPressed) DsTheme.pressTint(confirmPressed, pressBg)
                    else Color.Transparent)
                .clickable(interactionSource = confirmSrc, indication = null)
                { dismissAnimated { onConfirm(next) } }, contentAlignment = Alignment.Center) {
                Text(Strings.get("confirm"), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.brandText)
            }
        }
    }
}

@Composable
private fun VoiceSheet(
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    card: Color, muted: Color,
    current: String,
    apiKey: String?,
    onProgress: (Float) -> Unit = {},
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tone by remember { mutableStateOf(current) }
    val tones = listOf(
        "stella" to (Strings.get("settingsTtsVoiceStella") to Strings.get("settingsTtsVoiceStellaDesc")),
        "tide" to (Strings.get("settingsTtsVoiceTide") to Strings.get("settingsTtsVoiceTideDesc")))
    val now = tones.firstOrNull { it.first == tone } ?: tones[0]
    val ov = remember { androidx.compose.animation.core.Animatable(0f) }
    val sheet = remember { androidx.compose.animation.core.Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var sheetH by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    var backDragFrac by remember { mutableFloatStateOf(0f) }
    val sheetEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose { io.github.lijiahua1024.dsnative.data.ReadAloud.stop() }
    }
    fun dismissAnimated(then: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        io.github.lijiahua1024.dsnative.data.ReadAloud.stop()
        val dragFrac = if (sheetH > 1) (dragY / sheetH).coerceIn(0f, 1f) else 0f
        val startSheet = (sheet.value - dragFrac - backDragFrac * 0.45f).coerceIn(0f, 1f)
        val startOv = (ov.value * startSheet).coerceIn(0f, 1f)
        scope.launch {
            dragY = 0f
            backDragFrac = 0f
            sheet.snapTo(startSheet)
            ov.snapTo(startOv)
            launch { ov.animateTo(0f, androidx.compose.animation.core.tween(180)) }
            sheet.animateTo(0f, androidx.compose.animation.core.tween(200, easing = sheetEase))
            then()
        }
    }
    fun previewTone(targetTone: String) {
        if (!apiKey.isNullOrBlank()) {
            io.github.lijiahua1024.dsnative.data.ReadAloud.toggle(
                scope = scope,
                messageId = "voice-preview-$targetTone-${System.currentTimeMillis()}",
                text = Strings.get("ttsPreviewSample"),
                apiKey = apiKey,
                voice = targetTone,
                onError = { key -> DsToast.show(Strings.get(key)) },
            )
        }
    }
    LaunchedEffect(Unit) {
        ov.animateTo(1f, androidx.compose.animation.core.tween(170))
    }
    LaunchedEffect(Unit) {
        sheet.animateTo(1f, androidx.compose.animation.core.tween(220, easing = sheetEase))
    }
    val dragFracNow = if (sheetH > 1) (dragY / sheetH).coerceIn(0f, 1f) else 0f
    val effVis = (ov.value * (1f - dragFracNow) * (1f - backDragFrac * 0.65f)).coerceIn(0f, 1f)
    LaunchedEffect(effVis) { onProgress(effVis) }

    androidx.activity.compose.PredictiveBackHandler(enabled = !closing) { flow ->
        try {
            flow.collect { ev -> backDragFrac = ev.progress.coerceIn(0f, 1f) }
            dismissAnimated()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val s = backDragFrac
            scope.launch {
                androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(160)) { v, _ -> backDragFrac = v }
            }
            throw e
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f * effVis))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { dismissAnimated() },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.814f)
            .onSizeChanged { sheetH = it.height.coerceAtLeast(1) }
            .graphicsLayer {
                translationY = ((1f - sheet.value) + backDragFrac * 0.45f) * size.height + dragY
            }
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(card)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, d -> dragY = (dragY + d).coerceAtLeast(0f) },
                    onDragEnd = {
                        if (dragY > 110.dp.toPx()) {
                            dismissAnimated()
                        } else {
                            val start = dragY
                            scope.launch {
                                val a = androidx.compose.animation.core.Animatable(start)
                                a.animateTo(0f, androidx.compose.animation.core.tween(180)) {
                                    dragY = value
                                }
                            }
                        }
                    },
                    onDragCancel = { dragY = 0f },
                )
            }
            .clickable(enabled = false) {}) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                Text(Strings.get("settingsTtsVoice"), fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, color = c.labelPrimary)
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                    .size(32.dp).clip(RoundedCornerShape(50))
                    .background(if (c.dark) Color(0xFF3C3C3D) else Color(0xFFEDEDED))
                    .clickable { dismissAnimated() }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), Strings.get("close"),
                        tint = c.labelPrimary, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth()
                .excludeFromDrawerGesture()
                .pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onHorizontalDrag = { _, d -> dx += d },
                        onDragEnd = {
                            if (kotlin.math.abs(dx) > 32 * density) {
                                val next = if (dx > 0) "stella" else "tide"
                                if (next != tone) {
                                    tone = next
                                    previewTone(next)
                                }
                            }
                        })
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                VoiceArt(tone)
                Spacer(Modifier.height(16.dp))
                Text(now.second.first, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary)
                Spacer(Modifier.height(4.dp))
                Text(now.second.second, fontSize = 14.sp, color = muted)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tones.forEach { (id, _) ->
                        Box(Modifier.size(22.dp).padding(7.dp).clip(RoundedCornerShape(50))
                            .background(if (tone == id) c.brandText else muted)
                            .clickable {
                                tone = id
                                previewTone(id)
                            })
                    }
                }
            }
            Text(Strings.get("confirm"), fontSize = 16.sp, color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                    .height(48.dp).clip(RoundedCornerShape(28.dp)).background(c.brandText)
                    .clickable { dismissAnimated { onConfirm(tone) } }
                    .wrapContentHeight(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun DsConfirmDialog(
    title: String,
    body: String?,
    confirmText: String,
    cancelText: String,
    danger: Boolean,
    usePopup: Boolean = false,
    onProgress: (Float) -> Unit = {},
    content: (@Composable () -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = DsTheme.colors
    val ov = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    fun dismissAnimated(then: () -> Unit = onDismiss) {
        if (closing) return
        closing = true
        scope.launch {
            ov.animateTo(0f, androidx.compose.animation.core.tween(140))
            then()
        }
    }

    LaunchedEffect(Unit) { ov.animateTo(1f, androidx.compose.animation.core.tween(160)) }
    LaunchedEffect(ov.value) { onProgress(ov.value) }

    val cardBg = if (c.dark) Color(0xFF292929) else Color.White
    val cancelBg = if (c.dark) Color(0xFF3A3A3D) else Color(0xFFFFFFFF)
    val confirmFill = if (danger) Color(0xFFEF4444) else c.brandPrimary

    val dialogBody: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.60f * ov.value))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { dismissAnimated() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(horizontal = 35.dp)
                    .widthIn(max = 310.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = 0.94f + 0.06f * ov.value
                        scaleX = s; scaleY = s; alpha = ov.value
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(cardBg)
                    .clickable(enabled = false) {}
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.labelPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (body != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        body,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = c.labelSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(12.dp))
                    content()
                }
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(confirmFill)
                        .clickable { dismissAnimated(onConfirm) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(confirmText, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(cancelBg)
                        .clickable { dismissAnimated() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(cancelText, fontSize = 16.sp, color = c.labelPrimary)
                }
            }
        }
    }

    if (usePopup) {
        val atOrigin = remember {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ) = androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
            }
        }
        androidx.compose.ui.window.Popup(
            popupPositionProvider = atOrigin,
            onDismissRequest = { dismissAnimated() },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true,
            ),
        ) { dialogBody() }
    } else {
        androidx.activity.compose.BackHandler(enabled = !closing) { dismissAnimated() }
        dialogBody()
    }
}

@Composable
private fun VoiceArt(tone: String) {
    val (base, glows) = if (tone == "tide")
        listOf(Color(0xFF768FD4), Color(0xFF51649C), Color(0xFF83BBDE)) to
            listOf(Triple(Color(0xFFD0EDFF), 0.20f to 0.15f, 0.57f),
                   Triple(Color(0xFF96A1ED), 0.80f to 0.84f, 0.71f))
    else
        listOf(Color(0xFFBDE6FB), Color(0xFF97BCE2), Color(0xFF6587C4)) to
            listOf(Triple(Color(0xFFE7F8FB), 0.14f to 0.82f, 0.63f),
                   Triple(Color(0xFFECFAFF), 0.72f to 0.18f, 0.59f))
    androidx.compose.foundation.Canvas(Modifier.size(180.dp, 100.dp)
        .clip(RoundedCornerShape(60.dp))) {
        drawRect(androidx.compose.ui.graphics.Brush.linearGradient(base,
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(size.width, size.height)))
        glows.forEach { (col, pos, r) ->
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(col, Color.Transparent),
                androidx.compose.ui.geometry.Offset(size.width * pos.first, size.height * pos.second),
                size.minDimension * r), radius = size.minDimension * r,
                center = androidx.compose.ui.geometry.Offset(size.width * pos.first, size.height * pos.second))
        }
    }
}
