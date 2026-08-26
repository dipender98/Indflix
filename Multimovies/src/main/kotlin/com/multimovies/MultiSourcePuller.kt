package com.multimovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped memory of per-source extraction speed. [MultiSourcePuller.pull]
 * records how long each source took to produce links (or fail/timeout), and the
 * final link sort uses the measured average — so "fast" is decided by the user's
 * actual network, with the curated SOURCE_PRIORITY list only as the cold-start
 * fallback.
 *
 * In-memory only (this CloudStream build exposes no persistent settings API);
 * resets on app restart.
 */
internal object SourceSpeedTracker {
    /** Penalty (ms) added per failed attempt, so sources that fail often are
     *  demoted below consistently-fast ones. */
    private const val FAILURE_PENALTY_MS = 30_000L

    private data class Stats(var successes: Int = 0, var totalMs: Long = 0L, var failures: Int = 0) {
        fun avgMs(): Double = if (successes == 0) Double.MAX_VALUE
        else (totalMs + failures * FAILURE_PENALTY_MS).toDouble() / successes
    }

    private val map = java.util.concurrent.ConcurrentHashMap<String, Stats>()

    fun record(name: String, durationMs: Long, success: Boolean) {
        map.compute(name) { _, s ->
            val stats = s ?: Stats()
            if (success) {
                stats.successes++
                stats.totalMs += durationMs
            } else {
                stats.failures++
            }
            stats
        }
    }

    /** Learned average extraction latency for [name], or null when never measured.
     *  Measured-but-never-succeeded sources return [Double.MAX_VALUE] (slowest). */
    fun averageLatency(name: String): Double? = map[name]?.avgMs()
}

/**
 * MultiSourcePuller - the source-priority / parallel-pull / timeout engine.
 *
 * Given a list of (serverName, url) pairs it:
 *   1. Orders them by measured speed ([SourceSpeedTracker]) with the static
 *      [MultimoviesProvider.SOURCE_PRIORITY] as fallback.
 *   2. Launches ALL of them concurrently (parallel pulling).
 *   3. Wraps each individual source in a [timeoutMs] timeout (default 30s).
 *      A single slow/dead source can never block the others.
 *   4. Returns the successfully extracted links, sorted by measured speed.
 *
 * Per source it runs a unified extraction pipeline:
 *     a. dedicated host extractor (screenscape.me crypto)
 *     b. CloudStream's extractor registry (loadExtractor)
 *     c. a generic m3u8/mp4 sniff of the player page
 *
 * This is intentionally decoupled from the provider so the strategy can be
 * tuned (timeouts, priority weights, concurrency limits) in one place.
 */
object MultiSourcePuller {

    data class Source(
        val name: String,
        val url: String,
        val referer: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val latencyMs: Long = Long.MAX_VALUE,
    )

    /** Max iframe levels to unwrap before treating a page as the player. */
    private const val MAX_UNWRAP_LEVELS = 4

    /** Regexes for the generic embed sniffer: stream URLs to harvest directly. */
    internal val STREAM_URL_REGEXES = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.webm[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mkv[^\s"'<>\\]*"""),
    )

