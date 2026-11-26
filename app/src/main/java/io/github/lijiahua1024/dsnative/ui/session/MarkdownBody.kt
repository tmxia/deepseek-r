package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.size
import io.github.lijiahua1024.dsnative.ui.excludeFromDrawerGesture
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.theme.Strings
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.theme.DsTheme
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

// Full markdown body per reference markdown.css: body 16/28, block margins
// 16px (collapsed → 16dp gaps), h1-h3 32/16, list items 6px, marker
// label-secondary, blockquote 2px labelCaption bar + 14 padding, table
// bottom-borders only 15/25, code block banner + radius 12 + 13/22 mono,
// inline code 14 on markdownInlineCode r6, display math fallback = raw tex.
private val parser: Parser by lazy {
    Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            )
        )
        .build()
}

/** Pre-warms CommonMark parser, extensions, and code-highlight regex off the UI thread. */
internal fun prewarmMarkdownParser() {
    parser.parse("**a** `b` ~~c~~")
    CODE_TOKEN_RE.find("val x = 1")
}

private val mathBlock = Regex("\\$\\$([\\s\\S]+?)\\$\\$")

// Body text uses DsTheme.fontFamily: the ref WebView resolves its
// `Roboto, 'Noto Sans CJK SC'` stack to the bundled SC face for latin AND CJK
// (latin advances ~5% wider than Android's Roboto fallback).

// In the ref, inline <code> is a real box: its 5px padding occupies layout
// space, so following text starts after the chip edge. A drawn-only chip
// would overlap the next glyph; zero-height placeholders reproduce the gap.
private const val CHIP_PAD = "ds-inline-chip-pad"

// [citation:N] in reply markdown -> 来源角标（见 Sources.kt 的面板与 Registry）
private val CITE_RE = Regex("\\[citation:(\\d+)\\]")

// CSS half-leading + no last-line trim, applied wherever a line box's own
// height feeds layout (paragraphs, tables, code, headings, math).
private val dsLineStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)
private val cssLeading = androidx.compose.ui.text.TextStyle(lineHeightStyle = dsLineStyle)
private val chipPadContent = mapOf(
    CHIP_PAD to InlineTextContent(
        Placeholder(
            width = 5.sp,
            height = 1.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
    ) {},
)

private val CITE_BADGE_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = 6.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 0.dp)
private val URI_SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val ORDERED_LIST_PREFIX_RE = Regex("^\\d+[.)]\\s.*")

// ref .ds-markdown-cite: 14px 高角标（top -5px），圆角 6px 6px 6px 0，
// 0.5px l1 边框，markdown-placeholder 底，9px/500 等宽三级色数字
@Composable
private fun citeContent(c: io.github.lijiahua1024.dsnative.theme.DsColors): Map<String, InlineTextContent> {
    val sources = LocalSources.current
    if (sources.isEmpty()) return emptyMap()
    val open = LocalOpenSources.current
    return remember(sources, c.dark, open) {
        val map = HashMap<String, InlineTextContent>(sources.size)
        for (s in sources) {
            val src = s
            map["cite_" + s.index] = InlineTextContent(
                Placeholder(
                    width = 18.sp,
                    height = 14.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextTop,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 14.dp, height = 14.dp)
                            .clip(CITE_BADGE_SHAPE)
                            .background(c.markdownPlaceholder)
                            .border(0.5.dp, c.borderL1, CITE_BADGE_SHAPE)
                            .clickable { open(listOf(src)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(src.index.toString(), fontSize = 9.sp, lineHeight = 14.sp,
                            modifier = Modifier.semantics { contentDescription = Strings.get("citationLabel", "count" to src.index) },
                            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                            color = c.labelTertiary)
                    }
                }
            }
        }
        map
    }
}

private data class ParsedMarkdownChunk(
    val source: String,
    val tokens: Map<Int, ExtractedMathToken>,
    val blocks: List<Node>,
)

private val markdownChunkCache = object : android.util.LruCache<String, ParsedMarkdownChunk>(64) {}

private fun getParsedMarkdownChunk(rawChunk: String): ParsedMarkdownChunk {
    synchronized(markdownChunkCache) {
        markdownChunkCache.get(rawChunk)?.let { return it }
    }
    val prep = preprocessMarkdownMath(rawChunk)
    val doc = parser.parse(prep.markdown)
    val blocks = ArrayList<Node>()
    var cur = doc.firstChild
    while (cur != null) {
        blocks.add(cur)
        cur = cur.next
    }
    return ParsedMarkdownChunk(rawChunk, prep.tokens, blocks).also { parsed ->
        synchronized(markdownChunkCache) {
            markdownChunkCache.put(rawChunk, parsed)
        }
    }
}

private fun isUnsafeContinuationStart(line: String): Boolean {
    if (line.isEmpty()) return true
    val first = line[0]
    if (first == ' ' || first == '\t' || first == '>' || first == '|' || first == '=' || first == '-') return true
    if ((first == '*' || first == '+') && line.length > 1 && line[1] == ' ') return true
    if (first.isDigit() && ORDERED_LIST_PREFIX_RE.matches(line)) return true
    return false
}

