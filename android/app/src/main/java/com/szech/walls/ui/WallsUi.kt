package com.szech.walls.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.szech.walls.R
import com.google.android.material.R as MR

/**
 * 界面零件库。
 *
 * 规格照抄「不高山上（Bugaoshan）」的 Material 3 Expressive 设计体系
 * （对应它的 lib/theme_shape.dart、styled_card.dart、styled_tile.dart）：
 *
 *   栅格    页面左右留白 16dp、卡片内边距 20dp
 *           页头图标框左边缘 = 卡片左边缘 = 16dp
 *           组标题左边缘 = 卡片里图标框左边缘 = 36dp
 *   卡片    20dp 圆角、surfaceContainerLow 底、1dp 低对比描边、极浅阴影
 *   列表项  内边距 竖 14 横 20；左侧 36dp 圆角图标底座；右端 20dp chevron
 *   分隔线  1dp、缩进 56dp（正好让开图标底座）、8% 描边色
 *   按钮    一律药丸形；实心＝主操作，柔色＝次操作，描边＝弱操作
 *
 * 颜色一律从主题属性取（attr()），绝不写死色值——写死必然在深色模式下翻车。
 */
class WallsUi(private val ctx: Context) {

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 解析主题属性（随浅色/深色自动变化） */
    fun attr(@AttrRes a: Int): Int = MaterialColors.getColor(ctx, a, 0)

    /** 取本工程自己的语义色（success 之类 M3 没有的角色） */
    fun clr(@ColorRes r: Int): Int = ContextCompat.getColor(ctx, r)

    // ------------------------------------------------------------ 基础零件

