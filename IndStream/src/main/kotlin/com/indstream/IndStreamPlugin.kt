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
        // MovieBox bearer pre-warm: the x-user token lives for hours, so one
        // background GET now removes a serial round-trip (~0.5-1s) from the
        // first resolve of the session — one more chunk of the 5-7s startup.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { StreamEngine.prewarmMovieBoxToken() }
        }
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
 * pushes EVERY server that answers straight into the player's live change-server
 * list (arrival order — the USER's quality/source profile decides what
 * auto-plays), so playback fills instantly while the list keeps growing.
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

    /** Farm keys (FastStartCache keys) whose background pre-warm is STILL
     *  resolving. A Play tap that replays a PARTIAL warm registers its key
     *  here as a waiter target, so late warm arrivals still reach the live
     *  change-server list instead of only the cache (loadLinks must stay
     *  alive for the app to record pushes — the CSX/CineStream rule). */
    private val warmFarms = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
        // an episode URL) it costs ≤3s — the IMDB-keyed servers wait on it
        // lazily while the TMDB-keyed farm already runs.
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
        val imdbDeferred = fastStartScope.async {
            if (needsImdb) metaDeferred.await()?.imdbId else null
        }
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)

        // Instant replay: this title's farm already resolved (a prior play, or a
        // background pull from the detail page that finished meanwhile) — emit the
        // FULL server list instantly so the player opens with everything. No new
        // probes: labels come from the cached quality tags. Emission order is the
        // arrival order the list was built in; the app's own quality-profile
        // (the USER's priority settings) decides what auto-plays.
        val cached = StreamEngine.FastStartCache.get(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            val replayPushed = Collections.synchronizedSet(HashSet<String>())
            cached.forEach { replayPushed += it.url }
            StreamEngine.emit(
                cached,
                { emitted.incrementAndGet(); callback(it) },
                originalLangNow(),
                probeManifests = false,
            )
            // Fallback subtitles ARE the subtitle provider (user spec Sept 2026):
            // the same title-keyed OpenSubtitles set every play gets — sync-safe
            // after any in-player server switch.
            topUpSubtitles(metaDeferred.await()?.imdbId, season, episode, originalLangNow(), subtitleCallback)
            // CSX rule: if the background warm for THIS title is still resolving,
            // the replay above was only a PARTIAL list — return now and the app
            // drops every later arrival from the live list. So poll the
            // still-growing FastStartCache and forward newly-landed servers
            // straight to the player (already-fetched — zero new probes) until
            // the farm finishes or the live window caps out.
            val warm = warmFarms[cacheKey]
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
            android.util.Log.i("IndStream", "loadLinks: replay from warm cache (+live tail: ${if (warm != null) "yes" else "no"}), ${emitted.get()} streams for tmdb=$tmdbId/$type")
            return emitted.get() > 0
        }

        // Live window (user spec Sept 2026 rewrite #2): the whole farm
        // launches at once and EVERY stream is pushed to the player the
        // moment it lands — arrival order, no hold, no winner selection.
        // loadLinks STAYS ALIVE until the farm finishes or [LIVE_FILL_MS]
        // elapses: the app records callback pushes only while this coroutine
        // is running, so returning early froze the change-server list at the
        // first 1-2 arrivals. Everything that resolves during the window is
        // switchable WITHOUT re-entering the player; the full list keeps
        // landing in FastStartCache so a re-open replays it instantly.
        val pushedUrls = Collections.synchronizedSet(HashSet<String>())
        // Window gate: once loadLinks has returned the player ignores pushes,
        // so late arrivals land in FastStartCache only — no wasted
        // manifest-probes against streams nobody will watch this session.
        val windowOpen = java.util.concurrent.atomic.AtomicBoolean(true)
        val loadStartMs = System.currentTimeMillis()

        val farmDone = fastStartScope.async {
            try {
                StreamEngine.resolveRealtime(tmdbId, type, season, episode, imdbIdProvider = { imdbDeferred.await() }) { _, streams ->
                    // Subtitle-only carriers are dead weight now (server subs are
                    // not used — fallback is the provider): links only.
                    val fresh = streams.filter { it.url.isNotBlank() && pushedUrls.add(it.url) }
                    if (fresh.isEmpty()) return@resolveRealtime
                    StreamEngine.FastStartCache.put(cacheKey, fresh)
                    if (!windowOpen.get()) return@resolveRealtime
                    // LIVE push (the whole point of staying alive): each batch
                    // is emitted the instant it resolves, in arrival order —
                    // the player's own quality profile decides what plays.
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

        // Keep loadLinks ALIVE (bounded): the change-server list only grows
        // while this coroutine runs. First-arrival detection bounds the
        // "nothing works" case at FAST_START_MAX_MS; the LIVE_FILL cap below
        // (not the first arrival) bounds the whole window.
        val firstStreamArrived = CompletableDeferred<Unit>()
        val arrivalWatcher = fastStartScope.launch {
            while (emitted.get() == 0 && !farmDone.isCompleted) delay(50)
            firstStreamArrived.complete(Unit)
        }

        if (withTimeoutOrNull(StreamEngine.FAST_START_MAX_MS) { firstStreamArrived.await() } == null) {
            android.util.Log.w("IndStream", "loadLinks: farm produced no streams in ${StreamEngine.FAST_START_MAX_MS}ms")
            arrivalWatcher.cancel()
            windowOpen.set(false)
            return false
        }

        // Fallback subtitles: the OpenSubtitles provider IS the subtitle
        // source (user spec) — fired the moment the stream starts, in
        // PARALLEL with the fill window, and AWAITED before the return:
        // subtitleCallback pushes are only recorded while loadLinks is alive
        // (the same job-liveness rule the change-server list rides on; a
        // detached post-return push is silently dropped). Self-bounded:
        // ≤2.5s IMDB wait + the provider's 13s fetch budget (90s LIVE_FILL
        // window leaves both pushes ample room to land before loadLinks ends).
        val subsJob = fastStartScope.async {
            val imdb = runCatching { withTimeoutOrNull(2500L) { imdbDeferred.await() } }.getOrNull()
            runCatching { topUpSubtitles(imdb, season, episode, originalLangNow(), subtitleCallback) }
                .onFailure { android.util.Log.w("IndStream", "fallback subs failed: ${it.message}") }
        }

        // LIVE_FILL window: hold until the farm resolves or the cap expires,
        // whichever first — every server that answers inside the window is
        // pushed live (above) and stays tappable in the change-server list.
        withTimeoutOrNull(StreamEngine.LIVE_FILL_MS) { farmDone.await() }
        // Window closing: the app stops recording pushes the moment we return,
        // so late farm arrivals now land in FastStartCache only.
        windowOpen.set(false)
        // Let the subtitle tracks land BEFORE returning — the push IS the
        // delivery; after the return the app drops it (bug: first play came
        // up subtitle-less while this fetch ran detached past the return).
        subsJob.await()

        // Return → the app auto-starts on whichever link its own quality
        // profile ranks first. The farm has (usually) already finished inside
        // the window; anything straggling past it still lands in
        // FastStartCache for the full-list replay on the next open.
        android.util.Log.i("IndStream", "loadLinks: tmdb=$tmdbId/$type s=$season e=$episode -> " +
            "${emitted.get()} links live in ${System.currentTimeMillis() - loadStartMs}ms (farm=${if (farmDone.isCompleted) "done" else "capped at LIVE_FILL"})")
        return emitted.get() > 0
    }

    /** Fire-and-forget farm resolution into [StreamEngine.FastStartCache] while
     *  the detail page is open so a later Play tap replays instantly. Never
     *  blocks [load], swallows all errors, and runs at most once per title. The
     *  job is registered in [warmFarms] so a Play tap that catches a PARTIAL
     *  warm stays alive and forwards each later arrival into the live
     *  change-server list instead of freezing at the partial set. */
    private fun prewarm(tmdbId: Int, imdbId: String?, type: String) {
        val key = StreamEngine.FastStartCache.key(tmdbId, type, -1, -1)
        if (!prewarmed.add(key)) return
        if (StreamEngine.FastStartCache.get(key) != null) return
        val job = fastStartScope.launch {
            runCatching {
                // load() just warmed TmdbService's cache: the lazy lookup below
                // is an instant cache hit for IMDB-keyed servers.
                StreamEngine.resolveRealtime(tmdbId, type, -1, -1, imdbIdProvider = { imdbId }) { _, streams ->
                    if (streams.isNotEmpty()) StreamEngine.FastStartCache.put(key, streams)
                }
            }.onFailure { android.util.Log.w("IndStream", "prewarm failed: ${it.message}") }
        }
        warmFarms[key] = job
        job.invokeOnCompletion { warmFarms.remove(key, job) }
    }

    /** Subtitle provider (user spec Sept 2026 rewrite): server captions are
     *  NOT used — the OpenSubtitles fallback IS the subtitle provider. Runs
     *  when the stream starts (and on every replay), fetching the wanted
     *  languages (Hindi/English/original) — budgeted so it can never hold up
     *  playback, deduped by SubtilesProvider's per-title cache so repeat
     *  plays and instant replays never re-download. Title-keyed to a standard
     *  cut, so tracks stay in sync after any in-player server switch. */
    private suspend fun topUpSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        originalLang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val wanted = SubtilesProvider.desiredLanguages(originalLang)
        SubtilesProvider.fetch(imdbId, season, episode, wanted).forEach {
            subtitleCallback(SubtitleFile(it.lang, it.url))
        }
    }
}

