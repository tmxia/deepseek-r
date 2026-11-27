@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.lijiahua1024.dsnative.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.DebugLog
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.lijiahua1024.dsnative.ui.sider.Sider
import io.github.lijiahua1024.dsnative.ui.session.SessionView
import io.github.lijiahua1024.dsnative.ui.settings.AndroidSettings
import io.github.lijiahua1024.dsnative.ui.settings.ApiKeyScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// From useAndroidDrawerGesture.ts / Sider.module.css of the WebView reference:
// drawer edge = min(width - 68.4px, 380px); gain 1.031; touchSlop 8px;
// fling: velocity window 60ms, pause cap 56ms, min 0.33px/ms; settle p>=0.5
// or fling direction sign.
@Composable
fun App(settings: SettingsStore, chatStore: ChatStore) {
    val themePref by settings.themePreference.collectAsState()
    io.github.lijiahua1024.dsnative.theme.Strings.locale = settings.localePreference.collectAsState().value
    val dark = when (themePref) {
        io.github.lijiahua1024.dsnative.data.ThemePreference.LIGHT -> false
        io.github.lijiahua1024.dsnative.data.ThemePreference.DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (dark) io.github.lijiahua1024.dsnative.theme.DarkColors else io.github.lijiahua1024.dsnative.theme.LightColors
    val fontSizeSetting by settings.fontSize.collectAsState()
    val apiKey by settings.apiKey.collectAsState()
    var nav by remember { mutableStateOf<NavState>(NavState.Chat) }

    // 系统栏图标对比度跟随应用主题（ref SystemBars.setAppearance）。
    val barView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(dark) {
        (barView.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, barView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    DsTheme(colors) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val baseDensity = LocalDensity.current
        val designDensity = if (constraints.maxWidth > 0 && constraints.maxHeight > 0) {
            min(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()) / 360f
        } else baseDensity.density
        val contentFontScale = fontSizeSetting?.let { 1f + (it - 2) * 0.1f } ?: baseDensity.fontScale
        val contentDensity = Density(designDensity, fontScale = contentFontScale)
        androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides contentDensity) {
        // ref base/Modal .overlay has backdrop-filter: blur(2px), but
        // RenderEffect is a no-op on this emulator's GPU path, so the
        // backdrop relies on the dim overlay only (see decisions.md).
        if (apiKey == null) {
            Box(Modifier.fillMaxSize().background(DsTheme.colors.bgBase).statusBarsPadding()) {
                ApiKeyScreen(settings)
            }
            return@CompositionLocalProvider
        }
        val newChatRequests by chatStore.newChatRequests.collectAsState()
        var coldStartWelcome by remember { mutableStateOf(true) }
        LaunchedEffect(newChatRequests) { coldStartWelcome = newChatRequests == 0 }
        val isCurrentSessionEmpty by remember(chatStore) {
            combine(chatStore.activeId, chatStore.sessionCache) { id, cache ->
                cache[id]?.let { io.github.lijiahua1024.dsnative.data.visibleMessages(it).isNotEmpty() } != true
            }.distinctUntilChanged()
        }.collectAsState(initial = true)
        val freePan = nav == NavState.Chat && isCurrentSessionEmpty
        Box(Modifier.fillMaxSize()) {
            val settingsScope = rememberCoroutineScope()
            val settingsCtrl = remember {
                TwoPhaseController(if (nav == NavState.Settings) "settings" else "chat", settingsScope) { settled ->
                    if (settled == "chat" && nav == NavState.Settings) nav = NavState.Chat
                }
            }
            LaunchedEffect(nav) {
                if (nav == NavState.Settings && settingsCtrl.current != "settings") {
                    settingsCtrl.navigate("settings", isForward = true)
                } else if (nav == NavState.Chat && settingsCtrl.current != "chat" && !settingsCtrl.interactiveSeek) {
                    settingsCtrl.navigate("chat", isForward = false)
                }
            }
            val settingsMounted = settingsCtrl.current == "settings" || settingsCtrl.outgoing == "settings"
            val collapseDrawerBehindSettings = settingsCtrl.current == "settings" && settingsCtrl.outgoing == null
            val chatAlphaProvider = remember(settingsCtrl) { { settingsCtrl.layerAlpha("chat") } }
            val chatShiftDpProvider = remember(settingsCtrl) { { settingsCtrl.layerShiftDp("chat") } }
            val settingsBgAlphaProvider = remember(settingsCtrl) { { settingsCtrl.overlayBgAlpha("settings") } }
            val settingsAlphaProvider = remember(settingsCtrl) { { settingsCtrl.layerAlpha("settings") } }
            val settingsShiftDpProvider = remember(settingsCtrl) { { settingsCtrl.layerShiftDp("settings") } }
            BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
                if (settingsMounted) {
                    alpha = chatAlphaProvider()
                    translationX = with(contentDensity) { chatShiftDpProvider().dp.toPx() }
                } else {
                    alpha = 1f
                    translationX = 0f
                }
            }) {
                val wideLayout = maxWidth >= 768.dp
                val siderCollapsed by settings.siderCollapsed.collectAsState()
                if (wideLayout) {
                    Row(Modifier.fillMaxSize()) {
                        if (!siderCollapsed) {
                            Box(
                                Modifier.width(260.dp)
                                    .fillMaxHeight()
                                    .background(DsTheme.colors.bgBase),
                            ) {
                                Sider(
                                    settings = settings,
                                    chatStore = chatStore,
                                    onNavigateSettings = { nav = NavState.Settings },
                                    onClose = {},
                                    isWideLayout = true,
                                    onCollapseWide = { settings.setSiderCollapsed(true) },
                                )
                            }
                            Box(
                                Modifier.width(1.dp)
                                    .fillMaxHeight()
                                    .background(DsTheme.colors.borderL1),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            SessionView(
                                settings,
                                chatStore,
                                onOpenDrawer = { settings.setSiderCollapsed(false) },
                                onOpenSettings = { nav = NavState.Settings },
                                coldStartWelcome = coldStartWelcome,
                                greetingIdentity = newChatRequests,
                                showMenuButton = siderCollapsed,
                                isWideCollapsed = siderCollapsed,
                            )
                            io.github.lijiahua1024.dsnative.ui.session.DsToastHost()
                        }
                    }
                } else {
                    var drawerSearchExpanded by remember { mutableStateOf(false) }
                    DrawerFrame(
                        nav = nav,
                        dark = dark,
                        freePan = freePan,
                        closeImmediately = false,
                        collapseBehindOverlay = collapseDrawerBehindSettings,
                        searchExpanded = drawerSearchExpanded,
                        onCollapseSearch = { drawerSearchExpanded = false },
                        onNavigate = { nav = it },
                        siderContent = { close ->
                            Sider(settings, chatStore,
                                onNavigateSettings = {
                                    drawerSearchExpanded = false
                                    nav = NavState.Settings
                                },
                                onClose = {
                                    drawerSearchExpanded = false
                                    close()
                                },
                                searchExpanded = drawerSearchExpanded,
                                onSearchExpandedChange = { drawerSearchExpanded = it })
                        },
                    ) { open ->
                        SessionView(
                            settings,
                            chatStore,
                            onOpenDrawer = open,
                            onOpenSettings = { nav = NavState.Settings },
                            coldStartWelcome = coldStartWelcome,
                            greetingIdentity = newChatRequests,
                        )
                    }
                }
            }
            if (settingsMounted) {
                AndroidSettings(
                    settings = settings,
                    chatStore = chatStore,
                    bgAlpha = settingsBgAlphaProvider,
                    contentAlpha = settingsAlphaProvider,
                    contentShiftDp = settingsShiftDpProvider,
                    onRootBackProgress = { settingsCtrl.beginBackSeek("chat", it) },
                    onRootBackCommit = { settingsCtrl.commitBackSeek("chat") },
                    onRootBackCancel = { settingsCtrl.cancelBackSeek() },
                    onBack = { nav = NavState.Chat },
                )
                io.github.lijiahua1024.dsnative.ui.session.DsToastHost()
            }
        }
        }
        }
    }
}

