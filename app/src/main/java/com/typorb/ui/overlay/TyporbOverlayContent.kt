package com.typorb.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.data.TyporbSettings
import com.typorb.model.OverlayUiState
import com.typorb.overlay.OverlayMetrics
import com.typorb.ui.theme.TyporbElevation
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The floating Typorb.
 *
 * The window is sized by [com.typorb.overlay.OverlayController] — deliberately larger than the pill
 * by [contentPadding] on every side so the soft ambient shadow has somewhere to fall; a window
 * surface is clipped to its own bounds, so a shadow drawn in a pill-sized window would be cut off.
 *
 * @param cornerRadiusDp user-adjustable corner radius, driven by Settings.
 * @param showWaveform when `false` the recording capsule shows a steady pulse instead of the live
 *   amplitude bars.
 */
@Composable
fun TyporbOverlayContent(
    state: OverlayUiState,
    onTap: () -> Unit,
    cornerRadiusDp: Int = TyporbSettings.DEFAULT_OVERLAY_CORNER_DP,
    showWaveform: Boolean = true,
    contentPadding: Dp = 0.dp,
    idleSizeDp: Int = TyporbSettings.DEFAULT_OVERLAY_SIZE_DP,
) {
    val shape = TyporbShapes.overlayCorner(cornerRadiusDp)
    val interactionSource = remember { MutableInteractionSource() }

    // Press feedback. The window is the pill's own rectangle, so a tap is a resize *and* the only
    // thing the user can see happen is the morph starting a beat later — which read as the tap not
    // registering at all. Scaling down on press answers the finger immediately, under the finger,
    // before any of the window bookkeeping runs.
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = tween(durationMillis = if (pressed) 90 else 220),
        label = "press-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            // `clip = false` on the size transform: without it AnimatedContent clips the outgoing
            // pill to the *incoming* pill's box for the length of the crossfade, and because the
            // incoming capsule is much wider than the outgoing square orb, a failure or a recording
            // stop visibly guillotined the orb it was animating away from.
            transitionSpec = {
                fadeIn(tween(FADE_IN_MS)) togetherWith
                    fadeOut(tween(FADE_OUT_MS)) using
                    SizeTransform(clip = false)
            },
            // Keyed on the state's *kind*, never on the state itself. Recording publishes a new
            // amplitude list roughly fifteen times a second, and with the default key every one of
            // those counts as a new target — which is a fresh fade-in/fade-out per frame and the
            // capsule visibly strobing instead of animating. The same reasoning keeps a repeated
            // failure message from re-crossfading.
            contentKey = { it::class },
            label = "typorb-state",
        ) { current ->
            val content: @Composable (Modifier) -> Unit = when (current) {
                is OverlayUiState.Idle -> ({ m -> IdlePill(m, shape, idleSizeDp) })
                is OverlayUiState.Recording -> ({ m ->
                    RecordingPill(m, current.amplitudes, shape, showWaveform)
                })
                is OverlayUiState.Processing -> ({ m -> ProcessingPill(m, current.stage.label, shape) })
                is OverlayUiState.Failed -> ({ m -> FailedPill(m, current.message, shape) })
            }
            // `wrapContentSize` rather than `fillMaxSize`: the pill is sized by its own content, and
            // the window is sized to match it. Stretching the pill to the window instead meant the
            // outgoing and incoming pills were laid out at *each other's* size during a crossfade —
            // the square orb squashed into a capsule, then the capsule stretched back — which is what
            // made every state change look like a broken layout rather than a transition.
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(contentPadding)
                    .scale(pressScale),
                contentAlignment = Alignment.Center,
            ) {
                content(Modifier)
            }
        }
    }
}

/** The shared white surface: soft ambient shadow plus a hairline border. */
@Composable
private fun PillSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape,
    elevation: Dp = TyporbElevation.Overlay,
    borderColor: Color = TyporbPalette.Border,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            // Every pill is exactly as wide as its content and exactly one capsule tall. The overlay
            // window is sized from the same numbers, so the surface and its window agree instead of
            // the surface being stretched to whatever rectangle the window happens to be mid-morph
            // through.
            .height(48.dp)
            .wrapContentWidth()
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(TyporbPalette.Surface)
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Idle: a 48dp rounded square of glossy white with a softly pulsing indigo mic. */
@Composable
private fun IdlePill(modifier: Modifier, shape: RoundedCornerShape, idleSizeDp: Int) {
    val transition = rememberInfiniteTransition(label = "idle-pulse")
    val glow by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-glow",
    )
    val breath by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-breathe",
    )

    PillSurface(
        modifier = modifier.size(idleSizeDp.dp),
        shape = shape,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Start dictation",
            tint = TyporbPalette.Indigo.copy(alpha = 0.72f + 0.28f * glow),
            modifier = Modifier
                .size(23.dp)
                .scale(breath),
        )
    }
}