/**
 * Splits long streaming/static Markdown at safe top-level blank-line block boundaries outside
 * fenced code, $$ math, lists, blockquotes, tables, and reference-link definitions so finished
 * prefix blocks cache their AST and skip Compose recomposition as trailing text grows.
 */
internal fun splitMarkdownChunks(input: String): List<String> {
    if (input.length < 400 || input.contains("]:")) return listOf(input)
    val chunks = ArrayList<String>()
    var chunkStart = 0
    var i = 0
    var inFenceChar = '\u0000'
    var inFenceLen = 0
    var inMath = false
    var lastNonBlankLine = ""
    while (i < input.length) {
        val nl = input.indexOf('\n', i)
        val lineEnd = if (nl < 0) input.length else nl
        val line = input.substring(i, lineEnd)
        val trimmed = line.trimStart()
        if (inFenceLen > 0) {
            var count = 0
            while (count < trimmed.length && trimmed[count] == inFenceChar) count++
            if (count >= inFenceLen && trimmed.substring(count).isBlank()) inFenceLen = 0
            lastNonBlankLine = trimmed
            i = if (nl < 0) input.length else nl + 1
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val ch = trimmed[0]
            var count = 0
            while (count < trimmed.length && trimmed[count] == ch) count++
            inFenceChar = ch
            inFenceLen = count
            lastNonBlankLine = trimmed
            i = if (nl < 0) input.length else nl + 1
            continue
        }
        if (trimmed.startsWith("$$")) {
            val rest = trimmed.substring(2)
            if (!rest.contains("$$")) inMath = !inMath
        }
        if (line.isBlank() && !inMath && nl >= 0) {
            val nextStart = nl + 1
            if (nextStart < input.length && nextStart - chunkStart >= 280) {
                val nextNl = input.indexOf('\n', nextStart).let { if (it < 0) input.length else it }
                val nextLine = input.substring(nextStart, nextNl)
                if (!nextLine.isBlank() && !isUnsafeContinuationStart(lastNonBlankLine) && !isUnsafeContinuationStart(nextLine)) {
                    chunks.add(input.substring(chunkStart, nextStart))
                    chunkStart = nextStart
                }
            }
        } else if (line.isNotBlank()) {
            lastNonBlankLine = trimmed
        }
        i = if (nl < 0) input.length else nl + 1
    }
    if (chunkStart < input.length) chunks.add(input.substring(chunkStart))
    return if (chunks.isEmpty()) listOf(input) else chunks
}

// CSS margins collapse between siblings: the visible gap is the max of the
// bottom margin of the previous block and the top margin of the next.
private fun topMargin(n: Node): Int = when (n) {
    is Heading -> if (n.level <= 3) 32 else 16
    is TableBlock -> 0
    is ThematicBreak -> 32
    else -> 16
}

private fun bottomMargin(n: Node, isLast: Boolean): Int = when (n) {
    is TableBlock -> 0
    is BlockQuote -> 0
    is FencedCodeBlock, is IndentedCodeBlock -> if (isLast) 16 else 11
    is ThematicBreak -> 32
    else -> 16
}

@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    selectable: Boolean = false,
) {
    val c = DsTheme.colors
    val baseUriHandler = LocalUriHandler.current
    val linkCtx = androidx.compose.ui.platform.LocalContext.current
    val dark = c.dark
    // ref Link.tsx: http/https ссылки открываются во встроенном браузере,
    // остальные уходят наружу (target=_blank -> ACTION_VIEW).
    val uriHandler = remember(baseUriHandler, linkCtx, dark) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                val sanitized = if (uri.matches(URI_SCHEME_RE)) uri
                    else "https://$uri"
                if (io.github.lijiahua1024.dsnative.SourceBrowserActivity.webUri(sanitized) != null) {
                    io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(linkCtx, sanitized, dark)
                } else {
                    try { baseUriHandler.openUri(uri) } catch (_: Exception) {}
                }
            }
        }
    }
    val parsedChunks = remember(text) {
        splitMarkdownChunks(text).map(::getParsedMarkdownChunk)
    }
    val content: @Composable () -> Unit = {
        Column(modifier) {
            parsedChunks.forEachIndexed { chunkIdx, chunk ->
                if (chunkIdx > 0) {
                    val prevLast = parsedChunks[chunkIdx - 1].blocks.lastOrNull()
                    val nextFirst = chunk.blocks.firstOrNull()
                    if (prevLast != null && nextFirst != null) {
                        Spacer(Modifier.height(maxOf(bottomMargin(prevLast, false), topMargin(nextFirst)).dp))
                    }
                }
                val tailStreaming = streaming && chunkIdx == parsedChunks.lastIndex
                key(chunkIdx, chunk.source) {
                    RenderMarkdownChunk(chunk, c, uriHandler, tailStreaming)
                }
            }
        }
    }
    if (selectable) {
        SelectionContainer { content() }
    } else {
        content()
    }
}

