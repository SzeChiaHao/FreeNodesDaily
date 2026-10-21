package com.szech.walls.ui.nodes

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.szech.walls.R
import com.szech.walls.model.Node
import com.szech.walls.store.Repo
import com.szech.walls.ui.WallsCmd
import com.szech.walls.ui.WallsPageFragment
import com.google.android.material.R as MR

/**
 * 节点：搜索框 + 筛选 chips + 按实测下载速度降序的名单。
 * 点任意一条弹底部弹层看详情。
 */
class NodesFragment : WallsPageFragment() {

    private enum class Filter(val label: String) {
        ALL("全部"),
        SS("Shadowsocks"),
        TROJAN("Trojan"),
        VLESS("VLESS"),
        MEASURED("已测速")
    }

    private var query = ""
    private var filter = Filter.ALL
    private var host: LinearLayout? = null
    private var subtitle: android.widget.TextView? = null

    override fun build() {
        val col = page.col
        ui.header(col, R.drawable.ic_public, "节点", "按实测下载速度降序")

        col.addView(buildSearchBox())
        col.addView(buildChips())

        host = ui.bareCard(col)
        renderNodes()
    }

    override fun refresh() {
        renderNodes()
    }

    // ------------------------------------------------------------ 搜索与筛选

    private fun buildSearchBox(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_search_box)
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)
            ).apply { setMargins(0, ui.dp(4), 0, ui.dp(10)) }
        }
        row.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(ui.attr(MR.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20))
        })
        row.addView(ui.space(ui.dp(10)))
        row.addView(EditText(requireContext()).apply {
            background = null
            hint = "搜索节点、地址、备注"
            setHintTextColor(ui.attr(MR.attr.colorOnSurfaceVariant))
            setTextColor(ui.attr(MR.attr.colorOnSurface))
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    renderNodes()
                }
            })
        })
        return row
    }

    private fun buildChips(): View {
        // 用 ChipGroup + 横向滚动：五个筛选条件排一行，窄屏就横着滚
        val group = ChipGroup(ContextThemeWrapper(requireContext(), MR.style.Widget_Material3_ChipGroup)).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, ui.dp(4))
        }
        for (f in Filter.values()) {
            val chip = Chip(ContextThemeWrapper(requireContext(), MR.style.Widget_Material3_Chip_Filter)).apply {
                text = f.label
                isCheckable = true
                isChecked = f == filter
                chipMinHeight = ui.dp(34).toFloat()
                setOnCheckedChangeListener { _, checked -> if (checked) { filter = f; renderNodes() } }
            }
            group.addView(chip)
        }
        return HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, ui.dp(4)) }
            addView(group)
        }
    }

    private fun visibleNodes(): List<Node> {
        val all = Repo.nodes
        val byType = when (filter) {
            Filter.ALL -> all
            Filter.SS -> all.filter { it.type == "ss" }
            Filter.TROJAN -> all.filter { it.type == "trojan" }
            Filter.VLESS -> all.filter { it.type == "vless" }
            Filter.MEASURED -> all.filter { it.speed > 0 }
        }
        if (query.isEmpty()) return byType
        val q = query.lowercase()
        return byType.filter {
            it.name.lowercase().contains(q) ||
                it.server.lowercase().contains(q) ||
                it.note.lowercase().contains(q) ||
                it.type.lowercase().contains(q)
        }
    }

    // ------------------------------------------------------------ 名单

    private fun renderNodes() {
        val box = host ?: return
        // 重建列表会让子视图先被清空、高度变 0，页面的 ScrollView 就被夹回顶部。
        // 搜索每敲一个字也会走到这里，于是“搜一个词就被拉回顶部”很难用。
        // 先把当前滚动位置记下来，重建完再放回去。
        val sv = page.root
        val keepY = sv.scrollY
        val keepBottom = sv.scrollY + sv.height >= sv.getChildAt(0).height - 4
        box.removeAllViews()
        val list = visibleNodes()

        if (list.isEmpty()) {
            val msg = when {
                Repo.nodes.isEmpty() -> "还没有节点，回概览点一下「开始更新」。"
                query.isNotEmpty() -> "没有匹配「$query」的节点。"
                filter == Filter.MEASURED -> "这次没有测出速度的节点，试试把筛选切回「全部」。"
                else -> "这一类里没有节点。"
            }
            box.addView(
                ui.label(msg, R.style.TextAppearance_Walls_Caption)
                    .apply { setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20)) }
            )
            restoreScroll(sv, keepY, keepBottom)
            return
        }
        list.forEachIndexed { i, n ->
            if (i > 0) ui.divider(box)
            box.addView(nodeRow(i, n))
        }
        restoreScroll(sv, keepY, keepBottom)
    }

    /** 重建列表后把滚动位置放回去（原来在底部就继续跟到底部） */
    private fun restoreScroll(sv: android.widget.ScrollView, y: Int, toBottom: Boolean) {
        sv.post {
            val content = sv.getChildAt(0)?.height ?: 0
            val target = if (toBottom) (content - sv.height).coerceAtLeast(0) else y
            sv.scrollTo(0, target.coerceIn(0, (content - sv.height).coerceAtLeast(0)))
        }
    }

    private fun nodeRow(index: Int, n: Node): View {
        val primary = ui.attr(MR.attr.colorPrimary)
        val top3 = index < 3 && filter == Filter.ALL && query.isEmpty()
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_tile_ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { NodeDetailSheet.show(requireContext(), n) }
        }
        row.addView(
            ui.badge(
                "${index + 1}",
                if (top3) primary else ColorUtils.setAlphaComponent(primary, 31),
                if (top3) ui.attr(MR.attr.colorOnPrimary) else primary
            )
        )
        row.addView(ui.space(ui.dp(14)))

        val mid = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(ui.label(n.name, R.style.TextAppearance_Walls_TileLabel).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(
            ui.label(
                buildString {
                    append(n.type).append(" · ").append(n.server).append(':').append(n.port)
                    if (n.note.isNotEmpty()) append(" · ").append(n.note)
                },
                R.style.TextAppearance_Walls_Caption
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        row.addView(mid)
        row.addView(ui.space(ui.dp(10)))

        val right = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(
            ui.label(
                if (n.speed > 0) String.format("%.2f MB/s", n.speed) else "仅测延迟",
                R.style.TextAppearance_Walls_TileLabel
            ).apply {
                setTextColor(if (n.speed > 0) ui.clr(R.color.md_success) else ui.attr(MR.attr.colorOnSurfaceVariant))
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        right.addView(
            ui.label(
                when {
                    n.realDelay > 0 -> "实测 ${n.realDelay} ms"
                    n.delay > 0 -> "TCP ${n.delay} ms"
                    else -> "延迟未知"
                },
                R.style.TextAppearance_Walls_Caption
            )
        )
        row.addView(right)
        return row
    }
}