@Composable
fun DrawerFrame(
    nav: NavState,
    dark: Boolean,
    freePan: Boolean,
    closeImmediately: Boolean = false,
    collapseBehindOverlay: Boolean = false,
    searchExpanded: Boolean = false,
    onCollapseSearch: () -> Unit = {},
    onNavigate: (NavState) -> Unit,
    siderContent: @Composable (close: () -> Unit) -> Unit,
    mainContent: @Composable (open: () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val drawerWidthPx = with(density) { min(widthPx * 0.80f, 360.dp.toPx()) }
        val edgePx = with(density) { min(drawerWidthPx + 9.dp.toPx(), widthPx - 63.dp.toPx()) }
        var progress by remember { mutableFloatStateOf(0f) }
        val drawerVisible by remember { derivedStateOf { progress > 0f } }
        val drawerPastHalf by remember { derivedStateOf { progress > 0.5f } }
        val drawerAtRestOpen by remember { derivedStateOf { progress >= 0.999f } }
        var siderWarmed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            kotlinx.coroutines.yield()
            siderWarmed = true
        }
        val searchFrac by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (searchExpanded && drawerPastHalf) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "drawerSearchExpand",
        )
        val curSearchExpanded by rememberUpdatedState(searchExpanded)
        val curOnCollapseSearch by rememberUpdatedState(onCollapseSearch)
        LaunchedEffect(Unit) {
            snapshotFlow { progress <= 0.001f && curSearchExpanded }
                .distinctUntilChanged()
                .collect { if (it) curOnCollapseSearch() }
        }
        // Official drawer settle timing ~160ms ease-out.
        val settleScope = rememberCoroutineScope()
        val settleEasing = androidx.compose.animation.core.CubicBezierEasing(0.18f, 0.23f, 0.32f, 1f)
        var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        fun settleTo(target: Float) {
            val t = target.coerceIn(0f, 1f)
            val start = progress
            DebugLog.log("drawer", "settleTo $t (start=$start)")
            settleJob?.cancel()
            if (kotlin.math.abs(t - start) < 0.001f) { progress = t; return }
            settleJob = settleScope.launch {
                androidx.compose.animation.core.animate(
                    initialValue = start, targetValue = t,
                    animationSpec = androidx.compose.animation.core.tween(160, easing = settleEasing),
                ) { v, _ -> progress = v }
            }
        }

        LaunchedEffect(collapseBehindOverlay) {
            if (collapseBehindOverlay && progress > 0f) {
                settleJob?.cancel()
                progress = 0f
            }
        }

        // Back gesture closes an open drawer only while on Chat nav; latch active state so first move doesn't self-cancel.
        var drawerBackActive by remember { mutableStateOf(false) }
        androidx.activity.compose.PredictiveBackHandler(enabled = nav == NavState.Chat && !searchExpanded && (drawerAtRestOpen || drawerBackActive)) { flow ->
            drawerBackActive = true
            try {
                flow.collect { ev -> progress = (1f - ev.progress).coerceIn(0f, 1f) }
                drawerBackActive = false
                settleTo(0f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                drawerBackActive = false
                settleTo(1f)
                throw e
            }
        }

        val curFreePan by rememberUpdatedState(freePan)

        class Gesture {
            var downX = 0f; var downY = 0f
            var startedOpen = false
            var start = -1f // -1 = not started (progress is 0..1)
            var current = 0f
            var cancelled = false
            val samples = java.util.ArrayDeque<Pair<Float, Long>>()
        }
        DisposableEffect(Unit) {
            DrawerGestureBus.progressProbe = { progress }
            onDispose { DrawerGestureBus.progressProbe = null }
        }
        DisposableEffect(edgePx, nav) {
            val slopPx = 8f * density.density
            var g: Gesture? = null
            fun record(g: Gesture, x: Float, t: Long) {
                if (t <= g.samples.last().second) return
                g.samples.add(x to t)
                while (g.samples.size > 1 && t - g.samples.first().second > 60) g.samples.removeFirst()
            }
            fun evalMove(g: Gesture, x: Float, y: Float, t: Long) {
                record(g, x, t)
                val dx = x - g.downX
                val horizontal = abs(dx)
                if (g.start < 0f) {
                    val dy = abs(y - g.downY)
                    val canScrollVertically = !curFreePan || g.startedOpen
                    if (canScrollVertically && dy >= slopPx && dy > horizontal) {
                        g.cancelled = true
                        return
                    }
                    if (horizontal <= slopPx || (canScrollVertically && horizontal < dy)) return
                    settleJob?.cancel()
                    g.start = progress
                    if ((g.start <= 0.001f && dx < 0) || (g.start >= 0.999f && dx > 0)) {
                        g.start = -1f
                        return
                    }
                }
                val distance = kotlin.math.sign(dx) * max(0f, horizontal - slopPx)
                g.current = (g.start + (1.075f * distance) / edgePx).coerceIn(0f, 1f)
                progress = g.current
                DrawerGestureBus.trace.add(Triple(t, x, g.current))
            }
            DrawerGestureBus.handler = handler@{ ev ->
                if (nav != NavState.Chat || DrawerGestureBus.isBlocked()) {
                    val active = g
                    if (active != null && active.start >= 0f) settleTo(if (active.startedOpen) 1f else 0f)
                    g = null
                    return@handler
                }
                var cur = g
                if (cur == null) {
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        if (DrawerGestureBus.inExcludedZone(ev.x, ev.y)) return@handler
                        cur = Gesture()
                        g = cur
                        DrawerGestureBus.trace.clear()
                        cur.downX = ev.x; cur.downY = ev.y
                        cur.startedOpen = progress >= 0.999f
                        cur.samples.add(ev.x to ev.eventTime)
                        DebugLog.log("drawer", "gesture down x=${ev.x.toInt()} startedOpen=${cur.startedOpen} progress=$progress")
                    } else return@handler
                }
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_MOVE,
                    android.view.MotionEvent.ACTION_UP -> {
                        for (i in 0 until ev.historySize) {
                            evalMove(cur, ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
                            if (cur.cancelled) break
                        }
                        if (!cur.cancelled) evalMove(cur, ev.x, ev.y, ev.eventTime)
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            if (cur.start >= 0f) {
                                val now = ev.eventTime
                                val first = cur.samples.first()
                                val last = cur.samples.last()
                                val velocity = if (first != last && now - last.second < 56)
                                    (last.first - first.first) / (last.second - first.second) else 0f
                                val flick = abs(velocity) >= 0.28f * density.density
                                val open = if (cur.cancelled) cur.startedOpen else if (flick) velocity > 0 else cur.current >= 0.5f
                                settleTo(if (open) 1f else 0f)
                            }
                            g = null
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (cur.start >= 0f) settleTo(if (cur.startedOpen) 1f else 0f)
                        g = null
                    }
                }
            }
            onDispose { DrawerGestureBus.handler = null }
        }

        Box(Modifier.fillMaxSize()) {
            val activeDrawerWidthPx = drawerWidthPx + (widthPx - drawerWidthPx) * searchFrac
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DsTheme.colors.bgBase)
            ) {
                Box(
                    Modifier.width(with(density) { activeDrawerWidthPx.toDp() }).fillMaxSize()
                        .graphicsLayer { translationX = -activeDrawerWidthPx * (1f - progress) }
                ) {
                    if (siderWarmed || drawerVisible) {
                        siderContent {
                            if (closeImmediately) {
                                settleJob?.cancel()
                                progress = 0f
                            } else {
                                settleTo(0f)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val p = progress
                    if (p > 0f && p < 1f) drawRect(Color.Black, alpha = 0.21f * (1f - p))
                }
            )
            // Main content layer: 与 Web 一致，抽屉开合只做纯水平平推，去掉 scale 收缩，
            // 避免卡片上下边缘随缩放反复光栅化产生顶底横线闪。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = progress
                        val mainOffsetX = (edgePx * (1f - searchFrac) + widthPx * searchFrac) * p
                        translationX = mainOffsetX
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // ref .main：圆角为开合两端离散值（关闭 0 / 打开 36dp），不逐帧插值，
                            // 避免对整块消息容器做圆角裁剪重绘。
                            val open = progress > 0.5f
                            shape = if (open)
                                androidx.compose.foundation.shape.RoundedCornerShape(36.dp)
                            else RectangleShape
                            clip = open
                        }
                        .background(DsTheme.colors.bgBase)
                ) {
                    mainContent { settleTo(1f) }
                    io.github.lijiahua1024.dsnative.ui.session.DsToastHost()
                    if (drawerVisible) {
                        // ref: .main::after — washes toward bg (light) or #232324 (blend ~#1D1D1D) card (dark)
                        Box(Modifier.fillMaxSize()
                            .graphicsLayer { alpha = 0.7f * progress }
                            .background(if (dark) Color(0xFF232324) else DsTheme.colors.bgBase)
                            .semantics { contentDescription = Strings.get("collapseSiderbarButton") }
                            .pointerInput(Unit) {
                                detectTapGestures { settleTo(0f) }
                            })
                    }
                }
            }
        }
    }
}

