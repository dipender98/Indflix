package com.multimovies
/** FILE: ExternalSources. kt â€” third-party stream APIs (id -> direct streams). Deterministic JSON endpoints that work. without the site. */

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
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
    val isDash: Boolean = false,
)

data class NxshaSubtitle(val lang: String, val url: String)

/** Nxsha (nxsha. space) extractor. The web player resolves streams through same-origin endpoints whose request and. response bodies are CryptoJS-AES. */
object NxshaExtractor {

    private const val BASE_URL = "https://nxsha.space"
    private const val TMDB_PROXY_FIND = "https://fk.nxsha.xyz/api/v1/wxdb/3/find"

    // Budgets fit inside MultiSourcePuller's outer SOURCE_TIMEOUT_MS (15s): servers (~4s) + one parallel wave of source.
// lookups (~8s).
    private const val SERVERS_BUDGET_MS = 4_000L
    private const val SOURCES_BUDGET_MS = 8_000L
    private const val LOOKUP_BUDGET_MS = 5_000L
    private const val MAX_PARALLEL_PROVIDERS = 4

    /** Per-title single-flight memo so the dooplayer embed AND the GlobalSource entry for the same title share one API. resolution instead of doubling. */
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

    // Entry point.

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

    /** EmbedPrefetchCache-style single-flight: exactly one caller runs, concurrent + repeat callers within the TTL join its. result; empty results are not. */
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
            // Cancellation must propagate (structured concurrency); joiners get it via the cancelled job.
            if (t is kotlinx.coroutines.CancellationException) {
                job.cancel()
                throw t
            }
            // Complete normally with an empty result so concurrent/duplicate callers awaiting this job get emptyList() instead of.
// an exception propagating.
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
        // /api/sources needs a TMDB id; resolve imdb -> tmdb through the open TMDB proxy when the page only gave us an IMDB id.
        var tmdb = tmdbId
        if (tmdb == null && imdbId != null) tmdb = resolveTmdbFromImdb(imdbId, type)
        if (tmdb == null) return emptyList()

        // 1) server list (works with tmdb or imdb; pass both when known). Try the resolved origin first; if it doesn't serve.
// the API (a dooplayer may hand.
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

        // 2) per-provider sources, bounded-parallel, nitro-first order. The subtitle
        // lookup rides alongside and never gates the streams (outer kill is 15s).
        val collected = coroutineScope {
            val subsJob = async { runCatching { fetchSubtitles(apiBase, tmdb, type, season, episode, onSubtitle) } }
            val sem = Semaphore(MAX_PARALLEL_PROVIDERS)
            val sources = servers.map { server ->
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
            if (subsJob.isCompleted) runCatching { subsJob.await() } else subsJob.cancel()
            sources
        }
        return collected
    }

    /** GET one of the site's API endpoints with an encrypted q; returns the decrypted JSON. */
    private suspend fun apiGet(endpoint: String, payload: Map<String, Any?>, budgetMs: Long): JSONObject? {
        val q = NxshaProtocol.encodeData(payload)
        val body = HttpKit.get("$endpoint?q=$q", headers = sharedHeaders, budgetMs = budgetMs) ?: return null
        val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return NxshaProtocol.decodeData(envelope.optString("_hash"))
    }

    /** Fetch + map one provider's sources. Direct streams emit as-is; embed entries go through the CloudStream registry. then unwrapEmbed, and are. */
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
            val hindi = NxshaProtocol.isHindiQuality(quality) || MultiSourcePuller.declaredHindi(label, url)
            val fullName = if (hindi) "$label (Hindi)" else label

            if (!s.optBoolean("isEmbed", false)) {
                val streamType = s.optString("type")
                out.add(
                    NxshaSource(
                        name = fullName,
                        url = url,
                        quality = quality,
                        isM3u8 = streamType.equals("m3u8", true) || streamType.equals("hls", true) ||
                            url.contains(".m3u8", ignoreCase = true),
                        // DASH manifests (.mpd) are adaptive too - mistyping them as VIDEO breaks playback.
                        isDash = streamType.equals("mpd", true) || streamType.equals("dash", true) ||
                            url.contains(".mpd", ignoreCase = true),
                    )
                )
                continue
            }

