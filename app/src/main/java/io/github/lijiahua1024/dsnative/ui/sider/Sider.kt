package io.github.lijiahua1024.dsnative.ui.sider

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import io.github.lijiahua1024.dsnative.theme.dsPainterResource as painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ChatStore
import io.github.lijiahua1024.dsnative.data.SessionSummary
import io.github.lijiahua1024.dsnative.data.SettingsStore
import io.github.lijiahua1024.dsnative.data.groupSessions
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import io.github.lijiahua1024.dsnative.ui.DrawerGestureBlocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SiderRowShape = RoundedCornerShape(12.dp)
private val searchCalLocal = object : ThreadLocal<java.util.Calendar>() {
    override fun initialValue(): java.util.Calendar = java.util.Calendar.getInstance()
}
private val searchZhFmtLocal = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue(): java.text.SimpleDateFormat =
        java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
}
private val searchEnFmtLocal = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue(): java.text.SimpleDateFormat =
        java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Sider(
    settings: SettingsStore,
    chatStore: ChatStore,
    onNavigateSettings: () -> Unit,
    onClose: () -> Unit,
    isWideLayout: Boolean = false,
    onCollapseWide: () -> Unit = {},
    searchExpanded: Boolean = false,
    onSearchExpandedChange: (Boolean) -> Unit = {},
) {
    val c = DsTheme.colors
    val focusManager = LocalFocusManager.current
    val sessions by chatStore.sessions.collectAsState()
    val activeId by chatStore.activeId.collectAsState()
    val pinned by settings.pinnedSessions.collectAsState()
    val groups = remember(sessions, pinned) {
        val pinnedSet = pinned.toSet()
        val pinnedList = sessions.filter { it.id in pinnedSet }
        val rest = groupSessions(sessions.filter { it.id !in pinnedSet }, System.currentTimeMillis())
            .map { (k, l) -> (if (k.startsWith("session")) Strings.get(k) else k) to l }
        (if (pinnedList.isNotEmpty()) listOf(Strings.get("androidPinned") to pinnedList) else emptyList()) + rest
    }

    var query by remember { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val titleMatchSnippets = remember(sessions, trimmedQuery) {
        if (trimmedQuery.isEmpty()) emptyMap()
        else sessions.asSequence()
            .filter { it.title.contains(trimmedQuery, ignoreCase = true) }
            .associate { it.id to "" }
    }
    var settledSearch by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    LaunchedEffect(trimmedQuery, sessions) {
        if (trimmedQuery.isEmpty()) {
            settledSearch = null
            return@LaunchedEffect
        }
        delay(200)
        val matched = chatStore.searchSessionSnippets(trimmedQuery)
        settledSearch = trimmedQuery to matched
    }
    val matchedSnippets = when {
        trimmedQuery.isEmpty() -> null
        settledSearch?.first == trimmedQuery -> settledSearch!!.second
        else -> titleMatchSnippets
    }
    val matchedIds = matchedSnippets?.keys
    var selectionMode by remember { mutableStateOf(false) }

    fun closeSearchOverlay() {
        query = ""
        focusManager.clearFocus()
        onSearchExpandedChange(false)
    }

    PredictiveBackHandler(enabled = !isWideLayout && searchExpanded && !selectionMode) { flow ->
        try {
            flow.collect { }
            closeSearchOverlay()
        } catch (_: Throwable) {
        }
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renameFor by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteFor by remember { mutableStateOf<SessionSummary?>(null) }
    var pinning by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) }
    var deletingSel by remember { mutableStateOf<Set<String>?>(null) }
    var pressGesture by remember { mutableStateOf<SessionPressGesture?>(null) }
    val pressScope = rememberCoroutineScope()

    fun selectSessionWithPress(sessionId: String) {
        pressGesture = SessionPressGesture(sessionId, SessionPressPhase.Light)
        pressScope.launch {
            delay(17)
            pressGesture = pressGesture?.copy(phase = SessionPressPhase.Pressed)
            delay(150)
            chatStore.selectSession(sessionId)
            pressGesture = null
            onClose()
        }
    }

    DrawerGestureBlocker(menuFor != null || renameFor != null || deleteFor != null || pinning != null || deletingSel != null)
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp)) {
        if (isWideLayout && !selectionMode) {
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Strings.get("appName"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(34.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(onClick = onCollapseWide),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_sidebar_panel),
                        Strings.get("collapseSiderbarButton"),
                        tint = c.labelPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (c.dark) Color(0xFF292929) else Color(0xFFF3F3F4))
                    .clickable {
                        if (!chatStore.tryStartNewChat()) {
                            io.github.lijiahua1024.dsnative.ui.session.DsToast.show(
                                Strings.get("androidAlreadyInNewChat")
                            )
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_new_chat),
                    null,
                    tint = c.labelPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    Strings.get("newChat"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.labelPrimary,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (selectionMode) {
            // .selectionHeader h48: label left + close IconButton right
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) Strings.get("androidSelectChats") else Strings.get("androidSelectedChats").replace("{count}", selected.size.toString()),
                    fontSize = 20.sp, color = c.labelPrimary, modifier = Modifier.weight(1f))
                // .selectionHeader button: circle bg-base + soft shadow, tone=primary (dark)
                Box(Modifier.size(34.dp).shadow(2.dp, androidx.compose.foundation.shape.CircleShape)
                        .clip(androidx.compose.foundation.shape.CircleShape).background(c.bgBase)
                        .clickable { selectionMode = false; selected = emptySet() },
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), Strings.get("close"), tint = c.labelPrimary,
                        modifier = Modifier.size(16.dp))
                }
            }
        } else {
            // .search: h48 r24, padding 0 14, gap 8, search icon 16 tertiary; expands to full width when active
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!isWideLayout && searchExpanded) {
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { closeSearchOverlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            Strings.get("cancel"),
                            tint = c.labelPrimary,
                            modifier = Modifier.size(18.dp).rotate(180f),
                        )
                    }
                }
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (c.dark) Color(0xFF292929) else Color(0xFFF5F5F5))
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query, onValueChange = { query = it },
                        singleLine = true,
                        // ref input[type=search] -> search action on the IME
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.labelPrimary),
                        modifier = Modifier.fillMaxSize().onFocusChanged { st ->
                            if (st.isFocused && !isWideLayout && !searchExpanded) {
                                onSearchExpandedChange(true)
                            }
                        },
                        decorationBox = { inner ->
                            Box(
                                Modifier.fillMaxSize().padding(
                                    start = 38.dp,
                                    end = if (query.isNotEmpty()) 38.dp else 14.dp,
                                ),
                                contentAlignment = Alignment.CenterStart,
                            ) { inner() }
                        },
                    )
                    Icon(painterResource(R.drawable.ic_search), null, tint = c.labelTertiary,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(16.dp))
                    if (query.isEmpty()) Text(Strings.get("androidSearchChats"), fontSize = 16.sp, color = c.labelTertiary,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 38.dp))
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .padding(end = 6.dp)
                                .size(32.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                Strings.get("close"),
                                tint = c.labelTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        // .listWrap: margin-top 10, relative container for selectChats icon + bottom fade
        Box(Modifier.weight(1f).padding(top = 10.dp)) {
            if (!isWideLayout && searchExpanded) {
                val results = remember(sessions, matchedIds) {
                    if (matchedIds == null) sessions else sessions.filter { it.id in matchedIds }
                }
                LazyColumn(
                    Modifier.fillMaxSize()
                        .nestedScroll(remember { io.github.lijiahua1024.dsnative.ui.ScrollTracker("siderSearch") }),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(results, key = { "sr-${it.id}" }, contentType = { "search_row" }) { s ->
                        SearchSessionRow(
                            s = s,
                            snippet = matchedSnippets?.get(s.id).orEmpty(),
                            query = trimmedQuery,
                            onClick = {
                                closeSearchOverlay()
                                selectSessionWithPress(s.id)
                            },
                        )
                    }
                }
            } else {
            // session groups
            LazyColumn(Modifier.fillMaxSize()
                .nestedScroll(remember { io.github.lijiahua1024.dsnative.ui.ScrollTracker("sider") }),
                contentPadding = PaddingValues(bottom = 18.dp)) {
            val filtered = groups.map { g ->
                g.first to g.second.filter { s -> matchedIds == null || s.id in matchedIds }
            }.filter { it.second.isNotEmpty() }
            filtered.forEachIndexed { gi, (label, list) ->
                item(key = "g-$label", contentType = "group_header") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = (if (gi == 0) 2 else 12).dp, start = 6.dp, end = 6.dp).height(26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = c.labelTertiary, fontWeight = FontWeight.Normal,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                            modifier = Modifier.weight(1f))
                        if (gi == 0 && !selectionMode) {
                            Box(
                                Modifier.offset(x = 5.dp).size(32.dp).clickable {
                                    selectionMode = true; selected = emptySet()
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_select_chats), Strings.get("androidMultiSelect"), tint = c.labelPrimary,
                                    modifier = Modifier.padding(7.dp))
                            }
                        }
                    }
                }
                items(list, key = { it.id }, contentType = { "sider_row" }) { s ->
                    SiderRow(s, s.id == activeId, selectionMode, selected.contains(s.id),
                        isPinned = pinned.contains(s.id),
                        pressPhase = pressGesture?.takeIf { it.sessionId == s.id }?.phase
                            ?: SessionPressPhase.Rest,
                        onClick = {
                            if (selectionMode) {
                                selected = if (selected.contains(s.id)) selected - s.id else selected + s.id
                            } else selectSessionWithPress(s.id)
                        },
                        onLongClick = { if (!selectionMode) menuFor = s },
                        onMenuClick = { if (!selectionMode) menuFor = s },
                        menuOpen = menuFor?.id == s.id,
                        onMenuDismiss = { menuFor = null },
                        onRename = { renameFor = s; menuFor = null },
                        onPin = {
                            settings.setPinnedSessions(
                                if (pinned.contains(s.id)) pinned - s.id else pinned + s.id)
                            menuFor = null
                        },
                        onMultiSelect = {
                            selectionMode = true
                            selected = setOf(s.id)
                            menuFor = null
                        },
                        onDelete = { deleteFor = s; menuFor = null })
                }
            }
            }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, c.bgBase)
                        )
                    )
            )
        }

        // .bottom: h64 padding-top 8; .settings padding 4px 0
        if (selectionMode) {
            // .selectionActions: border-top borderL2, two buttons h42
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(c.bgBase)
                .drawBehind { drawLine(c.borderL2, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                .navigationBarsPadding()
                .height(42.dp)) {
                // &:disabled { opacity: 0.35 } — whole button fades
                val actionsAlpha = if (selected.isEmpty()) 0.35f else 1f
                TextButton(onClick = { if (selected.isNotEmpty()) pinning = selected to allPinned(selected, pinned) },
                    enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f).alpha(actionsAlpha)) {
                    Icon(painterResource(R.drawable.ic_pin), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Strings.get(if (allPinned(selected, pinned)) "androidUnpin" else "androidPinned"), fontSize = 15.sp, color = c.labelPrimary)
                }
                TextButton(onClick = { if (selected.isNotEmpty()) deletingSel = selected },
                    enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f).alpha(actionsAlpha)) {
                    Icon(painterResource(R.drawable.ic_trash), null, tint = c.labelPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Strings.get("delete"), fontSize = 15.sp, color = c.labelPrimary)
                }
            }
        } else if (!searchExpanded || isWideLayout) {
            // Match official drawer footer: 62dp row, flush 16dp start, 32dp avatar + username
            val profile by settings.accountProfile.collectAsState()
            val displayName = profile.username.ifBlank { Strings.get("profileDefaultUsername") }
            // ref .settings：Android 按压透明/淡骨架色，不使用默认中性灰涟漪整块背景。
            val profilePress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val profilePressed by profilePress.collectIsPressedAsState()
            Row(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(12.dp))
                    .background(DsTheme.pressTint(profilePressed, c.bgSkeleton))
                    .clickable(interactionSource = profilePress, indication = null, onClick = onNavigateSettings),
                verticalAlignment = Alignment.CenterVertically) {
                io.github.lijiahua1024.dsnative.ui.settings.ProfileAvatarView(
                    avatarDataUrl = profile.avatarDataUrl,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    displayName,
                    fontSize = 16.sp,
                    color = c.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).offset(y = (-1.5).dp),
                )
            }
        }
    }

    // long-press dropdown menu


    renameFor?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title) }
        val c = DsTheme.colors
        val ov = remember { androidx.compose.animation.core.Animatable(0f) }
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        fun dismissRename(after: () -> Unit = { renameFor = null }) {
            if (closing) return
            closing = true
            scope.launch {
                ov.animateTo(0f, androidx.compose.animation.core.tween(140))
                after()
            }
        }
        LaunchedEffect(s.id) { ov.animateTo(1f, androidx.compose.animation.core.tween(160)) }
        val atOrigin = remember {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: androidx.compose.ui.unit.IntSize,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    popupContentSize: androidx.compose.ui.unit.IntSize,
                ) = androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
            }
        }
        val dark = c.bgBase.red < 0.3f
        androidx.compose.ui.window.Popup(
            popupPositionProvider = atOrigin,
            onDismissRequest = { dismissRename() },
            properties = androidx.compose.ui.window.PopupProperties(
                focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true,
            ),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = (if (dark) 0.6f else 0.36f) * ov.value))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { dismissRename() },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(horizontal = 24.dp)
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(enabled = false) {}
                        .background(c.bgLayer2)
                        .then(
                            if (c.borderInverted.alpha > 0f)
                                Modifier.border(1.dp, c.borderInverted, RoundedCornerShape(24.dp))
                            else Modifier,
                        )
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                ) {
                    Text(
                        Strings.get("sessionItemMenuRenameOption"),
                        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = c.labelPrimary,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DsModalButton(Strings.get("cancel"), DsBtn.Outlined) { dismissRename() }
                        DsModalButton(Strings.get("confirm"), DsBtn.Primary) {
                            val trimmed = text.trim()
                            dismissRename {
                                if (trimmed.isNotEmpty()) chatStore.renameSession(s.id, trimmed)
                                renameFor = null
                            }
                        }
                    }
                }
            }
        }
    }

    deleteFor?.let { s ->
        DsModal(
            title = Strings.get("deleteSessionModalContentTitle"),
            body = Strings.get("deleteSessionWithFilesDescription"),
            onDismiss = { deleteFor = null },
            buttons = {
                DsModalButton(Strings.get("cancel"), DsBtn.Outlined) { deleteFor = null }
                DsModalButton(Strings.get("delete"), DsBtn.Danger) {
                    chatStore.removeSession(s.id); deleteFor = null
                }
            })
    }

    deletingSel?.let { ids ->
        DsModal(
            title = Strings.get("deleteSessionModalContentTitle"),
            body = Strings.get("deleteSessionWithFilesDescription"),
            onDismiss = { deletingSel = null },
            buttons = {
                DsModalButton(Strings.get("cancel"), DsBtn.Outlined) { deletingSel = null }
                DsModalButton(Strings.get("delete"), DsBtn.Danger) {
                    ids.forEach { chatStore.removeSession(it) }
                    deletingSel = null; selectionMode = false; selected = emptySet()
                }
            })
    }

    pinning?.let { (ids, unpin) ->
        DsModal(
            title = Strings.get(if (unpin) "androidUnpinConfirm" else "androidPinConfirm").replace("{count}", ids.size.toString()),
            body = null,
            onDismiss = { pinning = null },
            buttons = {
                DsModalButton(Strings.get(if (unpin) "androidKeepPinned" else "cancel"), DsBtn.Outlined) { pinning = null }
                DsModalButton(Strings.get(if (unpin) "androidUnpin" else "androidPinned"), DsBtn.Primary) {
                    settings.setPinnedSessions(if (unpin) pinned - ids else pinned + ids)
                    io.github.lijiahua1024.dsnative.ui.session.DsToast.show(
                        Strings.get(if (unpin) "androidUnpinnedChats" else "androidPinnedChats").replace("{count}", ids.size.toString()))
                    pinning = null; selectionMode = false; selected = emptySet()
                }
            })
    }
}

