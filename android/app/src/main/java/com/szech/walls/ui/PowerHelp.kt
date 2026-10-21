package com.szech.walls.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szech.walls.R
import com.szech.walls.store.Prefs

/**
 * 后台运行的「通行证」。
 *
 * 前台服务能挡住大部分清理，但国产 ROM 还有两道自己的关卡：
 *   1. 系统的电池优化（标准 API，能查也能请求豁免）；
 *   2. 厂商的「自启动 / 后台运行」开关（没有标准 API，只能跳它家的设置页）。
 *
 * 这里把两件事包成一个弹窗：讲清楚为什么需要，给两个按钮直接跳过去，
 * 回来以后再查一次并给结果。用户允许了就算完事，不再反复打扰。
 */
object PowerHelp {

    /** 系统层面是否已经豁免电池优化 */
    fun isIgnoringBattery(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    /** 厂商 ROM 的名字，弹窗文案里用得上 */
    private fun vendor(): String {
        val brand = (Build.BRAND ?: "").lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") -> "小米"
            brand.contains("huawei") || brand.contains("honor") -> "华为 / 荣耀"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> "OPPO / 一加"
            brand.contains("vivo") || brand.contains("iqoo") -> "vivo"
            brand.contains("meizu") -> "魅族"
            brand.contains("samsung") -> "三星"
            else -> ""
        }
    }

    /** 弹「要不要允许后台运行」 */
    fun ask(ctx: Context, force: Boolean = false, onDone: () -> Unit = {}) {
        val ignoring = isIgnoringBattery(ctx)
        if (ignoring && !force) {
            onDone()
            return
        }
        val v = vendor()
        val msg = buildString {
            append("更新节点时要连续跑一两分钟：抓取、连每个节点、下载测速。")
            append("这期间如果系统把应用冻住或者掐掉，测速就会大面积超时，白跑一趟。\n\n")
            append("允许之后，锁屏、切到别的应用都不会中断。\n\n")
            append("① 电池优化：让系统别在后台冻结「节点管家」。")
            if (v.isNotEmpty()) {
                append("\n② 自启动 / 后台运行：").append(v).append(" 的 ROM 另有一道开关，建议一并打开。")
            }
            if (ignoring) append("\n\n电池优化：已经是允许状态。")
        }
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle("允许后台运行")
            .setMessage(msg)
            .setPositiveButton("去关电池优化") { _, _ ->
                openBatterySettings(ctx)
                onDone()
            }
            .setNeutralButton("自启动设置") { _, _ ->
                if (!openAutoStart(ctx)) {
                    WallsCmd.toast(ctx, "这台机器没有单独的自启动页面，跳过即可")
                }
                onDone()
            }
            .setNegativeButton("以后再说") { _, _ ->
                Prefs.powerAsked = true
                onDone()
            }
        builder.show()
        Prefs.powerAsked = true
    }

    /** 设置页里那一条：随时能回来看状态 */
    fun statusText(ctx: Context): String =
        if (isIgnoringBattery(ctx)) "已允许" else "未允许"

    fun openBatterySettings(ctx: Context) {
        // 优先走「直接请求豁免」，系统会弹自己的确认框；不支持就退到电池优化列表
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:" + ctx.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(direct)
            return
        } catch (e: Exception) {
            // 继续往下退
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openAppDetails(ctx)
        }
    }

    /**
     * 跳厂商的自启动 / 后台运行页面。没有统一 API，只能按包名硬试；
     * 都打不开就退到「应用详情」。
     */
    fun openAutoStart(ctx: Context): Boolean {
        val candidates = listOf(
            // 小米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // OPPO / 一加 / realme
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            // vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
        )
        for ((pkg, cls) in candidates) {
            try {
                ctx.startActivity(
                    Intent().apply {
                        component = ComponentName(pkg, cls)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                return true
            } catch (e: Exception) {
                // 试下一个
            }
        }
        openAppDetails(ctx)
        return false
    }

    private fun openAppDetails(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:" + ctx.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            WallsCmd.toast(ctx, "打不开设置页，请手动到「设置 → 应用 → 节点管家」里允许后台运行")
        }
    }
}
