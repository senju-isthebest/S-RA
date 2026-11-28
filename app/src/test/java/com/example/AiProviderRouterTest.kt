package com.example

import com.example.core.AppLanguage
import com.example.data.ai.*
import com.example.data.model.BookPageContent
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

private class FakeTransport(
    private val executeHandler: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, "{}") },
    private val streamHandler: (HttpRequest) -> Flow<String> = { flowOf() }
) : AiHttpTransport {
    val executedRequests = mutableListOf<HttpRequest>()
    val streamedRequests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        executedRequests.add(request)
        return executeHandler(request)
    }

    override fun streamLines(request: HttpRequest): Flow<String> {
        streamedRequests.add(request)
        return streamHandler(request)
    }
}

private class FakeGeminiEngine(
    private val textHandler: suspend (AiTextRequest, String, Long) -> String = { _, _, _ -> "Gemini SDK response" },
    private val streamHandler: (AiTextRequest, String, Long) -> Flow<String> = { _, _, _ -> flowOf("Gemini", "Gemini chunk") },
    private val visionHandler: suspend (AiVisionRequest, String, Long) -> String = { _, _, _ -> "Gemini vision response" }
) : CloudGeminiEngine {
    override suspend fun generateText(request: AiTextRequest, modelName: String, timeoutMs: Long): String =
        textHandler(request, modelName, timeoutMs)

    override fun streamText(request: AiTextRequest, modelName: String, timeoutMs: Long): Flow<String> =
        streamHandler(request, modelName, timeoutMs)

    override suspend fun generateVision(request: AiVisionRequest, modelName: String, timeoutMs: Long): String =
        visionHandler(request, modelName, timeoutMs)
}

private class StubAdapter(
    override val providerId: AiProviderId,
    override val capabilities: Set<AiCapability> = AiCapability.entries.toSet(),
    private val onGenerateText: suspend (AiTextRequest, AiProviderConfig) -> AiResponse = { _, cfg ->
        AiResponse("ok-${providerId.name}", providerId, cfg.ollamaModel)
    },
    private val onStreamText: (AiTextRequest, AiProviderConfig) -> Flow<String> = { _, _ ->
        flowOf("chunk-${providerId.name}")
    },
    private val onVision: suspend (AiVisionRequest, AiProviderConfig) -> AiResponse = { _, _ ->
        AiResponse("vision-${providerId.name}", providerId, "vision-model")
    }
) : AiProviderAdapter {
    var textCalls = 0
    var visionCalls = 0

    override suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse {
        textCalls++
        return onGenerateText(request, config)
    }

    override fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String> =
        onStreamText(request, config)

    override suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse {
        visionCalls++
        return onVision(request, config)
    }

    override suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult =
        ConnectionTestResult(true, providerId, "ok")
}

class AiProviderRouterTest {

    // ---- Endpoint validation & secret hygiene ----

    @Test
    fun `endpoint validator accepts localhost, emulator bridge, private LAN, and remote https`() {
        val validUrls = listOf(
            "http://localhost:11434/",
            "http://127.0.0.1:11434",
            "http://10.0.2.2:11434",
            "http://192.168.1.42:11434",
            "http://172.16.5.10:11434",
            "https://ollama.myuniversity.edu"
        )
        validUrls.forEach { url ->
            val res = EndpointValidator.validateOllamaEndpoint(url)
            assertTrue("Expected valid URL: $url -> ${res.errorMessage}", res.isValid)
        }
        assertEquals(
            "http://localhost:11434",
            EndpointValidator.validateOllamaEndpoint("  http://LOCALHOST:11434/  ").normalizedUrl
        )
    }

    @Test
    fun `endpoint validator rejects remote cleartext http, credentials, queries, and invalid hosts`() {
        val invalidUrls = listOf(
            "",
            "   ",
            "http://example.com:11434",
            "http://user:pass@localhost:11434",
            "http://localhost:11434?token=abc",
            "http://localhost:11434#frag",
            "ftp://localhost:11434",
            "http://localhost:99999",
            "http://local host:11434"
        )
        invalidUrls.forEach { url ->
            val res = EndpointValidator.validateOllamaEndpoint(url)
            assertFalse("Expected invalid URL: $url", res.isValid)
            assertTrue(res.errorMessage.isNotBlank())
        }
    }

