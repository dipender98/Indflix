package com.multimovies

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
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
}

/** One resolved dooplayer server: its display name, the final (post-unwrap)
 *  stream/embed URL, the admin-ajax round-trip latency as a speed hint, and
 *  the raw pre-unwrap embed URL (still carrying the IMDB id) used only for
 *  last-resort meta recovery. Lives here so [EmbedPrefetchCache] and
 *  MultimoviesProvider share a single definition. */
data class ResolvedEmbed(
    val name: String,
    val url: String,
    val latencyMs: Long,
    val embedUrl: String? = null,
)

/** Session-level cache of player sources prefetched in the background while a
 *  movie's detail page is open, keyed by the page URL loadLinks() receives.
 *  A completed hit lets playback start without re-fetching the page or hitting
 *  admin-ajax at all. TTL is short because embed/stream URLs carry expiring
 *  signed tokens; bounded with evict-oldest like SearchCache.
 *
 *  While a prefetch is still running the entry holds its coroutine, so a Play
 *  tap (or a detail-page revisit) awaits/joins the same job instead of
 *  duplicating the network work. */
object EmbedPrefetchCache {
    private data class Entry(
        val embeds: List<ResolvedEmbed>?,
        val inFlight: Deferred<List<ResolvedEmbed>>?,
        val expiresAt: Long,
    )

    private const val TTL_MS = 4 * 60 * 1000L
    private const val MAX_SIZE = 32

    private val map = ConcurrentHashMap<String, Entry>()

    /** Completed, still-valid results for [key], or null (also when in-flight). */
    fun get(key: String): List<ResolvedEmbed>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            map.remove(key)
            return null
        }
        return e.embeds
    }

    /** Runs [resolve] for [key] unless one is already running or completed, in
     *  which case it joins that work. Exactly one caller executes [resolve];
     *  the result is cached (or the entry invalidated when empty) and shared
     *  with every concurrent caller. */
    suspend fun resolveOrJoin(
        key: String,
        resolve: suspend () -> List<ResolvedEmbed>,
        timeoutAtMs: Long = System.currentTimeMillis() + TTL_MS,
    ): List<ResolvedEmbed> {
        get(key)?.let { return it }
        map[key]?.inFlight?.let { return it.await() }

        val job = CompletableDeferred<List<ResolvedEmbed>>()
        map.putIfAbsent(key, Entry(null, job, timeoutAtMs))
        if (map[key]?.inFlight === job) {
            // We own the resolution: run it and complete the shared job.
            return try {
                val result = resolve()
                if (result.isNotEmpty()) put(key, result) else invalidate(key)
                job.complete(result)
                result
            } catch (t: Throwable) {
                invalidate(key)
                job.completeExceptionally(t)
                throw t
            }
        }
        // Lost the race: await the winner's job, or its already-cached result.
        return map[key]?.inFlight?.await() ?: get(key) ?: emptyList()
    }

    /** Wait up to [timeoutMs] for an in-flight prefetch of [key] to finish.
     *  Returns completed results if present or finished within the wait, else
     *  null (caller falls back to the full resolution path). */
    suspend fun awaitInFlight(key: String, timeoutMs: Long = 1500L): List<ResolvedEmbed>? {
        get(key)?.let { return it }
        val e = map[key] ?: return null
        val job = e.inFlight ?: return null
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    fun put(key: String, embeds: List<ResolvedEmbed>) {
        if (embeds.isEmpty()) return
        if (map.size >= MAX_SIZE) {
            map.entries.minByOrNull { it.value.expiresAt }?.key?.let { map.remove(it) }
        }
        map[key] = Entry(embeds, null, System.currentTimeMillis() + TTL_MS)
    }

    /** Drops a stale entry so the next play attempt falls back to full resolution. */
    fun invalidate(key: String) {
        map.remove(key)
    }
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
}

/** A curated, id-based public streaming source. Extensible — add more hosts by
 *  appending entries; the runtime probe (in loadLinks/pull) keeps only the ones
 *  that actually respond from the user's network. */
class GlobalSource(
    val name: String,
    val idType: SourceId,
    val buildUrl: (id: String, season: Int?, episode: Int?) -> String?,
    val headers: Map<String, String> = emptyMap(),
)

/** Curated global source registry (dooplayer-independent). URL patterns verified
 *  from public documentation / health-checked provider lists. Note: many public
 *  embed hosts rotate/expire fast (vixsrc.to went Next.js, vidsrc.net died,
 *  vidlink.pro API 404, multiembed.mov 403), so the list is kept to hosts that
 *  actually respond; the dooplayer embeds resolved from the site remain the
 *  primary source path. Append new hosts as they become available — the runtime
 *  probe (in loadLinks/pull) keeps only the ones that answer from the user's
 *  network.
 *
 *  Aug 2026 live diagnostic: each host is called on its final hop so we skip
 *  any 301 chain at runtime (vidsrc-embed.su -> vsembed.ru, 111movies.com ->
 *  111movies.net -> player.vidlove.cc). The Referer matches the live final
 *  host so the player page actually renders. */
object GlobalSources {
    val list: List<GlobalSource> = listOf(
        GlobalSource(
            name = "2embed.cc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://www.2embed.cc/embed/tv?imdb=$id&s=$s&e=$e"
                else "https://www.2embed.cc/embed/movie?imdb=$id"
            },
            headers = mapOf("Referer" to "https://www.2embed.cc/"),
        ),
        GlobalSource(
            // Aug 2026: vidsrc-embed.su now 301-redirects to vsembed.ru; pointing
            // straight at the live host saves a round-trip on every loadLinks.
            name = "VidSrc",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://vsembed.ru/embed/$id/$s-$e"
                else "https://vsembed.ru/embed/$id"
            },
            headers = mapOf("Referer" to "https://vsembed.ru/"),
        ),
        GlobalSource(
            // Aug 2026: 111movies.com -> 111movies.net -> player.vidlove.cc is the
            // final hop. Pointing at the live final host means the player page
            // renders on the first request with no chained 301s.
            name = "111Movies",
            idType = SourceId.IMDB,
            buildUrl = { id, s, e ->
                if (s != null && e != null) "https://player.vidlove.cc/embed/tv/$id/$s/$e"
                else "https://player.vidlove.cc/embed/movie/$id"
            },
            headers = mapOf("Referer" to "https://player.vidlove.cc/"),
        ),
    )
}
