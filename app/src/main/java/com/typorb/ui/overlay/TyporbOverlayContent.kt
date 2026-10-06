package com.typorb.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
 * ## The animation rules this file is built on
 *
 * The previous revision animated the orb three ways at once and the user's verdict was blunt: the
 * animation on tap was the worst part of the app. It was. A tap ran a `ValueAnimator` on the window
 * rectangle **and** a Compose `AnimatedContent` size transform **and** an idle "breathe" on an
 * infinite transition; the window is the clip rectangle the pill is drawn inside, so whenever the two
 * clocks disagreed — which was most frames — the pill was cut off by its own window, and the orb had a
 * perpetual 60fps animation running underneath every gesture, including the drag.
 *
 * Everything in here follows from three rules instead:
 *
 *  1. **One thing changes size, and it is the pill's own surface.** [animateIntAsState] on the width
 *     and height, both reading a single target: `OverlayMetrics.pillSizeDp`. There is no
 *     `SizeTransform`, no second container animating a rectangle, and nothing that changes the
 *     window's extent. The window is sized by
 *     [com.typorb.overlay.OverlayController] from that same target, to the *hull* of the pill that is
 *     leaving and the pill that is arriving, so it fits the whole morph — it is never the thing that
 *     clips.
 *  2. **Nothing animates unless something is happening.** The resting orb is completely still. There
 *     is no infinite transition in the idle state, which is the state the user is in when they drag
 *     the orb — the old breathing orb was recomposing every frame against the drag's own window writes
 *     and is a large part of why the drag felt rough.
 *  3. **A gesture is answered by the thing the user touched.** Press and drag state drive a scale on
 *     the pill surface, never on the window: the orb under the finger shrinks a little and the drag
 *     never resizes or re-anchors anything.
 *
 * ## The motion itself
 *
 *  * **State change** — the surface morphs to the new size on one [FastOutSlowInEasing] clock, its
 *    border colour moves with it, and a soft indigo sheen sweeps across the glass once. The sheen is
 *    the acknowledgement: it fires on every state change, so a tap visibly "lands" even when the
 *    transition replaces the content before it can be read.
 *  * **Content** — the outgoing pill fades out fast (80ms) and the incoming one fades in a beat later
 *    (120ms, starting at [CONTENT_IN_DELAY_MS]), so contents arrive *after* the capsule has opened
 *    rather than being squeezed inside it while it grows.
 *  * **Press** — the orb tightens to [PRESSED_SCALE] while the finger is down, and returns to full
 *    size the moment the gesture is resolved, dragging included. It deliberately does **not** grow
 *    while being carried: an orb that inflates under the finger reads as lag, not as feedback.
 *
 * @param pressed the finger is down on the orb; the tap has not been resolved yet.
 * @param dragging the gesture turned out to be a move, so the press is not a press.
 * @param cornerRadiusDp user-adjustable corner radius, driven by Settings.
 * @param showWaveform when `false` the recording capsule shows a steady pulse instead of live bars.
 * @param onPillSizeChanged reports the pill's target size in dp, once per change of state.
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
    onPillSizeChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val shape = TyporbShapes.overlayCorner(cornerRadiusDp)
    val (targetWidthDp, targetHeightDp) = OverlayMetrics.pillSizeDp(state, idleSizeDp)

    // The window learns the pill's *target* size and nothing else. `snapshotFlow` emits when that
    // pair changes — i.e. once per state change — and stays silent for every frame in between, which
    // is the entire point: the window must not be re-written while the pill is being animated.
    val reportSize by rememberUpdatedState(onPillSizeChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { targetWidthDp to targetHeightDp }.collect { (widthDp, heightDp) ->
            reportSize(widthDp, heightDp)
        }
    }

    val widthDp by animateIntAsState(
        targetValue = targetWidthDp,
        animationSpec = tween(OverlayMetrics.MORPH_MS, easing = ORB_EASING),
        label = "pill-width",
    )
    val heightDp by animateIntAsState(
        targetValue = targetHeightDp,
        animationSpec = tween(OverlayMetrics.MORPH_MS, easing = ORB_EASING),
        label = "pill-height",
    )

    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !dragging) PRESSED_SCALE else 1f,
        animationSpec = tween(PRESS_MS, easing = ORB_EASING),
        label = "press-scale",
    )

    val borderColor by animateColorAsState(
        targetValue = borderFor(state),
        animationSpec = tween(OverlayMetrics.MORPH_MS, easing = ORB_EASING),
        label = "pill-border",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .size(widthDp.dp, heightDp.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    // The corner the window is anchored to and the corner a drag holds: a press then
                    // tightens the orb towards the point the user is touching, instead of sliding it
                    // off that point.
                    transformOrigin = TransformOrigin(1f, 0f)
                },
        ) {
            OrbSurface(shape = shape, borderColor = borderColor, sweepKey = state::class) {
                AnimatedContent(
                    targetState = state,
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    // Fade only. The size is the surface's, animated above on its own single clock; a
                    // size transform here would put a second rectangle animation back on top of the
                    // first — the mistake this file exists to have stopped making.
                    transitionSpec = {
                        fadeIn(
                            tween(CONTENT_IN_MS, delayMillis = CONTENT_IN_DELAY_MS, easing = ORB_EASING),
                        ) togetherWith fadeOut(tween(CONTENT_OUT_MS, easing = ORB_EASING))
                    },
                    // Keyed on the state's *kind*, never on the state itself. Recording publishes a
                    // new amplitude list roughly fifteen times a second, and with the default key
                    // every one of those counts as a new target — a fresh fade-in/fade-out per frame,
                    // and a capsule that strobes instead of animating.
                    contentKey = { it::class },
                    label = "typorb-state",
                ) { current ->
                    when (current) {
                        is OverlayUiState.Idle -> IdleContent(heightDp)
                        is OverlayUiState.Recording ->
                            RecordingContent(current.amplitudes, showWaveform)
                        is OverlayUiState.Processing -> ProcessingContent(current.stage.label)
                        is OverlayUiState.Failed -> FailedContent(current.message)
                    }
                }
            }
        }
    }
}

