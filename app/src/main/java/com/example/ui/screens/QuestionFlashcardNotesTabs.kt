package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.*
import com.example.domain.ExamEngine
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun QuestionBankPanel(
    questions: List<QuestionItem>,
    chapters: List<Chapter>,
    onToggleBookmark: (QuestionItem) -> Unit,
    onToggleHidden: (QuestionItem) -> Unit,
    onUpdateNote: (QuestionItem, String) -> Unit,
    onReport: (QuestionItem, String) -> Unit,
    onEdit: (QuestionItem, String, String, String) -> Unit,
    onSetCollection: (QuestionItem, String) -> Unit,
    onGenerateMore: (chapter: Chapter, types: List<String>, count: Int, difficulty: String) -> Unit,
    onStartPracticeWith: (List<QuestionItem>) -> Unit,
    onCreateCustomExamFrom: (List<QuestionItem>) -> Unit,
    onExportQuestionsPdf: (List<QuestionItem>) -> Unit
) {
    var filterStatus by remember { mutableStateOf("ALL") } // ALL, UNANSWERED, INCORRECT, BOOKMARKED, WEAK
    var filterDifficulty by remember { mutableStateOf("ALL") }
    var filterChapter by remember { mutableStateOf<Long?>(null) }
    var editingQuestion by remember { mutableStateOf<QuestionItem?>(null) }
    var reportingQuestion by remember { mutableStateOf<QuestionItem?>(null) }
    var notingQuestion by remember { mutableStateOf<QuestionItem?>(null) }
    var showGenerateDialog by remember { mutableStateOf(false) }

    val filtered = remember(questions, filterStatus, filterDifficulty, filterChapter) {
        questions.filter { q ->
            !q.hidden &&
                (filterChapter == null || q.chapterNumber == filterChapter) &&
                (filterDifficulty == "ALL" || q.difficulty == filterDifficulty) &&
                when (filterStatus) {
                    "UNANSWERED" -> q.attempts == 0L
                    "INCORRECT" -> q.incorrectCount > 0L && !q.mistakeResolved
                    "BOOKMARKED" -> q.bookmarked
                    "WEAK" -> q.attempts > 0L && q.incorrectCount >= q.correctCount
                    else -> true
                }
        }
    }

    val statusFilters = listOf(
        "ALL" to stringResource(R.string.qb_filter_all),
        "UNANSWERED" to stringResource(R.string.qb_filter_unanswered),
        "INCORRECT" to stringResource(R.string.qb_filter_mistakes),
        "BOOKMARKED" to stringResource(R.string.qb_filter_bookmarked),
        "WEAK" to stringResource(R.string.qb_filter_weak)
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("question_bank_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.qb_count, filtered.size), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { onExportQuestionsPdf(filtered) }, enabled = filtered.isNotEmpty()) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.cd_export_questions))
                    }
                    OutlinedButton(onClick = { showGenerateDialog = true }, enabled = chapters.isNotEmpty()) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_generate))
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(statusFilters) { (key, label) ->
                    FilterChip(selected = filterStatus == key, onClick = { filterStatus = key }, label = { Text(label) })
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("ALL", "EASY", "MEDIUM", "HARD")) { diff ->
                    FilterChip(selected = filterDifficulty == diff, onClick = { filterDifficulty = diff }, label = { Text(localizedDifficulty(diff)) })
                }
                if (chapters.isNotEmpty()) {
                    item {
                        FilterChip(selected = filterChapter == null, onClick = { filterChapter = null }, label = { Text(stringResource(R.string.qb_all_chapters)) })
                    }
                    items(chapters, key = { it.id }) { ch ->
                        FilterChip(
                            selected = filterChapter == ch.chapterNumber,
                            onClick = { filterChapter = ch.chapterNumber },
                            label = { Text(stringResource(R.string.qb_chapter_short, ch.chapterNumber)) }
                        )
                    }
                }
            }
        }

        if (filtered.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onStartPracticeWith(filtered) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qb_practice_btn, filtered.size))
                    }
                    OutlinedButton(onClick = { onCreateCustomExamFrom(filtered) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qb_build_exam_btn))
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.Quiz,
                    stringResource(R.string.qb_empty_title),
                    stringResource(R.string.qb_empty_sub)
                )
            }
        } else {
            items(filtered, key = { it.id }) { q ->
                var revealAnswer by remember(q.id) { mutableStateOf(false) }
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                SoraStatusBadge(localizedQuestionTypeName(q.questionType), SoraSoftBlue)
                                SoraStatusBadge(
                                    localizedDifficulty(q.difficulty),
                                    when (q.difficulty) {
                                        "HARD" -> MaterialTheme.colorScheme.error
                                        "EASY" -> SoraSuccess
                                        else -> SoraWarning
                                    }
                                )
                                Text(stringResource(R.string.qb_ref_short, q.chapterNumber, q.sourcePage), style = MaterialTheme.typography.labelSmall)
                            }
                            Row {
                                IconButton(onClick = { onToggleBookmark(q) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (q.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        contentDescription = stringResource(R.string.cd_bookmark),
                                        tint = if (q.bookmarked) SoraWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { notingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.EditNote, contentDescription = stringResource(R.string.cd_personal_note))
                                }
                                IconButton(onClick = { editingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.cd_edit_question))
                                }
                                IconButton(onClick = { reportingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Flag, contentDescription = stringResource(R.string.cd_report_error))
                                }
                                IconButton(onClick = { onToggleHidden(q) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = stringResource(R.string.cd_hide_question))
                                }
                            }
                        }

                        Text(q.questionText, style = MaterialTheme.typography.titleSmall)
                        if (q.options.isNotEmpty()) {
                            q.options.forEachIndexed { idx, opt ->
                                Text("${('A' + idx)}. $opt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (q.personalNote.isNotBlank()) {
                            Text(stringResource(R.string.qb_note_prefix, q.personalNote), style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                        }
                        TextButton(onClick = { revealAnswer = !revealAnswer }) {
                            Text(if (revealAnswer) stringResource(R.string.qb_hide_answer) else stringResource(R.string.qb_show_answer))
                        }
                        if (revealAnswer) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.qb_answer_prefix, q.correctAnswer), style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
                                    if (q.explanation.isNotBlank()) {
                                        Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (q.aiGenerated) {
                                        Text(
                                            stringResource(R.string.qb_ai_badge, q.chapterNumber, q.sourcePage),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingQuestion?.let { q ->
        var txt by remember(q.id) { mutableStateOf(q.questionText) }
        var ans by remember(q.id) { mutableStateOf(q.correctAnswer) }
        var exp by remember(q.id) { mutableStateOf(q.explanation) }
        AlertDialog(
            onDismissRequest = { editingQuestion = null },
            title = { Text(stringResource(R.string.qb_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = txt, onValueChange = { txt = it }, label = { Text(stringResource(R.string.qb_question_label)) }, minLines = 2)
                    OutlinedTextField(value = ans, onValueChange = { ans = it }, label = { Text(stringResource(R.string.qb_correct_ans_label)) })
                    OutlinedTextField(value = exp, onValueChange = { exp = it }, label = { Text(stringResource(R.string.qb_explanation_label)) }, minLines = 2)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onEdit(q, txt, ans, exp)
                    editingQuestion = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editingQuestion = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    notingQuestion?.let { q ->
        var note by remember(q.id) { mutableStateOf(q.personalNote) }
        var col by remember(q.id) { mutableStateOf(q.collectionName) }
        AlertDialog(
            onDismissRequest = { notingQuestion = null },
            title = { Text(stringResource(R.string.qb_note_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.qb_note_label)) }, minLines = 3)
                    OutlinedTextField(value = col, onValueChange = { col = it }, label = { Text(stringResource(R.string.qb_collection_label)) }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateNote(q, note)
                    if (col != q.collectionName) onSetCollection(q, col)
                    notingQuestion = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { notingQuestion = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    reportingQuestion?.let { q ->
        var reason by remember(q.id) { mutableStateOf("") }
        val fallbackReason = stringResource(R.string.qb_report_title)
        AlertDialog(
            onDismissRequest = { reportingQuestion = null },
            title = { Text(stringResource(R.string.qb_report_title)) },
            text = {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(stringResource(R.string.qb_report_hint)) }, minLines = 2)
            },
            confirmButton = {
                Button(onClick = {
                    onReport(q, reason.ifBlank { fallbackReason })
                    reportingQuestion = null
                }) { Text(stringResource(R.string.qb_submit_report)) }
            },
            dismissButton = { TextButton(onClick = { reportingQuestion = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showGenerateDialog && chapters.isNotEmpty()) {
        var selectedCh by remember { mutableStateOf(chapters.first()) }
        var selectedTypes by remember { mutableStateOf(setOf("MCQ", "SHORT_ANSWER", "CALCULATION", "CASE_STUDY")) }
        var count by remember { mutableIntStateOf(6) }
        val diff = stringResource(R.string.diff_mixed)

        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text(stringResource(R.string.qb_generate_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.qb_chapter_label), style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(chapters) { ch ->
                            FilterChip(
                                selected = selectedCh.id == ch.id,
                                onClick = { selectedCh = ch },
                                label = { Text(stringResource(R.string.qb_chapter_short, ch.chapterNumber)) }
                            )
                        }
                    }
                    Text(stringResource(R.string.qb_types_label), style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(QuestionType.entries) { qt ->
                            val isOn = selectedTypes.contains(qt.name)
                            FilterChip(
                                selected = isOn,
                                onClick = {
                                    selectedTypes = if (isOn && selectedTypes.size > 1) selectedTypes - qt.name else selectedTypes + qt.name
                                },
                                label = { Text(qt.localizedLabel()) }
                            )
                        }
                    }
                    Text(stringResource(R.string.qb_count_label, count), style = MaterialTheme.typography.labelMedium)
                    Slider(value = count.toFloat(), onValueChange = { count = it.toInt() }, valueRange = 3f..15f)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showGenerateDialog = false
                    onGenerateMore(selectedCh, selectedTypes.toList(), count, diff)
                }) { Text(stringResource(R.string.action_generate)) }
            },
            dismissButton = { TextButton(onClick = { showGenerateDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
fun PracticeSessionPanel(
    questions: List<QuestionItem>,
    onRecordAttempt: (QuestionItem, Boolean) -> Unit
) {
    val activePool = remember(questions) { questions.filter { !it.hidden } }
    if (activePool.isEmpty()) {
        SoraEmptyState(
            Icons.Rounded.FitnessCenter,
            stringResource(R.string.practice_empty_title),
            stringResource(R.string.practice_empty_sub)
        )
        return
    }

    var adaptiveEnabled by remember { mutableStateOf(true) }
    var targetDifficulty by remember { mutableStateOf("MEDIUM") }
    var currentIndex by remember { mutableIntStateOf(0) }
    var selectedAnswer by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var isCorrect by remember { mutableStateOf(false) }
    var evalFeedback by remember { mutableStateOf("") }
    var streak by remember { mutableIntStateOf(0) }

    val orderedPool = remember(activePool, adaptiveEnabled, targetDifficulty) {
        if (!adaptiveEnabled) activePool
        else {
            val preferred = activePool.filter { it.difficulty == targetDifficulty }
            val rest = activePool.filter { it.difficulty != targetDifficulty }
            preferred + rest
        }
    }
    val q = orderedPool[currentIndex % orderedPool.size]

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.practice_progress, (currentIndex % orderedPool.size) + 1, orderedPool.size), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SoraStatusBadge(stringResource(R.string.practice_diff_badge, localizedDifficulty(q.difficulty)), SoraViolet)
                FilterChip(selected = adaptiveEnabled, onClick = { adaptiveEnabled = !adaptiveEnabled }, label = { Text(stringResource(R.string.practice_adaptive)) })
            }
        }

        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SoraStatusBadge(localizedQuestionTypeName(q.questionType), SoraSoftBlue)
                    Text(stringResource(R.string.qb_ref_short, q.chapterNumber, q.sourcePage), style = MaterialTheme.typography.labelSmall)
                }
                Text(q.questionText, style = MaterialTheme.typography.titleMedium)

                if (q.options.isNotEmpty()) {
                    q.options.forEach { option ->
                        val isSelected = selectedAnswer == option
                        OutlinedButton(
                            onClick = { if (!submitted) selectedAnswer = option },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(option, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = selectedAnswer,
                        onValueChange = { if (!submitted) selectedAnswer = it },
                        label = { Text(stringResource(R.string.practice_your_answer)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!submitted) {
                    Button(
                        onClick = {
                            val eval = ExamEngine.evaluateOffline(q, selectedAnswer)
                            val right = eval.isCorrect
                            isCorrect = right
                            evalFeedback = eval.feedback
                            submitted = true
                            onRecordAttempt(q, right)
                            if (right) {
                                streak++
                                if (adaptiveEnabled && streak >= 2) {
                                    targetDifficulty = if (targetDifficulty == "EASY") "MEDIUM" else "HARD"
                                }
                            } else {
                                streak = 0
                                if (adaptiveEnabled) {
                                    targetDifficulty = if (targetDifficulty == "HARD") "MEDIUM" else "EASY"
                                }
                            }
                        },
                        enabled = selectedAnswer.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("practice_submit_button")
                    ) {
                        Text(stringResource(R.string.practice_check_answer))
                    }
                } else {
                    Surface(
                        color = if (isCorrect) SoraSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (isCorrect) stringResource(R.string.practice_correct_pts, q.marks) else stringResource(R.string.practice_incorrect_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error
                            )
                            Text(stringResource(R.string.qb_correct_ans_prefix, q.correctAnswer), style = MaterialTheme.typography.bodySmall)
                            if (evalFeedback.isNotBlank()) {
                                Text(stringResource(R.string.exams_eval_feedback, evalFeedback), style = MaterialTheme.typography.labelSmall, color = SoraSoftBlue)
                            }
                            if (q.explanation.isNotBlank()) {
                                Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            currentIndex++
                            selectedAnswer = ""
                            evalFeedback = ""
                            submitted = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.practice_next_q))
                    }
                }
            }
        }
    }
}

@Composable
fun FlashcardsPanel(
    cards: List<Flashcard>,
    onRateCard: (Flashcard, String) -> Unit,
    onToggleBookmark: (Flashcard) -> Unit,
    onCreateCard: (deckName: String, front: String, back: String, format: String) -> Unit,
    onExportDeckPdf: (List<Flashcard>) -> Unit
) {
    var dueOnly by remember { mutableStateOf(true) }
    var shuffledOrder by remember { mutableStateOf(false) }
    var cardIndex by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val pool = remember(cards, dueOnly, shuffledOrder) {
        val base = if (dueOnly) cards.filter { it.nextReviewAtMillis <= now }.ifEmpty { cards } else cards
        if (shuffledOrder) base.shuffled() else base
    }

    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(320),
        label = "flashcard_flip"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = dueOnly,
                    onClick = { dueOnly = true; cardIndex = 0; flipped = false },
                    label = { Text(stringResource(R.string.fc_due_chip, cards.count { it.nextReviewAtMillis <= now })) }
                )
                FilterChip(
                    selected = !dueOnly,
                    onClick = { dueOnly = false; cardIndex = 0; flipped = false },
                    label = { Text(stringResource(R.string.fc_all_chip, cards.size)) }
                )
                IconButton(onClick = { shuffledOrder = !shuffledOrder; cardIndex = 0; flipped = false }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.cd_shuffle_deck),
                        tint = if (shuffledOrder) SoraViolet else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                IconButton(onClick = { onExportDeckPdf(cards) }, enabled = cards.isNotEmpty()) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.cd_export_cards))
                }
                FilledTonalButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.fc_new_card))
                }
            }
        }

        if (pool.isEmpty()) {
            SoraEmptyState(
                Icons.Rounded.Style,
                stringResource(R.string.fc_empty_title),
                stringResource(R.string.fc_empty_sub)
            )
        } else {
            val card = pool[cardIndex % pool.size]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fc_card_counter, (cardIndex % pool.size) + 1, pool.size, card.deckName), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { onToggleBookmark(card) }) {
                    Icon(
                        if (card.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_bookmark_card),
                        tint = if (card.bookmarked) SoraWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SoraSurfaceCard(
                onClick = { flipped = !flipped },
                accentBorder = if (flipped) SoraSuccess else SoraViolet,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 12f * density
                    }
                    .testTag("flashcard_flip_card")
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 180.dp)
                        .graphicsLayer { if (rotation > 90f) rotationY = 180f },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoraStatusBadge(
                            if (rotation <= 90f) stringResource(R.string.fc_front_badge, card.format)
                            else stringResource(R.string.fc_answer_badge, card.pageReference),
                            SoraSoftBlue
                        )
                        Text(
                            text = if (rotation <= 90f) card.front else card.back,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (rotation <= 90f && card.hint.isNotBlank()) {
                            Text(stringResource(R.string.fc_hint_prefix, card.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = stringResource(R.string.fc_tap_flip),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (flipped) {
                Text(stringResource(R.string.fc_rate_prompt), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onRateCard(card, "HARD"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.fc_hard), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onRateCard(card, "MEDIUM"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.fc_medium), color = SoraWarning) }
                    Button(
                        onClick = { onRateCard(card, "EASY"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.fc_easy)) }
                }
            } else {
                Button(onClick = { flipped = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.fc_reveal))
                }
            }
        }
    }

    if (showCreateDialog) {
        val defaultDeck = stringResource(R.string.label_custom_deck)
        var deck by remember { mutableStateOf(defaultDeck) }
        var front by remember { mutableStateOf("") }
        var back by remember { mutableStateOf("") }
        val fmt = "TERM_DEF"
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.fc_create_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = deck, onValueChange = { deck = it }, label = { Text(stringResource(R.string.fc_deck_label)) }, singleLine = true)
                    OutlinedTextField(value = front, onValueChange = { front = it }, label = { Text(stringResource(R.string.fc_front_label)) }, minLines = 2)
                    OutlinedTextField(value = back, onValueChange = { back = it }, label = { Text(stringResource(R.string.fc_back_label)) }, minLines = 3)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateCard(deck, front, back, fmt)
                    showCreateDialog = false
                }, enabled = front.isNotBlank() && back.isNotBlank()) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
fun NotesPanel(
    book: Book,
    notes: List<StudyNote>,
    onSaveNote: (id: String?, title: String, content: String, chapterNum: Long, page: Long, tags: List<String>, quick: Boolean) -> Unit,
    onDeleteNote: (String) -> Unit,
    onConvertToFlashcards: (StudyNote) -> Unit,
    onExportNotePdf: (StudyNote) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var editingNote by remember { mutableStateOf<StudyNote?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

    val bookNotes = remember(notes, book.id, search) {
        notes.filter {
            (it.bookId == book.id || it.bookId.isBlank()) &&
                (search.isBlank() || it.title.contains(search, true) || it.contentMarkdown.contains(search, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { creatingNew = true }, modifier = Modifier.testTag("new_note_button")) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.notes_new_btn))
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.notes_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (bookNotes.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.Description,
                    stringResource(R.string.notes_empty_title),
                    stringResource(R.string.notes_empty_sub)
                )
            }
        } else {
            items(bookNotes, key = { it.id }) { note ->
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(note.title, style = MaterialTheme.typography.titleMedium)
                                val baseMeta = stringResource(R.string.notes_meta_line, note.chapterNumber, note.linkedPage)
                                val tagSuffix = if (note.tags.isNotEmpty()) " • ${note.tags.joinToString()}" else ""
                                Text(
                                    "$baseMeta$tagSuffix",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(onClick = { onConvertToFlashcards(note) }) {
                                    Icon(Icons.Rounded.Style, contentDescription = stringResource(R.string.cd_convert_flashcards))
                                }
                                IconButton(onClick = { onExportNotePdf(note) }) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.cd_export_note))
                                }
                                IconButton(onClick = { editingNote = note }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.cd_edit_note))
                                }
                                IconButton(onClick = { onDeleteNote(note.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_delete_note), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        SoraMarkdownText(note.contentMarkdown)
                    }
                }
            }
        }
    }

    if (creatingNew || editingNote != null) {
        val target = editingNote
        var title by remember(target?.id) { mutableStateOf(target?.title ?: "") }
        var body by remember(target?.id) { mutableStateOf(target?.contentMarkdown ?: "") }
        var pageText by remember(target?.id) { mutableStateOf((target?.linkedPage ?: book.currentPage).toString()) }
        var tagsText by remember(target?.id) { mutableStateOf(target?.tags?.joinToString(", ") ?: "") }
        var quick by remember(target?.id) { mutableStateOf(target?.quickNote ?: false) }

        AlertDialog(
            onDismissRequest = { creatingNew = false; editingNote = null },
            title = { Text(if (target == null) stringResource(R.string.notes_new_title) else stringResource(R.string.notes_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.notes_title_label)) }, singleLine = true)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(stringResource(R.string.notes_body_label)) }, minLines = 5, maxLines = 10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = pageText, onValueChange = { pageText = it }, label = { Text(stringResource(R.string.notes_page_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = tagsText, onValueChange = { tagsText = it }, label = { Text(stringResource(R.string.notes_tags_label)) }, modifier = Modifier.weight(2f), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = quick, onCheckedChange = { quick = it })
                        Text(stringResource(R.string.notes_quick_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onSaveNote(
                        target?.id,
                        title,
                        body,
                        target?.chapterNumber ?: 1L,
                        pageText.toLongOrNull() ?: book.currentPage,
                        tags,
                        quick
                    )
                    creatingNew = false
                    editingNote = null
                }, enabled = title.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { creatingNew = false; editingNote = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
