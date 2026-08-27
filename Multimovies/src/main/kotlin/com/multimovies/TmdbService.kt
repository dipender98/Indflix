package com.multimovies

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
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Read a JSON string field, returning null when blank (avoids platform-type quirks). */
private fun str(obj: JSONObject, key: String): String? {
    val v: String = obj.optString(key)
    return if (v.isBlank()) null else v
}

/**
 * TMDB/SIMKL metadata engine for the Multimovies provider.
 *
 * Search is the only SIMKL-backed path: when [SIMKL_CLIENT_ID] is non-blank,
 * `search()` queries the SIMKL search API (which returns posters, ratings, years
 * and TMDB/IMDB ids); otherwise it uses the TMDB `/search/multi` endpoint. Detail
 * and episode metadata ALWAYS come from TMDB (using the tmdb id SIMKL returned),
 * so no SIMKL metadata endpoints are required.
 *
 * The API keys are embedded because the pinned CloudStream library exposes no
 * runtime access to user-entered TMDB/SIMKL keys (MainAPI has no settings hook).
 */
object TmdbService {

    private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val SIMKL_CLIENT_ID = ""
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val SIMKL_API = "https://api.simkl.com"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

    /** Cache of fetched detail metadata, keyed "tmdbId|type". */
    private val detailCache = ConcurrentHashMap<String, TmdbDetail>()
    /** Cache of imdb-id -> (tmdbId, type) lookups. */
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()

    /** One search hit (movie or series) with everything CloudStream needs to render
     *  a result row — rating and poster are inline in the search payload. */
    data class TmdbItem(
        val tmdbId: Int?,
        val imdbId: String?,
        val type: String,
        val name: String,
        val year: String?,
        val poster: String?,
        val rating: Double?,
    )

    /** Full metadata for a detail page, sourced from TMDB. */
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

    /** Per-episode metadata used to enrich TV detail pages. */
    data class TmdbEpisode(
        val name: String? = null,
        val overview: String? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val rating: Double? = null,
    )

    /** Search movies + series. SIMKL takes priority when its client_id is set. */
    suspend fun search(query: String): List<TmdbItem> {
        if (query.isBlank()) return emptyList()
        return if (SIMKL_CLIENT_ID.isNotBlank()) searchSimkl(query) else searchTmdb(query)
    }

    /** Fetch full TMDB metadata for [tmdbId] of [type] ("movie"|"series"). */
    suspend fun fetchMeta(tmdbId: Int, type: String): TmdbDetail? {
        if (tmdbId <= 0) return null
        val cacheKey = "$tmdbId|$type"
        detailCache[cacheKey]?.let { return it }
        val path = if (type == "movie") "movie" else "tv"
        val url = "$TMDB_API/$path/$tmdbId?api_key=$TMDB_API_KEY&language=en-US&append_to_response=external_ids,credits"
        val detail = runCatching { parseTmdbDetail(app.get(url, timeout = 6).text, type) }.getOrNull()
        if (detail != null) detailCache[cacheKey] = detail
        return detail
    }

    /** Resolve an IMDB id to (tmdbId, type) via TMDB's find endpoint. */
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

