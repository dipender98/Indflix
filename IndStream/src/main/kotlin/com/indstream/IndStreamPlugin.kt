package com.indstream

import com.lagradost.cloudstream3.*
/** FILE: IndStreamPlugin. kt - the plugin (entry + TMDB catalog provider). - plugin entrypoint. */

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
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

/** Registers the provider with CloudStream. */
@CloudstreamPlugin
class IndStream : Plugin() {
    override fun load(context: Context) {
        Settings.init(context)
        registerMainAPI(IndStreamProvider())
        openSettings = { ctx -> Settings.openSettings(ctx) }
        // MovieBox bearer pre-warm: the x-user token lives for hours, so one background GET now removes a serial round-trip.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { StreamEngine.prewarmMovieBoxToken() }
        }
    }
}

/** URL parser for both catalog forms. TMDB urls stay canonical; IMDB urls cover TMDB outages. */
object TmdbUrlParser {
    private val tmdbWebUrl = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
    private val imdbWebUrl = Regex("""imdb\.com/title/(tt\d+)""")
    private val imdbEpisodeUrl = Regex("""imdb\.com/title/(tt\d+)/season/(\d+)/episode/(\d+)""")

    /** Parse a TMDB web URL into (tmdbId, type). Returns null for non-TMDB URLs. */
    fun parseTmdbUrl(url: String?): Pair<Int, String>? {
        if (url.isNullOrBlank()) return null
        val m = tmdbWebUrl.find(url) ?: return null
        val id = m.groupValues[2].toIntOrNull() ?: return null
        return id to if (m.groupValues[1] == "movie") "movie" else "tv"
    }

    /** One title reference carrying both ids when known. Type is "movie" | "tv". */
    data class TitleRef(val tmdbId: Int?, val imdbId: String?, val type: String?)

    /** Parse either URL form into a [TitleRef]. Query params (?imdb=, ?tmdb=, ?type=) ride along. Pure. */
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

    /** Season/episode encoded in either URL form. Defaults to (-1, -1). Pure. */
    fun parseSeasonEpisode(url: String): Pair<Int, Int> {
        Regex("""themoviedb\.org/tv/\d+/season/(\d+)/episode/(\d+)""").find(url)?.let {
            return (it.groupValues[1].toIntOrNull() ?: -1) to (it.groupValues[2].toIntOrNull() ?: -1)
        }
        imdbEpisodeUrl.find(url)?.let {
            return (it.groupValues[2].toIntOrNull() ?: -1) to (it.groupValues[3].toIntOrNull() ?: -1)
        }
        return -1 to -1
    }

    /** Canonical TMDB url, carrying the IMDB id when known. */
    fun tmdbUrl(tmdbId: Int, type: String, imdbId: String?): String {
        val path = if (normType(type) == "movie") "movie" else "tv"
        return "https://www.themoviedb.org/$path/$tmdbId" +
            (imdbId?.takeIf { it.startsWith("tt") }?.let { "?imdb=$it" } ?: "")
    }

    /** Canonical TMDB episode url, carrying the IMDB id when known. */
    fun tmdbEpisodeUrl(tmdbId: Int, imdbId: String?, season: Int, episode: Int): String {
        val base = "https://www.themoviedb.org/tv/$tmdbId/season/$season/episode/$episode"
        return base + (imdbId?.takeIf { it.startsWith("tt") }?.let { "?imdb=$it" } ?: "")
    }
    /** IMDB url fallback, carrying the TMDB id when known. */
    fun imdbUrl(imdbId: String, type: String, tmdbId: Int?): String {
        val t = normType(type)
        val base = "https://www.imdb.com/title/$imdbId/"
        return if (tmdbId != null && tmdbId > 0) "$base?tmdb=$tmdbId&type=$t" else "$base?type=$t"
    }

    /** IMDB episode url fallback. */
    fun imdbEpisodeUrl(imdbId: String, season: Int, episode: Int, tmdbId: Int?): String {
        val base = "https://www.imdb.com/title/$imdbId/season/$season/episode/$episode/"
        return if (tmdbId != null && tmdbId > 0) "$base?tmdb=$tmdbId&type=tv" else "$base?type=tv"
    }