@Composable
private fun RenderMarkdownChunk(
    chunk: ParsedMarkdownChunk,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    streaming: Boolean,
) {
    val blocks = chunk.blocks
    for (idx in blocks.indices) {
        val node = blocks[idx]
        if (idx > 0) {
            Spacer(Modifier.height(maxOf(bottomMargin(blocks[idx - 1], false), topMargin(node)).dp))
        }
        RenderBlock(node, c, uriHandler, streaming, chunk.tokens)
    }
}

@Composable
private fun RenderBlock(
    node: Node,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    streaming: Boolean = false,
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    when (node) {
        is Paragraph -> RenderParagraph(inlineText(node, c, uriHandler), c, streaming, mathTokens)
        is Heading -> {
            val (size, lh) = when (node.level) {
                1 -> 24.sp to 34.sp
                2 -> 22.sp to 32.sp
                3 -> 20.sp to 30.sp
                else -> 16.sp to 28.sp
            }
            val (hStr, hInline) = rememberInlineMathPrepared(
                inlineText(node, c, uriHandler), c, mathTokens, fontSizeSp = size.value,
            )
            Text(hStr, fontSize = size,
                lineHeight = lh, fontWeight = if (node.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                style = cssLeading, color = c.labelPrimary,
                inlineContent = chipPadContent + citeContent(c) + hInline)
        }
        is FencedCodeBlock, is IndentedCodeBlock -> {
            val literal = when (node) {
                is FencedCodeBlock -> node.literal ?: ""
                else -> (node as IndentedCodeBlock).literal ?: ""
            }
            val lang = (node as? FencedCodeBlock)?.info ?: ""
            if (lang.trim().lowercase() == "mermaid") {
                MermaidCodeBlock(literal, streaming, c) { code ->
                    val mScroll = rememberScrollState()
                    val highlighted = remember(code, c.dark) { getHighlighted(code, c) }
                    Text(
                        highlighted,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 22.sp,
                        style = cssLeading,
                        modifier = Modifier.fillMaxWidth()
                            .then(if (mScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
                            .horizontalScroll(mScroll),
                    )
                }
            } else
            Column(
                Modifier.fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(c.markdownCodeBlock, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .animateContentSize(animationSpec = androidx.compose.animation.core.tween(250)),
            ) {
                // ref CodeInfoRow .container: padding 6px; copy .button h28 r8 px10 gap3
                Row(
                    Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.ifEmpty { "text" }, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp,
                        color = c.labelPrimary,
                        modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    // ref CodeBlock .button:active -> interactive-bg-hover; 复制成功仅按钮变勾/变文字，不弹 toast
                    val copySrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val copyPressed by copySrc.collectIsPressedAsState()
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var copied by remember { mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(copied) {
                        if (copied) {
                            kotlinx.coroutines.delay(1500)
                            copied = false
                        }
                    }
                    Row(
                        Modifier.height(28.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(DsTheme.pressTint(copyPressed, c.interactiveBgHover))
                            .clickable(copySrc, null) {
                                if (copied) return@clickable
                                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("code", literal))
                                copied = true
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(
                                if (copied) io.github.lijiahua1024.dsnative.R.drawable.ic_check
                                else io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                            null, tint = c.labelSecondary, modifier = Modifier.size(14.dp))
                        Text(Strings.get(if (copied) "copied" else "copy"),
                            fontSize = 13.sp, lineHeight = 14.2.sp, color = c.labelSecondary,
                            modifier = Modifier.padding(start = 3.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(if (c.dark) c.borderL2 else Color(0xFFEBEBEB)))
                val trimmedCode = remember(literal) { literal.trimEnd('\n') }
                // ref CodeBlock: FOLD_LINES=5，按逻辑行判定，不足阈值不渲染折叠按钮
                val codeLineCount = remember(trimmedCode) { trimmedCode.trim().split('\n').size }
                val needsFold = codeLineCount > 5
                var collapsed by remember(trimmedCode) { mutableStateOf(true) }
                // ref CodeBlock <pre>：white-space pre-wrap + overflow-wrap anywhere，
                // 长行在容器宽度内自动换行；不做横向滚动，避免折叠/展开间 intrinsic
                // 宽度与滚动状态重算导致的布局跳闪。
                Box(
                    Modifier.fillMaxWidth()
                        .background(c.markdownCodeBlockBanner)
                ) {
                    // 折叠态只对可见的前 5 行做高亮，避免长代码块首挂即对全量文本跑正则；展开后再全量高亮。
                    val highlightSource = if (needsFold && collapsed) {
                        trimmedCode.lines().take(5).joinToString("\n")
                    } else trimmedCode
                    val highlightedCode = remember(highlightSource, c.dark, lang) { getHighlighted(highlightSource, c, lang) }
                    Text(
                        highlightedCode,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp,
                        style = cssLeading,
                        maxLines = if (needsFold && collapsed) 5 else Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.9.dp, end = 34.9.dp, top = 14.9.dp, bottom = 14.9.dp),
                    )
                }
                // ref .foldToggle：margin 0 16px 12px、h28 r8 px10 gap4、13px/20px、labelSecondary
                if (needsFold) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { collapsed = !collapsed }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                Strings.get(if (collapsed) "expandCode" else "collapseCode"),
                                fontSize = 13.sp, lineHeight = 20.sp, color = c.labelSecondary,
                            )
                            androidx.compose.material3.Icon(
                                androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_chevron_right),
                                null, tint = c.labelSecondary,
                                modifier = Modifier.size(14.dp).rotate(if (collapsed) 90f else 270f),
                            )
                        }
                    }
                }
            }
        }
        is BlockQuote -> {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(c.labelCaption))
                Column(Modifier.padding(start = 14.dp)) {
                    var ch = node.firstChild
                    while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
                }
            }
        }
        is BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                            // ::marker disc ≈5.8dp centered on first 26.31dp line
                            Box(Modifier.width(16.dp).height(26.31.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(5.8.dp).background(c.labelSecondary, CircleShape))
                            }
                            Column(Modifier.weight(1f)) {
                                var ch = item.firstChild
                                while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
                            }
                        }
                    }
                    item = item.next
                }
            }
        }
        is OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var i = node.startNumber
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(Modifier.fillMaxWidth().padding(start = 2.dp)) {
                            Text("$i.", fontSize = 16.sp, lineHeight = 26.31.sp, color = c.labelSecondary,
                                style = cssLeading, modifier = Modifier.widthIn(min = 16.dp).padding(end = 4.dp))
                            Column(Modifier.weight(1f)) {
                                var ch = item.firstChild
                                while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
                            }
                        }
                        i++
                    }
                    item = item.next
                }
            }
        }
        is TableBlock -> RenderTable(node, c, uriHandler, mathTokens)
        is ThematicBreak -> {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderL2))
        }
        is HtmlBlock -> {
            val raw = (node.literal ?: "").trim()
            if (raw.isNotEmpty()) Text(raw, fontSize = 14.sp, color = c.labelSecondary)
        }
        else -> {
            var ch = node.firstChild
            while (ch != null) { RenderBlock(ch, c, uriHandler, streaming, mathTokens); ch = ch.next }
        }
    }
}

