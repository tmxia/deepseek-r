package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.Source
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 当前消息的搜索来源列表与打开来源面板的回调（面板挂在会话层） */
val LocalSources = compositionLocalOf<List<Source>> { emptyList() }
val LocalOpenSources = compositionLocalOf<(List<Source>) -> Unit> { {} }

private fun siteName(url: String): String = try {
    java.net.URL(url).host.removePrefix("www.")
} catch (_: Exception) { "" }

private fun JsonObject.str(key: String): String? {
    val prim = get(key) as? JsonPrimitive ?: return null
    if (prim is JsonNull) return null
    return prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray

private val SEARCH_TOOL_NAMES = setOf("search", "deep_search", "image_search", "deep_image_search")

private fun isDeepSearchKind(kind: String): Boolean =
    kind == "deep_search" || kind == "deep_image_search"

private fun isImageSearchKind(kind: String): Boolean =
    kind == "image_search" || kind == "deep_image_search"

/** ref SourceRegistry：按出现顺序注册、按 url 去重 */
private class Registry {
    val list = ArrayList<Source>()
    private val byUrl = HashMap<String, Source>()

    fun add(url: String?, title: String?, publishedAt: String?, item: JsonObject?): Source {
        val u = url ?: ""
        val existing = byUrl[u]
        if (existing != null) {
            var updated = existing
            var changed = false
            if (updated.siteName == siteName(u) && item != null) {
                val sn = item.str("site_name")
                if (sn != null) {
                    updated = updated.copy(siteName = sn)
                    changed = true
                }
            }
            if (updated.siteIcon == null && item != null) {
                val icon = item.str("site_icon")
                if (icon != null) {
                    updated = updated.copy(siteIcon = icon)
                    changed = true
                }
            }
            if (updated.snippet == null && item != null) {
                val snippet = item.str("snippet")
                if (snippet != null) {
                    updated = updated.copy(snippet = snippet)
                    changed = true
                }
            }
            if ((updated.title.isNullOrEmpty() || updated.title == u) && !title.isNullOrBlank()) {
                updated = updated.copy(title = title.trim())
                changed = true
            }
            if (updated.publishedAt == null && !publishedAt.isNullOrEmpty()) {
                updated = updated.copy(publishedAt = publishedAt)
                changed = true
            }
            if (changed) {
                val idx = list.indexOf(existing)
                if (idx >= 0) list[idx] = updated
                byUrl[u] = updated
            }
            return updated
        }
        val sn = item?.str("site_name") ?: siteName(u)
        val s = Source(
            index = list.size + 1,
            url = u,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: title,
            siteName = sn,
            publishedAt = publishedAt?.takeIf { it.isNotEmpty() },
            snippet = item?.str("snippet"),
            siteIcon = item?.str("site_icon"),
        )
        list.add(s)
        byUrl[u] = s
        return s
    }
}

/** ref view.ts：把 server_tool_use + 结果块、文本引用注册成来源列表 */
fun searchSources(blocks: List<ReplyBlock>): List<Source> {
    val registry = Registry()
    val results = HashMap<String, JsonObject>()
    for (b in blocks) {
        if (b is ReplyBlock.Tool) {
            val p = b.payload
            val t = p.str("type")
            if (t == "web_search_tool_result" || t == "web_fetch_tool_result") {
                p.str("tool_use_id")?.let { results[it] = p }
            }
        }
    }
    for (b in blocks) {
        when (b) {
            is ReplyBlock.Tool -> {
                val p = b.payload
                if (p.str("type") != "server_tool_use") continue
                val r = p.str("id")?.let { results[it] } ?: continue
                when (r.str("type")) {
                    "web_search_tool_result" -> {
                        val content = r.arr("content") ?: continue
                        for (el in content) {
                            val item = el as? JsonObject ?: continue
                            if (item.str("type") != "web_search_result") continue
                            registry.add(
                                item.str("url") ?: "",
                                item.str("title"),
                                item.str("page_age"),
                                item,
                            )
                        }
                    }
                    "web_fetch_tool_result" -> {
                        val content = r.obj("content") ?: continue
                        val kind = p.str("kind")
                        val action = p.obj("input")?.str("action")
                        if (content.str("type") == "web_fetch_result" &&
                            (kind == "deep_search" || action == "open_page")
                        ) {
                            registry.add(
                                content.str("url"),
                                content.obj("content")?.str("title"),
                                content.str("published_at"),
                                null,
                            )
                        }
                    }
                }
            }
            is ReplyBlock.Text -> for (cit in b.citations) {
                registry.add(cit.url, cit.title, null, null)
            }
            else -> {}
        }
    }
    return registry.list
}

/** 把文本块末尾补上 [citation:N] 标记（ref joinTextBlocks 的行为） */
fun withCitationMarkers(blocks: List<ReplyBlock>, sources: List<Source>): List<ReplyBlock> {
    if (sources.isEmpty()) return blocks
    val indexOf = HashMap<String, Int>()
    for (s in sources) indexOf[s.url] = s.index
    return blocks.map { b ->
        if (b is ReplyBlock.Text && b.citations.isNotEmpty()) {
            val marks = b.citations.mapNotNull { indexOf[it.url] }.distinct()
                .joinToString("") { "[citation:$it]" }
            if (marks.isEmpty()) b else b.copy(text = b.text.trimEnd() + marks)
        } else b
    }
}

/** 单个 server_tool_use 块对应一个搜索 step；返回(该次调用注册的来源, 是否已完成) */
sealed class ToolStep {
    data class Search(
        val sources: List<Source>,
        val status: ToolStatus,
        val kind: String = "search",
        val queries: List<String> = emptyList(),
    ) : ToolStep()
    data class Open(val sources: List<Source>, val status: ToolStatus) : ToolStep()
    data class Find(val pattern: String, val status: ToolStatus) : ToolStep()
    data class ReadLink(val url: String, val title: String, val status: ToolStatus) : ToolStep()
}

enum class ToolStatus { WIP, DONE, FAILED }

private fun toolStatus(status: String?): ToolStatus = when (status) {
    null -> ToolStatus.WIP
    "completed", "success" -> ToolStatus.DONE
    else -> ToolStatus.FAILED
}

/** 工具块 tool_use_id → payload 映射；同一回复内只建一次，避免每个工具块重复全量遍历。 */
fun buildToolById(allBlocks: List<ReplyBlock>): Map<String, JsonObject> {
    val byId = HashMap<String, JsonObject>()
    for (b in allBlocks) {
        if (b !is ReplyBlock.Tool) continue
        b.payload.str("tool_use_id")?.let { byId[it] = b.payload }
    }
    return byId
}

fun toolStep(
    block: ReplyBlock.Tool,
    allBlocks: List<ReplyBlock>,
    registry: List<Source>,
    streaming: Boolean = true,
): ToolStep? = toolStepWithById(block, buildToolById(allBlocks), registry, streaming)

internal fun toolStepWithById(
    block: ReplyBlock.Tool,
    byId: Map<String, JsonObject>,
    registry: List<Source>,
    streaming: Boolean = true,
): ToolStep? {
    val p = block.payload
    if (p.str("type") != "server_tool_use") return null
    val input = p.obj("input") ?: return null
    val action = input.str("action") ?: ""
    val kind = p.str("kind") ?: action.ifEmpty { "search" }
    val r = p.str("id")?.let { byId[it] }
    val isSearch = action in SEARCH_TOOL_NAMES || (action.isEmpty() && kind in SEARCH_TOOL_NAMES)
    if (isSearch) {
        val queries = buildList {
            input.arr("queries")?.forEach { el ->
                (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let(::add)
            }
            if (isEmpty()) input.str("query")?.let(::add)
        }
        val found = ArrayList<Source>()
        val status = when {
            r == null -> if (streaming) ToolStatus.WIP else ToolStatus.FAILED
            r.str("status") == "error" -> ToolStatus.FAILED
            else -> {
                val content = r.arr("content") ?: JsonArray(emptyList())
                val hasError = content.any { el ->
                    (el as? JsonObject)?.str("type") == "web_search_tool_result_error"
                }
                if (hasError) {
                    ToolStatus.FAILED
                } else {
                    for (el in content) {
                        val item = el as? JsonObject ?: continue
                        if (item.str("type") != "web_search_result") continue
                        item.str("url")?.let { url ->
                            registry.find { it.url == url }?.let { s ->
                                if (s !in found) found.add(s)
                            }
                        }
                    }
                    ToolStatus.DONE
                }
            }
        }
        return ToolStep.Search(found, status, kind = kind, queries = queries)
    }
    return when (action) {
        "open_page" -> {
            val found = ArrayList<Source>()
            val status = when {
                r == null -> if (streaming) ToolStatus.WIP else ToolStatus.FAILED
                r.str("status") == "error" -> ToolStatus.FAILED
                r.obj("content")?.str("type") == "web_fetch_result" -> {
                    r.obj("content")?.str("url")?.let { url ->
                        registry.find { it.url == url }?.let(found::add)
                    }
                    ToolStatus.DONE
                }
                else -> ToolStatus.FAILED
            }
            ToolStep.Open(found, status)
        }
        "read_link" -> {
            val fetched = r?.obj("content")
            val fetchedUrl = if (fetched?.str("type") == "web_fetch_result") fetched.str("url") else null
            val url = input.str("url") ?: fetchedUrl ?: return null
            val status = when {
                r == null -> if (streaming) ToolStatus.WIP else ToolStatus.FAILED
                r.str("status") == "error" -> ToolStatus.FAILED
                fetched?.str("type") == "web_fetch_result" -> ToolStatus.DONE
                else -> ToolStatus.FAILED
            }
            val title = fetched?.obj("content")?.str("title") ?: url
            ToolStep.ReadLink(url = url, title = title, status = status)
        }
        "find_in_page", "find_pattern", "find" -> {
            val pattern = input.str("pattern") ?: input.str("query") ?: ""
            val status = when {
                r == null -> if (streaming) ToolStatus.WIP else ToolStatus.FAILED
                r.str("status") == "error" -> ToolStatus.FAILED
                r.str("type") == "web_fetch_tool_result" ->
                    if (r.obj("content")?.str("type") == "web_fetch_result") ToolStatus.DONE else ToolStatus.FAILED
                else -> toolStatus(r.str("status"))
            }
            ToolStep.Find(pattern = pattern, status = status)
        }
        else -> null
    }
}

fun formatSourceDate(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val d = fmt.parse(iso.substringBefore('.').substringBefore('+'))
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(d!!)
    } catch (_: Exception) { iso }
}

private object SiteIconCache {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private val cache = object : android.util.LruCache<String, ImageBitmap>(64) {}
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache.get(url) }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        if (url in failed) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            cached(url)?.let { return@withContext it }
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        failed.add(url)
                        return@withContext null
                    }
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp == null) {
                        failed.add(url)
                        return@withContext null
                    }
                    val img = bmp.asImageBitmap()
                    synchronized(cache) { cache.put(url, img) }
                    img
                }
            } catch (_: Exception) {
                failed.add(url)
                null
            }
        }
    }
}

