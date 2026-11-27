package com.example.data.ai.providers

import kotlinx.coroutines.CancellationException

/**
 * Tries providers in configured priority order. Only transient failures trigger fallback.
 * It intentionally does not hide invalid requests, authentication failures, or cancellation.
 */
class AiProviderRouter(
    private val providers: List<AiTextProvider>
) {
    suspend fun generate(request: AiTextRequest): AiTextResponse {
        require(providers.isNotEmpty()) { "No AI providers are configured." }
        var lastTransient: AiProviderException? = null

        for (provider in providers) {
            try {
                return provider.generate(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AiProviderException) {
                if (!failure.shouldFallback) throw failure
                lastTransient = failure
            } catch (failure: Exception) {
                lastTransient = AiProviderException(
                    AiFailureKind.UNKNOWN,
                    "Provider ${provider.providerId} failed unexpectedly.",
                    failure
                )
                // Unknown failures are not automatically retried against another provider.
                throw lastTransient
            }
        }

        throw AiProviderException(
            AiFailureKind.TEMPORARY_UNAVAILABLE,
            "All configured AI providers are temporarily unavailable.",
            lastTransient
        )
    }
}
