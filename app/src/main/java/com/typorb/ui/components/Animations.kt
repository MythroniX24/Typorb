package com.typorb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Staggered entrance for a stack of cards: each one fades up a beat after the previous.
 *
 * The delay is capped so a long settings page does not make the user wait for the bottom of the
 * list — after [MAX_STAGGER_STEPS] items everything arrives together.
 *
 * @param index position in the list; drives the delay.
 * @param travelPx how far the element rises into place.
 */
@Composable
fun Modifier.entrance(
    index: Int,
    travelPx: Float = 26f,
    durationMs: Int = 340,
    stepMs: Int = 55,
): Modifier {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(MAX_STAGGER_STEPS) * stepMs.toLong())
        settled = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(durationMs),
        label = "entrance-alpha",
    )
    val offset by animateFloatAsState(
        targetValue = if (settled) 0f else travelPx,
        animationSpec = tween(durationMs),
        label = "entrance-offset",
    )

    return this.graphicsLayer {
        this.alpha = alpha
        translationY = offset
    }
}

/**
 * A slow, low-amplitude breathing animation for a status dot.
 *
 * Amplitude is deliberately small: a status indicator that throbs is distracting, and this one sits
 * beside the brand wordmark.
 *
 * [enabled] matters for performance as much as for taste: an infinite transition never retires, so
 * a permanently-running one keeps the frame clock busy at 60fps for the life of the composition and
 * competes with scrolling on a budget GPU. Callers pass `false` when there is nothing to signal and
 * the animation settles to [min] immediately instead.
 */
@Composable
fun rememberBreathingAlpha(
    min: Float = 0.55f,
    max: Float = 1f,
    periodMs: Int = 1600,
    enabled: Boolean = true,
): Float {
    if (!enabled) return min
    return BreathingLoop(max = max, periodMs = periodMs)
}

/**
 * The repeating loop, kept in its own composable so that when [rememberBreathingAlpha] is disabled
 * the infinite transition is never composed at all — there is no animation left driving frames.
 */
@Composable
private fun BreathingLoop(max: Float, periodMs: Int): Float {
    val transition = rememberInfiniteTransition(label = "breathing")
    val value by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathing-value",
    )
    return value
}

/** How many items still get an individual delay before the stagger is capped. */
private const val MAX_STAGGER_STEPS = 6