/** ref SiteLogo：优先加载 source.siteIcon，加载中/失败退回链接占位图标 */
@Composable
fun SiteLogo(
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    url: String? = null,
) {
    val c = DsTheme.colors
    val iconUrl = url?.trim()?.takeIf { it.isNotEmpty() }
    var loaded by remember(iconUrl) { mutableStateOf(iconUrl?.let { SiteIconCache.cached(it) }) }
    LaunchedEffect(iconUrl) {
        if (iconUrl != null && loaded == null) {
            loaded = SiteIconCache.load(iconUrl)
        }
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(c.markdownCitation),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded != null) {
            Image(
                bitmap = loaded!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painterResource(R.drawable.ic_link), null,
                tint = c.labelTertiary, modifier = Modifier.size(size * 0.7f))
        }
    }
}

/** ref SiteThumbnails：前 4 个站点重叠缩略图 */
@Composable
private fun SiteThumbnails(sources: List<Source>) {
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        sources.take(4).forEachIndexed { i, source ->
            val overlap = if (i == 0) 0.dp else (-4).dp
            SiteLogo(
                size = 16.dp,
                modifier = Modifier
                    .offset(x = overlap)
                    .border(1.dp, DsTheme.colors.bgBase, CircleShape),
                url = source.siteIcon,
            )
        }
    }
}

