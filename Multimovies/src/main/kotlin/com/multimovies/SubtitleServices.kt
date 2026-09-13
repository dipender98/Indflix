package com.multimovies

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Normalizes subtitle language names and provides fallback subtitle sources. */
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

/** Fetches fallback subtitles. */
object SubtilesProvider {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val SENSE_BASE = "https://subsense.nepiraw.com"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    /** Maximum wall-clock budget for the complete subtitle fetch. */
    const val FETCH_BUDGET_MS = 13_000L
    /** Timeout for the best-effort SubSense top-up. */
    private const val SENSE_CALL_MS = 5_000L
    /** Maximum tracks retained per language. */
    internal const val MAX_PER_LANG = 3
    /** Maximum tracks retained per language. */
    internal const val SENSE_MAX_PER_LANG = 5
    /** Re-watching the same episode within the TTL reuses the parsed list. */
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64

    /** Canonical subtitle language -> addon code (ISO-1). */
    private val CODES = mapOf(
        "English" to "en", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Sinhala" to "si",
    )

    /** Canonical language names mapped to supported SubSense ISO-3 codes. */
    private val SENSE_ISO3 = mapOf(
        "English" to "eng", "Hindi" to "hin", "Tamil" to "tam", "Telugu" to "tel",
        "Malayalam" to "mal", "Bengali" to "ben", "Urdu" to "urd",
        "Marathi" to "mar", "Kannada" to "kan", "Sinhala" to "sin",
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    /** Canonical names of languages worth always having. */
    fun desiredLanguages(): Set<String> = setOf("Hindi", "English")

    /** Returns requested languages not covered by stream-owned subtitles. */
    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    /** Maps canonical names to ordered addon codes, dropping unsupported names. */
    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())

    /** Priority language codes requested first. */
    internal val PRIORITY_CODES: Set<String> = linkedSetOf("hi", "en")

    /** Splits request codes into priority and remaining groups for concurrent fetching. */
    internal fun splitGroups(codes: Set<String>): Pair<Set<String>?, Set<String>?> {
        if (codes.isEmpty()) return null to null
        val priority = codes.filterTo(LinkedHashSet()) { it in PRIORITY_CODES }
        val rest = codes.filterTo(LinkedHashSet()) { it !in PRIORITY_CODES }
        return (priority.takeIf { it.isNotEmpty() }) to (rest.takeIf { it.isNotEmpty() })
    }

    /** Returns requested languages absent. */
    internal fun stillMissing(wanted: Set<String>, tracks: List<SubTrack>): Set<String> =
        wanted - tracks.map { it.lang }.toSet()

    /** Maps requested languages to supported SubSense ISO-3 codes. */
    internal fun subSenseLanguages(names: Set<String>): Set<String> {
        val mapped = names.mapNotNull { SENSE_ISO3[it] }.toCollection(LinkedHashSet())
        return mapped.ifEmpty { linkedSetOf("eng") }
    }

    /** Builds an addon URL for a movie or series. */
    internal fun buildUrl(imdbId: String, season: Int?, episode: Int?, langs: Set<String>): String {
        val codes = codesFromLangs(langs)
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season != null && season > 0 && episode != null && episode > 0)
            "series/$imdbId:$season:$episode" else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Builds a URL-encoded SubSense request for a movie or series. */
    internal fun subSenseUrl(imdbId: String, season: Int?, episode: Int?, langs: Set<String>): String {
        val list = subSenseLanguages(langs).joinToString(",") { "\"$it\"" }
        val cfg = """{"languages":[$list],"maxSubtitles":${SENSE_MAX_PER_LANG + 1}}"""
        val idPart = if (season != null && season > 0 && episode != null && episode > 0)
            "series/$imdbId:$season:$episode" else "movie/$imdbId"
        return "$SENSE_BASE/" + URLEncoder.encode(cfg, "UTF-8") + "/subtitles/$idPart.json"
    }

    /** Parsed subtitle track with canonical language and download URL. */
    internal data class SubTrack(val lang: String, val url: String)

