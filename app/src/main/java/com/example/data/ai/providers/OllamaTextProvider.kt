package com.example.data.ai.providers

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Ollama adapter for text and optional vision-model requests.
 * Endpoint must be explicitly configured. Never expose an unprotected instance publicly.
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
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

        // Parse literal IPv4 instead of prefix-matching hostnames such as "10.attacker.example".
        val ipv4 = normalized.split('.')
        if (ipv4.size == 4 && ipv4.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }) {
            val octets = ipv4.map { it.toInt() }
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 172 && octets[1] in 16..31)
        }

        // Only parse IPv6 literals (containing ':'), avoiding DNS lookups for hostnames.
        if (':' in normalized) {
            val address = runCatching { java.net.InetAddress.getByName(normalized) }.getOrNull() ?: return false
            val bytes = address.address
            val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || isUniqueLocalIpv6
        }
        return false
    }

    override suspend fun generate(request: AiTextRequest): AiTextResponse =
        withContext(Dispatchers.IO) {
            executePayload(buildPayload(request))
        }

    /**
     * Vision fallback requires an Ollama model that supports images (e.g. a vision model).
     * Image bytes are sent only to the explicitly configured endpoint.
     */
    suspend fun generateFromImage(bitmap: Bitmap, request: AiTextRequest): AiTextResponse =
        withContext(Dispatchers.IO) {
            val imageStream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, imageStream)) {
                throw AiProviderException(AiFailureKind.INVALID_REQUEST, "Could not encode image for Ollama.")
            }
            val encodedImage = Base64.encodeToString(imageStream.toByteArray(), Base64.NO_WRAP)
            executePayload(buildPayload(request).put("images", JSONArray().put(encodedImage)))
        }

    private fun buildPayload(request: AiTextRequest): JSONObject {
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
        return payload
    }

    private fun executePayload(payload: JSONObject): AiTextResponse = try {
        val httpRequest = Request.Builder()
            .url(endpointBaseUrl + "/api/generate")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

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

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .build()
    }
}
