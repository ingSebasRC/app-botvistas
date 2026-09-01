package com.botvistas.app.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.botvistas.app.service.BotAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class TikTokAutomationHandler(
    private val context: Context,
    private val serviceProvider: () -> BotAccessibilityService?,
    private val onLog: (String) -> Unit,
    private val onStateChanged: (AutomationState) -> Unit
) {

    suspend fun run(targetProfile: String, durationPerVideoSeconds: Int) {
        val service = serviceProvider()
        if (service == null) {
            val err = "Servicio de accesibilidad no está activo."
            onLog("ERROR: $err")
            onStateChanged(AutomationState.Error(err))
            return
        }

        onLog("Iniciando flujo de automatización en TikTok...")
        onStateChanged(AutomationState.Navigating("Abriendo TikTok y buscando perfil '$targetProfile'..."))

        val cleanUser = targetProfile.trim().removePrefix("@")
        val opened = openTikTokProfile(cleanUser)
        if (!opened) {
            val err = "No se pudo abrir la aplicación de TikTok."
            onLog("ERROR: $err")
            onStateChanged(AutomationState.Error(err))
            return
        }

        delay(4000) // Esperar carga de perfil

        var videoCount = 1

        while (coroutineContext.isActive) {
            val currentService = serviceProvider() ?: break

            onLog("Seleccionando video #$videoCount de @$cleanUser...")
            onStateChanged(AutomationState.Navigating("Reproduciendo video #$videoCount de @$cleanUser..."))

            // Clic en video inicial si es el primero
            if (videoCount == 1) {
                findAndClickFirstVideo(currentService)
                delay(2000)
            }

            // Reproducir durante el tiempo configurado
            onLog("Reproduciendo video #$videoCount en TikTok durante $durationPerVideoSeconds segundos...")
            for (sec in 1..durationPerVideoSeconds) {
                if (!coroutineContext.isActive) return

                onStateChanged(AutomationState.Playing(sec, durationPerVideoSeconds, "TikTok: @$cleanUser (Video #$videoCount)"))

                if (sec % 10 == 0 || sec == durationPerVideoSeconds) {
                    onLog("Progreso TikTok video #$videoCount: $sec / $durationPerVideoSeconds s")
                }
                delay(1000)
            }

            onLog("Tiempo cumplido para video #$videoCount. Deslizando al siguiente video en TikTok...")
            currentService.performSwipeUp()
            delay(2000)

            videoCount++
        }
    }

    private fun openTikTokProfile(username: String): Boolean {
        for (pkg in TIKTOK_PACKAGES) {
            try {
                val uri = Uri.parse("https://www.tiktok.com/@$username")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
                return true
            } catch (ignored: Exception) {
            }
        }

        // Fallback a launch intent
        for (pkg in TIKTOK_PACKAGES) {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launch)
                    return true
                }
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    private fun findAndClickFirstVideo(service: BotAccessibilityService): Boolean {
        val clickable = service.findNodes { it.isClickable }
        for (node in clickable) {
            val id = node.viewIdResourceName ?: ""
            if (id.contains("cover", ignoreCase = true) || id.contains("video", ignoreCase = true) || id.contains("item", ignoreCase = true)) {
                if (service.clickNode(node)) return true
            }
        }
        if (clickable.size > 3) {
            return service.clickNode(clickable[3])
        }
        return false
    }

    companion object {
        private const val TAG = "TikTokAutomation"
        private val TIKTOK_PACKAGES = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )
    }
}
