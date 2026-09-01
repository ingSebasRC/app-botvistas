package com.botvistas.app.automation

/**
 * Estados del motor de automatización local.
 */
sealed interface AutomationState {
    data object Idle : AutomationState
    data class Navigating(val stepDescription: String) : AutomationState
    data class Playing(val elapsedSeconds: Int, val targetSeconds: Int, val info: String = "") : AutomationState
    data object Stopped : AutomationState
    data class Error(val errorMessage: String) : AutomationState
}
