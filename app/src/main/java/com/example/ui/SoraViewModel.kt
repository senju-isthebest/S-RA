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
import com.example.data.exam.ExamEngine
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
import com.example.data.sync.CloudBookSyncManager
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

    private val cloudBookSync = CloudBookSyncManager()
    private var generationQueueActive = false

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
            val localPages = documentProcessor.loadPages(book.localBookId)
            if (localPages.isNotEmpty()) {
                _loadedBookPages.value = localPages
            } else {
                _loadedBookPages.value = emptyList()
                val cloudPages = cloudBookSync.downloadPages(book.id).getOrNull()
                if (!cloudPages.isNullOrEmpty()) {
                    documentProcessor.savePages(book.localBookId, cloudPages)
                    _loadedBookPages.value = cloudPages
                }
            }
            val localSource = documentProcessor.getLocalFile(book.localBookId, book.fileName)
            if (!localSource.exists() && book.storagePath.isNotBlank()) {
                cloudBookSync.downloadSourceFile(book.id, localSource)
            }
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
            val saved = repository.createBookWithId(localId, failedBook)
            if (saved.isSuccess) {
                syncImportedBook(localId, result.fileName, result.pages)
            }
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

        _ingestionProgress.value = IngestionProgressState(true, 0, 2, "Syncing book file and extracted pages...")
        val cloudSynced = syncImportedBook(localId, result.fileName, result.pages)

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
        _bannerMessage.value = when {
            !cloudSynced -> "Book saved locally, but cloud sync failed. It will remain available on this device."
            result.extractionStatus == "PARTIAL" -> "Imported and synced with ${result.failedPages.size} failed page(s). You can retry failed pages anytime."
            else -> "\"$finalTitle\" analyzed into ${savedChapters.size} chapter(s) and synced."
        }
    }

    private fun contentTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

    private suspend fun syncImportedBook(
        bookId: String,
        fileName: String,
        pages: List<BookPageContent>
    ): Boolean {
        val file = documentProcessor.getLocalFile(bookId, fileName)
        val contentType = contentTypeFor(fileName)
        val fileSynced = cloudBookSync.uploadSourceFile(
            bookId, file, fileName, contentType
        ) { uploaded, total ->
            _ingestionProgress.value = IngestionProgressState(
                true, uploaded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "Uploading textbook file..."
            )
        }.isSuccess
        val pagesSynced = if (pages.isEmpty()) {
            true
        } else {
            _ingestionProgress.value = IngestionProgressState(true, 0, pages.size, "Syncing extracted pages...")
            cloudBookSync.uploadPages(bookId, pages) { done, total ->
                _ingestionProgress.value = IngestionProgressState(true, done, total, "Syncing extracted pages...")
            }.isSuccess
        }
        return fileSynced && pagesSynced
    }

    fun retryCloudSync(book: Book) {
        viewModelScope.launch {
            _ingestionProgress.value = IngestionProgressState(true, 0, 1, "Retrying cloud sync...")
            var pages = documentProcessor.loadPages(book.localBookId)
            if (pages.isEmpty() && book.cloudPageCount > 0L) {
                pages = cloudBookSync.downloadPages(book.id).getOrNull().orEmpty()
                if (pages.isNotEmpty()) documentProcessor.savePages(book.localBookId, pages)
            }
            val localFile = documentProcessor.getLocalFile(book.localBookId, book.fileName)
            val sourceOk = if (localFile.exists()) {
                cloudBookSync.uploadSourceFile(book.id, localFile, book.fileName, contentTypeFor(book.fileName)) { uploaded, total ->
                    _ingestionProgress.value = IngestionProgressState(
                        true, uploaded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "Uploading textbook file..."
                    )
                }.isSuccess
            } else if (book.storagePath.isNotBlank()) {
                cloudBookSync.downloadSourceFile(book.id, localFile).isSuccess
            } else false
            val pagesOk = when {
                pages.isNotEmpty() -> cloudBookSync.uploadPages(book.id, pages) { done, total ->
                    _ingestionProgress.value = IngestionProgressState(true, done, total, "Syncing extracted pages...")
                }.isSuccess
                book.cloudPageCount == 0L -> true
                else -> false
            }
            _ingestionProgress.value = IngestionProgressState(false)
            _bannerMessage.value = if (sourceOk && pagesOk) "Cloud sync completed." else "Cloud sync is still unavailable. Your local copy is safe."
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

    private suspend fun loadPagesForBook(book: Book): List<BookPageContent> {
        val localPages = documentProcessor.loadPages(book.localBookId)
        if (localPages.isNotEmpty()) return localPages
        val cloudPages = cloudBookSync.downloadPages(book.id).getOrNull().orEmpty()
        if (cloudPages.isNotEmpty()) documentProcessor.savePages(book.localBookId, cloudPages)
        return cloudPages
    }

    fun generateChapterResources(book: Book, chapter: Chapter, difficultyLevel: String = "Standard") {
        viewModelScope.launch {
            if (generationQueueActive || _aiBusyLabel.value != null) {
                _bannerMessage.value = "Another AI task is already running. Wait for it to finish first."
                return@launch
            }
            generationQueueActive = true
            _aiBusyLabel.value = "Preparing chapter generation..."
            try {
                val pages = loadPagesForBook(book)
                if (pages.isEmpty()) {
                    _bannerMessage.value = "No extracted pages available for this book."
                    return@launch
                }
                _aiBusyLabel.value = "Generating AI resources for Chapter " + chapter.chapterNumber + "..."
                _ingestionProgress.value = IngestionProgressState(true, 0, 3, "Starting chapter generation...")
                val completed = generateAllResourcesForChapterInternal(book, chapter, pages, difficultyLevel)
                _bannerMessage.value = if (completed) {
                    "Chapter " + chapter.chapterNumber + " resources are ready."
                } else {
                    "Chapter " + chapter.chapterNumber + " is partially generated. Retry to continue missing resources."
                }
            } finally {
                generationQueueActive = false
                _ingestionProgress.value = IngestionProgressState(false)
                _aiBusyLabel.value = null
            }
        }
    }

    fun generateFullBookResources(book: Book, forceRegenerate: Boolean = false) {
        viewModelScope.launch {
            if (generationQueueActive || _aiBusyLabel.value != null) {
                _bannerMessage.value = "Another AI task is already running. Wait for it to finish first."
                return@launch
            }
            generationQueueActive = true
            _aiBusyLabel.value = "Preparing full-book generation..."
            try {
                val pages = loadPagesForBook(book)
                if (pages.isEmpty()) {
                    _bannerMessage.value = "No extracted pages available. Restore or re-import the book first."
                    return@launch
                }
                val chaptersResult = repository.getChapters(book.id)
                val chapters = chaptersResult.getOrNull().orEmpty().sortedBy { it.chapterNumber }
                if (chapters.isEmpty()) {
                    _bannerMessage.value = "No chapters are available for full-book generation."
                    return@launch
                }
                val chaptersToProcess = if (forceRegenerate) chapters.map { it.copy(
                    intelligenceGenerated = false, questionsGenerated = false, flashcardsGenerated = false,
                    generationStatus = "PENDING", generationError = ""
                ) } else chapters
                if (forceRegenerate) {
                    chapters.forEach { chapter ->
                        repository.updateChapterFields(book.id, chapter.id, mapOf(
                            "intelligenceGenerated" to false,
                            "questionsGenerated" to false,
                            "flashcardsGenerated" to false,
                            "generationStatus" to "PENDING",
                            "generationError" to ""
                        ))
                    }
                }
                _aiBusyLabel.value = "Generating resources across " + chapters.size + " chapters..."
                var completedChapters = 0
                var failedChapters = 0
                chaptersToProcess.forEachIndexed { index, chapter ->
                    _ingestionProgress.value = IngestionProgressState(
                        true, index, chapters.size,
                        "Chapter " + chapter.chapterNumber + "/" + chapters.size + ": " + chapter.title
                    )
                    val complete = try {
                        generateAllResourcesForChapterInternal(book, chapter, pages, forceGenerate = forceRegenerate)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        repository.updateChapterFields(book.id, chapter.id, mapOf(
                            "generationStatus" to "FAILED",
                            "generationError" to (error.localizedMessage ?: "Unexpected generation error").take(500)
                        ))
                        false
                    }
                    if (complete) completedChapters++ else failedChapters++
                    _ingestionProgress.value = IngestionProgressState(
                        true, index + 1, chapters.size,
                        if (complete) "Completed Chapter " + chapter.chapterNumber else "Chapter " + chapter.chapterNumber + " needs retry"
                    )
                    if (index < chapters.lastIndex) delay(1_500L)
                }
                _bannerMessage.value = if (failedChapters == 0) {
                    "Full-book generation completed for " + completedChapters + " chapters."
                } else {
                    completedChapters.toString() + " chapters completed; " + failedChapters + " need retry. Resume to continue."
                }
            } finally {
                generationQueueActive = false
                _ingestionProgress.value = IngestionProgressState(false)
                _aiBusyLabel.value = null
            }
        }
    }

    private suspend fun generateAllResourcesForChapterInternal(
        book: Book,
        chapter: Chapter,
        pages: List<BookPageContent>,
        difficultyLevel: String = "Standard",
        forceGenerate: Boolean = false
    ): Boolean {
        if (chapter.id.isBlank()) return false
        val existingQuestions = if (forceGenerate) emptyList() else questions.value.filter { it.bookId == book.id && it.chapterNumber == chapter.chapterNumber && it.aiGenerated }
        val existingFlashcards = if (forceGenerate) emptyList() else flashcards.value.filter { it.bookId == book.id && it.chapterNumber == chapter.chapterNumber && it.deckName.startsWith("Ch. " + chapter.chapterNumber + ":") }
        var intelligenceDone = chapter.intelligenceGenerated || (!forceGenerate && chapter.quickSummary.isNotBlank() && chapter.detailedSummary.isNotBlank())
        var questionsDone = chapter.questionsGenerated || existingQuestions.size >= 8
        var flashcardsDone = chapter.flashcardsGenerated || existingFlashcards.size >= 8
        val questionOffset = if (forceGenerate) 0 else existingQuestions.size
        val flashcardOffset = if (forceGenerate) 0 else existingFlashcards.size
        val attempts = (chapter.generationAttempts + 1L).coerceAtMost(100L)
        val errors = mutableListOf<String>()

        repository.updateChapterFields(book.id, chapter.id, mapOf(
            "generationStatus" to "PROCESSING",
            "generationAttempts" to attempts,
            "generationError" to ""
        ))

        if (!intelligenceDone) {
            _ingestionProgress.value = _ingestionProgress.value.copy(statusLabel = "Generating Chapter " + chapter.chapterNumber + " intelligence...")
            val result = aiService.generateChapterIntelligence(book.id, chapter, pages, difficultyLevel)
            result.onSuccess { (enrichedChapter, glossary) ->
                val generatedGlossary = glossary.mapIndexed { index, entry ->
                    entry.copy(id = "gen_" + book.id + "_ch" + chapter.chapterNumber + "_g" + (index + 1), bookId = book.id)
                }
                val chapterSaved = repository.saveChapter(
                    book.id,
                    enrichedChapter.copy(
                        id = chapter.id,
                        intelligenceGenerated = true,
                        generationAttempts = attempts,
                        generationStatus = "PROCESSING",
                        generationError = ""
                    )
                )
                val glossarySaved = if (chapterSaved.isSuccess) repository.addGlossaryEntries(book.id, generatedGlossary) else Result.failure(chapterSaved.exceptionOrNull() ?: IllegalStateException("Chapter save failed"))
                if (chapterSaved.isSuccess && glossarySaved.isSuccess) {
                    intelligenceDone = true
                    repository.updateChapterFields(book.id, chapter.id, mapOf("intelligenceGenerated" to true))
                } else {
                    errors += chapterSaved.exceptionOrNull()?.localizedMessage ?: glossarySaved.exceptionOrNull()?.localizedMessage ?: "Could not save chapter intelligence."
                }
            }.onFailure { errors += "Intelligence: " + (it.localizedMessage ?: "generation failed") }
        }

        if (!questionsDone) {
            delay(900L)
            _ingestionProgress.value = _ingestionProgress.value.copy(statusLabel = "Generating Chapter " + chapter.chapterNumber + " questions...")
            val result = aiService.generateQuestionsForChapter(
                bookId = book.id,
                bookTitle = book.title,
                chapter = chapter,
                pages = pages,
                requestedTypes = listOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "APPLICATION", "COMPARE_CONTRAST", "CALCULATION"),
                count = (8 - questionOffset).coerceAtLeast(1)
            )
            result.onSuccess { generated ->
                val stableQuestions = generated.mapIndexed { index, question ->
                    question.copy(id = "gen_" + book.id + "_ch" + chapter.chapterNumber + "_q" + (questionOffset + index + 1))
                }
                val saved = repository.addQuestions(stableQuestions)
                if (saved.isSuccess) {
                    questionsDone = true
                    repository.updateChapterFields(book.id, chapter.id, mapOf("questionsGenerated" to true))
                } else {
                    errors += saved.exceptionOrNull()?.localizedMessage ?: "Could not save generated questions."
                }
            }.onFailure { errors += "Questions: " + (it.localizedMessage ?: "generation failed") }
        }

        if (!flashcardsDone) {
            delay(900L)
            _ingestionProgress.value = _ingestionProgress.value.copy(statusLabel = "Generating Chapter " + chapter.chapterNumber + " flashcards...")
            val result = aiService.generateFlashcardsForChapter(book.id, chapter, pages, count = (8 - flashcardOffset).coerceAtLeast(1))
            result.onSuccess { generated ->
                val stableCards = generated.mapIndexed { index, card ->
                    card.copy(id = "gen_" + book.id + "_ch" + chapter.chapterNumber + "_f" + (flashcardOffset + index + 1))
                }
                val saved = repository.addFlashcards(stableCards)
                if (saved.isSuccess) {
                    flashcardsDone = true
                    repository.updateChapterFields(book.id, chapter.id, mapOf("flashcardsGenerated" to true))
                } else {
                    errors += saved.exceptionOrNull()?.localizedMessage ?: "Could not save generated flashcards."
                }
            }.onFailure { errors += "Flashcards: " + (it.localizedMessage ?: "generation failed") }
        }

        val allDone = intelligenceDone && questionsDone && flashcardsDone
        val anyDone = intelligenceDone || questionsDone || flashcardsDone
        val status = when {
            allDone -> "COMPLETE"
            anyDone -> "PARTIAL"
            else -> "FAILED"
        }
        repository.updateChapterFields(book.id, chapter.id, mapOf(
            "intelligenceGenerated" to intelligenceDone,
            "questionsGenerated" to questionsDone,
            "flashcardsGenerated" to flashcardsDone,
            "generationStatus" to status,
            "generationAttempts" to attempts,
            "generationError" to errors.joinToString("; ").take(500)
        ))
        return allDone
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
            val cloudCleanup = cloudBookSync.deleteCloudBookData(bookId)
            if (cloudCleanup.isFailure) {
                _bannerMessage.value = "Could not remove cloud copy. Book was kept so cleanup can be retried."
                return@launch
            }
            val deleted = repository.deleteBook(bookId)
            if (deleted.isFailure) {
                _bannerMessage.value = "Cloud copy removed, but book metadata could not be deleted."
                return@launch
            }
            val localBookId = books.value.firstOrNull { it.id == bookId }?.localBookId?.takeIf { it.isNotBlank() } ?: bookId
            runCatching { documentProcessor.deleteLocalBook(localBookId) }
            if (_selectedBookId.value == bookId) closeBookWorkspace()
            _bannerMessage.value = "Book and synced files deleted."
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
        val validQuestions = selectedQuestions
            .filter { it.id.isNotBlank() && it.questionText.isNotBlank() && it.correctAnswer.isNotBlank() && it.marks in 1L..100L }
            .filter { it.questionType != "MCQ" || it.options.size >= 2 }
            .distinctBy { it.id }
            .take(200)
        if (validQuestions.isEmpty()) {
            _bannerMessage.value = "No valid questions available for this exam configuration."
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val safeDuration = durationMinutes.coerceIn(1, 300)
            val totalMarks = validQuestions.sumOf { it.marks }.coerceAtLeast(1L)
            val exam = ExamRecord(
                title = title.ifBlank { preset.label },
                preset = preset.name,
                bookIds = validQuestions.map { it.bookId }.distinct().take(20),
                questionIds = validQuestions.map { it.id },
                durationMinutes = safeDuration.toLong(),
                totalMarks = totalMarks,
                allowRevisit = allowRevisit,
                showExplanationEach = showExplanationEach,
                status = "IN_PROGRESS",
                unansweredCount = validQuestions.size.toLong(),
                startedAtMillis = now,
                deadlineAtMillis = now + safeDuration * 60_000L
            )
            repository.createExam(exam)
                .onSuccess { id -> onCreated(id) }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: "Could not start exam." }
        }
    }

    fun ensureExamClock(exam: ExamRecord) {
        if (exam.id.isBlank() || exam.deadlineAtMillis > 0L) return
        val startedAt = exam.startedAtMillis.takeIf { it > 0L }
            ?: exam.createdAt?.toDate()?.time
            ?: System.currentTimeMillis()
        val deadline = startedAt + exam.durationMinutes.coerceIn(1L, 300L) * 60_000L
        viewModelScope.launch {
            repository.updateExamProgress(exam.id, mapOf(
                "startedAtMillis" to startedAt,
                "deadlineAtMillis" to deadline
            ))
        }
    }

    fun saveExamProgress(
        exam: ExamRecord,
        answersByQuestionId: Map<String, String>,
        flaggedIds: Set<String>,
        currentQuestionIndex: Int,
        timeSpentSeconds: Int
    ) {
        if (exam.id.isBlank() || exam.status == "SUBMITTED") return
        val answers = JSONObject()
        answersByQuestionId.filterKeys { it.isNotBlank() }.forEach { (id, answer) ->
            answers.put(id, answer.take(2500))
        }
        viewModelScope.launch {
            repository.updateExamProgress(exam.id, mapOf(
                "status" to "IN_PROGRESS",
                "answersJson" to answers.toString().take(50000),
                "flaggedQuestionIds" to flaggedIds.filter { it.isNotBlank() }.take(200),
                "currentQuestionIndex" to currentQuestionIndex.coerceIn(0, 199),
                "timeSpentSeconds" to timeSpentSeconds.coerceIn(0, 36000).toLong()
            )).onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Could not save exam progress."
            }
        }
    }

    fun submitExam(
        exam: ExamRecord,
        examQuestions: List<QuestionItem>,
        answersByQuestionId: Map<String, String>,
        flaggedIds: Set<String>,
        timeSpentSeconds: Int
    ) {
        if (exam.id.isBlank() || exam.status == "SUBMITTED") return
        viewModelScope.launch {
            var score = 0L
            var correct = 0L
            var incorrect = 0L
            var partial = 0L
            var unanswered = 0L
            val chapterMap = mutableMapOf<Long, Pair<Long, Long>>()
            val difficultyMap = mutableMapOf<String, Pair<Long, Long>>()

            examQuestions.forEach { question ->
                val answer = answersByQuestionId[question.id].orEmpty().trim()
                val previousChapter = chapterMap[question.chapterNumber] ?: (0L to 0L)
                val previousDifficulty = difficultyMap[question.difficulty] ?: (0L to 0L)
                if (answer.isBlank()) {
                    unanswered++
                    chapterMap[question.chapterNumber] = previousChapter.first to (previousChapter.second + question.marks)
                    difficultyMap[question.difficulty] = previousDifficulty.first to (previousDifficulty.second + question.marks)
                } else {
                    val grade = ExamEngine.grade(question, answer)
                    score += grade.earnedMarks
                    if (grade.isFullyCorrect) correct++ else incorrect++
                    if (grade.isPartiallyCorrect) partial++
                    chapterMap[question.chapterNumber] = (previousChapter.first + grade.earnedMarks) to (previousChapter.second + question.marks)
                    difficultyMap[question.difficulty] = (previousDifficulty.first + grade.earnedMarks) to (previousDifficulty.second + question.marks)
                    repository.recordQuestionAttempt(question, grade.isFullyCorrect)
                }
            }

            val pct = if (exam.totalMarks > 0L) {
                (score.toDouble() / exam.totalMarks.toDouble() * 100.0).coerceIn(0.0, 100.0)
            } else 0.0
            val analyticsJson = JSONObject().apply {
                val byChapter = JSONObject()
                chapterMap.forEach { (chapter, marks) -> byChapter.put("Ch. $chapter", "${marks.first}/${marks.second}") }
                val byDifficulty = JSONObject()
                difficultyMap.forEach { (difficulty, marks) -> byDifficulty.put(difficulty, "${marks.first}/${marks.second}") }
                put("byChapter", byChapter)
                put("byDifficulty", byDifficulty)
                put("partialCreditCount", partial)
            }.toString()
            val weakChapters = chapterMap.entries
                .filter { it.value.second > 0L && it.value.first.toDouble() / it.value.second < 0.7 }
                .map { "Chapter ${it.key}" }
            val recommendation = if (weakChapters.isEmpty()) {
                "Strong performance across tested chapters. Continue spaced-repetition flashcard reviews."
            } else {
                "Focus revision on ${weakChapters.joinToString(", ")} and review missed items in your Mistake Notebook."
            }
            val answersJson = JSONObject().apply {
                answersByQuestionId.forEach { (id, answer) -> put(id, answer.take(2500)) }
            }.toString()

            repository.updateExamProgress(exam.id, mapOf(
                "status" to "SUBMITTED",
                "answersJson" to answersJson.take(50000),
                "flaggedQuestionIds" to flaggedIds.filter { it.isNotBlank() }.take(200),
                "score" to score.coerceIn(0L, exam.totalMarks),
                "percentage" to pct,
                "correctCount" to correct,
                "incorrectCount" to incorrect,
                "partiallyCorrectCount" to partial,
                "unansweredCount" to unanswered,
                "timeSpentSeconds" to timeSpentSeconds.coerceIn(0, 36000).toLong(),
                "analyticsJson" to analyticsJson.take(20000),
                "recommendedRevision" to recommendation.take(3000)
            )).onFailure {
                _bannerMessage.value = it.localizedMessage ?: "Could not submit exam. Your answers remain saved."
                return@launch
            }

            examQuestions.map { it.bookId to it.chapterNumber }.distinct().forEach { (bookId, chapter) ->
                recalculateBookAndChapterMastery(bookId, chapter)
            }
        }
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
