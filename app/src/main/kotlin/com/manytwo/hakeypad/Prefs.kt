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

    /** Screen brightness while dimmed, 1-100 %. */
    var dimPercent: Int
        get() = p.getInt("dim_percent", 3)
        set(v) = p.edit().putInt("dim_percent", v.coerceIn(1, 100)).apply()

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
