package com.typorb.ui.overlay

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.data.TyporbSettings
import com.typorb.model.OverlayUiState
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The floating Typorb.
 *
 * The window is sized by [com.typorb.overlay.OverlayController], so this composable only has to
 * fill whatever box it is given: a 48dp square when idle, a capsule while recording or processing.
 *
 * @param cornerRadiusDp user-adjustable corner radius, driven by the Settings screen.
 * @param showWaveform when `false` the recording capsule shows a steady pulse dot instead of the
 *   live amplitude bars — cheaper on battery, and some users find the bars distracting.
 */
@Composable
fun TyporbOverlayContent(
    state: OverlayUiState,
    onTap: () -> Unit,
    cornerRadiusDp: Int = TyporbSettings.DEFAULT_OVERLAY_CORNER_DP,
    showWaveform: Boolean = true,
) {
    val shape = TyporbShapes.overlayCorner(cornerRadiusDp)
    val interactionSource = remember { MutableInteractionSource() }
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
            transitionSpec = {
                (fadeIn(tween(180)) togetherWith fadeOut(tween(110)))
            },
            label = "typorb-state",
        ) { current ->
            when (current) {
                is OverlayUiState.Idle -> IdlePill(shape)
                is OverlayUiState.Recording -> RecordingPill(current.amplitudes, shape, showWaveform)
                is OverlayUiState.Processing -> ProcessingPill(current.stage.label, shape)
                is OverlayUiState.Failed -> FailedPill(current.message, shape)
            }
        }
    }
}

/** Idle: a 48dp rounded square of frosted glass with a softly pulsing mic. */
@Composable
private fun IdlePill(shape: RoundedCornerShape) {
    val transition = rememberInfiniteTransition(label = "idle-pulse")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-glow",
    )
    val breath by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-breathe",
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .drawBehind {
                // Soft outer halo that pulses with the mic (drawn under the glass fill).
                val haloRadius = size.minDimension * 1.6f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TyporbPalette.NeonCyan.copy(alpha = 0.28f * glow),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = haloRadius,
                    ),
                    radius = haloRadius,
                )
            }
            .background(TyporbPalette.Glass, shape)
            .border(1.dp, TyporbPalette.NeonCyan.copy(alpha = 0.28f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Start dictation",
            tint = TyporbPalette.NeonCyan.copy(alpha = 0.75f + 0.25f * glow),
            modifier = Modifier
                .size(24.dp)
                .scale(breath),
        )
    }
}

/** Recording: a capsule with five bars driven by live microphone decibels. */
@Composable
private fun RecordingPill(
    amplitudes: List<Float>,
    shape: RoundedCornerShape,
    showWaveform: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Glass, shape)
            .border(1.dp, TyporbPalette.Violet.copy(alpha = 0.45f), shape)
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

/**
 * Five vertical bars whose heights track the last five amplitude samples.
 *
 * Each bar animates with its own spring-free tween so the waveform stays fluid at 20 Hz updates
 * instead of snapping.
 */
@Composable
private fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val recent = amplitudes.takeLast(com.typorb.domain.DictationCoordinator.WAVEFORM_BARS)
    // animateFloatAsState is composable, so the animated levels are created here and only read
    // (never created) inside the Canvas draw lambda.
    val animatedLevels = List(com.typorb.domain.DictationCoordinator.WAVEFORM_BARS) { index ->
        animateFloatAsState(
            targetValue = (recent.getOrNull(index) ?: RESTING_LEVEL).coerceIn(MIN_LEVEL, 1f),
            animationSpec = tween(durationMillis = 90),
            label = "bar-$index",
        )
    }

    Canvas(
        modifier = modifier
            .height(26.dp)
            .fillMaxWidth(),
    ) {
        val barCount = animatedLevels.size
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

/** The waveform-off state: a calm centred dot rather than a live meter. */
@Composable
private fun SteadyPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "steady-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "steady-scale",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(scale)
                .background(TyporbPalette.Violet, RoundedCornerShape(5.dp)),
        )
    }
}

/** The "tap to stop" affordance on the right of the recording capsule. */
@Composable
private fun StopGlyph() {
    val transition = rememberInfiniteTransition(label = "stop-glyph")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stop-pulse",
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .scale(pulse)
            .background(TyporbPalette.Danger.copy(alpha = 0.92f), RoundedCornerShape(5.dp)),
    )
}

/** Processing: rotating cyan → violet sweep border with a stage label and animated ellipsis. */
@Composable
private fun ProcessingPill(label: String, shape: RoundedCornerShape) {
    val transition = rememberInfiniteTransition(label = "processing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "sweep",
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Glass, shape)
            .drawBehind {
                // Sweeping gradient stroke, drawn on top of the glass fill.
                val radius = size.height / 2f
                rotate(degrees = sweep, pivot = center) {
                    drawRoundRect(
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                TyporbPalette.NeonCyan,
                                TyporbPalette.Violet,
                                TyporbPalette.NeonCyan.copy(alpha = 0.05f),
                                TyporbPalette.NeonCyan,
                            ),
                            center = center,
                        ),
                        topLeft = Offset(1f, 1f),
                        size = Size(size.width - 2f, size.height - 2f),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 2f),
                    )
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(14.dp)
                .background(TyporbPalette.Violet.copy(alpha = 0.9f), RoundedCornerShape(7.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = TyporbPalette.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
        )
        EllipsisDots()
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
                initialValue = 0.25f,
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
                    .background(TyporbPalette.NeonCyan.copy(alpha = alpha), RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Failure: a red-tinted capsule holding the reason the last dictation did not land. */
@Composable
private fun FailedPill(message: String, shape: RoundedCornerShape) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Glass, shape)
            .border(1.dp, TyporbPalette.Danger.copy(alpha = 0.55f), shape)
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

private const val RESTING_LEVEL = 0.14f
private const val MIN_LEVEL = 0.08f
private const val MIN_BAR_FRACTION = 0.18f