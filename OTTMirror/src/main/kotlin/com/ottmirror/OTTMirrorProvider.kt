package com.ottmirror

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

abstract class OTTMirrorProvider(
    internal val ott: OttService,
) : MainAPI() {

    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override var mainUrl = "https://net52.cc"

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object SearchCache {
        private const val TTL_MS = 15 * 60 * 1000L
        private data class Entry(val results: List<SearchResponse>, val expiresAt: Long)
        private val map = ConcurrentHashMap<String, Entry>()

        fun get(ott: OttService, query: String): List<SearchResponse>? {
            val key = "${ott.id}|${query.lowercase()}"
            val e = map[key] ?: return null
            if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
            return e.results
        }

        fun put(ott: OttService, query: String, results: List<SearchResponse>) {
            map["${ott.id}|${query.lowercase()}"] = Entry(results, System.currentTimeMillis() + TTL_MS)
        }
    }

    // Per-OTT CDN poster paths (reference repo values). TMDB upgrades the
    // poster in the background when it has a better one (search pass below).
    private fun posterUrl(id: String): String = when (ott) {
        OttService.NETFLIX -> "https://imgcdn.kim/poster/v/$id.jpg"
        OttService.HOTSTAR, OttService.DISNEY -> "https://imgcdn.kim/hs/v/$id.jpg"
        OttService.PRIME -> "https://imgcdn.kim/pv/341/$id.jpg"
    }
    // Main batch = episodes in the post.php payload (hsepimg/150/ for hs/dp);
    // paged = episodes from getEpisodes (plain hsepimg/). Matches reference.
    private fun episodePosterUrl(id: String, mainBatch: Boolean): String = when (ott) {
        OttService.NETFLIX -> "https://imgcdn.kim/poster/v/150/$id.jpg"
        OttService.HOTSTAR, OttService.DISNEY ->
            if (mainBatch) "https://imgcdn.kim/hsepimg/150/$id.jpg" else "https://imgcdn.kim/hsepimg/$id.jpg"
        OttService.PRIME -> "https://img.nfmirrorcdn.top/pvepimg/$id.jpg"
    }
    private fun posterHeaders(): Map<String, String> = mapOf("Referer" to "${DomainRotator.current(Role.MOBILE) ?: mainUrl}/home")

    private fun encode(id: String, title: String, tmdbId: String? = null, season: Int? = null, episode: Int? = null): String =
        encodeLoadData(LoadData(id, title, tmdbId, season, episode))

    // ------------------------------------------------------------------
    // Home page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val rows = runCatching { OTTMirrorBackend.getHomeRows(ott) }.getOrNull()
        if (rows.isNullOrEmpty()) {
            // A silent null reads as "empty home"; a saturated IP deserves the truth.
            if (OTTMirrorBackend.rateLimited()) throw ErrorLoadingException(OTTMirrorBackend.limitedMessage())
            return null
        }
        val lists = rows.map { (name, ids) ->
            HomePageList(
                name,
                ids.map { id ->
                    newTvSeriesSearchResponse("", encode(id, ""), TvType.TvSeries) {
                        this.posterUrl = posterUrl(id)
                        this.posterHeaders = posterHeaders()
                    }
                },
                isHorizontalImages = false,
            )
        }
        return newHomePageResponse(lists, false)
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse>? {
        SearchCache.get(ott, query)?.let { return it }
        val hits = runCatching {
            OTTMirrorBackend.search(ott, query)
        }.getOrDefault(emptyList())
        if (hits.isEmpty()) {
            if (OTTMirrorBackend.rateLimited()) throw ErrorLoadingException(OTTMirrorBackend.limitedMessage())
            return null
        }

        val results = hits.take(SEARCH_MAX_RESULTS).map { hit ->
            val isMovie = hit.type?.equals("movie", ignoreCase = true) == true
            if (isMovie) {
                newMovieSearchResponse(hit.title, encode(hit.id, hit.title), TvType.Movie) {
                    this.posterUrl = posterUrl(hit.id)
                    this.posterHeaders = posterHeaders()
                }
            } else {
                newTvSeriesSearchResponse(hit.title, encode(hit.id, hit.title), TvType.TvSeries) {
                    this.posterUrl = posterUrl(hit.id)
                    this.posterHeaders = posterHeaders()
                }
            }
        }

        results.forEach { r ->
            bgScope.launch {
                runCatching {
                    TmdbMeta.resolvePosterRating(r.name, null, r.type == TvType.Movie)?.let { info ->
                        info.rating?.let { r.score = Score.from10(it) }
                        info.poster?.let { r.posterUrl = it }
                    }
                }
            }
        }

        SearchCache.put(ott, query, results)
        return results
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val ld = decodeLoadData(url) ?: return null
        return try {
            loadDetail(ld)
        } catch (e: ErrorLoadingException) {
            // Rate-limit and reachability messages must reach the user.
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadDetail(ld: LoadData): LoadResponse {
        // post.php does NOT echo the content id in its body, so the LoadResponse
        // URL and episode seriesId must come from the id we already hold.
        val contentId = ld.id
        val post = OTTMirrorBackend.loadPost(ott, contentId)
            ?: if (OTTMirrorBackend.rateLimited()) throw ErrorLoadingException(OTTMirrorBackend.limitedMessage())
            else throw ErrorLoadingException("Could not load ${ld.title} from NetMirror")

        val isMovie = post.episodes.isEmpty() || post.type.equals("movie", ignoreCase = true)
        val tmdbId = post.tmdbId?.toIntOrNull()

        val meta = coroutineScope {
            val job = async {
                withTimeoutOrNull(5000L) {
                    tmdbId?.let { TmdbMeta.fetchMeta(it, if (isMovie) "movie" else "tv") }
                        ?: post.imdbId?.let { imdb -> TmdbMeta.findByImdb(imdb)?.let { (id, t) -> TmdbMeta.fetchMeta(id, t) } }
                }
            }
            job.await()
        }

        val title = meta?.name ?: post.title
        val year = meta?.year ?: post.year
        val poster = meta?.poster ?: post.poster ?: posterUrl(contentId)
        val description = meta?.overview ?: post.description
        val genres = meta?.genres ?: post.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val cast = meta?.cast
        val rating = meta?.rating ?: parseRating(post.rating)
        val tmdbIdFinal = meta?.tmdbId ?: tmdbId
        val imdbId = meta?.imdbId ?: post.imdbId

        if (isMovie) {
            return newMovieLoadResponse(title, encode(contentId, title, tmdbIdFinal?.toString()), TvType.Movie, posterUrl(contentId)) {
                this.posterUrl = poster
                this.backgroundPosterUrl = meta?.backdrop
                this.year = year?.toIntOrNull()
                this.plot = description
                this.tags = genres
                this.actors = cast
                imdbId?.let { addImdbId(it) }
                rating?.let { addScore(it.toString(), 10) }
            }
        }

        val episodes = buildEpisodes(post, contentId, title, tmdbIdFinal)
        enrichEpisodes(episodes, tmdbIdFinal)

        return newTvSeriesLoadResponse(title, encode(contentId, title, tmdbIdFinal?.toString()), TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = meta?.backdrop
            this.year = year?.toIntOrNull()
            this.plot = description
            this.tags = genres
            this.actors = cast
            imdbId?.let { addImdbId(it) }
            rating?.let { addScore(it.toString(), 10) }
        }
    }

    // "IMDb 7.6" -> 7.6 ; "80% match" -> 8.0 ; "65% match" -> 6.5
    private fun parseRating(raw: String?): Double? {
        val s = raw?.trim() ?: return null
        val imdb = s.removePrefix("IMDb ").trim().toDoubleOrNull()
        if (imdb != null) return imdb
        val pct = Regex("""(\d+(?:\.\d+)?)\s*%""").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        return pct?.div(10.0)
    }

    // "Season 2" / "S2" -> 2; used when a paged episode JSON omits its own
    // season number. Only a confident match counts — a wrong guess would make
    // embed-tmdb resolve the wrong episode, which is worse than falling
    // through to the episode-id-keyed paths.
    private fun seasonNumberFromLabel(label: String?): Int? =
        Regex("(?:season|s)\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(label ?: "")?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun buildEpisodes(post: NetMirrorPost, contentId: String, title: String, tmdbIdFinal: Int?): MutableList<Episode> {
        val episodes = mutableListOf<Episode>()
        post.episodes.forEach { e ->
            episodes += newEpisode(encode(e.id, title, tmdbIdFinal?.toString(), e.season, e.episode)) {
                this.name = e.title; this.season = e.season; this.episode = e.episode
                this.posterUrl = episodePosterUrl(e.id, mainBatch = true)
            }
        }
        if (post.nextPageShow && post.nextPageSeason != null) {
            val seasonOfPage = post.seasons.lastOrNull()?.let { seasonNumberFromLabel(it.label) }
            OTTMirrorBackend.getEpisodes(ott, contentId, post.nextPageSeason).forEach { e ->
                episodes += newEpisode(encode(e.id, title, tmdbIdFinal?.toString(), e.season ?: seasonOfPage, e.episode)) {
                    this.name = e.title; this.season = e.season ?: seasonOfPage; this.episode = e.episode
                    this.posterUrl = episodePosterUrl(e.id, mainBatch = false)
                }
            }
        }
        post.seasons.dropLast(1).forEach { s ->
            val sNum = seasonNumberFromLabel(s.label)
            OTTMirrorBackend.getEpisodes(ott, contentId, s.id).forEach { e ->
                episodes += newEpisode(encode(e.id, title, tmdbIdFinal?.toString(), e.season ?: sNum, e.episode)) {
                    this.name = e.title; this.season = e.season ?: sNum; this.episode = e.episode
                    this.posterUrl = episodePosterUrl(e.id, mainBatch = false)
                }
            }
        }
        return episodes
    }

    private suspend fun enrichEpisodes(episodes: MutableList<Episode>, tmdbIdFinal: Int?) {
        val seasonNums = episodes.mapNotNull { it.season }.toSet()
        if (tmdbIdFinal != null && seasonNums.isNotEmpty()) {
            val epMeta = TmdbMeta.fetchEpisodes(tmdbIdFinal, seasonNums)
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
    }

    // ------------------------------------------------------------------
    // Load links
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ld = decodeLoadData(data) ?: return false
        return try {
            OTTMirrorBackend.loadLinks(ott, data, subtitleCallback, callback)
        } catch (e: ErrorLoadingException) {
            // Surfacing "rate limited — auto-clears in ~Xs" beats a silent
            // "no links found" while the shared IP limiter is saturated.
            throw e
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val SEARCH_MAX_RESULTS = 6
    }
}