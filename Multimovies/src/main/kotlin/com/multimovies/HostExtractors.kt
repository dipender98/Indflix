package com.multimovies

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Whether a [GlobalSource] is keyed by an IMDB id or a TMDB id. */
enum class SourceId { IMDB, TMDB }

/** Ids + season/episode resolved during [MultimoviesProvider.load] and needed to
 *  build direct stream URLs in [MultimoviesProvider.loadLinks]. */
data class SourceMeta(
    val imdbId: String,
    val tmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/** Maps the exact url passed to loadLinks() (episode url / movie url) to its ids
 *  and season/episode, populated during load() so loadLinks() never re-solves
 *  Cloudflare just to get an IMDB/TMDB id. */
object SourceMetaCache {
    private val map = ConcurrentHashMap<String, SourceMeta>()
    fun put(key: String, meta: SourceMeta) = map.put(key, meta)
    fun get(key: String): SourceMeta? = map[key]
    fun clear() = map.clear()
}

/** Session-level cache of resolved stream links per (imdbId, season, episode).
 *  Reopening the same title/episode reuses cached streams (zero probe latency);
 *  entries expire so stale URLs/tokens don't linger forever. TTL is short (5 min)
 *  because most stream URLs carry expiring signed tokens. */
object LinkCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60 * 1000L
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(imdbId: String?, season: Int?, episode: Int?): List<ExtractorLink>? {
        if (imdbId == null) return null
        val key = "$imdbId|$season|$episode"
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.links
    }

    fun put(imdbId: String?, season: Int?, episode: Int?, links: List<ExtractorLink>) {
        if (imdbId == null || links.isEmpty()) return
        map["$imdbId|$season|$episode"] = Entry(links, System.currentTimeMillis() + TTL_MS)
    }

    fun clear() = map.clear()
}

/** A curated, id-based public streaming source. Extensible — add more hosts by
 *  appending entries; the runtime probe (in loadLinks/pull) keeps only the ones
 *  that actually respond from the user's network. */
class GlobalSource(
    val name: String,
    val idType: SourceId,
    val extraction: MultiSourcePuller.ExtractionType,
    val buildUrl: (id: String, season: Int?, episode: Int?) -> String?,
    val headers: Map<String, String> = emptyMap(),
    val priority: Int = 100,
)

/** Curated global source registry (dooplayer-independent). URL patterns verified
 *  from public documentation / health-checked provider lists. Note: many public
 *  embed hosts rotate/expire fast (vixsrc.to went Next.js, vidsrc.net died,
 *  vidlink.pro API 404, multiembed.mov 403), so the list is kept to hosts that
 *  actually respond; the dooplayer embeds resolved from the site remain the
 *  primary source path. Append new hosts as they become available — the runtime
 *  probe (in loadLinks/pull) keeps only the ones that answer from the user's
 *  network. */
object GlobalSources {
    val list: List<GlobalSource> = listOf(
        GlobalSource(
            name = "2embed.cc",
            idType = SourceId.IMDB,
            extraction = MultiSourcePuller.ExtractionType.GENERIC,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://www.2embed.cc/embed/tv?imdb=$id&s=$s&e=$e"
                else "https://www.2embed.cc/embed/movie?imdb=$id"
            },
            headers = mapOf("Referer" to "https://www.2embed.cc/"),
            priority = 0,
        ),
    )
}

/** Liveness-checker for cached stream links. When the user taps play on a
 *  previously-resolved title, the cached links are probed (HEAD / Range GET)
 *  first. Only responsive links are emitted instantly; if all cached links are
 *  stale/dead, the full resolution pipeline runs instead — which means
 *  CloudStream's loading dialog (with source-switch options) actually appears
 *  instead of silently buffering a dead URL. */
internal object LinkVerifier {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    internal fun isVerifiedStream(status: Int, contentType: String?): Boolean {
        if (status in 200..399) return true
        val ct = contentType.orEmpty().lowercase()
        return ct.contains("mpegurl") || ct.contains("mp2t") || ct.contains("mp4") || ct.contains("video") || ct.contains("octet-stream")
    }

    suspend fun verify(links: List<ExtractorLink>, perLinkTimeoutMs: Long = 2500): List<ExtractorLink> {
        if (links.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val sem = Semaphore(4)
            coroutineScope {
                links.map { link ->
                    async {
                        sem.acquire()
                        try {
                            withTimeoutOrNull(perLinkTimeoutMs) { verifyLink(link) }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    private fun verifyLink(link: ExtractorLink): ExtractorLink? {
        val url = link.url ?: return null
        val headers = buildMap {
            link.headers?.let { putAll(it) }
            link.referer?.let { put("Referer", it) }
        }
        if (probe("HEAD", url, headers)) return link
        if (probe("GET", url, headers + ("Range" to "bytes=0-0"))) return link
        return null
    }

    private fun probe(method: String, url: String, headers: Map<String, String>): Boolean {
        val req = Request.Builder().url(url).apply {
            if (method == "HEAD") head() else get()
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                isVerifiedStream(resp.code, resp.header("Content-Type"))
            }
        }.getOrDefault(false)
    }
}
