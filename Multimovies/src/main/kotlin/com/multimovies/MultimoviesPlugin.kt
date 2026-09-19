package com.multimovies
/** FILE: MultimoviesPlugin. kt - the plugin and provider engine. */

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Registers the provider with CloudStream. */
@CloudstreamPlugin
class Multimovies : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        // All providers/extractors added here are registered in the app.
        registerMainAPI(MultimoviesProvider())
        openSettings = { ctx -> Settings.openSettings(ctx) }
    }
}

/** Tiny in-memory cache for search results so repeated searches (and quick-search typing) are instant instead of. */
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

/** Matches any. * host (the site rotates its TLD, so URL checks must never pin the hostname - old. */
private val MULTIMOVIES_HOST_REGEX =
    Regex("""^https?://(?:www\.)?multimovies\.[a-z]{2,10}(?:/|$)""", RegexOption.IGNORE_CASE)

/** Matches the scheme+host prefix of a URL (for host rewriting in). */
private val URL_HOST_REGEX = Regex("""^(https?://[^/]+)""")

/** Tries common lazy-load attributes in order so a poster URL is found even when the theme stores the real image in a. data-* attribute instead of src. */
private fun Element.posterUrl(): String? =
    selectFirst("img")?.let { img ->
        listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"),
            img.attr("data-original"), img.attr("data-lazyload")).firstOrNull { it.isNotBlank() }
    }?.takeIf { it.isNotBlank() }

/** Cheap, no-network parse of the per-item rating badge (as a number). */
internal fun parseRating(item: Element): Double? {
    val raw = item.selectFirst(
        "span.dt_rating_vgs, span.imdb, div.imdb-rating, " +
        ".rating span, span.rating, [class*='imdb'] span"
    )?.text()?.trim()
        ?: return null
    return raw.replace(Regex("[^\\d.]"), "")
        .takeIf { it.isNotBlank() }?.toDoubleOrNull()
}

/** Server names as they appear on the "Video Sources" list, used ONLY to pick the ORDER in which the movie. */
internal val SOURCE_PRIORITY: List<String> = listOf(
    "Cineverse",
    "nxsha",
    "nhdapi",
    "GDMIRROR",
    "screenscape",
    "Peachify",
    "Vidout",
    "Server 01",
    "2embed",
    "VidSrc",
    "111Movies",
    // VidEm (videm. xyz): fast multi-server HLS player. xyz aggregator; resolved by VidemExtractor's signed-token API.
    "VidEm",
)

/** CSS selector for the item containers on a search-results page. */
private val SEARCH_ITEMS_SELECTOR = "div#archive-content div.item, div.search-page div.result-item, article.item, div.ml-items div.item, div.results div.result, ul.ml-posts li, div#content div.post, div.items div.item"

/** Visible headings first: the meta title carries a site-name affix. */
private const val TITLE_SELECTOR = "div.sheader h1, h1, meta[property=og:title]"

/** Drop a leading/trailing site-name affix ("Site | Title", "Title - Site") from scraped titles. Pure. */
internal fun stripSiteAffix(raw: String, siteName: String = "Multimovies"): String {
    val t = raw.trim()
    if (t.isEmpty() || siteName.isBlank()) return t
    val site = Regex.escape(siteName.trim())
    val lead = Regex("""(?i)^$site\s*[|:-]\s*""")
    val trail = Regex("""(?i)\s*[|:-]\s*$site$""")
    return trail.replace(lead.replace(t, ""), "").trim().takeIf { it.isNotEmpty() } ?: t
}

/** One live-search hit from the site JSON API (title, page URL, site poster, year, rating). */
internal data class DooplayHit(
    val title: String,
    val url: String,
    val poster: String?,
    val year: String?,
    val rating: Double?,
)

/** Map the live-search JSON payload to hits. Error bodies ("no_posts", "no_verify_nonce") yield an empty list. Pure. */
internal fun parseDooplaySearchHits(raw: String?): List<DooplayHit> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        if (root.has("error")) return emptyList()
        val out = ArrayList<DooplayHit>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val item = root.optJSONObject(keys.next()) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val img = item.optString("img").takeIf { it.isNotBlank() }
            val extra = item.optJSONObject("extra")
            val date = extra?.optString("date")?.takeIf { it.isNotBlank() && it[0].isDigit() }
            val rating = when (val r = extra?.opt("imdb")) {
                is Number -> r.toDouble().takeIf { it > 0 }
                is String -> r.toDoubleOrNull()?.takeIf { it > 0 }
                else -> null
            }
            out.add(DooplayHit(title, url, img, date, rating))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

