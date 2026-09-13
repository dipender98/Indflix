package com.vegamovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Fallback subtitles (OpenSubtitles addon + SubSense top-up). Progressive: hi/en emit first. */
internal object VegaSubtitles {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val SENSE_BASE = "https://subsense.nepiraw.com"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    const val FETCH_BUDGET_MS = 13_000L
    private const val SENSE_CALL_MS = 5_000L
    internal const val MAX_PER_LANG = 3
    internal const val SENSE_MAX_PER_LANG = 5
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    internal const val GROUP_SIZE = 6

    // Canonical name -> addon ISO-1 code (map order = menu + chunking order).
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

    // Native-script names as escapes (script-safe regardless of file encoding).
    private val NATIVE = mapOf(
        "Hindi" to "हिन्दी",
        "Tamil" to "தமிழ்",
        "Telugu" to "తెలుగు",
        "Malayalam" to "മലയാളം",
        "Kannada" to "ಕನ್ನಡ",
        "Marathi" to "मराठी",
        "Bengali" to "বাংলা",
        "Punjabi" to "ਪੰਜਾਬੀ",
        "Gujarati" to "ગુજરાતી",
        "Urdu" to "اردو",
        "Nepali" to "नेपाली",
        "Sinhala" to "සිංහල",
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    fun desiredLanguages(): Set<String> = LinkedHashSet(CODES.keys)

    // ISO-1/ISO-3 code -> canonical name.
    private val codeToCanon: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        CODES.forEach { (canon, c) -> m[c] = canon }
        SENSE_ISO3.forEach { (canon, c) -> m[c] = canon; m[c.take(2)] = canon }
        m["fil"] = "Filipino"
        m
    }

    private fun menuName(canon: String, raw: String?): String {
        val lower = raw?.lowercase().orEmpty()
        if (canon == "Hindi") {
            if (lower.contains("hinglish") || lower.contains("roman") || lower.contains("latin") ||
                lower.contains("latn")) return "Hindi (Hinglish)"
            return "Hindi (हिन्दी)"
        }
        return NATIVE[canon]?.let { "$canon ($it)" } ?: canon
    }

    internal data class SubTrack(val lang: String, val url: String, val menu: String = lang)

    private val INDIAN_CODES: Set<String> = linkedSetOf(
        "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "pa", "gu", "ne", "si",
    )

    // hi, en first (fast singles + most-wanted), then the Indian block, then foreign - all chunked.
    private fun groups(codes: Set<String>): List<Set<String>> {
        val out = mutableListOf<Set<String>>()
        codes.filterTo(LinkedHashSet()) { it == "hi" }.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        codes.filterTo(LinkedHashSet()) { it == "en" }.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        val done = out.flatten().toSet()
        codes.filterTo(LinkedHashSet()) { it in INDIAN_CODES && it !in done }
            .chunked(GROUP_SIZE).forEach { out.add(it.toCollection(LinkedHashSet())) }
        codes.filterTo(LinkedHashSet()) { it !in out.flatten().toSet() }
            .chunked(GROUP_SIZE).forEach { out.add(it.toCollection(LinkedHashSet())) }
        return out
    }

    private fun addonUrl(imdb: String, season: Int?, episode: Int?, codes: Set<String>): String {
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season != null && season > 0 && episode != null && episode > 0)
            "series/$imdb:$season:$episode" else "movie/$imdb"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    private fun senseUrl(imdb: String, season: Int?, episode: Int?, langs: Set<String>): String {
        val mapped = langs.mapNotNull { SENSE_ISO3[it] }.toCollection(LinkedHashSet())
            .ifEmpty { linkedSetOf("eng") }
        val cfg = """{"languages":[${mapped.joinToString(",") { "\"$it\"" }}],"maxSubtitles":${SENSE_MAX_PER_LANG + 1}}"""
        val idPart = if (season != null && season > 0 && episode != null && episode > 0)
            "series/$imdb:$season:$episode" else "movie/$imdb"
        return "$SENSE_BASE/" + URLEncoder.encode(cfg, "UTF-8") + "/subtitles/$idPart.json"
    }

