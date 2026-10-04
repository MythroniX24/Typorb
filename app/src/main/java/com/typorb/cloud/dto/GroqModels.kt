package com.typorb.cloud.dto

import com.google.gson.annotations.SerializedName

/**
 * Response of `GET /openai/v1/models`.
 *
 * Only used by the Settings screen's Test Connection button, which needs to prove a key is valid
 * without spending a transcription request.
 */
data class GroqModelsResponse(
    @SerializedName("data") val data: List<GroqModel> = emptyList(),
)

data class GroqModel(
    @SerializedName("id") val id: String = "",
)