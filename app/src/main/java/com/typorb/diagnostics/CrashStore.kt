package com.typorb.diagnostics

import android.content.Context
import java.io.File

/**
 * Stores the last crash on disk so it survives the process death that follows it.
 *
 * Writing to a file rather than memory is the whole point: the handler runs during a crash, and
 * anything held only in a field is gone by the time the next launch reads it.
 */
class CrashStore(context: Context) {

    private val file = File(context.filesDir, CrashLog.FILE)

    /** Best-effort write. Never throws: it is called from the crash handler itself. */
    fun save(report: CrashReport) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(CrashLog.encode(report))
        }
    }

    /** The stored crash without clearing it, so the screen survives rotation and process death. */
    fun peek(): CrashReport? =
        runCatching { if (file.isFile) CrashLog.decode(file.readText()) else null }.getOrNull()

    /** Reads the crash and clears it, for when the user dismisses the screen. */
    fun consume(): CrashReport? {
        val report = peek()
        runCatching { file.delete() }
        return report
    }

    fun clear() {
        runCatching { file.delete() }
    }
}