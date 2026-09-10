package com.pocketllm.companion

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal owner triple so a `ComposeView` can live in an overlay window
 * (owned by a Service) instead of an Activity. Compose reads these three via
 * the view tree; without them it throws on attach.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /** Must be called on the main thread, before the view is attached. */
    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Must be called on the main thread, after the view is detached. */
    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
