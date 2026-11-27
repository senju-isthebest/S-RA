package com.example.data.ai.providers

import kotlinx.coroutines.CancellationException

data class AiTextRequest(
    val prompt: String,
    val systemInstruction: String? = null,
    val temperature: Float = 0.4f,
    val maxTokens: Int? = null,
    val taskTag: String = "general"
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
