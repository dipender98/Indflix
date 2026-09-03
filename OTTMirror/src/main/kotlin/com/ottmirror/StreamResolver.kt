package com.ottmirror

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.util.Collections

/**
 * The federated resolution engine.
 *
 * Given a TMDB/IMDB id + type, it fans out to healthy servers in the farm,
 * unwraps embed/iframe pages down to real stream URLs, measures throughput,
 * and emits links fastest-first. Multi-audio masters are emitted as an
 * adaptive link first (so ExoPlayer's audio-track menu shows all dubs),
 * followed by per-quality split links.
 */
object StreamResolver {

    /** Max concurrent server probes at once. */
    private const val MAX_CONCURRENT = 5
    /** How many servers to try per resolve. */
    private const val MAX_SERVERS = 12
    /** Max iframe unwrap depth. */
    private const val MAX_UNWRAP_LEVELS = 4

    /** A raw stream candidate pulled from a server. */
    data class RawStream(
        val serverId: String,
        val serverName: String,
        val url: String,
        val isM3u8: Boolean,
        val referer: String? = null,
        val qualityHint: Int = 0,
        val measuredKbps: Long? = null, // fresh throughput probe from this resolve
        val subtitles: List<Pair<String, String>> = emptyList(), // (lang, url)
    )

    /** The resolved result for one title/episode. */
    data class ResolvedLinks(
        val links: List<ExtractorLink>,
        val subs: List<SubtitleFile>,
    )

