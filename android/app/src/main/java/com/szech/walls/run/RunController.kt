package com.szech.walls.run

import android.os.Handler
import android.os.Looper
import com.szech.walls.WallsApp
import com.szech.walls.core.Log
import com.szech.walls.pipeline.PipelineResult
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 流水线运行状态。
 *
 * 之前这个 Job 挂在 Activity 的 lifecycleScope 上，切页面没事，但转屏或后台被回收就断；
 * 拆成多页之后 Activity 允许重建（深色模式切换也要重建），所以运行状态必须搬出来独立存活。
 *
 * 进度这块后来重写过一次：原来只有一个「done/total」的进度条，而 total 在每个阶段都会变
 * （20 个源 → 600 个候选 → 80 个测速名额），于是表现成「卡在 20/20，然后突然跳到另一段」。
 * 用户根本不知道还剩几步。现在改成四段固定环节 + 加权总进度：
 *
 *   1 抓取订阅源   12%
 *   2 延迟筛选     28%
 *   3 真实测速     55%
 *   4 生成文件      5%
 *
 * 每段各自有 done/total 和剩余时间估算，总进度只往前走不回头。
 */
object RunController {

    fun interface Listener {
        fun onChanged()
    }

    /** 四个固定环节，顺序和权重都写死，界面按这个画 */
    val PHASE_TITLES = listOf("抓取订阅源", "延迟筛选", "真实测速", "生成文件")
    private val PHASE_WEIGHTS = intArrayOf(12, 28, 55, 5)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = mutableSetOf<Listener>()
    private var job: Job? = null
    private var ticker: Job? = null

    // running / cancelling 会被别的线程读（UI 主线程读 running 画面、工作线程读 cancelling 判断是否停），
    // 而 start()/stop() 里的赋值在 @Synchronized 里 —— 读的那边不在锁里，所以必须 @Volatile，
    // 否则可能看不到最新值（最坏情况：点了停止，流水线仍然看到 false，一路跑到底）。
    @Volatile
    var running: Boolean = false
        private set
    @Volatile
    var cancelling: Boolean = false
        private set

    // 下面这些进度字段都是「工作线程写、UI 主线程读」，写的那边在 @Synchronized 块里，
    // 读的那边（percent / stageEtaMs 这两个 getter）不在锁里，所以也得 @Volatile。
    // 否则界面可能推到旧的 stageDone，看起来像「卡住了」。

    /** 当前处在第几段（0..3），-1 表示没在跑 */
    @Volatile
    var stageIndex: Int = -1
        private set
    @Volatile
    var stageDone: Int = 0
        private set
    @Volatile
    var stageTotal: Int = 0
        private set

    /** 细粒度的统计，界面上当「陪跑信息」用 */
    @Volatile
    var sourceDone: Int = 0
        private set
    @Volatile
    var sourceTotal: Int = 0
        private set
    @Volatile
    var candidates: Int = 0
        private set
    @Volatile
    var alive: Int = 0
        private set
    @Volatile
    var measured: Int = 0
        private set

    @Volatile
    var startedAt: Long = 0L
        private set
    @Volatile
    private var stageStartedAt: Long = 0L
    @Volatile
    private var stageStartDone: Int = 0

    @Volatile
    var lastResult: PipelineResult? = null
        private set

    /**
     * 上一次跑完之后留下的一份快照。
     *
     * 原来一跑完 stageIndex 就被打回 -1，四段进度立刻全部变回「等待」——
     * 而这正是用户最想看统计的那一秒。现在把每段的 done/total 存一份，界面上把
     * 它渲染成「上一次运行」的明细。
     */
    class PhaseSnapshot(val done: Int, val total: Int, val skipped: Boolean = false) {
        override fun toString(): String = if (skipped) "跳过" else "$done/$total"
    }

    @Volatile
    var lastPhases: List<PhaseSnapshot> = emptyList()
        private set

    /** 上一次运行的用时（毫秒），没跑过为 0 */
    @Volatile
    var lastRunElapsedMs: Long = 0L
        private set

    /** 老字段，留个别处还在读 */
    val stage: String get() = PHASE_TITLES.getOrElse(stageIndex) { "" }
    val done: Int get() = stageDone
    val total: Int get() = stageTotal

    /** 加权总进度 0..100；没在跑时返回 0 */
    val percent: Int
        get() {
            if (stageIndex < 0) return 0
            var acc = 0
            for (i in 0 until stageIndex) acc += PHASE_WEIGHTS[i]
            val w = PHASE_WEIGHTS[stageIndex]
            val frac = if (stageTotal > 0) {
                stageDone.toDouble() / stageTotal
            } else {
                0.0
            }
            acc += (w * frac).toInt()
            return acc.coerceIn(0, 100)
        }

    val elapsedMs: Long
        get() = if (startedAt <= 0) 0 else (System.currentTimeMillis() - startedAt)

    /** 当前这一段还剩多久（毫秒），估不出来返回 -1 */
    val stageEtaMs: Long
        get() {
            if (stageIndex < 0 || stageTotal <= 0 || stageDone <= 0) return -1
            val spent = System.currentTimeMillis() - stageStartedAt
            if (spent < 1500) return -1
            val rate = (stageDone - stageStartDone).toDouble() / spent
            if (rate <= 0.0) return -1
            val left = (stageTotal - stageDone).coerceAtLeast(0)
            return (left / rate).toLong()
        }

    /** 整体还剩多久：当前段估算 + 后面几段按已知经验补一个粗估 */
    val totalEtaMs: Long
        get() {
            val cur = stageEtaMs
            if (cur < 0) return -1
            var rest = cur + 800L // 收尾
            for (i in stageIndex + 1 until PHASE_WEIGHTS.size) {
                rest += guessPhaseMs(i)
            }
            return rest
        }

