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

    fun reset() = map.clear()
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
 *     a. CloudStream's extractor registry (loadExtractor)
 *     b. inline host extractors (vixsrc masterPlaylist, vidlink enc+b/ flow)
 *     c. a generic m3u8/mp4 sniff of the player page
 *
 * This is intentionally decoupled from the provider so the strategy can be
 * tuned (timeouts, priority weights, concurrency limits) in one place.
 */
object MultiSourcePuller {

    /** Which extraction path a source takes. */
    enum class ExtractionType { GENERIC, MASTER_PLAYLIST, VIDLINK }

    data class Source(
        val name: String,
        val url: String,
        val referer: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val extraction: ExtractionType = ExtractionType.GENERIC,
        val tmdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val latencyMs: Long = Long.MAX_VALUE,
    )

    const val INDICATOR = " (Multimovies)"

    /** Max iframe levels to unwrap before treating a page as the player. */
    private const val MAX_UNWRAP_LEVELS = 4

    /** Regexes for the generic embed sniffer: stream URLs to harvest directly. */
    internal val STREAM_URL_REGEXES = listOf(
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.m3u8"""),
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

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").lowercase()

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
            val host = hostOf(current)
            if (host.endsWith(".m3u8") || host.contains(".m3u8", ignoreCase = true) ||
                host.contains(".mp4", ignoreCase = true)
            ) return current
            val text = runCatching {
                app.get(current, timeout = 5, headers = buildMap {
                    putAll(headers)
                    if (referer != null) put("Referer", referer)
                }).text
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

        coroutineScope {
            sources.map { src ->
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

    /** Wrap a raw extractor link with the source's name/label, headers and referer. */
    private fun toExtractorLink(src: Source, l: ExtractorLink): ExtractorLink =
        ExtractorLink(
            source = src.name + INDICATOR,
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
     *  speed tracking and priority lookup: strips the "(Multimovies)" indicator
     *  and any trailing language annotation such as " Hindi". */
    internal fun sourceKey(source: String?): String {
        if (source == null) return ""
        return source
            .replace(Regex("""\s+Hindi$""", RegexOption.IGNORE_CASE), "")
            .removeSuffix(INDICATOR)
            .trim()
    }

    /**
     * Order links for the player. Primary key is the *measured* per-source speed
     * ([SourceSpeedTracker.averageLatency]); unmeasured sources (cold start) fall
     * back to the curated static [priorityOf] ranking; then per-call embed
     * latency; then the Hindi preference.
     */
    internal fun sortLinks(
        links: List<ExtractorLink>,
        sources: List<Source>,
        priorityOf: (String) -> Int,
        preferHindi: Boolean = true,
    ): List<ExtractorLink> {
        val latencyByName = sources.associate { it.name to it.latencyMs }
        val comparator = compareBy<ExtractorLink>(
            { SourceSpeedTracker.averageLatency(sourceKey(it.source)) ?: Double.MAX_VALUE },
            { priorityOf(sourceKey(it.source)) },
            { latencyByName[sourceKey(it.source)] ?: Long.MAX_VALUE },
        ).thenByDescending { if (preferHindi) isHindi(it) else false }
        return links.sortedWith(comparator)
    }

    /** Unified per-source extraction: inline extractor first, then registry, then sniff. */
    private suspend fun extractSource(
        src: Source,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> = when (src.extraction) {
        ExtractionType.MASTER_PLAYLIST -> extractVixsrc(src)
        ExtractionType.VIDLINK -> extractVidlink(src)
        ExtractionType.GENERIC -> {
            // If unwrapEmbed already surfaced a playable stream or proxy relay URL,
            // emit it directly — no extra page fetch needed.
            directStreamLink(src)?.let { listOf(it) } ?: extractGeneric(src, onSubtitle)
        }
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
        val source = src.name + INDICATOR
        val name = if (isHindiHint(src.name, src.url, u)) "$source Hindi" else source
        val type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        val headers = buildMap {
            putAll(src.headers)
            src.referer?.let { put("Referer", it) }
        }
        return ExtractorLink(
            source = source,
            name = name,
            url = u,
            referer = src.referer ?: u,
            quality = getQualityFromName(u),
            headers = headers,
            extractorData = null,
            type = type,
            audioTracks = emptyList(),
        )
    }

    private suspend fun extractGeneric(
        src: Source,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
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

        // Stage a: CloudStream extractor registry (installed/built-in extractors).
        val found = mutableListOf<ExtractorLink>()
        val registryOk = runCatching {
            loadExtractor(
                url = src.url,
                referer = src.referer,
                subtitleCallback = onSubtitle,
                callback = { found.add(it) },
            )
        }.getOrDefault(false)
        if (registryOk && found.isNotEmpty()) return found

        // Stage b: inline host extractor based on the resolved host.
        val host = hostOf(src.url)
        val inline = when {
            host.contains("vixsrc") -> extractVixsrc(src)
            host.contains("vidlink") -> extractVidlink(src)
            else -> null
        }
        if (!inline.isNullOrEmpty()) return inline

        // Stage c: generic m3u8/mp4 sniff.
        return sniff(src)
    }

    /** VixSrc: parse window.masterPlaylist from the static HTML and build the
     *  signed master playlist URL (no JS execution needed). */
    private suspend fun extractVixsrc(src: Source): List<ExtractorLink> {
        val headers = buildMap {
            putAll(sharedHeaders)
            putAll(src.headers)
            if (src.referer != null) put("Referer", src.referer)
        }
        val text = runCatching {
            app.get(src.url, timeout = 8, headers = headers).text
        }.getOrNull() ?: return emptyList()
        val signed = buildSignedVixsrcUrl(text) ?: return emptyList()
        return listOf(
            ExtractorLink(
                source = src.name,
                name = src.name,
                url = signed,
                referer = src.url,
                quality = -1,
                headers = headers,
                extractorData = null,
                type = ExtractorLinkType.M3U8,
                audioTracks = emptyList(),
            )
        )
    }

    /**
     * Pure helper: extract the signed vixsrc master playlist URL from the player page.
     * Tries window.masterPlaylist {url, token, expires} first, then a bare m3u8.
     */
    internal fun buildSignedVixsrcUrl(text: String): String? {
        val obj = Regex("""window\.masterPlaylist\s*=\s*\{([\s\S]*?)\};?""").find(text)
        if (obj != null) {
            val body = obj.groupValues[1]
            val url = Regex("""["']?url["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1) ?: return null
            val token = Regex("""["']?token["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1) ?: return null
            val expires = Regex("""["']?expires["']?\s*[:=]\s*["']?([^"',}\s]+)["']?""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1) ?: return null
            val normalized = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("http", ignoreCase = true) -> url
                else -> return null
            }
            val sep = if (normalized.contains("?")) "&" else "?"
            return "$normalized${sep}token=$token&expires=$expires&h=1&lang=en"
        }
        return extractStreamUrl(text)
    }

    /** VidLink: encrypt the TMDB id, then fetch the stream via the b/ API. */
    private suspend fun extractVidlink(src: Source): List<ExtractorLink> {
        val tmdbId = src.tmdbId ?: return emptyList()
        val headers = buildMap {
            putAll(sharedHeaders)
            putAll(src.headers)
            put("Referer", "https://vidlink.pro/")
            put("Origin", "https://vidlink.pro/")
        }
        val encText = runCatching {
            app.get("https://vidlink.pro/api/enc-vidlink?text=$tmdbId", timeout = 6, headers = headers).text
        }.getOrNull() ?: return emptyList()
        val encrypted = parseEncVidlink(encText) ?: return emptyList()
        val isTv = src.season != null && src.episode != null
        val path = if (isTv) "tv/$encrypted/${src.season}/${src.episode}" else "movie/$encrypted"
        val data = runCatching {
            app.get("https://vidlink.pro/api/b/$path", timeout = 6, headers = headers).text
        }.getOrNull() ?: return emptyList()
        val stream = extractM3u8FromVidlink(data) ?: return emptyList()
        return listOf(
            ExtractorLink(
                source = src.name,
                name = src.name,
                url = stream,
                referer = src.url,
                quality = -1,
                headers = headers,
                extractorData = null,
                type = ExtractorLinkType.M3U8,
                audioTracks = emptyList(),
            )
        )
    }

    /** Accepts common enc-vidlink response shapes (JSON {text|encrypted|enc|data} or a raw string). */
    internal fun parseEncVidlink(text: String): String? {
        val t = text.trim()
        if (t.startsWith("\"") || t.startsWith("'")) return t.trim('"', '\'')
        if (t.startsWith("{")) {
            return try {
                val obj = org.json.JSONObject(t)
                listOf("text", "encrypted", "enc", "data").forEach { k ->
                    obj.optString(k).takeIf { it.isNotBlank() }?.let { return it }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val v = obj.optString(keys.next()).takeIf { it.isNotBlank() }
                    if (v != null) return v
                }
                null
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    internal fun extractM3u8FromVidlink(text: String): String? {
        extractStreamUrl(text)?.let { return it }
        return try {
            val obj = org.json.JSONObject(text)
            obj.optString("url").takeIf { it.isNotBlank() }
                ?: obj.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: obj.optString("stream").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** Generic fallback: fetch the player page and harvest the first stream URL
     *  using a multi-strategy approach (direct regex, proxy pattern, video/source
     *  tags, JS config objects, URL-encoded m3u8). */
    private suspend fun sniff(src: Source): List<ExtractorLink> {
        val headers = buildMap {
            putAll(sharedHeaders)
            putAll(src.headers)
            if (src.referer != null) put("Referer", src.referer)
        }
        val text = runCatching {
            app.get(src.url, timeout = 5, headers = headers).text
        }.getOrNull() ?: return emptyList()

        val stream = buildProxyStreamUrl(text, src.url)
            ?: extractStreamUrl(text)
            ?: extractVideoSourceUrl(text, src.url)
            ?: extractFromJsConfig(text)
            ?: decodeEncodedStreamUrl(text)
            ?: return emptyList()

        val source = src.name + INDICATOR
        val name = if (isHindiHint(src.name, src.url, stream)) "$source Hindi" else source
        val linkType = if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO
        return listOf(
            ExtractorLink(
                source = source,
                name = name,
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
