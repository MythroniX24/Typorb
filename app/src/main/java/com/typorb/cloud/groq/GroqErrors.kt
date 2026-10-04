package com.typorb.cloud.groq

import com.google.gson.Gson
import com.typorb.cloud.dto.GroqErrorEnvelope
import com.typorb.domain.TyporbException
import retrofit2.HttpException
import java.io.IOException

/**
 * Translates transport/HTTP failures into messages that make sense in the overlay pill, and decides
 * which ones are worth retrying automatically.
 */
object GroqErrors {

    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_FORBIDDEN = 403
    private const val STATUS_TOO_LARGE = 413
    private const val STATUS_RATE_LIMIT = 429

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is TyporbException -> error.retryable
        is IOException -> true
        is HttpException -> error.code() == STATUS_RATE_LIMIT || error.code() >= 500
        else -> false
    }

    fun toTyporbException(error: Throwable, gson: Gson = GroqClientFactory.gson): TyporbException {
        if (error is TyporbException) return error
        return when (error) {
            is HttpException -> fromHttp(error, gson)
            is IOException -> TyporbException(
                message = "Can't reach Groq. Check your connection or switch to Local mode.",
                retryable = true,
                cause = error,
            )
            else -> TyporbException(
                message = "Cloud processing failed. Try again or switch to Local mode.",
                retryable = false,
                cause = error,
            )
        }
    }

    private fun fromHttp(error: HttpException, gson: Gson): TyporbException {
        val serverMessage = parseServerMessage(error, gson)
        val (message, retryable) = when (error.code()) {
            STATUS_UNAUTHORIZED -> "Groq rejected your API key. Update it in Typorb." to false
            STATUS_FORBIDDEN -> "This Groq key can't use ${error.code()} resources. Check your plan." to false
            STATUS_TOO_LARGE -> "That recording is too long. Keep dictations under a minute." to false
            STATUS_RATE_LIMIT -> "Groq rate limit hit. Retrying shortly." to true
            in 500..599 -> "Groq is having a moment. Retrying shortly." to true
            else -> "Groq returned ${error.code()}. Try again." to false
        }
        return TyporbException(
            message = serverMessage ?: message,
            retryable = retryable,
            cause = error,
        )
    }

    private fun parseServerMessage(error: HttpException, gson: Gson): String? = runCatching {
        val body = error.response()?.errorBody()?.string().orEmpty()
        if (body.isBlank()) return@runCatching null
        gson.fromJson(body, GroqErrorEnvelope::class.java)?.error?.message
    }.getOrNull()
}