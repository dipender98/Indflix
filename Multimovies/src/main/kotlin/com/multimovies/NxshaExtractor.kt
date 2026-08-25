package com.multimovies

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
    private suspend fun apiGet(endpoint: String, payload: Map<String, Any?>, budgetMs: Long): JSONObject? =
        withTimeoutOrNull(budgetMs) {
            val q = NxshaProtocol.encodeData(payload)
            runCatching {
                val req = Request.Builder()
                    .url("$endpoint?q=$q")
                    .get()
                sharedHeaders.forEach { (k, v) -> req.header(k, v) }
                httpClient.newCall(req.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body.string()
                    val envelope = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                    NxshaProtocol.decodeData(envelope.optString("_hash"))
                }
            }.getOrNull()
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

        val label = "Nxsha/" + NxshaProtocol.shortServerName(server.name)
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
    private suspend fun resolveTmdbFromImdb(imdbId: String, type: String): String? =
        withTimeoutOrNull(LOOKUP_BUDGET_MS) {
            runCatching {
                val req = Request.Builder()
                    .url("$TMDB_PROXY_FIND/$imdbId?external_source=imdb_id")
                    .get()
                sharedHeaders.forEach { (k, v) -> req.header(k, v) }
                httpClient.newCall(req.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body.string()
                    val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                    // Prefer the array matching the requested type, but fall back
                    // to the other one when empty.
                    val primary = if (type == "movie") "movie_results" else "tv_results"
                    val secondary = if (type == "movie") "tv_results" else "movie_results"
                    listOf(primary, secondary).firstNotNullOfOrNull { key ->
                        obj.optJSONArray(key)?.optJSONObject(0)?.optString("id")
                            ?.takeIf { it.isNotBlank() && it != "null" }
                    }
                }
            }.getOrNull()
        }
}
