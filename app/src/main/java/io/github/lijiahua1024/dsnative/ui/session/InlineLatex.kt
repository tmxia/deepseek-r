package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 原生 Compose Canvas + TextMeasurer 行内 LaTeX 数学引擎：
 * 同步测量与绘制分式、根号、上下标、重音（\vec/\hat/\bar）、定界符、矩阵/cases 等结构，
 * 避免内联公式退化成 a/b 或产生多余 WebView 异步闪烁。
 */
internal data class MeasuredLatexFormula(
    val widthPx: Float,
    val heightPx: Float,
    val widthSp: TextUnit,
    val heightSp: TextUnit,
    val drawCommands: List<DrawCmd>,
)

internal sealed interface DrawCmd {
    data class TextDraw(val layout: TextLayoutResult, val x: Float, val y: Float) : DrawCmd
    data class LineDraw(val color: Color, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val strokeWidth: Float) : DrawCmd
    data class RectDraw(val color: Color, val x: Float, val y: Float, val w: Float, val h: Float) : DrawCmd
    data class PathDraw(val color: Color, val points: List<Pair<Float, Float>>, val strokeWidth: Float) : DrawCmd
    data class DelimDraw(val delim: String, val x: Float, val top: Float, val bottom: Float, val w: Float, val color: Color, val strokeWidth: Float) : DrawCmd
}

private data class MathBox(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: (x: Float, axisY: Float, out: MutableList<DrawCmd>) -> Unit,
) {
    val height: Float get() = ascent + descent
}

private fun parseColorOrNull(hexOrName: String?): Color? {
    val raw = hexOrName?.trim() ?: return null
    val norm = normalizeLatexColorArg(raw)
    if (norm.startsWith("#")) {
        return try {
            Color(android.graphics.Color.parseColor(norm))
        } catch (_: Exception) {
            null
        }
    }
    return when (norm.lowercase()) {
        "red" -> Color(0xFFDC2626)
        "blue" -> Color(0xFF2563EB)
        "green" -> Color(0xFF16A34A)
        "yellow" -> Color(0xFFEAB308)
        "orange" -> Color(0xFFEA580C)
        "purple" -> Color(0xFF9333EA)
        "gray", "grey" -> Color(0xFF6B7280)
        "black" -> Color.Black
        "white" -> Color.White
        else -> null
    }
}

private fun measureTextBox(
    text: String,
    style: MathTextStyle,
    fontSizeSp: Float,
    color: Color,
    measurer: TextMeasurer,
): MathBox {
    if (text.isEmpty()) return MathBox(0f, fontSizeSp * 0.4f, fontSizeSp * 0.2f) { _, _, _ -> }
    val textStyle = when (style) {
        MathTextStyle.MATH_ITALIC -> TextStyle(
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = fontSizeSp.sp,
            color = color,
        )
        MathTextStyle.UPRIGHT, MathTextStyle.OPERATOR -> TextStyle(
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Normal,
            fontSize = fontSizeSp.sp,
            color = color,
        )
        MathTextStyle.BOLD -> TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = fontSizeSp.sp,
            color = color,
        )
        MathTextStyle.MONO -> TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = (fontSizeSp * 0.92f).sp,
            color = color,
        )
        MathTextStyle.BLACKBOARD, MathTextStyle.CALLIGRAPHIC -> TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontStyle = if (style == MathTextStyle.CALLIGRAPHIC) FontStyle.Italic else FontStyle.Normal,
            fontSize = fontSizeSp.sp,
            color = color,
        )
    }
    val layout = measurer.measure(AnnotatedString(text), style = textStyle)
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val baseline = if (layout.lineCount > 0) layout.getLineBaseline(0) else h * 0.78f
    // math axis ≈ x-height/2 位于基线上方，令垂直对齐自然居中于行内文本
    val axisFromTop = baseline * 0.68f
    return MathBox(
        width = w,
        ascent = axisFromTop,
        descent = max(0f, h - axisFromTop),
    ) { x, axisY, out ->
        out.add(DrawCmd.TextDraw(layout, x, axisY - axisFromTop))
    }
}

