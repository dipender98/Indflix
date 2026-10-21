package com.indstream


import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

object StreamEngine {

    private const val MAX_CONCURRENT = 32
    private const val MAX_UNWRAP = 4
    private const val ANIMATION_GENRE_ID = 16

    @Volatile private var movieBoxToken: String? = null
    @Volatile private var movieBoxTokenAt: Long = 0L
    private const val MOVIEBOX_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MOVIEBOX_THROTTLE_RETRY_MS = 2_000L
    private val STREAM_REGEX = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
    )

    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

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
        val sizeLabel: String = "",
        val mediaLabel: String = "",
        val inlineManifest: String? = null,
        val extraHeaders: Map<String, String> = emptyMap(),
        val isDash: Boolean = false,
    )

    private data class SubjectRef(
        val id: String,
        val seasonEnd: Int,
        val language: String?,
        val detailPath: String,
    )

    class CleanMissException(message: String) : Exception(message)

    private data class ResolveAttempt(
        val streams: List<RawStream>,
        val crash: Throwable? = null,
        val cleanMiss: Boolean = false,
    )

    private fun selectServers(tmdbId: Int, type: String, season: Int, episode: Int): List<ServerSpec> {
        val enabled = ServerFarm.allServers.filter { Settings.serverEnabled(it.id) }
        val healthy = enabled.filter { HealthMonitor.isHealthy(it.id) }
        Log.d("IndStream", "healthy: ${healthy.size}/$enabled enabled")
        val candidates = if (healthy.isEmpty()) {
            val now = System.currentTimeMillis()
            val last = lastFarmProbeAt.get()
            val claimed = now - last >= FARM_REPROBE_COOLDOWN_MS && lastFarmProbeAt.compareAndSet(last, now)
            if (!claimed) {
                Log.w("IndStream", "all enabled servers tripped; re-probe cooldown active " +
                    "(${(FARM_REPROBE_COOLDOWN_MS - (now - last)) / 1000}s left)")
                emptyList()
            } else {
                Log.w("IndStream", "all ${enabled.size} enabled servers tripped -- clearing trips, re-probing enabled")
                HealthMonitor.resetTrips()
                enabled
            }
        } else healthy
        val servers = candidates
        Log.d("IndStream", "selectServers tmdb=$tmdbId type=$type s=$season e=$episode -> ${servers.size} servers (neutral order)")
        return servers
    }

    suspend fun resolveRealtime(
        tmdbId: Int, type: String, season: Int = -1, episode: Int = -1,
        imdbId: String? = null,
        imdbIdProvider: (suspend () -> String?)? = null,
        tmdbOnly: Boolean = false,
        onBatch: suspend (serverId: String, streams: List<RawStream>) -> Unit,
    ) {
        if (tmdbId <= 0 && (imdbId == null || !imdbId.startsWith("tt")) && imdbIdProvider == null) {
            Log.w("IndStream", "resolveRealtime skipped: no usable id (tmdbId=$tmdbId)")
            return
        }
        val imdbOnly = tmdbId <= 0
        val servers = selectServers(tmdbId, type, season, episode).filter { spec ->
            when {
                tmdbOnly -> spec.idType != ServerIdType.IMDB
                !imdbOnly -> true
                else -> spec.idType == ServerIdType.IMDB || spec.supportsImdb(type)
            }
        }
        if (servers.isEmpty()) return
        val sem = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            servers.map { spec ->
                async {
                    sem.acquire()
                    try {
                        val serverStart = System.currentTimeMillis()
                        val dual = spec.supportsImdb(type)
                        val raceSpec = if (dual) spec.copy(recordHealth = false) else spec
                        val serverImdb = when {
                            spec.idType == ServerIdType.IMDB -> imdbId ?: imdbIdProvider?.invoke()
                            dual && imdbOnly -> imdbId ?: imdbIdProvider?.invoke()
                            else -> imdbId
                        }
                        if (spec.idType == ServerIdType.IMDB || (dual && imdbOnly)) {
                            Log.i("IndStream", "${spec.id}: imdb=${serverImdb ?: "MISSING"} after " +
                                "${System.currentTimeMillis() - serverStart}ms wait")
                        }
                        var crashed: Throwable? = null
                        var cleanMiss = false
                        val outcome = withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            suspend fun attempt(keyType: ServerIdType, keyId: String?): ResolveAttempt {
                                if (keyType == ServerIdType.TMDB && tmdbId <= 0)
                                    return ResolveAttempt(emptyList(), cleanMiss = true)
                                if (keyType == ServerIdType.IMDB && (keyId == null || !keyId.startsWith("tt")))
                                    return ResolveAttempt(emptyList(), cleanMiss = true)
                                return try {
                                    ResolveAttempt(resolveOne(raceSpec, tmdbId, keyId, type, season, episode, keyType))
                                } catch (t: CleanMissException) {
                                    Log.i("IndStream", "${spec.id}: clean miss � ${t.message} (no breaker trip)")
                                    ResolveAttempt(emptyList(), cleanMiss = true)
                                } catch (c: kotlinx.coroutines.CancellationException) {
                                    throw c
                                } catch (t: Throwable) {
                                    Log.w("IndStream", "${spec.id}: resolver crashed: " +
                                        "${t.javaClass.simpleName}: ${t.message}")
                                    ResolveAttempt(emptyList(), t)
                                }
                            }
                            val primaryType = if (tmdbId > 0) ServerIdType.TMDB else ServerIdType.IMDB
                            val primary = async { attempt(primaryType, serverImdb) }
                            val alt = if (dual && tmdbId > 0) {
                                async {
                                    val altId = catching { imdbId ?: imdbIdProvider?.invoke() }.getOrNull()
                                    attempt(ServerIdType.IMDB, altId)
                                }
                            } else null
                            val won = if (alt == null) {
                                primary.await()
                            } else {
                                val altJob = alt
                                var result: ResolveAttempt? = null
                                select<Unit> {
                                    primary.onAwait { p ->
                                        if (p.streams.isNotEmpty()) result = p
                                        else {
                                            val a = altJob.await()
                                            result = if (a.streams.isNotEmpty()) a
                                            else if (p.crash != null) p
                                            else if (!p.cleanMiss) p else a
                                        }
                                    }
                                    altJob.onAwait { a ->
                                        if (a.streams.isNotEmpty()) result = a
                                        else {
                                            val p = primary.await()
                                            result = if (p.streams.isNotEmpty()) p
                                            else if (p.crash != null) p
                                            else if (!p.cleanMiss) p else a
                                        }
                                    }
                                }
                                primary.cancel()
                                altJob.cancel()
                                result ?: ResolveAttempt(emptyList())
                            }
                            crashed = won.crash
                            cleanMiss = won.cleanMiss
                            won.streams
                        }
                        if (outcome == null || crashed != null) {
                            val ms = System.currentTimeMillis() - serverStart
                            val crash = crashed
                            if (crash != null) {
                                Log.w("IndStream", "${spec.id}: crashed after ${ms}ms: " +
                                    "${crash.javaClass.simpleName}: ${crash.message} (recording failure)")
                                HealthMonitor.recordFailure(spec.id)
                            } else {
                                Log.w("IndStream", "${spec.id}: TIMEOUT � no result after ${spec.timeoutSec}s, recording failure")
                                HealthMonitor.recordFailure(spec.id)
                            }
                        }
                        val streams = outcome?.map { s ->
                            if (s.audioLabel.isBlank() && spec.declaredLanguages.size == 1)
                                s.copy(audioLabel = spec.declaredLanguages.first())
                            else s
                        }.orEmpty()
                        if (dual && streams.isEmpty() && crashed == null && !cleanMiss) {
                            Log.w("IndStream", "${spec.id}: no streams (recording failure)")
                            HealthMonitor.recordFailure(spec.id)
                        }
                        if (streams.isNotEmpty()) {
                            if (dual) HealthMonitor.recordSuccess(spec.id)
                            Log.i("IndStream", "${spec.id}: LANDED ${streams.size} streams at " +
                                "${System.currentTimeMillis() - serverStart}ms")
                            onBatch(spec.id, streams)
                        }
                    } finally { sem.release() }
                }
            }.awaitAll()
        }
    }

    suspend fun emit(
        streams: List<RawStream>,
        onLink: (ExtractorLink) -> Unit,
        originalLang: String? = null,
        probeManifests: Boolean = true,
    ): Set<String> {
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())
        if (streams.isEmpty()) return emitted

        var liveSet = streams
        if (!probeManifests) {
            val dead = withTimeoutOrNull(3000L) {
                coroutineScope {
                    val gate = Semaphore(6)
                    streams.filter { it.url.isNotBlank() && it.inlineManifest.isNullOrBlank() }
                        .map { raw ->
                            async {
                                gate.acquire()
                                try {
                                    HttpKit.aliveCheck(raw.url, raw.referer, raw.extraHeaders)
                                        .takeIf { it == false }?.let { raw.url }
                                } finally {
                                    gate.release()
                                }
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

        val ranked = liveSet.filter { it.url.isNotBlank() }

        data class ResolvedEmit(
            val raw: RawStream,
            val fullHeight: Int,
            val tagLabel: String,
            val fetchMs: Long? = null,
        )
        val preResolved = coroutineScope {
            ranked.map { raw ->
                async {
                    when {
                        raw.isM3u8 && probeManifests -> {
                            val alreadyKnown = raw.qualityHint > 0
                            if (alreadyKnown) {
                                ResolvedEmit(raw, raw.qualityHint, raw.audioLabel)
                            } else {
                                val lh = LinkedHashMap<String, String>()
                                lh.putAll(raw.extraHeaders)
                                if (!lh.containsKey("Referer") && !raw.referer.isNullOrBlank()) lh["Referer"] = raw.referer!!
                                val t0 = System.currentTimeMillis()
                                val masterText = raw.inlineManifest ?: withTimeoutOrNull(3000L) {
                                    catching { app.get(raw.url, timeout = 3, headers = lh).text }.getOrNull()
                                }
                                val fetchMs = if (raw.inlineManifest != null) 0
                                else masterText?.let { System.currentTimeMillis() - t0 }
                                val h = (ManifestKit.bestHeightOf(masterText, raw.url).takeIf { it > 0 } ?: raw.qualityHint).coerceAtLeast(0)
                                val master = ManifestKit.parseMaster(masterText, raw.url)
                                val tag = (if (raw.audioLabel.isNotBlank()) raw.audioLabel
                                else if (master != null && master.isMultiAudio) "Multi"
                                else if (master != null) ManifestKit.audioLanguageLabel(master).orEmpty()
                                else raw.audioLabel)
                                    .ifBlank { declaredLanguageHint(raw) ?: "" }
                                ResolvedEmit(raw, h, tag, fetchMs)
                            }
                        }
                        !raw.isM3u8 && probeManifests -> {
                            val t0 = System.currentTimeMillis()
                            val measured = HttpKit.resolveHeight(raw.url, raw.referer, raw.extraHeaders)
                            val fetchMs = (System.currentTimeMillis() - t0).takeIf { measured > 0 }
                            val h = maxOf(measured, raw.qualityHint).takeIf { it > 0 }
                                ?: run {
                                val fromUrl = ManifestKit.resolutionFromUrl(raw.url)
                                if (fromUrl > 0) fromUrl else -1
                            }
                            val tag = raw.audioLabel.ifBlank { declaredLanguageHint(raw) ?: "" }
                            ResolvedEmit(raw, h, tag, fetchMs)
                        }
                        else -> {
                            var fullHeight = if (!raw.isM3u8 && raw.qualityHint <= 0) -1 else raw.qualityHint
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
        val ordered = preResolved.sortedBy { speedRankMs(it.fetchMs, it.tagLabel) }
        val resolvedForKey = ordered.map { it.raw.copy(qualityHint = it.fullHeight) }
        val numbers = LinkNaming.dedupeNames(resolvedForKey, originalLang)

        val floorDroppedByServer = HashMap<String, Int>()
        val emittedByServer = HashMap<String, Int>()

        ordered.forEachIndexed { index, r ->
            val raw = r.raw
            val dupIdx = numbers.getOrElse(index) { 0 }
            if (!emitted.add(raw.url)) return@forEachIndexed

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
            val quality = r.fullHeight.coerceAtLeast(0)
            val label = LinkNaming.displayName(
                serverName = raw.serverName,
                audioLabel = r.tagLabel,
                qualityHint = r.fullHeight,
                duplicateIndex = dupIdx,
                originalLang = originalLang,
                sizeLabel = raw.sizeLabel.takeIf { it.isNotBlank() },
                mediaLabel = raw.mediaLabel.takeIf { it.isNotBlank() },
            )

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
        if (height <= 0) return true
        return height >= 720
    }


    internal suspend fun resolveOne(
        spec: ServerSpec,
        tmdbId: Int,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
        keyType: ServerIdType = spec.idType,
    ): List<RawStream> {
        val start = System.currentTimeMillis()
        val id = if (keyType == ServerIdType.IMDB)
            (imdbId ?: throw CleanMissException("${spec.id}: IMDB id unavailable (TMDB lookup miss) � host stays in farm"))
        else tmdbId.toString()
        val embedUrl = if (type == "movie") ServerFarm.buildMovieUrl(spec, id)
        else ServerFarm.buildTvUrl(spec, id, season, episode)
        val referer = spec.referer ?: embedUrl.substringBefore("?")

        if (spec.animeOnly && !isAnimationTitle(tmdbId, type)) {
            failServer(spec, "not an animation title (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vidlink") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveVidlink(spec, id, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidlink api", result.size); return result }
            failServer(spec, "vidlink returned no streams")
            return emptyList()
        }
        if (spec.id == "moviebox") {
            val result = resolveMovieBox(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "moviebox", result.size); return result }
            failServer(spec, "moviebox returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vaplayer") {
            val result = resolveVaplayer(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vaplayer", result.size); return result }
            failServer(spec, "vaplayer returned no streams")
            return emptyList()
        }
        if (spec.id == "onetouchtv") {
            val result = resolveOneTouchTv(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "onetouchtv api", result.size); return result }
            failServer(spec, "onetouchtv returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
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
        if (spec.id == "allmovieland") {
            val result = resolveAllmovieland(spec, tmdbId, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "allmovieland multi-lang", result.size); return result }
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
            val result = resolveStreamFlix(spec, id, type, season, episode)
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
        if (spec.id == "fsl") {
            val result = resolveFsl(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "fast file links", result.size); return result }
            failServer(spec, "fast file family returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "hindmoviez") {
            val result = resolveLinkIndex(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "link index files", result.size); return result }
            failServer(spec, "link index returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "hdhub4u") {
            val result = resolveRotatorFiles(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "rotator archive files", result.size); return result }
            failServer(spec, "rotator archive returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "moviesdrive") {
            val result = resolveRecoverFiles(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "recover search files", result.size); return result }
            failServer(spec, "recover search returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "dudefilms") {
            val result = resolveArchiveFiles(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "archive gateway files", result.size); return result }
            failServer(spec, "archive gateway returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "vidcore-api") {
            val result = resolveVidcore(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidcore api", result.size); return result }
            failServer(spec, "vidcore api returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "bingr") {
            val result = resolveBingr(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "bingr cascade", result.size); return result }
            failServer(spec, "bingr returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "vidrift") {
            val result = resolveVidrift(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidrift embed", result.size); return result }
            failServer(spec, "vidrift returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "rivestream") {
            val result = resolveRivestream(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "rivestream api", result.size); return result }
            failServer(spec, "rivestream returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "movy") {
            val result = resolveMovy(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "movy api", result.size); return result }
            failServer(spec, "movy returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "braflix") {
            val result = resolveBraflix(spec, id, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "braflix api", result.size); return result }
            failServer(spec, "braflix returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "animeworld") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveAnimeWorld(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "animeworld multi-audio hls", result.size); return result }
            failServer(spec, "animeworld returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }
        if (spec.id == "hianime") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveHiAnime(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "hianime player config", result.size); return result }
            failServer(spec, "hianime returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.isJsonApi) {
            val result = resolveJsonApi(spec, embedUrl, referer)
            if (result.isNotEmpty()) { okServer(spec, start, "json api", result.size); return result }
            failServer(spec, "json api returned no source")
            return emptyList()
        }

        val rawText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            catching {
                app.get(embedUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (rawText.isNullOrBlank()) { failServer(spec, "embed fetch blank/timeout: $embedUrl"); return emptyList() }
        Log.d("IndStream", "${spec.id}: embed fetched, ${rawText.length}B")

        val (unwrapped, unwrappedUrl) = unwrapPages(rawText, embedUrl, spec.timeoutSec)
        if (unwrapped !== rawText) Log.d("IndStream", "${spec.id}: unwrapped to $unwrappedUrl, ${unwrapped.length}B")

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

        val regLinks = mutableListOf<ExtractorLink>()
        val regOk = catching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        if (unwrappedUrl != embedUrl) {
            catching {
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

        failServer(spec, "no harvestable stream across full pipeline")
        return emptyList()
    }

    private fun failServer(spec: ServerSpec, reason: String, isCleanMiss: Boolean = false) {
        if (isCleanMiss) {
            Log.i("IndStream", "${spec.id}: clean miss � $reason (no breaker trip)")
        } else {
            Log.w("IndStream", "${spec.id}: $reason")
            if (spec.recordHealth) HealthMonitor.recordFailure(spec.id)
        }
    }

    private fun okServer(spec: ServerSpec, start: Long, stage: String, streamCount: Int) {
        val ms = System.currentTimeMillis() - start
        Log.d("IndStream", "${spec.id}: OK via $stage, $streamCount streams in ${ms}ms")
        if (spec.recordHealth) HealthMonitor.recordSuccess(spec.id)
    }

    private fun declaredLanguageHint(raw: RawStream): String? =
        ManifestKit.languageFromName(raw.serverName, raw.url)

    private suspend fun probeAudioHeight(url: String, referer: String?): Pair<String, Int> {
        val isAdaptive = url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)
        if (!isAdaptive) return "" to 0
        val text = withTimeoutOrNull(3000L) {
            catching { app.get(url, timeout = 3, headers = mapOf("Referer" to (referer ?: ""))).text }.getOrNull()
        } ?: return "" to 0
        val height = ManifestKit.bestHeightOf(text, url)
        val master = ManifestKit.parseMaster(text, url)
        val label = master?.let { ManifestKit.audioLanguageLabel(it) }.orEmpty()
        val langs = master?.audio?.map { r -> "lang=${r.language ?: "none"} name=${r.name}" }.orEmpty()
        Log.d("IndStream", "probeAudioHeight url=${url.take(80)} label=$label height=$height renditions=$langs")
        return label to height
    }

    private fun probeAudioInlineHeight(manifestText: String?): Pair<String, Int> {
        if (manifestText.isNullOrBlank()) return "" to 0
        val master = ManifestKit.parseMaster(manifestText) ?: return "" to 0
        return ManifestKit.audioLanguageLabel(master).orEmpty() to ManifestKit.bestHeight(master.variants)
    }

    private const val VIDLINK_KEY_HEX =
        "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"
    private val VIDLINK_NONCE = ByteArray(24)
    private const val VIDLINK_TIME_OFFSET_S = 480L
    internal val VIDLINK_TOKEN_OFFSETS = listOf(VIDLINK_TIME_OFFSET_S, 0L, 960L)
    internal val VIDLINK_PLAYER_HEADERS = mapOf("User-Agent" to "ExoPlayer")

    internal fun vidlinkPlaybackHeaders(quality: org.json.JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>(VIDLINK_PLAYER_HEADERS)
        quality.optJSONObject("headers")?.let { h ->
            h.optString("referer").takeIf { it.isNotBlank() }?.let { out["Referer"] = it }
            h.optString("origin").takeIf { it.isNotBlank() }?.let { out["Origin"] = it }
        }
        return out
    }

    internal fun vidlinkToken(mediaId: String, timestampSeconds: Long): String {
        val key = hexToBytes(VIDLINK_KEY_HEX)
        val idBytes = mediaId.toByteArray(Charsets.UTF_8)
        val message = ByteArray(idBytes.size + 8)
        idBytes.copyInto(message, 0)
        for (i in 0 until 8) {
            message[idBytes.size + 7 - i] = ((timestampSeconds ushr (i * 8)) and 0xFF).toByte()
        }
        val box = CryptoKit.secretbox(message, VIDLINK_NONCE, key)
        val payload = ByteArray(24 + box.size)
        VIDLINK_NONCE.copyInto(payload, 0)
        box.copyInto(payload, 24)
        return base64Url(payload)
    }

    internal fun vidlinkTokenWithOffset(mediaId: String, offsetSec: Long): String =
        vidlinkToken(mediaId, System.currentTimeMillis() / 1000L + offsetSec)

    internal fun vidlinkMovieApiUrlAt(tmdbId: String, offsetSec: Long): String =
        "https://vidlink.pro/api/b/movie/${vidlinkTokenWithOffset(tmdbId, offsetSec)}?multiLang=1"

    internal fun vidlinkTvApiUrlAt(tmdbId: String, season: Int, episode: Int, offsetSec: Long): String =
        "https://vidlink.pro/api/b/tv/${vidlinkTokenWithOffset(tmdbId, offsetSec)}/$season/$episode?multiLang=1"

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xFF else 0
            out.append(BASE64_URL_ALPHABET[b0 ushr 2])
            out.append(BASE64_URL_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (!has1) break
            out.append(BASE64_URL_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (!has2) break
            out.append(BASE64_URL_ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }

    private fun vidlinkHeaders(mediaPageUrl: String): Map<String, String> = mapOf(
        "User-Agent" to HttpKit.userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://vidlink.pro",
        "Referer" to mediaPageUrl,
    )

    private suspend fun resolveVidlink(
        spec: ServerSpec,
        id: String,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val mediaPage = if (type == "movie") "https://vidlink.pro/movie/$id"
        else "https://vidlink.pro/tv/$id/$season/$episode"
        Log.d("VidLink", "mediaPage=$mediaPage")

        var root: org.json.JSONObject? = null
        for ((attempt, offset) in VIDLINK_TOKEN_OFFSETS.withIndex()) {
            val apiUrl = if (type == "movie") vidlinkMovieApiUrlAt(id, offset)
            else vidlinkTvApiUrlAt(id, season, episode, offset)
            val jsonText = withTimeoutOrNull(6_000L) {
                catching {
                    app.get(apiUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
                }.getOrNull()
            }
            if (jsonText.isNullOrBlank()) {
                Log.w("VidLink", "no API response (timeout/HTTP error) for $mediaPage")
                return emptyList()
            }
            if (jsonText.trim() == "null") {
                throw CleanMissException("vidlink: api returned null body (no multiLang source) for $mediaPage")
            }
            Log.d("VidLink", "attempt ${attempt + 1} offset +${offset}s: ${jsonText.length}B preview=${safeSnippet(jsonText)}")
            val parsed = catching { org.json.JSONObject(jsonText) }.getOrElse {
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
            if (code == 2004 && attempt < VIDLINK_TOKEN_OFFSETS.lastIndex) {
                Log.w("VidLink", "2004 at offset +${offset}s — retrying with next offset")
                continue
            }
            if (code == 2004) {
                Log.e("VidLink", "CRITICAL: code 2004 — encryption key rotated! Update VIDLINK_KEY_HEX immediately. " +
                    "Extract the new key from https://vidlink.pro player JS bundles (look for 64-char hex near secretbox/sodium). " +
                    "Current key: ${VIDLINK_KEY_HEX.take(8)}...${VIDLINK_KEY_HEX.takeLast(8)}")
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

        val qualities = stream.optJSONObject("qualities")
        if (qualities != null && qualities.length() > 0) {
            val entries = qualities.names()?.let { n ->
                (0 until n.length()).mapNotNull { i ->
                    val key = n.optString(i)
                    val height = key.toIntOrNull() ?: 0
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
                        extraHeaders = vidlinkPlaybackHeaders(q),
                    )
                }
            }
            Log.w("VidLink", "qualities present but no usable urls (keys=${namesOf(qualities)})")
        }

        val masterUrl = stream.optString("playlist").takeIf { it.isNotBlank() }
            ?: resolved.optString("url").takeIf { it.isNotBlank() }
            ?: run {
                Log.w("VidLink", "no qualities/playlist/url; stream keys=${namesOf(stream)} root keys=${namesOf(resolved)}")
                return emptyList()
            }

        val masterText = withTimeoutOrNull(6_000L) {
            catching {
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
                extraHeaders = VIDLINK_PLAYER_HEADERS,
            )
        )
    }

    private suspend fun fetchMovieBoxBearer(forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            movieBoxToken?.takeIf {
                System.currentTimeMillis() - movieBoxTokenAt < MOVIEBOX_TOKEN_TTL_MS
            }?.let { return it }
        }
        val base = "https://h5-api.aoneroom.com"
        val xUser = withTimeoutOrNull(8_000L) {
            catching {
                app.get("$base/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
                    timeout = 8)
            }.getOrNull()
        }?.headers?.get("x-user") ?: run { Log.w("MovieBox", "no x-user header"); return null }
        val t = catching { org.json.JSONObject(xUser).optString("token", "") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("MovieBox", "no token in x-user"); return null }
        movieBoxToken = t
        movieBoxTokenAt = System.currentTimeMillis()
        return t
    }

    suspend fun prewarmMovieBoxToken(): String? = fetchMovieBoxBearer(forceRefresh = false)

    internal fun movieboxAuthRejected(httpCode: Int, jsonCode: String): Boolean =
        httpCode != 429 && (httpCode == 401 || httpCode == 403 || jsonCode != "0")

    internal fun movieboxSeasonEnd(rawTitle: String): Int? {
        val m = Regex("""\s+S(\d+)(?:\s*-\s*S?(\d+))?$""", RegexOption.IGNORE_CASE)
            .find(rawTitle) ?: return null
        return m.groupValues[2].toIntOrNull() ?: m.groupValues[1].toIntOrNull()
    }

    private val movieBoxPlayGate = Semaphore(1)
    @Volatile private var movieBoxPlayLastAt = 0L
    private const val MOVIEBOX_PLAY_STAGGER_MS = 1_500L

    internal const val MOVIEBOX_REFERER = "https://movieboxonline.net/"

    internal fun cloudFrontCookie(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val parts = signCookie.split(";").map { it.trim().trim('"') }
            .filter { it.contains("=") && it.substringAfter("=").isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.joinToString("; ")
    }

    internal fun isMovieboxNoticeUrl(url: String): Boolean =
        url.contains("macdn.aoneroom.com/other/", ignoreCase = true)

    internal fun decodeBase64Standard(input: String): ByteArray? = catching {
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

    internal fun dashManifestFromPolicy(signCookie: String?): String? = catching {
        if (signCookie.isNullOrBlank()) return@catching null
        val raw = signCookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("CloudFront-Policy=") }
            ?.substringAfter("=")?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: return@catching null
        var std = raw.replace('-', '+').replace('~', '/').replace('_', '=')
        std = std.filterNot { it.isWhitespace() }
        if (std.isEmpty()) return@catching null
        val stripped = std.trimEnd('=')
        if (stripped.isEmpty() || stripped.contains('=')) return@catching null
        if (stripped.length % 4 == 1) return@catching null
        val pad = (4 - stripped.length % 4) % 4
        std = stripped + "=".repeat(pad)
        val json = decodeBase64Standard(std)?.let { String(it, Charsets.UTF_8) }
            ?: return@catching null
        val res = org.json.JSONObject(json)
            .optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
            ?.takeIf { it.isNotBlank() } ?: return@catching null
        val base = res.trim().trimEnd('*').trimEnd('/')
        if (base.isBlank() || !base.startsWith("http")) return@catching null
        if (base.endsWith(".mpd", ignoreCase = true)) base else "$base/index.mpd"
    }.getOrNull()

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
        val meta = try {
            TmdbService.fetchMeta(tmdbId ?: 0, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name ?: run {
            throw CleanMissException("no title for tmdb=$tmdbId (title-keyed API)")
        }
        val seasonKey = if (season > 0) season else 1
        val episodeKey = if (episode > 0) episode else 1

        val host = "h5-api.aoneroom.com"
        val base = "https://$host"

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
            if (attempt == 1 && System.currentTimeMillis() - mbStart > 30_000) {
                Log.w("MovieBox", "skipping auth-retry (>30s already spent) \u2014 keeps the chain inside the 65s kill")
                break
            }
            val token = movieBoxBearer(forceRefresh = attempt > 0) ?: return emptyList()
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
                catching {
                    app.post("$base/wefeed-h5api-bff/subject/search", timeout = budgetSec, headers = baseHeaders,
                        json = mapOf(
                            "keyword" to title, "page" to 1, "perPage" to 24,
                            "subjectType" to subjectType,
                        ))
                }.getOrNull()
            }
            if (resp == null) {
                Log.w("MovieBox", "search no answer in ${budgetSec}s (attempt ${attempt + 1})")
                break
            }
            val root = catching { org.json.JSONObject(resp.text) }.getOrNull()
            val found = root?.let { unwrapData(it).optJSONArray("items") }
            if (found != null && found.length() > 0) {
                searchItems = found
                break
            }
            val jsonCode = root?.optString("code", "")?.takeIf { it.isNotBlank() } ?: "0"
            if (resp.code == 429) {
                if (!throttleRetried) {
                    throttleRetried = true
                    Log.w("MovieBox", "search throttled (HTTP 429) — retrying once after delay")
                    delay(MOVIEBOX_THROTTLE_RETRY_MS)
                    val retryItems = withTimeoutOrNull(10_000L) {
                        catching {
                            app.post("$base/wefeed-h5api-bff/subject/search", timeout = 10, headers = baseHeaders,
                                json = mapOf(
                                    "keyword" to title, "page" to 1, "perPage" to 24,
                                    "subjectType" to subjectType,
                                ))
                        }.getOrNull()
                    }?.let { r -> catching { org.json.JSONObject(r.text) }.getOrNull() }
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
                Log.w("MovieBox", "search answered with no items")
                break
            }
            if (attempt > 0) break
            Log.w("MovieBox", "search rejected (HTTP ${resp.code}, code=$jsonCode)" +
                " � retrying once with a fresh bearer token")
            movieBoxToken = null
        }
        val items = searchItems ?: run { Log.w("MovieBox", "no search items"); return emptyList() }

        val seasonSuffix = Regex("""\s+S\d+(?:\s*-\s*S?\d+)?$""", RegexOption.IGNORE_CASE)
        val bracketGroups = Regex("""[\[(]([^\])]+)[\])]""", RegexOption.IGNORE_CASE)
        val norm: (String) -> String = { t -> t.lowercase().replace(Regex("""[^a-z0-9]"""), "") }
        val titleNorm = norm(title)
        val subjects = mutableListOf<SubjectRef>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title", "")
            val seasonEnd = movieboxSeasonEnd(rawTitle)
            val audioTag = bracketGroups.findAll(rawTitle)
                .map { it.groupValues[1] }
                .firstOrNull { it.any { c -> c.isLetter() } && !it.any { c -> c.isDigit() } }
            val clean = rawTitle
                .let { seasonSuffix.replace(it, "") }
                .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "")
                .replace(Regex("""\s*\d{4}"""), "")
                .trim()
            val cleanNorm = norm(clean)
            val matched = cleanNorm == titleNorm ||
                (titleNorm.length >= 4 && cleanNorm.startsWith(titleNorm))
            if (!matched) continue
            subjects += SubjectRef(id, seasonEnd ?: 0, audioTag, item.optString("detailPath", ""))
        }
        if (subjects.isEmpty()) {
            throw CleanMissException("no exact title match for '$title' in ${items.length()} search rows")
        }
        Log.d("MovieBox", "subjects=${subjects.map { it.id + ":S-end" + it.seasonEnd + ":" + (it.language ?: "orig") }}")

        val refererBase = MOVIEBOX_REFERER
        val out = mutableListOf<RawStream>()
        val seenUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        val subjectResults = kotlinx.coroutines.coroutineScope {
            subjects.map { ref ->
                async {
                    val subjectId = ref.id
                    val language = ref.language
                    if (type != "movie" && ref.seasonEnd in 1 until season) return@async emptyList<RawStream>()

                    val detailPath = ref.detailPath.takeIf { it.isNotBlank() } ?: withTimeoutOrNull(8_000L) {
                        catching {
                            app.get("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                                timeout = 8).text
                        }.getOrNull()
                    }?.let { t ->
                        catching { org.json.JSONObject(t) }.getOrNull()
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

                    val (downloadObj, playObj) = kotlinx.coroutines.coroutineScope {
                        val d = async {
                            withTimeoutOrNull(8_000L) {
                                catching {
                                    app.get("$base/wefeed-h5api-bff/subject/download?$params",
                                        timeout = 8, headers = reqHeaders).text
                                }.getOrNull()
                            }?.let { t -> catching { org.json.JSONObject(t) }.getOrNull() }
                        }
                        val p = async {
                            withMovieBoxPlayPermit {
                                withTimeoutOrNull(8_000L) {
                                    catching {
                                        app.get("$base/wefeed-h5api-bff/subject/play?$params",
                                            timeout = 8, headers = reqHeaders).text
                                    }.getOrNull()
                                }?.let { t -> catching { org.json.JSONObject(t) }.getOrNull() }
                            }
                        }
                        (d.await() ?: org.json.JSONObject()) to (p.await() ?: org.json.JSONObject())
                    }

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
        if (out.isEmpty() && type != "movie" && subjects.isNotEmpty() &&
            subjects.all { it.seasonEnd in 1 until season }
        ) {
            throw CleanMissException("subjects cover up to S${subjects.maxOf { it.seasonEnd }}, requested S$season")
        }

        Log.d("MovieBox", "got ${out.size} streams from ${subjects.size} subjects")
        return out
    }

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
            catching { app.get(apiUrl, timeout = 10, headers = headers).text }.getOrNull()
        } ?: run { Log.w("VaPlayer", "no API response"); return emptyList() }
        val root = catching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("VaPlayer", "non-JSON response: ${safeSnippet(jsonText)}")
            return emptyList()
        }
        val statusCode = root.optInt("status_code", 0)
        if (statusCode != 200) {
            if (statusCode == 404) throw CleanMissException("not in VaPlayer catalog (404)")
            Log.w("VaPlayer", "status_code=$statusCode")
            return emptyList()
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val urls = data.optJSONArray("stream_urls") ?: return emptyList()
        val fileName = data.optString("file_name", "")
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

        suspend fun fetchEmbed(dub: String?, dubdp: String?): org.json.JSONObject? {
            val query = buildString {
                if (type == "tv") append("?type=tv").append(tvParams)
                else append("?type=movie")
                if (dub != null) { append("&dub=").append(dub); append("&dubdp=").append(dubdp) }
            }
            val jsonText = withTimeoutOrNull(12_000L) {
                catching {
                    app.get("https://net27.cc/api/embed-tmdb/$id$query",
                        timeout = 12, headers = headers).text
                }.getOrNull()
            } ?: return null
            return catching { org.json.JSONObject(jsonText) }.getOrNull()
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

        val (defaultRoot, vText) = coroutineScope {
            val rootA = async { fetchEmbed(null, null) }
            val varsA = async {
                withTimeoutOrNull(6_000L) {
                    catching {
                        val vQuery = if (type == "tv") "?se=$season&ep=$episode" else ""
                        app.get("https://net27.cc/api/variants-tmdb/$type/$id$vQuery",
                            timeout = 6, headers = headers).text
                    }.getOrNull()
                }
            }
            rootA.await() to varsA.await()
        }
        val defaultStreams = streamsOf(defaultRoot, null)

        val dubList = catching {
            org.json.JSONObject(vText ?: return@catching emptyList()).optJSONArray("variants").let { arr ->
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val v = arr?.optJSONObject(i) ?: return@mapNotNull null
                    val lang = v.optString("language")
                    val sid = v.optString("dubSubjectId")
                    val dp = v.optString("detailPath")
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
                        catching { streamsOf(fetchEmbed(sid, dp), lang) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
        }

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
                return emptyList()
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
            catching { app.get(apiUrl, timeout = 8, headers = apiHeaders).text }.getOrNull()
        } ?: run { Log.w("VixSrc", "api no response"); return emptyList() }
        val src = catching { org.json.JSONObject(apiText).optString("src") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("VixSrc", "no src in api response"); return emptyList() }
        val embedUrl = if (src.startsWith("http")) src else base + src
        val html = withTimeoutOrNull(8_000L) {
            catching { app.get(embedUrl, timeout = 8, headers = okHeaders(apiUrl)).text }.getOrNull()
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
            catching { app.get(masterUrl, timeout = 8, headers = okHeaders(apiUrl)).text }.getOrNull()
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

    internal fun vixsrcTokenFresh(expires: String, nowMs: Long): Boolean {
        val exp = expires.toLongOrNull() ?: return false
        return exp * 1000 - 60_000 >= nowMs
    }

    private const val API_BASE = "https://api.hlowb.com"
    private const val API_CHANNEL = "IndiaA"
    private const val API_CLIENT = "1"
    private const val API_LANG = "en-US"
    private const val API_PACKAGE = "com.external.castle"
    private const val API_SIGN = "ED0955EB04E67A1D9F3305B95454FED485261475"
    private const val API_KEY_TTL_MS = 60 * 60 * 1000L

    private val apiRequestHeaders = mapOf(
        "User-Agent" to "okhttp/4.9.3",
        "Accept" to "application/json",
        "Referer" to API_BASE,
    )

    @Volatile private var apiKey: String? = null
    @Volatile private var apiKeyAt: Long = 0L
    private val apiKeyLock = kotlinx.coroutines.sync.Mutex()

    data class AudioTrack(val languageId: String, val languageName: String, val existVideo: Boolean)
    data class ApiVideo(val url: String, val quality: String)

    internal suspend fun apiSecurityKey(): String? {
        val cached = apiKey
        if (cached != null && System.currentTimeMillis() - apiKeyAt < API_KEY_TTL_MS) return cached
        return apiKeyLock.withLock {
            val current = apiKey
            if (current != null && System.currentTimeMillis() - apiKeyAt < API_KEY_TTL_MS) return@withLock current
            val fresh = withTimeoutOrNull(8_000L) {
                catching {
                    app.get(
                        "$API_BASE/v0.1/system/getSecurityKey/1?channel=$API_CHANNEL&clientType=$API_CLIENT&lang=$API_LANG",
                        timeout = 8, headers = apiRequestHeaders,
                    ).text
                }.getOrNull()?.let { org.json.JSONObject(it).optString("data").takeIf { value -> value.isNotBlank() } }
            }
            if (fresh != null) {
                apiKey = fresh
                apiKeyAt = System.currentTimeMillis()
            } else {
                Log.w("IndStream", "api security key fetch failed")
            }
            fresh
        }
    }

    internal fun decryptApiPayload(cipherB64: String, secKeyB64: String): String? = runCatching {
        val raw = Base64Kit.decode(secKeyB64)
        val suffix = "T!BgJB".toByteArray(Charsets.UTF_8)
        val key = ByteArray(16)
        val n = minOf(raw.size, 16)
        System.arraycopy(raw, 0, key, 0, n)
        if (n < 16) System.arraycopy(suffix, 0, key, n, minOf(suffix.size, 16 - n))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(key),
        )
        val ct = Base64Kit.decode(Base64Kit.fromCustom(cipherB64.trim()))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    internal fun decryptApiEnvelope(text: String, secKeyB64: String): org.json.JSONObject? {
        val cipher = runCatching {
            val root = org.json.JSONObject(text.trim())
            val d = root.optString("data")
            if (d.isNotBlank()) d else text.trim()
        }.getOrNull() ?: text.trim()
        if (cipher.isBlank()) return null
        runCatching { org.json.JSONObject(cipher) }.getOrNull()?.let { return it }
        return decryptApiPayload(cipher, secKeyB64)
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    }

    private fun unwrapApiData(root: org.json.JSONObject): org.json.JSONObject =
        root.optJSONObject("data") ?: root

    internal suspend fun searchApi(sec: String, keyword: String): List<Pair<String, String>> {
        val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
        val text = withTimeoutOrNull(10_000L) {
            catching {
                app.get(
                    "$API_BASE/film-api/v1.1.0/movie/searchByKeyword?channel=$API_CHANNEL&clientType=$API_CLIENT" +
                        "&keyword=$enc&lang=$API_LANG&mode=1&packageName=$API_PACKAGE&page=1&size=30",
                    timeout = 10, headers = apiRequestHeaders,
                ).text
            }.getOrNull()
        } ?: return emptyList()
        val rows = decryptApiEnvelope(text, sec)?.let { unwrapApiData(it).optJSONArray("rows") }
            ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val title = row.optString("title").ifBlank { row.optString("name") }
            if (title.isBlank()) null else id to title
        }
    }

    internal suspend fun apiDetails(sec: String, movieId: String): org.json.JSONObject? {
        val text = withTimeoutOrNull(10_000L) {
            catching {
                app.get(
                    "$API_BASE/film-api/v1.9.9/movie?channel=$API_CHANNEL&clientType=$API_CLIENT&lang=$API_LANG" +
                        "&movieId=$movieId&packageName=$API_PACKAGE",
                    timeout = 10, headers = apiRequestHeaders,
                ).text
            }.getOrNull()
        } ?: return null
        return decryptApiEnvelope(text, sec)?.let { unwrapApiData(it) }
    }

    internal suspend fun apiVideo(
        sec: String,
        movieId: String,
        episodeId: String,
        languageId: String?,
        resolution: Int,
    ): org.json.JSONObject? {
        val body = mutableMapOf<String, Any>(
            "mode" to "1", "appMarket" to "GuanWang", "clientType" to API_CLIENT,
            "woolUser" to "false", "apkSignKey" to API_SIGN, "androidVersion" to "13",
            "movieId" to movieId, "episodeId" to episodeId,
            "isNewUser" to "true", "resolution" to resolution.toString(), "packageName" to API_PACKAGE,
        )
        if (languageId != null) body["languageId"] = languageId
        val text = withTimeoutOrNull(10_000L) {
            catching {
                app.post(
                    "$API_BASE/film-api/v2.0.1/movie/getVideo2?clientType=$API_CLIENT&packageName=$API_PACKAGE" +
                        "&channel=$API_CHANNEL&lang=$API_LANG",
                    timeout = 10,
                    headers = apiRequestHeaders + ("Content-Type" to "application/json"),
                    json = body,
                ).text
            }.getOrNull()
        } ?: return null
        return decryptApiEnvelope(text, sec)?.let { unwrapApiData(it) }
    }

    internal fun pickAudioTrack(tracks: List<AudioTrack>): AudioTrack? {
        if (tracks.isEmpty()) return null
        tracks.firstOrNull { it.languageName.contains("hindi", ignoreCase = true) }?.let { return it }
        tracks.firstOrNull { it.existVideo }?.let { return it }
        return tracks.first()
    }

    internal fun apiQualityLabel(description: String?, resNum: Int): String {
        if (!description.isNullOrBlank()) {
            Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE).find(description)?.let {
                val height = it.groupValues[1].toIntOrNull()
                if (height != null && height in setOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160)) {
                    return "${height}p"
                }
            }
            if (Regex("""\b(4k|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(description)) return "4K"
        }
        return when (resNum) { 1 -> "480p"; 2 -> "720p"; 3 -> "1080p"; 4 -> "4K"; else -> "" }
    }

    internal fun apiVideos(data: org.json.JSONObject, resolution: Int): List<ApiVideo> {
        val single = data.optString("videoUrl").takeIf { it.isNotBlank() }
        val arr = data.optJSONArray("videos")
        if ((arr == null || arr.length() == 0) && single != null) {
            return listOf(ApiVideo(single, apiQualityLabel(data.optString("resolutionDescription"), resolution)))
        }
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val video = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = video.optString("url").ifBlank { single } ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            ApiVideo(url, apiQualityLabel(video.optString("resolutionDescription"), video.optInt("resolution", resolution)))
        }.distinctBy { it.url }
    }

    private suspend fun resolveCastleTv(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta?.year?.take(4)
        val sec = apiSecurityKey() ?: throw CleanMissException("no security key")
        val rows = searchApi(sec, if (year != null) "$title $year" else title)
        if (rows.isEmpty()) throw CleanMissException("no title match")
        val best = rows.maxByOrNull { TitleMatch.titleDistance(title, it.second) }
            ?: throw CleanMissException("no title match")
        if (!TitleMatch.isRelevant(title, best.second, year?.toIntOrNull(), null)) {
            throw CleanMissException("match too weak: ${best.second}")
        }
        var det = apiDetails(sec, best.first) ?: throw CleanMissException("no details")
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
                            det = apiDetails(sec, sid) ?: det
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
            AudioTrack(
                t.optString("languageId"),
                t.optString("languageName").ifBlank { t.optString("abbreviate") },
                t.optBoolean("existIndividualVideo"),
            )
        }
        val pick = pickAudioTrack(tracks)
        val vids = mutableListOf<ApiVideo>()
        if (pick != null) {
            apiVideo(sec, activeId, epId, pick.languageId.takeIf { it.isNotBlank() }, 3)
                ?.let { vids += apiVideos(it, 3) }
        }
        if (vids.isEmpty()) {
            apiVideo(sec, activeId, epId, null, 3)
                ?.let { vids += apiVideos(it, 3) }
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

    private suspend fun resolveStreamFlix(
        spec: ServerSpec,
        id: String,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val now = System.currentTimeMillis()
        var data = if (now - streamflixDataAt < STREAMFLIX_TTL_MS) streamflixData else null
        if (data == null) {
            data = withTimeoutOrNull(20_000L) {
                catching {
                    app.get("https://api.streamflix.app/data.json", timeout = 20, headers = okHeaders(null)).text
                }.getOrNull()
            }
            if (data != null) { streamflixData = data; streamflixDataAt = now }
        }
        val items = data?.let { catching { org.json.JSONObject(it).optJSONArray("data") }.getOrNull() }
            ?: throw CleanMissException("no catalog")
        val imdbKey = id.takeIf { it.startsWith("tt") }
        var item: org.json.JSONObject? = null
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.opt("tmdb")?.toString() == id ||
                (imdbKey != null && it.optString("movieimdb").contains(imdbKey, ignoreCase = true))
            ) { item = it; break }
        }
        val found = item ?: throw CleanMissException("title not in catalog")
        var base = STREAMFLIX_BASE
        withTimeoutOrNull(8_000L) {
            catching {
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
                catching {
                    app.get("https://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app" +
                        "/Data/$key/seasons/$season/episodes.json", timeout = 10, headers = okHeaders(null)).text
                }.getOrNull()
            } ?: throw CleanMissException("no episode index")
            val eps = catching { org.json.JSONObject(epsText) }.getOrNull()
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

    internal fun titleHits(html: String, base: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        val out = LinkedHashMap<String, String>()
        doc.select(
            "h2.entry-title a[href], h2.post-title a[href], h3.entry-title a[href], " +
                "h3.simple-grid-grid-post-title a[href], .entry-title a[href], .post-title a[href]",
        ).forEach { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http")) return@forEach
            if (Regex("""/(category|tag|author|page|feed|search)/""", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@forEach
            val title = a.text().trim().ifBlank { a.attr("title").trim() }
            if (title.isNotBlank()) out.putIfAbsent(href, title)
        }
        doc.select("a[href] > p").forEach { p ->
            val a = p.parent() ?: return@forEach
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http")) return@forEach
            if (Regex("""/(category|tag|author|page|feed|search)/""", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@forEach
            val title = a.text().trim()
            if (title.isNotBlank()) out.putIfAbsent(href, title)
        }
        return out.map { it.key to it.value }
    }

    internal fun ariaHits(html: String, base: String): List<Pair<String, String>> =
        Jsoup.parse(html, base).select("a[href][aria-label]").mapNotNull { a ->
            val href = a.attr("abs:href").trim()
            val title = a.attr("aria-label").trim()
            if (!href.startsWith("http") || title.isBlank()) return@mapNotNull null
            if (Regex("""/(category|tag|author|page|search)/""", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@mapNotNull null
            href to title
        }.distinctBy { it.first }

    internal fun headingAnchorHits(html: String, base: String, hrefMatch: Regex): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http") || !hrefMatch.containsMatchIn(href)) return@mapNotNull null
            val label = a.text().trim()
            var node: org.jsoup.nodes.Element? = a
            var heading = ""
            while (node != null && heading.isBlank()) {
                val prev = node.previousElementSibling()
                if (prev != null && prev.tagName().matches(Regex("h[1-6]"))) heading = prev.text().trim()
                node = node.parent()
            }
            val low = label.lowercase()
            val generic = label.length < 8 || low == "download link" || low == "download links" ||
                low == "download now" || low == "get links"
            val merged = when {
                heading.isBlank() || label.contains(heading, true) -> label
                heading.contains(label, true) || generic -> heading
                else -> "$heading $label"
            }
            href to merged
        }.distinctBy { it.first }
    }

    internal fun fileHostLinks(html: String, base: String): List<Pair<String, String>> {
        val hostRe = Regex("""(?i)(hubcloud|hubdrive|gdflix|gdshine|hubcdn|pixeldrain|workers\.dev/0:|\.mkv|\.mp4|\.m3u8)""")
        return Jsoup.parse(html, base).select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http") || !hostRe.containsMatchIn(href)) return@mapNotNull null
            if (isArchiveUrl(href) || href.contains(".php?", true) || href.contains("/admin", true) ||
                href.contains("pixel.", true) || href.contains("/tg/", true) || href.contains("t.me", true)
            ) return@mapNotNull null
            href to a.text().trim()
        }.distinctBy { it.first }
    }

    internal fun qualityText(text: String): String {
        val value = Regex("""(?i)\b(2160p|1440p|1080p|720p|480p|4k)\b""").find(text)?.value ?: return ""
        return if (value.equals("4k", true)) "4K" else value.lowercase()
    }

    internal fun fileNameMatches(title: String, year: String?, file: String): Boolean {
        val normalized = TitleMatch.normalizeTitle(file)
        val tokens = TitleMatch.normalizeTitle(title).split(" ").filter { it.length > 2 }
        if (tokens.isEmpty() || !tokens.all { normalized.contains(it) }) return false
        val expected = year?.toIntOrNull() ?: return true
        val actual = TitleMatch.parseYear(file) ?: return true
        return TitleMatch.yearMatches(expected, actual, tolerance = 1)
    }

    private fun rankedHits(hits: List<Pair<String, String>>, title: String, year: String?): List<String> {
        val y = year?.toIntOrNull()
        return hits.map { it to TitleMatch.titleDistance(title, it.second) }
            .filter { (hit, _) -> TitleMatch.isRelevant(title, hit.second, y, TitleMatch.parseYear(hit.second)) }
            .sortedByDescending { it.second }
            .map { it.first.first }
            .take(2)
    }

    internal fun hubPostYearOk(html: String, year: String?): Boolean {
        if (year.isNullOrBlank()) return true
        val title = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: return true
        return title.contains(year)
    }

    internal data class HubItem(
        val title: String,
        val size: String,
        val languages: String,
        val gatewayUrls: List<String>,
    )

    internal fun hub4kItems(html: String): List<HubItem> {
        val root = Jsoup.parse(html)
        val langRe = Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|dual|multi)\b""")
        return root.select("div.download-item, div.episode-download-item").mapNotNull { item ->
            val header = item.selectFirst("div.download-header")
            val title = (header?.selectFirst("div.flex-1, .font-semibold")
                ?: item.selectFirst(".episode-file-title, .file-title"))
                ?.text()?.trim().orEmpty().ifBlank { header?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val badges = (header?.select("code .badge, .badge")
                ?: item.select(".badge-size, .badge-psa, .badge")).map { it.text().trim() }
            val size = badges.firstOrNull { normalizeSizeToken(it).isNotBlank() }
                ?.let { normalizeSizeToken(it) }.orEmpty()
            val langs = (badges + item.select(".badge").map { it.text().trim() })
                .firstOrNull { langRe.containsMatchIn(it) }
                .orEmpty()
            val gateways = item.select("a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) return@mapNotNull null
                if (!Regex("""greenmotors|hubcloud|hubdrive""", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                    return@mapNotNull null
                }
                href to a.text().contains("hubcloud", ignoreCase = true)
            }.sortedByDescending { if (it.second) 1 else 0 }
                .map { it.first }.distinct()
            HubItem(title, size, langs, gateways)
        }.filter { it.gatewayUrls.isNotEmpty() }
    }

    internal fun normalizeSizeToken(text: String): String =
        Regex("""\d+(?:\.\d+)?\s?(?:GB|MB)""", RegexOption.IGNORE_CASE).find(text)
            ?.value?.replace(" ", "")?.uppercase().orEmpty()

    internal fun rot13(text: String): String = buildString {
        for (c in text) when {
            c in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
            c in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
            else -> append(c)
        }
    }

    internal fun b64Text(input: String): String? {
        val clean = input.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (clean.length < 4 || clean.length % 4 == 1) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return catching { String(Base64Kit.decode(padded), Charsets.UTF_8) }.getOrNull()
    }

    internal fun rotatorTarget(pageHtml: String): String? {
        val blob = Regex("""'o'\s*,\s*'([A-Za-z0-9+/=]{16,})[^']*'""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""ck\('_wp_http_\d+','([^']+)'""").find(pageHtml)?.groupValues?.get(1)
            ?: return null
        val l1 = b64Text(blob) ?: return null
        val l2 = b64Text(l1) ?: return null
        val jsonText = b64Text(rot13(l2)) ?: return null
        val oToken = Regex("\"o\"\\s*:\\s*\"([A-Za-z0-9+/=]{10,})").find(jsonText)?.groupValues?.get(1)
        val wrapped = catching { org.json.JSONObject(jsonText) }.getOrNull()
            ?: oToken?.takeIf { b64Text(it)?.startsWith("http") == true }
                ?.let { org.json.JSONObject("{\"o\":\"$it\"}") }
            ?: return null
        val url = b64Text(wrapped.optString("o"))?.trim() ?: return null
        return url.takeIf { it.startsWith("http") }
    }

    internal fun hubLangOf(languages: String): String {
        val s = languages.lowercase()
        val hasHindi = s.contains("hindi")
        val hasOther = Regex("""english|dual|multi|tamil|telugu|malayalam|kannada|japanese|korean|chinese""").containsMatchIn(s)
        return when {
            s.contains("hindi") && hasOther -> "Multi"
            s.contains("hindi") -> "Hindi"
            Regex("""\b(tamil|telugu|malayalam|kannada|bengali|marathi|punjabi)\b""").containsMatchIn(s) ->
                releaseLanguageOf(languages).ifBlank { "Multi" }
            else -> releaseLanguageOf(languages).ifBlank { "" }
        }
    }

    private fun isArchiveUrl(url: String): Boolean =
        Regex("""\.(?:zip|rar|7z|tar|gz)(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    internal fun hubFileButtons(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html)
        val headQ = releaseHeightOf(doc.selectFirst(".card-header")?.text())
        val out = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a[href]")) {
            if (!Regex("""\bbtn\b""").containsMatchIn(a.className())) continue
            var url = a.attr("href").trim()
            if (!url.startsWith("http")) continue
            if (isArchiveUrl(url)) continue
            if (url.contains("facebook.") || url.contains("twitter.") || url.contains("t.me") ||
                url.contains("/tg/") || url.contains("pixel.") || url.contains("/admin")
            ) continue
            url = Regex("""pixeldrain\.[a-z]+/u/([a-zA-Z0-9]+)""").find(url)?.let {
                url.replace("/u/${it.groupValues[1]}", "/api/file/${it.groupValues[1]}")
            } ?: url
            val q = releaseHeightOf(a.text()).takeIf { it > 0 } ?: headQ
            out += url to (if (q > 0) "${q}p" else "")
        }
        return out.distinctBy { it.first }
    }

    private suspend fun hubDriveFiles(driveUrl: String, referer: String): List<Pair<String, String>> {
        val healed = when {
            driveUrl.contains("vcloud.", ignoreCase = true) -> healHost(driveUrl, "vcloud")
            driveUrl.contains("hubcloud.", ignoreCase = true) -> healHost(driveUrl, "hubcloud")
            else -> driveUrl
        }
        var url = driveUrl
        var drive = ""
        var finalUrl = driveUrl
        for (candidate in listOf(driveUrl, healed).distinct()) {
            val res = withTimeoutOrNull(8_000L) {
                catching { app.get(candidate, timeout = 8, headers = okHeaders(referer)) }.getOrNull()
            } ?: continue
            if (res.text.isBlank()) continue
            url = candidate
            drive = res.text
            finalUrl = res.url
            break
        }
        if (drive.isBlank()) return emptyList()
        googleVideoLink(drive, finalUrl)?.let { return listOf(it to qualityText(drive)) }
        val tokenUrl = Regex("""https?://[^"' ]*?(?:hubcloud|hubdrive)\.php\?[^"' ]+""").find(drive)?.value
            ?: Regex("""(?:hubcloud|hubdrive)\.php\?[^"' ]+""").find(drive)?.value?.let { tokenHref ->
                val origin = Regex("""^https?://[^/]+""").find(url)?.value ?: return emptyList()
                "$origin/$tokenHref"
            } ?: return emptyList()
        val page = withTimeoutOrNull(10_000L) {
            catching { app.get(tokenUrl, timeout = 10, headers = okHeaders(url)).text }.getOrNull()
        } ?: return emptyList()
        return hubFileButtons(page).take(4)
    }

    private fun googleVideoLink(html: String, finalUrl: String): String? {
        val raw = Regex("""(https?://video-downloads\.googleusercontent\.com/[^"'\s<>]+)""")
            .find(html)?.groupValues?.get(1)
            ?: finalUrl.substringAfter("dl.php?link=", "")
                .takeIf { it.startsWith("http") }
            ?: return null
        return java.net.URLDecoder.decode(raw.replace("&amp;", "&"), "UTF-8")
            .takeIf { it.startsWith("http") }
    }

    private suspend fun fetchText(
        url: String,
        referer: String? = null,
        timeoutSec: Long = 10,
        headers: Map<String, String> = emptyMap(),
    ): String? = withTimeoutOrNull(timeoutSec * 1000L) {
        catching {
            app.get(url, timeout = timeoutSec, headers = okHeaders(referer) + headers).text
        }.getOrNull()
    }

    internal fun pixeldrainDirect(url: String): String? {
        val m = Regex("""(?i)(https?://pixeldrain\.[a-z.]+)/(?:api/file|u)/([A-Za-z0-9_\-]+)""")
            .find(url) ?: return null
        return "${m.groupValues[1]}/api/file/${m.groupValues[2]}"
    }

    private fun rawFile(
        spec: ServerSpec,
        label: String,
        url: String,
        referer: String,
        language: String,
        size: String,
    ): RawStream {
        val quality = qualityText(label)
        val lang = language.ifBlank { releaseLanguageOf(label) }
        return RawStream(
            spec.id,
            listOf(spec.name, quality).filter { it.isNotBlank() }.joinToString(" "),
            url,
            url.contains(".m3u8", ignoreCase = true),
            referer,
            releaseHeightOf(label),
            audioPriority = when (lang) {
                "Hindi" -> 4
                "Multi" -> 2
                else -> 1
            },
            audioLabel = lang,
            sizeLabel = size,
            mediaLabel = label,
        )
    }

    private suspend fun gdshineStream(host: String, referer: String): Pair<String, String>? {
        val shortId = Regex("""gdshine\.[a-z.]+/s/([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
            .find(host)?.groupValues?.get(1) ?: return null
        val origin = Regex("""^https?://[^/]+""").find(host)?.value ?: return null
        val info = fetchText("$origin/api/files/s/$shortId", referer) ?: return null
        val id = catching { org.json.JSONObject(info) }.getOrNull()
            ?.optJSONObject("data")?.optString("id").orEmpty()
        if (id.isBlank()) return null
        val mirror = fetchText("$origin/api/gp/mirror/$id", referer) ?: return null
        val data = catching { org.json.JSONObject(mirror) }.getOrNull()
            ?.optJSONObject("data") ?: return null
        val stream = data.optString("url")
        if (!stream.startsWith("http")) return null
        val name = data.optString("filename")
        return stream to qualityText(name)
    }

    private suspend fun hostStreams(
        spec: ServerSpec,
        hosts: List<Pair<String, String>>,
        label: String,
        referer: String,
        language: String,
        size: String,
    ): List<RawStream> {
        val out = mutableListOf<RawStream>()
        for ((host, hostLabel) in hosts) {
            val pixeldrain = pixeldrainDirect(host)
            if (pixeldrain != null) {
                out += rawFile(spec, label.ifBlank { hostLabel }, pixeldrain, referer, language, size)
                continue
            }
            if (host.contains("gdshine.", ignoreCase = true)) {
                val stream = gdshineStream(host, referer) ?: continue
                out += rawFile(
                    spec, label.ifBlank { stream.second }, stream.first, referer, language, size,
                )
                continue
            }
            if (Regex("""(?i)\.(?:mkv|mp4|m3u8)(?:[?#]|$)""").containsMatchIn(host)) {
                out += rawFile(spec, label.ifBlank { hostLabel }, host, referer, language, size)
                continue
            }
            if (host.contains("hubcloud.", ignoreCase = true) || host.contains("hubdrive.", ignoreCase = true)) {
                for ((url, quality) in hubDriveFiles(host, referer)) {
                    out += rawFile(spec, quality.ifBlank { label }, url, referer, language, size)
                }
            }
        }
        return out
    }

    internal fun episodeMatches(text: String, season: Int, episode: Int): Boolean {
        val ep = "0?$episode"
        if (Regex("""(?i)\bS0?$season[\s._-]*(?:E|EP|EPISODE)[\s._-]*$ep\b""")
                .containsMatchIn(text)
        ) return true
        if (Regex("""(?i)\b$season\s*x\s*$ep\b""").containsMatchIn(text)) return true
        val taggedSeason = Regex("""(?i)\bS\d{1,2}\b|\bSeason\s*\d""").containsMatchIn(text)
        return !taggedSeason && Regex("""(?i)\b(?:EP?|EPISODE)[\s._-]*$ep\b""")
            .containsMatchIn(text)
    }

    internal fun seasonPackMatches(text: String, season: Int): Boolean =
        Regex("""(?i)\bS0?$season(?!\s*[._-]?E\d)""").containsMatchIn(text) ||
            Regex("""(?i)\bSeason\s*0?$season\b""").containsMatchIn(text)

    internal fun <T> episodeOrSeason(
        all: List<T>,
        text: (T) -> String,
        type: String,
        season: Int,
        episode: Int,
    ): List<T> {
        if (type == "movie" || season <= 0) return all
        val exact = all.filter { episodeMatches(text(it), season, episode) }
        return exact.ifEmpty { all.filter { seasonPackMatches(text(it), season) } }
    }

    private suspend fun healHost(url: String, key: String): String {
        val live = Domains.base(key) ?: return url
        val old = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        val oldHost = old.substringAfter("://").substringBefore('/')
        val liveHost = live.substringAfter("://").substringBefore('/')
        if (oldHost.equals(liveHost, ignoreCase = true)) return url
        if (oldHost.count { it == '.' } > liveHost.count { it == '.' }) return url
        return live + url.removePrefix(old)
    }

    private suspend fun resolveHub4k(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta?.year?.take(4)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        var base = ""
        var searchHtml = ""
        var fallback: Pair<String, String>? = null
        val bases = (listOfNotNull(Domains.base("4khdhub")) +
            listOf("https://4khdhub.one", "https://4khdhub.com")).distinct()
        for (candidate in bases) {
            val html = withTimeoutOrNull(8_000L) {
                catching { app.get("$candidate/?s=${enc(title)}", timeout = 8, headers = okHeaders(candidate)).text }.getOrNull()
            }
            if (html.isNullOrBlank()) continue
            if (hubParseCards(html, candidate).isNotEmpty()) { base = candidate; searchHtml = html; break }
            if (fallback == null) fallback = candidate to html
        }
        if (base.isBlank()) {
            val f = fallback ?: throw CleanMissException("no search answer")
            base = f.first
            searchHtml = f.second
        }
        if (base.isBlank()) throw CleanMissException("no search answer")
        val cards = hubParseCards(searchHtml, base)
        if (cards.isEmpty()) throw CleanMissException("no search hits")
        val ranked = cards.map { it to TitleMatch.titleDistance(title, it.second) }
            .sortedByDescending { it.second }.take(3)
        if (ranked.isEmpty() || ranked[0].second < 0.6) throw CleanMissException("no match")
        for ((card, _) in ranked.take(2)) {
            val post = withTimeoutOrNull(8_000L) {
                catching { app.get(card.first, timeout = 8, headers = okHeaders(base)).text }.getOrNull()
            } ?: continue
            if (!hubPostYearOk(post, year)) continue
            val items = episodeOrSeason(
                hub4kItems(post), { it.title }, type, season, episode,
            ).take(4)
            val out = mutableListOf<RawStream>()
            for (item in items) {
                val lang = hubLangOf(item.languages)
                val priority = when (lang) {
                    "Hindi" -> 4
                    "Multi" -> 2
                    else -> 1
                }
                for (gateway in item.gatewayUrls) {
                    val gwPage = withTimeoutOrNull(10_000L) {
                        catching { app.get(gateway, timeout = 10, headers = okHeaders(card.first)).text }.getOrNull()
                    } ?: continue
                    var driveUrl = rotatorTarget(gwPage)?.let { relUrl(gateway, it) } ?: continue
                    if (driveUrl.contains("hubdrive.", ignoreCase = true)) {
                        val inner = withTimeoutOrNull(8_000L) {
                            catching { app.get(driveUrl, timeout = 8, headers = okHeaders(card.first)).text }.getOrNull()
                        }?.let { page ->
                            Jsoup.parse(page, driveUrl).select("a[href]").firstOrNull { a ->
                                a.attr("abs:href").contains("hubcloud", ignoreCase = true)
                            }?.attr("abs:href")
                        }
                        driveUrl = inner?.takeIf { it.startsWith("http") } ?: continue
                    }
                    val referer = Regex("""^https?://[^/]+""").find(driveUrl)?.value ?: "https://hubcloud.ist/"
                    val files = hubDriveFiles(driveUrl, card.first)
                    for ((url, q) in files) {
                        val qText = q.ifBlank {
                            Regex("""\b(2160p|1440p|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE)
                                .find(item.title)?.value.orEmpty()
                        }
                        out += RawStream(
                            spec.id, "${spec.name} $qText".trim(), url,
                            url.contains(".m3u8", ignoreCase = true), referer,
                            releaseHeightOf(qText.ifBlank { item.title }),
                            audioPriority = priority, audioLabel = lang,
                            sizeLabel = item.size,
                            mediaLabel = item.title,
                        )
                    }
                    if (out.size >= 6) break
                }
                if (out.size >= 6) break
            }
            if (out.isNotEmpty()) return out
        }
        throw CleanMissException("no file links")
    }


    private suspend fun titleYear(tmdbId: Int, type: String): Pair<String, String?> {
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        return title to meta?.year?.take(4)
    }

    private suspend fun resolveLinkIndex(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val base = Domains.base("hindmoviez") ?: "https://hindmovie.one"
        val search = fetchText("$base/?s=${enc(title)}", base)
            ?: throw CleanMissException("no search answer")
        val posts = rankedHits(titleHits(search, base), title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val out = mutableListOf<RawStream>()
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            val links = episodeOrSeason(
                headingAnchorHits(
                    post, postUrl,
                    Regex("""links\.joshapp\.blog|mvlink\.blog""", RegexOption.IGNORE_CASE),
                ),
                { "${it.second} ${it.first}" }, type, season, episode,
            ).take(4)
            for ((href, label) in links) {
                val linkPage = fetchText(href, postUrl) ?: continue
                val name = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                    .find(linkPage)?.groupValues?.get(1)?.trim().orEmpty()
                val doc = Jsoup.parse(linkPage, href)
                val boldb = doc.select("a[href]")
                    .firstOrNull { it.attr("href").contains("boldb.site/?id=") }
                    ?.attr("href")
                val hshare = doc.select("a[href]")
                    .firstOrNull { it.attr("href").contains("hshare.lol/?id=") }
                    ?.attr("abs:href")
                val hosts = if (boldb != null) {
                    val rawId = java.net.URLDecoder.decode(boldb.substringAfter("id=", ""), "UTF-8")
                    val token = Base64Kit.encode(rawId.toByteArray(Charsets.UTF_8))
                        .trimEnd('=').replace('+', '-').replace('/', '_')
                    val filePage = fetchText("https://boldb.site/dl/$token", "https://boldb.site/")
                    filePage?.let { fileHostLinks(it, "https://boldb.site/") }.orEmpty()
                } else if (hshare != null) {
                    val mirror = fetchText(hshare, href) ?: continue
                    fileHostLinks(mirror, hshare)
                } else {
                    fileHostLinks(linkPage, href)
                }
                if (hosts.isEmpty()) continue
                val language = releaseLanguageOf(name.ifBlank { label })
                val size = normalizeSizeToken(label).ifBlank { normalizeSizeToken(name) }
                val ref = if (boldb != null) "https://boldb.site/" else hshare ?: href
                out += hostStreams(spec, hosts, label.ifBlank { name }, ref, language, size)
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        throw CleanMissException("no file links")
    }

    private suspend fun resolveRotatorFiles(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val base = Domains.base("hdhub4u") ?: "https://new8.hdhub4u.cl"
        val search = fetchText("$base/search/${enc(title).replace("+", "%20")}/", base)
            ?: throw CleanMissException("no search answer")
        val posts = rankedHits(ariaHits(search, base), title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val out = mutableListOf<RawStream>()
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            if (!hubPostYearOk(post, year)) continue
            val anchors = Jsoup.parse(post, postUrl).select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").trim()
                if (!Regex("""greenmotors|hubcdn|hubdrive""", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                    return@mapNotNull null
                }
                href to a.text().trim()
            }.filter { !it.second.contains("watch", ignoreCase = true) }.distinctBy { it.first }
            val links = episodeOrSeason(anchors, { "${it.second} ${it.first}" }, type, season, episode).take(6)
            val language = releaseLanguageOf(post)
            for ((href, label) in links) {
                val hosts = if (href.contains("greenmotors", ignoreCase = true)) {
                    val gateway = fetchText(href, postUrl) ?: continue
                    val archive = rotatorTarget(gateway) ?: continue
                    val archivePage = fetchText(archive, href) ?: continue
                    fileHostLinks(archivePage, archive)
                } else {
                    listOf(href to label)
                }
                out += hostStreams(spec, hosts, label, postUrl, language, normalizeSizeToken(label))
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        throw CleanMissException("no file links")
    }

    private suspend fun resolveRecoverFiles(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val base = Domains.base("moviesdrive") ?: "https://new3.moviesdrive.beer"
        val searchJson = fetchText("$base/search.php?q=${enc(title)}&page=1", base)
            ?: throw CleanMissException("no search answer")
        val hits = fslSearchHits(searchJson).map { relUrl(base, it.first) to it.second }
        val posts = rankedHits(hits, title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val episodeTag = if (type == "tv" && season > 0 && episode > 0) {
            "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        } else {
            ""
        }
        val out = mutableListOf<RawStream>()
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            val links = episodeOrSeason(
                headingAnchorHits(post, postUrl, Regex("""search-recover""", RegexOption.IGNORE_CASE)),
                { "${it.second} ${it.first}" }, type, season, episode,
            ).take(6)
            val language = releaseLanguageOf(post)
            for ((href, label) in links) {
                val token = Regex("""from_ac=([^&]+)""").find(href)?.groupValues?.get(1) ?: continue
                val apiBase = Regex("""^https?://[^/]+""").find(href)?.value
                    ?: Domains.base("hubcloud") ?: "https://hubcloud.ist"
                val quality = qualityText(label)
                val queries = listOf(
                    listOfNotNull(title, year, episodeTag, quality).joinToString(" ").trim(),
                    listOfNotNull(title, year, episodeTag).joinToString(" ").trim(),
                    listOfNotNull(title, year, quality).joinToString(" ").trim(),
                    listOfNotNull(title, year).joinToString(" ").trim(),
                    title,
                ).filter { it.isNotBlank() }.distinct()
                for (query in queries) {
                    val api = "$apiBase/drive/search-recover.php?api=search&q=${enc(query)}&page=1&from_ac=${enc(token)}"
                    val json = fetchText(api, base, headers = mapOf("Accept" to "application/json")) ?: continue
                    val arr = catching { org.json.JSONObject(json).optJSONArray("hits") }.getOrNull() ?: continue
                    var added = 0
                    for (i in 0 until arr.length()) {
                        val hit = arr.optJSONObject(i) ?: continue
                        val file = hit.optString("file_name")
                        if (!fileNameMatches(title, year, file)) continue
                        val fileQuality = qualityText(file)
                        if (quality.isNotBlank() && fileQuality.isNotBlank() &&
                            releaseHeightOf(quality) > 0 && releaseHeightOf(fileQuality) != releaseHeightOf(quality)
                        ) continue
                        val url = hit.optString("url")
                        if (!url.startsWith("http")) continue
                        val hosts = hubDriveFiles(url, postUrl)
                        out += hostStreams(
                            spec, hosts, label.ifBlank { file }, postUrl,
                            releaseLanguageOf(file).ifBlank { language }, normalizeSizeToken(label),
                        )
                        added++
                        if (added >= 2) break
                    }
                    if (out.isNotEmpty()) break
                }
                if (out.isNotEmpty()) return out.distinctBy { it.url }
            }
        }
        throw CleanMissException("no file links")
    }

    private suspend fun resolveArchiveFiles(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val base = Domains.base("dudefilms") ?: "https://dudefilms.events"
        val search = fetchText("$base/?s=${enc(title)}", base)
            ?: throw CleanMissException("no search answer")
        val posts = rankedHits(titleHits(search, base), title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val out = mutableListOf<RawStream>()
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            val links = episodeOrSeason(
                headingAnchorHits(post, postUrl, Regex("""dflinks\.cc/archives""", RegexOption.IGNORE_CASE)),
                { "${it.second} ${it.first}" }, type, season, episode,
            ).take(4)
            for ((href, label) in links) {
                val archive = fetchText(href, postUrl) ?: continue
                val hosts = fileHostLinks(archive, href)
                out += hostStreams(
                    spec, hosts, label, href,
                    releaseLanguageOf(label).ifBlank { releaseLanguageOf(post) }, normalizeSizeToken(label),
                )
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        throw CleanMissException("no file links")
    }

    private val FSL_SEEDS = listOf(
        Triple("vegamovies", "https://new1.vegamovies.lease", "https://new2.vegamovies.futbol"),
        Triple("rogmovies", "https://rogmovies.one", null),
    )

    private suspend fun fslSeeds(): List<Triple<String, String, String?>> {
        val remote = listOf("vegamovies", "rogmovies").mapNotNull { key ->
            Domains.base(key)?.let { Triple(key, it, null) }
        }
        return (remote + FSL_SEEDS).distinctBy { it.first }
    }
    private const val FSL_LANDING_PROBE = "/?re=vg&t=2"
    private val fslBaseCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    internal fun fslIsLanding(html: String): Boolean =
        html.contains("qikly.pro/host") || html.contains("id=\"stx\"")

    internal fun fslDomainPick(html: String, family: String): String? =
        Regex("""https?://[a-z0-9.\-]*$family\.[a-z]{2,10}""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value.lowercase() }
            .filterNot { it.contains("apk") || it.endsWith(".cfd") }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

    private suspend fun fslGet(url: String, timeoutSec: Long = 12): Pair<String, String>? {
        val res = withTimeoutOrNull(timeoutSec * 1000L) {
            catching { app.get(url, timeout = timeoutSec * 1000L, headers = okHeaders(url)) }.getOrNull()
        } ?: return null
        return res.text to res.url
    }

    private suspend fun fslLive(seed: String, bootstrap: String?): Pair<String, String>? {
        val (html0, url0) = fslGet(seed, 10)
            ?: (bootstrap?.let { fslGet(it, 10) } ?: return null)
        if (!fslIsLanding(html0)) return html0 to url0
        val origin = Regex("""^https?://[^/]+""").find(url0)?.value ?: return null
        val probed = fslGet(origin + FSL_LANDING_PROBE, 12) ?: return null
        return if (fslIsLanding(probed.first)) null else probed
    }

    private suspend fun fslBase(family: String, seed: String, bootstrap: String?): String? {
        fslBaseCache[family]?.let { (cached, at) ->
            if (System.currentTimeMillis() - at < 30 * 60_000L) return cached
        }
        val live = fslLive(seed, bootstrap)
        val liveBase = live?.let { (html, url) ->
            val origin = Regex("""^https?://[^/]+""").find(url)?.value ?: url
            fslDomainPick(html, family) ?: origin
        } ?: return null
        fslBaseCache[family] = liveBase to System.currentTimeMillis()
        return liveBase
    }

    internal fun fslSearchHits(json: String): List<Pair<String, String>> {
        val root = catching { org.json.JSONObject(json) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
            val permalink = doc.optString("permalink").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            permalink to doc.optString("post_title")
        }.filter { it.second.isNotBlank() }
    }

    internal data class FslGroup(val heading: String, val gateways: List<String>)

    internal fun fslGroups(html: String): List<FslGroup> {
        val gatewayRe = Regex(
            """https?://[a-z0-9.\-]*(?:nexdrive|gdflix|filepress|vcloud)\.[a-z]{2,10}/[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE,
        )
        val doc = Jsoup.parse(html)
        val entry = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        val groups = ArrayList<FslGroup>()
        var heading = ""
        var anchors = ArrayList<String>()
        fun close() {
            if (anchors.isNotEmpty() && heading.isNotBlank()) groups += FslGroup(heading, anchors.distinct())
            anchors = ArrayList()
        }
        for (el in entry.getAllElements()) {
            when {
                el.tagName().matches(Regex("h[1-6]")) -> {
                    val t = el.text().trim()
                    if (t.isNotBlank() && t.length < 220) {
                        if (anchors.isNotEmpty()) close()
                        heading = if (downloadHeadingShaped(t)) t else ""
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href")
                    if (gatewayRe.containsMatchIn(href)) {
                        anchors += if (href.contains("vcloud.", ignoreCase = true)) href else href.trimEnd('/') + "/"
                    }
                }
            }
        }
        close()
        return groups.groupBy { it.heading }
            .map { (_, gs) -> FslGroup(gs.first().heading, gs.flatMap { it.gateways }) }
            .filter { it.gateways.isNotEmpty() }
    }

    private fun downloadHeadingShaped(text: String): Boolean =
        Regex("""(?i)(\d{3,4}p\b|4K|\d+(?:\.\d+)?\s?(?:GB|MB)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""").containsMatchIn(text)

    internal data class GatewayLinks(val fastdl: List<String>, val cloud: List<String>)

    internal fun gatewayLinksOf(html: String): GatewayLinks {
        val fastdl = Regex("""https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)
            .findAll(html.replace("\\/", "/")).map { it.value }.distinct().toList()
        val vcloud = Regex("""https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""")
            .findAll(html).map { it.value }.distinct().toList()
        return GatewayLinks(fastdl, vcloud)
    }

    internal fun vcloudPageMatchesEpisode(pageTitle: String, type: String, season: Int, episode: Int): Boolean {
        if (type != "tv" || season <= 0 || episode <= 0) return true
        val tagged = Regex("""(?i)\bS\d{1,2}[\s._-]?E\d{1,3}\b""").containsMatchIn(pageTitle)
        return !tagged || episodeMatches(pageTitle, season, episode)
    }

    internal fun <T> episodeFirstOrder(all: List<T>, type: String, season: Int, episode: Int): List<T> {
        if (type != "tv" || season <= 0 || episode <= 0 || all.size <= 1) return all
        val start = (episode - 1).coerceIn(0, all.lastIndex)
        return all.drop(start) + all.take(start)
    }

    private suspend fun vcloudR2Links(
        vcloudUrl: String,
        referer: String,
        type: String,
        season: Int,
        episode: Int,
    ): List<Pair<String, String>> {
        val url = healHost(vcloudUrl, "vcloud")
        val page = withTimeoutOrNull(15_000L) {
            catching { app.get(url, timeout = 15, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return emptyList()
        val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)?.trim().orEmpty()
        if (isArchiveUrl(pageTitle) || pageTitle.contains("complete season", ignoreCase = true)) return emptyList()
        if (!vcloudPageMatchesEpisode(pageTitle, type, season, episode)) return emptyList()
        val tokenUrl = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""")
            .find(page)?.groupValues?.get(1)
            ?.let { blob -> vcloudDoubleAtob(blob) }
            ?.takeIf { it.startsWith("http") } ?: return emptyList()
        val token = withTimeoutOrNull(15_000L) {
            catching { app.get(tokenUrl, timeout = 15, headers = okHeaders(url)).text }.getOrNull()
        } ?: return emptyList()
        val btnRe = Regex(
            """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
            RegexOption.IGNORE_CASE,
        )
        val out = ArrayList<Pair<String, String>>()
        val seen = LinkedHashSet<String>()
        for (m in btnRe.findAll(token)) {
            val raw = m.groupValues[1].trim()
            val label = Regex("""\[([^\]]+)]""").find(m.groupValues[2].trim())?.groupValues?.get(1)?.trim()
                ?: m.groupValues[2].trim()
            val abs = if (raw.startsWith("http")) raw else {
                val origin = Regex("""^https?://[^/]+""").find(tokenUrl)?.value ?: continue
                "$origin${if (raw.startsWith('/')) raw else "/$raw"}"
            }
            if (isArchiveUrl(abs)) continue
            if (Regex("""(r2\.cloudflarestorage\.com|r2\.dev)""", RegexOption.IGNORE_CASE).containsMatchIn(abs) && seen.add(abs)) {
                out += abs to label.take(20)
            }
        }
        for (gpdl in Regex("""https?://[^"' ]*gpdl[^"' ]*hubcloud[^"' ]+""", RegexOption.IGNORE_CASE)
            .findAll(token).map { it.value }.distinct()
        ) {
            for ((direct, quality) in hubDriveFiles(gpdl, tokenUrl)) {
                if (seen.add(direct)) out += direct to quality
            }
        }
        return out
    }

    internal fun vcloudDoubleAtob(blob: String): String? {
        val inner = b64Text(blob.trim()) ?: return null
        return b64Text(inner.trim())
    }

    internal fun headingSizeOf(heading: String): String {
        Regex("""\[\s*(\d+(?:\.\d+)?\s*(?:GB|MB)(?:/E\b|/EP\b|/ZiP|/ZIP)?)\s*]""", RegexOption.IGNORE_CASE)
            .find(heading)?.let { return normalizeSizeToken(it.groupValues[1]).replace("/ZIP", "") }
        return normalizeSizeToken(heading)
    }

    private suspend fun resolveFsl(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name?.takeIf { it.isNotBlank() }
            ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta?.year?.take(4)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val out = mutableListOf<RawStream>()
        val seen = HashSet<String>()
        val epTag = if (type == "tv" && season > 0 && episode > 0) {
            " S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        } else {
            ""
        }
        for ((family, seed, bootstrap) in fslSeeds()) {
            val base = fslBase(family, seed, bootstrap) ?: continue
            val json = fslGet("$base/ts-search.php?q=${enc(title)}&page=1", 8)?.first ?: continue
            val hits = fslSearchHits(json)
            if (hits.isEmpty()) continue
            val ranked = hits.map { it to TitleMatch.titleDistance(title, it.second) }
                .sortedByDescending { it.second }
                .filter { (hit, _) ->
                    year == null || TitleMatch.yearMatches(year.toIntOrNull(), TitleMatch.parseYear(hit.second))
                }
                .take(2)
            for ((hitPair, _) in ranked) {
                val permalink = relUrl(base, hitPair.first)
                val post = fslGet(permalink, 10)?.first ?: continue
                val groups = episodeOrSeason(
                    fslGroups(post), { it.heading }, type, season, episode,
                )
                for (group in groups.take(4)) {
                    val size = headingSizeOf(group.heading)
                    val lang = releaseLanguageOf(group.heading)
                    val priority = when (lang) {
                        "Hindi" -> 4
                        "Multi" -> 2
                        else -> 1
                    }
                    val qText = Regex("""\b(2160p|1440p|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE)
                        .find(group.heading)?.value.orEmpty()
                    var perGroup = 0
                    for (gateway in group.gateways) {
                        val cloudLinks = if (gateway.contains("vcloud.", ignoreCase = true)) {
                            listOf(gateway)
                        } else {
                            val expanded = fslGet(gateway, 15)?.first ?: continue
                            gatewayLinksOf(expanded).cloud
                        }
                        for (vcloud in episodeFirstOrder(cloudLinks, type, season, episode)) {
                            for ((url, _) in vcloudR2Links(vcloud, gateway, type, season, episode)) {
                                if (!seen.add(url)) continue
                                out += RawStream(
                                    spec.id, "${spec.name} $qText$epTag".trim(), url,
                                    url.contains(".m3u8", ignoreCase = true),
                                    Regex("""^https?://[^/]+""").find(url)?.value,
                                    releaseHeightOf(qText.ifBlank { group.heading }),
                                    audioPriority = priority, audioLabel = lang,
                                    sizeLabel = size,
                                    mediaLabel = group.heading,
                                )
                                perGroup++
                                if (perGroup >= 2) break
                            }
                            if (perGroup >= 2) break
                        }
                        if (perGroup >= 2) break
                    }
                    if (out.size >= 6) break
                }
                if (out.isNotEmpty()) break
            }
            if (out.isNotEmpty()) break
        }
        if (out.isEmpty()) throw CleanMissException("no fast file links")
        return out
    }


    private const val VOD_API = "https://api3.devcorp.me"
    private const val VOD_REFERER = "https://onetouchtv.xyz/"
    private val VOD_KEY = "im72charPasswordofdInitVectorStm".toByteArray(Charsets.UTF_8)
    private val VOD_IV = "im72charPassword".toByteArray(Charsets.UTF_8)

    data class VodHit(val id: String, val title: String, val year: String, val type: String)
    data class VodSource(val url: String, val quality: String, val type: String)
    data class VodEpisode(val playId: String, val number: Int)

    private fun vodHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept" to "application/json, */*",
        "Referer" to VOD_REFERER,
    )

    internal fun decryptVodJson(text: String): org.json.JSONObject? = runCatching {
        val standard = Base64Kit.fromCustom(text.replace(Regex("\\s+"), ""))
        val raw = Base64Kit.decode(standard)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(VOD_KEY, "AES"),
            javax.crypto.spec.IvParameterSpec(VOD_IV),
        )
        org.json.JSONObject(String(cipher.doFinal(raw), Charsets.UTF_8))
    }.getOrNull()

    internal suspend fun searchVod(keyword: String): List<VodHit> = try {
        val text = app.get(
            "$VOD_API/vod/search?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}",
            timeout = 12, headers = vodHeaders(),
        ).text
        val rows = decryptVodJson(text)?.optJSONArray("result") ?: return emptyList()
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VodHit(id, row.optString("title"), row.optString("year"), row.optString("type"))
        }
    } catch (e: Exception) {
        Log.w("IndStream", "vod search failed: ${e.message}")
        emptyList()
    }

    internal suspend fun vodEpisodes(id: String): List<VodEpisode> = try {
        val text = app.get("$VOD_API/vod/$id/detail", timeout = 12, headers = vodHeaders()).text
        val rows = decryptVodJson(text)?.optJSONObject("result")?.optJSONArray("episodes")
            ?: return emptyList()
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val playId = row.optString("playId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VodEpisode(playId, row.optString("episode").toIntOrNull() ?: (i + 1))
        }
    } catch (e: Exception) {
        Log.w("IndStream", "vod detail failed: ${e.message}")
        emptyList()
    }

    internal suspend fun vodStreams(
        id: String,
        playId: String,
    ): Pair<List<VodSource>, List<Pair<String, String>>> = try {
        val text = app.get("$VOD_API/vod/$id/episode/$playId", timeout = 12, headers = vodHeaders()).text
        val result = decryptVodJson(text)?.optJSONObject("result")
            ?: return emptyList<VodSource>() to emptyList()
        val sources = result.optJSONArray("sources")?.let { rows ->
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val url = row.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VodSource(url, row.optString("quality"), row.optString("type"))
            }
        } ?: emptyList()
        val subs = result.optJSONArray("track")?.let { rows ->
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val url = row.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                (row.optString("name").ifBlank { "English" }) to url
            }
        } ?: emptyList()
        sources to subs
    } catch (e: Exception) {
        Log.w("IndStream", "vod episode failed: ${e.message}")
        emptyList<VodSource>() to emptyList()
    }

    internal fun matchVodTitle(hits: List<VodHit>, title: String, year: Int?, wantType: String): VodHit? {
        fun norm(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
        val normalized = norm(title)
        if (normalized.length < 3) return null
        val typeOk: (VodHit) -> Boolean = { hit ->
            if (wantType == "movie") hit.type.equals("movie", true)
            else !hit.type.equals("movie", true)
        }
        val yearOk: (VodHit) -> Boolean = { hit ->
            year == null || year <= 0 || hit.year.toIntOrNull() == null || hit.year.toIntOrNull() == year
        }
        return hits.filter { typeOk(it) && yearOk(it) }.firstOrNull { norm(it.title) == normalized }
            ?: hits.filter { typeOk(it) && yearOk(it) }.firstOrNull {
                val candidate = norm(it.title)
                candidate.length >= 3 && (candidate.startsWith(normalized) || normalized.startsWith(candidate))
            }
    }

    internal fun vodPlaybackHeaders(): Map<String, String> = mapOf("Referer" to "$VOD_API/")

    internal fun vodPlaybackReferer(): String = "$VOD_API/"

    private suspend fun resolveOneTouchTv(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId ?: return emptyList()
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val meta = try {
            TmdbService.fetchMeta(id, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name ?: throw CleanMissException("no title for tmdb=$id (title-keyed API)")
        val year = meta?.year?.take(4)?.toIntOrNull()
        val hits = searchVod(title)
        if (hits.isEmpty()) throw CleanMissException("no search hits for '$title'")
        val hit = matchVodTitle(hits, title, year, type)
            ?: throw CleanMissException("no title match for '$title' in ${hits.size} rows")
        val eps = vodEpisodes(hit.id)
        if (eps.isEmpty()) throw CleanMissException("no episodes for '${hit.title}'")
        val wantEp = if (type == "movie") 1 else {
            if (season <= 1) episode.coerceAtLeast(1)
            else throw CleanMissException("multi-season absolute mapping unsupported (S$season)")
        }
        val ep = eps.firstOrNull { it.number == wantEp }
            ?: throw CleanMissException("episode $wantEp missing")
        val (sources, _) = vodStreams(hit.id, ep.playId)
        if (sources.isEmpty()) throw CleanMissException("no sources for '${hit.title}' ep ${ep.number}")
        return sources.map { source ->
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = source.url,
                isM3u8 = source.url.contains(".m3u8", true) || source.type.equals("hls", true),
                referer = vodPlaybackReferer(),
                qualityHint = releaseHeightOf(source.quality),
                extraHeaders = vodPlaybackHeaders(),
            )
        }.distinctBy { it.url }
    }

    private suspend fun isAnimationTitle(tmdbId: Int, type: String): Boolean {
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val genres = meta?.genreIds
        if (genres.isNullOrEmpty()) return true
        return genres.contains(ANIMATION_GENRE_ID)
    }

    private const val ANIME_CATALOG = "https://watchanimeworld.one"
    private const val ANIME_PLAYER = "https://play.zephyrix.org"
    private const val ANIME_INDEX = "https://hianime.at"
    private const val ANIME_EMBED = "ZokoAnime"
    private const val ANIME_EMBED_REFERER = "https://zokoanime.video/"
    private const val ANIME_OBF_KEY = "otaku-embed-v1"
    private const val ANIME_CATALOG_REFERER = "$ANIME_CATALOG/"

    data class AnimeCard(val url: String, val title: String, val movie: Boolean)
    data class AnimeRow(val id: String, val title: String, val kind: String)
    data class AnimeServer(val name: String, val type: String, val url: String)

    private val ANIME_EPISODE_RE = Regex("""/episode/[a-z0-9-]+?-(\d+)x(\d+)/""", RegexOption.IGNORE_CASE)
    private val ANIME_CARD_RE = Regex(
        """<article class="post[^"]*"(.*?)</article>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ANIME_TITLE_RE = Regex(
        """<h2 class="entry-title"[^>]*>(.*?)</h2>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ANIME_CARD_LINK_RE = Regex("""href="([^"]+)"[^>]*class="lnk-blk"""", RegexOption.IGNORE_CASE)
    private val ANIME_EPISODE_HREF_RE = Regex("""href="([^"]*/episode/[^"]+)"""", RegexOption.IGNORE_CASE)
    private val ANIME_SEASON_ATTR = Regex("""data-season="(\d+)"""")
    private val ANIME_POST_ATTR = Regex("""data-post="(\d+)"""")
    private val ANIME_LI_POST_ATTR = Regex("""id="post-(\d+)"""")
    private val ANIME_PLAYER_RE = Regex("""play\.zephyrix\.org/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    private val ANIME_ROW_RE = Regex("""<a\s[^>]*class="[^"]*dynamic-name[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANIME_HREF_ATTR = Regex("""href="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ANIME_TITLE_ATTR = Regex("""title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ANIME_KIND_RE = Regex("""<span class="fdi-item[^"]*">\s*([^<]*?)\s*<""", RegexOption.IGNORE_CASE)
    private val ANIME_EPISODE_TAG_RE = Regex("""<a\s[^>]*data-number="[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANIME_NUMBER_ATTR = Regex("""data-number="(\d+)"""", RegexOption.IGNORE_CASE)
    private val ANIME_ID_ATTR = Regex("""data-id="(\d+)"""", RegexOption.IGNORE_CASE)
    private val ANIME_SERVER_TAG_RE = Regex(
        """<div\s[^>]*class="[^"]*server-item[^"]*"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ANIME_SERVER_NAME_ATTR = Regex("""data-server-name="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ANIME_SERVER_TYPE_ATTR = Regex("""data-type="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ANIME_SERVER_HASH_ATTR = Regex("""data-hash="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val ANIME_BLOB_RE = Regex("""window\.__P\s*=\s*"([^"]+)"""")

    private fun animeHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to HttpKit.userAgent,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
    )

    private fun animeAjaxHeaders(referer: String): Map<String, String> =
        animeHeaders(referer) + ("X-Requested-With" to "XMLHttpRequest")

    internal fun animeUnescape(text: String): String = text
        .replace("&#039;", "'").replace("&#8217;", "'").replace("&#8211;", "-")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#038;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("<[^>]+>"), "")
        .trim()

    private fun animeTitleScore(query: String, candidate: String): Double =
        TitleMatch.titleVariants(query).maxOfOrNull { TitleMatch.titleDistance(it, candidate) } ?: 0.0

    internal fun animeTitleCovers(query: String, candidate: String): Boolean {
        val tokens = TitleMatch.normalizeTitle(candidate).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        return TitleMatch.titleVariants(query).any { variant ->
            val queryTokens = variant.split(' ').filter { it.isNotBlank() }
            queryTokens.isNotEmpty() && queryTokens.all { q -> tokens.any { it.startsWith(q) } }
        }
    }

    internal fun parseAnimeCards(html: String): List<AnimeCard> = ANIME_CARD_RE.findAll(html).mapNotNull { card ->
        val block = card.groupValues[1]
        val title = ANIME_TITLE_RE.find(block)?.groupValues?.get(1)?.let(::animeUnescape).orEmpty()
        val href = ANIME_CARD_LINK_RE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        if (title.isBlank() || href.isBlank()) return@mapNotNull null
        val movie = href.contains("/movies/")
        if (!movie && !href.contains("/series/")) return@mapNotNull null
        AnimeCard(href, title, movie)
    }.distinctBy { it.url }.toList()

    internal fun pickAnimeCard(cards: List<AnimeCard>, title: String, year: Int?, movie: Boolean): AnimeCard? =
        cards.filter { it.movie == movie }
            .filter { TitleMatch.yearMatches(year, TitleMatch.parseYear(it.title)) }
            .filter { animeTitleCovers(title, it.title) }
            .maxByOrNull { animeTitleScore(title, it.title) }
            ?.takeIf { animeTitleScore(title, it.title) >= 0.7 }

    internal fun animePostId(html: String): String? =
        ANIME_POST_ATTR.find(html)?.groupValues?.get(1) ?: ANIME_LI_POST_ATTR.find(html)?.groupValues?.get(1)

    internal fun animeSeasonNumbers(html: String): List<Int> =
        ANIME_SEASON_ATTR.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()

    internal fun animeEpisodeLinks(html: String): List<String> =
        ANIME_EPISODE_HREF_RE.findAll(html).map { it.groupValues[1] }.distinct().toList()

    internal fun animeEpisodeSeason(url: String): Int? =
        ANIME_EPISODE_RE.find(url)?.groupValues?.get(1)?.toIntOrNull()

    internal fun animeEpisodeNumber(url: String): Int? =
        ANIME_EPISODE_RE.find(url)?.groupValues?.get(2)?.toIntOrNull()

    internal fun pickAnimeEpisodeByNumber(links: List<String>, episode: Int): String? =
        links.firstOrNull { animeEpisodeNumber(it) == episode }

    internal fun pickAnimeEpisodeByPosition(links: List<String>, episode: Int): String? {
        if (episode <= 0) return null
        return links.sortedBy { animeEpisodeNumber(it) ?: Int.MAX_VALUE }.getOrNull(episode - 1)
    }

    internal fun animeBucketGuess(episode: Int, maxSeason: Int, maxEpisode: Int): Int {
        if (episode <= 1 || maxSeason <= 1 || maxEpisode <= 1) return 1
        val guess = 1.0 + (episode - 1).toDouble() * (maxSeason - 1) / (maxEpisode - 1)
        return guess.toInt().coerceIn(1, maxSeason)
    }

    internal fun animePlayerId(html: String): String? =
        ANIME_PLAYER_RE.find(html)?.groupValues?.get(1)

    internal suspend fun findAnimeEpisode(
        seriesHtml: String,
        season: Int,
        episode: Int,
        fetchSeason: suspend (Int) -> String?,
    ): String? {
        if (episode <= 0) return null
        val seasons = animeSeasonNumbers(seriesHtml)
        if (seasons.isEmpty()) return null
        val rendered = animeEpisodeLinks(seriesHtml)
        val renderedSeason = rendered.firstNotNullOfOrNull { animeEpisodeSeason(it) }
        val maxEpisode = rendered.mapNotNull { animeEpisodeNumber(it) }.maxOrNull() ?: 0
        val cache = HashMap<Int, List<String>>()
        if (renderedSeason != null) cache[renderedSeason] = rendered

        suspend fun links(seasonNumber: Int): List<String>? {
            if (seasonNumber !in seasons) return null
            cache[seasonNumber]?.let { return it }
            val html = fetchSeason(seasonNumber) ?: return null
            val parsed = animeEpisodeLinks(html)
            cache[seasonNumber] = parsed
            return parsed
        }

        val top = seasons.last()
        val candidates = LinkedHashSet<Int>()
        if (season > 1) candidates += season
        val guess = animeBucketGuess(episode, top, if (maxEpisode > 0) maxEpisode else episode)
        candidates += guess
        candidates += guess - 1
        candidates += guess + 1
        candidates += top
        candidates += 1

        var budget = 4
        for (seasonNumber in candidates) {
            if (seasonNumber !in seasons) continue
            if (!cache.containsKey(seasonNumber)) {
                if (budget <= 0) continue
                budget--
            }
            val list = links(seasonNumber) ?: continue
            if (seasonNumber == season && season > 1) {
                pickAnimeEpisodeByPosition(list, episode)?.let { return it }
            }
            pickAnimeEpisodeByNumber(list, episode)?.let { return it }
        }
        return null
    }

    private suspend fun searchAnimeCards(keyword: String): List<AnimeCard> = try {
        parseAnimeCards(
            app.get(
                "$ANIME_CATALOG/?s=${java.net.URLEncoder.encode(keyword, "UTF-8")}",
                timeout = 15, headers = animeHeaders(ANIME_CATALOG_REFERER),
            ).text,
        )
    } catch (e: Exception) {
        Log.w("IndStream", "anime catalog search failed: ${e.message}")
        emptyList()
    }

    private suspend fun fetchAnimePage(url: String): String? = try {
        app.get(url, timeout = 15, headers = animeHeaders(ANIME_CATALOG_REFERER)).text
    } catch (e: Exception) {
        Log.w("IndStream", "anime page failed ($url): ${e.message}")
        null
    }

    private suspend fun animeSeasonHtml(post: String, season: Int): String? = try {
        app.get(
            "$ANIME_CATALOG/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$post",
            timeout = 12, headers = animeAjaxHeaders(ANIME_CATALOG_REFERER),
        ).text
    } catch (e: Exception) {
        Log.w("IndStream", "anime season $season failed: ${e.message}")
        null
    }

    private suspend fun animePlayerStream(playerId: String): String? = try {
        val body = app.post(
            "$ANIME_PLAYER/player/index.php?data=$playerId&do=getVideo",
            timeout = 15,
            headers = mapOf(
                "User-Agent" to HttpKit.userAgent,
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to ANIME_PLAYER,
                "Referer" to ANIME_CATALOG_REFERER,
            ),
            data = mapOf("hash" to playerId, "r" to ANIME_CATALOG_REFERER),
        ).text
        val json = org.json.JSONObject(body)
        json.optString("securedLink").takeIf { it.isNotBlank() }
            ?: json.optString("videoSource").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w("IndStream", "anime player failed: ${e.message}")
        null
    }

    internal fun parseAnimeRows(html: String): List<AnimeRow> = ANIME_ROW_RE.findAll(html).mapNotNull { match ->
        val tag = match.value
        val href = ANIME_HREF_ATTR.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        val id = Regex("""-(\d+)/?$""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = animeUnescape(ANIME_TITLE_ATTR.find(tag)?.groupValues?.get(1).orEmpty())
        if (title.isBlank()) return@mapNotNull null
        val tail = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 700))
        AnimeRow(id, title, ANIME_KIND_RE.find(tail)?.groupValues?.get(1)?.trim().orEmpty())
    }.distinctBy { it.id }.toList()

    internal fun pickAnimeRow(rows: List<AnimeRow>, title: String, movie: Boolean): AnimeRow? {
        fun kindScore(row: AnimeRow): Int = when {
            movie && row.kind.equals("MOVIE", true) -> 2
            !movie && row.kind.equals("TV", true) -> 2
            movie || row.kind.equals("MOVIE", true) -> 0
            else -> 1
        }
        return rows.filter { animeTitleCovers(title, it.title) }
            .maxWithOrNull(compareBy({ kindScore(it) }, { animeTitleScore(title, it.title) }))
            ?.takeIf { animeTitleScore(title, it.title) >= 0.7 }
    }

    internal fun parseAnimeEpisodes(html: String): List<Pair<Int, String>> =
        ANIME_EPISODE_TAG_RE.findAll(html).mapNotNull { match ->
            val number = ANIME_NUMBER_ATTR.find(match.value)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val id = ANIME_ID_ATTR.find(match.value)?.groupValues?.get(1) ?: return@mapNotNull null
            number to id
        }.toList()

    internal fun parseAnimeServers(html: String): List<AnimeServer> =
        ANIME_SERVER_TAG_RE.findAll(html).mapNotNull { match ->
            val tag = match.value
            val url = decodeAnimeHash(ANIME_SERVER_HASH_ATTR.find(tag)?.groupValues?.get(1).orEmpty())
                ?: return@mapNotNull null
            AnimeServer(
                ANIME_SERVER_NAME_ATTR.find(tag)?.groupValues?.get(1)?.trim().orEmpty(),
                ANIME_SERVER_TYPE_ATTR.find(tag)?.groupValues?.get(1)?.trim().orEmpty(),
                url,
            )
        }.toList()

    internal fun decodeAnimeHash(hash: String): String? = try {
        String(java.util.Base64.getDecoder().decode(hash.trim()), Charsets.UTF_8)
            .takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        null
    }

    internal fun animePlayerBlob(html: String): String? =
        ANIME_BLOB_RE.find(html)?.groupValues?.get(1)

    internal fun xorDecode(blob: String, key: String = ANIME_OBF_KEY): String? = try {
        if (key.isEmpty()) null else {
            val raw = java.util.Base64.getDecoder().decode(blob.trim())
            String(xor(raw, key), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    internal fun xorEncode(json: String, key: String = ANIME_OBF_KEY): String =
        java.util.Base64.getEncoder().encodeToString(xor(json.toByteArray(Charsets.UTF_8), key))

    private fun xor(raw: ByteArray, key: String): ByteArray =
        ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.length].code).toByte() }

    internal fun animePlayerConfig(blob: String): org.json.JSONObject? = try {
        xorDecode(blob)?.let { org.json.JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    private suspend fun searchAnimeRows(keyword: String): List<AnimeRow> = try {
        parseAnimeRows(
            app.get(
                "$ANIME_INDEX/search?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}",
                timeout = 15, headers = animeHeaders("$ANIME_INDEX/"),
            ).text,
        )
    } catch (e: Exception) {
        Log.w("IndStream", "anime index search failed: ${e.message}")
        emptyList()
    }

    private suspend fun animeEpisodeList(animeId: String): List<Pair<Int, String>> = try {
        parseAnimeEpisodes(
            org.json.JSONObject(
                app.get(
                    "$ANIME_INDEX/api/theme/episode/list/$animeId",
                    timeout = 15, headers = animeAjaxHeaders("$ANIME_INDEX/"),
                ).text,
            ).optString("html"),
        )
    } catch (e: Exception) {
        Log.w("IndStream", "anime index episode list failed: ${e.message}")
        emptyList()
    }

    private suspend fun animeServerList(episodeId: String): List<AnimeServer> = try {
        parseAnimeServers(
            org.json.JSONObject(
                app.get(
                    "$ANIME_INDEX/api/theme/episode/servers?episodeId=$episodeId",
                    timeout = 15, headers = animeAjaxHeaders("$ANIME_INDEX/"),
                ).text,
            ).optString("html"),
        )
    } catch (e: Exception) {
        Log.w("IndStream", "anime index server list failed: ${e.message}")
        emptyList()
    }

    private suspend fun animeStreamConfig(streamUrl: String): org.json.JSONObject? = try {
        animePlayerBlob(
            app.get(
                streamUrl,
                timeout = 15,
                headers = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to ANIME_EMBED_REFERER),
            ).text,
        )?.let(::animePlayerConfig)
    } catch (e: Exception) {
        Log.w("IndStream", "anime index player failed: ${e.message}")
        null
    }

    private suspend fun resolveAnimeWorld(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val meta = TmdbService.fetchMeta(tmdbId, type)
            ?: throw CleanMissException("no TMDB meta for $tmdbId")
        val title = meta.name ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val year = meta.year?.take(4)?.toIntOrNull()
        val movie = type == "movie"
        val hits = searchAnimeCards(title)
        if (hits.isEmpty()) throw CleanMissException("no search hits for '$title'")
        val hit = pickAnimeCard(hits, title, year, movie)
            ?: throw CleanMissException("no title match for '$title' in ${hits.size} rows")
        val seriesHtml = fetchAnimePage(hit.url)
            ?: throw CleanMissException("page fetch failed: ${hit.url.take(90)}")

        val pageUrl: String
        if (movie) {
            pageUrl = hit.url
        } else {
            val post = animePostId(seriesHtml)
                ?: throw CleanMissException("no post id on ${hit.url.take(90)}")
            pageUrl = findAnimeEpisode(seriesHtml, season, episode) { seasonNumber ->
                animeSeasonHtml(post, seasonNumber)
            } ?: throw CleanMissException("episode S${season}E$episode missing on '${hit.title}'")
        }
        val pageHtml = if (pageUrl == hit.url) seriesHtml else fetchAnimePage(pageUrl)
            ?: throw CleanMissException("episode fetch failed: ${pageUrl.take(90)}")
        val playerId = animePlayerId(pageHtml)
            ?: throw CleanMissException("no player on ${pageUrl.take(90)}")
        val masterUrl = animePlayerStream(playerId)
            ?: throw CleanMissException("player returned no source")
        val playReferer = "$ANIME_PLAYER/"
        val (label, height) = probeAudioHeight(masterUrl, playReferer)
        return listOf(
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = masterUrl, isM3u8 = true,
                referer = playReferer,
                qualityHint = height,
                measuredKbps = HttpKit.probeSpeed(masterUrl, playReferer),
                audioPriority = if (label.contains("Hindi")) 4 else 3,
                audioLabel = label.ifBlank { "Multi" },
                extraHeaders = mapOf("Referer" to playReferer),
            ),
        )
    }

    private suspend fun resolveHiAnime(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val meta = TmdbService.fetchMeta(tmdbId, type)
            ?: throw CleanMissException("no TMDB meta for $tmdbId")
        val title = meta.name ?: throw CleanMissException("no title for tmdb=$tmdbId")
        val movie = type == "movie"
        val hits = searchAnimeRows(title)
        if (hits.isEmpty()) throw CleanMissException("no search hits for '$title'")
        val hit = pickAnimeRow(hits, title, movie)
            ?: throw CleanMissException("no title match for '$title' in ${hits.size} rows")
        val eps = animeEpisodeList(hit.id)
        if (eps.isEmpty()) throw CleanMissException("no episodes for '${hit.title}'")
        val want = if (movie) eps.first().first else absoluteEpisode(tmdbId, season, episode)
        val ep = eps.firstOrNull { it.first == want }
            ?: throw CleanMissException("episode $want missing on '${hit.title}' (${eps.size} listed)")
        val servers = animeServerList(ep.second)
            .filter { it.name.equals(ANIME_EMBED, ignoreCase = true) }
        if (servers.isEmpty()) throw CleanMissException("no embed server for ep ${ep.second}")
        val out = mutableListOf<RawStream>()
        for (s in servers) {
            val cfg = animeStreamConfig(s.url) ?: continue
            val src = cfg.optString("src").takeIf { it.isNotBlank() } ?: continue
            val dub = s.type.equals("dub", ignoreCase = true)
            val label = if (dub) "English" else "Japanese"
            out += RawStream(
                serverId = spec.id, serverName = "${spec.name} $label",
                url = src, isM3u8 = src.contains(".m3u8", true),
                referer = ANIME_EMBED_REFERER,
                audioPriority = if (dub) 1 else 2,
                audioLabel = label,
                extraHeaders = mapOf(
                    "Referer" to ANIME_EMBED_REFERER,
                    "User-Agent" to HttpKit.userAgent,
                ),
            )
        }
        if (out.isEmpty()) throw CleanMissException("player config carried no source for '${hit.title}' ep ${ep.first}")
        return out.distinctBy { it.url }
    }

    private suspend fun absoluteEpisode(tmdbId: Int, season: Int, episode: Int): Int {
        if (season <= 1) return episode.coerceAtLeast(1)
        val prior = TmdbService.fetchSeasons(tmdbId, (1 until season).toList())
        val counted = prior.count { it.seasonNumber < season && it.episodeNumber > 0 }
        return if (counted > 0) counted + episode else episode
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
            catching { app.get(url, timeout = 15, headers = headers).text }.getOrNull()
        } ?: run { Log.w("VidCore", "no API response"); return emptyList() }
        val root = catching { org.json.JSONObject(jsonText) }.getOrElse {
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
                qualityHint = Regex("""(\d{3,4})""").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                extraHeaders = streamHeaders,
            )
        }
        Log.d("VidCore", "parsed ${out.size} sources")
        return out
    }

    internal val BINGR_SRVS = listOf("s40", "s70", "s80", "s81", "s62", "s63", "s30", "s60", "s61", "s31", "s3")

    internal data class BingrSource(val url: String, val quality: String, val label: String, val language: String)

    internal fun bingrBody(
        srv: String, type: String, id: String, title: String, year: String, season: Int, episode: Int,
    ): Map<String, Any?> {
        val query = LinkedHashMap<String, Any?>()
        query["title"] = title
        query["year"] = year
        if (type == "tv") { query["season"] = season; query["episode"] = episode }
        return mapOf("srv" to srv, "t" to (if (type == "tv") "tv" else "movie"), "id" to id, "query" to query)
    }

    internal fun bingrSources(jsonText: String): List<BingrSource> {
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<BingrSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            out += BingrSource(
                url = url,
                quality = o.optString("quality", ""),
                label = o.optString("label", ""),
                language = o.optString("language", ""),
            )
        }
        return out
    }

    internal fun heightHintOf(text: String): Int {
        Regex("""(\d{3,4})[pP]""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            text.contains("4k", true) || text.contains("2160", true) -> 2160
            text.contains("1440", true) -> 1440
            else -> 0
        }
    }

    private suspend fun resolveBingr(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val id = tmdbId.toString()
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val title = meta?.name.orEmpty()
        val year = meta?.year.orEmpty()
        val api = "https://api.bingr.one/api/stream"
        val postHeaders = mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://bingr.one",
            "Referer" to "https://bingr.one/",
            "User-Agent" to HttpKit.userAgent,
        )
        val out = LinkedHashMap<String, RawStream>()
        val deadline = System.currentTimeMillis() + 22_000L
        for (srv in BINGR_SRVS) {
            if (out.size >= 6 || System.currentTimeMillis() > deadline) break
            val text = withTimeoutOrNull(6_000L) {
                catching {
                    if (srv == "s40" && type == "tv") {
                        app.get("$api/aphelion-tv/$id/$season/$episode", timeout = 6,
                            headers = okHeaders("https://bingr.one/")).text
                    } else {
                        app.post(api, timeout = 6, headers = postHeaders,
                            json = bingrBody(srv, type, id, title, year, season, episode)).text
                    }
                }.getOrNull()
            } ?: continue
            for (s in bingrSources(text)) {
                if (out.containsKey(s.url)) continue
                val isDash = s.url.contains(".mpd", true)
                val adaptive = s.url.contains(".m3u8", true) || isDash
                out[s.url] = RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url, isM3u8 = adaptive, isDash = isDash,
                    referer = "https://bingr.one/",
                    qualityHint = heightHintOf(if (s.quality.isNotBlank()) s.quality else s.label),
                    audioLabel = s.language,
                )
            }
        }
        Log.d("Bingr", "cascade got ${out.size} sources for $type/$id")
        return out.values.toList()
    }


    private suspend fun resolveVidrift(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val base = "https://embed.vidrift.net"
        val page = if (type == "movie") "$base/embed/movie/$tmdbId"
        else "$base/embed/tv/$tmdbId/$season/$episode"
        val html = withTimeoutOrNull(12_000L) {
            catching { app.get(page, timeout = 12, headers = okHeaders("$base/")).text }.getOrNull()
        } ?: run { Log.w("Vidrift", "embed no response"); return emptyList() }
        val found = vidriftStreams(html)
        val out = found.map { (rel, label) ->
            val abs = if (rel.startsWith("http", true)) rel else base + rel
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = abs, isM3u8 = abs.contains(".m3u8", true),
                referer = "$base/", qualityHint = 0, audioLabel = label,
                extraHeaders = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to "$base/"),
            )
        }
        Log.d("Vidrift", "parsed ${out.size} streams for $type/$tmdbId")
        return out
    }

    internal fun vidriftStreams(html: String): List<Pair<String, String>> {
        val mi = html.indexOf("var embedMeta")
        val brace = if (mi >= 0) html.indexOf('{', mi) else -1
        val json = if (brace >= 0) extractBalancedJson(html, brace) else null
        val meta = json?.let { catching { org.json.JSONObject(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        meta.optJSONArray("orionStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (!u.contains(".m3u8", true) && !u.contains(".mp4", true)) continue
                out += u to vidriftLabel(o.optString("name"))
            }
        }
        if (out.isEmpty()) {
            meta.optString("evionUrl").takeIf { it.isNotBlank() && it.contains(".m3u8", true) }?.let { out += it to "" }
        }
        return out
    }

    private fun vidriftLabel(name: String): String {
        val tok = name.split(Regex("[^A-Za-z]+")).lastOrNull { it.isNotBlank() } ?: return ""
        return when (tok.lowercase()) {
            "hindi", "tamil", "telugu", "english", "kannada", "malayalam", "bengali", "marathi", "punjabi", "original" ->
                tok.replaceFirstChar { it.uppercase() }
            else -> ""
        }
    }


    private val RIVESTREAM_SALT = listOf(
        "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq",
        "o9zuXQ", "4Aebt", "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0",
        "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL", "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf",
        "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG", "W81hjts92", "rJhAT",
        "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1",
        "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g", "B5jXjMCSx", "cOzZBZTV", "FTXGy",
        "Dfh1q1", "ny9jqZ2POI", "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye", "3YUJnmxp", "1fv5Imona",
        "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU", "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa",
        "7UjkKrp", "Yvhrj", "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM",
    )

    private val RIVESTREAM_SERVICES_FALLBACK = listOf(
        "citadel", "apogee", "vanguard", "mirage", "velocity", "ignite", "tampa", "meridian", "apollo", "asiacloud",
    )
    @Volatile private var riveServices: List<String>? = null
    @Volatile private var riveServicesAt = 0L
    private const val RIVE_SERVICES_TTL_MS = 30 * 60 * 1000L

    private fun riveHex(t: Int): String {
        val s = if (t >= 0) t.toString(16) else "-" + (-t.toLong()).toString(16)
        return s.padStart(8, '0')
    }

    private fun riveHash2(s: String): String {
        var t = 0
        for (n in s.indices) {
            val r = s[n].code
            t = r + (t shl 6) + (t shl 16) - t
            val sh = n % 5
            val i = (t shl sh) or (t ushr (32 - sh))
            t = t xor (i xor ((r shl (n % 7)) or (r ushr (8 - (n % 7)))))
            t += (t ushr 11) xor (t shl 3)
        }
        t = t xor (t ushr 15)
        t = (65535 and t) * 49842 + ((((t ushr 16) * 49842) and 65535) shl 16)
        t = t xor (t ushr 13)
        t = (65535 and t) * 40503 + ((((t ushr 16) * 40503) and 65535) shl 16)
        t = t xor (t ushr 16)
        return riveHex(t)
    }

    private fun riveHashO(s: String): String {
        var n = 0xDEADBEEF.toInt() xor s.length
        for (e in s.indices) {
            val r = s[e].code
            var rMod = r
            rMod = rMod xor (((131 * e + 89) xor (rMod shl (e % 5))) and 255)
            n = ((n shl 7) or (n ushr 25)) xor rMod
            n = (65535 and n) * 60205 + (((n ushr 16) * 60205) shl 16)
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = (65535 and n) * 49842 + (((n ushr 16) * 49842) shl 16)
        n = n xor (n ushr 13)
        n = (65535 and n) * 40503 + (((n ushr 16) * 40503) shl 16)
        n = n xor (n ushr 16)
        n = (65535 and n) * 10196 + (((n ushr 16) * 10196) shl 16)
        n = n xor (n ushr 15)
        return riveHex(n)
    }

    internal fun rivestreamSecretKey(id: String): String {
        return try {
            val num = id.toIntOrNull()
            val salt: String
            val n: Int
            if (num == null) {
                val sum = id.sumOf { it.code }
                salt = RIVESTREAM_SALT[sum % RIVESTREAM_SALT.size]
                n = (sum % id.length) / 2
            } else {
                salt = RIVESTREAM_SALT[((num % RIVESTREAM_SALT.size) + RIVESTREAM_SALT.size) % RIVESTREAM_SALT.size]
                n = ((num % id.length) + id.length) % id.length / 2
            }
            val compound = id.substring(0, n) + salt + id.substring(n)
            Base64Kit.encode(riveHashO(riveHash2(compound)).toByteArray(Charsets.US_ASCII))
        } catch (e: Exception) { "topSecret" }
    }

    internal data class RiveSource(val url: String, val quality: String, val label: String, val format: String)

    internal fun rivestreamSources(jsonText: String): List<RiveSource> {
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<RiveSource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            out += RiveSource(url, o.optString("quality", ""), o.optString("source", ""), o.optString("format", ""))
        }
        return out
    }

    internal fun rivestreamServicesFrom(jsonText: String?): List<String> {
        val root = jsonText?.let { catching { org.json.JSONObject(it) }.getOrNull() } ?: return emptyList()
        val arr = root.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }

    internal fun rivestreamAudio(quality: String): String {
        val parts = quality.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return ""
        val tok = parts.last()
        return if (tok.length in 3..12 && tok.all { it.isLetter() }) tok.replaceFirstChar { it.uppercase() } else ""
    }

    internal fun rivestreamProxyHeaders(url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val raw = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("headers=") }?.substringAfter("headers=")
            ?.takeIf { it.isNotBlank() } ?: return out
        val json = catching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return out
        val obj = catching { org.json.JSONObject(json) }.getOrNull() ?: return out
        obj.keys().forEach { k -> val v = obj.optString(k); if (v.isNotBlank()) out[k] = v }
        return out
    }

    private suspend fun rivestreamServices(): List<String> {
        val now = System.currentTimeMillis()
        riveServices?.let { if (now - riveServicesAt < RIVE_SERVICES_TTL_MS) return it }
        val url = "https://www.rivestream.app/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=undefined"
        val text = withTimeoutOrNull(6_000L) {
            catching { app.get(url, timeout = 6, headers = okHeaders("https://www.rivestream.app/")).text }.getOrNull()
        }
        val list = rivestreamServicesFrom(text).ifEmpty { RIVESTREAM_SERVICES_FALLBACK }
        riveServices = list; riveServicesAt = now
        return list
    }

    private suspend fun resolveRivestream(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val id = tmdbId.toString()
        val key = rivestreamSecretKey(id)
        val api = "https://www.rivestream.app/api/backendfetch"
        val ref = "https://www.rivestream.app/"
        val rid = if (type == "movie") "movieVideoProvider" else "tvVideoProvider"
        val out = LinkedHashMap<String, RawStream>()
        val deadline = System.currentTimeMillis() + 18_000L
        for (svc in rivestreamServices()) {
            if (out.size >= 8 || System.currentTimeMillis() > deadline) break
            var url = "$api?requestID=$rid&id=$id"
            if (type != "movie") url += "&season=$season&episode=$episode"
            url += "&service=$svc&secretKey=$key&proxyMode=noProxy"
            val text = withTimeoutOrNull(7_000L) {
                catching { app.get(url, timeout = 7, headers = okHeaders(ref)).text }.getOrNull()
            } ?: continue
            for (s in rivestreamSources(text)) {
                if (out.containsKey(s.url)) continue
                val extra = LinkedHashMap<String, String>()
                extra["User-Agent"] = HttpKit.userAgent
                extra.putAll(rivestreamProxyHeaders(s.url))
                if (!extra.containsKey("Referer")) extra["Referer"] = ref
                out[s.url] = RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url,
                    isM3u8 = s.url.contains(".m3u8", true) || s.format.equals("hls", true),
                    referer = extra["Referer"], qualityHint = heightHintOf(s.quality),
                    audioLabel = rivestreamAudio(s.quality), extraHeaders = extra,
                )
            }
        }
        Log.d("Rivestream", "cascade got ${out.size} sources for $type/$id")
        return out.values.toList()
    }


    private val MOVY_GOLDEN = 0x9e3779b9.toInt()
    private val MOVY_MAGIC = byteArrayOf(109, 118, 109, 49)
    private val MOVY_LANES = listOf(
        "denver", "atlanta", "phoenix", "portland", "seattle", "miami", "boise", "paris",
        "cancun", "austin", "dallas", "delhi", "munich", "orlando", "tampa", "berlin",
    )
    private val MOVY_LANGS = setOf(
        "hindi", "tamil", "telugu", "english", "spanish", "french", "german",
        "italian", "portuguese", "korean", "japanese", "malayalam", "kannada",
    )

    private fun u32(v: Int): Long = v.toLong() and 0xFFFFFFFFL

    private fun movyFmix(h0: Int): Int {
        var h = h0
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    private fun movyRotl(x: Int, amount0: Int): Int {
        val amount = amount0 and 31
        return if (amount == 0) x else (x shl amount) or (x ushr (32 - amount))
    }

    private fun movyKeyedFnV(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (c in value) hash = (hash xor c.code) * 0x1000193
        return movyFmix(hash)
    }

    private class MovyKeystream(val s: HashMap<Int, Int>, var acc: Int)

    private fun movyInitKeystream(seed: String, mediaId: Int): MovyKeystream {
        val s = HashMap<Int, Int>()
        var n = movyFmix(movyKeyedFnV(seed) xor movyFmix(mediaId xor MOVY_GOLDEN))
        for (i in 0 until 8) {
            val slot = (u32(n) % 61).toInt()
            n = movyRotl(n + MOVY_GOLDEN, 7 + (7 and i))
            s[slot] = n xor movyFmix(n)
            n = movyFmix(n + slot)
        }
        return MovyKeystream(s, movyFmix(0xa5a5a5a5.toInt() xor n))
    }

    private fun movyNextWord(ks: MovyKeystream, counter: Int): Int {
        val s = ks.s
        val acc = ks.acc
        val slot = (u32(acc) % 61).toInt()
        val inTable = if (s.containsKey(slot)) -1 else 0
        val r = s[slot] ?: 0
        val mixed = r xor (MOVY_GOLDEN * (counter + 1))
        val b = (acc xor mixed) or (acc and mixed and inTable)
        val next = movyFmix(
            (movyRotl(b + acc, 31 and slot) xor movyRotl(acc, 31 and (slot * 7))) + MOVY_GOLDEN
        )
        s[slot] = next
        ks.acc = next
        return next
    }

    private fun movyBase64Url(payload: String): ByteArray {
        val normalized = payload.replace('-', '+').replace('_', '/')
        return Base64Kit.decode(normalized)
    }

    internal fun movyDecrypt(payload: String, seed: String, mediaId: Int): String? {
        return try {
            val cipher = movyBase64Url(payload)
            val ks = movyInitKeystream(seed, mediaId)
            val keystream = ByteArray(cipher.size)
            var counter = 0
            var i = 0
            while (i < cipher.size) {
                val word = movyNextWord(ks, counter++)
                if (i < cipher.size) keystream[i++] = (word and 0xFF).toByte()
                if (i < cipher.size) keystream[i++] = ((word ushr 8) and 0xFF).toByte()
                if (i < cipher.size) keystream[i++] = ((word ushr 16) and 0xFF).toByte()
                if (i < cipher.size) keystream[i++] = ((word ushr 24) and 0xFF).toByte()
            }
            for (j in MOVY_MAGIC.indices) {
                if (j >= cipher.size || (cipher[j].toInt() xor keystream[j].toInt()).toByte() != MOVY_MAGIC[j]) return null
            }
            val plain = ByteArray(cipher.size)
            for (j in cipher.indices) plain[j] = (cipher[j].toInt() xor keystream[j].toInt()).toByte()
            String(plain, MOVY_MAGIC.size, plain.size - MOVY_MAGIC.size, Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    internal data class MovySource(val url: String, val quality: String)

    internal fun movySources(plaintext: String): List<MovySource> {
        val root = catching { org.json.JSONObject(plaintext) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<MovySource>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
            out += MovySource(url, o.optString("quality", ""))
        }
        return out
    }

    private fun movyAudio(quality: String): String {
        val q = quality.trim().lowercase()
        return if (q in MOVY_LANGS) q.replaceFirstChar { it.uppercase() } else ""
    }

    private fun movyEncode(s: String): String {
        val once = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return java.net.URLEncoder.encode(once, "UTF-8").replace("+", "%20")
    }

    private suspend fun resolveMovy(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val base = "https://api.wecollege.net"
        val ref = "https://www.movy.sx/"
        val h = mapOf("Referer" to ref, "Origin" to "https://www.movy.sx", "User-Agent" to HttpKit.userAgent)
        val seedText = withTimeoutOrNull(6_000L) {
            catching { app.get("$base/seed?mediaId=$tmdbId", timeout = 6, headers = h).text }.getOrNull()
        } ?: run { Log.w("Movy", "seed no response"); return emptyList() }
        val seed = catching { org.json.JSONObject(seedText).optString("seed") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: run { Log.w("Movy", "no seed in response"); return emptyList() }
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val params = listOf(
            "title" to movyEncode(meta?.name.orEmpty()),
            "mediaType" to (if (type == "movie") "movie" else "tv"),
            "year" to meta?.year.orEmpty(),
            "totalSeasons" to "1",
            "seasonId" to (if (type == "movie") 1 else season).toString(),
            "episodeId" to (if (type == "movie") 1 else episode).toString(),
            "tmdbId" to tmdbId.toString(),
            "imdbId" to meta?.imdbId.orEmpty(),
        )
        val out = LinkedHashMap<String, RawStream>()
        val deadline = System.currentTimeMillis() + 18_000L
        for (lane in MOVY_LANES) {
            if (out.size >= 6 || System.currentTimeMillis() > deadline) break
            val query = params.joinToString("&") { "${it.first}=${it.second}" } + "&enc=2&seed=$seed"
            val cipher = withTimeoutOrNull(7_000L) {
                catching { app.get("$base/$lane/sources?$query", timeout = 7, headers = h).text }.getOrNull()
            } ?: continue
            val plain = movyDecrypt(cipher, seed, tmdbId) ?: continue
            for (s in movySources(plain)) {
                if (out.containsKey(s.url)) continue
                out[s.url] = RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url, isM3u8 = s.url.contains(".m3u8", true),
                    referer = ref, qualityHint = heightHintOf(s.quality),
                    audioLabel = movyAudio(s.quality),
                    extraHeaders = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to ref, "Origin" to "https://www.movy.sx"),
                )
            }
        }
        Log.d("Movy", "cascade got ${out.size} sources for $type/$tmdbId")
        return out.values.toList()
    }


    private suspend fun resolveBraflix(
        spec: ServerSpec, id: String, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val base = "https://braflix.dev"
        val path = if (type == "movie") "/api/play/movie/$id"
        else "/api/play/tv/$id/$season/$episode"
        val ref = "$base/"
        val headers = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to ref, "Origin" to base)
        val text = withTimeoutOrNull(12_000L) {
            catching { app.get("$base$path", timeout = 12, headers = headers).text }.getOrNull()
        } ?: run { Log.w("Braflix", "api no response"); return emptyList() }
        val src = braflixSource(text) ?: run { Log.w("Braflix", "no stream in response"); return emptyList() }
        val abs = if (src.stream.startsWith("http")) src.stream else base + src.stream
        Log.d("Braflix", "got 1 stream for $type/$id")
        return listOf(
            RawStream(
                serverId = spec.id, serverName = spec.name,
                url = abs, isM3u8 = true, isDash = true,
                referer = ref, qualityHint = heightHintOf(src.quality),
                extraHeaders = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to ref),
            )
        )
    }

    internal data class BraflixSource(val stream: String, val quality: String)

    internal fun braflixSource(jsonText: String): BraflixSource? {
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return null
        val result = root.optJSONObject("result") ?: return null
        val stream = result.optJSONObject("file")?.optString("stream")
            ?.takeIf { it.isNotBlank() } ?: return null
        val quality = result.optJSONObject("source")?.optString("quality").orEmpty()
        return BraflixSource(stream, quality)
    }

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
            catching { app.get(embedUrl, timeout = ((spec.timeoutSec - 2).coerceAtLeast(4)).toLong(), headers = okHeaders(referer)).text }.getOrNull()
        } ?: run { Log.w("VidEm", "embed page fetch failed"); return emptyList() }

        val qStart = pageText.indexOf("Q = {")
        if (qStart < 0) { Log.w("VidEm", "no Q object on page"); return emptyList() }
        val qJson = extractBalancedJson(pageText, qStart + 4) ?: run {
            Log.w("VidEm", "Q object unparseable"); return emptyList()
        }
        val q = catching { org.json.JSONObject(qJson) }.getOrElse {
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
                catching { app.get(playApi, timeout = 6, headers = okHeaders(referer)).text }.getOrNull()
            } ?: continue
            val playJson = catching { org.json.JSONObject(playJsonText) }.getOrElse { continue }
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
            catching { app.get(apiUrl, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return emptyList()

        val json = catching { org.json.JSONObject(jsonText) }.getOrNull()
        val streamUrl = json?.optString("url")?.takeIf { it.isNotBlank() }
            ?: json?.optString("stream")?.takeIf { it.isNotBlank() }
            ?: jsonText.trim().takeIf { it.startsWith("http") }

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

    private suspend fun resolve8Stream(
        spec: ServerSpec,
        imdbId: String?,
        type: String,
    ): List<RawStream> {
        val imdb = imdbId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (type != "movie") {
            throw CleanMissException("tv not supported (no episode targeting in the API)")
        }
        val base = "https://8-stream-api.vercel.app"
        val headers = okHeaders("$base/")
        val infoText = withTimeoutOrNull(8_000L) {
            catching { app.get("$base/api/v1/mediaInfo?id=$imdb", timeout = 8, headers = headers).text }.getOrNull()
        } ?: run { Log.w("8Stream", "no mediaInfo response"); return emptyList() }
        val root = catching { org.json.JSONObject(infoText) }.getOrElse {
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
                        catching {
                            app.post("$base/api/v1/getStream", timeout = 8, headers = headers,
                                json = mapOf("file" to file, "key" to key))
                        }.getOrNull()
                    } ?: return@async
                    val link = catching {
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

    internal fun allmovielandHosts(): List<String> =
        listOf("https://allmovieland.art", "https://allmovieland.one")

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

    internal fun amParseNodes(playlist: org.json.JSONArray): List<AmNode> = amNodeArr(playlist)

    private val allmovielandCardLinkRe = Regex(
        """<a\s+class="new-short__title--link"\s+href="(https?://allmovieland\.[a-z]+/[^"]+\.html)""" +
            """|href="(https?://allmovieland\.[a-z]+/[^"]+\.html)"\s*>\s*<h3 class="new-short__title""",
    )

    internal fun allmovielandCardUrl(html: String, titleNorm: String? = null): String? =
        allmovielandCardLinkRe.findAll(html)
            .map { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
            .firstOrNull { url ->
                if (titleNorm.isNullOrBlank()) return@firstOrNull true
                val tn = titleNorm.lowercase().replace(Regex("""[^a-z0-9]"""), "")
                val slug = Regex("""^\d+-""")
                    .replace(url.substringAfterLast('/').removeSuffix(".html"), "")
                    .lowercase().replace(Regex("""[^a-z0-9]"""), "")
                slug == tn || slug.startsWith(tn) ||
                    (tn.length >= 4 && slug.contains(tn))
            }

    internal fun amNodeIsSeason(n: AmNode, season: Int): Boolean {
        if (n.file.isNotBlank() || n.children.isEmpty()) return false
        if (n.children.any { it.file.isNotBlank() }) return false
        val seasonWord = n.title.contains("season", ignoreCase = true) ||
            n.title.contains("?????", ignoreCase = true) ||
            Regex("""(?i)\bs$season\b""").containsMatchIn(n.title)
        return (seasonWord && Regex("""\b$season\b""").containsMatchIn(n.title)) ||
            n.id == season.toString()
    }

    internal fun allmovielandEpisodeTitleMatches(title: String, episode: Int): Boolean {
        val e = "0*$episode"
        return Regex("""(?i)(?:^|[^a-z0-9])(?:episode|ep\.?|e\.?)[.\s_:=-]*$e(?!\d)""")
            .containsMatchIn(title) ||
            Regex("""(?i)s\d+[e]\s*$e(?![0-9])""").containsMatchIn(title) ||
            Regex("""x\s*$e(?!\d)""").containsMatchIn(title)
    }

    internal fun amNodeIsEpisode(n: AmNode, season: Int, episode: Int): Boolean {
        if (n.file.isNotBlank() || n.children.isEmpty()) return false
        if (amNodeIsSeason(n, episode) || amNodeIsSeason(n, season)) return false
        return n.episode == episode.toString() || n.id == "$season-$episode" ||
            allmovielandEpisodeTitleMatches(n.title, episode)
    }

    internal fun pickAllmovielandEpisodeNode(nodes: List<AmNode>, season: Int, episode: Int): AmNode? {
        val seasonNode = nodes.firstOrNull { amNodeIsSeason(it, season) }
        if (seasonNode != null) return seasonNode.children.firstOrNull { amNodeIsEpisode(it, season, episode) }
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

        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        val titleNorm = meta?.name?.lowercase()?.replace(Regex("""[^a-z0-9]"""), "")
        var answeredHost: String? = null
        var cardUrl: String? = null
        if (imdb != null) {
            for (host in allmovielandHosts()) {
                val html = withTimeoutOrNull(8_000L) {
                    catching {
                        app.get("$host/?do=search&subaction=search&story=$imdb",
                            timeout = 8, headers = okHeaders("$host/")).text
                    }.getOrNull()
                }
                if (html == null) { Log.w("Allmovieland", "host $host unreachable, trying next"); continue }
                answeredHost = host
                cardUrl = allmovielandCardUrl(html, titleNorm = null)
                if (cardUrl != null) break
            }
            if (cardUrl == null && titleNorm == null)
                throw IllegalStateException("allmovieland search timeout (network, all hosts)")
        }
        if (cardUrl == null && titleNorm != null) {
            val host = answeredHost ?: allmovielandHosts().first()
            val story = java.net.URLEncoder.encode(meta!!.name, "UTF-8")
            val html = withTimeoutOrNull(8_000L) {
                catching {
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

        val cardHtml = withTimeoutOrNull(6_000L) {
            catching { app.get(cardUrl, timeout = 6, headers = okHeaders(cardUrl)).text }.getOrNull()
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
                catching { app.get(pUrl, timeout = 5, headers = okHeaders(cardUrl)).text }.getOrNull()
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

        val fileUrl = when {
            file.startsWith("http") -> file
            file.startsWith("/playlist/") -> "$playerDomain$file"
            else -> "$playerDomain/playlist/$file"
        }
        val cdnBase = fileUrl.substringBefore("/playlist/")

        val playlistHeaders = okHeaders(playUrl).toMutableMap()
        playlistHeaders["X-Csrf-Token"] = key
        val playlistText = withTimeoutOrNull(6_000L) {
            catching { app.get(fileUrl, timeout = 6, headers = playlistHeaders).text }.getOrNull()
        } ?: run { Log.w("Allmovieland", "playlist timeout"); throw IllegalStateException("allmovieland playlist timeout (network)") }
        val playlist = catching { org.json.JSONArray(playlistText) }.getOrElse {
            Log.w("Allmovieland", "playlist not JSON array"); throw IllegalStateException("allmovieland playlist parse error") }

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

        val out = mutableListOf<RawStream>()
        val fetched = coroutineScope {
            langEntries.map { leaf ->
                async {
                    val langFile = leaf.file.replace("\\/", "/")
                    val langUrl = if (langFile.startsWith("http")) langFile
                    else "$cdnBase/playlist/$langFile.txt"
                    val m3u8Text = withTimeoutOrNull(5_000L) {
                        catching { app.get(langUrl, timeout = 5, headers = playlistHeaders).text }.getOrNull()
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
            throw CleanMissException("${langEntries.size} language playlists, none yielded an m3u8")
        }
        Log.d("Allmovieland", "got ${out.size} language streams from ${langEntries.size} entries " +
            "(series tree: ${type != "movie"})")
        return out
    }

    private suspend fun resolveJsonApi(
        spec: ServerSpec,
        apiUrl: String,
        referer: String,
    ): List<RawStream> {
        Log.d("IndStream", "${spec.id}: jsonApi GET $apiUrl")
        val jsonText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            catching {
                app.get(apiUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (jsonText.isNullOrBlank()) {
            Log.w("IndStream", "${spec.id}: no JSON response (timeout/HTTP error) from $apiUrl")
            return emptyList()
        }

        Log.d("IndStream", "${spec.id}: jsonApi response ${jsonText.length}B, preview=${safeSnippet(jsonText)}")

        val root = catching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("IndStream", "${spec.id}: non-JSON response from $apiUrl (${jsonText.length}B, starts: ${safeSnippet(jsonText)})")
            return emptyList()
        }
        val source = root.optJSONObject("source") ?: run {
            val nullSource = root.isNull("source")
            Log.w("IndStream", "${spec.id}: missing \"source\" (${if (nullSource) "null -- id not known to this server" else "absent"}); root keys=${namesOf(root)}")
            return emptyList()
        }

        val out = mutableListOf<RawStream>()

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

    private suspend fun unwrapPages(html: String, baseUrl: String, timeoutSec: Int): Pair<String, String> {
        var curHtml = html; var curUrl = baseUrl
        repeat(MAX_UNWRAP) {
            val src = firstIframeUrl(curHtml) ?: return curHtml to curUrl
            val resolved = relUrl(curUrl, src)
            if (resolved == curUrl) return curHtml to curUrl
            curUrl = resolved
            val next = withTimeoutOrNull((timeoutSec - 2).coerceAtLeast(3) * 1000L) {
                catching { app.get(curUrl, timeout = (timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(curUrl)).text }.getOrNull()
            } ?: return curHtml to curUrl
            if (next.isBlank()) return curHtml to curUrl
            curHtml = next
        }
        return curHtml to curUrl
    }

    private fun firstIframeUrl(html: String): String? {
        val attrs = listOf("src", "data-src", "data-lazy-src", "data-litespeed-src", "data-iframe-src", "data-url", "data-video")
        for (frame in Jsoup.parse(html).select("iframe")) {
            for (attr in attrs) {
                val v = frame.attr(attr).trim()
                if (v.isEmpty()) continue
                if (v.equals("about:blank", ignoreCase = true) || v.startsWith("javascript:", ignoreCase = true)) continue
                if (!v.startsWith("http", ignoreCase = true) && !v.startsWith("//") && !v.startsWith("/")) continue
                return v
            }
        }
        return null
    }

    private fun harvestUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/").replace("\\\"", "\"")
        return STREAM_REGEX.flatMap { r -> r.findAll(n).map { it.groupValues[0].trim('"', '\'') }.filter { it.startsWith("http") } }.distinct()
    }

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

    private fun namesOf(obj: org.json.JSONObject): String =
        obj.names()?.let { n ->
            (0 until n.length()).joinToString(",") { key -> sanitizeToken(n.optString(key)) }
        } ?: "none"

    private fun safeSnippet(text: String): String = sanitizeToken(text).take(40)

    private fun sanitizeToken(s: String): String = s.filterNot { it.isISOControl() }.take(64)

    private val lastFarmProbeAt = java.util.concurrent.atomic.AtomicLong(0L)
    private const val FARM_REPROBE_COOLDOWN_MS = 15_000L

    object FastStartCache {
        private const val TTL_MS = 5 * 60 * 1000L
        private const val MAX_ENTRIES = 64
        private data class Entry(val streams: List<RawStream>, val expiresAt: Long)
        private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()

        fun key(tmdbId: Int, type: String, season: Int, episode: Int) = "$tmdbId|$type|$season|$episode"

        fun get(key: String): List<RawStream>? {
            val e = map[key] ?: return null
            if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
            return e.streams
        }

        fun ageMs(key: String): Long {
            val e = map[key] ?: return -1L
            return (System.currentTimeMillis() - (e.expiresAt - TTL_MS)).coerceAtLeast(0)
        }

        fun put(key: String, streams: List<RawStream>) {
            if (streams.isEmpty()) return
            val now = System.currentTimeMillis()
            map.compute(key) { _, e ->
                val existing = e?.takeIf { now <= it.expiresAt }?.streams.orEmpty()
                Entry((existing + streams).distinctBy { it.url }, now + TTL_MS)
            }
            if (map.size > MAX_ENTRIES) {
                map.entries.removeIf { now > it.value.expiresAt }
                while (map.size > MAX_ENTRIES) {
                    map.entries.minByOrNull { it.value.expiresAt }
                        ?.let { map.remove(it.key) } ?: break
                }
            }
        }

        fun clear() = map.clear()
    }

    const val FAST_START_MAX_MS: Long = 45_000L

    const val LIVE_FILL_MS: Long = 90_000L
}





