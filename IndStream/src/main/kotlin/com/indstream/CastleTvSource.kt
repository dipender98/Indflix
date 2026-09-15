package com.indstream

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** App-API client: encrypted JSON API with per-language audio tracks. */
object CastleTvSource {

    const val BASE = "https://api.hlowb.com"
    private const val CHANNEL = "IndiaA"
    private const val CLIENT = "1"
    private const val LANG = "en-US"
    private const val PKG = "com.external.castle"
    private const val APK_SIGN = "ED0955EB04E67A1D9F3305B95454FED485261475"
    const val SECKEY_TTL_MS = 60 * 60 * 1000L

    private val apiHeaders = mapOf(
        "User-Agent" to "okhttp/4.9.3",
        "Accept" to "application/json",
        "Referer" to BASE,
    )

    @Volatile private var secKey: String? = null
    @Volatile private var secKeyAt: Long = 0L

    data class Track(val languageId: String, val languageName: String, val existVideo: Boolean)
    data class Video(val url: String, val quality: String)

    /** Cached security key (hour TTL). Null on failure. */
    suspend fun securityKey(): String? {
        val k = secKey
        if (k != null && System.currentTimeMillis() - secKeyAt < SECKEY_TTL_MS) return k
        val fresh = withTimeoutOrNull(8_000L) {
            runCatching {
                app.get("$BASE/v0.1/system/getSecurityKey/1?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG",
                    timeout = 8, headers = apiHeaders).text
            }.getOrNull()?.let { JSONObject(it).optString("data").takeIf { s -> s.isNotBlank() } }
        }
        if (fresh != null) { secKey = fresh; secKeyAt = System.currentTimeMillis() }
        else Log.w("CastleTV", "security key fetch failed")
        return fresh ?: k
    }

    /** Standard base64 decode (no Android APIs: unit-test safe, minSdk 21 safe). Pure. */
    internal fun b64decode(input: String): ByteArray {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = input.trim().replace(Regex("\\s+"), "").trimEnd('=')
        val out = ByteArray((clean.length * 6) / 8)
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

    /** AES-128-CBC decrypt (key = base64 secret + suffix, first 16 bytes; IV = key). Null on failure. */
    fun decrypt(cipherB64: String, secKeyB64: String): String? = runCatching {
        val raw = b64decode(secKeyB64)
        val suf = "T!BgJB".toByteArray(Charsets.UTF_8)
        val key = ByteArray(16)
        val n = minOf(raw.size, 16)
        System.arraycopy(raw, 0, key, 0, n)
        if (n < 16) System.arraycopy(suf, 0, key, n, minOf(suf.size, 16 - n))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        val ct = b64decode(cipherB64.trim().replace("-_.", "/").replace("@", "+"))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Unwrap {data: cipher} envelope (or raw cipher) and decrypt. Null on failure. */
    fun decryptEnvelope(text: String, secKeyB64: String): JSONObject? {
        val cipher = runCatching {
            val root = JSONObject(text.trim())
            val d = root.optString("data")
            if (d.isNotBlank()) d else text.trim()
        }.getOrNull() ?: text.trim()
        if (cipher.isBlank()) return null
        // Raw JSON (unencrypted fallback) passes through.
        runCatching { JSONObject(cipher) }.getOrNull()?.let { return it }
        return decrypt(cipher, secKeyB64)?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun unwrapData(root: JSONObject): JSONObject {
        val d = root.optJSONObject("data")
        return d ?: root
    }

    /** Title search; returns rows as (id, title). Empty on failure. */
    suspend fun search(sec: String, keyword: String): List<Pair<String, String>> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val text = withTimeoutOrNull(10_000L) {
            runCatching {
                app.get("$BASE/film-api/v1.1.0/movie/searchByKeyword?channel=$CHANNEL&clientType=$CLIENT" +
                    "&keyword=$enc&lang=$LANG&mode=1&packageName=$PKG&page=1&size=30",
                    timeout = 10, headers = apiHeaders).text
            }.getOrNull()
        } ?: return emptyList()
        val rows = decryptEnvelope(text, sec)?.let { unwrapData(it).optJSONArray("rows") } ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = r.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val title = r.optString("title").ifBlank { r.optString("name") }
            if (title.isBlank()) null else id to title
        }
    }

    /** Title details (seasons + episodes with tracks). Null on failure. */
    suspend fun details(sec: String, movieId: String): JSONObject? {
        val text = withTimeoutOrNull(10_000L) {
            runCatching {
                app.get("$BASE/film-api/v1.9.9/movie?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG" +
                    "&movieId=$movieId&packageName=$PKG", timeout = 10, headers = apiHeaders).text
            }.getOrNull()
        } ?: return null
        return decryptEnvelope(text, sec)?.let { unwrapData(it) }
    }

    /** Resolve one video URL set (per-language or shared when languageId is null). Null on failure. */
    suspend fun video(sec: String, movieId: String, episodeId: String, languageId: String?, resolution: Int): JSONObject? {
        val body = mutableMapOf<String, Any>(
            "mode" to "1", "appMarket" to "GuanWang", "clientType" to CLIENT,
            "woolUser" to "false", "apkSignKey" to APK_SIGN, "androidVersion" to "13",
            "movieId" to movieId, "episodeId" to episodeId,
            "isNewUser" to "true", "resolution" to resolution.toString(), "packageName" to PKG,
        )
        if (languageId != null) body["languageId"] = languageId
        val text = withTimeoutOrNull(10_000L) {
            runCatching {
                app.post("$BASE/film-api/v2.0.1/movie/getVideo2?clientType=$CLIENT&packageName=$PKG" +
                    "&channel=$CHANNEL&lang=$LANG", timeout = 10,
                    headers = apiHeaders + ("Content-Type" to "application/json"), json = body).text
            }.getOrNull()
        } ?: return null
        return decryptEnvelope(text, sec)?.let { unwrapData(it) }
    }

    /** Hindi-first track pick, then per-track video, else first track. */
    fun pickTrack(tracks: List<Track>): Track? {
        if (tracks.isEmpty()) return null
        tracks.firstOrNull { it.languageName.contains("hindi", ignoreCase = true) }?.let { return it }
        tracks.firstOrNull { it.existVideo }?.let { return it }
        return tracks.first()
    }

    /** Quality label from description/resolution number; blank when unknown. Pure. */
    fun qualityOf(description: String?, resNum: Int): String {
        if (!description.isNullOrBlank()) {
            Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE).find(description)?.let {
                val h = it.groupValues[1].toIntOrNull()
                if (h != null && h in setOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160)) return "${h}p"
            }
            if (Regex("""\b(4k|uhd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(description)) return "4K"
        }
        return when (resNum) { 1 -> "480p"; 2 -> "720p"; 3 -> "1080p"; 4 -> "4K"; else -> "" }
    }

    /** Videos out of a getVideo response. Pure. */
    fun videosOf(data: JSONObject, resolution: Int): List<Video> {
        val single = data.optString("videoUrl").takeIf { it.isNotBlank() }
        val arr = data.optJSONArray("videos")
        if ((arr == null || arr.length() == 0) && single != null) {
            return listOf(Video(single, qualityOf(data.optString("resolutionDescription"), resolution)))
        }
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = v.optString("url").ifBlank { single } ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            Video(url, qualityOf(v.optString("resolutionDescription"), v.optInt("resolution", resolution)))
        }.distinctBy { it.url }
    }
}
