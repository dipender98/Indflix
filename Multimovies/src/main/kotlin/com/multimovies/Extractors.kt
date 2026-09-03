package com.multimovies
/**

 * FILE: Extractors.kt â€” third-party stream APIs (id -> direct streams).
 *
 * Deterministic JSON endpoints that work without the Multimovies site:
 *  - NxshaExtractor   nxsha.space encrypted API (AES envelopes, wire rules).
 *  - ShowsExtractor   111Movies / vidlove backend at api.shows.st â€” adaptive
 *                     HLS up to 1080p with subtitles.
 *  - VidemExtractor   videm.xyz multi-server JSON API.
 *
 * These are swapped in/out independently; shared plumbing lives in Core.kt,
 * everything site-specific in Multimovies.kt.
 */

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class NxshaSource(
    val name: String,
    val url: String,
    val quality: String = "",
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

data class NxshaSubtitle(val lang: String, val url: String)

/**
 * Nxsha (nxsha.space) extractor.
 *
 * The web player resolves streams through same-origin endpoints whose request
 * and response bodies are CryptoJS-AES envelopes (OpenSSL "Salted__" format,
 * string passphrase). Everything here is deterministic client-side crypto — no
 * browser needed:
 *
 *   1. `GET /api/servers?q=encodeData({tmdbId, imdb_id, type, season, episode})`
 *      -> {"_hash": ...} -> {servers:[{name, scraper, position, high_priority,
 *      web_support, types, isDisable, ...}]}. Works with tmdbId OR imdb_id.
 *   2. `GET /api/sources?q=encodeData({ex_lang:false, provider:<scraper>,
 *      tmdbId, imdb_id, type, season, episode})` -> {sources:[{url, quality,
 *      isEmbed, type(m3u8|mp4|hls|embed), headers}]} — requires tmdbId; when
 *      only an IMDB id is known it is resolved through the open TMDB proxy at
 *      fk.nxsha.xyz (/find/{imdb}?external_source=imdb_id).
 *   3. `GET /api/subtitles` -> {subtitles:[{title, language, uri}]} (best effort).
 *
 * Language info lives inside the quality string ("Hindi dub : 1080",
 * "[Hindi, English] - 720P"), not in a dedicated field. Wire-protocol rules
 * (passphrase, envelope crypto, id parsing, server ordering) live in
 * [NxshaProtocol] so they stay unit-testable without CloudStream on the
 * classpath.
 */
object NxshaExtractor {

    private const val BASE_URL = "https://nxsha.space"
    private const val TMDB_PROXY_FIND = "https://fk.nxsha.xyz/api/v1/wxdb/3/find"

    // Budgets fit inside MultiSourcePuller's outer SOURCE_TIMEOUT_MS (15s):
    // servers (~4s) + one parallel wave of source lookups (~8s).
    private const val SERVERS_BUDGET_MS = 4_000L
    private const val SOURCES_BUDGET_MS = 8_000L
    private const val LOOKUP_BUDGET_MS = 5_000L
    private const val MAX_PARALLEL_PROVIDERS = 4

    /** Per-title single-flight memo so the dooplayer embed AND the GlobalSource
     *  entry for the same title share one API resolution instead of doubling
     *  every request. Short TTL because stream URLs carry expiring tokens. */
    private const val MEMO_TTL_MS = 2 * 60 * 1000L
    private const val MEMO_MAX_SIZE = 32

    private data class MemoEntry(
        val deferred: CompletableDeferred<List<NxshaSource>>,
        val expiresAt: Long,
    )

    private val memo = ConcurrentHashMap<String, MemoEntry>()

    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Accept" to "*/*",
    )

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    suspend fun extract(src: MultiSourcePuller.Source, onSubtitle: (NxshaSubtitle) -> Unit): List<NxshaSource> =
        withContext(Dispatchers.IO) {
            val parsed = NxshaProtocol.parseIdsFromUrl(src.url)
            val tmdbId = parsed.tmdbId ?: src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
            val imdbId = parsed.imdbId ?: src.imdbId?.takeIf { it.startsWith("tt") }
            val type = parsed.type ?: if ((parsed.season ?: src.season) != null) "tv" else "movie"
            val season = parsed.season ?: src.season
            val episode = parsed.episode ?: src.episode
            if (tmdbId == null && imdbId == null) return@withContext emptyList()

            val memoKey = "$tmdbId|$imdbId|$type|$season|$episode"
            resolveOrJoin(memoKey) {
                resolveAll(
                    baseUrl = NxshaProtocol.baseUrlFor(src.url, BASE_URL),
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    type = type,
                    season = season,
                    episode = episode,
                    referer = src.url,
                    onSubtitle = onSubtitle,
                )
            }
        }

    /** EmbedPrefetchCache-style single-flight: exactly one caller runs [resolve],
     *  concurrent + repeat callers within the TTL join its result; empty results
     *  are not cached so the next play retries. */
    private suspend fun resolveOrJoin(key: String, resolve: suspend () -> List<NxshaSource>): List<NxshaSource> {
        memo.values.removeAll { System.currentTimeMillis() > it.expiresAt && it.deferred.isCompleted }
        memo[key]?.let { return it.deferred.await() }

        val job = CompletableDeferred<List<NxshaSource>>()
        val existing = memo.putIfAbsent(key, MemoEntry(job, System.currentTimeMillis() + MEMO_TTL_MS))
        if (existing != null) return existing.deferred.await()

        return try {
            val result = resolve()
            if (result.isEmpty()) memo.remove(key) else trimMemo()
            job.complete(result)
            result
        } catch (t: Throwable) {
            memo.remove(key)
            // Complete normally with an empty result so concurrent/duplicate
            // callers awaiting this job get emptyList() instead of an exception
            // propagating through the player pipeline.
            job.complete(emptyList())
            emptyList()
        }
    }

    private fun trimMemo() {
        while (memo.size > MEMO_MAX_SIZE) {
            val oldest = memo.entries.minByOrNull { it.value.expiresAt }?.key ?: break
            memo.remove(oldest) ?: break
        }
    }

    private suspend fun resolveAll(
        baseUrl: String,
        tmdbId: String?,
        imdbId: String?,
        type: String,
        season: Int?,
        episode: Int?,
        referer: String,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ): List<NxshaSource> {
        // /api/sources needs a TMDB id; resolve imdb -> tmdb through the open
        // TMDB proxy when the page only gave us an IMDB id.
        var tmdb = tmdbId
        if (tmdb == null && imdbId != null) tmdb = resolveTmdbFromImdb(imdbId, type)
        if (tmdb == null) return emptyList()

        // 1) server list (works with tmdb or imdb; pass both when known). Try the
        //    resolved origin first; if it doesn't serve the API (a dooplayer may
        //    hand out a non-player host), fall back to the canonical base. The
        //    host that answers also serves /api/sources and /api/subtitles.
        val candidates = listOf(baseUrl, BASE_URL).distinct()
        var servers = emptyList<NxshaServer>()
        var apiBase = candidates.first()
        for (candidate in candidates) {
            val serversJson = apiGet(
                "$candidate/api/servers",
                buildMap {
                    put("tmdbId", tmdb)
                    put("imdb_id", imdbId.orEmpty())
                    put("type", type)
                    put("season", season)
                    put("episode", episode)
                },
                SERVERS_BUDGET_MS,
            )
            servers = serversJson?.optJSONArray("servers")
                ?.let { NxshaProtocol.parseServers(it, type) }
                .orEmpty()
            if (servers.isNotEmpty()) {
                apiBase = candidate
                break
            }
        }
        if (servers.isEmpty()) return emptyList()

        // 2) per-provider sources, bounded-parallel, nitro-first order
        val collected = coroutineScope {
            val sem = Semaphore(MAX_PARALLEL_PROVIDERS)
            servers.map { server ->
                async {
                    sem.acquire()
                    try {
                        withTimeoutOrNull(SOURCES_BUDGET_MS) {
                            fetchProviderSources(apiBase, server, tmdb, imdbId, type, season, episode, referer, onSubtitle)
                        }.orEmpty()
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll().flatten()
        }

        fetchSubtitles(apiBase, tmdb, type, season, episode, onSubtitle)
        return collected
    }

    /** GET one of the site's API endpoints with an encrypted q; returns the decrypted JSON. */
    private suspend fun apiGet(endpoint: String, payload: Map<String, Any?>, budgetMs: Long): JSONObject? {
        val q = NxshaProtocol.encodeData(payload)
        val body = HttpKit.get("$endpoint?q=$q", headers = sharedHeaders, budgetMs = budgetMs) ?: return null
        val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return NxshaProtocol.decodeData(envelope.optString("_hash"))
    }

    /** Fetch + map one provider's sources. Direct streams emit as-is; embed
     *  entries go through the CloudStream registry, then unwrapEmbed, and are
     *  dropped when neither yields a playable stream URL. */
    private suspend fun fetchProviderSources(
        baseUrl: String,
        server: NxshaServer,
        tmdbId: String,
        imdbId: String?,
        type: String,
        season: Int?,
        episode: Int?,
        referer: String,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ): List<NxshaSource> {
        val json = apiGet(
            "$baseUrl/api/sources",
            buildMap {
                put("ex_lang", false)
                put("provider", server.scraper)
                put("tmdbId", tmdbId)
                put("imdb_id", imdbId.orEmpty())
                put("type", type)
                put("season", season)
                put("episode", episode)
            },
            SOURCES_BUDGET_MS,
        ) ?: return emptyList()

        val label = "Nxsha (" + NxshaProtocol.shortServerName(server.name) + ")"
        val arr = json.optJSONArray("sources") ?: return emptyList()
        val out = mutableListOf<NxshaSource>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").trim().takeIf { it.startsWith("http") } ?: continue
            if (MultiSourcePuller.isYouTubeHost(url)) continue
            val quality = s.optString("quality")
            val hindi = NxshaProtocol.isHindiQuality(quality) || MultiSourcePuller.isHindiHint(label, url, null)
            val fullName = if (hindi) "$label Hindi" else label

            if (!s.optBoolean("isEmbed", false)) {
                val streamType = s.optString("type")
                out.add(
                    NxshaSource(
                        name = fullName,
                        url = url,
                        quality = quality,
                        isM3u8 = streamType.equals("m3u8", true) || streamType.equals("hls", true) ||
                            url.contains(".m3u8", ignoreCase = true),
                    )
                )
                continue
            }

            // Embedded entry: another site's player page. Try the registry
            // first (some hosts have extractors), then unwrapEmbed.
            val registryLinks = mutableListOf<ExtractorLink>()
            val registryOk = runCatching {
                loadExtractor(
                    url = url,
                    referer = referer,
                    subtitleCallback = { onSubtitle(NxshaSubtitle(it.lang, it.url)) },
                    callback = { registryLinks.add(it) },
                )
            }.getOrDefault(false)
            if (registryOk && registryLinks.isNotEmpty()) {
                registryLinks.forEach { l ->
                    out.add(
                        NxshaSource(
                            name = fullName,
                            url = l.url,
                            quality = quality,
                            isM3u8 = l.type == ExtractorLinkType.M3U8 || l.url.contains(".m3u8", ignoreCase = true),
                        )
                    )
                }
                continue
            }
            val unwrapped = MultiSourcePuller.unwrapEmbed(url, referer = referer)
            val playable = unwrapped.contains("serve_m3u8=", ignoreCase = true) ||
                unwrapped.contains(".m3u8", ignoreCase = true) ||
                unwrapped.contains(".mp4", ignoreCase = true) ||
                unwrapped.contains(".webm", ignoreCase = true)
            if (playable) {
                out.add(
                    NxshaSource(
                        name = fullName,
                        url = unwrapped,
                        quality = quality,
                        isM3u8 = unwrapped.contains(".m3u8", ignoreCase = true),
                    )
                )
            }
        }
        return out
    }

    /** Best-effort subtitles; direct opensubtitles/srt links work as-is in
     *  CloudStream (server-side fetch, no browser CORS involved). */
    private suspend fun fetchSubtitles(
        baseUrl: String,
        tmdbId: String,
        type: String,
        season: Int?,
        episode: Int?,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ) {
        val json = apiGet(
            "$baseUrl/api/subtitles",
            mapOf(
                "tmdbId" to tmdbId,
                "type" to type,
                "season" to season,
                "episode" to episode,
            ),
            LOOKUP_BUDGET_MS,
        ) ?: return
        val arr = json.optJSONArray("subtitles") ?: return
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val uri = s.optString("uri").takeIf { it.startsWith("http") } ?: continue
            val lang = s.optString("title").ifBlank { s.optString("language") }.ifBlank { "sub" }
            onSubtitle(NxshaSubtitle(lang, uri))
        }
    }

    /** imdb -> tmdb id via Nxsha's open TMDB proxy (last resort for pages that
     *  only carry an IMDB id, e.g. IMDB-keyed dooplayer embeds). */
    private suspend fun resolveTmdbFromImdb(imdbId: String, type: String): String? {
        val body = HttpKit.get(
            "$TMDB_PROXY_FIND/$imdbId?external_source=imdb_id",
            headers = sharedHeaders,
            budgetMs = LOOKUP_BUDGET_MS,
        ) ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Prefer the array matching the requested type, but fall back
        // to the other one when empty.
        val primary = if (type == "movie") "movie_results" else "tv_results"
        val secondary = if (type == "movie") "tv_results" else "movie_results"
        return listOf(primary, secondary).firstNotNullOfOrNull { key ->
            obj.optJSONArray(key)?.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() && it != "null" }
        }
    }
}

/** One entry of the decrypted /api/servers list (subset of fields we use). */
internal data class NxshaServer(
    val name: String,
    val scraper: String,
    val position: Int,
    val highPriority: Int,
)

/**
 * Pure Nxsha wire-protocol logic: envelope crypto, id parsing, server rules.
 *
 * Kept free of CloudStream imports (and isolated from [NxshaExtractor]) so it
 * runs on the plain JVM unit-test classpath — same pattern as the CryptoJs
 * result types. See [NxshaExtractor]'s doc for the endpoint flow.
 */
internal object NxshaProtocol {

    /** AES passphrase of the API envelopes. Extracted Aug 2026 from the
     *  player bundle (chunk 0fo9av_cihir0.js, module 41159,
     *  String.fromCharCode(83,56,120,33,74,107,52,90,80,49,117,71,56,36,109,121)).
     *  If extraction starts returning zero sources, re-extract from
     *  https://nxsha.space/_next/static/chunks/0fo9av_cihir0.js (file tail) or
     *  via a debugger on web.nxsha.app/embed/movie/550. */
    internal const val PASSPHRASE = "S8x!Jk4ZP1uG8\$my"

    /** Random ~10-char [a-z0-9] salt mimicking Math.random().toString(36).substring(2,12). */
    fun randomSalt(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(10)
        repeat(10) { sb.append(alphabet[SecureRandom().nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /** Build the base64url(no padding) encrypted `q` parameter value. Mirrors
     *  the player's encodeData(): payload + _req_ts + _req_salt -> JSON ->
     *  CryptoJS AES -> base64url without '=' padding. */
    fun encodeData(payload: Map<String, Any?>): String {
        val json = JSONObject()
        payload.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        json.put("_req_ts", System.currentTimeMillis())
        json.put("_req_salt", randomSalt())
        return CryptoJs.aesEncryptCryptoJs(json.toString(), PASSPHRASE)
            .replace("+", "-").replace("/", "_").replace("=", "")
    }

    /** Decrypt a response `_hash` envelope to its JSON payload (the player's
     *  decodeData()). Returns null on malformed/undecryptable input. */
    fun decodeData(hash: String?): JSONObject? {
        if (hash.isNullOrBlank()) return null
        val std = hash.replace("-", "+").replace("_", "/")
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        val plain = CryptoJs.aesDecryptCryptoJs(padded, PASSPHRASE) ?: return null
        return runCatching { JSONObject(plain) }.getOrNull()
    }

    data class ParsedIds(
        val tmdbId: String?,
        val imdbId: String?,
        val type: String?,
        val season: Int?,
        val episode: Int?,
    )

    /** Extract ids/type/season/episode from an Nxsha embed-style URL. Handles
     *  path form `/embed/movie/{tmdb}` & `/embed/tv/{tmdb}/{s}/{e}`, query form
     *  `?tmdb=&type=&s=&e=`, and IMDB-keyed forms `?imdb=tt...`. */
    fun parseIdsFromUrl(url: String): ParsedIds {
        val pathMatch = Regex("""/embed/(movie|tv)/(\d{2,10})(?:/(\d{1,4}))?(?:/(\d{1,4}))?""").find(url)
        var tmdb: String? = pathMatch?.groupValues?.getOrNull(2)
        var type: String? = pathMatch?.groupValues?.getOrNull(1)?.let { if (it == "movie") "movie" else "tv" }
        var season: Int? = pathMatch?.groupValues?.getOrNull(3)?.toIntOrNull()?.takeIf { type == "tv" }
        var episode: Int? = pathMatch?.groupValues?.getOrNull(4)?.toIntOrNull()?.takeIf { type == "tv" }

        fun queryValue(vararg names: String): String? {
            val parts = url.split('?', '&')
            for (part in parts.drop(1)) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val k = part.substring(0, idx).lowercase()
                if (names.any { k == it }) return part.substring(idx + 1)
            }
            return null
        }

        queryValue("tmdb", "tmdbid")?.takeIf { it.matches(Regex("""\d{2,10}""")) }?.let { tmdb = it }
        queryValue("type")?.let { t ->
            when {
                t.equals("movie", true) -> type = "movie"
                t.equals("tv", true) || t.equals("series", true) || t.equals("show", true) -> type = "tv"
            }
        }
        queryValue("s", "season")?.toIntOrNull()?.let { season = it }
        queryValue("e", "episode", "ep")?.toIntOrNull()?.let { episode = it }
        val imdb = queryValue("imdb", "imdb_id", "imdbid")?.takeIf { it.matches(Regex("""tt\d{6,10}""")) }
        // season/episode are NOT filtered on type here: query-form embeds such as
        // ?imdb=tt...&s=1&e=1 (no type=) must keep them so extract() can infer tv.
        // (Path-form already only kept s/e for /embed/tv/..., so this is safe.)
        return ParsedIds(tmdb, imdb, type, season, episode)
    }

    /** Server ordering: Nitro first (verified fastest), then high_priority asc,
     *  then listed position asc. Stable sort keeps API order for ties. */
    fun orderServers(servers: List<NxshaServer>): List<NxshaServer> =
        servers.sortedWith(
            compareByDescending<NxshaServer> {
                it.scraper.contains("nitro", ignoreCase = true) || it.name.contains("nitro", ignoreCase = true)
            }.thenBy { it.highPriority }
                .thenBy { it.position }
        )

    /** Filter the raw servers array to usable entries: web_support, not
     *  disabled, serves [type] (missing `types` = compatible), then ordered. */
    fun parseServers(arr: JSONArray?, type: String): List<NxshaServer> {
        if (arr == null) return emptyList()
        val out = mutableListOf<NxshaServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("web_support", true)) continue
            if (o.optBoolean("isDisable", false)) continue
            val servesTypes = o.optJSONArray("types")
            if (servesTypes != null && servesTypes.length() > 0 &&
                !(0 until servesTypes.length()).any { servesTypes.optString(it) == type }
            ) continue
            out.add(
                NxshaServer(
                    name = o.optString("name").ifBlank { o.optString("scraper") },
                    scraper = o.optString("scraper"),
                    position = o.optInt("position", Int.MAX_VALUE),
                    highPriority = o.optInt("high_priority", Int.MAX_VALUE),
                )
            )
        }
        return orderServers(out)
    }

    /** Short display label: "Nitro - [Multi-Lang]" -> "Nitro". */
    fun shortServerName(name: String): String =
        name.substringBefore('-').trim().ifEmpty { name.trim() }

    /** True when a quality string marks a Hindi audio track ("Hindi dub : 1080",
     *  "720p | Hindi", "[Hindi, English] - 720P"). */
    fun isHindiQuality(quality: String?): Boolean =
        quality.orEmpty().contains("hindi", ignoreCase = true)

    /** Prefer the origin of an nxsha.* embed URL (the dooplayer may hand out a
     *  different host than the canonical base); else the canonical base.
     *  Only nxsha.space and web.nxsha.app host the player API — nxsha.cc,
     *  nxsha.app, nxsha.xyz are landing/status sites (404/503). */
    fun baseUrlFor(url: String, fallback: String): String {
        val schemeHost = Regex("""^(https?)://[^/]+""").find(url)?.value ?: return fallback
        val host = schemeHost.substringAfter("://").lowercase()
        val playerHost = host == "nxsha.space" || host == "web.nxsha.app" ||
            host.endsWith(".nxsha.space") || host.endsWith(".web.nxsha.app")
        return if (playerHost) schemeHost else fallback
    }
}

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
        // api.shows.st now resolves a stream for BOTH /movie and /tv only from a
        // TMDB id (probed Aug 2026: /movie?id=tt0133093 returns "source":null,
        // /movie?id=603 returns a playable master). Prefer the TMDB id already
        // resolved during load()'s metadata fetch; fall back to the IMDB id only
        // when no TMDB id is cached — no extra TMDB public-API call is made here.
        val tmdbId = src.tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
        val imdbId = src.imdbId?.takeIf { it.startsWith("tt") }
        val id = tmdbId ?: imdbId ?: return@withContext emptyList()
        val isTv = src.season != null
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

