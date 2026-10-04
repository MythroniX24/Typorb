package com.typorb.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typorb.ui.theme.TyporbElevation
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The elevated white card every panel is built from.
 *
 * A soft diffuse ambient shadow (`clip = false`) lifts the white surface off the off-white canvas,
 * and a 1dp [TyporbPalette.Border] hairline keeps the edge crisp where the shadow is weakest.
 */
@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = TyporbShapes.Medium,
    elevation: Dp = TyporbElevation.Card,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(
                    bounded = false,
                    color = TyporbPalette.TextPrimary,
                ),
                onClick = onClick,
            )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(TyporbPalette.Surface)
            .border(BorderStroke(1.dp, TyporbPalette.Border), shape)
            .then(clickModifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Shared micro-interaction: a subtle scale-down while a surface is held. */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press-scale",
    )
    return this.scale(scale)
}

/** Uppercase section label above a card. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = TyporbPalette.TextMuted,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 6.dp, bottom = 10.dp),
    )
}

/**
 * The headline status badge: an emerald-tinted pill with a dot and a caption.
 *
 * Reads as "connected" without the loudness of a filled chip, which matters because it sits next to
 * the brand wordmark.
 */
@Composable
fun StatusBadge(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (active) TyporbPalette.EmeraldTint else TyporbPalette.SurfaceSunken
    val content = if (active) TyporbPalette.Emerald else TyporbPalette.TextSecondary

    Row(
        modifier = modifier
            .clip(TyporbShapes.Capsule)
            .background(container)
            .border(BorderStroke(1.dp, content.copy(alpha = 0.20f)), TyporbShapes.Capsule)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(content),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

/** Small rounded metadata label, e.g. a mode or latency tag. */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TyporbPalette.TextSecondary,
    container: Color = TyporbPalette.SurfaceSunken,
) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = modifier
            .clip(TyporbShapes.Capsule)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Filled indigo primary action, with a press-scale micro-interaction. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    container: Color = TyporbPalette.Cobalt,
) {
    val shape = TyporbShapes.Small
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .then(if (enabled) Modifier.pressScale(interactionSource) else Modifier)
            .clip(shape)
            .background(if (enabled) container else TyporbPalette.SurfaceSunken)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) TyporbPalette.OnAccent else TyporbPalette.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) TyporbPalette.OnAccent else TyporbPalette.TextMuted,
        )
    }
}

/** Outlined white secondary action that sits next to [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = TyporbPalette.TextPrimary,
) {
    val shape = TyporbShapes.Small
    val interactionSource = remember { MutableInteractionSource() }
    val content = if (enabled) tint else TyporbPalette.TextMuted

    Row(
        modifier = modifier
            .then(if (enabled) Modifier.pressScale(interactionSource) else Modifier)
            .clip(shape)
            .background(if (enabled) TyporbPalette.Surface else TyporbPalette.SurfaceSunken)
            .border(BorderStroke(1.dp, TyporbPalette.Border), shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
        }
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = content)
    }
}

/** Title + caption row with a trailing control slot. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(TyporbPalette.SurfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TyporbPalette.TextSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
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

/** Hairline divider used inside cards. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .size(height = 1.dp, width = Dp.Unspecified)
            .background(TyporbPalette.Border),
    )
}

/** Fades content in when it first appears — used for inline status text. */
@Composable
fun InlineNotice(
    text: String,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
) {
    val visible by androidx.compose.animation.core.animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(220),
        label = "notice",
    )
    Text(
        text = text,
        fontSize = 12.sp,
        color = tint,
        modifier = modifier
            .clip(TyporbShapes.Small)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .scale(0.98f + 0.02f * visible),
    )
}