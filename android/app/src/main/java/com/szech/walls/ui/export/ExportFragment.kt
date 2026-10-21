package com.szech.walls.ui.export

import android.widget.LinearLayout
import android.widget.TextView
import com.szech.walls.R
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出：把节点写成配置文件，交给你自己的机场客户端。
 *
 * 这里不是订阅服务 —— App 不在后台起 HTTP 服务，也不给客户端喂地址。
 * 想清楚一件事就够了：产物是文件。文件写进「下载/Walls/」，
 * 你去客户端里走「从文件导入」即可。文件名带时间戳，不会撞名。
 */
class ExportFragment : WallsPageFragment() {

    private var stampView: TextView? = null
    private var cleanValueView: TextView? = null

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_download, "导出", "生成配置文件，交给客户端导入")

        ui.groupTitle(col, "按客户端生成")
        val card = ui.card(col, 0, "")
        WallsCmd.FORMATS.forEachIndexed { i, fmt ->
            if (i > 0) ui.divider(card)
            // 整行可点：点哪儿都是「只生成这一种」，不会顺带把别的也写了
            card.addView(
                ui.tile(
                    icon = R.drawable.ic_description,
                    title = fmt.clients,
                    sub = fmt.note,
                    trailing = ui.iconButton(R.drawable.ic_download, "生成 ${fmt.clients} 的配置文件") {
                        generate(fmt.key)
                    },
                    onClick = { generate(fmt.key) }
                )
            )
        }

        val sec = ui.section(card, bottomDp = 18)
        stampView = ui.label("", R.style.TextAppearance_Walls_Caption)
        sec.addView(stampView)
        sec.addView(
            ui.label(
                "文件名形如 clash-20261018-1425.yaml：前缀是格式，后面是生成时间。" +
                    "写进「下载/Walls/」，去客户端里走「从文件导入」挑对应的那一个。",
                R.style.TextAppearance_Walls_Caption
            )
        )

        val btnAll = ui.button(
            "全部生成", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_download
        ) { generateAll() }
        btnAll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(14) }
        sec.addView(btnAll)

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(8), 0, 0)
        }
        val btnOpen = ui.button(
            "打开目录", R.style.ThemeOverlay_Walls_Button_Tonal, R.drawable.ic_folder_open
        ) { WallsCmd.openDownloadsFolder(requireContext()) }
        btnOpen.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(6) }
        row.addView(btnOpen)

        val btnClean = ui.button(
            "清理旧文件", R.style.ThemeOverlay_Walls_Button_Outlined, R.drawable.ic_delete
        ) { cleanOld() }
        btnClean.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = ui.dp(6) }
        row.addView(btnClean)
        sec.addView(row)

        val fileCard = ui.card(col, R.drawable.ic_folder_open, "文件放在哪")
        fileCard.addView(
            ui.tile(
                icon = R.drawable.ic_folder_open,
                title = "下载/Walls/",
                sub = "系统「下载」里的 Walls 文件夹",
                value = "打开",
                chevron = true,
                onClick = { WallsCmd.openDownloadsFolder(requireContext()) }
            )
        )
        ui.divider(fileCard)
        fileCard.addView(
            ui.tile(
                icon = R.drawable.ic_delete,
                title = "生成后删除旧文件",
                sub = "只删本应用生成的那些，你自己放进去的不动",
                value = if (Prefs.cleanOldExports) "开" else "关",
                valueSink = { cleanValueView = it },
                onClick = {
                    Prefs.cleanOldExports = !Prefs.cleanOldExports
                    cleanValueView?.text = if (Prefs.cleanOldExports) "开" else "关"
                }
            )
        )

        renderStamp()
    }

    override fun refresh() = renderStamp()

    private fun renderStamp() {
        val v = stampView ?: return
        val ts = Prefs.lastExportAt
        v.text = if (ts <= 0) {
            "还没生成过。先回概览跑一次更新，节点有了这里才有的可写。"
        } else {
            "上次生成：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(ts)) + " · " + Repo.nodes.size + " 个节点"
        }
    }

    private fun generate(key: String) {
        if (Repo.nodes.isEmpty()) {
            WallsCmd.toast(requireContext(), "先跑一次更新，有节点才能生成")
            return
        }
        WallsCmd.exportOne(requireContext(), key)
        renderStamp()
    }

    private fun generateAll() {
        WallsCmd.exportToDownloads(requireContext(), Repo.nodes)
        renderStamp()
    }

    private fun cleanOld() {
        val n = WallsCmd.cleanOldExports(requireContext())
        WallsCmd.toast(requireContext(), if (n > 0) "已删除 $n 个旧配置文件" else "没有可删的旧文件")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stampView = null
        cleanValueView = null
    }
}
