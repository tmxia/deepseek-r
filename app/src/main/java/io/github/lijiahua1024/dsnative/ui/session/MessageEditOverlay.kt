package io.github.lijiahua1024.dsnative.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import java.io.File

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
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var backFrac by remember { mutableFloatStateOf(0f) }
    val ease = remember { androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.72f, 0.25f, 1f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(210, easing = ease)) }
    fun closeAnimated() {
        if (closing) return
        closing = true
        keyboardController?.hide()
        scope.launch {
            val s = (enter.value * (1f - backFrac * 0.45f)).coerceIn(0f, 1f)
            enter.snapTo(s)
            backFrac = 0f
            enter.animateTo(0f, androidx.compose.animation.core.tween(170, easing = ease))
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
                Spacer(Modifier.width(8.dp))
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
                onConfirmCrop = null,
                onDownload = {},
            )
        }
    }
}