/**
 * The one surface every state is drawn on.
 *
 * One object for the whole life of the window, rather than one per state inside a crossfade: the pill
 * a user sees is a single continuous piece of glass that changes shape, and only its *contents*
 * crossfade. That is what makes the morph read as a morph instead of two different pills swapping.
 *
 * @param sweepKey retriggers the acknowledgement sheen; the state's kind.
 */
@Composable
private fun OrbSurface(
    shape: RoundedCornerShape,
    borderColor: Color,
    sweepKey: Any,
    content: @Composable BoxScope.() -> Unit,
) {
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(sweepKey) {
        sheen.snapTo(0f)
        sheen.animateTo(1f, tween(SHEEN_MS, easing = LinearEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(elevation = TyporbElevation.Overlay, shape = shape, clip = false)
            .clip(shape)
            .background(TyporbPalette.Surface)
            .background(
                Brush.verticalGradient(
                    listOf(TyporbPalette.Indigo.copy(alpha = SURFACE_TINT), Color.Transparent),
                ),
            )
            .drawWithContent {
                drawContent()
                // A narrow diagonal band of indigo travelling across the glass. It costs one gradient
                // rect per frame for [SHEEN_MS] and nothing at all the rest of the time, because at
                // both ends of its travel it is off the surface.
                val travel = size.width + size.height
                val x = -travel + sheen.value * 2f * travel
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            TyporbPalette.Indigo.copy(alpha = SHEEN_ALPHA),
                            Color.Transparent,
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x + size.height, size.height),
                    ),
                )
            }
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Border colour per state, animated by the caller so it moves on the same clock as the size.
 *
 * The tint is the only thing that distinguishes the states at a glance once the shape has settled, so
 * it is part of the morph rather than a hard switch.
 */
private fun borderFor(state: OverlayUiState): Color = when (state) {
    is OverlayUiState.Idle -> TyporbPalette.Border
    is OverlayUiState.Recording -> TyporbPalette.Indigo.copy(alpha = 0.30f)
    is OverlayUiState.Processing -> TyporbPalette.Cobalt.copy(alpha = 0.28f)
    is OverlayUiState.Failed -> TyporbPalette.Danger.copy(alpha = 0.35f)
}

/**
 * Idle: a still orb with an indigo mic.
 *
 * Deliberately still. This is the state the user is in when they drag the orb, and the old revision
 * ran two infinite animations here — a pulsing glow and a geometric "breath" that rescaled the mic
 * every frame — which meant a full recomposition of the overlay for every frame of every drag.
 */
@Composable
private fun IdleContent(heightDp: Int) {
    Icon(
        imageVector = Icons.Rounded.Mic,
        contentDescription = "Start dictation",
        tint = TyporbPalette.Indigo.copy(alpha = MIC_TINT),
        modifier = Modifier.size((heightDp * MIC_FRACTION).dp),
    )
}

/** Recording: live amplitude bars and a stop affordance, filling the capsule. */
@Composable
private fun RecordingContent(amplitudes: List<Float>, showWaveform: Boolean) {
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

/**
 * Five vertical bars whose heights track the last five amplitude samples.
 *
 * Each bar animates with its own tween so the waveform stays fluid at ~20 Hz updates instead of
 * snapping between samples. The per-bar delay is what makes the bars *enter* as a wave when the
 * capsule opens, rather than appearing all at once.
 */
@Composable
private fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    val barCount = com.typorb.domain.DictationCoordinator.WAVEFORM_BARS
    val recent = amplitudes.takeLast(barCount)
    val animatedLevels = List(barCount) { index ->
        animateFloatAsState(
            targetValue = (recent.getOrNull(index) ?: RESTING_LEVEL).coerceIn(MIN_LEVEL, 1f),
            animationSpec = tween(durationMillis = BAR_MS, delayMillis = index * BAR_STAGGER_MS),
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
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(TyporbPalette.Indigo, RoundedCornerShape(5.dp)),
        )
    }
}

/** The "tap to stop" square on the right of the recording capsule. Static — the bars are the life. */
@Composable
private fun StopGlyph() {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(TyporbPalette.Danger, RoundedCornerShape(5.dp)),
    )
}

