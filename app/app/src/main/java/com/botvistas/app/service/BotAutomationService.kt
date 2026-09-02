package com.botvistas.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Servicio de Accesibilidad principal para la automatización e interacción con YouTube y TikTok.
 */
class BotAutomationService : AccessibilityService() {

    /**
     * Bloqueo estricto de navegación hacia atrás para evitar retrocesos accidentales durante la automatización.
     */
    @Volatile
    var isBackNavigationLocked: Boolean = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        isBackNavigationLocked = true
        Log.i(TAG, "✅ [A11yService] BotAutomationService conectado y listo. Bloqueo estricto de retroceso: ACTIVO.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: "desconocido"
                    val cls = event.className?.toString() ?: "desconocido"
                    Log.d(TAG, "[A11yEvent] Cambio de ventana -> Paquete: $pkg | Clase: $cls")
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val pkg = event.packageName?.toString() ?: "desconocido"
                    val id = event.source?.viewIdResourceName ?: "sin_id"
                    Log.d(TAG, "[A11yEvent] Clic en pantalla -> Paquete: $pkg | Vista: $id")
                }
                else -> {
                    // Evitar spam de eventos recurrentes para no saturar el canal IPC ni logs
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Aviso procesando evento de accesibilidad: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ [A11yService] BotAutomationService onInterrupt() invocado por el sistema.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceActive.value = false
        Log.e(TAG, "⚠️ [A11yService] BotAutomationService onDestroy() invocado por el sistema.")
    }

