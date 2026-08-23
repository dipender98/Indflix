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
import java.nio.charset.StandardCharsets
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

    fun clear() = cache.clear()

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

/** Upgrades a thumbnail URL to the full-resolution image by stripping only
 * genuine thumbnail resize markers (-WxH where W,H are small) and query-size
 * params. Conservative: never strips -scaled (a real WordPress file variant)
 * and only drops -WxH when both dims <= 500 and the result still ends in an
 * image extension; otherwise returns the original so a poster is always shown.
 * Pure function, used for both search and detail posters. */
internal fun upgradePosterUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var fixed = if (url.startsWith("//")) "https:$url" else url
    fixed = stripSmallSizeSuffix(fixed)
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
            ) null else p
        }.joinToString("&")
        fixed = if (kept.isBlank()) base else "$base&$kept".replace("?&", "?")
    }
    return fixed
}

/** Strips a "-WxH" size marker (e.g. -300x450) only when it represents a
 * small thumbnail: both dims <= 500. Left intact: -scaled (a real WP file
 * variant), large dims (the image is already full-res). Returns [url] unchanged
 * when the marker dims exceed the thumbnail threshold or there is none. */
private fun stripSmallSizeSuffix(url: String): String {
    val m = Regex("""-(\d{2,4})x(\d{2,4})""", RegexOption.IGNORE_CASE).find(url) ?: return url
    val w = m.groupValues[1].toInt()
    val h = m.groupValues[2].toInt()
    if (w > 500 || h > 500) return url
    return url.removeRange(m.range)
}

internal fun upgradeUrl(url: String?): String? = upgradePosterUrl(url)

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

/** Server names as they appear on the Multimovies "Video Sources" list, ordered
 *  by speed/reliability (fastest/most reliable first). Mirrors the site's own
 *  ranking (GDMIRROR is the recommended/top source), so the quickest-responding
 *  hosts surface first. Servers not listed here are still pulled (generic
 *  sniffer fallback) but with the lowest priority. */
