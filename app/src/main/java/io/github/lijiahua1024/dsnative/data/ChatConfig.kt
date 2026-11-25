package io.github.lijiahua1024.dsnative.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 对齐 Web 参照 src/services/api/chatConfig.ts。
 * 自定义聊天接口配置：模型名、端点、协议格式、端点专属 Key。端点留空时走官方服务。
 */
@Serializable
enum class ChatApiFormat {
    @SerialName("messages") MESSAGES,
    @SerialName("chat") CHAT,
    @SerialName("responses") RESPONSES,
}

@Serializable
data class ChatConfig(
    val model: String = "",
    val endpoint: String = "",
    val format: ChatApiFormat = ChatApiFormat.MESSAGES,
    val apiKey: String = "",
)

/** 各格式默认请求路径（ref CHAT_API_PATHS）。 */
val CHAT_API_PATHS: Map<ChatApiFormat, String> = mapOf(
    ChatApiFormat.MESSAGES to "/messages",
    ChatApiFormat.CHAT to "/chat/completions",
    ChatApiFormat.RESPONSES to "/responses",
)

private val chatJson = Json { ignoreUnknownKeys = true }

/** 协议格式展示名（ref CHAT_API_FORMATS）。 */
fun chatFormatLabel(format: ChatApiFormat): String = when (format) {
    ChatApiFormat.MESSAGES -> "Anthropic Messages"
    ChatApiFormat.CHAT -> "OpenAI Chat Completions"
    ChatApiFormat.RESPONSES -> "OpenAI Responses"
}

/**
 * 校验并规范化基础地址（ref parseChatEndpoint）：
 * 必须是 http/https，不含 username:password 登录信息，不含 URL 片段（#）。
 */
fun parseChatEndpoint(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) return null
    return try {
        val url = java.net.URL(trimmed)
        val protocolOk = url.protocol == "https" || url.protocol == "http"
        val noCredentials = url.userInfo.isNullOrEmpty()
        val noFragment = url.ref.isNullOrEmpty()
        if (protocolOk && noCredentials && noFragment) url.toString() else null
    } catch (_: Exception) {
        null
    }
}

/** 去掉已拼接的协议后缀，返回基础地址（ref chatBaseUrl）；无效返回 null。 */
fun chatBaseUrl(value: String, format: ChatApiFormat): String? {
    val endpoint = parseChatEndpoint(value) ?: return null
    return try {
        val url = java.net.URL(endpoint)
        val path = url.path.trimEnd('/')
        val suffix = CHAT_API_PATHS[format] ?: return null
        val basePath = if (path.endsWith(suffix)) path.dropLast(suffix.length) else path
        "${url.protocol}://${url.authority}$basePath"
    } catch (_: Exception) {
        null
    }
}

/**
 * 规范化保存用完整端点（ref resolveChatEndpoint）：
 * - 输入为空串 → 返回 ""（使用官方服务）；
 * - 非法地址 → 返回 null；
 * - 格式未变且基础地址未变 → 沿用旧完整地址（兼容网关自定义路由）；
 * - messages 格式基础路径缺 /v1 时补齐，再拼协议后缀。
 */
fun resolveChatEndpoint(
    value: String,
    format: ChatApiFormat,
    previous: ChatConfig = ChatConfig(),
): String? {
    if (value.trim().isEmpty()) return ""
    val base = chatBaseUrl(value, format) ?: return null
    if (format == previous.format && base == chatBaseUrl(previous.endpoint, format)) {
        return previous.endpoint
    }
    return try {
        val url = java.net.URL(base)
        val path = url.path.trimEnd('/')
        val suffix = CHAT_API_PATHS[format] ?: return null
        val version = if (format == ChatApiFormat.MESSAGES && !path.endsWith("/v1")) "/v1" else ""
        "${url.protocol}://${url.authority}$path$version$suffix"
    } catch (_: Exception) {
        null
    }
}

/** 从持久化字符串读取配置（ref readChatConfig）；空/损坏回退默认。 */
fun readChatConfig(stored: String?): ChatConfig {
    if (stored.isNullOrBlank()) return ChatConfig()
    return try {
        chatJson.decodeFromString(ChatConfig.serializer(), stored)
    } catch (_: Exception) {
        ChatConfig()
    }
}

/** 配置是否有非默认内容需要持久化（ref setChatConfig 判定）。 */
fun chatConfigNonDefault(config: ChatConfig): Boolean =
    config.model.isNotBlank() || config.endpoint.isNotBlank() ||
        config.format != ChatApiFormat.MESSAGES
