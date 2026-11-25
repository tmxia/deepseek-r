package io.github.lijiahua1024.dsnative.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活对齐 Web 参照物的 `keepAlive` 桥接：本质是“电池优化白名单”开关，
 * 不起前台 Service、不挂常驻通知。
 *
 * - 读取系统是否已对本应用豁免电池优化；
 * - 开启：拉起系统“忽略电池优化”授权页；
 * - 关闭：跳转电池优化设置页，由用户手动撤销豁免。
 */
object KeepAlive {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** 拉起系统授权弹窗，请求把本应用加入电池优化白名单。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 跳转系统电池优化设置页，供用户关闭豁免。 */
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
