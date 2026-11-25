package io.github.lijiahua1024.dsnative.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import io.github.lijiahua1024.dsnative.theme.dsPainterResource as painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.Session
import io.github.lijiahua1024.dsnative.data.replyText
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.theme.DsColors
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.ui.DrawerGestureBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

enum class FeedbackSheetVariant { FEEDBACK, REPORT }

private val FEEDBACK_REASONS = listOf(
    "harmful" to "feedbackHarmful",
    "false" to "feedbackFalse",
    "unhelpful" to "feedbackUnhelpful",
    "other" to "feedbackOther",
)

@Composable
fun MessageFeedbackSheetOverlay(
    variant: FeedbackSheetVariant,
    onClose: () -> Unit,
    onSubmit: (reason: String?, detail: String) -> Unit,
) {
    val c = DsTheme.colors
    var reason by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var sheetH by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    var backFrac by remember { mutableFloatStateOf(0f) }
    val sheetEase = io.github.lijiahua1024.dsnative.ui.TwoPhaseSpec.SheetEase
    LaunchedEffect(Unit) {
        prog.animateTo(1f, androidx.compose.animation.core.tween(220, easing = sheetEase))
    }
    fun closeAnimated(then: () -> Unit = onClose) {
        if (closing) return
        closing = true
        val dragFrac = if (sheetH > 1) (dragY / sheetH).coerceIn(0f, 1f) else 0f
        val start = (prog.value - dragFrac - backFrac * 0.45f).coerceIn(0f, 1f)
        scope.launch {
            dragY = 0f
            backFrac = 0f
            prog.snapTo(start)
            prog.animateTo(0f, androidx.compose.animation.core.tween(180, easing = sheetEase))
            then()
        }
    }
    androidx.activity.compose.PredictiveBackHandler(enabled = !closing) { flow ->
        try {
            flow.collect { ev -> backFrac = ev.progress.coerceIn(0f, 1f) }
            closeAnimated()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val s = backFrac
            scope.launch {
                androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(150)) { v, _ -> backFrac = v }
            }
            throw e
        }
    }
    val dragFracNow = if (sheetH > 1) (dragY / sheetH).coerceIn(0f, 1f) else 0f
    val eff = (prog.value * (1f - dragFracNow) * (1f - backFrac * 0.65f)).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f * eff))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { closeAnimated() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = c.bgBase,
            shape = RoundedCornerShape(topStart = 21.dp, topEnd = 21.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { sheetH = it.height.coerceAtLeast(1) }
                .graphicsLayer {
                    translationY = ((1f - prog.value) + backFrac * 0.45f) * size.height + dragY
                }
                .imePadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, d -> dragY = (dragY + d).coerceAtLeast(0f) },
                        onDragEnd = {
                            if (dragY > 100.dp.toPx()) closeAnimated()
                            else {
                                val s = dragY
                                scope.launch {
                                    androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(160)) { v, _ -> dragY = v }
                                }
                            }
                        },
                        onDragCancel = { dragY = 0f },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 22.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        Strings.get(if (variant == FeedbackSheetVariant.REPORT) "report" else "feedback"),
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.labelPrimary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.labelTertiary.copy(alpha = 0.09f))
                            .clickable { closeAnimated() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            Strings.get("close"),
                            tint = c.labelPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (variant == FeedbackSheetVariant.FEEDBACK) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.5.dp),
                    ) {
                        for ((id, labelKey) in FEEDBACK_REASONS) {
                            val selected = reason == id
                            val chipBg = when {
                                selected && c.dark -> Color(0xFF1E2B40)
                                selected -> Color(0xFFEDF3FE)
                                else -> c.bgBase
                            }
                            val chipBorder = when {
                                selected && c.dark -> Color(0xFF35527C)
                                selected -> Color(0xFFCFDFFF)
                                else -> c.borderL2
                            }
                            val chipText = when {
                                selected && c.dark -> Color(0xFF8BB3FF)
                                selected -> Color(0xFF426EFE)
                                else -> c.labelPrimary
                            }
                            Box(
                                Modifier
                                    .weight(1f, fill = false)
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(25.dp))
                                    .background(chipBg)
                                    .border(1.dp, chipBorder, RoundedCornerShape(25.dp))
                                    .clickable { reason = if (selected) null else id }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    Strings.get(labelKey),
                                    fontSize = 13.sp,
                                    color = chipText,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 128.dp, max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.labelTertiary.copy(alpha = 0.08f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (detail.isEmpty()) {
                        Text(
                            Strings.get("feedbackPlaceholder"),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = c.labelTertiary,
                        )
                    }
                    BasicTextField(
                        value = detail,
                        onValueChange = { detail = it },
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = c.labelPrimary,
                            fontFamily = DsTheme.fontFamily,
                        ),
                        cursorBrush = SolidColor(c.brandText),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.8.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(c.brandText)
                        .clickable { closeAnimated { onSubmit(reason, detail.trim()) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Strings.get("submit"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

internal fun selectedShareGroupCount(messages: List<Message>, selectedIds: Set<String>): Int =
    messages.count { it is Message.User && it.id in selectedIds }

internal fun formatShareTranscript(messages: List<Message>, selectedIds: Set<String>): String {
    val parts = ArrayList<String>()
    for (msg in messages) {
        if (msg.id !in selectedIds) continue
        when (msg) {
            is Message.User -> if (msg.content.isNotBlank()) parts.add("User:\n${msg.content.trim()}")
            is Message.Assistant -> {
                val txt = formattedAssistantCopyText(msg).trim()
                if (txt.isNotEmpty()) parts.add("DeepSeek:\n$txt")
            }
        }
    }
    return parts.joinToString("\n\n")
}

private suspend fun generateShareCardPng(
    context: Context,
    messages: List<Message>,
    selectedIds: Set<String>,
    dark: Boolean,
): File? = withContext(Dispatchers.IO) {
    val chosen = messages.filter { it.id in selectedIds }
    if (chosen.isEmpty()) return@withContext null
    try {
        val width = 1080
        val padX = 56f
        val contentW = (width - padX * 2).toInt()
        val bgColor = if (dark) 0xFF151517.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (dark) 0xFFF3F4F6.toInt() else 0xFF0F0F0F.toInt()
        val bubbleBg = if (dark) 0xFF282B33.toInt() else 0xFFEDF3FE.toInt()
        val bubbleText = if (dark) 0xFFE5E7EB.toInt() else 0xFF262626.toInt()
        val mutedColor = if (dark) 0xFF9CA3AF.toInt() else 0xFF8C8C8C.toInt()

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 40f
        }
        val userPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bubbleText
            textSize = 40f
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4D6BFE.toInt()
            textSize = 44f
            isFakeBoldText = true
        }
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mutedColor
            textSize = 30f
        }

        data class BlockDraw(val isUser: Boolean, val layout: StaticLayout)
        val blocks = ArrayList<BlockDraw>()
        var totalH = 140f // top header
        for (m in chosen) {
            val txt = when (m) {
                is Message.User -> m.content.trim()
                is Message.Assistant -> replyText(m).trim()
            }
            if (txt.isEmpty()) continue
            val isUser = m is Message.User
            val maxW = if (isUser) (contentW * 0.78f).toInt() else contentW
            val tp = if (isUser) userPaint else textPaint
            @Suppress("DEPRECATION")
            val layout = StaticLayout(txt, tp, maxW, Layout.Alignment.ALIGN_NORMAL, 1.35f, 0f, false)
            blocks.add(BlockDraw(isUser, layout))
            totalH += layout.height + (if (isUser) 96f else 56f)
        }
        totalH += 100f
        val height = min(8192, max(480, totalH.toInt()))
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)
        canvas.drawText("DeepSeek", padX, 86f, headerPaint)
        var y = 140f
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bubbleBg }
        for (b in blocks) {
            if (y > height - 90f) break
            if (b.isUser) {
                var maxLineW = 0f
                for (lineIdx in 0 until b.layout.lineCount) {
                    maxLineW = max(maxLineW, b.layout.getLineWidth(lineIdx))
                }
                val bw = maxLineW + 64f
                val bh = b.layout.height + 52f
                val left = width - padX - bw
                canvas.drawRoundRect(RectF(left, y, left + bw, y + bh), 36f, 36f, bubblePaint)
                canvas.save()
                canvas.translate(left + 32f, y + 26f)
                b.layout.draw(canvas)
                canvas.restore()
                y += bh + 40f
            } else {
                canvas.save()
                canvas.translate(padX, y)
                b.layout.draw(canvas)
                canvas.restore()
                y += b.layout.height + 48f
            }
        }
        canvas.drawText(Strings.get("androidContentCaveat"), padX, height - 38f, footerPaint)

        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val outFile = File(shareDir, "deepseek_share_${System.currentTimeMillis()}.png")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bmp.recycle()
        outFile
    } catch (_: Exception) {
        null
    }
}

@Composable
fun MessageShareOverlay(
    session: Session,
    onClose: () -> Unit,
) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember(session) { visibleMessages(session) }
    var selectedIds by remember(session.id) {
        mutableStateOf(messages.map { it.id }.toSet())
    }
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var backFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, androidx.compose.animation.core.tween(220, easing = io.github.lijiahua1024.dsnative.ui.TwoPhaseSpec.FadeInEase))
    }
    fun closeAnimated() {
        if (closing) return
        closing = true
        scope.launch {
            val s = (enter.value * (1f - backFrac)).coerceIn(0f, 1f)
            enter.snapTo(s)
            backFrac = 0f
            enter.animateTo(0f, androidx.compose.animation.core.tween(180))
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
                androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(150)) { v, _ -> backFrac = v }
            }
            throw e
        }
    }
    DrawerGestureBlocker(true)

    val allSelected = messages.isNotEmpty() && selectedIds.size == messages.size
    val selectedGroups = selectedShareGroupCount(messages, selectedIds)
    val blue = Color(0xFF4D80F0)
    val eff = (enter.value * (1f - backFrac)).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = eff
                translationX = (1f - eff) * 40.dp.toPx()
            }
            .background(c.bgBase)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        selectedIds = if (allSelected) emptySet() else messages.map { it.id }.toSet()
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ShareCheckCircle(checked = allSelected, blue = blue)
                Text(
                    Strings.get("selectAll"),
                    fontSize = 16.sp,
                    color = c.labelPrimary,
                )
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                val checked = msg.id in selectedIds
                val text = when (msg) {
                    is Message.User -> msg.content
                    is Message.Assistant -> replyText(msg)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedIds = if (checked) selectedIds - msg.id else selectedIds + msg.id
                        },
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.padding(top = 10.dp, end = 14.dp)) {
                        ShareCheckCircle(checked = checked, blue = blue)
                    }
                    if (msg is Message.User) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Box(
                                Modifier
                                    .widthIn(max = 290.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (c.dark) c.bubble else Color(0xFFEDF3FE))
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                            ) {
                                Text(
                                    text,
                                    fontSize = 16.sp,
                                    lineHeight = 26.sp,
                                    color = if (c.dark) c.labelPrimary else c.labelSecondary,
                                )
                            }
                        }
                    } else {
                        Text(
                            text,
                            fontSize = 16.sp,
                            lineHeight = 28.sp,
                            color = c.labelPrimary,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, top = 6.dp)
                        .alpha(0.32f),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_copy), null, tint = c.labelTertiary, modifier = Modifier.size(18.dp))
                    Icon(painterResource(R.drawable.ic_thumb_up), null, tint = c.labelTertiary, modifier = Modifier.size(18.dp))
                    Icon(painterResource(R.drawable.ic_thumb_down), null, tint = c.labelTertiary, modifier = Modifier.size(18.dp))
                    Icon(painterResource(R.drawable.ic_speaker), null, tint = c.labelTertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = c.labelTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = c.borderL2.copy(alpha = 0.6f))

        Column(Modifier.fillMaxWidth().background(c.bgBase)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    Strings.get("selectedGroups", "count" to selectedGroups),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.labelPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                IconButton(
                    onClick = { closeAnimated() },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        Strings.get("back"),
                        tint = c.labelPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                ShareActionItem(
                    label = Strings.get("generateImage"),
                    circleBg = Color(0xFF4F8BF7),
                    iconTint = Color.White,
                    iconRes = R.drawable.ic_image_outline,
                ) {
                    scope.launch {
                        val file = generateShareCardPng(ctx, messages, selectedIds, c.dark)
                        if (file == null) {
                            DsToast.show(Strings.get("shareImageFailed"))
                            return@launch
                        }
                        try {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(sendIntent, Strings.get("generateImage")))
                        } catch (_: Exception) {
                            DsToast.show(Strings.get("shareImageFailed"))
                        }
                    }
                }
                ShareActionItem(
                    label = Strings.get("copyLink"),
                    circleBg = blue,
                    iconTint = Color.White,
                    iconRes = R.drawable.ic_link,
                ) {
                    val txt = formatShareTranscript(messages, selectedIds)
                    if (txt.isEmpty()) return@ShareActionItem
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("chat", txt))
                    DsToast.show(Strings.get("shareLocalTextCopied"))
                }
                ShareActionItem(
                    label = Strings.get("shareToWeChat"),
                    circleBg = Color(0xFF24C466),
                    iconTint = Color.White,
                    iconRes = R.drawable.ic_wechat,
                ) {
                    val txt = formatShareTranscript(messages, selectedIds)
                    if (txt.isEmpty()) return@ShareActionItem
                    val wechatInstalled = ctx.packageManager.getLaunchIntentForPackage("com.tencent.mm") != null
                    if (!wechatInstalled) {
                        DsToast.show(Strings.get("wechatNotInstalled"))
                        return@ShareActionItem
                    }
                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, txt)
                            setPackage("com.tencent.mm")
                        }
                        ctx.startActivity(intent)
                    } catch (_: Exception) {
                        DsToast.show(Strings.get("wechatNotInstalled"))
                    }
                }
                ShareActionItem(
                    label = Strings.get("moreSharing"),
                    circleBg = if (c.dark) Color(0xFF1E2B40) else Color(0xFFEDF3FE),
                    iconTint = blue,
                    iconRes = R.drawable.ic_ellipsis,
                ) {
                    val txt = formatShareTranscript(messages, selectedIds)
                    if (txt.isEmpty()) return@ShareActionItem
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, txt)
                    }
                    ctx.startActivity(Intent.createChooser(intent, Strings.get("share")))
                }
            }
        }
    }
}

@Composable
private fun ShareCheckCircle(checked: Boolean, blue: Color) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(blue)
                else Modifier.border(1.5.dp, blue, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painterResource(R.drawable.ic_check),
                null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ShareActionItem(
    label: String,
    circleBg: Color,
    iconTint: Color,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val c = DsTheme.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(iconRes),
                label,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(label, fontSize = 13.sp, color = c.labelPrimary)
    }
}
