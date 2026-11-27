package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import io.github.lijiahua1024.dsnative.theme.dsPainterResource as painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.*
import io.github.lijiahua1024.dsnative.data.replyText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.theme.softShadow
import io.github.lijiahua1024.dsnative.ui.input.ChatInput
import io.github.lijiahua1024.dsnative.ui.input.VoiceInput
import io.github.lijiahua1024.dsnative.ui.input.VoiceOverlay

@Composable
fun SessionView(
    settings: SettingsStore,
    chatStore: ChatStore,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit = {},
    coldStartWelcome: Boolean = false,
    greetingIdentity: Any? = null,
    showMenuButton: Boolean = true,
    isWideCollapsed: Boolean = false,
) {
    val c = DsTheme.colors
    val activeId by chatStore.activeId.collectAsState()
    val sessionCache by chatStore.sessionCache.collectAsState()
    // 同步派生当前会话：activeId 切换时直接取缓存新值，避免 flow 重建首帧仍保留旧会话先渲染一帧。
    val session = activeId?.let { sessionCache[it] }
    LaunchedEffect(activeId) {
        DebugLog.log("session", "activeId=${activeId ?: "null"}")
    }
    val streaming by remember(chatStore) {
        chatStore.streaming
    }.collectAsState()
    val listState = rememberLazyListState()
    var sourcesPanel by remember { mutableStateOf<List<Source>?>(null) }
    // (msg, 气泡 boundsInWindow) —— 参照物菜单在页内 DOM，Popup 窗口
    // 会把强制对比度的黑色导航栏区域刷白，必须用窗口内 overlay
    var bubbleMenu by remember { mutableStateOf<Pair<Message.User, androidx.compose.ui.geometry.Rect>?>(null) }
    var assistantMenu by remember { mutableStateOf<Pair<Message.Assistant, androidx.compose.ui.geometry.Rect>?>(null) }
    var feedbackTarget by remember { mutableStateOf<Pair<Message.Assistant, FeedbackSheetVariant>?>(null) }
    var shareSession by remember { mutableStateOf<Session?>(null) }
    var editTarget by remember { mutableStateOf<Message.User?>(null) }
    var selectTextTarget by remember { mutableStateOf<String?>(null) }
    val rootCtx = LocalContext.current
    val autoRead by settings.autoRead.collectAsState()
    val foldThinking by settings.foldThinking.collectAsState()
    val autoReadScope = rememberCoroutineScope()

    // 状态栏深灰带由 DrawerFrame 在 layer 之上统一绘制（ref 的状态栏是窗口级底色）
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding()) {
    // ref anchors the disclaimer to the bottom overlapping the composer's
    // bottom margin zone; the composer card bottom sits --android-composer-bottom
    // (27css px) above the screen bottom, so the disclaimer must overlay rather
    // than consume flow space below ChatInput.
    Box(Modifier.weight(1f).fillMaxWidth()) {
    CompositionLocalProvider(LocalOpenSources provides { sourcesPanel = it }) {
    Column(Modifier.fillMaxSize()) {
        // Official top bar: 52dp below status bar, 48dp buttons with 24dp icons.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showMenuButton) {
                Box(
                    Modifier.size(48.dp).clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenDrawer,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (isWideCollapsed) R.drawable.ic_sidebar_panel else R.drawable.ic_menu),
                        Strings.get("expandSiderbarButton"),
                        tint = c.labelPrimary,
                        modifier = Modifier.size(if (isWideCollapsed) 20.dp else 24.dp),
                    )
                }
            }
            val headerTitle = session?.title?.ifEmpty { Strings.get("untitledChat") } ?: ""
            Text(headerTitle, fontSize = 15.sp, lineHeight = 17.42.sp,
                fontWeight = FontWeight.Medium, color = c.labelPrimary,
                maxLines = 1, modifier = Modifier.weight(1f).padding(start = if (showMenuButton) 10.dp else 16.dp, end = 8.dp),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val readStatus by io.github.lijiahua1024.dsnative.data.ReadAloud.status.collectAsState()
            val reading = readStatus != io.github.lijiahua1024.dsnative.data.ReadAloud.Status.IDLE
            Box(
                Modifier.size(48.dp).toggleable(
                    value = autoRead,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                ) {
                    val next = !autoRead
                    settings.setAutoRead(next)
                    if (!next) io.github.lijiahua1024.dsnative.data.ReadAloud.stop()
                    DsToast.show(Strings.get(if (next) "androidAutoReadEnabled" else "androidAutoReadDisabled"))
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (autoRead || reading) R.drawable.ic_sound else R.drawable.ic_sound_muted),
                    Strings.get("autoRead"), tint = c.labelPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(48.dp).clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (!chatStore.tryStartNewChat()) {
                            DsToast.show(Strings.get("androidAlreadyInNewChat"))
                        }
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_new_chat), Strings.get("newChat"), tint = c.labelPrimary, modifier = Modifier.size(24.dp))
            }
        }

        val visible = remember(session) { session?.let { visibleMessages(it) } ?: emptyList() }
        val siblingIdsByParent = remember(session?.messages) {
            val msgs = session?.messages ?: return@remember emptyMap()
            val map = HashMap<String?, MutableList<String>>()
            for (m in msgs) {
                map.getOrPut(m.parentId) { ArrayList(2) }.add(m.id)
            }
            map
        }
        val lastAssistant = visible.lastOrNull() as? Message.Assistant
        var streamingMsgId by remember(activeId) { mutableStateOf<String?>(null) }
        LaunchedEffect(activeId, lastAssistant?.id, lastAssistant?.status, lastAssistant?.stopReason) {
            val msg = lastAssistant
            if (shouldAutoReadOnDone(streamingMsgId == msg?.id, true, msg, autoRead)) {
                val safeMsg = msg!!
                val audioManager = rootCtx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                io.github.lijiahua1024.dsnative.data.ReadAloud.toggle(
                    autoReadScope,
                    safeMsg.id,
                    replyText(safeMsg),
                    settings.apiKey.value ?: "",
                    settings.ttsVoice.value,
                    { key -> DsToast.show(Strings.get(key)) },
                    {
                        if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                            DsToast.show(Strings.get("readAloudLowVolume"))
                        }
                    },
                )
            }
            streamingMsgId = if (
                msg != null &&
                (msg.status == ReplyStatus.STREAMING || msg.status == ReplyStatus.PENDING)
            ) {
                msg.id
            } else null
        }
        if (visible.isEmpty()) {
            Welcome(
                Modifier.weight(1f),
                coldStart = coldStartWelcome,
                identity = if (activeId == null) greetingIdentity else activeId,
            )
        } else {
            val scope = rememberCoroutineScope()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val jumpThreshPx = with(density) { 44.8f.dp.toPx() }
            val showJump by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    if (info.totalItemsCount == 0) return@derivedStateOf false
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                    last.index < info.totalItemsCount - 1 ||
                        (last.offset + last.size - info.viewportEndOffset) > jumpThreshPx
                }
            }
            val curSession by rememberUpdatedState(session)
            val curVisible by rememberUpdatedState(visible)
            val curSessionId = session?.id ?: ""
            val onUserMenu = remember {
                { m: Message.User, r: androidx.compose.ui.geometry.Rect -> bubbleMenu = m to r }
            }
            val onAssistantMenu = remember {
                { amsg: Message.Assistant, rect: androidx.compose.ui.geometry.Rect -> assistantMenu = amsg to rect }
            }
            val onAssistantFeedback = remember {
                { amsg: Message.Assistant, variant: FeedbackSheetVariant -> feedbackTarget = amsg to variant }
            }
            val onShareCurrent: () -> Unit = remember {
                {
                    curSession?.let { shareSession = it }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                .nestedScroll(remember { io.github.lijiahua1024.dsnative.ui.ScrollTracker("chat") }),
                contentPadding = PaddingValues(bottom = 20.5.dp)) {
                items(visible.size, key = { visible[it].id }, contentType = { if (visible[it] is Message.User) "user" else "assistant" }) { index ->
                    when (val msg = visible[index]) {
                        is Message.User -> UserBubble(
                            msg = msg,
                            sessionId = curSessionId,
                            siblingIds = siblingIdsByParent[msg.parentId].orEmpty(),
                            uploadReply = visible.getOrNull(index + 1) as? Message.Assistant,
                            chatStore = chatStore,
                            onMenu = onUserMenu,
                        )
                        is Message.Assistant -> AssistantView(
                            msg = msg,
                            sessionId = curSessionId,
                            siblingIds = siblingIdsByParent[msg.parentId].orEmpty(),
                            chatStore = chatStore,
                            isLast = index == visible.size - 1,
                            onOpenSettings = onOpenSettings,
                            foldThinking = foldThinking,
                            onOpenMenu = onAssistantMenu,
                            onFeedback = onAssistantFeedback,
                            onShare = onShareCurrent,
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJump,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 15.dp),
            ) {
                val jSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.size(44.dp)
                        .clickable(jSrc, null) {
                            scope.launch {
                                val msgs = session?.let { visibleMessages(it) } ?: return@launch
                                if (msgs.isNotEmpty()) {
                                    listState.animateScrollToItem(msgs.size - 1, Int.MAX_VALUE)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(30.dp)
                            .softShadow(15.dp, blurRadius = 3.dp, offsetY = 0.5.dp,
                                color = Color.Black.copy(alpha = if (c.dark) 0.35f else 0.14f))
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (c.dark) Color(0xFF2C2C2E) else c.bgBase)
                            .border(0.6.dp, c.borderL1, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            Strings.get("jumpToBottom"),
                            tint = c.labelPrimary,
                            modifier = Modifier.size(14.dp).rotate(90f),
                        )
                    }
                }
            }
            }
            // Sticky-follow per MessageList.tsx: within STICKY_THRESHOLD(40px)
            // of the bottom stays following; scrolling up stops; re-approach
            // resumes. Content growth (streaming) follows. 触发键只取消息数量、
            // 增长标记与是否到底，不把 viewportEndOffset 纳入流：键盘伸缩每帧
            // 改变视口高度时不再强制重滚列表，避免开合动画过程中列表被反复拽跳。
            val stickyPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
            var sticky by remember(session?.id) { mutableStateOf(true) }
            val nearBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    if (info.totalItemsCount == 0) return@derivedStateOf true
                    val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                    last.index == info.totalItemsCount - 1 &&
                        last.offset + last.size - info.viewportEndOffset < stickyPx
                }
            }
            LaunchedEffect(session?.id) {
                var lastPos = 0
                snapshotFlow { listState.firstVisibleItemIndex * 100000 + listState.firstVisibleItemScrollOffset }
                    .collect { pos ->
                        val next = if (nearBottom) true else if (pos < lastPos) false else sticky
                        if (next != sticky) DebugLog.log("view", "sticky=$next first=${listState.firstVisibleItemIndex} off=${listState.firstVisibleItemScrollOffset} nearBottom=$nearBottom")
                        sticky = next
                        lastPos = pos
                    }
            }
            LaunchedEffect(session?.id) {
                snapshotFlow {
                    val msgs = curVisible
                    val last = msgs.lastOrNull() as? Message.Assistant
                    val lastVis = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    val atEnd = lastVis != null && listState.layoutInfo.totalItemsCount > 0 &&
                        lastVis.index == listState.layoutInfo.totalItemsCount - 1 &&
                        (lastVis.offset + lastVis.size - listState.layoutInfo.viewportEndOffset) < stickyPx
                    Triple(
                        msgs.size,
                        last?.let { replyGrowthKey(it) } ?: 0,
                        atEnd,
                    )
                }.collect { (size, _, _) ->
                    if (sticky && size > 0) {
                        val info = listState.layoutInfo
                        val lastVis = info.visibleItemsInfo.lastOrNull()
                        if (lastVis != null && lastVis.index == size - 1 && info.totalItemsCount == size) {
                            val overflow = (lastVis.offset + lastVis.size - info.viewportEndOffset) + info.afterContentPadding
                            if (overflow > 2) {
                                DebugLog.log("view", "follow rawDelta overflow=${overflow.toInt()} size=$size first=${listState.firstVisibleItemIndex} off=${listState.firstVisibleItemScrollOffset} vEnd=${info.viewportEndOffset}")
                                listState.dispatchRawDelta(overflow.toFloat())
                            }
                        } else {
                            DebugLog.log("view", "follow scrollToItem size=$size first=${listState.firstVisibleItemIndex} off=${listState.firstVisibleItemScrollOffset} vEnd=${info.viewportEndOffset}")
                            listState.scrollToItem(size - 1, Int.MAX_VALUE)
                        }
                    }
                }
            }
        }

        // content caveat + input
        ChatInput(settings, chatStore)
    }
    }
    sourcesPanel?.let { SearchSourcesPanel(it, numbered = true) { sourcesPanel = null } }
    val voiceStatus by VoiceInput.status.collectAsState()
    val voiceCanceling by VoiceInput.canceling.collectAsState()
    io.github.lijiahua1024.dsnative.ui.DrawerGestureBlocker(
        sourcesPanel != null || bubbleMenu != null || assistantMenu != null ||
            feedbackTarget != null || shareSession != null || editTarget != null ||
            selectTextTarget != null || voiceStatus != VoiceInput.Status.IDLE
    )
    if (voiceStatus != VoiceInput.Status.IDLE) {
        VoiceOverlay(canceling = voiceCanceling) { VoiceInput.level() }
    }
    if (session != null) {
        ContentCaveatOverlay(c)
    }
    bubbleMenu?.let { (bmsg, rect) ->
        val density = androidx.compose.ui.platform.LocalDensity.current
        var overlayPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var overlayH by remember { mutableIntStateOf(0) }
        var menuH by remember { mutableIntStateOf(0) }
        val winW = androidx.compose.ui.platform.LocalView.current.width.toFloat()
        val endPad = with(density) { (winW - rect.right - 22f).coerceAtLeast(0f).toDp() }
        val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val marginPx = with(density) { 8.dp.toPx() }
        val topPad = with(density) {
            clampMenuTopPx(rect.top - overlayPos.y + 97f, overlayH, menuH, navBottomPx, marginPx).toDp()
        }
        Box(Modifier.fillMaxSize()
            .onPlaced {
                overlayPos = it.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                overlayH = it.size.height
            }) {
            Box(Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { bubbleMenu = null })
            // ref .menu: min-width 124px, padding 4px, r12, menu bg + 层叠阴影
            androidx.compose.material3.Surface(
                color = c.menu, shape = RoundedCornerShape(12.dp),
                // ref .menu: box-shadow 0 12px 32px rgba(0,0,0,.08) + 0 0 1px .2
                shadowElevation = 16.dp,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(end = endPad)
                    .offset(y = topPad)
                    .width(136.dp)
                    .onPlaced { menuH = it.size.height },
            ) {
                Column(Modifier.padding(4.dp)) {
                    MenuOption(Strings.get("copy"), R.drawable.ic_copy, c.labelPrimary) {
                        val cb = rootCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("msg", bmsg.content))
                        DsToast.show(Strings.get("copied"))
                        bubbleMenu = null
                    }
                    if (bmsg.content.isNotEmpty()) {
                        MenuOption(Strings.get("androidSelectText"), R.drawable.ic_select_text, c.labelPrimary) {
                            bubbleMenu = null
                            selectTextTarget = bmsg.content
                        }
                    }
                    MenuOption(Strings.get("androidEditMessage"), R.drawable.ic_edit, c.labelPrimary) {
                        bubbleMenu = null
                        editTarget = bmsg
                    }
                    MenuOption(Strings.get("share"), R.drawable.ic_share, c.labelPrimary) {
                        bubbleMenu = null
                        session?.let { shareSession = it }
                    }
                }
            }
        }
    }
    assistantMenu?.let { (amsg, rect) ->
        val density = androidx.compose.ui.platform.LocalDensity.current
        var overlayPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var overlayH by remember { mutableIntStateOf(0) }
        var menuH by remember { mutableIntStateOf(0) }
        val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val marginPx = with(density) { 8.dp.toPx() }
        val topPad = with(density) {
            clampMenuTopPx(
                rawTopPx = (rect.top - overlayPos.y + 40f).coerceAtLeast(marginPx),
                containerHeightPx = overlayH,
                menuHeightPx = menuH,
                bottomInsetPx = navBottomPx,
                marginPx = marginPx,
            ).toDp()
        }
        Box(Modifier.fillMaxSize()
            .onPlaced {
                overlayPos = it.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                overlayH = it.size.height
            }) {
            Box(Modifier.fillMaxSize().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }) { assistantMenu = null })
            Surface(
                color = c.menu,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp)
                    .offset(y = topPad)
                    .width(154.dp)
                    .onPlaced { menuH = it.size.height },
            ) {
                Column(Modifier.padding(4.dp)) {
                    val replyTxt = replyText(amsg)
                    val formattedCopy = formattedAssistantCopyText(amsg)
                    val sid = session?.id ?: ""
                    MenuOption(Strings.get("copy"), R.drawable.ic_copy, c.labelPrimary) {
                        val cb = rootCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("reply", formattedCopy))
                        DsToast.show(Strings.get("copied"))
                        assistantMenu = null
                    }
                    if (formattedCopy.isNotEmpty()) {
                        MenuOption(Strings.get("androidSelectText"), R.drawable.ic_select_text, c.labelPrimary) {
                            assistantMenu = null
                            selectTextTarget = formattedCopy
                        }
                    }
                    MenuOption(Strings.get("regenerate"), R.drawable.ic_refresh, c.labelPrimary) {
                        if (sid.isNotEmpty()) chatStore.regenerateReply(sid, amsg.id)
                        assistantMenu = null
                    }
                    MenuOption(
                        Strings.get("like"),
                        if (amsg.reaction == "like") R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up,
                        if (amsg.reaction == "like") c.brandText else c.labelPrimary,
                    ) {
                        if (sid.isNotEmpty()) {
                            chatStore.setReplyReaction(sid, amsg.id, if (amsg.reaction == "like") null else "like")
                        }
                        assistantMenu = null
                    }
                    MenuOption(
                        Strings.get("dislike"),
                        if (amsg.reaction == "dislike") R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down,
                        if (amsg.reaction == "dislike") c.brandText else c.labelPrimary,
                    ) {
                        assistantMenu = null
                        if (amsg.reaction == "dislike" && sid.isNotEmpty()) {
                            chatStore.setReplyReaction(sid, amsg.id, null)
                        } else {
                            feedbackTarget = amsg to FeedbackSheetVariant.FEEDBACK
                        }
                    }
                    if (replyTxt.isNotEmpty() && amsg.stopReason != "refusal") {
                        MenuOption(Strings.get("ttsPlay"), R.drawable.ic_speaker, c.labelPrimary) {
                            val audioManager = rootCtx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                            ReadAloud.toggle(
                                autoReadScope, amsg.id, replyTxt,
                                settings.apiKey.value ?: "", settings.ttsVoice.value,
                                { key -> DsToast.show(Strings.get(key)) },
                                {
                                    if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                                        DsToast.show(Strings.get("readAloudLowVolume"))
                                    }
                                },
                            )
                            assistantMenu = null
                        }
                        MenuOption(Strings.get("share"), R.drawable.ic_share, c.labelPrimary) {
                            assistantMenu = null
                            session?.let { shareSession = it }
                        }
                    }
                    MenuOption(Strings.get("report"), R.drawable.ic_flag, c.labelPrimary) {
                        assistantMenu = null
                        feedbackTarget = amsg to FeedbackSheetVariant.REPORT
                    }
                }
            }
        }
    }
    feedbackTarget?.let { (amsg, variant) ->
        val sid = session?.id ?: ""
        MessageFeedbackSheetOverlay(
            variant = variant,
            onClose = { feedbackTarget = null },
            onSubmit = { _, _ ->
                if (variant == FeedbackSheetVariant.FEEDBACK && sid.isNotEmpty()) {
                    chatStore.setReplyReaction(sid, amsg.id, "dislike")
                }
                feedbackTarget = null
                DsToast.show(Strings.get("feedbackSubmitted"))
            },
        )
    }
    shareSession?.let { s ->
        MessageShareOverlay(session = s, onClose = { shareSession = null })
    }
    editTarget?.let { target ->
        val s = session
        if (s != null) {
            MessageEditOverlay(
                sessionId = s.id,
                msg = target,
                settings = settings,
                chatStore = chatStore,
                isStreaming = streaming.contains(s.id),
                onClose = { editTarget = null },
            )
        }
    }
    selectTextTarget?.let { selectedText ->
        TextSelectionOverlay(
            text = selectedText,
            onClose = { selectTextTarget = null },
        )
    }
    }
    }
}

