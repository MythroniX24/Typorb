package com.typorb.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.typorb.ui.components.pressScale
import com.typorb.ui.nav.Routes
import com.typorb.ui.nav.TyporbDestination
import com.typorb.ui.screens.config.ConfigScreen
import com.typorb.ui.screens.control.ControlScreen
import com.typorb.ui.screens.onboarding.OnboardingScreen
import com.typorb.ui.screens.vault.VaultScreen
import com.typorb.ui.theme.TyporbElevation
import com.typorb.ui.theme.TyporbPalette
import com.typorb.ui.theme.TyporbShapes

/**
 * The single activity's content: an animated [NavHost] plus a floating pill-shaped bottom bar.
 *
 * The bar is overlaid rather than placed in a `Scaffold` bottomBar slot so it hovers above the
 * content with a margin. Screens therefore pad their own bottom inset
 * ([rememberBottomBarClearance]).
 */
@Composable
fun TyporbAppRoot(
    viewModel: TyporbViewModel,
    navController: NavHostController = rememberNavController(),
) {
    // Every lifecycle-aware composable below — `collectAsStateWithLifecycle`, `LifecycleEventEffect`
    // — reads `LocalLifecycleOwner`, which AndroidComposeView supplies from the view tree's
    // ViewTreeLifecycleOwner. When that is absent they throw
    // "CompositionLocal LocalLifecycleOwner not present" on first composition and take the whole
    // app down before a single frame renders. That is exactly what the Minified build did.
    //
    // This root is only ever hosted by MainActivity, so the Activity is always available and is the
    // very instance the window would have provided. Owning it here means no screen has to care
    // whether the window wired an owner up, and one provider covers the whole subtree instead of
    // every screen guarding its own reads.
    val owner = LocalContext.current.findLifecycleOwner()
    if (owner != null) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            TyporbAppRootContent(viewModel, navController)
        }
    } else {
        TyporbAppRootContent(viewModel, navController)
    }
}

/**
 * Walks the context wrappers to the Activity, which is a [LifecycleOwner].
 *
 * `LocalContext` inside an activity's `setContent` is usually the Activity itself, but it may be a
 * ContextThemeWrapper or a ComposeContext wrapping it, so the unwrap loop is not paranoia.
 */
private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

@Composable
private fun TyporbAppRootContent(
    viewModel: TyporbViewModel,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    // Re-read permission state whenever the app returns to the foreground: the user just left for a
    // system settings screen to grant or revoke something.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    // Captured once, deliberately not read from `settings` on every recomposition: finishing
    // onboarding flips `onboardingComplete`, and a NavHost whose startDestination changes rebuilds
    // its whole graph — which would reset the back stack underneath the navigate() call below.
    val startDestination = remember {
        if (settings.onboardingComplete) Routes.CONTROL else Routes.ONBOARDING
    }

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
                    onOpenPermission = { target -> viewModel.launchPermission(context, target) },
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
                        // Single-top with state restoration: switching tabs keeps each tab's scroll
                        // position and search text, and never stacks duplicates.
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }
}

/** The floating pill-shaped navigation bar. */
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
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .shadow(elevation = TyporbElevation.FloatingBar, shape = TyporbShapes.Bar, clip = false)
                .clip(TyporbShapes.Bar)
                .background(TyporbPalette.Surface)
                .border(1.dp, TyporbPalette.Border, TyporbShapes.Bar)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    val shape = TyporbShapes.Capsule
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .pressScale(interactionSource, pressedScale = 0.95f)
            .clip(shape)
            // Selected: a solid indigo chip with crisp white content. Inactive: no fill at all, so
            // the slate label carries the state on its own.
            .background(if (selected) TyporbPalette.Indigo else TyporbPalette.Surface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = if (selected) TyporbPalette.OnAccent else TyporbPalette.TextMuted,
            modifier = Modifier.size(19.dp),
        )
        if (selected) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(7.dp))
            Text(
                text = destination.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TyporbPalette.OnAccent,
            )
        }
    }
}

/**
 * Extra bottom padding a screen needs so its last row clears the floating bar.
 *
 * Computed from the real navigation-bar inset rather than hard-coded: the bar applies
 * `navigationBarsPadding()` to itself, and a 3-button navigation bar is roughly 48dp tall while a
 * gesture bar is nearer 16dp. A fixed value therefore either hid content behind the bar on
 * 3-button devices or left a dead gap on gesture devices.
 */
@Composable
fun rememberBottomBarClearance(): Dp {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return BAR_HEIGHT + BAR_OUTER_MARGIN + navigationBar
}

/** Icon + label + the bar's own inner vertical padding, at the tallest state. */
private val BAR_HEIGHT = 57.dp

/** The bar's 14dp margin above and below. */
private val BAR_OUTER_MARGIN = 28.dp

// Crossfade with a whisper of scale, per the design system. A directional slide was tried first
// but reads as "navigation", which is wrong for peer tabs behind a bottom bar.
private fun tabEnter() = fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.985f)
private fun tabExit() = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.008f)
private fun tabPopEnter() = fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.985f)
private fun tabPopExit() = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 1.008f)