/** Scrape the live-search nonce from homepage HTML. Pure. */
internal fun extractDooplayNonce(html: String): String? {
    if (html.isBlank()) return null
    val block = Regex("""dtGonza\s*=\s*\{[^}]*\}""").find(html)?.value ?: return null
    return Regex(""""nonce"\s*:\s*"([A-Za-z0-9]+)"""").find(block)?.groupValues?.get(1)
}

/** a CloudStream provider that scrapes the site. */
class MultimoviesProvider : MainAPI() {

    override var mainUrl = MultimoviesDomainResolver.SEED_DOMAIN
    override var name = "Multimovies"
    // India flag in the search-provider picker (three-dot menu) and provider lists - MainAPI. lang defaults to "en" (UK.
    override var lang = "hi"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private val commonHeaders = mapOf("User-Agent" to userAgent)
    // Solves the Cloudflare managed challenge on the live. * domain.
    private var cfKiller: CloudflareKiller? = null
    private fun getCfKiller(): CloudflareKiller {
        return cfKiller ?: CloudflareKiller().also { cfKiller = it }
    }
    /** Fire-and-forget scope for resolving TMDB search hits to page docs in the background (search itself never blocks on). */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Maps "tmdbId|type" (fallback "imdbId|type") to the resolved MM page URL. */
    private val imdbUrlCache = ConcurrentHashMap<String, String>()
    /** Memoized detail-page docs keyed by MM URL, so load() never fetches twice. */
    private val mmDocCache = ConcurrentHashMap<String, Document>()
    /** Maps "tmdbId|type" to (name, year) so load() can slug-guess the MM page. */
    private val tmdbSearchCache = ConcurrentHashMap<String, Pair<String, String?>>()
    /** Cached live-search nonce scraped from the homepage (the JSON API rejects calls without it). */
    @Volatile private var dooplayNonce: String? = null
    @Volatile private var dooplayNonceAt = 0L
    private val nonceMutex = Mutex()

    /** In-flight link farm per EXACT load url: . . IndStreamProvider's warmFarms/single-flight model. */
    private val liveFarms = ConcurrentHashMap<String, Deferred<Unit>>()

    /** Poll a running farm's growing FastStartCache entry, pushing only URLs pushed has not seen yet; returns the number. */
    private suspend fun tailLiveFarm(
        data: String,
        farm: Deferred<Unit>,
        pushed: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var added = 0
        val deadline = System.currentTimeMillis() + LIVE_FILL_MS
        suspend fun diff(): Int {
            val fresh = FastStartCache.get(data)?.filter { pushed.add(it.url) }.orEmpty()
            fresh.forEach { link -> runCatching { callback(link) }; }
            added += fresh.size
            return fresh.size
        }
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            diff()
            if (!farm.isActive) { diff(); break }
        }
        return added
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

    // Getter (not a one-shot val): the genre URLs must follow mainUrl whenever MultimoviesDomainResolver rotates to a new.
// live domain mid-session.
    override val mainPage
        get() = mainPageOf(
        // Bollywood (5).
        Pair("$mainUrl/genre/bollywood-movies/", "Bollywood Movies"),
        Pair("$mainUrl/genre/netflix/", "Netflix"),
        Pair("$mainUrl/genre/amazon-prime/", "Amazon Prime"),
        Pair("$mainUrl/genre/disney-hotstar/", "Disney+ Hotstar"),
        Pair("$mainUrl/genre/zee-5/", "Zee5"),
        // Global Movies (5).
        Pair("$mainUrl/genre/hollywood/", "Hollywood"),
        Pair("$mainUrl/genre/action/", "Action"),
        Pair("$mainUrl/genre/comedy/", "Comedy"),
        Pair("$mainUrl/genre/horror/", "Horror"),
        Pair("$mainUrl/genre/science-fiction/", "Sci-Fi"),
        // Series (5).
        Pair("$mainUrl/tvshows/", "Web Series"),
        Pair("$mainUrl/genre/k-drama/", "K-Drama"),
        Pair("$mainUrl/genre/crime/", "Crime Series"),
        Pair("$mainUrl/genre/thriller/", "Thriller Series"),
        Pair("$mainUrl/genre/south-indian/", "South Indian"),
        // Anime (3).
        Pair("$mainUrl/genre/anime-hindi/", "Hindi Dub Anime"),
        Pair("$mainUrl/genre/anime-series/", "Anime Series"),
        Pair("$mainUrl/genre/anime-movies/", "Anime Movies"),
    )

    // Source priority / timeout configuration. Per-source timeout in milliseconds.
    companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L

        /** In-memory search result cache TTL (ms). Results don't change minute-to-minute; a longer TTL makes repeat/quick. searches instant. */
        const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1000L

        /** Search returns at most this many results. */
        const val SEARCH_MAX_RESULTS = 6

        /** Weighted relevance score a result must clear AFTER passing the hard every-token-matched gate; anything below is. removed outright. */
        const val SEARCH_RELEVANCE_THRESHOLD = 0.5

        /** Worst-case budget for an uncached search before giving up. */
        const val SEARCH_TOTAL_BUDGET_MS = 2500L

        /** Worst-case budget for the site's own JSON search (nonce fetch + one API call). */
        const val SITE_SEARCH_BUDGET_MS = 8000L

        /** TTL for the cached live-search nonce scraped from the homepage. */
        const val DOOPLAY_NONCE_TTL_MS = 12 * 60 * 60 * 1000L

        /** Live change-server window (, matches. */
        const val LIVE_FILL_MS = 90_000L

        /** Hard cap on how long loadLinks waits before returning, regardless of whether the farm has finished. */
        const val FAST_START_MAX_MS = 45_000L

        /** Quality floor (): a FIXED (non-HLS) stream whose resolved quality is a KNOWN height below 720p. */
        fun passesQualityFloor(isAdaptive: Boolean, height: Int): Boolean =
            isAdaptive || height <= 0 || height >= 720

        /** Max number of detail-page Documents cached in memory. Beyond this, oldest entries are evicted when a new page is. fetched. */
        private const val MM_DOC_CACHE_MAX_SIZE = 24
    }

    private fun priorityOf(serverName: String): Int {
        val idx = SOURCE_PRIORITY.indexOfFirst { serverName.contains(it, ignoreCase = true) }
        return if (idx == -1) SOURCE_PRIORITY.size else idx
    }

    /** True when url belongs to any. * domain. */
    private fun isMultimoviesUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return MULTIMOVIES_HOST_REGEX.containsMatchIn(url)
    }

    /** * URL, so old-domain URLs (session. */
    private fun liveUrl(url: String): String {
        val m = URL_HOST_REGEX.find(url) ?: return url
        if (!m.groupValues[1].substringAfter("://").startsWith("multimovies.", ignoreCase = true)) return url
        val liveHost = URL_HOST_REGEX.find(mainUrl)?.value ?: return url
        return liveHost + url.substring(m.range.last + 1)
    }

    /** Run block against the current mainUrl. */
    private suspend fun <T> withDomainRetry(
        retryIf: (T) -> Boolean,
        block: suspend () -> T,
    ): T {
        try {
            val first = block()
            if (!retryIf(first)) return first
            mainUrl = MultimoviesDomainResolver.resolve(forceRefresh = true)
            return block()
        } catch (e: Exception) {
            mainUrl = MultimoviesDomainResolver.resolve(forceRefresh = true)
            return block()
        }
    }

    /** Fetch url without the Cloudflare solver first: when solved cookies are already valid (CloudStream persists the. */
    internal suspend fun fetchDoc(
        url: String,
        timeoutSeconds: Long = 12,
        required: Boolean = false,
        headers: Map<String, String> = commonHeaders,
    ): Document? {
        val challengeTimeoutS = 15L
        // * host to the current live domain first, so cached/home-page URLs keep fetching after a domain rotation.
        val fetchUrl = liveUrl(url)
        // Fast path: rely on the persisted cookie jar; no WebView solve.
        try {
            val doc = app.get(fetchUrl, timeout = timeoutSeconds, headers = headers).document
            if (!isChallenge(doc)) return doc
        } catch (e: Exception) {
            // fall through to the challenge-solve path.
        }

        // Challenge path: solve with CloudflareKiller (cached or fresh).
        val solved = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            retryUntilSolved(
                attempts = solverFactories().size,
                fetch = { i ->
                    val factory = solverFactories()[i.coerceAtMost(solverFactories().size - 1)]
                    app.get(fetchUrl, timeout = challengeTimeoutS, headers = headers,
                        interceptor = factory()).document
                },
                isBlocked = ::isChallenge,
                onBlocked = { cfKiller = null },
                failureMessage = { lastErr -> lastErr?.localizedMessage ?: "Failed to load $fetchUrl" },
            )
        }
        return when {
            solved == null -> {
                if (required) throw ErrorLoadingException("Timed out fetching $fetchUrl") else null
            }
            isChallenge(solved) -> {
                if (required) throw ErrorLoadingException("Cloudflare challenge unsolved for $fetchUrl") else null
            }
            else -> solved
        }
    }

    /** CloudflareKiller factories retried in order: cached solver, then a fresh one. */
    private fun solverFactories(): List<() -> CloudflareKiller> = listOf(
        { getCfKiller() },
        { CloudflareKiller().also { cfKiller = it } },
    )

    /** Backward-compatible wrapper used by and. */
    internal suspend fun solveDocument(
        url: String,
        timeoutSeconds: Long = 15,
    ): Document = fetchDoc(url, timeoutSeconds = timeoutSeconds, required = true)
        ?: throw ErrorLoadingException("Failed to fetch $url")

    /** Reuses the memoized detail-page doc when present; otherwise fetches, memoizes, and returns it - so main-page card. */
    internal suspend fun cachedDocOrFetch(url: String): Document? {
        mmDocCache[url]?.let { return it }
        val doc = runCatching { solveDocument(url) }.getOrNull() ?: return null
        if (mmDocCache.size >= MM_DOC_CACHE_MAX_SIZE) {
            // ConcurrentHashMap has no order; eviction is arbitrary, not oldest-first.
            mmDocCache.keys.firstOrNull()?.let { mmDocCache.remove(it) }
        }
        mmDocCache[url] = doc
        return doc
    }

    // Search (site JSON API first; TMDB fallback only when the site has no relevant hit).

    override suspend fun search(query: String): List<SearchResponse>? = withDomainRetry(retryIf = { it == null }) {
        SearchCache.get(query)?.let { return@withDomainRetry it }

        // Primary: the site's own live-search JSON API - results link straight to
        // site pages and carry the site's own poster, year and rating inline.
        val site = withTimeoutOrNull(SITE_SEARCH_BUDGET_MS) { siteSearchDooplay(query) }.orEmpty()
        if (site.isNotEmpty()) {
            return@withDomainRetry site.also { SearchCache.put(query, it) }
        }

        // Fallback: one TMDB /search/multi request when the site index misses.
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
            } ?: run {
                // DIAG(search-blank): null here means the 2. 5s budget EXPIRED on a slow (but fine) TMDB reply, or every hit was.
// empty/filtered out.
                android.util.Log.w("Multimovies", "search NULL q='$query': ${SEARCH_TOTAL_BUDGET_MS}ms budget expired or no relevant hits (check tmdb search NET-FAIL/UPSTREAM-ERR lines above)")
                return@withDomainRetry null
            }

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

        if (responses.isEmpty()) return@withDomainRetry null
        return@withDomainRetry responses.also { SearchCache.put(query, it) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /** Build a CloudStream search result, year and IMDB rating all come straight). */
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

    /** Fresh live-search nonce, scraped from the homepage and cached. */
    private suspend fun dooplayNonce(forceRefresh: Boolean = false): String? = nonceMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            dooplayNonce?.let { if (now - dooplayNonceAt < DOOPLAY_NONCE_TTL_MS) return@withLock it }
        }
        val html = runCatching { app.get(mainUrl, timeout = 8, headers = commonHeaders).text }.getOrNull()
            ?: fetchDoc(mainUrl, timeoutSeconds = 8, required = false)?.html()
            ?: return@withLock dooplayNonce
        val fresh = extractDooplayNonce(html)
        if (fresh != null) {
            dooplayNonce = fresh
            dooplayNonceAt = now
            fresh
        } else dooplayNonce
    }

    /** Site live-search via the theme JSON API: results + posters come from the site itself. */
    private suspend fun siteSearchDooplay(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        var nonce = dooplayNonce() ?: return emptyList()
        var text = dooplaySearchJson(query, nonce)
        if (text != null && text.contains("no_verify_nonce")) {
            nonce = dooplayNonce(forceRefresh = true) ?: return emptyList()
            text = dooplaySearchJson(query, nonce)
        }
        if (text == null || text.contains("no_verify_nonce") || text.contains("no_posts")) return emptyList()
        val ranked = parseDooplaySearchHits(text).mapNotNull { hit ->
            val rel = relevanceOf(query, hit.title, hit.year)
            if (!rel.allTokensMatched || rel.score < SEARCH_RELEVANCE_THRESHOLD) null
            else rel.score to hit
        }.sortedByDescending { it.first }.take(SEARCH_MAX_RESULTS)
        if (ranked.isEmpty()) return emptyList()
        val responses = ranked.mapNotNull { (_, hit) -> hit.toSearchResponse() }
        // Warm the detail-page cache so tapping a result opens instantly.
        responses.forEach { r ->
            searchScope.launch { runCatching { cachedDocOrFetch(r.url) } }
        }
        backfillPosters(responses)
        return responses
    }

    /** One GET against the live-search endpoint, or null on network failure. */
    private suspend fun dooplaySearchJson(query: String, nonce: String): String? {
        val url = "$mainUrl/wp-json/dooplay/search/?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}&nonce=$nonce"
        return runCatching {
            app.get(url, timeout = 6, headers = commonHeaders + mapOf("Referer" to "$mainUrl/")).text
        }.getOrNull()
    }

    /** Build a result from a site hit: the URL is the site page, the poster is the site image. */
    private fun DooplayHit.toSearchResponse(): SearchResponse? {
        if (title.isBlank() || url.isBlank()) return null
        val pageUrl = liveUrl(url)
        if (!isMultimoviesUrl(pageUrl)) return null
        val poster = upgradePosterUrl(poster)
        val releaseYear = year?.take(4)?.toIntOrNull()
        val tvType = if (pageUrl.contains("/movies/")) TvType.Movie else TvType.TvSeries
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, pageUrl, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, pageUrl, tvType) {
                this.posterUrl = poster
                this.year = releaseYear
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    /** Resolve a page title to full detail through IMDB (suggest + Cinemeta). */
    private suspend fun resolveImdbDetail(title: String, pageType: String): TmdbService.TmdbDetail? {
        val kind = if (pageType == "movie") "movie" else "series"
        val hits = withTimeoutOrNull(8000L) { ImdbMeta.suggest(title) }.orEmpty()
        val best = hits.mapNotNull { hit ->
            if ((kind == "movie") != (hit.type == "movie")) return@mapNotNull null
            val rel = relevanceOf(title, hit.title, hit.year)
            if (!rel.allTokensMatched || rel.score < SEARCH_RELEVANCE_THRESHOLD) null
            else rel.score to hit
        }.sortedByDescending { it.first }.firstOrNull()?.second ?: return null
        return withTimeoutOrNull(7000L) { ImdbMeta.fetchMeta(best.imdbId, kind) }?.toTmdbDetail()
    }

    /** Cinemeta detail mapped onto the shared holder (tmdbId stays null). */
    private fun ImdbDetail.toTmdbDetail(): TmdbService.TmdbDetail = TmdbService.TmdbDetail(
        imdbId = imdbId,
        name = name,
        poster = poster,
        backdrop = backdrop,
        year = year,
        rating = rating,
        overview = overview,
        genres = genres,
        cast = cast,
    )

    /** Parse a TMDB web URL into (tmdbId, type). Returns null for non-TMDB URLs. */
    private fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
        return m.groupValues[2].toIntOrNull()?.let { id ->
            id to if (m.groupValues[1] == "movie") "movie" else "series"
        }
    }

    /** Resolve a TMDB hit to its real page Document (cached). */
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
        // Emit slugs for every common spelling of the title ("&" vs "and", apostrophes dropped, punctuation stripped) so a.
        val slugVariants = titleVariants(title)
            .map { t -> t.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
            .distinct()
        val variants = buildList {
            for (slug in slugVariants) {
                year?.take(4)?.let { y -> add("${slug}-$y") }
                add(slug)
            }
        }
        for (variant in variants) {
            if (variant.isBlank()) continue
            val guessUrl = "$base$variant/"
            val guessed = fetchDoc(guessUrl, timeoutSeconds = 6, required = false) ?: continue
            if (isChallenge(guessed)) continue
            val guessedTitle = guessed.selectFirst(TITLE_SELECTOR)?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()?.let(::stripSiteAffix)
            if (guessedTitle != null && titleDistance(guessedTitle, title) <= 1) {
                imdbUrlCache[key] = guessUrl
                mmDocCache[guessUrl] = guessed
                return guessed
            }
        }

        // Fallback: site search by title, pick the closest title match.
        val searchTerms = titleVariants(title)
        var searchDoc: Document? = null
        for (term in searchTerms) {
            searchDoc = fetchDoc(
                "$mainUrl/?s=${URLEncoder.encode(term, "UTF-8")}",
                timeoutSeconds = 8,
                required = false,
            )
            if (searchDoc != null && searchDoc.select(SEARCH_ITEMS_SELECTOR).isNotEmpty()) break
        }
        val candidate = searchDoc?.select(SEARCH_ITEMS_SELECTOR)?.mapNotNull { it.candidateHref() }
            ?.minByOrNull { titleDistance(it.second, title) }
            // Same bar as the slug-guess path: a non-match must stay "not found", never a wrong title.
            ?.takeIf { titleDistance(it.second, title) <= 1 } ?: return null
        val detailDoc = mmDocCache[candidate.first]
            ?: fetchDoc(candidate.first, timeoutSeconds = 8, required = false)
        if (detailDoc != null) {
            imdbUrlCache[key] = candidate.first
            mmDocCache[candidate.first] = detailDoc
        }
        return detailDoc
    }

    /** Extract (href, item title). */
    private fun Element.candidateHref(): Pair<String, String>? {
        val a = selectFirst("a[href], div.data a h2, div.poster a") ?: return null
        val href = a.attr("href").takeIf { isMultimoviesUrl(it) } ?: return null
        val itemTitle = selectFirst("img")?.attr("alt")
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()
            ?: a.text()?.trim()
        return if (itemTitle.isNullOrBlank()) null else href to itemTitle
    }

    /** Concurrently resolve TMDB posters for main-page results that are missing one or still carry a thumbnail size marker. */
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
                            val type = if (item.tvType == TvType.Movie) "movie" else "series"
                            TmdbService.search(item.title).firstOrNull { it.type == type }?.poster
                                ?.takeIf { it.isNotBlank() }
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
        val href = a.attr("href").takeIf { isMultimoviesUrl(it) } ?: return null
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

    // Main page.

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = withDomainRetry(retryIf = { it == null }) {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = fetchDoc(url, timeoutSeconds = 12, required = false) ?: return@withDomainRetry null
        val items = doc.select("article.item, div#archive-content div.item, div.items div.item").mapNotNull {
            it.toSearchResponse()
        }
        backfillPosters(items)
        newHomePageResponse(request.name, items)
    }

    // Load (detail page).

    override suspend fun load(url: String): LoadResponse? =
        withDomainRetry(retryIf = { it == null }) { loadInternal(url) }

    private suspend fun loadInternal(url: String): LoadResponse? {
        // Search results arrive as TMDB web URLs; main-page cards arrive as MM URLs.
        val tmdb = parseTmdbUrl(url)
        val cached = tmdb?.let { (id, type) -> tmdbSearchCache["$id|$type"] }

        return coroutineScope {
            // MM page (stream sources only) and TMDB metadata resolve in parallel.
            val pageJob = async {
                if (tmdb != null) {
                    resolveMultimoviesDoc(tmdb.first, null, tmdb.second, cached?.first.orEmpty(), cached?.second)
                } else {
                    cachedDocOrFetch(url)
                }
            }
            val metaJob = async {
                if (tmdb != null) withTimeoutOrNull(6000L) { TmdbService.fetchMeta(tmdb.first, tmdb.second) } else null
            }

            val detail = metaJob.await()
            var doc = pageJob.await()

            // A pasted TMDB URL with no cached title: retry page resolution with the real title once metadata arrives.
            if (doc == null && tmdb != null && detail?.name != null) {
                doc = resolveMultimoviesDoc(tmdb.first, null, tmdb.second, detail.name, detail.year)
            }
            doc ?: throw ErrorLoadingException("Could not match $url to a Multimovies page")
            // The real MM URL is what loadLinks() receives; never fall back to the TMDB search URL for search taps.
            val realUrl = doc.location()?.takeIf { it.isNotBlank() }
                ?: (if (tmdb != null) imdbUrlCache["${tmdb.first}|${tmdb.second}"] else null)
                ?: url

            val isMovie = tmdb?.second == "movie" || realUrl.contains("/movies/")
            val pageType = if (isMovie) "movie" else "series"

            // Fire-and-forget: pre-resolve the top-priority player servers while the user reads the detail page, so tapping Play.
            if (isMovie) prefetchEmbeds(realUrl, doc)

            // Direct MM page (main-page card): ids come, then metadata is fetched; the dooplayer embed URL is a last resort.
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
                if (resolvedDetail == null) {
                    // Pages carry no ids, so detail and cast come from IMDB by title.
                    val fallbackTitle = doc.selectFirst(TITLE_SELECTOR)?.let {
                        if (it.tagName() == "meta") it.attr("content") else it.text()
                    }?.trim()?.let(::stripSiteAffix)
                    if (!fallbackTitle.isNullOrBlank()) {
                        resolvedDetail = resolveImdbDetail(fallbackTitle, pageType)
                    }
                }
            }

            val tmdbId = resolvedDetail?.tmdbId ?: tmdb?.first
            val imdbId = resolvedDetail?.imdbId
                ?: if (tmdb == null) TmdbService.extractImdbId(doc) else null

            // Page-scraped fallbacks (only when TMDB gave nothing). Visible
            // headings first: the meta title carries a site-name affix.
            val pageTitle = doc.selectFirst(TITLE_SELECTOR)?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()?.let(::stripSiteAffix)
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
                ?: doc.select("div.sgeneros a, .genre a").mapNotNull { it.text() }
            val score = resolvedDetail?.rating
            val pageScore = doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
                ?.removePrefix("IMDb:")?.trim()?.toDoubleOrNull()

            // Stash ids for loadLinks() (movie page + every TV episode page).
            if (imdbId != null || tmdbId != null) {
                SourceMetaCache.put(realUrl, SourceMeta(imdbId ?: "", tmdbId?.toString(), null, null))
            }

            // Global id-based sources (2embed / VidSrc / 111Movies / Nxsha / VidEm) need exactly the ids we now hold - pre-resolve.
            if (isMovie) prefetchGlobals(
                SourceMeta(imdbId ?: "", tmdbId?.toString(), null, null), realUrl,
            )

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
                // TV / Seasons: episode LINKS come); titles/descriptions/thumbnails/ratings come.
                val episodes = arrayListOf<Episode>()
                val seasonLinks = doc.select("a[href*='/seasons/']")
                    .mapNotNull { it.attr("href").takeIf { h -> isMultimoviesUrl(h) } }
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
                seasonDocs.forEachIndexed { pageIdx, sDoc ->
                    if (sDoc == null) return@forEachIndexed
                    // Season pages arrive in DOM order; the page index beats a forced guess.
                    val pageSeason = pageIdx + 1
                    sDoc.select("ul.episodios li, div.eps div.ep, .episodios li").forEachIndexed { i, ep ->
                        val epLink = ep.selectFirst("a[href]")?.attr("href")?.takeIf { isMultimoviesUrl(it) }
                            ?: return@forEachIndexed
                        val nxm = Regex("(?i)(\\d+)x(\\d+)").find(epLink)
                        // Bare digit runs are years/post-ids as often as episodes; implausible ones fall back to list position.
                        val epNum = nxm?.groupValues?.getOrNull(2)?.toIntOrNull()
                            ?: Regex("(\\d+)").find(epLink)?.value?.toIntOrNull()?.takeIf { it in 1..150 }
                            ?: (i + 1)
                        val seasonNum = nxm?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
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

                // Episode enrichment (parallel, bounded, best-effort). TMDB ids use TMDB;
                // IMDB-resolved pages use Cinemeta, which carries the same episode rows.
                if (seasonNums.isNotEmpty()) {
                    if (tmdbId != null) {
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
                    } else if (imdbId != null) {
                        val epMeta = withTimeoutOrNull(7000L) { ImdbMeta.fetchEpisodes(imdbId) }.orEmpty()
                        episodes.forEach { ep ->
                            epMeta[ep.season to ep.episode]?.let { m ->
                                if (ep.name.isNullOrBlank()) ep.name = m.name
                                if (!m.overview.isNullOrBlank()) ep.description = m.overview
                                m.released?.let { ep.addDate(it) }
                                m.thumbnail?.let { ep.posterUrl = it }
                            }
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

    /** Resolve the IMDB id) dooplayer embed URL. */
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

    /** Parse the Dooplay "Video Sources" list: each li. dooplay_player_option carries data-nume (source index) and. */
    private fun parsePlayerOptions(doc: Document, pageUrl: String): List<Pair<String, Triple<String, String, String>>> {
        val postId = doc.selectFirst("meta#dooplay-ajax-counter")
            ?.attr("data-postid")
            ?.takeIf { it.isNotBlank() }
        return doc.select("ul#playeroptionsul li.dooplay_player_option, li.dooplay_player_option")
            .mapNotNull { li ->
                val name = li.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
                val nume = li.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (nume.equals("trailer", ignoreCase = true) ||
                    name.contains("trailer", ignoreCase = true) ||
                    name.contains("youtube", ignoreCase = true)
                ) return@mapNotNull null
                val post = postId ?: li.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = li.attr("data-type").takeIf { it.isNotBlank() }
                    ?: if (pageUrl.contains("/movies/")) "movie" else "tv"
                name to Triple(post, nume, type)
            }
    }

    /** Background movie-only prefetch: resolve EVERY dooplayer server through admin-ajax and unwrap it to its final. */
    private fun prefetchEmbeds(pageUrl: String, doc: Document) {
        searchScope.launch {
            runCatching {
                EmbedPrefetchCache.resolveOrJoin(pageUrl, resolve = { resolveAllEmbeds(pageUrl, doc) })
            }
        }
    }

    /** Movie-only pre-warm of the GLOBAL id-based sources into FastStartCache (the dooplayer embed path has prefetchEmbeds. */
    private fun prefetchGlobals(meta: SourceMeta, pageUrl: String) {
        if (meta.imdbId.isBlank() && meta.tmdbId == null) return
        if (FastStartCache.get(pageUrl)?.isNotEmpty() == true) return
        val sources = buildGlobalSources(meta)
        if (sources.isEmpty()) return
        sources.forEach { g ->
            searchScope.launch {
                runCatching {
                    pullSource(
                        g, ConcurrentHashMap(), pageUrl,
                        Collections.synchronizedSet(HashSet()),
                        Collections.synchronizedSet(HashSet()),
                        Collections.synchronizedList(mutableListOf()),
                        { _ -> }, { },
                        // Cache-only: pre-warm NEVER emits to a player - the gathered links land in FastStartCache only.
                        firstLink = null,
                        cacheKey = pageUrl,
                        cacheOnly = true,
                    )
                }
            }
        }
    }

    /** Resolve EVERY player server for pageUrl to its final post-unwrap URL. */
    private suspend fun resolveAllEmbeds(pageUrl: String, doc: Document): List<ResolvedEmbed> {
        val options = parsePlayerOptions(doc, pageUrl)
            .sortedBy { priorityOf(it.first) }
        if (options.isEmpty()) return emptyList()
        return coroutineScope {
            options.map { (name, triple) ->
                async {
                    runCatching {
                        val e = resolveEmbed(pageUrl, name, triple.first, triple.second, triple.third)
                            ?: return@runCatching null
                        val resolved = e.copy(
                            url = MultiSourcePuller.unwrapEmbed(e.url, referer = pageUrl, headers = commonHeaders),
                            unwrapped = true,
                        )
                        EmbedPrefetchCache.publish(pageUrl, resolved)
                        resolved
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    // Load links - parallel pulling with per-source.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withDomainRetry(retryIf = { !it }) {
        var meta = SourceMetaCache.get(data)

        // ): NO plugin-side ranking anywhere - not on the fresh path, not on replays. Fast path 1: cached links replay.
// instantly, in stored (arrival) order.
        if (meta != null) {
            LinkCache.get(meta.imdbId, meta.season, meta.episode)?.let { cached ->
                if (cached.isNotEmpty()) {
                    cached.forEach { runCatching { callback(it) } }
                    // SubtilesProvider is the ONLY subtitle source - replayed titles must get their tracks too (the 15-min per-title cache.
                    deliverFallbackSubs(meta, subtitleCallback)
                    return@withDomainRetry true
                }
            }
        }

        // Fast path 1. 5: fast-start cache - a previous play (or a background pull) already resolved this exact load url.
        FastStartCache.get(data)?.let { cached ->
            if (cached.isNotEmpty()) {
                cached.forEach { runCatching { callback(it) } }
                // Live tail (): the first tap's farm may still be resolving - forwarding its arrivals keeps the.
                val farm = liveFarms[data]
                if (farm != null && farm.isActive) {
                    val pushed = HashSet<String>().apply { cached.forEach { add(it.url) } }
                    val extra = tailLiveFarm(data, farm, pushed, callback)
                    android.util.Log.i("Multimovies", "loadLinks: replay ${cached.size} + live tail " +
                        "$extra links (first-tap farm still resolving)")
                }
                if (meta != null) {
                    // Fallback subtitles ARE the subtitle provider (): fetch the wanted set on every replay - the.
                    deliverFallbackSubs(meta, subtitleCallback)
                }
                return@withDomainRetry true
            }
        }

        // Single-flight join (, ): this url's farm is ALREADY launching.
        liveFarms[data]?.takeIf { it.isActive }?.let { farm ->
            val pushed = HashSet<String>()
            val extra = tailLiveFarm(data, farm, pushed, callback)
            if (extra > 0) {
                android.util.Log.i("Multimovies", "loadLinks: joined in-flight farm -> $extra live links")
                if (meta != null) deliverFallbackSubs(meta, subtitleCallback)
                return@withDomainRetry true
            }
            // Farm finished with nothing cached - fall through to a fresh resolve on this tap.
        }

        // Fast path 2: embeds prefetched in the background while the detail page was open - skips the page fetch AND.
        val awaited = EmbedPrefetchCache.awaitInFlight(data, timeoutMs = 1200L)
        var embeds: List<ResolvedEmbed> =
            (awaited.orEmpty() + EmbedPrefetchCache.arrived(data)).distinctBy { it.embedIdentity() }

        if (awaited == null) {
            // No complete prefetch (cold tap or warm-up still running): resolve the servers not already in hand.
            val doc = cachedDocOrFetch(data)
            if (doc != null) {
                val have = embeds.mapTo(HashSet()) { it.key }
                val missing = parsePlayerOptions(doc, data)
                    .distinctBy { dooplayOptionKey(it.second.first, it.second.second, it.second.third) }
                    .filterNot { have.contains(dooplayOptionKey(it.second.first, it.second.second, it.second.third)) }
                if (missing.isNotEmpty()) {
                    embeds += coroutineScope {
                        missing.map { (name, triple) ->
                            async {
                                runCatching { resolveEmbed(data, name, triple.first, triple.second, triple.third) }.getOrNull()
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }
        }

        // The raw dooplayer embed URLs also carry the IMDB id, which recovers meta when load() never resolved one.
        if (meta == null) {
            embeds.firstNotNullOfOrNull { extractImdbIdFromUrl(it.embedUrl ?: it.url) }?.let { id ->
                meta = SourceMeta(id, null, parseSeason(data), parseEpisode(data))
                SourceMetaCache.put(data, meta)
            }
        }

        // LIVE-FILL pipeline (): every source (global id-keyed + each dooplayer embed) is.
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val emittedUrls = Collections.synchronizedSet(HashSet<String>())
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val firstLink = CompletableDeferred<Unit>()
        val labelCounter = ConcurrentHashMap<String, Int>()
        // Server-caption NO-OP (): extractor caption lists are ignored everywhere - nothing is collected, cached.
        val noopSubtitle: (SubtitleFile) -> Unit = { }

        val globalSources = buildGlobalSources(meta)
        // Completion accounting: the pulls run detached so they keep landing in FastStartCache even after loadLinks returns.
        val remainingPulls = java.util.concurrent.atomic.AtomicInteger(globalSources.size + embeds.size)
        val allPullsDone = CompletableDeferred<Unit>()
        if (globalSources.isEmpty() && embeds.isEmpty()) allPullsDone.complete(Unit)

        // Publish this tap's farm so later taps on the SAME url can tail it live and never re-launch the fleet (live-window).
        val farm = searchScope.async { allPullsDone.await() }
        liveFarms[data] = farm
        farm.invokeOnCompletion { if (liveFarms[data] === farm) liveFarms.remove(data) }

        globalSources.forEach { g ->
            searchScope.launch {
                try {
                    pullSource(g, labelCounter, data, emitted, emittedUrls, found, noopSubtitle, callback,
                        firstLink = firstLink, cacheKey = data)
                } finally {
                    if (remainingPulls.decrementAndGet() <= 0) allPullsDone.complete(Unit)
                }
            }
        }

        // Embed pulls: launch order is the site's own option order (no priority re-ranking -).
        embeds.forEach { e ->
            searchScope.launch {
                try {
                    // Prefetched entries are already unwrapped (resolveAllEmbeds) - skip a redundant iframe walk; only cold-resolved.
                    val finalUrl = if (e.unwrapped) e.url
                    else MultiSourcePuller.unwrapEmbed(e.url, referer = data, headers = commonHeaders)
                    // After unwrap, the URL may now point at a downstream CDN (-> vibuxer / serve_m3u8 proxy).
                    val srcHeaders = commonHeaders + MultiSourcePuller.headersFor(finalUrl, referer = data)
                    val src = MultiSourcePuller.Source(
                        name = e.name,
                        url = finalUrl,
                        referer = data,
                        headers = srcHeaders,
                        // Cached ids let the Nxsha extractor resolve even when the embed URL itself carries no tmdb/imdb marker.
                        tmdbId = meta?.tmdbId,
                        imdbId = meta?.imdbId,
                    )
                    pullSource(src, labelCounter, data, emitted, emittedUrls, found, noopSubtitle, callback,
                        firstLink = firstLink, cacheKey = data)
                } finally {
                    if (remainingPulls.decrementAndGet() <= 0) allPullsDone.complete(Unit)
                }
            }
        }

        // Fallback subtitles (): the two-source provider (OpenSubtitles addon + SubSense top-up) is the ONLY.
        // Start fetching IMMEDIATELY on tap, in parallel with the stream farm, and emit priority tracks
        // (hi/en) first - the fetch used to wait for the first stream, delaying subs past playback start.
        val metaSubs = meta
        val subsJob = metaSubs?.let { m ->
            searchScope.async {
                deliverFallbackSubs(m, subtitleCallback)
            }
        }

        // LIVE FILL, -style () - two gates mirroring com. . IndStreamProvider’s proven flow: 1.
        val loadStartMs = System.currentTimeMillis()
        val nothingToPull = embeds.isEmpty() && globalSources.isEmpty()
        val firstStream = !nothingToPull &&
            withTimeoutOrNull(FAST_START_MAX_MS) { firstLink.await() } != null
        if (!firstStream) {
            // (a prior visit's prefetch or background pulls), else surface "no link found" - and drop a stale prefetch so the next.
            if (awaited != null && awaited.isNotEmpty()) EmbedPrefetchCache.invalidate(data)
            val bg = FastStartCache.get(data)
            if (bg != null && bg.isNotEmpty()) {
                bg.forEach { runCatching { callback(it) } }
                meta?.let { deliverFallbackSubs(it, subtitleCallback) }
                return@withDomainRetry true
            }
            return@withDomainRetry false
        }

        // All pulls answer, or the cap (whichever first) - pushes land live.
        withTimeoutOrNull(LIVE_FILL_MS) { allPullsDone.await() }

        // Subtitle tracks must land BEFORE the return (the app drops subtitleCallback pushes).
        subsJob?.await()

        // Full-list landing for the LinkCache instant replay: once the farm is done, cache the deduped arrival-order list.
        val metaFinal = meta
        if (metaFinal != null) {
            searchScope.launch {
                allPullsDone.await()
                val deduped = dedupeByHostQuality(found.toList())
                if (deduped.isNotEmpty()) {
                    LinkCache.put(metaFinal.imdbId, metaFinal.season, metaFinal.episode, deduped)
                }
            }
        }

        android.util.Log.i("Multimovies", "loadLinks: ${emitted.size} links pushed live in " +
            "${System.currentTimeMillis() - loadStartMs}ms (arrival order, no ranking)")
        return@withDomainRetry true
    }

    /** Subtitle provider: user Wyzie key first, else the OpenSubtitles + SubSense fallback. */
    private suspend fun deliverFallbackSubs(
        meta: SourceMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        runCatching {
            coroutineScope {
                val type = if (meta.season != null || meta.episode != null) "tv" else "movie"
                // Tmdb-only titles store "": resolve imdb via TMDB so subs still land instead of silently empty.
                var imdb = meta.imdbId.takeIf { it.startsWith("tt") }
                var detail: TmdbService.TmdbDetail? = null
                if (imdb == null) {
                    val tmdb = meta.tmdbId?.toIntOrNull()
                    if (tmdb != null) {
                        detail = withTimeoutOrNull(4000L) { TmdbService.fetchMeta(tmdb, type) }
                        imdb = detail?.imdbId?.takeIf { it.startsWith("tt") }
                    }
                }
                val base = SubtilesProvider.desiredLanguages(null)
                val baseCodes = SubtilesProvider.codesFromLangs(base)
                // Original-language lookup overlaps subtitle fetching instead of delaying it.
                val origLang = async {
                    detail?.originalLanguage ?: meta.tmdbId?.toIntOrNull()?.let { id ->
                        withTimeoutOrNull(4000L) { TmdbService.fetchMeta(id, type) }?.originalLanguage
                    }
                }
                // Shared emitter: both providers race, first tracks win, no duplicates.
                val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                val emitLock = Mutex()
                suspend fun emit(t: SubtitleFile) {
                    if (!seen.add(t.url)) return
                    emitLock.withLock { runCatching { subtitleCallback(t) } }
                }
                // One extra single-language request for the title's own original language (a TMDB 2-letter code),
                // only when it isn't already covered by the English/Indian base set.
                suspend fun topUpOriginal(wyzieKey: String?): Int {
                    val code = origLang.await()?.takeIf { it.length == 2 }?.lowercase() ?: return 0
                    if (code in baseCodes) return 0
                    return if (wyzieKey != null) {
                        WyzieSubs.fetchAndDeliver(
                            imdb, meta.tmdbId, meta.season, meta.episode,
                            setOf(code), wyzieKey, quiet = true,
                        ) { emit(it) }
                    } else {
                        SubtilesProvider.fetchAndDeliver(
                            imdb, meta.season, meta.episode, setOf(code), code,
                        ) { emit(it) }
                    }
                }
                // Race user key against the built-in stack: both fire at once, first tracks win.
                coroutineScope {
                    Settings.apiKey()?.let { key ->
                        launch {
                            runCatching {
                                WyzieSubs.fetchAndDeliver(
                                    imdb, meta.tmdbId, meta.season, meta.episode, base, key,
                                ) { emit(it) }
                                topUpOriginal(key)
                            }
                        }
                    }
                    launch {
                        runCatching {
                            SubtilesProvider.fetchAndDeliver(
                                imdb, meta.season, meta.episode, base, null,
                            ) { emit(it) }
                            topUpOriginal(null)
                        }
                    }
                }
            }
        }.onFailure { android.util.Log.w("Multimovies", "fallback subs failed: ${it.message}") }
    }

    /** Pull a single source, streaming found links to callback as they arrive and collecting them (deduped at emission. */
    private suspend fun pullSource(
        src: MultiSourcePuller.Source,
        labelCounter: ConcurrentHashMap<String, Int>,
        data: String,
        emitted: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        found: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        /** Completed on the first link so the subtitle trigger in can fire the fallback fetch as the stream starts. */
        firstLink: CompletableDeferred<Unit>? = null,
        /** When set, every resolved link is also merged into (keyed by the load url) so a re-tap replays instantly. */
        cacheKey: String? = null,
        /** Prewarm mode: links land in found/FastStartCache only - the callback is never invoked, so a background pull can't. */
        cacheOnly: Boolean = false,
    ): List<ExtractorLink> {
        /** Disambiguate duplicate labels within a single load: the first link with a given label keeps it; subsequent links. */
        suspend fun disambiguate(l: ExtractorLink): ExtractorLink {
            val label = l.source
            val n = labelCounter.compute(label) { _, v -> (v ?: 0) + 1 }!!
            if (n == 1) return l
            val dis = "$label-$n"
            return newExtractorLink(
                source = dis, name = dis, url = l.url,
                type = l.type,
            ) {
                referer = l.referer
                quality = l.quality
                headers = l.headers
                extractorData = l.extractorData
                audioTracks = l.audioTracks ?: emptyList()
            }
        }

        /** Quality floor (): drop KNOWN sub-720p fixed files. */
        fun passesFloor(l: ExtractorLink): Boolean =
            MultimoviesProvider.passesQualityFloor(
                l.type == ExtractorLinkType.M3U8 || l.type == ExtractorLinkType.DASH,
                l.quality,
            )

        /** Emit one disambiguated link: quality-floor it (sub-720 fixed files never reach the player OR the caches), then. */
        suspend fun emitOne(l: ExtractorLink, fast: Boolean = false) {
            if (!passesFloor(l)) return
            // Exact-URL dupes drop cheaply; host+quality+language decides the rest
            // AFTER enrichment, so a Hindi master is never dropped as an English dupe.
            if (!emittedUrls.add(l.url ?: return)) return
            val dis = disambiguate(MultiSourcePuller.enrichLabel(
                l,
                // Fast path skips the slow master probe: URL facts only, instant emit.
                probeBudgetMs = if (fast) MultiSourcePuller.FAST_PROBE_BUDGET_MS
                else MultiSourcePuller.LABEL_PROBE_BUDGET_MS,
            ))
            val key = "${hostOf(dis.url ?: "")}|${dis.quality}|${MultiSourcePuller.bracketTag(dis)}"
            if (!emitted.add(key)) return
            found.add(dis)
            if (cacheKey != null) FastStartCache.put(cacheKey, listOf(dis))
            firstLink?.complete(Unit)
            if (cacheOnly) return // prewarm: cache only.
            runCatching { callback(dis) }
        }

        val cineverseFastLink = if (MultiSourcePuller.isCineverseHost(src.url) &&
            (src.url.contains("serve_m3u8=1", ignoreCase = true) ||
                src.url.contains(".m3u8", ignoreCase = true) ||
                src.url.contains(".mp4", ignoreCase = true))
        ) buildDirectLink(src) else null
        if (cineverseFastLink != null) {
            emitOne(cineverseFastLink, fast = true)
            return listOf(cineverseFastLink)
        }
        return MultiSourcePuller.pull(
            sources = listOf(src),
            timeoutMs = SOURCE_TIMEOUT_MS,
            onSubtitle = subtitleCallback,
            onLink = { l -> emitOne(l) },
        )
    }

    /** Build the ExtractorLink emitted by the fast path. */
    private suspend fun buildDirectLink(
        src: MultiSourcePuller.Source,
    ): ExtractorLink {
        val u = src.url
        val headers = MultiSourcePuller.headersFor(u, src.referer, src.headers)
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(u)
        return newExtractorLink(
            source = src.name,
            name = src.name,
            url = u,
            type = type,
        ) {
            referer = src.referer ?: u
            this.quality = quality
            this.headers = headers
            extractorData = null
            audioTracks = emptyList()
        }
    }

    /** Resolve a single dooplayer server's embed URL via the site's admin-ajax endpoint. */
    private suspend fun resolveEmbed(data: String, name: String, post: String, nume: String, type: String): ResolvedEmbed? {
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

        val rawEmbed = Regex("\"embed_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(resp)?.groupValues?.get(1)
            ?: return null
        val embed = cleanEmbedUrl(rawEmbed).takeIf { it.isNotBlank() } ?: return null
        return ResolvedEmbed(name, embed, embedUrl = embed, key = dooplayOptionKey(post, nume, type))
    }

    private fun parseSeason(url: String): Int? =
        Regex("(?i)(\\d+)x\\d+").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseEpisode(url: String): Int? =
        Regex("(?i)\\d+x(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Build dooplayer-independent direct sources). */
    private fun buildGlobalSources(meta: SourceMeta?): List<MultiSourcePuller.Source> {
        if (meta == null) return emptyList()
        return GlobalSources.list.mapNotNull { g ->
            // tmdb-only titles store "" (never null): skip instead of requesting "?imdb=".
            val id = when (g.idType) {
                SourceId.IMDB -> meta.imdbId
                SourceId.TMDB -> meta.tmdbId
            }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = g.buildUrl(id, meta.season, meta.episode) ?: return@mapNotNull null
            MultiSourcePuller.Source(
                name = g.name,
                url = url,
                referer = url,
                headers = commonHeaders + g.headers,
                tmdbId = meta.tmdbId,
                imdbId = meta.imdbId,
                season = meta.season,
                episode = meta.episode,
            )
        }
    }

    /** Normalize a raw dooplayer embed_url: unescape JSON slashes/quotes, pull the iframe src when the value is an HTML. */
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

    /** Light dedupe (): keep the FIRST arrival per (host, quality, language) so the same final host reached through. */
    private fun dedupeByHostQuality(links: List<ExtractorLink>): List<ExtractorLink> {
        val seen = HashSet<String>()
        return links.filter { l -> seen.add("${hostOf(l.url ?: "")}|${l.quality}|${MultiSourcePuller.bracketTag(l)}") }
    }
}

/** Self-healing live-domain resolver for the Dooplay site. `. wtf` is a static gateway page that. */
internal object MultimoviesDomainResolver {

    /** Stable gateway URL that always announces the current live domain. */
    internal const val LANDING_URL = "https://multimovies.wtf/"

    /** Last-known-good domain; used only when the gateway itself is unreachable and no cached value exists. */
    internal const val SEED_DOMAIN = "https://multimovies.casa"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val GATEWAY_DEBOUNCE_MS = 60_000L
    private const val FETCH_TIMEOUT_S = 8L

    private val LIVE_DOMAIN_REGEX =
        Regex("""^https://(?:www\.)?multimovies\.[a-z]{2,10}$""")

    @Volatile
    private var cached: String? = null

    @Volatile
    private var resolvedAt = 0L

    @Volatile
    private var lastFetchAt = 0L

    private val mutex = Mutex()

    /** Normalize a candidate href: strip trailing slash, drop www, lowercase. Returns null when the href is not a bare live. domain. */
    internal fun normalize(href: String): String? {
        val h = href.trim().trimEnd('/')
        if (!LIVE_DOMAIN_REGEX.matches(h)) return null
        val normalized = h.replace("https://www.", "https://")
        if (normalized == LANDING_URL.trimEnd('/')) return null
        return normalized
    }

    /** The most-repeated live-domain href in the gateway page's HTML, or null. */
    internal fun extractLiveDomain(html: String): String? {
        if (html.isBlank()) return null
        return Jsoup.parse(html)
            .select("a[href]")
            .mapNotNull { normalize(it.attr("href")) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }

    /** The last known live domain (or the seed when never resolved) - no network. */
    internal fun currentDomain(): String = cached ?: SEED_DOMAIN

    /** Current live domain: cached value (6 h TTL) unless forceRefresh or the cache is stale. */
    suspend fun resolve(forceRefresh: Boolean = false): String = mutex.withLock {
        val now = System.currentTimeMillis()
        val fresh = !forceRefresh && cached != null && (now - resolvedAt) < CACHE_TTL_MS
        if (fresh) return@withLock cached!!

        if (forceRefresh && cached != null && (now - lastFetchAt) < GATEWAY_DEBOUNCE_MS) {
            return@withLock cached!!
        }

        val live = runCatching {
            extractLiveDomain(app.get(LANDING_URL, timeout = FETCH_TIMEOUT_S).text)
        }.getOrNull()

        lastFetchAt = System.currentTimeMillis()
        if (live != null) {
            cached = live
            resolvedAt = lastFetchAt
            live
        } else {
            cached ?: SEED_DOMAIN
        }
    }
}

/** MultiSourcePuller - the parallel-pull / timeout engine (NEUTRAL, ). */
object MultiSourcePuller {

    data class Source(
        val name: String,
        val url: String,
        val referer: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /** Max iframe levels to unwrap before treating a page as the player. */
    private const val MAX_UNWRAP_LEVELS = 4

    /** Regexes for the generic embed sniffer: stream URLs to harvest directly. */
    internal val STREAM_URL_REGEXES = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mkv[^\s"'<>\\]*"""),
    )

    /** Pure helper: pull the first stream URL (m3u8/mp4/webm/mkv). */
    internal fun extractStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        // Normalize JSON-escaped slashes/backslashes so the plain `https?: //` regex can still match URLs embedded in JSON (\/.
// > /).
        val normalized = text.replace("\\/", "/").replace("\\\"", "\"")
        for (r in STREAM_URL_REGEXES) {
            r.findAll(normalized).firstOrNull()?.groupValues?.get(0)?.let { raw ->
                val cleaned = raw.trim('"', '\'')
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return null
    }

    /** Detect a modiplay-style proxy player endpoint in page text, e. g. `\/proxy. php?serve_m3u8=1&ref=. &url=<url-encoded. */
    internal fun buildProxyStreamUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\/", "/")
        val m = Regex("""(?:https?:)?//[^"'\s<>]*proxy\.php\?[^"'\s<>]*serve_m3u8=1[^"'\s<>]*""")
            .find(normalized)
            ?: Regex("""/(?:[^"'\s<>]*proxy\.php\?[^"'\s<>]*serve_m3u8=1[^"'\s<>]*)""")
                .find(normalized)
        val raw = m?.value?.trim('"', '\'', '\\') ?: return null
        return resolveRelative(baseUrl, raw).takeIf { it.startsWith("http") }
    }

    /** Pull a stream URL. */
    internal fun extractVideoSourceUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")
            ?.attr("src")?.trim() ?: return null
        if (src.isBlank()) return null
        return resolveRelative(baseUrl, src).takeIf { it.startsWith("http") }
    }

    /** Pull a stream URL. . . m3u8"}`. */
    internal fun extractFromJsConfig(text: String): String? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\/", "/")
        val patterns = listOf(
            Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:source|src)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            p.findAll(normalized).firstOrNull()?.groupValues?.get(1)?.let {
                val v = it.trim()
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /** Decode a URL-encoded m3u8 URL found inside a query string (e. g. `url=%2F. . %2Fmaster. m3u8. . . `) and return it. as a plain https URL. */
    internal fun decodeEncodedStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = Regex("""url=([^"'&\s]+?%2F[^"'&\s]*master\.m3u8[^"'&\s]*)""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val encoded = m.groupValues[1]
        return runCatching {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    )

    /** Hosts that back the modiplay/vibuxer serve_m3u8=1 proxy. */
    private val cineverseCdnHosts = setOf(
        "vibuxer.com",
        "www.vibuxer.com",
        "modiplay.com",
        "www.modiplay.com",
        "modiplay.xyz",
        "hanerix.com",
        "cinemodiy.com",
        "cinehive.com",
        "play.cineverse.com",
        "cdn.cineverse.com",
    )

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").lowercase()

    /** True when the URL belongs to the modiplay/vibuxer CDN, or is a `serve_m3u8=1` proxy relay (the signature. */
    internal fun isCineverseHost(url: String): Boolean =
        cineverseCdnHosts.contains(hostOf(url)) ||
            hostOf(url).let { h -> cineverseCdnHosts.any { h == it || h.endsWith(".$it") } } ||
            url.contains("serve_m3u8", ignoreCase = true)

    /** Deterministic identity base for an emitted link: the server's own stable name. ──, derived. One parsed audio. rendition of an HLS master. */
    data class AudioRendition(val language: String?, val name: String, val isDefault: Boolean)

    /** Parsed HLS master: tallest variant height + audio renditions. */
    data class MasterFacts(val bestHeight: Int, val audio: List<AudioRendition>)

    /** Pure parse of an HLS master playlist text (JVM-testable): variant heights, audio languages. */
    internal fun parseMasterFacts(text: String?): MasterFacts? {
        if (text.isNullOrBlank() || !text.contains("#EXT-X-STREAM-INF")) return null
        var best = 0
        Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it > best) best = it }
        }
        val audio = mutableListOf<AudioRendition>()
        Regex("""#EXT-X-MEDIA:([^\n]*)""").findAll(text).forEach { m ->
            val attrs = m.groupValues[1]
            fun attr(key: String): String? =
                Regex("""$key\s*=\s*("([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE).find(attrs)
                    ?.let { g ->
                        g.groupValues[2].takeIf { it.isNotBlank() }
                            ?: g.groupValues[3].trim().takeIf { it.isNotBlank() }
                    }
            if (attr("TYPE")?.equals("AUDIO", ignoreCase = true) == true) {
                audio.add(AudioRendition(
                    language = attr("LANGUAGE")?.trim()?.takeIf { it.isNotBlank() },
                    name = attr("NAME")?.trim().orEmpty(),
                    isDefault = attr("DEFAULT")?.equals("YES", ignoreCase = true) == true,
                ))
            }
        }
        return MasterFacts(best, audio)
    }

    /** Canonical language tag. */
    private fun languageTagOf(lang: String?, name: String): String? {
        val s = (lang ?: name).trim().lowercase()
        if (s.isEmpty()) return null
        return when {
            s.contains("dual") || s.contains("multi") || s.contains("+") || s.contains("&") -> "Multi"
            s.contains("हिन्द") || s.contains("हिंद") || s == "hi" || s == "hin" || s.startsWith("hi-") || s.contains("hindi") -> "Hindi"
            s == "en" || s == "eng" || s.startsWith("en-") || s.contains("english") -> "English"
            s.contains("urdu") || s == "ur" || s == "urd" -> "Urdu"
            s.contains("tamil") || s == "ta" || s == "tam" -> "Tamil"
            s.contains("telugu") || s == "te" || s == "tel" -> "Telugu"
            s.contains("malayalam") || s == "ml" || s == "mal" -> "Malayalam"
            s.contains("kannada") || s == "kn" || s == "kan" -> "Kannada"
            s.contains("marathi") || s == "mr" || s == "mar" -> "Marathi"
            s.contains("bengali") || s.contains("bangla") || s == "bn" || s == "ben" -> "Bengali"
            s.contains("japanese") || s == "ja" || s == "jpn" || s == "jp" -> "Japanese"
            s.contains("korean") || s == "ko" || s == "kor" -> "Korean"
            s.contains("chinese") || s == "zh" || s == "zho" || s == "chi" -> "Chinese"
            s.contains("spanish") || s == "es" || s == "spa" -> "Spanish"
            s.contains("french") || s == "fr" || s == "fra" || s == "fre" -> "French"
            s.contains("arabic") || s == "ar" || s == "ara" || s == "arb" -> "Arabic"
            else -> null
        }
    }

    /** Trailing "(Language)" tag of an enriched label, else "". */
    internal fun bracketTag(l: ExtractorLink): String =
        Regex("""\(([A-Za-z]+)\)\s*$""").find(l.source ?: l.name ?: "")?.groupValues?.get(1).orEmpty()

    /** Language the HOST ITSELF declares (server brand "VidHindi", URL "lan=hindi", CDN path token) - a declaration, not a. guess. */
    internal fun declaredHindi(sourceName: String?, url: String?): Boolean {
        val hay = buildString {
            sourceName?.let { append(it.lowercase()); append('|') }
            url?.let { append(it.lowercase()) }
        }
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    /** Cheap height token. . . 1080p. mp4") - a host declaration, not a guess (0 = nothing declared). */
    internal fun resolutionFromUrl(url: String?): Int {
        if (url.isNullOrBlank()) return 0
        return Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE).findAll(url)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
    }

    /** Budget for one master fetch inside emission (runs in the live fill window, parallel per link - must never gate. playback). */
    internal const val LABEL_PROBE_BUDGET_MS = 2500L
    /** Fast-path probe budget (Cineverse direct links): fast CDNs answer in ms; slow ones fall back to URL facts. */
    internal const val FAST_PROBE_BUDGET_MS = 1000L

    /** Cache of label probes per stream url (per process) so a re-pull of the same source never re-fetches the same master. */
    private val labelProbeCache = java.util.concurrent.ConcurrentHashMap<String, MasterFacts?>()

    /** PROBE the stream for its real (language, height) facts: HLS masters are fetched (2. 5s budget) and parsed (variants +. */
    internal suspend fun probeLabelFacts(l: ExtractorLink, budgetMs: Long = LABEL_PROBE_BUDGET_MS): MasterFacts? {
        val url = l.url ?: return null
        if (url.isBlank()) return null
        if (l.type != ExtractorLinkType.M3U8) {
            val declared = resolutionFromUrl(url).takeIf { it > 0 } ?: return null
            return MasterFacts(declared, emptyList())
        }
        labelProbeCache[url]?.let { return it }
        val facts = withTimeoutOrNull(budgetMs) {
            runCatching {
                val headers = LinkedHashMap<String, String>()
                headers.putAll(l.headers)
                if (l.referer?.isNotBlank() == true && !headers.containsKey("Referer")) {
                    headers["Referer"] = l.referer
                }
                val text = app.get(url, timeout = 3, headers = headers).text
                parseMasterFacts(text)
            }.getOrNull()
        }
        // Cache hits only: ConcurrentHashMap rejects null values, so a miss
        // simply probes again (bounded by the 2.5s budget) instead of NPE-ing.
        if (facts != null) labelProbeCache[url] = facts
        return facts
    }

    /** Enrich a link's label with. */
    internal suspend fun enrichLabel(l: ExtractorLink, probeBudgetMs: Long = LABEL_PROBE_BUDGET_MS): ExtractorLink {
        val facts = probeLabelFacts(l, probeBudgetMs)
        val height = facts?.bestHeight?.takeIf { it > 0 }
            ?: l.quality.takeIf { it > 0 }
            ?: resolutionFromUrl(l.url).takeIf { it > 0 }
            ?: 0
        val langTag: String? = when {
            // Multi-language master → "Multi" (what the player's ABR exposes).
            facts != null && facts.audio.mapNotNull { languageTagOf(it.language, it.name) }.distinct().size > 1 -> "Multi"
            // The single track the player autoplays (DEFAULT or only rendition).
            facts != null && facts.audio.isNotEmpty() ->
                (facts.audio.firstOrNull { it.isDefault } ?: facts.audio.singleOrNull())
                    ?.let { languageTagOf(it.language, it.name) }
            // Host declaration ("VidHindi", "lan=hindi", "MyFlixer Hindi").
            declaredHindi(l.source, l.url) -> "Hindi"
            else -> null
        }

        // Rebuild the label: base (minus any existing tag/res token), then the (Language) bracket.
        var base = (l.source ?: l.name ?: "Server").trim()
        base = base.replace(Regex("""\s*\((?:Hindi|English|Multi|Urdu|Tamil|Telugu|Malayalam|Kannada|Marathi|Bengali|Japanese|Korean|Chinese|Spanish|French|Arabic|Unknown)\)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d{3,4}p\s*$|\s+4K\s*$""", RegexOption.IGNORE_CASE), "")
            .dedupeResolution()
        if (langTag != null && !base.contains(langTag, ignoreCase = true)) {
            base = "$base ($langTag)"
        }
        if (base == (l.source ?: l.name)) return l // unchanged → keep identity.
        return newExtractorLink(
            source = base, name = base, url = l.url,
            type = l.type,
        ) {
            referer = l.referer
            quality = height.takeIf { it > 0 } ?: l.quality
            headers = l.headers
            extractorData = l.extractorData
            audioTracks = l.audioTracks ?: emptyList()
        }
    }

    /** Collapse a duplicated resolution token inside a server label so the player never shows e. g. */
    private fun String.dedupeResolution(): String {
        val token = Regex("""\d{3,4}p|4K|2160p""", RegexOption.IGNORE_CASE)
        val seen = mutableSetOf<String>()
        val out = token.replace(this) { m ->
            val k = m.value.lowercase()
            if (!seen.add(k)) "" else m.value
        }
        return out.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /** Build the header set for a request to url. */
    internal fun headersFor(
        url: String,
        referer: String?,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(sharedHeaders.size + extra.size + 2)
        out.putAll(sharedHeaders)
        out.putAll(extra)
        if (!referer.isNullOrBlank()) out["Referer"] = referer
        if (isCineverseHost(url) || url.contains("serve_m3u8", ignoreCase = true)) {
            // vibuxer. com / proxy. php signs only when it sees the originating site as Referer and a matching Origin.
            val ref = referer?.takeIf { it.isNotBlank() } ?: "${MultimoviesDomainResolver.currentDomain()}/"
            out["Referer"] = ref
            val origin = ref.substringBefore("/seasons/")
                .substringBefore("/movies/")
                .substringBefore("/tvshows/")
                .takeIf { it.startsWith("http") } ?: MultimoviesDomainResolver.currentDomain()
            out["Origin"] = origin
        }
        return out
    }

    /** Resolve a possibly-relative against, producing an absolute https URL. Handles protocol-relative (//), absolute, and. root-relative. */
    internal fun resolveRelative(baseUrl: String, path: String): String {
        if (path.startsWith("//")) return "https:$path"
        if (path.startsWith("http", ignoreCase = true)) return path
        val schemeHost = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /** Recursively follow iframes until the deepest player URL is found. */
    suspend fun unwrapEmbed(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = sharedHeaders,
    ): String {
        var current = url
        repeat(MAX_UNWRAP_LEVELS) {
            // Terminal: a URL that IS a stream - direct m3u8/mp4 file, or a modiplay/proxy relay (serve_m3u8=1) that serves the.
            if (current.contains(".m3u8", ignoreCase = true) ||
                current.contains(".mp4", ignoreCase = true) ||
                current.contains("serve_m3u8=", ignoreCase = true)
            ) return current
            val text = runCatching {
                app.get(current, timeout = 5, headers = headersFor(current, referer, headers)).text
            }.getOrNull() ?: return current
            // Short-circuit: a page exposing a proxy/stream URL is the player itself.
            buildProxyStreamUrl(text, current)?.let { return it }
            extractStreamUrl(text)?.let { return it }
            extractVideoSourceUrl(text, current)?.let { return it }
            val next = Jsoup.parse(text).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: return current
            val resolved = resolveRelative(current, next)
            if (resolved == current) return current
            current = resolved
        }
        return current
    }

    /** @param sources raw server list (launch order = caller order) @param timeoutMs per-source hard timeout in ms (project. */
    suspend fun pull(
        sources: List<Source>,
        timeoutMs: Long = MultimoviesProvider.SOURCE_TIMEOUT_MS,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: suspend (ExtractorLink) -> Unit = {},
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        // All sources launch together (parallel); completion order = arrival order. No priority ordering, no speed tracking ().
        coroutineScope {
            sources.map { src ->
                async {
                    val result = withTimeoutOrNull(timeoutMs) {
                        runCatching {
                            val found = extractSource(src, onSubtitle = { subs.add(it) })
                            found.map { l ->
                                val link = toExtractorLink(src, l)
                                links.add(link)
                                onLink(link)
                                link
                            }
                        }
                    }
                    result?.getOrNull().orEmpty()
                }
            }.awaitAll()
        }

        subs.forEach { onSubtitle(it) }
        links.toList()
    }

    /** Wrap a raw extractor link with the source's headers/referer defaults. */
    private suspend fun toExtractorLink(src: Source, l: ExtractorLink): ExtractorLink =
        newExtractorLink(
            source = l.source,
            name = l.name,
            url = l.url,
            type = l.type,
        ) {
            referer = l.referer ?: src.url
            quality = l.quality
            headers = l.headers ?: src.headers
            extractorData = null
            audioTracks = l.audioTracks ?: emptyList()
        }

    /** True when points at a YouTube host (trailer embeds). */
    internal fun isYouTubeHost(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("youtube.com") || host.contains("youtu.be") ||
            host.contains("youtube-nocookie")
    }

    /** Unified per-source extraction: dedicated host extractor, then registry, then sniff. */
    private suspend fun extractSource(
        src: Source,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        // Trailers/YouTube embeds are not streams - never surface them as sources.
        if (isYouTubeHost(src.url)) return emptyList()

        // GDMirror (streams.iqsmartgames.com): JS-shell embed resolved statically
        // (embed vars -> mymovieapi/myseriesapi -> embedhelper2 mirrors -> packed HLS masters).
        if (hostOf(src.url).contains("iqsmartgames")) {
            val out = mutableListOf<ExtractorLink>()
            for (g in GdMirrorExtractor.extract(src.url)) {
                val label = g.name.ifBlank { "GDMirror" }
                val refererHeader = g.referer ?: src.referer ?: src.url
                val headers = src.headers + ("Referer" to refererHeader)
                out += newExtractorLink(
                    source = label,
                    name = label,
                    url = g.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = refererHeader
                    quality = getQualityFromName(g.fileName.ifEmpty { g.url })
                    this.headers = headers
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        // Nxsha: the web player resolves servers/sources through same-origin CryptoJS-AES envelopes (no stream URL in any.
        if (hostOf(src.url).contains("nxsha")) {
            val subs = mutableListOf<SubtitleFile>()
            val nxLinks = NxshaExtractor.extract(src) { subs.add(SubtitleFile(it.lang, it.url)) }
            subs.forEach { onSubtitle(it) }
            val out = mutableListOf<ExtractorLink>()
            for (s in nxLinks) {
                val source = s.name
                // DASH manifests (.mpd) are adaptive: mistyping them as VIDEO breaks playback.
                val type = if (s.isM3u8 || s.url.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else if (s.isDash || s.url.contains(".mpd", ignoreCase = true)) {
                    ExtractorLinkType.DASH
                } else ExtractorLinkType.VIDEO
                // Streams come back without headers.
                val refererHeader = src.referer ?: src.url
                val headers = src.headers + ("Referer" to refererHeader)
                out += newExtractorLink(
                    source = source,
                    name = source,
                    url = s.url,
                    type = type,
                ) {
                    referer = refererHeader
                    quality = getQualityFromName(s.quality.ifEmpty { s.url })
                    this.headers = headers
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        // VidEm (videm. xyz): signed-token multi-server HLS player.
        if (hostOf(src.url).contains("videm")) {
            val out = mutableListOf<ExtractorLink>()
            for (s in VidemExtractor.extract(src)) {
                // BASE label only - emitOne's enrichLabel appends the) + resolution for the final identity.
                val label = "VidEm (${s.name})"
                out += newExtractorLink(
                    source = label,
                    name = label,
                    url = s.url,
                    type = if (s.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = s.headers["Referer"] ?: src.url
                    quality = getQualityFromName(s.quality.ifEmpty { s.url })
                    this.headers = s.headers + src.headers
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        // 111Movies (api. shows. st): deterministic JSON API behind the vidlove player SPA. Emits source. url (adaptive HLS.
// master playlist) + subs.
        if (hostOf(src.url).contains("shows.st")) {
            val subs = mutableListOf<SubtitleFile>()
            val showLinks = ShowsExtractor.extract(src, onSubtitle = { subs.add(it) })
            subs.forEach { onSubtitle(it) }
            val out = mutableListOf<ExtractorLink>()
            for (s in showLinks) {
                // BASE label only - emitOne's enrichLabel appends the) + resolution for the final identity.
                val label = "111Movies (${s.name})"
                out += newExtractorLink(
                    source = label,
                    name = label,
                    url = s.url,
                    type = if (s.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    referer = s.headers["Referer"] ?: src.url
                    quality = getQualityFromName(s.quality.ifEmpty { s.url })
                    this.headers = s.headers + src.headers
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        // If unwrapEmbed already surfaced a playable stream or proxy relay URL, emit it directly - no extra page fetch needed.
        directStreamLink(src)?.let { return listOf(it) }

        // Stage a: CloudStream extractor registry (installed/built-in extractors).
        val found = mutableListOf<ExtractorLink>()
        val registryOk = runCatching {
            loadExtractor(
                url = src.url,
                referer = src.referer,
                subtitleCallback = onSubtitle,
                callback = { found.add(it) },
            )
        }.getOrDefault(false)
        if (registryOk && found.isNotEmpty()) {
            val out = mutableListOf<ExtractorLink>()
            for (l in found) {
                // BASE label only - emitOne's enrichLabel appends the) + resolution for the final identity.
                val label = src.name
                out += newExtractorLink(
                    source = label,
                    name = label,
                    url = l.url,
                    type = l.type,
                ) {
                    referer = l.referer
                    quality = l.quality
                    this.headers = l.headers
                    extractorData = null
                    audioTracks = l.audioTracks ?: emptyList()
                }
            }
            return out
        }

        // Stage b: generic m3u8/mp4 sniff.
        return sniff(src)
    }

    /** When src. url is itself a playable stream (serve_m3u8 proxy relay, m3u8 or mp4), build the ExtractorLink right away. */
    private suspend fun directStreamLink(src: Source): ExtractorLink? {
        val u = src.url
        val isStream = u.contains("serve_m3u8=1", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".webm", ignoreCase = true)
        if (!isStream) return null
        // BASE label only - emitOne's enrichLabel appends the) + resolution for the final identity.
        val label = src.name
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        // Use headersFor so the serve_m3u8 proxy request, which the player will replay against the emitted link.
        val headers = headersFor(u, src.referer, src.headers)
        return newExtractorLink(
            source = label,
            name = label,
            url = u,
            type = type,
        ) {
            referer = src.referer ?: u
            quality = getQualityFromName(u)
            this.headers = headers
            extractorData = null
            audioTracks = emptyList()
        }
    }

    /** Generic fallback: fetch the player page and harvest the first stream URL using a multi-strategy approach (direct. */
    private suspend fun sniff(src: Source): List<ExtractorLink> {
        val headers = headersFor(src.url, src.referer, src.headers)
        val text = runCatching {
            app.get(src.url, timeout = 5, headers = headers).text
        }.getOrNull() ?: return emptyList()

        val stream = buildProxyStreamUrl(text, src.url)
            ?: extractStreamUrl(text)
            ?: extractVideoSourceUrl(text, src.url)
            ?: extractFromJsConfig(text)
            ?: decodeEncodedStreamUrl(text)
            ?: return emptyList()

        // BASE label only - emitOne's enrichLabel appends the) + resolution for the final identity.
        val label = src.name
        val linkType = if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        return listOf(
            newExtractorLink(
                source = label,
                name = label,
                url = stream,
                type = linkType,
            ) {
                referer = src.url
                quality = getQualityFromName(stream)
                this.headers = headers
                extractorData = null
                audioTracks = emptyList()
            }
        )
    }
}

/** Whether a is keyed by an IMDB id or a TMDB id. */
enum class SourceId { IMDB, TMDB }

/** Ids + season/episode resolved during and needed to build direct stream URLs in. */
data class SourceMeta(
    val imdbId: String,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Maps the exact url passed to loadLinks() (episode url / movie url) to its ids and season/episode, populated during. */
object SourceMetaCache {
    private val map = ConcurrentHashMap<String, SourceMeta>()
    fun put(key: String, meta: SourceMeta) = map.put(key, meta)
    fun get(key: String): SourceMeta? = map[key]
}

/** One resolved dooplayer server: its display name, the final (post-unwrap) stream/embed URL, the admin-ajax round-trip. */
data class ResolvedEmbed(
    val name: String,
    val url: String,
    val embedUrl: String? = null,
    val unwrapped: Boolean = false,
    /** Admin-ajax option identity (post|nume|type); same display name may list twice (live: 2x Nxsha). */
    val key: String = "",
)

/** Stable identity of one dooplayer option; duplicate display names stay distinct. */
internal fun dooplayOptionKey(post: String, nume: String, type: String): String =
    "$post|$nume|$type"

/** Dedupe identity of a resolved embed: option key when known, else the embed URL. */
internal fun ResolvedEmbed.embedIdentity(): String =
    key.ifEmpty { embedUrl ?: url }

/** Session-level cache of player sources prefetched in the background while a movie's detail page is open, keyed by the. */
object EmbedPrefetchCache {
    private class Entry(
        val embeds: List<ResolvedEmbed>?,
        val inFlight: Deferred<List<ResolvedEmbed>>?,
        val expiresAt: Long,
        /** Servers a still-running prefetch has already resolved, published one-by-one in arrival order so a Play tap. */
        val partial: MutableList<ResolvedEmbed> =
            Collections.synchronizedList(mutableListOf()),
    )

    private const val TTL_MS = 4 * 60 * 1000L
    private const val MAX_SIZE = 32

    private val map = ConcurrentHashMap<String, Entry>()

    /** Completed, still-valid results for, or null (also when in-flight). */
    fun get(key: String): List<ResolvedEmbed>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.embeds
    }

    /** Publish ONE resolved server into a still-running prefetch's partial list (called. */
    fun publish(key: String, embed: ResolvedEmbed) {
        val e = map[key] ?: return
        if (System.currentTimeMillis() > e.expiresAt || e.embeds != null) return
        synchronized(e.partial) {
            if (e.partial.none { it.embedIdentity() == embed.embedIdentity() }) e.partial.add(embed)
        }
    }

    /** Snapshot of the servers arrived so far for an IN-FLIGHT prefetch (empty once the entry is complete. */
    fun arrived(key: String): List<ResolvedEmbed> {
        val e = map[key] ?: return emptyList()
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return emptyList()
        }
        if (e.embeds != null) return emptyList()
        return synchronized(e.partial) { e.partial.toList() }
    }

    /** Runs resolve for key unless one is already running or completed, in which case it joins that work. */
    suspend fun resolveOrJoin(
        key: String,
        resolve: suspend () -> List<ResolvedEmbed>,
        timeoutAtMs: Long = System.currentTimeMillis() + TTL_MS,
    ): List<ResolvedEmbed> {
        get(key)?.let { return it }
        map[key]?.inFlight?.let { return it.await() }

        val job = CompletableDeferred<List<ResolvedEmbed>>()
        map.putIfAbsent(key, Entry(null, job, timeoutAtMs))
        if (map[key]?.inFlight === job) {
            // We own the resolution: run it and complete the shared job.
            return try {
                val result = resolve()
                if (result.isNotEmpty()) put(key, result) else invalidate(key)
                job.complete(result)
                result
            } catch (t: Throwable) {
                invalidate(key)
                job.completeExceptionally(t)
                throw t
            }
        }
        // Lost the race: await the winner's job, or its already-cached result.
        return map[key]?.inFlight?.await() ?: get(key) ?: emptyList()
    }

    /** Wait up to timeoutMs for an in-flight prefetch of key to finish. */
    suspend fun awaitInFlight(key: String, timeoutMs: Long = 1500L): List<ResolvedEmbed>? {
        get(key)?.let { return it }
        val e = map[key] ?: return null
        val job = e.inFlight ?: return null
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    fun put(key: String, embeds: List<ResolvedEmbed>) {
        if (embeds.isEmpty()) return
        if (map.size >= MAX_SIZE) {
            map.entries.minByOrNull { it.value.expiresAt }?.key?.let { map.remove(it) }
        }
        map[key] = Entry(embeds, null, System.currentTimeMillis() + TTL_MS)
    }

    /** Drops a stale entry so the next play attempt falls back to full resolution. */
    fun invalidate(key: String) {
        map.remove(key)
    }
}

/** Session-level cache of resolved stream links per (imdbId, season, episode). */
object LinkCache {
    private data class Entry(
        val links: List<ExtractorLink>,
        val expiresAt: Long,
    )
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(imdbId: String?, season: Int?, episode: Int?): List<ExtractorLink>? {
        // Blank ids (tmdb-only titles store "") must never hit the cache:
        // every such title would otherwise share one key and replay another title.
        if (imdbId.isNullOrBlank()) return null
        val key = "$imdbId|$season|$episode"
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.links
    }

    fun put(imdbId: String?, season: Int?, episode: Int?, links: List<ExtractorLink>) {
        if (imdbId.isNullOrBlank() || links.isEmpty()) return
        map["$imdbId|$season|$episode"] = Entry(links, System.currentTimeMillis() + TTL_MS)
    }
}

/** Per-load-url cache of resolved stream links for the fast-start path. */
object FastStartCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<ExtractorLink>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
        return e.links
    }

    /** Merge for into the existing set (dedup by url) and extend TTL. */
    fun put(key: String, links: List<ExtractorLink>) {
        if (links.isEmpty()) return
        val existing = get(key).orEmpty()
        val merged = (existing + links).distinctBy { it.url }
        map[key] = Entry(merged, System.currentTimeMillis() + TTL_MS)
    }
}

/** A curated, id-based public streaming source. */
class GlobalSource(
    val name: String,
    val idType: SourceId,
    val buildUrl: (id: String, season: Int?, episode: Int?) -> String?,
    val headers: Map<String, String> = emptyMap(),
)

/** Curated global source registry (dooplayer-independent). */
object GlobalSources {
    val list: List<GlobalSource> = listOf(
        GlobalSource(
            name = "2embed.cc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://www.2embed.cc/embed/tv?imdb=$id&s=$s&e=$e"
                else "https://www.2embed.cc/embed/movie?imdb=$id"
            },
            headers = mapOf("Referer" to "https://www.2embed.cc/"),
        ),
        GlobalSource(
            // vidsrc-embed. su now 301-redirects to vsembed. ru; pointing straight at the live host saves a round-trip on every.
// loadLinks.
            name = "VidSrc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://vsembed.ru/embed/$id/$s-$e"
                else "https://vsembed.ru/embed/$id"
            },
            headers = mapOf("Referer" to "https://vsembed.ru/"),
        ),
        GlobalSource(
            // 111Movies backend - the player. vidlove. cc SPA is JS-only, but its data API at api. shows. st is fully.
            name = "111Movies",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://api.shows.st/tv?id=$id&season=$s&episode=$e&mode=json"
                else "https://api.shows.st/movie?id=$id&mode=json"
            },
            headers = mapOf("Referer" to "https://player.vidlove.cc/"),
        ),
        GlobalSource(
            // Nxsha's own player API (nitro, MbPly, Citadel, StremFx, . . . ).
            name = "Nxsha",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://nxsha.space/embed/tv/$id/$s/$e"
                else "https://nxsha.space/embed/movie/$id"
            },
            headers = mapOf("Referer" to "https://nxsha.space/"),
        ),
        GlobalSource(
            // VidEm (videm. xyz) is a fast, multi-server HLS player discovered via the vidapi. xyz aggregator.
            name = "VidEm",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://videm.xyz/embed/tv/$id/$s/$e"
                else "https://videm.xyz/embed/movie/$id"
            },
            headers = mapOf("Referer" to "https://videm.xyz/"),
        ),
    )
}


