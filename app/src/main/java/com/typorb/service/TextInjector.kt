package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
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
 * failing is silent: the pill says something reassuring, the transcript is stored in history, and the
 * user's words simply never appear where they were typing. So every route here is *verified* rather
 * than trusted, because `performAction` returning `true` does not mean the field accepted the text —
 * plenty of real apps return `true` and drop it.
 *
 * ## What this file gets wrong before, and how it is fixed
 *
 * 1. **The read-back compared against a snapshot.** `AccessibilityNodeInfo` is a *snapshot*: `.text`
 *    is the field's content as it was when the node was fetched, and `performAction` does not update
 *    it. Every verification therefore re-read the text from before the write, decided the write had
 *    changed nothing, and moved on to the next route — writing the same words twice, and finally
 *    reporting a failure for a dictation that had in fact landed. Since a node is only brought up to
 *    date by `refresh()`, every read-back now starts with one.
 * 2. **The field was rebuilt around content it had never read.** `ACTION_SET_TEXT` replaces the whole
 *    field, and the text it put back came from the node — which, for a hint, a placeholder, or a
 *    password, is not the user's content at all. A rebuild now only happens for a field whose contents
 *    were **proven** by a refresh; otherwise the route used is paste, which inserts at the field's own
 *    caret and is structurally incapable of inventing or deleting text.
 * 3. **The target was chosen before the dictation, and never re-checked.** A node picked up seconds
 *    ago can be stale by the time the words are ready — a recycled editor still answers questions and
 *    silently discards writes. The field is now resolved at commit time from the system's own input
 *    focus ([EditableFieldInspector.probe]), with the tap-time capture kept as the fallback for when
 *    the keyboard has since closed.
 * 4. **A read-back only ever consulted the node the write went to.** If that node had gone stale,
 *    its text could not be read even though the sentence was sitting in the field — so the write was
 *    called a failure, the next route wrote the same words again, and the user got them twice. The
 *    read-back now also asks the field the *system* is pointing at ([liveFocusedNode]), which is a
 *    different question from "what does this old node say" and is the only one of the two that
 *    cannot be out of date.
 *
 * ## The ladder is tried twice, but never to double a sentence
 *
 * A field that refuses everything is usually a field that was not ready yet — an app re-laying out
 * after the keyboard closed. [LADDER_PASSES] therefore runs the whole ladder once more after a short
 * beat, and that second pass is safe precisely because it only ever happens when **no route reported a
 * write at all**. The moment any route says it wrote something, the ladder stops being retried: a
 * second write on top of one the field will not confirm is how a dictation lands twice, and twice is
 * worse than a sentence left on the clipboard for the user to paste.
 *
 * The route ladder, in order:
 *  1. **The platform's own input connection** on Android 13+ ([commitViaInputMethod]) — the same
 *     `commitText` a keyboard uses, straight into the field at the caret.
 *  2. **`ACTION_SET_TEXT`** — only when the field's current content could be read. Appends rather
 *     than overwrites, and is verified.
 *  3. **`ACTION_PASTE`** from the clipboard — inserts at the caret, never reads what is already there.
 *  4. **`ACTION_FOCUS` then repeat**, for fields that refuse anything until they hold focus
 *     themselves. WhatsApp's message box behaves exactly this way.
 *  5. If none of that works the text is **left on the clipboard** and the pill says so. The words are
 *     the whole point of the feature; losing them is the one outcome never acceptable here.
 *
 * Everything runs off the main thread because [AccessibilityNodeInfo.performAction] blocks while the
 * target app handles the event.
 */
class TextInjector(private val service: AccessibilityService) {

    enum class Method {
        /**
         * Committed through the accessibility input connection — the keyboard's own route.
         *
         * Only offered on Android 13+, where the platform exposes it to a service that declares
         * `flagInputMethodEditor`. It needs no node and no clipboard, and it addresses whatever the
         * system holds as the input target, which is by definition the field the user tapped.
         */
        A11Y_IME,

        /** Typed straight into the field. */
        ACTION_SET_TEXT,

