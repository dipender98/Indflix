package com.indstream

import com.lagradost.cloudstream3.*

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.indstream.foryou.ForYouEngine
import com.indstream.foryou.SimklHistory
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select

@CloudstreamPlugin
class IndStream : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(IndStreamProvider())
        openSettings = { ctx -> Settings.openSettings(ctx) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { StreamEngine.prewarmMovieBoxToken() }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { SimklHistory.refresh() }
        }
    }
}

object TmdbUrlParser {
    private val tmdbWebUrl = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
    private val imdbWebUrl = Regex("""imdb\.com/title/(tt\d+)""")
    private val imdbEpisodeUrl = Regex("""imdb\.com/title/(tt\d+)/season/(\d+)/episode/(\d+)""")

    fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = tmdbWebUrl.find(url) ?: return null
        val id = m.groupValues[2].toIntOrNull() ?: return null
        return id to if (m.groupValues[1] == "movie") "movie" else "tv"
    }

    data class TitleRef(val tmdbId: Int?, val imdbId: String?, val type: String?)

    fun parseTitleUrl(url: String?): TitleRef? {
        if (url.isNullOrBlank()) return null
        val imdb = param(url, "imdb")?.takeIf { it.startsWith("tt") }
            ?: imdbWebUrl.find(url)?.groupValues?.get(1)
        val tmdb = param(url, "tmdb")?.toIntOrNull()
            ?: tmdbWebUrl.find(url)?.groupValues?.get(2)?.toIntOrNull()
        if (tmdb == null && imdb == null) return null
        val rawType = param(url, "type")
            ?: tmdbWebUrl.find(url)?.groupValues?.get(1)
            ?: if (url.contains("/season/")) "tv" else null
        return TitleRef(tmdb, imdb, rawType?.let { normType(it) })
    }

    fun parseSeasonEpisode(url: String): Pair<Int, Int> {
        Regex("""themoviedb\.org/tv/\d+/season/(\d+)/episode/(\d+)""").find(url)?.let {
            return (it.groupValues[1].toIntOrNull() ?: -1) to (it.groupValues[2].toIntOrNull() ?: -1)
        }
        imdbEpisodeUrl.find(url)?.let {
            return (it.groupValues[2].toIntOrNull() ?: -1) to (it.groupValues[3].toIntOrNull() ?: -1)
        }
        return -1 to -1
    }

    fun tmdbUrl(tmdbId: Int, type: String, imdbId: String?): String {
        val path = if (normType(type) == "movie") "movie" else "tv"
        return "https://www.themoviedb.org/$path/$tmdbId" +
            (imdbId?.takeIf { it.startsWith("tt") }?.let { "?imdb=$it" } ?: "")
    }

    fun tmdbEpisodeUrl(tmdbId: Int, imdbId: String?, season: Int, episode: Int): String {
        val base = "https://www.themoviedb.org/tv/$tmdbId/season/$season/episode/$episode"
        return base + (imdbId?.takeIf { it.startsWith("tt") }?.let { "?imdb=$it" } ?: "")
    }
    fun imdbUrl(imdbId: String, type: String, tmdbId: Int?): String {
        val t = normType(type)
        val base = "https://www.imdb.com/title/$imdbId/"
        return if (tmdbId != null && tmdbId > 0) "$base?tmdb=$tmdbId&type=$t" else "$base?type=$t"
    }

    fun imdbEpisodeUrl(imdbId: String, season: Int, episode: Int, tmdbId: Int?): String {
        val base = "https://www.imdb.com/title/$imdbId/season/$season/episode/$episode/"
        return if (tmdbId != null && tmdbId > 0) "$base?tmdb=$tmdbId&type=tv" else "$base?type=tv"
    }

    fun normType(t: String?): String = if (t == "movie") "movie" else "tv"

    private fun param(url: String, key: String): String? =
        Regex("""[?&]$key=([^&#]+)""").find(url)?.groupValues?.get(1)
}

class IndStreamProvider : MainAPI() {

    private val fastStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prewarmed = java.util.Collections.synchronizedSet(HashSet<String>())

    private val tmdbSearchGraceMs = 1_200L

