package com.multimovies

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Result types returned by [ScreenscapeExtractor]. These are plain Kotlin data
 * classes (no cloudstream dependencies) so the extractor's crypto/HTTP logic can
 * be unit- and live-tested without the compile-only cloudstream library on the
 * test classpath. [MultiSourcePuller] adapts them into cloudstream
 * [ExtractorLink]s.
 */
data class ScreenSubtitle(val lang: String, val url: String)
data class ScreenSource(
    val name: String,
    val url: String,
    val quality: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * screenscape.me extractor.
 *
 * screenscape is the site's "lan=hindi" (Hindi) server and an aggregator that proxies
 * many upstreams (streamflix, vidnest, vidwiki, nxsha, …). Its embed page is JS-rendered,
 * but the underlying API is fully deterministic client-side crypto (CryptoJS), reverse-
 * engineered from its bundle:
 *
 *   1. Client generates a 24-byte bootstrap token `e` (hex). POST /api/{createTokenRouteCode(e)}
 *      with header `x-screenscape-bootstrap: e`. The server returns an encrypted envelope
 *      that decrypts to { responseKey, apiToken }.
 *   2. Every subsequent request is HMAC-SHA256-signed with `apiToken` and its response is
 *      AES-encrypted, keyed by SHA256(token | METHOD:path?query | F | md5(F:hour)).
 *   3. The tmdb id required by the API is read from the embed page HTML (screenscape resolves
 *      imdb->tmdb client-side and embeds it).
 *
 * All primitives below mirror the site's minified bundle exactly (HmacSHA256/v/SHA256/MD5,
 * base64url, CryptoJS OpenSSL AES) so the plugin can resolve streams without a browser.
 */
object ScreenscapeExtractor {

