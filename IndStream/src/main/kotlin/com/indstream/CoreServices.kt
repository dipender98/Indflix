package com.indstream
/** FILE: CoreServices. kt â€” shared primitives. - shared HTTP helpers (speed probing, common headers). - TMDB search /. metadata / season data. - HLS. */

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/** Lean HTTP helpers for the module. Shares the CloudStream app client (with its cookie jar) but keeps -specific. timeouts and header logic in one place. */
object HttpKit {

    const val DEFAULT_TIMEOUT = 12L
    const val SHORT_TIMEOUT = 6L
    const val PLAYBACK_TIMEOUT = 15L

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    val commonHeaders = mapOf("User-Agent" to userAgent)

    /** GET with timeout. Returns null on any error. */
    suspend fun get(url: String, timeout: Long = DEFAULT_TIMEOUT): String? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching { app.get(url, timeout = timeout, headers = commonHeaders).text }.getOrNull()
        }
    }

    /** GET with referer. */
    suspend fun getWithReferer(url: String, referer: String, timeout: Long = DEFAULT_TIMEOUT): String? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching {
                app.get(url, timeout = timeout, headers = commonHeaders + mapOf("Referer" to referer)).text
            }.getOrNull()
        }
    }

    /** GET returning a parsed Jsoup Document. */
    suspend fun getDocument(url: String, timeout: Long = DEFAULT_TIMEOUT): Document? {
        return withTimeoutOrNull(timeout * 1000L) {
            runCatching { app.get(url, timeout = timeout, headers = commonHeaders).document }.getOrNull()
        }
    }

    /** GET returning parsed JSON Object. */
    suspend fun getJson(url: String, timeout: Long = DEFAULT_TIMEOUT): JSONObject? {
        val text = get(url, timeout) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** GET returning parsed JSON Array. */
    suspend fun getJsonArray(url: String, timeout: Long = DEFAULT_TIMEOUT): JSONArray? {
        val text = get(url, timeout) ?: return null
        return runCatching { JSONArray(text) }.getOrNull()
    }

    /** Resolve a possibly-relative URL against a base. */
    fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val schemeHost = Regex("""^https?://[^/]+""").find(base)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /** Measure approximate throughput of a stream URL via ranged GET (first 128KB). */
    suspend fun probeSpeed(url: String, referer: String? = null): Long? {
        return withTimeoutOrNull(5000L) {
            runCatching {
                val start = System.currentTimeMillis()
                val headers = mutableMapOf("User-Agent" to userAgent, "Range" to "bytes=0-131071")
                if (!referer.isNullOrBlank()) headers["Referer"] = referer
                val resp = app.get(url, timeout = 5, headers = headers)
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 1) return@runCatching null
                val bytes = resp.text.length.coerceAtLeast(1)
                // throughput in KB/s.
                (bytes * 1000L) / (elapsed * 1024L)
            }.getOrNull()
        }
    }

    /** Cheap liveness check for REPLAYED (possibly stale/expired) stream URLs: one tiny ranged request. Returns TRUE (alive. 2xx/3xx/416), FALSE (dead. */
    suspend fun aliveCheck(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = 2,
    ): Boolean? = withTimeoutOrNull((timeoutSec + 1) * 1000L) {
        runCatching {
            val headers = LinkedHashMap<String, String>().apply {
                put("User-Agent", userAgent)
                putAll(extraHeaders)
                if (!referer.isNullOrBlank() && !containsKey("Referer")) put("Referer", referer)
                put("Range", "bytes=0-0")
            }
            val code = app.get(url, timeout = timeoutSec, headers = headers).code
            code in 200..399 || code == 416
        }.getOrNull()
    }

    /** Measure the REAL pixel height of a direct (non-HLS) media file by parsing its ISO-BMFF (MP4/MOV) container. Fetches. the `moov` box - front for. */
    suspend fun resolveHeight(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Int {
        if (url.isBlank() || url.contains(".m3u8", ignoreCase = true)) return 0
        val hdrs = LinkedHashMap<String, String>().apply {
            put("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) put("Referer", referer)
            putAll(extraHeaders)
        }
        val front = fetchRange(url, "0-262143", hdrs)
        val moov = front?.let { findMoov(it) } ?: run {
            val total = contentLength(url, hdrs) ?: return 0
            if (total <= 262_144) return 0
            val start = (total - 524_288).coerceAtLeast(0)
            fetchRange(url, "$start-${total - 1}", hdrs)?.let { findMoov(it) } ?: return 0
        }
        return heightFromMoov(moov)
    }

    /** Ranged GET returning raw bytes, or null on any failure/timeout. */
    private suspend fun fetchRange(url: String, range: String, hdrs: Map<String, String>): ByteArray? {
        return withTimeoutOrNull(3000L) {
            runCatching {
                app.get(url, timeout = 3, headers = hdrs + mapOf("Range" to "bytes=$range"))
                    .body.bytes()
            }.getOrNull()
        }
    }

    /** Total file size (bytes) via a 0-byte ranged GET, or null. */
    private suspend fun contentLength(url: String, hdrs: Map<String, String>): Long? {
        return withTimeoutOrNull(3000L) {
            runCatching {
                val r = app.get(url, timeout = 3, headers = hdrs + mapOf("Range" to "bytes=0-0"))
                val cr = r.headers["Content-Range"]
                cr?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.headers["Content-Length"]?.toLongOrNull()
            }.getOrNull()
        }
    }

    /** Locate the `moov` top-level box bytes, or null. */
    private fun findMoov(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 8 <= data.size) {
            val size = read32(data, i)
            if (size < 8 || i + size > data.size) return null
            if (String(data, i + 4, 4, Charsets.ISO_8859_1) == "moov") return data.copyOfRange(i, i + size)
            i += size
        }
        return null
    }

    /** First non-zero video track size, ladder-normalized. */
    private fun heightFromMoov(moov: ByteArray): Int {
        fun walk(start: Int, end: Int): Int {
            var i = start
            while (i + 8 <= end) {
                val size = read32(moov, i)
                if (size < 8 || i + size > end) return 0
                val type = String(moov, i + 4, 4, Charsets.ISO_8859_1)
                if (type == "tkhd") {
                    val (w, h) = parseTkhdSize(moov, i, size)
                    if (h > 0) return ManifestKit.normalizeHeight(w, h)
                } else if (type == "trak" || type == "mdia" || type == "minf" || type == "stbl") {
                    val h = walk(i + 8, i + size)
                    if (h > 0) return h
                }
                i += size
            }
            return 0
        }
        return walk(0, moov.size)
    }

    /** Width + height (px), version 1 → +96; top 16 bits of 16.16. */
    private fun parseTkhdSize(b: ByteArray, off: Int, size: Int): Pair<Int, Int> {
        if (size < 12) return 0 to 0
        val version = b[off + 8].toInt() and 0xFF
        val base = if (version == 1) 96 else 84
        if (off + base + 8 > b.size) return 0 to 0
        fun fixed(at: Int): Int {
            val raw = read32(b, at).toLong() and 0xFFFFFFFFL
            return (raw ushr 16).toInt().coerceAtLeast(0)
        }
        return fixed(off + base) to fixed(off + base + 4)
    }

    /** Big-endian uint32. */
    private fun read32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}

