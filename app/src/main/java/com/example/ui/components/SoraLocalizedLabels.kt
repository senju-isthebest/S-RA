package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.ai.AiProviderId
import com.example.data.ai.AiRoutingStrategy
import com.example.data.model.ExamPreset
import com.example.data.model.QuestionType
import com.example.data.model.TutorMode
import com.example.ui.theme.SoraThemeMode

@Composable
fun TutorMode.localizedLabel(): String = when (this) {
    TutorMode.EXPLAIN -> stringResource(R.string.tutor_mode_explain)
    TutorMode.SIMPLIFY -> stringResource(R.string.tutor_mode_simplify)
    TutorMode.TEACH -> stringResource(R.string.tutor_mode_teach)
    TutorMode.SOCRATIC -> stringResource(R.string.tutor_mode_socratic)
    TutorMode.HINT -> stringResource(R.string.tutor_mode_hint)
    TutorMode.SOLVE -> stringResource(R.string.tutor_mode_solve)
    TutorMode.COMPARE -> stringResource(R.string.tutor_mode_compare)
    TutorMode.EXAMPLES -> stringResource(R.string.tutor_mode_examples)
    TutorMode.EXAM_MODE -> stringResource(R.string.tutor_mode_exam)
    TutorMode.DEEP_LEARNING -> stringResource(R.string.tutor_mode_deep)
    TutorMode.ASK_TEXTBOOK -> stringResource(R.string.tutor_mode_ask_book)
}

@Composable
fun TutorMode.localizedDescription(): String = when (this) {
    TutorMode.EXPLAIN -> stringResource(R.string.tutor_mode_explain_desc)
    TutorMode.SIMPLIFY -> stringResource(R.string.tutor_mode_simplify_desc)
    TutorMode.TEACH -> stringResource(R.string.tutor_mode_teach_desc)
    TutorMode.SOCRATIC -> stringResource(R.string.tutor_mode_socratic_desc)
    TutorMode.HINT -> stringResource(R.string.tutor_mode_hint_desc)
    TutorMode.SOLVE -> stringResource(R.string.tutor_mode_solve_desc)
    TutorMode.COMPARE -> stringResource(R.string.tutor_mode_compare_desc)
    TutorMode.EXAMPLES -> stringResource(R.string.tutor_mode_examples_desc)
    TutorMode.EXAM_MODE -> stringResource(R.string.tutor_mode_exam_desc)
    TutorMode.DEEP_LEARNING -> stringResource(R.string.tutor_mode_deep_desc)
    TutorMode.ASK_TEXTBOOK -> stringResource(R.string.tutor_mode_ask_book_desc)
}

@Composable
fun localizedTutorModeByName(name: String): String {
    val mode = TutorMode.entries.firstOrNull { it.name == name }
    return mode?.localizedLabel() ?: name
}

@Composable
fun QuestionType.localizedLabel(): String = when (this) {
    QuestionType.MCQ -> stringResource(R.string.qtype_mcq)
    QuestionType.TRUE_FALSE -> stringResource(R.string.qtype_true_false)
    QuestionType.FILL_BLANK -> stringResource(R.string.qtype_fill_blank)
    QuestionType.MATCHING -> stringResource(R.string.qtype_matching)
    QuestionType.ORDERING -> stringResource(R.string.qtype_ordering)
    QuestionType.SHORT_ANSWER -> stringResource(R.string.qtype_short_answer)
    QuestionType.LONG_ANSWER -> stringResource(R.string.qtype_long_answer)
    QuestionType.ESSAY -> stringResource(R.string.qtype_essay)
    QuestionType.DEFINITION -> stringResource(R.string.qtype_definition)
    QuestionType.EXPLAIN_JUSTIFY -> stringResource(R.string.qtype_explain_justify)
    QuestionType.COMPARE_CONTRAST -> stringResource(R.string.qtype_compare_contrast)
    QuestionType.TERMINOLOGY -> stringResource(R.string.qtype_terminology)
    QuestionType.CALCULATION -> stringResource(R.string.qtype_calculation)
    QuestionType.WORD_PROBLEM -> stringResource(R.string.qtype_word_problem)
    QuestionType.CASE_STUDY -> stringResource(R.string.qtype_case_study)
    QuestionType.APPLICATION -> stringResource(R.string.qtype_application)
    QuestionType.COMPREHENSION -> stringResource(R.string.qtype_comprehension)
    QuestionType.DIAGRAM_ID -> stringResource(R.string.qtype_diagram_id)
    QuestionType.TABLE_INTERPRETATION -> stringResource(R.string.qtype_table_interp)
    QuestionType.FORMULA_BASED -> stringResource(R.string.qtype_formula_based)
}

@Composable
fun localizedQuestionTypeName(raw: String): String {
    val match = QuestionType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    return match?.localizedLabel() ?: raw
}

@Composable
fun ExamPreset.localizedLabel(): String = when (this) {
    ExamPreset.QUICK_QUIZ -> stringResource(R.string.preset_quick_quiz)
    ExamPreset.CHAPTER_TEST -> stringResource(R.string.preset_chapter_test)
    ExamPreset.UNIT_EXAM -> stringResource(R.string.preset_unit_exam)
    ExamPreset.COMPREHENSIVE -> stringResource(R.string.preset_comprehensive)
    ExamPreset.FINAL_SIMULATION -> stringResource(R.string.preset_final_sim)
    ExamPreset.DIFFICULT_ONLY -> stringResource(R.string.preset_difficult)
    ExamPreset.WEAK_TOPICS -> stringResource(R.string.preset_weak_topics)
    ExamPreset.MISTAKE_REVIEW -> stringResource(R.string.preset_mistake_review)
    ExamPreset.UNSEEN_ONLY -> stringResource(R.string.preset_unseen_only)
    ExamPreset.CUSTOM -> stringResource(R.string.preset_custom)
}

