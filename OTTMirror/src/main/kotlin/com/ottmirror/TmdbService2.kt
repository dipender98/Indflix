package com.ottmirror

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * TMDB metadata engine for OTTMirror. Search, detail, episodes, IMDB→TMDB lookup.
 * Embedded public API key (same approach as Multimovies — no settings hook in pinned lib).
 */
object TmdbService2 {

    private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    private val detailCache = ConcurrentHashMap<String, TmdbDetail>()
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()

    /** One search hit (movie or series). */
    data class TmdbItem(
        val tmdbId: Int?,
        val imdbId: String?,
        val type: String,      // "movie" or "series"
        val name: String,
        val year: String?,
        val poster: String?,
        val rating: Double?,
    )

    /** Full metadata for a detail page. */
    data class TmdbDetail(
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

    /** Per-episode metadata. */
    data class TmdbEpisode(
        val seasonNumber: Int = -1,
        val episodeNumber: Int = -1,
        val name: String? = null,
        val overview: String? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val rating: Double? = null,
    )

    /** Search movies + series via TMDB /search/multi. */
    suspend fun search(query: String): List<TmdbItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get(
                "$API/search/multi?api_key=$API_KEY&query=$encoded&language=en-US&include_adult=false&page=1",
                timeout = 5,
            ).text
        }.getOrNull() ?: return emptyList()
        return parseTmdbMultiSearch(json)
    }

    /** Trending this week — powers the home page. [type] is "movie" or "tv". */
    suspend fun trending(type: String, page: Int = 1): List<TmdbItem> {
        val url = "$API/trending/$type/week?api_key=$API_KEY&language=en-US&page=$page"
        val json = runCatching { app.get(url, timeout = 5).text }.getOrNull() ?: return emptyList()
        return parseResults(json, type)
    }

    /** Popular titles — extra home page row. [type] is "movie" or "tv". */
    suspend fun popular(type: String, page: Int = 1): List<TmdbItem> {
        val url = "$API/$type/popular?api_key=$API_KEY&language=en-US&page=$page"
        val json = runCatching { app.get(url, timeout = 5).text }.getOrNull() ?: return emptyList()
        return parseResults(json, type)
    }

    /** Season numbers for a TV show (excludes specials/season 0). */
    suspend fun fetchTvSeasons(tmdbId: Int): List<Int> {
        if (tmdbId <= 0) return emptyList()
        val url = "$API/tv/$tmdbId?api_key=$API_KEY&language=en-US"
        val json = runCatching { app.get(url, timeout = 5).text }.getOrNull() ?: return emptyList()
        return try {
            val root = JSONObject(json)
            root.optJSONArray("seasons")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val num = s.optInt("season_number", -1)
                    num.takeIf { it > 0 }
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /** Fetch full TMDB metadata for [tmdbId] of [type] ("movie"|"series"). */
    suspend fun fetchMeta(tmdbId: Int, type: String): TmdbDetail? {
        if (tmdbId <= 0) return null
        val cacheKey = "$tmdbId|$type"
        detailCache[cacheKey]?.let { return it }
        val path = if (type == "movie") "movie" else "tv"
        val url = "$API/$path/$tmdbId?api_key=$API_KEY&language=en-US&append_to_response=external_ids,credits"
        val detail = runCatching { parseTmdbDetail(app.get(url, timeout = 6).text, type) }.getOrNull()
        if (detail != null) detailCache[cacheKey] = detail
        return detail
    }

    /** Resolve an IMDB id to (tmdbId, type) via TMDB's find endpoint. */
    suspend fun findByImdb(imdbId: String): Pair<Int, String>? {
        if (!imdbId.startsWith("tt")) return null
        imdbFindCache[imdbId]?.let { return it }
        val url = "$API/find/$imdbId?api_key=$API_KEY&external_source=imdb_id&language=en-US"
        val result = runCatching {
            val root = JSONObject(app.get(url, timeout = 5).text)
            val movie = root.optJSONArray("movie_results")?.optJSONObject(0)
            val tv = root.optJSONArray("tv_results")?.optJSONObject(0)
            when {
                movie != null -> movie.optInt("id", -1).takeIf { it > 0 }?.let { it to "movie" }
                tv != null -> tv.optInt("id", -1).takeIf { it > 0 }?.let { it to "series" }
                else -> null
            }
        }.getOrNull()
        if (result != null) imdbFindCache[imdbId] = result
        return result
    }

    /** Fetch TMDB episode metadata for the given [seasons] of [tmdbId], in parallel.
     *  Returns keyed (season, episode) -> metadata. */
    suspend fun fetchEpisodes(tmdbId: Int, seasons: Set<Int>): Map<Pair<Int, Int>, TmdbEpisode> {
        if (tmdbId <= 0 || seasons.isEmpty()) return emptyMap()
        val semaphore = Semaphore(3)
        return coroutineScope {
            seasons.map { season ->
                async {
                    semaphore.acquire()
                    try {
                        withTimeoutOrNull(1300L) { fetchSeason(tmdbId, season) }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull().flatten().associate { ep -> (ep.seasonNumber to ep.episodeNumber) to ep }
        }
    }

    /** Fetch all episodes of one season (used by the provider's TV detail). */
    suspend fun fetchSeasonPublic(tmdbId: Int, season: Int): List<TmdbEpisode> {
        return fetchSeason(tmdbId, season).orEmpty()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun parseTmdbMultiSearch(json: String): List<TmdbItem> {
        return try {
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val r = results.optJSONObject(i) ?: return@mapNotNull null
                val mediaType = str(r, "media_type") ?: return@mapNotNull null
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
                val type = if (mediaType == "movie") "movie" else "series"
                val name = str(r, "title") ?: str(r, "name") ?: return@mapNotNull null
                TmdbItem(
                    tmdbId = r.optInt("id", -1).takeIf { it > 0 },
                    imdbId = null, // IMDB id not in search results; fetch detail if needed
                    type = type,
                    name = name,
                    year = (str(r, "release_date") ?: str(r, "first_air_date"))?.take(4),
                    poster = str(r, "poster_path")?.let { "$IMG_BASE$it" },
                    rating = r.optDouble("vote_average", -1.0).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseTmdbDetail(raw: String?, type: String): TmdbDetail? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw)
            val name = str(m, "title") ?: str(m, "name") ?: return null
            val cast = m.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val cname = str(c, "name") ?: return@mapNotNull null
                    ActorData(
                        Actor(cname, str(c, "profile_path")?.let { "$IMG_BASE$it" } ?: ""),
                        roleString = str(c, "character"),
                    )
                }
            }
            TmdbDetail(
                tmdbId = m.optInt("id", -1).takeIf { it > 0 },
                imdbId = m.optJSONObject("external_ids")?.let { str(it, "imdb_id") },
                name = name,
                poster = str(m, "poster_path")?.let { "$IMG_BASE$it" },
                backdrop = str(m, "backdrop_path")?.let { "$IMG_BACKDROP$it" },
                year = (str(m, "release_date") ?: str(m, "first_air_date"))?.take(4),
                rating = m.optDouble("vote_average", -1.0).takeIf { it > 0 },
                overview = str(m, "overview"),
                genres = m.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> str(arr.optJSONObject(i), "name") }
                },
                cast = cast,
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<TmdbEpisode>? {
        val cacheKey = "$tmdbId|$season"
        seasonCache[cacheKey]?.let { return it }
        val url = "$API/tv/$tmdbId/season/$season?api_key=$API_KEY&language=en-US"
        val json = runCatching { app.get(url, timeout = 5).text }.getOrNull() ?: return null
        val episodes = try {
            val root = JSONObject(json)
            root.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val epNum = e.optInt("episode_number", -1).takeIf { it > 0 } ?: return@mapNotNull null
                    TmdbEpisode(
                        seasonNumber = season,
                        episodeNumber = epNum,
                        name = str(e, "name"),
                        overview = str(e, "overview"),
                        released = str(e, "air_date"),
                        thumbnail = str(e, "still_path")?.let { "$IMG_BASE$it" },
                        rating = e.optDouble("vote_average", -1.0).takeIf { it > 0 },
                    )
                }
            }
        } catch (e: Exception) { null }
        if (episodes != null) {
            seasonCache[cacheKey] = episodes
        }
        return episodes
    }

    /** Read a JSON string field, returning null when blank. */
    private fun str(obj: JSONObject, key: String): String? {
        val v = obj.optString(key)
        return if (v.isBlank()) null else v
    }

    /** Parse a TMDB results array into TmdbItems. [type] is "movie" or "tv". */
    private fun parseResults(json: String, type: String): List<TmdbItem> {
        return try {
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val r = results.optJSONObject(i) ?: return@mapNotNull null
                val name = str(r, "title") ?: str(r, "name") ?: return@mapNotNull null
                val mediaType = if (type == "movie") "movie" else "series"
                TmdbItem(
                    tmdbId = r.optInt("id", -1).takeIf { it > 0 },
                    imdbId = null,
                    type = mediaType,
                    name = name,
                    year = (str(r, "release_date") ?: str(r, "first_air_date"))?.take(4),
                    poster = str(r, "poster_path")?.let { "$IMG_BASE$it" },
                    rating = r.optDouble("vote_average", -1.0).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}
