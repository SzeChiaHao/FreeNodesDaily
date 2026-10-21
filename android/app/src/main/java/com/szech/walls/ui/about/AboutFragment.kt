package com.szech.walls.ui.about

import com.szech.walls.R
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment

/** 关于：版本、仓库、许可 */
class AboutFragment : WallsPageFragment() {

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_description, "关于", "节点管家 Walls")

        val c1 = ui.card(col, R.drawable.ic_public, "应用简介")
        val s1 = ui.section(c1)
        s1.addView(
            ui.label(
                "这个应用帮你在网上收集公开的免费节点，挨个测一遍速度和延迟，再把还能用的排好序，整理成一份订阅。\n\n" +
                    "它自己不负责上网，得靠你另外装的代理客户端来连。你在客户端的订阅那一栏里，" +
                    "填上这里显示的本地地址，节点就拉过去了。",
                R.style.TextAppearance_Walls_TileLabel
            )
        )

        val c2 = ui.card(col, R.drawable.ic_speed, "本次数据")
        val s2 = ui.section(c2)
        s2.addView(ui.infoRow("节点数", "${Repo.nodes.size} 个"))
        s2.addView(ui.infoRow("订阅源", "${Prefs.sources.size} 个"))
        s2.addView(ui.infoRow("版本", "v" + WallsCmd.currentVersion(requireContext()).first))

        val c3 = ui.card(col, R.drawable.ic_link, "链接")
        c3.addView(
            ui.tile(
                icon = R.drawable.ic_link,
                title = "GitHub 仓库",
                sub = WallsCmd.GITHUB_URL,
                chevron = true,
                onClick = { WallsCmd.openUrl(requireContext(), WallsCmd.GITHUB_URL) }
            )
        )
        ui.divider(c3)
        c3.addView(
            ui.tile(
                icon = R.drawable.ic_verified,
                title = "开源许可",
                sub = "AGPL-3.0",
                chevron = true,
                onClick = {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
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
    }
}
