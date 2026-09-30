package com.proconn.mobile.model

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject

enum class CrosshairStyle {
    CROSS, DOT, CIRCLE, CROSS_DOT, T_SHAPE, CHEVRON, DIAMOND
}

data class CrosshairDesign(
    var style: CrosshairStyle = CrosshairStyle.CROSS,
    var length: Float = 26f,
    var gap: Float = 10f,
    var thickness: Float = 5f,
    var color: Int = 0xFF00FF41.toInt(),
    var alpha: Int = 255,
    var centerDot: Boolean = true,
    var outline: Boolean = true,
    var name: String = "Default"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style.name)
        put("length", length.toDouble())
        put("gap", gap.toDouble())
        put("thickness", thickness.toDouble())
        put("color", color)
        put("alpha", alpha)
        put("centerDot", centerDot)
        put("outline", outline)
        put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject): CrosshairDesign = CrosshairDesign(
            style = try { CrosshairStyle.valueOf(o.optString("style", "CROSS")) } catch (e: Exception) { CrosshairStyle.CROSS },
            length = o.optDouble("length", 26.0).toFloat(),
            gap = o.optDouble("gap", 10.0).toFloat(),
            thickness = o.optDouble("thickness", 5.0).toFloat(),
            color = o.optInt("color", 0xFF00FF41.toInt()),
            alpha = o.optInt("alpha", 255),
            centerDot = o.optBoolean("centerDot", true),
            outline = o.optBoolean("outline", true),
            name = o.optString("name", "Default")
        )
    }
}

/** Shared crosshair drawing used by the designer preview, PNG export and the overlay. */
object CrosshairRenderer {
    fun draw(
        canvas: Canvas,
        d: CrosshairDesign,
        cx: Float,
        cy: Float,
        scale: Float,
        pulseScale: Float = 1f
    ) {
        val s = scale * pulseScale
        val L = d.length * s
        val G = d.gap * s
        val T = (d.thickness * s).coerceAtLeast(1f)

        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.color
            alpha = d.alpha.coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeWidth = T
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = d.color
            alpha = d.alpha.coerceIn(0, 255)
            style = Paint.Style.FILL
        }
        val outlineStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = (d.alpha * 0.85f).toInt().coerceIn(0, 255)
            style = Paint.Style.STROKE
            strokeWidth = T + 3.5f * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val outlineDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = (d.alpha * 0.85f).toInt().coerceIn(0, 255)
            style = Paint.Style.FILL
        }

        val strokes = if (d.outline) listOf(outlineStroke, main) else listOf(main)
        val dots = if (d.outline) listOf(outlineDot, dotPaint) else listOf(dotPaint)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            for (p in strokes) canvas.drawLine(x1, y1, x2, y2, p)
        }
        fun ring(x: Float, y: Float, r: Float) {
            for (p in strokes) canvas.drawCircle(x, y, r, p)
        }
        fun dotAt(x: Float, y: Float, r: Float) {
            for (p in dots) canvas.drawCircle(x, y, r, p)
        }

        when (d.style) {
            CrosshairStyle.CROSS -> {
                line(cx, cy - G - L, cx, cy - G)
                line(cx, cy + G, cx, cy + G + L)
                line(cx - G - L, cy, cx - G, cy)
                line(cx + G, cy, cx + G + L, cy)
                if (d.centerDot) dotAt(cx, cy, T * 0.7f)
            }
            CrosshairStyle.CROSS_DOT -> {
                line(cx, cy - G - L, cx, cy - G)
                line(cx, cy + G, cx, cy + G + L)
                line(cx - G - L, cy, cx - G, cy)
                line(cx + G, cy, cx + G + L, cy)
                dotAt(cx, cy, T * 0.7f)
            }
            CrosshairStyle.DOT -> dotAt(cx, cy, T.coerceAtLeast(2.5f))
            CrosshairStyle.CIRCLE -> {
                ring(cx, cy, L.coerceAtLeast(4f))
                if (d.centerDot) dotAt(cx, cy, T * 0.7f)
            }
            CrosshairStyle.T_SHAPE -> {
                line(cx - L, cy - G, cx + L, cy - G)
                line(cx, cy - G, cx, cy - G + L)
                if (d.centerDot) dotAt(cx, cy + G + T, T * 0.6f)
            }
            CrosshairStyle.CHEVRON -> {
                line(cx - L, cy + G, cx, cy - G)
                line(cx, cy - G, cx + L, cy + G)
                if (d.centerDot) dotAt(cx, cy, T * 0.7f)
            }
            CrosshairStyle.DIAMOND -> {
                val r = L.coerceAtLeast(6f)
                line(cx, cy - r, cx + r, cy)
                line(cx + r, cy, cx, cy + r)
                line(cx, cy + r, cx - r, cy)
                line(cx - r, cy, cx, cy - r)
                if (d.centerDot) dotAt(cx, cy, T * 0.7f)
            }
        }
    }
}
