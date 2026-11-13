package com.manytwo.hakeypad

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One Home Assistant entity as the keypad needs it. */
data class EntityState(
    val entityId: String,
    val state: String,
    val name: String,
    val unit: String?,
    val deviceClass: String?,
) {
    val domain: String get() = entityId.substringBefore('.')
    val isOn: Boolean get() = state == "on" || state == "open" || state == "playing" ||
        // climate (heaters, air con): any mode other than off counts as on
        (domain == "climate" && state in setOf("heat", "cool", "heat_cool", "auto", "dry", "fan_only"))
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"

    companion object {
        fun from(o: JSONObject): EntityState {
            val a = o.optJSONObject("attributes") ?: JSONObject()
            val id = o.getString("entity_id")
            return EntityState(
                entityId = id,
                state = o.optString("state", "unknown"),
                name = a.optString("friendly_name", id),
                unit = a.optString("unit_of_measurement").ifBlank { null },
                deviceClass = a.optString("device_class").ifBlank { null },
            )
        }
    }
}

/**
 * Talks to Home Assistant over its WebSocket API (/api/websocket):
 * logs in with a long-lived token, loads all states, then follows state_changed events,
 * so the buttons always show the real state even when a device is switched elsewhere.
 * Reconnects on its own. All callbacks run on the main thread.
 */