private fun layoutAstNode(
    node: LatexNode,
    fontSizeSp: Float,
    color: Color,
    measurer: TextMeasurer,
    density: Density,
    displayMode: Boolean = false,
): MathBox {
    val pxPerSp = with(density) { 1.sp.toPx() }
    val ruleStroke = max(1f, 0.065f * fontSizeSp * pxPerSp)

    return when (node) {
        is LatexNode.Text -> {
            val isBigOp = displayMode && node.style == MathTextStyle.OPERATOR &&
                node.text in setOf("∑", "∏", "∐", "∫", "∬", "∭", "∮", "⋃", "⋂")
            val sz = if (isBigOp) fontSizeSp * 1.32f else fontSizeSp
            measureTextBox(node.text, node.style, sz, color, measurer)
        }
        is LatexNode.Space -> {
            val w = max(0f, node.em * fontSizeSp * pxPerSp)
            MathBox(w, fontSizeSp * 0.4f * pxPerSp, fontSizeSp * 0.2f * pxPerSp) { _, _, _ -> }
        }
        is LatexNode.Seq -> {
            val boxes = node.children.map { layoutAstNode(it, fontSizeSp, color, measurer, density, displayMode) }
            if (boxes.isEmpty()) {
                MathBox(0f, 0.4f * fontSizeSp * pxPerSp, 0.2f * fontSizeSp * pxPerSp) { _, _, _ -> }
            } else {
                val w = boxes.sumOf { it.width.toDouble() }.toFloat()
                val asc = boxes.maxOf { it.ascent }
                val desc = boxes.maxOf { it.descent }
                MathBox(w, asc, desc) { x, axisY, out ->
                    var cx = x
                    for (b in boxes) {
                        b.draw(cx, axisY, out)
                        cx += b.width
                    }
                }
            }
        }
        is LatexNode.Frac -> {
            val subScale = if (displayMode) 0.84f else 0.74f
            val subSize = max(10f, fontSizeSp * subScale)
            val num = layoutAstNode(node.num, subSize, color, measurer, density, displayMode)
            val den = layoutAstNode(node.den, subSize, color, measurer, density, displayMode)
            val padX = (if (displayMode) 3.0f else 2.2f) * density.density
            val gap = (if (displayMode) 2.5f else 1.6f) * density.density
            val contentW = max(num.width, den.width) + padX * 2f
            val asc = num.height + gap + ruleStroke * 0.5f
            val desc = den.height + gap + ruleStroke * 0.5f
            MathBox(contentW, asc, desc) { x, axisY, out ->
                val numX = x + (contentW - num.width) * 0.5f
                val numAxis = axisY - gap - ruleStroke * 0.5f - num.descent
                num.draw(numX, numAxis, out)
                if (node.drawBar) {
                    out.add(DrawCmd.LineDraw(color, x + padX * 0.3f, axisY, x + contentW - padX * 0.3f, axisY, ruleStroke))
                }
                val denX = x + (contentW - den.width) * 0.5f
                val denAxis = axisY + gap + ruleStroke * 0.5f + den.ascent
                den.draw(denX, denAxis, out)
            }
        }
        is LatexNode.Root -> {
            val body = layoutAstNode(node.body, fontSizeSp, color, measurer, density, displayMode)
            val idxSize = max(8.5f, fontSizeSp * 0.58f)
            val idxBox = node.index?.let { layoutAstNode(it, idxSize, color, measurer, density) }
            val topPad = 2.6f * density.density
            val tailPad = 2.0f * density.density
            val radicalW = max(7f * density.density, 0.48f * fontSizeSp * pxPerSp)
            val idxOffsetW = idxBox?.let { max(0f, it.width - radicalW * 0.4f) } ?: 0f
            val totalW = idxOffsetW + radicalW + body.width + tailPad
            val asc = body.ascent + topPad + ruleStroke
            val desc = body.descent + density.density
            MathBox(totalW, asc, desc) { x, axisY, out ->
                val topY = axisY - body.ascent - topPad
                val botY = axisY + body.descent
                val radStartX = x + idxOffsetW
                val hookY = axisY + (body.descent * 0.1f)
                idxBox?.draw(x, topY + idxBox.ascent, out)
                out.add(
                    DrawCmd.PathDraw(
                        color = color,
                        points = listOf(
                            radStartX to hookY,
                            radStartX + radicalW * 0.28f to hookY - 2.2f * density.density,
                            radStartX + radicalW * 0.58f to botY,
                            radStartX + radicalW to topY,
                            x + totalW - tailPad * 0.5f to topY,
                        ),
                        strokeWidth = ruleStroke,
                    )
                )
                body.draw(radStartX + radicalW, axisY, out)
            }
        }
        is LatexNode.Script -> {
            val base = layoutAstNode(node.base, fontSizeSp, color, measurer, density, displayMode)
            val scriptSize = max(9.5f, fontSizeSp * 0.68f)
            val subBox = node.sub?.let { layoutAstNode(it, scriptSize, color, measurer, density) }
            val supBox = node.sup?.let { layoutAstNode(it, scriptSize, color, measurer, density) }
            if (node.stackedLimits) {
                val gap = 1.2f * density.density
                val maxW = max(base.width, max(subBox?.width ?: 0f, supBox?.width ?: 0f))
                val asc = base.ascent + (supBox?.let { it.height + gap } ?: 0f)
                val desc = base.descent + (subBox?.let { it.height + gap } ?: 0f)
                MathBox(maxW, asc, desc) { x, axisY, out ->
                    base.draw(x + (maxW - base.width) * 0.5f, axisY, out)
                    supBox?.let {
                        val sAxis = axisY - base.ascent - gap - it.descent
                        it.draw(x + (maxW - it.width) * 0.5f, sAxis, out)
                    }
                    subBox?.let {
                        val sAxis = axisY + base.descent + gap + it.ascent
                        it.draw(x + (maxW - it.width) * 0.5f, sAxis, out)
                    }
                }
            } else {
                val gapX = 0.8f * density.density
                val scriptW = max(subBox?.width ?: 0f, supBox?.width ?: 0f)
                val supAxisShift = base.ascent * 0.72f
                val subAxisShift = base.descent * 0.65f + 0.25f * fontSizeSp * pxPerSp
                val asc = max(base.ascent, supBox?.let { supAxisShift + it.ascent } ?: 0f)
                val desc = max(base.descent, subBox?.let { subAxisShift + it.descent } ?: 0f)
                MathBox(base.width + (if (scriptW > 0f) gapX + scriptW else 0f), asc, desc) { x, axisY, out ->
                    base.draw(x, axisY, out)
                    supBox?.draw(x + base.width + gapX, axisY - supAxisShift, out)
                    subBox?.draw(x + base.width + gapX, axisY + subAxisShift, out)
                }
            }
        }
        is LatexNode.Accent -> {
            val body = layoutAstNode(node.body, fontSizeSp, color, measurer, density, displayMode)
            val gap = 1.6f * density.density
            val accentH = when (node.kind) {
                MathAccentKind.BAR, MathAccentKind.OVERLINE, MathAccentKind.UNDERLINE -> 2.0f * density.density
                MathAccentKind.DOT, MathAccentKind.DDOT -> 2.5f * density.density
                else -> 4.2f * density.density
            }
            val isUnder = node.kind == MathAccentKind.UNDERLINE
            val asc = body.ascent + if (isUnder) 0f else (gap + accentH)
            val desc = body.descent + if (isUnder) (gap + accentH) else 0f
            MathBox(body.width, asc, desc) { x, axisY, out ->
                body.draw(x, axisY, out)
                val yTop = axisY - body.ascent - gap - accentH * 0.5f
                val left = x + 1f * density.density
                val right = x + body.width - 1f * density.density
                val midX = x + body.width * 0.5f
                when (node.kind) {
                    MathAccentKind.BAR, MathAccentKind.OVERLINE ->
                        out.add(DrawCmd.LineDraw(color, left, yTop, right, yTop, ruleStroke))
                    MathAccentKind.UNDERLINE -> {
                        val yBot = axisY + body.descent + gap
                        out.add(DrawCmd.LineDraw(color, left, yBot, right, yBot, ruleStroke))
                    }
                    MathAccentKind.VEC, MathAccentKind.RIGHTARROW -> {
                        val tip = max(left + 4f, right)
                        val arr = 2.4f * density.density
                        out.add(DrawCmd.LineDraw(color, left, yTop, tip, yTop, ruleStroke))
                        out.add(DrawCmd.PathDraw(color, listOf(
                            tip - arr to yTop - arr * 0.7f,
                            tip to yTop,
                            tip - arr to yTop + arr * 0.7f,
                        ), ruleStroke))
                    }
                    MathAccentKind.HAT -> {
                        val hw = max(3f * density.density, minOf(body.width * 0.42f, 6f * density.density))
                        out.add(DrawCmd.PathDraw(color, listOf(
                            midX - hw to yTop + 1.5f * density.density,
                            midX to yTop - 1.5f * density.density,
                            midX + hw to yTop + 1.5f * density.density,
                        ), ruleStroke))
                    }
                    MathAccentKind.TILDE -> {
                        val hw = max(3.5f * density.density, minOf(body.width * 0.42f, 6f * density.density))
                        out.add(DrawCmd.PathDraw(color, listOf(
                            midX - hw to yTop + density.density,
                            midX - hw * 0.3f to yTop - density.density,
                            midX + hw * 0.3f to yTop + density.density,
                            midX + hw to yTop - density.density,
                        ), ruleStroke))
                    }
                    MathAccentKind.DOT -> {
                        val r = max(1.2f, ruleStroke * 1.15f)
                        out.add(DrawCmd.RectDraw(color, midX - r, yTop - r, r * 2, r * 2))
                    }
                    MathAccentKind.DDOT -> {
                        val r = max(1.1f, ruleStroke * 1.1f)
                        val d = 2.2f * density.density
                        out.add(DrawCmd.RectDraw(color, midX - d - r, yTop - r, r * 2, r * 2))
                        out.add(DrawCmd.RectDraw(color, midX + d - r, yTop - r, r * 2, r * 2))
                    }
                }
            }
        }
        is LatexNode.Delimited -> {
            val body = layoutAstNode(node.body, fontSizeSp, color, measurer, density, displayMode)
            val delimW = if (node.left.isEmpty() && node.right.isEmpty()) 0f else (if (displayMode) 6.5f else 5.5f) * density.density
            val lw = if (node.left.isEmpty()) 0f else delimW
            val rw = if (node.right.isEmpty()) 0f else delimW
            val pad = 1.5f * density.density
            val asc = body.ascent + pad
            val desc = body.descent + pad
            MathBox(lw + body.width + rw, asc, desc) { x, axisY, out ->
                if (lw > 0f) out.add(DrawCmd.DelimDraw(node.left, x, axisY - asc, axisY + desc, lw, color, ruleStroke))
                body.draw(x + lw, axisY, out)
                if (rw > 0f) out.add(DrawCmd.DelimDraw(node.right, x + lw + body.width, axisY - asc, axisY + desc, rw, color, ruleStroke))
            }
        }
        is LatexNode.Matrix -> {
            val cellSize = max(10f, fontSizeSp * 0.85f)
            val measuredRows = node.rows.map { row ->
                row.map { cell -> layoutAstNode(cell, cellSize, color, measurer, density) }
            }
            val cols = measuredRows.maxOfOrNull { it.size } ?: 0
            val colWidths = FloatArray(cols) { col ->
                measuredRows.maxOfOrNull { row -> row.getOrNull(col)?.width ?: 0f } ?: 0f
            }
            val rowAscents = FloatArray(measuredRows.size) { r ->
                measuredRows[r].maxOfOrNull { it.ascent } ?: (cellSize * 0.4f * pxPerSp)
            }
            val rowDescents = FloatArray(measuredRows.size) { r ->
                measuredRows[r].maxOfOrNull { it.descent } ?: (cellSize * 0.2f * pxPerSp)
            }
            val colGap = (if (displayMode) 9f else 6.5f) * density.density
            val rowGap = (if (displayMode) 6f else 3.5f) * density.density
            val bodyW = colWidths.sum() + max(0, cols - 1) * colGap
            val bodyH = (rowAscents.indices.sumOf { (rowAscents[it] + rowDescents[it]).toDouble() }.toFloat()) +
                max(0, measuredRows.size - 1) * rowGap
            val (leftD, rightD) = when (node.kind) {
                MathMatrixKind.PAREN -> "(" to ")"
                MathMatrixKind.BRACKET -> "[" to "]"
                MathMatrixKind.BRACE -> "{" to "}"
                MathMatrixKind.PIPE -> "|" to "|"
                MathMatrixKind.DOUBLE_PIPE -> "∥" to "∥"
                MathMatrixKind.CASES -> "{" to ""
                else -> "" to ""
            }
            val lw = if (leftD.isEmpty()) 0f else 6.5f * density.density
            val rw = if (rightD.isEmpty()) 0f else 6.5f * density.density
            val halfH = bodyH * 0.5f + 1.5f * density.density
            MathBox(lw + bodyW + rw, halfH, halfH) { x, axisY, out ->
                if (lw > 0f) out.add(DrawCmd.DelimDraw(leftD, x, axisY - halfH, axisY + halfH, lw, color, ruleStroke))
                var ry = axisY - bodyH * 0.5f
                for (r in measuredRows.indices) {
                    val rAxis = ry + rowAscents[r]
                    var cx = x + lw
                    for (c in 0 until cols) {
                        val cell = measuredRows[r].getOrNull(c)
                        if (cell != null) {
                            val alignLeft = node.kind == MathMatrixKind.CASES || node.kind == MathMatrixKind.ALIGNED
                            val cellX = if (alignLeft) cx else cx + (colWidths[c] - cell.width) * 0.5f
                            cell.draw(cellX, rAxis, out)
                        }
                        cx += colWidths[c] + colGap
                    }
                    ry += rowAscents[r] + rowDescents[r] + rowGap
                }
                if (rw > 0f) out.add(DrawCmd.DelimDraw(rightD, x + lw + bodyW, axisY - halfH, axisY + halfH, rw, color, ruleStroke))
            }
        }
        is LatexNode.Colored -> {
            val fg = parseColorOrNull(node.color) ?: color
            val bg = parseColorOrNull(node.bgColor)
            val body = layoutAstNode(node.body, fontSizeSp, fg, measurer, density, displayMode)
            if (bg == null) body else {
                val pad = 2f * density.density
                MathBox(body.width + pad * 2, body.ascent + pad, body.descent + pad) { x, axisY, out ->
                    out.add(DrawCmd.RectDraw(bg, x, axisY - body.ascent - pad, body.width + pad * 2, body.height + pad * 2))
                    body.draw(x + pad, axisY, out)
                }
            }
        }
        is LatexNode.StackRel -> {
            val base = layoutAstNode(node.base, fontSizeSp, color, measurer, density, displayMode)
            val subSize = max(9f, fontSizeSp * 0.65f)
            val topBox = node.top?.let { layoutAstNode(it, subSize, color, measurer, density) }
            val botBox = node.bottom?.let { layoutAstNode(it, subSize, color, measurer, density) }
            val gap = 1.2f * density.density
            val maxW = max(base.width, max(topBox?.width ?: 0f, botBox?.width ?: 0f))
            val asc = base.ascent + (topBox?.let { it.height + gap } ?: 0f)
            val desc = base.descent + (botBox?.let { it.height + gap } ?: 0f)
            MathBox(maxW, asc, desc) { x, axisY, out ->
                base.draw(x + (maxW - base.width) * 0.5f, axisY, out)
                topBox?.draw(x + (maxW - topBox.width) * 0.5f, axisY - base.ascent - gap - topBox.descent, out)
                botBox?.draw(x + (maxW - botBox.width) * 0.5f, axisY + base.descent + gap + botBox.ascent, out)
            }
        }
    }
}

