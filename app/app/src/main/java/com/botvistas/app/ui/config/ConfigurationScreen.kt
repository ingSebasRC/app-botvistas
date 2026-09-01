package com.botvistas.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.botvistas.app.R
import com.botvistas.app.automation.AutomationState
import com.botvistas.app.model.Platform
import com.botvistas.app.network.ConnectionState
import com.botvistas.app.ui.theme.BotVistasTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    viewModel: ConfigurationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.saved_message)

    LaunchedEffect(uiState.savedMessageShown) {
        if (uiState.savedMessageShown) {
            snackbarHostState.showSnackbar(
                message = savedMessage,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            viewModel.onSavedMessageConsumed()
        }
    }

    ConfigurationContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onServerAddressChange = viewModel::onServerAddressChange,
        onDeviceIdChange = viewModel::onDeviceIdChange,
        onToggleConnection = viewModel::onToggleConnection,
        onOpenAccessibilitySettings = viewModel::onOpenAccessibilitySettings,
        onStartLocalAutomation = viewModel::onStartLocalAutomation,
        onStopLocalAutomation = viewModel::onStopLocalAutomation,
        onPlatformSelected = viewModel::onPlatformSelected,
        onProfileNameChange = viewModel::onProfileNameChange,
        onTimeChange = viewModel::onTimeChange,
        onSave = viewModel::onSave
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationContent(
    uiState: ConfigurationUiState,
    snackbarHostState: SnackbarHostState,
    onServerAddressChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartLocalAutomation: () -> Unit,
    onStopLocalAutomation: () -> Unit,
    onPlatformSelected: (Platform) -> Unit,
    onProfileNameChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Tarjeta de Conexión con Servidor PC
            ServerConnectionCard(
                serverAddress = uiState.serverAddress,
                deviceId = uiState.deviceId,
                connectionState = uiState.connectionState,
                onServerAddressChange = onServerAddressChange,
                onDeviceIdChange = onDeviceIdChange,
                onToggleConnection = onToggleConnection
            )

            // 2. Tarjeta de Permiso de Accesibilidad (Fase 5)
            AccessibilityPermissionCard(
                isEnabled = uiState.isAccessibilityEnabled,
                onOpenSettings = onOpenAccessibilitySettings
            )

            // 3. Tarjeta de Estado del Motor de Automatización (Fase 6)
            AutomationStatusCard(
                automationState = uiState.automationState,
                isAccessibilityEnabled = uiState.isAccessibilityEnabled,
                onStart = onStartLocalAutomation,
                onStop = onStopLocalAutomation
            )

            // 4. Tarjeta de Configuración Local
            Text(
                text = stringResource(R.string.config_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.config_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.platform_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            Platform.entries.forEachIndexed { index, platform ->
                                SegmentedButton(
                                    selected = uiState.platform == platform,
                                    onClick = { onPlatformSelected(platform) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = Platform.entries.size
                                    )
                                ) {
                                    Text(
                                        text = stringResource(
                                            when (platform) {
                                                Platform.YOUTUBE -> R.string.platform_youtube
                                                Platform.TIKTOK -> R.string.platform_tiktok
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.profileName,
                        onValueChange = onProfileNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.profile_label)) },
                        placeholder = { Text(stringResource(R.string.profile_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null
                            )
                        },
                        isError = uiState.profileNameError != null,
                        supportingText = {
                            uiState.profileNameError?.let { Text(it) }
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = uiState.timeText,
                        onValueChange = onTimeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.time_label)) },
                        placeholder = { Text(stringResource(R.string.time_hint)) },
                        suffix = { Text(stringResource(R.string.time_unit)) },
                        isError = uiState.timeError != null,
                        supportingText = {
                            uiState.timeError?.let { Text(it) }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerConnectionCard(
    serverAddress: String,
    deviceId: String,
    connectionState: ConnectionState,
    onServerAddressChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onToggleConnection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.server_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                ConnectionBadge(connectionState = connectionState)
            }

            OutlinedTextField(
                value = serverAddress,
                onValueChange = onServerAddressChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.server_ip_label)) },
                placeholder = { Text(stringResource(R.string.server_ip_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null
                    )
                },
                singleLine = true,
                enabled = connectionState !is ConnectionState.Connected
            )

            OutlinedTextField(
                value = deviceId,
                onValueChange = onDeviceIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_id_label)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = null
                    )
                },
                singleLine = true,
                enabled = connectionState !is ConnectionState.Connected
            )

            val isConnected = connectionState is ConnectionState.Connected
            val isConnecting = connectionState is ConnectionState.Connecting

            Button(
                onClick = onToggleConnection,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                enabled = !isConnecting
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (isConnected) {
                            stringResource(R.string.disconnect_button)
                        } else {
                            stringResource(R.string.connect_button)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessibilityPermissionCard(
    isEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFFF1F8F4) else Color(0xFFFFF8E6)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = if (isEnabled) Color(0xFF1E8E3E) else Color(0xFFB06000)
                    )
                    Text(
                        text = stringResource(R.string.accessibility_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isEnabled) Color(0xFFE6F4EA) else Color(0xFFFCE8E6)
                ) {
                    Text(
                        text = if (isEnabled) stringResource(R.string.accessibility_enabled) else stringResource(R.string.accessibility_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) Color(0xFF137333) else Color(0xFFC5221F),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (!isEnabled) {
                Text(
                    text = stringResource(R.string.accessibility_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Outlined.Settings, contentDescription = null)
                    Text(
                        text = stringResource(R.string.enable_accessibility_button),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationStatusCard(
    automationState: AutomationState,
    isAccessibilityEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "⚡ Motor de Automatización",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            when (automationState) {
                is AutomationState.Idle -> {
                    Text(
                        text = "Estado: En espera de órdenes locales o remotas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is AutomationState.Navigating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = automationState.stepDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                is AutomationState.Playing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "▶ ${automationState.info}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E8E3E)
                        )
                        val progress = (automationState.elapsedSeconds.toFloat() / maxOf(1, automationState.targetSeconds)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Reproduciendo: ${automationState.elapsedSeconds}s / ${automationState.targetSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is AutomationState.Stopped -> {
                    Text(
                        text = "⏹ Automatización detenida.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB06000)
                    )
                }
                is AutomationState.Error -> {
                    Text(
                        text = "❌ Error: ${automationState.errorMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val isRunning = automationState is AutomationState.Navigating || automationState is AutomationState.Playing

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning && isAccessibilityEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E8E3E))
                ) {
                    Text("▶ Iniciar Local")
                }

                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("⏹ Detener")
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(connectionState: ConnectionState) {
    val (bgColor, textColor, dotColor, label) = when (connectionState) {
        is ConnectionState.Connected -> Quadruple(
            Color(0xFFE6F4EA),
            Color(0xFF137333),
            Color(0xFF1E8E3E),
            stringResource(R.string.status_connected)
        )
        is ConnectionState.Connecting -> Quadruple(
            Color(0xFFFEF7E0),
            Color(0xFFB06000),
            Color(0xFFF9AB00),
            stringResource(R.string.status_connecting)
        )
        is ConnectionState.Error -> Quadruple(
            Color(0xFFFCE8E6),
            Color(0xFFC5221F),
            Color(0xFFD93025),
            stringResource(R.string.status_error)
        )
        is ConnectionState.Disconnected -> Quadruple(
            Color(0xFFF1F3F4),
            Color(0xFF5F6368),
            Color(0xFF80868B),
            stringResource(R.string.status_disconnected)
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Preview(showBackground = true)
@Composable
private fun ConfigurationContentPreview() {
    BotVistasTheme {
        ConfigurationContent(
            uiState = ConfigurationUiState(
                serverAddress = "192.168.1.100:8000",
                deviceId = "ANDROID-001",
                connectionState = ConnectionState.Connected,
                isAccessibilityEnabled = true,
                automationState = AutomationState.Playing(45, 180, "YouTube -> @MrBeast")
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onServerAddressChange = {},
            onDeviceIdChange = {},
            onToggleConnection = {},
            onOpenAccessibilitySettings = {},
            onStartLocalAutomation = {},
            onStopLocalAutomation = {},
            onPlatformSelected = {},
            onProfileNameChange = {},
            onTimeChange = {},
            onSave = {}
        )
    }
}