package com.vegamovies
/**

 * FILE: VegamoviesPlugin.kt — the Vegamovies plugin and provider engine.
 *
 * Scrapes the Vegamovies WordPress network:
 *  - vegamovies.*  — Hollywood / South dubbed / web series
 *  - rogmovies.*   — Bollywood
 *
 * Key facts (verified by tools/ probes, Sept 2026):
 *  - Search is a Meilisearch JSON proxy: GET {site}/ts-search.php?q=..&page=1
 *    returning {hits:[{document:{permalink,post_title,post_thumbnail,imdb_id,category}}]}.
 *  - Category pages are server-rendered `div.poster-card` grids.
 *  - Detail pages hold per-quality download groups: an h5 label heading and a
 *    `Download Now` link to https://nexdrive.fit/genxfm{ID}/.
 *  - The genxfm page exposes BOTH server URLs in raw HTML (the countdown is
 *    cosmetic client JS): fastdl.zip/embed?download=.. (resolves to a DIRECT
 *    video-downloads.googleusercontent.com .mkv) and vcloud.fit/.. (browser
 *    gated). See [NexdriveResolver].
 */

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Registers the Vegamovies provider with CloudStream.
 */
@CloudstreamPlugin
class Vegamovies : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegamoviesProvider())
    }
}

/** One parsed download-group: the h5 label plus its nexdrive (or other) gateway URL. */
internal data class DlLink(val label: String, val url: String)

/** Everything [VegamoviesProvider.load] scrapes off a detail page, before TMDB enrichment. */
internal data class ScrapedDetail(
    val title: String,
    val poster: String?,
    val year: Int?,
    val plot: String?,
    val imdbId: String?,
    val imdbRating: Double?,
    val language: String?,
    val isSeries: Boolean,
    /** Per quality-resolution link groups for a movie. */
    val links: List<DlLink>,
    /** Episodes of a web-series post: (season, episode, links). */
    val episodes: List<Triple<Int, Int?, List<DlLink>>>,
    /** Season-pack links that belong to no specific episode. */
    val packLinks: List<DlLink>,
)

/** The JSON payload stored in LoadResponse/Episode data for loadLinks(). */
internal data class LinkPayload(val pageUrl: String, val links: List<DlLink>)

/**
 * Vegamovies provider — WordPress scraper with TMDB keyless enrichment and a
 * two-phase live-fill download pipeline (user spec Sept 2026):
 *
 *   Phase 1 (first ~10s): each quality group resolves its direct file URL
 *           (fastdl -> video-downloads.googleusercontent.com .mkv). The FIRST
 *           resolved link starts playback — the host begins the video as soon
 *           as one link crosses auto-skip priority while loadLinks keeps running.
 *   Phase 2 (background, 90s window): every remaining group keeps resolving;
 *           links stream into the change-server list THE MOMENT they land. A
 *           group whose direct resolution fails (dead fastdl link, missing
 *           googleusercontent, 403) gets its nexdrive/vcloud page emitted as
 *           a FILE link so the list always has ALL the website's servers.
 */
class VegamoviesProvider : MainAPI() {

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        /** Seed domains — self-healed at runtime by [DomainResolver]. */
        const val SEED_VEGA = "https://new2.vegamovies.futbol"
        const val SEED_ROG = "https://new2.rogmovies.click"

        /** Live change-server window: loadLinks stays alive this long so the
         *  server list keeps growing while the video plays (user spec: "after
         *  solving give server list window in background when video keeps playing"). */
        const val LIVE_FILL_MS = 90_000L

        /** How long [loadLinks] waits for the FIRST direct link before it is
         *  (at most) this old when playback starts (user spec: "try both first
         *  direct and after 10 sec after solving"). */
        const val FIRST_DIRECT_BUDGET_MS = 10_000L

        const val SEARCH_MAX_RESULTS = 10