internal fun measureInlineLatexFormula(
    tex: String,
    fontSizeSp: Float,
    color: Color,
    measurer: TextMeasurer,
    density: Density,
    displayMode: Boolean = false,
): MeasuredLatexFormula {
    val ast = parseLatexInlineAst(tex)
    val box = layoutAstNode(ast, fontSizeSp, color, measurer, density, displayMode)
    val padX = (if (displayMode) 4f else 2f) * density.density
    val padY = (if (displayMode) 3f else 1f) * density.density
    val wPx = max(1f, box.width + padX * 2f)
    val hPx: Float
    val axisY: Float
    if (displayMode) {
        hPx = max(1f, box.height + padY * 2f)
        axisY = padY + box.ascent
    } else {
        val halfSpan = max(box.ascent, box.descent) + padY
        hPx = max(1f, halfSpan * 2f)
        axisY = halfSpan
    }
    val cmds = mutableListOf<DrawCmd>()
    box.draw(padX, axisY, cmds)
    return with(density) {
        MeasuredLatexFormula(
            widthPx = wPx,
            heightPx = hPx,
            widthSp = wPx.toSp(),
            heightSp = hPx.toSp(),
            drawCommands = cmds,
        )
    }
}

private fun DrawScope.drawMathDelimiter(cmd: DrawCmd.DelimDraw) {
    val top = cmd.top + 1f
    val bot = cmd.bottom - 1f
    val mid = (top + bot) * 0.5f
    val l = cmd.x + 1.5f
    val r = cmd.x + cmd.w - 1.5f
    val stroke = Stroke(width = cmd.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val p = Path()
    when (cmd.delim) {
        "(" -> {
            p.moveTo(r, top)
            p.quadraticTo(l - (r - l) * 0.35f, mid, r, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        ")" -> {
            p.moveTo(l, top)
            p.quadraticTo(r + (r - l) * 0.35f, mid, l, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        "[" -> {
            p.moveTo(r, top); p.lineTo(l + 1f, top); p.lineTo(l + 1f, bot); p.lineTo(r, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        "]" -> {
            p.moveTo(l, top); p.lineTo(r - 1f, top); p.lineTo(r - 1f, bot); p.lineTo(l, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        "{" -> {
            val mx = (l + r) * 0.5f
            p.moveTo(r, top)
            p.quadraticTo(mx, top, mx, mid - 2.5f)
            p.lineTo(l, mid)
            p.lineTo(mx, mid + 2.5f)
            p.quadraticTo(mx, bot, r, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        "}" -> {
            val mx = (l + r) * 0.5f
            p.moveTo(l, top)
            p.quadraticTo(mx, top, mx, mid - 2.5f)
            p.lineTo(r, mid)
            p.lineTo(mx, mid + 2.5f)
            p.quadraticTo(mx, bot, l, bot)
            drawPath(p, cmd.color, style = stroke)
        }
        "|" -> drawLine(cmd.color, Offset((l + r) * 0.5f, top), Offset((l + r) * 0.5f, bot), cmd.strokeWidth)
        "∥" -> {
            val m1 = l + (r - l) * 0.35f
            val m2 = l + (r - l) * 0.65f
            drawLine(cmd.color, Offset(m1, top), Offset(m1, bot), cmd.strokeWidth)
            drawLine(cmd.color, Offset(m2, top), Offset(m2, bot), cmd.strokeWidth)
        }
    }
}

@Composable
internal fun InlineLatexCanvas(measured: MeasuredLatexFormula) {
    val density = LocalDensity.current
    // 防御：测量值异常放大时封顶画布尺寸，避免把超界尺寸带进 Constraints 测量。
    val wDp = with(density) { measured.widthPx.toDp().value.coerceAtMost(1600f).dp }
    val hDp = with(density) { measured.heightPx.toDp().value.coerceAtMost(1600f).dp }
    Canvas(Modifier.size(wDp, hDp)) {
        for (cmd in measured.drawCommands) {
            when (cmd) {
                is DrawCmd.RectDraw -> drawRoundRect(
                    color = cmd.color,
                    topLeft = Offset(cmd.x, cmd.y),
                    size = Size(cmd.w, cmd.h),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
                is DrawCmd.LineDraw -> drawLine(
                    color = cmd.color,
                    start = Offset(cmd.x1, cmd.y1),
                    end = Offset(cmd.x2, cmd.y2),
                    strokeWidth = cmd.strokeWidth,
                    cap = StrokeCap.Round,
                )
                is DrawCmd.PathDraw -> if (cmd.points.size >= 2) {
                    val p = Path()
                    p.moveTo(cmd.points[0].first, cmd.points[0].second)
                    for (idx in 1 until cmd.points.size) {
                        p.lineTo(cmd.points[idx].first, cmd.points[idx].second)
                    }
                    drawPath(p, cmd.color, style = Stroke(width = cmd.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                is DrawCmd.DelimDraw -> drawMathDelimiter(cmd)
                is DrawCmd.TextDraw -> drawText(cmd.layout, topLeft = Offset(cmd.x, cmd.y))
            }
        }
    }
}
