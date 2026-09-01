package com.botvistas.app.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.botvistas.app.service.BotAutomationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class YouTubeAutomationHandler(
    private val context: Context,
    private val serviceProvider: () -> BotAutomationService?,
    private val onLog: (String) -> Unit,
    private val onStateChanged: (AutomationState) -> Unit
) {

    suspend fun run(targetProfile: String, durationPerVideoSeconds: Int) {
        val service = serviceProvider()
        if (service == null) {
            val err = "Servicio de accesibilidad no estÃ¡ activo."
            onLog("ERROR: $err")
            onStateChanged(AutomationState.Error(err))
            return
        }

        onLog("Iniciando flujo de navegaciÃ³n en YouTube para '$targetProfile'...")
        onStateChanged(AutomationState.Navigating("Abriendo YouTube..."))
        val opened = openYouTubeApp()
        if (!opened) {
            val err = "No se pudo abrir la aplicaciÃ³n de YouTube."
            onLog("ERROR: $err")
            onStateChanged(AutomationState.Error(err))
            return
        }

        delay(3000)

        val currentService = serviceProvider() ?: return
        onLog("Accediendo a la barra de bÃºsqueda de YouTube...")
        onStateChanged(AutomationState.Navigating("Abriendo barra de bÃºsqueda..."))
        val searchOpened = ensureSearchBarOpen(currentService)
        if (!searchOpened) {
            onLog("Aviso: No se detectÃ³ botÃ³n de bÃºsqueda directo, intentando buscar caja de texto...")
        }
        delay(1200)

        onLog("Escribiendo '$targetProfile' en la bÃºsqueda...")
        onStateChanged(AutomationState.Navigating("Escribiendo '$targetProfile'..."))
        val textEntered = enterSearchQuery(currentService, targetProfile)
        if (!textEntered) {
            onLog("Aviso: Reintentando ingresar texto de bÃºsqueda...")
            delay(1000)
            enterSearchQuery(currentService, targetProfile)
        }

        delay(1000)

        onLog("Confirmando y ejecutando la bÃºsqueda...")
        onStateChanged(AutomationState.Navigating("Ejecutando bÃºsqueda..."))
        if (!executeSearchQuery(currentService, targetProfile)) {
            onLog("La UI no confirmo la busqueda; abriendo resultados mediante Intent de YouTube.")
            if (!openYouTubeSearch(targetProfile)) {
                val err = "No se pudo ejecutar la busqueda de YouTube."
                onLog("ERROR: $err")
                onStateChanged(AutomationState.Error(err))
                return
            }
        }

        delay(3500)
        var videoCount = 1

        while (coroutineContext.isActive) {
            val liveService = serviceProvider() ?: break
            onLog("Localizando video #$videoCount de '$targetProfile'...")
            onStateChanged(AutomationState.Navigating("Seleccionando video #$videoCount..."))

            var videoStarted = findAndPlayVideoOrChannel(liveService, targetProfile)
            if (!videoStarted) {
                onLog("Desplazando hacia abajo para encontrar videos disponibles...")
                liveService.performScroll(forward = true)
                delay(2000)
                videoStarted = findAndPlayVideoOrChannel(liveService, targetProfile)
            }

            if (!videoStarted || !waitForPlaybackConfirmation(liveService)) {
                onLog("No se confirmo la reproduccion en la UI; no se informara estado de reproduccion al PC.")
                liveService.performScroll(forward = true)
                delay(2000)
                continue
            }

            onStateChanged(AutomationState.Playing(0, durationPerVideoSeconds, "Video #$videoCount ($targetProfile)"))
            delay(3000)

            onLog("â–¶ Reproduciendo video #$videoCount ($targetProfile) durante $durationPerVideoSeconds s...")
            for (sec in 1..durationPerVideoSeconds) {
                if (!coroutineContext.isActive) return
                onStateChanged(AutomationState.Playing(sec, durationPerVideoSeconds, "Video #$videoCount ($targetProfile)"))
                if (sec % 15 == 0 || sec == durationPerVideoSeconds) {
                    onLog("Progreso video #$videoCount: $sec / $durationPerVideoSeconds s")
                }
                delay(1000)
            }

            onLog("Tiempo cumplido para video #$videoCount ($durationPerVideoSeconds s).")
            onStateChanged(AutomationState.Navigating("Cambiando al siguiente video..."))
            liveService.performSwipeUp()
            delay(1500)
            liveService.performScroll(forward = true)
            delay(2000)
            videoCount++
        }
    }

    private fun openYouTubeApp(): Boolean = try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            context.startActivity(launchIntent)
            true
        } else false
    } catch (e: Exception) {
        Log.e(TAG, "Error abriendo YouTube: ${e.message}", e)
        false
    }

    private fun ensureSearchBarOpen(service: BotAutomationService): Boolean {
        val existingEdit = service.findFirstNode {
            it.className == "android.widget.EditText" || it.viewIdResourceName?.contains("search_edit_text") == true
        }
        if (existingEdit != null) return true

        val searchButtons = service.findNodes { node ->
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            id.contains("menu_search") || id.contains("search_button") ||
                desc.contains("Buscar", ignoreCase = true) || desc.contains("Search", ignoreCase = true)
        }
        for (btn in searchButtons) {
            if (service.clickNode(btn)) {
                Log.d(TAG, "Clic exitoso en botÃ³n de bÃºsqueda.")
                return true
            }
        }
        return false
    }

    private fun enterSearchQuery(service: BotAutomationService, query: String): Boolean {
        val editNode = service.findFirstNode { node ->
            node.className == "android.widget.EditText" ||
                node.viewIdResourceName?.contains("search_edit_text") == true || node.isEditable
        }
        return editNode?.let { service.inputText(it, query) } ?: false
    }

    private fun executeSearchQuery(service: BotAutomationService, query: String): Boolean {
        val suggestions = service.findNodes { node ->
            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            id.contains("text1") || id.contains("suggestion") || id.contains("search_type_suggestion") ||
                text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)
        }
        for (suggestion in suggestions) {
            if (suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK) || service.clickNode(suggestion)) {
                Log.d(TAG, "BÃºsqueda ejecutada mediante clic en sugerencia desplegable.")
                return true
            }
        }

        val actionButtons = service.findNodes { node ->
            val id = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            (id.contains("search") || desc.contains("Buscar", ignoreCase = true) ||
                desc.contains("Search", ignoreCase = true)) && node.isClickable
        }
        for (btn in actionButtons) {
            if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) || service.clickNode(btn)) {
                Log.d(TAG, "BÃºsqueda ejecutada mediante botÃ³n de bÃºsqueda.")
                return true
            }
        }
        return false
    }

    private fun openYouTubeSearch(query: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_SEARCH)
                .setPackage(YOUTUBE_PACKAGE)
                .putExtra("query", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Error abriendo resultados de YouTube: ${e.message}", e)
        false
    }

    private suspend fun waitForPlaybackConfirmation(service: BotAutomationService): Boolean {
        repeat(7) {
            if (isPlaybackUiVisible(service)) return true
            delay(1000)
        }
        return false
    }

    private fun isPlaybackUiVisible(service: BotAutomationService): Boolean =
        service.findFirstNode { node ->
            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            id.contains("pause", ignoreCase = true) ||
                text.contains("Pausar", ignoreCase = true) || text.contains("Pause", ignoreCase = true) ||
                desc.contains("Pausar", ignoreCase = true) || desc.contains("Pause", ignoreCase = true)
        } != null

    private suspend fun findAndPlayVideoOrChannel(service: BotAutomationService, targetProfile: String): Boolean {
        val channelNode = service.findFirstNode { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            (text.contains(targetProfile, ignoreCase = true) || desc.contains(targetProfile, ignoreCase = true)) &&
                (desc.contains("suscriptores", ignoreCase = true) || desc.contains("subscribers", ignoreCase = true) ||
                    desc.contains("canal", ignoreCase = true) || desc.contains("channel", ignoreCase = true))
        }
        if (channelNode != null) {
            onLog("Canal '$targetProfile' identificado. Accediendo al perfil...")
            service.clickNode(channelNode)
            delay(2500)
            val videosTab = service.findFirstNode { node ->
                val text = node.text?.toString() ?: ""
                text.equals("Videos", ignoreCase = true) || text.equals("VIDEOS", ignoreCase = true)
            }
            if (videosTab != null) {
                service.clickNode(videosTab)
                delay(1800)
            }
        }

        val videoNodes = service.findNodes { node ->
            val id = node.viewIdResourceName ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val isVideoElement = id.contains("thumbnail") || id.contains("compact_renderer") ||
                id.contains("video_title") || id.contains("item_layout") ||
                desc.contains("minutos", ignoreCase = true) || desc.contains("vistas", ignoreCase = true) ||
                desc.contains("visualizaciones", ignoreCase = true) || desc.contains("views", ignoreCase = true)
            isVideoElement && (node.isClickable || node.parent?.isClickable == true)
        }
        for (vNode in videoNodes) {
            if (service.clickNode(vNode)) {
                onLog("Clic exitoso en video de '$targetProfile'. Iniciando reproducciÃ³n.")
                return true
            }
        }

        val allClickable = service.findNodes { it.isClickable }
        for (i in 2 until minOf(allClickable.size, 7)) {
            val node = allClickable[i]
            val desc = node.contentDescription?.toString() ?: ""
            if (!desc.contains("atrÃ¡s", ignoreCase = true) && !desc.contains("buscar", ignoreCase = true)) {
                if (service.clickNode(node)) {
                    onLog("Clic ejecutado en elemento de resultados #$i.")
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val TAG = "YouTubeAutomation"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
