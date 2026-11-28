package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 折叠/展开后的视口跟随。
 *
 * 两种状态口径（真机确认）：
 *  - **展开**：锚定折叠按钮。按钮位于气泡/代码块内容下方，近似整条消息 item 的底部。展开后
 *    若按钮（item 底部）将被推出视口底部，上收列表把按钮留在视口底部留 margin 处，使按钮上方
 *    新展开的内容填满视口；上收量不把消息顶部推出上界。条目高于视口（超一屏）时按钮本就不可见，
 *    退化为顶部对齐视口顶。
 *  - **收起**：锚定消息顶部不动，列表不主动滚动，下方消息由 LazyColumn 默认重排自然补位。
 *
 * 时机：必须在新高度布局落地后再读几何。调用方在折叠态翻转后的 effect 内触发，本函数再跨两帧
 * 等待重组/测量/放置完成，避免在旧几何或中间态上推量造成跳动。
 */
suspend fun LazyListState.ensureFoldButtonVisible(index: Int, marginPx: Float) {
    // 跨两帧：第一帧让重组提交，第二帧让测量/放置落地。
    withFrameNanos { }
    withFrameNanos { }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewBottom = info.viewportEndOffset.toFloat()
    val top = item.offset.toFloat()
    val bottom = top + item.size
    val height = item.size.toFloat()

    // 条目超一屏：按钮在视口外下方，锚按钮不可达，退化为顶部对齐视口顶，从头阅读。
    if (height > viewBottom) {
        scrollToItem(index, 0)
        return
    }
    // 底部（按钮）越出视口底：上收到底部留 margin；上收量不把顶部推出上界。
    if (bottom > viewBottom - marginPx) {
        var delta = bottom - (viewBottom - marginPx)
        val maxUp = top - marginPx
        if (delta > maxUp) delta = maxUp
        if (abs(delta) > 0.5f) dispatchRawDelta(delta)
    }
}