private enum class DsBtn { Outlined, Primary, Danger }

// ref Button default size l: h40, min-width 72, padding 0 14, pill r4096, 14/22;
// outlined: transparent bg + borderL2 + w400; primary: brand fill; danger: error fill
@Composable
private fun DsModalButton(text: String, variant: DsBtn, onClick: () -> Unit) {
    val c = DsTheme.colors
    // 去默认灰色涟漪：填充按钮按压整体变淡(alpha 0.8)，描边按钮按压极淡浅灰；clip 先于背景保护圆角。
    val pressSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by pressSrc.collectIsPressedAsState()
    val restBg = when (variant) {
        DsBtn.Primary -> c.brandPrimary
        DsBtn.Danger -> c.stateErrorPrimary
        DsBtn.Outlined -> androidx.compose.ui.graphics.Color.Transparent
    }
    val bg = when (variant) {
        DsBtn.Outlined -> if (pressed) c.bgSkeleton else androidx.compose.ui.graphics.Color.Transparent
        else -> if (pressed) restBg.copy(alpha = 0.8f) else restBg
    }
    val fg = if (variant == DsBtn.Outlined) c.labelPrimary
        else androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    Box(Modifier.defaultMinSize(minWidth = 72.dp, minHeight = 40.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(bg)
        .then(if (variant == DsBtn.Outlined)
            Modifier.border(1.dp, c.borderL2, RoundedCornerShape(16.dp)) else Modifier)
        .padding(horizontal = 14.dp, vertical = 7.dp)
        .clickable(interactionSource = pressSrc, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, lineHeight = 22.sp,
            fontWeight = if (variant == DsBtn.Outlined) FontWeight.Normal else FontWeight.Medium,
            color = fg)
    }
}

// ref base/Modal: overlay rgba(0,0,0,.36) (dark .6) + backdrop-blur(2px);
// card w380 max(100vw-32), padding 22/24, r24, bg-layer-2, inset borderInverted;
// title 16/24 w500; body 14/22 secondary; footer end gap8 margin-top20
// Popup, not Dialog: platform Dialog adds its own 0.6 dim behind (double dim).
// ref .overlay backdrop-filter: blur(2px) — Popup windows can't blur what's
// behind them, and RenderEffect is a no-op on this emulator's GPU path, so
// the backdrop relies on the dim overlay only (see decisions.md).

@Composable
private fun DsModal(title: String, body: String?, onDismiss: () -> Unit,
                    buttons: @Composable RowScope.() -> Unit) {
    val c = DsTheme.colors
    val dark = c.bgBase.red < 0.3f
    val atWindowOrigin = remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize) =
                androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = atWindowOrigin,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        Box(Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (dark) 0.6f else 0.36f))
            .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center) {
            Column(
                // ref width: min(380, 100vw-32) — pad outside the card so the
                // card itself (bg + radius) spans the full constraint
                Modifier.padding(horizontal = 16.dp)
                    .widthIn(max = 380.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
                    .background(c.bgLayer2)
                    .then(if (c.borderInverted.alpha > 0f)
                        Modifier.border(1.dp, c.borderInverted, RoundedCornerShape(24.dp)) else Modifier)
                    // top pad +1.1dp / bottom -1.1dp: measured content sits ~3px
                    // high inside the card vs ref at equal total height
                    .padding(start = 24.dp, end = 24.dp, top = 23.1.dp, bottom = 20.9.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, color = c.labelPrimary)
                if (body != null) {
                    // measured: ref gaps are ~3px (1.1dp) wider after the title
                    // and ~5px (1.8dp) wider before the footer than 12/20dp give
                    Spacer(Modifier.height(13.1.dp))
                    Text(body, fontSize = 14.sp, lineHeight = 22.sp, color = c.labelSecondary)
                }
                Row(Modifier.fillMaxWidth().padding(top = 21.8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons)
            }
        }
    }
}

