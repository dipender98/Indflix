package com.ottmirror.stream

import com.ottmirror.core.HttpKit
import com.ottmirror.core.ManifestKit
import com.ottmirror.sources.VidlinkSource
/**

 * FILE: StreamEngine.kt — the OTTMirror resolution engine (HOW a TMDB id
 * becomes playable links).
 *
 *  - [StreamEngine]   fans out to healthy servers in parallel, probes audio
 *                     + speed, gates on dual-audio (Hindi first) and emits
 *                     the fastest usable link set.
 *  - [ServerFarm]     server registry + [HealthMonitor] � defined in
 *                     ServerRegistry.kt (data + health state, no orchestration).
 *
 * Distinct from core/CoreServices.kt (stateless primitives: HTTP, TMDB, manifest
 * parsing, title matching) and ServerRegistry.kt (server registry + health
 * data): this file holds the orchestration. Third-party stream sources
 * live in sources/VidLinkSource.kt.
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
            Log.w("OTTMirror", "resolve skipped: invalid tmdbId=$tmdbId")
            return emptyList()
        }
        val healthy = ServerFarm.allServers.filter { HealthMonitor.isHealthy(it.id) }
        Log.d("OTTMirror", "healthy: ${healthy.size}/${ServerFarm.allServers.size}")
        // A fully-tripped farm used to return emptyList() instantly, so every tap
        // showed "no link found" for a full trip window with zero network traffic.
        // Once per cooldown window, clear the trips and re-probe the whole farm --
        // one bad session must never lock the plugin out, but a dead uplink should
        // not trigger a full-farm hammer on every tap either.
        val candidates = if (healthy.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastFarmProbeAt < FARM_REPROBE_COOLDOWN_MS) {
                Log.w("OTTMirror", "all servers tripped; re-probe cooldown active " +
                    "(${(FARM_REPROBE_COOLDOWN_MS - (now - lastFarmProbeAt)) / 1000}s left)")
                emptyList()
            } else {
                lastFarmProbeAt = now
                // Clear only the trip state, keeping latency/throughput history so the
                // speedScore ordering survives the re-probe.
                Log.w("OTTMirror", "all ${ServerFarm.allServers.size} servers tripped -- clearing trips, re-probing all")
                HealthMonitor.resetTrips()
                ServerFarm.allServers
            }
        } else healthy
        val servers = candidates
            .sortedByDescending { HealthMonitor.speedScore(it.id) }
            .take(MAX_SERVERS)
        Log.d("OTTMirror", "resolve tmdb=$tmdbId type=$type s=$season e=$episode imdb=${imdbId ?: "none"} -> ${servers.size} servers")

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
                            Log.w("OTTMirror", "${spec.id}: no result after ${spec.timeoutSec}s (timeout or crash), recording failure")
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

        Log.d("OTTMirror", "resolved ${resolved.size} streams from ${servers.size} servers (${resolved.groupBy { it.serverId }.mapValues { it.value.size }})")

        // Same ranking as emit(): Hindi first, then speed, then quality.
        return resolved.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })
    }

    /**
     * Emit links fastest-first. Dual-audio masters get the adaptive link first.
     */
    suspend fun emit(streams: List<RawStream>, onLink: (ExtractorLink) -> Unit, onSubtitle: (SubtitleFile) -> Unit) {
        if (streams.isEmpty()) return
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())

        // Ranking (user spec: fastest Hindi first, then everything else):
        // 1. audioPriority desc — Hindi-dub/Hindi-audio streams (4) lead,
        //    original (2) / English (1) follow.
        // 2. measuredKbps desc — fastest measured link wins inside each audio
        //    group (JSON-API streams that skip probing — vidlink CDN 429s —
        //    carry null and tie-break on quality below).
        // 3. qualityHint desc — 1080p before 720p before 480p.
        val sorted = streams.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L }
            .thenByDescending { it.qualityHint })

        sorted.forEach { raw ->
            if (raw.url.isBlank()) return@forEach
            if (!emitted.add(raw.url)) return@forEach

            raw.subtitles.forEach { (lang, subUrl) -> onSubtitle(SubtitleFile(lang, subUrl)) }

            // Link headers: per-stream extras first (vidlink CDN streams carry their
            // exact playback requirements there � mwVault rejects ANY Referer,
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
                val label = buildString {
                    append(raw.serverName)
                    if (raw.audioLabel.isNotBlank()) append(" � ${raw.audioLabel}")
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
                    ).forEach { onLink(it) }
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
                        variants.forEach { onLink(it) }
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
    // Internals � multi-strategy pipeline (proven from Multimovies)
    // ------------------------------------------------------------------

    private suspend fun resolveOne(spec: ServerSpec, tmdbId: Int, imdbId: String?, type: String, season: Int, episode: Int): List<RawStream> {
        val start = System.currentTimeMillis()
        val id = if (spec.idType == ServerIdType.IMDB)
            (imdbId ?: run { Log.w("OTTMirror", "${spec.id}: IMDB required but missing"); return emptyList() })
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
                Log.w("OTTMirror", "${spec.id}: tv request without season/episode (s=$season e=$episode), skipping")
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
        Log.d("OTTMirror", "${spec.id}: embed fetched, ${rawText.length}B")

        // 2. Unwrap iframes
        val (unwrapped, unwrappedUrl) = unwrapPages(rawText, embedUrl, spec.timeoutSec)
        if (unwrapped !== rawText) Log.d("OTTMirror", "${spec.id}: unwrapped to $unwrappedUrl, ${unwrapped.length}B")

        // 3. Direct stream URL regex harvest
        val direct = harvestUrls(unwrapped)
        if (direct.isNotEmpty()) {
            Log.d("OTTMirror", "${spec.id}: direct harvest found ${direct.size} urls")
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
            Log.d("OTTMirror", "${spec.id}: no direct urls in page")
        }

        // 4. CloudStream extractor registry (VidSrc, 2embed, embed.su, MyFlixer, etc.)
        val regLinks = mutableListOf<ExtractorLink>()
        val regSubs = mutableListOf<SubtitleFile>()
        val regOk = runCatching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        // Also try the deepest unwrapped URL � most embed chains register a
        // CloudStream extractor on the INNER host (the actual player), not the
        // outer wrapper. The outer page is the correct referer for the inner
        // player's CORS / origin check.
        if (unwrappedUrl != embedUrl) {
            runCatching {
                loadExtractor(url = unwrappedUrl, referer = embedUrl, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
            }
        }
        if (regLinks.isNotEmpty()) {
            Log.d("OTTMirror", "${spec.id}: extractor registry returned ${regLinks.size} links (outerOk=$regOk unwrapped=$unwrappedUrl)")
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
            Log.d("OTTMirror", "${spec.id}: extractor registry returned no links (outerOk=$regOk unwrapped=$unwrappedUrl)")
        }

        // 5. JS config: file:"...", sources:[{file:"..."}]
        val jsUrls = harvestJsUrls(unwrapped)
        if (jsUrls.isNotEmpty()) {
            Log.d("OTTMirror", "${spec.id}: js harvest found ${jsUrls.size} urls")
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
            Log.d("OTTMirror", "${spec.id}: no js harvest")
        }

        // 6. <video src> / <source src> HTML elements
        val videoSrc = harvestVideoSource(unwrapped, embedUrl)
        if (videoSrc != null) {
            Log.d("OTTMirror", "${spec.id}: video tag found: $videoSrc")
            val subs = grabSubtitles(unwrapped)
            val probed = HttpKit.probeSpeed(videoSrc, referer)
            val pri = probeAudio(videoSrc, referer)
            okServer(spec, start, "video tag", 1)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                audioPriority = pri, audioLabel = audioLabelFor(pri)))
        } else {
            Log.d("OTTMirror", "${spec.id}: no video tag")
        }

        // 7. Subtitle-only fallback
        val subs = grabSubtitles(unwrapped)
        if (subs.isNotEmpty()) {
            Log.d("OTTMirror", "${spec.id}: subtitles only (${subs.size})")
            okServer(spec, start, "subtitles only", 0)
            return listOf(RawStream(spec.id, spec.name, "", false, referer, subtitles = subs))
        }

        failServer(spec, "no harvestable stream across full pipeline")
        return emptyList()
    }

    /** Log + trip a server. Single choke point so every failure names its reason. */
    private fun failServer(spec: ServerSpec, reason: String) {
        Log.w("OTTMirror", "${spec.id}: $reason")
        HealthMonitor.recordFailure(spec.id)
    }

    /** Log + record a successful resolution for a server. */
    private fun okServer(spec: ServerSpec, start: Long, stage: String, streamCount: Int) {
        val ms = System.currentTimeMillis() - start
        Log.d("OTTMirror", "${spec.id}: OK via $stage, $streamCount streams in ${ms}ms")
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
        return ManifestKit.audioPriority(master)
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

        val jsonText = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get(apiUrl, timeout = 8, headers = vidlinkHeaders(mediaPage)).text
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
        // code 2004 here is the API's token error � NOT ExoPlayer's
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
        // Direct MP4s � no playlist fetch, no speed probe (the CDN rate-limits
        // hard). Playback headers are per-source (see
        // VidlinkSource.PLAYER_HEADERS): mwVault CDN 429s on any Referer so
        // only the native UA is sent; mbVault requires the API-provided
        // headers. referer stays null so emit() injects nothing extra � a
        // blanket vidlink.pro Referer is exactly what breaks playback with
        // ExoPlayer ERROR_CODE_IO_BAD_HTTP_STATUS (2004).
        val qualities = stream.optJSONObject("qualities")
        if (qualities != null && qualities.length() > 0) {
            val entries = qualities.names()?.let { n ->
                (0 until n.length()).mapNotNull { i ->
                    val key = n.optString(i)
                    val q = qualities.optJSONObject(key) ?: return@mapNotNull null
                    val url = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Triple(key.toIntOrNull() ?: 0, url, q)
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

        // Legacy shape: stream.playlist (HLS master) � kept for when VidLink
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


    private suspend fun resolveMyFlixerHindi(
        spec: ServerSpec,
        tmdbId: Int?,
        imdbId: String?,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val id = if (spec.idType == ServerIdType.IMDB) imdbId ?: return emptyList() else tmdbId?.toString() ?: return emptyList()
        val embedUrl = if (type == "movie") {
            "https://hindi.myflixerapi.com/embed/movie?imdb=$id"
        } else {
            "https://hindi.myflixerapi.com/embed/series?imdb=$id&sea=$season&epi=$episode"
        }
        val referer = "https://hindi.myflixerapi.com/"
        Log.d("MyFlixerHindi", "Fetching $embedUrl")
        val rawText = withTimeoutOrNull(8_000L) {
            runCatching { app.get(embedUrl, timeout = 8, headers = okHeaders(referer)).text }.getOrNull()
        } ?: return emptyList()
        val (unwrapped, _) = unwrapPages(rawText, embedUrl, 12)

        // Try to extract m3u8 from player config in script tags
        val doc = Jsoup.parse(unwrapped)
        val sources = mutableListOf<String>()

        // Look for script tags containing JWPlayer or Clappr config
        doc.select("script").forEach { script ->
            val data = script.data()
            // Match patterns like file:"http...m3u8", url:"http...m3u8", source:"http...m3u8"
            val regex = Regex("""["']?(?:file|url|src|source|video_url|stream_url)["']?\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            regex.findAll(data).forEach { match ->
                match.groupValues[1].takeIf { it.isNotBlank() }?.let { sources.add(it) }
            }
        }

        // Also try harvestJsUrls as fallback
        if (sources.isEmpty()) {
            sources.addAll(harvestJsUrls(unwrapped))
        }

        if (sources.isNotEmpty()) {
            val pri = 4 // force Hindi
            return sources.map { url ->
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = url, isM3u8 = url.contains(".m3u8", ignoreCase = true),
                    referer = referer, audioPriority = pri, audioLabel = "Hindi"
                )
            }
        }
        return emptyList()
    }

    /**
     * NHD resolver (reversed Sept 2026): the extraction API key is embedded
     * PER-PAGE-LOAD (stale keys 401), so:
     *   1. GET nhdapi.com/movie/{id} (or /tv/{id}/{s}/{e}) and extract
     *      `var API_KEY = "..."` and `/api/movie/{id}` from the page JS.
     *   2. GET nhdapi.com/api/movie/{id}?key=... with the page as Referer.
     *   3. Response carries playUrl (their /api/hls?t= proxy, needs the
     *      page's exact UA) — emit with page UA + no Referer.
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

        // 1. Page first — carries the per-load API key.
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
        // The page JS maps /api/subtitles — the JSON carries none, skip.

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
     * API → `{serverName: {url (AES-GCM b64url), language, type}}`. URLs are
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

    /** VidRock AES-GCM decrypt — 12-byte nonce prefix, static 32-byte hex key.
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
                referer = null, qualityHint = 1080, subtitles = emptyList(),
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
     * The signed stream URLs carry no file extension � JSON parsing is mandatory.
     */
    private suspend fun resolveJsonApi(
        spec: ServerSpec,
        apiUrl: String,
        referer: String,
    ): List<RawStream> {
        Log.d("OTTMirror", "${spec.id}: jsonApi GET $apiUrl")
        val jsonText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(apiUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (jsonText.isNullOrBlank()) {
            Log.w("OTTMirror", "${spec.id}: no JSON response (timeout/HTTP error) from $apiUrl")
            return emptyList()
        }

        Log.d("OTTMirror", "${spec.id}: jsonApi response ${jsonText.length}B, preview=${safeSnippet(jsonText)}")

        val root = runCatching { org.json.JSONObject(jsonText) }.getOrElse {
            Log.w("OTTMirror", "${spec.id}: non-JSON response from $apiUrl (${jsonText.length}B, starts: ${safeSnippet(jsonText)})")
            return emptyList()
        }
        val source = root.optJSONObject("source") ?: run {
            val nullSource = root.isNull("source")
            Log.w("OTTMirror", "${spec.id}: missing \"source\" (${if (nullSource) "null -- id not known to this server" else "absent"}); root keys=${namesOf(root)}")
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
        // playlist inline (variant URIs are absolute https URLs) � the signed url has
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

        // Per-quality MP4s (source.qualities[]) � direct VIDEO links.
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
            Log.w("OTTMirror", "${spec.id}: source present but carried no url/manifest/qualities; source keys=${namesOf(source)}")
        }
        return out
    }

    /** Follow iframes to the deepest player page. Returns the deepest HTML and
     *  its final URL so [resolveOne] can also pass the inner URL to
     *  [loadExtractor] � many embed chains (2embed -> vidsrc, superembed -> cloud-hosted
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




