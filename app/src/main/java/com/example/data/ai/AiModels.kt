package com.example.data.ai

enum class AiProviderId {
    OLLAMA,
    GEMINI
}

enum class AiRoutingStrategy {
    OLLAMA_FIRST,
    GEMINI_FIRST,
    OLLAMA_ONLY,
    GEMINI_ONLY
}

enum class AiCapability {
    TEXT_GENERATION,
    STREAMING,
    JSON_MODE,
    VISION,
    MODEL_DISCOVERY
}

data class AiProviderConfig(
    val strategy: AiRoutingStrategy = AiRoutingStrategy.GEMINI_FIRST,
    val fallbackEnabled: Boolean = true,
    val ollamaEndpoint: String = DEFAULT_OLLAMA_ENDPOINT,
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    val ollamaVisionModel: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiApiKey: String = "",
    val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxRetries: Int = 1,
    val localOnlyPrivacyMode: Boolean = false
) {
    companion object {
        const val DEFAULT_OLLAMA_ENDPOINT = "http://localhost:11434"
        const val EMULATOR_OLLAMA_ENDPOINT = "http://10.0.2.2:11434"
        const val DEFAULT_OLLAMA_MODEL = "qwen2.5:3b"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024 // 10 MB safety limit
        const val MAX_PROMPT_CHARS = 80_000
    }
}

data class AiTextRequest(
    val prompt: String,
    val systemPrompt: String = "",
    val jsonMode: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

data class AiVisionRequest(
    val prompt: String,
    val imageBytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val jsonMode: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AiVisionRequest) return false
        return prompt == other.prompt &&
            mimeType == other.mimeType &&
            jsonMode == other.jsonMode &&
            imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int {
        var result = prompt.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + jsonMode.hashCode()
        return result
    }
}

data class AiResponse(
    val text: String,
    val provider: AiProviderId,
    val modelUsed: String,
    val fallbackUsed: Boolean = false,
    val latencyMs: Long = 0L
)

data class OllamaModelInfo(
    val name: String,
    val sizeBytes: Long = 0L,
    val modifiedAt: String = "",
    val supportsVisionHint: Boolean = false
)

data class ConnectionTestResult(
    val success: Boolean,
    val provider: AiProviderId,
    val message: String,
    val discoveredModels: List<OllamaModelInfo> = emptyList(),
    val latencyMs: Long = 0L
)

sealed class AiError(
    message: String,
    val provider: AiProviderId,
    val retryable: Boolean,
    val triggersFallback: Boolean,
    cause: Throwable? = null
) : Exception(SecretRedactor.redact(message), cause) {

    class ConfigurationError(
        provider: AiProviderId,
        message: String
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class ConnectionError(
        provider: AiProviderId,
        message: String,
        cause: Throwable? = null
    ) : AiError(message, provider, retryable = true, triggersFallback = true, cause = cause)

    class TimeoutError(
        provider: AiProviderId,
        message: String,
        cause: Throwable? = null
    ) : AiError(message, provider, retryable = true, triggersFallback = true, cause = cause)

    class RateLimitError(
        provider: AiProviderId,
        val retryAfterSeconds: Int? = null,
        message: String = "Rate limit exceeded (HTTP 429)."
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class AuthenticationError(
        provider: AiProviderId,
        message: String
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class ModelNotFoundError(
        provider: AiProviderId,
        val modelName: String,
        message: String
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class InvalidRequestError(
        provider: AiProviderId,
        message: String
    ) : AiError(message, provider, retryable = false, triggersFallback = false)

    class ServerError(
        provider: AiProviderId,
        val statusCode: Int,
        message: String
    ) : AiError(message, provider, retryable = true, triggersFallback = true)

    class MalformedResponseError(
        provider: AiProviderId,
        message: String,
        cause: Throwable? = null
    ) : AiError(message, provider, retryable = false, triggersFallback = true, cause = cause)

    class EmptyResponseError(
        provider: AiProviderId,
        message: String = "Provider returned an empty response."
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class UnsupportedCapabilityError(
        provider: AiProviderId,
        val capability: AiCapability,
        message: String
    ) : AiError(message, provider, retryable = false, triggersFallback = true)

    class AllProvidersFailedError(
        val errors: List<AiError>,
        message: String = errors.joinToString(" | ") { "${it.provider.name}: ${it.message}" }
    ) : AiError(
        message = message.ifBlank { "All AI providers failed." },
        provider = errors.lastOrNull()?.provider ?: AiProviderId.GEMINI,
        retryable = false,
        triggersFallback = false
    )
}
