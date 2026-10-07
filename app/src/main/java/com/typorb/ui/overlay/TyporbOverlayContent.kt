package com.typorb.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.R
import com.typorb.data.TyporbSettings
import com.typorb.domain.DictationCoordinator
import com.typorb.model.OverlayUiState
import com.typorb.overlay.OverlayMetrics
import com.typorb.overlay.WaveformGate
import com.typorb.ui.theme.TyporbElevation
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The floating Typorb: **the app's icon in a tile, and a panel that slides out of its side.**
 *
 * ## The shape
 *
 * The tile is the orb. It carries the app icon, it is the thing the user taps, and it is the thing
 * that stays put: it never resizes, never changes shape and never moves while a state changes around
 * it. Everything a state has to say is said by a **panel that grows out of the tile's left edge**:
 *
 *  * **Idle** — the tile on its own, completely still. This is the "simple box" the orb collapses to.
 *  * **Recording** — a panel slides out to the left of the tile carrying the live waveform.
 *  * **Processing** — the panel is gone again and the tile itself wears a slow ring, because the
 *    second tap is a request to *stop*, and what the user should see afterwards is their orb back the
 *    way they left it while the words are being written.
 *  * **Failed** — the panel comes back, tinted red, carrying the reason the last dictation did not
 *    land. It is the one state whose text has to be read, so it is the one panel that sizes itself to
 *    its message.
 *
 * The tile is drawn from `@drawable-nodpi/orb_icon`, the icon the user supplied, cropped to the
 * artwork and clipped to the same corner radius as the panel — so the orb reads as one product with a
 * launcher icon rather than a control with a logo glued on.
 *
 * ## The animation rules this file is built on
 *
 * An earlier revision animated the orb three ways at once and the user's verdict was blunt: the
 * animation on tap was the worst part of the app. It was. A tap ran a `ValueAnimator` on the window
 * rectangle **and** a Compose `AnimatedContent` size transform **and** an idle "breathe" on an
 * infinite transition; the window is the clip rectangle the pill is drawn inside, so whenever the two
 * clocks disagreed — which was most frames — the pill was cut off by its own window, and the orb had a
 * perpetual 60fps animation running underneath every gesture, including the drag.
 *
 * Everything in here still follows from the rules that fixed it, and the shape above is what makes
 * them easy to keep:
 *
 *  1. **One number changes size, and it is the pill's width.** The tile's size is constant, so the
 *     only thing animated is the *total* width, and the panel's width is derived from it. There
 *     is no second rectangle animating anywhere, and nothing changes the window's extent.
 *  2. **Nothing animates unless something is happening.** The resting tile is completely still — no
 *     breath, no glow. The tile is the state the user is in when they drag the orb, and an animation
 *     running underneath a drag is a recomposition the drag cannot afford.
 *  3. **A gesture is answered by the thing the user touched.** Press and drag drive a scale on the
 *     tile-plus-panel, never on the window: the orb under the finger tightens a little and the drag
 *     never resizes or re-anchors anything.
 *
 * @param pressed the finger is down on the orb; the tap has not been resolved yet.
 * @param dragging the gesture turned out to be a move, so the press is not a press.
 * @param cornerRadiusDp user-adjustable corner radius, driven by Settings.
 * @param showWaveform when `false` the recording panel shows a steady pulse instead of live bars.
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
    val tileSizeDp = idleSizeDp
    val (targetWidthDp, targetHeightDp) = OverlayMetrics.pillSizeDp(state, tileSizeDp)

    // The window learns the pill's *target* size and nothing else. `snapshotFlow` emits when that
    // pair changes — i.e. once per state change — and stays silent for every frame in between, which
    // is the entire point: the window must not be re-written while the pill is being animated.
    val reportSize by rememberUpdatedState(onPillSizeChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { targetWidthDp to targetHeightDp }.collect { (widthDp, heightDp) ->
            reportSize(widthDp, heightDp)
        }
    }

    // Only the width moves. The pill's height is the tile's height in every state, so animating it
    // would be animating a constant — and a second animation is a second clock.
    val widthDp by animateIntAsState(
        targetValue = targetWidthDp,
        animationSpec = tween(OverlayMetrics.MORPH_MS, easing = ORB_EASING),
        label = "pill-width",
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
        Row(
            modifier = Modifier
                .size(widthDp.dp, tileSizeDp.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    // The corner the window is anchored to and the corner a drag holds: a press then
                    // tightens the orb towards the point the user is touching, instead of sliding it
                    // off that point.
                    transformOrigin = TransformOrigin(1f, 0f)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left of the tile, so it grows *away* from the screen edge the orb is pinned to and the
            // tile underneath the user's finger does not move by a pixel.
            SidePanel(
                slotWidthDp = (widthDp - tileSizeDp).coerceAtLeast(0),
                state = state,
                shape = shape,
                borderColor = borderColor,
                showWaveform = showWaveform,
            )
            OrbTile(
                sizeDp = tileSizeDp,
                shape = shape,
                borderColor = borderColor,
                processing = state is OverlayUiState.Processing,
            )
        }
    }
}

