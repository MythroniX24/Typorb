package com.typorb.ui.screens.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typorb.data.ModelCatalog
import com.typorb.data.OfflineModelState
import com.typorb.data.TyporbSettings
import com.typorb.ui.BOTTOM_BAR_CLEARANCE
import com.typorb.ui.PermissionStatus
import com.typorb.ui.PermissionTarget
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.components.AccentButton
import com.typorb.ui.components.GhostButton
import com.typorb.ui.components.GlassSurface
import com.typorb.ui.components.SectionLabel
import com.typorb.ui.components.SettingRow
import com.typorb.ui.components.TagChip
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/** Screen 3 — API credentials, the offline model, permissions and overlay customisation. */
@Composable
fun ConfigScreen(
    viewModel: TyporbViewModel,
    permissions: PermissionStatus,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = BOTTOM_BAR_CLEARANCE),
    ) {
        Text(
            text = "Settings",
            fontSize = 24.sp,
            color = TyporbPalette.TextPrimary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp),
        )

        Column(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionLabel("Groq API")
            ApiKeyCard(viewModel = viewModel, settings = settings)

            SectionLabel("Offline model")
            ModelManagerCard(
                viewModel = viewModel,
                settings = settings,
                state = modelState,
            )

            SectionLabel("Permissions")
            PermissionCard(viewModel = viewModel, permissions = permissions)

            SectionLabel("Customization")
            CustomizationCard(viewModel = viewModel, settings = settings)
        }
    }
}

// --------------------------------------------------------------------- API key

