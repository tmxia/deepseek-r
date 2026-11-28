package io.github.lijiahua1024.dsnative.data

import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * 分段耗时聚合（仅调试模式生效）。按节点累计次数、累计耗时、最大单次耗时，
 * 由会话页 5 秒快照一次性写入 [DebugLog] 后清零，用于定位长会话冻结时间花在哪个节点。
 *
 * 未开启调试日志时 [record] 直接返回，常态运行不产生计时与额外开销。
 */
object FrameTrace {
    private class Acc(var count: Int = 0, var totalNanos: Long = 0L, var maxNanos: Long = 0L)

    private val acc = ConcurrentHashMap<String, Acc>()

    /** 记录一个节点的单次耗时；[extra] 为附加上下文（长度/分支等），单独写一条明细日志。 */
    fun record(node: String, nanos: Long, extra: String = "") {
        if (!DebugLog.enabled) return
        val a = acc.getOrPut(node) { Acc() }
        synchronized(a) {
            a.count++
            a.totalNanos += nanos
            if (nanos > a.maxNanos) a.maxNanos = nanos
        }
        if (extra.isNotEmpty()) DebugLog.log("trace", "$node $extra")
    }

    /** 输出当前各节点聚合（n / avg / max ms）并清零；无样本或日志关闭时忽略。 */
    fun snapshot() {
        if (!DebugLog.enabled) return
        if (acc.isEmpty()) return
        val sb = StringBuilder()
        for ((node, a) in acc) {
            val snapshot = synchronized(a) {
                if (a.count == 0) null else Triple(a.count, a.totalNanos, a.maxNanos)
            } ?: continue
            val (count, total, max) = snapshot
            val avg = total / count / 1_000_000.0
            val maxMs = max / 1_000_000.0
            sb.append(node).append(" n=").append(count)
                .append(" avg=").append(String.format(Locale.US, "%.1f", avg))
                .append("ms max=").append(String.format(Locale.US, "%.1f", maxMs)).append("ms; ")
            synchronized(a) {
                a.count = 0
                a.totalNanos = 0
                a.maxNanos = 0
            }
        }
        if (sb.isNotEmpty()) DebugLog.log("trace", sb.toString())
    }
}
