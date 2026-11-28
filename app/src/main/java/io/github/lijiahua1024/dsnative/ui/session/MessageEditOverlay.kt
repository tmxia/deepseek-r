package io.github.lijiahua1024.dsnative.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.DraftAttachment
import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import io.github.lijiahua1024.dsnative.ui.input.DraftAttachmentsRow
import io.github.lijiahua1024.dsnative.ui.input.ImagePreviewDialog
import io.github.lijiahua1024.dsnative.ui.input.TogglePill
import io.github.lijiahua1024.dsnative.ui.input.AttachmentsPanel
import io.github.lijiahua1024.dsnative.ui.input.RecentPhoto
import io.github.lijiahua1024.dsnative.ui.input.attachDir
import io.github.lijiahua1024.dsnative.ui.input.importToDraft
import io.github.lijiahua1024.dsnative.ui.input.queryRecentPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun canSubmitEditedMessage(
    draft: String,
    files: List<*>,
    isStreaming: Boolean,
): Boolean = !isStreaming && (draft.trim().isNotEmpty() || files.isNotEmpty())

/** 原消息附件带入编辑草稿：沿用原件元数据与本地路径，未改动发送时直接复用。 */
private fun FileAttachment.toDraft(): DraftAttachment = DraftAttachment(
    id = id,
    name = name,
    mimeType = mimeType,
    isImage = isImage,
    localPath = localPath ?: "",
)

/**
 * Modal message editor matching official DeepSeek Android (`修改输入` / `放弃` composer sheet)
 * so editing opens cleanly above the soft keyboard without in-place bubble distortion.
 */
