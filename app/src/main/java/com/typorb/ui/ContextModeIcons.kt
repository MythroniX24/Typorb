package com.typorb.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.ui.graphics.vector.ImageVector
import com.typorb.model.ContextMode

/**
 * Icon for a [ContextMode] chip.
 *
 * This lives in the UI layer rather than on the enum so `model/` stays free of Compose types — the
 * cloud and local engines both consume `ContextMode` and neither should drag in a UI dependency.
 */
val ContextMode.icon: ImageVector
    get() = when (this) {
        ContextMode.QUICK_CHAT -> Icons.Rounded.ChatBubbleOutline
        ContextMode.CODE -> Icons.Rounded.Code
        ContextMode.NOTES -> Icons.AutoMirrored.Rounded.Notes
        ContextMode.FORMAL -> Icons.Rounded.MailOutline
    }

/** Icon used for a transcript's mode pill in the vault. */
val ContextMode.vaultIcon: ImageVector
    get() = when (this) {
        ContextMode.QUICK_CHAT -> Icons.Rounded.ChatBubbleOutline
        ContextMode.CODE -> Icons.Rounded.Code
        ContextMode.NOTES -> Icons.AutoMirrored.Rounded.ListAlt
        ContextMode.FORMAL -> Icons.Rounded.MailOutline
    }
