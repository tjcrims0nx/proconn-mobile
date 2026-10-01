package com.proconn.mobile.fragments

import android.app.Fragment
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.proconn.mobile.R
import com.proconn.mobile.bluetooth.BluetoothHelper
import com.proconn.mobile.model.GameProfiles
import com.proconn.mobile.service.TapAccessibilityService
import com.proconn.mobile.store.TapStore
import com.proconn.mobile.ui.CurveView
import com.proconn.mobile.ui.StickView
import kotlin.math.hypot
import kotlin.math.pow

@Suppress("DEPRECATION")
class TunerFragment : Fragment() {

    enum class CurveKind { LINEAR, AGGRESSIVE, DYNAMIC, PRECISE, CUSTOM }

    private var stickL: StickView? = null
    private var stickR: StickView? = null
    private var axesL: TextView? = null
    private var axesR: TextView? = null
    private var padStatus: TextView? = null
    private var measureStatus: TextView? = null
    private var dzResult: TextView? = null
    private var curveView: CurveView? = null
    private var recommendations: TextView? = null
    private var aimLabel: TextView? = null

    private var curveKind = CurveKind.LINEAR
    private var sharpness = 0.5f // 0..1
    private var deadzone = 0f
    private var measuredDeadzone: Float? = null
    private var damping = 0f // 0..1 aim smoothing
    private var smoothedMag = 0f
    private var aimDial = 50

    private var sharpBar: SeekBar? = null
    private var aimBar: SeekBar? = null
    private var dzBar: SeekBar? = null
    private var dampBar: SeekBar? = null
    private var dzLabel: TextView? = null
    private var dampLabel: TextView? = null

    // Game profile card
    private var gameChips: LinearLayout? = null
    private var gameCurrent: TextView? = null
    private var gameDetected: TextView? = null
    private var gameNote: TextView? = null
    private var gameAuto: Switch? = null
    private var bestProfileLabel: TextView? = null
    private var installedGames: Set<String> = emptySet()
    private var selectedGameId: String = "codm"
    private var detectedGameId: String? = null

    // Bluetooth card
    private var btDot: View? = null
    private var btStatus: TextView? = null
    private var btHint: TextView? = null
    private var btDevices: LinearLayout? = null
    private var btEnableBtn: Button? = null
    private var btPermsRequested = false
    private var pendingPair = false

