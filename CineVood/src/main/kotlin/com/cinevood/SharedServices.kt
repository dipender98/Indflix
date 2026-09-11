package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import kotlin.random.Random

/*
 * HTTP header helpers + the metadata pipeline (debug-plan F6).
 *
 * Chain: Cinemeta-by-IMDb (keyless, uses the post's own .mfx-imdb link)
 *        -> TMDB JSON API (only when a key is configured)
 *        -> null (caller falls back to site-only metadata).
 * Cinemeta has NO search resource (manifest: catalog|meta|addon_catalog), so
 * the IMDb id from the post is the only entry point; a missing id means
 * site-only metadata unless a TMDB key is set.
 */

object SharedServices {

    private const val TAG = "CineVood"

    // Fill with your own v3 key to enable TMDB as first-choice metadata; the
    // provider works fully without it (Cinemeta + site art).
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

    fun diag(msg: String) {
        runCatching { Log.d(TAG, msg) }
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

    data class MetadataInfo(
        val imdbId: String?,
        val tmdbId: Int?,
        val isMovie: Boolean,
        val poster: String?,
        val backdrop: String?,
        val plot: String?,
        val rating10: Double?,
        val year: Int?,
        val runtimeMinutes: Int?,
        val genres: List<String>
    )

    /**
     * Full chain: TMDB (when keyed) -> Cinemeta-by-IMDb -> null.
     * Cinemeta serves meta for the IMDb id in one call; unknown ids return an
     * empty object or 404 (both treated as a miss). The preferred type is
     * tried first, then the other (RC: old/TV ids can 404 on the wrong path).
     */
    suspend fun metadataLookup(
        imdbId: String?,
        name: String,
        year: Int?,
        isSeries: Boolean
    ): MetadataInfo? {
        if (!TMDB_API_KEY.isBlank()) {
            tmdbLookup(imdbId, name, year, isSeries)?.let { return it.asMetadata() }
        }
        if (imdbId.isNullOrBlank()) {
            diag("META no-imdb no-key site-only name=$name")
            return null
        }
        val preferred = if (isSeries) "tv" else "movie"
        val other = if (isSeries) "movie" else "tv"
        cinemetaMeta(imdbId, preferred)?.let { return it }
        cinemetaMeta(imdbId, other)?.let { return it }
        diag("META cinemeta miss imdb=$imdbId name=$name")
        return null
    }

    private fun TmdbInfo.asMetadata() = MetadataInfo(
        imdbId = null,
        tmdbId = tmdbId,
        isMovie = isMovie,
        poster = poster,
        backdrop = backdrop,
        plot = null,
        rating10 = rating10,
        year = year,
        runtimeMinutes = runtimeMinutes,
        genres = genres
    )

    private suspend fun cinemetaMeta(imdbId: String, type: String): MetadataInfo? {
        val text = try {
            val t = app.get(
                "https://v3-cinemeta.strem.io/meta/$type/$imdbId.json",
                headers = browserHeaders(json = true),
                timeout = 12
            ).text
            if (t.isBlank()) null else t
        } catch (e: Exception) {
            diag("META cinemeta EXC ${e.javaClass.simpleName} $type/$imdbId")
            null
        } ?: return null
        return parseCinemetaMeta(type, text).also {
            if (it == null) diag("META cinemeta miss $type/$imdbId")
        }
    }

    /** Pure: build MetadataInfo from a Cinemeta meta JSON body. */
    fun parseCinemetaMeta(type: String, jsonText: String): MetadataInfo? {
        val meta = try {
            JSONObject(jsonText).optJSONObject("meta")
        } catch (e: Exception) {
            null
        } ?: return null
        if (meta.length() == 0) return null

        val year = meta.optString("releaseInfo").let { r ->
            Regex("""\d{4}""").find(r)?.value?.toIntOrNull()
        } ?: meta.optString("year").toIntOrNull()
        val rating = meta.optString("imdbRating").toDoubleOrNull()
        val genres = ArrayList<String>()
        for (key in listOf("genres", "genre")) {
            meta.optJSONArray(key)?.let { g ->
                for (i in 0 until g.length()) g.optString(i).takeIf { it.isNotBlank() }
                    ?.let { if (!genres.contains(it)) genres.add(it) }
            }
        }
        val info = MetadataInfo(
            imdbId = meta.optString("imdb_id").ifBlank { null },
            tmdbId = meta.optInt("moviedb_id").takeIf { it > 0 },
            isMovie = type == "movie",
            poster = meta.optString("poster").ifBlank { null },
            backdrop = meta.optString("background").ifBlank { null },
            plot = meta.optString("description").ifBlank { null },
            rating10 = rating?.takeIf { it > 0 },
            year = year,
            runtimeMinutes = meta.optString("runtime").let { rt ->
                Regex("""(\d{2,4})""").find(rt)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 5..600 }
            },
            genres = genres
        )
        diag("META cinemeta hit $type/${info.imdbId} name=${info.plot?.length ?: 0}ch year=${info.year}")
        return info
    }

    /** TMDB lookup — used only when TMDB_API_KEY is configured. */
    suspend fun tmdbLookup(
        imdbId: String?,
        name: String,
        year: Int?,
        isSeries: Boolean
    ): TmdbInfo? {
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
