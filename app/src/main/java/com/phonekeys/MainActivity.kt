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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ViewFlipper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executor

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var pasteTextInput: EditText
    private lateinit var liveTextInput: EditText
    private lateinit var inputPages: ViewFlipper
    private var activeInputPage = 0
    private var liveInputReady = false
    private lateinit var speedLabel: TextView
    private var typingIntervalMs = 35L
    private var     private var suppressInputWatcher = false
    private var selectedTheme = 0
    private val themeNames = listOf("Cyan", "Violet", "Emerald", "Amber", "Rose")
    private val themeAccents = listOf(
        Color.rgb(105, 232, 220),
        Color.rgb(183, 137, 255),
        Color.rgb(91, 224, 154),
        Color.rgb(255, 190, 92),
        Color.rgb(255, 117, 163)
    )
    private val accentColor: Int get() = themeAccents[selectedTheme]
    private var isSendingText = false
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
        val previousPasteDraft = if (::pasteTextInput.isInitialized) pasteTextInput.text.toString() else ""
        val previousLiveDraft = if (::liveTextInput.isInitialized) liveTextInput.text.toString() else ""
        val previousStatus = if (::statusText.isInitialized) statusText.text.toString() else "Starting PhoneKeys..."
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(22))
            setBackgroundColor(Color.rgb(12, 15, 25))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(page)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(25, 31, 49), 22)
        }
        header.addView(TextView(this).apply {
            text = "PHONEKEYS  /  BT HID"
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        })
        header.addView(TextView(this).apply {
            text = "Your phone. Your keyboard."
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(7), 0, dp(5))
        })
        statusText = TextView(this).apply {
            text = previousStatus
            textSize = 13f
            setTextColor(Color.rgb(180, 194, 220))
        }
        header.addView(statusText)
        page.addView(header, marginParams(bottom = 12))

        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectRow.addView(actionButton("Register", true) { registerKeyboard() }, weightedParams(1f, end = 5))
        connectRow.addView(actionButton("Unregister", false) { unregisterKeyboard() }, weightedParams(1f, start = 5))
        page.addView(connectRow, marginParams(bottom = 14))

        // Two independent input modes: the paste/send page and the live typing page.
        val pageTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.rgb(25, 31, 49), 16)
        }
        val pasteTab = actionButton("SEND TEXT", true) { showInputPage(0) }
        val liveTab = actionButton("LIVE INPUT  →", false) { showInputPage(1) }
        pageTabs.addView(pasteTab, weightedParams(1f, end = 4))
        pageTabs.addView(liveTab, weightedParams(1f, start = 4))
        page.addView(pageTabs, marginParams(bottom = 12))

        inputPages = ViewFlipper(this).apply {
            isMeasureAllChildren = true
            setInAnimation(this@MainActivity, android.R.anim.fade_in)
            setOutAnimation(this@MainActivity, android.R.anim.fade_out)
        }

        // PAGE 1: text to paste/type in full, then explicitly send to the PC.
        val pastePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val pasteSwipeHint = TextView(this).apply {
            text = "SEND TEXT  ·  swipe right for Live Input →"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(dp(2), dp(3), dp(2), dp(9))
            setOnTouchListener(swipeListener())
        }
        pastePage.addView(pasteSwipeHint)
        pastePage.addView(TextView(this).apply {
            text = "Paste or prepare a whole message here. It will only be sent when you tap the button."
            textSize = 12f
            setTextColor(Color.rgb(139, 151, 177))
            setPadding(dp(2), 0, 0, dp(9))
        })
        pasteTextInput = EditText(this).apply {
            hint = "Paste your message here…"
            setHintTextColor(Color.rgb(119, 130, 153))
            setTextColor(Color.rgb(25, 30, 43))
            textSize = 16f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(239, 243, 250), 16)
        }
        pasteTextInput.setText(previousPasteDraft)
        pastePage.addView(pasteTextInput, marginParams(bottom = 9))
        pastePage.addView(actionButton("SEND TEXT TO PC  →", true) {
            val content = pasteTextInput.text.toString()
            if (content.isBlank()) updateStatus("Paste or enter a message first.") else sendText(content)
        }, marginParams(bottom = 8))
        pastePage.addView(actionButton("CLEAR TEXT", false) {
            pasteTextInput.setText("")
        }, marginParams(bottom = 14))
        inputPages.addView(pastePage)

        // PAGE 2: every normal key typed here is sent live; no send button.
        val livePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val liveSwipeHint = TextView(this).apply {
            text = "← swipe left to return to Send Text"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(dp(2), dp(3), dp(2), dp(9))
            setOnTouchListener(swipeListener())
        }
        livePage.addView(liveSwipeHint)
        livePage.addView(TextView(this).apply {
            text = "Tap the box, use your regular phone keyboard, and each typed character goes straight to the PC. No send button."
            textSize = 12f
            setTextColor(Color.rgb(139, 151, 177))
            setPadding(dp(2), 0, 0, dp(9))
        })
        liveTextInput = EditText(this).apply {
            hint = "Live typing starts here…"
            setHintTextColor(Color.rgb(119, 130, 153))
            setTextColor(Color.rgb(25, 30, 43))
            textSize = 16f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(239, 243, 250), 16)
        }
        liveTextInput.setText(previousLiveDraft)
        liveInputReady = false
        liveTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!liveInputReady || s == null || isSendingText) return
                if (count > 0 && before == 0) {
                    val inserted = s.subSequence(start, start + count).toString()
                    if (inserted.length == 1) {
                        val ch = inserted[0]
                        if (ch == '\n' || ch == '\r') sendSpecial(0x28) else sendCharacter(ch)
                    } else {
                        // Multi-character insertion/paste in Live Input is intentionally sent immediately.
                        sendText(inserted)
                    }
                } else if (count == 0 && before == 1) {
                    sendSpecial(0x2A)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        livePage.addView(liveTextInput, marginParams(bottom = 9))
        livePage.addView(actionButton("CLEAR LIVE BOX", false) {
            liveInputReady = false
            liveTextInput.setText("")
            liveInputReady = true
        }, marginParams(bottom = 14))
        inputPages.addView(livePage)
        page.addView(inputPages, marginParams(bottom = 10))
        inputPages.displayedChild = activeInputPage.coerceIn(0, 1)
        liveInputReady = true

        addSectionTitle(page, "APPEARANCE", "Choose an accent theme for PhoneKeys.")
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        themeNames.forEachIndexed { index, name ->
            val themeButton = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index == selectedTheme) Color.rgb(12, 18, 28) else Color.rgb(222, 230, 245))
                background = rounded(if (index == selectedTheme) themeAccents[index] else Color.rgb(40, 48, 70), 12)
                minWidth = 0
                minHeight = dp(42)
                setPadding(dp(2), 0, dp(2), 0)
                stateListAnimator = null
                setOnClickListener {
                    if (selectedTheme != index) {
                        selectedTheme = index
                        buildInterface()
                    }
                }
            }
            val params = LinearLayout.LayoutParams(0, dp(42), 1f)
            params.setMargins(dp(2), 0, dp(2), 0)
            themeRow.addView(themeButton, params)
        }
        page.addView(themeRow, marginParams(bottom = 16))

        val speedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = rounded(Color.rgb(25, 31, 49), 17)
        }
        val speedTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedTop.addView(TextView(this).apply {
            text = "TEXT SPEED"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Color.rgb(173, 187, 215))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedLabel = TextView(this).apply {
            text = "VERY FAST · 35 ms"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }
        speedTop.addView(speedLabel)
        speedCard.addView(speedTop)
        val speedSeek = SeekBar(this).apply {
            max = 175
            progress = (typingIntervalMs.toInt() - 25).coerceIn(0, 175)
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(66, 77, 103))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    typingIntervalMs = (progress + 25).toLong()
                    val label = when {
                        typingIntervalMs <= 35 -> "VERY FAST"
                        typingIntervalMs <= 60 -> "FAST"
                        typingIntervalMs <= 110 -> "NORMAL"
                        else -> "SLOW / SAFE"
                    }
                    speedLabel.text = "$label · ${typingIntervalMs} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        speedCard.addView(speedSeek)
        speedCard.addView(TextView(this).apply {
            text = "Lower delay = faster pasted text. If the PC misses characters, move the slider right."
            textSize = 12f
            setTextColor(Color.rgb(151, 164, 190))
        })
        page.addView(speedCard, marginParams(bottom = 18))

        page.addView(TextView(this).apply {
            text = "Send Text is for messages you want to review before sending. Live Input sends normal typing immediately. Basic Bluetooth HID does not support emoji or all Unicode characters."
            textSize = 12f
            setTextColor(Color.rgb(132, 145, 171))
            setPadding(dp(2), dp(5), dp(2), dp(4))
        }, marginParams(bottom = 8))
        setContentView(scroll)
    }

    private fun showInputPage(index: Int) {
        activeInputPage = index.coerceIn(0, 1)
        if (::inputPages.isInitialized) inputPages.displayedChild = activeInputPage
        updateStatus(if (activeInputPage == 0) "Send Text mode: review a message, then send it." else "Live Input mode: typed characters send immediately.")
    }

    private fun swipeListener(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > dp(55) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        showInputPage(if (dx > 0) 1 else 0)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
            setPadding(dp(2), dp(2), 0, dp(4))
        })
        parent.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(139, 151, 177))
            setPadding(dp(2), 0, 0, dp(9))
        })
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.rgb(12, 25, 33) else Color.rgb(220, 229, 245))
            background = rounded(if (primary) accentColor else Color.rgb(40, 48, 70), 14)
            minHeight = dp(48)
            stateListAnimator = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { action() }
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun marginParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun weightedParams(weight: Float, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            setMargins(dp(start), 0, dp(end), 0)
        }

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
        if (isSendingText) { updateStatus("Text is already being sent. Wait for it to finish."); return }
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
        val interval = typingIntervalMs.coerceAtLeast(25L)
        val releaseDelay = minOf(18L, interval - 2L).coerceAtLeast(8L)
        val hid = hidDevice ?: return
        isSendingText = true
        updateStatus("Typing ${supported.size} keystrokes · ${interval} ms interval…")
        supported.forEachIndexed { index, key ->
            mainHandler.postDelayed({
                try {
                    val pressed = byteArrayOf(key.second.toByte(), 0, key.first.toByte(), 0, 0, 0, 0, 0)
                    val sent = hid.sendReport(target, 0, pressed)
                    if (!sent) updateStatus("PC rejected a key report. Try a slower speed.")
                    mainHandler.postDelayed({
                        try { hid.sendReport(target, 0, ByteArray(8)) } catch (_: Exception) { }
                        if (index == supported.lastIndex) {
                            isSendingText = false
                            val skipped = chars.size - supported.size
                            updateStatus(if (skipped > 0) "Finished. Skipped $skipped unsupported character(s)." else "Finished sending text!")
                        }
                    }, releaseDelay)
                } catch (e: Exception) {
                    isSendingText = false
                    updateStatus("Text send interrupted: ${e.message}")
                }
            }, index * interval)
        }
        if (supported.size < chars.size) {
            updateStatus("Sending text; unsupported emoji/Unicode characters will be skipped.")
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
