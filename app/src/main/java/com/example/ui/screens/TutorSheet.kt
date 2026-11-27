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
import androidx.compose.ui.unit.dp
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import com.example.data.model.TutorMessage
import com.example.data.model.TutorMode
import com.example.ui.components.SoraMarkdownText
import com.example.ui.components.SoraStatusBadge
import com.example.ui.components.SoraSurfaceCard
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
    onSendMessage: (TutorMode, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var activeMode by remember { mutableStateOf(TutorMode.EXPLAIN) }
    var input by remember { mutableStateOf("") }
    var includePageImage by remember { mutableStateOf(false) }

    val bookMessages = remember(messages, book.id) {
        messages.filter { it.bookId == book.id }
    }

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
                    Text("SŌRA AI Tutor", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${book.title} • ${chapter?.let { "Ch. ${it.chapterNumber}" } ?: "Overview"} • Page ${currentPage?.pageNumber ?: book.currentPage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close AI Tutor")
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TutorMode.entries) { mode ->
                    FilterChip(
                        selected = activeMode == mode,
                        onClick = { activeMode = mode },
                        label = { Text(mode.label) }
                    )
                }
            }

            Text(
                text = "${activeMode.label}: ${activeMode.description}",
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
                                Text("Contextual Academic Assistant", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Ask anything about Page ${currentPage?.pageNumber ?: 1}. Switch modes anytime without losing context. Citations always link back to your source material.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AssistChip(
                                        onClick = { onSendMessage(activeMode, "Explain the core concept on this page step by step.", includePageImage) },
                                        label = { Text("Explain this page") }
                                    )
                                    AssistChip(
                                        onClick = { onSendMessage(TutorMode.SOCRATIC, "Quiz me on this page using Socratic questioning.", includePageImage) },
                                        label = { Text("Socratic check") }
                                    )
                                }
                            }
                        }
                    }
                }

                items(bookMessages, key = { it.id }) { msg ->
                    val isUser = msg.role == "USER"
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
                                    if (isUser) "You (${msg.mode})" else "SŌRA Tutor (${msg.mode})",
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
                                    Text("SŌRA Tutor (${activeMode.label}) streaming...", style = MaterialTheme.typography.labelSmall, color = SoraViolet)
                                }
                                SoraMarkdownText(streamingText.ifBlank { "Thinking..." })
                            }
                        }
                    }
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
                        Text("Analyze page diagram / image with Vision", style = MaterialTheme.typography.bodySmall)
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
                    placeholder = { Text("Ask in ${activeMode.label} mode...") },
                    maxLines = 3,
                    modifier = Modifier.weight(1f).testTag("tutor_input")
                )
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank() && streamingText == null) {
                            onSendMessage(activeMode, input, includePageImage)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank() && streamingText == null,
                    modifier = Modifier.size(50.dp).testTag("tutor_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send to Tutor")
                }
            }
        }
    }
}
