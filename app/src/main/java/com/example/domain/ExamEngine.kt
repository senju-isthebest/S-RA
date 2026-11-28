package com.example.domain

import com.example.data.ai.AiAnswerGrade
import com.example.data.ai.BookPassageRetriever
import com.example.data.ai.JsonObj
import com.example.data.ai.JsonStr
import com.example.data.ai.SoraJson
import com.example.data.model.ExamPreset
import com.example.data.model.ExamRecord
import com.example.data.model.QuestionItem
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

data class AnswerEvaluation(
    val isCorrect: Boolean,
    val scoreRatio: Double,
    val awardedMarks: Long,
    val feedback: String,
    val gradedByAi: Boolean = false
)

data class ExamBlueprint(
    val bookIds: Set<String> = emptySet(),
    val chapterNumbers: Set<Long> = emptySet(),
    val questionTypes: Set<String> = emptySet(),
    val difficulty: String = "MIXED",
    val maxQuestions: Int = 12,
    val preset: ExamPreset = ExamPreset.CUSTOM
)

data class ExamSummaryStats(
    val score: Long,
    val totalMarks: Long,
    val percentage: Double,
    val correctCount: Long,
    val incorrectCount: Long,
    val unansweredCount: Long,
    val analyticsJson: String,
    val weakChapters: List<Long>
)

/**
 * Deterministic offline + AI-optional exam engine supporting all 20 SŌRA QuestionTypes,
 * numeric tolerance calculation checks, multi-variant fill-in-the-blank, open-ended keyword
 * rubrics, and chapter/type blueprint construction.
 */
object ExamEngine {

    private val DETERMINISTIC_TYPES = setOf(
        "MCQ", "TRUE_FALSE", "FILL_BLANK", "CALCULATION", "FORMULA_BASED", "MATCHING", "ORDERING"
    )

