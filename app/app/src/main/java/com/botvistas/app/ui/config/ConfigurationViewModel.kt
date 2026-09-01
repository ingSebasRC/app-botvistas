package com.botvistas.app.ui.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.botvistas.app.R
import com.botvistas.app.automation.AutomationController
import com.botvistas.app.automation.AutomationState
import com.botvistas.app.model.AutomationConfig
import com.botvistas.app.model.Platform
import com.botvistas.app.network.ConnectionState
import com.botvistas.app.network.WebSocketManager
import com.botvistas.app.service.BotAccessibilityService
import com.botvistas.app.util.AccessibilityUtils
import com.botvistas.app.util.DeviceUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager(application, viewModelScope)
    private val automationController = AutomationController(application, viewModelScope)

    private val _uiState = MutableStateFlow(
        ConfigurationUiState(
            deviceId = DeviceUtils.getDeviceId(application),
            isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(
                application,
                BotAccessibilityService::class.java
            )
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        // 1. Observar estado de conexión del WebSocket
        webSocketManager.connectionState
            .onEach { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
            .launchIn(viewModelScope)

        // 2. Observar estado del motor de automatización
        automationController.automationState
            .onEach { autoState ->
                _uiState.value = _uiState.value.copy(automationState = autoState)
            }
            .launchIn(viewModelScope)

        // 3. Observar ciclo de vida del servicio de accesibilidad
        BotAccessibilityService.isServiceActive
            .onEach { isActive ->
                _uiState.value = _uiState.value.copy(
                    isAccessibilityEnabled = isActive || AccessibilityUtils.isAccessibilityServiceEnabled(
                        getApplication(),
                        BotAccessibilityService::class.java
                    )
                )
            }
            .launchIn(viewModelScope)

        // 4. Recepción de comandos remotos desde el PC por WebSocket
        webSocketManager.onTaskStartReceived = { platformStr, targetProfile, durationSec ->
            val platform = if (platformStr.equals("tiktok", ignoreCase = true)) {
                Platform.TIKTOK
            } else {
                Platform.YOUTUBE
            }

            _uiState.value = _uiState.value.copy(
                platform = platform,
                profileName = targetProfile,
                timeText = durationSec.toString(),
                remoteTaskMessage = "⚡ Tarea remota activa: ${platform.name} -> $targetProfile"
            )

            // Iniciar el motor de automatización localmente
            val devId = _uiState.value.deviceId
            automationController.start(
                platform = platform,
                targetProfile = targetProfile,
                durationPerVideoSeconds = durationSec,
                deviceId = devId,
                wsManager = webSocketManager
            )
        }

        webSocketManager.onTaskStopReceived = {
            _uiState.value = _uiState.value.copy(
                remoteTaskMessage = "⏹ Tarea detenida por el PC"
            )
            val devId = _uiState.value.deviceId
            automationController.stop(devId, webSocketManager)
        }
    }

    fun refreshAccessibilityState() {
        val isEnabled = BotAccessibilityService.instance != null || AccessibilityUtils.isAccessibilityServiceEnabled(
            getApplication(),
            BotAccessibilityService::class.java
        )
        _uiState.value = _uiState.value.copy(isAccessibilityEnabled = isEnabled)
    }

    fun onOpenAccessibilitySettings() {
        AccessibilityUtils.openAccessibilitySettings(getApplication())
    }

    fun onStartLocalAutomation() {
        val current = _uiState.value
        val profileName = current.profileName.trim()
        val seconds = current.timeText.toIntOrNull() ?: 180

        if (profileName.isEmpty()) {
            _uiState.value = current.copy(
                profileNameError = getApplication<Application>().getString(R.string.error_profile_empty)
            )
            return
        }

        automationController.start(
            platform = current.platform,
            targetProfile = profileName,
            durationPerVideoSeconds = seconds,
            deviceId = current.deviceId,
            wsManager = webSocketManager
        )
    }

    fun onStopLocalAutomation() {
        automationController.stop(_uiState.value.deviceId, webSocketManager)
    }

    fun onServerAddressChange(value: String) {
        _uiState.value = _uiState.value.copy(serverAddress = value)
    }

    fun onDeviceIdChange(value: String) {
        _uiState.value = _uiState.value.copy(deviceId = value)
    }

    fun onToggleConnection() {
        val current = _uiState.value
        when (current.connectionState) {
            is ConnectionState.Connected, is ConnectionState.Connecting -> {
                webSocketManager.disconnect()
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                webSocketManager.connect(
                    serverAddress = current.serverAddress,
                    customDeviceId = current.deviceId
                )
            }
        }
    }

    fun onPlatformSelected(platform: Platform) {
        _uiState.value = _uiState.value.copy(
            platform = platform,
            savedMessageShown = false
        )
    }

    fun onProfileNameChange(value: String) {
        _uiState.value = _uiState.value.copy(
            profileName = value,
            profileNameError = null,
            savedMessageShown = false
        )
    }

    fun onTimeChange(value: String) {
        _uiState.value = _uiState.value.copy(
            timeText = value.filter { it.isDigit() }.take(MAX_TIME_DIGITS),
            timeError = null,
            savedMessageShown = false
        )
    }

    fun onSave() {
        val current = _uiState.value
        val profileName = current.profileName.trim()
        val seconds = current.timeText.toIntOrNull()
        val resources = getApplication<Application>().resources

        val profileNameError = if (profileName.isEmpty()) {
            resources.getString(R.string.error_profile_empty)
        } else {
            null
        }

        if (profileNameError == null && seconds != null && seconds in 1..MAX_SECONDS_PER_VIDEO) {
            _uiState.value = current.copy(
                lastSavedConfig = AutomationConfig(
                    platform = current.platform,
                    profileName = profileName,
                    secondsPerVideo = seconds
                ),
                savedMessageShown = true
            )
        } else {
            val timeError = if (seconds == null || seconds !in 1..MAX_SECONDS_PER_VIDEO) {
                resources.getString(R.string.error_time_invalid)
            } else {
                null
            }
            _uiState.value = current.copy(
                profileNameError = profileNameError,
                timeError = timeError
            )
        }
    }

    fun onSavedMessageConsumed() {
        _uiState.value = _uiState.value.copy(savedMessageShown = false)
    }

    override fun onCleared() {
        super.onCleared()
        webSocketManager.disconnect()
        automationController.stop(_uiState.value.deviceId, null)
    }

    private companion object {
        const val MAX_TIME_DIGITS = 6
        const val MAX_SECONDS_PER_VIDEO = 86400
    }
}