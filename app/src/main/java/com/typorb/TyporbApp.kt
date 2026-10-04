package com.typorb

import android.app.Application
import android.content.Context
import android.util.Log
import com.typorb.diagnostics.CrashLog
import com.typorb.diagnostics.CrashStore
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
        // Installed before anything else runs. A crash during the rest of this method, or in any
        // component it constructs, is exactly the case that was previously invisible: the process
        // died with no trace on screen. Recording it lets the next launch explain itself.
        installCrashHandler()
        super.onCreate()
        container = TyporbContainer(this)
    }

    /**
     * Chains a recording handler in front of whatever was already installed.
     *
     * The original is always invoked afterwards so the platform keeps showing its dialog and the
     * process still dies normally; swallowing the throwable here would leave a zombie app that is
     * far harder to debug than the crash itself.
     */
    private fun installCrashHandler() {
        // Guard against re-entry: Application.onCreate runs once per process, but a flag is cheaper
        // than reasoning about nesting if that ever changes.
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val store = CrashStore(this)
        Thread.setDefaultUncaughtExceptionHandler(
            CrashLog.handler(
                record = { report ->
                    Log.e(TAG, "Captured crash: ${report.headline}")
                    store.save(report)
                },
                delegate = previous,
            ),
        )
    }

    companion object {
        private const val TAG = "TyporbApp"

        private var crashHandlerInstalled = false

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