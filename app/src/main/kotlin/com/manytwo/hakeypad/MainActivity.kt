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
    private val states = HashMap<String, EntityState>()
    private val pending = HashMap<String, Long>()   // entity id → time pressed

    private val main = Handler(Looper.getMainLooper())
    private lateinit var screen: ScreenControl
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
            append("Since ").append(since).append(". Check the Wi-Fi.\nIt reconnects by itself.")
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

        // Watches the Wi-Fi all the time the keypad exists, not only while it is on screen.
        DebugLog.add("APP", "started: ${DebugLog.deviceLine(this)}")
        DebugLog.add("APP", "server ${android.net.Uri.parse(prefs.serverUrl).host ?: "not set"}, " +
            "${prefs.buttons.size} tiles, device owner ${if (WifiGuard.isDeviceOwner(this)) "yes" else "no"}, " +
            "Wi-Fi guard ${if (prefs.wifiGuard) "on" else "off"}, log ${DebugLog.where}")
        wifiGuard = WifiGuard(this, prefs).also {
            it.onStatus = { if (offlineView.visibility == View.VISIBLE) showOffline(wake = false) }
            it.start()
        }

        // This is a fixed wall keypad: Back does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
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
        screen = ScreenControl(this, prefs, findViewById(R.id.root))
        screen.start()
        batteryTick = 0
        main.post(clockRunnable)
        startClient()
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
        adapter.notifyDataSetChanged()
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
     * Autosize only checks that the text fits the box; a long word ("Dehumidifier") is then
     * broken in the middle. Cap the largest size so the longest word fits on one line.
     */
    private fun fitLongestWord(tv: TextView, text: String) {
        tv.post {
            val w = tv.width - tv.paddingLeft - tv.paddingRight
            if (w <= 0) return@post
            val longest = text.split(' ').maxByOrNull { it.length } ?: return@post
            val paint = android.text.TextPaint(tv.paint)
            val sp = resources.displayMetrics.scaledDensity
            var max = 22
            while (max > 13) {
                paint.textSize = max * sp
                if (paint.measureText(longest) <= w) break
                max--
            }
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv, 13, max, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
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
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
            return VH(v)
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

            val colorRes: Int
            when {
                pending.containsKey(id) && !connected -> {
                    h.value.text = "…"        // pressed while offline: waits for the connection
                    colorRes = R.color.tile_unavailable
                }
                s == null || !connected -> {
                    h.value.text = if (!connected) "–" else "not found"
                    colorRes = R.color.tile_unavailable
                }
                s.isUnavailable -> {
                    h.value.text = "offline"
                    colorRes = R.color.tile_unavailable
                }
                isSensor -> {
                    h.value.text = if (s.state.isBlank()) "–" else listOfNotNull(s.state, s.unit).joinToString(" ")
                    colorRes = R.color.tile_sensor
                }
                pending.containsKey(id) -> {
                    h.value.text = "…"
                    colorRes = if (s.isOn) R.color.tile_on else R.color.tile_off
                }
                else -> {
                    h.value.text = if (s.isOn) "ON" else if (s.state == "off") "OFF" else s.state
                    colorRes = if (s.isOn) R.color.tile_on else R.color.tile_off
                }
            }
            bg.setColor(ContextCompat.getColor(this@MainActivity, colorRes))
            val textColor = if (colorRes == R.color.tile_on) R.color.text_on else R.color.text_off
            h.name.setTextColor(ContextCompat.getColor(this@MainActivity, textColor))
            h.value.setTextColor(ContextCompat.getColor(this@MainActivity, textColor))
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
