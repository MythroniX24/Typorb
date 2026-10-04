package com.typorb.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Runs [block] up to [maxAttempts] times, backing off exponentially with jitter.
 *
 * [shouldRetry] decides which failures are worth repeating — rate limits and 5xx responses are,
 * a 401 from a wrong API key is not.
 */
suspend fun <T> retrying(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500L,
    maxDelayMs: Long = 4_000L,
    shouldRetry: (Throwable) -> Boolean,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable = IllegalStateException("retrying requires at least one attempt")
    repeat(maxAttempts) { index ->
        val attempt = index + 1
        try {
            return block(attempt)
        } catch (error: Throwable) {
            lastError = error
            val isLastAttempt = attempt == maxAttempts
            if (isLastAttempt || !shouldRetry(error)) throw error
            onRetry(attempt, error)
            val backoff = min(maxDelayMs, initialDelayMs shl (attempt - 1))
            delay(backoff + Random.nextLong(0, 250L))
        }
    }
    throw lastError
}