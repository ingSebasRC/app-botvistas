package com.botvistas.app.automation

import android.content.Context
import android.util.Log
import com.botvistas.app.model.Platform
import com.botvistas.app.network.WebSocketManager
import com.botvistas.app.service.BotAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AutomationController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _automationState = MutableStateFlow<AutomationState>(AutomationState.Idle)
    val automationState = _automationState.asStateFlow()

    private var activeJob: Job? = null

    fun isRunning(): Boolean = activeJob?.isActive == true

    fun start(
        platform: Platform,
        targetProfile: String,
        durationPerVideoSeconds: Int,
        deviceId: String,
        wsManager: WebSocketManager? = null
    ) {
        stop(deviceId, wsManager)

        val service = BotAccessibilityService.instance
        if (service == null) {
            val errorMsg = "No se puede iniciar: El servicio de accesibilidad de BotVistas no está habilitado."
            _automationState.value = AutomationState.Error(errorMsg)
            wsManager?.sendLog(deviceId, "ERROR: $errorMsg")
            wsManager?.sendStatusUpdate(deviceId, "error", error = errorMsg)
            return
        }

        activeJob = scope.launch {
            try {
                val logCallback: (String) -> Unit = { msg ->
                    Log.d(TAG, "[$deviceId] $msg")
                    wsManager?.sendLog(deviceId, msg)
                }

                var playbackReported = false
                val stateCallback: (AutomationState) -> Unit = { state ->
                    _automationState.value = state
                    if (state is AutomationState.Playing && !playbackReported) {
                        playbackReported = true
                        wsManager?.sendStatusUpdate(deviceId, "running")
                    }
                }

                when (platform) {
                    Platform.YOUTUBE -> {
                        val handler = YouTubeAutomationHandler(
                            context = context,
                            serviceProvider = { BotAccessibilityService.instance },
                            onLog = logCallback,
                            onStateChanged = stateCallback
                        )
                        handler.run(targetProfile, durationPerVideoSeconds)
                    }
                    Platform.TIKTOK -> {
                        val handler = TikTokAutomationHandler(
                            context = context,
                            serviceProvider = { BotAccessibilityService.instance },
                            onLog = logCallback,
                            onStateChanged = stateCallback
                        )
                        handler.run(targetProfile, durationPerVideoSeconds)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en el motor de automatización: ${e.message}", e)
                _automationState.value = AutomationState.Error(e.localizedMessage ?: "Error en automatización")
                wsManager?.sendStatusUpdate(deviceId, "error", error = e.localizedMessage)
                wsManager?.sendLog(deviceId, "ERROR CRÍTICO: ${e.localizedMessage}")
            } finally {
                if (_automationState.value !is AutomationState.Error) {
                    _automationState.value = AutomationState.Stopped
                    wsManager?.sendStatusUpdate(deviceId, "stopped")
                }
            }
        }
    }

    fun stop(deviceId: String, wsManager: WebSocketManager? = null) {
        if (activeJob?.isActive == true) {
            activeJob?.cancel()
            activeJob = null
            _automationState.value = AutomationState.Stopped
            wsManager?.sendStatusUpdate(deviceId, "stopped")
            wsManager?.sendLog(deviceId, "Automatización detenida.")
        }
    }

    companion object {
        private const val TAG = "AutomationController"
    }
}
