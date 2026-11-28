package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.core.AppLanguage
import com.example.core.LocalAppLanguage
import com.example.core.SoraSettings
import com.example.core.UserPreferences
import com.example.core.authStateFlow
import com.example.core.createLocalizedContext
import com.example.core.findActivity
import com.example.core.signOut
import com.example.data.ai.SoraAiService
import com.example.data.export.ExportManager
import com.example.data.local.DocumentProcessor
import com.example.data.model.ExamPreset
import com.example.data.repository.SoraRepository
import com.example.ui.components.UploadTextbookDialog
import com.example.ui.components.localizedLabel
import com.example.ui.screens.*
import com.example.ui.theme.SoraTheme
import com.example.ui.theme.SoraThemeMode
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

private data class SoraNavTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tag: String
)

private val SORA_NAV_TABS = listOf(
    SoraNavTab(R.string.nav_home, Icons.Rounded.Home, "nav_home"),
    SoraNavTab(R.string.nav_library, Icons.Rounded.LibraryBooks, "nav_library"),
    SoraNavTab(R.string.nav_exams, Icons.Rounded.Quiz, "nav_exams"),
    SoraNavTab(R.string.nav_planner, Icons.Rounded.EventNote, "nav_planner"),
    SoraNavTab(R.string.nav_tools, Icons.Rounded.Insights, "nav_tools")
)

private data class PendingUploadMeta(
    val title: String = "",
    val subject: String = "",
    val semester: String = "",
    val folder: String = ""
)

@Composable
fun SoraApp() {
    val baseContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPreferences = remember(baseContext) { UserPreferences(baseContext.applicationContext) }
    val settings by userPreferences.settings.collectAsStateWithLifecycle(initialValue = SoraSettings())

    val localizedContext = remember(baseContext, settings.appLanguage) {
        createLocalizedContext(baseContext, settings.appLanguage)
    }

    val currentUser by remember { Firebase.auth.authStateFlow() }
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        LocalLayoutDirection provides settings.appLanguage.layoutDirection,
        LocalAppLanguage provides settings.appLanguage
    ) {
        SoraTheme(themeMode = settings.themeMode, appLanguage = settings.appLanguage) {
            if (currentUser == null) {
                AuthScreen(
                    selectedLanguage = settings.appLanguage,
                    onSelectLanguage = { lang -> scope.launch { userPreferences.setAppLanguage(lang) } },
                    onAuthSuccess = {}
                )
            } else {
                val uid = currentUser!!.uid
                key(uid) {
                    SoraAuthenticatedContent(
                        uid = uid,
                        userPreferences = userPreferences,
                        settings = settings
                    )
                }
            }
        }
    }
}