/** TMDB metadata engine for. Search, detail, episodes, IMDB→TMDB lookup. */
object TmdbService {

    private const val API_KEY_PRIMARY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val API_KEY_FALLBACK = "a721dd910292becd0d78ed436463db21"
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    private const val LOG_TAG = "IndStream"

    /** Search-result cache TTL / bounds. Results don't change minute-to-minute; a hit makes history re-clicks instant and. immune to upstream blips. */
    private const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1000L
    private const val SEARCH_CACHE_MAX = 64
    /** Cap on riding someone else's in-flight search before giving up. Sized for a primary→fallback key retry. */
    private const val SEARCH_INFLIGHT_WAIT_MS = 11_000L

    private val detailCache = ConcurrentHashMap<String, TmdbDetail>()
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()

    /** One search hit (movie or series). */
    data class TmdbItem(
        val tmdbId: Int?,
        val imdbId: String?,
        val type: String,      // "movie" or "series".
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
        /** TMDB "original_language" code ("hi", "ja", "en", …). Powers the (original) tag on emitted server names - a stream. whose audio label is "Original". */
        val originalLanguage: String? = null,
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

    /** Search movies + series via TMDB /search/multi - cached + deduplicated. All three plugins share the TMDB key set. */
    suspend fun search(query: String): List<TmdbItem> {
        if (query.isBlank()) return emptyList()
        val key = query.trim().lowercase()
        searchCache[key]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.items
            searchCache.remove(key)
        }
        // putIfAbsent: first caller LEADS, everyone else rides its request. The leader runs on, so it still completes (and.
// fills the cache) even if the app.
        val mine = searchScope.async {
            val found = searchRemote(query)
            if (found.isNotEmpty()) {
                if (searchCache.size >= SEARCH_CACHE_MAX) {
                    val oldest = searchCache.entries.minByOrNull { e -> e.value.expiresAt }
                    oldest?.let { e -> searchCache.remove(e.key) }
                }
                searchCache[key] = SearchEntry(found, System.currentTimeMillis() + SEARCH_CACHE_TTL_MS)
            }
            found
        }
        val existing = searchInFlight.putIfAbsent(key, mine)
        if (existing == null) {
            mine.invokeOnCompletion { searchInFlight.remove(key, mine) }
        } else {
            mine.cancel()
        }
        val job = existing ?: mine
        return withTimeoutOrNull(SEARCH_INFLIGHT_WAIT_MS) { runCatching { job.await() }.getOrNull() }
            ?: emptyList()
    }