    @Test
    fun `secret redactor strips api keys, headers, bearer tokens, and explicit secrets`() {
        val customSecret = "super-secret-token-9988"
        val raw = "Failed calling https://host?key=abc123 with x-goog-api-key: xyz987, Bearer tok_123, AIzaSyD1234567890abcdef and $customSecret"
        val cleaned = SecretRedactor.redact(raw, listOf(customSecret))

        assertFalse(cleaned.contains("abc123"))
        assertFalse(cleaned.contains("xyz987"))
        assertFalse(cleaned.contains("tok_123"))
        assertFalse(cleaned.contains("AIzaSyD1234567890abcdef"))
        assertFalse(cleaned.contains(customSecret))
        assertTrue(cleaned.contains("[REDACTED"))
    }

    // ---- OllamaAdapter unit tests ----

    @Test
    fun ollamaAdapterPostsRequestAndParsesResponse() = runTest {
        val transport = FakeTransport(
            executeHandler = {
                HttpResponse(
                    statusCode = 200,
                    body = """{"model":"qwen2.5:3b","response":"Photosynthesis converts light energy.","done":true}"""
                )
            }
        )
        val adapter = OllamaAdapter(transport)
        val cfg = AiProviderConfig(
            ollamaEndpoint = "http://localhost:11434/",
            ollamaModel = "qwen2.5:3b"
        )

        val response = adapter.generateText(
            AiTextRequest(prompt = "Explain photosynthesis", systemPrompt = "Be concise"),
            cfg
        )

        assertEquals(AiProviderId.OLLAMA, response.provider)
        assertEquals("qwen2.5:3b", response.modelUsed)
        assertEquals("Photosynthesis converts light energy.", response.text)
        assertEquals(1, transport.executedRequests.size)
        val req = transport.executedRequests.single()
        assertEquals("POST", req.method)
        assertEquals("http://localhost:11434/api/generate", req.url)
        val sentBody = SoraJson.parseObject(req.body!!)
        assertEquals("qwen2.5:3b", sentBody.str("model"))
        assertEquals(false, sentBody.boolVal("stream"))
        assertEquals("Be concise", sentBody.str("system"))
    }

    @Test
    fun ollamaJsonModeRequestsJsonOutput() = runTest {
        val transport = FakeTransport(
            executeHandler = {
                HttpResponse(
                    statusCode = 200,
                    body = """{"response":"```json\n{\"title\":\"Physics 101\",\"chapters\":[]}\n```"}"""
                )
            }
        )
        val adapter = OllamaAdapter(transport)
        val resp = adapter.generateText(
            AiTextRequest(prompt = "Return structure", jsonMode = true),
            AiProviderConfig()
        )

        val sent = SoraJson.parseObject(transport.executedRequests.single().body!!)
        assertEquals("json", sent.str("format"))
        val parsed = SoraJson.parseObject(resp.text)
        assertEquals("Physics 101", parsed.str("title"))
    }

    @Test
    fun ollamaRejectsMalformedResponseJson() = runTest {
        val transport = FakeTransport(
            executeHandler = { HttpResponse(statusCode = 200, body = "{not-valid-json") }
        )
        val adapter = OllamaAdapter(transport)

        val err = try {
            adapter.generateText(AiTextRequest(prompt = "Hi"), AiProviderConfig())
            fail("Expected MalformedResponseError")
            null
        } catch (e: AiError) {
            e
        }

        assertTrue(err is AiError.MalformedResponseError)
        assertEquals(AiProviderId.OLLAMA, err?.provider)
        assertTrue(err!!.triggersFallback)
    }

    @Test
    fun ollamaRateLimitIsTypedForFallback() = runTest {
        val transport = FakeTransport(
            executeHandler = {
                HttpResponse(
                    statusCode = 429,
                    body = """{"error":"too many requests"}""",
                    headers = mapOf("retry-after" to "12")
                )
            }
        )
        val adapter = OllamaAdapter(transport)

        val err = try {
            adapter.generateText(AiTextRequest(prompt = "Hi"), AiProviderConfig())
            fail("Expected RateLimitError")
            null
        } catch (e: AiError) {
            e
        }

        assertTrue(err is AiError.RateLimitError)
        val rateErr = err as AiError.RateLimitError
        assertEquals(12, rateErr.retryAfterSeconds)
        assertTrue(rateErr.triggersFallback)
        assertFalse(rateErr.retryable)
    }