        /** Clipboard swap + `ACTION_PASTE`. */
        CLIPBOARD_PASTE,

        /**
         * `ACTION_FOCUS` followed by the text route.
         *
         * Several fields — most visibly WhatsApp's message box — refuse both a bare set-text and a
         * paste until they hold input focus themselves, and report the refusal as a plain `false`
         * with nothing in the log.
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
     * @param detail short description of what happened, shown in the debug console. Includes which
     *   field it was and how it was found, because "the words went somewhere" and "the words went
     *   nowhere" look identical to the user and need completely different fixes.
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
     * answer is certainly right. It is the *fallback* at injection time, not the first choice: a node
     * held across several seconds of dictation routinely goes stale, and only a lookup made when the
     * words are ready can prove the field is still there.
     */
    data class CapturedField(
        val node: AccessibilityNodeInfo,
        val textBefore: String,
        val source: EditableFieldInspector.Source,
    )

    /**
     * What one route did.
     *
     * @param method the route that delivered the text, or `null` when this one did not.
     * @param performed whether the field accepted a *write*. Kept apart from [method] because it is
     *   the difference between "refused" and "took it and hid it", which is the difference between a
     *   safe retry and a duplicated sentence.
     */
    private data class RouteResult(
        val method: Method? = null,
        val performed: Boolean = false,
    )

    /** The injection target and everything known about it, resolved at commit time. */
    private data class Target(
        /** The field to write into. */
        val node: AccessibilityNodeInfo,
        /** The field's current content, or `null` when it could not be proven. */
        val text: String?,
        /** Human-readable: which field, and which lookup produced it. */
        val description: String,
    ) {
        /** `true` when a rebuild of the whole field is safe because its content was actually read. */
        val contentProven: Boolean get() = text != null
    }

    @Volatile
    private var captured: CapturedField? = null

    /**
     * Remembers the field the user is in right now.
     *
     * Called the moment the orb is tapped to start recording. A failed capture is not an error — the
     * injector still re-probes at injection time — it just means there is no fallback if focus moves
     * away during the dictation.
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
                textBefore = textOf(node),
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
        injectWithRetry(content)
    }

    /**
     * The ladder, and the single retry it is allowed.
     *
     * Kept apart from [inject] so the target can be resolved again for the second pass: a [Target] is
     * a node *plus* the text that node held, and both are facts about an instant — reusing the first
     * pass's target for a retry would retry with the same stale answer that failed.
     */
    private suspend fun injectWithRetry(content: String): Result {
        var target = resolveTarget()
            ?: return failed(content, focusedFieldFound = false, detail = "no editable field found")

        val attempts = mutableListOf<String>()
        try {
            repeat(LADDER_PASSES) { pass ->
                val outcome = runLadder(target, content, attempts)
                outcome.method?.let { return success(it, target, attempts) }
                // A write the field will not confirm. Writing again is how one sentence lands twice,
                // and twice is worse than a sentence left on the clipboard for the user to paste.
                if (outcome.performed) {
                    return failed(
                        content = content,
                        focusedFieldFound = true,
                        detail = "the field reported a write it will not show, so it was not written " +
                            "again · ${target.description} (${attempts.joinToString("; ")})",
                    )
                }
                if (pass < LADDER_PASSES - 1) {
                    attempts += "nothing was even written; retrying the ladder"
                    delay(LADDER_RETRY_DELAY_MS)
                    // The field may only have been unreachable at the first pass — a window changing
                    // under the keyboard, an editor recreated when the IME moved. A fresh lookup is
                    // the whole point of a second pass.
                    target = resolveTarget() ?: target
                }
            }

            return failed(
                content = content,
                focusedFieldFound = true,
                detail = "all routes refused · ${target.description} (${attempts.joinToString("; ")})",
            )
        } catch (error: Exception) {
            Log.w(TAG, "Text injection failed", error)
            return failed(
                content = content,
                focusedFieldFound = true,
                detail = "injection threw ${error::class.java.simpleName} · ${target.description} " +
                    "(${attempts.joinToString("; ")})",
            )
        }
    }

