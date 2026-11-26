package io.github.lijiahua1024.dsnative.ui.session

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.theme.DsColors
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 原生 Mermaid 图表渲染卡片（零 WebView）。
 *
 * 对齐官方 DeepSeek `2.8.0`：
 * - `#FAFAFA` 卡片底色，左侧 `图表` / `代码` 分段胶囊，右侧 `保存` + `全屏` 图标文字操作
 * - Mermaid Classic 视觉风格（节点 `#ECECFF` 填充 / `#9370DB` 描边 / `#333333` 连线与字色，深色主题自动适配）
 * - 支持 Flowchart / Graph (TD/TB/LR/RL/BT)、Sequence、State、Class、ER、Pie 图表
 * - 支持全屏双指缩放与双击复位，以及高清 PNG 导出到系统相册
 */
@Composable
fun MermaidCodeBlock(
    literal: String,
    streaming: Boolean,
    c: DsColors,
    highlight: @Composable (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val measurer = rememberTextMeasurer()

    var tab by remember { mutableIntStateOf(0) } // 0 = 图表, 1 = 代码
    var showFullscreen by remember { mutableStateOf(false) }

    val parsed = remember(literal) { parseMermaidCached(literal) }
    val cardBg = if (c.dark) c.markdownCodeBlock else Color(0xFFFAFAFA)
    val segTrackBg = if (c.dark) c.bgLayer3 else Color(0xFFF0F0F0)
    val segActiveBg = if (c.dark) c.bgBase else Color.White
    val actionTint = if (c.dark) c.labelSecondary else Color(0xFF888A90)

    val cardPalette = remember(c.dark) { mermaidPalette(dark = c.dark) }
    val measuredLayout = remember(parsed, cardPalette, density.density, density.fontScale) {
        parsed?.let { layoutMermaidDiagram(it, cardPalette, measurer, density) }
    }

    fun exportDiagramToGallery() {
        val diagram = parsed ?: return
        val exportDensity = Density(3f, 1f)
        val exportPalette = mermaidPalette(dark = false)
        val exportMeasurer = TextMeasurer(fontFamilyResolver, exportDensity, LayoutDirection.Ltr)
        val exportLayout = layoutMermaidDiagram(diagram, exportPalette, exportMeasurer, exportDensity)
        val ok = renderAndSaveMermaidPng(context, exportLayout, exportDensity, Color.White)
        DsToast.show(Strings.get(if (ok) "androidImageSaved" else "androidImageSaveFailed"))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg),
    ) {
        // 顶部栏：[ 图表 | 代码 ] ........... 保存   全屏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(segTrackBg)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MermaidSegTab(
                    label = Strings.get("mermaidDiagram"),
                    selected = tab == 0,
                    activeBg = segActiveBg,
                    activeColor = c.labelPrimary,
                    inactiveColor = actionTint,
                    onClick = { tab = 0 },
                )
                MermaidSegTab(
                    label = Strings.get("mermaidCode"),
                    selected = tab == 1,
                    activeBg = segActiveBg,
                    activeColor = c.labelPrimary,
                    inactiveColor = actionTint,
                    onClick = { tab = 1 },
                )
            }

            Spacer(Modifier.weight(1f))

            if (tab == 0 && measuredLayout != null) {
                MermaidHeaderAction(
                    iconRes = R.drawable.ic_download,
                    label = Strings.get("mermaidSave"),
                    tint = actionTint,
                    onClick = { exportDiagramToGallery() },
                )
                Spacer(Modifier.width(14.dp))
                MermaidHeaderAction(
                    iconRes = R.drawable.ic_fullscreen,
                    label = Strings.get("mermaidFullscreen"),
                    tint = actionTint,
                    onClick = { showFullscreen = true },
                )
            } else if (tab == 1) {
                MermaidHeaderAction(
                    iconRes = R.drawable.ic_copy,
                    label = Strings.get("copy"),
                    tint = actionTint,
                    onClick = {
                        clipboard.setText(AnnotatedString(literal))
                        DsToast.show(Strings.get("copied"))
                    },
                )
            }
        }

        if (tab == 1) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                highlight(literal)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (measuredLayout == null) {
                    Text(
                        text = if (streaming) Strings.get("mermaidLoading")
                        else if (literal.isBlank()) Strings.get("mermaidEmpty")
                        else Strings.get("mermaidRenderFail"),
                        fontSize = 14.sp,
                        color = actionTint,
                        modifier = Modifier.padding(vertical = 28.dp),
                    )
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val availW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                        val fitScale = min(1f, availW / measuredLayout.widthPx.coerceAtLeast(1f))
                        if (fitScale >= 0.45f) {
                            val drawW = with(density) { (measuredLayout.widthPx * fitScale).toDp() }
                            val drawH = with(density) { (measuredLayout.heightPx * fitScale).toDp() }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Canvas(
                                    Modifier
                                        .size(drawW, drawH)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { showFullscreen = true },
                                        ),
                                ) {
                                    drawContext.canvas.save()
                                    drawContext.canvas.scale(fitScale, fitScale)
                                    measuredLayout.draw(this)
                                    drawContext.canvas.restore()
                                }
                            }
                        } else {
                            val hScroll = rememberScrollState()
                            val drawW = with(density) { measuredLayout.widthPx.toDp() }
                            val drawH = with(density) { measuredLayout.heightPx.toDp() }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(hScroll)
                                    .excludeFromDrawerGesture(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Canvas(
                                    Modifier
                                        .size(drawW, drawH)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { showFullscreen = true },
                                        ),
                                ) {
                                    measuredLayout.draw(this)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFullscreen && parsed != null) {
        MermaidFullscreenDialog(
            diagram = parsed,
            onSave = { exportDiagramToGallery() },
            onDismiss = { showFullscreen = false },
        )
    }
}

@Composable
private fun MermaidSegTab(
    label: String,
    selected: Boolean,
    activeBg: Color,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) activeBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) activeColor else inactiveColor,
        )
    }
}

@Composable
private fun MermaidHeaderAction(
    iconRes: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = tint,
        )
    }
}

