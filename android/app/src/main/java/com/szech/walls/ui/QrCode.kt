package com.szech.walls.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.szech.walls.parse.UriParser
import com.szech.walls.store.Prefs
import com.szech.walls.store.Repo

/**
 * 二维码两个方向：
 *   扫进来 —— 扫到订阅链接就加进订阅源，扫到 ss:// 这类分享链接就导入成单个节点
 *   扫出去 —— 把节点分享链接生成二维码，给另一台设备扫
 */
object QrCode {

    fun makeBitmap(text: String, sizePx: Int): Bitmap? = try {
        BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    } catch (e: Exception) {
        null
    }

    /** 弹窗显示一段文本的二维码 */
    fun show(ctx: Context, title: String, text: String) {
        val size = (ctx.resources.displayMetrics.widthPixels * 0.6f).toInt().coerceIn(240, 900)
        val bmp = makeBitmap(text, size)
        if (bmp == null) {
            WallsCmd.toast(ctx, "生成二维码失败")
            return
        }
        val ui = WallsUi(ctx)
        val img = ImageView(ctx).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(4))
            addView(img)
            addView(ui.label(text, com.szech.walls.R.style.TextAppearance_Walls_Caption).apply {
                setPadding(0, ui.dp(12), 0, 0)
                setTextIsSelectable(true)
            })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("复制文本") { _, _ -> WallsCmd.copy(ctx, text, "已复制") }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 处理扫码结果。onChanged 在数据被改动后回调，让界面刷新。 */
    fun handleScan(ctx: Context, raw: String, onChanged: () -> Unit = {}) {
        val text = raw.trim()
        if (text.isEmpty()) {
            WallsCmd.toast(ctx, "二维码是空的")
            return
        }
        val scheme = text.substringBefore("://", "").lowercase()

        when {
            // 订阅链接：加进订阅源
            text.startsWith("http://") || text.startsWith("https://") -> {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("扫到一条订阅链接")
                    .setMessage(text)
                    .setPositiveButton("加入订阅源") { _, _ ->
                        val list = Prefs.sources.toMutableList()
                        if (list.any { it == text }) {
                            WallsCmd.toast(ctx, "这条已经在订阅源里了")
                        } else {
                            list.add(text)
                            Prefs.sources = list
                            WallsCmd.toast(ctx, "已加入订阅源，现在共 ${list.size} 个")
                            onChanged()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            // 分享链接：导入成单个节点
            else -> {
                val node = UriParser.uriToProxy(text)
                if (node == null) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("不认识这个二维码")
                        .setMessage(if (scheme.isEmpty()) text else "协议 「$scheme」 暂时解析不了。\n\n$text")
                        .setPositiveButton("好", null)
                        .show()
                    return
                }
                val ui = WallsUi(ctx)
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(4))
                    addView(ui.infoRow("名称", node.name))
                    addView(ui.infoRow("类型", node.type))
                    addView(ui.infoRow("地址", "${node.server}:${node.port}"))
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("扫到一个节点")
                    .setView(box)
                    .setPositiveButton("导入") { _, _ ->
                        val list = Repo.nodes.toMutableList()
                        if (list.any { it.server == node.server && it.port == node.port }) {
                            WallsCmd.toast(ctx, "这个节点已经在列表里了")
                        } else {
                            list.add(node)
                            Repo.setResult(list, Repo.status)
                            WallsCmd.toast(ctx, "已导入，现在共 ${list.size} 个节点")
                            onChanged()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
