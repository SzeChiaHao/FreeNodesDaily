package com.szech.walls.ui.scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 扫码取景框。自己画，不用 zxing 那个默认的 ViewfinderView ——
 * 默认那套是「四个角 + 一条不动的红线」，提示字还压在框下面，看着潦草。
 *
 * 这里画：
 *   半透明遮罩，中间挖一个圆角方孔
 *   方孔一圈细边框
 *   提示字在框**上方**，带圆角底和描边（不是光秃秃一行字）
 *   一条红色细线在框里上下往返
 */
class ScanOverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    companion object {
        /** 取景框边长（像素）。解码区域也按这个收，两边必须用同一个算法。 */
        fun frameSide(width: Int, height: Int): Float =
            minOf(width * 0.68f, height * 0.44f)

        private const val FRAME_TOP_RATIO = 0.30f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val dim = Paint().apply { color = Color.parseColor("#A6000000") }

    private val frameBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#80FFFFFF")
    }

    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6000000") }
    private val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#80FFFFFF")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }

    private val frame = RectF()
    private var lineProgress = 0f

    private val sweep = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            lineProgress = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = frameSide(w, h)
        val left = (w - side) / 2f
        val top = h * FRAME_TOP_RATIO
        frame.set(left, top, left + side, top + side)

        // 红线两端淡出，中间实
        linePaint.shader = LinearGradient(
            frame.left, 0f, frame.right, 0f,
            intArrayOf(
                Color.parseColor("#00FF3B30"),
                Color.parseColor("#FFFF3B30"),
                Color.parseColor("#FFFF3B30"),
                Color.parseColor("#00FF3B30")
            ),
            floatArrayOf(0f, 0.18f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweep.start()
    }

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(28f)

        // 遮罩：整屏铺深色，再把中间的圆角方孔挖掉
        val mask = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(frame, r, r, Path.Direction.CW)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(mask, dim)

        // 方孔一圈细边框
        canvas.drawRoundRect(frame, r, r, frameBorder)

        // 框里上下往返的红线
        val cy = frame.top + dp(10f) + (frame.height() - dp(20f)) * lineProgress
        canvas.drawLine(frame.left + dp(14f), cy, frame.right - dp(14f), cy, linePaint)

        // 提示：在框的正上方，带圆角底 + 描边
        drawPill(canvas, frame.centerX(), frame.top - dp(18f))

        // 框下面一行小字
        canvas.drawText("对上就自动识别，扫不出来可以点屏幕对焦", frame.centerX(), frame.bottom + dp(30f), captionPaint)
    }

    private fun drawPill(canvas: Canvas, cx: Float, bottom: Float) {
        val text = "对准二维码"
        val padH = dp(20f)
        val padV = dp(10f)
        val w = titlePaint.measureText(text) / 2f + padH
        val h = (titlePaint.descent() - titlePaint.ascent()) / 2f + padV
        val rect = RectF(cx - w, bottom - h * 2f, cx + w, bottom)
        val rr = rect.height() / 2f
        canvas.drawRoundRect(rect, rr, rr, pillFill)
        canvas.drawRoundRect(rect, rr, rr, pillBorder)
        canvas.drawText(
            text,
            cx,
            rect.centerY() - (titlePaint.descent() + titlePaint.ascent()) / 2f,
            titlePaint
        )
    }
}
