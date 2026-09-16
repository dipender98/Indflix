package com.indstream

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** Videasy multi-server source (api.speedracelight.com). The player backend fans out per route; the CDN route. carries a per-resolution HLS ladder up to 2160p. */
object VideasySource {

    private const val API = "https://api.speedracelight.com"
    private const val ORIGIN = "https://player.videasy.net"
    private const val REFERER = "https://player.videasy.net/"

    /** One player route plus the mediaType casing it answers to. */
    data class Route(
        val path: String,
        val movieType: String,
        val tvType: String,
        val moviesOnly: Boolean = false,
    )

    /** Live routes (probed): cdn answers "Movie"/"TV Series" with a 480p-2160p ladder. hdmovie 500s upstream (retired). */
    internal val ROUTES: List<Route> = listOf(
        Route("cdn", movieType = "Movie", tvType = "TV Series"),
        Route("m4uhd", movieType = "movie", tvType = "TV Series"),
        Route("lamovie", movieType = "Movie", tvType = "TV Series", moviesOnly = true),
    )

    /** Headers for API + playlist requests. */
    fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept" to "*/*",
        "Origin" to ORIGIN,
        "Referer" to REFERER,
    )

    /** Playback sends no Origin/Referer: the CDN 403s the player Origin, the mirror 403s the player Referer. */
    fun playbackHeaders(): Map<String, String> = emptyMap()

    /** FNV-1a with the cipher's final mix. */
    private fun fnv1a(s: String): Int {
        var t = 2166136261L
        for (ch in s) {
            t = (t xor ch.code.toLong()) * 16777619L and 0xFFFFFFFFL
        }
        return mix(t)
    }

    /** The cipher's murmur-style finalizer. */
    private fun mix(x0: Long): Int {
        var x = x0 and 0xFFFFFFFFL
        x = x xor (x ushr 16)
        x = x * 2246822507L and 0xFFFFFFFFL
        x = x xor (x ushr 13)
        x = x * 3266489909L and 0xFFFFFFFFL
        x = x xor (x ushr 16)
        return x.toInt()
    }

    private fun rotl(v: Int, t0: Int): Int {
        val t = t0 and 31
        if (t == 0) return v
        return (v shl t) or (v ushr (32 - t))
    }

    private fun imul(a: Int, b: Int): Int {
        return (a.toLong() * b.toLong() and 0xFFFFFFFFL).toInt()
    }

    /** 61-slot sparse state: written slots only (holes read as 0). */
    class State internal constructor(val slots: HashMap<Int, Int>, var acc: Int)

    private fun makeState(seed: String, mediaId: Int): State {
        val slots = HashMap<Int, Int>()
        val wm = mix((mediaId.toLong() xor 2654435769L) and 0xFFFFFFFFL)
        var a = mix((fnv1a(seed).toLong() and 0xFFFFFFFFL) xor (wm.toLong() and 0xFFFFFFFFL))
        for (e in 0 until 8) {
            val idx = ((a.toLong() and 0xFFFFFFFFL) % 61).toInt()
            a = rotl((a + 2654435769L).toInt(), 7 + (7 and e))
            val wa = mix(a.toLong())
            slots[idx] = a xor wa
            a = mix((a.toLong() + idx) and 0xFFFFFFFFL)
        }
        return State(slots, mix(2779096485L xor a.toLong()))
    }

    private fun keystreamU32(state: State, counter: Int): Int {
        val r = state.slots
        var o = state.acc
        val n = ((o.toLong() and 0xFFFFFFFFL) % 61).toInt()
        val inR = r.containsKey(n)
        val d = r[n] ?: 0
        val a = d xor imul(2654435769.toInt(), counter + 1)
        val l = (if (inR) o or a else o xor a)
        val l2 = rotl(l + o, 31 and n) xor rotl(o, 31 and imul(n, 7))
        o = mix((l2.toLong() + 2654435769L) and 0xFFFFFFFFL)
        r[n] = o
        state.acc = o
        return o
    }