@Composable
private fun MermaidFullscreenDialog(
    diagram: MermaidDiagram,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val palette = remember { mermaidPalette(dark = false) }
    val layout = remember(diagram, density.density, density.fontScale) {
        layoutMermaidDiagram(diagram, palette, measurer, density)
    }
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val backDrag = remember { Animatable(0f) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) { visible = true }

    fun closeAnimated() {
        if (closing) return
        closing = true
        visible = false
        scope.launch {
            kotlinx.coroutines.delay(200)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = ::closeAnimated,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PredictiveBackHandler(enabled = !closing) { flow ->
            try {
                flow.collect { ev -> backDrag.snapTo(ev.progress.coerceIn(0f, 1f)) }
                closing = true
                visible = false
                backDrag.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                onDismiss()
            } catch (_: Throwable) {
                backDrag.animateTo(0f, tween(160, easing = FastOutSlowInEasing))
            }
        }

        val scaleDrop = 1f - 0.08f * backDrag.value
        val bgAlpha = 1f - 0.65f * backDrag.value

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = bgAlpha)),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.94f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.94f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scaleDrop
                            scaleY = scaleDrop
                        }
                        .pointerInput(layout) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoom > 1.15f) {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    } else {
                                        zoom = 2.2f
                                    }
                                },
                            )
                        }
                        .pointerInput(layout) {
                            detectTransformGestures { _, panDelta, zoomDelta, _ ->
                                val nextZoom = (zoom * zoomDelta).coerceIn(0.6f, 5f)
                                zoom = nextZoom
                                pan += panDelta
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val availW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                        val availH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f) * 0.72f
                        val baseScale = min(
                            availW / layout.widthPx.coerceAtLeast(1f),
                            max(0.55f, availH / layout.heightPx.coerceAtLeast(1f)),
                        ).coerceAtMost(1.8f)
                        val totalScale = baseScale * zoom
                        val cardW = with(density) { (layout.widthPx * baseScale).toDp() }
                        val cardH = with(density) { (layout.heightPx * baseScale).toDp() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationX = pan.x
                                    translationY = pan.y
                                    scaleX = zoom
                                    scaleY = zoom
                                }
                                .background(Color.White)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(cardW, cardH)) {
                                drawContext.canvas.save()
                                drawContext.canvas.scale(baseScale, baseScale)
                                layout.draw(this)
                                drawContext.canvas.restore()
                            }
                        }
                    }

                    // 左上角关闭 ×
                    IconButton(
                        onClick = ::closeAnimated,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 8.dp, top = 6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = Strings.get("close"),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // 右下角 保存 胶囊按钮（官方 [48,48,49] 背景）
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 16.dp, bottom = 58.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF303031))
                            .clickable(onClick = onSave)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = Strings.get("mermaidSave"),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = Strings.get("mermaidSave"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private data class MermaidPalette(
    val nodeFill: Color,
    val nodeStroke: Color,
    val text: Color,
    val edge: Color,
    val edgeLabelBg: Color,
    val clusterFill: Color,
    val clusterStroke: Color,
    val clusterText: Color,
    val actorLine: Color,
    val noteFill: Color,
    val noteStroke: Color,
    val startEndDot: Color,
)

private fun mermaidPalette(dark: Boolean): MermaidPalette = if (!dark) {
    MermaidPalette(
        nodeFill = Color(0xFFECECFF),
        nodeStroke = Color(0xFF9370DB),
        text = Color(0xFF333333),
        edge = Color(0xFF333333),
        edgeLabelBg = Color(0xE6E8E8E8),
        clusterFill = Color(0xFFFFFFDE),
        clusterStroke = Color(0xFFAAAA33),
        clusterText = Color(0xFF333333),
        actorLine = Color(0xFF999999),
        noteFill = Color(0xFFFFF5AD),
        noteStroke = Color(0xFFAAAA33),
        startEndDot = Color(0xFF333333),
    )
} else {
    MermaidPalette(
        nodeFill = Color(0xFF2B2A42),
        nodeStroke = Color(0xFF8D7CE0),
        text = Color(0xFFE6E6EE),
        edge = Color(0xFFB4B7C5),
        edgeLabelBg = Color(0xE6262730),
        clusterFill = Color(0xFF272722),
        clusterStroke = Color(0xFF6B6B38),
        clusterText = Color(0xFFE6E6EE),
        actorLine = Color(0xFF7B7F8D),
        noteFill = Color(0xFF353322),
        noteStroke = Color(0xFF807B40),
        startEndDot = Color(0xFFE6E6EE),
    )
}

// =============================================================================
// 1. AST & Parser
// =============================================================================

private sealed class MermaidDiagram {
    data class Flowchart(
        val dir: FlowDir,
        val nodes: List<FlowNode>,
        val edges: List<FlowEdge>,
        val subgraphs: List<Subgraph>,
    ) : MermaidDiagram()

    data class Sequence(
        val participants: List<SeqParticipant>,
        val steps: List<SeqStep>,
    ) : MermaidDiagram()

    data class Pie(
        val title: String?,
        val slices: List<PieSlice>,
    ) : MermaidDiagram()
}

private enum class FlowDir { TD, BT, LR, RL }

private enum class NodeShape { RECT, ROUND, STADIUM, DIAMOND, CIRCLE, SUBROUTINE, DATABASE, HEXAGON, START_DOT, END_DOT }

private data class FlowNode(
    val id: String,
    val label: String,
    val shape: NodeShape = NodeShape.RECT,
    val subgraphId: String? = null,
)

private enum class EdgeStyle { SOLID, DOTTED, THICK }
private enum class ArrowHead { ARROW, NONE, CIRCLE, CROSS }

private data class FlowEdge(
    val from: String,
    val to: String,
    val label: String = "",
    val style: EdgeStyle = EdgeStyle.SOLID,
    val headEnd: ArrowHead = ArrowHead.ARROW,
    val headStart: ArrowHead = ArrowHead.NONE,
)

private data class Subgraph(
    val id: String,
    val title: String,
    val nodeIds: MutableList<String> = ArrayList(),
)

private data class SeqParticipant(val id: String, val label: String)
private sealed class SeqStep {
    data class Message(val from: String, val to: String, val text: String, val dashed: Boolean) : SeqStep()
    data class Note(val actors: List<String>, val text: String) : SeqStep()
    data class Block(val label: String, val startIndex: Int, val endIndex: Int) : SeqStep()
}

private data class PieSlice(val label: String, val value: Float)

// Mermaid 解析产物跨实例缓存：解析为纯字符串处理，不捕获 TextMeasurer/上下文；
// 产物全部为 data class/enum/sealed 不可变数据。排版结果持有 TextLayoutResult，不入缓存。
private val mermaidParseCache = object : android.util.LruCache<String, MermaidDiagram>(32) {
    override fun sizeOf(key: String, value: MermaidDiagram): Int = key.length + 16
}

private fun parseMermaidCached(raw: String): MermaidDiagram? {
    synchronized(mermaidParseCache) { mermaidParseCache.get(raw)?.let { return it } }
    return parseMermaidDiagram(raw)?.also {
        synchronized(mermaidParseCache) { mermaidParseCache.put(raw, it) }
    }
}

private fun parseMermaidDiagram(raw: String): MermaidDiagram? {
    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("%%") }
    if (lines.isEmpty()) return null

    // Skip optional YAML frontmatter --- ... ---
    var idx = 0
    if (lines[0] == "---") {
        idx = 1
        while (idx < lines.size && lines[idx] != "---") idx++
        idx++
    }
    while (idx < lines.size && lines[idx].startsWith("%%")) idx++
    if (idx >= lines.size) return null

    val header = lines[idx]
    val lower = header.lowercase()
    return when {
        lower.startsWith("sequencediagram") -> parseSequence(lines.subList(idx + 1, lines.size))
        lower.startsWith("pie") -> parsePie(header, lines.subList(idx + 1, lines.size))
        lower.startsWith("statediagram") -> parseStateAsFlow(lines.subList(idx + 1, lines.size))
        lower.startsWith("classdiagram") -> parseClassAsFlow(lines.subList(idx + 1, lines.size))
        lower.startsWith("erdiagram") -> parseErAsFlow(lines.subList(idx + 1, lines.size))
        lower.startsWith("graph") || lower.startsWith("flowchart") -> {
            val tokens = header.split(Regex("\\s+"))
            val dir = when (tokens.getOrNull(1)?.uppercase()) {
                "LR" -> FlowDir.LR
                "RL" -> FlowDir.RL
                "BT" -> FlowDir.BT
                else -> FlowDir.TD
            }
            parseFlowchart(dir, lines.subList(idx + 1, lines.size))
        }
        else -> {
            // Heuristic fallback: if it contains `-->` or `---`, treat as TD flowchart
            if (lines.any { it.contains("-->") || it.contains("-.-") || it.contains("==>") }) {
                parseFlowchart(FlowDir.TD, lines.subList(idx, lines.size))
            } else {
                null
            }
        }
    }
}

