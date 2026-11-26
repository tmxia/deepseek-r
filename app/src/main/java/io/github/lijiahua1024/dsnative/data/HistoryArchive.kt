package io.github.lijiahua1024.dsnative.data

import io.github.lijiahua1024.dsnative.theme.Strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class ArchiveImportMode { MERGE, REPLACE }

sealed class ImportOutcome {
    data class Replace(val count: Int) : ImportOutcome()
    data class Merge(val added: Int, val merged: Int, val unchanged: Int) : ImportOutcome()
    data class Failure(val errorKey: String) : ImportOutcome()
}

class ArchiveBackupException(val errorKey: String) : Exception(errorKey)

private const val EXPORT_FORMAT_VERSION = 3
private const val MANIFEST_PATH = "manifest.json"
private const val USER_PATH = "user.json"
private const val INDEX_PATH = "chat_session_index.json"

private val archiveJson = Json { ignoreUnknownKeys = true }
private val citationRegex = Regex("""\[citation:\d+]""")
private val sessionPathRegex = Regex("""^chat_sessions/([^/]+)\.json$""")
private val sessionFileEntryRegex = Regex("""^chat_sessions/([^/]+)_files/""")

private fun failArchive(key: String): Nothing = throw ArchiveBackupException(key)

private fun utcIsoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun formatArchiveIso(millis: Long): String = utcIsoFormat().format(Date(millis))

