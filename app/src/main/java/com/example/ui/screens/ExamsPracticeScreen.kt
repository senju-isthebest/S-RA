package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.SoraFormatters
import com.example.data.ai.SoraJson
import com.example.data.ai.JsonStr
import com.example.data.export.ExportManager
import com.example.data.model.*
import com.example.domain.AnswerEvaluation
import com.example.domain.ExamBlueprint
import com.example.domain.ExamEngine
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning
import kotlinx.coroutines.delay

@Composable
fun ExamsPracticeScreen(
    books: List<Book>,
    questions: List<QuestionItem>,
    exams: List<ExamRecord>,
    viewModel: SoraViewModel,
    onOpenExam: (String) -> Unit
) {
    var subTab by remember { mutableIntStateOf(0) } // 0=Exams, 1=Practice, 2=Mistake Notebook
    val activeQuestions = remember(questions) { questions.filter { !it.hidden } }
    val mistakeQuestions = remember(activeQuestions) {
        activeQuestions.filter { it.incorrectCount > 0L && !it.mistakeResolved }
    }
    val mistakeExamTitle = stringResource(R.string.preset_mistake_review)

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text(stringResource(R.string.exams_tab_exams, exams.size)) })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text(stringResource(R.string.exams_tab_practice, activeQuestions.size)) })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text(stringResource(R.string.exams_tab_mistakes, mistakeQuestions.size)) })
        }

        when (subTab) {
            0 -> ExamBuilderAndHistoryTab(
                books = books,
                questions = activeQuestions,
                exams = exams,
                viewModel = viewModel,
                onOpenExam = onOpenExam
            )
            1 -> {
                var practiceFilter by remember { mutableStateOf("ALL") }
                val pool = remember(activeQuestions, practiceFilter) {
                    when (practiceFilter) {
                        "UNANSWERED" -> activeQuestions.filter { it.attempts == 0L }
                        "BOOKMARKED" -> activeQuestions.filter { it.bookmarked }
                        "WEAK" -> activeQuestions.filter { it.attempts > 0L && it.incorrectCount >= it.correctCount }
                        else -> activeQuestions
                    }
                }
                val filterChips = listOf(
                    "ALL" to stringResource(R.string.qb_filter_all),
                    "UNANSWERED" to stringResource(R.string.qb_filter_unanswered),
                    "WEAK" to stringResource(R.string.preset_weak_topics),
                    "BOOKMARKED" to stringResource(R.string.qb_filter_bookmarked)
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filterChips) { (k, l) ->
                            FilterChip(selected = practiceFilter == k, onClick = { practiceFilter = k }, label = { Text(l) })
                        }
                    }
                    PracticeSessionPanel(
                        questions = pool,
                        onRecordAttempt = { q, right -> viewModel.recordQuestionAttempt(q, right) }
                    )
                }
            }
            2 -> MistakeNotebookTab(
                mistakes = mistakeQuestions,
                onMarkResolved = { viewModel.markMistakeResolved(it) },
                onPracticeMistakes = { subTab = 1 },
                onStartMistakeExam = {
                    if (mistakeQuestions.isNotEmpty()) {
                        viewModel.createAndStartExam(
                            title = mistakeExamTitle,
                            preset = ExamPreset.MISTAKE_REVIEW,
                            selectedQuestions = mistakeQuestions.take(15),
                            durationMinutes = 20,
                            allowRevisit = true,
                            showExplanationEach = false,
                            onCreated = onOpenExam
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ExamBuilderAndHistoryTab(
    books: List<Book>,
    questions: List<QuestionItem>,
    exams: List<ExamRecord>,
    viewModel: SoraViewModel,
    onOpenExam: (String) -> Unit
) {
    var selectedBookIds by remember(books) { mutableStateOf(books.map { it.id }.toSet()) }
    var selectedChapters by remember { mutableStateOf(emptySet<Long>()) }
    var selectedTypes by remember { mutableStateOf(emptySet<String>()) }
    var customTitle by remember { mutableStateOf("") }
    var questionCount by remember { mutableIntStateOf(10) }
    var durationMinutes by remember { mutableIntStateOf(20) }
    var difficultyFilter by remember { mutableStateOf("MIXED") }
    var allowRevisit by remember { mutableStateOf(true) }
    var showExplanationEach by remember { mutableStateOf(false) }

    val customDefaultTitle = stringResource(R.string.exams_custom_default_title)
    val presetLabels = ExamPreset.entries.associateWith { it.localizedLabel() }

    val availableChapters = remember(questions, selectedBookIds) {
        questions.filter { selectedBookIds.isEmpty() || selectedBookIds.contains(it.bookId) }
            .map { it.chapterNumber }.distinct().sorted()
    }
    val availableTypes = remember(questions, selectedBookIds) {
        questions.filter { selectedBookIds.isEmpty() || selectedBookIds.contains(it.bookId) }
            .map { it.questionType.uppercase() }.distinct().sorted()
    }

    val matchingQuestions = remember(questions, selectedBookIds, selectedChapters, selectedTypes, difficultyFilter) {
        ExamEngine.buildExamQuestions(
            allQuestions = questions,
            blueprint = ExamBlueprint(
                bookIds = selectedBookIds,
                chapterNumbers = selectedChapters,
                questionTypes = selectedTypes,
                difficulty = difficultyFilter,
                maxQuestions = questions.size.coerceAtLeast(1)
            ),
            randomize = false
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("exams_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.exams_builder_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.exams_builder_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text(stringResource(R.string.exams_title_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (books.isNotEmpty()) {
                        Text(stringResource(R.string.exams_source_books), style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(books, key = { it.id }) { b ->
                                val isOn = selectedBookIds.contains(b.id)
                                FilterChip(
                                    selected = isOn,
                                    onClick = {
                                        selectedBookIds = if (isOn) selectedBookIds - b.id else selectedBookIds + b.id
                                    },
                                    label = { Text(b.title.take(20)) }
                                )
                            }
                        }
                    }
                    if (availableChapters.isNotEmpty()) {
                        Text(stringResource(R.string.exams_chapter_filter), style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(availableChapters) { ch ->
                                val isOn = selectedChapters.contains(ch)
                                FilterChip(
                                    selected = isOn,
                                    onClick = {
                                        selectedChapters = if (isOn) selectedChapters - ch else selectedChapters + ch
                                    },
                                    label = { Text(stringResource(R.string.qb_chapter_short, ch)) }
                                )
                            }
                        }
                    }
                    if (availableTypes.isNotEmpty()) {
                        Text(stringResource(R.string.exams_type_filter), style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(availableTypes) { qType ->
                                val isOn = selectedTypes.contains(qType)
                                FilterChip(
                                    selected = isOn,
                                    onClick = {
                                        selectedTypes = if (isOn) selectedTypes - qType else selectedTypes + qType
                                    },
                                    label = { Text(localizedQuestionTypeName(qType)) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MIXED", "EASY", "MEDIUM", "HARD").forEach { d ->
                            FilterChip(
                                selected = difficultyFilter == d,
                                onClick = { difficultyFilter = d },
                                label = { Text(localizedDifficulty(d)) }
                            )
                        }
                    }
                    Text(stringResource(R.string.exams_q_count_avail, questionCount, matchingQuestions.size), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = questionCount.toFloat(),
                        onValueChange = { questionCount = it.toInt() },
                        valueRange = 3f..30f
                    )
                    Text(stringResource(R.string.exams_duration_min, durationMinutes), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = durationMinutes.toFloat(),
                        onValueChange = { durationMinutes = it.toInt() },
                        valueRange = 5f..90f
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.exams_allow_revisit), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = allowRevisit, onCheckedChange = { allowRevisit = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.exams_show_exp), style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showExplanationEach, onCheckedChange = { showExplanationEach = it })
                    }
                    Button(
                        onClick = {
                            val chosen = matchingQuestions.shuffled().take(questionCount)
                            viewModel.createAndStartExam(
                                title = customTitle.ifBlank { customDefaultTitle },
                                preset = ExamPreset.CUSTOM,
                                selectedQuestions = chosen,
                                durationMinutes = durationMinutes,
                                allowRevisit = allowRevisit,
                                showExplanationEach = showExplanationEach,
                                onCreated = onOpenExam
                            )
                        },
                        enabled = matchingQuestions.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("start_custom_exam_button")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.exams_start_custom))
                    }
                }
            }
        }

        item {
            SoraSectionHeader(
                title = stringResource(R.string.exams_presets_title),
                subtitle = stringResource(R.string.exams_presets_sub)
            )
        }

        items(ExamPreset.entries.filter { it != ExamPreset.CUSTOM }) { preset ->
            val subset = remember(questions, preset) {
                when (preset) {
                    ExamPreset.DIFFICULT_ONLY -> questions.filter { it.difficulty == "HARD" }.ifEmpty { questions }
                    ExamPreset.WEAK_TOPICS -> questions.filter { it.attempts > 0L && it.incorrectCount >= it.correctCount }.ifEmpty { questions }
                    ExamPreset.MISTAKE_REVIEW -> questions.filter { it.incorrectCount > 0L && !it.mistakeResolved }.ifEmpty { questions }
                    ExamPreset.UNSEEN_ONLY -> questions.filter { it.attempts == 0L }.ifEmpty { questions }
                    else -> questions
                }.shuffled().take(preset.questionCount)
            }
            SoraSurfaceCard(
                onClick = {
                    viewModel.createAndStartExam(
                        title = presetLabels[preset] ?: preset.label,
                        preset = preset,
                        selectedQuestions = subset,
                        durationMinutes = preset.durationMinutes,
                        allowRevisit = preset != ExamPreset.FINAL_SIMULATION,
                        showExplanationEach = false,
                        onCreated = onOpenExam
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(preset.localizedLabel(), style = MaterialTheme.typography.titleSmall)
                        Text(preset.localizedDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoraStatusBadge(stringResource(R.string.badge_q_min, subset.size, preset.durationMinutes), SoraSoftBlue)
                }
            }
        }

        if (exams.isNotEmpty()) {
            item {
                SoraSectionHeader(title = stringResource(R.string.exams_history_title))
            }
            items(exams, key = { it.id }) { rec ->
                SoraSurfaceCard(
                    onClick = { onOpenExam(rec.id) },
                    accentBorder = if (rec.status == "SUBMITTED") SoraSuccess else SoraWarning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (rec.status == "SUBMITTED") {
                                    stringResource(R.string.exams_score_line, rec.score, rec.totalMarks, rec.correctCount, rec.incorrectCount)
                                } else {
                                    stringResource(R.string.exams_in_progress_line, rec.questionIds.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (rec.status == "SUBMITTED") {
                            SoraMasteryRing(progressPercent = rec.percentage.toInt(), size = 46.dp)
                        } else {
                            SoraStatusBadge(stringResource(R.string.exams_resume), SoraWarning)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MistakeNotebookTab(
    mistakes: List<QuestionItem>,
    onMarkResolved: (QuestionItem) -> Unit,
    onPracticeMistakes: () -> Unit,
    onStartMistakeExam: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (mistakes.isEmpty()) {
            item {
                SoraEmptyState(
                    icon = Icons.Rounded.Verified,
                    title = stringResource(R.string.mistakes_empty_title),
                    subtitle = stringResource(R.string.mistakes_empty_sub)
                )
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onStartMistakeExam, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mistakes_exam_btn))
                    }
                    OutlinedButton(onClick = onPracticeMistakes, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mistakes_practice_btn))
                    }
                }
            }
            items(mistakes, key = { it.id }) { q ->
                SoraSurfaceCard(accentBorder = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SoraStatusBadge(stringResource(R.string.mistakes_missed_x, q.incorrectCount), MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.mistake_book_ref, q.bookTitle.take(20), q.chapterNumber), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(q.questionText, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.qb_answer_prefix, q.correctAnswer), style = MaterialTheme.typography.bodySmall, color = SoraSuccess)
                        if (q.explanation.isNotBlank()) {
                            Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onMarkResolved(q) }) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.mistakes_mark_understood))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamSessionScreen(
    exam: ExamRecord,
    allQuestions: List<QuestionItem>,
    evaluations: Map<String, AnswerEvaluation> = emptyMap(),
    onRetakeMissed: (List<QuestionItem>) -> Unit = {},
    viewModel: SoraViewModel,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val exportManager = remember { ExportManager(context) }

    val examQuestions = remember(exam.questionIds, allQuestions) {
        val byId = allQuestions.associateBy { it.id }
        exam.questionIds.mapNotNull { byId[it] }
    }

    if (exam.status == "SUBMITTED") {
        val reportSubtitle = stringResource(R.string.exam_results_title)
        val unansweredLabel = stringResource(R.string.exam_unanswered)
        val ansObj = remember(exam.answersJson) {
            try { SoraJson.parseObject(exam.answersJson.ifBlank { "{}" }) } catch (_: Exception) { null }
        }
        val exportScoreLine = stringResource(R.string.exam_score_summary, exam.score, exam.totalMarks, exam.percentage.toInt())
        val exportStatsLine = stringResource(R.string.exam_stats_summary, exam.correctCount, exam.incorrectCount, exam.unansweredCount, exam.timeSpentSeconds / 60, exam.timeSpentSeconds % 60)
        val exportRecLine = stringResource(R.string.exam_recommended_rev, exam.recommendedRevision)
        val exportYourAnsPrefix = stringResource(R.string.exam_your_answer, "")
        val exportCorrectAnsPrefix = stringResource(R.string.qb_correct_ans_prefix, "")
        val exportExpLabel = stringResource(R.string.qb_explanation_label)

        ExamResultsScreen(
            exam = exam,
            examQuestions = examQuestions,
            evaluations = evaluations,
            onRetakeMissed = onRetakeMissed,
            onExportPdf = {
                val body = buildString {
                    append("$exportScoreLine\n$exportStatsLine\n\n$exportRecLine\n\n")
                    examQuestions.forEachIndexed { idx, q ->
                        val ans = ansObj?.str(q.id)?.ifBlank { unansweredLabel } ?: unansweredLabel
                        append("${idx + 1}. ${q.questionText}\n")
                        append("$exportYourAnsPrefix$ans\n")
                        append("$exportCorrectAnsPrefix${q.correctAnswer}\n")
                        append("$exportExpLabel: ${q.explanation}\n\n")
                    }
                }
                exportManager.sharePdf(exam.title, reportSubtitle, body)
            },
            onPrint = {
                exportManager.printStudyMaterial(exam.title, exportScoreLine, exam.recommendedRevision)
            },
            onClose = onClose
        )
        return
    }

    var currentIdx by remember { mutableIntStateOf(0) }
    var answersMap by remember(exam.id) { mutableStateOf(mutableMapOf<String, String>()) }
    var flaggedIds by remember(exam.id) { mutableStateOf(exam.flaggedQuestionIds.toSet()) }
    var remainingSeconds by remember(exam.id) { mutableIntStateOf((exam.durationMinutes * 60L).toInt().coerceAtLeast(60)) }
    var elapsedSeconds by remember(exam.id) { mutableIntStateOf(0) }
    var showConfirmSubmit by remember { mutableStateOf(false) }

    LaunchedEffect(exam.id) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
            elapsedSeconds++
        }
        if (examQuestions.isNotEmpty()) {
            viewModel.submitExam(exam, examQuestions, answersMap, flaggedIds, elapsedSeconds)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(exam.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val mins = remainingSeconds / 60
                        val secs = remainingSeconds % 60
                        val clock = SoraFormatters.ltrIsolate("%02d:%02d".format(mins, secs))
                        Text(
                            stringResource(R.string.exam_time_left_str, clock, answersMap.size, examQuestions.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSeconds < 60) MaterialTheme.colorScheme.error else SoraSoftBlue
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_exit_exam))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showConfirmSubmit = true },
                        modifier = Modifier.testTag("exam_submit_button")
                    ) {
                        Text(stringResource(R.string.exam_submit_btn), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        if (examQuestions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                SoraEmptyState(
                    Icons.Rounded.Warning,
                    stringResource(R.string.exam_empty_title),
                    stringResource(R.string.exam_empty_sub)
                )
            }
        } else {
            val q = examQuestions[currentIdx.coerceIn(0, examQuestions.lastIndex)]
            val currentAns = answersMap[q.id].orEmpty()
            val isFlagged = flaggedIds.contains(q.id)

            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (exam.allowRevisit) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(examQuestions) { idx, item ->
                            val answered = !answersMap[item.id].isNullOrBlank()
                            val flagged = flaggedIds.contains(item.id)
                            FilterChip(
                                selected = idx == currentIdx,
                                onClick = { currentIdx = idx },
                                label = { Text("${idx + 1}${if (flagged) "*" else ""}") },
                                colors = if (answered) FilterChipDefaults.filterChipColors(containerColor = SoraSuccess.copy(alpha = 0.2f)) else FilterChipDefaults.filterChipColors()
                            )
                        }
                    }
                } else {
                    LinearProgressIndicator(
                        progress = { (currentIdx + 1).toFloat() / examQuestions.size.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SoraStatusBadge(stringResource(R.string.exam_q_badge, currentIdx + 1, q.marks), SoraViolet)
                                        SoraStatusBadge(localizedDifficulty(q.difficulty), SoraSoftBlue)
                                    }
                                    IconButton(onClick = {
                                        flaggedIds = if (isFlagged) flaggedIds - q.id else flaggedIds + q.id
                                    }) {
                                        Icon(
                                            if (isFlagged) Icons.Rounded.Flag else Icons.Rounded.OutlinedFlag,
                                            contentDescription = stringResource(R.string.cd_flag_question),
                                            tint = if (isFlagged) SoraWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(q.questionText, style = MaterialTheme.typography.titleMedium)

                                if (q.options.isNotEmpty()) {
                                    q.options.forEach { opt ->
                                        val selected = currentAns == opt
                                        OutlinedButton(
                                            onClick = {
                                                answersMap = answersMap.toMutableMap().apply { put(q.id, opt) }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                        ) {
                                            Text(opt, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = currentAns,
                                        onValueChange = { txt ->
                                            answersMap = answersMap.toMutableMap().apply { put(q.id, txt) }
                                        },
                                        label = { Text(stringResource(R.string.exam_write_answer)) },
                                        minLines = 3,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (exam.showExplanationEach && currentAns.isNotBlank()) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(stringResource(R.string.exam_immediate_check, q.correctAnswer), style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
                                            if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (exam.allowRevisit) {
                        OutlinedButton(
                            onClick = { if (currentIdx > 0) currentIdx-- },
                            enabled = currentIdx > 0,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.action_previous)) }
                    }
                    if (currentIdx < examQuestions.lastIndex) {
                        Button(
                            onClick = { currentIdx++ },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.action_next)) }
                    } else {
                        Button(
                            onClick = { showConfirmSubmit = true },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.exam_review_submit)) }
                    }
                }
            }
        }
    }

    if (showConfirmSubmit) {
        val unanswered = examQuestions.count { answersMap[it.id].isNullOrBlank() }
        AlertDialog(
            onDismissRequest = { showConfirmSubmit = false },
            title = { Text(stringResource(R.string.exam_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.exam_confirm_body,
                        examQuestions.size - unanswered,
                        examQuestions.size,
                        flaggedIds.size,
                        unanswered
                    )
                )
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmSubmit = false
                    viewModel.submitExam(exam, examQuestions, answersMap, flaggedIds, elapsedSeconds)
                }) { Text(stringResource(R.string.exam_submit_now)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmSubmit = false }) { Text(stringResource(R.string.exam_keep_working)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamResultsScreen(
    exam: ExamRecord,
    examQuestions: List<QuestionItem>,
    evaluations: Map<String, AnswerEvaluation> = emptyMap(),
    onRetakeMissed: (List<QuestionItem>) -> Unit = {},
    onExportPdf: () -> Unit,
    onPrint: () -> Unit,
    onClose: () -> Unit
) {
    val ansObj = remember(exam.answersJson) {
        try { SoraJson.parseObject(exam.answersJson.ifBlank { "{}" }) } catch (_: Exception) { null }
    }
    val analyticsObj = remember(exam.analyticsJson) {
        try { SoraJson.parseObject(exam.analyticsJson.ifBlank { "{}" }) } catch (_: Exception) { null }
    }
    val chapterPairs = remember(analyticsObj) {
        val byCh = analyticsObj?.obj("byChapter")
        byCh?.entries?.map { (key, v) ->
            val raw = (v as? JsonStr)?.value ?: "0/1"
            val parts = raw.split("/")
            val c = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val t = parts.getOrNull(1)?.toFloatOrNull()?.coerceAtLeast(1f) ?: 1f
            key to (c / t) * 100f
        } ?: emptyList()
    }

    var reviewFilter by remember { mutableStateOf("ALL") } // ALL, INCORRECT, FLAGGED
    val evalById = remember(examQuestions, ansObj, evaluations) {
        examQuestions.associate { q ->
            val userAns = ansObj?.str(q.id).orEmpty()
            val ev = evaluations[q.id] ?: ExamEngine.evaluateOffline(q, userAns)
            q.id to ev
        }
    }
    val missedQuestions = remember(examQuestions, evalById) {
        examQuestions.filter { evalById[it.id]?.isCorrect == false }
    }
    val flaggedQuestions = remember(examQuestions, exam.flaggedQuestionIds) {
        val flaggedSet = exam.flaggedQuestionIds.toSet()
        examQuestions.filter { flaggedSet.contains(it.id) }
    }
    val displayedQuestions = remember(examQuestions, missedQuestions, flaggedQuestions, reviewFilter) {
        when (reviewFilter) {
            "INCORRECT" -> missedQuestions
            "FLAGGED" -> flaggedQuestions
            else -> examQuestions
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exam_results_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_close_results))
                    }
                },
                actions = {
                    IconButton(onClick = onExportPdf) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.cd_export_pdf)) }
                    IconButton(onClick = onPrint) { Icon(Icons.Rounded.Print, contentDescription = stringResource(R.string.cd_print_results)) }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SoraSurfaceCard(accentBorder = SoraSuccess, modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SoraMasteryRing(progressPercent = exam.percentage.toInt(), size = 72.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(exam.title, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.exam_score_summary, exam.score, exam.totalMarks, exam.percentage.toInt()), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.exam_stats_summary,
                                    exam.correctCount,
                                    exam.incorrectCount,
                                    exam.unansweredCount,
                                    exam.timeSpentSeconds / 60,
                                    exam.timeSpentSeconds % 60
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (exam.recommendedRevision.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.exam_recommended_rev, exam.recommendedRevision), style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                    }
                    if (missedQuestions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onRetakeMissed(missedQuestions) },
                            modifier = Modifier.fillMaxWidth().testTag("retake_missed_button")
                        ) {
                            Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.exams_retake_missed, missedQuestions.size))
                        }
                    }
                }
            }

            if (chapterPairs.isNotEmpty()) {
                item {
                    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.exam_accuracy_ch), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        SoraBarChart(entries = chapterPairs)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.exam_detailed_review), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = reviewFilter == "ALL",
                            onClick = { reviewFilter = "ALL" },
                            label = { Text(stringResource(R.string.exams_filter_all, examQuestions.size)) }
                        )
                        FilterChip(
                            selected = reviewFilter == "INCORRECT",
                            onClick = { reviewFilter = "INCORRECT" },
                            label = { Text(stringResource(R.string.exams_filter_incorrect, missedQuestions.size)) }
                        )
                        FilterChip(
                            selected = reviewFilter == "FLAGGED",
                            onClick = { reviewFilter = "FLAGGED" },
                            label = { Text(stringResource(R.string.exams_filter_flagged, flaggedQuestions.size)) }
                        )
                    }
                }
            }

            itemsIndexed(displayedQuestions, key = { _, q -> q.id }) { idx, q ->
                val userAns = ansObj?.str(q.id).orEmpty()
                val eval = evalById[q.id]
                val isCorrect = eval?.isCorrect == true
                SoraSurfaceCard(
                    accentBorder = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.exam_question_numbered, idx + 1, q.questionText),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (eval?.gradedByAi == true) {
                                SoraStatusBadge(stringResource(R.string.exams_ai_grading_badge), SoraViolet)
                            }
                        }
                        Text(
                            stringResource(R.string.exam_your_answer, userAns.ifBlank { stringResource(R.string.exam_unanswered) }),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error
                        )
                        Text(stringResource(R.string.qb_correct_ans_prefix, q.correctAnswer), style = MaterialTheme.typography.bodySmall, color = SoraSuccess)
                        if (!eval?.feedback.isNullOrBlank()) {
                            Text(
                                stringResource(R.string.exams_eval_feedback, eval?.feedback.orEmpty()),
                                style = MaterialTheme.typography.labelSmall,
                                color = SoraSoftBlue
                            )
                        }
                        if (q.explanation.isNotBlank()) {
                            Text(
                                stringResource(R.string.exam_explanation_ref, q.chapterNumber, q.sourcePage, q.explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