@Composable
private fun RenderParagraph(
    text: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    streaming: Boolean,
    mathTokens: Map<Int, ExtractedMathToken>,
) {
    val segs = splitMath(text, mathTokens)
    if (segs.size == 1 && segs[0].second == null) {
        ParagraphText(segs[0].first, c, mathTokens)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((str, math) in segs) {
            if (math != null) {
                KaTeXMath(math, streaming, c) { fallbackTex ->
                    val measurer = rememberTextMeasurer(cacheSize = 32)
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val measured = remember(fallbackTex, c.dark, density) {
                        measureInlineLatexFormula(fallbackTex, 18f, c.labelPrimary, measurer, density)
                    }
                    val fbScroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .then(if (fbScroll.maxValue > 0) Modifier.excludeFromDrawerGesture() else Modifier)
                            .horizontalScroll(fbScroll),
                        contentAlignment = Alignment.Center,
                    ) {
                        InlineLatexCanvas(measured)
                    }
                }
            } else if (str.text.isNotBlank() || segs.size == 1) {
                ParagraphText(str, c, mathTokens)
            }
        }
    }
}

// ref .ds-markdown code: rounded inline chip — bg bluish-100, radius 6px,
// padding 0 5px. SpanStyle.background is a bare rect, so the chip is drawn
// behind the text from layout-measured line boxes instead.
@Composable
private fun ParagraphText(
    str: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    val (styled, mathInlineContent) = rememberInlineMathPrepared(str, c, mathTokens, fontSizeSp = 16f)
    val hasInlineCodeChip = remember(styled) {
        styled.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && it.end > it.start }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val cite = citeContent(c)
    val inlineContent = remember(cite, mathInlineContent) {
        chipPadContent + cite + mathInlineContent
    }
    Text(
        styled, fontSize = 16.sp, lineHeight = 26.31.sp, color = c.labelPrimary,
        fontFamily = DsTheme.fontFamily,
        // CSS never trims and splits leading evenly; Compose's default trims
        // the last line's descent (~13px) which shrinks every paragraph box
        // and shifts all following content up. Center+None reproduces CSS
        // half-leading exactly for this font.
        style = cssLeading,
        onTextLayout = { if (hasInlineCodeChip) layout = it },
        inlineContent = inlineContent,
        modifier = if (!hasInlineCodeChip) Modifier else Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val padX = 5.dp.toPx()
            val radius = 6.dp.toPx()
            for (s in styled.spanStyles) {
                if (s.item.fontFamily != FontFamily.Monospace || s.end <= s.start) continue
                val firstLine = l.getLineForOffset(s.start)
                val lastLine = l.getLineForOffset(s.end - 1)
                for (line in firstLine..lastLine) {
                    val from = maxOf(s.start, l.getLineStart(line))
                    val to = minOf(s.end, l.getLineEnd(line, true))
                    if (from >= to) continue
                    val left = l.getBoundingBox(from).left
                    val right = l.getBoundingBox(to - 1).right
                    drawRoundRect(
                        c.markdownInlineCode,
                        topLeft = Offset(left - padX, l.getLineTop(line) + 2.dp.toPx()),
                        size = Size(right - left + 2 * padX,
                            l.getLineBottom(line) - l.getLineTop(line) - 4.dp.toPx()),
                        cornerRadius = CornerRadius(radius),
                    )
                }
            }
        },
    )
}

