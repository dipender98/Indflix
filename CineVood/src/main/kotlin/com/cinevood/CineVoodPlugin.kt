package com.cinevood

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

@CloudstreamPlugin
class CineVoodPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineVoodProvider())
    }
}

class CineVoodProvider : MainAPI() {

    override var name = "CineVood"
    override var mainUrl = DEFAULT_BASE
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val instantLinkLoading = true // play the first link while the rest arrive
    override val loadLinksTimeoutMs = 90_000L

    private val api = SiteApi()
    private val gate = CineVoodGate(refererProvider = { api.base })
    private val catMutex = Mutex()

    // ---------------------------------------------------------------- tabs

    private data class Tab(val label: String, val slug: String?)

    private val tabs = listOf(
        Tab("Latest", null),
        Tab("480p", "480p"),
        Tab("720p", "720p"),
        Tab("1080p", "1080p"),
        Tab("4K", "2160p-4k"),
        Tab("Blu-Ray", "blu-ray"),
        Tab("Dual Audio", "dual-audio-movies"),
        Tab("Hindi Dubbed", "hindi-dubbed-movies"),
        Tab("Punjabi", "punjabi"),
        Tab("Marathi", "marathi"),
        Tab("Gujarati", "gujarati"),
        Tab("Telugu", "telugu"),
        Tab("Tamil", "tamil-movie"),
        Tab("Kannada", "kannada"),
        Tab("Malayalam", "malayalam"),
        Tab("Bengali", "bengali"),
        Tab("English", "english"),
        Tab("Netflix", "netflix"),
        Tab("Prime Video", "amazon-prime-video"),
        Tab("Hotstar", "disney-plus-hotstar"),
        Tab("Zee5", "zee5-originals"),
        Tab("SonyLIV", "sonyliv"),
        Tab("Jio Studios", "jio-studios"),
        Tab("Action", "action"),
        Tab("Comedy", "comedy"),
        Tab("Thriller", "thriller"),
        Tab("Horror", "horror"),
        Tab("Romance", "romance")
    )

    override val mainPage: List<MainPageData> =
        tabs.map { MainPageData(name = it.label, data = it.slug ?: LATEST_TOKEN) }

