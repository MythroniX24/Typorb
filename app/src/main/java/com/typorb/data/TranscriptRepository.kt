package com.typorb.data

import android.content.Context
import android.util.Log
import com.typorb.model.ContextMode
import com.typorb.model.ProcessingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One completed dictation, as shown on the Transcripts screen.
 *
 * [mode] and [engine] are snapshotted at dictation time so history stays truthful even after the
 * user switches configuration.
 */
data class Transcript(
    val id: String,
    val text: String,
    val timestampMs: Long,
    val mode: ContextMode,
    val engine: ProcessingEngine,
    val latencyMs: Long,
)

/**
 * JSON codec for the transcript history.
 *
 * Kept as pure functions with no Android dependencies (beyond `org.json`, which the JVM unit tests
 * also have) so the wire format can be round-trip tested directly. A malformed or older record
 * degrades to a skipped entry rather than wiping the whole history.
 */
object TranscriptCodec {

    fun encode(items: List<Transcript>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put(KEY_ID, item.id)
                    put(KEY_TEXT, item.text)
                    put(KEY_TIMESTAMP, item.timestampMs)
                    put(KEY_MODE, item.mode.name)
                    put(KEY_ENGINE, item.engine.name)
                    put(KEY_LATENCY, item.latencyMs)
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String): List<Transcript> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse {
            Log.w(TAG, "Transcript history is unreadable; starting empty", it)
            return emptyList()
        }
        val items = ArrayList<Transcript>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val text = entry.optString(KEY_TEXT).takeIf { it.isNotBlank() } ?: continue
            items += Transcript(
                id = entry.optString(KEY_ID).ifBlank { entry.optString(KEY_TIMESTAMP) },
                text = text,
                timestampMs = entry.optLong(KEY_TIMESTAMP),
                mode = enumOrDefault(entry.optString(KEY_MODE), ContextMode.QUICK_CHAT),
                engine = enumOrDefault(entry.optString(KEY_ENGINE), ProcessingEngine.CLOUD),
                latencyMs = entry.optLong(KEY_LATENCY),
            )
        }
        return items
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private const val TAG = "TranscriptCodec"
    private const val KEY_ID = "id"
    private const val KEY_TEXT = "text"
    private const val KEY_TIMESTAMP = "ts"
    private const val KEY_MODE = "mode"
    private const val KEY_ENGINE = "engine"
    private const val KEY_LATENCY = "latency"
}

/**
 * Append-only history of everything Typorb has typed, newest first.
 *
 * Stored encrypted (see [SecurePreferences]) because dictated text is frequently private messages
 * and bug reports. Capped at [MAX_ITEMS]: the store is rewritten wholesale on every append, so an
 * unbounded list would make each dictation progressively slower.
 */
class TranscriptRepository(context: Context) {

    private val prefs = SecurePreferences.create(
        context.applicationContext,
        PREFS_NAME,
    )

    private val _transcripts = MutableStateFlow(load())

    /** Newest-first history. */
    val transcripts: StateFlow<List<Transcript>> = _transcripts.asStateFlow()

    /** Records a successful dictation, trimming anything past [MAX_ITEMS]. */
    fun add(transcript: Transcript) {
        persist((listOf(transcript) + _transcripts.value).take(MAX_ITEMS))
    }

    /** Removes one entry; returns `true` when something was actually deleted. */
    fun remove(id: String): Boolean {
        val current = _transcripts.value
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        persist(remaining)
        return true
    }

    /** Empties the vault. Returns `true` when there was anything to clear. */
    fun clear(): Boolean {
        if (_transcripts.value.isEmpty()) return false
        persist(emptyList())
        return true
    }

    private fun load(): List<Transcript> = TranscriptCodec.decode(
        prefs.getString(KEY_HISTORY, "").orEmpty(),
    )

    private fun persist(items: List<Transcript>) {
        _transcripts.value = items
        prefs.edit().putString(KEY_HISTORY, TranscriptCodec.encode(items)).apply()
    }

    private companion object {
        const val PREFS_NAME = "typorb_history"
        const val KEY_HISTORY = "transcripts"

        /** ~200 entries is plenty of scrollback and keeps the rewrite cost bounded. */
        const val MAX_ITEMS = 200
    }
}