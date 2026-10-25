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

                // Soft keyboards mark provisional words with composing spans while
                // autocorrect/predictive input is active. Wait until the IME commits
                // the text; otherwise provisional letters may be sent and then sent
                // again when the keyboard revises the word.
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

    private fun inputField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(textTertiary)
        setTextColor(Color.WHITE)
        textSize = 16f
        minLines = 5
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
