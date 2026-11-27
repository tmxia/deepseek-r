package io.github.lijiahua1024.dsnative.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 官方账号余额（ref services/api/balance.ts）。
 * 注意：该接口只提供账号级余额，不提供单个 Key 的实时 token 消耗。
 */
data class AccountBalance(
    val isAvailable: Boolean,
    /** 币种：CNY / USD；缺失时为 null */
    val currency: String?,
    /** 总可用余额（字符串金额，保留两位小数） */
    val totalBalance: String,
    /** 未过期的赠金余额 */
    val grantedBalance: String,
    /** 充值余额 */
    val toppedUpBalance: String,
)

private val balanceJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? {
    val el = get(key) ?: return null
    if (el is JsonNull) return null
    val primitive = el as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

private fun asAmount(value: String?): String =
    if (value != null && value.trim().isNotEmpty()) value.trim() else "0.00"

/**
 * 解析 GET /user/balance 响应。返回结构为 balance_infos 数组，首项即本账号余额；
 * 同时兼容旧版平铺字段。纯函数，便于单元测试直接喂样本。
 */
fun parseAccountBalance(body: String): AccountBalance {
    val root = balanceJson.parseToJsonElement(body).jsonObject
    val infos = root["balance_infos"] as? JsonArray
    val info = (infos?.firstOrNull() as? JsonObject) ?: root
    val availableEl = root["is_available"]
    val isAvailable = availableEl == null || availableEl is JsonNull ||
        (availableEl as? JsonPrimitive)?.content != "false"
    return AccountBalance(
        isAvailable = isAvailable,
        currency = info.text("currency"),
        totalBalance = asAmount(info.text("total_balance")),
        grantedBalance = asAmount(info.text("granted_balance")),
        toppedUpBalance = asAmount(info.text("topped_up_balance")),
    )
}

/** 币种符号：人民币 ¥、美元 $；其他币种返回空串，由调用方补币种代码。 */
fun balanceCurrencySymbol(currency: String?): String = when (currency) {
    "CNY" -> "¥"
    "USD" -> "$"
    else -> ""
}

/**
 * 进程内最近一次成功查询的账号余额快照（按 API Key 隔离）。
 * 重新进入账号页时先用快照填充，后台静默刷新，避免先显示「查询中」占位再跳变造成的跳闪。
 */
@Volatile
private var cachedKey: String? = null
@Volatile
private var cachedBalance: AccountBalance? = null

internal fun lastCachedBalance(apiKey: String?): AccountBalance? =
    if (!apiKey.isNullOrEmpty() && cachedKey == apiKey) cachedBalance else null

internal fun cacheAccountBalance(apiKey: String?, balance: AccountBalance?) {
    cachedKey = apiKey
    cachedBalance = balance
}
