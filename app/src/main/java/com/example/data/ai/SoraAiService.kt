package com.example.data.ai

import android.graphics.Bitmap
import com.example.core.AppLanguage
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.Flashcard
import com.example.data.model.GlossaryEntry
import com.example.data.model.QuestionItem
import com.example.data.model.TutorMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

data class DetectedBookStructure(
    val title: String,
    val author: String,
    val subject: String,
    val overview: String,
    val formulaSheet: String,
    val chapters: List<Chapter>
)

data class TeachBackFeedback(
    val scorePercent: Int,
    val accuratePoints: List<String>,
    val missingPoints: List<String>,
    val misconceptions: List<String>,
    val coachingFeedback: String
)

data class AiAnswerGrade(
    val isCorrect: Boolean,
    val scoreRatio: Double,
    val feedback: String
)

fun encodeBitmapJpeg(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}

class SoraAiService(
    private val configProvider: () -> AiProviderConfig = { AiProviderConfig() },
    transport: AiHttpTransport = HttpUrlConnectionTransport(),
    private val ollamaAdapter: OllamaAdapter = OllamaAdapter(transport),
    private val geminiAdapter: GeminiAdapter = GeminiAdapter(transport),
    private val router: AiProviderRouter = AiProviderRouter(
        mapOf(
            AiProviderId.OLLAMA to ollamaAdapter,
            AiProviderId.GEMINI to geminiAdapter
        )
    ),
    private val bitmapEncoder: (Bitmap) -> ByteArray = ::encodeBitmapJpeg
) {
    private val _lastAiResponse = MutableStateFlow<AiResponse?>(null)
    val lastAiResponse: StateFlow<AiResponse?> = _lastAiResponse.asStateFlow()

    suspend fun testOllamaConnection(overrideConfig: AiProviderConfig? = null): ConnectionTestResult {
        val cfg = overrideConfig ?: configProvider()
        return ollamaAdapter.testConnection(cfg)
    }

    suspend fun discoverOllamaModels(overrideConfig: AiProviderConfig? = null): Result<List<OllamaModelInfo>> {
        val cfg = overrideConfig ?: configProvider()
        return try {
            Result.success(ollamaAdapter.discoverModels(cfg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun testGeminiConnection(overrideConfig: AiProviderConfig? = null): ConnectionTestResult {
        val cfg = overrideConfig ?: configProvider()
        return geminiAdapter.testConnection(cfg)
    }

    suspend fun extractTextFromPageImage(bitmap: Bitmap, pageNumber: Int): Result<String> {
        val cfg = configProvider()
        return try {
            val imageBytes = bitmapEncoder(bitmap)
            val response = router.generateVision(
                request = AiVisionRequest(
                    prompt = "Transcribe all educational text, headings, definitions, tables, equations, and diagram labels from textbook page $pageNumber accurately. Preserve structure with markdown headings and bullet points. Do not invent text that is not visible.",
                    imageBytes = imageBytes,
                    mimeType = "image/jpeg",
                    jsonMode = false
                ),
                config = cfg
            )
            _lastAiResponse.value = response
            val text = response.text.trim()
            if (text.isBlank()) Result.failure(AiError.EmptyResponseError(response.provider, "No readable text found on page $pageNumber."))
            else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun analyzeBookStructure(
        bookId: String,
        fallbackTitle: String,
        pages: List<BookPageContent>,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<DetectedBookStructure> {
        val cfg = configProvider()
        return try {
            val totalPages = pages.size.coerceAtLeast(1)
            val excerpt = buildPageContextSnippet(pages, maxChars = 18000)
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                Analyze this uploaded textbook / study material ($totalPages total pages, filename/title hint: "$fallbackTitle").
                $langHint
                Preserve exact page numbers [Page X] from the source below. Never fabricate page ranges outside 1..$totalPages.
                Return ONLY a valid JSON object matching this exact schema:
                {
                  "title": "string",
                  "author": "string",
                  "subject": "string",
                  "overview": "concise 1-paragraph overview",
                  "formulaSheet": "consolidated formulas or empty string",
                  "chapters": [
                    {
                      "chapterNumber": 1,
                      "title": "string",
                      "startPage": 1,
                      "endPage": $totalPages,
                      "estimatedStudyMinutes": 25
                    }
                  ]
                }

                SOURCE PAGES:
                $excerpt
            """.trimIndent()

            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's textbook structure analyzer. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response

            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            val arr = json.arr("chapters")
            val chapters = (arr?.items ?: emptyList()).mapIndexedNotNull { i, item ->
                val obj = item as? JsonObj ?: return@mapIndexedNotNull null
                val start = obj.intVal("startPage", 1).coerceIn(1, totalPages).toLong()
                val end = obj.intVal("endPage", totalPages).coerceIn(start.toInt(), totalPages).toLong()
                Chapter(
                    bookId = bookId,
                    chapterNumber = obj.intVal("chapterNumber", i + 1).toLong(),
                    title = obj.str("title", "Chapter ${i + 1}").ifBlank { "Chapter ${i + 1}" },
                    startPage = start,
                    endPage = end,
                    estimatedStudyMinutes = obj.intVal("estimatedStudyMinutes", 25).coerceIn(5, 300).toLong()
                )
            }.ifEmpty {
                listOf(
                    Chapter(
                        bookId = bookId,
                        chapterNumber = 1L,
                        title = "Chapter 1: Core Concepts",
                        startPage = 1L,
                        endPage = totalPages.toLong(),
                        estimatedStudyMinutes = (totalPages * 5).coerceIn(15, 180).toLong()
                    )
                )
            }

            Result.success(
                DetectedBookStructure(
                    title = json.str("title", fallbackTitle).ifBlank { fallbackTitle },
                    author = json.str("author", ""),
                    subject = json.str("subject", "General Studies").ifBlank { "General Studies" },
                    overview = json.str("overview", ""),
                    formulaSheet = json.str("formulaSheet", ""),
                    chapters = chapters
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun generateChapterIntelligence(
        bookId: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        difficultyNote: String = "Standard",
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<Pair<Chapter, List<GlossaryEntry>>> {
        val cfg = configProvider()
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 20000)
            val langHint = languageDirective(responseLanguage)

            val prompt = """
                You are generating a structured textbook knowledge base for Chapter ${chapter.chapterNumber}: "${chapter.title}" (pages ${chapter.startPage}-${chapter.endPage}).
                Target explanation level: $difficultyNote.
                $langHint
                Strictly ground every fact, definition, formula, date, and pageReference in the provided pages. Do NOT fabricate page numbers outside ${chapter.startPage}..${chapter.endPage}.
                Return ONLY a valid JSON object with keys:
                - quickSummary (string), detailedSummary (string), bulletSummary (string), examRevisionSummary (string)
                - learningObjectives, mainTopics, prerequisites, keyConcepts, importantDefinitions, importantFacts, importantDates, formulas, examples, misconceptions, comparisons, difficultSections, relatedChapters (all string arrays)
                - conceptMap: {"id": "root", "label": "string", "summary": "string", "pageRef": 1, "children": [...]}
                - glossary: [{"term": "string", "definition": "string", "simplifiedExplanation": "string", "example": "string", "pageReference": 1, "relatedTerms": ["string"]}]

                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's chapter intelligence synthesizer. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response

            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            val conceptMapStr = json.obj("conceptMap")?.toJsonString().orEmpty()

            val updatedChapter = chapter.copy(
                quickSummary = json.str("quickSummary"),
                detailedSummary = json.str("detailedSummary"),
                bulletSummary = json.str("bulletSummary"),
                examRevisionSummary = json.str("examRevisionSummary"),
                learningObjectives = json.strList("learningObjectives"),
                mainTopics = json.strList("mainTopics"),
                prerequisites = json.strList("prerequisites"),
                keyConcepts = json.strList("keyConcepts"),
                importantDefinitions = json.strList("importantDefinitions"),
                importantFacts = json.strList("importantFacts"),
                importantDates = json.strList("importantDates"),
                formulas = json.strList("formulas"),
                examples = json.strList("examples"),
                misconceptions = json.strList("misconceptions"),
                comparisons = json.strList("comparisons"),
                difficultSections = json.strList("difficultSections"),
                relatedChapters = json.strList("relatedChapters"),
                conceptMapJson = conceptMapStr
            )

            val glossaryArr = json.arr("glossary")
            val glossaryEntries = (glossaryArr?.items ?: emptyList()).mapNotNull { item ->
                val obj = item as? JsonObj ?: return@mapNotNull null
                val term = obj.str("term").trim()
                val def = obj.str("definition").trim()
                if (term.isBlank() || def.isBlank()) null
                else GlossaryEntry(
                    bookId = bookId,
                    chapterNumber = chapter.chapterNumber,
                    term = term,
                    definition = def,
                    simplifiedExplanation = obj.str("simplifiedExplanation"),
                    example = obj.str("example"),
                    pageReference = obj.intVal("pageReference", chapter.startPage.toInt())
                        .coerceIn(1, pages.size.coerceAtLeast(1)).toLong(),
                    relatedTerms = obj.strList("relatedTerms")
                )
            }

            Result.success(updatedChapter to glossaryEntries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun generateQuestionsForChapter(
        bookId: String,
        bookTitle: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        requestedTypes: List<String>,
        count: Int = 8,
        difficultyHint: String = "Mixed (Easy, Medium, Hard)",
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<List<QuestionItem>> {
        val cfg = configProvider()
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 18000)
            val langHint = languageDirective(responseLanguage)

            val prompt = """
                Generate $count diverse, non-repetitive assessment questions grounded in Chapter ${chapter.chapterNumber}: "${chapter.title}" of "$bookTitle".
                Requested question types (use these exact type strings): ${requestedTypes.joinToString(", ")}.
                Difficulty mix: $difficultyHint.
                $langHint
                Accuracy rules:
                - For MCQ, provide exactly 4 distinct options where ONLY ONE is unambiguously correct, and set correctAnswer equal to that exact option text.
                - For TRUE_FALSE, set options to ["True", "False"] and correctAnswer to "True" or "False".
                - Cite the exact sourcePage from [Page X] in the text. Never fabricate page numbers.
                Return ONLY a valid JSON object:
                {
                  "questions": [
                    {
                      "questionText": "string",
                      "questionType": "MCQ",
                      "difficulty": "EASY|MEDIUM|HARD",
                      "options": ["string"],
                      "correctAnswer": "string",
                      "explanation": "string",
                      "section": "string",
                      "topic": "string",
                      "sourcePage": 1,
                      "learningObjective": "string",
                      "tags": ["string"],
                      "marks": 2,
                      "estimatedSeconds": 60
                    }
                  ]
                }

                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's assessment designer. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response

            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            val arr = json.arr("questions")
            val maxPage = pages.size.coerceAtLeast(1)

            val items = (arr?.items ?: emptyList()).mapNotNull { item ->
                val obj = item as? JsonObj ?: return@mapNotNull null
                val qText = obj.str("questionText").trim()
                val ans = obj.str("correctAnswer").trim()
                if (qText.isBlank() || ans.isBlank()) null
                else QuestionItem(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    chapterNumber = chapter.chapterNumber,
                    section = obj.str("section", chapter.title),
                    topic = obj.str("topic", chapter.title),
                    sourcePage = obj.intVal("sourcePage", chapter.startPage.toInt()).coerceIn(1, maxPage).toLong(),
                    difficulty = obj.str("difficulty", "MEDIUM").uppercase().let {
                        if (it in listOf("EASY", "MEDIUM", "HARD")) it else "MEDIUM"
                    },
                    questionType = obj.str("questionType", "MCQ").uppercase(),
                    questionText = qText,
                    options = obj.strList("options"),
                    correctAnswer = ans,
                    explanation = obj.str("explanation"),
                    learningObjective = obj.str("learningObjective"),
                    tags = obj.strList("tags"),
                    marks = obj.intVal("marks", 2).coerceIn(1, 25).toLong(),
                    estimatedSeconds = obj.intVal("estimatedSeconds", 60).coerceIn(15, 900).toLong(),
                    aiGenerated = true
                )
            }
            Result.success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun generateFlashcardsForChapter(
        bookId: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        count: Int = 10,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<List<Flashcard>> {
        val cfg = configProvider()
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 16000)
            val langHint = languageDirective(responseLanguage)

            val prompt = """
                Create $count high-retention spaced-repetition flashcards for Chapter ${chapter.chapterNumber}: "${chapter.title}".
                Use varied formats: TERM_DEF, QA, CONCEPT_EXPLAIN, FORMULA_MEANING, COMPARISON.
                Keep fronts concise and backs crystal clear. Cite exact pageReference from [Page X].
                $langHint
                Return ONLY a valid JSON object:
                {
                  "flashcards": [
                    {
                      "format": "TERM_DEF",
                      "front": "string",
                      "back": "string",
                      "hint": "string",
                      "pageReference": 1
                    }
                  ]
                }

                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's spaced-repetition flashcard creator. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response

            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            val arr = json.arr("flashcards")
            val maxPage = pages.size.coerceAtLeast(1)

            val cards = (arr?.items ?: emptyList()).mapNotNull { item ->
                val obj = item as? JsonObj ?: return@mapNotNull null
                val front = obj.str("front").trim()
                val back = obj.str("back").trim()
                if (front.isBlank() || back.isBlank()) null
                else Flashcard(
                    bookId = bookId,
                    chapterNumber = chapter.chapterNumber,
                    deckName = "Ch. ${chapter.chapterNumber}: ${chapter.title.take(40)}",
                    format = obj.str("format", "TERM_DEF"),
                    front = front,
                    back = back,
                    hint = obj.str("hint"),
                    pageReference = obj.intVal("pageReference", chapter.startPage.toInt()).coerceIn(1, maxPage).toLong()
                )
            }
            Result.success(cards)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    fun streamTutorReply(
        bookTitle: String,
        chapter: Chapter?,
        currentPage: BookPageContent?,
        mode: TutorMode,
        historyPairs: List<Pair<String, String>>,
        userQuery: String,
        pageBitmap: Bitmap? = null,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH,
        retrievedPassages: List<RetrievedPassage> = emptyList()
    ): Flow<String> {
        val cfg = configProvider()
        val langHint = languageDirective(responseLanguage)
        val groundedBlock = BookPassageRetriever.formatGroundedContext(retrievedPassages, maxChars = 6500)
        val systemContext = """
            You are SŌRA's Contextual Academic Tutor.
            Current Textbook: "$bookTitle"
            Current Chapter: ${chapter?.let { "Chapter ${it.chapterNumber}: ${it.title}" } ?: "Overview"}
            Current Page Number: ${currentPage?.pageNumber ?: 1}
            Active Tutor Mode: ${mode.label} (${mode.description})
            $langHint

            STRICT ACCURACY & CITATION RULES:
            1. Ground answers primarily in the textbook page excerpt and retrieved cross-chapter passages below.
            2. Clearly label "[From Textbook — Ch. X, p. Y]" (or "[From Textbook — p. X]") vs "[Supplementary Academic Context]".
            3. NEVER fabricate page numbers or citations not present in the source excerpts.
            4. Follow the active mode faithfully (e.g., Socratic asks guiding questions first; Hint gives progressive clues; Examiner grades the student's response).

            CURRENT PAGE TEXT ([Page ${currentPage?.pageNumber ?: 1}]):
            ${currentPage?.text?.take(4500) ?: "No text extracted for this page."}

            CHAPTER SUMMARY CONTEXT:
            ${chapter?.quickSummary?.take(1500) ?: ""}
            ${if (groundedBlock.isNotBlank()) "\nRETRIEVED TEXTBOOK PASSAGES (FULL-BOOK SEARCH):\n$groundedBlock" else ""}
        """.trimIndent()

        val conversationText = buildString {
            if (historyPairs.isNotEmpty()) {
                append("RECENT CONVERSATION:\n")
                historyPairs.takeLast(8).forEach { (role, text) ->
                    append("$role: $text\n")
                }
                append("\n")
            }
            append("STUDENT REQUEST: $userQuery")
        }

        return if (pageBitmap != null) {
            kotlinx.coroutines.flow.flow {
                val imageBytes = bitmapEncoder(pageBitmap)
                val response = router.generateVision(
                    request = AiVisionRequest(
                        prompt = "$systemContext\n\n$conversationText",
                        imageBytes = imageBytes,
                        mimeType = "image/jpeg",
                        jsonMode = false
                    ),
                    config = cfg
                )
                _lastAiResponse.value = response
                emit(response.text)
            }
        } else {
            router.streamText(
                request = AiTextRequest(
                    prompt = conversationText,
                    systemPrompt = systemContext,
                    jsonMode = false
                ),
                config = cfg
            )
        }
    }

    suspend fun runReadingAssistant(
        action: String,
        selectedText: String,
        pageNumber: Int,
        bookTitle: String,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<String> {
        val cfg = configProvider()
        return try {
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                Book: "$bookTitle", Page $pageNumber.
                Student selected this passage:
                "$selectedText"

                Perform action: $action (e.g., Simplify, Explain, Key Definitions, Worked Examples, Translate to Arabic/Spanish/French if requested, Practice Questions, or Concise Summary).
                $langHint
                Keep textbook terminology intact and clearly separate textbook facts from supplementary explanation.
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(prompt = prompt),
                config = cfg
            )
            _lastAiResponse.value = response
            val out = response.text.trim()
            if (out.isBlank()) Result.failure(AiError.EmptyResponseError(response.provider))
            else Result.success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun evaluateTeachBack(
        conceptTitle: String,
        referenceText: String,
        studentExplanation: String,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<TeachBackFeedback> {
        val cfg = configProvider()
        return try {
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                Evaluate this student's Teach-Back explanation for "$conceptTitle".
                $langHint
                REFERENCE TEXTBOOK MATERIAL:
                ${referenceText.take(6000)}

                STUDENT EXPLANATION:
                $studentExplanation

                Return ONLY a valid JSON object:
                {
                  "scorePercent": 0,
                  "accuratePoints": ["string"],
                  "missingPoints": ["string"],
                  "misconceptions": ["string"],
                  "coachingFeedback": "string"
                }
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's academic Teach-Back evaluator. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response
            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            Result.success(
                TeachBackFeedback(
                    scorePercent = json.intVal("scorePercent", 0).coerceIn(0, 100),
                    accuratePoints = json.strList("accuratePoints"),
                    missingPoints = json.strList("missingPoints"),
                    misconceptions = json.strList("misconceptions"),
                    coachingFeedback = json.str("coachingFeedback")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun solveEquationWithSteps(
        equationInput: String,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<String> {
        val cfg = configProvider()
        return try {
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                Solve the following academic/mathematical/scientific equation or word problem with clear step-by-step reasoning, showing intermediate steps, units, and verification of the final answer.
                $langHint
                Problem:
                $equationInput
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(prompt = prompt),
                config = cfg
            )
            _lastAiResponse.value = response
            val text = response.text.trim()
            if (text.isBlank()) Result.failure(AiError.EmptyResponseError(response.provider))
            else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun generateComparisonOrGapAnalysis(
        mode: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<String> {
        val cfg = configProvider()
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(5) }
            val snippet = buildPageContextSnippet(chapterPages, 12000)
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                For Chapter ${chapter.chapterNumber}: "${chapter.title}", generate: $mode.
                $langHint
                Use clean markdown tables or bulleted prerequisite checklists grounded in these pages:
                $snippet
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(prompt = prompt),
                config = cfg
            )
            _lastAiResponse.value = response
            val text = response.text.trim()
            if (text.isBlank()) Result.failure(AiError.EmptyResponseError(response.provider))
            else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun synthesizeFullBookExecutive(
        bookTitle: String,
        chapters: List<Chapter>,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<Pair<String, String>> {
        val cfg = configProvider()
        return try {
            val langHint = languageDirective(responseLanguage)
            val digest = chapters.sortedBy { it.chapterNumber }.joinToString("\n\n") { ch ->
                buildString {
                    append("Ch. ${ch.chapterNumber}: ${ch.title} (pp. ${ch.startPage}-${ch.endPage})\n")
                    if (ch.quickSummary.isNotBlank()) append("Summary: ${ch.quickSummary.take(500)}\n")
                    if (ch.keyConcepts.isNotEmpty()) append("Concepts: ${ch.keyConcepts.take(8).joinToString(", ")}\n")
                    if (ch.formulas.isNotEmpty()) append("Formulas: ${ch.formulas.take(8).joinToString("; ")}\n")
                }
            }.take(16000)
            val prompt = """
                Synthesize a unified full-book overview and master formula/principle sheet for "$bookTitle" covering ALL chapters below.
                $langHint
                Return ONLY valid JSON:
                {
                  "overview": "comprehensive multi-paragraph executive synthesis connecting themes across chapters",
                  "formulaSheet": "clean markdown list of key formulas, laws, and definitions with chapter references"
                }

                CHAPTER SUMMARIES:
                $digest
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's full-book knowledge synthesizer. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response
            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            Result.success(json.str("overview").trim() to json.str("formulaSheet").trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    suspend fun evaluateOpenEndedAnswer(
        questionText: String,
        referenceAnswer: String,
        explanation: String,
        studentAnswer: String,
        responseLanguage: AppLanguage = AppLanguage.ENGLISH
    ): Result<AiAnswerGrade> {
        val cfg = configProvider()
        return try {
            val langHint = languageDirective(responseLanguage)
            val prompt = """
                Grade this student exam answer fairly against the textbook reference answer.
                $langHint
                QUESTION: $questionText
                REFERENCE ANSWER: $referenceAnswer
                EXPLANATION: ${explanation.take(800)}
                STUDENT ANSWER: $studentAnswer

                Return ONLY valid JSON:
                {
                  "isCorrect": true,
                  "scoreRatio": 1.0,
                  "feedback": "concise 1-2 sentence grading feedback"
                }
            """.trimIndent()
            val response = router.generateText(
                request = AiTextRequest(
                    prompt = prompt,
                    systemPrompt = "You are SŌRA's academic exam grader. Respond strictly with valid JSON.",
                    jsonMode = true
                ),
                config = cfg
            )
            _lastAiResponse.value = response
            val json = SoraJson.parseObject(SoraJson.extractJsonPayload(response.text))
            val ratio = json.doubleVal("scoreRatio", if (json.boolVal("isCorrect", false)) 1.0 else 0.0).coerceIn(0.0, 1.0)
            Result.success(
                AiAnswerGrade(
                    isCorrect = json.boolVal("isCorrect", ratio >= 0.5),
                    scoreRatio = ratio,
                    feedback = json.str("feedback")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(sanitizeFailure(e, cfg))
        }
    }

    private fun languageDirective(language: AppLanguage): String = when (language) {
        AppLanguage.ARABIC -> "Write student-facing explanations, headings, summaries, questions, and feedback in clear Modern Standard Arabic while preserving technical scientific symbols/formulas accurately."
        AppLanguage.ENGLISH -> "Respond in clear academic English."
    }

    private fun sanitizeFailure(e: Exception, config: AiProviderConfig): Exception {
        if (e is AiError) return e
        val msg = SecretRedactor.redact(e.message ?: e::class.simpleName.orEmpty(), listOf(config.geminiApiKey))
        return IllegalStateException(msg, e)
    }

    private fun buildPageContextSnippet(pages: List<BookPageContent>, maxChars: Int): String {
        val sb = StringBuilder()
        for (page in pages) {
            if (page.text.isBlank()) continue
            val block = "[Page ${page.pageNumber}]\n${page.text}\n\n"
            if (sb.length + block.length > maxChars) {
                val remaining = (maxChars - sb.length).coerceAtLeast(0)
                if (remaining > 100) sb.append(block.take(remaining))
                break
            }
            sb.append(block)
        }
        return sb.toString()
    }
}
