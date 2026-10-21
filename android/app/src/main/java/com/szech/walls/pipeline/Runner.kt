package com.szech.walls.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.szech.walls.core.Log
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.store.RunStatus
import com.szech.walls.ui.WallsCmd
import com.szech.walls.work.DailyWorker
import com.szech.walls.WallsApp
import java.util.concurrent.TimeUnit

/** 把偏好设置变成一次流水线配置，并负责跑完落盘、按设置自动生成配置文件。 */
object Runner {

    fun config(isCancelled: () -> Boolean = { false }): PipelineConfig = PipelineConfig(
        sources = Prefs.sources + Prefs.discovered,
        maxNodes = Prefs.maxNodes,
        speedTop = Prefs.speedTop,
        speedBytes = Prefs.speedBytesKb * 1024,
        minSpeedMbps = Prefs.minSpeedTenths / 10.0,
        keepTop = Prefs.keepTop,
        measurableOnly = Prefs.measurableOnly,
        labelSpeed = Prefs.labelSpeed,
        quick = Prefs.quick,
        isCancelled = isCancelled
    )

    suspend fun runNow(
        onLog: (String) -> Unit = { Log.add(it) },
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): PipelineResult {
        Repo.status = RunStatus(
            lastRunAt = Repo.status.lastRunAt,
            running = true,
            message = "运行中…"
        )
        val t0 = System.currentTimeMillis()
        val res = Pipeline.run(config(isCancelled), onLog, onProgress)
        val st = RunStatus(
            lastRunAt = System.currentTimeMillis(),
            candidates = res.candidates,
            alive = res.alive,
            measured = res.measured,
            sourcesOk = res.sourcesOk,
            sourcesFail = res.sourcesFail,
            kept = res.nodes.size,
            message = when {
                res.cancelled -> "已取消（保留上次结果）"
                res.nodes.isEmpty() -> "本次没有可用节点，保留上次结果"
                else -> "完成，用时 ${(System.currentTimeMillis() - t0) / 1000} 秒"
            },
            running = false
        )
        if (res.nodes.isNotEmpty()) {
            Repo.setResult(res.nodes, st)
            // 抓取完成后自动落盘：产物是文件，不靠后台服务活着
            if (Prefs.autoSave && !res.cancelled) {
                onProgress("生成文件", 0, 1)
                try {
                    val n = WallsCmd.exportToDownloads(WallsApp.instance, Repo.nodes, notify = false)
                    if (n > 0) onLog("已生成 $n 个配置文件到 下载/Walls/")
                    onProgress("生成文件", 1, 1)
                } catch (e: Exception) {
                    onLog("生成配置文件失败：${e.message}")
                }
            }
        } else {
            Repo.setResult(Repo.nodes, st)
        }
        return res
    }

    // ------------------------------------------------------------ 定时任务

    private const val WORK_NAME = "walls-daily"

    fun schedule(ctx: Context, enabled: Boolean) {
        // 定时任务是「锦上添花」：WorkManager 初始化不了（Direct Boot、被系统限制等情况）
        // 也不该把 App 一起拖掉，所以整段包一层。
        try {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        } catch (e: Exception) {
            Log.add("定时更新没能安排上：${e.message}")
        }
    }
}
