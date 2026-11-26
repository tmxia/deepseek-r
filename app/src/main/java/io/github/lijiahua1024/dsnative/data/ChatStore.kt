package io.github.lijiahua1024.dsnative.data

import android.content.Context
import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.ApiErrorKind
import io.github.lijiahua1024.dsnative.api.BuiltMessageRequest
import io.github.lijiahua1024.dsnative.api.FALLBACK_NON_THINKING_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.FALLBACK_THINKING_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.FILE_RETRY_LIMIT
import io.github.lijiahua1024.dsnative.api.IMAGE_REPAIR_RETRY_LIMIT
import io.github.lijiahua1024.dsnative.api.MANY_IMAGES_COUNT
import io.github.lijiahua1024.dsnative.api.MANY_IMAGES_MAX_EDGE
import io.github.lijiahua1024.dsnative.api.MAX_IMAGE_EDGE
import io.github.lijiahua1024.dsnative.api.MAX_OUTPUT_TOKENS
import io.github.lijiahua1024.dsnative.api.MODEL
import io.github.lijiahua1024.dsnative.api.SYSTEM_PROMPT
import io.github.lijiahua1024.dsnative.api.StreamSession
import io.github.lijiahua1024.dsnative.api.TRANSIENT_RETRY_LIMIT
import io.github.lijiahua1024.dsnative.api.backoffDelayMs
import io.github.lijiahua1024.dsnative.api.buildCreateMessageRequest
import io.github.lijiahua1024.dsnative.api.estimatePrefixTokens
import io.github.lijiahua1024.dsnative.api.estimateUserTokens
import io.github.lijiahua1024.dsnative.api.extractFileIds
import io.github.lijiahua1024.dsnative.api.getFileErrorCode
import io.github.lijiahua1024.dsnative.api.getRejectedFileIdAtPosition
import io.github.lijiahua1024.dsnative.api.getRejectedImagePosition
import io.github.lijiahua1024.dsnative.api.isContextOverflow
import io.github.lijiahua1024.dsnative.api.isExtendedInputTooLong
import io.github.lijiahua1024.dsnative.api.isTransientRetryableError
import io.github.lijiahua1024.dsnative.api.isUploadParseFailed
import io.github.lijiahua1024.dsnative.api.latestReminderText
import io.github.lijiahua1024.dsnative.api.openMessageStreamRaw
import io.github.lijiahua1024.dsnative.api.prepareImageForUpload
import io.github.lijiahua1024.dsnative.api.uploadFile
import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** sessions 排序：updatedAt 新→旧 */
private fun sortSessions(sessions: List<SessionSummary>) = sessions.sortedByDescending { it.updatedAt }

internal fun orphanAttachmentPaths(
    removed: Session?,
    removedDrafts: List<DraftAttachment>,
    remainingSessions: Collection<Session>,
    remainingDrafts: Collection<List<DraftAttachment>>,
): Set<String> {
    val candidates = buildSet {
        removed?.messages?.forEach { m ->
            if (m is Message.User) m.files.mapNotNullTo(this) { it.localPath }
        }
        removedDrafts.forEach { add(it.localPath) }
    }
    if (candidates.isEmpty()) return emptySet()
    val referenced = buildSet {
        remainingSessions.forEach { s ->
            s.messages.forEach { m ->
                if (m is Message.User) m.files.mapNotNullTo(this) { it.localPath }
            }
        }
        remainingDrafts.forEach { list ->
            list.forEach { add(it.localPath) }
        }
    }
    return candidates - referenced
}

/** 原子写入文件（先写同目录 .tmp 再 renameTo），防止写中途进程被杀损坏会话 JSON。 */
internal fun atomicWriteText(target: File, content: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp.${System.nanoTime()}")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                out.fd.sync()
            } catch (_: Exception) {
                // 部分文件系统不支持 fsync，忽略即可
            }
        }
        if (!tmp.renameTo(target)) {
            if (target.exists() && target.delete() && tmp.renameTo(target)) {
                return
            }
            throw IOException("Failed to move ${tmp.name} to ${target.name}")
        }
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}

internal fun toAssistantMessageParam(
    reply: Message.Assistant,
    prefix: Boolean = false,
    thinking: Boolean = false,
): JsonObject {
    val content = mutableListOf<JsonObject>()
    val unsignedReadLinks = HashSet<String>()
    for (block in reply.blocks) {
        if (block !is ReplyBlock.Tool) continue
        val payload = block.payload
        val pType = (payload["type"] as? JsonPrimitive)?.content
        val pKind = (payload["kind"] as? JsonPrimitive)?.content
        val cObj = payload["content"] as? JsonObject
        val cType = (cObj?.get("type") as? JsonPrimitive)?.content
        val sig = (payload["signature"] as? JsonPrimitive)?.content
        if (pType == "web_fetch_tool_result" && pKind == "read_link" && cType == "web_fetch_result" && sig.isNullOrBlank()) {
            (payload["tool_use_id"] as? JsonPrimitive)?.content?.let { unsignedReadLinks.add(it) }
        }
    }
    var pendingText: StringBuilder? = null
    fun flushText() {
        val sb = pendingText ?: return
        pendingText = null
        if (sb.isNotEmpty()) {
            content.add(buildJsonObject {
                put("type", "text")
                put("text", sb.toString())
            })
        }
    }
    for (block in reply.blocks) {
        if (prefix && block is ReplyBlock.Thinking && block.thinking.isBlank()) {
            continue
        }
        when (block) {
            is ReplyBlock.Text -> {
                if (block.isTemplate) {
                    flushText()
                    continue
                }
                val sb = pendingText
                if (sb != null) {
                    sb.append(block.text)
                } else if (block.text.isNotEmpty()) {
                    val prevType = (content.lastOrNull()?.get("type") as? JsonPrimitive)?.content
                    val joiner = if (prevType == "text") "\n\n" else ""
                    pendingText = StringBuilder(joiner).append(block.text)
                }
            }
            is ReplyBlock.Thinking -> {
                flushText()
                if (prefix && thinking) {
                    content.add(buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking)
                        put("signature", block.signature ?: "")
                    })
                }
            }
            is ReplyBlock.Tool -> {
                flushText()
                val payload = block.payload
                val pType = (payload["type"] as? JsonPrimitive)?.content
                val pKind = (payload["kind"] as? JsonPrimitive)?.content
                if (pType == "server_tool_use" && pKind == "read_link") {
                    val id = (payload["id"] as? JsonPrimitive)?.content
                    if (id == null || id !in unsignedReadLinks) {
                        content.add(payload)
                    }
                } else if (pType == "web_fetch_tool_result" && pKind == "read_link") {
                    val toolUseId = (payload["tool_use_id"] as? JsonPrimitive)?.content
                    if (toolUseId == null || toolUseId !in unsignedReadLinks) {
                        content.add(payload)
                    }
                }
            }
        }
    }
    flushText()
    return buildJsonObject {
        put("role", "assistant")
        put("content", JsonArray(content))
    }
}

/** 规范化：rootMessageId 非空时按分支链取当前可见路径 */
fun visibleMessages(session: Session): List<Message> {
    if (session.rootMessageId == null && session.messages.isEmpty()) return emptyList()
    val byId = session.messages.associateBy { it.id }
    val path = mutableListOf<Message>()
    var id = session.rootMessageId
    while (id != null) {
        val message = byId[id] ?: break
        path.add(message)
        id = message.selectedChildId
    }
    // 线性（旧数据）兼容
    if (path.isEmpty() && session.rootMessageId == null) return session.messages
    return path
}

