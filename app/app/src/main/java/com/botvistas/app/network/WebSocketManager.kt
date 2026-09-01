package com.botvistas.app.network

import android.content.Context
import android.util.Log
import com.botvistas.app.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null

    var onTaskStartReceived: ((platform: String, targetProfile: String, durationSeconds: Int) -> Unit)? = null
    var onTaskStopReceived: (() -> Unit)? = null

    fun connect(serverAddress: String, customDeviceId: String? = null) {
        val trimmedAddress = serverAddress.trim()
        if (trimmedAddress.isEmpty()) {
            _connectionState.value = ConnectionState.Error("Ingresa la dirección IP del servidor")
            return
        }

        disconnect()
        _connectionState.value = ConnectionState.Connecting

        val deviceId = if (!customDeviceId.isNullOrBlank()) customDeviceId.trim() else DeviceUtils.getDeviceId(context)
        val formattedUrl = buildWebSocketUrl(trimmedAddress, deviceId)

        val okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
        client = okHttpClient

        val request = Request.Builder()
            .url(formattedUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket conectado con éxito a $formattedUrl")
                _connectionState.value = ConnectionState.Connected
                sendRegistration(deviceId)
                startHeartbeat(deviceId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Mensaje recibido del servidor: $text")
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrándose: $reason ($code)")
                stopHeartbeat()
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrado: $reason ($code)")
                stopHeartbeat()
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en WebSocket: ${t.message}", t)
                stopHeartbeat()
                _connectionState.value = ConnectionState.Error(t.localizedMessage ?: "Error de conexión")
            }
        })
    }

    fun disconnect() {
        stopHeartbeat()
        try {
            webSocket?.close(1000, "Desconectado por el usuario")
            webSocket = null
            client?.dispatcher?.executorService?.shutdown()
            client = null
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar", e)
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun buildWebSocketUrl(address: String, deviceId: String): String {
        val clean = address
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .trimEnd('/')

        val hostAndPort = if (!clean.contains(":")) {
            "$clean:8000"
        } else {
            clean
        }

        return "ws://$hostAndPort/ws/$deviceId"
    }

    private fun sendRegistration(deviceId: String) {
        val payload = JSONObject().apply {
            put("name", DeviceUtils.getDeviceName())
            put("ip_address", DeviceUtils.getLocalIpAddress())
            put("battery_level", DeviceUtils.getBatteryPercentage(context))
            put("app_version", "1.0.0")
        }
        val msg = JSONObject().apply {
            put("type", "REGISTER")
            put("device_id", deviceId)
            put("payload", payload)
        }
        sendMessage(msg.toString())
    }

    private fun startHeartbeat(deviceId: String) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val payload = JSONObject().apply {
                    put("battery_level", DeviceUtils.getBatteryPercentage(context))
                }
                val msg = JSONObject().apply {
                    put("type", "HEARTBEAT")
                    put("device_id", deviceId)
                    put("payload", payload)
                }
                sendMessage(msg.toString())
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            val type = json.optString("type", "").uppercase()
            val payload = json.optJSONObject("payload") ?: JSONObject()

            when (type) {
                "TASK_START" -> {
                    val platform = payload.optString("platform", "youtube")
                    val profile = payload.optString("target_profile", "")
                    val duration = payload.optInt("duration_per_video_seconds", 300)
                    scope.launch(Dispatchers.Main) {
                        onTaskStartReceived?.invoke(platform, profile, duration)
                    }
                }
                "TASK_STOP" -> {
                    scope.launch(Dispatchers.Main) {
                        onTaskStopReceived?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando mensaje JSON: $jsonText", e)
        }
    }

    fun sendStatusUpdate(deviceId: String, status: String, taskId: String? = null, error: String? = null) {
        val payload = JSONObject().apply {
            put("status", status)
            if (taskId != null) put("task_id", taskId)
            if (error != null) put("error_message", error)
        }
        val msg = JSONObject().apply {
            put("type", "TASK_STATUS")
            put("device_id", deviceId)
            put("payload", payload)
        }
        sendMessage(msg.toString())
    }

    fun sendLog(deviceId: String, message: String) {
        val payload = JSONObject().apply {
            put("message", message)
        }
        val msg = JSONObject().apply {
            put("type", "LOG")
            put("device_id", deviceId)
            put("payload", payload)
        }
        sendMessage(msg.toString())
    }

    private fun sendMessage(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    companion object {
        private const val TAG = "WebSocketManager"
    }
}
