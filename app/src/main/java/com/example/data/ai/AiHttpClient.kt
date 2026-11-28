package com.example.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Long = AiProviderConfig.DEFAULT_TIMEOUT_MS
)

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

interface AiHttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
    fun streamLines(request: HttpRequest): Flow<String>
}

class HttpUrlConnectionTransport(
    private val maxResponseBytes: Int = 4 * 1024 * 1024
) : AiHttpTransport {

    private fun openValidatedConnection(request: HttpRequest): HttpURLConnection {
        val parsed = try {
            URI(request.url.trim())
        } catch (e: Exception) {
            throw IOException("Malformed request URL: ${e.message}", e)
        }
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.lowercase().orEmpty()
        if ((scheme != "http" && scheme != "https") || host.isBlank()) {
            throw IOException("Unsupported or invalid URL scheme/host for AI transport.")
        }
        if (EndpointValidator.isBlockedHost(host)) {
            throw IOException("Target host '$host' is blocked by SSRF security policy.")
        }
        val timeout = request.timeoutMs.coerceIn(2_000L, 180_000L).toInt()
        return (URL(request.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = request.method
            connectTimeout = timeout.coerceAtMost(15_000)
            readTimeout = timeout
            doInput = true
            useCaches = false
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (request.body != null) {
                doOutput = true
                if (!request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val conn = openValidatedConnection(request)

        try {
            currentCoroutineContext().ensureActive()
            if (request.body != null) {
                conn.outputStream.use { os ->
                    os.write(request.body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                throw IOException("HTTP redirects (HTTP $code) are disabled for security.")
            }
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val bodyText = stream?.use { input ->
                val bytes = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(bytes)
                    if (read <= 0) break
                    total += read
                    if (total > maxResponseBytes) {
                        throw IllegalStateException("Response exceeded $maxResponseBytes byte safety limit")
                    }
                    out.write(bytes, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()

            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (k, v) ->
                if (!k.isNullOrBlank() && !v.isNullOrEmpty()) {
                    respHeaders[k.lowercase()] = v.first()
                }
            }
            HttpResponse(statusCode = code, body = bodyText, headers = respHeaders)
        } catch (e: CancellationException) {
            throw e
        } finally {
            conn.disconnect()
        }
    }

    override fun streamLines(request: HttpRequest): Flow<String> = flow {
        val conn = openValidatedConnection(request)

        try {
            currentCoroutineContext().ensureActive()
            if (request.body != null) {
                conn.outputStream.use { os ->
                    os.write(request.body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                throw IOException("HTTP redirects (HTTP $code) are disabled for security.")
            }
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpStatusException(code, errBody)
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}

class HttpStatusException(
    val statusCode: Int,
    val responseBody: String
) : Exception("HTTP $statusCode")
