package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.export.ExportManager
import com.example.data.model.*
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

private val WORKSPACE_TABS = listOf(
    "Overview", "Contents", "Read", "Summaries", "Learn",
    "Question Bank", "Practice", "Exams", "Flashcards",
    "Notes", "Mind Maps", "Glossary", "Revision", "Analytics"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookWorkspaceScreen(
    book: Book,
    chapters: List<Chapter>,
    glossary: List<GlossaryEntry>,
    pages: List<BookPageContent>,
    allQuestions: List<QuestionItem>,
    allFlashcards: List<Flashcard>,
    allNotes: List<StudyNote>,
    tutorMessages: List<TutorMessage>,
    tutorStreamingText: String?,
    aiBusyLabel: String?,
    readingAssistantResult: String?,
    teachBackResult: com.example.data.ai.TeachBackFeedback?,
    comparisonOrGapResult: String?,
    viewModel: SoraViewModel,
    onLaunchExam: (ExamPreset, List<QuestionItem>) -> Unit,
    onLaunchFocusForBook: (Book, Chapter?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val exportManager = remember { ExportManager(context) }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedChapter by remember(chapters) { mutableStateOf(chapters.firstOrNull()) }
    var showTutorSheet by remember { mutableStateOf(false) }

    val bookQuestions = remember(allQuestions, book.id) { allQuestions.filter { it.bookId == book.id } }
    val bookFlashcards = remember(allFlashcards, book.id) { allFlashcards.filter { it.bookId == book.id } }
    var practiceSubset by remember(bookQuestions) { mutableStateOf(bookQuestions) }

    LaunchedEffect(chapters) {
        if (selectedChapter == null || chapters.none { it.id == selectedChapter?.id }) {
            selectedChapter = chapters.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text("${book.subjectName} • ${chapters.size} chapters • ${book.masteryScore}% mastery", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("File: ${book.syncStatus} • Pages: ${book.pagesSyncStatus}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Library")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.retryCloudSync(book) }, modifier = Modifier.testTag("cloud_sync_retry")) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = "Retry cloud sync")
                    }
                    IconButton(onClick = { onLaunchFocusForBook(book, selectedChapter) }) {
                        Icon(Icons.Rounded.Timer, contentDescription = "Start focus timer for book")
                    }
                    IconButton(onClick = { showTutorSheet = true }, modifier = Modifier.testTag("open_tutor_button")) {
                        Icon(Icons.Rounded.Psychology, contentDescription = "Open AI Tutor", tint = SoraViolet)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTutorSheet = true },
                icon = { Icon(Icons.Rounded.Psychology, contentDescription = null) },
                text = { Text("AI Tutor") }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (aiBusyLabel != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    aiBusyLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = SoraViolet,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 12.dp
            ) {
                WORKSPACE_TABS.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTabIndex == idx,
                        onClick = { selectedTabIndex = idx },
                        text = { Text(label) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (WORKSPACE_TABS[selectedTabIndex]) {
                    "Overview" -> BookOverviewPanel(
                        book = book,
                        chapters = chapters,
                        questionsCount = bookQuestions.size,
                        flashcardsCount = bookFlashcards.size,
                        onRetryFailed = { viewModel.retryFailedPages(book) },
                        onGenerateFullBook = { force -> viewModel.generateFullBookResources(book, force) },
                        onGoToTab = { tabName -> selectedTabIndex = WORKSPACE_TABS.indexOf(tabName).coerceAtLeast(0) },
                        onExportFullPack = {
                            val summaryBody = buildString {
                                append("## Overview\n${book.overview}\n\n")
                                if (book.formulaSheet.isNotBlank()) append("## Formula Sheet\n${book.formulaSheet}\n\n")
                                chapters.forEach { ch ->
                                    append("## Chapter ${ch.chapterNumber}: ${ch.title}\n")
                                    append("${ch.quickSummary}\n\n")
                                }
                            }
                            exportManager.sharePdf("${book.title} Study Pack", "${book.subjectName} • ${book.totalPages} pages", summaryBody)
                        }
                    )

                    "Contents" -> ContentsPanel(
                        book = book,
                        chapters = chapters,
                        selectedChapter = selectedChapter,
                        onSelectChapter = { ch -> selectedChapter = ch },
                        onGenerateChapter = { ch -> viewModel.generateChapterResources(book, ch) },
                        onOpenReaderAt = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = WORKSPACE_TABS.indexOf("Read")
                        }
                    )

                    "Read" -> TextbookReaderTab(
                        book = book,
                        pages = pages,
                        documentProcessor = viewModel.documentProcessor,
                        readingAssistantResult = readingAssistantResult,
                        onPageChange = { viewModel.updateBookReadingPage(book, it) },
                        onToggleBookmark = { viewModel.toggleBookPageBookmark(book, it) },
                        onAddHighlight = { p, txt -> viewModel.addBookHighlight(book, p, txt) },
                        onAddLinkedNote = { p, txt ->
                            viewModel.saveNote(null, "Page $p Highlight", txt, book.id, book.subjectName, selectedChapter?.chapterNumber ?: 1L, p.toLong(), listOf("highlight"), false)
                        },
                        onRunReadingAssistant = { act, txt, p -> viewModel.runReadingAssistant(act, txt, p, book.title) },
                        onClearAssistant = { viewModel.clearReadingAssistant() },
                        onOpenTutorWithImage = { showTutorSheet = true }
                    )

                    "Summaries" -> SummariesTab(
                        book = book,
                        chapter = selectedChapter,
                        onRegenerateChapter = { diff -> selectedChapter?.let { viewModel.generateChapterResources(book, it, diff) } },
                        onExportPdf = { t, body -> exportManager.sharePdf(t, book.subjectName, body) },
                        onPrint = { t, body -> exportManager.printStudyMaterial(t, book.subjectName, body) }
                    )

                    "Learn" -> LearnToolsTab(
                        book = book,
                        chapter = selectedChapter,
                        pages = pages,
                        teachBackResult = teachBackResult,
                        comparisonOrGapResult = comparisonOrGapResult,
                        onEvaluateTeachBack = { title, ref, student -> viewModel.evaluateTeachBack(title, ref, student) },
                        onClearTeachBack = { viewModel.clearTeachBack() },
                        onRunGapOrComparison = { mode -> selectedChapter?.let { viewModel.runChapterComparisonOrGapAnalysis(mode, book, it) } },
                        onClearComparison = { viewModel.clearComparisonResult() },
                        onUpdateGoal = { goal -> selectedChapter?.let { viewModel.setChapterGoal(book.id, it.id, goal) } },
                        onOpenTutor = { showTutorSheet = true }
                    )

                    "Question Bank" -> QuestionBankPanel(
                        questions = bookQuestions,
                        chapters = chapters,
                        onToggleBookmark = { viewModel.toggleQuestionBookmark(it) },
                        onToggleHidden = { viewModel.toggleQuestionHidden(it) },
                        onUpdateNote = { q, n -> viewModel.updateQuestionNote(q, n) },
                        onReport = { q, r -> viewModel.reportQuestion(q, r) },
                        onEdit = { q, t, a, e -> viewModel.editQuestion(q, t, a, e) },
                        onSetCollection = { q, c -> viewModel.setQuestionCollection(q, c) },
                        onGenerateMore = { ch, types, cnt, diff -> viewModel.generateMoreQuestions(book, ch, types, cnt, diff) },
                        onStartPracticeWith = { subset ->
                            practiceSubset = subset
                            selectedTabIndex = WORKSPACE_TABS.indexOf("Practice")
                        },
                        onCreateCustomExamFrom = { subset -> onLaunchExam(ExamPreset.CUSTOM, subset) },
                        onExportQuestionsPdf = { subset ->
                            val body = subset.mapIndexed { idx, q ->
                                "${idx + 1}. [${q.difficulty} • ${q.questionType}] ${q.questionText}\nAnswer: ${q.correctAnswer}\nExplanation: ${q.explanation}\n"
                            }.joinToString("\n")
                            exportManager.sharePdf("${book.title} Question Bank", "${subset.size} questions", body)
                        }
                    )

                    "Practice" -> PracticeSessionPanel(
                        questions = practiceSubset.ifEmpty { bookQuestions },
                        onRecordAttempt = { q, correct -> viewModel.recordQuestionAttempt(q, correct) }
                    )

                    "Exams" -> BookExamsTab(
                        book = book,
                        questions = bookQuestions,
                        selectedChapter = selectedChapter,
                        onLaunchPreset = { preset, qList -> onLaunchExam(preset, qList) }
                    )

                    "Flashcards" -> FlashcardsPanel(
                        bookId = book.id,
                        cards = bookFlashcards,
                        onRateCard = { c, r -> viewModel.rateFlashcard(c, r) },
                        onToggleBookmark = { viewModel.toggleFlashcardBookmark(it) },
                        onCreateCard = { deck, f, b, fmt ->
                            viewModel.createCustomFlashcard(book.id, selectedChapter?.chapterNumber ?: 1L, deck, f, b, fmt)
                        },
                        onExportDeckPdf = { cards ->
                            val body = cards.mapIndexed { i, c -> "${i + 1}. Q: ${c.front}\nA: ${c.back}\n" }.joinToString("\n")
                            exportManager.sharePdf("${book.title} Flashcards", "${cards.size} cards", body)
                        }
                    )

                    "Notes" -> NotesPanel(
                        book = book,
                        notes = allNotes,
                        onSaveNote = { id, t, c, ch, pg, tags, qk ->
                            viewModel.saveNote(id, t, c, book.id, book.subjectName, ch, pg, tags, qk)
                        },
                        onDeleteNote = { viewModel.deleteNote(it) },
                        onConvertToFlashcards = { viewModel.convertNoteToFlashcards(it) },
                        onExportNotePdf = { n -> exportManager.sharePdf(n.title, book.title, n.contentMarkdown) }
                    )

                    "Mind Maps" -> MindMapsTab(
                        chapter = selectedChapter,
                        onJumpToPage = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = WORKSPACE_TABS.indexOf("Read")
                        },
                        onGenerateQuestionsFromNode = { topic ->
                            selectedChapter?.let { ch ->
                                viewModel.generateMoreQuestions(book, ch, listOf("MCQ", "SHORT_ANSWER", "APPLICATION"), 4, "Focused on $topic")
                            }
                        }
                    )

                    "Glossary" -> GlossaryTab(
                        entries = glossary,
                        onJumpToPage = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = WORKSPACE_TABS.indexOf("Read")
                        }
                    )

                    "Revision" -> BookRevisionPanel(
                        book = book,
                        chapters = chapters,
                        questions = bookQuestions,
                        flashcards = bookFlashcards,
                        onGoToTab = { tabName -> selectedTabIndex = WORKSPACE_TABS.indexOf(tabName).coerceAtLeast(0) }
                    )

                    "Analytics" -> BookAnalyticsPanel(
                        book = book,
                        chapters = chapters,
                        questions = bookQuestions
                    )
                }
            }
        }
    }

    if (showTutorSheet) {
        val currentPage = pages.firstOrNull { it.pageNumber == book.currentPage.toInt() } ?: pages.firstOrNull()
        TutorBottomSheet(
            book = book,
            chapter = selectedChapter,
            currentPage = currentPage,
            messages = tutorMessages,
            streamingText = tutorStreamingText,
            onSendMessage = { mode, prompt, includeImg ->
                viewModel.sendTutorMessage(book, selectedChapter, currentPage, mode, prompt, includeImg)
            },
            onDismiss = { showTutorSheet = false }
        )
    }
}

