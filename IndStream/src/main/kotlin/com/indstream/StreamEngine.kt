package com.indstream

/** FILE: StreamEngine. kt - the resolution engine (HOW a TMDB id becomes playable links). - fans out to healthy servers. in parallel and emits EVERY. */

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/** Federated resolution engine. 1. Fans out to healthy embed servers in parallel. 2. */
object StreamEngine {

    // One slot per host: the live farm is =16 servers, so a smaller cap only made fast resolvers QUEUE behind slow.
// multi-chain hosts (MovieBox /.
    // Socket guard only (the farm itself is uncapped); sub-fan-outs add up.
    private const val MAX_CONCURRENT = 32
    private const val MAX_UNWRAP = 4
    // Delay before a failed VidNest sub-server gets its single retry.
    private const val VIDNEST_SUB_RETRY_DELAY_MS = 1_500L

    /** MovieBox bearer token cache (): the x-user token lives for hours; caching it removes one serial round-trip. */
    @Volatile private var movieBoxToken: String? = null
    @Volatile private var movieBoxTokenAt: Long = 0L
    private const val MOVIEBOX_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    // Throttle backoff: a single delayed search retry on HTTP 429 (bearer is never the problem there).
    private const val MOVIEBOX_THROTTLE_RETRY_MS = 2_000L
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
        val audioPriority: Int = 0,
        val audioLabel: String = "",
        val inlineManifest: String? = null, // HLS master playlist text delivered inline (JSON API) Extra HTTP headers the player must send when fetching (e. g.
// "User-Agent: ExoPlayer" for CDNs.
        val extraHeaders: Map<String, String> = emptyMap(),
        // DASH flag; isM3u8 stays the adaptive marker.
        val isDash: Boolean = false,
    )

    /** MovieBox search hit: subject id, season coverage end, audio tag, search detailPath. */
    private data class SubjectRef(
        val id: String,
        val seasonEnd: Int,
        val language: String?,
        val detailPath: String,
    )

    /** A resolver throws this when the host legitimately carries no source for this title/episode: library miss. unsupported media type, no Hindi dub. */
    class CleanMissException(message: String) : Exception(message)

    /** Pick the servers to query for this title. NEUTRAL () � healthy servers in registry order, no Hindi-first placement. no speed-score sorting. */
    private fun selectServers(tmdbId: Int, type: String, season: Int, episode: Int): List<ServerSpec> {
        val healthy = ServerFarm.allServers.filter { HealthMonitor.isHealthy(it.id) }
        Log.d("IndStream", "healthy: ${healthy.size}/${ServerFarm.allServers.size}")
        // A fully-tripped farm used to return emptyList() instantly, so every tap showed "no link found" for a full trip.
// window with zero network traffic.
        val candidates = if (healthy.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastFarmProbeAt < FARM_REPROBE_COOLDOWN_MS) {
                Log.w("IndStream", "all servers tripped; re-probe cooldown active " +
                    "(${(FARM_REPROBE_COOLDOWN_MS - (now - lastFarmProbeAt)) / 1000}s left)")
                emptyList()
            } else {
                lastFarmProbeAt = now
                // Clear only the trip state, keeping latency/throughput history so the health data survives the re-probe.
// (display/debug only � no ranking).
                Log.w("IndStream", "all ${ServerFarm.allServers.size} servers tripped -- clearing trips, re-probing all")
                HealthMonitor.resetTrips()
                ServerFarm.allServers
            }
        } else healthy
        val servers = candidates
        Log.d("IndStream", "selectServers tmdb=$tmdbId type=$type s=$season e=$episode -> ${servers.size} servers (neutral order)")
        return servers
    }

    /** Dual-ID race helper (, ): run several id shapes for one host in parallel; the FIRST non-empty result wins and the. losing attempts are cancelled. */
    private suspend fun raceFirst(vararg blocks: suspend () -> List<RawStream>): List<RawStream> {
        if (blocks.isEmpty()) return emptyList()
        if (blocks.size == 1) return runCatching { blocks[0]() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(emptyList())
        return coroutineScope {
            val winner = CompletableDeferred<List<RawStream>>()
            val pending = java.util.concurrent.atomic.AtomicInteger(blocks.size)
            val jobs = blocks.map { block ->
                launch {
                    val r = runCatching { block() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(emptyList())
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

    /** Resolve every server, invoking with a server's results the instant that server finishes. The whole farm launches in. parallel, so completion order. */
    suspend fun resolveRealtime(
        tmdbId: Int, type: String, season: Int = -1, episode: Int = -1,
        /** Direct IMDB id when the URL already carries one. Lets IMDB-keyed servers run during a TMDB outage. */
        imdbId: String? = null,
        /** Lazily resolves the IMDB id for IMDB-keyed servers. Called INSIDE each server's coroutine so the (up to 3s) TMDB. lookup runs concurrently with the. */
        imdbIdProvider: (suspend () -> String?)? = null,
        /** Second-wave mode: only TMDB-keyed servers run (IMDB-keyed already ran in wave one). */
        tmdbOnly: Boolean = false,
        onBatch: suspend (serverId: String, streams: List<RawStream>) -> Unit,
    ) {
        if (tmdbId <= 0 && (imdbId == null || !imdbId.startsWith("tt")) && imdbIdProvider == null) {
            Log.w("IndStream", "resolveRealtime skipped: no usable id (tmdbId=$tmdbId)")
            return
        }
        // Without a TMDB id only IMDB-keyed servers (plus dual-id racers) can run.
        val imdbOnly = tmdbId <= 0
        val servers = selectServers(tmdbId, type, season, episode).filter { spec ->
            if (tmdbOnly) spec.idType != ServerIdType.IMDB
            else if (!imdbOnly) true
            else spec.idType == ServerIdType.IMDB || spec.id == "vidup" || spec.id == "vidcore"
        }
        if (servers.isEmpty()) return
        val sem = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            servers.map { spec ->
                async {
                    sem.acquire()
                    try {
                        // IMDB id only for the servers that need it � TMDB-keyed servers never wait for (or trigger) the lookup.
                        val serverStart = System.currentTimeMillis()
                        val serverImdb = if (spec.idType == ServerIdType.IMDB) imdbId ?: imdbIdProvider?.invoke() else null
                        if (spec.idType == ServerIdType.IMDB) {
                            Log.i("IndStream", "${spec.id}: imdb=${serverImdb ?: "MISSING"} after " +
                                "${System.currentTimeMillis() - serverStart}ms wait")
                        }
                        // Distinguish a CRASH (resolver throw that is not a clean miss � parse bug / changed upstream shape)). Only timeouts.
// strike the breaker: a shape.
                        var crashed: Throwable? = null
                        val outcome = withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            runCatching { resolveOne(spec, tmdbId, serverImdb, type, season, episode, imdbIdProvider) }
                                .onFailure { t -> if (t !is CleanMissException) crashed = t }
                                .recover { t ->
                                    if (t is CleanMissException) {
                                        Log.i("IndStream", "${spec.id}: clean miss � ${t.message} (no breaker trip)")
                                        emptyList<RawStream>()
                                    } else throw t
                                }.getOrNull()
                        }
                        if (outcome == null) {
                            val ms = System.currentTimeMillis() - serverStart
                            val crash = crashed
                            if (crash != null) {
                                Log.w("IndStream", "${spec.id}: crashed after ${ms}ms: " +
                                    "${crash.javaClass.simpleName}: ${crash.message} (no breaker trip)")
                            } else {
                                Log.w("IndStream", "${spec.id}: TIMEOUT � no result after ${spec.timeoutSec}s, recording failure")
                                HealthMonitor.recordFailure(spec.id)
                            }
                        }
                        // Single-language hosts often declare no labelled audio track; bias their streams' LABEL to the declared language.
// (display only � the). A host.
                        val streams = outcome?.map { s ->
                            if (s.audioLabel.isBlank() && spec.declaredLanguages.size == 1)
                                s.copy(audioLabel = spec.declaredLanguages.first())
                            else s
                        }.orEmpty()
                        if (streams.isNotEmpty()) {
                            Log.i("IndStream", "${spec.id}: LANDED ${streams.size} streams at " +
                                "${System.currentTimeMillis() - serverStart}ms")
                            onBatch(spec.id, streams)
                        }
                    } finally { sem.release() }
                }
            }.awaitAll()
        }
    }

    /** Emit links (, ). Changes. */
    suspend fun emit(
        streams: List<RawStream>,
        onLink: (ExtractorLink) -> Unit,
        /** TMDB original_language ("ja", "hi", . . . ): a stream whose audio label is "Original" carries this language, so. "VidLink (Japanese)" is shown. */
        originalLang: String? = null,
        /** Background-arrival mode (): true = skip the master-manifest re-fetch for labels; the RawStream's own qualityHint is. used as-is. */
        probeManifests: Boolean = true,
    ): Set<String> {
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())
        if (streams.isEmpty()) return emitted

        // Replay liveness sweep (F9, ): background/replay mode feeds FastStartCache links that may have gone stale � aoneroom.
// `?sign=` and vidnest tokens.
        var liveSet = streams
        if (!probeManifests) {
            val dead = withTimeoutOrNull(3000L) {
                coroutineScope {
                    streams.filter { it.url.isNotBlank() && it.inlineManifest.isNullOrBlank() }
                        .map { raw ->
                            async {
                                HttpKit.aliveCheck(raw.url, raw.referer, raw.extraHeaders)
                                    .takeIf { it == false }?.let { raw.url }
                            }
                        }.awaitAll().filterNotNull()
                }
            }.orEmpty()
            if (dead.isNotEmpty()) {
                Log.i("IndStream", "replay liveness: dropped ${dead.size} dead cached url(s)")
                val deadSet = dead.toHashSet()
                liveSet = streams.filter { it.url !in deadSet }
            }
        }

        // NEUTRAL order (): arrival order.
        val ranked = liveSet.filter { it.url.isNotBlank() }

        // Pre-resolve M3U8 masters before dedupe so the numbering key uses the ACTUAL height () rather than qualityHint (which.
// is 0 for adaptive masters).
        data class ResolvedEmit(
            val raw: RawStream,
            val fullHeight: Int,
            val tagLabel: String,
            val fetchMs: Long? = null,
        )
        // Pre-resolve CONCURRENTLY: serial map added up to 4s PER unprobed HLS master (VaPlayer/VidUp/VidCore arrive without.
// height) BEFORE the first link.
        val preResolved = coroutineScope {
            ranked.map { raw ->
                async {
                    when {
                        // Adaptive master (HLS or DASH): re-parse for real height + audio (skip when background/off, or when the height is.
// ALREADY known � probeAudioHeight.
                        raw.isM3u8 && probeManifests -> {
                            val alreadyKnown = raw.qualityHint > 0
                            if (alreadyKnown) {
                                ResolvedEmit(raw, raw.qualityHint, raw.audioLabel)
                            } else {
                                val lh = LinkedHashMap<String, String>()
                                lh.putAll(raw.extraHeaders)
                                if (!lh.containsKey("Referer") && !raw.referer.isNullOrBlank()) lh["Referer"] = raw.referer!!
                                // Fetch latency doubles as the speed signal (fastest-first rank).
                                val t0 = System.currentTimeMillis()
                                val masterText = raw.inlineManifest ?: withTimeoutOrNull(3000L) {
                                    runCatching { app.get(raw.url, timeout = 3, headers = lh).text }.getOrNull()
                                }
                                val fetchMs = if (raw.inlineManifest != null) 0
                                else masterText?.let { System.currentTimeMillis() - t0 }
                                // Dispatch HLS/MPD: bestHeightOf parses the DASH MPD too, so a MovieBox DASH ladder gets its real peak (e. g. 2160).
// not 0. Adaptive guard (): an.
                                val h = (ManifestKit.bestHeightOf(masterText, raw.url).takeIf { it > 0 } ?: raw.qualityHint).coerceAtLeast(0)
                                val master = ManifestKit.parseMaster(masterText, raw.url)
                                // Audio label: explicit server label wins; else the REAL track the player auto-selects (multi-audio? "Multi", single.
// track? its language); else a.
                                val tag = (if (raw.audioLabel.isNotBlank()) raw.audioLabel
                                else if (master != null && master.isMultiAudio) "Multi"
                                else if (master != null) ManifestKit.audioLanguageLabel(master).orEmpty()
                                else raw.audioLabel)
                                    .ifBlank { declaredLanguageHint(raw) ?: "" }
                                ResolvedEmit(raw, h, tag, fetchMs)
                            }
                        }
                        // Direct file: moov height is width-normalized; keep server hint.
                        !raw.isM3u8 && probeManifests -> {
                            val t0 = System.currentTimeMillis()
                            val measured = HttpKit.resolveHeight(raw.url, raw.referer, raw.extraHeaders)
                            // Only a parsed moov proves a fast live file; declared-only stays unranked.
                            val fetchMs = (System.currentTimeMillis() - t0).takeIf { measured > 0 }
                            val h = maxOf(measured, raw.qualityHint).takeIf { it > 0 }
                                ?: run {
                                val fromUrl = ManifestKit.resolutionFromUrl(raw.url)
                                if (fromUrl > 0) fromUrl else -1   // 1 = Auto (unknown direct file).
                            }
                            val tag = raw.audioLabel.ifBlank { declaredLanguageHint(raw) ?: "" }
                            ResolvedEmit(raw, h, tag, fetchMs)
                        }
                        // Background/off path: trust the RawStream's own qualityHint (no new probes). For direct files (non-adaptive) with no.
// known height, use -1 so they.
                        else -> {
                            var fullHeight = if (!raw.isM3u8 && raw.qualityHint <= 0) -1 else raw.qualityHint
                            // Adaptive guard (): an HLS master can NEVER carry the -1 "Auto" direct-file sentinel � clamp negatives to 0.
// (unknown-adaptive), and if the server.
                            if (raw.isM3u8 && fullHeight < 0) fullHeight = 0
                            if (raw.isM3u8 && fullHeight <= 0 && !raw.inlineManifest.isNullOrBlank()) {
                                val h = ManifestKit.bestHeightOf(raw.inlineManifest, raw.url).takeIf { it > 0 } ?: 0
                                if (h > 0) fullHeight = h
                            }
                            val tag = raw.audioLabel.ifBlank { declaredLanguageHint(raw) ?: "" }
                            ResolvedEmit(raw, fullHeight, tag)
                        }
                    }
                }
            }.awaitAll()
        }
        // Fastest-first: lowest effective fetch latency wins (Hindi gets a small
        // bonus, not a free pass). Stable: unmeasured ties keep arrival order.
        val ordered = preResolved.sortedBy { speedRankMs(it.fetchMs, it.tagLabel) }
        val resolvedForKey = ordered.map { it.raw.copy(qualityHint = it.fullHeight) }
        val numbers = LinkNaming.dedupeNames(resolvedForKey, originalLang)

        // 720p-floor observability (): the floor is, so a MovieBox-only-480p title would surface as "server absent" with zero.
// logs. Count drops vs survives.
        val floorDroppedByServer = HashMap<String, Int>()
        val emittedByServer = HashMap<String, Int>()

        ordered.forEachIndexed { index, r ->
            val raw = r.raw
            val dupIdx = numbers.getOrElse(index) { 0 }
            if (!emitted.add(raw.url)) return@forEachIndexed

            // Quality floor (): fixed (non-HLS) links with a KNOWN height below 720p never surface. Adaptive masters always pass �.
// their height reading is the.
            if (!passesQualityFloor(raw.isM3u8, r.fullHeight)) {
                floorDroppedByServer.merge(raw.serverId, 1, Int::plus)
                return@forEachIndexed
            }
            emittedByServer.merge(raw.serverId, 1, Int::plus)

            val linkHeaders = LinkedHashMap<String, String>()
            linkHeaders.putAll(raw.extraHeaders)
            if (!linkHeaders.containsKey("Referer") && !raw.referer.isNullOrBlank()) {
                linkHeaders["Referer"] = raw.referer!!
            }

            val isAdaptive = raw.isM3u8
            // ExtractorLink. quality = the REAL resolved height () so the user's quality-profile ranks every stream by its height.
// AND CloudStream's own badge is.
            val quality = r.fullHeight.coerceAtLeast(0)
            val label = LinkNaming.displayName(
                serverName = raw.serverName,
                audioLabel = r.tagLabel,
                qualityHint = r.fullHeight,
                duplicateIndex = dupIdx,
                originalLang = originalLang,
            )

            // DASH manifests need DASH type; HLS stays M3U8.
            val linkType = if (raw.isDash) ExtractorLinkType.DASH
            else if (isAdaptive) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
            onLink(newExtractorLink(
                source = label, name = label,
                url = raw.url, type = linkType,
            ) {
                referer = raw.referer ?: ""
                this.quality = quality
                this.headers = linkHeaders
            })
        }
        floorDroppedByServer.forEach { (sid, n) ->
            if ((emittedByServer[sid] ?: 0) == 0) {
                Log.i("IndStream", "$sid streams dropped by 720p floor ($n candidates, none =720p)")
            }
        }
        return emitted
    }

    /** Quality floor (): a FIXED (non-HLS) stream whose resolved height is a KNOWN value below 720p is dropped. Adaptive. HLS masters always pass (they. */
    /** Effective fetch latency for fastest-first rank; null sorts last. Pure. */
    internal fun speedRankMs(fetchMs: Long?, tagLabel: String): Double {
        if (fetchMs == null) return Double.MAX_VALUE
        val boost = when (LinkNaming.languageTag(tagLabel)) {
            "Hindi" -> 1.1
            "Multi" -> 1.05
            in ManifestKit.INDIAN_DUB_LANGUAGES.map { it.canonical } -> 1.05
            else -> 1.0
        }
        return fetchMs / boost
    }

    fun passesQualityFloor(isAdaptive: Boolean, height: Int): Boolean {
        if (isAdaptive) return true
        if (height <= 0) return true
        return height >= 720
    }

    // Internals � multi-strategy pipeline (proven).

    /** Single-server resolve (internal for live-chain tests). */
    internal suspend fun resolveOne(
        spec: ServerSpec,
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
        /** Lazy IMDB lookup for the dual-ID race (vidup/vidcore accept both id shapes). Null = no provider (prewarm path passes. a direct id). */
        imdbIdProvider: (suspend () -> String?)? = null,
    ): List<RawStream> {
        val start = System.currentTimeMillis()
        // RC-H (): a MISSING IMDB id is a TMDB-lookup problem (cold cache + 3s cap, or the transient 429s the last commit.
// fought), NOT this host failing.
        val id = if (spec.idType == ServerIdType.IMDB)
            (imdbId ?: throw CleanMissException("${spec.id}: IMDB id unavailable (TMDB lookup miss) � host stays in farm"))
        else tmdbId.toString()
        val embedUrl = if (type == "movie") ServerFarm.buildMovieUrl(spec, id)
        else ServerFarm.buildTvUrl(spec, id, season, episode)
        // Per-server referer (some APIs 403 without it, e. g. api. shows. st).
        val referer = spec.referer ?: embedUrl.substringBefore("?")

        // VidLink: encrypted-token API. Token embeds the TMDB id + a +480s timestamp (VidlinkSource); the response carries.
// stream. playlist (an adaptive.
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
            // Soft-fail: an empty result after a REAL
            // attempt (API answered, no playable/region streams) is a miss for
            // this title, not a host failure � no breaker trip. Genuine network
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
        if (spec.id == "videasy") {
            val result = resolveVideasy(spec, tmdbId, imdbId, type, season, episode, imdbIdProvider)
            if (result.isNotEmpty()) { okServer(spec, start, "videasy fan-out", result.size); return result }
            failServer(spec, "videasy returned no streams")
            return emptyList()
        }
        if (spec.id == "onetouchtv") {
            val result = resolveOneTouchTv(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "onetouchtv api", result.size); return result }
            failServer(spec, "onetouchtv returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "vidnest") {
            val (result, answered) = resolveVidnest(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidnest fan-out", result.size); return result }
            if (answered == 0) {
                failServer(spec, "vidnest: no sub-server answered (host down)")
            } else {
                // Soft-fail (, MovieBox): sub-servers answered but none carry this title � a title-level miss, not a host failure.
// VidNest flaps 502s per.
                failServer(spec, "vidnest returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            }
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
            // Dual-ID race (): both hosts accept TMDB AND IMDB ids in the URL path. The TMDB arm starts at t=0; the IMDB arm joins.
// the race as soon as the id.
            val arms = mutableListOf<suspend () -> List<RawStream>>()
            if (tmdbId > 0) arms.add { resolveEncDecPlayer(spec, tmdbId.toString(), type, season, episode, variant) }
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
            val result = resolveAllmovieland(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "allmovieland multi-lang", result.size); return result }
            // Soft-fail (): multi-step chain finishing with no language streams is a title-level miss � network failures inside.
// the resolver already log/return.
            failServer(spec, "allmovieland returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "netmirror") {
            val result = resolveNetmirror(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "netmirror api", result.size); return result }
            failServer(spec, "netmirror returned no streams")
            return emptyList()
        }
        if (spec.id == "vixsrc") {
            val result = resolveVixsrc(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vixsrc signed hls", result.size); return result }
            failServer(spec, "vixsrc returned no streams")
            return emptyList()
        }
        if (spec.id == "castletv") {
            val result = resolveCastleTv(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "castletv api", result.size); return result }
            failServer(spec, "castletv returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "streamflix") {
            val result = resolveStreamFlix(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "streamflix direct", result.size); return result }
            failServer(spec, "streamflix returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "4khdhub") {
            val result = resolveHub4k(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "hub file links", result.size); return result }
            failServer(spec, "hub returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "vidcore-api") {
            val result = resolveVidcore(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidcore api", result.size); return result }
            // Soft-fail (, moviebox): the API answering with no/empty sources is a title-level miss, not a host failure.
            failServer(spec, "vidcore api returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        // 0. JSON API branch (api. shows. st style): parse JSON, take source. url + source. qualities + subtitles.
        if (spec.isJsonApi) {
            val result = resolveJsonApi(spec, embedUrl, referer)
            if (result.isNotEmpty()) { okServer(spec, start, "json api", result.size); return result }
            failServer(spec, "json api returned no source")
            return emptyList()
        }

        // 1. Fetch embed page.
        val rawText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(embedUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (rawText.isNullOrBlank()) { failServer(spec, "embed fetch blank/timeout: $embedUrl"); return emptyList() }
        Log.d("IndStream", "${spec.id}: embed fetched, ${rawText.length}B")

        // 2. Unwrap iframes.
        val (unwrapped, unwrappedUrl) = unwrapPages(rawText, embedUrl, spec.timeoutSec)
        if (unwrapped !== rawText) Log.d("IndStream", "${spec.id}: unwrapped to $unwrappedUrl, ${unwrapped.length}B")

        // 3. Direct stream URL regex harvest.
        val direct = harvestUrls(unwrapped)
        if (direct.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: direct harvest found ${direct.size} urls")
            val result = direct.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val (label, h) = probeAudioHeight(url, referer)
                val dash = url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true) || dash, referer, h, probed,
                    audioLabel = label, isDash = dash)
            }
            okServer(spec, start, "direct harvest", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: no direct urls in page")
        }

        // 4. CloudStream extractor registry (VidSrc, 2embed, embed. su, MyFlixer, etc. ).
        val regLinks = mutableListOf<ExtractorLink>()
        val regOk = runCatching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        // Also try the deepest unwrapped URL � most embed chains register a CloudStream extractor on the INNER host (the.
// actual player), not the outer.
        if (unwrappedUrl != embedUrl) {
            runCatching {
                loadExtractor(url = unwrappedUrl, referer = embedUrl, subtitleCallback = { }, callback = { regLinks.add(it) })
            }
        }
        if (regLinks.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: extractor registry returned ${regLinks.size} links (outerOk=$regOk unwrapped=$unwrappedUrl)")
            val result = regLinks.map { link ->
                val probed = HttpKit.probeSpeed(link.url, link.referer)
                val (label, h) = probeAudioHeight(link.url, link.referer)
                val dash = link.type == ExtractorLinkType.DASH || link.url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, link.url, link.type == ExtractorLinkType.M3U8 || dash, link.referer,
                    ManifestKit.maxQuality(link.quality, h), probed,
                    audioLabel = label, isDash = dash)
            }
            okServer(spec, start, "extractor registry", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: extractor registry returned no links (outerOk=$regOk unwrapped=$unwrappedUrl)")
        }

        // 5. JS config: file: ". . .
        val jsUrls = harvestJsUrls(unwrapped)
        if (jsUrls.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: js harvest found ${jsUrls.size} urls")
            val result = jsUrls.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val (label, h) = probeAudioHeight(url, referer)
                val dash = url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true) || dash, referer, h, probed,
                    audioLabel = label, isDash = dash)
            }
            okServer(spec, start, "js config harvest", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: no js harvest")
        }

        // 6. <video src> / <source src> HTML elements.
        val videoSrc = harvestVideoSource(unwrapped, embedUrl)
        if (videoSrc != null) {
            Log.d("IndStream", "${spec.id}: video tag found: $videoSrc")
            val probed = HttpKit.probeSpeed(videoSrc, referer)
            val (label, h) = probeAudioHeight(videoSrc, referer)
            okServer(spec, start, "video tag", 1)
            val dash = videoSrc.contains(".mpd", ignoreCase = true)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true) || dash, referer, h, probed,
                audioLabel = label, isDash = dash))
        } else {
            Log.d("IndStream", "${spec.id}: no video tag")
        }

        // (No subtitle-only fallback carrier: server captions are not emitted � the SubtilesProvider provider is the only.
// subtitle source, . A page with no.
        failServer(spec, "no harvestable stream across full pipeline")
        return emptyList()
    }

    /** Log + trip a server. Single choke point so every failure names its reason. */
    private fun failServer(spec: ServerSpec, reason: String, isCleanMiss: Boolean = false) {
        if (isCleanMiss) {
            Log.i("IndStream", "${spec.id}: clean miss � $reason (no breaker trip)")
        } else {
            Log.w("IndStream", "${spec.id}: $reason")
            HealthMonitor.recordFailure(spec.id)
        }
    }

    /** Log + record a successful resolution for a server. */
    private fun okServer(spec: ServerSpec, start: Long, stage: String, streamCount: Int) {
        val ms = System.currentTimeMillis() - start
        Log.d("IndStream", "${spec.id}: OK via $stage, $streamCount streams in ${ms}ms")
        HealthMonitor.recordSuccess(spec.id)
    }

    /** DECLARED-language hint (, NEVER guess): a language counts only when the host itself declares it � a server brand. that names it ("MyFlixer Hindi". */
    private fun declaredLanguageHint(raw: RawStream): String? =
        ManifestKit.languageFromName(raw.serverName, raw.url)

    /** Probe an adaptive manifest (HLS master or DASH MPD) for the audio language label AND the peak video height in ONE. fetch � the master text serves. */
    private suspend fun probeAudioHeight(url: String, referer: String?): Pair<String, Int> {
        val isAdaptive = url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)
        if (!isAdaptive) return "" to 0
        val text = withTimeoutOrNull(3000L) {
            runCatching { app.get(url, timeout = 3, headers = mapOf("Referer" to (referer ?: ""))).text }.getOrNull()
        } ?: return "" to 0
        val height = ManifestKit.bestHeightOf(text, url)
        val master = ManifestKit.parseMaster(text, url)
        val label = master?.let { ManifestKit.audioLanguageLabel(it) }.orEmpty()
        // Debug: what audio renditions + heights did the probe see?
        val langs = master?.audio?.map { r -> "lang=${r.language ?: "none"} name=${r.name}" }.orEmpty()
        Log.d("IndStream", "probeAudioHeight url=${url.take(80)} label=$label height=$height renditions=$langs")
        return label to height
    }

    /** Probe an inline manifest (no network) for audio label + peak height. */
    private fun probeAudioInlineHeight(manifestText: String?): Pair<String, Int> {
        if (manifestText.isNullOrBlank()) return "" to 0
        val master = ManifestKit.parseMaster(manifestText) ?: return "" to 0
        return ManifestKit.audioLanguageLabel(master).orEmpty() to ManifestKit.bestHeight(master.variants)
    }

    /** Headers for vidlink. pro API + playlist requests (site Referer/Origin required). */
    private fun vidlinkHeaders(mediaPageUrl: String): Map<String, String> = mapOf(
        "User-Agent" to HttpKit.userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://vidlink.pro",
        "Referer" to mediaPageUrl,
    )

    /** VidLink resolver: token API -> stream. playlist (multi-audio HLS master) -> one inline-manifest RawStream. The. master is fetched once here so the. */
    private suspend fun resolveVidlink(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val mediaPage = if (type == "movie") "https://vidlink.pro/movie/$tmdbId"
        else "https://vidlink.pro/tv/$tmdbId/$season/$episode"
        Log.d("VidLink", "mediaPage=$mediaPage")

        // 2004 ("Invalid token") also fires on skewed device clocks: the token
        // embeds unix+480s, so walk the offset ladder before giving up.
        var root: org.json.JSONObject? = null
        for ((attempt, offset) in VidlinkSource.TOKEN_OFFSETS.withIndex()) {
            val apiUrl = if (type == "movie") VidlinkSource.movieApiUrlAt(tmdbId.toString(), offset)
            else VidlinkSource.tvApiUrlAt(tmdbId.toString(), season, episode, offset)
            val jsonText = withTimeoutOrNull(6_000L) {
                runCatching {
                    app.get(apiUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
                }.getOrNull()
            }
            if (jsonText.isNullOrBlank()) {
                Log.w("VidLink", "no API response (timeout/HTTP error) for $mediaPage")
                return emptyList()
            }
            // Literal `null` body: the site knows the title but has no multiLang source (library miss).
            if (jsonText.trim() == "null") {
                throw CleanMissException("vidlink: api returned null body (no multiLang source) for $mediaPage")
            }
            Log.d("VidLink", "attempt ${attempt + 1} offset +${offset}s: ${jsonText.length}B preview=${safeSnippet(jsonText)}")
            val parsed = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
                Log.w("VidLink", "non-JSON response for $mediaPage (${jsonText.length}B, starts: ${safeSnippet(jsonText)})")
                return emptyList()
            }
            if (!parsed.has("error") && !parsed.has("code")) {
                root = parsed
                break
            }
            val err = parsed.optJSONObject("error") ?: parsed
            val code = err.optInt("code", -1)
            val msg = err.optString("message").ifBlank { err.optString("error") }.ifBlank { "unknown" }
            if (code == 2004 && attempt < VidlinkSource.TOKEN_OFFSETS.lastIndex) {
                Log.w("VidLink", "2004 at offset +${offset}s — retrying with next offset")
                continue
            }
            // VidLink API error response: {"error": "Invalid token", "code": 2004} code 2004 here is the API's token error — NOT
            // ExoPlayer's. The token is generated client-side using XSalsa20-Poly1305 secretbox;
            // when the server rotates its key, ALL tokens are rejected with 2004.
            if (code == 2004) {
                Log.e("VidLink", "CRITICAL: code 2004 — encryption key rotated! Update VidlinkSource.KEY_HEX immediately. " +
                    "Extract the new key from https://vidlink.pro player JS bundles (look for 64-char hex near secretbox/sodium). " +
                    "Current key: ${VidlinkSource.KEY_HEX.take(8)}...${VidlinkSource.KEY_HEX.takeLast(8)}")
            } else {
                Log.w("VidLink", "API error code=$code msg=$msg")
            }
            return emptyList()
        }
        val resolved = root ?: return emptyList()

        val stream = resolved.optJSONObject("stream") ?: run {
            Log.w("VidLink", "no \"stream\" object; root keys=${namesOf(resolved)}")
            return emptyList()
        }

        // (Server captions are NOT collected � the SubtilesProvider provider is the only subtitle source, . ) New shape (.
// sourceId mwVault/mbVault).
        val qualities = stream.optJSONObject("qualities")
        if (qualities != null && qualities.length() > 0) {
            val entries = qualities.names()?.let { n ->
                (0 until n.length()).mapNotNull { i ->
                    val key = n.optString(i)
                    val height = key.toIntOrNull() ?: 0
                    // Quality floor note (): the emit() quality floor now drops KNOWN sub-720 progressive entries (360/480 mp4) at.
// emission; adaptive masters are always.
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
                        extraHeaders = VidlinkSource.qualityPlaybackHeaders(q),
                    )
                }
            }
            Log.w("VidLink", "qualities present but no usable urls (keys=${namesOf(qualities)})")
        }

        // Legacy shape: stream. playlist (HLS master) � kept for when VidLink serves an adaptive playlist again.
        val masterUrl = stream.optString("playlist").takeIf { it.isNotBlank() }
            ?: resolved.optString("url").takeIf { it.isNotBlank() }
            ?: run {
                Log.w("VidLink", "no qualities/playlist/url; stream keys=${namesOf(stream)} root keys=${namesOf(resolved)}")
                return emptyList()
            }

        // Fetch the master playlist once: audio label + best height inline.
        val masterText = withTimeoutOrNull(6_000L) {
            runCatching {
                app.get(masterUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
            }.getOrNull()
        }
        val master = ManifestKit.parseMaster(masterText, masterUrl)
        val height = master?.let { ManifestKit.bestHeight(it.variants) } ?: 0
        val label = master?.let { ManifestKit.audioLanguageLabel(it) }.orEmpty()

        return listOf(
            RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = masterUrl,
                isM3u8 = master != null || masterUrl.contains(".m3u8", ignoreCase = true),
                referer = null,
                qualityHint = height,
                audioLabel = label,
                inlineManifest = masterText?.takeIf { master != null },
                extraHeaders = VidlinkSource.PLAYER_HEADERS,
            )
        )
    }


    /** MyFlixer Hindi resolver (hindi. myflixerapi. com). state: the HTML embed pages sit behind an anti-bot wall and the. old /ajax/get_stream_link. */
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

        // 1. Status API hit/miss (no captcha) ----------------------------- Movies: one call.
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
                "failed" -> { /* try next fallback (series) or clean miss. */ }
                else -> failServer(spec, "status api unexpected body: ${statusText.take(120)}")
            }
        }
        if (!inLibrary) {
            Log.d("MyFlixerHindi", "$id not in library (clean miss, no breaker trip)")
            return emptyList()
        }
        Log.d("MyFlixerHindi", "$id in library � attempting embed chain")

        // 2. Embed page, both domains ------------------------------------ The hindi. subdomain is robot-walled for plain.