// Cumulative scroll-offset probes for the fling-equivalence harness
// (MotionServer `scroll`/`scrollreset`). Accumulates the px each list actually
// consumed, including fling deltas, matching the ref's scrollTop metric.
object ScrollProbe {
    @Volatile var chat = 0f
    @Volatile var sider = 0f
}

class ScrollTracker(val name: String) : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    @Volatile private var pre = 0f
    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        pre = available.y
        return androidx.compose.ui.geometry.Offset.Zero
    }
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        val used = pre - available.y
        if (name == "chat") ScrollProbe.chat += used else ScrollProbe.sider += used
        return androidx.compose.ui.geometry.Offset.Zero
    }
}

// Feeds raw window-level MotionEvents (true historical times) to the active
// drawer gesture handler; MainActivity.dispatchTouchEvent calls this first.
object DrawerGestureBus {
    var handler: ((android.view.MotionEvent) -> Unit)? = null
    @Volatile var lastDownAt = -1L
    @Volatile var lastDownX = -1f
    @Volatile var progressProbe: (() -> Float)? = null
    val trace = java.util.concurrent.CopyOnWriteArrayList<Triple<Long, Float, Float>>()
    private val blockers = java.util.Collections.synchronizedSet(mutableSetOf<Any>())
    private val excludeNodes = java.util.concurrent.ConcurrentHashMap<Any, androidx.compose.ui.layout.LayoutCoordinates>()

