package com.proconn.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.proconn.mobile.model.CrosshairDesign
import com.proconn.mobile.model.CrosshairRenderer
import kotlin.math.min

/**
 * Designer preview for a crosshair. Design space is 200 units wide.
 *
 * When preview animation is on, the same dynamic effects as the floating
 * overlay are simulated: the ADS shrink toggles on a timer (standing in for controller ADS holds
 * combined multiplicatively through the same [DynamicEffects] math.
 */
class CrosshairView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var design: CrosshairDesign = CrosshairDesign()

    /** Effect toggles mirrored from the DYNAMIC EFFECTS section. */
    var previewShrinkOnAds: Boolean = false
    var previewBreathing: Boolean = false

    private var previewAdsScale = DynamicEffects.ADS_RELEASE_SCALE
    private var previewRunning = false
    private var lastFrameNanos = 0L

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!previewRunning) return
            lastFrameNanos = frameTimeNanos
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    fun refresh() = invalidate()

    /** Start/stop the animated effects demo. Safe to call repeatedly. */
    fun setPreviewAnimated(on: Boolean) {
        if (on == previewRunning) return
        previewRunning = on
        if (on) {
            previewAdsScale = DynamicEffects.ADS_RELEASE_SCALE
            choreographer.postFrameCallback(frameCallback)
        } else {
            choreographer.removeFrameCallback(frameCallback)
            previewAdsScale = DynamicEffects.ADS_RELEASE_SCALE
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        setPreviewAnimated(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 200f
        val dynamicScale = if (previewRunning) {
            val timeSec = lastFrameNanos / 1_000_000_000.0
            // Simulate controller-driven ADS toggling every ~1.6s; the pulse is
            // visible only during the simulated ADS phases (desktop behavior).
            val adsNow =
                previewShrinkOnAds && ((timeSec / 1.6).toLong() % 2L == 0L)
            val target =
                if (adsNow) DynamicEffects.ADS_TARGET_SCALE
                else DynamicEffects.ADS_RELEASE_SCALE
            previewAdsScale = DynamicEffects.smooth(previewAdsScale, target)
            DynamicEffects.combined(previewAdsScale, adsNow, previewBreathing, timeSec)
        } else {
            1f
        }
        CrosshairRenderer.draw(canvas, design, width / 2f, height / 2f, scale, dynamicScale)
    }
}
