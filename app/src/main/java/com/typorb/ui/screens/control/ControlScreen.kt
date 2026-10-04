package com.typorb.ui.screens.control

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
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
import com.typorb.ui.components.GlassSurface
import com.typorb.ui.components.SectionLabel
import com.typorb.ui.components.StatusPill
import com.typorb.ui.components.TagChip
import com.typorb.ui.theme.TyporbGradients
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * Screen 1 — the control centre.
 *
 * Everything here is designed to be usable in under five seconds: see whether the service is live,
 * pick an engine, pick a tone, then try it without leaving the app via the sandbox field.
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            EngineHeroCard(
                settings = settings,
                onSelect = viewModel::setEngine,
            )

            SectionLabel("Context mode")
            ModeChips(
                selected = settings.contextMode,
                onSelect = viewModel::setContextMode,
            )

            SectionLabel("Try it out")
            SandboxCard(settings = settings, permissions = permissions)
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
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(TyporbGradients.AccentHorizontal),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Typorb",
            fontSize = 24.sp,
            color = TyporbPalette.TextPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        StatusPill(
            text = if (serviceActive) "Service Active" else "Needs setup",
            active = serviceActive,
            tint = if (serviceActive) TyporbPalette.Success else TyporbPalette.Warning,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = TyporbPalette.TextSecondary,
            )
        }
    }
}

/** The segmented engine picker with its live latency tag. */
@Composable
private fun EngineHeroCard(
    settings: TyporbSettings,
    onSelect: (ProcessingEngine) -> Unit,
) {
    GlassSurface(contentPadding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Processing engine",
                fontSize = 14.sp,
                color = TyporbPalette.TextPrimary,
            )
            TagChip(
                text = when (settings.engine) {
                    ProcessingEngine.CLOUD -> "~400ms"
                    ProcessingEngine.LOCAL -> "0 KB Data"
                },
                tint = if (settings.engine == ProcessingEngine.LOCAL) TyporbPalette.Violet
                else TyporbPalette.NeonCyan,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TyporbShapes.Small)
                .background(TyporbPalette.SurfaceElevated)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EngineSegment(
                label = "Groq Cloud",
                caption = "Ultra fast",
                icon = Icons.Rounded.Bolt,
                selected = settings.engine == ProcessingEngine.CLOUD,
                onClick = { onSelect(ProcessingEngine.CLOUD) },
                modifier = Modifier.weight(1f),
            )
            EngineSegment(
                label = "Local ONNX",
                caption = "Offline",
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (selected) TyporbPalette.Glass else TyporbPalette.TextPrimary.copy(alpha = 0f),
        label = "segment-bg",
    )
    Row(
        modifier = modifier
            .clip(TyporbShapes.Small)
            .background(background)
            .border(
                BorderStroke(
                    1.dp,
                    if (selected) TyporbPalette.NeonCyan.copy(alpha = 0.45f)
                    else TyporbPalette.TextPrimary.copy(alpha = 0f),
                ),
                TyporbShapes.Small,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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
            tint = if (selected) TyporbPalette.NeonCyan else TyporbPalette.TextMuted,
            modifier = Modifier.size(17.dp),
        )
        Column {
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (selected) TyporbPalette.TextPrimary else TyporbPalette.TextMuted,
            )
            Text(
                text = caption,
                fontSize = 10.sp,
                color = TyporbPalette.TextMuted,
            )
        }
    }
}

/** Horizontally scrolling filter chips; the active one gets the cyan border and violet glow. */
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
            Box(
                modifier = Modifier
                    .clip(TyporbShapes.Capsule)
                    .background(
                        if (isSelected) {
                            Brush.horizontalGradient(
                                TyporbGradients.Accent.map { it.copy(alpha = 0.18f) },
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(
                                    TyporbPalette.GlassElevated,
                                    TyporbPalette.GlassElevated,
                                ),
                            )
                        },
                        TyporbShapes.Capsule,
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) TyporbPalette.NeonCyan.copy(alpha = 0.65f)
                            else TyporbPalette.GlassBorder,
                        ),
                        TyporbShapes.Capsule,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(mode) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "${mode.emoji} ${mode.shortLabel}",
                    fontSize = 12.sp,
                    color = if (isSelected) TyporbPalette.TextPrimary else TyporbPalette.TextSecondary,
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
    GlassSurface(contentPadding = 14.dp) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Tap here to trigger keyboard and test the floating Typorb box...",
                    fontSize = 13.sp,
                    color = TyporbPalette.TextMuted,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TyporbPalette.TextPrimary,
                fontSize = 14.sp,
            ),
            shape = TyporbShapes.Small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TyporbPalette.Surface,
                unfocusedContainerColor = TyporbPalette.Surface,
                focusedBorderColor = TyporbPalette.NeonCyan.copy(alpha = 0.5f),
                unfocusedBorderColor = TyporbPalette.GlassBorder,
                cursorColor = TyporbPalette.NeonCyan,
            ),
        )
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