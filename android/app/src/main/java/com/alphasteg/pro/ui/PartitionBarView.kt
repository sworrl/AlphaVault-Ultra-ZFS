package com.alphasteg.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A GParted-style allocation bar: one horizontal strip split into proportional,
 * colored segments (used, music pool, vault, free), with a small legend beneath.
 * GPU-accelerated Canvas.
 */
class PartitionBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Segment(val label: String, val bytes: Long, val color: Int)

    private var segments: List<Segment> = emptyList()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#0A101D") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.parseColor("#1E2D4A") }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.parseColor("#050811") }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C7D0DE"); textSize = dp(11f) }
    private val legendDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val barH = dp(30f)
    private val legendRowH = dp(20f)

    /** Invoked with the tapped segment. */
    var onSegmentClick: ((Segment) -> Unit)? = null

    init { isClickable = true }

    fun setSegments(list: List<Segment>) {
        segments = list.filter { it.bytes > 0 }
        requestLayout()
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP && event.y <= barH + dp(4f) && segments.isNotEmpty()) {
            val total = segments.sumOf { it.bytes }.coerceAtLeast(1L).toDouble()
            val left = dp(1f); val right = width - dp(1f)
            var x = left.toDouble()
            for (seg in segments) {
                val w = seg.bytes / total * (right - left)
                if (event.x in x..(x + w)) { performClick(); onSegmentClick?.invoke(seg); return true }
                x += w
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val legendRows = if (segments.isEmpty()) 0 else ((segments.size + 1) / 2)
        val h = (barH + dp(10f) + legendRows * legendRowH).toInt()
        setMeasuredDimension(width, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty() || width == 0) return
        val total = segments.sumOf { it.bytes }.coerceAtLeast(1L).toDouble()

        val bar = RectF(dp(1f), dp(1f), width - dp(1f), barH)
        val radius = dp(8f)

        val clip = Path().apply { addRoundRect(bar, radius, radius, Path.Direction.CW) }
        canvas.drawRoundRect(bar, radius, radius, track)
        canvas.save()
        canvas.clipPath(clip)
        var x = bar.left.toDouble()
        for (seg in segments) {
            val w = seg.bytes / total * bar.width()
            fill.color = seg.color
            canvas.drawRect(x.toFloat(), bar.top, (x + w).toFloat(), bar.bottom, fill)
            if (x > bar.left) canvas.drawLine(x.toFloat(), bar.top, x.toFloat(), bar.bottom, divider)
            x += w
        }
        canvas.restore()
        canvas.drawRoundRect(bar, radius, radius, stroke)

        // Legend, two columns.
        val colW = width / 2f
        var i = 0
        for (seg in segments) {
            val col = i % 2
            val row = i / 2
            val lx = col * colW + dp(2f)
            val ly = barH + dp(10f) + row * legendRowH + dp(12f)
            legendDot.color = seg.color
            canvas.drawCircle(lx + dp(5f), ly - dp(4f), dp(4f), legendDot)
            canvas.drawText("${seg.label}  ${humanSize(seg.bytes)}", lx + dp(15f), ly, legendText)
            i++
        }
    }

    private fun humanSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1) return String.format("%.1f GB", gb)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.0f MB", mb)
    }
}
