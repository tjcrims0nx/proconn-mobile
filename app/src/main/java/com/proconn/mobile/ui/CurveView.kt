package com.proconn.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Graphs stick input (0..1) vs output (0..1) with a live dot driven by the real stick. */
class CurveView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** Maps raw 0..1 input to 0..1 output (deadzone + curve already applied by caller). */
    var curve: (Float) -> Float = { it }
    var liveX: Float = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#32225A")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#241743")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B5CF6")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B7A6F5")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A7DB5")
        textSize = 26f
    }

    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 40f
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return

        // grid
        for (i in 0..4) {
            val gx = pad + w * i / 4f
            val gy = pad + h * i / 4f
            canvas.drawLine(gx, pad, gx, pad + h, gridPaint)
            canvas.drawLine(pad, gy, pad + w, gy, gridPaint)
        }
        canvas.drawLine(pad, pad, pad, pad + h, axisPaint)
        canvas.drawLine(pad, pad + h, pad + w, pad + h, axisPaint)
        canvas.drawText("in", pad + w - 30f, pad + h + 30f, labelPaint)
        canvas.drawText("out", 4f, pad + 24f, labelPaint)

        // curve
        val path = Path()
        val steps = 60
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val y = curve(x).coerceIn(0f, 1f)
            val sx = pad + w * x
            val sy = pad + h * (1f - y)
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        canvas.drawPath(path, curvePaint)

        // live dot
        val lx = liveX.coerceIn(0f, 1f)
        val ly = curve(lx).coerceIn(0f, 1f)
        canvas.drawCircle(pad + w * lx, pad + h * (1f - ly), 12f, dotPaint)
    }
}
