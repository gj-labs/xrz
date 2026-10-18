package com.bindlab.shared.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** WS /ws?client=phone|xr with automatic reconnect. */
class WsClient(
    private val baseUrl: () -> String,
    private val clientKind: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = Http.client,
) {
    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ServerMessage> = _messages
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var running = false
    private var backoffMs = 1000L

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        reconnect?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
    }

    /** Drop the socket and reconnect, e.g. after the server URL changed. */
    fun restart() {
        stop(); start()
    }

    fun send(msg: JSONObject): Boolean = socket?.send(msg.toString()) ?: false

    private fun wsUrl(): String {
        val base = baseUrl().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/ws?client=$clientKind"
    }

    private fun connect() {
        val req = try {
            Request.Builder().url(wsUrl()).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad server url ${baseUrl()}", e); scheduleReconnect(); return
        }
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ServerMessage.parse(text)?.let { _messages.tryEmit(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "ws failure: ${t.message}")
                lost(webSocket)
            }
        })
    }

    private fun lost(ws: WebSocket) {
        if (ws !== socket) return
        _connected.value = false
        socket = null
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running) return
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(10_000L)
            if (running) connect()
        }
    }

    companion object {
        private const val TAG = "WsClient"
    }
}