    /**
     * Obtiene el nodo raíz de la ventana activa, con fallback inteligente a ventanas de YouTube si rootInActiveWindow es nulo o pertenece a otra app.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        try {
            val root = rootInActiveWindow
            if (root != null) return root
        } catch (e: Throwable) {
            Log.w(TAG, "Aviso obteniendo rootInActiveWindow: ${e.message}")
        }

        try {
            val windowList = windows
            // Priorizar ventana de YouTube si está presente
            for (window in windowList) {
                val r = window.root ?: continue
                if (r.packageName?.toString() == "com.google.android.youtube") {
                    return r
                }
            }
            // Fallback a primera ventana disponible
            for (window in windowList) {
                val r = window.root
                if (r != null) return r
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Aviso buscando en lista de ventanas: ${e.message}")
        }
        return null
    }

    // --- Métodos de búsqueda e interacción con nodos ---

    /**
     * Busca nodos por coincidencia de texto (insensible a mayúsculas/minúsculas).
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = getRootNode() ?: return emptyList()
        return try {
            val nativeNodes = root.findAccessibilityNodeInfosByText(text)
            if (!nativeNodes.isNullOrEmpty()) {
                nativeNodes
            } else {
                val result = mutableListOf<AccessibilityNodeInfo>()
                findNodesRecursive(root, 0, 10) { node ->
                    val nodeText = node.text?.toString() ?: ""
                    val nodeDesc = node.contentDescription?.toString() ?: ""
                    nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)
                }.forEach { result.add(it) }
                result
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findNodesByText: ${e.message}")
            emptyList()
        }
    }

    /**
     * Busca nodos por ID de recurso de vista (ej. "com.google.android.youtube:id/search_edit_text").
     */
    fun findNodesByViewId(viewId: String): List<AccessibilityNodeInfo> {
        val root = getRootNode() ?: return emptyList()
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findNodesByViewId: ${e.message}")
            emptyList()
        }
    }

    fun findFirstNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findFirstNodeByViewId: ${e.message}")
            null
        }
    }

    /**
     * Busca nodos por descripción de contenido.
     */
    fun findNodesByContentDescription(desc: String): List<AccessibilityNodeInfo> {
        val root = getRootNode() ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            findNodesRecursive(root, 0, 10) { node ->
                node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
            }.forEach { result.add(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findNodesByContentDescription: ${e.message}")
        }
        return result
    }

    /**
     * Busca todos los nodos que cumplan una condición con límite de profundidad seguro.
     */
    fun findNodes(maxDepth: Int = 10, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = getRootNode() ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            findNodesRecursive(root, 0, maxDepth, predicate).forEach { result.add(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findNodes: ${e.message}")
        }
        return result
    }

    /**
     * Encuentra el primer nodo que cumpla la condición con límite de profundidad seguro.
     */
    fun findFirstNode(maxDepth: Int = 12, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null
        return try {
            findFirstNodeRecursive(root, 0, maxDepth, predicate)
        } catch (e: Throwable) {
            Log.w(TAG, "Error en findFirstNode: ${e.message}")
            null
        }
    }

    private fun findFirstNodeRecursive(
        node: AccessibilityNodeInfo,
        currentDepth: Int,
        maxDepth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (currentDepth > maxDepth) return null
        try {
            if (predicate(node)) return node
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                val found = findFirstNodeRecursive(child, currentDepth + 1, maxDepth, predicate)
                if (found != null) return found
            }
        } catch (e: Throwable) {
            // Prevenir excepciones por nodos reciclados o conexiones IPC abortadas
        }
        return null
    }

    private fun findNodesRecursive(
        node: AccessibilityNodeInfo,
        currentDepth: Int,
        maxDepth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        if (currentDepth > maxDepth) return emptyList()
        val matching = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (predicate(node)) {
                matching.add(node)
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                matching.addAll(findNodesRecursive(child, currentDepth + 1, maxDepth, predicate))
            }
        } catch (e: Throwable) {
            // Prevenir fallos en iteración
        }
        return matching
    }

    /**
     * Hace clic en un nodo o busca en sus ancestros hasta ejecutar el clic, con fallback por coordenadas.
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        val current = node ?: return false

        try {
            // 1. Intentar clic directo si es clickeable
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            // 2. Intentar clic directo sin verificar isClickable
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }

            // 3. Intentar clic en ancestros seguros
            var parent = current.parent
            var depth = 0
            while (parent != null && depth < 5) {
                val desc = parent.contentDescription?.toString() ?: ""
                val cls = parent.className?.toString() ?: ""
                // Evitar ancestros de navegación hacia atrás
                if (!desc.contains("Atrás", ignoreCase = true) &&
                    !desc.contains("Navigate up", ignoreCase = true) &&
                    !cls.contains("Toolbar")
                ) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                parent = parent.parent
                depth++
            }

            // 4. Fallback a gesto de tap por coordenadas del nodo
            val rect = Rect()
            current.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                Log.d(TAG, "clickNode: Fallback a tap por coordenadas en ($centerX, $centerY)")
                return performTapGesture(centerX, centerY)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error en clickNode: ${e.message}")
        }

        return false
    }

    /**
     * Realiza un tap por gesto en coordenadas específicas de pantalla.
     */
    fun performTapGesture(x: Float, y: Float, durationMs: Long = 50): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            Log.w(TAG, "Error ejecutando performTapGesture en ($x, $y): ${e.message}")
            false
        }
    }

    /**
     * Obtiene las dimensiones de la pantalla en píxeles (ancho, alto).
     */
    fun getScreenDimensions(): Pair<Int, Int> {
        return try {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        } catch (e: Throwable) {
            Pair(1080, 2400)
        }
    }

    /**
     * Establece texto en un campo editable.
     */
    fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean {
        val target = node ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Throwable) {
            Log.w(TAG, "Error en inputText: ${e.message}")
            false
        }
    }

    /**
     * Realiza desplazamiento vertical hacia adelante o atrás.
     */
    fun performScroll(forward: Boolean = true): Boolean {
        val root = getRootNode() ?: return false
        return try {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val scrollableNodes = findNodes(maxDepth = 8) { it.isScrollable }
            for (node in scrollableNodes) {
                if (node.performAction(action)) {
                    return true
                }
            }
            root.performAction(action)
        } catch (e: Throwable) {
            Log.w(TAG, "Error en performScroll: ${e.message}")
            false
        }
    }

    /**
     * Despacha un gesto de deslizamiento vertical (Swipe Up).
     */
    fun performSwipeUp(startX: Float = 500f, startY: Float = 1400f, endY: Float = 400f, durationMs: Long = 300): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            Log.w(TAG, "Error en performSwipeUp: ${e.message}")
            false
        }
    }

    /**
     * Acción global BACK protegida por bloqueo estricto para evitar retrocesos accidentales.
     */
    fun pressBack(): Boolean {
        if (isBackNavigationLocked) {
            Log.w(TAG, "🛑 [BLOQUEO ESTRICTO] Comando GLOBAL_ACTION_BACK interceptado y cancelado para proteger la automatización.")
            return false
        }
        Log.d(TAG, "Ejecutando GLOBAL_ACTION_BACK permitido.")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        private const val TAG = "BotAutomationService"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive = _isServiceActive.asStateFlow()

        var instance: BotAutomationService? = null
            private set
    }
}
