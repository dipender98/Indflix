package com.vegamovies

import com.vegamovies.MetadataService.TmdbItem
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

private val NON_ALNUM_UNICODE = Regex("""[^\p{L}\p{M}\p{N}]+""")
private val COMBINING_MARKS = Regex("""\p{M}""")
private val YEAR_TOKEN = Regex("""^[12]\d{3}$""")

internal val SEARCH_STOPWORDS = setOf(
    "the", "a", "an", "and", "or", "of", "for", "in", "on", "at", "to", "with",
    "vs", "v", "s", "e", "de", "la", "le", "el", "en", "feat", "ft", "ze",
)

internal fun deaccent(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "")

internal fun normalizeTitle(t: String): String =
    deaccent(t.lowercase().replace("'", "").replace("’", "").trim())
        .replace(NON_ALNUM_UNICODE, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

internal fun levenshteinDistance(a: String, b: String): Int {
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

internal fun doubleFirstVowel(word: String): String? {
    val idx = word.indexOfFirst { it.lowercaseChar() in "aeiou" }
    if (idx < 0) return null
    if (idx > 0 && word[idx - 1] == word[idx]) return null
    if (idx + 1 < word.length && word[idx + 1] == word[idx]) return null
    return word.substring(0, idx + 1) + word[idx] + word.substring(idx + 1)
}

internal fun queryVariants(query: String, max: Int = 2): List<String> {
    val tokens = normalizeTitle(query).split(' ').filter { it.length >= 3 }
    if (tokens.isEmpty()) return emptyList()
    val variants = ArrayList<String>()
    for ((i, tok) in tokens.withIndex()) {
        if (variants.size >= max) break
        val doubled = doubleFirstVowel(tok) ?: continue
        if (doubled == tok) continue
        val renamed = tokens.toMutableList().also { it[i] = doubled }
        variants += renamed.joinToString(" ")
    }
    return variants.distinct()
}
internal fun significantQueryTokens(query: String): List<String> =
    normalizeTitle(query).split(' ').filter { it.length >= 2 && it !in SEARCH_STOPWORDS }.distinct()

internal fun tokenMatchScore(token: String, titleTokens: List<String>): Double {
    if (titleTokens.any { it == token }) return 1.0
    if (titleTokens.any { it.length >= 4 && it.contains(token) }) return 1.0
    if (token.length >= 4 && titleTokens.any { token.contains(it) }) return 1.0
    if (titleTokens.any { it.length >= 3 && (it.startsWith(token) || token.startsWith(it)) }) return 0.9
    val tolerance = if (token.length <= 5) 1 else 2
    if (titleTokens.any { levenshteinDistance(it, token) <= tolerance }) return 0.8
    return 0.0
}

internal fun relevanceScore(query: String, title: String): Double {
    val qNorm = normalizeTitle(query)
    val tNorm = normalizeTitle(title)
    if (qNorm.isBlank() || tNorm.isBlank()) return 0.0
    if (qNorm == tNorm) return 1.0
    val qTokens = significantQueryTokens(query)
    if (qTokens.isEmpty()) {
        val contained = tNorm.contains(qNorm)
        return if (contained) 1.0 else 0.0
    }
    val tTokens = tNorm.split(' ').filter { it.isNotEmpty() }
    var sum = 0.0
    var matchedAny = false
    for (tok in qTokens) {
        var best = tokenMatchScore(tok, tTokens)
        if (best < 1.0 && tok.length == 4 && tok.all { it.isDigit() } && tTokens.any { it == tok }) best = 1.0
        if (best <= 0.0) continue
        matchedAny = true
        sum += best
    }
    if (!matchedAny) return 0.0
    val extraWords = (tTokens.size - qTokens.size).coerceAtLeast(0)
    return (sum / qTokens.size - 0.03 * min(extraWords, 5)).coerceIn(0.0, 1.0)
}

internal fun probeTokens(title: String): List<String> =
    normalizeTitle(title).split(' ')
        .filter { it.length >= 2 && it !in SEARCH_STOPWORDS && !YEAR_TOKEN.matches(it) }
        .distinct()

internal fun votesBand(votes: Int): Double = when {
    votes >= 5000 -> 1.0
    votes >= 2000 -> 0.95
    votes >= 1000 -> 0.9
    votes >= 500 -> 0.8
    votes >= 200 -> 0.7
    votes >= 50 -> 0.55
    votes >= 10 -> 0.4
    votes >= 1 -> 0.3
    else -> 0.0
}

internal fun bestTmdbMatch(tmdb: List<TmdbItem>, title: String, year: Int?): TmdbItem? {
    var best: TmdbItem? = null
    var bestRel = 0.0
    for (item in tmdb) {
        val rel = relevanceScore(item.name, title)
        if (rel < 0.7) continue
        val itemYear = item.year?.toIntOrNull()
        if (year != null && itemYear != null && abs(year - itemYear) > 2) continue
        if (rel > bestRel) {
            bestRel = rel
            best = item
        }
    }
    return best
}

internal fun popularityScore(tmdb: List<TmdbItem>, title: String, year: Int?): Double =
    bestTmdbMatch(tmdb, title, year)?.let { votesBand(it.votes) } ?: 0.0

internal fun combinedScore(query: String, title: String, year: Int?, tmdb: List<TmdbItem>): Double {
    val rel = relevanceScore(query, title)
    if (rel <= 0.0) return 0.0
    if (rel >= 0.999) return 1.0
    return 0.6 * rel + 0.4 * popularityScore(tmdb, title, year)
}

internal fun List<com.lagradost.cloudstream3.SearchResponse>.dedupeByName(): List<com.lagradost.cloudstream3.SearchResponse> {
    val seen = HashSet<String>()
    return filter {
        seen.add(normalizeTitle(it.name) + "|" + (searchYear(it) ?: 0))
    }
}

internal fun searchYear(r: com.lagradost.cloudstream3.SearchResponse): Int? = when (r) {
    is com.lagradost.cloudstream3.MovieSearchResponse -> r.year
    is com.lagradost.cloudstream3.TvSeriesSearchResponse -> r.year
    else -> null
}