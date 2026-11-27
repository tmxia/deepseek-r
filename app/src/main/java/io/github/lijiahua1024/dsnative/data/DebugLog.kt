package io.github.lijiahua1024.dsnative.data

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 调试日志环形缓冲。仅在调试模式开启时由各核心模块写入，避免常态运行产生额外开销。
 * 日志只驻留内存（上限 [MAX] 条），由设置页「调试日志」查看/导出到系统下载目录。
 */
object DebugLog {
    private const val MAX = 2000
    private val entries = ConcurrentLinkedQueue<String>()

    /** 由 SettingsStore.debugMode 同步；关闭时所有写入直接丢弃。 */
    @Volatile
    var enabled: Boolean = false

    fun log(tag: String, message: String) {
        if (!enabled) return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        entries.add("[$stamp][$tag] $message")
        while (entries.size > MAX) entries.poll()
    }

    fun exception(tag: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        log(tag, "EXCEPTION ${throwable.javaClass.name}: ${throwable.message}\n$sw")
    }

    fun all(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