@Composable
fun Welcome(modifier: Modifier = Modifier, coldStart: Boolean = false, identity: Any? = null) {
    val c = DsTheme.colors
    val greeting = remember(coldStart, identity) { Strings.welcomeGreeting(coldStart) }
    Column(
        modifier = modifier.fillMaxWidth().offset(y = (-8).dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.whale),
            null,
            tint = c.brandPrimary,
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            greeting,
            fontSize = 21.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Medium,
            color = c.labelPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 225.dp),
        )
    }
}

/** Android toast overlay — ref [data-android] .toast: centered at 50%, r16,
 * bg rgb(32 32 32 / 86%), no border/shadow, 14/22 text */
object DsToast {
    val message = MutableStateFlow<String?>(null)
    fun show(text: String) { message.value = text }
}

@Composable
fun DsToastHost() {
    val text by DsToast.message.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(text) {
        if (text != null) { kotlinx.coroutines.delay(3000); DsToast.message.value = null }
    }
    if (text != null) {
        // ref [data-android] .toast: top 50% translateY(-50%) of the viewport —
        // measured centre = screen-centre + ~31 device px. Our host's Box centres
        // on the main layer (which starts below the status band), so the offset
        // must pull the toast back up to y≈1201.
        // ref Toast.module enter 0.2s ease-in-out: opacity 0->1 + translateY(-8px)
        val enter = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(text) { enter.animateTo(1f, androidx.compose.animation.core.tween(200)) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text!!, fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFFF7F9FC),
                modifier = Modifier.offset(y = (-11.8).dp)
                    .graphicsLayer { alpha = enter.value; translationY = -8.dp.toPx() * (1f - enter.value) }
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xDB202020))
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

