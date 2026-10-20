package com.multimovies

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

@CloudstreamPlugin
class Multimovies : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(MultimoviesProvider())
        openSettings = { ctx -> Settings.openSettings(ctx) }
    }
}

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

internal val MULTIMOVIES_HOST_REGEX =
    Regex("""^https?://(?:[a-z0-9-]+\.)?multimovies\.[a-z]{2,10}(?:/|$)""", RegexOption.IGNORE_CASE)

private val URL_HOST_REGEX = Regex("""^(https?://[^/]+)""")

private fun Element.posterUrl(): String? =
    selectFirst("img")?.let { img ->
        listOf(img.attr("src"), img.attr("data-src"), img.attr("data-lazy-src"),
            img.attr("data-original"), img.attr("data-lazyload")).firstOrNull { it.isNotBlank() }
    }?.takeIf { it.isNotBlank() }

internal fun parseRating(item: Element): Double? {
    val raw = item.selectFirst(
        "span.dt_rating_vgs, span.imdb, div.imdb-rating, " +
        ".rating span, span.rating, [class*='imdb'] span"
    )?.text()?.trim()
        ?: return null
    return raw.replace(Regex("[^\\d.]"), "")
        .takeIf { it.isNotBlank() }?.toDoubleOrNull()
}

internal val SOURCE_PRIORITY: List<String> = listOf(
    "Cineverse",
    "GDMIRROR",
    "GD mirror",
    "VIDOUT",
    "Vidsync",
    "Bingr",
    "Filmu",
    "VidBolt",
    "nxsha",
    "nhdapi",
    "2embed",
    "VidSrc",
    "VidEm",
)

private val SEARCH_ITEMS_SELECTOR = "article.poster-card, article.cinema-slide, div.catalog-grid article.poster-card, a.cinejoy-rec-card, div#archive-content div.item, div.search-page div.result-item, article.item"

private const val TITLE_SELECTOR = "h1.cinejoy-detail-title, div.sheader h1, h1, meta[property=og:title]"

internal fun stripSiteAffix(raw: String, siteName: String = "Multimovies"): String {
    val t = raw.trim()
    if (t.isEmpty() || siteName.isBlank()) return t
    val site = Regex.escape(siteName.trim())
    val lead = Regex("""(?i)^$site\s*[|:-]\s*""")
    val trail = Regex("""(?i)\s*[|:-]\s*$site$""")
    return trail.replace(lead.replace(t, ""), "").trim().takeIf { it.isNotEmpty() } ?: t
}

internal data class DooplayHit(
    val title: String,
    val url: String,
    val poster: String?,
    val year: String?,
    val rating: Double?,
)

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