    /** 经验值：后一段大概多久（只用于「约剩」这种模糊提示） */
    private fun guessPhaseMs(i: Int): Long = when (i) {
        1 -> 6_000L
        2 -> 45_000L
        3 -> 2_000L
        else -> 3_000L
    }

    @Synchronized
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // 通知一律回到主线程再发。
    //
    // 流水线跑在后台线程，原来直接在那边回调监听器，监听器里又会去改 TextView / 进度条。
    // 平时看着没事，一旦同时在切页面（视图树正在重建），就会撞在一起：
    // 轻则控件状态错乱，重则整页画不出来只剩空白。现在统一 post 到主线程，
    // 并且合并同一帧内的多次通知，避免测速阶段一秒几十次刷新把主线程压死。
    private val main = Handler(Looper.getMainLooper())
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun notifyChanged() {
        if (!pending.compareAndSet(false, true)) return
        main.post {
            pending.set(false)
            for (l in listeners.toList()) {
                try {
                    l.onChanged()
                } catch (e: Exception) {
                    // 单个页面出错不该影响别的页面
                }
            }
        }
    }

    /** 开始一次更新。quick = 只测 TCP 延迟。已经在跑就直接忽略。 */
    @Synchronized
    fun start(quick: Boolean, onFinished: (PipelineResult) -> Unit = {}) {
        if (running) return
        running = true
        cancelling = false
        stageIndex = -1
        stageDone = 0
        stageTotal = 0
        sourceDone = 0
        sourceTotal = 0
        candidates = 0
        alive = 0
        measured = 0
        startedAt = System.currentTimeMillis()
        stageStartedAt = startedAt
        stageStartDone = 0
        Prefs.quick = quick
        Log.add(if (quick) "=== 开始快速更新（只测 TCP 延迟）===" else "=== 开始完整更新（抓取 + 实测测速）===")

        // 起前台服务 + 持唤醒锁：锁屏、切后台都不断，跑完立刻停
        try {
            RunService.start(WallsApp.instance)
        } catch (e: Exception) {
            Log.add("前台服务没起来（继续在前台跑）")
        }
        notifyChanged()

        // 心跳：每半秒刷一次已经跑了多久，让「盯屏」也有反馈
        ticker = scope.launch {
            while (running) {
                delay(500)
                notifyChanged()
            }
        }

        job = scope.launch {
            val res: PipelineResult = try {
                Runner.runNow(
                    onLog = { Log.add(it) },
                    onProgress = { s, d, t ->
                        synchronized(this@RunController) {
                            val idx = indexOfStage(s)
                            if (idx != stageIndex) {
                                stageIndex = idx
                                stageStartedAt = System.currentTimeMillis()
                                stageStartDone = 0
                            }
                            stageDone = d
                            stageTotal = t
                            when (idx) {
                                0 -> {
                                    sourceDone = d
                                    sourceTotal = t
                                }
                                1 -> candidates = t
                                2 -> alive = t
                                3 -> measured = d
                            }
                        }
                        notifyChanged()
                        val pct = percent
                        RunService.update(
                            WallsApp.instance,
                            "${PHASE_TITLES.getOrElse(stageIndex) { "" }} $stageDone/$stageTotal",
                            pct
                        )
                    },
                    isCancelled = { cancelling }
                )
            } catch (e: Exception) {
                Log.add("出错：${e.message}")
                PipelineResult(emptyList(), 0, 0, 0, 0, 0, 0)
            }
            synchronized(this@RunController) {
                running = false
                job = null
                lastResult = res
                // 先把本轮的统计固化下来，再清当前状态，界面才不会看到「一片空白」
                lastRunElapsedMs = System.currentTimeMillis() - startedAt
                lastPhases = listOf(
                    PhaseSnapshot(sourceDone, sourceTotal),
                    PhaseSnapshot(candidates, candidates),
                    // quick 模式根本不跑测速段，标成「跳过」比写 0/0 诚实
                    if (Prefs.quick) PhaseSnapshot(0, 0, skipped = true)
                    else PhaseSnapshot(alive, alive),
                    if (Prefs.quick) PhaseSnapshot(0, 0, skipped = true)
                    else PhaseSnapshot(measured, alive)
                )
                stageIndex = -1
                stageDone = 0
                stageTotal = 0
            }
            when {
                res.cancelled -> Log.add("=== 已停止 ===")
                res.nodes.isEmpty() -> Log.add("=== 结束：没有可用节点 ===")
                else -> Log.add("=== 结束：入选 ${res.nodes.size} 个节点 ===")
            }
            notifyChanged()
            ticker?.cancel()
            try {
                RunService.stop(WallsApp.instance)
            } catch (e: Exception) {
                // ignore
            }
            onFinished(res)
        }
    }

    private fun indexOfStage(s: String): Int = when {
        s.contains("抓取") -> 0
        s.contains("延迟") -> 1
        s.contains("实测") || s.contains("测速") -> 2
        s.contains("生成") || s.contains("保存") || s.contains("写") -> 3
        else -> -1
    }

    /**
     * 协作式停止：不直接杀协程，只置标志位。
     * 流水线在每个节点、每个阶段之间检查它，所以一般一两秒内就会干净收尾，
     * 不会留下没关掉的 socket。
     */
    @Synchronized
    fun stop(): Boolean {
        if (!running) return false
        cancelling = true
        notifyChanged()
        Log.add("已请求停止，等待正在测的节点收尾…")
        return true
    }
}