    private val warmFarms = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "IndStream"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData>
        get() {
            val rows = ArrayList<Pair<String, String>>()
            if (SimklHistory.isSignedIn()) {
                rows += Pair("foryou|movie", "Made for You: Movies")
                rows += Pair("foryou|tv", "Made for You: Series")
            }
            rows += Pair("trending|movie", "Trending Movies")
            rows += Pair("trending|tv", "Trending Series")
            rows += Pair("popular|movie", "Popular Movies")
            rows += Pair("popular|tv", "Popular Series")
            rows += TmdbService.genreRows()
            return mainPageOf(*rows.toTypedArray())
        }


    override suspend fun search(query: String): List<SearchResponse>? = coroutineScope {
        if (query.isBlank()) return@coroutineScope null
        val imdbDef = async { imdbSearchFuzzy(query) }
        val tmdbDef = async { withTimeoutOrNull(12000L) { TmdbService.search(query) }.orEmpty() }
        var imdbHits: List<ImdbService.ImdbHit>? = null
        var tmdbHits: List<TmdbService.TmdbItem>? = null
        select<Unit> {
            imdbDef.onAwait { h ->
                if (h.isNotEmpty()) imdbHits = h else tmdbHits = tmdbDef.await()
            }
            tmdbDef.onAwait { t ->
                if (t.isNotEmpty()) tmdbHits = t else imdbHits = imdbDef.await()
            }
        }
        if (tmdbHits.isNullOrEmpty() && !imdbHits.isNullOrEmpty()) {
            val late = withTimeoutOrNull(tmdbSearchGraceMs) { tmdbDef.await() }.orEmpty()
            if (late.isNotEmpty()) tmdbHits = late
        }
        imdbDef.cancel()
        tmdbDef.cancel()
        tmdbHits?.let { hits ->
            return@coroutineScope hits.mapNotNull { it.toSearchResponse() }.take(12).takeIf { it.isNotEmpty() }
        }
        val hits = imdbHits.orEmpty()
        if (hits.isEmpty()) return@coroutineScope null
        val ratings = ImdbService.fetchRatings(hits.take(12).map { it.imdbId to it.type })
        return@coroutineScope hits.sortedWith(
            compareByDescending<ImdbService.ImdbHit> { SearchRank.combined(query, it.title, it.rank, null) }
                .thenByDescending { it.year ?: 0 },
        ).take(12).mapNotNull { h ->
            newCard(h.title, TmdbUrlParser.imdbUrl(h.imdbId, h.type, null), h.type, h.year, h.poster, ratings[h.imdbId])
        }.takeIf { it.isNotEmpty() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private suspend fun imdbSearchFuzzy(query: String): List<ImdbService.ImdbHit> = coroutineScope {
        val queries = (listOf(query) + SearchRank.queryVariants(query, max = 1)).distinct()
        if (queries.size == 1) return@coroutineScope ImdbService.suggest(query)
        queries.map { q -> async { ImdbService.suggest(q) } }
            .awaitAll().flatten().distinctBy { it.imdbId }
    }

    private fun newCard(
        name: String,
        url: String,
        type: String,
        year: Int?,
        poster: String?,
        rating: Double?,
    ): SearchResponse? {
        if (name.isBlank()) return null
        val tvType = if (type == "movie") TvType.Movie else TvType.TvSeries
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(name, url, tvType) {
                this.posterUrl = poster
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(name, url, tvType) {
                this.posterUrl = poster
                this.year = year
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun TmdbService.TmdbItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        if (name.isBlank()) return null
        val type = TmdbUrlParser.normType(type)
        return newCard(name, TmdbUrlParser.tmdbUrl(id, type, imdbId), type, year?.toIntOrNull(), poster, rating)
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val parts = request.data.split("|")
        val genre = TmdbService.parseGenreRow(request.data)
        val kind = parts[0].trim()
        val tmdbType = genre?.second ?: if (parts.getOrNull(1)?.trim() == "tv") "tv" else "movie"
        val items = withTimeoutOrNull(if (kind == "foryou") 25_000L else 12_000L) {
            when (kind) {
                "popular" -> TmdbService.popular(tmdbType, page)
                "foryou" -> ForYouEngine.row(tmdbType, page)
                "genre" -> genre
                    ?.let { (id, type) -> TmdbService.discover(type, listOf(id), page) }
                    .orEmpty()
                else -> TmdbService.trending(tmdbType, page)
            }
        }.orEmpty()
        if (items.isEmpty()) return null
        val responses = items.mapNotNull { it.toSearchResponse() }
        if (responses.isEmpty()) return null
        return newHomePageResponse(
            request.name,
            if (Settings.shuffleRows()) responses.shuffled() else responses,
        )
    }


    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val ref = TmdbUrlParser.parseTitleUrl(url) ?: return@coroutineScope null
        val urlImdb = ref.imdbId

        val quickTmdb = if (ref.tmdbId == null && urlImdb != null && ref.type == null) {
            withTimeoutOrNull(1500L) { TmdbService.findByImdb(urlImdb) }
        } else null
        val tmdbId: Int? = ref.tmdbId ?: quickTmdb?.first
        val type: String = ref.type ?: quickTmdb?.second?.let { TmdbUrlParser.normType(it) } ?: "movie"

        val resolvedTmdbId = java.util.concurrent.atomic.AtomicReference<Int?>(tmdbId)
        val tmdbMetaJob = async {
            val id = tmdbId ?: urlImdb?.let { iid ->
                runCatching { withTimeoutOrNull(6000L) { TmdbService.findByImdb(iid) } }.getOrNull()?.first
            }
            resolvedTmdbId.set(id)
            if (id == null) return@async null
            withTimeoutOrNull(12000L) { TmdbService.fetchMeta(id, type) }
        }
        val cineJob = async {
            val iid = urlImdb ?: tmdbId?.let { id ->
                runCatching { withTimeoutOrNull(6000L) { TmdbService.fetchMeta(id, type)?.imdbId } }
                    .getOrNull()?.takeIf { it.startsWith("tt") }
            } ?: return@async null
            val detail = withTimeoutOrNull(9000L) { ImdbService.fetchMeta(iid, type) }
            detail to iid
        }
        var tmdbMeta: TmdbService.TmdbDetail? = null
        var cineMeta: ImdbService.MetaDetail? = null
        var cineIid: String? = null
        select<Unit> {
            tmdbMetaJob.onAwait { m ->
                if (!m?.name.isNullOrBlank()) tmdbMeta = m
                else cineJob.await()?.let { cineMeta = it.first; cineIid = it.second }
            }
            cineJob.onAwait { c ->
                if (!c?.first?.name.isNullOrBlank()) { cineMeta = c?.first; cineIid = c?.second }
                else tmdbMeta = tmdbMetaJob.await()
            }
        }
        tmdbMetaJob.cancel()
        cineJob.cancel()
        val resolvedImdb: String? = tmdbMeta?.imdbId ?: cineIid ?: urlImdb

        val effectiveTmdbId: Int? = resolvedTmdbId.get()
            ?: tmdbMeta?.tmdbId
            ?: resolvedImdb?.let { iid ->
                runCatching { withTimeoutOrNull(800L) { TmdbService.findByImdb(iid)?.first } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()
            }

        val preIid = resolvedImdb
        if (effectiveTmdbId == null && preIid != null) {
            fastStartScope.launch {
                runCatching { withTimeoutOrNull(6000L) { TmdbService.findByImdb(preIid) } }
            }
        }

        val title: String
        val poster: String?
        val backdrop: String?
        val year: Int?
        val plot: String?
        val tags: List<String>?
        val score: Double?
        val cast: List<ActorData>?
        val duration: Int?
        val won = tmdbMeta
        if (won != null) {
            title = won.name ?: return@coroutineScope null
            poster = won.poster
            backdrop = won.backdrop
            year = won.year?.toIntOrNull()
            plot = won.overview
            tags = won.genres
            score = won.rating
            cast = won.cast
            duration = won.runtime?.let { it * 60 }
        } else {
            val wonCine = cineMeta ?: return@coroutineScope null
            title = wonCine.name ?: return@coroutineScope null
            poster = wonCine.poster
            backdrop = wonCine.backdrop
            year = wonCine.year?.toIntOrNull()
            plot = wonCine.overview
            tags = wonCine.genres
            score = wonCine.rating
            cast = extendImdbCast(wonCine, effectiveTmdbId, type)
            duration = wonCine.runtime?.let { it * 60 }
        }
        val dataUrl = if (effectiveTmdbId != null) TmdbUrlParser.tmdbUrl(effectiveTmdbId, type, resolvedImdb)
        else TmdbUrlParser.imdbUrl(resolvedImdb ?: return@coroutineScope null, type, null)
        val isMovie = type == "movie"

        if (isMovie) prewarm(effectiveTmdbId ?: 0, resolvedImdb, type)

        if (isMovie) {
            return@coroutineScope newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = cast
                this.duration = duration
                resolvedImdb?.let { addImdbId(it) }
                score?.let { addScore(it.toString(), 10) }
            }
        }

        val episodes = seriesEpisodes(effectiveTmdbId, resolvedImdb, type)
        if (episodes.isEmpty()) return@coroutineScope null
        val epList = episodes.map { ep ->
            val epUrl = if (effectiveTmdbId != null) TmdbUrlParser.tmdbEpisodeUrl(effectiveTmdbId, resolvedImdb, ep.seasonNumber, ep.episodeNumber)
            else TmdbUrlParser.imdbEpisodeUrl(resolvedImdb ?: "", ep.seasonNumber, ep.episodeNumber, null)
            newEpisode(epUrl) {
                this.name = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}"
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.description = ep.overview
                ep.released?.take(10)?.takeIf { it.isNotBlank() }?.let { runCatching { addDate(it) } }
                ep.thumbnail?.let { this.posterUrl = it }
                ep.rating?.let { this.score = Score.from10(it) }
                ep.runtime?.let { this.runTime = it * 60 }
            }
        }

        return@coroutineScope newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = cast
            this.duration = duration
            resolvedImdb?.let { addImdbId(it) }
            score?.let { addScore(it.toString(), 10) }
        }
    }

    private suspend fun extendImdbCast(
        wonCine: ImdbService.MetaDetail,
        tmdbId: Int?,
        type: String,
    ): List<ActorData>? {
        if (tmdbId == null || tmdbId <= 0) return wonCine.cast
        val tmdbCast = withTimeoutOrNull(4000L) {
            TmdbService.fetchMeta(tmdbId, type)?.cast
        }.orEmpty()
        return ImdbService.mergeCast(wonCine.cast, tmdbCast)
    }

    private suspend fun seriesEpisodes(
        tmdbId: Int?,
        imdbId: String?,
        type: String,
    ): List<TmdbService.TmdbEpisode> = coroutineScope {
        val tmdbJob = async {
            if (tmdbId == null || tmdbId <= 0) return@async emptyList()
            val seasons = withTimeoutOrNull(15000L) { TmdbService.fetchTvSeasons(tmdbId) }.orEmpty()
            if (seasons.isEmpty()) return@async emptyList()
            withTimeoutOrNull(30000L) { TmdbService.fetchSeasons(tmdbId, seasons) }.orEmpty()
        }
        val cineJob = async {
            if (imdbId == null) return@async emptyList()
            val raw = withTimeoutOrNull(9000L) { ImdbService.fetchSeriesRaw(imdbId) }
            ImdbService.parseCinemetaEpisodes(raw)
        }
        val won = select<List<TmdbService.TmdbEpisode>> {
            tmdbJob.onAwait { if (it.isNotEmpty()) it else cineJob.await() }
            cineJob.onAwait { if (it.isNotEmpty()) it else tmdbJob.await() }
        }
        tmdbJob.cancel()
        cineJob.cancel()
        won.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }

    private val tapSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private class FarmHandle(val key: String, val job: kotlinx.coroutines.Job)
    private val inFlightFarms = java.util.concurrent.ConcurrentHashMap<String, FarmHandle>()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val tap = tapSeq.incrementAndGet()
        val t0 = System.currentTimeMillis()
        android.util.Log.i("IndStream", "TAP#$tap loadLinks start casting=$isCasting")
        val result = try {
            loadLinksInner(tap, data, isCasting, subtitleCallback, callback)
        } catch (c: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("IndStream", "TAP#$tap loadLinks CANCELLED by the app after " +
                "${System.currentTimeMillis() - t0}ms (${c.message ?: c.javaClass.simpleName})")
            throw c
        }
        android.util.Log.i("IndStream", "TAP#$tap loadLinks END result=$result in " +
            "${System.currentTimeMillis() - t0}ms")
        return result
    }

