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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typorb.data.Transcript
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.ui.BOTTOM_BAR_CLEARANCE
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.components.GlassSurface
import com.typorb.ui.components.TagChip
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // The second tap on "Clear all" is what actually deletes; reset the armed state after a moment
    // so a stray tap later does not wipe the vault.
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            kotlinx.coroutines.delay(4_000)
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
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recent Transcriptions",
                    fontSize = 22.sp,
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
                    color = if (confirmClear) TyporbPalette.Danger else TyporbPalette.TextSecondary,
                    modifier = Modifier
                        .clip(TyporbShapes.Capsule)
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

        SearchBar(
            query = query,
            onQueryChange = viewModel::onSearchChange,
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (transcripts.isEmpty()) {
            EmptyState(hasQuery = query.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    bottom = BOTTOM_BAR_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = transcripts, key = { it.id }) { transcript ->
                    SwipeToDeleteCard(
                        onDelete = { viewModel.deleteTranscript(transcript.id) },
                    ) {
                        TranscriptCard(
                            transcript = transcript,
                            onCopy = { clipboard.setText(AnnotatedString(transcript.text)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TyporbShapes.Small)
            .background(TyporbPalette.Glass)
            .border(BorderStroke(1.dp, TyporbPalette.GlassBorder), TyporbShapes.Small)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = TyporbPalette.TextMuted,
            modifier = Modifier.size(17.dp),
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
            cursorBrush = SolidColor(TyporbPalette.NeonCyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search transcripts",
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
    onCopy: () -> Unit,
) {
    GlassSurface(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTimestamp(transcript.timestampMs),
                fontSize = 11.sp,
                color = TyporbPalette.TextMuted,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TagChip(
                text = transcript.mode.shortLabel,
                tint = if (transcript.mode == ContextMode.CODE) TyporbPalette.Violet
                else TyporbPalette.NeonCyan,
            )
            Spacer(modifier = Modifier.width(6.dp))
            TagChip(
                text = if (transcript.engine == ProcessingEngine.LOCAL) "Offline" else "Cloud",
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = "Copy transcript",
                tint = TyporbPalette.TextSecondary,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCopy)
                    .padding(7.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = transcript.text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = TyporbPalette.TextPrimary,
        )
    }
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
                .background(TyporbPalette.Danger.copy(alpha = 0.18f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = TyporbPalette.Danger,
                modifier = Modifier.size(17.dp),
            )
            Text(text = "Delete", fontSize = 12.sp, color = TyporbPalette.Danger)
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
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Minimalist concentric-ring glyph instead of an illustration asset.
        Box(contentAlignment = Alignment.Center) {
            listOf(0.34f, 0.58f, 0.84f).forEach { fraction ->
                Box(
                    modifier = Modifier
                        .size((120 * fraction).dp)
                        .clip(CircleShape)
                        .border(
                            BorderStroke(
                                1.dp,
                                TyporbPalette.NeonCyan.copy(alpha = 0.10f + 0.06f * fraction),
                            ),
                            CircleShape,
                        ),
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(TyporbPalette.NeonCyan.copy(alpha = 0.55f)),
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = if (hasQuery) "No transcripts match your search."
            else "No voice transcripts yet. Start typing anywhere!",
            fontSize = 14.sp,
            color = TyporbPalette.TextSecondary,
        )
    }
}

private val DELETE_TRIGGER_WIDTH = 76.dp
private const val SNAP_BACK_MS = 200

private fun formatTimestamp(timestampMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestampMs))