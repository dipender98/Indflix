package com.multimovies

import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A resolved stream from the 111Movies backend — the vidlove player's
 * deterministic JSON API at api.shows.st.
 */
data class ShowsSource(
    val name: String,
    val url: String,
    val quality: String = "",
    val isM3u8: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Dedicated extractor for the 111Movies / vidlove backend.
 *
 * The vidlove player at player.vidlove.cc is a JS SPA, but its data comes from
 * a fully deterministic JSON API:
 *
 *   1. `GET https://api.shows.st/{movie|tv}?id={tmdbId}[&season=&episode=]&mode=json`
 *      returns `{"meta":..., "subtitles":[...], "source":{...}}`.
 *   2. `source.url` is an adaptive HLS master playlist (Content-Type
 *      application/vnd.apple.mpegurl) whose renditions play when the request
 *      carries the player's Referer.
 *   3. `source.manifest` is the same master playlist inline; `subtitles` lists
 *      VTT tracks served from cache.vdrk.site.
 *
 * No browser needed — the API is plain JSON and the streams are direct HLS.
 * Note: the `/tv` endpoint requires a TMDB id (IMDB ids return 400); `/movie`
 * accepts either.
 */
object ShowsExtractor {

    private const val API_BASE = "https://api.shows.st"
    private const val PLAYER_REFERER = "https://player.vidlove.cc/"
    private const val TIMEOUT_MS = 6_000L

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Referer" to PLAYER_REFERER,
    )

    /**
     * Extract the 111Movies HLS playlist for [src]. Emits the adaptive master
     * playlist from `source.url` plus any subtitles via [onSubtitle].
     */
    suspend fun extract(
        src: MultiSourcePuller.Source,
        onSubtitle: (SubtitleFile) -> Unit = {},
    ): List<ShowsSource> = withContext(Dispatchers.IO) {
        // api.shows.st accepts an IMDB id for /movie but requires a TMDB id for
        // /tv. Prefer the IMDB id (always available via load()) for movies; for TV
        // use the TMDB id already resolved during load()'s metadata fetch — no
        // extra TMDB public-API call is made here.
        val isTv = src.season != null
        val tmdbId = src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
        val imdbId = src.imdbId?.takeIf { it.startsWith("tt") }
        val id = if (isTv) {
            tmdbId ?: return@withContext emptyList()
        } else {
            imdbId ?: tmdbId ?: return@withContext emptyList()
        }
        val type = if (isTv) "tv" else "movie"
        val url = if (type == "tv") {
            "$API_BASE/tv?id=$id&season=${src.season}&episode=${src.episode}&mode=json"
        } else {
            "$API_BASE/movie?id=$id&mode=json"
        }

        val json = HttpKit.getJson(url, headers = sharedHeaders, budgetMs = TIMEOUT_MS)
            ?: return@withContext emptyList()
        val sourceUrl = parseSourceUrl(json) ?: return@withContext emptyList()

        parseSubtitleTracks(json).forEach { (lang, file) ->
            onSubtitle(SubtitleFile(lang, file))
        }

        val label = json.optJSONObject("source")?.optString("label", "VidAPI")
            ?.takeIf { it.isNotBlank() } ?: "VidAPI"
        listOf(
            ShowsSource(
                name = label,
                url = sourceUrl,
                quality = "",
                isM3u8 = true,
                headers = mapOf("Referer" to PLAYER_REFERER),
            )
        )
    }

    /** The adaptive HLS master playlist URL from the API response, or null. */
    internal fun parseSourceUrl(json: JSONObject): String? =
        json.optJSONObject("source")?.optString("url", "")?.takeIf { it.isNotBlank() }

    /** (label, file) subtitle tracks from the API response. */
    internal fun parseSubtitleTracks(json: JSONObject): List<Pair<String, String>> {
        val arr = json.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = s.optString("label", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = s.optString("file", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            label to file
        }
    }
}

/**
 * A resolved stream from videm.xyz — name, URL, quality, type, and headers for
 * the player. Pure Kotlin data class (no CloudStream dependency) so the
 * extractor can be tested on the JVM without the cloudstream library.
 */
data class VidemSource(
    val name: String,
    val url: String,
    val quality: String = "",
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Dedicated extractor for [videm.xyz](https://videm.xyz), a fast, multi-server
 * TMDB/IMDB-keyed embed player discovered via the vidapi.xyz aggregator.
 *
 * The player is fully deterministic and does NOT require a browser:
 *   1. The embed page (`/embed/{movie|tv}/{id}[/{s}/{e}]`) is server-rendered
 *      and carries a signed JSON config in `var Q = {...}`.
 *   2. `Q.t` is a signed token used to authenticate subsequent API calls.
 *   3. `GET /api.php?a=sources&type=...&id=...&s=...&e=...&t=<Q.t>` returns a
 *      list of servers, each with a signed `ref` and display `name`.
 *   4. `GET /api.php?a=play&ref=<server.ref>&t=<Q.t>` returns
 *      `{"url":"/stream?id=...","type":"hls"}` — the HLS playlist URL.
 *   5. The HLS playlist streams directly; no further token dance needed.
 *
 * Multiple servers provide redundancy and the `lang` field in the server list
 * hints at multi-language / dual-audio support.
 */
object VidemExtractor {

    private const val BASE_URL = "https://videm.xyz"
    private const val EMBED_TIMEOUT_MS = 6_000L
    private const val SOURCES_TIMEOUT_MS = 5_000L
    private const val PLAY_TIMEOUT_MS = 5_000L
    private const val MAX_PARALLEL_SERVERS = 4

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Referer" to BASE_URL,
    )

    /**
     * Extract streams from videm.xyz for the given [src]. Returns a list of
     * [VidemSource] entries, one per server that responded with a playable URL.
     */
    suspend fun extract(src: MultiSourcePuller.Source): List<VidemSource> =
        withContext(Dispatchers.IO) {
            // Prefer the IMDB id (always available via load() without a TMDB
            // public-API call); fall back to the cached TMDB id only if needed.
            val imdbId = src.imdbId?.takeIf { it.startsWith("tt") }
            val tmdbId = src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
            val id = imdbId ?: tmdbId ?: return@withContext emptyList()
            val type = if (src.season != null) "tv" else "movie"
            val season = src.season ?: 0
            val episode = src.episode ?: 0

            val embedUrl = "$BASE_URL/embed/$type/$id" +
                (if (type == "tv" && season > 0) "/$season/$episode" else "")
            val embedHtml = HttpKit.get(embedUrl, headers = sharedHeaders, budgetMs = EMBED_TIMEOUT_MS)
                ?: return@withContext emptyList()
            val q = parseQConfig(embedHtml) ?: return@withContext emptyList()
            val token = q.optString("t", "").takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val qType = q.optString("type", type)
            val qId = q.optString("id", id)
            val qSeason = q.optInt("s", season)
            val qEpisode = q.optInt("e", episode)

            val sourcesUrl = "$BASE_URL/api.php?a=sources&type=$qType&id=$qId&s=$qSeason&e=$qEpisode&t=${
                URLEncoder.encode(token, "UTF-8")
            }"
            val sourcesJson = HttpKit.getJson(sourcesUrl, headers = sharedHeaders, budgetMs = SOURCES_TIMEOUT_MS)
                ?: return@withContext emptyList()
            val servers = sourcesJson.optJSONArray("servers") ?: return@withContext emptyList()

            val results = mutableListOf<VidemSource>()
            coroutineScope {
                val sem = Semaphore(MAX_PARALLEL_SERVERS)
                (0 until servers.length()).map { i ->
                    async {
                        sem.acquire()
                        try {
                            val server = servers.getJSONObject(i)
                            val ref = server.optString("ref", "")
                            val name = server.optString("name", "VidEm")
                            if (ref.isBlank()) return@async
                            val playUrl = "$BASE_URL/api.php?a=play&ref=$ref&t=${
                                URLEncoder.encode(token, "UTF-8")
                            }"
                            val playJson = HttpKit.getJson(playUrl, headers = sharedHeaders, budgetMs = PLAY_TIMEOUT_MS)
                                ?: return@async
                            val streamUrl = playJson.optString("url", "")
                            if (streamUrl.isBlank()) return@async
                            val resolved = MultiSourcePuller.resolveRelative(BASE_URL, streamUrl)
                            synchronized(results) {
                                results.add(
                                    VidemSource(
                                        name = name,
                                        url = resolved,
                                        quality = "",
                                        isM3u8 = playJson.optString("type", "") == "hls",
                                        headers = mapOf("Referer" to BASE_URL),
                                    )
                                )
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll()
            }
            results
        }

    /** Extract `var Q = {...}` JSON from the embed page HTML. */
    internal fun parseQConfig(html: String): JSONObject? {
        val m = Regex("""var\s+Q\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val raw = m.groupValues[1]
        // Normalise escaped slashes the JSON parser can't handle
        val cleaned = raw.replace("\\/", "/")
        return runCatching { JSONObject(cleaned) }.getOrNull()
    }
}