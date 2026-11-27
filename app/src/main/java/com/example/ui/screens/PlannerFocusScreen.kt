package com.example.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.SoraSettings
import com.example.data.model.*
import com.example.ui.FocusTimerState
import com.example.ui.SoraViewModel
import com.example.ui.components.*
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlannerFocusScreen(
    initialSubTab: Int = 0,
    books: List<Book>,
    tasks: List<PlannerTask>,
    focusSessions: List<FocusSession>,
    revisionQueue: List<SmartRevisionItem>,
    focusState: FocusTimerState,
    settings: SoraSettings,
    viewModel: SoraViewModel
) {
    var subTab by remember(initialSubTab) { mutableIntStateOf(initialSubTab) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("Study Planner") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("Focus Studio") })
        }

        if (subTab == 0) {
            StudyPlannerTab(
                books = books,
                tasks = tasks,
                revisionQueue = revisionQueue,
                viewModel = viewModel
            )
        } else {
            FocusStudioTab(
                books = books,
                focusSessions = focusSessions,
                focusState = focusState,
                settings = settings,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun StudyPlannerTab(
    books: List<Book>,
    tasks: List<PlannerTask>,
    revisionQueue: List<SmartRevisionItem>,
    viewModel: SoraViewModel
) {
    var timeWindow by remember { mutableStateOf("WEEK") } // TODAY, WEEK, MONTH, ALL
    var dailyCapacityMins by remember { mutableIntStateOf(90) }
    var showAddTaskDialog by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val filteredTasks = remember(tasks, timeWindow, now) {
        val cutoff = when (timeWindow) {
            "TODAY" -> now + 86_400_000L
            "WEEK" -> now + 7L * 86_400_000L
            "MONTH" -> now + 30L * 86_400_000L
            else -> Long.MAX_VALUE
        }
        tasks.filter { it.dueDateMillis <= cutoff }.sortedWith(compareBy<PlannerTask> { it.completed }.thenBy { it.dueDateMillis })
    }
    val examCountdowns = remember(tasks, now) {
        tasks.filter { !it.completed && it.taskType == "EXAM" && it.dueDateMillis >= now - 86_400_000L }
            .sortedBy { it.dueDateMillis }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("planner_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Academic Planner", style = MaterialTheme.typography.headlineSmall)
                    Text("${tasks.count { !it.completed }} pending • ${tasks.count { it.completed }} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { showAddTaskDialog = true }, modifier = Modifier.testTag("add_task_button")) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Task")
                }
            }
        }

        item {
            SoraSurfaceCard(accentBorder = SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Adaptive Plan & Missed Session Recovery", style = MaterialTheme.typography.titleSmall)
                    Text("Daily study capacity: $dailyCapacityMins min/day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = dailyCapacityMins.toFloat(),
                        onValueChange = { dailyCapacityMins = it.toInt() },
                        valueRange = 30f..300f
                    )
                    OutlinedButton(
                        onClick = { viewModel.reorganizeMissedAndAdaptivePlan(dailyCapacityMins) },
                        modifier = Modifier.fillMaxWidth().testTag("reorganize_plan_button")
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reorganize Missed & Balance Queue")
                    }
                }
            }
        }

        if (examCountdowns.isNotEmpty()) {
            item {
                SoraSectionHeader(title = "Exam Countdowns")
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(examCountdowns, key = { it.id }) { ex ->
                        val daysLeft = ((ex.dueDateMillis - now) / 86_400_000L).coerceAtLeast(0L)
                        SoraSurfaceCard(accentBorder = SoraWarning, modifier = Modifier.width(210.dp)) {
                            SoraStatusBadge("${daysLeft}d left", SoraWarning, Icons.Rounded.Event)
                            Spacer(Modifier.height(6.dp))
                            Text(ex.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(ex.subjectName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("TODAY" to "Daily", "WEEK" to "Weekly", "MONTH" to "Monthly", "ALL" to "All")) { (k, label) ->
                    FilterChip(selected = timeWindow == k, onClick = { timeWindow = k }, label = { Text(label) })
                }
            }
        }

        if (revisionQueue.isNotEmpty()) {
            item {
                val top = revisionQueue.first()
                SoraSurfaceCard(accentBorder = SoraSoftBlue, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Suggested Revision: ${top.title}", style = MaterialTheme.typography.titleSmall)
                            Text(top.reason, style = MaterialTheme.typography.labelSmall, color = SoraSoftBlue)
                        }
                        TextButton(onClick = {
                            viewModel.addPlannerTask(
                                title = top.title,
                                taskType = "REVISION",
                                subjectName = books.firstOrNull { it.id == top.bookId }?.subjectName ?: "General",
                                dueDateMillis = now + 7_200_000L,
                                estimatedMinutes = top.estimatedMinutes.toLong(),
                                priority = "HIGH",
                                recurring = "NONE",
                                bookId = top.bookId,
                                chapterNumber = top.chapterNumber
                            )
                        }) { Text("Schedule") }
                    }
                }
            }
        }

        if (filteredTasks.isEmpty()) {
            item {
                SoraEmptyState(
                    Icons.Rounded.EventAvailable,
                    "No Tasks in This View",
                    "Add study blocks, homework, projects, or exam dates to build your personalized study schedule."
                )
            }
        } else {
            items(filteredTasks, key = { it.id }) { task ->
                val dateFmt = remember(task.dueDateMillis) {
                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(task.dueDateMillis))
                }
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(
                            checked = task.completed,
                            onCheckedChange = { viewModel.toggleTaskCompleted(task) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.titleSmall)
                            Text("${task.subjectName} • $dateFmt • ${task.estimatedMinutes}m • ${task.priority}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SoraStatusBadge(task.taskType, if (task.taskType == "EXAM") SoraWarning else SoraViolet)
                        IconButton(onClick = { viewModel.deletePlannerTask(task.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showAddTaskDialog) {
        var title by remember { mutableStateOf("") }
        var taskType by remember { mutableStateOf("STUDY") }
        var subject by remember { mutableStateOf(books.firstOrNull()?.subjectName ?: "General") }
        var daysAhead by remember { mutableIntStateOf(1) }
        var estMins by remember { mutableIntStateOf(45) }
        var priority by remember { mutableStateOf("MEDIUM") }
        var recurring by remember { mutableStateOf("NONE") }

        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { Text("New Study Task / Exam") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject") }, singleLine = true)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("STUDY", "HOMEWORK", "ASSIGNMENT", "PROJECT", "EXAM", "REVISION")) { t ->
                            FilterChip(selected = taskType == t, onClick = { taskType = t }, label = { Text(t) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("LOW", "MEDIUM", "HIGH", "URGENT")) { p ->
                            FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) })
                        }
                    }
                    Text("Due in $daysAhead day(s) • Est. $estMins min", style = MaterialTheme.typography.labelMedium)
                    Slider(value = daysAhead.toFloat(), onValueChange = { daysAhead = it.toInt() }, valueRange = 0f..30f)
                    Slider(value = estMins.toFloat(), onValueChange = { estMins = it.toInt() }, valueRange = 15f..180f)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("NONE", "DAILY", "WEEKLY")) { r ->
                            FilterChip(selected = recurring == r, onClick = { recurring = r }, label = { Text("Repeat: $r") })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addPlannerTask(
                        title = title,
                        taskType = taskType,
                        subjectName = subject,
                        dueDateMillis = System.currentTimeMillis() + daysAhead * 86_400_000L,
                        estimatedMinutes = estMins.toLong(),
                        priority = priority,
                        recurring = recurring
                    )
                    showAddTaskDialog = false
                }, enabled = title.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddTaskDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FocusStudioTab(
    books: List<Book>,
    focusSessions: List<FocusSession>,
    focusState: FocusTimerState,
    settings: SoraSettings,
    viewModel: SoraViewModel
) {
    var goalText by remember { mutableStateOf(focusState.goalText) }
    var sessionNotes by remember { mutableStateOf("") }
    var ambientMode by remember { mutableStateOf(false) }
    var showTimerSettings by remember { mutableStateOf(false) }

    val subjectOptions = remember(books) {
        (listOf("General") + books.map { it.subjectName }).distinct()
    }

    val displaySeconds = if (focusState.mode == "STOPWATCH") focusState.elapsedSeconds else focusState.remainingSeconds
    val mins = displaySeconds / 60
    val secs = displaySeconds % 60
    val progress = when {
        focusState.mode == "STOPWATCH" -> ((focusState.elapsedSeconds % 60) / 60f)
        focusState.targetSeconds > 0 -> 1f - (focusState.remainingSeconds.toFloat() / focusState.targetSeconds.toFloat())
        else -> 0f
    }.coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = focusState.mode == "POMODORO",
                        onClick = { viewModel.configureFocusSession("POMODORO", settings.pomodoroWorkMinutes, focusState.subjectName, focusState.bookId, focusState.chapterNumber, goalText) },
                        label = { Text("Focus ${settings.pomodoroWorkMinutes}m") }
                    )
                }
                item {
                    FilterChip(
                        selected = focusState.mode == "SHORT_BREAK",
                        onClick = { viewModel.configureFocusSession("SHORT_BREAK", settings.pomodoroShortBreakMinutes, focusState.subjectName, focusState.bookId, focusState.chapterNumber, "Short Break") },
                        label = { Text("Break ${settings.pomodoroShortBreakMinutes}m") }
                    )
                }
                item {
                    FilterChip(
                        selected = focusState.mode == "LONG_BREAK",
                        onClick = { viewModel.configureFocusSession("LONG_BREAK", settings.pomodoroLongBreakMinutes, focusState.subjectName, focusState.bookId, focusState.chapterNumber, "Long Break") },
                        label = { Text("Long ${settings.pomodoroLongBreakMinutes}m") }
                    )
                }
                item {
                    FilterChip(
                        selected = focusState.mode == "CUSTOM",
                        onClick = { viewModel.configureFocusSession("CUSTOM", 45, focusState.subjectName, focusState.bookId, focusState.chapterNumber, goalText) },
                        label = { Text("Deep 45m") }
                    )
                }
                item {
                    FilterChip(
                        selected = focusState.mode == "STOPWATCH",
                        onClick = { viewModel.configureFocusSession("STOPWATCH", 0, focusState.subjectName, focusState.bookId, focusState.chapterNumber, goalText) },
                        label = { Text("Stopwatch") }
                    )
                }
            }
        }

        item {
            SoraSurfaceCard(accentBorder = if (focusState.running) SoraSuccess else SoraViolet, modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SoraStatusBadge(focusState.subjectName, SoraSoftBlue)
                        Row {
                            IconButton(onClick = { ambientMode = !ambientMode }) {
                                Icon(
                                    if (ambientMode) Icons.Rounded.Spa else Icons.Rounded.NotificationsOff,
                                    contentDescription = "Toggle ambient calm mode",
                                    tint = if (ambientMode) SoraSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showTimerSettings = true }) {
                                Icon(Icons.Rounded.Settings, contentDescription = "Configure Pomodoro")
                            }
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                        val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokePx = 12.dp.toPx()
                            drawArc(
                                color = trackColor,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                            drawArc(
                                brush = Brush.sweepGradient(listOf(SoraViolet, SoraSoftBlue, SoraSuccess, SoraViolet)),
                                startAngle = -90f,
                                sweepAngle = progress * 360f,
                                useCenter = false,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "%02d:%02d".format(mins, secs),
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (ambientMode) "Calm Deep Focus" else focusState.mode,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(subjectOptions) { subj ->
                            FilterChip(
                                selected = focusState.subjectName == subj,
                                onClick = { viewModel.configureFocusSession(focusState.mode, focusState.targetSeconds / 60, subj, focusState.bookId, focusState.chapterNumber, goalText) },
                                label = { Text(subj) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = { Text("Session Goal (e.g. Finish Ch. 3 exercises)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sessionNotes,
                        onValueChange = { sessionNotes = it },
                        label = { Text("Session Reflection Notes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.toggleFocusTimer() },
                            modifier = Modifier.weight(1f).testTag("focus_start_pause_button")
                        ) {
                            Icon(if (focusState.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (focusState.running) "Pause" else "Start")
                        }
                        OutlinedButton(
                            onClick = { viewModel.completeFocusSession(sessionNotes) },
                            enabled = focusState.elapsedSeconds > 0,
                            modifier = Modifier.weight(1f).testTag("focus_finish_button")
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Log Session")
                        }
                    }
                }
            }
        }

        if (focusSessions.isNotEmpty()) {
            val bySubject = focusSessions.groupBy { it.subjectName }
                .map { (subj, list) -> subj to list.sumOf { it.durationMinutes }.toFloat() }
            val maxVal = bySubject.maxOfOrNull { it.second }?.coerceAtLeast(30f) ?: 60f
            item {
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Focus Minutes by Subject", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SoraBarChart(entries = bySubject, maxValue = maxVal, barColor = SoraSoftBlue)
                }
            }
            item { SoraSectionHeader(title = "Recent Focus Sessions") }
            items(focusSessions.take(8), key = { it.id }) { s ->
                SoraSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${s.subjectName} (${s.mode})", style = MaterialTheme.typography.titleSmall)
                            if (s.goalText.isNotBlank() || s.notes.isNotBlank()) {
                                Text(s.goalText.ifBlank { s.notes }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SoraStatusBadge("${s.durationMinutes}m", SoraSuccess)
                    }
                }
            }
        }
    }

    if (showTimerSettings) {
        var work by remember { mutableIntStateOf(settings.pomodoroWorkMinutes) }
        var sb by remember { mutableIntStateOf(settings.pomodoroShortBreakMinutes) }
        var lb by remember { mutableIntStateOf(settings.pomodoroLongBreakMinutes) }
        AlertDialog(
            onDismissRequest = { showTimerSettings = false },
            title = { Text("Pomodoro Intervals") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Focus: $work min")
                    Slider(value = work.toFloat(), onValueChange = { work = it.toInt() }, valueRange = 15f..60f)
                    Text("Short Break: $sb min")
                    Slider(value = sb.toFloat(), onValueChange = { sb = it.toInt() }, valueRange = 3f..15f)
                    Text("Long Break: $lb min")
                    Slider(value = lb.toFloat(), onValueChange = { lb = it.toInt() }, valueRange = 10f..30f)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updatePomodoroSettings(work, sb, lb)
                    showTimerSettings = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showTimerSettings = false }) { Text("Cancel") } }
        )
    }
}