private fun cleanMermaidText(s: String): String {
    var t = s.trim()
    if ((t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) ||
        (t.startsWith("'") && t.endsWith("'") && t.length >= 2)
    ) {
        t = t.substring(1, t.length - 1)
    }
    return t
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace("\\n", "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()
}

private fun parseFlowchart(dir: FlowDir, rawLines: List<String>): MermaidDiagram.Flowchart? {
    val nodeMap = LinkedHashMap<String, FlowNode>()
    val edges = ArrayList<FlowEdge>()
    val subgraphs = ArrayList<Subgraph>()
    val subStack = ArrayDeque<Subgraph>()

    fun ensureNode(rawToken: String): FlowNode? {
        val tok = rawToken.trim().trimEnd(';')
        if (tok.isEmpty()) return null
        val parsed = parseNodeSpec(tok, subStack.lastOrNull()?.id) ?: return null
        val existing = nodeMap[parsed.id]
        val merged = if (existing == null) parsed else FlowNode(
            id = parsed.id,
            label = if (parsed.label != parsed.id) parsed.label else existing.label,
            shape = if (parsed.shape != NodeShape.RECT || parsed.label != parsed.id) parsed.shape else existing.shape,
            subgraphId = existing.subgraphId ?: parsed.subgraphId,
        )
        nodeMap[parsed.id] = merged
        val curSub = subStack.lastOrNull()
        if (curSub != null && parsed.id !in curSub.nodeIds) curSub.nodeIds.add(parsed.id)
        return merged
    }

    // Split semicolon-separated statements on a single line cleanly outside quotes/brackets
    val stmts = rawLines.flatMap { splitStatements(it) }
    for (stmt in stmts) {
        val s = stmt.trim()
        if (s.isEmpty() || s.startsWith("classDef ") || s.startsWith("class ") ||
            s.startsWith("style ") || s.startsWith("linkStyle ") || s.startsWith("click ") ||
            s.startsWith("direction ")
        ) continue

        if (s.startsWith("subgraph ", ignoreCase = true)) {
            val rest = s.substring(9).trim()
            val (sgId, sgTitle) = parseSubgraphHeader(rest, subgraphs.size)
            val sg = Subgraph(sgId, sgTitle)
            subgraphs.add(sg)
            subStack.addLast(sg)
            continue
        }
        if (s.equals("end", ignoreCase = true)) {
            if (subStack.isNotEmpty()) subStack.removeLast()
            continue
        }

        // Check if statement contains edge operators outside quotes/brackets
        val chain = splitEdgeChain(s)
        if (chain.size > 1) {
            for (i in 0 until chain.size - 1) {
                val seg = chain[i]
                val next = chain[i + 1]
                val leftTokens = splitAmpersand(seg.nodeExpr)
                val rightTokens = splitAmpersand(next.nodeExpr)
                val leftNodes = leftTokens.mapNotNull { ensureNode(it) }
                val rightNodes = rightTokens.mapNotNull { ensureNode(it) }
                for (ln in leftNodes) {
                    for (rn in rightNodes) {
                        edges.add(FlowEdge(ln.id, rn.id, next.edgeLabel, next.style, next.headEnd, next.headStart))
                    }
                }
            }
        } else {
            // Standalone node declaration or `A & B`
            for (tok in splitAmpersand(s)) {
                ensureNode(tok)
            }
        }
    }
    if (nodeMap.isEmpty()) return null
    return MermaidDiagram.Flowchart(dir, nodeMap.values.toList(), edges, subgraphs)
}

private fun parseSubgraphHeader(rest: String, index: Int): Pair<String, String> {
    val openBracket = rest.indexOf('[')
    if (openBracket > 0 && rest.endsWith("]")) {
        val id = rest.substring(0, openBracket).trim()
        val title = cleanMermaidText(rest.substring(openBracket + 1, rest.length - 1))
        return id to title.ifEmpty { id }
    }
    val cleaned = cleanMermaidText(rest)
    val id = cleaned.ifEmpty { "subgraph_$index" }
    return id to id
}

private data class ChainLink(
    val nodeExpr: String,
    val style: EdgeStyle = EdgeStyle.SOLID,
    val headStart: ArrowHead = ArrowHead.NONE,
    val headEnd: ArrowHead = ArrowHead.ARROW,
    val edgeLabel: String = "",
)

private val EDGE_REGEX = Regex(
    """(?<op><--+>|<-+\.-+->|<==+>|--+>|--+o|--+x|---+|\.-+\.->|\.-+->|\.-+\.-|==+>|===+|--+\s+[^-|>\n]+\s+--+>|--+\s+[^-|>\n]+\s+---+|-\.\s+[^.\n]+\s+\.->|==\s+[^=\n]+\s+==>)(?:\|(?<pipe>[^|]*)\|)?"""
)

private fun splitEdgeChain(stmt: String): List<ChainLink> {
    // Find edge operators only at bracket/quote depth 0
    val ops = ArrayList<Triple<IntRange, EdgeStyle, String>>()
    val headStarts = ArrayList<ArrowHead>()
    val headEnds = ArrayList<ArrowHead>()
    val matches = EDGE_REGEX.findAll(stmt).toList()
    for (m in matches) {
        if (!isAtDepthZero(stmt, m.range.first)) continue
        val opStr = m.groupValues.getOrNull(1) ?: continue
        val pipeLabel = cleanMermaidText(m.groupValues.getOrNull(2) ?: "")
        val style = when {
            opStr.contains('=') -> EdgeStyle.THICK
            opStr.contains('.') -> EdgeStyle.DOTTED
            else -> EdgeStyle.SOLID
        }
        val hStart = if (opStr.startsWith("<")) ArrowHead.ARROW else ArrowHead.NONE
        val hEnd = when {
            opStr.endsWith(">") -> ArrowHead.ARROW
            opStr.endsWith("o") -> ArrowHead.CIRCLE
            opStr.endsWith("x") -> ArrowHead.CROSS
            else -> ArrowHead.NONE
        }
        var inlineLabel = pipeLabel
        if (inlineLabel.isEmpty()) {
            // Check `-- text -->`, `-. text .->`, `== text ==>`
            val stripped = opStr.trimStart('<', '-', '.', '=').trimEnd('>', '-', '.', '=', 'o', 'x').trim()
            if (stripped.isNotEmpty() && !stripped.all { it == '-' || it == '.' || it == '=' }) {
                inlineLabel = cleanMermaidText(stripped)
            }
        }
        ops.add(Triple(m.range, style, inlineLabel))
        headStarts.add(hStart)
        headEnds.add(hEnd)
    }
    if (ops.isEmpty()) return listOf(ChainLink(stmt))

    val out = ArrayList<ChainLink>()
    out.add(ChainLink(stmt.substring(0, ops[0].first.first).trim()))
    for (i in ops.indices) {
        val start = ops[i].first.last + 1
        val end = if (i + 1 < ops.size) ops[i + 1].first.first else stmt.length
        val nodeExpr = stmt.substring(start, end).trim()
        out.add(ChainLink(nodeExpr, ops[i].second, headStarts[i], headEnds[i], ops[i].third))
    }
    return out
}

private fun isAtDepthZero(s: String, index: Int): Boolean {
    var paren = 0
    var bracket = 0
    var brace = 0
    var quote: Char? = null
    for (i in 0 until index) {
        val c = s[i]
        if (quote != null) {
            if (c == quote) quote = null
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '(' -> paren++
            ')' -> if (paren > 0) paren--
            '[' -> bracket++
            ']' -> if (bracket > 0) bracket--
            '{' -> brace++
            '}' -> if (brace > 0) brace--
        }
    }
    return quote == null && paren == 0 && bracket == 0 && brace == 0
}

private fun splitStatements(line: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var paren = 0; var bracket = 0; var brace = 0; var quote: Char? = null
    for (c in line) {
        if (quote != null) {
            cur.append(c)
            if (c == quote) quote = null
            continue
        }
        when (c) {
            '"', '\'' -> { quote = c; cur.append(c) }
            '(' -> { paren++; cur.append(c) }
            ')' -> { if (paren > 0) paren--; cur.append(c) }
            '[' -> { bracket++; cur.append(c) }
            ']' -> { if (bracket > 0) bracket--; cur.append(c) }
            '{' -> { brace++; cur.append(c) }
            '}' -> { if (brace > 0) brace--; cur.append(c) }
            ';' -> if (paren == 0 && bracket == 0 && brace == 0) {
                out.add(cur.toString())
                cur.setLength(0)
            } else cur.append(c)
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty()) out.add(cur.toString())
    return out
}

private fun splitAmpersand(expr: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var paren = 0; var bracket = 0; var brace = 0; var quote: Char? = null
    for (c in expr) {
        if (quote != null) {
            cur.append(c)
            if (c == quote) quote = null
            continue
        }
        when (c) {
            '"', '\'' -> { quote = c; cur.append(c) }
            '(' -> { paren++; cur.append(c) }
            ')' -> { if (paren > 0) paren--; cur.append(c) }
            '[' -> { bracket++; cur.append(c) }
            ']' -> { if (bracket > 0) bracket--; cur.append(c) }
            '{' -> { brace++; cur.append(c) }
            '}' -> { if (brace > 0) brace--; cur.append(c) }
            '&' -> if (paren == 0 && bracket == 0 && brace == 0) {
                out.add(cur.toString().trim())
                cur.setLength(0)
            } else cur.append(c)
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty()) out.add(cur.toString().trim())
    return out
}

private fun parseNodeSpec(token: String, subgraphId: String?): FlowNode? {
    var t = token.trim()
    // Strip :::className suffix
    val classIdx = t.indexOf(":::")
    if (classIdx > 0) t = t.substring(0, classIdx).trim()
    if (t.isEmpty()) return null

    // Find first shape opener outside quotes
    val openers = setOf('[', '(', '{')
    val firstOpen = t.indexOfFirst { it in openers }
    if (firstOpen <= 0) {
        val cleanId = t.takeWhile { !it.isWhitespace() }
        return if (cleanId.isEmpty()) null else FlowNode(cleanId, cleanId, NodeShape.RECT, subgraphId)
    }
    val id = t.substring(0, firstOpen).trim()
    val shapePart = t.substring(firstOpen).trim()
    val (shape, rawLabel) = when {
        shapePart.startsWith("([") && shapePart.endsWith("])") -> NodeShape.STADIUM to shapePart.drop(2).dropLast(2)
        shapePart.startsWith("((") && shapePart.endsWith("))") -> NodeShape.CIRCLE to shapePart.drop(2).dropLast(2)
        shapePart.startsWith("[[") && shapePart.endsWith("]]") -> NodeShape.SUBROUTINE to shapePart.drop(2).dropLast(2)
        shapePart.startsWith("[(") && shapePart.endsWith(")]") -> NodeShape.DATABASE to shapePart.drop(2).dropLast(2)
        shapePart.startsWith("{{") && shapePart.endsWith("}}") -> NodeShape.HEXAGON to shapePart.drop(2).dropLast(2)
        shapePart.startsWith("(") && shapePart.endsWith(")") -> NodeShape.ROUND to shapePart.drop(1).dropLast(1)
        shapePart.startsWith("[") && shapePart.endsWith("]") -> NodeShape.RECT to shapePart.drop(1).dropLast(1)
        shapePart.startsWith("{") && shapePart.endsWith("}") -> NodeShape.DIAMOND to shapePart.drop(1).dropLast(1)
        else -> NodeShape.RECT to shapePart
    }
    val label = cleanMermaidText(rawLabel).ifEmpty { id }
    return FlowNode(id, label, shape, subgraphId)
}

private fun parseSequence(lines: List<String>): MermaidDiagram.Sequence? {
    val participants = LinkedHashMap<String, SeqParticipant>()
    val steps = ArrayList<SeqStep>()
    val blockStack = ArrayDeque<Pair<String, Int>>()

    fun ensureActor(idRaw: String): String {
        val id = idRaw.trim()
        if (id !in participants) participants[id] = SeqParticipant(id, id)
        return id
    }

    for (rawLine in lines) {
        val line = rawLine.trim().trimEnd(';')
        if (line.isEmpty() || line.startsWith("autonumber", true) || line.startsWith("title", true)) continue
        when {
            line.startsWith("participant ", true) || line.startsWith("actor ", true) -> {
                val rest = line.substringAfter(' ').trim()
                val asMatch = Regex("""^(.+?)\s+as\s+(.+)$""", RegexOption.IGNORE_CASE).find(rest)
                if (asMatch != null) {
                    val id = asMatch.groupValues[1].trim()
                    val label = cleanMermaidText(asMatch.groupValues[2])
                    participants[id] = SeqParticipant(id, label)
                } else {
                    ensureActor(rest)
                }
            }
            line.startsWith("note ", true) -> {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val targetPart = line.substring(5, colon).trim()
                    val text = cleanMermaidText(line.substring(colon + 1))
                    val actorNames = targetPart
                        .replace(Regex("^(over|left of|right of)\\s+", RegexOption.IGNORE_CASE), "")
                        .split(',')
                        .map { ensureActor(it) }
                    steps.add(SeqStep.Note(actorNames, text))
                }
            }
            line.startsWith("loop ", true) || line.startsWith("alt ", true) ||
                line.startsWith("opt ", true) || line.startsWith("par ", true) -> {
                blockStack.addLast(line to steps.size)
            }
            line.equals("end", true) -> {
                val popped = if (blockStack.isNotEmpty()) blockStack.removeLast() else null
                if (popped != null && steps.size > popped.second) {
                    steps.add(SeqStep.Block(popped.first, popped.second, steps.size - 1))
                }
            }
            line.startsWith("activate ", true) || line.startsWith("deactivate ", true) ||
                line.startsWith("else", true) -> Unit
            else -> {
                val m = Regex("""^([^-\s<]+)\s*(--?>>|--?>|<<?--?)\s*([+-]?)([^:\s]+)\s*:\s*(.*)$""").find(line)
                if (m != null) {
                    val from = ensureActor(m.groupValues[1])
                    val arrow = m.groupValues[2]
                    val to = ensureActor(m.groupValues[4])
                    val msg = cleanMermaidText(m.groupValues[5])
                    steps.add(SeqStep.Message(from, to, msg, dashed = arrow.startsWith("--")))
                }
            }
        }
    }
    if (participants.isEmpty()) return null
    return MermaidDiagram.Sequence(participants.values.toList(), steps)
}

private fun parsePie(header: String, lines: List<String>): MermaidDiagram.Pie? {
    var title: String? = null
    val titleMatch = Regex("""title\s+(.+)$""", RegexOption.IGNORE_CASE).find(header)
    if (titleMatch != null) title = cleanMermaidText(titleMatch.groupValues[1])
    val slices = ArrayList<PieSlice>()
    for (l in lines) {
        if (l.startsWith("title ", true)) {
            title = cleanMermaidText(l.substring(6))
            continue
        }
        val m = Regex("""^["']?([^"':]+)["']?\s*:\s*([0-9]+(?:\.[0-9]+)?)$""").find(l) ?: continue
        val v = m.groupValues[2].toFloatOrNull() ?: continue
        if (v > 0f) slices.add(PieSlice(cleanMermaidText(m.groupValues[1]), v))
    }
    if (slices.isEmpty()) return null
    return MermaidDiagram.Pie(title, slices)
}

private fun parseStateAsFlow(lines: List<String>): MermaidDiagram.Flowchart? {
    val nodeMap = LinkedHashMap<String, FlowNode>()
    val edges = ArrayList<FlowEdge>()
    var starCount = 0
    fun resolveState(tok: String, isSource: Boolean): String {
        val t = tok.trim()
        if (t == "[*]") {
            val id = if (isSource) "__start_${starCount++}" else "__end_${starCount++}"
            nodeMap[id] = FlowNode(id, "", if (isSource) NodeShape.START_DOT else NodeShape.END_DOT)
            return id
        }
        if (t !in nodeMap) nodeMap[t] = FlowNode(t, t, NodeShape.ROUND)
        return t
    }
    for (raw in lines) {
        val l = raw.trim()
        if (l.startsWith("state ", true) && l.contains(" as ", true)) {
            val m = Regex("""^state\s+"([^"]+)"\s+as\s+(\S+)""", RegexOption.IGNORE_CASE).find(l)
            if (m != null) {
                nodeMap[m.groupValues[2]] = FlowNode(m.groupValues[2], cleanMermaidText(m.groupValues[1]), NodeShape.ROUND)
            }
            continue
        }
        val m = Regex("""^(.+?)\s*-->\s*([^:]+?)(?::\s*(.+))?$""").find(l)
        if (m != null) {
            val f = resolveState(m.groupValues[1], isSource = true)
            val to = resolveState(m.groupValues[2], isSource = false)
            edges.add(FlowEdge(f, to, cleanMermaidText(m.groupValues[3])))
        }
    }
    if (nodeMap.isEmpty()) return null
    return MermaidDiagram.Flowchart(FlowDir.TD, nodeMap.values.toList(), edges, emptyList())
}

private fun parseClassAsFlow(lines: List<String>): MermaidDiagram.Flowchart? {
    val nodes = LinkedHashMap<String, FlowNode>()
    val edges = ArrayList<FlowEdge>()
    var currentClass: String? = null
    val memberBuf = HashMap<String, MutableList<String>>()
    for (raw in lines) {
        val l = raw.trim()
        if (currentClass != null) {
            if (l == "}") currentClass = null
            else if (l.isNotEmpty()) memberBuf.getOrPut(currentClass) { ArrayList() }.add(l)
            continue
        }
        val cMatch = Regex("""^class\s+(\S+)\s*\{?$""").find(l)
        if (cMatch != null) {
            val cname = cMatch.groupValues[1]
            if (!nodes.containsKey(cname)) nodes[cname] = FlowNode(cname, cname, NodeShape.RECT)
            if (l.endsWith("{")) currentClass = cname
            continue
        }
        val rel = Regex("""^(\S+)\s*([<|*.o-]+--[>|*.o-]+|[<|*.o-]+)\s*(\S+?)(?:\s*:\s*(.+))?$""").find(l)
        if (rel != null) {
            val a = rel.groupValues[1]; val b = rel.groupValues[3]
            if (!nodes.containsKey(a)) nodes[a] = FlowNode(a, a, NodeShape.RECT)
            if (!nodes.containsKey(b)) nodes[b] = FlowNode(b, b, NodeShape.RECT)
            val lbl = cleanMermaidText(rel.groupValues[4])
            val op = rel.groupValues[2]
            edges.add(FlowEdge(a, b, lbl, if (op.contains('.')) EdgeStyle.DOTTED else EdgeStyle.SOLID))
        }
    }
    memberBuf.forEach { (id, members) ->
        val txt = (listOf(id) + members.take(6)).joinToString("\n")
        nodes[id] = FlowNode(id, txt, NodeShape.RECT)
    }
    if (nodes.isEmpty()) return null
    return MermaidDiagram.Flowchart(FlowDir.TD, nodes.values.toList(), edges, emptyList())
}

private fun parseErAsFlow(lines: List<String>): MermaidDiagram.Flowchart? {
    val nodes = LinkedHashMap<String, FlowNode>()
    val edges = ArrayList<FlowEdge>()
    for (raw in lines) {
        val m = Regex("""^(\S+)\s+([|o}{]+--[|o}{]+)\s+(\S+)\s*:\s*(.+)$""").find(raw.trim()) ?: continue
        val a = m.groupValues[1]; val b = m.groupValues[3]
        if (!nodes.containsKey(a)) nodes[a] = FlowNode(a, a, NodeShape.RECT)
        if (!nodes.containsKey(b)) nodes[b] = FlowNode(b, b, NodeShape.RECT)
        edges.add(FlowEdge(a, b, cleanMermaidText(m.groupValues[4])))
    }
    if (nodes.isEmpty()) return null
    return MermaidDiagram.Flowchart(FlowDir.TD, nodes.values.toList(), edges, emptyList())
}

// =============================================================================
// 2. Layered Vector Layout & Compose Canvas Renderer
// =============================================================================

private data class MeasuredMermaidDiagram(
    val widthPx: Float,
    val heightPx: Float,
    val draw: DrawScope.() -> Unit,
)

private fun layoutMermaidDiagram(
    diagram: MermaidDiagram,
    palette: MermaidPalette,
    measurer: TextMeasurer,
    density: Density,
): MeasuredMermaidDiagram = when (diagram) {
    is MermaidDiagram.Flowchart -> layoutFlowchart(diagram, palette, measurer, density)
    is MermaidDiagram.Sequence -> layoutSequence(diagram, palette, measurer, density)
    is MermaidDiagram.Pie -> layoutPie(diagram, palette, measurer, density)
}

private fun layoutFlowchart(
    flow: MermaidDiagram.Flowchart,
    p: MermaidPalette,
    measurer: TextMeasurer,
    density: Density,
): MeasuredMermaidDiagram {
    val d = density.density
    val pxPerSp = d * density.fontScale
    val textStyle = TextStyle(color = p.text, fontSize = 14.sp, lineHeight = 18.sp)
    val edgeTextStyle = TextStyle(color = p.text, fontSize = 12.sp, lineHeight = 15.sp)
    val clusterTitleStyle = TextStyle(color = p.clusterText, fontSize = 13.sp, fontWeight = FontWeight.Medium)

    // 1. Measure node boxes
    val textLayouts = HashMap<String, TextLayoutResult>()
    val nodeSize = HashMap<String, Pair<Float, Float>>()
    for (n in flow.nodes) {
        if (n.shape == NodeShape.START_DOT || n.shape == NodeShape.END_DOT) {
            nodeSize[n.id] = 20f * d to 20f * d
            continue
        }
        val tl = measurer.measure(
            text = AnnotatedString(n.label),
            style = textStyle,
            constraints = Constraints(maxWidth = (180f * d).roundToInt()),
        )
        textLayouts[n.id] = tl
        val padH = when (n.shape) {
            NodeShape.DIAMOND -> 28f * d
            NodeShape.CIRCLE -> 20f * d
            NodeShape.STADIUM, NodeShape.HEXAGON -> 22f * d
            else -> 16f * d
        }
        val padV = when (n.shape) {
            NodeShape.DIAMOND -> 22f * d
            NodeShape.CIRCLE -> 20f * d
            else -> 11f * d
        }
        var w = tl.size.width + padH * 2
        var h = tl.size.height + padV * 2
        if (n.shape == NodeShape.CIRCLE) {
            val side = max(w, h)
            w = side; h = side
        } else if (n.shape == NodeShape.DIAMOND) {
            w = max(w, h * 1.25f)
            h = max(h, w * 0.72f)
        }
        nodeSize[n.id] = max(56f * d, w) to max(34f * d, h)
    }

    val edgeLayouts = flow.edges.map { e ->
        if (e.label.isBlank()) null
        else measurer.measure(
            AnnotatedString(e.label),
            style = edgeTextStyle,
            constraints = Constraints(maxWidth = (130f * d).roundToInt()),
        )
    }

    // 2. Topological longest-path layer assignment (cycle-safe DFS)
    val adj = HashMap<String, MutableList<String>>()
    val inDeg = HashMap<String, Int>()
    for (n in flow.nodes) {
        adj[n.id] = ArrayList()
        inDeg[n.id] = 0
    }
    // Break back-edges via DFS order so cycles lay out stably without infinite loops
    val visitState = HashMap<String, Int>() // 0=unvisited, 1=visiting, 2=done
    val dagEdges = ArrayList<FlowEdge>()
    val rawAdj = HashMap<String, MutableList<FlowEdge>>()
    for (e in flow.edges) rawAdj.getOrPut(e.from) { ArrayList() }.add(e)
    fun dfs(u: String) {
        visitState[u] = 1
        for (e in rawAdj[u].orEmpty()) {
            when (visitState[e.to] ?: 0) {
                0 -> { dagEdges.add(e); dfs(e.to) }
                2 -> dagEdges.add(e)
                else -> Unit // back-edge breaks cycle
            }
        }
        visitState[u] = 2
    }
    for (n in flow.nodes) if ((visitState[n.id] ?: 0) == 0) dfs(n.id)

    for (e in dagEdges) {
        adj.getOrPut(e.from) { ArrayList() }.add(e.to)
        inDeg[e.to] = (inDeg[e.to] ?: 0) + 1
    }
    val rank = HashMap<String, Int>()
    val queue = ArrayDeque<String>()
    for (n in flow.nodes) {
        if ((inDeg[n.id] ?: 0) == 0) {
            rank[n.id] = 0
            queue.addLast(n.id)
        }
    }
    while (queue.isNotEmpty()) {
        val u = queue.removeFirst()
        val rU = rank[u] ?: 0
        for (v in adj[u].orEmpty()) {
            rank[v] = max(rank[v] ?: 0, rU + 1)
            val rem = (inDeg[v] ?: 1) - 1
            inDeg[v] = rem
            if (rem <= 0) queue.addLast(v)
        }
    }
    for (n in flow.nodes) if (n.id !in rank) rank[n.id] = 0

    val maxRank = rank.values.maxOrNull() ?: 0
    val layerBuckets = Array(maxRank + 1) { ArrayList<FlowNode>() }
    for (n in flow.nodes) layerBuckets[rank[n.id] ?: 0].add(n)

    // Simple 2-pass barycenter crossing reduction within each layer
    val posInLayer = HashMap<String, Float>()
    fun refreshPositions() {
        for (l in layerBuckets) l.forEachIndexed { idx, node -> posInLayer[node.id] = idx.toFloat() }
    }
    refreshPositions()
    val preds = HashMap<String, MutableList<String>>()
    for (e in dagEdges) preds.getOrPut(e.to) { ArrayList() }.add(e.from)
    repeat(2) {
        for (r in 1..maxRank) {
            layerBuckets[r].sortBy { node ->
                val ps = preds[node.id].orEmpty()
                if (ps.isEmpty()) posInLayer[node.id] ?: 0f
                else ps.mapNotNull { posInLayer[it] }.average().toFloat()
            }
            refreshPositions()
        }
        for (r in maxRank - 1 downTo 0) {
            layerBuckets[r].sortBy { node ->
                val ss = adj[node.id].orEmpty()
                if (ss.isEmpty()) posInLayer[node.id] ?: 0f
                else ss.mapNotNull { posInLayer[it] }.average().toFloat()
            }
            refreshPositions()
        }
    }

    // 3. Coordinate assignment
    val isHorizontal = flow.dir == FlowDir.LR || flow.dir == FlowDir.RL
    val reversed = flow.dir == FlowDir.BT || flow.dir == FlowDir.RL
    val orderedLayers = if (reversed) layerBuckets.reversedArray() else layerBuckets

    val crossGap = 24f * d
    val mainGap = 42f * d
    val edgeLabelExtraGap = 18f * d
    val outerPad = 18f * d
    val hasSubgraphs = flow.subgraphs.isNotEmpty()
    val sgTopPad = if (hasSubgraphs) 28f * d else 0f

    val layerMainSizes = FloatArray(orderedLayers.size) { r ->
        val bucket = orderedLayers[r]
        if (bucket.isEmpty()) 0f
        else bucket.maxOf { n ->
            val (w, h) = nodeSize[n.id]!!
            if (isHorizontal) w else h
        }
    }
    val layerCrossTotals = FloatArray(orderedLayers.size) { r ->
        val bucket = orderedLayers[r]
        if (bucket.isEmpty()) 0f
        else bucket.sumOf { n ->
            val (w, h) = nodeSize[n.id]!!
            (if (isHorizontal) h else w).toDouble()
        }.toFloat() + max(0, bucket.size - 1) * crossGap
    }
    val maxCross = layerCrossTotals.maxOrNull() ?: 0f

    // Determine main-axis spacing per gap (extra room if edges between these layers carry labels)
    val layerOffsets = FloatArray(orderedLayers.size)
    var curMain = outerPad + sgTopPad
    for (r in orderedLayers.indices) {
        layerOffsets[r] = curMain
        val hasLabelsOnGap = flow.edges.withIndex().any { (idx, e) ->
            edgeLayouts[idx] != null && rank[e.from] != rank[e.to]
        }
        curMain += layerMainSizes[r] + mainGap + (if (hasLabelsOnGap) edgeLabelExtraGap else 0f)
    }
    val totalMain = (curMain - mainGap - (if (flow.edges.any { it.label.isNotBlank() }) edgeLabelExtraGap else 0f)) + outerPad
    val totalCross = maxCross + outerPad * 2 + (if (hasSubgraphs) 16f * d else 0f)

    val nodeRects = HashMap<String, Rect>()
    for (r in orderedLayers.indices) {
        val bucket = orderedLayers[r]
        val startCross = (totalCross - layerCrossTotals[r]) * 0.5f
        var curC = startCross
        for (n in bucket) {
            val (w, h) = nodeSize[n.id]!!
            val rect = if (isHorizontal) {
                val x = layerOffsets[r] + (layerMainSizes[r] - w) * 0.5f
                val y = curC
                curC += h + crossGap
                Rect(x, y, x + w, y + h)
            } else {
                val x = curC
                val y = layerOffsets[r] + (layerMainSizes[r] - h) * 0.5f
                curC += w + crossGap
                Rect(x, y, x + w, y + h)
            }
            nodeRects[n.id] = rect
        }
    }

    val canvasW = if (isHorizontal) totalMain else totalCross
    val canvasH = if (isHorizontal) totalCross else totalMain

    // Precompute subgraph bounding boxes
    val sgRects = flow.subgraphs.mapNotNull { sg ->
        val rects = sg.nodeIds.mapNotNull { nodeRects[it] }
        if (rects.isEmpty()) null else {
            val l = rects.minOf { it.left } - 12f * d
            val t = rects.minOf { it.top } - 28f * d
            val r = rects.maxOf { it.right } + 12f * d
            val b = rects.maxOf { it.bottom } + 12f * d
            val tl = measurer.measure(AnnotatedString(sg.title), style = clusterTitleStyle)
            val minR = max(r, l + tl.size.width + 24f * d)
            Triple(Rect(l, t, minR, b), tl, sg)
        }
    }

    return MeasuredMermaidDiagram(
        widthPx = canvasW + (if (hasSubgraphs) 12f * d else 0f),
        heightPx = canvasH,
    ) {
        // 1. Subgraph containers
        for ((rect, tl, _) in sgRects) {
            drawRoundRect(
                color = p.clusterFill,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(6f * d, 6f * d),
            )
            drawRoundRect(
                color = p.clusterStroke,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(6f * d, 6f * d),
                style = Stroke(width = 1.1f * d),
            )
            drawText(tl, topLeft = Offset(rect.left + (rect.width - tl.size.width) * 0.5f, rect.top + 6f * d))
        }

        // 2. Edges & labels
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        flow.edges.forEachIndexed { idx, edge ->
            val fromR = nodeRects[edge.from] ?: return@forEachIndexed
            val toR = nodeRects[edge.to] ?: return@forEachIndexed
            val (p1, p2, ctrl1, ctrl2, labelPos) = computeEdgeAnchors(fromR, toR, isHorizontal, d, edge.from == edge.to)
            val strokeW = when (edge.style) {
                EdgeStyle.THICK -> 2.6f * d
                else -> 1.4f * d
            }
            val effect = if (edge.style == EdgeStyle.DOTTED) dashEffect else null
            if (edge.from == edge.to) {
                val loopPath = Path().apply {
                    moveTo(p1.x, p1.y)
                    cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, p2.x, p2.y)
                }
                drawPath(loopPath, color = p.edge, style = Stroke(width = strokeW, pathEffect = effect, cap = StrokeCap.Round))
            } else {
                val edgePath = Path().apply {
                    moveTo(p1.x, p1.y)
                    cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, p2.x, p2.y)
                }
                drawPath(edgePath, color = p.edge, style = Stroke(width = strokeW, pathEffect = effect, cap = StrokeCap.Round))
            }

            if (edge.headEnd == ArrowHead.ARROW) {
                drawArrowHead(tip = p2, tailDir = ctrl2, color = p.edge, sizePx = 7.5f * d)
            } else if (edge.headEnd == ArrowHead.CIRCLE) {
                drawCircle(p.edge, radius = 3.5f * d, center = p2, style = Stroke(width = 1.4f * d))
            }
            if (edge.headStart == ArrowHead.ARROW) {
                drawArrowHead(tip = p1, tailDir = ctrl1, color = p.edge, sizePx = 7.5f * d)
            }

            val tl = edgeLayouts[idx]
            if (tl != null) {
                val lw = tl.size.width + 10f * d
                val lh = tl.size.height + 4f * d
                drawRoundRect(
                    color = p.edgeLabelBg,
                    topLeft = Offset(labelPos.x - lw * 0.5f, labelPos.y - lh * 0.5f),
                    size = Size(lw, lh),
                    cornerRadius = CornerRadius(4f * d, 4f * d),
                )
                drawText(
                    tl,
                    topLeft = Offset(labelPos.x - tl.size.width * 0.5f, labelPos.y - tl.size.height * 0.5f),
                )
            }
        }

        // 3. Nodes & Labels
        for (n in flow.nodes) {
            val rect = nodeRects[n.id] ?: continue
            drawFlowNodeShape(n.shape, rect, p, d)
            textLayouts[n.id]?.let { tl ->
                drawText(
                    textLayoutResult = tl,
                    topLeft = Offset(
                        rect.center.x - tl.size.width * 0.5f,
                        rect.center.y - tl.size.height * 0.5f,
                    ),
                )
            }
        }
    }
}

