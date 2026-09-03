package com.multimovies
/**

 * FILE: SharedServices.kt â€” Multimovies shared services (reusable, site-agnostic).
 *
 *  - [HttpKit]        shared HTTP client (get / getJson / post + retry).
 *  - [CryptoJs]       CryptoJS-AES envelope helpers (OpenSSL "Salted__"
 *                     format) used by the encrypted APIs in
 *                     sources/ExternalSources.kt.
 *  - [TmdbService]    TMDB metadata: search, meta, season data, the
 *                     IMDB -> TMDB id fallback.
 *  - Search ranking  pure TMDB search relevance ranking + poster upgrades
 *                     (top-level functions: relevanceOf, titleDistance,
 *                     upgradePosterUrl, ...).
 *
 * Site-specific code lives in plugin/MultimoviesPlugin.kt; third-party stream
 * APIs live in sources/ExternalSources.kt.
 */

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * One shared OkHttp stack for the id-based extractors (Nxsha, Shows, VidEm).
 * Each previously built its own client and re-implemented
 * httpGet/httpGetJson/httpPost — this object kills the duplication.
 *
 * The client carries a per-host cookie jar. Callers pass their full header set
 * (including UA) as [headers]; the client's own connect/read timeouts are a
 * high hard cap (12 s), while [budgetMs] wraps each call in
 * `withTimeoutOrNull`.
 */
internal object HttpKit {

    private val cookieJar = object : okhttp3.CookieJar {
        private val cookies = mutableMapOf<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, list: List<okhttp3.Cookie>) {
            cookies[url.host] = list
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            return cookies[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** GET [url] with the given [headers], returning the response body text,
     *  or null on failure / timeout after [budgetMs]. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), budgetMs: Long = 8_000L): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(budgetMs) {
                runCatching {
                    val req = Request.Builder().url(url).get()
                    headers.forEach { (k, v) -> req.header(k, v) }
                    client.newCall(req.build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        resp.body.string()
                    }
                }.getOrNull()
            }
        }

    /** GET [url] and parse the response as JSON, or null. */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), budgetMs: Long = 8_000L): JSONObject? =
        get(url, headers, budgetMs)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }

    /** POST [url] with an empty body and the given [headers] + optional
     *  [referer] / [origin]. Returns the response body text, or null on
     *  failure / timeout after [budgetMs]. */
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        origin: String? = null,
        budgetMs: Long = 8_000L,
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) {
            runCatching {
                val req = Request.Builder().url(url).post("".toRequestBody(null))
                headers.forEach { (k, v) -> req.header(k, v) }
                if (!referer.isNullOrBlank()) req.header("Referer", referer)
                if (!origin.isNullOrBlank()) req.header("Origin", origin)
                client.newCall(req.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body.string()
                }
            }.getOrNull()
        }
    }
}

/**
 * Shared CryptoJS-compatible AES helpers for the OpenSSL "Salted__" envelope
 * format (`CryptoJS.AES.encrypt(data, passphrase)` / `.decrypt(...)` with a
 * string passphrase). Used by extractors whose web players ship that exact
 * client-side crypto.
 *
 * The encrypt side mirrors what CryptoJS does internally: random 8-byte salt,
 * EVP_BytesToKey(MD5) key+iv derivation, AES-256-CBC/PKCS5, output =
 * base64("Salted__" + salt + ciphertext).
 */
internal object CryptoJs {

    /** OpenSSL EVP_BytesToKey (MD5 variant), matching CryptoJS's default KDF. */
    fun evpKdf(password: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val total = keyLen + ivLen
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        while (out.size() < total) {
            val toHash = ByteArrayOutputStream().apply {
                write(block); write(password)
                if (salt != null) write(salt)
            }.toByteArray()
            block = md.digest(toHash)
            out.write(block)
        }
        val keyIv = out.toByteArray()
        return keyIv.copyOfRange(0, keyLen) to keyIv.copyOfRange(keyLen, total)
    }

    /** Lenient base64 decode matching Node's Buffer.from(s, "base64"): silently
     *  drops non-base64 characters and pads partial trailing groups (no padding
     *  required). Used by the OpenSSL envelope decoder. */
    fun base64DecodeLenient(s: String): ByteArray {
        val b64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val valid = s.filter { it in b64Chars }
        val padding = (4 - (valid.length % 4)) % 4
        return Base64.getDecoder().decode(valid + "=".repeat(padding))
    }

