package com.ottmirror.stream

import com.ottmirror.core.HttpKit
import com.ottmirror.core.ManifestKit
import com.ottmirror.sources.VidlinkSource
/**

 * FILE: StreamEngine.kt â€” the OTTMirror resolution engine (HOW a TMDB id
 * becomes playable links).
 *
 *  - [StreamEngine]   fans out to healthy servers in parallel, probes audio
 *                     + speed, gates on dual-audio (Hindi first) and emits
 *                     the fastest usable link set.
 *  - [ServerFarm]     server registry + [HealthMonitor] — defined in
 *                     ServerRegistry.kt (data + health state, no orchestration).
 *
 * Distinct from core/CoreServices.kt (stateless primitives: HTTP, TMDB, manifest
 * parsing, title matching) and ServerRegistry.kt (server registry + health
 * data): this file holds the orchestration. Third-party stream sources
 * live in sources/VidLinkSource.kt.
 */

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
        if (tmdbId <= 0) return emptyList()
        val servers = ServerFarm.allServers
            .filter { HealthMonitor.isHealthy(it.id) }
            .sortedByDescending { HealthMonitor.speedScore(it.id) }
            .take(MAX_SERVERS)
        if (servers.isEmpty()) return emptyList()

        val sem = Semaphore(MAX_CONCURRENT)
        val resolved = coroutineScope {
            servers.map { spec ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            runCatching { resolveOne(spec, tmdbId, imdbId, type, season, episode) }.getOrNull()
                        }
                    } finally { sem.release() }
                }
            }.awaitAll().filterNotNull().flatten()
        }

        // Prefer Hindi-capable streams while keeping every successful server visible.
        return resolved.sortedByDescending { it.audioPriority }
    }

    /**
     * Emit links fastest-first. Dual-audio masters get the adaptive link first.
     */
    suspend fun emit(streams: List<RawStream>, onLink: (ExtractorLink) -> Unit, onSubtitle: (SubtitleFile) -> Unit) {
        if (streams.isEmpty()) return
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())

        // Sort: Hindi priority first, then by speed
        val sorted = streams.sortedWith(compareByDescending<RawStream> { it.audioPriority }
            .thenByDescending { it.measuredKbps ?: 0L })

        sorted.forEach { raw ->
            if (raw.url.isBlank()) return@forEach
            if (!emitted.add(raw.url)) return@forEach

            raw.subtitles.forEach { (lang, subUrl) -> onSubtitle(SubtitleFile(lang, subUrl)) }

            // Link headers: Referer first (some CDNs require it), then per-stream
            // extras (e.g. ExoPlayer UA for the vidlink CDN which 428s on browser UAs).
            // Using a LinkedHashMap preserves order; an extra header that collides with
            // Referer overrides it.
            val linkHeaders = LinkedHashMap<String, String>()
            linkHeaders["Referer"] = raw.referer ?: ""
            linkHeaders.putAll(raw.extraHeaders)

            if (raw.isM3u8) {
                val masterText = raw.inlineManifest ?: withTimeoutOrNull(4000L) {
                    runCatching {
                        app.get(raw.url, timeout = 4, headers = linkHeaders).text
                    }.getOrNull()
                }
                val master = ManifestKit.parseMaster(masterText, raw.url)
                val label = buildString {
                    append(raw.serverName)
                    if (raw.audioLabel.isNotBlank()) append(" • ${raw.audioLabel}")
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
                    M3u8Helper.generateM3u8(raw.serverName, raw.url, raw.referer ?: "",
                        quality = raw.qualityHint.takeIf { it > 0 },
                        headers = linkHeaders,
                    ).forEach { onLink(it) }
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
    // Internals — multi-strategy pipeline (proven from Multimovies)
    // ------------------------------------------------------------------

    private suspend fun resolveOne(spec: ServerSpec, tmdbId: Int, imdbId: String?, type: String, season: Int, episode: Int): List<RawStream> {
        val start = System.currentTimeMillis()
        val id = if (spec.idType == ServerIdType.IMDB) (imdbId ?: return emptyList()) else tmdbId.toString()
        val embedUrl = if (type == "movie") ServerFarm.buildMovieUrl(spec, id)
        else ServerFarm.buildTvUrl(spec, id, season, episode)
        // Per-server referer (some APIs 403 without it, e.g. api.shows.st).
        val referer = spec.referer ?: embedUrl.substringBefore("?")

        // VidLink: encrypted-token API. Token embeds the TMDB id + a +480s
        // timestamp (VidlinkSource); the response carries stream.playlist (an
        // adaptive multi-audio master up to 1080p) + captions. Key rotation
        // (rare) is fixed by updating VidlinkSource.KEY_HEX only.
        if (spec.id == "vidlink") {
            if (type != "movie" && (season <= 0 || episode <= 0)) return emptyList()
            val result = resolveVidlink(spec, tmdbId, type, season, episode, start)
            if (result.isNotEmpty()) return result
            HealthMonitor.recordFailure(spec.id)
            return emptyList()
        }

        // 0. JSON API branch (api.shows.st style): parse JSON, take source.url +
        //    source.qualities[] + subtitles[]. The signed stream URLs carry no
        //    file extension, so regex harvest would never find them.
        if (spec.isJsonApi) {
            val result = resolveJsonApi(spec, embedUrl, referer, start)
            if (result.isNotEmpty()) return result
            HealthMonitor.recordFailure(spec.id)
            return emptyList()
        }

        // 1. Fetch embed page
        val rawText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(embedUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        }
        if (rawText.isNullOrBlank()) { HealthMonitor.recordFailure(spec.id); return emptyList() }

        // 2. Unwrap iframes
        val unwrapped = unwrapPages(rawText, embedUrl, spec.timeoutSec)

        // 3. Direct stream URL regex harvest
        val direct = harvestUrls(unwrapped)
        if (direct.isNotEmpty()) {
            val subs = grabSubtitles(unwrapped)
            val result = direct.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val pri = probeAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return result
        }

        // 4. CloudStream extractor registry (VidSrc, 2embed, embed.su etc.)
        val regLinks = mutableListOf<ExtractorLink>()
        val regSubs = mutableListOf<SubtitleFile>()
        val regOk = runCatching {
            loadExtractor(url = embedUrl, referer = referer, subtitleCallback = { regSubs.add(it) }, callback = { regLinks.add(it) })
        }.getOrDefault(false)
        if (regOk && regLinks.isNotEmpty()) {
            val result = regLinks.map { link ->
                val probed = HttpKit.probeSpeed(link.url, link.referer)
                val pri = if (link.url.contains(".m3u8", ignoreCase = true)) probeAudio(link.url, link.referer) else 0
                RawStream(spec.id, spec.name, link.url, link.type == ExtractorLinkType.M3U8, link.referer, link.quality, probed,
                    regSubs.map { it.lang to it.url },
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return result
        }

        // 5. JS config: file:"...", sources:[{file:"..."}]
        val jsUrls = harvestJsUrls(unwrapped)
        if (jsUrls.isNotEmpty()) {
            val subs = grabSubtitles(unwrapped)
            val result = jsUrls.map { url ->
                val probed = HttpKit.probeSpeed(url, referer)
                val pri = probeAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                    audioPriority = pri, audioLabel = audioLabelFor(pri))
            }
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return result
        }

        // 6. <video src> / <source src> HTML elements
        val videoSrc = harvestVideoSource(unwrapped, embedUrl)
        if (videoSrc != null) {
            val subs = grabSubtitles(unwrapped)
            val probed = HttpKit.probeSpeed(videoSrc, referer)
            val pri = probeAudio(videoSrc, referer)
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs,
                audioPriority = pri, audioLabel = audioLabelFor(pri)))
        }

        // 7. Subtitle-only fallback
        val subs = grabSubtitles(unwrapped)
        if (subs.isNotEmpty()) {
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return listOf(RawStream(spec.id, spec.name, "", false, referer, subtitles = subs))
        }

        HealthMonitor.recordFailure(spec.id)
        return emptyList()
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
        start: Long,
    ): List<RawStream> {
        val apiUrl = if (type == "movie") VidlinkSource.movieApiUrl(tmdbId.toString())
        else VidlinkSource.tvApiUrl(tmdbId.toString(), season, episode)
        val mediaPage = if (type == "movie") "https://vidlink.pro/movie/$tmdbId"
        else "https://vidlink.pro/tv/$tmdbId/$season/$episode"

        val jsonText = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get(apiUrl, timeout = 8, headers = vidlinkHeaders(mediaPage)).text
            }.getOrNull()
        } ?: return emptyList()
        val root = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()

        // VidLink API error response: {"error":"Invalid token","code":2004}
        if (root.has("error") || root.has("code")) {
            val err = root.optJSONObject("error") ?: root
            val code = err.optInt("code", -1)
            val msg = err.optString("message").ifBlank { err.optString("error") }.ifBlank { "unknown" }
            android.util.Log.w("VidLink", "API error code=$code msg=$msg")
            HealthMonitor.recordFailure(spec.id)
            return emptyList()
        }

        val stream = root.optJSONObject("stream") ?: return emptyList()

        // Captions live at stream.captions (new shape) or root.captions (legacy).
        val subs = (stream.optJSONArray("captions") ?: root.optJSONArray("captions"))?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = c.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lang = c.optString("lang").ifBlank { c.optString("name") }.ifBlank { "English" }
                lang to u
            }
        } ?: emptyList()

        // New shape (Sept 2026, sourceId mwVault): stream.qualities maps
        // "360"/"480"/"720"/"1080" -> {type:"mp4", url (signed, TTL 3600), ...}.
        // Direct MP4s — no playlist fetch, no speed probe (the CDN rate-limits
        // hard). The CDN User-Agent-fingerprints requests and 428/429-rejects
        // browser UAs, so the emission carries VidlinkSource.PLAYER_HEADERS
        // (ExoPlayer UA) for playback to succeed.
        val qualities = stream.optJSONObject("qualities")
        if (qualities != null && qualities.length() > 0) {
            val entries = qualities.names()?.let { n ->
                (0 until n.length()).mapNotNull { i ->
                    val key = n.optString(i)
                    val q = qualities.optJSONObject(key) ?: return@mapNotNull null
                    val url = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    (key.toIntOrNull() ?: 0) to url
                }
            }.orEmpty().sortedByDescending { it.first }
            if (entries.isNotEmpty()) {
                HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
                return entries.map { (height, url) ->
                    RawStream(
                        serverId = spec.id,
                        serverName = spec.name,
                        url = url,
                        isM3u8 = false,
                        referer = "https://vidlink.pro/",
                        qualityHint = height,
                        subtitles = subs,
                        extraHeaders = VidlinkSource.PLAYER_HEADERS,
                    )
                }
            }
        }

        // Legacy shape: stream.playlist (HLS master) — kept for when VidLink
        // serves an adaptive playlist again.
        val masterUrl = stream.optString("playlist").takeIf { it.isNotBlank() }
            ?: root.optString("url").takeIf { it.isNotBlank() }
            ?: return emptyList()

        // Fetch the master playlist once: audio priority + best height inline.
        val masterText = withTimeoutOrNull(6_000L) {
            runCatching {
                app.get(masterUrl, timeout = 6, headers = vidlinkHeaders(mediaPage)).text
            }.getOrNull()
        }
        val master = ManifestKit.parseMaster(masterText, masterUrl)
        val height = master?.let { ManifestKit.bestHeight(it.variants) } ?: 0
        val pri = master?.let { ManifestKit.audioPriority(it) } ?: 0

        HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
        return listOf(
            RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = masterUrl,
                isM3u8 = master != null || masterUrl.contains(".m3u8", ignoreCase = true),
                referer = "https://vidlink.pro/",
                qualityHint = height,
                subtitles = subs,
                audioPriority = pri,
                audioLabel = audioLabelFor(pri),
                inlineManifest = masterText?.takeIf { master != null },
            )
        )
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
        start: Long,
    ): List<RawStream> {
        val jsonText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(apiUrl, timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(referer)).text
            }.getOrNull()
        } ?: return emptyList()

        val root = runCatching { org.json.JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val source = root.optJSONObject("source") ?: return emptyList()
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
        // playlist inline (variant URIs are absolute https URLs) — the signed url has
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

        // Per-quality MP4s (source.qualities[]) — direct VIDEO links.
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

        if (out.isNotEmpty()) {
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
        }
        return out
    }

    /** Follow iframes to the deepest player page. */
    private suspend fun unwrapPages(html: String, baseUrl: String, timeoutSec: Int): String {
        var curHtml = html; var curUrl = baseUrl
        repeat(MAX_UNWRAP) {
            val iframe = Jsoup.parse(curHtml).selectFirst("iframe[src]") ?: return curHtml
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return curHtml
            val resolved = relUrl(curUrl, src)
            if (resolved == curUrl) return curHtml
            curUrl = resolved
            val next = withTimeoutOrNull((timeoutSec - 2).coerceAtLeast(3) * 1000L) {
                runCatching { app.get(curUrl, timeout = (timeoutSec - 2).coerceAtLeast(3).toLong(), headers = okHeaders(curUrl)).text }.getOrNull()
            } ?: return curHtml
            if (next.isBlank()) return curHtml
            curHtml = next
        }
        return curHtml
    }

    /** Harvest bare m3u8/mp4/webm URLs from raw page text. */
    private fun harvestUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/").replace("\\\"", "\"")
        return STREAM_REGEX.flatMap { r -> r.findAll(n).map { it.groupValues[0].trim('"', '\'') }.filter { it.startsWith("http") } }.distinct()
    }

    /** Extract from JS config: file:"..." / sources:[{file:"..."}] / url:"..." */
    private fun harvestJsUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val n = text.replace("\\/", "/")
        val patterns = listOf(
            Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:source|src)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
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
}




