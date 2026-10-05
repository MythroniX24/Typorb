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
 * ## Why this class is the fragile part of the app
 *
 * Everything else — recording, transcription, the overlay — failing is loud and visible. Injection
 * failing is silent: the pill says something reassuring, the transcript is stored in history, and
 * the user's words simply never appear where they were typing. So every route here is *verified*
 * rather than trusted, because `performAction` returning `true` does not mean the field accepted the
 * text — plenty of real apps return `true` and drop it.
 *
 * The strategy, in order:
 *  1. **`ACTION_SET_TEXT` into the field captured when the user tapped the orb.** The node is
 *     remembered at tap time on purpose: dictation takes seconds, and by the time the transcript is
 *     ready the user has often moved focus, opened a sheet, or the IME has swapped windows — so a
 *     node looked up *after* processing is regularly the wrong one, or gone. The remembered one is
 *     the field the user was demonstrably typing into.
 *  2. **Append, never overwrite.** `ACTION_SET_TEXT` replaces the field's entire contents. Dictating
 *     into a half-written message and having the half-written message deleted is worse than not
 *     typing at all, so the existing text is read first and the transcript is added to it.
 *  3. **Verify the write** by reading the field back. A route that did not change the field is
 *     treated as a failure and the next route is tried, rather than reporting success.
 *  4. **`ACTION_PASTE`** from the clipboard, for fields that refuse set-text.
 *  5. **`ACTION_FOCUS` then `ACTION_SET_TEXT` again**, for fields that refuse a bare set-text until
 *     they are focused. WhatsApp's message box behaves exactly this way, and it is the single most
 *     common place a dictation is expected to land.
 *  6. If none of that works the text is **left on the clipboard** and the pill says so. The words
 *     are the whole point of the feature; losing them is the one outcome never acceptable here.
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
         * `ACTION_FOCUS` followed by `ACTION_SET_TEXT`.
         *
         * Several fields — most visibly WhatsApp's message box — refuse `ACTION_SET_TEXT` until they
         * hold input focus themselves, and report the refusal as a plain `false` with nothing in the
         * log. Asking for focus first and setting the text immediately after is what makes the set-text
         * route work there at all.
         */
        FOCUS_THEN_SET_TEXT,

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

    /**
     * The field the user was typing into when they tapped the orb, plus the text it held then.
     *
     * Captured by [rememberFocus] on the way *into* recording, because that is the last moment the
     * answer is certainly right. Holding the text as well as the node is what lets a later append
     * tell the difference between "the field was empty" and "the user had already written half a
     * sentence" — the two need different joining punctuation.
     */
    data class CapturedField(
        val node: AccessibilityNodeInfo,
        val textBefore: String,
        val source: EditableFieldInspector.Source,
    )

    @Volatile
    private var captured: CapturedField? = null

    /**
     * Remembers the field the user is in right now.
     *
     * Called the moment the orb is tapped to start recording. A failed capture is not an error — the
     * injector still re-probes at injection time — it just means the fallback is all there is.
     */
    fun rememberFocus() {
        val focus = runCatching {
            EditableFieldInspector.probe(service, service.getRootInActiveWindow())
        }.getOrNull()
        val node = focus?.node
        captured = if (node == null) {
            null
        } else {
            CapturedField(
                node = node,
                textBefore = currentTextOf(node),
                source = focus.source,
            )
        }
    }

    /** Drops the remembered field; called when a dictation finishes or is abandoned. */
    fun forgetFocus() {
        captured = null
    }

    /** Runs the full injection pipeline. Never throws; failures surface as [Method.NONE]. */
    suspend fun inject(text: String): Result = withContext(Dispatchers.Default) {
        val content = text.trim()
        if (content.isEmpty()) {
            return@withContext Result(Method.NONE, focusedFieldFound = false, detail = "empty transcript")
        }

        // The field the user was in when they tapped, if it is still alive and still editable.
        val remembered = captured?.takeIf { runCatching { it.node.isEditableNode() }.getOrDefault(false) }

        val focus = remembered?.let { EditableFieldInspector.Focus(it.node, it.source) }
            ?: awaitFocusedField()
        val node = focus?.node
        if (focus == null || node == null) {
            return@withContext failed(
                content = content,
                focusedFieldFound = false,
                detail = "no editable field found",
            )
        }

        val target = "via ${focus.source.label}"
        val baseline = remembered?.textBefore ?: currentTextOf(node)
        val attempts = mutableListOf<String>()

        try {
            if (writeAndVerify(node, content, baseline, attempts, Method.ACTION_SET_TEXT)) {
                return@withContext success(Method.ACTION_SET_TEXT, target, attempts)
            }
            if (pasteViaClipboard(node, content)) {
                return@withContext success(Method.CLIPBOARD_PASTE, target, attempts)
            }
            attempts += "paste refused"
            if (focusThenSetText(node, content, baseline, attempts)) {
                return@withContext success(Method.FOCUS_THEN_SET_TEXT, target, attempts)
            }
            return@withContext failed(
                content = content,
                focusedFieldFound = true,
                detail = "all routes failed $target (${attempts.joinToString("; ")})",
            )
        } catch (error: Exception) {
            Log.w(TAG, "Text injection failed", error)
            return@withContext failed(
                content = content,
                focusedFieldFound = true,
                detail = "injection threw ${error::class.java.simpleName} (${attempts.joinToString("; ")})",
            )
        }
    }

    private fun success(method: Method, target: String, attempts: List<String>): Result {
        val trail = if (attempts.isEmpty()) "" else " after ${attempts.joinToString("; ")}"
        return Result(method, focusedFieldFound = true, detail = "inserted $target$trail")
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

    /**
     * Writes [content] into [node] and confirms the field actually changed.
     *
     * The read-back is the whole point. `ACTION_SET_TEXT` is documented to return whether the action
     * was *performed*, and a good number of apps — WhatsApp among them, for the message box while the
     * send button is in a transient state — perform it and drop the text. Trusting the return value
     * is how a dictation reported success and typed nothing.
     */
    private suspend fun writeAndVerify(
        node: AccessibilityNodeInfo,
        content: String,
        baseline: String,
        attempts: MutableList<String>,
        method: Method,
    ): Boolean {
        val target = join(existing = baseline, addition = content)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
        }
        val performed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
        if (!performed) {
            attempts += "${method.name} refused"
            return false
        }
        if (verifyWrite(node, content)) return true
        attempts += "${method.name} reported ok but the field did not change"
        return false
    }

    /**
     * `true` when [node]'s text now contains [content].
     *
     * Compared on letters and digits only. Field text comes back with the IME's own decorations —
     * composing underlines, trailing spaces, autocorrect substitutions — and a strict equality check
     * would call a successful write a failure and paste the same words in a second time.
     */
    private suspend fun verifyWrite(node: AccessibilityNodeInfo, content: String): Boolean {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (containsLoose(currentTextOf(node), content)) return true
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_SETTLE_MS)
        }
        return false
    }

    private fun containsLoose(actual: String, expected: String): Boolean =
        TranscriptJoin.landed(actual, expected)

    /**
     * The text a field holds, tolerating the node going stale mid-read.
     *
     * `node.text` is also `null` on fields that hold no text at all, which is the correct reading of
     * "this field is empty" rather than a failure.
     */
    private fun currentTextOf(node: AccessibilityNodeInfo): String =
        runCatching { node.text?.toString().orEmpty() }.getOrDefault("")

    /** Whether the node is *still* an editable field, rather than having been recycled into something else. */
    private fun AccessibilityNodeInfo.isEditableNode(): Boolean =
        runCatching { EditableFieldInspector.isEditable(this) }.getOrDefault(false)

    /**
     * Combines what is already in the field with the dictated words.
     *
     * A line break when the field already holds text: the user was part-way through a thought, and
     * running the transcript straight onto the end of it produces one unreadable run-on line.
     */
    internal fun join(existing: String, addition: String): String =
        TranscriptJoin.join(existing, addition)

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

    /**
     * Focuses [node] and immediately retries the set-text.
     *
     * Run inside [NonCancellable] for the same reason the clipboard swap is: once the field has been
     * asked to take focus, abandoning the write half-way would leave the user's caret somewhere new
     * with their words still nowhere.
     */
    private suspend fun focusThenSetText(
        node: AccessibilityNodeInfo,
        content: String,
        baseline: String,
        attempts: MutableList<String>,
    ): Boolean = withContext(NonCancellable) {
        val focused = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }.getOrDefault(false)
        if (!focused) {
            attempts += "focus refused"
            return@withContext false
        }
        // The focus change and the write are two binder round-trips; a field that has just been told
        // to take focus can still be settling when the next one lands.
        delay(FOCUS_SETTLE_MS)
        writeAndVerify(node, content, baseline, attempts, Method.FOCUS_THEN_SET_TEXT)
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

        /** Read-back window: long enough for a slow app to lay the text out, short enough to retry a paste. */
        const val VERIFY_ATTEMPTS = 3
        const val VERIFY_SETTLE_MS = 70L

        /** Gap between asking a field to take focus and writing into it. */
        const val FOCUS_SETTLE_MS = 60L

    }

}
