package com.example.data.ai

import com.example.core.AppLanguage
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.Flashcard
import com.example.data.model.GlossaryEntry
import com.example.data.model.QuestionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class FullBookAnalysisProgress(
    val active: Boolean = false,
    val currentChapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val currentChapterTitle: String = "",
    val completedChapters: Int = 0,
    val failedChapters: Int = 0
)

data class FullBookAnalysisResult(
    val completedChapters: Int,
    val failedChapters: Int,
    val consolidatedOverview: String,
    val consolidatedFormulaSheet: String
)

/**
 * Orchestrates multi-chapter intelligence, glossary, questions, flashcards, and executive synthesis
 * across EVERY chapter of a textbook without losing partial progress on chapter errors.
 */
class FullBookIntelligenceEngine(
    private val aiService: SoraAiService
) {
    suspend fun analyzeAllChapters(
        bookId: String,
        bookTitle: String,
        chapters: List<Chapter>,
        pages: List<BookPageContent>,
        difficultyLevel: String = "Standard",
        responseLanguage: AppLanguage = AppLanguage.ENGLISH,
        onProgress: (FullBookAnalysisProgress) -> Unit = {},
        onChapterReady: suspend (
            enrichedChapter: Chapter,
            glossary: List<GlossaryEntry>,
            questions: List<QuestionItem>,
            flashcards: List<Flashcard>
        ) -> Unit
    ): Result<FullBookAnalysisResult> {
        if (chapters.isEmpty() || pages.isEmpty()) {
            return Result.failure(IllegalStateException("Chapters and extracted pages are required for full-book intelligence."))
        }

        val ordered = chapters.sortedBy { it.chapterNumber }
        var completed = 0
        var failed = 0
        val enrichedChapters = mutableListOf<Chapter>()

        ordered.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            onProgress(
                FullBookAnalysisProgress(
                    active = true,
                    currentChapterIndex = idx + 1,
                    totalChapters = ordered.size,
                    currentChapterTitle = chapter.title,
                    completedChapters = completed,
                    failedChapters = failed
                )
            )

            try {
                val intelRes = aiService.generateChapterIntelligence(
                    bookId = bookId,
                    chapter = chapter,
                    pages = pages,
                    difficultyNote = difficultyLevel,
                    responseLanguage = responseLanguage
                )
                val qRes = aiService.generateQuestionsForChapter(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    chapter = chapter,
                    pages = pages,
                    requestedTypes = listOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "CALCULATION", "APPLICATION"),
                    count = 6,
                    responseLanguage = responseLanguage
                )
                val fcRes = aiService.generateFlashcardsForChapter(
                    bookId = bookId,
                    chapter = chapter,
                    pages = pages,
                    count = 6,
                    responseLanguage = responseLanguage
                )

                val (enriched, glossary) = intelRes.getOrElse { chapter to emptyList() }
                val questions = qRes.getOrElse { emptyList() }
                val cards = fcRes.getOrElse { emptyList() }

                if (intelRes.isSuccess || qRes.isSuccess || fcRes.isSuccess) {
                    onChapterReady(enriched, glossary, questions, cards)
                    enrichedChapters.add(enriched)
                    completed++
                } else {
                    enrichedChapters.add(chapter)
                    failed++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                enrichedChapters.add(chapter)
                failed++
            }
        }

        val synthRes = aiService.synthesizeFullBookExecutive(
            bookTitle = bookTitle,
            chapters = enrichedChapters,
            responseLanguage = responseLanguage
        )
        val (overview, formulaSheet) = synthRes.getOrElse {
            val fallbackOverview = enrichedChapters
                .filter { it.quickSummary.isNotBlank() }
                .joinToString("\n\n") { "Ch. ${it.chapterNumber} (${it.title}): ${it.quickSummary}" }
            val fallbackFormulas = enrichedChapters
                .flatMap { it.formulas }
                .distinct()
                .joinToString("\n") { "- $it" }
            fallbackOverview to fallbackFormulas
        }

        onProgress(
            FullBookAnalysisProgress(
                active = false,
                currentChapterIndex = ordered.size,
                totalChapters = ordered.size,
                currentChapterTitle = "",
                completedChapters = completed,
                failedChapters = failed
            )
        )

        return Result.success(
            FullBookAnalysisResult(
                completedChapters = completed,
                failedChapters = failed,
                consolidatedOverview = overview.take(4000),
                consolidatedFormulaSheet = formulaSheet.take(10000)
            )
        )
    }
}
