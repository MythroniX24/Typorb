package com.typorb.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * ## One animation, not two
 *
 * This used to be a double animation and it looked it. The window rectangle was morphed by a
 * `ValueAnimator` in [com.typorb.overlay.OverlayController] while Compose animated its own content
 * size on top — two clocks, two curves, and a window that for two hundred milliseconds was still the
 * idle square while the recording capsule inside it was already 160dp wide. A window surface is
 * clipped to its own bounds, so the pill was **guillotined** on every state change: the visible
 * symptom was an orb that visibly broke when tapped.
 *
 * Now Compose animates once and the window follows it. The box that *contains* the pill grows with
 * [animateIntAsState], this composable reports that box outward through [onPillBoxChanged], and the
 * controller makes the `WindowManager` rectangle exactly that box plus [contentPadding]. Because the
 * window is sized from the same value the pill is drawn with, it can never be smaller than what is
 * inside it, and the morph is a single smooth interpolation instead of a race.
 *
 * The box is the **larger** of the pill's current and target size, never the smaller: while a capsule
 * collapses back to the idle orb the outgoing pill is still on screen, and a box that had already
 * shrunk to the orb would cut it in half.
 *
 * The pill is drawn into the box's **top-right** corner — the same corner the controller anchors the
 * window and the drag to — so a capsule grows leftwards and the edge the user aimed at never moves.
 *
 * @param pressed the finger is down on the orb; the tap has been registered but not yet resolved.
 * @param dragging the orb is being moved, so the press reads as a lift rather than a press.
 * @param cornerRadiusDp user-adjustable corner radius, driven by Settings.
 * @param showWaveform when `false` the recording capsule shows a steady pulse instead of live bars.
 * @param onPillBoxChanged reports the box in dp, every frame of a morph.
 */
@Composable
fun TyporbOverlayContent(
    state: OverlayUiState,
    pressed: Boolean = false,
    dragging: Boolean = false,
    cornerRadiusDp: Int = TyporbSettings.DEFAULT_OVERLAY_CORNER_DP,
    showWaveform: Boolean = true,
    contentPadding: Dp = 0.dp,
    idleSizeDp: Int = TyporbSettings.DEFAULT_OVERLAY_SIZE_DP,
    onPillBoxChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val shape = TyporbShapes.overlayCorner(cornerRadiusDp)
    val (targetWidthDp, targetHeightDp) = OverlayMetrics.pillSizeDp(state, idleSizeDp)

    val animatedWidthDp by animateIntAsState(
        targetValue = targetWidthDp,
        animationSpec = tween(MORPH_MS),
        label = "pill-width",
    )
    val animatedHeightDp by animateIntAsState(
        targetValue = targetHeightDp,
        animationSpec = tween(MORPH_MS),
        label = "pill-height",
    )
    val boxWidthDp = maxOf(animatedWidthDp, targetWidthDp)
    val boxHeightDp = maxOf(animatedHeightDp, targetHeightDp)

    // The window has to be told about every step of the morph, because it *is* the clip rectangle the
    // pill is drawn inside. `snapshotFlow` conflates to the animation's own frame rate, and the effect
    // runs once for the whole life of the window: keying it on the callback would restart the flow on
    // every recomposition, which is every frame of the animation it exists to report.
    val reportBox by rememberUpdatedState(onPillBoxChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { boxWidthDp to boxHeightDp }.collect { (widthDp, heightDp) ->
            reportBox(widthDp, heightDp)
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            dragging -> DRAG_SCALE
            pressed -> PRESSED_SCALE
            else -> 1f
        },
        animationSpec = tween(durationMillis = if (pressed || dragging) 90 else 220),
        label = "press-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier.size(boxWidthDp.dp, boxHeightDp.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            AnimatedContent(
                targetState = state,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopEnd,
                // Fade only. The size is animated by the box above, so a size transform here would
                // put a second animation back on top of the first — the exact mistake this file is
                // recovering from.
                transitionSpec = {
                    fadeIn(tween(FADE_IN_MS)) togetherWith fadeOut(tween(FADE_OUT_MS))
                },
                // Keyed on the state's *kind*, never on the state itself. Recording publishes a new
                // amplitude list roughly fifteen times a second, and with the default key every one of
                // those counts as a new target — which is a fresh fade-in/fade-out per frame and the
                // capsule visibly strobing instead of animating.
                contentKey = { it::class },
                label = "typorb-state",
            ) { current ->
                val (widthDp, heightDp) = OverlayMetrics.pillSizeDp(current, idleSizeDp)
                // Scaled around its own top-right corner so the press shrinks the pill towards the
                // point the drag holds on to, instead of sliding it off that point.
                PillScale(scale) {
                    when (current) {
                        is OverlayUiState.Idle -> IdlePill(shape, widthDp, heightDp)
                        is OverlayUiState.Recording ->
                            RecordingPill(shape, widthDp, heightDp, current.amplitudes, showWaveform)
                        is OverlayUiState.Processing ->
                            ProcessingPill(shape, widthDp, heightDp, current.stage.label)
                        is OverlayUiState.Failed ->
                            FailedPill(shape, widthDp, heightDp, current.message)
                    }
                }
            }
        }
    }
}

