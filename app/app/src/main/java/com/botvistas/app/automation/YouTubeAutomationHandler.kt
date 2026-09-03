package com.botvistas.app.automation

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
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

    /**
     * Emite transiciones de estado con log detallado para trazabilidad en Logcat.
     */
    private fun updateState(newState: AutomationState) {
        val stateName = when (newState) {
            is AutomationState.Idle -> "Idle"
            is AutomationState.Navigating -> "Navigating(${newState.stepDescription})"
            is AutomationState.Playing -> "Playing(sec=${newState.elapsedSeconds}/${newState.targetSeconds}, info='${newState.info}')"
            is AutomationState.Stopped -> "Stopped"
            is AutomationState.Error -> "Error(${newState.errorMessage})"
        }
        Log.d(TAG, "[TRANSICION ESTADO] -> $stateName")
        onStateChanged(newState)
    }

    /**
     * Obtiene el servicio de accesibilidad de manera resiliente, esperando si experimenta un breve parpadeo/reconexión.
     */
    private suspend fun getResilientService(maxWaitMs: Long = 8000): BotAutomationService? {
        val service = serviceProvider()
        if (service != null) return service

        Log.w(TAG, "[A11yService] Servicio de accesibilidad temporalmente inaccesible. Esperando reconexión (hasta ${maxWaitMs / 1000}s)...")
        val startTime = System.currentTimeMillis()
        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < maxWaitMs) {
            delay(500)
            val retry = serviceProvider()
            if (retry != null) {
                Log.i(TAG, "[A11yService] Servicio de accesibilidad recuperado con éxito tras ${System.currentTimeMillis() - startTime}ms.")
                return retry
            }
        }

        Log.e(TAG, "[A11yService] Tiempo límite agotado esperando el servicio de accesibilidad.")
        return null
    }

    suspend fun run(targetProfile: String, durationPerVideoSeconds: Int) {
        val initialService = getResilientService()
        if (initialService == null) {
            val err = "Servicio de accesibilidad no está activo."
            Log.e(TAG, "[INICIO ERROR] $err")
            onLog("ERROR: $err")
            updateState(AutomationState.Error(err))
            return
        }

        // BLOQUEO ESTRICTO: Prevenir cualquier comando de retroceso (GLOBAL_ACTION_BACK)
        initialService.isBackNavigationLocked = true
        Log.d(TAG, "[SEGURIDAD] Bloqueo estricto de retroceso (BACK) activado en BotAutomationService.")

        onLog("Iniciando búsqueda directa en YouTube para '$targetProfile'...")
        updateState(AutomationState.Navigating("Buscando '$targetProfile'..."))
        val searchOpened = openYouTubeSearch(targetProfile)
        if (!searchOpened) {
            val err = "No se pudo iniciar la búsqueda directa de YouTube."
            Log.e(TAG, "[ERROR BUSQUEDA] $err")
            onLog("ERROR: $err")
            updateState(AutomationState.Error(err))
            return
        }

        // Esperar a que la pantalla de resultados de búsqueda cargue por completo (NUNCA retroceder)
        Log.d(TAG, "[ESPERA] Esperando carga inicial de resultados de búsqueda (4s)...")
        delay(4000)
        var videoCount = 1

        while (coroutineContext.isActive) {
            val liveService = getResilientService()
            if (liveService == null) {
                val err = "Servicio de accesibilidad desconectado permanentemente. Deteniendo automatización."
                Log.e(TAG, "[ERROR CRITICO] $err")
                onLog("ERROR CRÍTICO: $err")
                updateState(AutomationState.Error(err))
                break
            }

            liveService.isBackNavigationLocked = true

            Log.d(TAG, "[CICLO] Procesando video #$videoCount de '$targetProfile'...")
            onLog("Localizando video #$videoCount de '$targetProfile'...")
            updateState(AutomationState.Navigating("Seleccionando video #$videoCount..."))

            // Búsqueda a prueba de fallos: Detección con timeout de 7s y Fallback por Coordenadas (sin retroceso)
            var videoStarted = findAndOpenChannelOrVideo(liveService, targetProfile)
            if (!videoStarted) {
                Log.w(TAG, "[AVISO] Reintentando detección de canal o primera miniatura sin scroll ni retroceso...")
                onLog("Aviso: Reintentando detección sin scroll ni retroceso...")
                delay(2000)
                val retryService = getResilientService() ?: break
                videoStarted = findAndOpenChannelOrVideo(retryService, targetProfile)
            }

            if (!videoStarted) {
                Log.w(TAG, "[AVISO] No se pudo iniciar el video tras reintentos. Esperando 3s antes de siguiente intento...")
                onLog("Esperando resultados en pantalla...")
                delay(3000)
                continue
            }

            // Esperar confirmación de apertura de pantalla completa del reproductor
            Log.d(TAG, "[REPRODUCTOR] Esperando confirmación de pantalla completa del reproductor...")
            onLog("Esperando confirmación de pantalla completa del reproductor...")
            val playerOpen = waitForPlaybackConfirmation(liveService)
            if (!playerOpen) {
                Log.w(TAG, "[REPRODUCTOR] No se confirmó apertura de pantalla completa; scroll cancelado. Continuando monitoreo...")
                onLog("No se confirmó apertura de pantalla completa del reproductor; scroll cancelado.")
                delay(2000)
                continue
            }

            updateState(AutomationState.Playing(0, durationPerVideoSeconds, "Video #$videoCount ($targetProfile)"))
            delay(2000)

            onLog("▶ Reproduciendo video #$videoCount ($targetProfile) durante $durationPerVideoSeconds s...")
            for (sec in 1..durationPerVideoSeconds) {
                if (!coroutineContext.isActive) return
                updateState(AutomationState.Playing(sec, durationPerVideoSeconds, "Video #$videoCount ($targetProfile)"))
                if (sec % 15 == 0 || sec == durationPerVideoSeconds) {
                    onLog("Progreso video #$videoCount: $sec / $durationPerVideoSeconds s")
                }
                delay(1000)
            }

            onLog("Tiempo cumplido para video #$videoCount ($durationPerVideoSeconds s).")
            updateState(AutomationState.Navigating("Cambiando al siguiente video..."))

            // Solo realizar scroll si el reproductor de pantalla completa está abierto
            val currentService = getResilientService() ?: break
            if (isFullPlayerOpen(currentService)) {
                Log.d(TAG, "[SCROLL] Reproductor abierto. Despachando Swipe Up hacia siguiente video...")
                currentService.performSwipeUp()
                delay(1500)
                performScrollIfPlayerOpen(currentService)
                delay(2000)
            } else {
                Log.w(TAG, "[SCROLL] Scroll automático cancelado: pantalla completa del reproductor no abierta.")
                onLog("Scroll automático cancelado al cambiar de video: pantalla completa del reproductor no abierta.")
            }

            videoCount++
        }
    }

    /**
     * Abre directamente los resultados de búsqueda en YouTube mediante Intent ACTION_SEARCH con fallback ACTION_VIEW.
     * NUNCA retrocede ni abre el teclado virtual.
     */
    private fun openYouTubeSearch(query: String): Boolean {
        Log.d(TAG, "[INTENT] Abriendo búsqueda directa en YouTube para '$query'...")
        // 1. Intent ACTION_SEARCH estándar con flags adecuados
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(YOUTUBE_PACKAGE)
                putExtra("query", query)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            Log.d(TAG, "[INTENT] ACTION_SEARCH enviado exitosamente a YouTube.")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "[INTENT] ACTION_SEARCH falló (${e.message}), recurriendo a ACTION_VIEW con search_query...")
        }

        // 2. Fallback con ACTION_VIEW a la URL oficial de resultados
        try {
            val searchUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            val webIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage(YOUTUBE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(webIntent)
            Log.d(TAG, "[INTENT] ACTION_VIEW con search_query enviado exitosamente.")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "[INTENT] Error abriendo búsqueda directa de YouTube: ${e.message}", e)
            return false
        }
    }

    /**
     * Búsqueda a prueba de fallos del canal o primer video:
     * - Timeout de 7 segundos revisando nodos clave (Ver canal, Avatar, Texto, Thumbnail, Título).
     * - Si el nodo se detecta, ejecuta clic en nodo/ancestro o tap por coordenadas de dicho nodo.
     * - FALLBACK POR COORDENADAS: Si expiran los 7 segundos sin detectar nodos, NUNCA ejecuta retroceso (BACK).
     *   En su lugar, despacha un tap por coordenadas en la posición esperada del primer resultado en pantalla.
     */
    private suspend fun findAndOpenChannelOrVideo(service: BotAutomationService, targetProfile: String): Boolean {
        val cleanProfile = targetProfile.trim()
        val cleanHandle = cleanProfile.removePrefix("@").trim()

        Log.d(TAG, "[BUSQUEDA] Buscando canal o video para '$cleanProfile' (Timeout: 7s)...")

        // 0. Si el reproductor ya está abierto (ej. videos sucesivos)
        if (isFullPlayerOpen(service)) {
            val nextVideoNode = service.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                val isThumbnail = id.contains("thumbnail", ignoreCase = true)
                val isTitle = id.contains("video_title", ignoreCase = true) || id.contains("title", ignoreCase = true)
                val isContainer = id.contains("compact_renderer", ignoreCase = true) || id.contains("item_layout", ignoreCase = true)
                (isThumbnail || isTitle || isContainer) && !id.contains("channel_avatar", ignoreCase = true)
            }
            if (nextVideoNode != null) {
                Log.d(TAG, "[BUSQUEDA] Seleccionando siguiente video en el reproductor activo...")
                onLog("Seleccionando siguiente video en el reproductor activo...")
                return clickNodeOrClickableParent(nextVideoNode, service)
            }
        }

        // Bucle de detección con timeout de 7 segundos (1 intento por segundo)
        val startTime = System.currentTimeMillis()
        val timeoutMs = 7000L

        while (coroutineContext.isActive && (System.currentTimeMillis() - startTime) < timeoutMs) {
            val liveService = getResilientService() ?: service

            // 1. Buscar botón o texto "Ver canal" / "View channel"
            val verCanalNode = liveService.findFirstNode(maxDepth = 10) { node ->
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                text.contains("Ver canal", ignoreCase = true) ||
                    desc.contains("Ver canal", ignoreCase = true) ||
                    text.contains("View channel", ignoreCase = true) ||
                    desc.contains("View channel", ignoreCase = true)
            }
            if (verCanalNode != null) {
                Log.d(TAG, "[BUSQUEDA] Botón 'Ver canal' detectado. Ejecutando clic...")
                onLog("Botón 'Ver canal' detectado. Abriendo canal...")
                if (clickNodeOrClickableParent(verCanalNode, liveService)) {
                    return navigateChannelAndPlayFirstVideo(liveService)
                }
            }

            // 2. Buscar avatar del canal (channel_avatar / channel_header)
            val channelAvatarNode = liveService.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                id.contains("channel_avatar", ignoreCase = true) || id.contains("channel_header", ignoreCase = true)
            }
            if (channelAvatarNode != null) {
                Log.d(TAG, "[BUSQUEDA] Avatar del canal detectado. Ejecutando clic...")
                onLog("Avatar del canal detectado. Abriendo canal...")
                if (clickNodeOrClickableParent(channelAvatarNode, liveService)) {
                    return navigateChannelAndPlayFirstVideo(liveService)
                }
            }

            // 3. Buscar por texto del canal (ej. @hipercuriosoX o Hiper Curioso)
            val channelTextNode = liveService.findFirstNode(maxDepth = 10) { node ->
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val id = node.viewIdResourceName ?: ""

                val matchesClean = cleanHandle.isNotBlank() && (text.contains(cleanHandle, ignoreCase = true) || desc.contains(cleanHandle, ignoreCase = true))
                val matchesRaw = cleanProfile.isNotBlank() && (text.contains(cleanProfile, ignoreCase = true) || desc.contains(cleanProfile, ignoreCase = true))

                val isChannelIndicator = id.contains("channel", ignoreCase = true) ||
                    desc.contains("suscriptor", ignoreCase = true) ||
                    desc.contains("subscriber", ignoreCase = true) ||
                    desc.contains("canal", ignoreCase = true) ||
                    desc.contains("channel", ignoreCase = true)

                (matchesClean || matchesRaw) && isChannelIndicator
            }
            if (channelTextNode != null) {
                Log.d(TAG, "[BUSQUEDA] Texto del canal '$cleanProfile' detectado. Ejecutando clic...")
                onLog("Canal '$cleanProfile' detectado por texto. Abriendo...")
                if (clickNodeOrClickableParent(channelTextNode, liveService)) {
                    return navigateChannelAndPlayFirstVideo(liveService)
                }
            }

            // 4. Fallback a la primera miniatura de video (thumbnail)
            val firstThumbnailNode = liveService.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                id == "com.google.android.youtube:id/thumbnail" ||
                    id.endsWith(":id/thumbnail") ||
                    id.contains("thumbnail", ignoreCase = true)
            }
            if (firstThumbnailNode != null) {
                val idStr = firstThumbnailNode.viewIdResourceName ?: "thumbnail"
                Log.d(TAG, "[BUSQUEDA] Primera miniatura de video detectada ($idStr). Ejecutando clic...")
                onLog("Primera miniatura de video detectada. Iniciando...")
                if (clickNodeOrClickableParent(firstThumbnailNode, liveService)) {
                    return true
                }
            }

            // 5. Fallback por contenedor o título de video
            val videoFallback = liveService.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val isNotBack = !desc.contains("Atrás", ignoreCase = true) && !desc.contains("Navigate", ignoreCase = true)
                val isVideoElement = id.contains("video_title", ignoreCase = true) ||
                    id.contains("compact_renderer", ignoreCase = true) ||
                    id.contains("item_layout", ignoreCase = true) ||
                    desc.contains("minutos", ignoreCase = true) ||
                    desc.contains("segundos", ignoreCase = true) ||
                    desc.contains("vistas", ignoreCase = true) ||
                    desc.contains("views", ignoreCase = true)
                isVideoElement && isNotBack
            }
            if (videoFallback != null) {
                Log.d(TAG, "[BUSQUEDA] Video detectado por título o contenedor. Ejecutando clic...")
                onLog("Video detectado por título/contenedor. Iniciando...")
                if (clickNodeOrClickableParent(videoFallback, liveService)) {
                    return true
                }
            }

            delay(1000)
        }

        // =========================================================================
        // IMPLEMENTACIÓN DE TAP POR COORDENADAS (FALLBACK A PRUEBA DE FALLOS)
        // =========================================================================
        Log.w(TAG, "[FALLBACK COORDENADAS] Nodos no detectados tras ${timeoutMs}ms. NUNCA retroceder. Ejecutando Tap por Coordenadas.")
        onLog("Activando fallback a prueba de fallos: Tap por coordenadas en primer resultado (sin retroceder)...")

        val fallbackService = getResilientService() ?: service
        val (screenWidth, screenHeight) = fallbackService.getScreenDimensions()
        val tapX = screenWidth * 0.5f
        val tapY = screenHeight * 0.28f // 28% de la altura: posición donde aparece la primera tarjeta en YouTube

        Log.d(TAG, "[FALLBACK COORDENADAS] Despachando tap en ($tapX, $tapY) [Resolución: ${screenWidth}x${screenHeight}]")
        val tapped = fallbackService.performTapGesture(tapX, tapY)
        if (tapped) {
            delay(3000)
            val postService = getResilientService() ?: fallbackService
            if (isFullPlayerOpen(postService)) {
                Log.d(TAG, "[FALLBACK COORDENADAS] Reproductor activo tras tap por coordenadas.")
                return true
            }
            // Si el tap abrió la página del canal, navegar a videos
            return navigateChannelAndPlayFirstVideo(postService)
        }

        return false
    }

    /**
     * Navegación al canal y primer video:
     * 1. Detecta la pestaña 'Videos' por texto, contentDescription o IDs específicos de YouTube.
     * 2. Sube recursivamente por padres hasta encontrar un nodo clickeable y lo activa.
     * 3. Si el árbol de accesibilidad no devuelve nodo clickeable, despacha un tap por coordenadas
     *    calculadas sobre el área de la pestaña (fallback con dispatchGesture).
     * 4. Luego busca el primer video (thumbnail o ViewGroup con título) y aplica la misma estrategia.
     * 5. NUNCA retrocede.
     */
    private suspend fun navigateChannelAndPlayFirstVideo(service: BotAutomationService): Boolean {
        // Espera a que la pantalla del canal cargue
        delay(3000)

        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val liveService = getResilientService() ?: service

            if (isFullPlayerOpen(liveService)) {
                Log.d(TAG, "[CANAL] Reproductor de pantalla completa activo.")
                return true
            }

            Log.d(TAG, "[CANAL] Intento $attempt: Buscando pestaña 'Videos'...")
            val videosTab = findVideosTabNode(liveService)
            if (videosTab != null) {
                onLog("Accediendo a la pestaña 'Videos'...")
                clickNodeOrClickableParent(videosTab, liveService)
                delay(2000)
            } else {
                Log.w(TAG, "[CANAL] Pestaña 'Videos' no encontrada en árbol.")
            }

            val updatedService = getResilientService() ?: liveService
            if (isFullPlayerOpen(updatedService)) return true

            Log.d(TAG, "[CANAL] Buscando el primer video en la lista...")
            var firstVideoNode: AccessibilityNodeInfo? = updatedService.findFirstNode(maxDepth = 14) { node ->
                val id = node.viewIdResourceName ?: ""
                id == "com.google.android.youtube:id/thumbnail" || id.endsWith(":id/thumbnail") ||
                id.contains("video_metadata_layout", ignoreCase = true) ||
                id.contains("compact_video_renderer", ignoreCase = true) ||
                id.contains("rich_item_renderer", ignoreCase = true) ||
                id.contains("grid_video", ignoreCase = true)
            }

            if (firstVideoNode == null) {
                firstVideoNode = updatedService.findFirstNode(maxDepth = 14) { node ->
                    (node.className?.toString() ?: "").contains("ViewGroup") && containsThumbnailOrTitle(node)
                }
            }

            if (firstVideoNode != null) {
                onLog("Iniciando reproducción del primer video...")
                clickNodeOrClickableParent(firstVideoNode, updatedService)
                delay(2000)
            } else {
                Log.w(TAG, "[CANAL] Primer video no encontrado en este intento.")
            }

            if (isFullPlayerOpen(getResilientService() ?: updatedService)) {
                return true
            }
        }

        // Fallback final
        Log.w(TAG, "[CANAL] Múltiples intentos fallidos. Aplicando fallback por coordenadas en lista de videos...")
        val fallbackService = getResilientService() ?: service
        val (fw, fh) = fallbackService.getScreenDimensions()
        fallbackService.performTapGesture(fw * 0.5f, fh * 0.45f)
        delay(2500)
        
        return isFullPlayerOpen(getResilientService() ?: fallbackService)
    }

    /**
     * Busca el nodo de la pestaña 'Videos' usando múltiples estrategias:
     * 1. Coincidencia exacta de texto o contentDescription.
     * 2. IDs de recursos conocidos de la barra de pestañas de YouTube.
     * 3. Texto parcial dentro de un contenedor de pestañas.
     */
    private fun findVideosTabNode(service: BotAutomationService): AccessibilityNodeInfo? {
        // Estrategia 1: texto/desc exactos "Videos" o "Vídeos"
        val byText = service.findFirstNode(maxDepth = 12) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            text.equals("Videos", ignoreCase = true) ||
                text.equals("Vídeos", ignoreCase = true) ||
                desc.equals("Videos", ignoreCase = true) ||
                desc.equals("Vídeos", ignoreCase = true)
        }
        if (byText != null) {
            Log.d(TAG, "[CANAL] Pestaña 'Videos' encontrada por texto/desc exacto.")
            return byText
        }

        // Estrategia 2: IDs de recursos conocidos de pestañas de canal de YouTube
        val tabIds = listOf(
            "com.google.android.youtube:id/tab_layout",
            "com.google.android.youtube:id/channel_tab_fragment_container",
            "com.google.android.youtube:id/tab_title"
        )
        for (tabId in tabIds) {
            val candidates = service.findNodesByViewId(tabId)
            val match = candidates.firstOrNull { node ->
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                text.contains("Video", ignoreCase = true) || desc.contains("Video", ignoreCase = true)
            }
            if (match != null) {
                Log.d(TAG, "[CANAL] Pestaña 'Videos' encontrada por viewId '$tabId'.")
                return match
            }
        }

        // Estrategia 3: cualquier nodo que contenga "video" en texto dentro de un contexto de pestaña
        val byPartialText = service.findFirstNode(maxDepth = 12) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val id = node.viewIdResourceName ?: ""
            val inTabContext = id.contains("tab", ignoreCase = true) || id.contains("chip", ignoreCase = true)
            inTabContext && (text.contains("video", ignoreCase = true) || desc.contains("video", ignoreCase = true))
        }
        if (byPartialText != null) {
            Log.d(TAG, "[CANAL] Pestaña 'Videos' encontrada por texto parcial en contexto de pestaña.")
        }
        return byPartialText
    }

    private fun containsThumbnailOrTitle(node: AccessibilityNodeInfo, maxDepth: Int = 4): Boolean {
        if (maxDepth < 0) return false
        return try {
            val id = node.viewIdResourceName ?: ""
            val isThumbnail = id == "com.google.android.youtube:id/thumbnail" || id.endsWith(":id/thumbnail")
            val isTitle = id == "com.google.android.youtube:id/title" || id.contains("video_title", ignoreCase = true)
            isThumbnail || isTitle || (0 until node.childCount).any { index ->
                node.getChild(index)?.let { containsThumbnailOrTitle(it, maxDepth - 1) } == true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Clic robusto con protección estricta contra retroceso (ignora barras de herramientas y botones Atrás)
     * y fallback por coordenadas en el rectángulo del propio nodo.
     */
    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo, service: BotAutomationService? = null): Boolean {
        try {
            // 1. Si el nodo es cliqueable directamente
            if (node.isClickable) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.d(TAG, "performAction(ACTION_CLICK) directo exitoso en nodo.")
                    return true
                }
            }

            // 2. Intentar acción directa incluso si isClickable reporta false
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "performAction(ACTION_CLICK) consumido directamente por el nodo.")
                return true
            }

            // 3. Subir en el árbol de vistas buscando contenedor cliqueable seguro
            var currentParent = node.parent
            var depth = 0
            while (currentParent != null && depth < 6) {
                val desc = currentParent.contentDescription?.toString() ?: ""
                val className = currentParent.className?.toString() ?: ""
                val id = currentParent.viewIdResourceName ?: ""

                // Proteger: NUNCA interactuar con elementos que puedan activar retroceso
                if (desc.contains("Atrás", ignoreCase = true) ||
                    desc.contains("Navigate up", ignoreCase = true) ||
                    desc.contains("Navegar hacia arriba", ignoreCase = true) ||
                    id.contains("back", ignoreCase = true) ||
                    id.contains("up_button", ignoreCase = true) ||
                    className.contains("Toolbar") ||
                    className.contains("ActionBar")
                ) {
                    Log.d(TAG, "clickNodeOrClickableParent: Detenido en elemento de navegación hacia atrás.")
                    break
                }

                if (currentParent.isClickable) {
                    if (currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "performAction(ACTION_CLICK) exitoso en ancestro contenedor (profundidad $depth).")
                        return true
                    }
                }
                currentParent = currentParent.parent
                depth++
            }

            // 4. Fallback a tap por coordenadas en el centro del nodo
            if (service != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = rect.centerX().toFloat()
                    val cy = rect.centerY().toFloat()
                    Log.d(TAG, "clickNodeOrClickableParent: Fallback a tap por coordenadas del nodo en ($cx, $cy).")
                    return service.performTapGesture(cx, cy)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error en clickNodeOrClickableParent: ${e.message}")
        }

        return false
    }

    /**
     * Verifica si la pantalla completa del reproductor está abierta:
     * - Detección del botón de pausar o reproducir en controles principales.
     * - O detección del elemento de video (movie_player, watch_player, player_view) con dimensiones sustanciales.
     * EVITA falsos positivos causados por el contenedor general watch_while_layout.
     */
    private fun isFullPlayerOpen(service: BotAutomationService): Boolean {
        try {
            // Falso positivo: si vemos "Principal", "Videos" o "Unirse", estamos en la vista de canal, no en el reproductor principal.
            val inChannelTab = service.findFirstNode(maxDepth = 10) { node ->
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                text.equals("Principal", ignoreCase = true) ||
                text.equals("Videos", ignoreCase = true) ||
                text.equals("Vídeos", ignoreCase = true) ||
                text.equals("Unirse", ignoreCase = true) ||
                desc.equals("Principal", ignoreCase = true) ||
                desc.equals("Videos", ignoreCase = true) ||
                desc.equals("Vídeos", ignoreCase = true) ||
                desc.equals("Unirse", ignoreCase = true)
            } != null

            if (inChannelTab) {
                Log.d(TAG, "isFullPlayerOpen: Falso positivo evitado. Estamos en la página del canal.")
                return false
            }

            // 1. Detección del botón de pausar o reproducir en los controles del reproductor principal
            val pauseOrPlayButton = service.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val isInline = id.contains("inline", ignoreCase = true) || desc.contains("inline", ignoreCase = true)

                val isPauseOrPlay = id.contains("player_control_play_pause", ignoreCase = true) ||
                    id.contains("pause_button", ignoreCase = true) ||
                    desc.contains("Pausar", ignoreCase = true) ||
                    desc.contains("Pause video", ignoreCase = true) ||
                    desc.contains("Play", ignoreCase = true) ||
                    desc.contains("Reproducir video", ignoreCase = true)

                isPauseOrPlay && !isInline
            }

            if (pauseOrPlayButton != null) {
                return true
            }

            // 2. Detección de elementos de reproductor activo
            val playerElement = service.findFirstNode(maxDepth = 10) { node ->
                val id = node.viewIdResourceName ?: ""
                val isInline = id.contains("inline", ignoreCase = true)
                val isPlayer = id.contains("movie_player", ignoreCase = true) ||
                    id.contains("watch_while_layout", ignoreCase = true) ||
                    id.contains("player_view", ignoreCase = true) ||
                    id.contains("watch_player", ignoreCase = true) ||
                    id.contains("player_fragment", ignoreCase = true) ||
                    id.contains("video_player", ignoreCase = true)

                isPlayer && !isInline
            }

            if (playerElement != null) {
                val rect = Rect()
                playerElement.getBoundsInScreen(rect)
                if (rect.width() > 200 && rect.height() > 200) {
                    return true
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error verificando estado del reproductor: ${e.message}")
        }

        return false
    }

    /**
     * Cancela scrolls automáticos si la pantalla completa del reproductor no está abierta.
     */
    private fun performScrollIfPlayerOpen(service: BotAutomationService): Boolean {
        if (!isFullPlayerOpen(service)) {
            Log.w(TAG, "Scroll cancelado: la pantalla completa del reproductor no está abierta.")
            onLog("Scroll cancelado: la pantalla completa del reproductor no está abierta.")
            return false
        }
        return service.performScroll(forward = true)
    }

    /**
     * Espera a que la interfaz de la pantalla completa del reproductor esté confirmada.
     */
    private suspend fun waitForPlaybackConfirmation(service: BotAutomationService): Boolean {
        repeat(8) { attempt ->
            val liveService = getResilientService() ?: service
            if (isFullPlayerOpen(liveService)) {
                Log.d(TAG, "Reproductor de pantalla completa confirmado en intento ${attempt + 1}.")
                return true
            }
            delay(1000)
        }
        return false
    }

    companion object {
        private const val TAG = "YouTubeAutomation"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
