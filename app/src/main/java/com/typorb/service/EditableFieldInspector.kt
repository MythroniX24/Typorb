package com.typorb.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Answers the two questions the rest of Typorb asks about the screen: *is this node an editable text
 * field*, and *which node holds input focus right now*.
 *
 * Both halves are written the way they are because the obvious implementation of each has been
 * measured wrong on a device:
 *
 *  * `AccessibilityNodeInfo.isEditable` is `false` on plenty of real fields — Compose text fields,
 *    WebViews, and several OEM skins never set it. A rule that trusts it alone reports "no field is
 *    focused" for the field the user is looking at, which is one of the ways an orb goes missing.
 *  * The focused node is *not* reachable through `getRootInActiveWindow()` while a keyboard is up.
 *    That call answers for the active window, and during a text-editing session the window holding
 *    the input focus is the keyboard rather than the app underneath it, so the search starts inside
 *    the wrong window — or inside one that can never hold input focus at all. The field the user is
 *    editing is still reachable, but only through a lookup that searches every window:
 *    [AccessibilityService.findFocus].
 */
object EditableFieldInspector {

    /**
     * Android widget class names that are editable even when the node's flags say otherwise.
     *
     * `EditText` is matched by substring, so the whole family comes along for free —
     * `AppCompatEditText`, Material's `TextInputEditText`, MIUI's `MIUIEditText`. These are the
     * editable widgets whose names do *not* contain it, and they have to be listed by hand.
     */
    private val EDITABLE_CLASS_NAMES = setOf(
        "android.webkit.WebView",
        "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView",
    )

    /** Which of [probe]'s lookups produced the answer. Reported in Settings. */
    enum class Source(val label: String) {
        SYSTEM("system findFocus"),
        ACTIVE_WINDOW("active-window findFocus"),
        WINDOW_WALK("window walk"),
        NONE("none"),
    }

    /**
     * The observable facts about a node that decide whether it is editable.
     *
     * Kept apart from [AccessibilityNodeInfo] deliberately: this rule decides whether the orb appears
     * over a field or never appears at all, so it is a pure function covered by unit tests instead of
     * something only ever exercised on a device that is already misbehaving.
     */
    data class Signals(
        val reportedEditable: Boolean = false,
        val className: String? = null,
        val actionIds: List<Int> = emptyList(),
    )

    /**
     * `true` when [signals] describe an editable text input.
     *
     * Three independent routes, in order of authority:
     *
     *  1. `isEditable` — the documented flag, which well-behaved apps set.
     *  2. the class name — the whole `EditText` family, plus `AutoCompleteTextView` and `WebView`,
     *     which are editable without reporting the flag.
     *  3. the actions the field advertises: a node offering **both** `ACTION_SET_TEXT` and
     *     `ACTION_SET_SELECTION` is a text editor whatever it calls itself. This is the route that
     *     catches Compose and web fields, which report neither the flag nor a useful class name.
     *
     * Requiring *both* actions is what keeps a keyboard's own keys out of the answer: every key is
     * clickable, none of them offers text editing, so a key is never mistaken for a field.
     */
    fun looksEditable(signals: Signals): Boolean {
        if (signals.reportedEditable) return true
        val className = signals.className.orEmpty()
        if (className.contains("EditText") || className in EDITABLE_CLASS_NAMES) return true
        return signals.actionIds.contains(ACTION_SET_TEXT) &&
            signals.actionIds.contains(ACTION_SET_SELECTION)
    }

    /** The Android-shaped half of [looksEditable]: the only part of the rule that needs a device. */
    private fun signalsOf(node: AccessibilityNodeInfo): Signals = Signals(
        reportedEditable = node.isEditable,
        className = node.className?.toString(),
        // `actionList` is a fresh list per call and throws on a node that went stale between the
        // lookup and the read, which is normal while the screen is changing underneath us.
        actionIds = runCatching { node.actionList }.getOrNull().orEmpty().map { it.id },
    )

    fun isEditable(node: AccessibilityNodeInfo): Boolean = looksEditable(signalsOf(node))

    /** [probe]'s answer: the node, and which lookup produced it. */
    data class Focus(val node: AccessibilityNodeInfo?, val source: Source) {
        val isFieldFocused: Boolean get() = node != null
    }

    /**
     * Finds the field that owns input focus.
     *
     * The order is deliberate:
     *
     *  1. **`AccessibilityService.findFocus(FOCUS_INPUT)`** — the system's own answer, searched across
     *     every window. This is the only one of the three that still works while the keyboard is up,
     *     because it is not scoped to whichever window the platform currently calls active. It is also
     *     the closest thing to the user's own tap: the platform put input focus there.
     *  2. the same search inside the active window — covers the moment right after a host app has
     *     recreated its field.
     *  3. a depth-first walk of the active window for any editable node — the last resort for fields
     *     that never take accessibility input focus at all.
     *
     * A `null` result is a legitimate answer rather than an error: it means nothing editable is being
     * edited at that instant.
     */
    fun probe(service: AccessibilityService, activeRoot: AccessibilityNodeInfo?): Focus {
        runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.takeIf { isEditable(it) }
            ?.let { return Focus(it, Source.SYSTEM) }

        focusedEditableUnder(activeRoot)?.let { return Focus(it, Source.ACTIVE_WINDOW) }

        activeRoot?.let { root ->
            findFirstEditable(root, MAX_SEARCH_DEPTH)?.let { return Focus(it, Source.WINDOW_WALK) }
        }
        return Focus(null, Source.NONE)
    }

    /** Whatever the focused node is, as long as it is editable — or `null`. */
    fun focusedEditableNode(service: AccessibilityService, activeRoot: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        probe(service, activeRoot).node

    private fun focusedEditableUnder(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        return focused?.takeIf { isEditable(it) }
    }

    /**
     * First editable node in [node]'s subtree, or `null`.
     *
     * Visibility is required on this route only. The two routes above start from the node the system
     * says holds input focus, which cannot be invisible; this one walks a whole window and would
     * otherwise match a pre-filled field inside a dialog that is not on screen.
     */
    fun findFirstEditable(
        node: AccessibilityNodeInfo,
        remainingDepth: Int = MAX_SEARCH_DEPTH,
    ): AccessibilityNodeInfo? {
        if (remainingDepth <= 0) return null
        if (node.isVisibleToUser && isEditable(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findFirstEditable(child, remainingDepth - 1)?.let { return it }
        }
        return null
    }

    /** Bounds of the focused field, or `null` when it has no usable rectangle. */
    fun focusedFieldRect(node: AccessibilityNodeInfo?): Rect? {
        val focused = node?.takeIf { isEditable(it) } ?: return null
        val bounds = Rect()
        // The SDK exposes this as a void out-parameter method, hence the post-check.
        focused.getBoundsInScreen(bounds)
        return if (bounds.width() > 0 && bounds.height() > 0) bounds else null
    }

    private const val MAX_SEARCH_DEPTH = 12

    /**
     * `AccessibilityNodeInfo.ACTION_SET_TEXT`.
     *
     * Compile-time constants, so a JVM unit test can assert both the rule and these values without a
     * device — which matters, because if they ever stopped matching the platform, every action-based
     * detection would silently stop working and only a user would find out.
     */
    internal const val ACTION_SET_TEXT = AccessibilityNodeInfo.ACTION_SET_TEXT

    internal const val ACTION_SET_SELECTION = AccessibilityNodeInfo.ACTION_SET_SELECTION
}