/** One pill at its own size, scaled around the anchor corner, with the shared surface dressing. */
@Composable
private fun PillScale(scale: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            // Its own top-right corner, which is the point the window and the drag both hold on to: a
            // press then shrinks the pill towards that corner instead of sliding it off the point.
            transformOrigin = TransformOrigin(1f, 0f)
        },
    ) {
        content()
    }
}

/** The shared white surface: soft ambient shadow, a hairline border and a whisper of indigo. */
@Composable
private fun PillSurface(
    shape: RoundedCornerShape,
    widthDp: Int,
    heightDp: Int,
    elevation: Dp = TyporbElevation.Overlay,
    borderColor: Color = TyporbPalette.Border,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(widthDp.dp, heightDp.dp)
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(TyporbPalette.Surface)
            .background(
                Brush.verticalGradient(
                    listOf(TyporbPalette.Indigo.copy(alpha = SURFACE_TINT), Color.Transparent),
                ),
            )
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Idle: the orb itself — a rounded square of glossy white with a softly pulsing indigo mic. */
@Composable
private fun IdlePill(shape: RoundedCornerShape, widthDp: Int, heightDp: Int) {
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
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle-breathe",
    )

    PillSurface(shape = shape, widthDp = widthDp, heightDp = heightDp) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Start dictation",
            tint = TyporbPalette.Indigo.copy(alpha = 0.72f + 0.28f * glow),
            modifier = Modifier
                .size((widthDp * MIC_FRACTION).dp)
                .scale(breath),
        )
    }
}

/** Recording: a white capsule with live amplitude bars and a stop affordance. */
@Composable
private fun RecordingPill(
    shape: RoundedCornerShape,
    widthDp: Int,
    heightDp: Int,
    amplitudes: List<Float>,
    showWaveform: Boolean,
) {
    PillSurface(
        shape = shape,
        widthDp = widthDp,
        heightDp = heightDp,
        borderColor = TyporbPalette.Indigo.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
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
private fun ProcessingPill(shape: RoundedCornerShape, widthDp: Int, heightDp: Int, label: String) {
    val transition = rememberInfiniteTransition(label = "processing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "sweep",
    )

    PillSurface(shape = shape, widthDp = widthDp, heightDp = heightDp) {
        // Fills the surface, which has a definite size of its own, so the sweep ring is measured
        // against the capsule rather than against a window rectangle mid-morph.
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
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
                    overflow = TextOverflow.Ellipsis,
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
private fun FailedPill(shape: RoundedCornerShape, widthDp: Int, heightDp: Int, message: String) {
    PillSurface(
        shape = shape,
        widthDp = widthDp,
        heightDp = heightDp,
        borderColor = TyporbPalette.Danger.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
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
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val RESTING_LEVEL = 0.14f
private const val MIN_LEVEL = 0.08f
private const val MIN_BAR_FRACTION = 0.18f

/** How far the pill shrinks under the finger. Enough to read as a press, not enough to look broken. */
private const val PRESSED_SCALE = 0.90f

/** How far it grows while it is being carried, so a move never reads as a press. */
private const val DRAG_SCALE = 1.06f

/** Mic size relative to the idle orb, so a resized orb keeps its proportions. */
private const val MIC_FRACTION = 0.46f

/** Indigo tint along the top of every surface, fading to nothing by the middle. */
private const val SURFACE_TINT = 0.08f

/** The single morph duration: the box, the window and the pill all move on this one number. */
private const val MORPH_MS = 200

/** Crossfade timings. The outgoing pill leaves faster than the new one arrives. */
private const val FADE_IN_MS = 160
private const val FADE_OUT_MS = 110
