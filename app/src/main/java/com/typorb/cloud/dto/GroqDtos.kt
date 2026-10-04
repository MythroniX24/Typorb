package com.typorb.cloud.dto

import com.google.gson.annotations.SerializedName

/** `POST /openai/v1/audio/transcriptions` success payload. */
data class TranscriptionResponse(
    @SerializedName("text") val text: String? = null,
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String,
)

data class ChatCompletionRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("temperature") val temperature: Double = 0.2,
    @SerializedName("max_tokens") val maxTokens: Int = 700,
    @SerializedName("stream") val stream: Boolean = false,
)

data class ChatCompletionResponse(
    @SerializedName("choices") val choices: List<ChatChoice>? = null,
)

data class ChatChoice(
    @SerializedName("message") val message: ChatMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

/** OpenAI-compatible error envelope used by Groq. */
data class GroqErrorEnvelope(
    @SerializedName("error") val error: GroqErrorBody? = null,
)

data class GroqErrorBody(
    @SerializedName("message") val message: String? = null,
    @SerializedName("type") val type: String? = null,
)