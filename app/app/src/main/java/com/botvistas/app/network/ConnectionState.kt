package com.botvistas.app.network

/**
 * Estados posibles de la conexión WebSocket con el servidor PC.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}