    fun addBlocker(key: Any) { blockers.add(key) }
    fun removeBlocker(key: Any) { blockers.remove(key) }
    fun isBlocked(): Boolean = blockers.isNotEmpty()
    fun setExcludeNode(key: Any, coords: androidx.compose.ui.layout.LayoutCoordinates) { excludeNodes[key] = coords }
    fun removeExcludeZone(key: Any) { excludeNodes.remove(key) }
    fun inExcludedZone(x: Float, y: Float): Boolean =
        excludeNodes.values.any { coords ->
            if (!coords.isAttached) return@any false
            val it = coords.boundsInWindow()
            x in it.left..it.right && y in it.top..it.bottom
        }

    fun dispatch(ev: android.view.MotionEvent) {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastDownAt = ev.eventTime; lastDownX = ev.x
        }
        handler?.invoke(ev)
    }
}

@Composable
fun DrawerGestureBlocker(active: Boolean) {
    val key = remember { Any() }
    DisposableEffect(active) {
        if (active) DrawerGestureBus.addBlocker(key) else DrawerGestureBus.removeBlocker(key)
        onDispose { DrawerGestureBus.removeBlocker(key) }
    }
}

@Composable
fun Modifier.excludeFromDrawerGesture(): Modifier {
    val key = remember { Any() }
    DisposableEffect(Unit) {
        onDispose { DrawerGestureBus.removeExcludeZone(key) }
    }
    return this.onPlaced { coords ->
        if (coords.isAttached) DrawerGestureBus.setExcludeNode(key, coords)
        else DrawerGestureBus.removeExcludeZone(key)
    }
}
