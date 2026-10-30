package com.botillon.flowscribe.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.botillon.flowscribe.data.InsertMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service d'accessibilité minimal : il ne lit rien en continu, il ne fait que
 * localiser le champ de saisie actif au moment de l'insertion et y écrire le texte.
 */
class DictationAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            event.packageName?.let { lastPackage = it.toString() }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    suspend fun insertAtCursor(text: String, mode: InsertMode): InsertionResult {
        if (text.isBlank()) return InsertionResult.Failed("Texte vide")
        return withContext(Dispatchers.Main.immediate) {
            runCatching { performInsert(text, mode) }
                .getOrElse { InsertionResult.Failed(it.message ?: "Insertion impossible") }
        }
    }

    private fun performInsert(text: String, mode: InsertMode): InsertionResult {
        val node = findFocusedEditable() ?: return InsertionResult.NoField
        if (isSecure(node)) return InsertionResult.SecureField

        val existing = node.text?.toString().orEmpty()
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd
        val start = if (selectionStart in 0..existing.length) selectionStart else existing.length
        val end = if (selectionEnd in start..existing.length) selectionEnd else start

        val prefix = existing.substring(0, start)
        val suffix = existing.substring(end)
        val insertion = decorate(text, prefix)
        val newText = prefix + insertion + suffix
        val cursor = prefix.length + insertion.length

        if (mode == InsertMode.AUTO || mode == InsertMode.SET_TEXT) {
            if (setText(node, newText, cursor)) {
                return InsertionResult.Success("champ", insertion.length)
            }
            if (mode == InsertMode.SET_TEXT) {
                return InsertionResult.Failed("Ce champ refuse l'écriture directe")
            }
        }

        if (paste(node, insertion)) {
            return InsertionResult.Success("collage", insertion.length)
        }
        return InsertionResult.Failed("Ce champ refuse l'insertion")
    }

    private fun setText(node: AccessibilityNodeInfo, newText: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val applied = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!applied) return false

        runCatching { node.refresh() }
        val current = node.text?.toString()
        if (current != null && current != newText) return false

        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
        return true
    }

    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val previous = runCatching {
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
            } else null
        }.getOrNull()

        clipboard.setPrimaryClip(ClipData.newPlainText("FlowScribe", text))
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        var pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            // Dernier recours : coller sur le nœud actif retrouvé après rafraîchissement.
            runCatching { node.refresh() }
            pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                .getOrDefault(false)
        }
        if (previous != null && previous != text) {
            // On restaure le presse-papiers précédent pour ne pas le remplacer durablement.
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", previous)) }
        }
        return pasted
    }

    private fun decorate(text: String, prefix: String): String {
        if (prefix.isEmpty()) return text
        val last = prefix.last()
        val firstInserted = text.firstOrNull() ?: return text
        val needsSpace = !last.isWhitespace() && last !in "([{«\"'" && firstInserted !in ".,;:!?…)]}»\"'"
        return if (needsSpace) " $text" else text
    }

    private fun isSecure(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val type = node.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        runCatching {
            windows.forEach { window -> window.root?.let { roots.add(it) } }
        }

        for (root in roots) {
            val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (focused != null && focused.isEditable) return focused
        }
        for (root in roots) {
            val found = findEditable(root, 0)
            if (found != null) return found
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 18) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (index in 0 until minOf(node.childCount, 24)) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "FlowScribeA11y"

        @Volatile
        var instance: DictationAccessibilityService? = null
            private set

        @Volatile
        var lastPackage: String? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}

sealed interface InsertionResult {
    data class Success(val method: String, val chars: Int) : InsertionResult
    data object NoService : InsertionResult
    data object NoField : InsertionResult
    data object SecureField : InsertionResult
    data class Failed(val reason: String) : InsertionResult
}
