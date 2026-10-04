package com.typorb.cloud.groq

import com.typorb.cloud.dto.ChatCompletionRequest
import com.typorb.cloud.dto.ChatCompletionResponse
import com.typorb.cloud.dto.TranscriptionResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Groq's OpenAI-compatible surface. Only two endpoints are needed: speech-to-text and chat
 * completions used for context formatting.
 */
interface GroqApi {

    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        @Part("language") language: RequestBody,
        @Part("temperature") temperature: RequestBody,
    ): TranscriptionResponse

    @POST("openai/v1/chat/completions")
    suspend fun complete(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): ChatCompletionResponse
}