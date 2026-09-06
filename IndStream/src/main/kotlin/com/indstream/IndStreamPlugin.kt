package com.indstream

import com.lagradost.cloudstream3.*
/**

 * FILE: IndStreamPlugin.kt — the IndStream plugin (entry + TMDB catalog provider).
 *
 *  - [IndStream]          plugin entrypoint (@CloudstreamPlugin).
 *  - [IndStreamProvider]  TMDB-keyed MainAPI: no catalog of its own — every
 *                         title is resolved on demand against TMDB metadata,
 *                         then handed to the resolution engine in
 *                         StreamEngine.kt.
 *
 * Shared services live in CoreServices.kt; the VidLink stream source in
 * VidLinkSource.kt.
 */

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.util.Collections
import java.util.HashSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registers the IndStream provider with CloudStream.
 */
@CloudstreamPlugin
class IndStream : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IndStreamProvider())
    }
}

/**
 * Pure TMDB URL parser, extractable from [IndStreamProvider] for unit testing.
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
 * IndStream — a federated embed-server resolver keyed by TMDB/IMDB id.
 *
 * The plugin has no catalog of its own: search and metadata come from TMDB, and
 * every title is resolved on demand by racing dozens of independent HLS/DASH
 * embed servers (see [ServerFarm]) that all accept TMDB/IMDB ids. The resolver
 * measures real stream throughput and emits links fastest-first, so playback
 * feels like an official OTT app without depending on any single backend.
 */
class IndStreamProvider : MainAPI() {

    /**
     * Detached scope for the fast-start background pull: survives [loadLinks]
     * returning so the slower servers keep resolving into [StreamEngine.FastStartCache]
     * (and, during the fill window, feed the player) after playback has begun.
     */
    private val fastStartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Titles whose movie pre-warm already ran (or is running) — repeated
     *  detail-page visits must not re-resolve the whole farm. */
    private val prewarmed = java.util.Collections.synchronizedSet(HashSet<String>())

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "IndStream"
    // India flag in the search-provider picker (three-dot menu) and provider
    // lists — MainAPI.lang defaults to "en" (UK flag) unless overridden.
    // CloudStream's SubtitleHelper maps "hi" -> IN.
    override var lang = "hi"
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

