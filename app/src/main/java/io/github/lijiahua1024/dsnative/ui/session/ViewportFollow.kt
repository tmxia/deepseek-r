package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import io.github.lijiahua1024.dsnative.data.DebugLog

/**
 * 折叠/收起后的视口跟随。
 *
 * 口径（真机确认）：
 *  - **展开**：不做视口跟随。新增内容向下撑开，视口保持用户当前位置，不主动上收或跳转；
 *    展开前记录的列表锚点仅供收起回滚使用。
 *  - **收起**：锚定「前展开位置」。展开前记录列表锚点（首项 index + 滚动偏移），收起布局落地后
 *    用 `scrollToItem` 把视口精确回滚到该锚点，拉起视口、消除展开上滚留下的位移与留白，下方消息
 *    随之补位。不按条目当前几何猜 rawDelta，避免非首项顶部默认不动时偏差为 0、或条目滚出可视区
 *    后补偿被跳过。
 *
 * 时机：必须在新高度布局落地后再读几何。调用方在折叠态翻转后的 effect 内触发，本函数再跨两帧
 * 等待重组/测量/放置完成，避免在旧几何或中间态上推量造成跳动。
 */

/**
 * 收起后把列表锚点恢复到展开前记录的首项 [anchorIndex] / [anchorOffset]，精确回滚视口位置。
 * 用 scrollToItem 直接定位锚点，不依赖条目当前几何，不受重排基准漂移影响。
 */
suspend fun LazyListState.restorePreExpandAnchor(anchorIndex: Int, anchorOffset: Int) {
    withFrameNanos { }
    withFrameNanos { }
    DebugLog.log("foldAnchor", "restore target=($anchorIndex,$anchorOffset) " +
        "before=(${firstVisibleItemIndex},${firstVisibleItemScrollOffset})")
    scrollToItem(anchorIndex, anchorOffset)
    withFrameNanos { }
    DebugLog.log("foldAnchor", "restore after=(${firstVisibleItemIndex},${firstVisibleItemScrollOffset})")
}
