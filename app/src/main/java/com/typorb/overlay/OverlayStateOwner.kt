package com.typorb.overlay

import androidx.lifecycle.LifecycleOwner
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
 */
class OverlayStateOwner(
    /**
     * The service's lifecycle. `SavedStateRegistryOwner` extends `LifecycleOwner` on this version
     * of the library, so this owner cannot own its own lifecycle and has to adopt the service's.
     */
    private val lifecycleOwner: LifecycleOwner,
) : SavedStateRegistryOwner, ViewModelStoreOwner {

    override val lifecycle get() = lifecycleOwner.lifecycle

    private val controller = SavedStateRegistryController.create(this)

    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    override val viewModelStore: ViewModelStore get() = store

    init {
        // The registry must be attached before it can be read. Never restored from a bundle: this
        // owner has no Bundle to restore into.
        controller.performAttach()
    }

    /** Releases the store. Called when the overlay is torn down so nothing outlives the window. */
    fun dispose() {
        store.clear()
    }
}
