package com.szech.walls.ui.overview

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.szech.walls.R
import com.szech.walls.core.Log
import com.szech.walls.run.RunController
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

/** 概览：运行状态（四段进度）、订阅文件、运行日志 */
class OverviewFragment : WallsPageFragment() {

    /** 一段的执行情况，界面上四行 */
    private class PhaseRow(
        val badge: TextView,
        val title: TextView,
        val state: TextView,
        val bar: LinearProgressIndicator
    )

    private var statusView: TextView? = null
    private var promptView: TextView? = null
    private var overallLabel: TextView? = null
    private var timeLabel: TextView? = null
    private var overallBar: LinearProgressIndicator? = null
    private val phaseRows = ArrayList<PhaseRow>()

    private var logView: TextView? = null
    private var logScroll: ScrollView? = null
    private var lastFileView: TextView? = null

    /** 日志区最后一次被手指碰的时间；3 秒内不自动跟随 */
    private var logTouchedAt = 0L
    /** 已经上屏的日志文本，一样就不重复 setText（减少 layout 与选区跳动） */
    private var logRendered: String? = null

    private val runListener = RunController.Listener { renderRun() }
    private val logListener: (String) -> Unit = { view?.post { renderLog() } }

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_monitoring, "概览", "免费节点抓取 · 真实测速 · 生成配置文件")

        if (isExpanded()) {
            // 宽屏：页头横跨两栏，左栏窄、右栏宽，两栏最后一张卡片等高（底边对齐）
            val grid = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val left = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val right = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f)
                    .apply { marginStart = ui.dp(20) }
            }
            grid.addView(left)
            grid.addView(right)
            col.addView(grid)
            buildStatusCard(left)
            buildFilesCard(right)
            buildLogCard(right)
            // 两栏等高：measure 完之后把高的那个尺寸发给矮的那个
            grid.post {
                val h = maxOf(left.measuredHeight, right.measuredHeight)
                if (h > 0 && left.measuredHeight != right.measuredHeight) {
                    left.layoutParams = left.layoutParams.apply { height = h }
                    right.layoutParams = right.layoutParams.apply { height = h }
                    grid.requestLayout()
                }
            }
        } else {
            buildStatusCard(col)
            buildFilesCard(col)
            buildLogCard(col)
        }
    }

    private fun isExpanded(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 840

    private fun buildStatusCard(parent: LinearLayout) {
        val c1 = ui.card(parent, R.drawable.ic_monitoring, "运行状态")
        val s1 = ui.section(c1)
        statusView = ui.label("尚未运行过", R.style.TextAppearance_Walls_TileLabel)
        s1.addView(statusView)

        // ---------- 四段进度 ----------
        overallLabel = ui.label("", R.style.TextAppearance_Walls_Caption)
        s1.addView(overallLabel)
        overallBar = ui.progressBar()
        s1.addView(overallBar)
        timeLabel = ui.label("", R.style.TextAppearance_Walls_Caption)
        s1.addView(timeLabel)

        for (i in RunController.PHASE_TITLES.indices) {
            val box = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ui.dp(10), 0, 0)
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = ui.badge(
                "${i + 1}",
                ui.attr(MR.attr.colorSurfaceContainerHighest),
                ui.attr(MR.attr.colorOnSurfaceVariant),
                boxDp = 26
            )
            row.addView(badge)
            row.addView(ui.space(ui.dp(12)))
            val title = ui.label(RunController.PHASE_TITLES[i], R.style.TextAppearance_Walls_TileLabel)
            row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val state = ui.label("等待", R.style.TextAppearance_Walls_Caption)
            row.addView(state)
            box.addView(row)

            val bar = ui.progressBar()
            bar.setPadding(ui.dp(38), 0, 0, 0)
            box.addView(bar)
            s1.addView(box)
            phaseRows.add(PhaseRow(badge, title, state, bar))
        }

        promptView = ui.label(
            "点「开始更新」会依次走完这四步：抓取订阅源 → 延迟筛选 → 真实测速 → 生成文件。" +
                "头一回大约一两分钟，时间基本花在测速上。",
            R.style.TextAppearance_Walls_Caption
        ).apply { setPadding(0, ui.dp(12), 0, 0) }
        s1.addView(promptView)

        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(16), 0, 0)
        }
        val btnStart = ui.button("开始更新", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_play_arrow) {
            startRun(false)
        }
        btnStart.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(6) }
        row1.addView(btnStart)
        val btnQuick = ui.button("快速更新", R.style.ThemeOverlay_Walls_Button_Tonal, R.drawable.ic_bolt) {
            startRun(true)
        }
        btnQuick.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = ui.dp(6) }
        row1.addView(btnQuick)
        s1.addView(row1)

        val btnStop = ui.button("停止", R.style.ThemeOverlay_Walls_Button_Outlined, R.drawable.ic_stop_circle) {
            if (!RunController.stop()) WallsCmd.toast(requireContext(), "当前没有任务在跑")
        }
        btnStop.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(8) }
        s1.addView(btnStop)
    }

    private fun buildFilesCard(parent: LinearLayout) {
        // ---------- 订阅文件：生成 → 去客户端导入 ----------
        val c2 = ui.card(parent, R.drawable.ic_description, "订阅文件")
        val s2 = ui.section(c2)
        lastFileView = ui.label("还没生成过", R.style.TextAppearance_Walls_TileLabel)
        s2.addView(lastFileView)
        s2.addView(
            ui.label(
                "写进「下载/Walls/」，文件名带时间戳。到客户端里走「从文件导入」，挑对应的那一个。",
                R.style.TextAppearance_Walls_Caption
            )
        )

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(16), 0, 0)
        }
        val btnAll = ui.button(
            "全部生成", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_download
        ) {
            if (Repo.nodes.isEmpty()) {
                WallsCmd.toast(requireContext(), "先跑一次更新，有节点才能生成")
            } else {
                WallsCmd.exportToDownloads(requireContext(), Repo.nodes)
                renderRun()
            }
        }
        btnAll.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(6) }
        row.addView(btnAll)

        val btnOpen = ui.button(
            "打开目录", R.style.ThemeOverlay_Walls_Button_Tonal, R.drawable.ic_folder_open
        ) { WallsCmd.openDownloadsFolder(requireContext()) }
        btnOpen.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = ui.dp(6) }
        row.addView(btnOpen)
        s2.addView(row)

        ui.divider(c2)
        c2.addView(
            ui.tile(
                icon = R.drawable.ic_download,
                title = "按客户端生成",
                sub = "7 种格式，各拿各的",
                chevron = true,
                onClick = { findNavController().navigate(R.id.exportFragment) }
            )
        )
    }

    private fun buildLogCard(parent: LinearLayout) {
        val c3 = ui.card(parent, R.drawable.ic_terminal, "运行日志")
        val s3 = ui.section(c3, bottomDp = 12)

        // 日志不再整段摊开：固定高度、内部可滚，像网页里的代码块
        logView = ui.label("", R.style.TextAppearance_Walls_Mono).apply {
            setTextIsSelectable(true)
            // 不抢焦点：selectable 的 TextView 拿到焦点后，文本一更新就会
            // 把「光标/选区」带回视野，表现成日志自己上下跳。
            isFocusable = false
            isFocusableInTouchMode = false
        }
        logScroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(220)
            )
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(logView)
            // 这一块是「页面里再套一个滚动区」：手指落在日志上时，必须让父级 ScrollView
            // 别来抢手势，否则整页跟着滚、日志自己一动不动。
            // 另外记一笔「刚被摸过」，自动跟随在几秒内都让路，
            // 否则用户正拖着看历史，日志一更新就被拽走，看起来就是自己在抖。
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        logTouchedAt = System.currentTimeMillis()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        logTouchedAt = System.currentTimeMillis()
                        false
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        logTouchedAt = System.currentTimeMillis()
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                    else -> false
                }
            }
        }
        s3.addView(logScroll)

        val copyRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, ui.dp(10), 0, 0)
        }
        copyRow.addView(
            ui.button("复制全部", R.style.ThemeOverlay_Walls_Button_Text, R.drawable.ic_content_copy) {
                WallsCmd.copy(requireContext(), Log.snapshot().joinToString("\n"), "已复制运行日志")
            }
        )
        s3.addView(copyRow)
    }

    override fun onResume() {
        super.onResume()
        RunController.addListener(runListener)
        Log.addListener(logListener)
        renderRun()
        renderLog()
    }

    override fun onPause() {
        super.onPause()
        RunController.removeListener(runListener)
        Log.removeListener(logListener)
    }

    override fun refresh() {
        renderRun()
        renderLog()
    }

    private fun startRun(quick: Boolean) {
        if (RunController.running) {
            WallsCmd.toast(requireContext(), "正在运行中，请稍候")
            return
        }
        RunController.start(quick) { res ->
            val ctx = context ?: return@start
            ctx.mainExecutor.execute {
                val msg = when {
                    res.cancelled -> "已停止，保留上次结果"
                    res.nodes.isEmpty() -> "没有抓到可用节点，保留上次结果"
                    else -> "完成，共 ${res.nodes.size} 个节点"
                }
                WallsCmd.toast(ctx, msg)
            }
        }
    }

    private fun fmtTime(ts: Long): String =
        if (ts <= 0) "从未" else SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
        ).format(Date(ts))

    private fun fmtDur(ms: Long): String {
        if (ms < 0) return "…"
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 60) String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        else "0:%02d".format(s)
    }

    private fun renderRun() {
        val s = Repo.status
        val ss = Repo.nodes.count { it.type == "ss" }
        statusView?.text = buildString {
            append("上次更新：").append(fmtTime(s.lastRunAt)).append('\n')
            append("当前可用节点：").append(Repo.nodes.size).append(" 个（Shadowsocks ").append(ss).append(" 个）\n")
            append("候选 ").append(s.candidates)
                .append(" · 存活 ").append(s.alive)
                .append(" · 实测 ").append(s.measured)
                .append(" · 源 ").append(s.sourcesOk).append('/').append(s.sourcesOk + s.sourcesFail)
            if (s.message.isNotEmpty()) append('\n').append(s.message)
        }

        lastFileView?.text = fileStamp()
        val running = RunController.running
        promptView?.visibility = if (running) View.GONE else View.VISIBLE

        // 没在跑的时候，如果上一轮留下过快照，就把那四段原样摆出来（打勾 + 计数），
        // 而不是全部抹成「等待」—— 刚跑完那一秒用户最想看的就是这些数字。
        val snap = if (running) emptyList() else RunController.lastPhases
        val showOverall = running || snap.isNotEmpty()
        overallLabel?.visibility = if (showOverall) View.VISIBLE else View.GONE
        overallBar?.visibility = if (showOverall) View.VISIBLE else View.GONE
        timeLabel?.visibility = if (showOverall) View.VISIBLE else View.GONE

        val success = ui.clr(R.color.md_success)
        val onVariant = ui.attr(MR.attr.colorOnSurfaceVariant)

        val cur = RunController.stageIndex
        if (!running) {
            if (snap.isEmpty()) {
                for (row in phaseRows) {
                    row.badge.text = (phaseRows.indexOf(row) + 1).toString()
                    row.state.text = "等待"
                    row.state.setTextColor(onVariant)
                    row.bar.visibility = View.GONE
                }
                overallBar?.setProgressCompat(0, false)
            } else {
                overallLabel?.text = "上次运行：完成"
                timeLabel?.text = "用时 " + fmtDur(RunController.lastRunElapsedMs)
                overallBar?.setProgressCompat(100, false)
                phaseRows.forEachIndexed { i, row ->
                    val p = snap.getOrNull(i)
                    when {
                        p == null -> {
                            row.badge.text = "${i + 1}"
                            row.badge.setTextColor(onVariant)
                            row.state.text = "等待"
                            row.state.setTextColor(onVariant)
                            row.bar.visibility = View.GONE
                        }
                        p.skipped -> {
                            row.badge.text = "—"
                            row.badge.setTextColor(onVariant)
                            row.state.text = "跳过"
                            row.state.setTextColor(onVariant)
                            row.bar.visibility = View.GONE
                        }
                        else -> {
                            row.badge.text = "✓"
                            row.badge.setTextColor(success)
                            row.state.text = if (p.total > 0) "${p.done}/${p.total}" else "完成"
                            row.state.setTextColor(success)
                            row.bar.visibility = View.VISIBLE
                            row.bar.setProgressCompat(100, false)
                        }
                    }
                }
            }
            return
        }

        val pct = RunController.percent
        overallLabel?.text = "总进度 $pct%" +
            if (RunController.cancelling) " · 正在停止" else ""
        val eta = RunController.totalEtaMs
        timeLabel?.text = "已用 " + fmtDur(RunController.elapsedMs) +
            (if (eta > 0) " · 约剩 " + fmtDur(eta) else " · 估算中")
        overallBar?.setProgressCompat(pct, true)

        val primary = ui.attr(MR.attr.colorPrimary)

        RunController.PHASE_TITLES.forEachIndexed { i, name ->
            val row = phaseRows.getOrNull(i) ?: return@forEachIndexed
            row.title.text = name
            when {
                i < cur -> {
                    row.badge.text = "✓"
                    row.badge.setTextColor(success)
                    row.state.text = "完成"
                    row.state.setTextColor(success)
                    row.bar.visibility = View.GONE
                }
                i == cur -> {
                    row.badge.text = "${i + 1}"
                    row.badge.setTextColor(primary)
                    row.state.setTextColor(primary)
                    row.state.text = when {
                        RunController.stageTotal > 0 ->
                            "${RunController.stageDone}/${RunController.stageTotal}"
                        else -> "进行中"
                    }
                    row.bar.visibility = View.VISIBLE
                    val p = if (RunController.stageTotal > 0) {
                        RunController.stageDone * 100 / RunController.stageTotal
                    } else 0
                    row.bar.setProgressCompat(p.coerceIn(0, 100), true)
                }
                else -> {
                    row.badge.text = "${i + 1}"
                    row.badge.setTextColor(onVariant)
                    row.state.text = "等待"
                    row.state.setTextColor(onVariant)
                    row.bar.visibility = View.GONE
                }
            }
        }

        // 当前阶段的「陪跑信息」：别让用户只看到一根不动的条
        if (cur == 0 && RunController.sourceTotal > 0) {
            phaseRows[0].state.text =
                "${RunController.stageDone}/${RunController.stageTotal} 个源"
        }
    }

    private fun fileStamp(): String {
        val ts = Prefs.lastExportAt
        return if (ts <= 0) "还没生成过" else "上次生成：" + SimpleDateFormat(
            "MM-dd HH:mm", Locale.getDefault()
        ).format(Date(ts)) + " · " + Repo.nodes.size + " 个节点"
    }

    private fun renderLog() {
        val v = logView ?: return
        val sv = logScroll ?: return

        var atBottom = v.height <= sv.height || sv.scrollY + sv.height >= v.height - dp8
        val text = Log.snapshot().takeLast(200).joinToString("\n").ifEmpty { "（暂无日志）" }

        if (text != logRendered) {
            logRendered = text
            v.text = text
        }

        // 自动跟随的条件（全部满足才动）：
        //   1. 本来就在底部；
        //   2. 最近 3 秒内用户没碰过日志区（手指还按着就更不抢）；
        //   3. 确实还有可滚的余量。
        // 以前这三点一条都没卡，日志一更新就 fullScroll，用户正在拖的时候
        // 两边打架，看上去就是日志自己在上下抖。
        if (System.currentTimeMillis() - logTouchedAt < 3000) atBottom = false
        if (!atBottom) return
        sv.post {
            val max = (v.height - sv.height).coerceAtLeast(0)
            if (sv.scrollY < max - dp8) sv.scrollTo(0, max)
        }
    }

    private val dp8: Int by lazy { (8 * resources.displayMetrics.density).toInt() }

    override fun onDestroyView() {
        super.onDestroyView()
        statusView = null
        promptView = null
        overallLabel = null
        timeLabel = null
        overallBar = null
        phaseRows.clear()
        logView = null
        logScroll = null
        lastFileView = null
        logRendered = null
        logTouchedAt = 0L
    }
}