    /**
     * Resolve all stream candidates for a movie/episode across the farm.
     * Returns raw streams ordered by measured speed (fastest first).
     */
    suspend fun resolve(
        tmdbId: Int,
        type: String,          // "movie" | "series"
        season: Int = -1,
        episode: Int = -1,
    ): List<RawStream> {
        if (tmdbId <= 0) return emptyList()

        // Pick servers: prioritize healthy ones; include a few "trial" (untested) ones.
        val servers = ServerFarm.allServers
            .filter { HealthMonitor.isHealthy(it.id) }
            .sortedByDescending { HealthMonitor.speedScore(it.id) }
            .take(MAX_SERVERS)

        if (servers.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_CONCURRENT)
        val resolved = coroutineScope {
            servers.map { spec ->
                async {
                    semaphore.acquire()
                    try {
                        withTimeoutOrNull(spec.timeoutSec * 1000L) {
                            runCatching { resolveOne(spec, tmdbId, type, season, episode) }
                                .getOrNull()
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull().flatten()
        }

        // Fastest first: fresh per-stream measurement wins, EMA breaks ties.
        return resolved.sortedWith(
            compareByDescending<RawStream> { it.measuredKbps ?: 0L }
                .thenByDescending { HealthMonitor.speedScore(it.serverId) }
                .thenBy { if (it.isM3u8) 0 else 1 }
        )
    }

    /**
     * Emit resolved links to the CloudStream callbacks, fastest-first.
     * Multi-audio masters are emitted once as an adaptive link, then per-quality
     * splits via [M3u8Helper.generateM3u8].
     */
    suspend fun emit(
        streams: List<RawStream>,
        onLink: (ExtractorLink) -> Unit,
        onSubtitle: (SubtitleFile) -> Unit,
    ) {
        if (streams.isEmpty()) return
        val emitted = Collections.synchronizedSet(HashSet<String>())

        streams.forEach { raw ->
            if (raw.url.isBlank()) return@forEach // subtitle-only carrier
            if (!emitted.add(raw.url)) return@forEach

            // Subtitles from the server payload (manifest-level handled below).
            raw.subtitles.forEach { (lang, subUrl) ->
                onSubtitle(SubtitleFile(lang, subUrl))
            }

            val referer = raw.referer
            val quality = raw.qualityHint

            if (raw.isM3u8) {
                // Try to read the master playlist to detect multi-audio and variants.
                val masterText = withTimeoutOrNull(5000L) {
                    runCatching {
                        app.get(raw.url, timeout = 5, headers = if (referer != null) mapOf("Referer" to referer) else mapOf()).text
                    }.getOrNull()
                }
                val master = ManifestKit.parseMaster(masterText, raw.url)

                if (master?.isMultiAudio == true) {
                    // Adaptive master first: the player exposes the audio-track menu.
                    onLink(
                        ExtractorLink(
                            source = raw.serverName,
                            name = "${raw.serverName} Auto • multi-audio",
                            url = raw.url,
                            referer = referer ?: "",
                            quality = ManifestKit.bestHeight(master.variants).takeIf { it > 0 } ?: quality,
                            headers = if (referer != null) mapOf("Referer" to referer) else emptyMap(),
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    // Then per-quality splits.
                    M3u8Helper.generateM3u8(
                        source = raw.serverName,
                        streamUrl = raw.url,
                        referer = referer ?: "",
                        quality = quality.takeIf { it > 0 },
                        headers = if (referer != null) mapOf("Referer" to referer) else emptyMap(),
                    ).forEach { onLink(it) }
                    // Manifest-level subtitles.
                    master.subtitles.forEach { rend ->
                        rend.uri?.let { subUrl ->
                            onSubtitle(
                                SubtitleFile(
                                    rend.language ?: rend.name,
                                    ManifestKit.resolveUrl(raw.url, subUrl),
                                )
                            )
                        }
                    }
                } else {
                    // Single-audio: per-quality splits only.
                    M3u8Helper.generateM3u8(
                        source = raw.serverName,
                        streamUrl = raw.url,
                        referer = referer ?: "",
                        quality = quality.takeIf { it > 0 },
                        headers = if (referer != null) mapOf("Referer" to referer) else emptyMap(),
                    ).forEach { onLink(it) }
                    master?.subtitles?.forEach { rend ->
                        rend.uri?.let { subUrl ->
                            onSubtitle(
                                SubtitleFile(
                                    rend.language ?: rend.name,
                                    ManifestKit.resolveUrl(raw.url, subUrl),
                                )
                            )
                        }
                    }
                }
            } else {
                // Direct MP4 etc.
                onLink(
                    ExtractorLink(
                        source = raw.serverName,
                        name = "${raw.serverName} ${ManifestKit.qualityLabel(quality)}".trim(),
                        url = raw.url,
                        referer = referer ?: "",
                        quality = quality,
                        headers = if (referer != null) mapOf("Referer" to referer) else emptyMap(),
                        type = ExtractorLinkType.VIDEO,
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Resolve one server spec down to raw streams. */
    private suspend fun resolveOne(
        spec: ServerSpec,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
    ): List<RawStream> {
        val start = System.currentTimeMillis()
        val embedUrl = if (type == "movie") {
            ServerFarm.buildMovieUrl(spec, tmdbId)
        } else {
            ServerFarm.buildTvUrl(spec, tmdbId, season, episode)
        }

        val referer = embedUrl.substringBefore("?")
        val rawText = withTimeoutOrNull((spec.timeoutSec - 2).coerceAtLeast(3) * 1000L) {
            runCatching {
                app.get(
                    embedUrl,
                    timeout = (spec.timeoutSec - 2).coerceAtLeast(3).toLong(),
                    headers = HttpKit2.commonHeaders + mapOf("Referer" to referer),
                ).text
            }.getOrNull()
        }

        if (rawText.isNullOrBlank()) {
            HealthMonitor.recordFailure(spec.id)
            return emptyList()
        }

        // Unwrap iframes to the deepest player page.
        val unwrapped = unwrap(rawText, embedUrl, spec.timeoutSec)
        val streamUrls = extractStreamUrls(unwrapped)

        if (streamUrls.isEmpty()) {
            // Subtitle-only result: propagate subtitles but no stream.
            val subs = extractSubtitles(unwrapped)
            if (subs.isNotEmpty()) {
                HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
                return listOf(
                    RawStream(
                        serverId = spec.id,
                        serverName = spec.name,
                        url = "",
                        isM3u8 = false,
                        referer = referer,
                        subtitles = subs,
                    )
                )
            }
            HealthMonitor.recordFailure(spec.id)
            return emptyList()
        }

        val subs = extractSubtitles(unwrapped)
        val result = streamUrls.mapNotNull { (url, isM3u8) ->
            val probed = HttpKit2.probeSpeed(url, referer)
            val quality = if (isM3u8) 0 else getQualityFromName(url)
            RawStream(
                serverId = spec.id,
                serverName = spec.name,
                url = url,
                isM3u8 = isM3u8,
                referer = referer,
                qualityHint = quality,
                measuredKbps = probed,
                subtitles = subs,
            )
        }

        // Record health: success if we found streams, failure otherwise.
        if (result.isNotEmpty()) {
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
        } else {
            HealthMonitor.recordFailure(spec.id)
        }
        return result
    }

    /** Follow iframe chains until the deepest player page. Returns the final HTML. */
    private suspend fun unwrap(html: String, baseUrl: String, timeoutSec: Int): String {
        var currentHtml = html
        var currentUrl = baseUrl
        repeat(MAX_UNWRAP_LEVELS) {
            val iframe = Jsoup.parse(currentHtml).selectFirst("iframe[src]")
            val src = iframe?.attr("src")?.takeIf { it.isNotBlank() } ?: return currentHtml
            val resolved = HttpKit2.resolveUrl(currentUrl, src)
            if (resolved == currentUrl) return currentHtml
            currentUrl = resolved
            val next = withTimeoutOrNull((timeoutSec - 2).coerceAtLeast(3) * 1000L) {
                runCatching {
                    app.get(
                        currentUrl,
                        timeout = (timeoutSec - 2).coerceAtLeast(3).toLong(),
                        headers = HttpKit2.commonHeaders + mapOf("Referer" to currentUrl),
                    ).text
                }.getOrNull()
            } ?: return currentHtml
            if (next.isBlank()) return currentHtml
            currentHtml = next
        }
        return currentHtml
    }

    /** Extract direct stream URLs (.m3u8/.mp4) from a page's HTML/JS. */
    internal fun extractStreamUrls(text: String?): List<Pair<String, Boolean>> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Pair<String, Boolean>>()
        val seen = HashSet<String>()
        val patterns = listOf(
            Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl|source)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:source|src)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val u = m.groupValues[1].replace("\\/", "/").trim()
                if (u.isNotBlank() && u.startsWith("http") && seen.add(u)) {
                    out.add(u to u.contains(".m3u8", ignoreCase = true))
                }
            }
        }
        // Also check <video><source src="..."> and <source src="...">.
        Jsoup.parse(text).select("video source[src], source[src]").forEach { el ->
            val u = el.attr("src").trim()
            if (u.isNotBlank() && u.startsWith("http") && seen.add(u)) {
                out.add(u to u.contains(".m3u8", ignoreCase = true))
            }
        }
        return out
    }

    /** Extract subtitle tracks from a page's HTML/JS (JWPlayer-style tracks array). */
    internal fun extractSubtitles(text: String?): List<Pair<String, String>> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        // tracks:[{file:"...",label:"...",kind:"captions"}]
        val trackRegex = Regex("""\{[^{}]*?"file"\s*:\s*"([^"]+)"[^{}]*?"label"\s*:\s*"([^"]+)"[^{}]*?\}""")
        trackRegex.findAll(text).forEach { m ->
            val file = m.groupValues[1].replace("\\/", "/")
            val label = m.groupValues[2]
            if (file.isNotBlank() && label.isNotBlank() && seen.add(file)) {
                out.add(label to file)
            }
        }
        return out
    }
}
