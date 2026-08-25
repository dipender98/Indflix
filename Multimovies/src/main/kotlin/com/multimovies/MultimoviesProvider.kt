package com.multimovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Tiny in-memory cache for search results so repeated searches (and quick-search
 *  typing) are instant instead of re-fetching the site. Bounded in size, TTL per
 *  entry — search results don't change minute-to-minute. */
internal object SearchCache {
    private const val TTL_MS = MultimoviesProvider.SEARCH_CACHE_TTL_MS
    private const val MAX_SIZE = 128

    private data class Entry(val results: List<SearchResponse>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(query: String): List<SearchResponse>? {
        val key = key(query)
        val e = cache[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            cache.remove(key)
            return null
        }
        return e.results
    }

    fun put(query: String, results: List<SearchResponse>) {
        val key = key(query)
        if (cache.size >= MAX_SIZE) {
            cache.entries.minByOrNull { it.value.expiresAt }?.key?.let { cache.remove(it) }
        }
        cache[key] = Entry(results, System.currentTimeMillis() + TTL_MS)
    }

    private fun key(query: String) = query.trim().lowercase()
}

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

/** Tries common lazy-load attributes in order so a poster URL is found even when
 * the theme stores the real image in a data-* attribute instead of src. */
private fun Element.posterUrl(): String? =
    selectFirst("img")?.let { img ->
        listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"),
            img.attr("data-original"), img.attr("data-lazyload")).firstOrNull { it.isNotBlank() }
    }?.takeIf { it.isNotBlank() }

/** Cheap, no-network parse of the per-item rating badge (as a number) from
 *  list/search markup. Returns null when absent so search never makes extra
 *  network calls. Pure function, testable on the JVM without Score. */
internal fun parseRating(item: Element): Double? {
    val raw = item.selectFirst(
        "span.dt_rating_vgs, span.imdb, div.imdb-rating, " +
        ".rating span, span.rating, [class*='imdb'] span"
    )?.text()?.trim()
        ?: return null
    return raw.replace(Regex("[^\\d.]"), "")
        .takeIf { it.isNotBlank() }?.toDoubleOrNull()
}

/** Server names as they appear on the Multimovies "Video Sources" list, plus the
 *  direct global sources, ordered by speed/reliability (fastest/most reliable
 *  first). Verified Aug 2026: current titles only expose Cineverse, screenscape.me,
 *  gdmirror/GD, Nxsha and occasionally nhdapi. Cineverse (the modiplay serve_m3u8
 *  proxy) is the verified fast + Hindi source; Nxsha uses the same modiplay/vibuxer
 *  CDN backend but its embed needs JS; screenscape.me is the site's lan=hindi source;
 *  gdmirror is the site's recommended server. Servers not listed here are still
 *  pulled (generic sniffer fallback) but with the lowest priority. */
internal val SOURCE_PRIORITY: List<String> = listOf(
    "Cineverse",
    "screenscape.me",
    "gdmirror",
    "Nxsha",
    "nhdapi",
    "2embed",
)

/** CSS selector for the item containers on a Multimovies search-results page. */
private val SEARCH_ITEMS_SELECTOR = "div#archive-content div.item, div.search-page div.result-item, article.item, div.ml-items div.item, div.results div.result, ul.ml-posts li, div#content div.post, div.items div.item"

// ----------------------------------------------------------------------
// Search relevance engine (pure functions, JVM-testable, no network)
//
// Matching is deliberately NOT word-to-word: tokens match exactly, as
// substrings ("spiderman" hits "Spider-Man", "ave" hits "Avengers") or
// fuzzily via Levenshtein (typos). But the gate is strict — EVERY
// significant query token must match somewhere in the title, so all
// irrelevant "other" catalog hits are removed outright.
// ----------------------------------------------------------------------

/** Unicode-aware normalization (lowercase; letters, marks, digits only) so
 *  Hindi/Devanagari queries survive: vowel signs like ि/ी are combining marks
 *  (Unicode \p{M}), not letters, so they must be kept or scripts get mangled. */
private val NON_ALNUM_UNICODE = Regex("""[^\p{L}\p{M}\p{N}]+""")

internal fun normalizeTitle(t: String): String =
    t.lowercase().trim().replace(NON_ALNUM_UNICODE, " ").trim()

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


