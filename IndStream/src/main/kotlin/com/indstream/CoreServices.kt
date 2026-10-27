package com.indstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.math.BigInteger
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object HttpKit {

    const val DEFAULT_TIMEOUT = 12L

    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    val commonHeaders = mapOf("User-Agent" to userAgent)

    /** Runs [block], returning null on failure. Cancellation always propagates. */
    suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }

    suspend fun get(url: String, timeout: Long = DEFAULT_TIMEOUT): String? {
        return withTimeoutOrNull(timeout * 1000L) {
            attempt { app.get(url, timeout = timeout, headers = commonHeaders).text }
        }
    }

    fun resolveUrl(base: String, path: String): String = ManifestKit.resolveUrl(base, path)

    suspend fun aliveCheck(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSec: Long = 2,
    ): Boolean? = withTimeoutOrNull((timeoutSec + 1) * 1000L) {
        attempt {
            val headers = LinkedHashMap<String, String>().apply {
                put("User-Agent", userAgent)
                putAll(extraHeaders)
                if (!referer.isNullOrBlank() && !containsKey("Referer")) put("Referer", referer)
                put("Range", "bytes=0-0")
            }
            val code = app.get(url, timeout = timeoutSec, headers = headers).code
            when {
                code == 429 -> null
                code in 200..399 || code == 416 -> true
                else -> false
            }
        }
    }

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

    private suspend fun fetchRange(url: String, range: String, hdrs: Map<String, String>): ByteArray? {
        return withTimeoutOrNull(3000L) {
            attempt {
                app.get(url, timeout = 3, headers = hdrs + mapOf("Range" to "bytes=$range"))
                    .body.bytes()
            }
        }
    }

    private suspend fun contentLength(url: String, hdrs: Map<String, String>): Long? {
        return withTimeoutOrNull(3000L) {
            attempt {
                val r = app.get(url, timeout = 3, headers = hdrs + mapOf("Range" to "bytes=0-0"))
                val cr = r.headers["Content-Range"]
                cr?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.headers["Content-Length"]?.toLongOrNull()
            }
        }
    }

    private fun findMoov(data: ByteArray): ByteArray? {
        var i = 4
        while (i + 4 <= data.size) {
            val isMoov = data[i] == 'm'.code.toByte() && data[i + 1] == 'o'.code.toByte() &&
                data[i + 2] == 'o'.code.toByte() && data[i + 3] == 'v'.code.toByte()
            if (isMoov) {
                val start = i - 4
                val size = read32(data, start).toLong() and 0xFFFFFFFFL
                val end = when {
                    size == 0L -> data.size.toLong()
                    size == 1L -> if (i + 12 <= data.size) read64(data, i + 4) else -1L
                    size >= 8L -> start + size
                    else -> -1L
                }
                if (end in (start + 8L)..data.size.toLong()) {
                    return data.copyOfRange(start, end.toInt())
                }
            }
            i++
        }
        return null
    }

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
                } else if (type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl") {
                    val h = walk(i + 8, i + size)
                    if (h > 0) return h
                }
                i += size
            }
            return 0
        }
        return walk(0, moov.size)
    }

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

    private fun read32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun read64(b: ByteArray, off: Int): Long =
        ((read32(b, off).toLong() and 0xFFFFFFFFL) shl 32) or
            (read32(b, off + 4).toLong() and 0xFFFFFFFFL)
}

object Base64Kit {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val WHITESPACE = Regex("""\s+""")

    fun fromCustom(input: String): String =
        input.replace("-_.", "/").replace("@", "+")

    fun decode(input: String): ByteArray {
        val clean = input.trim().replace(WHITESPACE, "").trimEnd('=')
        val out = ByteArray((clean.length * 6) / 8)
        var bits = 0
        var acc = 0
        var pos = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
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

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append(ALPHABET[(n shr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        val rest = data.size - i
        if (rest == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append("==")
        } else if (rest == 2) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append(ALPHABET[(n shr 6) and 0x3F])
            out.append('=')
        }
        return out.toString()
    }
}

object CryptoKit {

    private val SIGMA = "expand 32-byte k".toByteArray(Charsets.US_ASCII)

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor rotl(x[a] + x[d], 7)
        x[c] = x[c] xor rotl(x[b] + x[a], 9)
        x[d] = x[d] xor rotl(x[c] + x[b], 13)
        x[a] = x[a] xor rotl(x[d] + x[c], 18)
    }

    private fun load32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun store32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        out[off + 2] = ((v ushr 16) and 0xFF).toByte()
        out[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun core(input: IntArray, addOriginal: Boolean): IntArray {
        val x = input.copyOf()
        var i = 20
        while (i > 0) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 5, 9, 13, 1)
            quarterRound(x, 10, 14, 2, 6)
            quarterRound(x, 15, 3, 7, 11)
            quarterRound(x, 0, 1, 2, 3)
            quarterRound(x, 5, 6, 7, 4)
            quarterRound(x, 10, 11, 8, 9)
            quarterRound(x, 15, 12, 13, 14)
            i -= 2
        }
        if (addOriginal) for (j in 0 until 16) x[j] += input[j]
        return x
    }

