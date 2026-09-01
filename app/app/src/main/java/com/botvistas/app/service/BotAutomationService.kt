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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.i(TAG, "BotAutomationService conectado y listo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitoreo reactivo de eventos
    }

    override fun onInterrupt() {
        Log.w(TAG, "BotAutomationService interrumpido.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceActive.value = false
        Log.i(TAG, "BotAutomationService destruido.")
    }

    // --- Métodos de búsqueda e interacción con nodos ---

    /**
     * Busca nodos por coincidencia de texto (insensible a mayúsculas/minúsculas).
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesRecursive(root) { node ->
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)
        }.forEach { result.add(it) }
        return result
    }

    /**
     * Busca nodos por ID de recurso de vista (ej. "com.google.android.youtube:id/search_edit_text").
     */
    fun findNodesByViewId(viewId: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
    }

    /**
     * Busca nodos por descripción de contenido.
     */
    fun findNodesByContentDescription(desc: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesRecursive(root) { node ->
            node.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
        }.forEach { result.add(it) }
        return result
    }

    /**
     * Busca todos los nodos que cumplan una condición.
     */
    fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        findNodesRecursive(root, predicate).forEach { result.add(it) }
        return result
    }

    /**
     * Encuentra el primer nodo que cumpla la condición.
     */
    fun findFirstNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFirstNodeRecursive(root, predicate)
    }

    private fun findFirstNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstNodeRecursive(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findNodesRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val matching = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) {
            matching.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            matching.addAll(findNodesRecursive(child, predicate))
        }
        return matching
    }

    /**
     * Hace clic en un nodo o busca en sus ancestros/hijos hasta ejecutar el clic.
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false

        // 1. Intentar clic directo
        if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Intentar clic en ancestros
        var parent = current.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }

        // 3. Intentar clic mediante gesto de toque en las coordenadas del nodo
        val rect = Rect()
        current.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            return performTapGesture(centerX, centerY)
        }

        return false
    }

    /**
     * Realiza un tap por gesto en coordenadas específicas de pantalla.
     */
    fun performTapGesture(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Establece texto en un campo editable.
     */
    fun inputText(node: AccessibilityNodeInfo?, text: String): Boolean {
        val target = node ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Realiza desplazamiento vertical hacia adelante o atrás.
     */
    fun performScroll(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val scrollableNodes = findNodes { it.isScrollable }
        for (node in scrollableNodes) {
            if (node.performAction(action)) {
                return true
            }
        }
        return root.performAction(action)
    }

    /**
     * Despacha un gesto de deslizamiento vertical (Swipe Up).
     */
    fun performSwipeUp(startX: Float = 500f, startY: Float = 1400f, endY: Float = 400f, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        private const val TAG = "BotAutomationService"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive = _isServiceActive.asStateFlow()

        var instance: BotAutomationService? = null
            private set
    }
}
