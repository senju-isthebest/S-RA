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
import androidx.compose.ui.unit.dp
import com.example.data.model.*
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
                Text("${filtered.size} Question(s)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { onExportQuestionsPdf(filtered) }, enabled = filtered.isNotEmpty()) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export questions PDF")
                    }
                    OutlinedButton(onClick = { showGenerateDialog = true }, enabled = chapters.isNotEmpty()) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Generate")
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("ALL" to "All", "UNANSWERED" to "Unanswered", "INCORRECT" to "Mistakes", "BOOKMARKED" to "Bookmarked", "WEAK" to "Weak")) { (key, label) ->
                    FilterChip(selected = filterStatus == key, onClick = { filterStatus = key }, label = { Text(label) })
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("ALL", "EASY", "MEDIUM", "HARD")) { diff ->
                    FilterChip(selected = filterDifficulty == diff, onClick = { filterDifficulty = diff }, label = { Text(diff) })
                }
                if (chapters.isNotEmpty()) {
                    item {
                        FilterChip(selected = filterChapter == null, onClick = { filterChapter = null }, label = { Text("All Ch.") })
                    }
                    items(chapters, key = { it.id }) { ch ->
                        FilterChip(
                            selected = filterChapter == ch.chapterNumber,
                            onClick = { filterChapter = ch.chapterNumber },
                            label = { Text("Ch.${ch.chapterNumber}") }
                        )
                    }
                }
            }
        }

        if (filtered.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onStartPracticeWith(filtered) }, modifier = Modifier.weight(1f)) {
                        Text("Practice (${filtered.size})")
                    }
                    OutlinedButton(onClick = { onCreateCustomExamFrom(filtered) }, modifier = Modifier.weight(1f)) {
                        Text("Build Exam")
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.Quiz,
                    "No Matching Questions",
                    "Tap Generate to create AI-crafted questions grounded in your textbook chapters."
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SoraStatusBadge(q.questionType, SoraSoftBlue)
                                SoraStatusBadge(
                                    q.difficulty,
                                    when (q.difficulty) {
                                        "HARD" -> MaterialTheme.colorScheme.error
                                        "EASY" -> SoraSuccess
                                        else -> SoraWarning
                                    }
                                )
                                Text("Ch.${q.chapterNumber} • p.${q.sourcePage}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row {
                                IconButton(onClick = { onToggleBookmark(q) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (q.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                        contentDescription = "Bookmark",
                                        tint = if (q.bookmarked) SoraWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { notingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.EditNote, contentDescription = "Personal note")
                                }
                                IconButton(onClick = { editingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit question")
                                }
                                IconButton(onClick = { reportingQuestion = q }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Flag, contentDescription = "Report error")
                                }
                                IconButton(onClick = { onToggleHidden(q) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = "Hide question")
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
                            Text("Note: ${q.personalNote}", style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                        }
                        TextButton(onClick = { revealAnswer = !revealAnswer }) {
                            Text(if (revealAnswer) "Hide Answer & Explanation" else "Show Answer & Explanation")
                        }
                        if (revealAnswer) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Answer: ${q.correctAnswer}", style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
                                    if (q.explanation.isNotBlank()) {
                                        Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (q.aiGenerated) {
                                        Text("AI-generated • Linked to Ch.${q.chapterNumber}, p.${q.sourcePage}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text("Edit Question") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = txt, onValueChange = { txt = it }, label = { Text("Question") }, minLines = 2)
                    OutlinedTextField(value = ans, onValueChange = { ans = it }, label = { Text("Correct Answer") })
                    OutlinedTextField(value = exp, onValueChange = { exp = it }, label = { Text("Explanation") }, minLines = 2)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onEdit(q, txt, ans, exp)
                    editingQuestion = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingQuestion = null }) { Text("Cancel") } }
        )
    }

    notingQuestion?.let { q ->
        var note by remember(q.id) { mutableStateOf(q.personalNote) }
        var col by remember(q.id) { mutableStateOf(q.collectionName) }
        AlertDialog(
            onDismissRequest = { notingQuestion = null },
            title = { Text("Question Note & Collection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Personal study note") }, minLines = 3)
                    OutlinedTextField(value = col, onValueChange = { col = it }, label = { Text("Collection tag (e.g. Midterm)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateNote(q, note)
                    if (col != q.collectionName) onSetCollection(q, col)
                    notingQuestion = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { notingQuestion = null }) { Text("Cancel") } }
        )
    }

    reportingQuestion?.let { q ->
        var reason by remember(q.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reportingQuestion = null },
            title = { Text("Report Issue in Question") },
            text = {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Describe inaccuracy or ambiguity") }, minLines = 2)
            },
            confirmButton = {
                Button(onClick = {
                    onReport(q, reason.ifBlank { "Flagged by student" })
                    reportingQuestion = null
                }) { Text("Submit Report") }
            },
            dismissButton = { TextButton(onClick = { reportingQuestion = null }) { Text("Cancel") } }
        )
    }

    if (showGenerateDialog && chapters.isNotEmpty()) {
        var selectedCh by remember { mutableStateOf(chapters.first()) }
        var selectedTypes by remember { mutableStateOf(setOf("MCQ", "SHORT_ANSWER", "CALCULATION", "CASE_STUDY")) }
        var count by remember { mutableIntStateOf(6) }
        var diff by remember { mutableStateOf("Mixed (Easy, Medium, Hard)") }

        AlertDialog(
            onDismissRequest = { showGenerateDialog = false },
            title = { Text("Generate Questions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chapter:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(chapters) { ch ->
                            FilterChip(selected = selectedCh.id == ch.id, onClick = { selectedCh = ch }, label = { Text("Ch.${ch.chapterNumber}") })
                        }
                    }
                    Text("Question Types:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(QuestionType.entries) { qt ->
                            val isOn = selectedTypes.contains(qt.name)
                            FilterChip(
                                selected = isOn,
                                onClick = {
                                    selectedTypes = if (isOn && selectedTypes.size > 1) selectedTypes - qt.name else selectedTypes + qt.name
                                },
                                label = { Text(qt.label) }
                            )
                        }
                    }
                    Text("Count: $count questions", style = MaterialTheme.typography.labelMedium)
                    Slider(value = count.toFloat(), onValueChange = { count = it.toInt() }, valueRange = 3f..15f)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showGenerateDialog = false
                    onGenerateMore(selectedCh, selectedTypes.toList(), count, diff)
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { showGenerateDialog = false }) { Text("Cancel") } }
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
        SoraEmptyState(Icons.Rounded.FitnessCenter, "No Practice Questions", "Generate questions in the Question Bank tab to start adaptive practice.")
        return
    }

    var adaptiveEnabled by remember { mutableStateOf(true) }
    var targetDifficulty by remember { mutableStateOf("MEDIUM") }
    var currentIndex by remember { mutableIntStateOf(0) }
    var selectedAnswer by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var isCorrect by remember { mutableStateOf(false) }
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
            Text("Question ${(currentIndex % orderedPool.size) + 1} / ${orderedPool.size}", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SoraStatusBadge("Difficulty: ${q.difficulty}", SoraViolet)
                FilterChip(selected = adaptiveEnabled, onClick = { adaptiveEnabled = !adaptiveEnabled }, label = { Text("Adaptive") })
            }
        }

        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoraStatusBadge(q.questionType, SoraSoftBlue)
                    Text("Ch.${q.chapterNumber} • p.${q.sourcePage}", style = MaterialTheme.typography.labelSmall)
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
                        label = { Text("Your answer") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!submitted) {
                    Button(
                        onClick = {
                            val right = if (q.options.isNotEmpty()) {
                                selectedAnswer.equals(q.correctAnswer, ignoreCase = true)
                            } else {
                                val keys = q.correctAnswer.lowercase().split(Regex("\\W+")).filter { it.length > 3 }
                                keys.isEmpty() || keys.count { selectedAnswer.lowercase().contains(it) } * 2 >= keys.size
                            }
                            isCorrect = right
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
                        Text("Check Answer")
                    }
                } else {
                    Surface(
                        color = if (isCorrect) SoraSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (isCorrect) "Correct! (+${q.marks} pts)" else "Incorrect — Added to Mistake Notebook",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCorrect) SoraSuccess else MaterialTheme.colorScheme.error
                            )
                            Text("Correct Answer: ${q.correctAnswer}", style = MaterialTheme.typography.bodySmall)
                            if (q.explanation.isNotBlank()) {
                                Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            currentIndex++
                            selectedAnswer = ""
                            submitted = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Next Question")
                    }
                }
            }
        }
    }
}