@Composable
private fun BookOverviewPanel(
    book: Book,
    chapters: List<Chapter>,
    questionsCount: Int,
    flashcardsCount: Int,
    onRetryFailed: () -> Unit,
    onGenerateFullBook: (Boolean) -> Unit,
    onGoToTab: (String) -> Unit,
    onExportFullPack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SoraMasteryRing(progressPercent = book.masteryScore.toInt(), size = 64.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        Text("${book.subjectName} • ${book.sourceType} • ${book.processedPages}/${book.totalPages} pages extracted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Mastery is computed from your quiz & spaced-repetition accuracy (not passive reading time).", style = MaterialTheme.typography.labelSmall, color = SoraSoftBlue)
                    }
                }
                if (book.failedPages.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRetryFailed, colors = ButtonDefaults.buttonColors(containerColor = SoraWarning)) {
                        Text("Retry ${book.failedPages.size} Failed Page(s)")
                    }
                }
            }
        }

        if (chapters.isNotEmpty()) {
            item {
                val completeCount = chapters.count { it.intelligenceGenerated && it.questionsGenerated && it.flashcardsGenerated }
                val allComplete = completeCount == chapters.size
                SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                    Text("Full-book AI generation", style = MaterialTheme.typography.titleSmall)
                    Text(completeCount.toString() + " of " + chapters.size + " chapters have summaries, questions and flashcards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onGenerateFullBook(allComplete) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (allComplete) "Regenerate all chapters" else "Generate / Resume all chapters")
                    }
                }
            }
        }

        if (book.overview.isNotBlank()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Textbook Overview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(book.overview, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (book.formulaSheet.isNotBlank()) {
            item {
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Text("Consolidated Formula Sheet", style = MaterialTheme.typography.titleSmall, color = SoraSoftBlue)
                    Spacer(Modifier.height(6.dp))
                    SoraMarkdownText(book.formulaSheet)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onGoToTab("Read") }, modifier = Modifier.weight(1f)) { Text("Read (${book.currentPage}/${book.totalPages})") }
                OutlinedButton(onClick = { onGoToTab("Question Bank") }, modifier = Modifier.weight(1f)) { Text("Questions ($questionsCount)") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onGoToTab("Flashcards") }, modifier = Modifier.weight(1f)) { Text("Cards ($flashcardsCount)") }
                OutlinedButton(onClick = onExportFullPack, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Export Pack")
                }
            }
        }
    }
}

