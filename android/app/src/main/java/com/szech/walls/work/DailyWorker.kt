package com.szech.walls.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.szech.walls.core.Log
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo

/** 每天自动跑一次「抓取 → 测速 → 排序 → 生成订阅」。 */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        Repo.init(applicationContext)
        // WorkManager 的周期任务在首次入队时可能立刻跑一次；如果用户刚手动点过
        // 「开始更新」，这里再跑一遍会白烧 4~5 分钟流量和电量（实测踩到过：
        // 手动跑完 2 分钟后后台任务又完整跑了一遍）。
        val last = Repo.status.lastRunAt
        if (last > 0 && System.currentTimeMillis() - last < 60 * 60 * 1000L) {
            Log.add("（后台任务）距上次更新不足 1 小时，跳过")
            return Result.success()
        }
        if (Repo.status.running) {
            Log.add("（后台任务）已有任务在运行，跳过")
            return Result.success()
        }
        Log.add("（后台任务）开始每日更新")
        return try {
            val res = Runner.runNow()
            Log.add("（后台任务）每日更新结束：入选 ${res.nodes.size} 个节点")
            if (res.nodes.isEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.add("（后台任务）出错：${e.message}")
            Result.retry()
        }
    }
}
