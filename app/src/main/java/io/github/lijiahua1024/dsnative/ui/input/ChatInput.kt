package io.github.lijiahua1024.dsnative.ui.input

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import io.github.lijiahua1024.dsnative.theme.dsPainterResource as painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val InputCardShape = RoundedCornerShape(24.dp)

internal fun showDisabledSend(
    focused: Boolean,
    voiceMode: Boolean,
    canSend: Boolean,
    isStreaming: Boolean,
): Boolean = false

// Android composer per ChatInput.module.css [data-android] .wrapper: radius
// 30px, font 16.75px, 1px border color-mix(currentColor 7%), shadow
// 0 10px 22px 7% / 0 1px 4px 4%; textarea padding-top 14.75px padding-inline
// 17px; functionRow h48 padding 0/7/4/11 gap4 center; toggles h32 r18 px11
// font 14/20 w400; right gap 0; send/stop 30px circle margin-right 7px.
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun ChatInput(settings: SettingsStore, chatStore: ChatStore) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    var text by remember(activeId) { mutableStateOf(chatStore.draft(activeId)) }
    LaunchedEffect(chatStore, activeId) {
        chatStore.drafts
            .map { it[activeId] ?: "" }
            .distinctUntilChanged()
            .collect { storeDraft ->
                if (text != storeDraft) text = storeDraft
            }
    }
    var focused by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf(0L) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val holdScope = androidx.compose.runtime.rememberCoroutineScope()
    // 发送成功后收起键盘、清焦点。延后到下一帧：sendMessage 可能新建会话并改 activeId，
    // 先让状态迁移与重组落定，避免在焦点树迁移的同帧操作失效节点；runCatching 兜底。
    val sendDraftAndHide: (String) -> Unit = { draft ->
        chatStore.setDraft(activeId, draft)
        chatStore.sendMessage(activeId)
        text = ""
        holdScope.launch {
            withFrameNanos { }
            runCatching {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }
    val thinking by settings.thinking.collectAsState()
    val search by settings.search.collectAsState()
    val isStreaming by remember(chatStore, activeId) {
        chatStore.streaming.map { activeId != null && it.contains(activeId) }.distinctUntilChanged()
    }.collectAsState(initial = chatStore.isStreamingSnapshot(activeId))
    val atts by remember(chatStore, activeId) {
        chatStore.draftAttachments.map { it[activeId] ?: emptyList() }.distinctUntilChanged()
    }.collectAsState(initial = chatStore.draftAttachments(activeId))
    // ref android: send 按钮只在 draftReady（文本非空或附件就绪）时出现；
    // 本地附件即时就绪
    val canSend = (text.isNotBlank() || atts.isNotEmpty()) && !isStreaming
    var showAttachments by remember { mutableStateOf(false) }
    var previewAtt by remember { mutableStateOf<DraftAttachment?>(null) }
    // 预览器「替换」时记录待被替换的草稿，选图返回后走 replaceDraftAttachment。
    var replaceTarget by remember { mutableStateOf<DraftAttachment?>(null) }
    // ref android-back: 附件面板打开时返回先收面板，不退出页面。
    androidx.activity.compose.BackHandler(enabled = showAttachments) {
        showAttachments = false
    }
    val inputScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // ref MainActivity.showRecentPhotos：打开附件面板时按版本请求媒体读取权限，
    // 每次会话只问一次；34+ 同时请求 READ_MEDIA_VISUAL_USER_SELECTED（部分授权也算）。
    var mediaGranted by remember { mutableStateOf(false) }
    val mediaPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        mediaGranted = grants[android.Manifest.permission.READ_MEDIA_IMAGES] == true ||
            grants[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            grants[android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
    }
    var askedForPhotos by remember { mutableStateOf(false) }
    LaunchedEffect(showAttachments) {
        if (!showAttachments) return@LaunchedEffect
        val sdk = android.os.Build.VERSION.SDK_INT
        val imagesPerm = if (sdk >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        fun granted(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(
            context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val has = granted(imagesPerm) ||
            (sdk >= 34 && granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        if (!has && !askedForPhotos) {
            askedForPhotos = true
            val perms = if (sdk >= 34) arrayOf(
                imagesPerm, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                else arrayOf(imagesPerm)
            mediaPermission.launch(perms)
        } else {
            mediaGranted = has
        }
    }
    val photos by produceState<List<RecentPhoto>>(emptyList(), showAttachments, mediaGranted) {
        value = if (showAttachments && mediaGranted) queryRecentPhotos(context) else emptyList()
    }
    val addFiles: (List<android.net.Uri>, Boolean) -> Unit = { uris, focusAfter ->
        inputScope.launch {
            val items = uris.mapNotNull { importToDraft(context, it) }
            if (items.isNotEmpty()) chatStore.addDraftAttachments(activeId, items)
            if (focusAfter) chatStore.requestFocusComposer()
        }
    }
    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) {
            chatStore.requestFocusComposer()
            return@rememberLauncherForActivityResult
        }
        val target = replaceTarget
        replaceTarget = null
        if (target != null) {
            inputScope.launch {
                uris.firstOrNull()?.let { importToDraft(context, it) }?.let {
                    chatStore.replaceDraftAttachment(activeId, target.id, it)
                }
            }
        } else {
            addFiles(uris, true)
        }
    }
    val openDocs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) addFiles(uris, false) }
    val openWeChatDocs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = buildList {
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(::add)
                }
                if (isEmpty()) data?.data?.let(::add)
            }
            if (uris.isNotEmpty()) addFiles(uris, false)
        }
    }
    val focusRequests by chatStore.focusComposerRequests.collectAsState()
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            if (VoiceInput.enabled.value) VoiceInput.enabled.value = false
            showAttachments = false
            try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
            keyboardController?.show()
        }
    }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = cameraFile
        cameraFile = null
        if (file != null) {
            inputScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (ok && file.exists() && file.length() > 0L) {
                    val att = DraftAttachment(
                        id = java.util.UUID.randomUUID().toString(),
                        name = file.name,
                        mimeType = "image/jpeg",
                        isImage = true,
                        localPath = file.absolutePath,
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        chatStore.addDraftAttachments(activeId, listOf(att))
                    }
                } else {
                    file.delete()
                }
            }
        }
    }
    val voiceMode by VoiceInput.enabled.collectAsState()
    val voiceStatus by VoiceInput.status.collectAsState()
    // ref: hold-to-talk 转写完成直接发送（sendSpoken）；点击语音键切模式时取消录音
    VoiceInput.apiKey = { settings.apiKey.value }
    VoiceInput.onText = { t ->
        sendDraftAndHide(t)
    }
    VoiceInput.onError = { key ->
        io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get(key))
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) VoiceInput.start(context)
        else android.widget.Toast.makeText(
            context, Strings.get("androidMicPermissionDenied"), android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    val tryStartVoice = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoiceInput.start(context)
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val keyboardOpen by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    var previousKeyboardOpen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardOpen) {
        if (previousKeyboardOpen && !keyboardOpen && focused) focusManager.clearFocus()
        previousKeyboardOpen = keyboardOpen
    }
    val cardShape = InputCardShape
    val cardBorder = if (c.dark) Color(0xFF333333) else Color.Black.copy(alpha = 0.055f)
    Column(
        Modifier.fillMaxWidth()
            .then(if (keyboardOpen) Modifier.imePadding() else Modifier)
            .padding(bottom = when {
                keyboardOpen -> 11.dp
                showAttachments -> 10.dp
                else -> 27.dp
            })
    ) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp)
            // During active voice recording, hide the composer card cleanly while keeping layout bounds.
            .alpha(if (voiceStatus != VoiceInput.Status.IDLE) 0f else 1f)
            .softShadow(
                cornerRadius = 24.dp,
                blurRadius = 14.dp,
                offsetY = 5.dp,
                color = if (c.dark) Color.Transparent else Color.Black.copy(alpha = 0.08f),
            )
            .clip(cardShape)
            .background(c.inputMajor)
            .border(0.6.dp, cardBorder, cardShape)
    ) {
        DraftAttachmentsRow(
            items = atts,
            showSuggestions = text.isBlank() && atts.any { it.isImage },
            canSend = canSend,
            onRemove = { chatStore.removeDraftAttachment(activeId, it.id) },
            onPreview = { previewAtt = it },
            onSuggestion = { s ->
                sendDraftAndHide(s)
            },
        )
        if (voiceMode) {
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .drawWithContent {
                        drawContent()
                        drawLine(c.labelPrimary.copy(alpha = 0.06f),
                            Offset(0f, size.height), Offset(size.width, size.height), 0.7.dp.toPx())
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startY = down.position.y
                            tryStartVoice()
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (VoiceInput.status.value == VoiceInput.Status.RECORDING) {
                                    VoiceInput.canceling.value = startY - ch.position.y >= 72.dp.toPx()
                                }
                                if (!ch.pressed) break
                            }
                            if (VoiceInput.canceling.value) VoiceInput.cancel() else VoiceInput.finish()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    Strings.get(if (voiceStatus == VoiceInput.Status.TRANSCRIBING)
                        "voiceInputCancel" else "androidHoldToTalk"),
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary,
                )
            }
        } else {
        Box(
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 58.dp)
                .padding(start = 17.dp, end = 17.dp, top = 16.dp, bottom = 10.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; chatStore.setDraft(activeId, it) },
                textStyle = TextStyle(fontSize = 16.75.sp, color = c.labelPrimary,
                    lineHeight = 24.sp, fontFamily = DsTheme.fontFamily),
                cursorBrush = SolidColor(c.brandText),
                singleLine = false,
                maxLines = 7,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = true,
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(max = 172.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                Strings.get(if (focused) "androidMessageHint" else "androidHoldHint"),
                                fontSize = 16.75.sp, lineHeight = 24.sp, color = c.labelCaption,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) {
                            focusAt = android.os.SystemClock.uptimeMillis()
                            showAttachments = false
                        }
                    }
                    // hold.delayed: pointerdown on empty unfocused field arms 380ms hold-to-talk timer.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val arm = text.isEmpty() &&
                                (!focused || android.os.SystemClock.uptimeMillis() - focusAt < 300)
                            if (!arm) return@awaitEachGesture
                            val startY = down.position.y
                            var started = false
                            val timer = holdScope.launch {
                                kotlinx.coroutines.delay(380)
                                    tryStartVoice(); started = true
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (started && VoiceInput.status.value == VoiceInput.Status.RECORDING) {
                                    VoiceInput.canceling.value = startY - ch.position.y >= 72.dp.toPx()
                                }
                                if (!ch.pressed) break
                                if (!started && kotlin.math.abs(startY - ch.position.y) > 12.dp.toPx()) break
                            }
                            if (!started) timer.cancel()
                            if (started) {
                                if (VoiceInput.canceling.value) VoiceInput.cancel() else VoiceInput.finish()
                            }
                        }
                    },
            )
            var previousVoiceMode by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(voiceMode) {
                if (previousVoiceMode == true && !voiceMode) {
                    try { focusRequester.requestFocus() }
                    catch (_: IllegalStateException) { }
                    keyboardController?.show()
                }
                previousVoiceMode = voiceMode
            }
        }
        }
        val togglesScroll = rememberScrollState()
        val needToggleFade by remember { derivedStateOf { togglesScroll.maxValue > 0 } }
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 12.dp, end = 10.dp, top = if (voiceMode) 10.dp else 0.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .then(
                        if (!needToggleFade) Modifier else Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithCache {
                                val fadeLeftW = 16.dp.toPx()
                                val fadeRightW = 20.dp.toPx()
                                val leftBrush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Black),
                                    startX = 0f,
                                    endX = fadeLeftW,
                                )
                                val rightBrush = Brush.horizontalGradient(
                                    listOf(Color.Black, Color.Transparent),
                                    startX = size.width - fadeRightW,
                                    endX = size.width,
                                )
                                onDrawWithContent {
                                    drawContent()
                                    val overflow = togglesScroll.maxValue > 0
                                    if (overflow && togglesScroll.value > 1) {
                                        drawRect(
                                            brush = leftBrush,
                                            size = Size(fadeLeftW, size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    if (overflow && togglesScroll.value < togglesScroll.maxValue - 1) {
                                        drawRect(
                                            brush = rightBrush,
                                            topLeft = Offset(size.width - fadeRightW, 0f),
                                            size = Size(fadeRightW, size.height),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                }
                            }
                    )
                    .horizontalScroll(togglesScroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TogglePill(Strings.get("thinkingSwitch"), thinking, { settings.setThinking(!thinking) }, R.drawable.ic_think)
                TogglePill(Strings.get("search"), search, { settings.setSearch(!search) }, R.drawable.ic_globe)
            }
            Spacer(Modifier.width(6.dp))
            val attSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val attPressed by attSrc.collectIsPressedAsState()
            Box(
                Modifier.align(Alignment.CenterVertically).size(36.dp)
                    .clickable(attSrc, null) {
                        if (!showAttachments) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                        showAttachments = !showAttachments
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(if (showAttachments) R.drawable.ic_close_circle else R.drawable.ic_add_circle),
                    Strings.get(if (showAttachments) "close" else "uploadFile"),
                    tint = c.labelPrimary,
                    modifier = Modifier.size(24.dp)
                        .clip(CircleShape)
                        .background(DsTheme.pressTint(attPressed, c.interactiveBgHover)))
            }
            Spacer(Modifier.width(5.dp))
            val voiceSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val voicePressed by voiceSrc.collectIsPressedAsState()
            val voiceIcon = if (voiceMode) R.drawable.ic_keyboard_circle else R.drawable.ic_voice_circle
            val voiceDesc = Strings.get(if (voiceMode) "androidSwitchToText" else "voiceInput")
            if (voiceStatus == VoiceInput.Status.RECORDING) {
                val haloEnter = remember { androidx.compose.animation.core.Animatable(0f) }
                val coreEnter = remember { androidx.compose.animation.core.Animatable(0f) }
                val glyphIn = remember { androidx.compose.animation.core.Animatable(0f) }
                val easeOut = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)
                val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "haloBreath")
                val haloS = breath.animateFloat(1f, 1.1f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(1800,
                            easing = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                        androidx.compose.animation.core.StartOffset(150)), label = "haloS")
                LaunchedEffect(Unit) {
                    haloEnter.animateTo(1f, androidx.compose.animation.core.tween(150, easing = easeOut))
                }
                LaunchedEffect(Unit) {
                    coreEnter.animateTo(1f,
                        androidx.compose.animation.core.tween(200, delayMillis = 140, easing = easeOut))
                }
                LaunchedEffect(Unit) {
                    glyphIn.animateTo(1f,
                        androidx.compose.animation.core.tween(100, delayMillis = 260, easing = easeOut))
                }
                Box(
                    Modifier.align(Alignment.CenterVertically).size(40.dp, 36.dp)
                        .clickable(voiceSrc, null) {
                            VoiceInput.cancel()
                            VoiceInput.enabled.value = !VoiceInput.enabled.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp)
                        .graphicsLayer {
                            val s = (0.6f + 0.4f * haloEnter.value) * haloS.value
                            scaleX = s; scaleY = s; alpha = 0.2f * haloEnter.value
                        }
                        .clip(CircleShape).background(c.brandPrimary))
                    Box(Modifier.size(32.dp)
                        .graphicsLayer {
                            val s = 0.6f + 0.4f * coreEnter.value
                            scaleX = s; scaleY = s; alpha = 0.75f * coreEnter.value
                        }
                        .clip(CircleShape).background(c.brandPrimary))
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(voiceIcon), null,
                            tint = Color.White,
                            modifier = Modifier.size(25.dp)
                                .graphicsLayer { alpha = glyphIn.value })
                    }
                    Box(Modifier.size(25.dp).align(Alignment.Center)
                        .semantics { contentDescription = voiceDesc }) {}
                }
            } else if (!canSend && !isStreaming) {
                Box(
                    Modifier.align(Alignment.CenterVertically).size(40.dp, 36.dp)
                        .clickable(voiceSrc, null) {
                            VoiceInput.cancel()
                            VoiceInput.enabled.value = !VoiceInput.enabled.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(voiceIcon), voiceDesc,
                        tint = c.labelPrimary,
                        modifier = Modifier.size(25.dp)
                            .clip(CircleShape)
                            .background(DsTheme.pressTint(voicePressed, c.interactiveBgHover)))
                }
            }
            if (canSend || isStreaming) {
                Box(
                    Modifier.align(Alignment.CenterVertically)
                        .size(40.dp, 36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(c.buttonPrimaryFill)
                            .clickable {
                                if (isStreaming) chatStore.stopReply(activeId!!)
                                else sendDraftAndHide(text)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (isStreaming) R.drawable.ic_stop else R.drawable.ic_send),
                            Strings.get(if (isStreaming) "stopGenerating" else "send"),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
    // ref AndroidAttachments：卡片外、composer 列内展开的面板
    AttachmentsPanel(
        open = showAttachments,
        photos = photos,
        selectedPhotoIds = atts.mapNotNull { it.recentPhotoId }.toSet(),
        onPhoto = { photo ->
            val existing = atts.firstOrNull { it.recentPhotoId == photo.id }
            if (existing != null) {
                chatStore.removeDraftAttachment(activeId, existing.id)
            } else {
                inputScope.launch {
                    importToDraft(context, photo.uri, photo.id)?.let {
                        chatStore.addDraftAttachments(activeId, listOf(it))
                    }
                }
            }
        },
        onCamera = {
            showAttachments = false
            val dir = attachDir(context)
            val f = java.io.File(dir, "camera-${System.currentTimeMillis()}.jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", f)
            cameraFile = f
            try {
                takePicture.launch(u)
            } catch (_: android.content.ActivityNotFoundException) {
                cameraFile = null
                f.delete()
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("androidCameraUnavailable"))
            }
        },
        onGallery = {
            showAttachments = false
            try {
                pickImages.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: android.content.ActivityNotFoundException) {
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("uploadFileStatusFailed"))
            }
        },
        onFiles = {
            showAttachments = false
            try {
                openDocs.launch(arrayOf("*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("uploadFileStatusFailed"))
            }
        },
        onWeChatFiles = {
            showAttachments = false
            val installed = context.packageManager.getLaunchIntentForPackage("com.tencent.mm") != null
            if (!installed) {
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("androidWeChatNotInstalled"))
            } else {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    type = "*/*"
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        putExtra(
                            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FWeiXin"),
                        )
                    }
                }
                try {
                    openWeChatDocs.launch(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("androidWeChatOpenFailed"))
                }
            }
        },
    )
    }
    previewAtt?.let { att ->
        ImagePreviewDialog(
            att,
            onClose = { previewAtt = null },
            onConfirmCrop = { cropped ->
                chatStore.replaceDraftAttachment(activeId, att.id, cropped)
                previewAtt = null
                chatStore.requestFocusComposer()
            },
            onDownload = {},
            onReplace = {
                replaceTarget = att
                previewAtt = null
                try {
                    pickImages.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: android.content.ActivityNotFoundException) {
                    io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("uploadFileStatusFailed"))
                }
            },
            onRemove = {
                chatStore.removeDraftAttachment(activeId, att.id)
                previewAtt = null
            },
        )
    }
}

@Composable
internal fun TogglePill(label: String, on: Boolean, onClick: () -> Unit, icon: Int) {
    val c = DsTheme.colors
    // ref [data-android] .toggle:active -> transform: scale(.96), 100ms
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.tween(100), label = "togglePress")
    val pillShape = RoundedCornerShape(18.dp)
    val bg = if (on) c.buttonGhostActiveFill else if (c.dark) c.inputMajor else Color.Transparent
    Row(
        Modifier.height(33.dp).defaultMinSize(minWidth = 100.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(pillShape)
            .background(bg)
            .border(1.dp, if (on) c.buttonGhostActiveBorder else c.borderL2, pillShape)
            .toggleable(
                value = on,
                interactionSource = src,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Checkbox,
            ) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null,
            tint = if (on) c.brandText else c.labelPrimary,
            modifier = Modifier.size(15.5.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            color = if (on) c.brandText else c.labelPrimary,
        )
    }
}
