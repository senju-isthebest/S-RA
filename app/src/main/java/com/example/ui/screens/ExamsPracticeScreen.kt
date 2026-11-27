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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.export.ExportManager
import com.example.data.exam.ExamEngine
import com.example.data.model.*
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning
import kotlinx.coroutines.delay
import org.json.JSONObject

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

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Exams (${exams.size})") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Practice (${activeQuestions.size})") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("Mistakes (${mistakeQuestions.size})") })
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
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listOf("ALL" to "All", "UNANSWERED" to "Unanswered", "WEAK" to "Weak Topics", "BOOKMARKED" to "Bookmarked")) { (k, l) ->
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
                            title = "Mistake Review Exam",
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
    var customTitle by remember { mutableStateOf("") }
    var questionCount by remember { mutableIntStateOf(10) }
    var durationMinutes by remember { mutableIntStateOf(20) }
    var difficultyFilter by remember { mutableStateOf("MIXED") } // MIXED, EASY, MEDIUM, HARD
    var allowRevisit by remember { mutableStateOf(true) }
    var showExplanationEach by remember { mutableStateOf(false) }

    val matchingQuestions = remember(questions, selectedBookIds, difficultyFilter) {
        questions.filter { q ->
            (selectedBookIds.isEmpty() || selectedBookIds.contains(q.bookId)) &&
                (difficultyFilter == "MIXED" || q.difficulty == difficultyFilter)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("exams_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Custom AI Exam Builder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Answers are locked during exams unless immediate feedback is enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Exam Title (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (books.isNotEmpty()) {
                        Text("Source Textbooks:", style = MaterialTheme.typography.labelMedium)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MIXED", "EASY", "MEDIUM", "HARD").forEach { d ->
                            FilterChip(
                                selected = difficultyFilter == d,
                                onClick = { difficultyFilter = d },
                                label = { Text(d) }
                            )
                        }
                    }
                    Text("Questions: $questionCount (available: ${matchingQuestions.size})", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = questionCount.toFloat(),
                        onValueChange = { questionCount = it.toInt() },
                        valueRange = 3f..30f
                    )
                    Text("Duration: $durationMinutes min", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = durationMinutes.toFloat(),
                        onValueChange = { durationMinutes = it.toInt() },
                        valueRange = 5f..90f
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow revisiting previous questions", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = allowRevisit, onCheckedChange = { allowRevisit = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Show explanation after each item", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showExplanationEach, onCheckedChange = { showExplanationEach = it })
                    }
                    Button(
                        onClick = {
                            val chosen = matchingQuestions.shuffled().take(questionCount)
                            viewModel.createAndStartExam(
                                title = customTitle.ifBlank { "Custom Study Exam" },
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
                        Text("Start Custom Exam")
                    }
                }
            }
        }

        item {
            SoraSectionHeader(title = "Smart Exam Presets", subtitle = "Tap any mode to launch immediately")
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
                        title = preset.label,
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
                        Text(preset.label, style = MaterialTheme.typography.titleSmall)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SoraStatusBadge("${subset.size} Q • ${preset.durationMinutes}m", SoraSoftBlue)
                }
            }
        }

        if (exams.isNotEmpty()) {
            item {
                SoraSectionHeader(title = "Exam History & Results")
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
                                    "${rec.score}/${rec.totalMarks} pts • ${rec.correctCount} correct • ${rec.incorrectCount} missed"
                                } else {
                                    "In progress • ${rec.questionIds.size} questions"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (rec.status == "SUBMITTED") {
                            SoraMasteryRing(progressPercent = rec.percentage.toInt(), size = 46.dp)
                        } else {
                            SoraStatusBadge("Resume", SoraWarning)
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
                    title = "Mistake Notebook Clear",
                    subtitle = "Any questions missed in Practice or Exams appear here automatically for targeted remediation."
                )
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onStartMistakeExam, modifier = Modifier.weight(1f)) {
                        Text("Mistake Exam")
                    }
                    OutlinedButton(onClick = onPracticeMistakes, modifier = Modifier.weight(1f)) {
                        Text("Practice Pool")
                    }
                }
            }
            items(mistakes, key = { it.id }) { q ->
                SoraSurfaceCard(accentBorder = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SoraStatusBadge("Missed ${q.incorrectCount}x", MaterialTheme.colorScheme.error)
                            Text("${q.bookTitle.take(20)} • Ch.${q.chapterNumber}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(q.questionText, style = MaterialTheme.typography.titleSmall)
                        Text("Answer: ${q.correctAnswer}", style = MaterialTheme.typography.bodySmall, color = SoraSuccess)
                        if (q.explanation.isNotBlank()) {
                            Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onMarkResolved(q) }) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Mark Understood")
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
    viewModel: SoraViewModel,
    onClose: () -> Unit
) {
    BackHandler(onBack = {
        viewModel.saveExamProgress(exam, answersMap, flaggedIds, currentIdx, elapsedSeconds)
        onClose()
    })

    val context = LocalContext.current
    val exportManager = remember { ExportManager(context) }

    val examQuestions = remember(exam.questionIds, allQuestions) {
        val byId = allQuestions.associateBy { it.id }
        exam.questionIds.mapNotNull { byId[it] }
    }

    if (exam.status == "SUBMITTED") {
        ExamResultsScreen(
            exam = exam,
            examQuestions = examQuestions,
            onExportPdf = {
                val body = buildString {
                    append("Score: ${exam.score}/${exam.totalMarks} (${exam.percentage.toInt()}%)\n")
                    append("Correct: ${exam.correctCount} | Incorrect: ${exam.incorrectCount} | Unanswered: ${exam.unansweredCount}\n\n")
                    append("Recommendation: ${exam.recommendedRevision}\n\n")
                    val ansObj = try { JSONObject(exam.answersJson) } catch (_: Exception) { JSONObject() }
                    examQuestions.forEachIndexed { idx, q ->
                        append("${idx + 1}. ${q.questionText}\n")
                        append("Your Answer: ${ansObj.optString(q.id, "(Blank)")}\n")
                        append("Correct Answer: ${q.correctAnswer}\n")
                        append("Explanation: ${q.explanation}\n\n")
                    }
                }
                exportManager.sharePdf("${exam.title} Results", "SŌRA Exam Report", body)
            },
            onPrint = {
                exportManager.printStudyMaterial(exam.title, "Score: ${exam.percentage.toInt()}%", exam.recommendedRevision)
            },
            onClose = onClose
        )
        return
    }

    val clockStartMillis = remember(exam.id, exam.startedAtMillis, exam.createdAt) {
        exam.startedAtMillis.takeIf { it > 0L }
            ?: exam.createdAt?.toDate()?.time
            ?: System.currentTimeMillis()
    }
    val clockDeadlineMillis = remember(exam.id, exam.deadlineAtMillis, exam.durationMinutes, clockStartMillis) {
        exam.deadlineAtMillis.takeIf { it > 0L }
            ?: clockStartMillis + exam.durationMinutes.coerceIn(1L, 300L) * 60_000L
    }
    var currentIdx by remember(exam.id) {
        mutableIntStateOf(exam.currentQuestionIndex.toInt().coerceAtLeast(0))
    }
    var answersMap by remember(exam.id) { mutableStateOf(parseExamAnswers(exam.answersJson)) }
    var flaggedIds by remember(exam.id) { mutableStateOf(exam.flaggedQuestionIds.toSet()) }
    var remainingSeconds by remember(exam.id, clockDeadlineMillis) {
        mutableIntStateOf(ExamEngine.remainingSeconds(clockDeadlineMillis, System.currentTimeMillis()))
    }
    var elapsedSeconds by remember(exam.id, clockStartMillis) {
        mutableIntStateOf(((System.currentTimeMillis() - clockStartMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(36000L).toInt())
    }
    var showConfirmSubmit by remember { mutableStateOf(false) }

    LaunchedEffect(exam.id, exam.deadlineAtMillis, exam.status) {
        if (exam.status == "SUBMITTED") return@LaunchedEffect
        viewModel.ensureExamClock(exam)
        while (remainingSeconds > 0) {
            delay(1000L)
            val now = System.currentTimeMillis()
            remainingSeconds = ExamEngine.remainingSeconds(clockDeadlineMillis, now)
            elapsedSeconds = ((now - clockStartMillis).coerceAtLeast(0L) / 1000L).coerceAtMost(36000L).toInt()
        }
        if (examQuestions.isNotEmpty()) {
            viewModel.submitExam(exam, examQuestions, answersMap, flaggedIds, elapsedSeconds)
        }
    }

    LaunchedEffect(exam.id, answersMap, flaggedIds, currentIdx) {
        if (exam.status == "SUBMITTED") return@LaunchedEffect
        delay(600L)
        viewModel.saveExamProgress(exam, answersMap, flaggedIds, currentIdx, elapsedSeconds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(exam.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val mins = remainingSeconds / 60
                        val secs = remainingSeconds % 60
                        Text(
                            "Time Left: %02d:%02d • ${answersMap.size}/${examQuestions.size} answered".format(mins, secs),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remainingSeconds < 60) MaterialTheme.colorScheme.error else SoraSoftBlue
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveExamProgress(exam, answersMap, flaggedIds, currentIdx, elapsedSeconds)
                        onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Exit exam")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showConfirmSubmit = true },
                        modifier = Modifier.testTag("exam_submit_button")
                    ) {
                        Text("Submit", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        if (examQuestions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                SoraEmptyState(Icons.Rounded.Warning, "Exam Questions Unavailable", "Return and generate a new exam.")
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
                                        SoraStatusBadge("Q${currentIdx + 1} • ${q.marks} pts", SoraViolet)
                                        SoraStatusBadge(q.difficulty, SoraSoftBlue)
                                    }
                                    IconButton(onClick = {
                                        flaggedIds = if (isFlagged) flaggedIds - q.id else flaggedIds + q.id
                                    }) {
                                        Icon(
                                            if (isFlagged) Icons.Rounded.Flag else Icons.Rounded.OutlinedFlag,
                                            contentDescription = "Flag question",
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
                                        label = { Text("Write your answer") },
                                        minLines = 3,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (exam.showExplanationEach && currentAns.isNotBlank()) {
                                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Immediate Check: ${q.correctAnswer}", style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
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
                        ) { Text("Previous") }
                    }
                    if (currentIdx < examQuestions.lastIndex) {
                        Button(
                            onClick = { currentIdx++ },
                            modifier = Modifier.weight(1f)
                        ) { Text("Next") }
                    } else {
                        Button(
                            onClick = { showConfirmSubmit = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Review & Submit") }
                    }
                }
            }
        }
    }

    if (showConfirmSubmit) {
        val unanswered = examQuestions.count { answersMap[it.id].isNullOrBlank() }
        AlertDialog(
            onDismissRequest = { showConfirmSubmit = false },
            title = { Text("Submit Exam?") },
            text = {
                Text("Answered: ${examQuestions.size - unanswered}/${examQuestions.size}\nFlagged: ${flaggedIds.size}\nUnanswered: $unanswered\n\nOnce submitted, answers are graded and locked.")
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmSubmit = false
                    viewModel.submitExam(exam, examQuestions, answersMap, flaggedIds, elapsedSeconds)
                }) { Text("Submit Now") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmSubmit = false }) { Text("Keep Working") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable

private fun parseExamAnswers(raw: String): MutableMap<String, String> {
    val json = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
    val result = mutableMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = json.optString(key, "")
    }
    return result
}

private fun ExamResultsScreen(
    exam: ExamRecord,
    examQuestions: List<QuestionItem>,
    onExportPdf: () -> Unit,
    onPrint: () -> Unit,
    onClose: () -> Unit
) {
    val ansJson = remember(exam.answersJson) {
        try { JSONObject(exam.answersJson) } catch (_: Exception) { JSONObject() }
    }
    val analyticsObj = remember(exam.analyticsJson) {
        try { JSONObject(exam.analyticsJson) } catch (_: Exception) { JSONObject() }
    }
    val chapterPairs = remember(analyticsObj) {
        val byCh = analyticsObj.optJSONObject("byChapter") ?: JSONObject()
        byCh.keys().asSequence().map { key ->
            val parts = byCh.optString(key, "0/1").split("/")
            val c = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val t = parts.getOrNull(1)?.toFloatOrNull()?.coerceAtLeast(1f) ?: 1f
            key to (c / t) * 100f
        }.toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exam Results & Analytics") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close results")
                    }
                },
                actions = {
                    IconButton(onClick = onExportPdf) { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export PDF") }
                    IconButton(onClick = onPrint) { Icon(Icons.Rounded.Print, contentDescription = "Print results") }
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
                            Text("Score: ${exam.score}/${exam.totalMarks} pts (${exam.percentage.toInt()}%)", style = MaterialTheme.typography.bodyMedium)
                            Text("Correct: ${exam.correctCount} • Missed: ${exam.incorrectCount} • Blank: ${exam.unansweredCount} • ${exam.timeSpentSeconds / 60}m ${exam.timeSpentSeconds % 60}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (exam.recommendedRevision.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Recommended Revision: ${exam.recommendedRevision}", style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                    }
                }
            }

            if (chapterPairs.isNotEmpty()) {
                item {
                    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Accuracy by Chapter", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        SoraBarChart(entries = chapterPairs)
                    }
                }
            }

            item { Text("Detailed Answer Review", style = MaterialTheme.typography.titleMedium) }

            itemsIndexed(examQuestions) { idx, q ->
                val userAns = ansJson.optString(q.id, "")
                val grade = ExamEngine.grade(q, userAns)
                val isCorrect = grade.isFullyCorrect
                SoraSurfaceCard(
                    accentBorder = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Q${idx + 1}. ${q.questionText}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Your Answer: ${userAns.ifBlank { "(Unanswered)" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error
                        )
                        Text("Correct Answer: ${q.correctAnswer}", style = MaterialTheme.typography.bodySmall, color = SoraSuccess)
                        Text(
                            "Marks awarded: ${grade.earnedMarks}/${grade.maximumMarks}" +
                                if (grade.isPartiallyCorrect) " • Partial credit" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (grade.isFullyCorrect) SoraSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (q.explanation.isNotBlank()) {
                            Text("Explanation (Ch.${q.chapterNumber}, p.${q.sourcePage}): ${q.explanation}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
