package com.typorb.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that decides whether the field the user is typing into is recognised.
 *
 * Getting this wrong is invisible from the inside: the orb simply never appears over fields of the
 * kind the rule rejects, on someone else's phone. Every route is therefore asserted here rather than
 * discovered by a user.
 */
class EditableFieldInspectorTest {

    private fun signals(
        reportedEditable: Boolean = false,
        className: String? = null,
        actionIds: List<Int> = emptyList(),
    ) = EditableFieldInspector.Signals(reportedEditable, className, actionIds)

    @Test
    fun `the platform constants the rule compares against are the documented ones`() {
        // If these ever stopped matching, the action-based route would silently stop working: the
        // rule would compare against a value no node ever reports, and nothing else in the app would
        // notice. Both numbers are the AOSP values for these actions.
        assertEquals(2_097_152, AccessibilityNodeInfo.ACTION_SET_TEXT)
        assertEquals(131_072, AccessibilityNodeInfo.ACTION_SET_SELECTION)
    }

    @Test
    fun `the documented editable flag is enough on its own`() {
        assertTrue(EditableFieldInspector.looksEditable(signals(reportedEditable = true)))
    }

    @Test
    fun `the edit text family is recognised from its class name alone`() {
        listOf(
            "android.widget.EditText",
            "androidx.appcompat.widget.AppCompatEditText",
            "com.google.android.material.textfield.TextInputEditText",
            "com.miui.widget.MIUIEditText",
        ).forEach { className ->
            assertTrue(className, EditableFieldInspector.looksEditable(signals(className = className)))
        }
    }

    @Test
    fun `web and auto-complete fields are recognised without EditText in the name`() {
        listOf(
            "android.webkit.WebView",
            "android.widget.AutoCompleteTextView",
            "android.widget.MultiAutoCompleteTextView",
        ).forEach { className ->
            assertTrue(className, EditableFieldInspector.looksEditable(signals(className = className)))
        }
    }

    @Test
    fun `a field that advertises text editing is recognised with no flag and a generic class name`() {
        // The Compose and web cases: `isEditable` unset and a class name that says nothing about the
        // node being a text field.
        assertTrue(
            EditableFieldInspector.looksEditable(
                signals(
                    className = "android.view.View",
                    actionIds = listOf(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `set-text alone does not make a field`() {
        assertFalse(
            EditableFieldInspector.looksEditable(
                signals(
                    className = "android.view.View",
                    actionIds = listOf(AccessibilityNodeInfo.ACTION_SET_TEXT),
                ),
            ),
        )
    }

    @Test
    fun `a keyboard key is never mistaken for a text field`() {
        // Every key on a soft keyboard is clickable and nothing more. A rule that accepted any node
        // carrying actions would recognise the keyboard itself as the field to dictate into, which is
        // the one thing the lookup must not do — it is the window that shadows the app.
        assertFalse(
            EditableFieldInspector.looksEditable(
                signals(
                    className = "android.widget.Button",
                    actionIds = listOf(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a plain container with no signals is not a field`() {
        assertFalse(EditableFieldInspector.looksEditable(signals(className = "android.widget.FrameLayout")))
        assertFalse(EditableFieldInspector.looksEditable(signals()))
    }

    @Test
    fun `every lookup is named, so the debug console never shows a blank cause`() {
        EditableFieldInspector.Source.values().forEach { source ->
            assertTrue(source.name, source.label.isNotBlank())
        }
    }
}
