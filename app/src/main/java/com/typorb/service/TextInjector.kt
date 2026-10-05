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
 * Strategy, in order:
 *  1. `ACTION_SET_TEXT` with an [Bundle] — instant, keeps the undo stack intact, and does not touch
 *     the clipboard at all.
 *  2. If the target refuses that (web views, Compose text fields, hardened apps): save the clipboard,
 *     put the text there, run `ACTION_PASTE`, then put the user's original clipboard back.
 *  3. If there is no field to write into, or both of the above are refused, the dictated text is
 *     **left on the clipboard**. The words are the whole point of the feature; a failure to deliver
 *     them to a field is not a reason to make them disappear, and a plain "couldn't insert" left the
 *     user with nothing at all to show for the dictation.
 *
 * Everything runs off the main thread because [AccessibilityNodeInfo.performAction] blocks while the
 * target app handles the event.
 */
class TextInjector(private val service: AccessibilityService) {

    enum class Method {
        /** Typed straight into the field. */
        ACTION_SET_TEXT,

        /** Clipboard swap + `ACTION_PASTE`. */
        CLIPBOARD_PASTE,

        /**
         * Not inserted, but handed to the clipboard for a manual long-press paste.
         *
         * Distinct from [NONE] precisely so the overlay can say *where* the text is instead of
         * reporting that the dictation failed.
         */
        COPIED_TO_CLIPBOARD,

        /** Nothing was delivered anywhere. */
        NONE,
    }

    /**
     * @param focusedFieldFound whether an editable field could be found at all — the single most
     *   useful fact when a report says "nothing appeared".
     * @param detail short description of what happened, shown in the debug console.
     */
    data class Result(
        val method: Method,
        val focusedFieldFound: Boolean,
        val detail: String,
    )

    /** Runs the full injection pipeline. Never throws; failures surface as [Method.NONE]. */
    suspend fun inject(text: String): Result = withContext(Dispatchers.Default) {
        val content = text.trim()
        if (content.isEmpty()) {
            return@withContext Result(Method.NONE, focusedFieldFound = false, detail = "empty transcript")
        }

        val focus = awaitFocusedField()
        val node = focus?.node
        if (focus == null || node == null) {
            return@withContext failed(
                content = content,
                focusedFieldFound = false,
                detail = "no editable field found",
            )
        }

        val target = "via ${focus.source.label}"
        try {
            if (setTextDirectly(node, content)) {
                return@withContext Result(Method.ACTION_SET_TEXT, true, "set text $target")
            }
            if (pasteViaClipboard(node, content)) {
                return@withContext Result(Method.CLIPBOARD_PASTE, true, "pasted $target")
            }
            return@withContext failed(
                content = content,
                focusedFieldFound = true,
                detail = "field refused set-text and paste $target",
            )
        } catch (error: Exception) {
            Log.w(TAG, "Text injection failed", error)
            return@withContext failed(
                content = content,
                focusedFieldFound = true,
                detail = "injection threw ${error::class.java.simpleName}",
            )
        }
    }

    /** Node currently holding input focus, if it is editable. */
    fun focusedEditableNode(): AccessibilityNodeInfo? =
        EditableFieldInspector.focusedEditableNode(service, service.getRootInActiveWindow())

    /**
     * Last resort: hand the transcript to the clipboard.
     *
     * The clipboard is deliberately *not* restored afterwards — that is the deliverable. The pill
     * says so, so a manual paste is an instruction rather than a guess.
     */
    private fun failed(content: String, focusedFieldFound: Boolean, detail: String): Result {
        val copied = copyToClipboard(content)
        return Result(
            method = if (copied) Method.COPIED_TO_CLIPBOARD else Method.NONE,
            focusedFieldFound = focusedFieldFound,
            detail = if (copied) "$detail — left on the clipboard" else "$detail — clipboard unavailable",
        )
    }

    private fun copyToClipboard(text: String): Boolean = runCatching {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching false
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        true
    }.getOrDefault(false)

    /**
     * The focused node can momentarily disappear while an IME swaps windows, so retry before giving
     * up — otherwise a fast second tap right after processing would silently drop the text.
     *
     * The window is deliberately ~0.7 s rather than the three quick attempts this used to make: the
     * only cost of waiting is that the pill says "Inserting text…" a little longer, while the cost of
     * giving up early is the user's words going somewhere they did not expect.
     */
    private suspend fun awaitFocusedField(): EditableFieldInspector.Focus? {
        repeat(FOCUS_ATTEMPTS) { attempt ->
            // Asked of the whole screen rather than of one window: while a keyboard is up,
            // `getRootInActiveWindow()` can name the keyboard itself, and hunting inside it for a text
            // field is how a dictation would end up somewhere other than where the user was writing.
            val focus = EditableFieldInspector.probe(service, service.getRootInActiveWindow())
            if (focus.isFieldFocused) return focus
            if (attempt < FOCUS_ATTEMPTS - 1) delay(FOCUS_RETRY_DELAY_MS)
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

        const val FOCUS_ATTEMPTS = 8
        const val FOCUS_RETRY_DELAY_MS = 90L
        const val CLIPBOARD_SETTLE_MS = 140L
        const val PASTE_SETTLE_MS = 80L
    }
}