        /** Matches a nexdrive-gateway download link anywhere in a page. */
        val GENXFM_REGEX = Regex("""https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    }

    override var mainUrl = SEED_VEGA
    /** Bollywood sister-site base (also self-healed). */
    var bollywoodUrl = SEED_ROG

    override var name = "Vegamovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf("User-Agent" to UA, "Accept-Language" to "en-US,en;q=0.9")
    private var cfKiller: CloudflareKiller? = null

    /** In-session cache: detail page URL -> parsed payload JSON (re-tap = instant). */
    private val detailCache = ConcurrentHashMap<String, String>()

    // Getter (not one-shot val): category URLs must follow the live domains
    // whenever [DomainResolver] rotates to a fresh mirror mid-session.
    override val mainPage
        get() = mainPageOf(
            Pair("$mainUrl/", "Latest Releases"),
            Pair("$mainUrl/dual-audio-movies/", "Dual Audio Movies"),
            Pair("$mainUrl/hindi-dubbed-movies/", "Hindi Dubbed"),
            Pair(bollywoodUrl + "/bollywood/", "Bollywood"),
            Pair("$mainUrl/web-series/", "Web Series"),
            Pair("$mainUrl/web-series/netflix/", "Netflix"),
            Pair("$mainUrl/web-series/amazon-prime-video/", "Amazon Prime"),
            Pair("$mainUrl/web-series/jiohotstar/", "JioHotstar"),
            Pair("$mainUrl/movies-by-genres/action/", "Action"),
            Pair("$mainUrl/movies-by-quality/2160p/", "4K Ultra HD"),
            Pair("$mainUrl/anime-series/", "Anime"),
            Pair("$mainUrl/korean-series/", "K-Drama"),
        )

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    /** True when [doc] is a Cloudflare interstitial. */
    internal fun isChallenge(doc: Document): Boolean {
        val t = doc.title()
        return t.contains("just a moment", true) ||
            t.contains("checking your browser", true) ||
            doc.selectFirst("meta[name=robots][content*=noindex]") != null &&
            doc.body().text().isBlank()
    }

    /**
     * Fetch [url]: fast path with plain headers first (CloudStream persists the
     * cookie jar), CloudflareKiller solve on a challenge. Returns null on
     * hard failure; callers degrade gracefully.
     */
    suspend fun fetchDoc(
        url: String,
        timeoutSeconds: Long = 12,
        headers: Map<String, String> = commonHeaders,
    ): Document? {
        try {
            val doc = app.get(url, timeout = timeoutSeconds, headers = headers).document
            if (!isChallenge(doc)) return doc
        } catch (e: Exception) {
            // fall through to the challenge-solve path
        }
        return try {
            val killer = cfKiller ?: CloudflareKiller().also { cfKiller = it }
            val solved = app.get(url, timeout = 20, headers = headers, interceptor = killer).document
            if (isChallenge(solved)) {
                cfKiller = null
                null
            } else solved
        } catch (e: Exception) {
            null
        }
    }

    /** Re-point a URL whose host is a known vegamovies/rogmovies domain at the
     *  current live domain, so cached/old-domain links keep working after a rotation. */
    fun liveUrl(url: String): String {
        val host = Regex("""^https?://([^/]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: return url
        val live = when {
            host.contains("vegamovies") -> mainUrl
            host.contains("rogmovies") -> bollywoodUrl
            else -> return url
        }
        val liveHost = Regex("""^https?://([^/]+)""").find(live)?.groupValues?.get(1) ?: return url
        return url.replaceFirst(Regex("://$host"), "://$liveHost")
    }

    // ------------------------------------------------------------------
    // Domain self-healing
    // ------------------------------------------------------------------

    /**
     * Discovers the live Hollywood + Bollywood domains by scanning "OFFICIAL
     * PORTAL HUB" cross-links on whichever seed site still answers. Both sites
     * always announce each other, so one alive seed heals both.
     */
    suspend fun refreshDomains() = coroutineScope {
        val a = async { fetchDoc(SEED_VEGA, timeoutSeconds = 8) }
        val b = async { fetchDoc(SEED_ROG, timeoutSeconds = 8) }
        val docs = listOf(a.await(), b.await()).filterNotNull()
        for (doc in docs) {
            mainUrl = DomainResolver.pick(doc, "vegamovies") ?: mainUrl
            bollywoodUrl = DomainResolver.pick(doc, "rogmovies") ?: bollywoodUrl
        }
    }

    internal object DomainResolver {
        /** Picks the most common href for family (vegamovies|rogmovies) with a
         *  non-seed prefix (new3.*, live*…), falling back to any host of the family. */
        fun pick(doc: Document, family: String): String? {
            val hosts = doc.select("a[href]")
                .mapNotNull { Regex("""https?://[a-z0-9.\-]*$family\.[a-z]{2,10}""", RegexOption.IGNORE_CASE)
                    .find(it.attr("abs:href"))?.value?.lowercase() }
                .filterNot { it.contains("apk") || it.endsWith(".cfd") }
                .groupingBy { it.substringBeforeLast('/', it) }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return null
            return hosts.trimEnd('/')
        }
    }