class HaClient(
    private val serverUrl: String,
    private val token: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnection(status: Status, detail: String = "")
        fun onAllStates(states: Map<String, EntityState>)
        fun onStateChanged(state: EntityState)
        /** Home Assistant did not confirm a press within PRESS_GIVE_UP_MS. */
        fun onPressFailed(entityId: String) {}
    }

    /** A press waiting for Home Assistant to confirm it. */
    private class Press(val entityId: String, val body: JSONObject, val repeatable: Boolean, val since: Long) {
        var msgId = -1
        var sentAt = 0L
    }

    enum class Status { CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

    private val main = Handler(Looper.getMainLooper())
    companion object {
        private const val PRESS_ACK_MS = 2_500L       // no answer by then: the socket is probably dead
        private const val PRESS_GIVE_UP_MS = 60_000L
    }
    private val presses = LinkedHashMap<String, Press>()   // entity id → press not yet confirmed
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var nextId = 1
    private var getStatesId = -1
    private var stopped = false
    private var retryDelayMs = 2000L
    private var authOk = false
    private var connectStarted = 0L

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
    }

    /**
     * Switch an entity, or run a script/scene/button.
     *
     * [wantOn] is the state the user asked for. Switches are sent as turn_on/turn_off, not
     * "toggle", so a press can safely be sent again if the first try got lost: sending
     * "turn on" twice still means on. Each press waits for Home Assistant's answer; if none
     * comes within 2.5 s the connection is rebuilt and the press sent again, for up to 60 s.
     * A press made while offline waits and goes out as soon as the connection is back.
     */
    fun press(entityId: String, wantOn: Boolean) {
        val domain = entityId.substringBefore('.')
        val (svcDomain, service, repeatable) = when (domain) {
            "script", "scene" -> Triple(domain, "turn_on", false)
            "button", "input_button" -> Triple(domain, "press", false)
            "lock" -> Triple("lock", if (wantOn) "lock" else "unlock", true)
            "cover" -> Triple("cover", if (wantOn) "open_cover" else "close_cover", true)
            else -> Triple("homeassistant", if (wantOn) "turn_on" else "turn_off", true)
        }
        val body = JSONObject()
            .put("type", "call_service")
            .put("domain", svcDomain)
            .put("service", service)
            .put("target", JSONObject().put("entity_id", entityId))
        val p = Press(entityId, body, repeatable, System.currentTimeMillis())
        presses[entityId] = p          // a newer press of the same tile replaces an older one
        DebugLog.add("PRESS", "$entityId → $service" + if (authOk) "" else " (offline: held until connected)")
        // Not logged in: send it when we are. Only start a new connection if none is under way.
        if (authOk) sendPress(p)
        else if (ws == null || System.currentTimeMillis() - connectStarted > 10_000) reconnectNow("press while offline")
        main.removeCallbacks(pressCheck)
        main.postDelayed(pressCheck, 500)
    }

    /** Call a service now (no retry). False if not connected. */
    fun callService(domain: String, service: String, entityId: String, data: JSONObject): Boolean {
        if (!authOk) return false
        send(JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
            .put("service_data", data).put("target", JSONObject().put("entity_id", entityId)))
        return true
    }

    private fun sendPress(p: Press) {
        val o = JSONObject(p.body.toString())
        if (p.sentAt > 0) DebugLog.add("PRESS", "${p.entityId} sent again (no answer yet)")
        p.msgId = nextId
        p.sentAt = System.currentTimeMillis()
        send(o)
    }

    private val pressCheck = object : Runnable {
        override fun run() {
            if (presses.isEmpty()) return
            val now = System.currentTimeMillis()
            var stale = false
            for (p in presses.values.toList()) {
                when {
                    now - p.since > PRESS_GIVE_UP_MS -> {
                        presses.remove(p.entityId)
                        DebugLog.add("PRESS", "${p.entityId} GAVE UP after ${(now - p.since) / 1000} s: not switched")
                        listener.onPressFailed(p.entityId)
                    }
                    authOk && p.sentAt > 0 && now - p.sentAt > PRESS_ACK_MS -> stale = true
                }
            }
            // A press got no answer: the socket is dead without knowing it. Start again.
            if (stale) reconnectNow("a press got no answer in ${PRESS_ACK_MS} ms")
            if (presses.isNotEmpty()) main.postDelayed(this, 500)
        }
    }

    /** After logging in again: send the presses that are still waiting. */
    private fun resendPresses() {
        for (p in presses.values.toList()) {
            if (p.sentAt == 0L || p.repeatable) sendPress(p)
            else {   // may have run already: don't run it twice
                presses.remove(p.entityId)
                DebugLog.add("PRESS", "${p.entityId} not repeated (a script/scene/button may already have run)")
                listener.onPressFailed(p.entityId)
            }
        }
    }

    private fun hostOnly(): String = try { android.net.Uri.parse(serverUrl).host ?: "?" } catch (_: Exception) { "?" }

    private fun wsUrl(): String {
        val base = serverUrl.trim().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$ws/api/websocket"
    }

    private fun connect() {
        if (stopped) return
        authOk = false
        connectStarted = System.currentTimeMillis()
        DebugLog.add("HA", "connecting to ${hostOnly()}")
        post { listener.onConnection(Status.CONNECTING) }
        val req = try {
            Request.Builder().url(wsUrl()).build()
        } catch (e: IllegalArgumentException) {
            post { listener.onConnection(Status.DISCONNECTED, "Bad server address") }
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != ws) return
                DebugLog.add("HA", "connection FAILED: ${t.javaClass.simpleName}: ${t.message}" +
                    (response?.let { " (HTTP ${it.code})" } ?: "") + "; next try in ${retryDelayMs / 1000} s")
                post { listener.onConnection(Status.DISCONNECTED, t.message ?: t.javaClass.simpleName) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                DebugLog.add("HA", "connection closed by server: $code $reason")
                post { listener.onConnection(Status.DISCONNECTED, reason) }
                scheduleReconnect()
            }
        })
    }

    private val reconnect = Runnable { connect() }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    /**
     * The phone has (re)joined a network: connect again at once instead of waiting out the
     * retry delay, and drop the old socket, which may be dead without knowing it yet.
     */
    fun reconnectNow(why: String = "") {
        if (stopped) return
        DebugLog.add("HA", "reconnecting now${if (why.isNotEmpty()) ": $why" else ""}")
        main.removeCallbacks(reconnect)
        retryDelayMs = 2000L
        val old = ws
        ws = null            // its late failure/close callbacks are then ignored
        old?.cancel()
        connect()
    }

    private fun send(o: JSONObject) {
        if (o.optString("type") != "auth") o.put("id", nextId++)
        ws?.send(o.toString())
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "auth_required" -> send(JSONObject().put("type", "auth").put("access_token", token))
            "auth_invalid" -> {
                DebugLog.add("HA", "token REJECTED: ${msg.optString("message")}")
                stopped = true   // a wrong token won't fix itself; wait for new settings
                post { listener.onConnection(Status.AUTH_FAILED, msg.optString("message")) }
                ws?.close(1000, "auth invalid")
            }
            "auth_ok" -> {
                authOk = true
                DebugLog.add("HA", "connected, logged in after ${System.currentTimeMillis() - connectStarted} ms (HA ${msg.optString("ha_version")})")
                retryDelayMs = 2000L
                post { listener.onConnection(Status.CONNECTED) }
                getStatesId = nextId
                send(JSONObject().put("type", "get_states"))
                send(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"))
                post { resendPresses() }
            }
            "result" -> if (msg.optInt("id") != getStatesId) {
                val id = msg.optInt("id")
                val okRes = msg.optBoolean("success")
                val err = msg.optJSONObject("error")?.optString("message")
                post {
                    presses.values.firstOrNull { it.msgId == id }?.let {
                        presses.remove(it.entityId)
                        val ms = System.currentTimeMillis() - it.sentAt
                        val total = System.currentTimeMillis() - it.since
                        DebugLog.add("PRESS", "${it.entityId} " + (if (okRes) "confirmed by HA in $ms ms" else "REFUSED by HA: $err") +
                            if (total - ms > 200) " ($total ms after the press)" else "")
                    }
                }
            } else if (msg.optBoolean("success")) {
                val arr = msg.optJSONArray("result") ?: JSONArray()
                val map = HashMap<String, EntityState>()
                for (i in 0 until arr.length()) {
                    val s = EntityState.from(arr.getJSONObject(i))
                    map[s.entityId] = s
                }
                post { listener.onAllStates(map) }
            }
            "event" -> {
                val data = msg.optJSONObject("event")?.optJSONObject("data") ?: return
                val ns = data.optJSONObject("new_state") ?: return
                val s = EntityState.from(ns)
                post { listener.onStateChanged(s) }
            }
        }
    }

    private fun post(r: () -> Unit) = main.post(r)
}
