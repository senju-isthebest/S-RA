package com.example

import com.example.data.ai.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class VisionFakeTransport(
    private val handler: suspend (HttpRequest) -> HttpResponse
) : AiHttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return handler(request)
    }

    override fun streamLines(request: HttpRequest): Flow<String> = flowOf()
}

private class VisionFakeGeminiEngine(
    private val onVision: suspend (AiVisionRequest, String, Long) -> String
) : CloudGeminiEngine {
    override suspend fun generateText(request: AiTextRequest, modelName: String, timeoutMs: Long): String = ""
    override fun streamText(request: AiTextRequest, modelName: String, timeoutMs: Long): Flow<String> = flowOf()
    override suspend fun generateVision(request: AiVisionRequest, modelName: String, timeoutMs: Long): String =
        onVision(request, modelName, timeoutMs)
}

class OllamaVisionAdapterTest {

    @Test
    fun `base64 codec encodes empty, padded, and binary payloads accurately`() {
        assertEquals("", Base64Codec.encode(byteArrayOf()))
        assertEquals("Zg==", Base64Codec.encode("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encode("foo".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encode("foobar".toByteArray()))
        assertEquals("/+8=", Base64Codec.encode(byteArrayOf(0xFF.toByte(), 0xEF.toByte())))
    }

    @Test
    fun visionRequestIncludesEncodedImage() = runTest {
        val sampleBytes = byteArrayOf(0x01, 0x02, 0x03, 0x41)
        val expectedB64 = Base64Codec.encode(sampleBytes)
        val transport = VisionFakeTransport {
            HttpResponse(200, """{"response":"Transcribed Heading\nF = ma"}""")
        }
        val adapter = OllamaAdapter(transport)

        val resp = adapter.generateVision(
            AiVisionRequest(prompt = "Transcribe page 4", imageBytes = sampleBytes),
            AiProviderConfig(ollamaModel = "qwen2.5:3b", ollamaVisionModel = "llava:7b")
        )

        assertEquals(AiProviderId.OLLAMA, resp.provider)
        assertEquals("llava:7b", resp.modelUsed)
        assertEquals("Transcribed Heading\nF = ma", resp.text)

        val sent = SoraJson.parseObject(transport.requests.single().body!!)
        assertEquals("llava:7b", sent.str("model"))
        val images = sent.strList("images")
        assertEquals(listOf(expectedB64), images)
    }

    @Test
    fun `vision falls back to ollamaModel when ollamaVisionModel is blank`() = runTest {
        val transport = VisionFakeTransport { HttpResponse(200, """{"response":"OCR ok"}""") }
        val adapter = OllamaAdapter(transport)

        val resp = adapter.generateVision(
            AiVisionRequest(prompt = "OCR", imageBytes = byteArrayOf(10, 20)),
            AiProviderConfig(ollamaModel = "minicpm-v:8b", ollamaVisionModel = "")
        )

        assertEquals("minicpm-v:8b", resp.modelUsed)
    }

    @Test
    fun `vision rejects empty or oversized image bytes before calling transport`() = runTest {
        val transport = VisionFakeTransport { HttpResponse(200, """{"response":"never"}""") }
        val adapter = OllamaAdapter(transport)

        try {
            adapter.generateVision(
                AiVisionRequest(prompt = "OCR", imageBytes = byteArrayOf()),
                AiProviderConfig()
            )
            fail("Expected InvalidRequestError for empty image")
        } catch (e: AiError.InvalidRequestError) {
            assertEquals(AiProviderId.OLLAMA, e.provider)
        }

        val oversized = ByteArray(AiProviderConfig.MAX_IMAGE_BYTES + 1)
        try {
            adapter.generateVision(
                AiVisionRequest(prompt = "OCR", imageBytes = oversized),
                AiProviderConfig()
            )
            fail("Expected InvalidRequestError for oversized image")
        } catch (e: AiError.InvalidRequestError) {
            assertEquals(AiProviderId.OLLAMA, e.provider)
        }

        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `vision router falls back from ollama failure to gemini vision`() = runTest {
        val ollama = OllamaAdapter(VisionFakeTransport { HttpResponse(503, "busy") })
        val gemini = GeminiAdapter(
            VisionFakeTransport { HttpResponse(500, "") },
            VisionFakeGeminiEngine { req, _, _ -> "Gemini OCR (${req.imageBytes.size} bytes)" }
        )
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        val resp = router.generateVision(
            AiVisionRequest(prompt = "Read equations", imageBytes = byteArrayOf(1, 2, 3, 4)),
            AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true, maxRetries = 0)
        )

        assertEquals(AiProviderId.GEMINI, resp.provider)
        assertTrue(resp.fallbackUsed)
        assertEquals("Gemini OCR (4 bytes)", resp.text)
    }
}
