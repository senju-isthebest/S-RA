package com.example.data.ai.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
