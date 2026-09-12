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
 *    returning {hits:[{document:{permalink,post_title,post_thumbnail,
 *    imdb_id,category}}]}.
 *  - Category pages are server-rendered `div.poster-card` grids.
 *  - Download chips ("⚡ G-Direct", "⚡ V-Cloud", "🗜 Batch/Zip") each link to a
 *    nexdrive.fit/genxfm{ID}/ page whose H1 ends "= G-Direct | = V-Cloud |
 *    = Batch" and whose body holds N concrete server URLs (N=1 for movies,
 *    N=episode-count for per-episode series groups, in document order).
 *  - fastdl embeds resolve to a DIRECT, streamable + downloadable
 *    googleusercontent file URL (see [NexdriveResolver]); vcloud/zip stay
 *    browser-downloads.
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Registers the Vegamovies provider with CloudStream. */
@CloudstreamPlugin
class Vegamovies : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegamoviesProvider())
    }
}

/** One download chip scraped under a heading: its gateway URL + chip text. */
internal data class DlLink(val gatewayUrl: String, val chip: String, val heading: String)

/** One payload entry handed to loadLinks(): concrete file URL or (gateway, idx). */
internal data class PayloadLink(
    val url: String,
    /** Server family when known at scrape time (from chip), else "". */
    val kind: String = "",
    val heading: String = "",
    /** Index into the gateway's ordered concrete links (series episodes). */
    val idx: Int = 0,
    /** True = [url] is a genxfm gateway that must be expanded. */
    val isGateway: Boolean = true,
    val season: Int? = null,
    val episode: Int? = null,
)

