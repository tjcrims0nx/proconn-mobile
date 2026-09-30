package com.proconn.mobile.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * Shared math for the dynamic crosshair effects, used by both the floating
 * overlay (OverlayView) and the designer preview (CrosshairView) so they
 * behave identically.
 *
 * Because a background overlay cannot observe live controller input while a
 * game is in the foreground, the ADS shrink is driven by the learned controller
 * ADS button: ADS is active only while that button is held.
 *
 * Like the desktop ProConn crosshair, the pulse is ADS-gated: it runs only
 * while ADS is active, tightening with the ADS scale.
 */
object DynamicEffects {
    /** Target scale while ADS is active (0.65 = 65% of normal size). */
    const val ADS_TARGET_SCALE = 0.65f

    /** Target scale when not aiming. */
    const val ADS_RELEASE_SCALE = 1.0f

    /** Per-frame smoothing factor for the ADS shrink/expand animation. */
    private const val SMOOTHING = 0.25f

    /** Breathing pulse amplitude (subtle: 5%). */
    private const val BREATH_AMPLITUDE = 0.05f

    /** Breathing pulse frequency in Hz. */
    private const val BREATH_HZ = 1.2

    /** Ease current toward target; call once per vsync frame. */
    fun smooth(current: Float, target: Float): Float =
        current + (target - current) * SMOOTHING

    /** ADS-gated pulse multiplier at the given time in seconds (subtle: ±5%). */
    fun breathing(timeSec: Double): Float =
        (1.0 + BREATH_AMPLITUDE * sin(2 * PI * BREATH_HZ * timeSec)).toFloat()

    /**
     * Combined dynamic multiplier: ADS scale times the optional pulse.
     * The pulse applies ONLY while ADS is active: while ADS the scale is
     * ADS_SCALE × breathing(t); otherwise it is exactly ADS_SCALE
     * (1.0 when not aiming). Mirrors the desktop ProConn ADS-gated pulse.
     */
    fun combined(adsScale: Float, adsActive: Boolean, breathingOn: Boolean, timeSec: Double): Float =
        adsScale * if (breathingOn && adsActive) breathing(timeSec) else 1f
}
