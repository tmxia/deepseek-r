package io.github.lijiahua1024.dsnative.data

import android.view.Choreographer
import java.util.Locale

/**
 * 聊天区帧耗时/卡顿采样。仅在调试日志开启时汇总写入 [DebugLog]，常态下不产生日志与额外开销。
 *
 * 指标口径（单采样窗口）：
 * - frames：窗口内 vsync 帧数
 * - avg/max：平均/单帧耗时 ms
 * - jank>24：超过 24ms（掉一帧）的帧数；jank>32：超过 32ms（掉两帧）的帧数
 */
object PerfMonitor {
    private const val JANK_24_MS = 24.0
    private const val JANK_32_MS = 32.0

    @Volatile
    private var active = false
    private var lastFrameNanos = 0L

    private var frames = 0
    private var totalMs = 0.0
    private var maxMs = 0.0
    private var jank24 = 0
    private var jank32 = 0

    private val callback: Choreographer.FrameCallback = Choreographer.FrameCallback { frameNanos ->
        if (lastFrameNanos != 0L) {
            val ms = (frameNanos - lastFrameNanos) / 1_000_000.0
            if (ms in 0.1..500.0) {
                frames++
                totalMs += ms
                if (ms > maxMs) maxMs = ms
                if (ms > JANK_24_MS) jank24++
                if (ms > JANK_32_MS) jank32++
            }
        }
        lastFrameNanos = frameNanos
        if (active) Choreographer.getInstance().postFrameCallback(callback)
    }

    fun start() {
        if (active) return
        active = true
        reset()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        active = false
        lastFrameNanos = 0L
    }

    /** 把当前窗口汇总写入调试日志并清零；无帧样本或日志关闭时直接忽略。 */
    fun snapshot(tag: String) {
        if (!DebugLog.enabled || frames == 0) {
            reset()
            return
        }
        val avg = totalMs / frames
        DebugLog.log(
            tag,
            "frames=$frames avg=${fmt(avg)}ms max=${fmt(maxMs)}ms " +
                "jank>24=$jank24 jank>32=$jank32",
        )
        reset()
    }

    private fun reset() {
        frames = 0
        totalMs = 0.0
        maxMs = 0.0
        jank24 = 0
        jank32 = 0
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
}
