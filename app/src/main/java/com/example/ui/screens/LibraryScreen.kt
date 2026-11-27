package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Book
import com.example.data.model.Subject
import com.example.ui.IngestionProgressState
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraWarning

@Composable
fun LibraryScreen(
    books: List<Book>,
    subjects: List<Subject>,
    ingestionProgress: IngestionProgressState,
    onUploadClick: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onRetryFailedPages: (Book) -> Unit,
    onRenameBook: (String, String) -> Unit,
    onArchiveBook: (Book) -> Unit,
    onDeleteBook: (String) -> Unit,
    onCreateSubject: (name: String, semester: String, folder: String, targetGrade: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedSubjectFilter by remember { mutableStateOf("All") }
    var showArchived by remember { mutableStateOf(false) }
    var showNewSubjectDialog by remember { mutableStateOf(false) }
    var bookToRename by remember { mutableStateOf<Book?>(null) }

    val subjectNames = remember(books, subjects) {
        (listOf("All") + subjects.map { it.name } + books.map { it.subjectName }).distinct().filter { it.isNotBlank() }
    }

    val filteredBooks = remember(books, searchQuery, selectedSubjectFilter, showArchived) {
        books.filter { b ->
            b.archived == showArchived &&
                (selectedSubjectFilter == "All" || b.subjectName.equals(selectedSubjectFilter, ignoreCase = true)) &&
                (searchQuery.isBlank() || b.title.contains(searchQuery, true) || b.subjectName.contains(searchQuery, true) || b.folder.contains(searchQuery, true))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Textbook Library", style = MaterialTheme.typography.headlineMedium)
                    Text("${books.count { !it.archived }} active • ${books.count { it.archived }} archived", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showNewSubjectDialog = true },
                        modifier = Modifier.testTag("add_subject_button")
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Subject")
                    }
                    Button(
                        onClick = onUploadClick,
                        modifier = Modifier.testTag("library_upload_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Upload")
                    }
                }
            }
        }

        if (ingestionProgress.active) {
            item {
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(ingestionProgress.statusLabel, style = MaterialTheme.typography.titleSmall)
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (ingestionProgress.totalPages > 0) ingestionProgress.processedPages.toFloat() / ingestionProgress.totalPages.toFloat() else 0.3f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search textbooks, subjects, or folders...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("library_search_input")
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(subjectNames) { subj ->
                    FilterChip(
                        selected = selectedSubjectFilter == subj,
                        onClick = { selectedSubjectFilter = subj },
                        label = { Text(subj) }
                    )
                }
                item {
                    FilterChip(
                        selected = showArchived,
                        onClick = { showArchived = !showArchived },
                        label = { Text(if (showArchived) "Archived" else "Show Archived") },
                        leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }
        }

        if (filteredBooks.isEmpty()) {
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_library),
                            contentDescription = "Empty library illustration",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Text(
                            if (showArchived) "No archived textbooks" else "No textbooks in this view yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Upload a PDF, scanned book, DOCX, PPTX, image, or lecture notes to auto-generate chapters, summaries, flashcards & exams.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!showArchived) {
                            Button(onClick = onUploadClick) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Upload Study Material")
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredBooks, key = { it.id }) { book ->
                BookLibraryCard(
                    book = book,
                    onClick = { onOpenBook(book) },
                    onRetryFailed = { onRetryFailedPages(book) },
                    onRename = { bookToRename = book },
                    onArchive = { onArchiveBook(book) },
                    onDelete = { onDeleteBook(book.id) }
                )
            }
        }
    }

    if (showNewSubjectDialog) {
        var sName by remember { mutableStateOf("") }
        var sSem by remember { mutableStateOf("Fall 2026") }
        var sFolder by remember { mutableStateOf("Core") }
        var sGrade by remember { mutableStateOf("A") }
        AlertDialog(
            onDismissRequest = { showNewSubjectDialog = false },
            title = { Text("Create Academic Subject") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sName, onValueChange = { sName = it }, label = { Text("Subject Name") }, singleLine = true)
                    OutlinedTextField(value = sSem, onValueChange = { sSem = it }, label = { Text("Semester") }, singleLine = true)
                    OutlinedTextField(value = sFolder, onValueChange = { sFolder = it }, label = { Text("Folder") }, singleLine = true)
                    OutlinedTextField(value = sGrade, onValueChange = { sGrade = it }, label = { Text("Target Grade") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateSubject(sName, sSem, sFolder, sGrade)
                    showNewSubjectDialog = false
                }, enabled = sName.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewSubjectDialog = false }) { Text("Cancel") } }
        )
    }

    bookToRename?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { bookToRename = null },
            title = { Text("Rename Textbook") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Title") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    onRenameBook(target.id, newName)
                    bookToRename = null
                }, enabled = newName.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { bookToRename = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BookLibraryCard(
    book: Book,
    onClick: () -> Unit,
    onRetryFailed: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    SoraSurfaceCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("book_card_${book.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SoraMasteryRing(progressPercent = book.masteryScore.toInt())
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.subjectName} • ${book.semester} • ${book.sourceType} (${book.totalPages}p)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (statusColor, statusIcon) = when (book.extractionStatus) {
                        "COMPLETE" -> SoraSuccess to Icons.Rounded.CheckCircle
                        "PARTIAL" -> SoraWarning to Icons.Rounded.Warning
                        "FAILED" -> MaterialTheme.colorScheme.error to Icons.Rounded.Error
                        else -> SoraSoftBlue to Icons.Rounded.Sync
                    }
                    SoraStatusBadge(book.extractionStatus, statusColor, statusIcon)
                    if (book.failedPages.isNotEmpty()) {
                        TextButton(onClick = onRetryFailed, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("Retry ${book.failedPages.size} page(s)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Book options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text(if (book.archived) "Unarchive" else "Archive") }, onClick = { menuExpanded = false; onArchive() })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}