private data class EdgeCurve(
    val p1: Offset,
    val p2: Offset,
    val ctrl1: Offset,
    val ctrl2: Offset,
    val labelCenter: Offset,
)

private fun computeEdgeAnchors(
    from: Rect,
    to: Rect,
    isHorizontal: Boolean,
    d: Float,
    selfLoop: Boolean,
): EdgeCurve {
    if (selfLoop) {
        val p1 = Offset(from.right, from.center.y - 6f * d)
        val p2 = Offset(from.right, from.center.y + 6f * d)
        val ctrl1 = Offset(from.right + 36f * d, from.top - 10f * d)
        val ctrl2 = Offset(from.right + 36f * d, from.bottom + 10f * d)
        return EdgeCurve(p1, p2, ctrl1, ctrl2, Offset(from.right + 28f * d, from.center.y))
    }
    if (isHorizontal) {
        val forward = to.center.x >= from.center.x
        val p1 = if (forward) Offset(from.right, from.center.y) else Offset(from.left, from.center.y)
        val p2 = if (forward) Offset(to.left, to.center.y) else Offset(to.right, to.center.y)
        val dx = (p2.x - p1.x) * 0.45f
        val ctrl1 = Offset(p1.x + dx, p1.y)
        val ctrl2 = Offset(p2.x - dx, p2.y)
        val mid = cubicPoint(p1, ctrl1, ctrl2, p2, 0.5f)
        return EdgeCurve(p1, p2, ctrl1, ctrl2, mid)
    } else {
        val forward = to.center.y >= from.center.y
        val p1 = if (forward) Offset(from.center.x, from.bottom) else Offset(from.center.x, from.top)
        val p2 = if (forward) Offset(to.center.x, to.top) else Offset(to.center.x, to.bottom)
        val dy = (p2.y - p1.y) * 0.45f
        val ctrl1 = Offset(p1.x, p1.y + dy)
        val ctrl2 = Offset(p2.x, p2.y - dy)
        val mid = cubicPoint(p1, ctrl1, ctrl2, p2, 0.5f)
        return EdgeCurve(p1, p2, ctrl1, ctrl2, mid)
    }
}

