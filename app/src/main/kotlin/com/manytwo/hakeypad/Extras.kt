package com.manytwo.hakeypad

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The extra lines on a tile: the room reading (temperature or humidity) and a target that
 * − / + change (the setting at which the device switches off, or a heater's own thermostat).
 *
 * Nothing about a particular house is built in. Each tile's reading and target can be set in
 * Settings; when they are not, they are found from what Home Assistant has:
 *  - climate tile: reading = its current_temperature, target = its own setpoint.
 *  - other tiles: reading = sensor.<same id>_temperature / _humidity (a Sonoff with a sensor);
 *    target = an input_number whose name starts with the tile's name followed by " off"
 *    (e.g. "Pink Trees Heater" → "Pink Trees Heater off above").
 */
object Extras {

    class Reading(val entityId: String, val value: Double?, val unit: String)

    class Target(
        val entityId: String,
        val value: Double?,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String,
        /** Short word before the value: "off" (a rule's switch-off point) or "set" (thermostat). */
        val word: String,
        val isClimate: Boolean,
    ) {
        /** − / + always move in whole steps (1° or 1 %), whatever HA's own step is. */
        val tapStep: Double get() = maxOf(step, 1.0)

        /** One tap from [from]: a half value (13.5) snaps to the next whole one, then ±1. */
        fun next(from: Double, dir: Int): Double {
            val whole = if (dir > 0) kotlin.math.floor(from + 1e-6) + tapStep else kotlin.math.ceil(from - 1e-6) - tapStep
            return whole.coerceIn(kotlin.math.ceil(min - 1e-6), kotlin.math.floor(max + 1e-6))
        }
    }

    const val NONE = "-"

    /**
     * The reading entities for [tileId]: from Settings (one, or several separated by commas,
     * e.g. temperature and humidity), or found automatically. Empty = none.
     */
    fun readingIds(tileId: String, states: Map<String, EntityState>, prefs: Prefs): List<String> {
        prefs.readingFor(tileId)?.let { v ->
            return if (v == NONE) emptyList() else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOfNotNull(autoReading(tileId, states))
    }

    fun targetId(tileId: String, states: Map<String, EntityState>, prefs: Prefs): String? {
        prefs.targetFor(tileId)?.let { return if (it == NONE) null else it }
        return autoTarget(tileId, states)
    }

    fun autoReading(tileId: String, states: Map<String, EntityState>): String? {
        val s = states[tileId] ?: return null
        if (s.domain == "climate") return if (s.attrs.has("current_temperature")) tileId else null
        if (s.domain == "sensor") return null
        // sensor.<object id>_temperature/_humidity; Sonoff multi-channel ids end in _1, _2…
        val obj = tileId.substringAfter('.')
        val bases = listOf(obj, obj.replace(Regex("_\\d+$"), "")).distinct()
        val found = bases.flatMap { b -> listOf("sensor.${b}_humidity", "sensor.${b}_temperature") }
            .filter { states.containsKey(it) }
        if (found.isEmpty()) return null
        // A dehumidifier wants the humidity; anything else the temperature.
        val humid = s.name.contains("humid", ignoreCase = true) ||
            autoTarget(tileId, states)?.let { states[it]?.name?.contains("humid", true) } == true
        return found.firstOrNull { it.endsWith(if (humid) "_humidity" else "_temperature") } ?: found.first()
    }

    fun autoTarget(tileId: String, states: Map<String, EntityState>): String? {
        val s = states[tileId] ?: return null
        if (s.domain == "climate") return if (s.attrs.has("temperature") && !s.attrs.isNull("temperature")) tileId else null
        if (s.domain == "sensor") return null
        val prefix = s.name.trim().lowercase(Locale.ROOT) + " off"
        return states.values.filter { it.domain == "input_number" && it.name.lowercase(Locale.ROOT).startsWith(prefix) }
            .minByOrNull { it.entityId }?.entityId
    }

    fun readings(tileId: String, states: Map<String, EntityState>, prefs: Prefs): List<Reading> =
        readingIds(tileId, states, prefs).map { id ->
            val s = states[id]
            when {
                s == null -> Reading(id, null, "")
                s.domain == "climate" -> Reading(id, s.num("current_temperature"), "°")
                else -> Reading(id, s.state.toDoubleOrNull(), unitShort(s.unit))
            }
        }

    fun target(tileId: String, states: Map<String, EntityState>, prefs: Prefs, readingUnit: String?): Target? {
        val id = targetId(tileId, states, prefs) ?: return null
        val s = states[id] ?: return null
        return if (s.domain == "climate") Target(
            id, s.num("temperature"),
            s.num("min_temp") ?: 5.0, s.num("max_temp") ?: 35.0,
            s.num("target_temp_step") ?: 0.5, "°", "set", true,
        ) else Target(
            id, s.state.toDoubleOrNull(),
            s.num("min") ?: 0.0, s.num("max") ?: 100.0, s.num("step") ?: 1.0,
            s.unit?.let { unitShort(it) } ?: readingUnit ?: "", "off", false,
        )
    }

    /** "°C" → "°" to save room; "%" stays. */
    private fun unitShort(u: String?): String = when {
        u == null -> ""
        u.startsWith("°") -> "°"
        else -> u
    }

    /** Reading: 17.66 → "17.7". Target (whole = true): 13.0 → "13", 13.5 → "13.5". */
    fun fmt(v: Double?, whole: Boolean = false): String {
        if (v == null) return "–"
        val decimals = if (whole && abs(v - v.roundToLong()) < 1e-6) 0 else 1
        return String.format(Locale.getDefault(), "%.${decimals}f", v)
    }
}
