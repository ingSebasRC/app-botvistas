package com.botvistas.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.botvistas.app.ui.config.ConfigurationScreen
import com.botvistas.app.ui.config.ConfigurationViewModel
import com.botvistas.app.ui.theme.BotVistasTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ConfigurationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BotVistasTheme {
                ConfigurationScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibilityState()
    }
}