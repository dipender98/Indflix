package com.multimovies

import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.concurrent.ConcurrentHashMap

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
 *  entries expire so stale URLs/tokens don't linger forever. */
object LinkCache {
    private data class Entry(val links: List<ExtractorLink>, val expiresAt: Long)
    private const val TTL_MS = 15 * 60 * 1000L
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
