package com.indstream

/**

 * FILE: StreamEngine.kt â€” the IndStream resolution engine (HOW a TMDB id
 * becomes playable links).
 *
 *  - [StreamEngine]   fans out to healthy servers in parallel, probes audio
 *                     + speed, gates on dual-audio (Hindi first) and emits
 *                     the fastest usable link set.
 *  - [ServerFarm]     server registry + [HealthMonitor] ï¿½ defined in
 *                     ServerRegistry.kt (data + health state, no orchestration).
 *
 * Distinct from CoreServices.kt (stateless primitives: HTTP, TMDB, manifest
 * parsing, title matching) and ServerRegistry.kt (server registry + health
 * data): this file holds the orchestration. Third-party stream sources
 * live in VidLinkSource.kt.
 */

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/**
 * Federated resolution engine.
 *
 * 1. Fans out to healthy embed servers in parallel.
 * 2. Uses the same multi-strategy pipeline as Multimovies:
 *    fetch ? unwrap iframes ? bare-URL regex harvest ? loadExtractor registry
 *    ? JS config ? <video> source ? subtitles.
 * 3. Dual-audio gating: only emits servers whose master playlist carries
 *    at least Hindi+English audio tracks. When none do, falls back to
 *    the best available single-audio source.
 */
object StreamEngine {

    // One slot per host: the live farm is ≤16 servers, so a smaller cap only
    // made fast resolvers QUEUE behind slow multi-chain hosts (MovieBox /
    // Allmovieland hold their slot 10-20s) and pushed the first frame out.
    // Full-parallel launch = every host starts the instant the tap lands
    // (user spec: instant play + all servers pulled).
    private const val MAX_CONCURRENT = 16
    private const val MAX_SERVERS = 16
    private const val MAX_UNWRAP = 4
    /** Minimum stream height to prefer for AUTO-PLAY (user spec Sept 2026:
     *  the ≥720 gate moved here from emit() — the server LIST stays
     *  unrestricted so every successful host is visible). */
    private const val MIN_AUTO_HEIGHT = 720
    /** PREFERRED auto-play starter height (user Sept 2026 refinement): when a
     *  1080p candidate exists (direct file OR adaptive master whose probed
     *  bestHeight ≥1080) it is auto-played FIRST; ≥720 / adaptive-that-climbs
     *  is the fallback. ≥720 stays the floor, not the target. */
    private const val PREFERRED_HEIGHT = 1080

