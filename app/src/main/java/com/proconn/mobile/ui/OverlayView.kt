package com.proconn.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.proconn.mobile.model.CrosshairRenderer
import com.proconn.mobile.store.DesignStore
import kotlin.math.min

/**
 * The floating overlay view. Redraws on every vsync via Choreographer so the
 * crosshair stays screen-synced. Reads the live design from DesignStore so
 * designer edits apply instantly.
 *
 * Dynamic effects: the ADS shrink target is toggled by OverlayService each
 * while the learned controller ADS button is held (animated smoothly per frame), and the optional
 * pulse runs only while ADS is active — driven by the Choreographer frame
 * time but gated on adsActive. Both combine multiplicatively into a single
 * dynamic scale, matching the desktop ProConn ADS-gated pulse.
 */
class OverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** Pulse-while-ADS on/off (set by OverlayService from TapStore). */
    var breathingPulse: Boolean = false

    /** Whether ADS is currently active (set by OverlayService). Gates the pulse. */
    var adsActive: Boolean = false

    /** ADS shrink animation target (set by OverlayService). */
    var adsTarget: Float = DynamicEffects.ADS_RELEASE_SCALE

    private var adsScale: Float = DynamicEffects.ADS_RELEASE_SCALE
    private var lastFrameNanos: Long = 0L

    companion object {
        /**
         * Render scale in px per design unit. 1.5 matches the pre-v1.5 fixed
         * 300dp window (300/200), so the crosshair renders at exactly the
         * same absolute size as before — only the window is now tight.
         */
        private const val RENDER_SCALE = 1.5f
    }

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            lastFrameNanos = frameTimeNanos
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        // Hardware-accelerated layers keep the per-frame draw cheap.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    /**
     * Measure tightly around the current design so the overlay window
     * (WRAP_CONTENT) never covers more screen than the crosshair itself.
     * Covers every style: worst-case half-extent is gap + length + thickness,
     * plus margin for outline, round caps and the breathing pulse.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val px = desiredPx()
        setMeasuredDimension(px, px)
    }

    private fun desiredPx(): Int {
        val d = DesignStore.current
        val density = resources.displayMetrics.density
        val extentUnits = d.gap + d.length + d.thickness + 16f
        // ~24dp of transparent padding around the crosshair: a generous grab
        // area for drag-to-position mode, symmetric so the crosshair stays
        // centered in its window.
        val halfPx = extentUnits * RENDER_SCALE * density * 1.06f + 24f * density
        return (halfPx * 2).toInt().coerceAtLeast((64 * density).toInt())
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // The design can change live from the designer tab; remeasure if so.
        if (desiredPx() != width) {
            requestLayout()
        }
        val scale = RENDER_SCALE * resources.displayMetrics.density
        adsScale = DynamicEffects.smooth(adsScale, adsTarget)
        val timeSec = lastFrameNanos / 1_000_000_000.0
        val dynamicScale = DynamicEffects.combined(adsScale, adsActive, breathingPulse, timeSec)
        CrosshairRenderer.draw(canvas, DesignStore.current, width / 2f, height / 2f, scale, dynamicScale)
    }
}
