package com.multimovies

import org.jsoup.nodes.Document

/**
 * Pure, JVM-testable helpers shared by the provider's search pipeline, poster
 * handling and fetch retry logic. No CloudStream/Android runtime dependency, so
 * the unit tests exercise them on the plain JVM.
 *
 * Kept out of [MultimoviesProvider] so the provider file stays focused on the
 * MainAPI wiring (search / mainPage / load / loadLinks) instead of carrying
 * ~250 lines of string/regex utilities.
 */

/** Unicode-aware normalization (lowercase; letters, marks, digits only) so
 *  Hindi/Devanagari queries survive: vowel signs like ि/ी are combining marks
 *  (Unicode \p{M}), not letters, so they must be kept or scripts get mangled. */
private val NON_ALNUM_UNICODE = Regex("""[^\p{L}\p{M}\p{N}]+""")

internal fun normalizeTitle(t: String): String =
    t.lowercase().replace("'", "").replace("’", "").trim()
        .replace(NON_ALNUM_UNICODE, " ").trim()

/** Alternative spellings of a title that a Dooplay site may store, so slug
 *  guessing and the site-search fallback survive "&" vs "and", dropped
 *  apostrophes ("King's Man" -> "Kings Man") and stray punctuation such as
 *  "(", ")", ":", ";", "," that a WordPress search treats literally. */
internal fun titleVariants(title: String): List<String> {
    val andWord = Regex("\\band\\b", RegexOption.IGNORE_CASE)
    return buildList {
        add(title)
        if (title.contains('&')) add(title.replace("&", " and "))
        if (andWord.containsMatchIn(title)) add(title.replace(andWord, "&"))
        val noApostrophe = title.replace("'", "").replace("’", "")
        if (noApostrophe != title) add(noApostrophe)
        add(title.replace(Regex("[^\\p{L}\\p{M}\\p{N} ]+"), " "))
    }.map { it.replace(Regex("\\s+"), " ").trim() }.distinct()
}

/** Classic Levenshtein edit distance (two-row implementation). */
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

/** Coarse title match distance for validating a fetched page / search hit
 *  against the queried title: 0 identical (also when they differ only by the
 *  "&"/"and" join or a dropped apostrophe), 1 when one is a prefix of the
 *  other, else 2. */
internal fun titleDistance(itemTitle: String, target: String): Int {
    val a = normalizeTitle(itemTitle)
    val b = normalizeTitle(target)
    return when {
        a == b -> 0
        // TMDB spells "Locke & Key" where the site says "Locke and Key";
        // dropping the "and" join on either side makes them identical.
        a.replace(" and ", " ") == b && a.contains(" and ") -> 0
        b.replace(" and ", " ") == a && b.contains(" and ") -> 0
        a.startsWith(b) || b.startsWith(a) -> 1
        else -> 2
    }
}

/** Query words that carry meaning (>=2 chars); digit tokens such as years are
 *  kept so "iron man 2010" can match a release year too. */
internal fun significantQueryTokens(query: String): List<String> =
    normalizeTitle(query).split(' ').filter { it.length >= 2 }.distinct()

/** Best fuzzy match score for one query [token] against the title's tokens:
 *  1.0 exact or substring in either direction (long words only for the
 *  query-contains-title direction to avoid false hits like "spiderman" vs
 *  "Man"), 0.7 within a small Levenshtein tolerance (typos), else 0.0. */
internal fun tokenMatchScore(token: String, titleTokens: List<String>): Double {
    if (titleTokens.any { it == token || it.contains(token) }) return 1.0
    if (titleTokens.any { token.contains(it) && it.length >= 4 }) return 1.0
    val tolerance = if (token.length <= 5) 1 else 2
    if (titleTokens.any { levenshtein(it, token) <= tolerance }) return 0.7
    return 0.0
}

/** Relevance verdict for one search candidate. [score] is in [0,1];
 *  [allTokensMatched] drives the hard "remove every other" gate. */
internal data class Relevance(val score: Double, val allTokensMatched: Boolean)

/** Fuzzy relevance of [query] against a candidate [title] (+ optional release
 *  [year], which pure-digit tokens may match). Score = weighted mean of
 *  per-token matches with a small penalty for bloated titles, clamped [0,1]. */
internal fun relevanceOf(query: String, title: String, year: String?): Relevance {
    val qNorm = normalizeTitle(query)
    if (qNorm.isEmpty()) return Relevance(0.0, false)
    val tNorm = normalizeTitle(title)
    if (qNorm == tNorm) return Relevance(1.0, true)

    val qTokens = significantQueryTokens(query)
    if (qTokens.isEmpty()) {
        // Single-character query: substring containment is the whole signal.
        val contained = tNorm.contains(qNorm)
        return Relevance(if (contained) 1.0 else 0.0, contained)
    }
    val tTokens = tNorm.split(' ').filter { it.isNotEmpty() }

    var sum = 0.0
    var matchedAll = true
    for (token in qTokens) {
        var best = tokenMatchScore(token, tTokens)
        if (best < 1.0 && token.all { it.isDigit() } && year.orEmpty().contains(token)) best = 1.0
        if (best == 0.0) matchedAll = false
        sum += best
    }
    val extraTitleWords = (tTokens.size - qTokens.size).coerceAtLeast(0)
    val score = (sum / qTokens.size - 0.03 * minOf(extraTitleWords, 5)).coerceIn(0.0, 1.0)
    return Relevance(score, matchedAll)
}

/** TMDB image CDN size prefixes that are too small for a poster. Anything from
 *  w92 to w500 is upgraded to `original`; w780/w1280 are kept (already good). */
private val TMDB_SIZE_REGEX = Regex("""/(w92|w154|w185|w342|w500|w780|w1280)/""")