    private fun success(method: Method, target: Target, attempts: List<String>): Result {
        val trail = if (attempts.isEmpty()) "" else " after ${attempts.joinToString("; ")}"
        return Result(method, focusedFieldFound = true, detail = "inserted via ${method.name} · " +
            "${target.description}$trail")
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

    // ------------------------------------------------------------------ choosing the field

    /**
     * Resolves the field to type into, *now*.
     *
     * The system's own input focus comes first: it is a fact about this instant, it cannot be a stale
     * node, and it is the same answer a keyboard would use. The field captured when the orb was tapped
     * is the fallback — it is the only thing left when the keyboard has closed since, and it is kept
     * out of the way otherwise so a node that has been sitting around for ten seconds cannot win over
     * a live one.
     *
     * A node's content is only reported as readable when `refresh()` says the node is still current.
     * That single call is what separates "this field is empty" from "this field will not tell me what
     * it holds", and the two need different routes.
     */
    private suspend fun resolveTarget(): Target? {
        val fresh = awaitFocusedField()
        val node = fresh?.node ?: captured?.node?.takeIf { isCurrent(it) } ?: return null
        val description = if (fresh?.node != null) {
            "${describe(node)} · live focus (${fresh.source.label})"
        } else {
            "${describe(node)} · captured at tap time"
        }
        val current = runCatching { node.refresh() }.getOrDefault(false)
        val text = if (current) readText(node)?.let { dropHint(node, it) } else null
        return Target(node = node, text = text, description = description)
    }

    /** `true` when the node is still backed by a live window, i.e. a write to it can land. */
    private fun isCurrent(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.refresh() }.getOrDefault(false)

    /**
     * Treats a field's own hint as emptiness.
     *
     * Some apps (and some OEM skins) publish their placeholder as the field's text rather than as
     * `hintText`. Reading that as content is how a dictation ends up prepending "Message" to the
     * user's sentence, so a value that matches the hint is thrown away instead.
     */
    private fun dropHint(node: AccessibilityNodeInfo, text: String): String? {
        val hint = runCatching { node.hintText?.toString() }.getOrNull() ?: return text
        return if (hint.isNotEmpty() && text.equals(hint, ignoreCase = false)) null else text
    }

    /** `field type in package`, short enough for the debug console row. */
    private fun describe(node: AccessibilityNodeInfo): String {
        val className = runCatching { node.className?.toString() }.getOrNull()
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "field"
        val packageName = runCatching { node.packageName?.toString() }.getOrNull() ?: "unknown app"
        return "$className in $packageName"
    }

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
            val focus = runCatching {
                EditableFieldInspector.probe(service, service.getRootInActiveWindow())
            }.getOrNull()
            if (focus?.isFieldFocused == true) return focus
            if (attempt < FOCUS_ATTEMPTS - 1) delay(FOCUS_RETRY_DELAY_MS)
        }
        return null
    }

    // ------------------------------------------------------------------ the routes

    /**
     * The keyboard's own route: commit into the current editor through the accessibility input
     * connection.
     *
     * This is the only route that *cannot* be wrong about which field it targets — it addresses
     * whatever the system holds as the input target, exactly like a real keyboard — and the only one
     * that cannot invent or delete text, because it inserts at the caret.
     *
     * Android 13+ only, and only where the platform grants it: the service has to declare
     * `flagInputMethodEditor` for a connection to exist. Both conditions are checked here and every
     * failure is swallowed, because a missing capability must degrade to the node routes rather than
     * take the dictation down with it.
     */
    private suspend fun commitViaInputMethod(
        text: String,
        target: Target,
        attempts: MutableList<String>,
    ): RouteResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return RouteResult()
        val connection = runCatching { accessibilityInputConnection() }.getOrNull()
        if (connection == null) {
            attempts += "input connection unavailable"
            return RouteResult()
        }
        return withContext(NonCancellable) {
            try {
                connection.commitText(text, 1, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Input connection commit refused", error)
                attempts += "input connection refused"
                return@withContext RouteResult()
            }
            // Verified through the field whenever there is one to ask. The connection itself reports
            // nothing — `commitText` returns void — so without this a commit into a replaced editor
            // would be indistinguishable from one that landed. A commit that threw did not happen;
            // one that returned is treated as a write, because the platform gives no other answer.
            if (verifyWrite(target.node, text)) {
                RouteResult(method = Method.A11Y_IME, performed = true)
            } else {
                attempts += "input connection committed nothing the field can see"
                RouteResult(performed = true)
            }
        }
    }

