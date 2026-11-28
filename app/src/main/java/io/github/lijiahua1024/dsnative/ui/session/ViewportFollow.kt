package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 折叠/展开后的视口跟随。
 *
 * 口径（真机确认）：
 *  - **展开**：锚定折叠按钮。按钮近似整条消息 item 底部。展开后若按钮（item 底部）将被推出视口
 *    底部，上收列表把按钮留在视口底部留 margin；上收量不把顶部推出上界；条目超一屏时按钮本就
 *    不可见，退化为顶部对齐视口顶。
 *  - **收起**：锚定「前展开位置」。展开前记录消息顶部在视口的位置，收起布局落地后把消息顶部
 *    校正回该位置，拉起视口、消除展开上滚留下的位移与留白，下方消息随之补位。
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

/**
 * 收起后把消息 item 顶部校正回展开前记录的 [targetTop]（相对视口顶的像素偏移）。
 * delta = 当前 top - 目标 top：正值表示顶部偏下（上方留白），上拉列表；负值表示偏上，下放。
 */
suspend fun LazyListState.anchorItemTopTo(index: Int, targetTop: Int) {
    withFrameNanos { }
    withFrameNanos { }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = (item.offset - targetTop).toFloat()
    if (abs(delta) > 0.5f) dispatchRawDelta(delta)
}