    /** The /search/multi round-trip; never throws — key trips/rate-limits are logged inside tmdbGet and arrive as an empty list. */
    private suspend fun searchRemote(query: String): List<TmdbItem> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = tmdbGet(
            "search q='$query'",
            "/search/multi",
            "query=$encoded&language=en-US&include_adult=false&page=1",
        ) ?: return emptyList()
        return parseTmdbMultiSearch(json)
    }

    /** Decide whether to fall through to the next key. Network failure (handled separately as responded==null),
     * HTTP 401/403/429/5xx, or a 2xx body whose JSON reports a *key* failure (`success=false` with one of the
     * TMDB invalid/suspended/`rate-limit exceeded` status codes — see [tmdbKeyTripCode]). A 400/404 or a 200
     * that isn't a key trip is NOT retried with a second key — those are resource-level answers callers already
     * treat as empty/no-hit. */
    internal fun shouldTryNextKey(httpCode: Int, tmdbTripStatus: Int?): Boolean =
        tmdbTripStatus != null ||
            httpCode == 401 ||
            httpCode == 403 ||
            httpCode == 429 ||
            httpCode >= 500

    /** GET a TMDB endpoint, falling back primary→second key on a tripped/rate-limited/broken key.
     * Logs the reason for every rejected key, and never logs the keys themselves.
     * Returns the 2xx body on success, a genuine non-key error body untouched (so callers keep their
     * prior empty-parse behavior for e.g. 404), or null once both keys are exhausted. */
    private suspend fun tmdbGet(op: String, path: String, query: String, timeout: Long = 5): String? {
        // Personal key first when the user saved one in Settings.
        val keys = buildList {
            Settings.tmdbApiKey()?.let { add("personal" to it) }
            add("primary" to API_KEY_PRIMARY)
            add("fallback" to API_KEY_FALLBACK)
        }
        var lastReason = "no keys configured"
        for ((index, pair) in keys.withIndex()) {
            val (label, key) = pair
            val url = "$API$path?api_key=$key&$query"
            val responded: Pair<Int, String>? = runCatching {
                val r = app.get(url, timeout = timeout)
                r.code to r.text
            }.getOrElse { t ->
                lastReason = "$label NET-FAIL(${t.javaClass.simpleName}: ${(t.message ?: "").take(120)})"
                android.util.Log.w(LOG_TAG, "TMDB $op $lastReason${fallingBack(index, keys)}")
                null
            }
            if (responded == null) continue
            val (code, body) = responded
            val trip = if (code in 200..299) tmdbKeyTripCode(body) else null
            if (shouldTryNextKey(code, trip)) {
                lastReason = "$label HTTP $code${trip?.let { " tmdb_status=$it" } ?: ""}: ${safeSnippet(body)}"
                android.util.Log.w(LOG_TAG, "TMDB $op API-KEY-LIMITED $lastReason${fallingBack(index, keys)}")
                continue
            }
            if (code in 200..299) return body
            // Resource-level HTTP error (400/404/…): not a key issue, so don't burn the second key. Surface the
            // reason and hand the body to the caller, which parses it to an empty result just as before.
            android.util.Log.w(LOG_TAG, "TMDB $op $label HTTP $code (not a key issue: no fallback): ${safeSnippet(body)}")
            return body
        }
        android.util.Log.e(LOG_TAG, "TMDB $op ABANDONED after ${keys.size} keys: $lastReason")
        return null
    }

    private fun fallingBack(index: Int, keys: List<Pair<String, String>>): String =
        if (index < keys.lastIndex) " → trying ${keys[index + 1].first}" else " → no keys left"

    /** Lightweight key check for Settings Verify: /configuration answers 200 for a valid v3 key. Never throws. */
    suspend fun testTmdbKey(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty()) return "Enter a key first"
        if (key.startsWith("eyJ")) return "That's the Read Access Token - paste the API Key (v3)"
        return runCatching {
            val r = app.get("$API/configuration?api_key=$key", timeout = 8)
            when (r.code) {
                in 200..299 -> "Key works - TMDB connected"
                401 -> "Key rejected (401) - check the API Key (v3)"
                else -> "TMDB error (${r.code})"
            }
        }.getOrDefault("Verify failed - no network?")
    }

    /** If the 2xx error body carries `success:false` with a *key*-level TMDB status code (7 invalid, 10 suspended,
     * 30 rate-limit exceeded) return that code; otherwise null (the response is either a valid 2xx payload or
     * some other error the caller can treat as an empty result). */
    internal fun tmdbKeyTripCode(body: String): Int? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optBoolean("success", true)) return null
        return when (val sc = root.optInt("status_code", 0)) {
            7, 10, 30 -> sc
            else -> null
        }
    }

    private fun safeSnippet(s: String): String {
        var out = s.filter { !it.isISOControl() }
            .replace(API_KEY_PRIMARY, "***")
            .replace(API_KEY_FALLBACK, "***")
        Settings.tmdbApiKey()?.let { out = out.replace(it, "***") }
        return out.take(200).trim()
    }

    private data class SearchEntry(val items: List<TmdbItem>, val expiresAt: Long)

    private val searchCache = ConcurrentHashMap<String, SearchEntry>()
    private val searchInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<TmdbItem>>>()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Trending this week - powers the home page. is "movie" or "tv". */
    suspend fun trending(type: String, page: Int = 1): List<TmdbItem> {
        val json = tmdbGet("trending $type", "/trending/$type/week", "language=en-US&page=$page")
            ?: return emptyList()
        return parseResults(json, type)
    }

    /** Popular titles - extra home page row. is "movie" or "tv". */
    suspend fun popular(type: String, page: Int = 1): List<TmdbItem> {
        val json = tmdbGet("popular $type", "/$type/popular", "language=en-US&page=$page")
            ?: return emptyList()
        return parseResults(json, type)
    }

    /** Season numbers for a TV show (excludes specials/season 0). */
    suspend fun fetchTvSeasons(tmdbId: Int): List<Int> {
        if (tmdbId <= 0) return emptyList()
        val json = tmdbGet("seasons tv=$tmdbId", "/tv/$tmdbId", "language=en-US") ?: return emptyList()
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

    /** Fetch full TMDB metadata for of ("movie"|"series"). */
    suspend fun fetchMeta(tmdbId: Int, type: String): TmdbDetail? {
        if (tmdbId <= 0) return null
        val cacheKey = "$tmdbId|$type"
        detailCache[cacheKey]?.let { return it }
        val path = if (type == "movie") "movie" else "tv"
        val json = tmdbGet(
            "meta $path=$tmdbId",
            "/$path/$tmdbId",
            "language=en-US&append_to_response=external_ids,credits",
        )
        val detail = json?.let { runCatching { parseTmdbDetail(it, type) }.getOrNull() }
        if (detail != null) detailCache[cacheKey] = detail
        return detail
    }

    /** Resolve an IMDB id to (tmdbId, type) via TMDB's find endpoint. */
    suspend fun findByImdb(imdbId: String): Pair<Int, String>? {
        if (!imdbId.startsWith("tt")) return null
        imdbFindCache[imdbId]?.let { return it }
        val json = tmdbGet("find imdb=$imdbId", "/find/$imdbId", "external_source=imdb_id&language=en-US")
        val result = if (json != null) runCatching {
            val root = JSONObject(json)
            val movie = root.optJSONArray("movie_results")?.optJSONObject(0)
            val tv = root.optJSONArray("tv_results")?.optJSONObject(0)
            when {
                movie != null -> movie.optInt("id", -1).takeIf { it > 0 }?.let { it to "movie" }
                tv != null -> tv.optInt("id", -1).takeIf { it > 0 }?.let { it to "series" }
                else -> null
            }
        }.getOrNull() else null
        if (result != null) imdbFindCache[imdbId] = result
        return result
    }

    /** Fetch TMDB episode metadata for the given of, in parallel. Returns keyed (season, episode) -> metadata. */
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

    // Internal helpers ------------------------------------------------------------------ Collapse search hits sharing a.
// normalized (title, year). TMDB.
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

    /** Parse a TMDB `/search/multi` response, deduplicated by (title, year). */
    internal fun parseTmdbMultiSearch(json: String): List<TmdbItem> {
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
                    imdbId = null, // IMDB id not in search results; fetch detail if needed.
                    type = type,
                    name = name,
                    year = (str(r, "release_date") ?: str(r, "first_air_date"))?.take(4),
                    poster = str(r, "poster_path")?.let { "$IMG_BASE$it" },
                    rating = r.optDouble("vote_average", -1.0).takeIf { it > 0 },
                )
            }.dedupedByTitle()
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
                originalLanguage = m.optString("original_language").takeIf { it.isNotBlank() && it != "null" },
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
        val json = tmdbGet("season tv=$tmdbId s=$season", "/tv/$tmdbId/season/$season", "language=en-US")
            ?: return null
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

    /** Parse a TMDB results array into TmdbItems. is "movie" or "tv". */
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

