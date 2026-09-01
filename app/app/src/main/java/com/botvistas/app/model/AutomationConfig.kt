package com.botvistas.app.model

data class AutomationConfig(
    val platform: Platform = Platform.YOUTUBE,
    val profileName: String = "",
    val secondsPerVideo: Int = 0
)