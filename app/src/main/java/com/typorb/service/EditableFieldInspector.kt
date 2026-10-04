package com.typorb.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Node predicates used to decide whether the Typorb should be visible: it only ever appears while a
 * genuinely editable field owns focus.
 */
object EditableFieldInspector {

    /** Android widget class names that are editable even when flags are missing (custom WebViews). */
    private val EDITABLE_CLASS_NAMES = setOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView",
        "android.webkit.WebView",
    )

    /**
     * `true` when [node] is an editable text input.
     *
     * `AccessibilityNodeInfo.isTextEditable()` is a hidden API, so the public `isEditable` flag plus a
     * class-name check is what reliably covers EditText, AutoCompleteTextView and WebView inputs.
     */
    fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isEditable) return true
        return node.className?.toString() in EDITABLE_CLASS_NAMES && node.isFocusable
    }

    /**
     * Finds the node that currently holds input focus, preferring an explicit focus input and falling
     * back to a depth-first search for the first editable field.
     */
    fun findFocusedEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (isEditable(focused)) return focused
        }
        return findFirstEditable(root, remainingDepth = MAX_SEARCH_DEPTH)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, remainingDepth: Int): AccessibilityNodeInfo? {
        if (remainingDepth <= 0) return null
        if (isEditable(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findFirstEditable(child, remainingDepth - 1)
            if (match != null) return match
        }
        return null
    }

    /** Bounds of the focused field, or `null` when it has no usable rectangle. */
    fun focusedFieldRect(root: AccessibilityNodeInfo?): Rect? {
        val node = root?.takeIf { isEditable(it) } ?: return null
        val bounds = Rect()
        // The SDK exposes this as a void out-parameter method, hence the post-check.
        node.getBoundsInScreen(bounds)
        return if (bounds.width() > 0 && bounds.height() > 0) bounds else null
    }

    private const val MAX_SEARCH_DEPTH = 12
}