package com.szech.walls.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.szech.walls.MainActivity
import com.szech.walls.R

/**
 * 抓取 / 测速期间的前台服务。
 *
 * 之前这条流水线只挂在一个进程内的协程上：屏幕一黑，CPU 睡了，socket 就卡住；
 * 切到后台，系统随时可以把进程回收。表现就是「必须亮屏、必须待在前台」。
 *
 * 现在跑的时候起一个前台服务，同时持一把 PARTIAL_WAKE_LOCK：
 *   - 前台服务把进程钉住，切出去、锁屏都不会被杀；
 *   - wake lock 让 CPU 别睡，网速测试才测得准。
 * 跑完就停服务、放锁，不留常驻。
 */
class RunService : Service() {

    companion object {
        private const val CHANNEL_ID = "walls_run"
        private const val NOTIFICATION_ID = 41

        /** 跑一次最多多久，防止异常时锁不释放把电吃光 */
        private const val MAX_WAKELOCK_MS = 30 * 60 * 1000L

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RunService::class.java))
            } catch (e: Exception) {
                // 系统不给起（极端省电模式之类）也不该把任务一起拖死，退化成前台跑
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RunService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }

        @Volatile
        private var lastAt = 0L
        @Volatile
        private var lastText = ""

        /** 更新通知里的进度文案。太频繁的更新直接跳过，免得把系统通知栏刷爆。 */
        fun update(ctx: Context, text: String, percent: Int) {
            val now = System.currentTimeMillis()
            if (text == lastText && now - lastAt < 500) return
            if (now - lastAt < 250) return
            lastAt = now
            lastText = text
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, build(ctx, text, percent, indeterminate = percent < 0))
            } catch (e: Exception) {
                // 通知没权限时忽略
            }
        }

        private fun build(
            ctx: Context,
            text: String,
            percent: Int,
            indeterminate: Boolean
        ): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("节点管家")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
            if (indeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, percent.coerceIn(0, 100), false)
            }
            return builder.build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "抓取与测速", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "更新节点时显示进度，跑完自动消失"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, build(this, "正在抓取节点…", -1, true))
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walls:pipeline").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKELOCK_MS)
            }
        }
        // 不要 START_STICKY：进程被系统杀掉后没必要自己复活跑一半的任务
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
