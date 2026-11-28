package com.example

import com.example.core.AppLanguage
import com.example.data.ai.AiCapability
import com.example.data.ai.AiProviderAdapter
import com.example.data.ai.AiProviderConfig
import com.example.data.ai.AiProviderId
import com.example.data.ai.AiProviderRouter
import com.example.data.ai.AiResponse
import com.example.data.ai.AiRoutingStrategy
import com.example.data.ai.AiTextRequest
import com.example.data.ai.AiVisionRequest
import com.example.data.ai.BookPassageRetriever
import com.example.data.ai.ConnectionTestResult
import com.example.data.ai.FullBookIntelligenceEngine
import com.example.data.ai.SoraAiService
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class StubFullBookAdapter(
    private val failChapterMatch: String? = null
) : AiProviderAdapter {
    override val providerId: AiProviderId = AiProviderId.OLLAMA
    override val capabilities: Set<AiCapability> = AiCapability.entries.toSet()
    val capturedPrompts = mutableListOf<String>()

    override suspend fun generateText(request: AiTextRequest, config: AiProviderConfig): AiResponse {
        capturedPrompts.add(request.prompt)
        if (failChapterMatch != null &&
            !request.prompt.contains("executive synthesis") &&
            request.prompt.contains(failChapterMatch)
        ) {
            throw IllegalStateException("Simulated timeout on chapter")
        }
        val text = when {
            request.jsonMode && request.prompt.contains("quickSummary") ->
                """{"quickSummary":"Chapter digest.","detailedSummary":"Detailed notes.","keyConcepts":["Entropy"],"definitions":["S = k ln W"],"importantFacts":["Energy conserved"],"formulas":["Delta U = Q - W"],"mindMapJson":"{}","glossary":[{"term":"Entropy","definition":"Measure of disorder","page":1}]}"""
            request.jsonMode && request.prompt.contains("correctAnswer") ->
                """{"questions":[{"questionText":"State the First Law.","questionType":"SHORT_ANSWER","difficulty":"MEDIUM","marks":2,"options":[],"correctAnswer":"Energy conservation","explanation":"First Law","sourcePage":2}]}"""
            request.jsonMode && request.prompt.contains("flashcards") ->
                """{"flashcards":[{"front":"First Law","back":"Delta U = Q - W","hint":"Work and heat","deck":"Thermo","format":"TERM_DEF","page":2}]}"""
            request.jsonMode && request.prompt.contains("executive synthesis") ->
                """{"overview":"Complete book executive synthesis across chapters.","formulaSheet":"Delta U = Q - W\npV = nRT"}"""
            else -> "{}"
        }
        return AiResponse(text, AiProviderId.OLLAMA, "llama3.2:3b")
    }

    override fun streamText(request: AiTextRequest, config: AiProviderConfig): Flow<String> = flowOf("")
    override suspend fun generateVision(request: AiVisionRequest, config: AiProviderConfig): AiResponse =
        AiResponse("", AiProviderId.OLLAMA, "llava")
    override suspend fun testConnection(config: AiProviderConfig): ConnectionTestResult =
        ConnectionTestResult(success = true, provider = AiProviderId.OLLAMA, message = "ok")
}

class FullBookIntelligenceTest {

    private val samplePages = listOf(
        BookPageContent(1, "Chapter 1 Kinematics covers uniform velocity and acceleration equations v = u + at.", "TEXT"),
        BookPageContent(2, "Newton's second law states Force equals mass times acceleration F = m * a.", "TEXT"),
        BookPageContent(3, "الفصل الثاني: الديناميكا الحرارية والطاقة الحرة وإنتروبيا النظام المغلق.", "TEXT"),
        BookPageContent(4, "Ideal gas equation relates pressure, volume, and temperature: pV = nRT.", "TEXT"),
        BookPageContent(5, "Electrostatics describes Coulomb force between charges F = k * q1 * q2 / r^2.", "TEXT")
    )

    private val sampleChapters = listOf(
        Chapter(id = "ch1", bookId = "b1", chapterNumber = 1L, title = "Kinematics & Mechanics", startPage = 1L, endPage = 2L),
        Chapter(id = "ch2", bookId = "b1", chapterNumber = 2L, title = "Thermodynamics", startPage = 3L, endPage = 4L),
        Chapter(id = "ch3", bookId = "b1", chapterNumber = 3L, title = "Electrostatics", startPage = 5L, endPage = 5L)
    )

    @Test
    fun `bilingual BM25 retriever ranks English and Arabic passages accurately and enforces budget`() {
        val enHits = BookPassageRetriever.retrieve(
            query = "Newton second law acceleration formula",
            pages = samplePages,
            chapters = sampleChapters,
            topK = 3,
            maxTotalChars = 2000
        )
        assertTrue(enHits.isNotEmpty())
        assertEquals(2, enHits.first().pageNumber)
        assertEquals(1L, enHits.first().chapterNumber)

        val arHits = BookPassageRetriever.retrieve(
            query = "الديناميكا الحراريه انتروبيا",
            pages = samplePages,
            chapters = sampleChapters,
            topK = 2,
            maxTotalChars = 2000
        )
        assertTrue(arHits.isNotEmpty())
        assertEquals(3, arHits.first().pageNumber)
        assertEquals(2L, arHits.first().chapterNumber)

        val hugePages = (1..20).map { p ->
            BookPageContent(p, "Physics concept density momentum impulse energy conservation ".repeat(15), "TEXT")
        }
        val budgeted = BookPassageRetriever.retrieve(
            query = "momentum energy conservation",
            pages = hugePages,
            chapters = emptyList(),
            topK = 10,
            maxTotalChars = 1500
        )
        assertTrue(budgeted.sumOf { it.excerpt.length } <= 1500)
    }

    @Test
    fun `full book intelligence engine processes all chapters saves incremental results and survives partial chapter failure`() = runTest {
        val adapter = StubFullBookAdapter(failChapterMatch = "Thermodynamics")
        val router = AiProviderRouter(mapOf(AiProviderId.OLLAMA to adapter))
        val service = SoraAiService(
            configProvider = { AiProviderConfig(strategy = AiRoutingStrategy.OLLAMA_ONLY, ollamaEndpoint = "http://localhost:11434") },
            router = router
        )
        val engine = FullBookIntelligenceEngine(service)
        val savedChapters = mutableListOf<Chapter>()

        val result = engine.analyzeAllChapters(
            bookId = "b1",
            bookTitle = "University Physics",
            chapters = sampleChapters,
            pages = samplePages,
            responseLanguage = AppLanguage.ENGLISH
        ) { enriched, _, _, _ ->
            savedChapters.add(enriched)
        }

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(2, summary.completedChapters)
        assertEquals(1, summary.failedChapters)
        assertEquals(2, savedChapters.size)
        assertTrue(summary.consolidatedOverview.contains("executive synthesis"))
        assertTrue(summary.consolidatedFormulaSheet.contains("pV = nRT"))
    }
}