    // ------------------------------------------------------------------
    // Search — Meilisearch JSON proxy on both sites, TMDB-poster backfill
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? = coroutineScope {
        if (query.isBlank()) return@coroutineScope null
        val vega = async { siteSearch(mainUrl, query) }
        val rog = async { siteSearch(bollywoodUrl, query) }
        val merged = (vega.await() + rog.await())
            .distinctBy { it.url }
            .take(SEARCH_MAX_RESULTS)
        if (merged.isEmpty()) {
            // Seeds may be stale: heal domains once and retry.
            refreshDomains()
            val retry = (listOf(mainUrl, bollywoodUrl).map { siteSearch(it, query, force = true) }.flatten())
                .distinctBy { it.url }
                .take(SEARCH_MAX_RESULTS)
            if (retry.isEmpty()) return@coroutineScope null
            postEnrich(retry)
            return@coroutineScope retry
        }
        postEnrich(merged)
        merged
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /** Calls {site}/ts-search.php and maps JSON hits to SearchResponses. */
    internal suspend fun siteSearch(baseUrl: String, query: String, force: Boolean = false): List<SearchResponse> {
        val url = "$baseUrl/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=1"
        val text = runCatching {
            app.get(url, timeout = 7, headers = commonHeaders + mapOf("Referer" to "$baseUrl/")).text
        }.getOrNull() ?: return emptyList()
        return parseSearchHits(text, baseUrl)
    }

    /**
     * Pure: map a ts-search.php JSON response into SearchResponses. Exported
     * (internal) so unit tests run against captured payloads without network.
     */
    internal fun parseSearchHits(json: String, baseUrl: String): List<SearchResponse> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
            val permalink = str(doc, "permalink") ?: return@mapNotNull null
            val rawTitle = str(doc, "post_title") ?: return@mapNotNull null
            val title = cleanSearchTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null
            val absolute = if (permalink.startsWith("http")) permalink else baseUrl.trimEnd('/') + permalink
            val categories = parseCategoryArray(doc.optJSONArray("category"))
            val poster = str(doc, "post_thumbnail")
            val year = Regex("""\((\d{4})\)?""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            if (isSeries(permalink, categories, rawTitle)) {
                newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
                    posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, absolute, TvType.Movie) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    internal fun parseCategoryArray(arr: JSONArray?): List<String> =
        arr?.let { (0 until it.length()).mapNotNull { i -> runCatching { it.getString(i) }.getOrNull() } } ?: emptyList()

    /** Clean a "Download X (2019) ... qualities" title down to "X (2019)". */
    internal fun cleanSearchTitle(raw: String): String {
        var t = raw.trim().removePrefix("Download").trim()
        // Cut the title at the year+quality noise: keep through the closing "(2019)".
        Regex("""^(.*?\(\d{4}[^)]*\))[) ]*\s""").find(t + " ")?.let { m ->
            t = m.groupValues[1].trim()
        } ?: run {
            t = t.substringBefore("] ").trim()
        }
        // Strip trailing qualities/sources that survived.
        t = t.replace(Regex("""(?i)\s*(\d{3,4}p.*|WEB-?DL.*|BluRay.*|HDTS.*|HD.*|4K.*)$"""), "").trim()
        return t.trim(' ', '-', '|', ':')
    }

    /** Series heuristic: permalink categories or title carry series markers. */
    internal fun isSeries(permalink: String, categories: List<String>, rawTitle: String): Boolean {
        val hay = (permalink + " " + categories.joinToString(" ") + " " + rawTitle).lowercase()
        return Regex("""season|series|s\d{1,2}e\d|-ep|episod|drama|anime|tv-show|web-?show""").containsMatchIn(hay)
    }

    /** Fill missing posters from TMDB in parallel (bounded, best-effort). */
    private suspend fun postEnrich(results: List<SearchResponse>) {
        val need = results.filter { it.posterUrl.isNullOrBlank() }.take(6)
        if (need.isEmpty()) return
        val sem = Semaphore(3)
        coroutineScope {
            need.map { r ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(2500L) {
                            val hit = MetadataService.search(r.name).firstOrNull()
                            if (r.posterUrl.isNullOrBlank() && hit?.poster != null) r.posterUrl = hit.poster
                            hit
                        }
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
    }

    // ------------------------------------------------------------------
    // Main page — server-rendered poster-card grids
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        val url = if (page > 1) "${base.trimEnd('/')}/page/$page/" else base
        val doc = fetchDoc(url)
            ?: run {
                refreshDomains()
                fetchDoc(if (page > 1) "${base.trimEnd('/')}/page/$page/" else base)
            } ?: return newHomePageResponse(request.name, emptyList())
        val items = parseListing(doc)
        postEnrich(items)
        return newHomePageResponse(request.name, items, false)
    }

    /** Pure: scrape poster-cards from a listing page. */
    internal fun parseListing(doc: Document): List<SearchResponse> {
        return doc.select("div.poster-card, article, div[itemprop=item]").mapNotNull { card ->
            val href = card.selectFirst("meta[itemprop=url]")?.attr("content")
                ?: card.selectFirst("""a[href*="/download-"]""")?.attr("abs:href")
                ?: return@mapNotNull null
            if (!Regex("""/download-|/[\w\-]+-\d{4}""").containsMatchIn(href)) return@mapNotNull null
            val img = card.selectFirst("img") ?: return@mapNotNull null
            val rawTitle = img.attr("alt").ifBlank { card.selectFirst(".poster-title, h2, h3")?.text() ?: "" }
            if (rawTitle.isBlank()) return@mapNotNull null
            val poster = listOf(
                img.attr("src"), img.attr("abs:data-src"), img.attr("data-lazy-src"),
            ).firstOrNull { it.isNotBlank() }
            val title = cleanSearchTitle(rawTitle)
            val year = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            val cats = card.select(".poster-quality, .badge, .category").map { it.text() }
            if (isSeries(href, cats, rawTitle)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster; this.year = year
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster; this.year = year
                }
            }
        }.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Load — detail page + TMDB enrichment
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        detailCache[url]?.let { return fromPayload(url, it) }
        val doc = fetchDoc(url) ?: run {
            refreshDomains()
            fetchDoc(liveUrl(url)) ?: throw ErrorLoadingException("Could not load $url")
        }
        val scraped = parseDetail(doc, url)
            ?: throw ErrorLoadingException("Unsupported page: $url")

        // TMDB enrichment: imdbId scraped from the page makes the match exact.
        val meta = withTimeoutOrNull(6000L) {
            MetadataService.enrich(scraped.title, scraped.year?.toString(), scraped.imdbId)
        }

        val title = meta?.name ?: scraped.title
        val poster = scraped.poster?.takeIf { it.isNotBlank() } ?: meta?.poster
        val plot = meta?.overview ?: scraped.plot
        val tags = (meta?.genres ?: scraped.language?.let { listOf(it) })
        val year = scraped.year ?: meta?.year?.toIntOrNull()

        val payload = buildPayload(url, scraped)
        detailCache[url] = payload

        return if (scraped.isSeries) {
            val episodes = scraped.episodes.map { (season, ep, links) ->
                newEpisode(LinkPayload(url, links).toJson()) {
                    this.name = "Episode ${ep ?: "?"}"
                    this.season = season
                    this.episode = ep
                }
            } + scraped.packLinks.map { pack ->
                newEpisode(LinkPayload(url, listOf(pack)).toJson()) {
                    this.name = pack.label
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                backgroundPosterUrl = meta?.backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = meta?.cast
                scraped.imdbId?.let { addImdbId(it) }
                (meta?.rating ?: scraped.imdbRating)?.let { addScore(it.toString(), 10) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, payload) {
                posterUrl = poster
                backgroundPosterUrl = meta?.backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = meta?.cast
                scraped.imdbId?.let { addImdbId(it) }
                (meta?.rating ?: scraped.imdbRating)?.let { addScore(it.toString(), 10) }
            }
        }
    }

    /** Rebuild a LoadResponse from the cached payload (poster/title lost — refetch metadata cheaply). */
    private suspend fun fromPayload(url: String, payload: String): LoadResponse? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val links = json.optJSONArray("links") ?: return null
        val list = dlLinksFromJson(links)
        val title = url.substringAfterLast('/').substringBefore("-20").replace('-', ' ').trim()
        return if (json.optBoolean("series", false)) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode(LinkPayload(url, list).toJson()) { this.name = title }
            ))
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, payload)
        }
    }

    private fun buildPayload(pageUrl: String, scraped: ScrapedDetail): String {
        val arr = JSONArray()
        (scraped.links + scraped.episodes.flatMap { it.third } + scraped.packLinks).forEach {
            arr.put(JSONObject().put("l", it.label).put("u", it.url))
        }
        val o = JSONObject()
            .put("page", pageUrl)
            .put("links", arr)
            .put("series", scraped.isSeries)
        val s = o.toString()
        return s
    }

    private fun LinkPayload.toJson(): String = JSONObject()
        .put("page", pageUrl)
        .put("links", JSONArray().apply {
            links.forEach { put(JSONObject().put("l", it.label).put("u", it.url)) }
        })
        .toString()

    /** Parses a data payload (movie payload / per-episode JSON) into LinkPayload. */
    internal fun parsePayload(data: String): LinkPayload? {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val page = str(o, "page") ?: ""
        val arr = o.optJSONArray("links") ?: return null
        return LinkPayload(page, dlLinksFromJson(arr))
    }

    private fun dlLinksFromJson(arr: JSONArray): List<DlLink> =
        (0 until arr.length()).mapNotNull { i ->
            val l = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = str(l, "u") ?: return@mapNotNull null
            DlLink(str(l, "l") ?: "", u)
        }

    // ------------------------------------------------------------------
    // Detail-page parsing
    // ------------------------------------------------------------------

    /** Pure parse of a Vegamovies/RogMovies download post. */
    internal fun parseDetail(doc: Document, pageUrl: String): ScrapedDetail? {
        val h1 = doc.selectFirst("h1, entry-title, .post-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val title = cleanSearchTitle(h1.removePrefix("Download").trim())
        val year = Regex("""\((\d{4})""").find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?.ifBlank { null }
            ?: doc.selectFirst("article img, .entry-content img")?.absUrl("src")

        val body = doc.body().text()
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""").find(body)?.groupValues?.get(1)
            ?: doc.select("a[href]").firstOrNull { it.attr("href").contains("imdb.com/title/") }
                ?.let { Regex("""(tt\d+)""").find(it.attr("href"))?.value }
        val imdbRating = Regex("""(?i)IMDb Rating:?\s*[-–]?\s*([\d.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        val language = Regex("""(?im)^Language:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()

        val plot = Regex("""(?i)SYNOPSIS|PLOT:""").find(body)?.let { m ->
            val after = body.substring(m.range.last + 1)
            val cut = after.indexOf("Screenshots", ignoreCase = true)
            (if (cut >= 0) after.substring(0, cut) else after)
                .trim(':', '-', ' ', '\n')
                .take(1200)
                .takeIf { it.isNotBlank() }
        }

        // Download groups: every nexdrive link carries its nearest preceding heading.
        val anchors = doc.select("a[href]").filter { GENXFM_REGEX.containsMatchIn(it.attr("abs:href")) }
        if (anchors.isEmpty()) return null
        val links = anchors.mapNotNull { a ->
            val u = a.attr("abs:href")
            val label = nearestHeading(a)
                ?: a.text().takeIf { it.isNotBlank() && !it.contains("download", true) }
                ?: "Download"
            DlLink(label, u)
        }
        if (links.isEmpty()) return null

        // Series? Split per-episode when labels carry episode markers.
        val epRegex = Regex("""(?i)(?:S(\d{1,2})[\s.-]*E(\d{1,3})|(\d{1,2})x(\d{2})|(?:episode|ep\.?)[\s:#-]*(\d{1,3}))""")
        val episodes = mutableMapOf<Pair<Int, Int>, MutableList<DlLink>>()
        val unmatched = mutableListOf<DlLink>()
        for (l in links) {
            val m = epRegex.find(l.label)
            if (m != null) {
                val s = (m.groupValues[1].ifBlank { m.groupValues[3] }).toIntOrNull() ?: 1
                val e = (m.groupValues[2].ifBlank { m.groupValues[4] }.ifBlank { m.groupValues[5] }).toIntOrNull()
                episodes.getOrPut(s to (e ?: 0)) { mutableListOf() }.add(l)
            } else unmatched.add(l)
        }
        val isSeries = episodes.isNotEmpty() ||
            (Regex("""(?i)season|series|drama|anime""").containsMatchIn(h1) && links.size > 2)

        val epList = episodes.entries.map { (key, lks) -> Triple(key.first, key.second.takeIf { it > 0 }, lks) }
            .sortedWith(compareBy({ it.first }, { it.second ?: 0 }))
        return ScrapedDetail(
            title = title, poster = poster, year = year,
            plot = plot, imdbId = imdbId, imdbRating = imdbRating,
            language = language, isSeries = isSeries,
            links = if (isSeries) emptyList() else links,
            episodes = if (isSeries) epList else emptyList(),
            packLinks = if (isSeries && unmatched.isNotEmpty() && epList.isNotEmpty()) unmatched else emptyList(),
        )
    }

