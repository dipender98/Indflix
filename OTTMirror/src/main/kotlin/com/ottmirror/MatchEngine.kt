package com.ottmirror

import kotlin.math.abs
import kotlin.math.min

/**
 * Pure, JVM-testable helpers for title normalization and fuzzy matching.
 * No network, no Android — safe for unit tests.
 */
object MatchEngine {

    /** Strip punctuation, collapse whitespace, lowercase. Keeps unicode letters/digits. */
    fun normalizeTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** Common spelling variants of a title ("&"→"and", roman numerals, accents). */
    fun titleVariants(title: String?): List<String> {
        val n = normalizeTitle(title)
        if (n.isBlank()) return emptyList()
        val variants = linkedSetOf(n)
        // "a & b" ↔ "a and b" — must be derived from the RAW title before the
        // ampersand is normalized away into a space.
        if (title != null) {
            variants += normalizeTitle(title.replace("&", " and "))
            variants += normalizeTitle(title.replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), "&"))
        }
        // apostrophes dropped / kept
        variants += n.replace("'", "")
        variants += n.replace("’", "")
        // roman numeral ↔ number (basic)
        variants += n.replace(Regex("\\biv\\b"), "4")
        variants += n.replace(Regex("\\biii\\b"), "3")
        variants += n.replace(Regex("\\bii\\b"), "2")
        variants += n.replace(Regex("\\bi\\b"), "1")
        return variants.filter { it.isNotBlank() }.distinct()
    }

    /** Classic Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }

    /** Relevance 0..1 — exact normalized match = 1.0. */
    fun titleDistance(query: String, title: String): Double {
        val q = normalizeTitle(query)
        val t = normalizeTitle(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        // token-prefix bonus: every query token starts one of the title tokens
        val qTokens = q.split(" ")
        val tTokens = t.split(" ")
        if (qTokens.all { qt -> tTokens.any { tt -> tt.startsWith(qt) } }) {
            val lenScore = q.length.toDouble() / t.length.toDouble()
            return 0.7 + 0.3 * min(1.0, lenScore)
        }
        // levenshtein similarity on the full strings
        val maxLen = maxOf(q.length, t.length)
        if (maxLen == 0) return 0.0
        return 1.0 - levenshtein(q, t).toDouble() / maxLen
    }

    /** Whether the title plausibly matches given the release year. */
    fun yearMatches(queryYear: Int?, candidateYear: Int?, tolerance: Int = 2): Boolean {
        if (queryYear == null || candidateYear == null) return true
        return abs(queryYear - candidateYear) <= tolerance
    }

    /** Combined gate used by search: strict token match + score threshold. */
    fun isRelevant(query: String, title: String, queryYear: Int?, candidateYear: Int?): Boolean {
        if (!yearMatches(queryYear, candidateYear)) return false
        // Strip any embedded years (e.g. "Joker (2019)") before comparing titles.
        val q = stripYearTokens(normalizeTitle(query))
        val t = stripYearTokens(normalizeTitle(title))
        return titleDistance(q, t) >= 0.7
    }

    /** Remove standalone 4-digit year tokens ("2019", "2001") from a normalized title. */
    private fun stripYearTokens(normalized: String): String {
        if (normalized.isBlank()) return normalized
        return normalized.split(" ")
            .filterNot { it.matches(Regex("(19|20)\\d{2}")) }
            .joinToString(" ")
            .trim()
    }

    /** Extract 4-digit year from a string, or null. */
    fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("(19|20)\\d{2}").find(raw)?.value?.toIntOrNull()
    }
}