/** Pure parsers for HLS master playlists and DASH MPDs. No network, no Android, no CloudStream dependency - safe for. JVM unit tests. */
object ManifestKit {

    /** Resolve a possibly-relative URL against a base. Pure (no network). */
    fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val schemeHost = Regex("""^https?://[^/]+""").find(base)?.value ?: return path
        return if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    /** One video variant in an HLS master playlist. */
    data class Variant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
        val audioGroup: String? = null,
        val subtitlesGroup: String? = null,
        val width: Int = 0,
    )

    /** One EXT-X-MEDIA rendition (audio or subtitles). */
    data class MediaRendition(
        val type: String,       // "AUDIO" | "SUBTITLES".
        val groupId: String,
        val name: String,
        val language: String?,
        val uri: String?,
        val forced: Boolean = false,
        val default: Boolean = false,
    )

    /** Parsed master playlist. */
    data class MasterPlaylist(
        val variants: List<Variant>,
        val audio: List<MediaRendition>,
        val subtitles: List<MediaRendition>,
    ) {
        val isMultiAudio: Boolean get() = audio.map { it.groupId }.distinct().size > 1 ||
            audio.map { it.language }.filterNotNull().distinct().size > 1
        val hasSubtitles: Boolean get() = subtitles.isNotEmpty()
    }

    /** Parse an HLS master playlist text. Returns null if no variants found. */
    fun parseMaster(text: String?, baseUrl: String = ""): MasterPlaylist? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
        val variants = mutableListOf<Variant>()
        val media = mutableListOf<MediaRendition>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    parseMediaTag(line)?.let { media.add(it) }
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttrs(line.removePrefix("#EXT-X-STREAM-INF:"))
                    val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    if (uri != null) {
                        // Scope crops (1920x800/920) are still 1080p-class.
                        val (w, h) = parseResolution(attrs["RESOLUTION"])
                        variants.add(
                            Variant(
                                url = resolveUrl(baseUrl, uri),
                                height = h,
                                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                                codecs = attrs["CODECS"],
                                audioGroup = attrs["AUDIO"],
                                subtitlesGroup = attrs["SUBTITLES"],
                                width = w,
                            )
                        )
                    }
                    i++ // consume the URI line.
                }
            }
            i++
        }

        return if (variants.isEmpty() && media.isEmpty()) null
        else MasterPlaylist(
            variants = variants,
            audio = media.filter { it.type == "AUDIO" },
            subtitles = media.filter { it.type == "SUBTITLES" },
        )
    }

    private fun parseMediaTag(attrStr: String): MediaRendition? {
        val attrs = parseAttrs(attrStr)
        val type = attrs["TYPE"] ?: return null
        val groupId = attrs["GROUP-ID"] ?: return null
        val name = attrs["NAME"] ?: return null
        return MediaRendition(
            type = type,
            groupId = groupId,
            name = name,
            language = attrs["LANGUAGE"],
            uri = attrs["URI"],
            forced = attrs["FORCED"] == "YES",
            default = attrs["DEFAULT"] == "YES",
        )
    }

    /** Parse `KEY=VALUE, KEY2="VALUE2"` attr lists (values may be quoted). */
    fun parseAttrs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val regex = Regex("""([A-Za-z0-9-]+)=("([^"]*)"|[^,\s]*)""")
        regex.findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[3].ifEmpty { m.groupValues[2] }
            out[key] = value
        }
        return out
    }

    /** True if the playlist is a master (multi-variant) rather than a media playlist. */
    fun isMaster(text: String?): Boolean = text != null && text.contains("#EXT-X-STREAM-INF")

    /** One representation in a DASH MPD. */
    data class Representation(
        val id: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
        val width: Int = 0,
    )

    /** Parse a DASH MPD text into video representations. */
    fun parseMpd(text: String?): List<Representation> {
        if (text.isNullOrBlank()) return emptyList()
        val reps = mutableListOf<Representation>()

        // Iterate AdaptationSets; mimeType lives on the set, not the Representation.
        val setRegex = Regex("""<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>""", RegexOption.DOT_MATCHES_ALL)
        setRegex.findAll(text).forEach { setMatch ->
            val setAttrs = parseXmlAttrs(setMatch.groupValues[1])
            val setMime = setAttrs["mimeType"] ?: ""
            if (!setMime.contains("video", ignoreCase = true)) return@forEach
            val repRegex = Regex("""<Representation\b([^>]*?)/?>""")
            repRegex.findAll(setMatch.groupValues[2]).forEach { repMatch ->
                val attrs = parseXmlAttrs(repMatch.groupValues[1])
                val height = attrs["height"]?.toIntOrNull() ?: 0
                reps.add(
                    Representation(
                        id = attrs["id"] ?: "",
                        height = height,
                        bandwidth = attrs["bandwidth"]?.toLongOrNull() ?: 0L,
                        codecs = attrs["codecs"],
                        width = attrs["width"]?.toIntOrNull() ?: 0,
                    )
                )
            }
        }
        return reps
    }

    private fun parseXmlAttrs(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val regex = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")
        regex.findAll(raw).forEach { m -> out[m.groupValues[1]] = m.groupValues[2] }
        return out
    }

    /** Deterministic identity key for dedup: host + normalized path. */
    fun urlKey(url: String): String {
        return url
            .lowercase()
            .replace(Regex("https?://"), "")
            .substringBefore("?")
            .trimEnd('/')
    }

    /** Rank variants by quality (height desc), used to label links. */
    fun bestHeight(variants: List<Variant>): Int =
        variants.maxOfOrNull { normalizeHeight(it.width, it.height) } ?: 0

    /** "1920x800" -> (1920, 800); missing/garbled -> (0, 0). */
    fun parseResolution(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank()) return 0 to 0
        val nums = Regex("""\d+""").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.size < 2) return 0 to 0
        return nums[0] to nums[1]
    }

    /** Scope crops carry full width but cut height, so ladder by width. */
    fun normalizeHeight(width: Int, height: Int): Int {
        if (width <= 0) return height.coerceAtLeast(0)
        val wide = ((width * 9 + 8) / 16).coerceAtLeast(0)
        return max(height, wide)
    }

    /** Peak video height of a manifest text, HLS master or DASH MPD alike. Dispatches on content (`<MPD` →, else) so. adaptive links (HLS m3u8 AND DASH. */
    fun bestHeightOf(text: String?, url: String): Int {
        if (text.isNullOrBlank()) return 0
        return if (text.contains("<MPD", ignoreCase = true)) {
            parseMpd(text).maxOfOrNull { normalizeHeight(it.width, it.height) } ?: 0
        } else {
            parseMaster(text, url)?.let { bestHeight(it.variants) } ?: 0
        }
    }

    /** Human label for a height: "4K"/"1080p"/"720p"/"480p"/"Auto". */
    fun qualityLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        else -> "Auto"
    }

    /** Max of two, but treats 0 (unknown) as -inf so known quality wins. */
    fun maxQuality(a: Int, b: Int): Int = if (a <= 0) b else if (b <= 0) a else max(a, b)

    /** Best resolution token (height) embedded in a URL/filename like ". . . /1080p/. . . " → 1080, else 0. */
    fun resolutionFromUrl(url: String?): Int {
        if (url.isNullOrBlank()) return 0
        return Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE).findAll(url).maxOfOrNull {
            it.groupValues[1].toIntOrNull() ?: 0
        } ?: 0
    }

    // ── Language detection ────────────────────────────────── Language codes we know.
    private val LANG_HINDI = setOf("hi", "hin")
    private val LANG_ENGLISH = setOf("en", "eng")

    /** True if a rendition's language is Hindi (handles hi, hin, hi-IN, hindi, हिन्दी). */
    fun isHindi(rendition: MediaRendition): Boolean {
        val lang = rendition.language?.lowercase()
        if (lang != null && (lang in LANG_HINDI || lang.startsWith("hi"))) return true
        return rendition.name.contains("hindi", ignoreCase = true) ||
            rendition.name.contains("हिन्दी", ignoreCase = true) ||
            rendition.name.contains("हिंदी", ignoreCase = true)
    }

    /** True if a rendition's language is English (handles en, eng, en-US, english). */
    fun isEnglish(rendition: MediaRendition): Boolean {
        val lang = rendition.language?.lowercase()
        if (lang != null && (lang in LANG_ENGLISH || lang.startsWith("en"))) return true
        return rendition.name.contains("english", ignoreCase = true)
    }

    /** True if a master playlist has at least Hindi + English audio tracks. */
    fun hasHindiEnglishAudio(master: MasterPlaylist): Boolean {
        if (master.audio.isEmpty()) return false
        val hasHindi = master.audio.any { isHindi(it) }
        val hasEnglish = master.audio.any { isEnglish(it) }
        return hasHindi && hasEnglish
    }

    /** True if a stream URL's name/context suggests Hindi audio. */
    fun isHindiFromName(name: String?, url: String?): Boolean {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        return hay.contains("hindi") || hay.contains("हिन्दी") || hay.contains("हिंदी")
    }

    /** True if a stream URL's name/context suggests English audio. */
    fun isEnglishFromName(name: String?, url: String?): Boolean {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        return hay.contains("english") || hay.contains("eng") || hay.contains("english")
    }

    /** True if rendition is likely the original/default track (default=YES or name "original"). A rendition with NO. language attribute is NOT "original". */
    fun isOriginal(rendition: MediaRendition): Boolean =
        rendition.default ||
            rendition.name.contains("original", ignoreCase = true)

    // ── Indian dub languages (, not just Hindi - Tamil/Telugu dubs previously fell out of audioPriority's Hindi/English.
