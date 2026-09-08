package com.multimovies

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FILE: SubtitleServices.kt — subtitle normalization + the OpenSubtitles
 * provider for Multimovies (user spec Sept 2026 rewrite #2: server captions
 * are ignored; [SubtilesProvider] IS the subtitle source, fetched when the
 * stream starts).
 *
 *  - [canonicalName] maps raw API language strings (ISO codes, native script,
 *    3-letter codes like ara/ger/hin) to full display names: "Hindi",
 *    "English", "Urdu", …
 *  - [SubtilesProvider.fetch] fetches the wanted languages from the
 *    OpenSubtitles v3+ Stremio community addon
 *    (opensubtitles.stremio.homes — keyless, the same service CSX uses).
 *    Hard-bounded by [FALLBACK_BUDGET_MS]; never blocks or breaks playback.
 */
object SubtitleServices {

    /** Native-script / code subtitle names -> canonical English names. */
    fun canonicalName(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return "Subtitle"
        val lower = s.lowercase()
        return when {
            lower.contains("\u0939\u093f\u0928\u094d\u0926") || lower.contains("\u0939\u093f\u0902\u0926") ||
                lower == "hi" || lower == "hin" || lower.contains("hindi") -> "Hindi"
            lower.contains("urdu") || lower == "ur" || lower == "urd" || lower.contains("\u0627\u0631\u062f\u0648") -> "Urdu"
            lower.contains("bengali") || lower.contains("bangla") || lower == "bn" || lower == "ben" -> "Bengali"
            lower.contains("arabic") || lower == "ar" || lower == "ara" -> "Arabic"
            lower.contains("russian") || lower == "ru" || lower == "rus" -> "Russian"
            lower.contains("chinese") || lower.contains("mandarin") || lower == "zh" || lower == "chi" -> "Chinese"
            lower.contains("japanese") || lower == "ja" || lower == "jpn" -> "Japanese"
            lower.contains("korean") || lower == "ko" || lower == "kor" -> "Korean"
            lower.contains("french") || lower == "fr" || lower == "fre" -> "French"
            lower.contains("spanish") || lower == "es" || lower == "spa" -> "Spanish"
            lower.contains("portuguese") || lower == "pt" || lower == "por" -> "Portuguese"
            lower.contains("english") || lower == "en" || lower == "eng" -> "English"
            lower.contains("tamil") || lower == "ta" || lower == "tam" -> "Tamil"
            lower.contains("telugu") || lower == "te" || lower == "tel" -> "Telugu"
            lower.contains("malayalam") || lower == "ml" || lower == "mal" -> "Malayalam"
            lower.contains("kannada") || lower == "kn" || lower == "kan" -> "Kannada"
            lower.contains("marathi") || lower == "mr" || lower == "mar" -> "Marathi"
            lower.contains("punjabi") || lower == "pa" || lower == "pan" -> "Punjabi"
            lower.contains("gujarati") || lower == "gu" || lower == "guj" -> "Gujarati"
            lower.contains("thai") || lower == "th" || lower == "tha" -> "Thai"
            lower.contains("turkish") || lower == "tr" || lower == "tur" -> "Turkish"
            lower.contains("german") || lower == "de" || lower == "ger" -> "German"
            lower.contains("italian") || lower == "it" || lower == "ita" -> "Italian"
            else -> s.substringBefore('-').substringBefore(' ').ifBlank { "Subtitle" }
        }
    }
}

/**
 * OpenSubtitles fallback â€” same provider as IndStream's SubtilesProvider
 * (verified live Sept 2026). Runs AFTER links were delivered; a slow or dead
 * lookup costs at most the timeout and then is dropped (per the 2026 user
 * spec: never block a stream on subtitles).
 */
object SubtilesProvider {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    private const val FALLBACK_BUDGET_MS = 6_500L
    private const val MAX_PER_LANG = 3
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    private val CODES = mapOf(
        "English" to "en", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Sinhala" to "si",
    )
    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    /** Canonical names of languages worth always having. */
    fun desiredLanguages(): Set<String> = setOf("Hindi", "English")

    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    internal fun buildUrl(imdbId: String, season: Int?, episode: Int, langs: Set<String>): String {
        val codes = langs.mapNotNull { CODES[it] }
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season != null && season > 0 && episode > 0)
            "series/$imdbId:$season:$episode" else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    suspend fun fetch(
        imdbId: String?,
        season: Int?,
        episode: Int?,
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

        val url = buildUrl(imdb, season, episode ?: -1, missing)
        Log.d("SubtilesProvider", "GET $url")
        val text = withTimeoutOrNull(FALLBACK_BUDGET_MS) {
            runCatching {
                com.lagradost.cloudstream3.app.get(url, timeout = 6).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Log.w("SubtilesProvider", "no response within budget â€” dropped (streams unaffected)")
            return emptyList()
        }
        val subs = runCatching { parse(text, langs) }.getOrDefault(emptyList())
        if (subs.isNotEmpty() && cache.size < 64) {
            cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
        }
        Log.d("SubtilesProvider", "${subs.size} fallback subs for $imdb (langs=$langs)")
        return subs
    }

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
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            out.add(
                SubtitleFile(
                    SubtitleServices.canonicalName(s.optString("lang_code").ifBlank { s.optString("lang") }),
                    url,
                ),
            )
        }
        return out
    }
}
