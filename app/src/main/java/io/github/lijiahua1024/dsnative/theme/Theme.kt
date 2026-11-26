package io.github.lijiahua1024.dsnative.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

private val preloadedVectors = ConcurrentHashMap<Int, ImageVector>()

/** Decodes XML vector icons on a background thread so UI frames never block on XML parsing. */
fun preloadVectorIcons(context: android.content.Context, vararg ids: Int) {
    val res = context.resources
    val theme = context.theme
    for (id in ids) {
        if (!preloadedVectors.containsKey(id)) {
            runCatching { ImageVector.vectorResource(theme, res, id) }
                .getOrNull()
                ?.let { preloadedVectors[id] = it }
        }
    }
}

@Composable
fun dsPainterResource(resId: Int): Painter {
    val cached = preloadedVectors[resId]
    if (cached != null) return rememberVectorPainter(cached)
    return androidx.compose.ui.res.painterResource(resId)
}

/** DeepSeek design tokens, mirrored from src/app/theme.css of the WebView reference. */
@Immutable
data class DsColors(
    val dark: Boolean,
    // surfaces
    val bgBase: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgSkeleton: Color,
    // borders
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderInverted: Color,
    val borderL2DarkmodeThin: Color,
    // brand
    val brandPrimary: Color,
    val brandPrimaryInvert: Color,
    val brandText: Color,
    val buttonPrimaryHover: Color,
    val buttonPrimaryFill: Color,
    // labels
    val labelPrimary: Color,
    val labelPrimaryForeground: Color,
    val labelPrimaryInverted: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    // interactive
    val interactiveBgHover: Color,
    val interactiveBgActive: Color,
    val interactiveBgHoverAccent: Color,
    val interactiveBgHoverDanger: Color,
    // buttons
    val buttonContrastFill: Color,
    val buttonElevatedFill: Color,
    val buttonGhostActiveFill: Color,
    val buttonGhostActiveBorder: Color,
    val buttonGhostActiveHover: Color,
    // markdown
    val markdownCitation: Color,
    val markdownCodeBlockBanner: Color,
    val markdownCodeBlock: Color,
    val markdownInlineCode: Color,
    val markdownPlaceholder: Color,
    val markdownTag: Color,
    // states
    val stateErrorPrimary: Color,
    val stateErrorSecondary: Color,
    val toastBg: Color,
    val tooltipBg: Color,
    // search results sheet (ref AndroidSearchPanel.module.css overrides)
    val searchResultDate: Color,
    val searchResultSnippet: Color,
    val searchResultNumber: Color,
    // specific
    val bubble: Color,
    val inputMajor: Color,
    val loginInput: Color,
    val menu: Color,
    val selector: Color,
    val sidebarFill: Color,
    val sidebarNavItemActiveAccent: Color,
    val sidebarNavItemHover: Color,
    // android thinking block
    val thinkingText: Color,
    val thinkingDot: Color,
    val thinkingLine: Color,
    val searchSummaryText: Color,
)