internal data class LinkPayload(val pageUrl: String, val links: List<PayloadLink>) {
    fun toJson(): String {
        val arr = JSONArray()
        links.forEach {
            arr.put(
                JSONObject().put("u", it.url).put("k", it.kind).put("h", it.heading)
                    .put("i", it.idx).put("g", it.isGateway)
                    .put("s", it.season ?: JSONObject.NULL).put("e", it.episode ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("page", pageUrl).put("links", arr).toString()
    }

    companion object {
        fun fromJson(data: String): LinkPayload? {
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
            val arr = o.optJSONArray("links") ?: return null
            val links = (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = l.optString("u").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PayloadLink(
                    url = u,
                    kind = l.optString("k"),
                    heading = l.optString("h"),
                    idx = l.optInt("i", 0),
                    isGateway = l.optBoolean("g", true),
                    season = l.optInt("s", -1).takeIf { it > 0 },
                    episode = l.optInt("e", -1).takeIf { it > 0 },
                )
            }
            return LinkPayload(o.optString("page"), links)
        }
    }
}

/**
 * Vegamovies provider — WordPress scraper with TMDB keyless enrichment and a
 * two-phase live-fill server pipeline (user spec Sept 2026):
 *
 *   Phase 1 (first ~10s): every server group's G-Drive chip is expanded →
 *           embed → googleusercontent DIRECT file; the FIRST such link starts
 *           playback while loadLinks keeps running.
 *   Phase 2 (90s window): all remaining qualities/chips keep landing in the
 *           change-server list in arrival order; download-only servers
 *           (V-Cloud, Batch/Zip, dead embeds) are listed too — labeled
 *           "[Downloadable]" so the player never auto-selects them.
 */
class VegamoviesProvider : MainAPI() {

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        /** Seed domains — self-healed at runtime by [DomainResolver]. */
        const val SEED_VEGA = "https://new2.vegamovies.futbol"
        const val SEED_ROG = "https://new2.rogmovies.click"

        /** Live change-server window (user: server list must keep growing in
         *  the background while the video plays). */
        const val LIVE_FILL_MS = 90_000L

        const val SEARCH_MAX_RESULTS = 10

        /** Matches a nexdrive-gateway download link anywhere in a page. */
        val GENXFM_REGEX = Regex(
            """https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE,
        )

        /** A genxfm anchor belongs to the CURRENT heading only when that heading
         *  looks like a download group (quality / size / codec / pack wording) —
         *  comment, search, sidebar and "Info:" headings never collect links. */
        val DOWNLOAD_HEADING = Regex(
            """(?i)(\d{3,4}p\b|4K|WEB[\s-]?DL|WEBRip|Blu\s?Ray|BD-?Rip|HD-?Rip|DVDRip|x26[45]|HEVC|AVC\b|\d+(?:\.\d+)?\s?(?:GB|MB)(?:/|\b)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|Complete|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""",
        )

        /** Heading noise on comments/sidebar blocks that must not label links. */
        val NOISE_HEADING = Regex(
            """(?i)(leave a comment|search movies|recent updates|related|screenshot|official portal|vegamovies 20|comment )""",
        )
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

    // Getter (not one-shot val): category URLs follow the live domains.
    override val mainPage
        get() = mainPageOf(
            Pair("$mainUrl/", "Latest Releases"),
            Pair("$mainUrl/dual-audio-movies/", "Dual Audio Movies"),
            Pair("$mainUrl/hindi-dubbed-movies/", "Hindi Dubbed"),
            Pair("$bollywoodUrl/bollywood/", "Bollywood"),
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
        return t.contains("just a moment", true) || t.contains("checking your browser", true)
    }

    /** Fetch [url]: plain fast path, CloudflareKiller solve on challenge. */
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
            if (isChallenge(solved)) { cfKiller = null; null } else solved
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Domain self-healing
    // ------------------------------------------------------------------

    /** Live Hollywood + Bollywood domains via the posts' own cross-links. */
    suspend fun refreshDomains() = coroutineScope {
        val a = async { fetchDoc(SEED_VEGA, timeoutSeconds = 8) }
        val b = async { fetchDoc(SEED_ROG, timeoutSeconds = 8) }
        for (doc in listOf(a.await(), b.await()).filterNotNull()) {
            mainUrl = DomainResolver.pick(doc, "vegamovies") ?: mainUrl
            bollywoodUrl = DomainResolver.pick(doc, "rogmovies") ?: bollywoodUrl
        }
    }

    internal object DomainResolver {
        /** The most common vegamovies / rogmovies family href on the page
         *  (the live mirror; each site always announces the other). */
        fun pick(doc: Document, family: String): String? =
            doc.select("a[href]")
                .mapNotNull {
                    Regex("""https?://[a-z0-9.\-]*$family\.[a-z]{2,10}""", RegexOption.IGNORE_CASE)
                        .find(it.attr("abs:href"))?.value?.lowercase()
                }
                .filterNot { it.contains("apk") || it.endsWith(".cfd") }
                .map { it.substringBefore('/', it) }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }
                ?.key
    }

    // ------------------------------------------------------------------
    // Search — Meilisearch JSON proxy on both sites
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? = coroutineScope {
        if (query.isBlank()) return@coroutineScope null
        val vega = async { siteSearch(mainUrl, query) }
        val rog = async { siteSearch(bollywoodUrl, query) }
        var merged = (vega.await() + rog.await()).distinctBy { it.url }.take(SEARCH_MAX_RESULTS)
        if (merged.isEmpty()) {
            refreshDomains()
            merged = listOf(mainUrl, bollywoodUrl)
                .map { siteSearch(it, query) }.flatten()
                .distinctBy { it.url }.take(SEARCH_MAX_RESULTS)
        }
        if (merged.isEmpty()) return@coroutineScope null
        backfillPosters(merged)
        merged
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /** Calls {site}/ts-search.php; pure JSON mapping in [parseSearchHits]. */
    internal suspend fun siteSearch(baseUrl: String, query: String): List<SearchResponse> {
        val url = "$baseUrl/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=1"
        val text = runCatching {
            app.get(url, timeout = 7, headers = commonHeaders + mapOf("Referer" to "$baseUrl/")).text
        }.getOrNull() ?: return emptyList()
        return parseSearchHits(text, baseUrl)
    }

    /** Pure: map a ts-search.php JSON response into SearchResponses. */
    internal fun parseSearchHits(json: String, baseUrl: String): List<SearchResponse> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
            val permalink = doc.optString("permalink").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawTitle = doc.optString("post_title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanSearchTitle(rawTitle)
            if (title.isBlank()) return@mapNotNull null
            val absolute = if (permalink.startsWith("http")) permalink else baseUrl.trimEnd('/') + permalink
            val categories = doc.optJSONArray("category")?.let { c ->
                (0 until c.length()).mapNotNull { ci -> c.optString(ci).takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val poster = doc.optString("post_thumbnail").takeIf { it.isNotBlank() }
            val year = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            if (isSeries(permalink, categories, rawTitle)) {
                newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
                    posterUrl = poster; this.year = year
                }
            } else {
                newMovieSearchResponse(title, absolute, TvType.Movie) {
                    posterUrl = poster; this.year = year
                }
            }
        }
    }

    /** Clean "Download X (2019) Dual Audio … 480p [500MB] | 720p …" to "X (2019)". */
    internal fun cleanSearchTitle(raw: String): String {
        var t = raw.trim().removePrefix("Download").trim()
        // Keep through the year parenthesis when present — anything after it is
        // quality/source noise and gets dropped wholesale. Season tags BEFORE
        // the year ("Reacher : Season 4 (2026)") survive with the kept prefix.
        val until = Regex("""(.*?\(\d{4}[^)]*\))""").find(t)
        if (until != null) {
            return until.groupValues[1].trim().trimEnd(' ', '-', '|', ':', ',')
        }
        // No year: trim trailing quality/source noise.
        t = t.replace(
            Regex("""(?i)\)\s*(\d{3,4}p[\s\S]*|WEB[\s-]?DL.*|BluRay.*|HDTS.*|HDTV.*|4K.*|Dual Audio.*|Hindi.*|S\d{2}E\d{2,}.*)$"""),
            "",
        ).trim()
        return t.trim(' ', '-', '|', ':', ',').ifBlank { raw.trim().take(80) }
    }

    /** Series heuristic for search/listing cards. */
    internal fun isSeries(permalink: String, categories: List<String>, rawTitle: String): Boolean {
        val hay = (permalink + " " + categories.joinToString(" ") + " " + rawTitle).lowercase()
        return Regex(
            """season|series|-series|drama|anime|s\d{1,2}e\d{1,3}|web-?show|tv-show|\beps\b|multimovies\.|complete""",
        ).containsMatchIn(hay) && !Regex("""full movie|-movie\b""").containsMatchIn(hay)
    }

    /** TMDB poster backfill for results without one (bounded, best-effort). */
    private suspend fun backfillPosters(results: List<SearchResponse>) {
        val need = results.filter { it.posterUrl.isNullOrBlank() }.take(6)
        if (need.isEmpty()) return
        val sem = Semaphore(3)
        coroutineScope {
            need.map { r ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(2500L) {
                            MetadataService.search(r.name).firstOrNull()?.poster?.let { r.posterUrl = it }
                        }
                    } finally { sem.release() }
                }
            }.awaitAll()
        }
    }

    // ------------------------------------------------------------------
    // Main page — server-rendered poster-card grids
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        val target = if (page > 1) "${base.trimEnd('/')}/page/$page/" else base
        var doc = fetchDoc(target, timeoutSeconds = 14)
        if (doc == null) {
            refreshDomains()
            val healed = if (page > 1) "${base.trimEnd('/')}/page/$page/" else base
            doc = fetchDoc(healed, timeoutSeconds = 14) ?: fetchDoc(target, timeoutSeconds = 14)
        }
        val items = doc?.let { parseListing(it) } ?: emptyList()
        backfillPosters(items)
        return newHomePageResponse(request.name, items)
    }

