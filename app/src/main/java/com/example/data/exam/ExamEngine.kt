package com.example.data.exam

import com.example.data.model.QuestionItem
import kotlin.math.floor

data class QuestionGrade(
    val earnedMarks: Long,
    val maximumMarks: Long,
    val isFullyCorrect: Boolean,
    val isPartiallyCorrect: Boolean
)

object ExamEngine {
    fun grade(question: QuestionItem, answer: String): QuestionGrade {
        val maximum = question.marks.coerceIn(1L, 100L)
        val given = answer.trim()
        if (given.isBlank()) return QuestionGrade(0L, maximum, false, false)

        val normalizedGiven = normalize(given)
        val normalizedExpected = normalize(question.correctAnswer)
        val exactMatch = normalizedGiven == normalizedExpected ||
            selectedOptionMatches(question, normalizedGiven)

        if (exactMatch) return QuestionGrade(maximum, maximum, true, false)

        val type = question.questionType.uppercase()
        if (type in setOf("MCQ", "TRUE_FALSE", "FILL_BLANK", "CALCULATION")) {
            return QuestionGrade(0L, maximum, false, false)
        }

        val expectedTokens = tokens(question.correctAnswer)
        if (expectedTokens.isEmpty()) return QuestionGrade(0L, maximum, false, false)
        val givenTokens = tokens(given)
        val overlap = expectedTokens.count { it in givenTokens }
        val ratio = overlap.toDouble() / expectedTokens.size.toDouble()
        if (ratio < 0.20) return QuestionGrade(0L, maximum, false, false)

        val earned = floor(maximum * ratio).toLong().coerceIn(1L, maximum)
        return QuestionGrade(
            earnedMarks = earned,
            maximumMarks = maximum,
            isFullyCorrect = earned == maximum,
            isPartiallyCorrect = earned in 1 until maximum
        )
    }

    fun remainingSeconds(deadlineAtMillis: Long, nowMillis: Long): Int =
        (((deadlineAtMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun selectedOptionMatches(question: QuestionItem, normalizedGiven: String): Boolean {
        if (question.options.isEmpty()) return false
        val expected = question.correctAnswer.trim()
        val letterIndex = expected.uppercase().singleOrNull()
            ?.takeIf { it in 'A'..'J' }
            ?.let { it - 'A' }
        val numericIndex = expected.toIntOrNull()?.minus(1)
        val expectedIndex = letterIndex ?: numericIndex
        if (expectedIndex != null && expectedIndex in question.options.indices) {
            return normalize(question.options[expectedIndex]) == normalizedGiven
        }
        return question.options.any { normalize(it) == normalizedGiven } &&
            normalize(expected) == normalizedGiven
    }

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ")

    private fun tokens(value: String): Set<String> =
        normalize(value).split(' ').filter { it.length >= 3 }.toSet()
}
