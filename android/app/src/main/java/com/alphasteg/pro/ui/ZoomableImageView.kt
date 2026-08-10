package com.alphasteg.pro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image view you can pinch-zoom, drag-pan, and double-tap-zoom, drawn through a
 * [Matrix]. It has no dependencies, so it runs inside the secure vault viewer with
 * nothing leaving the app.
 *
 * The image starts fit-centered; that fit is the minimum scale (you cannot shrink
 * it smaller than the screen), and it zooms up to [MAX_ZOOM] times fit. Panning is
 * clamped so the picture edges never travel inside the view once it fills it.
 */
@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    private val m = Matrix()
    private val tmp = FloatArray(9)
    private var baseScale = 1f
    private var minScale = 1f
    private var maxScale = 1f
    private var viewW = 0
    private var viewH = 0
    private var laidOut = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                var factor = d.scaleFactor
                if (cur * factor < minScale) factor = minScale / cur
                if (cur * factor > maxScale) factor = maxScale / cur
                m.postScale(factor, factor, d.focusX, d.focusY)
                clampTranslation()
                imageMatrix = m
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                m.postTranslate(-dx, -dy)
                clampTranslation()
                imageMatrix = m
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > baseScale * 1.05f) baseScale else baseScale * 2.5f
                val factor = target / currentScale()
                m.postScale(factor, factor, e.x, e.y)
                clampTranslation()
                imageMatrix = m
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        setOnTouchListener { _, ev ->
            // Once a gesture starts, keep it: a parent pager or scroll view must not steal it.
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            true
        }
    }

    private fun currentScale(): Float {
        m.getValues(tmp)
        return tmp[Matrix.MSCALE_X]
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        viewW = w
        viewH = h
        laidOut = true
        fitCenter()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (laidOut) fitCenter()
    }

    private fun fitCenter() {
        val d = drawable ?: return
        val bw = d.intrinsicWidth.toFloat()
        val bh = d.intrinsicHeight.toFloat()
        if (viewW == 0 || viewH == 0 || bw <= 0 || bh <= 0) return
        baseScale = minOf(viewW / bw, viewH / bh)
        minScale = baseScale
        maxScale = baseScale * MAX_ZOOM
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((viewW - bw * baseScale) / 2f, (viewH - bh * baseScale) / 2f)
        imageMatrix = m
    }

    private fun imageRect(): RectF {
        val d = drawable ?: return RectF()
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        return r
    }

    private fun clampTranslation() {
        val r = imageRect()
        val dx = when {
            r.width() <= viewW -> (viewW - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < viewW -> viewW - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= viewH -> (viewH - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < viewH -> viewH - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 6f
    }
}