    /** Fetch TMDB episode metadata for the given [seasons] of [tmdbId], in
     *  parallel with bounded concurrency and a short per-call cap. */
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
            }.awaitAll().filterNotNull().flatten().associate { (s, e, meta) -> (s to e) to meta }
        }
    }

    // ------------------------------------------------------------------
    // Search backends
    // ------------------------------------------------------------------

    private suspend fun searchTmdb(query: String): List<TmdbItem> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get(
                "$TMDB_API/search/multi?api_key=$TMDB_API_KEY&query=$encoded&language=en-US&include_adult=false&page=1",
                timeout = 5,
            ).text
        }.getOrNull() ?: return emptyList()
        return parseTmdbMultiSearch(json)
    }

    private suspend fun searchSimkl(query: String): List<TmdbItem> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = runCatching {
            app.get("$SIMKL_API/search/simkl?q=$encoded&client_id=$SIMKL_CLIENT_ID", timeout = 5).text
        }.getOrNull() ?: return emptyList()
        val items = parseSimklSearch(json)
        if (items.isEmpty()) return emptyList()
        // Hits without a tmdb id are resolved via TMDB /find (parallel, capped);
        // still-unresolved hits are dropped (rare).
        return coroutineScope {
            items.map { item ->
                async {
                    if (item.tmdbId != null) item
                    else item.imdbId?.let { imdb ->
                        withTimeoutOrNull(1300L) { findByImdb(imdb) }
                    }?.let { (tmdbId, type) -> item.copy(tmdbId = tmdbId, type = type) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Parse a TMDB `/search/multi` response into [TmdbItem]s (movies + series only). */
    fun parseTmdbMultiSearch(raw: String?): List<TmdbItem> {
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
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Parse a SIMKL `/search/simkl` response (JSON array) into [TmdbItem]s. */
    fun parseSimklSearch(raw: String?): List<TmdbItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = when (m.optString("type")) {
                    "movie" -> "movie"
                    "show" -> "series"
                    else -> return@mapNotNull null
                }
                val name = str(m, "title") ?: return@mapNotNull null
                val ids = m.optJSONObject("ids")
                val ratings = m.optJSONObject("ratings")
                val rating = ratings?.optJSONObject("imdb")?.optDouble("rating", -1.0)?.takeIf { it > 0 }
                    ?: ratings?.optJSONObject("simkl")?.optDouble("rating", -1.0)?.takeIf { it > 0 }
                TmdbItem(
                    tmdbId = ids?.optInt("tmdb", -1)?.takeIf { it > 0 },
                    imdbId = ids?.let { str(it, "imdb") },
                    type = type,
                    name = name,
                    year = str(m, "year")?.take(4),
                    poster = str(m, "poster"),
                    rating = rating,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Parse a TMDB detail response (with `external_ids` + `credits` appended). */
    fun parseTmdbDetail(raw: String?, type: String): TmdbDetail? {
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

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<Triple<Int, Int, TmdbEpisode>> {
        val url = "$TMDB_API/tv/$tmdbId/season/$season?api_key=$TMDB_API_KEY&language=en-US"
        return runCatching {
            val root = JSONObject(app.get(url, timeout = 5).text)
            root.optJSONArray("episodes")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val ep = e.optInt("episode_number", -1)
                    if (ep <= 0) return@mapNotNull null
                    Triple(
                        season,
                        ep,
                        TmdbEpisode(
                            name = str(e, "name"),
                            overview = str(e, "overview"),
                            released = str(e, "air_date"),
                            thumbnail = str(e, "still_path")?.let { "$IMG_BASE$it" },
                            rating = e.optDouble("vote_average", -1.0).takeIf { it > 0 },
                        ),
                    )
                }
            } ?: emptyList()
        }.getOrNull() ?: emptyList()
    }

    /** Extract the IMDB "tt…" id from a Multimovies/Dooplay detail page. */
    fun extractImdbId(doc: Document): String? {
        // 1. Open Graph meta tag: <meta property="og:imdb_id" content="tt...">
        doc.selectFirst("meta[property=\"og:imdb_id\"]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { return normalizeImdb(it) }

        // 2. IMDB links anywhere on the page
        doc.select("a[href*='imdb.com/title/'], a[href*='/title/tt']").firstOrNull()
            ?.attr("href")
            ?.let { normalizeImdb(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 3. Dooplay-specific containers (common patterns)
        doc.select("div.imdb a, span.imdb a, li.imdb a, .imdb-link a, .imdbRating a, [class*='imdb'] a")
            .firstOrNull()
            ?.attr("href")
            ?.let { normalizeImdb(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // 4. Data attributes (some themes use data-imdb / data-imdb-id / data-imdbid)
        doc.select("[data-imdb], [data-imdb-id], [data-imdbid], [data-imdb_id]").firstOrNull()?.let { el ->
            listOf("data-imdb", "data-imdb-id", "data-imdbid", "data-imdb_id").forEach { attr ->
                el.attr(attr).takeIf { it.isNotBlank() }?.let { return normalizeImdb(it) }
            }
        }

        // 5. Script tags with JSON-LD or embedded data
        doc.select("script[type=\"application/ld+json\"]").forEach { script ->
            val text = script.html()
            val m = Regex("""\"@id\"\s*:\s*\"https?://(?:www\.)?imdb\.com/title/(tt\d+)\"""").find(text)
                ?: Regex("""tt\d{7,8}""").find(text)
            m?.value?.let { return normalizeImdb(it) }
        }

        // 6. Inline JavaScript variables: imdb_id = "tt...", "imdb": "tt...", etc.
        doc.select("script").forEach { script ->
            val text = script.html()
            val m = Regex("""imdb[_\s]*id\s*[=:]\s*['"](tt\d+)['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""['"](?:imdb|imdb_id|imdbId|imdbid)['"]\s*:\s*['"](tt\d+)['"]""", RegexOption.IGNORE_CASE).find(text)
            m?.groupValues?.getOrNull(1)?.let { return it }
        }

        return null
    }

    private fun normalizeImdb(value: String): String {
        val m = Regex("""tt\d{7,8}""").find(value)
        return m?.value ?: value
    }

    /** Extract a TMDB numeric id from the page (used when a main-page card tap
     *  doesn't carry a TMDB search URL, so the id must be scraped from the
     *  Multimovies detail page itself). Checks data-* attributes, JSON-LD
     *  sameAs/@id links and inline JS vars. Returns null when absent. */
    fun extractTmdbId(doc: Document): String? {
        // 1. Data attributes
        doc.select("[data-tmdb], [data-tmdb-id], [data-tmdbid], [data-tmdb_id]").firstOrNull()?.let { el ->
            listOf("data-tmdb", "data-tmdb-id", "data-tmdbid", "data-tmdb_id").forEach { attr ->
                el.attr(attr).takeIf { it.isNotBlank() }?.let { return it.trim() }
            }
        }

        // 2. JSON-LD / scripts referencing themoviedb.org or inline tmdb vars
        doc.select("script").forEach { script ->
            val text = script.html()
            Regex("""https?://(?:www\.)?themoviedb\.org/(?:movie|tv)/(\d+)""")
                .find(text)?.groupValues?.get(1)?.let { return it }
            Regex("""["']?(?:tmdb|tmdbId|tmdb_id|tmdbid)["']?\s*[=:]\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}
