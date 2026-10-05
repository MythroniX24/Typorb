package com.typorb.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import com.typorb.BuildConfig
import com.typorb.data.ModelFiles
import com.typorb.domain.TyporbException
import com.typorb.local.dsp.LogMelSpectrogram
import com.typorb.local.tokenizer.ByteLevelBpeTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

/** Output names that carry the decoder KV cache, and the input names that consume it. */
private const val PRESENT_PREFIX = "present."
private const val PAST_PREFIX = "past_key_values."

/**
 * Engine B core — a self-contained Whisper `tiny` decoder running entirely on-device through ONNX
 * Runtime, so dictation keeps working in airplane mode.
 *
 * Two export shapes are supported, detected automatically at load time:
 *
 *  * **Split (what `onnx-community/whisper-tiny` publishes)** — `whisper-tiny.onnx` is an encoder
 *    (`input_features` `[1, 80, 3000]` → `last_hidden_state`) and `whisper-tiny-decoder.onnx` is the
 *    merged decoder (`input_ids`, `encoder_hidden_states`, `use_cache_branch`, optional
 *    `past_key_values.N.{encoder,decoder}.{key,value}` → `logits`, `present.*`).
 *  * **Fused** — one file carrying the encoder and decoder together; the decoder's mel input is then
 *    produced internally and fed as `input_features`.
 *
 * `input_features` is always the full 30-second window (Whisper's encoder convolutions are anchored
 * to that length), and short takes are zero-padded to it.
 *
 * Sessions are loaded lazily, stay resident for the life of the process (the overlay may be toggled
 * constantly and a warm model makes the first tap instant) and are reclaimed by the OS on death. They
 * are deliberately *not* torn down when the accessibility service disconnects: that would race with a
 * concurrent [ensureLoaded] on the IO dispatcher and could orphan a native session.
 *
 * What *is* re-checked on every dictation is which files the session was built from
 * ([resolveSource]). A model the user downloads while Typorb is running has to be the model that
 * actually runs — otherwise a session loaded from the fp32 weights bundled in the APK stays resident
 * for the rest of the process and the freshly downloaded int8 model is never used, which on a budget
 * phone is the difference between a two-second dictation and a thirty-second one.
 */
