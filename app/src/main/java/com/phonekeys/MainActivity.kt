package com.phonekeys

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var host: BluetoothDevice? = null

    private val executor = Executor { command -> runOnUiThread(command) }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(
            pluggedDevice: BluetoothDevice?,
            registeredNow: Boolean
        ) {
            registered = registeredNow
            host = pluggedDevice
            setStatus(
                if (registeredNow) "HID app registered. Make this phone discoverable/pair it from Windows Bluetooth settings."
                else "HID app is not registered. Check Bluetooth and try Register keyboard."
            )
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                setStatus("Connected to Windows. Tap a key below to test.")
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                host = null
                setStatus(if (registered) "Registered; not connected to Windows yet." else "Disconnected.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 16, 12, 12)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply {
            text = "PhoneKeys HID test\nTurn Bluetooth on, then register."
            textSize = 16f
            setPadding(8, 8, 8, 12)
        }
        outer.addView(status)
        outer.addView(makeButton("Register keyboard") { registerHid() })
        outer.addView(makeButton("Unregister keyboard") {
            try {
                hid?.unregisterApp()
                registered = false
                setStatus("Unregistered.")
            } catch (e: Exception) {
                setStatus("Unregister error: ${e.message}")
            }
        })
        outer.addView(makeButton("Send A") { sendKey(0x04) })
        outer.addView(makeButton("Send Enter") { sendKey(0x28) })
        outer.addView(makeButton("Send Backspace") { sendKey(0x2A) })
        outer.addView(makeButton("Send Space") { sendKey(0x2C) })
        outer.addView(TextView(this).apply {
            text = "Test prototype only. Clipboard and full keyboard layout are not included yet."
            textSize = 13f
            setPadding(8, 16, 8, 8)
        })
        setContentView(outer)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun registerHid() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            setStatus("This phone reports no Bluetooth adapter.")
            return
        }
        if (!adapter.isEnabled) {
            setStatus("Turn Bluetooth on in Android Settings, then tap Register keyboard.")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
            setStatus("Grant Nearby devices permission, then tap Register keyboard again.")
            return
        }
        setStatus("Requesting Android Bluetooth HID service…")
        val ok = adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) {
                    setStatus("Unexpected Bluetooth profile: $profile")
                    return
                }
                hid = proxy as BluetoothHidDevice
                try {
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "PhoneKeys",
                        "PhoneKeys Bluetooth keyboard test",
                        "OpenAI",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        KEYBOARD_DESCRIPTOR
                    )
                    val accepted = hid?.registerApp(sdp, null, null, executor, callback) ?: false
                    setStatus(
                        if (accepted) "Registration request sent. Wait for the status callback."
                        else "Android rejected the HID registration request. This may be blocked by the phone firmware."
                    )
                } catch (e: SecurityException) {
                    setStatus("Bluetooth permission error: ${e.message}")
                } catch (e: Exception) {
                    setStatus("HID registration failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                host = null
                setStatus("Bluetooth HID service disconnected.")
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) setStatus("Could not request Bluetooth HID service. Firmware/API support may be unavailable.")
    }

    // USB HID keyboard report: modifier, reserved byte, six key slots.
    private fun sendKey(usage: Int) {
        val service = hid
        val device = host
        if (service == null || device == null || !registered) {
            setStatus("Not connected yet. Register the keyboard and pair from Windows first.")
            return
        }
        try {
            val pressed = byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0)
            val released = ByteArray(8)
            val first = service.sendReport(device, 0, pressed)
            service.sendReport(device, 0, released)
            setStatus(if (first) "Sent key usage $usage." else "Windows did not accept the key report.")
        } catch (e: Exception) {
            setStatus("Send error: ${e.message}")
        }
    }

    private fun setStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        // Keep HID registration managed by Android's foreground registration rules;
        // do not unregister here while testing the visible activity.
        super.onDestroy()
    }

    companion object {
        // Standard HID boot keyboard report descriptor.
        private val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
            0x81.toByte(), 0x02, 0x95, 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05,
            0x91.toByte(), 0x02, 0x95, 0x01, 0x75, 0x03, 0x91.toByte(), 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07,
            0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte()
        )
    }
}