    private fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key, 0)
        state[2] = load32(key, 4)
        state[3] = load32(key, 8)
        state[4] = load32(key, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce16, 0)
        state[7] = load32(nonce16, 4)
        state[8] = load32(nonce16, 8)
        state[9] = load32(nonce16, 12)
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key, 16)
        state[12] = load32(key, 20)
        state[13] = load32(key, 24)
        state[14] = load32(key, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = false)
        val res = ByteArray(32)
        val picks = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        picks.forEachIndexed { i, w -> store32(res, i * 4, out[w]) }
        return res
    }

    private fun salsa20Block(key32: ByteArray, nonce8: ByteArray, blockIndex: Long): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key32, 0)
        state[2] = load32(key32, 4)
        state[3] = load32(key32, 8)
        state[4] = load32(key32, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce8, 0)
        state[7] = load32(nonce8, 4)
        state[8] = blockIndex.toInt()
        state[9] = (blockIndex ushr 32).toInt()
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key32, 16)
        state[12] = load32(key32, 20)
        state[13] = load32(key32, 24)
        state[14] = load32(key32, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = true)
        val res = ByteArray(64)
        for (i in 0 until 16) store32(res, i * 4, out[i])
        return res
    }

    internal fun xsalsa20Keystream(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(key.size == 32 && nonce24.size == 24)
        val subkey = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val nonce8 = nonce24.copyOfRange(16, 24)
        val out = ByteArray(length)
        var offset = 0
        var block = 0L
        while (offset < length) {
            val blockBytes = salsa20Block(subkey, nonce8, block)
            val n = minOf(64, length - offset)
            blockBytes.copyInto(out, offset, 0, n)
            offset += n
            block++
        }
        return out
    }

    private fun leBytesToBig(b: ByteArray): BigInteger =
        BigInteger(1, b.reversedArray())

    private fun bigToLeBytes(v: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var x = v
        for (i in 0 until size) {
            out[i] = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }

    internal fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        require(key.size == 32)
        val p = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))
        val rBytes = key.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        val r = leBytesToBig(rBytes)
        val s = leBytesToBig(key.copyOfRange(16, 32))
        var h = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val end = minOf(i + 16, msg.size)
            val block = msg.copyOfRange(i, end) + 0x01
            h = h.add(leBytesToBig(block)).multiply(r).mod(p)
            i += 16
        }
        h = h.add(s).mod(BigInteger.valueOf(2).pow(128))
        return bigToLeBytes(h, 16)
    }

    internal fun secretbox(message: ByteArray, nonce24: ByteArray, key: ByteArray): ByteArray {
        require(nonce24.size == 24 && key.size == 32)
        val full = ByteArray(32 + message.size)
        message.copyInto(full, 32)
        val ks = xsalsa20Keystream(key, nonce24, full.size)
        val c = ByteArray(full.size) { (full[it].toInt() xor ks[it].toInt()).toByte() }
        val mac = poly1305(c.copyOfRange(0, 32), c.copyOfRange(32, c.size))
        val out = ByteArray(16 + message.size)
        mac.copyInto(out, 0)
        c.copyInto(out, 16, 32, c.size)
        return out
    }
}

object TmdbService {

    private const val API_KEY_PRIMARY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val API_KEY_FALLBACK = "a721dd910292becd0d78ed436463db21"
    private const val API = "https://api.themoviedb.org/3"
    private const val API_ALIAS = "https://api.tmdb.org/3"
    private val API_HOSTS = listOf(API, API_ALIAS)
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    private const val LOG_TAG = "IndStream"

    private const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1000L
    private const val SEARCH_CACHE_MAX = 64
    private const val SEARCH_INFLIGHT_WAIT_MS = 11_000L
    private const val SEARCH_MAX_PAGES = 3
    private const val SEARCH_QUERY_VARIANTS = 3
    private const val SEARCH_CANDIDATE_CAP = 60
    private const val ALIAS_TOP = 4
    private const val ALIAS_TIMEOUT_MS = 2500L
    private const val ALIAS_CACHE_TTL_MS = 60 * 60 * 1000L
    private const val ALIAS_CACHE_MAX = 128
    private const val ALIAS_MAX_TITLES = 40
    private const val NEGATIVE_TTL_MS = 60_000L
    private const val CATALOG_TTL_MS = 10 * 60 * 1000L
    private const val DETAIL_CACHE_MAX = 128
    private const val SEASON_CACHE_MAX = 256
    private const val SEASON_LIST_CACHE_MAX = 64
    private const val CATALOG_CACHE_MAX = 64
    private const val FIND_CACHE_MAX = 128
    private const val NEGATIVE_CACHE_MAX = 256
    private const val BREAKER_THRESHOLD = 3
    private const val BREAKER_COOLDOWN_MS = 60_000L
    private const val BREAKER_WINDOW_MS = 30_000L
    private const val SEASON_CONCURRENCY = 3
    private const val REQUEST_TIMEOUT_SEC = 8L
    private const val TRANSPORT_RETRIES = 4
    private const val TRANSPORT_RETRY_DELAY_MS = 150L
    private const val TRANSPORT_RETRY_MAX_ELAPSED_MS = 2500L

    private val detailCache = ConcurrentHashMap<String, TmdbDetail>()
    private val imdbFindCache = ConcurrentHashMap<String, Pair<Int, String>>()
    private val seasonCache = ConcurrentHashMap<String, List<TmdbEpisode>>()
    private val seasonListCache = ConcurrentHashMap<String, List<Int>>()
    private val catalogCache = ConcurrentHashMap<String, Pair<List<TmdbItem>, Long>>()
    private val genreNameCache = ConcurrentHashMap<String, Map<Int, String>>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private data class TmdbReply(val body: String? = null, val unreachable: Boolean = false)

    data class TmdbItem(
        val tmdbId: Int?,
        val imdbId: String?,
        val type: String,
        val name: String,
        val year: String?,
        val poster: String?,
        val rating: Double?,
        val genreIds: List<Int> = emptyList(),
        val popularity: Double = 0.0,
        val voteCount: Int = 0,
        val originalLanguage: String? = null,
        val overview: String? = null,
        val originalName: String? = null,
        val aliases: List<String> = emptyList(),
        val originCountry: List<String> = emptyList(),
    )

    data class TmdbDetail(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val name: String? = null,
        val originalLanguage: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val year: String? = null,
        val rating: Double? = null,
        val overview: String? = null,
        val genres: List<String>? = null,
        val genreIds: List<Int> = emptyList(),
        val cast: List<ActorData>? = null,
        val runtime: Int? = null,
        val totalSeasons: Int? = null,
    )

    data class TmdbEpisode(
        val seasonNumber: Int = -1,
        val episodeNumber: Int = -1,
        val name: String? = null,
        val overview: String? = null,
        val released: String? = null,
        val thumbnail: String? = null,
        val rating: Double? = null,
        val runtime: Int? = null,
    )