internal fun Session.normalized(): Session {
    if (rootMessageId != null || messages.isEmpty()) {
        return if (rootMessageId != null) this
        else copy(rootMessageId = null)
    }
    return copy(
        rootMessageId = messages.first().id,
        messages = messages.mapIndexed { index, message ->
            when (message) {
                is Message.User -> message.copy(
                    parentId = messages.getOrNull(index - 1)?.id,
                    selectedChildId = messages.getOrNull(index + 1)?.id,
                )
                is Message.Assistant -> message.copy(
                    parentId = messages.getOrNull(index - 1)?.id,
                    selectedChildId = messages.getOrNull(index + 1)?.id,
                )
            }
        },
    )
}

private fun appendBranch(session: Session, parentId: String?, newMessages: List<Message>): Session {
    val normalized = session.normalized()
    val first = newMessages.first()
    val appended = newMessages.mapIndexed { index, message ->
        val parent = if (index == 0) parentId else newMessages[index - 1].id
        val child = newMessages.getOrNull(index + 1)?.id
        when (message) {
            is Message.User -> message.copy(parentId = parent, selectedChildId = child)
            is Message.Assistant -> message.copy(parentId = parent, selectedChildId = child)
        }
    }
    return normalized.copy(
        rootMessageId = if (parentId == null) first.id else normalized.rootMessageId,
        messages = normalized.messages.map { message ->
            if (message.id == parentId) {
                when (message) {
                    is Message.User -> message.copy(selectedChildId = first.id)
                    is Message.Assistant -> message.copy(selectedChildId = first.id)
                }
            } else message
        } + appended,
    )
}

private fun selectVersion(session: Session, messageId: String): Session {
    val normalized = session.normalized()
    val message = normalized.messages.find { it.id == messageId } ?: return normalized
    val visible = visibleMessages(normalized)
    if (visible.none { it.parentId == message.parentId }) return normalized
    return normalized.copy(
        rootMessageId = if (message.parentId == null) message.id else normalized.rootMessageId,
        messages = normalized.messages.map { item ->
            if (item.id == message.parentId) {
                when (item) {
                    is Message.User -> item.copy(selectedChildId = message.id)
                    is Message.Assistant -> item.copy(selectedChildId = message.id)
                }
            } else item
        },
    )
}

fun replyText(reply: Message.Assistant): String =
    reply.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("") { it.text }

private fun isUsableReply(message: Message): Boolean =
    message is Message.Assistant &&
        (message.status == ReplyStatus.DONE || message.status == ReplyStatus.STOPPED) &&
        message.stopReason != "refusal" &&
        message.blocks.none { it is ReplyBlock.Text && it.isTemplate } &&
        replyText(message).isNotEmpty()

/** 会话分组：今天 / 昨天 / 7 天内 / 30 天内 / 按月（yyyy-MM） */
fun groupSessions(sessions: List<SessionSummary>, now: Long): List<Pair<String, List<SessionSummary>>> {
    if (sessions.isEmpty()) return emptyList()
    val day = 86_400_000L
    val cal = java.util.Calendar.getInstance()
    var monthFmt: SimpleDateFormat? = null
    val groups = mutableListOf<Pair<String, MutableList<SessionSummary>>>()
    for (session in sessions) {
        cal.timeInMillis = session.updatedAt
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val diff = ((now - startOfDay) / day).toInt()
        val key = when {
            diff <= 0 -> "sessionHistoryTimelineLabelToday"
            diff <= 1 -> "sessionHistoryTimelineLabelYesterday"
            diff < 7 -> "sessionHistoryTimelineLabel7days"
            diff < 30 -> "sessionHistoryTimelineLabel30days"
            else -> {
                val fmt = monthFmt ?: SimpleDateFormat("yyyy-MM", Locale.getDefault()).also { monthFmt = it }
                fmt.format(Date(session.updatedAt))
            }
        }
        val last = groups.lastOrNull()
        if (last?.first == key) last.second.add(session)
        else groups.add(key to mutableListOf(session))
    }
    return groups
}

internal fun sessionMatchesQuery(session: Session, rawQuery: String): Boolean =
    findSessionMatchSnippet(session, rawQuery) != null

internal fun findSessionMatchSnippet(session: Session, rawQuery: String): String? {
    val q = rawQuery.trim()
    if (q.isEmpty()) return ""
    fun extractSnippet(raw: String): String? {
        val pos = raw.indexOf(q, ignoreCase = true)
        if (pos < 0) return null
        val start = maxOf(0, pos - 16)
        val end = minOf(raw.length, pos + q.length + 52)
        val excerpt = raw.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return if (start > 0) "…$excerpt" else excerpt
    }
    for (msg in session.messages) {
        when (msg) {
            is Message.User -> {
                extractSnippet(msg.content)?.let { return it }
                msg.files.firstNotNullOfOrNull { extractSnippet(it.name) }?.let { return it }
            }
            is Message.Assistant -> {
                for (block in msg.blocks) {
                    val snip = when (block) {
                        is ReplyBlock.Text -> extractSnippet(block.text)
                        is ReplyBlock.Thinking -> extractSnippet(block.thinking)
                        is ReplyBlock.Tool -> null
                    }
                    if (snip != null) return snip
                }
            }
        }
    }
    return if (session.title.contains(q, ignoreCase = true)) "" else null
}

private fun createId(): String = UUID.randomUUID().toString()

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