val LightColors = DsColors(
    dark = false,
    bgBase = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    bgSkeleton = Color(0x0A000000),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    brandPrimary = Color(0xFF3964FE),
    brandPrimaryInvert = Color(0xFF3964FE),
    borderInverted = Color(0x00000000),
    borderL2DarkmodeThin = Color(0x1A000000),
    brandText = Color(0xFF426EFE),
    buttonPrimaryFill = Color(0xFF3964FE),
    buttonPrimaryHover = Color(0xFF5686FE),
    labelPrimary = Color(0xFF0F0F0F),
    labelPrimaryForeground = Color(0xFFFFFFFF),
    labelPrimaryInverted = Color(0xFFFFFFFF),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFB9BABB),
    labelDimmed = Color(0xFFE1E5EE),
    interactiveBgHover = Color(0x0F263148),
    interactiveBgActive = Color(0x1A263148),
    interactiveBgHoverAccent = Color(0x24263148),
    interactiveBgHoverDanger = Color(0x0DEC1313),
    buttonContrastFill = Color(0xFF61666B),
    buttonElevatedFill = Color(0xFFFFFFFF),
    buttonGhostActiveFill = Color(0xFFEDF3FE),
    buttonGhostActiveBorder = Color(0xFFD3E2FF),
    buttonGhostActiveHover = Color(0xFFE4EDFD),
    markdownCitation = Color(0xFFEBEEF2),
    markdownCodeBlockBanner = Color(0xFFFAFAFA),
    markdownCodeBlock = Color(0xFFFAFAFA),
    markdownInlineCode = Color(0xFFEBEEF2),
    markdownPlaceholder = Color(0xFFF5F6F7),
    markdownTag = Color(0xFFF1F3F5),
    stateErrorPrimary = Color(0xFFEC1313),
    stateErrorSecondary = Color(0xFFF25A5A),
    toastBg = Color(0xFF353638),
    tooltipBg = Color(0xFF2C2C2E),
    searchResultDate = Color(0xFFA2A4A6),
    searchResultSnippet = Color(0xFF8E9094),
    searchResultNumber = Color(0xFF7D7F85),
    bubble = Color(0xFFEDF3FE),
    inputMajor = Color(0xFFFFFFFF),
    loginInput = Color(0xFFF9FAFB),
    menu = Color(0xFFFFFFFF),
    selector = Color(0xFFF1F3F5),
    sidebarFill = Color(0xFFF9FAFB),
    sidebarNavItemActiveAccent = Color(0xFFE4EDFD),
    sidebarNavItemHover = Color(0xFFF1F3F5),
    thinkingText = Color(0xFF7D7F85),
    thinkingDot = Color(0xFFA2A4A6),
    thinkingLine = Color(0xFFEDEDED),
    searchSummaryText = Color(0xFF545557),
)

val DarkColors = DsColors(
    dark = true,
    bgBase = Color(0xFF0F0F0F),
    bgLayer2 = Color(0xFF242424),
    bgLayer3 = Color(0xFF3C3C3D),
    bgSkeleton = Color(0x14FFFFFF),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    brandPrimary = Color(0xFF5686FE),
    brandPrimaryInvert = Color(0xFFFFFFFF),
    borderInverted = Color(0x0FFFFFFF),
    borderL2DarkmodeThin = Color(0x0FFFFFFF),
    brandText = Color(0xFF6799FE),
    buttonPrimaryFill = Color(0xFF5686FE),
    buttonPrimaryHover = Color(0xFF3964FE),
    labelPrimary = Color(0xFFF9FAFB),
    labelPrimaryForeground = Color(0xFFFFFFFF),
    labelPrimaryInverted = Color(0xFF353638),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),
    interactiveBgHover = Color(0x14FFFFFF),
    interactiveBgActive = Color(0x24FFFFFF),
    interactiveBgHoverAccent = Color(0x3DFFFFFF),
    interactiveBgHoverDanger = Color(0x26F25A5A),
    buttonContrastFill = Color(0xFFF9FAFB),
    buttonElevatedFill = Color(0xFF43454A),
    buttonGhostActiveFill = Color(0xFF283142),
    buttonGhostActiveBorder = Color(0xFF33436B),
    buttonGhostActiveHover = Color(0xFF34415B),
    markdownCitation = Color(0xFF353638),
    markdownCodeBlockBanner = Color(0xFF2C2C2E),
    markdownCodeBlock = Color(0xFF1B1B1C),
    markdownInlineCode = Color(0xFF2C2C2E),
    markdownPlaceholder = Color(0xFF2C2C2E),
    markdownTag = Color(0xFF2C2C2E),
    stateErrorPrimary = Color(0xFFF25A5A),
    stateErrorSecondary = Color(0xFFF25A5A),
    toastBg = Color(0xFF43454A),
    tooltipBg = Color(0xFF43454A),
    searchResultDate = Color(0xFF8E9094),
    searchResultSnippet = Color(0xFF8E9094),
    searchResultNumber = Color(0xFFB9BABB),
    bubble = Color(0xFF333333),
    inputMajor = Color(0xFF242424),
    loginInput = Color(0xFF1B1B1C),
    menu = Color(0xFF2C2C2E),
    selector = Color(0xFF3C3C3D),
    sidebarFill = Color(0xFF141414),
    sidebarNavItemActiveAccent = Color(0xFF283142),
    sidebarNavItemHover = Color(0xFF2C2C2E),
    thinkingText = Color(0xFFADB2B8),
    thinkingDot = Color(0xFF81858C),
    thinkingLine = Color(0xFF43454A),
    searchSummaryText = Color(0xFFE5E5E5),
)