/** Processing: a rotating indigo sweep border with a stage label and animated ellipsis. */
@Composable
private fun ProcessingContent(label: String) {
    val transition = rememberInfiniteTransition(label = "processing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "sweep",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Measured against the surface, which has a definite size of its own, so the sweep ring
            // follows the capsule rather than a window rectangle mid-morph.
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
        contentAlignment = Alignment.Center,
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
private fun FailedContent(message: String) {
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

private const val RESTING_LEVEL = 0.14f
private const val MIN_LEVEL = 0.08f
private const val MIN_BAR_FRACTION = 0.18f

/** Mic size relative to the orb, so a resized orb keeps its proportions. */
private const val MIC_FRACTION = 0.46f

/** How solid the mic reads at rest. The old glow animation modulated this every frame; it is fixed. */
private const val MIC_TINT = 0.86f

/** Indigo tint along the top of every surface, fading to nothing by the middle. */
private const val SURFACE_TINT = 0.08f

/** How far the pill tightens under the finger. Enough to read as a press, not enough to look broken. */
private const val PRESSED_SCALE = 0.94f

/** The press response is quicker than the morph: it is feedback, and feedback has to feel immediate. */
private const val PRESS_MS = 110

/** Content crossfade. The outgoing content leaves first so the new one is never squeezed in behind it. */
private const val CONTENT_OUT_MS = 80
private const val CONTENT_IN_MS = 120
private const val CONTENT_IN_DELAY_MS = 70

/** One bar's tween, and the stagger that makes the bars rise as a wave. */
private const val BAR_MS = 90
private const val BAR_STAGGER_MS = 22

/** How long the acknowledgement sheen takes to cross the glass, and how strong it is. */
private const val SHEEN_MS = 420
private const val SHEEN_ALPHA = 0.12f

/** The single easing curve every part of the morph shares. */
private val ORB_EASING = FastOutSlowInEasing