    @Test
    fun ollamaClassifiesHttpFailuresWithoutLosingFallbackSemantics() = runTest {
        suspend fun callWith(code: Int, body: String): AiError {
            val adapter = OllamaAdapter(FakeTransport(executeHandler = { HttpResponse(code, body) }))
            return try {
                adapter.generateText(AiTextRequest(prompt = "Test"), AiProviderConfig(ollamaModel = "qwen2.5:3b"))
                throw AssertionError("Expected AiError for HTTP $code")
            } catch (e: AiError) {
                e
            }
        }

        val authErr = callWith(401, "Unauthorized")
        assertTrue(authErr is AiError.AuthenticationError)
        assertTrue(authErr.triggersFallback)

        val modelErr = callWith(404, """{"error":"model 'qwen2.5:3b' not found"}""")
        assertTrue(modelErr is AiError.ModelNotFoundError)
        assertTrue(modelErr.triggersFallback)

        val badReq = callWith(400, """{"error":"invalid parameter"}""")
        assertTrue(badReq is AiError.InvalidRequestError)
        assertFalse(badReq.triggersFallback)

        val serverErr = callWith(503, "Service Unavailable")
        assertTrue(serverErr is AiError.ServerError)
        assertTrue(serverErr.retryable)
        assertTrue(serverErr.triggersFallback)
    }

