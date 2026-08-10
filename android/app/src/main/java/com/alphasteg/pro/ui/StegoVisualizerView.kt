package com.alphasteg.pro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class StegoVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00F2FE")
    }
    private val wavePath = Path()

    private var phase = 0f
    private val barCount = 32
    private val barHeights = FloatArray(barCount)

    private val animRunnable = object : Runnable {
        override fun run() {
            phase += 0.15f
            for (i in 0 until barCount) {
                barHeights[i] = (sin(phase + i * 0.35f) * 0.4f + 0.5f) * height * 0.75f
            }
            invalidate()
            postDelayed(this, 16) // ~60 FPS
        }
    }

    init {
        post(animRunnable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#00F2FE"),
            Color.parseColor("#9D00FF"),
            Shader.TileMode.CLAMP
        )
        barPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barWidth = width.toFloat() / barCount - 4f

        // 1. Draw Animated Frequency Bars
        for (i in 0 until barCount) {
            val left = i * (barWidth + 4f) + 2f
            val top = height - barHeights[i]
            val right = left + barWidth
            val bottom = height.toFloat()
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, barPaint)
        }

        // 2. Draw Superimposed Cyber Waveform
        wavePath.reset()
        val centerY = height / 2f
        wavePath.moveTo(0f, centerY)
        for (x in 0..width step 10) {
            val y = centerY + sin(phase + x * 0.02f).toFloat() * (height * 0.25f)
            wavePath.lineTo(x.toFloat(), y)
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animRunnable)
    }
}