    /** Pure: scrape poster-cards from a listing page. */
    internal fun parseListing(doc: Document): List<SearchResponse> {
        return doc.select("div.poster-card").mapNotNull { card ->
            cardToSearch(card)
        }.ifEmpty {
            // Fallback theme: <a href*=/download-> + nearby img.
            doc.select("a[href*=\"/download-\"]").mapNotNull { a ->
                val card = a.parent() ?: return@mapNotNull null
                cardToSearch(card, a)
            }
        }.distinctBy { it.url }
    }

    private fun cardToSearch(card: Element, link: Element? = null): SearchResponse? {
        val href = card.selectFirst("meta[itemprop=url]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: link?.attr("abs:href")
            ?: card.selectFirst("""a[href*="/download-"]""")?.attr("abs:href")
            ?: return null
        if (!GENXFM_REGEX.containsMatchIn(href) && !href.contains("/download-")) return null
        val img = card.selectFirst("img")
        val rawTitle = img?.attr("alt").orEmpty().ifBlank { card.selectFirst(".poster-title, h2, h3")?.text().orEmpty() }
            .ifBlank { return null }
        val poster = img?.let { i ->
            listOf(i.attr("abs:src"), i.attr("abs:data-src"), i.attr("abs:data-lazy-src")).firstOrNull { it.isNotBlank() }
        }
        val title = cleanSearchTitle(rawTitle)
        val year = Regex("""\((\d{4})""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cats = card.select(".poster-quality, .badge").map { it.text() }
        return if (isSeries(href, cats, rawTitle)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster; this.year = year }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; this.year = year }
        }
    }

    // ------------------------------------------------------------------
    // Load — detail page, sections, gateway expansion, TMDB enrichment
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url) ?: run {
            refreshDomains()
            fetchDoc(url) ?: throw ErrorLoadingException("Could not load $url")
        }
        val h1 = doc.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("No title on $url")

        val scraped = parseDetail(doc)
        if (scraped.groups.isEmpty()) throw ErrorLoadingException("No download links on $url")

        val title = cleanSearchTitle(h1.removePrefix("Download").trim())
        val year = Regex("""\((\d{4})""").find(h1)?.groupValues?.get(1)?.toIntOrNull()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".entry-content img, article img")?.absUrl("src")
        val body = doc.body().text()
        val imdbId = scraped.imdbId
        val imdbRating = Regex("""(?i)IMDb Rating:?\s*[-–]?\s*([\d.]+)""").find(body)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val plot = Regex("""(?i)SYNOPSIS|\bPLOT:|\bPlot\b""").find(body)?.let { m ->
            val after = body.substring(m.range.last + 1)
            val cut = after.indexOf("Screenshots", ignoreCase = true)
            (if (cut >= 0) after.substring(0, cut) else after)
                .trim(':', '-', ' ', '\n').take(1200).takeIf { it.isNotBlank() }
        }
        val language = Regex("""(?im)^\s*Language:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()

        val meta = withTimeoutOrNull(6000L) { MetadataService.enrich(title, year?.toString(), imdbId) }

        val seasonsFor = scraped.groups.mapNotNull { it.season }.distinct()
        val isSeriesTitle = isSeries(url, emptyList(), h1)

        return if (isSeriesTitle || (seasonsFor.isNotEmpty() && scraped.groups.size > 1)) {
            buildSeriesResponse(url, title, year, poster, meta, imdbId, imdbRating, plot, language, scraped)
        } else {
            buildMovieResponse(url, title, year, poster, meta, imdbId, imdbRating, plot, language, scraped)
        }
    }

    /** Parsed detail: download groups + any IMDb id found on the page. */
    internal data class Scraped(val imdbId: String?, val groups: List<Group>)
    /** One quality heading block and the server chips under it. */
    internal data class Group(val heading: String, val season: Int?, val links: List<DlLink>, val pack: Boolean)

    /**
     * Walk the post body in order: every h1-h6 sets the current heading; a
     * genxfm anchor is a server chip belonging to that heading. Headings are
     * quality lines ("... 720p WEB-DL x264 [800MB/E]"), season badges, or noise
     * (comments/search) which never labels anything.
     */
    internal fun parseDetail(doc: Document): Scraped {
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
            .find(doc.body().html())?.groupValues?.get(1)
        val groups = ArrayList<Group>()
        var heading = ""
        var anchors = ArrayList<DlLink>()

        fun closeGroup() {
            if (anchors.isNotEmpty() && heading.isNotBlank()) {
                val (season, _) = LinkNaming.seasonEpisodeFrom(heading)
                groups += Group(heading, season, anchors.toList(), LinkNaming.isPack(heading))
            }
            anchors = ArrayList()
        }

        val root = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        for (el in root.getAllElements()) {
            when {
                el.tagName().matches(Regex("h[1-6]")) -> {
                    val t = el.text().trim()
                    if (t.isNotBlank() && t.length < 220) {
                        if (anchors.isNotEmpty()) closeGroup()
                        // Download-group-shaped headings take links; everything
                        // else (Info:, Screenshots:, comments, sidebar) turns
                        // labelling off until the next download-shaped heading.
                        heading = if (DOWNLOAD_HEADING.containsMatchIn(t)) t else ""
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href")
                    if (GENXFM_REGEX.containsMatchIn(href)) {
                        anchors += DlLink(href.trimEnd('/') + "/", LinkNaming.kindFromChip(el.text()), heading)
                    }
                }
            }
        }
        closeGroup()
        // Merge groups sharing a heading but split by noise? Group by heading to dedupe.
        val merged = groups.groupBy { it.heading }.map { (h, gs) ->
            gs.first().copy(links = gs.flatMap { it.links }.distinctBy { it.gatewayUrl })
        }.filter { it.links.isNotEmpty() }
        return Scraped(imdbId, merged)
    }

    /** Expand all gateways of a scrap into concrete PayloadLinks (parallel). */
    private suspend fun expandAll(scraped: Scraped, referer: String): List<Pair<Group, List<PayloadLink>>> =

        coroutineScope {
            val sem = Semaphore(6)
            scraped.groups.map { g ->
                async {
                    sem.acquire()
                    try {
                        val concrete = g.links.flatMap { dl ->
                            val chipKind = LinkNaming.kindFromChip(dl.chip)
                            val exp = NexdriveResolver.expand(dl.gatewayUrl, referer, commonHeaders)
                            when {
                                exp.links.isEmpty() -> listOf(
                                    PayloadLink(dl.gatewayUrl, chipKind.ifBlank { Servers.GATE }, g.heading, 0, true),
                                )
                                // Concrete servers of this chip. The chip's family
                                // wins over the host guess (a Batch/Zip chip is
                                // served by vcloud URLs but is an archive).
                                // idx = episode ordinal within the family.
                                else -> exp.links.map { c ->
                                    PayloadLink(c.url, chipKind.ifBlank { c.kind }, g.heading, c.idx, false)
                                }
                            }
                        }.distinctBy { it.url } // two chips may share one gateway
                        g to concrete
                    } finally { sem.release() }
                }
            }.awaitAll()
        }

    private suspend fun buildMovieResponse(
        url: String, title: String, year: Int?, poster: String?,
        meta: MetadataService.TmdbDetail?, imdbId: String?, imdbRating: Double?,
        plot: String?, language: String?, scraped: Scraped,
    ): LoadResponse {
        val groups = expandAll(scraped, url)
        val links = groups.flatMap { it.second }
        val payload = LinkPayload(url, links).toJson()
        return newMovieLoadResponse(title, url, TvType.Movie, payload) {
            posterUrl = poster ?: meta?.poster
            backgroundPosterUrl = meta?.backdrop
            this.year = year ?: meta?.year?.toIntOrNull()
            this.plot = meta?.overview ?: plot
            this.tags = meta?.genres ?: language?.let { listOf(it) }
            this.actors = meta?.cast
            imdbId?.let { addImdbId(it) }
            (meta?.rating ?: imdbRating)?.let { addScore(it.toString(), 10) }
        }
    }

    /**
     * How many episodes this set of concrete links reveals: the largest number
     * of ordered links sharing ONE server family (each family lists one file
     * per episode, in order). A quality gate with one G-Drive + one V-Cloud
     * link is 1 episode on 2 servers (max family size 1), while a 12-episode
     * G-Drive chip is 12. ZIP/pack links never inflate the count.
     */
    private fun List<PayloadLink>.familyEpisodeCount(): Int =
        filter { it.kind != Servers.ZIP }
            .groupBy { it.kind }
            .maxOfOrNull { (_, same) -> same.maxOf { it.idx } + 1 }
            ?: 0

    /**
     * Series: gateways already expanded by [expandAll]. Each group whose links
     * are per-episode contributes one file per episode (in page order); groups
     * with a single link or pack badges become "Season N (Pack)" episodes.
     */
    private suspend fun buildSeriesResponse(
        url: String, title: String, year: Int?, poster: String?,
        meta: MetadataService.TmdbDetail?, imdbId: String?, imdbRating: Double?,
        plot: String?, language: String?, scraped: Scraped,
    ): LoadResponse {
        val groups = expandAll(scraped, url)

        // Season → ordered quality groups.
        val bySeason = LinkedHashMap<Int, MutableList<Pair<Group, List<PayloadLink>>>>()
        for (ge in groups) {
            val s = ge.first.season ?: 1
            bySeason.getOrPut(s) { ArrayList() }.add(ge)
        }

        val episodes = ArrayList<Episode>()
        for ((season, seasonGroups) in bySeason) {
            // Per-episode structure: some server family exposes >1 ordered links
            // (one per episode). A quality gate with one G-Drive + one V-Cloud
            // link is a SINGLE file shown twice (two servers), NOT 2 episodes —
            // count within the largest family, ZIP links never count.
            val maxEps = seasonGroups
                .filter { (g, _) -> !g.pack }
                .maxOfOrNull { (_, c) -> c.familyEpisodeCount() } ?: 0

            if (maxEps > 1) {
                // TMDB episode names/thumbs if available.
                val epMeta = withTimeoutOrNull(6000L) {
                    MetadataService.episodesForSeason(imdbId, meta?.tmdbId, season)
                } ?: emptyMap()
                for (i in 0 until maxEps) {
                    // A single-link ZIP family is the season pack, not episode 1.
                    val links = seasonGroups.flatMap { (g, concrete) ->
                        if (g.pack) emptyList()
                        else concrete.filter { it.idx == i && it.kind != Servers.ZIP }
                            .map { it.copy(heading = g.heading) }
                    }
                    if (links.isEmpty()) continue
                    val epNum = i + 1
                    val m = epMeta[epNum]
                    episodes += newEpisode(LinkPayload(url, links.map { it.copy(season = season, episode = epNum) }).toJson()) {
                        this.season = season
                        this.episode = epNum
                        this.name = m?.name
                        this.description = m?.overview
                        m?.thumbnail?.let { this.posterUrl = it }
                    }
                }
            }
            // Season-level rows: whole-season pack chips, plus every group when
            // the season has no per-episode structure at all (crew-girl style:
            // one quality-labeled .zip per season).
            seasonGroups.forEach { (g, concrete) ->
                val packLinks = concrete.filter { it.kind == Servers.ZIP }
                val noEpisodes = maxEps <= 1
                val rows = when {
                    noEpisodes -> listOf(concrete to g.heading.ifBlank { "Season $season" })
                    packLinks.isNotEmpty() -> listOf(packLinks to "${g.heading.ifBlank { "Season $season" }} (Pack)")
                    else -> emptyList()
                }
                for ((rowLinks, rowName) in rows) {
                    if (rowLinks.isEmpty()) continue
                    val payload = LinkPayload(
                        url,
                        rowLinks.map { it.copy(heading = g.heading, season = season) },
                    ).toJson()
                    episodes += newEpisode(payload) {
                        this.season = season
                        this.name = rowName
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster ?: meta?.poster
            backgroundPosterUrl = meta?.backdrop
            this.year = year ?: meta?.year?.toIntOrNull()
            this.plot = meta?.overview ?: plot
            this.tags = meta?.genres ?: language?.let { listOf(it) }
            this.actors = meta?.cast
            imdbId?.let { addImdbId(it) }
            (meta?.rating ?: imdbRating)?.let { addScore(it.toString(), 10) }
        }
    }

    // ------------------------------------------------------------------
    // loadLinks — resolve embeds, live-fill, honest names
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = LinkPayload.fromJson(data) ?: return false
        if (payload.links.isEmpty()) return false
        val referer = payload.pageUrl.ifBlank { mainUrl }
        var emitted = false

        withTimeoutOrNull(LIVE_FILL_MS) {
            coroutineScope {
                val sem = Semaphore(6)
                payload.links.map { pl ->
                    async {
                        sem.acquire()
                        val link = try {
                            buildLink(pl, referer)
                        } finally { sem.release() }
                        if (link != null) {
                            emitted = true
                            callback(link)
                        }
                    }
                }.awaitAll()
            }
        }
        return emitted
    }

    /** Resolve one payload link into an ExtractorLink (or null if hopeless). */
    private suspend fun buildLink(pl: PayloadLink, referer: String): ExtractorLink? {
        val kind = pl.kind.ifBlank { Servers.kindOf(pl.url) }
        // Gateway stored unexpanded (rare — only when expansion failed at load
        // time): re-expand now and pick the episode index.
        val concrete: String
        val concreteKind: String
        if (pl.isGateway || GENXFM_REGEX.containsMatchIn(pl.url)) {
            val exp = NexdriveResolver.expand(NexdriveGateway.normalize(pl.url), referer, commonHeaders)
            if (exp.links.isEmpty()) {
                // Dead gateway: list the gateway page itself as browser download.
                return extractor(pl.url, Servers.GATE, pl, browserOnly = true)
            }
            val sameKind = if (pl.kind.isNotBlank()) exp.links.filter { it.kind == pl.kind } else exp.links
            val pool = sameKind.ifEmpty { exp.links }
            val idx = pl.idx.coerceIn(0, pool.size - 1)
            concrete = pool[idx].url
            concreteKind = pool[idx].kind
        } else {
            concrete = pl.url
            concreteKind = kind
        }

        return when (concreteKind) {
            Servers.GDRIVE -> {
                // embed page -> googleusercontent direct (1 fast hop), or self as browser link.
                val direct = NexdriveResolver.resolveEmbed(concrete, referer, commonHeaders)
                if (direct != null) extractor(direct, Servers.GDRIVE, pl, browserOnly = false)
                else extractor(concrete, Servers.GDRIVE, pl, browserOnly = true)
            }
            Servers.VCLOUD, Servers.ZIP -> extractor(concrete, concreteKind, pl, browserOnly = true)
            else -> {
                val direct = runCatching {
                    if (concrete.contains("fastdl")) NexdriveResolver.resolveEmbed(concrete, referer, commonHeaders) else null
                }.getOrNull()
                if (direct != null) extractor(direct, Servers.GDRIVE, pl, browserOnly = false)
                else extractor(concrete, concreteKind, pl, browserOnly = true)
            }
        }
    }

    private fun extractor(url: String, kind: String, pl: PayloadLink, browserOnly: Boolean): ExtractorLink {
        val raw = RawLink(url, kind, pl.heading, browserOnly, pl.season, pl.episode)
        return ExtractorLink(
            source = "Vegamovies",
            name = LinkNaming.displayName(raw),
            url = url,
            referer = if (kind == Servers.GDRIVE && !browserOnly) "https://fastdl.zip/" else pl.pageReferer(),
            // Unknown quality for download-only rows so auto-play never picks them.
            quality = if (browserOnly) 0 else getQualityFromName(pl.heading),
            headers = commonHeaders + mapOf("Referer" to (if (kind == Servers.GDRIVE && !browserOnly) "https://fastdl.zip/" else "https://new2.vegamovies.futbol/")),
            extractorData = null,
            // Browser-only gate pages keep VIDEO type + UNKNOWN quality so the
            // player's auto-selection never picks them; tapping offers the page
            // (a real, working download source in the system browser).
            type = ExtractorLinkType.VIDEO,
            audioTracks = emptyList(),
        )
    }

    private fun PayloadLink.pageReferer(): String =
        Regex("""^https?://[^/]+""").find(url)?.value ?: "https://new2.vegamovies.futbol/"
}

/** Normalize a genxfm URL to its cache key form. */
internal object NexdriveGateway {
    fun normalize(url: String): String = url.trimEnd('/') + "/"
}