class ChatStore(
    private val context: Context,
    val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val chatDir = File(context.filesDir, "chat").apply { mkdirs() }
    private val attachesDir = File(context.filesDir, "attaches").apply { mkdirs() }
    private val legacyCacheAttachesDir = File(context.cacheDir, "attaches")
    private val mutex = Mutex()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions

    private val _sessionCache = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessionCache: StateFlow<Map<String, Session>> = _sessionCache

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    private val _newChatRequests = MutableStateFlow(0)
    val newChatRequests: StateFlow<Int> = _newChatRequests

    private val _focusComposerRequests = MutableStateFlow(0)
    val focusComposerRequests: StateFlow<Int> = _focusComposerRequests

    /** 正在生成的会话 id 集合 */
    private val _streaming = MutableStateFlow<Set<String>>(emptySet())
    val streaming: StateFlow<Set<String>> = _streaming

    /** 历史导入/全删期间锁定交互 */
    private val _importingHistory = MutableStateFlow(false)
    val importingHistory: StateFlow<Boolean> = _importingHistory

    /** 草稿：null key = 新对话 */
    private val _drafts = MutableStateFlow<Map<String?, String>>(emptyMap())
    val drafts: StateFlow<Map<String?, String>> = _drafts

    /** 草稿附件：null key = 新对话 */
    private val _draftAttachments =
        MutableStateFlow<Map<String?, List<DraftAttachment>>>(emptyMap())
    val draftAttachments: StateFlow<Map<String?, List<DraftAttachment>>> = _draftAttachments

    private val streamJobs = mutableMapOf<String, Job>()
    private val activeStreams = mutableMapOf<String, StreamSession>()
    private val titleSessions = mutableSetOf<String>()

    init {
        scope.launch { load() }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = chatDir.listFiles { f -> f.extension == "json" && f.name != "index.json" }
                ?.mapNotNull { file ->
                    try {
                        json.decodeFromString<Session>(file.readText())
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.associateBy { it.id }
                ?: emptyMap()
            _sessionCache.value = loaded.mapValues { it.value.normalized() }
            _sessions.value = sortSessions(loaded.values.map { it.toSummary() })
            val validIds = loaded.keys
            val pinned = settings.pinnedSessions.value.filter { it in validIds }
            if (pinned != settings.pinnedSessions.value) settings.setPinnedSessions(pinned)
            _ready.value = true
        }
    }

    private fun Session.toSummary() = SessionSummary(id, title, createdAt, updatedAt)

    private val writeLocks = ConcurrentHashMap<String, Any>()

    private fun writeLockFor(sessionId: String): Any =
        writeLocks.getOrPut(sessionId) { Any() }

    private fun writeSessionSync(session: Session) {
        synchronized(writeLockFor(session.id)) {
            atomicWriteText(File(chatDir, "${session.id}.json"), json.encodeToString(session))
        }
    }

    private fun persist(sessionId: String) {
        if (!_sessionCache.value.containsKey(sessionId)) return
        scope.launch(Dispatchers.IO) {
            try {
                val latest = _sessionCache.value[sessionId] ?: return@launch
                writeSessionSync(latest)
            } catch (_: Exception) {
                // 保存失败：下次启动丢该会话的最新状态
            }
        }
    }

    private suspend fun updateSession(id: String, update: (Session) -> Session) {
        mutex.withLock {
            val previous = _sessionCache.value[id] ?: return@withLock
            val session = update(previous)
            if (session === previous) return@withLock
            _sessionCache.value = _sessionCache.value + (id to session)
            if (session.title != previous.title || session.updatedAt != previous.updatedAt) {
                _sessions.value = sortSessions(
                    _sessions.value.map { if (it.id == id) session.toSummary() else it }
                )
            }
        }
    }

    suspend fun getSession(id: String): Session? = mutex.withLock { _sessionCache.value[id] }

    fun sessionSnapshot(id: String): Session? = _sessionCache.value[id]

    fun isStreamingSnapshot(id: String?): Boolean = id != null && _streaming.value.contains(id)

    suspend fun searchSessionSnippets(query: String): Map<String, String> {
        val q = query.trim()
        val snapshot = _sessionCache.value.values.toList()
        if (q.isEmpty()) return snapshot.associate { it.id to "" }
        return withContext(Dispatchers.Default) {
            buildMap {
                for (session in snapshot) {
                    ensureActive()
                    findSessionMatchSnippet(session, q)?.let { put(session.id, it) }
                }
            }
        }
    }

    suspend fun searchSessionIds(query: String): Set<String> = searchSessionSnippets(query).keys

    fun newChat() {
        ReadAloud.stop()
        _activeId.value = null
        _newChatRequests.value += 1
    }

    fun tryStartNewChat(): Boolean {
        if (_activeId.value == null) return false
        newChat()
        return true
    }

    fun requestFocusComposer() {
        _focusComposerRequests.value += 1
    }

    fun selectSession(id: String) {
        ReadAloud.stop()
        _activeId.value = id
    }

    fun setDraft(sessionId: String?, text: String) {
        _drafts.value = _drafts.value + (sessionId to text)
    }

    fun draft(sessionId: String?): String = _drafts.value[sessionId] ?: ""

    fun draftAttachments(sessionId: String?): List<DraftAttachment> =
        _draftAttachments.value[sessionId] ?: emptyList()

    fun addDraftAttachments(sessionId: String?, items: List<DraftAttachment>) {
        _draftAttachments.value =
            _draftAttachments.value + (sessionId to (draftAttachments(sessionId) + items))
    }

    private fun isManagedAttachPath(path: String): Boolean = try {
        val parent = File(path).canonicalFile.parentFile
        parent == attachesDir.canonicalFile ||
            (legacyCacheAttachesDir.exists() && parent == legacyCacheAttachesDir.canonicalFile)
    } catch (_: Exception) {
        false
    }

    private fun deleteOrphanAttachFiles(paths: Set<String>) {
        paths.forEach { path ->
            if (isManagedAttachPath(path)) {
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
    }

    fun removeDraftAttachment(sessionId: String?, attachmentId: String) {
        val current = draftAttachments(sessionId)
        val removed = current.filter { it.id == attachmentId }
        if (removed.isEmpty()) return
        val remainingList = current.filterNot { it.id == attachmentId }
        val nextDrafts = _draftAttachments.value + (sessionId to remainingList)
        _draftAttachments.value = nextDrafts
        val orphans = orphanAttachmentPaths(null, removed, _sessionCache.value.values, nextDrafts.values)
        if (orphans.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { deleteOrphanAttachFiles(orphans) }
        }
    }

    fun replaceDraftAttachment(sessionId: String?, attachmentId: String, replacement: DraftAttachment) {
        val current = draftAttachments(sessionId)
        val removed = current.filter { it.id == attachmentId }
        if (removed.isEmpty()) {
            addDraftAttachments(sessionId, listOf(replacement))
            return
        }
        val nextList = current.map { if (it.id == attachmentId) replacement else it }
        val nextDrafts = _draftAttachments.value + (sessionId to nextList)
        _draftAttachments.value = nextDrafts
        val orphans = orphanAttachmentPaths(null, removed, _sessionCache.value.values, nextDrafts.values)
        if (orphans.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { deleteOrphanAttachFiles(orphans) }
        }
    }

    fun renameSession(id: String, title: String) {
        titleSessions.remove(id)
        scope.launch {
            updateSession(id) { it.copy(title = title) }
            persist(id)
        }
    }

    fun removeSession(id: String) {
        titleSessions.remove(id)
        scope.launch {
            var orphans = emptySet<String>()
            mutex.withLock {
                val removed = _sessionCache.value[id]
                val nextSessions = _sessionCache.value - id
                _sessionCache.value = nextSessions
                _sessions.value = _sessions.value.filter { it.id != id }
                if (_activeId.value == id) _activeId.value = null
                _drafts.value = _drafts.value - id
                val removedDrafts = _draftAttachments.value[id].orEmpty()
                val nextDrafts = _draftAttachments.value - id
                _draftAttachments.value = nextDrafts
                orphans = orphanAttachmentPaths(removed, removedDrafts, nextSessions.values, nextDrafts.values)
            }
            val pinned = settings.pinnedSessions.value
            if (id in pinned) {
                settings.setPinnedSessions(pinned.filterNot { it == id })
            }
            streamJobs.remove(id)?.cancel()
            withContext(Dispatchers.IO) {
                File(chatDir, "$id.json").delete()
                deleteOrphanAttachFiles(orphans)
            }
        }
    }

    fun removeAllSessions() {
        titleSessions.clear()
        scope.launch {
            _importingHistory.value = true
            try {
                mutex.withLock {
                    _sessionCache.value = emptyMap()
                    _sessions.value = emptyList()
                    _activeId.value = null
                    _drafts.value = emptyMap()
                    _draftAttachments.value = emptyMap()
                }
                settings.setPinnedSessions(emptyList())
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                withContext(Dispatchers.IO) {
                    chatDir.listFiles()?.forEach { it.delete() }
                    attachesDir.listFiles()?.forEach { it.delete() }
                    if (legacyCacheAttachesDir.exists()) {
                        legacyCacheAttachesDir.listFiles()?.forEach { it.delete() }
                    }
                }
            } finally {
                _importingHistory.value = false
            }
        }
    }

    fun selectVersion(sessionId: String, messageId: String) {
        scope.launch {
            updateSession(sessionId) { selectVersion(it, messageId) }
            persist(sessionId)
        }
    }

    fun setReplyReaction(sessionId: String, messageId: String, reaction: String?) {
        scope.launch {
            updateSession(sessionId) { session ->
                session.copy(messages = session.messages.map { m ->
                    if (m is Message.Assistant && m.id == messageId) m.copy(reaction = reaction) else m
                })
            }
            persist(sessionId)
        }
    }

    /** 提交草稿；null 目标表示新建会话。返回是否成功发出。 */
    fun sendMessage(targetSessionId: String?): Boolean {
        if (_importingHistory.value || !_ready.value) return false
        ReadAloud.stop()
        val text = draft(targetSessionId).trim()
        val atts = draftAttachments(targetSessionId)
        if (text.isEmpty() && atts.isEmpty()) return false
        val now = System.currentTimeMillis()
        var sessionId = targetSessionId
        if (sessionId != null && (_streaming.value.contains(sessionId) || _sessionCache.value[sessionId] == null)) {
            return false
        }
        if (sessionId == null) {
            val session = Session(createId(), "", now, now, emptyList(), null)
            sessionId = session.id
            _sessionCache.value = _sessionCache.value + (session.id to session)
            _sessions.value = sortSessions(listOf(session.toSummary()) + _sessions.value)
            _activeId.value = session.id
        }
        val current = _sessionCache.value[sessionId] ?: return false
        val history = visibleMessages(current)
        val question = Message.User(
            createId(), text,
            atts.map { a -> a.toFileAttachment(File(a.localPath).length()) },
            now)
        val reply = createReply()
        val finalId = sessionId
        scope.launch {
            updateSession(finalId) { appendBranch(it, history.lastOrNull()?.id, listOf(question, reply)).copy(updatedAt = now) }
            persist(finalId)
        }
        _drafts.value = _drafts.value - targetSessionId
        _draftAttachments.value = _draftAttachments.value - targetSessionId
        val capturedHistory = history
        streamJobs[finalId] = scope.launch(Dispatchers.IO) {
            streamReply(finalId, reply, capturedHistory, question)
        }
        return true
    }

    private fun createReply(): Message.Assistant = Message.Assistant(
        id = createId(),
        createdAt = System.currentTimeMillis(),
        model = MODEL,
        thinking = _thinkingCurrent,
        search = _searchCurrent,
        prefix = true,
        status = ReplyStatus.PENDING,
        blocks = emptyList(),
    )

    private val _thinkingCurrent get() = settings.thinking.value
    private val _searchCurrent get() = settings.search.value

    fun stopReply(sessionId: String) {
        activeStreams[sessionId]?.close()
        streamJobs[sessionId]?.cancel()
    }

    /** 重新生成当前路径上最后一条回答。 */
    fun regenerateReply(sessionId: String, messageId: String? = null) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        ReadAloud.stop()
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = if (messageId == null) path.size - 1 else path.indexOfFirst { it.id == messageId }
        val question = path.getOrNull(index - 1) as? Message.User ?: return
        if (path.getOrNull(index)?.role() != "assistant") return
        val history = path.subList(0, index - 1)
        val reply = createReply()
        scope.launch {
            updateSession(sessionId) { appendBranch(it, question.id, listOf(reply)) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) { streamReply(sessionId, reply, history, question) }
    }

    private fun Message.role() = when (this) {
        is Message.User -> "user"
        is Message.Assistant -> "assistant"
    }

    /** ref canContinueReply：被停止或到输出上限的回复可续写。 */
    fun canContinueReply(reply: Message.Assistant): Boolean {
        if (!reply.prefix || reply.stopReason == "refusal" || reply.stopReason == "tool_use" ||
            reply.blocks.any { block ->
                when (block) {
                    is ReplyBlock.Text -> block.isTemplate
                    is ReplyBlock.Tool -> {
                        val t = (block.payload["type"] as? JsonPrimitive)?.content
                        t == "server_tool_use" || t == "web_search_tool_result" ||
                            t == "web_fetch_tool_result"
                    }
                    else -> false
                }
            }) {
            return false
        }
        return reply.status == ReplyStatus.STOPPED ||
            (reply.status == ReplyStatus.DONE && reply.stopReason == "max_tokens")
    }

    /** ref continueReply：继续最后一条未完成的回答，新流内容追加在原块之后。 */
    fun continueReply(sessionId: String, messageId: String) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val question = path.getOrNull(path.size - 2) as? Message.User ?: return
        val previous = path.lastOrNull() as? Message.Assistant ?: return
        if (previous.id != messageId || !canContinueReply(previous)) return
        val reply = previous.copy(status = ReplyStatus.PENDING, stopReason = null, error = null)
        updateReply(sessionId, reply)
        scope.launch {
            updateSession(sessionId) { it.copy(updatedAt = System.currentTimeMillis()) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) {
            streamReply(sessionId, reply, path.subList(0, path.size - 2), question, previous)
        }
    }

    /** 补传失败的回复重置为 pending 后重发（ref retryUpload：仅 error.kind=='upload'）。 */
    fun retryUpload(sessionId: String, messageId: String) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = path.indexOfFirst { it.id == messageId }
        val question = path.getOrNull(index - 1) as? Message.User ?: return
        val previous = path.getOrNull(index) as? Message.Assistant ?: return
        if (previous.error?.kind != "upload") return
        val reply = previous.copy(status = ReplyStatus.PENDING, error = null)
        updateReply(sessionId, reply)
        scope.launch { persist(sessionId) }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) {
            streamReply(sessionId, reply, path.subList(0, index - 1), question)
        }
    }

    /** 需要服务端版面解析的文档扩展名（ref LAYOUT_RECOGNITION_EXTS）。 */
    private val layoutExts = setOf("pdf", "doc", "docx", "ppt", "pptx")

    /** ref getUploadPurpose：图片不传 purpose，PDF/Office 文档走版面解析，其余 user_data。 */
    private fun uploadPurpose(name: String, isImage: Boolean): String? =
        if (isImage) null
        else if (layoutExts.contains(name.substringAfterLast('.', "").lowercase()))
            "rich_layout_recognition" else "user_data"

    private data class UploadedMeta(
        val serverId: String,
        val maxEdge: Int? = null,
        val converted: Boolean? = null,
    )

    /** 把补传得到的 serverId 与图片尺寸元数据写回所有引用同一附件的 user 消息（ref saveUploadedFiles）。 */
    private suspend fun saveUploadedFiles(sessionId: String, uploads: Map<String, UploadedMeta>) {
        if (uploads.isEmpty()) return
        updateSession(sessionId) { session ->
            session.copy(messages = session.messages.map { m ->
                if (m is Message.User && m.files.any { uploads.containsKey(it.id) }) {
                    m.copy(files = m.files.map { f ->
                        uploads[f.id]?.let { meta ->
                            f.copy(
                                serverId = meta.serverId,
                                serverImageMaxEdge = meta.maxEdge ?: f.serverImageMaxEdge,
                                serverImageConverted = meta.converted ?: f.serverImageConverted,
                            )
                        } ?: f
                    })
                } else m
            })
        }
        persist(sessionId)
    }

    /** 服务端判定 file_id 失效时，清除对应附件 serverId 以触发下一次重传。 */
    private suspend fun clearInvalidatedFiles(sessionId: String, targetServerIds: Set<String>): Boolean {
        if (targetServerIds.isEmpty()) return false
        var cleared = false
        updateSession(sessionId) { session ->
            session.copy(messages = session.messages.map { m ->
                if (m is Message.User && m.files.any { it.serverId != null && it.serverId in targetServerIds }) {
                    cleared = true
                    m.copy(files = m.files.map { f ->
                        if (f.serverId != null && f.serverId in targetServerIds) {
                            f.copy(serverId = null, serverImageMaxEdge = null, serverImageConverted = null)
                        } else f
                    })
                } else m
            })
        }
        if (cleared) persist(sessionId)
        return cleared
    }

    private suspend fun uploadAttachmentWithPrep(
        apiKey: String,
        file: FileAttachment,
        maxEdge: Int = MAX_IMAGE_EDGE,
        forceConversion: Boolean = false,
    ): UploadedMeta? {
        val path = file.localPath?.takeIf { it.isNotEmpty() } ?: return null
        val srcFile = File(path)
        if (!srcFile.exists()) return null
        if (!file.isImage) {
            val id = uploadFile(apiKey, srcFile, file.name, file.mimeType, uploadPurpose(file.name, false))
            return UploadedMeta(serverId = id)
        }
        var prepared = prepareImageForUpload(
            source = srcFile,
            cacheDir = context.cacheDir,
            originalName = file.name,
            originalMime = file.mimeType,
            maxEdge = maxEdge,
            forceConversion = forceConversion,
        )
        if (forceConversion && !prepared.converted) {
            prepared.tempFile?.delete()
            throw ApiError(ApiErrorKind.UPLOAD, Strings.get("uploadParseFailed"))
        }
        return try {
            val id = uploadFile(apiKey, prepared.file, prepared.uploadName, prepared.mimeType, null)
            UploadedMeta(
                serverId = id,
                maxEdge = prepared.maxEdge.takeIf { it > 0 },
                converted = prepared.converted,
            )
        } catch (e: ApiError) {
            if (!prepared.converted && prepared.maxEdge > 0 && isUploadParseFailed(e)) {
                prepared.tempFile?.delete()
                val fallback = prepareImageForUpload(
                    source = srcFile,
                    cacheDir = context.cacheDir,
                    originalName = file.name,
                    originalMime = file.mimeType,
                    maxEdge = maxEdge,
                    forceConversion = true,
                )
                if (!fallback.converted) {
                    fallback.tempFile?.delete()
                    throw e
                }
                prepared = fallback
                val id = uploadFile(apiKey, fallback.file, fallback.uploadName, fallback.mimeType, null)
                UploadedMeta(
                    serverId = id,
                    maxEdge = fallback.maxEdge.takeIf { it > 0 },
                    converted = true,
                )
            } else {
                throw e
            }
        } finally {
            prepared.tempFile?.delete()
        }
    }

    private fun refreshRequestSnapshot(
        sessionId: String,
        question: Message.User,
        history: List<Message>,
    ): Pair<Message.User, List<Message>> {
        val byId = _sessionCache.value[sessionId]?.messages?.associateBy { it.id } ?: return question to history
        val freshQ = (byId[question.id] as? Message.User) ?: question
        val freshHist = history.map { m -> byId[m.id] ?: m }
        return freshQ to freshHist
    }

    private suspend fun prepareRequestFiles(
        sessionId: String,
        question: Message.User,
        history: List<Message>,
        apiKey: String,
    ): Pair<Message.User, List<Message>> {
        var (curQ, curHist) = refreshRequestSnapshot(sessionId, question, history)

        // 1. 当前提问待传附件：任一失败整体按 upload 报错，已成功的仍保留以供重试复用。
        val qUploads = LinkedHashMap<String, UploadedMeta>()
        var qFailure: ApiError? = null
        for (file in curQ.files) {
            if (file.serverId == null && !file.localPath.isNullOrEmpty()) {
                try {
                    uploadAttachmentWithPrep(apiKey, file, MAX_IMAGE_EDGE, false)?.let { qUploads[file.id] = it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (qFailure == null) qFailure = ApiError(ApiErrorKind.UPLOAD, e.message ?: e.toString(), e.status, e.code)
                } catch (e: Exception) {
                    if (qFailure == null) qFailure = ApiError(ApiErrorKind.UPLOAD, e.message ?: e.toString())
                }
            }
        }
        if (qUploads.isNotEmpty()) {
            saveUploadedFiles(sessionId, qUploads)
        }
        qFailure?.let { throw it }

        val refreshed1 = refreshRequestSnapshot(sessionId, curQ, curHist)
        curQ = refreshed1.first; curHist = refreshed1.second

        // 2. 历史轮次中 serverId == null 的本地附件（如刚导入的 ZIP 会话）：从最新轮次往前补传，失败静默跳过。
        val historyRounds = buildList {
            var i = 0
            while (i + 1 < curHist.size) {
                val u = curHist[i]
                val a = curHist[i + 1]
                if (u is Message.User && isUsableReply(a)) {
                    add(u)
                    i++
                }
                i++
            }
        }
        val seenIds = qUploads.keys.toMutableSet()
        for (histUser in historyRounds.asReversed()) {
            for (file in histUser.files) {
                if (file.serverId != null || file.localPath.isNullOrEmpty() || !seenIds.add(file.id)) continue
                currentCoroutineContext().ensureActive()
                try {
                    uploadAttachmentWithPrep(apiKey, file, MAX_IMAGE_EDGE, false)?.let { meta ->
                        saveUploadedFiles(sessionId, mapOf(file.id to meta))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }

        val refreshed2 = refreshRequestSnapshot(sessionId, curQ, curHist)
        curQ = refreshed2.first; curHist = refreshed2.second

        // 3. 当次请求图片总数 >= 15 张时，API 要求单边不超过 4096（含历史图）。
        val allImages = collectContextImages(curQ, curHist)
        if (allImages.size >= MANY_IMAGES_COUNT) {
            val oversized = LinkedHashMap<String, FileAttachment>()
            for (img in allImages) {
                if (!img.localPath.isNullOrEmpty() && File(img.localPath).exists() &&
                    (img.serverImageConverted != true || (img.serverImageMaxEdge ?: Int.MAX_VALUE) > MANY_IMAGES_MAX_EDGE)
                ) {
                    if (!oversized.containsKey(img.id)) oversized[img.id] = img
                }
            }
            val shrunk = LinkedHashMap<String, UploadedMeta>()
            var shrinkErr: ApiError? = null
            for ((_, img) in oversized) {
                try {
                    uploadAttachmentWithPrep(apiKey, img, MANY_IMAGES_MAX_EDGE, true)?.let { shrunk[img.id] = it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (shrinkErr == null) shrinkErr = ApiError(ApiErrorKind.UPLOAD, e.message ?: e.toString(), e.status, e.code)
                } catch (e: Exception) {
                    if (shrinkErr == null) shrinkErr = ApiError(ApiErrorKind.UPLOAD, e.message ?: e.toString())
                }
            }
            if (shrunk.isNotEmpty()) saveUploadedFiles(sessionId, shrunk)
            shrinkErr?.let { throw it }
        }

        return refreshRequestSnapshot(sessionId, curQ, curHist)
    }

    private fun collectContextImages(question: Message.User, history: List<Message>): List<FileAttachment> {
        val out = ArrayList<FileAttachment>()
        out.addAll(question.files.filter { it.isImage && !it.serverId.isNullOrEmpty() })
        var i = 0
        while (i + 1 < history.size) {
            val u = history[i]
            val a = history[i + 1]
            if (u is Message.User && isUsableReply(a)) {
                out.addAll(u.files.filter { it.isImage && !it.serverId.isNullOrEmpty() })
                i++
            }
            i++
        }
        return out
    }

    /** 编辑一条 user 消息并重新生成其回答（产生新版本分支）。 */
    fun editMessage(
        sessionId: String,
        messageId: String,
        newContent: String,
        nextFiles: List<FileAttachment>? = null,
    ) {
        if (_importingHistory.value || streamJobs.containsKey(sessionId)) return
        val session = _sessionCache.value[sessionId] ?: return
        val path = visibleMessages(session)
        val index = path.indexOfFirst { it.id == messageId }
        val original = path.getOrNull(index) as? Message.User ?: return
        // files = nextFiles ?? previous.files；未改动沿用原附件，仅当文本与附件皆空时拒绝。
        // 纯图片/文件消息因此可以正常编辑并保留附件。
        val files = nextFiles ?: original.files
        if (newContent.isBlank() && files.isEmpty()) return
        val history = path.subList(0, index)
        val question = original.copy(
            id = createId(),
            content = newContent.trim(),
            files = files,
            createdAt = System.currentTimeMillis(),
        )
        val reply = createReply()
        scope.launch {
            updateSession(sessionId) { appendBranch(it, original.parentId, listOf(question, reply)) }
            persist(sessionId)
        }
        streamJobs[sessionId] = scope.launch(Dispatchers.IO) { streamReply(sessionId, reply, history, question) }
    }

    // ---------- 流式回复 ----------

    private fun updateReply(sessionId: String, reply: Message.Assistant) {
        scope.launch(Dispatchers.Default) {
            updateSession(sessionId) { session ->
                session.copy(messages = session.messages.map { message ->
                    if (message.id == reply.id) {
                        reply.copy(parentId = message.parentId, selectedChildId = message.selectedChildId)
                    } else message
                })
            }
        }
    }

    private suspend fun updateReplySync(sessionId: String, reply: Message.Assistant) {
        updateSession(sessionId) { session ->
            session.copy(messages = session.messages.map { message ->
                if (message.id == reply.id) {
                    reply.copy(parentId = message.parentId, selectedChildId = message.selectedChildId)
                } else message
            })
        }
    }

    private suspend fun streamReply(
        sessionId: String,
        initial: Message.Assistant,
        history: List<Message>,
        question: Message.User,
        prefix: Message.Assistant? = null,
    ) {
        var reply = initial
        if (_sessionCache.value[sessionId]?.title.isNullOrEmpty()) {
            titleSessions.add(sessionId)
        }
        _streaming.value = _streaming.value + sessionId
        val flushMutex = Mutex()
        var lastFlush = 0L
        try {
            val reminder = prefix?.latestReminder ?: latestReminderText(Strings.isZh())
            reply = reply.copy(latestReminder = reminder)
            updateReplySync(sessionId, reply)
            persist(sessionId)

            val key = settings.apiKey.value ?: ""
            val activeModel = initial.model ?: MODEL
            val prefixThinking = prefix?.thinking ?: settings.thinking.value
            val fallbackOutputCap = if (initial.thinking) {
                FALLBACK_THINKING_OUTPUT_TOKENS
            } else {
                FALLBACK_NON_THINKING_OUTPUT_TOKENS
            }
            val budget = contextBudget(MAX_OUTPUT_TOKENS)
            var historyBudget = kotlin.math.max(
                0,
                budget.input - estimateUserTokens(question) - estimatePrefixTokens(prefix),
            )
            var historyRetries = 0
            var historySummaryRequests = 0
            var historyContext: HistoryContextResult? = null
            var maxOutputCap = MAX_OUTPUT_TOKENS
            var reduceInput = false
            var reducedInput: ReducedInputResult? = null
            var fileInvalidRetries = 0
            var imageRepairRetries = 0
            var transientRetries = 0

            val summarizeFn: suspend (SummaryRequestInput) -> String? = { req ->
                requestContextSummary(key, activeModel, req)
            }

            while (true) {
                var preparing = false
                var receivedContent = false
                var lastBuilt: BuiltMessageRequest? = null
                var preparedQuestion = question
                var preparedHistory = history
                try {
                    val activeCtx = historyContext ?: prepareHistoryContext(
                        history = history,
                        checkpoints = _sessionCache.value[sessionId]?.contextCheckpoints.orEmpty(),
                        budget = historyBudget,
                        recentBudget = budget.recent,
                        summarize = { input ->
                            if (historySummaryRequests++ < HISTORY_SUMMARY_LIMIT) {
                                summarizeFn(input)
                            } else null
                        },
                        saveCheckpoint = { cp ->
                            updateSession(sessionId) { session ->
                                session.copy(
                                    contextCheckpoints = saveContextCheckpoint(session.contextCheckpoints, cp),
                                )
                            }
                            persist(sessionId)
                        },
                    ).also { historyContext = it }

                    preparing = true
                    val preparedPair = prepareRequestFiles(sessionId, question, activeCtx.history, key)
                    preparedQuestion = preparedPair.first
                    preparedHistory = preparedPair.second
                    preparing = false

                    if (reduceInput && reducedInput == null) {
                        val reduced = summarizeCurrentInput(
                            context = context,
                            apiKey = key,
                            question = preparedQuestion,
                            prefix = prefix,
                            summarize = summarizeFn,
                        ) ?: throw ApiError(
                            ApiErrorKind.PROTOCOL,
                            Strings.get("replyInputTooLong"),
                            code = "reply_input_too_long",
                        )
                        reducedInput = reduced
                        historyBudget = kotlin.math.max(
                            0,
                            kotlin.math.min(
                                budget.recent,
                                contextBudget(maxOutputCap).input -
                                    estimateUserTokens(reduced.question) -
                                    estimatePrefixTokens(reduced.prefix),
                            ),
                        )
                        historyContext = null
                        continue
                    }

                    val sendQuestion = reducedInput?.question ?: preparedQuestion
                    val sendPrefix = if (reducedInput != null) reducedInput!!.prefix else prefix
                    val built = buildCreateMessageRequest(
                        model = activeModel,
                        systemPrompt = SYSTEM_PROMPT,
                        history = preparedHistory,
                        question = sendQuestion,
                        latestReminder = reminder,
                        thinking = initial.thinking,
                        search = initial.search,
                        requestTitle = titleSessions.contains(sessionId),
                        prefix = sendPrefix,
                        prefixThinking = prefixThinking,
                        maxOutputTokensCap = maxOutputCap,
                        includeExtensions = true,
                        contextSummary = activeCtx.summary,
                    )
                    lastBuilt = built
                    val stream = try {
                        openMessageStreamRaw(key, built.bodyJson, built.hasFiles)
                    } catch (e: ApiError) {
                        // ref streamMessage：扩展模式在生成前拒绝长输入时，退回基础 Messages API。
                        if (isExtendedInputTooLong(e)) {
                            val basic = buildCreateMessageRequest(
                                model = activeModel,
                                systemPrompt = SYSTEM_PROMPT,
                                history = preparedHistory,
                                question = sendQuestion,
                                latestReminder = reminder,
                                thinking = initial.thinking,
                                search = initial.search,
                                requestTitle = titleSessions.contains(sessionId),
                                prefix = sendPrefix,
                                prefixThinking = prefixThinking,
                                maxOutputTokensCap = maxOutputCap,
                                includeExtensions = false,
                                contextSummary = activeCtx.summary,
                            )
                            lastBuilt = basic
                            openMessageStreamRaw(key, basic.bodyJson, basic.hasFiles)
                        } else throw e
                    }
                    activeStreams[sessionId] = stream
                    try {
                        val blockIndexes = mutableMapOf<Int, Int>()
                        while (true) {
                            val event = stream.nextEvent() ?: throw ApiError(ApiErrorKind.INTERRUPTED, "")
                            val (eventName, data) = event
                            var payload: JSONObject? = null
                            try {
                                payload = JSONObject(data)
                            } catch (_: Exception) {
                                if (eventName == "error") {
                                    throw ApiError(ApiErrorKind.STREAM, data)
                                }
                            }
                            val error = payload?.optJSONObject("error")
                            if (error != null) {
                                val errCode = error.optString("code").takeIf { it.isNotEmpty() }
                                    ?: error.optString("type").takeIf { it.isNotEmpty() }
                                throw ApiError(ApiErrorKind.STREAM, error.optString("message"), code = errCode)
                            }
                            if (eventName == "error") {
                                throw ApiError(ApiErrorKind.STREAM, data)
                            }
                            if (payload == null) continue
                            val type = payload.optString("type")
                            if (type.isEmpty()) continue
                            if (type == "content_block_start") receivedContent = true
                            reply = applyEvent(reply, type, payload, blockIndexes)
                            // 标题生成
                            if (type == "message_delta") {
                                val delta = payload.optJSONObject("delta")
                                val stopReason = delta?.optString("stop_reason")
                                val title = delta?.optString("title")?.trim()
                                if ((stopReason == "end_turn" || stopReason == "max_tokens") &&
                                    !title.isNullOrEmpty() && titleSessions.contains(sessionId)
                                ) {
                                    updateSession(sessionId) { s ->
                                        if (s.title.isEmpty()) s.copy(title = title) else s
                                    }
                                    titleSessions.remove(sessionId)
                                }
                            }
                            if (type == "message_stop") {
                                reply = settleReply(reply, ReplyStatus.DONE)
                                break
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastFlush > 100) {
                                lastFlush = now
                                flushMutex.withLock { updateReply(sessionId, reply) }
                            }
                        }
                    } finally {
                        stream.close()
                        activeStreams.remove(sessionId)
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (preparing || receivedContent) throw e

                    if (isContextOverflow(e)) {
                        val ctxTokens = historyContext?.tokens ?: 0
                        if (ctxTokens > 0 && historyRetries < HISTORY_OVERFLOW_RETRIES) {
                            historyBudget = if (++historyRetries < HISTORY_OVERFLOW_RETRIES) ctxTokens / 2 else 0
                            historyContext = null
                            continue
                        }
                        if (maxOutputCap > fallbackOutputCap) {
                            maxOutputCap = fallbackOutputCap
                            continue
                        }
                        if (!reduceInput) {
                            reduceInput = true
                            continue
                        }
                        throw ApiError(
                            ApiErrorKind.PROTOCOL,
                            Strings.get("replyInputTooLong"),
                            e.status,
                            code = "reply_input_too_long",
                        )
                    }
                    if (getFileErrorCode(e) != null && fileInvalidRetries < FILE_RETRY_LIMIT) {
                        val mentioned = extractFileIds(e.message)
                        val targets = if (mentioned.isNotEmpty()) mentioned else buildSet {
                            preparedQuestion.files.mapNotNullTo(this) { it.serverId }
                            var i = 0
                            while (i + 1 < preparedHistory.size) {
                                val u = preparedHistory[i]
                                val a = preparedHistory[i + 1]
                                if (u is Message.User && isUsableReply(a)) {
                                    u.files.mapNotNullTo(this) { it.serverId }
                                    i++
                                }
                                i++
                            }
                        }
                        if (clearInvalidatedFiles(sessionId, targets)) {
                            delay(backoffDelayMs(fileInvalidRetries++))
                            continue
                        }
                    }
                    val rejectedPos = getRejectedImagePosition(e)
                    if (rejectedPos != null && imageRepairRetries < IMAGE_REPAIR_RETRY_LIMIT && lastBuilt != null) {
                        val rejectedFileId = getRejectedFileIdAtPosition(
                            lastBuilt.messages, rejectedPos.first, rejectedPos.second,
                        )
                        val images = collectContextImages(preparedQuestion, preparedHistory)
                        val targetImg = rejectedFileId?.let { fid -> images.firstOrNull { it.serverId == fid } }
                        val edgeCap = if (images.size >= MANY_IMAGES_COUNT) MANY_IMAGES_MAX_EDGE else MAX_IMAGE_EDGE
                        if (targetImg != null &&
                            !(targetImg.serverImageConverted == true && (targetImg.serverImageMaxEdge ?: Int.MAX_VALUE) <= edgeCap)
                        ) {
                            val repairedMeta = try {
                                uploadAttachmentWithPrep(key, targetImg, maxEdge = edgeCap, forceConversion = true)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                null
                            }
                            if (repairedMeta != null) {
                                saveUploadedFiles(sessionId, mapOf(targetImg.id to repairedMeta))
                                imageRepairRetries++
                                continue
                            }
                        }
                    }
                    if (isTransientRetryableError(e) && transientRetries < TRANSIENT_RETRY_LIMIT) {
                        delay(backoffDelayMs(transientRetries++))
                        continue
                    }
                    throw e
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            reply = settleReply(reply, ReplyStatus.STOPPED)
            throw e
        } catch (e: ApiError) {
            reply = when {
                e.kind == ApiErrorKind.INTERRUPTED && reply.stopReason != null ->
                    settleReply(reply, ReplyStatus.DONE)
                // ref: a dropped stream with partial content settles 'stopped'
                // silently (no error notice), keeping the action row.
                e.kind == ApiErrorKind.INTERRUPTED && reply.blocks.isNotEmpty() ->
                    settleReply(reply, ReplyStatus.STOPPED)
                e.code == "reply_input_too_long" ->
                    settleReply(reply, ReplyStatus.ERROR, ReplyError("too_long", e.message ?: "", e.status))
                else ->
                    settleReply(reply, ReplyStatus.ERROR, ReplyError(e.kind.name.lowercase(), e.message ?: "", e.status))
            }
        } catch (e: Exception) {
            // 用户按停止取消 socket：抛出的 SocketException 应记为 stopped 而非错误
            if (!currentCoroutineContext().isActive) {
                reply = settleReply(reply, ReplyStatus.STOPPED)
                throw kotlinx.coroutines.CancellationException("stopped").initCause(e)
            }
            reply = settleReply(reply, ReplyStatus.ERROR, ReplyError("protocol", e.message ?: e.toString()))
        } finally {
            titleSessions.remove(sessionId)
            withContext(kotlinx.coroutines.NonCancellable) {
                updateReplySync(sessionId, reply)
            }
            _streaming.value = _streaming.value - sessionId
            streamJobs.remove(sessionId)
            persist(sessionId)
        }
    }

    private fun applyEvent(
        reply: Message.Assistant,
        type: String,
        payload: JSONObject,
        blockIndexes: MutableMap<Int, Int>,
    ): Message.Assistant {
        val next = if (reply.status == ReplyStatus.STREAMING) reply else reply.copy(status = ReplyStatus.STREAMING)
        val now = System.currentTimeMillis()
        return when (type) {
            "message_start" -> next
            "content_block_start" -> {
                val index = payload.optInt("index")
                blockIndexes.remove(index)
                val block = payload.optJSONObject("content_block") ?: return next
                val converted = toReplyBlock(block, now)
                if (converted == null) {
                    return if (block.optString("type") == "tool_use") next.copy(prefix = false) else next
                }
                blockIndexes[index] = next.blocks.size
                next.copy(blocks = next.blocks + converted)
            }
            "content_block_delta" -> {
                val index = blockIndexes[payload.optInt("index")] ?: return next
                val delta = payload.optJSONObject("delta") ?: return next
                next.copy(blocks = next.blocks.mapIndexed { i, block ->
                    if (i != index) block else when (block) {
                        is ReplyBlock.Text -> when (delta.optString("type")) {
                            "text_delta" -> block.copy(text = block.text + delta.optString("text"))
                            "citations_delta" -> delta.optJSONObject("citation")?.let {
                                block.copy(citations = block.citations + Citation(
                                    url = it.optString("url"),
                                    title = if (it.has("title") && !it.isNull("title")) it.getString("title") else null,
                                    cited_text = it.optString("cited_text"),
                                ))
                            } ?: block
                            else -> block
                        }
                        is ReplyBlock.Thinking -> when (delta.optString("type")) {
                            "thinking_delta" -> block.copy(thinking = block.thinking + delta.optString("thinking"))
                            "signature_delta" -> block.copy(signature = (block.signature ?: "") + delta.optString("signature"))
                            else -> block
                        }
                        else -> block
                    }
                })
            }
            "content_block_stop" -> {
                val index = blockIndexes[payload.optInt("index")] ?: return next
                blockIndexes.remove(payload.optInt("index"))
                next.copy(blocks = next.blocks.mapIndexed { i, block ->
                    if (i == index && block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                        block.copy(endedAt = now)
                    } else block
                })
            }
            "message_delta" -> {
                val delta = payload.optJSONObject("delta")
                next.copy(stopReason = delta?.optString("stop_reason") ?: next.stopReason)
            }
            else -> next
        }
    }

    private fun toReplyBlock(block: JSONObject, now: Long): ReplyBlock? = when (block.optString("type")) {
        "text" -> ReplyBlock.Text(block.optString("text"))
        "thinking" -> ReplyBlock.Thinking(
            thinking = block.optString("thinking"),
            signature = block.optString("signature").takeIf { block.has("signature") },
            startedAt = now,
        )
        "server_tool_use", "web_search_tool_result", "web_fetch_tool_result" ->
                ReplyBlock.Tool(json.parseToJsonElement(block.toString()).jsonObject)
        else -> null
    }

    private fun settleReply(
        reply: Message.Assistant,
        status: ReplyStatus,
        error: ReplyError? = null,
    ): Message.Assistant {
        val now = System.currentTimeMillis()
        return reply.copy(
            status = status,
            error = error,
            blocks = reply.blocks.flatMap { block ->
                if (reply.stopReason == "refusal" && (block is ReplyBlock.Thinking || block is ReplyBlock.Text)) {
                    emptyList()
                } else if (block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                    listOf(block.copy(endedAt = now))
                } else listOf(block)
            },
        )
    }

    // ---------- 历史导出/导入（官方 App zip 格式 v3） ----------

    /** 导出全部会话（含全分支树、工具结果与本地图片原件）为官方 v3 zip。失败返回 false。 */
    fun exportArchive(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val sessions = _sessionCache.value.values.toList()
            val pinned = settings.pinnedSessions.value.toSet()
            val out = context.contentResolver.openOutputStream(uri) ?: return false
            out.use { stream -> writeSessionsArchive(sessions, pinned, stream) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 ZipFile（支持 STORED + Data Descriptor）从官方导出的 v3 zip 导入历史会话，
     * 恢复完整分支树、工具步骤与 `_files/` 原图，并支持 MERGE 深度树合并 / REPLACE 替换两种模式。
     */
    suspend fun importArchive(
        context: Context,
        uri: android.net.Uri,
        mode: ArchiveImportMode = ArchiveImportMode.MERGE,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        var tmpZip: File? = null
        var bundle: ParsedArchiveBundle? = null
        _importingHistory.value = true
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportOutcome.Failure("backupUnrecognized")
            tmpZip = input.use { copyStreamToTempZip(it, context.cacheDir) }
            val parsed = readArchiveZipFile(tmpZip, attachesDir)
            bundle = parsed

            if (mode == ArchiveImportMode.REPLACE) {
                streamJobs.values.forEach { it.cancel() }
                streamJobs.clear()
                titleSessions.clear()
                val newMap = parsed.sessions.associateBy { it.id }
                val keepFiles = parsed.extractedFiles.values.map { it.first }.toHashSet()
                mutex.withLock {
                    _sessionCache.value = newMap
                    _sessions.value = sortSessions(newMap.values.map { it.toSummary() })
                    _activeId.value = null
                    _drafts.value = emptyMap()
                    _draftAttachments.value = emptyMap()
                }
                chatDir.listFiles()?.forEach { it.delete() }
                attachesDir.listFiles()?.forEach { f ->
                    if (f.absolutePath !in keepFiles) f.delete()
                }
                if (legacyCacheAttachesDir.exists()) {
                    legacyCacheAttachesDir.listFiles()?.forEach { it.delete() }
                }
                for (s in newMap.values) {
                    writeSessionSync(s)
                }
                settings.setPinnedSessions(parsed.pinnedIds.filter { it in newMap })
                return@withContext ImportOutcome.Replace(newMap.size)
            }

            // MERGE: 深度树合并
            var added = 0
            var merged = 0
            var unchanged = 0
            val persistedIds = ArrayList<String>()
            val newPinned = LinkedHashSet(settings.pinnedSessions.value)
            val resultMap = LinkedHashMap<String, Session>()
            mutex.withLock {
                resultMap.putAll(_sessionCache.value)
                for (incoming in parsed.sessions) {
                    val existing = resultMap[incoming.id]
                    if (existing == null) {
                        val normalized = incoming.normalized()
                        resultMap[incoming.id] = normalized
                        persistedIds.add(incoming.id)
                        if (incoming.id in parsed.pinnedIds) newPinned.add(incoming.id)
                        added++
                    } else {
                        val res = mergeArchiveSession(existing, incoming)
                        if (res.changed) {
                            resultMap[incoming.id] = res.session
                            persistedIds.add(incoming.id)
                            if (incoming.id in parsed.pinnedIds) newPinned.add(incoming.id)
                            merged++
                        } else {
                            if (incoming.id in parsed.pinnedIds && newPinned.add(incoming.id)) {
                                merged++
                            } else {
                                unchanged++
                            }
                        }
                    }
                }
                _sessionCache.value = resultMap
                _sessions.value = sortSessions(resultMap.values.map { it.toSummary() })
                val validIds = resultMap.keys
                settings.setPinnedSessions(newPinned.filter { it in validIds })
            }
            for (sid in persistedIds) {
                val s = resultMap[sid] ?: continue
                writeSessionSync(s)
            }
            // 删除合并后未被引用的新解包图片文件
            val livePaths = buildSet {
                resultMap.values.forEach { s ->
                    s.messages.forEach { m ->
                        if (m is Message.User) m.files.mapNotNullTo(this) { it.localPath }
                    }
                }
                _draftAttachments.value.values.forEach { list -> list.forEach { add(it.localPath) } }
            }
            parsed.extractedFiles.values.forEach { (p, _) ->
                if (p !in livePaths && isManagedAttachPath(p)) {
                    try { File(p).delete() } catch (_: Exception) {}
                }
            }
            ImportOutcome.Merge(added = added, merged = merged, unchanged = unchanged)
        } catch (e: ArchiveBackupException) {
            bundle?.extractedFiles?.values?.forEach { (p, _) ->
                if (isManagedAttachPath(p)) try { File(p).delete() } catch (_: Exception) {}
            }
            ImportOutcome.Failure(e.errorKey)
        } catch (_: Exception) {
            bundle?.extractedFiles?.values?.forEach { (p, _) ->
                if (isManagedAttachPath(p)) try { File(p).delete() } catch (_: Exception) {}
            }
            ImportOutcome.Failure("historyImportFailed")
        } finally {
            tmpZip?.delete()
            _importingHistory.value = false
        }
    }
}
