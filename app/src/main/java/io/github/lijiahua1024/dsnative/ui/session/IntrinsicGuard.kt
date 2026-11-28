package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints

/**
 * 固有测量（intrinsic）高度防御封顶。
 *
 * 背景：Compose 打包 [Constraints] 时，宽、高各自所需位宽之和不能超过 31 位。固有测量阶段
 * 宽度哨兵为 32767（落入 16 位档），若子项上报的固有高度也 ≥32768（同样 16 位档），
 * 位宽合计 32 位就会抛出
 * `IllegalArgumentException: Can't represent a width of 32767 and height of ... in Constraints`。
 *
 * 这里只钳制固有测量向父级上报的高度；常规测量/布局高度完全不受影响，不裁剪、不压缩真实内容。
 * 封顶值取 32000px，低于 32767 哨兵（15 位档），与宽度哨兵位宽合计 31 位，可正常编码。
 */
private const val INTRINSIC_HEIGHT_CAP_PX = 32000

private val IntrinsicHeightGuardPolicy = object : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurables.firstOrNull()?.measure(constraints)
        return layout(placeable?.width ?: 0, placeable?.height ?: 0) {
            placeable?.placeRelative(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.firstOrNull()
        ?.minIntrinsicHeight(width)
        ?.coerceAtMost(INTRINSIC_HEIGHT_CAP_PX) ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables.firstOrNull()
        ?.maxIntrinsicHeight(width)
        ?.coerceAtMost(INTRINSIC_HEIGHT_CAP_PX) ?: 0
}

/**
 * 把内容包一层固有高度封顶：父级做 intrinsic 测量时拿到的高度不会超过 [INTRINSIC_HEIGHT_CAP_PX]，
 * 正常渲染尺寸保持原样。用于引用块 / 表格行 / 思考块这类带 IntrinsicSize 的纵向容器。
 */
@Composable
internal fun IntrinsicHeightGuard(content: @Composable () -> Unit) {
    Layout(content = content, measurePolicy = IntrinsicHeightGuardPolicy)
}
