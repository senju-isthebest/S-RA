package com.example.data.ai

import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException

interface CloudGeminiEngine {
    suspend fun generateText(request: AiTextRequest, modelName: String, timeoutMs: Long): String
    fun streamText(request: AiTextRequest, modelName: String, timeoutMs: Long): Flow<String>
    suspend fun generateVision(request: AiVisionRequest, modelName: String, timeoutMs: Long): String
}

class FirebaseAiLogicEngine : CloudGeminiEngine {

    override suspend fun generateText(request: AiTextRequest, modelName: String, timeoutMs: Long): String {
        val model = Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                if (request.jsonMode) responseMimeType = "application/json"
                if (request.temperature != null) temperature = request.temperature.toFloat()
                if (request.maxTokens != null) maxOutputTokens = request.maxTokens
            }
        )
        val combinedPrompt = if (request.systemPrompt.isNotBlank()) {
            "${request.systemPrompt}\n\n${request.prompt}"
        } else {
            request.prompt
        }
        val response = withTimeout(timeoutMs) {
            model.generateContent(combinedPrompt)
        }
        return response.text?.trim().orEmpty()
    }

    override fun streamText(request: AiTextRequest, modelName: String, timeoutMs: Long): Flow<String> = flow {
        val model = Firebase.ai.generativeModel(modelName = modelName)
        val combinedPrompt = if (request.systemPrompt.isNotBlank()) {
            "${request.systemPrompt}\n\n${request.prompt}"
        } else {
            request.prompt
        }
        val acc = StringBuilder()
        withTimeout(timeoutMs) {
            model.generateContentStream(combinedPrompt).collect { chunk ->
                val piece = chunk.text.orEmpty()
                if (piece.isNotEmpty()) {
                    acc.append(piece)
                    emit(acc.toString())
                }
            }
        }
    }

    override suspend fun generateVision(request: AiVisionRequest, modelName: String, timeoutMs: Long): String {
        val bmp = BitmapFactory.decodeByteArray(request.imageBytes, 0, request.imageBytes.size)
            ?: throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Could not decode image bytes.")
        try {
            val model = Firebase.ai.generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    if (request.jsonMode) responseMimeType = "application/json"
                }
            )
            val response = withTimeout(timeoutMs) {
                model.generateContent(
                    content {
                        image(bmp)
                        text(request.prompt)
                    }
                )
            }
            return response.text?.trim().orEmpty()
        } finally {
            bmp.recycle()
        }
    }
}

