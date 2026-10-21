package com.szech.walls.ui.nodes

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szech.walls.R
import com.szech.walls.export.Formats
import com.szech.walls.model.Node
import com.szech.walls.ui.QrCode
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsUi
import com.google.android.material.R as MR

/**
 * 节点详情：底部弹层，不是弹窗。
 *
 * 手机上拇指够得着，而且能放得下更多东西 —— 现在这里还挂着「二维码」，
 * 把分享链接生成出来给另一台设备扫。
 */
object NodeDetailSheet {

    fun show(ctx: Context, node: Node) {
        val ui = WallsUi(ctx)
        val link = if (node.type == "ss") Formats.ssUri(node) else Formats.v2rayUri(node)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部拖拽条
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
                layoutParams = FrameLayout.LayoutParams(ui.dp(32), ui.dp(4), android.view.Gravity.CENTER)
            })
        })

        // 头：图标底座 + 标题 + 节点名
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12))
        }
        head.addView(ui.iconBox(R.drawable.ic_public, boxDp = 40, iconDp = 22))
        head.addView(ui.space(ui.dp(14)))
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.label("节点详情", R.style.TextAppearance_Walls_PageTitle))
            addView(ui.label(node.name, R.style.TextAppearance_Walls_Caption).apply { maxLines = 1 })
        })
        col.addView(head)

        // 明细
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(20), 0)
            addView(ui.infoRow("类型", node.type.ifEmpty { "—" }))
            addView(ui.infoRow("地址", "${node.server}:${node.port}"))
            if (node.cipher.isNotEmpty()) addView(ui.infoRow("加密", node.cipher))
            if (node.speed > 0) addView(ui.infoRow("实测速度", String.format("%.2f MB/s", node.speed)))
            if (node.realDelay > 0) addView(ui.infoRow("实测延迟", "${node.realDelay} ms"))
            if (node.delay > 0) addView(ui.infoRow("TCP 延迟", "${node.delay} ms"))
            if (node.note.isNotEmpty()) addView(ui.infoRow("备注", node.note))
        })

        // 分享链接
        if (!link.isNullOrEmpty()) {
            col.addView(ui.label(link, R.style.TextAppearance_Walls_Mono).apply {
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
                setTextIsSelectable(true)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = ui.dp(12).toFloat()
                    setColor(ui.attr(MR.attr.colorSurfaceContainer))
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(ui.dp(20), ui.dp(12), ui.dp(20), 0)
                layoutParams = lp
            })
        }

        // 两个动作
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20))
        }
        val copy = ui.button(
            "复制链接", R.style.ThemeOverlay_Walls_Button, R.drawable.ic_content_copy
        ) {
            link?.let { WallsCmd.copy(ctx, it, "已复制分享链接") }
        }
        copy.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = ui.dp(6) }
        actions.addView(copy)

        val qr = ui.button(
            "二维码", R.style.ThemeOverlay_Walls_Button_Tonal, R.drawable.ic_qr_code
        ) {
            if (link.isNullOrEmpty()) WallsCmd.toast(ctx, "这个节点生成不了分享链接")
            else QrCode.show(ctx, "节点二维码", link)
        }
        qr.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = ui.dp(6) }
        actions.addView(qr)
        col.addView(actions)

        BottomSheetDialog(ctx).apply {
            setContentView(col)
            show()
        }
    }

}