/** Recording: a white capsule with live amplitude bars and a stop affordance. */
@Composable
private fun RecordingPill(
    modifier: Modifier,
    amplitudes: List<Float>,
    shape: RoundedCornerShape,
    showWaveform: Boolean,
) {
    PillSurface(modifier = modifier, shape = shape, borderColor = TyporbPalette.Indigo.copy(alpha = 0.30f)) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .width(160.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showWaveform) {
                Waveform(amplitudes = amplitudes, modifier = Modifier.weight(1f))
            } else {
                SteadyPulse(modifier = Modifier.weight(1f))
            }
            StopGlyph()
        }
    }
}

/**
 * Five vertical bars whose heights track the last five amplitude samples.
 *
 * Each bar animates with its own tween so the waveform stays fluid at ~20 Hz updates instead of
 * snapping between samples.
 */
@Composable
private fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val barCount = com.typorb.domain.DictationCoordinator.WAVEFORM_BARS
    val recent = amplitudes.takeLast(barCount)
    val animatedLevels = List(barCount) { index ->
        animateFloatAsState(
            targetValue = (recent.getOrNull(index) ?: RESTING_LEVEL).coerceIn(MIN_LEVEL, 1f),
            animationSpec = tween(durationMillis = 90),
            label = "bar-$index",
        )
    }

    Canvas(
        modifier = modifier
            .height(24.dp)
            .fillMaxWidth(),
    ) {
        val gap = size.width * 0.28f / barCount.coerceAtLeast(1)
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val brush = Brush.verticalGradient(TyporbPalette.WaveformGradient)

        animatedLevels.forEachIndexed { index, animated ->
            val height = size.height * (MIN_BAR_FRACTION + (1f - MIN_BAR_FRACTION) * animated.value)
            val top = (size.height - height) / 2f
            drawRoundRect(
                brush = brush,
                topLeft = Offset(index * (barWidth + gap), top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** The waveform-off state: a calm centred pulse rather than a live meter. */
@Composable
private fun SteadyPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "steady-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "steady-scale",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(scale)
                .background(TyporbPalette.Indigo, RoundedCornerShape(5.dp)),
        )
    }
}

/** The "tap to stop" square on the right of the recording capsule. */
@Composable
private fun StopGlyph() {
    val transition = rememberInfiniteTransition(label = "stop-glyph")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stop-pulse",
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .scale(pulse)
            .background(TyporbPalette.Danger, RoundedCornerShape(5.dp)),
    )
}

/** Processing: a rotating indigo sweep border with a stage label and animated ellipsis. */
@Composable
private fun ProcessingPill(modifier: Modifier, label: String, shape: RoundedCornerShape) {
    val transition = rememberInfiniteTransition(label = "processing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "sweep",
    )

    PillSurface(modifier = modifier.width(190.dp), shape = shape) {
        // Fills the surface, which now has a definite size of its own (190 x 48dp) rather than
        // inheriting a window rectangle that was mid-morph.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val radius = size.height / 2f
                    rotate(degrees = sweep, pivot = center) {
                        drawRoundRect(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    TyporbPalette.Cobalt,
                                    TyporbPalette.Indigo,
                                    TyporbPalette.Cobalt.copy(alpha = 0.04f),
                                    TyporbPalette.Cobalt,
                                ),
                                center = center,
                            ),
                            topLeft = Offset(1f, 1f),
                            size = Size(size.width - 2f, size.height - 2f),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = 2f),
                        )
                    }
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(13.dp)
                        .background(TyporbPalette.Indigo, RoundedCornerShape(6.5.dp)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    color = TyporbPalette.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                EllipsisDots()
            }
        }
    }
}

/** Three dots that cascade, giving the ticker a live feel. */
@Composable
private fun EllipsisDots() {
    val transition = rememberInfiniteTransition(label = "ellipsis")
    Row(
        modifier = Modifier.padding(start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 560, delayMillis = index * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .background(TyporbPalette.Indigo.copy(alpha = alpha), RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Failure: a red-tinted capsule holding the reason the last dictation did not land. */
@Composable
private fun FailedPill(modifier: Modifier, message: String, shape: RoundedCornerShape) {
    PillSurface(
        modifier = modifier,
        shape = shape,
        borderColor = TyporbPalette.Danger.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .width(OverlayMetrics.errorWidthDp(message).dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(TyporbPalette.Danger, RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                color = TyporbPalette.TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

private const val RESTING_LEVEL = 0.14f
private const val MIN_LEVEL = 0.08f
private const val MIN_BAR_FRACTION = 0.18f

/** How far the pill shrinks under the finger. Enough to read as a press, not enough to look broken. */
private const val PRESSED_SCALE = 0.88f

/** Crossfade timings. The outgoing pill leaves faster than the new one arrives, so the two never sit at half-opacity together. */
private const val FADE_IN_MS = 180
private const val FADE_OUT_MS = 110
