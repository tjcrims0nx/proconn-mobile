package com.proconn.mobile.fragments

import android.app.AlertDialog
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.proconn.mobile.R
import com.proconn.mobile.model.CrosshairDesign
import com.proconn.mobile.model.CrosshairStyle
import com.proconn.mobile.service.OverlayService
import com.proconn.mobile.service.TapAccessibilityService
import com.proconn.mobile.store.DesignStore
import com.proconn.mobile.store.TapStore
import com.proconn.mobile.ui.CrosshairView

@Suppress("DEPRECATION")
class CrosshairFragment : Fragment() {

    private var design: CrosshairDesign = CrosshairDesign()
    private var preview: CrosshairView? = null

    // Enterprise palette for programmatic UI (mirrors colors.xml).
    private val cText = Color.parseColor("#F5F2FC")
    private val cMuted = Color.parseColor("#8A7DB5")
    private val cSuccess = Color.parseColor("#34D399")

    private val swatches = listOf(
        "Neon" to 0xFF00FF41.toInt(),
        "Cyan" to 0xFF00E5FF.toInt(),
        "Red" to 0xFFFF3B5C.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Yellow" to 0xFFFFE600.toInt(),
        "Pink" to 0xFFFF3DFF.toInt()
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_crosshair, container, false)
        design = DesignStore.current.copy()
        preview = v.findViewById(R.id.preview)

        // screen sync readout
        val hz = currentRefreshRate()
        v.findViewById<TextView>(R.id.sync_text).text =
            if (hz > 0) "Screen sync: ${hz.toInt()} Hz · vsync-aligned overlay" else "Screen sync: --"

        buildStyleRow(v)
        buildSeekBars(v)
        buildColorRow(v)
        v.findViewById<Button>(R.id.btn_custom_color).setOnClickListener { showCustomColorDialog() }

        val chkDot = v.findViewById<CheckBox>(R.id.chk_dot)
        val chkOutline = v.findViewById<CheckBox>(R.id.chk_outline)
        chkDot.isChecked = design.centerDot
        chkOutline.isChecked = design.outline
        chkDot.setOnCheckedChangeListener { _, c -> design.centerDot = c; sync() }
        chkOutline.setOnCheckedChangeListener { _, c -> design.outline = c; sync() }

        v.findViewById<Button>(R.id.btn_save).setOnClickListener {
            val nameView = v.findViewById<EditText>(R.id.design_name)
            val name = nameView.text.toString().ifBlank { "Design ${System.currentTimeMillis() % 10000}" }
            activity?.let { a ->
                DesignStore.save(a, name)
                nameView.setText("")
                refreshDesignList(v)
                Toast.makeText(a, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
            }
        }
        refreshDesignList(v)

        v.findViewById<Button>(R.id.btn_overlay).setOnClickListener { toggleOverlay(v) }
        wireMasterButton(v)
        wireDragPosition(v)

        wireDynamicEffects(v)
        wireControllerAds(v)

        sync()
        return v
    }

    override fun onDestroyView() {
        TapAccessibilityService.instance?.cancelLearnMode()
        TapAccessibilityService.learnListener = null
        learning = false
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        view?.let { refreshDesignList(it) }
        view?.let { updateMasterButton(it) }
        syncDragSwitch()
        updateOverlayButton()
        view?.let { updatePreviewEffects() }
        view?.let { refreshControllerAdsUi() }
    }

    // ---------- master START / STOP ----------

    private fun wireMasterButton(root: View) {
        root.findViewById<Button>(R.id.btn_master_start).setOnClickListener { onMasterStartStop(root) }
        updateMasterButton(root)
    }

    private fun updateMasterButton(root: View) {
        val running = OverlayService.isRunning
        val btn = root.findViewById<Button>(R.id.btn_master_start)
        btn.text = if (running) "STOP" else "START"
        // STOP state: keep the white button, switch to a danger tint.
        btn.background = activity?.getDrawable(
            if (running) R.drawable.bg_button_rounded else R.drawable.bg_button_white
        )
        btn.setTextColor(if (running) cText else Color.parseColor("#3A1E7A"))
        val st = root.findViewById<TextView>(R.id.master_status)
        st.text = if (running) "Running in background" else "Stopped"
        st.setTextColor(if (running) cSuccess else Color.parseColor("#B4A8DC"))
    }