@Composable
fun FlashcardsPanel(
    bookId: String,
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
                FilterChip(selected = dueOnly, onClick = { dueOnly = true; cardIndex = 0; flipped = false }, label = { Text("Due (${cards.count { it.nextReviewAtMillis <= now }})") })
                FilterChip(selected = !dueOnly, onClick = { dueOnly = false; cardIndex = 0; flipped = false }, label = { Text("All (${cards.size})") })
                IconButton(onClick = { shuffledOrder = !shuffledOrder; cardIndex = 0; flipped = false }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle deck", tint = if (shuffledOrder) SoraViolet else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                IconButton(onClick = { onExportDeckPdf(cards) }, enabled = cards.isNotEmpty()) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export flashcards PDF")
                }
                FilledTonalButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Card")
                }
            }
        }

        if (pool.isEmpty()) {
            SoraEmptyState(Icons.Rounded.Style, "No Flashcards Yet", "Generate chapter intelligence or add custom flashcards to begin spaced repetition.")
        } else {
            val card = pool[cardIndex % pool.size]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Card ${(cardIndex % pool.size) + 1} of ${pool.size} • ${card.deckName}", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { onToggleBookmark(card) }) {
                    Icon(
                        if (card.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = "Bookmark flashcard",
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
                        SoraStatusBadge(if (rotation <= 90f) "FRONT • ${card.format}" else "ANSWER • p.${card.pageReference}", SoraSoftBlue)
                        Text(
                            text = if (rotation <= 90f) card.front else card.back,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (rotation <= 90f && card.hint.isNotBlank()) {
                            Text("Hint: ${card.hint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = "Tap card to flip",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (flipped) {
                Text("Rate recall to schedule SM-2 next review:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onRateCard(card, "HARD"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("Difficult", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onRateCard(card, "MEDIUM"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("Medium", color = SoraWarning) }
                    Button(
                        onClick = { onRateCard(card, "EASY"); flipped = false; cardIndex++ },
                        modifier = Modifier.weight(1f)
                    ) { Text("Easy") }
                }
            } else {
                Button(onClick = { flipped = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reveal Answer")
                }
            }
        }
    }

    if (showCreateDialog) {
        var deck by remember { mutableStateOf("Custom Deck") }
        var front by remember { mutableStateOf("") }
        var back by remember { mutableStateOf("") }
        var fmt by remember { mutableStateOf("TERM_DEF") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Flashcard") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = deck, onValueChange = { deck = it }, label = { Text("Deck Name") }, singleLine = true)
                    OutlinedTextField(value = front, onValueChange = { front = it }, label = { Text("Front (Term / Question)") }, minLines = 2)
                    OutlinedTextField(value = back, onValueChange = { back = it }, label = { Text("Back (Definition / Answer)") }, minLines = 3)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateCard(deck, front, back, fmt)
                    showCreateDialog = false
                }, enabled = front.isNotBlank() && back.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
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
                Text("Study Notebook", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { creatingNew = true }, modifier = Modifier.testTag("new_note_button")) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Note")
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search notes & tags...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (bookNotes.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.Description,
                    "No Notes Yet",
                    "Write Markdown notes linked to textbook pages and convert them into flashcards anytime."
                )
            }
        } else {
            items(bookNotes, key = { it.id }) { note ->
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(note.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Ch.${note.chapterNumber} • p.${note.linkedPage} ${if (note.tags.isNotEmpty()) "• ${note.tags.joinToString()}" else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(onClick = { onConvertToFlashcards(note) }) {
                                    Icon(Icons.Rounded.Style, contentDescription = "Convert note to flashcards")
                                }
                                IconButton(onClick = { onExportNotePdf(note) }) {
                                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export note PDF")
                                }
                                IconButton(onClick = { editingNote = note }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit note")
                                }
                                IconButton(onClick = { onDeleteNote(note.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete note", tint = MaterialTheme.colorScheme.error)
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
            title = { Text(if (target == null) "New Study Note" else "Edit Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Note Title") }, singleLine = true)
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Markdown Content (# Heading, - Bullet, Term: Def)") }, minLines = 5, maxLines = 10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = pageText, onValueChange = { pageText = it }, label = { Text("Page #") }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = tagsText, onValueChange = { tagsText = it }, label = { Text("Tags") }, modifier = Modifier.weight(2f), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = quick, onCheckedChange = { quick = it })
                        Text("Quick Note", style = MaterialTheme.typography.bodySmall)
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
                }, enabled = title.isNotBlank()) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { creatingNew = false; editingNote = null }) { Text("Cancel") }
            }
        )
    }
}