    /** Normalize "series" to "tv". */
    fun normType(t: String?): String = if (t == "movie") "movie" else "tv"

    private fun param(url: String, key: String): String? =
        Regex("""[?&]$key=([^&#]+)""").find(url)?.groupValues?.get(1)
}

/** a federated embed-server resolver keyed by TMDB/IMDB id. */
class IndStreamProvider : MainAPI() {

    /** Detached scope for the fast-start background pull: survives loadLinks returning so the slower servers keep resolving. */
    private val fastStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Titles whose movie pre-warm already ran (or is running) - repeated detail-page visits must not re-resolve the whole. farm. */
    private val prewarmed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Farm keys (FastStartCache keys) whose background pre-warm is STILL resolving. */
    private val warmFarms = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "IndStream"
    // India flag in the search-provider picker (three-dot menu) and provider lists - MainAPI. lang defaults to "en" (UK.
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Home rows are TMDB-powered (the farm has no catalog of its own).
    override val mainPage
        get() = mainPageOf(
            Pair("trending|movie", "Trending Movies"),
            Pair("trending|tv", "Trending Series"),
            Pair("popular|movie", "Popular Movies"),
            Pair("popular|tv", "Popular Series"),
        )

    // Search: IMDB suggest + TMDB raced in parallel, fuzzy-ranked. Either source alone suffices.