    // Live Bluetooth state: receiver registered in onResume, unregistered in onPause.
    private var btReceiverRegistered = false
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // onReceive runs on the main thread — refresh the card immediately.
            refreshBluetooth()
        }
    }

    private val pressed = mutableSetOf<Int>()
    private val axisPressed = mutableSetOf<Int>()
    private val buttonDots = mutableMapOf<Int, TextView>()

    // deadzone sampling
    private var sampling = false
    private val samples = mutableListOf<Pair<Long, Float>>()
    private val handler = Handler(Looper.getMainLooper())

    private val buttonDefs = listOf(
        KeyEvent.KEYCODE_BUTTON_A to "A",
        KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X",
        KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "LB",
        KeyEvent.KEYCODE_BUTTON_R1 to "RB",
        KeyEvent.KEYCODE_BUTTON_L2 to "LT",
        KeyEvent.KEYCODE_BUTTON_R2 to "RT",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_DPAD_UP to "↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "→",
        KeyEvent.KEYCODE_BUTTON_START to "Start",
        KeyEvent.KEYCODE_BUTTON_SELECT to "Back"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_tuner, container, false)

        stickL = v.findViewById<StickView>(R.id.stick_l).apply { label = "L" }
        stickR = v.findViewById<StickView>(R.id.stick_r).apply { label = "R" }
        axesL = v.findViewById(R.id.axes_l)
        axesR = v.findViewById(R.id.axes_r)
        padStatus = v.findViewById(R.id.pad_status)
        measureStatus = v.findViewById(R.id.measure_status)
        dzResult = v.findViewById(R.id.dz_result)
        curveView = v.findViewById(R.id.curve_view)
        recommendations = v.findViewById(R.id.recommendations)
        aimLabel = v.findViewById(R.id.lbl_aim)

        v.findViewById<Button>(R.id.btn_refresh_pads).setOnClickListener { refreshGamepads() }
        refreshGamepads()

        // Bluetooth card
        btDot = v.findViewById(R.id.bt_dot)
        btStatus = v.findViewById(R.id.bt_status)
        btHint = v.findViewById(R.id.bt_hint)
        btDevices = v.findViewById(R.id.bt_devices)
        btEnableBtn = v.findViewById<Button>(R.id.btn_bt_enable).apply {
            setOnClickListener { activity?.let { BluetoothHelper.requestEnable(it) } }
        }
        v.findViewById<Button>(R.id.btn_bt_pair).setOnClickListener { onPairClicked() }
        refreshBluetooth()

        v.findViewById<Button>(R.id.btn_measure).setOnClickListener { startMeasurement() }

        buildCurveRow(v)
        sharpBar = v.findViewById<SeekBar>(R.id.seek_sharp)
        sharpBar?.setOnSeekBarChangeListener(simpleSeek { p ->
            sharpness = p / 100f
            if (curveKind != CurveKind.CUSTOM) { curveKind = CurveKind.CUSTOM; buildCurveRow(v) }
            refreshCurve()
        })
        aimBar = v.findViewById<SeekBar>(R.id.seek_aim)
        aimBar?.setOnSeekBarChangeListener(simpleSeek { p ->
            aimDial = p
            aimLabel?.text = "Aim dial: $p"
            sharpness = p / 100f
            curveKind = CurveKind.CUSTOM
            sharpBar?.progress = p
            buildCurveRow(v)
            refreshCurve()
            updateRecommendations()
        })

        buildButtonGrid(v)
        refreshCurve()
        updateRecommendations()

        // ---------- aim tuning ----------
        dzLabel = v.findViewById(R.id.lbl_dz)
        dampLabel = v.findViewById(R.id.lbl_damp)
        dzBar = v.findViewById<SeekBar>(R.id.seek_dz).apply {
            progress = (deadzone * 100).toInt().coerceIn(0, 20)
        }
        dampBar = v.findViewById<SeekBar>(R.id.seek_damp).apply {
            progress = (damping * 100).toInt().coerceIn(0, 100)
        }
        updateAimTuningLabels()
        dzBar?.setOnSeekBarChangeListener(simpleSeek { p ->
            deadzone = p / 100f
            updateAimTuningLabels()
            refreshCurve()
            updateRecommendations()
        })
        dampBar?.setOnSeekBarChangeListener(simpleSeek { p ->
            damping = p / 100f
            updateAimTuningLabels()
            refreshCurve()
            updateRecommendations()
        })
        v.findViewById<Button>(R.id.btn_best_aim).setOnClickListener { applyBestAim() }

        // ---------- game profile card ----------
        gameChips = v.findViewById(R.id.game_chips)
        gameCurrent = v.findViewById(R.id.game_current)
        gameDetected = v.findViewById(R.id.game_detected)
        gameNote = v.findViewById(R.id.game_note)
        bestProfileLabel = v.findViewById(R.id.lbl_best_profile)
        gameAuto = v.findViewById(R.id.game_auto)
        return v
    }

    // ---------- bluetooth ----------

    override fun onResume() {
        super.onResume()
        val act = activity
        if (act != null && !btReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    act.registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    act.registerReceiver(btReceiver, filter)
                }
                btReceiverRegistered = true
            } catch (e: Exception) {
                // Receiver registration failed — the card still refreshes in onResume.
            }
        }
        refreshBluetooth()
        refreshGameCardState()
    }

    override fun onPause() {
        if (TapAccessibilityService.gameListener != null) {
            TapAccessibilityService.gameListener = null
        }
        if (btReceiverRegistered) {
            try {
                activity?.unregisterReceiver(btReceiver)
            } catch (e: Exception) {
            }
            btReceiverRegistered = false
        }
        super.onPause()
    }

    private fun onPairClicked() {
        val act = activity ?: return
        val missing = BluetoothHelper.missingPermissions(act)
        if (missing.isNotEmpty()) {
            pendingPair = true
            btPermsRequested = true
            requestPermissions(missing, BluetoothHelper.REQ_BT_PERMS)
            return
        }
        btHint?.visibility = View.GONE
        BluetoothHelper.startPairing(act)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode != BluetoothHelper.REQ_BT_PERMS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        btHint?.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            refreshBluetooth()
            if (pendingPair) {
                pendingPair = false
                activity?.let { BluetoothHelper.startPairing(it) }
            }
        }
        pendingPair = false
    }

    /** Public: MainActivity calls this after the BT enable / pairing activities return. */
    fun refreshBluetooth() {
        val act = activity ?: return
        if (!BluetoothHelper.isSupported(act)) {
            setBtHeader(false, "Controller disconnected – no Bluetooth on this device")
            btEnableBtn?.visibility = View.GONE
            btDevices?.removeAllViews()
            return
        }
        val on = BluetoothHelper.isEnabled(act)
        btEnableBtn?.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) {
            setBtHeader(false, "Controller disconnected – Bluetooth is off")
            btDevices?.removeAllViews()
            return
        }

        // Ask once for runtime permissions when the card first appears.
        if (!btPermsRequested) {
            val missing = BluetoothHelper.missingPermissions(act)
            if (missing.isNotEmpty()) {
                btPermsRequested = true
                requestPermissions(missing, BluetoothHelper.REQ_BT_PERMS)
            }
        }

        if (BluetoothHelper.missingPermissions(act).isNotEmpty()) {
            setBtHeader(false, "Controller disconnected – pair via Bluetooth")
            btDevices?.removeAllViews()
            return
        }
        BluetoothHelper.refreshDevices(act) { infos ->
            if (!isAdded) return@refreshDevices
            renderBtDevices(infos)
        }
    }

    private fun setBtHeader(connected: Boolean, text: String) {
        btStatus?.text = text
        btDot?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (connected) "#34D399" else "#F87171")
        )
    }

    private fun renderBtDevices(infos: List<BluetoothHelper.BtDeviceInfo>) {
        val list = btDevices ?: return
        val ctx: Context = activity ?: return
        list.removeAllViews()

        val connected = infos.firstOrNull { it.isConnected }
        if (connected != null) {
            setBtHeader(true, "Controller connected – ${connected.name}")
        } else {
            setBtHeader(false, "Controller disconnected – pair via Bluetooth")
        }

        if (infos.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "No paired controllers yet — tap PAIR CONTROLLER."
                textSize = 13f
                setTextColor(Color.parseColor("#8A7DB5"))
            })
            return
        }
        val density = resources.displayMetrics.density
        for (info in infos) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                text = (if (info.isGamepad) "🎮 " else "") + info.name
                textSize = 14f
                setTextColor(Color.parseColor("#F5F2FC"))
            })
            textCol.addView(TextView(ctx).apply {
                text = info.address
                textSize = 12f
                setTextColor(Color.parseColor("#8A7DB5"))
            })
            row.addView(textCol)
            row.addView(TextView(ctx).apply {
                text = if (info.isConnected) "Connected" else "Paired"
                textSize = 12f
                setTextColor(
                    if (info.isConnected) Color.parseColor("#34D399")
                    else Color.parseColor("#8A7DB5")
                )
                background = ctx.getDrawable(R.drawable.bg_indicator_rounded)
                val p = (6 * density).toInt()
                setPadding((10 * density).toInt(), p, (10 * density).toInt(), p)
            })
            list.addView(row)
        }
    }

    // ---------- game profiles ----------

    /**
     * Re-read persisted game state and redraw the Game card. The
     * accessibility service is the source of truth (it persists
     * detections even while this UI is paused); the static listener
     * below only covers live updates while the UI is visible.
     */
    private fun refreshGameCardState() {
        val act = activity ?: return
        selectedGameId = TapStore.getGameId(act)
        detectedGameId = TapStore.getGameDetected(act).ifEmpty { null }
        gameAuto?.setOnCheckedChangeListener(null)
        gameAuto?.isChecked = TapStore.isGameAutoDetect(act)
        gameAuto?.setOnCheckedChangeListener { _, checked ->
            activity?.let { TapStore.setGameAutoDetect(it, checked) }
            // Re-arm detection so a game already in the foreground is
            // picked up on the next window event.
            TapAccessibilityService.instance?.resetForegroundTracking()
        }
        refreshInstalledGames()
        refreshGameCard()
        TapAccessibilityService.gameListener = { id ->
            if (isAdded) {
                selectedGameId = id
                detectedGameId = id
                refreshGameCard()
            }
        }
    }

    private fun refreshInstalledGames() {
        val pm = activity?.packageManager ?: return
        installedGames = GameProfiles.ALL.filter { p ->
            p.packageNames.any { pkg ->
                try {
                    pm.getApplicationInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }.map { it.id }.toSet()
    }

    private fun refreshGameCard() {
        val ctx: Context = activity ?: return
        val profile = GameProfiles.byId(selectedGameId)
        gameCurrent?.text = profile.displayName
        gameDetected?.text =
            "Detected: " + (detectedGameId?.let { GameProfiles.byId(it).displayName } ?: "—")
        gameNote?.text = profile.note
        bestProfileLabel?.text = "Applies the ${profile.shortName} profile"
        val chips = gameChips ?: return
        chips.removeAllViews()
        val density = resources.displayMetrics.density
        val margin = (4 * density).toInt()
        for (p in GameProfiles.ALL) {
            val selected = p.id == selectedGameId
            val installed = installedGames.contains(p.id)
            val b = Button(ctx).apply {
                text = (if (installed) "✓ " else "") + p.shortName
                textSize = 12f
                isAllCaps = false
                setTextColor(
                    Color.parseColor(
                        if (selected) "#FFFFFF"
                        else if (installed) "#F5F2FC" else "#8A7DB5"
                    )
                )
                background = ctx.getDrawable(
                    if (selected) R.drawable.bg_button_selected_rounded
                    else R.drawable.bg_button_rounded
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(margin, margin, margin, margin) }
            }
            b.setOnClickListener { selectGame(p.id) }
            chips.addView(b)
        }
    }

    /** Manual override: persists until auto-detect switches on the next game's foreground event. */
    private fun selectGame(id: String) {
        val act = activity ?: return
        selectedGameId = id
        TapStore.setGameId(act, id)
        refreshGameCard()
    }

    // ---------- gamepad listing ----------

    private fun refreshGamepads() {
        val pads = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter {
                !it.isVirtual && (
                    it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                    )
            }
        padStatus?.text = if (pads.isEmpty()) {
            "No controller detected — pair one in Bluetooth settings, then tap Refresh."
        } else {
            "Connected: " + pads.joinToString(", ") { it.name }
        }
    }

    // ---------- live input (called by MainActivity) ----------

    fun onGamepadMotion(event: MotionEvent) {
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        var rx = event.getAxisValue(MotionEvent.AXIS_Z)
        var ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (rx == 0f && ry == 0f) {
            rx = event.getAxisValue(MotionEvent.AXIS_RX)
            ry = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        stickL?.setPosition(lx, ly)
        stickR?.setPosition(rx, ry)
        axesL?.text = "L: %.2f, %.2f".format(lx, ly)
        axesR?.text = "R: %.2f, %.2f".format(rx, ry)

        val mag = hypot(rx, ry).coerceIn(0f, 1f)
        if (sampling) {
            samples.add(System.currentTimeMillis() to mag)
            measureStatus?.text = "Sampling… magnitude %.2f".format(mag)
        }
        // Exponential smoothing on the live dot; damping=0 means no smoothing.
        smoothedMag += (1f - damping) * (mag - smoothedMag)
        curveView?.liveX = smoothedMag
        curveView?.refresh()

        // Some controllers report d-pad and triggers as axes instead of keys.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        if (lt == 0f) lt = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        var rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (rt == 0f) rt = event.getAxisValue(MotionEvent.AXIS_GAS)
        setAxisButton(KeyEvent.KEYCODE_DPAD_LEFT, hatX < -0.5f)
        setAxisButton(KeyEvent.KEYCODE_DPAD_RIGHT, hatX > 0.5f)
        setAxisButton(KeyEvent.KEYCODE_DPAD_UP, hatY < -0.5f)
        setAxisButton(KeyEvent.KEYCODE_DPAD_DOWN, hatY > 0.5f)
        setAxisButton(KeyEvent.KEYCODE_BUTTON_L2, lt > 0.3f)
        setAxisButton(KeyEvent.KEYCODE_BUTTON_R2, rt > 0.3f)
    }

    /** Axis-derived button state; combined with key-event state when rendering. */
    private fun setAxisButton(keyCode: Int, down: Boolean) {
        val had = axisPressed.contains(keyCode)
        if (down == had) return
        if (down) axisPressed.add(keyCode) else axisPressed.remove(keyCode)
        renderButton(keyCode)
    }

    fun onGamepadKey(keyCode: Int, down: Boolean) {
        if (down) pressed.add(keyCode) else pressed.remove(keyCode)
        renderButton(keyCode)
    }

    private fun renderButton(keyCode: Int) {
        val down = pressed.contains(keyCode) || axisPressed.contains(keyCode)
        buttonDots[keyCode]?.let { dot ->
            dot.background = dot.context.getDrawable(
                if (down) R.drawable.bg_indicator_lit_rounded
                else R.drawable.bg_indicator_rounded
            )
            dot.setTextColor(if (down) Color.parseColor("#FFFFFF") else Color.parseColor("#8A7DB5"))
        }
    }

    // ---------- deadzone measurement ----------

    private fun startMeasurement() {
        if (sampling) return
        sampling = true
        samples.clear()
        dzResult?.text = "Deadzone: measuring…"
        measureStatus?.text = "Slowly tilt the RIGHT stick outward from center…"
        handler.postDelayed({
            sampling = false
            measuredDeadzone = estimateDeadzone(samples)
            deadzone = measuredDeadzone ?: 0f
            dzBar?.progress = (deadzone * 100).toInt().coerceIn(0, 20)
            updateAimTuningLabels()
            val dzPct = (deadzone * 100).toInt()
            dzResult?.text = "Deadzone: $dzPct%"
            measureStatus?.text = "Done — ${samples.size} samples"
            refreshCurve()
            updateRecommendations()
        }, 4000)
    }

    /** Smallest magnitude sustained above the 0.02 noise floor for >= 100 ms. */
    private fun estimateDeadzone(data: List<Pair<Long, Float>>): Float {
        if (data.size < 10) return 0f
        var best = 1f
        for (i in data.indices) {
            val cand = data[i].second
            if (cand < 0.02f || cand >= best) continue
            var j = i
            while (j < data.size && data[j].second >= cand - 0.005f &&
                (j == i || data[j].first - data[j - 1].first < 120)
            ) j++
            if (j - 1 > i && data[j - 1].first - data[i].first >= 100) best = cand
        }
        return if (best >= 1f) 0f else best
    }

    // ---------- curves ----------

    private fun applyDeadzone(x: Float): Float =
        ((x - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)

    private fun curveFn(x: Float): Float {
        val e = when (curveKind) {
            CurveKind.LINEAR -> 1f
            CurveKind.AGGRESSIVE -> 2.2f
            CurveKind.DYNAMIC -> 1.35f
            CurveKind.PRECISE -> 0.65f
            CurveKind.CUSTOM -> 0.4f + sharpness * 2.2f
        }
        return applyDeadzone(x).pow(e)
    }

    private fun refreshCurve() {
        curveView?.curve = ::curveFn
        curveView?.refresh()
    }

    // ---------- aim tuning ----------

    private fun updateAimTuningLabels() {
        dzLabel?.text = "Deadzone: ${(deadzone * 100).toInt()}%"
        dampLabel?.text = "Damping: ${(damping * 100).toInt()}%"
    }

    /**
     * One tap: applies the selected game's profile — its deadzone, damping,
     * response curve, and aim dial. CODM uses the measured deadzone (or 5%
     * when nothing was measured) with zero damping, Dynamic curve, aim 65.
     */
    private fun applyBestAim() {
        val act = activity ?: return
        val p = GameProfiles.byId(TapStore.getGameId(act))
        deadzone = if (p.useMeasuredDeadzone) measuredDeadzone ?: 0.05f else p.deadzone
        damping = p.damping
        curveKind = try {
            CurveKind.valueOf(p.curve)
        } catch (e: IllegalArgumentException) {
            CurveKind.DYNAMIC
        }
        aimDial = p.aimDial
        aimBar?.progress = aimDial
        aimLabel?.text = "Aim dial: $aimDial"
        sharpness = aimDial / 100f
        sharpBar?.progress = aimDial
        dzBar?.progress = (deadzone * 100).toInt().coerceIn(0, 20)
        dampBar?.progress = (damping * 100).toInt().coerceIn(0, 100)
        view?.let { buildCurveRow(it) }
        updateAimTuningLabels()
        refreshCurve()
        updateRecommendations()
    }

    private fun buildCurveRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.curve_row)
        row.removeAllViews()
        val ctx: Context = activity ?: return
        for (k in CurveKind.values()) {
            val b = Button(ctx).apply {
                text = k.name.lowercase().replaceFirstChar { it.uppercase() }
                textSize = 12f
                setTextColor(Color.parseColor("#F5F2FC"))
                background = ctx.getDrawable(
                    if (k == curveKind) R.drawable.bg_button_selected_rounded
                    else R.drawable.bg_button_rounded
                )
            }
            b.setOnClickListener { curveKind = k; refreshCurve(); buildCurveRow(root); updateRecommendations() }
            row.addView(b)
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ---------- button tester ----------

    private fun buildButtonGrid(root: View) {
        val grid = root.findViewById<GridLayout>(R.id.button_grid)
        grid.removeAllViews()
        val ctx: Context = activity ?: return
        val density = resources.displayMetrics.density
        for ((code, label) in buttonDefs) {
            val tv = TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.parseColor("#8A7DB5"))
                background = ctx.getDrawable(R.drawable.bg_indicator_rounded)
                setPadding(8, 20, 8, 20)
            }
            val px = (4 * density).toInt()
            tv.layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(px, px, px, px)
            }
            grid.addView(tv)
            buttonDots[code] = tv
        }
    }

    // ---------- recommendations ----------

    private fun updateRecommendations() {
        val dzPct = (deadzone * 100).toInt()
        val dzStr = "%.2f".format(deadzone)
        val curveName = curveKind.name.lowercase().replaceFirstChar { it.uppercase() }
        val smooth = when {
            aimDial < 34 -> "low smoothing — raw and responsive"
            aimDial < 67 -> "medium smoothing — balanced"
            else -> "higher smoothing — steadier micro-aim"
        }
        recommendations?.text =
            "• In-game deadzone: keep it 3–6% (measured $dzStr) — start near $dzPct%\n" +
                "• Aim smoothing: ${(damping * 100).toInt()}% damping on the live dot (0 = raw, responsive)\n" +
                "• Response curve: $curveName (aim dial $aimDial, $smooth) — match in-game Aim Response Curve: Dynamic\n" +
                "• Sensitivity: start medium-high standard, ~0.85x ADS multiplier, then adjust in 5% steps\n" +
                "• These are starting points — fine-tune in your game's settings."
    }
}
