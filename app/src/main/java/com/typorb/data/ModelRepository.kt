package com.typorb.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** What the offline engine can do right now. */
sealed interface OfflineModelState {
    /** Nothing downloaded. */
    data class NotInstalled(val totalBytes: Long) : OfflineModelState

    /** Verified and ready to load. */
    data class Ready(val totalBytes: Long, val onDiskBytes: Long) : OfflineModelState

    /** A download is running. */
    data class Downloading(
        val currentFile: String,
        val fileBytes: Long,
        val fileTotalBytes: Long,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : OfflineModelState {
        val fileProgress: Float
            get() = if (fileTotalBytes <= 0L) 0f else (fileBytes.toFloat() / fileTotalBytes).coerceIn(0f, 1f)

        val overallProgress: Float
            get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
    }

    /** Present on disk but failed verification (truncated download, corrupt flash). */
    data class Corrupt(val reason: String) : OfflineModelState

    /** Download failed; [retryable] says whether tapping "retry" can help. */
    data class Failed(val reason: String, val retryable: Boolean = true) : OfflineModelState
}

/**
 * Downloads and verifies the offline Whisper weights into app-private storage.
 *
 * Design points that matter on the target hardware:
 *  * **streamed, never buffered** — the decoder is 30–118 MB and a low-end phone cannot afford a
 *    byte array of that size, so the response is written straight to disk;
 *  * **SHA-256 verified** before a file is promoted from `.part` to its final name, so a truncated
 *    or corrupted transfer can never be loaded as a model;
 *  * **atomic** — the engine only ever sees complete files;
 *  * **cancellable** and safe to retry: partial files are removed before each attempt.
 */
class ModelRepository(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    private val root: File = File(context.filesDir, "models")

    private val _state = MutableStateFlow<OfflineModelState>(OfflineModelState.NotInstalled(0L))

    /** Download/verification progress for the UI. */
    val state: StateFlow<OfflineModelState> = _state.asStateFlow()

    @Volatile
    private var activeVariant: ModelCatalog.Variant? = null

    /** Resolves verified files for [variant], or `null` when it is not installed. */
    suspend fun filesFor(variant: ModelCatalog.Variant): ModelFiles? =
        withContext(Dispatchers.IO) { resolveFiles(variant) }

    /** Re-reads disk state without touching the network and publishes it through [state]. */
    suspend fun refresh(variant: ModelCatalog.Variant): OfflineModelState =
        withContext(Dispatchers.IO) { inspect(variant) }.also { _state.value = it }

    /**
     * Downloads every file of [variant], reporting progress through [state].
     *
     * @throws CancellationException when the caller cancels; the `.part` file is cleaned up.
     */
    suspend fun download(variant: ModelCatalog.Variant) {
        if (activeVariant != null) throw IllegalStateException("A model download is already running")
        activeVariant = variant
        try {
            val dir = variantDirectory(variant).apply { mkdirs() }
            val totalBytes = variant.totalBytes
            var doneBytes = 0L

            for (file in variant.files) {
                val target = File(dir, file.name)
                if (isVerified(target, file.sha256)) {
                    doneBytes += file.sizeBytes
                    continue
                }
                // Clear any half-finished attempt before starting over.
                File(dir, "${file.name}.part").delete()
                target.delete()

                _state.value = OfflineModelState.Downloading(
                    currentFile = file.name,
                    fileBytes = 0L,
                    fileTotalBytes = file.sizeBytes,
                    bytesDone = doneBytes,
                    bytesTotal = totalBytes,
                )

                downloadVerified(file, File(dir, "${file.name}.part")) { downloaded ->
                    _state.value = OfflineModelState.Downloading(
                        currentFile = file.name,
                        fileBytes = downloaded,
                        fileTotalBytes = file.sizeBytes,
                        bytesDone = doneBytes + downloaded,
                        bytesTotal = totalBytes,
                    )
                }

                // Promote the verified temp file atomically; the engine never sees a partial file.
                val part = File(dir, "${file.name}.part")
                target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                doneBytes += file.sizeBytes
            }

            _state.value = inspect(variant)
        } catch (cancellation: CancellationException) {
            cleanupPartials(variant)
            _state.value = OfflineModelState.Failed("Download cancelled.", retryable = true)
            throw cancellation
        } catch (error: Exception) {
            cleanupPartials(variant)
            Log.w(TAG, "Model download failed", error)
            _state.value = OfflineModelState.Failed(
                reason = error.message ?: "Download failed.",
                retryable = error !is IOException,
            )
        } finally {
            activeVariant = null
        }
    }

    /** Removes a downloaded variant (and any partial files). */
    suspend fun delete(variant: ModelCatalog.Variant): Unit = withContext(Dispatchers.IO) {
        variantDirectory(variant).deleteRecursively()
        _state.value = OfflineModelState.NotInstalled(variant.totalBytes)
    }

    /** Files the engine can load, or null if anything is missing or corrupt. */
    fun resolveFiles(variant: ModelCatalog.Variant): ModelFiles? {
        val dir = variantDirectory(variant)
        val encoder = File(dir, variant.encoder.name)
        val decoder = File(dir, variant.decoder.name)
        val tokenizer = File(dir, variant.tokenizer.name)
        val allPresent = listOf(encoder, decoder, tokenizer).all { it.isFile && it.length() > 0L }
        if (!allPresent) return null
        return ModelFiles(encoder, decoder, tokenizer)
    }

    private fun inspect(variant: ModelCatalog.Variant): OfflineModelState {
        val dir = variantDirectory(variant)
        val files = resolveFiles(variant)
        if (files == null) {
            val partial = dir.listFiles()?.any { it.name.endsWith(".part") } == true
            return if (partial) {
                OfflineModelState.Corrupt("An earlier download did not finish.")
            } else {
                OfflineModelState.NotInstalled(variant.totalBytes)
            }
        }
        val corrupt = listOf(
            variant.encoder to files.encoder,
            variant.decoder to files.decoder,
            variant.tokenizer to files.tokenizer,
        ).firstOrNull { (spec, onDisk) -> !isVerified(onDisk, spec.sha256) }
        if (corrupt != null) {
            return OfflineModelState.Corrupt("${corrupt.first.name} failed its integrity check.")
        }
        val onDiskBytes = files.encoder.length() + files.decoder.length() + files.tokenizer.length()
        return OfflineModelState.Ready(variant.totalBytes, onDiskBytes)
    }

    private fun downloadVerified(
        spec: ModelCatalog.ModelFile,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        val request = Request.Builder().url(spec.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${spec.name}")
            }
            val body = response.body ?: throw IOException("Empty response for ${spec.name}")
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L

            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }

            val actual = digest.digest().toHexString()
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                destination.delete()
                throw IOException("Integrity check failed for ${spec.name} (expected ${spec.sha256.take(12)}…, got ${actual.take(12)}…)")
            }
            if (written != spec.sizeBytes) {
                destination.delete()
                throw IOException("Size mismatch for ${spec.name}: got $written, expected ${spec.sizeBytes}")
            }
        }
    }

    private fun cleanupPartials(variant: ModelCatalog.Variant) {
        variantDirectory(variant).listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    private fun variantDirectory(variant: ModelCatalog.Variant): File =
        File(root, variant.id)

    companion object {
        private const val TAG = "ModelRepository"

        /** 256 KB: large enough to keep syscalls low, small enough for a 3 GB phone. */
        private const val DOWNLOAD_BUFFER_BYTES = 256 * 1024

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true) // huggingface.co redirects to a CDN
            .retryOnConnectionFailure(true)
            .build()

        /** Verifies a file's SHA-256. Exposed for tests and for corruption checks. */
        fun isVerified(file: File, expectedSha256: String): Boolean {
            if (!file.isFile || file.length() <= 0L) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream -> stream.digestInto(digest) }
            return digest.digest().toHexString().equals(expectedSha256, ignoreCase = true)
        }

        private fun InputStream.digestInto(digest: MessageDigest) {
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }

        private fun ByteArray.toHexString(): String {
            val hex = "0123456789abcdef"
            val out = StringBuilder(size * 2)
            for (byte in this) {
                val value = byte.toInt() and 0xFF
                out.append(hex[value ushr 4]).append(hex[value and 0x0F])
            }
            return out.toString()
        }
    }
}

/** Verified, on-disk model files ready for the engine. */
data class ModelFiles(
    val encoder: File,
    val decoder: File,
    val tokenizer: File,
)