    override suspend fun search(query: String): List<SearchResponse>? = coroutineScope {
        if (query.isBlank()) return@coroutineScope null
        val imdbJob = async { imdbSearchFuzzy(query) }
        val tmdbJob = async { withTimeoutOrNull(12000L) { TmdbService.search(query) }.orEmpty() }
        val imdbHits = imdbJob.await()
        val tmdbHits = tmdbJob.await()

        // Attach TMDB ids to top IMDB hits; bounded so a TMDB outage never fails search.
        val tmdbByImdb = withTimeoutOrNull(5000L) { mapImdbToTmdb(imdbHits.take(8)) }.orEmpty()
        // IMDB ratings for IMDB cards (same bounded budget; cached after the first search).
        val imdbRatings = withTimeoutOrNull(5000L) {
            ImdbService.fetchRatings(imdbHits.take(12).map { it.imdbId to it.type })
        }.orEmpty()
        // Each card shows its own source throughout: IMDB cards IMDB poster + rating, TMDB cards TMDB poster + rating.
        val cards = buildSearchCards(query, imdbHits, tmdbHits, tmdbByImdb, imdbRatings)
        if (cards.isEmpty()) return@coroutineScope null
        cards.map { it.response }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    /** One ranked card before poster backfill. */
    private data class RankedCard(
        val response: SearchResponse,
        val name: String,
        val year: Int?,
        val imdbRank: Int?,
        val tmdbRating: Double?,
    )

    /** Suggest across the query plus one typo respelling, merged by IMDB id. */
    private suspend fun imdbSearchFuzzy(query: String): List<ImdbService.ImdbHit> = coroutineScope {
        val queries = (listOf(query) + SearchRank.queryVariants(query, max = 1)).distinct()
        if (queries.size == 1) return@coroutineScope ImdbService.suggest(query)
        queries.map { q -> async { ImdbService.suggest(q) } }
            .awaitAll().flatten().distinctBy { it.imdbId }
    }

    /** Best-effort IMDB -> (tmdbId, type) map. Empty on TMDB outage. */
    private suspend fun mapImdbToTmdb(hits: List<ImdbService.ImdbHit>): Map<String, Pair<Int, String>> =
        coroutineScope {
            hits.map { h ->
                async<Pair<String, Pair<Int, String>>?> {
                    val found: Pair<Int, String>? = runCatching {
                        withTimeoutOrNull(4000L) { TmdbService.findByImdb(h.imdbId) }
                    }.getOrNull()
                    if (found != null) h.imdbId to found else null
                }
            }.awaitAll().filterNotNull().toMap()
        }

    /** Merge both sources, fuzzy-rank, dedupe by (title, year). TMDB ratings only order cards, never display on them. */
    private fun buildSearchCards(
        query: String,
        imdbHits: List<ImdbService.ImdbHit>,
        tmdbHits: List<TmdbService.TmdbItem>,
        tmdbByImdb: Map<String, Pair<Int, String>>,
        imdbRatings: Map<String, Double>,
    ): List<RankedCard> {
        val tmdbRatingByKey = tmdbHits.associateBy(
            { SearchRank.dedupeKey(it.name, it.year?.toIntOrNull()) }, { it.rating })
        val cards = ArrayList<RankedCard>()
        for (h in imdbHits) {
            val mapped = tmdbByImdb[h.imdbId]
            val type = mapped?.second?.let { TmdbUrlParser.normType(it) } ?: h.type
            val url = if (mapped != null) TmdbUrlParser.tmdbUrl(mapped.first, type, h.imdbId)
            else TmdbUrlParser.imdbUrl(h.imdbId, type, null)
            // IMDB card, IMDB metadata throughout.
            val rating = imdbRatings[h.imdbId]
            newCard(h.title, url, type, h.year, h.poster, rating)?.let {
                cards += RankedCard(it, h.title, h.year, h.rank, tmdbRatingByKey[SearchRank.dedupeKey(h.title, h.year)])
            }
        }
        val imdbKeys = imdbHits.map { SearchRank.dedupeKey(it.title, it.year) }.toSet()
        for (t in tmdbHits) {
            val id = t.tmdbId ?: continue
            if (t.name.isBlank()) continue
            // Skip TMDB rows already covered by an IMDB hit.
            if (imdbKeys.contains(SearchRank.dedupeKey(t.name, t.year?.toIntOrNull()))) continue
            val type = TmdbUrlParser.normType(t.type)
            newCard(t.name, TmdbUrlParser.tmdbUrl(id, type, t.imdbId), type, t.year?.toIntOrNull(), t.poster, t.rating)?.let {
                cards += RankedCard(it, t.name, t.year?.toIntOrNull(), null, t.rating)
            }
        }
        val seen = HashSet<String>()
        return cards.sortedWith(
            compareByDescending<RankedCard> { SearchRank.combined(query, it.name, it.imdbRank, it.tmdbRating) }
                .thenByDescending { it.year ?: 0 },
        ).filter { seen.add(SearchRank.dedupeKey(it.name, it.year)) }.take(12)
    }

    /** One search card. Type follows the resolved media kind. */
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

    // Main page (TMDB-powered rows).

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Catalog keys are "<category>|<media>" (see mainPage): category FIRST.
        val parts = request.data.split("|")
        val kind = parts[0].trim()                                       // "trending" | "popular".
        val tmdbType = if (parts.getOrNull(1)?.trim() == "tv") "tv" else "movie"
        val items = withTimeoutOrNull(12000L) {
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

    // Load (detail page). Open race: whichever source answers first with a title wins outright.

    override suspend fun load(url: String): LoadResponse? = coroutineScope {
        val ref = TmdbUrlParser.parseTitleUrl(url) ?: return@coroutineScope null
        val urlImdb = ref.imdbId

        // Missing TMDB id resolves via IMDB when that endpoint is alive.
        val foundTmdb = if (ref.tmdbId == null && urlImdb != null) {
            withTimeoutOrNull(6000L) { TmdbService.findByImdb(urlImdb) }
        } else null
        val tmdbId: Int? = ref.tmdbId ?: foundTmdb?.first
        val type: String = ref.type ?: foundTmdb?.second?.let { TmdbUrlParser.normType(it) } ?: "movie"

        val tmdbMetaJob = async {
            val id = tmdbId ?: return@async null
            withTimeoutOrNull(12000L) {
                TmdbService.fetchMeta(id, type)
                    ?: run { delay(1200L); TmdbService.fetchMeta(id, type) }
            }
        }
        val cineJob = async {
            val iid = urlImdb ?: tmdbId?.let { id ->
                runCatching { withTimeoutOrNull(6000L) { TmdbService.fetchMeta(id, type)?.imdbId } }
                    .getOrNull()?.takeIf { it.startsWith("tt") }
            } ?: return@async null
            val detail = withTimeoutOrNull(9000L) { ImdbService.fetchMeta(iid, type) }
            detail to iid
        }
        // Open race: the first source back with a title wins outright; the loser is cancelled.
        // A TMDB outage answers through IMDB with no waiting, and vice versa.
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

        val title: String
        val poster: String?
        val backdrop: String?
        val year: Int?
        val plot: String?
        val tags: List<String>?
        val score: Double?
        val cast: List<ActorData>?
        /** Watch time in seconds; only TMDB reports runtime, so an IMDB win leaves it empty. */
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
            cast = wonCine.cast
            duration = null
        }
        val dataUrl = if (tmdbId != null) TmdbUrlParser.tmdbUrl(tmdbId, type, resolvedImdb)
        else TmdbUrlParser.imdbUrl(resolvedImdb ?: return@coroutineScope null, type, null)
        val isMovie = type == "movie"

        if (isMovie) prewarm(tmdbId ?: 0, resolvedImdb, type)

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

        val episodes = seriesEpisodes(tmdbId, resolvedImdb, type)
        if (episodes.isEmpty()) return@coroutineScope null
        val epList = episodes.map { ep ->
            val epUrl = if (tmdbId != null) TmdbUrlParser.tmdbEpisodeUrl(tmdbId, resolvedImdb, ep.seasonNumber, ep.episodeNumber)
            else TmdbUrlParser.imdbEpisodeUrl(resolvedImdb ?: "", ep.seasonNumber, ep.episodeNumber, null)
            newEpisode(epUrl) {
                this.name = ep.name
                this.season = ep.seasonNumber
                this.episode = ep.episodeNumber
                this.description = ep.overview
                ep.released?.let { this.addDate(it) }
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

    /** Episode rows from whichever source answers first; the loser is cancelled. */
    private suspend fun seriesEpisodes(
        tmdbId: Int?,
        imdbId: String?,
        type: String,
    ): List<TmdbService.TmdbEpisode> = coroutineScope {
        val tmdbJob = async {
            if (tmdbId == null || tmdbId <= 0) return@async emptyList()
            val seasons = withTimeoutOrNull(15000L) {
                TmdbService.fetchTvSeasons(tmdbId)
                    .ifEmpty { delay(1200L); TmdbService.fetchTvSeasons(tmdbId) }
            }.orEmpty()
            if (seasons.isEmpty()) return@async emptyList()
            seasons.map { season ->
                async { withTimeoutOrNull(10000L) { TmdbService.fetchSeasonPublic(tmdbId, season) } }
            }.awaitAll().filterNotNull().flatten()
        }
        val cineJob = async {
            if (imdbId == null) return@async emptyList()
            val raw = withTimeoutOrNull(9000L) { ImdbService.fetchSeriesRaw(imdbId) }
            ImdbService.parseCinemetaEpisodes(raw)
        }
        // First non-empty list wins outright.
        val won = select<List<TmdbService.TmdbEpisode>> {
            tmdbJob.onAwait { if (it.isNotEmpty()) it else cineJob.await() }
            cineJob.onAwait { if (it.isNotEmpty()) it else tmdbJob.await() }
        }
        tmdbJob.cancel()
        cineJob.cancel()
        won.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
    }

    // Load links (the resolver). Correlation id so every line of ONE Play tap strings together in logcat (live-window.
// debug, ): "TAP#7 …".
    private val tapSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** A farm resolution already running for a cacheKey. */
    private class FarmHandle(val key: String, val job: kotlinx.coroutines.Job)
    private val inFlightFarm = java.util.concurrent.atomic.AtomicReference<FarmHandle?>(null)

    /** Live-window. */
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

    /** URL id first, then cached meta, then a lookup. Never throws. */
    private suspend fun imdbWithRetry(
        first: suspend () -> String?,
        tmdbId: Int,
        type: String,
        urlImdb: String?,
    ): String? {
        urlImdb?.takeIf { it.startsWith("tt") }?.let { return it }
        runCatching { withTimeoutOrNull(8000L) { first() } }.getOrNull()
            ?.takeIf { it.startsWith("tt") }?.let { return it }
        if (tmdbId <= 0) return null
        return runCatching { withTimeoutOrNull(8000L) { TmdbService.fetchMeta(tmdbId, type)?.imdbId } }
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

        // Season/episode ride in either URL form; movies default to -1.
        val (season, episode) = TmdbUrlParser.parseSeasonEpisode(data)

        val needsImdb = ServerFarm.allServers.any { it.idType == ServerIdType.IMDB }
        // Warmed detail cache answers instantly; a TMDB outage just yields null here.
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

        // Instant replay: this title's farm already resolved (a prior play, or a background pull.
        val liveFarm = inFlightFarm.get()?.takeIf { it.key == cacheKey && it.job.isActive }?.job
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
            // Fallback subtitles ARE the subtitle provider (): the same title-keyed OpenSubtitles set every play gets.
            // Start the fetch immediately (don't block the emit on meta resolution) and await it before returning.
            val subsJob = fastStartScope.async {
                val imdb = imdbWithRetry({ metaDeferred.await()?.imdbId }, tmdbId, type, urlImdb)
                runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                    .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
            }
            // rule: if the background warm for THIS title is still resolving, the replay above was only a PARTIAL list.
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
                // Final diff: the last batch may have merged after our last poll.
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
            // Let subtitle tracks land before returning — the push IS the delivery; after return the app drops it.
            subsJob.await()
            return emitted.get() > 0
        }

        // Live window (): the whole farm launches at once and EVERY stream is pushed to the player the.
        val pushedUrls = Collections.synchronizedSet(HashSet<String>())
        // Window gate: once loadLinks has returned the player ignores pushes, so late arrivals land in FastStartCache only.
        val windowOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        val loadStartMs = System.currentTimeMillis()

        // A farm for this EXACT key already in flight (this provider's live path, or a movie pre-warm)?
        run {
            val running: kotlinx.coroutines.Job? =
                inFlightFarm.get()?.takeIf { it.key == cacheKey && it.job.isActive }?.job
                    ?: warmFarms[cacheKey]?.takeIf { it.isActive }
            if (running != null) {
                android.util.Log.i("IndStream", "TAP#$tap joining in-flight farm for $cacheKey (no second launch)")
                // Subtitles start fetching immediately, in parallel with the joined farm tail.
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
                        return false
                    }
                }
                if (emitted.get() > 0) {
                    // subsJob already has the fetch in flight; just ensure it lands before returning.
                }
                subsJob.await()
                android.util.Log.i("IndStream", "TAP#$tap joined farm -> ${emitted.get()} live links " +
                    "in ${System.currentTimeMillis() - loadStartMs}ms")
                return emitted.get() > 0
            }
        }

        val farmDone = fastStartScope.async {
            try {
                StreamEngine.resolveRealtime(tmdbId, type, season, episode, urlImdb, imdbIdProvider = { imdbDeferred.await() }) { sid, streams ->
                    // Subtitle-only carriers are dead weight now (server subs are not used - fallback is the provider): links only.
                    val fresh = streams.filter { it.url.isNotBlank() && pushedUrls.add(it.url) }
                    if (fresh.isEmpty()) return@resolveRealtime
                    StreamEngine.FastStartCache.put(cacheKey, fresh)
                    if (!windowOpen.get()) return@resolveRealtime
                    // LIVE push (the whole point of staying alive): each batch is emitted the instant it resolves, in arrival order - the.
                    android.util.Log.i("IndStream", "TAP#$tap +${fresh.size} from $sid at " +
                        "${System.currentTimeMillis() - loadStartMs}ms (window open)")
                    StreamEngine.emit(
                        fresh,
                        { emitted.incrementAndGet(); callback(it) },
                        originalLangNow(),
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("IndStream", "live resolve failed: ${t.message}")
            }
        }
        // F6: OWNERSHIP of this title's farm is published so a re-tap that catches a PARTIAL cache can still tail the running.
        run {
            val handle = FarmHandle(cacheKey, farmDone)
            inFlightFarm.set(handle)
            farmDone.invokeOnCompletion { inFlightFarm.compareAndSet(handle, null) }
        }

        // Subtitles start fetching THE MOMENT the user taps play - not after the first stream resolves - so tracks are
        // ready before/at playback start. Batch-priority order (English + original + all Indian langs first, then foreign).
        val subsJob = fastStartScope.async {
            val imdb = imdbWithRetry({ imdbDeferred.await() }, tmdbId, type, urlImdb)
            runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
        }

        // Keep loadLinks ALIVE (bounded): the change-server list only grows while this coroutine runs.
        val firstStreamArrived = CompletableDeferred<Unit>()
        val arrivalWatcher = fastStartScope.launch {
            while (emitted.get() == 0 && !farmDone.isCompleted) delay(50)
            firstStreamArrived.complete(Unit)
        }

        if (withTimeoutOrNull(StreamEngine.FAST_START_MAX_MS) { firstStreamArrived.await() } == null) {
            android.util.Log.w("IndStream", "TAP#$tap farm produced no streams in ${StreamEngine.FAST_START_MAX_MS}ms " +
                "(detached farm keeps filling FastStartCache; next tap replays + tails it)")
            arrivalWatcher.cancel()
            windowOpen.set(false)
            // Subs still get a chance to land even if no stream arrived.
            subsJob.await()
            return false
        }

        // LIVE_FILL window: hold until the farm resolves or the cap expires, whichever first - every server that answers.
        withTimeoutOrNull(StreamEngine.LIVE_FILL_MS) { farmDone.await() }
        // Window closing: the app stops recording pushes the moment we return, so late farm arrivals now land in.
// FastStartCache only.
        windowOpen.set(false)
        // Let the subtitle tracks land BEFORE returning - the push IS the delivery; after the return the app drops it (bug.
        subsJob.await()

        // Return → the app auto-starts on whichever link its own quality profile ranks first.
        android.util.Log.i("IndStream", "TAP#$tap live window: tmdb=$tmdbId/$type s=$season e=$episode -> " +
            "${emitted.get()} links live in ${System.currentTimeMillis() - loadStartMs}ms (farm=${if (farmDone.isCompleted) "done" else "capped at LIVE_FILL"})")
        return emitted.get() > 0
    }

    /** Fire-and-forget farm resolution into StreamEngine. FastStartCache while the detail page is open so a later Play tap. */
    private fun prewarm(tmdbId: Int, imdbId: String?, type: String) {
        val key = if (tmdbId > 0) StreamEngine.FastStartCache.key(tmdbId, type, -1, -1)
        else "imdb:${imdbId ?: "none"}|$type|-1|-1"
        if (!prewarmed.add(key)) return
        if (StreamEngine.FastStartCache.get(key) != null) return
        val job = fastStartScope.launch {
            runCatching {
                // load() just warmed TmdbService's cache: the lazy lookup below is an instant cache hit for IMDB-keyed servers.
                StreamEngine.resolveRealtime(tmdbId, type, -1, -1, imdbId, imdbIdProvider = { imdbId }) { _, streams ->
                    if (streams.isNotEmpty()) StreamEngine.FastStartCache.put(key, streams)
                }
            }.onFailure { android.util.Log.w("IndStream", "prewarm failed: ${it.message}") }
        }
        warmFarms[key] = job
        job.invokeOnCompletion { warmFarms.remove(key, job) }
    }

    /** Race user key against the built-in stack: both fire at once, first tracks win. */
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