    /** MovieBox bearer token cache (CSX parity): the x-user token lives for
     *  hours; caching it removes one serial round-trip from every resolve. */
    @Volatile private var movieBoxToken: String? = null
    @Volatile private var movieBoxTokenAt: Long = 0L
    private const val MOVIEBOX_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    private val STREAM_REGEX = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
    )

    data class RawStream(
        val serverId: String,
        val serverName: String,
        val url: String,
        val isM3u8: Boolean,
        val referer: String? = null,
        val qualityHint: Int = 0,
        val measuredKbps: Long? = null,
        val subtitles: List<Pair<String, String>> = emptyList(),
        val audioPriority: Int = 0,
        val audioLabel: String = "",
        val inlineManifest: String? = null, // HLS master playlist text delivered inline (JSON API)
        /** Extra HTTP headers the player must send when fetching [url] (e.g.
         *  "User-Agent: ExoPlayer" for CDNs that reject browser UAs). Merged
         *  into the ExtractorLink headers at emission time. */
        val extraHeaders: Map<String, String> = emptyMap(),
        /** Wall time from the Play tap to this stream's arrival (ms), stamped
         *  per batch in [resolveRealtime]. Live ranking signal. */
        val resolveMs: Long? = null,
        /** Live time-to-first-byte (ms) measured by [probeCandidates] during
         *  the settle window. Null = not probed this session (score falls
         *  back to the health-history prior). */
        val ttfbMs: Long? = null,
    )

    /**
     * A resolver throws this when the host legitimately carries no source for
     * this title/episode: library miss, unsupported media type, no Hindi dub,
     * season range not covered. Unlike a network failure or a timeout it must
     * NOT feed the circuit breaker — otherwise three unlucky taps (an
     * English-only title hitting Videasy Hindi, a MovieBox title-match miss,
     * a series episode on 8Stream) trip the breaker and a working server
     * silently vanishes from the farm for 5 minutes. This is exactly what
     * made MovieBox "appear in some episodes but not others".
     */
    class CleanMissException(message: String) : Exception(message)

    /**
     * Pick the servers to query for this title, in the order they should be hit:
     * healthy first, Hindi-flagged first (so Hindi dubs play before slower
     * English sources), then by learned real-time [HealthMonitor.speedScore].
     * This is the "select the fastest server in real time" step of the fast-start
     * path — the server list is launched in this order so the quickest,
     * highest-priority host resolves and plays first.
     */
    private fun selectServers(tmdbId: Int, type: String, season: Int, episode: Int): List<ServerSpec> {
        val healthy = ServerFarm.allServers.filter { HealthMonitor.isHealthy(it.id) }
        Log.d("IndStream", "healthy: ${healthy.size}/${ServerFarm.allServers.size}")
        // A fully-tripped farm used to return emptyList() instantly, so every tap
        // showed "no link found" for a full trip window with zero network traffic.
        // Once per cooldown window, clear the trips and re-probe the whole farm --
        // one bad session must never lock the plugin out, but a dead uplink should
        // not trigger a full-farm hammer on every tap either.
        val candidates = if (healthy.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastFarmProbeAt < FARM_REPROBE_COOLDOWN_MS) {
                Log.w("IndStream", "all servers tripped; re-probe cooldown active " +
                    "(${(FARM_REPROBE_COOLDOWN_MS - (now - lastFarmProbeAt)) / 1000}s left)")
                emptyList()
            } else {
                lastFarmProbeAt = now
                // Clear only the trip state, keeping latency/throughput history so the
                // speedScore ordering survives the re-probe.
                Log.w("IndStream", "all ${ServerFarm.allServers.size} servers tripped -- clearing trips, re-probing all")
                HealthMonitor.resetTrips()
                ServerFarm.allServers
            }
        } else healthy
        // Hindi-first fast path: Hindi-flagged servers are placed FIRST
        // (ahead of the rest of the farm) so their results arrive â€” and can
        // play â€” before slower English sources finish probing.
        val hindiFirst = candidates.filter { it.hindi }
        val others = candidates.filterNot { it.hindi }
            .sortedByDescending { HealthMonitor.speedScore(it.id) }
        val servers = (hindiFirst + others).take(MAX_SERVERS)
        Log.d("IndStream", "selectServers tmdb=$tmdbId type=$type s=$season e=$episode -> ${servers.size} servers (hindi-first: ${hindiFirst.map { it.id }})")
        return servers
    }

    /**
     * Dual-ID race helper (user spec Sept 2026, Phase 1): run several id
     * shapes for one host in parallel; the FIRST non-empty result wins and
     * the losing attempts are cancelled. All-empty → emptyList. Used by the
     * vidup/vidcore dispatch (both hosts accept TMDB and IMDB ids in their
     * URL path) — TMDB-keyed arm starts at t=0, the IMDB-keyed arm starts as
     * soon as the id resolves, so neither blocks the other.
     */
    private suspend fun raceFirst(vararg blocks: suspend () -> List<RawStream>): List<RawStream> {
        if (blocks.isEmpty()) return emptyList()
        if (blocks.size == 1) return runCatching { blocks[0]() }.getOrDefault(emptyList())
        return coroutineScope {
            val winner = CompletableDeferred<List<RawStream>>()
            val pending = java.util.concurrent.atomic.AtomicInteger(blocks.size)
            val jobs = blocks.map { block ->
                launch {
                    val r = runCatching { block() }.getOrDefault(emptyList())
                    if (r.isNotEmpty()) winner.complete(r)
                    else if (pending.decrementAndGet() == 0 && !winner.isCompleted) {
                        winner.complete(emptyList())
                    }
                }
            }
            val result = winner.await()
            jobs.forEach { it.cancel() }
            result
        }
    }

    /**
     * Resolve every server, invoking [onBatch] with a server's results the instant
     * that server finishes. Because the farm is launched fastest-first (see
     * [selectServers]), completion order is fastest-first â€” so a caller can start
     * playback on the FIRST batch instead of waiting for the slowest server (the
     * startup "head" before the first frame). This is the core of the fast-start
     * path used by [IndStreamProvider.loadLinks].
     */
    suspend fun resolveRealtime(
        tmdbId: Int, type: String, season: Int = -1, episode: Int = -1,
        /** Lazily resolves the IMDB id for IMDB-keyed servers. Called INSIDE each
         *  server's coroutine so the (up to 3s) TMDB lookup runs concurrently with
         *  the farm instead of blocking every server's start (fast-start: zero
         *  serial head before the first launch). */
        imdbIdProvider: (suspend () -> String?)? = null,
        onBatch: suspend (serverId: String, streams: List<RawStream>) -> Unit,
    ) {
        if (tmdbId <= 0) {
            Log.w("IndStream", "resolveRealtime skipped: invalid tmdbId=$tmdbId")
            return
        }
        val servers = selectServers(tmdbId, type, season, episode)
        if (servers.isEmpty()) return
        val sem = Semaphore(MAX_CONCURRENT)
        // Wall-clock origin for the live resolve-time signal: every batch's
        // RawStreams get stamped with ms-from-Play-tap (startupScore input).
        val playStart = System.currentTimeMillis()
        coroutineScope {
            servers.map { spec ->
                async {
                    sem.acquire()
                    try {
                        // IMDB id only for the servers that need it — TMDB-keyed
                        // servers never wait for (or trigger) the lookup.
                        val imdbId = if (spec.idType == ServerIdType.IMDB) imdbIdProvider?.invoke() else null
                        val outcome = withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            runCatching { resolveOne(spec, tmdbId, imdbId, type, season, episode, imdbIdProvider) }
                                .recover { t ->
                                    if (t is CleanMissException) {
                                        Log.i("IndStream", "${spec.id}: clean miss — ${t.message} (no breaker trip)")
                                        emptyList<RawStream>()
                                    } else throw t
                                }.getOrNull()
                        }
                        if (outcome == null) {
                            // resolveOne was cut off (hang/black-hole) or crashed before it could
                            // record anything: count it as a failure so the breaker can trip.
                            Log.w("IndStream", "${spec.id}: no result after ${spec.timeoutSec}s (timeout or crash), recording failure")
                            HealthMonitor.recordFailure(spec.id)
                        }
                        // Hindi-only hosts often declare no labelled `hi` audio track,
                        // so probe-based audioPriority would wrongly read 0. Bias the
                        // streams from a `spec.hindi` server to Hindi (priority 4) so
                        // they float above English sources in the audio-first sort.
                        val resolveMs = System.currentTimeMillis() - playStart
                        val streams = outcome?.map { s ->
                            val biased = if (spec.hindi) s.copy(audioPriority = 4, audioLabel = "Hindi") else s
                            biased.copy(resolveMs = resolveMs)
                        }.orEmpty()
                        if (streams.isNotEmpty()) {
                            Log.d("IndStream", "server=${spec.id} resolve_ms=$resolveMs streams=${streams.size}")
                            onBatch(spec.id, streams)
                        }
                    } finally { sem.release() }
                }
            }.awaitAll()
        }
    }

    /**
     * Resolve all streams across the farm (collects every server's results into a
     * single list). Kept for callers that want the full list at once; the
     * fast-start path in [IndStreamProvider.loadLinks] prefers [resolveRealtime].
     */
    suspend fun resolve(tmdbId: Int, imdbId: String?, type: String, season: Int = -1, episode: Int = -1): List<RawStream> {
        if (tmdbId <= 0) {
            Log.w("IndStream", "resolve skipped: invalid tmdbId=$tmdbId")
            return emptyList()
        }
        val out = mutableListOf<RawStream>()
        resolveRealtime(tmdbId, type, season, episode, imdbIdProvider = { imdbId }) { _, streams -> out += streams }
        Log.d("IndStream", "resolved ${out.size} streams from farm")
        // Same ranking as emit(): Hindi first, then speed, then quality.
        return out.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })
    }

    /**
     * Live-probe score for instant-play ranking (user spec Sept 2026: rank
     * from THIS session's measured signals, never static host-speed folklore).
     * Higher = the stream is expected to produce frame 1 sooner AND at a
     * usable quality. Weights:
     *  - TTFB (40%): [RawStream.ttfbMs] probed live during the settle window —
     *    time to first byte on the actual stream URL is the dominant
     *    first-frame signal. When no live probe exists, a WEAK health-history
     *    prior substitutes (never overrides a live probe).
     *  - Resolve time (25%): [RawStream.resolveMs] — wall time from the Play
     *    tap to this server answering.
     *  - Throughput (20%): fresh probe kbps, else the server's health-history
     *    average, else neutral 0.5.
     *  - Quality (10%): 1.0 for ≥1080, 0.9 for ≥720, 0.5 for adaptive with
     *    unknown height (lowered from 0.7 — user report: adaptive resolution
     *    does not reliably climb), 0.0 for known sub-720.
     *  - Audio (5%): SOFT language preference that can never overcome a
     *    meaningful speed difference.
     */
    private fun ttfbNorm(ms: Long): Double = 1.0 / (1.0 + ms / 500.0)
    private fun throughputNorm(kbps: Long): Double = min(1.0, kbps / 5000.0)

    private fun qualityNorm(s: RawStream): Double = when {
        s.qualityHint >= 1080 -> 1.0
        s.qualityHint >= MIN_AUTO_HEIGHT -> 0.9
        s.isM3u8 && s.qualityHint <= 0 -> 0.5      // adaptive, height unknown
        s.qualityHint in 1 until MIN_AUTO_HEIGHT -> 0.0
        else -> 0.0
    }

    private fun startupScore(s: RawStream): Double {
        val hist = HealthMonitor.speedScore(s.serverId) // 0..1
        val ttfbTerm = s.ttfbMs?.let { ttfbNorm(it) } ?: (0.6 + 0.3 * hist).coerceAtMost(1.0)
        val resolveTerm = s.resolveMs?.let { ttfbNorm(it) } ?: 0.5
        val throughputTerm = when {
            (s.measuredKbps ?: 0L) > 0L -> throughputNorm(s.measuredKbps!!)
            else -> HealthMonitor.averageThroughput(s.serverId)
                ?.let { throughputNorm(it) } ?: 0.5
        }
        val audioSoft = when (s.audioPriority) {
            4 -> 1.0; 3 -> 0.75; 2 -> 0.5; 1 -> 0.25; else -> 0.0
        }
        return 0.40 * ttfbTerm + 0.25 * resolveTerm + 0.20 * throughputTerm +
            0.10 * qualityNorm(s) + 0.05 * audioSoft
    }

    /**
     * Auto-play pick (user spec Phase 2, **1080p-first** eligibility order):
     *  1. 1080p pool — KNOWN height ≥1080 (direct 1080p file OR adaptive master
     *     whose probed bestHeight ≥1080). This is the preferred starter.
     *  2. 720p pool — KNOWN height ≥720 to <1080. Fallback when no 1080p exists.
     *  3. Adaptive-unknown pool — m3u8 with unknown height (the player starts
     *     ~720 and ABR-climbs; user-accepted). Only if no known ≥720 exists.
     *  4. Everything else (sub-720 only) — start the best available rather
     *     than stall; late ≥720+ arrivals still join the server list.
     * Within the first non-empty pool, the highest [startupScore] (live probes)
     * wins. Pure JVM logic (HealthMonitor is plain Kotlin) — unit-tested in
     * Test/AutoPlayPickTest.kt.
     */
    fun pickAutoPlay(streams: List<RawStream>): RawStream? {
        if (streams.isEmpty()) return null
        val candidates = streams.filter { it.url.isNotBlank() }
        if (candidates.isEmpty()) return null
        // Strict 1080-first pool order. `qualityHint` is 0 for adaptive masters
        // until probeCandidates stamps the probed bestHeight — so a 1080p
        // adaptive master lands in pool1 only after the 1.5s settle probes run.
        val p1080 = candidates.filter { it.qualityHint >= PREFERRED_HEIGHT }
        val p720 = candidates.filter { it.qualityHint >= MIN_AUTO_HEIGHT }
        val adaptiveUnknown = candidates.filter { it.isM3u8 && it.qualityHint <= 0 }
        val pool = when {
            p1080.isNotEmpty() -> p1080
            p720.isNotEmpty() -> p720
            adaptiveUnknown.isNotEmpty() -> adaptiveUnknown
            else -> candidates
        }
        val winner = pool.maxByOrNull { startupScore(it) }
        Log.d("IndStream", "pickAutoPlay: pool=${pool.size}/${candidates.size} " +
            "winner=${winner?.serverName} h=${winner?.qualityHint} " +
            "ttfb=${winner?.ttfbMs}ms resolve=${winner?.resolveMs}ms score=${winner?.let { startupScore(it) }}")
        return winner
    }

    /**
     * Live probes for auto-play ranking, run INSIDE the 1.5s settle window
     * (parallel, one round-trip per candidate, tight budgets). For HLS
     * masters the master fetch does triple duty: TTFB + best variant height +
     * audio renditions — the text is stored into [RawStream.inlineManifest]
     * so `emit()` skips its own re-fetch. Returns stamped COPIES; the caller
     * uses them for [pickAutoPlay] only (the arrival buffer is untouched, so
     * FastStartCache keeps the raw streams).
     */
    suspend fun probeCandidates(streams: List<RawStream>): List<RawStream> {
        val distinct = streams.distinctBy { it.url }.filter { it.url.isNotBlank() }
        return coroutineScope {
            distinct.map { raw ->
                async {
                    val stamped = if (raw.isM3u8) {
                        // Master probe: TTFB + variants + audio in one fetch
                        // (skip when the resolver already delivered everything).
                        val alreadyKnown = raw.inlineManifest != null ||
                            (raw.qualityHint > 0 && raw.audioPriority > 0)
                        if (alreadyKnown) {
                            val p = HttpKit.probeTtfb(raw.url, raw.referer,
                                timeoutMs = PROBE_TTFB_BUDGET_MS, extraHeaders = raw.extraHeaders)
                            raw.copy(ttfbMs = p.ttfbMs, measuredKbps = raw.measuredKbps ?: p.kbps)
                        } else {
                            val lh = LinkedHashMap<String, String>()
                            lh.putAll(raw.extraHeaders)
                            if (!lh.containsKey("Referer") && !raw.referer.isNullOrBlank()) lh["Referer"] = raw.referer!!
                            val probeStart = System.currentTimeMillis()
                            val fetched = withTimeoutOrNull(PROBE_MASTER_BUDGET_MS) {
                                runCatching { app.get(raw.url, timeout = 3, headers = lh).text }.getOrNull()
                            }
                            if (fetched == null) {
                                // Master probe lost the race with the settle
                                // window — TTFB-only fallback, still eligible.
                                val p = HttpKit.probeTtfb(raw.url, raw.referer,
                                    timeoutMs = PROBE_TTFB_BUDGET_MS, extraHeaders = raw.extraHeaders)
                                raw.copy(ttfbMs = p.ttfbMs)
                            } else {
                                val master = ManifestKit.parseMaster(fetched, raw.url)
                                val height = master?.let { ManifestKit.bestHeight(it.variants) }?.takeIf { it > 0 }
                                    ?: raw.qualityHint
                                val pri = master?.let { ManifestKit.audioPriority(it) } ?: raw.audioPriority
                                raw.copy(
                                    ttfbMs = System.currentTimeMillis() - probeStart,
                                    measuredKbps = raw.measuredKbps,
                                    qualityHint = if (raw.qualityHint <= 0) height else raw.qualityHint,
                                    audioPriority = if (pri > 0) pri else raw.audioPriority,
                                    audioLabel = if (pri > 0 && raw.audioLabel.isBlank()) audioLabelFor(pri) else raw.audioLabel,
                                    inlineManifest = master?.let { fetched },
                                )
                            }
                        }
                    } else {
                        // Direct file: ranged GET gives TTFB + throughput.
                        val p = HttpKit.probeTtfb(raw.url, raw.referer,
                            timeoutMs = PROBE_TTFB_BUDGET_MS, extraHeaders = raw.extraHeaders)
                        raw.copy(ttfbMs = p.ttfbMs, measuredKbps = raw.measuredKbps ?: p.kbps)
                    }
                    Log.d("IndStream", "probe server=${stamped.serverName} h=${stamped.qualityHint} " +
                        "ttfb=${stamped.ttfbMs}ms kbps=${stamped.measuredKbps ?: "?"} " +
                        "resolve=${stamped.resolveMs ?: "?"}ms url=${stamped.url.take(60)}")
                    stamped
                }
            }.awaitAll()
        }
    }

    /**
     * Emit links fastest-first. Each stream becomes exactly ONE link — HLS
     * masters go out as the adaptive source (the player selects the rung),
     * never as master + per-variant duplicates (user spec Sept 2026).
     *
     * Quality policy (user spec Sept 2026, REVISED): the ≥720 gate applies to
     * AUTO-PLAY selection only ([pickAutoPlay]) — the server LIST is
     * unrestricted so every successful host is visible, sub-720 included.
     * The former single-choke-point <720 filter was removed here.
     *
     * Returns the canonical subtitle language names the servers actually
     * provided so the caller can top up gaps from [SubtitleFallback].
     */
    suspend fun emit(
        streams: List<RawStream>,
        onLink: (ExtractorLink) -> Unit,
        onSubtitle: (SubtitleFile) -> Unit,
        /** TMDB original_language ("ja", "hi", ...): a stream whose audio label is
         *  "Original" carries this language, so "VidLink (Japanese)" is shown
         *  instead of "VidLink (Original)". */
        originalLang: String? = null,
        /** Trickle mode (background server arrival AFTER the first frame):
         *  true = skip the master-manifest re-fetch (zero extra bandwidth given
         *  to the already-running video) and don't re-emit subtitles heights.
         *  Labels fall back to the RawStream's own qualityHint. */
        probeManifests: Boolean = true,
        /** Subtitle pass — with the SHARED [subDedupeKeys] set this is safe on
         *  every batch: each (canonicalLang, url) pair is emitted exactly once
         *  across the whole loadLinks call, so late servers' caption tracks
         *  reach the player too instead of being dropped. */
        emitSubtitles: Boolean = true,
        /** Sleep before emitting each link (trickle pacing: keeps background
         *  arrivals from burstingmegabytes of manifest fetches / player
         *  channel traffic while the stream is warming up). */
        emitGapMs: Long = 0L,
        /** Subtitle dedupe keys SHARED across every emit call of one loadLinks
         *  (first batch + all trickle batches). When null a per-call set is
         *  used (old behaviour: safe but trickle batches can't emit subs). */
        subDedupeKeys: MutableSet<String>? = null,
    ): Set<String> {
        if (streams.isEmpty()) return emptySet()
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())
        // Subtitle dedupe: per-quality RawStreams (VidLink qualities[]) and
        // multi-master servers (VaPlayer) repeat the SAME caption list per
        // stream, which made every subtitle show up 2-3 times (user-reported
        // Sept 2026). One emission per (canonicalLang, url) — keyed on the
        // SHARED set when the caller provides one, so dedupe spans batches.
        val emittedSubs = subDedupeKeys ?: java.util.Collections.synchronizedSet(HashSet<String>())
        val subLangs = java.util.Collections.synchronizedSet(HashSet<String>())
        fun emitSub(rawLang: String?, url: String) {
            if (url.isBlank()) return
            val canonical = LinkNaming.canonicalSubtitleName(rawLang)
            if (!emittedSubs.add("$canonical|$url")) return
            subLangs.add(canonical)
            onSubtitle(SubtitleFile(canonical, url))
        }

        // Quality gate before numbering (user spec: 720p minimum) so the
        // -1..-N group numbers are contiguous: gate first, then rank.
        // Streams with a blank url are subtitle-only carriers (pipeline
        // step 7) - they never reach the link gate below.
        // Rank best-first for INSTANT PLAY: the first link emitted (the one the
        // player auto-plays) must be the one that actually STARTS soonest, not
        // merely the server that finished resolving first. Speed/history
        // dominate; audio language is only a soft tie-breaker (user spec: a slow
        // "Hindi" source must never outrank a fast one).
        val ranked = streams.sortedWith(compareByDescending<RawStream> { startupScore(it) }
            .thenByDescending { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })

        // Subtitle pass FIRST, over every stream including subtitle-only ones:
        // each server's OWN caption tracks are taken here (user spec: "servers
        // give their own subtitles - take them"). The fallback provider later
        // tops up whatever language none of the servers carried. Safe on every
        // batch now that dedupe is shared — late servers' tracks are emitted
        // exactly once.
        if (emitSubtitles) {
            ranked.forEach { raw -> raw.subtitles.forEach { (lang, subUrl) -> emitSub(lang, subUrl) } }
        }

        // Link pass: every url-bearing stream becomes a link (list is
        // unrestricted — auto-play ≥720 preference lives in pickAutoPlay).
        // Streams with a blank url are subtitle-only carriers (pipeline
        // step 7) - they never become links.
        val eligible = ranked.filter { raw -> raw.url.isNotBlank() }

        // Pre-resolve M3U8 masters before dedupe so the numbering key uses
        // the ACTUAL height (from variant parse) rather than qualityHint
        // (which is 0 for adaptive masters). Without this, a master with
        // qualityHint=0 and a direct MP4 qualityHint=1080 from the same
        // server form separate dedupe groups and both print "1080p".
        //
        // Also: quality=0 on every ExtractorLink — the resolution is baked
        // into the name string (user spec). CloudStream's built-in quality
        // badge also uses ExtractorLink.quality, so setting it to a non-zero
        // value duplicated the resolution: "VidLink (Multi) 1080p [1080p]".
        // Setting quality=0 suppresses the badge and fixes the doubling.
        data class ResolvedEmit(
            val raw: RawStream,
            val fullHeight: Int,
            val tagLabel: String,
            val master: ManifestKit.MasterPlaylist?,
        )
        // Pre-resolve CONCURRENTLY: the old serial map added up to 4s PER
        // unprobed HLS master (VaPlayer/VidUp/VidCore arrive without height or
        // audio probes) BEFORE the first link reached the player — the single
        // biggest chunk of the old 5-7s startup. Parallel fetches cap that
        // head at one master timeout (~4s worst case, usually <1s).
        val preResolved = coroutineScope {
            eligible.map { raw ->
                async {
                    when {
                        // HLS master: re-parse for real height + audio (skip when trickle/off or already known).
                        raw.isM3u8 && probeManifests -> {
                            val alreadyKnown = (raw.audioPriority > 0 || raw.measuredKbps != null) &&
                                !raw.subtitles.any { it.second.startsWith("/") }
                            if (alreadyKnown) {
                                ResolvedEmit(raw, raw.qualityHint, raw.audioLabel, null)
                            } else {
                                val lh = LinkedHashMap<String, String>()
                                lh.putAll(raw.extraHeaders)
                                if (!lh.containsKey("Referer") && !raw.referer.isNullOrBlank()) lh["Referer"] = raw.referer!!
                                val masterText = raw.inlineManifest ?: withTimeoutOrNull(3000L) {
                                    runCatching { app.get(raw.url, timeout = 3, headers = lh).text }.getOrNull()
                                }
                                val master = ManifestKit.parseMaster(masterText, raw.url)
                                val h = master?.let { ManifestKit.bestHeight(it.variants) }?.takeIf { it > 0 } ?: raw.qualityHint
                                // Audio label: explicit server label wins; else the REAL track the
                                // player auto-selects (multi-audio → "Multi", single track → its language).
                                val tag = if (raw.audioLabel.isNotBlank()) raw.audioLabel
                                else if (master != null && master.isMultiAudio) "Multi"
                                else if (master != null) audioLabelFor(ManifestKit.audioPriority(master))
                                else raw.audioLabel
                                master?.let { m ->
                                    val langs = m.audio.map { r -> "lang=${r.language ?: "none"} name=${r.name} group=${r.groupId} default=${r.default}" }
                                    Log.d("IndStream", "audioTracks server=${raw.serverName} multiAudio=${m.isMultiAudio} renditions=$langs")
                                }
                                ResolvedEmit(raw, h, tag, master)
                            }
                        }
                        // Direct file: measure the REAL height (moov parse → URL token → "Auto").
                        !raw.isM3u8 && probeManifests -> {
                            val measured = HttpKit.resolveHeight(raw.url, raw.referer, raw.extraHeaders)
                            val h = if (measured > 0) measured
                            else if (raw.qualityHint > 0) raw.qualityHint
                            else {
                                val fromUrl = ManifestKit.resolutionFromUrl(raw.url)
                                if (fromUrl > 0) fromUrl else -1   // -1 = Auto (unknown direct file)
                            }
                            ResolvedEmit(raw, h, raw.audioLabel, null)
                        }
                        // Trickle/off path: trust the RawStream's own qualityHint (no new probes).
                        else -> ResolvedEmit(raw, raw.qualityHint, raw.audioLabel, null)
                    }
                }
            }.awaitAll()
        }
        val resolvedForKey = preResolved.map { it.raw.copy(qualityHint = it.fullHeight) }
        val numbers = LinkNaming.dedupeNames(resolvedForKey, originalLang)

        preResolved.forEachIndexed { index, r ->
            val raw = r.raw
            val dupIdx = numbers.getOrElse(index) { 0 }
            if (!emitted.add(raw.url)) return@forEachIndexed

            val linkHeaders = LinkedHashMap<String, String>()
            linkHeaders.putAll(raw.extraHeaders)
            if (!linkHeaders.containsKey("Referer") && !raw.referer.isNullOrBlank()) {
                linkHeaders["Referer"] = raw.referer!!
            }

            val label = LinkNaming.displayName(
                serverName = raw.serverName,
                audioLabel = r.tagLabel,
                qualityHint = r.fullHeight,
                duplicateIndex = dupIdx,
                originalLang = originalLang,
            )

            if (raw.isM3u8) {
                onLink(ExtractorLink(
                    source = label, name = label,
                    url = raw.url, referer = raw.referer ?: "",
                    quality = 0,
                    headers = linkHeaders, type = ExtractorLinkType.M3U8,
                ))
                if (emitGapMs > 0) delay(emitGapMs)
                r.master?.subtitles?.forEach { sub ->
                    sub.uri?.let { emitSub(sub.language ?: sub.name, ManifestKit.resolveUrl(raw.url, it)) }
                }
            } else {
                onLink(ExtractorLink(
                    source = label, name = label,
                    url = raw.url, referer = raw.referer ?: "",
                    quality = 0,
                    headers = linkHeaders, type = ExtractorLinkType.VIDEO,
                ))
                if (emitGapMs > 0) delay(emitGapMs)
            }
        }
        return subLangs.toSet()
    }

    // ------------------------------------------------------------------
    // Internals ï¿½ multi-strategy pipeline (proven from Multimovies)
    // ------------------------------------------------------------------

    private suspend fun resolveOne(
        spec: ServerSpec,
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
        /** Lazy IMDB lookup for the dual-ID race (vidup/vidcore accept both id
         *  shapes). Null = no provider (prewarm path passes a direct id). */
        imdbIdProvider: (suspend () -> String?)? = null,
    ): List<RawStream> {
        val start = System.currentTimeMillis()
        val id = if (spec.idType == ServerIdType.IMDB)
            (imdbId ?: run { Log.w("IndStream", "${spec.id}: IMDB required but missing"); return emptyList() })
        else tmdbId.toString()
        val embedUrl = if (type == "movie") ServerFarm.buildMovieUrl(spec, id)
        else ServerFarm.buildTvUrl(spec, id, season, episode)
        // Per-server referer (some APIs 403 without it, e.g. api.shows.st).
        val referer = spec.referer ?: embedUrl.substringBefore("?")

        // VidLink: encrypted-token API. Token embeds the TMDB id + a +480s
        // timestamp (VidlinkSource); the response carries stream.playlist (an
        // adaptive multi-audio master up to 1080p) + captions. Key rotation
        // (rare) is fixed by updating VidlinkSource.KEY_HEX only.
        if (spec.id == "vidlink") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveVidlink(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidlink api", result.size); return result }
            failServer(spec, "vidlink returned no streams")
            return emptyList()
        }
        if (spec.id == "myflixer-hindi") {
            val result = resolveMyFlixerHindi(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "myflixer-hindi", result.size); return result }
            failServer(spec, "myflixer-hindi returned no streams")
            return emptyList()
        }
        if (spec.id == "moviebox") {
            val result = resolveMovieBox(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "moviebox", result.size); return result }
            // Soft-fail (user spec Sept 2026): an empty result after a REAL
            // attempt (API answered, no playable/region streams) is a miss for
            // this title, not a host failure — no breaker trip. Genuine network
            // failures inside resolveMovieBox still record via failServer /
            // the timeout path above.
            failServer(spec, "moviebox returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "primesrc") {
            val result = resolvePrimeSrc(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "primesrc", result.size); return result }
            failServer(spec, "primesrc returned no streams")
            return emptyList()
        }
        if (spec.id == "videasy-hindi") {
            val result = resolveVideasyHindi(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "videasy-hindi", result.size); return result }
            failServer(spec, "videasy-hindi returned no streams")
            return emptyList()
        }
        if (spec.id == "nhd") {
            val result = resolveNhd(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "nhd", result.size); return result }
            failServer(spec, "nhd returned no streams")
            return emptyList()
        }
        if (spec.id == "vaplayer") {
            val result = resolveVaplayer(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vaplayer", result.size); return result }
            failServer(spec, "vaplayer returned no streams")
            return emptyList()
        }
        if (spec.id == "vidrock") {
            val result = resolveVidrock(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidrock", result.size); return result }
            failServer(spec, "vidrock returned no streams")
            return emptyList()
        }
        if (spec.id == "videm") {
            val result = resolveVidem(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "videm", result.size); return result }
            failServer(spec, "videm returned no streams")
            return emptyList()
        }
        if (spec.id == "ezvidapi") {
            val result = resolveEzvidapi(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "ezvidapi", result.size); return result }
            failServer(spec, "ezvidapi returned no streams")
            return emptyList()
        }
        if (spec.id == "8stream") {
            val result = resolve8Stream(spec, imdbId, type)
            if (result.isNotEmpty()) { okServer(spec, start, "8stream api", result.size); return result }
            failServer(spec, "8stream returned no streams")
            return emptyList()
        }
        if (spec.id == "vidup" || spec.id == "vidcore") {
            val variant = spec.id
            // Dual-ID race (user spec Sept 2026): both hosts accept TMDB AND
            // IMDB ids in the URL path. The TMDB arm starts at t=0; the IMDB
            // arm joins the race as soon as the id resolves (instant cache
            // hit after load()). First non-empty result wins; on a joint miss
            // one failure is recorded, not two.
            val arms = mutableListOf<suspend () -> List<RawStream>>()
            arms.add { resolveEncDecPlayer(spec, tmdbId.toString(), type, season, episode, variant) }
            if (!imdbId.isNullOrBlank()) {
                val fixed = imdbId
                arms.add { resolveEncDecPlayer(spec, fixed, type, season, episode, variant) }
            } else if (imdbIdProvider != null) {
                val provider = imdbIdProvider
                arms.add {
                    val iid = provider.invoke()
                    if (iid.isNullOrBlank()) emptyList<RawStream>()
                    else resolveEncDecPlayer(spec, iid, type, season, episode, variant)
                }
            }
            val result = raceFirst(*arms.toTypedArray())
            if (result.isNotEmpty()) { okServer(spec, start, "$variant enc-dec", result.size); return result }
            failServer(spec, "$variant returned no streams (tmdb${if (arms.size > 1) "+imdb" else ""} raced)")
            return emptyList()
        }
        if (spec.id == "allmovieland") {
            val result = resolveAllmovieland(spec, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "allmovieland multi-lang", result.size); return result }
            // Soft-fail (user spec Sept 2026): multi-step chain finishing with
            // no language streams is a title-level miss — network failures
            // inside the resolver already log/return distinctly and the
            // timeout path records hard failures.
            failServer(spec, "allmovieland returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        // 0. JSON API branch (api.shows.st style): parse JSON, take source.url +
        //    source.qualities[] + subtitles[]. The signed stream URLs carry no
        //    file extension, so regex harvest would never find them.
        if (spec.isJsonApi) {
            val result = resolveJsonApi(spec, embedUrl, referer)
            if (result.isNotEmpty()) { okServer(spec, start, "json api", result.size); return result }
            failServer(spec, "json api returned no source")
            return emptyList()
        }

        // 1. Fetch embed page
        val rawText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(embedUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (rawText.isNullOrBlank()) { failServer(spec, "embed fetch blank/timeout: $embedUrl"); return emptyList() }
        Log.d("IndStream", "${spec.id}: embed fetched, ${rawText.length}B")

        // 2. Unwrap iframes
        val (unwrapped, unwrappedUrl) = unwrapPages(rawText, embedUrl, spec.timeoutSec)
        if (unwrapped !== rawText) Log.d("IndStream", "${spec.id}: unwrapped to $unwrappedUrl, ${unwrapped.length}B")

        // 3. Direct stream URL regex harvest
        val direct = harvestUrls(unwrapped)
        if (direct.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: direct harvest found ${direct.size} urls")
            val subs = grabSubtitles(unwrapped)
            val result = direct.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val pri = probeAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            okServer(spec, start, "direct harvest", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: no direct urls in page")
        }

        // 4. CloudStream extractor registry (VidSrc, 2embed, embed.su, MyFlixer, etc.)
        val regLinks = mutableListOf<ExtractorLink>()
        val regSubs = mutableListOf<SubtitleFile>()
        val regOk = runCatching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        // Also try the deepest unwrapped URL ï¿½ most embed chains register a
        // CloudStream extractor on the INNER host (the actual player), not the
        // outer wrapper. The outer page is the correct referer for the inner
        // player's CORS / origin check.
        if (unwrappedUrl != embedUrl) {
            runCatching {
                loadExtractor(url = unwrappedUrl, referer = embedUrl, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
            }
        }
        if (regLinks.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: extractor registry returned ${regLinks.size} links (outerOk=$regOk unwrapped=$unwrappedUrl)")
            val result = regLinks.map { link ->
                val probed = HttpKit.probeSpeed(link.url, link.referer)
                val pri = if (link.url.contains(".m3u8", ignoreCase = true)) probeAudio(link.url, link.referer) else 0
                RawStream(spec.id, spec.name, link.url, link.type == ExtractorLinkType.M3U8, link.referer, link.quality, probed,
                    regSubs.map { it.lang to it.url },
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            okServer(spec, start, "extractor registry", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: extractor registry returned no links (outerOk=$regOk unwrapped=$unwrappedUrl)")
        }

        // 5. JS config: file:"...", sources:[{file:"..."}]
        val jsUrls = harvestJsUrls(unwrapped)
        if (jsUrls.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: js harvest found ${jsUrls.size} urls")
            val subs = grabSubtitles(unwrapped)
            val result = jsUrls.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val pri = probeAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            okServer(spec, start, "js config harvest", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: no js harvest")
        }

        // 6. <video src> / <source src> HTML elements
        val videoSrc = harvestVideoSource(unwrapped, embedUrl)
        if (videoSrc != null) {
            Log.d("IndStream", "${spec.id}: video tag found: $videoSrc")
            val subs = grabSubtitles(unwrapped)
            val probed = HttpKit.probeSpeed(videoSrc, referer)
            val pri = probeAudio(videoSrc, referer)
            okServer(spec, start, "video tag", 1)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                audioPriority = pri, audioLabel = audioLabelFor(pri)))
        } else {
            Log.d("IndStream", "${spec.id}: no video tag")
        }

        // 7. Subtitle-only fallback
        val subs = grabSubtitles(unwrapped)
        if (subs.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: subtitles only (${subs.size})")
            okServer(spec, start, "subtitles only", 0)
            return listOf(RawStream(spec.id, spec.name, "", false, referer, subtitles = subs))
        }

        failServer(spec, "no harvestable stream across full pipeline")
        return emptyList()
    }

    /** Log + trip a server. Single choke point so every failure names its reason.
     *  A [CleanMissException] (library miss / unsupported media / no Hindi dub)
     *  is logged as a clean miss WITHOUT tripping the breaker — the host is up,
     *  this title just isn't in it. */
    private fun failServer(spec: ServerSpec, reason: String, isCleanMiss: Boolean = false) {
        if (isCleanMiss) {
            Log.i("IndStream", "${spec.id}: clean miss — $reason (no breaker trip)")
        } else {
            Log.w("IndStream", "${spec.id}: $reason")
            HealthMonitor.recordFailure(spec.id)
        }
    }

    /** Log + record a successful resolution for a server. */
    private fun okServer(spec: ServerSpec, start: Long, stage: String, streamCount: Int) {
        val ms = System.currentTimeMillis() - start
        Log.d("IndStream", "${spec.id}: OK via $stage, $streamCount streams in ${ms}ms")
        HealthMonitor.recordSuccess(spec.id, ms, null)
    }

    /** Returns audio label for the given priority. */
    private fun audioLabelFor(priority: Int): String = when (priority) {
        4 -> "Hindi"
        3 -> "Hindi+English"
        2 -> "Original"
        1 -> "English"
        else -> ""
    }

    /** Probe HLS master for best audio language priority. */
    private suspend fun probeAudio(url: String, referer: String?): Int {
        if (!url.contains(".m3u8", ignoreCase = true)) return 0
        val text = withTimeoutOrNull(3000L) {
            runCatching { app.get(url, timeout = 3, headers = mapOf("Referer" to (referer ?: ""))).text }.getOrNull()
        } ?: return 0
        val master = ManifestKit.parseMaster(text, url) ?: return 0
        val priority = ManifestKit.audioPriority(master)
        // Debug: what audio renditions did the probe see?
        val langs = master.audio.map { r -> "lang=${r.language ?: "none"} name=${r.name}" }
        Log.d("IndStream", "probeAudio url=${url.take(80)} priority=$priority renditions=$langs")
        return priority
    }

    /** Probe an inline master playlist for audio priority (no network). */
    private fun probeAudioInline(manifestText: String?): Int {
        if (manifestText.isNullOrBlank()) return 0
        val master = ManifestKit.parseMaster(manifestText) ?: return 0
        return ManifestKit.audioPriority(master)
    }

    /** Headers for vidlink.pro API + playlist requests (site Referer/Origin required). */
    private fun vidlinkHeaders(mediaPageUrl: String): Map<String, String> = mapOf(
        "User-Agent" to HttpKit.userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://vidlink.pro",
        "Referer" to mediaPageUrl,
    )

    /**
     * VidLink resolver: token API -> stream.playlist (multi-audio HLS master) ->
     * one inline-manifest RawStream. The master is fetched once here so the
     * audio probe and quality height come free (no second fetch in emit()).
     */
    private suspend fun resolveVidlink(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val apiUrl = if (type == "movie") VidlinkSource.movieApiUrl(tmdbId.toString())
        else VidlinkSource.tvApiUrl(tmdbId.toString(), season, episode)
        val mediaPage = if (type == "movie") "https://vidlink.pro/movie/$tmdbId"
        else "https://vidlink.pro/tv/$tmdbId/$season/$episode"
        Log.d("VidLink", "apiUrl=$apiUrl mediaPage=$mediaPage")

        val jsonText = withTimeoutOrNull(6_000L) {
            runCatching {
                app.get(apiUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
            }.getOrNull()
        }
        if (jsonText.isNullOrBlank()) {
            Log.w("VidLink", "no API response (timeout/HTTP error) for $mediaPage")
            return emptyList()
        }
        Log.d("VidLink", "API response length=${jsonText.length}, preview=${safeSnippet(jsonText)}")
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("VidLink", "non-JSON response for $mediaPage (${jsonText.length}B, starts: ${safeSnippet(jsonText)})")
            return emptyList()
        }

        // VidLink API error response: {"error":"Invalid token","code":2004}
        // code 2004 here is the API's token error ï¿½ NOT ExoPlayer's
        // ERROR_CODE_IO_BAD_HTTP_STATUS. It appears when the site rotates its
        // secretbox key; fix by updating VidlinkSource.KEY_HEX.
        if (root.has("error") || root.has("code")) {
            val err = root.optJSONObject("error") ?: root
            val code = err.optInt("code", -1)
            val msg = err.optString("message").ifBlank { err.optString("error") }.ifBlank { "unknown" }
            Log.w("VidLink", "API error code=$code msg=$msg (code 2004 = token key rotated; update VidlinkSource.KEY_HEX)")
            return emptyList()
        }

        val stream = root.optJSONObject("stream") ?: run {
            Log.w("VidLink", "no \"stream\" object; root keys=${namesOf(root)}")
            return emptyList()
        }

        // Captions live at stream.captions (new shape) or root.captions (legacy).
        // The `language` field carries the display name — often a NATIVE-SCRIPT
        // string (اُردُو / বাংলা / العربية / 中文 …), verified live Sept 2026.
        // `lang`/`name` do NOT exist on the current shape; reading them first is
        // what made every track show as a numbered "English 1..10" duplicate.
        val subs = (stream.optJSONArray("captions") ?: root.optJSONArray("captions"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = c.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lang = c.optString("language")
                    .ifBlank { c.optString("lang") }
                    .ifBlank { c.optString("name") }
                    .ifBlank { "English" }
                LinkNaming.canonicalSubtitleName(lang) to u
            }
        } ?: emptyList()

        // New shape (Sept 2026, sourceId mwVault/mbVault): stream.qualities maps
        // "360"/"480"/"720"/"1080" -> {type:"mp4", url (signed, TTL 3600),
        // headers:{referer,origin} (mbVault only, else {}), requiresProxy}.
        // Direct MP4s ï¿½ no playlist fetch, no speed probe (the CDN rate-limits
        // hard). Playback headers are per-source (see
        // VidlinkSource.PLAYER_HEADERS): mwVault CDN 429s on any Referer so
        // only the native UA is sent; mbVault requires the API-provided
        // headers. referer stays null so emit() injects nothing extra ï¿½ a
        // blanket vidlink.pro Referer is exactly what breaks playback with
        // ExoPlayer ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
        val qualities = stream.optJSONObject("qualities")
        if (qualities != null && qualities.length() > 0) {
            val entries = qualities.names()?.let { n ->
                (0 until n.length()).mapNotNull { i ->
                    val key = n.optString(i)
                    val height = key.toIntOrNull() ?: 0
                    // All qualities pass through here (user spec Sept 2026: the
                    // ≥720 gate moved to AUTO-PLAY selection — the server list
                    // stays unrestricted so 360/480 entries still surface for
                    // manual pick / Hindi preference / bandwidth-limited titles).
                    val q = qualities.optJSONObject(key) ?: return@mapNotNull null
                    val url = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Triple(height, url, q)
                }
            }.orEmpty().sortedByDescending { it.first }
            if (entries.isNotEmpty()) {
                Log.d("VidLink", "qualities shape: ${entries.map { it.first }}p for $mediaPage")
                return entries.map { (height, url, q) ->
                    RawStream(
                        serverId = spec.id,
                        serverName = spec.name,
                        url = url,
                        isM3u8 = false,
                        referer = null,
                        qualityHint = height,
                        subtitles = subs,
                        extraHeaders = VidlinkSource.qualityPlaybackHeaders(q),
                    )
                }
            }
            Log.w("VidLink", "qualities present but no usable urls (keys=${namesOf(qualities)})")
        }

        // Legacy shape: stream.playlist (HLS master) ï¿½ kept for when VidLink
        // serves an adaptive playlist again.
        val masterUrl = stream.optString("playlist").takeIf { it.isNotBlank() }
            ?: root.optString("url").takeIf { it.isNotBlank() }
            ?: run {
                Log.w("VidLink", "no qualities/playlist/url; stream keys=${namesOf(stream)} root keys=${namesOf(root)}")
                return emptyList()
            }

        // Fetch the master playlist once: audio priority + best height inline.
        val masterText = withTimeoutOrNull(6_000L) {
            runCatching {
                app.get(masterUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
            }.getOrNull()
        }
        val master = ManifestKit.parseMaster(masterText, masterUrl)
        val height = master?.let { ManifestKit.bestHeight(it.variants) } ?: 0
        val pri = master?.let { ManifestKit.audioPriority(it) } ?: 0

        return listOf(
            RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = masterUrl,
                isM3u8 = master != null || masterUrl.contains(".m3u8", ignoreCase = true),
                referer = null,
                qualityHint = height,
                subtitles = subs,
                audioPriority = pri,
                audioLabel = audioLabelFor(pri),
                inlineManifest = masterText?.takeIf { master != null },
                extraHeaders = VidlinkSource.PLAYER_HEADERS,
            )
        )
    }


    /**
     * MyFlixer Hindi resolver (hindi.myflixerapi.com). Sept 2026 state: the
     * HTML embed pages sit behind an anti-bot wall and the old
     * /ajax/get_stream_link endpoint is gone (404), but /api/status is a
     * clean JSON hit/miss API with no captcha:
     *   movie:  /api/status?imdb={id}&type=movie
     *   series: /api/status?imdb={id}&type=tv&sea=&epi= (episode-level checks
     *           are flaky, so show-level + type=movie fallbacks are tried and
     *           the embed chain itself validates the episode)
     * Flow:
     *   1. Status check — "failed" = not in library → CLEAN miss (no breaker
     *      trip; the library is small so most titles legitimately miss —
     *      counting those as failures tripped the breaker and hid the server).
     *   2. On a hit, warm the app cookie jar with a homepage GET (the wall is
     *      cookie-gated for plain clients), then fetch the embed page with a
     *      mobile UA (series use /embed/series?imdb=&sea=&epi=).
     *   3. If the page renders (wall passed): parse data-movie-id + the server
     *      list (data-id + "Server-{Name}" label) and run the AJAX chain;
     *      each link resolves through the standard pipeline labelled
     *      "MyFlixer Hindi {SubServer}".
     *   4. If the wall still blocks: record a failure (host-side blockage)
     *      and return empty — logged clearly so the cause is one grep away.
     */
    private suspend fun resolveMyFlixerHindi(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,

        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = imdbId ?: run {
            Log.w("MyFlixerHindi", "IMDB id required (host is IMDB-keyed), got none")
            return emptyList()
        }
        val referer = "https://hindi.myflixerapi.com/"
        val isMovie = type == "movie"

        // ── 1. Status API hit/miss (no captcha) ─────────────────────────────
        // Movies: one call. Series: episode-level first, then show-level tv,
        // then show-level movie (their own docs use type=movie for series —
        // the API accepts both; first "success" wins).
        val statusUrls = if (isMovie) {
            listOf("https://hindi.myflixerapi.com/api/status?imdb=$id&type=movie")
        } else {
            listOf(
                "https://hindi.myflixerapi.com/api/status?imdb=$id&type=tv&sea=$season&epi=$episode",
                "https://hindi.myflixerapi.com/api/status?imdb=$id&type=tv",
                "https://hindi.myflixerapi.com/api/status?imdb=$id&type=movie",
            )
        }
        var inLibrary = false
        for (statusUrl in statusUrls) {
            val statusText = withTimeoutOrNull(6_000L) {
                runCatching { app.get(statusUrl, timeout = 6, headers = okHeaders(referer)).text }.getOrNull()
            }
            if (statusText == null) {
                // Network-level failure on the status API = host problem.
                failServer(spec, "status api unreachable: $statusUrl")
                return emptyList()
            }
            val status = runCatching { org.json.JSONObject(statusText).optString("status", "") }.getOrNull()
            when (status) {
                "success" -> { inLibrary = true; break }
                "failed" -> { /* try next fallback (series) or clean miss */ }
                else -> failServer(spec, "status api unexpected body: ${statusText.take(120)}")
            }
        }
        if (!inLibrary) {
            Log.d("MyFlixerHindi", "$id not in library (clean miss, no breaker trip)")
            return emptyList()
        }
        Log.d("MyFlixerHindi", "$id in library — attempting embed chain")

        // ── 2. Embed page, both domains ────────────────────────────────────
        // The hindi. subdomain is robot-walled for plain clients (verified
        // Sept 2026) but the MAIN myflixerapi.com renders the same embed
        // chain without a wall — same library, same data-movie-id/AJAX
        // shapes. Try hindi. first (canonical Hindi host), fall back to the
        // main domain on a wall. Series episodes use /embed/series?imdb=….
        // Warm-up: the anti-bot wall is cookie-gated; a homepage GET seeds
        // the app's shared cookie jar before the embed request.
        val mobileHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://myflixerapi.com/",
        )
        val domains = listOf("hindi.myflixerapi.com", "myflixerapi.com")
        val embedPaths = if (isMovie || season <= 0)
            listOf("/embed/$id")
        else listOf("/embed/series?imdb=$id&sea=$season&epi=$episode", "/embed/$id/$season/$episode")
        runCatching {
            withTimeoutOrNull(5_000L) { app.get("https://myflixerapi.com/", timeout = 5, headers = okHeaders()) }
        }

        var embedDomain: String? = null
        var embedText: String? = null
        outer@ for (domain in domains) {
            for (path in embedPaths) {
                val url = "https://$domain$path"
                Log.d("MyFlixerHindi", "Fetching $url")
                val text = withTimeoutOrNull(8_000L) {
                    runCatching { app.get(url, timeout = 8, headers = mobileHeaders).text }.getOrNull()
                } ?: continue
                // Genuine library miss page (checked BEFORE the wall — the
                // miss page also carries the robot banner in its footer).
                if (text.contains("movie-not-found") || text.contains("Movie or Episode Not Found", ignoreCase = true)) {
                    Log.d("MyFlixerHindi", "$id not in library (embed page miss)")
                    return emptyList()
                }
                if (text.contains("not a robot", ignoreCase = true)) {
                    Log.d("MyFlixerHindi", "$domain walled — trying next domain/path")
                    continue
                }
                if (Regex("""data-movie-id="([^"]+)"""").containsMatchIn(text)) {
                    embedDomain = domain
                    embedText = text
                    break@outer
                }
                Log.d("MyFlixerHindi", "$domain$path: no data-movie-id (${text.length}B)")
            }
        }
        val rawText = embedText ?: run {
            failServer(spec, "embed blocked on all domains (wall) or no data-movie-id")
            return emptyList()
        }
        val embedBase = "https://$embedDomain"

        val movieId = Regex("""data-movie-id="([^"]+)"""").find(rawText)?.groupValues?.get(1)
        if (movieId.isNullOrBlank()) {
            failServer(spec, "no data-movie-id in embed page (${rawText.length}B)")
            return emptyList()
        }
        // Server list: <a class="server" data-id="8zj">Server-Videasy</a> —
        // capture the friendly label for "MyFlixer Hindi {Name}" link naming;
        // fall back to bare data-ids when labels are absent.
        val labeledServers = Regex("""data-id="([^"]+)"[^>]*>\s*Server-([^<]+)""").findAll(rawText)
            .mapNotNull { m ->
                val sid = m.groupValues[1]
                val label = m.groupValues[2].trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                sid to label
            }
            .toMap()
        val serverIds = if (labeledServers.isNotEmpty()) labeledServers.keys.toList()
        else Regex("""class="server dropdown-item" data-id="([^"]+)"""").findAll(rawText)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .toList()
        if (serverIds.isEmpty()) {
            failServer(spec, "no server data-ids in embed page")
            return emptyList()
        }
        Log.d("MyFlixerHindi", "movieId=$movieId servers=$serverIds labels=$labeledServers")

        // ── 3. AJAX chain on the domain that rendered the embed page ──────
        val ajaxHeaders = mobileHeaders.toMutableMap().apply {
            put("X-Requested-With", "XMLHttpRequest")
        }
        data class FlixerLink(val url: String, val label: String?)
        val links = mutableListOf<FlixerLink>()
        for (sid in serverIds) {
            val ajaxUrl = "$embedBase/ajax/get_stream_link?id=$sid&movie=$movieId&is_init=false&captcha="
            val jsonText = withTimeoutOrNull(6_000L) {
                runCatching { app.get(ajaxUrl, timeout = 6, headers = ajaxHeaders).text }.getOrNull()
            } ?: continue
            val json = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: continue
            if (!json.optBoolean("success", false)) continue
            val link = json.optJSONObject("data")?.optString("link")?.takeIf { it.isNotBlank() } ?: continue
            Log.d("MyFlixerHindi", "server=$sid link=$link")
            links.add(FlixerLink(link, labeledServers[sid]))
        }
        if (links.isEmpty()) {
            failServer(spec, "no stream links from any server (ajax may be retired)")
            return emptyList()
        }

        // Resolve each link through the standard pipeline (unwrap iframes,
        // extractor registry, harvest). The host is Hindi-flagged so results
        // rank as Hindi audio; sub-server label names the link.
        val out = mutableListOf<RawStream>()
        for (fl in links) {
            val resolved = resolveEmbedLink(spec, fl.url, referer) ?: continue
            out += resolved.map {
                if (fl.label.isNullOrBlank()) it
                else it.copy(serverName = "${spec.name} ${fl.label}".trim())
            }
        }
        if (out.isNotEmpty()) {
            okServer(spec, System.currentTimeMillis(), "myflixer-hindi ajax", out.size)
        } else {
            failServer(spec, "links resolved but produced no streams")
        }
        return out
    }
    /**
     * Videasy "Fade" (Hindi) resolver: the decrypted API returns per-audio
     * muxed streams â€” the "Hindi" quality entry IS a Hindi-dubbed HLS master
     * (verified Sept 2026: GOT S1E1/AOT S1E1 return distinct Hindi + English
     * URLs). Only sources labelled Hindi are emitted (this server exists for
     * Hindi; English comes from the rest of the farm). HLS masters are
     * quality-labelled 360â†’1080p; a master URL without a known height gets
     * qualityHint 0 (adaptive).
     */
    private suspend fun resolveVideasyHindi(
        spec: ServerSpec,
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        // Title/year help the upstream match; fetch them best-effort.
        val meta = runCatching {
            TmdbService.fetchMeta(tmdbId, type)
        }.getOrNull()
        val title = meta?.name
        val year = meta?.year?.take(4)?.toIntOrNull()

        val fetched = VideasySource.fetchSources(
            tmdbId = tmdbId, imdbId = imdbId, title = title, year = year,
            mediaType = type, season = season, episode = episode,
        )
        if (fetched.sources.isEmpty()) return emptyList()

        val out = fetched.sources
            .filter { it.quality.equals("Hindi", ignoreCase = true) }
            .map { s ->
                val isHls = s.url.contains(".m3u8", ignoreCase = true)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url, isM3u8 = isHls,
                    referer = null, qualityHint = 0, // adaptive master; heights come from variants
                    subtitles = fetched.subtitles,
                    audioPriority = 4, audioLabel = "Hindi",
                    extraHeaders = VideasySource.apiHeaders(),
                )
            }
        // The host exists only for Hindi: an English-only title is a CLEAN miss
        // (no breaker trip), otherwise three straight taps on English-only
        // content tripped the breaker and Videasy Hindi vanished from the farm.
        if (out.isEmpty()) {
            throw CleanMissException("sources present but no Hindi label: ${fetched.sources.map { it.quality }}")
        }
        return out
    }

    /** Fetch (and cache) the MovieBox bearer token from the app-pkgs response's
     *  `x-user` header. The token lives for hours (MOVIEBOX_TOKEN_TTL_MS). */
    private suspend fun fetchMovieBoxBearer(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            movieBoxToken?.takeIf {
                System.currentTimeMillis() - movieBoxTokenAt < MOVIEBOX_TOKEN_TTL_MS
            }?.let { return it }
        }
        val base = "https://h5-api.aoneroom.com"
        val xUser = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get("$base/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
                    timeout = 8, headers = okHeaders())
            }.getOrNull()
        }?.headers?.get("x-user") ?: run { Log.w("MovieBox", "no x-user header"); return null }
        val t = runCatching { org.json.JSONObject(xUser).optString("token", "") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("MovieBox", "no token in x-user"); return null }
        movieBoxToken = t
        movieBoxTokenAt = System.currentTimeMillis()
        return t
    }

    /** Public pre-warm, called once at plugin load: the token lives for hours,
     *  so one background GET at app start removes a serial round-trip
     *  (~0.5-1s) from the first MovieBox resolve of the session. */
    suspend fun prewarmMovieBoxToken(): String? = fetchMovieBoxBearer(forceRefresh = false)

    /**
     * MovieBox resolver (h5-api.aoneroom.com app API, ported from CSX
     * CineStream's invokeMoviebox, Sept 2026). Title-keyed, 4-step chain:
     *   1. GET /wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox —
     *      the response HEADER `x-user` carries JSON with a bearer `token`.
     *   2. POST /wefeed-h5api-bff/subject/search {keyword, page, perPage,
     *      subjectType:1|2} → items[] with subjectId + title; title brackets
     *      mark the audio ("Title [Hindi]") and a trailing " S1-S3" is the
     *      season coverage (stripped before matching).
     *   3. GET h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=
     *      {subjectId} → items[0].subject.detailPath (needed by step 4).
     *   4. GET /wefeed-h5api-bff/subject/download? and /subject/play?
     *      subjectId=&se=&ep=&detailPath= (Referer/Origin
     *      fmoviesunblocked.net) → downloads[]/streams[]/dash[] with
     *      {url, resolution, vipLocked} + captions[].
     * Direct MP4/HLS up to 2160p; vipLocked entries are skipped.
     */
    private suspend fun resolveMovieBox(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = runCatching { TmdbService.fetchMeta(tmdbId ?: 0, type) }.getOrNull()
        val title = meta?.name ?: run {
            // Title-keyed API without a title = nothing to search; the host is
            // fine, so a clean miss (no breaker trip) keeps MovieBox visible.
            throw CleanMissException("no title for tmdb=$tmdbId (title-keyed API)")
        }
        val seasonKey = if (season > 0) season else 1
        val episodeKey = if (episode > 0) episode else 1

        val host = "h5-api.aoneroom.com"
        val base = "https://$host"

        // 1+2. Bearer token + title search, with ONE retry (user report Sept
        // 2026: MovieBox works, then vanishes, then works — the aoneroom host
        // flaps, and a stale bearer token blanks the search even though a
        // fresh token resolves it). A failed/empty search drops the cached
        // token and retries once before giving up. The prewarmed token
        // (prewarmMovieBoxToken) usually makes the first attempt instant.
        suspend fun movieBoxBearer(forceRefresh: Boolean): String? =
            fetchMovieBoxBearer(forceRefresh)

        val subjectType = if (type == "movie") 1 else 2
        fun unwrapData(json: org.json.JSONObject): org.json.JSONObject {
            val d = json.optJSONObject("data") ?: return json
            return d.optJSONObject("data") ?: d
        }
        var baseHeaders: Map<String, String> = emptyMap()
        var searchItems: org.json.JSONArray? = null
        for (attempt in 0 until 2) {
            val token = movieBoxBearer(forceRefresh = attempt > 0) ?: return emptyList()
            baseHeaders = mapOf(
                "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept" to "application/json",
                "Referer" to base,
                "Connection" to "keep-alive",
                "Authorization" to "Bearer $token",
            )
            val searchJsonText = withTimeoutOrNull(10_000L) {
                runCatching {
                    app.post("$base/wefeed-h5api-bff/subject/search", timeout = 10, headers = baseHeaders,
                        json = mapOf(
                            "keyword" to title, "page" to 1, "perPage" to 24,
                            "subjectType" to subjectType,
                        ))
                }.getOrNull()
            }?.text
            val found = searchJsonText?.let {
                runCatching { org.json.JSONObject(it) }.getOrNull()
            }?.let { unwrapData(it).optJSONArray("items") }
            if (found != null && found.length() > 0) {
                searchItems = found
                break
            }
            Log.w("MovieBox", "search failed/empty (attempt ${attempt + 1})" +
                if (attempt == 0) " — retrying with a fresh bearer token" else "")
            movieBoxToken = null
        }
        val items = searchItems ?: run { Log.w("MovieBox", "no search items after retry"); return emptyList() }

        // "Title [Hindi]" / "Title (Hindi Dubbed)" → audio; "Title S1-S3"
        // trailing suffix is season coverage, stripped before matching. The
        // MovieBox title often also carries a year / extra bracket group
        // ("The Batman (2024)"), so match on a NORMALIZED form (lowercase,
        // alnum only) and accept an exact match OR the MovieBox title starting
        // with the TMDB title — otherwise legit titles silently miss and
        // MovieBox (the fastest source) vanishes from the farm.
        val seasonSuffix = Regex("""\s+S\d+(?:\s*-\s*S?\d+)?$""", RegexOption.IGNORE_CASE)
        val bracketGroups = Regex("""[\[(]([^\])]+)[\])]""", RegexOption.IGNORE_CASE)
        val norm: (String) -> String = { t -> t.lowercase().replace(Regex("""[^a-z0-9]"""), "") }
        val titleNorm = norm(title)
        val subjects = mutableListOf<Triple<String, Int, String?>>() // id, seasonEnd, language
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title", "")
            val seasonEnd = seasonSuffix.find(rawTitle)?.value
                ?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }?.toIntOrNull()
            // Pull the audio tag from any bracket group that carries a language
            // name (letters, not a bare year) — "Title [Hindi]", "Title (2024)".
            val audioTag = bracketGroups.findAll(rawTitle)
                .map { it.groupValues[1] }
                .firstOrNull { it.any { c -> c.isLetter() } && !it.any { c -> c.isDigit() } }
            val clean = rawTitle
                .let { seasonSuffix.replace(it, "") }
                .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "") // drop all bracket groups
                .replace(Regex("""\s*\d{4}"""), "")               // drop a stray year
                .trim()
            val cleanNorm = norm(clean)
            val matched = cleanNorm == titleNorm ||
                (titleNorm.length >= 4 && cleanNorm.startsWith(titleNorm))
            if (!matched) continue
            // 0 = no explicit "S1-S3" coverage marker: the subject is presumed
            // to cover every season (the play/download APIs take se/ep
            // directly). Treating a marker-less subject as S1-only is what
            // silently hid MovieBox on later-season episodes.
            subjects += Triple(id, seasonEnd ?: 0, audioTag)
        }
        if (subjects.isEmpty()) {
            throw CleanMissException("no exact title match for '$title' in ${items.length()} search rows")
        }
        Log.d("MovieBox", "subjects=${subjects.map { it.first + ":" + (it.third ?: "orig") }}")

        val refererBase = "https://fmoviesunblocked.net/"
        val out = mutableListOf<RawStream>()
        val seenUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Subjects resolve concurrently (CSX parity — the serial loop was the
        // main speed gap: token+search+detail+download+play per subject).
        val subjectResults = kotlinx.coroutines.coroutineScope {
            subjects.map { (subjectId, seasonEnd, language) ->
                async {
                    // Series entry EXPLICITLY covering fewer seasons than
                    // requested can't serve this episode (library splits shows
                    // into S1-S3 / S4-…). seasonEnd==0 (no marker) never skips.
                    if (type != "movie" && seasonEnd in 1 until season) return@async emptyList<RawStream>()

                    // 3. detailPath lookup.
                    val detailText = withTimeoutOrNull(8_000L) {
                        runCatching {
                            app.get("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                                timeout = 8, headers = okHeaders()).text
                        }.getOrNull()
                    } ?: return@async emptyList<RawStream>()
                    val detailPath = runCatching { org.json.JSONObject(detailText) }.getOrNull()
                        ?.optJSONObject("data")
                        ?.optJSONArray("items")?.optJSONObject(0)
                        ?.optJSONObject("subject")
                        ?.optString("detailPath", "").orEmpty()
                    if (detailPath.isBlank()) return@async emptyList<RawStream>()

                    val reqHeaders = baseHeaders + mapOf(
                        "Referer" to "$refererBase/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                        "Origin" to refererBase.trimEnd('/'),
                    )
                    val params = buildString {
                        append("subjectId=$subjectId")
                        if (type != "movie") append("&se=$seasonKey&ep=$episodeKey")
                        append("&detailPath=$detailPath")
                    }

                    // 4. download + play endpoints in parallel.
                    val (downloadObj, playObj) = kotlinx.coroutines.coroutineScope {
                        val d = async {
                            withTimeoutOrNull(8_000L) {
                                runCatching {
                                    app.get("$base/wefeed-h5api-bff/subject/download?$params",
                                        timeout = 8, headers = reqHeaders).text
                                }.getOrNull()
                            }?.let { t -> runCatching { org.json.JSONObject(t) }.getOrNull() }
                        }
                        val p = async {
                            withTimeoutOrNull(8_000L) {
                                runCatching {
                                    app.get("$base/wefeed-h5api-bff/subject/play?$params",
                                        timeout = 8, headers = reqHeaders).text
                                }.getOrNull()
                            }?.let { t -> runCatching { org.json.JSONObject(t) }.getOrNull() }
                        }
                        (d.await() ?: org.json.JSONObject()) to (p.await() ?: org.json.JSONObject())
                    }

                    // The server's OWN subtitle tracks ride in the play
                    // response: subtitles[] {url, lanName|lan} (CSX-shape
                    // verified; user spec Sept 2026: take what servers give).
                    val subjectSubs = unwrapData(playObj).optJSONArray("subtitles")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val s = arr.optJSONObject(i) ?: return@mapNotNull null
                            val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val lang = s.optString("lanName").ifBlank { s.optString("lan") }
                                .ifBlank { s.optString("language") }.ifBlank { "English" }
                            lang to u
                        }
                    } ?: emptyList()

                    fun addStreams(arr: org.json.JSONArray?, dash: Boolean): List<RawStream> {
                        if (arr == null) return emptyList()
                        val added = mutableListOf<RawStream>()
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            if (s.optBoolean("vipLocked", false)) continue
                            val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                            if (!seenUrls.add(url)) continue
                            val resolution = s.optString("resolutions", "").toIntOrNull()
                                ?: s.optInt("resolution", 0)
                            // Audio tag may be "Hindi", "Hindi Dubbed",
                            // "Dual Audio [Hindi-English]" etc — any mention
                            // of Hindi ranks as Hindi (priority 4).
                            val isHindi = language?.contains("hindi", ignoreCase = true) == true
                            added += RawStream(
                                serverId = spec.id,
                                serverName = spec.name + if (dash) " Auto" else "",
                                url = url,
                                isM3u8 = url.contains(".m3u8", ignoreCase = true) || dash,
                                referer = refererBase,
                                qualityHint = resolution,
                                subtitles = subjectSubs,
                                audioPriority = if (isHindi) 4 else 2,
                                audioLabel = language ?: "",
                            )
                        }
                        return added
                    }
                    addStreams(unwrapData(downloadObj).optJSONArray("downloads"), dash = false) +
                        addStreams(unwrapData(playObj).optJSONArray("streams"), dash = false) +
                        addStreams(unwrapData(playObj).optJSONArray("dash"), dash = true)
                }
            }.awaitAll()
        }
        subjectResults.forEach { out += it }
        // Every matched subject explicitly covers fewer seasons than requested
        // ("S1-S3" markers only) — the episode is a library miss, not a host
        // failure: clean miss keeps MovieBox in the farm for other episodes.
        if (out.isEmpty() && type != "movie" && subjects.isNotEmpty() &&
            subjects.all { it.second in 1 until season }
        ) {
            throw CleanMissException("subjects cover up to S${subjects.maxOf { it.second }}, requested S$season")
        }

        Log.d("MovieBox", "got ${out.size} streams from ${subjects.size} subjects")
        return out
    }

    /**
     * PrimeSrc resolver (primesrc.me, verified live Sept 2026). IMDB-keyed,
     * 2-step: /api/v1/s?imdb={id}&type=movie|tv[&season=&episode=] returns
     * info + servers[] ({name, key, file_name, audio_language}); each key
     * exchanges at /api/v1/l?key={key} → {"link": "<player url>"} which is
     * resolved through the standard embed pipeline. audio_language "hi" marks
     * Hindi dubs (priority 4); everything else is original audio (2).
     */
    private suspend fun resolvePrimeSrc(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val imdb = imdbId ?: run { Log.w("PrimeSrc", "IMDB id required"); return emptyList() }
        val referer = "https://primesrc.me/"
        val apiUrl = if (type == "movie") "https://primesrc.me/api/v1/s?imdb=$imdb&type=movie"
        else "https://primesrc.me/api/v1/s?imdb=$imdb&type=tv&season=$season&episode=$episode"
        Log.d("PrimeSrc", "GET $apiUrl")

        val jsonText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(4) * 1000L) {
            runCatching { app.get(apiUrl, timeout = spec.timeoutSec.toLong(), headers = okHeaders(referer)).text }.getOrNull()
        } ?: run { Log.w("PrimeSrc", "api fetch failed"); return emptyList() }
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("PrimeSrc", "not JSON"); return emptyList()
        }
        val servers = root.optJSONArray("servers")
            ?: run { Log.w("PrimeSrc", "no servers array"); return emptyList() }

        val out = mutableListOf<RawStream>()
        for (i in 0 until servers.length()) {
            val sv = servers.optJSONObject(i) ?: continue
            val key = sv.optString("key").takeIf { it.isNotBlank() } ?: continue
            val name = sv.optString("name").ifBlank { "PrimeSrc" }
            val fileName = sv.optString("file_name", "")
            val lang = sv.optString("audio_language", "")
            val isHindi = lang.equals("hi", ignoreCase = true)
            val resolved = withTimeoutOrNull(6_000L) {
                runCatching {
                    app.get("https://primesrc.me/api/v1/l?key=$key",
                        timeout = 6, headers = okHeaders(referer)).text
                }.getOrNull()
            } ?: continue
            val link = runCatching { org.json.JSONObject(resolved).optString("link", "") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            Log.d("PrimeSrc", "server=$name lang=$lang link=$link")
            val streams = resolveEmbedLink(spec, link, referer,
                audioPriority = if (isHindi) 4 else 2,
                audioLabel = if (isHindi) "Hindi" else "Original",
            ) ?: continue
            // Sub-server name distinguishes same-host duplicates; the file
            // name carries the real resolution ("… 1080p …") when the player
            // link itself reports none.
            val height = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(fileName)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out += streams.map {
                it.copy(
                    serverName = "${spec.name} $name".trim(),
                    qualityHint = maxOf(it.qualityHint, height),
                )
            }
        }
        Log.d("PrimeSrc", "got ${out.size} streams")
        return out
    }

    /** Resolve one player/stream link through the multi-strategy pipeline
     *  (unwrap → harvest → extractor registry). Returns null on failure.
     *  Audio ranking is parameterized: MyFlixer Hindi defaults to Hindi (4);
     *  PrimeSrc passes per-server values from audio_language. */
    private suspend fun resolveEmbedLink(
        spec: ServerSpec, link: String, referer: String,
        audioPriority: Int = 4, audioLabel: String = "Hindi",
    ): List<RawStream>? {
        val rawText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(link, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return null
        val (unwrapped, unwrappedUrl) = unwrapPages(rawText, link, spec.timeoutSec)

        // Direct stream URLs in the page/JS config.
        val direct = harvestUrls(unwrapped) + harvestJsUrls(unwrapped)
        if (direct.isNotEmpty()) {
            return direct.map { url ->
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = url, isM3u8 = url.contains(".m3u8", ignoreCase = true),
                    referer = referer, audioPriority = audioPriority, audioLabel = audioLabel,
                )
            }
        }

        // CloudStream extractor registry on the inner player host.
        val regLinks = mutableListOf<ExtractorLink>()
        val regSubs = mutableListOf<SubtitleFile>()
        runCatching {
            loadExtractor(url = unwrappedUrl, referer = link,
                subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
        }
        if (regLinks.isNotEmpty()) {
            return regLinks.map { l ->
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = l.url, isM3u8 = l.type == ExtractorLinkType.M3U8,
                    referer = l.referer, qualityHint = l.quality,
                    subtitles = regSubs.map { it.lang to it.url },
                    audioPriority = audioPriority, audioLabel = audioLabel,
                )
            }
        }
        return null
    }

    /**
     * NHD resolver (reversed Sept 2026): the extraction API key is embedded
     * PER-PAGE-LOAD (stale keys 401), so:
     *   1. GET nhdapi.com/movie/{id} (or /tv/{id}/{s}/{e}) and extract
     *      `var API_KEY = "..."` and `/api/movie/{id}` from the page JS.
     *   2. GET nhdapi.com/api/movie/{id}?key=... with the page as Referer.
     *   3. Response carries playUrl (their /api/hls?t= proxy, needs the
     *      page's exact UA) â€” emit with page UA + no Referer.
     *audioTracks field (per-dub sibling URLs) marks Hindi dubs.
     */
    private suspend fun resolveNhd(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId?.toString() ?: return emptyList()
        val pageUrl = if (type == "movie") "https://nhdapi.com/movie/$id"
        else "https://nhdapi.com/tv/$id/$season/$episode"
        val referer = "https://nhdapi.com/"
        Log.d("NHD", "page=$pageUrl")

        // 1. Page first â€” carries the per-load API key.
        val pageText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(pageUrl, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: run { Log.w("NHD", "page fetch failed"); return emptyList() }
        val apiKey = Regex("""var\s+API_KEY\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
        if (apiKey.isNullOrBlank()) {
            Log.w("NHD", "no API_KEY in page (keys embedded only when service is up)")
            return emptyList()
        }
        val apiPath = Regex("""var\s+API_PATH\s*=\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)
            ?: if (type == "movie") "/api/movie/$id" else "/api/tv/$id"

        // 2. Extraction API.
        val apiUrl = "https://nhdapi.com$apiPath?key=$apiKey"
        val jsonText = withTimeoutOrNull(10_000L) {
            runCatching {
                app.get(apiUrl, timeout = 10, headers = okHeaders(pageUrl)).text
            }.getOrNull()
        } ?: run { Log.w("NHD", "extraction API no response"); return emptyList() }
        val json = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("NHD", "non-JSON extraction response: ${safeSnippet(jsonText)}")
            return emptyList()
        }
        if (!json.optBoolean("success", false)) {
            Log.w("NHD", "extraction API success=false: ${safeSnippet(jsonText)}")
            return emptyList()
        }

        val subs = mutableListOf<Pair<String, String>>()
        // The page JS maps /api/subtitles â€” the JSON carries none, skip.

        val playUrl = json.optString("playUrl").takeIf { it.isNotBlank() }
        val kind = json.optString("kind").ifBlank { "hls" }
        val audioTracks = json.optJSONArray("audioTracks")

        // audioTracks is null on the single-dub path; per-dub sibling URLs list
        // each dub as its own manifest. Hindi naming appears in the label.
        val out = mutableListOf<RawStream>()
        if (audioTracks != null && audioTracks.length() > 0) {
            for (i in 0 until audioTracks.length()) {
                val t = audioTracks.optJSONObject(i) ?: continue
                val url = t.optString("url").takeIf { it.isNotBlank() } ?: continue
                val label = t.optString("label").ifBlank { t.optString("name") }.ifBlank { "Audio" }
                val isHindi = label.contains("hindi", ignoreCase = true)
                out += RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = url, isM3u8 = url.contains(".m3u8", true) || kind == "hls",
                    referer = null, qualityHint = 0, subtitles = subs,
                    audioPriority = if (isHindi) 4 else 1,
                    audioLabel = if (isHindi) "Hindi" else label,
                    extraHeaders = mapOf("User-Agent" to NHD_UA),
                )
            }
        } else if (!playUrl.isNullOrBlank()) {
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = playUrl, isM3u8 = kind != "mp4",
                referer = null, qualityHint = 0, subtitles = subs,
                extraHeaders = mapOf("User-Agent" to NHD_UA),
            )
        }
        if (out.isEmpty()) Log.w("NHD", "no playUrl/audioTracks in response; keys=${namesOf(json)}")
        return out
    }

    /**
     * VaPlayer resolver (CSX CineStream, verified Sept 2026): IMDB-keyed JSON
     * API â†’ `data.stream_urls[]` are DIRECT HLS master playlists (up to
     * 1920x800 â‰ˆ 1080p). Zero crypto. Referer nextgencloudfabric.com required
     * on both API and playlist fetches.
     */
    private suspend fun resolveVaplayer(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val imdb = imdbId ?: run { Log.w("VaPlayer", "imdbId required"); return emptyList() }
        val apiUrl = if (type == "movie")
            "https://streamdata.vaplayer.ru/api.php?imdb=$imdb&type=movie"
        else
            "https://streamdata.vaplayer.ru/api.php?imdb=$imdb&type=tv&season=$season&episode=$episode"
        val referer = "https://nextgencloudfabric.com/"
        val headers = okHeaders(referer)
        Log.d("VaPlayer", "GET $apiUrl")

        val jsonText = withTimeoutOrNull(10_000L) {
            runCatching { app.get(apiUrl, timeout = 10, headers = headers).text }.getOrNull()
        } ?: run { Log.w("VaPlayer", "no API response"); return emptyList() }
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("VaPlayer", "non-JSON response: ${safeSnippet(jsonText)}")
            return emptyList()
        }
        val statusCode = root.optInt("status_code", 0)
        if (statusCode != 200) {
            // 404 = title not in the VaPlayer catalog: clean miss (no breaker
            // trip) — the host is up, this title just isn't in it.
            if (statusCode == 404) throw CleanMissException("not in VaPlayer catalog (404)")
            Log.w("VaPlayer", "status_code=$statusCode")
            return emptyList()
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val urls = data.optJSONArray("stream_urls") ?: return emptyList()
        // The file_name carries the real height ("Inception (2010) [1080p]/…").
        // VaPlayer returns several masters (one per CDN/height); without a real
        // height they all share qualityHint=0 and collapse in dedupeNames into a
        // single numbered group ("VaPlayer-1 … -3"), which reads as the server
        // appearing multiple times. Seed each URL with its height so they stay
        // distinct (the emit() master re-parse refines it further).
        val fileName = data.optString("file_name", "")
        // Server-provided height hint from file_name; fall back to 0 (adaptive) so emit()
        // measures the real master height instead of falsely claiming 1080p.
        val fileNameHeight = Regex("""\[(\d{3,4})p\]""", RegexOption.IGNORE_CASE)
            .find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val subs = data.optJSONArray("default_subs")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                (s.optString("lang").ifBlank { s.optString("code").ifBlank { "English" } }) to u
            }
        } ?: emptyList()

        val out = mutableListOf<RawStream>()
        for (i in 0 until urls.length()) {
            val u = urls.optString(i).takeIf { it.isNotBlank() } ?: continue
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = u, isM3u8 = true, referer = referer,
                qualityHint = fileNameHeight, subtitles = subs,
            )
        }
        Log.d("VaPlayer", "got ${out.size} master playlists")
        return out
    }

    /**
     * VidRock resolver (CSX CineStream, verified Sept 2026): TMDB-keyed JSON
     * API â†’ `{serverName: {url (AES-GCM b64url), language, type}}`. URLs are
     * decrypted locally (key static, matches CSX's decryptVidrockUrl):
     * 12-byte nonce prefix + ciphertext+tag, AES/GCM/NoPadding.
     * `language` field marks "Hindi" when the server carries a Hindi dub.
     */
    private suspend fun resolveVidrock(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        val apiUrl = if (type == "movie") "https://vidrock.ru/api/movie/$id/"
        else "https://vidrock.ru/api/tv/$id/$season/$episode/"
        val headers = mapOf(
            "User-Agent" to HttpKit.userAgent,
            "Origin" to "https://vidrock.ru",
            "Referer" to "https://vidrock.ru/",
        )
        Log.d("VidRock", "GET $apiUrl")

        val jsonText = withTimeoutOrNull(10_000L) {
            runCatching { app.get(apiUrl, timeout = 10, headers = headers).text }.getOrNull()
        } ?: run { Log.w("VidRock", "no API response"); return emptyList() }
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("VidRock", "non-JSON response: ${safeSnippet(jsonText)}")
            return emptyList()
        }

        val out = mutableListOf<RawStream>()
        val names = root.names() ?: return emptyList()
        for (i in 0 until names.length()) {
            val serverName = names.optString(i)
            val sd = root.optJSONObject(serverName) ?: continue
            val enc = sd.optString("url").takeIf { it.isNotBlank() && it != "error" && it != "null" } ?: continue
            val decrypted = decryptVidrockUrl(enc) ?: run {
                Log.w("VidRock", "decrypt failed for $serverName"); continue
            }
            val lang = sd.optString("language").ifBlank { "" }
            val isHindi = lang.contains("hindi", ignoreCase = true)
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = decrypted,
                isM3u8 = decrypted.contains(".m3u8", true) || sd.optString("type") == "hls",
                referer = "https://vidrock.ru/",
                qualityHint = 0, subtitles = emptyList(),
                audioPriority = if (isHindi) 4 else 1,
                audioLabel = lang.ifBlank { "" },
            )
        }
        Log.d("VidRock", "got ${out.size} servers (${out.map { it.audioLabel }.distinct()})")
        return out
    }

    /** VidRock AES-GCM decrypt â€” 12-byte nonce prefix, static 32-byte hex key.
     *  Port of CSX's decryptVidrockUrl (verified against live payload). */
    private fun decryptVidrockUrl(payload: String): String? = runCatching {
        val keyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val std = payload.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        val data = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        require(data.size > 12 + 16) { "payload too short" }
        val nonce = data.copyOfRange(0, 12)
        val cipherText = data.copyOfRange(12, data.size)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    /**
     * VidEm resolver (2embed.cc's real player, reversed Sept 2026):
     *   1. GET videm.xyz/embed/{movie|tv}/{imdb}[/s/e] â€” page carries a signed
     *      `Q = {..., "ssr":{"servers":[{"ref","name","lang"},...]}}` object.
     *   2. For each server ref: GET api.php?a=play&ref=...&t={Q.t} â†’
     *      {"url":"/_stream?id=...","type":"hls"}.
     *   3. /_stream URLs are HLS masters (up to 1080p); no Referer needed at
     *      playback. lang field (null/English/Hindi) drives audio ranking.
     */
    private suspend fun resolveVidem(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val imdb = imdbId ?: run { Log.w("VidEm", "imdbId required"); return emptyList() }
        val embedUrl = if (type == "movie") "https://videm.xyz/embed/movie/$imdb"
        else "https://videm.xyz/embed/tv/$imdb/$season/$episode"
        val referer = "https://videm.xyz/"
        Log.d("VidEm", "GET $embedUrl")

        val pageText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(4) * 1000L) {
            runCatching { app.get(embedUrl, timeout = ((spec.timeoutSec - 2).coerceAtLeast(4)).toLong(), headers = okHeaders(referer)).text }.getOrNull()
        } ?: run { Log.w("VidEm", "embed page fetch failed"); return emptyList() }

        // Extract Q = {...} (single line JSON with ssr.servers[] + token t).
        val qStart = pageText.indexOf("Q = {")
        if (qStart < 0) { Log.w("VidEm", "no Q object on page"); return emptyList() }
        val qJson = extractBalancedJson(pageText, qStart + 4) ?: run {
            Log.w("VidEm", "Q object unparseable"); return emptyList()
        }
        val q = runCatching { org.json.JSONObject(qJson) }.getOrElse {
            Log.w("VidEm", "Q not JSON: ${safeSnippet(qJson)}"); return emptyList()
        }
        val token = q.optString("t").takeIf { it.isNotBlank() } ?: run {
            Log.w("VidEm", "Q has no token"); return emptyList()
        }
        val ssr = q.optJSONObject("ssr") ?: run { Log.w("VidEm", "Q has no ssr"); return emptyList() }
        val servers = ssr.optJSONArray("servers") ?: run { Log.w("VidEm", "ssr has no servers"); return emptyList() }

        val out = mutableListOf<RawStream>()
        for (i in 0 until servers.length()) {
            val sv = servers.optJSONObject(i) ?: continue
            val ref = sv.optString("ref").takeIf { it.isNotBlank() } ?: continue
            val svName = sv.optString("name").ifBlank { "VidEm" }
            val lang = sv.optString("lang").ifBlank { "" }
            val playApi = "https://videm.xyz/api.php?a=play&ref=${java.net.URLEncoder.encode(ref, "UTF-8")}" +
                "&t=${java.net.URLEncoder.encode(token, "UTF-8")}"
            val playJsonText = withTimeoutOrNull(6_000L) {
                runCatching { app.get(playApi, timeout = 6, headers = okHeaders(referer)).text }.getOrNull()
            } ?: continue
            val playJson = runCatching { org.json.JSONObject(playJsonText) }.getOrElse { continue }
            val streamPath = playJson.optString("url").takeIf { it.isNotBlank() } ?: continue
            val streamUrl = if (streamPath.startsWith("http")) streamPath
            else "https://videm.xyz$streamPath"
            val isHindi = lang.contains("hindi", ignoreCase = true)
            out += RawStream(
                serverId = spec.id, serverName = "${spec.name} ${svName.substringAfter("Server ")}".trim(),
                url = streamUrl,
                isM3u8 = playJson.optString("type") == "hls" || streamUrl.contains(".m3u8", true),
                referer = null, qualityHint = 0, subtitles = emptyList(),
                audioPriority = if (isHindi) 4 else 1,
                audioLabel = lang,
            )
        }
        Log.d("VidEm", "got ${out.size} streams from ${servers.length()} server refs")
        return out
    }

    /** Extract a balanced {...} JSON object starting at [start] (which points
     *  at '{'). Handles nested braces and strings with escapes â€” enough for
     *  the videm Q object. Returns null if unbalanced. */
    private fun extractBalancedJson(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private suspend fun resolveEzvidapi(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = if (spec.idType == ServerIdType.IMDB) imdbId ?: return emptyList() else tmdbId?.toString() ?: return emptyList()
        val apiUrl = if (type == "movie") {
            "https://ezvidapi.com/movie/vidsrc/$id"
        } else {
            "https://ezvidapi.com/tv/vidsrc/$id/$season/$episode"
        }
        val referer = "https://ezvidapi.com/"
        Log.d("EzvidAPI", "Fetching $apiUrl")
        val jsonText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(apiUrl, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return emptyList()

        // Try to parse JSON
        val json = runCatching { org.json.JSONObject(jsonText) }.getOrNull()
        val streamUrl = json?.optString("url")?.takeIf { it.isNotBlank() }
            ?: json?.optString("stream")?.takeIf { it.isNotBlank() }
            ?: jsonText.trim().takeIf { it.startsWith("http") } // fallback: plain URL

        if (streamUrl == null) {
            Log.w("EzvidAPI", "No URL in response: ${safeSnippet(jsonText)}")
            return emptyList()
        }

        val pri = probeAudio(streamUrl, referer)
        return listOf(
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = streamUrl, isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true),
                referer = referer, audioPriority = pri, audioLabel = audioLabelFor(pri)
            )
        )
    }

    /**
     * 8Stream resolver (himanshu8443/8StreamApi, IMDB-keyed). /mediaInfo
     * returns per-language playlist entries ({title:"Hindi", file, …} plus a
     * shared key); each file exchanges at POST /api/v1/getStream for a DIRECT
     * HLS master. Two calls per language, zero captcha. Movies only — the API
     * documents no season/episode targeting and playing the wrong episode is
     * worse than not playing.
     */
    private suspend fun resolve8Stream(
        spec: ServerSpec,
        imdbId: String?,
        type: String,
    ): List<RawStream> {
        val imdb = imdbId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (type != "movie") {
            // The API has no episode targeting: a series tap is a known
            // non-capability, not a host failure — clean miss (no breaker trip)
            // keeps the server alive for movie taps.
            throw CleanMissException("tv not supported (no episode targeting in the API)")
        }
        val base = "https://8-stream-api.vercel.app"
        val headers = okHeaders("$base/")
        val infoText = withTimeoutOrNull(8_000L) {
            runCatching { app.get("$base/api/v1/mediaInfo?id=$imdb", timeout = 8, headers = headers).text }.getOrNull()
        } ?: run { Log.w("8Stream", "no mediaInfo response"); return emptyList() }
        val root = runCatching { org.json.JSONObject(infoText) }.getOrElse {
            Log.w("8Stream", "mediaInfo not JSON"); return emptyList()
        }
        val data = root.optJSONObject("data") ?: run { Log.w("8Stream", "no data object"); return emptyList() }
        val key = data.optString("key")
        val playlist = data.optJSONArray("playlist") ?: run { Log.w("8Stream", "no playlist"); return emptyList() }

        val out = mutableListOf<RawStream>()
        kotlinx.coroutines.coroutineScope {
            (0 until playlist.length()).mapNotNull { i ->
                val entry = playlist.optJSONObject(i) ?: return@mapNotNull null
                val lang = entry.optString("title").ifBlank { "Multi" }
                val file = entry.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                async {
                    val linkJson = withTimeoutOrNull(8_000L) {
                        runCatching {
                            app.post("$base/api/v1/getStream", timeout = 8, headers = headers,
                                json = mapOf("file" to file, "key" to key))
                        }.getOrNull()
                    } ?: return@async
                    val link = runCatching {
                        org.json.JSONObject(linkJson.text).optJSONObject("data")?.optString("link")
                    }.getOrNull()?.takeIf { it.startsWith("http") } ?: return@async
                    synchronized(out) {
                        out += RawStream(
                            serverId = spec.id, serverName = spec.name,
                            url = link, isM3u8 = link.contains(".m3u8", ignoreCase = true),
                            referer = "$base/", qualityHint = 0,
                            audioPriority = if (lang.contains("hindi", ignoreCase = true)) 4 else 1,
                            audioLabel = lang,
                        )
                    }
                }
            }.awaitAll()
        }
        Log.d("8Stream", "got ${out.size} language streams")
        return out
    }

    /**
     * Vidup/Vidcore resolver — enc-dec.app pipeline (verified Sept 2026).
     * Both players share the same moon.peakstorm.top HLS backend; the only
     * difference is the domain prefix and enc-dec endpoint suffix.
     *
     * Pipeline (4 chained HTTP calls, ~1.5s total):
     * 1. GET page → regex "en":"token" from inline script
     * 2. GET enc-dec.app/api/enc-{variant}?text={token} → {servers, stream, token}
     * 3. POST servers (X-CSRF-Token) → encrypted sub-server JSON
     * 4. POST enc-dec.app/api/dec-{variant} → [{name:"Euro"|"CineX"|"Zenith"|"Premier", ...}]
     * 5. For each sub-server: POST {stream}/{data} → dec → {url, tracks, 4kAvailable}
     *
     * Sub-server names carry quality hints: "Premier" = 4K.
     * Hindi muxed on Bollywood titles (Dangal = "Original audio" = Hindi).
     */
    private suspend fun resolveEncDecPlayer(
        spec: ServerSpec,
        keyId: String?, // TMDB or IMDB id — both hosts accept either in the URL path
        type: String,
        season: Int,
        episode: Int,
        variant: String, // "vidup" or "vidcore"
    ): List<RawStream> {
        val id = keyId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val pageUrl = if (type == "movie") "${spec.referer!!.trimEnd('/')}/movie/$id"
            else "${spec.referer!!.trimEnd('/')}/tv/$id/$season/$episode"
        val referer = spec.referer!!

        // 1. Page → token
        val pageText = withTimeoutOrNull(10_000L) {
            runCatching { app.get(pageUrl, timeout = 10, headers = okHeaders(referer)).text }.getOrNull()
        } ?: run { Log.w(variant, "page timeout"); return emptyList() }
        val token = Regex("""\\"(?:en|token)\\":\\"([^\\]+)\\"""")
            .find(pageText)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: run { Log.w(variant, "no token in page"); return emptyList() }
        val encToken = java.net.URLEncoder.encode(token, "UTF-8")

        // 2. enc-dec encrypt
        val encText = withTimeoutOrNull(8_000L) {
            runCatching { app.get("https://enc-dec.app/api/enc-$variant?text=$encToken", timeout = 8).text }.getOrNull()
        } ?: return emptyList()
        val encRoot = runCatching { org.json.JSONObject(encText) }.getOrNull() ?: return emptyList()
        val encResult = encRoot.optJSONObject("result") ?: return emptyList()
        val serversUrl = encResult.optString("servers").takeIf { it.isNotBlank() } ?: return emptyList()
        val streamBase = encResult.optString("stream").takeIf { it.isNotBlank() } ?: return emptyList()
        // enc-dec.app (live probe Sept 2026): the `token`/CSRF field is now
        // often EMPTY and the servers endpoint no longer validates it —
        // requiring a non-blank token here killed VidUp/VidCore for every
        // title. Send the header only when present.
        val csrfToken = encResult.optString("token", "")

        // 3. POST servers → encrypted sub-server list
        val srvHeaders = okHeaders(referer).toMutableMap()
        if (csrfToken.isNotBlank()) srvHeaders["X-CSRF-Token"] = csrfToken
        val srvEnc = withTimeoutOrNull(8_000L) {
            runCatching { app.post(serversUrl, timeout = 8, headers = srvHeaders).text }.getOrNull()
        } ?: return emptyList()

        // 4. decrypt sub-server list
        val srvDec = withTimeoutOrNull(8_000L) {
            runCatching {
                app.post("https://enc-dec.app/api/dec-$variant",
                    timeout = 8, headers = mapOf("Content-Type" to "application/json"),
                    json = mapOf("text" to srvEnc.trim())).text
            }.getOrNull()
        } ?: return emptyList()
        val srvArr = runCatching { org.json.JSONObject(srvDec).optJSONArray("result") }.getOrNull()
            ?: run { Log.w(variant, "dec returned no result array"); return emptyList() }

        val out = mutableListOf<RawStream>()
        for (i in 0 until srvArr.length()) {
            val srv = srvArr.optJSONObject(i) ?: continue
            val srvName = srv.optString("name").ifBlank { "Server${i + 1}" }
            val srvData = srv.optString("data").takeIf { it.isNotBlank() } ?: continue
            val desc = srv.optString("description", "")
            val is4k = desc.contains("4K", ignoreCase = true) || srvName.contains("Premier", ignoreCase = true)

            // 5. POST stream/{data} → final URL
            val streamUrl = "$streamBase/$srvData"
            val streamEnc = withTimeoutOrNull(8_000L) {
                runCatching { app.post(streamUrl, timeout = 8, headers = srvHeaders).text }.getOrNull()
            } ?: continue
            val decText = withTimeoutOrNull(8_000L) {
                runCatching {
                    app.post("https://enc-dec.app/api/dec-$variant",
                        timeout = 8, headers = mapOf("Content-Type" to "application/json"),
                        json = mapOf("text" to streamEnc.trim())).text
                }.getOrNull()
            } ?: continue
            val decResult = runCatching { org.json.JSONObject(decText) }.getOrNull()
                ?.optJSONObject("result") ?: continue
            val m3u8 = decResult.optString("url").takeIf { it.startsWith("http") } ?: continue
            val subTracks = decResult.optJSONArray("tracks")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    val t = arr.optJSONObject(j) ?: return@mapNotNull null
                    val u = t.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    (t.optString("label").ifBlank { "English" }) to u
                }
            } ?: emptyList()

            out += RawStream(
                serverId = spec.id,
                serverName = "${spec.name} $srvName",
                url = m3u8, isM3u8 = true, referer = referer,
                qualityHint = 0, // adaptive: emit() measures the real master height
                subtitles = subTracks,
                audioPriority = 0, // muxed audio — no EXT-X-MEDIA tracks to probe
                audioLabel = "",
            )
        }
        Log.d(variant, "got ${out.size} streams from ${srvArr.length()} sub-servers")
        return out
    }

    /**
     * Allmovieland resolver — DLE CMS with per-language HLS playlists
     * (verified Sept 2026). Hindi, Bengali, Tamil, Telugu — each language
     * is a separate HLS stream, so true selectable multi-audio.
     *
     * Pipeline:
     * 1. IMDB-keyed DLE search → find card URL (allmovieland.{art|one}/NNN-slug.html)
     * 2. Card page → extract AwsIndStreamDomain (self-updating) + IndStreamPlayerConfigs.src (IMDB id)
     * 3. GET {domain}/play/{imdb} → script with file + key
     * 4. GET {cdnDomain}/playlist/{file}.txt (X-Csrf-Token: {key}) → JSON [{title:"Hindi", file:..., id:...}]
     * 5. Per language: GET {cdnDomain}/playlist/{lang.file}.txt → signed m3u8 URL
     */
    private suspend fun resolveAllmovieland(
        spec: ServerSpec,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val imdb = imdbId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val headers = okHeaders("https://allmovieland.one/")

        // 1. Search by IMDB id
        val searchUrl = "https://allmovieland.one/?do=search&subaction=search&story=$imdb"
        val searchHtml = withTimeoutOrNull(12_000L) {
            runCatching { app.get(searchUrl, timeout = 12, headers = headers).text }.getOrNull()
        } ?: run { Log.w("Allmovieland", "search timeout"); throw IllegalStateException("allmovieland search timeout (network)") }
        // Cards link to allmovieland.{art|one}/NNN-slug.html
        val cardUrl = Regex("""href="(https?://allmovieland\.[a-z]+/[^"]+\.html)"\s*>\s*<h3 class="new-short__title""")
            .find(searchHtml)?.groupValues?.get(1)
            ?: run { throw CleanMissException("no card found for $imdb (title not in library)") }

        // 2. Card page → player domain + IMDB src
        val cardHtml = withTimeoutOrNull(12_000L) {
            runCatching { app.get(cardUrl, timeout = 12, headers = okHeaders(cardUrl)).text }.getOrNull()
        } ?: throw IllegalStateException("allmovieland card fetch failed (network)")
        val playerDomain = Regex("""AwsIndStreamDomain\s*=\s*'([^']+)'""")
            .find(cardHtml)?.groupValues?.get(1)?.trimEnd('/')
            ?: run { Log.w("Allmovieland", "no AwsIndStreamDomain"); throw IllegalStateException("allmovieland parse: no AwsIndStreamDomain") }
        val playSrc = Regex("""src:\s*'([^']+)'""")
            .find(cardHtml)?.groupValues?.get(1)
            ?: run { Log.w("Allmovieland", "no src in player config"); throw IllegalStateException("allmovieland parse: no src") }

        // 3. Play page → file + key. The page serves the file URL inside an
        // ESCAPED JSON string ("https:\/\/cdn...\/playlist\/...") — captured
        // raw it breaks both startsWith("http") (passes: "https:\..." starts
        // with "http") and substringBefore("/playlist/") (the literal
        // "/playlist/" never appears in "\/playlist\/"). Unescape first.
        val playUrl = "$playerDomain/play/$playSrc"
        val playHtml = withTimeoutOrNull(10_000L) {
            runCatching { app.get(playUrl, timeout = 10, headers = okHeaders(cardUrl)).text }.getOrNull()
        } ?: throw IllegalStateException("allmovieland play fetch failed (network)")
        val file = Regex("""["']?file["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(playHtml)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: run { Log.w("Allmovieland", "no file in play page"); throw IllegalStateException("allmovieland parse: no file") }
        val key = Regex("""["']?key["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(playHtml)?.groupValues?.get(1)
            ?: run { Log.w("Allmovieland", "no key in play page"); throw IllegalStateException("allmovieland parse: no key") }

        // file may be: an absolute URL (movies) OR a path that already starts
        // with "/playlist/" (series) — the naive "$playerDomain/playlist/$file"
        // doubled the prefix on series and 404'd the fetch.
        val fileUrl = when {
            file.startsWith("http") -> file
            file.startsWith("/playlist/") -> "$playerDomain$file"
            else -> "$playerDomain/playlist/$file"
        }
        val cdnBase = fileUrl.substringBefore("/playlist/")

        // 4. Language playlist (file = encrypted path like "B64hash.txt")
        val playlistHeaders = okHeaders(playUrl).toMutableMap()
        playlistHeaders["X-Csrf-Token"] = key
        val playlistText = withTimeoutOrNull(10_000L) {
            runCatching { app.get(fileUrl, timeout = 10, headers = playlistHeaders).text }.getOrNull()
        } ?: run { Log.w("Allmovieland", "playlist timeout"); throw IllegalStateException("allmovieland playlist timeout (network)") }
        val playlist = runCatching { org.json.JSONArray(playlistText) }.getOrElse {
            Log.w("Allmovieland", "playlist not JSON array"); throw IllegalStateException("allmovieland playlist parse error") }

        // Series shape (verified live Sept 2026): each top entry is a SEASON
        // ("title":"Season 1") whose folder[] holds EPISODES, each holding a
        // folder[] of per-language leaves {file, title:"Hindi"}. Movies have
        // the flat shape {title:"Hindi", file}. Pick the requested episode
        // (season/episode > 0) or fall back to the first episode of the
        // season; leaves from the matched episode keep the movie loop below.
        data class LangLeaf(val lang: String, val file: String)
        fun collectLeaves(arr: org.json.JSONArray, depth: Int): List<LangLeaf> {
            val leaves = mutableListOf<LangLeaf>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val nested = e.optJSONArray("folder")
                if (nested != null && depth < 2) {
                    leaves += collectLeaves(nested, depth + 1)
                } else if (!e.optString("file").isNullOrBlank()) {
                    leaves += LangLeaf(e.optString("title").ifBlank { "Multi" }, e.optString("file"))
                }
            }
            return leaves
        }
        val leaves: List<LangLeaf> = if (type != "movie" && season > 0) {
            val seasonObj = (0 until playlist.length())
                .mapNotNull { playlist.optJSONObject(it) }
                .firstOrNull {
                    // "Season 1" / "S1" style titles, or id match.
                    it.optString("title").contains(Regex("""\b$season\b""")) ||
                        it.optString("id") == season.toString()
                } ?: playlist.optJSONObject(0)
            val seasonFolder = seasonObj?.optJSONArray("folder")
            if (seasonFolder == null) emptyList()
            else {
                val episodeObj = (0 until seasonFolder.length())
                    .mapNotNull { seasonFolder.optJSONObject(it) }
                    .firstOrNull {
                        it.optString("episode") == episode.toString() ||
                            it.optString("id") == "$season-$episode"
                    } ?: seasonFolder.optJSONObject(0)
                episodeObj?.optJSONArray("folder")?.let { collectLeaves(it, 0) } ?: emptyList()
            }
        } else emptyList()

        // 5. Per-language: fetch each language's playlist → m3u8 URL
        val out = mutableListOf<RawStream>()
        val langEntries = leaves.ifEmpty {
            (0 until playlist.length()).mapNotNull { playlist.optJSONObject(it) }
                .filter { !it.optString("file").isNullOrBlank() }
                .map { LangLeaf(it.optString("title").ifBlank { "Multi" }, it.optString("file")) }
        }
        for (leaf in langEntries) {
            val langTitle = leaf.lang
            val langFile = leaf.file.replace("\\/", "/")
            val langUrl = if (langFile.startsWith("http")) langFile
            else "$cdnBase/playlist/$langFile.txt"
            val m3u8Text = withTimeoutOrNull(8_000L) {
                runCatching { app.get(langUrl, timeout = 8, headers = playlistHeaders).text }.getOrNull()
            } ?: continue
            val m3u8 = m3u8Text.trim().replace("\\/", "/").takeIf { it.startsWith("http") } ?: continue
            val isHindi = langTitle.contains("hindi", ignoreCase = true)
            out += RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = m3u8, isM3u8 = true, referer = playUrl,
                qualityHint = 0,
                audioPriority = if (isHindi) 4 else 2,
                audioLabel = langTitle,
            )
        }
        Log.d("Allmovieland", "got ${out.size} language streams from ${langEntries.size} entries " +
            "(series tree: ${leaves.isNotEmpty()})")
        return out
    }

    /**
     * JSON API resolver (api.shows.st / 111Movies shape):
     * `{ "source": { "url": ..., "qualities": [{"quality","url"}] }, "subtitles": [...] }`
     * The signed stream URLs carry no file extension — JSON parsing is mandatory.
     */
    private suspend fun resolveJsonApi(
        spec: ServerSpec,
        apiUrl: String,
        referer: String,
    ): List<RawStream> {
        Log.d("IndStream", "${spec.id}: jsonApi GET $apiUrl")
        val jsonText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(apiUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (jsonText.isNullOrBlank()) {
            Log.w("IndStream", "${spec.id}: no JSON response (timeout/HTTP error) from $apiUrl")
            return emptyList()
        }

        Log.d("IndStream", "${spec.id}: jsonApi response ${jsonText.length}B, preview=${safeSnippet(jsonText)}")

        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("IndStream", "${spec.id}: non-JSON response from $apiUrl (${jsonText.length}B, starts: ${safeSnippet(jsonText)})")
            return emptyList()
        }
        val source = root.optJSONObject("source") ?: run {
            val nullSource = root.isNull("source")
            Log.w("IndStream", "${spec.id}: missing \"source\" (${if (nullSource) "null -- id not known to this server" else "absent"}); root keys=${namesOf(root)}")
            return emptyList()
        }
        val subs = root.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = s.optString("label").ifBlank { null } ?: return@mapNotNull null
                val file = s.optString("file").ifBlank { null } ?: return@mapNotNull null
                label to file
            }
        } ?: emptyList()

        val out = mutableListOf<RawStream>()

        // Adaptive master (source.url). source.manifest carries the FULL HLS master
        // playlist inline (variant URIs are absolute https URLs) ï¿½ the signed url has
        // no file extension, so manifest presence is the HLS signal.
        val masterUrl = source.optString("url").takeIf { it.isNotBlank() }
        val inlineManifest = source.optString("manifest").takeIf { it.isNotBlank() && it.contains("#EXT-X-STREAM-INF") }
        if (masterUrl != null || inlineManifest != null) {
            val url = masterUrl ?: ""
            val isHls = inlineManifest != null || url.contains(".m3u8", ignoreCase = true)
            val pri = if (inlineManifest != null) probeAudioInline(inlineManifest)
                else if (isHls && url.isNotBlank()) probeAudio(url, referer) else 0
            val probed = if (url.isNotBlank()) HttpKit.probeSpeed(url, referer) else null
            val height = inlineManifest?.let { m ->
                ManifestKit.parseMaster(m)?.let { ManifestKit.bestHeight(it.variants) }
            } ?: 0
            out.add(RawStream(
                serverId = spec.id, serverName = spec.name,
                url = url, isM3u8 = isHls, referer = referer,
                qualityHint = height, measuredKbps = probed, subtitles = subs,
                audioPriority = pri, audioLabel = audioLabelFor(pri), inlineManifest = inlineManifest,
            ))
        }

        // Per-quality MP4s (source.qualities[]) ï¿½ direct VIDEO links.
        source.optJSONArray("qualities")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val q = arr.optJSONObject(i) ?: return@mapNotNull null
                val qUrl = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qLabel = q.optString("quality").takeIf { it.isNotBlank() }
                val height = qLabel?.let { Regex("(\\d{3,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                val probed = HttpKit.probeSpeed(qUrl, referer)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = qUrl, isM3u8 = false, referer = referer,
                    qualityHint = height, measuredKbps = probed, subtitles = subs,
                    audioPriority = 0, audioLabel = "",
                )
            }.let { out.addAll(it) }
        }

        if (out.isEmpty()) {
            Log.w("IndStream", "${spec.id}: source present but carried no url/manifest/qualities; source keys=${namesOf(source)}")
        }
        return out
    }

    /** Follow iframes to the deepest player page. Returns the deepest HTML and
     *  its final URL so [resolveOne] can also pass the inner URL to
     *  [loadExtractor] ï¿½ many embed chains (2embed -> vidsrc, superembed -> cloud-hosted
     *  player) only have a CloudStream extractor registered for the INNER host. */
    private suspend fun unwrapPages(html: String, baseUrl: String, timeoutSec: Int): Pair<String, String> {
        var curHtml = html; var curUrl = baseUrl
        repeat(MAX_UNWRAP) {
            val iframe = Jsoup.parse(curHtml).selectFirst("iframe[src]") ?: return curHtml to curUrl
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return curHtml to curUrl
            val resolved = relUrl(curUrl, src)
            if (resolved == curUrl) return curHtml to curUrl
            curUrl = resolved
            val next = withTimeoutOrNull((timeoutSec - 2).coerceAtLeast(3) * 1000L) {
                runCatching { app.get(curUrl, timeout = (timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(curUrl)).text }.getOrNull()
            } ?: return curHtml to curUrl
            if (next.isBlank()) return curHtml to curUrl
            curHtml = next
        }
        return curHtml to curUrl
    }

    /** Harvest bare m3u8/mp4/webm URLs from raw page text. */
    private fun harvestUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/").replace("\\\"", "\"")
        return STREAM_REGEX.flatMap { r -> r.findAll(n).map { it.groupValues[0].trim('"', '\'') }.filter { it.startsWith("http") } }.distinct()
    }

    /** Extract from JS config: file:"..." / sources:[{file:"..."}] / url:"..."
     *  Also catches JWPlayer `setup({file:...})` and the broader set of stream
     *  key names used by VidSrc / MyFlixer / SuperEmbed / NHD players. */
    private fun harvestJsUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/")
        val patterns = listOf(
            Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl|play_url|file_url|source_url|videoUrl|video_url|stream)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?(?:url|src|source)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:source|src)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""\.setup\s*\(\s*\{[^}]*?["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        return patterns.flatMap { p -> p.findAll(n).map { it.groupValues[1].trim() }.filter { it.startsWith("http") } }.distinct()
    }

    /** Pull stream URL from <video src> / <source src>. */
    private fun harvestVideoSource(text: String, baseUrl: String): String? {
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")?.attr("src")?.trim() ?: return null
        return relUrl(baseUrl, src).takeIf { it.startsWith("http") }
    }

    /** Extract subtitle tracks from JWPlayer-style tracks array. */
    private fun grabSubtitles(text: String?): List<Pair<String, String>> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>(); val seen = HashSet<String>()
        Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""").findAll(text).forEach { m ->
            val f = m.groupValues[1].replace("\\/", "/"); val l = m.groupValues[2]
            if (f.isNotBlank() && l.isNotBlank() && seen.add(f)) out.add(l to f)
        }
        return out
    }

    private fun relUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val h = Regex("""^https?://[^/]+""").find(base)?.value ?: return path
        return if (path.startsWith("/")) "$h$path" else "$h/$path"
    }

    private fun okHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        if (!referer.isNullOrBlank()) h["Referer"] = referer
        return h
    }

    /** UA nhdapi's HLS proxy expects at playback (the extraction API echoes it
     *  back; using the browser UA gets 502 from the upstream). */
    private const val NHD_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** JSON key listing for failure logs (shape changes are one log line away).
     *  Keys are server-controlled: strip control chars (log-forgery) and cap length. */
    private fun namesOf(obj: org.json.JSONObject): String =
        obj.names()?.let { n ->
            (0 until n.length()).joinToString(",") { key -> sanitizeToken(n.optString(key)) }
        } ?: "none"

    /** Short, control-char-free prefix of a remote body for parse-failure hints --
     *  long enough to recognise the shape, too short to carry a signed URL. */
    private fun safeSnippet(text: String): String = sanitizeToken(text).take(40)

    private fun sanitizeToken(s: String): String = s.filterNot { it.isISOControl() }.take(64)

    /** Last farm-wide re-probe; cooldown keeps a dead uplink from hammering all
     *  embeds (~30s of network) on every single playback tap. */
    @Volatile
    private var lastFarmProbeAt = 0L
    private const val FARM_REPROBE_COOLDOWN_MS = 15_000L

    /**
     * Per-title cache of resolved streams, populated by the fast-start background
     * pull. Two jobs:
     *   1. Instant replay — a re-tap (or switching back) emits cached links
     *      immediately so playback starts with zero head.
     *   2. Background landing zone — the slower servers that were still resolving
     *      when playback started keep landing here, so the next play already has
     *      the full farm without re-waiting.
     * Short TTL: stream URLs are signed/expiring, so stale links are never served
     * for long.
     */
    object FastStartCache {
        private const val TTL_MS = 5 * 60 * 1000L
        private data class Entry(val streams: List<RawStream>, val expiresAt: Long)
        private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()

        fun key(tmdbId: Int, type: String, season: Int, episode: Int) = "$tmdbId|$type|$season|$episode"

        fun get(key: String): List<RawStream>? {
            val e = map[key] ?: return null
            if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
            return e.streams
        }

        /** Merge [streams] for [key] into the existing set (dedup by url) and extend TTL. */
        fun put(key: String, streams: List<RawStream>) {
            if (streams.isEmpty()) return
            val existing = get(key).orEmpty()
            val merged = (existing + streams).distinctBy { it.url }
            map[key] = Entry(merged, System.currentTimeMillis() + TTL_MS)
        }

        fun clear() = map.clear()
    }

    /** Fill window (legacy): after the first server's link is emitted, keep pulling fast
     *  servers for this long so the player gets a few alternates, then return so
     *  playback starts. The remaining (slower) servers keep resolving in the
     *  background and land in [FastStartCache] for instant replay. */
    const val FAST_START_FILL_MS: Long = 2_500L

    /** Hard cap on how long [IndStreamProvider.loadLinks] waits for the FIRST
     *  batch before returning, regardless of whether the farm has finished.
     *  Safety net so a totally dead farm still surfaces "no link found"
     *  instead of hanging. */
    const val FAST_START_MAX_MS: Long = 45_000L

    /** Trickle window (instant-play model): frame 1 starts the moment the
     *  FIRST server resolves (the fastest host normally answers in well under
     *  a second). After that, the remaining servers keep resolving and their
     *  links are pushed to the player for up to this long — the server list
     *  keeps growing in the background — while capped/staggered so the
     *  running stream keeps the bandwidth. 4s was too short: slow multi-chain
     *  Hindi hosts (MovieBox detail+download+play chain, Allmovieland search →
     *  card → play → playlist chain) resolve in 4-12s+ and only ever landed in
     *  [FastStartCache], never in the CURRENT episode's server list (user
     *  report: "many servers do not come in background … keep pulling all
     *  servers, specially Hindi"). 25s covers every server's timeoutSec
     *  (longest = 20s + the IMDB lookup the IMDB-keyed hosts wait on) while
     *  staggered emission keeps the running stream's bandwidth free. Past
     *  this window arrivals land in [FastStartCache] only (instant replay on
     *  the next tap). */
    const val FAST_START_TRICKLE_MS: Long = 25_000L

    /** Pacing between links pushed during the trickle window: a link every
     *  ~150ms is plenty for the UI and guarantees no burst of manifest fetches
     *  (or player-channel traffic) competes with the first second of playback. */
    const val TRICKLE_EMIT_GAP_MS: Long = 150L

    /** Hold window (user spec Sept 2026, Phase 1 → Phase 2): after the FIRST
     *  server's batch lands, pause the player for this long so a fair live
     *  sample can be probed in parallel — TTFB, resolve time, master bestHeight
     *  — then auto-play the best ≥720 candidate. The user's product range is
     *  1–2s; 1.5s is the sweet spot between "first frame speed" and
     *  "enough samples to rank honestly". Hard-capped by [FAST_START_MAX_MS]. */
    const val FAST_START_SETTLE_MS: Long = 1_500L

    /** Live-probe budget per candidate during the settle window. One round
     *  trip per stream; tight enough that 16 candidates complete inside the
     *  hold even on a slow link. */
    const val PROBE_TTFB_BUDGET_MS: Long = 600L

    /** Master-probe budget (HLS master fetch does triple duty: TTFB + best
     *  variant height + audio renditions — stored into [RawStream.inlineManifest]
     *  so `emit()` skips its own re-fetch). */
    const val PROBE_MASTER_BUDGET_MS: Long = 800L
}