// clients () but the MAIN.
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
                // Genuine library miss page (checked BEFORE the wall � the miss page also carries the robot banner in its footer).
                if (text.contains("movie-not-found") || text.contains("Movie or Episode Not Found", ignoreCase = true)) {
                    Log.d("MyFlixerHindi", "$id not in library (embed page miss)")
                    return emptyList()
                }
                if (text.contains("not a robot", ignoreCase = true)) {
                    Log.d("MyFlixerHindi", "$domain walled � trying next domain/path")
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
        // Server list: <a class="server" data-id="8zj">Server-Videasy</a> �
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

        // -- 3. AJAX chain on the domain that rendered the embed page ------
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


    /** Fetch (and cache) the MovieBox bearer token. The token lives for hours (MOVIEBOX_TOKEN_TTL_MS). */
    private suspend fun fetchMovieBoxBearer(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            movieBoxToken?.takeIf {
                System.currentTimeMillis() - movieBoxTokenAt < MOVIEBOX_TOKEN_TTL_MS
            }?.let { return it }
        }
        val base = "https://h5-api.aoneroom.com"
        // INTERNAL BUDGETS: the upstream sends NO per-request timeout, so every
        // call below carries an explicit cap.
        val xUser = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get("$base/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
                    timeout = 8)
            }.getOrNull()
        }?.headers?.get("x-user") ?: run { Log.w("MovieBox", "no x-user header"); return null }
        val t = runCatching { org.json.JSONObject(xUser).optString("token", "") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("MovieBox", "no token in x-user"); return null }
        movieBoxToken = t
        movieBoxTokenAt = System.currentTimeMillis()
        return t
    }

    /** Public pre-warm, called once at plugin load: the token lives for hours, so one background GET at app start removes a. serial round-trip (~0. 5-1s). */
    suspend fun prewarmMovieBoxToken(): String? = fetchMovieBoxBearer(forceRefresh = false)

    /** Search rejection class: 429 is transient throttling (no token burn); 401/403 or a non-zero body code means the bearer was rejected. Pure. */
    internal fun movieboxAuthRejected(httpCode: Int, jsonCode: String): Boolean =
        httpCode != 429 && (httpCode == 401 || httpCode == 403 || jsonCode != "0")

    /** MovieBox resolver (title-keyed app API, 4-step chain). */
    internal fun movieboxSeasonEnd(rawTitle: String): Int? {
        val m = Regex("""\s+S(\d+)(?:\s*-\s*S?(\d+))?$""", RegexOption.IGNORE_CASE)
            .find(rawTitle) ?: return null
        return m.groupValues[2].toIntOrNull() ?: m.groupValues[1].toIntOrNull()
    }

    // Single-flight for play calls; staggered to respect per-IP budget.
    private val movieBoxPlayGate = Semaphore(1)
    @Volatile private var movieBoxPlayLastAt = 0L
    private const val MOVIEBOX_PLAY_STAGGER_MS = 1_500L

    // Media referer: the file CDN gates on it (allowed mirror = 206, else 429).
    internal const val MOVIEBOX_REFERER = "https://movieboxonline.net/"

    // Normalize signCookie into a Cookie header value.
    internal fun cloudFrontCookie(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val parts = signCookie.split(";").map { it.trim().trim('"') }
            .filter { it.contains("=") && it.substringAfter("=").isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.joinToString("; ")
    }

    // Drop deprecation-notice clips served from the notice path.
    internal fun isMovieboxNoticeUrl(url: String): Boolean =
        url.contains("macdn.aoneroom.com/other/", ignoreCase = true)

    // Standard base64 decode without java.util (minSdk 21).
    internal fun decodeBase64Standard(input: String): ByteArray? = runCatching {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val rev = IntArray(128) { -1 }
        alpha.forEachIndexed { i, c -> rev[c.code] = i }
        val s = input.filterNot { it.isWhitespace() }.trimEnd('=')
        require(s.isNotEmpty() && s.length % 4 != 1) { "bad b64" }
        require(s.all { it.code < 128 && rev[it.code] >= 0 }) { "bad b64" }
        val out = ByteArray(s.length * 3 / 4 + 3)
        var w = 0
        var i = 0
        while (i < s.length) {
            val c0 = rev[s[i].code]
            val c1 = if (i + 1 < s.length) rev[s[i + 1].code] else 0
            val c2 = if (i + 2 < s.length) rev[s[i + 2].code] else 0
            val c3 = if (i + 3 < s.length) rev[s[i + 3].code] else 0
            out[w++] = ((c0 shl 2) or (c1 shr 4)).toByte()
            if (i + 2 < s.length) out[w++] = (((c1 and 0xF) shl 4) or (c2 shr 2)).toByte()
            if (i + 3 < s.length) out[w++] = (((c2 and 0x3) shl 6) or c3).toByte()
            i += 4
        }
        out.copyOf(w)
    }.getOrNull()

    // Rebuild DASH manifest URL from the signed policy Resource.
    internal fun dashManifestFromPolicy(signCookie: String?): String? = runCatching {
        if (signCookie.isNullOrBlank()) return@runCatching null
        val raw = signCookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("CloudFront-Policy=") }
            ?.substringAfter("=")?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        // Reverse URL-safe variant.
        var std = raw.replace('-', '+').replace('~', '/').replace('_', '=')
        std = std.filterNot { it.isWhitespace() }
        if (std.isEmpty()) return@runCatching null
        val stripped = std.trimEnd('=')
        // Padding only valid at end; length mod 4 == 1 never valid.
        if (stripped.isEmpty() || stripped.contains('=')) return@runCatching null
        if (stripped.length % 4 == 1) return@runCatching null
        val pad = (4 - stripped.length % 4) % 4
        std = stripped + "=".repeat(pad)
        val json = decodeBase64Standard(std)?.let { String(it, Charsets.UTF_8) }
            ?: return@runCatching null
        val res = org.json.JSONObject(json)
            .optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
            ?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val base = res.trim().trimEnd('*').trimEnd('/')
        if (base.isBlank() || !base.startsWith("http")) return@runCatching null
        if (base.endsWith(".mpd", ignoreCase = true)) base else "$base/index.mpd"
    }.getOrNull()

    // Serialize play calls with stagger; cancellation-safe via finally.
    internal suspend fun <T> withMovieBoxPlayPermit(block: suspend () -> T): T {
        movieBoxPlayGate.acquire()
        try {
            val wait = MOVIEBOX_PLAY_STAGGER_MS - (System.currentTimeMillis() - movieBoxPlayLastAt)
            if (wait > 0) delay(wait)
            try {
                return block()
            } finally {
                movieBoxPlayLastAt = System.currentTimeMillis()
            }
        } finally {
            movieBoxPlayGate.release()
        }
    }

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
            // Title-keyed API without a title = nothing to search; the host is fine, so a clean miss (no breaker trip) keeps.
