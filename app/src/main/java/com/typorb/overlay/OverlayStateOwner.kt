package com.typorb.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A minimal [SavedStateRegistryOwner] and [ViewModelStoreOwner] for the overlay window.
 *
 * The orb is hosted in a [android.view.WindowManager] window, so it has no `ComponentActivity` to
 * inherit these from. A `ComposeView` nevertheless demands a `SavedStateRegistryOwner` from its
 * view tree the moment it is attached — unconditionally, regardless of whether any composable
 * calls `rememberSaveable`. Attaching one without this throws:
 *
 * ```
 * IllegalStateException: Composed into the View which doesn't propagateViewTreeSavedStateRegistryOwner!
 * ```
 *
 * An earlier revision concluded the owner was unnecessary because the overlay holds no saved state.
 * That was wrong: the requirement comes from `ComposeView.onAttachedToWindow`, not from the
 * composition's contents.
 *
 * [SavedStateRegistry]'s own constructor is internal to the framework, but
 * [SavedStateRegistryController] is the supported public way to own one, which is what makes this
 * possible outside an activity. Nothing is ever saved or restored — the overlay outlives neither
 * process death nor configuration change — so the registry only has to exist and be attached.
 *
 * ## Why this owns its own lifecycle
 *
 * The obvious implementation — adopt the accessibility service's lifecycle, as an earlier revision
 * did — cannot work. `SavedStateRegistryController.performAttach()` opens with:
 *
 * ```java
 * if (owner.getLifecycle().getCurrentState() != Lifecycle.State.INITIALIZED)
 *     throw IllegalStateException("Restarter must be created only during owner's initialization stage")
 * ```
 *
 * and the service moves its own `LifecycleRegistry` to `STARTED` at the top of `onServiceConnected`,
 * *before* `setUp()` builds this owner. So the owner was always constructed against a lifecycle that
 * had already left `INITIALIZED`, and every service start died with that `IllegalStateException` —
 * which is precisely what the Redmi 8A reported as `startup FAILED: IllegalStateException`.
 *
 * Giving this class a private [LifecycleRegistry] decouples it from the service entirely: it is in
 * `INITIALIZED` until this constructor drives it forward, so the ordering of the service's callbacks
 * no longer matters. The two orderings `performAttach()` and `performRestore()` demand are both
 * honoured below; the overlay's window is disposed from [dispose] regardless of which lifecycle
 * happens to be driving the composition.
 */
class OverlayStateOwner : SavedStateRegistryOwner, ViewModelStoreOwner {

    /**
     * Private, and therefore always in `INITIALIZED` while [SavedStateRegistryController.create]
     * and [controller]'s attach run.
     */
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    private val controller = SavedStateRegistryController.create(this)

    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    override val viewModelStore: ViewModelStore get() = store

    init {
        // 1. Must happen while [registry] is still `INITIALIZED`.
        controller.performAttach()

        // 2. Must happen while it is still below `STARTED`. This is the "nothing was restored"
        //    counterpart of what an activity does in `onCreate`, and it is not optional: it is what
        //    flips the registry's internal `isRestored` flag so the `Recreator` observer added by
        //    `performAttach()` can legally read `androidx.savedstate.Restarter` back out on
        //    `ON_CREATE`. Skipping it throws
        //    "You can consumeRestoredStateForKey only after super.onCreate of corresponding
        //    component" the moment the lifecycle advances.
        controller.performRestore(null)

        // 3. Only now may the lifecycle move. Advancing dispatches `ON_CREATE`/`ON_START`, which
        //    runs `Recreator` (it finds no restored component and removes itself) and takes the
        //    registry out of its restoring state.
        registry.currentState = Lifecycle.State.STARTED
    }

    /** Releases the store and the lifecycle. Called when the overlay is torn down. */
    fun dispose() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
