package com.ottmirror.plugin

import com.ottmirror.core.TmdbService
import com.ottmirror.stream.StreamEngine
import com.ottmirror.stream.ServerFarm
import com.ottmirror.stream.ServerIdType
/**

 * FILE: OTTMirror.kt — the OTTMirror plugin (entry + TMDB catalog provider).
 *
 *  - [OTTMirror]          plugin entrypoint (@CloudstreamPlugin).
 *  - [OTTMirrorProvider]  TMDB-keyed MainAPI: no catalog of its own — every
 *                         title is resolved on demand against TMDB metadata,
 *                         then handed to the resolution engine in
 *                         stream/StreamEngine.kt.
 *
 * Shared services live in core/CoreServices.kt; the VidLink stream source in
 * sources/VidLinkSource.kt.
 */

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers the OTTMirror provider with CloudStream.
 */
@CloudstreamPlugin
class OTTMirror : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OTTMirrorProvider())
    }
}

/**
 * Pure TMDB URL parser, extractable from [OTTMirrorProvider] for unit testing.
 * No CloudStream dependency — safe for JVM unit tests.
 */
object TmdbUrlParser {
    private val tmdbWebUrl = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")

    /** Parse a TMDB web URL into (tmdbId, type). Returns null for non-TMDB URLs. */
    fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = tmdbWebUrl.find(url) ?: return null
        val id = m.groupValues[2].toIntOrNull() ?: return null
        return id to if (m.groupValues[1] == "movie") "movie" else "tv"
    }
}

/**
 * OTTMirror — a federated embed-server resolver keyed by TMDB/IMDB id.
 *
 * The plugin has no catalog of its own: search and metadata come from TMDB, and
 * every title is resolved on demand by racing dozens of independent HLS/DASH
 * embed servers (see [ServerFarm]) that all accept TMDB/IMDB ids. The resolver
 * measures real stream throughput and emits links fastest-first, so playback
 * feels like an official OTT app without depending on any single backend.
 */
class OTTMirrorProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "OTTMirror"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbWebUrl = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
    private val episodeUrl = Regex("""themoviedb\.org/tv/(\d+)/season/(\d+)/episode/(\d+)""")

    // Home rows are TMDB-powered (the farm has no catalog of its own).
    override val mainPage
        get() = mainPageOf(
            Pair("trending|movie", "Trending Movies"),
            Pair("trending|tv", "Trending Series"),
            Pair("popular|movie", "Popular Movies"),
            Pair("popular|tv", "Popular Series"),
        )

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        val items = withTimeoutOrNull(6000L) { TmdbService.search(query) }.orEmpty()
        if (items.isEmpty()) return null
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private fun TmdbService.TmdbItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        if (name.isBlank()) return null
        val tmdbPath = if (type == "movie") "movie" else "tv"
        val url = "https://www.themoviedb.org/$tmdbPath/$id"
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

    // ------------------------------------------------------------------
    // Main page (TMDB-powered rows)
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val (type, kind) = request.data.split("|").let { it[0] to it.getOrElse(1) { "trending" } }
        val tmdbType = if (type == "movie") "movie" else "tv"
        val items = withTimeoutOrNull(6000L) {
            when (kind) {
                "popular" -> TmdbService.popular(tmdbType, page)
                else -> TmdbService.trending(tmdbType, page)
            }
        }.orEmpty()
        if (items.isEmpty()) return null
        val responses = items.mapNotNull { it.toSearchResponse() }
        if (responses.isEmpty()) return null
        return newHomePageResponse(request.name, responses)
    }

    // ------------------------------------------------------------------
    // Load (detail page)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val tmdb = TmdbUrlParser.parseTmdbUrl(url) ?: return null
        val (tmdbId, type) = tmdb
        val isMovie = type == "movie"

        val meta = withTimeoutOrNull(7000L) { TmdbService.fetchMeta(tmdbId, type) }

        val title = meta?.name ?: return null
        val poster = meta?.poster
        val backdrop = meta?.backdrop
        val year = meta?.year?.toIntOrNull()
        val plot = meta?.overview
        val tags = meta?.genres
        val score = meta?.rating
        val imdbId = meta?.imdbId

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = meta?.cast
                imdbId?.let { addImdbId(it) }
                score?.let { addScore(it.toString(), 10) }
            }
        }

        // TV: enumerate seasons from TMDB, then fetch episodes per season.
        val seasons = TmdbService.fetchTvSeasons(tmdbId)
        if (seasons.isEmpty()) return null

        val episodes = coroutineScope {
            seasons.map { season ->
                async {
                    withTimeoutOrNull(6000L) { TmdbService.fetchSeasonPublic(tmdbId, season) }
                }
            }.awaitAll().filterNotNull().flatten()
        }

        val epList = episodes.map { ep ->
            // Encode season/episode into the episode URL so loadLinks() can parse it.
            val epUrl = "https://www.themoviedb.org/tv/$tmdbId/season/${ep.seasonNumber}/episode/${ep.episodeNumber}"
            newEpisode(epUrl) {
                this.name = ep.name
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.description = ep.overview
                ep.released?.let { this.addDate(it) }
                ep.thumbnail?.let { this.posterUrl = it }
                ep.rating?.let { this.score = Score.from10(it) }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = meta?.cast
            imdbId?.let { addImdbId(it) }
            score?.let { addScore(it.toString(), 10) }
        }
    }

    // ------------------------------------------------------------------
    // Load links (the resolver)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdb = TmdbUrlParser.parseTmdbUrl(data) ?: run {
            android.util.Log.w("OTTMirror", "loadLinks: not a TMDB url: $data")
            return false
        }
        val (tmdbId, type) = tmdb

        // Episodes carry season/episode in the URL; movies/episodes without it default to -1.
        val epMatch = episodeUrl.find(data)
        val season = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: -1
        val episode = epMatch?.groupValues?.get(3)?.toIntOrNull() ?: -1

        val needsImdb = ServerFarm.allServers.any { it.idType == ServerIdType.IMDB }
        val imdbId = if (needsImdb) withTimeoutOrNull(3000L) { TmdbService.fetchMeta(tmdbId, type) }?.imdbId else null
        val streams = withTimeoutOrNull(45_000L) {
            StreamEngine.resolve(tmdbId, imdbId, type, season, episode)
        }.orEmpty()

        if (streams.isEmpty()) {
            android.util.Log.w("OTTMirror", "loadLinks: zero streams for tmdb=$tmdbId/$type -> CloudStream shows \"no link found\"")
            return false
        }

        var emitted = 0
        StreamEngine.emit(streams, { emitted++; callback(it) }, subtitleCallback)
        android.util.Log.i("OTTMirror", "loadLinks: tmdb=$tmdbId/$type s=$season e=$episode -> ${streams.size} streams, $emitted links emitted")
        return emitted > 0
    }
}

