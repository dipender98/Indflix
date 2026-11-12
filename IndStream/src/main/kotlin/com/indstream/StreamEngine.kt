package com.indstream


import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

object StreamEngine {

    private const val MAX_CONCURRENT = 32
    private const val MAX_UNWRAP = 4
    private const val ANIMATION_GENRE_ID = 16
    private const val SUBTITLE_PROBE_CONCURRENCY = 6
    private const val SUBTITLE_PROBE_TIMEOUT_MS = 2_500L

    @Volatile private var movieBoxToken: String? = null
    @Volatile private var movieBoxTokenAt: Long = 0L
    private const val MOVIEBOX_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MOVIEBOX_THROTTLE_RETRY_MS = 2_000L
    private val STREAM_REGEX = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
    )

    private val WHITESPACE = Regex("""\s+""")
    private val ORIGIN = Regex("""^https?://[^/]+""")
    private val NON_ALNUM_LOWER = Regex("""[^a-z0-9]""")
    private val MOVIEBOX_SEASON_END = Regex("""\s+S(\d+)(?:\s*-\s*S?(\d+))?$""", RegexOption.IGNORE_CASE)
    private val MOVIEBOX_SEASON_SUFFIX = Regex("""\s+S\d+(?:\s*-\s*S?\d+)?$""", RegexOption.IGNORE_CASE)
    private val MOVIEBOX_BRACKETS = Regex("""[\[(]([^\])]+)[\])]""", RegexOption.IGNORE_CASE)
    private val MOVIEBOX_BRACKET_REMOVE = Regex("""\s*[\(\[][^)\]]*[\)\]]""")
    private val MOVIEBOX_YEAR = Regex("""\s*\d{4}""")
    private val FILE_NAME_HEIGHT = Regex("""\[(\d{3,4})p\]""", RegexOption.IGNORE_CASE)
    private val HEIGHT_HINT = Regex("""(\d{3,4})[pP]""")
    private val HEIGHT_NUM = Regex("""(\d{3,4})""")
    private val HINDI_WORD = Regex("""\bHINDI\b""")
    private val TAMIL_WORD = Regex("""\bTAMIL\b""")
    private val TELUGU_WORD = Regex("""\bTELUGU\b""")
    private val MULTI_WORD = Regex("""\b(MULTI|DUAL|DUBBED)\b""")
    private val ENGLISH_WORD = Regex("""\b(ENGLISH|ENG)\b""")
    private val FOUR_K_WORD = Regex("""\b4K\b""")
    private val HUB_OTHER_LANG =
        Regex("""english|dual|multi|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|urdu|japanese|korean|chinese""")
    private val ARCHIVE_URL = Regex("""\.(?:zip|rar|7z|tar|gz)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
    private val BTN_CLASS = Regex("""\bbtn\b""")
    private val PIXELDRAIN_PAGE =
        Regex("""(?i)(https?://pixeldrain\.[a-z.]+)/(?:api/file|u)/([A-Za-z0-9_\-]+)""")
    private val PIXELDRAIN_SHORT = Regex("""pixeldrain\.[a-z]+/u/([a-zA-Z0-9]+)""")
    private val GDSHINE_SHORT = Regex("""gdshine\.[a-z.]+/s/([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    private val GOOGLE_VIDEO =
        Regex("""(https?://video-downloads\.googleusercontent\.com/[^"'\s<>]+)""")
    private val TOKEN_URL_ABS = Regex("""https?://[^"' ]*?(?:hubcloud|hubdrive)\.php\?[^"' ]+""")
    private val TOKEN_URL_REL = Regex("""(?:hubcloud|hubdrive)\.php\?[^"' ]+""")
    private val FILE_HOST_RE =
        Regex("""(?i)(hubcloud|hubdrive|gdflix|gdshine|hubcdn|pixeldrain|workers\.dev/0:|\.mkv|\.mp4|\.m3u8)""")
    private val QUALITY_TEXT_RE = Regex("""(?i)\b(2160p|1440p|1080p|720p|480p|4k)\b""")
    private val HUB_LANG_RE =
        Regex("""(?i)\b(hindi|english|tamil|telugu|malayalam|kannada|bengali|marathi|punjabi|dual|multi)\b""")
    private val DOWNLOAD_HEADING = Regex(
        """(?i)(\d{3,4}p\b|4K|\d+(?:\.\d+)?\s?(?:GB|MB)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""",
    )
    private val VCLOUD_BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val VCLOUD_ATOB = Regex(
        """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )
    private val BRACKET_LABEL = Regex("""\[([^\]]+)]""")
    private val R2_HOST = Regex("""(r2\.cloudflarestorage\.com|r2\.dev)""", RegexOption.IGNORE_CASE)
    private val GPDL_URL =
        Regex("""https?://[^"' ]*gpdl[^"' ]*hubcloud[^"' ]+""", RegexOption.IGNORE_CASE)
    private val VIDEO_EXT = Regex("""(?i)\.(?:mkv|mp4|m3u8)(?:[?#]|$)""")
    private val TAGGED_SEASON = Regex("""(?i)\bS\d{1,2}\b|\bSeason\s*\d""")
    private val HUB_QUALITY = Regex("""\b(2160p|1440p|1080p|720p|480p|4k)\b""", RegexOption.IGNORE_CASE)
    private val NON_CONTENT_PATH = Regex("""/(category|tag|author|page|feed|search)/""", RegexOption.IGNORE_CASE)
    private val NON_CONTENT_PATH_BASIC = Regex("""/(category|tag|author|page|search)/""", RegexOption.IGNORE_CASE)
    private val HEADING_TAG = Regex("""h[1-6]""")
    private val OG_TITLE = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val HTML_TITLE = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val HUB_GATEWAY_HOST = Regex("""greenmotors|hubcloud|hubdrive""", RegexOption.IGNORE_CASE)
    private val HUB_GATEWAY_HOST_ALT = Regex("""greenmotors|hubcdn|hubdrive|hubcloud""", RegexOption.IGNORE_CASE)
    private val SIZE_TOKEN = Regex("""\d+(?:\.\d+)?\s?(?:GB|MB)""", RegexOption.IGNORE_CASE)
    private val VCLOUD_EPISODE_TAG = Regex("""(?i)\bS\d{1,2}[\s._-]?E\d{1,3}\b""")
    private val HEADING_SIZE = Regex(
        """\[\s*(\d+(?:\.\d+)?\s*(?:GB|MB)(?:/E\b|/EP\b|/ZiP|/ZIP)?)\s*]""",
        RegexOption.IGNORE_CASE,
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
        val audioPriority: Int = 0,
        val audioLabel: String = "",
        val sizeLabel: String = "",
        val mediaLabel: String = "",
        val inlineManifest: String? = null,
        val extraHeaders: Map<String, String> = emptyMap(),
        val isDash: Boolean = false,
        val subtitles: List<SubtitleKit.Track> = emptyList(),
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
        val servers = candidates + listOfNotNull(StremioNuvioAddons.serverSpec())
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
                                    Log.i("IndStream", "${spec.id}: clean miss - ${t.message} (no breaker trip)")
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
                                    "${crash.javaClass.simpleName}: ${crash.message}")
                                if (spec.recordHealth) HealthMonitor.recordFailure(spec.id)
                            } else {
                                Log.w("IndStream", "${spec.id}: TIMEOUT - no result after ${spec.timeoutSec}s")
                                if (spec.recordHealth) HealthMonitor.recordFailure(spec.id)
                            }
                        }
                        val streams = outcome.orEmpty()
                        if (dual && streams.isEmpty() && crashed == null && !cleanMiss) {
                            Log.w("IndStream", "${spec.id}: no streams (recording failure)")
                            if (spec.recordHealth) HealthMonitor.recordFailure(spec.id)
                        }
                        if (streams.isNotEmpty()) {
                            if (dual && spec.recordHealth) HealthMonitor.recordSuccess(spec.id)
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
        onSubtitle: (suspend (SubtitleFile) -> Unit)? = null,
        onResolved: ((Map<String, String>) -> Unit)? = null,
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
            val masterText: String? = null,
        )
        val preResolved = coroutineScope {
            ranked.map { raw ->
                async {
                    when {
                        raw.isM3u8 && probeManifests -> {
                            val t0 = System.currentTimeMillis()
                            val masterText = fetchMasterText(raw)
                            val fetchMs = if (raw.inlineManifest != null) 0
                            else masterText?.let { System.currentTimeMillis() - t0 }
                            val h = (ManifestKit.bestHeightOf(masterText, raw.url).takeIf { it > 0 } ?: raw.qualityHint).coerceAtLeast(0)
                            val master = ManifestKit.parseMaster(masterText, raw.url)
                            // The manifest's own audio declaration is the most specific data; it
                            // overrides a coarser label stamped earlier in the chain.
                            val tag = manifestAudioTag(master).ifBlank { raw.audioLabel }
                            ResolvedEmit(raw, h, tag, fetchMs, masterText)
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
                            val tag = raw.audioLabel
                            ResolvedEmit(raw, h, tag, fetchMs)
                        }
                        else -> {
                            var fullHeight = if (!raw.isM3u8 && raw.qualityHint <= 0) -1 else raw.qualityHint
                            if (raw.isM3u8 && fullHeight < 0) fullHeight = 0
                            if (raw.isM3u8 && fullHeight <= 0 && !raw.inlineManifest.isNullOrBlank()) {
                                val h = ManifestKit.bestHeightOf(raw.inlineManifest, raw.url).takeIf { it > 0 } ?: 0
                                if (h > 0) fullHeight = h
                            }
                            val tag = raw.audioLabel
                            ResolvedEmit(raw, fullHeight, tag)
                        }
                    }
                }
            }.awaitAll()
        }
        if (onResolved != null && probeManifests) {
            val probed = preResolved
                .filter { it.tagLabel.isNotBlank() && it.tagLabel != it.raw.audioLabel }
                .associate { it.raw.url to it.tagLabel }
            if (probed.isNotEmpty()) onResolved(probed)
        }
        val ordered = preResolved.sortedBy { speedRankMs(it.fetchMs, it.tagLabel) }

        val floorDroppedByServer = HashMap<String, Int>()
        val emittedByServer = HashMap<String, Int>()

        ordered.forEach { r ->
            val raw = r.raw
            if (!passesQualityFloor(raw.isM3u8, r.fullHeight)) {
                floorDroppedByServer.merge(raw.serverId, 1, Int::plus)
                return@forEach
            }
            if (!emitted.add(raw.url)) return@forEach
            emittedByServer.merge(raw.serverId, 1, Int::plus)

            val linkHeaders = LinkedHashMap<String, String>()
            linkHeaders.putAll(raw.extraHeaders)
            val rawReferer = raw.referer
            if (!linkHeaders.containsKey("Referer") && !rawReferer.isNullOrBlank()) {
                linkHeaders["Referer"] = rawReferer
            }

            val isAdaptive = raw.isM3u8
            val quality = r.fullHeight.takeIf { it > 0 }
                ?: LinkNaming.qualityHeight(raw.mediaLabel, raw.serverName)
            val label = LinkNaming.displayName(
                serverName = raw.serverName,
                audioLabel = r.tagLabel,
                sizeLabel = raw.sizeLabel.takeIf { it.isNotBlank() },
                mediaLabel = raw.mediaLabel.takeIf { it.isNotBlank() },
            )
            val sourceLabel = LinkNaming.priorityName(
                serverName = raw.serverName,
                audioLabel = r.tagLabel,
                mediaLabel = raw.mediaLabel.takeIf { it.isNotBlank() },
            )

            val linkType = if (raw.isDash) ExtractorLinkType.DASH
            else if (isAdaptive) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
            onLink(newExtractorLink(
                source = sourceLabel, name = label,
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
        if (onSubtitle != null) {
            emitSubtitles(liveSet, ordered.associate { it.raw.url to it.masterText }, onSubtitle)
        }
        return emitted
    }

    /** Warms the subtitle cache without emitting anything, used by background preloads. */
    suspend fun cacheSubtitles(streams: List<RawStream>) {
        emitSubtitles(streams, emptyMap(), null)
    }

    /**
     * Resolves manifest audio declarations and patches cached labels, so later cache
     * replays keep the accurate list instead of the coarser label stamped at harvest.
     */
    suspend fun warmAudioLabels(streams: List<RawStream>, cacheKey: String) {
        val targets = streams.filter { it.url.isNotBlank() && it.isM3u8 }
        if (targets.isEmpty()) return
        val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
        coroutineScope {
            targets.map { raw ->
                async {
                    warmLabelGate.acquire()
                    try {
                        val master = ManifestKit.parseMaster(fetchMasterText(raw), raw.url)
                        manifestAudioTag(master).takeIf { it.isNotBlank() }?.let { labels[raw.url] = it }
                    } finally {
                        warmLabelGate.release()
                    }
                }
            }.awaitAll()
        }
        FastStartCache.patchLabels(cacheKey, labels)
    }

    private val warmLabelGate = Semaphore(6)

    private fun manifestAudioTag(master: ManifestKit.MasterPlaylist?): String =
        master?.let {
            ManifestKit.audioLanguageLabel(it).orEmpty()
                .ifBlank { if (it.isMultiAudio) "Multi" else "" }
        }.orEmpty()

    private suspend fun fetchMasterText(raw: RawStream, timeoutSec: Int = 3): String? {
        val headers = LinkedHashMap<String, String>()
        headers.putAll(raw.extraHeaders)
        if (!headers.containsKey("Referer") && !raw.referer.isNullOrBlank()) headers["Referer"] = raw.referer
        return raw.inlineManifest ?: withTimeoutOrNull(timeoutSec * 1000L) {
            catching { app.get(raw.url, timeout = timeoutSec.toLong(), headers = headers).text }.getOrNull()
        }
    }

    private suspend fun emitSubtitles(
        streams: List<RawStream>,
        masterTexts: Map<String, String?>,
        onSubtitle: (suspend (SubtitleFile) -> Unit)?,
    ) {
        if (streams.isEmpty()) return
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val pending = mutableListOf<RawStream>()

        suspend fun deliver(raw: RawStream, tracks: List<SubtitleKit.Track>) {
            if (onSubtitle == null) return
            val headers = subtitleProbeHeaders(raw)
            for (track in tracks) {
                val url = track.url.trim()
                if (url.isEmpty() || !seen.add(url)) continue
                onSubtitle(subtitleFileOf(raw.serverName, track, headers))
            }
        }

        for (raw in streams) {
            val inline = raw.inlineManifest ?: masterTexts[raw.url]
            val fromMaster = SubtitleKit.fromMaster(ManifestKit.parseMaster(inline, raw.url), raw.url)
            val known = SubtitleKit.merge(
                SubtitleKit.merge(raw.subtitles, FastStartCache.subtitlesFor(raw.url)),
                fromMaster,
            )
            if (known.isEmpty() && raw.isM3u8 && !raw.isDash && !FastStartCache.subtitlesProbed(raw.url)) {
                pending += raw
                continue
            }
            deliver(raw, known)
        }
        if (pending.isEmpty()) return

        coroutineScope {
            val gate = Semaphore(SUBTITLE_PROBE_CONCURRENCY)
            pending.map { raw ->
                async {
                    gate.acquire()
                    try {
                        val text = withTimeoutOrNull(SUBTITLE_PROBE_TIMEOUT_MS) {
                            catching { app.get(raw.url, timeout = 3, headers = subtitleProbeHeaders(raw)).text }
                                .getOrNull()
                        }
                        if (text.isNullOrBlank()) return@async
                        val tracks = SubtitleKit.fromMaster(ManifestKit.parseMaster(text, raw.url), raw.url)
                        FastStartCache.markSubtitlesProbed(raw.url)
                        if (tracks.isEmpty()) return@async
                        FastStartCache.putSubtitles(raw.url, tracks)
                        deliver(raw, tracks)
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
    }

    private fun subtitleProbeHeaders(raw: RawStream): Map<String, String> {
        val headers = LinkedHashMap(raw.extraHeaders)
        val referer = raw.referer
        if (!headers.containsKey("Referer") && !referer.isNullOrBlank()) {
            headers["Referer"] = referer
        }
        return headers
    }

    internal fun subtitleFileOf(
        serverName: String,
        track: SubtitleKit.Track,
        headers: Map<String, String> = emptyMap(),
    ): SubtitleFile {
        val canonical = LinkNaming.canonicalSubtitleName(track.language)
        val file = SubtitleFile(LinkNaming.taggedSubtitleName(canonical, serverName), track.url)
        if (headers.isNotEmpty()) file.headers = headers
        return file
    }

    /** Attaches subtitle tracks found anywhere in a provider payload to every stream of that provider. */
    internal fun withJsonSubtitles(
        streams: List<RawStream>,
        payload: Any?,
        baseUrl: String,
    ): List<RawStream> {
        if (streams.isEmpty() || payload == null) return streams
        val tracks = SubtitleKit.fromJson(payload, baseUrl)
        if (tracks.isEmpty()) return streams
        return streams.map { it.copy(subtitles = SubtitleKit.merge(it.subtitles, tracks)) }
    }

    private suspend fun resolveSubtitleEndpoints(
        streams: List<RawStream>,
        roots: List<Any?>,
        baseUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): List<RawStream> {
        if (streams.isEmpty() || roots.isEmpty()) return streams
        val (cleaned, endpoints) = SubtitleKit.splitSubtitleEndpoints(roots)
        val direct = withJsonSubtitles(streams, org.json.JSONArray().apply { cleaned.forEach { put(it) } }, baseUrl)
        if (endpoints.isEmpty()) return direct
        val fetched = mutableListOf<Any>()
        for (rawUrl in endpoints.distinct().take(2)) {
            val url = ManifestKit.resolveUrl(baseUrl, rawUrl)
            val text = withTimeoutOrNull(6_000L) {
                catching { app.get(url, timeout = 6, headers = headers).text }.getOrNull()
            } ?: continue
            val parsed = catching { org.json.JSONArray(text) }.getOrNull()
                ?: catching { org.json.JSONObject(text) }.getOrNull() ?: continue
            fetched += parsed
        }
        if (fetched.isEmpty()) return direct
        return withJsonSubtitles(direct, org.json.JSONArray().apply { fetched.forEach { put(it) } }, baseUrl)
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
            (imdbId ?: throw CleanMissException("${spec.id}: IMDB id unavailable (TMDB lookup miss) - host stays in farm"))
        else tmdbId.toString()

        if (spec.id == StremioNuvioAddons.SERVER_ID) {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val addonId = imdbId?.trim()?.takeIf { it.startsWith("tt") }
            if (addonId == null) {
                failServer(spec, "IMDB id unavailable for addon lookup (soft miss, no breaker trip)", isCleanMiss = true)
                return emptyList()
            }
            val result = StremioNuvioAddons.resolve(addonId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "addon manifests", result.size); return result }
            failServer(spec, "addons returned no playable streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        val embedUrl = if (type == "movie") ServerFarm.buildMovieUrl(spec, id)
        else ServerFarm.buildTvUrl(spec, id, season, episode)
        val referer = spec.referer ?: embedUrl.substringBefore("?")

        if (spec.animeOnly && !isAnimationTitle(tmdbId, type)) {
            failServer(spec, "not an animation title (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vidy") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveVidy(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidy sources", result.size); return result }
            failServer(spec, "vidy returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vidbolt") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveVidbolt(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidbolt api", result.size); return result }
            failServer(spec, "vidbolt returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vidly") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveVidly(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vidly embed", result.size); return result }
            failServer(spec, "vidly returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
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
            val result = resolveMovieBox(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "moviebox", result.size); return result }
            failServer(spec, "moviebox returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
            return emptyList()
        }

        if (spec.id == "vaplayer") {
            val result = resolveVaplayer(spec, imdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "vaplayer", result.size); return result }
            failServer(spec, "vaplayer returned no streams")
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
        if (spec.id == "katmoviehd") {
            val result = resolveKatmovie(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "file api links", result.size); return result }
            failServer(spec, "file api returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
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
        if (spec.id == "movy") {
            val result = resolveMovy(spec, tmdbId, type, season, episode)
            if (result.isNotEmpty()) { okServer(spec, start, "movy api", result.size); return result }
            failServer(spec, "movy returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
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
        if (spec.id == "atlantic") {
            if (type != "movie" && (season <= 0 || episode <= 0)) {
                Log.w("IndStream", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
                return emptyList()
            }
            val result = resolveAtlantic(spec, tmdbId, type, season, episode, embedUrl)
            if (result.isNotEmpty()) { okServer(spec, start, "atlantic hls", result.size); return result }
            failServer(spec, "atlantic returned no streams (soft miss, no breaker trip)", isCleanMiss = true)
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
                val (label, h) = probeAudioHeight(url, referer)
                val dash = url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true) || dash, referer, h,
                    audioLabel = label, isDash = dash)
            }
            okServer(spec, start, "direct harvest", result.size)
            return result
        } else {
            Log.d("IndStream", "${spec.id}: no direct urls in page")
        }

        val regLinks = mutableListOf<ExtractorLink>()
        val regSubs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val regOk = catching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        if (unwrappedUrl != embedUrl) {
            catching {
                loadExtractor(url = unwrappedUrl, referer = embedUrl, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
            }
        }
        if (regLinks.isNotEmpty()) {
            Log.d("IndStream", "${spec.id}: extractor registry returned ${regLinks.size} links (outerOk=$regOk unwrapped=$unwrappedUrl)")
            val regTracks = regSubs.mapNotNull { file ->
                file.url.trim().takeIf { it.isNotBlank() }?.let { SubtitleKit.Track(file.lang, it) }
            }.distinctBy { it.url }
            val result = regLinks.map { link ->
                val (label, h) = probeAudioHeight(link.url, link.referer)
                val dash = link.type == ExtractorLinkType.DASH || link.url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, link.url, link.type == ExtractorLinkType.M3U8 || dash, link.referer,
                    ManifestKit.maxQuality(link.quality, h),
                    audioLabel = label, isDash = dash, subtitles = regTracks)
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
                val (label, h) = probeAudioHeight(url, referer)
                val dash = url.contains(".mpd", ignoreCase = true)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true) || dash, referer, h,
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
            val (label, h) = probeAudioHeight(videoSrc, referer)
            okServer(spec, start, "video tag", 1)
            val dash = videoSrc.contains(".mpd", ignoreCase = true)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true) || dash, referer, h,
                audioLabel = label, isDash = dash))
        } else {
            Log.d("IndStream", "${spec.id}: no video tag")
        }

        failServer(spec, "no harvestable stream across full pipeline")
        return emptyList()
    }

    private fun failServer(spec: ServerSpec, reason: String, isCleanMiss: Boolean = false) {
        if (isCleanMiss) {
            Log.i("IndStream", "${spec.id}: clean miss - $reason (no breaker trip)")
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

    private suspend fun probeAudioHeight(url: String, referer: String?): Pair<String, Int> {
        val isAdaptive = url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)
        if (!isAdaptive) return "" to 0
        val text = withTimeoutOrNull(3000L) {
            catching { app.get(url, timeout = 3, headers = mapOf("Referer" to (referer ?: ""))).text }.getOrNull()
        } ?: return "" to 0
        val height = ManifestKit.bestHeightOf(text, url)
        val master = ManifestKit.parseMaster(text, url)
        val tracks = SubtitleKit.fromMaster(master, url)
        if (tracks.isNotEmpty()) FastStartCache.putSubtitles(url, tracks)
        FastStartCache.markSubtitlesProbed(url)
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
        val h = quality.optJSONObject("headers")
        val referer = h?.optString("referer")?.takeIf { it.isNotBlank() }
        val origin = h?.optString("origin")?.takeIf { it.isNotBlank() }
        val out = LinkedHashMap<String, String>()
        if (referer == null && origin == null) {
            // mwVault: the CDN 428-rejects browser UAs and any Referer header.
            out["User-Agent"] = "ExoPlayer"
        } else {
            // mbVault: the CDN requires a browser UA plus the API-provided origin/referer.
            out["User-Agent"] = HttpKit.userAgent
            referer?.let { out["Referer"] = it }
            origin?.let { out["Origin"] = it }
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
        ServerFarm.buildMovieUrl(
            ServerFarm.allServers.first { it.id == "vidlink" },
            vidlinkTokenWithOffset(tmdbId, offsetSec),
        )

    internal fun vidlinkTvApiUrlAt(tmdbId: String, season: Int, episode: Int, offsetSec: Long): String =
        ServerFarm.buildTvUrl(
            ServerFarm.allServers.first { it.id == "vidlink" },
            vidlinkTokenWithOffset(tmdbId, offsetSec),
            season,
            episode,
        )

    internal suspend fun probeServer(spec: ServerSpec): Boolean {
        val sampleId = if (spec.animeOnly) PROBE_ANIME_TMDB_ID else PROBE_TMDB_ID
        val budget = spec.timeoutSec.coerceIn(20, 70).toLong() * 1000L
        return withTimeoutOrNull(budget) {
            val streams = catching {
                resolveOne(
                    spec.copy(recordHealth = false),
                    sampleId.toInt(),
                    PROBE_IMDB_ID,
                    "movie",
                    0,
                    0,
                )
            }.getOrNull()
            !streams.isNullOrEmpty()
        } ?: false
    }

    private const val PROBE_TMDB_ID = "27205"
    private const val PROBE_IMDB_ID = "tt1375666"
    private const val PROBE_ANIME_TMDB_ID = "129"

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

    private const val ATLANTIC_REFERER = "https://atlantic.st/"
    private const val ATLANTIC_FALLBACK_KEY_HEX =
        "55060a042823f51a94c296894a58a0db15f3baef807155140983083fa799ef47"

    internal fun atlanticCdnUrl(jsonText: String?): String? {
        if (jsonText.isNullOrBlank()) return null
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return null
        if (!root.optBoolean("ready", false)) return null
        return root.optString("url").trim().takeIf { it.startsWith("http") }
    }

    internal fun atlanticSourceUrl(jsonText: String?): String? {
        if (jsonText.isNullOrBlank()) return null
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return null
        val sources = root.optJSONObject("sources")
        val fromSources = sources?.names()?.let { names ->
            (0 until names.length()).firstNotNullOfOrNull { i ->
                sources.optJSONObject(names.optString(i))
                    ?.optString("url")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        return fromSources ?: root.optString("url").trim().takeIf { it.startsWith("ms_") }
    }

    internal fun atlanticDecryptSource(
        encoded: String?,
        keyHex: String = ATLANTIC_FALLBACK_KEY_HEX,
    ): String? {
        val payload = encoded?.trim()?.removePrefix("ms_")?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = catching { hexToBytes(payload) }.getOrNull() ?: return null
        if (bytes.size <= 28) return null
        return catching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(hexToBytes(keyHex), "AES"),
                javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
            )
            String(
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)),
                Charsets.UTF_8,
            ).trim().takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    private suspend fun resolveAtlantic(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
        embedUrl: String,
    ): List<RawStream> {
        val headers = mapOf("Referer" to ATLANTIC_REFERER, "User-Agent" to HttpKit.userAgent)

        suspend fun masterStream(url: String, stage: String): RawStream? {
            val text = withTimeoutOrNull(8_000L) {
                catching { app.get(url, timeout = 8, headers = headers).text }.getOrNull()
            }
            val master = ManifestKit.parseMaster(text, url) ?: run {
                Log.w("IndStream", "${spec.id}: $stage master unreadable")
                return null
            }
            val height = ManifestKit.bestHeight(master.variants)
            if (!passesQualityFloor(isAdaptive = true, height = height)) {
                Log.i("IndStream", "${spec.id}: $stage peak ${ManifestKit.qualityLabel(height)} below floor")
                return null
            }
            Log.d("IndStream", "${spec.id}: $stage ${ManifestKit.qualityLabel(height)} (${master.variants.size} variants)")
            return RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = url,
                isM3u8 = true,
                referer = ATLANTIC_REFERER,
                qualityHint = height,
                audioLabel = ManifestKit.audioLanguageLabel(master).orEmpty(),
                inlineManifest = text,
                extraHeaders = headers,
            )
        }

        val primaryJson = withTimeoutOrNull(8_000L) {
            catching { app.get(embedUrl, timeout = 8, headers = headers).text }.getOrNull()
        }
        atlanticCdnUrl(primaryJson)?.let { primary ->
            masterStream(primary, "primary")?.let { return listOf(it) }
        }

        val fallbackUrl = if (type == "movie") {
            "https://stream.hls.lol/moscow?tmdbId=$tmdbId&type=movie"
        } else {
            "https://stream.hls.lol/moscow?tmdbId=$tmdbId&type=tv&seasonId=$season&episodeId=$episode"
        }
        val fallbackJson = withTimeoutOrNull(8_000L) {
            catching { app.get(fallbackUrl, timeout = 8, headers = headers).text }.getOrNull()
        }
        val resolved = atlanticDecryptSource(atlanticSourceUrl(fallbackJson))
        if (resolved != null) masterStream(resolved, "fallback")?.let { return listOf(it) }
        return emptyList()
    }

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
                return withJsonSubtitles(entries.map { (height, url, q) ->
                    RawStream(
                        serverId = spec.id,
                        serverName = spec.name,
                        url = url,
                        isM3u8 = false,
                        referer = null,
                        qualityHint = height,
                        extraHeaders = vidlinkPlaybackHeaders(q),
                    )
                }, resolved, mediaPage)
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

        return withJsonSubtitles(listOf(
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
        ), resolved, mediaPage)
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
        val m = MOVIEBOX_SEASON_END.find(rawTitle) ?: return null
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
                } else {
                    Log.w("MovieBox", "throttle retry answered with no items")
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
                " - retrying once with a fresh bearer token")
            movieBoxToken = null
        }
        val items = searchItems ?: run { Log.w("MovieBox", "no search items"); return emptyList() }

        val norm: (String) -> String = { t -> t.lowercase().replace(NON_ALNUM_LOWER, "") }
        val titleNorm = norm(title)
        val subjects = mutableListOf<SubjectRef>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title", "")
            val seasonEnd = movieboxSeasonEnd(rawTitle)
            val audioTag = MOVIEBOX_BRACKETS.findAll(rawTitle)
                .map { it.groupValues[1] }
                .firstOrNull { it.any { c -> c.isLetter() } && !it.any { c -> c.isDigit() } }
            val clean = rawTitle
                .replace(MOVIEBOX_SEASON_SUFFIX, "")
                .replace(MOVIEBOX_BRACKET_REMOVE, "")
                .replace(MOVIEBOX_YEAR, "")
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
                                if (manifest != null) {
                                    finalUrl = manifest
                                } else if (!rawUrl.contains(".mpd", ignoreCase = true) ||
                                    isMovieboxNoticeUrl(rawUrl)
                                ) {
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
                    val downloads = unwrapData(downloadObj)
                    val play = unwrapData(playObj)
                    val streams = addStreams(downloads.optJSONArray("downloads"), dash = false) +
                        addStreams(play.optJSONArray("streams"), dash = false) +
                        addStreams(play.optJSONArray("dash"), dash = true)
                    if (lockedTotal > 0) Log.i("MovieBox", "$subjectId: $lockedTotal VIP-locked rendition(s) skipped")
                    withJsonSubtitles(streams, org.json.JSONArray().apply { put(downloads); put(play) }, base)
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
        val fileNameHeight = FILE_NAME_HEIGHT.find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
        return withJsonSubtitles(out, root, apiUrl)
    }

    private suspend fun resolveNetmirror(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        if (tmdbId == null) return emptyList()
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        return catching { resolveNetmirrorDirect(spec, tmdbId, type, season, episode) }
            .getOrElse { err ->
                if (err is CleanMissException) throw err
                Log.w("NetMirror", "primary failed: ${err.javaClass.simpleName}")
                emptyList()
            }
    }

    private suspend fun resolveNetmirrorDirect(
        spec: ServerSpec,
        tmdbId: Int?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = tmdbId?.toString() ?: return emptyList()
        val playReferer = "https://videodownloader.site/"
        val base = spec.referer?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: return emptyList()
        val headers = okHeaders("$base/")
        val tvParams = if (type == "tv") "&se=$season&ep=$episode" else ""

        suspend fun fetchEmbed(dub: String?, dubdp: String?): org.json.JSONObject? {
            val query = buildString {
                if (type == "tv") append("?type=tv").append(tvParams)
                else append("?type=movie")
                if (dub != null) { append("&dub=").append(dub); append("&dubdp=").append(dubdp) }
            }
            val jsonText = withTimeoutOrNull(12_000L) {
                catching {
                    app.get("$base/api/embed-tmdb/$id$query",
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
            return withJsonSubtitles(out, root, base)
        }

        val (defaultRoot, vText) = coroutineScope {
            val rootA = async { fetchEmbed(null, null) }
            val varsA = async {
                withTimeoutOrNull(6_000L) {
                    catching {
                        val vQuery = if (type == "tv") "?se=$season&ep=$episode" else ""
                        app.get("$base/api/variants-tmdb/$type/$id$vQuery",
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

    internal fun releaseLanguageOf(text: String?): String {
        val u = (text ?: "").uppercase()
        return when {
            HINDI_WORD.containsMatchIn(u) -> "Hindi"
            TAMIL_WORD.containsMatchIn(u) -> "Tamil"
            TELUGU_WORD.containsMatchIn(u) -> "Telugu"
            MULTI_WORD.containsMatchIn(u) -> "Multi"
            ENGLISH_WORD.containsMatchIn(u) -> "English"
            else -> ""
        }
    }

    internal fun releaseHeightOf(text: String?): Int {
        val u = (text ?: "").uppercase()
        return when {
            u.contains("2160P") || FOUR_K_WORD.containsMatchIn(u) -> 2160
            u.contains("1080P") -> 1080
            u.contains("720P") -> 720
            u.contains("480P") -> 480
            else -> 0
        }
    }

    internal fun hubParseCards(html: String, base: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        val out = LinkedHashMap<String, String>()
        doc.select("a.movie-card").forEach { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http")) return@forEach
            val title = a.attr("aria-label").removeSuffix(" details").trim()
                .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { a.text().trim() }
            if (title.isNotBlank()) out.putIfAbsent(href, title)
        }
        return out.map { it.key to it.value }
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
            if (NON_CONTENT_PATH.containsMatchIn(href)) return@forEach
            val title = a.text().trim().ifBlank { a.attr("title").trim() }
            if (title.isNotBlank()) out.putIfAbsent(href, title)
        }
        doc.select("a[href] > p").forEach { p ->
            val a = p.parent() ?: return@forEach
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http")) return@forEach
            if (NON_CONTENT_PATH.containsMatchIn(href)) return@forEach
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
            if (NON_CONTENT_PATH_BASIC.containsMatchIn(href)) return@mapNotNull null
            href to title
        }.distinctBy { it.first }

    internal fun posterHits(html: String, base: String): List<Pair<String, String>> =
        Jsoup.parse(html, base).select("a[href][data-wpel-link]").mapNotNull { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http")) return@mapNotNull null
            if (NON_CONTENT_PATH_BASIC.containsMatchIn(href)) return@mapNotNull null
            val title = a.selectFirst(".poster-title")?.text()?.trim().orEmpty()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { a.text().trim().take(120) }
            if (title.isBlank()) return@mapNotNull null
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
                if (prev != null && HEADING_TAG.matches(prev.tagName())) heading = prev.text().trim()
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
        return Jsoup.parse(html, base).select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").trim()
            if (!href.startsWith("http") || !FILE_HOST_RE.containsMatchIn(href)) return@mapNotNull null
            if (isArchiveUrl(href) || href.contains(".php?", true) || href.contains("/admin", true) ||
                href.contains("pixel.", true) || href.contains("/tg/", true) || href.contains("t.me", true)
            ) return@mapNotNull null
            href to a.text().trim()
        }.distinctBy { it.first }
    }

    internal fun qualityText(text: String): String {
        val value = QUALITY_TEXT_RE.find(text)?.value ?: return ""
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
        val title = OG_TITLE.find(html)?.groupValues?.get(1)
            ?: HTML_TITLE.find(html)?.groupValues?.get(1)
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
                .filter { HUB_LANG_RE.containsMatchIn(it) }
                .distinct()
                .joinToString(" ")
            val gateways = item.select("a[href]").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) return@mapNotNull null
                if (!HUB_GATEWAY_HOST.containsMatchIn(href)) {
                    return@mapNotNull null
                }
                href to a.text().contains("hubcloud", ignoreCase = true)
            }.sortedByDescending { if (it.second) 1 else 0 }
                .map { it.first }.distinct()
            HubItem(title, size, langs, gateways)
        }.filter { it.gatewayUrls.isNotEmpty() }
    }

    internal fun normalizeSizeToken(text: String): String =
        SIZE_TOKEN.find(text)?.value?.replace(" ", "")?.uppercase().orEmpty()

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

    private val ROTATOR_BLOB = Regex("""'o'\s*,\s*'([A-Za-z0-9+/=]{16,})[^']*'""")
    private val ROTATOR_WP_BLOB = Regex("""ck\('_wp_http_\d+','([^']+)'""")
    private val ROTATOR_O_TOKEN = Regex("\"o\"\\s*:\\s*\"([A-Za-z0-9+/=]{10,})")
    private val JS_LOCATION = Regex(
        """(?:window\.)?location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""",
        RegexOption.IGNORE_CASE,
    )
    private val META_REFRESH = Regex(
        """<meta\s+http-equiv=["']refresh["'][^>]*content=["'][^;]*;\s*url=([^"']+)""",
        RegexOption.IGNORE_CASE,
    )
    private val STORED_REDIRECT = Regex("""s\('o','([A-Za-z0-9+/=]+)'""")
    private val DIRECT_URL = Regex("""https?://[^\s"'<>\\]+""")

    internal fun rotatorTarget(pageHtml: String): String? {
        val blob = ROTATOR_BLOB.find(pageHtml)?.groupValues?.get(1)
            ?: ROTATOR_WP_BLOB.find(pageHtml)?.groupValues?.get(1)
            ?: return null
        val l1 = b64Text(blob) ?: return null
        val l2 = b64Text(l1) ?: return null
        val jsonText = b64Text(rot13(l2)) ?: return null
        val oToken = ROTATOR_O_TOKEN.find(jsonText)?.groupValues?.get(1)
        val wrapped = catching { org.json.JSONObject(jsonText) }.getOrNull()
            ?: oToken?.takeIf { b64Text(it)?.startsWith("http") == true }
                ?.let { org.json.JSONObject("{\"o\":\"$it\"}") }
            ?: return null
        val url = b64Text(wrapped.optString("o"))?.trim() ?: return null
        return url.takeIf { it.startsWith("http") }
    }

    /** Best-effort extraction of a JS/meta redirect target from gateway pages. */
    internal fun jsRedirectTarget(pageHtml: String): String? {
        JS_LOCATION.find(pageHtml)?.groupValues?.get(1)?.let { return it }
        META_REFRESH.find(pageHtml)?.groupValues?.get(1)?.let { return it.trim() }
        val stored = STORED_REDIRECT.find(pageHtml)?.groupValues?.get(1) ?: return null
        var payload: String? = stored
        repeat(6) {
            val decoded = payload?.let { b64Text(it) } ?: return null
            DIRECT_URL.find(decoded)?.value?.let { return it }
            payload = decoded
        }
        return null
    }

    internal fun hubLangOf(languages: String): String {
        val s = languages.lowercase()
        return when {
            // Hindi mixed with any other listed language is the ambiguity bucket (priority only).
            s.contains("hindi") && HUB_OTHER_LANG.containsMatchIn(s) -> "Multi"
            s.contains("hindi") -> "Hindi"
            else -> LinkNaming.languageLabel(languages).ifBlank { "" }
        }
    }

    private fun isArchiveUrl(url: String): Boolean = ARCHIVE_URL.containsMatchIn(url)

    internal fun hubFileButtons(html: String): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        val headQ = releaseHeightOf(doc.selectFirst(".card-header")?.text())
        val out = mutableListOf<Triple<String, String, String>>()
        for (a in doc.select("a[href]")) {
            if (!BTN_CLASS.containsMatchIn(a.className())) continue
            var url = a.attr("href").trim()
            if (!url.startsWith("http")) continue
            if (isArchiveUrl(url)) continue
            if (url.contains("facebook.") || url.contains("twitter.") || url.contains("t.me") ||
                url.contains("/tg/") || url.contains("pixel.") || url.contains("/admin")
            ) continue
            url = PIXELDRAIN_SHORT.find(url)?.let {
                url.replace("/u/${it.groupValues[1]}", "/api/file/${it.groupValues[1]}")
            } ?: url
            val text = a.text().trim()
            val q = releaseHeightOf(text).takeIf { it > 0 } ?: headQ
            out += Triple(url, if (q > 0) "${q}p" else "", LinkNaming.languageLabel(text))
        }
        return out.distinctBy { it.first }
    }

    private val REURL_VAR = Regex("""var\s+reurl\s*=\s*"([^"]+)"""")
    private val HUB_DRIVE_NEXT = Regex("""https?://hubcloud\.[a-z.]+/drive/[A-Za-z0-9]+""")

    private suspend fun hubDriveFiles(driveUrl: String, referer: String): List<Triple<String, String, String>> {
        if (driveUrl.contains("hubdrive.", ignoreCase = true) || driveUrl.contains("hubcdn.", ignoreCase = true)) {
            val index = fetchText(driveUrl, referer) ?: return emptyList()
            val reurl = REURL_VAR.find(index)?.groupValues?.get(1)
            val direct = reurl?.substringAfter("r=", "")
                ?.replace('-', '+')?.replace('_', '/')
                ?.let { b64Text(it) }
                ?.substringAfter("link=", "")
                ?.takeIf { it.startsWith("http") }
            if (direct != null) return listOf(Triple(direct, qualityText(direct), ""))
            val next = HUB_DRIVE_NEXT.find(index)?.value ?: return emptyList()
            return hubDriveFiles(next, driveUrl)
        }
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
        googleVideoLink(drive, finalUrl)?.let { return listOf(Triple(it, qualityText(drive), "")) }
        val tokenUrl = TOKEN_URL_ABS.find(drive)?.value
            ?: TOKEN_URL_REL.find(drive)?.value?.let { tokenHref ->
                val origin = ORIGIN.find(url)?.value ?: return emptyList()
                "$origin/$tokenHref"
            } ?: return emptyList()
        val page = withTimeoutOrNull(10_000L) {
            catching { app.get(tokenUrl, timeout = 10, headers = okHeaders(url)).text }.getOrNull()
        } ?: return emptyList()
        return hubFileButtons(page).take(4)
    }

    private fun googleVideoLink(html: String, finalUrl: String): String? {
        val raw = GOOGLE_VIDEO.find(html)?.groupValues?.get(1)
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
        val m = PIXELDRAIN_PAGE.find(url) ?: return null
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
        val shortId = GDSHINE_SHORT.find(host)?.groupValues?.get(1) ?: return null
        val origin = ORIGIN.find(host)?.value ?: return null
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
            val hostLang = releaseLanguageOf(hostLabel).ifBlank { language }
            val pixeldrain = pixeldrainDirect(host)
            if (pixeldrain != null) {
                out += rawFile(spec, label.ifBlank { hostLabel }, pixeldrain, referer, hostLang, size)
                continue
            }
            if (host.contains("gdshine.", ignoreCase = true)) {
                val stream = gdshineStream(host, referer) ?: continue
                out += rawFile(
                    spec, label.ifBlank { stream.second }, stream.first, referer, hostLang, size,
                )
                continue
            }
            if (VIDEO_EXT.containsMatchIn(host)) {
                out += rawFile(spec, label.ifBlank { hostLabel }, host, referer, hostLang, size)
                continue
            }
            if (host.contains("hubcloud.", ignoreCase = true) ||
                host.contains("hubdrive.", ignoreCase = true) ||
                host.contains("hubcdn.", ignoreCase = true)
            ) {
                for ((url, quality, fileLang) in hubDriveFiles(host, referer)) {
                    out += rawFile(
                        spec, quality.ifBlank { label }, url, referer,
                        fileLang.ifBlank { language }, size,
                    )
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
        val taggedSeason = TAGGED_SEASON.containsMatchIn(text)
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
        val old = ORIGIN.find(url)?.value ?: return url
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
            ).sortedByDescending { releaseHeightOf(it.title) }.take(4)
            val out = mutableListOf<RawStream>()
            for (item in items) {
                // Keep every language name from the source data for the audio label; hubLangOf
                // remains the priority bucket ("Multi" for Hindi+others) and is not shown.
                val langNames = LinkNaming.languageNames(item.languages)
                val lang = langNames.joinToString(", ")
                val audioLang = LinkNaming.languageLabel(item.languages).ifBlank { lang }
                val priority = when (hubLangOf(item.languages)) {
                    "Hindi" -> 4
                    "Multi" -> 2
                    else -> 1
                }
                for (gateway in item.gatewayUrls) {
                    val gwPage = withTimeoutOrNull(10_000L) {
                        catching { app.get(gateway, timeout = 10, headers = okHeaders(card.first)).text }.getOrNull()
                    } ?: continue
                    var resolvedDrive: String? = null
                    for (target in listOf(rotatorTarget(gwPage), jsRedirectTarget(gwPage))) {
                        if (target != null) { resolvedDrive = relUrl(gateway, target); break }
                    }
                    val driveUrl = resolvedDrive ?: continue
                    val finalDrive = if (driveUrl.contains("hubdrive.", ignoreCase = true)) {
                        val inner = withTimeoutOrNull(8_000L) {
                            catching { app.get(driveUrl, timeout = 8, headers = okHeaders(card.first)).text }.getOrNull()
                        }?.let { page ->
                            Jsoup.parse(page, driveUrl).select("a[href]").firstOrNull { a ->
                                a.attr("abs:href").contains("hubcloud", ignoreCase = true)
                            }?.attr("abs:href")
                        }
                        inner?.takeIf { it.startsWith("http") } ?: continue
                    } else driveUrl
                    val referer = ORIGIN.find(finalDrive)?.value ?: "https://hubcloud.ist/"
                    val files = hubDriveFiles(finalDrive, card.first)
                    for ((url, q, fileLang) in files) {
                        val qText = q.ifBlank { HUB_QUALITY.find(item.title)?.value.orEmpty() }
                        val fileAudio = LinkNaming.languageLabel(fileLang)
                        val audio = when {
                            fileAudio.isNotBlank() -> fileAudio
                            audioLang.isNotBlank() -> audioLang
                            else -> lang
                        }
                        val audioPriority = when {
                            audio.contains(',') || audio == "Multi" -> 2
                            audio.startsWith("Hindi") -> 4
                            else -> 1
                        }
                        out += RawStream(
                            spec.id, "${spec.name} $qText".trim(), url,
                            url.contains(".m3u8", ignoreCase = true), referer,
                            releaseHeightOf(qText.ifBlank { item.title }),
                            audioPriority = audioPriority, audioLabel = audio,
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

    private val LINK_HOST_RE = Regex("""links\.joshapp\.blog|mvlink\.blog""", RegexOption.IGNORE_CASE)

    private suspend fun resolveLinkIndex(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        var base = ""
        var search = ""
        var fallback: Pair<String, String>? = null
        val bases = (listOfNotNull(Domains.base("hindmoviez")) +
            listOf("https://hindmovie.one")).distinct()
        for (candidate in bases) {
            val html = fetchText("$candidate/?s=${enc(title)}", candidate, timeoutSec = 12)
            if (html.isNullOrBlank()) continue
            if (titleHits(html, candidate).isNotEmpty()) { base = candidate; search = html; break }
            if (fallback == null) fallback = candidate to html
        }
        if (base.isBlank()) {
            val f = fallback ?: throw CleanMissException("no search answer")
            base = f.first
            search = f.second
        }
        val posts = rankedHits(titleHits(search, base), title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val out = mutableListOf<RawStream>()
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            val links = episodeOrSeason(
                headingAnchorHits(post, postUrl, LINK_HOST_RE),
                { "${it.second} ${it.first}" }, type, season, episode,
            ).take(4)
            for ((href, label) in links) {
                val linkPage = fetchText(href, postUrl) ?: continue
                val name = HTML_TITLE.find(linkPage)?.groupValues?.get(1)?.trim().orEmpty()
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
                val language = LinkNaming.languageLabel(label).ifBlank { releaseLanguageOf(name) }
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
                if (!HUB_GATEWAY_HOST_ALT.containsMatchIn(href)) {
                    return@mapNotNull null
                }
                href to a.text().trim()
            }.filter { !it.second.contains("watch", ignoreCase = true) }.distinctBy { it.first }
            val links = episodeOrSeason(anchors, { "${it.second} ${it.first}" }, type, season, episode)
                .sortedByDescending { releaseHeightOf(it.second) }
                .take(8)
            val language = releaseLanguageOf(post)
            for ((href, label) in links) {
                val hosts = if (href.contains("greenmotors", ignoreCase = true)) {
                    val gateway = fetchText(href, postUrl) ?: continue
                    val archive = rotatorTarget(gateway)
                        ?: jsRedirectTarget(gateway)?.let { relUrl(href, it) }
                        ?: continue
                    val archivePage = fetchText(archive, href) ?: continue
                    fileHostLinks(archivePage, archive)
                } else {
                    listOf(href to label)
                }
                out += hostStreams(
                    spec, hosts, label, postUrl,
                    releaseLanguageOf(label).ifBlank { language }, normalizeSizeToken(label),
                )
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
        val base = Domains.base("moviesdrive") ?: "https://new2.moviesdrive.cyou"
        val search = fetchText("$base/search/${enc(title)}/", base)
            ?: throw CleanMissException("no search answer")
        val posts = rankedHits(posterHits(search, base), title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        val episodeTag = if (type == "tv" && season > 0 && episode > 0) {
            "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        } else {
            ""
        }
        val out = mutableListOf<RawStream>()
        val archiveRe = Regex("""mdrive\.lol/archive""", RegexOption.IGNORE_CASE)
        for (postUrl in posts) {
            val post = fetchText(postUrl, base) ?: continue
            val links = episodeOrSeason(
                headingAnchorHits(post, postUrl, Regex("""mdrive\.lol/archive|search-recover""", RegexOption.IGNORE_CASE)),
                { "${it.second} ${it.first}" }, type, season, episode,
            ).take(6)
            val language = releaseLanguageOf(post)
            for ((href, label) in links) {
                if (archiveRe.containsMatchIn(href)) {
                    val archive = fetchText(href, postUrl) ?: continue
                    val hosts = fileHostLinks(archive, href)
                    out += hostStreams(spec, hosts, label, href, language, normalizeSizeToken(label))
                    if (out.isNotEmpty()) return out.distinctBy { it.url }
                    continue
                }
                val token = Regex("""from_ac=([^&]+)""").find(href)?.groupValues?.get(1) ?: continue
                val apiBase = ORIGIN.find(href)?.value
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
                        val hosts = hubDriveFiles(url, postUrl).map { (u, q, fileLang) ->
                            u to listOf(q, fileLang).filter { it.isNotBlank() }.joinToString(" ")
                        }
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

    private val KMHD_FILE_RE = Regex("""links\.kmhd\.me/file/([A-Za-z0-9_-]+)""")
    private val KMHD_PACK_RE = Regex("""links\.kmhd\.me/pack/([A-Za-z0-9_-]+)""")

    internal fun katmovieSearchHits(json: String): List<Pair<String, String>> {
        val arr = catching { org.json.JSONArray(json.trim()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val hit = arr.optJSONObject(i) ?: continue
            val url = hit.optString("url").trim()
            val title = animeUnescape(hit.optString("title"))
            if (!url.startsWith("http") || title.isBlank()) continue
            out += url to title
        }
        return out.distinctBy { it.first }
    }

    internal fun katmoviePayloadLinks(payload: String): Pair<List<String>, List<String>> {
        val normalized = payload
            .replace("\\u002F", "/")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
        val files = KMHD_FILE_RE.findAll(normalized).map { it.groupValues[1] }.distinct().toList()
        val packs = KMHD_PACK_RE.findAll(normalized).map { it.groupValues[1] }.distinct().toList()
        return files to packs
    }

    private suspend fun katmoviePackFiles(packId: String): List<Pair<String, String>> {
        val json = fetchText(
            "https://api.dandndn.one/api/v1/pack/$packId", "https://links.kmhd.me/", timeoutSec = 12,
        ) ?: return emptyList()
        val info = catching { org.json.JSONObject(json).optJSONObject("info") }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val keys = info.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val name = info.optJSONObject(id)?.optString("name").orEmpty().trim()
            if (name.isBlank()) continue
            out += id to animeUnescape(name)
        }
        return out
    }

    private suspend fun katmovieFileMeta(id: String): org.json.JSONObject? {
        val json = fetchText(
            "https://api.dandndn.one/api/v1/file/$id", "https://links.kmhd.me/", timeoutSec = 12,
        ) ?: return null
        return catching { org.json.JSONObject(json) }.getOrNull()
    }

    internal fun katmovieLangText(name: String): String = name
        .replace(Regex("""(?i)\bHIN\b"""), "Hindi")
        .replace(Regex("""(?i)\bENG\b"""), "English")
        .replace(Regex("""(?i)\bTAM\b"""), "Tamil")
        .replace(Regex("""(?i)\bTEL\b"""), "Telugu")
        .replace(Regex("""(?i)\bMAL\b"""), "Malayalam")
        .replace(Regex("""(?i)\bPAN\b"""), "Punjabi")
        .replace(Regex("""(?i)\bBEN\b"""), "Bengali")

    internal fun katmovieSizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1fGB", bytes / 1073741824.0)
        else -> String.format(java.util.Locale.US, "%.0fMB", bytes / 1048576.0)
    }

    private suspend fun resolveKatmovie(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val (title, year) = titleYear(tmdbId, type)
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val site = Domains.base("katmoviehd") ?: "https://katmoviehd.top"
        val postSite = "https://new." + site.substringAfter("://")
        val searchJson = fetchText(
            "$site/wp-json/wp/v2/search?search=${enc(title)}&per_page=8", site, timeoutSec = 15,
        ) ?: throw CleanMissException("no search answer")
        val hits = katmovieSearchHits(searchJson)
        val posts = rankedHits(hits, title, year)
        if (posts.isEmpty()) throw CleanMissException("no match")
        for (postUrl in posts) {
            val slug = postUrl.trimEnd('/').substringAfterLast('/')
            if (slug.isBlank()) continue
            val payload = fetchText("$postSite/post/$slug/__data.json", postSite, timeoutSec = 15)
                ?: continue
            val (fileIds, packIds) = katmoviePayloadLinks(payload)
            if (fileIds.isEmpty() && packIds.isEmpty()) continue
            val candidates = packIds.take(3).flatMap { katmoviePackFiles(it) } +
                fileIds.take(6).map { it to "" }
            val picked = episodeOrSeason(candidates, { it.second }, type, season, episode)
                .sortedByDescending { releaseHeightOf(it.second) }
                .take(4)
            val postTitle = hits.firstOrNull { it.first == postUrl }?.second.orEmpty()
            val out = mutableListOf<RawStream>()
            for ((fileId, packedName) in picked) {
                if (releaseHeightOf(packedName) in 1..719) continue
                val file = katmovieFileMeta(fileId) ?: continue
                val name = file.optString("name").trim()
                if (name.isBlank() || file.optBoolean("is_zip") ||
                    name.endsWith("zip", ignoreCase = true) ||
                    !fileNameMatches(title, year, name)
                ) continue
                if (releaseHeightOf(name) in 1..719) continue
                val hubId = file.optJSONObject("upload_links")?.optString("hubdrive_res")
                    .orEmpty().trim()
                if (hubId.isBlank() || hubId.equals("None", true) || hubId.equals("null", true)) continue
                val status = file.optJSONObject("status")?.optString("hubdrive_res").orEmpty().trim()
                if (status.isNotBlank() && !status.equals("Uploaded", true) && !status.equals("null", true)) continue
                val size = katmovieSizeLabel(file.optLong("size"))
                val mirror = "${Domains.base("hubcloud") ?: "https://hubcloud.ist"}/drive/$hubId"
                val referer = ORIGIN.find(mirror)?.value ?: mirror
                val expanded = katmovieLangText(name)
                val audio = LinkNaming.languageNames(expanded).joinToString(", ")
                    .ifBlank { LinkNaming.languageLabel(postTitle) }
                val priority = when (hubLangOf(expanded)) {
                    "Hindi" -> 4
                    "Multi" -> 2
                    else -> 1
                }
                for ((url, q, fileLang) in hubDriveFiles(mirror, "https://links.kmhd.me/")) {
                    if (!VIDEO_EXT.containsMatchIn(url) && !url.contains("pixeldrain.", ignoreCase = true)) continue
                    val qText = q.ifBlank { qualityText(name) }
                    if (releaseHeightOf(qText) in 1..719) continue
                    out += RawStream(
                        spec.id, "${spec.name} $qText".trim(), url,
                        url.contains(".m3u8", ignoreCase = true), referer,
                        releaseHeightOf(qText.ifBlank { name }),
                        audioPriority = priority,
                        audioLabel = LinkNaming.languageLabel(fileLang).ifBlank { audio },
                        sizeLabel = size,
                        mediaLabel = name,
                    )
                    if (out.size >= 6) break
                }
                if (out.size >= 6) break
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        throw CleanMissException("no file links")
    }

    private const val VIDBOLT_ORIGIN = "https://vidbolt.xyz"
    internal val VIDBOLT_SERVERS = listOf("orion", "callisto", "vega", "quantum", "quasar")

    internal data class VidboltSource(
        val url: String,
        val kind: String,
        val quality: String,
        val language: String,
        val label: String,
        val master: String,
    )

    internal fun vidboltSources(jsonText: String): List<VidboltSource> {
        val root = catching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val stream = root.optJSONObject("stream") ?: return emptyList()
        val sources = stream.optJSONArray("sources") ?: return emptyList()
        val out = ArrayList<VidboltSource>(sources.length())
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val url = src.optString("url").trim()
            if (!url.startsWith("http")) continue
            out += VidboltSource(
                url = url,
                kind = src.optString("kind"),
                quality = src.optString("quality"),
                language = src.optString("language"),
                label = src.optString("label"),
                master = src.optString("master"),
            )
        }
        return out
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
            catching { app.get(url, timeout = timeoutSec, headers = okHeaders(url)) }.getOrNull()
        } ?: return null
        return res.text to res.url
    }

    private suspend fun fslLive(seed: String, bootstrap: String?): Pair<String, String>? {
        val (html0, url0) = fslGet(seed, 10)
            ?: (bootstrap?.let { fslGet(it, 10) } ?: return null)
        if (!fslIsLanding(html0)) return html0 to url0
        val origin = ORIGIN.find(url0)?.value ?: return null
        val probed = fslGet(origin + FSL_LANDING_PROBE, 12) ?: return null
        return if (fslIsLanding(probed.first)) null else probed
    }

    private suspend fun fslBase(family: String, seed: String, bootstrap: String?): String? {
        fslBaseCache[family]?.let { (cached, at) ->
            if (System.currentTimeMillis() - at < 30 * 60_000L) return cached
        }
        val live = fslLive(seed, bootstrap)
        val liveBase = live?.let { (html, url) ->
            val origin = ORIGIN.find(url)?.value ?: url
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

    private val FSL_GATEWAY_LINK = Regex(
        """https?://[a-z0-9.\-]*(?:nexdrive|gdflix|filepress|vcloud)\.[a-z]{2,10}/[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE,
    )

    internal fun fslGroups(html: String): List<FslGroup> {
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
                el.tagName().matches(HEADING_TAG) -> {
                    val t = el.text().trim()
                    if (t.isNotBlank() && t.length < 220) {
                        if (anchors.isNotEmpty()) close()
                        heading = if (downloadHeadingShaped(t)) t else ""
                    }
                }
                el.tagName() == "a" -> {
                    val href = el.attr("abs:href")
                    if (FSL_GATEWAY_LINK.containsMatchIn(href)) {
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
        DOWNLOAD_HEADING.containsMatchIn(text)

    private val SERVER_LABEL_ACTION =
        Regex("""(?i)^(?:download|file|watch|play|get|stream|online)\b""")
    private val SERVER_LABEL_TRAILING = Regex("""(?i)\s*\bserver\b\s*$""")

    internal fun fslServerLabel(tag: String?, fallback: String): String {
        val label = tag?.trim().orEmpty()
        if (label.isBlank()) return fallback
        if (LinkNaming.languageLabel(label).isNotBlank()) return fallback
        if (SERVER_LABEL_ACTION.containsMatchIn(label)) return fallback
        if (QUALITY_TEXT_RE.containsMatchIn(label)) return fallback
        return label.replace(SERVER_LABEL_TRAILING, "").trim().ifBlank { fallback }
    }

    internal data class GatewayLinks(val fastdl: List<String>, val cloud: List<String>)

    private val FASTDL_LINK =
        Regex("""https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)
    private val VCLOUD_LINK = Regex("""https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""")

    internal fun gatewayLinksOf(html: String): GatewayLinks {
        val normalized = html.replace("\\/", "/")
        val fastdl = FASTDL_LINK.findAll(normalized).map { it.value }.distinct().toList()
        val vcloud = VCLOUD_LINK.findAll(html).map { it.value }.distinct().toList()
        return GatewayLinks(fastdl, vcloud)
    }

    internal fun vcloudPageMatchesEpisode(pageTitle: String, type: String, season: Int, episode: Int): Boolean {
        if (type != "tv" || season <= 0 || episode <= 0) return true
        val tagged = VCLOUD_EPISODE_TAG.containsMatchIn(pageTitle)
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
        val pageTitle = HTML_TITLE.find(page)?.groupValues?.get(1)?.trim().orEmpty()
        if (isArchiveUrl(pageTitle) || pageTitle.contains("complete season", ignoreCase = true)) return emptyList()
        if (!vcloudPageMatchesEpisode(pageTitle, type, season, episode)) return emptyList()
        val tokenUrl = VCLOUD_ATOB.find(page)?.groupValues?.get(1)
            ?.let { blob -> vcloudDoubleAtob(blob) }
            ?.takeIf { it.startsWith("http") } ?: return emptyList()
        val token = withTimeoutOrNull(15_000L) {
            catching { app.get(tokenUrl, timeout = 15, headers = okHeaders(url)).text }.getOrNull()
        } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val seen = LinkedHashSet<String>()
        for (m in VCLOUD_BTN.findAll(token)) {
            val raw = m.groupValues[1].trim()
            val label = BRACKET_LABEL.find(m.groupValues[2].trim())?.groupValues?.get(1)?.trim()
                ?: m.groupValues[2].trim()
            val abs = if (raw.startsWith("http")) raw else {
                val origin = ORIGIN.find(tokenUrl)?.value ?: continue
                "$origin${if (raw.startsWith('/')) raw else "/$raw"}"
            }
            if (isArchiveUrl(abs)) continue
            if (R2_HOST.containsMatchIn(abs) && seen.add(abs)) {
                out += abs to label.take(20)
            }
        }
        for (gpdl in GPDL_URL.findAll(token).map { it.value }.distinct()) {
            for ((direct, quality, _) in hubDriveFiles(gpdl, tokenUrl)) {
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
        HEADING_SIZE.find(heading)?.let { return normalizeSizeToken(it.groupValues[1]).replace("/ZIP", "") }
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
                    val priorityLang = releaseLanguageOf(group.heading)
                    val lang = LinkNaming.languageLabel(group.heading).ifBlank { priorityLang }
                    val priority = when (priorityLang) {
                        "Hindi" -> 4
                        "Multi" -> 2
                        else -> 1
                    }
                    val qText = HUB_QUALITY.find(group.heading)?.value.orEmpty()
                    var perGroup = 0
                    for (gateway in group.gateways) {
                        val cloudLinks = if (gateway.contains("vcloud.", ignoreCase = true)) {
                            listOf(gateway)
                        } else {
                            val expanded = fslGet(gateway, 15)?.first ?: continue
                            gatewayLinksOf(expanded).cloud
                        }
                        for (vcloud in episodeFirstOrder(cloudLinks, type, season, episode)) {
                            for ((url, sourceLabel) in vcloudR2Links(vcloud, gateway, type, season, episode)) {
                                if (!seen.add(url)) continue
                                val serverName = listOf(fslServerLabel(sourceLabel, spec.name), qText)
                                    .filter { it.isNotBlank() }.joinToString(" ")
                                out += RawStream(
                                    spec.id, serverName, url,
                                    url.contains(".m3u8", ignoreCase = true),
                                    ORIGIN.find(url)?.value,
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w("IndStream", "anime catalog search failed: ${e.message}")
        emptyList()
    }

    private suspend fun fetchAnimePage(url: String): String? = try {
        app.get(url, timeout = 15, headers = animeHeaders(ANIME_CATALOG_REFERER)).text
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w("IndStream", "anime page failed ($url): ${e.message}")
        null
    }

    private suspend fun animeSeasonHtml(post: String, season: Int): String? = try {
        app.get(
            "$ANIME_CATALOG/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$post",
            timeout = 12, headers = animeAjaxHeaders(ANIME_CATALOG_REFERER),
        ).text
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (e: Exception) {
        null
    }

    internal fun xorEncode(json: String, key: String = ANIME_OBF_KEY): String =
        java.util.Base64.getEncoder().encodeToString(xor(json.toByteArray(Charsets.UTF_8), key))

    private fun xor(raw: ByteArray, key: String): ByteArray =
        ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.length].code).toByte() }

    internal fun animePlayerConfig(blob: String): org.json.JSONObject? = try {
        xorDecode(blob)?.let { org.json.JSONObject(it) }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
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
                audioPriority = if (label.contains("Hindi")) 4 else 3,
                audioLabel = label,
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
            out += withJsonSubtitles(listOf(RawStream(
                serverId = spec.id, serverName = "${spec.name} $label",
                url = src, isM3u8 = src.contains(".m3u8", true),
                referer = ANIME_EMBED_REFERER,
                audioPriority = if (dub) 1 else 2,
                audioLabel = label,
                extraHeaders = mapOf(
                    "Referer" to ANIME_EMBED_REFERER,
                    "User-Agent" to HttpKit.userAgent,
                ),
            )), cfg, ANIME_EMBED_REFERER)
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
        HEIGHT_HINT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
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
        val subtitlePayloads = mutableListOf<Any>()
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
            catching { org.json.JSONObject(text) }.getOrNull()?.let { subtitlePayloads += it }
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
        return resolveSubtitleEndpoints(out.values.toList(), subtitlePayloads, api, postHeaders)
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

    private val MOVY_GOLDEN = 0x9e3779b9.toInt()
    private val MOVY_MAGIC = byteArrayOf(109, 118, 109, 49)
    private val MOVY_LANES = listOf(
        "denver", "atlanta", "phoenix", "portland", "seattle", "miami", "boise", "paris",
        "cancun", "austin", "dallas", "delhi", "munich", "orlando", "tampa", "berlin",
    )
    private val VIDY_LANES = listOf(
        "miami", "boise", "vegas", "reno", "oakland", "seattle", "phoenix", "atlanta",
        "portland", "austin", "dallas", "tampa", "orlando", "munich", "berlin", "paris",
        "delhi", "cancun",
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
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
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
        return resolveWecollegeSources(
            spec, tmdbId, type, season, episode,
            referer = "https://www.movy.sx/",
            lanes = MOVY_LANES,
            laneTimeoutSec = 7,
            logTag = "Movy",
        )
    }

    private suspend fun resolveVidy(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        return resolveWecollegeSources(
            spec, tmdbId, type, season, episode,
            referer = "https://www.vidy.st/",
            lanes = VIDY_LANES,
            laneTimeoutSec = 6,
            logTag = "Vidy",
        )
    }

    private suspend fun resolveVidbolt(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val origin = VIDBOLT_ORIGIN
        val api = if (type == "movie") ServerFarm.buildMovieUrl(spec, tmdbId.toString())
        else ServerFarm.buildTvUrl(spec, tmdbId.toString(), season, episode)
        val payloads = ArrayList<String>(VIDBOLT_SERVERS.size)
        val first = fetchText(api, "$origin/", timeoutSec = 8)
        if (first != null && vidboltSources(first).isNotEmpty()) payloads += first
        if (payloads.isEmpty()) {
            val deadline = System.currentTimeMillis() + 15_000L
            for (server in VIDBOLT_SERVERS) {
                if (System.currentTimeMillis() >= deadline) break
                val text = fetchText("$api&only=$server", "$origin/", timeoutSec = 6) ?: continue
                if (vidboltSources(text).isNotEmpty()) payloads += text
            }
        }
        if (payloads.isEmpty()) {
            Log.w("VidBolt", "no sources for $type/$tmdbId")
            return emptyList()
        }
        val out = LinkedHashMap<String, RawStream>()
        for (payload in payloads) {
            for (src in vidboltSources(payload)) {
                if (out.containsKey(src.url)) continue
                val master = src.master.takeIf { it.contains("#EXTM3U") || it.contains("<MPD", true) }
                val playlist = ManifestKit.parseMaster(master, src.url)
                val height = heightHintOf(src.quality).takeIf { it > 0 }
                    ?: playlist?.let { ManifestKit.bestHeight(it.variants) } ?: 0
                if (height in 1..719) continue
                val audioRaw = src.language.takeUnless { it.contains("sub", true) }.orEmpty()
                val audioLabel = LinkNaming.languageLabel(audioRaw)
                    .ifBlank { playlist?.let { ManifestKit.audioLanguageLabel(it) }.orEmpty() }
                val isDash = src.kind.equals("dash", true) || src.url.contains(".mpd", true)
                out[src.url] = RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = src.url,
                    isM3u8 = src.kind.equals("hls", true) || src.url.contains(".m3u8", true) || isDash,
                    isDash = isDash,
                    referer = "$origin/",
                    qualityHint = height,
                    audioPriority = when {
                        audioLabel.contains(',') || audioLabel == "Multi" -> 2
                        audioLabel.startsWith("Hindi") -> 4
                        else -> 1
                    },
                    audioLabel = audioLabel,
                    mediaLabel = src.label.ifBlank { src.quality },
                    inlineManifest = master?.takeIf { playlist != null },
                    extraHeaders = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to "$origin/"),
                    subtitles = playlist?.let { SubtitleKit.fromMaster(it, src.url) }.orEmpty(),
                )
            }
        }
        Log.d("VidBolt", "parsed ${out.size} streams for $type/$tmdbId")
        return out.values.toList()
    }

    private suspend fun resolveVidly(
        spec: ServerSpec, tmdbId: Int, type: String, season: Int, episode: Int,
    ): List<RawStream> {
        val origin = "https://vidly.pro"
        val page = if (type == "movie") ServerFarm.buildMovieUrl(spec, tmdbId.toString())
        else ServerFarm.buildTvUrl(spec, tmdbId.toString(), season, episode)
        val html = withTimeoutOrNull(12_000L) {
            catching { app.get(page, timeout = 12, headers = okHeaders("$origin/")).text }.getOrNull()
        } ?: run { Log.w("Vidly", "embed no response"); return emptyList() }
        val urls = vidlyStreams(html)
        if (urls.isEmpty()) {
            Log.w("Vidly", "no stream in embed for $type/$tmdbId")
            return emptyList()
        }
        val out = ArrayList<RawStream>(urls.size)
        for (url in urls.take(4)) {
            val masterText = fetchText(url, "$origin/", timeoutSec = 6)
            val master = ManifestKit.parseMaster(masterText, url)
            val height = master?.let { ManifestKit.bestHeight(it.variants) } ?: 0
            if (height in 1..719) continue
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = url,
                isM3u8 = true,
                referer = "$origin/",
                qualityHint = height,
                audioLabel = master?.let { ManifestKit.audioLanguageLabel(it) }.orEmpty(),
                inlineManifest = masterText?.takeIf { master != null },
                extraHeaders = mapOf("User-Agent" to HttpKit.userAgent, "Referer" to "$origin/"),
                subtitles = master?.let { SubtitleKit.fromMaster(it, url) }.orEmpty(),
            )
        }
        Log.d("Vidly", "parsed ${out.size} streams for $type/$tmdbId")
        return out
    }

    private val VIDLY_M3U8 = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)

    internal fun vidlyStreams(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val text = html.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
        return VIDLY_M3U8.findAll(text)
            .map { it.value.trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
    }

    private suspend fun resolveWecollegeSources(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
        referer: String,
        lanes: List<String>,
        laneTimeoutSec: Long,
        logTag: String,
    ): List<RawStream> {
        if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
        val base = "https://api.wecollege.net"
        val origin = referer.trimEnd('/')
        val headers = mapOf(
            "Referer" to referer,
            "Origin" to origin,
            "User-Agent" to HttpKit.userAgent,
        )
        val seedText = withTimeoutOrNull(6_000L) {
            catching { app.get("$base/seed?mediaId=$tmdbId", timeout = 6, headers = headers).text }.getOrNull()
        } ?: run { Log.w(logTag, "seed no response"); return emptyList() }
        val seed = catching { org.json.JSONObject(seedText).optString("seed") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: run { Log.w(logTag, "no seed in response"); return emptyList() }
        val meta = try {
            TmdbService.fetchMeta(tmdbId, type)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(logTag, "metadata lookup failed: ${t.javaClass.simpleName}")
            null
        }
        val params = listOf(
            "title" to movyEncode(meta?.name.orEmpty()),
            "mediaType" to (if (type == "movie") "movie" else "tv"),
            "year" to meta?.year.orEmpty(),
            "totalSeasons" to (meta?.totalSeasons?.toString() ?: "1"),
            "seasonId" to (if (type == "movie") 1 else season).toString(),
            "episodeId" to (if (type == "movie") 1 else episode).toString(),
            "tmdbId" to tmdbId.toString(),
            "imdbId" to meta?.imdbId.orEmpty(),
        )
        val out = LinkedHashMap<String, RawStream>()
        val deadline = System.currentTimeMillis() + 18_000L
        for (lane in lanes) {
            if (out.size >= 6 || System.currentTimeMillis() > deadline) break
            val query = params.joinToString("&") { "${it.first}=${it.second}" } + "&enc=2&seed=$seed"
            val cipher = withTimeoutOrNull(laneTimeoutSec * 1000L) {
                catching {
                    app.get("$base/$lane/sources?$query", timeout = laneTimeoutSec, headers = headers).text
                }.getOrNull()
            } ?: continue
            val plain = movyDecrypt(cipher, seed, tmdbId) ?: continue
            for (s in movySources(plain)) {
                if (out.containsKey(s.url)) continue
                val isDash = s.url.contains(".mpd", ignoreCase = true)
                out[s.url] = RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url, isM3u8 = s.url.contains(".m3u8", true) || isDash,
                    referer = referer, qualityHint = heightHintOf(s.quality),
                    audioLabel = movyAudio(s.quality),
                    extraHeaders = headers,
                    isDash = isDash,
                )
            }
        }
        Log.d(logTag, "cascade got ${out.size} sources for $type/$tmdbId")
        return out.values.toList()
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
                val lang = entry.optString("title")
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
            out.add(RawStream(
                serverId = spec.id, serverName = spec.name,
                url = url, isM3u8 = isHls, referer = referer,
                qualityHint = height,
                audioLabel = label, inlineManifest = inlineManifest,
            ))
        }

        source.optJSONArray("qualities")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val q = arr.optJSONObject(i) ?: return@mapNotNull null
                val qUrl = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qLabel = q.optString("quality").takeIf { it.isNotBlank() }
                val height = qLabel?.let { HEIGHT_NUM.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = qUrl, isM3u8 = false, referer = referer,
                    qualityHint = height,
                    audioPriority = 0, audioLabel = "",
                )
            }.let { out.addAll(it) }
        }

        if (out.isEmpty()) {
            Log.w("IndStream", "${spec.id}: source present but carried no url/manifest/qualities; source keys=${namesOf(source)}")
        }
        return resolveSubtitleEndpoints(out, listOf(root), apiUrl, okHeaders(referer))
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

    private fun relUrl(base: String, path: String): String = ManifestKit.resolveUrl(base, path)

    private fun okHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = HttpKit.userAgent
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
        private const val MAX_SUBTITLE_ENTRIES = 256
        private data class Entry(val streams: List<RawStream>, val expiresAt: Long)
        private val map = java.util.concurrent.ConcurrentHashMap<String, Entry>()
        private val subtitles = java.util.concurrent.ConcurrentHashMap<String, List<SubtitleKit.Track>>()
        private val subtitleProbes = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

        fun patchLabels(key: String, labels: Map<String, String>) {
            if (labels.isEmpty()) return
            val now = System.currentTimeMillis()
            map.computeIfPresent(key) { _, e ->
                if (now > e.expiresAt) e
                else Entry(
                    e.streams.map { s ->
                        labels[s.url]?.takeIf { it.isNotBlank() }?.let { s.copy(audioLabel = it) } ?: s
                    },
                    e.expiresAt,
                )
            }
        }

        fun subtitlesFor(url: String): List<SubtitleKit.Track> = subtitles[url].orEmpty()

        fun subtitlesProbed(url: String): Boolean {
            val at = subtitleProbes[url] ?: return false
            if (System.currentTimeMillis() - at > TTL_MS) {
                subtitleProbes.remove(url)
                return false
            }
            return true
        }

        fun markSubtitlesProbed(url: String) {
            if (url.isBlank()) return
            subtitleProbes[url] = System.currentTimeMillis()
            while (subtitleProbes.size > MAX_SUBTITLE_ENTRIES * 2) {
                val oldest = subtitleProbes.minByOrNull { it.value }?.key ?: break
                subtitleProbes.remove(oldest)
            }
        }

        fun putSubtitles(url: String, tracks: List<SubtitleKit.Track>) {
            if (url.isBlank() || tracks.isEmpty()) return
            subtitles[url] = tracks
            while (subtitles.size > MAX_SUBTITLE_ENTRIES) {
                val oldest = subtitles.keys.firstOrNull() ?: break
                subtitles.remove(oldest)
            }
        }
    }

    const val FAST_START_MAX_MS: Long = 45_000L

    const val LIVE_FILL_MS: Long = 90_000L
}
