package com.indstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Keyless suggest + metadata source backing the TMDB race. */
object ImdbService {

    private const val SUGGEST_API = "https://v3.sg.media-imdb.com/suggestion/x"
    private const val CINEMETA_API = "https://v3-cinemeta.strem.io/meta"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    private const val WAIT_MS = 11_000L

    /** One suggest hit. */
    data class ImdbHit(
        val imdbId: String,
        val title: String,
        val year: Int?,
        val type: String, // "movie" | "tv".
        val poster: String?,
        val rank: Int?,
    )

    /** Full detail merged from a metadata race. */
    data class MetaDetail(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val name: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val year: String? = null,
        val rating: Double? = null,
        val overview: String? = null,
        val genres: List<String>? = null,
        val cast: List<ActorData>? = null,
    )

    private data class Entry(val hits: List<ImdbHit>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<ImdbHit>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metaCache = ConcurrentHashMap<String, MetaDetail>()

    /** Suggest lookup with in-memory cache. Never throws. */
    suspend fun suggest(query: String): List<ImdbHit> {
        if (query.isBlank()) return emptyList()
        val key = query.trim().lowercase()
        cache[key]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.hits
            cache.remove(key)
        }
        val mine = scope.async {
            val found = suggestRemote(query)
            if (found.isNotEmpty()) {
                if (cache.size >= CACHE_MAX) {
                    cache.entries.minByOrNull { e -> e.value.expiresAt }?.let { e -> cache.remove(e.key) }
                }
                cache[key] = Entry(found, System.currentTimeMillis() + CACHE_TTL_MS)
            }
            found
        }
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing == null) mine.invokeOnCompletion { inFlight.remove(key, mine) } else mine.cancel()
        return withTimeoutOrNull(WAIT_MS) { runCatching { (existing ?: mine).await() }.getOrNull() }.orEmpty()
    }

    private suspend fun suggestRemote(query: String): List<ImdbHit> {
        // Suggest keys collapse whitespace to underscores.
        val slug = query.trim().lowercase().replace(Regex("""\s+"""), "_")
        val encoded = URLEncoder.encode(slug, "UTF-8")
        val text = withTimeoutOrNull(6000L) {
            runCatching { app.get("$SUGGEST_API/$encoded.json", timeout = 6).text }.getOrNull()
        } ?: return emptyList()
        return parseSuggest(text)
    }

    /** Map a suggest payload to hits. Name-kind entries are dropped. Pure. */
    fun parseSuggest(raw: String?): List<ImdbHit> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("d") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                val title = o.optString("l").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qid = o.optString("qid")
                if (qid == "videoGame") return@mapNotNull null
                val type = if (qid == "tvSeries" || qid == "tvMiniSeries") "tv" else "movie"
                ImdbHit(
                    imdbId = id,
                    title = title,
                    year = o.optInt("y", -1).takeIf { it > 0 },
                    type = type,
                    poster = o.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() },
                    rank = o.optInt("rank", -1).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Keyless full metadata by IMDB id. Never throws. */
    suspend fun fetchMeta(imdbId: String, type: String): MetaDetail? {
        if (!imdbId.startsWith("tt")) return null
        val kind = if (type == "movie") "movie" else "series"
        val key = "$kind|$imdbId"
        metaCache[key]?.let { return it }
        val text = withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/$kind/$imdbId.json", timeout = 7).text }.getOrNull()
        } ?: return null
        val detail = parseCinemeta(text, imdbId) ?: return null
        if (metaCache.size < CACHE_MAX) metaCache[key] = detail
        return detail
    }

    /** Map a Cinemeta envelope to detail. Pure. */
    fun parseCinemeta(raw: String?, imdbId: String): MetaDetail? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw).optJSONObject("meta") ?: return null
            val cast = m.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                    val c = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ActorData(Actor(c, ""), roleString = null)
                }
            }
            MetaDetail(
                imdbId = imdbId,
                name = m.optString("name").takeIf { it.isNotBlank() },
                poster = m.optString("poster").takeIf { it.isNotBlank() },
                backdrop = m.optString("background").takeIf { it.isNotBlank() },
                year = m.optString("releaseInfo")?.take(4)?.takeIf { it[0].isDigit() },
                rating = m.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 },
                overview = m.optString("description").takeIf { it.isNotBlank() },
                genres = m.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                },
                cast = cast,
            )
        } catch (e: Exception) { null }
    }

    /** Episode rows from a Cinemeta series payload. Pure. */
    fun parseCinemetaEpisodes(raw: String?): List<TmdbService.TmdbEpisode> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val videos = JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
            (0 until videos.length()).mapNotNull { i ->
                val v = videos.optJSONObject(i) ?: return@mapNotNull null
                val season = v.optInt("season", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val ep = v.optInt("episode", -1).takeIf { it > 0 } ?: return@mapNotNull null
                TmdbService.TmdbEpisode(
                    seasonNumber = season,
                    episodeNumber = ep,
                    name = v.optString("title").takeIf { it.isNotBlank() },
                    overview = v.optString("overview").takeIf { it.isNotBlank() },
                    released = v.optString("released").takeIf { it.isNotBlank() },
                    thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
                    rating = null,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Raw series payload for episode listing. Never throws. */
    suspend fun fetchSeriesRaw(imdbId: String): String? {
        if (!imdbId.startsWith("tt")) return null
        return withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/series/$imdbId.json", timeout = 7).text }.getOrNull()
        }
    }
}
