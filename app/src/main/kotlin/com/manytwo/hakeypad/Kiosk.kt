package com.manytwo.hakeypad

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Kiosk mode (Android "lock task"): the keypad stays on screen; Home, Recents and the status
 * bar do nothing, the power button still works. Only for a device-owner keypad, and only
 * when switched on in Settings. Settings (hold the title, PIN) still opens, and turning
 * kiosk off there frees the phone again.
 */
object Kiosk {
    private var lastWall: Boolean? = null

    /** Wall keypad = opens at start-up or kiosk. Then it must never sit behind a lock screen. */
    fun isWall(ctx: Context, prefs: Prefs) = WifiGuard.isDeviceOwner(ctx) && (prefs.autoStart || prefs.kiosk)

    /**
     * Call from onCreate (before the window shows). A wall keypad shows over the lock screen and
     * switches the screen on. Learned the hard way: after a reboot the keypad opened behind the
     * swipe lock screen, the screen went off, and Android then treats the app as in the
     * background: no Wi-Fi details, no connection to Home Assistant, until someone woke it.
     */
    fun showOverLockScreen(a: Activity, prefs: Prefs) {
        val wall = isWall(a, prefs)
        a.setShowWhenLocked(wall)
        a.setTurnScreenOn(wall)
    }

    fun apply(a: Activity, prefs: Prefs) {
        if (!WifiGuard.isDeviceOwner(a)) return
        val dpm = a.getSystemService(DevicePolicyManager::class.java)
        val admin = WifiGuard.admin(a)
        showOverLockScreen(a, prefs)
        val wall = isWall(a, prefs)
        try {
            // No swipe lock screen on a wall keypad (only possible when there is no PIN/pattern),
            // and the screen stays on while charging even if another app is in front.
            if (dpm.setKeyguardDisabled(admin, wall) && wall != lastWall) DebugLog.add("APP", "swipe lock screen ${if (wall) "off" else "back on"}")
            dpm.setGlobalSetting(admin, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                if (wall) "7" else "0")
            if (wall && a.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false)
                DebugLog.add("APP", "screen was off")
        } catch (e: Exception) {
            DebugLog.add("APP", "wall keypad setup failed: $e")
        }
        lastWall = wall
        val on = isOn(a)
        try {
            if (prefs.kiosk) {
                dpm.setLockTaskPackages(admin, arrayOf(a.packageName))
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
                if (!on) { a.startLockTask(); DebugLog.add("APP", "kiosk on") }
            } else if (on) {
                a.stopLockTask(); DebugLog.add("APP", "kiosk off")
            }
        } catch (e: Exception) {
            DebugLog.add("APP", "kiosk change failed: $e")
        }
    }

    fun isOn(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** Leave kiosk mode now (before opening Android's own settings, or removing device owner). */
    fun off(a: Activity) {
        if (isOn(a)) try { a.stopLockTask(); DebugLog.add("APP", "kiosk off") } catch (_: Exception) {}
    }
}

/** Opens the keypad after the phone starts, if switched on in Settings (device owner only). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        DebugLog.init(ctx)
        val prefs = Prefs(ctx)
        val owner = WifiGuard.isDeviceOwner(ctx)
        DebugLog.add("APP", "phone started; auto start ${if (prefs.autoStart) "on" else "off"}, device owner ${if (owner) "yes" else "no"}")
        if (!prefs.autoStart || !owner) return
        // A device-owner app may open an activity from the background (Android 10+).
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