class GeminiAdapter(
    private val transport: AiHttpTransport,
    private val firebaseEngine: CloudGeminiEngine = FirebaseAiLogicEngine(),
    private val restBaseUrl: String = "https://generativelanguage.googleapis.com"
) : AiProviderAdapter {

    override val providerId: AiProviderId = AiProviderId.GEMINI

    override val capabilities: Set<AiCapability> = setOf(
        AiCapability.TEXT_GENERATION,
        AiCapability.STREAMING,
        AiCapability.JSON_MODE,
        AiCapability.VISION
    )

    override suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse {
        val model = config.geminiModel.trim().ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Prompt cannot be empty.")
        }
        val safeTimeout = config.requestTimeoutMs.coerceIn(5_000L, 180_000L)
        val start = System.currentTimeMillis()
        val apiKey = config.geminiApiKey.trim()

        val rawText = if (apiKey.isNotBlank()) {
            executeRestGenerate(
                apiKey = apiKey,
                model = model,
                request = request,
                imageBytes = null,
                mimeType = null,
                timeoutMs = safeTimeout
            )
        } else {
            try {
                firebaseEngine.generateText(request, model, safeTimeout)
            } catch (e: AiError) {
                throw e
            } catch (e: TimeoutCancellationException) {
                throw AiError.TimeoutError(AiProviderId.GEMINI, "Gemini cloud request timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyFirebaseException(e, apiKey)
            }
        }

        val cleaned = validateAndFinalizeOutput(rawText, request.jsonMode)
        return AiResponse(
            text = cleaned,
            provider = AiProviderId.GEMINI,
            modelUsed = model,
            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        )
    }

    override fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String> = flow {
        val model = config.geminiModel.trim().ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Prompt cannot be empty.")
        }
        val safeTimeout = config.requestTimeoutMs.coerceIn(5_000L, 180_000L)
        val apiKey = config.geminiApiKey.trim()

        if (apiKey.isNotBlank()) {
            val single = generateText(request, config)
            emit(single.text)
            return@flow
        }

        var emittedAny = false
        try {
            firebaseEngine.streamText(request, model, safeTimeout).collect { chunk ->
                if (chunk.isNotEmpty()) {
                    emittedAny = true
                    emit(chunk)
                }
            }
        } catch (e: AiError) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw AiError.TimeoutError(AiProviderId.GEMINI, "Gemini stream timed out.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifyFirebaseException(e, apiKey)
        }

        if (!emittedAny) {
            throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini returned an empty stream.")
        }
    }

    override suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse {
        val model = config.geminiModel.trim().ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL }
        if (request.prompt.isBlank()) {
            throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Vision prompt cannot be empty.")
        }
        if (request.imageBytes.isEmpty()) {
            throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Image payload cannot be empty.")
        }
        if (request.imageBytes.size > AiProviderConfig.MAX_IMAGE_BYTES) {
            throw AiError.InvalidRequestError(AiProviderId.GEMINI, "Image exceeds 10 MB safety limit.")
        }
        val safeTimeout = config.requestTimeoutMs.coerceIn(5_000L, 180_000L)
        val start = System.currentTimeMillis()
        val apiKey = config.geminiApiKey.trim()

        val rawText = if (apiKey.isNotBlank()) {
            executeRestGenerate(
                apiKey = apiKey,
                model = model,
                request = AiTextRequest(prompt = request.prompt, jsonMode = request.jsonMode),
                imageBytes = request.imageBytes,
                mimeType = request.mimeType,
                timeoutMs = safeTimeout
            )
        } else {
            try {
                firebaseEngine.generateVision(request, model, safeTimeout)
            } catch (e: AiError) {
                throw e
            } catch (e: TimeoutCancellationException) {
                throw AiError.TimeoutError(AiProviderId.GEMINI, "Gemini vision request timed out.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classifyFirebaseException(e, apiKey)
            }
        }

        val cleaned = validateAndFinalizeOutput(rawText, request.jsonMode)
        return AiResponse(
            text = cleaned,
            provider = AiProviderId.GEMINI,
            modelUsed = model,
            latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
        )
    }

    override suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            val resp = generateText(
                AiTextRequest(prompt = "Reply with OK.", maxTokens = 8),
                config
            )
            ConnectionTestResult(
                success = resp.text.isNotBlank(),
                provider = AiProviderId.GEMINI,
                message = "Gemini connected (${resp.modelUsed}).",
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionTestResult(
                success = false,
                provider = AiProviderId.GEMINI,
                message = SecretRedactor.redact(e.message ?: "Gemini connection failed", listOf(config.geminiApiKey)),
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            )
        }
    }

    private suspend fun executeRestGenerate(
        apiKey: String,
        model: String,
        request: AiTextRequest,
        imageBytes: ByteArray?,
        mimeType: String?,
        timeoutMs: Long
    ): String {
        val parts = mutableListOf<JsonVal>()
        if (imageBytes != null && mimeType != null) {
            parts.add(
                SoraJson.obj(
                    "inlineData" to SoraJson.obj(
                        "mimeType" to mimeType,
                        "data" to Base64Codec.encode(imageBytes)
                    )
                )
            )
        }
        parts.add(SoraJson.obj("text" to request.prompt))

        val bodyPairs = mutableListOf<Pair<String, Any?>>(
            "contents" to SoraJson.arr(
                listOf(SoraJson.obj("role" to "user", "parts" to JsonArr(parts)))
            )
        )
        if (request.systemPrompt.isNotBlank()) {
            bodyPairs.add(
                "systemInstruction" to SoraJson.obj(
                    "parts" to SoraJson.arr(listOf(SoraJson.obj("text" to request.systemPrompt)))
                )
            )
        }
        val genCfgPairs = mutableListOf<Pair<String, Any?>>()
        if (request.jsonMode) genCfgPairs.add("responseMimeType" to "application/json")
        if (request.temperature != null) genCfgPairs.add("temperature" to request.temperature)
        if (request.maxTokens != null) genCfgPairs.add("maxOutputTokens" to request.maxTokens)
        if (genCfgPairs.isNotEmpty()) {
            bodyPairs.add("generationConfig" to SoraJson.obj(*genCfgPairs.toTypedArray()))
        }

        val url = "${restBaseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"
        val resp = try {
            withTimeout(timeoutMs) {
                transport.execute(
                    HttpRequest(
                        method = "POST",
                        url = url,
                        headers = mapOf(
                            "Content-Type" to "application/json; charset=utf-8",
                            "Accept" to "application/json",
                            "x-goog-api-key" to apiKey
                        ),
                        body = SoraJson.obj(*bodyPairs.toTypedArray()).toJsonString(),
                        timeoutMs = timeoutMs
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw AiError.TimeoutError(AiProviderId.GEMINI, "Gemini REST request timed out.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiError.TimeoutError(AiProviderId.GEMINI, "Gemini socket timed out.", e)
        } catch (e: IOException) {
            throw AiError.ConnectionError(
                AiProviderId.GEMINI,
                SecretRedactor.redact("Gemini connection error: ${e.message}", listOf(apiKey)),
                e
            )
        }

        if (resp.statusCode !in 200..299) {
            throw classifyHttpFailure(resp.statusCode, resp.body, resp.headers, apiKey, model)
        }
        if (resp.body.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini returned an empty response body.")
        }

        val root = try {
            SoraJson.parseObject(resp.body)
        } catch (e: SoraJsonException) {
            throw AiError.MalformedResponseError(AiProviderId.GEMINI, "Malformed Gemini JSON response: ${e.message}", e)
        }

        val candidates = root.arr("candidates")
        val first = candidates?.items?.firstOrNull() as? JsonObj
            ?: throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini returned no response candidates.")
        val contentObj = first.obj("content")
            ?: throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini candidate missing content.")
        val text = contentObj.arr("parts")?.items
            ?.mapNotNull { (it as? JsonObj)?.str("text")?.takeIf { s -> s.isNotBlank() } }
            ?.joinToString("\n")
            .orEmpty()
            .trim()

        if (text.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini returned empty candidate text.")
        }
        return text
    }

    private fun validateAndFinalizeOutput(raw: String, jsonMode: Boolean): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw AiError.EmptyResponseError(AiProviderId.GEMINI, "Gemini returned an empty response.")
        }
        if (jsonMode) {
            val extracted = SoraJson.extractJsonPayload(trimmed)
            try {
                SoraJson.parse(extracted)
            } catch (e: SoraJsonException) {
                throw AiError.MalformedResponseError(AiProviderId.GEMINI, "Gemini JSON output was malformed: ${e.message}", e)
            }
            return extracted
        }
        return trimmed
    }

    private fun classifyHttpFailure(
        statusCode: Int,
        body: String,
        headers: Map<String, String>,
        apiKey: String,
        model: String
    ): AiError {
        val safeDetail = SecretRedactor.redact(body.take(300), listOf(apiKey))
        return when (statusCode) {
            401, 403 -> AiError.AuthenticationError(AiProviderId.GEMINI, "Gemini API key rejected or lacks permission (HTTP $statusCode).")
            404 -> AiError.ModelNotFoundError(AiProviderId.GEMINI, model, "Gemini model '$model' not found (HTTP 404).")
            400, 422 -> AiError.InvalidRequestError(AiProviderId.GEMINI, "Gemini rejected request (HTTP $statusCode): $safeDetail")
            429 -> AiError.RateLimitError(
                AiProviderId.GEMINI,
                headers["retry-after"]?.toIntOrNull(),
                "Gemini rate limit exceeded (HTTP 429)."
            )
            in 500..599 -> AiError.ServerError(AiProviderId.GEMINI, statusCode, "Gemini server error (HTTP $statusCode).")
            else -> AiError.ServerError(AiProviderId.GEMINI, statusCode, "Gemini HTTP $statusCode: $safeDetail")
        }
    }

    private fun classifyFirebaseException(e: Exception, apiKey: String): AiError {
        val msg = SecretRedactor.redact(e.message ?: e::class.simpleName.orEmpty(), listOf(apiKey))
        val lower = msg.lowercase()
        return when {
            lower.contains("429") || lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("rate") ->
                AiError.RateLimitError(AiProviderId.GEMINI, message = msg)
            lower.contains("401") || lower.contains("403") || lower.contains("unauthenticated") || lower.contains("permission") || lower.contains("app check") ->
                AiError.AuthenticationError(AiProviderId.GEMINI, msg)
            lower.contains("timeout") ->
                AiError.TimeoutError(AiProviderId.GEMINI, msg, e)
            lower.contains("network") || lower.contains("unable to resolve") || lower.contains("connect") ->
                AiError.ConnectionError(AiProviderId.GEMINI, msg, e)
            else -> AiError.ServerError(AiProviderId.GEMINI, 500, msg)
        }
    }
}
