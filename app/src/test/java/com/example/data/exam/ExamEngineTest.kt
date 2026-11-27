package com.example.data.exam

import com.example.data.model.QuestionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamEngineTest {
    @Test fun objectiveQuestionRequiresExactCorrectOption() {
        val q = QuestionItem(questionType = "MCQ", options = listOf("One", "Two"), correctAnswer = "B", marks = 4)
        assertEquals(4L, ExamEngine.grade(q, "Two").earnedMarks)
        assertEquals(0L, ExamEngine.grade(q, "One").earnedMarks)
    }

    @Test fun shortAnswerAwardsPartialCreditForRelevantKeywords() {
        val q = QuestionItem(questionType = "SHORT_ANSWER", correctAnswer = "photosynthesis converts light energy into chemical energy", marks = 5)
        val grade = ExamEngine.grade(q, "Photosynthesis converts light energy")
        assertTrue(grade.earnedMarks in 1L..4L)
        assertTrue(grade.isPartiallyCorrect)
        assertFalse(grade.isFullyCorrect)
    }

    @Test fun blankAnswerGetsNoCredit() {
        val grade = ExamEngine.grade(QuestionItem(marks = 3, correctAnswer = "answer"), "  ")
        assertEquals(0L, grade.earnedMarks)
        assertFalse(grade.isPartiallyCorrect)
    }

    @Test fun timerNeverReturnsNegativeAndRoundsUp() {
        assertEquals(2, ExamEngine.remainingSeconds(2_001L, 1_000L))
        assertEquals(0, ExamEngine.remainingSeconds(999L, 1_000L))
    }
}