    /**
     * Pure helper: pull the first stream URL (m3u8/mp4/webm/mkv) from raw page text.
     * Testable without network access.
     */
    internal fun extractStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        // Normalize JSON-escaped slashes/backslashes so the plain `https?://` regex
        // can still match URLs embedded in JSON (\/ -> /).
        val normalized = text.replace("\\/", "/").replace("\\\"", "\"")
        for (r in STREAM_URL_REGEXES) {
            r.findAll(normalized).firstOrNull()?.groupValues?.get(0)?.let { raw ->
                val cleaned = raw.trim('"', '\'')
                if (cleaned.isNotBlank()) return cleaned
            }
        }
        return null
    }

    /** Detect a modiplay-style proxy player endpoint in page text, e.g.
     *  `\/proxy.php?serve_m3u8=1&ref=...&url=<url-encoded m3u8>&ebd=...`.
     *  The proxy endpoint serves the playlist directly (Content-Type mpegurl),
     *  so the returned URL is playable as-is. Returns null when absent. */
    internal fun buildProxyStreamUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\/", "/")
        val m = Regex("""(?:https?:)?//[^"'\s<>]*proxy\.php\?[^"'\s<>]*serve_m3u8=1[^"'\s<>]*""")
            .find(normalized)
            ?: Regex("""/(?:[^"'\s<>]*proxy\.php\?[^"'\s<>]*serve_m3u8=1[^"'\s<>]*)""")
                .find(normalized)
        val raw = m?.value?.trim('"', '\'', '\\') ?: return null
        return resolveRelative(baseUrl, raw).takeIf { it.startsWith("http") }
    }

    /** Pull a stream URL from a `<video src>` / `<source src>` element. */
    internal fun extractVideoSourceUrl(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        val src = Jsoup.parse(text).selectFirst("video[src], video source[src], source[src]")
            ?.attr("src")?.trim() ?: return null
        if (src.isBlank()) return null
        return resolveRelative(baseUrl, src).takeIf { it.startsWith("http") }
    }

    /** Pull a stream URL from common JS player config shapes embedded in the page:
     *  `sources:[{file:"...m3u8"}]`, `file:"...m3u8"`, `url:"...m3u8"`,
     *  `hlsUrl:"..."`, `streamUrl:"..."`. Returns null when absent. */
    internal fun extractFromJsConfig(text: String): String? {
        if (text.isBlank()) return null
        val normalized = text.replace("\\/", "/")
        val patterns = listOf(
            Regex("""["']?(?:file|url|src|hlsUrl|hls_source|streamUrl|stream_url|playUrl)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*[:=]\s*\[\s*\{\s*["']?file["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:source|src)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            p.findAll(normalized).firstOrNull()?.groupValues?.get(1)?.let {
                val v = it.trim()
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /** Decode a URL-encoded m3u8 URL found inside a query string (e.g. `url=%2F..%2Fmaster.m3u8...`)
     *  and return it as a plain https URL. */
    internal fun decodeEncodedStreamUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = Regex("""url=([^"'&\s]+?%2F[^"'&\s]*master\.m3u8[^"'&\s]*)""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val encoded = m.groupValues[1]
        return runCatching {
            java.net.URLDecoder.decode(encoded, "UTF-8")
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    )

    /** Hosts that back the Cineverse modiplay/vibuxer serve_m3u8=1 proxy. The
     *  proxy returns 403 to bare requests; it requires a same-site Referer and
     *  Origin (it was handed out by the Multimovies dooplayer player) or it
     *  won't sign the playlist. Used by [headersFor] so every Cineverse call
     *  - admin-ajax wrap, unwrap, and the final proxy fetch - carries the
     *  required pair. */
    private val cineverseCdnHosts = setOf(
        "vibuxer.com",
        "www.vibuxer.com",
        "modiplay.com",
        "www.modiplay.com",
    )

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").lowercase()

    /** True when the URL belongs to the Cineverse modiplay/vibuxer CDN. */
    internal fun isCineverseHost(url: String): Boolean =
        cineverseCdnHosts.contains(hostOf(url)) ||
            hostOf(url).let { h -> cineverseCdnHosts.any { h == it || h.endsWith(".$it") } }

    /** Deterministic identity for an emitted link: `<Server>[ Hindi]`.
     *  Every ExtractorLink must carry this exact string in BOTH `source` and
     *  `name`: CloudStream saves player priorities keyed on an exact match of
     *  `source` while the server list displays `name`, so any drift (CDN
     *  suffixes, quality suffixes, per-load counters) breaks the user's
     *  ranking. Deliberately free of runtime-derived parts — extractor/extension
     *  availability and CDN hosts must never influence it. */
    internal fun linkLabel(base: String?, hindi: Boolean): String =
        (base?.trim()?.takeIf { it.isNotEmpty() } ?: "Multimovies") +
            (if (hindi) " Hindi" else "")

    /** Build the header set for a request to [url]. The Cineverse CDN requires
     *  the page that linked to it as Referer/Origin; for other hosts the
     *  caller-supplied headers and shared UA are used as-is. Pure / cheap. */
    internal fun headersFor(
        url: String,
        referer: String?,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(sharedHeaders.size + extra.size + 2)
        out.putAll(sharedHeaders)
        out.putAll(extra)
        if (!referer.isNullOrBlank()) out["Referer"] = referer
        if (isCineverseHost(url)) {
            // vibuxer.com / proxy.php signs only when it sees the originating
            // site as Referer and a matching Origin. The plugin's embed URL
            // comes from the dooplayer player on multimovies.motorcycles, so
            // that's the referer we advertise.
            val ref = referer?.takeIf { it.isNotBlank() } ?: "https://multimovies.motorcycles/"
            out["Referer"] = ref
            val origin = ref.substringBefore("/seasons/")
                .substringBefore("/movies/")
                .substringBefore("/tvshows/")
                .takeIf { it.startsWith("http") } ?: "https://multimovies.motorcycles"
            out["Origin"] = origin
        }
        return out
    }

    /** Resolve a possibly-relative [path] against [baseUrl], producing an absolute
     *  https URL. Handles protocol-relative (//), absolute, and root-relative. */
    internal fun resolveRelative(baseUrl: String, path: String): String {
        if (path.startsWith("//")) return "https:$path"
        if (path.startsWith("http", ignoreCase = true)) return path
        val schemeHost = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /**
     * Recursively follow iframes until the deepest player URL is found. A wrapper
     * page (e.g. an aggregator/redirector) is fetched and its first iframe src
     * followed, up to [MAX_UNWRAP_LEVELS]. Direct stream URLs (.m3u8/.mp4) and
     * proxy relay URLs (serve_m3u8=1) short-circuit: the page that exposes them
     * IS the stream, so [MultiSourcePuller.pull] can emit it directly without
     * re-fetching.
     */
    suspend fun unwrapEmbed(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = sharedHeaders,
    ): String {
        var current = url
        repeat(MAX_UNWRAP_LEVELS) {
            // Terminal: a URL that IS a stream — direct m3u8/mp4 file, or a
            // modiplay/proxy relay (serve_m3u8=1) that serves the playlist
            // directly. Checked on the whole URL string (not just the host)
            // because these markers live in the path/query; fetching them as
            // HTML would only re-download a playlist and risk misparsing it.
            if (current.contains(".m3u8", ignoreCase = true) ||
                current.contains(".mp4", ignoreCase = true) ||
                current.contains("serve_m3u8=", ignoreCase = true)
            ) return current
            val text = runCatching {
                app.get(current, timeout = 5, headers = headersFor(current, referer, headers)).text
            }.getOrNull() ?: return current
            // Short-circuit: a page exposing a proxy/stream URL is the player itself.
            buildProxyStreamUrl(text, current)?.let { return it }
            extractStreamUrl(text)?.let { return it }
            extractVideoSourceUrl(text, current)?.let { return it }
            val next = Jsoup.parse(text).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: return current
            val resolved = resolveRelative(current, next)
            if (resolved == current) return current
            current = resolved
        }
        return current
    }

    /** True when a link name/label indicates a Hindi audio track. */
    internal fun isHindi(link: ExtractorLink): Boolean {
        val hay = buildString {
            link.name?.let { append(it) }
            append(' ')
            link.source?.let { append(it) }
        }.lowercase()
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    /** True when a source URL, name, or stream URL contains a Hindi/streamhg hint.
     *  Used by [sniff] to name the extracted link so the Hindi-preference sort
     *  can prefer it. Checks the proxy platform (streamhg = Hindi), explicit
     *  language params (lan=hindi), and any Hindi text in the source name. */
    internal fun isHindiHint(sourceName: String, sourceUrl: String, streamUrl: String?): Boolean {
        val hay = buildString {
            append(sourceName.lowercase())
            append('|')
            append(sourceUrl.lowercase())
            if (streamUrl != null) { append('|'); append(streamUrl.lowercase()) }
        }
        return hay.contains("streamhg") || hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी") || hay.contains("lan=hindi") || hay.contains("modiplay") || hay.contains("serve_m3u8")
    }

    /**
     * @param sources   raw server list (unsorted is fine, sorting happens here)
     * @param timeoutMs per-source hard timeout in ms (project default: 15_000)
     * @param priorityOf maps a server name to a sort index (lower = better)
     * @param preferHindi when true, Hindi-audio links win latency/priority ties
     * @param onSubtitle called for each subtitle found
     * @param onLink optional: called immediately for every extracted link (streaming —
     *        lets the player start the fastest source instead of waiting for all sources)
     * @return list of extractor links, ordered by measured speed then priority then latency then Hindi
     */
    suspend fun pull(
        sources: List<Source>,
        timeoutMs: Long = MultimoviesProvider.SOURCE_TIMEOUT_MS,
        priorityOf: (String) -> Int,
        preferHindi: Boolean = true,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit = {},
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        // Launch higher-priority sources first so the reliable/fast ones (Cineverse)
        // start resolving and emit their link before slower fallbacks.
        val orderedSources = sources.sortedBy { priorityOf(it.name) }
        coroutineScope {
            orderedSources.map { src ->
                async {
                    val startedMs = System.currentTimeMillis()
                    val result = withTimeoutOrNull(timeoutMs) {
                        runCatching {
                            val found = extractSource(src, onSubtitle = { subs.add(it) })
                            found.map { l ->
                                val link = toExtractorLink(src, l)
                                links.add(link)
                                onLink(link)
                                link
                            }
                        }
                    }
                    val made = result?.getOrNull().orEmpty()
                    SourceSpeedTracker.record(
                        src.name,
                        System.currentTimeMillis() - startedMs,
                        success = made.isNotEmpty(),
                    )
                }
            }.awaitAll()
        }

        subs.forEach { onSubtitle(it) }
        sortLinks(links, sources, priorityOf, preferHindi)
    }

    /** Wrap a raw extractor link with the source's headers/referer defaults.
     *  Identity is NOT touched here: every extractSource branch already emits
     *  final `source == name == linkLabel(...)` labels. */
    private fun toExtractorLink(src: Source, l: ExtractorLink): ExtractorLink =
        ExtractorLink(
            source = l.source,
            name = l.name,
            url = l.url,
            referer = l.referer ?: src.url,
            quality = l.quality,
            headers = l.headers ?: src.headers,
            extractorData = null,
            type = l.type,
            audioTracks = l.audioTracks ?: emptyList(),
        )

    /** Normalize an [ExtractorLink.source] / [Source.name] into a stable key for
     *  speed tracking and priority lookup: strips any duplicate counter
     *  ("Name-2"), trailing language annotation (" Hindi") and the trailing
     *  parenthesized server label, so "Nxsha (Nitro) Hindi-2",
     *  "Cineverse-2" and "Cineverse (Vibuxer)" all reduce to their
     *  SOURCE_PRIORITY name. */
    internal fun sourceKey(source: String?): String {
        if (source == null) return ""
        return source
            .replace(Regex("""-\d+$"""), "")
            .replace(Regex("""\s+Hindi$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\([^)]*\)$"""), "")
            .trim()
    }

    /**
     * Order links for the player. Primary key is the curated static [priorityOf]
     * ranking (so Cineverse / the reliable fast sources always come first);
     * measured per-source speed and per-call embed latency only break ties within
     * the same priority, then the Hindi preference, then adaptive HLS over fixed
     * progressive files — an m3u8 manifest lets the player start quickly at a
     * lower rendition and ramp quality up automatically.
     */
    internal fun sortLinks(
        links: List<ExtractorLink>,
        sources: List<Source>,
        priorityOf: (String) -> Int,
        preferHindi: Boolean = true,
    ): List<ExtractorLink> {
        val latencyByName = sources.associate { it.name to it.latencyMs }
        val comparator = compareBy<ExtractorLink>(
            { priorityOf(sourceKey(it.source)) },
            { SourceSpeedTracker.averageLatency(sourceKey(it.source)) ?: Double.MAX_VALUE },
            { latencyByName[sourceKey(it.source)] ?: Long.MAX_VALUE },
        ).thenByDescending { if (preferHindi) isHindi(it) else false }
            .thenByDescending { it.type == ExtractorLinkType.M3U8 }
        return links.sortedWith(comparator)
    }

    /** True when [url] points at a YouTube host (trailer embeds). */
    internal fun isYouTubeHost(url: String): Boolean {
        val host = hostOf(url)
        return host.contains("youtube.com") || host.contains("youtu.be") ||
            host.contains("youtube-nocookie")
    }

    /** Unified per-source extraction: dedicated host extractor, then registry, then sniff. */
    private suspend fun extractSource(
        src: Source,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        // Trailers/YouTube embeds are not streams — never surface them as sources.
        if (isYouTubeHost(src.url)) return emptyList()
        // screenscape.me: JS-rendered player, but its API is deterministic client-side
        // crypto (HMAC-signed routes + CryptoJS-AES responses). Route to the dedicated
        // extractor which reproduces that flow (no browser needed).
        if (hostOf(src.url).contains("screenscape")) {
            val subs = mutableListOf<SubtitleFile>()
            val screenLinks = ScreenscapeExtractor.extract(src) { subs.add(SubtitleFile(it.lang, it.url)) }
            subs.forEach { onSubtitle(it) }
            return screenLinks.map { s ->
                val source = s.name
                val type = if (s.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val headers = buildMap {
                    put("Referer", s.headers["Referer"] ?: src.url)
                    putAll(src.headers)
                    putAll(s.headers)
                }
                ExtractorLink(
                    source = source,
                    name = s.name,
                    url = s.url,
                    referer = s.headers["Referer"] ?: src.url,
                    quality = getQualityFromName(s.quality.ifEmpty { s.url }),
                    headers = headers,
                    extractorData = null,
                    type = type,
                    audioTracks = emptyList(),
                )
            }
        }

        // Nxsha: the web player resolves servers/sources through same-origin
        // CryptoJS-AES envelopes (no stream URL in any HTML), so it needs the
        // dedicated extractor too. Ordered after screenscape so
        // nxsha.screenscape.me keeps hitting the screenscape extractor.
        if (hostOf(src.url).contains("nxsha")) {
            val subs = mutableListOf<SubtitleFile>()
            val nxLinks = NxshaExtractor.extract(src) { subs.add(SubtitleFile(it.lang, it.url)) }
            subs.forEach { onSubtitle(it) }
            return nxLinks.map { s ->
                val source = s.name
                val type = if (s.isM3u8 || s.url.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else ExtractorLinkType.VIDEO
                // Streams come back without headers; mirror browser behavior by
                // advertising the embed page as Referer unless told otherwise.
                val refererHeader = s.headers["Referer"] ?: src.referer ?: src.url
                val headers = buildMap {
                    putAll(src.headers)
                    putAll(s.headers)
                    if (!s.headers.containsKey("Referer")) put("Referer", refererHeader)
                }
                ExtractorLink(
                    source = source,
                    name = source,
                    url = s.url,
                    referer = refererHeader,
                    quality = getQualityFromName(s.quality.ifEmpty { s.url }),
                    headers = headers,
                    extractorData = null,
                    type = type,
                    audioTracks = emptyList(),
                )
            }
        }

        // If unwrapEmbed already surfaced a playable stream or proxy relay URL,
        // emit it directly — no extra page fetch needed.
        directStreamLink(src)?.let { return listOf(it) }

        // Stage a: CloudStream extractor registry (installed/built-in extractors).
        // Relabeled to the source's stable identity — registry links carry bare
        // extractor names that would miss SOURCE_PRIORITY and drift whenever
        // extensions are installed or removed.
        val found = mutableListOf<ExtractorLink>()
        val registryOk = runCatching {
            loadExtractor(
                url = src.url,
                referer = src.referer,
                subtitleCallback = onSubtitle,
                callback = { found.add(it) },
            )
        }.getOrDefault(false)
        if (registryOk && found.isNotEmpty()) {
            return found.map { l ->
                val label = linkLabel(
                    src.name,
                    isHindi(l) || isHindiHint(src.name, src.url, l.url),
                )
                ExtractorLink(
                    source = label,
                    name = label,
                    url = l.url,
                    referer = l.referer,
                    quality = l.quality,
                    headers = l.headers,
                    extractorData = null,
                    type = l.type,
                    audioTracks = l.audioTracks ?: emptyList(),
                )
            }
        }

        // Stage b: generic m3u8/mp4 sniff.
        return sniff(src)
    }

    /** When [src.url] is itself a playable stream (serve_m3u8 proxy relay, m3u8 or
     *  mp4), build the ExtractorLink right away. Returns null otherwise so the
     *  generic pipeline runs. */
    private fun directStreamLink(src: Source): ExtractorLink? {
        val u = src.url
        val isStream = u.contains("serve_m3u8=1", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".webm", ignoreCase = true)
        if (!isStream) return null
        val label = linkLabel(src.name, isHindiHint(src.name, src.url, u))
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        // Use headersFor so the Cineverse serve_m3u8 proxy request, which the
        // player will replay against the emitted link, carries the same
        // Referer/Origin pair the embed originally needed.
        val headers = headersFor(u, src.referer, src.headers)
        return ExtractorLink(
            source = label,
            name = label,
            url = u,
            referer = src.referer ?: u,
            quality = getQualityFromName(u),
            headers = headers,
            extractorData = null,
            type = type,
            audioTracks = emptyList(),
        )
    }

    /** Generic fallback: fetch the player page and harvest the first stream URL
     *  using a multi-strategy approach (direct regex, proxy pattern, video/source
     *  tags, JS config objects, URL-encoded m3u8). */
    private suspend fun sniff(src: Source): List<ExtractorLink> {
        val headers = headersFor(src.url, src.referer, src.headers)
        val text = runCatching {
            app.get(src.url, timeout = 5, headers = headers).text
        }.getOrNull() ?: return emptyList()

        val stream = buildProxyStreamUrl(text, src.url)
            ?: extractStreamUrl(text)
            ?: extractVideoSourceUrl(text, src.url)
            ?: extractFromJsConfig(text)
            ?: decodeEncodedStreamUrl(text)
            ?: return emptyList()

        val label = linkLabel(src.name, isHindiHint(src.name, src.url, stream))
        val linkType = if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        return listOf(
            ExtractorLink(
                source = label,
                name = label,
                url = stream,
                referer = src.url,
                quality = getQualityFromName(stream),
                headers = headers,
                extractorData = null,
                type = linkType,
                audioTracks = emptyList(),
            )
        )
    }
}
