package com.example.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.SoraFormatters
import com.example.data.export.ExportManager
import com.example.data.model.*
import com.example.domain.StudentToolboxEngine
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun ToolsAnalyticsScreen(
    profile: UserProfile?,
    books: List<Book>,
    questions: List<QuestionItem>,
    flashcards: List<Flashcard>,
    exams: List<ExamRecord>,
    notes: List<StudyNote>,
    focusSessions: List<FocusSession>,
    citations: List<CitationEntry>,
    equationSolution: String?,
    aiBusyLabel: String?,
    viewModel: SoraViewModel
) {
    var subTab by remember { mutableIntStateOf(0) } // 0=Analytics, 1=Student Toolbox

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text(stringResource(R.string.tools_tab_analytics)) })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text(stringResource(R.string.tools_tab_toolbox)) })
        }

        if (subTab == 0) {
            GlobalAnalyticsTab(
                profile = profile,
                books = books,
                questions = questions,
                flashcards = flashcards,
                exams = exams,
                notes = notes,
                focusSessions = focusSessions
            )
        } else {
            StudentToolboxTab(
                books = books,
                citations = citations,
                equationSolution = equationSolution,
                aiBusyLabel = aiBusyLabel,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun GlobalAnalyticsTab(
    profile: UserProfile?,
    books: List<Book>,
    questions: List<QuestionItem>,
    flashcards: List<Flashcard>,
    exams: List<ExamRecord>,
    notes: List<StudyNote>,
    focusSessions: List<FocusSession>
) {
    val context = LocalContext.current
    val exportManager = remember { ExportManager(context) }

    val attempted = remember(questions) { questions.filter { it.attempts > 0L } }
    val accuracy = remember(attempted) {
        if (attempted.isEmpty()) 0
        else ((attempted.sumOf { it.correctCount }.toDouble() / attempted.sumOf { it.attempts }.toDouble().coerceAtLeast(1.0)) * 100.0).toInt()
    }
    val reviewedCards = remember(flashcards) { flashcards.filter { it.lastRating != "UNSEEN" } }
    val cardRetention = remember(reviewedCards) {
        if (reviewedCards.isEmpty()) 0
        else ((reviewedCards.count { it.lastRating != "HARD" }.toDouble() / reviewedCards.size) * 100.0).toInt()
    }
    val submittedExams = remember(exams) { exams.filter { it.status == "SUBMITTED" } }
    val avgExamScore = remember(submittedExams) {
        if (submittedExams.isEmpty()) 0 else submittedExams.map { it.percentage }.average().toInt()
    }
    val totalFocusMins = remember(focusSessions, profile) {
        focusSessions.sumOf { it.durationMinutes.toInt() }.coerceAtLeast(profile?.weeklyStudyMinutes?.toInt() ?: 0)
    }

    val scholarFallback = stringResource(R.string.home_scholar_fallback)
    val analyticsTitle = stringResource(R.string.analytics_title)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("analytics_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(analyticsTitle, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.analytics_formula_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = SoraSoftBlue
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StatMiniCard(stringResource(R.string.analytics_acc), stringResource(R.string.home_percent_value, accuracy), stringResource(R.string.analytics_items_sub, attempted.size), Modifier.weight(1f))
                        StatMiniCard(stringResource(R.string.analytics_recall), stringResource(R.string.home_percent_value, cardRetention), stringResource(R.string.analytics_cards_sub, reviewedCards.size), Modifier.weight(1f))
                        StatMiniCard(stringResource(R.string.analytics_exams), stringResource(R.string.home_percent_value, avgExamScore), stringResource(R.string.analytics_taken_sub, submittedExams.size), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        StatMiniCard(stringResource(R.string.analytics_focus), stringResource(R.string.home_minutes_short, totalFocusMins), stringResource(R.string.analytics_sessions_sub, focusSessions.size), Modifier.weight(1f))
                        StatMiniCard(stringResource(R.string.analytics_streak), stringResource(R.string.home_streak_days, profile?.studyStreakDays ?: 0), stringResource(R.string.analytics_active_days), Modifier.weight(1f))
                        StatMiniCard(stringResource(R.string.analytics_notes), "${notes.size}", stringResource(R.string.analytics_books_sub, books.size), Modifier.weight(1f))
                    }
                }
            }
        }

        if (books.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.analytics_by_book), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    SoraBarChart(entries = books.map { it.title.take(22) to it.masteryScore.toFloat() })
                }
            }
        }

        val weakTopics = questions.filter { !it.hidden && it.incorrectCount > 0L && !it.mistakeResolved }
            .groupBy { it.topic.ifBlank { "Ch.${it.chapterNumber}" } }
            .entries.sortedByDescending { it.value.size }.take(6)
        if (weakTopics.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.analytics_mistakes_topic), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    SoraBarChart(
                        entries = weakTopics.map { it.key to it.value.size.toFloat() },
                        maxValue = weakTopics.maxOf { it.value.size }.toFloat().coerceAtLeast(5f),
                        barColor = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.analytics_export_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.analytics_export_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val report = buildString {
                                    append("Accuracy: $accuracy% | Recall: $cardRetention% | Avg Exam: $avgExamScore%\n")
                                    append("Focus: ${totalFocusMins}m | Streak: ${profile?.studyStreakDays ?: 0}d\n\n")
                                    books.forEach { append("- ${it.title} (${it.subjectName}): ${it.masteryScore}%\n") }
                                    append("\n")
                                    notes.forEach { append("### ${it.title}\n${it.contentMarkdown}\n\n") }
                                }
                                exportManager.sharePdf(analyticsTitle, profile?.displayName ?: scholarFallback, report)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_export_pdf))
                        }
                        OutlinedButton(
                            onClick = {
                                val summary = "Accuracy: $accuracy% | Recall: $cardRetention% | Avg Exam: $avgExamScore% | Focus: ${totalFocusMins}m"
                                exportManager.printStudyMaterial(analyticsTitle, profile?.displayName ?: scholarFallback, summary)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_print))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatMiniCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = SoraSuccess)
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StudentToolboxTab(
    books: List<Book>,
    citations: List<CitationEntry>,
    equationSolution: String?,
    aiBusyLabel: String?,
    viewModel: SoraViewModel
) {
    val tools = listOf(
        stringResource(R.string.tool_calc),
        stringResource(R.string.tool_solver),
        stringResource(R.string.tool_grade),
        stringResource(R.string.tool_formulas),
        stringResource(R.string.tool_units),
        stringResource(R.string.tool_word_count),
        stringResource(R.string.tool_citations)
    )
    var selectedTool by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("toolbox_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tools.indices.toList()) { idx ->
                    FilterChip(
                        selected = selectedTool == idx,
                        onClick = { selectedTool = idx },
                        label = { Text(tools[idx]) }
                    )
                }
            }
        }

        when (selectedTool) {
            0 -> item { ScientificCalculatorCard() }
            1 -> item {
                AiEquationSolverCard(
                    solution = equationSolution,
                    busyLabel = aiBusyLabel,
                    onSolve = { viewModel.solveEquation(it) },
                    onClear = { viewModel.clearEquationSolution() }
                )
            }
            2 -> item { GradeCalculatorCard() }
            3 -> {
                item { Text(stringResource(R.string.formulas_core_title), style = MaterialTheme.typography.titleMedium) }
                items(StudentToolboxEngine.formulaLibrary) { f ->
                    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                        SoraStatusBadge(localizedFormulaCategory(f.category), SoraSoftBlue)
                        Spacer(Modifier.height(4.dp))
                        Text(f.name, style = MaterialTheme.typography.titleSmall)
                        Text(SoraFormatters.ltrIsolate(f.expression), style = MaterialTheme.typography.labelLarge, color = SoraViolet)
                        Text(f.variables, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val bookFormulas = books.filter { it.formulaSheet.isNotBlank() }
                if (bookFormulas.isNotEmpty()) {
                    item { Text(stringResource(R.string.formulas_extracted_title), style = MaterialTheme.typography.titleMedium) }
                    items(bookFormulas, key = { it.id }) { b ->
                        SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                            Text(b.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            SoraMarkdownText(b.formulaSheet)
                        }
                    }
                }
            }
            4 -> item { UnitConverterCard() }
            5 -> item { WordCounterCard() }
            6 -> item {
                CitationOrganizerCard(
                    citations = citations,
                    onAddCitation = { st, au, ti, yr, pub, pg, fmt ->
                        viewModel.addCitation(st, au, ti, yr, pub, pg, fmt)
                    },
                    onDeleteCitation = { viewModel.deleteCitation(it) }
                )
            }
        }
    }
}

