package com.multimovies

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** FILE: WyzieSubs.kt - Wyzie Subs client, used only when the user saved their own key. */
object WyzieSubs {

    private const val BASE = "https://sub.wyzie.io"
    private const val TAG = "WyzieSubs"

    /** Single-request budget; the app keeps filling streams while this runs. */
    const val FETCH_BUDGET_MS = 10_000L

    /** Source-list budget; the lookup is cached afterwards. */
    internal const val SOURCES_BUDGET_MS = 2_500L
    /** Per-key source-list cache TTL. */
    private const val SOURCES_TTL_MS = 6 * 60 * 60 * 1000L
    private val sourcesCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** Cap per language so one source can't flood the menu. */
    internal const val MAX_PER_LANG = 5

    // One language-group fetch outcome: tracks, hard failure, or blank reply.
    private data class GroupResult(
        val tracks: List<SubtilesProvider.SubTrack>,
        val failure: String?,
        val blank: Boolean,
    )

    /** Build the /search URL. Prefers IMDB (always present here), falls back to TMDB. */
    fun buildUrl(
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        codes: Set<String>,
        apiKey: String,
        sources: List<String>? = null,
    ): String? {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val id = imdbId?.takeIf { it.startsWith("tt") }
            ?: tmdbId?.takeIf { it.matches(Regex("""\d{2,10}""")) }
            ?: return null
        val out = StringBuilder("$BASE/search?id=${enc(id)}")
        if (season != null && season > 0 && episode != null && episode > 0) {
            out.append("&season=$season&episode=$episode")
        }
        // Raw commas (docs show language=en,es) - each code encoded on its own.
        val langs = codes.filter { it.isNotBlank() }.distinct().joinToString(",") { enc(it) }
        if (langs.isNotBlank()) out.append("&language=$langs")
        // Scope to this key's sources; `all` is rejected for some keys.
        out.append(sourceQuery(sources))
        out.append("&key=${enc(key)}")
        return out.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** `&source=a,b` for this key's sources, else empty (fail open to the default). */
    internal fun sourceQuery(sources: List<String>?): String {
        val list = sources?.filter { it.isNotBlank() }?.distinct().orEmpty()
        return if (list.isEmpty()) "" else "&source=" + list.joinToString(",") { enc(it) }
    }

    /** Sources this key can query, via /sources (cached); null when unknown. Never throws. */
    suspend fun keySources(apiKey: String, deadline: Long): List<String>? {
        val key = apiKey.trim().ifEmpty { return null }
        val now = System.currentTimeMillis()
        sourcesCache[key]?.let { (exp, list) -> if (now < exp) return list }
        val (_, text) = runCatching { get("$BASE/sources?key=${enc(key)}", deadline) }.getOrNull()
            ?: return null
        val list = parseSources(text).takeIf { it.isNotEmpty() } ?: return null
        sourcesCache[key] = (System.currentTimeMillis() + SOURCES_TTL_MS) to list
        return list
    }

    /** `available` list, or every source when all are free; empty when unknown. Pure. */
    internal fun parseSources(text: String?): List<String> {
        val o = text?.trim()?.takeIf { it.startsWith("{") }
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return emptyList()
        o.optJSONArray("available")?.let { arr ->
            val out = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            if (out.isNotEmpty()) return out
        }
        if (!o.optBoolean("allFree", false)) return emptyList()
        val arr = o.optJSONArray("sources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

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
    internal fun failureReason(code: Int?, text: String?): String? {
        // "No subtitles found" is a healthy empty result on any status.
        if (text?.contains("no subtitles found", ignoreCase = true) == true) return null
        if (code == 401) return "key rejected"
        if (code == 429 || code == 402) return "limit reached"
        if (code != null && code !in 200..299) {
            // Read the error body first: a 403 can still mean an invalid key.
            val reason = bodyReason(text)
            if (reason != null && reason != "server error") return reason
            return if (code == 400) "request rejected" else "server error ($code)"
        }
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        if (t.startsWith("[")) return null
        if (!t.startsWith("{")) return "server error"
        val o = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return "server error"
        if (o.has("subtitles") || o.has("url")) return null
        return bodyReason(text) ?: "server error"
    }

    /** Classify an error body; null when it looks like usable data. */
    private fun bodyReason(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        if (t.startsWith("[")) return null
        if (!t.startsWith("{")) return "server error"
        val o = runCatching { org.json.JSONObject(t) }.getOrNull() ?: return "server error"
        if (o.has("subtitles") || o.has("url")) return null
        val msg = (o.optString("message") + " " + o.optString("error")).lowercase()
        return when {
            msg.contains("language") || msg.contains("source") || msg.contains("restrict") ||
                msg.contains("upgrade") || msg.contains("plan") -> "request rejected"
            msg.contains("unauthor") || msg.contains("invalid") ||
                msg.contains("api key") -> "key rejected"
            msg.contains("rate") || msg.contains("limit") ||
                msg.contains("quota") || msg.contains("top-up") ||
                msg.contains("top up") || msg.contains("topup") -> "limit reached"
            else -> "server error"
        }
    }

    // Chunked fetch: small parallel requests per language group, not one giant query.
    suspend fun fetchAndDeliver(
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        missing: Set<String>,
        apiKey: String,
        quiet: Boolean = false,
        onTrack: suspend (SubtitleFile) -> Unit,
    ): Int {
        if (missing.isEmpty()) return 0
        val codes = SubtilesProvider.codesFromLangs(missing)
        if (codes.isEmpty()) return 0
        val groups = SubtilesProvider.groupRequests(codes)
        if (groups.isEmpty()) return 0
        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val masked = apiKey.trim()
        // Scope searches to this key's sources first (cached after the first play).
        val srcEnd = minOf(deadline, System.currentTimeMillis() + SOURCES_BUDGET_MS)
        val sources = runCatching { keySources(apiKey, srcEnd) }.getOrNull()
        Log.d(TAG, "sources=${sources?.joinToString(",") ?: "default"}")
        // One small request per group, all in flight at once.
        val results = coroutineScope {
            groups.map { group ->
                async {
                    val url = buildUrl(imdbId, tmdbId, season, episode, group, apiKey, sources)
                        ?: return@async GroupResult(emptyList(), null, false)
                    Log.d(TAG, "GET ${url.replace(masked, "***")}")
                    val (code, text) = get(url, deadline)
                    if (text.isNullOrBlank()) return@async GroupResult(emptyList(), null, true)
                    failureReason(code, text)?.let { return@async GroupResult(emptyList(), it, false) }
                    GroupResult(runCatching { parse(text, group) }.getOrDefault(emptyList()), null, false)
                }
            }.awaitAll()
        }
        // Merge priority-first, deduped by url and capped per language.
        val seen = HashSet<String>()
        val perLang = HashMap<String, Int>()
        var tracks = results.flatMap { it.tracks }.filter { t ->
            if (!seen.add(t.url)) return@filter false
            if ((perLang[t.lang] ?: 0) >= MAX_PER_LANG) return@filter false
            perLang[t.lang] = (perLang[t.lang] ?: 0) + 1
            true
        }
        if (tracks.isEmpty()) {
            results.mapNotNull { it.failure }.firstOrNull()?.let { reason ->
                Log.w(TAG, "wyzie failed ($reason) - caller falls back to built-in subtitles")
                if (!quiet) Settings.notifyWyzieFailed(reason)
                return 0
            }
            if (System.currentTimeMillis() < deadline) {
                // Empty but healthy: the filter may have excluded everything server-side.
                Log.d(TAG, "empty with filter - one unfiltered retry")
                buildUrl(imdbId, tmdbId, season, episode, emptySet(), apiKey, sources)?.let { retryUrl ->
                    val (retryCode, retryText) = get(retryUrl, deadline)
                    if (!retryText.isNullOrBlank() && failureReason(retryCode, retryText) == null) {
                        tracks = runCatching { parse(retryText, codes) }.getOrDefault(emptyList())
                    }
                }
            }
            if (tracks.isEmpty()) {
                if (results.all { it.blank }) {
                    Log.w(TAG, "no reply - caller falls back to built-in subtitles")
                    if (!quiet) Settings.notifyWyzieFailed("no reply")
                } else {
                    Log.d(TAG, "0 wyzie subs for $imdbId")
                }
                return 0
            }
        }
        tracks.forEach { onTrack(SubtitleFile(it.menu, it.url)) }
        Log.d(TAG, "${tracks.size} wyzie subs for $imdbId")
        return tracks.size
    }

    /** Live key check used by the Verify link and the save check. Never throws. */
    suspend fun testKey(apiKey: String): Pair<Boolean, String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return false to "Enter a key first"
        val end = System.currentTimeMillis() + FETCH_BUDGET_MS
        val sources = runCatching { keySources(key, minOf(end, System.currentTimeMillis() + SOURCES_BUDGET_MS)) }.getOrNull()
        val url = buildUrl("tt1375666", null, null, null, emptySet(), key, sources)
            ?: return false to "Enter a key first"
        val (code, text) = get(url, end)
        failureReason(code, text)?.let { return false to "Key failed ($it)" }
        val n = runCatching { parse(text.orEmpty(), setOf("en")) }.getOrDefault(emptyList()).size
        return if (n > 0) true to "Key works - $n English subs found"
        else false to "Key accepted but no subs returned"
    }

    /** GET bounded by the shared deadline; (status code, body), nulls on failure/timeout. */
    private suspend fun get(url: String, deadline: Long): Pair<Int?, String?> {
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0) return null to null
        return withTimeoutOrNull(budget) {
            runCatching {
                val r = app.get(url, timeout = (budget / 1000L).coerceAtLeast(1L))
                r.code to r.text
            }.getOrNull()
        } ?: (null to null)
    }
}
