package io.github.lijiahua1024.dsnative.api

import io.github.lijiahua1024.dsnative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 默认包（debug 和 release）的 API 地址 = 参照物真实地址。 */
const val API_ORIGIN: String = BuildConfig.API_ORIGIN

/** Debug 包的运行时覆盖（adb 注入）；release 恒为 API_ORIGIN。 */
object ApiOrigin {
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        if (!BuildConfig.DEBUG) return
        prefs = context.applicationContext
            .getSharedPreferences("debug_api", android.content.Context.MODE_PRIVATE)
    }

    fun setOverride(origin: String) {
        prefs?.edit()?.putString("origin", origin)?.apply()
    }

    fun get(): String =
        if (BuildConfig.DEBUG) prefs?.getString("origin", null) ?: API_ORIGIN
        else API_ORIGIN
}

const val MODEL = "deepseek-flash"
const val MAX_OUTPUT_TOKENS = 393_216
const val SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。"

enum class ApiErrorKind { HTTP, STREAM, NETWORK, INTERRUPTED, PROTOCOL, UPLOAD }

class ApiError(
    val kind: ApiErrorKind,
    message: String,
    val status: Int? = null,
    val code: String? = null,
) : Exception(message)

/** 流式事件（Messages API，Anthropic 兼容）；payload 为原始 JSON。 */
data class StreamEvent(val type: String, val payload: JSONObject?)

private val client = OkHttpClient.Builder()
    // 参照物(WebView fetch) 发出的传输级头；原生端补齐以逐字节对齐
    .addInterceptor { chain ->
        val req = chain.request().newBuilder()
            .header("Origin", "https://appassets.androidplatform.net")
            .header("Referer", "https://appassets.androidplatform.net/")
            .apply { if (chain.request().header("Accept") == null) header("Accept", "*/*") }
            .build()
        chain.proceed(req)
    }
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS) // SSE：读超时交给应用层
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

// Non-stream one-shot calls must not hang on the 0s read timeout.
private val oneShotClient = client.newBuilder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()

private fun readErrorDetail(body: String, fallback: String): Pair<String, String?> {
    if (body.isEmpty()) return fallback to null
    return try {
        val err = JSONObject(body).optJSONObject("error")
        val msg = err?.optString("message")?.takeIf { it.isNotEmpty() } ?: body
        val code = err?.optString("code")?.takeIf { it.isNotEmpty() }
            ?: err?.optString("type")?.takeIf { it.isNotEmpty() }
        msg to code
    } catch (e: Exception) {
        body to null
    }
}

/** GET /models 校验 API Key（不产生费用） */
suspend fun verifyApiKey(apiKey: String): Unit = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("${ApiOrigin.get()}/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
    try {
        oneShotClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val (msg, code) = readErrorDetail(body, response.message)
                throw ApiError(ApiErrorKind.HTTP, msg, response.code, code)
            }
        }
    } catch (e: ApiError) {
        throw e
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
}

/**
 * GET /user/balance：查询当前 Key 所属账号余额。余额是账号级信息，固定请求官方服务地址，
 * 不跟随高级设置里的自定义端点。（ref fetchAccountBalance）
 */
suspend fun fetchAccountBalance(apiKey: String): AccountBalance = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("${ApiOrigin.get()}/user/balance")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
    try {
        oneShotClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val (msg, code) = readErrorDetail(body, response.message)
                throw ApiError(ApiErrorKind.HTTP, msg, response.code, code)
            }
            parseAccountBalance(body)
        }
    } catch (e: ApiError) {
        throw e
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
}

/** POST /anthropic/v1/files — 返回 file_id。purpose 须在 file 之前，图片不传（ref uploadFile）。 */
suspend fun uploadFile(
    apiKey: String,
    file: java.io.File,
    name: String,
    mimeType: String,
    purpose: String?,
): String = withContext(Dispatchers.IO) {
    val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
    if (purpose != null) {
        multipart.addFormDataPart("purpose", purpose)
    }
    multipart.addFormDataPart(
        "file", name,
        file.asRequestBody((mimeType.ifEmpty { "application/octet-stream" }).toMediaType()),
    )
    val request = Request.Builder()
        .url("${ApiOrigin.get()}/anthropic/v1/files")
        .header("x-api-key", apiKey)
        // ref anthropicHeaders(beta=true)：Files API 必带
        .header("anthropic-beta", FILES_BETA)
        .post(multipart.build())
        .build()
    val response = try {
        client.newCall(request).execute()
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            val (msg, code) = readErrorDetail(body, it.message)
            throw ApiError(ApiErrorKind.HTTP, msg, it.code, code)
        }
        try {
            JSONObject(body).getString("id")
        } catch (e: Exception) {
            throw ApiError(ApiErrorKind.PROTOCOL, "missing file id")
        }
    }
}

