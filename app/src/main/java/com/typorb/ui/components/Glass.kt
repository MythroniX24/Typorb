package com.typorb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.ui.theme.TyporbGradients
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The frosted-glass primitive every panel is built from.
 *
 * A real backdrop blur is not available across Typorb's API 26+ range (see
 * [com.typorb.data.SecurePreferences] for why), so the effect is composed from three layers: a
 * 60%-opaque `#13131A` fill, a 1dp `#2A2A38` hairline, and a faint diagonal sheen that suggests a
 * light source. It reads identically on every supported API level and costs one draw call.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = TyporbShapes.Medium,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(TyporbPalette.Glass)
            .background(TyporbGradients.cardSheen(), shape)
            .border(BorderStroke(1.dp, accent ?: TyporbPalette.GlassBorder), shape)
            .then(clickable)
            .padding(contentPadding),
        content = content,
    )
}

/** Section label above a card, in muted slate. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = TyporbPalette.TextMuted,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

/**
 * The status pill in the top bar: a pulsing dot plus a caption.
 *
 * The dot only pulses when [active]; a "Needs setup" state stays still so the UI never nags.
 */
@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    tint: Color = TyporbPalette.Success,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-pulse",
    )
    val alpha = if (active) pulse else 0.6f

    Row(
        modifier = modifier
            .clip(TyporbShapes.Capsule)
            .background(TyporbPalette.Glass)
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.28f)), TyporbShapes.Capsule)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .scale(if (active) pulse else 1f)
                .clip(CircleShape)
                .background(tint.copy(alpha = alpha)),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            color = TyporbPalette.TextSecondary,
        )
    }
}

/** Small rounded label used for mode tags and metadata chips. */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TyporbPalette.TextSecondary,
    container: Color = TyporbPalette.GlassElevated,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = tint,
        modifier = modifier
            .clip(TyporbShapes.Capsule)
            .background(container)
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.22f)), TyporbShapes.Capsule)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The cyan→violet filled pill used for primary confirmations. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = TyporbShapes.Small
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (enabled) TyporbGradients.AccentHorizontal
                else Brush.horizontalGradient(
                    listOf(TyporbPalette.GlassElevated, TyporbPalette.GlassElevated),
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TyporbPalette.Background else TyporbPalette.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (enabled) TyporbPalette.Background else TyporbPalette.TextMuted,
        )
    }
}

/** Outlined secondary button that sits next to [AccentButton]. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = TyporbPalette.TextPrimary,
) {
    val shape = TyporbShapes.Small
    Row(
        modifier = modifier
            .clip(shape)
            .background(TyporbPalette.GlassElevated)
            .border(BorderStroke(1.dp, TyporbPalette.GlassBorder), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(text = text, fontSize = 13.sp, color = tint)
    }
}

/** Full-width row used inside settings cards, with a trailing control slot. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                color = TyporbPalette.TextPrimary,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TyporbPalette.TextSecondary,
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}