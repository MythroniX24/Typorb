package com.typorb.ui.screens.vault

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typorb.data.Transcript
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.ui.rememberBottomBarClearance
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.components.ElevatedCard
import com.typorb.ui.components.TagChip
import com.typorb.ui.components.entrance
import com.typorb.ui.vaultIcon
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes
import com.typorb.ui.util.RelativeTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen 2 — the transcript vault.
 *
 * Every dictation the accessibility service successfully injected is filed here, so a user can
 * re-copy something they dictated minutes ago without re-recording it.
 */
@Composable
fun VaultScreen(viewModel: TyporbViewModel) {
    val transcripts by viewModel.filteredTranscripts.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val total by viewModel.transcripts.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val bottomClearance = rememberBottomBarClearance()

    // Ticks so "Just now" ages into "2 mins ago" while the screen is open. Deriving it from the
    // list (the previous approach) only refreshed the badges when a transcript was added or removed,
    // so a screen left open showed frozen timestamps.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(CLOCK_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    // The second tap on "Clear all" is what actually deletes; reset the armed state after a moment
    // so a stray tap later does not wipe the vault.
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            kotlinx.coroutines.delay(CLEAR_ARM_TIMEOUT_MS)
            confirmClear = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .entrance(index = 0)
                .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Saved Dictations",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TyporbPalette.TextPrimary,
                )
                Text(
                    text = if (total.isEmpty()) "Nothing stored yet"
                    else "${total.size} saved · newest first",
                    fontSize = 12.sp,
                    color = TyporbPalette.TextMuted,
                )
            }
            if (total.isNotEmpty()) {
                Text(
                    text = if (confirmClear) "Tap again" else "Clear all",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (confirmClear) TyporbPalette.Danger else TyporbPalette.TextSecondary,
                    modifier = Modifier
                        .clip(TyporbShapes.Capsule)
                        .background(
                            if (confirmClear) TyporbPalette.DangerTint else TyporbPalette.Surface,
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                if (confirmClear) TyporbPalette.Danger.copy(alpha = 0.3f)
                                else TyporbPalette.Border,
                            ),
                            TyporbShapes.Capsule,
                        )
                        .clickable {
                            if (confirmClear) {
                                viewModel.clearTranscripts()
                                confirmClear = false
                            } else {
                                confirmClear = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        SearchFilter(
            query = query,
            onQueryChange = viewModel::onSearchChange,
            modifier = Modifier
                .entrance(index = 1)
                .padding(horizontal = 18.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (transcripts.isEmpty()) {
            EmptyState(hasQuery = query.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    bottom = bottomClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = transcripts, key = { it.id }) { transcript ->
                    SwipeToDeleteCard(onDelete = { viewModel.deleteTranscript(transcript.id) }) {
                        TranscriptCard(
                            transcript = transcript,
                            nowMs = nowMs,
                            onCopy = { clipboard.setText(AnnotatedString(transcript.text)) },
                            onDelete = { viewModel.deleteTranscript(transcript.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TyporbShapes.Small)
            .background(TyporbPalette.Surface)
            .border(BorderStroke(1.dp, TyporbPalette.Border), TyporbShapes.Small)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = TyporbPalette.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TyporbPalette.TextPrimary,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(TyporbPalette.Cobalt),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Filter transcripts",
                        fontSize = 14.sp,
                        color = TyporbPalette.TextMuted,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun TranscriptCard(
    transcript: Transcript,
    nowMs: Long,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagChip(
                text = RelativeTime.format(transcript.timestampMs, nowMs),
                tint = TyporbPalette.TextSecondary,
            )
            Spacer(modifier = Modifier.width(7.dp))
            TagChip(
                text = transcript.mode.shortLabel,
                tint = if (transcript.mode == ContextMode.CODE) TyporbPalette.Indigo
                else TyporbPalette.Cobalt,
                container = TyporbPalette.SurfaceSunken,
                icon = transcript.mode.vaultIcon,
            )
            Spacer(modifier = Modifier.width(6.dp))
            TagChip(
                text = if (transcript.engine == ProcessingEngine.LOCAL) "Offline" else "Cloud",
                tint = TyporbPalette.TextMuted,
                container = TyporbPalette.SurfaceSunken,
            )
            Spacer(modifier = Modifier.weight(1f))

            IconAction(
                icon = Icons.Rounded.ContentCopy,
                description = "Copy transcript",
                tint = TyporbPalette.TextSecondary,
                onClick = onCopy,
            )
            Spacer(modifier = Modifier.width(2.dp))
            IconAction(
                icon = Icons.Rounded.DeleteOutline,
                description = "Delete transcript",
                tint = TyporbPalette.TextMuted,
                onClick = onDelete,
            )
        }

        Spacer(modifier = Modifier.height(9.dp))

        Text(
            text = transcript.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = TyporbPalette.TextPrimary,
        )
    }
}

/** Minimalist circular icon button used for the per-item actions. */
@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(TyporbPalette.SurfaceSunken)
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

/**
 * Reveals a delete affordance when the card is dragged left, and commits the delete past a
 * threshold — then springs the card back when the drag was not far enough.
 *
 * Uses [draggable] rather than `detectHorizontalDragGestures` because the drag callback is not a
 * coroutine scope: `Animatable.snapTo` is a suspending function, so driving it from the raw gesture
 * callback would need a coroutine launch per pointer event. Plain state keeps the drag 1:1 with the
 * finger, and [animateFloatAsState] animates only the snap-back.
 */
@Composable
private fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var animating by remember { mutableStateOf(false) }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (animating) tween(SNAP_BACK_MS) else snap(),
        label = "swipe-offset",
    )

    val deleteTriggerPx = with(LocalDensity.current) { DELETE_TRIGGER_WIDTH.toPx() }

    val draggableState = rememberDraggableState { delta ->
        // Never allow dragging rightwards; only the delete direction is meaningful.
        animating = false
        dragOffset = (dragOffset + delta).coerceAtMost(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TyporbShapes.Medium),
    ) {
        // Delete layer stays put while the card slides over it.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .clip(TyporbShapes.Small)
                .background(TyporbPalette.DangerTint)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = TyporbPalette.Danger,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "Delete",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TyporbPalette.Danger,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (abs(dragOffset) >= deleteTriggerPx) {
                            onDelete()
                        }
                        // Springs the card closed either way; after a delete the item is already
                        // gone from the list, so this animation is never seen.
                        animating = true
                        dragOffset = 0f
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(TyporbPalette.SurfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Notes,
                contentDescription = null,
                tint = TyporbPalette.TextMuted,
                modifier = Modifier.size(34.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (hasQuery) "No transcripts match your search."
            else "No transcripts recorded yet.",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = TyporbPalette.TextSecondary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (!hasQuery) {
            Text(
                text = "Dictate anywhere and your text will be saved here.",
                fontSize = 12.sp,
                color = TyporbPalette.TextMuted,
            )
        }
    }
}

private val DELETE_TRIGGER_WIDTH = 76.dp
private const val SNAP_BACK_MS = 200
private const val CLEAR_ARM_TIMEOUT_MS = 4_000L

/** Badge refresh cadence: often enough to stay honest, rare enough to be free. */
private const val CLOCK_TICK_MS = 30_000L