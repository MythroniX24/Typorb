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
    fun `a field the service cannot read no longer hides the orb`() {
        // The case that cost a release: the keyboard is up, the focused field cannot be read as
        // editable, and the verdict named the field while nothing appeared on screen. The keyboard is
        // the condition now, so exactly this state must report the orb as working.
        val unreadableField = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 42,
            editableFieldFocused = false,
            focusSource = "none",
            imeVisible = true,
            imeHeightPx = 508,
            overlayVisible = true,
            overlayShowAttempts = 1,
        )
        assertEquals("Orb is on screen", unreadableField.headline())

        // And the verdict that used to be produced here must not be reachable at all: without a
        // keyboard there is nothing to place the orb above.
        assertEquals(
            "Keyboard not detected",
            unreadableField.copy(imeVisible = false, overlayVisible = false).headline(),
        )
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
    fun `an addView error is named verbatim and points at the fallback grant`() {
        // The Redmi 8A case: focus and keyboard both detected, orb still absent. The only way to
        // explain that from the outside was the addView failure, so it must lead the verdict.
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 618,
            editableFieldFocused = true,
            imeVisible = true,
            imeHeightPx = 2283,
            overlayVisible = false,
            overlayWindowError = "BadTokenException: token not valid",
            overlayPermissionGranted = false,
        )
        assertEquals("Overlay window not added", d.headline())
        assertTrue(d.detail().contains("BadTokenException: token not valid"))
        assertTrue(d.detail().contains("Display over other apps"))
    }

    @Test
    fun `when both window types were tried the grant is not suggested again`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 12,
            editableFieldFocused = true,
            imeVisible = true,
            overlayVisible = false,
            overlayWindowError = "BadTokenException: token not valid",
            overlayPermissionGranted = true,
        )
        assertTrue(d.detail().contains("will not help further"))
    }

    @Test
    fun `a probe failure is reported separately from the orb failure`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            probeAttached = false,
            probeWindowError = "BadTokenException: token not valid",
        )
        val report = d.asReport(nowMs = 1_000L)
        assertTrue(report.contains("probe addView err : BadTokenException"))
        assertTrue(report.contains("IME probe window  : not attached"))
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
            focusSource = "system findFocus",
            overlayVisible = true,
            overlayShowAttempts = 2,
            lastEvaluationAtMs = 1_000L,
            breadcrumbs = listOf("focus=true"),
        )
        val report = d.asReport(nowMs = 3_500L)

        assertTrue(report.contains("Orb is on screen"))
        assertTrue(report.contains("events received   : 7"))
        assertTrue(report.contains("TYPE_VIEW_FOCUSED"))
        assertTrue(report.contains("com.google.android.inputmethod.latin"))
        assertTrue(report.contains("keyboard height   : 508px"))
        assertTrue(report.contains("(system findFocus)"))
        assertTrue(report.contains("asked 2x"))
        assertTrue(report.contains("IME probe window  : attached"))
        assertTrue(report.contains("orb on screen     : true"))
        assertTrue(report.contains("2500ms ago"))
        assertTrue(report.contains("focus=true"))
        assertTrue(!report.contains("Typorb failed"))
    }

    @Test
    fun `a silent event stream is fine while the watchdog is running`() {
        // The watchdog is the path that shows the orb without any event at all, so "no events" is only
        // a fault when the watchdog is silent too — which is the case it was written for.
        val covered = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 0,
            watchdogTicks = 120,
            imeVisible = true,
            imeHeightPx = 508,
            overlayVisible = true,
        )
        assertEquals("Orb is on screen", covered.headline())

        assertEquals(
            "Running, but receiving no events",
            covered.copy(watchdogTicks = 0, overlayVisible = false).headline(),
        )
    }

    @Test
    fun `a pinned orb without a keyboard is not reported as a keyboard fault`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 42,
            watchdogTicks = 40,
            imeVisible = false,
            orbPinned = true,
            overlayVisible = true,
        )
        assertEquals("Orb is on screen", d.headline())
        assertTrue(d.detail().contains("Still dictating"))
    }

    @Test
    fun `report carries the watchdog and window liveness rows`() {
        val d = OrbDiagnostics(
            serviceConnected = true,
            eventCount = 9,
            watchdogTicks = 300,
            watchdogKeyboardTicks = 42,
            orbPinned = true,
            overlayAttached = true,
            overlayVisible = true,
            imeVisible = true,
        )
        val report = d.asReport(nowMs = 2_000L)
        assertTrue(report.contains("watchdog          : 300 checks, 42 with a keyboard"))
        assertTrue(report.contains("orb pinned        : true"))
        assertTrue(report.contains("orb window live   : true"))
    }

    @Test
    fun `event names fall back to the raw value`() {
        assertEquals("—", AccessibilityEventNames.name(null))
        assertEquals("TYPE_WINDOW_STATE_CHANGED", AccessibilityEventNames.name(32))
        assertEquals("type 999", AccessibilityEventNames.name(999))
    }
}
