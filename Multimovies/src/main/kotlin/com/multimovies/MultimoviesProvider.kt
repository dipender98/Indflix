package com.multimovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


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
        const val SOURCE_TIMEOUT_MS = 30_000L

        /**
         * Server names as they appear on the Multimovies "Video Sources" list,
         * ordered from most reliable/fast to least. Servers not listed here are
         * still pulled, but with the lowest priority.
         */
        val SOURCE_PRIORITY: List<String> = listOf(
            "GDMIRROR - Recommended",
            "GDMIRROR",
            "Cineverse",
            "Nxsha",
            "GDFlix",
            "HubCloud",
            "FastDL",
            "StreamWish",
            "screenscape.me",
            "VidZee",
            "VidZee v2",
            "vixsrc.to",
            "CinemaOS",
            "vidlink.pro",
            "Multimovies",
        )
    }

    private fun priorityOf(serverName: String): Int {
        val idx = SOURCE_PRIORITY.indexOfFirst { serverName.contains(it, ignoreCase = true) }
        return if (idx == -1) SOURCE_PRIORITY.size else idx
    }

    /**
     * Fetch [url] and parse it to a Jsoup Document, solving the site's Cloudflare
     * managed challenge via [CloudflareKiller].
     *
     * The challenge is solved in a WebView and can take a while, so we use a long
     * timeout and retry once with a FRESH solver if the first attempt returns the
     * "Just a moment..." interstitial (a 200 response that is not the real page).
     *
     * On hard failure we throw [ErrorLoadingException] so the detail page shows the
     * error ONCE. Returning null instead makes CloudStream's LoadFragment retry
     * load() forever (the "keep refreshing" loop).
     */
    private suspend fun solveDocument(url: String, timeoutSeconds: Int = 60): Document {
        val solvers = listOf(
            { getCfKiller() },
            { cfKiller = CloudflareKiller().also { cfKiller = it } },
        )
        var lastErr: Exception? = null
        for (makeSolver in solvers) {
            try {
                val doc = app.get(
                    url,
                    timeout = timeoutSeconds,
                    headers = commonHeaders,
                    interceptor = makeSolver(),
                ).document
                val bodyText = doc.body()?.text() ?: ""
                val isChallenge = bodyText.contains("just a moment", ignoreCase = true)
                    || bodyText.contains("cf-mitigated", ignoreCase = true)
                    || bodyText.contains("verify you are human", ignoreCase = true)
                    || bodyText.contains("checking your browser", ignoreCase = true)
                if (isChallenge) {
                    lastErr = ErrorLoadingException("Cloudflare challenge not solved")
                    cfKiller = null
                    continue
                }
                return doc
            } catch (e: Exception) {
                lastErr = e
                cfKiller = null
            }
        }
        throw ErrorLoadingException(lastErr?.localizedMessage ?: "Failed to load $url")
    }


    private fun upgradePosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fixed = if (url.startsWith("//")) "https:$url" else url
        // Strip Dooplay "-WxH" thumbnail size suffixes to get the full-resolution image.
        return fixed.replace(Regex("""-\d+x\d+(?=\.[a-zA-Z]+$)"""), "")
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val doc = try {
            app.get(searchUrl, timeout = 20, headers = commonHeaders, interceptor = getCfKiller()).document
        } catch (e: Exception) {
            return null
        }

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
        return results.takeIf { it.isNotEmpty() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a[href], div.data a h2, div.poster a") ?: return null
        val href = a.attr("href").takeIf { it.contains(mainUrl) } ?: return null
        val title = selectFirst("img")?.attr("alt")
            ?: a.selectFirst("h2, div.data h3 a, .title")?.text()
            ?: a.text()
            ?.trim()
            ?: return null
        val poster = upgradePosterUrl(
            selectFirst("img")?.attr("src")
                ?: selectFirst("img")?.attr("data-src")
        )
        val isMovie = href.contains("/movies/")
        val isSeries = href.contains("/tvshows/") || href.contains("/seasons/")
        val tvType = when {
            isSeries -> TvType.TvSeries
            isMovie -> TvType.Movie
            else -> TvType.TvSeries
        }
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, tvType) { this.posterUrl = poster }
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
        val doc = try {
            app.get(url, timeout = 20, headers = commonHeaders, interceptor = getCfKiller()).document
        } catch (e: Exception) {
            return null
        }
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

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.poster img, img.wp-post-image")?.attr("src")

        val year = doc.selectFirst("span.date, .year, .extra span")?.text()
            ?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val plot = doc.selectFirst("div.wp-content, div.description, .wp-content p")?.text()
            ?.replace("Overview:", "")?.trim()

        val tags = doc.select("div.sgeneros a, .genre a, .sgeneros a").mapNotNull { it.text() }

        val score = doc.selectFirst("span.dt_rating_vgs, .imdb, .rating span")?.text()
            ?.removePrefix("IMDb:")?.trim()

        val isMovie = url.contains("/movies/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score?.let { Score.from10(it) }
                this.tags = tags
            }
        } else {
            // TV / Seasons: collect all episodes from season + episode archive pages.
            val episodes = arrayListOf<Episode>()
            val seasonLinks = doc.select("div.se-c div.se-q a[href], ul.episodios li a[href], .seasons a[href]")
                .mapNotNull { it.attr("href").takeIf { h -> h.contains(mainUrl) } }
                .distinct()

            val pages = if (seasonLinks.isEmpty()) listOf(url) else seasonLinks
            coroutineScope {
                pages.map { seasonUrl ->
                    async {
                        val sDoc = try {
                            solveDocument(seasonUrl)
                        } catch (e: Exception) {
                            return@async
                        }
                        sDoc.select("ul.episodios li, div.eps div.ep, .episodios li").forEachIndexed { i, ep ->
                            val epLink = ep.selectFirst("a[href]")?.attr("href")?.takeIf { it.contains(mainUrl) }
                                ?: return@forEachIndexed
                            val epNum = Regex("(?i)(\\d+)x(\\d+)").find(epLink)?.groupValues?.getOrNull(2)?.toIntOrNull()
                                ?: Regex("(\\d+)").find(epLink)?.value?.toIntOrNull() ?: (i + 1)
                            val seasonNum = Regex("(?i)(\\d+)x(\\d+)").find(epLink)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                            val epTitle = ep.selectFirst(".episodiotitle a, .title, a")?.text()?.trim()
                            episodes.add(
                                newEpisode(epLink) {
                                    this.name = epTitle
                                    this.episode = epNum
                                    this.season = seasonNum
                                }
                            )
                        }
                    }
                }.awaitAll()
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score?.let { Score.from10(it) }
                this.tags = tags
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

        // Resolve every source's embed via the dooplayer admin-ajax endpoint,
        // then hand each embed to CloudStream's extractor registry.
        val servers = options.mapNotNull { (name, triple) ->
            val (post, nume, type) = triple
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
                    timeout = 20,
                    interceptor = getCfKiller(),
                ).text
            }.getOrNull() ?: return@mapNotNull null

            // The ajax response is JSON like {"embed_url":"...","type":"iframe"}.
            // embed_url is EITHER a direct host URL OR an HTML snippet containing
            // an <iframe>. Extract the JSON string value robustly (handling
            // escaped quotes) and pull the iframe src when present.
            val rawEmbed = Regex("\"embed_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(resp)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?: return@mapNotNull null

            val embed = if (rawEmbed.contains("<iframe", ignoreCase = true)) {
                Jsoup.parse(rawEmbed).selectFirst("iframe")?.attr("src")
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            } else {
                rawEmbed.replace("\\/", "/").trim()
            }
            name to embed
        }

        if (servers.isEmpty()) return false

        val sources = servers.map { (name, href) ->
            MultiSourcePuller.Source(name = name, url = href, referer = data)
        }

        // Parallel pull, per-source 30s timeout, priority-ordered results.
        val links = runCatching {
            MultiSourcePuller.pull(
                sources = sources,
                timeoutMs = SOURCE_TIMEOUT_MS,
                priorityOf = { priorityOf(it) },
                onSubtitle = subtitleCallback,
            )
        }.getOrElse { emptyList() }

        links.forEach { runCatching { callback(it) } }
        return links.isNotEmpty()
    }
}