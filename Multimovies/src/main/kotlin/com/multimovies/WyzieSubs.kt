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

    /** One Wyzie request; emits tracks and returns the count (0 = fall back to built-in). */
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
        val url = buildUrl(imdbId, tmdbId, season, episode, codes, apiKey) ?: return 0
        Log.d(TAG, "GET ${url.replace(apiKey.trim(), "***")}")
        val text = withTimeoutOrNull(FETCH_BUDGET_MS) {
            runCatching {
                app.get(url, timeout = FETCH_BUDGET_MS / 1000L).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Log.w(TAG, "no response - caller falls back to built-in subtitles")
            return 0
        }
        val tracks = runCatching { parse(text, codes) }.getOrDefault(emptyList())
        tracks.forEach { onTrack(SubtitleFile(it.menu, it.url)) }
        Log.d(TAG, "${tracks.size} wyzie subs for $imdbId")
        return tracks.size
    }
}
