package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Writes dictated text into whatever field the user is editing.
 *
 * Strategy:
 *  1. `ACTION_SET_TEXT` with an [Bundle] — instant, keeps the undo stack intact, and does not touch
 *     the clipboard at all.
 *  2. If the target refuses that (web views, Compose text fields, hardened apps): save the clipboard,
 *     put the text there, run `ACTION_PASTE`, then put the user's original clipboard back.
 *
 * Everything runs off the main thread because [AccessibilityNodeInfo.performAction] blocks while the
 * target app handles the event.
 */
class TextInjector(private val service: AccessibilityService) {

    enum class Method { ACTION_SET_TEXT, CLIPBOARD_PASTE, NONE }

    data class Result(val method: Method, val focusedFieldFound: Boolean)

    /** Runs the full injection pipeline. Never throws; failures surface as [Method.NONE]. */
    suspend fun inject(text: String): Result = withContext(Dispatchers.Default) {
        val target = awaitFocusedField() ?: return@withContext Result(Method.NONE, false)
        try {
            if (setTextDirectly(target, text)) {
                return@withContext Result(Method.ACTION_SET_TEXT, true)
            }
            val pasted = pasteViaClipboard(target, text)
            Result(if (pasted) Method.CLIPBOARD_PASTE else Method.NONE, true)
        } catch (error: Exception) {
            Log.w(TAG, "Text injection failed", error)
            Result(Method.NONE, true)
        }
    }

    /** Node currently holding input focus, if it is editable. */
    fun focusedEditableNode(): AccessibilityNodeInfo? =
        EditableFieldInspector.findFocusedEditable(service.getRootInActiveWindow())

    /**
     * The focused node can momentarily disappear while an IME swaps windows, so retry briefly before
     * giving up — otherwise a fast second tap right after processing would silently drop the text.
     */
    private suspend fun awaitFocusedField(): AccessibilityNodeInfo? {
        repeat(FOCUS_ATTEMPTS) {
            EditableFieldInspector.findFocusedEditable(service.getRootInActiveWindow())?.let { return it }
            delay(FOCUS_RETRY_DELAY_MS)
        }
        return null
    }

    private fun setTextDirectly(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
    }

    /**
     * Clipboard paste + restore run inside [NonCancellable].
     *
     * The user can dismiss the pill (or the keyboard) mid-dictation, which cancels the recording
     * coroutine. If that cancellation landed between "copy text in" and "restore clipboard", the
     * dictated text would be left in the user's clipboard — so once we start, we always finish.
     */
    private suspend fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean =
        withContext(NonCancellable) paste@{
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@paste false

            // From Android 10 a background app may be unable to *read* the clipboard; that is fine,
            // we simply have nothing to restore and clear it afterwards instead.
            val savedClip: ClipData? = runCatching { clipboard.primaryClip }.getOrNull()

            try {
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                // The IME reads the clipboard asynchronously; pasting too early pastes the old value.
                delay(CLIPBOARD_SETTLE_MS)
                val pasted = runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
                delay(PASTE_SETTLE_MS)
                pasted
            } finally {
                restoreClipboard(clipboard, savedClip)
            }
        }

    private fun restoreClipboard(clipboard: ClipboardManager, saved: ClipData?) {
        runCatching {
            if (saved != null) {
                clipboard.setPrimaryClip(saved)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        }.onFailure { Log.w(TAG, "Could not restore clipboard", it) }
    }

    private companion object {
        const val TAG = "TextInjector"
        const val CLIP_LABEL = "Typorb"

        const val FOCUS_ATTEMPTS = 3
        const val FOCUS_RETRY_DELAY_MS = 60L
        const val CLIPBOARD_SETTLE_MS = 140L
        const val PASTE_SETTLE_MS = 80L
    }
}