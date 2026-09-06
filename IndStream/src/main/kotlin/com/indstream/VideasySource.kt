package com.indstream

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Videasy "Fade" (Hindi) stream source â€” api.speedracelight.com.
 *
 * Videasy's player (player.videasy.net) exposes a server list; the "Fade"
 * entry fetches `{api}/hdmovie/sources-with-title` and filters
 * `quality == "Hindi"` â€” a dedicated Hindi-dub upstream (verified Sept 2026:
 * GOT S1E1 + AOT S1E1 return distinct muxed Hindi/English HLS streams).
 *
 * API chain (all reversed from the player's webpack chunk 8351):
 *  1. GET /seed?mediaId={tmdbId} â†’ {"seed":"<num>.<rand>","ttlMs":30000}
 *  2. GET /hdmovie/sources-with-title?mediaType=&tmdbId=&imdbId=&year=
 *        &enc=2&seed={seed}&title={urlEncodedTitle} â†’ base64url ciphertext
 *  3. Decrypt with the custom "mvm1" stream cipher (below); payload is JSON:
 *     {"sources":[{quality,url}...],"subtitles":[],...}
 *
 * Cipher (port of the minified JS):
 *  - state: 61-slot SPARSE table (JS Array holes â€” only init-written slots
 *    exist; `n in r` checks slot presence, holes read as 0).
 *  - init: a = w(fnv1a(seed) ^ w(mediaId ^ 2654435769)); 8 rounds of
 *    idx=a%61; a=rotl(a+2654435769, 7+(7&e)); slot=a^w(a); a=w(a+idx).
 *    acc = w(2779096485 ^ a).
 *  - keystream per u32: n=acc%61; d=slot(n) or 0 (hole); a=d^imul(K,ctr+1);
 *    l = (n in slots) ? (acc|a) : (acc^a); l=rotl(l+acc,31&n)^rotl(acc,31&imul(n,7));
 *    acc=w(l+2654435769); slot(n)=acc; return acc â€” packed 4Ã—LITTLE-ENDIAN.
 *  - plaintext: XOR, header "mvm1" (0x6D,0x76,0x6D,0x31) then UTF-8 JSON.
 *
 * The 500s on some titles mean the upstream scraper failed â€” treat as "no
 * sources" (the farm's other servers cover those).
 */
object VideasySource {

    private const val API = "https://api.speedracelight.com"
    private const val ROUTE = "hdmovie" // Videasy "Fade" server = Hindi upstream
    private const val ORIGIN = "https://player.videasy.net"
    private const val REFERER = "https://player.videasy.net/"

    /** Headers for API + playlist requests. */
    fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept" to "*/*",
        "Origin" to ORIGIN,
        "Referer" to REFERER,
    )

    /** FNV-1a with the cipher's final mix. */
    private fun fnv1a(s: String): Int {
        var t = 2166136261L
        for (ch in s) {
            t = (t xor ch.code.toLong()) * 16777619L and 0xFFFFFFFFL
        }
        return mix(t)
    }

    /** The cipher's murmur-style finalizer `w(x)`. */
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
        val al = a.toLong(); val bl = b.toLong()
        return (al * bl and 0xFFFFFFFFL).toInt()
    }

    /** 61-slot sparse state: written slots only (JS Array holes). */
    class State internal constructor(val slots: HashMap<Int, Int>, var acc: Int)

    private fun makeState(seed: String, mediaId: Int): State {
        val slots = HashMap<Int, Int>()
        // a = w(fnv1a(seed) ^ w(mediaId ^ 2654435769))
        val wm = mix((mediaId.toLong() xor 2654435769L) and 0xFFFFFFFFL)
        var a = mix((fnv1a(seed).toLong() and 0xFFFFFFFFL) xor (wm.toLong() and 0xFFFFFFFFL)).toInt()
        for (e in 0 until 8) {
            val idx = ((a.toLong() and 0xFFFFFFFFL) % 61).toInt()
            a = rotl((a + 2654435769L).toInt(), 7 + (7 and e))
            // slot[idx] = a ^ w(a); a = w(a + idx)
            val wa = mix(a.toLong())
            slots[idx] = a xor wa
            a = mix((a.toLong() + idx) and 0xFFFFFFFFL).toInt()
        }
        return State(slots, mix(2779096485L xor a.toLong()).toInt())
    }

    private fun keystreamU32(state: State, counter: Int): Int {
        val r = state.slots
        var o = state.acc
        val n = ((o.toLong() and 0xFFFFFFFFL) % 61).toInt()
        val inR = r.containsKey(n)
        val d = r[n] ?: 0 // hole >>> 0 = 0
        val a = d xor imul(2654435769.toInt(), counter + 1)
        val l = (if (inR) o or a else o xor a)
        val l2 = rotl(l + o, 31 and n) xor rotl(o, 31 and imul(n, 7))
        o = mix((l2.toLong() + 2654435769L) and 0xFFFFFFFFL).toInt()
        r[n] = o
        state.acc = o
        return o
    }

    /** Decrypt base64url payload; returns JSON text after the "mvm1" header. */
    fun decrypt(b64: String, seed: String, mediaId: Int): String {
        val normalized = buildString {
            for (c in b64) when (c) {
                '-' -> append('+'); '_' -> append('/'); else -> append(c)
            }
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val raw = java.util.Base64.getDecoder().decode(padded)
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
        // Header "mvm1"
        if (raw.size < 4 || out[0] != 0x6D.toByte() || out[1] != 0x76.toByte() ||
            out[2] != 0x6D.toByte() || out[3] != 0x31.toByte()) {
            throw IllegalStateException("videasy decrypt: bad header ${out.take(4).joinToString("") { "%02x".format(it) }}")
        }
        return String(out, 4, out.size - 4, Charsets.UTF_8)
    }

    /** Parsed source entry. */
    data class Source(val quality: String, val url: String)

    /** Full decoded payload: stream sources + the server's own subtitle
     *  tracks ({url, language|lang|code} items — usually empty, but they
     *  must be taken when present, user spec Sept 2026). */
    data class Result(val sources: List<Source>, val subtitles: List<Pair<String, String>>)

    // ---- JVM-test hooks (internal, same-package access) ----

    internal fun mixForTest(x0: Long): Int = mix(x0)
    internal fun fnv1aForTest(s: String): Int = fnv1a(s)
    internal fun stateForTest(seed: String, mediaId: Int): State = makeState(seed, mediaId)
    internal fun keystreamForTest(state: State, counter: Int): Int = keystreamU32(state, counter)
    internal fun decryptForTest(b64: String, seed: String, mediaId: Int): String = decrypt(b64, seed, mediaId)

    /**
     * Fetch + decrypt sources for one title. Returns parsed sources (may be
     * empty when the upstream has no entry or 500s). [imdbId]/[year]/[title]
     * all help the upstream match; pass what you have.
     */
    suspend fun fetchSources(
        tmdbId: Int,
        imdbId: String?,
        title: String?,
        year: Int?,
        mediaType: String,
        season: Int,
        episode: Int,
    ): Result {
        return try {
            val seedJson = com.lagradost.cloudstream3.app.get(
                "$API/seed?mediaId=$tmdbId", timeout = 6, headers = apiHeaders(),
            ).text
            val seed = JSONObject(seedJson).optString("seed")
            if (seed.isBlank()) {
                Log.w("VideasyHindi", "no seed in response: ${seedJson.take(120)}")
                return Result(emptyList(), emptyList())
            }

            val q = buildString {
                append("mediaType=").append(if (mediaType == "tv") "tv" else "movie")
                append("&tmdbId=").append(tmdbId)
                if (!imdbId.isNullOrBlank()) append("&imdbId=").append(imdbId)
                if (year != null && year > 0) append("&year=").append(year)
                append("&enc=2&seed=").append(java.net.URLEncoder.encode(seed, "UTF-8"))
                if (!title.isNullOrBlank()) append("&title=").append(java.net.URLEncoder.encode(title, "UTF-8"))
                if (mediaType == "tv" && season > 0) {
                    append("&seasonId=").append(season)
                    append("&episodeId=").append(episode.coerceAtLeast(1))
                }
            }
            val enc = com.lagradost.cloudstream3.app.get(
                "$API/$ROUTE/sources-with-title?$q", timeout = 10, headers = apiHeaders(),
            ).text
            if (enc.isBlank() || enc.startsWith("<")) {
                Log.w("VideasyHindi", "bad response (${enc.length}B): ${enc.take(80)}")
                return Result(emptyList(), emptyList())
            }

            val jsonText = decrypt(enc, seed, tmdbId)
            val root = JSONObject(jsonText)
            val sources = root.optJSONArray("sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = s.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Source(s.optString("quality"), url)
                }
            } ?: emptyList()
            // The payload's own subtitle tracks ({url, language|lang|code}).
            // Usually absent for hdmovie — emitted when present so server-
            // owned captions are never dropped (user spec Sept 2026).
            val subtitles = root.optJSONArray("subtitles")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = (s.optString("url").ifBlank { s.optString("file") })
                        .takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val lang = s.optString("language").ifBlank { s.optString("lang") }
                        .ifBlank { s.optString("code") }.ifBlank { "English" }
                    lang to url
                }
            } ?: emptyList()
            Result(sources, subtitles)
        } catch (e: Exception) {
            Log.w("VideasyHindi", "fetchSources failed: ${e.message}")
            Result(emptyList(), emptyList())
        }
    }
}
