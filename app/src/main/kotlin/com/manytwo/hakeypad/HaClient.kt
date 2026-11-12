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
    val isOn: Boolean get() = state == "on" || state == "open" || state == "playing"
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
    }

    enum class Status { CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var nextId = 1
    private var getStatesId = -1
    private var stopped = false
    private var retryDelayMs = 2000L
    private var authOk = false

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

    /** Toggle a switch/light/fan/input_boolean, or run a script/scene. */
    fun press(entityId: String) {
        val domain = entityId.substringBefore('.')
        val (svcDomain, service) = when (domain) {
            "script", "scene" -> domain to "turn_on"
            "button", "input_button" -> domain to "press"
            "lock" -> "lock" to "toggle"
            else -> "homeassistant" to "toggle"
        }
        send(JSONObject()
            .put("type", "call_service")
            .put("domain", svcDomain)
            .put("service", service)
            .put("target", JSONObject().put("entity_id", entityId)))
    }

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
                post { listener.onConnection(Status.DISCONNECTED, t.message ?: t.javaClass.simpleName) }
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != ws) return
                post { listener.onConnection(Status.DISCONNECTED, reason) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        main.postDelayed({ connect() }, delay)
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
                stopped = true   // a wrong token won't fix itself; wait for new settings
                post { listener.onConnection(Status.AUTH_FAILED, msg.optString("message")) }
                ws?.close(1000, "auth invalid")
            }
            "auth_ok" -> {
                authOk = true
                retryDelayMs = 2000L
                post { listener.onConnection(Status.CONNECTED) }
                getStatesId = nextId
                send(JSONObject().put("type", "get_states"))
                send(JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"))
            }
            "result" -> if (msg.optInt("id") == getStatesId && msg.optBoolean("success")) {
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