// MovieBox visible.
            throw CleanMissException("no title for tmdb=$tmdbId (title-keyed API)")
        }
        val seasonKey = if (season > 0) season else 1
        val episodeKey = if (episode > 0) episode else 1

        val host = "h5-api.aoneroom.com"
        val base = "https://$host"

        // 1+2. Bearer token + title search: search is ONE uncapped request.
        suspend fun movieBoxBearer(forceRefresh: Boolean): String? =
            fetchMovieBoxBearer(forceRefresh)

        val subjectType = if (type == "movie") 1 else 2
        fun unwrapData(json: org.json.JSONObject): org.json.JSONObject {
            val d = json.optJSONObject("data") ?: return json
            return d.optJSONObject("data") ?: d
        }
        var baseHeaders: Map<String, String> = emptyMap()
        var searchItems: org.json.JSONArray? = null
        var throttleRetried = false
        val mbStart = System.currentTimeMillis()
        for (attempt in 0 until 2) {
            val budgetSec = if (attempt == 0) 15L else 12L
            // Budget guard: auth-retry only pays off on a FAST rejection.
            if (attempt == 1 && System.currentTimeMillis() - mbStart > 30_000) {
                Log.w("MovieBox", "skipping auth-retry (>30s already spent) \u2014 keeps the chain inside the 65s kill")
                break
            }
            val token = movieBoxBearer(forceRefresh = attempt > 0) ?: return emptyList()
            // 's EXACT baseHeaders (invokeMoviebox): Asia/Kolkata and the okHeaders() Chrome UA were inventions; aoneroom is known.
// to answer differently per.
            baseHeaders = mapOf(
                "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
                "Accept-Language" to "en-US,en;q=0.5",
                "Accept" to "application/json",
                "Referer" to base,
                "Host" to host,
                "Connection" to "keep-alive",
                "Authorization" to "Bearer $token",
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            )
            val resp = withTimeoutOrNull(budgetSec * 1000L) {
                runCatching {
                    app.post("$base/wefeed-h5api-bff/subject/search", timeout = budgetSec, headers = baseHeaders,
                        json = mapOf(
                            "keyword" to title, "page" to 1, "perPage" to 24,
                            "subjectType" to subjectType,
                        ))
                }.getOrNull()
            }
            if (resp == null) {
                // Timeout / connection failure: a second wait is pointless � the token was never rejected (survives this the same way.
// only because it never cuts the.
                Log.w("MovieBox", "search no answer in ${budgetSec}s (attempt ${attempt + 1})")
                break
            }
            val root = runCatching { org.json.JSONObject(resp.text) }.getOrNull()
            val found = root?.let { unwrapData(it).optJSONArray("items") }
            if (found != null && found.length() > 0) {
                searchItems = found
                break
            }
            val jsonCode = root?.optString("code", "")?.takeIf { it.isNotBlank() } ?: "0"
            if (resp.code == 429) {
                // Throttled but never rejected: one delayed retry with the same bearer before giving up.
                if (!throttleRetried) {
                    throttleRetried = true
                    Log.w("MovieBox", "search throttled (HTTP 429) — retrying once after delay")
                    delay(MOVIEBOX_THROTTLE_RETRY_MS)
                    val retryItems = withTimeoutOrNull(10_000L) {
                        runCatching {
                            app.post("$base/wefeed-h5api-bff/subject/search", timeout = 10, headers = baseHeaders,
                                json = mapOf(
                                    "keyword" to title, "page" to 1, "perPage" to 24,
                                    "subjectType" to subjectType,
                                ))
                        }.getOrNull()
                    }?.let { r -> runCatching { org.json.JSONObject(r.text) }.getOrNull() }
                        ?.let { unwrapData(it).optJSONArray("items") }
                    if (retryItems != null && retryItems.length() > 0) {
                        searchItems = retryItems
                        break
                    }
                    Log.w("MovieBox", "throttle retry answered with no items")
                } else {
                    Log.w("MovieBox", "search throttled (HTTP 429)")
                }
                break
            }
            val authRejected = movieboxAuthRejected(resp.code, jsonCode)
            if (!authRejected) {
                // Answered, accepted, just no rows for this title � a real miss, not a token problem; retrying would only burn 12s.
                Log.w("MovieBox", "search answered with no items")
                break
            }
            if (attempt > 0) break
            Log.w("MovieBox", "search rejected (HTTP ${resp.code}, code=$jsonCode)" +
                " � retrying once with a fresh bearer token")
            movieBoxToken = null
        }
        val items = searchItems ?: run { Log.w("MovieBox", "no search items"); return emptyList() }

        // "Title " / "Title (Hindi Dubbed)"? audio; "Title S1-S3" trailing suffix is season coverage, stripped before.
// matching. The MovieBox title often.
        val seasonSuffix = Regex("""\s+S\d+(?:\s*-\s*S?\d+)?$""", RegexOption.IGNORE_CASE)
        val bracketGroups = Regex("""[\[(]([^\])]+)[\])]""", RegexOption.IGNORE_CASE)
        val norm: (String) -> String = { t -> t.lowercase().replace(Regex("""[^a-z0-9]"""), "") }
        val titleNorm = norm(title)
        val subjects = mutableListOf<SubjectRef>() // id, seasonEnd, language, search detailPath.
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title", "")
            // " S1-S4" / " S3" coverage suffix -> LAST number (season end). F4 (wrong-episode): this used to be `value. filter.
