package com.proconn.mobile

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import com.proconn.mobile.bluetooth.BluetoothHelper
import com.proconn.mobile.fragments.CrosshairFragment
import com.proconn.mobile.fragments.GuideFragment
import com.proconn.mobile.fragments.TunerFragment

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private var tunerFragment: TunerFragment? = null
    private var currentTag: String = "crosshair"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.tab_crosshair).setOnClickListener { showTab("crosshair") }
        findViewById<Button>(R.id.tab_tuner).setOnClickListener { showTab("tuner") }
        findViewById<Button>(R.id.tab_guide).setOnClickListener { showTab("guide") }

        if (savedInstanceState == null) showTab("crosshair")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun showTab(tag: String) {
        currentTag = tag
        val f: Fragment = when (tag) {
            "tuner" -> TunerFragment().also { tunerFragment = it }
            "guide" -> GuideFragment()
            else -> CrosshairFragment()
        }
        if (tag != "tuner") tunerFragment = null
        fragmentManager.beginTransaction().replace(R.id.container, f, tag).commit()

        val active = R.drawable.bg_button_selected_rounded
        val idle = R.drawable.bg_button_rounded
        findViewById<Button>(R.id.tab_crosshair).setBackgroundResource(if (tag == "crosshair") active else idle)
        findViewById<Button>(R.id.tab_tuner).setBackgroundResource(if (tag == "tuner") active else idle)
        findViewById<Button>(R.id.tab_guide).setBackgroundResource(if (tag == "guide") active else idle)
    }

    private fun isGamepadEvent(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepadEvent(event.source) && event.action == MotionEvent.ACTION_MOVE) {
            tunerFragment?.onGamepadMotion(event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadEvent(event.source)) {
            tunerFragment?.onGamepadKey(keyCode, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadEvent(event.source)) {
            tunerFragment?.onGamepadKey(keyCode, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BluetoothHelper.REQ_BT_PAIR -> {
                BluetoothHelper.handlePairResult(this, resultCode, data)
                tunerFragment?.refreshBluetooth()
            }
            BluetoothHelper.REQ_BT_ENABLE -> tunerFragment?.refreshBluetooth()
        }
    }
}
