package com.manytwo.hakeypad

import android.content.Context
import org.json.JSONArray

/** All settings live on the phone only. Nothing personal is built into the app. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("keypad", Context.MODE_PRIVATE)

    /** e.g. https://home.example.com:8123 (no trailing slash needed). */
    var serverUrl: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()

    /** Home Assistant long-lived access token (Profile → Security in HA). */
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    /** Shown at the top of the keypad. */
    var title: String
        get() = p.getString("title", "Keypad") ?: "Keypad"
        set(v) = p.edit().putString("title", v.trim()).apply()

    var columns: Int
        get() = p.getInt("columns", 2)
        set(v) = p.edit().putInt("columns", v.coerceIn(1, 4)).apply()

    /** Seconds without a touch before the screen dims. 0 = never dim. */
    var dimAfterSec: Int
        get() = p.getInt("dim_after", 60)
        set(v) = p.edit().putInt("dim_after", v.coerceAtLeast(0)).apply()

    /** Night brightness when idle (dimmed), 1-100 %. */
    var dimPercent: Int
        get() = p.getInt("dim_percent", 3)
        set(v) = p.edit().putInt("dim_percent", v.coerceIn(1, 100)).apply()

    // ---- day / night brightness ----

    /** Day starts and ends, minutes after midnight (default 07:00-23:00). Outside = night. */
    var dayStart: Int
        get() = p.getInt("day_start", 7 * 60)
        set(v) = p.edit().putInt("day_start", v.coerceIn(0, 1439)).apply()
    var dayEnd: Int
        get() = p.getInt("day_end", 23 * 60)
        set(v) = p.edit().putInt("day_end", v.coerceIn(0, 1439)).apply()

    /** Daytime brightness when in use: -1 = automatic from the light sensor, 0 = the phone's own setting, 1-100 %. */
    var dayLevel: Int
        get() = p.getInt("day_level", -1)
        set(v) = p.edit().putInt("day_level", v.coerceIn(-1, 100)).apply()

    /** Daytime brightness when nobody has touched it for dimAfterSec, 1-100 %. */
    var dayIdlePercent: Int
        get() = p.getInt("day_idle", 30)
        set(v) = p.edit().putInt("day_idle", v.coerceIn(1, 100)).apply()

    /** Night brightness when woken, 1-100 %. (Night idle = dimPercent.) */
    var nightWakePercent: Int
        get() = p.getInt("night_wake", 20)
        set(v) = p.edit().putInt("night_wake", v.coerceIn(1, 100)).apply()

    /** What brightens the screen besides a touch. */
    var wakeOnLight: Boolean
        get() = p.getBoolean("wake_light", true)
        set(v) = p.edit().putBoolean("wake_light", v).apply()
    /** Movement nearby: a shadow changes the room light by this much, %. */
    var wakeOnMotion: Boolean
        get() = p.getBoolean("wake_motion", true)
        set(v) = p.edit().putBoolean("wake_motion", v).apply()
    var motionPercent: Int
        get() = p.getInt("motion_percent", 15)
        set(v) = p.edit().putInt("motion_percent", v.coerceIn(3, 80)).apply()
    var wakeOnProximity: Boolean
        get() = p.getBoolean("wake_prox", true)
        set(v) = p.edit().putBoolean("wake_prox", v).apply()
    var wakeOnChange: Boolean
        get() = p.getBoolean("wake_change", true)
        set(v) = p.edit().putBoolean("wake_change", v).apply()

    // ---- wall keypad (need device owner) ----

    /** Open the keypad when the phone starts. */
    var autoStart: Boolean
        get() = p.getBoolean("auto_start", false)
        set(v) = p.edit().putBoolean("auto_start", v).apply()

    /** Kiosk: pin the keypad on screen (Android lock task mode). */
    var kiosk: Boolean
        get() = p.getBoolean("kiosk", false)
        set(v) = p.edit().putBoolean("kiosk", v).apply()

    /** input_number entity in Home Assistant that gets the battery level. Empty = off. */
    var batteryEntity: String
        get() = p.getString("battery_entity", "") ?: ""
        set(v) = p.edit().putString("battery_entity", v.trim()).apply()

    /** Optional PIN that protects Settings. Empty = no PIN. */
    var pin: String
        get() = p.getString("pin", "") ?: ""
        set(v) = p.edit().putString("pin", v.trim()).apply()

    /** Entity ids shown as buttons/tiles, in display order. */
    var buttons: List<String>
        get() {
            val a = JSONArray(p.getString("buttons", "[]"))
            return (0 until a.length()).map { a.getString(it) }
        }
        set(v) = p.edit().putString("buttons", JSONArray(v).toString()).apply()

    /** Optional custom labels: entity id → label. */
    fun label(entityId: String): String? = p.getString("label:$entityId", null)?.takeIf { it.isNotBlank() }
    fun setLabel(entityId: String, label: String?) {
        if (label.isNullOrBlank()) p.edit().remove("label:$entityId").apply()
        else p.edit().putString("label:$entityId", label.trim()).apply()
    }

    /** Wi-Fi guard on/off (see WifiGuard). Repairs only happen when the app is device owner. */
    var wifiGuard: Boolean
        get() = p.getBoolean("wifi_guard", true)
        set(v) = p.edit().putBoolean("wifi_guard", v).apply()

    /** The Wi-Fi network to look after. Empty = the one the phone is on (filled in automatically). */
    var wifiSsid: String
        get() = p.getString("wifi_ssid", "") ?: ""
        set(v) = p.edit().putString("wifi_ssid", v.trim()).apply()

    /** Preferred access points, best first, one per line: "name  BSSID". Empty = strongest. */
    var wifiAps: String
        get() = p.getString("wifi_aps", "") ?: ""
        set(v) = p.edit().putString("wifi_aps", v.trim()).apply()

    /** Signals weaker than this (dBm) count as bad when a better AP is visible. */
    var wifiMinRssi: Int
        get() = p.getInt("wifi_min_rssi", -80)
        set(v) = p.edit().putInt("wifi_min_rssi", v.coerceIn(-95, -40)).apply()

    /**
     * The AP the guard last tied the saved network to. Android 10 cannot untie it again;
     * only "forget network" in Android's Wi-Fi settings and joining again does that.
     */
    var wifiPinnedTo: String
        get() = p.getString("wifi_pinned_to", "") ?: ""
        set(v) = p.edit().putString("wifi_pinned_to", v).apply()

    /** Last name Home Assistant gave each entity, so tiles keep their names while offline. */
    fun cachedName(entityId: String): String? = p.getString("name:$entityId", null)
    fun cacheNames(names: Map<String, String>) {
        val e = p.edit()
        for ((id, n) in names) if (p.getString("name:$id", null) != n) e.putString("name:$id", n)
        e.apply()
    }

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
}