    /** The closest preceding heading (h1-h6 / strong) text for a download anchor:
     *  walk previous siblings and their subtrees, then fall back to the earliest
     *  heading inside an ancestor block that isn't a generic label. */
    private fun nearestHeading(a: Element): String? {
        fun headingText(el: Element): String? {
            if (Regex("^h[1-6]$").matches(el.tagName()) || el.tagName() == "strong" || el.tagName() == "b") {
                val t = el.text().trim()
                if (t.isNotBlank() && t.length < 200) return t
            }
            return el.select("h1,h2,h3,h4,h5,h6,strong").lastOrNull()?.text()?.trim()
                ?.takeIf { it.isNotBlank() && it.length < 200 }
        }
        var el: Element? = a.previousElementSibling()
        while (el != null) {
            headingText(el)?.let { return it }
            el = el.previousElementSibling()
        }
        a.parents().forEach { parent ->
            parent.select("h1,h2,h3,h4,h5,h6,strong").firstOrNull()
                ?.text()?.trim()?.takeIf { it.isNotBlank() && it.length < 200 && !it.contains("Download Now", true) }
                ?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // loadLinks — two-phase live-fill (all servers, direct first)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = parsePayload(data) ?: return false
        if (payload.links.isEmpty()) return false
        val referer = payload.pageUrl.ifBlank { mainUrl }
        var emitted = false

        // Single-flight: resolve all groups concurrently, bounded; results push
        // to the player THE MOMENT they land (user spec: server list grows in
        // the background while the video keeps playing).
        withTimeoutOrNull(LIVE_FILL_MS) {
            coroutineScope {
                val sem = Semaphore(4)
                payload.links.map { link ->
                    async {
                        sem.acquire()
                        try {
                            val resolved = NexdriveResolver.resolve(link.url, referer, commonHeaders)
                            for (r in resolved) {
                                val quality = getQualityFromName(link.label.ifBlank { r.name })
                                if (r.direct) {
                                    emitted = true
                                    callback(
                                        ExtractorLink(
                                            source = "Vegamovies",
                                            name = "${link.label.ifBlank { "Direct" }}",
                                            url = r.url,
                                            referer = r.referer,
                                            quality = quality,
                                            headers = commonHeaders + mapOf("Referer" to r.referer),
                                            extractorData = null,
                                            type = ExtractorLinkType.VIDEO,
                                            audioTracks = emptyList(),
                                        )
                                    )
                                } else {
                                    // Browser-gated server: still list it so ALL
                                    // website servers appear (user: "want all links").
                                    // Quality reads UNKNOWN so the player never
                                    // auto-skips to a page it cannot stream.
                                    callback(
                                        ExtractorLink(
                                            source = "Vegamovies",
                                            name = "${link.label.ifBlank { "Server" }} (browser)",
                                            url = r.url,
                                            referer = referer,
                                            quality = 0,
                                            headers = commonHeaders,
                                            extractorData = null,
                                            type = ExtractorLinkType.VIDEO,
                                            audioTracks = emptyList(),
                                        )
                                    )
                                    emitted = true
                                }
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll()
            }
        }
        if (!emitted) {
            // Total failure: surface the raw nexdrive gateways so the user at
            // least gets the working web download.
            payload.links.forEach { link ->
                callback(
                    ExtractorLink(
                        source = "Vegamovies",
                        name = "${link.label.ifBlank { "Server" }} (browser)",
                        url = link.url,
                        referer = referer,
                        quality = 0,
                        headers = commonHeaders,
                        extractorData = null,
                        type = ExtractorLinkType.VIDEO,
                        audioTracks = emptyList(),
                    )
                )
            }
            emitted = true
        }
        return emitted
    }
}
