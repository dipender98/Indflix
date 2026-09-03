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

/**
 * Federated resolution engine.
 *
 * 1. Fans out to healthy embed servers in parallel.
 * 2. Uses the same multi-strategy pipeline as Multimovies:
 *    fetch → unwrap iframes → bare-URL regex harvest → loadExtractor registry
 *    → JS config → <video> source → subtitles.
 * 3. Dual-audio gating: only emits servers whose master playlist carries
 *    at least Hindi+English audio tracks. When none do, falls back to
 *    the best available single-audio source.
 */
object StreamResolver {

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
        val hasHindiEnglish: Boolean = false, // true if HLS master has Hi+En audio
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

        // Dual-audio gating: prefer servers with Hi+En, fallback to rest
        val dualAudio = resolved.filter { it.hasHindiEnglish }
        return if (dualAudio.isNotEmpty()) dualAudio else resolved
    }

    /**
     * Emit links fastest-first. Dual-audio masters get the adaptive link first.
     */
    suspend fun emit(streams: List<RawStream>, onLink: (ExtractorLink) -> Unit, onSubtitle: (SubtitleFile) -> Unit) {
        if (streams.isEmpty()) return
        val emitted = java.util.Collections.synchronizedSet(HashSet<String>())

        // Sort: dual-audio first, then by speed
        val sorted = streams.sortedWith(
            compareByDescending<RawStream> { it.hasHindiEnglish }
                .thenByDescending { it.measuredKbps ?: 0L }
        )

        sorted.forEach { raw ->
            if (raw.url.isBlank()) return@forEach
            if (!emitted.add(raw.url)) return@forEach

            raw.subtitles.forEach { (lang, subUrl) -> onSubtitle(SubtitleFile(lang, subUrl)) }

            if (raw.isM3u8) {
                val masterText = withTimeoutOrNull(4000L) {
                    runCatching {
                        app.get(raw.url, timeout = 4, headers = mapOf("Referer" to (raw.referer ?: ""))).text
                    }.getOrNull()
                }
                val master = ManifestKit.parseMaster(masterText, raw.url)
                val label = buildString {
                    append(raw.serverName)
                    if (raw.hasHindiEnglish) append(" • Hi+En")
                }

                if (master?.isMultiAudio == true) {
                    onLink(ExtractorLink(
                        source = raw.serverName, name = "$label Auto",
                        url = raw.url, referer = raw.referer ?: "",
                        quality = ManifestKit.bestHeight(master.variants).takeIf { it > 0 } ?: raw.qualityHint,
                        headers = mapOf("Referer" to (raw.referer ?: "")), type = ExtractorLinkType.M3U8,
                    ))
                    M3u8Helper.generateM3u8(raw.serverName, raw.url, raw.referer ?: "",
                        quality = raw.qualityHint.takeIf { it > 0 },
                        headers = mapOf("Referer" to (raw.referer ?: "")),
                    ).forEach { onLink(it) }
                    master.subtitles.forEach { r ->
                        r.uri?.let { onSubtitle(SubtitleFile(r.language ?: r.name, ManifestKit.resolveUrl(raw.url, it))) }
                    }
                } else {
                    M3u8Helper.generateM3u8(raw.serverName, raw.url, raw.referer ?: "",
                        quality = raw.qualityHint.takeIf { it > 0 },
                        headers = mapOf("Referer" to (raw.referer ?: "")),
                    ).forEach { onLink(it) }
                    master?.subtitles?.forEach { r ->
                        r.uri?.let { onSubtitle(SubtitleFile(r.language ?: r.name, ManifestKit.resolveUrl(raw.url, it))) }
                    }
                }
            } else {
                onLink(ExtractorLink(
                    source = raw.serverName, name = "${raw.serverName} ${ManifestKit.qualityLabel(raw.qualityHint)}".trim(),
                    url = raw.url, referer = raw.referer ?: "", quality = raw.qualityHint,
                    headers = mapOf("Referer" to (raw.referer ?: "")), type = ExtractorLinkType.VIDEO,
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
                val probed = HttpKit2.probeSpeed(url, referer)
                val hasHiEn = probeDualAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs, hasHiEn)
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
                val probed = HttpKit2.probeSpeed(link.url, link.referer)
                val hasHiEn = link.url.contains(".m3u8", ignoreCase = true) && probeDualAudio(link.url, link.referer)
                RawStream(spec.id, spec.name, link.url, link.type == ExtractorLinkType.M3U8, link.referer, link.quality, probed,
                    regSubs.map { it.lang to it.url }, hasHiEn)
            }
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return result
        }

        // 5. JS config: file:"...", sources:[{file:"..."}]
        val jsUrls = harvestJsUrls(unwrapped)
        if (jsUrls.isNotEmpty()) {
            val subs = grabSubtitles(unwrapped)
            val result = jsUrls.map { url ->
                val probed = HttpKit2.probeSpeed(url, referer)
                val hasHiEn = probeDualAudio(url, referer)
                RawStream(spec.id, spec.name, url, url.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs, hasHiEn)
            }
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return result
        }

        // 6. <video src> / <source src> HTML elements
        val videoSrc = harvestVideoSource(unwrapped, embedUrl)
        if (videoSrc != null) {
            val subs = grabSubtitles(unwrapped)
            val probed = HttpKit2.probeSpeed(videoSrc, referer)
            val hasHiEn = probeDualAudio(videoSrc, referer)
            HealthMonitor.recordSuccess(spec.id, System.currentTimeMillis() - start, null)
            return listOf(RawStream(spec.id, spec.name, videoSrc, videoSrc.contains(".m3u8", ignoreCase = true), referer, 0, probed, subs, hasHiEn))
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

    /** Probe a master playlist for Hindi+English audio. Returns false if not HLS or unreadable. */
    private suspend fun probeDualAudio(url: String, referer: String?): Boolean {
        if (!url.contains(".m3u8", ignoreCase = true)) return false
        val text = withTimeoutOrNull(3000L) {
            runCatching { app.get(url, timeout = 3, headers = mapOf("Referer" to (referer ?: ""))).text }.getOrNull()
        } ?: return false
        val master = ManifestKit.parseMaster(text, url) ?: return false
        return ManifestKit.hasHindiEnglishAudio(master)
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

        // Adaptive master (source.url) — probe for Hi+En dual audio.
        val masterUrl = source.optString("url").takeIf { it.isNotBlank() }
        if (masterUrl != null) {
            val probed = HttpKit2.probeSpeed(masterUrl, referer)
            val isHls = masterUrl.contains(".m3u8", ignoreCase = true)
            val hasHiEn = if (isHls) probeDualAudio(masterUrl, referer) else false
            out.add(RawStream(
                serverId = spec.id, serverName = spec.name,
                url = masterUrl, isM3u8 = isHls, referer = referer,
                qualityHint = 0, measuredKbps = probed, subtitles = subs,
                hasHindiEnglish = hasHiEn,
            ))
        }

        // Per-quality MP4s (source.qualities[]) — direct VIDEO links.
        source.optJSONArray("qualities")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val q = arr.optJSONObject(i) ?: return@mapNotNull null
                val qUrl = q.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qLabel = q.optString("quality").takeIf { it.isNotBlank() }
                val height = qLabel?.let { Regex("(\\d{3,4})").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                val probed = HttpKit2.probeSpeed(qUrl, referer)
                RawStream(
                    serverId = spec.id, serverName = spec.name,
                    url = qUrl, isM3u8 = false, referer = referer,
                    qualityHint = height, measuredKbps = probed, subtitles = subs,
                    hasHindiEnglish = false,
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