/**
 * The panel beside the tile: a glass capsule holding whatever the current state has to show.
 *
 * Its width is the difference between the pill's animated width and the tile, minus the gap the two
 * surfaces keep between them. Deriving it from the animation rather than animating it separately is
 * what keeps the whole thing on one clock — the panel is the *remainder* of the pill, so it cannot
 * disagree with it.
 *
 * @param slotWidthDp everything the pill has left over beside the tile, [OverlayMetrics.PANEL_GAP_DP]
 *   included.
 */
@Composable
private fun SidePanel(
    slotWidthDp: Int,
    state: OverlayUiState,
    shape: RoundedCornerShape,
    borderColor: Color,
    showWaveform: Boolean,
) {
    val panelWidthDp = slotWidthDp - OverlayMetrics.PANEL_GAP_DP
    // Nothing to draw yet. The first PANEL_GAP_DP of the expansion is spent opening the gap, so the
    // panel appears once the slot is wider than that — and, collapsing, it is dropped the moment it
    // would have no width left, rather than compositing a zero-width surface for the last few frames.
    if (panelWidthDp <= 0) return

    Box(
        modifier = Modifier
            .width(slotWidthDp.dp)
            .fillMaxHeight()
            .padding(end = OverlayMetrics.PANEL_GAP_DP.dp),
    ) {
        PanelSurface(shape = shape, borderColor = borderColor, sweepKey = state::class) {
            AnimatedContent(
                targetState = state,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
                // Fade only. The size is the pill's, animated on its own single clock; a size
                // transform here would put a second rectangle animation on top of the first — the
                // mistake this file exists to have stopped making.
                transitionSpec = {
                    fadeIn(
                        tween(CONTENT_IN_MS, delayMillis = CONTENT_IN_DELAY_MS, easing = ORB_EASING),
                    ) togetherWith fadeOut(tween(CONTENT_OUT_MS, easing = ORB_EASING))
                },
                // Keyed on the state's *kind*, never on the state itself. Recording publishes a new
                // amplitude list roughly fifteen times a second, and with the default key every one of
                // those counts as a new target — a fresh fade-in/fade-out per frame, and a panel that
                // strobes instead of animating.
                contentKey = { it::class },
                label = "typorb-state",
            ) { current ->
                when (current) {
                    is OverlayUiState.Recording ->
                        RecordingContent(current.amplitudes, showWaveform)

                    is OverlayUiState.Failed -> FailedContent(current.message)
                    // Idle and Processing are tile-only states; the panel is not on screen in either.
                    is OverlayUiState.Idle, is OverlayUiState.Processing -> Unit
                }
            }
        }
    }
}

/**
 * The glass capsule: the surface every panel is drawn on.
 *
 * @param sweepKey retriggers the acknowledgement sheen; the state's kind.
 */
@Composable
private fun PanelSurface(
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
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/**
 * The orb itself: the app's icon, square, and the only thing on screen when nothing is happening.
 *
 * The icon is the whole tile rather than a glyph floating in glass. It is what the user supplied, it
 * is what the launcher shows, and it means the resting orb is recognisable as Typorb at a glance
 * instead of being one more anonymous dark square over their keyboard.
 *
 * While the engine works, a rotating cobalt arc runs around the tile's inside edge over a light scrim.
 * The ring is the entire progress affordance now that the recording panel collapses on the second tap:
 * it is small, it is on the thing the user just tapped, and it costs nothing while it is not running.
 */
@Composable
private fun OrbTile(
    sizeDp: Int,
    shape: RoundedCornerShape,
    borderColor: Color,
    processing: Boolean,
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .shadow(elevation = TyporbElevation.Overlay, shape = shape, clip = false)
            .clip(shape)
            // Behind the artwork: the icon is opaque, but a density-less drawable is still decoded
            // asynchronously, and an unpainted tile for one frame reads as a flicker.
            .background(TyporbPalette.SurfaceSunken)
            .border(1.dp, borderColor, shape),
    ) {
        Image(
            painter = painterResource(R.drawable.orb_icon),
            contentDescription = "Typorb",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (processing) ProcessingRing()
    }
}

/** The tile's working state: a light scrim and a cobalt arc sweeping the tile's inside edge. */
@Composable
private fun ProcessingRing() {
    val transition = rememberInfiniteTransition(label = "processing")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1_100, easing = LinearEasing)),
        label = "sweep",
    )

    Box(modifier = Modifier.fillMaxSize().background(TyporbPalette.Surface.copy(alpha = SCRIM))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = RING_STROKE_PX / 2f
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
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    style = Stroke(width = RING_STROKE_PX),
                )
            }
        }
    }
}

/**
 * Border colour per state, animated by the caller so it moves on the same clock as the size.
 *
 * The tint is the only thing that distinguishes the states at a glance once the shape has settled, so
 * it is part of the morph rather than a hard switch. It is applied to the tile *and* the panel, which
 * is what makes the two read as one object while the panel is out.
 */
