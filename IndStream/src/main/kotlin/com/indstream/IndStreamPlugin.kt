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
        // Subtitle dedupe keys SHARED by every emit call of this loadLinks
        // (first batch + all trickle batches + the fallback top-up): one
        // (canonicalLang, url) pair is emitted exactly once per play, which is
        // what makes it safe for trickle batches to carry subtitle tracks.
        val sharedSubKeys = Collections.synchronizedSet(HashSet<String>())
        // Gate for the fast-start fill window: links are pushed to the player only
        // while this is true. Once loadLinks returns, it flips to false so the
        // still-running background pull stops touching the (now-closed) player and
        // only lands results in FastStartCache for instant replay.
        val linksAccepted = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstReady = CompletableDeferred<Unit>()
        // Timestamp (ms) when the first batch actually arrived — published by
        // the resolveRealtime onBatch closure. The 1.5s settle hold is
        // measured from THIS point, not from the moment loadLinks started
        // awaiting (which would include the user's tap-to-network latency).
        val firstArrivalMs = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)

        // Instant replay: if a previous play already pulled this title (or the
        // background pull from a prior tap is still warm), fire the cached links
        // straight to the player so playback starts with zero head. Same
        // winner-first split as the fresh-start path so frame-1 quality
        // matches: the best live signal (cached fields) wins, the rest of the
        // cache fills the server list.
        val cached = StreamEngine.FastStartCache.get(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            val winner = StreamEngine.pickAutoPlay(cached)
            val winUrl = winner?.url
            val rest = if (winUrl != null) cached.filter { it.url != winUrl } else cached
            if (winner != null) {
                val langs = StreamEngine.emit(listOf(winner), { emitted.incrementAndGet(); callback(it) },
                    subtitleCallback, originalLangNow(), subDedupeKeys = sharedSubKeys)
                coveredSubLangs.addAll(langs)
            }
            if (rest.isNotEmpty()) {
                val langs = StreamEngine.emit(rest, { emitted.incrementAndGet(); callback(it) },
                    subtitleCallback, originalLangNow(), subDedupeKeys = sharedSubKeys)
                coveredSubLangs.addAll(langs)
            }
            android.util.Log.i("IndStream", "loadLinks: instant replay from cache, ${cached.size} streams for tmdb=$tmdbId/$type")
            topUpSubtitles(metaDeferred.await()?.imdbId, season, episode, originalLangNow(), coveredSubLangs, sharedSubKeys, subtitleCallback)
            return emitted.get() > 0
        }

        // Fast-start (user spec Sept 2026, live-probe model):
        //   1. Launch the whole farm (MAX_CONCURRENT=16, all servers in parallel)
        //      and buffer arrivals in a synchronizedList.
        //   2. Wait for the FIRST batch (firstReady) — the user has already paid
        //      network + resolve time; the FAST_START_SETTLE_MS hold after that
        //      is where the live sample is collected. Hold = 1.5s by default
        //      (user product range 1–2s). Hard-capped by FAST_START_MAX_MS.
        //   3. During the hold, probeCandidates runs in parallel against every
        //      buffered stream (TTFB, master bestHeight, audioPriority). The
        //      probe's master fetch is stored into RawStream.inlineManifest so
        //      emit() skips its own re-fetch.
        //   4. At hold end: pickAutoPlay selects the best ≥720 candidate (or
        //      best available if nothing meets the bar). Emit ONLY that link →
        //      the player auto-plays it and the list shows it.
        //   5. The remaining buffered streams + every later arrival go to the
        //      server list via a 25s trickle, deduped by url (emittedUrls) so
        //      the winner never re-appears and FastStartCache stays consistent.
        //
        // IMDB-keyed servers resolve the id lazily through a SHARED deferred
        // (dedup: at most one find/episode lookup even when several servers ask).
        // TMDB-keyed servers never wait for it — the farm launches with zero
        // serial head. This is Phase 0/Track B from the user spec; already
        // implemented in the existing code path below.
        val imdbDeferred = fastStartScope.async {
            if (needsImdb) metaDeferred.await()?.imdbId else null
        }
        val buffered = Collections.synchronizedList(mutableListOf<StreamEngine.RawStream>())
        fastStartScope.launch {
            try {
                StreamEngine.resolveRealtime(tmdbId, type, season, episode, imdbIdProvider = { imdbDeferred.await() }) { _, streams ->
                    if (streams.isNotEmpty()) {
                        buffered.addAll(streams)
                        StreamEngine.FastStartCache.put(cacheKey, buffered.toList())
                        // Publish the actual first-arrival timestamp so the
                        // settle hold is measured from network-arrival time,
                        // not from when loadLinks started awaiting.
                        firstArrivalMs.compareAndSet(Long.MAX_VALUE, System.currentTimeMillis())
                        // Signal "we have something to play" the instant the
                        // first server lands — the main thread starts the hold
                        // timer here, long before the rest of the farm.
                        if (linksAccepted.get() && emitted.get() == 0) firstReady.complete(Unit)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("IndStream", "fast-start background resolve failed: ${t.message}")
            } finally {
                linksAccepted.set(false)
            }
        }

        // PHASE 1 — hold + probe: wait for the FIRST server's batch, then
        // hold until (first-arrival + FAST_START_SETTLE_MS) so live probes
        // can finish — they start at firstReady completion and run in
        // parallel with the remaining hold time. The hold is measured from
        // the batch's ACTUAL arrival (firstArrivalMs, published by onBatch),
        // never from the tap — a slow first server must not shorten the
        // sample window. Total wait is still bounded by FAST_START_MAX_MS
        // (measured from the tap) so a dead farm surfaces "no link found".
        val playT0 = System.currentTimeMillis()
        withTimeoutOrNull(StreamEngine.FAST_START_MAX_MS) { firstReady.await() }
        if (buffered.isEmpty()) {
            android.util.Log.w("IndStream", "loadLinks: farm produced no streams in ${System.currentTimeMillis() - playT0}ms")
            return false
        }
        // Hold deadline = first-arrival + SETTLE, clamped by the overall
        // FAST_START_MAX_MS deadline (probeStartedAt is ~first-arrival + ε).
        val probeStartedAt = System.currentTimeMillis()
        val settleEnd = (firstArrivalMs.get() + StreamEngine.FAST_START_SETTLE_MS)
            .coerceAtMost(playT0 + StreamEngine.FAST_START_MAX_MS)
        val settleRemaining = (settleEnd - probeStartedAt).coerceIn(0L, StreamEngine.FAST_START_SETTLE_MS)
        // Probes overlap the hold; if the hold is already spent (very late
        // first batch deep into the cap) skip probing and rank on arrival
        // data alone.
        val probeDeferred = if (settleRemaining > 0L) {
            fastStartScope.async { StreamEngine.probeCandidates(buffered.toList()) }
        } else null
        if (settleRemaining > 0L) kotlinx.coroutines.delay(settleRemaining)
        val probed = probeDeferred?.let { d ->
            runCatching { d.await() }.getOrElse { t ->
                android.util.Log.w("IndStream", "probeCandidates failed: ${t.message}")
                null
            }
        } ?: buffered.toList()
        android.util.Log.i("IndStream", "loadLinks: settled ${System.currentTimeMillis() - firstArrivalMs.get()}ms after first arrival " +
            "(held ${System.currentTimeMillis() - playT0}ms total, probed=${probed.size} candidates)")

        // PHASE 2 — pick + emit auto-play winner. Strict eligibility per
        // pickAutoPlay: ≥720 known > adaptive-unknown > everything else.
        val winner = StreamEngine.pickAutoPlay(probed)
        val autoPlayUrl = winner?.url
        var started = winner != null
        if (winner != null) {
            val langs = StreamEngine.emit(
                listOf(winner),
                { emitted.incrementAndGet(); callback(it) },
                subtitleCallback,
                originalLangNow(),
                subDedupeKeys = sharedSubKeys,
            )
            coveredSubLangs.addAll(langs)
        } else if (probed.isNotEmpty()) {
            // No url-bearing candidate (subtitle-only carriers only): still
            // run the emit pass so caption tracks reach the player — the old
            // first-batch path preserved this.
            val langs = StreamEngine.emit(
                probed,
                { emitted.incrementAndGet(); callback(it) },
                subtitleCallback,
                originalLangNow(),
                subDedupeKeys = sharedSubKeys,
            )
            coveredSubLangs.addAll(langs)
            started = emitted.get() > 0
        }
        // Track every URL the player has already received so the trickle
        // never re-pushes the winner (or any duplicates). Subtitle-only
        // carriers have a blank url — key them on their subtitle urls so
        // multiple carriers don't collapse into one.
        fun dedupeKey(s: StreamEngine.RawStream): String =
            if (s.url.isNotBlank()) s.url
            else "sub|" + s.subtitles.joinToString("|") { it.second }
        val emittedUrls = Collections.synchronizedSet(HashSet<String>())
        autoPlayUrl?.let { emittedUrls.add(it) }

        // PHASE 3 — background trickle: the stream is warming up, but the farm
        // keeps resolving and each later batch is pushed to the player for
        // FAST_START_TRICKLE_MS (server list keeps growing). Trickle mode: NO
        // master re-fetches (bytes belong to the video), one link every
        // TRICKLE_EMIT_GAP_MS so nothing bursts. Subtitle tracks ARE emitted
        // — deduped through sharedSubKeys, so late servers (MovieBox!) bring
        // their own captions instead of playing mute.
        if (started) {
            val trickleEnd = System.currentTimeMillis() + StreamEngine.FAST_START_TRICKLE_MS
            // Drain everything that's already buffered (minus the winner
            // url) and then keep filling the list with later arrivals until
            // the 25s window closes. emittedUrls is the single source of
            // truth for "already pushed to the player".
            fastStartScope.launch {
                try {
                    if (coveredSubLangs.isEmpty()) {
                        launch {
                            topUpSubtitles(imdbDeferred.await(), season, episode, originalLangNow(),
                                coveredSubLangs, sharedSubKeys, subtitleCallback)
                        }
                    }
                    while (System.currentTimeMillis() < trickleEnd) {
                        val slice = synchronized(buffered) { buffered.toList() }
                        val next = slice.filter { emittedUrls.add(dedupeKey(it)) }
                        if (next.isNotEmpty()) {
                            val langs = StreamEngine.emit(
                                next,
                                { emitted.incrementAndGet(); callback(it) },
                                subtitleCallback,
                                originalLangNow(),
                                probeManifests = false,
                                emitGapMs = StreamEngine.TRICKLE_EMIT_GAP_MS,
                                subDedupeKeys = sharedSubKeys,
                            )
                            coveredSubLangs.addAll(langs)
                        } else {
                            kotlinx.coroutines.delay(200)
                        }
                    }
                    topUpSubtitles(imdbDeferred.await(), season, episode, originalLangNow(),
                        coveredSubLangs, sharedSubKeys, subtitleCallback)
                } catch (t: Throwable) {
                    android.util.Log.w("IndStream", "trickle emit stopped: ${t.message}")
                }
            }
        }

        android.util.Log.i("IndStream", "loadLinks: tmdb=$tmdbId/$type s=$season e=$episode -> started=$started, $emitted links emitted (first-frame)")
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
     *  fast-start paths. [sharedSubKeys] dedupes fallback tracks against
     *  everything already emitted (server tracks + any earlier fallback pass),
     *  so running the top-up twice (early + end-of-trickle) never duplicates. */
    private suspend fun topUpSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        originalLang: String?,
        coveredSubLangs: MutableSet<String>,
        sharedSubKeys: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val missing = SubtitleFallback.missingLanguages(
            coveredSubLangs, SubtitleFallback.desiredLanguages(originalLang),
        )
        if (missing.isNotEmpty()) {
            SubtitleFallback.fetch(imdbId, season, episode, missing)
                .filter { sharedSubKeys.add("${it.lang}|${it.url}") }
                .forEach { subtitleCallback(it) }
        }
    }
}

