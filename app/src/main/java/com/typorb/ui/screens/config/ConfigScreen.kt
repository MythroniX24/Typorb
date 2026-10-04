package com.typorb.ui.screens.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typorb.data.ModelCatalog
import com.typorb.diagnostics.AccessibilityEventNames
import com.typorb.diagnostics.OrbDiagnosticsBus
import com.typorb.data.OfflineModelState
import com.typorb.data.TyporbSettings
import com.typorb.ui.ApiKeyCheck
import com.typorb.ui.rememberBottomBarClearance
import com.typorb.ui.PermissionStatus
import com.typorb.ui.PermissionTarget
import com.typorb.ui.TyporbViewModel
import com.typorb.ui.components.ElevatedCard
import com.typorb.ui.components.Hairline
import com.typorb.ui.components.PrimaryButton
import com.typorb.ui.components.SecondaryButton
import com.typorb.ui.components.SectionLabel
import com.typorb.ui.components.SettingRow
import com.typorb.ui.components.TagChip
import com.typorb.ui.components.entrance
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val bottomClearance = rememberBottomBarClearance()

    // LazyColumn rather than Column + verticalScroll: this screen is long, and a scrollable Column
    // eagerly composes and measures every card up front. On a low-end GPU that first frame is
    // expensive and stutters when the user flicks the list. LazyColumn only builds what is visible.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = bottomClearance),
    ) {
        item(key = "config-title") {
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp),
            )
        }

        item(key = "config-api") {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .entrance(index = 0),
            ) {
                SectionLabel("Groq API")
                ApiKeyCard(viewModel = viewModel, settings = settings)
            }
        }

        item(key = "config-model") {
            Column(
                modifier = Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp)
                    .entrance(index = 1),
            ) {
                SectionLabel("Model storage")
                ModelStorageCard(viewModel = viewModel, settings = settings, state = modelState)
            }
        }

        item(key = "config-permissions") {
            Column(
                modifier = Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp)
                    .entrance(index = 2),
            ) {
                SectionLabel("Permissions")
                PermissionCard(
                    permissions = permissions,
                    onOpen = { target -> viewModel.launchPermission(context, target) },
                )
            }
        }

        item(key = "config-overlay") {
            Column(
                modifier = Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp)
                    .entrance(index = 3),
            ) {
                SectionLabel("Overlay customization")
                CustomizationCard(viewModel = viewModel, settings = settings)
            }
        }

        item(key = "config-debug") {
            Column(
                modifier = Modifier
                    .padding(start = 18.dp, end = 18.dp, top = 22.dp)
                    .entrance(index = 4),
            ) {
                SectionLabel("Diagnostics")
                DebugConsoleCard(
                    onOpenAccessibility = {
                        viewModel.launchPermission(context, PermissionTarget.ACCESSIBILITY)
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------------- debug console

/**
 * Answers "why is the orb not showing?" without needing a cable, a logcat or a bug report.
 *
 * Every row here is a fact the orb's visibility actually depends on, so a single wrong row
 * identifies the broken link. The verdict at the top is derived from those rows rather than
 * written by hand, which keeps the explanation and the real decision from drifting apart.
 */
@Composable
private fun DebugConsoleCard(onOpenAccessibility: () -> Unit) {
    val diagnostics by OrbDiagnosticsBus.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val healthy = diagnostics.serviceConnected && diagnostics.overlayVisible

    ElevatedCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Orb debug console",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TagChip(
                text = if (healthy) "Live" else "Check me",
                tint = if (healthy) TyporbPalette.Emerald else TyporbPalette.Danger,
                container = if (healthy) TyporbPalette.EmeraldTint else TyporbPalette.DangerTint,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Live state of the floating orb, straight from the accessibility service.",
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = diagnostics.headline(),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (healthy) TyporbPalette.Emerald else TyporbPalette.Danger,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = diagnostics.detail(),
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Hairline()
        Spacer(modifier = Modifier.height(10.dp))

        DebugRow("Accessibility service", yesNo(diagnostics.serviceConnected))
        DebugRow("Events received", diagnostics.eventCount.toString())
        DebugRow(
            label = "Last event",
            value = "${AccessibilityEventNames.name(diagnostics.lastEventType)} " +
                "· ${diagnostics.lastEventPackage?.substringAfterLast('.') ?: "—"}",
        )
        DebugRow("Text field focused", yesNo(diagnostics.editableFieldFocused))
        DebugRow("Windows visible", diagnostics.windowsSeen.toString())
        DebugRow("Keyboard window found", yesNo(diagnostics.imeWindowFound))
        DebugRow("Keyboard height", "${diagnostics.imeHeightPx} px")
        DebugRow("Keyboard visible", yesNo(diagnostics.imeVisible))
        DebugRow("Keyboard (inset signal)", "${diagnostics.imeInsetPx} px")
        DebugRow("IME package", diagnostics.imePackage?.substringAfterLast('.') ?: "unknown")
        DebugRow("Overlay window", yesNo(diagnostics.overlayVisible))
        DebugRow("Inset probe", if (diagnostics.probeAttached) "attached" else "not attached")
        DebugRow("Last check", lastCheckLabel(diagnostics.lastEvaluationAtMs))

        if (diagnostics.breadcrumbs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "RECENT ACTIVITY",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextMuted,
            )
            Spacer(modifier = Modifier.height(5.dp))
            diagnostics.breadcrumbs.forEach { line ->
                Text(
                    text = "· $line",
                    fontSize = 11.sp,
                    color = TyporbPalette.TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Copy report",
                icon = Icons.Rounded.ContentPaste,
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            diagnostics.asReport(System.currentTimeMillis()),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "Fix it",
                icon = Icons.Rounded.Accessibility,
                onClick = onOpenAccessibility,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Copied reports include every row above — paste one into a bug report.",
            fontSize = 11.sp,
            color = TyporbPalette.TextMuted,
        )
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TyporbPalette.TextPrimary,
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

private fun lastCheckLabel(lastEvaluationAtMs: Long): String {
    if (lastEvaluationAtMs == 0L) return "never"
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(lastEvaluationAtMs))
}

// --------------------------------------------------------------------- API key

@Composable
private fun ApiKeyCard(viewModel: TyporbViewModel, settings: TyporbSettings) {
    val input by viewModel.apiKeyInput.collectAsStateWithLifecycle()
    val check by viewModel.apiKeyCheck.collectAsStateWithLifecycle()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    ElevatedCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Groq API key",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (settings.hasApiKey) {
                TagChip(
                    text = "Key stored",
                    tint = TyporbPalette.Emerald,
                    container = TyporbPalette.EmeraldTint,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Required for Cloud mode. Stored encrypted on this device only.",
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = input,
            onValueChange = viewModel::onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !settings.hasApiKey,
            singleLine = true,
            placeholder = {
                Text(
                    text = if (settings.hasApiKey) "••••••••••••••••" else "gsk_…",
                    fontSize = 14.sp,
                    color = TyporbPalette.TextMuted,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TyporbPalette.TextPrimary,
                fontSize = 14.sp,
            ),
            shape = TyporbShapes.Small,
            visualTransformation = if (revealed) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Paste shortcut — the key almost always arrives from a browser or a manager.
                    CircleIcon(
                        icon = Icons.Rounded.ContentPaste,
                        description = "Paste API key",
                        onClick = { viewModel.onApiKeyChange(clipboard.getText()?.text.orEmpty()) },
                    )
                    CircleIcon(
                        icon = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        description = if (revealed) "Hide key" else "Show key",
                        onClick = { revealed = !revealed },
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = TyporbPalette.Surface,
                unfocusedContainerColor = TyporbPalette.Surface,
                disabledContainerColor = TyporbPalette.SurfaceSunken,
                focusedBorderColor = TyporbPalette.Cobalt,
                unfocusedBorderColor = TyporbPalette.Border,
                disabledBorderColor = TyporbPalette.Border,
                cursorColor = TyporbPalette.Cobalt,
            ),
        )

        if (input.isNotBlank() && !settings.hasApiKey) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Looks too short to be a Groq key — expected 20+ characters.",
                fontSize = 11.sp,
                color = TyporbPalette.Warning,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                settings.hasApiKey -> {
                    SecondaryButton(
                        text = "Test connection",
                        onClick = viewModel::testConnection,
                        icon = Icons.Rounded.Check,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SecondaryButton(
                        text = "Remove",
                        onClick = viewModel::clearApiKey,
                        icon = Icons.Rounded.DeleteOutline,
                        tint = TyporbPalette.Danger,
                    )
                }
                else -> {
                    PrimaryButton(
                        text = "Save key",
                        onClick = viewModel::saveApiKey,
                        enabled = input.isNotBlank(),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SecondaryButton(
                        text = "Test",
                        onClick = viewModel::testConnection,
                        enabled = input.isNotBlank(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = check != ApiKeyCheck.Idle,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionResult(check = check)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .clip(TyporbShapes.Capsule)
                .clickable { runCatching { uriHandler.openUri(GROQ_CONSOLE_URL) } }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "Get Free Groq API Key",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.Cobalt,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = TyporbPalette.Cobalt,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ConnectionResult(check: ApiKeyCheck) {
    Spacer(modifier = Modifier.height(12.dp))
    val (message, tint, container) = when (check) {
        is ApiKeyCheck.Ok -> Triple(
            "Connected — ${check.modelCount} models available.",
            TyporbPalette.Emerald,
            TyporbPalette.EmeraldTint,
        )
        is ApiKeyCheck.Failed -> Triple(check.reason, TyporbPalette.Danger, TyporbPalette.DangerTint)
        ApiKeyCheck.Testing -> Triple("Checking…", TyporbPalette.TextSecondary, TyporbPalette.SurfaceSunken)
        ApiKeyCheck.Idle -> Triple("", TyporbPalette.TextSecondary, TyporbPalette.SurfaceSunken)
    }
    Text(
        text = message,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .clip(TyporbShapes.Small)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

// --------------------------------------------------------------- model storage

@Composable
private fun ModelStorageCard(
    viewModel: TyporbViewModel,
    settings: TyporbSettings,
    state: OfflineModelState,
) {
    // animateContentSize so the card grows into the progress bar instead of jumping.
    ElevatedCard(
        modifier = Modifier.animateContentSize(),
        contentPadding = 16.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Local ONNX model",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TagChip(
                text = when (state) {
                    is OfflineModelState.Ready -> "${formatBytes(state.onDiskBytes)} cached"
                    is OfflineModelState.NotInstalled -> formatBytes(state.totalBytes)
                    is OfflineModelState.Downloading -> "${(state.overallProgress * 100).toInt()}%"
                    is OfflineModelState.Corrupt -> "Corrupt"
                    is OfflineModelState.Failed -> "Failed"
                },
                tint = when (state) {
                    is OfflineModelState.Ready -> TyporbPalette.Emerald
                    is OfflineModelState.Corrupt, is OfflineModelState.Failed -> TyporbPalette.Danger
                    else -> TyporbPalette.TextSecondary
                },
                container = when (state) {
                    is OfflineModelState.Ready -> TyporbPalette.EmeraldTint
                    is OfflineModelState.Corrupt, is OfflineModelState.Failed -> TyporbPalette.DangerTint
                    else -> TyporbPalette.SurfaceSunken
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                        .background(if (selected) TyporbPalette.SurfaceSunken else TyporbPalette.Surface)
                        .border(
                            BorderStroke(
                                1.dp,
                                if (selected) TyporbPalette.Indigo.copy(alpha = 0.45f)
                                else TyporbPalette.Border,
                            ),
                            TyporbShapes.Small,
                        )
                        .clickable { viewModel.selectModelVariant(variant) }
                        .padding(12.dp),
                ) {
                    Text(
                        text = variant.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) TyporbPalette.TextPrimary else TyporbPalette.TextSecondary,
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
            is OfflineModelState.Ready ->
                Text("Cached and verified on this device.", fontSize = 12.sp, color = TyporbPalette.TextSecondary)
            is OfflineModelState.NotInstalled ->
                Text(
                    "Not downloaded yet. Offline mode needs the weights on disk.",
                    fontSize = 12.sp,
                    color = TyporbPalette.TextSecondary,
                )
            is OfflineModelState.Corrupt ->
                Text(state.reason, fontSize = 12.sp, color = TyporbPalette.Danger)
            is OfflineModelState.Failed ->
                Text(state.reason, fontSize = 12.sp, color = TyporbPalette.Danger)
            is OfflineModelState.Downloading -> {
                Text(
                    "Downloading ${state.currentFile}…",
                    fontSize = 12.sp,
                    color = TyporbPalette.TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(TyporbShapes.Capsule),
                    color = TyporbPalette.Cobalt,
                    trackColor = TyporbPalette.SurfaceSunken,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val variant = viewModel.modelVariants.firstOrNull { it.id == settings.modelVariantId }
            ?: ModelCatalog.recommended
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is OfflineModelState.Downloading ->
                    TertiaryMini(text = "Cancel", onClick = viewModel::cancelModelDownload)
                is OfflineModelState.Ready -> {
                    SecondaryButton(
                        text = "Update",
                        onClick = { viewModel.downloadModel(variant) },
                        icon = Icons.Rounded.Refresh,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SecondaryButton(
                        text = "Free space",
                        onClick = { viewModel.deleteModel(variant) },
                        icon = Icons.Rounded.DeleteOutline,
                        tint = TyporbPalette.Danger,
                    )
                }
                else -> PrimaryButton(
                    text = "Download",
                    onClick = { viewModel.downloadModel(variant) },
                )
            }
        }
    }
}

@Composable
private fun TertiaryMini(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = TyporbPalette.Danger,
        modifier = Modifier
            .clip(TyporbShapes.Small)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

// ---------------------------------------------------------------- permissions

@Composable
private fun PermissionCard(
    permissions: PermissionStatus,
    onOpen: (PermissionTarget) -> Unit,
) {
    ElevatedCard(contentPadding = 16.dp) {
        Text(
            text = "Permissions",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TyporbPalette.TextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Only the accessibility service is strictly required. Turning one off opens the " +
                "system screen where it can be revoked.",
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        PermissionSwitchRow(
            icon = Icons.Rounded.Accessibility,
            title = "Accessibility service",
            subtitle = "Finds text fields and types into them",
            granted = permissions.accessibilityService,
            onToggle = { onOpen(PermissionTarget.ACCESSIBILITY) },
        )
        PermissionSwitchRow(
            icon = Icons.Rounded.Mic,
            title = "Microphone",
            subtitle = "Captures your voice",
            granted = permissions.microphonePermission,
            onToggle = { onOpen(PermissionTarget.MICROPHONE) },
        )
    }
}

/**
 * A switch row for a system permission.
 *
 * The switch mirrors real grant state rather than storing anything of its own, and every change
 * hands off to the system screen — an app cannot grant these to itself.
 */
@Composable
private fun PermissionSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onToggle: () -> Unit,
) {
    var explained by remember { mutableStateOf(false) }

    Column {
        SettingRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Switch(
                checked = granted,
                onCheckedChange = {
                    explained = true
                    onToggle()
                },
                colors = accentSwitchColors(),
            )
        }
        if (explained && !granted) {
            Text(
                text = "Opened system settings — grant it there and come back.",
                fontSize = 11.sp,
                color = TyporbPalette.TextMuted,
                modifier = Modifier.padding(start = 46.dp, bottom = 6.dp),
            )
        }
    }
}

// -------------------------------------------------------------- customization

@Composable
private fun CustomizationCard(viewModel: TyporbViewModel, settings: TyporbSettings) {
    ElevatedCard(contentPadding = 16.dp) {
        Text(
            text = "Floating box",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TyporbPalette.TextPrimary,
        )

        Spacer(modifier = Modifier.height(14.dp))

        SliderRow(
            label = "Orb scale",
            valueLabel = "${settings.overlaySizeDp}dp",
            value = settings.overlaySizeDp.toFloat(),
            range = TyporbSettings.MIN_OVERLAY_SIZE_DP.toFloat()..
                TyporbSettings.MAX_OVERLAY_SIZE_DP.toFloat(),
            steps = TyporbSettings.MAX_OVERLAY_SIZE_DP - TyporbSettings.MIN_OVERLAY_SIZE_DP - 1,
            onValueChange = { viewModel.setOverlaySize(it.toInt()) },
        )

        Spacer(modifier = Modifier.height(14.dp))

        SliderRow(
            label = "Corner radius",
            valueLabel = "${settings.overlayCornerRadiusDp}dp",
            value = settings.overlayCornerRadiusDp.toFloat(),
            range = TyporbSettings.MIN_OVERLAY_CORNER_DP.toFloat()..
                TyporbSettings.MAX_OVERLAY_CORNER_DP.toFloat(),
            steps = TyporbSettings.MAX_OVERLAY_CORNER_DP - TyporbSettings.MIN_OVERLAY_CORNER_DP - 1,
            onValueChange = { viewModel.setOverlayCornerRadius(it.toInt()) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        Hairline()
        Spacer(modifier = Modifier.height(16.dp))

        SettingRow(
            title = "Haptic feedback",
            subtitle = "Tick on start, click on confirm",
            icon = Icons.Rounded.Widgets,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Switch(
                checked = settings.hapticsEnabled,
                onCheckedChange = viewModel::setHapticsEnabled,
                colors = accentSwitchColors(),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(
            title = "Reactive waveform",
            subtitle = "Live amplitude bars while recording",
            icon = Icons.Rounded.Widgets,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Switch(
                checked = settings.waveformEnabled,
                onCheckedChange = viewModel::setWaveformEnabled,
                colors = accentSwitchColors(),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(
            title = "GPU acceleration",
            subtitle = "Off is faster on entry-level Adreno GPUs",
            icon = Icons.Rounded.Widgets,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Switch(
                checked = settings.useGpuAcceleration,
                onCheckedChange = viewModel::setGpuAcceleration,
                colors = accentSwitchColors(),
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TyporbPalette.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TyporbPalette.Cobalt,
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps.coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = TyporbPalette.Cobalt,
            activeTrackColor = TyporbPalette.Cobalt,
            inactiveTrackColor = TyporbPalette.Border,
        ),
    )
}

@Composable
private fun CircleIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = TyporbPalette.TextSecondary,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(9.dp),
    )
}

@Composable
private fun accentSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = TyporbPalette.OnAccent,
    checkedTrackColor = TyporbPalette.Cobalt,
    uncheckedThumbColor = TyporbPalette.OnAccent,
    uncheckedTrackColor = TyporbPalette.BorderStrong,
    uncheckedBorderColor = TyporbPalette.BorderStrong,
)

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