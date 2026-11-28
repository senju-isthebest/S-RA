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
import androidx.compose.ui.res.stringResource
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

private const val FILTER_ALL_KEY = "__ALL__"

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
    var selectedSubjectFilter by remember { mutableStateOf(FILTER_ALL_KEY) }
    var showArchived by remember { mutableStateOf(false) }
    var showNewSubjectDialog by remember { mutableStateOf(false) }
    var bookToRename by remember { mutableStateOf<Book?>(null) }

    val subjectNames = remember(books, subjects) {
        (listOf(FILTER_ALL_KEY) + subjects.map { it.name } + books.map { it.subjectName }).distinct().filter { it.isNotBlank() }
    }

    val filteredBooks = remember(books, searchQuery, selectedSubjectFilter, showArchived) {
        books.filter { b ->
            b.archived == showArchived &&
                (selectedSubjectFilter == FILTER_ALL_KEY || b.subjectName.equals(selectedSubjectFilter, ignoreCase = true)) &&
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(R.string.library_counts, books.count { !it.archived }, books.count { it.archived }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showNewSubjectDialog = true },
                        modifier = Modifier.testTag("add_subject_button")
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.library_subject_btn))
                    }
                    Button(
                        onClick = onUploadClick,
                        modifier = Modifier.testTag("library_upload_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.library_upload_btn))
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
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.cd_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("library_search_input")
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(subjectNames) { subj ->
                    val label = if (subj == FILTER_ALL_KEY) stringResource(R.string.label_all) else localizedSubjectName(subj)
                    FilterChip(
                        selected = selectedSubjectFilter == subj,
                        onClick = { selectedSubjectFilter = subj },
                        label = { Text(label) }
                    )
                }
                item {
                    FilterChip(
                        selected = showArchived,
                        onClick = { showArchived = !showArchived },
                        label = { Text(if (showArchived) stringResource(R.string.library_archived) else stringResource(R.string.library_show_archived)) },
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
                            contentDescription = stringResource(R.string.cd_empty_library),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Text(
                            if (showArchived) stringResource(R.string.library_empty_archived) else stringResource(R.string.library_empty_default),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.library_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!showArchived) {
                            Button(onClick = onUploadClick) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.library_upload_cta))
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
        val defaultSem = stringResource(R.string.label_fall_semester)
        val defaultFol = stringResource(R.string.label_core_folder)
        var sName by remember { mutableStateOf("") }
        var sSem by remember { mutableStateOf(defaultSem) }
        var sFolder by remember { mutableStateOf(defaultFol) }
        var sGrade by remember { mutableStateOf("A") }
        AlertDialog(
            onDismissRequest = { showNewSubjectDialog = false },
            title = { Text(stringResource(R.string.library_new_subject_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sName, onValueChange = { sName = it }, label = { Text(stringResource(R.string.library_subject_name)) }, singleLine = true)
                    OutlinedTextField(value = sSem, onValueChange = { sSem = it }, label = { Text(stringResource(R.string.library_semester)) }, singleLine = true)
                    OutlinedTextField(value = sFolder, onValueChange = { sFolder = it }, label = { Text(stringResource(R.string.library_folder)) }, singleLine = true)
                    OutlinedTextField(value = sGrade, onValueChange = { sGrade = it }, label = { Text(stringResource(R.string.library_target_grade)) }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateSubject(sName, sSem, sFolder, sGrade)
                    showNewSubjectDialog = false
                }, enabled = sName.isNotBlank()) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewSubjectDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    bookToRename?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { bookToRename = null },
            title = { Text(stringResource(R.string.library_rename_title)) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.library_title_label)) }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    onRenameBook(target.id, newName)
                    bookToRename = null
                }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { bookToRename = null }) { Text(stringResource(R.string.action_cancel)) } }
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
                    stringResource(
                        R.string.library_book_meta,
                        localizedSubjectName(book.subjectName),
                        book.semester,
                        book.sourceType,
                        book.totalPages
                    ),
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
                    SoraStatusBadge(localizedExtractionStatus(book.extractionStatus), statusColor, statusIcon)
                    if (book.failedPages.isNotEmpty()) {
                        TextButton(onClick = onRetryFailed, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(stringResource(R.string.library_retry_pages, book.failedPages.size), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_book_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text(if (book.archived) stringResource(R.string.action_unarchive) else stringResource(R.string.action_archive)) }, onClick = { menuExpanded = false; onArchive() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}
