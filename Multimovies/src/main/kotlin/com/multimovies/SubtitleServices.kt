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
            // Devanagari Hindi: native script or the script name; roman-labeled Hinglish is still the Hindi bucket.
            lower.contains("\u0939\u093f\u0928\u094d\u0926") || lower.contains("\u0939\u093f\u0902\u0926") ||
                lower.contains("\u0926\u0947\u0935\u0928\u093e\u0917\u0930\u0940") ||
                lower == "hi" || lower == "hin" || lower.contains("hindi") || lower.contains("hinglish") -> "Hindi"
            lower.contains("urdu") || lower == "ur" || lower == "urd" || lower.contains("\u0627\u0631\u062f\u0648") -> "Urdu"
            lower.contains("bengali") || lower.contains("bangla") || lower.contains("\u09ac\u09be\u0982\u09b2") ||
                lower == "bn" || lower == "ben" -> "Bengali"
            lower.contains("arabic") || lower == "ar" || lower == "ara" || lower == "arb" -> "Arabic"
            lower.contains("russian") || lower == "ru" || lower == "rus" -> "Russian"
            lower.contains("chinese") || lower.contains("mandarin") || lower == "zh" || lower == "chi" || lower == "zho" -> "Chinese"
            lower.contains("japanese") || lower == "ja" || lower == "jpn" -> "Japanese"
            lower.contains("korean") || lower == "ko" || lower == "kor" -> "Korean"
            lower.contains("french") || lower == "fr" || lower == "fre" || lower == "fra" -> "French"
            lower.contains("spanish") || lower == "es" || lower == "spa" -> "Spanish"
            lower.contains("portuguese") || lower == "pt" || lower == "por" -> "Portuguese"
            lower.contains("english") || lower == "en" || lower == "eng" -> "English"
            lower.contains("tamil") || lower.contains("\u0ba4\u0bae\u0bb4") || lower == "ta" || lower == "tam" -> "Tamil"
            lower.contains("telugu") || lower.contains("\u0c24\u0c46\u0c32\u0c41\u0c17") || lower == "te" || lower == "tel" -> "Telugu"
            lower.contains("malayalam") || lower.contains("\u0d2e\u0d32\u0d2f\u0d3e\u0d33") || lower == "ml" || lower == "mal" -> "Malayalam"
            lower.contains("kannada") || lower.contains("\u0c95\u0ca8\u0ccd\u0ca8\u0ca1") || lower == "kn" || lower == "kan" -> "Kannada"
            lower.contains("marathi") || lower.contains("\u092e\u0930\u093e\u091f") || lower == "mr" || lower == "mar" -> "Marathi"
            lower.contains("punjabi") || lower.contains("\u0a2a\u0a70\u0a1c\u0a3e\u0a2c") || lower == "pa" || lower == "pan" -> "Punjabi"
            lower.contains("gujarati") || lower.contains("\u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4") || lower == "gu" || lower == "guj" -> "Gujarati"
            lower.contains("nepali") || lower.contains("\u0928\u0947\u092a\u093e\u0932") || lower == "ne" || lower == "nep" -> "Nepali"
            lower.contains("sinhala") || lower.contains("singhalese") || lower.contains("\u0dc3\u0dd2\u0d82\u0dc4") ||
                lower == "si" || lower == "sin" -> "Sinhala"
            lower.contains("thai") || lower == "th" || lower == "tha" -> "Thai"
            lower.contains("turkish") || lower == "tr" || lower == "tur" -> "Turkish"
            lower.contains("german") || lower == "de" || lower == "ger" || lower == "deu" -> "German"
            lower.contains("italian") || lower == "it" || lower == "ita" -> "Italian"
            lower.contains("indonesian") || lower == "id" || lower == "ind" -> "Indonesian"
            lower.contains("malaysian") || lower == "ms" || lower == "may" || lower == "msa" -> "Malaysian"
            lower.contains("vietnamese") || lower == "vi" || lower == "vie" -> "Vietnamese"
            lower.contains("filipino") || lower.contains("tagalog") || lower == "fil" -> "Filipino"
            lower.contains("dutch") || lower == "nl" || lower == "dut" || lower == "nld" -> "Dutch"
            lower.contains("polish") || lower == "pl" || lower == "pol" -> "Polish"
            lower.contains("hindi dubbed") || lower == "dubbed" -> "Hindi"
            else -> s.substringBefore('-').substringBefore(' ').ifBlank { "Subtitle" }
        }
    }

    /** Indian canonical name -> native-script name (Devanagari escapes: script-safe regardless of file encoding). */
    val INDIAN_NATIVE = mapOf(
        "Hindi" to "\u0939\u093f\u0928\u094d\u0926\u0940",
        "Tamil" to "\u0ba4\u0bae\u0bb4\u0bcd",
        "Telugu" to "\u0c24\u0c46\u0c32\u0c41\u0c17\u0c41",
        "Malayalam" to "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02",
        "Kannada" to "\u0c95\u0ca8\u0ccd\u0ca1",
        "Marathi" to "\u092e\u0930\u093e\u091f\u0940",
        "Bengali" to "\u09ac\u09be\u0982\u09b2\u09be",
        "Punjabi" to "\u0a2a\u0a70\u0a1c\u0a3e\u0a2c\u0a40",
        "Gujarati" to "\u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4\u0ac0",
        "Urdu" to "\u0627\u0631\u062f\u0648",
        "Nepali" to "\u0928\u0947\u092a\u093e\u0932\u0940",
        "Sinhala" to "\u0dc3\u0dd2\u0d82\u0dc4\u0dcf",
    )

    /** Menu label for a parsed track: Indian languages show Roman + native-script name; roman/Devanagari Hindi splits into Hinglish vs native. */
    fun subtitleMenuName(canon: String, raw: String?): String {
        val lower = raw?.lowercase().orEmpty()
        if (canon == "Hindi") {
            if (lower.contains("hinglish") || lower.contains("roman") || lower.contains("latin") ||
                lower.contains("latn") || lower.contains("english word")) return "Hindi (Hinglish)"
            if (lower.contains("\u0939\u093f\u0928\u094d\u0926") || lower.contains("\u0939\u093f\u0902\u0926") ||
                lower.contains("\u0926\u0947\u0935\u0928\u093e\u0917\u0930\u0940")) return "Hindi (\u0939\u093f\u0928\u094d\u0926\u0940)"
            return "Hindi (\u0939\u093f\u0928\u094d\u0926\u0940)"
        }
        val native = INDIAN_NATIVE[canon] ?: return canon
        return "$canon ($native)"
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

    /** How many codes one addon request carries; big multi-language requests are slower, so the rest is fetched in parallel chunks. */
    internal const val GROUP_SIZE = 6

    /** Every subtitle language we request - the Indian block first, then global (map order = desired order + chunking). */
    private val CODES = mapOf(
        "Hindi" to "hi", "English" to "en", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Punjabi" to "pa",
        "Gujarati" to "gu", "Nepali" to "ne", "Sinhala" to "si",
        "Arabic" to "ar", "Spanish" to "es", "French" to "fr",
        "German" to "de", "Italian" to "it", "Portuguese" to "pt",
        "Russian" to "ru", "Chinese" to "zh", "Japanese" to "ja",
        "Korean" to "ko", "Turkish" to "tr", "Thai" to "th",
        "Indonesian" to "id", "Malaysian" to "ms", "Vietnamese" to "vi",
        "Filipino" to "fil", "Dutch" to "nl", "Polish" to "pl",
    )

    /** Canonical language names mapped to supported SubSense ISO-3 codes. */
    private val SENSE_ISO3 = mapOf(
        "English" to "eng", "Hindi" to "hin", "Tamil" to "tam", "Telugu" to "tel",
        "Malayalam" to "mal", "Bengali" to "ben", "Urdu" to "urd",
        "Marathi" to "mar", "Kannada" to "kan", "Punjabi" to "pan",
        "Gujarati" to "guj", "Nepali" to "nep", "Sinhala" to "sin",
        "Arabic" to "ara", "Spanish" to "spa", "French" to "fra",
        "German" to "deu", "Italian" to "ita", "Portuguese" to "por",
        "Russian" to "rus", "Chinese" to "zho", "Japanese" to "jpn",
        "Korean" to "kor", "Turkish" to "tur", "Thai" to "tha",
        "Indonesian" to "ind", "Malaysian" to "msa", "Vietnamese" to "vie",
        "Filipino" to "fil", "Dutch" to "nld", "Polish" to "pol",
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    /** Every language worth requesting when the video starts - Indian block, then the rest global. */
    fun desiredLanguages(originalLang: String? = null): Set<String> {
        val wanted = CODES.keys.toCollection(LinkedHashSet())
        originalLang?.let { SubtitleServices.canonicalName(it) }?.let { wanted.add(it) }
        return wanted
    }

    /** Returns requested languages not covered by stream-owned subtitles. */
    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    /** Maps canonical names to ordered addon codes, dropping unsupported names. */
    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())

    /** ISO-1 codes for Indian languages — always batch-pulled first so they land at the top of the menu. */
    private val INDIAN_LANG_CODES: Set<String> = linkedSetOf(
        "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "pa", "gu", "ne", "si",
    )

    /** Priority batch: English → original → all Indian langs → rest foreign. */
    internal val PRIORITY_CODES: Set<String> = linkedSetOf("en") + INDIAN_LANG_CODES

    /** Request codes into addon groups: English first, then original, then Indian block (chunked), then the rest in GROUP_SIZE chunks. */
    internal fun groupRequests(codes: Set<String>, originalCode: String? = null): List<Set<String>> {
        if (codes.isEmpty()) return emptyList()
        val groups = mutableListOf<Set<String>>()
        // 1. English (always first).
        codes.filterTo(LinkedHashSet()) { it == "en" }
            .takeIf { it.isNotEmpty() }?.let { groups.add(it) }
        // 2. Original language (if not already English).
        if (originalCode != null && originalCode != "en" && codes.contains(originalCode)) {
            groups.add(linkedSetOf(originalCode))
        }
        // 3. All Indian languages still uncovered — chunked to keep each request fast.
        codes.filterTo(LinkedHashSet()) { it in INDIAN_LANG_CODES }
            .chunked(GROUP_SIZE)
            .forEach { groups.add(it.toCollection(LinkedHashSet())) }
        // 4. Everything else in GROUP_SIZE chunks.
        codes.filterTo(LinkedHashSet()) { it !in groups.flatten().toSet() }
            .chunked(GROUP_SIZE)
            .forEach { groups.add(it.toCollection(LinkedHashSet())) }
        return groups
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
    internal data class SubTrack(val lang: String, val url: String, val menu: String = lang)

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
            val raw = s.optString("lang_code").ifBlank { s.optString("lang") }
            val code = raw.lowercase()
            // Prefer ISO-1 codes, falling back to ISO-3 language values.
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            val canon = SubtitleServices.canonicalName(raw)
            out.add(SubTrack(canon, url, SubtitleServices.subtitleMenuName(canon, raw)))
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
            var raw = s.optString("lang")
            if (raw.isNotBlank()) {
                canon = SubtitleServices.canonicalName(raw)
            } else {
                for (tok in s.optString("id").split('-')) {
                    val c = SubtitleServices.canonicalName(tok)
                    if (c != "Subtitle" && c != tok.lowercase()) { canon = c; raw = tok; break }
                }
            }
            if (canon.isEmpty()) canon = "Subtitle"
            if (canon == "Subtitle") continue
            if ((perLang[canon] ?: 0) >= SENSE_MAX_PER_LANG) continue
            perLang[canon] = (perLang[canon] ?: 0) + 1
            out.add(SubTrack(canon, url, SubtitleServices.subtitleMenuName(canon, raw)))
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
        originalLang: String? = null,
    ): List<SubtitleFile> {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        if (missing.isEmpty()) return emptyList()
        val codes = codesFromLangs(missing)
        if (codes.isEmpty()) return emptyList()
        val originalCode = originalLang?.let { SubtitleServices.canonicalName(it) }?.let { CODES[it] }

        val key = "$imdb|$season|$episode|${codes.sorted().joinToString(",")}"
        cache[key]?.let { (exp, subs) ->
            if (System.currentTimeMillis() < exp) return subs
            cache.remove(key)
        }

        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val groups = groupRequests(codes, originalCode)
        // First group = priority batch (English + original + all Indian langs).
        val priority = groups.firstOrNull() ?: emptySet()
        val tracks = coroutineScope {
            val jobs = groups.map { launchFetch(imdb, season, episode, it, deadline) }
            val results = jobs.map { it.await() }
            var merged = mergeGroups(results)
            val priority = codes.filterTo(LinkedHashSet()) { it in PRIORITY_CODES }
            if (results.all { it.isEmpty() } && priority.isNotEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                // Retry the priority group once if every initial group failed.
                Log.d("SubtilesProvider", "all groups empty - one priority retry")
                val retry = launchFetch(imdb, season, episode, priority, deadline).await()
                merged = mergeGroups(results + listOf(retry))
            }
            val gaps = stillMissing(missing, merged)
            if (gaps.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val sense = fetchSubSense(imdb, season, episode, gaps, deadline)
                merged = mergeGroups(listOf(merged, sense))
            }
            merged
        }
        val subs = tracks.map { SubtitleFile(it.menu, it.url) }
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