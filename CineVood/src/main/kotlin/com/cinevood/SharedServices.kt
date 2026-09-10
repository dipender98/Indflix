package com.cinevood

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import kotlin.random.Random

/*
 * HTTP header helpers + the single-call TMDB JSON-API lookup.
 * TMDB enrichment is metadata-only: poster/backdrop/score/ids. If no API key
 * is configured the provider still works (site art is used instead).
 */

object SharedServices {

    // Fill with your own v3 key to enable TMDB backdrops/scores; the plugin
    // degrades gracefully when empty.
    const val TMDB_API_KEY = ""

    private val UAS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-A525F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"
    )

    fun userAgent(): String = UAS[Random.nextInt(UAS.size)]

    fun browserHeaders(referer: String? = null, json: Boolean = false): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent()
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["Accept"] = if (json) "application/json,*/*;q=0.7"
        else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["sec-ch-ua"] = "\"Chromium\";v=\"138\", \"Not=A?Brand\";v=\"24\""
        referer?.let { h["Referer"] = it }
        return h
    }

    data class TmdbInfo(
        val tmdbId: Int,
        val isMovie: Boolean,
        val poster: String?,
        val backdrop: String?,
        val rating10: Double?,  // TMDB vote_average (0..10)
        val votes: Int?,
        val year: Int?,
        val runtimeMinutes: Int?,
        val genres: List<String>
    )

    /** One TMDB request: IMDb find when possible, else movie/tv text search. */
    suspend fun tmdbLookup(imdbId: String?, name: String, year: Int?, isSeries: Boolean): TmdbInfo? {
        if (TMDB_API_KEY.isBlank()) return null
        return try {
            if (!imdbId.isNullOrBlank()) {
                val find = jsonGet(
                    "https://api.themoviedb.org/3/find/$imdbId" +
                        "?external_source=imdb_id&api_key=$TMDB_API_KEY"
                ) ?: return null
                val movie = find.optJSONArray("movie_results")
                val arr = if (movie != null && movie.length() > 0) movie
                else find.optJSONArray("tv_results")
                if (arr != null && arr.length() > 0) fromTmdb(arr.getJSONObject(0), movie != null && movie.length() > 0) else null
            } else {
                val media = if (isSeries) "tv" else "movie"
                val q = java.net.URLEncoder.encode(name, "UTF-8")
                val yy = year?.let { if (media == "movie") "&year=$it" else "&first_air_date_year=$it" } ?: ""
                val res = jsonGet(
                    "https://api.themoviedb.org/3/search/$media" +
                        "?query=$q$yy&api_key=$TMDB_API_KEY"
                ) ?: return null
                val arr = res.optJSONArray("results") ?: return null
                if (arr.length() == 0) null else fromTmdb(arr.getJSONObject(0), media == "movie")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fromTmdb(o: JSONObject, movie: Boolean): TmdbInfo {
        val genres = ArrayList<String>()
        o.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) g.optJSONObject(i)?.optString("name")?.let { genres.add(it) }
        }
        return TmdbInfo(
            tmdbId = o.optInt("id"),
            isMovie = movie,
            poster = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it },
            backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDB_IMG + it },
            rating10 = if (o.has("vote_average") && !o.isNull("vote_average"))
                o.getDouble("vote_average").takeIf { it > 0 } else null,
            votes = if (o.has("vote_count")) o.optInt("vote_count").takeIf { it > 0 } else null,
            year = (if (movie) o.optString("release_date") else o.optString("first_air_date"))
                .let { if (it.length >= 4) it.substring(0, 4).toIntOrNull() else null },
            runtimeMinutes = if (movie) o.optInt("runtime").takeIf { it > 0 } else null,
            genres = genres
        )
    }

    const val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private suspend fun jsonGet(url: String): JSONObject? = try {
        val text = app.get(url, headers = mapOf("User-Agent" to userAgent())).text
        if (text.isBlank()) null else JSONObject(text)
    } catch (e: Exception) {
        null
    }
}