    private fun parseAddon(text: String, want: Set<String>): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seen = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seen.add(it) } ?: continue
            val raw = s.optString("lang_code").ifBlank { s.optString("lang") }
            val code = raw.lowercase()
            val code1 = code.take(2)
            if (code1 !in want && code !in want) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            val canon = codeToCanon[code] ?: codeToCanon[code1] ?: continue
            out.add(SubTrack(canon, url, menuName(canon, raw)))
        }
        return out
    }

    private fun parseSense(text: String): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seen = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seen.add(it) } ?: continue
            val raw = s.optString("lang")
            val canon = codeToCanon[raw.lowercase()] ?: s.optString("id").split('-')
                .firstNotNullOfOrNull { codeToCanon[it.lowercase()] } ?: continue
            if ((perLang[canon] ?: 0) >= SENSE_MAX_PER_LANG) continue
            perLang[canon] = (perLang[canon] ?: 0) + 1
            out.add(SubTrack(canon, url, menuName(canon, raw.ifBlank { null })))
        }
        return out
    }

    private fun CoroutineScope.launchFetch(
        imdb: String, season: Int?, episode: Int?, codes: Set<String>, deadline: Long,
    ): Deferred<List<SubTrack>> = async {
        val budget = deadline - System.currentTimeMillis()
        if (budget <= 0) return@async emptyList()
        val text = withTimeoutOrNull(budget) {
            runCatching {
                app.get(addonUrl(imdb, season, episode, codes), timeout = (budget / 1000L).coerceAtLeast(1L)).text
            }.getOrNull()
        }
        if (text.isNullOrBlank()) return@async emptyList()
        runCatching { parseAddon(text, codes) }.getOrDefault(emptyList())
    }

    /** Progressive fetch: priority emits first so tracks land at playback start. Returns delivered count. */
    suspend fun fetchAndDeliver(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        onTrack: suspend (SubtitleFile) -> Unit,
    ): Int {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return 0
        val wanted = desiredLanguages()
        val codes = wanted.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())
        val key = "$imdb|$season|$episode"
        cache[key]?.let { (exp, subs) ->
            if (System.currentTimeMillis() < exp) {
                subs.forEach { onTrack(it) }
                return subs.size
            }
            cache.remove(key)
        }
        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val all = groups(codes)
        if (all.isEmpty()) return 0
        val seen = HashSet<String>()
        val perLang = HashMap<String, Int>()
        val collected = mutableListOf<SubTrack>()
        var delivered = 0
        suspend fun emit(tracks: List<SubTrack>) {
            for (t in tracks) {
                if (!seen.add(t.url)) continue
                if ((perLang[t.lang] ?: 0) >= SENSE_MAX_PER_LANG) continue
                perLang[t.lang] = (perLang[t.lang] ?: 0) + 1
                collected.add(t)
                onTrack(SubtitleFile(t.menu, t.url))
                delivered++
            }
        }
        coroutineScope {
            // Phase 1 (top priority): Hindi + English singles, land at playback start.
            all.take(2).map { launchFetch(imdb, season, episode, it, deadline) }
                .map { it.await() }.forEach { emit(it) }
            // Phase 2 (priority batch): rest of the Indian block.
            if (System.currentTimeMillis() < deadline) {
                all.drop(2).filter { g -> g.all { it in INDIAN_CODES } }
                    .map { launchFetch(imdb, season, episode, it, deadline) }
                    .map { it.await() }.forEach { emit(it) }
            }
            // Phase 3: foreign languages.
            if (System.currentTimeMillis() < deadline) {
                all.drop(2).filterNot { g -> g.all { it in INDIAN_CODES } }
                    .map { launchFetch(imdb, season, episode, it, deadline) }
                    .map { it.await() }.forEach { emit(it) }
            }
            val gaps = wanted - collected.map { it.lang }.toSet()
            val budget = minOf(SENSE_CALL_MS, deadline - System.currentTimeMillis())
            if (gaps.isNotEmpty() && budget > 0) {
                val text = withTimeoutOrNull(budget) {
                    runCatching {
                        app.get(senseUrl(imdb, season, episode, gaps), timeout = (budget / 1000L).coerceAtLeast(1L)).text
                    }.getOrNull()
                }
                if (!text.isNullOrBlank()) emit(runCatching { parseSense(text) }.getOrDefault(emptyList()))
            }
        }
        if (collected.isNotEmpty()) {
            if (cache.size >= CACHE_MAX) cache.clear()
            cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to collected.map { SubtitleFile(it.menu, it.url) }
        }
        return delivered
    }
}