    /** Parses and caps OpenSubtitles addon tracks for requested codes. */
    internal fun parseOpenSubtitles(text: String, wantCodes: Set<String>): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            val code = (s.optString("lang_code").ifBlank { s.optString("lang") }).lowercase()
            // Prefer ISO-1 codes, falling back to ISO-3 language values.
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            out.add(SubTrack(SubtitleServices.canonicalName(s.optString("lang_code")
                .ifBlank { s.optString("lang") }), url))
        }
        return out
    }

    /** Parses and caps SubSense tracks, using language or ID fallback. */
    internal fun parseSubSense(text: String): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            // Use the language field first; fall back to ID tokens.
            var canon = ""
            if (s.optString("lang").isNotBlank()) {
                canon = SubtitleServices.canonicalName(s.optString("lang"))
            } else {
                for (tok in s.optString("id").split('-')) {
                    val c = SubtitleServices.canonicalName(tok)
                    if (c != "Subtitle" && c != tok.lowercase()) { canon = c; break }
                }
            }
            if (canon.isEmpty()) canon = "Subtitle"
            if (canon == "Subtitle") continue
            if ((perLang[canon] ?: 0) >= SENSE_MAX_PER_LANG) continue
            perLang[canon] = (perLang[canon] ?: 0) + 1
            out.add(SubTrack(canon, url))
        }
        return out
    }

    /** Merges groups in priority order, deduplicates URLs, and applies caps. */
    internal fun mergeGroups(groups: List<List<SubTrack>>): List<SubTrack> {
        val out = LinkedHashMap<String, SubTrack>()
        for (group in groups) for (t in group) {
            if (out.values.none { it.url == t.url }) out[t.lang + t.url] = t
        }
        val perLang = HashMap<String, Int>()
        val capped = mutableListOf<SubTrack>()
        for (t in out.values) {
            if ((perLang[t.lang] ?: 0) >= SENSE_MAX_PER_LANG) continue
            perLang[t.lang] = (perLang[t.lang] ?: 0) + 1
            capped.add(t)
        }
        return capped
    }

    /** Fetches missing subtitles within the configured budget and caches results. */
    suspend fun fetch(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        missing: Set<String>,
    ): List<SubtitleFile> {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        if (missing.isEmpty()) return emptyList()
        val codes = codesFromLangs(missing)
        if (codes.isEmpty()) return emptyList()

        val key = "$imdb|$season|$episode|${codes.sorted().joinToString(",")}"
        cache[key]?.let { (exp, subs) ->
            if (System.currentTimeMillis() < exp) return subs
            cache.remove(key)
        }

        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val (priority, rest) = splitGroups(codes)
        val tracks = coroutineScope {
            val prioJob = priority?.let { launchFetch(imdb, season, episode, it, deadline) }
            val restJob = rest?.let { launchFetch(imdb, season, episode, it, deadline) }
            val prio = prioJob?.await() ?: emptyList()
            val others = restJob?.await() ?: emptyList()
            var retry = emptyList<SubTrack>()
            if (prio.isEmpty() && others.isEmpty() && priority != null &&
                System.currentTimeMillis() < deadline
            ) {
                // Retry the priority group once if both initial groups fail.
                Log.d("SubtilesProvider", "both groups empty - one priority retry")
                retry = launchFetch(imdb, season, episode, priority, deadline).await()
            }
            var merged = mergeGroups(listOf(prio, retry, others))
            val gaps = stillMissing(missing, merged)
            if (gaps.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val sense = fetchSubSense(imdb, season, episode, gaps, deadline)
                merged = mergeGroups(listOf(merged, sense))
            }
            merged
        }
        val subs = tracks.map { SubtitleFile(it.lang, it.url) }
        if (subs.isNotEmpty()) put(key, subs)
        Log.d("SubtilesProvider", "${subs.size} fallback subs for $imdb (codes=$codes)")
        return subs
    }

    /** Fetches and parses one addon group before the shared deadline. */
    private fun CoroutineScope.launchFetch(
        imdb: String, season: Int?, episode: Int?, codes: Set<String>, deadline: Long,
    ): Deferred<List<SubTrack>> = async {
        val url = buildUrlOf(imdb, season, episode, codes)
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0) return@async emptyList()
        val tracks = fetchGroup(url, codes, budget)
        Log.d("SubtilesProvider", "group ${codes.joinToString("|")}: ${tracks.size} tracks")
        tracks
    }

    /** HTTP + parse for one group; null-safe: any failure -> empty list. */
    private suspend fun fetchGroup(url: String, codes: Set<String>, budgetMs: Long): List<SubTrack> {
        Log.d("SubtilesProvider", "GET $url")
        val text = withTimeoutOrNull(budgetMs) {
            runCatching {
                com.lagradost.cloudstream3.app.get(url, timeout = (budgetMs / 1000L).coerceAtLeast(1L)).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Log.w("SubtilesProvider", "no response within budget - dropped (streams unaffected)")
            return emptyList()
        }
        return runCatching { parseOpenSubtitles(text, codes) }.getOrDefault(emptyList())
    }

    /** Builds an addon URL. */
    private fun buildUrlOf(imdbId: String, season: Int?, episode: Int?, codes: Set<String>): String {
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season != null && season > 0 && episode != null && episode > 0)
            "series/$imdbId:$season:$episode" else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Best-effort SubSense top-up bounded by the remaining fetch window. */
    private suspend fun fetchSubSense(
        imdb: String, season: Int?, episode: Int?, gaps: Set<String>, deadline: Long,
    ): List<SubTrack> {
        val budget = minOf(SENSE_CALL_MS, deadline - System.currentTimeMillis())
        if (budget <= 0) return emptyList()
        val url = subSenseUrl(imdb, season, episode, gaps)
        Log.d("SubtilesProvider", "GET(sense) $url")
        val text = withTimeoutOrNull(budget) {
            runCatching {
                com.lagradost.cloudstream3.app.get(url, timeout = (budget / 1000L).coerceAtLeast(1L)).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) {
            Log.w("SubtilesProvider", "subsense: no response - top-up skipped")
            return emptyList()
        }
        return runCatching { parseSubSense(text) }.getOrDefault(emptyList())
    }

    private fun put(key: String, subs: List<SubtitleFile>) {
        if (cache.size >= CACHE_MAX) {
            val now = System.currentTimeMillis()
            cache.entries.filter { it.value.first <= now }.forEach { cache.remove(it.key) }
            if (cache.size >= CACHE_MAX) return // Cache is full of fresh entries; skip this write.
        }
        cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
    }
}