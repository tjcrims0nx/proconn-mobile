package com.proconn.mobile.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build

/** Bluetooth pairing + bonded/connected device listing for the Tuner tab. */
object BluetoothHelper {

    const val REQ_BT_ENABLE = 200
    const val REQ_BT_PAIR = 201
    const val REQ_BT_PERMS = 202

    // BluetoothProfile.HID_HOST is hidden API; its value is 4 on all Android versions.
    private const val PROFILE_HID_HOST = 4

    data class BtDeviceInfo(
        val name: String,
        val address: String,
        val isGamepad: Boolean,
        val isConnected: Boolean
    )

    fun adapter(context: Context): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter
    }

    fun isSupported(context: Context): Boolean = adapter(context) != null

    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission(context)) return false
        return try {
            adapter(context)?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
    }

    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    fun hasScanPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

    /** Permissions that still need a runtime request on this device (empty on API <= 30). */
    fun missingPermissions(context: Context): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        val out = mutableListOf<String>()
        if (!hasConnectPermission(context)) out.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (!hasScanPermission(context)) out.add(Manifest.permission.BLUETOOTH_SCAN)
        return out.toTypedArray()
    }

    fun requestEnable(activity: Activity) {
        try {
            activity.startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ENABLE
            )
        } catch (e: Exception) {
            // No activity to handle it — nothing we can do.
        }
    }

    fun isGamepad(device: BluetoothDevice): Boolean {
        val cls: BluetoothClass? = try {
            device.bluetoothClass
        } catch (e: SecurityException) {
            null
        }
        if (cls != null) {
            // deviceClass is the full 24-bit class; minor lives in the low 8 bits.
            // Peripheral joystick = 0x504, gamepad = 0x508 per the BT spec.
            val major = cls.majorDeviceClass
            val minor = cls.deviceClass and 0xFF
            val peripheralPad = major == BluetoothClass.Device.Major.PERIPHERAL &&
                (minor == 0x04 || minor == 0x08)
            val toyPad = cls.deviceClass == BluetoothClass.Device.TOY_CONTROLLER ||
                cls.deviceClass == BluetoothClass.Device.TOY_GAME
            if (peripheralPad || toyPad) return true
        }
        val n = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: return false
        val lower = n.lowercase()
        return lower.contains("controller") || lower.contains("gamepad") ||
            lower.contains("xbox") || lower.contains("dualshock") ||
            lower.contains("dualsense") || lower.contains("joy-con") ||
            lower.contains("8bitdo")
    }

    fun getBondedDevices(context: Context): List<BluetoothDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        return try {
            val bonded = adapter(context)?.bondedDevices ?: return emptyList()
            bonded.sortedWith(
                compareByDescending<BluetoothDevice> { isGamepad(it) }
                    .thenBy { it.name?.lowercase() ?: "" }
            )
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Reads the HID-host profile's connected devices, then releases the proxy. */
    fun getConnectedHidDevices(context: Context, cb: (Set<String>) -> Unit) {
        if (!hasConnectPermission(context)) {
            cb(emptySet())
            return
        }
        val ad = adapter(context)
        if (ad == null) {
            cb(emptySet())
            return
        }
        try {
            ad.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val addrs = proxy.connectedDevices
                            .mapNotNull { d ->
                                try {
                                    d.address
                                } catch (e: SecurityException) {
                                    null
                                }
                            }.toSet()
                        cb(addrs)
                    } finally {
                        try {
                            ad.closeProfileProxy(profile, proxy)
                        } catch (e: Exception) {
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, PROFILE_HID_HOST)
        } catch (e: SecurityException) {
            cb(emptySet())
        } catch (e: Exception) {
            cb(emptySet())
        }
    }

    /** Bonded devices merged with HID connection state, gamepads first. */
    fun refreshDevices(context: Context, cb: (List<BtDeviceInfo>) -> Unit) {
        val bonded = getBondedDevices(context)
        getConnectedHidDevices(context) { connected ->
            val infos = bonded.map { d ->
                val addr = try {
                    d.address
                } catch (e: SecurityException) {
                    "?"
                }
                val nm = try {
                    d.name
                } catch (e: SecurityException) {
                    null
                }
                BtDeviceInfo(
                    name = nm ?: "Unknown device",
                    address = addr,
                    isGamepad = isGamepad(d),
                    isConnected = connected.contains(addr)
                )
            }
            cb(infos)
        }
    }

    /**
     * Starts the system pairing flow. Android shows a system dialog — the app
     * only triggers it; the user confirms. On API 26-32 the chooser result
     * comes back to MainActivity.onActivityResult; on API 33+ the association
     * callback fires and we bond directly.
     */
    @SuppressLint("MissingPermission")
    fun startPairing(activity: Activity) {
        if (!hasConnectPermission(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasScanPermission(activity)) return
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return
        // Plain filter matches all devices (required on API < 33 for the chooser).
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .build()
        try {
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    try {
                        activity.startIntentSenderForResult(
                            chooserLauncher, REQ_BT_PAIR, null, 0, 0, 0
                        )
                    } catch (e: Exception) {
                    }
                }

                override fun onFailure(error: CharSequence?) {}

                // API 33+: system shows the consent dialog itself; bond the chosen device.
                override fun onAssociationCreated(info: AssociationInfo) {
                    bondMac(activity, info.deviceMacAddress?.toString())
                }
            }, null)
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }

    /** API 26-32 path: MainActivity.onActivityResult forwards here. */
    @Suppress("DEPRECATION")
    fun handlePairResult(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        val device: BluetoothDevice? =
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        if (device == null) return false
        bondDevice(context, device)
        return true
    }

    private fun bondMac(context: Context, mac: String?) {
        if (mac.isNullOrEmpty()) return
        if (!hasConnectPermission(context)) return
        try {
            val device = adapter(context)?.getRemoteDevice(mac) ?: return
            bondDevice(context, device)
        } catch (e: Exception) {
        }
    }

    private fun bondDevice(context: Context, device: BluetoothDevice) {
        if (!hasConnectPermission(context)) return
        try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) device.createBond()
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }
    }
}
