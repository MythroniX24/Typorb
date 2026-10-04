package com.typorb

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Application entry point. Holds the single [TyporbContainer] so the launcher activity and the
 * accessibility service share one set of repositories and engine instances.
 *
 * Typorb deliberately uses manual constructor injection rather than a DI framework: the object
 * graph is small, the service is the only long-lived consumer, and it keeps the release build free
 * of annotation processing.
 */
class TyporbApp : Application() {

    lateinit var container: TyporbContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = TyporbContainer(this)
    }

    companion object {
        private const val TAG = "TyporbApp"

        /** Resolves the shared container from any context in this process. */
        fun containerOf(context: Context): TyporbContainer {
            val app = context.applicationContext
            check(app is TyporbApp) { "TyporbApp is not the application class" }
            return app.container
        }

        /**
         * Loads the offline model off the caller's thread. Failures are logged and ignored: a warm-up
         * failure must never prevent the overlay from working in Cloud mode.
         */
        fun warmUp(context: Context) {
            val container = containerOf(context)
            container.serviceScope.launch {
                runCatching { container.warmUpLocalEngine() }
                    .onFailure { Log.w(TAG, "Local engine warm-up skipped", it) }
            }
        }
    }
}