    /** URL-safe base64 decode (minSdk-safe, JVM-safe). */
    internal fun b64decode(input: String): ByteArray {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.trim().replace('-', '+').replace('_', '/')
            .replace(Regex("\\s+"), "").trimEnd('=')
        val out = ByteArray((clean.length * 6) / 8 + 1)
        var bits = 0
        var acc = 0
        var pos = 0
        for (ch in clean) {
            val v = alpha.indexOf(ch)
            if (v < 0) throw IllegalArgumentException("bad base64 char")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(pos)
    }

    /** Decrypt base64url payload; returns JSON text after the "mvm1" header. */
    fun decrypt(b64: String, seed: String, mediaId: Int): String {
        val raw = b64decode(b64)
        val state = makeState(seed, mediaId)
        val out = ByteArray(raw.size)
        var i = 0
        var counter = 0
        while (i < raw.size) {
            val v = keystreamU32(state, counter).toLong() and 0xFFFFFFFFL
            counter++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (i >= raw.size) break
                out[i] = (raw[i].toInt() xor ((v ushr shift) and 0xFFL).toInt()).toByte()
                i++
            }
        }
        if (raw.size < 4 || out[0] != 0x6D.toByte() || out[1] != 0x76.toByte() ||
            out[2] != 0x6D.toByte() || out[3] != 0x31.toByte()
        ) {
            throw IllegalStateException("videasy decrypt: bad header")
        }
        return String(out, 4, out.size - 4, Charsets.UTF_8)
    }

    /** Parsed source entry. */
    data class Source(val quality: String, val url: String, val route: String)

    /** Decoded payload: stream sources plus the server's own subtitle tracks. */
    data class Result(val sources: List<Source>, val subtitles: List<Pair<String, String>>, val httpOk: Boolean)

    internal fun mixForTest(x0: Long): Int = mix(x0)
    internal fun fnv1aForTest(s: String): Int = fnv1a(s)
    internal fun stateForTest(seed: String, mediaId: Int): State = makeState(seed, mediaId)
    internal fun keystreamForTest(state: State, counter: Int): Int = keystreamU32(state, counter)
    internal fun decryptForTest(b64: String, seed: String, mediaId: Int): String = decrypt(b64, seed, mediaId)

