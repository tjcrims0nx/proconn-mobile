package com.proconn.mobile.store

import android.content.Context

/**
 * Persists the ADS crosshair-effect settings (shrink + pulse) and the
 * Controller ADS sync configuration. Historically also held the removed
 * one-tap button settings; the preference file name is kept so existing
 * effect settings survive the upgrade.
 */
object TapStore {
    private const val PREFS = "one_tap"
    private const val K_SHRINK_ADS = "shrink_on_ads"
    private const val K_BREATHING = "breathing_pulse"
    private const val K_CONTROLLER_ADS_SYNC = "controller_ads_sync"
    private const val K_CONTROLLER_ADS_KEY = "controller_ads_key"
    private const val K_GAME_ID = "game_profile_id"
    private const val K_GAME_AUTO = "game_auto_detect"
    private const val K_GAME_DETECTED = "game_detected_id"

    private fun prefs(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "Shrink on ADS": the crosshair tightens while ADS is active. Default ON. */
    fun isShrinkOnAds(c: Context): Boolean =
        prefs(c).getBoolean(K_SHRINK_ADS, true)

    fun setShrinkOnAds(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_SHRINK_ADS, v).apply()
    }

    /** "Pulse while ADS": subtle pulse that runs only while ADS is active. Default OFF. */
    fun isBreathingPulse(c: Context): Boolean =
        prefs(c).getBoolean(K_BREATHING, false)

    fun setBreathingPulse(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_BREATHING, v).apply()
    }

    /**
     * "Controller ADS sync": a learned controller button drives the ADS
     * state (true only while the button is physically held). Default OFF.
     */
    fun isControllerAdsSync(c: Context): Boolean =
        prefs(c).getBoolean(K_CONTROLLER_ADS_SYNC, false)

    fun setControllerAdsSync(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_CONTROLLER_ADS_SYNC, v).apply()
    }

    /**
     * KeyCode learned for Controller ADS sync; 0 = none learned yet.
     * Learned via the accessibility service's key-event filter.
     */
    fun getControllerAdsKey(c: Context): Int =
        prefs(c).getInt(K_CONTROLLER_ADS_KEY, 0)

    fun setControllerAdsKey(c: Context, v: Int) {
        prefs(c).edit().putInt(K_CONTROLLER_ADS_KEY, v).apply()
    }

    /**
     * Selected game profile id for per-game aim tuning. Default "codm".
     * A foreground-detection event overrides this while auto-detect is on.
     */
    fun getGameId(c: Context): String =
        prefs(c).getString(K_GAME_ID, "codm") ?: "codm"

    fun setGameId(c: Context, v: String) {
        prefs(c).edit().putString(K_GAME_ID, v).apply()
    }

    /** Auto-detect the foreground game and switch profiles. Default ON. */
    fun isGameAutoDetect(c: Context): Boolean =
        prefs(c).getBoolean(K_GAME_AUTO, true)

    fun setGameAutoDetect(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_GAME_AUTO, v).apply()
    }

    /**
     * Profile id of the last game seen in the foreground ("" = none yet).
     * Written by the accessibility service; shown as "Detected: <game>".
     */
    fun getGameDetected(c: Context): String =
        prefs(c).getString(K_GAME_DETECTED, "") ?: ""

    fun setGameDetected(c: Context, v: String) {
        prefs(c).edit().putString(K_GAME_DETECTED, v).apply()
    }
}
