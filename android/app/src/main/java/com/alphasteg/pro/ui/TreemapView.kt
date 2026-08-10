package com.alphasteg.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A WinDirStat-style treemap: every item is a rectangle whose area is its byte
 * size, packed with the squarified algorithm (Bruls, Huizing, van Wijk) so the
 * tiles stay close to square. Colored by the item's color; big tiles get a label.
 * Rendered on the hardware-accelerated Canvas (GPU).
 */
class TreemapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Item(val label: String, val bytes: Long, val color: Int, val tag: Any? = null)

    private var items: List<Item> = emptyList()
    private var rects: List<RectF> = emptyList()

    /** Invoked with the tapped tile's item. */
    var onTileClick: ((Item) -> Unit)? = null

    init { isClickable = true }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.parseColor("#050811") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(11f); isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DDE6F2"); textSize = dp(9.5f) }

    fun setItems(list: List<Item>) {
        val firstFill = items.isEmpty() && list.isNotEmpty()
        items = list.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
        rects = emptyList()
        requestLayout()
        invalidate()
        if (firstFill) {
            alpha = 0f
            animate().cancel()
            animate().alpha(1f).setDuration(320).start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 0.62f).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    private fun recompute() {
        if (items.isEmpty() || width == 0 || height == 0) { rects = emptyList(); return }
        val bounds = RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        rects = squarify(items.map { it.bytes.toDouble() }, bounds)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) recompute()
        items.forEachIndexed { i, item ->
            val r = rects.getOrNull(i) ?: return@forEachIndexed
            fill.color = item.color
            canvas.drawRect(r, fill)
            canvas.drawRect(r, border)
            // Label only tiles big enough to read.
            if (r.width() > dp(56f) && r.height() > dp(28f)) {
                val name = ellipsize(item.label, label, r.width() - dp(10f))
                canvas.drawText(name, r.left + dp(5f), r.top + dp(15f), label)
                canvas.drawText(humanSize(item.bytes), r.left + dp(5f), r.top + dp(27f), sub)
            }
        }
    }

    /** Squarified treemap layout: returns one RectF per input weight, in order. */
    private fun squarify(weights: List<Double>, bounds: RectF): List<RectF> {
        val total = weights.sum().coerceAtLeast(1e-9)
        val scale = bounds.width().toDouble() * bounds.height().toDouble() / total
        val areas = weights.map { it * scale }
        val order = weights.indices.sortedByDescending { weights[it] }
        val out = arrayOfNulls<RectF>(weights.size)
        var free = RectF(bounds)
        var i = 0
        val n = order.size

        fun worst(row: List<Double>, side: Double): Double {
            val sum = row.sum(); if (sum <= 0.0 || side <= 0.0) return Double.MAX_VALUE
            val s2 = sum * sum; val side2 = side * side
            return maxOf(side2 * row.max() / s2, s2 / (side2 * row.min()))
        }

        while (i < n) {
            val vertical = free.width() >= free.height()
            val side = (if (vertical) free.height() else free.width()).toDouble()
            var j = i
            var row = mutableListOf(areas[order[i]])
            while (j + 1 < n) {
                val next = ArrayList(row).apply { add(areas[order[j + 1]]) }
                if (worst(next, side) <= worst(row, side)) { row = next; j++ } else break
            }
            val rowSum = row.sum()
            val thickness = if (side > 0) rowSum / side else 0.0
            var pos = (if (vertical) free.top else free.left).toDouble()
            for (k in i..j) {
                val a = areas[order[k]]
                val extent = if (thickness > 0) a / thickness else 0.0
                out[order[k]] = if (vertical)
                    RectF(free.left, pos.toFloat(), (free.left + thickness).toFloat(), (pos + extent).toFloat())
                else
                    RectF(pos.toFloat(), free.top, (pos + extent).toFloat(), (free.top + thickness).toFloat())
                pos += extent
            }
            free = if (vertical)
                RectF((free.left + thickness).toFloat(), free.top, free.right, free.bottom)
            else
                RectF(free.left, (free.top + thickness).toFloat(), free.right, free.bottom)
            i = j + 1
        }
        return out.map { it ?: RectF() }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            val idx = rects.indexOfFirst { it.contains(event.x, event.y) }
            if (idx in items.indices) {
                performClick()
                onTileClick?.invoke(items[idx])
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun humanSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
