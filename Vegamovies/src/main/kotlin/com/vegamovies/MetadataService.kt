package com.vegamovies

/**
 * MetadataService.kt — TMDB-backed keyless metadata engine for Vegamovies.
 *
 * The site itself stores posters as TMDB images and links IMDb ids, which
 * makes TMDB the fastest enrichment path: poster, backdrop, plot, genres,
 * cast and rating in one or two HTTP calls. Same key/embedded pattern the
 * Multimovies plugin uses (the CloudStream library exposes no user-key hook).
 */

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Read a JSON string field, returning null when blank. */
internal fun str(obj: JSONObject, key: String): String? {
    val v: String = obj.optString(key)
    return if (v.isBlank()) null else v
}

/**
 * TMDB search / detail / find-by-IMDb client. All calls are best-effort:
 * failures arrive as null / empty, never exceptions. Results are cached in
 * memory (TMDB metadata doesn't change minute-to-minute).
 */
object MetadataService {

    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    private val detailCache = ConcurrentHashMap<String, TmdbDetail>()
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()

    data class TmdbItem(
        val tmdbId: Int?,
        val imdbId: String?,
        val type: String,        // "movie" | "series"
        val name: String,
        val year: String?,
        val poster: String?,
        val rating: Double?,
    )

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

    /** Search movies + series; returns up to [limit] ranked hits. */
    suspend fun search(query: String, limit: Int = 8): List<TmdbItem> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get(
                "$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$encoded&language=en-US&include_adult=false&page=1",
                timeout = 6,
            ).text
        }.getOrNull() ?: return emptyList()
        return parseMultiSearch(json).take(limit)
    }

    /** Fetch full TMDB metadata for [tmdbId] of [type] ("movie"|"series"). */
    suspend fun fetchMeta(tmdbId: Int, type: String): TmdbDetail? {
        if (tmdbId <= 0) return null
        val cacheKey = "$tmdbId|$type"
        detailCache[cacheKey]?.let { return it }
        val path = if (type == "movie") "movie" else "tv"
        val url = "$TMDB_API/$path/$tmdbId?api_key=$TMDB_API_KEY&language=en-US&append_to_response=external_ids,credits"
        val detail = runCatching { parseDetail(app.get(url, timeout = 6).text) }.getOrNull()
        if (detail != null) detailCache[cacheKey] = detail
        return detail
    }

    /** Resolve an IMDb id to (tmdbId, type) via TMDB's find endpoint. */
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
                tv != null -> tv.optInt("id", -1).takeIf { it > 0 }?.let { it to "series" }
                else -> null
            }
        }.getOrNull()
        if (result != null) imdbFindCache[imdbId] = result
        return result
    }

    /**
     * One-shot title/year → (detail) resolution used by [VegamoviesProvider.load]:
     * prefers the IMDb id scraped from the page (exact), falls back to a TMDB
     * title search. Wrapped in a caller-supplied budget; returns null on miss.
     */
    suspend fun enrich(title: String, year: String?, imdbId: String?): TmdbDetail? {
        imdbId?.takeIf { it.startsWith("tt") }?.let { id ->
            findByImdb(id)?.let { (tmdbId, type) ->
                fetchMeta(tmdbId, type)?.let { return it }
            }
        }
        val hit = search(title).firstOrNull { year == null || it.year == null || it.year == year }
            ?: search(title).firstOrNull()
        val tmdbId = hit?.tmdbId ?: return null
        return fetchMeta(tmdbId, hit.type)
    }

    /** Parse a TMDB /search/multi response (movies + series only), deduped by title+year. */
    fun parseMultiSearch(raw: String?): List<TmdbItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            val results = root.optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val m = results.optJSONObject(i) ?: return@mapNotNull null
                val mediaType = m.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
                val id = m.optInt("id", -1)
                if (id <= 0) return@mapNotNull null
                val name = str(m, "title") ?: str(m, "name") ?: return@mapNotNull null
                TmdbItem(
                    tmdbId = id,
                    imdbId = null,
                    type = if (mediaType == "movie") "movie" else "series",
                    name = name,
                    year = (str(m, "release_date") ?: str(m, "first_air_date"))?.take(4),
                    poster = str(m, "poster_path")?.let { "$IMG_BASE$it" },
                    rating = m.optDouble("vote_average", -1.0).takeIf { it > 0 },
                )
            }.dedupedByTitle()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Collapse search hits sharing a normalized (title, year); highest-rated wins. */
    internal fun List<TmdbItem>.dedupedByTitle(): List<TmdbItem> {
        val best = LinkedHashMap<String, TmdbItem>()
        for (item in this) {
            val key = item.name.trim().lowercase() + "|" + (item.year ?: "")
            val current = best[key]
            if (current == null || (item.rating ?: 0.0) > (current.rating ?: 0.0)) {
                best[key] = item
            }
        }
        return best.values.toList()
    }

    /** Parse a TMDB detail response (with external_ids + credits appended). */
    fun parseDetail(raw: String?): TmdbDetail? {
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
}
