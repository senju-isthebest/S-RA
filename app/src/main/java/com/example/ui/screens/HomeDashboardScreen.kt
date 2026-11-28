package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.AppLanguage
import com.example.core.SoraFormatters
import com.example.data.model.*
import com.example.data.sync.SyncState
import com.example.data.sync.SyncStatus
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraThemeMode
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun HomeDashboardScreen(
    profile: UserProfile?,
    books: List<Book>,
    tasks: List<PlannerTask>,
    questions: List<QuestionItem>,
    flashcards: List<Flashcard>,
    exams: List<ExamRecord>,
    focusSessions: List<FocusSession>,
    revisionQueue: List<SmartRevisionItem>,
    themeMode: SoraThemeMode,
    appLanguage: AppLanguage = AppLanguage.ENGLISH,
    syncState: SyncState = SyncState(),
    onOpenSyncCenter: () -> Unit = {},
    onOpenAccountSecurity: () -> Unit = {},
    onSelectLanguage: (AppLanguage) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onCycleTheme: () -> Unit,
    onSignOut: () -> Unit,
    onUploadBookClick: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onNavigateTab: (Int) -> Unit,
    onStartQuickQuiz: () -> Unit,
    onStartFocus: () -> Unit,
    onToggleTask: (PlannerTask) -> Unit,
    onUpdateDashboardSections: (List<String>) -> Unit
) {
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val activeBooks = remember(books) { books.filter { !it.archived } }
    val todayTasks = remember(tasks) {
        val endOfDay = System.currentTimeMillis() + 86_400_000L
        tasks.filter { !it.completed && it.dueDateMillis <= endOfDay }.sortedBy { it.dueDateMillis }
    }
    val upcomingExams = remember(tasks) {
        tasks.filter { !it.completed && it.taskType == "EXAM" }.sortedBy { it.dueDateMillis }
    }
    val chPrefix = stringResource(R.string.qb_chapter_short, 0).substringBefore("0")
    val weakTopics = remember(questions, chPrefix) {
        questions.filter { !it.hidden && it.incorrectCount > 0 && !it.mistakeResolved }
            .groupBy { it.topic.ifBlank { "$chPrefix${it.chapterNumber}" } }
            .entries
            .sortedByDescending { it.value.size }
            .take(4)
    }
    val dueCardsCount = remember(flashcards) {
        val now = System.currentTimeMillis()
        flashcards.count { it.nextReviewAtMillis <= now }
    }
    val overallMastery = remember(activeBooks, questions, exams) {
        val attempted = questions.filter { it.attempts > 0L }
        when {
            attempted.isNotEmpty() -> {
                val c = attempted.sumOf { it.correctCount }.toDouble()
                val a = attempted.sumOf { it.attempts }.toDouble().coerceAtLeast(1.0)
                ((c / a) * 100).toInt().coerceIn(0, 100)
            }
            activeBooks.isNotEmpty() -> activeBooks.map { it.masteryScore.toInt() }.average().toInt()
            else -> 0
        }
    }
    val weeklyMinutes = profile?.weeklyStudyMinutes?.toInt() ?: focusSessions.sumOf { it.durationMinutes.toInt() }
    val streakDays = profile?.studyStreakDays?.toInt() ?: 0
    val sections = profile?.dashboardSections?.ifEmpty {
        listOf("continue", "quick_actions", "revision_queue", "tasks", "exams", "weak_topics", "library")
    } ?: listOf("continue", "quick_actions", "revision_queue", "tasks", "exams", "weak_topics", "library")

    val scholarFallback = stringResource(R.string.home_scholar_fallback)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Welcome Hero Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_study),
                    contentDescription = stringResource(R.string.cd_study_banner),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xE612141E), Color(0xB3191D2D))
                            )
                        )
                )
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.home_welcome,
                                    profile?.displayName?.ifBlank { scholarFallback } ?: scholarFallback
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.home_subtitle_counts, activeBooks.size, dueCardsCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC9D0E8)
                            )
                        }
                        SoraLanguageSwitcher(
                            currentLanguage = appLanguage,
                            onLanguageSelected = onSelectLanguage,
                            onDarkBackdrop = true
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val syncTint = when (syncState.status) {
                            SyncStatus.SYNCED -> SoraSuccess
                            SyncStatus.SYNCING -> SoraSoftBlue
                            SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                            SyncStatus.OFFLINE_QUEUED, SyncStatus.CONFLICT_RESOLVED -> SoraWarning
                            SyncStatus.IDLE -> Color.White
                        }
                        Surface(
                            onClick = onOpenSyncCenter,
                            color = Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.heightIn(min = 36.dp).testTag("open_sync_center_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.CloudSync,
                                    contentDescription = stringResource(R.string.cd_cloud_sync),
                                    tint = syncTint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.sync_pill_label, syncStatusLabel(syncState)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onOpenAccountSecurity,
                                modifier = Modifier.testTag("open_account_security_button")
                            ) {
                                Icon(
                                    Icons.Rounded.VerifiedUser,
                                    contentDescription = stringResource(R.string.cd_account_security),
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = onOpenAiSettings,
                                modifier = Modifier.testTag("open_ai_settings_button")
                            ) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = stringResource(R.string.cd_ai_settings),
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = onCycleTheme,
                                modifier = Modifier.testTag("theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = when (themeMode) {
                                        SoraThemeMode.DARK -> Icons.Rounded.DarkMode
                                        SoraThemeMode.LIGHT -> Icons.Rounded.LightMode
                                        SoraThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                                    },
                                    contentDescription = stringResource(R.string.cd_theme_toggle),
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = { showCustomizeDialog = true },
                                modifier = Modifier.testTag("customize_dashboard_button")
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.cd_customize_dashboard), tint = Color.White)
                            }
                            IconButton(
                                onClick = onSignOut,
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Icon(Icons.Rounded.Logout, contentDescription = stringResource(R.string.cd_sign_out), tint = Color.White)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricPill(
                            icon = Icons.Rounded.LocalFireDepartment,
                            value = stringResource(R.string.home_streak_days, streakDays),
                            label = stringResource(R.string.home_metric_streak),
                            tint = SoraWarning,
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            icon = Icons.Rounded.Schedule,
                            value = stringResource(R.string.home_minutes_short, weeklyMinutes),
                            label = stringResource(R.string.home_metric_weekly_focus),
                            tint = SoraSoftBlue,
                            modifier = Modifier.weight(1f)
                        )
                        MetricPill(
                            icon = Icons.Rounded.Verified,
                            value = stringResource(R.string.home_percent_value, overallMastery),
                            label = stringResource(R.string.home_metric_mastery),
                            tint = SoraSuccess,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Dynamic customizable sections
        sections.forEach { sectionKey ->
            when (sectionKey) {
                "quick_actions" -> item(key = "sec_quick_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoraSectionHeader(title = stringResource(R.string.home_quick_actions))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { QuickActionChip(stringResource(R.string.qa_upload_book), Icons.Rounded.UploadFile, true, "qa_upload", onUploadBookClick) }
                            item { QuickActionChip(stringResource(R.string.qa_start_studying), Icons.Rounded.MenuBook, false, "qa_study") { activeBooks.firstOrNull()?.let(onOpenBook) ?: onNavigateTab(1) } }
                            item { QuickActionChip(stringResource(R.string.qa_start_quiz), Icons.Rounded.Quiz, false, "qa_quiz", onStartQuickQuiz) }
                            item { QuickActionChip(stringResource(R.string.qa_take_exam), Icons.Rounded.Assignment, false, "qa_exam") { onNavigateTab(2) } }
                            item { QuickActionChip(stringResource(R.string.qa_flashcards, dueCardsCount), Icons.Rounded.Style, false, "qa_flash") { activeBooks.firstOrNull()?.let(onOpenBook) ?: onNavigateTab(1) } }
                            item { QuickActionChip(stringResource(R.string.qa_ask_tutor), Icons.Rounded.Psychology, false, "qa_tutor") { activeBooks.firstOrNull()?.let(onOpenBook) ?: onNavigateTab(1) } }
                            item { QuickActionChip(stringResource(R.string.qa_focus_timer), Icons.Rounded.Timer, false, "qa_focus", onStartFocus) }
                            item { QuickActionChip(stringResource(R.string.qa_toolbox), Icons.Rounded.Calculate, false, "qa_tools") { onNavigateTab(4) } }
                        }
                    }
                }

                "continue" -> item(key = "sec_continue") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoraSectionHeader(
                            title = stringResource(R.string.home_continue_studying),
                            actionLabel = stringResource(R.string.home_library_action),
                            onAction = { onNavigateTab(1) }
                        )
                        val latestBook = activeBooks.firstOrNull()
                        if (latestBook == null) {
                            SoraEmptyState(
                                icon = Icons.Rounded.LibraryBooks,
                                title = stringResource(R.string.home_empty_title),
                                subtitle = stringResource(R.string.home_empty_subtitle),
                                actionLabel = stringResource(R.string.home_upload_textbook),
                                onAction = onUploadBookClick
                            )
                        } else {
                            SoraSurfaceCard(
                                onClick = { onOpenBook(latestBook) },
                                accentBorder = SoraViolet,
                                modifier = Modifier.fillMaxWidth().testTag("continue_book_card")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    SoraMasteryRing(progressPercent = latestBook.masteryScore.toInt())
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(latestBook.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            stringResource(
                                                R.string.home_page_progress,
                                                localizedSubjectName(latestBook.subjectName),
                                                latestBook.currentPage,
                                                latestBook.totalPages.coerceAtLeast(1)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        LinearProgressIndicator(
                                            progress = {
                                                (latestBook.currentPage.toFloat() / latestBook.totalPages.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = stringResource(R.string.cd_open_workspace),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                "revision_queue" -> item(key = "sec_revision_queue") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoraSectionHeader(
                            title = stringResource(R.string.home_revision_queue),
                            subtitle = stringResource(R.string.home_revision_subtitle)
                        )
                        if (revisionQueue.isEmpty()) {
                            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.home_revision_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            revisionQueue.take(3).forEach { item ->
                                val book = activeBooks.firstOrNull { it.id == item.bookId }
                                SoraSurfaceCard(
                                    onClick = { book?.let(onOpenBook) ?: onNavigateTab(2) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        SoraStatusBadge(
                                            text = SoraFormatters.ltrIsolate(stringResource(R.string.home_priority_rank, item.priorityRank)),
                                            color = when (item.priorityRank) {
                                                1 -> MaterialTheme.colorScheme.error
                                                2 -> SoraWarning
                                                else -> SoraSoftBlue
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(item.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(stringResource(R.string.home_minutes_short, item.estimatedMinutes), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                "tasks" -> item(key = "sec_tasks") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoraSectionHeader(
                            title = stringResource(R.string.home_tasks_today),
                            actionLabel = stringResource(R.string.home_planner_action),
                            onAction = { onNavigateTab(3) }
                        )
                        if (todayTasks.isEmpty()) {
                            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.home_tasks_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            todayTasks.take(4).forEach { task ->
                                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Checkbox(
                                            checked = task.completed,
                                            onCheckedChange = { onToggleTask(task) }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(task.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${localizedSubjectName(task.subjectName)} • ${stringResource(R.string.home_minutes_short, task.estimatedMinutes)} • ${localizedPriority(task.priority)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        SoraStatusBadge(localizedTaskType(task.taskType), SoraViolet)
                                    }
                                }
                            }
                        }
                    }
                }

                "exams" -> if (upcomingExams.isNotEmpty() || exams.isNotEmpty()) {
                    item(key = "sec_exams") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SoraSectionHeader(
                                title = stringResource(R.string.home_upcoming_exams),
                                actionLabel = stringResource(R.string.home_exams_action),
                                onAction = { onNavigateTab(2) }
                            )
                            upcomingExams.take(2).forEach { ex ->
                                val days = ((ex.dueDateMillis - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
                                SoraSurfaceCard(modifier = Modifier.fillMaxWidth(), accentBorder = SoraWarning) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(ex.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                SoraFormatters.formatDate(ex.dueDateMillis, appLanguage),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        SoraStatusBadge(stringResource(R.string.home_days_left, days), SoraWarning, Icons.Rounded.Event)
                                    }
                                }
                            }
                            exams.filter { it.status == "SUBMITTED" }.take(2).forEach { rec ->
                                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(rec.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                stringResource(R.string.home_exam_score_line, rec.correctCount, rec.incorrectCount),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            stringResource(R.string.home_percent_value, rec.percentage.toInt()),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = SoraSuccess
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                "weak_topics" -> if (weakTopics.isNotEmpty()) {
                    item(key = "sec_weak_topics") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SoraSectionHeader(title = stringResource(R.string.home_weak_topics))
                            SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                                SoraBarChart(
                                    entries = weakTopics.map { (topic, list) -> topic to (list.size * 10f).coerceAtMost(100f) },
                                    maxValue = 100f,
                                    barColor = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                "library" -> if (activeBooks.isNotEmpty()) {
                    item(key = "sec_library") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SoraSectionHeader(
                                title = stringResource(R.string.home_recent_books),
                                actionLabel = stringResource(R.string.home_all_count, activeBooks.size),
                                onAction = { onNavigateTab(1) }
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(activeBooks.take(5), key = { it.id }) { book ->
                                    SoraSurfaceCard(
                                        onClick = { onOpenBook(book) },
                                        modifier = Modifier.width(210.dp)
                                    ) {
                                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(4.dp))
                                        Text(localizedSubjectName(book.subjectName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.home_pages_short, book.totalPages), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(stringResource(R.string.home_mastery_badge, book.masteryScore), style = MaterialTheme.typography.labelSmall, color = SoraSuccess)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomizeDialog) {
        CustomizeDashboardDialog(
            current = sections,
            onDismiss = { showCustomizeDialog = false },
            onSave = {
                onUpdateDashboardSections(it)
                showCustomizeDialog = false
            }
        )
    }
}

@Composable
private fun MetricPill(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB8C0DA), maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    icon: ImageVector,
    highlighted: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    ElevatedAssistChip(
        onClick = onClick,
        modifier = Modifier.height(48.dp).testTag(tag),
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        label = { Text(label) },
        colors = if (highlighted) {
            AssistChipDefaults.elevatedAssistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.primary
            )
        } else {
            AssistChipDefaults.elevatedAssistChipColors()
        }
    )
}

@Composable
private fun CustomizeDashboardDialog(
    current: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val allLabels = mapOf(
        "continue" to stringResource(R.string.sec_continue),
        "quick_actions" to stringResource(R.string.sec_quick_actions),
        "revision_queue" to stringResource(R.string.sec_revision_queue),
        "tasks" to stringResource(R.string.sec_tasks),
        "exams" to stringResource(R.string.sec_exams),
        "weak_topics" to stringResource(R.string.sec_weak_topics),
        "library" to stringResource(R.string.sec_library)
    )
    var selected by remember { mutableStateOf(current.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_customize_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                allLabels.forEach { (key, label) ->
                    val checked = selected.contains(key)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) {
                                        (selected + key).distinct().toMutableList()
                                    } else {
                                        selected.filterNot { it == key }.toMutableList()
                                    }
                                }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(selected.ifEmpty { listOf("continue", "quick_actions") }) }) {
                Text(stringResource(R.string.home_save_layout))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
