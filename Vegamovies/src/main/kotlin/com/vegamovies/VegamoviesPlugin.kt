package com.vegamovies

import android.content.Context
import android.util.Log
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

@CloudstreamPlugin
class Vegamovies : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VegamoviesProvider())
    }
}

internal data class DlLink(val gatewayUrl: String, val chip: String, val heading: String)

internal data class PayloadLink(
    val url: String,
    val kind: String = "",
    val heading: String = "",
    val idx: Int = 0,
    val isGateway: Boolean = true,
    val season: Int? = null,
    val episode: Int? = null,
)

internal data class LinkPayload(val pageUrl: String, val links: List<PayloadLink>, val imdbId: String? = null) {
    fun toJson(): String {
        val arr = JSONArray()
        links.forEach {
            arr.put(
                JSONObject().put("u", it.url).put("k", it.kind).put("h", it.heading)
                    .put("i", it.idx).put("g", it.isGateway)
                    .put("s", it.season ?: JSONObject.NULL).put("e", it.episode ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("page", pageUrl).put("links", arr)
            .put("imdb", imdbId ?: JSONObject.NULL).toString()
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
            return LinkPayload(o.optString("page"), links, o.optString("imdb").takeIf { it.startsWith("tt") })
        }
    }
}

class VegamoviesProvider : MainAPI() {

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        const val TAG = "Vegamovies"

        const val SEED_VEGA = "https://new1.vegamovies.lease"
        const val SEED_ROG = "https://rogmovies.one"

        const val BOOTSTRAP_VEGA = "https://new2.vegamovies.futbol"
        const val BOOTSTRAP_ROG = "https://new2.rogmovies.click"

        const val LANDING_PROBE = "/?re=vg&t=2"

        const val LIVE_FILL_MS = 180_000L

        const val SEARCH_MAX_RESULTS = 10

        val VEGA_SEEDS = listOf(SEED_VEGA, BOOTSTRAP_VEGA)
        val ROG_SEEDS = listOf(SEED_ROG, BOOTSTRAP_ROG)

        const val SEARCH_TIMEOUT_SEC = 12L

        const val INDEX_PROBE = "film"

        const val HEAL_COOLDOWN_MS = 60_000L

        val GENXFM_REGEX = Regex(
            """https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE,
        )

        val DOWNLOAD_HEADING = Regex(
            """(?i)(\d{3,4}p\b|4K|WEB[\s-]?DL|WEBRip|Blu\s?Ray|BD-?Rip|HD-?Rip|DVDRip|x26[45]|HEVC|AVC\b|\d+(?:\.\d+)?\s?(?:GB|MB)(?:/|\b)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|Complete|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""",
        )

        val NOISE_HEADING = Regex(
            """(?i)(leave a comment|search movies|recent updates|related|screenshot|official portal|vegamovies 20|comment )""",
        )
    }

    override var mainUrl = SEED_VEGA
    var bollywoodUrl = SEED_ROG

    override var name = "Vegamovies"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val commonHeaders = mapOf("User-Agent" to UA, "Accept-Language" to "en-US,en;q=0.9")
    private var cfKiller: CloudflareKiller? = null

    private val lastHeal = java.util.concurrent.atomic.AtomicLong(0L)

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

    internal fun isChallenge(doc: Document): Boolean {
        val t = doc.title()
        return t.contains("just a moment", true) || t.contains("checking your browser", true)
    }

    suspend fun fetchDoc(
        url: String,
        timeoutSeconds: Long = 12,
        headers: Map<String, String> = commonHeaders,
    ): Document? = fetchDocWithUrl(url, timeoutSeconds, headers)?.first

    internal suspend fun fetchDocWithUrl(
        url: String,
        timeoutSeconds: Long = 12,
        headers: Map<String, String> = commonHeaders,
    ): Pair<Document, String>? {
        try {
            val res = app.get(url, timeout = timeoutSeconds, headers = headers)
            if (!isChallenge(res.document)) return res.document to res.url
        } catch (e: Exception) {
        }
        return try {
            val killer = cfKiller ?: CloudflareKiller().also { cfKiller = it }
            val res = app.get(url, timeout = 20, headers = headers, interceptor = killer)
            if (isChallenge(res.document)) { cfKiller = null; null } else res.document to res.url
        } catch (e: Exception) {
            null
        }
    }

    internal fun isLanding(doc: Document): Boolean =
        doc.selectFirst("#stx") != null || doc.body().html().contains("qikly.pro/host")

    internal fun isChallengeBody(text: String): Boolean {
        val head = text.take(2500).lowercase()
        return head.contains("just a moment") || head.contains("checking your browser") ||
            head.contains("cf-chl-") || head.contains("challenge-platform")
    }

    private suspend fun liveDoc(seed: String): Pair<Document, String>? {
        val first = fetchDocWithUrl(seed, 10) ?: return null
        if (!isLanding(first.first)) return first
        val origin = DomainResolver.originOf(first.second) ?: return null
        return fetchDocWithUrl(origin + LANDING_PROBE, 12)?.takeUnless { isLanding(it.first) }
    }

    internal suspend fun searchIndexJson(base: String, query: String): String? {
        val url = "$base/ts-search.php?q=${URLEncoder.encode(query.trim(), "UTF-8")}&page=1"
        val headers = commonHeaders + mapOf("Referer" to "$base/")
        val direct = runCatching {
            app.get(url, timeout = SEARCH_TIMEOUT_SEC, headers = headers).text
        }.getOrNull()
        if (direct != null && direct.trimStart().startsWith("{")) return direct
        if (direct != null && !isChallengeBody(direct)) {
            Log.w(TAG, "index: $base answered non-JSON (${direct.take(60).replace("\n", " ")})")
            return null
        }
        val solved = runCatching {
            val killer = cfKiller ?: CloudflareKiller().also { cfKiller = it }
            app.get(url, timeout = SEARCH_TIMEOUT_SEC + 8, headers = headers, interceptor = killer).text
        }.getOrNull()
        return solved?.takeIf { it.trimStart().startsWith("{") }
    }

    private suspend fun verifiedBase(family: String, seeds: List<String>): String? {
        for (seed in seeds) {
            val live = liveDoc(seed) ?: continue
            val origin = DomainResolver.originOf(live.second) ?: continue
            for (candidate in listOf(origin) + DomainResolver.candidates(live.first, family)) {
                if (searchIndexJson(candidate, INDEX_PROBE) != null) return candidate
            }
        }
        return null
    }

    suspend fun refreshDomains() = coroutineScope {
        val a = async { verifiedBase("vegamovies", VEGA_SEEDS) }
        val b = async { verifiedBase("rogmovies", ROG_SEEDS) }
        val vega = a.await()
        val rog = b.await()
        vega?.let {
            if (it != mainUrl) Log.i(TAG, "heal: hollywood host $it (was $mainUrl)")
            mainUrl = it
        }
        rog?.let {
            if (it != bollywoodUrl) Log.i(TAG, "heal: bollywood host $it (was $bollywoodUrl)")
            bollywoodUrl = it
        }
        if (vega == null) Log.w(TAG, "heal: no hollywood host served the index")
        if (rog == null) Log.w(TAG, "heal: no bollywood host served the index")
        lastHeal.set(System.currentTimeMillis())
    }

    internal object DomainResolver {
        fun pick(doc: Document, family: String): String? = candidates(doc, family).firstOrNull()

        fun candidates(doc: Document, family: String): List<String> =
            doc.select("a[href]")
                .mapNotNull {
                    Regex("""https?://[a-z0-9.\-]*$family\.[a-z]{2,10}""", RegexOption.IGNORE_CASE)
                        .find(it.attr("abs:href"))?.value?.lowercase()
                }
                .filterNot { it.contains("apk") || it.endsWith(".cfd") }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }

        fun originOf(url: String): String? =
            Regex("""^https?://[^/]+""").find(url)?.value
    }


    override suspend fun search(query: String): List<SearchResponse>? = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val vega = async { siteSearchFuzzy(candidateBases("vegamovies"), query) }
        val rog = async { siteSearchFuzzy(candidateBases("rogmovies"), query) }
        val tmdb = async { MetadataService.search(query, 8) }

        var hits = (vega.await() + rog.await()).distinctBy { it.url }
        val tmdbItems = tmdb.await()
        if (hits.isEmpty() && System.currentTimeMillis() - lastHeal.get() > HEAL_COOLDOWN_MS) {
            refreshDomains()
            hits = (siteSearchFuzzy(candidateBases("vegamovies"), query) +
                siteSearchFuzzy(candidateBases("rogmovies"), query)).distinctBy { it.url }
        }
        if (hits.isEmpty()) {
            Log.w(TAG, "search: no hits for '$query' (hosts=$mainUrl,$bollywoodUrl)")
            return@coroutineScope emptyList()
        }
        Log.i(TAG, "search: '$query' -> ${hits.size} hits")

        val extra = withTimeoutOrNull(6000L) { expansionHits(tmdbItems, hits) }.orEmpty()
        if (extra.isNotEmpty()) hits = (hits + extra).distinctBy { it.url }

        val ranked = rankSearchResults(query, hits, tmdbItems).dedupeByName().take(SEARCH_MAX_RESULTS)
        backfillPosters(ranked, tmdbItems)
        ranked
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun candidateBases(family: String): List<String> {
        val primary = if (family == "vegamovies") mainUrl else bollywoodUrl
        val seeds = if (family == "vegamovies") VEGA_SEEDS else ROG_SEEDS
        return (listOf(primary) + seeds).distinct()
    }

    internal suspend fun siteSearchFuzzy(bases: List<String>, query: String): List<SearchResponse> {
        for (base in bases) {
            val hits = siteSearchFuzzy(base, query)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    internal suspend fun siteSearchFuzzy(baseUrl: String, query: String): List<SearchResponse> {
        val queries = buildList {
            add(query)
            addAll(queryVariants(query, max = 1))
        }.distinct()
        if (queries.size == 1) return siteSearch(baseUrl, query)
        return coroutineScope {
            queries.map { q -> async { siteSearch(baseUrl, q) } }
                .awaitAll().flatten().distinctBy { it.url }
        }
    }

    internal suspend fun siteSearch(baseUrl: String, query: String): List<SearchResponse> {
        val json = searchIndexJson(baseUrl, query) ?: return emptyList()
        return parseSearchHits(json, baseUrl)
    }

    private suspend fun expansionHits(
        tmdb: List<MetadataService.TmdbItem>,
        existing: List<SearchResponse>,
    ): List<SearchResponse> {
        if (tmdb.isEmpty()) return emptyList()
        val uncovered = tmdb.take(4).filter { item ->
            existing.none { bestTmdbMatch(listOf(item), it.name, yearOf(it)) != null }
        }
        if (uncovered.isEmpty()) return emptyList()
        val probes = uncovered.mapNotNull { item ->
            probeTokens(item.name).take(3).joinToString(" ").trim().ifBlank { null }
        }.distinct().take(3)
        if (probes.isEmpty()) return emptyList()
        val sem = Semaphore(2)
        return coroutineScope {
            probes.map { probe ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(4500L) {
                            siteSearchFuzzy(candidateBases("vegamovies"), probe) +
                                siteSearchFuzzy(candidateBases("rogmovies"), probe)
                        }.orEmpty()
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    internal fun yearOf(r: SearchResponse): Int? = when (r) {
        is MovieSearchResponse -> r.year
        is TvSeriesSearchResponse -> r.year
        else -> null
    }

    internal fun rankSearchResults(
        query: String,
        results: List<SearchResponse>,
        tmdb: List<MetadataService.TmdbItem>,
    ): List<SearchResponse> {
        val scored = results.map { r -> r to combinedScore(query, r.name, yearOf(r), tmdb) }
        return scored.sortedWith(
            compareByDescending<Pair<SearchResponse, Double>> { it.second }
                .thenByDescending { yearOf(it.first) ?: 0 },
        ).map { it.first }
    }

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

    internal fun cleanSearchTitle(raw: String): String {
        var t = raw.trim().removePrefix("Download").trim()
        val until = Regex("""(.*?\(\d{4}[^)]*\))""").find(t)
        if (until != null) {
            return until.groupValues[1].trim().trimEnd(' ', '-', '|', ':', ',')
        }
        t = t.replace(
            Regex("""(?i)\)\s*(\d{3,4}p[\s\S]*|WEB[\s-]?DL.*|BluRay.*|HDTS.*|HDTV.*|4K.*|Dual Audio.*|Hindi.*|S\d{2}E\d{2,}.*)$"""),
            "",
        ).trim()
        return t.trim(' ', '-', '|', ':', ',').ifBlank { raw.trim().take(80) }
    }

    internal fun isSeries(permalink: String, categories: List<String>, rawTitle: String): Boolean {
        val hay = (permalink + " " + categories.joinToString(" ") + " " + rawTitle).lowercase()
        return Regex(
            """season|series|-series|drama|anime|s\d{1,2}e\d{1,3}|web-?show|tv-show|\beps\b|multimovies\.|complete""",
        ).containsMatchIn(hay) && !Regex("""full movie|-movie\b""").containsMatchIn(hay)
    }

    private suspend fun backfillPosters(
        results: List<SearchResponse>,
        tmdb: List<MetadataService.TmdbItem> = emptyList(),
    ) {
        val need = results.filter { it.posterUrl.isNullOrBlank() }.take(6)
        if (need.isEmpty()) return
        val sem = Semaphore(3)
        coroutineScope {
            need.map { r ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(2500L) {
                            val known = bestTmdbMatch(tmdb, r.name, yearOf(r))?.poster
                                ?: MetadataService.search(r.name).firstOrNull()?.poster
                            if (!known.isNullOrBlank()) r.posterUrl = known
                        }
                    } finally { sem.release() }
                }
            }.awaitAll()
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val target = pageUrl(mainUrl, request.data, page)
        var doc = fetchDoc(target, timeoutSeconds = 14)
        if (doc == null) {
            refreshDomains()
            doc = fetchDoc(pageUrl(mainUrl, request.data, page), timeoutSeconds = 14)
                ?: fetchDoc(target, timeoutSeconds = 14)
        }
        val items = doc?.let { parseListing(it) } ?: emptyList()
        backfillPosters(items)
        return newHomePageResponse(request.name, items)
    }

    private fun pageUrl(base: String, data: String, page: Int): String {
        val path = Regex("""^https?://[^/]+(/.*)?$""").find(data)?.groupValues?.get(1).orEmpty()
        val full = base.trimEnd('/') + path
        return if (page > 1) "${full.trimEnd('/')}/page/$page/" else full
    }

    private fun rebase(url: String, base: String): String {
        val path = Regex("""^https?://[^/]+(/.*)?$""").find(url)?.groupValues?.get(1) ?: return url
        return base.trimEnd('/') + path
    }

    internal fun parseListing(doc: Document): List<SearchResponse> {
        return doc.select("div.poster-card").mapNotNull { card ->
            cardToSearch(card)
        }.ifEmpty {
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


    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDoc(url) ?: run {
            refreshDomains()
            fetchDoc(url) ?: fetchDoc(rebase(url, mainUrl))
                ?: throw ErrorLoadingException("Could not load $url")
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
        val scrapedImdb = scraped.imdbId
        val imdbRating = Regex("""(?i)IMDb Rating:?\s*[-–]?\s*([\d.]+)""").find(body)
            ?.groupValues?.get(1)?.toDoubleOrNull()
        val plot = Regex("""(?i)SYNOPSIS|\bPLOT:|\bPlot\b""").find(body)?.let { m ->
            val after = body.substring(m.range.last + 1)
            val cut = after.indexOf("Screenshots", ignoreCase = true)
            (if (cut >= 0) after.substring(0, cut) else after)
                .trim(':', '-', ' ', '\n').take(1200).takeIf { it.isNotBlank() }
        }
        val language = Regex("""(?im)^\s*Language:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()

        val meta = withTimeoutOrNull(6000L) { MetadataService.enrich(title, year?.toString(), scrapedImdb) }
        val imdbId = scrapedImdb ?: meta?.imdbId

        val seasonsFor = scraped.groups.mapNotNull { it.season }.distinct()
        val isSeriesTitle = isSeries(url, emptyList(), h1)

        return if (isSeriesTitle || (seasonsFor.isNotEmpty() && scraped.groups.size > 1)) {
            buildSeriesResponse(url, title, year, poster, meta, imdbId, imdbRating, plot, language, scraped)
        } else {
            buildMovieResponse(url, title, year, poster, meta, imdbId, imdbRating, plot, language, scraped)
        }
    }

    internal data class Scraped(val imdbId: String?, val groups: List<Group>)
    internal data class Group(
        val heading: String,
        val season: Int?,
        val episode: Int?,
        val links: List<DlLink>,
        val pack: Boolean,
    )

    internal fun parseDetail(doc: Document): Scraped {
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""", RegexOption.IGNORE_CASE)
            .find(doc.body().html())?.groupValues?.get(1)
            ?: Regex("""\[imdb[^]]*](tt\d+)\[/imdb]""", RegexOption.IGNORE_CASE)
                .find(doc.body().html())?.groupValues?.get(1)
        val groups = ArrayList<Group>()
        var heading = ""
        var anchors = ArrayList<DlLink>()

        fun closeGroup() {
            if (anchors.isNotEmpty() && heading.isNotBlank()) {
                val (season, episode) = LinkNaming.seasonEpisodeFrom(heading)
                val pack = LinkNaming.isPack(heading) && episode == null
                groups += Group(heading, season, episode, anchors.toList(), pack)
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
        val merged = groups.groupBy { it.heading to (it.season ?: 1) to it.episode }
            .map { (_, gs) ->
                gs.first().copy(links = gs.flatMap { it.links }.distinctBy { it.gatewayUrl })
            }
            .filter { it.links.isNotEmpty() }
        return Scraped(imdbId, merged)
    }

    private suspend fun expandAll(scraped: Scraped, referer: String): List<Pair<Group, List<PayloadLink>>> =

        coroutineScope {
            val sem = Semaphore(12)
            scraped.groups.map { g ->
                async {
                    sem.acquire()
                    try {
                        val concrete = g.links.flatMap { dl ->
                            val chipKind = LinkNaming.kindFromChip(dl.chip)
                            val exp = NexdriveResolver.expand(dl.gatewayUrl, referer, commonHeaders)
                            when {
                                exp.links.isEmpty() -> listOf(
                                    PayloadLink(
                                        dl.gatewayUrl,
                                        chipKind.ifBlank { Servers.GATE },
                                        g.heading,
                                        0,
                                        true,
                                        g.season,
                                        g.episode,
                                    ),
                                )
                                else -> exp.links.map { c ->
                                    PayloadLink(
                                        c.url,
                                        if (chipKind == Servers.ZIP) Servers.ZIP else c.kind,
                                        g.heading,
                                        c.idx,
                                        false,
                                        g.season,
                                        g.episode,
                                    )
                                }
                            }
                        }.distinctBy { it.url }
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
        val payload = LinkPayload(url, links, imdbId).toJson()
        return newMovieLoadResponse(title, url, TvType.Movie, payload) {
            posterUrl = poster ?: meta?.poster
            backgroundPosterUrl = meta?.backdrop
            this.year = year ?: meta?.year?.toIntOrNull()
            applyMetadata(this, meta, imdbRating, plot, language)
            this.actors = meta?.cast
            imdbId?.let { addImdbId(it) }
        }
    }

    private fun applyMetadata(
        resp: LoadResponse, meta: MetadataService.TmdbDetail?, imdbRating: Double?,
        plot: String?, language: String?,
    ) {
        val pagePlot = plot?.takeIf { it.length >= 40 }
        resp.plot = pagePlot ?: meta?.overview
        val genres = LinkedHashSet<String>()
        language?.takeIf { it.isNotBlank() }?.let { genres.add(it.trim()) }
        meta?.genres?.forEach { genres.add(it) }
        if (genres.isNotEmpty()) resp.tags = genres.toList()
        (imdbRating ?: meta?.rating)?.let { resp.addScore(it.toString(), 10) }
    }

    private fun List<PayloadLink>.familyEpisodeCount(): Int =
        filter { it.kind != Servers.ZIP }
            .groupBy { it.kind }
            .maxOfOrNull { (_, same) -> same.maxOf { it.idx } + 1 }
            ?: 0

    private suspend fun buildSeriesResponse(
        url: String, title: String, year: Int?, poster: String?,
        meta: MetadataService.TmdbDetail?, imdbId: String?, imdbRating: Double?,
        plot: String?, language: String?, scraped: Scraped,
    ): LoadResponse {
        val groups = expandAll(scraped, url)
            .map { (g, concrete) -> g to concrete.filter { it.kind != Servers.ZIP } }

        val bySeason = LinkedHashMap<Int, MutableList<Pair<Group, List<PayloadLink>>>>()
        for (ge in groups) {
            val s = ge.first.season ?: 1
            bySeason.getOrPut(s) { ArrayList() }.add(ge)
        }

        val episodes = ArrayList<Episode>()
        for ((season, seasonGroups) in bySeason) {
            val explicit = seasonGroups.filter { it.first.episode != null }
            val explicitByEp = explicit.groupBy { it.first.episode!! }
            val hasExplicit = explicitByEp.isNotEmpty()
            val maxEps = if (hasExplicit) explicitByEp.keys.max()
            else seasonGroups.filter { (g, _) -> !g.pack }
                .maxOfOrNull { (_, c) -> c.familyEpisodeCount() } ?: 0

            val epMeta = if (maxEps > 1) withTimeoutOrNull(9000L) {
                MetadataService.episodesForSeason(imdbId, meta?.tmdbId, season)
            } ?: emptyMap() else emptyMap()

            if (!hasExplicit && maxEps > 1) {
                for (i in 0 until maxEps) {
                    val links = seasonGroups.flatMap { (g, concrete) ->
                        if (g.pack) emptyList()
                        else concrete.filter { it.idx == i && it.kind != Servers.ZIP }
                            .map { it.copy(heading = g.heading) }
                    }
                    if (links.isEmpty()) continue
                    val epNum = i + 1
                    val m = epMeta[epNum]
                    episodes += newEpisode(LinkPayload(url, links.map { it.copy(season = season, episode = epNum) }, imdbId).toJson()) {
                        this.season = season
                        this.episode = epNum
                        this.name = m?.name ?: "Episode $epNum"
                        this.description = m?.overview
                        m?.runTime?.let { this.runTime = it * 60 }
                        m?.thumbnail?.let { this.posterUrl = it }
                        m?.aired?.let { this.addDate(it) }
                    }
                }
            } else {
                for (epNum in 1..maxEps) {
                    val rows = explicitByEp[epNum]
                    val links = rows?.flatMap { (g, concrete) ->
                        concrete.map { it.copy(heading = g.heading, season = season, episode = epNum) }
                    } ?: emptyList()
                    if (links.isEmpty()) continue
                    val m = epMeta[epNum]
                    episodes += newEpisode(LinkPayload(url, links, imdbId).toJson()) {
                        this.season = season
                        this.episode = epNum
                        this.name = m?.name ?: "Episode $epNum"
                        this.description = m?.overview
                        m?.runTime?.let { this.runTime = it * 60 }
                        m?.thumbnail?.let { this.posterUrl = it }
                        m?.aired?.let { this.addDate(it) }
                    }
                }
            }
            val seasonLevel = seasonGroups.filter { it.first.episode == null }
            if (!hasExplicit && maxEps <= 1) {
                seasonLevel.forEach { (g, concrete) ->
                    if (concrete.isEmpty()) return@forEach
                    val payload = LinkPayload(
                        url,
                        concrete.map { it.copy(heading = g.heading, season = season) },
                        imdbId,
                    ).toJson()
                    episodes += newEpisode(payload) {
                        this.season = season
                        this.name = g.heading.ifBlank { "Season $season" }
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = meta?.poster ?: poster
            backgroundPosterUrl = meta?.backdrop
            this.year = year ?: meta?.year?.toIntOrNull()
            applyMetadata(this, meta, imdbRating, plot, language)
            this.actors = meta?.cast
            imdbId?.let { addImdbId(it) }
        }
    }


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

        val subsJob = coroutineScope {
            async {
                val season = payload.links.firstNotNullOfOrNull { it.season }
                val episode = payload.links.firstNotNullOfOrNull { it.episode }
                runCatching {
                    VegaSubtitles.fetchAndDeliver(payload.imdbId, season, episode) {
                        runCatching { subtitleCallback(it) }
                    }
                }
            }
        }

        val waves = payload.links
            .groupBy {
                when (it.kind) {
                    Servers.VCLOUD -> 0
                    Servers.GDRIVE -> 1
                    Servers.ZIP -> 3
                    else -> 2
                }
            }.toSortedMap().values
            .map { wave -> wave.sortedByDescending { LinkNaming.qualityInt(it.heading) } }

        withTimeoutOrNull(LIVE_FILL_MS) {
            coroutineScope {
                val sem = Semaphore(12)
                waves.forEach { wave ->
                    wave.map { pl ->
                        async {
                            sem.acquire()
                            val links = try {
                                runCatching { buildLinks(pl, referer) }.getOrElse {
                                    delay(800)
                                    runCatching { buildLinks(pl, referer) }.getOrDefault(emptyList())
                                }
                            } finally { sem.release() }
                            links.forEach {
                                emitted = true
                                callback(it)
                            }
                        }
                    }.awaitAll()
                }
            }
        }
        runCatching { subsJob.await() }
        return emitted
    }

    private suspend fun buildLinks(pl: PayloadLink, referer: String): List<ExtractorLink> {
        val kind = pl.kind.ifBlank { Servers.kindOf(pl.url) }
        val concrete: String
        val concreteKind: String
        if (pl.isGateway || GENXFM_REGEX.containsMatchIn(pl.url)) {
            val exp = NexdriveResolver.expand(NexdriveGateway.normalize(pl.url), referer, commonHeaders)
            if (exp.links.isEmpty()) {
                return listOf(extractor(pl.url, Servers.GATE, pl, browserOnly = true))
            }
            if (pl.kind.isNotBlank() && pl.kind != Servers.GATE) {
                val pool = exp.links.filter { it.kind == pl.kind }
                if (pool.isEmpty()) return emptyList()
                val idx = pl.idx.coerceIn(0, pool.size - 1)
                concrete = pool[idx].url
                concreteKind = pool[idx].kind
            } else {
                val idx = pl.idx.coerceIn(0, exp.links.size - 1)
                concrete = exp.links[idx].url
                concreteKind = exp.links[idx].kind
            }
        } else {
            concrete = pl.url
            concreteKind = kind
        }

        return when (concreteKind) {
            Servers.GDRIVE -> {
                val direct = NexdriveResolver.resolveEmbed(concrete, referer, commonHeaders)
                listOf(extractor(direct ?: concrete, Servers.GDRIVE, pl, browserOnly = true))
            }
            Servers.VCLOUD -> {
                val fs = runCatching { VcloudResolver.resolve(concrete, referer, commonHeaders) }
                    .getOrDefault(emptyList())
                if (fs.isEmpty()) listOf(extractor(concrete, Servers.VCLOUD, pl, browserOnly = true))
                else fs.map { extractor(it.url, Servers.VCLOUD, pl, browserOnly = false, serverTag = it.tag) }
            }
            Servers.ZIP -> listOf(extractor(concrete, concreteKind, pl, browserOnly = true))
            else -> {
                val v = runCatching {
                    if (concrete.contains("vcloud")) VcloudResolver.resolve(concrete, referer, commonHeaders) else emptyList()
                }.getOrDefault(emptyList())
                if (v.isNotEmpty()) {
                    return v.map { extractor(it.url, Servers.VCLOUD, pl, browserOnly = false, serverTag = it.tag) }
                }
                val direct = runCatching {
                    if (concrete.contains("fastdl")) NexdriveResolver.resolveEmbed(concrete, referer, commonHeaders) else null
                }.getOrNull()
                listOf(extractor(direct ?: concrete, concreteKind, pl, browserOnly = true))
            }
        }
    }

    private fun extractor(
        url: String, kind: String, pl: PayloadLink, browserOnly: Boolean, serverTag: String = "",
    ): ExtractorLink {
        val raw = RawLink(url, kind, pl.heading, browserOnly, pl.season, pl.episode, serverTag)
        return ExtractorLink(
            source = "Vegamovies",
            name = LinkNaming.displayName(raw),
            url = url,
            referer = if (kind == Servers.GDRIVE && !browserOnly) "https://fastdl.zip/" else pl.pageReferer(),
            quality = if (browserOnly) 0 else LinkNaming.qualityInt(pl.heading),
            headers = commonHeaders + mapOf("Referer" to (if (kind == Servers.GDRIVE && !browserOnly) "https://fastdl.zip/" else "$mainUrl/")),
            extractorData = null,
            type = ExtractorLinkType.VIDEO,
            audioTracks = emptyList(),
        )
    }

    private fun PayloadLink.pageReferer(): String =
        Regex("""^https?://[^/]+""").find(url)?.value ?: "$mainUrl/"
}

internal object NexdriveGateway {
    fun normalize(url: String): String = url.trimEnd('/') + "/"
}