    private suspend fun imdbWithRetry(
        first: suspend () -> String?,
        tmdbId: Int,
        type: String,
        urlImdb: String?,
    ): String? {
        urlImdb?.takeIf { it.startsWith("tt") }?.let { return it }
        runCatching { withTimeoutOrNull(8000L) { first() } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it.startsWith("tt") }?.let { return it }
        if (tmdbId <= 0) return null
        return runCatching { withTimeoutOrNull(8000L) { TmdbService.fetchMeta(tmdbId, type)?.imdbId } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()?.takeIf { it.startsWith("tt") }
    }

    private suspend fun loadLinksInner(
        tap: Int,
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = TmdbUrlParser.parseTitleUrl(data) ?: run {
            android.util.Log.w("IndStream", "loadLinks: unparsable url: $data")
            return false
        }
        val type = ref.type ?: "movie"
        val tmdbId = ref.tmdbId ?: 0
        val urlImdb = ref.imdbId

        val (season, episode) = TmdbUrlParser.parseSeasonEpisode(data)

        val needsImdb = ServerFarm.allServers.any { it.idType == ServerIdType.IMDB }
        val originalLangRef = java.util.concurrent.atomic.AtomicReference<String?>()
        val metaDeferred = fastStartScope.async {
            if (tmdbId <= 0) return@async null
            val d = withTimeoutOrNull(4000L) { TmdbService.fetchMeta(tmdbId, type) }
            originalLangRef.set(d?.originalLanguage)
            d
        }
        val originalLangNow: () -> String? = { originalLangRef.get() }
        val cacheKey = if (tmdbId > 0) StreamEngine.FastStartCache.key(tmdbId, type, season, episode)
        else "imdb:${urlImdb ?: "none"}|$type|$season|$episode"
        val imdbDeferred = fastStartScope.async {
            if (!needsImdb) return@async urlImdb
            urlImdb?.let { return@async it }
            metaDeferred.await()?.imdbId
        }
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)

        fun releaseMeta() {
            metaDeferred.cancel()
            imdbDeferred.cancel()
        }

        val liveFarm = inFlightFarms[cacheKey]?.takeIf { it.job.isActive }?.job
        val warm = liveFarm ?: warmFarms[cacheKey]
        val cached = StreamEngine.FastStartCache.get(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            android.util.Log.i("IndStream", "TAP#$tap replay from FastStartCache " +
                "(age=${StreamEngine.FastStartCache.ageMs(cacheKey)}ms, ${cached.size} streams, " +
                "farmStillRunning=${warm != null})")
            val replayPushed = Collections.synchronizedSet(HashSet<String>())
            cached.forEach { replayPushed += it.url }
            StreamEngine.emit(
                cached,
                { emitted.incrementAndGet(); callback(it) },
                originalLangNow(),
                probeManifests = false,
            )
            val subsJob = fastStartScope.async {
                val imdb = imdbWithRetry({ metaDeferred.await()?.imdbId }, tmdbId, type, urlImdb)
                runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                    .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
            }
            if (warm != null) {
                val fillStart = System.currentTimeMillis()
                while (warm.isActive && System.currentTimeMillis() - fillStart < StreamEngine.LIVE_FILL_MS) {
                    delay(250)
                    val grown = StreamEngine.FastStartCache.get(cacheKey)
                    val fresh = grown?.filter { replayPushed.add(it.url) } ?: emptyList()
                    if (fresh.isNotEmpty()) StreamEngine.emit(
                        fresh,
                        { emitted.incrementAndGet(); callback(it) },
                        originalLangNow(),
                        probeManifests = false,
                    )
                }
                val grown = StreamEngine.FastStartCache.get(cacheKey)
                val fresh = grown?.filter { replayPushed.add(it.url) } ?: emptyList()
                if (fresh.isNotEmpty()) StreamEngine.emit(
                    fresh,
                    { emitted.incrementAndGet(); callback(it) },
                    originalLangNow(),
                    probeManifests = false,
                )
            }
            android.util.Log.i("IndStream", "TAP#$tap replay done (+live tail: ${if (warm != null) "yes" else "no"}), " +
                "${emitted.get()} streams for tmdb=$tmdbId/$type s=$season e=$episode")
            subsJob.await()
            releaseMeta()
            return emitted.get() > 0
        }

        val pushedUrls = Collections.synchronizedSet(HashSet<String>())
        val windowOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        val loadStartMs = System.currentTimeMillis()

        run {
            val running: kotlinx.coroutines.Job? =
                inFlightFarms[cacheKey]?.takeIf { it.job.isActive }?.job
                    ?: warmFarms[cacheKey]?.takeIf { it.isActive }
            if (running != null) {
                android.util.Log.i("IndStream", "TAP#$tap joining in-flight farm for $cacheKey (no second launch)")
                val subsJob = fastStartScope.async {
                    val imdb = imdbWithRetry({ imdbDeferred.await() }, tmdbId, type, urlImdb)
                    runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                        .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
                }
                val joined = Collections.synchronizedSet(HashSet<String>())
                suspend fun tail(): Boolean {
                    val fresh = StreamEngine.FastStartCache.get(cacheKey)
                        ?.filter { joined.add(it.url) }.orEmpty()
                    if (fresh.isEmpty()) return false
                    StreamEngine.emit(
                        fresh,
                        { emitted.incrementAndGet(); callback(it) },
                        originalLangNow(),
                        probeManifests = false,
                    )
                    return true
                }
                while (System.currentTimeMillis() - loadStartMs < StreamEngine.LIVE_FILL_MS) {
                    delay(250)
                    tail()
                    if (!running.isActive) { tail(); break }
                    if (emitted.get() == 0 &&
                        System.currentTimeMillis() - loadStartMs > StreamEngine.FAST_START_MAX_MS
                    ) {
                        android.util.Log.w("IndStream", "TAP#$tap joined farm produced nothing in " +
                            "${StreamEngine.FAST_START_MAX_MS}ms")
                        subsJob.await()
                        releaseMeta()
                        return false
                    }
                }
                if (emitted.get() > 0) {
                }
                subsJob.await()
                android.util.Log.i("IndStream", "TAP#$tap joined farm -> ${emitted.get()} live links " +
                    "in ${System.currentTimeMillis() - loadStartMs}ms")
                releaseMeta()
                return emitted.get() > 0
            }
        }

        suspend fun emitBatch(sid: String, streams: List<StreamEngine.RawStream>) {
            val fresh = streams.filter { it.url.isNotBlank() && pushedUrls.add(it.url) }
            if (fresh.isEmpty()) return
            StreamEngine.FastStartCache.put(cacheKey, fresh)
            if (!windowOpen.get()) return
            android.util.Log.i("IndStream", "TAP#$tap +${fresh.size} from $sid at " +
                "${System.currentTimeMillis() - loadStartMs}ms (window open)")
            StreamEngine.emit(
                fresh,
                { emitted.incrementAndGet(); callback(it) },
                originalLangNow(),
            )
        }

        val farmDone = fastStartScope.async {
            try {
                StreamEngine.resolveRealtime(tmdbId, type, season, episode, urlImdb, imdbIdProvider = { imdbDeferred.await() }) { sid, streams ->
                    emitBatch(sid, streams)
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("IndStream", "live resolve failed: ${t.message}")
            }
        }
        run {
            val handle = FarmHandle(cacheKey, farmDone)
            inFlightFarms[cacheKey] = handle
            farmDone.invokeOnCompletion { inFlightFarms.remove(cacheKey, handle) }
        }

        val tmdbWave = if (tmdbId <= 0 && urlImdb != null) {
            fastStartScope.async {
                val id2 = runCatching { withTimeoutOrNull(6000L) { TmdbService.findByImdb(urlImdb) } }
                    .getOrNull()?.first?.takeIf { it > 0 }
                if (id2 == null) {
                    android.util.Log.i("IndStream", "TAP#$tap no TMDB id for $urlImdb (TMDB wave skipped)")
                    return@async
                }
                android.util.Log.i("IndStream", "TAP#$tap TMDB id $id2 resolved, launching TMDB wave")
                runCatching {
                    StreamEngine.resolveRealtime(id2, type, season, episode, urlImdb, tmdbOnly = true) { sid, streams ->
                        emitBatch(sid, streams)
                    }
                }.onFailure { android.util.Log.w("IndStream", "TAP#$tap TMDB wave failed: ${it.message}") }
            }
        } else null

        val subsJob = fastStartScope.async {
            val imdb = imdbWithRetry({ imdbDeferred.await() }, tmdbId, type, urlImdb)
            runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
        }

        val firstStreamArrived = CompletableDeferred<Unit>()
        val arrivalWatcher = fastStartScope.launch {
            while (emitted.get() == 0 && (!farmDone.isCompleted || tmdbWave?.isCompleted == false)) delay(50)
            firstStreamArrived.complete(Unit)
        }

        if (withTimeoutOrNull(StreamEngine.FAST_START_MAX_MS) { firstStreamArrived.await() } == null) {
            android.util.Log.w("IndStream", "TAP#$tap farm produced no streams in ${StreamEngine.FAST_START_MAX_MS}ms " +
                "(detached farm keeps filling FastStartCache; next tap replays + tails it)")
            arrivalWatcher.cancel()
            windowOpen.set(false)
            subsJob.await()
            releaseMeta()
            return false
        }

        withTimeoutOrNull(StreamEngine.LIVE_FILL_MS) { farmDone.await(); tmdbWave?.await() }
        windowOpen.set(false)
        subsJob.await()

        android.util.Log.i("IndStream", "TAP#$tap live window: tmdb=$tmdbId/$type s=$season e=$episode -> " +
            "${emitted.get()} links live in ${System.currentTimeMillis() - loadStartMs}ms (farm=${if (farmDone.isCompleted) "done" else "capped at LIVE_FILL"})")
        releaseMeta()
        return emitted.get() > 0
    }

    private fun prewarm(tmdbId: Int, imdbId: String?, type: String) {
        val key = if (tmdbId > 0) StreamEngine.FastStartCache.key(tmdbId, type, -1, -1)
        else "imdb:${imdbId ?: "none"}|$type|-1|-1"
        if (!prewarmed.add(key)) return
        if (StreamEngine.FastStartCache.get(key) != null) return
        val job = fastStartScope.launch {
            runCatching {
                StreamEngine.resolveRealtime(tmdbId, type, -1, -1, imdbId, imdbIdProvider = { imdbId }) { _, streams ->
                    if (streams.isNotEmpty()) StreamEngine.FastStartCache.put(key, streams)
                }
                if (tmdbId <= 0 && imdbId != null) {
                    val id2 = runCatching { withTimeoutOrNull(6000L) { TmdbService.findByImdb(imdbId) } }
                        .getOrNull()?.first?.takeIf { it > 0 }
                    if (id2 != null) {
                        StreamEngine.resolveRealtime(id2, type, -1, -1, imdbId, tmdbOnly = true) { _, streams ->
                            if (streams.isNotEmpty()) StreamEngine.FastStartCache.put(key, streams)
                        }
                    }
                }
            }.onFailure { android.util.Log.w("IndStream", "prewarm failed: ${it.message}") }
        }
        warmFarms[key] = job
        job.invokeOnCompletion { warmFarms.remove(key, job) }
    }

    private suspend fun topUpSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        originalLang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val wanted = SubtilesProvider.desiredLanguages(originalLang)
        val seen = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val emitLock = Mutex()
        suspend fun emit(t: SubtitleFile) {
            if (!seen.add(t.url)) return
            emitLock.withLock { runCatching { subtitleCallback(t) } }
        }
        coroutineScope {
            Settings.apiKey()?.let { key ->
                launch {
                    runCatching {
                        WyzieSubs.fetchAndDeliver(imdbId, season, episode, wanted, key) { emit(it) }
                    }
                }
            }
            launch {
                runCatching {
                    SubtilesProvider.fetchAndDeliver(imdbId, season, episode, wanted, originalLang) { emit(it) }
                }
            }
        }
    }
}

