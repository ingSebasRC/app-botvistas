package com.botvistas.app.ui.config

import com.botvistas.app.automation.AutomationState
import com.botvistas.app.model.AutomationConfig
import com.botvistas.app.model.Platform
import com.botvistas.app.network.ConnectionState

data class ConfigurationUiState(
    val serverAddress: String = "192.168.1.100:8000",
    val deviceId: String = "",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isAccessibilityEnabled: Boolean = false,
    val automationState: AutomationState = AutomationState.Idle,
    val platform: Platform = Platform.YOUTUBE,
    val profileName: String = "",
    val timeText: String = "180",
    val profileNameError: String? = null,
    val timeError: String? = null,
    val savedMessageShown: Boolean = false,
    val lastSavedConfig: AutomationConfig? = null,
    val remoteTaskMessage: String? = null
)