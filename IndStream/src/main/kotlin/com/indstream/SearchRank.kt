package com.indstream

import java.text.Normalizer
import kotlin.math.min

/** Typo-tolerant ranking shared by both search sources. */
object SearchRank {

    private val NON_ALNUM = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val MARKS = Regex("""\p{M}""")
    private val YEAR_TOKEN = Regex("""^[12]\d{3}$""")

    /** Tokens too generic to carry query meaning. */
    internal val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "for", "in", "on", "at", "to", "with",
        "vs", "v", "s", "e", "de", "la", "le", "el", "en", "feat", "ft", "ze",
    )

    /** Fold accents for transliterated titles. */
    internal fun deaccent(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "")

    /** Lowercase, apostrophes dropped, punctuation to spaces. */
    internal fun normalizeTitle(t: String): String =
        deaccent(t.lowercase().replace("'", "").replace("’", "").trim())
            .replace(NON_ALNUM, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Two-row edit distance. */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** Double the first vowel for long-vowel transliterations. Null when absent or already long. */
    internal fun doubleFirstVowel(word: String): String? {
        val idx = word.indexOfFirst { it.lowercaseChar() in "aeiou" }
        if (idx < 0) return null
        if (idx > 0 && word[idx - 1] == word[idx]) return null
        if (idx + 1 < word.length && word[idx + 1] == word[idx]) return null
        return word.substring(0, idx + 1) + word[idx] + word.substring(idx + 1)
    }

    /** Typo-tolerant respellings of a query, at most [max]. */
    internal fun queryVariants(query: String, max: Int = 2): List<String> {
        val tokens = normalizeTitle(query).split(' ').filter { it.length >= 3 }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for ((i, tok) in tokens.withIndex()) {
            if (out.size >= max) break
            val doubled = doubleFirstVowel(tok) ?: continue
            if (doubled == tok) continue
            out += tokens.toMutableList().also { it[i] = doubled }.joinToString(" ")
        }
        return out.distinct()
    }

    /** Significant query tokens for scoring. */
    internal fun queryTokens(query: String): List<String> =
        normalizeTitle(query).split(' ').filter { it.length >= 2 && it !in STOPWORDS }.distinct()

    /** Best match of one token against title tokens. */
    internal fun tokenScore(token: String, titleTokens: List<String>): Double {
        if (titleTokens.any { it == token }) return 1.0
        if (titleTokens.any { it.length >= 4 && it.contains(token) }) return 1.0
        if (token.length >= 4 && titleTokens.any { token.contains(it) }) return 1.0
        if (titleTokens.any { it.length >= 3 && (it.startsWith(token) || token.startsWith(it)) }) return 0.9
        val tolerance = if (token.length <= 5) 1 else 2
        if (titleTokens.any { levenshtein(it, token) <= tolerance }) return 0.8
        return 0.0
    }

    /** Query-vs-title relevance in [0, 1]. */
    internal fun relevance(query: String, title: String): Double {
        val qNorm = normalizeTitle(query)
        val tNorm = normalizeTitle(title)
        if (qNorm.isBlank() || tNorm.isBlank()) return 0.0
        if (qNorm == tNorm) return 1.0
        val qTokens = queryTokens(query)
        if (qTokens.isEmpty()) return if (tNorm.contains(qNorm)) 1.0 else 0.0
        val tTokens = tNorm.split(' ').filter { it.isNotEmpty() }
        var sum = 0.0
        var matched = false
        for (tok in qTokens) {
            var best = tokenScore(tok, tTokens)
            if (best < 1.0 && tok.length == 4 && tok.all { it.isDigit() } && tTokens.any { it == tok }) best = 1.0
            if (best <= 0.0) continue
            matched = true
            sum += best
        }
        if (!matched) return 0.0
        val extra = (tTokens.size - qTokens.size).coerceAtLeast(0)
        return (sum / qTokens.size - 0.03 * min(extra, 5)).coerceIn(0.0, 1.0)
    }

    /** Lower suggest rank means more popular. */
    internal fun rankBand(rank: Int?): Double = when {
        rank == null || rank <= 0 -> 0.0
        rank <= 1000 -> 1.0
        rank <= 10000 -> 0.9
        rank <= 50000 -> 0.8
        rank <= 200000 -> 0.6
        else -> 0.4
    }

    /** Final score: relevance dominates, popularity breaks ties. */
    fun combined(query: String, title: String, imdbRank: Int?, tmdbRating: Double?): Double {
        val rel = relevance(query, title)
        if (rel <= 0.0) return 0.0
        if (rel >= 0.999) return 1.0
        val pop = maxOf(rankBand(imdbRank), (tmdbRating ?: 0.0) / 10.0)
        return 0.6 * rel + 0.4 * pop
    }

    /** Collapse duplicates sharing normalized (title, year). First survives. */
    fun dedupeKey(name: String, year: Int?): String = normalizeTitle(name) + "|" + (year ?: 0)

    /** Significant non-year tokens for follow-up probes. */
    internal fun probeTokens(title: String): List<String> =
        normalizeTitle(title).split(' ')
            .filter { it.length >= 2 && it !in STOPWORDS && !YEAR_TOKEN.matches(it) }
            .distinct()
}
