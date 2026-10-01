package com.proconn.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.proconn.mobile.R
import com.proconn.mobile.root.RootHelper
import com.proconn.mobile.store.OverlayStore
import com.proconn.mobile.store.TapStore
import com.proconn.mobile.ui.DynamicEffects
import com.proconn.mobile.ui.OverlayView

/**
 * Floating, vsync-synced crosshair overlay. Runs as a foreground service with
 * a persistent notification (Hide / Show actions).
 *
 * ADS state is controller-driven only: true while the learned controller ADS
 * button is physically held (mirrored from the accessibility service's
 * system-wide key-event filter; "Controller ADS sync" must be on), OR while
 * the root shaping daemon reports the physical left trigger past its ADS
 * threshold. The ADS shrink and the pulse follow this state.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.proconn.mobile.START_OVERLAY"
        const val ACTION_STOP = "com.proconn.mobile.STOP_OVERLAY"
        const val ACTION_CENTER_CROSSHAIR = "com.proconn.mobile.CENTER_CROSSHAIR"
        const val ACTION_HIDE_VIEWS = "com.proconn.mobile.HIDE_VIEWS"
        const val ACTION_SHOW_VIEWS = "com.proconn.mobile.SHOW_VIEWS"
        const val ACTION_REFRESH_EFFECTS = "com.proconn.mobile.REFRESH_EFFECTS"
        const val ACTION_SET_DRAGGABLE = "com.proconn.mobile.SET_DRAGGABLE"
        const val EXTRA_DRAGGABLE = "draggable"
        const val CHANNEL_ID = "proconn_overlay"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var instance: OverlayService? = null
            private set
        /**
         * Drag-to-position mode for the crosshair. Never persisted — the
         * service always starts with this OFF (locked, untouchable).
         */
        @Volatile var isDraggable: Boolean = false
            private set
        @Volatile var refreshRateHz: Float = 0f
            private set
        /**
         * Whether the crosshair view is currently attached. The service
         * (and its notification) can stay alive while hidden.
         */
        @Volatile var overlayVisible: Boolean = true

        /**
         * Built-in default anchor for the crosshair, measured from the user's
         * CODM screenshots (2026-09-30): mean of 4 measurements (522, 520,
         * 529, 532 in 0-1000 coords) → x ≈ 0.526 of screen width, y ≈ 0.500
         * of height — not at geometric center. The user can drag the
         * crosshair to a new anchor ("Drag to position"); these are the
         * built-in defaults restored by a fresh install.
         */
        const val GAME_CROSSHAIR_X = 0.526f
        const val GAME_CROSSHAIR_Y = 0.5f

        /**
         * ADS state, controller-driven only: true while the learned
         * controller ADS button is physically held (mirrored from the
         * accessibility service's system-wide key-event filter).
         * The ADS shrink and the pulse follow this state. Without root,
         * a background overlay cannot observe analog trigger axes, so
         * this is only visible when the controller emits digital button
         * events — with root, see rootAdsHeld below.
         */
        @Volatile var controllerHeld: Boolean = false
            private set
        /**
         * ADS state from the root shaping daemon: true while the physical
         * left trigger is pulled past its ADS threshold, read straight
         * from the controller's evdev node (so analog-only triggers
         * work). OR'd with controllerHeld — either source drives the
         * ADS shrink and the pulse.
         */
        @Volatile var rootAdsHeld: Boolean = false
            private set
        val adsActive: Boolean get() = controllerHeld || rootAdsHeld

        fun overlayPermissionIntent(c: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

        fun a11ySettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private var wm: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null

    // ---------- root daemon ADS watcher ----------
    //
    // The daemon publishes the analog trigger's ADS state to a plain file
    // in our storage (no root needed to read it). A FileObserver on the
    // files dir gives us event-driven updates; a slow background check
    // confirms the daemon is still alive so a stale "1" can't stick the
    // crosshair in ADS after the daemon dies.
    private val adsWatcherHandler = Handler(Looper.getMainLooper())
    private var adsFileObserver: FileObserver? = null

    @Volatile private var daemonAlive = false

    private val daemonCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            Thread {
                val alive = try {
                    RootHelper.isDaemonRunning(this@OverlayService)
                } catch (e: Exception) {
                    false
                }
                daemonAlive = alive
                if (!alive && rootAdsHeld) {
                    rootAdsHeld = false
                    adsWatcherHandler.post { refreshAdsVisuals() }
                }
            }.start()
            adsWatcherHandler.postDelayed(this, 2000)
        }
    }

    private fun startAdsWatch() {
        stopAdsWatch()
        @Suppress("DEPRECATION")
        adsFileObserver = object : FileObserver(
            filesDir.absolutePath,
            FileObserver.CREATE or FileObserver.MODIFY or
                FileObserver.CLOSE_WRITE or FileObserver.DELETE or
                FileObserver.MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != RootHelper.STATE_FILE || !daemonAlive) return
                // FileObserver callbacks are NOT on the main thread.
                val held = RootHelper.readAdsState(this@OverlayService)
                if (held != rootAdsHeld) {
                    rootAdsHeld = held
                    adsWatcherHandler.post { refreshAdsVisuals() }
                }
            }
        }.also {
            try {
                it.startWatching()
            } catch (e: Exception) {
                // ignore — the slow check still clears stale state
            }
        }
        adsWatcherHandler.post(daemonCheck)
    }

    private fun stopAdsWatch() {
        adsWatcherHandler.removeCallbacks(daemonCheck)
        try {
            adsFileObserver?.stopWatching()
        } catch (e: Exception) {
            // ignore
        }
        adsFileObserver = null
        daemonAlive = false
        rootAdsHeld = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlay()
                return START_NOT_STICKY
            }
            ACTION_CENTER_CROSSHAIR -> {
                centerCrosshair()
                return START_STICKY
            }
            ACTION_HIDE_VIEWS -> {
                hideViews()
                return START_STICKY
            }
            ACTION_SHOW_VIEWS -> {
                ensureRunning()
                showViews()
                return START_STICKY
            }
            ACTION_REFRESH_EFFECTS -> {
                updateAdsEffects()
                return START_STICKY
            }
            ACTION_SET_DRAGGABLE -> {
                setDraggable(intent.getBooleanExtra(EXTRA_DRAGGABLE, false))
                return START_STICKY
            }
        }
        startOverlay()
        updateAdsEffects()
        return START_STICKY
    }

    private fun ensureRunning() {
        if (!isRunning) startOverlay()
    }

    // ---------- overlay ----------

    private fun startOverlay() {
        if (isRunning) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, notification)
        }

        refreshRateHz = currentRefreshRate()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // v1.7+: the crosshair anchors to the calibrated game-crosshair
        // position (GAME_CROSSHAIR_X/Y), like the game's own crosshair.
        // Ignore any legacy saved position on first v1.5 run.
        if (OverlayStore.needsV15Centering(this)) {
            OverlayStore.markV15Centered(this)
        }
        // Drag mode is never persisted: always start locked.
        isDraggable = false
        showViews()
        isRunning = true
        startAdsWatch()
    }

    /**
     * (Re)attaches the crosshair view. The crosshair window is
     * WRAP_CONTENT around the crosshair itself — never full-screen — and
     * FLAG_NOT_TOUCHABLE unless drag-to-position mode is on: purely visual,
     * it can never block touches during gameplay. The crosshair sits at the
     * saved anchor and is only movable while dragging.
     */
    private fun showViews() {
        val w = wm ?: return
        if (overlayView == null) {
            // Fresh view with no saved drag position: start at the calibrated
            // game-crosshair anchor (a drag offset from a previous session is
            // preserved via lastOffsetX/Y instead).
            if (lastOffsetX == 0 && lastOffsetY == 0) {
                val (ax, ay) = gameAnchorOffset()
                lastOffsetX = ax
                lastOffsetY = ay
            }
            overlayView = OverlayView(this)
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                x = lastOffsetX
                y = lastOffsetY
            }
            try {
                w.addView(overlayView, params)
            } catch (e: Exception) {
                // ignore
            }
        }
        overlayVisible = true
        centerCrosshair()
        applyFlags()
        updateAdsEffects()
        refreshNotification()
    }

    /**
     * Detaches the crosshair view but keeps the service (and its
     * notification) alive, so the overlay can be hidden/shown instantly.
     */
    private fun hideViews() {
        val w = wm ?: return
        params?.let {
            lastOffsetX = it.x
            lastOffsetY = it.y
        }
        try {
            overlayView?.let { w.removeView(it) }
        } catch (e: Exception) {
            // ignore
        }
        overlayView = null
        params = null
        overlayVisible = false
        refreshNotification()
    }

    private var lastOffsetX = 0
    private var lastOffsetY = 0

    private fun currentRefreshRate(): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.refreshRate ?: 0f
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
            }
        } catch (e: Exception) {
            0f
        }
    }

    private fun applyFlags() {
        val p = params ?: return
        val v = overlayView ?: return
        // Locked (default): purely visual, never touchable. Drag mode:
        // touchable but still never focusable.
        p.flags = if (isDraggable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        v.setOnTouchListener(if (isDraggable) dragListener else null)
        try {
            wm?.updateViewLayout(v, p)
        } catch (e: Exception) {
            // view not attached; ignore
        }
    }

    /** Move the crosshair to the saved anchor. */
    private fun centerCrosshair() {
        val p = params ?: return
        val v = overlayView ?: return
        val (ax, ay) = gameAnchorOffset()
        p.x = ax
        p.y = ay
        try {
            wm?.updateViewLayout(v, p)
        } catch (e: Exception) {
            // view not attached; ignore
        }
    }

    /**
     * Drag-to-position mode, toggled by the single "Drag to position" switch
     * in the Crosshair tab. When ON the crosshair window becomes touchable
     * (still not focusable) and can be dragged by hand; releasing saves the
     * new anchor to OverlayStore. When OFF the window is FLAG_NOT_TOUCHABLE
     * again — purely visual, zero touch interference during gameplay.
     */
    fun setDraggable(enabled: Boolean) {
        isDraggable = enabled
        val p = params ?: return
        val v = overlayView ?: return
        p.flags = if (enabled) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        v.setOnTouchListener(if (enabled) dragListener else null)
        try {
            wm?.updateViewLayout(v, p)
        } catch (e: Exception) {
            // view not attached; ignore
        }
    }

    private var dragGrabDX = 0f
    private var dragGrabDY = 0f

    /** Drag the crosshair window; on release, persist the new anchor. */
    private val dragListener = View.OnTouchListener { v, ev ->
        val p = params ?: return@OnTouchListener false
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                dragGrabDX = p.x - ev.rawX
                dragGrabDY = p.y - ev.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = (ev.rawX + dragGrabDX).toInt()
                p.y = (ev.rawY + dragGrabDY).toInt()
                try {
                    wm?.updateViewLayout(v, p)
                } catch (e: Exception) {
                    // ignore
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Window offsets are relative to Gravity.CENTER; convert the
                // final position back to anchor fractions and save.
                val dm = resources.displayMetrics
                val ax = (p.x / dm.widthPixels.toFloat() + 0.5f).coerceIn(0f, 1f)
                val ay = (p.y / dm.heightPixels.toFloat() + 0.5f).coerceIn(0f, 1f)
                OverlayStore.setAnchor(this, ax, ay)
                true
            }
            else -> false
        }
    }

    /**
     * Pixel offsets (from Gravity.CENTER) of the crosshair anchor — the
     * saved position, or the built-in calibrated game-crosshair default
     * (OverlayStore) when never moved.
     */
    private fun gameAnchorOffset(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val (ax, ay) = OverlayStore.getAnchor(this)
        return ((ax - 0.5f) * dm.widthPixels).toInt() to
                ((ay - 0.5f) * dm.heightPixels).toInt()
    }

    // ---------- ADS effects ----------

    /**
     * Pushes the dynamic crosshair-effect settings (ADS shrink +
     * pulse-while-ADS) and the current controller-driven ADS state into
     * the overlay view.
     */
    private fun updateAdsEffects() {
        if (!isRunning) return
        overlayView?.apply {
            breathingPulse = TapStore.isBreathingPulse(this@OverlayService)
            // If the shrink was turned off, make sure the ADS target
            // scale follows the plain (non-shrunk) state.
            adsActive = OverlayService.adsActive && TapStore.isShrinkOnAds(this@OverlayService)
            adsTarget =
                if (adsActive) DynamicEffects.ADS_TARGET_SCALE
                else DynamicEffects.ADS_RELEASE_SCALE
        }
        refreshNotification()
    }

    /**
     * Called by TapAccessibilityService (same process, main thread) when the
     * learned controller ADS button is pressed/released. Feeds the
     * controller-driven ADS state.
     */
    fun setControllerHeld(held: Boolean) {
        if (controllerHeld == held) return
        controllerHeld = held
        refreshAdsVisuals()
    }

    /** Push the controller-driven ADS state into the overlay view. */
    private fun refreshAdsVisuals() {
        val a = this
        val shrinkOn = TapStore.isShrinkOnAds(a)
        val active = adsActive
        overlayView?.adsActive = active
        overlayView?.adsTarget =
            if (active && shrinkOn) DynamicEffects.ADS_TARGET_SCALE
            else DynamicEffects.ADS_RELEASE_SCALE
    }

    // ---------- notification ----------

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Crosshair overlay", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val visible = overlayVisible
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java)
                .setAction(if (visible) ACTION_HIDE_VIEWS else ACTION_SHOW_VIEWS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ProConn Mobile overlay active")
            .setContentText(
                if (visible) "Crosshair is on screen. Tap Hide to remove it anytime."
                else "Overlay hidden. Tap Show to bring it back."
            )
            .setSmallIcon(R.drawable.ic_crosshair)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_crosshair),
                    if (visible) "Hide" else "Show", toggleIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        if (!isRunning) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification())
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- teardown ----------

    private fun stopOverlay() {
        try {
            overlayView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // ignore
        }
        overlayView = null
        params = null
        isRunning = false
        controllerHeld = false
        stopAdsWatch()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            overlayView?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // ignore
        }
        stopAdsWatch()
        isRunning = false
        instance = null
        super.onDestroy()
    }
}
