package com.typorb.ui.screens.control

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typorb.data.TyporbSettings
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.ui.BOTTOM_BAR_CLEARANCE
import com.typorb.ui.PermissionStatus
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.components.ElevatedCard
import com.typorb.ui.components.SectionLabel
import com.typorb.ui.components.StatusBadge
import com.typorb.ui.components.TagChip
import com.typorb.ui.components.pressScale
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * Screen 1 — the control centre.
 *
 * Everything here is usable in under five seconds: see whether the service is live, pick an engine,
 * pick a tone, then try it without leaving the app via the sandbox field.
 */
@Composable
fun ControlScreen(
    viewModel: TyporbViewModel,
    permissions: PermissionStatus,
    onOpenSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = BOTTOM_BAR_CLEARANCE),
    ) {
        ControlTopBar(
            serviceActive = permissions.accessibilityService,
            onOpenSettings = onOpenSettings,
        )

        Column(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EngineHeroCard(settings = settings, onSelect = viewModel::setEngine)

            Column {
                SectionLabel("Typing context")
                ModeChips(selected = settings.contextMode, onSelect = viewModel::setContextMode)
            }

            Column {
                SectionLabel("Try it out")
                SandboxCard(settings = settings, permissions = permissions)
            }
        }
    }
}

@Composable
private fun ControlTopBar(
    serviceActive: Boolean,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Typorb",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = TyporbPalette.TextPrimary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        StatusBadge(
            text = if (serviceActive) "Service Ready" else "Needs setup",
            active = serviceActive,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Settings",
                tint = TyporbPalette.TextSecondary,
            )
        }
    }
}

/** The hero card: a segmented pill toggle between the two engines, plus a latency tag. */
@Composable
private fun EngineHeroCard(
    settings: TyporbSettings,
    onSelect: (ProcessingEngine) -> Unit,
) {
    ElevatedCard(contentPadding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Processing engine",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TagChip(
                text = when (settings.engine) {
                    ProcessingEngine.CLOUD -> "~400ms speed"
                    ProcessingEngine.LOCAL -> "Zero data used"
                },
                tint = if (settings.engine == ProcessingEngine.LOCAL) TyporbPalette.Indigo
                else TyporbPalette.Cobalt,
                container = TyporbPalette.SurfaceSunken,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TyporbShapes.Capsule)
                .background(TyporbPalette.SurfaceSunken)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            EngineSegment(
                label = "Cloud Mode",
                caption = "Groq Whisper",
                icon = Icons.Rounded.Cloud,
                selected = settings.engine == ProcessingEngine.CLOUD,
                onClick = { onSelect(ProcessingEngine.CLOUD) },
                modifier = Modifier.weight(1f),
            )
            EngineSegment(
                label = "Offline Local",
                caption = "ONNX",
                icon = Icons.Rounded.FlightTakeoff,
                selected = settings.engine == ProcessingEngine.LOCAL,
                onClick = { onSelect(ProcessingEngine.LOCAL) },
                modifier = Modifier.weight(1f),
            )
        }

        // Surface the one configuration that silently breaks cloud dictation before it happens.
        if (settings.engine == ProcessingEngine.CLOUD && !settings.hasApiKey) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Add a Groq API key in Settings to use Cloud mode.",
                fontSize = 12.sp,
                color = TyporbPalette.Warning,
            )
        }
    }
}

@Composable
private fun EngineSegment(
    label: String,
    caption: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (selected) TyporbPalette.Surface else TyporbPalette.SurfaceSunken,
        animationSpec = tween(180),
        label = "segment-bg",
    )

    Row(
        modifier = modifier
            .pressScale(interactionSource, pressedScale = 0.96f)
            .clip(TyporbShapes.Capsule)
            .background(container)
            .then(
                // The selected segment is a raised white chip inside the sunken track.
                if (selected) {
                    Modifier.border(
                        BorderStroke(1.dp, TyporbPalette.Border),
                        TyporbShapes.Capsule,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 11.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) TyporbPalette.Cobalt else TyporbPalette.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Column {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) TyporbPalette.TextPrimary else TyporbPalette.TextSecondary,
            )
            Text(
                text = caption,
                fontSize = 10.sp,
                color = TyporbPalette.TextMuted,
            )
        }
    }
}

/**
 * Horizontally scrolling filter chips.
 *
 * The active chip is a solid indigo fill with white text; the rest sit on a clean off-white surface
 * with a hairline border.
 */
@Composable
private fun ModeChips(
    selected: ContextMode,
    onSelect: (ContextMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ContextMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val interactionSource = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .pressScale(interactionSource, pressedScale = 0.95f)
                    .clip(TyporbShapes.Capsule)
                    .background(if (isSelected) TyporbPalette.Cobalt else TyporbPalette.Surface)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) TyporbPalette.Cobalt else TyporbPalette.Border,
                        ),
                        TyporbShapes.Capsule,
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { onSelect(mode) }
                    .padding(horizontal = 15.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "${mode.emoji} ${mode.chipLabel}",
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) TyporbPalette.OnAccent else TyporbPalette.TextSecondary,
                )
            }
        }
    }
}

/**
 * A real editable field inside the app.
 *
 * Tapping it raises the soft keyboard, which is exactly the trigger the accessibility service
 * watches for — so it is a genuine end-to-end test of the floating Typorb without leaving Typorb.
 */
@Composable
private fun SandboxCard(
    settings: TyporbSettings,
    permissions: PermissionStatus,
) {
    var value by rememberSaveable { mutableStateOf("") }

    ElevatedCard(contentPadding = 14.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TyporbShapes.Small)
                .background(TyporbPalette.SurfaceSunken)
                .border(BorderStroke(1.dp, TyporbPalette.Border), TyporbShapes.Small)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TyporbPalette.TextPrimary,
                    fontSize = 14.sp,
                ),
                cursorBrush = SolidColor(TyporbPalette.Cobalt),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "Tap here to open keyboard and test Typorb overlay...",
                                fontSize = 13.sp,
                                color = TyporbPalette.TextMuted,
                            )
                        }
                        inner()
                    }
                },
            )
            // Quick-clear, only present once there is something to clear.
            if (value.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Cancel,
                    contentDescription = "Clear",
                    tint = TyporbPalette.TextMuted,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { value = "" }
                        .padding(5.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = when {
                !permissions.accessibilityService ->
                    "Enable the accessibility service so Typorb can show the orb here."
                settings.engine == ProcessingEngine.CLOUD && !settings.hasApiKey ->
                    "Cloud mode needs an API key — add one in Settings first."
                else ->
                    "Now tap the orb and speak. The text lands in this box."
            },
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )
    }
}