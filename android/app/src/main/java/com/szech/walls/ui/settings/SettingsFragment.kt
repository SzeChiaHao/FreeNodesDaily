package com.szech.walls.ui.settings

import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.szech.walls.R
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment
import com.google.android.material.R as MR

/** 设置：分四组，别再排成一条长龙 */
class SettingsFragment : WallsPageFragment() {

    private data class Item(
        val icon: Int,
        val title: String,
        val showValue: Boolean = true,
        val action: (() -> Unit)? = null
    )

    /** 标题 → 右侧取值那格，改完设置直接改这几个 TextView，不用遍历视图树 */
    private val valueViews = LinkedHashMap<String, TextView>()

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_tune, "设置", "共 16 项")
        valueViews.clear()

        val groups = listOf(
            "数量与速度" to listOf(
                Item(R.drawable.ic_dns, "候选节点上限") {
                    number("进入测速的候选节点上限", Prefs.maxNodes, 20, 3000) { Prefs.maxNodes = it }
                },
                Item(R.drawable.ic_bookmark, "保留节点数量") {
                    number("最终保留多少个节点", Prefs.keepTop, 5, 500) { Prefs.keepTop = it }
                },
                Item(R.drawable.ic_speed, "测速节点数量") {
                    number("做下载测速的节点个数", Prefs.speedTop, 0, 300) { Prefs.speedTop = it }
                },
                Item(R.drawable.ic_data_usage, "每个节点测速流量(KB)") {
                    number(
                        "每个节点下载多少流量（KB）。40 个节点 × 512KB ≈ 20MB 流量",
                        Prefs.speedBytesKb, 64, 4096
                    ) { Prefs.speedBytesKb = it }
                },
                Item(R.drawable.ic_filter_alt, "最低合格速度(0.1MB/s)") {
                    number("低于该速度的节点被过滤（单位 0.1 MB/s）", Prefs.minSpeedTenths, 0, 100) {
                        Prefs.minSpeedTenths = it
                    }
                }
            ),
            "过滤与命名" to listOf(
                Item(R.drawable.ic_verified, "只保留可实测协议的节点") {
                    bool("只保留可实测协议的节点") { Prefs.measurableOnly = it }
                },
                Item(R.drawable.ic_label, "节点名加上速度前缀") {
                    bool("节点名加上速度前缀") { Prefs.labelSpeed = it }
                }
            ),
            "自动化" to listOf(
                Item(R.drawable.ic_download, "抓取后自动保存") {
                    bool("抓取后自动保存") { Prefs.autoSave = it }
                },
                Item(R.drawable.ic_delete, "生成后删除旧文件") {
                    bool("生成后删除旧文件") { Prefs.cleanOldExports = it }
                },
                Item(R.drawable.ic_schedule, "后台运行权限") {
                    com.szech.walls.ui.PowerHelp.ask(requireContext(), force = true) { refreshValues() }
                },
                Item(R.drawable.ic_schedule, "每天自动更新") {
                    bool("每天自动更新") {
                        Prefs.autoDaily = it
                        Runner.schedule(requireContext(), it)
                    }
                },
                Item(R.drawable.ic_edit, "编辑订阅源") { sources() }
            ),
            "关于" to listOf(
                Item(R.drawable.ic_description, "导出诊断日志", showValue = false) {
                    WallsCmd.exportDiagnostics(requireContext())
                },
                Item(R.drawable.ic_system_update, "检查更新") {
                    WallsCmd.checkUpdate(requireContext(), this, manual = true)
                },
                Item(R.drawable.ic_link, "GitHub 仓库", showValue = false) {
                    WallsCmd.openUrl(requireContext(), WallsCmd.GITHUB_URL)
                },
                Item(R.drawable.ic_description, "开源许可", showValue = false) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("开源许可")
                        .setMessage(
                            "节点管家（Walls）以 AGPL-3.0 许可开源。\n\n" +
                                "界面图标来自 Google Material Symbols（Apache License 2.0）。"
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            )
        )

        for ((groupTitle, items) in groups) {
            ui.groupTitle(col, groupTitle)
            val card = ui.card(col, 0, "")
            items.forEachIndexed { i, item ->
                if (i > 0) ui.divider(card)
                val value = if (item.showValue) settingValue(item.title) else ""
                var valueView: TextView? = null
                val row = ui.tile(
                    icon = item.icon,
                    title = item.title,
                    value = value,
                    chevron = true,
                    valueSink = { valueView = it },
                    onClick = { item.action?.invoke(); refreshValues() }
                )
                card.addView(row)
                if (item.showValue) valueView?.let { valueViews[item.title] = it }
            }
        }
        refreshValues()
    }

    override fun refresh() {
        refreshValues()
    }

    private fun refreshValues() {
        for ((title, view) in valueViews) {
            val v = settingValue(title)
            if (v.isNotEmpty()) view.text = v
        }
    }

    private fun settingValue(label: String): String = when (label) {
        "候选节点上限" -> Prefs.maxNodes.toString()
        "保留节点数量" -> Prefs.keepTop.toString()
        "测速节点数量" -> Prefs.speedTop.toString()
        "每个节点测速流量(KB)" -> "${Prefs.speedBytesKb} KB"
        "最低合格速度(0.1MB/s)" -> "${Prefs.minSpeedTenths / 10.0} MB/s"
        "只保留可实测协议的节点" -> if (Prefs.measurableOnly) "开" else "关"
        "节点名加上速度前缀" -> if (Prefs.labelSpeed) "开" else "关"
        "抓取后自动保存" -> if (Prefs.autoSave) "开" else "关"
        "生成后删除旧文件" -> if (Prefs.cleanOldExports) "开" else "关"
        "后台运行权限" -> com.szech.walls.ui.PowerHelp.statusText(requireContext())
        "每天自动更新" -> if (Prefs.autoDaily) "开" else "关"
        "编辑订阅源" -> "${Prefs.sources.size} 个"
        "检查更新" -> "v" + WallsCmd.currentVersion(requireContext()).first
        else -> ""
    }

    // ------------------------------------------------------------ 弹窗

    private fun bool(label: String, set: (Boolean) -> Unit) {
        val cur = when (label) {
            "只保留可实测协议的节点" -> Prefs.measurableOnly
            "节点名加上速度前缀" -> Prefs.labelSpeed
            "抓取后自动保存" -> Prefs.autoSave
            "生成后删除旧文件" -> Prefs.cleanOldExports
            "每天自动更新" -> Prefs.autoDaily
            else -> false
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setSingleChoiceItems(arrayOf("开", "关"), if (cur) 0 else 1) { d, which ->
                set(which == 0)
                d.dismiss()
                refreshValues()
            }
            .show()
    }

    private fun number(label: String, cur: Int, min: Int, max: Int, set: (Int) -> Unit) {
        val input = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cur.toString())
            setSelection(text?.length ?: 0)
        }
        val wrap = TextInputLayout(
            ContextThemeWrapper(requireContext(), MR.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            hint = "$min ~ $max"
            addView(input)
        }
        val box = FrameLayout(requireContext()).apply {
            setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0)
            addView(wrap)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < min || v > max) {
                    WallsCmd.toast(requireContext(), "请输入 $min ~ $max 之间的整数")
                } else {
                    set(v)
                }
                refreshValues()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sources() {
        WallsCmd.showSourcesDialog(requireContext()) { refreshValues() }
    }
}