@Composable
fun ExamPreset.localizedDescription(): String = when (this) {
    ExamPreset.QUICK_QUIZ -> stringResource(R.string.preset_quick_quiz_desc)
    ExamPreset.CHAPTER_TEST -> stringResource(R.string.preset_chapter_test_desc)
    ExamPreset.UNIT_EXAM -> stringResource(R.string.preset_unit_exam_desc)
    ExamPreset.COMPREHENSIVE -> stringResource(R.string.preset_comprehensive_desc)
    ExamPreset.FINAL_SIMULATION -> stringResource(R.string.preset_final_sim_desc)
    ExamPreset.DIFFICULT_ONLY -> stringResource(R.string.preset_difficult_desc)
    ExamPreset.WEAK_TOPICS -> stringResource(R.string.preset_weak_topics_desc)
    ExamPreset.MISTAKE_REVIEW -> stringResource(R.string.preset_mistake_review_desc)
    ExamPreset.UNSEEN_ONLY -> stringResource(R.string.preset_unseen_only_desc)
    ExamPreset.CUSTOM -> stringResource(R.string.preset_custom_desc)
}

@Composable
fun AiRoutingStrategy.localizedLabel(): String = when (this) {
    AiRoutingStrategy.GEMINI_FIRST -> stringResource(R.string.ai_strategy_gemini_first)
    AiRoutingStrategy.OLLAMA_FIRST -> stringResource(R.string.ai_strategy_ollama_first)
    AiRoutingStrategy.OLLAMA_ONLY -> stringResource(R.string.ai_strategy_ollama_only)
    AiRoutingStrategy.GEMINI_ONLY -> stringResource(R.string.ai_strategy_gemini_only)
}

@Composable
fun AiProviderId.localizedLabel(): String = when (this) {
    AiProviderId.OLLAMA -> stringResource(R.string.ai_provider_ollama)
    AiProviderId.GEMINI -> stringResource(R.string.ai_provider_gemini)
}

@Composable
fun SoraThemeMode.localizedLabel(): String = when (this) {
    SoraThemeMode.DARK -> stringResource(R.string.theme_dark)
    SoraThemeMode.LIGHT -> stringResource(R.string.theme_light)
    SoraThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
fun localizedDifficulty(raw: String): String = when (raw.uppercase()) {
    "ALL" -> stringResource(R.string.label_all)
    "MIXED" -> stringResource(R.string.diff_mixed)
    "EASY" -> stringResource(R.string.diff_easy)
    "MEDIUM" -> stringResource(R.string.diff_medium)
    "HARD" -> stringResource(R.string.diff_hard)
    else -> raw
}

@Composable
fun localizedPriority(raw: String): String = when (raw.uppercase()) {
    "LOW" -> stringResource(R.string.prio_low)
    "MEDIUM" -> stringResource(R.string.prio_medium)
    "HIGH" -> stringResource(R.string.prio_high)
    "URGENT" -> stringResource(R.string.prio_urgent)
    else -> raw
}

@Composable
fun localizedTaskType(raw: String): String = when (raw.uppercase()) {
    "STUDY" -> stringResource(R.string.task_study)
    "HOMEWORK" -> stringResource(R.string.task_homework)
    "ASSIGNMENT" -> stringResource(R.string.task_assignment)
    "PROJECT" -> stringResource(R.string.task_project)
    "EXAM" -> stringResource(R.string.task_exam)
    "REVISION" -> stringResource(R.string.task_revision)
    else -> raw
}

@Composable
fun localizedRecurring(raw: String): String = when (raw.uppercase()) {
    "NONE" -> stringResource(R.string.repeat_none)
    "DAILY" -> stringResource(R.string.repeat_daily)
    "WEEKLY" -> stringResource(R.string.repeat_weekly)
    else -> raw
}

@Composable
fun localizedMasteryStatus(raw: String): String = when (raw.uppercase()) {
    "NOT_STARTED" -> stringResource(R.string.mastery_not_started)
    "LEARNING" -> stringResource(R.string.mastery_learning)
    "REVIEWING" -> stringResource(R.string.mastery_reviewing)
    "MASTERED" -> stringResource(R.string.mastery_mastered)
    else -> raw
}

@Composable
fun localizedExtractionStatus(raw: String): String = when (raw.uppercase()) {
    "COMPLETE" -> stringResource(R.string.status_complete)
    "PARTIAL" -> stringResource(R.string.status_partial)
    "FAILED" -> stringResource(R.string.status_failed)
    "PROCESSING" -> stringResource(R.string.status_processing)
    else -> raw
}

@Composable
fun localizedUnitCategoryName(index: Int, fallback: String): String = when (index) {
    0 -> stringResource(R.string.unit_cat_length)
    1 -> stringResource(R.string.unit_cat_mass)
    2 -> stringResource(R.string.unit_cat_energy)
    3 -> stringResource(R.string.unit_cat_pressure)
    4 -> stringResource(R.string.unit_cat_data)
    5 -> stringResource(R.string.unit_cat_temperature)
    else -> fallback
}

@Composable
fun localizedFormulaCategory(category: String): String = when (category) {
    "Physics" -> stringResource(R.string.subject_physics)
    "Chemistry" -> stringResource(R.string.subject_chemistry)
    "Mathematics" -> stringResource(R.string.subject_mathematics)
    "Biology" -> stringResource(R.string.subject_biology)
    "Economics" -> stringResource(R.string.subject_economics)
    else -> category
}

@Composable
fun localizedSubjectName(raw: String): String = when (raw) {
    "General" -> stringResource(R.string.label_general)
    "General Studies" -> stringResource(R.string.label_general_studies)
    else -> raw
}