/**
 * Multimovies - a CloudStream provider that scrapes the Multimovies (multimovies.motorcycles) site.
 *
 * Source handling policy (per project requirements):
 *  - Multiple streaming sources are pulled in PARALLEL.
 *  - Each source has a hard TIMEOUT of ~30 seconds (SOURCE_TIMEOUT_MS).
 *  - Sources are tried in PRIORITY order (reliable + fast first). The priority list
 *    is defined in [SOURCE_PRIORITY] and is used both to order parallel launches and
 *    to sort the returned links.
 */
class MultimoviesProvider : MainAPI() {

    override var mainUrl = "https://multimovies.motorcycles"
    override var name = "Multimovies"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private val commonHeaders = mapOf("User-Agent" to userAgent)
    // Solves the Cloudflare managed challenge on multimovies.motorcycles. Created
    // on first use (not at plugin install) so CookieManager/WebView setup happens
    // during a network call. Cached so solved cookies persist across calls.
    private var cfKiller: CloudflareKiller? = null
    private fun getCfKiller(): CloudflareKiller {
        return cfKiller ?: CloudflareKiller().also { cfKiller = it }
    }
    /** Fire-and-forget scope for resolving TMDB search hits to Multimovies page
     *  docs in the background (search itself never blocks on Multimovies). */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Maps "tmdbId|type" (fallback "imdbId|type") to the resolved MM page URL. */
    private val imdbUrlCache = ConcurrentHashMap<String, String>()
    /** Memoized detail-page docs keyed by MM URL, so load() never fetches twice. */
    private val mmDocCache = ConcurrentHashMap<String, Document>()
    /** Maps "tmdbId|type" to (name, year) so load() can slug-guess the MM page. */
    private val tmdbSearchCache = ConcurrentHashMap<String, Pair<String, String?>>()
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/movies/", "Movies"),
        Pair("$mainUrl/tvshows/", "TV Shows"),
        Pair("$mainUrl/seasons/", "Seasons"),
        Pair("$mainUrl/genre/bollywood-movies/", "Bollywood"),
        Pair("$mainUrl/genre/hollywood/", "Hollywood"),
        Pair("$mainUrl/genre/south-indian/", "South Indian"),
        Pair("$mainUrl/genre/anime-hindi/", "Hindi Dub Anime"),
        Pair("$mainUrl/trending/", "Top Rated"),
    )

    // ------------------------------------------------------------------
    // Source priority / timeout configuration
    // ------------------------------------------------------------------

    /** Per-source timeout in milliseconds. */
    companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L

        /** In-memory search result cache TTL (ms). Results don't change
         *  minute-to-minute; a longer TTL makes repeat/quick searches instant. */
        const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1000L

        /** Search returns at most this many results. */
        const val SEARCH_MAX_RESULTS = 6

        /** Weighted relevance score a result must clear AFTER passing the hard
         *  every-token-matched gate; anything below is removed outright. */
        const val SEARCH_RELEVANCE_THRESHOLD = 0.5

        /** Worst-case budget for an uncached search before giving up. */
        const val SEARCH_TOTAL_BUDGET_MS = 2500L
    }

    private fun priorityOf(serverName: String): Int {
        val idx = SOURCE_PRIORITY.indexOfFirst { serverName.contains(it, ignoreCase = true) }
        return if (idx == -1) SOURCE_PRIORITY.size else idx
    }

    /**
     * Fetch [url] without the Cloudflare solver first: when solved cookies are
     * already valid (CloudStream persists the cookie jar across calls), this is
     * a fast ~1–3s path. Only if the response is a challenge (or the fast path
     * errors) do we re-request with a fresh [CloudflareKiller] solve.
     *
     * [required]=true (used by [solveDocument] for detail/link pages) surfaces an
     * [ErrorLoadingException] instead of null so CloudStream doesn't retry
     * forever. [required]=false returns null to let callers degrade gracefully.
     */
    internal suspend fun fetchDoc(
        url: String,
        timeoutSeconds: Long = 12,
        required: Boolean = false,
        headers: Map<String, String> = commonHeaders,
    ): Document? {
        val challengeTimeoutS = 15L
        // Fast path: rely on the persisted cookie jar; no WebView solve.
        try {
            val doc = app.get(url, timeout = timeoutSeconds, headers = headers).document
            if (!isChallenge(doc)) return doc
        } catch (e: Exception) {
            // fall through to the challenge-solve path
        }

        // Challenge path: solve with CloudflareKiller (cached or fresh).
        val solved = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            retryUntilSolved(
                attempts = solverFactories().size,
                fetch = { i ->
                    val factory = solverFactories()[i.coerceAtMost(solverFactories().size - 1)]
                    app.get(url, timeout = challengeTimeoutS, headers = headers,
                        interceptor = factory()).document
                },
                isBlocked = ::isChallenge,
                onBlocked = { cfKiller = null },
                failureMessage = { lastErr -> lastErr?.localizedMessage ?: "Failed to load $url" },
            )
        }
        return when {
            solved == null -> {
                if (required) throw ErrorLoadingException("Timed out fetching $url") else null
            }
            isChallenge(solved) -> {
                if (required) throw ErrorLoadingException("Cloudflare challenge unsolved for $url") else null
            }
            else -> solved
        }
    }

    /** CloudflareKiller factories retried in order: cached solver, then a fresh one. */
    private fun solverFactories(): List<() -> CloudflareKiller> = listOf(
        { getCfKiller() },
        { CloudflareKiller().also { cfKiller = it } },
    )

    /** Backward-compatible wrapper used by [load] and [loadLinks]. */
    internal suspend fun solveDocument(
        url: String,
        timeoutSeconds: Long = 15,
    ): Document = fetchDoc(url, timeoutSeconds = timeoutSeconds, required = true)
        ?: throw ErrorLoadingException("Failed to fetch $url")


    // ------------------------------------------------------------------
    // Search (TMDB/SIMKL-driven; Multimovies is only touched in the background
    // to resolve each hit to its real page URL, never for metadata).
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        SearchCache.get(query)?.let { return it }

        // One request to TMDB /search/multi (or SIMKL search when its client_id is
        // set) returns posters + ratings inline — no enrichment round-trips. The
        // strict relevance gate still removes every non-matching hit.
        val ranked: List<Pair<Double, TmdbService.TmdbItem>> =
            withTimeoutOrNull(SEARCH_TOTAL_BUDGET_MS) {
                val raw = TmdbService.search(query)
                if (raw.isEmpty()) return@withTimeoutOrNull null
                raw.mapNotNull { item ->
                    val rel = relevanceOf(query, item.name, item.year)
                    if (!rel.allTokensMatched || rel.score < SEARCH_RELEVANCE_THRESHOLD) null
                    else rel.score to item
                }.sortedByDescending { it.first }
                    .take(SEARCH_MAX_RESULTS)
                    .ifEmpty { null }
            } ?: return null

        // Remember (name, year) per hit so load() can slug-guess its MM page.
        ranked.forEach { (_, item) ->
            item.tmdbId?.let { tmdbSearchCache["$it|${item.type}"] = item.name to item.year }
        }

        val responses = ranked.mapNotNull { (_, item) -> item.toSearchResponse() }

        // Background: resolve each hit's real MM page so load() is instant on tap.
        responses.forEach { r ->
            val parsed = parseTmdbUrl(r.url) ?: return@forEach
            val cached = tmdbSearchCache["${parsed.first}|${parsed.second}"] ?: return@forEach
            searchScope.launch {
                runCatching {
                    resolveMultimoviesDoc(parsed.first, null, parsed.second, cached.first, cached.second)
                }
            }
        }

        if (responses.isEmpty()) return null
        return responses.also { SearchCache.put(query, it) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /** Build a CloudStream search result from a TMDB/SIMKL hit — poster, year and
     *  IMDB rating all come straight from the search payload (no extra network). */
    private fun TmdbService.TmdbItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        if (name.isBlank()) return null
        val url = "https://www.themoviedb.org/${if (type == "movie") "movie" else "tv"}/$id"
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        val releaseYear = year?.toIntOrNull()
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(name, url, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(name, url, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    /** Parse a TMDB web URL into (tmdbId, type). Returns null for non-TMDB URLs. */
    private fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
        return m.groupValues[2].toIntOrNull()?.let { id ->
            id to if (m.groupValues[1] == "movie") "movie" else "series"
        }
    }

    /** Resolve a TMDB hit to its real Multimovies page Document (cached). Slugs are
     *  guessed first so load() avoids the site's `?s=` search endpoint entirely;
     *  the site search is only a fallback when the guess misses. [title] is the
     *  search-result title (from [tmdbSearchCache]); when blank nothing is fetched. */
    private suspend fun resolveMultimoviesDoc(
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        title: String,
        year: String?,
    ): Document? {
        val key = "${tmdbId ?: imdbId ?: "?"}|$type"
        imdbUrlCache[key]?.let { cachedUrl ->
            return mmDocCache[cachedUrl] ?: fetchDoc(cachedUrl, timeoutSeconds = 8, required = false)
        }
        if (title.isBlank()) return null

        // Slug-guess first (/{movies|tvshows}/{slug}-{year}/), validated by title.
        val base = if (type == "movie") "$mainUrl/movies/" else "$mainUrl/tvshows/"
        val slug = title.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val variants = buildList {
            year?.take(4)?.let { y -> add("${slug}-$y") }
            add(slug)
        }
        for (variant in variants.distinct()) {
            if (variant.isBlank()) continue
            val guessUrl = "$base$variant/"
            val guessed = fetchDoc(guessUrl, timeoutSeconds = 6, required = false) ?: continue
            if (isChallenge(guessed)) continue
            val guessedTitle = guessed.selectFirst("h1, div.sheader h1, meta[property=og:title]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()
            if (guessedTitle != null && titleDistance(guessedTitle, title) <= 1) {
                imdbUrlCache[key] = guessUrl
                mmDocCache[guessUrl] = guessed
                return guessed
            }
        }

        // Fallback: site search by title, pick the closest title match.
        val searchDoc = fetchDoc(
            "$mainUrl/?s=${URLEncoder.encode(title, "UTF-8")}",
            timeoutSeconds = 8,
            required = false,
        ) ?: return null
        val candidate = searchDoc.select(SEARCH_ITEMS_SELECTOR).mapNotNull { it.candidateHref() }
            .minByOrNull { titleDistance(it.second, title) } ?: return null
        val detailDoc = mmDocCache[candidate.first]
            ?: fetchDoc(candidate.first, timeoutSeconds = 8, required = false)
        if (detailDoc != null) {
            imdbUrlCache[key] = candidate.first
            mmDocCache[candidate.first] = detailDoc
        }
        return detailDoc
    }

    /** Extract (href, item title) from a Multimovies search-result element. */
    private fun Element.candidateHref(): Pair<String, String>? {
        val a = selectFirst("a[href], div.data a h2, div.poster a") ?: return null
        val href = a.attr("href").takeIf { it.contains(mainUrl) } ?: return null
        val itemTitle = selectFirst("img")?.attr("alt")
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()
            ?: a.text()?.trim()
        return if (itemTitle.isNullOrBlank()) null else href to itemTitle
    }

    private fun titleDistance(itemTitle: String, target: String): Int {
        val a = normalizeTitle(itemTitle)
        val b = normalizeTitle(target)
        return when {
            a == b -> 0
            a.startsWith(b) || b.startsWith(a) -> 1
            else -> 2
        }
    }

    /** Concurrently resolve TMDB posters for main-page results that are missing one
     *  or still carry a thumbnail size marker. Bounded to 3 concurrent lookups with
     *  a 3s per-item timeout, AND an overall 2.5s budget so a page is never stalled
     *  by many poster-less items. */
    private suspend fun backfillPosters(results: List<SearchResponse>) {
        val toFetch = results.mapNotNull { r ->
            val poster = r.posterUrl
            if (poster.isNullOrBlank() || isThumbnailish(poster))
                SearchItem(r, r.name ?: "", r.type ?: TvType.Movie) else null
        }
        if (toFetch.isEmpty()) return
        val semaphore = Semaphore(3)
        withTimeoutOrNull(2500L) {
            coroutineScope {
                toFetch.map { item ->
                    async {
                        semaphore.acquire()
                        try {
                            withTimeoutOrNull(3000L) {
                                val type = if (item.tvType == TvType.Movie) "movie" else "series"
                                TmdbService.search(item.title).firstOrNull { it.type == type }?.poster
                            }?.takeIf { it.isNotBlank() }
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll().forEachIndexed { idx, url ->
                    if (url != null) toFetch[idx].response.posterUrl = url
                }
            }
        }
    }

    private data class SearchItem(
        val response: SearchResponse,
        val title: String,
        val tvType: TvType,
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a[href], div.data a h2, div.poster a") ?: return null
        val href = a.attr("href").takeIf { it.contains(mainUrl) } ?: return null
        val title = selectFirst("img")?.attr("alt")
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()
            ?: a.text()
            ?.trim()
            ?: return null
        val poster = upgradePosterUrl(posterUrl())
        val isMovie = href.contains("/movies/")
        val isSeries = href.contains("/tvshows/") || href.contains("/seasons/")
        val tvType = when {
            isSeries -> TvType.TvSeries
            isMovie -> TvType.Movie
            else -> TvType.TvSeries
        }
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                parseRating(this@toSearchResponse)?.let { Score.from10(it.toString()) }?.let { this.score = it }
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                parseRating(this@toSearchResponse)?.let { Score.from10(it.toString()) }?.let { this.score = it }
            }
        }
    }

    // ------------------------------------------------------------------
    // Main page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = fetchDoc(url, timeoutSeconds = 12, required = false) ?: return null
        val items = doc.select("article.item, div#archive-content div.item, div.items div.item").mapNotNull {
            it.toSearchResponse()
        }
        backfillPosters(items)
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------------
    // Load (detail page)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        // Search results arrive as TMDB web URLs; main-page cards arrive as MM URLs.
        val tmdb = parseTmdbUrl(url)
        val cached = tmdb?.let { (id, type) -> tmdbSearchCache["$id|$type"] }

        return coroutineScope {
            // MM page (stream sources only) and TMDB metadata resolve in parallel.
            val pageJob = async {
                if (tmdb != null) {
                    resolveMultimoviesDoc(tmdb.first, null, tmdb.second, cached?.first.orEmpty(), cached?.second)
                } else {
                    runCatching { solveDocument(url) }.getOrNull()
                }
            }
            val metaJob = async {
                if (tmdb != null) withTimeoutOrNull(6000L) { TmdbService.fetchMeta(tmdb.first, tmdb.second) } else null
            }

            val detail = metaJob.await()
            var doc = pageJob.await()

            // A pasted TMDB URL with no cached title: retry page resolution with the
            // real title once metadata arrives.
            if (doc == null && tmdb != null && detail?.name != null) {
                doc = resolveMultimoviesDoc(tmdb.first, null, tmdb.second, detail.name, detail.year)
            }
            doc ?: throw ErrorLoadingException("Could not match $url to a Multimovies page")
            // The real MM URL is what loadLinks() receives; never fall back to the
            // TMDB search URL for search taps.
            val realUrl = doc.location()?.takeIf { it.isNotBlank() }
                ?: (if (tmdb != null) imdbUrlCache["${tmdb.first}|${tmdb.second}"] else null)
                ?: url

            val isMovie = tmdb?.second == "movie" || realUrl.contains("/movies/")
            val pageType = if (isMovie) "movie" else "series"

            // Direct MM page (main-page card): ids come from the page markup, then
            // metadata is fetched from TMDB; the dooplayer embed URL is a last resort.
            var resolvedDetail = detail
            if (tmdb == null && resolvedDetail == null) {
                val imdbFromPage = TmdbService.extractImdbId(doc)
                val tmdbFromPage = TmdbService.extractTmdbId(doc)?.toIntOrNull()
                resolvedDetail = withTimeoutOrNull(4000L) {
                    tmdbFromPage?.let { TmdbService.fetchMeta(it, pageType) }
                        ?: imdbFromPage?.let { imdb ->
                            TmdbService.findByImdb(imdb)?.let { (id, t) -> TmdbService.fetchMeta(id, t) }
                        }
                        ?: firstEmbedImdbId(doc)?.let { imdb ->
                            TmdbService.findByImdb(imdb)?.let { (id, t) -> TmdbService.fetchMeta(id, t) }
                        }
                }
            }

            val tmdbId = resolvedDetail?.tmdbId ?: tmdb?.first
            val imdbId = resolvedDetail?.imdbId
                ?: if (tmdb == null) TmdbService.extractImdbId(doc) else null

            // Page-scraped fallbacks (only when TMDB gave nothing).
            val pageTitle = doc.selectFirst("h1, div.sheader h1, meta[property=og:title]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()
            val title = resolvedDetail?.name ?: pageTitle
                ?: throw ErrorLoadingException("No title found on $realUrl")
            val poster = resolvedDetail?.poster ?: upgradePosterUrl(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("div.poster img, img.wp-post-image")?.attr("src")
            )
            val year = resolvedDetail?.year?.toIntOrNull()
                ?: doc.selectFirst("span.date, .year, .extra span")?.text()
                    ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
            val plot = resolvedDetail?.overview ?: doc.selectFirst("div.wp-content, div.description, .wp-content p")?.text()
                ?.replace("Overview:", "")?.trim()
            val tags = resolvedDetail?.genres
                ?: doc.select("div.sgeneros a, .genre a, .sgeneros a").mapNotNull { it.text() }
            val score = resolvedDetail?.rating
            val pageScore = doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
                ?.removePrefix("IMDb:")?.trim()?.toDoubleOrNull()

            // Stash ids for loadLinks() (movie page + every TV episode page).
            if (imdbId != null || tmdbId != null) {
                SourceMetaCache.put(realUrl, SourceMeta(imdbId ?: "", tmdbId?.toString(), null, null))
            }

            if (isMovie) {
                newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = resolvedDetail?.backdrop
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = resolvedDetail?.cast
                    imdbId?.let { addImdbId(it) }
                    (score ?: pageScore)?.let { addScore(it.toString(), 10) }
                }
            } else {
                // TV / Seasons: episode LINKS come from the MM season pages (streams
                // only); titles/descriptions/thumbnails/ratings come from TMDB.
                val episodes = arrayListOf<Episode>()
                val seasonLinks = doc.select("a[href*='/seasons/']")
                    .mapNotNull { it.attr("href").takeIf { h -> h.contains(mainUrl) } }
                    .distinct()
                val pages = if (seasonLinks.isEmpty()) listOf(realUrl) else seasonLinks

                val seasonDocs = coroutineScope {
                    val sem = Semaphore(4)
                    pages.map { seasonUrl ->
                        async {
                            sem.acquire()
                            try {
                                fetchDoc(seasonUrl, timeoutSeconds = 10, required = false)
                            } finally {
                                sem.release()
                            }
                        }
                    }.awaitAll()
                }

                val seasonNums = mutableSetOf<Int>()
                seasonDocs.forEach { sDoc ->
                    if (sDoc == null) return@forEach
                    sDoc.select("ul.episodios li, div.eps div.ep, .episodios li").forEachIndexed { i, ep ->
                        val epLink = ep.selectFirst("a[href]")?.attr("href")?.takeIf { it.contains(mainUrl) }
                            ?: return@forEachIndexed
                        val epNum = Regex("(?i)(\\d+)x(\\d+)").find(epLink)?.groupValues?.getOrNull(2)?.toIntOrNull()
                            ?: Regex("(\\d+)").find(epLink)?.value?.toIntOrNull() ?: (i + 1)
                        val seasonNum = Regex("(?i)(\\d+)x(\\d+)").find(epLink)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                        val epTitle = ep.selectFirst(".episodiotitle a, .title, a")?.text()?.trim()
                        val ep = newEpisode(epLink) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                        }
                        episodes.add(ep)
                        seasonNums.add(seasonNum)
                        if (imdbId != null || tmdbId != null) {
                            SourceMetaCache.put(epLink, SourceMeta(imdbId ?: "", tmdbId?.toString(), seasonNum, epNum))
                        }
                    }
                }

                // TMDB episode enrichment (parallel, bounded, best-effort).
                if (tmdbId != null && seasonNums.isNotEmpty()) {
                    val epMeta = TmdbService.fetchEpisodes(tmdbId, seasonNums)
                    episodes.forEach { ep ->
                        epMeta[ep.season to ep.episode]?.let { m ->
                            if (ep.name.isNullOrBlank()) ep.name = m.name
                            ep.description = m.overview
                            m.released?.let { ep.addDate(it) }
                            m.thumbnail?.let { ep.posterUrl = it }
                            m.rating?.let { ep.score = Score.from10(it) }
                        }
                    }
                }

                newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = resolvedDetail?.backdrop
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = resolvedDetail?.cast
                    imdbId?.let { addImdbId(it) }
                    (score ?: pageScore)?.let { addScore(it.toString(), 10) }
                }
            }
        }
    }

    /** Resolve the IMDB id from the site's first (non-trailer) dooplayer embed
     *  URL. Every dooplayer admin-ajax response carries an embed_url that embeds
     *  the IMDB id (e.g. tt1979320), so this is a very reliable fallback when the
     *  detail page doesn't expose the id directly. */
    private suspend fun firstEmbedImdbId(doc: Document): String? {
        val option = doc.selectFirst("li.dooplay_player_option:not([data-nume='trailer'])") ?: return null
        val post = doc.selectFirst("meta#dooplay-ajax-counter")?.attr("data-postid")
            ?.takeIf { it.isNotBlank() } ?: option.attr("data-post").takeIf { it.isNotBlank() } ?: return null
        val nume = option.attr("data-nume").takeIf { it.isNotBlank() } ?: return null
        val type = option.attr("data-type").takeIf { it.isNotBlank() } ?: "movie"
        val resp = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = commonHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to mainUrl,
                ),
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type,
                ),
                referer = mainUrl,
                timeout = 5,
                interceptor = getCfKiller(),
            ).text
        }.getOrNull() ?: return null
        return extractImdbIdFromUrl(resp)
    }


    // ------------------------------------------------------------------
    // Load links - parallel pulling with per-source 30s timeout + priority
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var meta = SourceMetaCache.get(data)

        // Fast path: cached links emit instantly so playback starts right away
        // (like other plugins). The LinkCache TTL is short (5 min) so stale URLs
        // don't linger; no liveness probe delays the first frame.
        if (meta != null) {
            LinkCache.get(meta.imdbId, meta.season, meta.episode)?.let { cached ->
                if (cached.isNotEmpty()) {
                    cached.forEach { runCatching { callback(it) } }
                    return true
                }
            }
        }

        val doc = try {
            solveDocument(data)
        } catch (e: Exception) {
            null
        } ?: return false

        // Each "Video Source" on a Multimovies episode page is a
        // li.dooplayer_player_option carrying data-nume (source index) and data-type.
        // The real embed URL comes from the site's dooplayer admin-ajax endpoint,
        // keyed by the post id. The post id is Dooplay-standard in
        // <meta id="dooplay-ajax-counter" data-postid="...">; the <li> data-post
        // is a fallback when the meta tag is absent.
        val postId = doc.selectFirst("meta#dooplay-ajax-counter")
            ?.attr("data-postid")
            ?.takeIf { it.isNotBlank() }
        val options = doc.select("ul#playeroptionsul li.dooplay_player_option, li.dooplay_player_option")
            .mapNotNull { li ->
                val name = li.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
                val post = postId ?: li.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nume = li.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = li.attr("data-type").takeIf { it.isNotBlank() }
                    ?: if (data.contains("/movies/")) "movie" else "tv"
                name to Triple(post, nume, type)
            }

        // Resolve every dooplayer server's embed URL in parallel (admin-ajax).
        // The embed URLs also carry the IMDB id, which recovers meta when load()
        // never resolved one.
        val embeds = coroutineScope {
            options.map { (name, triple) ->
                async {
                    runCatching { resolveEmbed(data, name, triple.first, triple.second, triple.third) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        if (meta == null) {
            embeds.firstNotNullOfOrNull { extractImdbIdFromUrl(it.url) }?.let { id ->
                meta = SourceMeta(id, null, parseSeason(data), parseEpisode(data))
                SourceMetaCache.put(data, meta)
            }
        }

        // Streaming pipeline: every source (global id-based + each dooplayer embed)
        // is unwrapped and pulled concurrently, so the fastest working source emits
        // its link first — CloudStream's loading dialog stays visible while slower
        // sources resolve and populates the source list the user can switch between.
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val sourceRefs = Collections.synchronizedList(mutableListOf<MultiSourcePuller.Source>())

        // Streaming pipeline: every source (global id-based + each dooplayer embed)
        // is pulled concurrently, launched in priority order so Cineverse (the
        // reliable/fast Hindi source) starts resolving and emits its link first;
        // lower-priority fallbacks join as they resolve.
        val globalSources = buildGlobalSources(meta)
        val orderedEmbeds = embeds.sortedBy { priorityOf(it.name) }

        coroutineScope {
            val embedJobs = orderedEmbeds.map { e ->
                async {
                    val finalUrl = MultiSourcePuller.unwrapEmbed(e.url, referer = data, headers = commonHeaders)
                    val src = MultiSourcePuller.Source(
                        name = e.name,
                        url = finalUrl,
                        referer = data,
                        headers = commonHeaders,
                        latencyMs = e.latencyMs,
                    )
                    sourceRefs.add(src)
                    pullSource(src, data, emitted, found, subtitleCallback, callback)
                }
            }
            val globalJobs = globalSources.map { g ->
                sourceRefs.add(g)
                async {
                    pullSource(g, data, emitted, found, subtitleCallback, callback)
                }
            }
            (embedJobs + globalJobs).awaitAll()
        }

        val sorted = MultiSourcePuller.sortLinks(found.toList(), sourceRefs.toList(), ::priorityOf, preferHindi = true)
        val deduped = dedupeByHostQuality(sorted)
        if (meta != null) LinkCache.put(meta.imdbId, meta.season, meta.episode, deduped)

        return deduped.isNotEmpty()
    }

    /** Pull a single source, streaming found links to [callback] as they arrive and
     *  collecting them (deduped at emission time) into [found] for the final sort
     *  and link cache. */
    private suspend fun pullSource(
        src: MultiSourcePuller.Source,
        data: String,
        emitted: MutableSet<String>,
        found: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): List<ExtractorLink> = MultiSourcePuller.pull(
        sources = listOf(src),
        timeoutMs = SOURCE_TIMEOUT_MS,
        priorityOf = ::priorityOf,
        onSubtitle = subtitleCallback,
        onLink = { l ->
            val key = "${hostOf(l.url ?: "")}|${l.quality}"
            if (emitted.add(key)) {
                found.add(l)
                runCatching { callback(l) }
            }
        },
    )

    private data class ResolvedEmbed(val name: String, val url: String, val latencyMs: Long)

    /** Resolve a single dooplayer server's embed URL via the site's admin-ajax
     *  endpoint, measuring the round-trip latency as a speed hint. */
    private suspend fun resolveEmbed(data: String, name: String, post: String, nume: String, type: String): ResolvedEmbed? {
        val startMs = System.currentTimeMillis()
        val resp = runCatching {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = commonHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data,
                ),
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type,
                ),
                referer = data,
                timeout = 6,
                interceptor = getCfKiller(),
            ).text
        }.getOrNull() ?: return null
        val latencyMs = System.currentTimeMillis() - startMs

        val rawEmbed = Regex("\"embed_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(resp)?.groupValues?.get(1)
            ?: return null
        val embed = cleanEmbedUrl(rawEmbed).takeIf { it.isNotBlank() } ?: return null
        return ResolvedEmbed(name, embed, latencyMs)
    }

    private fun parseSeason(url: String): Int? =
        Regex("(?i)(\\d+)x\\d+").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseEpisode(url: String): Int? =
        Regex("(?i)\\d+x(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Build dooplayer-independent direct sources from the ids cached during load(). */
    private fun buildGlobalSources(meta: SourceMeta?): List<MultiSourcePuller.Source> {
        if (meta == null) return emptyList()
        return GlobalSources.list.mapNotNull { g ->
            val id = when (g.idType) {
                SourceId.IMDB -> meta.imdbId
                SourceId.TMDB -> meta.tmdbId
            } ?: return@mapNotNull null
            val url = g.buildUrl(id, meta.season, meta.episode) ?: return@mapNotNull null
            MultiSourcePuller.Source(
                name = g.name,
                url = url,
                referer = url,
                headers = commonHeaders + g.headers,
                tmdbId = meta.tmdbId,
                season = meta.season,
                episode = meta.episode,
                latencyMs = g.priority.toLong(),
            )
        }
    }

    /** Normalize a raw dooplayer embed_url: unescape JSON slashes/quotes, pull the
     *  iframe src when the value is an HTML snippet, and HTML-decode entities
     *  (`&amp;` -> `&`) that the admin-ajax JSON embeds inside query strings.
     *  Without the entity decode, multi-param embeds like
     *  `?imdb=tt1&amp;type=movie` break because the server sees a literal
     *  `&amp;` separator instead of `&`. */
    private fun cleanEmbedUrl(raw: String): String {
        var url = raw.replace("\\/", "/").replace("\\\"", "\"").trim()
        if (url.contains("<iframe", ignoreCase = true)) {
            url = Jsoup.parseBodyFragment(url).selectFirst("iframe")?.attr("src")?.trim().orEmpty()
        }
        if (url.isBlank()) return ""
        return if (url.contains("&amp;", ignoreCase = true) || url.contains("&#038;", ignoreCase = true)) {
            Jsoup.parseBodyFragment(url).text()
        } else {
            url
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").lowercase()

    /** Keep only the highest-ranked link per (host, quality) — avoids duplicate
     *  entries when the same final host is reached through multiple paths. */
    private fun dedupeByHostQuality(links: List<ExtractorLink>): List<ExtractorLink> {
        val seen = HashSet<String>()
        return links.filter { l -> seen.add("${hostOf(l.url ?: "")}|${l.quality}") }
    }
}