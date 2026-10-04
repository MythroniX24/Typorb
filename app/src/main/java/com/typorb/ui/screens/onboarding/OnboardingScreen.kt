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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.ui.PermissionStatus
import com.typorb.ui.PermissionTarget
import com.typorb.ui.components.AccentButton
import com.typorb.ui.components.GlassSurface
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
        Spacer(modifier = Modifier.height(40.dp))

        PulsingOrb()

        Spacer(modifier = Modifier.height(26.dp))

        Text(
            text = "Typorb",
            fontSize = 30.sp,
            color = TyporbPalette.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Speak anywhere. Typorb types for you.",
            fontSize = 14.sp,
            color = TyporbPalette.TextSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        GlassSurface(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
            Text(
                text = "Before you start",
                fontSize = 15.sp,
                color = TyporbPalette.TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Typorb needs one permission to see the field you are typing in.",
                fontSize = 12.sp,
                color = TyporbPalette.TextSecondary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            GateRow(
                title = "Accessibility service",
                subtitle = "Required · finds text fields and types into them",
                done = permissions.accessibilityService,
                actionLabel = "Open",
                onOpen = { onOpenPermission(PermissionTarget.ACCESSIBILITY) },
            )
            GateRow(
                title = "Microphone",
                subtitle = "Required · captures your voice",
                done = permissions.microphonePermission,
                actionLabel = "Open",
                onOpen = { onOpenPermission(PermissionTarget.MICROPHONE) },
            )
            GateRow(
                title = "Display over other apps",
                subtitle = "Optional · keeps the orb visible everywhere",
                done = permissions.overlayPermission,
                actionLabel = "Open",
                onOpen = { onOpenPermission(PermissionTarget.OVERLAY) },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "You can grant these later in Settings.",
            fontSize = 12.sp,
            color = TyporbPalette.TextMuted,
        )

        Spacer(modifier = Modifier.height(20.dp))

        AccentButton(
            text = if (permissions.ready) "Start typing" else "Continue anyway",
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
private fun GateRow(
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (done) TyporbPalette.Success else TyporbPalette.Warning),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = TyporbPalette.TextPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = TyporbPalette.TextMuted)
        }
        if (!done) {
            AccentButton(text = actionLabel, onClick = onOpen)
        } else {
            Text(text = "Done", fontSize = 12.sp, color = TyporbPalette.Success)
        }
    }
}

/** The brand mark: a gradient orb with a pulsing halo, echoing the floating Typorb. */
@Composable
private fun PulsingOrb() {
    val transition = rememberInfiniteTransition(label = "onboarding-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-scale",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        TyporbGradients.Accent.map { it.copy(alpha = 0.10f) },
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(pulse)
                .clip(TyporbShapes.Large)
                .background(TyporbGradients.AccentHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = TyporbPalette.Background,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}