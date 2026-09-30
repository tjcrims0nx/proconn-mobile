package com.proconn.mobile.fragments

import android.Manifest
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.proconn.mobile.R
import com.proconn.mobile.service.OverlayService
import com.proconn.mobile.service.TapAccessibilityService

/**
 * Guide tab. Starts with a PERMISSIONS checklist card: one row per permission
 * with a live status dot (green = granted, red = missing) and an action
 * button that takes the user directly to the right place — no Settings digging.
 */
@Suppress("DEPRECATION")
class GuideFragment : Fragment() {

    companion object {
        private const val REQ_BT = 2001
        private const val REQ_NOTIF = 2002
    }

    private val rowDots = mutableListOf<Pair<View, PermDef>>()

    private data class PermDef(
        val title: String,
        val hint: String,
        val granted: () -> Boolean,
        val actionLabel: String,
        val onAction: () -> Unit,
        val extraHint: String? = null,
        val extraActionLabel: String? = null,
        val extraAction: (() -> Unit)? = null
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_guide, container, false)
        buildPermissionRows(v)
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        refreshPermissionRows()
    }

    private fun permDefs(): List<PermDef> {
        val a = activity ?: return emptyList()
        val pkg = a.packageName
        return listOf(
            PermDef(
                title = "Display over other apps",
                hint = "Lets the crosshair float over your game.",
                granted = { Settings.canDrawOverlays(a) },
                actionLabel = "Open",
                onAction = { a.startActivity(OverlayService.overlayPermissionIntent(a)) }
            ),
            PermDef(
                title = "Accessibility service (ADS sync)",
                hint = "Watches your learned controller ADS button so the crosshair shrinks and pulses while you aim.",
                granted = { TapAccessibilityService.isEnabled() },
                actionLabel = "Open",
                onAction = { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                extraHint = "Sideloaded apps only: if the toggle is grayed out, open App info → ⋮ menu → Allow restricted settings, then come back.",
                extraActionLabel = "Open App info",
                extraAction = {
                    a.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$pkg")
                        )
                    )
                }
            ),
            PermDef(
                title = "Bluetooth",
                hint = "Needed to pair your controller from the app.",
                granted = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED
                    else true
                },
                actionLabel = "Grant",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestPermissions(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN
                            ),
                            REQ_BT
                        )
                    }
                }
            ),
            PermDef(
                title = "Notifications",
                hint = "Shows the overlay Hide/Show controls in your notification shade.",
                granted = {
                    if (Build.VERSION.SDK_INT >= 33)
                        a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                    else true
                },
                actionLabel = "Grant",
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQ_NOTIF
                        )
                    }
                }
            ),
            PermDef(
                title = "Run in background",
                hint = "Keeps the crosshair alive during CODM.",
                granted = {
                    try {
                        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
                        pm.isIgnoringBatteryOptimizations(pkg)
                    } catch (e: Exception) {
                        false
                    }
                },
                actionLabel = "Open",
                onAction = {
                    try {
                        a.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$pkg")
                            )
                        )
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            )
        )
    }

    private fun buildPermissionRows(root: View) {
        val a = activity ?: return
        val list = root.findViewById<LinearLayout>(R.id.perm_list) ?: return
        list.removeAllViews()
        rowDots.clear()
        for (def in permDefs()) {
            list.addView(buildRow(a, def))
        }
        refreshPermissionRows()
    }

    private fun buildRow(a: android.app.Activity, def: PermDef): View {
        val density = a.resources.displayMetrics.density
        val container = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F87171"))
            }
            val s = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        val title = TextView(a).apply {
            text = def.title
            setTextColor(Color.parseColor("#F5F2FC"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(a).apply {
            text = def.actionLabel
            textSize = 12f
            setTextColor(Color.parseColor("#F5F2FC"))
            background = a.getDrawable(R.drawable.bg_button_rounded)
            setOnClickListener { def.onAction() }
        }
        row.addView(dot)
        row.addView(title)
        row.addView(btn)
        container.addView(row)
        container.addView(TextView(a).apply {
            text = def.hint
            setTextColor(Color.parseColor("#8A7DB5"))
            textSize = 13f
            setPadding((22 * density).toInt(), (2 * density).toInt(), 0, 0)
        })
        def.extraHint?.let { eh ->
            container.addView(TextView(a).apply {
                text = eh
                setTextColor(Color.parseColor("#8A7DB5"))
                textSize = 13f
                setPadding((22 * density).toInt(), (4 * density).toInt(), 0, 0)
            })
            if (def.extraActionLabel != null && def.extraAction != null) {
                container.addView(Button(a).apply {
                    text = def.extraActionLabel
                    textSize = 12f
                    setTextColor(Color.parseColor("#F5F2FC"))
                    background = a.getDrawable(R.drawable.bg_button_rounded)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins((22 * density).toInt(), (4 * density).toInt(), 0, 0)
                    layoutParams = lp
                    setOnClickListener { def.extraAction.invoke() }
                })
            }
        }
        rowDots.add(dot to def)
        return container
    }

    private fun refreshPermissionRows() {
        for ((dot, def) in rowDots) {
            val ok = try {
                def.granted()
            } catch (e: Exception) {
                false
            }
            (dot.background as? GradientDrawable)?.setColor(
                Color.parseColor(if (ok) "#34D399" else "#F87171")
            )
        }
    }
}