@Composable
private fun ScientificCalculatorCard() {
    var expr by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf<String?>(null) }
    var steps by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val invalidMsg = stringResource(R.string.calc_invalid)

    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.calc_title), style = MaterialTheme.typography.titleMedium)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = expr,
                    onValueChange = { expr = it },
                    label = { Text(stringResource(R.string.calc_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("calc_input")
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("sin(", "cos(", "tan(", "sqrt(", "ln(", "log(", "^", "pi", "e", "(", ")")) { token ->
                        SuggestionChip(onClick = { expr += token }, label = { Text(token) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        StudentToolboxEngine.evaluateScientificExpression(expr)
                            .onSuccess { (res, trace) ->
                                errorText = null
                                resultText = if (res % 1.0 == 0.0) res.toLong().toString() else "%.6f".format(res).trimEnd('0').trimEnd('.')
                                steps = trace
                            }
                            .onFailure {
                                resultText = null
                                steps = emptyList()
                                errorText = it.message ?: invalidMsg
                            }
                    },
                    modifier = Modifier.weight(1f).testTag("calc_eval_button")
                ) { Text(stringResource(R.string.calc_eval)) }
                OutlinedButton(onClick = { expr = ""; resultText = null; steps = emptyList(); errorText = null }) {
                    Text(stringResource(R.string.action_clear))
                }
            }
            if (errorText != null) {
                Text(errorText.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (resultText != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.calc_result, SoraFormatters.ltrIsolate(resultText.orEmpty())), style = MaterialTheme.typography.titleLarge, color = SoraSuccess)
                        if (steps.isNotEmpty()) {
                            Text(stringResource(R.string.calc_steps), style = MaterialTheme.typography.labelMedium)
                            steps.forEachIndexed { idx, s -> Text("${idx + 1}. ${SoraFormatters.ltrIsolate(s)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiEquationSolverCard(
    solution: String?,
    busyLabel: String?,
    onSolve: (String) -> Unit,
    onClear: () -> Unit
) {
    var problem by remember { mutableStateOf("") }
    SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.solver_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = problem,
                onValueChange = { problem = it },
                label = { Text(stringResource(R.string.solver_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("ai_solver_input")
            )
            if (busyLabel != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSolve(problem) },
                    enabled = problem.isNotBlank() && busyLabel == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.solver_btn))
                }
                if (solution != null) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                }
            }
            if (!solution.isNullOrBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        SoraMarkdownText(solution)
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeCalculatorCard() {
    var scores by remember { mutableStateOf(listOf(88.0 to 30.0, 92.0 to 30.0)) }
    var newScore by remember { mutableStateOf("85") }
    var newWeight by remember { mutableStateOf("20") }

    var currentAvg by remember { mutableStateOf("86") }
    var targetAvg by remember { mutableStateOf("90") }
    var finalWeight by remember { mutableStateOf("30") }

    val (weightedAvg, totalWeight) = remember(scores) { StudentToolboxEngine.calculateWeightedGrade(scores) }
    val requiredFinal = remember(currentAvg, targetAvg, finalWeight) {
        StudentToolboxEngine.calculateRequiredFinalScore(
            currentAvg.toDoubleOrNull() ?: 85.0,
            targetAvg.toDoubleOrNull() ?: 90.0,
            finalWeight.toDoubleOrNull() ?: 30.0
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.grade_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.grade_current_avg, "%.2f".format(weightedAvg), "%.0f".format(totalWeight)),
                    style = MaterialTheme.typography.titleSmall,
                    color = SoraSuccess
                )
                scores.forEachIndexed { i, (sc, wt) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.grade_item_row, i + 1, sc.toString(), wt.toString()), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { scores = scores.filterIndexed { idx, _ -> idx != i } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_remove_item), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newScore, onValueChange = { newScore = it }, label = { Text(stringResource(R.string.grade_score_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newWeight, onValueChange = { newWeight = it }, label = { Text(stringResource(R.string.grade_weight_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                    Button(onClick = {
                        val s = newScore.toDoubleOrNull()
                        val w = newWeight.toDoubleOrNull()
                        if (s != null && w != null && w > 0) scores = scores + (s to w)
                    }) { Text(stringResource(R.string.action_add)) }
                }
            }
        }

        SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.grade_final_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = currentAvg, onValueChange = { currentAvg = it }, label = { Text(stringResource(R.string.grade_current_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = targetAvg, onValueChange = { targetAvg = it }, label = { Text(stringResource(R.string.grade_target_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = finalWeight, onValueChange = { finalWeight = it }, label = { Text(stringResource(R.string.grade_final_wt_label)) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Text(
                    stringResource(R.string.grade_needed_result, "%.1f".format(requiredFinal)),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (requiredFinal > 100.0) SoraWarning else SoraSuccess
                )
            }
        }
    }
}

@Composable
private fun UnitConverterCard() {
    var catIndex by remember { mutableIntStateOf(0) }
    val cat = StudentToolboxEngine.unitCategories[catIndex]
    val unitKeys = cat.units.keys.toList()
    var fromUnit by remember(catIndex) { mutableStateOf(unitKeys.first()) }
    var toUnit by remember(catIndex) { mutableStateOf(unitKeys.getOrElse(1) { unitKeys.first() }) }
    var inputVal by remember { mutableStateOf("1") }

    val converted = remember(inputVal, cat, fromUnit, toUnit) {
        val v = inputVal.toDoubleOrNull() ?: 0.0
        StudentToolboxEngine.convertUnit(v, cat, fromUnit, toUnit)
    }

    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.units_title), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StudentToolboxEngine.unitCategories.indices.toList()) { i ->
                    FilterChip(
                        selected = catIndex == i,
                        onClick = { catIndex = i },
                        label = { Text(localizedUnitCategoryName(i, StudentToolboxEngine.unitCategories[i].name.substringBefore(" ("))) }
                    )
                }
            }
            OutlinedTextField(value = inputVal, onValueChange = { inputVal = it }, label = { Text(stringResource(R.string.units_value_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.units_from), style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(unitKeys) { u -> FilterChip(selected = fromUnit == u, onClick = { fromUnit = u }, label = { Text(u) }) }
            }
            Text(stringResource(R.string.units_to), style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(unitKeys) { u -> FilterChip(selected = toUnit == u, onClick = { toUnit = u }, label = { Text(u) }) }
            }
            val formattedConverted = "%.4f".format(converted).trimEnd('0').trimEnd('.')
            Text(
                SoraFormatters.ltrIsolate(stringResource(R.string.unit_conversion_eq, inputVal, fromUnit, formattedConverted, toUnit)),
                style = MaterialTheme.typography.titleMedium,
                color = SoraSuccess
            )
        }
    }
}

@Composable
private fun WordCounterCard() {
    var text by remember { mutableStateOf("") }
    val (words, chars, readMins) = remember(text) { StudentToolboxEngine.countTextMetrics(text) }

    SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.wc_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.wc_hint)) },
                minLines = 5,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniCard(stringResource(R.string.wc_words), "$words", stringResource(R.string.wc_count_sub), Modifier.weight(1f))
                StatMiniCard(stringResource(R.string.wc_chars), "$chars", stringResource(R.string.wc_chars_sub), Modifier.weight(1f))
                StatMiniCard(stringResource(R.string.wc_read_time), stringResource(R.string.home_minutes_short, readMins), stringResource(R.string.wc_wpm_sub), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CitationOrganizerCard(
    citations: List<CitationEntry>,
    onAddCitation: (style: String, authors: String, title: String, year: String, publisher: String, pages: String, formattedText: String) -> Unit,
    onDeleteCitation: (String) -> Unit
) {
    var style by remember { mutableStateOf("APA") }
    var authors by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("2025") }
    var publisher by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cit_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("APA", "MLA", "CHICAGO", "IEEE").forEach { st ->
                        FilterChip(selected = style == st, onClick = { style = st }, label = { Text(st) })
                    }
                }
                OutlinedTextField(value = authors, onValueChange = { authors = it }, label = { Text(stringResource(R.string.cit_authors)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.cit_book_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text(stringResource(R.string.cit_year)) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = pages, onValueChange = { pages = it }, label = { Text(stringResource(R.string.cit_pages)) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = publisher, onValueChange = { publisher = it }, label = { Text(stringResource(R.string.cit_publisher)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val formatted = StudentToolboxEngine.formatCitation(style, authors, title, year, publisher, pages)
                        onAddCitation(style, authors, title, year, publisher, pages, formatted)
                        title = ""; authors = ""
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.cit_generate_btn)) }
            }
        }

        citations.forEach { c ->
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        SoraStatusBadge(c.style, SoraViolet)
                        Spacer(Modifier.height(4.dp))
                        Text(c.formattedText, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDeleteCitation(c.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_citation), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
