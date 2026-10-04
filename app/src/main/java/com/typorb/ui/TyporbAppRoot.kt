package com.typorb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.typorb.ui.components.GlassSurface
import com.typorb.ui.nav.Routes
import com.typorb.ui.nav.TyporbDestination
import com.typorb.ui.screens.config.ConfigScreen
import com.typorb.ui.screens.control.ControlScreen
import com.typorb.ui.screens.onboarding.OnboardingScreen
import com.typorb.ui.screens.vault.VaultScreen
import com.typorb.ui.theme.TyporbGradients
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The single activity's content: an animated [NavHost] plus a floating frosted-glass bottom bar.
 *
 * The bar is overlaid rather than placed in a `Scaffold` bottomBar slot so it floats above the
 * content with a margin, which is the look the design system asks for. Screens therefore pad their
 * own bottom inset ([BOTTOM_BAR_CLEARANCE]).
 */
@Composable
fun TyporbAppRoot(
    viewModel: TyporbViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    // Re-read permission state whenever the app comes back to the foreground: the user just left to a
    // system settings screen to grant or revoke something.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    // The gate is a first-launch screen; once dismissed it never returns, so a user who later
    // revokes a permission is not trapped re-granting it on every visit.
    val startDestination =
        if (settings.onboardingComplete) Routes.CONTROL else Routes.ONBOARDING

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activeTab = TyporbDestination.fromRoute(currentRoute)
    val barVisible = activeTab != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TyporbPalette.Background),
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { tabEnter() },
            exitTransition = { tabExit() },
            popEnterTransition = { tabPopEnter() },
            popExitTransition = { tabPopExit() },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    permissions = permissions,
                    onOpenPermission = { target ->
                        viewModel.launchPermission(context, target)
                    },
                    onFinish = {
                        viewModel.completeOnboarding()
                        navController.navigate(Routes.CONTROL) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.CONTROL) {
                ControlScreen(
                    viewModel = viewModel,
                    permissions = permissions,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.VAULT) {
                VaultScreen(viewModel = viewModel)
            }
            composable(Routes.SETTINGS) {
                ConfigScreen(viewModel = viewModel, permissions = permissions)
            }
        }

        AnimatedVisibility(
            visible = barVisible,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.94f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.94f),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TyporbBottomBar(
                current = activeTab,
                onSelect = { destination ->
                    if (destination.route == currentRoute) return@TyporbBottomBar
                    navController.navigate(destination.route) {
                        // Single-top with state restoration: switching tabs keeps each tab's
                        // scroll position and search text, and never stacks duplicates.
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

/** The elevated floating bottom navigation bar. */
@Composable
private fun TyporbBottomBar(
    current: TyporbDestination?,
    onSelect: (TyporbDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TyporbShapes.Large)
                .background(TyporbPalette.GlassElevated)
                .background(TyporbGradients.cardSheen(alpha = 0.05f), TyporbShapes.Large)
                .border(BorderStroke(1.dp, TyporbPalette.GlassBorder), TyporbShapes.Large)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TyporbDestination.bottomBarTabs.forEach { destination ->
                BottomBarItem(
                    destination = destination,
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: TyporbDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = TyporbShapes.Small
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(
                        TyporbGradients.Accent.map { it.copy(alpha = 0.16f) },
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            TyporbPalette.TextPrimary.copy(alpha = 0f),
                            TyporbPalette.TextPrimary.copy(alpha = 0f),
                        ),
                    )
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = if (selected) TyporbPalette.NeonCyan else TyporbPalette.TextMuted,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = destination.label,
            fontSize = 10.sp,
            color = if (selected) TyporbPalette.NeonCyan else TyporbPalette.TextMuted,
        )
    }
}

/** Extra bottom padding screens need so the last row clears the floating bar. */
val BOTTOM_BAR_CLEARANCE = 108.dp

// Directional slide so the tab being selected feels like it comes from where the user tapped.
private fun tabEnter() = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
private fun tabExit() = fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 16 }
private fun tabPopEnter() = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 16 }
private fun tabPopExit() = fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { it / 12 }