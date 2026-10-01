package com.proconn.mobile.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.proconn.mobile.model.GameProfiles
import com.proconn.mobile.store.TapStore

/**
 * Accessibility service with one job:
 *
 * Controller ADS sync (opt-in): observe system-wide hardware KEY events
 * from gamepads via flagRequestFilterKeyEvents and mirror a learned
 * controller button into the ADS state. Analog trigger AXES are invisible
 * to background apps (Android security boundary — motion events go only
 * to the focused app), so this works with digital buttons; the in-app
 * Learn flow empirically tests the user's exact hardware.
 *
 * Key events are never consumed — onKeyEvent always returns false so the
 * game still receives every press. The service reads no screen content
 * and performs no gestures.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        /**
         * Learn-result callback: invoked with the learned keyCode, or null
         * when the 15s learn window expires with no button detected. Set by
         * the UI before calling [TapAccessibilityService.startLearnMode].
         * Runs on the main thread (same process as the UI).
         */
        @Volatile
        var learnListener: ((Int?) -> Unit)? = null

        /** How long Learn mode waits for a controller button press. */
        const val LEARN_TIMEOUT_MS = 15_000L

        /**
         * Foreground-game callback: invoked with the matched profile id when
         * a known game comes to the foreground. Set/cleared by the UI.
         * Runs on the main thread (same process as the UI).
         */
        @Volatile
        var gameListener: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var learnMode = false
    private val learnTimeout = Runnable { finishLearn(null) }
    /** Last foreground package seen — tracked for every app, not just games. */
    private var lastForegroundPkg: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelLearnMode()
        learnListener = null
        gameListener = null
        lastForegroundPkg = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Foreground-game detection: the config subscribes to
        // typeWindowStateChanged. When a known game package comes to the
        // foreground, report its profile id. Every foreground package is
        // tracked so leaving a game and coming back re-triggers detection.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastForegroundPkg) return
        lastForegroundPkg = pkg
        val profile = GameProfiles.byPackage(pkg) ?: return
        if (TapStore.isGameAutoDetect(this)) {
            // Persist directly: the UI may be paused while a game is
            // foregrounded, so the service is the source of truth. The
            // fragment re-reads these in onResume; the live listener
            // covers the rare case the UI is visible (e.g. split-screen).
            TapStore.setGameId(this, profile.id)
            TapStore.setGameDetected(this, profile.id)
            gameListener?.invoke(profile.id)
        }
    }

    override fun onInterrupt() {}

    /**
     * Enter Learn mode: the next gamepad button press (ACTION_DOWN,
     * no repeat) is captured as the user's ADS button. Times out after
     * [LEARN_TIMEOUT_MS] with a null result. Returns false if learn mode
     * is already active.
     */
    fun startLearnMode(): Boolean {
        if (learnMode) return false
        learnMode = true
        handler.removeCallbacks(learnTimeout)
        handler.postDelayed(learnTimeout, LEARN_TIMEOUT_MS)
        return true
    }

    /** Leave Learn mode silently (no listener notification). */
    fun cancelLearnMode() {
        learnMode = false
        handler.removeCallbacks(learnTimeout)
    }

    /**
     * Forget the last foreground package so the next window-state event
     * re-evaluates it (used when auto-detect is re-enabled mid-game).
     */
    fun resetForegroundTracking() {
        lastForegroundPkg = null
    }

    private fun finishLearn(keyCode: Int?) {
        if (!learnMode) return
        learnMode = false
        handler.removeCallbacks(learnTimeout)
        if (keyCode != null) {
            TapStore.setControllerAdsKey(this, keyCode)
        }
        learnListener?.invoke(keyCode)
    }

    /**
     * System-wide hardware key events (flagRequestFilterKeyEvents). Only
     * gamepad/joystick sources are considered. In Learn mode the first
     * button press is captured; otherwise a press/release of the learned
     * ADS button (when Controller ADS sync is on) drives the ADS state.
     * Never consumes the event.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val padMask = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        if (event.source and padMask == 0) return false

        if (learnMode) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                finishLearn(event.keyCode)
            }
            return false
        }

        if (!TapStore.isControllerAdsSync(this)) return false
        val learned = TapStore.getControllerAdsKey(this)
        if (learned == 0 || event.keyCode != learned) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN ->
                if (event.repeatCount == 0) OverlayService.instance?.setControllerHeld(true)
            KeyEvent.ACTION_UP ->
                OverlayService.instance?.setControllerHeld(false)
        }
        return false
    }
}