// {isDigit() }. toInt()` � "S1-S4".
            val seasonEnd = movieboxSeasonEnd(rawTitle)
            // Pull the audio tag, not a bare year) � "Title ", "Title (2024)".
            val audioTag = bracketGroups.findAll(rawTitle)
                .map { it.groupValues[1] }
                .firstOrNull { it.any { c -> c.isLetter() } && !it.any { c -> c.isDigit() } }
            val clean = rawTitle
                .let { seasonSuffix.replace(it, "") }
                .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "") // drop all bracket groups.
                .replace(Regex("""\s*\d{4}"""), "")               // drop a stray year.
                .trim()
            val cleanNorm = norm(clean)
            val matched = cleanNorm == titleNorm ||
                (titleNorm.length >= 4 && cleanNorm.startsWith(titleNorm))
            if (!matched) continue
            // 0 = no explicit "S1-S3" coverage marker: the subject is presumed to cover every season (the play/download APIs take.
            // se/ep directly). Treating a.
            subjects += SubjectRef(id, seasonEnd ?: 0, audioTag, item.optString("detailPath", ""))
        }
        if (subjects.isEmpty()) {
            throw CleanMissException("no exact title match for '$title' in ${items.length()} search rows")
        }
        Log.d("MovieBox", "subjects=${subjects.map { it.id + ":S-end" + it.seasonEnd + ":" + (it.language ?: "orig") }}")

        val refererBase = MOVIEBOX_REFERER
        val out = mutableListOf<RawStream>()
        val seenUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Subjects resolve concurrently ().
        val subjectResults = kotlinx.coroutines.coroutineScope {
            subjects.map { ref ->
                async {
                    val subjectId = ref.id
                    val language = ref.language
                    // Series entry EXPLICITLY covering fewer seasons than requested can't serve this episode (library splits shows into.
                    // S1-S3 / S4-�). seasonEnd==0 (no.
                    if (type != "movie" && ref.seasonEnd in 1 until season) return@async emptyList<RawStream>()

                    // 3. detailPath: search already carries it; h5 lookup is fallback only.
                    val detailPath = ref.detailPath.takeIf { it.isNotBlank() } ?: withTimeoutOrNull(8_000L) {
                        runCatching {
                            app.get("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                                timeout = 8).text
                        }.getOrNull()
                    }?.let { t ->
                        runCatching { org.json.JSONObject(t) }.getOrNull()
                            ?.optJSONObject("data")
                            ?.optJSONArray("items")?.optJSONObject(0)
                            ?.optJSONObject("subject")
                            ?.optString("detailPath", "").orEmpty()
                    }.orEmpty()
                    if (detailPath.isBlank()) return@async emptyList<RawStream>()

                    val reqHeaders = baseHeaders + mapOf(
                        "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
                        "Origin" to "https://fmoviesunblocked.net",
                    )
                    val params = buildString {
                        append("subjectId=$subjectId")
                        if (type != "movie") append("&se=$seasonKey&ep=$episodeKey")
                        append("&detailPath=$detailPath")
                    }

                    // 4. download + play endpoints; play is gated (per-IP budget).
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
                            withMovieBoxPlayPermit {
                                withTimeoutOrNull(8_000L) {
                                    runCatching {
                                        app.get("$base/wefeed-h5api-bff/subject/play?$params",
                                            timeout = 8, headers = reqHeaders).text
                                    }.getOrNull()
                                }?.let { t -> runCatching { org.json.JSONObject(t) }.getOrNull() }
                            }
                        }
                        (d.await() ?: org.json.JSONObject()) to (p.await() ?: org.json.JSONObject())
                    }

                    // (Server subtitle tracks in the play response are ignored: SubtilesProvider is the only subtitle provider. ).
                    var lockedTotal = 0
                    fun addStreams(arr: org.json.JSONArray?, dash: Boolean): List<RawStream> {
                        if (arr == null) return emptyList()
                        val added = mutableListOf<RawStream>()
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            if (s.optBoolean("vipLocked", false)) { lockedTotal++; continue }
                            val rawUrl = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val sign = s.optString("signCookie").takeIf { it.isNotBlank() }
                            val cookie = cloudFrontCookie(sign)
                            // DASH ladder carries the manifest inside the policy.
                            var finalUrl = rawUrl
                            var isDashEntry = dash
                            if (dash) {
                                val manifest = dashManifestFromPolicy(sign)
                                if (manifest != null) finalUrl = manifest
                                else {
                                    if (isMovieboxNoticeUrl(rawUrl)) continue
                                    continue
                                }
                                isDashEntry = true
                            } else {
                                if (isMovieboxNoticeUrl(rawUrl)) continue
                                if (rawUrl.contains(".mpd", ignoreCase = true)) isDashEntry = true
                            }
                            if (!seenUrls.add(finalUrl)) continue
                            val resolution = s.optString("resolutions", "").toIntOrNull()
                                ?: s.optInt("resolution", 0)
                            val isHindi = language?.contains("hindi", ignoreCase = true) == true
                            val headers = LinkedHashMap<String, String>()
                            headers["Referer"] = refererBase
                            headers["User-Agent"] = HttpKit.userAgent
                            if (cookie != null) headers["Cookie"] = cookie
                            val isHls = finalUrl.contains(".m3u8", ignoreCase = true)
                            val isMpd = isDashEntry || finalUrl.contains(".mpd", ignoreCase = true)
                            added += RawStream(
                                serverId = spec.id,
                                serverName = spec.name,
                                url = finalUrl,
                                isM3u8 = isHls || isMpd,
                                referer = refererBase,
                                qualityHint = resolution,
                                audioPriority = if (isHindi) 4 else 2,
                                audioLabel = language ?: "",
                                extraHeaders = headers,
                                isDash = isMpd,
                            )
                        }
                        return added
                    }
                    val streams = addStreams(unwrapData(downloadObj).optJSONArray("downloads"), dash = false) +
                        addStreams(unwrapData(playObj).optJSONArray("streams"), dash = false) +
                        addStreams(unwrapData(playObj).optJSONArray("dash"), dash = true)
                    if (lockedTotal > 0) Log.i("MovieBox", "$subjectId: $lockedTotal VIP-locked rendition(s) skipped")
                    streams
                }
            }.awaitAll()
        }
        subjectResults.forEach { out += it }
        // Every matched subject explicitly covers fewer seasons than requested ("S1-S3" markers only) � the episode is a.
// library miss, not a host failure.
        if (out.isEmpty() && type != "movie" && subjects.isNotEmpty() &&
            subjects.all { it.seasonEnd in 1 until season }
        ) {
            throw CleanMissException("subjects cover up to S${subjects.maxOf { it.seasonEnd }}, requested S$season")
        }

        Log.d("MovieBox", "got ${out.size} streams from ${subjects.size} subjects")
        return out
    }

    /** PrimeSrc resolver (primesrc. me, ). IMDB-keyed, 2-step: /api/v1/s?imdb={id}&type=movie|tv returns info + servers. ({name, key, file_name. */
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
            // Sub-server name distinguishes same-host duplicates; the file name carries the real resolution ("� 1080p �") when the.
