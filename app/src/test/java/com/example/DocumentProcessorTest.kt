package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.SoraAiService
import com.example.data.local.DocumentProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun processor(): DocumentProcessor =
        DocumentProcessor(ApplicationProvider.getApplicationContext(), SoraAiService())

    @Test
    fun `extractTextOperators decodes parenthesized, UTF-16BE hex, and ASCII hex PDF strings`() {
        val dp = processor()
        val stream = """
            BT
            (Hello \(World\)) Tj
            <FEFF0050006800790073006900630073> Tj
            <0043006800610070007400650072> Tj
            <4E6577746F6E> Tj
            ET
        """.trimIndent()

        val extracted = dp.extractTextOperators(stream)
        assertTrue("Expected paren text in: $extracted", extracted.contains("Hello (World)"))
        assertTrue("Expected UTF-16BE BOM text in: $extracted", extracted.contains("Physics"))
        assertTrue("Expected interleaved UTF-16BE text in: $extracted", extracted.contains("Chapter"))
        assertTrue("Expected ASCII hex text in: $extracted", extracted.contains("Newton"))
    }

    @Test
    fun `copyStreamWithLimit enforces max byte budget and leaves no partial target file`() {
        val dp = processor()
        val dir = tempFolder.newFolder("ingest_limit")

        val smallTarget = File(dir, "small.bin")
        val okPayload = ByteArray(1024) { 0x41 }
        assertTrue(dp.copyStreamWithLimit(ByteArrayInputStream(okPayload), smallTarget, maxBytes = 2048))
        assertTrue(smallTarget.exists())
        assertEquals(1024L, smallTarget.length())

        val largeTarget = File(dir, "too_big.bin")
        val oversized = ByteArray(4096) { 0x42 }
        assertFalse(dp.copyStreamWithLimit(ByteArrayInputStream(oversized), largeTarget, maxBytes = 2048))
        assertFalse("Oversized copy must not create destination file", largeTarget.exists())
        val leftoverTemps = dir.listFiles { _, name -> name.endsWith(".tmp") }.orEmpty()
        assertTrue("No temp files should remain after size rejection", leftoverTemps.isEmpty())
    }
}