private fun cubicPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    return Offset(
        uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x,
        uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y,
    )
}

private fun DrawScope.drawArrowHead(tip: Offset, tailDir: Offset, color: Color, sizePx: Float) {
    val angle = atan2((tip.y - tailDir.y).toDouble(), (tip.x - tailDir.x).toDouble())
    val spread = 0.48
    val pA = Offset(
        (tip.x - sizePx * cos(angle - spread)).toFloat(),
        (tip.y - sizePx * sin(angle - spread)).toFloat(),
    )
    val pB = Offset(
        (tip.x - sizePx * cos(angle + spread)).toFloat(),
        (tip.y - sizePx * sin(angle + spread)).toFloat(),
    )
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(pA.x, pA.y)
        lineTo(pB.x, pB.y)
        close()
    }
    drawPath(path, color = color, style = Fill)
}

private fun DrawScope.drawFlowNodeShape(shape: NodeShape, r: Rect, p: MermaidPalette, d: Float) {
    val strokeW = 1.2f * d
    when (shape) {
        NodeShape.START_DOT -> {
            drawCircle(p.startEndDot, radius = min(r.width, r.height) * 0.42f, center = r.center)
        }
        NodeShape.END_DOT -> {
            val rad = min(r.width, r.height) * 0.46f
            drawCircle(p.startEndDot, radius = rad, center = r.center, style = Stroke(width = 1.5f * d))
            drawCircle(p.startEndDot, radius = rad * 0.6f, center = r.center)
        }
        NodeShape.RECT, NodeShape.SUBROUTINE -> {
            drawRect(p.nodeFill, topLeft = r.topLeft, size = r.size)
            drawRect(p.nodeStroke, topLeft = r.topLeft, size = r.size, style = Stroke(width = strokeW))
            if (shape == NodeShape.SUBROUTINE) {
                val inset = 6f * d
                drawLine(p.nodeStroke, Offset(r.left + inset, r.top), Offset(r.left + inset, r.bottom), strokeWidth = strokeW)
                drawLine(p.nodeStroke, Offset(r.right - inset, r.top), Offset(r.right - inset, r.bottom), strokeWidth = strokeW)
            }
        }
        NodeShape.ROUND, NodeShape.STADIUM -> {
            val rad = if (shape == NodeShape.STADIUM) r.height * 0.5f else 6f * d
            drawRoundRect(p.nodeFill, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(rad, rad))
            drawRoundRect(p.nodeStroke, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(rad, rad), style = Stroke(width = strokeW))
        }
        NodeShape.CIRCLE -> {
            val rad = min(r.width, r.height) * 0.5f
            drawCircle(p.nodeFill, radius = rad, center = r.center)
            drawCircle(p.nodeStroke, radius = rad, center = r.center, style = Stroke(width = strokeW))
        }
        NodeShape.DIAMOND -> {
            val path = Path().apply {
                moveTo(r.center.x, r.top)
                lineTo(r.right, r.center.y)
                lineTo(r.center.x, r.bottom)
                lineTo(r.left, r.center.y)
                close()
            }
            drawPath(path, p.nodeFill, style = Fill)
            drawPath(path, p.nodeStroke, style = Stroke(width = strokeW, join = StrokeJoin.Miter))
        }
        NodeShape.HEXAGON -> {
            val dx = min(r.width * 0.16f, 14f * d)
            val path = Path().apply {
                moveTo(r.left + dx, r.top)
                lineTo(r.right - dx, r.top)
                lineTo(r.right, r.center.y)
                lineTo(r.right - dx, r.bottom)
                lineTo(r.left + dx, r.bottom)
                lineTo(r.left, r.center.y)
                close()
            }
            drawPath(path, p.nodeFill, style = Fill)
            drawPath(path, p.nodeStroke, style = Stroke(width = strokeW))
        }
        NodeShape.DATABASE -> {
            val ry = min(r.height * 0.16f, 8f * d)
            drawRoundRect(p.nodeFill, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(r.width * 0.5f, ry))
            drawRoundRect(p.nodeStroke, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(r.width * 0.5f, ry), style = Stroke(width = strokeW))
            drawOval(p.nodeFill, topLeft = r.topLeft, size = Size(r.width, ry * 2f))
            drawOval(p.nodeStroke, topLeft = r.topLeft, size = Size(r.width, ry * 2f), style = Stroke(width = strokeW))
        }
    }
}