    suspend fun search(query: String): List<TmdbItem> {
        if (query.isBlank()) return emptyList()
        val key = SearchRank.canonical(query).ifBlank { query.trim().lowercase() }
        searchCache[key]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.items
            searchCache.remove(key)
        }
        if (recentlyFailed("search:$key")) return emptyList()
        val hits = withTimeoutOrNull(SEARCH_INFLIGHT_WAIT_MS) {
            lead(key, searchInFlight) {
                val queries = SearchRank.recallQueries(query, SEARCH_QUERY_VARIANTS)
                val replies = coroutineScope {
                    queries.flatMapIndexed { index, variant ->
                        val pages = if (index == 0) SEARCH_MAX_PAGES else 1
                        (1..pages).map { page -> async { searchRemote(variant, page) } }
                    }.awaitAll()
                }
                val found = replies.mapNotNull { it.first }.flatten()
                if (found.isEmpty()) {
                    if (replies.isNotEmpty() && replies.all { it.first == null }) {
                        markFailedLookup("search:$key", replies.all { it.second })
                    }
                    emptyList()
                } else {
                    val merged = found.dedupedByTitle().take(SEARCH_CANDIDATE_CAP)
                    val final = rankCandidates(query, merged)
                    if (searchCache.size >= SEARCH_CACHE_MAX) {
                        val oldest = searchCache.entries.minByOrNull { e -> e.value.expiresAt }
                        oldest?.let { e -> searchCache.remove(e.key) }
                    }
                    searchCache[key] = SearchEntry(final, System.currentTimeMillis() + SEARCH_CACHE_TTL_MS)
                    final
                }
            }
        }
        return hits ?: emptyList()
    }

    private suspend fun rankCandidates(query: String, items: List<TmdbItem>): List<TmdbItem> {
        val ranked = SearchRank.rank(query, items)
        if (ranked.isEmpty()) return items.take(12)
        if (SearchRank.score(query, ranked.first()).tier >= 98.0) return ranked
        val aliases = aliasesFor(ranked.take(ALIAS_TOP))
        if (aliases.isEmpty()) return ranked
        return SearchRank.rank(
            query,
            ranked.map { item -> aliases[item.tmdbId]?.let { item.copy(aliases = it) } ?: item },
        ).ifEmpty { ranked }
    }

    private suspend fun aliasesFor(items: List<TmdbItem>): Map<Int, List<String>> {
        val byId = items.mapNotNull { item -> item.tmdbId?.let { it to item } }.toMap()
        if (byId.isEmpty()) return emptyMap()
        return withTimeoutOrNull(ALIAS_TIMEOUT_MS) {
            coroutineScope {
                byId.map { (id, item) ->
                    async {
                        val kind = if (item.type == "movie") "movie" else "tv"
                        id to fetchAliases(kind, id)
                    }
                }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
            }
        }.orEmpty()
    }

    private suspend fun fetchAliases(kind: String, id: Int): List<String> {
        aliasCache[id]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.titles
            aliasCache.remove(id)
        }
        val reply = tmdbGet("alt titles $kind=$id", "/$kind/$id/alternative_titles", "language=en-US")
        val titles = reply.body?.let { parseAlternativeTitles(it) }.orEmpty()
        if (titles.isNotEmpty()) {
            if (aliasCache.size >= ALIAS_CACHE_MAX) {
                aliasCache.entries.minByOrNull { e -> e.value.expiresAt }?.let { e -> aliasCache.remove(e.key) }
            }
            aliasCache[id] = AliasEntry(titles, System.currentTimeMillis() + ALIAS_CACHE_TTL_MS)
        }
        return titles
    }

    internal fun parseAlternativeTitles(json: String): List<String> = try {
        val root = JSONObject(json)
        val arr = root.optJSONArray("titles") ?: root.optJSONArray("results") ?: return emptyList()
        (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.let { str(it, "title") } }
            .distinct()
            .take(ALIAS_MAX_TITLES)
    } catch (e: Exception) { emptyList() }

    private suspend fun searchRemote(query: String, page: Int): Pair<List<TmdbItem>?, Boolean> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val reply = tmdbGet(
            "search q='$query' p=$page",
            "/search/multi",
            "query=$encoded&language=en-US&include_adult=false&page=$page",
        )
        val json = reply.body ?: return null to reply.unreachable
        return parseTmdbMultiSearch(json) to false
    }

    internal fun shouldTryNextKey(httpCode: Int, tmdbTripStatus: Int?): Boolean =
        tmdbTripStatus != null ||
            httpCode == 401 ||
            httpCode == 403 ||
            httpCode >= 500

    internal const val TMDB_429_RETRY_DELAY_MS = 1500L

    internal fun rateLimitRetryDelayMs(httpCode: Int, attempt: Int): Long? =
        if (httpCode == 429 && attempt == 0) TMDB_429_RETRY_DELAY_MS else null

    private suspend fun tmdbGet(op: String, path: String, query: String, timeout: Long = REQUEST_TIMEOUT_SEC): TmdbReply {
        if (breakerOpen()) {
            android.util.Log.w(LOG_TAG, "TMDB $op SKIPPED (breaker open)")
            return TmdbReply(unreachable = true)
        }
        val personal = Settings.tmdbApiKey()?.trim()?.takeIf { it.isNotEmpty() }
        val keys = buildList {
            personal?.let { add("personal" to it) }
            add("primary" to API_KEY_PRIMARY)
            add("fallback" to API_KEY_FALLBACK)
        }
        if (personal != null) android.util.Log.d(LOG_TAG, "TMDB $op using personal key")
        var lastReason = "no keys configured"
        for ((index, pair) in keys.withIndex()) {
            val (label, key) = pair
            var attempt = 0
            var netAttempt = 0
            while (true) {
                val url = "${API_HOSTS[netAttempt % API_HOSTS.size]}$path?api_key=$key&$query"
                val startedAt = System.currentTimeMillis()
                val responded: Pair<Int, String> = try {
                    val r = app.get(url, timeout = timeout, headers = HttpKit.commonHeaders)
                    r.code to r.text
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val fastFailure = System.currentTimeMillis() - startedAt <= TRANSPORT_RETRY_MAX_ELAPSED_MS
                    if (fastFailure && netAttempt < TRANSPORT_RETRIES) {
                        netAttempt++
                        val retryMs = TRANSPORT_RETRY_DELAY_MS * netAttempt
                        android.util.Log.w(LOG_TAG, "TMDB $op $label NET-FAIL(${t.javaClass.simpleName}): retry $netAttempt/$TRANSPORT_RETRIES in ${retryMs}ms")
                        delay(retryMs)
                        continue
                    }
                    lastReason = "$label NET-FAIL(${t.javaClass.simpleName}: ${(t.message ?: "").take(120)})"
                    android.util.Log.w(LOG_TAG, "TMDB $op $lastReason (transport failure after $TRANSPORT_RETRIES retries: no key rotation)")
                    recordTransportFailure()
                    return TmdbReply(unreachable = true)
                }
                val (code, body) = responded
                val retryMs = rateLimitRetryDelayMs(code, attempt)
                if (retryMs != null) {
                    attempt++
                    android.util.Log.w(LOG_TAG, "TMDB $op $label HTTP 429: retrying same key in ${retryMs}ms")
                    delay(retryMs)
                    continue
                }
                if (code == 429) {
                    lastReason = "$label HTTP 429 (rate limited)"
                    android.util.Log.w(LOG_TAG, "TMDB $op $lastReason: ${safeSnippet(body)}")
                    recordTransportFailure()
                    return TmdbReply()
                }
                val trip = if (code in 200..299) tmdbKeyTripCode(body) else null
                if (shouldTryNextKey(code, trip)) {
                    lastReason = "$label HTTP $code${trip?.let { " tmdb_status=$it" } ?: ""}: ${safeSnippet(body)}"
                    android.util.Log.w(LOG_TAG, "TMDB $op API-KEY-LIMITED $lastReason${fallingBack(index, keys)}")
                    break
                }
                if (code in 200..299) {
                    recordTransportSuccess()
                    return TmdbReply(body)
                }
                recordTransportSuccess()
                android.util.Log.w(LOG_TAG, "TMDB $op $label HTTP $code (not a key issue: no fallback): ${safeSnippet(body)}")
                return TmdbReply(body)
            }
        }
        recordTransportFailure()
        android.util.Log.e(LOG_TAG, "TMDB $op ABANDONED after ${keys.size} keys: $lastReason")
        return TmdbReply()
    }

    private fun fallingBack(index: Int, keys: List<Pair<String, String>>): String =
        if (index < keys.lastIndex) " → trying ${keys[index + 1].first}" else " → no keys left"

    suspend fun testTmdbKey(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty()) return "Enter a key first"
        if (key.startsWith("eyJ")) return "That's the Read Access Token - paste the API Key (v3)"
        for (host in API_HOSTS) {
            val result = HttpKit.attempt {
                val r = app.get("$host/configuration?api_key=$key", timeout = 8, headers = HttpKit.commonHeaders)
                when (r.code) {
                    in 200..299 -> "Key works - TMDB connected"
                    401 -> "Key rejected (401) - check the API Key (v3)"
                    else -> "TMDB error (${r.code})"
                }
            }
            if (result != null) return result
        }
        return "Verify failed - no network?"
    }

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
    private data class AliasEntry(val titles: List<String>, val expiresAt: Long)

    private val searchCache = ConcurrentHashMap<String, SearchEntry>()
    private val aliasCache = ConcurrentHashMap<Int, AliasEntry>()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val searchInFlight = ConcurrentHashMap<String, Deferred<List<TmdbItem>>>()
    private val metaInFlight = ConcurrentHashMap<String, Deferred<TmdbDetail?>>()
    private val findInFlight = ConcurrentHashMap<String, Deferred<Pair<Int, String>?>>()
    private val seasonInFlight = ConcurrentHashMap<String, Deferred<List<TmdbEpisode>?>>()
    private val seasonListInFlight = ConcurrentHashMap<String, Deferred<List<Int>>>()

    private val breakerLock = Any()
    private var breakerFailures = 0
    private var breakerOpenUntil = 0L
    private var breakerLastFailureAt = 0L

    private suspend fun <T> lead(
        key: String,
        inflight: ConcurrentHashMap<String, Deferred<T>>,
        block: suspend () -> T,
    ): T {
        val mine = searchScope.async(start = CoroutineStart.LAZY) { block() }
        val existing = inflight.putIfAbsent(key, mine)
        if (existing != null) {
            mine.cancel()
            return existing.await()
        }
        mine.invokeOnCompletion { inflight.remove(key, mine) }
        mine.start()
        return mine.await()
    }

    private fun breakerOpen(): Boolean = synchronized(breakerLock) {
        System.currentTimeMillis() < breakerOpenUntil
    }

    private fun recordTransportFailure() = synchronized(breakerLock) {
        val now = System.currentTimeMillis()
        if (now - breakerLastFailureAt > BREAKER_WINDOW_MS) breakerFailures = 0
        breakerLastFailureAt = now
        breakerFailures++
        if (breakerFailures >= BREAKER_THRESHOLD) {
            breakerOpenUntil = now + BREAKER_COOLDOWN_MS
            breakerFailures = 0
            android.util.Log.w(LOG_TAG, "TMDB breaker OPEN for ${BREAKER_COOLDOWN_MS / 1000}s")
        }
    }

    private fun recordTransportSuccess() = synchronized(breakerLock) { breakerFailures = 0 }

    private fun recentlyFailed(key: String): Boolean {
        val exp = negativeCache[key] ?: return false
        if (System.currentTimeMillis() > exp) {
            negativeCache.remove(key)
            return false
        }
        return true
    }

    private fun markFailed(key: String) {
        if (negativeCache.size >= NEGATIVE_CACHE_MAX) {
            negativeCache.entries.minByOrNull { it.value }?.let { negativeCache.remove(it.key) }
        }
        negativeCache[key] = System.currentTimeMillis() + NEGATIVE_TTL_MS
    }

    private fun markFailedLookup(key: String, unreachable: Boolean = false) {
        if (!unreachable) markFailed(key)
    }

    suspend fun trending(type: String, page: Int = 1): List<TmdbItem> =
        catalog("trending:$type:$page", type) { tmdbGet("trending $type", "/trending/$type/week", "language=en-US&page=$page") }

    suspend fun popular(type: String, page: Int = 1): List<TmdbItem> =
        catalog("popular:$type:$page", type) { tmdbGet("popular $type", "/$type/popular", "language=en-US&page=$page") }

    suspend fun recommendations(tmdbId: Int, type: String): List<TmdbItem> {
        if (tmdbId <= 0) return emptyList()
        val kind = if (type == "movie") "movie" else "tv"
        return catalog("recs:$kind:$tmdbId", kind) {
            tmdbGet("recs $kind=$tmdbId", "/$kind/$tmdbId/recommendations", "language=en-US&page=1")
        }
    }

    suspend fun similar(tmdbId: Int, type: String): List<TmdbItem> {
        if (tmdbId <= 0) return emptyList()
        val kind = if (type == "movie") "movie" else "tv"
        return catalog("similar:$kind:$tmdbId", kind) {
            tmdbGet("similar $kind=$tmdbId", "/$kind/$tmdbId/similar", "language=en-US&page=1")
        }
    }

    suspend fun discover(
        type: String,
        genreIds: List<Int>,
        page: Int = 1,
        language: String? = null,
        sortBy: String = "popularity.desc",
        minVotes: Int = 0,
        originCountry: String? = null,
        keywordIds: List<Int> = emptyList(),
    ): List<TmdbItem> {
        val kind = if (type == "movie") "movie" else "tv"
        val genres = genreIds.take(3).joinToString("|")
        val keywords = keywordIds.take(3).joinToString("|")
        if (genres.isBlank() && keywords.isBlank() &&
            language.isNullOrBlank() && originCountry.isNullOrBlank()
        ) return emptyList()
        val query = buildString {
            append("language=en-US&page=$page&sort_by=$sortBy&include_adult=false")
            if (genres.isNotBlank()) append("&with_genres=$genres")
            if (keywords.isNotBlank()) append("&with_keywords=$keywords")
            if (!language.isNullOrBlank()) append("&with_original_language=$language")
            if (!originCountry.isNullOrBlank()) append("&with_origin_country=$originCountry")
            if (minVotes > 0) append("&vote_count.gte=$minVotes")
        }
        val key = "discover:$kind:$genres:$keywords:$page:$sortBy:${language.orEmpty()}:" +
            "$minVotes:${originCountry.orEmpty()}"
        return catalog(key, kind) {
            tmdbGet("discover $kind", "/discover/$kind", query)
        }
    }

    suspend fun genreNames(type: String): Map<Int, String> {
        val kind = if (type == "movie") "movie" else "tv"
        genreNameCache[kind]?.let { return it }
        if (recentlyFailed("genres:$kind")) return emptyMap()
        val json = tmdbGet("genres $kind", "/genre/$kind/list", "language=en-US")
        if (json.body == null) {
            markFailedLookup("genres:$kind", json.unreachable)
            return emptyMap()
        }
        val map = parseGenreNames(json.body)
        if (map.isNotEmpty()) genreNameCache[kind] = map
        return map
    }

    internal fun parseGenreNames(json: String): Map<Int, String> = try {
        val root = JSONObject(json)
        val arr = root.optJSONArray("genres") ?: return emptyMap()
        (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val name = g.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private suspend fun catalog(key: String, type: String, fetch: suspend () -> TmdbReply): List<TmdbItem> {
        catalogCache[key]?.let { (items, expiresAt) ->
            if (System.currentTimeMillis() <= expiresAt) return items
            catalogCache.remove(key)
        }
        if (recentlyFailed("cat:$key")) return emptyList()
        val reply = fetch()
        val json = reply.body
        if (json == null) {
            markFailedLookup("cat:$key", reply.unreachable)
            return emptyList()
        }
        val items = parseResults(json, type)
        if (items.isNotEmpty()) {
            if (catalogCache.size >= CATALOG_CACHE_MAX) {
                catalogCache.entries.minByOrNull { it.value.second }?.let { catalogCache.remove(it.key) }
            }
            catalogCache[key] = items to (System.currentTimeMillis() + CATALOG_TTL_MS)
        }
        return items
    }

    suspend fun fetchTvSeasons(tmdbId: Int): List<Int> {
        if (tmdbId <= 0) return emptyList()
        val cacheKey = "seasons:$tmdbId"
        seasonListCache[cacheKey]?.let { return it }
        if (recentlyFailed(cacheKey)) return emptyList()
        return lead(cacheKey, seasonListInFlight) {
            val reply = tmdbGet("seasons tv=$tmdbId", "/tv/$tmdbId", "language=en-US")
            val json = reply.body
            if (json == null) {
                markFailedLookup(cacheKey, reply.unreachable)
                emptyList()
            } else {
                val seasons = parseSeasons(json)
                if (seasons.isNotEmpty()) {
                    if (seasonListCache.size >= SEASON_LIST_CACHE_MAX) {
                        seasonListCache.keys.firstOrNull()?.let { seasonListCache.remove(it) }
                    }
                    seasonListCache[cacheKey] = seasons
                }
                seasons
            }
        }
    }

    internal fun parseSeasons(json: String): List<Int> = try {
        val root = JSONObject(json)
        root.optJSONArray("seasons")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                s.optInt("season_number", -1).takeIf { it > 0 }
            }
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    suspend fun fetchMeta(tmdbId: Int, type: String): TmdbDetail? {
        if (tmdbId <= 0) return null
        val kind = if (type == "movie") "movie" else "tv"
        val cacheKey = "$tmdbId|$kind"
        detailCache[cacheKey]?.let { return it }
        if (recentlyFailed(cacheKey)) return null
        return lead(cacheKey, metaInFlight) {
            val reply = tmdbGet("meta $kind=$tmdbId", "/$kind/$tmdbId", "language=en-US&append_to_response=external_ids,credits")
            val json = reply.body
            val detail = json?.let { parseTmdbDetail(it, kind) }
            if (detail != null) {
                if (detailCache.size >= DETAIL_CACHE_MAX) {
                    detailCache.keys.firstOrNull()?.let { detailCache.remove(it) }
                }
                detailCache[cacheKey] = detail
            } else {
                markFailedLookup(cacheKey, reply.unreachable)
            }
            detail
        }
    }

    suspend fun findByImdb(imdbId: String): Pair<Int, String>? {
        if (!imdbId.startsWith("tt")) return null
        imdbFindCache[imdbId]?.let { return it }
        if (recentlyFailed("find:$imdbId")) return null
        return lead(imdbId, findInFlight) {
            val reply = tmdbGet("find imdb=$imdbId", "/find/$imdbId", "external_source=imdb_id&language=en-US")
            val json = reply.body
            val result = json?.let { parseFindResult(it) }
            if (result != null) {
                if (imdbFindCache.size >= FIND_CACHE_MAX) {
                    imdbFindCache.keys.firstOrNull()?.let { imdbFindCache.remove(it) }
                }
                imdbFindCache[imdbId] = result
            } else {
                markFailedLookup("find:$imdbId", reply.unreachable)
            }
            result
        }
    }

    internal fun parseFindResult(json: String): Pair<Int, String>? = try {
        val root = JSONObject(json)
        val movie = root.optJSONArray("movie_results")?.optJSONObject(0)
        val tv = root.optJSONArray("tv_results")?.optJSONObject(0)
        when {
            movie != null -> movie.optInt("id", -1).takeIf { it > 0 }?.let { it to "movie" }
            tv != null -> tv.optInt("id", -1).takeIf { it > 0 }?.let { it to "series" }
            else -> null
        }
    } catch (e: Exception) { null }

    suspend fun fetchSeasons(tmdbId: Int, seasons: List<Int>): List<TmdbEpisode> {
        if (tmdbId <= 0 || seasons.isEmpty()) return emptyList()
        val gate = Semaphore(SEASON_CONCURRENCY)
        return coroutineScope {
            seasons.distinct().map { season ->
                async {
                    gate.acquire()
                    try {
                        fetchSeason(tmdbId, season).orEmpty()
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll().flatten().sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        }
    }

    internal fun List<TmdbItem>.dedupedByTitle(): List<TmdbItem> {
        val best = LinkedHashMap<String, TmdbItem>()
        for (item in this) {
            val key = SearchRank.normalizeTitle(item.name) + "|" + (item.year ?: "")
            val current = best[key]
            if (current == null || (item.rating ?: 0.0) > (current.rating ?: 0.0)) {
                best[key] = item
            }
        }
        return best.values.toList()
    }

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
                    imdbId = null,
                    type = type,
                    name = name,
                    year = (str(r, "release_date") ?: str(r, "first_air_date"))?.take(4),
                    poster = str(r, "poster_path")?.let { "$IMG_BASE$it" },
                    rating = r.optDouble("vote_average", -1.0).takeIf { it > 0 },
                    genreIds = genreIdsOf(r),
                    popularity = r.optDouble("popularity", 0.0),
                    voteCount = r.optInt("vote_count", 0).coerceAtLeast(0),
                    originalLanguage = str(r, "original_language"),
                    overview = str(r, "overview"),
                    originalName = str(r, "original_title") ?: str(r, "original_name"),
                    originCountry = originCountriesOf(r),
                )
            }.dedupedByTitle()
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun parseTmdbDetail(raw: String?, type: String): TmdbDetail? {
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
                genreIds = m.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optInt("id", -1)?.takeIf { it > 0 }
                    }
                }.orEmpty(),
                cast = cast,
                runtime = m.optInt("runtime", -1).takeIf { it > 0 }
                    ?: m.optJSONArray("episode_run_time")?.optInt(0, -1)?.takeIf { it > 0 },
                totalSeasons = m.optInt("number_of_seasons", -1).takeIf { it > 0 },
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSeason(tmdbId: Int, season: Int): List<TmdbEpisode>? {
        val cacheKey = "$tmdbId|$season"
        seasonCache[cacheKey]?.let { return it }
        if (recentlyFailed(cacheKey)) return null
        return lead(cacheKey, seasonInFlight) {
            val reply = tmdbGet("season tv=$tmdbId s=$season", "/tv/$tmdbId/season/$season", "language=en-US")
            val json = reply.body
            if (json == null) {
                markFailedLookup(cacheKey, reply.unreachable)
                null
            } else {
                val episodes = parseSeasonEpisodes(json, season)
                if (episodes != null) {
                    if (episodes.isNotEmpty()) {
                        if (seasonCache.size >= SEASON_CACHE_MAX) {
                            seasonCache.keys.firstOrNull()?.let { seasonCache.remove(it) }
                        }
                        seasonCache[cacheKey] = episodes
                    }
                }
                episodes
            }
        }
    }

    private fun parseSeasonEpisodes(json: String, season: Int): List<TmdbEpisode>? = try {
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
                    runtime = e.optInt("runtime", -1).takeIf { it > 0 },
                )
            }
        }
    } catch (e: Exception) { null }

    private fun str(obj: JSONObject, key: String): String? {
        val v = obj.optString(key)
        return if (v.isBlank()) null else v
    }

    private fun genreIdsOf(obj: JSONObject): List<Int> =
        obj.optJSONArray("genre_ids")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                (arr.opt(i) as? Number)?.toInt()?.takeIf { it > 0 }
            }
        } ?: emptyList()

    private fun originCountriesOf(obj: JSONObject): List<String> =
        obj.optJSONArray("origin_country")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } ?: emptyList()

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
                    genreIds = genreIdsOf(r),
                    popularity = r.optDouble("popularity", 0.0),
                    voteCount = r.optInt("vote_count", 0).coerceAtLeast(0),
                    originalLanguage = str(r, "original_language"),
                    overview = str(r, "overview"),
                    originalName = str(r, "original_title") ?: str(r, "original_name"),
                    originCountry = originCountriesOf(r),
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}

