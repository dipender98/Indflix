package com.multimovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private val INDICATOR = " (Multimovies)"

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
        val links = Collections.synchronizedList<ArrayList<ExtractorLink>>(ArrayList())
        val subs = Collections.synchronizedList<ArrayList<SubtitleFile>>(ArrayList())

        coroutineScope {
            ordered.map { src ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        runCatching {
                            loadExtractor(
                                url = src.url,
                                referer = src.referer,
                                subtitleCallback = { subs.addAll(it) },
                                callback = { l ->
                                    links.add(
                                        newExtractorLink(
                                            source = src.name + INDICATOR,
                                            name = l.name,
                                            url = l.url,
                                            type = l.type,
                                        ) {
                                            this.referer = l.referer
                                            this.quality = l.quality
                                            this.headers = l.headers
                                        }
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
}