@Composable
private fun ContentsPanel(
    book: Book,
    chapters: List<Chapter>,
    selectedChapter: Chapter?,
    onSelectChapter: (Chapter) -> Unit,
    onGenerateChapter: (Chapter) -> Unit,
    onOpenReaderAt: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (chapters.isEmpty()) {
            item { SoraEmptyState(Icons.Rounded.Toc, "No Chapters Detected", "Re-analyze or paste structured chapters.") }
        } else {
            items(chapters, key = { it.id }) { ch ->
                val isSelected = ch.id == selectedChapter?.id
                SoraSurfaceCard(
                    onClick = { onSelectChapter(ch) },
                    accentBorder = if (isSelected) SoraViolet else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SoraMasteryRing(progressPercent = ch.masteryScore.toInt(), size = 48.dp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Chapter ${ch.chapterNumber}: ${ch.title}", style = MaterialTheme.typography.titleSmall)
                            val generatedCount = listOf(ch.intelligenceGenerated, ch.questionsGenerated, ch.flashcardsGenerated).count { it }
                            Text("AI resources: " + generatedCount + "/3 • " + ch.generationStatus, style = MaterialTheme.typography.labelSmall, color = if (generatedCount == 3) SoraSuccess else SoraWarning)
                            Text("Pages ${ch.startPage}-${ch.endPage} • Est. ${ch.estimatedStudyMinutes}m • ${ch.masteryStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { onOpenReaderAt(ch.startPage.toInt()) }) { Text("Read") }
                            TextButton(onClick = { onSelectChapter(ch); onGenerateChapter(ch) }) {
                                Text(if (generatedCount == 0) "Analyze" else "Resume")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookExamsTab(
    book: Book,
    questions: List<QuestionItem>,
    selectedChapter: Chapter?,
    onLaunchPreset: (ExamPreset, List<QuestionItem>) -> Unit
) {
    val active = remember(questions) { questions.filter { !it.hidden } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Generate Exam for ${book.title}", style = MaterialTheme.typography.titleMedium)
            Text("Answers remain locked until submission. Post-exam analytics identify weak chapters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(ExamPreset.entries) { preset ->
            val subset = remember(active, preset, selectedChapter) {
                when (preset) {
                    ExamPreset.CHAPTER_TEST -> selectedChapter?.let { ch -> active.filter { it.chapterNumber == ch.chapterNumber } }.orEmpty().ifEmpty { active }
                    ExamPreset.DIFFICULT_ONLY -> active.filter { it.difficulty == "HARD" }.ifEmpty { active }
                    ExamPreset.MISTAKE_REVIEW -> active.filter { it.incorrectCount > 0 && !it.mistakeResolved }.ifEmpty { active }
                    ExamPreset.UNSEEN_ONLY -> active.filter { it.attempts == 0L }.ifEmpty { active }
                    else -> active
                }.shuffled().take(preset.questionCount)
            }
            SoraSurfaceCard(
                onClick = { onLaunchPreset(preset, subset) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.label, style = MaterialTheme.typography.titleSmall)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoraStatusBadge("${subset.size} Q • ${preset.durationMinutes}m", SoraViolet)
                }
            }
        }
    }
}

@Composable
private fun BookRevisionPanel(
    book: Book,
    chapters: List<Chapter>,
    questions: List<QuestionItem>,
    flashcards: List<Flashcard>,
    onGoToTab: (String) -> Unit
) {
    val mistakes = remember(questions) { questions.filter { it.incorrectCount > 0 && !it.mistakeResolved } }
    val dueCards = remember(flashcards) { flashcards.filter { it.nextReviewAtMillis <= System.currentTimeMillis() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraWarning, modifier = Modifier.fillMaxWidth()) {
                Text("Book Revision Priority", style = MaterialTheme.typography.titleMedium)
                Text("1. ${mistakes.size} unresolved mistake(s)\n2. ${dueCards.size} due spaced-repetition flashcard(s)\n3. ${chapters.count { it.masteryScore < 60 }} chapter(s) below 60% mastery", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onGoToTab("Practice") }) { Text("Practice Mistakes") }
                    OutlinedButton(onClick = { onGoToTab("Flashcards") }) { Text("Review Cards") }
                }
            }
        }
    }
}

@Composable
private fun BookAnalyticsPanel(
    book: Book,
    chapters: List<Chapter>,
    questions: List<QuestionItem>
) {
    val attempted = questions.filter { it.attempts > 0L }
    val accuracy = if (attempted.isNotEmpty()) {
        (attempted.sumOf { it.correctCount }.toFloat() / attempted.sumOf { it.attempts }.toFloat().coerceAtLeast(1f)) * 100f
    } else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Text("Performance Summary — ${book.title}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Question Accuracy: ${accuracy.toInt()}% across ${attempted.size} attempted item(s)", style = MaterialTheme.typography.bodyMedium)
                Text("How Mastery is Calculated: 70% Question Accuracy + 30% Spaced-Repetition Flashcard Recall.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (chapters.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Mastery by Chapter", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    SoraBarChart(entries = chapters.map { "Ch.${it.chapterNumber}: ${it.title.take(18)}" to it.masteryScore.toFloat() })
                }
            }
        }
    }
}
