package com.typorb.ui.util

import java.util.concurrent.TimeUnit

/**
 * Formats a transcript timestamp as a short relative badge ("2 mins ago").
 *
 * Deliberately a pure function of `(timestampMs, nowMs)` rather than reading the clock itself, so
 * every boundary can be unit-tested without freezing time.
 */
object RelativeTime {

    /**
     * @param timestampMs when the dictation landed.
     * @param nowMs the current time; injected so tests are deterministic.
     */
    fun format(timestampMs: Long, nowMs: Long): String {
        val deltaMs = nowMs - timestampMs

        // A clock skew or a timestamp from the future should read as "just now", never as a
        // negative age.
        if (deltaMs < TimeUnit.MINUTES.toMillis(1)) return "Just now"

        val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
        if (minutes < 60) return plural(minutes, "min")

        val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
        if (hours < 24) return plural(hours, "hour")

        val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
        if (days < 7) return plural(days, "day")

        val weeks = days / 7
        if (weeks < 5) return plural(weeks, "week")

        // Gated on days, not on `days / 30`: with 30-day months the two disagree between day 360 and
        // day 364, which produced "0 years ago" for a 360-day-old transcript.
        if (days < 365) return plural(days / 30, "month")

        return plural(days / 365, "year")
    }

    private fun plural(value: Long, unit: String): String =
        if (value == 1L) "1 $unit ago" else "$value ${unit}s ago"
}