@Composable
private fun ApiKeyCard(viewModel: TyporbViewModel, settings: TyporbSettings) {
    val input by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val saved by viewModel.apiKeySaved.collectAsStateWithLifecycle()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    GlassSurface(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Groq API key",
                fontSize = 14.sp,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (settings.hasApiKey) {
                TagChip(text = "Key stored", tint = TyporbPalette.Success)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Required for ⚡ Cloud mode. Stored encrypted on this device only.",
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TyporbShapes.Small)
                .background(TyporbPalette.Surface)
                .border(BorderStroke(1.dp, TyporbPalette.GlassBorder), TyporbShapes.Small)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = viewModel::onApiKeyChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !settings.hasApiKey,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TyporbPalette.TextPrimary,
                    fontSize = 14.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TyporbPalette.NeonCyan),
                visualTransformation = if (revealed) VisualTransformation.None
                else PasswordVisualTransformation(),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = if (settings.hasApiKey) "••••••••••••••••" else "gsk_…",
                                fontSize = 14.sp,
                                color = TyporbPalette.TextMuted,
                            )
                        }
                        inner()
                    }
                },
            )
            // Paste shortcut — the key almost always arrives via a browser or password manager.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable {
                        viewModel.onApiKeyChange(clipboard.getText()?.text.orEmpty())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = "Paste API key",
                    tint = TyporbPalette.TextSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        if (input.isNotBlank() && !saved) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Looks too short to be a Groq key — expected 20+ characters.",
                fontSize = 11.sp,
                color = TyporbPalette.Warning,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (settings.hasApiKey) {
                Row(
                    modifier = Modifier
                        .clip(TyporbShapes.Small)
                        .clickable { revealed = !revealed }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = TyporbPalette.Success,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(text = "Key saved", fontSize = 12.sp, color = TyporbPalette.Success)
                }
                Spacer(modifier = Modifier.width(6.dp))
                GhostButton(
                    text = "Remove",
                    onClick = viewModel::clearApiKey,
                    icon = Icons.Rounded.DeleteOutline,
                    tint = TyporbPalette.Danger,
                )
            } else {
                AccentButton(
                    text = "Save key",
                    onClick = viewModel::saveApiKey,
                    enabled = input.isNotBlank(),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(TyporbShapes.Capsule)
                    .clickable { runCatching { uriHandler.openUri(GROQ_CONSOLE_URL) } }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "Get Free Groq API Key",
                    fontSize = 12.sp,
                    color = TyporbPalette.NeonCyan,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = TyporbPalette.NeonCyan,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

// ----------------------------------------------------------------- model card

@Composable
private fun ModelManagerCard(
    viewModel: TyporbViewModel,
    settings: TyporbSettings,
    state: OfflineModelState,
) {
    GlassSurface(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Offline model",
                fontSize = 14.sp,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TagChip(
                text = when (state) {
                    is OfflineModelState.Ready -> formatBytes(state.onDiskBytes)
                    is OfflineModelState.NotInstalled -> formatBytes(state.totalBytes)
                    is OfflineModelState.Downloading -> "${(state.overallProgress * 100).toInt()}%"
                    is OfflineModelState.Corrupt -> "Corrupt"
                    is OfflineModelState.Failed -> "Failed"
                },
                tint = when (state) {
                    is OfflineModelState.Ready -> TyporbPalette.Success
                    is OfflineModelState.Corrupt, is OfflineModelState.Failed -> TyporbPalette.Danger
                    else -> TyporbPalette.TextSecondary
                },
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewModel.modelVariants.forEach { variant ->
                val selected = variant.id == settings.modelVariantId
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(TyporbShapes.Small)
                        .background(
                            if (selected) TyporbPalette.GlassElevated
                            else TyporbPalette.Surface.copy(alpha = 0.6f),
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                if (selected) TyporbPalette.Violet.copy(alpha = 0.55f)
                                else TyporbPalette.GlassBorder,
                            ),
                            TyporbShapes.Small,
                        )
                        .clickable { viewModel.selectModelVariant(variant) }
                        .padding(12.dp),
                ) {
                    Text(
                        text = variant.label,
                        fontSize = 12.sp,
                        color = if (selected) TyporbPalette.TextPrimary else TyporbPalette.TextMuted,
                    )
                    Text(
                        text = formatBytes(variant.totalBytes),
                        fontSize = 11.sp,
                        color = TyporbPalette.TextMuted,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (state) {
            is OfflineModelState.Ready -> Text(
                text = "Cached and verified on this device.",
                fontSize = 12.sp,
                color = TyporbPalette.TextSecondary,
            )

            is OfflineModelState.NotInstalled -> Text(
                text = "Not downloaded yet. Offline mode needs the weights on disk.",
                fontSize = 12.sp,
                color = TyporbPalette.TextSecondary,
            )

            is OfflineModelState.Corrupt -> Text(
                text = state.reason,
                fontSize = 12.sp,
                color = TyporbPalette.Danger,
            )

            is OfflineModelState.Failed -> Text(
                text = state.reason,
                fontSize = 12.sp,
                color = TyporbPalette.Danger,
            )

            is OfflineModelState.Downloading -> {
                Text(
                    text = "Downloading ${state.currentFile}…",
                    fontSize = 12.sp,
                    color = TyporbPalette.TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(TyporbShapes.Capsule),
                    color = TyporbPalette.NeonCyan,
                    trackColor = TyporbPalette.SurfaceElevated,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val variant = viewModel.modelVariants.firstOrNull { it.id == settings.modelVariantId }
            ?: ModelCatalog.recommended
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is OfflineModelState.Downloading -> GhostButton(
                    text = "Cancel",
                    onClick = viewModel::cancelModelDownload,
                    icon = Icons.Rounded.DeleteOutline,
                )

                is OfflineModelState.Ready -> {
                    GhostButton(
                        text = "Re-download",
                        onClick = { viewModel.downloadModel(variant) },
                        icon = Icons.Rounded.Refresh,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    GhostButton(
                        text = "Free space",
                        onClick = { viewModel.deleteModel(variant) },
                        icon = Icons.Rounded.DeleteOutline,
                        tint = TyporbPalette.Danger,
                    )
                }

                else -> AccentButton(
                    text = "Download",
                    onClick = { viewModel.downloadModel(variant) },
                )
            }
        }
    }
}

// -------------------------------------------------------------- permissions

@Composable
private fun PermissionCard(viewModel: TyporbViewModel, permissions: PermissionStatus) {
    val context = androidx.compose.ui.platform.LocalContext.current
    GlassSurface(contentPadding = 16.dp) {
        Text(text = "Permissions", fontSize = 14.sp, color = TyporbPalette.TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Only the accessibility service is strictly required.",
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(14.dp))

        PermissionLine(
            title = "Accessibility service",
            subtitle = "Required — finds text fields and types into them",
            granted = permissions.accessibilityService,
            onOpen = { viewModel.launchPermission(context, PermissionTarget.ACCESSIBILITY) },
        )
        PermissionLine(
            title = "Display over other apps",
            subtitle = "Recommended — keeps the orb visible on some OEM skins",
            granted = permissions.overlayPermission,
            onOpen = { viewModel.launchPermission(context, PermissionTarget.OVERLAY) },
        )
        PermissionLine(
            title = "Microphone",
            subtitle = "Required to capture your voice",
            granted = permissions.microphonePermission,
            onOpen = { viewModel.launchPermission(context, PermissionTarget.MICROPHONE) },
        )
    }
}

@Composable
private fun PermissionLine(
    title: String,
    subtitle: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (granted) TyporbPalette.Success else TyporbPalette.Warning),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = TyporbPalette.TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = TyporbPalette.TextMuted)
        }
        Text(
            text = if (granted) "Granted" else "Grant",
            fontSize = 12.sp,
            color = if (granted) TyporbPalette.Success else TyporbPalette.NeonCyan,
            modifier = Modifier
                .clip(TyporbShapes.Capsule)
                .clickable(enabled = !granted, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

// ----------------------------------------------------------- customization

@Composable
private fun CustomizationCard(viewModel: TyporbViewModel, settings: TyporbSettings) {
    GlassSurface(contentPadding = 16.dp) {
        Text(text = "Floating box", fontSize = 14.sp, color = TyporbPalette.TextPrimary)
        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Corner radius",
                fontSize = 13.sp,
                color = TyporbPalette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${settings.overlayCornerRadiusDp}dp",
                fontSize = 13.sp,
                color = TyporbPalette.NeonCyan,
            )
        }
        Slider(
            value = settings.overlayCornerRadiusDp.toFloat(),
            onValueChange = { viewModel.setOverlayCornerRadius(it.toInt()) },
            valueRange = TyporbSettings.MIN_OVERLAY_CORNER_DP.toFloat()..
                TyporbSettings.MAX_OVERLAY_CORNER_DP.toFloat(),
            steps = (TyporbSettings.MAX_OVERLAY_CORNER_DP - TyporbSettings.MIN_OVERLAY_CORNER_DP - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = TyporbPalette.NeonCyan,
                activeTrackColor = TyporbPalette.NeonCyan,
                inactiveTrackColor = TyporbPalette.SurfaceElevated,
            ),
        )

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalRule()

        Spacer(modifier = Modifier.height(14.dp))

        SettingRow(
            title = "Haptic feedback",
            subtitle = "Tick on start, click on confirm",
        ) {
            Switch(
                checked = settings.hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TyporbPalette.NeonCyan,
                    checkedTrackColor = TyporbPalette.Violet.copy(alpha = 0.55f),
                    uncheckedThumbColor = TyporbPalette.TextMuted,
                    uncheckedTrackColor = TyporbPalette.SurfaceElevated,
                ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingRow(
            title = "Reactive waveform",
            subtitle = "Live amplitude bars while recording",
        ) {
            Switch(
                checked = settings.waveformEnabled,
                onCheckedChange = viewModel::setWaveformEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TyporbPalette.NeonCyan,
                    checkedTrackColor = TyporbPalette.Violet.copy(alpha = 0.55f),
                    uncheckedThumbColor = TyporbPalette.TextMuted,
                    uncheckedTrackColor = TyporbPalette.SurfaceElevated,
                ),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalRule()
        Spacer(modifier = Modifier.height(14.dp))

        SettingRow(
            title = "GPU acceleration",
            subtitle = "Off is faster on entry-level Adreno GPUs",
        ) {
            Switch(
                checked = settings.useGpuAcceleration,
                onCheckedChange = viewModel::setGpuAcceleration,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TyporbPalette.NeonCyan,
                    checkedTrackColor = TyporbPalette.Violet.copy(alpha = 0.55f),
                    uncheckedThumbColor = TyporbPalette.TextMuted,
                    uncheckedTrackColor = TyporbPalette.SurfaceElevated,
                ),
            )
        }
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TyporbPalette.GlassBorder),
    )
}

private const val GROQ_CONSOLE_URL = "https://console.groq.com/keys"

/** Human-readable byte count, e.g. `40.8 MB`. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / 1_048_576.0
    return if (megabytes >= 1024) {
        String.format("%.1f GB", megabytes / 1024)
    } else {
        String.format("%.1f MB", megabytes)
    }
}