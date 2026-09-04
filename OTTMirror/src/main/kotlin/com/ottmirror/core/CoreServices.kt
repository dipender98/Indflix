package com.ottmirror.core
/**

 * FILE: CoreServices.kt â€” shared OTTMirror primitives.
 *
 *  - [HttpKit]       shared HTTP helpers (speed probing, common headers).
 *  - [TmdbService]   TMDB search / metadata / season data.
 *  - [ManifestKit]   HLS master-playlist parsing: variants, audio
 *                    renditions, Hindi / dual-audio priority.
 *  - [TitleMatch]    fuzzy title matching (normalize, levenshtein, year
 *                    tolerance) used to verify search results.
 *
 * The resolution orchestration lives in stream/StreamEngine.kt; the VidLink
 * source lives in sources/VidLinkSource.kt.
 */

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Lean HTTP helpers for the OTTMirror module.
 * Shares the CloudStream app client (with its cookie jar) but keeps
 * OTTMirror-specific timeouts and header logic in one place.
 */
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
                // throughput in KB/s
                (bytes * 1000L) / (elapsed * 1024L)
            }.getOrNull()
        }
    }
}

/**
 * TMDB metadata engine for OTTMirror. Search, detail, episodes, IMDB→TMDB lookup.
 * Embedded public API key (same approach as Multimovies — no settings hook in pinned lib).
 */
object TmdbService {

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

    /** Collapse search hits sharing a normalized (title, year). TMDB multi-search
     *  sometimes lists the same title twice — the real entry plus a junk duplicate
     *  of the other media type (e.g. "Breaking Bad" as tv/1396 and movie/1762067).
     *  The highest-rated entry of each group wins. */
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
                    imdbId = null, // IMDB id not in search results; fetch detail if needed
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

/**
 * Pure parsers for HLS master playlists and DASH MPDs.
 * No network, no Android, no CloudStream dependency — safe for JVM unit tests.
 */
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
    )

    /** One EXT-X-MEDIA rendition (audio or subtitles). */
    data class MediaRendition(
        val type: String,       // "AUDIO" | "SUBTITLES"
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
                        val height = attrs["RESOLUTION"]?.let { r ->
                            Regex("\\d+").findAll(r).toList().getOrNull(1)?.value?.toIntOrNull()
                        } ?: 0
                        variants.add(
                            Variant(
                                url = resolveUrl(baseUrl, uri),
                                height = height,
                                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                                codecs = attrs["CODECS"],
                                audioGroup = attrs["AUDIO"],
                                subtitlesGroup = attrs["SUBTITLES"],
                            )
                        )
                    }
                    i++ // consume the URI line
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

    /** Parse `KEY=VALUE,KEY2="VALUE2"` attr lists (values may be quoted). */
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
    fun bestHeight(variants: List<Variant>): Int = variants.maxOfOrNull { it.height } ?: 0

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

    // ── Language detection ──────────────────────────────────

    /** Language codes we know. */
    private val LANG_HINDI = setOf("hi", "hin")
    private val LANG_ENGLISH = setOf("en", "eng")

    /** True if a rendition's language is Hindi. */
    fun isHindi(rendition: MediaRendition): Boolean =
        rendition.language?.lowercase()?.let { it in LANG_HINDI } == true ||
            rendition.name.contains("hindi", ignoreCase = true) ||
            rendition.name.contains("हिन्दी", ignoreCase = true)

    /** True if a rendition's language is English. */
    fun isEnglish(rendition: MediaRendition): Boolean =
        rendition.language?.lowercase()?.let { it in LANG_ENGLISH } == true ||
            rendition.name.contains("english", ignoreCase = true)

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

    /** True if rendition is likely the original/default track (no language attr, default=YES, or name "original"). */
    fun isOriginal(rendition: MediaRendition): Boolean =
        rendition.language == null ||
            rendition.default ||
            rendition.name.contains("original", ignoreCase = true)

    /** Returns priority 4(Hindi) > 3(Hindi+English) > 2(Original) > 1(English) > 0(Other). */
    fun audioPriority(master: MasterPlaylist): Int {
        if (master.audio.isEmpty()) return 0
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

/**
 * Pure, JVM-testable helpers for title normalization and fuzzy matching.
 * No network, no Android — safe for unit tests.
 */
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
        // "a & b" ↔ "a and b" — must be derived from the RAW title before the
        // ampersand is normalized away into a space.
        if (title != null) {
            variants += normalizeTitle(title.replace("&", " and "))
            variants += normalizeTitle(title.replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), "&"))
        }
        // apostrophes dropped / kept
        variants += n.replace("'", "")
        variants += n.replace("’", "")
        // roman numeral ↔ number (basic)
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

    /** Relevance 0..1 — exact normalized match = 1.0. */
    fun titleDistance(query: String, title: String): Double {
        val q = normalizeTitle(query)
        val t = normalizeTitle(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        // token-prefix bonus: every query token starts one of the title tokens
        val qTokens = q.split(" ")
        val tTokens = t.split(" ")
        if (qTokens.all { qt -> tTokens.any { tt -> tt.startsWith(qt) } }) {
            val lenScore = q.length.toDouble() / t.length.toDouble()
            return 0.7 + 0.3 * min(1.0, lenScore)
        }
        // levenshtein similarity on the full strings
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
        // Strip any embedded years (e.g. "Joker (2019)") before comparing titles.
        val q = stripYearTokens(normalizeTitle(query))
        val t = stripYearTokens(normalizeTitle(title))
        return titleDistance(q, t) >= 0.7
    }

    /** Remove standalone 4-digit year tokens ("2019", "2001") from a normalized title. */
    private fun stripYearTokens(normalized: String): String {
        if (normalized.isBlank()) return normalized
        return normalized.split(" ")
            .filterNot { it.matches(Regex("(19|20)\\d{2}")) }
            .joinToString(" ")
            .trim()
    }

    /** Extract 4-digit year from a string, or null. */
    fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("(19|20)\\d{2}").find(raw)?.value?.toIntOrNull()
    }
}