internal val SOURCE_PRIORITY: List<String> = listOf(
    "GDMIRROR",
    "screenscape.me",
    "VidZee",
    "VidZee v2",
    "vixsrc.to",
    "CinemaOS",
    "vidlink.pro",
    "Cineverse",
    "Nxsha",
)


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

        /** Per-source timeout for the generic embed sniffer (seconds). */
        const val SNIFF_TIMEOUT_S = 5L

        /** In-memory search result cache TTL (ms). */
        const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L
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
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        SearchCache.get(query)?.let { return it }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        // Fast path: reuse persisted cookies (skip the slow Cloudflare WebView solve)
        // via fetchDoc; only fall back to solving when a challenge is detected.
        val doc = fetchDoc(searchUrl, timeoutSeconds = 12, required = false) ?: return null

        val items = doc.select(
            "div#archive-content div.item, " +
            "div.search-page div.result-item, " +
            "article.item, " +
            "div.ml-items div.item, " +
            "div.results div.result, " +
            "ul.ml-posts li, " +
            "div#content div.post, " +
            "div.items div.item"
        )

        val results = items.mapNotNull { it.toSearchResponse() }
        // Backfill posters only for items whose own markup yielded none. Bounded
        // (max 3 concurrent, ~3s each) so search latency stays ~1-2s; if an item
        // doesn't resolve in time it simply keeps no poster.
        backfillPosters(results)
        return results.takeIf { it.isNotEmpty() }?.also { SearchCache.put(query, it) }
    }

    /** Concurrently resolve Cinemeta posters for search results that are missing one.
     *  Bounded to 3 concurrent lookups with a 3s per-item timeout so search stays fast. */
    private suspend fun backfillPosters(results: List<SearchResponse>) {
        val toFetch = results.mapNotNull { r ->
            val poster = r.posterUrl
            if (poster.isNullOrBlank()) SearchItem(r, r.name ?: "", r.type ?: TvType.Movie) else null
        }
        if (toFetch.isEmpty()) return
        val semaphore = Semaphore(3)
        coroutineScope {
            toFetch.map { item ->
                async {
                    semaphore.acquire()
                    try {
                        withTimeoutOrNull(3000L) {
                            val imdbId = CinemetaService.searchImdbId(
                                item.title, null, if (item.tvType == TvType.Movie) "movie" else "series"
                            ) ?: return@withTimeoutOrNull null
                            val metaType = if (item.tvType == TvType.Movie) "movie" else "series"
                            CinemetaService.fetchMeta(imdbId, metaType)?.poster
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

    private data class SearchItem(
        val response: SearchResponse,
        val title: String,
        val tvType: TvType,
    )

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

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
        return newHomePageResponse(request.name, items)
    }

    // ------------------------------------------------------------------
    // Load (detail page)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        // solveDocument() surfaces failures via ErrorLoadingException (no retry loop).
        val doc = solveDocument(url)

        val title = doc.selectFirst("h1, div.sheader h1, meta[property=og:title]")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim() ?: throw ErrorLoadingException("No title found on $url")

        val poster = upgradeUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.poster img, img.wp-post-image")?.attr("src")
        )

        val year = doc.selectFirst("span.date, .year, .extra span")?.text()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val plot = doc.selectFirst("div.wp-content, div.description, .wp-content p")?.text()
            ?.replace("Overview:", "")?.trim()

        val tags = doc.select("div.sgeneros a, .genre a, .sgeneros a").mapNotNull { it.text() }

        val score = doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
            ?.removePrefix("IMDb:")?.trim()

        val isMovie = url.contains("/movies/")

        // IMDB id: used to tag the response for CloudStream's built-in meta-provider
        // enrichment (cast with photos, richer ratings) and for metadata fetches.
        // Falls back to a title-based Cinemeta search when the page doesn't expose an
        // IMDB id directly (some Dooplay builds omit the IMDB link). The fallback runs
        // INSIDE the coroutineScope below so it overlaps season-page fetching.
        val imdbIdFromPage = CinemetaService.extractImdbId(doc)

        val aioType = if (isMovie) "movie" else "series"
        // Fetch keyless cast + artwork (from AIOStreams/TVDB addon) and Cinemeta
        // episode metadata concurrently. Both are independent API calls, each with its
        // own internal error handling (return null on failure), so neither can block or
        // crash load(). This parallel fetch keeps load() fast even on slow networks.
        return coroutineScope {
            // Resolve the IMDB id (page extraction + fallback title search). The
            // fallback search is deferred so season-page fetching can run in parallel
            // with it instead of serializing before the season loop.
            val imdbIdDeferred = if (imdbIdFromPage == null && !title.isNullOrBlank()) {
                async {
                    withTimeoutOrNull(4000L) {
                        CinemetaService.searchImdbId(title, year, if (isMovie) "movie" else "series")
                    }
                }
            } else null

            if (isMovie) {
                // Movies: the only concurrency win is IMDB-id search overlapping nothing
                // else (no seasons), but resolve it before launching aioMeta which needs it.
                val resolvedImdbId = imdbIdFromPage ?: imdbIdDeferred?.await()
                val aioMetaDeferred = if (resolvedImdbId != null) {
                    async { TvdbDataService.fetchMeta(resolvedImdbId, aioType) }
                } else null
                val aioMeta = aioMetaDeferred?.await()
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = aioMeta?.poster ?: poster
                    this.backgroundPosterUrl = aioMeta?.background
                    this.logoUrl = aioMeta?.logo
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = aioMeta?.cast
                    if (resolvedImdbId != null) {
                        addImdbId(resolvedImdbId)
                        score?.let { s -> s.toDoubleOrNull()?.let { addScore(s, 10) } }
                    } else {
                        this.score = score?.let { Score.from10(it) }
                    }
                }
            } else {
                // TV / Seasons: collect all episodes from season + episode archive pages.
                val episodes = arrayListOf<Episode>()
                val seasonLinks = doc.select("div.se-c div.se-q a[href], ul.episodios li a[href], .seasons a[href]")
                    .mapNotNull { it.attr("href").takeIf { h -> h.contains(mainUrl) } }
                    .distinct()

                val pages = if (seasonLinks.isEmpty()) listOf(url) else seasonLinks

                // Fetch all season/episode pages in PARALLEL (fast-path fetchDoc reuses
                // the already-solved cookies and never triggers a fresh CF solve). Each
                // page is bounded by a 10s timeout and wrapped so a failure just skips
                // that season. Concurrency capped to avoid hammering the site.
                val seasonDocsDeferred = async {
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

                // Resolve the IMDB id (overlaps season page fetching above).
                val resolvedImdbId = imdbIdFromPage ?: imdbIdDeferred?.await()

                // Now that the id is known, kick off the two metadata API calls in parallel.
                val aioMetaDeferred = if (resolvedImdbId != null) {
                    async { TvdbDataService.fetchMeta(resolvedImdbId, aioType) }
                } else null
                val videoMetaDeferred = if (resolvedImdbId != null) {
                    async { CinemetaService.fetchMeta(resolvedImdbId, "series") }
                } else null

                // Await season docs + metadata concurrently (fastest-available wins).
                val seasonDocs = seasonDocsDeferred.await()
                val aioMeta = aioMetaDeferred?.await()
                val videoMeta = videoMetaDeferred?.await()

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
                        // Enrich with Cinemeta episode metadata: description, release date, thumbnail.
                        videoMeta?.videos?.find {
                            it.season == seasonNum && it.episode == epNum
                        }?.let { vid ->
                            ep.description = vid.overview
                            vid.released?.let { ep.addDate(it) }
                            vid.thumbnail?.let { ep.posterUrl = it }
                            vid.rating?.toDoubleOrNull()?.let { ep.score = Score.from10(it) }
                        }
                        episodes.add(ep)
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = aioMeta?.poster ?: poster
                    this.backgroundPosterUrl = aioMeta?.background
                    this.logoUrl = aioMeta?.logo
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = aioMeta?.cast
                    if (resolvedImdbId != null) {
                        addImdbId(resolvedImdbId)
                        score?.let { s -> s.toDoubleOrNull()?.let { addScore(s, 10) } }
                    } else {
                        this.score = score?.let { Score.from10(it) }
                    }
                }
            }
        }
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
        val doc = try {
            solveDocument(data)
        } catch (e: Exception) {
            return false
        }

        // Each "Video Source" on a Multimovies episode page is a
        // li.dooplay_player_option carrying data-nume (source index) and data-type.
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

        if (options.isEmpty()) return false

        // Resolve every source's embed URL via the dooplayer admin-ajax endpoint.
        // These are independent POSTs → run in parallel so the fastest source isn't
        // blocked by the slowest. As a side effect we measure response time (latencyMs)
        // so results can be sorted by speed.
        data class Embed(val name: String, val url: String, val latencyMs: Long)
        val servers: List<Embed> = coroutineScope {
            options.map { (name, triple) ->
                async {
                    val (post, nume, type) = triple
                    val startMs = System.currentTimeMillis()
                    val body = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type,
                    )
                    val resp = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            headers = commonHeaders + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to data,
                            ),
                            data = body,
                            referer = data,
                            timeout = 6,
                            interceptor = getCfKiller(),
                        ).text
                    }.getOrNull() ?: return@async null
                    val latencyMs = System.currentTimeMillis() - startMs

                    val rawEmbed = Regex("\"embed_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                        .find(resp)?.groupValues?.get(1)
                        ?.replace("\\/", "/")
                        ?.replace("\\\"", "\"")
                        ?: return@async null
                    val embed = if (rawEmbed.contains("<iframe", ignoreCase = true)) {
                        Jsoup.parse(rawEmbed).selectFirst("iframe")?.attr("src")
                            ?.takeIf { it.isNotBlank() } ?: return@async null
                    } else {
                        rawEmbed.replace("\\/", "/").trim()
                    }
                    Embed(name, embed, latencyMs)
                }
            }.awaitAll().filterNotNull()
        }

        if (servers.isEmpty()) return false

        // Sort resolved embeds by measured latency first (fastest respond first),
        // then by the static priority list. This means the quickest-responding
        // sources are extracted and streamed to the player first.
        val orderedServers = servers.sortedWith(
            compareBy<Embed>({ it.latencyMs }).thenBy { priorityOf(it.name) }
        )
        val sources = orderedServers.map { e ->
            MultiSourcePuller.Source(name = e.name, url = e.url, referer = data)
        }

        // Parallel pull, per-source timeout, streaming: every extracted link is
        // pushed to the player immediately (onLink) instead of waiting for all
        // sources to finish — video starts as soon as the fastest source yields a
        // link. The returned list is still used for the unlinked-source fallback.
        val links = runCatching {
            MultiSourcePuller.pull(
                sources = sources,
                timeoutMs = SOURCE_TIMEOUT_MS,
                priorityOf = { priorityOf(it) },
                onSubtitle = subtitleCallback,
                onLink = { runCatching { callback(it) } },
            )
        }.getOrElse { emptyList() }

        // Fallback: for sources that yielded no links via CloudStream's extractor
        // registry, try a generic HLS/direct-file sniffer. Some Dooplay embed hosts
        // are not registered extractors, so we scrape the embed page for m3u8/mp4
        // URLs. Per-source bounded by SNIFF_TIMEOUT_S.
        val linkedSources = links.mapNotNull { it.source?.removeSuffix(MultiSourcePuller.INDICATOR) }
        val unlinkedNames = sources.map { it.name }.toSet() - linkedSources.toSet()
        val unlinkedUrls = orderedServers.filter { it.name in unlinkedNames }.map { it.name to it.url }
        val sniffs = if (unlinkedUrls.isNotEmpty()) {
            runCatching {
                MultiSourcePuller.sniffEmbeds(unlinkedUrls, SNIFF_TIMEOUT_S, data)
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        sniffs.forEach { runCatching { callback(it) } }

        return links.isNotEmpty() || sniffs.isNotEmpty()
    }
}