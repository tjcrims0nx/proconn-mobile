package com.proconn.mobile.store

import android.content.Context

/**
 * Persists overlay positioning preferences. The floating crosshair always
 * sits at the saved anchor (OverlayStore.DEFAULT_ANCHOR_X/Y, the calibrated
 * CODM game-crosshair position, overridable by dragging in "Drag to position"
 * mode) — matching the game's own crosshair — and is untouchable outside
 * drag mode.
 */
object OverlayStore {
    private const val PREFS = "overlay_prefs"
    private const val K_CENTERED_V15 = "centered_v15"
    private const val K_ANCHOR_X = "anchor_x"
    private const val K_ANCHOR_Y = "anchor_y"

    /**
     * Built-in default anchor = calibrated CODM game-crosshair position:
     * mean of 4 screenshot measurements (522, 520, 529, 532 in 0-1000 coords).
     * Dragging the crosshair ("Drag to position") overrides these; the saved
     * anchor persists across restarts.
     */
    const val DEFAULT_ANCHOR_X = 0.526f
    const val DEFAULT_ANCHOR_Y = 0.5f

    private fun prefs(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Current crosshair anchor as screen fractions (saved drag position or default). */
    fun getAnchor(c: Context): Pair<Float, Float> =
        prefs(c).getFloat(K_ANCHOR_X, DEFAULT_ANCHOR_X) to
                prefs(c).getFloat(K_ANCHOR_Y, DEFAULT_ANCHOR_Y)

    fun setAnchor(c: Context, x: Float, y: Float) {
        prefs(c).edit().putFloat(K_ANCHOR_X, x).putFloat(K_ANCHOR_Y, y).apply()
    }

    /** First v1.5 run: ignore any legacy saved position and center instead. */
    fun needsV15Centering(c: Context): Boolean =
        !prefs(c).getBoolean(K_CENTERED_V15, false)

    fun markV15Centered(c: Context) {
        prefs(c).edit().putBoolean(K_CENTERED_V15, true).apply()
    }
}