private data class ExtractedTableData(
    val headerRows: List<List<TableCell>>,
    val bodyRows: List<List<TableCell>>,
    val columnCount: Int,
    val markdownCopy: String,
    val csvText: String,
)

private fun nodePlainText(node: Node): String {
    val sb = StringBuilder()
    var cur = node.firstChild
    while (cur != null) {
        when (cur) {
            is org.commonmark.node.Text -> sb.append(cur.literal)
            is org.commonmark.node.Code -> sb.append(cur.literal)
            is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> sb.append(' ')
            else -> sb.append(nodePlainText(cur))
        }
        cur = cur.next
    }
    return sb.toString().trim()
}

private fun collectTableData(table: TableBlock): ExtractedTableData {
    val headers = ArrayList<List<TableCell>>()
    val body = ArrayList<List<TableCell>>()
    var section = table.firstChild
    while (section != null) {
        val isHead = section is TableHead
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = ArrayList<TableCell>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells.add(cell)
                    cell = cell.next
                }
                if (cells.isNotEmpty()) {
                    if (isHead) headers.add(cells) else body.add(cells)
                }
            }
            row = row.next
        }
        section = section.next
    }
    val allRows = headers + body
    val colCount = allRows.maxOfOrNull { it.size } ?: 0
    val mdSb = StringBuilder()
    headers.forEach { row ->
        mdSb.append("| ").append(row.joinToString(" | ") { nodePlainText(it) }).append(" |\n")
        if (colCount > 0) mdSb.append("| ").append((0 until colCount).joinToString(" | ") { "---" }).append(" |\n")
    }
    body.forEach { row ->
        mdSb.append("| ").append(row.joinToString(" | ") { nodePlainText(it) }).append(" |\n")
    }
    val csvSb = StringBuilder()
    allRows.forEach { row ->
        csvSb.append(row.joinToString(",") { cell ->
            val raw = nodePlainText(cell)
            if (raw.contains(',') || raw.contains('"') || raw.contains('\n')) "\"${raw.replace("\"", "\"\"")}\"" else raw
        }).append('\n')
    }
    return ExtractedTableData(headers, body, colCount, mdSb.toString().trimEnd(), csvSb.toString())
}

private fun exportTableCsv(context: android.content.Context, csv: String): Boolean {
    return runCatching {
        val filename = "deepseek_table_${System.currentTimeMillis()}.csv"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/DeepSeek")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            val ok = resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)); true } ?: false
            if (!ok) {
                resolver.delete(uri, null, null)
                false
            } else {
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            java.io.File(dir, filename).writeText(csv, Charsets.UTF_8)
            true
        }
    }.getOrDefault(false)
}

@Composable
private fun RenderTable(
    table: TableBlock,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    mathTokens: Map<Int, ExtractedMathToken> = emptyMap(),
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data = remember(table) { collectTableData(table) }
    var showFullscreen by remember { mutableStateOf(false) }

    val cardBg = if (c.dark) c.markdownCodeBlock else Color(0xFFFAFAFA)
    val gridColor = if (c.dark) c.borderL2 else Color(0xFFDCDCDC)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.75.dp, gridColor, shape)
            .background(cardBg),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(cardBg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Strings.get("tableLabel"),
                fontSize = 13.sp,
                color = c.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            val actionTint = if (c.dark) c.labelSecondary else Color(0xFF7B7F85)
            androidx.compose.material3.IconButton(
                onClick = {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("table", data.markdownCopy))
                    io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("copied"))
                },
                modifier = Modifier.size(34.dp),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                    Strings.get("copy"),
                    tint = actionTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            androidx.compose.material3.IconButton(
                onClick = {
                    val ok = exportTableCsv(ctx, data.csvText)
                    io.github.lijiahua1024.dsnative.ui.session.DsToast.show(
                        Strings.get(if (ok) "tableExported" else "tableExportFailed"),
                    )
                },
                modifier = Modifier.size(34.dp),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_download),
                    Strings.get("tableExportCsv"),
                    tint = actionTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            androidx.compose.material3.IconButton(
                onClick = { showFullscreen = true },
                modifier = Modifier.size(34.dp),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_fullscreen),
                    Strings.get("mermaidFullscreen"),
                    tint = actionTint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(gridColor))
        RenderTableGrid(data, c, uriHandler, mathTokens, gridColor)
    }

    if (showFullscreen) {
        TableFullscreenDialog(
            data = data,
            c = c,
            uriHandler = uriHandler,
            mathTokens = mathTokens,
            onCopy = {
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("table", data.markdownCopy))
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(Strings.get("copied"))
            },
            onExportCsv = {
                val ok = exportTableCsv(ctx, data.csvText)
                io.github.lijiahua1024.dsnative.ui.session.DsToast.show(
                    Strings.get(if (ok) "tableExported" else "tableExportFailed"),
                )
            },
            onDismiss = { showFullscreen = false },
        )
    }
}

