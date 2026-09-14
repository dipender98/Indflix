package com.multimovies

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import kotlinx.coroutines.withTimeoutOrNull

/** FILE: WyzieSubs.kt - Wyzie Subs client, used only when the user saved their own key. */
object WyzieSubs {

    private const val BASE = "https://sub.wyzie.io"
    private const val TAG = "WyzieSubs"

    /** Single-request budget; the app keeps filling streams while this runs. */
    const val FETCH_BUDGET_MS = 10_000L

    /** Cap per language so one source can't flood the menu. */
    internal const val MAX_PER_LANG = 5

    /** Build the /search URL. Prefers IMDB (always present here), falls back to TMDB. */
    fun buildUrl(
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        codes: Set<String>,
        apiKey: String,
    ): String? {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val id = imdbId?.takeIf { it.startsWith("tt") }
            ?: tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
            ?: return null
        val out = StringBuilder("$BASE/search?id=${enc(id)}")
        if (season != null && season > 0 && episode != null && episode > 0) {
            out.append("&season=$season&episode=$episode")
        }
        val langs = codes.filter { it.isNotBlank() }.distinct().joinToString(",")
        if (langs.isNotBlank()) out.append("&language=${enc(langs)}")
        out.append("&key=${enc(key)}")
        return out.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Tolerant parse: array root, {"subtitles":[...]}, or a single object. */
    internal fun parse(text: String, wantCodes: Set<String>): List<SubtilesProvider.SubTrack> {
        val arr = runCatching {
            val t = text.trim()
            when {
                t.startsWith("[") -> org.json.JSONArray(t)
                t.startsWith("{") -> {
                    val o = org.json.JSONObject(t)
                    o.optJSONArray("subtitles") ?: org.json.JSONArray().put(o)
                }
                else -> null
            }
        }.getOrNull() ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubtilesProvider.SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            val raw = s.optString("language").ifBlank { s.optString("lang") }
            val code = raw.lowercase()
            val code1 = code.take(2)
            if (raw.isBlank() || (code1 !in wantCodes && code !in wantCodes)) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            val canon = SubtitleServices.canonicalName(raw)
            out.add(SubtilesProvider.SubTrack(canon, url, SubtitleServices.subtitleMenuName(canon, raw)))
        }
        return out
    }

    /** Null when the body looks like usable subtitle data; else a short failure reason. */
    internal fun failureReason(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        if (t.startsWith("[")) return null
        if (!t.startsWith("{")) return "server error"
        val o = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return "server error"
        if (o.has("subtitles") || o.has("url")) return null
        val msg = (o.optString("message") + " " + o.optString("error") + " " + o.opt("code")).lowercase()
        return when {
            msg.contains("401") || msg.contains("unauthor") || msg.contains("invalid") ||
                msg.contains("api key") -> "key rejected"
            msg.contains("429") || msg.contains("402") || msg.contains("rate") ||
                msg.contains("limit") || msg.contains("quota") || msg.contains("top-up") ||
                msg.contains("topup") -> "limit reached"
            else -> "server error"
        }
    }

    /** One Wyzie request (+ one unfiltered retry when the filter yields nothing). */
    suspend fun fetchAndDeliver(
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        missing: Set<String>,
        apiKey: String,
        onTrack: suspend (SubtitleFile) -> Unit,
    ): Int {
        if (missing.isEmpty()) return 0
        val codes = SubtilesProvider.codesFromLangs(missing)
        if (codes.isEmpty()) return 0
        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val url = buildUrl(imdbId, tmdbId, season, episode, codes, apiKey) ?: return 0
        Log.d(TAG, "GET ${url.replace(apiKey.trim(), "***")}")
        val text = get(url, deadline)
        if (text.isNullOrBlank()) {
            Log.w(TAG, "no reply - caller falls back to built-in subtitles")
            Settings.notifyWyzieFailed("no reply")
            return 0
        }
        failureReason(text)?.let { reason ->
            Log.w(TAG, "wyzie failed ($reason) - caller falls back to built-in subtitles")
            Settings.notifyWyzieFailed(reason)
            return 0
        }
        var tracks = runCatching { parse(text, codes) }.getOrDefault(emptyList())
        if (tracks.isEmpty() && System.currentTimeMillis() < deadline) {
            // Empty but healthy: the language filter may have excluded everything
            // server-side - one unfiltered retry, the client-side filter still applies.
            Log.d(TAG, "empty with filter - one unfiltered retry")
            buildUrl(imdbId, tmdbId, season, episode, emptySet(), apiKey)?.let { retryUrl ->
                val retryText = get(retryUrl, deadline)
                if (!retryText.isNullOrBlank() && failureReason(retryText) == null) {
                    tracks = runCatching { parse(retryText, codes) }.getOrDefault(emptyList())
                }
            }
        }
        tracks.forEach { onTrack(SubtitleFile(it.menu, it.url)) }
        Log.d(TAG, "${tracks.size} wyzie subs for $imdbId")
        return tracks.size
    }

    /** GET bounded by the shared deadline; null on any failure/timeout. */
    private suspend fun get(url: String, deadline: Long): String? {
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0) return null
        return withTimeoutOrNull(budget) {
            runCatching {
                app.get(url, timeout = (budget / 1000L).coerceAtLeast(1L)).text
            }.getOrNull()
        }
    }
}
