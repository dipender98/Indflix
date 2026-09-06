package com.indstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * FILE: SubtitleFallback.kt — last-resort subtitle source for titles where
 * the stream servers carry NO captions of their own (user spec, Sept 2026:
 * "servers give their own subtitles — take them; if some don't, use
 * OpenSubtitles or another provider").
 *
 * Provider: the OpenSubtitles v3+ Stremio community addon
 * (`opensubtitles.stremio.homes`), the same keyless service CSX / Cineverse
 * plugins use for subtitle fan-out. Verified live Sept 2026:
 *   GET /{langs}/ai-translated=true|from=all|auto-adjustment=true
 *       /subtitles/movie/{imdb}.json
 *       /subtitles/series/{imdb}:{season}:{episode}.json
 *   → {"subtitles":[{lang_code, lang, title, url (sub.vtt), ...}]}
 * URL-encoded "|" is also accepted; the "|" codes are the addon's language
 * list (max 10 — a longer or unsupported list 400s; verified pa/gu rejected,
 * hi/en/ta/te/ml/bn/ur/mr/kn/si accepted).
 *
 * Budget rules (user: subtitle work must never delay playback long):
 *  - Streams/links are already delivered before this runs; it only adds
 *    subtitle entries, so worst case the user has one subtitle source
 *    instead of many.
 *  - Hard [FETCH_BUDGET_MS] timeout — expired results are dropped, never
 *    retried in the same process run beyond the small [CACHE_TTL_MS] cache.
 *  - Per-language cap keeps the subtitle menu readable.
 */
object SubtitleFallback {

    private const val BASE = "https://opensubtitles.stremio.homes"
    /** Indian-market + global languages, verified accepted by the addon. */
    private const val LANGS = "en|hi|ta|te|ml|bn|ur|mr|kn|si"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    /** Wall-clock budget for the whole fetch. 000ms keeps worst-case subtitle
     *  top-up under seven more seconds after playback already started. */
    private const val FETCH_BUDGET_MS = 6_500L
    /** How many tracks per language survive into the menu (addon returns
     *  dozens of near-duplicate releases otherwise). */
    private const val MAX_PER_LANG = 3
    /** Re-watching the same episode within the TTL reuses the parsed list. */
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    /** Canonical subtitle language -> addon code (subset of [LANGS]). */
    private val CODES = mapOf(
        "English" to "en", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Sinhala" to "si",
    )

    /** The languages worth requesting from the fallback provider:
     *  Hindi + English + the title's original language when the code is in
     *  the addon's supported set. Everything else the user can live without. */
    fun desiredLanguages(originalLang: String?): Set<String> {
        val wanted = LinkedHashSet(setOf("Hindi", "English"))
        originalLang?.let { LinkNaming.languageTag(it) }?.let { wanted.add(it) }
        return wanted.filter { CODES.containsKey(it) }.toSet()
    }

    /** Which of [desired] none of the stream servers carried ([covered] =
     *  canonical names emitted by the server-owned subtitle pass). */
    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    /** Build the addon URL for one imdb id. Movies + series paths verified
     *  live (200 with subtitles). season/episode <= 0 -> movie. [langs] are
     *  canonical language names ("Hindi", "English", …) — unmapped names are
     *  dropped; a fully-empty set falls back to "en|hi". */
    internal fun buildUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val codes = langs.mapNotNull { CODES[it] }
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /**
     * Fetch fallback subtitles for [imdbId] covering [missing] (canonical
     * language names). Empty list on any failure/timeout — this must never
     * break playback. Results cached per title+episode.
     */
    suspend fun fetch(
        imdbId: String?,
        season: Int,
        episode: Int,
        missing: Set<String>,
    ): List<SubtitleFile> {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        if (missing.isEmpty()) return emptyList()
        val langs = missing.mapNotNull { CODES[it] }.toSet()
        if (langs.isEmpty()) return emptyList()

        val key = "$imdb|$season|$episode|${langs.sorted().joinToString(",")}"
        cache[key]?.let { (exp, subs) ->
            if (System.currentTimeMillis() < exp) return subs
            cache.remove(key)
        }

        val url = buildUrl(imdb, season, episode, missing)
        Log.d("SubtitleFallback", "GET $url")
        val text = withTimeoutOrNull(FETCH_BUDGET_MS) {
            runCatching {
                com.lagradost.cloudstream3.app.get(url, timeout = 6).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Log.w("SubtitleFallback", "no response within budget — dropped (streams unaffected)")
            return emptyList()
        }
        val subs = runCatching { parse(text, langs) }.getOrDefault(emptyList())
        if (subs.isNotEmpty()) put(key, subs)
        Log.d("SubtitleFallback", "${subs.size} fallback subs for $imdb (langs=$langs)")
        return subs
    }

    /** Parse the addon response keeping [MAX_PER_LANG] per requested code. */
    internal fun parse(text: String, wantCodes: Set<String>): List<SubtitleFile> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubtitleFile>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            val code = (s.optString("lang_code").ifBlank { s.optString("lang") }).lowercase()
            // lang_code is ISO1 ("hi"), lang may be ISO2 ("hin"/"per") — accept
            // only the languages we actually asked for.
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            val name = LinkNaming.canonicalSubtitleName(s.optString("lang_code")
                .ifBlank { s.optString("lang") })
            out.add(SubtitleFile(name, url))
        }
        return out
    }

    private fun put(key: String, subs: List<SubtitleFile>) {
        if (cache.size >= CACHE_MAX) {
            val now = System.currentTimeMillis()
            cache.entries.filter { it.value.first <= now }.forEach { cache.remove(it.key) }
            if (cache.size >= CACHE_MAX) return // still full of fresh entries — skip caching
        }
        cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
    }
}
