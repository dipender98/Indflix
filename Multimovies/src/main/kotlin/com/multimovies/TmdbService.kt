package com.multimovies

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Cinemeta metadata service — KEYLESS (no API key required).
 *
 * Cinemeta is Stremio's public metadata API. Queried by IMDB id, returns cast
 * as a list of actor names (no headshots). Same approach as CSX (VegaMoviesProvider)
 * and most CloudStream extensions, so no personal TMDB key is needed.
 *
 * Endpoint: https://v3-cinemeta.strem.io/meta/{type}/{imdbId}.json  (type = "movie" | "series")
 *
 * Repurposed from the old TmdbService.kt (which used a keyed direct TMDB call).
 */
object CinemetaService {

    private const val META_URL = "https://v3-cinemeta.strem.io/meta"

    suspend fun getMetadata(imdbId: String, type: String): CinemetaMeta? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$META_URL/$type/$imdbId.json"
                val json = Jsoup.connect(url)
                    .ignoreContentType(true)
                    .timeout(15000)
                    .get()
                    .text()
                tryParseJson<CinemetaResponse>(json)?.meta
            } catch (e: Exception) {
                Log.e("CinemetaService", "Failed to fetch metadata for $imdbId: ${e.message}")
                null
            }
        }

    data class CinemetaResponse(val meta: CinemetaMeta)

    data class CinemetaMeta(
        val name: String? = null,
        val description: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val imdbRating: String? = null,
        val releaseInfo: String? = null,
        val year: String? = null,
        val genre: List<String>? = null,
        val genres: List<String>? = null,
        val cast: List<String>? = null,
        val director: List<String>? = null,
        val writer: List<String>? = null,
    )
}
