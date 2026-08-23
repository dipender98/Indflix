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
 * could not remove (e.g. a large -WxH variant that is still smaller than the
 * original, or a CDN resize query token). Used to decide when a search result
 * should be overridden with a known-good full-resolution poster from Cinemeta. */
internal fun isThumbnailish(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return Regex("""-\d{2,4}x\d{2,4}""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""[?&](resize|w|h|width|height|fit|im|q|quality)=""", RegexOption.IGNORE_CASE).containsMatchIn(url)
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

/** Server names as they appear on the Multimovies "Video Sources" list, plus the
 *  direct global sources, ordered by speed/reliability (fastest/most reliable
 *  first). GDMIRROR is the recommended/top dooplayer source; vixsrc.to (direct,
 *  no-JS HLS) is the fastest global source. Servers not listed here are still
 *  pulled (generic sniffer fallback) but with the lowest priority. */
internal val SOURCE_PRIORITY: List<String> = listOf(
    "GDMIRROR",
    "vixsrc.to",
    "autoembed",
    "2embed",
    "vidlink.pro",
    "multiembed",
    "screenscape.me",
    "VidZee",
    "VidZee v2",
    "CinemaOS",
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

    /** Concurrently resolve Cinemeta posters for search results that are missing one
     *  or still carry a thumbnail size marker. Bounded to 3 concurrent lookups with
     *  a 3s per-item timeout so search stays fast. */
    private suspend fun backfillPosters(results: List<SearchResponse>) {
        val toFetch = results.mapNotNull { r ->
            val poster = r.posterUrl
            if (poster.isNullOrBlank() || isThumbnailish(poster))
                SearchItem(r, r.name ?: "", r.type ?: TvType.Movie) else null
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
        val aioType = if (isMovie) "movie" else "series"

        val imdbIdFromPage = CinemetaService.extractImdbId(doc)
        val tmdbIdFromPage = CinemetaService.extractTmdbId(doc)

        // The IMDB id is the linchpin for meta-provider enrichment (cast photos,
        // episode ratings/thumbnails) AND for building direct stream hosts. Resolve
        // it concurrently with season-page fetching. No external metadata API is
        // awaited inside load() so the response renders fast (progressive loading):
        // episodes appear immediately, then the app's meta-providers fill cast,
        // artwork, episode ratings and thumbnails via addImdbId.
        return coroutineScope {
            val imdbIdJob = async {
                imdbIdFromPage ?: if (title.isNotBlank()) {
                    runCatching {
                        withTimeoutOrNull(4000L) {
                            CinemetaService.searchImdbId(title, year, aioType)
                        }
                    }.getOrNull()
                } else null
            }

            if (isMovie) {
                val resolvedImdbId = imdbIdJob.await()
                if (resolvedImdbId != null) {
                    SourceMetaCache.put(url, SourceMeta(resolvedImdbId, tmdbIdFromPage, null, null))
                }
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
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
                val seasonDocsJob = async {
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

                val resolvedImdbId = imdbIdJob.await()
                val seasonDocs = seasonDocsJob.await()

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
                        // Stash ids + season/episode keyed by the exact url loadLinks() will receive.
                        if (resolvedImdbId != null) {
                            SourceMetaCache.put(epLink, SourceMeta(resolvedImdbId, tmdbIdFromPage, seasonNum, epNum))
                        }
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
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
        val meta = SourceMetaCache.get(data)

        // Fast path: cached links for this title/episode (no probing, no CF solve).
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

        // Path A: dooplayer-independent direct sources built from the resolved ids.
        val globalSources = buildGlobalSources(meta)
        // Path B: dooplayer-resolved embeds (admin-ajax + recursive iframe unwrap).
        val dooplayerSources = if (options.isNotEmpty()) resolveDooplayerSources(data, options) else emptyList()

        val sources = globalSources + dooplayerSources
        if (sources.isEmpty()) return false

        // Dedupe at emit time so a host reached through multiple paths surfaces once.
        val emitted = Collections.synchronizedSet(HashSet<String>())

        val links = runCatching {
            MultiSourcePuller.pull(
                sources = sources,
                timeoutMs = SOURCE_TIMEOUT_MS,
                priorityOf = { priorityOf(it) },
                onSubtitle = subtitleCallback,
                onLink = { l ->
                    val key = "${hostOf(l.url ?: "")}|${l.quality}"
                    if (emitted.add(key)) runCatching { callback(l) }
                },
            )
        }.getOrElse { emptyList() }

        val deduped = dedupeByHostQuality(links)
        if (meta != null) LinkCache.put(meta.imdbId, meta.season, meta.episode, deduped)

        return deduped.isNotEmpty()
    }

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
                extraction = g.extraction,
                tmdbId = meta.tmdbId,
                season = meta.season,
                episode = meta.episode,
                latencyMs = g.priority.toLong(),
            )
        }
    }

    /** Resolve every dooplayer server's embed URL (parallel admin-ajax POSTs) and
     *  recursively unwrap wrapper pages to the deepest player URL. */
    private suspend fun resolveDooplayerSources(
        data: String,
        options: List<Pair<String, Triple<String, String, String>>>,
    ): List<MultiSourcePuller.Source> {
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

        return servers.map { e ->
            val finalUrl = MultiSourcePuller.unwrapEmbed(
                e.url,
                referer = data,
                headers = commonHeaders,
            )
            MultiSourcePuller.Source(
                name = e.name,
                url = finalUrl,
                referer = data,
                headers = commonHeaders,
                latencyMs = e.latencyMs,
            )
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