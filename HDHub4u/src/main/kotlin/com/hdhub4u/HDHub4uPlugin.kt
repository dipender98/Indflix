package com.hdhub4u

/**
 * FILE: HDHub4uPlugin.kt — plugin entry + the HDHub4u MainAPI provider.
 *
 * 100% site-sourced (user spec Sept 2026): search, posters, catalogs,
 * metadata and stream links ALL come from the HDHub4u site itself.
 *  - search  = the site's own typesense index (the same one its search.html
 *              page queries; `?s=` is dead on this theme and echoes the home feed)
 *  - catalogs = the site's category pages (`li.thumb` card grid)
 *  - load    = the site's post page (title/poster/categories/imdb-id as shown)
 *  - loadLinks = the post's own download/watch buttons resolved server-side:
 *              hubcdn "Instant" -> direct .mkv (R2),
 *              hdstream4u "WATCH" -> HLS master (site-hosted VTT captions too),
 *              hubdrive "Drive" -> Google Drive file (may be login-gated; the
 *              site itself then says "Try HubCloud Server" — we just skip),
 *              ?id=<b64> redirectors -> decoded when they yield a URL.
 * Emission is arrival order (repo-wide user spec); sub-720p FIXED files are
 * dropped ([passesQualityFloor]).
 */

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "HDHub4u"

/** Per-link resolve budget (matches Multimovies' SOURCE_TIMEOUT_MS). */
private const val SOURCE_TIMEOUT_MS = 15_000L

@CloudstreamPlugin
class HDHub4u : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDHub4uProvider())
    }
}

class HDHub4uProvider : MainAPI() {

    override var mainUrl = "https://hdhub4u.ag"
    override var name = "HDHub4u"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /** Site categories (slugs verified live 2026-09-11); "latest" = home feed. */
    override val mainPage
        get() = mainPageOf(
            "latest" to "Latest Releases",
            "bollywood-movies" to "Bollywood Movies",
            "hollywood-movies" to "Hollywood Movies",
            "dual-audio" to "Dual Audio",
            "hindi-dubbed" to "Hindi Dubbed",
            "unofficial-south-hindi-dubs" to "South Hindi Dubs",
            "amazon-prime-video" to "Amazon Prime Video",
            "netflix" to "Netflix",
            "disney" to "Disney+",
            "hbo-max" to "HBO Max",
            "jiohotstar" to "JioHotstar",
        )

    // ── search ─────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = DomainResolver.ensure()
        val hits = withDomainRetry(retryOnNull = false) {
            withTimeoutOrNull(10_000L) { searchSite(query) }
        }.orEmpty()
        val out = hits.mapNotNull { it.toSearchResponse() }
        Log.i(TAG, "search '$query' -> ${out.size} hits")
        return out.ifEmpty { null }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun TypesenseHit.toSearchResponse(): SearchResponse? {
        if (permalink.isBlank() || title.isBlank()) return null
        val url = liveUrl(permalink)
        val cleaned = cleanTitle(title)
        val year = parseYear(title)
        return if (isSeriesTitle(title)) {
            newTvSeriesSearchResponse(cleaned, url, TvType.TvSeries) {
                this.posterUrl = thumbnail.takeIf { it.startsWith("http") }
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleaned, url, TvType.Movie) {
                this.posterUrl = thumbnail.takeIf { it.startsWith("http") }
                this.year = year
            }
        }
    }