    fun space(w: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    fun label(s: String, @StyleRes style: Int): TextView = TextView(ctx).apply {
        TextViewCompat.setTextAppearance(this, style)
        text = s
    }

    /** 36dp / 40dp 圆角图标底座：主色 12% 底 + 主色图标 */
    fun iconBox(
        @DrawableRes icon: Int,
        boxDp: Int = 36,
        iconDp: Int = 20,
        container: Int = 0,
        on: Int = 0
    ): FrameLayout {
        val primary = attr(MR.attr.colorPrimary)
        val bg = if (container != 0) container else ColorUtils.setAlphaComponent(primary, 31)
        val fg = if (on != 0) on else primary
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(bg)
            }
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(fg)
                layoutParams = FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER)
            })
        }
    }

    // ------------------------------------------------------------ 整页外壳

    class Page(val root: ScrollView, val col: LinearLayout)

    /** 一页的骨架：可滚动，左右各留 16dp */
    fun page(): Page {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            clipToPadding = false
            addView(col)
        }
        return Page(scroll, col)
    }

    /** 页头：40dp 图标框 + 22sp 大标题 + 一行副标题。图标框左边缘与卡片左边缘齐平。 */
    fun header(parent: LinearLayout, @DrawableRes icon: Int, title: String, sub: String) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        row.addView(iconBox(icon, boxDp = 40, iconDp = 22))
        row.addView(space(dp(14)))
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, R.style.TextAppearance_Walls_PageTitle))
            addView(label(sub, R.style.TextAppearance_Walls_Caption))
        })
        parent.addView(row)
    }

    /** 组标题：左边缘 = 卡片内图标框左边缘（16 + 20 = 36） */
    fun groupTitle(parent: LinearLayout, title: String) {
        parent.addView(label(title, R.style.TextAppearance_Walls_GroupTitle).apply {
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    // ------------------------------------------------------------ 卡片

    /** 一张卡片：20dp 圆角 + 描边 + 标题行，返回可继续塞内容的竖向容器。title 传空则不画标题行。 */
    fun card(parent: LinearLayout, @DrawableRes icon: Int, title: String): LinearLayout {
        val cardView = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
            radius = dp(20).toFloat()
            setCardBackgroundColor(attr(MR.attr.colorSurfaceContainerLow))
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = clr(R.color.md_walls_card_stroke)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        if (title.isNotEmpty()) {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(8))
            }
            if (icon != 0) {
                head.addView(iconBox(icon))
                head.addView(space(dp(14)))
            }
            head.addView(label(title, R.style.TextAppearance_Walls_CardTitle))
            body.addView(head)
        }
        cardView.addView(body)
        parent.addView(cardView)
        return body
    }

    /** 只有内容、没有标题的卡片（节点列表那种整卡列表项） */
    fun bareCard(parent: LinearLayout): LinearLayout {
        val cardView = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
            radius = dp(20).toFloat()
            setCardBackgroundColor(attr(MR.attr.colorSurfaceContainerLow))
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = clr(R.color.md_walls_card_stroke)
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        cardView.addView(body)
        parent.addView(cardView)
        return body
    }

    /** 卡片里放「自由内容」（非列表项）用的容器：左右 20dp */
    fun section(parent: LinearLayout, bottomDp: Int = 20): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(bottomDp))
            parent.addView(this)
        }

    /** 列表项之间的分隔线，缩进 56dp＝卡片内边距 20 + 图标底座 36 */
    fun divider(parent: LinearLayout, indentDp: Int = 56) {
        parent.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(indentDp) }
            setBackgroundColor(clr(R.color.md_walls_divider))
        })
    }

    // ------------------------------------------------------------ 列表项

    /**
     * 一个列表项：图标底座 + 主文字（+ 副文字） + 右侧取值 + 右侧附件 + chevron。
     * 标题不再用省略号截断，放不下就换行（最多两行）。
     */
    fun tile(
        @DrawableRes icon: Int = 0,
        title: String,
        sub: String? = null,
        value: String? = null,
        trailing: View? = null,
        chevron: Boolean = false,
        topDp: Int = 14,
        bottomDp: Int = 14,
        valueSink: ((TextView) -> Unit)? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(topDp), dp(20), dp(bottomDp))
            if (onClick != null) {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_tile_ripple)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        if (icon != 0) {
            row.addView(iconBox(icon))
            row.addView(space(dp(14)))
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(label(title, R.style.TextAppearance_Walls_TileLabel).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (sub != null) col.addView(label(sub, R.style.TextAppearance_Walls_Caption))
        row.addView(col)
        if (value != null) {
            row.addView(space(dp(10)))
            val v = label(value, R.style.TextAppearance_Walls_TileValue)
            valueSink?.invoke(v)
            row.addView(v)
        }
        if (trailing != null) {
            row.addView(space(dp(10)))
            row.addView(trailing)
        }
        if (chevron) {
            row.addView(space(dp(6)))
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(attr(MR.attr.colorOnSurfaceVariant), 0x66)
                )
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
        }
        return row
    }

    // ------------------------------------------------------------ 按钮

    /** 药丸按钮。overlay 取 R.style.ThemeOverlay_Walls_Button / _Tonal / _Outlined / _Text */
    fun button(
        text: String,
        @StyleRes overlay: Int = R.style.ThemeOverlay_Walls_Button,
        @DrawableRes icon: Int = 0,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(ContextThemeWrapper(ctx, overlay)).apply {
        this.text = text
        if (icon != 0) {
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            iconSize = dp(18)
        }
        setOnClickListener { onClick() }
    }

    /** 图标按钮（M3 柔色圆形），用于「复制」「打开」这类小动作 */
    fun iconButton(
        @DrawableRes icon: Int,
        desc: String,
        onClick: () -> Unit
    ): MaterialButton = MaterialButton(
        ContextThemeWrapper(ctx, MR.style.ThemeOverlay_Material3_Button_IconButton_Filled_Tonal)
    ).apply {
        setIconResource(icon)
        iconSize = dp(16)
        minWidth = dp(34)
        minimumWidth = dp(34)
        minHeight = dp(34)
        minimumHeight = dp(34)
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ 其它

    fun progressBar(): LinearProgressIndicator = LinearProgressIndicator(ctx).apply {
        isIndeterminate = false
        max = 100
        trackThickness = dp(6)
        setTrackCornerRadius(dp(3))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, dp(2)) }
        visibility = View.GONE
    }

    /** 详情里的「标签 : 取值」一行，标签定宽保证值对齐 */
    fun infoRow(k: String, v: String, labelWidthDp: Int = 84): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label(k, R.style.TextAppearance_Walls_Caption).apply { width = dp(labelWidthDp) })
            addView(label(v, R.style.TextAppearance_Walls_TileValue).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

    /** 圆角小徽标（节点名次） */
    fun badge(text: String, container: Int, on: Int, boxDp: Int = 36): TextView =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Walls_Rank)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(container)
            }
            setTextColor(on)
            layoutParams = LinearLayout.LayoutParams(dp(boxDp), dp(boxDp))
        }
}
