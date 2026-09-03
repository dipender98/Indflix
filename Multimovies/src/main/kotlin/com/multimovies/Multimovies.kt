package com.multimovies
/**

 * FILE: Multimovies.kt â€” the Multimovies plugin (entry + site + engine).
 *
 * Everything Multimovies-site-specific lives here:
 *  - [Multimovies]          plugin entrypoint (@CloudstreamPlugin); registers
 *                           the provider with CloudStream.
 *  - [MultimoviesProvider]  MainAPI â€” scrapes multimovies.motorcycles
 *                           (search / load / loadLinks, dooplayer servers).
 *  - [MultiSourcePuller]    parallel-pull engine: races every source with a
 *                           per-source timeout, unwraps embeds, sorts links
 *                           by latency + Hindi-audio preference.
 *  - [GlobalSources]        registry of id-keyed global embed sources
 *                           (2embed, VidSrc, 111Movies, ...).
 *
 * Reusable shared services live in Core.kt; third-party stream APIs in
 * Extractors.kt.
 */

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Registers the Multimovies provider with CloudStream.
 */
@CloudstreamPlugin
class Multimovies : Plugin() {
    override fun load(context: Context) {
        // All providers/extractors added here are registered in the app.
        registerMainAPI(MultimoviesProvider())
    }
}

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

/** Matches any multimovies.* host (the site rotates its TLD, so URL checks must
 *  never pin the hostname — old cached-domain URLs still count as on-site after
 *  [MultimoviesDomainResolver] points at a fresh mirror). */
private val MULTIMOVIES_HOST_REGEX =
    Regex("""^https?://(?:www\.)?multimovies\.[a-z]{2,10}(?:/|$)""", RegexOption.IGNORE_CASE)

/** Matches the scheme+host prefix of a URL (for host rewriting in [MultimoviesProvider.liveUrl]). */
private val URL_HOST_REGEX = Regex("""^(https?://[^/]+)""")

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
 *  first). Verified Aug 2026: live diagnostic confirmed Cineverse (current CDN
 *  vibuxer.com, serve_m3u8=1 proxy), nxsha (.cc/.space) and nhdapi respond;
 *  the legacy modiplay.com / gdmirror.com / nxsha.com back-ends are all dead.
 *  Cineverse is the verified fast + Hindi source.
 *
 *  Nxsha (index 1) expands internally into its own server fleet resolved by
 *  [NxshaExtractor] through nxsha.space's encrypted /api/servers+/api/sources:
 *  Nitro (fastest), MbPly, MhPly, Citadel, AwsPly, StremFx, VidHindi(4K embed),
 *  CastVid, Lolly, Prvibd, Stvvid, Ophm, AsiaLug, TunWatch, Gbru. Nitro-first
 *  ordering lives in NxshaExtractor.orderServers; SourceSpeedTracker re-ranks
 *  after the first load.
 *
 *  The tail lists the id-based GlobalSources (2embed.cc, VidSrc, 111Movies) —
 *  verified-responding public hosts; Nxsha's own sources match the "nxsha"
 *  entry above. Nxsha's hardcoded player fallbacks (videasy/vidnest/vidfast/
 *  moviesapi/vidzee) were probed Aug 2026: all JS-rendered SPAs or 403 for
 *  non-browsers, so they are NOT added here; their upstreams still arrive via
 *  Nxsha's own provider pipeline.
 *  Note: gdmirror was removed entirely (host refused TCP, no alt TLD resolves);
 *  add it back here if it ever comes back to life. */
internal val SOURCE_PRIORITY: List<String> = listOf(
    "Cineverse",
    "nxsha",
    "nhdapi",
    "2embed",
    "VidSrc",
    "111Movies",
    // VidEm (videm.xyz): fast multi-server HLS player verified Aug 2026 via the
    // vidapi.xyz aggregator; resolved by VidemExtractor's signed-token API.
    "VidEm",
)

/** CSS selector for the item containers on a Multimovies search-results page. */
private val SEARCH_ITEMS_SELECTOR = "div#archive-content div.item, div.search-page div.result-item, article.item, div.ml-items div.item, div.results div.result, ul.ml-posts li, div#content div.post, div.items div.item"

/**
 * Multimovies - a CloudStream provider that scrapes the Multimovies site. The site
 * rotates its live domain every few days (announced on the multimovies.wtf gateway);
 * [MultimoviesDomainResolver] keeps mainUrl pointed at the current mirror.
 *
 * Source handling policy (per project requirements):
 *  - Multiple streaming sources are pulled in PARALLEL.
 *  - Each source has a hard TIMEOUT of ~30 seconds (SOURCE_TIMEOUT_MS).
 *  - Sources are tried in PRIORITY order (reliable + fast first). The priority list
 *    is defined in [SOURCE_PRIORITY] and is used both to order parallel launches and
 *    to sort the returned links.
 */
class MultimoviesProvider : MainAPI() {

    override var mainUrl = MultimoviesDomainResolver.SEED_DOMAIN
    override var name = "Multimovies"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private val commonHeaders = mapOf("User-Agent" to userAgent)
    // Solves the Cloudflare managed challenge on the live multimovies.* domain. Created
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