    private fun onMasterStartStop(root: View) {
        val a = activity ?: return
        if (OverlayService.isRunning) {
            a.stopService(Intent(a, OverlayService::class.java))
            updateMasterButton(root)
            updateOverlayButton()
            Toast.makeText(a, "Overlay stopped", Toast.LENGTH_SHORT).show()
            return
        }
        // a. overlay permission first
        if (!Settings.canDrawOverlays(a)) {
            Toast.makeText(a, "ProConn needs \"Display over other apps\" to show the crosshair", Toast.LENGTH_LONG).show()
            a.startActivity(OverlayService.overlayPermissionIntent(a))
            return
        }
        // b. start the foreground service (crosshair overlay)
        a.startForegroundService(Intent(a, OverlayService::class.java).setAction(OverlayService.ACTION_START))
        Toast.makeText(a, "Starting…", Toast.LENGTH_SHORT).show()
        root.postDelayed({ updateMasterButton(root); updateOverlayButton() }, 800)

        // c. battery-optimization exemption (non-blocking)
        try {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(a.packageName)) {
                AlertDialog.Builder(a)
                    .setTitle("Keep running in background")
                    .setMessage("To keep the crosshair alive during CODM, let ProConn run without battery restrictions.")
                    .setPositiveButton("Allow") { _, _ ->
                        try {
                            a.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${a.packageName}")
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(a, "Open Settings → Battery and exempt ProConn Mobile", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        } catch (e: Exception) {
            // PowerManager unavailable; skip
        }
    }

    // ---------- drag to position ----------

    /**
     * One small Switch row: when ON, the floating crosshair becomes draggable
     * so it can be placed by hand — release saves the position. When OFF
     * (default) it is untouchable, exactly like v1.9. Drag mode is never
     * persisted; the service always starts locked.
     */
    private var dragSwitch: Switch? = null

    private fun wireDragPosition(root: View) {
        dragSwitch = root.findViewById(R.id.sw_drag_position)
        syncDragSwitch()
        dragSwitch?.setOnCheckedChangeListener { _, on -> setDragMode(on) }
    }

    private fun setDragMode(on: Boolean) {
        val a = activity ?: return
        if (on && !OverlayService.isRunning) {
            syncDragSwitch()
            Toast.makeText(a, "Press START first, then drag the crosshair", Toast.LENGTH_LONG).show()
            return
        }
        a.startService(
            Intent(a, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SET_DRAGGABLE)
                .putExtra(OverlayService.EXTRA_DRAGGABLE, on)
        )
        if (on) Toast.makeText(a, "Drag the crosshair into place — release to save", Toast.LENGTH_SHORT).show()
    }

    /** Reflect the service's drag state without re-triggering the listener. */
    private fun syncDragSwitch() {
        val sw = dragSwitch ?: return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = OverlayService.isDraggable
        sw.setOnCheckedChangeListener { _, on -> setDragMode(on) }
    }

    // ---------- dynamic effects ----------

    private fun wireDynamicEffects(root: View) {
        val a = activity ?: return
        val chkShrink = root.findViewById<CheckBox>(R.id.chk_shrink_ads)
        val chkBreath = root.findViewById<CheckBox>(R.id.chk_breathing)
        chkShrink.isChecked = TapStore.isShrinkOnAds(a)
        chkBreath.isChecked = TapStore.isBreathingPulse(a)
        val apply: () -> Unit = {
            TapStore.setShrinkOnAds(a, chkShrink.isChecked)
            TapStore.setBreathingPulse(a, chkBreath.isChecked)
            // Push the new settings into the running overlay.
            a.startService(Intent(a, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH_EFFECTS))
            updatePreviewEffects()
        }
        chkShrink.setOnCheckedChangeListener { _, _ -> apply() }
        chkBreath.setOnCheckedChangeListener { _, _ -> apply() }
        updatePreviewEffects()
    }

    private fun updatePreviewEffects() {
        val a = activity ?: return
        preview?.apply {
            previewShrinkOnAds = TapStore.isShrinkOnAds(a)
            previewBreathing = TapStore.isBreathingPulse(a)
            setPreviewAnimated(previewShrinkOnAds || previewBreathing)
        }
    }

    // ---------- controller ADS sync ----------

    private var swControllerAds: Switch? = null
    private var tvAdsButton: TextView? = null
    private var btnLearnAds: Button? = null
    private var learning = false

    /**
     * "Controller ADS" section: an opt-in switch plus a LEARN button that
     * captures the controller button the user aims with (via the
     * accessibility service's system-wide key-event filter). While the
     * learned button is held, the ADS state engages — shrink and pulse
     * follow the real ADS with no in-game buttons.
     */
    private fun wireControllerAds(root: View) {
        val a = activity ?: return
        swControllerAds = root.findViewById(R.id.sw_controller_ads)
        tvAdsButton = root.findViewById(R.id.tv_ads_button)
        btnLearnAds = root.findViewById(R.id.btn_learn_ads)
        swControllerAds?.setOnCheckedChangeListener(null)
        swControllerAds?.isChecked = TapStore.isControllerAdsSync(a)
        swControllerAds?.setOnCheckedChangeListener { _, on ->
            if (on && !TapAccessibilityService.isEnabled()) {
                swControllerAds?.isChecked = false
                Toast.makeText(a, "Enable ProConn Mobile in Accessibility settings first", Toast.LENGTH_LONG).show()
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnCheckedChangeListener
            }
            TapStore.setControllerAdsSync(a, on)
            // Never leave a stale hold behind when the feature is switched off.
            if (!on) OverlayService.instance?.setControllerHeld(false)
            refreshControllerAdsUi()
        }
        btnLearnAds?.setOnClickListener { startLearn() }
        refreshControllerAdsUi()
    }

    private fun refreshControllerAdsUi() {
        val a = activity ?: return
        if (learning) {
            tvAdsButton?.text = "Press your ADS button on the controller…"
            btnLearnAds?.text = "LEARNING…"
            btnLearnAds?.isEnabled = false
            return
        }
        val key = TapStore.getControllerAdsKey(a)
        val name = if (key == 0) "Not set" else keyCodeName(key)
        tvAdsButton?.text = "ADS button: $name"
        btnLearnAds?.text = "LEARN"
        btnLearnAds?.isEnabled = true
    }

    private fun keyCodeName(keyCode: Int): String {
        return try {
            KeyEvent.keyCodeToString(keyCode)
        } catch (e: Exception) {
            "key $keyCode"
        }
    }

    /** 15s learn window: the next gamepad button press becomes the ADS button. */
    private fun startLearn() {
        val a = activity ?: return
        val svc = TapAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(a, "Enable ProConn Mobile in Accessibility settings first", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!svc.startLearnMode()) return // already learning
        learning = true
        TapAccessibilityService.learnListener = { keyCode ->
            a.runOnUiThread {
                learning = false
                TapAccessibilityService.learnListener = null
                refreshControllerAdsUi()
                if (keyCode == null) {
                    Toast.makeText(a, "No button detected", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(a, "Learned ${keyCodeName(keyCode)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        refreshControllerAdsUi()
        Toast.makeText(a, "Press your ADS button on the controller…", Toast.LENGTH_LONG).show()
    }

    // ---------- misc ----------

    private fun currentRefreshRate(): Float {
        val a = activity ?: return 0f
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) a.display?.refreshRate ?: 0f
            else {
                @Suppress("DEPRECATION")
                (a.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
            }
        } catch (e: Exception) { 0f }
    }

    private fun sync() {
        DesignStore.current = design
        preview?.design = design
        preview?.refresh()
        updateOverlayButton()
    }

    private fun updateOverlayButton() {
        view?.findViewById<Button>(R.id.btn_overlay)?.text = when {
            !OverlayService.isRunning -> "Show as floating overlay"
            OverlayService.overlayVisible -> "Hide floating overlay"
            else -> "Show floating overlay"
        }
    }

    private fun buildStyleRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.style_row)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for (s in CrosshairStyle.values()) {
            val b = Button(activity).apply {
                text = s.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                setTextColor(cText)
                textSize = 12f
                background = activity?.getDrawable(
                    if (s == design.style) R.drawable.bg_button_primary
                    else R.drawable.bg_button_rounded
                )
            }
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (8 * density).toInt(), 0) }
            b.setOnClickListener { design.style = s; sync(); buildStyleRow(root) }
            row.addView(b)
        }
    }

    private fun seek(root: View, id: Int, onChange: (Int) -> Unit) {
        root.findViewById<SeekBar>(id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun buildSeekBars(root: View) {
        val lengthBar = root.findViewById<SeekBar>(R.id.seek_length)
        val gapBar = root.findViewById<SeekBar>(R.id.seek_gap)
        val thickBar = root.findViewById<SeekBar>(R.id.seek_thickness)
        val alphaBar = root.findViewById<SeekBar>(R.id.seek_alpha)
        lengthBar.progress = (design.length - 4).toInt().coerceIn(0, 76)
        gapBar.progress = design.gap.toInt().coerceIn(0, 60)
        thickBar.progress = (design.thickness - 1).toInt().coerceIn(0, 15)
        alphaBar.progress = (design.alpha - 20).coerceIn(0, 235)
        seek(root, R.id.seek_length) { design.length = (it + 4).toFloat(); sync() }
        seek(root, R.id.seek_gap) { design.gap = it.toFloat(); sync() }
        seek(root, R.id.seek_thickness) { design.thickness = (it + 1).toFloat(); sync() }
        seek(root, R.id.seek_alpha) { design.alpha = it + 20; sync() }
    }

    private fun buildColorRow(root: View) {
        val row = root.findViewById<LinearLayout>(R.id.color_row)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for ((name, c) in swatches) {
            val b = Button(activity).apply {
                text = ""
                contentDescription = name
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(c)
                    cornerRadius = 12 * density
                }
            }
            val px = (44 * density).toInt()
            b.layoutParams = LinearLayout.LayoutParams(px, px).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            b.setOnClickListener { design.color = c; sync() }
            row.addView(b)
        }
    }

    private fun showCustomColorDialog() {
        val a = activity ?: return
        val layout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            setBackgroundColor(Color.parseColor("#1B1233"))
        }
        var r = Color.red(design.color); var g = Color.green(design.color); var b = Color.blue(design.color)
        val previewDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.rgb(r, g, b))
            cornerRadius = 12 * resources.displayMetrics.density
        }
        val previewBox = View(a).apply {
            background = previewDrawable
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120)
        }
        layout.addView(previewBox)
        fun addChannel(label: String, value: Int, set: (Int) -> Unit) {
            val tv = TextView(a).apply { text = "$label: $value"; setTextColor(cText) }
            val sb = SeekBar(a).apply { max = 255; progress = value }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    set(p); tv.text = "$label: $p"
                    previewDrawable.setColor(Color.rgb(r, g, b))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            layout.addView(tv); layout.addView(sb)
        }
        addChannel("R", r) { r = it }
        addChannel("G", g) { g = it }
        addChannel("B", b) { b = it }
        AlertDialog.Builder(a)
            .setTitle("Custom color")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ -> design.color = Color.rgb(r, g, b); sync() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshDesignList(root: View) {
        val a = activity ?: return
        val list = root.findViewById<LinearLayout>(R.id.design_list)
        list.removeAllViews()
        val names = DesignStore.list(a)
        if (names.isEmpty()) {
            list.addView(TextView(a).apply {
                text = "No saved designs yet"
                setTextColor(cMuted)
            })
            return
        }
        val density = resources.displayMetrics.density
        for (name in names) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            val tv = TextView(a).apply {
                text = name
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
            }
            val load = Button(a).apply {
                text = "Load"; textSize = 12f
                setTextColor(Color.parseColor("#FFFFFF"))
                background = a.getDrawable(R.drawable.bg_button_primary)
            }
            val del = Button(a).apply {
                text = "Del"; textSize = 12f
                setTextColor(Color.parseColor("#F87171"))
                background = a.getDrawable(R.drawable.bg_button_rounded)
            }
            load.setOnClickListener {
                DesignStore.load(a, name)?.let {
                    design = it
                    sync()
                    root.findViewById<CheckBox>(R.id.chk_dot).isChecked = design.centerDot
                    root.findViewById<CheckBox>(R.id.chk_outline).isChecked = design.outline
                    buildSeekBars(root); buildStyleRow(root)
                }
            }
            del.setOnClickListener { DesignStore.delete(a, name); refreshDesignList(root) }
            row.addView(tv); row.addView(load); row.addView(del)
            list.addView(row)
        }
    }

    private fun toggleOverlay(root: View) {
        val a = activity ?: return
        if (!OverlayService.isRunning) {
            if (!Settings.canDrawOverlays(a)) {
                Toast.makeText(a, "Grant \"Display over other apps\" permission", Toast.LENGTH_LONG).show()
                a.startActivity(OverlayService.overlayPermissionIntent(a))
                return
            }
            a.startForegroundService(Intent(a, OverlayService::class.java).setAction(OverlayService.ACTION_START))
            root.postDelayed({ updateOverlayButton() }, 600)
            return
        }
        // Service is running: toggle view visibility (Hide/Show), keep the
        // service alive — same as the notification actions.
        val action = if (OverlayService.overlayVisible) OverlayService.ACTION_HIDE_VIEWS
                     else OverlayService.ACTION_SHOW_VIEWS
        a.startService(Intent(a, OverlayService::class.java).setAction(action))
        root.postDelayed({ updateOverlayButton() }, 400)
    }
}
