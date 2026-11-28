package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ai.FullBookAnalysisProgress
import com.example.data.ai.RetrievedPassage
import com.example.data.export.ExportManager
import com.example.data.model.*
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

private const val TAB_IDX_OVERVIEW = 0
private const val TAB_IDX_CONTENTS = 1
private const val TAB_IDX_READ = 2
private const val TAB_IDX_SUMMARIES = 3
private const val TAB_IDX_LEARN = 4
private const val TAB_IDX_QUESTION_BANK = 5
private const val TAB_IDX_PRACTICE = 6
private const val TAB_IDX_EXAMS = 7
private const val TAB_IDX_FLASHCARDS = 8
private const val TAB_IDX_NOTES = 9
private const val TAB_IDX_MIND_MAPS = 10
private const val TAB_IDX_GLOSSARY = 11
private const val TAB_IDX_REVISION = 12
private const val TAB_IDX_ANALYTICS = 13

@StringRes
private val WORKSPACE_TAB_RES = listOf(
    R.string.tab_overview,
    R.string.tab_contents,
    R.string.tab_read,
    R.string.tab_summaries,
    R.string.tab_learn,
    R.string.tab_question_bank,
    R.string.tab_practice,
    R.string.tab_exams,
    R.string.tab_flashcards,
    R.string.tab_notes,
    R.string.tab_mind_maps,
    R.string.tab_glossary,
    R.string.tab_revision,
    R.string.tab_analytics
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
    fullBookProgress: FullBookAnalysisProgress = FullBookAnalysisProgress(),
    retrievedPassages: List<RetrievedPassage> = emptyList(),
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
                        Text(
                            stringResource(
                                R.string.workspace_subtitle,
                                localizedSubjectName(book.subjectName),
                                chapters.size,
                                book.masteryScore
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back_library))
                    }
                },
                actions = {
                    IconButton(onClick = { onLaunchFocusForBook(book, selectedChapter) }) {
                        Icon(Icons.Rounded.Timer, contentDescription = stringResource(R.string.cd_focus_book))
                    }
                    IconButton(onClick = { showTutorSheet = true }, modifier = Modifier.testTag("open_tutor_button")) {
                        Icon(Icons.Rounded.Psychology, contentDescription = stringResource(R.string.cd_open_tutor), tint = SoraViolet)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showTutorSheet = true },
                icon = { Icon(Icons.Rounded.Psychology, contentDescription = null) },
                text = { Text(stringResource(R.string.fab_ai_tutor)) }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (aiBusyLabel != null || fullBookProgress.active) {
                if (fullBookProgress.active && fullBookProgress.totalChapters > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (fullBookProgress.currentChapterIndex.toFloat() / fullBookProgress.totalChapters.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    aiBusyLabel ?: stringResource(
                        R.string.fullbook_progress,
                        fullBookProgress.currentChapterIndex,
                        fullBookProgress.totalChapters,
                        fullBookProgress.currentChapterTitle
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = SoraViolet,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 12.dp
            ) {
                WORKSPACE_TAB_RES.forEachIndexed { idx, resId ->
                    Tab(
                        selected = selectedTabIndex == idx,
                        onClick = { selectedTabIndex = idx },
                        text = { Text(stringResource(resId)) }
                    )
                }
            }

            val overviewLabel = stringResource(R.string.tab_overview)
            val formulaSheetLabel = stringResource(R.string.overview_formula_sheet)
            val answerLabel = stringResource(R.string.qb_correct_ans_prefix, "")
            val explanationLabel = stringResource(R.string.qb_explanation_label)
            val focusTopicHint = stringResource(R.string.diff_standard)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTabIndex) {
                    TAB_IDX_OVERVIEW -> BookOverviewPanel(
                        book = book,
                        questionsCount = bookQuestions.size,
                        flashcardsCount = bookFlashcards.size,
                        analyzeAllEnabled = chapters.isNotEmpty() && !fullBookProgress.active,
                        onAnalyzeAllChapters = { viewModel.analyzeFullBookAllChapters(book, chapters) },
                        onRetryFailed = { viewModel.retryFailedPages(book) },
                        onGoToTab = { tabIdx -> selectedTabIndex = tabIdx.coerceIn(0, WORKSPACE_TAB_RES.lastIndex) },
                        onExportFullPack = {
                            val summaryBody = buildString {
                                append("## $overviewLabel\n${book.overview}\n\n")
                                if (book.formulaSheet.isNotBlank()) append("## $formulaSheetLabel\n${book.formulaSheet}\n\n")
                                chapters.forEach { ch ->
                                    append("## ${ch.chapterNumber}: ${ch.title}\n")
                                    append("${ch.quickSummary}\n\n")
                                }
                            }
                            exportManager.sharePdf(book.title, "${book.subjectName} • ${book.totalPages}", summaryBody)
                        }
                    )

                    TAB_IDX_CONTENTS -> ContentsPanel(
                        chapters = chapters,
                        selectedChapter = selectedChapter,
                        onSelectChapter = { ch -> selectedChapter = ch },
                        onGenerateChapter = { ch -> viewModel.generateChapterResources(book, ch) },
                        onOpenReaderAt = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = TAB_IDX_READ
                        }
                    )

                    TAB_IDX_READ -> TextbookReaderTab(
                        book = book,
                        pages = pages,
                        documentProcessor = viewModel.documentProcessor,
                        readingAssistantResult = readingAssistantResult,
                        onPageChange = { viewModel.updateBookReadingPage(book, it) },
                        onToggleBookmark = { viewModel.toggleBookPageBookmark(book, it) },
                        onAddHighlight = { p, txt -> viewModel.addBookHighlight(book, p, txt) },
                        onAddLinkedNote = { p, txt ->
                            viewModel.saveNote(null, "p.$p", txt, book.id, book.subjectName, selectedChapter?.chapterNumber ?: 1L, p.toLong(), listOf("highlight"), false)
                        },
                        onRunReadingAssistant = { act, txt, p -> viewModel.runReadingAssistant(act, txt, p, book.title) },
                        onClearAssistant = { viewModel.clearReadingAssistant() },
                        onOpenTutorWithImage = { showTutorSheet = true }
                    )

                    TAB_IDX_SUMMARIES -> SummariesTab(
                        book = book,
                        chapter = selectedChapter,
                        onRegenerateChapter = { diff -> selectedChapter?.let { viewModel.generateChapterResources(book, it, diff) } },
                        onExportPdf = { t, body -> exportManager.sharePdf(t, book.subjectName, body) },
                        onPrint = { t, body -> exportManager.printStudyMaterial(t, book.subjectName, body) }
                    )

                    TAB_IDX_LEARN -> LearnToolsTab(
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

                    TAB_IDX_QUESTION_BANK -> QuestionBankPanel(
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
                            selectedTabIndex = TAB_IDX_PRACTICE
                        },
                        onCreateCustomExamFrom = { subset -> onLaunchExam(ExamPreset.CUSTOM, subset) },
                        onExportQuestionsPdf = { subset ->
                            val body = subset.mapIndexed { idx, q ->
                                "${idx + 1}. [${q.difficulty} • ${q.questionType}] ${q.questionText}\n$answerLabel${q.correctAnswer}\n$explanationLabel: ${q.explanation}\n"
                            }.joinToString("\n")
                            exportManager.sharePdf(book.title, "${subset.size}", body)
                        }
                    )

                    TAB_IDX_PRACTICE -> PracticeSessionPanel(
                        questions = practiceSubset.ifEmpty { bookQuestions },
                        onRecordAttempt = { q, correct -> viewModel.recordQuestionAttempt(q, correct) }
                    )

                    TAB_IDX_EXAMS -> BookExamsTab(
                        book = book,
                        questions = bookQuestions,
                        selectedChapter = selectedChapter,
                        onLaunchPreset = { preset, qList -> onLaunchExam(preset, qList) }
                    )

                    TAB_IDX_FLASHCARDS -> FlashcardsPanel(
                        cards = bookFlashcards,
                        onRateCard = { c, r -> viewModel.rateFlashcard(c, r) },
                        onToggleBookmark = { viewModel.toggleFlashcardBookmark(it) },
                        onCreateCard = { deck, f, b, fmt ->
                            viewModel.createCustomFlashcard(book.id, selectedChapter?.chapterNumber ?: 1L, deck, f, b, fmt)
                        },
                        onExportDeckPdf = { cards ->
                            val body = cards.mapIndexed { i, c -> "${i + 1}. ${c.front}\n-> ${c.back}\n" }.joinToString("\n")
                            exportManager.sharePdf(book.title, "${cards.size}", body)
                        }
                    )

                    TAB_IDX_NOTES -> NotesPanel(
                        book = book,
                        notes = allNotes,
                        onSaveNote = { id, t, c, ch, pg, tags, qk ->
                            viewModel.saveNote(id, t, c, book.id, book.subjectName, ch, pg, tags, qk)
                        },
                        onDeleteNote = { viewModel.deleteNote(it) },
                        onConvertToFlashcards = { viewModel.convertNoteToFlashcards(it) },
                        onExportNotePdf = { n -> exportManager.sharePdf(n.title, book.title, n.contentMarkdown) }
                    )

                    TAB_IDX_MIND_MAPS -> MindMapsTab(
                        chapter = selectedChapter,
                        onJumpToPage = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = TAB_IDX_READ
                        },
                        onGenerateQuestionsFromNode = { topic ->
                            selectedChapter?.let { ch ->
                                viewModel.generateMoreQuestions(book, ch, listOf("MCQ", "SHORT_ANSWER", "APPLICATION"), 4, "$focusTopicHint ($topic)")
                            }
                        }
                    )

                    TAB_IDX_GLOSSARY -> GlossaryTab(
                        entries = glossary,
                        onJumpToPage = { page ->
                            viewModel.updateBookReadingPage(book, page)
                            selectedTabIndex = TAB_IDX_READ
                        }
                    )

                    TAB_IDX_REVISION -> BookRevisionPanel(
                        chapters = chapters,
                        questions = bookQuestions,
                        flashcards = bookFlashcards,
                        onGoToTab = { tabIdx -> selectedTabIndex = tabIdx.coerceIn(0, WORKSPACE_TAB_RES.lastIndex) }
                    )

                    TAB_IDX_ANALYTICS -> BookAnalyticsPanel(
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
            retrievedPassages = retrievedPassages,
            onSendMessage = { mode, prompt, includeImg, fullBookSearch ->
                viewModel.sendTutorMessage(book, selectedChapter, currentPage, mode, prompt, includeImg, fullBookSearch)
            },
            onJumpToPassagePage = { p ->
                viewModel.updateBookReadingPage(book, p)
                selectedTabIndex = TAB_IDX_READ
            },
            onDismiss = { showTutorSheet = false }
        )
    }
}