val LocalDsColors = androidx.compose.runtime.staticCompositionLocalOf { LightColors }
val LocalDsFontFamily = androidx.compose.runtime.staticCompositionLocalOf<
        androidx.compose.ui.text.font.FontFamily> {
    androidx.compose.ui.text.font.FontFamily.Default
}

object DsTheme {
    val colors: DsColors
        @androidx.compose.runtime.Composable get() = LocalDsColors.current
    val fontFamily: androidx.compose.ui.text.font.FontFamily
        @androidx.compose.runtime.Composable get() = LocalDsFontFamily.current

    /** ref :active 按压着色非瞬时切换：背景/不透明度走
     *  --ds-transition-duration(200ms) 淡入，松开同样淡出。 */
    @androidx.compose.runtime.Composable
    fun pressTint(pressed: Boolean, color: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
        androidx.compose.animation.animateColorAsState(
            if (pressed) color else color.copy(alpha = 0f),
            androidx.compose.animation.core.tween(200), label = "pressTint",
        ).value

    /** 透明度按压态（ref opacity 变化走同一 200ms 过渡） */
    @androidx.compose.runtime.Composable
    fun pressAlpha(pressed: Boolean, pressedAlpha: Float, restAlpha: Float = 1f): Float =
        androidx.compose.animation.core.animateFloatAsState(
            if (pressed) pressedAlpha else restAlpha,
            androidx.compose.animation.core.tween(200), label = "pressAlpha",
        ).value
}

/** Draw a diffused CSS-like Gaussian drop shadow outside a rounded rectangle without clipping. */
fun Modifier.softShadow(
    cornerRadius: Dp,
    blurRadius: Dp,
    offsetY: Dp = 0.dp,
    offsetX: Dp = 0.dp,
    color: Color = Color.Black.copy(alpha = 0.08f),
): Modifier = if (color.alpha <= 0f) this else this.drawWithCache {
    val paint = androidx.compose.ui.graphics.Paint().also { p ->
        val fw = p.asFrameworkPaint()
        fw.isAntiAlias = true
        fw.color = color.toArgb()
        fw.maskFilter = android.graphics.BlurMaskFilter(
            blurRadius.toPx().coerceAtLeast(1f),
            android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
    }
    val dx = offsetX.toPx()
    val dy = offsetY.toPx()
    val r = cornerRadius.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            canvas.drawRoundRect(dx, dy, size.width + dx, size.height + dy, r, r, paint)
        }
    }
}

@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun DsTheme(colors: DsColors, content: @androidx.compose.runtime.Composable () -> Unit) {
    // Use system default variable sans-serif so Medium/SemiBold/Bold weights render accurately on real devices.
    val fontFamily = androidx.compose.ui.text.font.FontFamily.Default
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDsColors provides colors,
        LocalDsFontFamily provides fontFamily,
        androidx.compose.material3.LocalTextStyle provides
            androidx.compose.ui.text.TextStyle(fontFamily = fontFamily),
        androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement provides false,
    ) { content() }
}