    /** Quality label to ladder height ("4K"/"2160p" to 2160, else 0). Pure. */
    internal fun heightOf(quality: String?): Int {
        val q = (quality ?: "").trim()
        if (q.isEmpty()) return 0
        if (Regex("""\b(4k|uhd|2160p?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) return 2160
        return Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE).find(q)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 240..2160 } ?: 0
    }

    /** Quality label to audio language ("Hindi"/"English"/"Multi", else blank). Pure. */
    internal fun languageOf(quality: String?): String {
        val q = (quality ?: "").lowercase()
        if (q.isBlank()) return ""
        return when {
            q.contains("hindi") -> "Hindi"
            q.contains("tamil") -> "Tamil"
            q.contains("telugu") -> "Telugu"
            q.contains("english") || q == "en" -> "English"
            q.contains("multi") || q.contains("dual") -> "Multi"
            else -> ""
        }
    }

    /** Direct file extensions play as VIDEO links; everything else is HLS. Pure. */
    internal fun isHls(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains(".mp4") || u.contains(".mkv") || u.contains(".webm") || u.contains(".avi")) return false
        return true
    }

    /** Query URL for one route (title single-encoded like the player). Pure. */
    internal fun routeUrl(
        route: Route,
        title: String?,
        year: Int?,
        tmdbId: Int,
        imdbId: String?,
        mediaType: String,
        season: Int,
        episode: Int,
        seed: String,
        flipCasing: Boolean = false,
    ): String {
        fun mt(): String {
            val base = if (mediaType == "tv") route.tvType else route.movieType
            if (!flipCasing) return base
            return when (base) {
                "Movie" -> "movie"
                "movie" -> "Movie"
                "TV Series" -> "tv"
                "tv" -> "TV Series"
                else -> base
            }
        }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return buildString {
            append(API).append('/').append(route.path).append("/sources-with-title?")
            if (!title.isNullOrBlank()) append("title=").append(enc(title)).append('&')
            append("mediaType=").append(enc(mt())).append("&tmdbId=").append(tmdbId)
            if (!imdbId.isNullOrBlank()) append("&imdbId=").append(enc(imdbId))
            if (year != null && year > 0) append("&year=").append(year)
            if (mediaType == "tv" && season > 0) {
                append("&seasonId=").append(season).append("&episodeId=").append(episode.coerceAtLeast(1))
            }
            append("&enc=2&seed=").append(enc(seed))
        }
    }

    /** Decrypted body to sources + subtitles. Pure. */
    internal fun parseResult(jsonText: String, route: String): Result {
        val root = JSONObject(jsonText)
        val sources = root.optJSONArray("sources")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Source(s.optString("quality"), url, route)
            }
        } ?: emptyList()
        val subtitles = root.optJSONArray("subtitles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = s.optString("url").ifBlank { s.optString("file") }
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lang = s.optString("language").ifBlank { s.optString("lang") }
                    .ifBlank { s.optString("code") }.ifBlank { "English" }
                lang to url
            }
        } ?: emptyList()
        return Result(sources, subtitles, httpOk = true)
    }

    /** Fetch + decrypt across all routes in parallel, deduped by url. */
    suspend fun fetchAllSources(
        tmdbId: Int,
        imdbId: String?,
        title: String?,
        year: Int?,
        mediaType: String,
        season: Int,
        episode: Int,
    ): Result {
        // First pass with each route's known-good casing.
        val seed = fetchSeed(tmdbId) ?: return Result(emptyList(), emptyList(), httpOk = false)
        val first = fetchRoutes(seed, tmdbId, imdbId, title, year, mediaType, season, episode, flipCasing = false)
        if (first.sources.isNotEmpty()) return first
        // Retry once with a fresh seed (30s TTL) and flipped casing.
        if (!first.httpOk) return first
        val fresh = fetchSeed(tmdbId) ?: return first
        return fetchRoutes(fresh, tmdbId, imdbId, title, year, mediaType, season, episode, flipCasing = true)
    }

    /** One parallel pass over the routes. Never throws. */
    private suspend fun fetchRoutes(
        seed: String,
        tmdbId: Int,
        imdbId: String?,
        title: String?,
        year: Int?,
        mediaType: String,
        season: Int,
        episode: Int,
        flipCasing: Boolean,
    ): Result {
        val routes = ROUTES.filter { !(it.moviesOnly && mediaType == "tv") }
        val got = coroutineScope {
            routes.map { route ->
                async {
                    runCatching {
                        fetchRoute(route, seed, tmdbId, imdbId, title, year, mediaType, season, episode, flipCasing)
                    }.getOrDefault(Result(emptyList(), emptyList(), httpOk = false))
                }
            }.awaitAll()
        }
        val seen = HashSet<String>()
        val sources = got.flatMap { it.sources }.filter { seen.add(it.url) }
        val subtitles = got.flatMap { it.subtitles }.distinct()
        return Result(sources, subtitles, httpOk = got.any { it.httpOk })
    }

    /** One seed for all routes (server-cached per mediaId, short TTL). Null on failure. */
    private suspend fun fetchSeed(tmdbId: Int): String? {
        return try {
            val seedJson = com.lagradost.cloudstream3.app.get(
                "$API/seed?mediaId=$tmdbId", timeout = 6, headers = apiHeaders(),
            ).text
            JSONObject(seedJson).optString("seed").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("Videasy", "seed fetch failed: ${e.message}")
            null
        }
    }

    /** Query + decrypt one route. Never throws. */
    private suspend fun fetchRoute(
        route: Route,
        seed: String,
        tmdbId: Int,
        imdbId: String?,
        title: String?,
        year: Int?,
        mediaType: String,
        season: Int,
        episode: Int,
        flipCasing: Boolean,
    ): Result {
        return try {
            val url = routeUrl(route, title, year, tmdbId, imdbId, mediaType, season, episode, seed, flipCasing)
            val enc = com.lagradost.cloudstream3.app.get(url, timeout = 10, headers = apiHeaders()).text
            if (enc.isBlank() || enc.startsWith("<") || enc.length < 20) {
                return Result(emptyList(), emptyList(), httpOk = false)
            }
            parseResult(decrypt(enc, seed, tmdbId), route.path)
        } catch (e: Exception) {
            Log.w("Videasy", "fetchRoute ${route.path} failed: ${e.message}")
            Result(emptyList(), emptyList(), httpOk = false)
        }
    }
}
