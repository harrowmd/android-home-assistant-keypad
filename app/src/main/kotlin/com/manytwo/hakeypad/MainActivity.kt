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
    private val adapter = TileAdapter()

    private var client: HaClient? = null
    private val states = HashMap<String, EntityState>()
    private val pending = HashMap<String, Long>()   // entity id → time pressed

    private val main = Handler(Looper.getMainLooper())
    private var dimmed = false
    private val dimRunnable = Runnable { setDimmed(true) }
    private val clockRunnable = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            main.postDelayed(this, 1000L * (60 - (System.currentTimeMillis() / 1000) % 60))
        }
    }
    private val settingsHold = Runnable { askPinThenOpenSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        titleView = findViewById(R.id.title)
        clockView = findViewById(R.id.clock)
        statusView = findViewById(R.id.status)
        emptyView = findViewById(R.id.empty)
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.itemAnimator = null
        // Tile heights depend on the grid height, so redraw once it is known or changes.
        grid.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) grid.post { adapter.notifyDataSetChanged() }
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

        // This is a fixed wall keypad: Back does nothing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        titleView.text = prefs.title
        val cols = prefs.columns
        grid.layoutManager = GridLayoutManager(this, cols)
        adapter.setItems(prefs.buttons)
        updateEmpty()
        main.post(clockRunnable)
        startClient()
        setDimmed(false)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockRunnable)
        main.removeCallbacks(dimRunnable)
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
    }

    private fun updateEmpty() {
        emptyView.visibility = if (prefs.buttons.isEmpty() || !prefs.isConfigured) View.VISIBLE else View.GONE
        emptyView.setText(if (!prefs.isConfigured) R.string.empty_not_setup else R.string.empty_no_buttons)
    }

    // ---- dimming: the first touch on a dimmed screen only wakes it ----

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (dimmed) {
                setDimmed(false)
                swallowUntilUp = true
                return true
            }
            scheduleDim()
        }
        if (swallowUntilUp) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowUntilUp = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
    private var swallowUntilUp = false

    private fun scheduleDim() {
        main.removeCallbacks(dimRunnable)
        val s = prefs.dimAfterSec
        if (s > 0) main.postDelayed(dimRunnable, s * 1000L)
    }

    private fun setDimmed(d: Boolean) {
        dimmed = d
        val lp = window.attributes
        lp.screenBrightness = if (d) prefs.dimPercent / 100f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        findViewById<View>(R.id.root).alpha = if (d) 0.5f else 1f
        if (!d) scheduleDim()
    }

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
    }
    private var connected = false

    override fun onAllStates(states: Map<String, EntityState>) {
        this.states.clear()
        this.states.putAll(states)
        adapter.notifyDataSetChanged()
    }

    override fun onStateChanged(state: EntityState) {
        states[state.entityId] = state
        pending.remove(state.entityId)
        adapter.refresh(state.entityId)
    }

    private fun showStatus(text: String, colorRes: Int) {
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun press(entityId: String, v: View) {
        val c = client ?: return
        if (!connected) return
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pending[entityId] = System.currentTimeMillis()
        adapter.refresh(entityId)
        c.press(entityId)
        // If HA never reports a change (e.g. device offline), stop showing "…" after 6 s.
        main.postDelayed({ if (pending.remove(entityId) != null) adapter.refresh(entityId) }, 6000)
    }

    // ---- the grid of tiles ----

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
            // Make the tiles fill the screen: share the grid height between the rows.
            val cols = (grid.layoutManager as? GridLayoutManager)?.spanCount ?: 2
            val rows = ((items.size + cols - 1) / cols).coerceAtLeast(1)
            val minH = (110 * resources.displayMetrics.density).toInt()
            val gridH = grid.height - grid.paddingTop - grid.paddingBottom
            h.itemView.layoutParams.height = if (gridH > 0) (gridH / rows).coerceAtLeast(minH) else minH

            val label = prefs.label(id) ?: s?.name ?: id
            h.name.text = label
            val isSensor = id.startsWith("sensor.") || id.startsWith("binary_sensor.")
            val bg = h.tile.background.mutate() as GradientDrawable

            val colorRes: Int
            when {
                s == null || !connected -> {
                    h.value.text = if (!connected) "–" else "not found"
                    colorRes = R.color.tile_unavailable
                }
                s.isUnavailable -> {
                    h.value.text = "offline"
                    colorRes = R.color.tile_unavailable
                }
                isSensor -> {
                    h.value.text = listOfNotNull(s.state, s.unit).joinToString(" ")
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
        main.removeCallbacksAndMessages(null)
    }
}