    @Test
    fun `ollama discovers models from api tags and identifies vision hints`() = runTest {
        val transport = FakeTransport(
            executeHandler = { req ->
                assertEquals("GET", req.method)
                assertEquals("http://localhost:11434/api/tags", req.url)
                HttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "models": [
                            {"name": "qwen2.5:3b", "size": 1900000000, "modified_at": "2026-01-01"},
                            {"name": "llava:7b", "size": 4200000000, "modified_at": "2026-01-02"}
                          ]
                        }
                    """.trimIndent()
                )
            }
        )
        val adapter = OllamaAdapter(transport)
        val models = adapter.discoverModels(AiProviderConfig())

        assertEquals(2, models.size)
        assertEquals("qwen2.5:3b", models[0].name)
        assertFalse(models[0].supportsVisionHint)
        assertEquals("llava:7b", models[1].name)
        assertTrue(models[1].supportsVisionHint)
    }

    // ---- GeminiAdapter tests ----

    @Test
    fun `gemini rest adapter uses x-goog-api-key header and redacts leaked key from failures`() = runTest {
        val secretKey = "AIzaSyFakeSecretKey1234567890"
        val transport = FakeTransport(
            executeHandler = { req ->
                assertFalse("API key must never appear in URL", req.url.contains(secretKey))
                assertEquals(secretKey, req.headers["x-goog-api-key"])
                HttpResponse(
                    statusCode = 200,
                    body = """{"candidates":[{"content":{"parts":[{"text":"Restored answer"}]}}]}"""
                )
            }
        )
        val adapter = GeminiAdapter(transport, FakeGeminiEngine())
        val resp = adapter.generateText(
            AiTextRequest(prompt = "Explain mitosis", jsonMode = false),
            AiProviderConfig(geminiApiKey = secretKey, geminiModel = "gemini-3.8-flash")
        )
        assertEquals(AiProviderId.GEMINI, resp.provider)
        assertEquals("Restored answer", resp.text)

        val failingAdapter = GeminiAdapter(
            FakeTransport(executeHandler = { throw IOException("Failure with key $secretKey") }),
            FakeGeminiEngine()
        )
        val connErr = try {
            failingAdapter.generateText(
                AiTextRequest(prompt = "Hi"),
                AiProviderConfig(geminiApiKey = secretKey)
            )
            fail("Expected ConnectionError")
            null
        } catch (e: AiError.ConnectionError) {
            e
        }
        assertFalse(connErr!!.message.orEmpty().contains(secretKey))
    }

    // ---- AiProviderRouter routing, fallback, retries, streaming, cancellation ----

    @Test
    fun `router resolves deterministic provider order across all strategies`() {
        val router = AiProviderRouter(
            mapOf(
                AiProviderId.OLLAMA to StubAdapter(AiProviderId.OLLAMA),
                AiProviderId.GEMINI to StubAdapter(AiProviderId.GEMINI)
            )
        )

        assertEquals(
            listOf(AiProviderId.GEMINI, AiProviderId.OLLAMA),
            router.resolveOrder(AiProviderConfig(strategy = AiRoutingStrategy.GEMINI_FIRST, fallbackEnabled = true))
        )
        assertEquals(
            listOf(AiProviderId.GEMINI),
            router.resolveOrder(AiProviderConfig(strategy = AiRoutingStrategy.GEMINI_FIRST, fallbackEnabled = false))
        )
        assertEquals(
            listOf(AiProviderId.OLLAMA, AiProviderId.GEMINI),
            router.resolveOrder(AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true))
        )
        assertEquals(
            listOf(AiProviderId.OLLAMA),
            router.resolveOrder(AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_ONLY, fallbackEnabled = true))
        )
        assertEquals(
            listOf(AiProviderId.GEMINI),
            router.resolveOrder(AiProviderConfig(strategy = AiRoutingStrategy.GEMINI_ONLY, fallbackEnabled = true))
        )
    }

    @Test
    fun `router falls back from ollama rate limit to gemini and marks fallbackUsed`() = runTest {
        val ollama = StubAdapter(
            providerId = AiProviderId.OLLAMA,
            onGenerateText = { _, _ -> throw AiError.RateLimitError(AiProviderId.OLLAMA) }
        )
        val gemini = StubAdapter(
            providerId = AiProviderId.GEMINI,
            onGenerateText = { _, cfg -> AiResponse("Cloud fallback ok", AiProviderId.GEMINI, cfg.geminiModel) }
        )
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        val resp = router.generateText(
            AiTextRequest(prompt = "Summarize chapter 1"),
            AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true, maxRetries = 2)
        )

        assertEquals(1, ollama.textCalls) // non-retryable 429 must not burn retries
        assertEquals(1, gemini.textCalls)
        assertEquals(AiProviderId.GEMINI, resp.provider)
        assertTrue(resp.fallbackUsed)
        assertEquals("Cloud fallback ok", resp.text)
    }

    @Test
    fun `router retries transient server errors up to maxRetries before falling back`() = runTest {
        val ollama = StubAdapter(
            providerId = AiProviderId.OLLAMA,
            onGenerateText = { _, _ -> throw AiError.ServerError(AiProviderId.OLLAMA, 503, "busy") }
        )
        val gemini = StubAdapter(
            providerId = AiProviderId.GEMINI,
            onGenerateText = { _, cfg -> AiResponse("Recovered via Gemini", AiProviderId.GEMINI, cfg.geminiModel) }
        )
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        val resp = router.generateText(
            AiTextRequest(prompt = "Test retry"),
            AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true, maxRetries = 2)
        )

        assertEquals(3, ollama.textCalls) // 1 initial + 2 retries
        assertEquals(1, gemini.textCalls)
        assertTrue(resp.fallbackUsed)
    }

    @Test
    fun `router does not fall back on InvalidRequestError`() = runTest {
        val ollama = StubAdapter(
            providerId = AiProviderId.OLLAMA,
            onGenerateText = { _, _ -> throw AiError.InvalidRequestError(AiProviderId.OLLAMA, "Bad prompt") }
        )
        val gemini = StubAdapter(AiProviderId.GEMINI)
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        try {
            router.generateText(
                AiTextRequest(prompt = "Invalid"),
                AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true)
            )
            fail("Expected InvalidRequestError")
        } catch (e: AiError.InvalidRequestError) {
            assertEquals(AiProviderId.OLLAMA, e.provider)
        }
        assertEquals(0, gemini.textCalls)
    }

    @Test
    fun `stream fallback stops if partial chunks were already emitted`() = runTest {
        val ollama = StubAdapter(
            providerId = AiProviderId.OLLAMA,
            onStreamText = { _, _ ->
                flow {
                    emit("Partial text")
                    throw AiError.ConnectionError(AiProviderId.OLLAMA, "Socket dropped mid-stream")
                }
            }
        )
        var geminiStreamInvoked = false
        val gemini = StubAdapter(
            providerId = AiProviderId.GEMINI,
            onStreamText = { _, _ ->
                geminiStreamInvoked = true
                flowOf("Should not happen")
            }
        )
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        val collected = mutableListOf<String>()
        try {
            router.streamText(
                AiTextRequest(prompt = "Stream"),
                AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true)
            ).toList(collected)
            fail("Expected ConnectionError")
        } catch (_: AiError.ConnectionError) {
            // expected
        }

        assertEquals(listOf("Partial text"), collected)
        assertFalse("Must not fall back mid-stream after partial output", geminiStreamInvoked)
    }

    @Test
    fun `cancellation exception propagates immediately without fallback`() = runTest {
        val ollama = StubAdapter(
            providerId = AiProviderId.OLLAMA,
            onGenerateText = { _, _ -> throw CancellationException("User cancelled") }
        )
        val gemini = StubAdapter(AiProviderId.GEMINI)
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to ollama, AiProviderId.GEMINI to gemini))

        try {
            router.generateText(
                AiTextRequest(prompt = "Cancel me"),
                AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_FIRST, fallbackEnabled = true)
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, gemini.textCalls)
    }

    // ---- Real HTTP Loopback Integration Tests (HttpServer + HttpUrlConnectionTransport) ----

    @Test
    fun `loopback http integration tests ollama discovery, fallback to gemini rest, and timeouts`() = runBlocking {
        val ollamaHits = AtomicInteger(0)
        val geminiHits = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { ex ->
            val payload = """{"models":[{"name":"qwen2.5:3b","size":1500,"modified_at":"2026-03-01"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.createContext("/api/generate") { ex ->
            val hit = ollamaHits.incrementAndGet()
            val payload = """{"error":"temporary overload $hit"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(503, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.createContext("/v1beta/models/gemini-3.8-flash:generateContent") { ex ->
            geminiHits.incrementAndGet()
            val innerJson = """{"title":"Quantum Mechanics","author":"Dr. Dirac","subject":"Physics","overview":"Wave functions","formulaSheet":"E = hf","chapters":[{"chapterNumber":1,"title":"Foundations","startPage":1,"endPage":3,"estimatedStudyMinutes":20}]}"""
            val responseJson = SoraJson.obj(
                "candidates" to SoraJson.arr(
                    listOf(
                        SoraJson.obj(
                            "content" to SoraJson.obj(
                                "parts" to SoraJson.arr(listOf(SoraJson.obj("text" to innerJson)))
                            )
                        )
                    )
                )
            ).toJsonString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, responseJson.size.toLong())
            ex.responseBody.use { it.write(responseJson) }
        }
        server.createContext("/slow/api/generate") { ex ->
            try {
                Thread.sleep(6500L)
            } catch (_: InterruptedException) {
            }
            val body = """{"response":"too late"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port"
            val transport = HttpUrlConnectionTransport()
            val ollamaAdapter = OllamaAdapter(transport)
            val geminiAdapter = GeminiAdapter(transport, FakeGeminiEngine(), restBaseUrl = baseUrl)
            val cfg = AiProviderConfig(
                strategy = AiRoutingStrategy.OLLAMA_FIRST,
                fallbackEnabled = true,
                ollamaEndpoint = baseUrl,
                ollamaModel = "qwen2.5:3b",
                geminiModel = "gemini-3.8-flash",
                geminiApiKey = "AIzaSyLoopbackTestKey00001",
                requestTimeoutMs = 5_000L,
                maxRetries = 1
            )
            val aiService = SoraAiService(
                configProvider = { cfg },
                transport = transport,
                ollamaAdapter = ollamaAdapter,
                geminiAdapter = geminiAdapter,
                router = AiProviderRouter(
                    mapOf(AiProviderId.OLLAMA to ollamaAdapter, AiProviderId.GEMINI to geminiAdapter)
                )
            )

            // 1. Model discovery & testConnection against live loopback HTTP server
            val connResult = aiService.testOllamaConnection()
            assertTrue(connResult.success)
            assertEquals(1, connResult.discoveredModels.size)
            assertEquals("qwen2.5:3b", connResult.discoveredModels.single().name)

            // 2. End-to-end SoraAiService.analyzeBookStructure with 503 retry + fallback to Gemini REST loopback
            val structureRes = aiService.analyzeBookStructure(
                bookId = "b1",
                fallbackTitle = "Physics",
                pages = listOf(BookPageContent(1, "Quantum waves", "Chapter 1")),
                responseLanguage = AppLanguage.ENGLISH
            )
            assertTrue("Expected fallback success: ${structureRes.exceptionOrNull()}", structureRes.isSuccess)
            val structure = structureRes.getOrThrow()
            assertEquals("Quantum Mechanics", structure.title)
            assertEquals("Dr. Dirac", structure.author)
            assertEquals(1, structure.chapters.size)
            assertEquals(2, ollamaHits.get()) // 1 initial + 1 retry
            assertEquals(1, geminiHits.get())
            assertTrue(aiService.lastAiResponse.value?.fallbackUsed == true)

            // 3. Real socket timeout test against slow loopback endpoint
            val slowCfg = cfg.copy(
                strategy = AiRoutingStrategy.OLLAMA_ONLY,
                ollamaEndpoint = "$baseUrl/slow",
                requestTimeoutMs = 5_000L,
                maxRetries = 0
            )
            val timeoutErr = try {
                ollamaAdapter.generateText(AiTextRequest("Slow"), slowCfg)
                fail("Expected TimeoutError")
                null
            } catch (e: AiError) {
                e
            }
            assertTrue("Expected TimeoutError or ConnectionError on read timeout, got $timeoutErr", timeoutErr is AiError.TimeoutError)
        } finally {
            server.stop(0)
        }
    }
}