@Composable
private fun BookOverviewPanel(
    book: Book,
    questionsCount: Int,
    flashcardsCount: Int,
    analyzeAllEnabled: Boolean,
    onAnalyzeAllChapters: () -> Unit,
    onRetryFailed: () -> Unit,
    onGoToTab: (Int) -> Unit,
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
                        Text(
                            stringResource(
                                R.string.overview_extracted,
                                localizedSubjectName(book.subjectName),
                                book.sourceType,
                                book.processedPages,
                                book.totalPages
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.overview_mastery_note), style = MaterialTheme.typography.labelSmall, color = SoraSoftBlue)
                    }
                }
                if (book.failedPages.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onRetryFailed, colors = ButtonDefaults.buttonColors(containerColor = SoraWarning)) {
                        Text(stringResource(R.string.overview_retry_failed, book.failedPages.size))
                    }
                }
            }
        }

        if (book.overview.isNotBlank()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.overview_book_summary), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(book.overview, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (book.formulaSheet.isNotBlank()) {
            item {
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.overview_formula_sheet), style = MaterialTheme.typography.titleSmall, color = SoraSoftBlue)
                    Spacer(Modifier.height(6.dp))
                    SoraMarkdownText(book.formulaSheet)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onGoToTab(TAB_IDX_READ) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.overview_read_btn, book.currentPage, book.totalPages))
                }
                OutlinedButton(onClick = { onGoToTab(TAB_IDX_QUESTION_BANK) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.overview_questions_btn, questionsCount))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onGoToTab(TAB_IDX_FLASHCARDS) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.overview_cards_btn, flashcardsCount))
                }
                OutlinedButton(onClick = onExportFullPack, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.overview_export_pack))
                }
            }
        }
        item {
            FilledTonalButton(
                onClick = onAnalyzeAllChapters,
                enabled = analyzeAllEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("analyze_full_book_button")
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fullbook_analyze_btn))
            }
        }
    }
}