    /** Decrypt a base64 OpenSSL-Salted AES ciphertext; returns UTF-8 plaintext
     *  or null when the payload is malformed / undecryptable. */
    fun aesDecryptCryptoJs(cipherBase64: String, passphrase: String): String? {
        val all = runCatching { base64DecodeLenient(cipherBase64) }.getOrNull() ?: return null
        val (salt, cipher) = if (all.size >= 16 && String(all.copyOfRange(0, 8), StandardCharsets.ISO_8859_1) == "Salted__") {
            all.copyOfRange(8, 16) to all.copyOfRange(16, all.size)
        } else null to all
        val (key, iv) = evpKdf(passphrase.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return runCatching {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(cipher), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /** Encrypt [plaintext] the way CryptoJS.AES.encrypt(plaintext, passphrase)
     *  does and return its standard-base64 string form ("Salted__" envelope). */
    fun aesEncryptCryptoJs(plaintext: String, passphrase: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpKdf(passphrase.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream(8 + salt.size + encrypted.size)
        out.write("Salted__".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(salt)
        out.write(encrypted)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

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

/**
 * Pure, JVM-testable helpers shared by the provider's search pipeline, poster
 * handling and fetch retry logic. No CloudStream/Android runtime dependency, so
 * the unit tests exercise them on the plain JVM.
 *
 * Kept out of [MultimoviesProvider] so the provider file stays focused on the
 * MainAPI wiring (search / mainPage / load / loadLinks) instead of carrying
 * ~250 lines of string/regex utilities.
 */

/** Unicode-aware normalization (lowercase; letters, marks, digits only) so
 *  Hindi/Devanagari queries survive: vowel signs like ि/ी are combining marks
 *  (Unicode \p{M}), not letters, so they must be kept or scripts get mangled. */
private val NON_ALNUM_UNICODE = Regex("""[^\p{L}\p{M}\p{N}]+""")

internal fun normalizeTitle(t: String): String =
    t.lowercase().replace("'", "").replace("’", "").trim()
        .replace(NON_ALNUM_UNICODE, " ").trim()

/** Alternative spellings of a title that a Dooplay site may store, so slug
 *  guessing and the site-search fallback survive "&" vs "and", dropped
 *  apostrophes ("King's Man" -> "Kings Man") and stray punctuation such as
 *  "(", ")", ":", ";", "," that a WordPress search treats literally. */
internal fun titleVariants(title: String): List<String> {
    val andWord = Regex("\\band\\b", RegexOption.IGNORE_CASE)
    return buildList {
        add(title)
        if (title.contains('&')) add(title.replace("&", " and "))
        if (andWord.containsMatchIn(title)) add(title.replace(andWord, "&"))
        val noApostrophe = title.replace("'", "").replace("’", "")
        if (noApostrophe != title) add(noApostrophe)
        add(title.replace(Regex("[^\\p{L}\\p{M}\\p{N} ]+"), " "))
    }.map { it.replace(Regex("\\s+"), " ").trim() }.distinct()
}

/** Classic Levenshtein edit distance (two-row implementation). */
internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = cur
        cur = tmp
    }
    return prev[b.length]
}

/** Coarse title match distance for validating a fetched page / search hit
 *  against the queried title: 0 identical (also when they differ only by the
 *  "&"/"and" join or a dropped apostrophe), 1 when one is a prefix of the
 *  other, else 2. */
internal fun titleDistance(itemTitle: String, target: String): Int {
    val a = normalizeTitle(itemTitle)
    val b = normalizeTitle(target)
    return when {
        a == b -> 0
        // TMDB spells "Locke & Key" where the site says "Locke and Key";
        // dropping the "and" join on either side makes them identical.
        a.replace(" and ", " ") == b && a.contains(" and ") -> 0
        b.replace(" and ", " ") == a && b.contains(" and ") -> 0
        a.startsWith(b) || b.startsWith(a) -> 1
        else -> 2
    }
}

/** Query words that carry meaning (>=2 chars); digit tokens such as years are
 *  kept so "iron man 2010" can match a release year too. */
internal fun significantQueryTokens(query: String): List<String> =
    normalizeTitle(query).split(' ').filter { it.length >= 2 }.distinct()

/** Best fuzzy match score for one query [token] against the title's tokens:
 *  1.0 exact or substring in either direction (long words only for the
 *  query-contains-title direction to avoid false hits like "spiderman" vs
 *  "Man"), 0.7 within a small Levenshtein tolerance (typos), else 0.0. */
internal fun tokenMatchScore(token: String, titleTokens: List<String>): Double {
    if (titleTokens.any { it == token || it.contains(token) }) return 1.0
    if (titleTokens.any { token.contains(it) && it.length >= 4 }) return 1.0
    val tolerance = if (token.length <= 5) 1 else 2
    if (titleTokens.any { levenshtein(it, token) <= tolerance }) return 0.7
    return 0.0
}

/** Relevance verdict for one search candidate. [score] is in [0,1];
 *  [allTokensMatched] drives the hard "remove every other" gate. */
internal data class Relevance(val score: Double, val allTokensMatched: Boolean)

/** Fuzzy relevance of [query] against a candidate [title] (+ optional release
 *  [year], which pure-digit tokens may match). Score = weighted mean of
 *  per-token matches with a small penalty for bloated titles, clamped [0,1]. */
internal fun relevanceOf(query: String, title: String, year: String?): Relevance {
    val qNorm = normalizeTitle(query)
    if (qNorm.isEmpty()) return Relevance(0.0, false)
    val tNorm = normalizeTitle(title)
    if (qNorm == tNorm) return Relevance(1.0, true)

    val qTokens = significantQueryTokens(query)
    if (qTokens.isEmpty()) {
        // Single-character query: substring containment is the whole signal.
        val contained = tNorm.contains(qNorm)
        return Relevance(if (contained) 1.0 else 0.0, contained)
    }
    val tTokens = tNorm.split(' ').filter { it.isNotEmpty() }

    var sum = 0.0
    var matchedAll = true
    for (token in qTokens) {
        var best = tokenMatchScore(token, tTokens)
        if (best < 1.0 && token.all { it.isDigit() } && year.orEmpty().contains(token)) best = 1.0
        if (best == 0.0) matchedAll = false
        sum += best
    }
    val extraTitleWords = (tTokens.size - qTokens.size).coerceAtLeast(0)
    val score = (sum / qTokens.size - 0.03 * minOf(extraTitleWords, 5)).coerceIn(0.0, 1.0)
    return Relevance(score, matchedAll)
}

/** TMDB image CDN size prefixes that are too small for a poster. Anything from
 *  w92 to w500 is upgraded to `original`; w780/w1280 are kept (already good). */
private val TMDB_SIZE_REGEX = Regex("""/(w92|w154|w185|w342|w500|w780|w1280)/""")

/** Amazon (IMDB) CDN thumbnail suffix, e.g. `_SX250` / `_SY450`. Upgraded to a
 *  larger variant so posters aren't pixelated. */
private val AMAZON_SIZE_REGEX = Regex("""_SX\d{2,4}(?=\.|_|$)""", RegexOption.IGNORE_CASE)

/** Upgrades a thumbnail URL to the full-resolution image by stripping only
 *  genuine thumbnail resize markers (-WxH where W,H are small, TMDB CDN size
 *  prefixes, Amazon _SX* suffixes) and query-size params. Conservative: never
 *  strips -scaled (a real WordPress file variant) and only drops -WxH when both
 *  dims <= 500; otherwise returns the original so a poster is always shown.
 *  Pure function, used for both search and detail posters. */
internal fun upgradePosterUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var fixed = if (url.startsWith("//")) "https:$url" else url
    fixed = stripSmallSizeSuffix(fixed)
    fixed = TMDB_SIZE_REGEX.replace(fixed) { m ->
        when (m.groupValues[1]) {
            "w92", "w154", "w185", "w342", "w500" -> "/original/"
            else -> m.value
        }
    }
    fixed = AMAZON_SIZE_REGEX.replace(fixed, "_SX500")
    val qIdx = fixed.indexOf("?")
    if (qIdx >= 0) {
        val base = fixed.substring(0, qIdx)
        val kept = fixed.substring(qIdx + 1).split("&").mapNotNull { p ->
            val k = p.substringBefore("=").trim()
            if (k.equals("resize", ignoreCase = true) || k.equals("w", ignoreCase = true)
                || k.equals("width", ignoreCase = true)
                || k.equals("h", ignoreCase = true)
                || k.equals("height", ignoreCase = true)
                || k.equals("fit", ignoreCase = true)
                || k.equals("im", ignoreCase = true)
                || k.equals("q", ignoreCase = true)
                || k.equals("quality", ignoreCase = true)
            ) null else p
        }.joinToString("&")
        fixed = if (kept.isBlank()) base else "$base?$kept"
    }
    return fixed
}

/** True when [url] still carries a resize/thumbnail marker that [upgradePosterUrl]
 *  could not remove (e.g. a large -WxH variant that is still smaller than the
 *  original, a TMDB CDN small-size prefix, or an Amazon _SX* suffix). Used to
 *  decide when a search result should be overridden with a known-good
 *  full-resolution poster from TMDB. */
internal fun isThumbnailish(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return Regex("""-\d{2,4}x\d{2,4}""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""/(w92|w154|w185|w342|w500)/""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""_SX\d{2,4}(?=\.|_|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        || Regex("""[?&](resize|w|h|width|height|fit|im|q|quality)=""", RegexOption.IGNORE_CASE).containsMatchIn(url)
}

/** Strips a "-WxH" size marker (e.g. -300x450) only when it represents a
 *  small thumbnail: both dims <= 500. Left intact: -scaled (a real WP file
 *  variant), large dims (the image is already full-res). Returns [url] unchanged
 *  when the marker dims exceed the thumbnail threshold or there is none. */
private fun stripSmallSizeSuffix(url: String): String {
    val m = Regex("""-(\d{2,4})x(\d{2,4})""", RegexOption.IGNORE_CASE).find(url) ?: return url
    val w = m.groupValues[1].toInt()
    val h = m.groupValues[2].toInt()
    if (w > 500 || h > 500) return url
    return url.removeRange(m.range)
}

/** Pull the first `tt\d{7,8}` IMDB id from any text (URL, JSON, HTML). Used
 *  to extract the IMDB id from dooplayer admin-ajax embed URLs, which always
 *  carry the id (e.g. tt1979320) inside the embed_url field. */
internal fun extractImdbIdFromUrl(text: String?): String? =
    text?.let { Regex("""tt\d{7,8}""").find(it)?.value }

/**
 * Retry [fetch] up to [attempts] times. If a result is "blocked" (e.g. a Cloudflare
 * challenge page rather than the real document), [onBlocked] is invoked and the next
 * attempt is tried. If every attempt fails or is blocked, throws an exception built by
 * [failureMessage].
 *
 * This is the core of the detail-page fix: it tries a fixed, finite number of solvers
 * and then surfaces a single error instead of CloudStream's LoadFragment retrying
 * load() forever (the "keep refreshing" loop). Kept CloudStream-free so it is
 * unit-testable on the JVM without an Android/WebView runtime.
 */
internal suspend fun <T> retryUntilSolved(
    attempts: Int,
    fetch: suspend (attempt: Int) -> T,
    isBlocked: (T) -> Boolean,
    onBlocked: () -> Unit = {},
    failureMessage: (Throwable?) -> String,
): T {
    var lastErr: Throwable? = null
    repeat(attempts) { i ->
        try {
            val result = fetch(i)
            if (isBlocked(result)) {
                lastErr = IllegalStateException(failureMessage(null))
                onBlocked()
                return@repeat
            }
            return result
        } catch (e: Throwable) {
            lastErr = e
        }
    }
    throw IllegalStateException(failureMessage(lastErr))
}

/** True when [doc] is the site's Cloudflare interstitial rather than real content. */
internal fun isChallenge(doc: Document): Boolean =
    doc.body()?.text().orEmpty().let { bodyText ->
        val titleText = doc.selectFirst("title")?.text()?.lowercase() ?: ""
        bodyText.contains("just a moment", ignoreCase = true)
            || bodyText.contains("cf-mitigated", ignoreCase = true)
            || bodyText.contains("verify you are human", ignoreCase = true)
            || bodyText.contains("checking your browser", ignoreCase = true)
            || bodyText.contains("attention required", ignoreCase = true)
            || titleText.contains("just a moment", ignoreCase = true)
    }

