package com.szech.walls.work

import android.content.Context
import android.os.PowerManager
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
        Log.add("（后台任务）开始每日更新")

        // 这一趟多半发生在夜里、屏幕是黑的。不持锁的话 CPU 会打盹，
        // 测速大面积超时，结果比白天手动跑差一大截 —— 同一个 App 两套结果。
        // 上限 20 分钟：真出异常也不会一直攥着锁吃电。
        var lock: PowerManager.WakeLock? = null
        try {
            lock = try {
                val pm = applicationContext.getSystemService(PowerManager::class.java)
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walls:daily").apply {
                    setReferenceCounted(false)
                    acquire(MAX_LOCK_MS)
                }
            } catch (e: Exception) {
                Log.add("（后台任务）唤醒锁没拿到，继续跑：${e.message}")
                null
            }

            val res = Runner.runNow()
            Log.add("（后台任务）每日更新结束：入选 ${res.nodes.size} 个节点")
            return if (res.nodes.isEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.add("（后台任务）出错：${e.message}")
            return Result.retry()
        } finally {
            try {
                lock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private companion object {
        const val MAX_LOCK_MS = 20 * 60 * 1000L
    }
}