    private const val BASE_URL = "https://screenscape.me"
    private val sharedHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
        "x-screenscape-client" to "web-player",
        "sec-ch-ua" to "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-store",
    )

    // HTTP goes through the shared HttpKit client (per-host cookie jar + timeouts).

    // Secret constants (decoded from the bundle's browser Buffer polyfill via live decryptApiBody).
    // The 4-step decodeSecret (b64→hex→b64→hex) is NOT used because no standard Buffer polyfill
    // reproduces the browser's custom module 467034 exactly. Values extracted via instrumenting the
    // live crypto chunk's own decryptApiBody call.
    private val F: String = "a6nG5GbtiQwFgLqRnNRvE0ZMCsHUmfm0-hQflAxzInXvfV8TI4UmIjDYZoTBSQOa"
    private val C: String = "sVFL-6633ARp-tqnK61b0OE2rwSmZYzP8df5hC7PGxOUk4TTvXd0sUWRrPZRAlOn"
    private val I: Int = 18
    private val D: Int = 14

    // ---- low-level crypto (mirror of CryptoJS bundle) ----

    private fun hmacSha256Hex(message: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** base64url encode (btoa + /+ -> _- + strip padding), as used by `u`. */
    private fun base64Url(s: String): String {
        val b64 = Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
        return b64.replace("+", "-").replace("/", "_").replace(Regex("=+$"), "")
    }

/** standard base64 -> utf8 string, as used by `o`. Lenient: matches Node.js
      * Buffer.from(s, "base64") which silently skips non-base64 characters and
      * handles partial trailing groups (no padding required). Delegated to
      * [CryptoJs.base64DecodeLenient]. */
     private fun base64DecodeBytes(s: String): ByteArray = CryptoJs.base64DecodeLenient(s)

    /** CryptoJS `enc.Utf8.stringify` port: bytes -> UTF-16 string. Mirrors the bundle's
     * lossy decoder exactly (bytes < 0x80 -> 1 char; < 0x800 -> 2-byte pair; < 0x10000 ->
     * 3-byte triple; else dropped). Java's strict UTF-8 replacement differs, so this is
     * required to reproduce `o(e)` = Base64.parse(e).toString(enc.Utf8). */
    private fun cryptoJsUtf8String(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                sb.append(b.toChar())
                i++
            } else if (b < 0x800) {
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() else 0
                sb.append((((b shl 6) or (b1 and 0x3F)) and 0x7FF).toChar())
                i += 2
            } else if (b < 0x10000) {
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() else 0
                sb.append((((b shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)) and 0xFFFF).toChar())
                i += 3
            } else {
                i++
            }
        }
        return sb.toString()
    }

    /** XOR of [data] with a repeating [key], char-code based (mirrors bundle's `n`). */
    private fun xor(data: String, key: String): String {
        if (key.isEmpty()) return data
        val sb = StringBuilder(data.length)
        for (i in data.indices) {
            sb.append((data[i].code xor key[i % key.length].code).toChar())
        }
        return sb.toString()
    }

    // ---- signing (module I, ported verbatim) ----

    private fun p(): String {
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun createTokenRouteCode(e: String): String {
        val x = (System.currentTimeMillis()).toString(36)
        val r = p()
        val t = "token.$x.$r"
        val a = base64Url(t)
        val f = hmacSha256Hex(t, e).take(24)
        return "$a.$f"
    }

    private fun createServerRouteRequestId(apiToken: String): String {
        val json = JSONObject().apply {
            put("k", "route"); put("v", "server"); put("t", System.currentTimeMillis()); put("n", p())
        }.toString()
        val r = base64Url(json)
        val t = hmacSha256Hex(json, apiToken).take(24)
        return "$r.$t"
    }

    private fun createServerRequestId(serverName: String, apiToken: String): String {
        val x = System.currentTimeMillis().toString(36)
        val r = p()
        val a = "$serverName.$x.$r"
        val f = base64Url(a)
        val c = hmacSha256Hex(a, apiToken).take(24)
        return "$f.$c"
    }

    private fun createServerTmdbRequestId(tmdbId: String, season: Int?, episode: Int?, apiToken: String): String {
        val json = JSONObject().apply {
            put("k", "tmdb"); put("t", System.currentTimeMillis()); put("n", p())
            put("tmdbId", tmdbId)
            put("season", season ?: JSONObject.NULL)
            put("episode", episode ?: JSONObject.NULL)
        }.toString()
        val t = base64Url(json)
        val a = hmacSha256Hex(json, apiToken).take(24)
        return "$t.$a"
    }

    // ---- response decryption (module k, ported verbatim) ----

    private fun decryptEnvelope(envelopeD: String, envelopeS: String, envelopeV: Int, token: String, context: String): String? {
        val b = cryptoJsUtf8String(base64DecodeBytes(envelopeD))
        val h = b.indexOf(":")
        if (h < 0) return null
        val l = b.substring(0, h)
        val u = l.toLongOrNull() ?: return null
        if (kotlin.math.abs(System.currentTimeMillis() - u) > 300_000) return null
        val hour = if (envelopeV >= 2) (u / 3_600_000) else (System.currentTimeMillis() / 3_600_000)
        val cd = md5Hex("$F:$hour").take(16)
        val pKey = sha256Hex("$token|$context|$F|$cd")
        val hmacMsg = if (envelopeV >= 2) "$envelopeD:$envelopeV" else envelopeD
        if (hmacSha256Hex(hmacMsg, pKey) == envelopeS) {
            return doDecrypt(b, h, pKey)
        }
        // previous-hour fallback (v>=2)
        if (envelopeV >= 2) {
            val prevHour = (u / 3_600_000) - 1
            val cd2 = md5Hex("$F:$prevHour").take(16)
            val pKey2 = sha256Hex("$token|$context|$F|$cd2")
            if (hmacSha256Hex(hmacMsg, pKey2) == envelopeS) {
                return doDecrypt(b, h, pKey2)
            }
        }
        return null
    }

    private fun doDecrypt(b: String, h: Int, pKey: String): String? {
        val a = b.substring(0, h)
        val payload = b.substring(h + 1)
        val xorKey = sha256Hex("$C:$a:$pKey").take(D)
        val xored = xor(payload, xorKey)
        val reversed = xored.reversed()
        val secondXor = xor(reversed, pKey.take(I))
        // secondXor is base64(utf8bytes(openSslB64)); decode to bytes, then CryptoJS-Utf8 to recover the
        // OpenSSL base64 string that AES.decrypt expects (mirrors `h = o(n(b, s))`).
        val openSslB64 = cryptoJsUtf8String(base64DecodeBytes(secondXor))
        return CryptoJs.aesDecryptCryptoJs(openSslB64, pKey)
    }

    // ---- tmdb id extraction from the embed page HTML ----

    private fun extractTmdbId(html: String): String? {
        // handle both "tmdbId":"538858" and \"tmdbId\":\"538858\" (escaped in JS payload)
        Regex("""tmdbId\\?["']?\s*[:=]\s*\\?["']?(\d{4,8})""", RegexOption.IGNORE_CASE).find(html)?.let {
            return it.groupValues.getOrNull(1)
        }
        listOf(
            Regex("""tmdbId["'\s:=]+["']?(\d{4,8})""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:tmdb|tmdbId|id)["']\s*:\s*"?(\d{4,8})"""),
            // fallback: embed?tmdb=XXX link or TMDB ID input value
            Regex("""embed\?tmdb=(\d{4,8})""", RegexOption.IGNORE_CASE),
            Regex("""placeholder="TMDB ID" value="(\d{4,8})"""),
        ).forEach { m ->
            m.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    // ---- public entry point ----

    suspend fun extract(src: MultiSourcePuller.Source, onSubtitle: (ScreenSubtitle) -> Unit): List<ScreenSource> {
        val embedUrl = src.url
        val html = HttpKit.get(embedUrl, headers = sharedHeaders) ?: return emptyList()
        val tmdbId = extractTmdbId(html) ?: Regex("""[?&]tmdb=(\d{4,8})""").find(embedUrl)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val season = src.season ?: Regex("""(\d+)x(\d+)""").find(src.referer ?: embedUrl)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = src.episode ?: Regex("""(\d+)x(\d+)""").find(src.referer ?: embedUrl)
            ?.groupValues?.getOrNull(2)?.toIntOrNull()

        // 1) bootstrap -> apiToken
        val e = (0 until 24).joinToString("") {
            (SecureRandom().nextInt(256)).toString(16).padStart(2, '0')
        }
        val route = createTokenRouteCode(e)
        val bootText = HttpKit.post(
            "$BASE_URL/api/$route",
            headers = sharedHeaders + mapOf("x-screenscape-bootstrap" to e),
            referer = embedUrl,
            origin = BASE_URL,
        ) ?: return emptyList()

        val bootEnv = runCatching { JSONObject(bootText) }.getOrNull() ?: return emptyList()
        val bootDecrypted = if (bootEnv.has("d") && bootEnv.has("s")) {
            decryptEnvelope(
                bootEnv.optString("d"), bootEnv.optString("s"), bootEnv.optInt("v", 1),
                e, "POST:/api/$route?",
            )?.let { runCatching { JSONObject(it) }.getOrNull() }
        } else bootEnv
        val apiToken = bootDecrypted?.optString("apiToken")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val responseKey = bootDecrypted?.optString("responseKey")?.takeIf { it.isNotBlank() } ?: return emptyList()

        // 2) server request (screenscape backend) -> stream.
        // Signing uses responseKey; apiToken goes in the x-api-token header.
        val serverRoute = createServerRouteRequestId(responseKey)
        val hostSeg = createServerRequestId("streamflix", responseKey)
        val q = createServerTmdbRequestId(tmdbId, season, episode, responseKey)
        val apiPath = "/api/$serverRoute/$hostSeg"
        val query = "q=$q"
        val srvText = HttpKit.get(
            "$BASE_URL$apiPath?$query",
            headers = sharedHeaders + mapOf("Referer" to embedUrl, "x-api-token" to apiToken),
        ) ?: return emptyList()

        val env = runCatching { JSONObject(srvText) }.getOrNull() ?: return emptyList()
        val decrypted = if (env.has("d") && env.has("s")) {
            decryptEnvelope(
                env.optString("d"), env.optString("s"), env.optInt("v", 1),
                responseKey, "GET:$apiPath?$query",
            )?.let { runCatching { JSONObject(it) }.getOrNull() }
        } else env
        val json = decrypted ?: return emptyList()

        val sources = mutableListOf<ScreenSource>()
        val subs = mutableListOf<ScreenSubtitle>()
        fun jsonHeaders(o: JSONObject): Map<String, String> {
            val h = o.optJSONObject("headers") ?: return emptyMap()
            return h.keys().asSequence().associateWith { h.optString(it) }
        }
        json.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                sources.add(
                    mkSource(
                        url = url,
                        quality = o.optString("quality"),
                        name = src.name,
                        headers = jsonHeaders(o),
                        lang = o.optString("language"),
                    )
                )
            }
        }
        json.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                sources.add(
                    mkSource(
                        url = url,
                        quality = o.optString("quality"),
                        name = src.name,
                        headers = jsonHeaders(o),
                        lang = o.optString("language"),
                    )
                )
            }
        }
        if (sources.isEmpty()) {
            json.optString("url").takeIf { it.isNotBlank() }?.let {
                sources.add(mkSource(it, "", src.name))
            }
        }
        json.optJSONArray("subtitles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
                val lang = o.optString("lang").takeIf { it.isNotBlank() }
                    ?: o.optString("language").takeIf { it.isNotBlank() } ?: "en"
                subs.add(ScreenSubtitle(lang, url))
            }
        }
        subs.forEach { onSubtitle(it) }
        return sources
    }

    private fun mkSource(
        url: String,
        quality: String,
        name: String,
        headers: Map<String, String> = emptyMap(),
        lang: String = "",
    ): ScreenSource {
        val hindi = lang.contains("hindi", ignoreCase = true) ||
            MultiSourcePuller.isHindiHint(name, url, null) ||
            url.contains("lan=hindi", ignoreCase = true)
        return ScreenSource(
            name = MultiSourcePuller.linkLabel(name, hindi),
            url = url,
            quality = quality,
            headers = headers,
        )
    }
}
