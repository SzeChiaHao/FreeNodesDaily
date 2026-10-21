package com.szech.walls.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szech.walls.R
import com.szech.walls.core.Log
import com.szech.walls.net.Fetcher
import com.szech.walls.pipeline.SourceSpec
import com.szech.walls.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import com.google.android.material.R as MR

/**
 * 订阅源体检。对应 Windows 版的 `daily_auto.py --doctor`。
 *
 * 免费节点源的存活是流动的：上周还能用的一批，这周可能全 404。整轮跑一次要十几分钟，
 * 而「这个源还活着吗」完全可以单独查——并发探一遍，几十秒出结果，
 * 然后直接把死源从清单里去掉，不用再去猜「是不是脚本坏了」。
 *
 * 探测只做到「能下载 + 能解析出节点」为止，不测节点本身的速度：那是阶段 2 的事。
 *
 * 界面上照 NodeDetailSheet 的样子做：底部弹层 + 拖拽条 + 图标标题 + 卡片列表 +
 * 两颗药丸按钮。整套零件都取自 WallsUi，不另起一套视觉。
 */
object SourceDoctor {

    private const val PROBE_TIMEOUT_MS = 8000
    private const val CONCURRENCY = 8

    data class Row(
        val raw: String,
        val url: String,
        val ok: Boolean,
        val count: Int,
        val millis: Long,
        val mirror: String,
        val error: String
    ) {
        val usable: Boolean get() = ok && count > 0
        val short: String get() = url.removePrefix("https://raw.githubusercontent.com/")
    }

    fun run(ctx: Context, owner: LifecycleOwner, onReplaced: () -> Unit = {}) {
        val specs = SourceSpec.parseAll(Prefs.sources)
        if (specs.isEmpty()) {
            WallsCmd.toast(ctx, "订阅源是空的")
            return
        }
        WallsCmd.toast(ctx, "正在体检 ${specs.size} 个源…")
        owner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { probeAll(specs) }
            for (r in rows) {
                Log.add(
                    "体检 " + (if (r.usable) "[${r.count}]" else if (r.ok) "[空]" else "[失败]") +
                        " ${r.url}" + (if (r.mirror.isNotEmpty()) "（${r.mirror}）" else "")
                )
            }
            show(ctx, rows, onReplaced)
        }
    }

    private suspend fun probeAll(specs: List<SourceSpec>): List<Row> = coroutineScope {
        val sem = Semaphore(CONCURRENCY)
        specs.map { spec ->
            async { sem.withPermit { probe(spec) } }
        }.awaitAll()
    }

    private fun probe(spec: SourceSpec): Row {
        val t0 = System.currentTimeMillis()
        if (spec.needsProxy) {
            return Row(spec.raw, spec.url, false, 0, 0, "", "需要本地代理，手机上跳过")
        }
        val fetcher = Fetcher(timeoutMs = PROBE_TIMEOUT_MS, mirrorTimeoutMs = 5000)
        val pair = try {
            fetcher.getWithMirror(spec.url, PROBE_TIMEOUT_MS)
        } catch (e: Exception) {
            return Row(spec.raw, spec.url, false, 0, System.currentTimeMillis() - t0, "", e.message ?: "失败")
        }
        val text = pair.first
        val mirror = pair.second ?: ""
        val cost = System.currentTimeMillis() - t0
        if (text == null) {
            return Row(spec.raw, spec.url, false, 0, cost, "", "下载失败")
        }
        val count = try {
            val (uris, nodes) = fetcher.parseContent(text)
            uris.size + nodes.size
        } catch (e: Exception) {
            0
        }
        return Row(spec.raw, spec.url, true, count, cost, mirror, if (count == 0) "解析不出节点" else "")
    }

    // ------------------------------------------------------------ 界面

    private fun show(ctx: Context, rows: List<Row>, onReplaced: () -> Unit) {
        val ui = WallsUi(ctx)
        val usable = rows.filter { it.usable }.sortedByDescending { it.count }
        val empty = rows.filter { it.ok && it.count == 0 }
        val dead = rows.filter { !it.ok }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // 顶部拖拽条（与节点详情一致）
        col.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(24)
            )
            addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = ui.dp(2).toFloat()
                    setColor(ui.attr(MR.attr.colorOnSurfaceVariant))
                    alpha = 100
                }
                layoutParams = FrameLayout.LayoutParams(ui.dp(32), ui.dp(4), Gravity.CENTER)
            })
        })

        // 头：图标底座 + 标题 + 一行结果摘要
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12))
        }
        head.addView(ui.iconBox(R.drawable.ic_monitoring, boxDp = 40, iconDp = 22))
        head.addView(ui.space(ui.dp(14)))
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.label("订阅源体检", R.style.TextAppearance_Walls_PageTitle))
            addView(ui.label(
                "可用 ${usable.size} 个（${usable.sumOf { it.count }} 个节点）· " +
                    "空 ${empty.size} · 不可用 ${dead.size}",
                R.style.TextAppearance_Walls_Caption
            ))
        })
        col.addView(head)

        // 列表卡片：整卡列表项 + 缩进分隔线，跟节点页、设置页同一套
        val listWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
        }
        val card = ui.card(listWrap, 0, "")
        val ordered = usable + empty + dead
        ordered.forEachIndexed { i, r ->
            if (i > 0) ui.divider(card)
            val value = when {
                r.usable -> "${r.count} 个 · ${String.format(Locale.ROOT, "%.1fs", r.millis / 1000.0)}"
                r.ok -> "空"
                else -> "不可用"
            }
            card.addView(
                ui.tile(
                    icon = if (r.usable) R.drawable.ic_check_circle else R.drawable.ic_error,
                    title = r.short,
                    sub = if (r.mirror.isNotEmpty()) "镜像 ${r.mirror}"
                    else r.error.ifEmpty { null },
                    value = value,
                    chevron = false
                )
            )
        }
        col.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(320)
            )
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(listWrap)
        })

        // 两颗药丸按钮（主操作 + 次操作），与详情弹层同构
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(20))
        }
        val report = buildString {
            appendLine(
                "# 源体检（" +
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                        .format(java.util.Date()) + "）"
            )
            appendLine()
            appendLine("- 可用 ${usable.size}、空 ${empty.size}、不可用 ${dead.size}")
            appendLine()
            for (r in usable) appendLine("${r.count}\t${r.mirror}\t${r.url}")
            if (dead.isNotEmpty()) {
                appendLine()
                appendLine("# 不可用，先注释掉")
                for (r in dead) appendLine("# ${r.url}")
            }
        }
        val copyBtn = ui.button(
            "复制报告", R.style.ThemeOverlay_Walls_Button_Tonal, R.drawable.ic_content_copy
        ) { WallsCmd.copy(ctx, report, "已复制体检报告") }
        copyBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(6) }
        actions.addView(copyBtn)

        val applyBtn = ui.button(
            "用可用的替换", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_check_circle
        ) {
            if (usable.isEmpty()) {
                WallsCmd.toast(ctx, "这次没有一个源可用，先别替换")
            } else {
                Prefs.sources = usable.map { it.raw }
                Log.add("体检后清单已替换：${usable.size} 个源")
                WallsCmd.toast(ctx, "已替换为 ${usable.size} 个可用源")
                onReplaced()
            }
        }
        applyBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = ui.dp(6) }
        actions.addView(applyBtn)
        col.addView(actions)

        BottomSheetDialog(ctx).apply {
            setContentView(col)
            show()
        }
    }
}
