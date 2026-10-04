package com.typorb.ui.screens.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.ui.PermissionStatus
import com.typorb.ui.PermissionTarget
import com.typorb.ui.components.ElevatedCard
import com.typorb.ui.components.PrimaryButton
import com.typorb.ui.components.SettingRow
import com.typorb.ui.theme.TyporbGradients
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * First-launch permission gate.
 *
 * Shown only until the user completes it once — after that the app opens straight on Control, and
 * revoking a permission later surfaces the warning in-app rather than re-blocking the whole UI.
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionStatus,
    onOpenPermission: (PermissionTarget) -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        PulsingOrb()

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Typorb",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = TyporbPalette.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Speak anywhere. Typorb types for you.",
            fontSize = 15.sp,
            color = TyporbPalette.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(34.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
            Text(
                text = "Before you start",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Typorb needs permission to see the field you are typing in.",
                fontSize = 12.sp,
                color = TyporbPalette.TextSecondary,
            )

            Spacer(modifier = Modifier.height(10.dp))

            GateRow(
                icon = Icons.Rounded.Accessibility,
                title = "Accessibility service",
                subtitle = "Required · finds text fields and types into them",
                done = permissions.accessibilityService,
                onOpen = { onOpenPermission(PermissionTarget.ACCESSIBILITY) },
            )
            GateRow(
                icon = Icons.Rounded.Mic,
                title = "Microphone",
                subtitle = "Required · captures your voice",
                done = permissions.microphonePermission,
                onOpen = { onOpenPermission(PermissionTarget.MICROPHONE) },
            )
            GateRow(
                icon = Icons.Rounded.Widgets,
                title = "Display over other apps",
                subtitle = "Optional · keeps the orb visible everywhere",
                done = permissions.overlayPermission,
                onOpen = { onOpenPermission(PermissionTarget.OVERLAY) },
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "You can change these later in Settings.",
            fontSize = 12.sp,
            color = TyporbPalette.TextMuted,
        )

        Spacer(modifier = Modifier.height(22.dp))

        PrimaryButton(
            text = if (permissions.ready) "Start typing" else "Continue anyway",
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun GateRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    done: Boolean,
    onOpen: () -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = Modifier.padding(vertical = 7.dp),
    ) {
        if (done) {
            Text(
                text = "Granted",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.Emerald,
            )
        } else {
            PrimaryButton(text = "Open", onClick = onOpen)
        }
    }
}

/** The brand mark: an indigo orb with a soft pulsing halo. */
@Composable
private fun PulsingOrb() {
    val transition = rememberInfiniteTransition(label = "onboarding-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-scale",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(CircleShape)
                .background(TyporbGradients.halo()),
        )
        Box(
            modifier = Modifier
                .size(66.dp)
                .scale(pulse)
                .clip(TyporbShapes.Large)
                .background(TyporbGradients.AccentHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = TyporbPalette.OnAccent,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}