    // ------------------------------------------------------------- listings

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        attempt("main") {
            learnCategories()
            val wpPage = page.coerceAtLeast(1)
            val items: List<PostListItem> = if (request.data == LATEST_TOKEN) {
                api.postsLatest(wpPage)
            } else {
                val id = api.categoryIdBySlug(request.data)
                    ?: throw java.io.IOException("unresolved category ${request.data}")
                api.postsCategory(id, wpPage)
            }
            val adult = adultCategoryIds()
            val clean = items.filter { !adultItem(it, adult) }
            newHomePageResponse(request, clean.map { searchResponseFor(it) }, hasNext = items.size >= 20)
        }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        learnCategories()
        val adult = adultCategoryIds()
        val q = query.trim()
        val wpPage = page.coerceAtLeast(1)
        val items: List<PostListItem> = attempt("search") {
            runCatching { api.postsSearch(q, wpPage) }
                .getOrElse { api.searchHtmlFallback(q, wpPage) }
        }
        val parsed = items.mapNotNull { item ->
            val t = runCatching { TitleParser.parse(item.title) }.getOrNull() ?: return@mapNotNull null
            if (t.isAdult || t.isTrailer || adultItem(item, adult)) return@mapNotNull null
            item to t
        }
        val ql = q.lowercase()
        val ranked = parsed
            .sortedWith(
                compareBy({ rankName(ql, it.second.name) },
                    { -(it.second.year ?: 0) })
            )
            .distinctBy { "${it.second.name.lowercase()}|${it.second.year}" }
        return newSearchResponseList(
            ranked.map { searchResponseFor(it.first, it.second) },
            hasNext = items.size >= 20
        )
    }

    /** Legacy non-paged entry point. */
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    private fun rankName(q: String, name: String): Int = when {
        name.lowercase() == q -> 0
        name.lowercase().startsWith(q) -> 1
        name.lowercase().contains(q) -> 2
        else -> 3
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse {
        learnCategories()
        val adult = adultCategoryIds()
        return attempt("load") {
            val post = api.postByLink(DomainResolver.rewriteTo(url, api.base))
            val t = TitleParser.parse(post.title)
            if (t.isAdult || post.categories.any { it in adult }) {
                throw IllegalArgumentException("Content not available")
            }
            val groups = PostParser.parseDownloadGroups(post.content)
            val imdbId = PostParser.extractImdbId(post.content)
            val isSeries = t.isSeries || post.categories.any { slug ->
                api.slugOf(slug)?.contains("series") == true
            }
            val tmdb = runCatching {
                SharedServices.tmdbLookup(imdbId, t.name, t.year, isSeries)
            }.getOrNull()
            val type = when {
                isSeries -> TvType.TvSeries
                tmdb != null && !tmdb.isMovie -> TvType.TvSeries
                else -> TvType.Movie
            }
            val poster = post.poster ?: tmdb?.poster

            if (type == TvType.TvSeries) {
                val bySeason = LinkedHashMap<Int, MutableList<GroupInfo>>()
                groups.forEach { g ->
                    val s = (g.season ?: t.seasons.firstOrNull() ?: 1).coerceIn(1, 60)
                    bySeason.getOrPut(s) { ArrayList() }.add(g)
                }
                if (bySeason.isEmpty()) {
                    bySeason[1] = ArrayList(groups)
                }
                val episodes = bySeason.toSortedMap().map { (season, seasonGroups) ->
                    newEpisode("${post.url}|$season") {
                        this.season = season
                        name = seasonTitle(t.name, season, seasonGroups)
                        this.posterUrl = poster
                    }
                }
                newTvSeriesLoadResponse(t.name, post.url, TvType.TvSeries, episodes) {
                    this.plot = cleanPlot(post.excerpt)
                    this.posterUrl = poster
                    this.year = t.year
                    this.tags = tmdb?.genres ?: emptyList()
                    applyTmdbExtras(tmdb)
                }
            } else {
                newMovieLoadResponse(t.name, post.url, TvType.Movie, post.url) {
                    this.plot = cleanPlot(post.excerpt)
                    this.posterUrl = poster
                    this.year = t.year
                    this.tags = tmdb?.genres ?: emptyList()
                    applyTmdbExtras(tmdb)
                }
            }
        }
    }

    private fun seasonTitle(show: String, season: Int, groups: List<GroupInfo>): String {
        val from = groups.mapNotNull { it.episodeFrom }.minOrNull()
        val to = groups.mapNotNull { it.episodeTo }.maxOrNull()
        val ep = if (from != null && to != null && to >= from) " · E$from-$to"
        else if (from != null) " · E$from" else ""
        return "Season $season$ep"
    }

    private fun LoadResponse.applyTmdbExtras(info: SharedServices.TmdbInfo?) {
        if (info == null) return
        backgroundPosterUrl = info.backdrop ?: backgroundPosterUrl
        info.rating10?.let { score = Score.from10(it) }
        if (info.year != null && year == null) year = info.year
        if (info.runtimeMinutes != null) duration = info.runtimeMinutes
    }

    private fun cleanPlot(excerpt: String): String? =
        excerpt.replace(Regex("(?i)\\bdownload\\b.*"), "").trim().takeIf { it.length > 20 }

    // ----------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = data.substringBefore('|')
        val season = data.substringAfter('|', "").toIntOrNull()
        learnCategories()
        return attempt("links") {
            val post = api.postByLink(DomainResolver.rewriteTo(url, api.base))
            var groups = PostParser.parseDownloadGroups(post.content)
                .filter { it.quality >= MIN_QUALITY }
            if (season != null) {
                val pick = groups.filter { (it.season ?: season) == season }
                if (pick.isNotEmpty()) groups = pick
            }
            if (groups.isEmpty()) return@attempt false

            val emitted = AtomicInteger(0)
            withContext(Dispatchers.IO) {
                coroutineScope {
                    groups.map { group ->
                        async {
                            val ok = runCatching {
                                LinkExtractors.emit(group, post.url, gate, subtitleCallback) { link ->
                                    callback(link)
                                    emitted.incrementAndGet()
                                    Unit // callback returns Unit in this CS API
                                }
                            }.getOrDefault(false)
                            if (ok) Unit else false
                        }
                    }.awaitAll()
                }
            }
            emitted.get() > 0
        }
    }

    // ------------------------------------------------------------- plumbing

    private val categoryLearn = java.util.concurrent.atomic.AtomicBoolean(false)

    private suspend fun learnCategories() {
        if (categoryLearn.get()) return
        catMutex.withLock {
            if (categoryLearn.get()) return
            runCatching { api.ensureCategories() }.onSuccess { categoryLearn.set(true) }
        }
    }

    private fun adultCategoryIds(): Set<Int> =
        api.allCategories.entries
            .filter { ADULT_SLUG.containsMatchIn(it.key) || ADULT_SLUG.containsMatchIn(it.value.name) }
            .map { it.value.id }.toSet()

    private fun adultItem(item: PostListItem, adultIds: Set<Int>): Boolean {
        if (item.categories.any { it in adultIds }) return true
        return ADULT_TEXT.containsMatchIn(item.title)
    }

    private suspend fun <T> attempt(tag: String, call: suspend () -> T): T {
        var last: Exception? = null
        repeat(3) {
            try {
                return call()
            } catch (e: Exception) {
                last = e
                runCatching { api.ensureHealthy() }
                api.rotate()
                if (mainUrl != api.base) mainUrl = api.base
            }
        }
        throw last ?: Exception("CineVood $tag failed")
    }

    private fun searchResponseFor(item: PostListItem, parsed: ParsedTitle? = null): SearchResponse {
        val t = parsed ?: runCatching { TitleParser.parse(item.title) }.getOrNull()
        val display = t?.name?.ifBlank { null } ?: item.title
        val year = t?.year
        val series = t?.isSeries == true
        return if (series) {
            newTvSeriesSearchResponse(display, item.url, TvType.TvSeries) {
                posterUrl = item.poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(display, item.url, TvType.Movie) {
                posterUrl = item.poster
                this.year = year
            }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://cinevood.loan"
        private const val LATEST_TOKEN = "__latest__"
        private const val MIN_QUALITY = 720
        private val ADULT_SLUG = Regex(
            "(?i)(18|adult|erotic|brazzers|moodx|vivamax|kooku|ullu|primeshot|xxx|porn|neonx|desi[- ]?girl|cuties)"
        )
        private val ADULT_TEXT = Regex(
            "(?i)(\\[\\s*18\\s*\\+|18\\+|brazzers|moodx|kooku|vivamax|adult|erotic|xxx|porn|onlyfans|leaked)"
        )
    }
}