    // ── main page (site category grids) ────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        mainUrl = DomainResolver.ensure()
        val slug = request.data
        val pageUrl = when {
            slug == "latest" && page <= 1 -> "$mainUrl/"
            slug == "latest" -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/category/$slug/"
            else -> "$mainUrl/category/$slug/page/$page/"
        }
        val doc = withDomainRetry(retryOnNull = true) {
            fetchDoc(pageUrl, timeoutSeconds = 12)
        } ?: return null
        val cards = parseCards(doc.outerHtml())
        val items = cards.mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) return null
        return newHomePageResponse(request.name, items)
    }

    private fun Card.toSearchResponse(): SearchResponse? {
        if (href.isBlank() || title.isBlank()) return null
        val url = liveUrl(href)
        val cleaned = cleanTitle(title)
        val year = parseYear(title)
        return if (isSeriesTitle(title)) {
            newTvSeriesSearchResponse(cleaned, url, TvType.TvSeries) {
                this.posterUrl = poster.takeIf { it.startsWith("http") }
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleaned, url, TvType.Movie) {
                this.posterUrl = poster.takeIf { it.startsWith("http") }
                this.year = year
            }
        }
    }

    // ── load (post page) ───────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = DomainResolver.ensure()
        val postUrl = liveUrl(url)
        val doc = withDomainRetry(retryOnNull = true) {
            fetchDoc(postUrl, timeoutSeconds = 15, required = true)
        } ?: throw ErrorLoadingException("Failed to load $postUrl")
        val html = doc.outerHtml()

        val rawTitle = doc.selectFirst("h1.page-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val cleaned = cleanTitle(rawTitle)
        val poster = doc.selectFirst("main.page-body img")?.attr("abs:src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = doc.select("div.page-meta a").map { it.text().trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { doc.select("a[rel='category tag']").map { it.text().trim() } }
        val imdbId = Regex("""imdb\.com/title/(tt\d{6,10})""").find(html)?.groupValues?.get(1)
        val year = parseYear(rawTitle) ?: parseYear(
            Regex(""""datePublished"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        )
        val isSeries = isSeriesTitle(rawTitle)

        val links = parsePostLinks(html)
        Log.i(TAG, "load '$cleaned' links=${links.size} series=$isSeries")

        if (!isSeries) {
            return newMovieLoadResponse(cleaned, postUrl, TvType.Movie, postUrl) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                imdbId?.let { addImdbId(it) }
            }
        }

        // Series: one post holds the whole season; links carry an EPISODE n
        // heading group. Posts whose links never group into episodes are a
        // "full pack" — one pseudo-episode holding every link.
        val eps = links.map { it.episode }.filter { it >= 1 }.distinct().sorted()
        val season = Regex("""Season\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val episodes: List<Episode> = if (eps.isEmpty()) {
            listOf(newEpisode(postUrl) {
                this.name = "Full Pack"
                this.season = season
                this.episode = 1
                this.posterUrl = poster
            })
        } else {
            eps.map { n ->
                newEpisode("$postUrl#ep=$n") {
                    this.name = "Episode $n"
                    this.season = season
                    this.episode = n
                    this.posterUrl = poster
                }
            }
        }

        return newTvSeriesLoadResponse(cleaned, postUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            imdbId?.let { addImdbId(it) }
        }
    }

    // ── load links ─────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        mainUrl = DomainResolver.ensure()
        val epMatch = Regex("""#ep=(\d+)$""").find(data)
        val postUrl = liveUrl(data.substringBefore("#ep="))
        val wantEp = epMatch?.groupValues?.get(1)?.toIntOrNull()

        val doc = withDomainRetry(retryOnNull = true) {
            fetchDoc(postUrl, timeoutSeconds = 15, required = true)
        } ?: return false
        var links = parsePostLinks(doc.outerHtml())
        if (wantEp != null) {
            val scoped = links.filter { it.episode == wantEp }
            if (scoped.isNotEmpty()) links = scoped
        }
        if (links.isEmpty()) {
            Log.w(TAG, "loadLinks: no links on $postUrl (ep=$wantEp)")
            return false
        }

        var emitted = 0
        coroutineScope {
            links.map { link ->
                launch {
                    val sources = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        runCatching { resolveLink(link) }.getOrNull()
                    }.orEmpty()
                    for (src in sources) {
                        if (src.url.isBlank()) continue
                        if (!passesQualityFloor(src.isAdaptive, src.quality)) {
                            Log.i(TAG, "drop sub-720p fixed: ${src.name}")
                            continue
                        }
                        val referer = src.headers["Referer"] ?: ""
                        callback(ExtractorLink(
                            source = "HDHub4u",
                            name = src.name,
                            url = src.url,
                            referer = referer,
                            quality = src.quality,
                            headers = src.headers - "Referer",
                            type = if (src.isAdaptive) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        ))
                        emitted++
                        for ((lang, subUrl) in src.subtitles) {
                            runCatching { subtitleCallback(SubtitleFile(lang, subUrl)) }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "loadLinks $postUrl (ep=$wantEp): $emitted links from ${links.size} buttons")
        return emitted > 0
    }
}
