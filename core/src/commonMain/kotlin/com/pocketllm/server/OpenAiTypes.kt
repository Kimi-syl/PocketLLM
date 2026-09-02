package com.pocketllm.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String = "user", val content: String = "")

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
)

@Serializable
data class CompletionRequest(
    val model: String? = null,
    val prompt: String = "",
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

@Serializable
data class Choice(
    val index: Int,
    val message: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class ChatCompletion(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
)

@Serializable
data class TextChoice(
    val index: Int,
    val text: String,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class TextCompletion(
    val id: String,
    @SerialName("object") val objectType: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<TextChoice>,
    val usage: Usage,
)

@Serializable
data class StreamChoice(
    val index: Int,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<StreamChoice>,
)

@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long = 0L,
    @SerialName("owned_by") val ownedBy: String = "pocketllm",
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelInfo>,
)

@Serializable
data class ErrorBody(
    val message: String,
    val type: String = "invalid_request_error",
    val code: String? = null,
)

@Serializable
data class ErrorResponse(val error: ErrorBody)