@Composable
private fun ContentsPanel(
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
            item {
                SoraEmptyState(
                    Icons.Rounded.Toc,
                    stringResource(R.string.contents_empty_title),
                    stringResource(R.string.contents_empty_sub)
                )
            }
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
                            Text(
                                stringResource(R.string.contents_chapter_title, ch.chapterNumber, ch.title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(
                                    R.string.contents_chapter_meta,
                                    ch.startPage,
                                    ch.endPage,
                                    ch.estimatedStudyMinutes,
                                    localizedMasteryStatus(ch.masteryStatus)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { onOpenReaderAt(ch.startPage.toInt()) }) {
                                Text(stringResource(R.string.contents_read))
                            }
                            TextButton(onClick = { onSelectChapter(ch); onGenerateChapter(ch) }) {
                                Text(if (ch.quickSummary.isBlank()) stringResource(R.string.action_analyze) else stringResource(R.string.action_refresh))
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
            Text(stringResource(R.string.book_exams_title, book.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.book_exams_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text(preset.localizedLabel(), style = MaterialTheme.typography.titleSmall)
                        Text(preset.localizedDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoraStatusBadge(stringResource(R.string.badge_q_min, subset.size, preset.durationMinutes), SoraViolet)
                }
            }
        }
    }
}

@Composable
private fun BookRevisionPanel(
    chapters: List<Chapter>,
    questions: List<QuestionItem>,
    flashcards: List<Flashcard>,
    onGoToTab: (Int) -> Unit
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
                Text(stringResource(R.string.book_revision_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.book_revision_summary,
                        mistakes.size,
                        dueCards.size,
                        chapters.count { it.masteryScore < 60 }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onGoToTab(TAB_IDX_PRACTICE) }) { Text(stringResource(R.string.book_revision_mistakes_btn)) }
                    OutlinedButton(onClick = { onGoToTab(TAB_IDX_FLASHCARDS) }) { Text(stringResource(R.string.book_revision_cards_btn)) }
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
                Text(stringResource(R.string.book_analytics_title, book.title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.book_analytics_acc, accuracy.toInt(), attempted.size), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.book_analytics_formula), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (chapters.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.book_analytics_by_ch), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val entries = chapters.map {
                        stringResource(R.string.book_analytics_ch_prefix, it.chapterNumber, it.title.take(18)) to it.masteryScore.toFloat()
                    }
                    SoraBarChart(entries = entries)
                }
            }
        }
    }
}
