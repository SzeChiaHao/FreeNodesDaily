package com.szech.walls.ui.settings

import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.szech.walls.R
import com.szech.walls.pipeline.Runner
import com.szech.walls.store.Prefs
import com.szech.walls.ui.PowerHelp
import com.szech.walls.ui.SourceDoctor
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment
import com.google.android.material.R as MR

/**
 * 设置：分四组，别再排成一条长龙。
 *
 * 每一项自己带着「怎么读、怎么写、右侧显示什么」，不再靠中文标题当字典键去反查
 * （以前改一个字就静默失效：值显示空白、开关永远从 false 开始）。
 */
class SettingsFragment : WallsPageFragment() {

    private class Item(
        val icon: Int,
        val title: String,
        /** 右侧取值那格；返回 null 表示这一项不显示取值 */
        val value: (() -> String)? = null,
        val action: () -> Unit
    )

    /** 每一项 → 它右侧的取值 TextView，改完设置直接改这几个，不用遍历视图树 */
    private val valueViews = LinkedHashMap<Item, TextView>()

    override fun build() {
        val col = page.col
        valueViews.clear()

        val groups = listOf(
            "数量与速度" to listOf(
                numberItem(R.drawable.ic_dns, "候选节点上限", 20, 3000,
                    { Prefs.maxNodes }) { Prefs.maxNodes = it },
                numberItem(R.drawable.ic_bookmark, "保留节点数量", 5, 500,
                    { Prefs.keepTop }) { Prefs.keepTop = it },
                numberItem(R.drawable.ic_speed, "测速节点数量", 0, 300,
                    { Prefs.speedTop }) { Prefs.speedTop = it },
                numberItem(R.drawable.ic_data_usage, "每个节点测速流量(KB)", 64, 4096,
                    { Prefs.speedBytesKb }, value = { "$it KB" }) { Prefs.speedBytesKb = it },
                numberItem(R.drawable.ic_filter_alt, "最低合格速度(0.1MB/s)", 0, 100,
                    { Prefs.minSpeedTenths }, value = { "${it / 10.0} MB/s" }) { Prefs.minSpeedTenths = it }
            ),
            "过滤与命名" to listOf(
                boolItem(R.drawable.ic_verified, "只保留可实测协议的节点",
                    { Prefs.measurableOnly }) { Prefs.measurableOnly = it },
                boolItem(R.drawable.ic_label, "节点名加上速度前缀",
                    { Prefs.labelSpeed }) { Prefs.labelSpeed = it },
                boolItem(R.drawable.ic_error, "导出包含未实测节点",
                    { Prefs.includeUnverified }) { Prefs.includeUnverified = it }
            ),
            "自动化" to listOf(
                boolItem(R.drawable.ic_download, "抓取后自动保存",
                    { Prefs.autoSave }) { Prefs.autoSave = it },
                boolItem(R.drawable.ic_delete, "生成后删除旧文件",
                    { Prefs.cleanOldExports }) { Prefs.cleanOldExports = it },
                boolItem(R.drawable.ic_cloud_sync, "抓取优先走镜像",
                    { Prefs.mirrorFirst }) { Prefs.mirrorFirst = it },
                Item(R.drawable.ic_schedule, "后台运行权限", { PowerHelp.statusText(requireContext()) }) {
                    PowerHelp.ask(requireContext(), force = true) { refreshValues() }
                },
                boolItem(R.drawable.ic_schedule, "每天自动更新",
                    { Prefs.autoDaily }) {
                    Prefs.autoDaily = it
                    Runner.schedule(requireContext(), it)
                },
                Item(R.drawable.ic_edit, "编辑订阅源", { "${Prefs.sources.size} 个" }) {
                    WallsCmd.showSourcesDialog(requireContext()) { refreshValues() }
                },
                Item(R.drawable.ic_monitoring, "订阅源体检") {
                    SourceDoctor.run(requireContext(), this) { refreshValues() }
                }
            ),
            "关于" to listOf(
                Item(R.drawable.ic_description, "导出诊断日志") {
                    WallsCmd.exportDiagnostics(requireContext())
                },
                Item(R.drawable.ic_system_update, "检查更新",
                    { "v" + WallsCmd.currentVersion(requireContext()).first }) {
                    WallsCmd.checkUpdate(requireContext(), this, manual = true)
                },
                Item(R.drawable.ic_link, "GitHub 仓库") {
                    WallsCmd.openUrl(requireContext(), WallsCmd.GITHUB_URL)
                },
                Item(R.drawable.ic_description, "开源许可") {
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

        val total = groups.sumOf { it.second.size }
        ui.header(col, R.drawable.ic_tune, "设置", "共 $total 项")

        for ((groupTitle, items) in groups) {
            ui.groupTitle(col, groupTitle)
            val card = ui.card(col, 0, "")
            items.forEachIndexed { i, item ->
                if (i > 0) ui.divider(card)
                var valueView: TextView? = null
                card.addView(
                    ui.tile(
                        icon = item.icon,
                        title = item.title,
                        value = item.value?.invoke() ?: "",
                        chevron = true,
                        valueSink = { valueView = it },
                        onClick = {
                            item.action()
                            refreshValues()
                        }
                    )
                )
                if (item.value != null) valueView?.let { valueViews[item] = it }
            }
        }
        refreshValues()
    }

    override fun refresh() {
        refreshValues()
    }

    private fun refreshValues() {
        for ((item, view) in valueViews) {
            val v = item.value?.invoke() ?: continue
            if (v.isNotEmpty()) view.text = v
        }
    }

    // ------------------------------------------------------------ 两种弹窗

    private fun boolItem(
        icon: Int,
        title: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ): Item = Item(icon, title, { if (get()) "开" else "关" }) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(arrayOf("开", "关"), if (get()) 0 else 1) { d, which ->
                set(which == 0)
                d.dismiss()
                refreshValues()
            }
            .show()
    }

    private fun numberItem(
        icon: Int,
        title: String,
        min: Int,
        max: Int,
        get: () -> Int,
        value: (Int) -> String = { it.toString() },
        set: (Int) -> Unit
    ): Item = Item(icon, title, { value(get()) }) {
        val cur = get()
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
            .setTitle(title)
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
}
