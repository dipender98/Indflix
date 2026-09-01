package com.ottmirror

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal object TmdbMeta {
    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    data class Detail(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val name: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val year: String? = null,
        val rating: Double? = null,
        val overview: String? = null,
        val genres: List<String>? = null,
        val cast: List<ActorData>? = null,
    )

    data class EpisodeMeta(
        val name: String? = null,
        val overview: String? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val rating: Double? = null,
    )

    data class PosterInfo(val rating: Double?, val poster: String?)

    private val detailCache = ConcurrentHashMap<String, Detail>()
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()
    private val titleSearchCache = ConcurrentHashMap<String, Pair<Int, String>>()
    private val posterRatingCache = ConcurrentHashMap<String, PosterInfo>()

    suspend fun fetchMeta(tmdbId: Int, type: String): Detail? {
        if (tmdbId <= 0) return null
        val cacheKey = "$tmdbId|$type"
        detailCache[cacheKey]?.let { return it }
        val path = if (type == "movie") "movie" else "tv"
        val url = "$TMDB_API/$path/$tmdbId?api_key=$TMDB_API_KEY&language=en-US&append_to_response=external_ids,credits"
        val detail = runCatching { parseDetail(app.get(url, timeout = 6).text, type) }.getOrNull()
        if (detail != null) detailCache[cacheKey] = detail
        return detail
    }

    suspend fun findByImdb(imdbId: String): Pair<Int, String>? {
        if (!imdbId.startsWith("tt")) return null
        imdbFindCache[imdbId]?.let { return it }
        val url = "$TMDB_API/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id&language=en-US"
        val result = runCatching {
            val root = JSONObject(app.get(url, timeout = 5).text)
            val movie = root.optJSONArray("movie_results")?.optJSONObject(0)
            val tv = root.optJSONArray("tv_results")?.optJSONObject(0)
            when {
                movie != null -> movie.optInt("id", -1).takeIf { it > 0 }?.let { it to "movie" }
                tv != null -> tv.optInt("id", -1).takeIf { it > 0 }?.let { it to "tv" }
                else -> null
            }
        }.getOrNull()
        if (result != null) imdbFindCache[imdbId] = result
        return result
    }

    /**
     * TMDB /search/multi fallback used when post.php carries no tmdb_id and
     * no imdb_id (common on home-row titles — live probe: tmdb_id=null for
     * The Mentalist/Vikings/The Good Doctor). Gives embed-tmdb a TMDB key so
     * the sessionless MP4 path actually runs for those titles.
     */
    suspend fun searchByTitle(title: String): Pair<Int, String>? {
        if (title.isBlank()) return null
        val key = "t|${title.lowercase().trim()}"
        titleSearchCache[key]?.let { return it }
        val encoded = URLEncoder.encode(title.trim(), "UTF-8")
        val result = runCatching {
            val root = JSONObject(
                app.get(
                    "$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$encoded&language=en-US&include_adult=false&page=1",
                    timeout = 5,
                ).text
            )
            val arr = root.optJSONArray("results") ?: return@runCatching null
            (0 until arr.length()).firstNotNullOfOrNull { i ->
                val m = arr.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                val name = m.optString("title").ifBlank { m.optString("name") }
                if (!name.equals(title.trim(), ignoreCase = true)) return@firstNotNullOfOrNull null
                val type = m.optString("media_type").takeIf { it == "movie" || it == "tv" } ?: return@firstNotNullOfOrNull null
                m.optInt("id", -1).takeIf { it > 0 }?.let { it to type }
            }
        }.getOrNull()
        if (result != null) titleSearchCache[key] = result
        return result
    }

    suspend fun fetchEpisodes(tmdbId: Int, seasons: Set<Int>): Map<Pair<Int, Int>, EpisodeMeta> {
        if (tmdbId <= 0 || seasons.isEmpty()) return emptyMap()
        val semaphore = Semaphore(3)
        return coroutineScope {
            seasons.map { season ->
                async {
                    semaphore.acquire()
                    try { withTimeoutOrNull(1300L) { fetchSeason(tmdbId, season) } }
                    finally { semaphore.release() }
                }
            }.awaitAll().filterNotNull().flatten().associate { (s, e, meta) -> (s to e) to meta }
        }
    }

    suspend fun resolvePosterRating(title: String, year: String?, isMovie: Boolean): PosterInfo? {
        if (title.isBlank()) return null
        val key = "${title.lowercase()}|${year ?: "?"}|$isMovie"
        posterRatingCache[key]?.let { return it }
        val encoded = URLEncoder.encode(title.trim(), "UTF-8")
        val info = runCatching {
            val raw = app.get(
                "$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$encoded&language=en-US&include_adult=false&page=1",
                timeout = 5,
            ).text
            val root = JSONObject(raw)
            val results = root.optJSONArray("results") ?: return@runCatching null
            val wantType = if (isMovie) "movie" else "tv"
            var best: PosterInfo? = null
            for (i in 0 until results.length()) {
                val m = results.optJSONObject(i) ?: continue
                if (m.optString("media_type") != wantType) continue
                val name = m.optString("title").takeIf { it.isNotBlank() }
                    ?: m.optString("name").takeIf { it.isNotBlank() } ?: continue
                val date = m.optString("release_date").takeIf { it.isNotBlank() }
                    ?: m.optString("first_air_date").takeIf { it.isNotBlank() }
                val poster = m.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$IMG_BASE$it" }
                val rating = m.optDouble("vote_average", -1.0).takeIf { it > 0 }
                val yearMatch = year.isNullOrBlank() || date?.take(4) == year.take(4)
                if (name.equals(title.trim(), ignoreCase = true) && yearMatch) {
                    return@runCatching PosterInfo(rating, poster)
                }
                if (best == null) best = PosterInfo(rating, poster)
            }
            best
        }.getOrNull()
        info?.let { posterRatingCache[key] = it }
        return info
    }

    fun parseDetail(raw: String?, type: String): Detail? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw)
            val name = m.optString("title").takeIf { it.isNotBlank() }
                ?: m.optString("name").takeIf { it.isNotBlank() } ?: return null
            val cast = m.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val cname = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ActorData(
                        Actor(cname, c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$IMG_BASE$it" } ?: ""),
                        roleString = c.optString("character").takeIf { it.isNotBlank() },
                    )
                }
            }
            Detail(
                tmdbId = m.optInt("id", -1).takeIf { it > 0 },
                imdbId = m.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() },
                name = name,
                poster = m.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$IMG_BASE$it" },
                backdrop = m.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "$IMG_BACKDROP$it" },
                year = (m.optString("release_date").takeIf { it.isNotBlank() }
                    ?: m.optString("first_air_date").takeIf { it.isNotBlank() })?.take(4),
                rating = m.optDouble("vote_average", -1.0).takeIf { it > 0 },
                overview = m.optString("overview").takeIf { it.isNotBlank() },
                genres = m.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() } }
                },
                cast = cast,
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<Triple<Int, Int, EpisodeMeta>> {
        val url = "$TMDB_API/tv/$tmdbId/season/$season?api_key=$TMDB_API_KEY&language=en-US"
        return runCatching {
            val root = JSONObject(app.get(url, timeout = 5).text)
            root.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val ep = e.optInt("episode_number", -1)
                    if (ep <= 0) return@mapNotNull null
                    Triple(
                        season, ep,
                        EpisodeMeta(
                            name = e.optString("name").takeIf { it.isNotBlank() },
                            overview = e.optString("overview").takeIf { it.isNotBlank() },
                            released = e.optString("air_date").takeIf { it.isNotBlank() },
                            thumbnail = e.optString("still_path").takeIf { it.isNotBlank() }?.let { "$IMG_BASE$it" },
                            rating = e.optDouble("vote_average", -1.0).takeIf { it > 0 },
                        ),
                    )
                }
            } ?: emptyList()
        }.getOrNull() ?: emptyList()
    }
}