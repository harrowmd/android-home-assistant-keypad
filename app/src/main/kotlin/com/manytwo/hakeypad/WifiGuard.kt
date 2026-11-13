@file:Suppress("DEPRECATION")   // the "legacy" WifiManager calls are the only ones that can do this job

package com.manytwo.hakeypad

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Keeps the keypad's Wi-Fi usable.
 *
 * Every 15 s it checks that the phone is on Wi-Fi, how strong the signal is, and that
 * Home Assistant can be reached (a TCP connect to the server's port). In a house with
 * several access points that share one network name, Android often stays on a far-away
 * AP, or keeps trying one that refuses it. When the link stays bad, the guard picks a
 * good AP itself (the preferred list first, else the strongest, 2.4 GHz before 5 GHz) and
 * pins the saved network to that AP's BSSID, then reconnects. Every third try it drops the
 * link and starts again.
 *
 * Since Android 10 only a "device owner" app may change Wi-Fi like this, so the actions
 * need the keypad to be set as device owner (see README). Without that the guard only
 * watches and logs.
 */
class WifiGuard(context: Context, private val prefs: Prefs) {

    companion object {
        private const val TAG = "HAKeypad.Wifi"
        private const val TICK_MS = 15_000L
        private const val DROPPED_AFTER_MS = 15_000L     // not on Wi-Fi at all
        private const val DROPPED_GAP_MS = 30_000L       // between two repairs while not on Wi-Fi
        private const val URGENT_AFTER_MS = 45_000L      // connected, but HA unreachable
        private const val SOON_AFTER_MS = 120_000L       // weak signal / not a preferred AP
        private const val MIN_GAP_MS = 90_000L           // between two repairs
        private const val PENALTY_MS = 10 * 60_000L      // avoid an AP that just failed
        private const val DROP_PENALTY_MS = 2 * 60_000L  // avoid the AP that just vanished (reboot)
        private val MAC = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

        fun admin(ctx: Context) = ComponentName(ctx, KeypadAdmin::class.java)
        fun isDeviceOwner(ctx: Context): Boolean =
            ctx.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(ctx.packageName)

        /** "name  BSSID" per line → list of (bssid, name), in the user's order. */
        fun parseAps(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
            val m = MAC.find(line) ?: return@mapNotNull null
            val name = line.replace(m.value, " ").trim().ifBlank { m.value.lowercase() }
            m.value.lowercase() to name
        }
    }

    /** One line about the current link, e.g. "Wi-Fi: loft −64 dBm · Home Assistant OK". */
    var status: String = "Wi-Fi: checking…"
        private set
    var onStatus: ((String) -> Unit)? = null

    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    @Volatile private var probing = false
    val log = WifiLog(ctx)

    private var running = false
    private val probes = ArrayDeque<Boolean>()       // last few "HA reachable?" results
    private var badSince = 0L
    private var goodSince = 0L
    private var lastRepair = 0L
    private var repairs = 0
    private var lastBssid: String? = "?"
    private var lastGoodBssid: String? = null         // the AP the phone was last connected to
    private var wasDropped = false
    private var lastProblem = ""
    private var lastHaDown = false
    private val penalty = HashMap<String, Long>()    // bssid → avoid until

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            check()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (isDeviceOwner(ctx)) grantLocation()
        log.add("Guard started. Device owner: ${if (isDeviceOwner(ctx)) "yes" else "no (watch only)"}")
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    // ---- watching ----

    private fun check() {
        val info = wifi.connectionInfo
        val connected = wifi.isWifiEnabled && info != null &&
            info.supplicantState == SupplicantState.COMPLETED && info.networkId != -1
        val bssid = info?.bssid?.lowercase()?.takeIf { connected && it != "02:00:00:00:00:00" }
        val rssi = if (connected) info.rssi else -127
        if (connected && prefs.wifiSsid.isBlank()) {
            info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }?.let {
                prefs.wifiSsid = it
                log.add("Looking after Wi-Fi network \"$it\"")
            }
        }
        if (bssid != lastBssid) {
            log.add(if (connected) "On ${name(bssid)} at $rssi dBm, ${info.frequency} MHz" else "Not connected to Wi-Fi")
            lastBssid = bssid
            if (bssid != null) lastGoodBssid = bssid
        }
        // No Wi-Fi: nothing to test, decide now (never wait for a network check here).
        if (!connected) { record(null, false, -1, null, if (wifi.isWifiEnabled) "not on Wi-Fi" else "Wi-Fi switched off"); evaluate(false, null, rssi, false, null); return }
        // A check can hang (a DNS lookup has no timeout). Don't queue behind it: count it as a failure.
        if (probing) { record(bssid, false, -1, null, "previous check still hanging"); evaluate(true, bssid, rssi, false, null); return }
        probing = true
        val target = haHostPort()
        val gw = gateway()
        io.execute {
            val ms = if (target != null) probe(target.first, target.second) else -1L
            val ok = ms >= 0
            // If HA does not answer, ask the router too: if it answers, the Wi-Fi is fine and
            // HA itself is down (restart, update), so moving the phone would not help.
            val gwOk = if (!ok && gw != null) ping(gw) else null
            main.post {
                probing = false
                record(bssid, ok, ms, gwOk, if (target == null) "no server set" else lastProbeError)
                evaluate(connected, bssid, rssi, ok, gwOk)
            }
        }
    }

    /** TCP connect to Home Assistant: the time it took in ms, or -1 with the reason in [lastProbeError]. */
    private fun probe(host: String, port: Int): Long {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 4000) }
            (System.nanoTime() - t0) / 1_000_000
        } catch (e: Exception) {
            lastProbeError = "${e.javaClass.simpleName}: ${e.message} after ${(System.nanoTime() - t0) / 1_000_000} ms"
            -1
        }
    }
    @Volatile private var lastProbeError = ""

    private fun gateway(): InetAddress? {
        val g = wifi.dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        val b = byteArrayOf((g and 0xff).toByte(), (g shr 8 and 0xff).toByte(), (g shr 16 and 0xff).toByte(), (g shr 24 and 0xff).toByte())
        return InetAddress.getByAddress(b)
    }

    private fun ping(a: InetAddress): Boolean = try { a.isReachable(2000) } catch (e: Exception) { false }

    private fun haHostPort(): Pair<String, Int>? {
        val u = Uri.parse(prefs.serverUrl.ifBlank { return null })
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else if (u.scheme == "http") 80 else 443
        return host to port
    }

    private var gatewayCheckLogged = false

    // ---- debug log: every failed check at once, and a summary line every minute ----
    private var statOk = 0
    private var statFail = 0
    private var statMsSum = 0L
    private var statMsMax = 0L
    private var statSince = 0L

    private fun record(bssid: String?, ok: Boolean, ms: Long, gwOk: Boolean?, why: String) {
        val info = wifi.connectionInfo
        val link = if (bssid != null && info != null)
            "${info.ssid?.trim('"')} ${name(bssid).let { if (it == bssid) bssid else "$it [$bssid]" }} ${info.rssi} dBm ${info.frequency} MHz ${info.linkSpeed} Mbps" else "no AP"
        if (ok) {
            statOk++; statMsSum += ms; if (ms > statMsMax) statMsMax = ms
        } else {
            statFail++
            val router = when (gwOk) { true -> "router answers"; false -> "router does NOT answer"; null -> "router not checked" }
            DebugLog.add("HA", "check FAILED on $link: $why; $router")
        }
        val now = System.currentTimeMillis()
        if (statSince == 0L) statSince = now
        if (now - statSince >= 60_000L) {
            val n = statOk + statFail
            val avg = if (statOk > 0) statMsSum / statOk else 0
            DebugLog.add("STAT", "$link · HA $statOk/$n ok" +
                (if (statOk > 0) ", avg $avg ms, max $statMsMax ms" else "") +
                (if (statFail > 0) " · LOST $statFail" else "") +
                (if (!prefs.wifiGuard) " · guard off" else if (!isDeviceOwner(ctx)) " · watch only" else ""))
            statOk = 0; statFail = 0; statMsSum = 0; statMsMax = 0; statSince = now
        }
    }

    private fun evaluate(connected: Boolean, bssid: String?, rssi: Int, haOk: Boolean, gwOk: Boolean?) {
        if (!running) return
        val now = System.currentTimeMillis()
        val haDown = !haOk && gwOk == true
        if (gwOk != null && !gatewayCheckLogged) {
            log.add("Router check: ${if (gwOk) "router answers" else "router does not answer"}")
            gatewayCheckLogged = true
        }
        // Only count failures that point at the Wi-Fi, not at Home Assistant itself.
        probes.addLast(haOk || haDown)
        while (probes.size > 4) probes.removeFirst()
        val fails = probes.count { !it }

        val aps = parseAps(prefs.wifiAps).map { it.first }
        val minRssi = prefs.wifiMinRssi
        var urgent = false
        var dropped = false      // not on Wi-Fi at all: act fast
        var linkDead = false     // connected, but Home Assistant can't be reached through this AP
        val problems = mutableListOf<String>()
        if (!wifi.isWifiEnabled) { problems += "Wi-Fi is switched off"; urgent = true; dropped = true }
        else if (!connected) { problems += "not connected"; urgent = true; dropped = true }
        else {
            if (probes.size >= 3 && fails >= 2) {
                problems += "Home Assistant unreachable ($fails of ${probes.size} checks)"; urgent = true; linkDead = true
            }
            // Only complain about the AP if there is a better one to move to.
            val better = betterAp(bssid, rssi, aps, minRssi)
            if (better != null) {
                problems += if (rssi < minRssi) "weak signal $rssi dBm, ${name(better.BSSID)} is ${better.level} dBm"
                    else "on ${name(bssid)}, preferred ${name(better.BSSID)} is ${better.level} dBm"
            }
        }

        status = buildString {
            append("Wi-Fi: ")
            append(if (connected) "${name(bssid)} $rssi dBm" else if (wifi.isWifiEnabled) "not connected" else "switched off – please switch it on")
            if (connected) append(when {
                haOk -> " · Home Assistant OK"
                haDown -> " · Wi-Fi OK, Home Assistant not answering"
                else -> " · Home Assistant not reachable"
            })
            if (!prefs.wifiGuard) append(" · guard off")
            else if (!isDeviceOwner(ctx)) append(" · watch only")
        }
        onStatus?.invoke(status)

        if (!prefs.wifiGuard) return
        if (haDown != lastHaDown) {
            log.add(if (haDown) "Home Assistant not answering, but the router does: leaving the Wi-Fi alone"
                    else "Home Assistant answers again")
            lastHaDown = haDown
        }
        if (problems.isEmpty()) {
            badSince = 0L
            wasDropped = false
            lastProblem = ""
            if (goodSince == 0L) goodSince = now
            if (repairs > 0 && now - goodSince > 3 * 60_000L) {
                log.add("Link good for 3 minutes")
                repairs = 0
            }
            return
        }
        goodSince = 0L
        val p = problems.joinToString("; ")
        // Time the 15 s from the moment the Wi-Fi dropped, not from an earlier, milder problem.
        if (badSince == 0L || (dropped && !wasDropped)) badSince = now
        wasDropped = dropped
        // Log a problem once, not every 15 s because a dBm figure changed.
        val key = p.replace(Regex("-?\\d+"), "#")
        if (key != lastProblem) { log.add("Problem: $p"); lastProblem = key }
        val wait = if (dropped) DROPPED_AFTER_MS else if (urgent) URGENT_AFTER_MS else SOON_AFTER_MS
        val gap = if (dropped) DROPPED_GAP_MS else MIN_GAP_MS
        if (now - badSince >= wait && now - lastRepair >= gap) repair(p, bssid, linkDead, dropped)
    }

    /** A visible AP of our network that is clearly better than the current one, or null. */
    private fun betterAp(bssid: String?, rssi: Int, aps: List<String>, minRssi: Int): ScanResult? {
        val now = System.currentTimeMillis()
        val scan = ourScan().filter { it.BSSID != bssid && (penalty[it.BSSID] ?: 0) < now }
        if (aps.isNotEmpty()) {
            val myRank = aps.indexOf(bssid).let { if (it < 0) Int.MAX_VALUE else it }
            // A preferred AP ranked above the current one, with a usable signal.
            aps.forEachIndexed { i, a ->
                if (i >= myRank) return@forEachIndexed
                scan.firstOrNull { it.BSSID == a && it.level >= minRssi }?.let { return it }
            }
        }
        if (rssi < minRssi) {
            // Any AP of the network will do here, not only the preferred ones.
            val best = bestOf(scan.filter { it.level >= rssi + 10 })
            if (best != null) return best
        }
        return null
    }

    /**
     * The AP most likely to give a steady connection: the strongest, with 2.4 GHz counted
     * 10 dB better than 5 GHz (it reaches further and copes better with walls; speed does
     * not matter for a keypad). Works with any access points, nothing is set in advance.
     */
    private fun bestOf(list: List<ScanResult>): ScanResult? =
        list.maxByOrNull { it.level + if (it.frequency < 3000) 10 else 0 }

    // ---- repairing ----

    private fun repair(problem: String, bssid: String?, linkDead: Boolean, dropped: Boolean = false) {
        val now = System.currentTimeMillis()
        lastRepair = now
        badSince = now          // give the repair time to work before the next one
        probes.clear()
        if (!isDeviceOwner(ctx)) {
            log.add("Would reconnect now ($problem), but HA Keypad is not device owner")
            return
        }
        repairs++
        // Avoid an AP for a while only if it actually failed, not when moving to a preferred one.
        if (linkDead && bssid != null) penalty[bssid] = now + PENALTY_MS
        // The AP the phone just fell off is probably rebooting or broken, but Android's scan list
        // can still show it for a while. Skip it for 2 minutes if anything else is in reach.
        if (dropped) lastGoodBssid?.let { if ((penalty[it] ?: 0) < now) penalty[it] = now + DROP_PENALTY_MS }
        if (!wifi.isWifiEnabled) {
            // Android 10 does not let any normal app (not even a device owner) switch Wi-Fi on.
            log.add("Wi-Fi is switched off; it has to be switched on by hand")
            return
        }
        if (repairs % 3 == 0) {
            // Every third repair: drop the link completely and start again.
            log.add("Repair $repairs: disconnecting, then reconnecting from scratch")
            wifi.disconnect()
            main.postDelayed({ wifi.startScan(); main.postDelayed({ connectBest() }, 6000) }, 3000)
            return
        }
        log.add("Repair $repairs: scanning for a better AP")
        wifi.startScan()
        main.postDelayed({ connectBest() }, 5000)
    }

    private fun ourScan(): List<ScanResult> {
        val ssid = prefs.wifiSsid.ifBlank { return emptyList() }
        return try { wifi.scanResults.filter { it.SSID == ssid } } catch (e: SecurityException) { emptyList() }
    }

    private fun ourConfigs(): List<WifiConfiguration> {
        val ssid = prefs.wifiSsid.ifBlank { return emptyList() }
        return try {
            wifi.configuredNetworks.orEmpty().filter { it.SSID == "\"$ssid\"" }
        } catch (e: SecurityException) {
            log.add("Can't read saved networks: ${e.message}")
            emptyList()
        }
    }

    /** Choose the AP to use, pin the saved network(s) to it and connect. */
    private fun connectBest(retry: Boolean = true) {
        if (!running) return
        val configs = ourConfigs()
        if (configs.isEmpty()) { log.add("No saved Wi-Fi network \"${prefs.wifiSsid}\""); return }
        val now = System.currentTimeMillis()
        val minRssi = prefs.wifiMinRssi
        val aps = parseAps(prefs.wifiAps).map { it.first }
        val scan = ourScan()
        val usable = scan.filter { (penalty[it.BSSID] ?: 0) < now }
        // 1. the first preferred AP with a usable signal
        // 2. any AP of the network with a usable signal (2.4 GHz first: it reaches further)
        // 3. the strongest preferred AP, then the strongest AP at all
        val pick =
            aps.firstNotNullOfOrNull { a -> usable.firstOrNull { it.BSSID == a && it.level >= minRssi } }
                ?: bestOf(usable.filter { it.level >= minRssi })
                ?: usable.filter { it.BSSID in aps }.maxByOrNull { it.level }
                ?: bestOf(usable)
                ?: bestOf(scan)
        if (pick == null) {
            if (retry) {   // the scan may not be ready yet
                wifi.startScan()
                main.postDelayed({ connectBest(retry = false) }, 10_000)
                return
            }
            log.add("No AP of \"${prefs.wifiSsid}\" visible; asking Android to reconnect")
            configs.firstOrNull()?.let { wifi.enableNetwork(it.networkId, true) }
            wifi.reconnect()
            return
        }
        pin(configs, pick.BSSID)
        // Use the saved profile that matches the AP's security (WPA3 vs WPA2).
        val sae = pick.capabilities.contains("SAE") && !pick.capabilities.contains("PSK")
        val cfg = configs.firstOrNull { it.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) != sae }
            ?: configs.first()
        log.add("Connecting to ${name(pick.BSSID)} (${pick.level} dBm, ${pick.frequency} MHz)")
        wifi.disconnect()
        wifi.enableNetwork(cfg.networkId, true)
        wifi.reconnect()
    }

    private fun pin(configs: List<WifiConfiguration>, bssid: String) {
        var ok = true
        for (c in configs) {
            c.BSSID = bssid
            if (wifi.updateNetwork(c) == -1) ok = false
        }
        if (ok) prefs.wifiPinnedTo = bssid
        else log.add("Android refused to pin the network to $bssid")
    }

    private fun grantLocation() {
        // Android only shows BSSIDs and scan results to apps with location permission.
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                dpm.setPermissionGrantState(admin(ctx), ctx.packageName, p,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    /** Scan results for our network, strongest first, for the "Nearby APs" list. */
    fun nearby(): List<ScanResult> = ourScan().sortedByDescending { it.level }

    fun name(bssid: String?): String {
        if (bssid == null) return "?"
        return parseAps(prefs.wifiAps).firstOrNull { it.first == bssid }?.second ?: bssid
    }
}

/** A small rolling log in the app's own folder; also copied to logcat (tag HAKeypad.Wifi). */
class WifiLog(context: Context) {
    private val file = File(context.filesDir, "wifi-log.txt")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        DebugLog.add("WIFI", msg)
        try {
            file.appendText("${fmt.format(Date())}  $msg\n")
            if (file.length() > 64_000) file.writeText(file.readLines().takeLast(300).joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun lines(): List<String> = try { file.readLines() } catch (_: Exception) { emptyList() }
}
