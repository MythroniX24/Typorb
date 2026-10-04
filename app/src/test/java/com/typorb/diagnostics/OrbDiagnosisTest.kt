package com.typorb.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug console is only useful if its verdict is right, so the wording is asserted rather than
 * eyeballed. Each case pins one link in the "why is the orb not showing" chain.
 */
class OrbDiagnosisTest {

    @Test
    fun `service not connected is reported before anything else`() {
        val d = OrbDiagnostics(serviceConnected = false)
        assertEquals("Typorb is not running", d.headline())
    }

    @Test
    fun `startup failure outranks a disconnected service`() {
        val d = OrbDiagnostics(
            serviceConnected = false,
            setupError = "IllegalStateException: no container",
        )
        assertEquals("Typorb failed to start", d.headline())
        assertEquals("IllegalStateException: no container", d.detail())
    }

    @Test
    fun `zero events means the service is enabled but starved`() {
        val d = OrbDiagnostics(serviceConnected = true, eventCount = 0)
        assertEquals("Running, but receiving no events", d.headline())
        assertTrue(d.detail().contains("battery optimiser"))
    }

    @Test
    fun `no focused field is named as the blocker`() {
        val d = OrbDiagnostics(serviceConnected = true, eventCount = 42)
        assertEquals("No text field has focus", d.headline())
    }

    @Test
    fun `no visible keyboard is distinguished from a missing keyboard window`() {
        val focused = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 42,
            editableFieldFocused = true,
        )
        assertEquals("Keyboard not detected", focused.headline())
        assertEquals("The service can see no windows at all.", focused.detail())

        val untagged = focused.copy(windowsSeen = 3)
        assertEquals("No window is tagged as a keyboard.", untagged.detail())

        val tagged = focused.copy(windowsSeen = 3, imeWindowFound = true, imeHeightPx = 508)
        assertEquals("A keyboard window exists but measured 508px.", tagged.detail())
    }

    @Test
    fun `focus and keyboard but no window means WindowManager refused it`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 42,
            editableFieldFocused = true,
            imeVisible = true,
            imeHeightPx = 508,
            overlayVisible = false,
        )
        assertEquals("Overlay window not added", d.headline())
        assertEquals("WindowManager refused the overlay window.", d.detail())
    }

    @Test
    fun `everything green reports the measured height`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 42,
            editableFieldFocused = true,
            imeVisible = true,
            imeHeightPx = 508,
            overlayVisible = true,
        )
        assertEquals("Orb is on screen", d.headline())
        assertEquals("All checks pass — keyboard at 508px.", d.detail())
    }

    @Test
    fun `report contains every row a bug report needs`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 7,
            lastEventType = 8,
            lastEventPackage = "com.android.mms",
            imePackage = "com.google.android.inputmethod.latin",
            windowsSeen = 2,
            imeWindowFound = true,
            imeHeightPx = 508,
            imeVisible = true,
            imeInsetPx = 500,
            probeAttached = true,
            editableFieldFocused = true,
            overlayVisible = true,
            lastEvaluationAtMs = 1_000L,
            breadcrumbs = listOf("focus=true"),
        )
        val report = d.asReport(nowMs = 3_500L)

        assertTrue(report.contains("Orb is on screen"))
        assertTrue(report.contains("events received   : 7"))
        assertTrue(report.contains("TYPE_VIEW_FOCUSED"))
        assertTrue(report.contains("com.google.android.inputmethod.latin"))
        assertTrue(report.contains("keyboard height   : 508px"))
        assertTrue(report.contains("IME probe window  : attached"))
        assertTrue(report.contains("orb on screen     : true"))
        assertTrue(report.contains("2500ms ago"))
        assertTrue(report.contains("focus=true"))
        assertTrue(!report.contains("Typorb failed"))
    }

    @Test
    fun `event names fall back to the raw value`() {
        assertEquals("—", AccessibilityEventNames.name(null))
        assertEquals("TYPE_WINDOW_STATE_CHANGED", AccessibilityEventNames.name(32))
        assertEquals("type 999", AccessibilityEventNames.name(999))
    }
}