internal fun extractDooplayNonce(html: String): String? {
    if (html.isBlank()) return null
    val block = Regex("""dtGonza\s*=\s*\{[^}]*\}""").find(html)?.value ?: return null
    return Regex(""""nonce"\s*:\s*"([A-Za-z0-9]+)"""").find(block)?.groupValues?.get(1)
}

internal data class V2Hit(
    val title: String,
    val url: String,
    val poster: String?,
    val year: String?,
    val rating: Double?,
    val type: String?,
)

internal fun sanitizeV2Poster(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val t = raw.trim()
    val idx = t.lastIndexOf("http", startIndex = maxOf(0, t.length - 8), ignoreCase = true)
    val cut = if (idx > 0) t.substring(idx) else t
    return upgradePosterUrl(if (cut.startsWith("//")) "https:$cut" else cut)
}

internal fun parseV2SearchApi(raw: String?): List<V2Hit> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("results")
        val out = ArrayList<V2Hit>()
        fun push(o: JSONObject?) {
            if (o == null) return
            val kind = o.optString("type")
            if (kind != "movie" && kind != "tv") return
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (title.isEmpty()) return
            val url = o.optString("url").trim()
            if (url.isEmpty()) return
            val poster = sanitizeV2Poster(o.optString("poster").ifBlank { o.optString("poster_path") })
            val year = o.optInt("year", -1).takeIf { it > 0 }?.toString()
                ?: Regex("""\d{4}""").find(o.optString("release_date") + o.optString("first_air_date"))?.value
            val rating = o.optDouble("rating", -1.0).takeIf { it > 0 }
                ?: o.optDouble("vote_average", -1.0).takeIf { it > 0 }
            out.add(V2Hit(title, url, poster, year, rating, kind))
        }
        if (arr != null) {
            for (i in 0 until arr.length()) push(arr.optJSONObject(i))
        } else {
            val movies = root.optJSONArray("movies")
            val shows = root.optJSONArray("tv_shows") ?: root.optJSONArray("tv")
            if (movies != null) for (i in 0 until movies.length()) push(movies.optJSONObject(i))
            if (shows != null) for (i in 0 until shows.length()) push(shows.optJSONObject(i))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

internal data class V2Server(val name: String, val url: String)

internal fun parseV2WatchConfig(html: String): List<V2Server> {
    if (html.isBlank()) return emptyList()
    return try {
        val m = Regex("""watchConfig\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val servers = JSONObject(m.groupValues[1]).optJSONArray("initialServers") ?: return emptyList()
        (0 until servers.length()).mapNotNull { i ->
            val o = servers.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").replace("\\/", "/").trim()
            if (!url.startsWith("http")) return@mapNotNull null
            val name = o.optString("name").trim().ifEmpty { "Server" }
            V2Server(name, url)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

internal fun extractTmdbIdFromServerUrl(url: String): String? {
    val u = url.trim()
    Regex("""[?&]id=(\d{2,10})""").find(u)?.groupValues?.get(1)?.let { return it }
    Regex("""/(?:movie|tv)/(\d{2,10})""").find(u)?.groupValues?.get(1)?.let { return it }
    Regex("""/embed/(?:movie|tv)/(\d{2,10})""").find(u)?.groupValues?.get(1)?.let { return it }
    return null
}

internal fun extractImdbIdFromServerUrl(url: String): String? =
    Regex("""tt\d{7,8}""").find(url)?.value

internal fun fixServerPlaceholder(url: String, tmdbId: String?): String {
    if (tmdbId.isNullOrBlank() || !url.contains("{tmdbId}")) return url
    return url.replace("{tmdbId}", tmdbId)
}

class MultimoviesProvider : MainAPI() {

    override var mainUrl = MultimoviesDomainResolver.SEED_DOMAIN
    override var name = "Multimovies"
    override var lang = "hi"

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    private val commonHeaders = mapOf("User-Agent" to userAgent)
    private var cfKiller: CloudflareKiller? = null
    private fun getCfKiller(): CloudflareKiller {
        return cfKiller ?: CloudflareKiller().also { cfKiller = it }
    }
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imdbUrlCache = ConcurrentHashMap<String, String>()
    private val mmDocCache = ConcurrentHashMap<String, Document>()
    private val tmdbSearchCache = ConcurrentHashMap<String, Pair<String, String?>>()
    @Volatile private var dooplayNonce: String? = null
    @Volatile private var dooplayNonceAt = 0L
    private val nonceMutex = Mutex()

    private val liveFarms = ConcurrentHashMap<String, Deferred<Unit>>()

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

    override val mainPage
        get() = mainPageOf(
        Pair("$mainUrl/genre/bollywood-movies", "Bollywood Movies"),
        Pair("$mainUrl/genre/netflix", "Netflix"),
        Pair("$mainUrl/genre/amazon-prime", "Amazon Prime"),
        Pair("$mainUrl/genre/disney-hotstar", "Disney+ Hotstar"),
        Pair("$mainUrl/genre/zee-5", "Zee5"),
        Pair("$mainUrl/genre/hollywood", "Hollywood"),
        Pair("$mainUrl/genre/action", "Action"),
        Pair("$mainUrl/genre/comedy", "Comedy"),
        Pair("$mainUrl/genre/horror", "Horror"),
        Pair("$mainUrl/genre/science-fiction", "Sci-Fi"),
        Pair("$mainUrl/series", "Web Series"),
        Pair("$mainUrl/genre/k-drama", "K-Drama"),
        Pair("$mainUrl/genre/crime", "Crime Series"),
        Pair("$mainUrl/genre/thriller", "Thriller Series"),
        Pair("$mainUrl/genre/south-indian", "South Indian"),
        Pair("$mainUrl/genre/anime-hindi", "Hindi Dub Anime"),
        Pair("$mainUrl/genre/anime-series", "Anime Series"),
        Pair("$mainUrl/genre/anime-movies", "Anime Movies"),
    )

    companion object {
        const val SOURCE_TIMEOUT_MS = 15_000L

        const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1000L

        const val SEARCH_MAX_RESULTS = 6

        const val SEARCH_RELEVANCE_THRESHOLD = 0.5

        const val SEARCH_TOTAL_BUDGET_MS = 2500L

        const val SITE_SEARCH_BUDGET_MS = 8000L

        const val DOOPLAY_NONCE_TTL_MS = 12 * 60 * 60 * 1000L

        const val LIVE_FILL_MS = 90_000L

        const val FAST_START_MAX_MS = 45_000L

        fun passesQualityFloor(isAdaptive: Boolean, height: Int): Boolean =
            isAdaptive || height <= 0 || height >= 720

        private const val MM_DOC_CACHE_MAX_SIZE = 24
    }

    private fun priorityOf(serverName: String): Int {
        val idx = SOURCE_PRIORITY.indexOfFirst { serverName.contains(it, ignoreCase = true) }
        return if (idx == -1) SOURCE_PRIORITY.size else idx
    }

    private fun isMultimoviesUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return MULTIMOVIES_HOST_REGEX.containsMatchIn(url)
    }

    private fun liveUrl(url: String): String {
        if (url.startsWith("/")) return mainUrl.trimEnd('/') + url
        val m = URL_HOST_REGEX.find(url) ?: return url
        if (!isMultimoviesUrl(m.groupValues[1])) return url
        val liveHost = URL_HOST_REGEX.find(mainUrl)?.value ?: return url
        return liveHost + url.substring(m.range.last + 1)
    }

    private fun isV2DetailUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !isMultimoviesUrl(url)) return false
        return url.contains("/movie/") || url.contains("/series/") || url.contains("/watch/")
    }

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

    internal suspend fun fetchDoc(
        url: String,
        timeoutSeconds: Long = 12,
        required: Boolean = false,
        headers: Map<String, String> = commonHeaders,
    ): Document? {
        val challengeTimeoutS = 15L
        val fetchUrl = liveUrl(url)
        try {
            val doc = app.get(fetchUrl, timeout = timeoutSeconds, headers = headers).document
            if (!isChallenge(doc)) return doc
        } catch (e: Exception) {
        }

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

    private fun solverFactories(): List<() -> CloudflareKiller> = listOf(
        { getCfKiller() },
        { CloudflareKiller().also { cfKiller = it } },
    )

    internal suspend fun solveDocument(
        url: String,
        timeoutSeconds: Long = 15,
    ): Document = fetchDoc(url, timeoutSeconds = timeoutSeconds, required = true)
        ?: throw ErrorLoadingException("Failed to fetch $url")

    internal suspend fun cachedDocOrFetch(url: String): Document? {
        mmDocCache[url]?.let { return it }
        val doc = runCatching { solveDocument(url) }.getOrNull() ?: return null
        if (mmDocCache.size >= MM_DOC_CACHE_MAX_SIZE) {
            mmDocCache.keys.firstOrNull()?.let { mmDocCache.remove(it) }
        }
        mmDocCache[url] = doc
        return doc
    }


    override suspend fun search(query: String): List<SearchResponse>? = withDomainRetry(retryIf = { it == null }) {
        SearchCache.get(query)?.let { return@withDomainRetry it }

        val site = withTimeoutOrNull(SITE_SEARCH_BUDGET_MS) { siteSearchDooplay(query) }.orEmpty()
        if (site.isNotEmpty()) {
            return@withDomainRetry site.also { SearchCache.put(query, it) }
        }

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
                android.util.Log.w("Multimovies", "search NULL q='$query': ${SEARCH_TOTAL_BUDGET_MS}ms budget expired or no relevant hits (check tmdb search NET-FAIL/UPSTREAM-ERR lines above)")
                return@withDomainRetry null
            }

        ranked.forEach { (_, item) ->
            item.tmdbId?.let { tmdbSearchCache["$it|${item.type}"] = item.name to item.year }
        }

        val responses = ranked.mapNotNull { (_, item) -> item.toSearchResponse() }

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

    private suspend fun siteSearchDooplay(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val pageDoc = fetchDoc("$mainUrl/search?q=$q", timeoutSeconds = 8, required = false)
        val pageHits = pageDoc?.select("article.poster-card")?.mapNotNull { el ->
            val a = el.selectFirst("a.poster-art[href], div.poster-caption a[href]") ?: return@mapNotNull null
            val href = liveUrl(a.attr("href").trim())
            if (!isMultimoviesUrl(href)) return@mapNotNull null
            val title = el.selectFirst("div.poster-caption a")?.text()?.trim()
                ?: el.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val poster = sanitizeV2Poster(
                el.selectFirst("a.poster-art img")?.attr("src")
                    ?: el.selectFirst("img")?.attr("src")
            )
            val meta = el.selectFirst("p.poster-meta")?.text().orEmpty()
            val year = Regex("""\d{4}""").find(meta)?.value
            val rating = el.selectFirst("span.poster-rating")?.text()
                ?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()?.takeIf { it > 0 }
            val type = if (href.contains("/series/") || meta.contains("Series", true)) "series" else "movie"
            V2Hit(title, href, poster, year, rating, type)
        }.orEmpty()
        val apiText = runCatching {
            app.get("$mainUrl/api/search?q=$q&limit=12", timeout = 6, headers = commonHeaders).text
        }.getOrNull()
        val apiHits = parseV2SearchApi(apiText).map {
            it.copy(url = liveUrl(it.url))
        }.filter { isMultimoviesUrl(it.url) }
        val merged = (pageHits + apiHits).distinctBy { liveUrl(it.url).trimEnd('/') }.take(SEARCH_MAX_RESULTS * 2)
        if (merged.isEmpty()) return emptyList()
        val ranked = merged.mapNotNull { hit ->
            val rel = relevanceOf(query, hit.title, hit.year)
            if (!rel.allTokensMatched || rel.score < SEARCH_RELEVANCE_THRESHOLD) null
            else rel.score to hit
        }.sortedByDescending { it.first }.take(SEARCH_MAX_RESULTS)
        if (ranked.isEmpty()) return emptyList()
        val responses = ranked.mapNotNull { (_, hit) -> hit.toSearchResponse() }
        responses.forEach { r ->
            searchScope.launch { runCatching { cachedDocOrFetch(r.url) } }
        }
        backfillPosters(responses)
        return responses
    }

    private suspend fun dooplaySearchJson(query: String, nonce: String): String? {
        val url = "$mainUrl/wp-json/dooplay/search/?keyword=${URLEncoder.encode(query.trim(), "UTF-8")}&nonce=$nonce"
        return runCatching {
            app.get(url, timeout = 6, headers = commonHeaders + mapOf("Referer" to "$mainUrl/")).text
        }.getOrNull()
    }

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

    private fun V2Hit.toSearchResponse(): SearchResponse? {
        if (title.isBlank() || url.isBlank()) return null
        val pageUrl = liveUrl(url)
        if (!isMultimoviesUrl(pageUrl)) return null
        val releaseYear = year?.take(4)?.toIntOrNull()
        val tvType = if (type == "series" || pageUrl.contains("/series/")) TvType.TvSeries else TvType.Movie
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
        runtime = runtime,
    )

    private fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
        return m.groupValues[2].toIntOrNull()?.let { id ->
            id to if (m.groupValues[1] == "movie") "movie" else "series"
        }
    }

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

        val searchTerms = titleVariants(title).take(3)
        for (term in searchTerms) {
            val q = URLEncoder.encode(term, "UTF-8")
            val searchDoc = fetchDoc("$mainUrl/search?q=$q", timeoutSeconds = 8, required = false)
                ?: continue
            val candidate = searchDoc.select("article.poster-card").mapNotNull { it.candidateHref() }
                .minByOrNull { titleDistance(it.second, title) }
                ?.takeIf { titleDistance(it.second, title) <= 1 }
                ?: continue
            val detailUrl = liveUrl(candidate.first)
            val detailDoc = mmDocCache[detailUrl]
                ?: fetchDoc(detailUrl, timeoutSeconds = 8, required = false)
                ?: continue
            imdbUrlCache[key] = detailUrl
            mmDocCache[detailUrl] = detailDoc
            return detailDoc
        }
        val legacyBase = if (type == "movie") "$mainUrl/movies/" else "$mainUrl/tvshows/"
        val slug = title.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isNotBlank()) {
            val guessUrl = "$legacyBase$slug/"
            val guessed = fetchDoc(guessUrl, timeoutSeconds = 5, required = false)
            val guessedTitle = guessed?.selectFirst(TITLE_SELECTOR)?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()?.let(::stripSiteAffix)
            if (guessedTitle != null && titleDistance(guessedTitle, title) <= 1 && guessed != null) {
                imdbUrlCache[key] = guessUrl
                mmDocCache[guessUrl] = guessed
                return guessed
            }
        }
        return null
    }

    private fun Element.candidateHref(): Pair<String, String>? {
        val a = selectFirst("a.poster-art[href], div.poster-caption a[href], a[href], div.data a h2, div.poster a")
            ?: return null
        val rawHref = a.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val href = liveUrl(rawHref)
        if (!isMultimoviesUrl(href)) return null
        val itemTitle = selectFirst("div.poster-caption a")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()?.trim()
            ?: a.text()?.trim()
        return if (itemTitle.isNullOrBlank()) null else href to itemTitle
    }

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
        val a = selectFirst("a.poster-art[href], div.poster-caption a[href], a[href], div.data a h2, div.poster a")
            ?: return null
        val rawHref = a.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val href = liveUrl(rawHref)
        if (!isMultimoviesUrl(href)) return null
        val title = selectFirst("div.poster-caption a")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()?.trim()
            ?: a.text()?.trim()
            ?: return null
        if (title.isBlank()) return null
        val poster = sanitizeV2Poster(
            selectFirst("a.poster-art img")?.attr("src") ?: posterUrl()
        )
        val meta = selectFirst("p.poster-meta")?.text().orEmpty()
        val isMovie = href.contains("/movie/") || href.contains("/movies/") || meta.contains("Movie", true)
        val isSeries = href.contains("/series/") || href.contains("/tvshows/") || href.contains("/seasons/")
            || meta.contains("Series", true)
        val tvType = when {
            isMovie && !isSeries -> TvType.Movie
            isSeries -> TvType.TvSeries
            href.contains("/movie/") -> TvType.Movie
            else -> TvType.TvSeries
        }
        val year = Regex("""\d{4}""").find(meta)?.value?.toIntOrNull()
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.year = year
                parseRating(this@toSearchResponse)?.let { Score.from10(it.toString()) }?.let { this.score = it }
                    ?: selectFirst("span.poster-rating")?.text()?.replace(Regex("[^\\d.]"), "")
                        ?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }?.let { this.score = it }
            }
        } else {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                this.year = year
                parseRating(this@toSearchResponse)?.let { Score.from10(it.toString()) }?.let { this.score = it }
                    ?: selectFirst("span.poster-rating")?.text()?.replace(Regex("[^\\d.]"), "")
                        ?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }?.let { this.score = it }
            }
        }
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = withDomainRetry(retryIf = { it == null }) {
        val base = request.data.trimEnd('/')
        val url = when {
            page <= 1 -> base
            base.contains("?") -> "$base&page=$page"
            else -> "$base?page=$page"
        }
        val doc = fetchDoc(url, timeoutSeconds = 12, required = false) ?: return@withDomainRetry null
        val items = doc.select("article.poster-card, article.cinema-slide a.button-glass, div.catalog-grid article, a.cinejoy-rec-card").mapNotNull { el ->
            if (el.tagName() == "a" && el.hasClass("button-glass")) {
                val href = liveUrl(el.attr("href").trim())
                if (!isMultimoviesUrl(href)) return@mapNotNull null
                val slide = el.closest("article.cinema-slide")
                val title = slide?.selectFirst("h1")?.text()?.trim() ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val poster = sanitizeV2Poster(slide.selectFirst("img.cinema-backdrop")?.attr("src"))
                val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
                if (tvType == TvType.Movie) newMovieSearchResponse(title, href, tvType) { this.posterUrl = poster }
                else newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = poster }
            } else {
                el.toSearchResponse()
            }
        }.distinctBy { it.url.trimEnd('/') }
        if (items.isEmpty()) return@withDomainRetry null
        backfillPosters(items)
        newHomePageResponse(request.name, items)
    }


    override suspend fun load(url: String): LoadResponse? =
        withDomainRetry(retryIf = { it == null }) { loadInternal(url) }

    private suspend fun loadInternal(url: String): LoadResponse? {
        val tmdb = parseTmdbUrl(url)
        val cached = tmdb?.let { (id, type) -> tmdbSearchCache["$id|$type"] }

        return coroutineScope {
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

            if (doc == null && tmdb != null && detail?.name != null) {
                doc = resolveMultimoviesDoc(tmdb.first, null, tmdb.second, detail.name, detail.year)
            }
            doc ?: throw ErrorLoadingException("Could not match $url to a Multimovies page")
            val realUrl = doc.location()?.takeIf { it.isNotBlank() }
                ?: (if (tmdb != null) imdbUrlCache["${tmdb.first}|${tmdb.second}"] else null)
                ?: url

            val isMovie = tmdb?.second == "movie" || realUrl.contains("/movie/")
                || realUrl.contains("/watch/movie")
                || (!realUrl.contains("/series/") && !realUrl.contains("/watch/tv") && realUrl.contains("/movies/"))
            val pageType = if (isMovie) "movie" else "series"

            val v2Servers = parseV2Servers(doc)
            if (v2Servers.isNotEmpty()) {
                val embeds = v2Servers.map { s ->
                    ResolvedEmbed(s.name, s.url, embedUrl = s.url, key = s.name + "|" + s.url)
                }
                EmbedPrefetchCache.put(realUrl, embeds)
            }
            val earlyMeta = earlyV2Meta(doc, v2Servers, realUrl)
            if (isMovie) prefetchGlobals(earlyMeta ?: SourceMeta("", null, null, null), realUrl)

            var resolvedDetail = detail
            if (tmdb == null && resolvedDetail == null) {
                val serverTmdb = v2Servers.firstNotNullOfOrNull { extractTmdbIdFromServerUrl(it.url) }?.toIntOrNull()
                val serverImdb = v2Servers.firstNotNullOfOrNull { extractImdbIdFromServerUrl(it.url) }
                val imdbFromPage = TmdbService.extractImdbId(doc) ?: serverImdb
                val tmdbFromPage = TmdbService.extractTmdbId(doc)?.toIntOrNull() ?: serverTmdb
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
                    val fallbackTitle = doc.selectFirst(TITLE_SELECTOR)?.let {
                        if (it.tagName() == "meta") it.attr("content") else it.text()
                    }?.trim()?.let(::stripSiteAffix)
                    if (!fallbackTitle.isNullOrBlank()) {
                        resolvedDetail = resolveImdbDetail(fallbackTitle, pageType)
                    }
                }
            }

            val finalTmdbId = resolvedDetail?.tmdbId ?: tmdb?.first
                ?: v2Servers.firstNotNullOfOrNull { extractTmdbIdFromServerUrl(it.url) }?.toIntOrNull()
            val finalImdbId = resolvedDetail?.imdbId
                ?: (if (tmdb == null) TmdbService.extractImdbId(doc) else null)
                ?: v2Servers.firstNotNullOfOrNull { extractImdbIdFromServerUrl(it.url) }
            val tmdbId = finalTmdbId
            val imdbId = finalImdbId

            if (tmdbId == null && imdbId != null && !resolvedDetail?.cast.isNullOrEmpty()) {
                val tmdbCast = withTimeoutOrNull(6000L) {
                    TmdbService.findByImdb(imdbId)?.let { (id, t) -> TmdbService.fetchMeta(id, t)?.cast }
                }.orEmpty()
                mergeImdbCast(resolvedDetail?.cast, tmdbCast)?.let { merged ->
                    resolvedDetail = resolvedDetail?.copy(cast = merged)
                }
            }

            val pageTitle = doc.selectFirst(TITLE_SELECTOR)?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()?.let { stripSiteAffix(it.replace(Regex("""\s*-\s*MultiMovies.*$""", RegexOption.IGNORE_CASE), "")) }
            val title = resolvedDetail?.name ?: pageTitle
                ?: throw ErrorLoadingException("No title found on $realUrl")
            val poster = resolvedDetail?.poster ?: sanitizeV2Poster(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("div.watch-bg img")?.attr("src")
                    ?: doc.selectFirst("div.poster img, img.wp-post-image")?.attr("src")
            )
            val backdrop = resolvedDetail?.backdrop ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.cinema-backdrop")?.attr("src")
            val year = resolvedDetail?.year?.toIntOrNull()
                ?: doc.selectFirst(".cinejoy-meta-pill")?.parent()?.text()
                    ?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
                ?: doc.selectFirst("span.date, .year, .extra span")?.text()
                    ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
            val plot = resolvedDetail?.overview
                ?: doc.selectFirst("p.cinejoy-detail-overview")?.text()?.trim()
                ?: doc.selectFirst("div.wp-content, div.description, .wp-content p")?.text()
                    ?.replace("Overview:", "")?.trim()
            val tags = resolvedDetail?.genres?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("div.cinejoy-detail-genres")?.text()
                    ?.split("•", ",", "|")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: doc.select("div.sgeneros a, .genre a").map { it.text().trim() }.filter { it.isNotBlank() }
            val pageCast = parseV2Cast(doc)
            val cast = resolvedDetail?.cast?.takeIf { it.isNotEmpty() } ?: pageCast
            if (resolvedDetail != null && pageCast != null) {
                resolvedDetail = resolvedDetail?.copy(cast = cast)
            }
            val score = resolvedDetail?.rating
            val pageScore = doc.selectFirst(".cinejoy-score-pill")?.text()?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()
                ?: doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
                    ?.removePrefix("IMDb:")?.trim()?.toDoubleOrNull()

            if (imdbId != null || tmdbId != null) {
                SourceMetaCache.put(realUrl, SourceMeta(imdbId ?: "", tmdbId?.toString(), null, null))
            }

            if (isMovie) prefetchGlobals(
                SourceMeta(imdbId ?: "", tmdbId?.toString(), null, null), realUrl,
            )

            if (isMovie) {
                newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = cast
                    this.duration = resolvedDetail?.runtime?.let { it * 60 }
                        ?: parseRuntimeToMinutes(doc)?.let { it * 60 }
                    imdbId?.let { addImdbId(it) }
                    (score ?: pageScore)?.let { addScore(it.toString(), 10) }
                }
            } else {
                val episodes = arrayListOf<Episode>()
                val seasonNums = mutableSetOf<Int>()
                val epCards = doc.select("div.cinejoy-ep-card")
                if (epCards.isNotEmpty()) {
                    epCards.forEachIndexed { i, card ->
                        val link = card.selectFirst("a.cinejoy-ep-thumb-link[href], a.cinejoy-ep-name[href]")
                            ?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                        val epUrl = liveUrl(link)
                        if (!isMultimoviesUrl(epUrl)) return@forEachIndexed
                        val seasonNum = Regex("""/season/(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val epNum = Regex("""/episode/(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                            ?: card.selectFirst("span.cinejoy-ep-badge-num")?.text()
                                ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                            ?: (i + 1)
                        val epTitle = card.selectFirst("a.cinejoy-ep-name")?.text()?.trim()
                            ?.takeIf { it.isNotBlank() }
                        val epDesc = card.selectFirst("p.cinejoy-ep-desc")?.text()?.trim()
                        val epThumb = card.selectFirst("img.cinejoy-ep-img")?.attr("src")
                            ?.takeIf { it.isNotBlank() && !it.contains("default-col") }
                        val ep = newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                            this.description = epDesc
                            this.posterUrl = epThumb
                        }
                        episodes.add(ep)
                        seasonNums.add(seasonNum)
                        if (imdbId != null || tmdbId != null) {
                            SourceMetaCache.put(epUrl, SourceMeta(imdbId ?: "", tmdbId?.toString(), seasonNum, epNum))
                        }
                        searchScope.launch {
                            runCatching {
                                val epDoc = cachedDocOrFetch(epUrl)
                                if (epDoc != null) {
                                    val servers = parseV2Servers(epDoc)
                                    if (servers.isNotEmpty()) {
                                        EmbedPrefetchCache.put(epUrl, servers.map { s ->
                                            ResolvedEmbed(s.name, s.url, embedUrl = s.url, key = s.name + "|" + s.url)
                                        })
                                    }
                                }
                            }
                        }
                    }
                } else {
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
                    seasonDocs.forEachIndexed { pageIdx, sDoc ->
                        if (sDoc == null) return@forEachIndexed
                        val pageSeason = pageIdx + 1
                        sDoc.select("ul.episodios li, div.eps div.ep, .episodios li").forEachIndexed { i, ep ->
                            val epLink = ep.selectFirst("a[href]")?.attr("href")?.takeIf { isMultimoviesUrl(it) }
                                ?: return@forEachIndexed
                            val nxm = Regex("(?i)(\\d+)x(\\d+)").find(epLink)
                            val epNum = nxm?.groupValues?.getOrNull(2)?.toIntOrNull()
                                ?: Regex("(\\d+)").find(epLink)?.value?.toIntOrNull()?.takeIf { it in 1..150 }
                                ?: (i + 1)
                            val seasonNum = nxm?.groupValues?.getOrNull(1)?.toIntOrNull() ?: pageSeason
                            val epTitle = ep.selectFirst(".episodiotitle a, .title, a")?.text()?.trim()
                            val item = newEpisode(epLink) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = seasonNum
                            }
                            episodes.add(item)
                            seasonNums.add(seasonNum)
                            if (imdbId != null || tmdbId != null) {
                                SourceMetaCache.put(epLink, SourceMeta(imdbId ?: "", tmdbId?.toString(), seasonNum, epNum))
                            }
                        }
                    }
                }

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
                                m.runtime?.let { ep.runTime = it * 60 }
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
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = cast
                    this.duration = resolvedDetail?.runtime?.let { it * 60 }
                        ?: parseRuntimeToMinutes(doc)?.let { it * 60 }
                    imdbId?.let { addImdbId(it) }
                    (score ?: pageScore)?.let { addScore(it.toString(), 10) }
                }
            }
        }
    }

    private fun parseV2Servers(doc: Document): List<V2Server> {
        val fromConfig = parseV2WatchConfig(doc.html())
        val chips = doc.select("button.server-chip[data-url]").mapNotNull { b ->
            val raw = b.attr("data-url").trim().ifEmpty { return@mapNotNull null }
            val url = raw.replace("&amp;", "&")
            if (!url.startsWith("http")) return@mapNotNull null
            val name = b.selectFirst(".server-name")?.text()?.trim()
                ?: b.attr("data-id").trim().ifEmpty { "Server" }
            V2Server(name, url)
        }
        val tmdb = (fromConfig + chips).firstNotNullOfOrNull { extractTmdbIdFromServerUrl(it.url) }
        return (fromConfig + chips).distinctBy { it.url }.map { s ->
            s.copy(url = fixServerPlaceholder(s.url, tmdb ?: extractTmdbIdFromServerUrl(s.url)))
        }
    }

    private fun earlyV2Meta(doc: Document, servers: List<V2Server>, pageUrl: String): SourceMeta? {
        val tmdb = servers.firstNotNullOfOrNull { extractTmdbIdFromServerUrl(it.url) }
        val imdb = servers.firstNotNullOfOrNull { extractImdbIdFromServerUrl(it.url) }
            ?: TmdbService.extractImdbId(doc)
        if (tmdb == null && imdb == null) return null
        val season = Regex("""/season/(\d+)""").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: parseSeason(pageUrl)
        val episode = Regex("""/episode/(\d+)""").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: parseEpisode(pageUrl)
        return SourceMeta(imdb ?: "", tmdb, season, episode)
    }

    private fun parseV2Cast(doc: Document): List<com.lagradost.cloudstream3.ActorData>? {
        val cards = doc.select("a.cinejoy-cast-card")
        if (cards.isEmpty()) return null
        return cards.mapNotNull { c ->
            val name = c.selectFirst(".cinejoy-cast-name")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val role = c.selectFirst(".cinejoy-cast-role")?.text()?.trim()
            val img = c.selectFirst("img.cinejoy-cast-img")?.attr("src")?.takeIf { it.startsWith("http") }
            com.lagradost.cloudstream3.ActorData(
                com.lagradost.cloudstream3.Actor(name, img ?: ""),
                roleString = role,
            )
        }.take(15).ifEmpty { null }
    }

    internal fun parseRuntimeToMinutes(doc: Document): Int? {
        val texts = listOf(
            doc.selectFirst(".cinejoy-spec-box")?.text().orEmpty(),
            doc.selectFirst(".cinejoy-detail-meta")?.text().orEmpty(),
            doc.selectFirst("script[type=application/ld+json]")?.html().orEmpty(),
        ).joinToString(" ")
        Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""").find(texts)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h > 0 || min > 0) return h * 60 + min
        }
        Regex("""(\d+)\s*h\s*(\d+)\s*m""", RegexOption.IGNORE_CASE).find(texts)?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
        }
        return null
    }

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

    private fun prefetchEmbeds(pageUrl: String, doc: Document) {
        searchScope.launch {
            runCatching {
                EmbedPrefetchCache.resolveOrJoin(pageUrl, resolve = { resolveAllEmbeds(pageUrl, doc) })
            }
        }
    }

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
                        firstLink = null,
                        cacheKey = pageUrl,
                        cacheOnly = true,
                    )
                }
            }
        }
    }

    private suspend fun resolveAllEmbeds(pageUrl: String, doc: Document): List<ResolvedEmbed> {
        val v2 = parseV2Servers(doc)
        if (v2.isNotEmpty()) {
            return v2.map { s ->
                val resolved = ResolvedEmbed(
                    s.name, s.url, embedUrl = s.url,
                    unwrapped = false, key = s.name + "|" + s.url,
                )
                EmbedPrefetchCache.publish(pageUrl, resolved)
                resolved
            }
        }
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


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withDomainRetry(retryIf = { !it }) {
        var meta = SourceMetaCache.get(data)

        if (meta != null) {
            LinkCache.get(meta.imdbId, meta.season, meta.episode)?.let { cached ->
                if (cached.isNotEmpty()) {
                    cached.forEach { runCatching { callback(it) } }
                    deliverFallbackSubs(meta, subtitleCallback)
                    return@withDomainRetry true
                }
            }
        }

        FastStartCache.get(data)?.let { cached ->
            if (cached.isNotEmpty()) {
                cached.forEach { runCatching { callback(it) } }
                val farm = liveFarms[data]
                if (farm != null && farm.isActive) {
                    val pushed = HashSet<String>().apply { cached.forEach { add(it.url) } }
                    val extra = tailLiveFarm(data, farm, pushed, callback)
                    android.util.Log.i("Multimovies", "loadLinks: replay ${cached.size} + live tail " +
                        "$extra links (first-tap farm still resolving)")
                }
                if (meta != null) {
                    deliverFallbackSubs(meta, subtitleCallback)
                }
                return@withDomainRetry true
            }
        }

        liveFarms[data]?.takeIf { it.isActive }?.let { farm ->
            val pushed = HashSet<String>()
            val extra = tailLiveFarm(data, farm, pushed, callback)
            if (extra > 0) {
                android.util.Log.i("Multimovies", "loadLinks: joined in-flight farm -> $extra live links")
                if (meta != null) deliverFallbackSubs(meta, subtitleCallback)
                return@withDomainRetry true
            }
        }

        val awaited = EmbedPrefetchCache.awaitInFlight(data, timeoutMs = 1200L)
        var embeds: List<ResolvedEmbed> =
            (awaited.orEmpty() + EmbedPrefetchCache.arrived(data)).distinctBy { it.embedIdentity() }

        if (awaited == null) {
            val doc = cachedDocOrFetch(data)
            if (doc != null) {
                val have = embeds.mapTo(HashSet()) { it.key }
                val v2 = parseV2Servers(doc)
                    .map { s -> ResolvedEmbed(s.name, s.url, embedUrl = s.url, key = s.name + "|" + s.url) }
                    .filterNot { have.contains(it.key) }
                if (v2.isNotEmpty()) {
                    embeds += v2
                } else {
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
                if (meta == null) {
                    val tmdb = v2.firstNotNullOfOrNull { extractTmdbIdFromServerUrl(it.url) }
                    val imdb = v2.firstNotNullOfOrNull { extractImdbIdFromServerUrl(it.url) }
                        ?: TmdbService.extractImdbId(doc)
                    if (tmdb != null || imdb != null) {
                        meta = SourceMeta(
                            imdb ?: "", tmdb,
                            Regex("""/season/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: parseSeason(data),
                            Regex("""/episode/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: parseEpisode(data),
                        )
                        SourceMetaCache.put(data, meta)
                    }
                }
            }
        }

        if (meta == null) {
            embeds.firstNotNullOfOrNull { extractImdbIdFromUrl(it.embedUrl ?: it.url) }?.let { id ->
                meta = SourceMeta(id, null, parseSeason(data), parseEpisode(data))
                SourceMetaCache.put(data, meta)
            }
        }

        val tmdbForFix = meta?.tmdbId
        if (tmdbForFix != null) {
            embeds = embeds.map { e ->
                if (e.url.contains("{tmdbId}")) e.copy(url = fixServerPlaceholder(e.url, tmdbForFix)) else e
            }
        }
        val emitted = Collections.synchronizedSet(HashSet<String>())
        val emittedUrls = Collections.synchronizedSet(HashSet<String>())
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val firstLink = CompletableDeferred<Unit>()
        val labelCounter = ConcurrentHashMap<String, Int>()
        val noopSubtitle: (SubtitleFile) -> Unit = { }

        val globalSources = buildGlobalSources(meta)
        val remainingPulls = java.util.concurrent.atomic.AtomicInteger(globalSources.size + embeds.size)
        val allPullsDone = CompletableDeferred<Unit>()
        if (globalSources.isEmpty() && embeds.isEmpty()) allPullsDone.complete(Unit)

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

        embeds.forEach { e ->
            searchScope.launch {
                try {
                    val finalUrl = if (e.unwrapped) e.url
                    else MultiSourcePuller.unwrapEmbed(e.url, referer = data, headers = commonHeaders)
                    val srcHeaders = commonHeaders + MultiSourcePuller.headersFor(finalUrl, referer = data)
                    val src = MultiSourcePuller.Source(
                        name = e.name,
                        url = finalUrl,
                        referer = data,
                        headers = srcHeaders,
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

        val metaSubs = meta
        val subsJob = metaSubs?.let { m ->
            searchScope.async {
                deliverFallbackSubs(m, subtitleCallback)
            }
        }

        val loadStartMs = System.currentTimeMillis()
        val nothingToPull = embeds.isEmpty() && globalSources.isEmpty()
        val firstStream = !nothingToPull &&
            withTimeoutOrNull(FAST_START_MAX_MS) { firstLink.await() } != null
        if (!firstStream) {
            if (awaited != null && awaited.isNotEmpty()) EmbedPrefetchCache.invalidate(data)
            val bg = FastStartCache.get(data)
            if (bg != null && bg.isNotEmpty()) {
                bg.forEach { runCatching { callback(it) } }
                meta?.let { deliverFallbackSubs(it, subtitleCallback) }
                return@withDomainRetry true
            }
            return@withDomainRetry false
        }

        withTimeoutOrNull(LIVE_FILL_MS) { allPullsDone.await() }

        subsJob?.await()

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

    private suspend fun deliverFallbackSubs(
        meta: SourceMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        runCatching {
            coroutineScope {
                val type = if (meta.season != null || meta.episode != null) "tv" else "movie"
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
                val origLang = async {
                    detail?.originalLanguage ?: meta.tmdbId?.toIntOrNull()?.let { id ->
                        withTimeoutOrNull(4000L) { TmdbService.fetchMeta(id, type) }?.originalLanguage
                    }
                }
                val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                val emitLock = Mutex()
                suspend fun emit(t: SubtitleFile) {
                    if (!seen.add(t.url)) return
                    emitLock.withLock { runCatching { subtitleCallback(t) } }
                }
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

    private suspend fun pullSource(
        src: MultiSourcePuller.Source,
        labelCounter: ConcurrentHashMap<String, Int>,
        data: String,
        emitted: MutableSet<String>,
        emittedUrls: MutableSet<String>,
        found: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        firstLink: CompletableDeferred<Unit>? = null,
        cacheKey: String? = null,
        cacheOnly: Boolean = false,
    ): List<ExtractorLink> {
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

        fun passesFloor(l: ExtractorLink): Boolean =
            MultimoviesProvider.passesQualityFloor(
                l.type == ExtractorLinkType.M3U8 || l.type == ExtractorLinkType.DASH,
                l.quality,
            )

        suspend fun emitOne(l: ExtractorLink, fast: Boolean = false) {
            if (!passesFloor(l)) return
            if (!emittedUrls.add(l.url ?: return)) return
            val dis = disambiguate(MultiSourcePuller.enrichLabel(
                l,
                probeBudgetMs = if (fast) MultiSourcePuller.FAST_PROBE_BUDGET_MS
                else MultiSourcePuller.LABEL_PROBE_BUDGET_MS,
            ))
            val key = "${hostOf(dis.url ?: "")}|${dis.quality}|${MultiSourcePuller.bracketTag(dis)}"
            if (!emitted.add(key)) return
            found.add(dis)
            if (cacheKey != null) FastStartCache.put(cacheKey, listOf(dis))
            firstLink?.complete(Unit)
            if (cacheOnly) return
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
        Regex("""/season/(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)(\\d+)x\\d+").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseEpisode(url: String): Int? =
        Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)\\d+x(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun buildGlobalSources(meta: SourceMeta?): List<MultiSourcePuller.Source> {
        if (meta == null) return emptyList()
        return GlobalSources.list.mapNotNull { g ->
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

    private fun dedupeByHostQuality(links: List<ExtractorLink>): List<ExtractorLink> {
        val seen = HashSet<String>()
        return links.filter { l -> seen.add("${hostOf(l.url ?: "")}|${l.quality}|${MultiSourcePuller.bracketTag(l)}") }
    }
}

internal object MultimoviesDomainResolver {

    internal const val LANDING_URL = "https://multimovies.wtf/"

    internal const val SEED_DOMAIN = "https://m1.multimovies.courses"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val GATEWAY_DEBOUNCE_MS = 60_000L
    private const val FETCH_TIMEOUT_S = 8L

    private val LIVE_DOMAIN_REGEX =
        Regex("""^https://(?:[a-z0-9-]+\.)?multimovies\.[a-z]{2,10}$""")

    @Volatile
    private var cached: String? = null

    @Volatile
    private var resolvedAt = 0L

    @Volatile
    private var lastFetchAt = 0L

    private val mutex = Mutex()

    internal fun normalize(href: String): String? {
        val h = href.trim().trimEnd('/')
        if (!LIVE_DOMAIN_REGEX.matches(h)) return null
        val normalized = h.replace("https://www.", "https://")
        if (normalized == LANDING_URL.trimEnd('/')) return null
        return normalized
    }

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

    internal fun currentDomain(): String = cached ?: SEED_DOMAIN

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

    private const val MAX_UNWRAP_LEVELS = 4

    internal val STREAM_URL_REGEXES = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mkv[^\s"'<>\\]*"""),
    )

    internal fun extractStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\/", "/").replace("\\\"", "\"")
        for (r in STREAM_URL_REGEXES) {
            r.findAll(normalized).firstOrNull()?.groupValues?.get(0)?.let { raw ->
                val cleaned = raw.trim('"', '\'')
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return null
    }

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

    internal fun extractVideoSourceUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")
            ?.attr("src")?.trim() ?: return null
        if (src.isBlank()) return null
        return resolveRelative(baseUrl, src).takeIf { it.startsWith("http") }
    }

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

    internal fun isCineverseHost(url: String): Boolean =
        cineverseCdnHosts.contains(hostOf(url)) ||
            hostOf(url).let { h -> cineverseCdnHosts.any { h == it || h.endsWith(".$it") } } ||
            url.contains("serve_m3u8", ignoreCase = true)

    data class AudioRendition(val language: String?, val name: String, val isDefault: Boolean)

    data class MasterFacts(val bestHeight: Int, val audio: List<AudioRendition>)

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

    internal fun bracketTag(l: ExtractorLink): String =
        Regex("""\(([A-Za-z]+)\)\s*$""").find(l.source ?: l.name ?: "")?.groupValues?.get(1).orEmpty()

    internal fun declaredHindi(sourceName: String?, url: String?): Boolean {
        val hay = buildString {
            sourceName?.let { append(it.lowercase()); append('|') }
            url?.let { append(it.lowercase()) }
        }
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    internal fun resolutionFromUrl(url: String?): Int {
        if (url.isNullOrBlank()) return 0
        return Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE).findAll(url)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
    }

    internal const val LABEL_PROBE_BUDGET_MS = 2500L
    internal const val FAST_PROBE_BUDGET_MS = 1000L

    private val labelProbeCache = java.util.concurrent.ConcurrentHashMap<String, MasterFacts?>()

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
        if (facts != null) labelProbeCache[url] = facts
        return facts
    }

    internal suspend fun enrichLabel(l: ExtractorLink, probeBudgetMs: Long = LABEL_PROBE_BUDGET_MS): ExtractorLink {
        val facts = probeLabelFacts(l, probeBudgetMs)
        val height = facts?.bestHeight?.takeIf { it > 0 }
            ?: l.quality.takeIf { it > 0 }
            ?: resolutionFromUrl(l.url).takeIf { it > 0 }
            ?: 0
        val langTag: String? = when {
            facts != null && facts.audio.mapNotNull { languageTagOf(it.language, it.name) }.distinct().size > 1 -> "Multi"
            facts != null && facts.audio.isNotEmpty() ->
                (facts.audio.firstOrNull { it.isDefault } ?: facts.audio.singleOrNull())
                    ?.let { languageTagOf(it.language, it.name) }
            declaredHindi(l.source, l.url) -> "Hindi"
            else -> null
        }

        var base = (l.source ?: l.name ?: "Server").trim()
        base = base.replace(Regex("""\s*\((?:Hindi|English|Multi|Urdu|Tamil|Telugu|Malayalam|Kannada|Marathi|Bengali|Japanese|Korean|Chinese|Spanish|French|Arabic|Unknown)\)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d{3,4}p\s*$|\s+4K\s*$""", RegexOption.IGNORE_CASE), "")
            .dedupeResolution()
        if (langTag != null && !base.contains(langTag, ignoreCase = true)) {
            base = "$base ($langTag)"
        }
        if (base == (l.source ?: l.name)) return l
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

    private fun String.dedupeResolution(): String {
        val token = Regex("""\d{3,4}p|4K|2160p""", RegexOption.IGNORE_CASE)
        val seen = mutableSetOf<String>()
        val out = token.replace(this) { m ->
            val k = m.value.lowercase()
            if (!seen.add(k)) "" else m.value
        }
        return out.replace(Regex("""\s{2,}"""), " ").trim()
    }

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

    internal fun resolveRelative(baseUrl: String, path: String): String {
        if (path.startsWith("//")) return "https:$path"
        if (path.startsWith("http", ignoreCase = true)) return path
        val schemeHost = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    suspend fun unwrapEmbed(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = sharedHeaders,
    ): String {
        var current = url
        repeat(MAX_UNWRAP_LEVELS) {
            if (current.contains(".m3u8", ignoreCase = true) ||
                current.contains(".mp4", ignoreCase = true) ||
                current.contains("serve_m3u8=", ignoreCase = true)
            ) return current
            val text = runCatching {
                app.get(current, timeout = 5, headers = headersFor(current, referer, headers)).text
            }.getOrNull() ?: return current
            buildProxyStreamUrl(text, current)?.let { return it }
            extractStreamUrl(text)?.let { return it }
            extractVideoSourceUrl(text, current)?.let { return it }
            val next = Jsoup.parse(text).select("iframe").firstNotNullOfOrNull { frame ->
                frame.attr("src").takeIf { it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:") }
            } ?: return current
            val resolved = resolveRelative(current, next)
            if (resolved == current) return current
            current = resolved
        }
        return current
    }

    suspend fun pull(
        sources: List<Source>,
        timeoutMs: Long = MultimoviesProvider.SOURCE_TIMEOUT_MS,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: suspend (ExtractorLink) -> Unit = {},
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

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

    internal fun isYouTubeHost(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("youtube.com") || host.contains("youtu.be") ||
            host.contains("youtube-nocookie")
    }

    private suspend fun extractSource(
        src: Source,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        if (isYouTubeHost(src.url)) return emptyList()

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

        if (hostOf(src.url).contains("nxsha")) {
            val subs = mutableListOf<SubtitleFile>()
            val nxLinks = NxshaExtractor.extract(src) { subs.add(SubtitleFile(it.lang, it.url)) }
            subs.forEach { onSubtitle(it) }
            val out = mutableListOf<ExtractorLink>()
            for (s in nxLinks) {
                val source = s.name
                val type = if (s.isM3u8 || s.url.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else if (s.isDash || s.url.contains(".mpd", ignoreCase = true)) {
                    ExtractorLinkType.DASH
                } else ExtractorLinkType.VIDEO
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

        if (hostOf(src.url).contains("videm")) {
            val out = mutableListOf<ExtractorLink>()
            for (s in VidemExtractor.extract(src)) {
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

        if (src.url.contains("vidsync.pro", ignoreCase = true)) return emptyList()

        if (hostOf(src.url).contains("modiplay")) {
            val out = mutableListOf<ExtractorLink>()
            for (s in ModiplayExtractor.extract(src.url)) {
                val refererHeader = s.headers["Referer"] ?: src.url
                out += newExtractorLink(
                    source = s.name,
                    name = s.name,
                    url = s.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = refererHeader
                    quality = getQualityFromName(s.url)
                    this.headers = s.headers + src.headers
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        if (hostOf(src.url).contains("vsembed.ru")) {
            val resolved = VsEmbedExtractor.resolve(
                src.url, if (src.season != null) "tv" else "movie", src.season, src.episode,
            )?.takeIf { it != src.url } ?: return emptyList()
            return extractSource(src.copy(url = resolved, referer = src.url), onSubtitle)
        }

        if (hostOf(src.url).contains("vidout.pages.dev")) {
            val tv = src.season != null
            val tmdb = src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
                ?: extractTmdbIdFromServerUrl(src.url)
                ?: src.imdbId?.let { tmdbForImdb(it) }
                ?: return emptyList()
            val out = mutableListOf<ExtractorLink>()
            for (u in VidoutExtractor.extract(tmdb, if (tv) "tv" else "movie", src.season)) {
                val refererHeader = src.referer ?: src.url
                out += newExtractorLink(
                    source = src.name,
                    name = src.name,
                    url = u,
                    type = linkTypeFor(u, m3u8Hint = true),
                ) {
                    referer = refererHeader
                    quality = getQualityFromName(u)
                    this.headers = src.headers + ("Referer" to refererHeader)
                    extractorData = null
                    audioTracks = emptyList()
                }
            }
            return out
        }

        if (hostOf(src.url).contains("bingr.one") || hostOf(src.url).contains("filmu.in") || hostOf(src.url).contains("vidbolt.xyz")) {
            return apiHostLinks(src, onSubtitle)
        }

        if (hostOf(src.url).contains("2embed")) {
            val html = HttpKit.get(src.url, mapOf("Referer" to (src.referer ?: src.url)), 6_000L)
                ?: return emptyList()
            val target = resolveTwoEmbedTarget(html, src.imdbId?.takeIf { it.startsWith("tt") })
                ?.takeIf { it != src.url } ?: return emptyList()
            return extractSource(src.copy(url = target, referer = src.url), onSubtitle)
        }

        directStreamLink(src)?.let { return listOf(it) }

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

        return sniff(src)
    }

    private suspend fun tmdbForImdb(imdbId: String): String? = withTimeoutOrNull(4000L) {
        TmdbService.findByImdb(imdbId)?.first?.toString()
    }

    private suspend fun apiTitleYear(tmdbId: String?, type: String): Pair<String, String> {
        val id = tmdbId?.toIntOrNull() ?: return "" to ""
        val d = withTimeoutOrNull(3000L) { TmdbService.fetchMeta(id, type) } ?: return "" to ""
        return (d.name.orEmpty() to d.year.orEmpty())
    }

    private fun linkTypeFor(url: String, m3u8Hint: Boolean = false): ExtractorLinkType = when {
        m3u8Hint || url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
        url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private suspend fun hostLink(
        label: String,
        s: VidemSource,
        fallbackReferer: String,
        fallbackHeaders: Map<String, String>,
    ): ExtractorLink {
        val refererHeader = s.headers["Referer"] ?: fallbackReferer
        return newExtractorLink(
            source = label,
            name = label,
            url = s.url,
            type = linkTypeFor(s.url, m3u8Hint = s.isM3u8),
        ) {
            referer = refererHeader
            quality = getQualityFromName(s.quality.ifEmpty { s.url })
            this.headers = s.headers + fallbackHeaders + ("Referer" to refererHeader)
            extractorData = null
            audioTracks = emptyList()
        }
    }

    private suspend fun apiHostLinks(src: Source, onSubtitle: (SubtitleFile) -> Unit): List<ExtractorLink> {
        val host = hostOf(src.url)
        val tv = src.season != null
        val type = if (tv) "tv" else "movie"
        val tmdb = src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
            ?: extractTmdbIdFromServerUrl(src.url)
            ?: src.imdbId?.let { tmdbForImdb(it) }
        val imdb = src.imdbId?.takeIf { it.startsWith("tt") }
            ?: extractImdbIdFromServerUrl(src.url)
        val (title, year) = apiTitleYear(tmdb, type)
        val subs = mutableListOf<SubtitleFile>()
        val emitSub: (NxshaSubtitle) -> Unit = { subs.add(SubtitleFile(it.lang, it.url)) }
        val streams: List<VidemSource> = when {
            host.contains("bingr.one") -> {
                val id = tmdb ?: return emptyList()
                BingrExtractor.extract(id, type, title, year, src.season, src.episode, emitSub)
            }
            host.contains("filmu.in") -> FilmuExtractor.extract(imdb, tmdb, type, title, year, src.season, src.episode, emitSub)
            else -> VidboltExtractor.extract(imdb, tmdb, type, title, year, src.season, src.episode, emitSub)
        }
        subs.forEach { onSubtitle(it) }
        val refererHeader = src.referer ?: src.url
        return streams.map { s ->
            hostLink(if (host.contains("bingr.one")) "Bingr" else s.name.ifBlank { src.name }, s, refererHeader, src.headers)
        }
    }

    private suspend fun directStreamLink(src: Source): ExtractorLink? {
        val u = src.url
        val isStream = u.contains("serve_m3u8=1", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".webm", ignoreCase = true)
        if (!isStream) return null
        val label = src.name
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
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

enum class SourceId { IMDB, TMDB }

data class SourceMeta(
    val imdbId: String,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

object SourceMetaCache {
    private val map = ConcurrentHashMap<String, SourceMeta>()
    fun put(key: String, meta: SourceMeta) = map.put(key, meta)
    fun get(key: String): SourceMeta? = map[key]
}

data class ResolvedEmbed(
    val name: String,
    val url: String,
    val embedUrl: String? = null,
    val unwrapped: Boolean = false,
    val key: String = "",
)

internal fun dooplayOptionKey(post: String, nume: String, type: String): String =
    "$post|$nume|$type"

internal fun ResolvedEmbed.embedIdentity(): String =
    key.ifEmpty { embedUrl ?: url }

object EmbedPrefetchCache {
    private class Entry(
        val embeds: List<ResolvedEmbed>?,
        val inFlight: Deferred<List<ResolvedEmbed>>?,
        val expiresAt: Long,
        val partial: MutableList<ResolvedEmbed> =
            Collections.synchronizedList(mutableListOf()),
    )

    private const val TTL_MS = 4 * 60 * 1000L
    private const val MAX_SIZE = 32

    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<ResolvedEmbed>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.embeds
    }

    fun publish(key: String, embed: ResolvedEmbed) {
        val e = map[key] ?: return
        if (System.currentTimeMillis() > e.expiresAt || e.embeds != null) return
        synchronized(e.partial) {
            if (e.partial.none { it.embedIdentity() == embed.embedIdentity() }) e.partial.add(embed)
        }
    }

    fun arrived(key: String): List<ResolvedEmbed> {
        val e = map[key] ?: return emptyList()
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return emptyList()
        }
        if (e.embeds != null) return emptyList()
        return synchronized(e.partial) { e.partial.toList() }
    }

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
        return map[key]?.inFlight?.await() ?: get(key) ?: emptyList()
    }

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

    fun invalidate(key: String) {
        map.remove(key)
    }
}

object LinkCache {
    private data class Entry(
        val links: List<ExtractorLink>,
        val expiresAt: Long,
    )
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(imdbId: String?, season: Int?, episode: Int?): List<ExtractorLink>? {
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

object FastStartCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<ExtractorLink>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
        return e.links
    }

    fun put(key: String, links: List<ExtractorLink>) {
        if (links.isEmpty()) return
        val existing = get(key).orEmpty()
        val merged = (existing + links).distinctBy { it.url }
        map[key] = Entry(merged, System.currentTimeMillis() + TTL_MS)
    }
}

class GlobalSource(
    val name: String,
    val idType: SourceId,
    val buildUrl: (id: String, season: Int?, episode: Int?) -> String?,
    val headers: Map<String, String> = emptyMap(),
)

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
            name = "VidSrc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://vsembed.ru/embed/$id/$s-$e"
                else "https://vsembed.ru/embed/$id"
            },
            headers = mapOf("Referer" to "https://vsembed.ru/"),
        ),
        GlobalSource(
            name = "Nxsha",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://nxsha.space/embed/tv/$id/$s/$e"
                else "https://nxsha.space/embed/movie/$id"
            },
            headers = mapOf("Referer" to "https://nxsha.space/"),
        ),
        GlobalSource(
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


