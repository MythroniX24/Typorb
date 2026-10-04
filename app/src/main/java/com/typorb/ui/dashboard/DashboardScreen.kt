package com.typorb.ui.dashboard

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.typorb.data.ModelCatalog
import com.typorb.data.OfflineModelState
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import com.typorb.ui.theme.TyporbPalette
import com.typorb.util.Permissions

/**
 * The launcher screen: everything the user needs before the overlay becomes useful.
 *
 * Permission state is re-read on every resume, because all three permissions are granted through
 * separate system screens that return to this activity.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val apiKeyInput by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val apiKeySaved by viewModel.apiKeySaved.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val microphoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { viewModel.refreshPermissions() },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Background),
    ) {
        // Ambient cyan/violet glow behind the header.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            TyporbPalette.Violet.copy(alpha = 0.28f),
                            TyporbPalette.NeonCyan.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header(ready = permissions.ready)

            StatusCard(
                accessibilityGranted = permissions.accessibilityService,
                overlayGranted = permissions.overlayPermission,
                microphoneGranted = permissions.microphonePermission,
                onOpenAccessibility = { context.startActivity(Permissions.accessibilitySettingsIntent()) },
                onOpenOverlay = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
                onRequestMicrophone = {
                    if (permissions.microphonePermission) {
                        context.startActivity(Permissions.appDetailsIntent(context))
                    } else {
                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )

            EngineCard(
                selected = settings.engine,
                onSelect = viewModel::setEngine,
            )

            ModeCard(
                selected = settings.contextMode,
                onSelect = viewModel::setContextMode,
            )

            OfflineModelCard(
                state = modelState,
                variants = viewModel.modelVariants,
                selectedVariantId = settings.modelVariantId,
                useGpu = settings.useGpuAcceleration,
                onSelectVariant = { variant -> viewModel.selectModelVariant(variant) },
                onDownload = viewModel::downloadModel,
                onCancel = viewModel::cancelModelDownload,
                onDelete = viewModel::deleteModel,
                onToggleGpu = viewModel::setGpuAcceleration,
            )

            ApiKeyCard(
                value = apiKeyInput,
                saved = apiKeySaved || settings.hasApiKey,
                onValueChange = viewModel::onApiKeyChange,
                onSave = viewModel::saveApiKey,
                onClear = viewModel::clearApiKey,
            )

            Hint(
                if (settings.engine == ProcessingEngine.LOCAL) {
                    "Local mode runs Whisper tiny on-device. Nothing leaves your phone, even in airplane mode."
                } else {
                    "Cloud mode streams your audio to Groq for transcription and formatting. Keys stay encrypted on this device."
                },
            )
        }
    }
}

@Composable
private fun Header(ready: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Typorb",
                style = MaterialTheme.typography.headlineLarge,
                color = TyporbPalette.TextPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            StatusPill(ready = ready)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Tap the floating orb in any text field and just talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = TyporbPalette.TextSecondary,
        )
    }
}

@Composable
private fun StatusPill(ready: Boolean) {
    val color = if (ready) TyporbPalette.Success else TyporbPalette.NeonCyan
    val label = if (ready) "Active" else "Setup needed"
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun StatusCard(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    microphoneGranted: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestMicrophone: () -> Unit,
) {
    Panel(title = "Permissions") {
        PermissionRow(
            icon = Icons.Rounded.AccessibilityNew,
            title = "Accessibility service",
            subtitle = "Required. Lets Typorb see text fields and type into them.",
            granted = accessibilityGranted,
            actionLabel = "Open settings",
            onAction = onOpenAccessibility,
        )
        PermissionRow(
            icon = Icons.Rounded.Layers,
            title = "Display over other apps",
            subtitle = "Recommended. Keeps the orb visible on every OEM skin.",
            granted = overlayGranted,
            actionLabel = "Open settings",
            onAction = onOpenOverlay,
        )
        PermissionRow(
            icon = Icons.Rounded.Mic,
            title = "Microphone",
            subtitle = "Required to capture your voice.",
            granted = microphoneGranted,
            actionLabel = "Allow",
            onAction = onRequestMicrophone,
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (granted) TyporbPalette.NeonCyan else TyporbPalette.TextMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TyporbPalette.TextPrimary,
            )
            Text(
                text = if (granted) "Granted" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) TyporbPalette.Success else TyporbPalette.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (granted) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Granted",
                tint = TyporbPalette.Success,
                modifier = Modifier.size(20.dp),
            )
        } else {
            OutlinedButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineCard(selected: ProcessingEngine, onSelect: (ProcessingEngine) -> Unit) {
    Panel(title = "Processing engine") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ProcessingEngine.entries.forEachIndexed { index, engine ->
                SegmentedButton(
                    selected = selected == engine,
                    onClick = { onSelect(engine) },
                    shape = SegmentedButtonDefaults.itemShape(index, ProcessingEngine.entries.size),
                    icon = {
                        Icon(
                            imageVector = if (engine == ProcessingEngine.CLOUD) {
                                Icons.Rounded.Cloud
                            } else {
                                Icons.Rounded.Flight
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (engine == ProcessingEngine.CLOUD) "Cloud" else "Local",
                        )
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = when (selected) {
                ProcessingEngine.CLOUD ->
                    "Groq whisper-large-v3 transcribes, llama-3.1-8b-instant formats. Fast and free tier."
                ProcessingEngine.LOCAL ->
                    "Quantised Whisper tiny via ONNX Runtime. Fully offline, ~40 MB model."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TyporbPalette.TextSecondary,
        )
    }
}

@Composable
private fun ModeCard(selected: ContextMode, onSelect: (ContextMode) -> Unit) {
    Panel(title = "Context mode") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContextMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(text = mode.shortLabel) },
                    leadingIcon = {
                        Icon(
                            imageVector = modeIcon(mode),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TyporbPalette.Violet.copy(alpha = 0.22f),
                        selectedLabelColor = TyporbPalette.TextPrimary,
                        selectedLeadingIconColor = TyporbPalette.NeonCyan,
                        labelColor = TyporbPalette.TextSecondary,
                        iconColor = TyporbPalette.TextMuted,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = TyporbPalette.TextSecondary,
        )
    }
}

@Composable
private fun ApiKeyCard(
    value: String,
    saved: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Panel(title = "Groq API key") {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(text = if (saved) "Saved — enter a new key to replace" else "gsk_…") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TyporbPalette.Surface,
                unfocusedContainerColor = TyporbPalette.Surface,
                focusedIndicatorColor = TyporbPalette.NeonCyan,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = TyporbPalette.NeonCyan,
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSave,
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TyporbPalette.NeonCyan,
                    contentColor = TyporbPalette.Background,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Save key")
            }
            if (saved) {
                OutlinedButton(onClick = onClear) {
                    Text(text = "Remove", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Stored with EncryptedSharedPreferences (AES256-GCM, Keystore-backed) and excluded from backups.",
            style = MaterialTheme.typography.bodySmall,
            color = TyporbPalette.TextMuted,
        )
    }
}

@Composable
private fun OfflineModelCard(
    state: OfflineModelState,
    variants: List<ModelCatalog.Variant>,
    selectedVariantId: String,
    useGpu: Boolean,
    onSelectVariant: (ModelCatalog.Variant) -> Unit,
    onDownload: (ModelCatalog.Variant) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelCatalog.Variant) -> Unit,
    onToggleGpu: (Boolean) -> Unit,
) {
    val selected = variants.firstOrNull { it.id == selectedVariantId } ?: variants.first()
    Panel(title = "Offline model") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            variants.forEach { variant ->
                FilterChip(
                    selected = variant.id == selected.id,
                    onClick = { onSelectVariant(variant) },
                    label = { Text(text = variant.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TyporbPalette.NeonCyan.copy(alpha = 0.18f),
                        selectedLabelColor = TyporbPalette.TextPrimary,
                        labelColor = TyporbPalette.TextSecondary,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = selected.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = TyporbPalette.TextSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (state) {
            is OfflineModelState.Downloading -> {
                Text(
                    text = "Downloading ${state.currentFile} · " +
                        "${formatBytes(state.fileBytes)} / ${formatBytes(state.fileTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TyporbPalette.TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = TyporbPalette.NeonCyan,
                    trackColor = TyporbPalette.SurfaceElevated,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(text = "Cancel", style = MaterialTheme.typography.labelMedium)
                }
            }

            is OfflineModelState.Ready -> {
                StatusRow(
                    icon = Icons.Rounded.Check,
                    tint = TyporbPalette.Success,
                    text = "Installed · ${formatBytes(state.onDiskBytes)} on disk",
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = { onDelete(selected) }) {
                    Text(text = "Delete", style = MaterialTheme.typography.labelMedium)
                }
            }

            is OfflineModelState.NotInstalled -> {
                Text(
                    text = "Not installed · ${formatBytes(state.totalBytes)} to download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TyporbPalette.TextMuted,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onDownload(selected) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TyporbPalette.NeonCyan,
                        contentColor = TyporbPalette.Background,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Download")
                }
            }

            is OfflineModelState.Corrupt -> {
                StatusRow(Icons.Rounded.Warning, TyporbPalette.Danger, state.reason)
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = { onDownload(selected) }) { Text(text = "Re-download") }
            }

            is OfflineModelState.Failed -> {
                StatusRow(Icons.Rounded.Warning, TyporbPalette.Danger, state.reason)
                Spacer(modifier = Modifier.height(10.dp))
                if (state.retryable) {
                    Button(onClick = { onDownload(selected) }) { Text(text = "Retry") }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                checked = useGpu,
                onCheckedChange = onToggleGpu,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Use GPU (NNAPI)",
                style = MaterialTheme.typography.bodyMedium,
                color = TyporbPalette.TextPrimary,
            )
        }
        Text(
            text = "Off is faster on older GPUs (Adreno 5xx, PowerVR). Turn on for newer devices.",
            style = MaterialTheme.typography.bodySmall,
            color = TyporbPalette.TextMuted,
        )
    }
}

@Composable
private fun StatusRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = TyporbPalette.TextSecondary)
    }
}

/** 1536 → "1.5 KB". */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = TyporbPalette.TextMuted,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Shared frosted card used by every dashboard section. */
@Composable
private fun Panel(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(TyporbPalette.Surface.copy(alpha = 0.92f))
            .border(1.dp, TyporbPalette.GlassBorder, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TyporbPalette.NeonCyan,
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

private fun modeIcon(mode: ContextMode): ImageVector = when (mode) {
    ContextMode.QUICK_CHAT -> Icons.AutoMirrored.Rounded.Chat
    ContextMode.CODE -> Icons.Rounded.Code
    ContextMode.NOTES -> Icons.AutoMirrored.Rounded.Notes
    ContextMode.FORMAL -> Icons.Rounded.AlternateEmail
}