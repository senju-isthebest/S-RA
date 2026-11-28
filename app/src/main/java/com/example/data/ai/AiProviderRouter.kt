package com.example.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class AiProviderRouter(
    private val adapters: Map<AiProviderId, AiProviderAdapter>
) {

    fun resolveOrder(config: AiProviderConfig): List<AiProviderId> {
        val raw = when (config.strategy) {
            AiRoutingStrategy.OLLAMA_FIRST -> if (config.fallbackEnabled) listOf(AiProviderId.OLLAMA, AiProviderId.GEMINI) else listOf(AiProviderId.OLLAMA)
            AiRoutingStrategy.GEMINI_FIRST -> if (config.fallbackEnabled) listOf(AiProviderId.GEMINI, AiProviderId.OLLAMA) else listOf(AiProviderId.GEMINI)
            AiRoutingStrategy.OLLAMA_ONLY -> listOf(AiProviderId.OLLAMA)
            AiRoutingStrategy.GEMINI_ONLY -> listOf(AiProviderId.GEMINI)
        }
        return raw.distinct().filter { id ->
            adapters.containsKey(id) && (!config.localOnlyPrivacyMode || id != AiProviderId.GEMINI)
        }
    }

    private fun emptyOrderError(config: AiProviderConfig): AiError.ConfigurationError =
        if (config.localOnlyPrivacyMode) {
            AiError.ConfigurationError(
                AiProviderId.OLLAMA,
                "Local-Only Privacy Mode blocks cloud Gemini requests. Configure local Ollama or switch AI strategy."
            )
        } else {
            AiError.ConfigurationError(AiProviderId.GEMINI, "No AI adapters are configured.")
        }

    suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse {
        val order = resolveOrder(config)
        if (order.isEmpty()) {
            throw emptyOrderError(config)
        }

        val requiredCapability = if (request.jsonMode) AiCapability.JSON_MODE else AiCapability.TEXT_GENERATION
        val collectedErrors = mutableListOf<AiError>()

        for ((index, providerId) in order.withIndex()) {
            currentCoroutineContext().ensureActive()
            val adapter = adapters[providerId] ?: continue

            if (!adapter.capabilities.contains(requiredCapability)) {
                val err = AiError.UnsupportedCapabilityError(
                    providerId,
                    requiredCapability,
                    "${providerId.name} does not support ${requiredCapability.name}."
                )
                collectedErrors.add(err)
                if (!config.fallbackEnabled) break
                continue
            }

            try {
                val response = executeWithRetry(config) {
                    adapter.generateText(request, config)
                }
                return response.copy(fallbackUsed = index > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                collectedErrors.add(e)
                if (!e.triggersFallback || !config.fallbackEnabled) {
                    break
                }
            } catch (e: Exception) {
                val wrapped = AiError.ServerError(providerId, 500, SecretRedactor.redact(e.message ?: "Unexpected error", listOf(config.geminiApiKey)))
                collectedErrors.add(wrapped)
                if (!config.fallbackEnabled) break
            }
        }

        if (collectedErrors.size == 1) throw collectedErrors.first()
        throw AiError.AllProvidersFailedError(collectedErrors)
    }

    fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String> = flow {
        val order = resolveOrder(config)
        if (order.isEmpty()) {
            throw emptyOrderError(config)
        }

        val collectedErrors = mutableListOf<AiError>()
        for (providerId in order) {
            currentCoroutineContext().ensureActive()
            val adapter = adapters[providerId] ?: continue
            if (!adapter.capabilities.contains(AiCapability.STREAMING)) {
                collectedErrors.add(
                    AiError.UnsupportedCapabilityError(providerId, AiCapability.STREAMING, "${providerId.name} does not support streaming.")
                )
                if (!config.fallbackEnabled) break
                continue
            }
            var emittedAny = false
            try {
                emitAll(
                    flow {
                        adapter.streamText(request, config).collect { chunk ->
                            emittedAny = true
                            emit(chunk)
                        }
                    }
                )
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                collectedErrors.add(e)
                // Do not fall back mid-stream if partial chunks already reached the UI
                if (emittedAny || !e.triggersFallback || !config.fallbackEnabled) break
            } catch (e: Exception) {
                val wrapped = AiError.ServerError(providerId, 500, SecretRedactor.redact(e.message ?: "Stream error", listOf(config.geminiApiKey)))
                collectedErrors.add(wrapped)
                if (emittedAny || !config.fallbackEnabled) break
            }
        }

        if (collectedErrors.size == 1) throw collectedErrors.first()
        throw AiError.AllProvidersFailedError(collectedErrors)
    }

    suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse {
        val order = resolveOrder(config)
        if (order.isEmpty()) {
            throw emptyOrderError(config)
        }
        val collectedErrors = mutableListOf<AiError>()

        for ((index, providerId) in order.withIndex()) {
            currentCoroutineContext().ensureActive()
            val adapter = adapters[providerId] ?: continue
            if (!adapter.capabilities.contains(AiCapability.VISION)) {
                collectedErrors.add(
                    AiError.UnsupportedCapabilityError(providerId, AiCapability.VISION, "${providerId.name} does not support vision.")
                )
                if (!config.fallbackEnabled) break
                continue
            }
            try {
                val response = executeWithRetry(config) {
                    adapter.generateVision(request, config)
                }
                return response.copy(fallbackUsed = index > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                collectedErrors.add(e)
                if (!e.triggersFallback || !config.fallbackEnabled) break
            } catch (e: Exception) {
                val wrapped = AiError.ServerError(providerId, 500, SecretRedactor.redact(e.message ?: "Vision error", listOf(config.geminiApiKey)))
                collectedErrors.add(wrapped)
                if (!config.fallbackEnabled) break
            }
        }

        if (collectedErrors.size == 1) throw collectedErrors.first()
        throw AiError.AllProvidersFailedError(collectedErrors)
    }

    private suspend fun <T> executeWithRetry(
        config: AiProviderConfig,
        block: suspend () -> T
    ): T {
        val maxAttempts = 1 + config.maxRetries.coerceIn(0, 3)
        var lastError: AiError? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                lastError = e
                if (!e.retryable || attempt >= maxAttempts) {
                    throw e
                }
                delay(250L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("Retry loop exited unexpectedly")
    }
}