/** ref .summary：消息下方的来源汇总胶囊 */
@Composable
fun SourcesSummary(sources: List<Source>, onClick: () -> Unit) {
    val c = DsTheme.colors
    Row(
        Modifier
            .padding(top = 16.dp)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFFD4D4D4), RoundedCornerShape(100.dp))
            .clip(RoundedCornerShape(100.dp))
            .background(c.bgBase)
            .padding(start = 14.dp, end = 12.dp, top = 5.dp, bottom = 5.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteThumbnails(sources)
        Spacer(Modifier.width(6.dp))
        Text(
            Strings.get(if (sources.size == 1) "messageSearchSummary" else "messageSearchSummaries")
                .replace("{count}", sources.size.toString()),
            fontSize = 15.sp,
            color = if (c.dark) androidx.compose.ui.graphics.Color(0xFFE5E5E5)
                    else androidx.compose.ui.graphics.Color(0xFF545557),
        )
    }
}

/** ref SearchRow：「已阅读 N 个网页」或「搜索到 N 个网页」步骤行，点击打开来源面板 */
@Composable
fun SearchStepRow(
    step: ToolStep.Search,
    onOpen: (List<Source>) -> Unit,
    stopped: Boolean = false,
) {
    val c = DsTheme.colors
    val clickable = step.status == ToolStatus.DONE && step.sources.isNotEmpty()
    var tick by remember(step.queries) { mutableIntStateOf(0) }
    LaunchedEffect(step.status, step.queries.size) {
        if (step.status == ToolStatus.WIP && step.queries.size > 1) {
            while (isActive) {
                delay(2000)
                tick++
            }
        }
    }
    val isDeep = isDeepSearchKind(step.kind)
    val label = when (step.status) {
        ToolStatus.WIP -> when {
            step.queries.isNotEmpty() ->
                "${Strings.get("toolSearchIntro")} ${step.queries[tick % step.queries.size]}"
            isImageSearchKind(step.kind) -> Strings.get("imageSearching")
            else -> Strings.get("messageSearching")
        }
        ToolStatus.FAILED -> Strings.get(if (stopped) "searchStopped" else "toolSearchFail")
        ToolStatus.DONE -> when {
            step.sources.isEmpty() ->
                Strings.get(if (isDeep) "toolSearchNoResult" else "messageSearchNoResult")
            step.sources.size == 1 ->
                Strings.get(if (isDeep) "toolSearch1Result" else "messageSearchResult")
            else -> Strings.get(
                if (isDeep) "toolSearchResults" else "messageSearchResults",
                "count" to step.sources.size,
            )
        }
    }
    Row(
        Modifier
            .height(34.dp)
            .then(if (clickable) Modifier.clickable { onOpen(step.sources) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = c.labelSecondary,
            modifier = Modifier.padding(1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 14.sp, lineHeight = 24.sp, color = c.labelSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (clickable) SiteThumbnails(step.sources)
    }
}

@Composable
fun ReadLinkStepRow(step: ToolStep.ReadLink) {
    val c = DsTheme.colors
    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_link), null, tint = c.labelSecondary,
            modifier = Modifier.padding(1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "${Strings.get("readingLink")} ${step.title}",
            fontSize = 14.sp, lineHeight = 24.sp, color = c.labelSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun OpenStepRow(step: ToolStep.Open, onOpen: (List<Source>) -> Unit) {
    val c = DsTheme.colors
    val clickable = step.status == ToolStatus.DONE && step.sources.isNotEmpty()
    Row(
        Modifier
            .height(34.dp)
            .then(if (clickable) Modifier.clickable { onOpen(step.sources) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_link), null, tint = c.labelSecondary,
            modifier = Modifier.padding(1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        val label = when {
            step.status == ToolStatus.WIP -> Strings.get("androidToolOpening")
            step.status == ToolStatus.FAILED || step.sources.isEmpty() -> Strings.get("toolOpenFail")
            step.sources.size == 1 -> Strings.get("androidToolOpenOne")
            else -> Strings.get("androidToolOpenMany").replace("{count}", step.sources.size.toString())
        }
        Text(label, fontSize = 14.sp, lineHeight = 24.sp, color = c.labelSecondary)
        if (clickable) SiteThumbnails(step.sources)
    }
}

@Composable
fun FindStepRow(step: ToolStep.Find) {
    val c = DsTheme.colors
    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search), null, tint = c.labelSecondary,
            modifier = Modifier.padding(1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        val label = when (step.status) {
            ToolStatus.WIP -> Strings.get("messageToolFindWip")
            ToolStatus.DONE -> Strings.get("messageToolFindFinished")
                .replace("{query}", step.pattern)
            ToolStatus.FAILED -> Strings.get("messageToolFindNoResult")
                .replace("{query}", step.pattern)
        }
        Text(label, fontSize = 14.sp, lineHeight = 24.sp, color = c.labelSecondary)
    }
}

/** ref AndroidSearchPanel：底部来源面板（scrim + 圆角 sheet） */
@Composable
fun SearchSourcesPanel(sources: List<Source>, numbered: Boolean, onClose: () -> Unit) {
    val c = DsTheme.colors
    val panelEase = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }
    val panelScope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var sheetH by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        prog.animateTo(1f, androidx.compose.animation.core.tween(240, easing = panelEase))
    }
    val requestClose = {
        if (!closing) {
            closing = true
            panelScope.launch {
                prog.animateTo(0f, androidx.compose.animation.core.tween(240, easing = panelEase))
                onClose()
            }
        }
        Unit
    }
    val popupPositionProvider = androidx.compose.runtime.remember {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ) = androidx.compose.ui.unit.IntOffset(-anchorBounds.left, -anchorBounds.top)
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = popupPositionProvider,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        onDismissRequest = requestClose,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 6.5.dp)) {
        val dragFrac = if (sheetH > 1) (dragOffsetY / sheetH).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x52000000).copy(alpha = 0x52 / 255f * prog.value * (1f - dragFrac)))
                .clickable(interactionSource = rememberMutableInteraction(),
                    indication = null) { requestClose() }
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = this.maxHeight - 44.dp)
                .onSizeChanged { sheetH = it.height }
                .offset { androidx.compose.ui.unit.IntOffset(0, ((1f - prog.value) * sheetH + dragOffsetY).toInt()) }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(if (c.dark) androidx.compose.ui.graphics.Color(0xFF292929) else c.bgBase)
                .pointerInput(sheetH) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > sheetH * 0.25f || dragOffsetY > 120.dp.toPx()) requestClose()
                            else dragOffsetY = 0f
                        },
                        onDragCancel = { dragOffsetY = 0f },
                        onVerticalDrag = { _, dy ->
                            dragOffsetY = (dragOffsetY + dy).coerceAtLeast(0f)
                        },
                    )
                }
        ) {
            // ref .header: 56px, 16.75px/600 居中标题 + 右侧 30px 圆形关闭
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                Text(Strings.get("searchResult"), fontSize = 16.75.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = c.labelPrimary)
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c.interactiveBgHover)
                        .clickable(onClick = requestClose),
                    contentAlignment = Alignment.Center,
                ) {
                    // ref 内联 svg：两条 1.8px 描边对角线（20px viewBox）
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                        val stroke = 1.8.dp.toPx()
                        val s = size.width
                        drawLine(c.labelPrimary, androidx.compose.ui.geometry.Offset(5.2f/20f*s, 5.2f/20f*s),
                            androidx.compose.ui.geometry.Offset(14.8f/20f*s, 14.8f/20f*s), strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(c.labelPrimary, androidx.compose.ui.geometry.Offset(14.8f/20f*s, 5.2f/20f*s),
                            androidx.compose.ui.geometry.Offset(5.2f/20f*s, 14.8f/20f*s), strokeWidth = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
                // measured: first card ink ~6px early vs ref at spec 16px pad
                item { Spacer(Modifier.height(15.3.dp)) }
                itemsIndexed(sources) { i, source ->
                    // ref spec 31px; measured pitch is ~7.5px (2.7dp) short per card
                    if (i > 0) Spacer(Modifier.height(33.7.dp))
                    SourceItem(source, numbered = numbered,
                        single = sources.size == 1)
                }
                // ref spec is 32px; trimmed 5dp to match measured sheet height
                item { Spacer(Modifier.height(27.dp)) }
            }
        }
    }
    }
}

