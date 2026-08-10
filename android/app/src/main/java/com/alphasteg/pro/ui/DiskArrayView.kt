package com.alphasteg.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.sin

/**
 * A RAID-manager style array of disks, drawn on a hardware-accelerated Canvas
 * (which runs on the GPU). Each carrier is a tile with a circular usage gauge in
 * its own color, a soft glow, and an "online" indicator that pulses. Set the
 * data with [setDisks].
 */
class DiskArrayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Disk(val label: String, val sub: String, val usedFraction: Float, val color: Int)

    private var disks: List<Disk> = emptyList()
    private val columns = 3

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#0C1322") }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(6f); color = Color.parseColor("#1E2D4A") }
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(15f); isFakeBoldText = true }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C7D0DE"); textAlign = Paint.Align.CENTER; textSize = dp(11f); isFakeBoldText = true }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8F9CAE"); textAlign = Paint.Align.CENTER; textSize = dp(9.5f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var phase = 0f
    private val gap = dp(10f)
    private val tileH = dp(132f)

    private val ticker = object : Runnable {
        override fun run() {
            phase += 0.06f
            invalidate()
            postDelayed(this, 33) // ~30 fps pulse
        }
    }

    fun setDisks(list: List<Disk>) {
        disks = list
        requestLayout()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(ticker)
        if (isVisible && disks.isNotEmpty()) post(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(ticker)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = if (disks.isEmpty()) 0 else ceil(disks.size / columns.toFloat()).toInt()
        val height = (rows * tileH + (rows).coerceAtLeast(0) * gap).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, height.coerceAtLeast(0))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (disks.isEmpty() || width == 0) return
        val tileW = (width - gap * (columns - 1)) / columns

        disks.forEachIndexed { i, d ->
            val col = i % columns
            val row = i / columns
            val left = col * (tileW + gap)
            val top = row * (tileH + gap)
            drawTile(canvas, d, left, top, tileW, tileH, i)
        }
    }

    private fun drawTile(canvas: Canvas, d: Disk, left: Float, top: Float, w: Float, h: Float, index: Int) {
        val rect = RectF(left, top, left + w, top + h)
        val radius = dp(14f)

        // Body + colored glowing border.
        canvas.drawRoundRect(rect, radius, radius, tilePaint)
        val pulse = (sin(phase + index * 0.6f) * 0.5f + 0.5f)
        strokePaint.color = d.color
        strokePaint.setShadowLayer(dp(10f) * (0.4f + 0.6f * pulse), 0f, 0f, d.color)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        strokePaint.clearShadowLayer()

        // Circular usage gauge.
        val cx = left + w / 2f
        val cy = top + dp(46f)
        val gr = dp(30f)
        val arc = RectF(cx - gr, cy - gr, cx + gr, cy + gr)
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        gaugePaint.color = d.color
        canvas.drawArc(arc, -90f, 360f * d.usedFraction.coerceIn(0f, 1f), false, gaugePaint)
        canvas.drawText("${(d.usedFraction * 100).toInt()}%", cx, cy + dp(5f), pctPaint)

        // Labels.
        canvas.drawText(ellipsize(d.label, labelPaint, w - dp(12f)), cx, top + h - dp(30f), labelPaint)
        canvas.drawText(ellipsize(d.sub, subPaint, w - dp(12f)), cx, top + h - dp(14f), subPaint)

        // Online dot, pulsing.
        dotPaint.color = Color.parseColor("#2ECC71")
        dotPaint.alpha = (120 + 135 * pulse).toInt().coerceIn(0, 255)
        canvas.drawCircle(left + dp(14f), top + dp(14f), dp(4f), dotPaint)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