private fun parseArchiveIso(raw: String): Long? {
    val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$""").matchEntire(raw)
        ?: return null
    val frac = (m.groupValues[2]).padEnd(3, '0').take(3)
    val norm = "${m.groupValues[1]}.$frac" + "Z"
    return try {
        val fmt = utcIsoFormat().apply { isLenient = false }
        val d = fmt.parse(norm) ?: return null
        if (fmt.format(d) == norm) d.time else null
    } catch (_: Exception) {
        null
    }
}

private fun parseOptionalArchiveIso(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    parseArchiveIso(raw)?.let { return formatArchiveIso(it) }
    val formats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
    for (pattern in formats) {
        try {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = fmt.parse(raw.trim()) ?: continue
            return formatArchiveIso(parsed.time)
        } catch (_: Exception) {
        }
    }
    return null
}

private fun isHttpUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val scheme = URI(url).scheme?.lowercase()
        scheme == "http" || scheme == "https"
    } catch (_: Exception) {
        false
    }
}

private fun safePathSegment(name: String): String {
    val result = name.replace('/', '_').replace('\\', '_')
    return if (result.isEmpty() || result == "." || result == "..") "unnamed" else result
}

private fun sessionJsonPath(sessionId: String) = "chat_sessions/$sessionId.json"

private fun sessionFilesPrefix(sessionId: String) = "chat_sessions/${sessionId}_files/"

internal fun imageArchivePath(sessionId: String, fileId: String, fileName: String): String =
    Normalizer.normalize(
        "${sessionFilesPrefix(sessionId)}${safePathSegment(fileId)}_${safePathSegment(fileName)}",
        Normalizer.Form.NFC,
    )

internal fun detectImageMimeType(bytes: ByteArray): String {
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    ) return "image/jpeg"
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    ) return "image/png"
    val ascii12 = String(bytes, 0, minOf(12, bytes.size), Charsets.US_ASCII)
    if (ascii12.startsWith("GIF87a") || ascii12.startsWith("GIF89a")) return "image/gif"
    if (ascii12.length >= 12 && ascii12.startsWith("RIFF") && ascii12.substring(8, 12) == "WEBP") {
        return "image/webp"
    }
    if (bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp"
    if (bytes.size >= 4 && (
            (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()) ||
            (bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
        )
    ) return "image/tiff"
    if (ascii12.length >= 12 && ascii12.substring(4, 8) == "ftyp" &&
        (ascii12.substring(8, 12) == "avif" || ascii12.substring(8, 12) == "avis")
    ) return "image/avif"
    return ""
}

private fun JsonObject.reqObj(k: String): JsonObject =
    (get(k) as? JsonObject) ?: failArchive("backupIncomplete")

private fun JsonObject.reqArr(k: String): JsonArray =
    (get(k) as? JsonArray) ?: failArchive("backupIncomplete")

private fun JsonObject.reqStr(k: String): String {
    val p = get(k) as? JsonPrimitive ?: failArchive("backupIncomplete")
    if (!p.isString) failArchive("backupIncomplete")
    return p.content
}

private fun JsonObject.reqNullableStr(k: String): String? {
    val el = get(k) ?: failArchive("backupIncomplete")
    if (el is JsonNull) return null
    val p = el as? JsonPrimitive ?: failArchive("backupIncomplete")
    if (!p.isString) failArchive("backupIncomplete")
    return p.content
}

private fun JsonObject.optStrOrNull(k: String): String? {
    val el = get(k) ?: return null
    if (el is JsonNull) return null
    val p = el as? JsonPrimitive ?: failArchive("backupIncomplete")
    if (!p.isString) failArchive("backupIncomplete")
    return p.content
}

private fun JsonObject.reqBool(k: String): Boolean =
    (get(k) as? JsonPrimitive)?.booleanOrNull ?: failArchive("backupIncomplete")

private fun JsonObject.reqInt(k: String): Int {
    val l = (get(k) as? JsonPrimitive)?.longOrNull ?: failArchive("backupIncomplete")
    if (l < 0L || l > Int.MAX_VALUE) failArchive("backupIncomplete")
    return l.toInt()
}

private fun JsonObject.reqNullableInt(k: String): Int? {
    val el = get(k) ?: failArchive("backupIncomplete")
    if (el is JsonNull) return null
    return reqInt(k)
}

private fun JsonObject.reqNumber(k: String): Double =
    (get(k) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: failArchive("backupIncomplete")

private fun JsonObject.reqSessionId(k: String = "id"): String {
    val id = reqStr(k)
    if (id.isEmpty() || '/' in id || '\\' in id || id == "." || id == "..") failArchive("backupIncomplete")
    return id
}

private fun JsonObject.strOrNull(key: String): String? {
    val p = get(key) as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p.contentOrNull
}

// ---------- Export (v3 ZIP) ----------

private fun numberMessages(messages: List<Message>): Pair<List<Message>, Map<String, Int>> {
    val byId = messages.associateBy { it.id }
    val ids = HashMap<String, Int>(messages.size)
    val ordered = ArrayList<Message>(messages.size)
    val visiting = HashSet<String>()
    fun visit(msg: Message) {
        if (msg.id in ids) return
        if (!visiting.add(msg.id)) failArchive("backupInvalidBranches")
        val pId = msg.parentId
        if (pId != null) {
            val parent = byId[pId] ?: failArchive("backupInvalidBranches")
            visit(parent)
        }
        visiting.remove(msg.id)
        ordered.add(msg)
        ids[msg.id] = ordered.size
    }
    messages.forEach(::visit)
    return ordered to ids
}

private fun archiveReplyStatus(reply: Message.Assistant): String {
    if (reply.stopReason == "refusal") return "CONTENT_FILTER"
    return when (reply.status) {
        ReplyStatus.DONE -> if (reply.stopReason == "max_tokens") "INCOMPLETE" else "FINISHED"
        ReplyStatus.STOPPED -> "INCOMPLETE"
        ReplyStatus.ERROR -> "FAILED"
        ReplyStatus.PENDING, ReplyStatus.STREAMING -> "WIP"
    }
}

private fun toolToFragments(
    toolPayload: JsonObject,
    resultPayload: JsonObject?,
): List<JsonObject> {
    val input = toolPayload["input"] as? JsonObject ?: return emptyList()
    val action = input.strOrNull("action") ?: ""
    val kind = toolPayload.strOrNull("kind") ?: action
    if (action == "search" || action == "deep_search" || action == "image_search" ||
        action == "deep_image_search" || (action.isEmpty() && kind != "read_link")
    ) {
        val queries = (input["queries"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull
        } ?: listOfNotNull(input.strOrNull("query"))
        val rType = resultPayload?.strOrNull("type")
        val items = if (rType == "web_search_tool_result") {
            resultPayload["content"] as? JsonArray ?: JsonArray(emptyList())
        } else JsonArray(emptyList())
        val status = when {
            rType != "web_search_tool_result" -> "WIP"
            items.any { (it as? JsonObject)?.strOrNull("type") == "web_search_tool_result_error" } -> "FAILED"
            else -> "FINISHED"
        }
        val frag = buildJsonObject {
            put("type", "TOOL_SEARCH")
            put("queries", buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } })
            put("status", status)
            put("results", buildJsonArray {
                for (el in items) {
                    val item = el as? JsonObject ?: continue
                    if (item.strOrNull("type") != "web_search_result") continue
                    add(buildJsonObject {
                        put("url", item.strOrNull("url") ?: "")
                        put("title", item.strOrNull("title") ?: "")
                        put("snippet", item.strOrNull("snippet") ?: "")
                        val pub = parseOptionalArchiveIso(item.strOrNull("page_age"))
                        if (pub != null) put("published_at", pub) else put("published_at", JsonNull)
                        val sn = item.strOrNull("site_name")
                        if (sn != null) put("site_name", sn) else put("site_name", JsonNull)
                        val icon = item.strOrNull("site_icon")
                        if (icon != null) put("site_icon", icon) else put("site_icon", JsonNull)
                    })
                }
            })
        }
        return listOf(frag)
    }
    if (action == "read_link") {
        val rType = resultPayload?.strOrNull("type")
        val rKind = resultPayload?.strOrNull("kind")
        val content = resultPayload?.get("content") as? JsonObject
        val sig = resultPayload?.strOrNull("signature")?.trim()
        if (rType == "web_fetch_tool_result" && rKind == "read_link" &&
            content?.strOrNull("type") == "web_fetch_result" && !sig.isNullOrEmpty()
        ) {
            val url = content.strOrNull("url")?.ifEmpty { null } ?: input.strOrNull("url")
            val title = (content["content"] as? JsonObject)?.strOrNull("title")
            return listOf(buildJsonObject {
                put("type", "READ_LINK")
                put("results", buildJsonArray {
                    add(buildJsonObject {
                        if (!url.isNullOrEmpty()) put("url", url)
                        if (title != null) put("title", title) else put("title", JsonNull)
                        put("signature", sig)
                    })
                })
            })
        }
        return emptyList()
    }
    val content = if (resultPayload?.strOrNull("type") == "web_fetch_tool_result") {
        resultPayload["content"] as? JsonObject
    } else null
    val page = if (content?.strOrNull("type") == "web_fetch_result") content else null
    val status = when {
        content == null -> "WIP"
        page != null -> "FINISHED"
        else -> "FAILED"
    }
    if (action == "find_in_page" || action == "find_pattern" || action == "find") {
        val pattern = input.strOrNull("pattern") ?: input.strOrNull("query") ?: ""
        return listOf(buildJsonObject {
            put("type", "TOOL_FIND")
            put("status", status)
            put("pattern", pattern)
        })
    }
    val url = input.strOrNull("url")?.ifEmpty { null } ?: page?.strOrNull("url")
    val pageTitle = (page?.get("content") as? JsonObject)?.strOrNull("title")
    val pub = parseOptionalArchiveIso(page?.strOrNull("published_at"))
    return listOf(buildJsonObject {
        put("type", "TOOL_OPEN")
        put("status", status)
        if (url != null) put("url", url) else put("url", JsonNull)
        if (pageTitle != null) put("title", pageTitle) else put("title", JsonNull)
        if (page != null) put("snippet", "") else put("snippet", JsonNull)
        put("site_name", JsonNull)
        if (pub != null) put("published_at", pub) else put("published_at", JsonNull)
    })
}

private fun assistantToFragments(message: Message.Assistant): JsonArray {
    val results = HashMap<String, JsonObject>()
    for (block in message.blocks) {
        if (block is ReplyBlock.Tool) {
            val t = block.payload.strOrNull("type")
            if (t == "web_search_tool_result" || t == "web_fetch_tool_result") {
                block.payload.strOrNull("tool_use_id")?.let { results[it] = block.payload }
            }
        }
    }
    val usedWeb = message.blocks.any {
        it is ReplyBlock.Tool && it.payload.strOrNull("type") == "server_tool_use"
    }
    val out = ArrayList<JsonObject>()
    var pendingType: String? = null
    val pendingBuf = StringBuilder()
    fun flushText() {
        val t = pendingType ?: return
        out.add(buildJsonObject {
            put("type", t)
            put("content", pendingBuf.toString())
        })
        pendingType = null
        pendingBuf.clear()
    }
    for (block in message.blocks) {
        when (block) {
            is ReplyBlock.Text -> {
                val t = if (block.isTemplate) "TEMPLATE_RESPONSE" else "RESPONSE"
                if (pendingType != t) {
                    flushText()
                    pendingType = t
                }
                pendingBuf.append(if (usedWeb) citationRegex.replace(block.text, "") else block.text)
            }
            is ReplyBlock.Thinking -> {
                if (block.thinking.isNotEmpty()) {
                    flushText()
                    val elapsed: Double? = block.elapsedSeconds?.toDouble()
                        ?: if (block.startedAt != null && block.endedAt != null) {
                            (block.endedAt - block.startedAt) / 1000.0
                        } else null
                    out.add(buildJsonObject {
                        put("type", "THINK")
                        put("content", block.thinking)
                        if (elapsed != null) put("elapsed_secs", elapsed) else put("elapsed_secs", JsonNull)
                    })
                }
            }
            is ReplyBlock.Tool -> {
                if (block.payload.strOrNull("type") == "server_tool_use") {
                    val toolId = block.payload.strOrNull("id")
                    val converted = toolToFragments(block.payload, toolId?.let { results[it] })
                    if (converted.isNotEmpty()) {
                        flushText()
                        out.addAll(converted)
                    }
                }
            }
        }
    }
    flushText()
    val hasTemplate = message.blocks.any { it is ReplyBlock.Text && it.isTemplate }
    if (message.status == ReplyStatus.ERROR && message.error != null) {
        out.add(buildJsonObject {
            put("type", "TEMPLATE_RESPONSE")
            put("content", message.error.message.ifEmpty { Strings.get("unknownError") })
        })
    } else if (!hasTemplate && message.stopReason == "refusal") {
        out.add(buildJsonObject {
            put("type", "TEMPLATE_RESPONSE")
            put("content", Strings.get("replyRefusal"))
        })
    }
    return JsonArray(out)
}

internal fun sessionToArchiveJson(
    original: Session,
    pinned: Boolean,
    exportImage: (FileAttachment) -> String?,
): Pair<JsonObject, JsonObject> {
    val session = original.normalized()
    val (ordered, ids) = numberMessages(session.messages)
    val firstReply = HashMap<String, Message.Assistant>()
    for (m in ordered) {
        if (m is Message.Assistant && m.parentId != null && m.parentId !in firstReply) {
            firstReply[m.parentId] = m
        }
    }
    val msgs = buildJsonArray {
        for (m in ordered) {
            val id = ids[m.id] ?: failArchive("backupInvalidBranches")
            val parentInt = m.parentId?.let { ids[it] }
            val reply = (m as? Message.Assistant) ?: firstReply[m.id]
            add(buildJsonObject {
                put("id", id)
                if (parentInt != null) put("parent_id", parentInt) else put("parent_id", JsonNull)
                put("role", if (m is Message.User) "USER" else "ASSISTANT")
                put("status", if (m is Message.User) "FINISHED" else archiveReplyStatus(m as Message.Assistant))
                put("thinking_enabled", reply?.thinking ?: false)
                put("search_enabled", reply?.search ?: false)
                put("accumulated_token_usage", 0)
                put("inserted_at", formatArchiveIso(m.createdAt))
                put("fragments", when (m) {
                    is Message.User -> buildJsonArray {
                        if (m.files.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", "FILE")
                                put("files", buildJsonArray {
                                    for (file in m.files) {
                                        val exportPath = if (file.isImage) exportImage(file) else null
                                        add(buildJsonObject {
                                            put("id", file.id)
                                            put("file_name", file.name)
                                            put("file_size", maxOf(0L, file.size))
                                            put("token_usage", JsonNull)
                                            put("inserted_at", formatArchiveIso(m.createdAt))
                                            if (exportPath != null) put("export_path", exportPath) else put("export_path", JsonNull)
                                            put("is_image", file.isImage)
                                            put("width", JsonNull)
                                            put("height", JsonNull)
                                        })
                                    }
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("type", "REQUEST")
                            put("content", m.content)
                        })
                    }
                    is Message.Assistant -> assistantToFragments(m)
                })
            })
        }
    }
    val current = visibleMessages(session).lastOrNull()
    val sessionObj = buildJsonObject {
        put("id", session.id)
        if (session.title.isEmpty()) put("title", JsonNull) else put("title", session.title)
        put("pinned", pinned)
        put("current_message_id", current?.let { ids[it.id] } ?: 0)
        put("inserted_at", formatArchiveIso(session.createdAt))
        put("updated_at", formatArchiveIso(session.updatedAt))
        put("messages", msgs)
    }
    val indexObj = buildJsonObject {
        put("id", session.id)
        if (session.title.isEmpty()) put("title", JsonNull) else put("title", session.title)
        put("pinned", pinned)
        put("message_count", ordered.size)
        put("inserted_at", formatArchiveIso(session.createdAt))
        put("updated_at", formatArchiveIso(session.updatedAt))
    }
    return sessionObj to indexObj
}

internal fun writeSessionsArchive(
    sessions: List<Session>,
    pinnedIds: Set<String>,
    outputStream: OutputStream,
) {
    val sorted = sessions.sortedBy { it.updatedAt }
    val imageEntries = LinkedHashMap<String, File>()
    val sessionEntries = ArrayList<Pair<String, String>>(sorted.size)
    val indexEntries = ArrayList<JsonObject>(sorted.size)
    for (session in sorted) {
        val (sObj, iObj) = sessionToArchiveJson(session, session.id in pinnedIds) { file ->
            val path = file.localPath ?: return@sessionToArchiveJson null
            val f = File(path)
            if (!f.isFile || !file.isImage) return@sessionToArchiveJson null
            val entryPath = imageArchivePath(session.id, file.id, file.name)
            if (!imageEntries.containsKey(entryPath)) imageEntries[entryPath] = f
            entryPath
        }
        sessionEntries.add(sessionJsonPath(session.id) to sObj.toString())
        indexEntries.add(iObj)
    }
    ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
        fun writeBytes(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        writeBytes(
            MANIFEST_PATH,
            buildJsonObject { put("export_format_version", EXPORT_FORMAT_VERSION) }.toString().toByteArray(Charsets.UTF_8),
        )
        for ((name, text) in sessionEntries) {
            writeBytes(name, text.toByteArray(Charsets.UTF_8))
        }
        writeBytes(INDEX_PATH, JsonArray(indexEntries).toString().toByteArray(Charsets.UTF_8))
        for ((entryPath, srcFile) in imageEntries) {
            zip.putNextEntry(ZipEntry(entryPath))
            srcFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

// ---------- Import (ZipFile + Schema + Tree Rebuild) ----------

internal data class ParsedArchiveBundle(
    val sessions: List<Session>,
    val pinnedIds: Set<String>,
    /** sessionId -> (archiveImageEntryNfc -> fileAttachmentId) */
    val imageRefsBySession: Map<String, Map<String, String>>,
    /** archiveImageEntryNfc -> extractedFilePath */
    val extractedFiles: Map<String, Pair<String, String>>,
)

private fun toolBlocksJson(
    id: String,
    input: JsonObject,
    kind: String,
    result: ReplyBlock.Tool? = null,
): List<ReplyBlock> = buildList {
    add(ReplyBlock.Tool(buildJsonObject {
        put("type", "server_tool_use")
        put("id", id)
        put("name", if (kind == "read_link") "deepseek_read_url" else "deepseek_web_search")
        put("input", input)
        put("kind", kind)
        put("caller", buildJsonObject { put("type", "direct") })
    }))
    if (result != null) add(result)
}

private fun fetchResultBlock(toolUseId: String, content: JsonObject): ReplyBlock.Tool =
    ReplyBlock.Tool(buildJsonObject {
        put("type", "web_fetch_tool_result")
        put("tool_use_id", toolUseId)
        put("kind", "deep_search")
        put("content", content)
    })

private fun fetchedContent(url: String, title: String?, publishedAt: String?): JsonObject =
    buildJsonObject {
        put("type", "web_fetch_result")
        put("url", url)
        put("content", buildJsonObject {
            put("type", "document")
            put("source", buildJsonObject {
                put("type", "text")
                put("media_type", "text/plain")
                put("data", "")
            })
            if (title != null) put("title", title) else put("title", JsonNull)
        })
        if (publishedAt != null) put("published_at", publishedAt) else put("published_at", JsonNull)
    }

private val fetchUnavailableContent = buildJsonObject {
    put("type", "web_fetch_tool_result_error")
    put("error_code", "unavailable")
}

private fun parseFragmentsToBlocks(fragments: JsonArray, msgId: String): List<ReplyBlock> {
    val parsed = ArrayList<JsonObject>()
    for (el in fragments) {
        val obj = el as? JsonObject ?: continue
        val t = obj.optStrOrNull("type") ?: continue
        parsed.add(obj)
    }
    if (parsed.size == 1 && parsed[0].optStrOrNull("type") == "RESPONSE") {
        val c = (parsed[0]["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        return listOf(ReplyBlock.Text(c))
    }
    val searchKind = if (parsed.any {
            val t = it.optStrOrNull("type")
            t == "TOOL_OPEN" || t == "TOOL_FIND"
        }
    ) "deep_search" else "search"
    val blocks = ArrayList<ReplyBlock>()
    parsed.forEachIndexed { idx, frag ->
        val id = "history-$msgId-$idx"
        when (frag.optStrOrNull("type")) {
            "RESPONSE" -> blocks.add(ReplyBlock.Text(frag.reqStr("content")))
            "TEMPLATE_RESPONSE" -> blocks.add(ReplyBlock.Text(frag.reqStr("content"), isTemplate = true))
            "THINK" -> {
                val secsEl = frag["elapsed_secs"]
                val secs = if (secsEl == null || secsEl is JsonNull) null else {
                    val d = frag.reqNumber("elapsed_secs")
                    kotlin.math.round(d).toLong()
                }
                blocks.add(ReplyBlock.Thinking(thinking = frag.reqStr("content"), elapsedSeconds = secs))
            }
            "TOOL_SEARCH" -> {
                val queries = frag.reqArr("queries").map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: failArchive("backupIncomplete")
                }
                val status = frag.optStrOrNull("status") ?: "FINISHED"
                val resultsArr = frag.reqArr("results")
                val resultBlock = if (status == "WIP") null else ReplyBlock.Tool(buildJsonObject {
                    put("type", "web_search_tool_result")
                    put("tool_use_id", id)
                    put("kind", searchKind)
                    put("content", buildJsonArray {
                        if (status == "FAILED") {
                            add(buildJsonObject {
                                put("type", "web_search_tool_result_error")
                                put("error_code", "unavailable")
                            })
                        } else {
                            for (el in resultsArr) {
                                val rObj = el as? JsonObject ?: failArchive("backupIncomplete")
                                val url = rObj.reqStr("url")
                                if (!isHttpUrl(url)) continue
                                val title = rObj.reqStr("title")
                                val snippet = rObj.reqStr("snippet")
                                val pub = rObj.reqNullableStr("published_at")
                                val sn = rObj.reqNullableStr("site_name")
                                val icon = rObj.optStrOrNull("site_icon")
                                add(buildJsonObject {
                                    put("type", "web_search_result")
                                    put("url", url)
                                    put("title", title)
                                    put("snippet", snippet)
                                    if (icon != null && isHttpUrl(icon)) put("site_icon", icon) else put("site_icon", JsonNull)
                                    if (sn != null) put("site_name", sn) else put("site_name", JsonNull)
                                    if (pub != null) put("page_age", pub) else put("page_age", JsonNull)
                                    put("encrypted_content", "")
                                })
                            }
                        }
                    })
                })
                blocks.addAll(toolBlocksJson(
                    id = id,
                    input = buildJsonObject {
                        put("action", "search")
                        put("query", queries.firstOrNull() ?: "")
                        put("queries", buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } })
                    },
                    kind = searchKind,
                    result = resultBlock,
                ))
            }
            "TOOL_OPEN" -> {
                val url = frag.reqNullableStr("url")
                if (!isHttpUrl(url)) return@forEachIndexed
                val status = frag.reqStr("status")
                val title = frag.reqNullableStr("title")
                val pub = frag.reqNullableStr("published_at")
                val res = if (status == "WIP") null else fetchResultBlock(
                    id,
                    if (status == "FINISHED") fetchedContent(url!!, title, pub) else fetchUnavailableContent,
                )
                blocks.addAll(toolBlocksJson(
                    id = id,
                    input = buildJsonObject {
                        put("action", "open_page")
                        put("url", url!!)
                    },
                    kind = "deep_search",
                    result = res,
                ))
            }
            "TOOL_FIND" -> {
                val status = frag.reqStr("status")
                val pattern = frag.optStrOrNull("pattern") ?: ""
                val res = if (status == "WIP") null else fetchResultBlock(
                    id,
                    if (status == "FINISHED") fetchedContent("", null, null) else fetchUnavailableContent,
                )
                blocks.addAll(toolBlocksJson(
                    id = id,
                    input = buildJsonObject {
                        put("action", "find_in_page")
                        put("url", "")
                        put("pattern", pattern)
                    },
                    kind = "deep_search",
                    result = res,
                ))
            }
            "READ_LINK" -> {
                frag.reqArr("results").forEachIndexed { rIdx, el ->
                    val entry = el as? JsonObject ?: failArchive("backupIncomplete")
                    val sig = entry.reqStr("signature")
                    val rawUrl = entry.optStrOrNull("url")
                    val url = if (isHttpUrl(rawUrl)) rawUrl!! else ""
                    if (url.isEmpty() && sig.isBlank()) return@forEachIndexed
                    val title = entry.optStrOrNull("title")
                    val toolId = "$id-$rIdx"
                    blocks.addAll(toolBlocksJson(
                        id = toolId,
                        input = buildJsonObject {
                            put("action", "read_link")
                            put("url", url)
                        },
                        kind = "read_link",
                        result = ReplyBlock.Tool(buildJsonObject {
                            put("type", "web_fetch_tool_result")
                            put("tool_use_id", toolId)
                            put("kind", "read_link")
                            put("content", fetchedContent(url, title, null))
                            put("signature", sig)
                        }),
                    ))
                }
            }
        }
    }
    return blocks
}

private fun parseArchiveStatus(status: String): ReplyStatus = when (status) {
    "FINISHED", "CONTENT_FILTER" -> ReplyStatus.DONE
    "INCOMPLETE", "WIP" -> ReplyStatus.STOPPED
    "FAILED", "CONTEXT_LENGTH_EXCEEDED", "TIMEOUT" -> ReplyStatus.ERROR
    else -> failArchive("backupIncomplete")
}

internal fun parseArchiveSessionFile(
    rawJson: String,
    indexPinned: Map<String, Boolean>,
): Triple<Session, Boolean, Map<String, String>> {
    val root = try {
        archiveJson.parseToJsonElement(rawJson).jsonObject
    } catch (_: Exception) {
        failArchive("backupInvalidJson")
    }
    val sid = root.reqSessionId()
    val title = root.reqNullableStr("title") ?: ""
    val filePinned = if ("pinned" in root) root.reqBool("pinned") else false
    val pinned = filePinned || (indexPinned[sid] == true)
    val currentId = root.reqInt("current_message_id")
    val createdAt = parseArchiveIso(root.reqStr("inserted_at")) ?: failArchive("backupIncomplete")
    val updatedAt = parseArchiveIso(root.reqStr("updated_at")) ?: failArchive("backupIncomplete")
    val rawMessages = root.reqArr("messages")
    val imagePaths = LinkedHashMap<String, String>()
    val filesPrefix = sessionFilesPrefix(sid)

    data class RawMsg(val numId: Int, val parentNumId: Int?, val msg: Message)
    val items = rawMessages.map { el ->
        val mObj = el as? JsonObject ?: failArchive("backupIncomplete")
        val numId = mObj.reqInt("id")
        val parentNumId = mObj.reqNullableInt("parent_id")
        val role = mObj.reqStr("role")
        val statusStr = mObj.reqStr("status")
        val thinkingEnabled = mObj.reqBool("thinking_enabled")
        val searchEnabled = mObj.reqBool("search_enabled")
        val mCreated = parseArchiveIso(mObj.reqStr("inserted_at")) ?: failArchive("backupIncomplete")
        val frags = mObj.reqArr("fragments")
        val msgIdStr = numId.toString()
        val msg: Message = when (role) {
            "USER" -> {
                val content = StringBuilder()
                val files = ArrayList<FileAttachment>()
                for (fel in frags) {
                    val f = fel as? JsonObject ?: continue
                    when (f.optStrOrNull("type")) {
                        "REQUEST" -> content.append(f.reqStr("content"))
                        "FILE" -> {
                            for (feEl in f.reqArr("files")) {
                                val fe = feEl as? JsonObject ?: failArchive("backupIncomplete")
                                val fid = fe.reqStr("id")
                                val fname = fe.reqStr("file_name")
                                val fsize = (fe["file_size"] as? JsonPrimitive)?.longOrNull
                                    ?.takeIf { it >= 0L } ?: failArchive("backupIncomplete")
                                val isImg = fe.reqBool("is_image")
                                val exportPath = fe.reqNullableStr("export_path")
                                    ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
                                if (isImg && exportPath != null) {
                                    if (!exportPath.startsWith(filesPrefix) ||
                                        (imagePaths[exportPath] ?: fid) != fid
                                    ) {
                                        failArchive("backupInvalidFiles")
                                    }
                                    imagePaths[exportPath] = fid
                                }
                                files.add(
                                    FileAttachment(
                                        id = fid,
                                        serverId = null,
                                        name = fname,
                                        size = fsize,
                                        isImage = isImg,
                                        mimeType = "",
                                        localPath = null,
                                    ),
                                )
                            }
                        }
                    }
                }
                Message.User(id = msgIdStr, content = content.toString(), files = files, createdAt = mCreated)
            }
            "ASSISTANT" -> {
                val allBlocks = parseFragmentsToBlocks(frags, msgIdStr)
                val refused = statusStr == "CONTENT_FILTER"
                val filteredBlocks = if (refused) {
                    allBlocks.filter { it !is ReplyBlock.Thinking && (it !is ReplyBlock.Text || it.isTemplate) }
                } else allBlocks
                Message.Assistant(
                    id = msgIdStr,
                    createdAt = mCreated,
                    thinking = thinkingEnabled,
                    search = searchEnabled,
                    prefix = false,
                    status = parseArchiveStatus(statusStr),
                    blocks = filteredBlocks,
                    stopReason = if (refused) "refusal" else null,
                )
            }
            else -> failArchive("backupIncomplete")
        }
        RawMsg(numId, parentNumId, msg)
    }.sortedBy { it.numId }

    val byNumId = HashMap<Int, Message>(items.size)
    val ordered = ArrayList<Message>(items.size)
    for (item in items) {
        if (item.numId in byNumId) failArchive("backupInvalidBranches")
        val parent = item.parentNumId?.let { byNumId[it] }
        if (item.parentNumId == null) {
            if (item.msg !is Message.User) failArchive("backupInvalidBranches")
        } else {
            if (parent == null) failArchive("backupInvalidBranches")
            if ((parent is Message.User && item.msg is Message.User) ||
                (parent is Message.Assistant && item.msg is Message.Assistant)
            ) failArchive("backupInvalidBranches")
        }
        val withParent = when (item.msg) {
            is Message.User -> item.msg.copy(parentId = parent?.id)
            is Message.Assistant -> item.msg.copy(parentId = parent?.id)
        }
        byNumId[item.numId] = withParent
        ordered.add(withParent)
        if (parent != null) {
            val pIdx = ordered.indexOfFirst { it.id == parent.id }
            val updatedParent = when (parent) {
                is Message.User -> parent.copy(selectedChildId = withParent.id)
                is Message.Assistant -> parent.copy(selectedChildId = withParent.id)
            }
            byNumId[item.parentNumId!!] = updatedParent
            if (pIdx >= 0) ordered[pIdx] = updatedParent
        }
    }
    if (ordered.isEmpty()) {
        return Triple(Session(id = sid, title = title, createdAt = createdAt, updatedAt = updatedAt), pinned, imagePaths)
    }
    var cur = byNumId[currentId] ?: failArchive("backupInvalidBranches")
    // Clear trailing selectedChildId below current_message_id so stopped/middle leaf is respected.
    fun setSelectedChild(node: Message, childId: String?): Message = when (node) {
        is Message.User -> node.copy(selectedChildId = childId)
        is Message.Assistant -> node.copy(selectedChildId = childId)
    }
    val curIdx = ordered.indexOfFirst { it.id == cur.id }
    val clearedCur = setSelectedChild(cur, null)
    if (curIdx >= 0) ordered[curIdx] = clearedCur
    var walk = clearedCur
    while (walk.parentId != null) {
        val pNumId = walk.parentId!!.toIntOrNull() ?: failArchive("backupInvalidBranches")
        val pNode = byNumId[pNumId] ?: failArchive("backupInvalidBranches")
        val pUpdated = setSelectedChild(pNode, walk.id)
        byNumId[pNumId] = pUpdated
        val pIdx = ordered.indexOfFirst { it.id == pNode.id }
        if (pIdx >= 0) ordered[pIdx] = pUpdated
        walk = pUpdated
    }
    val session = Session(
        id = sid,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = ordered,
        rootMessageId = walk.id,
    )
    return Triple(session, pinned, imagePaths)
}

internal fun readArchiveZipFile(
    zipFile: File,
    attachesDir: File,
): ParsedArchiveBundle {
    if (!isZipMagic(zipFile)) failArchive("backupUnrecognized")
    attachesDir.mkdirs()
    val extractedFiles = HashMap<String, Pair<String, String>>()
    try {
        ZipFile(zipFile).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_PATH) ?: failArchive("backupUnrecognized")
            val manifestObj = try {
                archiveJson.parseToJsonElement(
                    zip.getInputStream(manifestEntry).readBytes().toString(Charsets.UTF_8),
                ).jsonObject
            } catch (_: Exception) {
                failArchive("backupInvalidJson")
            }
            val verEl = manifestObj["export_format_version"]
            val version = (verEl as? JsonPrimitive)?.intOrNull
            if (version != null && version > EXPORT_FORMAT_VERSION) failArchive("backupNewerVersion")
            if (version != EXPORT_FORMAT_VERSION) failArchive("backupInvalidVersion")

            val indexEntry = zip.getEntry(INDEX_PATH) ?: failArchive("backupIncomplete")
            val indexArr = try {
                archiveJson.parseToJsonElement(
                    zip.getInputStream(indexEntry).readBytes().toString(Charsets.UTF_8),
                ).jsonArray
            } catch (_: Exception) {
                failArchive("backupInvalidJson")
            }
            val indexPinned = HashMap<String, Boolean>()
            val listedIds = HashSet<String>()
            for (el in indexArr) {
                val obj = el as? JsonObject ?: failArchive("backupIncomplete")
                val id = obj.reqSessionId()
                if (!listedIds.add(id)) failArchive("backupIncomplete")
                indexPinned[id] = if ("pinned" in obj) obj.reqBool("pinned") else false
            }

            val sessions = ArrayList<Session>()
            val pinnedIds = HashSet<String>()
            val imageRefsBySession = HashMap<String, Map<String, String>>()
            val entryByNormPath = HashMap<String, ZipEntry>()
            val allEntries = zip.entries()
            while (allEntries.hasMoreElements()) {
                val e = allEntries.nextElement()
                if (!e.isDirectory) {
                    val norm = Normalizer.normalize(e.name, Normalizer.Form.NFC)
                    entryByNormPath[norm] = e
                }
            }
            val importedIds = HashSet<String>()
            for ((path, entry) in entryByNormPath) {
                if (path == MANIFEST_PATH || path == INDEX_PATH || path == USER_PATH) continue
                val sidMatch = sessionPathRegex.matchEntire(path)
                if (sidMatch != null) {
                    val sid = sidMatch.groupValues[1]
                    if (sid !in importedIds.also { it.add(sid) }) failArchive("backupIncomplete")
                    val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val (session, pinned, refs) = parseArchiveSessionFile(text, indexPinned)
                    if (session.id != sid) failArchive("backupIncomplete")
                    sessions.add(session)
                    if (pinned) pinnedIds.add(sid)
                    imageRefsBySession[sid] = refs
                } else if (!sessionFileEntryRegex.containsMatchIn(path)) {
                    failArchive("backupIncomplete")
                }
            }
            if (importedIds != listedIds) failArchive("backupIncomplete")

            // Extract referenced images and attach local paths
            val outSessions = sessions.map { session ->
                val refs = imageRefsBySession[session.id].orEmpty()
                if (refs.isEmpty()) return@map session
                val idToPathAndMime = HashMap<String, Pair<String, String>>()
                for ((archivePath, fileId) in refs) {
                    val entry = entryByNormPath[archivePath] ?: continue
                    val bytes = zip.getInputStream(entry).readBytes()
                    val mime = detectImageMimeType(bytes)
                    val rawName = archivePath.substringAfterLast('/')
                    val destFile = File(attachesDir, "${UUID.randomUUID()}-${safePathSegment(rawName)}")
                    destFile.writeBytes(bytes)
                    val pair = destFile.absolutePath to mime
                    extractedFiles[archivePath] = pair
                    idToPathAndMime[fileId] = pair
                }
                if (idToPathAndMime.isEmpty()) return@map session
                session.copy(messages = session.messages.map { msg ->
                    if (msg !is Message.User || msg.files.isEmpty()) msg
                    else msg.copy(files = msg.files.map { f ->
                        val resolved = idToPathAndMime[f.id] ?: return@map f
                        f.copy(localPath = resolved.first, mimeType = f.mimeType.ifEmpty { resolved.second })
                    })
                })
            }
            return ParsedArchiveBundle(outSessions, pinnedIds, imageRefsBySession, extractedFiles)
        }
    } catch (e: ArchiveBackupException) {
        extractedFiles.values.forEach { (p, _) -> File(p).delete() }
        throw e
    } catch (e: Exception) {
        extractedFiles.values.forEach { (p, _) -> File(p).delete() }
        throw ArchiveBackupException("backupIncomplete").initCause(e)
    }
}

private fun isZipMagic(file: File): Boolean {
    if (!file.isFile || file.length() < 4) return false
    return try {
        file.inputStream().use { input ->
            val buf = ByteArray(4)
            if (input.read(buf) < 4) return false
            buf[0] == 0x50.toByte() && buf[1] == 0x4B.toByte() &&
                ((buf[2] == 0x03.toByte() && buf[3] == 0x04.toByte()) ||
                 (buf[2] == 0x05.toByte() && buf[3] == 0x06.toByte()))
        }
    } catch (_: Exception) {
        false
    }
}

fun copyStreamToTempZip(input: InputStream, cacheDir: File): File {
    val tmp = File.createTempFile("archive-import-", ".zip", cacheDir)
    tmp.outputStream().use { out -> input.copyTo(out) }
    return tmp
}

// ---------- Deep Tree Merge ----------

private enum class StatusBucket(val rank: Int) { ERROR(1), INCOMPLETE(2), REFUSAL(3), DONE(4) }

private fun statusBucket(msg: Message.Assistant): StatusBucket = when {
    msg.stopReason == "refusal" -> StatusBucket.REFUSAL
    msg.status == ReplyStatus.ERROR -> StatusBucket.ERROR
    msg.status == ReplyStatus.DONE && msg.stopReason != "max_tokens" -> StatusBucket.DONE
    else -> StatusBucket.INCOMPLETE
}

private data class CanonicalUser(val createdAt: Long, val content: String, val filesKey: String)

private data class CanonicalAssistant(
    val createdAt: Long,
    val text: String,
    val thinking: String,
    val templates: String,
    val hasTemplate: Boolean,
    val canSynthesizeTemplate: Boolean,
    val tools: List<String>,
    val statusBucket: StatusBucket,
)

private fun canonicalTools(blocks: List<ReplyBlock>): List<String> {
    val results = HashMap<String, JsonObject>()
    for (b in blocks) {
        if (b is ReplyBlock.Tool) {
            val t = b.payload.strOrNull("type")
            if (t == "web_search_tool_result" || t == "web_fetch_tool_result") {
                b.payload.strOrNull("tool_use_id")?.let { results[it] = b.payload }
            }
        }
    }
    val out = ArrayList<String>()
    for (b in blocks) {
        if (b !is ReplyBlock.Tool || b.payload.strOrNull("type") != "server_tool_use") continue
        val input = b.payload["input"] as? JsonObject ?: continue
        val action = input.strOrNull("action") ?: ""
        val id = b.payload.strOrNull("id")
        val res = id?.let { results[it] }
        if (action == "search" || action == "deep_search" || action == "image_search" || action == "deep_image_search") {
            val queries = (input["queries"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull
            } ?: listOfNotNull(input.strOrNull("query"))
            val items = if (res?.strOrNull("type") == "web_search_tool_result") {
                res["content"] as? JsonArray ?: JsonArray(emptyList())
            } else JsonArray(emptyList())
            val st = when {
                res?.strOrNull("type") != "web_search_tool_result" -> "WIP"
                items.any { (it as? JsonObject)?.strOrNull("type") == "web_search_tool_result_error" } -> "FAILED"
                else -> "FINISHED"
            }
            val urls = if (st == "FINISHED") items.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                if (obj.strOrNull("type") != "web_search_result") return@mapNotNull null
                obj.strOrNull("url")?.takeIf(::isHttpUrl)
            } else emptyList()
            out.add("search:$queries:$st:$urls")
            continue
        }
        if (action == "read_link") {
            val c = res?.get("content") as? JsonObject
            val sig = res?.strOrNull("signature")?.trim()
            if (res?.strOrNull("type") == "web_fetch_tool_result" &&
                res.strOrNull("kind") == "read_link" &&
                c?.strOrNull("type") == "web_fetch_result" && !sig.isNullOrEmpty()
            ) {
                val rawUrl = c.strOrNull("url")?.ifEmpty { null } ?: input.strOrNull("url") ?: ""
                val u = if (isHttpUrl(rawUrl)) rawUrl else ""
                out.add("read_link:$u:$sig")
            }
            continue
        }
        val content = if (res?.strOrNull("type") == "web_fetch_tool_result") res["content"] as? JsonObject else null
        val page = if (content?.strOrNull("type") == "web_fetch_result") content else null
        val st = when {
            content == null -> "WIP"
            page != null -> "FINISHED"
            else -> "FAILED"
        }
        if (action == "find_in_page" || action == "find_pattern" || action == "find") {
            out.add("find:$st:${input.strOrNull("pattern") ?: input.strOrNull("query") ?: ""}")
            continue
        }
        val rawUrl = input.strOrNull("url")?.ifEmpty { null } ?: page?.strOrNull("url") ?: ""
        if (isHttpUrl(rawUrl)) out.add("open:$st:$rawUrl")
    }
    return out
}

private fun canonicalUser(m: Message.User) = CanonicalUser(
    createdAt = m.createdAt,
    content = m.content,
    filesKey = m.files.joinToString("|") { "${it.id}:${it.name}:${it.size}:${it.isImage}" },
)

private fun canonicalAssistant(m: Message.Assistant): CanonicalAssistant {
    val refused = m.stopReason == "refusal"
    val usedWeb = m.blocks.any { it is ReplyBlock.Tool && it.payload.strOrNull("type") == "server_tool_use" }
    val text = StringBuilder()
    val thinking = StringBuilder()
    val templates = StringBuilder()
    var hasTemplate = false
    for (b in m.blocks) {
        when (b) {
            is ReplyBlock.Text -> {
                val norm = if (usedWeb) citationRegex.replace(b.text, "") else b.text
                if (b.isTemplate) {
                    hasTemplate = true
                    templates.append(norm)
                } else if (!refused) {
                    text.append(norm)
                }
            }
            is ReplyBlock.Thinking -> if (!refused && b.thinking.isNotEmpty()) thinking.append(b.thinking)
            is ReplyBlock.Tool -> {}
        }
    }
    return CanonicalAssistant(
        createdAt = m.createdAt,
        text = text.toString(),
        thinking = thinking.toString(),
        templates = templates.toString(),
        hasTemplate = hasTemplate,
        canSynthesizeTemplate = (m.status == ReplyStatus.ERROR && m.error != null) || (!hasTemplate && refused),
        tools = canonicalTools(m.blocks),
        statusBucket = statusBucket(m),
    )
}

private fun canonicalMatch(a: Any, b: Any): Boolean {
    if (a is CanonicalUser && b is CanonicalUser) {
        return a.createdAt == b.createdAt && a.content == b.content && a.filesKey == b.filesKey
    }
    if (a is CanonicalAssistant && b is CanonicalAssistant) {
        if (a.createdAt != b.createdAt || a.text != b.text || a.thinking != b.thinking ||
            a.statusBucket != b.statusBucket || a.tools != b.tools
        ) return false
        if (a.canSynthesizeTemplate != b.canSynthesizeTemplate) return true
        return a.templates == b.templates
    }
    return false
}

private fun isSubsumedBy(older: CanonicalAssistant, newer: CanonicalAssistant): Boolean {
    if (older.statusBucket != StatusBucket.INCOMPLETE && older.statusBucket != StatusBucket.ERROR) return false
    if (!newer.text.startsWith(older.text) || !newer.thinking.startsWith(older.thinking)) return false
    if (older.tools.size > newer.tools.size ||
        older.tools.indices.any { older.tools[it] != newer.tools[it] }
    ) return false
    return newer.text.length > older.text.length ||
        newer.thinking.length > older.thinking.length ||
        newer.tools.size > older.tools.size ||
        newer.statusBucket.rank > older.statusBucket.rank
}

internal data class MergeSessionResult(
    val session: Session,
    val changed: Boolean,
    val addedMessages: Int,
    val updatedMessages: Int,
)

internal fun mergeArchiveSession(
    localInput: Session,
    incomingInput: Session,
    createId: () -> String = { UUID.randomUUID().toString() },
): MergeSessionResult {
    val local = localInput.normalized()
    val incoming = incomingInput.normalized()
    val createdAt = minOf(local.createdAt, incoming.createdAt)
    val updatedAt = maxOf(local.updatedAt, incoming.updatedAt)
    val title = when {
        local.title.isEmpty() && incoming.title.isNotEmpty() -> incoming.title
        local.title.isNotEmpty() && incoming.title.isNotEmpty() && local.title != incoming.title &&
            incoming.updatedAt > local.updatedAt -> incoming.title
        else -> local.title
    }
    var changed = createdAt != localInput.createdAt || updatedAt != localInput.updatedAt || title != localInput.title
    var addedMessages = 0
    var updatedMessages = 0

    val localChildren = local.messages.groupBy { it.parentId }
    val incomingChildren = incoming.messages.groupBy { it.parentId }
    val usedIds = local.messages.map { it.id }.toHashSet()
    val mergedById = LinkedHashMap<String, Message>()
    val originalSelected = HashMap<String, String?>()
    for (m in local.messages) {
        mergedById[m.id] = m
        originalSelected[m.id] = m.selectedChildId
    }
    val incomingToMergedId = HashMap<String, String>()
    val incomingById = incoming.messages.associateBy { it.id }
    val appendedMessages = ArrayList<Message>()
    val appendedIds = HashSet<String>()

    fun withLinks(m: Message, id: String, parentId: String?, selectedChildId: String?): Message = when (m) {
        is Message.User -> m.copy(id = id, parentId = parentId, selectedChildId = selectedChildId)
        is Message.Assistant -> m.copy(id = id, parentId = parentId, selectedChildId = selectedChildId)
    }

    fun graftSubtree(incomingRoot: Message, targetParentId: String?) {
        val stack = ArrayDeque<Pair<Message, String?>>()
        stack.addLast(incomingRoot to targetParentId)
        val graftedPairs = ArrayList<Pair<Message, String>>()
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val (node, pId) = stack.removeLast()
            if (!visited.add(node.id)) continue
            var mergedId = node.id
            while (mergedId in usedIds) mergedId = createId()
            usedIds.add(mergedId)
            incomingToMergedId[node.id] = mergedId
            val cloned = withLinks(node, mergedId, pId, null)
            mergedById[mergedId] = cloned
            appendedMessages.add(cloned)
            appendedIds.add(mergedId)
            graftedPairs.add(node to mergedId)
            addedMessages++
            changed = true
            val children = incomingChildren[node.id].orEmpty()
            for (i in children.indices.reversed()) stack.addLast(children[i] to mergedId)
        }
        for ((inNode, mergedId) in graftedPairs) {
            val mappedChild = inNode.selectedChildId?.let { incomingToMergedId[it] } ?: continue
            val m = mergedById[mergedId] ?: continue
            val updated = withLinks(m, m.id, m.parentId, mappedChild)
            mergedById[mergedId] = updated
            val appIdx = appendedMessages.indexOfFirst { it.id == mergedId }
            if (appIdx >= 0) appendedMessages[appIdx] = updated
        }
    }

    val canonCache = HashMap<Message, Any>()
    fun canonOf(m: Message): Any = canonCache.getOrPut(m) {
        when (m) {
            is Message.User -> canonicalUser(m)
            is Message.Assistant -> canonicalAssistant(m)
        }
    }

    val pairQueue = ArrayDeque<Pair<String?, String?>>()
    pairQueue.addLast(null to null)
    val visitedParents = HashSet<String?>()
    while (pairQueue.isNotEmpty()) {
        val (localParentId, incomingParentId) = pairQueue.removeFirst()
        if (!visitedParents.add(incomingParentId)) continue
        val inList = incomingChildren[incomingParentId].orEmpty()
        val locList = localChildren[localParentId].orEmpty()
        val matchedLocalIds = HashSet<String>()

        for (inChild in inList) {
            val inCanon = canonOf(inChild)
            val matchedLocal = locList.firstOrNull { locChild ->
                locChild.id !in matchedLocalIds && canonicalMatch(canonOf(locChild), inCanon)
            }
            if (matchedLocal != null) {
                matchedLocalIds.add(matchedLocal.id)
                incomingToMergedId[inChild.id] = matchedLocal.id
                if (matchedLocal is Message.User && inChild is Message.User) {
                    val target = mergedById[matchedLocal.id] as Message.User
                    var fileMutated = false
                    val backfilled = target.files.mapIndexed { idx, f ->
                        val inc = inChild.files.getOrNull(idx)
                        val newMime = if (f.mimeType.isEmpty() && inc != null && inc.mimeType.isNotEmpty()) inc.mimeType else f.mimeType
                        val missingLocal = f.localPath == null || !File(f.localPath).exists()
                        val newPath = if (missingLocal && inc?.localPath != null) inc.localPath else f.localPath
                        if (newMime != f.mimeType || newPath != f.localPath) {
                            fileMutated = true
                            f.copy(mimeType = newMime, localPath = newPath)
                        } else f
                    }
                    if (fileMutated) {
                        mergedById[matchedLocal.id] = target.copy(files = backfilled)
                        changed = true
                        updatedMessages++
                    }
                }
                pairQueue.addLast(matchedLocal.id to inChild.id)
                continue
            }
            if (inChild is Message.Assistant && inCanon is CanonicalAssistant) {
                var evolvedLocal: Message.Assistant? = null
                var upgradeWithIncoming = false
                for (locChild in locList) {
                    if (locChild.id in matchedLocalIds || locChild !is Message.Assistant) continue
                    if (locChild.createdAt != inChild.createdAt) continue
                    val locCanon = canonOf(locChild) as CanonicalAssistant
                    if (isSubsumedBy(locCanon, inCanon)) {
                        evolvedLocal = locChild; upgradeWithIncoming = true; break
                    }
                    if (isSubsumedBy(inCanon, locCanon)) {
                        evolvedLocal = locChild; upgradeWithIncoming = false; break
                    }
                }
                if (evolvedLocal != null) {
                    matchedLocalIds.add(evolvedLocal.id)
                    incomingToMergedId[inChild.id] = evolvedLocal.id
                    if (upgradeWithIncoming) {
                        val target = mergedById[evolvedLocal.id] as Message.Assistant
                        mergedById[evolvedLocal.id] = target.copy(
                            model = target.model ?: inChild.model,
                            latestReminder = target.latestReminder ?: inChild.latestReminder,
                            thinking = inChild.thinking,
                            search = inChild.search,
                            prefix = inChild.prefix,
                            status = inChild.status,
                            stopReason = inChild.stopReason,
                            blocks = inChild.blocks,
                            error = if (inChild.status == ReplyStatus.ERROR) inChild.error else null,
                        )
                        changed = true
                        updatedMessages++
                    }
                    pairQueue.addLast(evolvedLocal.id to inChild.id)
                    continue
                }
            }
            graftSubtree(inChild, localParentId)
        }
    }

    val localVisible = visibleMessages(local)
    val incomingVisible = visibleMessages(incoming)
    val localLeafId = localVisible.lastOrNull()?.id
    val mappedIncomingVisible = incomingVisible.mapNotNull { incomingToMergedId[it.id] }
    val mappedIncomingLeafId = mappedIncomingVisible.lastOrNull()

    fun isAncestor(ancestorId: String, descendantId: String): Boolean {
        var cursor: String? = descendantId
        val seen = HashSet<String>()
        while (cursor != null && seen.add(cursor)) {
            if (cursor == ancestorId) return true
            cursor = mergedById[cursor]?.parentId
        }
        return false
    }

    val selectIncomingPath = when {
        localLeafId == null -> mappedIncomingLeafId != null
        mappedIncomingLeafId == null || mappedIncomingLeafId == localLeafId -> false
        isAncestor(localLeafId, mappedIncomingLeafId) -> true
        isAncestor(mappedIncomingLeafId, localLeafId) -> false
        else -> incoming.updatedAt > local.updatedAt
    }

    var rootMessageId = local.rootMessageId
    if (selectIncomingPath && mappedIncomingVisible.isNotEmpty()) {
        if (rootMessageId != mappedIncomingVisible.first()) {
            rootMessageId = mappedIncomingVisible.first()
            changed = true
        }
        for (i in 0 until mappedIncomingVisible.size - 1) {
            val id = mappedIncomingVisible[i]
            val next = mappedIncomingVisible[i + 1]
            val node = mergedById[id] ?: continue
            if (node.selectedChildId != next) {
                mergedById[id] = withLinks(node, node.id, node.parentId, next)
            }
        }
        val incomingLeaf = mappedIncomingLeafId?.let { mergedById[it] }
        if (incomingLeaf != null && incomingVisible.lastOrNull()?.selectedChildId == null) {
            mergedById[incomingLeaf.id] = withLinks(incomingLeaf, incomingLeaf.id, incomingLeaf.parentId, null)
        }
    }

    val localVisibleIds = localVisible.map { it.id }.toHashSet()
    for ((inId, mergedId) in incomingToMergedId) {
        if (mergedId in appendedIds || mergedId in localVisibleIds) continue
        val localNode = mergedById[mergedId] ?: continue
        val inNode = incomingById[inId] ?: continue
        if (localNode.selectedChildId == null && inNode.selectedChildId != null) {
            val mappedChild = incomingToMergedId[inNode.selectedChildId]
            if (mappedChild != null) {
                mergedById[mergedId] = withLinks(localNode, localNode.id, localNode.parentId, mappedChild)
            }
        }
    }

    for (m in local.messages) {
        val u = mergedById[m.id]
        if (u != null && u.selectedChildId != originalSelected[m.id]) changed = true
    }

    if (!changed) return MergeSessionResult(localInput, changed = false, addedMessages = 0, updatedMessages = 0)
    val mergedMessages = local.messages.map { mergedById[it.id] ?: it } + appendedMessages
    return MergeSessionResult(
        session = Session(
            id = local.id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = mergedMessages,
            rootMessageId = rootMessageId,
            contextCheckpoints = local.contextCheckpoints,
        ),
        changed = true,
        addedMessages = addedMessages,
        updatedMessages = updatedMessages,
    )
}
