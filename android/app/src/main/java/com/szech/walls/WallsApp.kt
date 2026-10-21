package com.szech.walls

import android.app.Application
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WallsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Repo.init(this)
        Runner.schedule(this, Prefs.autoDaily)
        installCrashHandler()
    }

    /**
     * 崩溃时把现场写进「下载/Walls/」，用户直接把文件发过来就行，不用接电脑抓 logcat。
     * 写完仍然交回系统默认处理（该弹「应用已停止」还是弹），不改行为。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                val nl = System.lineSeparator()
                val ver = try {
                    val pi = packageManager.getPackageInfo(packageName, 0)
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else 0L
                    pi.versionName + " (vc" + code + ")"
                } catch (e: Exception) {
                    "?"
                }
                val text = buildString {
                    append("时间：").append(stamp).append(nl)
                    append("线程：").append(thread.name).append(nl)
                    append("版本：").append(ver).append(nl)
                    append("机型：").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE)
                        .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(')')
                        .append(nl).append(nl)
                    append(error.stackTraceToString())
                    append(nl).append(nl).append("---- 运行日志 ----").append(nl)
                    append(com.szech.walls.core.Log.snapshot().joinToString(nl))
                }
                val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                    .format(Date()) + ".txt"
                com.szech.walls.ui.WallsCmd.writeToDownloads(this, name, text, "text/plain")
            } catch (e: Exception) {
                // 写不出来就算了，别在崩溃处理里再崩一次
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var instance: WallsApp
            private set
    }
}
