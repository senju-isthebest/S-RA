package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.AppLanguage
import com.example.core.InputValidator
import com.example.core.SoraSettings
import com.example.core.UserPreferences
import com.example.core.createLocalizedContext
import com.example.data.ai.AiProviderConfig
import com.example.data.ai.AiResponse
import com.example.data.ai.BookPassageRetriever
import com.example.data.ai.ConnectionTestResult
import com.example.data.ai.FullBookAnalysisProgress
import com.example.data.ai.FullBookIntelligenceEngine
import com.example.data.ai.JsonObj
import com.example.data.ai.JsonStr
import com.example.data.ai.RetrievedPassage
import com.example.data.ai.SoraAiService
import com.example.data.ai.SoraJson
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
import com.example.data.model.SyncedUserPreferences
import com.example.data.model.TutorMessage
import com.example.data.model.TutorMode
import com.example.data.model.UserProfile
import com.example.data.repository.SoraRepository
import com.example.data.sync.AndroidConnectivityProvider
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.ConflictResolver
import com.example.data.sync.FirestoreSyncBackend
import com.example.data.sync.NetworkConnectivityProvider
import com.example.data.sync.OfflineSyncOperation
import com.example.data.sync.SyncOpType
import com.example.data.sync.SyncState
import com.example.domain.AnswerEvaluation
import com.example.domain.ExamEngine
import com.example.domain.OfflineExamCache
import com.example.ui.theme.SoraThemeMode
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.auth
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
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
    val currentUserId: String,
    private val appContext: Context? = null,
    syncManagerOverride: CloudSyncManager? = null
) : ViewModel() {

    private val storageDir: File = (appContext?.filesDir
        ?: File(System.getProperty("java.io.tmpdir"), "sora_vm_${InputValidator.sanitizeFileName(currentUserId)}"))
        .apply { mkdirs() }

    val cloudSyncManager: CloudSyncManager = syncManagerOverride ?: CloudSyncManager(
        uid = currentUserId,
        filesDir = storageDir,
        backend = FirestoreSyncBackend(repository, documentProcessor),
        documentProcessor = documentProcessor,
        connectivityProvider = appContext?.let { AndroidConnectivityProvider(it) }
            ?: object : NetworkConnectivityProvider { override fun isOnline(): Boolean = true }
    )
    val syncState: StateFlow<SyncState> = cloudSyncManager.syncState

    private val offlineExamCache = OfflineExamCache(storageDir)
    private val fullBookEngine = FullBookIntelligenceEngine(aiService)

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _ingestionProgress = MutableStateFlow(IngestionProgressState())
    val ingestionProgress: StateFlow<IngestionProgressState> = _ingestionProgress.asStateFlow()

    private val _fullBookProgress = MutableStateFlow(FullBookAnalysisProgress())
    val fullBookProgress: StateFlow<FullBookAnalysisProgress> = _fullBookProgress.asStateFlow()

    private val _lastRetrievedPassages = MutableStateFlow<List<RetrievedPassage>>(emptyList())
    val lastRetrievedPassages: StateFlow<List<RetrievedPassage>> = _lastRetrievedPassages.asStateFlow()

    private val _lastExamEvaluations = MutableStateFlow<Map<String, AnswerEvaluation>>(emptyMap())
    val lastExamEvaluations: StateFlow<Map<String, AnswerEvaluation>> = _lastExamEvaluations.asStateFlow()

    private val _offlineExams = MutableStateFlow(offlineExamCache.loadAll())

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
    private var fullBookAnalysisJob: Job? = null

    private val _aiConnectionTestResult = MutableStateFlow<ConnectionTestResult?>(null)
    val aiConnectionTestResult: StateFlow<ConnectionTestResult?> = _aiConnectionTestResult.asStateFlow()

    private val _aiTesting = MutableStateFlow(false)
    val aiTesting: StateFlow<Boolean> = _aiTesting.asStateFlow()

    val lastAiResponse: StateFlow<AiResponse?> = aiService.lastAiResponse

    val settings: StateFlow<SoraSettings> = userPreferences.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SoraSettings())

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

    val exams: StateFlow<List<ExamRecord>> = combine(
        repository.observeExams(currentUserId).safeCatch("exams"),
        _offlineExams
    ) { remote, offline ->
        val byId = LinkedHashMap<String, ExamRecord>()
        remote.forEach { byId[it.id] = it }
        offline.forEach { loc ->
            val rem = byId[loc.id]
            if (rem == null || (rem.status != "SUBMITTED" && loc.status == "SUBMITTED")) {
                byId[loc.id] = loc
            }
        }
        byId.values.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

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
        viewModelScope.launch {
            repository.observeSyncedPreferences(currentUserId)
                .safeCatchSingle("syncedPreferences", null)
                .collect { remote ->
                    if (remote != null && ConflictResolver.shouldApplyRemotePreferences(settings.value, remote)) {
                        userPreferences.applySyncedNonSecretPreferences(
                            themeModeName = remote.themeMode,
                            languageCode = remote.languageCode,
                            pomodoroWork = remote.pomodoroWorkMinutes.toInt(),
                            pomodoroShort = remote.pomodoroShortBreakMinutes.toInt(),
                            pomodoroLong = remote.pomodoroLongBreakMinutes.toInt(),
                            ambientSound = remote.ambientFocusSound,
                            aiStrategyName = remote.aiStrategy,
                            aiFallbackEnabled = remote.aiFallbackEnabled,
                            ollamaModel = remote.ollamaModel,
                            geminiModel = remote.geminiModel,
                            aiTimeoutMs = remote.aiTimeoutMs,
                            remoteUpdatedAtMillis = remote.clientUpdatedAtMillis
                        )
                    }
                }
        }
        viewModelScope.launch {
            if (!settings.value.localOnlyPrivacyMode) {
                cloudSyncManager.flushPendingQueue(localOnlyPrivacyMode = false)
            }
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun setThemeMode(mode: SoraThemeMode) {
        viewModelScope.launch {
            userPreferences.setThemeMode(mode)
            enqueuePreferencesSync()
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            userPreferences.setAppLanguage(language)
            enqueuePreferencesSync()
        }
    }

    fun setLocalOnlyPrivacyMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setLocalOnlyPrivacyMode(enabled)
            if (!enabled) {
                cloudSyncManager.flushPendingQueue(localOnlyPrivacyMode = false)
            }
        }
    }

    fun updateAiSettings(config: AiProviderConfig) {
        viewModelScope.launch {
            userPreferences.updateAiSettings(config)
            enqueuePreferencesSync()
            _bannerMessage.value = lStr(R.string.ai_settings_saved, "AI settings saved.")
        }
    }

    fun triggerManualSync() {
        viewModelScope.launch {
            val res = cloudSyncManager.flushPendingQueue(localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode)
            res.onSuccess {
                _bannerMessage.value = lStr(R.string.sync_completed_msg, "Cloud sync completed successfully.")
            }.onFailure { err ->
                _bannerMessage.value = err.localizedMessage ?: lStr(R.string.sync_status_error, "Sync retry needed")
            }
        }
    }

    fun clearSyncQueue() {
        viewModelScope.launch { cloudSyncManager.clearLocalQueue() }
    }

    private suspend fun enqueuePreferencesSync() {
        val s = settings.value
        val payload = SoraJson.obj(
            "themeMode" to s.themeMode.name,
            "languageCode" to s.appLanguage.code,
            "pomodoroWorkMinutes" to s.pomodoroWorkMinutes.toLong(),
            "pomodoroShortBreakMinutes" to s.pomodoroShortBreakMinutes.toLong(),
            "pomodoroLongBreakMinutes" to s.pomodoroLongBreakMinutes.toLong(),
            "ambientFocusSound" to s.ambientFocusSound,
            "aiStrategy" to s.aiStrategy.name,
            "aiFallbackEnabled" to s.aiFallbackEnabled,
            "ollamaModel" to s.ollamaModel,
            "ollamaVisionModel" to s.ollamaVisionModel,
            "geminiModel" to s.geminiModel,
            "aiTimeoutMs" to s.aiTimeoutMs,
            "clientUpdatedAtMillis" to System.currentTimeMillis()
        ).toJsonString()
        cloudSyncManager.enqueueOrRun(
            op = OfflineSyncOperation(
                opType = SyncOpType.SYNC_PREFERENCES,
                entityId = "app",
                payloadJson = payload
            ),
            localOnlyPrivacyMode = s.localOnlyPrivacyMode
        )
    }

    fun clearGeminiApiKey() {
        viewModelScope.launch {
            userPreferences.clearGeminiApiKey()
            _bannerMessage.value = lStr(R.string.ai_key_cleared, "Custom API key cleared.")
        }
    }

    fun testOllamaConnection(config: AiProviderConfig) {
        viewModelScope.launch {
            _aiTesting.value = true
            _aiConnectionTestResult.value = aiService.testOllamaConnection(config)
            _aiTesting.value = false
        }
    }

    fun testGeminiConnection(config: AiProviderConfig) {
        viewModelScope.launch {
            _aiTesting.value = true
            _aiConnectionTestResult.value = aiService.testGeminiConnection(config)
            _aiTesting.value = false
        }
    }

    fun clearConnectionTestResult() {
        _aiConnectionTestResult.value = null
    }

    private fun lStr(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val ctx = appContext ?: return if (args.isEmpty()) fallback else fallback.format(*args)
        return try {
            val localized = createLocalizedContext(ctx, settings.value.appLanguage)
            if (args.isEmpty()) localized.getString(resId) else localized.getString(resId, *args)
        } catch (_: Exception) {
            if (args.isEmpty()) fallback else fallback.format(*args)
        }
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
                .onFailure {
                    _bannerMessage.value = it.localizedMessage
                        ?: lStr(R.string.vm_layout_save_failed, "Failed to save dashboard layout.")
                }
        }
    }

    fun openBookWorkspace(book: Book) {
        _selectedBookId.value = book.id
        viewModelScope.launch {
            val local = documentProcessor.loadPages(book.localBookId)
            _loadedBookPages.value = local
            if (local.isEmpty()) {
                cloudSyncManager.hydrateMissingBookPages(book).onSuccess { restored ->
                    if (restored.isNotEmpty() && _selectedBookId.value == book.id) {
                        _loadedBookPages.value = restored
                        _bannerMessage.value = lStr(
                            R.string.sync_pages_restored,
                            "Restored %1\$d extracted page(s) from cloud.",
                            restored.size
                        )
                    }
                }
            }
        }
    }

    fun closeBookWorkspace() {
        _selectedBookId.value = null
        _loadedBookPages.value = emptyList()
        _lastRetrievedPassages.value = emptyList()
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
            _ingestionProgress.value = IngestionProgressState(
                true, 0, 1, lStr(R.string.vm_reading_file, "Reading file...")
            )
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
            _ingestionProgress.value = IngestionProgressState(
                true, 0, 1, lStr(R.string.vm_processing_text, "Processing study text...")
            )
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
            _bannerMessage.value = result.extractionError.ifBlank {
                lStr(R.string.vm_doc_failed, "Document extraction failed.")
            }
            return
        }

        _ingestionProgress.value = IngestionProgressState(
            true,
            result.pages.size,
            result.pages.size,
            lStr(R.string.vm_analyzing_structure, "AI analyzing chapters & structure...")
        )
        val fallbackTitle = customTitle.ifBlank { result.fileName.substringBeforeLast(".") }
        val structureRes = aiService.analyzeBookStructure(
            bookId = localId,
            fallbackTitle = fallbackTitle,
            pages = result.pages,
            responseLanguage = settings.value.appLanguage
        )

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
            _bannerMessage.value = lStr(R.string.vm_save_book_failed, "Failed to save book: %1\$s", it.localizedMessage.orEmpty())
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

        // Sync extracted textbook pages via offline-first cloud sync queue
        cloudSyncManager.enqueueOrRun(
            op = OfflineSyncOperation(
                opType = SyncOpType.SYNC_BOOK_PAGES,
                entityId = localId,
                payloadJson = SoraJson.obj("localBookId" to localId).toJsonString()
            ),
            localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode
        )

        // Auto-generate rich intelligence + starter questions + flashcards for Chapter 1
        val firstChapter = savedChapters.firstOrNull()
        if (firstChapter != null) {
            _ingestionProgress.value = IngestionProgressState(
                true,
                result.pages.size,
                result.pages.size,
                lStr(R.string.vm_generating_ch1, "Generating Chapter 1 summaries, glossary & questions...")
            )
            generateAllResourcesForChapterInternal(book.copy(id = localId), firstChapter, result.pages)
        }

        _ingestionProgress.value = IngestionProgressState(false)
        _selectedBookId.value = localId
        _loadedBookPages.value = result.pages
        _bannerMessage.value = if (result.extractionStatus == "PARTIAL") {
            lStr(R.string.vm_imported_partial, "Imported with %1\$d failed page(s). You can retry failed pages anytime.", result.failedPages.size)
        } else {
            lStr(R.string.vm_analyzed_chapters, "\"%1\$s\" analyzed into %2\$d chapter(s).", finalTitle, savedChapters.size)
        }
    }

    fun retryFailedPages(book: Book) {
        if (book.failedPages.isEmpty()) return
        viewModelScope.launch {
            _ingestionProgress.value = IngestionProgressState(
                true, 0, book.failedPages.size, lStr(R.string.vm_retrying_pages, "Retrying failed pages...")
            )
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
            _bannerMessage.value = if (res.failedPages.isEmpty()) {
                lStr(R.string.vm_pages_recovered, "All pages recovered successfully.")
            } else {
                lStr(R.string.vm_pages_still_failed, "%1\$d page(s) still failed.", res.failedPages.size)
            }
        }
    }

    fun generateChapterResources(book: Book, chapter: Chapter, difficultyLevel: String = "Standard") {
        viewModelScope.launch {
            val pages = documentProcessor.loadPages(book.localBookId)
            if (pages.isEmpty()) {
                _bannerMessage.value = lStr(R.string.vm_no_extracted_pages, "No extracted pages available for this book.")
                return@launch
            }
            _aiBusyLabel.value = lStr(
                R.string.vm_generating_ch_resources,
                "Generating AI summaries, concept map & questions for Ch. %1\$d...",
                chapter.chapterNumber
            )
            generateAllResourcesForChapterInternal(book, chapter, pages, difficultyLevel)
            _aiBusyLabel.value = null
        }
    }

    fun cancelFullBookAnalysis() {
        fullBookAnalysisJob?.cancel()
        fullBookAnalysisJob = null
        _fullBookProgress.value = FullBookAnalysisProgress(active = false)
        _aiBusyLabel.value = null
    }

    fun analyzeFullBookAllChapters(book: Book, chapters: List<Chapter>, difficultyLevel: String = "Standard") {
        if (chapters.isEmpty()) return
        fullBookAnalysisJob?.cancel()
        fullBookAnalysisJob = viewModelScope.launch {
            try {
                val pages = documentProcessor.loadPages(book.localBookId)
                if (pages.isEmpty()) {
                    _bannerMessage.value = lStr(R.string.vm_no_extracted_pages, "No extracted pages available for this book.")
                    return@launch
                }
                val result = fullBookEngine.analyzeAllChapters(
                    bookId = book.id,
                    bookTitle = book.title,
                    chapters = chapters,
                    pages = pages,
                    difficultyLevel = difficultyLevel,
                    responseLanguage = settings.value.appLanguage,
                    onProgress = { prog ->
                        _fullBookProgress.value = prog
                        if (prog.active) {
                            _aiBusyLabel.value = lStr(
                                R.string.fullbook_progress,
                                "Analyzing Ch. %1\$d/%2\$d: %3\$s",
                                prog.currentChapterIndex,
                                prog.totalChapters,
                                prog.currentChapterTitle
                            )
                        }
                    },
                    onChapterReady = { enriched, glossary, genQuestions, genCards ->
                        repository.saveChapter(book.id, enriched)
                        if (glossary.isNotEmpty()) repository.addGlossaryEntries(book.id, glossary)
                        if (genQuestions.isNotEmpty()) repository.addQuestions(genQuestions)
                        if (genCards.isNotEmpty()) repository.addFlashcards(genCards)
                    }
                )
                result.onSuccess { res ->
                    val updates = mutableMapOf<String, Any>()
                    if (res.consolidatedOverview.isNotBlank()) updates["overview"] = res.consolidatedOverview
                    if (res.consolidatedFormulaSheet.isNotBlank()) updates["formulaSheet"] = res.consolidatedFormulaSheet
                    if (updates.isNotEmpty()) repository.updateBook(book.id, updates)
                    _bannerMessage.value = if (res.failedChapters == 0) {
                        lStr(R.string.fullbook_done, "Full-book analysis complete: %1\$d chapter(s) processed.", res.completedChapters)
                    } else {
                        lStr(R.string.fullbook_partial, "Full-book analysis finished (%1\$d ok, %2\$d failed).", res.completedChapters, res.failedChapters)
                    }
                }.onFailure { err ->
                    _bannerMessage.value = err.localizedMessage ?: lStr(R.string.vm_analysis_failed, "Analysis failed.")
                }
            } finally {
                _fullBookProgress.value = FullBookAnalysisProgress(active = false)
                _aiBusyLabel.value = null
            }
        }
    }

    private suspend fun generateAllResourcesForChapterInternal(
        book: Book,
        chapter: Chapter,
        pages: List<BookPageContent>,
        difficultyLevel: String = "Standard"
    ) {
        val lang = settings.value.appLanguage
        val intelRes = aiService.generateChapterIntelligence(book.id, chapter, pages, difficultyLevel, lang)
        intelRes.onSuccess { (enrichedChapter, glossary) ->
            repository.saveChapter(book.id, enrichedChapter)
            if (glossary.isNotEmpty()) {
                repository.addGlossaryEntries(book.id, glossary)
            }
        }.onFailure {
            _bannerMessage.value = lStr(R.string.vm_ch_intel_err, "Chapter intelligence error: %1\$s", it.localizedMessage.orEmpty())
        }

        val qRes = aiService.generateQuestionsForChapter(
            bookId = book.id,
            bookTitle = book.title,
            chapter = chapter,
            pages = pages,
            requestedTypes = listOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "APPLICATION", "COMPARE_CONTRAST", "CALCULATION"),
            count = 8,
            responseLanguage = lang
        )
        qRes.onSuccess { generated ->
            repository.addQuestions(generated)
        }.onFailure {
            _bannerMessage.value = lStr(R.string.vm_q_gen_err, "Question generation error: %1\$s", it.localizedMessage.orEmpty())
        }

        val fcRes = aiService.generateFlashcardsForChapter(book.id, chapter, pages, count = 8, responseLanguage = lang)
        fcRes.onSuccess { cards ->
            repository.addFlashcards(cards)
        }.onFailure {
            _bannerMessage.value = lStr(R.string.vm_fc_gen_err, "Flashcard generation error: %1\$s", it.localizedMessage.orEmpty())
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
            _aiBusyLabel.value = lStr(R.string.vm_generating_questions, "Generating %1\$d custom questions...", count)
            aiService.generateQuestionsForChapter(
                bookId = book.id,
                bookTitle = book.title,
                chapter = chapter,
                pages = pages,
                requestedTypes = types,
                count = count,
                difficultyHint = difficultyHint,
                responseLanguage = settings.value.appLanguage
            )
                .onSuccess { items ->
                    repository.addQuestions(items)
                    _bannerMessage.value = lStr(R.string.vm_added_questions, "Added %1\$d questions to Question Bank.", items.size)
                }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: lStr(R.string.vm_q_gen_err, "Question generation failed.") }
            _aiBusyLabel.value = null
        }
    }

    // --- BOOK OPERATIONS ---
    fun updateBookReadingPage(book: Book, page: Int) {
        val safePage = page.coerceIn(1, book.totalPages.toInt().coerceAtLeast(1))
        viewModelScope.launch {
            cloudSyncManager.enqueueOrRun(
                op = OfflineSyncOperation(
                    opType = SyncOpType.UPDATE_BOOK_FIELDS,
                    entityId = book.id,
                    payloadJson = SoraJson.obj("currentPage" to safePage.toLong()).toJsonString()
                ),
                localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode
            )
        }
    }

    fun toggleBookPageBookmark(book: Book, page: Int) {
        val updated = if (book.bookmarkedPages.contains(page.toLong())) {
            book.bookmarkedPages - page.toLong()
        } else {
            (book.bookmarkedPages + page.toLong()).distinct().sorted()
        }
        viewModelScope.launch {
            cloudSyncManager.enqueueOrRun(
                op = OfflineSyncOperation(
                    opType = SyncOpType.UPDATE_BOOK_FIELDS,
                    entityId = book.id,
                    payloadJson = SoraJson.obj("bookmarkedPages" to updated).toJsonString()
                ),
                localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode
            )
        }
    }

    fun addBookHighlight(book: Book, page: Int, highlightText: String) {
        if (highlightText.isBlank()) return
        val existingObj = try {
            SoraJson.parseObject(book.highlightsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            JsonObj(emptyMap())
        }
        val key = page.toString()
        val existing = existingObj.str(key, "")
        val combined = if (existing.isBlank()) highlightText.trim() else "$existing\n• ${highlightText.trim()}"
        val updatedMap = existingObj.entries.toMutableMap().apply {
            put(key, JsonStr(combined.take(1500)))
        }
        val jsonStr = JsonObj(updatedMap).toJsonString()
        viewModelScope.launch {
            cloudSyncManager.enqueueOrRun(
                op = OfflineSyncOperation(
                    opType = SyncOpType.UPDATE_BOOK_FIELDS,
                    entityId = book.id,
                    payloadJson = SoraJson.obj("highlightsJson" to jsonStr).toJsonString()
                ),
                localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode
            )
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
            books.value.firstOrNull { it.id == bookId }?.let {
                documentProcessor.deleteBookCache(it.localBookId)
            }
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
            _bannerMessage.value = lStr(R.string.vm_question_flagged, "Question flagged for review.")
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
            _bannerMessage.value = lStr(R.string.vm_exam_no_questions, "No matching questions available for this exam configuration.")
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
            if (settings.value.localOnlyPrivacyMode) {
                val localExam = exam.copy(id = "offline_${UUID.randomUUID().toString().take(12)}")
                offlineExamCache.saveExam(localExam)
                _offlineExams.value = offlineExamCache.loadAll()
                onCreated(localExam.id)
                return@launch
            }
            val cloudRes = repository.createExam(exam)
            cloudRes.onSuccess { id ->
                offlineExamCache.saveExam(exam.copy(id = id))
                _offlineExams.value = offlineExamCache.loadAll()
                onCreated(id)
            }.onFailure {
                // Offline fallback so exams work cleanly even offline
                val localExam = exam.copy(id = "offline_${UUID.randomUUID().toString().take(12)}")
                offlineExamCache.saveExam(localExam)
                _offlineExams.value = offlineExamCache.loadAll()
                _bannerMessage.value = lStr(R.string.exams_offline_saved, "Saved offline and queued for cloud sync.")
                onCreated(localExam.id)
            }
        }
    }

    fun submitExam(
        exam: ExamRecord,
        examQuestions: List<QuestionItem>,
        answersByQuestionId: Map<String, String>,
        flaggedIds: Set<String>,
        timeSpentSeconds: Int,
        useAiOpenEndedGrading: Boolean = true
    ) {
        viewModelScope.launch {
            val evalMap = LinkedHashMap<String, AnswerEvaluation>()
            val lang = settings.value.appLanguage
            val aiEvalFn: (suspend (QuestionItem, String) -> Result<com.example.data.ai.AiAnswerGrade>)? =
                if (useAiOpenEndedGrading) {
                    { q, ans ->
                        aiService.evaluateOpenEndedAnswer(
                            questionText = q.questionText,
                            referenceAnswer = q.correctAnswer,
                            explanation = q.explanation,
                            studentAnswer = ans,
                            responseLanguage = lang
                        )
                    }
                } else null

            examQuestions.forEach { q ->
                val userAns = answersByQuestionId[q.id]?.trim().orEmpty()
                if (userAns.isNotBlank()) {
                    val eval = ExamEngine.evaluateQuestion(q, userAns, aiEvalFn)
                    evalMap[q.id] = eval
                    repository.recordQuestionAttempt(q, eval.isCorrect)
                }
            }
            _lastExamEvaluations.value = evalMap

            val summary = ExamEngine.computeExamSummary(
                examQuestions = examQuestions,
                evaluations = evalMap,
                answersByQuestionId = answersByQuestionId
            )

            val weakCh = summary.weakChapters.map { lStr(R.string.qb_chapter_short, "Ch.%1\$d", it) }
            val recommendation = if (weakCh.isEmpty()) {
                lStr(
                    R.string.vm_recommendation_strong,
                    "Strong mastery across all tested chapters. Continue spaced-repetition flashcard reviews."
                )
            } else {
                lStr(
                    R.string.vm_recommendation_focus,
                    "Focus revision on %1\$s and review missed items in your Mistake Notebook.",
                    weakCh.joinToString(", ")
                )
            }

            val answersJsonObj = JsonObj(answersByQuestionId.mapValues { (_, v) -> JsonStr(v) })
            val submittedExam = exam.copy(
                status = "SUBMITTED",
                answersJson = answersJsonObj.toJsonString(),
                flaggedQuestionIds = flaggedIds.toList().take(200),
                score = summary.score,
                percentage = summary.percentage,
                correctCount = summary.correctCount,
                incorrectCount = summary.incorrectCount,
                unansweredCount = summary.unansweredCount,
                timeSpentSeconds = timeSpentSeconds.toLong(),
                analyticsJson = summary.analyticsJson,
                recommendedRevision = recommendation
            )
            offlineExamCache.saveExam(submittedExam)
            _offlineExams.value = offlineExamCache.loadAll()

            if (!exam.id.startsWith("offline_")) {
                val payload = SoraJson.obj(
                    "status" to "SUBMITTED",
                    "answersJson" to submittedExam.answersJson,
                    "flaggedQuestionIds" to submittedExam.flaggedQuestionIds,
                    "score" to submittedExam.score,
                    "percentage" to submittedExam.percentage,
                    "correctCount" to submittedExam.correctCount,
                    "incorrectCount" to submittedExam.incorrectCount,
                    "unansweredCount" to submittedExam.unansweredCount,
                    "timeSpentSeconds" to submittedExam.timeSpentSeconds,
                    "analyticsJson" to submittedExam.analyticsJson,
                    "recommendedRevision" to submittedExam.recommendedRevision
                ).toJsonString()
                cloudSyncManager.enqueueOrRun(
                    op = OfflineSyncOperation(
                        opType = SyncOpType.SUBMIT_EXAM,
                        entityId = exam.id,
                        payloadJson = payload
                    ),
                    localOnlyPrivacyMode = settings.value.localOnlyPrivacyMode
                )
            }
            examQuestions.map { it.bookId to it.chapterNumber }.distinct().forEach { (bId, ch) ->
                recalculateBookAndChapterMastery(bId, ch)
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
        includePageImage: Boolean = false,
        fullBookSearch: Boolean = true
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

            val allPages = if (_selectedBookId.value == book.id && _loadedBookPages.value.isNotEmpty()) {
                _loadedBookPages.value
            } else {
                documentProcessor.loadPages(book.localBookId)
            }
            val retrieved = if (fullBookSearch && allPages.isNotEmpty()) {
                BookPassageRetriever.retrieve(
                    query = prompt.trim(),
                    pages = allPages,
                    chapters = selectedBookChapters.value,
                    topK = 5,
                    maxTotalChars = 6500
                )
            } else emptyList()
            _lastRetrievedPassages.value = retrieved

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
                    pageBitmap = bitmap,
                    responseLanguage = settings.value.appLanguage,
                    retrievedPassages = retrieved
                ).collect { chunk ->
                    finalReply = chunk
                    _tutorStreamingText.value = chunk
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tutor stream error", e)
                _bannerMessage.value = e.localizedMessage ?: lStr(R.string.vm_tutor_failed, "AI Tutor request failed.")
            } finally {
                bitmap?.recycle()
                _tutorStreamingText.value = null
            }

            if (finalReply.isNotBlank()) {
                val citations = (retrieved.map { p ->
                    if (p.chapterNumber > 0) "Ch. ${p.chapterNumber}, p. ${p.pageNumber}" else "p. ${p.pageNumber}"
                } + listOfNotNull(
                    chapter?.let { "Ch. ${it.chapterNumber}" },
                    page?.let { "p. ${it.pageNumber}" }
                )).distinct().take(10)
                repository.addTutorMessage(
                    TutorMessage(
                        bookId = book.id,
                        chapterNumber = chNum,
                        pageNumber = pageNum,
                        mode = mode.name,
                        role = "MODEL",
                        text = finalReply,
                        citations = citations,
                        distinctionNote = lStr(R.string.vm_tutor_distinction, "Textbook references cited; supplementary explanations labeled.")
                    )
                )
            }
        }
    }

    fun runReadingAssistant(action: String, text: String, pageNumber: Int, bookTitle: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = lStr(R.string.vm_reading_assistant_busy, "Running Reading Assistant (%1\$s)...", action)
            aiService.runReadingAssistant(action, text, pageNumber, bookTitle, settings.value.appLanguage)
                .onSuccess { _readingAssistantResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: lStr(R.string.vm_assistant_failed, "Assistant failed.") }
            _aiBusyLabel.value = null
        }
    }

    fun clearReadingAssistant() {
        _readingAssistantResult.value = null
    }

    fun evaluateTeachBack(conceptTitle: String, referenceText: String, studentText: String) {
        if (studentText.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = lStr(R.string.vm_eval_teachback, "Evaluating your Teach-Back explanation...")
            aiService.evaluateTeachBack(conceptTitle, referenceText, studentText, settings.value.appLanguage)
                .onSuccess { _teachBackResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: lStr(R.string.vm_teachback_failed, "Teach-Back failed.") }
            _aiBusyLabel.value = null
        }
    }

    fun clearTeachBack() {
        _teachBackResult.value = null
    }

    fun runChapterComparisonOrGapAnalysis(mode: String, book: Book, chapter: Chapter) {
        viewModelScope.launch {
            val pages = documentProcessor.loadPages(book.localBookId)
            _aiBusyLabel.value = lStr(R.string.vm_analyzing_mode, "Analyzing %1\$s...", mode)
            aiService.generateComparisonOrGapAnalysis(mode, chapter, pages, settings.value.appLanguage)
                .onSuccess { _comparisonOrGapResult.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: lStr(R.string.vm_analysis_failed, "Analysis failed.") }
            _aiBusyLabel.value = null
        }
    }

    fun clearComparisonResult() {
        _comparisonOrGapResult.value = null
    }

    fun solveEquation(equationInput: String) {
        if (equationInput.isBlank()) return
        viewModelScope.launch {
            _aiBusyLabel.value = lStr(R.string.vm_solving_eq, "Solving step-by-step...")
            aiService.solveEquationWithSteps(equationInput.trim(), settings.value.appLanguage)
                .onSuccess { _equationSolution.value = it }
                .onFailure { _bannerMessage.value = it.localizedMessage ?: lStr(R.string.vm_solver_failed, "Equation solver failed.") }
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
            _bannerMessage.value = lStr(
                R.string.vm_note_cards_hint,
                "Add a few bullet points or 'Term: Definition' lines in your note first."
            )
            return
        }
        viewModelScope.launch {
            repository.addFlashcards(cards)
            _bannerMessage.value = lStr(
                R.string.vm_created_cards_from_note,
                "Created %1\$d flashcard(s) from \"%2\$s\".",
                cards.size,
                note.title
            )
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
                lStr(
                    R.string.vm_rescheduled_missed,
                    "Rescheduled %1\$d missed session(s) realistically across your upcoming capacity.",
                    overdue.size
                )
            } else {
                lStr(R.string.vm_plan_balanced, "Study plan balanced with your Smart Revision Queue.")
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
                            completeFocusSession(notes = lStr(R.string.vm_focus_completed_note, "Completed timer goal"))
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
            _bannerMessage.value = lStr(
                R.string.vm_recorded_focus,
                "Recorded %1\$dm focus session for %2\$s.",
                mins,
                s.subjectName
            )
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

    // --- ACCOUNT SECURITY & DATA EXPORT (TASK 5) ---
    fun exportAllUserDataJson(): String {
        val exportObj = SoraJson.obj(
            "exportedAtMillis" to System.currentTimeMillis(),
            "accountUidMasked" to InputValidator.maskUid(currentUserId),
            "displayName" to (profile.value?.displayName.orEmpty()),
            "language" to settings.value.appLanguage.code,
            "themeMode" to settings.value.themeMode.name,
            "books" to books.value.map { b ->
                SoraJson.obj(
                    "id" to b.id,
                    "title" to b.title,
                    "subjectName" to b.subjectName,
                    "totalPages" to b.totalPages,
                    "currentPage" to b.currentPage,
                    "masteryScore" to b.masteryScore,
                    "bookmarkedPages" to b.bookmarkedPages,
                    "overview" to b.overview
                )
            },
            "notes" to notes.value.map { n ->
                SoraJson.obj(
                    "id" to n.id,
                    "title" to n.title,
                    "bookId" to n.bookId,
                    "chapterNumber" to n.chapterNumber,
                    "linkedPage" to n.linkedPage,
                    "contentMarkdown" to n.contentMarkdown
                )
            },
            "flashcards" to flashcards.value.map { fc ->
                SoraJson.obj(
                    "id" to fc.id,
                    "deckName" to fc.deckName,
                    "front" to fc.front,
                    "back" to fc.back,
                    "easeFactor" to fc.easeFactor,
                    "intervalDays" to fc.intervalDays
                )
            },
            "exams" to exams.value.map { ex ->
                SoraJson.obj(
                    "id" to ex.id,
                    "title" to ex.title,
                    "status" to ex.status,
                    "score" to ex.score,
                    "totalMarks" to ex.totalMarks,
                    "percentage" to ex.percentage
                )
            },
            "plannerTasks" to plannerTasks.value.map { t ->
                SoraJson.obj(
                    "id" to t.id,
                    "title" to t.title,
                    "taskType" to t.taskType,
                    "completed" to t.completed,
                    "dueDateMillis" to t.dueDateMillis
                )
            },
            "citations" to citations.value.map { c ->
                SoraJson.obj(
                    "style" to c.style,
                    "title" to c.title,
                    "formattedText" to c.formattedText
                )
            }
        )
        return exportObj.toJsonString()
    }

    fun clearLocalCaches() {
        viewModelScope.launch {
            documentProcessor.clearAllBooksCache()
            offlineExamCache.clearAll()
            _offlineExams.value = emptyList()
            cloudSyncManager.clearLocalQueue()
            _loadedBookPages.value = emptyList()
            _bannerMessage.value = lStr(R.string.sec_cache_cleared, "Local book and offline cache cleared.")
        }
    }

    fun deleteAccountAndAllData(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val currentUser = Firebase.auth.currentUser
            val lastSignIn = currentUser?.metadata?.lastSignInTimestamp ?: 0L
            if (currentUser != null && lastSignIn > 0L && (System.currentTimeMillis() - lastSignIn) > 270_000L) {
                _bannerMessage.value = lStr(
                    R.string.sec_reauth_required,
                    "Please sign out and sign in with Google again before deleting your account."
                )
                return@launch
            }

            val cloudRes = repository.deleteAllUserCloudData()
            if (cloudRes.isFailure) {
                _bannerMessage.value = cloudRes.exceptionOrNull()?.localizedMessage
                    ?: lStr(R.string.sec_reauth_required, "Please sign in again before deleting.")
                return@launch
            }

            try {
                currentUser?.delete()?.await()
                documentProcessor.clearAllBooksCache()
                offlineExamCache.clearAll()
                cloudSyncManager.clearLocalQueue()
                userPreferences.clearAllPreferences()
                _bannerMessage.value = lStr(R.string.sec_account_deleted, "Account and study data deleted.")
                onDeleted()
            } catch (_: FirebaseAuthRecentLoginRequiredException) {
                _bannerMessage.value = lStr(
                    R.string.sec_reauth_required,
                    "Please sign out and sign in with Google again before deleting your account."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _bannerMessage.value = e.localizedMessage
                    ?: lStr(R.string.sec_reauth_required, "Please sign out and sign in again.")
            }
        }
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
        fullBookAnalysisJob?.cancel()
    }
}
