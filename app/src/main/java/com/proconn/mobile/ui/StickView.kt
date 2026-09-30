package com.proconn.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Live analog stick visualizer. Position set via setPosition(x, y) in -1..1. */
class StickView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var label: String = "L"
    private var px = 0f
    private var py = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#32225A")
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B5CF6")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#241743")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B7A6F5")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A7DB5")
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    fun setPosition(x: Float, y: Float) {
        px = x.coerceIn(-1f, 1f)
        py = y.coerceIn(-1f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6f
        canvas.drawCircle(cx, cy, r, ringPaint)
        canvas.drawCircle(cx, cy, r, edgePaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)
        canvas.drawText(label, cx, cy + r + 30f.coerceAtMost(height / 2f - 4f), textPaint)
        canvas.drawCircle(cx + px * r, cy + py * r, 16f, dotPaint)
    }
}