    /**
     * One pass of the ladder: the keyboard's own route, then set-text, then paste, then a focused
     * retry. Returns as soon as a route lands.
     */
    private suspend fun runLadder(
        target: Target,
        content: String,
        attempts: MutableList<String>,
    ): RouteResult {
        var performed = false

        commitViaInputMethod(content, target, attempts).let { result ->
            result.method?.let { return result }
            performed = performed || result.performed
        }

        // A rebuild of the whole field is only offered when its content was proven. Otherwise the
        // first write route is paste, which cannot prepend a placeholder or delete text it never read.
        if (target.contentProven) {
            writeAndVerify(target.node, content, target.text!!, attempts, Method.ACTION_SET_TEXT)
                .let { result ->
                    result.method?.let { return result }
                    performed = performed || result.performed
                }
        } else {
            attempts += "field content unreadable, rebuild skipped"
        }

        pasteViaClipboard(target.node, content, attempts).let { result ->
            result.method?.let { return result }
            performed = performed || result.performed
        }

        focusThenWrite(target, content, attempts).let { result ->
            result.method?.let { return result }
            performed = performed || result.performed
        }

        return RouteResult(performed = performed)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun accessibilityInputConnection(): InputMethod.AccessibilityInputConnection? {
        val inputMethod = service.inputMethod ?: return null
        if (!inputMethod.currentInputStarted) return null
        return inputMethod.currentInputConnection
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
    ): RouteResult {
        val target = TranscriptJoin.join(existing = baseline, addition = content)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
        }
        val performed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
        if (!performed) {
            attempts += "${method.name} refused"
            return RouteResult()
        }
        if (verifyWrite(node, content)) return RouteResult(method = method, performed = true)
        attempts += "${method.name} reported ok but the field did not change"
        return RouteResult(performed = true)
    }