@Composable
fun MessageEditOverlay(
    sessionId: String,
    msg: Message.User,
    settings: SettingsStore,
    chatStore: ChatStore,
    isStreaming: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var backFrac by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    val ease = remember { androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(210, easing = ease)) }
    // 键盘在浮层仍可见时被外部收起（后台返回 / 系统回收输入法），先撤离焦点，避免
    // 残留输入连接在前台恢复时被系统重新挂起、再次把已收起的键盘顶起，面板随 inset
    // 反复伸缩跳变。口径与主输入框键盘回落处理一致。
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
    fun closeAnimated() {
        if (closing) return
        closing = true
        // 先断开输入连接，但不在退场首帧同步收起键盘：IME 收起的 inset 位移会与面板
        // 退场动画（graphicsLayer 淡出 / 下滑）并行叠加，面板在两帧间被先后推拉形成
        // 关闭跳闪。退场位移只由 layer 单一驱动；待面板透明后再收键盘并卸载。
        focusManager.clearFocus()
        scope.launch {
            val s = (enter.value * (1f - backFrac * 0.45f)).coerceIn(0f, 1f)
            enter.snapTo(s)
            backFrac = 0f
            enter.animateTo(0f, androidx.compose.animation.core.tween(170, easing = ease))
            runCatching { keyboardController?.hide() }
            onClose()
        }
    }
    androidx.activity.compose.PredictiveBackHandler(enabled = !closing) { flow ->
        try {
            flow.collect { ev -> backFrac = ev.progress.coerceIn(0f, 1f) }
            closeAnimated()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val s = backFrac
            scope.launch {
                androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(140)) { v, _ -> backFrac = v }
            }
            throw e
        }
    }
    val c = DsTheme.colors
    val thinking by settings.thinking.collectAsState()
    val search by settings.search.collectAsState()
    val focusRequester = remember(msg.id) { FocusRequester() }
    var value by remember(msg.id) {
        mutableStateOf(TextFieldValue(msg.content, selection = TextRange(msg.content.length)))
    }
    // 原消息 files 映射为编辑草稿附件（existing 引用），图片在浮层内可见、可移除；
    // 未改动项提交时沿用原 FileAttachment，不重复上传。
    var draftFiles by remember(msg.id) { mutableStateOf(msg.files.map { it.toDraft() }) }
    var previewDraft by remember { mutableStateOf<DraftAttachment?>(null) }
    val context = LocalContext.current
    var replaceTarget by remember(msg.id) { mutableStateOf<DraftAttachment?>(null) }
    var showAttachments by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var photos by remember { mutableStateOf<List<RecentPhoto>>(emptyList()) }
    LaunchedEffect(showAttachments) {
        photos = if (showAttachments) queryRecentPhotos(context) else emptyList()
    }
    // 附件面板打开时返回先收面板，不直接退出编辑浮层。
    androidx.activity.compose.BackHandler(enabled = showAttachments) { showAttachments = false }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val target = replaceTarget
        replaceTarget = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            if (target != null) {
                val att = withContext(Dispatchers.IO) { importToDraft(context, uris.first()) }
                if (att != null) {
                    draftFiles = draftFiles.map { if (it.id == target.id) att else it }
                }
            } else {
                val items = uris.mapNotNull { withContext(Dispatchers.IO) { importToDraft(context, it) } }
                draftFiles = draftFiles + items
            }
        }
    }
    val openDocs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = uris.mapNotNull { withContext(Dispatchers.IO) { importToDraft(context, it) } }
            draftFiles = draftFiles + items
        }
    }
    val openWeChatDocs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data
        val uris = buildList {
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(::add)
            }
            if (isEmpty()) data?.data?.let(::add)
        }
        if (uris.isNotEmpty()) scope.launch {
            val items = uris.mapNotNull { withContext(Dispatchers.IO) { importToDraft(context, it) } }
            draftFiles = draftFiles + items
        }
    }
    val takePicture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = cameraFile
        cameraFile = null
        if (file != null) scope.launch(Dispatchers.IO) {
            if (ok && file.exists() && file.length() > 0L) {
                val att = DraftAttachment(
                    id = UUID.randomUUID().toString(),
                    name = file.name,
                    mimeType = "image/jpeg",
                    isImage = true,
                    localPath = file.absolutePath,
                )
                withContext(Dispatchers.Main) { draftFiles = draftFiles + att }
            } else {
                file.delete()
            }
        }
    }
    LaunchedEffect(msg.id) {
        try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
        keyboardController?.show()
    }
    val canSend = canSubmitEditedMessage(value.text, draftFiles, isStreaming)
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val eff = (enter.value * (1f - backFrac * 0.45f)).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f * eff))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { closeAnimated() },
        )
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .graphicsLayer {
                    alpha = eff
                    translationY = (1f - eff) * 180f
                }
                .softShadow(
                    cornerRadius = 24.dp,
                    blurRadius = 16.dp,
                    offsetY = (-4).dp,
                    color = if (c.dark) Color.Transparent else Color.Black.copy(alpha = 0.10f),
                )
                .clip(sheetShape)
                .background(c.inputMajor)
                .border(0.6.dp, if (c.dark) c.borderL1 else Color(0xFFEAECEF), sheetShape)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {}
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Strings.get("editMessageTitle"),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(44.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = Strings.get("discard") }
                        .clickable { closeAnimated() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        Strings.get("discard"),
                        tint = c.labelSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            DraftAttachmentsRow(
                items = draftFiles,
                showSuggestions = false,
                canSend = canSend,
                onRemove = { item -> draftFiles = draftFiles.filterNot { it.id == item.id } },
                onPreview = { item -> previewDraft = item },
                onSuggestion = {},
            )
            AttachmentsPanel(
                open = showAttachments,
                photos = photos,
                selectedPhotoIds = draftFiles.mapNotNull { it.recentPhotoId }.toSet(),
                onPhoto = { photo ->
                    val existing = draftFiles.firstOrNull { it.recentPhotoId == photo.id }
                    if (existing != null) {
                        draftFiles = draftFiles.filterNot { it.id == existing.id }
                    } else scope.launch {
                        val att = withContext(Dispatchers.IO) { importToDraft(context, photo.uri, photo.id) }
                        if (att != null) draftFiles = draftFiles + att
                    }
                },
                onCamera = {
                    showAttachments = false
                    val dir = attachDir(context)
                    val f = File(dir, "camera-${System.currentTimeMillis()}.jpg")
                    val u = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f)
                    cameraFile = f
                    try {
                        takePicture.launch(u)
                    } catch (_: android.content.ActivityNotFoundException) {
                        cameraFile = null
                        f.delete()
                        DsToast.show(Strings.get("androidCameraUnavailable"))
                    }
                },
                onGallery = {
                    showAttachments = false
                    try {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: android.content.ActivityNotFoundException) {
                        DsToast.show(Strings.get("uploadFileStatusFailed"))
                    }
                },
                onFiles = {
                    showAttachments = false
                    try {
                        openDocs.launch(arrayOf("*/*"))
                    } catch (_: android.content.ActivityNotFoundException) {
                        DsToast.show(Strings.get("uploadFileStatusFailed"))
                    }
                },
                onWeChatFiles = {
                    showAttachments = false
                    val installed = context.packageManager.getLaunchIntentForPackage("com.tencent.mm") != null
                    if (!installed) {
                        DsToast.show(Strings.get("androidWeChatNotInstalled"))
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
                            DsToast.show(Strings.get("androidWeChatOpenFailed"))
                        }
                    }
                },
            )
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(min = 76.dp, max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(
                        fontSize = 16.5.sp,
                        lineHeight = 24.sp,
                        color = c.labelPrimary,
                        fontFamily = DsTheme.fontFamily,
                    ),
                    cursorBrush = SolidColor(c.brandText),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = true,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 15.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TogglePill(Strings.get("thinkingSwitch"), thinking, { settings.setThinking(!thinking) }, R.drawable.ic_think)
                    TogglePill(Strings.get("search"), search, { settings.setSearch(!search) }, R.drawable.ic_globe)
                }
                Spacer(Modifier.width(6.dp))
                val attSrc = remember { MutableInteractionSource() }
                val attPressed by attSrc.collectIsPressedAsState()
                Box(
                    Modifier.size(36.dp)
                        .clickable(attSrc, null) { showAttachments = !showAttachments },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (showAttachments) R.drawable.ic_close_circle else R.drawable.ic_add_circle),
                        Strings.get(if (showAttachments) "close" else "uploadFile"),
                        tint = c.labelPrimary,
                        modifier = Modifier.size(24.dp)
                            .clip(CircleShape)
                            .background(DsTheme.pressTint(attPressed, c.interactiveBgHover)),
                    )
                }
                Spacer(Modifier.width(5.dp))
                val disabledSendBg = if (c.dark) Color(0xFF4D6BFE).copy(alpha = 0.35f) else Color(0xFFB3C5FF)
                Box(
                    Modifier.size(32.dp)
                        .clip(CircleShape)
                        .background(if (canSend) c.buttonPrimaryFill else disabledSendBg)
                        .clickable(enabled = canSend) {
                            // 未改动的 existing 沿用原 FileAttachment（保留 serverId，不重复上传）；
                            // 新增/替换项由草稿本地文件生成。
                            val nextFiles = draftFiles.mapNotNull { d ->
                                msg.files.firstOrNull { it.id == d.id } ?: run {
                                    val len = try { File(d.localPath).length() }
                                    catch (_: Exception) { 0L }
                                    d.toFileAttachment(len)
                                }
                            }
                            chatStore.editMessage(sessionId, msg.id, value.text.trim(), nextFiles)
                            closeAnimated()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_send),
                        Strings.get("send"),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        previewDraft?.let { item ->
            ImagePreviewDialog(
                item = item,
                onClose = { previewDraft = null },
                onConfirmCrop = { cropped ->
                    draftFiles = draftFiles.map { if (it.id == item.id) cropped else it }
                    previewDraft = null
                },
                onDownload = {},
                onReplace = {
                    replaceTarget = item
                    previewDraft = null
                    try {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: android.content.ActivityNotFoundException) {}
                },
                onRemove = {
                    draftFiles = draftFiles.filterNot { it.id == item.id }
                    previewDraft = null
                },
            )
        }
    }
}
