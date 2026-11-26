package com.manytwo.hakeypad

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), HaClient.Listener {

    private lateinit var prefs: Prefs
    private lateinit var grid: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var clockView: TextView
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var offlineView: View
    private lateinit var offlineDetail: TextView
    private val adapter = TileAdapter()

    private var client: HaClient? = null
    private var lastNetwork: Network? = null
    /** When the phone joins Wi-Fi again, reconnect to Home Assistant at once. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                val first = lastNetwork == null
                val changed = network != lastNetwork
                lastNetwork = network
                DebugLog.add("NET", "Wi-Fi network available" + if (first) "" else if (changed) " (new)" else " (same)")
                if (!first && changed) client?.reconnectNow("phone rejoined Wi-Fi")
            }
        }
        override fun onLost(network: Network) { DebugLog.add("NET", "Wi-Fi network lost") }
    }
    private lateinit var wifiGuard: WifiGuard
    private var wallPhone = false
    private val states = HashMap<String, EntityState>()
    private val pending = HashMap<String, Long>()   // entity id → time pressed

    // ---- room reading and − / + target per tile (see Extras) ----
    private val readingIds = HashMap<String, List<String>>()   // tile → reading entities
    private val targetIds = HashMap<String, String>()    // tile → target entity
    private val pendingTarget = HashMap<String, Double>()   // tile → value chosen with − / +, not yet confirmed
    private val targetSends = HashMap<String, Runnable>()

    private val main = Handler(Looper.getMainLooper())
    private lateinit var screen: ScreenControl
    private var pal: Palette? = null
    private var batteryTick = 0
    private val battery by lazy { Battery(this, prefs) }
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            screen.tick()
            if (batteryTick++ % 10 == 0) battery.report(client)
            main.postDelayed(this, 1000L * (60 - (System.currentTimeMillis() / 1000) % 60))
        }
    }
    private val settingsHold = Runnable { askPinThenOpenSettings() }

    // ---- offline warning: after OFFLINE_WARN_MS without Home Assistant, cover the buttons in red ----
    private val OFFLINE_WARN_MS = 60_000L
    private var offlineSince = 0L          // 0 = connected
    private var lastDetail = ""
    private val offlineRunnable = Runnable { showOffline() }

    private fun showOffline(wake: Boolean = true) {
        if (offlineSince == 0L || !prefs.isConfigured) return
        val since = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(offlineSince))
        offlineDetail.text = buildString {
            if (wifiGuard.away && wifiGuard.noNetwork) append("Since ").append(since).append(". The phone has no network.")
            else if (wifiGuard.away) append("Since ").append(since)
                .append(". Not reachable from this network: away from home it needs a VPN.")
            else append("Since ").append(since).append(". Check the Wi-Fi.\nIt reconnects by itself.")
            if (lastDetail.isNotBlank()) append("\n\n").append(lastDetail)
            append("\n\n").append(wifiGuard.status)
        }
        val wasShown = offlineView.visibility == View.VISIBLE
        if (!wasShown) DebugLog.add("APP", "red offline panel shown (offline since $since)")
        offlineView.visibility = View.VISIBLE
        if (wake && !wasShown) screen.wake("offline warning")   // make sure it is noticed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        prefs = Prefs(this)
        Kiosk.showOverLockScreen(this, prefs)
        setContentView(R.layout.activity_main)
        titleView = findViewById(R.id.title)
        clockView = findViewById(R.id.clock)
        statusView = findViewById(R.id.status)
        emptyView = findViewById(R.id.empty)
        offlineView = findViewById(R.id.offline)
        offlineDetail = findViewById(R.id.offline_detail)
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.itemAnimator = null
        // Tile heights depend on the grid height, so redraw once it is known or changes.
        grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) grid.post { layoutTiles() }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Hold the title for 2 seconds to open Settings (so nobody gets there by accident).
        @SuppressLint("ClickableViewAccessibility")
        titleView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> main.postDelayed(settingsHold, 2000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(settingsHold)
            }
            true
        }
        emptyView.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // On a wall keypad (device owner) the guard watches the Wi-Fi all the time the keypad
        // exists. On a phone carried around it only runs while the keypad is on screen.
        wallPhone = WifiGuard.isDeviceOwner(this)
        DebugLog.add("APP", "started: ${DebugLog.deviceLine(this)}")
        DebugLog.add("APP", "server ${android.net.Uri.parse(prefs.serverUrl).host ?: "not set"}, " +
            "${prefs.buttons.size} tiles, device owner ${if (WifiGuard.isDeviceOwner(this)) "yes" else "no"}, " +
            "Wi-Fi guard ${if (prefs.wifiGuard) "on" else "off"}, log ${DebugLog.where}")
        wifiGuard = WifiGuard(this, prefs).also {
            it.onStatus = { if (offlineView.visibility == View.VISIBLE) showOffline(wake = false) }
            if (wallPhone) it.start()
        }

        // A fixed wall keypad: Back does nothing. On a phone carried around, Back leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(wallPhone) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        super.onResume()
        DebugLog.add("APP", "keypad on screen")
        hideSystemBars()
        titleView.text = prefs.title
        val cols = prefs.columns
        grid.layoutManager = GridLayoutManager(this, cols)
        adapter.setItems(prefs.buttons)
        grid.post { layoutTiles() }
        updateEmpty()
        applyColours()
        screen = ScreenControl(this, prefs, findViewById(R.id.root))
        screen.start()
        batteryTick = 0
        main.post(clockRunnable)
        startClient()
        if (!wallPhone) wifiGuard.start()
        Kiosk.apply(this, prefs)
    }

    override fun onPause() {
        super.onPause()
        DebugLog.add("APP", "keypad left the screen")
        main.removeCallbacks(clockRunnable)
        screen.stop()
        main.removeCallbacks(offlineRunnable)
        offlineSince = 0L
        offlineView.visibility = View.GONE
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        lastNetwork = null
        client?.stop()
        client = null
        if (!wallPhone) wifiGuard.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Theme (phone's Dark/Light, or fixed) and the user's own colours. */
    private fun applyColours() {
        val p = Palette.current(this, prefs)
        pal = p
        findViewById<View>(R.id.root).setBackgroundColor(p.bg)
        // Black behind the keypad: dimming fades the keypad (root alpha) towards black, also
        // with a light theme.
        window.decorView.setBackgroundColor(0xFF000000.toInt())
        @Suppress("DEPRECATION")
        run { window.statusBarColor = p.bg; window.navigationBarColor = p.bg }
        titleView.setTextColor(p.bar)
        clockView.setTextColor(p.bar)
        emptyView.setTextColor(p.bar)
        adapter.notifyDataSetChanged()
    }

    private fun startClient() {
        client?.stop()
        client = null
        if (!prefs.isConfigured) {
            showStatus("Not set up", R.color.status_bad)
            return
        }
        client = HaClient(prefs.serverUrl, prefs.token, this).also { it.start() }
        try {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCallback)
        } catch (_: Exception) {}   // already registered
    }

    private fun updateEmpty() {
        emptyView.visibility = if (prefs.buttons.isEmpty() || !prefs.isConfigured) View.VISIBLE else View.GONE
        emptyView.setText(if (!prefs.isConfigured) R.string.empty_not_setup else R.string.empty_no_buttons)
    }

    // ---- dimming: the first touch on a dimmed screen only wakes it ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (screen.swallowTouch) {
                screen.touched()
                swallowUntilUp = true
                return true
            }
            screen.touched()
        }
        if (swallowUntilUp) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowUntilUp = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
    private var swallowUntilUp = false

    // ---- settings ----

    private fun askPinThenOpenSettings() {
        titleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val pin = prefs.pin
        if (pin.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java)); return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString() == pin) startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Home Assistant callbacks ----

    override fun onConnection(status: HaClient.Status, detail: String) {
        when (status) {
            HaClient.Status.CONNECTING -> showStatus("Connecting…", R.color.status_wait)
            HaClient.Status.CONNECTED -> showStatus("●", R.color.status_ok)
            HaClient.Status.AUTH_FAILED -> showStatus("Token rejected", R.color.status_bad)
            HaClient.Status.DISCONNECTED -> showStatus("Offline", R.color.status_bad)
        }
        if (status != HaClient.Status.CONNECTED) adapter.notifyDataSetChanged()
        connected = status == HaClient.Status.CONNECTED
        if (detail.isNotBlank()) lastDetail = detail
        when {
            connected -> {
                battery.send(client)
                if (offlineSince > 0 && System.currentTimeMillis() - offlineSince > 2000) DebugLog.add("APP", "back online after ${(System.currentTimeMillis() - offlineSince) / 1000} s offline")
                offlineSince = 0L
                lastDetail = ""
                main.removeCallbacks(offlineRunnable)
                offlineView.visibility = View.GONE
            }
            status == HaClient.Status.AUTH_FAILED -> {
                // Won't fix itself: show the warning straight away.
                if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
                lastDetail = "The token was rejected. Hold the title for 2 s to fix it in Settings."
                main.removeCallbacks(offlineRunnable)
                showOffline()
            }
            offlineSince == 0L -> {
                offlineSince = System.currentTimeMillis()
                main.postDelayed(offlineRunnable, OFFLINE_WARN_MS)
            }
            offlineView.visibility == View.VISIBLE -> showOffline(wake = false)   // refresh the detail
        }
    }
    private var connected = false

    override fun onAllStates(states: Map<String, EntityState>) {
        this.states.clear()
        this.states.putAll(states)
        prefs.cacheNames(prefs.buttons.mapNotNull { id -> states[id]?.let { id to it.name } }.toMap())
        resolveExtras()
        layoutTiles()
    }

    override fun onPressFailed(entityId: String) {
        if (pending.remove(entityId) != null) adapter.refresh(entityId)
        Toast.makeText(this, "Not switched: no answer from Home Assistant", Toast.LENGTH_LONG).show()
    }

    override fun onStateChanged(state: EntityState) {
        val old = states[state.entityId]
        states[state.entityId] = state
        val pressedAt = pending.remove(state.entityId)
        if (state.entityId in prefs.buttons && old?.state != state.state) {
            DebugLog.add("STATE", "${state.entityId} ${old?.state ?: "?"} → ${state.state}" +
                if (pressedAt != null) " (${System.currentTimeMillis() - pressedAt} ms after the press)" else " (switched elsewhere)")
            if (pressedAt == null && prefs.wakeOnChange && old != null) screen.wake("${state.name} switched elsewhere")
        }
        adapter.refresh(state.entityId)
        // Tiles that show this entity as their reading or target.
        for (tile in prefs.buttons) {
            if (tile == state.entityId) continue
            if (readingIds[tile]?.contains(state.entityId) == true || targetIds[tile] == state.entityId) {
                if (targetIds[tile] == state.entityId && pendingTarget.containsKey(tile) && !targetSends.containsKey(tile)) {
                    pendingTarget.remove(tile)   // HA has the new value
                    DebugLog.add("TARGET", "${state.entityId} now ${state.state}")
                }
                adapter.refresh(tile)
            }
        }
        if (targetIds[state.entityId] == state.entityId && pendingTarget.containsKey(state.entityId) && !targetSends.containsKey(state.entityId))
            pendingTarget.remove(state.entityId).also { adapter.refresh(state.entityId) }   // climate setpoint
    }

    /** Find each tile's reading and target entity (Settings, or automatically). */
    private fun resolveExtras() {
        readingIds.clear(); targetIds.clear()
        for (tile in prefs.buttons) {
            Extras.readingIds(tile, states, prefs).takeIf { it.isNotEmpty() }?.let { readingIds[tile] = it }
            Extras.targetId(tile, states, prefs)?.let { targetIds[tile] = it }
            prefs.setAutoExtras(tile, "${Extras.autoReading(tile, states) ?: "none"} | ${Extras.autoTarget(tile, states) ?: "none"}")
        }
    }

    /** − / + pressed: change the shown target at once, send it to HA when the taps stop. */
    private fun stepTarget(tile: String, dir: Int, v: View) {
        val t = Extras.target(tile, states, prefs, null) ?: return
        val base = pendingTarget[tile] ?: t.value ?: return
        val nv = t.next(base, dir)
        if (nv == base) return   // at the limit
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pendingTarget[tile] = nv
        adapter.refresh(tile)
        targetSends.remove(tile)?.let { main.removeCallbacks(it) }
        val send = Runnable {
            targetSends.remove(tile)
            val value = pendingTarget[tile] ?: return@Runnable
            val ok = if (t.isClimate)
                client?.callService("climate", "set_temperature", t.entityId, org.json.JSONObject().put("temperature", value)) == true
            else
                client?.callService("input_number", "set_value", t.entityId, org.json.JSONObject().put("value", value)) == true
            DebugLog.add("TARGET", "${t.entityId} → $value" + if (ok) "" else " NOT SENT (offline)")
            if (!ok) {
                pendingTarget.remove(tile); adapter.refresh(tile)
                Toast.makeText(this, "Not changed: no connection to Home Assistant", Toast.LENGTH_LONG).show()
            } else main.postDelayed({   // no confirmation: show HA's value again
                if (pendingTarget.remove(tile) != null) adapter.refresh(tile)
            }, 10_000)
        }
        targetSends[tile] = send
        main.postDelayed(send, 1200)
    }

    private fun showStatus(text: String, colorRes: Int) {
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun press(entityId: String, v: View) {
        val c = client ?: run { DebugLog.add("PRESS", "$entityId pressed, but not set up"); return }
        DebugLog.add("PRESS", "tile pressed: ${prefs.label(entityId) ?: states[entityId]?.name ?: entityId}" +
            " (shows ${states[entityId]?.state ?: "?"}, ${if (connected) "online" else "OFFLINE"})")
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pending[entityId] = System.currentTimeMillis()
        adapter.refresh(entityId)
        // Ask for the opposite of what the tile shows. While offline the press waits (up to
        // 60 s) and is sent as soon as the connection is back.
        c.press(entityId, wantOn = !(states[entityId]?.isOn ?: false))
        // If HA never reports a change (e.g. device offline), stop showing "…" after 62 s.
        main.postDelayed({ if (pending.remove(entityId) != null) adapter.refresh(entityId) }, 62_000)
    }

    // ---- the grid of tiles ----

    /**
     * Size a tile name: the largest size (22 sp down to 8 sp) at which it fits the box in at
     * most 3 lines without breaking a word. Android's own autosize only checks that the text
     * fits, so it broke "Dehumidifier" in the middle on narrow tiles.
     */
    /** The ON/OFF line: 28 sp, smaller only if it doesn't fit the tile's width. */
    private fun fitValue(tv: TextView) {
        tv.post {
            val w = tv.width - tv.paddingLeft - tv.paddingRight
            if (w <= 0) return@post
            val text = tv.text.toString()
            val paint = android.text.TextPaint(tv.paint)
            val sp = resources.displayMetrics.scaledDensity
            var size = 28
            while (size > 12) {
                paint.textSize = size * sp
                if (paint.measureText(text) <= w) break
                size--
            }
            if (tv.textSize != size * sp) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        }
    }

    /** The reading line ("69% (60%)"): 22 sp, smaller (down to 10 sp) until it fits its width. */
    private fun fitLine(tv: TextView) {
        tv.post {
            val w = tv.width - tv.paddingLeft - tv.paddingRight
            if (w <= 0) return@post
            val text = tv.text
            val paint = android.text.TextPaint(tv.paint)
            val sp = resources.displayMetrics.scaledDensity
            var size = 22
            while (size > 10) {
                paint.textSize = size * sp
                if (android.text.Layout.getDesiredWidth(text, paint) <= w) break
                size--
            }
            if (tv.textSize != size * sp) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        }
    }

    private fun fitLongestWord(tv: TextView, text: String) {
        tv.post {
            val w = tv.width - tv.paddingLeft - tv.paddingRight
            val hMax = tv.height - tv.paddingTop - tv.paddingBottom
            if (w <= 0 || hMax <= 0 || tv.text.toString() != text) return@post
            val paint = android.text.TextPaint(tv.paint)
            val sp = resources.displayMetrics.scaledDensity
            var size = 22
            while (size > 8) {
                paint.textSize = size * sp
                val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                    .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                    .setIncludePad(tv.includeFontPadding)
                    .build()
                // A line may only end at a space (or the end): otherwise a word was broken.
                val wordsWhole = (0 until layout.lineCount).all { l ->
                    val end = layout.getLineEnd(l)
                    end >= text.length || text[end - 1] == ' ' || text[end] == ' '
                }
                if (wordsWhole && layout.lineCount <= 3 && layout.height <= hMax) break
                size--
            }
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        }
    }

    private var tileHeight = 0

    /**
     * Share the grid height between the rows, but keep each tile between 110 and 200 dp
     * so a few buttons don't become tall stripes. Any space left over is split above and
     * below, so the tiles sit in the middle of the screen.
     */
    private fun layoutTiles() {
        val d = resources.displayMetrics.density
        val minH = (110 * d).toInt()
        val maxH = (200 * d).toInt()
        val cols = (grid.layoutManager as? GridLayoutManager)?.spanCount ?: 2
        val rows = ((adapter.itemCount + cols - 1) / cols).coerceAtLeast(1)
        val avail = grid.height
        tileHeight = if (avail > 0) (avail / rows).coerceIn(minH, maxH) else minH
        val pad = ((avail - tileHeight * rows) / 2).coerceAtLeast(0)
        grid.setPadding(0, pad, 0, pad)
        adapter.notifyDataSetChanged()
    }

    private inner class TileAdapter : RecyclerView.Adapter<TileAdapter.VH>() {
        private var items: List<String> = emptyList()

        fun setItems(list: List<String>) { items = list; notifyDataSetChanged() }
        fun refresh(entityId: String) {
            val i = items.indexOf(entityId)
            if (i >= 0) notifyItemChanged(i)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val value: TextView = v.findViewById(R.id.value)
            val tile: View = v.findViewById(R.id.tile)
            val extraRow: View = v.findViewById(R.id.extra_row)
            val reading: TextView = v.findViewById(R.id.reading)
            val minus: TextView = v.findViewById(R.id.minus)
            val plus: TextView = v.findViewById(R.id.plus)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
            val h = VH(v)
            // Tiles change width (columns setting, rotation): size the name again for the new width.
            h.name.addOnLayoutChangeListener { tv, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) fitLongestWord(tv as TextView, tv.text.toString())
            }
            h.value.addOnLayoutChangeListener { tv, l, _, r, _, ol, _, or, _ ->
                if (r - l != or - ol) fitValue(tv as TextView)
            }
            // Narrow tiles (3-4 columns) get a smaller inner margin, so the text has room.
            h.tile.addOnLayoutChangeListener { tile, l, _, r, _, ol, _, or, _ ->
                if (r - l != or - ol) {
                    val d = resources.displayMetrics.density
                    val pad = ((r - l) * 0.06f).coerceIn(4 * d, 12 * d).toInt()
                    if (tile.paddingLeft != pad) tile.post { tile.setPadding(pad, pad, pad, pad) }
                }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val id = items[position]
            val s = states[id]
            if (tileHeight > 0) h.itemView.layoutParams.height = tileHeight

            val label = prefs.label(id) ?: s?.name ?: prefs.cachedName(id) ?: id
            h.name.text = label
            fitLongestWord(h.name, label)
            val isSensor = id.startsWith("sensor.") || id.startsWith("binary_sensor.")
            val bg = h.tile.background.mutate() as GradientDrawable

            val p = pal ?: Palette.current(this@MainActivity, prefs)
            val tileColour: Int
            when {
                pending.containsKey(id) && !connected -> {
                    h.value.text = "…"        // pressed while offline: waits for the connection
                    tileColour = p.tileUnavailable
                }
                s == null || !connected -> {
                    h.value.text = if (!connected) "–" else "not found"
                    tileColour = p.tileUnavailable
                }
                s.isUnavailable -> {
                    h.value.text = "offline"
                    tileColour = p.tileUnavailable
                }
                isSensor -> {
                    h.value.text = if (s.state.isBlank()) "–" else listOfNotNull(s.state, s.unit).joinToString(" ")
                    tileColour = p.tileSensor
                }
                pending.containsKey(id) -> {
                    h.value.text = "…"
                    tileColour = if (s.isOn) p.tileOn else p.tileOff
                }
                else -> {
                    // ON/OFF is shown by the colour; only other states (e.g. "open") get words.
                    h.value.text = if (s.isOn || s.state == "off") "" else s.state
                    tileColour = if (s.isOn) p.tileOn else p.tileOff
                }
            }
            bg.setColor(tileColour)
            val textColor = if (tileColour == p.tileOn) p.textOn else p.text
            h.name.setTextColor(textColor)
            h.value.setTextColor(textColor)

            // Reading and target on one line:  69%  (60%)  −  +
            val live = connected && s != null && !s.isUnavailable && !isSensor
            val rs = if (live) Extras.readings(id, states, prefs) else emptyList()
            val t = if (live) Extras.target(id, states, prefs, rs.firstOrNull()?.unit) else null
            h.extraRow.visibility = if (rs.isNotEmpty() || t != null) View.VISIBLE else View.GONE
            val line = android.text.SpannableStringBuilder()
            // whole numbers: "16° 66%"
            line.append(rs.joinToString(" ") { r -> (r.value?.let { Math.round(it).toString() } ?: "–") + r.unit })
            if (t != null) {
                val shown = pendingTarget[id] ?: t.value
                if (line.isNotEmpty()) line.append(" ")
                val from = line.length
                line.append("(${Extras.fmt(shown, whole = true)}${t.unit})")
                // The target a little smaller; faded while it waits for Home Assistant.
                line.setSpan(android.text.style.RelativeSizeSpan(0.8f), from, line.length, 0)
                if (pendingTarget.containsKey(id))
                    line.setSpan(android.text.style.ForegroundColorSpan((textColor and 0x00FFFFFF) or 0x99000000.toInt()), from, line.length, 0)
                h.minus.visibility = View.VISIBLE; h.plus.visibility = View.VISIBLE
                h.minus.setOnClickListener { stepTarget(id, -1, it) }
                h.plus.setOnClickListener { stepTarget(id, +1, it) }
            } else {
                h.minus.visibility = View.GONE; h.plus.visibility = View.GONE
            }
            h.reading.setTextColor(textColor)
            h.reading.text = line
            fitLine(h.reading)
            // Only unusual states (…, offline, "open") get words; ON/OFF is the colour.
            h.value.visibility = if (h.value.text.isEmpty()) View.GONE else View.VISIBLE
            if (h.value.visibility == View.VISIBLE) fitValue(h.value)
            for (tv in listOf(h.minus, h.plus)) tv.setTextColor(textColor)
            h.itemView.alpha = if (pending.containsKey(id)) 0.7f else 1f

            if (isSensor) {
                h.itemView.setOnClickListener(null)
                h.itemView.isClickable = false
            } else {
                h.itemView.setOnClickListener { press(id, it) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiGuard.stop()
        main.removeCallbacksAndMessages(null)
    }
}