object ManifestKit {

    private val SCHEME_HOST = Regex("""^https?://[^/]+""")
    private val SCHEME_PREFIX = Regex("https?://")
    private val ATTR_RE = Regex("""([A-Za-z0-9-]+)=("([^"]*)"|[^,\s]*)""")
    private val MPD_SET_RE = Regex("""<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>""", RegexOption.DOT_MATCHES_ALL)
    private val MPD_REP_RE = Regex("""<Representation\b([^>]*?)/?>""")
    private val XML_ATTR_RE = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")
    private val DIGITS_RE = Regex("""\d+""")
    private val URL_HEIGHT_RE = Regex("""(?<!\d)(\d{3,4})p(?!\d)""", RegexOption.IGNORE_CASE)

    fun resolveUrl(base: String, path: String): String {
        if (path.startsWith("http", ignoreCase = true)) return path
        if (path.startsWith("//")) return "https:$path"
        val schemeHost = SCHEME_HOST.find(base)?.value ?: return path
        val resolved = runCatching { java.net.URI(base).resolve(path).toString() }.getOrNull()
        return resolved?.takeIf { it.startsWith("http") }
            ?: if (path.startsWith("/")) "$schemeHost$path" else "$schemeHost/$path"
    }

    data class Variant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
        val audioGroup: String? = null,
        val subtitlesGroup: String? = null,
        val width: Int = 0,
    )

    data class MediaRendition(
        val type: String,
        val groupId: String,
        val name: String,
        val language: String?,
        val uri: String?,
        val forced: Boolean = false,
        val default: Boolean = false,
    )

    data class MasterPlaylist(
        val variants: List<Variant>,
        val audio: List<MediaRendition>,
        val subtitles: List<MediaRendition>,
    ) {
        val isMultiAudio: Boolean get() = audio.map { it.groupId }.distinct().size > 1 ||
            audio.map { it.language }.filterNotNull().distinct().size > 1
        val hasSubtitles: Boolean get() = subtitles.isNotEmpty()
    }

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
                    i++
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

    fun parseAttrs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        ATTR_RE.findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[3].ifEmpty { m.groupValues[2] }
            out[key] = value
        }
        return out
    }

    fun isMaster(text: String?): Boolean = text != null && text.contains("#EXT-X-STREAM-INF")

    data class Representation(
        val id: String,
        val height: Int,
        val bandwidth: Long,
        val codecs: String? = null,
        val width: Int = 0,
    )

    fun parseMpd(text: String?): List<Representation> {
        if (text.isNullOrBlank()) return emptyList()
        val reps = mutableListOf<Representation>()

        MPD_SET_RE.findAll(text).forEach { setMatch ->
            val setAttrs = parseXmlAttrs(setMatch.groupValues[1])
            val setVideo = setAttrs["mimeType"]?.contains("video", ignoreCase = true) == true ||
                setAttrs["contentType"]?.contains("video", ignoreCase = true) == true
            MPD_REP_RE.findAll(setMatch.groupValues[2]).forEach { repMatch ->
                val attrs = parseXmlAttrs(repMatch.groupValues[1])
                val repMime = attrs["mimeType"]
                val isVideo = if (repMime != null) repMime.contains("video", ignoreCase = true) else setVideo
                if (!isVideo) return@forEach
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
        XML_ATTR_RE.findAll(raw).forEach { m -> out[m.groupValues[1]] = m.groupValues[2] }
        return out
    }

    fun urlKey(url: String): String {
        return url
            .lowercase()
            .replace(SCHEME_PREFIX, "")
            .substringBefore("?")
            .trimEnd('/')
    }

    fun bestHeight(variants: List<Variant>): Int =
        variants.maxOfOrNull { normalizeHeight(it.width, it.height) } ?: 0

    fun parseResolution(raw: String?): Pair<Int, Int> {
        if (raw.isNullOrBlank()) return 0 to 0
        val nums = DIGITS_RE.findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.size < 2) return 0 to 0
        return nums[0] to nums[1]
    }

    fun normalizeHeight(width: Int, height: Int): Int {
        if (width <= 0) return height.coerceAtLeast(0)
        val wide = ((width * 9 + 8) / 16).coerceAtLeast(0)
        return max(height, wide)
    }

    fun bestHeightOf(text: String?, url: String): Int {
        if (text.isNullOrBlank()) return 0
        return if (text.contains("<MPD", ignoreCase = true)) {
            parseMpd(text).maxOfOrNull { normalizeHeight(it.width, it.height) } ?: 0
        } else {
            parseMaster(text, url)?.let { bestHeight(it.variants) } ?: 0
        }
    }

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

    fun maxQuality(a: Int, b: Int): Int = if (a <= 0) b else if (b <= 0) a else max(a, b)

    fun resolutionFromUrl(url: String?): Int {
        if (url.isNullOrBlank()) return 0
        return URL_HEIGHT_RE.findAll(url).maxOfOrNull {
            it.groupValues[1].toIntOrNull() ?: 0
        } ?: 0
    }

    private val LANG_HINDI = setOf("hi", "hin")
    private val LANG_ENGLISH = setOf("en", "eng")

    fun isHindi(rendition: MediaRendition): Boolean {
        val lang = rendition.language?.lowercase()
        if (lang != null && (lang in LANG_HINDI || lang.startsWith("hi"))) return true
        return rendition.name.contains("hindi", ignoreCase = true) ||
            rendition.name.contains("हिन्दी", ignoreCase = true) ||
            rendition.name.contains("हिंदी", ignoreCase = true)
    }

    fun isEnglish(rendition: MediaRendition): Boolean {
        val lang = rendition.language?.lowercase()
        if (lang != null && (lang in LANG_ENGLISH || lang.startsWith("en"))) return true
        return rendition.name.contains("english", ignoreCase = true)
    }

    fun hasHindiEnglishAudio(master: MasterPlaylist): Boolean {
        if (master.audio.isEmpty()) return false
        val hasHindi = master.audio.any { isHindi(it) }
        val hasEnglish = master.audio.any { isEnglish(it) }
        return hasHindi && hasEnglish
    }

    fun isOriginal(rendition: MediaRendition): Boolean =
        rendition.default ||
            rendition.name.contains("original", ignoreCase = true)

    data class DubLanguage(val canonical: String, val codes: Set<String>, val names: Set<String>) {
        val asciiPatterns: List<Regex> = names
            .filter { name -> name.all { it.code < 128 } }
            .map { name -> Regex("""(?i)\b${Regex.escape(name)}\b""") }
        val nonAsciiNames: List<String> = names.filter { name -> name.any { it.code >= 128 } }
    }

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

    fun languageFromName(name: String?, url: String?): String? {
        val hay = buildString {
            name?.let { append(it.lowercase()); append(' ') }
            url?.let { append(it.lowercase()); append(' ') }
        }
        if (hay.isBlank()) return null
        return INDIAN_DUB_LANGUAGES.firstOrNull { spec ->
            spec.asciiPatterns.any { it.containsMatchIn(hay) } ||
                spec.nonAsciiNames.any { hay.contains(it) }
        }?.canonical
    }

    fun audioLanguageLabel(master: MasterPlaylist): String? {
        if (master.audio.isEmpty()) return null
        fun trackLabel(t: MediaRendition): String? = when {
            isHindi(t) -> "Hindi"
            isEnglish(t) -> "English"
            indianLanguageOf(t) != null -> indianLanguageOf(t)
            isOriginal(t) -> "Original"
            else -> null
        }
        val defaultTrack = master.audio.firstOrNull { it.default }
        if (defaultTrack != null) return trackLabel(defaultTrack)
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

/**
 * Finds subtitle tracks in provider payloads without knowing the provider.
 * Covers HLS master renditions and any nested JSON list whose entries carry a subtitle URL.
 */
object SubtitleKit {

    data class Track(
        val language: String,
        val url: String,
        val name: String = "",
    )

    private const val MAX_JSON_DEPTH = 8

    private val SUBTITLE_EXTENSIONS = listOf(
        ".srt", ".vtt", ".ass", ".ssa", ".ttml", ".dfxp", ".sub", ".sbv",
    )
    private val URL_FIELDS = listOf(
        "url", "file", "src", "link", "path", "source", "subtitleUrl", "subtitle_url", "sub_url",
    )
    private val LANGUAGE_FIELDS = listOf(
        "label", "name", "title", "languageName", "language_name", "language", "lang", "srclang", "locale",
    )
    private val NAME_FIELDS = listOf("name", "label", "title")

    fun fromMaster(master: ManifestKit.MasterPlaylist?, baseUrl: String): List<Track> {
        if (master == null) return emptyList()
        return master.subtitles.mapNotNull { rendition ->
            val uri = rendition.uri?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val language = rendition.language?.trim()?.takeIf { it.isNotBlank() }
                ?: rendition.name.trim().takeIf { it.isNotBlank() }
                ?: "Subtitle"
            Track(language, ManifestKit.resolveUrl(baseUrl, uri), rendition.name)
        }.distinctBy { it.url }
    }

    fun fromJson(root: Any?, baseUrl: String): List<Track> {
        if (root == null) return emptyList()
        val found = LinkedHashMap<String, Track>()

        fun add(urlRaw: String?, language: String?, name: String?) {
            val raw = urlRaw?.trim()?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return
            if (!looksLikeSubtitle(raw)) return
            val url = ManifestKit.resolveUrl(baseUrl, raw)
            if (found.containsKey(url)) return
            val lang = language?.trim().orEmpty().ifBlank { name?.trim().orEmpty() }.ifBlank { "Subtitle" }
            found[url] = Track(lang, url, name?.trim().orEmpty())
        }

        fun walk(node: Any?, depth: Int) {
            if (depth > MAX_JSON_DEPTH) return
            when (node) {
                is org.json.JSONObject -> {
                    val names = node.names() ?: return
                    for (i in 0 until names.length()) {
                        val key = names.optString(i)
                        val value = node.opt(key) ?: continue
                        if (value == org.json.JSONObject.NULL) continue
                        when (value) {
                            is org.json.JSONArray -> {
                                val subtitleList = isSubtitleKey(key)
                                for (j in 0 until value.length()) {
                                    when (val entry = value.opt(j)) {
                                        is org.json.JSONObject -> {
                                            if (subtitleList) add(entry.urlField(), entry.languageField(), entry.nameField())
                                            walk(entry, depth + 1)
                                        }
                                        is String -> if (subtitleList) add(entry, null, null)
                                        is org.json.JSONArray -> walk(entry, depth + 1)
                                    }
                                }
                            }
                            is org.json.JSONObject -> {
                                if (isSubtitleKey(key)) add(value.urlField(), value.languageField(), value.nameField())
                                walk(value, depth + 1)
                            }
                            is String -> if (isSubtitleKey(key)) add(value, null, null)
                        }
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until node.length()) {
                        val entry = node.opt(i)
                        if (entry is org.json.JSONObject) {
                            val u = entry.urlField()
                            if (u != null && looksLikeSubtitle(u)) {
                                add(u, entry.languageField(), entry.nameField())
                            }
                        }
                        walk(entry, depth + 1)
                    }
                }
            }
        }

        walk(root, 0)
        return found.values.toList()
    }

    fun merge(existing: List<Track>, extra: List<Track>): List<Track> {
        if (extra.isEmpty()) return existing
        if (existing.isEmpty()) return extra
        return (existing + extra).distinctBy { it.url }
    }

    fun splitSubtitleEndpoints(roots: List<Any?>): Pair<List<Any>, List<String>> {
        val endpoints = LinkedHashSet<String>()
        fun collect(node: Any?, depth: Int) {
            if (depth > MAX_JSON_DEPTH || node == null) return
            when (node) {
                is org.json.JSONObject -> {
                    val names = node.names() ?: return
                    for (i in 0 until names.length()) {
                        val key = names.optString(i)
                        val value = node.opt(key) ?: continue
                        if (value is String && isSubtitleKey(key)) endpoints.add(value)
                        collect(value, depth + 1)
                    }
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), depth + 1)
            }
        }
        roots.forEach { collect(it, 0) }
        val cleaned = roots.mapNotNull { stripEndpointStrings(it) }
        return cleaned to endpoints.toList()
    }

    private fun stripEndpointStrings(node: Any?, depth: Int = 0): Any? {
        if (depth > MAX_JSON_DEPTH || node == null) return node
        return when (node) {
            is org.json.JSONObject -> {
                val out = org.json.JSONObject()
                val names = node.names() ?: return node
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val value = node.opt(key) ?: continue
                    if (value is String && isSubtitleKey(key)) continue
                    stripEndpointStrings(value, depth + 1)?.let { out.put(key, it) }
                }
                out
            }
            is org.json.JSONArray -> {
                val out = org.json.JSONArray()
                for (i in 0 until node.length()) {
                    stripEndpointStrings(node.opt(i), depth + 1)?.let { out.put(it) }
                }
                out
            }
            else -> node
        }
    }

    private fun org.json.JSONObject.urlField(): String? {
        for (key in URL_FIELDS) {
            val v = optString(key).trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun org.json.JSONObject.languageField(): String? {
        for (key in LANGUAGE_FIELDS) {
            val v = optString(key).trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun org.json.JSONObject.nameField(): String? {
        for (key in NAME_FIELDS) {
            val v = optString(key).trim()
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun isSubtitleKey(key: String): Boolean {
        val k = key.lowercase().replace("_", "").replace("-", "")
        return k == "sub" || k.endsWith("subs") || k.contains("subtitle") ||
            k.contains("caption") || k == "track" || k == "tracks" || k == "texttracks"
    }

    private fun looksLikeSubtitle(raw: String): Boolean {
        val lower = raw.lowercase()
        if (SUBTITLE_EXTENSIONS.any { lower.contains(it) }) return true
        val path = raw.substringBefore('?').substringBefore('#').lowercase()
        return path.contains("/subtitle") || path.contains("/caption") || path.contains("/subs/")
    }
}

object TitleMatch {

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val AND_WORD = Regex("""\band\b""", RegexOption.IGNORE_CASE)
    private val ROMAN_IV = Regex("""\biv\b""")
    private val ROMAN_III = Regex("""\biii\b""")
    private val ROMAN_II = Regex("""\bii\b""")
    private val ROMAN_I = Regex("""\bi\b""")
    private val YEAR_TOKEN = Regex("""(19|20)\d{2}""")

    fun normalizeTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .lowercase()
            .trim()
            .replace(NON_WORD, " ")
            .trim()
            .replace(WHITESPACE, " ")
    }

    fun titleVariants(title: String?): List<String> {
        val n = normalizeTitle(title)
        if (n.isBlank()) return emptyList()
        val variants = linkedSetOf(n)
        if (title != null) {
            variants += normalizeTitle(title.replace("&", " and "))
            variants += normalizeTitle(title.replace(AND_WORD, "&"))
        }
        variants += n.replace("'", "")
        variants += n.replace("’", "")
        variants += n.replace(ROMAN_IV, "4")
        variants += n.replace(ROMAN_III, "3")
        variants += n.replace(ROMAN_II, "2")
        variants += n.replace(ROMAN_I, "1")
        return variants.filter { it.isNotBlank() }.distinct()
    }

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

    fun titleDistance(query: String, title: String): Double {
        val q = normalizeTitle(query)
        val t = normalizeTitle(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        val qTokens = q.split(" ")
        val tTokens = t.split(" ")
        if (qTokens.all { qt -> tTokens.any { tt -> tt.startsWith(qt) } }) {
            val lenScore = q.length.toDouble() / t.length.toDouble()
            return 0.7 + 0.3 * min(1.0, lenScore)
        }
        val maxLen = maxOf(q.length, t.length)
        if (maxLen == 0) return 0.0
        return 1.0 - levenshtein(q, t).toDouble() / maxLen
    }

    fun yearMatches(queryYear: Int?, candidateYear: Int?, tolerance: Int = 2): Boolean {
        if (queryYear == null || candidateYear == null) return true
        return abs(queryYear - candidateYear) <= tolerance
    }

    fun isRelevant(query: String, title: String, queryYear: Int?, candidateYear: Int?): Boolean {
        if (!yearMatches(queryYear, candidateYear)) return false
        val q = stripYearTokens(normalizeTitle(query))
        val t = stripYearTokens(normalizeTitle(title))
        return titleDistance(q, t) >= 0.7
    }

    private fun stripYearTokens(normalized: String): String {
        if (normalized.isBlank()) return normalized
        val stripped = normalized.split(" ")
            .filterNot { YEAR_TOKEN.matches(it) }
            .joinToString(" ")
            .trim()
        return stripped.ifBlank { normalized }
    }

    fun parseYear(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return YEAR_TOKEN.find(raw)?.value?.toIntOrNull()
    }
}


