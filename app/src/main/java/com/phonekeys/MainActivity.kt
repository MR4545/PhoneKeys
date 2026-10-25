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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
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
    // Live input mirrors the receiving device: what it currently holds, where its caret is,
    // and how many unsupported characters we have already warned about.
    private var liveRemoteText = ""
    private var liveRemoteCursor = 0
    private var liveUnsupportedShown = 0
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
    private val scene = GlassScene()
    private val frostedHosts = ArrayList<View>()
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
        window.statusBarColor = SCENE_TOP
        window.navigationBarColor = SCENE_BOTTOM
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

        // Fixed colorful backdrop; the scrolling glass cards blur whatever is behind them.
        frostedHosts.clear()
        val root = FrameLayout(this)
        root.addView(
            GlassBackdrop(this, scene),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(page)
        // The frosted panels sample the backdrop by position, so redraw them when anything moves.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> frostedHosts.forEach { it.invalidate() } }
        root.viewTreeObserver.addOnGlobalLayoutListener { frostedHosts.forEach { it.invalidate() } }
        root.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // ---- Header ----
        page.addView(TextView(this).apply {
            text = "PhoneKeys"
            textSize = 36f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.01f
            setTextColor(Color.WHITE)
        })
        page.addView(TextView(this).apply {
            text = "Use this phone as a Bluetooth keyboard"
            textSize = 14f
            setTextColor(textSecondary)
            setPadding(0, dp(4), 0, 0)
        }, marginParams(bottom = 28))

        // ---- Connection card: live status + register / unregister ----
        val statusCard = glassCard()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(44), dp(44)))
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        statusTitle = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        statusColumn.addView(statusTitle)
        statusText = TextView(this).apply {
            text = previousStatus
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(textSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        statusColumn.addView(statusText)
        statusRow.addView(statusColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(statusRow)
        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectRow.addView(actionButton("Register keyboard", true) { registerKeyboard() }, weightedParams(1.4f, end = 4))
        connectRow.addView(actionButton("Unregister", false) { unregisterKeyboard() }, weightedParams(1f, start = 4))
        statusCard.addView(connectRow, marginParams(top = 18))
        page.addView(statusCard, marginParams(bottom = 12))

        // ---- Segmented control: Send text / Live input ----
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            frost(this, 100)
        }
        pasteTab = tabButton("Send text") { showInputPage(0) }
        liveTab = tabButton("Live input") { showInputPage(1) }
        tabBar.addView(pasteTab, weightedParams(1f, end = 2))
        tabBar.addView(liveTab, weightedParams(1f, start = 2))
        page.addView(tabBar, marginParams(bottom = 12))

        // ---- Input card (two pages) ----
        val inputCard = glassCard()
        inputPages = ViewFlipper(this).apply {
            measureAllChildren = true
            setInAnimation(this@MainActivity, android.R.anim.fade_in)
            setOutAnimation(this@MainActivity, android.R.anim.fade_out)
        }

        // PAGE 1: prepare a whole message, then send it explicitly.
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

        // PAGE 2: every committed character is sent immediately.
        val livePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        livePage.addView(caption("Type with your normal phone keyboard. Typing, deleting and cursor moves are mirrored on the connected device as you go.").apply {
            setOnTouchListener(swipeListener())
        })
        liveTextInput = inputField("Live typing starts here…", live = true)
        liveTextInput.setText(previousLiveDraft)
        resetLiveSync(previousLiveDraft)
        liveInputReady = false
        liveTextInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { syncLiveToRemote() }
        })
        // Moving the caret (tapping, dragging, space-bar swipe) moves the caret on the other device too.
        (liveTextInput as? LiveEditText)?.onSelectionChange = { syncLiveToRemote() }
        livePage.addView(liveTextInput, marginParams())
        livePage.addView(actionButton("Clear", false) {
            liveInputReady = false
            liveTextInput.setText("")
            resetLiveSync("")
            liveInputReady = true
        }, marginParams(top = 12))
        inputPages.addView(livePage)
        inputCard.addView(inputPages)
        page.addView(inputCard, marginParams(bottom = 12))
        inputPages.displayedChild = activeInputPage.coerceIn(0, 1)
        liveInputReady = true

        // ---- Typing speed ----
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
            setTextColor(Color.argb(210, 255, 255, 255))
        }
        speedTop.addView(speedLabel)
        speedCard.addView(speedTop)
        val speedSeek = SeekBar(this).apply {
            max = 175
            splitTrack = false
            progressDrawable = seekTrack()
            thumb = seekThumb()
            progress = (typingIntervalMs.toInt() - 25).coerceIn(0, 175)
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
        page.addView(speedCard, marginParams(bottom = 18))

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

    // ---------------------------------------------------------------------
    // Glass UI building blocks
    // ---------------------------------------------------------------------

    private fun speedText(ms: Long): String {
        val label = when {
            ms <= 35 -> "Very fast"
            ms <= 60 -> "Fast"
            ms <= 110 -> "Normal"
            else -> "Slow and safe"
        }
        return "$label, $ms ms"
    }

    /** Plain translucent glass for small elements that sit on top of a frosted card. */
    private fun glass(radiusDp: Int, strong: Boolean = false): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(if (strong) 92 else 34, 255, 255, 255),
                Color.argb(if (strong) 58 else 16, 255, 255, 255)
            )
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.argb(if (strong) 140 else 56, 255, 255, 255))
        }

    /** Gives a view real frosted-glass background: a blurred copy of the backdrop, tinted and edge-lit. */
    private fun frost(view: View, radiusDp: Int) {
        view.background = FrostedDrawable(view, scene, dp(radiusDp).toFloat())
        frostedHosts.add(view)
    }

    private fun glassCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        frost(this, 28)
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
        setColor(Color.argb(72, 4, 6, 18))
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), if (focused) Color.argb(175, 255, 255, 255) else Color.argb(40, 255, 255, 255))
    }

    private fun inputField(hintText: String, live: Boolean = false): EditText = (if (live) LiveEditText(this) else EditText(this)).apply {
        hint = hintText
        setHintTextColor(textTertiary)
        setTextColor(Color.WHITE)
        textSize = 16f
        minLines = 5
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        inputType = if (live) {
            // No suggestions or autocorrect: keyboards then commit each letter immediately.
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        setPadding(dp(18), dp(16), dp(18), dp(16))
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
        val radius = 100 // clamps to a full pill
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else Color.argb(220, 255, 255, 255))
            background = withRipple(glass(radius, strong = primary), radius)
            minHeight = dp(52)
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
            cornerRadius = dp(100).toFloat()
            if (selected) {
                setColor(Color.argb(66, 255, 255, 255))
                setStroke(dp(1), Color.argb(110, 255, 255, 255))
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
        button.background = withRipple(face, 100)
        button.setTextColor(if (selected) Color.WHITE else textSecondary)
    }

    private fun refreshTabs() {
        if (!::pasteTab.isInitialized || !::liveTab.isInitialized) return
        styleTab(pasteTab, activeInputPage == 0)
        styleTab(liveTab, activeInputPage == 1)
    }

    /** 0 = not registered, 1 = ready to pair, 2 = connecting, 3 = connected. */
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
        val glow = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(color, 55)) }
        val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusDot.background = LayerDrawable(arrayOf(glow, core)).apply {
            setLayerInset(1, dp(15), dp(15), dp(15), dp(15))
        }
    }

    private fun seekTrack(): LayerDrawable {
        val track = GradientDrawable().apply {
            setColor(Color.argb(55, 255, 255, 255))
            cornerRadius = dp(4).toFloat()
            setSize(-1, dp(8))
        }
        val fill = ClipDrawable(
            GradientDrawable().apply {
                setColor(Color.argb(235, 255, 255, 255))
                cornerRadius = dp(4).toFloat()
                setSize(-1, dp(8))
            },
            Gravity.START, ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf<Drawable>(track, fill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun seekThumb(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setSize(dp(26), dp(26))
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

    // Both Live Input and Send Text use this same ordered HID queue.
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

    private fun resetLiveSync(text: String) {
        liveRemoteText = remoteForm(text)
        liveRemoteCursor = liveRemoteText.length
        liveUnsupportedShown = text.count { !isRemoteTypable(it) && it != '\r' }
    }

    private fun isRemoteTypable(ch: Char): Boolean = ch == '\n' || ch == '\t' || keyForCharacter(ch) != null

    /** The text as the receiving device will actually see it: only characters the basic layout can type. */
    private fun remoteForm(text: String): String = text.filter { isRemoteTypable(it) }

    private fun typeRemoteChar(ch: Char) {
        when (ch) {
            '\n' -> enqueueLiveKey(0x28)
            '\t' -> enqueueLiveKey(0x2B)
            else -> keyForCharacter(ch)?.let { enqueueLiveKey(it.first, if (it.second) 0x02 else 0) }
        }
    }

    /** Presses Left or Right arrow until the remote caret sits at [target]. */
    private fun moveRemoteCursorTo(target: Int) {
        val clamped = target.coerceIn(0, liveRemoteText.length)
        val delta = clamped - liveRemoteCursor
        if (delta > 0) repeat(delta) { enqueueLiveKey(0x4F) }       // Right arrow
        else if (delta < 0) repeat(-delta) { enqueueLiveKey(0x50) } // Left arrow
        liveRemoteCursor = clamped
    }

    /**
     * Makes the receiving device match the live box. Safe to call as often as you like
     * (every text change and every caret move): it only sends what is actually different.
     *  1. Text: find the changed range, move the remote caret to its end, backspace the removed
     *     characters, then type the new ones. This works anywhere in the text, not just at the end.
     *  2. Caret: if the local caret is somewhere else, press arrows until the remote caret matches.
     */
    private fun syncLiveToRemote() {
        if (!liveInputReady || suppressInputWatcher || !::liveTextInput.isInitialized) return
        val text = liveTextInput.text?.toString() ?: return
        val remoteText = remoteForm(text)
        val selStart = liveTextInput.selectionStart
        val selEnd = liveTextInput.selectionEnd
        val caretTarget = if (selStart >= 0 && selStart == selEnd) {
            remoteForm(text.substring(0, selStart.coerceAtMost(text.length))).length
        } else -1
        if (remoteText == liveRemoteText && (caretTarget < 0 || caretTarget == liveRemoteCursor)) return
        if (getConnectedHost() == null) return // nothing is sent; the next change catches up

        if (remoteText != liveRemoteText) {
            val old = liveRemoteText
            var prefix = 0
            while (prefix < old.length && prefix < remoteText.length && old[prefix] == remoteText[prefix]) prefix++
            var suffix = 0
            while (suffix < old.length - prefix && suffix < remoteText.length - prefix &&
                old[old.length - 1 - suffix] == remoteText[remoteText.length - 1 - suffix]) suffix++
            val removed = old.length - prefix - suffix
            val inserted = remoteText.substring(prefix, remoteText.length - suffix)

            moveRemoteCursorTo(prefix + removed)
            repeat(removed) { enqueueLiveKey(0x2A) } // Backspace
            inserted.forEach { typeRemoteChar(it) }
            liveRemoteText = remoteText
            liveRemoteCursor = prefix + inserted.length
        }
        if (caretTarget >= 0) moveRemoteCursorTo(caretTarget)

        val unsupported = text.count { !isRemoteTypable(it) && it != '\r' }
        if (unsupported > liveUnsupportedShown) {
            updateStatus("Skipped a character the basic keyboard layout can't type (emoji and some symbols).")
        }
        liveUnsupportedShown = unsupported
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
        // HID registration exposes the keyboard service. Discoverability helps a
        // nearby phone find this handset during pairing; Android still asks the
        // user to approve the visibility window.
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
        } else if (requestCode == REQUEST_ADVERTISE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestHostDiscoverability()
            else updateStatus("Keyboard registered. Enable Bluetooth visibility manually if the other phone cannot find this device.")
        }
    }

    override fun onDestroy() {
        // Do not unregister HID or close the profile proxy here. Android can destroy
        // the Activity while the user switches apps; the foreground service keeps the
        // keyboard session alive until the user explicitly taps Unregister.
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

// ---------------------------------------------------------------------------
// Frosted glass rendering
// ---------------------------------------------------------------------------

private val SCENE_TOP = Color.rgb(14, 10, 46)
private val SCENE_MID = Color.rgb(10, 12, 40)
private val SCENE_BOTTOM = Color.rgb(6, 8, 24)

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/**
 * The vivid scene behind the UI, plus a heavily blurred copy of it. Every frosted panel
 * draws the matching part of the blurred copy, which is what makes it look like real glass.
 */
private class GlassScene {
    var anchor: View? = null
    var sharp: Bitmap? = null
    var blurred: Bitmap? = null

    fun build(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paintScene(Canvas(bmp), w.toFloat(), h.toFloat())
        sharp = bmp

        // Cheap, device-independent blur: repeatedly halve, then repeatedly double with filtering.
        var cur = bmp
        repeat(5) { cur = Bitmap.createScaledBitmap(cur, maxOf(1, cur.width / 2), maxOf(1, cur.height / 2), true) }
        repeat(5) { cur = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true) }
        blurred = if (cur.width == w && cur.height == h) cur else Bitmap.createScaledBitmap(cur, w, h, true)
    }

    private fun paintScene(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(SCENE_TOP, SCENE_MID, SCENE_BOTTOM), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        blob(c, p, w * 0.80f, h * 0.15f, w * 0.28f, Color.rgb(110, 80, 240), Color.rgb(230, 70, 150))
        blob(c, p, w * 0.10f, h * 0.38f, w * 0.24f, Color.rgb(30, 170, 230), Color.rgb(50, 90, 230))
        blob(c, p, w * 0.90f, h * 0.60f, w * 0.20f, Color.rgb(240, 150, 50), Color.rgb(240, 80, 110))
        blob(c, p, w * 0.22f, h * 0.84f, w * 0.32f, Color.rgb(130, 80, 230), Color.rgb(30, 180, 200))
        blob(c, p, w * 0.78f, h * 0.93f, w * 0.15f, Color.rgb(230, 70, 150), Color.rgb(240, 150, 50))
    }

    private fun blob(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, from: Int, to: Int) {
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, from, to, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }
}

/** EditText that reports caret moves, so live input can mirror them on the other device. */
private class LiveEditText(context: Context) : EditText(context) {
    var onSelectionChange: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChange?.invoke()
    }
}

/** Full-screen view that shows the sharp scene. */
private class GlassBackdrop(context: Context, private val scene: GlassScene) : View(context) {
    init { scene.anchor = this }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scene.build(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        scene.sharp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}

/**
 * Frosted glass: blurred backdrop clipped to a rounded shape, a smoked tint so white text
 * stays readable, a soft top sheen, and a light edge that fades from top-left to bottom-right.
 */
private class FrostedDrawable(
    private val host: View,
    private val scene: GlassScene,
    private val radius: Float
) : Drawable() {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rect = RectF()
    private val borderRect = RectF()
    private val hostPos = IntArray(2)
    private val anchorPos = IntArray(2)
    private val edge = host.resources.displayMetrics.density

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)

        val saved = canvas.save()
        canvas.clipPath(clip)

        val blurred = scene.blurred
        val anchor = scene.anchor
        if (blurred != null && anchor != null) {
            host.getLocationInWindow(hostPos)
            anchor.getLocationInWindow(anchorPos)
            canvas.drawBitmap(
                blurred,
                (anchorPos[0] - hostPos[0]).toFloat(),
                (anchorPos[1] - hostPos[1]).toFloat(),
                bitmapPaint
            )
        }
        canvas.drawColor(Color.argb(100, 6, 8, 24))
        sheenPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            Color.argb(48, 255, 255, 255), Color.argb(14, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, sheenPaint)
        canvas.restoreToCount(saved)

        borderRect.set(rect)
        borderRect.inset(edge / 2f, edge / 2f)
        borderPaint.strokeWidth = edge
        borderPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.argb(150, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(borderRect, radius - edge / 2f, radius - edge / 2f, borderPaint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