private fun borderFor(state: OverlayUiState): Color = when (state) {
    is OverlayUiState.Idle -> TyporbPalette.Border
    is OverlayUiState.Recording -> TyporbPalette.Indigo.copy(alpha = 0.30f)
    is OverlayUiState.Processing -> TyporbPalette.Cobalt.copy(alpha = 0.28f)
    is OverlayUiState.Failed -> TyporbPalette.Danger.copy(alpha = 0.35f)
}

/** Recording: the live waveform filling the panel. */
@Composable
private fun RecordingContent(amplitudes: List<Float>, showWaveform: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PANEL_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showWaveform) {
            Waveform(amplitudes = amplitudes)
        } else {
            SteadyPulse()
        }
    }
}

/**
 * The waveform: the sentence, drawn.
 *
 * Bars grow **up and down** from the line through the panel's middle, so the shape reads as sound
 * rather than as a bar chart, and when the user stops speaking they collapse back onto that line —
 * which is drawn underneath them for the whole time they are out. That is the "straight line" the orb
 * shows in a pause: the rail on its own, spanning the panel, with the bars indistinguishable from it
 * because at zero level they are the same height and the same colour.
 *
 * Each bar eases towards its own sample ([BAR_MS]) and is staggered by [BAR_STAGGER_MS], which is what
 * makes the bars enter as a wave when the panel opens instead of appearing all at once. The silence
 * decision itself is [WaveformGate]'s, not this composable's.
 */
@Composable
private fun Waveform(amplitudes: List<Float>) {
    val barCount = DictationCoordinator.WAVEFORM_BARS
    val recent = amplitudes.takeLast(barCount)
    val levels = List(barCount) { index ->
        animateFloatAsState(
            targetValue = WaveformGate.level(recent.getOrNull(index) ?: 0f),
            animationSpec = tween(durationMillis = BAR_MS, delayMillis = index * BAR_STAGGER_MS),
            label = "bar-$index",
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(WAVE_HEIGHT_DP.dp),
    ) {
        val railHeight = RAIL_HEIGHT_DP.dp.toPx()
        val centre = size.height / 2f
        // One gradient across the whole waveform rather than one per bar: the bars then share a single
        // ramp by position, so a tall bar next to a short one is a change in height and not in colour.
        val brush = Brush.verticalGradient(
            colors = TyporbPalette.WaveformGradient,
            startY = 0f,
            endY = size.height,
        )

        // The rail: what is left when the user stops speaking.
        drawRoundRect(
            color = TyporbPalette.Cobalt.copy(alpha = RAIL_ALPHA),
            topLeft = Offset(0f, centre - railHeight / 2f),
            size = Size(size.width, railHeight),
            cornerRadius = CornerRadius(railHeight / 2f, railHeight / 2f),
        )

        val gap = size.width * BAR_GAP_FRACTION / barCount
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val barRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        levels.forEachIndexed { index, animated ->
            val level = animated.value
            val barHeight = railHeight + (size.height - railHeight) * level
            drawRoundRect(
                brush = brush,
                // Quiet bars fade into the rail, so a silence is one uninterrupted line rather than a
                // line with dashes sitting on it.
                alpha = RAIL_ALPHA + (1f - RAIL_ALPHA) * level,
                topLeft = Offset(index * (barWidth + gap), centre - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = barRadius,
            )
        }
    }
}

/** The waveform-off state: a calm centred pulse rather than a live meter. */
@Composable
private fun SteadyPulse() {
    val transition = rememberInfiniteTransition(label = "steady-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "steady-scale",
    )
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

/** Failure: the reason the last dictation did not land, in a red-tinted panel. */
@Composable
private fun FailedContent(message: String) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PANEL_PADDING_DP.dp),
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
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How much air the panel's content keeps off its own edges. */
private const val PANEL_PADDING_DP = 16

/** Height of the waveform canvas inside a 48dp panel — tall enough to see, short enough to breathe. */
private const val WAVE_HEIGHT_DP = 26

/** The straight line the waveform collapses onto when the user stops speaking. */
private const val RAIL_HEIGHT_DP = 2

/** How solid that line is. Quiet bars fade to exactly this, which is what makes it one line. */
private const val RAIL_ALPHA = 0.35f

/** Share of the waveform's width given over to the gaps between bars. */
private const val BAR_GAP_FRACTION = 0.28f

/** Indigo tint along the top of the panel, fading to nothing by the middle. */
private const val SURFACE_TINT = 0.08f

/** How far the orb tightens under the finger. Enough to read as a press, not enough to look broken. */
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

/** How long the acknowledgement sheen takes to cross the panel, and how strong it is. */
private const val SHEEN_MS = 420
private const val SHEEN_ALPHA = 0.12f

/** The working ring: how thick the arc is, in px, and how far the icon is dimmed behind it. */
private const val RING_STROKE_PX = 3f
private const val SCRIM = 0.45f

/** The single easing curve every part of the morph shares. */
private val ORB_EASING = FastOutSlowInEasing