/** Amazon (IMDB) CDN thumbnail suffix, e.g. `_SX250` / `_SY450`. Upgraded to a
 *  larger variant so posters aren't pixelated. */
private val AMAZON_SIZE_REGEX = Regex("""_SX\d{2,4}(?=\.|_|$)""", RegexOption.IGNORE_CASE)

/** Upgrades a thumbnail URL to the full-resolution image by stripping only
 *  genuine thumbnail resize markers (-WxH where W,H are small, TMDB CDN size
 *  prefixes, Amazon _SX* suffixes) and query-size params. Conservative: never
 *  strips -scaled (a real WordPress file variant) and only drops -WxH when both
 *  dims <= 500; otherwise returns the original so a poster is always shown.
 *  Pure function, used for both search and detail posters. */
internal fun upgradePosterUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var fixed = if (url.startsWith("//")) "https:$url" else url
    fixed = stripSmallSizeSuffix(fixed)
    fixed = TMDB_SIZE_REGEX.replace(fixed) { m ->
        when (m.groupValues[1]) {
            "w92", "w154", "w185", "w342", "w500" -> "/original/"
            else -> m.value
        }
    }
    fixed = AMAZON_SIZE_REGEX.replace(fixed, "_SX500")
    val qIdx = fixed.indexOf("?")
    if (qIdx >= 0) {
        val base = fixed.substring(0, qIdx)
        val kept = fixed.substring(qIdx + 1).split("&").mapNotNull { p ->
            val k = p.substringBefore("=").trim()
            if (k.equals("resize", ignoreCase = true) || k.equals("w", ignoreCase = true)
                || k.equals("width", ignoreCase = true)
                || k.equals("h", ignoreCase = true)
                || k.equals("height", ignoreCase = true)
                || k.equals("fit", ignoreCase = true)
                || k.equals("im", ignoreCase = true)
                || k.equals("q", ignoreCase = true)
                || k.equals("quality", ignoreCase = true)
            ) null else p
        }.joinToString("&")
        fixed = if (kept.isBlank()) base else "$base?$kept"
    }
    return fixed
}

/** True when [url] still carries a resize/thumbnail marker that [upgradePosterUrl]
 *  could not remove (e.g. a large -WxH variant that is still smaller than the
 *  original, a TMDB CDN small-size prefix, or an Amazon _SX* suffix). Used to
 *  decide when a search result should be overridden with a known-good
 *  full-resolution poster from TMDB. */
internal fun isThumbnailish(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return Regex("""-\d{2,4}x\d{2,4}""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""/(w92|w154|w185|w342|w500)/""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""_SX\d{2,4}(?=\.|_|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""[?&](resize|w|h|width|height|fit|im|q|quality)=""", RegexOption.IGNORE_CASE).containsMatchIn(url)
}

/** Strips a "-WxH" size marker (e.g. -300x450) only when it represents a
 *  small thumbnail: both dims <= 500. Left intact: -scaled (a real WP file
 *  variant), large dims (the image is already full-res). Returns [url] unchanged
 *  when the marker dims exceed the thumbnail threshold or there is none. */
private fun stripSmallSizeSuffix(url: String): String {
    val m = Regex("""-(\d{2,4})x(\d{2,4})""", RegexOption.IGNORE_CASE).find(url) ?: return url
    val w = m.groupValues[1].toInt()
    val h = m.groupValues[2].toInt()
    if (w > 500 || h > 500) return url
    return url.removeRange(m.range)
}

/** Pull the first `tt\d{7,8}` IMDB id from any text (URL, JSON, HTML). Used
 *  to extract the IMDB id from dooplayer admin-ajax embed URLs, which always
 *  carry the id (e.g. tt1979320) inside the embed_url field. */
internal fun extractImdbIdFromUrl(text: String?): String? =
    text?.let { Regex("""tt\d{7,8}""").find(it)?.value }

/**
 * Retry [fetch] up to [attempts] times. If a result is "blocked" (e.g. a Cloudflare
 * challenge page rather than the real document), [onBlocked] is invoked and the next
 * attempt is tried. If every attempt fails or is blocked, throws an exception built by
 * [failureMessage].
 *
 * This is the core of the detail-page fix: it tries a fixed, finite number of solvers
 * and then surfaces a single error instead of CloudStream's LoadFragment retrying
 * load() forever (the "keep refreshing" loop). Kept CloudStream-free so it is
 * unit-testable on the JVM without an Android/WebView runtime.
 */
internal suspend fun <T> retryUntilSolved(
    attempts: Int,
    fetch: suspend (attempt: Int) -> T,
    isBlocked: (T) -> Boolean,
    onBlocked: () -> Unit = {},
    failureMessage: (Throwable?) -> String,
): T {
    var lastErr: Throwable? = null
    repeat(attempts) { i ->
        try {
            val result = fetch(i)
            if (isBlocked(result)) {
                lastErr = IllegalStateException(failureMessage(null))
                onBlocked()
                return@repeat
            }
            return result
        } catch (e: Throwable) {
            lastErr = e
        }
    }
    throw IllegalStateException(failureMessage(lastErr))
}

/** True when [doc] is the site's Cloudflare interstitial rather than real content. */
internal fun isChallenge(doc: Document): Boolean =
    doc.body()?.text().orEmpty().let { bodyText ->
        val titleText = doc.selectFirst("title")?.text()?.lowercase() ?: ""
        bodyText.contains("just a moment", ignoreCase = true)
            || bodyText.contains("cf-mitigated", ignoreCase = true)
            || bodyText.contains("verify you are human", ignoreCase = true)
            || bodyText.contains("checking your browser", ignoreCase = true)
            || bodyText.contains("attention required", ignoreCase = true)
            || titleText.contains("just a moment", ignoreCase = true)
    }
