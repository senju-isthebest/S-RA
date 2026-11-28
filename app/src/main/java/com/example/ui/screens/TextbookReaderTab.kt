package com.example.ui.screens

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppLanguage
import com.example.core.LocalAppLanguage
import com.example.data.ai.SoraJson
import com.example.data.local.DocumentProcessor
import com.example.data.model.Book
import com.example.data.model.BookPageContent
import com.example.ui.components.SoraMarkdownText
import com.example.ui.components.SoraStatusBadge
import com.example.ui.components.SoraSurfaceCard
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun TextbookReaderTab(
    book: Book,
    pages: List<BookPageContent>,
    documentProcessor: DocumentProcessor,
    readingAssistantResult: String?,
    onPageChange: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onAddHighlight: (Int, String) -> Unit,
    onAddLinkedNote: (Int, String) -> Unit,
    onRunReadingAssistant: (action: String, text: String, pageNum: Int) -> Unit,
    onClearAssistant: () -> Unit,
    onOpenTutorWithImage: () -> Unit
) {
    val context = LocalContext.current
    val appLanguage = LocalAppLanguage.current
    var currentPageNum by remember(book.id) { mutableIntStateOf(book.currentPage.toInt().coerceIn(1, pages.size.coerceAtLeast(1))) }
    var searchQuery by remember { mutableStateOf("") }
    var showVisualPage by remember { mutableStateOf(book.sourceType == "PDF" || book.sourceType == "IMAGE") }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showHighlightDialog by remember { mutableStateOf(false) }
    var showAssistantPicker by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

    val tts = remember {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
            }
        }
        instance
    }

    LaunchedEffect(ttsReady, appLanguage) {
        if (ttsReady) {
            tts.language = appLanguage.locale
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(book.id, currentPageNum, showVisualPage) {
        onPageChange(currentPageNum)
        if (showVisualPage) {
            renderedBitmap = when (book.sourceType) {
                "PDF" -> documentProcessor.renderPdfPageBitmap(book.localBookId, book.fileName, currentPageNum - 1, 1400)
                "IMAGE" -> documentProcessor.loadStoredImageBitmap(book.localBookId, book.fileName)
                else -> null
            }
        } else {
            renderedBitmap = null
        }
    }

    val currentPage = pages.firstOrNull { it.pageNumber == currentPageNum }
    val searchHits = remember(searchQuery, pages) {
        if (searchQuery.isBlank()) emptyList()
        else pages.filter { it.text.contains(searchQuery, ignoreCase = true) }.map { it.pageNumber }
    }
    val highlightsObj = remember(book.highlightsJson) {
        try { SoraJson.parseObject(book.highlightsJson.ifBlank { "{}" }) } catch (_: Exception) { null }
    }
    val pageHighlights = highlightsObj?.str(currentPageNum.toString()).orEmpty()
    val isBookmarked = book.bookmarkedPages.contains(currentPageNum.toLong())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Reader Controls Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { if (currentPageNum > 1) currentPageNum-- },
                    enabled = currentPageNum > 1,
                    modifier = Modifier.testTag("reader_prev_page")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_prev_page))
                }
                Text(
                    stringResource(R.string.reader_page_counter, currentPageNum, pages.size.coerceAtLeast(1)),
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(
                    onClick = { if (currentPageNum < pages.size) currentPageNum++ },
                    enabled = currentPageNum < pages.size,
                    modifier = Modifier.testTag("reader_next_page")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.cd_next_page))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { onToggleBookmark(currentPageNum) }) {
                    Icon(
                        if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_bookmark_page),
                        tint = if (isBookmarked) SoraWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showHighlightDialog = true }) {
                    Icon(Icons.Rounded.BorderColor, contentDescription = stringResource(R.string.cd_highlight_note))
                }
                IconButton(onClick = {
                    val speakText = currentPage?.text.orEmpty()
                    if (isSpeaking) {
                        tts.stop()
                        isSpeaking = false
                    } else if (ttsReady && speakText.isNotBlank()) {
                        tts.speak(speakText.take(3500), TextToSpeech.QUEUE_FLUSH, null, "sora_p_$currentPageNum")
                        isSpeaking = true
                    }
                }) {
                    Icon(
                        if (isSpeaking) Icons.Rounded.StopCircle else Icons.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.cd_read_aloud),
                        tint = if (isSpeaking) SoraViolet else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Search inside textbook
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.reader_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (searchHits.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reader_matches), style = MaterialTheme.typography.labelSmall)
                searchHits.take(6).forEach { p ->
                    FilterChip(
                        selected = p == currentPageNum,
                        onClick = { currentPageNum = p },
                        label = { Text(stringResource(R.string.page_ref_short, p)) }
                    )
                }
            }
        }

        // View Mode & Zoom Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (book.sourceType in listOf("PDF", "IMAGE")) {
                    FilterChip(
                        selected = showVisualPage,
                        onClick = { showVisualPage = true },
                        label = { Text(stringResource(R.string.reader_original_page)) }
                    )
                }
                FilterChip(
                    selected = !showVisualPage,
                    onClick = { showVisualPage = false },
                    label = { Text(stringResource(R.string.reader_extracted_text)) }
                )
            }
            if (showVisualPage && renderedBitmap != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f) }) {
                        Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.cd_zoom_out))
                    }
                    Text(stringResource(R.string.home_percent_value, (zoomScale * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) }) {
                        Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.cd_zoom_in))
                    }
                }
            }
        }

        // Page Content View
        if (showVisualPage && renderedBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Image(
                    bitmap = renderedBitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_page_image, currentPageNum),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                )
            }
        } else {
            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                val text = currentPage?.text.orEmpty()
                if (text.isBlank()) {
                    Text(
                        currentPage?.error ?: stringResource(R.string.reader_no_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SoraStatusBadge(currentPage?.extractionMethod ?: "TEXT", SoraSoftBlue)
                        Text(stringResource(R.string.reader_chars_count, text.length), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    SoraMarkdownText(text)
                }
            }
        }

        if (pageHighlights.isNotBlank()) {
            SoraSurfaceCard(accentBorder = SoraWarning, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reader_highlights_title, currentPageNum), style = MaterialTheme.typography.labelMedium, color = SoraWarning)
                Spacer(Modifier.height(4.dp))
                Text(pageHighlights, style = MaterialTheme.typography.bodySmall)
            }
        }

        // AI Reading Assistant & Image Understanding Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showAssistantPicker = true },
                modifier = Modifier.weight(1f).testTag("reading_assistant_button"),
                enabled = !currentPage?.text.isNullOrBlank()
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.reader_assistant_btn))
            }
            OutlinedButton(
                onClick = onOpenTutorWithImage,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.reader_diagram_btn))
            }
        }

        if (readingAssistantResult != null) {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.reader_insight_title), style = MaterialTheme.typography.titleSmall, color = SoraViolet)
                    IconButton(onClick = onClearAssistant) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_insight))
                    }
                }
                SoraMarkdownText(readingAssistantResult)
            }
        }
    }

    if (showHighlightDialog) {
        var snippet by remember { mutableStateOf("") }
        var alsoNote by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showHighlightDialog = false },
            title = { Text(stringResource(R.string.reader_highlight_dialog, currentPageNum)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = snippet,
                        onValueChange = { snippet = it },
                        label = { Text(stringResource(R.string.reader_highlight_hint)) },
                        minLines = 3
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoNote, onCheckedChange = { alsoNote = it })
                        Text(stringResource(R.string.reader_also_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onAddHighlight(currentPageNum, snippet)
                    if (alsoNote) onAddLinkedNote(currentPageNum, snippet)
                    showHighlightDialog = false
                }, enabled = snippet.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showHighlightDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    if (showAssistantPicker) {
        val translateLabel = if (appLanguage == AppLanguage.ARABIC) {
            stringResource(R.string.ra_translate_en)
        } else {
            stringResource(R.string.ra_translate_ar)
        }
        val actions = listOf(
            stringResource(R.string.ra_simplify),
            stringResource(R.string.ra_explain),
            stringResource(R.string.ra_definitions),
            stringResource(R.string.ra_examples),
            translateLabel,
            stringResource(R.string.ra_practice),
            stringResource(R.string.ra_summary)
        )
        AlertDialog(
            onDismissRequest = { showAssistantPicker = false },
            title = { Text(stringResource(R.string.reader_assistant_dialog, currentPageNum)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions.forEach { act ->
                        OutlinedButton(
                            onClick = {
                                showAssistantPicker = false
                                onRunReadingAssistant(act, currentPage?.text?.take(3500).orEmpty(), currentPageNum)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(act)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAssistantPicker = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }
}