private fun layoutSequence(
    seq: MermaidDiagram.Sequence,
    p: MermaidPalette,
    measurer: TextMeasurer,
    density: Density,
): MeasuredMermaidDiagram {
    val d = density.density
    val actorStyle = TextStyle(color = p.text, fontSize = 13.5f.sp, fontWeight = FontWeight.Medium)
    val msgStyle = TextStyle(color = p.text, fontSize = 12.5f.sp)
    val pad = 16f * d
    val actorLayouts = seq.participants.map {
        measurer.measure(AnnotatedString(it.label), style = actorStyle, constraints = Constraints(maxWidth = (120f * d).roundToInt()))
    }
    val actorWidths = actorLayouts.map { max(68f * d, it.size.width + 24f * d) }
    val actorBoxH = max(36f * d, (actorLayouts.maxOfOrNull { it.size.height } ?: 0) + 16f * d)
    val actorGap = 24f * d
    val actorCentersX = HashMap<String, Float>()
    var cx = pad
    seq.participants.forEachIndexed { idx, part ->
        val w = actorWidths[idx]
        actorCentersX[part.id] = cx + w * 0.5f
        cx += w + actorGap
    }
    val totalW = cx - actorGap + pad

    val stepSpacing = 44f * d
    val msgCount = seq.steps.count { it !is SeqStep.Block }.coerceAtLeast(1)
    val firstStepY = pad + actorBoxH + 26f * d
    val bottomActorsY = firstStepY + msgCount * stepSpacing + 12f * d
    val totalH = bottomActorsY + actorBoxH + pad

    val stepLayouts = seq.steps.map { step ->
        when (step) {
            is SeqStep.Message -> measurer.measure(AnnotatedString(step.text), style = msgStyle)
            is SeqStep.Note -> measurer.measure(AnnotatedString(step.text), style = msgStyle, constraints = Constraints(maxWidth = (140f * d).roundToInt()))
            is SeqStep.Block -> measurer.measure(AnnotatedString(step.label), style = msgStyle)
        }
    }

    return MeasuredMermaidDiagram(totalW, totalH) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
        // Lifelines
        seq.participants.forEach { part ->
            val x = actorCentersX[part.id] ?: return@forEach
            drawLine(
                color = p.actorLine,
                start = Offset(x, pad + actorBoxH),
                end = Offset(x, bottomActorsY),
                strokeWidth = 1.2f * d,
            )
        }
        // Top & Bottom Actor boxes
        seq.participants.forEachIndexed { idx, part ->
            val xCenter = actorCentersX[part.id] ?: return@forEachIndexed
            val w = actorWidths[idx]
            val tl = actorLayouts[idx]
            for (topY in listOf(pad, bottomActorsY)) {
                val rect = Rect(xCenter - w * 0.5f, topY, xCenter + w * 0.5f, topY + actorBoxH)
                drawRoundRect(p.nodeFill, rect.topLeft, rect.size, CornerRadius(4f * d, 4f * d))
                drawRoundRect(p.nodeStroke, rect.topLeft, rect.size, CornerRadius(4f * d, 4f * d), style = Stroke(1.2f * d))
                drawText(tl, topLeft = Offset(xCenter - tl.size.width * 0.5f, topY + (actorBoxH - tl.size.height) * 0.5f))
            }
        }
        // Steps
        var row = 0
        seq.steps.forEachIndexed { idx, step ->
            when (step) {
                is SeqStep.Message -> {
                    val y = firstStepY + row * stepSpacing
                    row++
                    val x1 = actorCentersX[step.from] ?: pad
                    val x2 = actorCentersX[step.to] ?: pad
                    val tl = stepLayouts[idx]
                    if (step.from == step.to) {
                        val loop = Path().apply {
                            moveTo(x1, y)
                            lineTo(x1 + 32f * d, y)
                            lineTo(x1 + 32f * d, y + 20f * d)
                            lineTo(x1, y + 20f * d)
                        }
                        drawPath(loop, p.edge, style = Stroke(1.4f * d, pathEffect = if (step.dashed) dash else null))
                        drawArrowHead(Offset(x1, y + 20f * d), Offset(x1 + 20f * d, y + 20f * d), p.edge, 7f * d)
                        drawText(tl, topLeft = Offset(x1 + 36f * d, y + 2f * d))
                    } else {
                        drawLine(
                            color = p.edge,
                            start = Offset(x1, y),
                            end = Offset(x2, y),
                            strokeWidth = 1.4f * d,
                            pathEffect = if (step.dashed) dash else null,
                        )
                        drawArrowHead(Offset(x2, y), Offset(x1, y), p.edge, 7.5f * d)
                        val midX = (x1 + x2) * 0.5f
                        drawText(tl, topLeft = Offset(midX - tl.size.width * 0.5f, y - tl.size.height - 4f * d))
                    }
                }
                is SeqStep.Note -> {
                    val y = firstStepY + row * stepSpacing
                    row++
                    val tl = stepLayouts[idx]
                    val cxNote = step.actors.mapNotNull { actorCentersX[it] }.average().toFloat()
                    val nw = max(72f * d, tl.size.width + 18f * d)
                    val nh = tl.size.height + 12f * d
                    val rect = Rect(cxNote - nw * 0.5f, y - nh * 0.5f, cxNote + nw * 0.5f, y + nh * 0.5f)
                    drawRect(p.noteFill, rect.topLeft, rect.size)
                    drawRect(p.noteStroke, rect.topLeft, rect.size, style = Stroke(1f * d))
                    drawText(tl, topLeft = Offset(cxNote - tl.size.width * 0.5f, rect.top + 6f * d))
                }
                is SeqStep.Block -> Unit
            }
        }
    }
}

