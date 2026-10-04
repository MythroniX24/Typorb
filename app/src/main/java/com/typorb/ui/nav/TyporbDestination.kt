package com.typorb.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Every screen the single activity can show. */
object Routes {
    /** First-launch permission gate. Hidden from the bottom bar. */
    const val ONBOARDING = "onboarding"

    const val CONTROL = "control"
    const val VAULT = "vault"
    const val SETTINGS = "settings"
}

/**
 * A bottom-bar destination.
 *
 * [route] doubles as the navigation key, so the set is the single source of truth for both the
 * `NavHost` graph and the bar itself.
 */
enum class TyporbDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    CONTROL(Routes.CONTROL, "Control", Icons.Rounded.Home),
    VAULT(Routes.VAULT, "Transcripts", Icons.AutoMirrored.Rounded.ListAlt),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Rounded.Settings);

    companion object {
        /** Tabs shown on the floating bar — onboarding is deliberately excluded. */
        val bottomBarTabs: List<TyporbDestination> = listOf(CONTROL, VAULT, SETTINGS)

        fun fromRoute(route: String?): TyporbDestination? =
            entries.firstOrNull { it.route == route }
    }
}