@Composable
private fun RenderTableGrid(
    data: ExtractedTableData,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    mathTokens: Map<Int, ExtractedMathToken>,
    gridColor: Color,
) {
    val headerBg = if (c.dark) c.markdownCodeBlock else Color(0xFFFAFAFA)
    val bodyBg = if (c.dark) c.bgBase else Color.White
    val tableScroll = rememberScrollState()
    val cols = data.columnCount.coerceAtLeast(1)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val equalCol = maxWidth / cols
        val colW = maxOf(116.dp, equalCol)
        val needHScroll = colW * cols > maxWidth + 0.5.dp
        val rows = data.headerRows.map { true to it } + data.bodyRows.map { false to it }

        Column(
            Modifier
                .fillMaxWidth()
                .then(if (needHScroll) Modifier.excludeFromDrawerGesture().horizontalScroll(tableScroll) else Modifier),
        ) {
            rows.forEachIndexed { rIdx, (isHeader, rowCells) ->
                Row(
                    Modifier
                        .height(IntrinsicSize.Max)
                        .background(if (isHeader) headerBg else bodyBg),
                ) {
                    for (colIdx in 0 until cols) {
                        val cell = rowCells.getOrNull(colIdx)
                        Box(
                            Modifier
                                .width(colW)
                                .fillMaxHeight()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (cell != null) {
                                val (cellStr, cellInline) = rememberInlineMathPrepared(
                                    inlineText(cell, c, uriHandler),
                                    c,
                                    mathTokens,
                                    fontSizeSp = 15f,
                                )
                                Text(
                                    cellStr,
                                    fontSize = 15.sp,
                                    lineHeight = 23.sp,
                                    fontWeight = if (isHeader) FontWeight.Medium else FontWeight.Normal,
                                    style = cssLeading,
                                    color = c.labelPrimary,
                                    inlineContent = chipPadContent + citeContent(c) + cellInline,
                                )
                            }
                        }
                        if (colIdx < cols - 1) {
                            Box(Modifier.width(0.75.dp).fillMaxHeight().background(gridColor))
                        }
                    }
                }
                if (rIdx < rows.lastIndex) {
                    Box(Modifier.width(colW * cols).height(0.75.dp).background(gridColor))
                }
            }
        }
    }
}

@Composable
private fun TableFullscreenDialog(
    data: ExtractedTableData,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    mathTokens: Map<Int, ExtractedMathToken>,
    onCopy: () -> Unit,
    onExportCsv: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gridColor = if (c.dark) c.borderL2 else Color(0xFFDCDCDC)
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        androidx.activity.compose.PredictiveBackHandler { flow ->
            try {
                flow.collect { }
                onDismiss()
            } catch (_: Throwable) {
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
        ) {
            val vScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .background(c.bgBase)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_close),
                            Strings.get("close"),
                            tint = c.labelPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        Strings.get("tableLabel"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.labelPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.IconButton(onClick = onCopy) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_copy),
                            Strings.get("copy"),
                            tint = c.labelSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = onExportCsv) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(io.github.lijiahua1024.dsnative.R.drawable.ic_download),
                            Strings.get("tableExportCsv"),
                            tint = c.labelSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.75.dp).background(gridColor))
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
                    RenderTableGrid(data, c, uriHandler, mathTokens, gridColor)
                }
            }
        }
    }
}

/** 按块级公式占位符与 $$...$$ 拆分段落；返回 (styled, tex?) 列表。 */
private fun splitMath(
    styled: AnnotatedString,
    mathTokens: Map<Int, ExtractedMathToken>,
): List<Pair<AnnotatedString, String?>> {
    val plain = styled.text
    val tokenMatches = BLOCK_TOKEN_REGEX.findAll(plain).toList()
    if (tokenMatches.isEmpty()) {
        val rawMatches = mathBlock.findAll(plain).toList()
        if (rawMatches.isEmpty()) return listOf(styled to null)
        val out = mutableListOf<Pair<AnnotatedString, String?>>()
        var i = 0
        for (m in rawMatches) {
            if (m.range.first > i) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, m.range.first)) to null)
            out.add(AnnotatedString("") to sanitizeLatexDialect(m.groupValues[1].trim()))
            i = m.range.last + 1
        }
        if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
        return out
    }
    val out = mutableListOf<Pair<AnnotatedString, String?>>()
    var i = 0
    for (m in tokenMatches) {
        if (m.range.first > i) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, m.range.first)) to null)
        val id = m.groupValues[1].toIntOrNull()
        val tex = id?.let { mathTokens[it]?.sanitizedTex }
        if (tex != null) {
            out.add(AnnotatedString("") to tex)
        } else {
            out.add(styled.subSequence(androidx.compose.ui.text.TextRange(m.range.first, m.range.last + 1)) to null)
        }
        i = m.range.last + 1
    }
    if (i < plain.length) out.add(styled.subSequence(androidx.compose.ui.text.TextRange(i, plain.length)) to null)
    return out
}

