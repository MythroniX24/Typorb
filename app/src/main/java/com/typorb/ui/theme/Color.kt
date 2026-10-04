package com.typorb.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Typorb's light palette: a crisp off-white canvas, elevated pure-white cards and a cobalt →
 * indigo accent ramp.
 *
 * Hex values are fixed by the design system so the app shell and the floating overlay always read
 * as one product.
 */
object TyporbPalette {
    /** #F8F9FA — off-white canvas. Deliberately not pure white, to avoid glare. */
    val Background = Color(0xFFF8F9FA)

    /** #FFFFFF — elevated card and bottom-bar surfaces. */
    val Surface = Color(0xFFFFFFFF)

    /** When a white card sits on white, this is the tint that separates them. */
    val SurfaceSunken = Color(0xFFF4F5F7)

    /** #E5E7EB — hairline borders separating white surfaces from the canvas. */
    val Border = Color(0xFFE5E7EB)

    /** A slightly stronger border for pressed/selected outlines. */
    val BorderStrong = Color(0xFFD1D5DB)

    /** #3B82F6 — cobalt, the primary actionable colour. */
    val Cobalt = Color(0xFF3B82F6)

    /** #6366F1 — electric indigo, the second half of the accent ramp. */
    val Indigo = Color(0xFF6366F1)

    /** #10B981 — soft emerald for active/connected state. */
    val Emerald = Color(0xFF10B981)

    /** #ECFDF5 — the emerald tint behind "service ready" pills. */
    val EmeraldTint = Color(0xFFECFDF5)

    /** Teal used as the second stop of the recording waveform. */
    val WaveCyan = Color(0xFF06B6D4)

    /** #111827 — sharp obsidian for titles and body copy. */
    val TextPrimary = Color(0xFF111827)

    /** #6B7280 — neutral slate for captions and metadata. */
    val TextSecondary = Color(0xFF6B7280)

    /** A lighter slate for the quietest hints. */
    val TextMuted = Color(0xFF9CA3AF)

    /** Text/icons drawn on top of an indigo or emerald fill. */
    val OnAccent = Color(0xFFFFFFFF)

    /** Feedback. */
    val Danger = Color(0xFFEF4444)
    val DangerTint = Color(0xFFFEF2F2)
    val Warning = Color(0xFFF59E0B)

    /** Live waveform ramp. */
    val WaveformGradient = listOf(Cobalt, WaveCyan)
}