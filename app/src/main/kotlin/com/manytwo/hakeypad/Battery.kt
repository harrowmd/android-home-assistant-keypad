package com.manytwo.hakeypad

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Battery level, charging and temperature: written to the debug log every 10 minutes, and
 * optionally sent to a Home Assistant input_number (Settings → Battery). With that, a Home
 * Assistant automation can switch a smart plug to keep the battery between e.g. 40 and 80 %,
 * which is much kinder to a phone that is on charge all the time.
 */
class Battery(private val ctx: Context, private val prefs: Prefs) {

    data class State(val percent: Int, val charging: String, val tempC: Float, val health: String)

    fun read(): State? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "on charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "on USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "on wireless charger"
            else -> "not charging"
        }
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEATING"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        return State(level * 100 / scale, plugged, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f, health)
    }

    fun describe(): String = read()?.let { "${it.percent} %, ${it.charging}, ${it.tempC} °C, health ${it.health}" } ?: "unknown"

    /** Log the battery, and send it to Home Assistant if an entity is set. */
    fun report(client: HaClient?) {
        DebugLog.add("BATT", describe())
        send(client)
    }

    fun send(client: HaClient?) {
        val entity = prefs.batteryEntity.ifBlank { return }
        val s = read() ?: return
        if (client?.callService("input_number", "set_value", entity, JSONObject().put("value", s.percent)) == true)
            DebugLog.add("BATT", "sent ${s.percent} % to $entity")
    }
}