@Composable
private fun rememberInlineMathPrepared(
    str: AnnotatedString,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    mathTokens: Map<Int, ExtractedMathToken>,
    fontSizeSp: Float,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val plain = str.text
    if (!INLINE_TOKEN_REGEX.containsMatchIn(plain)) {
        return str to emptyMap()
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(str, c.dark, mathTokens, fontSizeSp, density) {
        val inlineMap = HashMap<String, InlineTextContent>()
        val built = buildAnnotatedString {
            var cursor = 0
            for (m in INLINE_TOKEN_REGEX.findAll(plain)) {
                if (m.range.first > cursor) {
                    append(str.subSequence(cursor, m.range.first))
                }
                val id = m.groupValues[1].toIntOrNull()
                val tok = id?.let { mathTokens[it] }
                if (tok != null) {
                    val ast = parseLatexInlineAst(tok.sanitizedTex)
                    if (!ast.hasComplexLayout()) {
                        append(simpleAstToAnnotatedString(ast))
                    } else {
                        val measured = measureInlineLatexFormula(tok.sanitizedTex, fontSizeSp, c.labelPrimary, measurer, density)
                        val key = "dsm_${tok.id}"
                        appendInlineContent(key, tok.rawTex)
                        inlineMap[key] = InlineTextContent(
                            Placeholder(
                                width = measured.widthSp,
                                height = measured.heightSp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            InlineLatexCanvas(measured)
                        }
                    }
                } else {
                    append(str.subSequence(m.range.first, m.range.last + 1))
                }
                cursor = m.range.last + 1
            }
            if (cursor < plain.length) {
                append(str.subSequence(cursor, plain.length))
            }
        }
        built to inlineMap
    }
}

private fun simpleAstToAnnotatedString(node: LatexNode): AnnotatedString = buildAnnotatedString {
    fun emit(n: LatexNode) {
        when (n) {
            is LatexNode.Seq -> n.children.forEach(::emit)
            is LatexNode.Space -> if (n.em > 0.1f) append(' ')
            is LatexNode.Text -> {
                val style = when (n.style) {
                    MathTextStyle.MATH_ITALIC -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                    MathTextStyle.BOLD -> SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    MathTextStyle.MONO -> SpanStyle(fontFamily = FontFamily.Monospace)
                    MathTextStyle.UPRIGHT, MathTextStyle.OPERATOR, MathTextStyle.BLACKBOARD, MathTextStyle.CALLIGRAPHIC ->
                        SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Normal)
                }
                withStyle(style) { append(n.text) }
            }
            is LatexNode.Script -> {
                emit(n.base)
                n.sub?.let { sub ->
                    withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 11.sp, fontFamily = FontFamily.Serif)) {
                        append(simpleAstToAnnotatedString(sub))
                    }
                }
                n.sup?.let { sup ->
                    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 11.sp, fontFamily = FontFamily.Serif)) {
                        append(simpleAstToAnnotatedString(sup))
                    }
                }
            }
            is LatexNode.Delimited -> {
                if (n.left.isNotEmpty()) withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append(n.left) }
                emit(n.body)
                if (n.right.isNotEmpty()) withStyle(SpanStyle(fontFamily = FontFamily.Serif)) { append(n.right) }
            }
            is LatexNode.Colored -> emit(n.body)
            else -> {}
        }
    }
    emit(node)
}

