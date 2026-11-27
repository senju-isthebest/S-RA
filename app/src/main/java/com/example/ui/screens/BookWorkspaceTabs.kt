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
import androidx.compose.ui.unit.dp
import com.example.data.ai.TeachBackFeedback
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning
import org.json.JSONObject

@Composable
fun SummariesTab(
    book: Book,
    chapter: Chapter?,
    onRegenerateChapter: (difficulty: String) -> Unit,
    onExportPdf: (title: String, body: String) -> Unit,
    onPrint: (title: String, body: String) -> Unit
) {
    var summaryType by remember { mutableStateOf("Quick") }
    var difficulty by remember { mutableStateOf("Standard") }

    if (chapter == null) {
        SoraEmptyState(Icons.Rounded.Summarize, "No Chapters Available", "Analyze a textbook first to view AI chapter intelligence.")
        return
    }

    val activeSummaryText = when (summaryType) {
        "Quick" -> chapter.quickSummary
        "Detailed" -> chapter.detailedSummary
        "Bullet" -> chapter.bulletSummary
        else -> chapter.examRevisionSummary
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ch. ${chapter.chapterNumber}: ${chapter.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Row {
                    IconButton(onClick = { onExportPdf("${book.title} - Ch ${chapter.chapterNumber} Summary", activeSummaryText) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export summary PDF")
                    }
                    IconButton(onClick = { onPrint("${book.title} - Ch ${chapter.chapterNumber}", activeSummaryText) }) {
                        Icon(Icons.Rounded.Print, contentDescription = "Print summary")
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("Quick", "Detailed", "Bullet", "Exam Revision")) { mode ->
                    FilterChip(
                        selected = summaryType == mode,
                        onClick = { summaryType = mode },
                        label = { Text(mode) }
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Simplified", "Standard", "Rigorous").forEach { lvl ->
                    FilterChip(selected = difficulty == lvl, onClick = { difficulty = lvl }, label = { Text(lvl) })
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { onRegenerateChapter(difficulty) }) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Generate")
                }
            }
        }

        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                if (activeSummaryText.isBlank()) {
                    Text(
                        "Tap Generate to synthesize AI summaries, concepts, formulas, misconceptions, and glossary for this chapter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SoraMarkdownText(activeSummaryText)
                }
            }
        }

        val sections = listOf(
            "Key Concepts" to chapter.keyConcepts,
            "Important Definitions" to chapter.importantDefinitions,
            "Important Facts" to chapter.importantFacts,
            "Important Dates" to chapter.importantDates,
            "Formulas" to chapter.formulas,
            "Examples" to chapter.examples,
            "Common Misconceptions" to chapter.misconceptions,
            "Important Comparisons" to chapter.comparisons
        ).filter { it.second.isNotEmpty() }

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
    book: Book,
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
        SoraEmptyState(Icons.Rounded.School, "Select a Chapter", "Choose a chapter to access Teach-Back, Knowledge Gaps, and Comparison Tables.")
        return
    }
    var teachBackInput by remember { mutableStateOf("") }
    var goalText by remember(chapter.id) { mutableStateOf(chapter.userGoal) }

    val refText = remember(chapter, pages) {
        pages.filter { it.pageNumber in chapter.startPage.toInt()..chapter.endPage.toInt() }
            .joinToString("\n") { it.text }.ifBlank { chapter.detailedSummary.ifBlank { chapter.quickSummary } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                Text("Chapter Learning Goal", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    placeholder = { Text("e.g., Master derivations and score >= 90% on quiz") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUpdateGoal(goalText) }) { Text("Save Goal") }
                    OutlinedButton(onClick = onOpenTutor) {
                        Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open 11-Mode Tutor")
                    }
                }
            }
        }

        if (chapter.learningObjectives.isNotEmpty() || chapter.prerequisites.isNotEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    if (chapter.learningObjectives.isNotEmpty()) {
                        Text("Learning Objectives", style = MaterialTheme.typography.titleSmall, color = SoraSuccess)
                        chapter.learningObjectives.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (chapter.prerequisites.isNotEmpty()) {
                        Text("Prerequisites", style = MaterialTheme.typography.titleSmall, color = SoraWarning)
                        chapter.prerequisites.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onRunGapOrComparison("Comparison Table of Key Concepts") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Comparison Table")
                }
                OutlinedButton(
                    onClick = { onRunGapOrComparison("Prerequisite Knowledge Gaps & Difficult Sections") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Knowledge Gaps")
                }
            }
        }

        if (comparisonOrGapResult != null) {
            item {
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("AI Learning Analysis", style = MaterialTheme.typography.titleSmall, color = SoraSoftBlue)
                        IconButton(onClick = onClearComparison) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
                    }
                    SoraMarkdownText(comparisonOrGapResult)
                }
            }
        }

        // Teach-Back Mode
        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Teach-Back Mastery Check", style = MaterialTheme.typography.titleMedium, color = SoraViolet)
                    Text(
                        "Explain \"${chapter.title}\" in your own words as if teaching a classmate. SŌRA will evaluate accuracy and identify missing concepts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = teachBackInput,
                        onValueChange = { teachBackInput = it },
                        label = { Text("Your explanation") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("teach_back_input")
                    )
                    Button(
                        onClick = { onEvaluateTeachBack(chapter.title, refText, teachBackInput) },
                        enabled = teachBackInput.isNotBlank(),
                        modifier = Modifier.testTag("teach_back_submit")
                    ) {
                        Text("Evaluate My Explanation")
                    }
                }
            }
        }

        if (teachBackResult != null) {
            item {
                SoraSurfaceCard(accentBorder = SoraSuccess, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Teach-Back Score: ${teachBackResult.scorePercent}%", style = MaterialTheme.typography.titleMedium, color = SoraSuccess)
                        IconButton(onClick = onClearTeachBack) { Icon(Icons.Rounded.Close, contentDescription = "Close feedback") }
                    }
                    Text(teachBackResult.coachingFeedback, style = MaterialTheme.typography.bodyMedium)
                    if (teachBackResult.accuratePoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Accurate Points:", style = MaterialTheme.typography.labelMedium, color = SoraSuccess)
                        teachBackResult.accuratePoints.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (teachBackResult.missingPoints.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Missing Information:", style = MaterialTheme.typography.labelMedium, color = SoraWarning)
                        teachBackResult.missingPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (teachBackResult.misconceptions.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Misconceptions:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
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
            "Concept Map Not Generated Yet",
            "Open the Summaries tab and tap Generate to build an interactive concept map for this chapter."
        )
        return
    }
    val rootNode = remember(chapter.conceptMapJson) { parseConceptNode(chapter.conceptMapJson) }
    if (rootNode == null) {
        SoraEmptyState(Icons.Rounded.AccountTree, "Invalid Concept Map Data", "Regenerate chapter intelligence to rebuild the map.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Interactive Concept Map", style = MaterialTheme.typography.titleLarge)
            Text("Expand/collapse branches, jump to source pages, or generate targeted questions from any node.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                contentDescription = "Toggle branch"
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
                        Text("p.${node.pageRef}", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onGenerateQuestionsFromNode(node.label) }) {
                        Icon(Icons.Rounded.Quiz, contentDescription = "Generate questions for ${node.label}", modifier = Modifier.size(18.dp))
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
                placeholder = { Text("Search glossary terms...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sortAlpha, onClick = { sortAlpha = true }, label = { Text("Alphabetical") })
                FilterChip(selected = !sortAlpha, onClick = { sortAlpha = false }, label = { Text("By Chapter") })
            }
        }
        if (displayed.isEmpty()) {
            item {
                SoraEmptyState(Icons.Rounded.MenuBook, "No Glossary Terms Yet", "Generate chapter intelligence to extract key terminology and simplified explanations.")
            }
        } else {
            items(displayed, key = { it.id }) { item ->
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(item.term, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { onJumpToPage(item.pageReference.toInt()) }) {
                            Text("Ch.${item.chapterNumber} • p.${item.pageReference}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(item.definition, style = MaterialTheme.typography.bodyMedium)
                    if (item.simplifiedExplanation.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Simple: ${item.simplifiedExplanation}", style = MaterialTheme.typography.bodySmall, color = SoraSoftBlue)
                    }
                    if (item.example.isNotBlank()) {
                        Text("Example: ${item.example}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.relatedTerms.isNotEmpty()) {
                        Text("Related: ${item.relatedTerms.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun parseConceptNode(jsonStr: String): ConceptNode? {
    return try {
        fun parseObj(obj: JSONObject): ConceptNode {
            val arr = obj.optJSONArray("children")
            val children = if (arr != null) (0 until arr.length()).map { parseObj(arr.getJSONObject(it)) } else emptyList()
            return ConceptNode(
                id = obj.optString("id", obj.optString("label", "node")),
                label = obj.optString("label", "Concept"),
                summary = obj.optString("summary", ""),
                pageRef = obj.optInt("pageRef", 1).coerceAtLeast(1),
                children = children
            )
        }
        parseObj(JSONObject(jsonStr))
    } catch (_: Exception) {
        null
    }
}
