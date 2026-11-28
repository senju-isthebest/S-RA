package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ai.JsonObj
import com.example.data.ai.SoraJson
import com.example.data.ai.TeachBackFeedback
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun SummariesTab(
    book: Book,
    chapter: Chapter?,
    onRegenerateChapter: (difficulty: String) -> Unit,
    onExportPdf: (title: String, body: String) -> Unit,
    onPrint: (title: String, body: String) -> Unit
) {
    var summaryType by remember { mutableStateOf("QUICK") }
    var difficulty by remember { mutableStateOf("Standard") }

    if (chapter == null) {
        SoraEmptyState(
            Icons.Rounded.Summarize,
            stringResource(R.string.summaries_empty_title),
            stringResource(R.string.summaries_empty_sub)
        )
        return
    }

    val activeSummaryText = when (summaryType) {
        "QUICK" -> chapter.quickSummary
        "DETAILED" -> chapter.detailedSummary
        "BULLET" -> chapter.bulletSummary
        else -> chapter.examRevisionSummary
    }

    val summaryModes = listOf(
        "QUICK" to stringResource(R.string.summary_quick),
        "DETAILED" to stringResource(R.string.summary_detailed),
        "BULLET" to stringResource(R.string.summary_bullet),
        "EXAM" to stringResource(R.string.summary_exam)
    )
    val difficultyLevels = listOf(
        "Simplified" to stringResource(R.string.diff_simplified),
        "Standard" to stringResource(R.string.diff_standard),
        "Rigorous" to stringResource(R.string.diff_rigorous)
    )
    val sections = listOf(
        stringResource(R.string.sec_key_concepts) to chapter.keyConcepts,
        stringResource(R.string.sec_important_defs) to chapter.importantDefinitions,
        stringResource(R.string.sec_important_facts) to chapter.importantFacts,
        stringResource(R.string.sec_important_dates) to chapter.importantDates,
        stringResource(R.string.sec_formulas) to chapter.formulas,
        stringResource(R.string.sec_examples) to chapter.examples,
        stringResource(R.string.sec_misconceptions) to chapter.misconceptions,
        stringResource(R.string.sec_comparisons) to chapter.comparisons
    ).filter { it.second.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.summary_ch_header, chapter.chapterNumber, chapter.title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = { onExportPdf("${book.title} - ${chapter.chapterNumber}", activeSummaryText) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.cd_export_summary))
                    }
                    IconButton(onClick = { onPrint("${book.title} - ${chapter.chapterNumber}", activeSummaryText) }) {
                        Icon(Icons.Rounded.Print, contentDescription = stringResource(R.string.cd_print_summary))
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(summaryModes) { (key, label) ->
                    FilterChip(
                        selected = summaryType == key,
                        onClick = { summaryType = key },
                        label = { Text(label) }
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                difficultyLevels.forEach { (key, label) ->
                    FilterChip(selected = difficulty == key, onClick = { difficulty = key }, label = { Text(label) })
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { onRegenerateChapter(difficulty) }) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_generate))
                }
            }
        }

        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                if (activeSummaryText.isBlank()) {
                    Text(
                        stringResource(R.string.summaries_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SoraMarkdownText(activeSummaryText)
                }
            }
        }

        items(sections) { (title, list) ->
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                list.forEach { entry ->
                    Text("• $entry", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
fun LearnToolsTab(
    chapter: Chapter?,
    pages: List<BookPageContent>,
    teachBackResult: TeachBackFeedback?,
    comparisonOrGapResult: String?,
    onEvaluateTeachBack: (conceptTitle: String, refText: String, studentText: String) -> Unit,
    onClearTeachBack: () -> Unit,
    onRunGapOrComparison: (mode: String) -> Unit,
    onClearComparison: () -> Unit,
    onUpdateGoal: (String) -> Unit,
    onOpenTutor: () -> Unit
) {
    if (chapter == null) {
        SoraEmptyState(
            Icons.Rounded.School,
            stringResource(R.string.learn_empty_title),
            stringResource(R.string.learn_empty_sub)
        )
        return
    }
    var teachBackInput by remember { mutableStateOf("") }
    var goalText by remember(chapter.id) { mutableStateOf(chapter.userGoal) }

    val refText = remember(chapter, pages) {
        pages.filter { it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt() }
            .joinToString("\n") { it.text }.ifBlank { chapter.detailedSummary.ifBlank { chapter.quickSummary } }
    }
    val compareModeLabel = stringResource(R.string.learn_compare_mode)
    val gapsModeLabel = stringResource(R.string.learn_gaps_mode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.learn_goal_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    placeholder = { Text(stringResource(R.string.learn_goal_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUpdateGoal(goalText) }) { Text(stringResource(R.string.learn_save_goal)) }
                    OutlinedButton(onClick = onOpenTutor) {
                        Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.learn_open_tutor))
                    }
                }
            }
        }

        if (chapter.learningObjectives.isNotEmpty() || chapter.prerequisites.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    if (chapter.learningObjectives.isNotEmpty()) {
                        Text(stringResource(R.string.learn_objectives), style = MaterialTheme.typography.titleSmall, color = SoraSuccess)
                        chapter.learningObjectives.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (chapter.prerequisites.isNotEmpty()) {
                        Text(stringResource(R.string.learn_prerequisites), style = MaterialTheme.typography.titleSmall, color = SoraWarning)
                        chapter.prerequisites.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onRunGapOrComparison(compareModeLabel) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.learn_comparison_btn))
                }
                OutlinedButton(
                    onClick = { onRunGapOrComparison(gapsModeLabel) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.learn_gaps_btn))
                }
            }
        }

        if (comparisonOrGapResult != null) {
            item {
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.learn_analysis_title), style = MaterialTheme.typography.titleSmall, color = SoraSoftBlue)
                        IconButton(onClick = onClearComparison) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_dismiss)) }
                    }
                    SoraMarkdownText(comparisonOrGapResult)
                }
            }
        }

        // Teach-Back Mode
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.learn_teachback_title), style = MaterialTheme.typography.titleMedium, color = SoraViolet)
                    Text(
                        stringResource(R.string.learn_teachback_desc, chapter.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = teachBackInput,
                        onValueChange = { teachBackInput = it },
                        label = { Text(stringResource(R.string.learn_teachback_label)) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("teach_back_input")
                    )
                    Button(
                        onClick = { onEvaluateTeachBack(chapter.title, refText, teachBackInput) },
                        enabled = teachBackInput.isNotBlank(),
                        modifier = Modifier.testTag("teach_back_submit")
                    ) {
                        Text(stringResource(R.string.learn_teachback_eval))
                    }
                }
            }
        }

        if (teachBackResult != null) {
            item {
                SoraSurfaceCard(accentBorder = SoraSuccess, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.learn_teachback_score, teachBackResult.scorePercent),
                            style = MaterialTheme.typography.titleMedium,
                            color = SoraSuccess
                        )
                        IconButton(onClick = onClearTeachBack) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_feedback)) }
                    }
                    Text(teachBackResult.coachingFeedback, style = MaterialTheme.typography.bodyMedium)
                    if (teachBackResult.accuratePoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.learn_accurate_points), style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
                        teachBackResult.accuratePoints.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (teachBackResult.missingPoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.learn_missing_points), style = MaterialTheme.typography.labelMedium, color = SoraWarning)
                        teachBackResult.missingPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (teachBackResult.misconceptions.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.learn_misconceptions), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        teachBackResult.misconceptions.forEach { Text("! $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
fun MindMapsTab(
    chapter: Chapter?,
    onJumpToPage: (Int) -> Unit,
    onGenerateQuestionsFromNode: (topicLabel: String) -> Unit
) {
    if (chapter == null || chapter.conceptMapJson.isBlank()) {
        SoraEmptyState(
            Icons.Rounded.AccountTree,
            stringResource(R.string.mindmap_empty_title),
            stringResource(R.string.mindmap_empty_sub)
        )
        return
    }
    val rootNode = remember(chapter.conceptMapJson) { parseConceptNode(chapter.conceptMapJson) }
    if (rootNode == null) {
        SoraEmptyState(
            Icons.Rounded.AccountTree,
            stringResource(R.string.mindmap_invalid_title),
            stringResource(R.string.mindmap_invalid_sub)
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(stringResource(R.string.mindmap_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.mindmap_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ConceptTreeNodeItem(
                node = rootNode,
                depth = 0,
                onJumpToPage = onJumpToPage,
                onGenerateQuestionsFromNode = onGenerateQuestionsFromNode
            )
        }
    }
}

@Composable
private fun ConceptTreeNodeItem(
    node: ConceptNode,
    depth: Int,
    onJumpToPage: (Int) -> Unit,
    onGenerateQuestionsFromNode: (String) -> Unit
) {
    var expanded by remember(node.id) { mutableStateOf(depth < 2) }
    Column(modifier = Modifier.padding(start = (depth * 14).dp)) {
        SoraSurfaceCard(
            accentBorder = if (depth == 0) SoraViolet else SoraSoftBlue,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (node.children.isNotEmpty()) {
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.cd_toggle_branch)
                            )
                        }
                    }
                    Column {
                        Text(node.label, style = MaterialTheme.typography.titleSmall)
                        if (node.summary.isNotBlank()) {
                            Text(node.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row {
                    TextButton(onClick = { onJumpToPage(node.pageRef) }) {
                        Text(stringResource(R.string.page_ref_short, node.pageRef), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onGenerateQuestionsFromNode(node.label) }) {
                        Icon(Icons.Rounded.Quiz, contentDescription = stringResource(R.string.cd_generate_node_q, node.label), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded && node.children.isNotEmpty()) {
            Column {
                node.children.forEach { child ->
                    ConceptTreeNodeItem(child, depth + 1, onJumpToPage, onGenerateQuestionsFromNode)
                }
            }
        }
    }
}

@Composable
fun GlossaryTab(
    entries: List<GlossaryEntry>,
    onJumpToPage: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sortAlpha by remember { mutableStateOf(true) }

    val displayed = remember(entries, query, sortAlpha) {
        val filtered = entries.filter {
            query.isBlank() || it.term.contains(query, true) || it.definition.contains(query, true)
        }
        if (sortAlpha) filtered.sortedBy { it.term.lowercase() }
        else filtered.sortedBy { it.chapterNumber }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.glossary_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sortAlpha, onClick = { sortAlpha = true }, label = { Text(stringResource(R.string.glossary_alpha)) })
                FilterChip(selected = !sortAlpha, onClick = { sortAlpha = false }, label = { Text(stringResource(R.string.glossary_by_ch)) })
            }
        }
        if (displayed.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.MenuBook,
                    stringResource(R.string.glossary_empty_title),
                    stringResource(R.string.glossary_empty_sub)
                )
            }
        } else {
            items(displayed, key = { it.id }) { item ->
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(item.term, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onJumpToPage(item.pageReference.toInt()) }) {
                            Text(stringResource(R.string.glossary_ref, item.chapterNumber, item.pageReference), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(item.definition, style = MaterialTheme.typography.bodyMedium)
                    if (item.simplifiedExplanation.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.glossary_simple, item.simplifiedExplanation), style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                    }
                    if (item.example.isNotBlank()) {
                        Text(stringResource(R.string.glossary_example, item.example), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.relatedTerms.isNotEmpty()) {
                        Text(stringResource(R.string.glossary_related, item.relatedTerms.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun parseConceptNode(jsonStr: String): ConceptNode? {
    return try {
        fun parseObj(obj: JsonObj): ConceptNode {
            val arr = obj.arr("children")
            val children = arr?.items?.mapNotNull { (it as? JsonObj)?.let(::parseObj) } ?: emptyList()
            return ConceptNode(
                id = obj.str("id").ifBlank { obj.str("label", "node") },
                label = obj.str("label", "Concept"),
                summary = obj.str("summary"),
                pageRef = obj.intVal("pageRef", 1).coerceAtLeast(1),
                children = children
            )
        }
        parseObj(SoraJson.parseObject(jsonStr))
    } catch (_: Exception) {
        null
    }
}
