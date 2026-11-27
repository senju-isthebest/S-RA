package com.example.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.SoraSettings
import com.example.core.UserPreferences
import com.example.data.ai.SoraAiService
import com.example.data.ai.TeachBackFeedback
import com.example.data.local.DocumentProcessor
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.CitationEntry
import com.example.data.model.ExamPreset
import com.example.data.model.ExamRecord
import com.example.data.model.Flashcard
import com.example.data.model.FocusSession
import com.example.data.model.GlossaryEntry
import com.example.data.model.PlannerTask
import com.example.data.model.QuestionItem
import com.example.data.model.SmartRevisionItem
import com.example.data.model.StudyNote
import com.example.data.model.Subject
import com.example.data.model.TutorMessage
import com.example.data.model.TutorMode
import com.example.data.model.UserProfile
import com.example.data.repository.SoraRepository
import com.example.ui.theme.SoraThemeMode
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private const val TAG = "SoraViewModel"

data class IngestionProgressState(
    val active: Boolean = false,
    val processedPages: Int = 0,
    val totalPages: Int = 0,
    val statusLabel: String = ""
)

data class FocusTimerState(
    val running: Boolean = false,
    val mode: String = "POMODORO", // POMODORO, SHORT_BREAK, LONG_BREAK, STOPWATCH, CUSTOM
    val remainingSeconds: Int = 25 * 60,
    val elapsedSeconds: Int = 0,
    val targetSeconds: Int = 25 * 60,
    val subjectName: String = "General",
    val bookId: String = "",
    val chapterNumber: Long = 0L,
    val goalText: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class SoraViewModel(
    private val repository: SoraRepository,
    private val aiService: SoraAiService,
    val documentProcessor: DocumentProcessor,
    private val userPreferences: UserPreferences,
    val currentUserId: String
) : ViewModel() {

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _ingestionProgress = MutableStateFlow(IngestionProgressState())
    val ingestionProgress: StateFlow<IngestionProgressState> = _ingestionProgress.asStateFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    private val _loadedBookPages = MutableStateFlow<List<BookPageContent>>(emptyList())
    val loadedBookPages: StateFlow<List<BookPageContent>> = _loadedBookPages.asStateFlow()

    private val _aiBusyLabel = MutableStateFlow<String?>(null)
    val aiBusyLabel: StateFlow<String?> = _aiBusyLabel.asStateFlow()

    private val _tutorStreamingText = MutableStateFlow<String?>(null)
    val tutorStreamingText: StateFlow<String?> = _tutorStreamingText.asStateFlow()

    private val _readingAssistantResult = MutableStateFlow<String?>(null)
    val readingAssistantResult: StateFlow<String?> = _readingAssistantResult.asStateFlow()

    private val _teachBackResult = MutableStateFlow<TeachBackFeedback?>(null)
    val teachBackResult: StateFlow<TeachBackFeedback?> = _teachBackResult.asStateFlow()

    private val _comparisonOrGapResult = MutableStateFlow<String?>(null)
    val comparisonOrGapResult: StateFlow<String?> = _comparisonOrGapResult.asStateFlow()

    private val _equationSolution = MutableStateFlow<String?>(null)
    val equationSolution: StateFlow<String?> = _equationSolution.asStateFlow()

    private val _focusState = MutableStateFlow(FocusTimerState())
    val focusState: StateFlow<FocusTimerState> = _focusState.asStateFlow()
    private var timerJob: Job? = null

    val settings: StateFlow<SoraSettings> = userPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), SoraSettings())

    val profile: StateFlow<UserProfile?> = repository.observeProfile(currentUserId)
        .safeCatchSingle("profile", null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val subjects: StateFlow<List<Subject>> = repository.observeSubjects(currentUserId)
        .safeCatch("subjects")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val books: StateFlow<List<Book>> = repository.observeBooks(currentUserId)
        .safeCatch("books")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val selectedBookChapters: StateFlow<List<Chapter>> = _selectedBookId
        .flatMapLatest { bookId ->
            if (bookId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeChapters(currentUserId, bookId).safeCatch("chapters")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val selectedBookGlossary: StateFlow<List<GlossaryEntry>> = _selectedBookId
        .flatMapLatest { bookId ->
            if (bookId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeGlossary(currentUserId, bookId).safeCatch("glossary")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val questions: StateFlow<List<QuestionItem>> = repository.observeQuestions(currentUserId)
        .safeCatch("questions")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val flashcards: StateFlow<List<Flashcard>> = repository.observeFlashcards(currentUserId)
        .safeCatch("flashcards")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val exams: StateFlow<List<ExamRecord>> = repository.observeExams(currentUserId)
        .safeCatch("exams")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val notes: StateFlow<List<StudyNote>> = repository.observeNotes(currentUserId)
        .safeCatch("notes")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val plannerTasks: StateFlow<List<PlannerTask>> = repository.observePlannerTasks(currentUserId)
        .safeCatch("plannerTasks")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val focusSessions: StateFlow<List<FocusSession>> = repository.observeFocusSessions(currentUserId)
        .safeCatch("focusSessions")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val tutorMessages: StateFlow<List<TutorMessage>> = repository.observeTutorMessages(currentUserId)
        .safeCatch("tutorMessages")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val citations: StateFlow<List<CitationEntry>> = repository.observeCitations(currentUserId)
        .safeCatch("citations")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch {
            repository.ensureUserProfile()
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun setThemeMode(mode: SoraThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(mode) }
    }

    fun updatePomodoroSettings(work: Int, shortBreak: Int, longBreak: Int) {
        viewModelScope.launch {
            userPreferences.setPomodoroDurations(work, shortBreak, longBreak)
            if (!_focusState.value.running && _focusState.value.mode == "POMODORO") {
                _focusState.value = _focusState.value.copy(
                    remainingSeconds = work * 60,
                    targetSeconds = work * 60
                )
            }
        }
    }

    fun updateDashboardOrder(sections: List<String>) {
        viewModelScope.launch {
            repository.updateDashboardSections(sections)
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Failed to save dashboard layout." }
        }
    }

    fun openBookWorkspace(book: Book) {
        _selectedBookId.value = book.id
        viewModelScope.launch {
            _loadedBookPages.value = documentProcessor.loadPages(book.localBookId)
        }
    }

    fun closeBookWorkspace() {
        _selectedBookId.value = null
        _loadedBookPages.value = emptyList()
    }

    // --- SMART DOCUMENT UPLOAD & AI TEXTBOOK INTELLIGENCE ---
    fun uploadTextbookFromUri(
        uri: Uri,
        customTitle: String,
        subjectName: String,
        semester: String,
        folder: String
    ) {
        viewModelScope.launch {
            val localId = "book_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            _ingestionProgress.value = IngestionProgressState(true, 0, 1, "Reading file...")
            val result = documentProcessor.ingestFromUri(uri, localId) { done, total, label ->
                _ingestionProgress.value = IngestionProgressState(true, done, total, label)
            }
            finalizeBookIngestion(localId, customTitle, subjectName, semester, folder, result)
        }
    }

    fun uploadTextbookFromText(
        title: String,
        subjectName: String,
        semester: String,
        folder: String,
        rawText: String
    ) {
        viewModelScope.launch {
            val localId = "book_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            _ingestionProgress.value = IngestionProgressState(true, 0, 1, "Processing study text...")
            val result = documentProcessor.ingestPlainText(
                localBookId = localId,
                fileName = "${title.take(30).replace(" ", "_")}.txt",
                sourceType = "TEXT",
                rawText = rawText
            ) { done, total, label ->
                _ingestionProgress.value = IngestionProgressState(true, done, total, label)
            }
            finalizeBookIngestion(localId, title, subjectName, semester, folder, result)
        }
    }

    private suspend fun finalizeBookIngestion(
        localId: String,
        customTitle: String,
        subjectName: String,
        semester: String,
        folder: String,
        result: com.example.data.local.IngestionResult
    ) {
        if (result.pages.isEmpty() || result.extractionStatus == "FAILED") {
            val failedBook = Book(
                title = customTitle.ifBlank { result.fileName },
                subjectName = subjectName.ifBlank { "General" },
                semester = semester,
                folder = folder,
                sourceType = result.sourceType,
                fileName = result.fileName,
                localBookId = localId,
                totalPages = result.pages.size.coerceAtLeast(1).toLong(),
                processedPages = 0L,
                failedPages = result.failedPages.map { it.toLong() },
                extractionStatus = "FAILED",
                extractionError = result.extractionError
            )
            repository.createBookWithId(localId, failedBook)
            _ingestionProgress.value = IngestionProgressState(false)
            _bannerMessage.value = result.extractionError.ifBlank { "Document extraction failed." }
            return
        }

        _ingestionProgress.value = IngestionProgressState(
            true,
            result.pages.size,
            result.pages.size,
            "AI analyzing chapters & structure..."
        )
        val fallbackTitle = customTitle.ifBlank { result.fileName.substringBeforeLast(".") }
        val structureRes = aiService.analyzeBookStructure(localId, fallbackTitle, result.pages)

        val structure = structureRes.getOrNull()
        val finalTitle = customTitle.ifBlank { structure?.title ?: fallbackTitle }
        val finalSubject = subjectName.ifBlank { structure?.subject ?: "General" }

        val book = Book(
            title = finalTitle,
            author = structure?.author.orEmpty(),
            subjectName = finalSubject,
            semester = semester.ifBlank { "Fall 2026" },
            folder = folder.ifBlank { "Core" },
            sourceType = result.sourceType,
            fileName = result.fileName,
            localBookId = localId,
            totalPages = result.pages.size.toLong(),
            processedPages = result.pages.count { it.text.isNotBlank() }.toLong(),
            currentPage = 1L,
            failedPages = result.failedPages.map { it.toLong() },
            extractionStatus = result.extractionStatus,
            extractionError = result.extractionError,
            overview = structure?.overview.orEmpty(),
            formulaSheet = structure?.formulaSheet.orEmpty()
        )

        repository.createBookWithId(localId, book).onFailure {
            _ingestionProgress.value = IngestionProgressState(false)
            _bannerMessage.value = "Failed to save book: ${it.localizedMessage}"
            return
        }

        val chapters = structure?.chapters ?: listOf(
            Chapter(
                bookId = localId,
                chapterNumber = 1L,
                title = "Chapter 1: $finalTitle",
                startPage = 1L,
                endPage = result.pages.size.toLong(),
                estimatedStudyMinutes = 30L
            )
        )

        val savedChapters = mutableListOf<Chapter>()
        chapters.forEach { ch ->
            val idRes = repository.saveChapter(localId, ch)
            if (idRes.isSuccess) savedChapters.add(ch.copy(id = idRes.getOrThrow()))
        }

        // Auto-generate rich intelligence + starter questions + flashcards for Chapter 1
        val firstChapter = savedChapters.firstOrNull()
        if (firstChapter != null) {
            _ingestionProgress.value = IngestionProgressState(
                true, result.pages.size, result.pages.size, "Generating Chapter 1 summaries, glossary & questions..."
            )
            generateAllResourcesForChapterInternal(book.copy(id = localId), firstChapter, result.pages)
        }

        _ingestionProgress.value = IngestionProgressState(false)
        _selectedBookId.value = localId
        _loadedBookPages.value = result.pages
        _bannerMessage.value = if (result.extractionStatus == "PARTIAL") {
            "Imported with ${result.failedPages.size} failed page(s). You can retry failed pages anytime."
        } else {
            "\"$finalTitle\" analyzed into ${savedChapters.size} chapter(s)."
        }
    }

    fun retryFailedPages(book: Book) {
        if (book.failedPages.isEmpty()) return
        viewModelScope.launch {
            _ingestionProgress.value = IngestionProgressState(true, 0, book.failedPages.size, "Retrying failed pages...")
            val res = documentProcessor.retryFailedPages(
                localBookId = book.localBookId,
                fileName = book.fileName,
                sourceType = book.sourceType,
                failedPageNumbers = book.failedPages.map { it.toInt() }
            ) { done, total, label ->
                _ingestionProgress.value = IngestionProgressState(true, done, total, label)
            }
            repository.updateBook(
                book.id,
                mapOf(
                    "processedPages" to res.pages.count { it.text.isNotBlank() }.toLong(),
                    "failedPages" to res.failedPages.map { it.toLong() },
                    "extractionStatus" to res.extractionStatus,
                    "extractionError" to res.extractionError
                )
            )
            if (_selectedBookId.value == book.id) {
                _loadedBookPages.value = res.pages
            }
            _ingestionProgress.value = IngestionProgressState(false)
            _bannerMessage.value = if (res.failedPages.isEmpty()) "All pages recovered successfully." else "${res.failedPages.size} page(s) still failed."
        }
    }

    fun generateChapterResources(book: Book, chapter: Chapter, difficultyLevel: String = "Standard") {
        viewModelScope.launch {
            val pages = documentProcessor.loadPages(book.localBookId)
            if (pages.isEmpty()) {
                _bannerMessage.value = "No extracted pages available for this book."
                return@launch
            }
            _aiBusyLabel.value = "Generating AI summaries, concept map & questions for Ch. ${chapter.chapterNumber}..."
            generateAllResourcesForChapterInternal(book, chapter, pages, difficultyLevel)
            _aiBusyLabel.value = null
        }
    }

    private suspend fun generateAllResourcesForChapterInternal(
        book: Book,
        chapter: Chapter,
        pages: List<BookPageContent>,
        difficultyLevel: String = "Standard"
    ) {
        val intelRes = aiService.generateChapterIntelligence(book.id, chapter, pages, difficultyLevel)
        intelRes.onSuccess { (enrichedChapter, glossary) ->
            repository.saveChapter(book.id, enrichedChapter)
            if (glossary.isNotEmpty()) {
                repository.addGlossaryEntries(book.id, glossary)
            }
        }.onFailure {
            _bannerMessage.value = "Chapter intelligence error: ${it.localizedMessage}"
        }

        val qRes = aiService.generateQuestionsForChapter(
            bookId = book.id,
            bookTitle = book.title,
            chapter = chapter,
            pages = pages,
            requestedTypes = listOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "APPLICATION", "COMPARE_CONTRAST", "CALCULATION"),
            count = 8
        )
        qRes.onSuccess { generated ->
            repository.addQuestions(generated)
        }.onFailure {
            _bannerMessage.value = "Question generation error: ${it.localizedMessage}"
        }

        val fcRes = aiService.generateFlashcardsForChapter(book.id, chapter, pages, count = 8)
        fcRes.onSuccess { cards ->
            repository.addFlashcards(cards)
        }.onFailure {
            _bannerMessage.value = "Flashcard generation error: ${it.localizedMessage}"
        }
    }

    fun generateMoreQuestions(
        book: Book,
        chapter: Chapter,
        types: List<String>,
        count: Int,
        difficultyHint: String
    ) {
        viewModelScope.launch {
            val pages = documentProcessor.loadPages(book.localBookId)
            _aiBusyLabel.value = "Generating $count custom questions..."
            aiService.generateQuestionsForChapter(book.id, book.title, chapter, pages, types, count, difficultyHint)
                .onSuccess { items ->
                    repository.addQuestions(items)
                    _bannerMessage.value = "Added ${items.size} questions to Question Bank."
                }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Question generation failed." }
            _aiBusyLabel.value = null
        }
    }

    // --- BOOK OPERATIONS ---
    fun updateBookReadingPage(book: Book, page: Int) {
        val safePage = page.coerceIn(1, book.totalPages.toInt().coerceAtLeast(1))
        viewModelScope.launch {
            repository.updateBook(book.id, mapOf("currentPage" to safePage.toLong()))
        }
    }

    fun toggleBookPageBookmark(book: Book, page: Int) {
        val updated = if (book.bookmarkedPages.contains(page.toLong())) {
            book.bookmarkedPages - page.toLong()
        } else {
            (book.bookmarkedPages + page.toLong()).distinct().sorted()
        }
        viewModelScope.launch {
            repository.updateBook(book.id, mapOf("bookmarkedPages" to updated))
        }
    }

    fun addBookHighlight(book: Book, page: Int, highlightText: String) {
        if (highlightText.isBlank()) return
        val map = try { JSONObject(book.highlightsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val key = page.toString()
        val existing = map.optString(key, "")
        val combined = if (existing.isBlank()) highlightText.trim() else "$existing\n• ${highlightText.trim()}"
        map.put(key, combined.take(1500))
        viewModelScope.launch {
            repository.updateBook(book.id, mapOf("highlightsJson" to map.toString()))
        }
    }

    fun renameBook(bookId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            repository.updateBook(bookId, mapOf("title" to newTitle.trim().take(160)))
        }
    }

    fun toggleArchiveBook(book: Book) {
        viewModelScope.launch {
            repository.updateBook(book.id, mapOf("archived" to !book.archived))
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            if (_selectedBookId.value == bookId) closeBookWorkspace()
            repository.deleteBook(bookId)
        }
    }

    fun addSubject(name: String, semester: String, folder: String, targetGrade: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.addSubject(
                Subject(name = name.trim(), semester = semester.trim(), folder = folder.trim(), targetGrade = targetGrade.trim())
            )
        }
    }

    fun setChapterGoal(bookId: String, chapterId: String, goal: String) {
        viewModelScope.launch {
            repository.updateChapterFields(bookId, chapterId, mapOf("userGoal" to goal.take(500)))
        }
    }

    // --- QUESTION BANK & PRACTICE ---
    fun recordQuestionAttempt(question: QuestionItem, isCorrect: Boolean) {
        viewModelScope.launch {
            repository.recordQuestionAttempt(question, isCorrect)
            recalculateBookAndChapterMastery(question.bookId, question.chapterNumber)
        }
    }

    fun toggleQuestionBookmark(q: QuestionItem) {
        viewModelScope.launch { repository.updateQuestion(q.id, mapOf("bookmarked" to !q.bookmarked)) }
    }

    fun toggleQuestionHidden(q: QuestionItem) {
        viewModelScope.launch { repository.updateQuestion(q.id, mapOf("hidden" to !q.hidden)) }
    }

    fun updateQuestionNote(q: QuestionItem, note: String) {
        viewModelScope.launch { repository.updateQuestion(q.id, mapOf("personalNote" to note.take(2000))) }
    }

    fun reportQuestion(q: QuestionItem, reason: String) {
        viewModelScope.launch {
            repository.updateQuestion(q.id, mapOf("reported" to true, "reportReason" to reason.take(400)))
            _bannerMessage.value = "Question flagged for review."
        }
    }

    fun editQuestion(q: QuestionItem, newText: String, newAnswer: String, newExplanation: String) {
        viewModelScope.launch {
            repository.updateQuestion(
                q.id,
                mapOf(
                    "questionText" to newText.take(2500),
                    "correctAnswer" to newAnswer.take(2000),
                    "explanation" to newExplanation.take(3000)
                )
            )
        }
    }

    fun setQuestionCollection(q: QuestionItem, collection: String) {
        viewModelScope.launch {
            repository.updateQuestion(q.id, mapOf("collectionName" to collection.take(80)))
        }
    }

    fun markMistakeResolved(q: QuestionItem) {
        viewModelScope.launch {
            repository.updateQuestion(q.id, mapOf("mistakeResolved" to true))
        }
    }

    // --- FLASHCARDS ---
    fun rateFlashcard(card: Flashcard, rating: String) {
        viewModelScope.launch {
            repository.rateFlashcardSm2(card, rating)
            recalculateBookAndChapterMastery(card.bookId, card.chapterNumber)
        }
    }

    fun toggleFlashcardBookmark(card: Flashcard) {
        viewModelScope.launch { repository.updateFlashcard(card.id, mapOf("bookmarked" to !card.bookmarked)) }
    }

    fun createCustomFlashcard(bookId: String, chapterNum: Long, deckName: String, front: String, back: String, format: String) {
        if (front.isBlank() || back.isBlank()) return
        viewModelScope.launch {
            repository.addFlashcards(
                listOf(
                    Flashcard(
                        bookId = bookId.ifBlank { "custom" },
                        chapterNumber = chapterNum,
                        deckName = deckName.ifBlank { "Custom Deck" },
                        format = format,
                        front = front.trim(),
                        back = back.trim()
                    )
                )
            )
        }
    }

    // --- EXAMS ---
    fun createAndStartExam(
        title: String,
        preset: ExamPreset,
        selectedQuestions: List<QuestionItem>,
        durationMinutes: Int,
        allowRevisit: Boolean,
        showExplanationEach: Boolean,
        onCreated: (String) -> Unit
    ) {
        if (selectedQuestions.isEmpty()) {
            _bannerMessage.value = "No matching questions available for this exam configuration."
            return
        }
        viewModelScope.launch {
            val totalMarks = selectedQuestions.sumOf { it.marks }.coerceAtLeast(1L)
            val exam = ExamRecord(
                title = title.ifBlank { preset.label },
                preset = preset.name,
                bookIds = selectedQuestions.map { it.bookId }.distinct().take(20),
                questionIds = selectedQuestions.map { it.id }.take(200),
                durationMinutes = durationMinutes.toLong(),
                totalMarks = totalMarks,
                allowRevisit = allowRevisit,
                showExplanationEach = showExplanationEach,
                status = "IN_PROGRESS",
                unansweredCount = selectedQuestions.size.toLong()
            )
            repository.createExam(exam)
                .onSuccess { id -> onCreated(id) }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Could not start exam." }
        }
    }

    fun submitExam(
        exam: ExamRecord,
        examQuestions: List<QuestionItem>,
        answersByQuestionId: Map<String, String>,
        flaggedIds: Set<String>,
        timeSpentSeconds: Int
    ) {
        viewModelScope.launch {
            var score = 0L
            var correct = 0L
            var incorrect = 0L
            var unanswered = 0L
            val chapterMap = mutableMapOf<Long, Pair<Int, Int>>() // ch -> (correct, total)
            val difficultyMap = mutableMapOf<String, Pair<Int, Int>>()

            examQuestions.forEach { q ->
                val userAns = answersByQuestionId[q.id]?.trim().orEmpty()
                val chStats = chapterMap[q.chapterNumber] ?: (0 to 0)
                val diffStats = difficultyMap[q.difficulty] ?: (0 to 0)
                if (userAns.isBlank()) {
                    unanswered++
                    chapterMap[q.chapterNumber] = chStats.first to (chStats.second + 1)
                    difficultyMap[q.difficulty] = diffStats.first to (diffStats.second + 1)
                } else {
                    val isRight = evaluateAnswerMatch(q, userAns)
                    repository.recordQuestionAttempt(q, isRight)
                    if (isRight) {
                        correct++
                        score += q.marks
                        chapterMap[q.chapterNumber] = (chStats.first + 1) to (chStats.second + 1)
                        difficultyMap[q.difficulty] = (diffStats.first + 1) to (diffStats.second + 1)
                    } else {
                        incorrect++
                        chapterMap[q.chapterNumber] = chStats.first to (chStats.second + 1)
                        difficultyMap[q.difficulty] = diffStats.first to (diffStats.second + 1)
                    }
                }
            }

            val pct = if (exam.totalMarks > 0L) (score.toDouble() / exam.totalMarks.toDouble()) * 100.0 else 0.0
            val analyticsJson = JSONObject().apply {
                val chObj = JSONObject()
                chapterMap.forEach { (ch, pair) -> chObj.put("Ch. $ch", "${pair.first}/${pair.second}") }
                val diffObj = JSONObject()
                difficultyMap.forEach { (d, pair) -> diffObj.put(d, "${pair.first}/${pair.second}") }
                put("byChapter", chObj)
                put("byDifficulty", diffObj)
            }.toString()

            val weakCh = chapterMap.entries
                .filter { it.value.second > 0 && (it.value.first.toDouble() / it.value.second) < 0.7 }
                .map { "Chapter ${it.key}" }
            val recommendation = if (weakCh.isEmpty()) {
                "Strong mastery across all tested chapters. Continue spaced-repetition flashcard reviews."
            } else {
                "Focus revision on ${weakCh.joinToString(", ")} and review missed items in your Mistake Notebook."
            }

            val answersJsonObj = JSONObject()
            answersByQuestionId.forEach { (k, v) -> answersJsonObj.put(k, v) }

            repository.updateExamProgress(
                exam.id,
                mapOf(
                    "status" to "SUBMITTED",
                    "answersJson" to answersJsonObj.toString(),
                    "flaggedQuestionIds" to flaggedIds.toList().take(200),
                    "score" to score,
                    "percentage" to pct,
                    "correctCount" to correct,
                    "incorrectCount" to incorrect,
                    "unansweredCount" to unanswered,
                    "timeSpentSeconds" to timeSpentSeconds.toLong(),
                    "analyticsJson" to analyticsJson,
                    "recommendedRevision" to recommendation
                )
            )
            examQuestions.map { it.bookId to it.chapterNumber }.distinct().forEach { (bId, ch) ->
                recalculateBookAndChapterMastery(bId, ch)
            }
        }
    }

    private fun evaluateAnswerMatch(q: QuestionItem, userAns: String): Boolean {
        val target = q.correctAnswer.trim().lowercase()
        val given = userAns.trim().lowercase()
        if (target == given) return true
        if (q.questionType in listOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "CALCULATION")) {
            return given == target
        }
        // For open-ended short answers, check high keyword overlap
        val keywords = target.split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (keywords.isEmpty()) return given.contains(target)
        val hits = keywords.count { given.contains(it) }
        return hits.toDouble() / keywords.size >= 0.5
    }

    private suspend fun recalculateBookAndChapterMastery(bookId: String, chapterNumber: Long) {
        if (bookId.isBlank()) return
        val bookQuestions = questions.value.filter { it.bookId == bookId }
        val bookCards = flashcards.value.filter { it.bookId == bookId }
        val chQuestions = bookQuestions.filter { it.chapterNumber == chapterNumber }
        val chCards = bookCards.filter { it.chapterNumber == chapterNumber }
        val (chScore, chStatus) = repository.computeChapterMastery(chQuestions, chCards)

        val chapterDoc = selectedBookChapters.value.firstOrNull { it.bookId == bookId && it.chapterNumber == chapterNumber }
        if (chapterDoc != null && chapterDoc.id.isNotBlank()) {
            repository.updateChapterFields(
                bookId,
                chapterDoc.id,
                mapOf("masteryScore" to chScore.toLong(), "masteryStatus" to chStatus)
            )
        }
        val (bookScore, _) = repository.computeChapterMastery(bookQuestions, bookCards)
        repository.updateBook(bookId, mapOf("masteryScore" to bookScore.toLong()))
    }

    // --- AI TUTOR & LEARNING TOOLS ---
    fun sendTutorMessage(
        book: Book,
        chapter: Chapter?,
        page: BookPageContent?,
        mode: TutorMode,
        prompt: String,
        includePageImage: Boolean = false
    ) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val chNum = chapter?.chapterNumber ?: 0L
            val pageNum = page?.pageNumber?.toLong() ?: book.currentPage
            repository.addTutorMessage(
                TutorMessage(
                    bookId = book.id,
                    chapterNumber = chNum,
                    pageNumber = pageNum,
                    mode = mode.name,
                    role = "USER",
                    text = prompt.trim()
                )
            )

            val history = tutorMessages.value
                .filter { it.bookId == book.id }
                .takeLast(8)
                .map { it.role to it.text }

            val bitmap: Bitmap? = if (includePageImage && page != null) {
                when (book.sourceType) {
                    "PDF" -> documentProcessor.renderPdfPageBitmap(book.localBookId, book.fileName, page.pageNumber - 1, 1024)
                    "IMAGE" -> documentProcessor.loadStoredImageBitmap(book.localBookId, book.fileName)
                    else -> null
                }
            } else null

            _tutorStreamingText.value = ""
            var finalReply = ""
            try {
                aiService.streamTutorReply(
                    bookTitle = book.title,
                    chapter = chapter,
                    currentPage = page,
                    mode = mode,
                    historyPairs = history,
                    userQuery = prompt.trim(),
                    pageBitmap = bitmap
                ).collect { chunk ->
                    finalReply = chunk
                    _tutorStreamingText.value = chunk
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tutor stream error", e)
                _bannerMessage.value = e.localizedMessage ?: "AI Tutor request failed."
            } finally {
                bitmap?.recycle()
                _tutorStreamingText.value = null
            }

            if (finalReply.isNotBlank()) {
                val citations = listOfNotNull(
                    chapter?.let { "Ch. ${it.chapterNumber}" },
                    page?.let { "p. ${it.pageNumber}" }
                )
                repository.addTutorMessage(
                    TutorMessage(
                        bookId = book.id,
                        chapterNumber = chNum,
                        pageNumber = pageNum,
                        mode = mode.name,
                        role = "MODEL",
                        text = finalReply,
                        citations = citations,
                        distinctionNote = "Textbook references cited; supplementary explanations labeled."
                    )
                )
            }
        }
    }

    fun runReadingAssistant(action: String, text: String, pageNumber: Int, bookTitle: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = "Running Reading Assistant ($action)..."
            aiService.runReadingAssistant(action, text, pageNumber, bookTitle)
                .onSuccess { _readingAssistantResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Assistant failed." }
            _aiBusyLabel.value = null
        }
    }

    fun clearReadingAssistant() {
        _readingAssistantResult.value = null
    }

    fun evaluateTeachBack(conceptTitle: String, referenceText: String, studentText: String) {
        if (studentText.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = "Evaluating your Teach-Back explanation..."
            aiService.evaluateTeachBack(conceptTitle, referenceText, studentText)
                .onSuccess { _teachBackResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Teach-Back failed." }
            _aiBusyLabel.value = null
        }
    }

    fun clearTeachBack() {
        _teachBackResult.value = null
    }

    fun runChapterComparisonOrGapAnalysis(mode: String, book: Book, chapter: Chapter) {
        viewModelScope.launch {
            val pages = documentProcessor.loadPages(book.localBookId)
            _aiBusyLabel.value = "Analyzing $mode..."
            aiService.generateComparisonOrGapAnalysis(mode, chapter, pages)
                .onSuccess { _comparisonOrGapResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Analysis failed." }
            _aiBusyLabel.value = null
        }
    }

    fun clearComparisonResult() {
        _comparisonOrGapResult.value = null
    }

    fun solveEquation(equationInput: String) {
        if (equationInput.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = "Solving step-by-step..."
            aiService.solveEquationWithSteps(equationInput.trim())
                .onSuccess { _equationSolution.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Equation solver failed." }
            _aiBusyLabel.value = null
        }
    }

    fun clearEquationSolution() {
        _equationSolution.value = null
    }

    // --- NOTES & CONVERSIONS ---
    fun saveNote(
        existingId: String?,
        title: String,
        content: String,
        bookId: String,
        subjectName: String,
        chapterNumber: Long,
        linkedPage: Long,
        tags: List<String>,
        quickNote: Boolean
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            if (existingId.isNullOrBlank()) {
                repository.createNote(
                    StudyNote(
                        bookId = bookId,
                        subjectName = subjectName.ifBlank { "General" },
                        chapterNumber = chapterNumber,
                        linkedPage = linkedPage,
                        title = title.trim(),
                        contentMarkdown = content,
                        tags = tags,
                        quickNote = quickNote
                    )
                )
            } else {
                repository.updateNote(
                    existingId,
                    mapOf(
                        "title" to title.trim(),
                        "contentMarkdown" to content,
                        "tags" to tags,
                        "linkedPage" to linkedPage
                    )
                )
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch { repository.deleteNote(noteId) }
    }

    fun convertNoteToFlashcards(note: StudyNote) {
        val lines = note.contentMarkdown.lines()
            .map { it.trim().trimStart('-', '*', '#').trim() }
            .filter { it.length > 6 }
        val cards = lines.take(6).map { line ->
            val parts = line.split(":", "—", " - ", limit = 2)
            val front = parts.first().trim()
            val back = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: line
            Flashcard(
                bookId = note.bookId.ifBlank { "notes" },
                chapterNumber = note.chapterNumber,
                deckName = note.title.take(40),
                format = "TERM_DEF",
                front = front,
                back = back,
                pageReference = note.linkedPage
            )
        }
        if (cards.isEmpty()) {
            _bannerMessage.value = "Add a few bullet points or 'Term: Definition' lines in your note first."
            return
        }
        viewModelScope.launch {
            repository.addFlashcards(cards)
            _bannerMessage.value = "Created ${cards.size} flashcard(s) from \"${note.title}\"."
        }
    }

    // --- STUDY PLANNER & ADAPTIVE REORGANIZATION ---
    fun addPlannerTask(
        title: String,
        taskType: String,
        subjectName: String,
        dueDateMillis: Long,
        estimatedMinutes: Long,
        priority: String,
        recurring: String,
        bookId: String = "",
        chapterNumber: Long = 0L
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createPlannerTask(
                PlannerTask(
                    title = title.trim(),
                    taskType = taskType,
                    subjectName = subjectName.ifBlank { "General" },
                    bookId = bookId,
                    chapterNumber = chapterNumber,
                    dueDateMillis = dueDateMillis,
                    estimatedMinutes = estimatedMinutes,
                    priority = priority,
                    recurring = recurring
                )
            )
        }
    }

    fun toggleTaskCompleted(task: PlannerTask) {
        viewModelScope.launch {
            repository.updatePlannerTask(task.id, mapOf("completed" to !task.completed))
        }
    }

    fun deletePlannerTask(taskId: String) {
        viewModelScope.launch { repository.deletePlannerTask(taskId) }
    }

    fun reorganizeMissedAndAdaptivePlan(dailyAvailableMinutes: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dayMs = 86_400_000L
            val incomplete = plannerTasks.value.filter { !it.completed }
            val overdue = incomplete.filter { it.dueDateMillis < now - dayMs / 2 }
            var dayOffset = 0
            var minutesToday = 0

            // Redistribute overdue tasks calmly across upcoming days without penalty
            overdue.sortedByDescending { it.priority == "URGENT" || it.priority == "HIGH" }.forEach { task ->
                val dur = task.estimatedMinutes.toInt().coerceAtLeast(15)
                if (minutesToday + dur > dailyAvailableMinutes && minutesToday > 0) {
                    dayOffset++
                    minutesToday = 0
                }
                val newDue = now + dayOffset * dayMs + 3_600_000L
                repository.updatePlannerTask(task.id, mapOf("dueDateMillis" to newDue))
                minutesToday += dur
            }

            // Schedule Smart Revision items if needed
            val revisionQueue = getSmartRevisionQueue()
            if (overdue.isEmpty() && revisionQueue.isNotEmpty() && incomplete.size < 5) {
                val top = revisionQueue.first()
                repository.createPlannerTask(
                    PlannerTask(
                        title = top.title,
                        taskType = "REVISION",
                        subjectName = books.value.firstOrNull { it.id == top.bookId }?.subjectName ?: "General",
                        bookId = top.bookId,
                        chapterNumber = top.chapterNumber,
                        dueDateMillis = now + 7_200_000L,
                        estimatedMinutes = top.estimatedMinutes.toLong(),
                        priority = "HIGH"
                    )
                )
            }
            _bannerMessage.value = if (overdue.isNotEmpty()) {
                "Rescheduled ${overdue.size} missed session(s) realistically across your upcoming capacity."
            } else {
                "Study plan balanced with your Smart Revision Queue."
            }
        }
    }

    fun getSmartRevisionQueue(): List<SmartRevisionItem> {
        val chMap = _selectedBookId.value?.let { mapOf(it to selectedBookChapters.value) } ?: emptyMap()
        return repository.buildSmartRevisionQueue(
            books = books.value,
            chaptersByBook = chMap,
            questions = questions.value,
            flashcards = flashcards.value,
            tasks = plannerTasks.value
        )
    }

    // --- FOCUS TIMER ---
    fun configureFocusSession(mode: String, minutes: Int, subjectName: String, bookId: String = "", chapterNum: Long = 0L, goalText: String = "") {
        timerJob?.cancel()
        val secs = minutes * 60
        _focusState.value = FocusTimerState(
            running = false,
            mode = mode,
            remainingSeconds = if (mode == "STOPWATCH") 0 else secs,
            elapsedSeconds = 0,
            targetSeconds = secs,
            subjectName = subjectName.ifBlank { "General" },
            bookId = bookId,
            chapterNumber = chapterNum,
            goalText = goalText
        )
    }

    fun toggleFocusTimer() {
        val current = _focusState.value
        if (current.running) {
            timerJob?.cancel()
            _focusState.value = current.copy(running = false)
        } else {
            _focusState.value = current.copy(running = true)
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000L)
                    val s = _focusState.value
                    if (!s.running) break
                    if (s.mode == "STOPWATCH") {
                        _focusState.value = s.copy(elapsedSeconds = s.elapsedSeconds + 1)
                    } else {
                        val nextRem = (s.remainingSeconds - 1).coerceAtLeast(0)
                        val nextElapsed = s.elapsedSeconds + 1
                        _focusState.value = s.copy(remainingSeconds = nextRem, elapsedSeconds = nextElapsed)
                        if (nextRem == 0) {
                            completeFocusSession(notes = "Completed timer goal")
                            break
                        }
                    }
                }
            }
        }
    }

    fun completeFocusSession(notes: String = "") {
        timerJob?.cancel()
        val s = _focusState.value
        val mins = (s.elapsedSeconds / 60).coerceAtLeast(1)
        _focusState.value = s.copy(running = false, elapsedSeconds = 0, remainingSeconds = s.targetSeconds)
        viewModelScope.launch {
            val firestoreMode = when (s.mode) {
                "STOPWATCH" -> "STOPWATCH"
                "CUSTOM" -> "CUSTOM"
                else -> "POMODORO"
            }
            repository.recordFocusSession(
                FocusSession(
                    subjectName = s.subjectName,
                    bookId = s.bookId,
                    chapterNumber = s.chapterNumber,
                    mode = firestoreMode,
                    durationMinutes = mins.toLong(),
                    goalText = s.goalText,
                    notes = notes
                )
            )
            _bannerMessage.value = "Recorded ${mins}m focus session for ${s.subjectName}."
        }
    }

    fun addCitation(style: String, authors: String, title: String, year: String, publisher: String, pages: String, formattedText: String) {
        viewModelScope.launch {
            repository.addCitation(
                CitationEntry(
                    bookId = _selectedBookId.value.orEmpty(),
                    style = style,
                    authors = authors,
                    title = title,
                    year = year,
                    publisher = publisher,
                    pages = pages,
                    formattedText = formattedText
                )
            )
        }
    }

    fun deleteCitation(id: String) {
        viewModelScope.launch { repository.deleteCitation(id) }
    }

    private fun <T> Flow<List<T>>.safeCatch(label: String): Flow<List<T>> =
        safeCatchSingle(label, emptyList())

    private fun <T> Flow<T>.safeCatchSingle(label: String, fallback: T): Flow<T> =
        catch { error ->
            val fsErr = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (fsErr == null && error is CancellationException) throw error
            val root = fsErr ?: error
            Log.w(TAG, "Stream error on $label", root)
            _bannerMessage.value = root.message ?: "Failed to sync $label"
            emit(fallback)
        }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
    }
}
