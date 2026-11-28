package com.example.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException

interface AiProviderAdapter {
    val providerId: AiProviderId
    val capabilities: Set<AiCapability>

    suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse
    fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String>
    suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse
    suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult
}

class OllamaAdapter(
    private val transport: AiHttpTransport
) : AiProviderAdapter {

    override val providerId: AiProviderId = AiProviderId.OLLAMA

    override val capabilities: Set<AiCapability> = setOf(
        AiCapability.TEXT_GENERATION,
        AiCapability.STREAMING,
        AiCapability.JSON_MODE,
        AiCapability.VISION,
        AiCapability.MODEL_DISCOVERY
    )

    override suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse {
        val baseUrl = validateEndpoint(config)
        val model = config.ollamaModel.trim()
        if (model.isBlank()) {
            throw AiError.ConfigurationError(AiProviderId.OLLAMA, "Ollama model name is not configured.")
        }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Prompt cannot be empty.")
        }
        if (request.prompt.length > AiProviderConfig.MAX_PROMPT_CHARS) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Prompt exceeds maximum character limit.")
        }

        val payload = buildGeneratePayload(
            model = model,
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            stream = false,
            jsonMode = request.jsonMode,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            encodedImages = emptyList()
        )

        val start = System.currentTimeMillis()
        val resp = executePost("$baseUrl/api/generate", payload, config.requestTimeoutMs)
        val text = parseGenerateResponseText(resp, expectJson = request.jsonMode, model = model)
        return AiResponse(
            text = text,
            provider = AiProviderId.OLLAMA,
            modelUsed = model,
            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        )
    }

    override fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String> = flow {
        val baseUrl = validateEndpoint(config)
        val model = config.ollamaModel.trim()
        if (model.isBlank()) {
            throw AiError.ConfigurationError(AiProviderId.OLLAMA, "Ollama model name is not configured.")
        }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Prompt cannot be empty.")
        }

        val payload = buildGeneratePayload(
            model = model,
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            stream = true,
            jsonMode = request.jsonMode,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            encodedImages = emptyList()
        )

        val accumulated = StringBuilder()
        try {
            withTimeout(config.requestTimeoutMs.coerceIn(5_000L, 180_000L)) {
                transport.streamLines(
                    HttpRequest(
                        method = "POST",
                        url = "$baseUrl/api/generate",
                        headers = mapOf("Accept" to "application/x-ndjson"),
                        body = payload.toJsonString(),
                        timeoutMs = config.requestTimeoutMs
                    )
                ).collect { line ->
                    val obj = try {
                        SoraJson.parseObject(line)
                    } catch (e: SoraJsonException) {
                        throw AiError.MalformedResponseError(AiProviderId.OLLAMA, "Malformed Ollama stream chunk: ${e.message}", e)
                    }
                    val errText = obj.str("error")
                    if (errText.isNotBlank()) {
                        throw classifyErrorMessage(errText, model)
                    }
                    val piece = obj.str("response")
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
            }
        } catch (e: AiError) {
            throw e
        } catch (e: HttpStatusException) {
            throw classifyHttpFailure(e.statusCode, e.responseBody, emptyMap(), model)
        } catch (e: TimeoutCancellationException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Ollama stream timed out.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Ollama stream read timed out.", e)
        } catch (e: IOException) {
            throw AiError.ConnectionError(AiProviderId.OLLAMA, "Cannot reach Ollama at $baseUrl: ${e.message}", e)
        }

        if (accumulated.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.OLLAMA, "Ollama returned an empty stream.")
        }
    }

    override suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse {
        val baseUrl = validateEndpoint(config)
        val model = config.ollamaVisionModel.trim().ifBlank { config.ollamaModel.trim() }
        if (model.isBlank()) {
            throw AiError.ConfigurationError(AiProviderId.OLLAMA, "Ollama vision model is not configured.")
        }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Vision prompt cannot be empty.")
        }
        if (request.imageBytes.isEmpty()) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Image payload cannot be empty.")
        }
        if (request.imageBytes.size > AiProviderConfig.MAX_IMAGE_BYTES) {
            throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Image exceeds 10 MB safety limit.")
        }

        val encodedImage = Base64Codec.encode(request.imageBytes)
        val payload = buildGeneratePayload(
            model = model,
            prompt = request.prompt,
            systemPrompt = "",
            stream = false,
            jsonMode = request.jsonMode,
            temperature = null,
            maxTokens = null,
            encodedImages = listOf(encodedImage)
        )

        val start = System.currentTimeMillis()
        val resp = executePost("$baseUrl/api/generate", payload, config.requestTimeoutMs)
        val text = parseGenerateResponseText(resp, expectJson = request.jsonMode, model = model)
        return AiResponse(
            text = text,
            provider = AiProviderId.OLLAMA,
            modelUsed = model,
            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        )
    }

    suspend fun discoverModels(config: AiProviderConfig): List<OllamaModelInfo> {
        val baseUrl = validateEndpoint(config)
        val resp = try {
            withTimeout(config.requestTimeoutMs.coerceIn(5_000L, 60_000L)) {
                transport.execute(
                    HttpRequest(
                        method = "GET",
                        url = "$baseUrl/api/tags",
                        headers = mapOf("Accept" to "application/json"),
                        timeoutMs = config.requestTimeoutMs
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Timed out discovering Ollama models.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Timed out connecting to Ollama.", e)
        } catch (e: IOException) {
            throw AiError.ConnectionError(AiProviderId.OLLAMA, "Unable to connect to Ollama at $baseUrl: ${e.message}", e)
        }

        if (resp.statusCode !in 200..299) {
            throw classifyHttpFailure(resp.statusCode, resp.body, resp.headers, config.ollamaModel)
        }

        val root = try {
            SoraJson.parseObject(resp.body)
        } catch (e: SoraJsonException) {
            throw AiError.MalformedResponseError(AiProviderId.OLLAMA, "Malformed /api/tags response: ${e.message}", e)
        }
        val modelsArr = root.arr("models") ?: return emptyList()
        return modelsArr.items.mapNotNull { item ->
            val obj = item as? JsonObj ?: return@mapNotNull null
            val name = obj.str("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val lower = name.lowercase()
            val visionHint = lower.contains("vision") ||
                lower.contains("llava") ||
                lower.contains("vl") ||
                lower.contains("moondream") ||
                lower.contains("bakllava") ||
                lower.contains("minicpm-v")
            OllamaModelInfo(
                name = name,
                sizeBytes = obj.longVal("size", 0L),
                modifiedAt = obj.str("modified_at"),
                supportsVisionHint = visionHint
            )
        }
    }

    override suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val models = discoverModels(config)
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            val targetModel = config.ollamaModel.trim()
            val hasModel = models.any { it.name == targetModel || it.name.substringBefore(":") == targetModel }
            val msg = when {
                models.isEmpty() -> "Connected to Ollama, but no models are installed. Run: ollama pull ${targetModel.ifBlank { AiProviderConfig.DEFAULT_OLLAMA_MODEL }}"
                hasModel -> "Connected (${models.size} model(s) ready, '${targetModel}' installed)."
                else -> "Connected (${models.size} model(s) found), but '${targetModel}' is not pulled yet."
            }
            ConnectionTestResult(
                success = true,
                provider = AiProviderId.OLLAMA,
                message = msg,
                discoveredModels = models,
                latencyMs = latency
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTestResult(
                success = false,
                provider = AiProviderId.OLLAMA,
                message = SecretRedactor.redact(e.message ?: "Connection failed"),
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            )
        }
    }

    private fun validateEndpoint(config: AiProviderConfig): String {
        val validation = EndpointValidator.validateOllamaEndpoint(config.ollamaEndpoint)
        if (!validation.isValid) {
            throw AiError.ConfigurationError(AiProviderId.OLLAMA, validation.errorMessage)
        }
        return validation.normalizedUrl
    }

    private fun buildGeneratePayload(
        model: String,
        prompt: String,
        systemPrompt: String,
        stream: Boolean,
        jsonMode: Boolean,
        temperature: Double?,
        maxTokens: Int?,
        encodedImages: List<String>
    ): JsonObj {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "model" to model,
            "prompt" to prompt,
            "stream" to stream
        )
        if (systemPrompt.isNotBlank()) {
            pairs.add("system" to systemPrompt)
        }
        if (jsonMode) {
            pairs.add("format" to "json")
        }
        if (encodedImages.isNotEmpty()) {
            pairs.add("images" to encodedImages)
        }
        val optPairs = mutableListOf<Pair<String, Any?>>()
        if (temperature != null) optPairs.add("temperature" to temperature)
        if (maxTokens != null) optPairs.add("num_predict" to maxTokens)
        if (optPairs.isNotEmpty()) {
            pairs.add("options" to SoraJson.obj(*optPairs.toTypedArray()))
        }
        return SoraJson.obj(*pairs.toTypedArray())
    }

    private suspend fun executePost(url: String, payload: JsonObj, timeoutMs: Long): HttpResponse {
        val safeTimeout = timeoutMs.coerceIn(5_000L, 180_000L)
        return try {
            withTimeout(safeTimeout) {
                transport.execute(
                    HttpRequest(
                        method = "POST",
                        url = url,
                        headers = mapOf(
                            "Content-Type" to "application/json; charset=utf-8",
                            "Accept" to "application/json"
                        ),
                        body = payload.toJsonString(),
                        timeoutMs = safeTimeout
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Ollama request timed out after ${safeTimeout / 1000}s.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiError.TimeoutError(AiProviderId.OLLAMA, "Ollama read timed out.", e)
        } catch (e: IOException) {
            throw AiError.ConnectionError(AiProviderId.OLLAMA, "Could not connect to Ollama: ${e.message}", e)
        }
    }

    private fun parseGenerateResponseText(resp: HttpResponse, expectJson: Boolean, model: String): String {
        if (resp.statusCode !in 200..299) {
            throw classifyHttpFailure(resp.statusCode, resp.body, resp.headers, model)
        }
        if (resp.body.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.OLLAMA, "Ollama returned an empty HTTP body.")
        }
        val root = try {
            SoraJson.parseObject(resp.body)
        } catch (e: SoraJsonException) {
            throw AiError.MalformedResponseError(AiProviderId.OLLAMA, "Ollama returned malformed JSON: ${e.message}", e)
        }

        val errMsg = root.str("error")
        if (errMsg.isNotBlank()) {
            throw classifyErrorMessage(errMsg, model)
        }

        val text = root.str("response").ifBlank {
            root.obj("message")?.str("content").orEmpty()
        }.trim()

        if (text.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.OLLAMA, "Ollama returned an empty response text.")
        }

        if (expectJson) {
            val extracted = SoraJson.extractJsonPayload(text)
            try {
                SoraJson.parse(extracted)
            } catch (e: SoraJsonException) {
                throw AiError.MalformedResponseError(
                    AiProviderId.OLLAMA,
                    "Ollama JSON mode did not produce valid JSON: ${e.message}",
                    e
                )
            }
            return extracted
        }
        return text
    }

    private fun classifyHttpFailure(
        statusCode: Int,
        body: String,
        headers: Map<String, String>,
        model: String
    ): AiError {
        val parsedErr = try {
            SoraJson.parseObject(body).str("error")
        } catch (_: Exception) {
            ""
        }
        val detail = SecretRedactor.redact(parsedErr.ifBlank { body.take(300) })

        return when (statusCode) {
            401, 403 -> AiError.AuthenticationError(AiProviderId.OLLAMA, "Ollama authorization failed (HTTP $statusCode).")
            404 -> {
                if (detail.contains("model", ignoreCase = true) || detail.contains("not found", ignoreCase = true)) {
                    AiError.ModelNotFoundError(
                        AiProviderId.OLLAMA,
                        model,
                        "Ollama model '$model' not found. Pull it first: ollama pull $model"
                    )
                } else {
                    AiError.ConfigurationError(AiProviderId.OLLAMA, "Ollama endpoint path not found (HTTP 404). Verify URL.")
                }
            }
            400, 422 -> AiError.InvalidRequestError(AiProviderId.OLLAMA, "Ollama rejected request (HTTP $statusCode): $detail")
            429 -> {
                val retryAfter = headers["retry-after"]?.toIntOrNull()
                AiError.RateLimitError(AiProviderId.OLLAMA, retryAfter, "Ollama rate limit reached (HTTP 429). $detail")
            }
            in 500..599 -> AiError.ServerError(AiProviderId.OLLAMA, statusCode, "Ollama server error (HTTP $statusCode): $detail")
            else -> AiError.ServerError(AiProviderId.OLLAMA, statusCode, "Ollama HTTP $statusCode: $detail")
        }
    }

    private fun classifyErrorMessage(errMsg: String, model: String): AiError {
        val lower = errMsg.lowercase()
        return when {
            lower.contains("not found") && lower.contains("model") ->
                AiError.ModelNotFoundError(AiProviderId.OLLAMA, model, "Ollama model '$model' not found: $errMsg")
            lower.contains("rate") || lower.contains("too many") ->
                AiError.RateLimitError(AiProviderId.OLLAMA, message = errMsg)
            else -> AiError.ServerError(AiProviderId.OLLAMA, 500, SecretRedactor.redact(errMsg))
        }
    }
}
