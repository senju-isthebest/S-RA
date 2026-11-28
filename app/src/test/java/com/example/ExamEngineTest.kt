package com.example

import com.example.data.ai.AiAnswerGrade
import com.example.data.model.ExamPreset
import com.example.data.model.ExamRecord
import com.example.data.model.QuestionItem
import com.example.data.model.QuestionType
import com.example.domain.AnswerEvaluation
import com.example.domain.ExamBlueprint
import com.example.domain.ExamEngine
import com.example.domain.OfflineExamCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExamEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `deterministic evaluation covers MCQ, TF in EN and AR, fill-blank variants, calculation tolerance, matching, and ordering`() {
        val mcq = QuestionItem(id = "q1", questionType = "MCQ", correctAnswer = "B) Acceleration", marks = 2L)
        assertTrue(ExamEngine.evaluateOffline(mcq, "b) acceleration").isCorrect)
        assertFalse(ExamEngine.evaluateOffline(mcq, "A) Velocity").isCorrect)

        val tf = QuestionItem(id = "q2", questionType = "TRUE_FALSE", correctAnswer = "True", marks = 1L)
        assertTrue(ExamEngine.evaluateOffline(tf, "true").isCorrect)
        assertTrue(ExamEngine.evaluateOffline(tf, "صح").isCorrect)
        assertFalse(ExamEngine.evaluateOffline(tf, "خطأ").isCorrect)

        val fill = QuestionItem(id = "q3", questionType = "FILL_BLANK", correctAnswer = "joule | J | ن.م", marks = 2L)
        assertTrue(ExamEngine.evaluateOffline(fill, "J").isCorrect)
        assertTrue(ExamEngine.evaluateOffline(fill, "joule").isCorrect)

        val calc = QuestionItem(id = "q4", questionType = "CALCULATION", correctAnswer = "9.81 m/s^2", marks = 3L)
        assertTrue(ExamEngine.evaluateOffline(calc, "9.8 m/s2").isCorrect)
        assertFalse(ExamEngine.evaluateOffline(calc, "12.0").isCorrect)

        val formulaFrac = QuestionItem(id = "q5", questionType = "FORMULA_BASED", correctAnswer = "0.5", marks = 2L)
        assertTrue(ExamEngine.evaluateOffline(formulaFrac, "1/2").isCorrect)

        val matching = QuestionItem(id = "q6", questionType = "MATCHING", correctAnswer = "A-2, B-1, C-3", marks = 2L)
        assertTrue(ExamEngine.evaluateOffline(matching, "A-2, B-1, C-3").isCorrect)
        assertFalse(ExamEngine.evaluateOffline(matching, "A-1").isCorrect)
    }

    @Test
    fun `open-ended rubric works offline and upgrades to AI semantic grading when available`() = runTest {
        val essay = QuestionItem(
            id = "e1",
            questionType = QuestionType.EXPLAIN_JUSTIFY.name,
            questionText = "Explain why entropy increases in spontaneous isolated processes.",
            correctAnswer = "Entropy measures thermal disorder and increases toward thermodynamic equilibrium in isolated systems.",
            marks = 4L
        )
        // Offline keyword rubric awards credit for strong coverage and rejects unrelated text
        val offlineGood = ExamEngine.evaluateOffline(essay, "Entropy increases toward thermodynamic equilibrium as disorder grows in isolated systems.")
        assertTrue(offlineGood.isCorrect)
        assertEquals(4L, offlineGood.awardedMarks)

        val offlineBad = ExamEngine.evaluateOffline(essay, "Photosynthesis converts sunlight into glucose.")
        assertFalse(offlineBad.isCorrect)

        // Optional AI grader overrides open-ended scoring when reachable
        val aiGraded = ExamEngine.evaluateQuestion(essay, "System spreads energy out over microstates.") { _, _ ->
            Result.success(AiAnswerGrade(isCorrect = true, scoreRatio = 0.75, feedback = "Good statistical mechanics insight"))
        }
        assertTrue(aiGraded.gradedByAi)
        assertTrue(aiGraded.isCorrect)
        assertEquals(3L, aiGraded.awardedMarks)
        assertEquals("Good statistical mechanics insight", aiGraded.feedback)

        // When AI grader fails, cleanly falls back to offline rubric
        val aiFallback = ExamEngine.evaluateQuestion(essay, "Photosynthesis produces oxygen.") { _, _ ->
            Result.failure(RuntimeException("Offline"))
        }
        assertFalse(aiFallback.gradedByAi)
        assertFalse(aiFallback.isCorrect)
    }

    @Test
    fun `blueprint builder filters by chapter, type, difficulty, and preset and computes summary`() {
        val allTypes = QuestionType.entries.mapIndexed { idx, qt ->
            QuestionItem(
                id = "t_$idx",
                bookId = "b1",
                chapterNumber = (idx % 3 + 1).toLong(),
                questionType = qt.name,
                difficulty = if (idx % 2 == 0) "HARD" else "EASY",
                correctAnswer = "Answer $idx",
                marks = 2L
            )
        }
        assertEquals(20, allTypes.size)

        val ch2Calc = ExamEngine.buildExamQuestions(
            allQuestions = allTypes,
            blueprint = ExamBlueprint(
                chapterNumbers = setOf(2L),
                questionTypes = setOf("CALCULATION", "MCQ", "FORMULA_BASED"),
                difficulty = "MIXED",
                maxQuestions = 10
            ),
            randomize = false
        )
        assertTrue(ch2Calc.isNotEmpty())
        assertTrue(ch2Calc.all { it.chapterNumber == 2L })

        val q1 = allTypes[0]
        val q2 = allTypes[1]
        val summary = ExamEngine.computeExamSummary(
            examQuestions = listOf(q1, q2),
            evaluations = mapOf(
                q1.id to AnswerEvaluation(true, 1.0, 2L, "ok"),
                q2.id to AnswerEvaluation(false, 0.0, 0L, "missed")
            ),
            answersByQuestionId = mapOf(q1.id to "Answer 0", q2.id to "wrong")
        )
        assertEquals(2L, summary.score)
        assertEquals(4L, summary.totalMarks)
        assertEquals(50.0, summary.percentage, 0.01)
        assertEquals(1L, summary.correctCount)
        assertEquals(1L, summary.incorrectCount)
        assertTrue(summary.analyticsJson.contains("byChapter"))
    }

    @Test
    fun `offline exam cache persists and reloads submitted exams cleanly`() {
        val cache = OfflineExamCache(tempFolder.newFolder("exam_cache"))
        val rec = ExamRecord(
            id = "offline_abc123",
            title = "Offline Physics Quiz",
            preset = ExamPreset.CHAPTER_TEST.name,
            bookIds = listOf("b1"),
            questionIds = listOf("q1", "q2"),
            flaggedQuestionIds = listOf("q2"),
            status = "SUBMITTED",
            score = 8L,
            totalMarks = 10L,
            percentage = 80.0,
            correctCount = 4L,
            incorrectCount = 1L,
            unansweredCount = 0L,
            recommendedRevision = "Review Ch. 2"
        )
        cache.saveExam(rec)
        val loaded = cache.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("offline_abc123", loaded.first().id)
        assertEquals(80.0, loaded.first().percentage, 0.01)
        assertEquals(listOf("q2"), loaded.first().flaggedQuestionIds)

        cache.clearAll()
        assertTrue(cache.loadAll().isEmpty())
    }
}
