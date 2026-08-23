package com.multimovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import java.util.Collections

/**
 * MultiSourcePuller - the source-priority / parallel-pull / timeout engine.
 *
 * Given a list of (serverName, url) pairs it:
 *   1. Orders them by [priorityOf] (reliable + fast first).
 *   2. Launches ALL of them concurrently (parallel pulling).
 *   3. Wraps each individual source in a [timeoutMs] timeout (default 30s).
 *      A single slow/dead source can never block the others.
 *   4. Returns the successfully extracted links, sorted by priority.
 *
 * This is intentionally decoupled from the provider so the strategy can be
 * tuned (timeouts, priority weights, concurrency limits) in one place.
 */
object MultiSourcePuller {

    data class Source(
        val name: String,
        val url: String,
        val referer: String? = null,
    )

    const val INDICATOR = " (Multimovies)"

    /** Regexes for the generic embed sniffer: stream URLs to harvest directly. */
    internal val STREAM_URL_REGEXES = listOf(
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
        Regex("""https?://[^\s"'<>]+\.m3u8"""),
        Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*"""),
        Regex("""https?://[^\s"'<>]+\.webm[^\s"'<>]*"""),
        Regex("""https?://[^\s"'<>]+\.mkv[^\s"'<>]*"""),
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

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    )

    /**
     * @param sources   raw server list (unsorted is fine, sorting happens here)
     * @param timeoutMs per-source hard timeout in ms (project default: 30_000)
     * @param priorityOf maps a server name to a sort index (lower = better)
     * @param onSubtitle called for each subtitle found
     * @return list of extractor links, ordered by source priority
     */
    suspend fun pull(
        sources: List<Source>,
        timeoutMs: Long = MultimoviesProvider.SOURCE_TIMEOUT_MS,
        priorityOf: (String) -> Int,
        onSubtitle: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()

        val ordered = sources.sortedBy { priorityOf(it.name) }
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        coroutineScope {
            ordered.map { src ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        runCatching {
                            loadExtractor(
                                url = src.url,
                                referer = src.referer,
                                subtitleCallback = { subs.add(it) },
                                callback = { l ->
                                    links.add(
                                        ExtractorLink(
                                            source = src.name + INDICATOR,
                                            name = l.name,
                                            url = l.url,
                                            referer = l.referer,
                                            quality = l.quality,
                                            headers = l.headers,
                                            extractorData = null,
                                            type = l.type,
                                            audioTracks = emptyList()
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        subs.forEach { onSubtitle(it) }
        links
            .sortedBy { priorityOf(it.source?.removeSuffix(INDICATOR).orEmpty()) }
    }

    /**
     * Generic fallback for embed hosts that CloudStream's extractor registry
     * can't handle (e.g. custom Cineverse/Nexa players). Fetches each embed
     * page and harvests the first m3u8/mp4/webm/mkv URL found in the response,
     * emitting a direct link. Per-source bounded by [timeoutS]; best-effort
     * (returns empty list / partial results, never throws).
     */
    suspend fun sniffEmbeds(
        sources: List<Pair<String, String>>,
        timeoutS: Long,
        referer: String?,
    ): List<ExtractorLink> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) return@withContext emptyList()
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        coroutineScope {
            sources.map { (name, url) ->
                async {
                    withTimeoutOrNull(timeoutS * 1000L) {
                        runCatching {
                            val headers = buildMap {
                                putAll(sharedHeaders)
                                if (referer != null) put("Referer", referer)
                            }
                            val text = app.get(
                                url, timeout = timeoutS, headers = headers,
                            ).text ?: return@runCatching
                            val stream = extractStreamUrl(text) ?: return@runCatching
                            val source = name + INDICATOR
                            val linkType =
                                if (stream.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
                                else ExtractorLinkType.VIDEO
                            links.add(
                                ExtractorLink(
                                    source = source,
                                    name = source,
                                    url = stream,
                                    referer = url,
                                    quality = getQualityFromName(stream),
                                    headers = headers,
                                    extractorData = null,
                                    type = linkType,
                                    audioTracks = emptyList(),
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        links
    }
}