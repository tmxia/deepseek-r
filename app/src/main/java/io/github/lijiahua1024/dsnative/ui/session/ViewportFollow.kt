package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 消息 item 因折叠/展开改变高度后的视口跟随校正（锚定顶部）。
 *
 * 背景：折叠态只改 Text maxLines，气泡/代码块高度随之变化，外层 LazyColumn 不会自动补偿滚动
 * 位置。早期实现按「越界量」`dispatchRawDelta`，且在高度突变、列表自动重排的同一帧读取几何，
 * 相对位移被叠加到自动重排上，表现为高度切换瞬间过推、跳动/闪一下。
 *
 * 口径（对齐真机确认）：
 *  - 锚定顶部：展开超长（超一屏）内容时顶部对齐视口，从头阅读；
 *  - 折叠后保持顶部位置不动，不主动上推/下放，避免与列表自动重排叠加出跳动；
 *  - 仅在条目确实越界时做一次稳定校正：顶部越出上界 / 整条落在视口下方，用 `scrollToItem`
 *    锚定回视口；条目矮于视口且底部越界时小幅上收；条目高于视口则保持顶部不动。
 *
 * 必须在新布局落地后调用（onSizeChanged 信号或跨帧之后），不要在点击回调的旧几何上直接调用。
 */
suspend fun LazyListState.followItemTopVisible(index: Int, marginPx: Float) {
    // 跨一帧确保重组/测量/放置已落地，读到的是新高度几何。
    withFrameNanos { }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewBottom = info.viewportEndOffset.toFloat()
    val top = item.offset.toFloat()
    val bottom = top + item.size
    val height = item.size.toFloat()

    when {
        // 顶部越出上界：锚定回 margin 处（从头读）。
        top < marginPx -> scrollToItem(index, marginPx.toInt())
        // 整条已落到视口下方：锚定回顶部。
        top >= viewBottom -> scrollToItem(index, 0)
        // 条目矮于视口且底部越界：小幅上收到底部留 margin；高于视口（超一屏）保持顶部。
        height <= viewBottom && bottom > viewBottom - marginPx -> {
            val delta = bottom - (viewBottom - marginPx)
            if (abs(delta) > 0.5f) dispatchRawDelta(delta)
        }
        // 其余：顶部合规且在视口内，保持不动，锚顶不跳。
    }
}
