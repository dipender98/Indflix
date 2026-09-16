package com.indstream

import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** OneTouchTV (api3.devcorp.me): title search, AES-256-CBC envelope, per-episode HLS sources. */
object OneTouchTvSource {

    private const val API = "https://api3.devcorp.me"
    private const val REFERER = "https://onetouchtv.xyz/"
    private val KEY = "im72charPasswordofdInitVectorStm".toByteArray(Charsets.UTF_8)
    private val IV = "im72charPassword".toByteArray(Charsets.UTF_8)

    fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept" to "application/json, */*",
        "Referer" to REFERER,
    )

    /** Custom-base64 AES-256-CBC envelope to JSON. Null on any failure. Pure except Base64. */
    fun decryptToJson(text: String): JSONObject? = runCatching {
        var s = text.replace("-_.", "/").replace("@", "+").replace(Regex("\\s+"), "")
        s += "=".repeat((4 - s.length % 4) % 4)
        val raw = java.util.Base64.getDecoder().decode(s)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        JSONObject(String(c.doFinal(raw), Charsets.UTF_8))
    }.getOrNull()

    data class Hit(val id: String, val title: String, val year: String, val type: String)
    data class Source(val url: String, val quality: String, val type: String)
    data class Episode(val playId: String, val number: Int)

    /** Search titles. Never throws. */
    suspend fun search(keyword: String): List<Hit> {
        return try {
            val enc = com.lagradost.cloudstream3.app.get(
                "$API/vod/search?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}",
                timeout = 12, headers = apiHeaders(),
            ).text
            val arr = decryptToJson(enc)?.optJSONArray("result") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Hit(id, o.optString("title"), o.optString("year"), o.optString("type"))
            }
        } catch (e: Exception) {
            Log.w("OneTouchTV", "search failed: ${e.message}")
            emptyList()
        }
    }

    /** Episode list for a title id. Never throws. */
    suspend fun episodes(id: String): List<Episode> {
        return try {
            val enc = com.lagradost.cloudstream3.app.get(
                "$API/vod/$id/detail", timeout = 12, headers = apiHeaders(),
            ).text
            val arr = decryptToJson(enc)?.optJSONObject("result")?.optJSONArray("episodes")
                ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val pid = o.optString("playId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Episode(pid, o.optString("episode").toIntOrNull() ?: (i + 1))
            }
        } catch (e: Exception) {
            Log.w("OneTouchTV", "detail failed: ${e.message}")
            emptyList()
        }
    }

    /** Stream sources + subtitle tracks for one episode. Never throws. */
    suspend fun streams(id: String, playId: String): Pair<List<Source>, List<Pair<String, String>>> {
        return try {
            val enc = com.lagradost.cloudstream3.app.get(
                "$API/vod/$id/episode/$playId", timeout = 12, headers = apiHeaders(),
            ).text
            val res = decryptToJson(enc)?.optJSONObject("result")
                ?: return emptyList<Source>() to emptyList()
            val sources = res.optJSONArray("sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Source(url, o.optString("quality"), o.optString("type"))
                }
            } ?: emptyList()
            val subs = res.optJSONArray("track")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    (o.optString("name").ifBlank { "English" }) to url
                }
            } ?: emptyList()
            sources to subs
        } catch (e: Exception) {
            Log.w("OneTouchTV", "episode failed: ${e.message}")
            emptyList<Source>() to emptyList()
        }
    }

    /** Normalized title match with year guard. Pure. */
    fun matchTitle(hits: List<Hit>, title: String, year: Int?, wantType: String): Hit? {
        fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")
        val tn = norm(title)
        if (tn.length < 3) return null
        val typeOk: (Hit) -> Boolean = { h ->
            if (wantType == "movie") h.type.equals("movie", true)
            else !h.type.equals("movie", true)
        }
        val yearOk: (Hit) -> Boolean = { h ->
            year == null || year <= 0 || h.year.toIntOrNull() == null || h.year.toIntOrNull() == year
        }
        return hits.filter { typeOk(it) && yearOk(it) }.firstOrNull { norm(it.title) == tn }
            ?: hits.filter { typeOk(it) && yearOk(it) }.firstOrNull {
                norm(it.title).startsWith(tn) || tn.startsWith(norm(it.title))
            }
    }

    /** Quality label to ladder height. Pure. */
    fun heightOf(quality: String?): Int {
        val q = (quality ?: "").lowercase()
        if (q.contains("2160") || q.contains("4k")) return 2160
        if (q.contains("1080")) return 1080
        if (q.contains("720")) return 720
        if (q.contains("480")) return 480
        if (q.contains("360")) return 360
        return 0
    }

    fun playbackHeaders(): Map<String, String> = mapOf("Referer" to "$API/")
    fun playbackReferer(): String = "$API/"
}
