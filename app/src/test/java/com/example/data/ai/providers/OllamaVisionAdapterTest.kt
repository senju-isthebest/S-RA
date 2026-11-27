package com.example.data.ai.providers

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OllamaVisionAdapterTest {
    @Test
    fun visionRequestIncludesEncodedImage() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"response":"OCR text"}"""))
        server.start()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val provider = OllamaTextProvider("vision-model", server.url("/").toString())
            val response = provider.generateFromImage(bitmap, AiTextRequest(prompt = "Read text"))
            val payload = org.json.JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(1, payload.getJSONArray("images").length())
            assertEquals("OCR text", response.text)
        } finally {
            bitmap.recycle()
            server.shutdown()
        }
    }
}