/** DELETE /anthropic/v1/files/{file_id}（用于分段总结 PDF 临时文件 Best-Effort 清理）。 */
suspend fun deleteFile(apiKey: String, fileId: String): Unit = withContext(Dispatchers.IO) {
    val shortClient = oneShotClient.newBuilder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()
    val encoded = java.net.URLEncoder.encode(fileId, "UTF-8").replace("+", "%20")
    val request = Request.Builder()
        .url("${ApiOrigin.get()}/anthropic/v1/files/$encoded")
        .header("x-api-key", apiKey)
        .header("anthropic-beta", FILES_BETA)
        .delete()
        .build()
    try {
        shortClient.newCall(request).execute().use { /* best-effort cleanup */ }
    } catch (_: Exception) { }
}

/** Anthropic 兼容 Files API 及 file_id 引用图片时必传（ref FILES_BETA）。 */
const val FILES_BETA = "files-api-2025-04-14"

/** ref hasFile：messages 里出现 image/document block 时 messages 请求也带 beta 头。 */
private fun hasFile(body: JSONObject): Boolean {
    val messages = body.optJSONArray("messages") ?: return false
    for (i in 0 until messages.length()) {
        val content = messages.optJSONObject(i)?.optJSONArray("content") ?: continue
        for (j in 0 until content.length()) {
            val type = content.optJSONObject(j)?.optString("type")
            if (type == "image" || type == "document") return true
        }
    }
    return false
}

/** WHATWG SSE 行解析（参照 ref sse.ts）。internal 以便单元测试直接喂样本。 */
internal class SseReader(private val reader: java.io.BufferedReader) {
    private var data = StringBuilder()
    private var hasData = false
    private var eventType = ""

    /** 读下一个完整 SSE 事件；流结束返回 null。参照 sse.ts 的 WHATWG 解析。 */
    fun nextEvent(): Pair<String, String>? {
        while (true) {
            val line = reader.readLine() ?: run {
                // 未以空行结尾的残留事件按规范丢弃
                return null
            }
            if (line.isEmpty()) {
                if (hasData) {
                    val event = (eventType.ifEmpty { "message" }) to data.toString()
                    data = StringBuilder(); hasData = false; eventType = ""
                    return event
                }
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
                "event" -> eventType = value
                "id" -> {}
            }
        }
    }
}

class StreamSession(val call: okhttp3.Call, reader: java.io.BufferedReader) {
    private val sse = SseReader(reader)
    fun nextEvent(): Pair<String, String>? = sse.nextEvent()

    fun close() {
        call.cancel()
    }
}

/**
 * POST /anthropic/v1/messages（stream: true），返回可逐个读事件的 StreamSession；调用方负责 close()。
 */
suspend fun openMessageStreamRaw(
    apiKey: String,
    bodyJson: String,
    hasFiles: Boolean,
): StreamSession = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("${ApiOrigin.get()}/anthropic/v1/messages")
        .header("x-api-key", apiKey)
        .apply { if (hasFiles) header("anthropic-beta", FILES_BETA) }
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .post(bodyJson.toByteArray().toRequestBody("application/json".toMediaType()))
        .build()
    val call = client.newCall(request)
    val response = try {
        call.execute()
    } catch (e: java.io.IOException) {
        throw ApiError(ApiErrorKind.NETWORK, e.message ?: e.toString())
    }
    if (!response.isSuccessful) {
        val bodyText = response.body?.string().orEmpty()
        response.close()
        val (msg, code) = readErrorDetail(bodyText, response.message)
        throw ApiError(ApiErrorKind.HTTP, msg, response.code, code)
    }
    val contentType = response.header("Content-Type") ?: ""
    val responseBody = response.body
    if (responseBody == null || !contentType.contains("text/event-stream")) {
        val text = responseBody?.string().orEmpty()
        response.close()
        val (msg, code) = readErrorDetail(text, "")
        throw ApiError(ApiErrorKind.PROTOCOL, msg, response.code, code)
    }
    StreamSession(call, responseBody.charStream().buffered())
}

suspend fun openMessageStream(apiKey: String, body: JSONObject): StreamSession =
    JSONObject(body.toString()).run {
        put("stream", true)
        openMessageStreamRaw(apiKey, toString(), hasFile(this))
    }