@Composable
private fun SoraAuthenticatedContent(
    uid: String,
    userPreferences: UserPreferences,
    settings: SoraSettings
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context.findActivity() ?: context) }

    val aiConfigRef = remember { AtomicReference(settings.toAiProviderConfig()) }
    SideEffect {
        aiConfigRef.set(settings.toAiProviderConfig())
    }

    val viewModel: SoraViewModel = viewModel(
        key = uid,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = FirebaseFirestore.getInstance(appContext.getString(R.string.firestore_database_id))
                val repository = SoraRepository(db, Firebase.auth)
                val aiService = SoraAiService(configProvider = { aiConfigRef.get() })
                val processor = DocumentProcessor(appContext, aiService)
                return SoraViewModel(repository, aiService, processor, userPreferences, uid, appContext) as T
            }
        }
    )

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val selectedBookId by viewModel.selectedBookId.collectAsStateWithLifecycle()
    val chapters by viewModel.selectedBookChapters.collectAsStateWithLifecycle()
    val glossary by viewModel.selectedBookGlossary.collectAsStateWithLifecycle()
    val loadedPages by viewModel.loadedBookPages.collectAsStateWithLifecycle()
    val questions by viewModel.questions.collectAsStateWithLifecycle()
    val flashcards by viewModel.flashcards.collectAsStateWithLifecycle()
    val exams by viewModel.exams.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val tasks by viewModel.plannerTasks.collectAsStateWithLifecycle()
    val focusSessions by viewModel.focusSessions.collectAsStateWithLifecycle()
    val tutorMessages by viewModel.tutorMessages.collectAsStateWithLifecycle()
    val citations by viewModel.citations.collectAsStateWithLifecycle()
    val ingestionProgress by viewModel.ingestionProgress.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val aiBusyLabel by viewModel.aiBusyLabel.collectAsStateWithLifecycle()
    val tutorStreamingText by viewModel.tutorStreamingText.collectAsStateWithLifecycle()
    val readingAssistantResult by viewModel.readingAssistantResult.collectAsStateWithLifecycle()
    val teachBackResult by viewModel.teachBackResult.collectAsStateWithLifecycle()
    val comparisonOrGapResult by viewModel.comparisonOrGapResult.collectAsStateWithLifecycle()
    val equationSolution by viewModel.equationSolution.collectAsStateWithLifecycle()
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val lastAiResponse by viewModel.lastAiResponse.collectAsStateWithLifecycle()
    val aiConnectionTestResult by viewModel.aiConnectionTestResult.collectAsStateWithLifecycle()
    val aiTesting by viewModel.aiTesting.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val fullBookProgress by viewModel.fullBookProgress.collectAsStateWithLifecycle()
    val lastRetrievedPassages by viewModel.lastRetrievedPassages.collectAsStateWithLifecycle()
    val lastExamEvaluations by viewModel.lastExamEvaluations.collectAsStateWithLifecycle()

    var currentTab by remember { mutableIntStateOf(0) }
    var plannerInitialSubTab by remember { mutableIntStateOf(0) }
    var activeExamId by remember { mutableStateOf<String?>(null) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showAiSettingsSheet by remember { mutableStateOf(false) }
    var showSyncCenter by remember { mutableStateOf(false) }
    var showAccountSecurity by remember { mutableStateOf(false) }
    var pendingMeta by remember { mutableStateOf(PendingUploadMeta()) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.uploadTextbookFromUri(
                uri = uri,
                customTitle = pendingMeta.title,
                subjectName = pendingMeta.subject,
                semester = pendingMeta.semester,
                folder = pendingMeta.folder
            )
            currentTab = 1
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.uploadTextbookFromUri(
                uri = uri,
                customTitle = pendingMeta.title,
                subjectName = pendingMeta.subject,
                semester = pendingMeta.semester,
                folder = pendingMeta.folder
            )
            currentTab = 1
        }
    }

    val activeExam = remember(exams, activeExamId) {
        activeExamId?.let { id -> exams.firstOrNull { it.id == id } }
    }
    val selectedBook = remember(books, selectedBookId) {
        selectedBookId?.let { id -> books.firstOrNull { it.id == id } }
    }
    val revisionQueue = remember(books, chapters, questions, flashcards, tasks) {
        viewModel.getSmartRevisionQueue()
    }

    val quickQuizEmptyMsg = stringResource(R.string.quick_quiz_empty)
    val quickQuizTitle = stringResource(R.string.quick_quiz_title)
    val retakeMissedTitle = stringResource(R.string.preset_mistake_review)
    val exportSuccessMsg = stringResource(R.string.sec_export_done)

    when {
        activeExam != null -> {
            ExamSessionScreen(
                exam = activeExam,
                allQuestions = questions,
                evaluations = lastExamEvaluations,
                onRetakeMissed = { missedList ->
                    viewModel.createAndStartExam(
                        title = retakeMissedTitle,
                        preset = ExamPreset.MISTAKE_REVIEW,
                        selectedQuestions = missedList,
                        durationMinutes = (missedList.size * 2).coerceIn(10, 60),
                        allowRevisit = true,
                        showExplanationEach = false,
                        onCreated = { newId -> activeExamId = newId }
                    )
                },
                viewModel = viewModel,
                onClose = { activeExamId = null }
            )
        }
        selectedBook != null -> {
            val presetTitles = ExamPreset.entries.associateWith { it.localizedLabel() }
            val studyGoalPattern = stringResource(R.string.focus_goal_study_book, selectedBook.title)
            BookWorkspaceScreen(
                book = selectedBook,
                chapters = chapters,
                glossary = glossary,
                pages = loadedPages,
                allQuestions = questions,
                allFlashcards = flashcards,
                allNotes = notes,
                tutorMessages = tutorMessages,
                tutorStreamingText = tutorStreamingText,
                aiBusyLabel = aiBusyLabel,
                readingAssistantResult = readingAssistantResult,
                teachBackResult = teachBackResult,
                comparisonOrGapResult = comparisonOrGapResult,
                fullBookProgress = fullBookProgress,
                retrievedPassages = lastRetrievedPassages,
                viewModel = viewModel,
                onLaunchExam = { preset, qList ->
                    val presetTitle = presetTitles[preset] ?: preset.label
                    viewModel.createAndStartExam(
                        title = "${selectedBook.title}: $presetTitle",
                        preset = preset,
                        selectedQuestions = qList,
                        durationMinutes = preset.durationMinutes,
                        allowRevisit = preset != ExamPreset.FINAL_SIMULATION,
                        showExplanationEach = false,
                        onCreated = { examId -> activeExamId = examId }
                    )
                },
                onLaunchFocusForBook = { b, ch ->
                    viewModel.configureFocusSession(
                        mode = "POMODORO",
                        minutes = settings.pomodoroWorkMinutes,
                        subjectName = b.subjectName,
                        bookId = b.id,
                        chapterNum = ch?.chapterNumber ?: 1L,
                        goalText = studyGoalPattern
                    )
                    viewModel.closeBookWorkspace()
                    plannerInitialSubTab = 1
                    currentTab = 3
                },
                onBack = { viewModel.closeBookWorkspace() }
            )
        }
        else -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                SORA_NAV_TABS.forEachIndexed { idx, tab ->
                                    val label = stringResource(tab.labelRes)
                                    NavigationBarItem(
                                        selected = currentTab == idx,
                                        onClick = {
                                            if (idx == 3) plannerInitialSubTab = 0
                                            currentTab = idx
                                        },
                                        icon = { Icon(tab.icon, contentDescription = label) },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag(tab.tag)
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useRail) {
                            NavigationRail {
                                SORA_NAV_TABS.forEachIndexed { idx, tab ->
                                    val label = stringResource(tab.labelRes)
                                    NavigationRailItem(
                                        selected = currentTab == idx,
                                        onClick = {
                                            if (idx == 3) plannerInitialSubTab = 0
                                            currentTab = idx
                                        },
                                        icon = { Icon(tab.icon, contentDescription = label) },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag(tab.tag)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            when (currentTab) {
                                0 -> HomeDashboardScreen(
                                    profile = profile,
                                    books = books,
                                    tasks = tasks,
                                    questions = questions,
                                    flashcards = flashcards,
                                    exams = exams,
                                    focusSessions = focusSessions,
                                    revisionQueue = revisionQueue,
                                    themeMode = settings.themeMode,
                                    appLanguage = settings.appLanguage,
                                    syncState = syncState,
                                    onOpenSyncCenter = { showSyncCenter = true },
                                    onOpenAccountSecurity = { showAccountSecurity = true },
                                    onSelectLanguage = { viewModel.setAppLanguage(it) },
                                    onOpenAiSettings = { showAiSettingsSheet = true },
                                    onCycleTheme = {
                                        val next = when (settings.themeMode) {
                                            SoraThemeMode.DARK -> SoraThemeMode.LIGHT
                                            SoraThemeMode.LIGHT -> SoraThemeMode.SYSTEM
                                            SoraThemeMode.SYSTEM -> SoraThemeMode.DARK
                                        }
                                        viewModel.setThemeMode(next)
                                    },
                                    onSignOut = {
                                        signOut(credentialManager = credentialManager, scope = scope)
                                    },
                                    onUploadBookClick = { showUploadDialog = true },
                                    onOpenBook = { viewModel.openBookWorkspace(it) },
                                    onNavigateTab = { currentTab = it },
                                    onStartQuickQuiz = {
                                        val activeQ = questions.filter { !it.hidden }.shuffled().take(5)
                                        if (activeQ.isEmpty()) {
                                            viewModel.showBanner(quickQuizEmptyMsg)
                                        } else {
                                            viewModel.createAndStartExam(
                                                title = quickQuizTitle,
                                                preset = ExamPreset.QUICK_QUIZ,
                                                selectedQuestions = activeQ,
                                                durationMinutes = 10,
                                                allowRevisit = true,
                                                showExplanationEach = false,
                                                onCreated = { examId -> activeExamId = examId }
                                            )
                                        }
                                    },
                                    onStartFocus = {
                                        plannerInitialSubTab = 1
                                        currentTab = 3
                                    },
                                    onToggleTask = { viewModel.toggleTaskCompleted(it) },
                                    onUpdateDashboardSections = { viewModel.updateDashboardOrder(it) }
                                )

                                1 -> LibraryScreen(
                                    books = books,
                                    subjects = subjects,
                                    ingestionProgress = ingestionProgress,
                                    onUploadClick = { showUploadDialog = true },
                                    onOpenBook = { viewModel.openBookWorkspace(it) },
                                    onRetryFailedPages = { viewModel.retryFailedPages(it) },
                                    onRenameBook = { id, newTitle -> viewModel.renameBook(id, newTitle) },
                                    onArchiveBook = { viewModel.toggleArchiveBook(it) },
                                    onDeleteBook = { viewModel.deleteBook(it) },
                                    onCreateSubject = { n, sem, fol, gr -> viewModel.addSubject(n, sem, fol, gr) }
                                )

                                2 -> ExamsPracticeScreen(
                                    books = books,
                                    questions = questions,
                                    exams = exams,
                                    viewModel = viewModel,
                                    onOpenExam = { examId -> activeExamId = examId }
                                )

                                3 -> PlannerFocusScreen(
                                    initialSubTab = plannerInitialSubTab,
                                    books = books,
                                    tasks = tasks,
                                    focusSessions = focusSessions,
                                    revisionQueue = revisionQueue,
                                    focusState = focusState,
                                    settings = settings,
                                    viewModel = viewModel
                                )

                                4 -> ToolsAnalyticsScreen(
                                    profile = profile,
                                    books = books,
                                    questions = questions,
                                    flashcards = flashcards,
                                    exams = exams,
                                    notes = notes,
                                    focusSessions = focusSessions,
                                    citations = citations,
                                    equationSolution = equationSolution,
                                    aiBusyLabel = aiBusyLabel,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUploadDialog) {
        val subjList = (subjects.map { it.name } + books.map { it.subjectName }).distinct().filter { it.isNotBlank() }
        UploadTextbookDialog(
            subjects = subjList,
            onDismiss = { showUploadDialog = false },
            onPickFile = { title, subj, sem, fol ->
                pendingMeta = PendingUploadMeta(title, subj, sem, fol)
                showUploadDialog = false
                documentPickerLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "text/plain",
                        "image/*"
                    )
                )
            },
            onPickImage = { title, subj, sem, fol ->
                pendingMeta = PendingUploadMeta(title, subj, sem, fol)
                showUploadDialog = false
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onSubmitText = { title, subj, sem, fol, rawText ->
                showUploadDialog = false
                viewModel.uploadTextbookFromText(title, subj, sem, fol, rawText)
                currentTab = 1
            }
        )
    }

    if (showAiSettingsSheet) {
        AiSettingsSheet(
            settings = settings,
            lastAiResponse = lastAiResponse,
            connectionTestResult = aiConnectionTestResult,
            isTestingConnection = aiTesting,
            onSelectLanguage = { viewModel.setAppLanguage(it) },
            onSelectTheme = { viewModel.setThemeMode(it) },
            onSaveAiConfig = { viewModel.updateAiSettings(it) },
            onClearApiKey = { viewModel.clearGeminiApiKey() },
            onTestOllama = { viewModel.testOllamaConnection(it) },
            onTestGemini = { viewModel.testGeminiConnection(it) },
            onClearConnectionTest = { viewModel.clearConnectionTestResult() },
            onDismiss = { showAiSettingsSheet = false }
        )
    }

    if (showSyncCenter) {
        SyncCenterDialog(
            syncState = syncState,
            appLanguage = settings.appLanguage,
            onSyncNow = { viewModel.triggerManualSync() },
            onClearQueue = { viewModel.clearSyncQueue() },
            onDismiss = { showSyncCenter = false }
        )
    }

    if (showAccountSecurity) {
        AccountSecuritySheet(
            uid = uid,
            profile = profile,
            settings = settings,
            syncState = syncState,
            onToggleLocalPrivacyMode = { viewModel.setLocalOnlyPrivacyMode(it) },
            onOpenSyncCenter = { showSyncCenter = true },
            onExportAllDataJson = {
                val jsonPayload = viewModel.exportAllUserDataJson()
                ExportManager(context).shareJsonExport("sora_study_backup", jsonPayload)
                viewModel.showBanner(exportSuccessMsg)
            },
            onClearLocalCache = { viewModel.clearLocalCaches() },
            onClearApiKey = { viewModel.clearGeminiApiKey() },
            onSignOut = { signOut(credentialManager = credentialManager, scope = scope) },
            onDeleteAccountConfirmed = {
                viewModel.deleteAccountAndAllData {
                    signOut(credentialManager = credentialManager, scope = scope)
                }
            },
            onDismiss = { showAccountSecurity = false }
        )
    }
}
