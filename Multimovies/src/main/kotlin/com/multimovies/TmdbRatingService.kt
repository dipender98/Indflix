package com.multimovies

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * TMDB rating-only service. Used ONLY when the user supplies a TMDB API key
 * in the provider settings. All other metadata is left to the keyless
 * CinemetaService. Returns null on any failure so callers fall back to Cinemeta.
 */
object TmdbRatingService {

    private const val BASE_URL = "https://api.themoviedb.org/3"

    suspend fun getRating(imdbId: String, type: String, apiKey: String): Score? =
        withContext(Dispatchers.IO) {
            try {
                // Resolve IMDB id -> TMDB id
                val findUrl = "$BASE_URL/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
                val findJson = Jsoup.connect(findUrl).ignoreContentType(true).timeout(15000).get().text()
                val find = tryParseJson<FindResponse>(findJson) ?: return@withContext null
                val tmdbId = if (type == "movie") {
                    find.movie_results.firstOrNull()?.id
                } else {
                    find.tv_results.firstOrNull()?.id
                } ?: return@withContext null

                // Fetch details for vote_average
                val detUrl = "$BASE_URL/$type/$tmdbId?api_key=$apiKey"
                val detJson = Jsoup.connect(detUrl).ignoreContentType(true).timeout(15000).get().text()
                val det = tryParseJson<DetailResponse>(detJson) ?: return@withContext null
                det.vote_average?.let { Score.from10(it) }
            } catch (e: Exception) {
                Log.e("TmdbRatingService", "Rating fetch failed: ${e.message}")
                null
            }
        }

    private data class FindResponse(
        val movie_results: List<IdHolder> = emptyList(),
        val tv_results: List<IdHolder> = emptyList(),
    )
    private data class IdHolder(val id: Int = 0)
    private data class DetailResponse(val vote_average: Double? = null)
}