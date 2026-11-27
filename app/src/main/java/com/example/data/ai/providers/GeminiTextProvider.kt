package com.example.data.ai.providers

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class GeminiTextProvider(
    override val modelId: String = "gemini-3.8-flash",
    private val timeoutMs: Long = 60_000L
) : AiTextProvider {
    override val providerId: String = "gemini"

    private val textModel by lazy { Firebase.ai.generativeModel(modelId) }
    private val jsonModel by lazy {
        Firebase.ai.generativeModel(
            modelName = modelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }

    override suspend fun generate(request: AiTextRequest): AiTextResponse {
        val prompt = buildString {
            request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
                append("System instructions:\n").append(it).append("\n\n")
            }
            append(request.prompt)
        }
        try {
            val result = withTimeout(timeoutMs) {
                (if (request.responseFormat == AiResponseFormat.JSON) jsonModel else textModel).generateContent(prompt)
            }
            val text = result.text?.trim().orEmpty()
            if (text.isBlank()) {
                throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "Gemini returned empty text.")
            }
            return AiTextResponse(text, providerId, modelId)
        } catch (timeout: TimeoutCancellationException) {
            throw AiProviderException(AiFailureKind.TIMEOUT, "Gemini request timed out.", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AiProviderException) {
            throw failure
        } catch (failure: Exception) {
            val message = failure.message.orEmpty().lowercase()
            val kind = when {
                "429" in message || "quota" in message || "rate" in message -> AiFailureKind.RATE_LIMITED
                "timeout" in message || "timed out" in message -> AiFailureKind.TIMEOUT
                "network" in message || "unavailable" in message || "503" in message || "502" in message || "500" in message || "504" in message -> AiFailureKind.NETWORK
                "401" in message || "403" in message || "permission" in message -> AiFailureKind.AUTHENTICATION
                else -> AiFailureKind.UNKNOWN
            }
            throw AiProviderException(kind, "Gemini text generation failed.", failure)
        }
    }
}
