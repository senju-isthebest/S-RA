package com.example.data.ai.providers

enum class AiResponseFormat { TEXT, JSON }

data class AiTextRequest(
    val prompt: String,
    val systemInstruction: String? = null,
    val temperature: Float = 0.4f,
    val maxTokens: Int? = null,
    val taskTag: String = "general",
    val responseFormat: AiResponseFormat = AiResponseFormat.TEXT
)

data class AiTextResponse(
    val text: String,
    val providerId: String,
    val modelId: String
)

enum class AiFailureKind {
    RATE_LIMITED,
    TEMPORARY_UNAVAILABLE,
    TIMEOUT,
    NETWORK,
    INVALID_RESPONSE,
    AUTHENTICATION,
    INVALID_REQUEST,
    CANCELLED,
    UNKNOWN
}

class AiProviderException(
    val kind: AiFailureKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    val shouldFallback: Boolean
        get() = kind in setOf(
            AiFailureKind.RATE_LIMITED,
            AiFailureKind.TEMPORARY_UNAVAILABLE,
            AiFailureKind.TIMEOUT,
            AiFailureKind.NETWORK
        )
}

interface AiTextProvider {
    val providerId: String
    val modelId: String
    suspend fun generate(request: AiTextRequest): AiTextResponse
}