// buckets as "Unknown") ── One.
    data class DubLanguage(val canonical: String, val codes: Set<String>, val names: Set<String>)

    /** The official Indian dubbing languages, Hindi first (Hindi is the most common host-wide dub). Detection is. containment-based over codes and names. */
    val INDIAN_DUB_LANGUAGES = listOf(
        DubLanguage("Hindi", setOf("hi", "hin"), setOf("hindi", "हिन्दी", "हिंदी")),
        DubLanguage("Tamil", setOf("ta", "tam"), setOf("tamil", "தமிழ்")),
        DubLanguage("Telugu", setOf("te", "tel"), setOf("telugu", "తెలుగు")),
        DubLanguage("Malayalam", setOf("ml", "mal"), setOf("malayalam", "മലയാളം")),
        DubLanguage("Kannada", setOf("kn", "kan"), setOf("kannada", "ಕನ್ನಡ")),
        DubLanguage("Bengali", setOf("bn", "ben"), setOf("bengali", "bangla", "বাংলা")),
        DubLanguage("Marathi", setOf("mr", "mar"), setOf("marathi", "मराठी")),
        DubLanguage("Punjabi", setOf("pa", "pan"), setOf("punjabi", "ਪੰਜਾਬੀ")),
        DubLanguage("Gujarati", setOf("gu", "guj"), setOf("gujarati", "ગુજરાતી")),
    )

    /** Canonical Indian-dub language of a rendition ("ta", "ta-IN", "Tamil", "தமிழ்" …), or null. Hindi included - for. renditions the generic isHindi(). */
    fun indianLanguageOf(rendition: MediaRendition): String? {
        val lang = rendition.language?.lowercase()
        if (!lang.isNullOrBlank()) {
            val code = lang.substringBefore('-')
            INDIAN_DUB_LANGUAGES.firstOrNull { code in it.codes }?.let { return it.canonical }
        }
        val name = rendition.name.lowercase()
        if (name.isBlank()) return null
        return INDIAN_DUB_LANGUAGES.firstOrNull { spec -> spec.names.any { name.contains(it) } }?.canonical
    }

    /** Canonical Indian-dub language token declared in a server name and/or URL ("…tamil…", "…/te/…", "MyFlixer Hindi"), or. null. The host-declared. */
    fun languageFromName(name: String?, url: String?): String? {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        if (hay.isBlank()) return null
        return INDIAN_DUB_LANGUAGES.firstOrNull { spec -> spec.names.any { hay.contains(it) } }?.canonical
    }

    /** Audio-language LABEL of the track the player auto-selects - the display counterpart of. Same precedence (DEFAULT=YES. rendition, else the single. */
    fun audioLanguageLabel(master: MasterPlaylist): String? {
        if (master.audio.isEmpty()) return null
        // Hindi/English keep their legacy buckets; an Indian dub beats the "Original" fallback (a DEFAULT=YES `te` track on an.
// English title is a Telugu.
        fun trackLabel(t: MediaRendition): String? = when {
            isHindi(t) -> "Hindi"
            isEnglish(t) -> "English"
            indianLanguageOf(t) != null -> indianLanguageOf(t)
            isOriginal(t) -> "Original"
            else -> null
        }
        val defaultTrack = master.audio.firstOrNull { it.default }
        if (defaultTrack != null) return trackLabel(defaultTrack)
        // No explicit DEFAULT: a single audio rendition IS what plays - label it precisely. Several renditions with no default.
// = true dual/multi audio.
        if (master.audio.size == 1) return trackLabel(master.audio.first())
        val hasHindi = master.audio.any { isHindi(it) }
        val hasEnglish = master.audio.any { isEnglish(it) }
        val hasOriginal = master.audio.any { isOriginal(it) }
        return when {
            hasHindi && hasEnglish -> "Hindi+English"
            hasHindi -> "Hindi"
            hasOriginal -> "Original"
            else -> master.audio.firstNotNullOfOrNull { indianLanguageOf(it) }
                ?: if (hasEnglish) "English" else null
        }
    }

    /** Returns priority 4(Hindi) > 3(Hindi+English) > 2(Original) > 1(English) > 0(Other). Prefers the track the player. actually auto-selects - the. */
    fun audioPriority(master: MasterPlaylist): Int {
        if (master.audio.isEmpty()) return 0
        val defaultTrack = master.audio.firstOrNull { it.default }
        if (defaultTrack != null) {
            return when {
                isHindi(defaultTrack) -> 4
                isEnglish(defaultTrack) -> 1
                isOriginal(defaultTrack) -> 2
                else -> 0
            }
        }
        // No explicit DEFAULT: a single audio rendition IS what plays - label it precisely. Several renditions with no default.
// = true dual/multi audio.
        if (master.audio.size == 1) {
            val t = master.audio.first()
            return when {
                isHindi(t) -> 4
                isEnglish(t) -> 1
                isOriginal(t) -> 2
                else -> 0
            }
        }
        val hasHindi = master.audio.any { isHindi(it) }
        val hasEnglish = master.audio.any { isEnglish(it) }
        val hasOriginal = master.audio.any { isOriginal(it) }
        return when {
            hasHindi && hasEnglish -> 3
            hasHindi -> 4
            hasOriginal -> 2
            hasEnglish -> 1
            else -> 0
        }
    }
}

