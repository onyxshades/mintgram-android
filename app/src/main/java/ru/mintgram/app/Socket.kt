package ru.mintgram.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * WebSocket-клиент к /ws. После открытия сам шлёт {type:"auth"}.
 * Переподключается с нарастающей паузой, пока connect() не отменили через close().
 */
class Socket(private val api: Api, private val prefs: Prefs, private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 256)
    val events: SharedFlow<JSONObject> = _events

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private var ws: WebSocket? = null
    private var wanted = false
    private var retry = 0
    private var reconnectJob: Job? = null

    fun connect() {
        wanted = true
        open()
    }

    private fun open() {
        val token = prefs.token ?: return
        ws?.cancel()
        val wsUrl = api.base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        val req = Request.Builder().url(wsUrl).build()
        ws = api.client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (_: Exception) { return }
                if (o.optString("type") == "ready") { _ready.value = true; retry = 0 }
                _events.tryEmit(o)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, -1)
        })
    }

    private fun lost(source: WebSocket, code: Int) {
        if (source !== ws) return
        _ready.value = false
        if (code == 4003) {
            // сервер не принял токен — сессия недействительна
            _events.tryEmit(JSONObject().put("type", "_auth_failed"))
            wanted = false
            return
        }
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            retry++
            delay((retry.coerceAtMost(6) * 1500L))
            if (wanted) open()
        }
    }

    fun send(o: JSONObject): Boolean = _ready.value && ws?.send(o.toString()) == true

    /** Вернуть соединение, если оно упало, пока приложение было в фоне. */
    fun ensure() {
        if (wanted && !_ready.value) { retry = 0; open() }
    }

    fun close() {
        wanted = false
        reconnectJob?.cancel()
        ws?.close(1000, null)
        ws = null
        _ready.value = false
    }
}