private fun inlineText(
    node: Node,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    uriHandler: androidx.compose.ui.platform.UriHandler,
): AnnotatedString = buildAnnotatedString {
    var ch = node.firstChild
    while (ch != null) {
        when (ch) {
            is CText -> {
                // ref joinTextBlocks 在文本末尾插入 [citation:N]，Markdown 里渲染为角标
                var pos = 0
                var hit = false
                for (m in CITE_RE.findAll(ch.literal)) {
                    hit = true
                    if (m.range.first > pos) append(ch.literal.substring(pos, m.range.first))
                    appendInlineContent("cite_" + m.groupValues[1], m.value)
                    pos = m.range.last + 1
                }
                if (!hit || pos < ch.literal.length) append(ch.literal.substring(pos))
            }
            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append('\n')
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(inlineText(ch, c, uriHandler))
            }
            // WebView synthesizes ~14deg oblique for fonts without italic
            // (all CJK); skewX -0.25 matches that. Real italic latin would
            // double-slant, so FontStyle stays Normal here.
            is Emphasis -> withStyle(SpanStyle(
                textGeometricTransform = TextGeometricTransform(skewX = -0.25f),
            )) {
                append(inlineText(ch, c, uriHandler))
            }
            is Strikethrough -> withStyle(SpanStyle(
                textDecoration = TextDecoration.LineThrough,
            )) {
                append(inlineText(ch, c, uriHandler))
            }
            is Code -> {
                appendInlineContent(CHIP_PAD, " ")
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                ) { append(ch.literal) }
                appendInlineContent(CHIP_PAD, " ")
            }
            is Link -> {
                val url = ch.destination
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(style = SpanStyle(color = c.brandText)),
                    ) { uriHandler.openUri(url) },
                ) { append(inlineText(ch, c, uriHandler)) }
            }
            is Image -> append(ch.title ?: "[image]")
            is HtmlInline -> append(ch.literal ?: "")
            else -> {
                if (ch is CustomNode || ch.firstChild == null) {
                    append(ch.toString())
                } else {
                    append(inlineText(ch, c, uriHandler))
                }
            }
        }
        ch = ch.next
    }
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "interface", "object", "return", "if", "else", "elif",
    "for", "while", "when", "import", "package", "null", "None", "is", "not", "and", "or",
    "in", "as", "data", "sealed", "public", "private", "internal", "override", "suspend",
    "const", "def", "function", "let", "new", "void", "int", "long", "double", "float",
    "static", "final", "extends", "implements", "async", "await", "yield", "pass", "break",
    "continue", "raise", "except", "finally", "assert", "with", "from", "lambda", "self",
    "this", "super", "try", "catch", "throw", "throws", "switch", "case", "default",
    "struct", "enum", "type", "export", "fn", "pub", "impl", "mut", "match", "use",
)

private val BOOLEANS = setOf("true", "false", "True", "False")
private val CODE_TOKEN_RE = Regex("(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|//[^\n]*|#[^\n]*|/\\*[\\s\\S]*?\\*/|\\b\\d+(\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")

// 代码高亮 AnnotatedString 跨实例缓存：产物为纯 SpanStyle 文本数据、不可变，
// 回收重挂命中即免重复正则扫描与组装；键含明暗主题与语言。
private val highlightCache = object : android.util.LruCache<String, AnnotatedString>(64) {
    override fun sizeOf(key: String, value: AnnotatedString): Int = key.length + 16
}

private fun getHighlighted(
    code: String,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    lang: String = "",
): AnnotatedString {
    val key = "${c.dark}|$lang|$code"
    synchronized(highlightCache) { highlightCache.get(key)?.let { return it } }
    return highlightCode(code, c, lang).also {
        synchronized(highlightCache) { highlightCache.put(key, it) }
    }
}

// Atom One Light palette matching official DeepSeek code card (light + dark)
private fun highlightCode(
    code: String,
    c: io.github.lijiahua1024.dsnative.theme.DsColors,
    lang: String = "",
): AnnotatedString = buildAnnotatedString {
    val comment = if (c.dark) Color(0xFF8DA1B9) else Color(0xFFA0A1A7)
    val keyword = if (c.dark) Color(0xFFE9AE7E) else Color(0xFFA626A4)
    val function = if (c.dark) Color(0xFFC699E3) else Color(0xFF4078F2)
    val string = if (c.dark) Color(0xFF91D076) else Color(0xFF50A14F)
    val number = if (c.dark) Color(0xFFE6D37A) else Color(0xFF986801)
    val className = if (c.dark) Color(0xFF6CB8E6) else Color(0xFF986801)
    // ref pre[data-lang='yaml']：yaml 裸值整体按字符串绿着色
    val isYaml = lang.trim().lowercase() in setOf("yaml", "yml")
    val plain = if (isYaml) string else (if (c.dark) Color(0xFFE3EAF2) else Color(0xFF191B23))
    var i = 0
    for (m in CODE_TOKEN_RE.findAll(code)) {
        if (m.range.first > i) withStyle(SpanStyle(color = plain)) { append(code.substring(i, m.range.first)) }
        val t = m.value
        val color = when {
            t.startsWith("//") || t.startsWith("/*") || (t.startsWith("#") && !t.startsWith("#!")) -> comment
            t.startsWith("\"") || t.startsWith("'") -> string
            t.first().isDigit() || BOOLEANS.contains(t) -> number
            KEYWORDS.contains(t) -> keyword
            t.first().isUpperCase() -> className
            code.getOrNull(m.range.last + 1) == '(' -> function
            else -> plain
        }
        withStyle(SpanStyle(color = color)) { append(t) }
        i = m.range.last + 1
    }
    if (i < code.length) withStyle(SpanStyle(color = plain)) { append(code.substring(i)) }
}
