package com.manytwo.hakeypad

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Server, token, look and the list of buttons. Opened by holding the keypad title for 2 s. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var title: EditText
    private lateinit var columns: EditText
    private lateinit var dimAfter: EditText
    private lateinit var dimPercent: EditText
    private lateinit var pin: EditText
    private lateinit var buttonList: LinearLayout
    private lateinit var testResult: TextView
    private var buttons = mutableListOf<String>()
    private lateinit var wifiGuardBox: CheckBox
    private lateinit var wifiSsid: EditText
    private lateinit var wifiAps: EditText
    private lateinit var wifiMinRssi: EditText
    private lateinit var guard: WifiGuard

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val chosen = r.data?.getStringArrayListExtra(PickerActivity.EXTRA_SELECTED) ?: return@registerForActivityResult
        // Keep the existing order for buttons that stay, add new ones at the end.
        val kept = buttons.filter { it in chosen }
        buttons = (kept + chosen.filter { it !in kept }).toMutableList()
        showButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        server = findViewById(R.id.server)
        token = findViewById(R.id.token)
        title = findViewById(R.id.title)
        columns = findViewById(R.id.columns)
        dimAfter = findViewById(R.id.dim_after)
        dimPercent = findViewById(R.id.dim_percent)
        pin = findViewById(R.id.pin)
        buttonList = findViewById(R.id.button_list)
        testResult = findViewById(R.id.test_result)

        server.setText(prefs.serverUrl)
        token.setText(prefs.token)
        title.setText(prefs.title)
        columns.setText(prefs.columns.toString())
        dimAfter.setText(prefs.dimAfterSec.toString())
        dimPercent.setText(prefs.dimPercent.toString())
        pin.setText(prefs.pin)
        buttons = prefs.buttons.toMutableList()
        showButtons()
        setUpWifi()
        setUpScreen()

        findViewById<TextView>(R.id.version).text =
            "HA Keypad ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILD_TIME}"

        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            if (server.text.isBlank() || token.text.isBlank()) {
                toast("Enter the server and token first"); return@setOnClickListener
            }
            pick.launch(Intent(this, PickerActivity::class.java)
                .putExtra(PickerActivity.EXTRA_SERVER, server.text.toString())
                .putExtra(PickerActivity.EXTRA_TOKEN, token.text.toString())
                .putStringArrayListExtra(PickerActivity.EXTRA_SELECTED, ArrayList(buttons)))
        }
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            // Lets the user pick this app as the Home app, so it is always on screen.
            Kiosk.off(this)    // Android's own settings can't open in kiosk mode
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save(); finish() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    // ---- screen (day/night, wake) and wall keypad ----

    private fun hhmm(m: Int) = String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    private fun parseHhmm(t: String, default: Int): Int {
        val p = t.trim().split(':', '.', ' ').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        return when (p.size) {
            1 -> if (p[0] in 0..23) p[0] * 60 else default
            2 -> if (p[0] in 0..23 && p[1] in 0..59) p[0] * 60 + p[1] else default
            else -> default
        }
    }
    private fun et(id: Int) = findViewById<EditText>(id)
    private fun cb(id: Int) = findViewById<CheckBox>(id)

    private fun setUpScreen() {
        et(R.id.day_start).setText(hhmm(prefs.dayStart))
        et(R.id.day_end).setText(hhmm(prefs.dayEnd))
        et(R.id.day_level).setText(if (prefs.dayLevel == -1) "" else prefs.dayLevel.toString())
        et(R.id.day_idle).setText(prefs.dayIdlePercent.toString())
        et(R.id.night_wake).setText(prefs.nightWakePercent.toString())
        cb(R.id.wake_light).isChecked = prefs.wakeOnLight
        cb(R.id.wake_prox).isChecked = prefs.wakeOnProximity
        cb(R.id.wake_motion).isChecked = prefs.wakeOnMotion
        et(R.id.motion_percent).setText(prefs.motionPercent.toString())
        cb(R.id.wake_change).isChecked = prefs.wakeOnChange
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val hasLight = sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT) != null
        val hasProx = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY) != null
        cb(R.id.wake_light).isEnabled = hasLight
        cb(R.id.wake_motion).isEnabled = hasLight
        cb(R.id.wake_prox).isEnabled = hasProx
        findViewById<TextView>(R.id.screen_note).text = buildString {
            append("The screen stays on only while the keypad is open. ")
            if (!hasLight) append("This phone has no light sensor: \"automatic\" uses the phone's own setting. ")
            if (!hasProx) append("This phone has no proximity sensor. ")
            append("The first touch on a very dim screen only wakes it.")
        }

        val owner = WifiGuard.isDeviceOwner(this)
        cb(R.id.auto_start).isChecked = prefs.autoStart
        cb(R.id.kiosk).isChecked = prefs.kiosk
        cb(R.id.auto_start).isEnabled = owner
        cb(R.id.kiosk).isEnabled = owner
        findViewById<TextView>(R.id.wall_note).text = if (owner)
            "For a phone that stays on the wall. Leave these off on a phone you carry around."
        else
            "Opening at start-up and kiosk need HA Keypad to be device owner (see Wi-Fi below). Not needed on a phone you carry around."
        et(R.id.battery_entity).setText(prefs.batteryEntity)
        findViewById<TextView>(R.id.battery_now).text = "Battery now: " + Battery(this, prefs).describe() +
            ". On charge all the time? Then a smart plug on the charger, switched by Home Assistant to keep 40-80 %, makes the battery last much longer."
    }

    private fun saveScreen() {
        prefs.dayStart = parseHhmm(et(R.id.day_start).text.toString(), 7 * 60)
        prefs.dayEnd = parseHhmm(et(R.id.day_end).text.toString(), 23 * 60)
        prefs.dayLevel = et(R.id.day_level).text.toString().trim().let { if (it.isEmpty()) -1 else it.toIntOrNull() ?: -1 }
        prefs.dayIdlePercent = et(R.id.day_idle).text.toString().toIntOrNull() ?: 30
        prefs.nightWakePercent = et(R.id.night_wake).text.toString().toIntOrNull() ?: 20
        prefs.wakeOnLight = cb(R.id.wake_light).isChecked
        prefs.wakeOnProximity = cb(R.id.wake_prox).isChecked
        prefs.wakeOnMotion = cb(R.id.wake_motion).isChecked
        prefs.motionPercent = et(R.id.motion_percent).text.toString().toIntOrNull() ?: 15
        prefs.wakeOnChange = cb(R.id.wake_change).isChecked
        prefs.autoStart = cb(R.id.auto_start).isChecked
        prefs.kiosk = cb(R.id.kiosk).isChecked
        prefs.batteryEntity = et(R.id.battery_entity).text.toString()
        DebugLog.add("APP", "settings saved: day ${hhmm(prefs.dayStart)}-${hhmm(prefs.dayEnd)}, day level ${prefs.dayLevel}, " +
            "idle ${prefs.dayIdlePercent}/${prefs.dimPercent} %, night wake ${prefs.nightWakePercent} %, " +
            "wake light/motion(${prefs.motionPercent} %)/prox/change ${prefs.wakeOnLight}/${prefs.wakeOnMotion}/${prefs.wakeOnProximity}/${prefs.wakeOnChange}, " +
            "auto start ${prefs.autoStart}, kiosk ${prefs.kiosk}, battery entity ${prefs.batteryEntity.ifBlank { "none" }}")
    }

    private fun save() {
        prefs.serverUrl = server.text.toString()
        prefs.token = token.text.toString()
        prefs.title = title.text.toString().ifBlank { "Keypad" }
        prefs.columns = columns.text.toString().toIntOrNull() ?: 2
        prefs.dimAfterSec = dimAfter.text.toString().toIntOrNull() ?: 60
        prefs.dimPercent = dimPercent.text.toString().toIntOrNull() ?: 3
        prefs.pin = pin.text.toString()
        prefs.buttons = buttons
        saveScreen()
        prefs.wifiGuard = wifiGuardBox.isChecked
        prefs.wifiSsid = wifiSsid.text.toString()
        prefs.wifiAps = wifiAps.text.toString()
        prefs.wifiMinRssi = wifiMinRssi.text.toString().toIntOrNull() ?: -80
        toast("Saved")
    }

    /** One row per chosen button: name, ▲ ▼ to reorder, ✎ to rename, ✕ to remove. */
    private fun showButtons() {
        buttonList.removeAllViews()
        if (buttons.isEmpty()) {
            buttonList.addView(TextView(this).apply {
                text = "No buttons yet. Tap \"Choose buttons\"."
                setPadding(0, 8, 0, 8)
            })
            return
        }
        buttons.forEachIndexed { i, id ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_button_row, buttonList, false)
            row.findViewById<TextView>(R.id.row_name).text =
                (prefs.label(id) ?: prefs.cachedName(id))?.let { "$it\n$id" } ?: id
            row.findViewById<View>(R.id.row_up).setOnClickListener {
                if (i > 0) { buttons.add(i - 1, buttons.removeAt(i)); showButtons() }
            }
            row.findViewById<View>(R.id.row_down).setOnClickListener {
                if (i < buttons.size - 1) { buttons.add(i + 1, buttons.removeAt(i)); showButtons() }
            }
            row.findViewById<View>(R.id.row_rename).setOnClickListener { rename(id) }
            row.findViewById<View>(R.id.row_remove).setOnClickListener { buttons.removeAt(i); showButtons() }
            buttonList.addView(row)
        }
    }

    private fun rename(id: String) {
        val input = EditText(this).apply { setText(prefs.label(id) ?: ""); hint = "Leave empty to use the HA name" }
        AlertDialog.Builder(this)
            .setTitle("Button label")
            .setMessage(id)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> prefs.setLabel(id, input.text.toString()); showButtons() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Wi-Fi ----

    private fun setUpWifi() {
        guard = WifiGuard(this, prefs)
        wifiGuardBox = findViewById(R.id.wifi_guard)
        wifiSsid = findViewById(R.id.wifi_ssid)
        wifiAps = findViewById(R.id.wifi_aps)
        wifiMinRssi = findViewById(R.id.wifi_min_rssi)
        wifiGuardBox.isChecked = prefs.wifiGuard
        wifiSsid.setText(prefs.wifiSsid)
        wifiAps.setText(prefs.wifiAps)
        wifiMinRssi.setText(prefs.wifiMinRssi.toString())
        showOwner()
        findViewById<TextView>(R.id.wifi_now).text = currentWifi()

        // Without device owner, location permission must be granted by the user to see APs.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !WifiGuard.isDeviceOwner(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }

        findViewById<Button>(R.id.btn_nearby).setOnClickListener { showNearby() }
        findViewById<Button>(R.id.btn_wifi_log).setOnClickListener {
            // Newest first, without the year, in small type so a line fits the screen.
            val lines = guard.log.lines().takeLast(200).reversed().map { it.removeRange(0, minOf(5, it.length)) }
            val text = TextView(this).apply {
                text = "Full debug log (presses, connection, every Home Assistant check): ${DebugLog.where}\n\n" +
                    (if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n"))
                textSize = 12f
                setTextIsSelectable(true)
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            AlertDialog.Builder(this)
                .setTitle("Wi-Fi log (newest first)")
                .setView(android.widget.ScrollView(this).apply { addView(text) })
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        wifiGuardBox.setOnCheckedChangeListener { _, on ->
            if (!on && prefs.wifiPinnedTo.isNotBlank()) AlertDialog.Builder(this)
                .setTitle("Wi-Fi guard off")
                .setMessage(pinNote.trim())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        findViewById<Button>(R.id.btn_remove_owner).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Stop being device owner?")
                .setMessage("The keypad will then only watch the Wi-Fi, not repair it. To turn it back on you need adb (see the text above).$pinNote")
                .setPositiveButton("Stop") { _, _ ->
                    guard.log.add("Device owner removed in Settings")
                    Kiosk.off(this); prefs.kiosk = false; prefs.autoStart = false
                    @Suppress("DEPRECATION")
                    getSystemService(DevicePolicyManager::class.java).clearDeviceOwnerApp(packageName)
                    showOwner()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Android 10 can't undo a pin, so say how to do it by hand. */
    private val pinNote: String get() = prefs.wifiPinnedTo.let { b ->
        if (b.isBlank()) "" else "\n\nNote: the phone stays tied to access point ${guard.name(b)}. " +
            "To let Android choose again: Android Settings → Wi-Fi → ${prefs.wifiSsid} → Forget, then join it again with the password."
    }

    private fun showOwner() {
        val owner = WifiGuard.isDeviceOwner(this)
        findViewById<TextView>(R.id.wifi_owner).text = if (owner)
            "HA Keypad is device owner: it can repair the Wi-Fi."
        else
            "HA Keypad is not device owner, so it can only watch the Wi-Fi. To let it repair the Wi-Fi, remove all accounts from the phone, then run on a computer:\nadb shell dpm set-device-owner $packageName/.KeypadAdmin"
        findViewById<View>(R.id.btn_remove_owner).visibility = if (owner) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun currentWifi(): String {
        val w = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        val i = w.connectionInfo
        if (!w.isWifiEnabled) return "Wi-Fi is off"
        if (i == null || i.networkId == -1) return "Not connected to Wi-Fi"
        return "Now: ${i.ssid.trim('"')} · ${guard.name(i.bssid?.lowercase())} · ${i.rssi} dBm · ${i.frequency} MHz"
    }

    /** Lists the access points of the network; tick some to add them to the preferred list. */
    private fun showNearby() {
        if (prefs.wifiSsid.isBlank() && wifiSsid.text.isNotBlank()) prefs.wifiSsid = wifiSsid.text.toString()
        val list = guard.nearby()
        if (list.isEmpty()) {
            toast("No access points found (is location on?)"); return
        }
        val known = WifiGuard.parseAps(wifiAps.text.toString()).toMap()
        val labels = list.map {
            val band = if (it.frequency > 4900) "5 GHz" else "2.4 GHz"
            "${known[it.BSSID] ?: it.BSSID}\n${it.level} dBm · $band"
        }.toTypedArray()
        val ticked = BooleanArray(list.size) { list[it].BSSID in known }
        AlertDialog.Builder(this)
            .setTitle("Access points of \"${prefs.wifiSsid}\"")
            .setMultiChoiceItems(labels, ticked) { _, i, on -> ticked[i] = on }
            .setPositiveButton("Add ticked") { _, _ ->
                val lines = wifiAps.text.toString().lines().filter { it.isNotBlank() }.toMutableList()
                list.forEachIndexed { i, r ->
                    if (ticked[i] && r.BSSID !in known) lines += "ap${lines.size + 1}  ${r.BSSID}"
                }
                wifiAps.setText(lines.joinToString("\n"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var testClient: HaClient? = null

    private fun test() {
        testClient?.stop()
        testResult.text = "Testing…"
        testClient = HaClient(server.text.toString(), token.text.toString(), object : HaClient.Listener {
            override fun onConnection(status: HaClient.Status, detail: String) {
                when (status) {
                    HaClient.Status.CONNECTED -> testResult.text = "Connected ✓ — loading states…"
                    HaClient.Status.AUTH_FAILED -> { testResult.text = "Token rejected: $detail"; testClient?.stop() }
                    HaClient.Status.DISCONNECTED -> { testResult.text = "Can't connect: $detail"; testClient?.stop() }
                    HaClient.Status.CONNECTING -> {}
                }
            }
            override fun onAllStates(states: Map<String, EntityState>) {
                testResult.text = "Connected ✓ — ${states.size} entities"
                testClient?.stop()
            }
            override fun onStateChanged(state: EntityState) {}
        }).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        testClient?.stop()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
