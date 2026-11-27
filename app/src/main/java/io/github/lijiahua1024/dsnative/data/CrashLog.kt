package io.github.lijiahua1024.dsnative.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兜底捕获未处理异常并把完整栈追加到应用内部目录，便于真机闪退无 adb 时回传确诊。
 * 不吞掉异常：记录后继续交给系统默认处理器，崩溃/结束进程的原有行为不变。
 */
object CrashLog {
    private const val DIR = "crash"
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DebugLog.exception("crash", throwable)
                val dir = File(context.filesDir, DIR).apply { mkdirs() }
                val file = File(dir, FILE)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val entry = "===== $stamp | thread=${thread.name} =====\n$sw\n\n"
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.writeText(entry)
                } else {
                    file.appendText(entry)
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 读取持久化崩溃记录全文；无记录或读取失败时返回空串。 */
    fun readAll(): String = runCatching {
        val base = appContext?.filesDir ?: return ""
        val file = File(base, DIR).resolve(FILE)
        if (file.exists()) file.readText().trim() else ""
    }.getOrDefault("")
}
