package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.Flashcard
import com.example.data.model.GlossaryEntry
import com.example.data.model.QuestionItem
import com.example.data.model.TutorMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai

import com.example.data.ai.providers.AiProviderRouter
import com.example.data.ai.providers.AiTextRequest
import com.example.data.ai.providers.GeminiTextProvider
import com.example.data.ai.providers.OllamaTextProvider
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SoraAiService"
private const val AI_TIMEOUT_MS = 60_000L
private const val TEXT_ROUTER_TIMEOUT_MS = 170_000L

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

class SoraAiService(
    private val ollamaBaseUrl: String = BuildConfig.OLLAMA_BASE_URL,
    private val ollamaModel: String = BuildConfig.OLLAMA_MODEL,
    private val ollamaVisionModel: String = BuildConfig.OLLAMA_VISION_MODEL
) {

    private val generalModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    // Text and structured requests use Gemini first; text-only and structured JSON can fall back
    // to a configured Ollama endpoint. Image OCR and image-based tutor requests remain Gemini-only.
    private val textRouter by lazy {
        val configuredProviders = mutableListOf<com.example.data.ai.providers.AiTextProvider>(
            GeminiTextProvider()
        )
        if (ollamaBaseUrl.isNotBlank()) {
            runCatching {
                configuredProviders += OllamaTextProvider(
                    modelId = ollamaModel,
                    baseUrl = ollamaBaseUrl
                )
            }.onFailure { error ->
                Log.w(TAG, "Ignoring invalid Ollama fallback configuration.", error)
            }
        }
        AiProviderRouter(configuredProviders)
    }

    private suspend fun generateStructuredTextWithFallback(
        prompt: String,
        taskTag: String,
        primaryCall: suspend () -> String
    ): String {
        try {
            val primaryText = withTimeout(AI_TIMEOUT_MS) { primaryCall() }
            JSONObject(primaryText) // Reject malformed structured output before returning it.
            return primaryText
        } catch (timeout: TimeoutCancellationException) {
            if (ollamaBaseUrl.isBlank()) throw timeout
            Log.w(TAG, "$taskTag primary provider timed out; trying configured Ollama fallback.")
            return generateOllamaJsonFallback(prompt, taskTag)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val invalidStructuredOutput = failure is org.json.JSONException
            if (ollamaBaseUrl.isBlank() || (!isTransientAiFailure(failure) && !invalidStructuredOutput)) throw failure
            Log.w(TAG, "$taskTag primary provider failed; trying configured Ollama fallback.")
            return generateOllamaJsonFallback(prompt, taskTag)
        }
    }

    private suspend fun generateOllamaJsonFallback(prompt: String, taskTag: String): String {
        val provider = OllamaTextProvider(modelId = ollamaModel, baseUrl = ollamaBaseUrl)
        val schemaGuidance = when (taskTag) {
            "book_structure" -> """JSON keys: title, author, subject, overview, formulaSheet, chapters. chapters is an array of objects with chapterNumber, title, startPage, endPage, estimatedStudyMinutes."""
            "chapter_intelligence" -> """JSON keys: quickSummary, detailedSummary, bulletSummary, examRevisionSummary, learningObjectives, mainTopics, prerequisites, keyConcepts, importantDefinitions, importantFacts, importantDates, formulas, examples, misconceptions, comparisons, difficultSections, relatedChapters, conceptMap, glossary. conceptMap is a tree with id, label, summary, pageRef, children; glossary items have term, definition, simplifiedExplanation, example, pageReference, relatedTerms."""
            "question_generation" -> """Return {"questions":[...]}. Each question has questionText, questionType, difficulty, options, correctAnswer, explanation, section, topic, sourcePage, learningObjective, tags, marks, estimatedSeconds."""
            "flashcard_generation" -> """Return {"flashcards":[...]}. Each item has format, front, back, hint, pageReference."""
            "teach_back" -> """JSON keys: scorePercent, accuratePoints, missingPoints, misconceptions, coachingFeedback."""
            else -> "Return a JSON object matching the task's expected fields."
        }
        val jsonPrompt = prompt + "\n\n" + schemaGuidance + "\nReturn valid JSON only. Do not wrap it in markdown fences."
        return withTimeout(110_000L) {
            val response = provider.generate(
                AiTextRequest(
                    prompt = jsonPrompt,
                    taskTag = taskTag,
                    responseFormat = com.example.data.ai.providers.AiResponseFormat.JSON
                )
            ).text
            try {
                JSONObject(response)
                response
            } catch (malformed: org.json.JSONException) {
                val repairPrompt = "$jsonPrompt\n\nYour previous output was malformed. Repair it into a valid JSON object matching the requested fields. Return JSON only.\n\nPrevious output:\n$response"
                val repaired = provider.generate(
                    AiTextRequest(
                        prompt = repairPrompt,
                        taskTag = "${taskTag}_json_repair",
                        responseFormat = com.example.data.ai.providers.AiResponseFormat.JSON
                    )
                ).text
                JSONObject(repaired)
                repaired
            }
        }
    }

    private suspend fun generateOllamaTextFallback(prompt: String, taskTag: String): String {
        if (ollamaBaseUrl.isBlank()) error("No Ollama fallback endpoint is configured.")
        val provider = OllamaTextProvider(modelId = ollamaModel, baseUrl = ollamaBaseUrl)
        return withTimeout(110_000L) {
            provider.generate(AiTextRequest(prompt = prompt, taskTag = taskTag)).text
        }
    }

    private fun isTransientAiFailure(failure: Throwable): Boolean {
        val messages = generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return listOf(
            "429", "quota", "rate limit", "rate-limit", "timeout", "timed out",
            "network", "connection", "unavailable", "502", "503", "504", "500"
        ).any { it in messages }
    }

    private val structureSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Detected textbook title"),
            "author" to Schema.string(description = "Detected author or source"),
            "subject" to Schema.string(description = "Academic subject name"),
            "overview" to Schema.string(description = "High-level textbook overview"),
            "formulaSheet" to Schema.string(description = "Consolidated formulas and constants found in the material"),
            "chapters" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "chapterNumber" to Schema.integer(),
                        "title" to Schema.string(),
                        "startPage" to Schema.integer(),
                        "endPage" to Schema.integer(),
                        "estimatedStudyMinutes" to Schema.integer()
                    )
                )
            )
        )
    )

    private val chapterIntelSchema = Schema.obj(
        properties = mapOf(
            "quickSummary" to Schema.string(),
            "detailedSummary" to Schema.string(),
            "bulletSummary" to Schema.string(),
            "examRevisionSummary" to Schema.string(),
            "learningObjectives" to Schema.array(items = Schema.string()),
            "mainTopics" to Schema.array(items = Schema.string()),
            "prerequisites" to Schema.array(items = Schema.string()),
            "keyConcepts" to Schema.array(items = Schema.string()),
            "importantDefinitions" to Schema.array(items = Schema.string()),
            "importantFacts" to Schema.array(items = Schema.string()),
            "importantDates" to Schema.array(items = Schema.string()),
            "formulas" to Schema.array(items = Schema.string()),
            "examples" to Schema.array(items = Schema.string()),
            "misconceptions" to Schema.array(items = Schema.string()),
            "comparisons" to Schema.array(items = Schema.string()),
            "difficultSections" to Schema.array(items = Schema.string()),
            "relatedChapters" to Schema.array(items = Schema.string()),
            "conceptMap" to Schema.obj(
                properties = mapOf(
                    "id" to Schema.string(),
                    "label" to Schema.string(),
                    "summary" to Schema.string(),
                    "pageRef" to Schema.integer(),
                    "children" to Schema.array(
                        items = Schema.obj(
                            properties = mapOf(
                                "id" to Schema.string(),
                                "label" to Schema.string(),
                                "summary" to Schema.string(),
                                "pageRef" to Schema.integer(),
                                "children" to Schema.array(
                                    items = Schema.obj(
                                        properties = mapOf(
                                            "id" to Schema.string(),
                                            "label" to Schema.string(),
                                            "summary" to Schema.string(),
                                            "pageRef" to Schema.integer()
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            "glossary" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "term" to Schema.string(),
                        "definition" to Schema.string(),
                        "simplifiedExplanation" to Schema.string(),
                        "example" to Schema.string(),
                        "pageReference" to Schema.integer(),
                        "relatedTerms" to Schema.array(items = Schema.string())
                    )
                )
            )
        )
    )

    private val questionBankSchema = Schema.obj(
        properties = mapOf(
            "questions" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "questionText" to Schema.string(),
                        "questionType" to Schema.string(),
                        "difficulty" to Schema.enumeration(listOf("EASY", "MEDIUM", "HARD")),
                        "options" to Schema.array(items = Schema.string()),
                        "correctAnswer" to Schema.string(),
                        "explanation" to Schema.string(),
                        "section" to Schema.string(),
                        "topic" to Schema.string(),
                        "sourcePage" to Schema.integer(),
                        "learningObjective" to Schema.string(),
                        "tags" to Schema.array(items = Schema.string()),
                        "marks" to Schema.integer(),
                        "estimatedSeconds" to Schema.integer()
                    )
                )
            )
        )
    )

    private val flashcardSchema = Schema.obj(
        properties = mapOf(
            "flashcards" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "format" to Schema.enumeration(
                            listOf("TERM_DEF", "QA", "CONCEPT_EXPLAIN", "FORMULA_MEANING", "COMPARISON")
                        ),
                        "front" to Schema.string(),
                        "back" to Schema.string(),
                        "hint" to Schema.string(),
                        "pageReference" to Schema.integer()
                    )
                )
            )
        )
    )

    private val teachBackSchema = Schema.obj(
        properties = mapOf(
            "scorePercent" to Schema.integer(),
            "accuratePoints" to Schema.array(items = Schema.string()),
            "missingPoints" to Schema.array(items = Schema.string()),
            "misconceptions" to Schema.array(items = Schema.string()),
            "coachingFeedback" to Schema.string()
        )
    )

    private val structureModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = structureSchema
            }
        )
    }

    private val chapterIntelModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = chapterIntelSchema
            }
        )
    }

    private val questionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = questionBankSchema
            }
        )
    }

    private val flashcardModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = flashcardSchema
            }
        )
    }

    private val teachBackModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = teachBackSchema
            }
        )
    }

    suspend fun extractTextFromPageImage(bitmap: Bitmap, pageNumber: Int): Result<String> {
        val prompt = "Transcribe all educational text, headings, definitions, tables, equations, and diagram labels from textbook page $pageNumber accurately. Preserve structure with markdown headings and bullet points. Do not invent text that is not visible."
        return try {
            val response = withTimeout(AI_TIMEOUT_MS) {
                generalModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) Result.failure(IllegalStateException("No readable text found on page $pageNumber."))
            else Result.success(text)
        } catch (e: TimeoutCancellationException) {
            if (ollamaBaseUrl.isBlank()) {
                Log.e(TAG, "OCR timeout on page $pageNumber", e)
                Result.failure(IllegalStateException("OCR timed out on page $pageNumber."))
            } else {
                runOllamaImageFallback(bitmap, prompt, "ocr_page_$pageNumber")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ollamaBaseUrl.isNotBlank() && isTransientAiFailure(e)) {
                runOllamaImageFallback(bitmap, prompt, "ocr_page_$pageNumber")
            } else {
                Log.e(TAG, "OCR failed on page $pageNumber", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun runOllamaImageFallback(
        bitmap: Bitmap,
        prompt: String,
        taskTag: String
    ): Result<String> {
        return try {
            Log.w(TAG, "$taskTag primary provider failed; trying configured Ollama vision fallback.")
            if (ollamaVisionModel.isBlank()) {
                return Result.failure(IllegalStateException("No Ollama vision model is configured for OCR fallback."))
            }
            val provider = OllamaTextProvider(modelId = ollamaVisionModel, baseUrl = ollamaBaseUrl)
            val response = withTimeout(110_000L) {
                provider.generateFromImage(bitmap, AiTextRequest(prompt = prompt, taskTag = taskTag))
            }
            Result.success(response.text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    suspend fun analyzeBookStructure(
        bookId: String,
        fallbackTitle: String,
        pages: List<BookPageContent>
    ): Result<DetectedBookStructure> {
        return try {
            val totalPages = pages.size.coerceAtLeast(1)
            val excerpt = buildPageContextSnippet(pages, maxChars = 18000)
            val prompt = """
                Analyze this uploaded textbook / study material ($totalPages total pages, filename/title hint: "$fallbackTitle").
                Preserve exact page numbers [Page X] from the source below. Never fabricate page ranges outside 1..$totalPages.
                Identify:
                - Book title, author (or "Study Material" if unknown), academic subject
                - Concise 1-paragraph overview
                - Consolidated formulaSheet (if formulas/equations exist, otherwise empty string)
                - Logical chapters covering pages 1 to $totalPages with realistic startPage, endPage, and estimatedStudyMinutes.
                
                SOURCE PAGES:
                $excerpt
            """.trimIndent()

            val jsonStr = generateStructuredTextWithFallback(prompt, "book_structure") {
                structureModel.generateContent(prompt).text ?: error("Empty structure response from AI.")
            }
            val json = JSONObject(jsonStr)
            val arr = json.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val start = obj.optInt("startPage", 1).coerceIn(1, totalPages).toLong()
                val end = obj.optInt("endPage", totalPages).coerceIn(start.toInt(), totalPages).toLong()
                Chapter(
                    bookId = bookId,
                    chapterNumber = obj.optInt("chapterNumber", i + 1).toLong(),
                    title = obj.optString("title", "Chapter ${i + 1}").ifBlank { "Chapter ${i + 1}" },
                    startPage = start,
                    endPage = end,
                    estimatedStudyMinutes = obj.optInt("estimatedStudyMinutes", 25).coerceIn(5, 300).toLong()
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
                    title = json.optString("title", fallbackTitle).ifBlank { fallbackTitle },
                    author = json.optString("author", ""),
                    subject = json.optString("subject", "General Studies").ifBlank { "General Studies" },
                    overview = json.optString("overview", ""),
                    formulaSheet = json.optString("formulaSheet", ""),
                    chapters = chapters
                )
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Book structure analysis timed out after 60s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "analyzeBookStructure failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateChapterIntelligence(
        bookId: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        difficultyNote: String = "Standard"
    ): Result<Pair<Chapter, List<GlossaryEntry>>> {
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 20000)

            val prompt = """
                You are generating a structured textbook knowledge base for Chapter ${chapter.chapterNumber}: "${chapter.title}" (pages ${chapter.startPage}-${chapter.endPage}).
                Target explanation level: $difficultyNote.
                Strictly ground every fact, definition, formula, date, and pageReference in the provided pages. Do NOT fabricate page numbers outside ${chapter.startPage}..${chapter.endPage}.
                Provide:
                - quickSummary (concise 100-150 words)
                - detailedSummary (thorough multi-paragraph explanation)
                - bulletSummary (clean bullet points)
                - examRevisionSummary (high-yield exam points & common traps)
                - learningObjectives, mainTopics, prerequisites, keyConcepts, importantDefinitions, importantFacts, importantDates, formulas, examples, misconceptions, comparisons, difficultSections, relatedChapters
                - conceptMap: hierarchical tree showing concept relationships with accurate pageRef
                - glossary: up to 12 key terms with definition, simplifiedExplanation, example, pageReference, relatedTerms
                
                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val json = JSONObject(
                generateStructuredTextWithFallback(prompt, "chapter_intelligence") {
                    chapterIntelModel.generateContent(prompt).text ?: error("Empty chapter intelligence response.")
                }
            )
            val conceptMapStr = json.optJSONObject("conceptMap")?.toString().orEmpty()

            val updatedChapter = chapter.copy(
                quickSummary = json.optString("quickSummary", ""),
                detailedSummary = json.optString("detailedSummary", ""),
                bulletSummary = json.optString("bulletSummary", ""),
                examRevisionSummary = json.optString("examRevisionSummary", ""),
                learningObjectives = json.optStringList("learningObjectives"),
                mainTopics = json.optStringList("mainTopics"),
                prerequisites = json.optStringList("prerequisites"),
                keyConcepts = json.optStringList("keyConcepts"),
                importantDefinitions = json.optStringList("importantDefinitions"),
                importantFacts = json.optStringList("importantFacts"),
                importantDates = json.optStringList("importantDates"),
                formulas = json.optStringList("formulas"),
                examples = json.optStringList("examples"),
                misconceptions = json.optStringList("misconceptions"),
                comparisons = json.optStringList("comparisons"),
                difficultSections = json.optStringList("difficultSections"),
                relatedChapters = json.optStringList("relatedChapters"),
                conceptMapJson = conceptMapStr
            )

            val glossaryArr = json.optJSONArray("glossary") ?: JSONArray()
            val glossaryEntries = (0 until glossaryArr.length()).mapNotNull { idx ->
                val obj = glossaryArr.getJSONObject(idx)
                val term = obj.optString("term", "").trim()
                val def = obj.optString("definition", "").trim()
                if (term.isBlank() || def.isBlank()) null
                else GlossaryEntry(
                    bookId = bookId,
                    chapterNumber = chapter.chapterNumber,
                    term = term,
                    definition = def,
                    simplifiedExplanation = obj.optString("simplifiedExplanation", ""),
                    example = obj.optString("example", ""),
                    pageReference = obj.optInt("pageReference", chapter.startPage.toInt())
                        .coerceIn(1, pages.size.coerceAtLeast(1)).toLong(),
                    relatedTerms = obj.optStringList("relatedTerms")
                )
            }

            Result.success(updatedChapter to glossaryEntries)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Chapter intelligence timed out after 60s."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateChapterIntelligence failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateQuestionsForChapter(
        bookId: String,
        bookTitle: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        requestedTypes: List<String>,
        count: Int = 8,
        difficultyHint: String = "Mixed (Easy, Medium, Hard)"
    ): Result<List<QuestionItem>> {
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 18000)

            val prompt = """
                Generate $count diverse, non-repetitive assessment questions grounded in Chapter ${chapter.chapterNumber}: "${chapter.title}" of "$bookTitle".
                Requested question types (use these exact type strings): ${requestedTypes.joinToString(", ")}.
                Difficulty mix: $difficultyHint.
                Accuracy rules:
                - For MCQ, provide exactly 4 distinct options where ONLY ONE is unambiguously correct, and set correctAnswer equal to that exact option text.
                - For TRUE_FALSE, set options to ["True", "False"] and correctAnswer to "True" or "False".
                - For mathematical/calculation questions, verify arithmetic carefully and show worked steps in explanation.
                - Cite the exact sourcePage from [Page X] in the text. Never fabricate page numbers.
                
                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val json = JSONObject(
                generateStructuredTextWithFallback(prompt, "question_generation") {
                    questionModel.generateContent(prompt).text ?: error("Empty question response.")
                }
            )
            val arr = json.optJSONArray("questions") ?: JSONArray()
            val maxPage = pages.size.coerceAtLeast(1)

            val items = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val qText = obj.optString("questionText", "").trim()
                val ans = obj.optString("correctAnswer", "").trim()
                if (qText.isBlank() || ans.isBlank()) null
                else QuestionItem(
                    bookId = bookId,
                    bookTitle = bookTitle,
                    chapterNumber = chapter.chapterNumber,
                    section = obj.optString("section", chapter.title),
                    topic = obj.optString("topic", chapter.title),
                    sourcePage = obj.optInt("sourcePage", chapter.startPage.toInt()).coerceIn(1, maxPage).toLong(),
                    difficulty = obj.optString("difficulty", "MEDIUM").uppercase().let {
                        if (it in listOf("EASY", "MEDIUM", "HARD")) it else "MEDIUM"
                    },
                    questionType = obj.optString("questionType", "MCQ").uppercase(),
                    questionText = qText,
                    options = obj.optStringList("options"),
                    correctAnswer = ans,
                    explanation = obj.optString("explanation", ""),
                    learningObjective = obj.optString("learningObjective", ""),
                    tags = obj.optStringList("tags"),
                    marks = obj.optInt("marks", 2).coerceIn(1, 25).toLong(),
                    estimatedSeconds = obj.optInt("estimatedSeconds", 60).coerceIn(15, 900).toLong(),
                    aiGenerated = true
                )
            }
            Result.success(items)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Question generation timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateQuestionsForChapter failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateFlashcardsForChapter(
        bookId: String,
        chapter: Chapter,
        pages: List<BookPageContent>,
        count: Int = 10
    ): Result<List<Flashcard>> {
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(6) }
            val contextText = buildPageContextSnippet(chapterPages, maxChars = 16000)

            val prompt = """
                Create $count high-retention spaced-repetition flashcards for Chapter ${chapter.chapterNumber}: "${chapter.title}".
                Use varied formats: TERM_DEF, QA, CONCEPT_EXPLAIN, FORMULA_MEANING, COMPARISON.
                Keep fronts concise and backs crystal clear. Cite exact pageReference from [Page X].
                
                SOURCE PAGES:
                $contextText
            """.trimIndent()

            val json = JSONObject(
                generateStructuredTextWithFallback(prompt, "flashcard_generation") {
                    flashcardModel.generateContent(prompt).text ?: error("Empty flashcard response.")
                }
            )
            val arr = json.optJSONArray("flashcards") ?: JSONArray()
            val maxPage = pages.size.coerceAtLeast(1)

            val cards = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val front = obj.optString("front", "").trim()
                val back = obj.optString("back", "").trim()
                if (front.isBlank() || back.isBlank()) null
                else Flashcard(
                    bookId = bookId,
                    chapterNumber = chapter.chapterNumber,
                    deckName = "Ch. ${chapter.chapterNumber}: ${chapter.title.take(40)}",
                    format = obj.optString("format", "TERM_DEF"),
                    front = front,
                    back = back,
                    hint = obj.optString("hint", ""),
                    pageReference = obj.optInt("pageReference", chapter.startPage.toInt()).coerceIn(1, maxPage).toLong()
                )
            }
            Result.success(cards)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Flashcard generation timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateFlashcardsForChapter failed", e)
            Result.failure(e)
        }
    }

    fun streamTutorReply(
        bookTitle: String,
        chapter: Chapter?,
        currentPage: BookPageContent?,
        mode: TutorMode,
        historyPairs: List<Pair<String, String>>,
        userQuery: String,
        pageBitmap: Bitmap? = null
    ): Flow<String> = flow {
        val systemContext = """
            You are SŌRA's Contextual Academic Tutor.
            Current Textbook: "$bookTitle"
            Current Chapter: ${chapter?.let { "Chapter ${it.chapterNumber}: ${it.title}" } ?: "Overview"}
            Current Page Number: ${currentPage?.pageNumber ?: 1}
            Active Tutor Mode: ${mode.label} (${mode.description})
            
            STRICT ACCURACY & CITATION RULES:
            1. Ground answers primarily in the textbook page excerpt below.
            2. Clearly label "[From Textbook — p. X]" vs "[Supplementary Academic Context]".
            3. NEVER fabricate page numbers or citations not present in the source excerpt.
            4. Follow the active mode faithfully (e.g., Socratic asks guiding questions first; Hint gives progressive clues; Examiner grades the student's response).
            
            CURRENT PAGE TEXT ([Page ${currentPage?.pageNumber ?: 1}]):
            ${currentPage?.text?.take(5000) ?: "No text extracted for this page."}
            
            CHAPTER SUMMARY CONTEXT:
            ${chapter?.quickSummary?.take(1500) ?: ""}
        """.trimIndent()

        val conversationText = buildString {
            append(systemContext).append("\n\n")
            if (historyPairs.isNotEmpty()) {
                append("RECENT CONVERSATION:\n")
                historyPairs.takeLast(8).forEach { (role, text) ->
                    append("$role: $text\n")
                }
                append("\n")
            }
            append("STUDENT REQUEST: $userQuery")
        }

        val accumulated = StringBuilder()
        try {
            withTimeout(AI_TIMEOUT_MS) {
                val stream = if (pageBitmap != null) {
                    generalModel.generateContentStream(
                        content {
                            image(pageBitmap)
                            text(conversationText)
                        }
                    )
                } else {
                    generalModel.generateContentStream(conversationText)
                }
                stream.collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            if (pageBitmap != null || accumulated.isNotEmpty() || ollamaBaseUrl.isBlank()) throw timeout
            Log.w(TAG, "Tutor stream timed out; trying configured Ollama text fallback.")
            val fallback = generateOllamaTextFallback(conversationText, "tutor")
            accumulated.append(fallback)
            emit(accumulated.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (pageBitmap != null || accumulated.isNotEmpty() || ollamaBaseUrl.isBlank() || !isTransientAiFailure(failure)) {
                throw failure
            }
            Log.w(TAG, "Tutor stream failed transiently; trying configured Ollama text fallback.")
            val fallback = generateOllamaTextFallback(conversationText, "tutor")
            accumulated.append(fallback)
            emit(accumulated.toString())
        }
    }

    suspend fun runReadingAssistant(
        action: String,
        selectedText: String,
        pageNumber: Int,
        bookTitle: String
    ): Result<String> {
        return try {
            val prompt = """
                Book: "$bookTitle", Page $pageNumber.
                Student selected this passage:
                "$selectedText"
                
                Perform action: $action (e.g., Simplify, Explain, Key Definitions, Worked Examples, Translate to Spanish/Arabic/French if requested, Practice Questions, or Concise Summary).
                Keep textbook terminology intact and clearly separate textbook facts from supplementary explanation.
            """.trimIndent()
            val out = withTimeout(TEXT_ROUTER_TIMEOUT_MS) {
                textRouter.generate(AiTextRequest(prompt = prompt, taskTag = "reading_assistant")).text.trim()
            }
            if (out.isBlank()) Result.failure(IllegalStateException("Empty assistant response."))
            else Result.success(out)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Reading assistant timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun evaluateTeachBack(
        conceptTitle: String,
        referenceText: String,
        studentExplanation: String
    ): Result<TeachBackFeedback> {
        return try {
            val prompt = """
                Evaluate this student's Teach-Back explanation for "$conceptTitle".
                REFERENCE TEXTBOOK MATERIAL:
                ${referenceText.take(6000)}
                
                STUDENT EXPLANATION:
                $studentExplanation
                
                Score accuracy 0..100, list accurate points, missing key information, any misconceptions, and constructive coaching feedback.
            """.trimIndent()
            val json = JSONObject(
                generateStructuredTextWithFallback(prompt, "teach_back") {
                    teachBackModel.generateContent(prompt).text ?: error("Empty teach-back response.")
                }
            )
            Result.success(
                TeachBackFeedback(
                    scorePercent = json.optInt("scorePercent", 0).coerceIn(0, 100),
                    accuratePoints = json.optStringList("accuratePoints"),
                    missingPoints = json.optStringList("missingPoints"),
                    misconceptions = json.optStringList("misconceptions"),
                    coachingFeedback = json.optString("coachingFeedback", "")
                )
            )
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Teach-back evaluation timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun solveEquationWithSteps(equationInput: String): Result<String> {
        return try {
            val prompt = """
                Solve the following academic/mathematical/scientific equation or word problem with clear step-by-step reasoning, showing intermediate steps, units, and verification of the final answer:
                $equationInput
            """.trimIndent()
            val text = withTimeout(TEXT_ROUTER_TIMEOUT_MS) {
                textRouter.generate(AiTextRequest(prompt = prompt, taskTag = "equation_solver")).text.trim()
            }
            if (text.isBlank()) Result.failure(IllegalStateException("Empty solution returned."))
            else Result.success(text)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Equation solver timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateComparisonOrGapAnalysis(
        mode: String,
        chapter: Chapter,
        pages: List<BookPageContent>
    ): Result<String> {
        return try {
            val chapterPages = pages.filter {
                it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt()
            }.ifEmpty { pages.take(5) }
            val snippet = buildPageContextSnippet(chapterPages, 12000)
            val prompt = """
                For Chapter ${chapter.chapterNumber}: "${chapter.title}", generate: $mode.
                Use clean markdown tables or bulleted prerequisite checklists grounded in these pages:
                $snippet
            """.trimIndent()
            val text = withTimeout(TEXT_ROUTER_TIMEOUT_MS) {
                textRouter.generate(AiTextRequest(prompt = prompt, taskTag = "chapter_analysis")).text.trim()
            }
            if (text.isBlank()) Result.failure(IllegalStateException("Empty analysis returned."))
            else Result.success(text)
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Analysis timed out."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
        }
    }
}