private class CoordsRef(var coords: androidx.compose.ui.layout.LayoutCoordinates? = null)

@Composable
private fun BoxScope.ContentCaveatOverlay(c: io.github.lijiahua1024.dsnative.theme.DsColors) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeInsets = WindowInsets.ime
    val keyboardOpen by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    if (!keyboardOpen) {
        Text(
            Strings.get("androidContentCaveat"),
            fontSize = 11.sp, lineHeight = 16.sp,
            color = c.labelTertiary.copy(alpha = 0.26f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
        )
    }
}

@Composable
fun BranchSwitcher(
    sessionId: String,
    siblingIds: List<String>,
    activeMessageId: String,
    chatStore: ChatStore,
) {
    if (siblingIds.size <= 1) return
    val index = siblingIds.indexOf(activeMessageId).coerceAtLeast(0)
    val activeColor = Color(0xFF8E9094)
    val disabledColor = Color(0xFFD4D4D4)
    Row(Modifier.padding(end = 5.dp).height(42.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = index > 0,
            onClick = { chatStore.selectVersion(sessionId, siblingIds[index - 1]) },
            modifier = Modifier.size(43.4.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_right), null,
                tint = if (index > 0) activeColor else disabledColor,
                modifier = Modifier.size(16.dp).rotate(180f)
                    .semantics { contentDescription = Strings.get("previousMessage") }
                    .offset(x = 0.25.dp))
        }
        Box(Modifier.semantics {
            contentDescription = Strings.get(
                "messageBranchCount", "index" to index + 1, "total" to siblingIds.size)
        }) {
            Text("${index + 1}/${siblingIds.size}", fontSize = 15.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, color = activeColor)
        }
        IconButton(enabled = index < siblingIds.size - 1,
            onClick = { chatStore.selectVersion(sessionId, siblingIds[index + 1]) },
            modifier = Modifier.size(43.4.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_right), null,
                tint = if (index < siblingIds.size - 1) activeColor else disabledColor,
                modifier = Modifier.size(16.dp)
                    .semantics { contentDescription = Strings.get("nextMessage") }
                    .offset(x = (-0.45).dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun UserBubble(
    msg: Message.User,
    sessionId: String,
    siblingIds: List<String>,
    uploadReply: Message.Assistant?,
    chatStore: ChatStore,
    onMenu: (Message.User, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
) {
    val c = DsTheme.colors
    val coordsRef = remember { CoordsRef() }
    // 长按菜单挂在附件/气泡上弹出消息级「修改/分享」。手势检测器下沉到每个文件项/气泡
    // 自身（combinedClickable），避免外层容器与内层图片 clickable 竞争导致长按被吞掉。
    val openMenu: () -> Unit = {
        val coords = coordsRef.coords
        val rect = if (coords != null && coords.isAttached) {
            val p = coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
            androidx.compose.ui.geometry.Rect(
                p.x, p.y, p.x + coords.size.width, p.y + coords.size.height,
            )
        } else androidx.compose.ui.geometry.Rect.Zero
        onMenu(msg, rect)
    }
    Column(Modifier.fillMaxWidth().padding(top = 11.38.dp)) {
        // ref .files: wrap 行右对齐 gap 10px mb 8px；混排文档时缩略图 64px
        if (msg.files.isNotEmpty()) {
            val hasDoc = msg.files.any { !it.isImage }
            val imgSize = if (hasDoc) 64.dp else 120.dp
            // 外层容器只负责记录整行坐标，长按/点击手势在各文件项内部处理。
            Box(
                Modifier
                    .onPlaced { coordsRef.coords = it }
            ) {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = if (msg.content.isNotEmpty()) 12.dp else 0.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    msg.files.forEach { file ->
                        MessageFileView(sessionId, file, uploadReply, imgSize, chatStore, onLongClick = openMenu)
                    }
                }
            }
        }
        if (msg.content.isNotEmpty()) {
            // ref UserMessage: FOLD_LINES=4，按字符宽度估算行数，超过 4 行才显示折叠按钮
            val needsFold = remember(msg.content) { estimateUserBubbleLines(msg.content) > 4 }
            // 折叠态用 rememberSaveable 持久化：列表回收/重组/重挂后恢复用户上次的展开
            // 或折叠选择，不会被重置回默认折叠（修复展开后等待片刻自动收回、手动收起不生效）。
            var collapsed by rememberSaveable(msg.id) { mutableStateOf(true) }
            val bubbleShape = RoundedCornerShape(22.dp, 22.dp, 4.dp, 22.dp)
            Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 16.dp)) {
                Column(
                    Modifier.align(Alignment.TopEnd)
                        .clip(bubbleShape)
                        .background(c.bubble),
                ) {
                    Box(
                        Modifier
                            .onPlaced { coordsRef.coords = it }
                            // 仅挂长按检测器弹菜单，不注册 tap 检测器；与折叠按钮 clickable 互不竞争
                            // （对齐 Web：气泡本体只响应长按 contextmenu，折叠按钮 stopPropagation）。
                            .pointerInput(msg.id) {
                                detectTapGestures(onLongPress = { openMenu() })
                            }
                            .padding(horizontal = 14.93.dp, vertical = 10.13.dp),
                    ) {
                        Text(
                            msg.content,
                            fontSize = 15.sp, lineHeight = 24.5.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary,
                            maxLines = if (needsFold && collapsed) 4 else Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    // ref .foldToggle：整行可点、右对齐、高 26px、margin-top 4px、13px/20px、labelSecondary。
                    // 按钮为气泡本体的兄弟节点，独立于长按手势容器。
                    if (needsFold) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { collapsed = !collapsed },
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Strings.get(if (collapsed) "expandBlock" else "collapseBlock"),
                                fontSize = 13.sp, lineHeight = 20.sp, color = c.labelSecondary,
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            BranchSwitcher(sessionId, siblingIds, msg.id, chatStore)
        }
        Spacer(Modifier.height(10.37.dp))
    }
}

/** ref estimateLineCount：气泡可用宽度 280px、字号 15；全角/中日韩按 1em，拉丁/数字/符号按 0.55em 估行。 */
private fun estimateUserBubbleLines(text: String, fontSize: Int = 15): Int {
    val charsPerLine = 280.0 / fontSize
    var lines = 1
    var current = 0.0
    fun isWide(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x2E80..0x9FFF || cp in 0xF900..0xFDEF ||
            cp in 0x3000..0x303F || cp in 0xFF00..0xFFEF
    }
    for (ch in text) {
        if (ch == '\n') { lines += 1; current = 0.0; continue }
        val width = if (isWide(ch)) 1.0 else 0.55
        if (current + width > charsPerLine) { lines += 1; current = width } else current += width
    }
    return lines
}

/** ref getExtension：大写扩展名。 */
private fun fileExt(name: String) =
    name.substringAfterLast('.', "").uppercase()

/** ref formatSize：<1024 为 "N B"，KB/MB/GB <10 保留一位小数。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
    return "${if (value < 10) "%.1f".format(value) else value.toInt()} ${units[unit]}"
}

private fun fileIconRes(ext: String): Int = when {
    ext in setOf("JPG", "JPEG", "PNG", "GIF", "WEBP", "BMP", "SVG", "HEIC", "TIF", "TIFF") ->
        R.drawable.ic_file_image
    ext == "PDF" -> R.drawable.ic_file_pdf
    ext in setOf("XLS", "XLSX", "CSV") -> R.drawable.ic_file_table
    ext in setOf("PPT", "PPTX") -> R.drawable.ic_file_ppt
    else -> R.drawable.ic_file_other
}

/**
 * ref MessageFile：图片显示缩略图（纯图片 120px，混排 64px），其余显示 FileCard。
 * 补传中的卡片显示 spinner，补传失败显示重试图标（retryable）或错误文案。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageFileView(
    sessionId: String,
    file: FileAttachment,
    reply: Message.Assistant?,
    imgSize: androidx.compose.ui.unit.Dp,
    chatStore: ChatStore,
    onLongClick: () -> Unit = {},
) {
    val c = DsTheme.colors
    var preview by remember { mutableStateOf(false) }
    val uploadError = reply?.takeIf { it.error?.kind == "upload" }
    val uploading = file.serverId == null && reply?.status == ReplyStatus.PENDING

    if (file.isImage) {
        // ref Web MessageFile：点击打开预览；长按 contextmenu 冒泡到消息级菜单。
        // Compose 下长按与点击同挂在文件项自身的 combinedClickable 上，避免被外层/内层
        // 另一套 tap 检测器吞掉长按手势。
        Box(Modifier.size(imgSize)
            .clip(RoundedCornerShape(16.dp))
            .background(c.markdownPlaceholder)
            .border(1.dp, if (uploadError != null) c.stateErrorPrimary else c.borderL2,
                RoundedCornerShape(16.dp))
            .combinedClickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = {
                    if (uploadError != null) chatStore.retryUpload(sessionId, uploadError.id)
                    else preview = true
                },
                onLongClick = onLongClick)) {
            file.localPath?.let { io.github.lijiahua1024.dsnative.ui.input.orientedThumbFor(it, 320) }
                ?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (uploading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
                        strokeWidth = 2.dp)
                }
            } else if (uploadError != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        .clickable { chatStore.retryUpload(sessionId, uploadError.id) },
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_refresh), null, tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    } else {
        // ref FileCard .card：240px min-h64 pad 0/12/0/14 r16 border l2 bg input-major
        Row(Modifier.width(240.dp).defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (uploadError != null) c.stateErrorPrimary else c.borderL2,
                RoundedCornerShape(16.dp))
            .background(c.inputMajor)
            .combinedClickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = { if (uploadError != null) chatStore.retryUpload(sessionId, uploadError.id) },
                onLongClick = onLongClick)
            .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).padding(end = 0.dp), contentAlignment = Alignment.Center) {
                when {
                    uploading -> CircularProgressIndicator(Modifier.size(16.dp),
                        color = c.labelTertiary, strokeWidth = 2.dp)
                    uploadError != null -> Icon(painterResource(R.drawable.ic_refresh), null,
                        tint = c.labelSecondary, modifier = Modifier.size(18.dp))
                    else -> Icon(painterResource(fileIconRes(fileExt(file.name))), null,
                        tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(file.name, fontSize = 14.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.W400, color = c.labelPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        uploading -> Strings.get("uploadFileStatusUploading")
                        uploadError != null -> Strings.get("uploadFileStatusFailed")
                        else -> listOf(fileExt(file.name), formatFileSize(file.size))
                            .filter { it.isNotEmpty() }.joinToString(" ")
                    },
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = if (uploadError != null) c.stateErrorPrimary else c.labelTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (preview) {
        io.github.lijiahua1024.dsnative.ui.input.ImagePreviewDialog(
            DraftAttachment(file.id, file.name, file.mimeType, file.isImage,
                file.localPath ?: ""),
            onClose = { preview = false },
            onConfirmCrop = { cropped ->
                chatStore.addDraftAttachments(sessionId, listOf(cropped))
                preview = false
                chatStore.requestFocusComposer()
            },
            onDownload = {})  // ref: canDownload solo nel viewer dei messaggi
    }
}

@Composable
private fun MenuOption(label: String, icon: Int, tint: Color, onClick: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (isPressed) {
        if (DsTheme.colors.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f)
    } else {
        Color.Transparent
    }

    Row(
        Modifier
            .fillMaxWidth() // 🌟 撑满整行
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor) // 极淡背景色
            .clickable(
                interactionSource = interactionSource,
                indication = null, // 关水波纹
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, lineHeight = 24.sp, color = tint)
    }
}

@Composable
fun WaitingDots() {
    val c = DsTheme.colors
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "waiting")
    Row(
        Modifier.height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (index in 0..2) {
            val alpha by transition.animateFloat(
                initialValue = 0.3f, targetValue = 0.3f, label = "dot$index",
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 900
                        0.3f at 0
                        1.0f at 297   // 33%
                        1.0f at 594   // 66%
                        0.3f at 900
                    },
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 100)
                )
            )
            Box(Modifier.size(6.dp).alpha(alpha).background(c.labelDimmed, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantView(
    msg: Message.Assistant,
    sessionId: String,
    siblingIds: List<String>,
    chatStore: ChatStore,
    isLast: Boolean = false,
    onOpenSettings: () -> Unit = {},
    foldThinking: Boolean = true,
    onOpenMenu: (Message.Assistant, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onFeedback: (Message.Assistant, FeedbackSheetVariant) -> Unit = { _, _ -> },
    onShare: () -> Unit = {},
) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val sources = remember(msg.blocks) { searchSources(msg.blocks) }
    val openSources = LocalOpenSources.current
    val hasFinishedReply = (msg.status == ReplyStatus.DONE || msg.status == ReplyStatus.STOPPED) &&
        replyText(msg).isNotEmpty()
    val coordsRef = remember { CoordsRef() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 19.dp)
            .then(
                if (hasFinishedReply) {
                    Modifier
                        .onPlaced { coordsRef.coords = it }
                        // 只挂长按检测器弹菜单：父级不注册 tap 检测器，避免与思考块标题/内容行
                        // 内层 clickable 竞争吞掉点击（对齐 Web：内容区仅响应长按 contextmenu）。
                        .pointerInput(msg.id) {
                            detectTapGestures(
                                onLongPress = {
                                    val rect = coordsRef.coords?.takeIf { it.isAttached }?.boundsInWindow()
                                        ?: androidx.compose.ui.geometry.Rect.Zero
                                    onOpenMenu(msg, rect)
                                },
                            )
                        }
                } else Modifier,
            ),
    ) {
    CompositionLocalProvider(LocalSources provides sources) {
        val renderBlocks = remember(msg.blocks, sources) { withCitationMarkers(msg.blocks, sources) }
        var prevWasStep = false
        for (block in renderBlocks) {
            when (block) {
                is ReplyBlock.Thinking -> ThinkingBlock(block, msg, foldThinking).also { prevWasStep = true }
                is ReplyBlock.Text -> MarkdownBody(block.text,
                    Modifier.padding(top = if (prevWasStep) 5.dp else 0.dp),
                    streaming = msg.status == ReplyStatus.STREAMING)
                    .also { prevWasStep = false }
                is ReplyBlock.Tool -> {
                    val step = toolStep(block, msg.blocks, sources, msg.status == ReplyStatus.STREAMING)
                    when (step) {
                        is ToolStep.Search -> { SearchStepRow(step, openSources, stopped = msg.status == ReplyStatus.STOPPED); prevWasStep = true }
                        is ToolStep.Open -> { OpenStepRow(step, openSources); prevWasStep = true }
                        is ToolStep.Find -> { FindStepRow(step); prevWasStep = true }
                        is ToolStep.ReadLink -> if (step.status == ToolStatus.WIP) {
                            ReadLinkStepRow(step); prevWasStep = true
                        }
                        null -> {}
                    }
                }
            }
        }
        if (msg.status != ReplyStatus.STREAMING && msg.status != ReplyStatus.ERROR && sources.isNotEmpty()) {
            SourcesSummary(sources) { openSources(sources) }
        }
        when (msg.status) {
            // ref .waiting: three 6px dots fading in a 34px row
            ReplyStatus.PENDING -> WaitingDots()
            ReplyStatus.STREAMING -> if (msg.blocks.isEmpty()) WaitingDots()
            ReplyStatus.ERROR -> {
                // ref .notice: warning icon + 14/22 error text + inline brand action
                val action = when (msg.error?.status) {
                    401 -> "changeApiKey"
                    402 -> "topUp"
                    else -> null
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_warning), null, tint = c.stateErrorPrimary,
                        modifier = Modifier.padding(top = 4.dp).size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append(msg.error?.let { errorText(it) } ?: Strings.get("unknownError"))
                        if (action != null) {
                            append(' ')
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(color = c.brandPrimary)) {
                                append(Strings.get(action))
                            }
                        }
                    }, fontSize = 14.sp, lineHeight = 22.sp, color = c.stateErrorPrimary,
                        modifier = Modifier.clickable(action != null) {
                            if (msg.error?.status == 402) {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://platform.deepseek.com/top_up")))
                            } else onOpenSettings()
                        })
                }
                // ref .bottom for an error reply: copy + regenerate icons only
                Row(Modifier.padding(top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    IconButton({
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply",
                            msg.error?.let { errorText(it) } ?: Strings.get("unknownError")))
                        DsToast.show(Strings.get("copied"))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(painterResource(R.drawable.ic_copy), Strings.get("copy"), tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                    IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), null, tint = c.labelTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            else -> {}
        }
        if (msg.status == ReplyStatus.DONE || msg.status == ReplyStatus.STOPPED) {
            Row(Modifier.fillMaxWidth().offset(x = (-6).dp).padding(top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BranchSwitcher(sessionId, siblingIds, msg.id, chatStore)
                Row(modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                val clipboard = remember(ctx) {
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val audioManager = remember(ctx) {
                    ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                }
                val iconTint = if (c.dark) c.labelTertiary else Color(0xFF8E9094)
                IconButton({
                    val txt = formattedAssistantCopyText(msg)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", txt))
                    DsToast.show(Strings.get("copied"))
                }, modifier = Modifier.size(34.dp)) {
                    Icon(painterResource(R.drawable.ic_copy), Strings.get("copy"), tint = iconTint, modifier = Modifier.size(18.dp))
                }
                val liked = msg.reaction == "like"
                val disliked = msg.reaction == "dislike"
                IconButton(
                    onClick = { chatStore.setReplyReaction(sessionId, msg.id, if (liked) null else "like") },
                    modifier = Modifier.size(34.dp).semantics {
                        contentDescription = Strings.get("like")
                    },
                ) {
                    Icon(
                        painterResource(if (liked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up),
                        Strings.get("like"),
                        tint = if (liked) c.brandText else iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (disliked) chatStore.setReplyReaction(sessionId, msg.id, null)
                        else onFeedback(msg, FeedbackSheetVariant.FEEDBACK)
                    },
                    modifier = Modifier.size(34.dp).semantics {
                        contentDescription = Strings.get("dislike")
                    },
                ) {
                    Icon(
                        painterResource(if (disliked) R.drawable.ic_thumb_down_filled else R.drawable.ic_thumb_down),
                        Strings.get("dislike"),
                        tint = if (disliked) c.brandText else iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                run {
                    // ref ReadAloudButton + ReadingBars: loading/playing 竖条动画,
                    // повторный тап по читаемой = стоп (stopReadAloud).
                    val readId by io.github.lijiahua1024.dsnative.data.ReadAloud.messageId.collectAsState()
                    val readStatus by io.github.lijiahua1024.dsnative.data.ReadAloud.status.collectAsState()
                    val reading = readId == msg.id && readStatus !=
                        io.github.lijiahua1024.dsnative.data.ReadAloud.Status.IDLE
                    IconButton({
                        io.github.lijiahua1024.dsnative.data.ReadAloud.toggle(
                            scope, msg.id, replyText(msg),
                            chatStore.settings.apiKey.value ?: "",
                            chatStore.settings.ttsVoice.value,
                            { key -> DsToast.show(Strings.get(key)) },
                            { if (audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) DsToast.show(Strings.get("readAloudLowVolume")) },
                        )
                    }, modifier = Modifier.size(34.dp)) {
                        if (reading) {
                            ReadingBars(readStatus ==
                                io.github.lijiahua1024.dsnative.data.ReadAloud.Status.PLAYING)
                        } else {
                            Icon(painterResource(R.drawable.ic_speaker), Strings.get("ttsPlay"),
                                tint = iconTint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_share), Strings.get("share"), tint = iconTint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton({ chatStore.regenerateReply(sessionId, msg.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), Strings.get("regenerate"), tint = iconTint, modifier = Modifier.size(18.dp))
                }
                }
                // ref .continueButton：outlined s（h32 pill 1px l2 border 14/22 w400），margin-left:auto
                if (isLast && chatStore.canContinueReply(msg)) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.height(32.dp).widthIn(min = 58.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, c.borderL2, RoundedCornerShape(50))
                            .clickable { chatStore.continueReply(sessionId, msg.id) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(Strings.get("continueGenerating"), fontSize = 14.sp,
                            lineHeight = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W400,
                            color = c.labelPrimary)
                    }
                }
            }
        }
    }
    }
}

internal fun clampMenuTopPx(
    rawTopPx: Float,
    containerHeightPx: Int,
    menuHeightPx: Int,
    bottomInsetPx: Float,
    marginPx: Float,
): Float {
    val safeMin = marginPx.coerceAtLeast(0f)
    if (containerHeightPx <= 0 || menuHeightPx <= 0) return rawTopPx.coerceAtLeast(safeMin)
    val safeMax = (containerHeightPx - bottomInsetPx - menuHeightPx - marginPx).coerceAtLeast(safeMin)
    return rawTopPx.coerceIn(safeMin, safeMax)
}

internal fun replyGrowthKey(msg: Message.Assistant): Int =
    msg.blocks.sumOf { block ->
        when (block) {
            is ReplyBlock.Text -> block.text.length
            is ReplyBlock.Thinking -> block.thinking.length
            is ReplyBlock.Tool -> block.payload.toString().length
        }
    } + msg.blocks.size

internal fun thinkingDurationSeconds(
    block: ReplyBlock.Thinking,
    now: Long = System.currentTimeMillis(),
): Long? {
    block.elapsedSeconds?.let { secs ->
        return maxOf(1L, secs)
    }
    val start = block.startedAt ?: return null
    val end = block.endedAt ?: now
    return maxOf(1L, kotlin.math.round((end - start) / 1000.0).toLong())
}

internal fun shouldAutoReadOnDone(
    wasStreaming: Boolean,
    isLast: Boolean,
    msg: Message.Assistant?,
    autoRead: Boolean,
): Boolean {
    if (!wasStreaming || !isLast || !autoRead || msg == null) return false
    if (msg.status != ReplyStatus.DONE || msg.stopReason != "end_turn") return false
    return replyText(msg).isNotBlank()
}

internal fun defaultThinkingExpanded(foldThinking: Boolean, hasResponse: Boolean): Boolean =
    !foldThinking || !hasResponse

@Composable
fun ThinkingBlock(block: ReplyBlock.Thinking, msg: Message.Assistant, foldThinking: Boolean = true) {
    val c = DsTheme.colors
    val hasResponse =
        msg.blocks.any { it is ReplyBlock.Text && it.text.isNotEmpty() }
    var override by remember(msg.id) { mutableStateOf<Boolean?>(null) }
    val expanded = override ?: defaultThinkingExpanded(foldThinking, hasResponse)
    val title = when {
        msg.status == ReplyStatus.STREAMING && !hasResponse -> Strings.get("messageThinking")
        msg.status == ReplyStatus.STOPPED && !hasResponse -> Strings.get("messageThinkStopped")
        else -> {
            val secs = thinkingDurationSeconds(block)
            if (secs == null) Strings.get("thinkingSwitch")
            else Strings.get(if (secs == 1L) "messageThinkDuration" else "messageThinkDurationPlural", "count" to secs)
        }
    }
    // ref ReplyArea.module.css: disclosure grid-rows 0fr->1fr 220ms cubic-bezier(0.2,0,0,1),
    // chevron transform 220ms, .area margin-bottom 5->24px 220ms, steps opacity 160ms
    val discEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val chevDeg by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 90f else 0f,
        androidx.compose.animation.core.tween(220, easing = discEase), label = "chev")
    val areaPad by androidx.compose.animation.core.animateDpAsState(
        if (expanded) 24.dp else 5.dp,
        androidx.compose.animation.core.tween(220, easing = discEase), label = "areaPad")
    // ref .titleText.shimmer: 流式未出正文时标题文本扫光
    // 1.5s linear infinite（gradient bg-position 100%->0，宽 400%）
    val titleActive = msg.status == ReplyStatus.STREAMING && !hasResponse
    // ref ReplyArea.tsx: previewActive = android && thinking && titleActive（流式未出正文阶段）
    val previewActive = titleActive
    // 流式预览内的手动展开（streamExpanded），独立于整体 override；出正文/停止后复位
    var streamExpanded by remember(msg.id) { mutableStateOf(false) }
    var previewOverflow by remember(msg.id) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val previewHeightPx = with(LocalDensity.current) { 128.dp.toPx().toInt() }
    LaunchedEffect(previewActive) { if (!previewActive) streamExpanded = false }
    // 预览态跟随滚动到最新思考（对齐 Web follow rAF 滚到底）
    LaunchedEffect(block.thinking, previewActive, streamExpanded) {
        if (previewActive && !streamExpanded) scrollState.scrollTo(scrollState.maxValue)
    }
    val inPreview = previewActive && !streamExpanded
    val titleColor = c.thinkingText
    val titleStyle = if (titleActive) {
        val shimmer = androidx.compose.animation.core.rememberInfiniteTransition(label = "thinkShimmer")
        val p = shimmer.animateFloat(0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1500,
                    easing = androidx.compose.animation.core.LinearEasing)),
            label = "thinkShimmerP")
        val dim = titleColor.copy(alpha = 0.15f)
        val w = 320f
        TextStyle(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                0.34f to titleColor, 0.5f to dim, 0.66f to titleColor,
                start = androidx.compose.ui.geometry.Offset(-3f * w * (1f - p.value), 0f),
                end = androidx.compose.ui.geometry.Offset(-3f * w * (1f - p.value) + 4f * w, 0f)),
            fontSize = 14.sp,
        )
    } else {
        TextStyle(color = titleColor, fontSize = 14.sp)
    }
    Column {
        Row(Modifier.fillMaxWidth().height(32.dp).clickable {
            when {
                previewActive && previewOverflow -> streamExpanded = !streamExpanded
                else -> override = !expanded
            }
        }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = titleStyle)
            Spacer(Modifier.width(4.dp))
            when {
                previewActive && previewOverflow -> {
                    if (streamExpanded) {
                        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.thinkingText,
                            modifier = Modifier.size(14.dp).rotate(90f))
                    } else {
                        // ref .previewIcon：双下箭头堆叠，提示可点开全部思考
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.thinkingText,
                                modifier = Modifier.size(10.dp).rotate(90f).offset(y = 2.dp))
                            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.thinkingText,
                                modifier = Modifier.size(10.dp).rotate(90f).offset(y = (-2).dp))
                        }
                    }
                }
                else -> Icon(painterResource(R.drawable.ic_chevron_right), null, tint = c.thinkingText,
                    modifier = Modifier.size(14.dp).rotate(chevDeg))
            }
        }
        AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically(
                androidx.compose.animation.core.tween(220, easing = discEase))
                + androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.shrinkVertically(
                androidx.compose.animation.core.tween(220, easing = discEase))
                + androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(160))) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.padding(start = 7.5.dp, top = 4.dp).width(1.dp).fillMaxHeight().background(c.thinkingLine))
                Column(Modifier.padding(start = 24.dp)) {
                    Box(
                        Modifier.then(
                            if (inPreview) Modifier.heightIn(max = 128.dp) else Modifier
                        )
                    ) {
                        Column(
                            Modifier
                                .then(if (inPreview) Modifier.verticalScroll(scrollState) else Modifier)
                                .onSizeChanged { size -> previewOverflow = size.height > previewHeightPx }
                        ) {
                            Text(block.thinking, fontSize = 14.sp, color = c.thinkingText)
                        }
                        // ref .previewFade：预览超出时底部渐变淡出到会话背景
                        if (inPreview && previewOverflow) {
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.65f to Color.Transparent,
                                        1f to c.bgBase
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(areaPad))
    }
}

private fun errorText(e: ReplyError): String {
    if (e.kind == "too_long") return Strings.get("replyInputTooLong")
    val key = when (e.status) {
        401 -> "replyInvalidApiKey"
        402 -> "replyInsufficientBalance"
        429 -> "replyRateLimited"
        500, 503 -> "completionServerBusyToast"
        else -> when (e.kind) {
            "stream" -> "replyRefused"
            "network" -> "commonNetworkErrorToast"
            else -> "unknownError"
        }
    }
    return Strings.get(key, "message" to e.message)
}