    // Getter (not a one-shot val): the genre URLs must follow mainUrl whenever
    // MultimoviesDomainResolver rotates to a new live domain mid-session.
    override val mainPage
        get() = mainPageOf(
        // Bollywood (5)
        Pair("$mainUrl/genre/bollywood-movies/", "Bollywood Movies"),
        Pair("$mainUrl/genre/netflix/", "Netflix"),
        Pair("$mainUrl/genre/amazon-prime/", "Amazon Prime"),
        Pair("$mainUrl/genre/disney-hotstar/", "Disney+ Hotstar"),
        Pair("$mainUrl/genre/zee-5/", "Zee5"),
        // Global Movies (5)
        Pair("$mainUrl/genre/hollywood/", "Hollywood"),
        Pair("$mainUrl/genre/action/", "Action"),
        Pair("$mainUrl/genre/comedy/", "Comedy"),
        Pair("$mainUrl/genre/horror/", "Horror"),
        Pair("$mainUrl/genre/science-fiction/", "Sci-Fi"),
        // Series (5)
        Pair("$mainUrl/tvshows/", "Web Series"),
        Pair("$mainUrl/genre/k-drama/", "K-Drama"),
        Pair("$mainUrl/genre/crime/", "Crime Series"),
        Pair("$mainUrl/genre/thriller/", "Thriller Series"),
        Pair("$mainUrl/genre/south-indian/", "South Indian"),
        // Anime (3)
        Pair("$mainUrl/genre/anime-hindi/", "Hindi Dub Anime"),
        Pair("$mainUrl/genre/anime-series/", "Anime Series"),
        Pair("$mainUrl/genre/anime-movies/", "Anime Movies"),
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

        /** How many top-priority player servers the movie-page background
         *  prefetch resolves ahead of the Play tap. */
        const val EMBED_PREFETCH_COUNT = 2

        /** Max number of detail-page Documents cached in memory. Beyond this,
         *  oldest entries are evicted when a new page is fetched. */
        private const val MM_DOC_CACHE_MAX_SIZE = 24
    }

    private fun priorityOf(serverName: String): Int {
        val idx = SOURCE_PRIORITY.indexOfFirst { serverName.contains(it, ignoreCase = true) }
        return if (idx == -1) SOURCE_PRIORITY.size else idx
    }