    private val NUMERIC_RE = Regex("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
    private val FRACTION_RE = Regex("([-+]?\\d+(?:\\.\\d+)?)\\s*/\\s*([-+]?\\d+(?:\\.\\d+)?)")

    fun normalizeText(raw: String): String {
        return BookPassageRetriever.normalizeForSearch(raw)
            .replace(Regex("[^\\p{L}\\p{N}\\s.+\\-/*=]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun extractNumericValue(raw: String): Double? {
        val clean = raw.trim().replace(",", "")
        val frac = FRACTION_RE.find(clean)
        if (frac != null) {
            val num = frac.groupValues[1].toDoubleOrNull()
            val den = frac.groupValues[2].toDoubleOrNull()
            if (num != null && den != null && den != 0.0) return num / den
        }
        val numMatch = NUMERIC_RE.find(clean) ?: return null
        return numMatch.value.toDoubleOrNull()
    }

    fun isOpenEndedType(questionType: String): Boolean =
        questionType.uppercase() !in DETERMINISTIC_TYPES

    fun evaluateOffline(question: QuestionItem, userAnswer: String): AnswerEvaluation {
        val givenRaw = userAnswer.trim()
        if (givenRaw.isBlank()) {
            return AnswerEvaluation(
                isCorrect = false,
                scoreRatio = 0.0,
                awardedMarks = 0L,
                feedback = "Unanswered"
            )
        }
        val normTarget = normalizeText(question.correctAnswer)
        val normGiven = normalizeText(givenRaw)
        val marks = question.marks.coerceAtLeast(1L)

        if (normTarget == normGiven) {
            return AnswerEvaluation(true, 1.0, marks, "Exact match")
        }

        return when (question.questionType.uppercase()) {
            "MCQ" -> AnswerEvaluation(false, 0.0, 0L, "Selected option differs from answer key")

            "TRUE_FALSE" -> {
                val targetBool = parseBooleanAnswer(normTarget)
                val givenBool = parseBooleanAnswer(normGiven)
                val match = targetBool != null && targetBool == givenBool
                AnswerEvaluation(
                    isCorrect = match,
                    scoreRatio = if (match) 1.0 else 0.0,
                    awardedMarks = if (match) marks else 0L,
                    feedback = if (match) "Correct boolean value" else "Incorrect boolean value"
                )
            }

            "FILL_BLANK" -> {
                val variants = question.correctAnswer.split("|", "/", ";")
                    .map { normalizeText(it) }
                    .filter { it.isNotBlank() }
                val textHit = variants.any { it == normGiven }
                val numTarget = extractNumericValue(question.correctAnswer)
                val numGiven = extractNumericValue(givenRaw)
                val numHit = numTarget != null && numGiven != null && approxEqual(numTarget, numGiven)
                val hit = textHit || numHit
                AnswerEvaluation(
                    isCorrect = hit,
                    scoreRatio = if (hit) 1.0 else 0.0,
                    awardedMarks = if (hit) marks else 0L,
                    feedback = if (hit) "Matched expected blank" else "Blank does not match expected terms"
                )
            }

            "CALCULATION", "FORMULA_BASED" -> {
                val tNum = extractNumericValue(question.correctAnswer)
                val gNum = extractNumericValue(givenRaw)
                val hit = if (tNum != null && gNum != null) {
                    approxEqual(tNum, gNum)
                } else {
                    normGiven == normTarget
                }
                AnswerEvaluation(
                    isCorrect = hit,
                    scoreRatio = if (hit) 1.0 else 0.0,
                    awardedMarks = if (hit) marks else 0L,
                    feedback = if (hit) "Within numeric tolerance (±1.5%)" else "Numeric result outside tolerance"
                )
            }

            "MATCHING" -> evaluateMatching(question.correctAnswer, givenRaw, marks)

            "ORDERING" -> evaluateOrdering(question.correctAnswer, givenRaw, marks)

            else -> evaluateOpenEndedRubric(question, givenRaw, marks)
        }
    }

    private fun evaluateMatching(targetRaw: String, givenRaw: String, marks: Long): AnswerEvaluation {
        val targetPairs = parseMatchingPairs(targetRaw)
        if (targetPairs.isEmpty()) {
            val exact = normalizeText(targetRaw) == normalizeText(givenRaw)
            return AnswerEvaluation(exact, if (exact) 1.0 else 0.0, if (exact) marks else 0L, if (exact) "Exact match" else "Matching mismatch")
        }
        val givenParsed = parseMatchingEntries(givenRaw)
        val matched = targetPairs.entries.count { (key, expectedVal) ->
            val actual = givenParsed.byKey[key]
            actual != null && !givenParsed.ambiguousKeys.contains(key) && actual == expectedVal
        }
        val denom = maxOf(targetPairs.size, givenParsed.totalEntries).coerceAtLeast(1)
        val ratio = (matched.toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
        val awarded = (marks * ratio).roundToLong().coerceIn(0L, marks)
        return AnswerEvaluation(
            isCorrect = ratio >= 0.75,
            scoreRatio = ratio,
            awardedMarks = awarded,
            feedback = "Matched $matched/${targetPairs.size} pairs"
        )
    }

    private data class ParsedMatchingEntries(
        val byKey: Map<String, String>,
        val ambiguousKeys: Set<String>,
        val totalEntries: Int
    )

    private fun parseMatchingPairs(raw: String): Map<String, String> =
        parseMatchingEntries(raw).byKey

    private fun parseMatchingEntries(raw: String): ParsedMatchingEntries {
        val entries = raw.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val byKey = linkedMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        var parsedCount = 0
        for (entry in entries) {
            val split = splitMatchingPair(entry) ?: continue
            val left = normalizeText(split.first)
            val right = normalizeText(split.second)
            if (left.isBlank() || right.isBlank()) continue
            parsedCount++
            val prev = byKey[left]
            if (prev == null) {
                byKey[left] = right
            } else if (prev != right) {
                ambiguous.add(left)
            }
        }
        return ParsedMatchingEntries(byKey, ambiguous, parsedCount.coerceAtLeast(entries.size))
    }

    private fun splitMatchingPair(entry: String): Pair<String, String>? {
        for (sep in listOf("->", "=>", "→", "=", ":", "-")) {
            val idx = entry.indexOf(sep)
            if (idx > 0 && idx + sep.length < entry.length) {
                val left = entry.substring(0, idx).trim()
                val right = entry.substring(idx + sep.length).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) return left to right
            }
        }
        return null
    }

    private fun evaluateOrdering(targetRaw: String, givenRaw: String, marks: Long): AnswerEvaluation {
        val targetSeq = parseOrderingSequence(targetRaw)
        val givenSeq = parseOrderingSequence(givenRaw)
        if (targetSeq.size <= 1) {
            val exact = normalizeText(targetRaw) == normalizeText(givenRaw)
            return AnswerEvaluation(exact, if (exact) 1.0 else 0.0, if (exact) marks else 0L, if (exact) "Exact order" else "Incorrect order")
        }
        if (givenSeq == targetSeq) {
            return AnswerEvaluation(true, 1.0, marks, "Exact sequence match (${targetSeq.size}/${targetSeq.size})")
        }
        if (givenSeq.isEmpty()) {
            return AnswerEvaluation(false, 0.0, 0L, "No ordered steps detected")
        }

        val denom = maxOf(targetSeq.size, givenSeq.size)
        val exactPosHits = (0 until minOf(targetSeq.size, givenSeq.size)).count { i ->
            targetSeq[i] == givenSeq[i]
        }
        val exactRatio = exactPosHits.toDouble() / denom.toDouble()

        val usedGiven = BooleanArray(givenSeq.size)
        val matchedGivenPos = mutableListOf<Int>()
        for (tItem in targetSeq) {
            val gIdx = (givenSeq.indices).firstOrNull { !usedGiven[it] && givenSeq[it] == tItem }
            if (gIdx != null) {
                usedGiven[gIdx] = true
                matchedGivenPos.add(gIdx)
            }
        }
        var concordant = 0
        for (i in 0 until matchedGivenPos.size) {
            for (j in i + 1 until matchedGivenPos.size) {
                if (matchedGivenPos[i] < matchedGivenPos[j]) concordant++
            }
        }
        val totalPairs = (targetSeq.size * (targetSeq.size - 1)) / 2
        val pairOrderRatio = if (totalPairs > 0) {
            (concordant.toDouble() / totalPairs.toDouble()) * (matchedGivenPos.size.toDouble() / denom.toDouble())
        } else 0.0

        val ratio = (0.60 * exactRatio + 0.40 * pairOrderRatio).coerceIn(0.0, 0.99)
        val awarded = (marks * ratio).roundToLong().coerceIn(0L, marks)
        return AnswerEvaluation(
            isCorrect = ratio >= 0.75,
            scoreRatio = ratio,
            awardedMarks = awarded,
            feedback = "$exactPosHits/${targetSeq.size} in exact position"
        )
    }

    private fun parseOrderingSequence(raw: String): List<String> {
        val listPrefix = Regex("^\\s*(?:\\(?\\d{1,2}\\)?[.)]\\s+|[-•*]\\s+)")
        return raw.split(Regex("(?:->|→|>|[,;\\n])+"))
            .map { part -> normalizeText(part.replace(listPrefix, "").trim()) }
            .filter { it.isNotBlank() }
    }

    suspend fun evaluateQuestion(
        question: QuestionItem,
        userAnswer: String,
        aiEvaluator: (suspend (QuestionItem, String) -> Result<AiAnswerGrade>)? = null
    ): AnswerEvaluation {
        val offline = evaluateOffline(question, userAnswer)
        if (userAnswer.isBlank() || !isOpenEndedType(question.questionType) || aiEvaluator == null) {
            return offline
        }
        val aiRes = aiEvaluator(question, userAnswer)
        val grade = aiRes.getOrNull() ?: return offline
        val ratio = grade.scoreRatio.coerceIn(0.0, 1.0)
        val marks = question.marks.coerceAtLeast(1L)
        val awarded = (marks * ratio).roundToLong().coerceIn(0L, marks)
        return AnswerEvaluation(
            isCorrect = grade.isCorrect || ratio >= 0.5,
            scoreRatio = ratio,
            awardedMarks = awarded,
            feedback = grade.feedback.ifBlank { offline.feedback },
            gradedByAi = true
        )
    }

    fun buildExamQuestions(
        allQuestions: List<QuestionItem>,
        blueprint: ExamBlueprint,
        randomize: Boolean = true
    ): List<QuestionItem> {
        val visible = allQuestions.filter { !it.hidden }
        val base = visible.filter { q ->
            (blueprint.bookIds.isEmpty() || blueprint.bookIds.contains(q.bookId)) &&
                (blueprint.chapterNumbers.isEmpty() || blueprint.chapterNumbers.contains(q.chapterNumber)) &&
                (blueprint.questionTypes.isEmpty() || blueprint.questionTypes.contains(q.questionType.uppercase())) &&
                (blueprint.difficulty == "MIXED" || q.difficulty.equals(blueprint.difficulty, ignoreCase = true))
        }
        val presetFiltered = when (blueprint.preset) {
            ExamPreset.DIFFICULT_ONLY -> base.filter { it.difficulty == "HARD" }.ifEmpty { base }
            ExamPreset.WEAK_TOPICS -> base.filter { it.attempts > 0L && it.incorrectCount >= it.correctCount }.ifEmpty { base }
            ExamPreset.MISTAKE_REVIEW -> base.filter { it.incorrectCount > 0L && !it.mistakeResolved }.ifEmpty { base }
            ExamPreset.UNSEEN_ONLY -> base.filter { it.attempts == 0L }.ifEmpty { base }
            else -> base
        }
        val ordered = if (randomize) presetFiltered.shuffled() else presetFiltered
        return ordered.take(blueprint.maxQuestions.coerceAtLeast(1))
    }

    fun computeExamSummary(
        examQuestions: List<QuestionItem>,
        evaluations: Map<String, AnswerEvaluation>,
        answersByQuestionId: Map<String, String>
    ): ExamSummaryStats {
        var score = 0L
        val totalMarks = examQuestions.sumOf { it.marks.coerceAtLeast(1L) }.coerceAtLeast(1L)
        var correct = 0L
        var incorrect = 0L
        var unanswered = 0L
        val chapterMap = linkedMapOf<Long, Pair<Int, Int>>()
        val diffMap = linkedMapOf<String, Pair<Int, Int>>()
        val typeMap = linkedMapOf<String, Pair<Int, Int>>()

        examQuestions.forEach { q ->
            val ans = answersByQuestionId[q.id]?.trim().orEmpty()
            val ch = chapterMap[q.chapterNumber] ?: (0 to 0)
            val df = diffMap[q.difficulty] ?: (0 to 0)
            val tp = typeMap[q.questionType] ?: (0 to 0)

            if (ans.isBlank()) {
                unanswered++
                chapterMap[q.chapterNumber] = ch.first to (ch.second + 1)
                diffMap[q.difficulty] = df.first to (df.second + 1)
                typeMap[q.questionType] = tp.first to (tp.second + 1)
            } else {
                val ev = evaluations[q.id] ?: evaluateOffline(q, ans)
                score += ev.awardedMarks
                if (ev.isCorrect) {
                    correct++
                    chapterMap[q.chapterNumber] = (ch.first + 1) to (ch.second + 1)
                    diffMap[q.difficulty] = (df.first + 1) to (df.second + 1)
                    typeMap[q.questionType] = (tp.first + 1) to (tp.second + 1)
                } else {
                    incorrect++
                    chapterMap[q.chapterNumber] = ch.first to (ch.second + 1)
                    diffMap[q.difficulty] = df.first to (df.second + 1)
                    typeMap[q.questionType] = tp.first to (tp.second + 1)
                }
            }
        }

        val pct = (score.toDouble() / totalMarks.toDouble()) * 100.0
        val analyticsJson = JsonObj(
            mapOf(
                "byChapter" to JsonObj(chapterMap.entries.associate { (k, v) -> "Ch. $k" to JsonStr("${v.first}/${v.second}") }),
                "byDifficulty" to JsonObj(diffMap.entries.associate { (k, v) -> k to JsonStr("${v.first}/${v.second}") }),
                "byType" to JsonObj(typeMap.entries.associate { (k, v) -> k to JsonStr("${v.first}/${v.second}") })
            )
        ).toJsonString()

        val weakChapters = chapterMap.entries
            .filter { it.value.second > 0 && (it.value.first.toDouble() / it.value.second) < 0.7 }
            .map { it.key }

        return ExamSummaryStats(
            score = score.coerceAtMost(totalMarks),
            totalMarks = totalMarks,
            percentage = pct.coerceIn(0.0, 100.0),
            correctCount = correct,
            incorrectCount = incorrect,
            unansweredCount = unanswered,
            analyticsJson = analyticsJson,
            weakChapters = weakChapters
        )
    }

    private fun evaluateOpenEndedRubric(
        question: QuestionItem,
        givenRaw: String,
        marks: Long
    ): AnswerEvaluation {
        val targetTokens = BookPassageRetriever.tokenize(question.correctAnswer).distinct()
        val givenTokens = BookPassageRetriever.tokenize(givenRaw).toSet()
        if (targetTokens.isEmpty()) {
            val hit = normalizeText(givenRaw).contains(normalizeText(question.correctAnswer))
            return AnswerEvaluation(hit, if (hit) 1.0 else 0.0, if (hit) marks else 0L, "")
        }
        val hits = targetTokens.count { it in givenTokens }
        val recall = hits.toDouble() / targetTokens.size.toDouble()
        val (ratio, isRight, fb) = when {
            recall >= 0.70 -> Triple(1.0, true, "Covered key concepts ($hits/${targetTokens.size} key terms)")
            recall >= 0.45 -> Triple(0.5, true, "Partial credit: covered $hits/${targetTokens.size} key terms")
            else -> Triple(0.0, false, "Missing key concepts ($hits/${targetTokens.size} key terms matched)")
        }
        val awarded = (marks * ratio).roundToLong().coerceIn(0L, marks)
        return AnswerEvaluation(
            isCorrect = isRight,
            scoreRatio = ratio,
            awardedMarks = awarded,
            feedback = fb
        )
    }

    private fun parseBooleanAnswer(norm: String): Boolean? = when (norm) {
        "true", "t", "yes", "1", "صح", "صحيح", "نعم" -> true
        "false", "f", "no", "0", "خطا", "خاطئ", "لا" -> false
        else -> null
    }

    private fun approxEqual(a: Double, b: Double): Boolean {
        val diff = abs(a - b)
        val tol = maxOf(1e-4, abs(a) * 0.015)
        return diff <= tol
    }
}

/**
 * Local offline JSON cache for ExamRecords so students can take, submit, and review exams offline.
 */
class OfflineExamCache(filesDir: File) {
    private val dir = File(filesDir, "exams").apply { mkdirs() }

    fun saveExam(exam: ExamRecord) {
        if (exam.id.isBlank()) return
        val file = File(dir, "exam_${exam.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}.json")
        val payload = SoraJson.obj(
            "id" to exam.id,
            "title" to exam.title,
            "preset" to exam.preset,
            "bookIds" to exam.bookIds,
            "questionIds" to exam.questionIds,
            "durationMinutes" to exam.durationMinutes,
            "totalMarks" to exam.totalMarks,
            "allowRevisit" to exam.allowRevisit,
            "showExplanationEach" to exam.showExplanationEach,
            "status" to exam.status,
            "answersJson" to exam.answersJson,
            "flaggedQuestionIds" to exam.flaggedQuestionIds,
            "score" to exam.score,
            "percentage" to exam.percentage,
            "correctCount" to exam.correctCount,
            "incorrectCount" to exam.incorrectCount,
            "unansweredCount" to exam.unansweredCount,
            "timeSpentSeconds" to exam.timeSpentSeconds,
            "analyticsJson" to exam.analyticsJson,
            "recommendedRevision" to exam.recommendedRevision
        ).toJsonString()
        file.writeText(payload, Charsets.UTF_8)
    }

    fun loadAll(): List<ExamRecord> {
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val obj = SoraJson.parseObject(file.readText(Charsets.UTF_8))
                ExamRecord(
                    id = obj.str("id"),
                    title = obj.str("title"),
                    preset = obj.str("preset", "CUSTOM"),
                    bookIds = obj.strList("bookIds"),
                    questionIds = obj.strList("questionIds"),
                    durationMinutes = obj.longVal("durationMinutes", 20L),
                    totalMarks = obj.longVal("totalMarks", 10L),
                    allowRevisit = obj.boolVal("allowRevisit", true),
                    showExplanationEach = obj.boolVal("showExplanationEach", false),
                    status = obj.str("status", "IN_PROGRESS"),
                    answersJson = obj.str("answersJson", "{}"),
                    flaggedQuestionIds = obj.strList("flaggedQuestionIds"),
                    score = obj.longVal("score", 0L),
                    percentage = obj.doubleVal("percentage", 0.0),
                    correctCount = obj.longVal("correctCount", 0L),
                    incorrectCount = obj.longVal("incorrectCount", 0L),
                    unansweredCount = obj.longVal("unansweredCount", 0L),
                    timeSpentSeconds = obj.longVal("timeSpentSeconds", 0L),
                    analyticsJson = obj.str("analyticsJson", "{}"),
                    recommendedRevision = obj.str("recommendedRevision", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun clearAll() {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
    }
}
