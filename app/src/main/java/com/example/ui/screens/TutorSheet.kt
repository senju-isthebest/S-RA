package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ai.RetrievedPassage
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.TutorMessage
import com.example.data.model.TutorMode
import com.example.ui.components.SoraMarkdownText
import com.example.ui.components.SoraStatusBadge
import com.example.ui.components.SoraSurfaceCard
import com.example.ui.components.localizedDescription
import com.example.ui.components.localizedLabel
import com.example.ui.components.localizedTutorModeByName
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorBottomSheet(
    book: Book,
    chapter: Chapter?,
    currentPage: BookPageContent?,
    messages: List<TutorMessage>,
    streamingText: String?,
    retrievedPassages: List<RetrievedPassage> = emptyList(),
    onSendMessage: (TutorMode, String, Boolean, Boolean) -> Unit,
    onJumpToPassagePage: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    var activeMode by remember { mutableStateOf(TutorMode.EXPLAIN) }
    var input by remember { mutableStateOf("") }
    var includePageImage by remember { mutableStateOf(false) }
    var fullBookSearchEnabled by remember { mutableStateOf(true) }

    val bookMessages = remember(messages, book.id) {
        messages.filter { it.bookId == book.id }
    }
    val chapterLabel = chapter?.let { stringResource(R.string.qb_chapter_short, it.chapterNumber) }
        ?: stringResource(R.string.tutor_overview_fallback)
    val explainPrompt = stringResource(R.string.tutor_chip_explain_prompt)
    val socraticPrompt = stringResource(R.string.tutor_chip_socratic_prompt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tutor_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            R.string.tutor_subtitle,
                            book.title,
                            chapterLabel,
                            currentPage?.pageNumber ?: book.currentPage.toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_tutor))
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TutorMode.entries) { mode ->
                    FilterChip(
                        selected = activeMode == mode,
                        onClick = { activeMode = mode },
                        label = { Text(mode.localizedLabel()) }
                    )
                }
            }

            Text(
                text = "${activeMode.localizedLabel()}: ${activeMode.localizedDescription()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (bookMessages.isEmpty() && streamingText == null) {
                    item {
                        SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.tutor_empty_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.tutor_empty_desc, currentPage?.pageNumber ?: 1),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(
                                        onClick = { onSendMessage(activeMode, explainPrompt, includePageImage, fullBookSearchEnabled) },
                                        label = { Text(stringResource(R.string.tutor_chip_explain)) }
                                    )
                                    AssistChip(
                                        onClick = { onSendMessage(TutorMode.SOCRATIC, socraticPrompt, includePageImage, fullBookSearchEnabled) },
                                        label = { Text(stringResource(R.string.tutor_chip_socratic)) }
                                    )
                                }
                            }
                        }
                    }
                }

                items(bookMessages, key = { it.id }) { msg ->
                    val isUser = msg.role == "USER"
                    val modeLabel = localizedTutorModeByName(msg.mode)
                    SoraSurfaceCard(
                        accentBorder = if (isUser) SoraSoftBlue else SoraViolet,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (isUser) stringResource(R.string.tutor_role_you, modeLabel)
                                    else stringResource(R.string.tutor_role_sora, modeLabel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) SoraSoftBlue else SoraViolet
                                )
                                if (msg.citations.isNotEmpty()) {
                                    SoraStatusBadge(msg.citations.joinToString(" • "), SoraSoftBlue)
                                }
                            }
                            SoraMarkdownText(msg.text)
                            if (msg.distinctionNote.isNotBlank()) {
                                Text(
                                    msg.distinctionNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (streamingText != null) {
                    item {
                        SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text(
                                        stringResource(R.string.tutor_streaming, activeMode.localizedLabel()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoraViolet
                                    )
                                }
                                SoraMarkdownText(streamingText.ifBlank { stringResource(R.string.tutor_thinking) })
                            }
                        }
                    }
                }
            }

            if (retrievedPassages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.tutor_retrieved_sources, retrievedPassages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(retrievedPassages) { passage ->
                            AssistChip(
                                onClick = {
                                    onJumpToPassagePage(passage.pageNumber)
                                    onDismiss()
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.tutor_passage_chip,
                                            passage.chapterNumber.coerceAtLeast(1L),
                                            passage.pageNumber
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = fullBookSearchEnabled,
                        onCheckedChange = { fullBookSearchEnabled = it },
                        modifier = Modifier.testTag("tutor_fullbook_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tutor_fullbook_toggle), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (book.sourceType in listOf("PDF", "IMAGE")) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = includePageImage, onCheckedChange = { includePageImage = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tutor_vision_toggle), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.tutor_input_hint, activeMode.localizedLabel())) },
                    maxLines = 3,
                    modifier = Modifier.weight(1f).testTag("tutor_input")
                )
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank() && streamingText == null) {
                            onSendMessage(activeMode, input, includePageImage, fullBookSearchEnabled)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && streamingText == null,
                    modifier = Modifier.size(50.dp).testTag("tutor_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cd_send_tutor))
                }
            }
        }
    }
}