// player link itself reports none.
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

    /** Resolve one player/stream link through the multi-strategy pipeline (unwrap? harvest? extractor registry). Returns. null on failure. */
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
                val dash = url.contains(".mpd", ignoreCase = true)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = url, isM3u8 = url.contains(".m3u8", ignoreCase = true) || dash,
                    referer = referer, audioPriority = audioPriority, audioLabel = audioLabel,
                    isDash = dash,
                )
            }
        }

        // CloudStream extractor registry on the inner player host.
        val regLinks = mutableListOf<ExtractorLink>()
        runCatching {
            loadExtractor(url = unwrappedUrl, referer = link,
                subtitleCallback = { }, callback = { regLinks.add(it) })
        }
        if (regLinks.isNotEmpty()) {
            return regLinks.map { l ->
                val dash = l.type == ExtractorLinkType.DASH || l.url.contains(".mpd", ignoreCase = true)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = l.url, isM3u8 = l.type == ExtractorLinkType.M3U8 || dash,
                    referer = l.referer, qualityHint = l.quality,
                    audioPriority = audioPriority, audioLabel = audioLabel,
                    isDash = dash,
                )
            }
        }
        return null
    }

        /**
     * VaPlayer resolver: IMDB-keyed JSON
     * API → `data.stream_urls[]` are DIRECT HLS master playlists (up to
     * 1920x800 ≈ 1080p). Zero crypto. Referer nextgencloudfabric.com required
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
        val imdb = imdbId ?: throw CleanMissException("vaplayer: IMDB id unavailable (TMDB lookup miss)")
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
            // trip) � the host is up, this title just isn't in it.
            if (statusCode == 404) throw CleanMissException("not in VaPlayer catalog (404)")
            Log.w("VaPlayer", "status_code=$statusCode")
            return emptyList()
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val urls = data.optJSONArray("stream_urls") ?: return emptyList()
        // The file_name carries the real height ("Title (year) [1080p]").
        // VaPlayer returns several masters (one per CDN/height); without a real
        // height they all share qualityHint=0 and collapse in dedupeNames into a
        // single numbered group ("VaPlayer-1 � -3"), which reads as the server
        // appearing multiple times. Seed each URL with its height so they stay
        // distinct (the emit() master re-parse refines it further).
        val fileName = data.optString("file_name", "")
        // Server-provided height hint from file_name; fall back to 0 (adaptive) so emit()
        // measures the real master height instead of falsely claiming 1080p.
        val fileNameHeight = Regex("""\[(\d{3,4})p\]""", RegexOption.IGNORE_CASE)
            .find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val out = mutableListOf<RawStream>()
        for (i in 0 until urls.length()) {
            val u = urls.optString(i).takeIf { it.isNotBlank() } ?: continue
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = u, isM3u8 = true, referer = referer,
                qualityHint = fileNameHeight,
            )
        }
        Log.d("VaPlayer", "got ${out.size} master playlists")
        return out
    }

    /**
     * NetMirror resolver:
     * TMDB-keyed JSON API � Netflix-grade direct MP4 ladders (360?1080p),
     * ZERO crypto, zero captcha.
     *
     * Dub discovery (user report "original only"): the web player's
     * audio menu is served by /api/variants-tmdb/{type}/{id}[?se=&ep=] ->
     * {variants: [{dubSubjectId, language:"Hindi dub", detailPath}]} � every
     * dub is its OWN subject and each resolves to a DISTINCT file at
     * /api/embed-tmdb/{id}?type=..&dub={dubSubjectId}&dubdp={detailPath} -
     * every dub resolves to its own file, distinct from the default
     * ladder. "* dub" variants = dubbed AUDIO; "* sub" variants =
     * subtitle-only (original audio) and are skipped. The default (no params)
     * ladder carries the original audio and stays UNLABELLED � the never-guess
     * rule; labelled dubs come from the host's own language fields.
     *
     * Fan-out budget: default-embed ? variants GET in parallel (12s/6s) then
     * N dub embeds in PARALLEL (12s) -> worst ? 24s under the 50s farm kill
     * (generous headroom above the chain - a canned timeout is a breaker
     * strike; past upstream flaps taught this).
     */
    private suspend fun resolveNetmirror(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId?.toString() ?: return emptyList()
        val playReferer = "https://videodownloader.site/"
        val headers = okHeaders("https://net27.cc/")
        val tvParams = if (type == "tv") "&se=$season&ep=$episode" else ""

        /** GET embed-tmdb for one (optional dubbed) subject. null = no usable answer.
         *  The web player ALWAYS sends type= (movie included) � mirror it exactly. */
        suspend fun fetchEmbed(dub: String?, dubdp: String?): org.json.JSONObject? {
            val query = buildString {
                if (type == "tv") append("?type=tv").append(tvParams)
                else append("?type=movie")
                if (dub != null) { append("&dub=").append(dub); append("&dubdp=").append(dubdp) }
            }
            val jsonText = withTimeoutOrNull(12_000L) {
                runCatching {
                    app.get("https://net27.cc/api/embed-tmdb/$id$query",
                        timeout = 12, headers = headers).text
                }.getOrNull()
            } ?: return null
            return runCatching { org.json.JSONObject(jsonText) }.getOrNull()
        }

        fun streamsOf(root: org.json.JSONObject?, label: String?): List<RawStream> {
            if (root == null || !root.optBoolean("ok", false)) return emptyList()
            val out = mutableListOf<RawStream>()
            val arr = root.optJSONArray("streams")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").takeIf { it.isNotBlank() && it.startsWith("http") } ?: continue
                    out += RawStream(
                        serverId = spec.id, serverName = spec.name,
                        url = url, isM3u8 = false, referer = playReferer,
                        qualityHint = o.optInt("resolution", 0),
                        audioLabel = label ?: "",
                    )
                }
            }
            if (out.isEmpty()) {
                root.optString("mp4").takeIf { it.isNotBlank() && it.startsWith("http") }?.let { u ->
                    out += RawStream(
                        serverId = spec.id, serverName = spec.name,
                        url = u, isM3u8 = false, referer = playReferer,
                        qualityHint = root.optInt("resolution", 0),
                        audioLabel = label ?: "",
                    )
                }
            }
            return out
        }

        // 1+2. Default ladder (original audio) and the variant list (web
        //        player's audio menu) fetched in PARALLEL: max(12,6) + 12 dub
        //        fan-out ? 24s chain under the 50s farm kill (user spec
        //        round-3). A variants failure is NOT fatal �
        //        the default ladder keeps the old (verified) single-server
        //        contract alive.
        val (defaultRoot, vText) = coroutineScope {
            val rootA = async { fetchEmbed(null, null) }
            val varsA = async {
                withTimeoutOrNull(6_000L) {
                    runCatching {
                        val vQuery = if (type == "tv") "?se=$season&ep=$episode" else ""
                        app.get("https://net27.cc/api/variants-tmdb/$type/$id$vQuery",
                            timeout = 6, headers = headers).text
                    }.getOrNull()
                }
            }
            rootA.await() to varsA.await()
        }
        // The default ladder's audio is NOT host-declared anywhere (sampled
        // variants carry isOriginal=false even for originals), so it stays
        // BLANK-labelled � honest unknown (never-guess rule). Originals get
        // the "Original" tag only when the host's variant says so.
        val defaultStreams = streamsOf(defaultRoot, null)

        val dubList = runCatching {
            org.json.JSONObject(vText ?: return@runCatching emptyList()).optJSONArray("variants").let { arr ->
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val v = arr?.optJSONObject(i) ?: return@mapNotNull null
                    val lang = v.optString("language")
                    val sid = v.optString("dubSubjectId")
                    val dp = v.optString("detailPath")
                     // LANGUAGE POLICY:
                    // [netmirrorDubLabel]. Unmatched variants (Japanese dub on
                    // a Korean film, "ptbr dub", "* sub" subtitle-only rows)
                    // are DROPPED � they would clutter rows Indflix viewers
                    // never select; the ORIGINAL never drops (default ladder or
                    // an isOriginal variant), and "* sub" rows carry the
                    // default's audio anyway.
                    val label = netmirrorDubLabel(lang, v.optBoolean("isOriginal", false))
                    if (label != null && sid.isNotBlank() && dp.isNotBlank()) Triple(sid, dp, label) else null
                }
            }
        }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        val dubs = coroutineScope {
            dubList.filter { (sid, _, _) -> seen.add(sid) }.take(12)
                .map { (sid, dp, lang) ->
                    async {
                        runCatching { streamsOf(fetchEmbed(sid, dp), lang) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
        }

        // Dedupe by FILE (signed query stripped). When the blank default row
        // collides with an explicitly labelled dub (same file), the LABEL wins
        // � the host told us what that audio is.
        val byFile = LinkedHashMap<String, RawStream>()
        for (st in defaultStreams + dubs) {
            val key = st.url.substringBefore("?")
            val prev = byFile[key]
            when {
                prev == null -> byFile[key] = st
                prev.audioLabel.isBlank() && st.audioLabel.isNotBlank() -> byFile[key] = st
            }
        }
        val out = ArrayList<RawStream>(byFile.size)
        out += byFile.values
        if (out.isEmpty()) {
            if (defaultRoot == null) {
                Log.w("NetMirror", "no API response for $id ($type)")
                return emptyList() // network failure -> strike (host really down)
            }
            if (!defaultRoot.optBoolean("ok", false)) {
                throw CleanMissException("netmirror: not mirrored (ok=false) for $id")
            }
            Log.w("NetMirror", "ok=true but no stream urls; keys=${namesOf(defaultRoot)}")
            return emptyList()
        }
        Log.d("NetMirror",
            "default=${defaultStreams.size} dubs=[${dubs.map { it.audioLabel }.filter { it.isNotBlank() }.distinct()}]")
        out.sortByDescending { it.qualityHint }
        return out
    }

    /**
     * NetMirror variant-language policy:
     * keep the ORIGINAL (any country � anime keeps Japanese, a Korean title
     * keeps Korean � displayed via the TMDB original language), ENGLISH, and
     * every official INDIAN dub language mapped to its canonical name
     * ([ManifestKit.INDIAN_DUB_LANGUAGES]: Hindi/Tamil/Telugu/Bengali/
     * Malayalam/Kannada/Marathi/Punjabi/Gujarati). Everything else is dropped
     * � German/French/Spanish/Russian/"ptbr"/"esla" dubs and "* sub"
     * (subtitle-only, same audio as the default) rows only clutter the sheet.
     * Pure mapping from the host's OWN fields � never guesses a language.
     */
    internal fun netmirrorDubLabel(language: String?, isOriginal: Boolean): String? {
        if (isOriginal) return "Original"
        val m = Regex("""^(.*\S)\s+dub$""", RegexOption.IGNORE_CASE)
            .find(language?.trim().orEmpty())?.groupValues?.get(1)?.trim().orEmpty()
        if (m.isBlank()) return null
        if (m.equals("english", true) || m.equals("eng", true) || m.equals("en", true)) return "English"
        return ManifestKit.INDIAN_DUB_LANGUAGES.firstOrNull { d ->
            d.names.any { it.equals(m, ignoreCase = true) }
        }?.canonical
    }

    /**
     * VidRock resolver: TMDB-keyed JSON
     * API → `{serverName: {url (AES-GCM b64url), language, type}}`. URLs are
     * decrypted locally (static key):
     * 12-byte nonce prefix + ciphertext+tag, AES/GCM/NoPadding.
     * `language` field marks "Hindi" when the server carries a Hindi dub.
     */
    /**
     * VixSrc resolver: TMDB-keyed JSON hands an embed
     * path; the embed page carries token/expires/playlist, master is HLS.
     */
    private suspend fun resolveVixsrc(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val base = "https://vixsrc.to"
        val apiUrl = if (type == "movie") "$base/api/movie/$id"
        else "$base/api/tv/$id/$season/$episode"
        val apiHeaders = okHeaders("$base/") + mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Origin" to base,
        )
        val apiText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(apiUrl, timeout = 8, headers = apiHeaders).text }.getOrNull()
        } ?: run { Log.w("VixSrc", "api no response"); return emptyList() }
        val src = runCatching { org.json.JSONObject(apiText).optString("src") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("VixSrc", "no src in api response"); return emptyList() }
        val embedUrl = if (src.startsWith("http")) src else base + src
        val html = withTimeoutOrNull(8_000L) {
            runCatching { app.get(embedUrl, timeout = 8, headers = okHeaders(apiUrl)).text }.getOrNull()
        } ?: run { Log.w("VixSrc", "embed page no response"); return emptyList() }
        val (token, expires, playlist) = vixsrcTokenData(html)
            ?: run { Log.w("VixSrc", "no token/expires/playlist in embed"); return emptyList() }
        if (!vixsrcTokenFresh(expires, System.currentTimeMillis())) {
            Log.w("VixSrc", "token expired, skipping")
            return emptyList()
        }
        val absPlaylist = HttpKit.resolveUrl(embedUrl, playlist)
        val sep = if (absPlaylist.contains("?")) "&" else "?"
        val masterUrl = "$absPlaylist${sep}token=$token&expires=$expires&h=1"
        val masterText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(masterUrl, timeout = 8, headers = okHeaders(apiUrl)).text }.getOrNull()
        } ?: run { Log.w("VixSrc", "master fetch failed"); return emptyList() }
        val (label, h) = probeAudioInlineHeight(masterText)
        return listOf(
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = masterUrl, isM3u8 = true, referer = apiUrl,
                qualityHint = h, audioLabel = label,
                inlineManifest = masterText.takeIf { it.contains("#EXT-X-STREAM-INF") },
                extraHeaders = mapOf("User-Agent" to HttpKit.userAgent),
            )
        )
    }

    /** Token triple out of a VixSrc embed page, or null. Pure scans. */
    internal fun vixsrcTokenData(html: String): Triple<String, String, String>? {
        fun field(key: String, from: Int = 0): Pair<String, Int>? {
            val ki = html.indexOf(34.toChar() + key + 34.toChar(), from).takeIf { it >= 0 } ?: return null
            val ci = html.indexOf(58.toChar(), ki).takeIf { it >= 0 } ?: return null
            var si = ci + 1
            while (si < html.length && (html[si] == 32.toChar() || html[si] == 34.toChar() || html[si] == 39.toChar())) si++
            var ei = si
            while (ei < html.length && html[ei] != 34.toChar() && html[ei] != 39.toChar()) ei++
            if (ei <= si) return null
            return html.substring(si, ei) to ei
        }
        val (token, p1) = field("token") ?: return null
        val (expires, p2) = field("expires", p1) ?: return null
        val (playlist, _) = field("url", p2) ?: return null
        return Triple(token, expires, playlist)
    }

    /** VixSrc token liveness (60s grace). Pure. */
    internal fun vixsrcTokenFresh(expires: String, nowMs: Long): Boolean {
        val exp = expires.toLongOrNull() ?: return false
        return exp * 1000 - 60_000 >= nowMs
    }

        /** CastleTV: title search, Hindi-first audio track, single 1080p resolve. */
    private suspend fun resolveCastleTv(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = runCatching { TmdbService.fetchMeta(tmdbId, type) }.getOrNull()
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta?.year?.take(4)
        val sec = CastleTvSource.securityKey() ?: throw CleanMissException("no security key")
        val rows = CastleTvSource.search(sec, if (year != null) "$title $year" else title)
        if (rows.isEmpty()) throw CleanMissException("no title match")
        val best = rows.maxByOrNull { TitleMatch.titleDistance(title, it.second) }
            ?: throw CleanMissException("no title match")
        if (!TitleMatch.isRelevant(title, best.second, year?.toIntOrNull(), null)) {
            throw CleanMissException("match too weak: ${best.second}")
        }
        var det = CastleTvSource.details(sec, best.first) ?: throw CleanMissException("no details")
        var activeId = best.first
        if (type != "movie") {
            if (season <= 0 || episode <= 0) throw CleanMissException("tv without season/episode")
            val seasons = det.optJSONArray("seasons")
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val s = seasons.optJSONObject(i) ?: continue
                    if (s.optInt("number", -1) == season) {
                        val sid = s.opt("movieId")?.takeIf { it != org.json.JSONObject.NULL }?.toString()
                        if (!sid.isNullOrBlank() && sid != activeId) {
                            det = CastleTvSource.details(sec, sid) ?: det
                            activeId = sid
                        }
                        break
                    }
                }
            }
        }
        val eps = det.optJSONArray("episodes") ?: throw CleanMissException("no episodes")
        val epEntry: org.json.JSONObject? = if (type == "movie") eps.optJSONObject(0)
        else (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }
            .firstOrNull { it.optInt("number", -1) == episode }
        val epId = epEntry?.opt("id")?.takeIf { it != org.json.JSONObject.NULL }?.toString()
            ?: throw CleanMissException("no episode id")
        val tracksJson = epEntry.optJSONArray("tracks")
        val tracks = (0 until (tracksJson?.length() ?: 0)).mapNotNull { i ->
            val t = tracksJson!!.optJSONObject(i) ?: return@mapNotNull null
            CastleTvSource.Track(
                t.optString("languageId"),
                t.optString("languageName").ifBlank { t.optString("abbreviate") },
                t.optBoolean("existIndividualVideo"),
            )
        }
        val pick = CastleTvSource.pickTrack(tracks)
        val vids = mutableListOf<CastleTvSource.Video>()
        if (pick != null) {
            CastleTvSource.video(sec, activeId, epId, pick.languageId.takeIf { it.isNotBlank() }, 3)
                ?.let { vids += CastleTvSource.videosOf(it, 3) }
        }
        if (vids.isEmpty()) {
            CastleTvSource.video(sec, activeId, epId, null, 3)
                ?.let { vids += CastleTvSource.videosOf(it, 3) }
        }
        if (vids.isEmpty()) throw CleanMissException("no video urls")
        val hindi = pick?.languageName?.contains("hindi", ignoreCase = true) == true
        val langLabel = if (hindi) "Hindi" else pick?.languageName.orEmpty()
        return vids.take(4).map {
            RawStream(spec.id, "${spec.name} $langLabel".trim(), it.url,
                it.url.contains(".m3u8", ignoreCase = true), null, releaseHeightOf(it.quality),
                audioPriority = if (hindi) 4 else 1, audioLabel = langLabel)
        }
    }

    @Volatile private var streamflixData: String? = null
    @Volatile private var streamflixDataAt: Long = 0L
    private const val STREAMFLIX_TTL_MS = 30 * 60 * 1000L
    private const val STREAMFLIX_BASE = "https://cf.streamflixserver.site/"

    /** StreamFlix: TMDB-keyed catalog match, direct file links. */
    private suspend fun resolveStreamFlix(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val now = System.currentTimeMillis()
        var data = if (now - streamflixDataAt < STREAMFLIX_TTL_MS) streamflixData else null
        if (data == null) {
            data = withTimeoutOrNull(20_000L) {
                runCatching {
                    app.get("https://api.streamflix.app/data.json", timeout = 20, headers = okHeaders(null)).text
                }.getOrNull()
            }
            if (data != null) { streamflixData = data; streamflixDataAt = now }
        }
        val items = data?.let { runCatching { org.json.JSONObject(it).optJSONArray("data") }.getOrNull() }
            ?: throw CleanMissException("no catalog")
        var item: org.json.JSONObject? = null
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.opt("tmdb")?.toString() == tmdbId.toString()) { item = it; break }
        }
        val found = item ?: throw CleanMissException("title not in catalog")
        var base = STREAMFLIX_BASE
        withTimeoutOrNull(8_000L) {
            runCatching {
                app.get("https://api.streamflix.app/config/config-streamflixapp.json",
                    timeout = 8, headers = okHeaders(null)).text
            }.getOrNull()?.let { org.json.JSONObject(it).optJSONArray("download")?.optString(0) }
                ?.takeIf { s -> s.isNotBlank() }?.let { base = it }
        }
        val links = mutableListOf<String>()
        if (type == "movie") {
            found.optString("movielink").takeIf { it.isNotBlank() }?.let { links += it }
        } else {
            if (season <= 0 || episode <= 0) throw CleanMissException("tv without season/episode")
            val key = found.optString("moviekey").takeIf { it.isNotBlank() }
                ?: throw CleanMissException("no series key")
            val epsText = withTimeoutOrNull(10_000L) {
                runCatching {
                    app.get("https://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app" +
                        "/Data/$key/seasons/$season/episodes.json", timeout = 10, headers = okHeaders(null)).text
                }.getOrNull()
            } ?: throw CleanMissException("no episode index")
            val eps = runCatching { org.json.JSONObject(epsText) }.getOrNull()
                ?: throw CleanMissException("no episode index")
            val ep = eps.optJSONObject((episode - 1).toString()) ?: eps.optJSONObject(episode.toString())
            ep?.optString("link")?.takeIf { it.isNotBlank() }?.let { links += it }
        }
        if (links.isEmpty()) throw CleanMissException("no file links")
        return links.take(3).map { link ->
            val url = if (link.startsWith("http")) link else base.trimEnd('/') + "/" + link.trimStart('/')
            RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true),
                null, releaseHeightOf(link))
        }
    }

    /** Release-tag language to canonical label. Pure. */
    internal fun releaseLanguageOf(text: String?): String {
        val u = (text ?: "").uppercase()
        return when {
            Regex("""\bHINDI\b""").containsMatchIn(u) -> "Hindi"
            Regex("""\bTAMIL\b""").containsMatchIn(u) -> "Tamil"
            Regex("""\bTELUGU\b""").containsMatchIn(u) -> "Telugu"
            Regex("""\b(MULTI|DUAL|DUBBED)\b""").containsMatchIn(u) -> "Multi"
            Regex("""\b(ENGLISH|ENG)\b""").containsMatchIn(u) -> "English"
            else -> ""
        }
    }

    /** Release-tag resolution to ladder height. Pure. */
    internal fun releaseHeightOf(text: String?): Int {
        val u = (text ?: "").uppercase()
        return when {
            u.contains("2160P") || Regex("""\b4K\b""").containsMatchIn(u) -> 2160
            u.contains("1080P") -> 1080
            u.contains("720P") -> 720
            u.contains("480P") -> 480
            else -> 0
        }
    }

    /** Hub-family search cards as (url, title). Pure. */
    internal fun hubParseCards(html: String, base: String): List<Pair<String, String>> {
        val re = Regex("""<a\s+href="([^"]+)"\s+class="movie-card"\s+aria-label="([^"]+)"""", RegexOption.IGNORE_CASE)
        return re.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1]
            val title = m.groupValues[2].removeSuffix(" details").trim()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> base.trimEnd('/') + href
                else -> base.trimEnd('/') + "/" + href
            }
            url to title
        }.toList()
    }

    /** Hub post year check via og:title/<title>. Pure. */
    internal fun hubPostYearOk(html: String, year: String?): Boolean {
        if (year.isNullOrBlank()) return true
        val title = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return true
        return title.contains(year)
    }

    /** Hub post file-host links (hubcloud drive pages). Pure. */
    internal fun hubDriveLinks(html: String): List<String> {
        return Regex("""href="(https?://hubcloud\.ist/drive/[^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.distinct().toList()
    }

    /** Hubcloud download-page buttons as (url, quality). Pure. */
    internal fun hubFileButtons(html: String): List<Pair<String, String>> {
        val header = Regex("""card-header[^>]*>(.*?)</""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1).orEmpty()
        val headQ = releaseHeightOf(header)
        val btnRe = Regex("""<a[^>]+class="[^"]*\bbtn\b[^"]*"[^>]+href="([^"]+)"[^>]*>([^<]{0,60})""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val altRe = Regex("""<a[^>]+href="([^"]+)"[^>]+class="[^"]*\bbtn\b[^"]*"[^>]*>([^<]{0,60})""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val out = mutableListOf<Pair<String, String>>()
        for (m in btnRe.findAll(html) + altRe.findAll(html)) {
            var url = m.groupValues[1]
            if (!url.startsWith("http")) continue
            if (url.contains("facebook.") || url.contains("twitter.") || url.contains("t.me")) continue
            url = Regex("""pixeldrain\.[a-z]+/u/([a-zA-Z0-9]+)""").find(url)?.let {
                url.replace("/u/${it.groupValues[1]}", "/api/file/${it.groupValues[1]}")
            } ?: url
            val q = releaseHeightOf(m.groupValues[2]).takeIf { it > 0 } ?: headQ
            out += url to (if (q > 0) "${q}p" else "")
        }
        return out.distinctBy { it.first }
    }

    /** Single hubcloud drive page to file links (token used fresh, inline). */
    private suspend fun hubDriveFiles(driveUrl: String, referer: String): List<Pair<String, String>> {
        val drive = withTimeoutOrNull(8_000L) {
            runCatching { app.get(driveUrl, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return emptyList()
        val tokenHref = Regex("""hubcloud\.php\?[^"' ]+""").find(drive)?.value ?: return emptyList()
        val tokenUrl = if (tokenHref.startsWith("http")) tokenHref else "https://hubcloud.ist/$tokenHref"
        val page = withTimeoutOrNull(10_000L) {
            runCatching { app.get(tokenUrl, timeout = 10, headers = okHeaders(driveUrl)).text }.getOrNull()
        } ?: return emptyList()
        return hubFileButtons(page).take(4)
    }

    /** Hub-family (Hindi-dub file index): search, year-verified post, file-host links. */
    private suspend fun resolveHub4k(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = runCatching { TmdbService.fetchMeta(tmdbId, type) }.getOrNull()
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta?.year?.take(4)
        val base = "https://4khdhub.one"
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val searchHtml = withTimeoutOrNull(8_000L) {
            runCatching { app.get("$base/?s=${enc(title)}", timeout = 8, headers = okHeaders(base)).text }.getOrNull()
        } ?: throw CleanMissException("no search answer")
        val cards = hubParseCards(searchHtml, base)
        if (cards.isEmpty()) throw CleanMissException("no search hits")
        val ranked = cards.map { it to TitleMatch.titleDistance(title, it.second) }
            .sortedByDescending { it.second }.take(3)
        if (ranked.isEmpty() || ranked[0].second < 0.6) throw CleanMissException("no match")
        for ((card, _) in ranked.take(2)) {
            val post = withTimeoutOrNull(8_000L) {
                runCatching { app.get(card.first, timeout = 8, headers = okHeaders(base)).text }.getOrNull()
            } ?: continue
            if (!hubPostYearOk(post, year)) continue
            val lang = releaseLanguageOf(
                Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(post)?.groupValues?.get(1).orEmpty() + " " + card.second,
            )
            val drives = hubDriveLinks(post).take(3)
            for (d in drives) {
                val files = hubDriveFiles(d, card.first)
                if (files.isNotEmpty()) {
                    val hindi = lang == "Hindi"
                    return files.map { (url, q) ->
                        RawStream(spec.id, "${spec.name} $q".trim(),
                            url, url.contains(".m3u8", ignoreCase = true),
                            "https://hubcloud.ist/", releaseHeightOf(q),
                            audioPriority = if (hindi) 4 else if (lang == "Multi") 2 else 1,
                            audioLabel = lang)
                    }
                }
            }
        }
        throw CleanMissException("no file links")
    }

    /** Videasy resolver: multi-route API with local mvm1 decrypt. CDN carries HLS up to 2160p. */
    private suspend fun resolveVideasy(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
        imdbIdProvider: (suspend () -> String?)? = null,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val meta = runCatching { TmdbService.fetchMeta(id, type) }.getOrNull()
        val title = meta?.name ?: throw CleanMissException("no title for tmdb=$id (title-keyed API)")
        val year = meta?.year?.take(4)?.toIntOrNull()
        val imdb = imdbId ?: imdbIdProvider?.invoke()

        val fetched = VideasySource.fetchAllSources(
            tmdbId = id, imdbId = imdb, title = title, year = year,
            mediaType = type, season = season, episode = episode,
        )
        if (!fetched.httpOk) return emptyList()
        if (fetched.sources.isEmpty()) {
            throw CleanMissException("upstream answered, no entry (flap or library miss)")
        }
        val referer: String? = null
        return fetched.sources.map { s ->
            val lang = VideasySource.languageOf(s.quality)
            RawStream(
                serverId = spec.id,
                serverName = "${spec.name} ${s.route.replaceFirstChar { it.uppercase() }}".trim(),
                url = s.url, isM3u8 = VideasySource.isHls(s.url),
                referer = referer, qualityHint = VideasySource.heightOf(s.quality),
                audioLabel = lang,
                extraHeaders = VideasySource.playbackHeaders(),
            )
        }.distinctBy { it.url }
    }

    /** OneTouchTV resolver: title search, AES envelope, per-episode HLS sources. */
    private suspend fun resolveOneTouchTv(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val meta = runCatching { TmdbService.fetchMeta(id, type) }.getOrNull()
        val title = meta?.name ?: throw CleanMissException("no title for tmdb=$id (title-keyed API)")
        val year = meta?.year?.take(4)?.toIntOrNull()
        val hits = OneTouchTvSource.search(title)
        if (hits.isEmpty()) throw CleanMissException("no search hits for '$title'")
        val hit = OneTouchTvSource.matchTitle(hits, title, year, type)
            ?: throw CleanMissException("no title match for '$title' in ${hits.size} rows")
        val eps = OneTouchTvSource.episodes(hit.id)
        if (eps.isEmpty()) throw CleanMissException("no episodes for '${hit.title}'")
        val wantEp = if (type == "movie") 1 else {
            // Absolute-episode backends number continuously; S1 maps directly.
            if (season <= 1) episode.coerceAtLeast(1)
            else throw CleanMissException("multi-season absolute mapping unsupported (S$season)")
        }
        val ep = eps.firstOrNull { it.number == wantEp } ?: eps.firstOrNull()
            ?: throw CleanMissException("episode $wantEp missing")
        val (sources, _) = OneTouchTvSource.streams(hit.id, ep.playId)
        if (sources.isEmpty()) throw CleanMissException("no sources for '${hit.title}' ep ${ep.number}")
        return sources.map { s ->
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = s.url, isM3u8 = s.url.contains(".m3u8", true) || s.type.equals("hls", true),
                referer = OneTouchTvSource.playbackReferer(),
                qualityHint = OneTouchTvSource.heightOf(s.quality),
                extraHeaders = OneTouchTvSource.playbackHeaders(),
            )
        }.distinctBy { it.url }
    }

    private suspend fun resolveVidcore(
        spec: ServerSpec, tmdbId: Int?, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        val url = if (type == "movie") "https://vidrack.created.app/api/sources/movy?id=$id"
        else "https://vidrack.created.app/api/sources/movy?id=$id&season=$season&episode=$episode"
        val headers = mapOf("Referer" to "https://www.vidcore.org/", "User-Agent" to HttpKit.userAgent)
        Log.d("VidCore", "GET $url")
        val jsonText = withTimeoutOrNull(15_000L) {
            runCatching { app.get(url, timeout = 15, headers = headers).text }.getOrNull()
        } ?: run { Log.w("VidCore", "no API response"); return emptyList() }
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("VidCore", "non-JSON: ${safeSnippet(jsonText)}"); return emptyList()
        }
        val sources = root.optJSONArray("sources") ?: return emptyList()
        val out = mutableListOf<RawStream>()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val streamUrl = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val quality = s.optString("quality", "")
            val label = s.optString("label", quality)
            val streamHeaders = mutableMapOf("User-Agent" to HttpKit.userAgent)
            val h = s.optJSONObject("headers")
            if (h != null) {
                h.keys().forEach { k -> streamHeaders[k] = h.optString(k) }
            } else {
                streamHeaders["Referer"] = "https://www.movy.bz/"
                streamHeaders["Origin"] = "https://www.movy.bz"
            }
            out += RawStream(
                serverId = spec.id, serverName = "${spec.name} $label".trim(),
                url = streamUrl, isM3u8 = streamUrl.contains(".m3u8", true),
                referer = streamHeaders["Referer"] ?: "https://www.movy.bz/",
                qualityHint = VideasySource.heightOf(quality),
                extraHeaders = streamHeaders,
            )
        }
        Log.d("VidCore", "parsed ${out.size} sources")
        return out
    }

    private suspend fun resolveVidrock(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        val apiUrl = if (type == "movie") "https://vidrock.to/api/movie/$id/"
        else "https://vidrock.to/api/tv/$id/$season/$episode/"
        val headers = mapOf(
            "User-Agent" to HttpKit.userAgent,
            "Origin" to "https://vidrock.to",
            "Referer" to "https://vidrock.to/",
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
            // Keep the host sub-server in the name (Nova/Atlas/Luna/...).
            out += RawStream(
                serverId = spec.id, serverName = "${spec.name} $serverName".trim(),
                url = decrypted,
                isM3u8 = decrypted.contains(".m3u8", true) || sd.optString("type") == "hls",
                referer = "https://vidrock.to/",
                qualityHint = 0,
                audioPriority = if (isHindi) 4 else 1,
                audioLabel = lang.ifBlank { "" },
                extraHeaders = mapOf(
                    "Referer" to "https://vidrock.to",
                    "Origin" to "https://vidrock.to",
                ),
            )
        }
        Log.d("VidRock", "got ${out.size} servers (${out.map { it.audioLabel }.distinct()})")
        return out
    }

    /** VidRock AES-GCM decrypt: 12-byte nonce prefix, static 32-byte hex key. */
    private fun decryptVidrockUrl(payload: String): String? = runCatching {
        val keyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val std = payload.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        // java.util (not android.util): unit-test safe, identical on device.
        val data = java.util.Base64.getDecoder().decode(padded)
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
     * VidNest resolver (sub-server aggregator):
     * TMDB-keyed fan-out across the host's sub-servers � moviebox/allmovies/
     * klikxxi/onehd/vidlink/hollymoviehd/purstream. Movie + tv per sub-server:
     *   GET {server}/movie/{tmdb} | {server}/tv/{tmdb}/{s}/{e}
     *   -> {"encrypted":true,"data":"<custom-b64>"} | plain JSON
     * The custom base64 uses a NON-standard alphabet (see VIDNEST_ALPHABET);
     * decoded payloads are per-server JSON with DIFFERENT shapes:
     *   moviebox    {url:[{lang,link,resolution,type}]}
     *   allmovies   {streams:[{url,language,type}]}
     *   klikxxi     {sources:[{url,quality,type}]}
     *   onehd       {url,headers?,subtitles?}
     *   hollymoviehd{sources:[{file,label,type}]}
     *   purstream   {sources:[{url,format,name}]}
     *   vidlink     {data:{stream:{playlist,captions}}}
     * Sub-servers flap (502) independently: per-sub failures are skipped and
     * only the ANSWER COUNT is reported so the dispatch can distinguish
     * "host down" (breaker trip) from "title miss" (clean miss).
     * All sub-servers launch in parallel; per-sub timeout is half the spec
     * budget so the fan-out always fits inside the per-server kill.
     */
    /** True when a VidNest sub-server body is an ERROR PAGE, not content - the
     *  host answers 502s with a JSON/HTML body ("Error 502: Bad gateway",
     *  `error_name`) which earlier code mis-read as "answered" (host up, title
     *  miss) - the health signal was inverted exactly when users saw VidNest
     *  present with wrong/dead streams. Pure: string
     *  only, JVM-testable without org.json. */
    internal fun vidnestIsErrorPage(text: String?): Boolean {
        val t = text?.trimStart() ?: return true
        if (t.isEmpty()) return false
        if (t.startsWith("<")) return true
        return t.contains("\"error_name\"", ignoreCase = true) ||
            t.contains("bad gateway", ignoreCase = true) ||
            t.contains("\"cloudflare\"", ignoreCase = true) ||
            t.contains("error 50", ignoreCase = true)
    }

    /** Retry-worthy sub answers: blank or error pages only. A parsed-but-empty answer is a title miss. Pure. */
    internal fun vidnestShouldRetry(text: String?): Boolean =
        text.isNullOrBlank() || vidnestIsErrorPage(text)

    /** Url -> "season|episode" of the request that FIRST surfaced it. The
     *  VidNest moviebox sub-server answers different (se,ep) paths with the
     *  IDENTICAL generic file (same file for different episodes) - a stream
     *  already pinned to another episode is confidently WRONG, so drop it and
     *  log (never silently play a mismatched episode). Bounded; not persisted. */
    private val vidnestUrlEpisodes = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun resolveVidnest(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): Pair<List<RawStream>, Int> {
        val id = tmdbId ?: return emptyList<RawStream>() to 0
        val subServers = listOf(
            "moviebox", "allmovies", "klikxxi", "onehd",
            "hollymoviehd", "purstream", "vidlink",
        )
        val subTimeout = ((spec.timeoutSec - 2).coerceAtLeast(6) * 1000L / 2).toLong()
        val headers = mapOf(
            "User-Agent" to HttpKit.userAgent,
            "Referer" to "https://vidnest.fun/",
            "Origin" to "https://vidnest.fun",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )

        data class SubResult(val server: String, val answered: Boolean, val streams: List<RawStream>)

        val results = coroutineScope {
            subServers.map { sub ->
                async {
                    val url = if (type == "movie")
                        "https://new.vidnest.fun/$sub/movie/$id"
                    else "https://new.vidnest.fun/$sub/tv/$id/$season/$episode"
                    suspend fun fetchOnce(budgetMs: Long): String? = withTimeoutOrNull(budgetMs) {
                        runCatching { app.get(url, timeout = budgetMs / 1000, headers = headers).text }
                            .getOrNull()
                    }
                    var raw = fetchOnce(subTimeout)
                    if (vidnestShouldRetry(raw)) {
                        // Subs 502 transiently on cold starts: one delayed retry before counting it down.
                        delay(VIDNEST_SUB_RETRY_DELAY_MS)
                        raw = fetchOnce((subTimeout / 2).coerceAtLeast(2_000L))
                        if (!vidnestShouldRetry(raw)) Log.d("VidNest", "$sub: retry answered")
                    }
                    if (raw.isNullOrBlank()) return@async SubResult(sub, answered = false, streams = emptyList())
                    if (vidnestIsErrorPage(raw)) {
                        // 502 "Bad gateway" pages (JSON or HTML) are a host-DOWN
                        // answer, not a title miss: keep the answered=false health
                        // semantics (breaker sees the flap, aggregate can still
                        // trip when the WHOLE host is down).
                        Log.w("VidNest", "$sub: error page, counted as down: ${safeSnippet(raw)}")
                        return@async SubResult(sub, answered = false, streams = emptyList())
                    }
                    val decoded = runCatching {
                        val root = org.json.JSONObject(raw)
                        if (root.optBoolean("encrypted") && !root.isNull("data")) {
                            val payload = root.optString("data")
                            if (payload.isBlank()) {
                                Log.w("VidNest", "$sub: encrypted but empty data payload")
                                return@runCatching null
                            }
                            val json = decodeVidnestPayload(payload)
                            if (json == null) {
                                Log.w("VidNest", "$sub: base64 decode failed (${payload.length} chars, preview=${safeSnippet(payload)})")
                                return@runCatching null
                            }
                            org.json.JSONObject(json)
                        } else {
                            root
                        }
                    }.getOrNull() ?: return@async SubResult(sub, answered = true, streams = emptyList())
                    SubResult(sub, answered = true, streams = parseVidnestSub(sub, decoded, spec))
                }
            }.map { it.await() }
        }

        val answered = results.count { it.answered }
        var streams = results.flatMap { it.streams }
            // URL dedupe: vidlink sub-server can mirror what the dedicated
            // VidLink spec already emits � the farm dedupes later anyway, but
            // an in-resolver dedupe keeps the log honest.
            .distinctBy { it.url }
        // Episode-collapse guard (TV only): drop any sub-server url already
        // pinned to a DIFFERENT (season, episode) by an earlier request - the
        // same signed file cannot be two episodes - serving it here plays
        // the wrong episode.
        if (type != "movie") {
            if (vidnestUrlEpisodes.size > 400) vidnestUrlEpisodes.clear()
            val reqKey = "$season|$episode"
            streams = streams.filter { s ->
                val prior = vidnestUrlEpisodes.putIfAbsent(s.url, reqKey)
                if (prior != null && prior != reqKey) {
                    Log.w("VidNest", "drop ${s.serverName}: url already served S${prior} (requested S$reqKey) � episode collapse")
                    false
                } else true
            }
        }
        Log.d("VidNest", "fan-out: $answered/${subServers.size} answered, ${streams.size} streams " +
            "(${results.filter { it.streams.isNotEmpty() }.joinToString { it.server }})")
        return streams to answered
    }

    /** Parse one decoded VidNest sub-server payload into streams. The shapes
     *  differ per sub-server (documented on [resolveVidnest]); unknown shapes
     *  return empty and are logged with their keys for future additions. */
    private fun parseVidnestSub(sub: String, root: org.json.JSONObject, spec: ServerSpec): List<RawStream> {
        val out = mutableListOf<RawStream>()
        // Sub-server stays in the name so rows read VidNest Moviebox, ....
        val subName = "${spec.name} ${sub.replaceFirstChar { it.uppercase() }}".trim()
        fun add(url: String, lang: String, qualityLabel: String, isFile: Boolean) {
            if (!url.startsWith("http")) return
            val pri = when {
                lang.contains("hindi", true) -> 4
                lang.isBlank() -> 0
                else -> 2 // labelled non-Hindi audio (Tamil/Telugu/Japanese/...) � "Original" tier
            }
            out += RawStream(
                serverId = spec.id, serverName = subName,
                url = url, isM3u8 = !isFile || url.contains(".m3u8", true),
                referer = "https://vidnest.fun/",
                qualityHint = Regex("(\\d{3,4})").find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                audioPriority = pri,
                audioLabel = lang,
            )
        }
        // First usable value across candidate keys. Guards the JSON-null trap:
        // optString renders a null field as the literal "null", which is non-blank
        // and would shadow the real fallback key on a drifted shape.
        fun pick(o: org.json.JSONObject, vararg keys: String): String {
            for (k in keys) {
                if (o.isNull(k)) continue
                val v = o.optString(k).trim()
                if (v.isNotEmpty() && v != "null") return v
            }
            return ""
        }
        when (sub) {
            "moviebox" -> root.optJSONArray("url")?.let { arr ->
                // Ad guard: proxy serves one static promo file under every resolution label.
                val links = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("link")?.takeIf { it.isNotBlank() }
                }.toSet()
                if (arr.length() > 1 && links.size == 1) {
                    Log.w("VidNest", "moviebox: single file for ${arr.length()} labels, ad drop")
                    return@let
                }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("link"), o.optString("lang"), o.optString("resolution"), o.optString("type") == "mp4")
                }
            }
            "allmovies" -> root.optJSONArray("streams")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("url"), o.optString("language"), "", o.optString("type") == "mp4")
                }
            }
            "klikxxi" -> {
                // Shape drift: entries carry the file under url|file and the
                // rendition label under quality|label.
                val arr = root.optJSONArray("sources")
                if (arr == null) {
                    Log.d("VidNest", "klikxxi: no 'sources' array; keys=${namesOf(root)}")
                } else {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(pick(o, "url", "file"), "", pick(o, "quality", "label"),
                            o.optString("type") == "mp4")
                    }
                }
            }
            "hollymoviehd" -> root.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("file"), "", o.optString("label"), o.optString("type") == "mp4")
                }
            }
            "purstream" -> root.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("url"), "", o.optString("name"), o.optString("format") == "mp4")
                }
            }
            "onehd" -> add(root.optString("url"), "", "", true)
            "vidlink" -> root.optJSONObject("data")?.optJSONObject("stream")?.optString("playlist")?.let {
                add(it, "", "", true)
            }
        }
        if (out.isEmpty()) Log.d("VidNest", "$sub: no recognizable stream shape; keys=${namesOf(root)}")
        return out
    }

    /** VidNest custom-base64 decode - the host encodes payloads with its own
     *  alphabet (RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/=).
     *  Map each char back to its 6-bit value, re-pack into bytes, strip
     *  '=' placeholders (64 sentinel). */
    private val vidnestAlphabet = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

    private fun decodeVidnestPayload(input: String): String? = runCatching {
        val rev = HashMap<Char, Int>(vidnestAlphabet.length)
        vidnestAlphabet.forEachIndexed { idx, c -> rev[c] = idx }
        val pad = (4 - input.length % 4) % 4
        val padded = input + "=".repeat(pad)
        val bytes = ByteArray(padded.length / 4 * 3)
        var w = 0
        var i = 0
        while (i < padded.length) {
            val c0 = rev[padded[i]] ?: 64
            val c1 = rev[padded[i + 1]] ?: 64
            val c2 = if (padded[i + 2] == '=') 64 else rev[padded[i + 2]] ?: 64
            val c3 = if (padded[i + 3] == '=') 64 else rev[padded[i + 3]] ?: 64
            if (c0 == 64 || c1 == 64) throw IllegalArgumentException("bad vidnest b64 at $i")
            bytes[w++] = ((c0 shl 2) or (c1 shr 4)).toByte()
            if (c2 != 64) bytes[w++] = (((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte()
            if (c3 != 64) bytes[w++] = (((c2 and 0x03) shl 6) or c3).toByte()
            i += 4
        }
        String(bytes, 0, w, Charsets.UTF_8)
    }.getOrNull()

    /**
     * VidEm resolver (2embed.cc embed player):
     *   1. GET videm.xyz/embed/{movie|tv}/{imdb}[/s/e] — page carries a signed
     *      `Q = {..., "ssr":{"servers":[{"ref","name","lang"},...]}}` object.
     *   2. For each server ref: GET api.php?a=play&ref=...&t={Q.t} →
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
                referer = null, qualityHint = 0,
                audioPriority = if (isHindi) 4 else 1,
                audioLabel = lang,
            )
        }
        Log.d("VidEm", "got ${out.size} streams from ${servers.length()} server refs")
        return out
    }

    /** Extract a balanced {...} JSON object starting at [start] (which points
     *  at '{'). Handles nested braces and strings with escapes — enough for
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

        val (label, h) = probeAudioHeight(streamUrl, referer)
        return listOf(
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = streamUrl, isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true),
                referer = referer, qualityHint = h,
                audioLabel = label
            )
        )
    }

    /**
     * 8Stream resolver (himanshu8443/8StreamApi, IMDB-keyed). /mediaInfo
     * returns per-language playlist entries ({title:"Hindi", file, �} plus a
     * shared key); each file exchanges at POST /api/v1/getStream for a DIRECT
     * HLS master. Two calls per language, zero captcha. Movies only � the API
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
            // non-capability, not a host failure � clean miss (no breaker trip)
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
     * Vidup/Vidcore resolver - encode/decode pipeline.
     * Both players share the same HLS backend; the only
     * difference is the domain prefix and encode/decode endpoint suffix.
     *
     * Pipeline (4 chained HTTP calls, ~1.5s total):
     * 1. GET page ? regex "en":"token" from inline script
     * 2. GET enc-dec.app/api/enc-{variant}?text={token} ? {servers, stream, token}
     * 3. POST servers (X-CSRF-Token) ? encrypted sub-server JSON
     * 4. POST enc-dec.app/api/dec-{variant} ? [{name:"Euro"|"CineX"|"Zenith"|"Premier", ...}]
     * 5. For each sub-server: POST {stream}/{data} ? dec ? {url, tracks, 4kAvailable}
     *
     * Sub-server names carry quality hints: "Premier" = 4K.
     * For Hindi titles, "Original audio" means Hindi.
     */
    private suspend fun resolveEncDecPlayer(
        spec: ServerSpec,
        keyId: String?, // TMDB or IMDB id � both hosts accept either in the URL path
        type: String,
        season: Int,
        episode: Int,
        variant: String, // "vidup" or "vidcore"
    ): List<RawStream> {
        val id = keyId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val pageUrl = if (type == "movie") "${spec.referer!!.trimEnd('/')}/movie/$id"
            else "${spec.referer!!.trimEnd('/')}/tv/$id/$season/$episode"
        val referer = spec.referer!!

        // 1. Page ? token
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
        // enc-dec.app: the `token`/CSRF field is now
        // often EMPTY and the servers endpoint no longer validates it �
        // requiring a non-blank token here killed VidUp/VidCore for every
        // title. Send the header only when present.
        val csrfToken = encResult.optString("token", "")

        // 3. POST servers ? encrypted sub-server list
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

            // 5. POST stream/{data} ? final URL
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

            out += RawStream(
                serverId = spec.id,
                serverName = "${spec.name} $srvName",
                url = m3u8, isM3u8 = true, referer = referer,
                qualityHint = 0, // adaptive: emit() measures the real master height
                audioPriority = 0, // muxed audio � no EXT-X-MEDIA tracks to probe
                audioLabel = "",
            )
        }
        Log.d(variant, "got ${out.size} streams from ${srvArr.length()} sub-servers")
        return out
    }

    /**
     * Allmovieland resolver - DLE CMS with per-language HLS playlists.
     * Each language is a separate HLS stream, so true selectable multi-audio.
     * is a separate HLS stream, so true selectable multi-audio.
     *
     * Pipeline:
     * 1. IMDB-keyed DLE search ? find card URL (allmovieland.{art|one}/NNN-slug.html)
     * 2. Card page ? extract AwsIndStreamDomain (self-updating) + IndStreamPlayerConfigs.src (IMDB id)
     * 3. GET {domain}/play/{imdb} ? script with file + key
     * 4. GET {cdnDomain}/playlist/{file}.txt (X-Csrf-Token: {key}) ? JSON [{title:"Hindi", file:..., id:...}]
     * 5. Per language: GET {cdnDomain}/playlist/{lang.file}.txt ? signed m3u8 URL
     */
    /** Candidate allmovieland hosts, newest first:
     *  allmovieland.one 301-redirects to allmovieland.art and the full
     *  pipeline works on .art; .one stays as the fallback in case .art moves
     *  again. Pinned as a pure list by ServerFarmHindiTest. */
    internal fun allmovielandHosts(): List<String> =
        listOf("https://allmovieland.art", "https://allmovieland.one")

    /** A season/episode/leaf node of the playlist tree, decoded JSON-free so
     *  the strict picker is JVM-unit-testable (ARCHITECTURE rule: matching
     *  logic must be network-free). */
    internal data class AmNode(
        val title: String,
        val id: String,
        val episode: String,
        val file: String,
        val children: List<AmNode> = emptyList(),
    )

    private fun amNodeArr(arr: org.json.JSONArray): List<AmNode> =
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            AmNode(
                title = e.optString("title", ""), id = e.optString("id", ""),
                episode = e.optString("episode", ""), file = e.optString("file", ""),
                children = e.optJSONArray("folder")?.let { amNodeArr(it) }.orEmpty(),
            )
        }

    /** Parse the allmovieland playlist JSONArray into the pure node tree. */
    internal fun amParseNodes(playlist: org.json.JSONArray): List<AmNode> = amNodeArr(playlist)

    // Two card-href shapes accepted: the CURRENT live markup embeds cards as
    // <a class="new-short__title--link" href="..."> and the
    // LEGACY one as href="�" immediately followed by <h3 class="new-short__title�
    // � the old regex demanded the h3 class close right after the word and
    // broke when the site added "hover-op" (RC-A).
    private val allmovielandCardLinkRe = Regex(
        """<a\s+class="new-short__title--link"\s+href="(https?://allmovieland\.[a-z]+/[^"]+\.html)""" +
            """|href="(https?://allmovieland\.[a-z]+/[^"]+\.html)"\s*>\s*<h3 class="new-short__title""",
    )

    /** First card href out of a DLE search-results page. When [titleNorm]
     *  (normalized TMDB name) is given the card SLUG must belong to it � the
     *  IMDB-story search became unreliable (RC-C), and the title-fallback
     *  search must never grab an unrelated show. */
    internal fun allmovielandCardUrl(html: String, titleNorm: String? = null): String? =
        allmovielandCardLinkRe.findAll(html)
            .map { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
            .firstOrNull { url ->
                if (titleNorm.isNullOrBlank()) return@firstOrNull true
                // Defensive: normalize both sides (tolerates a raw title too).
                val tn = titleNorm.lowercase().replace(Regex("""[^a-z0-9]"""), "")
                val slug = Regex("""^\d+-""")
                    .replace(url.substringAfterLast('/').removeSuffix(".html"), "")
                    .lowercase().replace(Regex("""[^a-z0-9]"""), "")
                slug == tn || slug.startsWith(tn) ||
                    (tn.length >= 4 && slug.contains(tn))
            }

    /** Season container: children carry NO files (episode rows, not language
     *  leaves) and its title speaks of this season ("Season 2", "S2",
     *  "????? 2") or its id IS the season. The children-shape test is what
     *  keeps a flat per-episode tree from being mistaken for a season node. */
    internal fun amNodeIsSeason(n: AmNode, season: Int): Boolean {
        if (n.file.isNotBlank() || n.children.isEmpty()) return false
        if (n.children.any { it.file.isNotBlank() }) return false
        val seasonWord = n.title.contains("season", ignoreCase = true) ||
            n.title.contains("?????", ignoreCase = true) ||
            Regex("""(?i)\bs$season\b""").containsMatchIn(n.title)
        return (seasonWord && Regex("""\b$season\b""").containsMatchIn(n.title)) ||
            n.id == season.toString()
    }

    /** Episode title forms: "E2"/"Ep 02"/"Episode 2", "S01E02", "1x02".
     *  A BARE number is never trusted (that is how "Part 2"/"Season 2"
     *  false-matched before F3). */
    internal fun allmovielandEpisodeTitleMatches(title: String, episode: Int): Boolean {
        val e = "0*$episode"
        return Regex("""(?i)(?:^|[^a-z0-9])(?:episode|ep\.?|e\.?)[.\s_:=-]*$e(?!\d)""")
            .containsMatchIn(title) ||
            Regex("""(?i)s\d+[e]\s*$e(?![0-9])""").containsMatchIn(title) ||
            Regex("""x\s*$e(?!\d)""").containsMatchIn(title)
    }

    /** Episode container: fileless with kids, matched by the explicit
     *  `episode` field, `"<season>-<episode>"` id, or a titled episode form �
     *  never a season node relabelled. */
    internal fun amNodeIsEpisode(n: AmNode, season: Int, episode: Int): Boolean {
        if (n.file.isNotBlank() || n.children.isEmpty()) return false
        if (amNodeIsSeason(n, episode) || amNodeIsSeason(n, season)) return false
        return n.episode == episode.toString() || n.id == "$season-$episode" ||
            allmovielandEpisodeTitleMatches(n.title, episode)
    }

    /** Strict series-tree picker (RC-F3): EXACTLY the requested episode or
     *  null � there is deliberately NO `?: first()` fallback; a null makes
     *  the resolver clean-miss (server absent) instead of silently playing
     *  the WRONG episode. Handles the two-level season tree and the flat
     *  single-season shape. */
    internal fun pickAllmovielandEpisodeNode(nodes: List<AmNode>, season: Int, episode: Int): AmNode? {
        val seasonNode = nodes.firstOrNull { amNodeIsSeason(it, season) }
        if (seasonNode != null) return seasonNode.children.firstOrNull { amNodeIsEpisode(it, season, episode) }
        // Flat shape only ever covers the show's single (first) season � a
        // later-season request must NOT be served flat tree episode 1 (that is
        // the wrong-episode class again).
        if (season != 1) return null
        return nodes.firstOrNull { amNodeIsEpisode(it, season, episode) }
    }

    private suspend fun resolveAllmovieland(
        spec: ServerSpec,
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val resolveStart = System.currentTimeMillis()
        val imdb = imdbId?.takeIf { it.isNotBlank() }

        // 1. Find the card. PRIMARY: IMDB-story search across
        // [allmovielandHosts]; the IMDB-story query can answer an EMPTY shell
        // even for titles whose card exists in the library, so a no-card
        // answer falls
        // back to a TMDB-title search whose results are SLUG-VERIFIED (the
        // fallback must never grab a wrong show � RC-B wrong-title guard).
        // INTERNAL BUDGETS (fits the 60s timeoutSec kill, F8 audit): search
        // 8s/host + one 8s title fallback, card 6s, play 5s �2 candidates,
        // playlist 6s, per-language 5s CONCURRENT � worst chain � 50s.
        val meta = runCatching { TmdbService.fetchMeta(tmdbId, type) }.getOrNull()
        val titleNorm = meta?.name?.lowercase()?.replace(Regex("""[^a-z0-9]"""), "")
        var answeredHost: String? = null
        var cardUrl: String? = null
        if (imdb != null) {
            for (host in allmovielandHosts()) {
                val html = withTimeoutOrNull(8_000L) {
                    runCatching {
                        app.get("$host/?do=search&subaction=search&story=$imdb",
                            timeout = 8, headers = okHeaders("$host/")).text
                    }.getOrNull()
                }
                if (html == null) { Log.w("Allmovieland", "host $host unreachable, trying next"); continue }
                answeredHost = host
                cardUrl = allmovielandCardUrl(html, titleNorm = null)
                if (cardUrl != null) break
            }
            // Every host failed on the IMDB query: still try the title search
            // (below); if that too cannot run, it IS a network failure.
            if (cardUrl == null && titleNorm == null)
                throw IllegalStateException("allmovieland search timeout (network, all hosts)")
        }
        if (cardUrl == null && titleNorm != null) {
            val host = answeredHost ?: allmovielandHosts().first()
            val story = java.net.URLEncoder.encode(meta!!.name, "UTF-8")
            val html = withTimeoutOrNull(8_000L) {
                runCatching {
                    app.get("$host/?do=search&subaction=search&story=$story",
                        timeout = 8, headers = okHeaders("$host/")).text
                }.getOrNull()
            } ?: run {
                if (answeredHost == null) throw IllegalStateException("allmovieland search timeout (network, all hosts)")
                null
            }
            cardUrl = html?.let { allmovielandCardUrl(it, titleNorm) }
        }
        cardUrl ?: run {
            if (imdb == null && meta == null)
                throw CleanMissException("no IMDB id and no TMDB title available")
            throw CleanMissException("no card for imdb=${imdb ?: "-"}/'${meta?.name ?: "?"}' (title not in library)")
        }
        Log.i("Allmovieland", "card=${cardUrl.substringAfterLast('/')} for imdb=${imdb ?: "-"} s=$season e=$episode")

        // 2. Card page gives the player domain + ALL player-config src's: a
        // card can embed MULTIPLE player srcs and the first one can point at
        // a foreign/missing entry, whose player answers "ERROR. Video Not
        // Found" literally. Trusting src[0] was the wrong-title mechanism.
        // src[0] was the wrong-title mechanism. Now: the candidate equal to
        // this title's IMDB goes first, and step 3 walks up to two.
        val cardHtml = withTimeoutOrNull(6_000L) {
            runCatching { app.get(cardUrl, timeout = 6, headers = okHeaders(cardUrl)).text }.getOrNull()
        } ?: throw IllegalStateException("allmovieland card fetch failed (network)")
        val playerDomain = Regex("""AwsIndStreamDomain\s*=\s*'([^']+)'""")
            .find(cardHtml)?.groupValues?.get(1)?.trimEnd('/')
            ?: run { Log.w("Allmovieland", "no AwsIndStreamDomain"); throw IllegalStateException("allmovieland parse: no AwsIndStreamDomain") }
        val srcCandidates = Regex("""src:\s*'([^']+)'""").findAll(cardHtml)
            .map { it.groupValues[1] }.filter { it.isNotBlank() }.distinct().toList()
        if (srcCandidates.isEmpty()) {
            Log.w("Allmovieland", "no src in player config"); throw IllegalStateException("allmovieland parse: no src")
        }
        val orderedSrcs = (if (imdb != null) srcCandidates.filter { it.contains(imdb, ignoreCase = true) } else emptyList()) +
            srcCandidates.filterNot { imdb != null && it.contains(imdb, ignoreCase = true) }

        // 3. Play page ? file + key, walking up to TWO src candidates
        // (5s each). "Video Not Found" / no-file pages move to the next
        // candidate; ALL candidates failing is a LIBRARY problem ? clean
        // miss, NOT a breaker strike (the old hard IllegalStateException
        // here silently removed Allmovieland for whole trip windows �
        // the "sometimes doesn't work" report). The page serves the file
        // URL ESCAPED ("https:\/\/cdn...") � unescape before use.
        var chosenPlay: String? = null
        var playFile: String? = null
        var playKey: String? = null
        for (cand in orderedSrcs.take(2)) {
            if (System.currentTimeMillis() - resolveStart > (spec.timeoutSec - 24) * 1000L) {
                Log.w("Allmovieland", "budget guard: skipping remaining player srcs")
                break
            }
            val pUrl = "$playerDomain/play/$cand"
            val playHtml = withTimeoutOrNull(5_000L) {
                runCatching { app.get(pUrl, timeout = 5, headers = okHeaders(cardUrl)).text }.getOrNull()
            }
            if (playHtml.isNullOrBlank()) { Log.w("Allmovieland", "player src $cand: no answer"); continue }
            if (playHtml.contains("video not found", ignoreCase = true)) {
                Log.w("Allmovieland", "player src $cand: Video Not Found"); continue
            }
            val f = Regex("""["']?file["']?\s*[:=]\s*["']([^"']+)["']""")
                .find(playHtml)?.groupValues?.get(1)?.replace("\\/", "/")
            val k = Regex("""["']?key["']?\s*[:=]\s*["']([^"']+)["']""").find(playHtml)?.groupValues?.get(1)
            if (f.isNullOrBlank() || k.isNullOrBlank()) {
                Log.w("Allmovieland", "player src $cand: no file/key (${playHtml.length}B, ${safeSnippet(playHtml)})"); continue
            }
            chosenPlay = pUrl; playFile = f; playKey = k; break
        }
        val playUrl = chosenPlay
            ?: throw CleanMissException("no playable entry behind ${orderedSrcs.take(2).size} card player src(s)")
        val file = playFile!!
        val key = playKey!!

        // file may be: an absolute URL (movies) OR a path that already starts with "/playlist/" (series) � the naive.
// "$playerDomain/playlist/$file" doubled.
        val fileUrl = when {
            file.startsWith("http") -> file
            file.startsWith("/playlist/") -> "$playerDomain$file"
            else -> "$playerDomain/playlist/$file"
        }
        val cdnBase = fileUrl.substringBefore("/playlist/")

        // 4. Language playlist (file = encrypted path like "B64hash. txt"). 6s ceiling (budget spec � see search step above).
        val playlistHeaders = okHeaders(playUrl).toMutableMap()
        playlistHeaders["X-Csrf-Token"] = key
        val playlistText = withTimeoutOrNull(6_000L) {
            runCatching { app.get(fileUrl, timeout = 6, headers = playlistHeaders).text }.getOrNull()
        } ?: run { Log.w("Allmovieland", "playlist timeout"); throw IllegalStateException("allmovieland playlist timeout (network)") }
        val playlist = runCatching { org.json.JSONArray(playlistText) }.getOrElse {
            Log.w("Allmovieland", "playlist not JSON array"); throw IllegalStateException("allmovieland playlist parse error") }

        // Series shape (re-, ): top entries are SEASON containers ("Season 1") whose folder holds EPISODE rows whose folder.
// holds per-language leaves {file.
        val nodes = amParseNodes(playlist)
        data class LangLeaf(val lang: String, val file: String)
        fun leavesOf(node: AmNode): List<LangLeaf> = node.children.flatMap {
            if (it.file.isNotBlank()) listOf(LangLeaf(it.title.ifBlank { "Multi" }, it.file))
            else leavesOf(it)
        }
        val langEntries: List<LangLeaf> = if (type == "movie") {
            nodes.filter { it.file.isNotBlank() }.map { LangLeaf(it.title.ifBlank { "Multi" }, it.file) }
        } else {
            if (season <= 0 || episode <= 0)
                throw CleanMissException("tv request without season/episode (s=$season e=$episode)")
            val epNode = pickAllmovielandEpisodeNode(nodes, season, episode)
            if (epNode == null) {
                Log.w("Allmovieland", "series tree has no EXACT S$season E$episode � shape: " +
                    nodes.joinToString { "'${it.title}'(id=${it.id},ep=${it.episode},kids=${it.children.size})" }
                        .take(400))
                throw CleanMissException("no exact S$season E$episode in series tree (wrong-episode guard)")
            }
            val leaves = leavesOf(epNode)
            if (leaves.isEmpty()) {
                Log.w("Allmovieland", "S$season E$episode node carries no language files")
                throw CleanMissException("empty episode node for S$season E$episode")
            }
            leaves
        }

        // 5. Per-language: fetch each language's playlist? m3u8 URL.
        val out = mutableListOf<RawStream>()
        val fetched = coroutineScope {
            langEntries.map { leaf ->
                async {
                    val langFile = leaf.file.replace("\\/", "/")
                    val langUrl = if (langFile.startsWith("http")) langFile
                    else "$cdnBase/playlist/$langFile.txt"
                    val m3u8Text = withTimeoutOrNull(5_000L) {
                        runCatching { app.get(langUrl, timeout = 5, headers = playlistHeaders).text }.getOrNull()
                    }
                    leaf to m3u8Text?.trim()?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
                }
            }.awaitAll()
        }
        for ((leaf, m3u8) in fetched) {
            if (m3u8 == null) continue
            val langTitle = leaf.lang
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
        if (out.isEmpty()) {
            // Every language playlist timed out / answered no URL: the node matched, the content just did not answer this tap.
// Clean miss (kept retryable).
            throw CleanMissException("${langEntries.size} language playlists, none yielded an m3u8")
        }
        Log.d("Allmovieland", "got ${out.size} language streams from ${langEntries.size} entries " +
            "(series tree: ${type != "movie"})")
        return out
    }

    /** JSON API resolver (api. shows. st / 111Movies shape): `{"source": {"url": . . . , "qualities": }, "subtitles": }`. The signed stream URLs carry no. */
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
        // (root. subtitles is not collected � SubtilesProvider is the only subtitle provider, . ).

        val out = mutableListOf<RawStream>()

        // Adaptive master (source. url). source. manifest carries the FULL HLS master playlist inline (variant URIs are.
// absolute https URLs) � the signed.
        val masterUrl = source.optString("url").takeIf { it.isNotBlank() }
        val inlineManifest = source.optString("manifest").takeIf { it.isNotBlank() && it.contains("#EXT-X-STREAM-INF") }
        if (masterUrl != null || inlineManifest != null) {
            val url = masterUrl ?: ""
            val isHls = inlineManifest != null || url.contains(".m3u8", ignoreCase = true)
            val (label, height) = when {
                inlineManifest != null -> probeAudioInlineHeight(inlineManifest)
                isHls && url.isNotBlank() -> probeAudioHeight(url, referer)
                else -> "" to 0
            }
            val probed = if (url.isNotBlank()) HttpKit.probeSpeed(url, referer) else null
            out.add(RawStream(
                serverId = spec.id, serverName = spec.name,
                url = url, isM3u8 = isHls, referer = referer,
                qualityHint = height, measuredKbps = probed,
                audioLabel = label, inlineManifest = inlineManifest,
            ))
        }

        // Per-quality MP4s (source. qualities) � direct VIDEO links.
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
                    qualityHint = height, measuredKbps = probed,
                    audioPriority = 0, audioLabel = "",
                )
            }.let { out.addAll(it) }
        }

        if (out.isEmpty()) {
            Log.w("IndStream", "${spec.id}: source present but carried no url/manifest/qualities; source keys=${namesOf(source)}")
        }
        return out
    }

    /** Follow iframes to the deepest player page. Returns the deepest HTML and its final URL so can also pass the inner URL. to � many embed chains. */
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

    /** Harvest bare m3u8/mp4/webm URLs. */
    private fun harvestUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/").replace("\\\"", "\"")
        return STREAM_REGEX.flatMap { r -> r.findAll(n).map { it.groupValues[0].trim('"', '\'') }.filter { it.startsWith("http") } }.distinct()
    }

    /** Extract. . . " / sources: / url: ". . . */
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

    /** Pull stream URL. */
    private fun harvestVideoSource(text: String, baseUrl: String): String? {
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")?.attr("src")?.trim() ?: return null
        return relUrl(baseUrl, src).takeIf { it.startsWith("http") }
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

    /** JSON key listing for failure logs (shape changes are one log line away). Keys are server-controlled: strip control. chars (log-forgery) and cap length. */
    private fun namesOf(obj: org.json.JSONObject): String =
        obj.names()?.let { n ->
            (0 until n.length()).joinToString(",") { key -> sanitizeToken(n.optString(key)) }
        } ?: "none"

    /** Short, control-char-free prefix of a remote body for parse-failure hints -- long enough to recognise the shape, too. short to carry a signed URL. */
    private fun safeSnippet(text: String): String = sanitizeToken(text).take(40)

    private fun sanitizeToken(s: String): String = s.filterNot { it.isISOControl() }.take(64)

    /** Last farm-wide re-probe; cooldown keeps a dead uplink) on every single playback tap. */
    @Volatile
    private var lastFarmProbeAt = 0L
    private const val FARM_REPROBE_COOLDOWN_MS = 15_000L

    /** Per-title cache of resolved streams. Two jobs: 1. */
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

        /** Age of the current entry in ms (-1 = nothing cached). Replay, and the age is what tells "instant replay of fresh. links" apart. */
        fun ageMs(key: String): Long {
            val e = map[key] ?: return -1L
            return (System.currentTimeMillis() - (e.expiresAt - TTL_MS)).coerceAtLeast(0)
        }

        /** Merge for into the existing set (dedup by url) and extend TTL. */
        fun put(key: String, streams: List<RawStream>) {
            if (streams.isEmpty()) return
            val existing = get(key).orEmpty()
            val merged = (existing + streams).distinctBy { it.url }
            map[key] = Entry(merged, System.currentTimeMillis() + TTL_MS)
        }

        fun clear() = map.clear()
    }

    /** Hard cap on how long waits for the FIRST server batch before giving up. Safety net so a totally dead farm still. surfaces "no link found" instead. */
    const val FAST_START_MAX_MS: Long = 45_000L

    /** Live fill window (): loadLinks STAYS ALIVE for at most this long, pushing every server that answers into the live. change-server list � the list. */
    const val LIVE_FILL_MS: Long = 90_000L
}





