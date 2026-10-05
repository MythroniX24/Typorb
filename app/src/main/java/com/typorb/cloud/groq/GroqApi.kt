package com.typorb.cloud.groq

import com.typorb.cloud.dto.ChatCompletionRequest
import com.typorb.cloud.dto.ChatCompletionResponse
import com.typorb.cloud.dto.GroqModelsResponse
import com.typorb.cloud.dto.TranscriptionResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Groq's OpenAI-compatible surface. Only two endpoints are needed: speech-to-text and chat
 * completions used for context formatting.
 */
interface GroqApi {

    /**
     * @param language ISO-639-1 code, or `null` to let Whisper detect the language itself. Retrofit
     *   omits a null part entirely, which is exactly how "auto" is expressed in the API.
     */
    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        @Part("language") language: RequestBody?,
        @Part("temperature") temperature: RequestBody,
    ): TranscriptionResponse

    /**
     * Lists the models the key can reach. Used by Test Connection, which needs to prove a key is
     * valid without spending a transcription request on silence.
     */
    @GET("openai/v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String,
    ): GroqModelsResponse

    @POST("openai/v1/chat/completions")
    suspend fun complete(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest,
    ): ChatCompletionResponse
}