class WhisperOnnxEngine(
    private val context: Context,
    private val modelAssetPath: String = BuildConfig.WHISPER_ASSET_PATH,
    private val decoderModelAssetPath: String = BuildConfig.WHISPER_DECODER_ASSET_PATH,
    private val tokenizerAssetPath: String = TOKENIZER_ASSET_PATH,
    /**
     * Files downloaded by [com.typorb.data.ModelRepository]. When present they win over the bundled
     * assets, which is what makes the offline engine work on a normal (Cloud-only) install.
     */
    private val downloadedFiles: suspend () -> ModelFiles? = { null },
    /** Enables the GPU backend when the device is capable; always falls back to XNNPACK on failure. */
    private val preferNnapi: Boolean = false,
) {

    private val loadLock = Mutex()

    @Volatile private var inference: Inference? = null

    @Volatile private var tokenizer: ByteLevelBpeTokenizer? = null

    /** Which bytes the resident [inference] was built from; see [resolveSource]. */
    @Volatile private var loadedKey: String? = null

    /** Warms the sessions up so the first real dictation is not slowed by model loading. */
    suspend fun warmUp() {
        withContext(Dispatchers.IO) { ensureLoaded() }
    }

    /**
     * @param languageToken decoder prompt token for the language the speaker is expected to use, e.g.
     *   `"<|hi|>"`. Anything the checkpoint does not define is skipped by
     *   [ByteLevelBpeTokenizer.buildPrompt].
     * @throws TyporbException when the model or tokenizer is missing or corrupt.
     */
    suspend fun transcribe(
        pcm: ByteArray,
        languageToken: String = ByteLevelBpeTokenizer.SPECIAL_ENGLISH,
    ): String = withContext(Dispatchers.Default) {
        val active = ensureLoaded()
        val activeTokenizer = tokenizer
            ?: throw TyporbException.localModelUnavailable(IllegalStateException("Tokenizer missing"))

        val features = computeFeatures(pcm)
        val durationMs = (pcm.size / 2).toLong() * MILLIS_PER_SECOND / SAMPLE_RATE
        val generated = active.greedyDecode(
            tokenizer = activeTokenizer,
            features = features,
            maxSteps = SessionTuning.maxDecoderSteps(durationMs),
            prompt = activeTokenizer.buildPrompt(languageToken),
        )
        activeTokenizer.decode(generated).trim().ifEmpty { throw TyporbException.noAudio() }
    }

    // ----------------------------------------------------------------- loading

    private suspend fun ensureLoaded(): Inference {
        val source = resolveSource()
        inference?.let { if (source.key == loadedKey) return it }
        return loadLock.withLock {
            inference?.let { if (source.key == loadedKey) return@withLock it }

            val tokenizerJson = withContext(Dispatchers.IO) {
                readSource(Source.TOKENIZER, source)
            }.decodeToString()
            val parsed = runCatching { ByteLevelBpeTokenizer.fromJson(tokenizerJson) }
                .getOrElse { error -> throw TyporbException.localModelUnavailable(error) }

            val loaded = withContext(Dispatchers.IO) {
                val environment = OrtEnvironment.getEnvironment()
                val first = environment.createSession(readSource(Source.ENCODER, source), optionsFor("encoder"))
                // Static check: a fused graph carries both the mel input and the decoder token input.
                val isFused = first.inputNames.any { it.contains("feature") } &&
                    DECODER_TOKEN_INPUTS.any { token -> first.inputNames.contains(token) }

                if (isFused) {
                    Inference(encoder = null, decoder = DecoderBinding(first))
                } else {
                    Inference(
                        encoder = EncoderBinding(first),
                        decoder = DecoderBinding(
                            environment.createSession(
                                readSource(Source.DECODER, source),
                                optionsFor("decoder"),
                            ),
                        ),
                    )
                }
            }

            inference = loaded
            loadedKey = source.key
            tokenizer = parsed
            Log.i(TAG, "Loaded Whisper (vocab=${parsed.vocabularySize}, fused=${loaded.isFused})")
            loaded
        }
    }

    /** One of the three model files, preferring a verified download over the bundled asset. */
    private enum class Source { ENCODER, DECODER, TOKENIZER }

    /**
     * The files a session would be built from right now, and a key that changes when they change.
     *
     * [key] is derived from file lengths rather than contents: it is read once per dictation, and the
     * sizes of three model files identify a variant well enough to notice a download appearing or a
     * variant being switched without hashing 40–150 MB on every tap.
     */
    private class ModelSource(val key: String, val files: ModelFiles?)

    private suspend fun resolveSource(): ModelSource = withContext(Dispatchers.IO) {
        val downloaded = runCatching { downloadedFiles() }.getOrNull()
        val usable = downloaded?.takeIf { files ->
            files.encoder.isFile && files.encoder.length() > 0L &&
                files.decoder.isFile && files.decoder.length() > 0L &&
                files.tokenizer.isFile && files.tokenizer.length() > 0L
        }
        if (usable == null) {
            return@withContext ModelSource(
                key = "assets:$modelAssetPath",
                files = null,
            )
        }
        ModelSource(
            key = "download:${usable.encoder.length()}:${usable.decoder.length()}:" +
                "${usable.tokenizer.length()}",
            files = usable,
        )
    }

    private suspend fun readSource(source: Source, model: ModelSource): ByteArray {
        val file = model.files?.let { files ->
            when (source) {
                Source.ENCODER -> files.encoder
                Source.DECODER -> files.decoder
                Source.TOKENIZER -> files.tokenizer
            }
        }
        if (file != null) {
            return runCatching { file.readBytes() }
                .getOrElse { error -> throw TyporbException.localModelUnavailable(error) }
        }
        val assetPath = when (source) {
            Source.ENCODER -> modelAssetPath
            Source.DECODER -> decoderModelAssetPath
            Source.TOKENIZER -> tokenizerAssetPath
        }
        return readAsset(assetPath)
    }

    /**
     * Session options tuned for mobile CPUs.
     *
     *  * XNNPACK gives f32 GEMM/MVN kernels roughly 2× the stock MLAS path on Arm;
     *  * one core is reserved for the UI (see [SessionTuning]);
     *  * the optimised graph is cached on disk, so the expensive ORT optimisation pass runs once
     *    instead of on every service start — worth many seconds on an A53;
     *  * inter-op stays sequential because the two graphs already run back to back.
     */
    private fun optionsFor(graph: String): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            val threads = SessionTuning.intraOpThreads(Runtime.getRuntime().availableProcessors())
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(SessionTuning.INTER_OP_THREADS)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)

            if (preferNnapi && SessionTuning.isNnapiEligible(Build.VERSION.SDK_INT)) {
                runCatching { addNnapi() }
                    .onFailure { Log.w(TAG, "NNAPI unavailable, staying on CPU", it) }
            } else {
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
                    .onFailure { Log.w(TAG, "XNNPACK unavailable, using default kernels", it) }
            }

            optimisedGraphCache(context, graph)?.let { path ->
                runCatching { setOptimizedModelFilePath(path) }
                    .onFailure { Log.w(TAG, "Optimised-model cache disabled", it) }
            }
        }

    /** `filesDir/onnx-opt/<graph>.ort`, recreated when a newer model replaces the old one. */
    private fun optimisedGraphCache(context: Context, graph: String): String? = runCatching {
        val dir = java.io.File(context.filesDir, "onnx-opt").apply { mkdirs() }
        java.io.File(dir, "$graph-${modelAssetPath.hashCode()}.ort").absolutePath
    }.getOrNull()

    private fun readAsset(path: String): ByteArray = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (error: Exception) {
        throw TyporbException.localModelUnavailable(error)
    }

    // ---------------------------------------------------------------- features

    /**
     * PCM16 → Whisper's log-mel tensor contents, laid out `[melBins][frames]` and zero-padded to the
     * full 30-second window.
     */
    private fun computeFeatures(pcm: ByteArray): FloatArray {
        val samples = pcm.toFloatSamples()
        val spectrogram = LogMelSpectrogram(sampleRate = SAMPLE_RATE)
        val energies = spectrogram.energies(samples)
        val availableFrames = energies.firstOrNull()?.size ?: 0
        if (availableFrames <= 0) throw TyporbException.noAudio()

        val usedFrames = min(EXPECTED_FRAMES, availableFrames)
        val normalised = LogMelSpectrogram.normalise(energies, usedFrames)
        val flat = FloatArray(MEL_BINS * EXPECTED_FRAMES) // trailing frames stay zero — Whisper's padding
        for (melBin in 0 until MEL_BINS) {
            val row = normalised[melBin]
            val offset = melBin * EXPECTED_FRAMES
            for (frame in 0 until usedFrames) flat[offset + frame] = row[frame]
        }
        return flat
    }

    // ------------------------------------------------------------ tensor plumbing

    /** Encoder half of a split export: mel features in, decoder memory out. */
    private class EncoderBinding(private val session: OrtSession) : AutoCloseable {

        val featureInput: String = session.inputNames.firstOrNull { it.contains("feature") }
            ?: error("Encoder exposes no mel-feature input (expected input_features)")

        val hiddenOutput: String = session.outputNames.firstOrNull { it.contains("hidden") }
            ?: session.outputNames.first()

        /** Runs the encoder and returns the `last_hidden_state` tensor, owned by the caller. */
        fun run(features: FloatArray): OnnxTensor {
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(features),
                longArrayOf(1, MEL_BINS.toLong(), EXPECTED_FRAMES.toLong()),
            )
            return session.run(mapOf(featureInput to tensor)).use { result ->
                // Widened to Any? so the type check is a real runtime guard rather than a cast the
                // compiler can prove redundant from ORT's typed accessor.
                val value: Any? = result[hiddenOutput]
                if (value !is OnnxTensor) error("Encoder produced no hidden-state tensor")
                value
            }
        }

        override fun close() {
            session.close()
        }
    }

    /** Decoder half (or the whole fused graph): greedy decode with an optional KV cache. */
    private class DecoderBinding(private val session: OrtSession) : AutoCloseable {

        private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

        private val tokenInput: String = DECODER_TOKEN_INPUTS.firstOrNull { session.inputNames.contains(it) }
            ?: error("Decoder exposes no token input (expected input_ids)")

        private val featureInput: String? =
            session.inputNames.firstOrNull { it.contains("feature") }

        private val encoderHiddenInput: String? =
            session.inputNames.firstOrNull { it.contains("encoder_hidden_states") }

        private val cacheBranchInput: String? =
            session.inputNames.firstOrNull { it.contains("use_cache") || it.contains("use_cache_branch") }

        private val logitsOutput: String =
            session.outputNames.firstOrNull { it.contains("logits") } ?: session.outputNames.first()

        /** Maps each cache output name onto the input that consumes it in the next step. */
        private val cacheInputByOutput: Map<String, String> = buildMap {
            for (name in session.outputNames) {
                if (!name.startsWith(PRESENT_PREFIX) && !name.startsWith(PAST_PREFIX)) continue
                cacheInputCandidates(name).firstOrNull { candidate ->
                    session.inputNames.contains(candidate)
                }?.let { put(name, it) }
            }
        }

        /**
         * @param encoderHidden non-null only for split exports; for fused graphs the mel features are
         *   fed directly instead.
         */
        fun greedyDecode(
            tokenizer: ByteLevelBpeTokenizer,
            features: FloatArray,
            encoderHidden: OnnxTensor?,
            maxSteps: Int,
            prompt: LongArray,
        ): List<Long> {
            val generated = mutableListOf<Long>()
            var tokens = prompt
            var cache: Map<String, OnnxTensor> = emptyMap()

            repeat(maxSteps) {
                // With a cache only the newest token is needed; without one the whole prefix re-runs.
                val feed = if (cache.isEmpty()) tokens else tokens.takeLast(1).toLongArray()
                val outputs = run(features, encoderHidden, feed, cache, useCache = cache.isNotEmpty())

                val next = argmaxAtLastFrame(outputs[logitsOutput]) ?: return generated
                if (tokenizer.isStopToken(next)) return generated
                generated += next
                cache = readCache(outputs)
                tokens = tokens + next
            }
            return generated
        }

        private fun run(
            features: FloatArray,
            encoderHidden: OnnxTensor?,
            tokenIds: LongArray,
            cache: Map<String, OnnxTensor>,
            useCache: Boolean,
        ): Map<String, OnnxValue> {
            val inputs = HashMap<String, OnnxTensor>(2 + cache.size + 1)
            inputs[tokenInput] = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenIds),
                longArrayOf(1, tokenIds.size.toLong()),
            )
            encoderHidden?.let { hidden -> encoderHiddenInput?.let { inputs[it] = hidden } }
            featureInput?.let { name ->
                inputs[name] = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(features),
                    longArrayOf(1, MEL_BINS.toLong(), EXPECTED_FRAMES.toLong()),
                )
            }
            if (useCache) {
                cacheBranchInput?.let { name ->
                    inputs[name] = OnnxTensor.createTensor(environment, booleanArrayOf(true))
                }
                cache.forEach { (outputName, tensor) ->
                    cacheInputByOutput[outputName]?.let { inputs[it] = tensor }
                }
            }

            session.run(inputs).use { result ->
                val named = LinkedHashMap<String, OnnxValue>(session.outputNames.size)
                session.outputNames.forEachIndexed { index, name -> named[name] = result[index] }
                return named
            }
        }

        private fun readCache(outputs: Map<String, OnnxValue>): Map<String, OnnxTensor> {
            if (cacheInputByOutput.isEmpty()) return emptyMap()
            val cache = HashMap<String, OnnxTensor>(cacheInputByOutput.size)
            cacheInputByOutput.forEach { (outputName, _) ->
                (outputs[outputName] as? OnnxTensor)?.let { cache[outputName] = it }
            }
            return cache
        }

        /**
         * `present.<rest>` feeds `past_key_values.<rest>` in the next step; the fused naming drops the
         * encoder/decoder infix, so that variant is tried too.
         */
        private fun cacheInputCandidates(outputName: String): List<String> {
            val candidates = mutableListOf(outputName)
            if (outputName.startsWith(PRESENT_PREFIX)) {
                val suffix = outputName.removePrefix(PRESENT_PREFIX)
                candidates += "past_key_values.$suffix"
                candidates += "past_key_values.${suffix.removePrefix("encoder.").removePrefix("decoder.")}"
            }
            return candidates
        }

        /** Greedy argmax over the final `[…, vocab]` row of a `[1][T][V]` or `[1][V]` logits tensor. */
        private fun argmaxAtLastFrame(logits: OnnxValue?): Long? {
            val row = lastFrameOf((logits as? OnnxTensor)?.value) ?: return null
            var bestId = 0L
            var bestScore = Float.NEGATIVE_INFINITY
            for (token in row.indices) {
                val score = row[token]
                if (score > bestScore) {
                    bestScore = score
                    bestId = token.toLong()
                }
            }
            return bestId
        }

        private fun lastFrameOf(value: Any?): FloatArray? = when (value) {
            is FloatArray -> value
            is Array<*> -> unwrap(value, depth = 0)
            else -> null
        }

        private fun unwrap(node: Any?, depth: Int): FloatArray? {
            if (depth > MAX_TENSOR_DEPTH || node !is Array<*>) {
                return if (node is FloatArray) node else collect(node)
            }
            val last = node.lastOrNull() ?: return null
            return when (last) {
                is FloatArray -> last
                is Array<*> -> unwrap(last, depth + 1)
                else -> collect(last)
            }
        }

        private fun collect(source: Any?): FloatArray {
            val floats = ArrayList<Float>(DEFAULT_VOCABULARY)
            fun walk(node: Any?) {
                when (node) {
                    is FloatArray -> node.forEach(floats::add)
                    is ShortArray -> node.forEach { floats.add(halfToFloat(it)) }
                    is Array<*> -> node.forEach(::walk)
                }
            }
            walk(source)
            return floats.toFloatArray()
        }

        override fun close() {
            session.close()
        }
    }

    /** Encoder + decoder pair (or a fused decoder standing alone). */
    private class Inference(
        private val encoder: EncoderBinding?,
        private val decoder: DecoderBinding,
    ) : AutoCloseable {

        /** `true` when a single ONNX file carries both halves of the model. */
        val isFused: Boolean get() = encoder == null

        fun greedyDecode(
            tokenizer: ByteLevelBpeTokenizer,
            features: FloatArray,
            maxSteps: Int,
            prompt: LongArray,
        ): List<Long> {
            val hidden = encoder?.run(features)
            return try {
                decoder.greedyDecode(tokenizer, features, hidden, maxSteps, prompt)
            } finally {
                hidden?.close()
            }
        }

        override fun close() {
            encoder?.close()
            decoder.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    companion object {
        private const val TAG = "WhisperOnnxEngine"

        const val SAMPLE_RATE = 16_000
        const val MEL_BINS = LogMelSpectrogram.DEFAULT_MEL_BINS
        const val EXPECTED_FRAMES = 3_000
        const val TOKENIZER_ASSET_PATH = "whisper/tokenizer.json"

        private val DECODER_TOKEN_INPUTS = listOf("input_ids", "decoder_input_ids")

        /** Greedy-loop budget is derived from the recording length by [SessionTuning]. */
        private const val MILLIS_PER_SECOND = 1_000L
        internal const val MAX_TENSOR_DEPTH = 4
        internal const val DEFAULT_VOCABULARY = 51_866

        /** Little-endian PCM16 → float in `[-1, 1]`. */
        internal fun ByteArray.toFloatSamples(): FloatArray {
            val sampleCount = size / 2
            return FloatArray(sampleCount) { index ->
                val low = this[index * 2].toInt() and 0xFF
                val high = this[index * 2 + 1].toInt()
                val value = (high shl 8) or low
                if (value >= 0x8000) (value - 0x10000) / 32768f else value / 32768f
            }
        }

        /** IEEE 754 binary16 → binary32. */
        internal fun halfToFloat(value: Short): Float {
            val bits = value.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits and 0x7C00) shr 10
            val mantissa = bits and 0x03FF
            return when (exponent) {
                0 -> if (mantissa == 0) {
                    Float.fromBits(sign)
                } else {
                    var shift = -1
                    var normalised = mantissa
                    while (normalised and 0x0400 == 0) {
                        normalised = normalised shl 1
                        shift++
                    }
                    Float.fromBits(
                        sign or ((127 - 15 - shift) shl 23) or ((normalised and 0x03FF) shl 13),
                    )
                }
                0x1F -> Float.fromBits(sign or 0x7F800000 or (mantissa shl 13))
                else -> Float.fromBits(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
            }
        }
    }
}