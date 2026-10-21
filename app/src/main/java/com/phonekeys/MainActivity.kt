
package com.phonekeys

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(
            pluggedDevice: BluetoothDevice?,
            registered: Boolean
        ) {
            this@MainActivity.registered = registered
            updateStatus(
                if (registered) {
                    "Keyboard registered. Pair/connect your PC."
                } else {
                    "Keyboard is not registered."
                }
            )
        }

        override fun onConnectionStateChanged(
            device: BluetoothDevice?,
            state: Int
        ) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED ->
                    updateStatus("Connected to PC. Ready to type!")

                BluetoothProfile.STATE_CONNECTING ->
                    updateStatus("Connecting to PC...")

                BluetoothProfile.STATE_DISCONNECTED ->
                    updateStatus("Disconnected. Register and reconnect.")

                else -> updateStatus("Bluetooth state: $state")
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceConnected(
            profile: Int,
            proxy: BluetoothProfile
        ) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                updateStatus("Bluetooth HID available. Tap Register keyboard.")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                registered = false
                updateStatus("Bluetooth HID service disconnected.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH
            )
        } else {
            initializeBluetooth()
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        statusText = TextView(this).apply {
            text = "Starting PhoneKeys..."
            textSize = 18f
            setPadding(0, 0, 0, 20)
        }
        root.addView(statusText)

        addButton(root, "Register keyboard") {
            registerKeyboard()
        }

        addButton(root, "Unregister keyboard") {
            unregisterKeyboard()
        }

        addButton(root, "Send A") {
            sendKey(0x04)
        }

        addButton(root, "Send Enter") {
            sendKey(0x28)
        }

        addButton(root, "Send Backspace") {
            sendKey(0x2A)
        }

        addButton(root, "Send Space") {
            sendKey(0x2C)
        }

        val note = TextView(this).apply {
            text = "\nTest version: connect your PC over Bluetooth, " +
                "then test these buttons in a text editor."
            textSize = 14f
        }
        root.addView(note)

        setContentView(scroll)
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

        parent.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun initializeBluetooth() {
        try {
            val manager =
                getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = manager.adapter

            if (adapter == null) {
                updateStatus("This device has no Bluetooth adapter.")
                return
            }

            if (!adapter.isEnabled) {
                updateStatus("Turn on Bluetooth in Android Settings.")
                return
            }

            val success = adapter.getProfileProxy(
                this,
                profileListener,
                BluetoothProfile.HID_DEVICE
            )

            if (!success) {
                updateStatus("Could not request the HID service.")
            } else {
                updateStatus("Requesting Bluetooth HID service...")
            }
        } catch (e: SecurityException) {
            updateStatus("Bluetooth permission is required.")
        } catch (e: Exception) {
            updateStatus("Bluetooth error: ${e.message}")
        }
    }

    private fun registerKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH
            )
            return
        }

        val hid = hidDevice

        if (hid == null) {
            updateStatus("HID service is not ready. Restart the app.")
            return
        }

        try {
            val settings = BluetoothHidDeviceAppSdpSettings(
                "PhoneKeys",
                "PhoneKeys Bluetooth Keyboard",
                "PhoneKeys",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                KEYBOARD_DESCRIPTOR
            )

            val executor = Executor { command ->
                runOnUiThread { command.run() }
            }

            val accepted = hid.registerApp(
                settings,
                null,
                null,
                executor,
                callback
            )

            updateStatus(
                if (accepted) {
                    "Registration requested. Waiting for Android..."
                } else {
                    "Android rejected the registration request."
                }
            )
        } catch (e: SecurityException) {
            updateStatus("Bluetooth permission denied.")
        } catch (e: Exception) {
            updateStatus("Registration error: ${e.message}")
        }
    }

    private fun unregisterKeyboard() {
        try {
            val hid = hidDevice

            if (hid == null) {
                updateStatus("HID service is not ready.")
                return
            }

            hid.unregisterApp()
            registered = false
            updateStatus("Unregister requested.")
        } catch (e: Exception) {
            updateStatus("Unregister error: ${e.message}")
        }
    }

    private fun sendKey(keyCode: Int) {
        try {
            val hid = hidDevice

            if (hid == null || !registered) {
                updateStatus("Register the keyboard first.")
                return
            }

            val devices = hid.connectedDevices
            val pc = devices.firstOrNull()

            if (pc == null) {
                updateStatus("No connected PC. Pair/connect over Bluetooth.")
                return
            }

            val pressed = byteArrayOf(
                0x00,
                0x00,
                keyCode.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00
            )

            val released = ByteArray(8)

            val sent = hid.sendReport(pc, 0, pressed)

            if (!sent) {
                updateStatus("Android could not send the key.")
                return
            }

            mainHandler.postDelayed({
                try {
                    hid.sendReport(pc, 0, released)
                } catch (_: Exception) {
                    updateStatus("Key release failed. Reconnect and retry.")
                }
            }, 60L)

            updateStatus("Key sent.")
        } catch (e: SecurityException) {
            updateStatus("Bluetooth permission denied.")
        } catch (e: Exception) {
            updateStatus("Send error: ${e.message}")
        }
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) {
            statusText.text = message
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                initializeBluetooth()
            } else {
                updateStatus("Bluetooth permission was not granted.")
            }
        }
    }

    override fun onDestroy() {
        try {
            val hid = hidDevice
            if (hid != null) {
                if (registered) {
                    hid.unregisterApp()
                }
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter
                    ?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        } catch (_: Exception) {
            // The activity may be closing while Bluetooth disconnects.
        }

        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BLUETOOTH = 100

        private val KEYBOARD_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
            0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01,
            0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03,
            0x91, 0x01, 0x95, 0x06, 0x75, 0x08, 0x15, 0x00,
            0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
            0x81, 0x00, 0xC0
        ).map { it.toByte() }.toByteArray()
    }
}

