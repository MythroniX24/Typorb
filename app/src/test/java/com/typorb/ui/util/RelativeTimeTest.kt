package com.typorb.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The relative badge is the only timestamp a user sees on a transcript, so every unit boundary is
 * pinned here — an off-by-one would render "60 mins ago" instead of "1 hour ago".
 *
 * `nowMs` is injected rather than read from the clock, so these are deterministic.
 */
class RelativeTimeTest {

    private val now = 1_800_000_000_000L

    private fun ago(millis: Long): String = RelativeTime.format(now - millis, now)

    @Test
    fun `under a minute reads as just now`() {
        assertEquals("Just now", ago(0))
        assertEquals("Just now", ago(TimeUnit.SECONDS.toMillis(59)))
    }

    @Test
    fun `minutes are singular and plural correctly`() {
        assertEquals("1 min ago", ago(TimeUnit.MINUTES.toMillis(1)))
        assertEquals("2 mins ago", ago(TimeUnit.MINUTES.toMillis(2)))
        assertEquals("59 mins ago", ago(TimeUnit.MINUTES.toMillis(59)))
    }

    @Test
    fun `hours take over at exactly sixty minutes`() {
        assertEquals("1 hour ago", ago(TimeUnit.MINUTES.toMillis(60)))
        assertEquals("23 hours ago", ago(TimeUnit.HOURS.toMillis(23)))
    }

    @Test
    fun `days take over at exactly twenty four hours`() {
        assertEquals("1 day ago", ago(TimeUnit.HOURS.toMillis(24)))
        assertEquals("6 days ago", ago(TimeUnit.DAYS.toMillis(6)))
    }

    @Test
    fun `weeks take over at exactly seven days`() {
        assertEquals("1 week ago", ago(TimeUnit.DAYS.toMillis(7)))
        assertEquals("4 weeks ago", ago(TimeUnit.DAYS.toMillis(34)))
    }

    @Test
    fun `months and years roll over at their boundaries`() {
        assertEquals("1 month ago", ago(TimeUnit.DAYS.toMillis(35)))
        assertEquals("11 months ago", ago(TimeUnit.DAYS.toMillis(350)))
        // Day 360-364 is the boundary that used to render "0 years ago".
        assertEquals("12 months ago", ago(TimeUnit.DAYS.toMillis(360)))
        assertEquals("1 year ago", ago(TimeUnit.DAYS.toMillis(400)))
    }

    @Test
    fun `a future timestamp from clock skew never renders as negative`() {
        val future = RelativeTime.format(now + TimeUnit.MINUTES.toMillis(5), now)

        assertEquals("Just now", future)
    }
}