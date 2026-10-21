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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executor

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var textToSend: EditText
    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@MainActivity.registered = registered
            updateStatus(if (registered) "Keyboard registered. Connect your PC." else "Keyboard is not registered.")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> updateStatus("Connected to PC. Ready to type!")
                BluetoothProfile.STATE_CONNECTING -> updateStatus("Connecting to PC...")
                BluetoothProfile.STATE_DISCONNECTED -> updateStatus("Disconnected. Register and reconnect.")
                else -> updateStatus("Bluetooth state: $state")
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
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
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        } else initializeBluetooth()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(18, 22, 32))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)

        val heading = TextView(this).apply {
            text = "PhoneKeys"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(heading)
        statusText = TextView(this).apply {
            text = "Starting PhoneKeys..."
            textSize = 14f
            setTextColor(Color.rgb(150, 220, 255))
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusText)

        addActionButton(root, "Register keyboard") { registerKeyboard() }
        addActionButton(root, "Unregister keyboard") { unregisterKeyboard() }
        addSectionTitle(root, "TYPE A MESSAGE")
        textToSend = EditText(this).apply {
            hint = "Paste or type text here..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            textSize = 16f
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(12, 12, 12, 12)
        }
        root.addView(textToSend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addActionButton(root, "SEND ENTIRE TEXT TO PC") { sendText(textToSend.text.toString()) }
        addSectionTitle(root, "ON-SCREEN KEYBOARD")
        addKeyRow(root, listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        addKeyRow(root, listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
        addKeyRow(root, listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
        addKeyRow(root, listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"))
        addKeyRow(root, listOf("@", "#", "$", "%", "^", "&", "*", "(", ")"))
        addKeyRow(root, listOf("-", "_", "=", "+", "[", "]", "{", "}"))
        addKeyRow(root, listOf("\\", "|", ";", ":", "'", "\"", "`", "~"))
        addKeyRow(root, listOf(".", ",", "<", ">", "/", "?", "!"))
        addKeyRow(root, listOf("Tab", "Space", "Enter"))
        addActionButton(root, "Backspace") { sendSpecial(0x2A) }
        addActionButton(root, "Clear message") { textToSend.setText("") }
        val note = TextView(this).apply {
            text = "Connect PhoneKeys to your computer over Bluetooth first. Paste text into the box, then tap SEND. The symbol rows include common punctuation and Shift-based symbols. Bluetooth HID sends key presses, so support for symbols depends on your computer's keyboard layout. Emoji and many Unicode characters (such as ₹, é, and non-Latin scripts) cannot be sent directly by this basic HID layout."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 14, 0, 8)
        }
        root.addView(note)
        setContentView(scroll)
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.rgb(120, 200, 255))
            setPadding(0, 18, 0, 8)
        })
    }

    private fun addActionButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addKeyRow(parent: LinearLayout, keys: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        keys.forEach { key ->
            val label = when (key) { "Space" -> "Space"; "Enter" -> "Enter"; "Tab" -> "Tab"; else -> key }
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = if (key.length > 1) 10f else 14f
                setPadding(0, 0, 0, 0)
                minWidth = 0
                setOnClickListener {
                    when (key) {
                        "⇧" -> { shiftNext = !shiftNext; updateStatus(if (shiftNext) "Shift enabled for next letter." else "Shift off.") }
                        "⌫" -> sendSpecial(0x2A)
                        "Space" -> sendSpecial(0x2C)
                        "Enter" -> sendSpecial(0x28)
                        "Tab" -> sendSpecial(0x2B)
                        else -> {
                            val character = if (shiftNext) key.uppercase() else key
                            sendCharacter(character.first())
                            shiftNext = false
                        }
                    }
                }
            }
            val weight = if (key == "Space") 4f else if (key.length > 1) 1.8f else 1f
            val params = LinearLayout.LayoutParams(0, 48, weight)
            params.setMargins(2, 2, 2, 2)
            row.addView(button, params)
        }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private var shiftNext = false

    private fun initializeBluetooth() {
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = manager.adapter
            if (adapter == null) { updateStatus("This device has no Bluetooth adapter."); return }
            if (!adapter.isEnabled) { updateStatus("Turn on Bluetooth in Android Settings."); return }
            val success = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
            updateStatus(if (success) "Requesting Bluetooth HID service..." else "Could not request the HID service.")
        } catch (e: SecurityException) { updateStatus("Bluetooth permission is required.") }
        catch (e: Exception) { updateStatus("Bluetooth error: ${e.message}") }
    }

    private fun registerKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH); return
        }
        val hid = hidDevice
        if (hid == null) { updateStatus("HID service is not ready. Restart the app."); return }
        try {
            val settings = BluetoothHidDeviceAppSdpSettings("PhoneKeys", "PhoneKeys Bluetooth Keyboard", "PhoneKeys", BluetoothHidDevice.SUBCLASS1_KEYBOARD, KEYBOARD_DESCRIPTOR)
            val executor = Executor { command -> runOnUiThread { command.run() } }
            val accepted = hid.registerApp(settings, null, null, executor, callback)
            updateStatus(if (accepted) "Registration requested. Waiting for Android..." else "Android rejected the registration request.")
        } catch (e: SecurityException) { updateStatus("Bluetooth permission denied.") }
        catch (e: Exception) { updateStatus("Registration error: ${e.message}") }
    }

    private fun unregisterKeyboard() {
        try {
            val hid = hidDevice ?: run { updateStatus("HID service is not ready."); return }
            hid.unregisterApp(); registered = false; updateStatus("Unregister requested.")
        } catch (e: Exception) { updateStatus("Unregister error: ${e.message}") }
    }

    private fun sendSpecial(keyCode: Int, modifier: Int = 0) = sendReportStroke(keyCode, modifier)

    private fun sendCharacter(character: Char) {
        val pair = keyForCharacter(character)
        if (pair == null) { updateStatus("Character '$character' is not supported by the basic keyboard layout."); return }
        sendReportStroke(pair.first, if (pair.second) 0x02 else 0)
    }

    private fun sendReportStroke(keyCode: Int, modifier: Int = 0, delayMs: Long = 55L) {
        val target = getConnectedPc() ?: return
        try {
            val hid = hidDevice ?: return
            val pressed = byteArrayOf(modifier.toByte(), 0, keyCode.toByte(), 0, 0, 0, 0, 0)
            val released = ByteArray(8)
            if (!hid.sendReport(target, 0, pressed)) { updateStatus("Android could not send the key."); return }
            mainHandler.postDelayed({ try { hid.sendReport(target, 0, released) } catch (_: Exception) { updateStatus("Key release failed. Reconnect and retry.") } }, delayMs)
        } catch (e: SecurityException) { updateStatus("Bluetooth permission denied.") }
        catch (e: Exception) { updateStatus("Send error: ${e.message}") }
    }

    private fun sendText(text: String) {
        if (text.isEmpty()) { updateStatus("Type or paste some text first."); return }
        val target = getConnectedPc() ?: return
        val chars = text.toList()
        val supported = chars.mapNotNull { ch ->
            when (ch) {
                '\n', '\r' -> Pair(0x28, 0)
                '\t' -> Pair(0x2B, 0)
                else -> keyForCharacter(ch)?.let { Pair(it.first, if (it.second) 0x02 else 0) }
            }
        }
        if (supported.isEmpty()) { updateStatus("No supported characters found."); return }
        updateStatus("Typing ${supported.size} keystrokes on the PC...")
        val hid = hidDevice ?: return
        supported.forEachIndexed { index, key ->
            val start = index * 95L
            mainHandler.postDelayed({
                try {
                    val pressed = byteArrayOf(key.second.toByte(), 0, key.first.toByte(), 0, 0, 0, 0, 0)
                    hid.sendReport(target, 0, pressed)
                    mainHandler.postDelayed({ try { hid.sendReport(target, 0, ByteArray(8)) } catch (_: Exception) {} }, 35L)
                    if (index == supported.lastIndex) mainHandler.postDelayed({ updateStatus("Finished sending text.") }, 50L)
                } catch (e: Exception) { updateStatus("Text send interrupted: ${e.message}") }
            }, start)
        }
        if (supported.size < chars.size) {
            val skipped = chars.size - supported.size
            updateStatus("Sending text; $skipped unsupported character(s), such as emoji or Unicode symbols, will be skipped.")
        }
    }

    private fun getConnectedPc(): BluetoothDevice? {
        try {
            val hid = hidDevice
            if (hid == null || !registered) { updateStatus("Register the keyboard first."); return null }
            val pc = hid.connectedDevices.firstOrNull()
            if (pc == null) { updateStatus("No connected PC. Pair/connect over Bluetooth."); return null }
            return pc
        } catch (e: SecurityException) { updateStatus("Bluetooth permission denied."); return null }
    }

    // Returns (HID usage code, requires Shift).
    private fun keyForCharacter(ch: Char): Pair<Int, Boolean>? {
        if (ch in 'a'..'z') return Pair(0x04 + (ch - 'a'), false)
        if (ch in 'A'..'Z') return Pair(0x04 + (ch - 'A'), true)
        if (ch in '1'..'9') return Pair(0x1E + (ch - '1'), false)
        if (ch == '0') return Pair(0x27, false)
        return when (ch) {
            ' ' -> Pair(0x2C, false)
            '!' -> Pair(0x1E, true); '@' -> Pair(0x1F, true); '#' -> Pair(0x20, true); '$' -> Pair(0x21, true)
            '%' -> Pair(0x22, true); '^' -> Pair(0x23, true); '&' -> Pair(0x24, true); '*' -> Pair(0x25, true)
            '(' -> Pair(0x26, true); ')' -> Pair(0x27, true); '-' -> Pair(0x2D, false); '_' -> Pair(0x2D, true)
            '=' -> Pair(0x2E, false); '+' -> Pair(0x2E, true); '[' -> Pair(0x2F, false); '{' -> Pair(0x2F, true)
            ']' -> Pair(0x30, false); '}' -> Pair(0x30, true); '\\' -> Pair(0x31, false); '|' -> Pair(0x31, true)
            ';' -> Pair(0x33, false); ':' -> Pair(0x33, true); '\'' -> Pair(0x34, false); '"' -> Pair(0x34, true)
            '`' -> Pair(0x35, false); '~' -> Pair(0x35, true); ',' -> Pair(0x36, false); '<' -> Pair(0x36, true)
            '.' -> Pair(0x37, false); '>' -> Pair(0x37, true); '/' -> Pair(0x38, false); '?' -> Pair(0x38, true)
            else -> null
        }
    }

    private fun updateStatus(message: String) { if (::statusText.isInitialized) statusText.text = message }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) initializeBluetooth()
            else updateStatus("Bluetooth permission was not granted.")
        }
    }

    override fun onDestroy() {
        try {
            val hid = hidDevice
            if (hid != null) {
                if (registered) hid.unregisterApp()
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        } catch (_: Exception) { }
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