            // Embedded entry: another site's player page. Try the registry first (some hosts have extractors), then unwrapEmbed.
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
                            isDash = l.type == ExtractorLinkType.DASH || l.url.contains(".mpd", ignoreCase = true),
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
                        isDash = unwrapped.contains(".mpd", ignoreCase = true),
                    )
                )
            }
        }
        return out
    }

    /** Best-effort subtitles; direct opensubtitles/srt links work as-is in CloudStream (server-side fetch, no browser CORS. involved). */
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

    /** imdb -> tmdb id via Nxsha's open TMDB proxy (last resort for pages that only carry an IMDB id, e. g. IMDB-keyed. dooplayer embeds). */
    private suspend fun resolveTmdbFromImdb(imdbId: String, type: String): String? {
        val body = HttpKit.get(
            "$TMDB_PROXY_FIND/$imdbId?external_source=imdb_id",
            headers = sharedHeaders,
            budgetMs = LOOKUP_BUDGET_MS,
        ) ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Prefer the array matching the requested type, but fall back to the other one when empty.
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

/** Pure Nxsha wire-protocol logic: envelope crypto, id parsing, server rules. Kept free of CloudStream imports (and. isolated) so it runs on the plain. */
internal object NxshaProtocol {

    /** AES passphrase of the API envelopes. Extracted from the player bundle, module 41159 (rotated keys happen). */
    internal const val PASSPHRASE = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

    /** Random ~10-char salt mimicking Math. random(). toString(36). substring(2, 12). */
    fun randomSalt(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(10)
        repeat(10) { sb.append(alphabet[SecureRandom().nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /** Build the base64url(no padding) encrypted `q` parameter value. ): payload + _req_ts + _req_salt -> JSON -> CryptoJS. AES -> base64url without '='. */
    fun encodeData(payload: Map<String, Any?>): String {
        val json = JSONObject()
        payload.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        json.put("_req_ts", System.currentTimeMillis())
        json.put("_req_salt", randomSalt())
        return CryptoJs.aesEncryptCryptoJs(json.toString(), PASSPHRASE)
            .replace("+", "-").replace("/", "_").replace("=", "")
    }

    /** Decrypt a response `_hash` envelope to its JSON payload (the player's decodeData()). Returns null on. malformed/undecryptable input. */
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

    /** Extract ids/type/season/episode. Handles path form `/embed/movie/{tmdb}` & `/embed/tv/{tmdb}/{s}/{e}`, query form. `?tmdb=&type=&s=&e=`, and. */
    fun parseIdsFromUrl(url: String): ParsedIds {
        val pathMatch = Regex("""/embed/(movie|tv)/(\d{2,10})(?:/(\d{1,4}))?(?:/(\d{1,4}))?""").find(url)
        var tmdb: String? = pathMatch?.groupValues?.getOrNull(2)
        var type: String? = pathMatch?.groupValues?.getOrNull(1)?.let { if (it == "movie") "movie" else "tv" }
        var season: Int? = pathMatch?.groupValues?.getOrNull(3)?.toIntOrNull()?.takeIf { type == "tv" }
        var episode: Int? = pathMatch?.groupValues?.getOrNull(4)?.toIntOrNull()?.takeIf { type == "tv" }

        // IMDB id in the path (`/embed/movie/tt1375666`): the site now serves Nxsha movie embeds this way.
        val pathImdb = Regex("""/embed/(?:movie|tv)/(tt\d{6,10})(?:[/?&#]|$)""").find(url)
            ?.groupValues?.getOrNull(1)
        if (type == null) {
            Regex("""/embed/(movie|tv)/""").find(url)?.groupValues?.getOrNull(1)?.let {
                type = if (it == "movie") "movie" else "tv"
            }
        }

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
        val imdb = pathImdb
            ?: queryValue("imdb", "imdb_id", "imdbid")?.takeIf { it.matches(Regex("""tt\d{6,10}""")) }
        // season/episode are NOT filtered on type here: query-form embeds such as?imdb=tt. . . &s=1&e=1 (no type=) must keep.
// them so extract() can infer tv.
        return ParsedIds(tmdb, imdb, type, season, episode)
    }

    /** Server ordering: high_priority asc, then listed position asc. No provider gets a hardcoded top slot. */
    fun orderServers(servers: List<NxshaServer>): List<NxshaServer> =
        servers.sortedWith(compareBy<NxshaServer> { it.highPriority }.thenBy { it.position })

    /** Retired upstream scrapers: listed online but never resolve (fail/timeout every call). */
    private val RETIRED_SCRAPERS = setOf("vidking")

    /** Filter the raw servers array to usable entries: web_support, not disabled, not retired, serves (missing `types` = compatible). then ordered. */
    fun parseServers(arr: JSONArray?, type: String): List<NxshaServer> {
        if (arr == null) return emptyList()
        val out = mutableListOf<NxshaServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("scraper") in RETIRED_SCRAPERS) continue
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

    /** Short display label: "Nitro - " -> "Nitro". */
    fun shortServerName(name: String): String =
        name.substringBefore('-').trim().ifEmpty { name.trim() }

    /** True when a quality string marks a Hindi audio track ("Hindi dub: 1080", "720p | Hindi", " - 720P"). */
    fun isHindiQuality(quality: String?): Boolean =
        quality.orEmpty().contains("hindi", ignoreCase = true)

    /** Prefer the origin of an nxsha. * embed URL (the dooplayer may hand out a different host than the canonical base). else the canonical base. Only. */
    fun baseUrlFor(url: String, fallback: String): String {
        val schemeHost = Regex("""^(https?)://[^/]+""").find(url)?.value ?: return fallback
        val host = schemeHost.substringAfter("://").lowercase()
        val playerHost = host == "nxsha.space" || host == "web.nxsha.app" ||
            host.endsWith(".nxsha.space") || host.endsWith(".web.nxsha.app")
        return if (playerHost) schemeHost else fallback
    }
}

/** A resolved stream. xyz - name, URL, quality, type, and headers for the player. Pure Kotlin data class (no. CloudStream dependency) so the extractor. */
data class VidemSource(
    val name: String,
    val url: String,
    val quality: String = "",
    val isM3u8: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

/** Dedicated extractor for (a fast, multi-server TMDB/IMDB-keyed embed player discovered via the vidapi. xyz. aggregator. The player is fully. */
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

    /** Extract streams. xyz for the given. Returns a list of entries, one per server that responded with a playable URL. */
    suspend fun extract(src: MultiSourcePuller.Source): List<VidemSource> =
        withContext(Dispatchers.IO) {
            // Prefer the IMDB id (always available via load() without a TMDB public-API call); fall back to the cached TMDB id.
// only if needed.
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
                            var playJson = HttpKit.getJson(playUrl, headers = sharedHeaders, budgetMs = PLAY_TIMEOUT_MS)
                                ?: return@async
                            // Stale mint: the player re-mints with fresh=1 when the first answer carries no url.
                            if (playJson.optString("url", "").isBlank()) {
                                playJson = HttpKit.getJson("$playUrl&fresh=1", headers = sharedHeaders, budgetMs = PLAY_TIMEOUT_MS)
                                    ?: return@async
                            }
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

    /** Extract `var Q = {. . . }` JSON. */
    internal fun parseQConfig(html: String): JSONObject? {
        val m = Regex("""var\s+Q\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val raw = m.groupValues[1]
        // Normalise escaped slashes the JSON parser can't handle.
        val cleaned = raw.replace("\\/", "/")
        return runCatching { JSONObject(cleaned) }.getOrNull()
    }
}

/** One resolved GDMirror HLS stream. */
data class GdMirrorStream(
    val name: String,
    val url: String,
    val fileName: String = "",
    val referer: String? = null,
)

/** GDMirror (streams.iqsmartgames.com) extractor. The dooplayer embed is a JS shell, resolved statically:
 *  embed vars -> mymovieapi/myseriesapi (fileslugs) -> embedhelper2 (mirror embeds) -> mirror player page
 *  (packed JWPlayer `links` carrying HLS masters). */
object GdMirrorExtractor {

    private const val DEF_API = "https://streams.iqsmartgames.com"
    private const val DEF_PLAYER = "https://pro.iqsmartgames.com"
    private const val PAGE_BUDGET_MS = 5_000L
    private const val API_BUDGET_MS = 5_000L
    private const val HELPER_BUDGET_MS = 5_000L
    private const val MIRROR_BUDGET_MS = 6_000L
    private const val MAX_SLUGS = 2
    private const val MAX_MIRRORS = 3
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    suspend fun extract(pageUrl: String): List<GdMirrorStream> = coroutineScope {
        val embed = GdMirrorProtocol.parseEmbed(pageUrl) ?: return@coroutineScope emptyList()
        val html = HttpKit.get(
            embed.pageUrl,
            mapOf("User-Agent" to UA, "Accept" to "text/html,*/*", "Referer" to pageUrl),
            PAGE_BUDGET_MS,
        ) ?: return@coroutineScope emptyList()
        val vars = GdMirrorProtocol.parseVars(html, embed)
        val apiJson = HttpKit.getJson(
            GdMirrorProtocol.apiUrl(vars),
            mapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to embed.pageUrl),
            API_BUDGET_MS,
        ) ?: return@coroutineScope emptyList()
        val files = GdMirrorProtocol.parseFiles(apiJson)
            .sortedByDescending { GdMirrorProtocol.isHindiName(it.fileName) }
            .take(MAX_SLUGS)
        if (files.isEmpty()) return@coroutineScope emptyList()
        val sem = Semaphore(2)
        files.map { f ->
            async {
                sem.acquire()
                try {
                    resolveSlug(vars, f)
                } finally {
                    sem.release()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun resolveSlug(vars: GdMirrorProtocol.Vars, file: GdMirrorProtocol.GdFile): List<GdMirrorStream> {
        val helperBody = HttpKit.postForm(
            "${vars.player}/embedhelper2.php",
            mapOf("sid" to file.slug, "UserFavSite" to "", "currentDomain" to vars.playerHost),
            mapOf("User-Agent" to UA, "Accept" to "*/*", "Referer" to vars.player + "/"),
            HELPER_BUDGET_MS,
        ) ?: return emptyList()
        val out = mutableListOf<GdMirrorStream>()
        for (m in GdMirrorProtocol.parseMirrors(helperBody).take(MAX_MIRRORS)) {
            val html = HttpKit.get(
                m.url,
                mapOf("User-Agent" to UA, "Accept" to "text/html,*/*", "Referer" to vars.player + "/"),
                MIRROR_BUDGET_MS,
            ) ?: continue
            GdMirrorProtocol.extractHls(html, m.base).forEach { url ->
                val hindi = GdMirrorProtocol.isHindiName(file.fileName)
                out.add(
                    GdMirrorStream(
                        "GDMirror (${m.site}${if (hindi) " Hindi" else ""})",
                        url, file.fileName, m.url,
                    )
                )
            }
            if (out.size >= 3) break
        }
        return out
    }
}

/** Pure GDMirror wire logic: embed/vars/api parsing, mirror mapping, packed-player unpack. */
internal object GdMirrorProtocol {

    /** Site-wide static key for keyless embeds (the player page ships an empty myKey when the parent omits one). */
    internal const val FALLBACK_KEY = "e11a7debaaa4f5d25b671706ffe4d2acb56efbd4"

    internal data class Embed(val pageUrl: String, val kind: String, val id: String, val season: String?, val episode: String?, val key: String?)
    internal data class Vars(val api: String, val player: String, val playerHost: String, val idType: String, val id: String, val season: String?, val epname: String?, val key: String)
    internal data class GdFile(val slug: String, val fileName: String)
    internal data class Mirror(val site: String, val base: String, val url: String)

    /** Parse an iqsmartgames embed URL: /embed/movie/{id} or /embed/tv/{id}/{s}/{e} plus ?key=. */
    internal fun parseEmbed(url: String): Embed? {
        val path = Regex("""/embed/(movie|tv)/([^/?#]+)(?:/(\d+)(?:/(\d+))?)?""").find(url) ?: return null
        val key = Regex("""[?&]key=([^&#]+)""").find(url)?.groupValues?.get(1)
        return Embed(url, path.groupValues[1], path.groupValues[2], path.groupValues[3].ifEmpty { null },
            path.groupValues[4].ifEmpty { null }, key?.ifEmpty { null })
    }

    private fun jsVar(page: String, name: String): String? =
        Regex("""(?:let|var|const)\s+$name\s*=\s*"([^"]*)"""").find(page)?.groupValues?.get(1)

    /** Embed-page JS vars with URL-derived fallbacks so one missing var never kills the chain. */
    internal fun parseVars(page: String, embed: Embed): Vars {
        val id = jsVar(page, "FinalID")?.ifEmpty { null } ?: embed.id
        val idType = jsVar(page, "idType")?.ifEmpty { null }
            ?: if (id.startsWith("tt")) "imdbid" else "tmdbid"
        val key = jsVar(page, "myKey")?.ifEmpty { null } ?: embed.key?.ifEmpty { null } ?: FALLBACK_KEY
        val api = jsVar(page, "api_url")?.trimEnd('/')?.ifEmpty { null } ?: DEF_API
        val player = jsVar(page, "player_base")?.trimEnd('/')?.ifEmpty { null } ?: DEF_PLAYER
        val season = jsVar(page, "season")?.ifEmpty { null } ?: embed.season
        val epname = jsVar(page, "epname")?.ifEmpty { null } ?: embed.episode
        val host = Regex("""^https?://[^/]+""").find(player)?.value?.substringAfter("://").orEmpty()
        return Vars(api, player, host, idType, id, season, epname, key)
    }

    internal const val DEF_API = "https://streams.iqsmartgames.com"
    internal const val DEF_PLAYER = "https://pro.iqsmartgames.com"

    /** mymovieapi / myseriesapi URL for the resolved vars. */
    internal fun apiUrl(v: Vars): String {
        val enc: (String) -> String = { URLEncoder.encode(it, "UTF-8") }
        return if (v.season != null || v.epname != null) {
            "${v.api}/myseriesapi?${v.idType}=${enc(v.id)}&season=${enc(v.season.orEmpty())}" +
                "&epname=${enc(v.epname.orEmpty())}&key=${enc(v.key)}"
        } else {
            "${v.api}/mymovieapi?${v.idType}=${enc(v.id)}&key=${enc(v.key)}"
        }
    }

    /** `data` array -> (fileslug, filename), blanks dropped. */
    internal fun parseFiles(json: JSONObject): List<GdFile> {
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<GdFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = o.optString("fileslug").trim().takeIf { it.isNotEmpty() } ?: continue
            out.add(GdFile(slug, o.optString("filename")))
        }
        return out
    }

    internal fun isHindiName(name: String?): Boolean = name?.contains("hindi", ignoreCase = true) == true

    /** embedhelper2 response -> mirror embeds in sources order: siteUrl + base64(mresult)[siteKey]. */
    internal fun parseMirrors(body: String): List<Mirror> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val sources = root.optJSONObject("sources") ?: return emptyList()
        val mresult = runCatching {
            JSONObject(String(Base64.getDecoder().decode(root.optString("mresult")), Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Mirror>()
        val keys = sources.keys()
        while (keys.hasNext()) {
            val siteKey = keys.next()
            val o = sources.optJSONObject(siteKey) ?: continue
            val code = mresult.optString(siteKey).takeIf { it.isNotBlank() } ?: continue
            val base = o.optString("siteUrl").takeIf { it.startsWith("http") } ?: continue
            val site = o.optString("friendlyName").ifBlank { siteKey }
            out.add(Mirror(site, base, base + code))
        }
        // JSONObject key order is not contractual: verified player families first, then stable key order.
        return out.sortedWith(compareBy({ mirrorPriority(it.site) }, { it.site }))
    }

    private fun mirrorPriority(site: String): Int = when (site.lowercase()) {
        "streamhg" -> 0
        "earnvids" -> 1
        else -> 2
    }

    /** Mirror player page -> HLS master urls (hls4, hls3, hls2 preference), relative resolved against the page. */
    internal fun extractHls(html: String, mirrorBase: String): List<String> {
        val start = html.indexOf("eval(function(p,a,c,k,e,d)").takeIf { it >= 0 } ?: return emptyList()
        val end = html.indexOf(".split('|')))", start).takeIf { it > start } ?: return emptyList()
        val unpacked = deanUnpack(html.substring(start, end + ".split('|')))".length)) ?: return emptyList()
        val found = mutableListOf<String>()
        for (tag in listOf("hls4", "hls3", "hls2")) {
            Regex(""""$tag"\s*:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }?.let { found.add(absolutize(mirrorBase, it)) }
        }
        return found.distinct()
    }

    private fun absolutize(base: String, ref: String): String {
        if (ref.startsWith("http", ignoreCase = true)) return ref
        if (ref.startsWith("//")) return "https:$ref"
        val schemeHost = Regex("""^https?://[^/]+""").find(base)?.value ?: return ref
        return if (ref.startsWith("/")) "$schemeHost$ref" else "$schemeHost/$ref"
    }

    /** Dean Edwards packer unpack: eval(function(p,a,c,k,e,d){...}('P',A,C,'K'.split('|'))). Null-safe. */
    internal fun deanUnpack(block: String): String? = runCatching {
        val m = Regex("""\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*)'\.split\('\|'\)\)\)""")
            .find(block) ?: return null
        val p = unescapeJs(m.groupValues[1])
        val a = m.groupValues[2].toInt()
        val c = m.groupValues[3].toInt()
        val k = m.groupValues[4].split('|')
        fun eKey(n: Int): String {
            var x = n
            var s = ""
            do {
                val d = x % a
                s = (if (d > 35) (d + 29).toChar().toString() else d.toString(a)) + s
                x /= a
            } while (x > 0)
            return s
        }
        var s = p
        for (i in c - 1 downTo 0) {
            val rep = k.getOrNull(i).orEmpty()
            if (rep.isEmpty()) continue
            s = Regex("\\b${Regex.escape(eKey(i))}\\b").replace(s, java.util.regex.Matcher.quoteReplacement(rep))
        }
        s
    }.getOrNull()

    /** Unescape a JS string literal body (no surrounding quotes): \\ \' \" \/ \n \r \t \xNN \uNNNN. */
    internal fun unescapeJs(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i + 1 >= s.length) {
                out.append(ch)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                '\\' -> out.append('\\')
                '\'' -> out.append('\'')
                '"' -> out.append('"')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'x' -> {
                    out.append(s.substring(i + 2, minOf(i + 4, s.length)).toIntOrNull(16)?.toChar() ?: e)
                    i += 2
                }
                'u' -> {
                    out.append(s.substring(i + 2, minOf(i + 6, s.length)).toIntOrNull(16)?.toChar() ?: e)
                    i += 4
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }

}

/** True for a directly playable stream URL. */
internal fun isMediaUrl(u: String): Boolean {
    val l = u.lowercase()
    // `.urlset/master.txt` is standard HLS manifest naming on these CDNs; bare .txt is not a stream.
    return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".mpd") ||
        l.contains(".webm") || l.contains(".mkv") || l.contains(".urlset/")
}

/** Modiplay (Cineverse backend) extractor. Embed/proxy pages carry a static tokenized HLS relay. */
object ModiplayExtractor {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    suspend fun extract(pageUrl: String): List<VidemSource> {
        var current = pageUrl
        repeat(2) {
            val html = HttpKit.get(
                current,
                mapOf("User-Agent" to UA, "Referer" to pageUrl),
                6_000L,
            ) ?: return emptyList()
            // The player iframe proxies the chosen platform; its page carries the real HLS master.
            // The iframe src keeps HTML entities (&amp;) - a browser decodes them before requesting, so must we.
            val frame = Regex(
                """<iframe[^>]+src=["']([^"']*proxy\.php[^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            if (frame != null) {
                val resolved = proxyStreams(MultiSourcePuller.resolveRelative(current, frame), current)
                if (resolved.isNotEmpty()) {
                    return resolved.map { VidemSource("Cineverse", it, "", true, mapOf("Referer" to current)) }
                }
            }
            val streams = parseModiplayStreams(html, current)
            if (streams.isNotEmpty()) {
                return streams.map { VidemSource("Cineverse", it, "", true, mapOf("Referer" to current)) }
            }
            current = frame?.let { MultiSourcePuller.resolveRelative(current, it) } ?: return emptyList()
        }
        return emptyList()
    }

    /** Fetch a proxy player page and pull the HLS master it relays (encoded url= param or inline). */
    private suspend fun proxyStreams(proxyUrl: String, referer: String): List<String> {
        val html = HttpKit.get(proxyUrl, mapOf("User-Agent" to UA, "Referer" to referer), 6_000L)
            ?: return emptyList()
        val out = LinkedHashSet<String>()
        Regex("""[?&]url=([^"'&\s]+)""").findAll(html).forEach { m ->
            runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
                .getOrNull()
                ?.takeIf { it.startsWith("http") && it.contains(".m3u8", ignoreCase = true) }
                ?.let { out.add(it) }
        }
        MultiSourcePuller.extractStreamUrl(html)?.let { out.add(it) }
        return out.toList()
    }
}

/** Stream URLs from a modiplay page: direct HLS first, relay second. Pure. */
internal fun parseModiplayStreams(html: String, baseUrl: String): List<String> {
    if (html.isBlank()) return emptyList()
    val clean = html.replace("\\/", "/").replace("&amp;", "&")
    val out = LinkedHashSet<String>()
    Regex("""directSrc\s*[:=]\s*["']([^"']+)["']""").findAll(clean).forEach { m ->
        out.add(MultiSourcePuller.resolveRelative(baseUrl, m.groupValues[1].trim()))
    }
    Regex("""["']src["']\s*:\s*["']([^"']*stream_proxy\.php[^"']*)["']""").findAll(clean).forEach { m ->
        out.add(MultiSourcePuller.resolveRelative(baseUrl, m.groupValues[1].trim()))
    }
    // Relay relay shape: `var src="...stream_proxy.php?url=..."` (var form, not the JSON key above).
    Regex("""(?:var|let|const)\s+src\s*=\s*["']([^"']*stream_proxy\.php[^"']*)["']""").findAll(clean).forEach { m ->
        out.add(MultiSourcePuller.resolveRelative(baseUrl, m.groupValues[1].trim()))
    }
    Regex("""EMBED_URL\s*=\s*['"]([^'"]+)['"]""").findAll(clean).forEach { m ->
        // Skip bare embed prefixes ("https://host/e/") - the real id is concatenated at runtime.
        val v = m.groupValues[1].trim()
        if (!v.endsWith("/")) out.add(MultiSourcePuller.resolveRelative(baseUrl, v))
    }
    return out.filter { it.startsWith("http") }.distinct()
}

/** VidSrc (vsembed) resolver. The embed shell exposes a JSON endpoint with the player URL. */
object VsEmbedExtractor {

    suspend fun resolve(pageUrl: String, type: String, season: Int?, episode: Int?): String? {
        val id = Regex("""tt\d{6,10}""").find(pageUrl)?.value
            ?: Regex("""(\d{2,10})""").findAll(pageUrl).map { it.groupValues[1] }.lastOrNull()
            ?: return null
        val base = Regex("""^https?://[^/]+""").find(pageUrl)?.value ?: return null
        val kind = if (type == "tv") "tv" else "movie"
        val candidates = listOf(
            "$base/vs_src.php?type=$kind&id=$id&s=${season ?: 1}&e=${episode ?: 1}",
            "$base/vs_src.php?type=$kind&id=$id&season=${season ?: 1}&episode=${episode ?: 1}",
            "$base/vs_src.php?type=$kind&id=$id",
        )
        for (u in candidates.distinct()) {
            val src = HttpKit.getJson(u, budgetMs = 5_000L)?.optString("src")
                ?.takeIf { it.startsWith("http") } ?: continue
            return src
        }
        return null
    }
}

/** Vidout extractor. Streams come from a public endpoint keyed by TMDB id. */
object VidoutExtractor {

    private const val BASE_URL = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls"

    suspend fun extract(tmdbId: String, type: String, season: Int?): List<String> {
        if (!tmdbId.matches(Regex("""\d{2,10}"""))) return emptyList()
        val path = if (type == "tv") "tv/$tmdbId/S${season ?: 1}.json" else "movie/$tmdbId"
        val body = HttpKit.get("$BASE_URL/$path", budgetMs = 6_000L) ?: return emptyList()
        return parseVidoutBody(body)
    }
}

/** Stream URLs from a vidout endpoint body (JSON sources or plain URL lines). Pure. */
internal fun parseVidoutBody(body: String): List<String> {
    val t = body.trim()
    if (t.isEmpty()) return emptyList()
    if (t.startsWith("{") || t.startsWith("[")) {
        val found = LinkedHashSet<String>()
        fun grab(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val keys = v.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val item = v.opt(k)
                        if (item is String && item.startsWith("http") &&
                            (k.equals("url", true) || k.equals("file", true) || k.equals("src", true) ||
                                k.equals("link", true) || k.equals("stream", true))
                        ) found.add(item)
                        grab(item)
                    }
                }
                is JSONArray -> for (i in 0 until v.length()) grab(v.opt(i))
                is String -> if (v.startsWith("http") && isMediaUrl(v)) found.add(v)
            }
        }
        runCatching {
            grab(if (t.startsWith("[")) JSONArray(t) else JSONObject(t))
        }
        val media = found.filter { isMediaUrl(it) }
        if (media.isNotEmpty()) return media.distinct()
    }
    return t.lines().map { it.trim() }
        .filter { it.startsWith("http") && isMediaUrl(it) }
        .distinct()
}

/** Bingr extractor. The stream endpoint needs a server id; known ids are tried in order. */
object BingrExtractor {

    private const val API = "https://api.bingr.one/api/stream"
    internal val SRVS = listOf("s40", "s70", "s62", "s63")

    suspend fun extract(
        tmdbId: String,
        type: String,
        title: String,
        year: String,
        season: Int?,
        episode: Int?,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ): List<VidemSource> {
        if (!tmdbId.matches(Regex("""\d{2,10}"""))) return emptyList()
        val out = ArrayList<VidemSource>()
        for (srv in SRVS) {
            val resp = HttpKit.postJson(
                API,
                bingrBody(srv, type, tmdbId, title, year, season, episode),
                mapOf("Content-Type" to "application/json", "Origin" to "https://bingr.one"),
                4_000L,
            ) ?: continue
            val root = runCatching { JSONObject(resp) }.getOrNull() ?: continue
            root.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val url = arr.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") } ?: continue
                    out.add(VidemSource("Bingr", url, "", url.contains(".m3u8", true)))
                }
            }
            root.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url").ifBlank { o.optString("file") }
                    if (url.startsWith("http")) {
                        onSubtitle(NxshaSubtitle(o.optString("lang").ifBlank { o.optString("label").ifBlank { "sub" } }, url))
                    }
                }
            }
            if (out.size >= 4) break
        }
        return out.distinctBy { it.url }
    }
}

/** Bingr stream request body. Pure. */
internal fun bingrBody(
    srv: String,
    type: String,
    id: String,
    title: String,
    year: String,
    season: Int?,
    episode: Int?,
): String {
    val o = JSONObject()
    o.put("srv", srv)
    o.put("t", if (type == "tv") "tv" else "movie")
    o.put("id", id)
    o.put("query", JSONObject().put("title", title).put("year", year))
    if (type == "tv") {
        o.put("season", season ?: 1)
        o.put("episode", episode ?: 1)
    }
    return o.toString()
}

/** Filmu box extractor. Scrapers need the bundled API key. */
object FilmuExtractor {

    private const val BASE_URL = "https://box.filmu.in"
    private const val API_KEY = "09eb429913afb6b1cc90f23746f41fb3279aed77726c625c40672b81444c0bac"
    internal val PROVS = listOf("Vaplayer", "Ainary", "ShowBox", "MovieBoxV2", "NoTorrent")

    suspend fun extract(
        imdbId: String?,
        tmdbId: String?,
        type: String,
        title: String,
        year: String,
        season: Int?,
        episode: Int?,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ): List<VidemSource> {
        val id = imdbId?.takeIf { it.startsWith("tt") } ?: tmdbId ?: return emptyList()
        val kind = if (type == "tv") "tv" else "movie"
        val out = ArrayList<VidemSource>()
        for (prov in PROVS) {
            val url = buildString {
                append("$BASE_URL/scrape/$prov/$kind/$id?title=${URLEncoder.encode(title, "UTF-8")}")
                append("&tmdbId=${tmdbId.orEmpty()}&imdbId=${imdbId.orEmpty()}&year=$year")
                if (type == "tv") append("&season=${season ?: 1}&episode=${episode ?: 1}")
            }
            val resp = HttpKit.get(url, mapOf("x-api-key" to API_KEY), 4_000L) ?: continue
            val root = runCatching { JSONObject(resp) }.getOrNull() ?: continue
            out.addAll(parseFilmuSources(root, prov))
            root.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uri = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                    onSubtitle(NxshaSubtitle(o.optString("label").ifBlank { o.optString("lang").ifBlank { "sub" } }, uri))
                }
            }
        }
        return out.distinctBy { it.url }
    }
}

/** Filmu scraper sources mapped to streams. Pure. */
internal fun parseFilmuSources(root: JSONObject, prov: String): List<VidemSource> {
    val arr = root.optJSONArray("sources") ?: return emptyList()
    val out = ArrayList<VidemSource>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        var url = o.optString("url").ifBlank { o.optString("file") }.trim()
        if (url.startsWith("/")) url = "https://box.filmu.in$url"
        if (!url.startsWith("http")) continue
        val quality = o.optString("quality").ifBlank { o.optString("label") }
        val label = if (quality.contains("hindi", true)) "Filmu ($prov Hindi)" else "Filmu ($prov)"
        val headers = LinkedHashMap<String, String>()
        val headerObj = o.optJSONObject("headers")
        if (headerObj != null) {
            val keys = headerObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers[k] = headerObj.optString(k)
            }
        }
        if (!headers.containsKey("Referer")) headers["Referer"] = "https://box.filmu.in/"
        out.add(VidemSource(label, url, quality, url.contains(".m3u8", true), headers))
    }
    return out
}

/** VidBolt scraper extractor. Endpoints need no auth. */
object VidboltExtractor {

    private const val BASE_URL = "https://scraper.vidbolt.xyz"
    internal val SCRAPERS = listOf("Quasar", "Callisto", "Saffron", "Ninetta")

    suspend fun extract(
        imdbId: String?,
        tmdbId: String?,
        type: String,
        title: String,
        year: String,
        season: Int?,
        episode: Int?,
        onSubtitle: (NxshaSubtitle) -> Unit,
    ): List<VidemSource> {
        val tmdb = tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) } ?: return emptyList()
        val id = imdbId?.takeIf { it.startsWith("tt") } ?: "tmdb$tmdb"
        val kind = if (type == "tv") "tv" else "movie"
        val out = ArrayList<VidemSource>()
        for (s in SCRAPERS) {
            val url = buildString {
                append("$BASE_URL/scrape/$s/$kind/$id?tmdbId=$tmdb")
                if (title.isNotBlank()) append("&title=${URLEncoder.encode(title, "UTF-8")}")
                if (year.isNotBlank()) append("&year=$year")
                if (type == "tv") append("&season=${season ?: 1}&episode=${episode ?: 1}")
            }
            val resp = HttpKit.get(url, budgetMs = 4_000L) ?: continue
            val root = runCatching { JSONObject(resp) }.getOrNull() ?: continue
            root.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val stream = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val headers = LinkedHashMap<String, String>()
                    val headerObj = o.optJSONObject("headers")
                    if (headerObj != null) {
                        val keys = headerObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            headers[k] = headerObj.optString(k)
                        }
                    }
                    out.add(VidemSource("VidBolt (${o.optString("name").ifBlank { s }})", stream,
                        o.optString("quality"), stream.contains(".m3u8", true), headers))
                }
            }
            root.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uri = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                    onSubtitle(NxshaSubtitle(o.optString("lang").ifBlank { o.optString("label").ifBlank { "sub" } }, uri))
                }
            }
            if (out.size >= 4) break
        }
        return out.distinctBy { it.url }
    }
}

/** 2embed page helper: the static iframe target lacks the title id; append the known one. Pure. */
internal fun resolveTwoEmbedTarget(html: String, imdbId: String?): String? {
    if (html.isBlank()) return null
    val target = Regex("""data-src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        ?: Regex("""<iframe[^>]+src\s*=\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        ?: return null
    if (!imdbId.isNullOrBlank() && !target.contains(imdbId) && target.endsWith("/")) return target + imdbId
    return target
}