private fun formatSearchDate(tsMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val cal = searchCalLocal.get()!!
    cal.timeInMillis = nowMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val dayMs = 86_400_000L
    return when {
        tsMs >= startOfToday -> Strings.get("sessionHistoryTimelineLabelToday")
        tsMs >= startOfToday - dayMs -> Strings.get("sessionHistoryTimelineLabelYesterday")
        else -> (if (Strings.isZh()) searchZhFmtLocal else searchEnFmtLocal).get()!!
            .format(java.util.Date(tsMs))
    }
}

@Composable
private fun SearchSessionRow(
    s: SessionSummary,
    snippet: String,
    query: String,
    onClick: () -> Unit,
) {
    val c = DsTheme.colors
    val dateLabel = remember(s.updatedAt) { formatSearchDate(s.updatedAt) }
    val highlightedSnippet = remember(snippet, query, c.brandText, c.labelSecondary) {
        buildAnnotatedString {
            if (query.isEmpty()) {
                append(snippet)
            } else {
                var pos = 0
                while (pos < snippet.length) {
                    val idx = snippet.indexOf(query, pos, ignoreCase = true)
                    if (idx < 0) {
                        append(snippet.substring(pos))
                        break
                    }
                    if (idx > pos) append(snippet.substring(pos, idx))
                    withStyle(SpanStyle(color = c.brandText, fontWeight = FontWeight.Medium)) {
                        append(snippet.substring(idx, idx + query.length))
                    }
                    pos = idx + query.length
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SiderRowShape)
            .padding(horizontal = 6.dp, vertical = 12.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painterResource(R.drawable.ic_chat_item),
            null,
            tint = c.labelSecondary,
            modifier = Modifier.padding(top = 3.dp).size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title.ifBlank { Strings.get("untitledChat") },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dateLabel,
                    fontSize = 13.sp,
                    color = c.labelTertiary,
                )
            }
            if (snippet.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    highlightedSnippet,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = c.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class SessionPressPhase { Rest, Light, Pressed }

private data class SessionPressGesture(
    val sessionId: String,
    val phase: SessionPressPhase,
)

private fun allPinned(selected: Set<String>, pinned: Collection<String>) =
    selected.isNotEmpty() && pinned.containsAll(selected)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SiderRow(s: SessionSummary, active: Boolean, selectionMode: Boolean, checked: Boolean,
                     isPinned: Boolean = false,
                     pressPhase: SessionPressPhase,
                     onClick: () -> Unit, onLongClick: () -> Unit,
                     onMenuClick: () -> Unit = {},
                     menuOpen: Boolean = false, onMenuDismiss: () -> Unit = {},
                     onRename: () -> Unit = {}, onPin: () -> Unit = {},
                     onMultiSelect: () -> Unit = {}, onDelete: () -> Unit = {}) {
    val c = DsTheme.colors
    Box {
    // [data-android] .item: h48, padding-inline 7/4, r12, font 16;
    // active: bg nav-item-active-accent + color brand-primary-invert
    val pressSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by pressSrc.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().height(44.8.dp)
            .clip(RoundedCornerShape(16.dp))
            // selection rows render as plain .select buttons — no active accent;
            // ref .item:active -> bg-skeleton
            .background(
                when {
                    pressPhase == SessionPressPhase.Light ->
                        if (c.dark) c.sidebarNavItemActiveAccent.copy(alpha = 0.72f) else Color(0xFFEDF1FE)
                    pressPhase == SessionPressPhase.Pressed ->
                        if (c.dark) c.sidebarNavItemActiveAccent else Color(0xFFE0E8F1)
                    pressed -> DsTheme.pressTint(pressed, c.bgSkeleton)
                    active && !selectionMode -> c.sidebarNavItemActiveAccent
                    else -> Color.Transparent
                }
            )
            .combinedClickable(pressSrc, null, onClick = onClick, onLongClick = onLongClick)
            .padding(start = 20.62.dp, end = 20.62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            // .select > span: 19px circle 1.5px border, checked = filled radial
            Box(
                Modifier.size(19.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) c.brandText else androidx.compose.ui.graphics.Color.Transparent)
                    .then(
                        if (!checked) Modifier.drawBehind {
                            drawCircle(androidx.compose.ui.graphics.Color(0xFFC9C9C9), radius = size.minDimension / 2 - 0.75.dp.toPx(),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        } else Modifier
                    ),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(s.title.ifEmpty { Strings.get("untitledChat") }, fontSize = 15.sp, lineHeight = 22.4.sp,
            color = when {
                pressPhase != SessionPressPhase.Rest -> if (c.dark) c.brandText else Color(0xFF3269EF)
                active && !selectionMode -> c.brandText
                else -> c.labelPrimary
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (active && !selectionMode) {
            val menuInteractionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }
            Box(
                Modifier
                    .width(44.8f.dp)
                    .height(44.8f.dp)
                    .clickable(
                        interactionSource = menuInteractionSource,
                        indication = null,
                        onClick = onMenuClick,
                    )
                    .semantics { },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(18.9f.dp)
                        .semantics { contentDescription = Strings.get("showMenu") },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_ellipsis),
                        contentDescription = null,
                        tint = c.labelPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
    if (menuOpen) {
        // ref base/Menu: anchored at the item's (invisible) ellipsis — align start
        // -5px, offset +4 below; menu clamps to viewport with 8px padding, so its
        // right edge lands at vw-8 and it can overflow the drawer into the scrim.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val vw = androidx.compose.ui.platform.LocalView.current.width.let {
            if (it > 0) it / density.density else 360f
        }
        val edge = kotlin.math.min(vw - 68.4f, 380f)
        val offX = with(density) { (vw - 8f - (edge - 24f)).dp.roundToPx() }
        val offY = with(density) { 44.dp.roundToPx() } // item h48, icon btn bottom ≈ +40, +4 gap
        androidx.compose.ui.window.Popup(
            alignment = Alignment.TopEnd,
            offset = androidx.compose.ui.unit.IntOffset(offX, offY),
            onDismissRequest = onMenuDismiss,
            // focusable so the menu reaches the accessibility tree (uiautomator
            // cannot see non-focusable popups) — ref menu is a real focusable listbox
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            // .menu: min-width 124, pad 4, r12, bg menu, layered shadow
            androidx.compose.material3.Surface(
                color = c.menu, shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                // fixed 124: fillMaxWidth children otherwise widen the popup
                // and shift its aligned right edge off-screen-left
                modifier = Modifier.width(124.dp),
            ) {
                Column(Modifier.padding(4.dp)) {
                    SiderMenuOption(R.drawable.ic_edit, Strings.get("sessionItemMenuRenameOption"), c.labelPrimary, onRename)
                    SiderMenuOption(
                        R.drawable.ic_pin,
                        Strings.get(if (isPinned) "androidUnpin" else "androidPinned"),
                        c.labelPrimary,
                        onPin,
                    )
                    SiderMenuOption(R.drawable.ic_select_chats, Strings.get("androidMultiSelect"), c.labelPrimary, onMultiSelect)
                    SiderMenuOption(R.drawable.ic_trash, Strings.get("delete"), c.stateErrorPrimary, onDelete)
                }
            }
        }
    }
}
}

// .option: flex, gap 8, padding 8px 10px, r8, 14/24; .danger -> error color
@Composable
private fun SiderMenuOption(icon: Int, label: String, color: androidx.compose.ui.graphics.Color, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onSelect)
            .height(40.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, lineHeight = 24.sp, color = color)
    }
}
