package com.phonekeys

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.BaseInputConnection
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
    private var livePreviousText = ""
    // One ordered queue is shared by live typing and pasted-text sending so HID
    // key-down/key-up reports can never interleave between the two modes.
    private data class QueuedKey(val keyCode: Int, val modifier: Int)
    private val liveKeyQueue = java.util.ArrayDeque<QueuedKey>()
    private var liveKeySending = false
    private var pendingSkippedCharacters = 0
    private lateinit var speedLabel: TextView
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var pasteTab: Button
    private lateinit var liveTab: Button
    private var typingIntervalMs = 35L
    private var suppressInputWatcher = false
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
            keyboardRegistered = registered
            setLinkState(if (!registered) 0 else if (pluggedDevice != null) 3 else 1)
            if (registered) {
                updateStatus("PhoneKeys keyboard registered. Connect a computer or compatible Android phone in Bluetooth settings.")
                requestHostDiscoverability()
            } else {
                updateStatus("Keyboard is not registered.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    setLinkState(3)
                    val hostName = try { device?.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                    updateStatus("Connected${hostName?.let { " to $it" } ?: " to a host device"}. Ready to type!")
                }
                BluetoothProfile.STATE_CONNECTING -> { setLinkState(2); updateStatus("Connecting to Bluetooth host...") }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    setLinkState(if (registered) 1 else 0)
                    updateStatus("Host disconnected. Reconnect from the other device's Bluetooth settings.")
                }
                else -> updateStatus("Bluetooth state: $state")
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registered = keyboardRegistered
                updateStatus(if (registered) "Bluetooth HID available. Keyboard session is active in the background." else "Bluetooth HID available. Tap Register keyboard.")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                registered = false
                keyboardRegistered = false
                setLinkState(0)
                updateStatus("Bluetooth HID service disconnected. Reopen PhoneKeys and register again if needed.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKDROP_BASE
        window.navigationBarColor = BACKDROP_BASE
        buildInterface()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_BLUETOOTH)
        } else initializeBluetooth()
    }

    private val textSecondary = Color.argb(170, 255, 255, 255)
    private val textTertiary = Color.argb(105, 255, 255, 255)

    private fun buildInterface() {
        val previousPasteDraft = if (::pasteTextInput.isInitialized) pasteTextInput.text.toString() else ""
        val previousLiveDraft = if (::liveTextInput.isInitialized) liveTextInput.text.toString() else ""
        val previousStatus = if (::statusText.isInitialized) statusText.text.toString() else "Starting PhoneKeys..."

        val root = FrameLayout(this)
        root.addView(
            GlassBackdrop(this, accentColor),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(page)
        root.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        page.addView(TextView(this).apply {
            text = "PhoneKeys"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.01f
            setTextColor(Color.WHITE)
        })
        page.addView(TextView(this).apply {
            text = "Use this phone as a Bluetooth keyboard"
            textSize = 14f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, 0)
        }, marginParams(bottom = 20))

        
        val statusCard = glassCard(strong = true)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(18), dp(18)))
        statusTitle = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        statusRow.addView(statusTitle)
        statusCard.addView(statusRow)
        statusText = TextView(this).apply {
            text = previousStatus
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(textSecondary)
            setPadding(0, dp(8), 0, dp(16))
        }
        statusCard.addView(statusText)
        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectRow.addView(actionButton("Register keyboard", true) { registerKeyboard() }, weightedParams(1.4f, end = 4))
        connectRow.addView(actionButton("Unregister", false) { unregisterKeyboard() }, weightedParams(1f, start = 4))
        statusCard.addView(connectRow)
        page.addView(statusCard, marginParams(bottom = 16))

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glass(18)
        }
        pasteTab = tabButton("Send text") { showInputPage(0) }
        liveTab = tabButton("Live input") { showInputPage(1) }
        tabBar.addView(pasteTab, weightedParams(1f, end = 2))
        tabBar.addView(liveTab, weightedParams(1f, start = 2))
        page.addView(tabBar, marginParams(bottom = 12))

        val inputCard = glassCard()
        inputPages = ViewFlipper(this).apply {
            measureAllChildren = true
            setInAnimation(this@MainActivity, android.R.anim.fade_in)
            setOutAnimation(this@MainActivity, android.R.anim.fade_out)
        }
        
        val pastePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pastePage.addView(caption("Write or paste a message, review it, then send it all at once.").apply {
            setOnTouchListener(swipeListener())
        })
        pasteTextInput = inputField("Paste your message here…")
        pasteTextInput.setText(previousPasteDraft)
        pastePage.addView(pasteTextInput, marginParams())
        val pasteActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pasteActions.addView(actionButton("Clear", false) {
            pasteTextInput.setText("")
        }, weightedParams(1f, end = 4))
        pasteActions.addView(actionButton("Send to device", true) {
            val content = pasteTextInput.text.toString()
            if (content.isBlank()) updateStatus("Paste or enter a message first.") else sendText(content)
        }, weightedParams(1.6f, start = 4))
        pastePage.addView(pasteActions, marginParams(top = 12))
        inputPages.addView(pastePage)

        val livePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        livePage.addView(caption("Type with your normal phone keyboard. Each character is sent to the connected device as you type.").apply {
            setOnTouchListener(swipeListener())
        })
        liveTextInput = inputField("Live typing starts here…")
        liveTextInput.setText(previousLiveDraft)
        livePreviousText = previousLiveDraft
        liveInputReady = false
        liveTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s == null || !liveInputReady || suppressInputWatcher) return

                val composingStart = BaseInputConnection.getComposingSpanStart(s)
                val composingEnd = BaseInputConnection.getComposingSpanEnd(s)
                if (composingStart >= 0 && composingEnd >= composingStart) return

                val oldText = livePreviousText
                val newText = s.toString()
                if (oldText == newText) return
                livePreviousText = newText

                var prefix = 0
                while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
                var suffix = 0
                while (suffix < oldText.length - prefix && suffix < newText.length - prefix &&
                    oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

                val removedCount = oldText.length - prefix - suffix
                val inserted = newText.substring(prefix, newText.length - suffix)
                repeat(removedCount) { sendSpecial(0x2A) }
                inserted.forEach { ch ->
                    when (ch) {
                        '\n', '\r' -> sendSpecial(0x28)
                        '\t' -> sendSpecial(0x2B)
                        else -> sendCharacter(ch)
                    }
                }
            }
        })
        livePage.addView(liveTextInput, marginParams())
        livePage.addView(actionButton("Clear", false) {
            liveInputReady = false
            liveTextInput.setText("")
            livePreviousText = ""
            liveInputReady = true
        }, marginParams(top = 12))
        inputPages.addView(livePage)
        inputCard.addView(inputPages)
        page.addView(inputCard, marginParams(bottom = 16))
        inputPages.displayedChild = activeInputPage.coerceIn(0, 1)
        liveInputReady = true

        val speedCard = glassCard()
        val speedTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedTop.addView(TextView(this).apply {
            text = "Typing speed"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedLabel = TextView(this).apply {
            text = speedText(typingIntervalMs)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accentColor)
        }
        speedTop.addView(speedLabel)
        speedCard.addView(speedTop)
        val speedSeek = SeekBar(this).apply {
            max = 175
            progress = (typingIntervalMs.toInt() - 25).coerceIn(0, 175)
            progressTintList = ColorStateList.valueOf(accentColor)
            thumbTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    typingIntervalMs = (progress + 25).toLong()
                    speedLabel.text = speedText(typingIntervalMs)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        speedCard.addView(speedSeek, marginParams(top = 8, bottom = 4))
        speedCard.addView(caption("A shorter delay sends text faster. If the receiving device misses characters, move the slider right.", bottom = 0))
        page.addView(speedCard, marginParams(bottom = 16))

        val accentCard = glassCard()
        addSectionTitle(accentCard, "Accent color", "Changes the background glow and highlights.")
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeAccents.forEachIndexed { index, color ->
            val cell = FrameLayout(this)
            val swatch = View(this).apply {
                contentDescription = themeNames[index]
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (index == selectedTheme) setStroke(dp(3), Color.WHITE)
                    else setStroke(dp(1), Color.argb(90, 255, 255, 255))
                }
                setOnClickListener {
                    if (selectedTheme != index) {
                        selectedTheme = index
                        buildInterface()
                    }
                }
            }
            cell.addView(swatch, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            swatchRow.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        accentCard.addView(swatchRow)
        page.addView(accentCard, marginParams(bottom = 18))

        page.addView(TextView(this).apply {
            text = "Basic Bluetooth keyboards can't type emoji or most non-ASCII characters. Unsupported characters are skipped."
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(textTertiary)
            setPadding(dp(8), 0, dp(8), 0)
        }, marginParams())

        setContentView(root)
        setLinkState(linkState)
        refreshTabs()
    }

    private fun showInputPage(index: Int) {
        activeInputPage = index.coerceIn(0, 1)
        if (::inputPages.isInitialized) inputPages.displayedChild = activeInputPage
        refreshTabs()
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

    private fun speedText(ms: Long): String {
        val label = when {
            ms <= 35 -> "Very fast"
            ms <= 60 -> "Fast"
            ms <= 110 -> "Normal"
            else -> "Slow and safe"
        }
        return "$label, $ms ms"
    }

    /** Translucent frosted panel: diagonal white sheen plus a hairline light border. */
    private fun glass(radiusDp: Int, strong: Boolean = false): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(if (strong) 58 else 42, 255, 255, 255),
                Color.argb(if (strong) 24 else 14, 255, 255, 255)
            )
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.argb(if (strong) 95 else 62, 255, 255, 255))
        }

    private fun glassCard(strong: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = glass(24, strong)
    }

    private fun withRipple(content: GradientDrawable, radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, mask)
    }

    private fun caption(text: String, bottom: Int = 12): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(textSecondary)
        setPadding(0, 0, 0, dp(bottom))
    }

    private fun fieldBackground(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(95, 6, 8, 20))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), if (focused) withAlpha(accentColor, 210) else Color.argb(50, 255, 255, 255))
    }

    private fun inputField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(textTertiary)
        setTextColor(Color.WHITE)
        textSize = 16f
        minLines = 5
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = fieldBackground(false)
        setOnFocusChangeListener { v, hasFocus -> v.background = fieldBackground(hasFocus) }
    }

    private fun addSectionTitle(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        parent.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, dp(12))
        })
    }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit): Button {
        val radius = 16
        val face: GradientDrawable = if (primary) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blendWithWhite(accentColor, 0.22f), accentColor)
            ).apply { cornerRadius = dp(radius).toFloat() }
        } else glass(radius)
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.rgb(8, 12, 24) else Color.argb(235, 255, 255, 255))
            background = withRipple(face, radius)
            minHeight = dp(50)
            stateListAnimator = null
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun tabButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(44)
        stateListAnimator = null
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { action() }
    }

    private fun styleTab(button: Button, selected: Boolean) {
        val face = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (selected) {
                setColor(withAlpha(accentColor, 75))
                setStroke(dp(1), withAlpha(accentColor, 160))
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
        button.background = withRipple(face, 14)
        button.setTextColor(if (selected) Color.WHITE else textSecondary)
    }

    private fun refreshTabs() {
        if (!::pasteTab.isInitialized || !::liveTab.isInitialized) return
        styleTab(pasteTab, activeInputPage == 0)
        styleTab(liveTab, activeInputPage == 1)
    }

    private fun setLinkState(state: Int) {
        linkState = state
        if (!::statusDot.isInitialized || !::statusTitle.isInitialized) return
        val (title, color) = when (state) {
            3 -> "Connected" to Color.rgb(94, 224, 154)
            2 -> "Connecting" to Color.rgb(255, 190, 92)
            1 -> "Ready to pair" to Color.rgb(255, 190, 92)
            else -> "Not registered" to Color.rgb(150, 160, 185)
        }
        statusTitle.text = title
        val glow = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(color, 75)) }
        val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusDot.background = LayerDrawable(arrayOf(glow, core)).apply {
            setLayerInset(1, dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun blendWithWhite(color: Int, fraction: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * fraction).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * fraction).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt()
    )

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
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_BLUETOOTH); return
        }
        val hid = hidDevice
        if (hid == null) { updateStatus("HID service is not ready. Restart the app."); return }
        try {
            startKeyboardForegroundService()
            val settings = BluetoothHidDeviceAppSdpSettings("PhoneKeys", "PhoneKeys Bluetooth Keyboard", "PhoneKeys", BluetoothHidDevice.SUBCLASS1_KEYBOARD, KEYBOARD_DESCRIPTOR)
            val executor = Executor { command -> runOnUiThread { command.run() } }
            val accepted = hid.registerApp(settings, null, null, executor, callback)
            updateStatus(if (accepted) "Registration requested. Waiting for Android..." else "Android rejected the registration request.")
        } catch (e: SecurityException) { updateStatus("Bluetooth permission denied.") }
        catch (e: Exception) { updateStatus("Registration error: ${e.message}") }
    }

    private fun startKeyboardForegroundService() {
        try {
            val intent = android.content.Intent(this, KeyboardKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            updateStatus("Could not start background keyboard service: ${e.message}")
        }
    }

    private fun unregisterKeyboard() {
        try {
            val hid = hidDevice ?: run { updateStatus("HID service is not ready."); return }
            hid.unregisterApp()
            registered = false
            keyboardRegistered = false
            setLinkState(0)
            stopService(android.content.Intent(this, KeyboardKeepAliveService::class.java))
            updateStatus("Keyboard unregistered. Background keyboard service stopped.")
        } catch (e: Exception) { updateStatus("Unregister error: ${e.message}") }
    }


    private fun sendSpecial(keyCode: Int, modifier: Int = 0) {
        enqueueLiveKey(keyCode, modifier)
    }

    private fun sendCharacter(character: Char) {
        val pair = keyForCharacter(character)
        if (pair == null) {
            updateStatus("Character '$character' is not supported by the basic keyboard layout.")
            return
        }
        enqueueLiveKey(pair.first, if (pair.second) 0x02 else 0)
    }

    private fun enqueueLiveKey(keyCode: Int, modifier: Int = 0) {
        if (getConnectedHost() == null) return
        liveKeyQueue.addLast(QueuedKey(keyCode, modifier))
        drainLiveKeyQueue()
    }

    private fun drainLiveKeyQueue() {
        if (liveKeySending) return
        if (liveKeyQueue.isEmpty()) {
            if (isSendingText) {
                isSendingText = false
                updateStatus(if (pendingSkippedCharacters > 0) {
                    "Finished sending text. Skipped $pendingSkippedCharacters unsupported character(s)."
                } else "Finished sending text!")
                pendingSkippedCharacters = 0
            }
            return
        }
        val target = getConnectedHost() ?: run { liveKeyQueue.clear(); isSendingText = false; return }
        val hid = hidDevice ?: run { liveKeyQueue.clear(); isSendingText = false; return }
        val key = liveKeyQueue.removeFirst()
        liveKeySending = true
        try {
            val pressed = byteArrayOf(key.modifier.toByte(), 0, key.keyCode.toByte(), 0, 0, 0, 0, 0)
            if (!hid.sendReport(target, 0, pressed)) {
                updateStatus("Host rejected a key report. Reconnect or try a slower speed.")
            }
            val interval = typingIntervalMs.coerceAtLeast(25L)
            val releaseDelay = minOf(18L, interval - 2L).coerceAtLeast(8L)
            mainHandler.postDelayed({
                try { hid.sendReport(target, 0, ByteArray(8)) }
                catch (_: Exception) { updateStatus("Could not release a key. Reconnect and retry.") }
                mainHandler.postDelayed({
                    liveKeySending = false
                    drainLiveKeyQueue()
                }, (interval - releaseDelay).coerceAtLeast(1L))
            }, releaseDelay)
        } catch (e: SecurityException) {
            liveKeySending = false
            updateStatus("Bluetooth permission denied.")
        } catch (e: Exception) {
            liveKeySending = false
            updateStatus("Keyboard send error: ${e.message}")
        }
    }

    private fun sendReportStroke(keyCode: Int, modifier: Int = 0, delayMs: Long = 55L) {
        val target = getConnectedHost() ?: return
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
        if (getConnectedHost() == null) return

        val supported = ArrayList<QueuedKey>()
        var skipped = 0
        text.forEach { ch ->
            when (ch) {
                '\n', '\r' -> supported.add(QueuedKey(0x28, 0))
                '\t' -> supported.add(QueuedKey(0x2B, 0))
                else -> {
                    val pair = keyForCharacter(ch)
                    if (pair == null) skipped++
                    else supported.add(QueuedKey(pair.first, if (pair.second) 0x02 else 0))
                }
            }
        }
        if (supported.isEmpty()) { updateStatus("No supported characters found."); return }

        isSendingText = true
        pendingSkippedCharacters = skipped
        liveKeyQueue.addAll(supported)
        updateStatus("Sending ${supported.size} keystrokes to the connected host · ${typingIntervalMs} ms interval…")
        if (skipped > 0) {
            mainHandler.postDelayed({
                if (isSendingText) updateStatus("Sending text; $skipped unsupported emoji/Unicode character(s) will be skipped.")
            }, 500L)
        }
        drainLiveKeyQueue()
    }

    private fun getConnectedHost(): BluetoothDevice? {
        try {
            val hid = hidDevice
            if (hid == null || !registered) {
                updateStatus("Register the PhoneKeys keyboard first, then connect a computer or compatible Android phone.")
                return null
            }
            val host = hid.connectedDevices.firstOrNull()
            if (host == null) {
                updateStatus("No connected host. In the other device's Bluetooth settings, connect to PhoneKeys or this phone's advertised keyboard service.")
                return null
            }
            return host
        } catch (e: SecurityException) {
            updateStatus("Bluetooth permission denied.")
            return null
        } catch (e: Exception) {
            updateStatus("Could not check Bluetooth host: ${e.message}")
            return null
        }
    }

    private fun requestHostDiscoverability() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_ADVERTISE)
            return
        }
        try {
            val intent = android.content.Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivity(intent)
        } catch (e: Exception) {
            updateStatus("Keyboard registered. If PhoneKeys isn't visible, open Bluetooth settings and enable visibility.")
        }
    }

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
        } else if (requestCode == REQUEST_ADVERTISE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestHostDiscoverability()
            else updateStatus("Keyboard registered. Enable Bluetooth visibility manually if the other phone cannot find this device.")
        }
    }

    override fun onDestroy() {

        super.onDestroy()
    }

    companion object {
        @Volatile private var keyboardRegistered = false
        @Volatile private var linkState = 0
        private const val REQUEST_BLUETOOTH = 100
        private const val REQUEST_ADVERTISE = 101
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

private val BACKDROP_BASE = Color.rgb(9, 11, 24)

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

private fun shiftHue(color: Int, degrees: Float): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color.HSVToColor(hsv)
}


private class GlassBackdrop(context: Context, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(BACKDROP_BASE)
        drawOrb(canvas, w * 0.90f, h * 0.06f, w * 0.85f, accent, 135)
        drawOrb(canvas, w * 0.00f, h * 0.40f, w * 0.75f, shiftHue(accent, 70f), 95)
        drawOrb(canvas, w * 0.95f, h * 0.85f, w * 0.90f, shiftHue(accent, -70f), 105)
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
}
