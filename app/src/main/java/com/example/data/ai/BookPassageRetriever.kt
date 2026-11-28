package com.example.data.ai

import com.example.data.model.BookPageContent
import com.example.data.model.Chapter
import kotlin.math.ln

data class RetrievedPassage(
    val chapterNumber: Long,
    val pageNumber: Int,
    val score: Double,
    val excerpt: String
)

private data class IndexedPassageChunk(
    val chapterNumber: Long,
    val pageNumber: Int,
    val excerpt: String,
    val tokens: List<String>,
    val termFreq: Map<String, Int>
)

/**
 * Bilingual (English + Arabic) BM25/TF-IDF passage retriever and context budget manager
 * for full-book Q&A and grounded citations across all textbook chapters.
 */
object BookPassageRetriever {

    private val EN_STOPWORDS = setOf(
        "the", "and", "for", "that", "this", "with", "from", "are", "was", "were",
        "have", "has", "had", "not", "but", "what", "which", "when", "where", "who",
        "why", "how", "into", "about", "than", "then", "also", "can", "will", "their"
    )

    private val AR_STOPWORDS = setOf(
        "في", "من", "على", "الى", "عن", "مع", "هذا", "هذه", "ذلك", "تلك",
        "التي", "الذي", "الذين", "ما", "ماذا", "كيف", "لماذا", "اين", "متى",
        "ان", "أن", "كان", "كانت", "يكون", "بين", "او", "أو", "ثم", "بل"
    )

    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun normalizeForSearch(raw: String): String {
        return raw
            .replace(ARABIC_DIACRITICS, "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .lowercase()
    }

    fun tokenize(raw: String): List<String> {
        val norm = normalizeForSearch(raw)
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in norm) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (sb.isNotEmpty()) {
                addIfMeaningful(sb.toString(), tokens)
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) addIfMeaningful(sb.toString(), tokens)
        return tokens
    }

    private fun addIfMeaningful(token: String, out: MutableList<String>) {
        if (token.length < 2) return
        if (token in EN_STOPWORDS || token in AR_STOPWORDS) return
        out.add(token)
    }

    fun retrieve(
        query: String,
        pages: List<BookPageContent>,
        chapters: List<Chapter>,
        topK: Int = 5,
        maxTotalChars: Int = 6_500
    ): List<RetrievedPassage> {
        if (pages.isEmpty() || topK <= 0 || maxTotalChars <= 0) return emptyList()
        val queryTokens = tokenize(query)
        val normQuery = normalizeForSearch(query).trim()

        val chunks = buildChunks(pages, chapters)
        if (chunks.isEmpty()) return emptyList()

        if (queryTokens.isEmpty()) {
            return applyContextBudget(
                chunks.take(topK).map { RetrievedPassage(it.chapterNumber, it.pageNumber, 1.0, it.excerpt) },
                maxTotalChars
            )
        }

        val docCount = chunks.size.toDouble()
        val avgLen = chunks.sumOf { it.tokens.size }.toDouble() / docCount.coerceAtLeast(1.0)
        val df = HashMap<String, Int>()
        queryTokens.distinct().forEach { qt ->
            df[qt] = chunks.count { (it.termFreq[qt] ?: 0) > 0 }
        }

        val k1 = 1.5
        val b = 0.75

        val scored = chunks.mapNotNull { chunk ->
            var score = 0.0
            val docLen = chunk.tokens.size.toDouble().coerceAtLeast(1.0)
            for (qt in queryTokens) {
                val tf = chunk.termFreq[qt]?.toDouble() ?: 0.0
                if (tf <= 0.0) continue
                val n = df[qt]?.toDouble() ?: 0.0
                val idf = ln(((docCount - n + 0.5) / (n + 0.5)) + 1.0)
                val denom = tf + k1 * (1.0 - b + b * (docLen / avgLen.coerceAtLeast(1.0)))
                score += idf * ((tf * (k1 + 1.0)) / denom)
            }
            if (normQuery.length >= 4 && normalizeForSearch(chunk.excerpt).contains(normQuery)) {
                score += 2.5
            }
            if (score <= 0.0) null
            else RetrievedPassage(
                chapterNumber = chunk.chapterNumber,
                pageNumber = chunk.pageNumber,
                score = score,
                excerpt = chunk.excerpt
            )
        }
            .sortedByDescending { it.score }
            .distinctBy { it.pageNumber to it.excerpt.take(80) }
            .take(topK)

        return applyContextBudget(scored, maxTotalChars)
    }

    fun formatGroundedContext(passages: List<RetrievedPassage>, maxChars: Int = 6_500): String {
        if (passages.isEmpty()) return ""
        val sb = StringBuilder()
        for (p in applyContextBudget(passages, maxChars)) {
            val chPart = if (p.chapterNumber > 0) "Ch. ${p.chapterNumber}, " else ""
            val header = "[From Textbook — ${chPart}p. ${p.pageNumber}]\n"
            if (sb.length + header.length + p.excerpt.length + 2 > maxChars) break
            sb.append(header).append(p.excerpt).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun applyContextBudget(
        passages: List<RetrievedPassage>,
        maxTotalChars: Int
    ): List<RetrievedPassage> {
        val result = mutableListOf<RetrievedPassage>()
        var used = 0
        for (p in passages) {
            if (used >= maxTotalChars) break
            val available = maxTotalChars - used
            val clipped = if (p.excerpt.length <= available) p.excerpt else p.excerpt.take(available)
            if (clipped.length < 24 && result.isNotEmpty()) break
            result.add(p.copy(excerpt = clipped))
            used += clipped.length
        }
        return result
    }

    private fun buildChunks(
        pages: List<BookPageContent>,
        chapters: List<Chapter>
    ): List<IndexedPassageChunk> {
        val out = mutableListOf<IndexedPassageChunk>()
        for (page in pages) {
            val text = page.text.trim()
            if (text.isBlank()) continue
            val chNum = chapters.firstOrNull {
                page.pageNumber in it.startPage.toInt()..it.endPage.toInt()
            }?.chapterNumber ?: 0L

            if (text.length <= 950) {
                val toks = tokenize(text)
                out.add(
                    IndexedPassageChunk(
                        chapterNumber = chNum,
                        pageNumber = page.pageNumber,
                        excerpt = text,
                        tokens = toks,
                        termFreq = toks.groupingBy { it }.eachCount()
                    )
                )
            } else {
                var start = 0
                while (start < text.length) {
                    val end = (start + 950).coerceAtMost(text.length)
                    val slice = text.substring(start, end).trim()
                    if (slice.isNotBlank()) {
                        val toks = tokenize(slice)
                        out.add(
                            IndexedPassageChunk(
                                chapterNumber = chNum,
                                pageNumber = page.pageNumber,
                                excerpt = slice,
                                tokens = toks,
                                termFreq = toks.groupingBy { it }.eachCount()
                            )
                        )
                    }
                    if (end >= text.length) break
                    start += 700
                }
            }
        }
        return out
    }
}
