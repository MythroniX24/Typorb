package com.typorb.overlay

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the startup crash reported on a Redmi 8A: `IllegalStateException: Restarter must be created
 * only during owner's initialization stage`, thrown from `SavedStateRegistryController.performAttach()`
 * while `TyporbAccessibilityService.setUp()` built the overlay.
 *
 * These are plain JVM tests, not instrumentation tests, so the one Android touch point has to be
 * neutralised first: `LifecycleRegistry` routes its main-thread check through
 * `ArchTaskExecutor.isMainThread()`, which reaches `Looper.getMainLooper()`. Outside an instrumented
 * test that is `null` (the module sets `unitTests.isReturnDefaultValues = true`), so a delegate that
 * claims the test thread is the main thread is installed for the duration of each test and removed
 * again afterwards to keep it from leaking into other suites.
 */
class OverlayStateOwnerTest {

    @Before
    fun installMainThreadDelegate() {
        ArchTaskExecutor.getInstance().setDelegate(ClaimingMainThreadExecutor)
    }

    @After
    fun removeMainThreadDelegate() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    /**
     * The regression itself.
     *
     * The service moves its own lifecycle to `STARTED` at the top of `onServiceConnected`, before
     * `setUp()` constructs [OverlayStateOwner]. So the owner is *always* built while some surrounding
     * lifecycle is already past `INITIALIZED`, and must therefore not depend on that state.
     */
    @Test
    fun `owner is constructed after the service lifecycle is already started`() {
        val service = FakeOwner()
        service.moveTo(Lifecycle.State.STARTED)
        assertEquals(Lifecycle.State.STARTED, service.lifecycle.currentState)

        val owner = OverlayStateOwner()

        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
        // Reaching STARTED dispatches ON_CREATE, which runs the Recreator observer installed by
        // performAttach(). It reads this flag to decide whether consuming restored state is legal.
        assertTrue(owner.savedStateRegistry.isRestored)
    }

    /** Nothing to save, but the view-model store is part of the contract Compose reads on attach. */
    @Test
    fun `dispose destroys the lifecycle`() {
        val owner = OverlayStateOwner()
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)

        owner.dispose()

        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    /**
     * Pins the library contract that caused the crash, so the KDoc's explanation cannot quietly rot.
     *
     * This is the *previous* shape of [OverlayStateOwner]: an owner that adopts a lifecycle which has
     * already started. It is kept as a failing-by-design example rather than deleted, because it is
     * the only thing in this repository that proves why the owner needs a lifecycle of its own.
     */
    @Test
    fun `attaching a registry to an already started lifecycle is the failure that was reported`() {
        val service = FakeOwner()
        service.moveTo(Lifecycle.State.STARTED)
        val controller = SavedStateRegistryController.create(service)

        val error = runCatching { controller.performAttach() }.exceptionOrNull()

        assertTrue(
            "expected the initialization-stage check to reject a STARTED owner, got: $error",
            error is IllegalStateException && error.message.orEmpty().contains("initialization stage"),
        )
    }

    /** Stands in for the accessibility service's own `LifecycleOwner`. */
    private class FakeOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private object ClaimingMainThreadExecutor : TaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) = Unit
        override fun postToMainThread(runnable: Runnable) = Unit
        override fun isMainThread(): Boolean = true
    }
}
