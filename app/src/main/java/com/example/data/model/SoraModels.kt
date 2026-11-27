package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val studyStreakDays: Long = 0L,
    val weeklyStudyMinutes: Long = 0L,
    val dailyGoalMinutes: Long = 60L,
    val lastStudyDateKey: String = "",
    val dashboardSections: List<String> = listOf(
        "continue", "quick_actions", "revision_queue", "tasks", "exams", "weak_topics", "library"
    ),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Subject(
    @DocumentId val id: String = "",
    val name: String = "",
    val semester: String = "Fall 2026",
    val folder: String = "General",
    val colorHex: String = "#8B7AFF",
    val targetGrade: String = "A",
    val archived: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "name" to name.take(80),
        "semester" to semester.take(50),
        "folder" to folder.take(50),
        "colorHex" to colorHex.take(16),
        "targetGrade" to targetGrade.take(20),
        "archived" to archived,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class Book(
    @DocumentId val id: String = "",
    val title: String = "",
    val author: String = "",
    val subjectId: String = "",
    val subjectName: String = "General",
    val semester: String = "Fall 2026",
    val folder: String = "Core",
    val sourceType: String = "PDF",
    val fileName: String = "",
    val storagePath: String = "",
    val sourceSizeBytes: Long = 0L,
    val sourceSha256: String = "",
    val syncStatus: String = "LOCAL",
    val cloudPageCount: Long = 0L,
    val pagesSyncStatus: String = "LOCAL",
    val localBookId: String = "",
    val totalPages: Long = 0L,
    val processedPages: Long = 0L,
    val currentPage: Long = 1L,
    val failedPages: List<Long> = emptyList(),
    val extractionStatus: String = "PROCESSING",
    val extractionError: String = "",
    val overview: String = "",
    val formulaSheet: String = "",
    val archived: Boolean = false,
    val masteryScore: Long = 0L,
    val bookmarkedPages: List<Long> = emptyList(),
    val highlightsJson: String = "{}",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "title" to title.take(160),
        "author" to author.take(120),
        "subjectId" to subjectId.take(128),
        "subjectName" to subjectName.take(80),
        "semester" to semester.take(50),
        "folder" to folder.take(50),
        "sourceType" to sourceType.take(20),
        "fileName" to fileName.take(200),
        "storagePath" to storagePath.take(256),
        "sourceSizeBytes" to sourceSizeBytes.coerceIn(0L, 83886080L),
        "sourceSha256" to sourceSha256.take(64),
        "syncStatus" to syncStatus,
        "cloudPageCount" to cloudPageCount.coerceIn(0L, 5000L),
        "pagesSyncStatus" to pagesSyncStatus,
        "localBookId" to localBookId.take(128),
        "totalPages" to totalPages.coerceIn(0L, 5000L),
        "processedPages" to processedPages.coerceIn(0L, 5000L),
        "currentPage" to currentPage.coerceIn(1L, 5000L),
        "failedPages" to failedPages.take(500),
        "extractionStatus" to extractionStatus,
        "extractionError" to extractionError.take(500),
        "overview" to overview.take(4000),
        "formulaSheet" to formulaSheet.take(10000),
        "archived" to archived,
        "masteryScore" to masteryScore.coerceIn(0L, 100L),
        "bookmarkedPages" to bookmarkedPages.take(500),
        "highlightsJson" to highlightsJson.take(20000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class Chapter(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val chapterNumber: Long = 1L,
    val title: String = "",
    val startPage: Long = 1L,
    val endPage: Long = 1L,
    val estimatedStudyMinutes: Long = 25L,
    val masteryScore: Long = 0L,
    val masteryStatus: String = "NOT_STARTED",
    val userGoal: String = "",
    val quickSummary: String = "",
    val detailedSummary: String = "",
    val bulletSummary: String = "",
    val examRevisionSummary: String = "",
    val learningObjectives: List<String> = emptyList(),
    val mainTopics: List<String> = emptyList(),
    val prerequisites: List<String> = emptyList(),
    val keyConcepts: List<String> = emptyList(),
    val importantDefinitions: List<String> = emptyList(),
    val importantFacts: List<String> = emptyList(),
    val importantDates: List<String> = emptyList(),
    val formulas: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val misconceptions: List<String> = emptyList(),
    val comparisons: List<String> = emptyList(),
    val difficultSections: List<String> = emptyList(),
    val relatedChapters: List<String> = emptyList(),
    val conceptMapJson: String = "",
    val lastStudiedAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "title" to title.take(200),
        "startPage" to startPage.coerceIn(1L, 5000L),
        "endPage" to endPage.coerceIn(1L, 5000L),
        "estimatedStudyMinutes" to estimatedStudyMinutes.coerceIn(1L, 1000L),
        "masteryScore" to masteryScore.coerceIn(0L, 100L),
        "masteryStatus" to masteryStatus,
        "userGoal" to userGoal.take(500),
        "quickSummary" to quickSummary.take(4000),
        "detailedSummary" to detailedSummary.take(12000),
        "bulletSummary" to bulletSummary.take(8000),
        "examRevisionSummary" to examRevisionSummary.take(8000),
        "learningObjectives" to learningObjectives.take(30),
        "mainTopics" to mainTopics.take(40),
        "prerequisites" to prerequisites.take(30),
        "keyConcepts" to keyConcepts.take(50),
        "importantDefinitions" to importantDefinitions.take(50),
        "importantFacts" to importantFacts.take(50),
        "importantDates" to importantDates.take(30),
        "formulas" to formulas.take(50),
        "examples" to examples.take(30),
        "misconceptions" to misconceptions.take(30),
        "comparisons" to comparisons.take(30),
        "difficultSections" to difficultSections.take(30),
        "relatedChapters" to relatedChapters.take(20),
        "conceptMapJson" to conceptMapJson.take(25000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class GlossaryEntry(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val chapterNumber: Long = 1L,
    val term: String = "",
    val definition: String = "",
    val simplifiedExplanation: String = "",
    val example: String = "",
    val pageReference: Long = 1L,
    val relatedTerms: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "term" to term.take(120),
        "definition" to definition.take(1500),
        "simplifiedExplanation" to simplifiedExplanation.take(1000),
        "example" to example.take(1000),
        "pageReference" to pageReference.coerceIn(0L, 5000L),
        "relatedTerms" to relatedTerms.take(20),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class QuestionItem(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val bookTitle: String = "",
    val chapterNumber: Long = 1L,
    val section: String = "",
    val topic: String = "",
    val sourcePage: Long = 1L,
    val difficulty: String = "MEDIUM",
    val questionType: String = "MCQ",
    val questionText: String = "",
    val options: List<String> = emptyList(),
    val correctAnswer: String = "",
    val explanation: String = "",
    val learningObjective: String = "",
    val tags: List<String> = emptyList(),
    val marks: Long = 2L,
    val estimatedSeconds: Long = 60L,
    val bookmarked: Boolean = false,
    val hidden: Boolean = false,
    val reported: Boolean = false,
    val reportReason: String = "",
    val personalNote: String = "",
    val collectionName: String = "",
    val attempts: Long = 0L,
    val correctCount: Long = 0L,
    val incorrectCount: Long = 0L,
    val lastAnsweredCorrect: Boolean? = null,
    val mistakeResolved: Boolean = false,
    val aiGenerated: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "bookTitle" to bookTitle.take(160),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "section" to section.take(160),
        "topic" to topic.take(160),
        "sourcePage" to sourcePage.coerceIn(0L, 5000L),
        "difficulty" to difficulty,
        "questionType" to questionType.take(40),
        "questionText" to questionText.take(2500),
        "options" to options.take(10),
        "correctAnswer" to correctAnswer.take(2000),
        "explanation" to explanation.take(3000),
        "learningObjective" to learningObjective.take(400),
        "tags" to tags.take(15),
        "marks" to marks.coerceIn(1L, 100L),
        "estimatedSeconds" to estimatedSeconds.coerceIn(5L, 3600L),
        "bookmarked" to bookmarked,
        "hidden" to hidden,
        "reported" to reported,
        "reportReason" to reportReason.take(400),
        "personalNote" to personalNote.take(2000),
        "collectionName" to collectionName.take(80),
        "attempts" to attempts.coerceIn(0L, 10000L),
        "correctCount" to correctCount.coerceIn(0L, 10000L),
        "incorrectCount" to incorrectCount.coerceIn(0L, 10000L),
        "lastAnsweredCorrect" to lastAnsweredCorrect,
        "mistakeResolved" to mistakeResolved,
        "aiGenerated" to aiGenerated,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    ).filterValues { it != null }.mapValues { it.value!! }
}

@Keep
@IgnoreExtraProperties
data class Flashcard(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val chapterNumber: Long = 1L,
    val deckName: String = "General",
    val format: String = "TERM_DEF",
    val front: String = "",
    val back: String = "",
    val hint: String = "",
    val pageReference: Long = 1L,
    val bookmarked: Boolean = false,
    val easeFactor: Double = 2.5,
    val intervalDays: Long = 0L,
    val repetitions: Long = 0L,
    val nextReviewAtMillis: Long = System.currentTimeMillis(),
    val lastRating: String = "UNSEEN",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "deckName" to deckName.take(100),
        "format" to format.take(40),
        "front" to front.take(1500),
        "back" to back.take(2500),
        "hint" to hint.take(500),
        "pageReference" to pageReference.coerceIn(0L, 5000L),
        "bookmarked" to bookmarked,
        "easeFactor" to easeFactor.coerceIn(1.3, 5.0),
        "intervalDays" to intervalDays.coerceIn(0L, 3650L),
        "repetitions" to repetitions.coerceIn(0L, 1000L),
        "nextReviewAtMillis" to nextReviewAtMillis.coerceAtLeast(0L),
        "lastRating" to lastRating,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class ExamRecord(
    @DocumentId val id: String = "",
    val title: String = "",
    val preset: String = "CUSTOM",
    val bookIds: List<String> = emptyList(),
    val questionIds: List<String> = emptyList(),
    val durationMinutes: Long = 20L,
    val totalMarks: Long = 10L,
    val allowRevisit: Boolean = true,
    val showExplanationEach: Boolean = false,
    val status: String = "IN_PROGRESS",
    val answersJson: String = "{}",
    val flaggedQuestionIds: List<String> = emptyList(),
    val score: Long = 0L,
    val percentage: Double = 0.0,
    val correctCount: Long = 0L,
    val incorrectCount: Long = 0L,
    val unansweredCount: Long = 0L,
    val timeSpentSeconds: Long = 0L,
    val analyticsJson: String = "{}",
    val recommendedRevision: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "title" to title.take(160),
        "preset" to preset.take(50),
        "bookIds" to bookIds.take(20),
        "questionIds" to questionIds.take(200),
        "durationMinutes" to durationMinutes.coerceIn(1L, 300L),
        "totalMarks" to totalMarks.coerceIn(1L, 1000L),
        "allowRevisit" to allowRevisit,
        "showExplanationEach" to showExplanationEach,
        "status" to status,
        "answersJson" to answersJson.take(50000),
        "flaggedQuestionIds" to flaggedQuestionIds.take(200),
        "score" to score.coerceIn(0L, 1000L),
        "percentage" to percentage.coerceIn(0.0, 100.0),
        "correctCount" to correctCount.coerceIn(0L, 200L),
        "incorrectCount" to incorrectCount.coerceIn(0L, 200L),
        "unansweredCount" to unansweredCount.coerceIn(0L, 200L),
        "timeSpentSeconds" to timeSpentSeconds.coerceIn(0L, 36000L),
        "analyticsJson" to analyticsJson.take(20000),
        "recommendedRevision" to recommendedRevision.take(3000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class StudyNote(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val subjectName: String = "General",
    val chapterNumber: Long = 0L,
    val linkedPage: Long = 0L,
    val linkedConcept: String = "",
    val linkedQuestionId: String = "",
    val title: String = "",
    val contentMarkdown: String = "",
    val tags: List<String> = emptyList(),
    val bookmarked: Boolean = false,
    val quickNote: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "subjectName" to subjectName.take(80),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "linkedPage" to linkedPage.coerceIn(0L, 5000L),
        "linkedConcept" to linkedConcept.take(160),
        "linkedQuestionId" to linkedQuestionId.take(128),
        "title" to title.take(160),
        "contentMarkdown" to contentMarkdown.take(30000),
        "tags" to tags.take(15),
        "bookmarked" to bookmarked,
        "quickNote" to quickNote,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class PlannerTask(
    @DocumentId val id: String = "",
    val title: String = "",
    val taskType: String = "STUDY",
    val subjectName: String = "General",
    val bookId: String = "",
    val chapterNumber: Long = 0L,
    val dueDateMillis: Long = System.currentTimeMillis(),
    val estimatedMinutes: Long = 45L,
    val priority: String = "MEDIUM",
    val completed: Boolean = false,
    val recurring: String = "NONE",
    val notes: String = "",
    val confidenceRating: Long = 3L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "title" to title.take(160),
        "taskType" to taskType,
        "subjectName" to subjectName.take(80),
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "dueDateMillis" to dueDateMillis.coerceAtLeast(0L),
        "estimatedMinutes" to estimatedMinutes.coerceIn(1L, 600L),
        "priority" to priority,
        "completed" to completed,
        "recurring" to recurring,
        "notes" to notes.take(1000),
        "confidenceRating" to confidenceRating.coerceIn(1L, 5L),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class FocusSession(
    @DocumentId val id: String = "",
    val subjectName: String = "General",
    val bookId: String = "",
    val chapterNumber: Long = 0L,
    val mode: String = "POMODORO",
    val durationMinutes: Long = 25L,
    val goalText: String = "",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "subjectName" to subjectName.take(80),
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "mode" to mode,
        "durationMinutes" to durationMinutes.coerceIn(0L, 600L),
        "goalText" to goalText.take(200),
        "notes" to notes.take(1000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class TutorMessage(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val chapterNumber: Long = 0L,
    val pageNumber: Long = 1L,
    val mode: String = "EXPLAIN",
    val role: String = "USER",
    val text: String = "",
    val citations: List<String> = emptyList(),
    val distinctionNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "chapterNumber" to chapterNumber.coerceIn(0L, 500L),
        "pageNumber" to pageNumber.coerceIn(0L, 5000L),
        "mode" to mode.take(40),
        "role" to role,
        "text" to text.take(15000),
        "citations" to citations.take(20),
        "distinctionNote" to distinctionNote.take(500),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class CitationEntry(
    @DocumentId val id: String = "",
    val bookId: String = "",
    val style: String = "APA",
    val authors: String = "",
    val title: String = "",
    val year: String = "",
    val publisher: String = "",
    val pages: String = "",
    val formattedText: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "bookId" to bookId.take(128),
        "style" to style,
        "authors" to authors.take(200),
        "title" to title.take(240),
        "year" to year.take(20),
        "publisher" to publisher.take(160),
        "pages" to pages.take(40),
        "formattedText" to formattedText.take(1000),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

enum class TutorMode(val label: String, val description: String) {
    EXPLAIN("Explain", "Clear explanation anchored to this chapter"),
    SIMPLIFY("Simplify", "Plain-language breakdown of difficult concepts"),
    TEACH("Teach Step-by-Step", "Guided lesson with comprehension checks"),
    SOCRATIC("Socratic", "Guides you with questions before giving answers"),
    HINT("Hint", "Progressive clues without spoiling the solution"),
    SOLVE("Solve", "Step-by-step worked solution"),
    COMPARE("Compare", "Side-by-side comparison of related concepts"),
    EXAMPLES("Examples", "Additional worked examples grounded in the text"),
    EXAM_MODE("Examiner", "Evaluates your response like an examiner"),
    DEEP_LEARNING("Deep Learning", "Deeper conceptual links across chapters"),
    ASK_TEXTBOOK("Ask Textbook", "Answers strictly from your uploaded pages with citations")
}

enum class QuestionType(val label: String) {
    MCQ("Multiple Choice"),
    TRUE_FALSE("True / False"),
    FILL_BLANK("Fill in the Blank"),
    MATCHING("Matching"),
    ORDERING("Ordering"),
    SHORT_ANSWER("Short Answer"),
    LONG_ANSWER("Long Answer"),
    ESSAY("Essay"),
    DEFINITION("Definition"),
    EXPLAIN_JUSTIFY("Explain & Justify"),
    COMPARE_CONTRAST("Compare & Contrast"),
    TERMINOLOGY("Scientific Terminology"),
    CALCULATION("Calculation"),
    WORD_PROBLEM("Word Problem"),
    CASE_STUDY("Case Study"),
    APPLICATION("Application"),
    COMPREHENSION("Reading Comprehension"),
    DIAGRAM_ID("Diagram Identification"),
    TABLE_INTERPRETATION("Table Interpretation"),
    FORMULA_BASED("Formula-Based")
}

enum class ExamPreset(
    val label: String,
    val questionCount: Int,
    val durationMinutes: Int,
    val description: String
) {
    QUICK_QUIZ("Quick Quiz", 5, 10, "Rapid 5-question comprehension check"),
    CHAPTER_TEST("Chapter Test", 10, 20, "Focused assessment on selected chapter"),
    UNIT_EXAM("Unit Exam", 15, 30, "Multi-chapter unit evaluation"),
    COMPREHENSIVE("Comprehensive Exam", 25, 45, "Full-textbook coverage across topics"),
    FINAL_SIMULATION("Final Exam Simulation", 30, 60, "Timed high-stakes simulation, no revisit"),
    DIFFICULT_ONLY("Difficult Only", 10, 25, "Hard-difficulty analytical & calculation items"),
    WEAK_TOPICS("Weak Topics Exam", 10, 20, "Targets chapters & topics with lowest accuracy"),
    MISTAKE_REVIEW("Mistake Review Exam", 10, 20, "Re-tests questions previously answered incorrectly"),
    UNSEEN_ONLY("Unseen Questions", 10, 20, "Fresh questions you have never attempted"),
    CUSTOM("Custom Exam", 12, 25, "Fully customized exam configuration")
}

data class BookPageContent(
    val pageNumber: Int,
    val text: String,
    val extractionMethod: String,
    val error: String? = null
)

data class ConceptNode(
    val id: String,
    val label: String,
    val summary: String,
    val pageRef: Int = 1,
    val children: List<ConceptNode> = emptyList()
)

data class SmartRevisionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val reason: String,
    val priorityRank: Int,
    val bookId: String,
    val chapterNumber: Long,
    val estimatedMinutes: Int,
    val itemType: String // "MISTAKE", "FLASHCARD", "CHAPTER", "EXAM_PREP"
)