    /**
     * `true` when [node]'s text now contains [content].
     *
     * **`refresh()` first, always.** `AccessibilityNodeInfo` carries the field's text as it was when
     * the node was fetched, and `performAction` does not update it — so reading `.text` straight after
     * a write answers with the value from *before* the write. Every verification used to do exactly
     * that, which turned a successful write into "the field did not change" and sent the pipeline on
     * to write the same sentence a second time.
     *
     * Compared on letters and digits only. Field text comes back with the IME's own decorations —
     * composing underlines, trailing spaces, autocorrect substitutions — and a strict equality check
     * would call a successful write a failure.
     */
    private suspend fun verifyWrite(node: AccessibilityNodeInfo, content: String): Boolean {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (showsText(node, content)) return true
            // The node the write went to is one answer. The field the *system* is pointing at right
            // now is a better one: it is a live lookup, it cannot be a node left over from before
            // the dictation, and a write that landed in a freshly recreated editor is only visible
            // there. Without this question, that write looked like a failure and the next route wrote
            // the same sentence a second time.
            val live = liveFocusedNode()
            if (live != null && live != node && showsText(live, content)) return true
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_SETTLE_MS)
        }
        return false
    }

    /** `true` when [node], re-read from the live window, now contains [content]. */
    private fun showsText(node: AccessibilityNodeInfo, content: String): Boolean {
        runCatching { node.refresh() }
        return TranscriptJoin.landed(textOf(node), content)
    }

    /** The editable field holding input focus at this instant, or `null`. */
    private fun liveFocusedNode(): AccessibilityNodeInfo? = runCatching {
        EditableFieldInspector.probe(service, service.getRootInActiveWindow()).node
    }.getOrNull()

    /**
     * The text a field holds, tolerating the node going stale mid-read.
     *
     * `node.text` is also `null` on fields that hold no text at all, which is the correct reading of
     * "this field is empty" rather than a failure.
     */
    private fun textOf(node: AccessibilityNodeInfo): String = readText(node).orEmpty()

    /** `node.text`, or `null` when the node refused to answer. */
    private fun readText(node: AccessibilityNodeInfo): String? =
        runCatching { node.text?.toString() }.getOrNull()

    /**
     * Clipboard paste + restore run inside [NonCancellable].
     *
     * The user can dismiss the pill (or the keyboard) mid-dictation, which cancels the recording
     * coroutine. If that cancellation landed between "copy text in" and "restore clipboard", the
     * dictated text would be left in the user's clipboard — so once we start, we always finish.
     */
    private suspend fun pasteViaClipboard(
        node: AccessibilityNodeInfo,
        text: String,
        attempts: MutableList<String>,
    ): RouteResult = withContext(NonCancellable) paste@{
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@paste RouteResult()

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
            if (!pasted) {
                attempts += "paste refused"
                return@paste RouteResult()
            }
            // Settled before restoring as well: the app reads the clipboard on its own schedule, and
            // putting the old value back underneath it is how a paste turns into the wrong text.
            delay(PASTE_SETTLE_MS)
            if (verifyWrite(node, text)) return@paste RouteResult(Method.CLIPBOARD_PASTE, performed = true)
            attempts += "paste reported ok but the field did not change"
            RouteResult(performed = true)
        } finally {
            restoreClipboard(clipboard, savedClip)
        }
    }

    /**
     * Focuses the field and retries the write.
     *
     * The route a *proven* field needs before it accepts anything (WhatsApp's message box, and every
     * Compose text field). For a field whose contents could not be read the retry is a paste instead:
     * focusing is safe to ask for, rebuilding a field around text nobody read is not.
     *
     * Run inside [NonCancellable] for the same reason the clipboard swap is: once the field has been
     * asked to take focus, abandoning the write half-way would leave the user's caret somewhere new
     * with their words still nowhere.
     */
    /** @return the route this attempt delivered by, or a result that says what it refused to do. */
    private suspend fun focusThenWrite(
        target: Target,
        content: String,
        attempts: MutableList<String>,
    ): RouteResult = withContext(NonCancellable) {
        val focused = runCatching {
            target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }.getOrDefault(false)
        if (!focused) {
            attempts += "focus refused"
            return@withContext RouteResult()
        }
        // The focus change and the write are two binder round-trips; a field that has just been told
        // to take focus can still be settling when the next one lands.
        delay(FOCUS_SETTLE_MS)
        if (target.contentProven) {
            writeAndVerify(target.node, content, target.text!!, attempts, Method.FOCUS_THEN_SET_TEXT)
        } else {
            // Nothing was ever proven about this field's contents, so the focused retry is a paste as
            // well: focusing a field is safe to ask for, rebuilding one around text nobody read is not.
            pasteViaClipboard(target.node, content, attempts)
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

        /**
         * How many times the whole ladder runs.
         *
         * Twice, and the second pass only when the first wrote nothing at all: a field that was not
         * ready yet is worth one more attempt, a field that took the text and hid it is not.
         */
        const val LADDER_PASSES = 2

        /** How long to wait before the second pass, so a field mid-relayout has time to settle. */
        const val LADDER_RETRY_DELAY_MS = 260L

        /**
         * How long to leave the dictated text on the clipboard before putting the user's own value
         * back. The app reads the clipboard asynchronously, and 80ms was short enough that a busy app
         * could paste whatever was there before.
         */
        const val PASTE_SETTLE_MS = 260L

        /** Read-back window: long enough for a slow app to lay the text out, short enough to retry a paste. */
        const val VERIFY_ATTEMPTS = 3
        const val VERIFY_SETTLE_MS = 80L

        /** Gap between asking a field to take focus and writing into it. */
        const val FOCUS_SETTLE_MS = 60L
    }
}
