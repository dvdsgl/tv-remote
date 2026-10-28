package xyz.dvdsgl.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * A remote for Apple TVs on the local network, laid out like the iPhone's Apple TV Remote: TV
 * picker and power on top, a large touch surface, then back, TV, play/pause and keyboard.
 * The phone's volume keys control the TV while the app is open.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private companion object {
        const val LIGHT_BARS = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }
    /** The palette: light (free) or dark (the paid upgrade). */
    private object C {
        var dark = false
        private fun c(light: String, dark: String) = Color.parseColor(if (this.dark) dark else light)
        val bg get() = c("#FFFFFF", "#000000")
        val pad get() = c("#F2F2F7", "#1C1C1E")
        val button get() = c("#E5E5EA", "#2C2C2E")
        val pressed get() = c("#D1D1D6", "#48484A")
        val text get() = c("#1D1D1F", "#FFFFFF")
        val secondary get() = c("#6E6E73", "#8E8E93")
        val blue get() = c("#007AFF", "#0A84FF")
        val red get() = c("#FF3B30", "#FF453A")
        val handle get() = c("#C7C7CC", "#48484A")
        val card get() = c("#FFFFFF", "#1C1C1E")
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var remote: Remote
    private lateinit var deviceName: TextView
    private lateinit var status: TextView
    private lateinit var power: View
    private lateinit var medium: Typeface
    private lateinit var semibold: Typeface
    private lateinit var regular: Typeface
    private val ui = Handler(Looper.getMainLooper())
    private var devices = listOf<JSONObject>()
    private var powerOn = true
    private var tvKeyboard = false
    private var keyboardSheet: Dialog? = null
    private var pairSheet: Dialog? = null
    private var selected = false
    private lateinit var upgrade: Upgrade
    private lateinit var root: FrameLayout
    private var wide = false
    private var column: View? = null
    /** Debug builds only: "remote", "pairing", "picker", "keyboard" or "offline" shows that screen with
     *  sample data and no network, for screenshots (adb shell am start ... --es demo pairing). */
    private var demo: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light is free; dark is the upgrade. Asking for the matching night mode makes the system
        // draw what's around the app (gesture bar, taskbar, keyboard) to match.
        // Remote starts light; dark mode is the upgrade (free in the oss build).
        val themePrefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        C.dark = themePrefs.getBoolean("dark", false) && (Upgrade.FREE || themePrefs.getBoolean("owned", false))
        getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(
            if (C.dark) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        upgrade = Upgrade(this) { owned -> if (!owned && C.dark) applyTheme(false, persist = true) }
        prefs = getSharedPreferences("remote", Context.MODE_PRIVATE)
        // Settings from the bridge era (token, bridge address) are no longer used.
        if (prefs.contains("token")) prefs.edit().remove("token").remove("host").remove("port").apply()
        regular = resources.getFont(R.font.inter_regular)
        medium = resources.getFont(R.font.inter_medium)
        semibold = resources.getFont(R.font.inter_semibold)
        remote = Remote(this, onConnected = ::onRemote, onEvent = ::onEvent)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) demo = intent.getStringExtra("demo")
        buildUi()
    }

    /** Builds the remote in the current theme (C.dark); called again to switch themes in place. */
    private fun buildUi() {
        window.decorView.setBackgroundColor(C.bg)
        window.setBackgroundDrawable(ColorDrawable(C.bg))
        window.insetsController?.setSystemBarsAppearance(if (C.dark) 0 else LIGHT_BARS, LIGHT_BARS)
        val root = FrameLayout(this).apply {
            setBackgroundColor(C.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        val m = resources.displayMetrics
        val surface = FrameLayout(this).apply {
            addView(TouchSurface(this@MainActivity).apply {
                dark = C.dark
                listener = object : TouchSurface.Listener {
                    override fun onTouch(x: Int, y: Int, mode: Int) {
                        if (selected) remote.send(JSONObject().put("op", "touch").put("x", x).put("y", y).put("mode", mode))
                    }
                    override fun onTap() = key("select")
                }
            })
        }
        wide = m.widthPixels / m.density >= 600
        // Clickable so taps on the column's own gaps don't reach the root below.
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        column.addView(topBar())
        column.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dp(20), dp(12), dp(20), 0)
        })
        column.addView(controlRow())
        // Unfolded: the phone layout floats on one side, within thumb reach. Tapping the empty
        // side moves it there.
        if (wide) root.setOnClickListener { switchSide() }
        root.addView(column, FrameLayout.LayoutParams(minOf(m.widthPixels, dp(420)), ViewGroup.LayoutParams.MATCH_PARENT,
            if (wide) sideGravity() else Gravity.CENTER_HORIZONTAL))
        this.column = column
        this.root = root

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        upgrade.start()
        thankIfBought()
        if (demo == null) remote.start() else ui.post(::showDemo)
    }

    private fun showDemo() {
        deviceName.text = "Living Room"
        setStatus("")
        when (demo) {
            "pairing" -> pair("demo")
            "picker" -> showPicker(listOf(
                JSONObject().put("id", "lr").put("name", "Living Room").put("model", "AppleTV14,1").put("paired", true),
                JSONObject().put("id", "br").put("name", "Bedroom").put("model", "AppleTV5,3").put("paired", false),
            ), current = "lr", connected = true)
            "keyboard" -> showKeyboard(prefill = "Severance")
            "offline" -> setStatus("Can't reach Living Room. Is it on and on the same Wi-Fi?")
        }
    }

    override fun onDestroy() {
        upgrade.stop()
        super.onDestroy()
    }

    override fun onStop() {
        if (demo == null) remote.stop()
        super.onStop()
    }

    // ── Layout ──

    /** TV name (opens the picker), a status line only while there is one, and power. */
    private fun topBar() = FrameLayout(this).apply {
        setPadding(dp(20), 0, dp(20), 0)
        minimumHeight = dp(64)
        val title = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            deviceName = text(prefs.getString("deviceName", "Choose TV")!!, 17f, semibold, C.text).apply {
                letterSpacing = -0.01f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            // A spacer as wide as the chevron keeps the name itself centered.
            row.addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(20), 1))
            row.addView(deviceName)
            row.addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_chevron_down); imageTintList = ColorStateList.valueOf(C.secondary) },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) })
            addView(row)
            status = text("", 13f, regular, C.secondary).apply {
                setPadding(0, dp(3), 0, 0); gravity = Gravity.CENTER_HORIZONTAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { chooseTv() }
        }
        // Equal margins on both sides (clear of power), so the title stays centered whatever its width.
        addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = dp(52); marginEnd = dp(52)
        })
        // Shows the current mode: a sun in light mode (tap for dark), a moon in dark mode.
        addView(circle(if (C.dark) R.drawable.ic_moon else R.drawable.ic_sun, 44, 22, color = Color.TRANSPARENT) { toggleDark() }
            .apply { contentDescription = if (C.dark) "Switch to light mode" else "Switch to dark mode" },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        power = circle(R.drawable.ic_power, 44, 26, color = Color.TRANSPARENT) {
            remote.send(JSONObject().put("op", "power").put("on", !powerOn))
            powerOn = !powerOn
        }.apply { contentDescription = "Power" }
        addView(power, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        placeTopButtons()
        setStatus("Connecting")
    }

    /** Unfolded, power sits on the outer edge of the column. */
    private fun placeTopButtons() {
        (power.layoutParams as FrameLayout.LayoutParams).gravity = (if (wide) sideGravity() else Gravity.END) or Gravity.CENTER_VERTICAL
        power.requestLayout()
    }

    private fun sideGravity() = if (prefs.getString("side", "end") == "start") Gravity.START else Gravity.END

    private fun switchSide() {
        prefs.edit().putString("side", if (sideGravity() == Gravity.END) "start" else "end").apply()
        val c = column ?: return
        android.transition.TransitionManager.beginDelayedTransition(root, android.transition.ChangeBounds().setDuration(260))
        c.layoutParams = (c.layoutParams as FrameLayout.LayoutParams).apply { gravity = sideGravity() }
        placeTopButtons()
    }

    private fun back(size: Int) = circle(R.drawable.ic_back, size, 26) { key("menu") }.apply { contentDescription = "Back" }
    private fun tv(size: Int) = circle(R.drawable.ic_tv, size, 26, longPress = { key("home_hold") }) { key("home") }.apply { contentDescription = "TV" }
    private fun playPause(size: Int) = circle(R.drawable.ic_playpause, size, 28) { key("play_pause") }.apply { contentDescription = "Play or pause" }
    /** Siri: hold to talk, as on the Siri Remote. The keyboard needs no button; it opens by
     *  itself when the TV focuses a text field. */
    @SuppressLint("ClickableViewAccessibility")
    private fun siri(size: Int) = circle(R.drawable.ic_mic, size, 26) {}.apply {
        contentDescription = "Siri"
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (selected) remote.send(JSONObject().put("op", "siri").put("down", true)) else key("select")
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    if (selected) remote.send(JSONObject().put("op", "siri").put("down", false))
            }
            false // let the button show its pressed ripple
        }
    }

    /** Phone: one row of four, spread across the width under the touch surface. */
    private fun controlRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(28), dp(28), dp(28), dp(28))
        listOf(back(68), tv(68), playPause(68), siri(68)).forEachIndexed { i, v ->
            if (i > 0) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(v, LinearLayout.LayoutParams(dp(68), dp(68)))
        }
    }

    private fun circle(icon: Int, size: Int, iconSize: Int, color: Int = C.button, tint: Int? = null, longPress: (() -> Unit)? = null, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            background = RippleDrawable(ColorStateList.valueOf(C.pressed), shape, null)
            addView(ImageView(this@MainActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(tint ?: C.text) },
                FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onClick() }
            if (longPress != null) setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); longPress(); true }
            layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        }

    // ── Remote ──

    private var statusMessage = ""

    /** The line under the TV name, shown only while there is something to say. */
    private fun setStatus(message: String) {
        statusMessage = message
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun key(k: String) {
        if (selected) remote.send(JSONObject().put("op", "key").put("key", k))
        else if (prefs.getString("device", null) != null && remote.connected) select(prefs.getString("device", null)!!)
    }

    private fun onRemote(up: Boolean) {
        if (!up) {
            selected = false
            setStatus("Not connected")
            return
        }
        val id = prefs.getString("device", null)
        if (id == null) {
            setStatus("Choose a TV")
            chooseTv()
        } else {
            select(id)
        }
    }

    /** Once, after the first connection: Android's own "Add tile to Quick Settings?" prompt. */
    private fun offerTile() {
        if (demo != null || prefs.getBoolean("tileOffered", false)) return
        prefs.edit().putBoolean("tileOffered", true).apply()
        getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
            android.content.ComponentName(this, RemoteTile::class.java), "Remote",
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor) {}
    }

    private fun select(id: String) {
        setStatus("Connecting")
        remote.request(JSONObject().put("op", "select").put("device", id)) { r ->
            if (r.optBoolean("ok")) {
                selected = true
                offerTile()
                setStatus("")
                onEvent(r)
            } else if (r.optBoolean("unpaired")) {
                setStatus("Not paired")
                pair(id)
            } else if (r.optBoolean("unknown")) {
                setStatus("Choose a TV")
                chooseTv()
            } else {
                setStatus(r.optString("error", "Can't connect"))
            }
        }
    }

    private fun onEvent(e: JSONObject) {
        if (e.optString("ev") != "state") return
        if (!e.optBoolean("connected", true)) { setStatus("Not connected"); return }
        if (statusMessage == "Not connected") setStatus("")
        powerOn = e.optString("power", "on") != "off"
        power.alpha = if (powerOn) 1f else 0.55f
        val kb = e.optBoolean("keyboard", false)
        if (kb && !tvKeyboard) showKeyboard()
        if (!kb && tvKeyboard) keyboardSheet?.dismiss()
        tvKeyboard = kb
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return volumeKey(keyCode) || super.onKeyDown(keyCode, event)
    }

    /** Volume keys go to the TV (held keys repeat). */
    private fun volumeKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { key("volume_up"); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { key("volume_down"); true }
        else -> false
    }

    // ── TV picker ──

    private fun chooseTv() {
        remote.request(JSONObject().put("op", "devices").put("rescan", devices.isEmpty())) { r ->
            if (!r.optBoolean("ok")) { setStatus(r.optString("error")); return@request }
            val arr = r.getJSONArray("devices")
            devices = List(arr.length()) { arr.getJSONObject(it) }
            showPicker(devices, prefs.getString("device", null), selected)
        }
    }

    private fun showPicker(devices: List<JSONObject>, current: String?, connected: Boolean) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(14)) }
            val sheet = bottomSheet(body)
            body.addView(text("Choose a TV", 20f, semibold, C.text).apply { letterSpacing = -0.01f; setPadding(dp(24), dp(4), dp(24), dp(12)) })
            if (devices.isEmpty()) body.addView(text("No Apple TVs found on this Wi-Fi.", 15f, regular, C.secondary).apply { setPadding(dp(24), dp(4), dp(24), dp(12)) })
            for (d in devices) {
                val id = d.getString("id")
                val state = when {
                    id == current && connected -> "Connected"
                    !d.getBoolean("paired") -> "Not paired"
                    else -> null
                }
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    background = RippleDrawable(ColorStateList.valueOf(C.button), null, ColorDrawable(Color.WHITE))
                    addView(FrameLayout(this@MainActivity).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.button) }
                        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_tv); imageTintList = ColorStateList.valueOf(C.text) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                    val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    labels.addView(text(d.getString("name"), 17f, medium, C.text))
                    labels.addView(text(listOfNotNull(modelName(d.getString("model")), state).joinToString(" · "), 14f, regular, C.secondary).apply { setPadding(0, dp(3), 0, 0) })
                    addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_check)
                        imageTintList = ColorStateList.valueOf(C.blue)
                        visibility = if (id == current) View.VISIBLE else View.INVISIBLE
                    }, LinearLayout.LayoutParams(dp(24), dp(24)))
                    setOnClickListener {
                        sheet.dismiss()
                        if (demo != null) return@setOnClickListener
                        prefs.edit().putString("device", id).putString("deviceName", d.getString("name")).apply()
                        deviceName.text = d.getString("name")
                        select(id)
                    }
                })
            }
            sheet.show()
    }

    /** "AppleTV14,1" -> "Apple TV 4K", as Apple names them. */
    private fun modelName(id: String): String {
        val major = id.removePrefix("AppleTV").substringBefore(',').toIntOrNull() ?: return "Apple TV"
        return when {
            major <= 3 -> "Apple TV"
            major == 5 -> "Apple TV HD"
            else -> "Apple TV 4K"
        }
    }

    /**
     * A dark sheet from the bottom. Floating (the TV picker): a card inset from the edges with
     * all corners rounded, sitting above the navigation bar. Attached (the keyboard): rounded
     * top corners only, flush with the keyboard below it.
     */
    private fun bottomSheet(content: View, floating: Boolean = true): Dialog {
        val sheet = Dialog(this)
        val r = dp(28).toFloat()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.pad)
                cornerRadii = if (floating) FloatArray(8) { r } else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true // keep row ripples inside the rounded corners
            if (floating) addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { setColor(C.handle); cornerRadius = dp(3).toFloat() }
            }, LinearLayout.LayoutParams(dp(36), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(14) })
            addView(content)
        }
        val gap = if (floating) dp(10) else 0
        val frame = FrameLayout(this).apply {
            addView(body)
            // Floating: clear of the screen edges and above the navigation bar. Attached: run the
            // sheet's color under the navigation bar and sit on top of the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
                if (floating) v.setPadding(gap, 0, gap, bottom + gap) else body.setPadding(0, 0, 0, bottom)
                insets
            }
        }
        sheet.setContentView(frame)
        // The phone's volume keys keep controlling the TV while a sheet is open.
        sheet.setOnKeyListener { _, keyCode, e -> e.action == KeyEvent.ACTION_DOWN && volumeKey(keyCode) }
        sheet.window?.apply {
            setDecorFitsSystemWindows(false)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            val m = resources.displayMetrics
            setLayout(minOf(m.widthPixels, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        return sheet
    }

    // ── Pairing ──

    /**
     * A full-screen code entry: four slots and a numpad. It asks the TV for a code when it
     * opens; a wrong code shakes the slots and asks for a new one.
     */
    private fun pair(id: String) {
        if (pairSheet?.isShowing == true) return
        val tv = deviceName.text.toString()
        val sheet = Dialog(this, R.style.Theme_Remote)
        pairSheet = sheet
        val digits = StringBuilder()
        var ready = false

        val m = resources.displayMetrics
        val wide = m.widthPixels / m.density >= 600
        // The unfolded screen in landscape is only ~700dp tall: tighten the header and the pad.
        val short = m.heightPixels / m.density < 760
        val key = if (short) 68 else 78
        val hGap = if (short) 22 else 28
        val vGap = if (short) 10 else 16
        val root = FrameLayout(this).apply {
            setBackgroundColor(C.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(if (short) 12 else 24))
        }
        root.addView(column, FrameLayout.LayoutParams(minOf(m.widthPixels, dp(420)), ViewGroup.LayoutParams.MATCH_PARENT,
            if (wide) sideGravity() else Gravity.CENTER_HORIZONTAL))

        column.addView(text("Cancel", 17f, regular, C.blue).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { gravity = Gravity.START })

        column.addView(text("Enter code", 28f, semibold, C.text).apply { gravity = Gravity.CENTER; letterSpacing = -0.02f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (short) 8 else 56) })
        // Two lines tall, so a longer error doesn't push the slots down.
        val message = text("", 15f, regular, C.secondary).apply { gravity = Gravity.CENTER or Gravity.TOP; setLines(2); setLineSpacing(0f, 1.2f) }
        column.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val slotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val slots = List(4) { i ->
            text("", 32f, semibold, C.text).apply { gravity = Gravity.CENTER }.also {
                slotRow.addView(it, LinearLayout.LayoutParams(dp(56), dp(68)).apply { if (i > 0) marginStart = dp(12) })
            }
        }
        column.addView(slotRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val retry = text("Try again", 17f, medium, C.blue).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.INVISIBLE
        }
        column.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        fun render(busy: Boolean = false) {
            slots.forEachIndexed { i, slot ->
                slot.text = digits.getOrNull(i)?.toString() ?: ""
                val active = ready && !busy && i == digits.length
                slot.background = GradientDrawable().apply {
                    setColor(C.pad)
                    cornerRadius = dp(14).toFloat()
                    if (active) setStroke(dp(2), C.blue)
                }
                slot.alpha = if (ready || busy) 1f else 0.5f
            }
        }
        fun say(s: String, error: Boolean = false) {
            message.text = s
            message.setTextColor(if (error) C.red else C.secondary)
        }

        lateinit var begin: () -> Unit
        fun submit() {
            ready = false
            render(busy = true)
            say("Pairing")
            remote.request(JSONObject().put("op", "pair_pin").put("pin", digits.toString())) { p ->
                if (!sheet.isShowing) return@request
                if (p.optBoolean("ok")) {
                    root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    sheet.dismiss()
                    select(id)
                    return@request
                }
                root.performHapticFeedback(HapticFeedbackConstants.REJECT)
                slotRow.animate().cancel()
                slotRow.translationX = 0f
                android.animation.ObjectAnimator.ofFloat(slotRow, "translationX", 0f, 18f, -18f, 12f, -12f, 6f, -6f, 0f)
                    .setDuration(420).start()
                digits.clear()
                render()
                say("That code didn't work. Getting a new one", error = true)
                // The TV needs a moment before it will show another code.
                ui.postDelayed({ if (sheet.isShowing) begin() }, 1200)
            }
        }
        fun press(d: Char) {
            if (!ready || digits.length >= 4) return
            digits.append(d)
            render()
            if (digits.length == 4) submit()
        }
        fun delete() {
            if (!ready || digits.isEmpty()) return
            digits.setLength(digits.length - 1)
            render()
        }
        begin = {
            ready = false
            digits.clear()
            retry.visibility = View.INVISIBLE
            render()
            if (message.currentTextColor != C.red) say("Asking $tv for a code")
            remote.request(JSONObject().put("op", "pair_begin").put("device", id)) { r ->
                if (!sheet.isShowing) return@request
                if (r.optBoolean("ok")) {
                    ready = true
                    say("Shown on $tv")
                    render()
                } else {
                    say(r.optString("error", "Couldn't reach $tv."), error = true)
                    retry.visibility = View.VISIBLE
                }
            }
        }
        retry.setOnClickListener { say(""); begin() }

        // Numpad: 1-9, then a blank, 0 and delete.
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val keys = "123456789 0<"
        for (row in 0 until 4) {
            pad.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((col, c) in keys.substring(row * 3, row * 3 + 3).withIndex()) {
                    val cell = FrameLayout(this@MainActivity)
                    when (c) {
                        ' ' -> {}
                        '<' -> cell.addView(FrameLayout(this@MainActivity).apply {
                            contentDescription = "Delete"
                            background = RippleDrawable(ColorStateList.valueOf(C.pressed), null,
                                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_delete); imageTintList = ColorStateList.valueOf(C.text) }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); delete() }
                            setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); while (digits.isNotEmpty()) delete(); true }
                        }, FrameLayout.LayoutParams(dp(key), dp(key), Gravity.CENTER))
                        else -> cell.addView(FrameLayout(this@MainActivity).apply {
                            background = RippleDrawable(ColorStateList.valueOf(C.pressed),
                                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.button) }, null)
                            addView(text(c.toString(), 34f, regular, C.text).apply { gravity = Gravity.CENTER },
                                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); press(c) }
                        }, FrameLayout.LayoutParams(dp(key), dp(key), Gravity.CENTER))
                    }
                    addView(cell, LinearLayout.LayoutParams(dp(key), dp(key)).apply { if (col > 0) marginStart = dp(hGap) })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (row > 0) topMargin = dp(vGap) })
        }
        column.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        sheet.setContentView(root)
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(C.bg))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            insetsController?.setSystemBarsAppearance(if (C.dark) 0 else LIGHT_BARS,
                LIGHT_BARS)
        }
        // A hardware keyboard (or a foldable's cover keyboard) works too.
        sheet.setOnKeyListener { _, keyCode, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (keyCode - KeyEvent.KEYCODE_0)); true }
                KeyEvent.KEYCODE_DEL -> { delete(); true }
                else -> false
            }
        }
        sheet.setOnDismissListener {
            pairSheet = null
            if (!selected) setStatus("Not paired")
        }
        render()
        sheet.show()
        if (demo != null) { ready = true; say("Shown on $tv"); digits.append("38"); render() } else begin()
    }

    // ── Keyboard ──

    private fun showKeyboard(prefill: String? = null) {
        if (keyboardSheet?.isShowing == true) return
        lateinit var done: TextView
        val field = EditText(this).apply {
            setTextColor(C.text)
            textSize = 17f
            typeface = regular
            isSingleLine = true
            textCursorDrawable = GradientDrawable().apply { setColor(C.blue); setSize(dp(2), 0) }
            background = GradientDrawable().apply { setColor(C.button); cornerRadius = dp(14).toFloat() }
            setPadding(dp(16), 0, dp(16), 0)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
            addView(FrameLayout(this@MainActivity).apply {
                setPadding(dp(8), 0, dp(8), dp(12))
                addView(text("TYPING ON ${deviceName.text}".uppercase(), 13f, medium, C.secondary).apply { letterSpacing = 0.02f },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
                done = text("Done", 17f, semibold, C.blue)
                addView(done, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            })
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }
        val sheet = bottomSheet(body, floating = false)
        sheet.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(0.45f)
        }
        done.setOnClickListener { sheet.dismiss() }
        // Prefill with what the TV's field already holds.
        if (prefill != null) { field.setText(prefill); field.setSelection(prefill.length) }
        else if (selected) remote.request(JSONObject().put("op", "text")) { r ->
            if (r.optBoolean("ok") && field.text.isEmpty()) {
                field.setText(r.optString("text")); field.setSelection(field.text.length)
            }
        }
        var pending: Runnable? = null
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                pending?.let(ui::removeCallbacks)
                pending = Runnable { remote.send(JSONObject().put("op", "text").put("set", s.toString())) }.also { ui.postDelayed(it, 150) }
            }
        })
        field.setOnEditorActionListener { _, _, _ -> key("select"); sheet.dismiss(); true }
        sheet.setOnDismissListener { keyboardSheet = null }
        keyboardSheet = sheet
        sheet.show()
        field.requestFocus()
        ui.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }, 150)
    }

    // ── Dark mode (the paid upgrade) ──

    /**
     * The sun/moon button. Owners (and the oss build) just switch. On the free version, the sun
     * previews dark mode with the upgrade card; dismissing the card fades back to light.
     */
    private fun toggleDark() {
        when {
            C.dark -> applyTheme(false, persist = true)
            upgrade.owned -> applyTheme(true, persist = true)
            else -> { applyTheme(true, persist = false); showUpgrade() }
        }
    }

    /** Switches theme in place, crossfading from a snapshot of the old screen. */
    private fun applyTheme(dark: Boolean, persist: Boolean) {
        if (persist) {
            prefs.edit().putBoolean("dark", dark).apply()
            getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(
                if (dark) android.app.UiModeManager.MODE_NIGHT_YES else android.app.UiModeManager.MODE_NIGHT_NO)
        }
        if (dark == C.dark) return
        val decor = window.decorView
        val snapshot = if (decor.width > 0 && decor.height > 0)
            android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { decor.draw(android.graphics.Canvas(it)) } else null
        val name = deviceName.text
        val message = statusMessage
        C.dark = dark
        buildUi()
        deviceName.text = name
        setStatus(message)
        power.alpha = if (powerOn) 1f else 0.55f
        if (snapshot != null) {
            val fade = android.graphics.drawable.BitmapDrawable(resources, snapshot).apply { setBounds(0, 0, decor.width, decor.height) }
            decor.overlay.add(fade)
            android.animation.ValueAnimator.ofInt(255, 0).apply {
                duration = 320
                addUpdateListener { fade.alpha = it.animatedValue as Int }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { decor.overlay.remove(fade); snapshot.recycle() }
                })
                start()
            }
        }
    }

    /** The upgrade, as a bottom card over the dark-mode preview. */
    private fun showUpgrade() {
        var bought = false
        lateinit var sheet: Dialog
        val body = cardBody(R.drawable.ic_moon, "Dark mode",
            "Remote is free, with no ads. Keep dark mode with a one-time upgrade, and thank you for your support.",
            "Unlock for ${upgrade.price ?: "$2.99"}", "Not now", onSecondary = { sheet.dismiss() }) {
            upgrade.buy { result ->
                when (result) {
                    Upgrade.Result.Bought -> { bought = true; sheet.dismiss(); applyTheme(true, persist = true); thank() }
                    Upgrade.Result.Cancelled -> {}
                    is Upgrade.Result.Failed -> card(R.drawable.ic_moon, "Can't unlock right now", result.message, "OK", null) {}.show()
                }
            }
        }
        sheet = bottomSheet(body)
        sheet.setOnDismissListener { if (!bought) applyTheme(false, persist = false) }
        sheet.show()
    }

    private fun thank() = card(R.drawable.ic_moon, "Thank you", "Dark mode is yours. Thanks for supporting Remote.", "Done", null) {}.show()

    private fun thankIfBought() {
        if (!prefs.getBoolean("thank", false)) return
        prefs.edit().remove("thank").apply()
        thank()
    }

    /** A centered card: icon, title, message, a primary button and an optional quiet one. */
    private fun card(icon: Int, title: String, message: String, primary: String, secondary: String?, onPrimary: () -> Unit): Dialog {
        val dialog = Dialog(this)
        val body = cardBody(icon, title, message, primary, secondary, onSecondary = { dialog.dismiss() }) { dialog.dismiss(); onPrimary() }.apply {
            setPadding(dp(24), dp(28), dp(24), dp(if (secondary == null) 20 else 14))
            background = GradientDrawable().apply { setColor(C.card); cornerRadius = dp(28).toFloat() }
        }
        dialog.setContentView(body)
        dialog.setOnKeyListener { _, keyCode, e -> e.action == KeyEvent.ACTION_DOWN && volumeKey(keyCode) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(56), dp(360)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.32f)
        }
        return dialog
    }

    /** Icon, title, message, a primary button and an optional quiet one. */
    private fun cardBody(icon: Int, title: String, message: String, primary: String, secondary: String?,
                         onSecondary: () -> Unit, onPrimary: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1C1C1E")) }
                addView(ImageView(this@MainActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.WHITE) },
                    FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(text(title, 22f, semibold, C.text).apply { letterSpacing = -0.02f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(text(message, 15f, regular, C.secondary).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(text(primary, 17f, semibold, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                    GradientDrawable().apply { setColor(C.blue); cornerRadius = dp(25).toFloat() }, null)
                setOnClickListener { onPrimary() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(22) })
            if (secondary != null) addView(text(secondary, 16f, medium, C.blue).apply {
                gravity = Gravity.CENTER
                setOnClickListener { onSecondary() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        }

    // ── Helpers ──

    private fun text(s: String, size: Float, face: Typeface, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        typeface = face
        setTextColor(color)
        includeFontPadding = false
    }
}
