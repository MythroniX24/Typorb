package com.typorb.local

import android.os.Build

/**
 * Session/thread tuning for the offline engine.
 *
 * The target is a low-end Android phone (e.g. Redmi 8A: Snapdragon 439, four Cortex-A53 at ~1.8 GHz,
 * 3 GB RAM, Adreno 505 with no meaningful NNAPI support). The rules below encode that reality and
 * are pure functions so they can be unit-tested on the JVM.
 */
object SessionTuning {

    /**
     * Intra-op threads for the CPU (XNNPACK) backend.
     *
     * One core is always left to the UI/IME thread — during inference the keyboard, the overlay and
     * the target app are all live, and starving them is what makes an overlay feel broken. Small
     * matmuls also stop scaling past ~4 threads.
     */
    fun intraOpThreads(availableProcessors: Int): Int = when {
        availableProcessors <= 1 -> 1
        availableProcessors == 2 -> 1 // both cores matter more than any speed-up
        else -> minOf(4, availableProcessors - 1)
    }

    /** Inter-op parallelism: the two graphs are already parallel to each other; keep ORT sequential. */
    const val INTER_OP_THREADS = 1

    /**
     * Whether the GPU backend may be attempted at all.
     *
     * NNAPI is only worth enabling on GPUs from roughly Adreno 6xx / Mali-G7xx onward. On older GPUs
     * (Adreno 5xx and older, PowerVR) the driver advertises the API but either fails to compile the
     * graph or falls back to a slow CPU path, which is *worse* than plain XNNPACK. It is therefore
     * opt-in from the dashboard and always guarded by a runtime fallback.
     */
    fun isNnapiEligible(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

    /**
     * Prompt-length budget for the greedy decode loop.
     *
     * Whisper caps a 30 s segment at 448 tokens; dictations are far shorter, so the loop stops as
     * soon as it cannot produce another useful token. Keeping the cap modest bounds worst-case
     * latency on slow CPUs.
     */
    fun maxDecoderSteps(durationMs: Long): Int = when {
        durationMs <= 0 -> 64
        durationMs < 3_000 -> 48
        durationMs < 10_000 -> 96
        durationMs < 20_000 -> 160
        durationMs < 28_000 -> 256
        // Near-max segments get Whisper's own limit so long dictations are never cut off.
        else -> HARD_TOKEN_CAP
    }

    private const val HARD_TOKEN_CAP = 448
}