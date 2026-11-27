package com.example.data.ai.providers

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class OllamaVisionAdapterTest {
    @Test
    fun visionRequestIncludesEncodedImage() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"response":"OCR text"}"""))
        server.start()
        try {
            val provider = OllamaTextProvider("vision-model", server.url("/").toString())
            val response = provider.generateFromImageBase64("AQID", AiTextRequest(prompt = "Read text"))
            val payload = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(1, payload.getJSONArray("images").length())
            assertEquals("AQID", payload.getJSONArray("images").getString(0))
            assertEquals("OCR text", response.text)
        } finally {
            server.shutdown()
        }
    }
}