@Composable
private fun rememberMutableInteraction(): androidx.compose.foundation.interaction.MutableInteractionSource =
    androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

/** ref .result：meta(logo+站名+日期+序号) + 标题 + 摘要 */
@Composable
private fun SourceItem(source: Source, numbered: Boolean, single: Boolean) {
    val c = DsTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ref AndroidSearchPanel .item:active -> opacity .65
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Column(Modifier.fillMaxWidth().alpha(DsTheme.pressAlpha(pressed, 0.65f)).clickable(src, null) {
        try {
            io.github.lijiahua1024.dsnative.SourceBrowserActivity.open(ctx, source.url, c.dark)
        } catch (_: Exception) {}
    }) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteLogo(20.dp, url = source.siteIcon)
            Spacer(Modifier.width(10.36.dp))
            // ref .meta: siteName/date flex-shrink, .number { margin-left: auto }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(source.siteName, fontSize = 14.sp, lineHeight = 20.sp,
                    letterSpacing = 0.10.sp,
                    color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                val date = formatSourceDate(source.publishedAt)
                if (date.isNotEmpty()) {
                    Spacer(Modifier.width(10.36.dp))
                    Text(date, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.10.sp, color = c.searchResultDate,
                        maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.36.dp))
            if (numbered) {
                Box(
                    Modifier
                        .size(20.dp)
                        .border(1.dp, c.borderL2, CircleShape)
                        .background(c.interactiveBgHover, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(source.index.toString(), fontSize = 12.sp, lineHeight = 18.sp,
                        color = c.searchResultNumber, fontFamily = FontFamily.Monospace)
                }
            }
        }
        source.title?.let {
            Text(it, fontSize = 17.sp, lineHeight = 26.sp, letterSpacing = 0.10.sp, color = c.labelPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp))
        }
        source.snippet?.let {
            Text(it, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.10.sp, color = c.searchResultSnippet,
                maxLines = if (single) 4 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp))
        }
    }
}