private fun layoutPie(
    pie: MermaidDiagram.Pie,
    p: MermaidPalette,
    measurer: TextMeasurer,
    density: Density,
): MeasuredMermaidDiagram {
    val d = density.density
    val colors = listOf(
        Color(0xFFECECFF),
        Color(0xFFFFFFDE),
        Color(0xFFE5F6FF),
        Color(0xFFFFE4E1),
        Color(0xFFE8F5E9),
        Color(0xFFF3E5F5),
    )
    val total = pie.slices.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
    val titleLayout = pie.title?.let {
        measurer.measure(AnnotatedString(it), TextStyle(color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
    val legendLayouts = pie.slices.map { s ->
        val pct = ((s.value / total) * 100f).roundToInt()
        measurer.measure(AnnotatedString("${s.label} ($pct%)"), TextStyle(color = p.text, fontSize = 12.5f.sp))
    }
    val pad = 16f * d
    val radius = 78f * d
    val titleH = titleLayout?.let { it.size.height + 14f * d } ?: 0f
    val legendW = (legendLayouts.maxOfOrNull { it.size.width } ?: 0) + 28f * d
    val legendH = legendLayouts.sumOf { it.size.height + (8f * d).roundToInt() }.toFloat()
    val bodyH = max(radius * 2f, legendH)
    val totalW = pad * 2 + radius * 2 + 24f * d + legendW
    val totalH = pad * 2 + titleH + bodyH

    return MeasuredMermaidDiagram(totalW, totalH) {
        titleLayout?.let { tl ->
            drawText(tl, topLeft = Offset((totalW - tl.size.width) * 0.5f, pad))
        }
        val center = Offset(pad + radius, pad + titleH + bodyH * 0.5f)
        var startDeg = -90f
        pie.slices.forEachIndexed { idx, slice ->
            val sweep = (slice.value / total) * 360f
            val fill = colors[idx % colors.size]
            drawArc(
                color = fill,
                startAngle = startDeg,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
            )
            drawArc(
                color = p.nodeStroke,
                startAngle = startDeg,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(1.2f * d),
            )
            startDeg += sweep
        }
        var ly = pad + titleH + (bodyH - legendH) * 0.5f
        val lx = pad + radius * 2 + 24f * d
        legendLayouts.forEachIndexed { idx, tl ->
            val swatch = colors[idx % colors.size]
            val boxSz = 12f * d
            drawRect(swatch, topLeft = Offset(lx, ly + (tl.size.height - boxSz) * 0.5f), size = Size(boxSz, boxSz))
            drawRect(p.nodeStroke, topLeft = Offset(lx, ly + (tl.size.height - boxSz) * 0.5f), size = Size(boxSz, boxSz), style = Stroke(1f * d))
            drawText(tl, topLeft = Offset(lx + boxSz + 8f * d, ly))
            ly += tl.size.height + 8f * d
        }
    }
}

private fun renderAndSaveMermaidPng(
    context: Context,
    layout: MeasuredMermaidDiagram,
    exportDensity: Density,
    bgColor: Color,
): Boolean {
    val bmpW = layout.widthPx.roundToInt().coerceIn(64, 4096)
    val bmpH = layout.heightPx.roundToInt().coerceIn(64, 4096)
    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    return try {
        val uiCanvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
        CanvasDrawScope().draw(
            density = exportDensity,
            layoutDirection = LayoutDirection.Ltr,
            canvas = uiCanvas,
            size = Size(bmpW.toFloat(), bmpH.toFloat()),
        ) {
            drawRect(bgColor, topLeft = Offset.Zero, size = size)
            layout.draw(this)
        }
        savePngBitmapToMediaStore(context, bitmap)
    } catch (_: Throwable) {
        false
    } finally {
        bitmap.recycle()
    }
}

private fun savePngBitmapToMediaStore(context: Context, bitmap: Bitmap): Boolean {
    val resolver = context.contentResolver
    val filename = "deepseek_mermaid_${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DeepSeek")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val uri = resolver.insert(collection, values) ?: return false
    return try {
        val ok = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (!ok) {
            resolver.delete(uri, null, null)
            false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }
    } catch (_: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
