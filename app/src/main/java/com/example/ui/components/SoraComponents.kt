package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SoraSoftBlue
import com.example.ui.theme.SoraSuccess
import com.example.ui.theme.SoraViolet
import com.example.ui.theme.SoraWarning

@Composable
fun SoraSurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentBorder: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(
        1.dp,
        accentBorder?.copy(alpha = 0.45f) ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    )
    Card(
        modifier = modifier
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun SoraSectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun SoraMasteryRing(
    progressPercent: Int,
    size: Dp = 56.dp,
    strokeWidth: Dp = 5.dp,
    label: String? = null
) {
    val clamped = progressPercent.coerceIn(0, 100)
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val activeColor = when {
        clamped >= 80 -> SoraSuccess
        clamped >= 50 -> SoraSoftBlue
        else -> SoraViolet
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(SoraViolet, activeColor, SoraViolet)),
                startAngle = -90f,
                sweepAngle = (clamped / 100f) * 360f,
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Text(
            text = label ?: "$clamped%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun SoraBarChart(
    entries: List<Pair<String, Float>>,
    maxValue: Float = 100f,
    barColor: Color = SoraViolet,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val safeMax = maxValue.coerceAtLeast(1f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (label, value) ->
            val ratio = (value / safeMax).coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${value.toInt()}${if (maxValue == 100f) "%" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = barColor
                    )
                }
                val track = MaterialTheme.colorScheme.surfaceContainerHigh
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    drawRoundRect(
                        color = track,
                        size = this.size,
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                    if (ratio > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(barColor, SoraSoftBlue)),
                            size = Size(this.size.width * ratio, this.size.height),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SoraStatusBadge(
    text: String,
    color: Color,
    icon: ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
fun SoraEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SoraSurfaceCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp).testTag("empty_state_action")
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun SoraMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold
                )
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                line.startsWith("# ") -> Text(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = line.drop(2).replace("**", ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                line.isBlank() -> Spacer(modifier = Modifier.height(2.dp))
                else -> Text(
                    text = line.replace("**", ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun UploadTextbookDialog(
    subjects: List<String>,
    onDismiss: () -> Unit,
    onPickFile: (title: String, subject: String, semester: String, folder: String) -> Unit,
    onPickImage: (title: String, subject: String, semester: String, folder: String) -> Unit,
    onSubmitText: (title: String, subject: String, semester: String, folder: String, text: String) -> Unit
) {
    val defaultSubject = androidx.compose.ui.res.stringResource(com.example.R.string.label_general_studies)
    val defaultSemester = androidx.compose.ui.res.stringResource(com.example.R.string.label_fall_semester)
    val defaultFolder = androidx.compose.ui.res.stringResource(com.example.R.string.label_core_folder)
    val defaultNotesTitle = androidx.compose.ui.res.stringResource(com.example.R.string.upload_default_notes_title)

    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf(subjects.firstOrNull() ?: defaultSubject) }
    var semester by remember { mutableStateOf(defaultSemester) }
    var folder by remember { mutableStateOf(defaultFolder) }
    var modePaste by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    androidx.compose.ui.res.stringResource(com.example.R.string.upload_supported_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_title_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("upload_title_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_subject)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = semester,
                        onValueChange = { semester = it },
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_semester)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_folder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !modePaste,
                        onClick = { modePaste = false },
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_mode_file)) }
                    )
                    FilterChip(
                        selected = modePaste,
                        onClick = { modePaste = true },
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_mode_paste)) }
                    )
                }
                if (modePaste) {
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_paste_label)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("upload_paste_input")
                    )
                }
            }
        },
        confirmButton = {
            if (modePaste) {
                Button(
                    onClick = {
                        if (pastedText.isNotBlank()) {
                            onSubmitText(
                                title.ifBlank { defaultNotesTitle },
                                subject,
                                semester,
                                folder,
                                pastedText
                            )
                        }
                    },
                    enabled = pastedText.isNotBlank(),
                    modifier = Modifier.testTag("upload_paste_confirm")
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_analyze_text))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onPickImage(title, subject, semester, folder) },
                        modifier = Modifier.testTag("upload_pick_image")
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_image_ocr))
                    }
                    Button(
                        onClick = { onPickFile(title, subject, semester, folder) },
                        modifier = Modifier.testTag("upload_pick_file")
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(androidx.compose.ui.res.stringResource(com.example.R.string.upload_pick_file))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(com.example.R.string.action_cancel))
            }
        }
    )
}
