package com.manytwo.hakeypad

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.log10

/**
 * Screen brightness by day and night, and what wakes the screen.
 *
 * Day (default 07:00-23:00): in use = automatic from the light sensor, the phone's own
 * setting, or a fixed level; idle = a medium level. Night: idle = very dim, with the tiles
 * darkened too; woken = a low level. Besides a touch, the screen wakes when the room light
 * goes on (light sensor), when a hand comes near the top of the phone (proximity sensor),
 * or when a device on the keypad is switched elsewhere.
 *
 * Only runs while the keypad is on screen (sensors are released in stop()), so on a normal
 * phone that opens the keypad now and then it costs nothing in the background.
 */
class ScreenControl(private val activity: Activity, private val prefs: Prefs, private val root: View) {

    private val main = Handler(Looper.getMainLooper())
    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val light: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    var dimmed = false
        private set
    private var night: Boolean? = null
    private var lux = -1f                 // smoothed room light
    private var luxBase = -1f             // slow average, to spot the light being switched on
    private var lastNear = false
    private val recent = ArrayDeque<Pair<Long, Float>>()   // last ~3 s of raw light readings
    private var quietUntil = 0L           // ignore light changes caused by our own brightness change
    private var lastMotionWake = 0L
    private var applied = -2f             // last brightness set on the window
    private var running = false

    val hasLight get() = light != null
    val hasProximity get() = proximity != null

    /** The first touch on a very dim screen only wakes it, so nothing is switched by accident. */
    val swallowTouch: Boolean get() = dimmed && idlePercent() <= 15

    private val dimRunnable = Runnable { setDimmed(true, "no touch for ${prefs.dimAfterSec} s") }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0]
            val now = System.currentTimeMillis()
            lux = if (lux < 0) v else lux * 0.6f + v * 0.4f
            // Movement nearby: a shadow (or a reflection) changes the light quickly. Compare with
            // the average of the last 3 s; small changes in a dark room are only sensor noise.
            if (prefs.wakeOnMotion && now > quietUntil && recent.size >= 4) {
                val avg = recent.map { it.second }.average().toFloat()
                val diff = abs(v - avg)
                if (diff >= 3f && diff >= avg * prefs.motionPercent / 100f && now - lastMotionWake > 1500) {
                    lastMotionWake = now
                    wake("movement nearby (light ${avg.toInt()} → ${v.toInt()} lux)")
                }
            }
            recent.addLast(now to v)
            while (recent.isNotEmpty() && now - recent.first().first > 3000) recent.removeFirst()
            if (luxBase < 0) luxBase = v
            // The room light going on: much brighter than a moment ago.
            if (prefs.wakeOnLight && v > luxBase * 3 && v - luxBase > 15) {
                wake("room light on (${luxBase.toInt()} → ${v.toInt()} lux)")
                luxBase = v
            } else {
                luxBase = luxBase * 0.9f + v * 0.1f
            }
            if (!dimmed && isNight() == false && prefs.dayLevel == -1) apply()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < (proximity?.maximumRange ?: 5f) && e.values[0] < 5f
            if (near && !lastNear && prefs.wakeOnProximity) wake("hand near the phone")
            lastNear = near
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        running = true
        if (light != null && (prefs.wakeOnLight || prefs.wakeOnMotion || prefs.dayLevel == -1))
            sensors.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL)
        if (proximity != null && prefs.wakeOnProximity)
            sensors.registerListener(proximityListener, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        night = null
        dimmed = false
        applied = -2f
        apply()
        scheduleDim()
    }

    fun stop() {
        running = false
        main.removeCallbacks(dimRunnable)
        sensors.unregisterListener(lightListener)
        sensors.unregisterListener(proximityListener)
        lux = -1f; luxBase = -1f; lastNear = false; recent.clear()
    }

    /** A touch: wake if dimmed, and start the dim timer again. */
    fun touched() {
        if (dimmed) setDimmed(false, "touch") else scheduleDim()
    }

    fun wake(reason: String) {
        if (!running) return
        if (dimmed) setDimmed(false, reason) else scheduleDim()
    }

    /** Called every minute: switch between day and night. */
    fun tick() { if (running) apply() }

    private fun setDimmed(d: Boolean, reason: String) {
        if (d != dimmed) DebugLog.add("SCRN", (if (d) "dimmed" else "woken") + ": $reason")
        dimmed = d
        apply()
        if (!d) scheduleDim()
    }

    private fun scheduleDim() {
        main.removeCallbacks(dimRunnable)
        val s = prefs.dimAfterSec
        if (s > 0) main.postDelayed(dimRunnable, s * 1000L)
    }

    fun isNight(): Boolean {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val start = prefs.dayStart
        val end = prefs.dayEnd
        val day = if (start <= end) m in start until end else m >= start || m < end
        return !day
    }

    /** Daytime level in use, from the room light: dark room 8 %, 10 lux ~40 %, 100 lux ~70 %, 1000+ lux 100 %. */
    private fun autoPercent(): Int {
        if (lux < 0) return 60
        val f = (log10(lux + 1) / 3f).coerceIn(0f, 1f)
        return (8 + 92 * f).toInt()
    }

    private fun idlePercent(): Int = if (isNight()) prefs.dimPercent else prefs.dayIdlePercent

    private fun apply() {
        val n = isNight()
        if (n != night) {
            if (night != null) DebugLog.add("SCRN", if (n) "night started" else "day started")
            night = n
        }
        // Brightness as a fraction, or -1 = leave it to the phone.
        val level: Float = when {
            n && dimmed -> prefs.dimPercent / 100f
            n -> prefs.nightWakePercent / 100f
            dimmed -> {
                val idle = prefs.dayIdlePercent
                // In a dark room automatic may be lower than the idle level: never brighten when dimming.
                (if (prefs.dayLevel == -1 && light != null) minOf(idle, autoPercent()) else idle) / 100f
            }
            prefs.dayLevel == 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            prefs.dayLevel == -1 && light != null -> autoPercent() / 100f
            prefs.dayLevel == -1 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> prefs.dayLevel / 100f
        }
        // Avoid flicker: only change for a step of 3 % or more (or a switch to/from "phone").
        if (applied == -2f || (level < 0) != (applied < 0) || abs(level - applied) >= 0.03f) {
            val lp = activity.window.attributes
            lp.screenBrightness = level
            activity.window.attributes = lp
            applied = level
            // The screen's own light reaches the light sensor: don't take that for movement.
            quietUntil = System.currentTimeMillis() + 2500
            recent.clear()
        }
        // At night darken the tiles too, so the screen doesn't light up the room.
        root.alpha = when {
            n && dimmed -> 0.35f
            n -> 0.8f
            dimmed -> 0.7f
            else -> 1f
        }
    }

    fun describe(): String = buildString {
        append(if (isNight()) "night" else "day")
        append(if (dimmed) ", idle" else ", in use")
        if (lux >= 0) append(", room ${lux.toInt()} lux")
    }
}