        // Movie pre-warm (fast-start tier 2): resolve the whole farm while the
        // user reads the detail page, landing links in FastStartCache so the
        // Play tap replays instantly. TV episodes can't be keyed ahead (their
        // season/episode isn't known here) — they keep the fast-start path.
        if (isMovie) prewarm(tmdbId, imdbId, type)

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
            android.util.Log.w("IndStream", "loadLinks: not a TMDB url: $data")
            return false
        }
        val (tmdbId, type) = tmdb

        // Episodes carry season/episode in the URL; movies/episodes without it default to -1.
        val epMatch = episodeUrl.find(data)
        val season = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: -1
        val episode = epMatch?.groupValues?.get(3)?.toIntOrNull() ?: -1

        val needsImdb = ServerFarm.allServers.any { it.idType == ServerIdType.IMDB }
        // No serial TMDB round-trip here: load() already warmed TmdbService's
        // detail cache for this exact (tmdbId, type), so fetchMeta below returns
        // instantly in the common case. On a cold cache (deep link straight to
        // an episode URL) it costs ≤3s — for the FIRST fast servers we simply
        // start without it and let IMDB-keyed ones fill in lazily (their slots
        // are behind the TMDB-keyed Hindi servers anyway).
        // Atomic holder so the concurrent TMDB lookup can publish the original
        // language when it lands (usually instantly — load() warms the cache),
        // while every emit site keeps a non-blocking snapshot read.
        val originalLangRef = java.util.concurrent.atomic.AtomicReference<String?>()
        val metaDeferred = fastStartScope.async {
            val d = withTimeoutOrNull(3000L) { TmdbService.fetchMeta(tmdbId, type) }
            originalLangRef.set(d?.originalLanguage)
            d
        }
        val originalLangNow: () -> String? = { originalLangRef.get() }
        val cacheKey = StreamEngine.FastStartCache.key(tmdbId, type, season, episode)

        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val coveredSubLangs = Collections.synchronizedSet(HashSet<String>())
        // Gate for the fast-start fill window: links are pushed to the player only
        // while this is true. Once loadLinks returns, it flips to false so the
        // still-running background pull stops touching the (now-closed) player and
        // only lands results in FastStartCache for instant replay.
        val linksAccepted = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstReady = CompletableDeferred<Unit>()

        // Instant replay: if a previous play already pulled this title (or the
        // background pull from a prior tap is still warm), fire the cached links
        // straight to the player so playback starts with zero head.
        val cached = StreamEngine.FastStartCache.get(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            val langs = StreamEngine.emit(cached, { emitted.incrementAndGet(); callback(it) }, subtitleCallback, originalLangNow())
            coveredSubLangs.addAll(langs)
            android.util.Log.i("IndStream", "loadLinks: instant replay from cache, ${cached.size} streams for tmdb=$tmdbId/$type")
            topUpSubtitles(metaDeferred.await()?.imdbId, season, episode, originalLangNow(), coveredSubLangs, subtitleCallback)
            return emitted.get() > 0
        }

        // Fast-start (user goal: pick the fastest server in real time, hit/start
        // the stream, then pull the rest in the background). The farm is resolved
        // in a detached scope so it survives this function returning: the first
        // server's link is emitted the instant it's ready and playback begins,
        // a short fill window grabs a few more fast sources, then we return while
        // the slower servers keep resolving into FastStartCache.
        //
        // IMDB-keyed servers resolve the id lazily through a SHARED deferred
        // (dedup: at most one find/episode lookup even when several servers ask).
        // TMDB-keyed servers never wait for it — the farm launches with zero
        // serial head.
        val imdbDeferred = fastStartScope.async {
            if (needsImdb) metaDeferred.await()?.imdbId else null
        }
        // Buffer every server's streams here instead of emitting them the instant
        // they resolve. A server that resolves first is NOT necessarily the one
        // that starts the video fastest, so we collect candidates across the settle
        // window and let emit() pick the best-starting one as the FIRST link.
        val buffered = Collections.synchronizedList(mutableListOf<StreamEngine.RawStream>())
        fastStartScope.launch {
            try {
                StreamEngine.resolveRealtime(tmdbId, type, season, episode, imdbIdProvider = { imdbDeferred.await() }) { _, streams ->
                    if (streams.isNotEmpty()) {
                        buffered.addAll(streams)
                        StreamEngine.FastStartCache.put(cacheKey, buffered.toList())
                        // Signal "we have something to pick" so the settle window can begin.
                        if (linksAccepted.get() && emitted.get() == 0) firstReady.complete(Unit)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("IndStream", "fast-start background resolve failed: ${t.message}")
            } finally {
                linksAccepted.set(false)
            }
        }

        // Settle briefly so a few genuinely-fast servers (MovieBox, VidLink, …) can
        // report before we commit to a link. We do NOT emit-on-first-arrival: the
        // link chosen is the highest startupScore one from everything gathered.
        // The hard cap guarantees we never hang past FAST_START_MAX_MS even if the
        // whole farm is slow/dead.
        withTimeoutOrNull(StreamEngine.FAST_START_MAX_MS) {
            firstReady.await()
            if (linksAccepted.get()) delay(StreamEngine.FAST_START_FILL_MS)
        }
        linksAccepted.set(false)

        // Emit the gathered streams ranked best-first. emit() orders by startupScore
        // (history + fresh probe + soft language), so the player's first/auto-played
        // link is the one that actually begins playback soonest.
        val started = if (buffered.isNotEmpty()) {
            val langs = StreamEngine.emit(
                buffered.toList(),
                { emitted.incrementAndGet(); callback(it) },
                subtitleCallback,
                originalLangNow(),
            )
            coveredSubLangs.addAll(langs)
            true
        } else false

        android.util.Log.i("IndStream", "loadLinks: tmdb=$tmdbId/$type s=$season e=$episode -> started=$started, $emitted links emitted")
        if (started) {
            // Awaiting is safe: metaDeferred is bounded by its own 3s timeout.
            topUpSubtitles(imdbDeferred.await(), season, episode, originalLangNow(), coveredSubLangs, subtitleCallback)
        }
        return started
    }

    /** Fire-and-forget farm resolution into [StreamEngine.FastStartCache] while
     *  the detail page is open so a later Play tap replays instantly. Never
     *  blocks [load], swallows all errors, and runs at most once per title. */
    private fun prewarm(tmdbId: Int, imdbId: String?, type: String) {
        val key = StreamEngine.FastStartCache.key(tmdbId, type, -1, -1)
        if (!prewarmed.add(key)) return
        if (StreamEngine.FastStartCache.get(key) != null) return
        fastStartScope.launch {
            runCatching {
                // load() just warmed TmdbService's cache: the lazy lookup below
                // is an instant cache hit for IMDB-keyed servers.
                StreamEngine.resolveRealtime(tmdbId, type, -1, -1, imdbIdProvider = { imdbId }) { _, streams ->
                    if (streams.isNotEmpty()) StreamEngine.FastStartCache.put(key, streams)
                }
            }.onFailure { android.util.Log.w("IndStream", "prewarm failed: ${it.message}") }
        }
    }

    /** Subtitle top-up (user spec Sept 2026): take what the servers give; when a
     *  wanted language (Hindi/English/original) is missing, fetch it from the
     *  OpenSubtitles fallback — budgeted so it can never hold up playback, and
     *  dropped silently when it takes too long. Shared by the instant-replay and
     *  fast-start paths. */
    private suspend fun topUpSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        originalLang: String?,
        coveredSubLangs: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val missing = SubtitleFallback.missingLanguages(
            coveredSubLangs, SubtitleFallback.desiredLanguages(originalLang),
        )
        if (missing.isNotEmpty()) {
            SubtitleFallback.fetch(imdbId, season, episode, missing)
                .forEach { subtitleCallback(it) }
        }
    }
}

