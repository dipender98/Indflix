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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private const val MAX_CONCURRENT = 5
    private const val MAX_SERVERS = 12
    private const val MAX_UNWRAP = 4
    /** Minimum stream height to emit (user spec: 720p and above only). */
    private const val MIN_QUALITY_P = 720
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
    )

    /**
     * Resolve all streams across the farm.
     */
    suspend fun resolve(tmdbId: Int, imdbId: String?, type: String, season: Int = -1, episode: Int = -1): List<RawStream> {
        if (tmdbId <= 0) {
            Log.w("IndStream", "resolve skipped: invalid tmdbId=$tmdbId")
            return emptyList()
        }
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
        Log.d("IndStream", "resolve tmdb=$tmdbId type=$type s=$season e=$episode imdb=${imdbId ?: "none"} -> ${servers.size} servers (hindi-first: ${hindiFirst.map { it.id }})")

        val sem = Semaphore(MAX_CONCURRENT)
        val resolved = coroutineScope {
            servers.map { spec ->
                async {
                    sem.acquire()
                    try {
                        val outcome = withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            runCatching { resolveOne(spec, tmdbId, imdbId, type, season, episode) }.getOrNull()
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
                        outcome?.map { s ->
                            if (spec.hindi) s.copy(audioPriority = 4, audioLabel = "Hindi") else s
                        }
                    } finally { sem.release() }
                }
            }.awaitAll().filterNotNull().flatten()
        }

        Log.d("IndStream", "resolved ${resolved.size} streams from ${servers.size} servers (${resolved.groupBy { it.serverId }.mapValues { it.value.size }})")

        // Same ranking as emit(): Hindi first, then speed, then quality.
        return resolved.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })
    }

    /**
     * Emit links fastest-first. Dual-audio masters get the adaptive link first.
     *
     * Quality gate (user spec): only 720p and above are emitted â€” 360/480p
     * sources are dropped here, the single choke point every server's results
     * flow through. Streams with qualityHint 0 (unknown, e.g. adaptive HLS
     * masters) are kept: their variants get labelled by M3u8Helper at playback.
     */
    suspend fun emit(streams: List<RawStream>, onLink: (ExtractorLink) -> Unit, onSubtitle: (SubtitleFile) -> Unit) {
        if (streams.isEmpty()) return
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())

        // Ranking (user spec: fastest Hindi first, then everything else):
        // 1. audioPriority desc â€” Hindi-dub/Hindi-audio streams (4) lead,
        //    original (2) / English (1) follow.
        // 2. measuredKbps desc â€” fastest measured link wins inside each audio
        //    group (JSON-API streams that skip probing â€” vidlink CDN 429s â€”
        //    carry null and tie-break on quality below).
        // 3. qualityHint desc â€” 1080p before 720p before 480p.
        val sorted = streams.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })

        sorted.forEach { raw ->
            if (raw.url.isBlank()) return@forEach
            if (!emitted.add(raw.url)) return@forEach

            // Quality gate: drop sub-720p sources (user spec: 720p minimum).
            // qualityHint 0 = unknown height (adaptive master) â€” kept, the
            // player picks the variant; per-variant filtering happens below
            // for M3U8s whose master declares heights.
            if (raw.qualityHint in 1 until MIN_QUALITY_P) {
                Log.d("IndStream", "emit: dropping ${raw.serverName} ${raw.qualityHint}p (< ${MIN_QUALITY_P}p): ${raw.url.take(60)}")
                return@forEach
            }

            raw.subtitles.forEach { (lang, subUrl) -> onSubtitle(SubtitleFile(lang, subUrl)) }

            // Link headers: per-stream extras first (vidlink CDN streams carry their
            // exact playback requirements there ï¿½ mwVault rejects ANY Referer,
            // mbVault needs the API-provided origin/referer), then a Referer
            // fallback only for streams that declare one (embed servers).
            // Never emit an empty Referer: the vidlink CDN 429s on its mere
            // presence, which the player surfaces as
            // ExoPlayer ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
            val linkHeaders = LinkedHashMap<String, String>()
            linkHeaders.putAll(raw.extraHeaders)
            if (!linkHeaders.containsKey("Referer") && !raw.referer.isNullOrBlank()) {
                linkHeaders["Referer"] = raw.referer!!
            }

            if (raw.isM3u8) {
                val masterText = raw.inlineManifest ?: withTimeoutOrNull(4000L) {
                    runCatching {
                        app.get(raw.url, timeout = 4, headers = linkHeaders).text
                    }.getOrNull()
                }
                val master = ManifestKit.parseMaster(masterText, raw.url)
                // Debug: what audio renditions does this master actually carry?
                master?.let {
                    val langs = it.audio.map { r -> "lang=${r.language ?: "none"} name=${r.name} group=${r.groupId}" }
                    Log.d("IndStream", "audioTracks server=${raw.serverName} multiAudio=${it.isMultiAudio} renditions=$langs")
                }
                val label = buildString {
                    append(raw.serverName)
                    if (raw.audioLabel.isNotBlank()) append(" ï¿½ ${raw.audioLabel}")
                }

                if (master?.isMultiAudio == true) {
                    onLink(ExtractorLink(
                        source = raw.serverName, name = "$label Auto",
                        url = raw.url, referer = raw.referer ?: "",
                        quality = ManifestKit.bestHeight(master.variants).takeIf { it > 0 } ?: raw.qualityHint,
                        headers = linkHeaders, type = ExtractorLinkType.M3U8,
                    ))
                    M3u8Helper.generateM3u8(raw.serverName, raw.url, raw.referer ?: "",
                        quality = raw.qualityHint.takeIf { it > 0 },
                        headers = linkHeaders,
                    ).filter { it.quality <= 0 || it.quality >= MIN_QUALITY_P }
                        .forEach { onLink(it) }
                    master.subtitles.forEach { r ->
                        r.uri?.let { onSubtitle(SubtitleFile(r.language ?: r.name, ManifestKit.resolveUrl(raw.url, it))) }
                    }
                } else {
                    val variants = M3u8Helper.generateM3u8(raw.serverName, raw.url, raw.referer ?: "",
                        quality = raw.qualityHint.takeIf { it > 0 },
                        headers = linkHeaders,
                    )
                    if (variants.isEmpty()) {
                        onLink(ExtractorLink(
                            source = raw.serverName,
                            name = "$label Auto",
                            url = raw.url,
                            referer = raw.referer ?: "",
                            quality = raw.qualityHint,
                            headers = linkHeaders,
                            type = ExtractorLinkType.M3U8,
                        ))
                    } else {
                        // Per-variant gate: adaptive masters expose every rung
                        // (240â†’1080); keep only 720p+ variants (plus the auto
                        // master link emitted above for ABR playback).
                        variants.filter { it.quality <= 0 || it.quality >= MIN_QUALITY_P }
                            .forEach { onLink(it) }
                    }
                    master?.subtitles?.forEach { r ->
                        r.uri?.let { onSubtitle(SubtitleFile(r.language ?: r.name, ManifestKit.resolveUrl(raw.url, it))) }
                    }
                }
            } else {
                onLink(ExtractorLink(
                    source = raw.serverName, name = "${raw.serverName} ${ManifestKit.qualityLabel(raw.qualityHint)}".trim(),
                    url = raw.url, referer = raw.referer ?: "", quality = raw.qualityHint,
                    headers = linkHeaders, type = ExtractorLinkType.VIDEO,
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals ï¿½ multi-strategy pipeline (proven from Multimovies)
    // ------------------------------------------------------------------

    private suspend fun resolveOne(spec: ServerSpec, tmdbId: Int, imdbId: String?, type: String, season: Int, episode: Int): List<RawStream> {
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
            failServer(spec, "moviebox returned no streams")
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

    /** Log + trip a server. Single choke point so every failure names its reason. */
    private fun failServer(spec: ServerSpec, reason: String) {
        Log.w("IndStream", "${spec.id}: $reason")
        HealthMonitor.recordFailure(spec.id)
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
        val subs = (stream.optJSONArray("captions") ?: root.optJSONArray("captions"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = c.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lang = c.optString("lang").ifBlank { c.optString("name") }.ifBlank { "English" }
                lang to u
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
                    // Filter out streams below 720p
                    if (height < 720) return@mapNotNull null
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

        // ── 2. Embed page (cookie warm-up + mobile UA to pass the wall) ────
        // Series episodes use the documented /embed/series?imdb=&sea=&epi= form.
        val embedUrl = if (isMovie || season <= 0) "https://hindi.myflixerapi.com/embed/$id"
        else "https://hindi.myflixerapi.com/embed/series?imdb=$id&sea=$season&epi=$episode"
        // Warm-up: the anti-bot wall is cookie-gated; a homepage GET seeds the
        // app's shared cookie jar before the embed request.
        runCatching {
            withTimeoutOrNull(5_000L) { app.get(referer, timeout = 5, headers = okHeaders(referer)) }
        }
        val mobileHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to referer,
        )
        Log.d("MyFlixerHindi", "Fetching $embedUrl")
        val rawText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(embedUrl, timeout = 8, headers = mobileHeaders).text }.getOrNull()
        } ?: run { failServer(spec, "embed fetch failed: $embedUrl"); return emptyList() }

        // Genuine library miss page (checked BEFORE the wall — the miss page
        // also carries the robot banner in its footer).
        if (rawText.contains("movie-not-found") || rawText.contains("Movie or Episode Not Found", ignoreCase = true)) {
            Log.d("MyFlixerHindi", "$id not in library (embed page miss)")
            return emptyList()
        }
        // Anti-bot wall — the page never renders data-movie-id behind it.
        if (rawText.contains("not a robot", ignoreCase = true) || rawText.contains("CONFIRM YOU ARE NOT A ROBOT", ignoreCase = true)) {
            failServer(spec, "embed page behind anti-bot wall (${rawText.length}B) — cookie warm-up did not pass")
            return emptyList()
        }

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

        // ── 3. AJAX chain (unchanged shape; endpoint may return if the wall
        //       was transient — it 404s cleanly otherwise) ──────────────────
        val ajaxHeaders = mobileHeaders.toMutableMap().apply {
            put("X-Requested-With", "XMLHttpRequest")
        }
        data class FlixerLink(val url: String, val label: String?)
        val links = mutableListOf<FlixerLink>()
        for (sid in serverIds) {
            val ajaxUrl = "https://hindi.myflixerapi.com/ajax/get_stream_link?id=$sid&movie=$movieId&is_init=false&captcha="
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

        val sources = VideasySource.fetchSources(
            tmdbId = tmdbId, imdbId = imdbId, title = title, year = year,
            mediaType = type, season = season, episode = episode,
        )
        if (sources.isEmpty()) return emptyList()

        val out = sources
            .filter { it.quality.equals("Hindi", ignoreCase = true) }
            .map { s ->
                val isHls = s.url.contains(".m3u8", ignoreCase = true)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = s.url, isM3u8 = isHls,
                    referer = null, qualityHint = 0, // adaptive master; heights come from variants
                    subtitles = emptyList(),
                    audioPriority = 4, audioLabel = "Hindi",
                    extraHeaders = VideasySource.apiHeaders(),
                )
            }
        if (out.isEmpty()) Log.d("VideasyHindi", "sources present but no Hindi label: ${sources.map { it.quality }}")
        return out
    }

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
            Log.w("MovieBox", "no title for tmdb=$tmdbId (title-keyed API)")
            return emptyList()
        }
        val seasonKey = if (season > 0) season else 1
        val episodeKey = if (episode > 0) episode else 1

        val host = "h5-api.aoneroom.com"
        val base = "https://$host"

        // 1. Bearer token from the x-user response header.
        val xUser = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get("$base/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
                    timeout = 8, headers = okHeaders())
            }.getOrNull()
        }?.headers?.get("x-user") ?: run { Log.w("MovieBox", "no x-user header"); return emptyList() }
        val token = runCatching { org.json.JSONObject(xUser).optString("token", "") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: run { Log.w("MovieBox", "no token in x-user"); return emptyList() }

        val baseHeaders = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Asia/Kolkata\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to base,
            "Connection" to "keep-alive",
            "Authorization" to "Bearer $token",
        )

        // 2. Title search (subjectType 1=movie, 2=tv).
        val subjectType = if (type == "movie") 1 else 2
        val searchJsonText = withTimeoutOrNull(10_000L) {
            runCatching {
                app.post("$base/wefeed-h5api-bff/subject/search", timeout = 10, headers = baseHeaders,
                    json = mapOf(
                        "keyword" to title, "page" to 1, "perPage" to 24,
                        "subjectType" to subjectType,
                    ))
            }.getOrNull()
        }?.text ?: run { Log.w("MovieBox", "search failed"); return emptyList() }

        fun unwrapData(json: org.json.JSONObject): org.json.JSONObject {
            val d = json.optJSONObject("data") ?: return json
            return d.optJSONObject("data") ?: d
        }

        val searchObj = runCatching { org.json.JSONObject(searchJsonText) }.getOrElse {
            Log.w("MovieBox", "search not JSON"); return emptyList()
        }
        val items = unwrapData(searchObj).optJSONArray("items")
            ?: run { Log.w("MovieBox", "no search items"); return emptyList() }

        // "Title [Hindi]" → audio; "Title S1-S3" trailing suffix is season
        // coverage, stripped before matching. Clean title must equal the TMDB
        // title exactly (case-insensitive) to avoid wrong-title matches.
        val seasonSuffix = Regex("""\s+S\d+(?:\s*-\s*S?\d+)?$""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""^${Regex.escape(title)}(?:\s+\[([^\]]+)])?$""", RegexOption.IGNORE_CASE)
        val subjects = mutableListOf<Triple<String, Int, String?>>() // id, seasonEnd, language
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("subjectId").takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title", "")
            val clean = seasonSuffix.replace(rawTitle, "")
            val m = titleRegex.find(clean) ?: continue
            val seasonEnd = seasonSuffix.find(rawTitle)?.value
                ?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }?.toIntOrNull()
            subjects += Triple(id, seasonEnd ?: 1, m.groupValues[1])
        }
        if (subjects.isEmpty()) {
            Log.d("MovieBox", "no exact title match for '$title'")
            return emptyList()
        }
        Log.d("MovieBox", "subjects=${subjects.map { it.first + ":" + (it.third ?: "orig") }}")

        val refererBase = "https://fmoviesunblocked.net/"
        val out = mutableListOf<RawStream>()
        val seenUrls = mutableSetOf<String>()

        for ((subjectId, seasonEnd, language) in subjects) {
            // Series entry covering fewer seasons than requested can't serve
            // this episode (their library splits long shows into S1-S3 / S4-…).
            if (type != "movie" && season > seasonEnd) continue

            // 3. detailPath lookup.
            val detailText = withTimeoutOrNull(8_000L) {
                runCatching {
                    app.get("https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
                        timeout = 8, headers = okHeaders()).text
                }.getOrNull()
            } ?: continue
            val detailPath = runCatching { org.json.JSONObject(detailText) }.getOrNull()
                ?.optJSONObject("data")
                ?.optJSONArray("items")?.optJSONObject(0)
                ?.optJSONObject("subject")
                ?.optString("detailPath", "").orEmpty()
            if (detailPath.isBlank()) continue

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

            fun addStreams(arr: org.json.JSONArray?, dash: Boolean) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.optBoolean("vipLocked", false)) continue
                    val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                    if (!seenUrls.add(url)) continue
                    val resolution = s.optString("resolutions", "").toIntOrNull()
                        ?: s.optInt("resolution", 0)
                    out += RawStream(
                        serverId = spec.id,
                        serverName = spec.name + if (dash) " Auto" else "",
                        url = url,
                        isM3u8 = url.contains(".m3u8", ignoreCase = true) || dash,
                        referer = refererBase,
                        qualityHint = resolution,
                        audioPriority = if (language?.contains("hindi", ignoreCase = true) == true) 4 else 2,
                        audioLabel = language ?: "",
                    )
                }
            }
            addStreams(unwrapData(downloadObj).optJSONArray("downloads"), dash = false)
            addStreams(unwrapData(playObj).optJSONArray("streams"), dash = false)
            addStreams(unwrapData(playObj).optJSONArray("dash"), dash = true)
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
                    referer = null, qualityHint = 1080, subtitles = subs,
                    audioPriority = if (isHindi) 4 else 1,
                    audioLabel = if (isHindi) "Hindi" else label,
                    extraHeaders = mapOf("User-Agent" to NHD_UA),
                )
            }
        } else if (!playUrl.isNullOrBlank()) {
            out += RawStream(
                serverId = spec.id, serverName = spec.name,
                url = playUrl, isM3u8 = kind != "mp4",
                referer = null, qualityHint = 1080, subtitles = subs,
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
        if (root.optInt("status_code", 0) != 200) {
            Log.w("VaPlayer", "status_code=${root.optInt("status_code", -1)} (404 = not in catalog)")
            return emptyList()
        }
        val data = root.optJSONObject("data") ?: return emptyList()
        val urls = data.optJSONArray("stream_urls") ?: return emptyList()
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
                qualityHint = 0, subtitles = subs,
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
                qualityHint = 1080, subtitles = emptyList(),
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
                referer = null, qualityHint = 1080, subtitles = emptyList(),
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
     * JSON API resolver (api.shows.st / 111Movies shape):
     * `{ "source": { "url": ..., "qualities": [{"quality","url"}] }, "subtitles": [...] }`
     * The signed stream URLs carry no file extension ï¿½ JSON parsing is mandatory.
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
}




