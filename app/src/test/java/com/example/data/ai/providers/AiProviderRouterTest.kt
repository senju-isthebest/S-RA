package com.example.data.ai.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AiProviderRouterTest {
    private class FakeProvider(
        override val providerId: String,
        override val modelId: String,
        private val outcome: suspend (AiTextRequest) -> AiTextResponse
    ) : AiTextProvider {
        override suspend fun generate(request: AiTextRequest) = outcome(request)
    }

    @Test
    fun fallsBackOnTransientFailure() = runBlocking {
        val primary = FakeProvider("primary", "p") {
            throw AiProviderException(AiFailureKind.RATE_LIMITED, "quota")
        }
        val backup = FakeProvider("backup", "b") {
            AiTextResponse("ok", "backup", "b")
        }
        val result = AiProviderRouter(listOf(primary, backup)).generate(AiTextRequest("test"))
        assertEquals("backup", result.providerId)
        assertEquals("ok", result.text)
    }

    @Test
    fun fallsBackOnTimeout() = runBlocking {
        val primary = FakeProvider("primary", "p") {
            throw AiProviderException(AiFailureKind.TIMEOUT, "timed out")
        }
        val backup = FakeProvider("backup", "b") {
            AiTextResponse("recovered", "backup", "b")
        }
        val result = AiProviderRouter(listOf(primary, backup)).generate(AiTextRequest("test"))
        assertEquals("recovered", result.text)
        assertEquals("backup", result.providerId)
    }

    @Test
    fun doesNotFallbackOnInvalidRequest() = runBlocking {
        var backupCalled = false
        val primary = FakeProvider("primary", "p") {
            throw AiProviderException(AiFailureKind.INVALID_REQUEST, "bad request")
        }
        val backup = FakeProvider("backup", "b") {
            backupCalled = true
            AiTextResponse("ok", "backup", "b")
        }
        assertThrows(AiProviderException::class.java) {
            runBlocking { AiProviderRouter(listOf(primary, backup)).generate(AiTextRequest("test")) }
        }
        assertEquals(false, backupCalled)
    }

    @Test
    fun cancellationNeverCallsBackup() = runBlocking {
        var backupCalled = false
        val primary = FakeProvider("primary", "p") {
            throw CancellationException("cancelled")
        }
        val backup = FakeProvider("backup", "b") {
            backupCalled = true
            AiTextResponse("should not happen", "backup", "b")
        }
        try {
            AiProviderRouter(listOf(primary, backup)).generate(AiTextRequest("test"))
        } catch (_: CancellationException) {
            // Expected: cancellation is propagated without retrying.
        }
        assertEquals(false, backupCalled)
    }


    @Test
    fun ollamaAdapterPostsRequestAndParsesResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"response":"Adapter works"}"""))
        server.start()
        try {
            val provider = OllamaTextProvider("test-model", server.url("/").toString())
            val response = provider.generate(AiTextRequest(prompt = "hello", temperature = 0.2f, maxTokens = 42))
            val request = server.takeRequest()
            val payload = org.json.JSONObject(request.body.readUtf8())
            assertEquals("/api/generate", request.path)
            assertEquals("test-model", payload.getString("model"))
            assertEquals("hello", payload.getString("prompt"))
            assertEquals(false, payload.getBoolean("stream"))
            assertEquals(42, payload.getJSONObject("options").getInt("num_predict"))
            assertEquals("Adapter works", response.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ollamaRateLimitIsTypedForFallback() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limit"))
        server.start()
        try {
            val provider = OllamaTextProvider("test-model", server.url("/").toString())
            val error = try {
                provider.generate(AiTextRequest("test"))
                null
            } catch (e: AiProviderException) {
                e
            }
            assertEquals(AiFailureKind.RATE_LIMITED, error?.kind)
            assertTrue(error?.shouldFallback == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsPublicHttpOllamaEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            OllamaTextProvider(modelId = "model", baseUrl = "http://example.com:11434")
        }
    }

    @Test
    fun acceptsHttpsAndPrivateLanOllamaEndpoints() {
        OllamaTextProvider(modelId = "model", baseUrl = "https://ai.example.com")
        OllamaTextProvider(modelId = "model", baseUrl = "http://192.168.1.10:11434")
    }

    @Test
    fun rejectsCredentialsEmbeddedInEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            OllamaTextProvider(modelId = "model", baseUrl = "https://user:password@ai.example.com")
        }
    }

    @Test
    fun returnsUnavailableWhenAllTransientProvidersFail() = runBlocking {
        val providers = listOf("one", "two").map {
            FakeProvider(it, "model") {
                throw AiProviderException(AiFailureKind.TEMPORARY_UNAVAILABLE, "down")
            }
        }
        val error = try {
            AiProviderRouter(providers).generate(AiTextRequest("test"))
            null
        } catch (e: AiProviderException) {
            e
        }
        assertEquals(AiFailureKind.TEMPORARY_UNAVAILABLE, error?.kind)
    }
}