/** Pure, JVM-testable helpers for title normalization and fuzzy matching. No network, no Android - safe for unit tests. */
object TitleMatch {

    /** Strip punctuation, collapse whitespace, lowercase. Keeps unicode letters/digits. */
    fun normalizeTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** Common spelling variants of a title ("&"→"and", roman numerals, accents). */
    fun titleVariants(title: String?): List<String> {
        val n = normalizeTitle(title)
        if (n.isBlank()) return emptyList()
        val variants = linkedSetOf(n)
        // "a & b" ↔ "a and b" - must be derived.
        if (title != null) {
            variants += normalizeTitle(title.replace("&", " and "))
            variants += normalizeTitle(title.replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), "&"))
        }
        // apostrophes dropped / kept.
        variants += n.replace("'", "")
        variants += n.replace("’", "")
        // roman numeral ↔ number (basic).
        variants += n.replace(Regex("\\biv\\b"), "4")
        variants += n.replace(Regex("\\biii\\b"), "3")
        variants += n.replace(Regex("\\bii\\b"), "2")
        variants += n.replace(Regex("\\bi\\b"), "1")
        return variants.filter { it.isNotBlank() }.distinct()
    }

    /** Classic Levenshtein distance. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }

    /** Relevance 0. . 1 - exact normalized match = 1. 0. */
    fun titleDistance(query: String, title: String): Double {
        val q = normalizeTitle(query)
        val t = normalizeTitle(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        // token-prefix bonus: every query token starts one of the title tokens.
        val qTokens = q.split(" ")
        val tTokens = t.split(" ")
        if (qTokens.all { qt -> tTokens.any { tt -> tt.startsWith(qt) } }) {
            val lenScore = q.length.toDouble() / t.length.toDouble()
            return 0.7 + 0.3 * min(1.0, lenScore)
        }
        // levenshtein similarity on the full strings.
        val maxLen = maxOf(q.length, t.length)
        if (maxLen == 0) return 0.0
        return 1.0 - levenshtein(q, t).toDouble() / maxLen
    }

    /** Whether the title plausibly matches given the release year. */
    fun yearMatches(queryYear: Int?, candidateYear: Int?, tolerance: Int = 2): Boolean {
        if (queryYear == null || candidateYear == null) return true
        return abs(queryYear - candidateYear) <= tolerance
    }

    /** Combined gate used by search: strict token match + score threshold. */
    fun isRelevant(query: String, title: String, queryYear: Int?, candidateYear: Int?): Boolean {
        if (!yearMatches(queryYear, candidateYear)) return false
        // Strip any embedded years (e. g. "Joker (2019)") before comparing titles.
        val q = stripYearTokens(normalizeTitle(query))
        val t = stripYearTokens(normalizeTitle(title))
        return titleDistance(q, t) >= 0.7
    }

    /** Remove standalone 4-digit year tokens ("2019", "2001"). */
    private fun stripYearTokens(normalized: String): String {
        if (normalized.isBlank()) return normalized
        return normalized.split(" ")
            .filterNot { it.matches(Regex("(19|20)\\d{2}")) }
            .joinToString(" ")
            .trim()
    }

    /** Extract 4-digit year, or null. */
    fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("(19|20)\\d{2}").find(raw)?.value?.toIntOrNull()
    }
}



