package com.example.data.ai.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Text-only Ollama adapter. Endpoint must be explicitly configured by the caller.
 * Do not expose an Ollama server directly to the public internet.
 */
class OllamaTextProvider(
    override val modelId: String,
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient()
) : AiTextProvider {
    override val providerId: String = "ollama"
    private val endpointBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        require(modelId.isNotBlank()) { "Ollama model name is required." }
        val parsed = endpointBaseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Ollama endpoint must be a valid HTTP(S) URL.")
        require(parsed.username.isBlank() && parsed.password.isBlank()) {
            "Credentials must not be embedded in the Ollama endpoint URL."
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Ollama endpoint must not contain a query string or fragment."
        }
        require(parsed.scheme == "https" || isPrivateHttpHost(parsed.host)) {
            "HTTP is allowed only for localhost or private-network Ollama endpoints. Use HTTPS for remote hosts."
        }
    }

    private fun isPrivateHttpHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".localhost")) return true
        if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) return true
        if (normalized.startsWith("172.")) {
            val second = normalized.substringAfter("172.").substringBefore(".").toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    override suspend fun generate(request: AiTextRequest): AiTextResponse =
        withContext(Dispatchers.IO) {
            val prompt = buildString {
                request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
                    append("System instructions:\n").append(it).append("\n\n")
                }
                append(request.prompt)
            }
            val payload = JSONObject()
                .put("model", modelId)
                .put("prompt", prompt)
                .put("stream", false)
                .put("options", JSONObject().put("temperature", request.temperature))
            request.maxTokens?.let { payload.getJSONObject("options").put("num_predict", it) }
            if (request.responseFormat == AiResponseFormat.JSON) payload.put("format", "json")

            val url = endpointBaseUrl + "/api/generate"
            val httpRequest = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(httpRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val kind = when (response.code) {
                            401, 403 -> AiFailureKind.AUTHENTICATION
                            408 -> AiFailureKind.TIMEOUT
                            429 -> AiFailureKind.RATE_LIMITED
                            in 500..599 -> AiFailureKind.TEMPORARY_UNAVAILABLE
                            else -> AiFailureKind.INVALID_REQUEST
                        }
                        throw AiProviderException(kind, "Ollama returned HTTP ${response.code}.")
                    }
                    val text = try {
                        JSONObject(body).optString("response").trim()
                    } catch (e: Exception) {
                        throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "Invalid Ollama response.", e)
                    }
                    if (text.isBlank()) {
                        throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "Ollama returned empty text.")
                    }
                    AiTextResponse(text, providerId, modelId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: AiProviderException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw AiProviderException(AiFailureKind.TIMEOUT, "Ollama request timed out.", e)
            } catch (e: IOException) {
                throw AiProviderException(AiFailureKind.NETWORK, "Could not reach Ollama.", e)
            }
        }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()
    }
}