    /** True when [url] belongs to any multimovies.* domain. The site rotates its
     *  TLD, so the check must not pin the hostname — URLs from a doc cached under
     *  an old domain still count after [MultimoviesDomainResolver] self-heals. */
    private fun isMultimoviesUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return MULTIMOVIES_HOST_REGEX.containsMatchIn(url)
    }

    /** Rewrite [url]'s scheme+host to the current live domain when it's a
     *  multimovies.* URL, so old-domain URLs (session caches, home-page requests
     *  built before a rotation) fetch from the live mirror. Other hosts pass
     *  through untouched. */
    private fun liveUrl(url: String): String {
        val m = URL_HOST_REGEX.find(url) ?: return url
        if (!m.groupValues[1].substringAfter("://").startsWith("multimovies.", ignoreCase = true)) return url
        val liveHost = URL_HOST_REGEX.find(mainUrl)?.value ?: return url
        return liveHost + url.substring(m.range.last + 1)
    }

    /**
     * Run [block] against the current [mainUrl]. When the result fails (null or
     * [retryIf] returns true) or an exception escapes, force a fresh gateway
     * resolve and retry once — so a rotated/parked domain self-heals without a
     * plugin republish. Only runs the extra resolve on an actual failure, never
     * on the happy path.
     */
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
        // Rewrite a stale multimovies.* host to the current live domain first, so
        // cached/home-page URLs keep fetching after a domain rotation.
        val fetchUrl = liveUrl(url)
        // Fast path: rely on the persisted cookie jar; no WebView solve.
        try {
            val doc = app.get(fetchUrl, timeout = timeoutSeconds, headers = headers).document
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

    /** Backward-compatible wrapper used by [load] and [loadLinks]. */
    internal suspend fun solveDocument(
        url: String,
        timeoutSeconds: Long = 15,
    ): Document = fetchDoc(url, timeoutSeconds = timeoutSeconds, required = true)
        ?: throw ErrorLoadingException("Failed to fetch $url")

    /** Reuses the memoized detail-page doc when present; otherwise fetches,
     *  memoizes, and returns it — so main-page card taps get the same doc reuse
     *  as search taps. Returns null on failure (never throws). */
    internal suspend fun cachedDocOrFetch(url: String): Document? {
        mmDocCache[url]?.let { return it }
        val doc = runCatching { solveDocument(url) }.getOrNull() ?: return null
        if (mmDocCache.size >= MM_DOC_CACHE_MAX_SIZE) {
            mmDocCache.keys.firstOrNull()?.let { mmDocCache.remove(it) }
        }
        mmDocCache[url] = doc
        return doc
    }

    // ------------------------------------------------------------------
    // Search (TMDB/SIMKL-driven; Multimovies is only touched in the background
    // to resolve each hit to its real page URL, never for metadata).
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? = withDomainRetry(retryIf = { it == null }) {
        SearchCache.get(query)?.let { return@withDomainRetry it }

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
            } ?: return@withDomainRetry null

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
        // Emit slugs for every common spelling of the title ("&" vs "and",
        // apostrophes dropped, punctuation stripped) so a TMDB title still
        // guesses the site's URL.
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
            val guessedTitle = guessed.selectFirst("h1, div.sheader h1, meta[property=og:title]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()
            if (guessedTitle != null && titleDistance(guessedTitle, title) <= 1) {
                imdbUrlCache[key] = guessUrl
                mmDocCache[guessUrl] = guessed
                return guessed
            }
        }

        // Fallback: site search by title, pick the closest title match. The
        // server drops queries carrying a literal "&" or certain punctuation
        // ("?s=Locke+%26+Key" returns nothing), so retry the alternative
        // spellings until one returns results.
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
            ?.minByOrNull { titleDistance(it.second, title) } ?: return null
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
        val href = a.attr("href").takeIf { isMultimoviesUrl(it) } ?: return null
        val itemTitle = selectFirst("img")?.attr("alt")
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()
            ?: a.text()?.trim()
        return if (itemTitle.isNullOrBlank()) null else href to itemTitle
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

    // ------------------------------------------------------------------
    // Main page
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Load (detail page)
    // ------------------------------------------------------------------

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

            // Fire-and-forget: pre-resolve the top-priority player servers while
            // the user reads the detail page, so tapping Play emits links without
            // waiting for a page fetch + admin-ajax round-trips.
            if (isMovie) prefetchEmbeds(realUrl, doc)

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
                ?: doc.select("div.sgeneros a, .genre a").mapNotNull { it.text() }
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
                seasonDocs.forEach { sDoc ->
                    if (sDoc == null) return@forEach
                    sDoc.select("ul.episodios li, div.eps div.ep, .episodios li").forEachIndexed { i, ep ->
                        val epLink = ep.selectFirst("a[href]")?.attr("href")?.takeIf { isMultimoviesUrl(it) }
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

    /** Parse the Dooplay "Video Sources" list: each li.dooplay_player_option
     *  carries data-nume (source index) and data-type; the post id comes from
     *  meta#dooplay-ajax-counter with the li's own data-post as fallback.
     *  Trailers (YouTube embeds) are excluded — they are not playable sources. */
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

    /** Background movie-only prefetch: resolve the top [EMBED_PREFETCH_COUNT]
     *  dooplayer servers (static priority order) through admin-ajax and unwrap,
     *  then park the results in [EmbedPrefetchCache] so a Play tap can skip the
     *  page fetch AND admin-ajax entirely. [EmbedPrefetchCache.resolveOrJoin]
     *  deduplicates concurrent starts; any error simply leaves the cache empty.
     *  Never blocks or fails load(). */
    private fun prefetchEmbeds(pageUrl: String, doc: Document) {
        searchScope.launch {
            runCatching {
                EmbedPrefetchCache.resolveOrJoin(pageUrl, resolve = { resolveTopEmbeds(pageUrl, doc) })
            }
        }
    }

    /** Resolve the top-priority player servers for [pageUrl] to their final
     *  post-unwrap URLs. Empty when the page exposes no usable options. */
    private suspend fun resolveTopEmbeds(pageUrl: String, doc: Document): List<ResolvedEmbed> {
        val options = parsePlayerOptions(doc, pageUrl)
            .sortedBy { priorityOf(it.first) }
            .take(EMBED_PREFETCH_COUNT)
        if (options.isEmpty()) return emptyList()
        return coroutineScope {
            options.map { (name, triple) ->
                async {
                    runCatching {
                        val e = resolveEmbed(pageUrl, name, triple.first, triple.second, triple.third)
                            ?: return@runCatching null
                        e.copy(url = MultiSourcePuller.unwrapEmbed(e.url, referer = pageUrl, headers = commonHeaders))
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ------------------------------------------------------------------
    // Load links - parallel pulling with per-source timeout + priority
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withDomainRetry(retryIf = { !it }) {
        var meta = SourceMetaCache.get(data)

        // Fast path 1: cached links emit instantly so playback starts right away
        // (like other plugins). The LinkCache TTL is short (5 min) so stale URLs
        // don't linger; no liveness probe delays the first frame.
        if (meta != null) {
            LinkCache.get(meta.imdbId, meta.season, meta.episode)?.let { cached ->
                if (cached.isNotEmpty()) {
                    cached.forEach { runCatching { callback(it) } }
                    return@withDomainRetry true
                }
            }
        }

        // Fast path 2: embeds prefetched in the background while the detail page
        // was open — skips the page fetch AND admin-ajax entirely. If a prefetch
        // is still running (Play tapped early), await it briefly rather than
        // duplicating the network work.
        val awaited = EmbedPrefetchCache.awaitInFlight(data, timeoutMs = 1200L)
        val prefetched = awaited != null && awaited.isNotEmpty()
        var embeds: List<ResolvedEmbed> = awaited.orEmpty()

        if (embeds.isEmpty()) {
            val doc = cachedDocOrFetch(data) ?: return@withDomainRetry false
            embeds = coroutineScope {
                parsePlayerOptions(doc, data).map { (name, triple) ->
                    async {
                        runCatching { resolveEmbed(data, name, triple.first, triple.second, triple.third) }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        }

        // The raw dooplayer embed URLs also carry the IMDB id, which recovers
        // meta when load() never resolved one.
        if (meta == null) {
            embeds.firstNotNullOfOrNull { extractImdbIdFromUrl(it.embedUrl ?: it.url) }?.let { id ->
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
        val labelCounter = ConcurrentHashMap<String, Int>()

        coroutineScope {
            val embedJobs = orderedEmbeds.map { e ->
                async {
                    val finalUrl = MultiSourcePuller.unwrapEmbed(e.url, referer = data, headers = commonHeaders)
                    // After unwrap, the URL may now point at a downstream CDN
                    // (Cineverse -> vibuxer / serve_m3u8 proxy). Augment headers
                    // with the host-specific pair the proxy requires so the
                    // link the player replays against doesn't 403.
                    val srcHeaders = commonHeaders + MultiSourcePuller.headersFor(finalUrl, referer = data)
                    val src = MultiSourcePuller.Source(
                        name = e.name,
                        url = finalUrl,
                        referer = data,
                        headers = srcHeaders,
                        // Cached ids let the Nxsha extractor resolve even when the
                        // embed URL itself carries no tmdb/imdb marker.
                        tmdbId = meta?.tmdbId,
                        imdbId = meta?.imdbId,
                        latencyMs = e.latencyMs,
                    )
                    sourceRefs.add(src)
                    pullSource(src, labelCounter, data, emitted, found, subtitleCallback, callback)
                }
            }
            val globalJobs = globalSources.map { g ->
                sourceRefs.add(g)
                async {
                    pullSource(g, labelCounter, data, emitted, found, subtitleCallback, callback)
                }
            }
            (embedJobs + globalJobs).awaitAll()
        }

        val sorted = MultiSourcePuller.sortLinks(found.toList(), sourceRefs.toList(), ::priorityOf, preferHindi = true)
        val deduped = dedupeByHostQuality(sorted)
        if (deduped.isEmpty()) {
            // A fully dead prefetched entry would otherwise poison every retry
            // within its TTL — drop it so the next attempt takes the full path.
            if (prefetched) EmbedPrefetchCache.invalidate(data)
        } else if (meta != null) {
            LinkCache.put(meta.imdbId, meta.season, meta.episode, deduped)
        }

        return@withDomainRetry deduped.isNotEmpty()
    }

    /** Pull a single source, streaming found links to [callback] as they arrive and
     *  collecting them (deduped at emission time) into [found] for the final sort
     *  and link cache. Links arrive already carrying their stable identity
     *  (`source == name == "<Server>[ Hindi]"`, set at origin in
     *  MultiSourcePuller) — no renaming happens here.
     *
     *  Cineverse fast path: if [src] is a Cineverse CDN URL that already points
     *  straight at a stream (the `serve_m3u8=1` proxy URL that `unwrapEmbed`
     *  surfaces), the playable link is emitted immediately via [callback] — no
     *  second `pull()` round-trip, no registry lookup, no `loadExtractor` step.
     *  Recorded as a fast success in [SourceSpeedTracker] so the sort key
     *  reflects the win. */
    private suspend fun pullSource(
        src: MultiSourcePuller.Source,
        labelCounter: ConcurrentHashMap<String, Int>,
        data: String,
        emitted: MutableSet<String>,
        found: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): List<ExtractorLink> {
        /** Disambiguate duplicate labels within a single load: the first link
         *  with a given label keeps it; subsequent links with the same label
         *  get "-2", "-3" … appended. Both source and name are rewritten so
         *  priority entries and the player list stay in sync. */
        fun disambiguate(l: ExtractorLink): ExtractorLink {
            val label = l.source
            val n = labelCounter.compute(label) { _, v -> (v ?: 0) + 1 }!!
            if (n == 1) return l
            val dis = "$label-$n"
            return ExtractorLink(
                source = dis, name = dis, url = l.url,
                referer = l.referer, quality = l.quality,
                headers = l.headers, extractorData = l.extractorData,
                type = l.type, audioTracks = l.audioTracks ?: emptyList(),
            )
        }

        val cineverseFastLink = if (MultiSourcePuller.isCineverseHost(src.url) &&
            (src.url.contains("serve_m3u8=1", ignoreCase = true) ||
                src.url.contains(".m3u8", ignoreCase = true) ||
                src.url.contains(".mp4", ignoreCase = true))
        ) buildDirectLink(src) else null
        if (cineverseFastLink != null) {
            val key = "${hostOf(cineverseFastLink.url ?: "")}|${cineverseFastLink.quality}"
            if (emitted.add(key)) {
                val dis = disambiguate(cineverseFastLink)
                found.add(dis)
                SourceSpeedTracker.record(src.name, 0L, success = true)
                runCatching { callback(dis) }
            }
            return listOf(cineverseFastLink)
        }
        return MultiSourcePuller.pull(
            sources = listOf(src),
            timeoutMs = SOURCE_TIMEOUT_MS,
            priorityOf = ::priorityOf,
            onSubtitle = subtitleCallback,
            onLink = { l ->
                val key = "${hostOf(l.url ?: "")}|${l.quality}"
                if (emitted.add(key)) {
                    val dis = disambiguate(l)
                    found.add(dis)
                    runCatching { callback(dis) }
                }
            },
        )
    }

    /** Build the ExtractorLink emitted by the Cineverse fast path. Mirrors
     *  [MultiSourcePuller.directStreamLink]'s labeling + header logic so the
     *  link shape matches what the registry path would have produced. */
    private fun buildDirectLink(
        src: MultiSourcePuller.Source,
    ): ExtractorLink {
        val u = src.url
        val label = MultiSourcePuller.linkLabel(
            src.name,
            MultiSourcePuller.isHindiHint(src.name, src.url, u),
        )
        val headers = MultiSourcePuller.headersFor(u, src.referer, src.headers)
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(u)
        return ExtractorLink(
            source = label,
            name = label,
            url = u,
            referer = src.referer ?: u,
            quality = quality,
            headers = headers,
            extractorData = null,
            type = type,
            audioTracks = emptyList(),
        )
    }

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
        return ResolvedEmbed(name, embed, latencyMs, embedUrl = embed)
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
                imdbId = meta.imdbId,
                season = meta.season,
                episode = meta.episode,
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

/**
 * Self-healing live-domain resolver for the Multimovies Dooplay site.
 *
 * `multimovies.wtf` is a static gateway page that always links to the CURRENT
 * live domain (rotated every few days: `.motorcycles` -> `.beer` -> ...). The
 * provider's old hardcoded `mainUrl` silently pointed at the old domain after
 * every rotation, requiring a manual republish.
 *
 * This object resolves the live domain from the gateway, caches it (6 h TTL)
 * and only re-fetches when forced (a request failed) or the cache expires.
 * Pure extraction ([extractLiveDomain]) is JVM-testable; [resolve] needs the
 * CloudStream runtime (`app`).
 */
internal object MultimoviesDomainResolver {

    /** Stable gateway URL that always announces the current live domain. */
    internal const val LANDING_URL = "https://multimovies.wtf/"

    /** Last-known-good domain; used only when the gateway itself is unreachable
     *  and no cached value exists. */
    internal const val SEED_DOMAIN = "https://multimovies.beer"

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

    /** Normalize a candidate href: strip trailing slash, drop www, lowercase.
     *  Returns null when the href is not a bare multimovies live domain. */
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

    /** The last known live domain (or the seed when never resolved) — no network. */
    internal fun currentDomain(): String = cached ?: SEED_DOMAIN

    /**
     * Current live domain: cached value (6 h TTL) unless [forceRefresh] or the
     * cache is stale. A failed gateway fetch falls back to the last cached value,
     * then the seed, so the provider always gets a usable domain. Thread-safe.
     */
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

/**
 * Session-scoped memory of per-source extraction speed. [MultiSourcePuller.pull]
 * records how long each source took to produce links (or fail/timeout), and the
 * final link sort uses the measured average — so "fast" is decided by the user's
 * actual network, with the curated SOURCE_PRIORITY list only as the cold-start
 * fallback.
 *
 * In-memory only (this CloudStream build exposes no persistent settings API);
 * resets on app restart.
 */
internal object SourceSpeedTracker {
    /** Penalty (ms) added per failed attempt, so sources that fail often are
     *  demoted below consistently-fast ones. */
    private const val FAILURE_PENALTY_MS = 30_000L

    private data class Stats(var successes: Int = 0, var totalMs: Long = 0L, var failures: Int = 0) {
        fun avgMs(): Double = if (successes == 0) Double.MAX_VALUE
        else (totalMs + failures * FAILURE_PENALTY_MS).toDouble() / successes
    }

    private val map = java.util.concurrent.ConcurrentHashMap<String, Stats>()

    fun record(name: String, durationMs: Long, success: Boolean) {
        map.compute(name) { _, s ->
            val stats = s ?: Stats()
            if (success) {
                stats.successes++
                stats.totalMs += durationMs
            } else {
                stats.failures++
            }
            stats
        }
    }

    /** Learned average extraction latency for [name], or null when never measured.
     *  Measured-but-never-succeeded sources return [Double.MAX_VALUE] (slowest). */
    fun averageLatency(name: String): Double? = map[name]?.avgMs()
}

/**
 * MultiSourcePuller - the source-priority / parallel-pull / timeout engine.
 *
 * Given a list of (serverName, url) pairs it:
 *   1. Orders them by measured speed ([SourceSpeedTracker]) with the static
 *      [MultimoviesProvider.SOURCE_PRIORITY] as fallback.
 *   2. Launches ALL of them concurrently (parallel pulling).
 *   3. Wraps each individual source in a [timeoutMs] timeout (default 30s).
 *      A single slow/dead source can never block the others.
 *   4. Returns the successfully extracted links, sorted by measured speed.
 *
* Per source it runs a unified extraction pipeline:
     *     a. CloudStream's extractor registry (loadExtractor)
     *     b. a generic m3u8/mp4 sniff of the player page
 *
 * This is intentionally decoupled from the provider so the strategy can be
 * tuned (timeouts, priority weights, concurrency limits) in one place.
 */
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
        val latencyMs: Long = Long.MAX_VALUE,
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

    /**
     * Pure helper: pull the first stream URL (m3u8/mp4/webm/mkv) from raw page text.
     * Testable without network access.
     */
    internal fun extractStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        // Normalize JSON-escaped slashes/backslashes so the plain `https?://` regex
        // can still match URLs embedded in JSON (\/ -> /).
        val normalized = text.replace("\\/", "/").replace("\\\"", "\"")
        for (r in STREAM_URL_REGEXES) {
            r.findAll(normalized).firstOrNull()?.groupValues?.get(0)?.let { raw ->
                val cleaned = raw.trim('"', '\'')
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return null
    }

    /** Detect a modiplay-style proxy player endpoint in page text, e.g.
     *  `\/proxy.php?serve_m3u8=1&ref=...&url=<url-encoded m3u8>&ebd=...`.
     *  The proxy endpoint serves the playlist directly (Content-Type mpegurl),
     *  so the returned URL is playable as-is. Returns null when absent. */
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

    /** Pull a stream URL from a `<video src>` / `<source src>` element. */
    internal fun extractVideoSourceUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")
            ?.attr("src")?.trim() ?: return null
        if (src.isBlank()) return null
        return resolveRelative(baseUrl, src).takeIf { it.startsWith("http") }
    }

    /** Pull a stream URL from common JS player config shapes embedded in the page:
     *  `sources:[{file:"...m3u8"}]`, `file:"...m3u8"`, `url:"...m3u8"`,
     *  `hlsUrl:"..."`, `streamUrl:"..."`. Returns null when absent. */
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

    /** Decode a URL-encoded m3u8 URL found inside a query string (e.g. `url=%2F..%2Fmaster.m3u8...`)
     *  and return it as a plain https URL. */
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

    /** Hosts that back the Cineverse modiplay/vibuxer serve_m3u8=1 proxy. The
     *  proxy returns 403 to bare requests; it requires a same-site Referer and
     *  Origin (it was handed out by the Multimovies dooplayer player) or it
     *  won't sign the playlist. Used by [headersFor] so every Cineverse call
     *  - admin-ajax wrap, unwrap, and the final proxy fetch - carries the
     *  required pair. The set is a best-effort list; [isCineverseHost] also
     *  matches any URL carrying `serve_m3u8` so a CDN host rotation can't
     *  silently drop the required headers. */
    private val cineverseCdnHosts = setOf(
        "vibuxer.com",
        "www.vibuxer.com",
        "modiplay.com",
        "www.modiplay.com",
        "cinemodiy.com",
        "cinehive.com",
        "play.cineverse.com",
        "cdn.cineverse.com",
    )

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").lowercase()

    /** True when the URL belongs to the Cineverse modiplay/vibuxer CDN, or is a
     *  `serve_m3u8=1` proxy relay (the signature of the Cineverse flow) on any
     *  host. The URL-wide marker check makes header injection survive CDN host
     *  rotation. */
    internal fun isCineverseHost(url: String): Boolean =
        cineverseCdnHosts.contains(hostOf(url)) ||
            hostOf(url).let { h -> cineverseCdnHosts.any { h == it || h.endsWith(".$it") } } ||
            url.contains("serve_m3u8", ignoreCase = true)

    /** Deterministic identity for an emitted link: `<Server>[ Hindi]`.
     *  Every ExtractorLink must carry this exact string in BOTH `source` and
     *  `name`: CloudStream saves player priorities keyed on an exact match of
     *  `source` while the server list displays `name`, so any drift (CDN
     *  suffixes, quality suffixes, per-load counters) breaks the user's
     *  ranking. Deliberately free of runtime-derived parts — extractor/extension
     *  availability and CDN hosts must never influence it. */
    internal fun linkLabel(base: String?, hindi: Boolean): String =
        (base?.trim()?.takeIf { it.isNotEmpty() } ?: "Multimovies") +
            (if (hindi) " Hindi" else "")

    /** Build the header set for a request to [url]. The Cineverse CDN requires
     *  the page that linked to it as Referer/Origin; for other hosts the
     *  caller-supplied headers and shared UA are used as-is. Pure / cheap. */
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
            // vibuxer.com / proxy.php signs only when it sees the originating
            // site as Referer and a matching Origin. The plugin's embed URL
            // comes from the dooplayer player on the live multimovies.* mirror,
            // so that's the referer we advertise.
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

    /** Resolve a possibly-relative [path] against [baseUrl], producing an absolute
     *  https URL. Handles protocol-relative (//), absolute, and root-relative. */
    internal fun resolveRelative(baseUrl: String, path: String): String {
        if (path.startsWith("//")) return "https:$path"
        if (path.startsWith("http", ignoreCase = true)) return path
        val schemeHost = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /**
     * Recursively follow iframes until the deepest player URL is found. A wrapper
     * page (e.g. an aggregator/redirector) is fetched and its first iframe src
     * followed, up to [MAX_UNWRAP_LEVELS]. Direct stream URLs (.m3u8/.mp4) and
     * proxy relay URLs (serve_m3u8=1) short-circuit: the page that exposes them
     * IS the stream, so [MultiSourcePuller.pull] can emit it directly without
     * re-fetching.
     */
    suspend fun unwrapEmbed(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = sharedHeaders,
    ): String {
        var current = url
        repeat(MAX_UNWRAP_LEVELS) {
            // Terminal: a URL that IS a stream — direct m3u8/mp4 file, or a
            // modiplay/proxy relay (serve_m3u8=1) that serves the playlist
            // directly. Checked on the whole URL string (not just the host)
            // because these markers live in the path/query; fetching them as
            // HTML would only re-download a playlist and risk misparsing it.
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

    /** True when a link name/label indicates a Hindi audio track. */
    internal fun isHindi(link: ExtractorLink): Boolean {
        val hay = buildString {
            link.name?.let { append(it) }
            append(' ')
            link.source?.let { append(it) }
        }.lowercase()
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    /** True when a source URL, name, or stream URL contains a Hindi/streamhg hint.
     *  Used by [sniff] to name the extracted link so the Hindi-preference sort
     *  can prefer it. Checks the proxy platform (streamhg = Hindi), explicit
     *  language params (lan=hindi), and any Hindi text in the source name. */
    internal fun isHindiHint(sourceName: String, sourceUrl: String, streamUrl: String?): Boolean {
        val hay = buildString {
            append(sourceName.lowercase())
            append('|')
            append(sourceUrl.lowercase())
            if (streamUrl != null) { append('|'); append(streamUrl.lowercase()) }
        }
        return hay.contains("streamhg") || hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी") || hay.contains("lan=hindi") || hay.contains("modiplay") || hay.contains("serve_m3u8")
    }

    /**
     * @param sources   raw server list (unsorted is fine, sorting happens here)
     * @param timeoutMs per-source hard timeout in ms (project default: 15_000)
     * @param priorityOf maps a server name to a sort index (lower = better)
     * @param preferHindi when true, Hindi-audio links win latency/priority ties
     * @param onSubtitle called for each subtitle found
     * @param onLink optional: called immediately for every extracted link (streaming —
     *        lets the player start the fastest source instead of waiting for all sources)
     * @return list of extractor links, ordered by measured speed then priority then latency then Hindi
     */
    suspend fun pull(
        sources: List<Source>,
        timeoutMs: Long = MultimoviesProvider.SOURCE_TIMEOUT_MS,
        priorityOf: (String) -> Int,
        preferHindi: Boolean = true,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit = {},
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        // Launch higher-priority sources first so the reliable/fast ones (Cineverse)
        // start resolving and emit their link before slower fallbacks.
        val orderedSources = sources.sortedBy { priorityOf(it.name) }
        coroutineScope {
            orderedSources.map { src ->
                async {
                    val startedMs = System.currentTimeMillis()
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
                    val made = result?.getOrNull().orEmpty()
                    SourceSpeedTracker.record(
                        src.name,
                        System.currentTimeMillis() - startedMs,
                        success = made.isNotEmpty(),
                    )
                }
            }.awaitAll()
        }

        subs.forEach { onSubtitle(it) }
        sortLinks(links, sources, priorityOf, preferHindi)
    }

    /** Wrap a raw extractor link with the source's headers/referer defaults.
     *  Identity is NOT touched here: every extractSource branch already emits
     *  final `source == name == linkLabel(...)` labels. */
    private fun toExtractorLink(src: Source, l: ExtractorLink): ExtractorLink =
        ExtractorLink(
            source = l.source,
            name = l.name,
            url = l.url,
            referer = l.referer ?: src.url,
            quality = l.quality,
            headers = l.headers ?: src.headers,
            extractorData = null,
            type = l.type,
            audioTracks = l.audioTracks ?: emptyList(),
        )

    /** Normalize an [ExtractorLink.source] / [Source.name] into a stable key for
     *  speed tracking and priority lookup: strips any duplicate counter
     *  ("Name-2"), trailing language annotation (" Hindi") and the trailing
     *  parenthesized server label, so "Nxsha (Nitro) Hindi-2",
     *  "Cineverse-2" and "Cineverse (Vibuxer)" all reduce to their
     *  SOURCE_PRIORITY name. */
    internal fun sourceKey(source: String?): String {
        if (source == null) return ""
        return source
            .replace(Regex("""-\d+$"""), "")
            .replace(Regex("""\s+Hindi$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\([^)]*\)$"""), "")
            .trim()
    }

    /**
     * Order links for the player. Primary key is the curated static [priorityOf]
     * ranking (so Cineverse / the reliable fast sources always come first);
     * measured per-source speed and per-call embed latency only break ties within
     * the same priority, then the Hindi preference, then adaptive HLS over fixed
     * progressive files — an m3u8 manifest lets the player start quickly at a
     * lower rendition and ramp quality up automatically.
     */
    internal fun sortLinks(
        links: List<ExtractorLink>,
        sources: List<Source>,
        priorityOf: (String) -> Int,
        preferHindi: Boolean = true,
    ): List<ExtractorLink> {
        val latencyByName = sources.associate { it.name to it.latencyMs }
        val comparator = compareBy<ExtractorLink>(
            { priorityOf(sourceKey(it.source)) },
            { SourceSpeedTracker.averageLatency(sourceKey(it.source)) ?: Double.MAX_VALUE },
            { latencyByName[sourceKey(it.source)] ?: Long.MAX_VALUE },
        ).thenByDescending { if (preferHindi) isHindi(it) else false }
            .thenByDescending { it.type == ExtractorLinkType.M3U8 }
        return links.sortedWith(comparator)
    }

    /** True when [url] points at a YouTube host (trailer embeds). */
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
        // Trailers/YouTube embeds are not streams — never surface them as sources.
        if (isYouTubeHost(src.url)) return emptyList()

        // Nxsha: the web player resolves servers/sources through same-origin
        // CryptoJS-AES envelopes (no stream URL in any HTML), so it needs the
        // dedicated extractor too.
        if (hostOf(src.url).contains("nxsha")) {
            val subs = mutableListOf<SubtitleFile>()
            val nxLinks = NxshaExtractor.extract(src) { subs.add(SubtitleFile(it.lang, it.url)) }
            subs.forEach { onSubtitle(it) }
            return nxLinks.map { s ->
                val source = s.name
                val type = if (s.isM3u8 || s.url.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else ExtractorLinkType.VIDEO
                // Streams come back without headers; mirror browser behavior by
                // advertising the embed page as Referer unless told otherwise.
                val refererHeader = s.headers["Referer"] ?: src.referer ?: src.url
                val headers = buildMap {
                    putAll(src.headers)
                    putAll(s.headers)
                    if (!s.headers.containsKey("Referer")) put("Referer", refererHeader)
                }
                ExtractorLink(
                    source = source,
                    name = source,
                    url = s.url,
                    referer = refererHeader,
                    quality = getQualityFromName(s.quality.ifEmpty { s.url }),
                    headers = headers,
                    extractorData = null,
                    type = type,
                    audioTracks = emptyList(),
                )
            }
        }

        // VidEm (videm.xyz): signed-token multi-server HLS player. The embed page
        // is server-rendered, so the dedicated extractor reproduces the
        // embed -> sources -> play flow deterministically (no browser needed).
        if (hostOf(src.url).contains("videm")) {
            return VidemExtractor.extract(src).map { s ->
                val label = linkLabel(
                    "VidEm (${s.name})",
                    isHindiHint(src.name, src.url, s.url),
                )
                ExtractorLink(
                    source = label,
                    name = label,
                    url = s.url,
                    referer = s.headers["Referer"] ?: src.url,
                    quality = getQualityFromName(s.quality.ifEmpty { s.url }),
                    headers = s.headers + src.headers,
                    extractorData = null,
                    type = if (s.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    audioTracks = emptyList(),
                )
            }
        }

        // 111Movies (api.shows.st): deterministic JSON API behind the vidlove
        // player SPA. Emits source.url (adaptive HLS master playlist) + subs.
        if (hostOf(src.url).contains("shows.st")) {
            val subs = mutableListOf<SubtitleFile>()
            val showLinks = ShowsExtractor.extract(src, onSubtitle = { subs.add(it) })
            subs.forEach { onSubtitle(it) }
            return showLinks.map { s ->
                val label = linkLabel(
                    "111Movies (${s.name})",
                    isHindiHint(src.name, src.url, s.url),
                )
                ExtractorLink(
                    source = label,
                    name = label,
                    url = s.url,
                    referer = s.headers["Referer"] ?: src.url,
                    quality = getQualityFromName(s.quality.ifEmpty { s.url }),
                    headers = s.headers + src.headers,
                    extractorData = null,
                    type = if (s.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    audioTracks = emptyList(),
                )
            }
        }

        // If unwrapEmbed already surfaced a playable stream or proxy relay URL,
        // emit it directly — no extra page fetch needed.
        directStreamLink(src)?.let { return listOf(it) }

        // Stage a: CloudStream extractor registry (installed/built-in extractors).
        // Relabeled to the source's stable identity — registry links carry bare
        // extractor names that would miss SOURCE_PRIORITY and drift whenever
        // extensions are installed or removed.
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
            return found.map { l ->
                val label = linkLabel(
                    src.name,
                    isHindi(l) || isHindiHint(src.name, src.url, l.url),
                )
                ExtractorLink(
                    source = label,
                    name = label,
                    url = l.url,
                    referer = l.referer,
                    quality = l.quality,
                    headers = l.headers,
                    extractorData = null,
                    type = l.type,
                    audioTracks = l.audioTracks ?: emptyList(),
                )
            }
        }

        // Stage b: generic m3u8/mp4 sniff.
        return sniff(src)
    }

    /** When [src.url] is itself a playable stream (serve_m3u8 proxy relay, m3u8 or
     *  mp4), build the ExtractorLink right away. Returns null otherwise so the
     *  generic pipeline runs. */
    private fun directStreamLink(src: Source): ExtractorLink? {
        val u = src.url
        val isStream = u.contains("serve_m3u8=1", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".webm", ignoreCase = true)
        if (!isStream) return null
        val label = linkLabel(src.name, isHindiHint(src.name, src.url, u))
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        // Use headersFor so the Cineverse serve_m3u8 proxy request, which the
        // player will replay against the emitted link, carries the same
        // Referer/Origin pair the embed originally needed.
        val headers = headersFor(u, src.referer, src.headers)
        return ExtractorLink(
            source = label,
            name = label,
            url = u,
            referer = src.referer ?: u,
            quality = getQualityFromName(u),
            headers = headers,
            extractorData = null,
            type = type,
            audioTracks = emptyList(),
        )
    }

    /** Generic fallback: fetch the player page and harvest the first stream URL
     *  using a multi-strategy approach (direct regex, proxy pattern, video/source
     *  tags, JS config objects, URL-encoded m3u8). */
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

        val label = linkLabel(src.name, isHindiHint(src.name, src.url, stream))
        val linkType = if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        return listOf(
            ExtractorLink(
                source = label,
                name = label,
                url = stream,
                referer = src.url,
                quality = getQualityFromName(stream),
                headers = headers,
                extractorData = null,
                type = linkType,
                audioTracks = emptyList(),
            )
        )
    }
}

/** Whether a [GlobalSource] is keyed by an IMDB id or a TMDB id. */
enum class SourceId { IMDB, TMDB }

/** Ids + season/episode resolved during [MultimoviesProvider.load] and needed to
 *  build direct stream URLs in [MultimoviesProvider.loadLinks]. */
data class SourceMeta(
    val imdbId: String,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Maps the exact url passed to loadLinks() (episode url / movie url) to its ids
 *  and season/episode, populated during load() so loadLinks() never re-solves
 *  Cloudflare just to get an IMDB/TMDB id. */
object SourceMetaCache {
    private val map = ConcurrentHashMap<String, SourceMeta>()
    fun put(key: String, meta: SourceMeta) = map.put(key, meta)
    fun get(key: String): SourceMeta? = map[key]
}

/** One resolved dooplayer server: its display name, the final (post-unwrap)
 *  stream/embed URL, the admin-ajax round-trip latency as a speed hint, and
 *  the raw pre-unwrap embed URL (still carrying the IMDB id) used only for
 *  last-resort meta recovery. Lives here so [EmbedPrefetchCache] and
 *  MultimoviesProvider share a single definition. */
data class ResolvedEmbed(
    val name: String,
    val url: String,
    val latencyMs: Long,
    val embedUrl: String? = null,
)

/** Session-level cache of player sources prefetched in the background while a
 *  movie's detail page is open, keyed by the page URL loadLinks() receives.
 *  A completed hit lets playback start without re-fetching the page or hitting
 *  admin-ajax at all. TTL is short because embed/stream URLs carry expiring
 *  signed tokens; bounded with evict-oldest like SearchCache.
 *
 *  While a prefetch is still running the entry holds its coroutine, so a Play
 *  tap (or a detail-page revisit) awaits/joins the same job instead of
 *  duplicating the network work. */
object EmbedPrefetchCache {
    private data class Entry(
        val embeds: List<ResolvedEmbed>?,
        val inFlight: Deferred<List<ResolvedEmbed>>?,
        val expiresAt: Long,
    )

    private const val TTL_MS = 4 * 60 * 1000L
    private const val MAX_SIZE = 32

    private val map = ConcurrentHashMap<String, Entry>()

    /** Completed, still-valid results for [key], or null (also when in-flight). */
    fun get(key: String): List<ResolvedEmbed>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.embeds
    }

    /** Runs [resolve] for [key] unless one is already running or completed, in
     *  which case it joins that work. Exactly one caller executes [resolve];
     *  the result is cached (or the entry invalidated when empty) and shared
     *  with every concurrent caller. */
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

    /** Wait up to [timeoutMs] for an in-flight prefetch of [key] to finish.
     *  Returns completed results if present or finished within the wait, else
     *  null (caller falls back to the full resolution path). */
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

/** Session-level cache of resolved stream links per (imdbId, season, episode).
 *  Reopening the same title/episode reuses cached streams (zero probe latency);
 *  entries expire so stale URLs/tokens don't linger forever. TTL is short (5 min)
 *  because most stream URLs carry expiring signed tokens. */
object LinkCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(imdbId: String?, season: Int?, episode: Int?): List<ExtractorLink>? {
        if (imdbId == null) return null
        val key = "$imdbId|$season|$episode"
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.links
    }

    fun put(imdbId: String?, season: Int?, episode: Int?, links: List<ExtractorLink>) {
        if (imdbId == null || links.isEmpty()) return
        map["$imdbId|$season|$episode"] = Entry(links, System.currentTimeMillis() + TTL_MS)
    }
}

/** A curated, id-based public streaming source. Extensible — add more hosts by
 *  appending entries; the runtime probe (in loadLinks/pull) keeps only the ones
 *  that actually respond from the user's network. */
class GlobalSource(
    val name: String,
    val idType: SourceId,
    val buildUrl: (id: String, season: Int?, episode: Int?) -> String?,
    val headers: Map<String, String> = emptyMap(),
)

/** Curated global source registry (dooplayer-independent). URL patterns verified
 *  from public documentation / health-checked provider lists. Note: many public
 *  embed hosts rotate/expire fast (vixsrc.to went Next.js, vidsrc.net died,
 *  vidlink.pro API 404, multiembed.mov 403), so the list is kept to hosts that
 *  actually respond; the dooplayer embeds resolved from the site remain the
 *  primary source path. Append new hosts as they become available — the runtime
 *  probe (in loadLinks/pull) keeps only the ones that answer from the user's
 *  network.
 *
 *  Aug 2026 live diagnostic: each host is called on its final hop so we skip
 *  any 301 chain at runtime (vidsrc-embed.su -> vsembed.ru, 111movies.com ->
 *  111movies.net -> player.vidlove.cc). The Referer matches the live final
 *  host so the player page actually renders. */
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
            // Aug 2026: vidsrc-embed.su now 301-redirects to vsembed.ru; pointing
            // straight at the live host saves a round-trip on every loadLinks.
            name = "VidSrc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://vsembed.ru/embed/$id/$s-$e"
                else "https://vsembed.ru/embed/$id"
            },
            headers = mapOf("Referer" to "https://vsembed.ru/"),
        ),
        GlobalSource(
            // Aug 2026: 111Movies backend — the player.vidlove.cc SPA is JS-only,
            // but its data API at api.shows.st is fully deterministic: the JSON
            // response carries source.url (adaptive HLS master playlist) and
            // subtitles. IMDB-keyed: /movie accepts an IMDB id directly, /tv
            // requires a TMDB id (used only when already resolved during load()'s
            // metadata fetch — no extra TMDB public-API call). Dedicated
            // ShowsExtractor branch in MultiSourcePuller.
            name = "111Movies",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://api.shows.st/tv?id=$id&season=$s&episode=$e&mode=json"
                else "https://api.shows.st/movie?id=$id&mode=json"
            },
            headers = mapOf("Referer" to "https://player.vidlove.cc/"),
        ),
        GlobalSource(
            // Aug 2026: Nxsha's own player API (nitro, MbPly, Citadel, StremFx,
            // ...). IMDB-keyed: NxshaExtractor resolves an IMDB id to TMDB through
            // Nxsha's own fk.nxsha.xyz proxy (not the TMDB public API), then
            // decrypts the encrypted /api/servers + /api/sources.
            name = "Nxsha",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://nxsha.space/embed/tv/$id/$s/$e"
                else "https://nxsha.space/embed/movie/$id"
            },
            headers = mapOf("Referer" to "https://nxsha.space/"),
        ),
        GlobalSource(
            // Aug 2026: VidEm (videm.xyz) is a fast, multi-server HLS player
            // discovered via the vidapi.xyz aggregator. IMDB-keyed: its embed page
            // normalizes the id internally, so an IMDB id works directly. The
            // dedicated VidemExtractor branch in MultiSourcePuller resolves its
            // signed-token /api.php sources + play endpoints without a browser.
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


