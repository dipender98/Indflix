package com.indstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** FILE: SubtilesProvider. kt - THE subtitle provider (, no server captions, every play gets its subtitles). PRIMARY. the OpenSubtitles v3+ Stremio. */
object SubtilesProvider {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val SENSE_BASE = "https://subsense.nepiraw.com"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    /** Wall-clock budget for the whole fetch. Measured live: the addon answers 2-3s warm but 11. 5s cold; the old 6. 5s cap. dropped those answers. */
    const val FETCH_BUDGET_MS = 13_000L
    /** SubSense second-source call: <1s warm (measured), so 5s is the generous tail; fired only while the main window still. has time. */
    private const val SENSE_CALL_MS = 5_000L
    /** How many tracks per language survive into the menu (addon returns dozens of near-duplicate releases otherwise). */
    internal const val MAX_PER_LANG = 3
    /** SubSense cap -, five tracks per language keeps the menu readable either way. */
    internal const val SENSE_MAX_PER_LANG = 5
    /** Re-watching the same episode within the TTL reuses the parsed list. */
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64

    /** How many codes one addon request carries; the addon slows down on big multi-language requests, so the rest is fetched in parallel chunks. */
    internal const val GROUP_SIZE = 6

    /** Every subtitle language we request - the full available menu, Indian block first (map order = desired order + chunking). */
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

    /** Canonical language -> SubSense ISO-3 codes (probe: the response `lang`/id carry ISO-3; the same request codes are. accepted). Only. */
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

    /** Every language worth requesting; the original language rides along when it isn't already in the set. */
    fun desiredLanguages(originalLang: String?): Set<String> {
        val wanted = LinkedHashSet(CODES.keys)
        originalLang?.let { LinkNaming.languageTag(it) }?.let { wanted.add(it) }
        return wanted
    }

    /** Which of none of the stream servers carried (= canonical names emitted by the server-owned subtitle pass). */
    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    /** Canonical names -> the addon's ISO-1 codes, requested order, unmapped names dropped. Pure; the empty-input fallback. lives in. */
    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())

    /** ISO-1 codes for Indian languages — always batch-pulled first so they land at the top of the menu. */
    private val INDIAN_LANG_CODES: Set<String> = linkedSetOf(
        "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "pa", "gu", "ne", "si",
    )

    /** Priority batch: English → original → all Indian langs → rest foreign. */
    internal val PRIORITY_CODES: Set<String> = linkedSetOf("en") + INDIAN_LANG_CODES

    /** Request codes into addon groups: English first, then original, then Indian block (chunked), then the rest in GROUP_SIZE chunks.
     *  The addon is measurably slower on big multi-language requests, so parallel small batches beat one giant one. */
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
        // 4. Everything else in GROUP_SIZE chunks (preserves CODES insertion order = Indian→foreign).
        codes.filterTo(LinkedHashSet()) { it !in groups.flatten().toSet() }
            .chunked(GROUP_SIZE)
            .forEach { groups.add(it.toCollection(LinkedHashSet())) }
        return groups
    }

    /** Names still uncovered after a batch of parsed tracks came back - drives the SubSense top-up request. Pure. */
    internal fun stillMissing(wanted: Set<String>, tracks: List<SubTrack>): Set<String> =
        wanted - tracks.map { it.lang }.toSet()

    /** SubSense wants ISO-3 for the languages we know it supports; unknown names are dropped. Empty -> request "eng" (its. guaranteed language - an empty. */
    internal fun subSenseLanguages(names: Set<String>): Set<String> {
        val mapped = names.mapNotNull { SENSE_ISO3[it] }.toCollection(LinkedHashSet())
        return mapped.ifEmpty { linkedSetOf("eng") }
    }

    /** Build the addon URL for one imdb id. Movies + series paths). season/episode <= 0 -> movie. are canonical language. names ("Hindi", "English", …). */
    internal fun buildUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val codes = codesFromLangs(langs)
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Build the SubSense URL for one imdb id. The config segment is the US-ASCII URL-encoded JSON - probe) braces get the. segment 301-stripped, so the. */
    internal fun subSenseUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val list = subSenseLanguages(langs).joinToString(",") { "\"$it\"" }
        val cfg = """{"languages":[$list],"maxSubtitles":${SENSE_MAX_PER_LANG + 1}}"""
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        return "$SENSE_BASE/" + URLEncoder.encode(cfg, "UTF-8") + "/subtitles/$idPart.json"
    }

    /** One parsed subtitle track (pure test boundary): canonical display language name + download url. Mapped to only in so. group parsing, dedupe and cap. */
    internal data class SubTrack(val lang: String, val url: String, val menu: String = lang)

    /** Parse the OpenSubtitles-addon response keeping per requested code. Real shape (probe): {lang_code: "en", lang. "eng", title, url: "…/sub. vtt?…"}. */
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
            // lang_code is ISO1 ("hi"), lang may be ISO2/3 ("hin"/"per") - accept only the languages we actually asked for.
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            val raw = s.optString("lang_code").ifBlank { s.optString("lang") }
            val canon = LinkNaming.canonicalSubtitleName(raw)
            out.add(SubTrack(canon, url, LinkNaming.subtitleMenuName(canon, raw)))
        }
        return out
    }

    /** Parse the SubSense response keeping per language. REAL, Inception + GOT s01e01): {"subtitles": - …", "source". "opensubtitles", "fileName". */
    internal fun parseSubSense(text: String): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            // `lang` is authoritative; the id "subsense-srt-opensubtitles-<iso3>-<n>" is only a fallback (its LAST token is a.
// counter, so scan segments).
            var canon = ""
            var raw = s.optString("lang")
            if (raw.isNotBlank()) {
                canon = LinkNaming.canonicalSubtitleName(raw)
            } else {
                for (tok in s.optString("id").split('-')) {
                    val c = LinkNaming.canonicalSubtitleName(tok)
                    if (c != "Subtitle" && c != tok.lowercase()) { canon = c; raw = tok; break }
                }
            }
            if (canon.isEmpty()) canon = "Subtitle"
            if (canon == "Subtitle") continue
            if ((perLang[canon] ?: 0) >= SENSE_MAX_PER_LANG) continue
            perLang[canon] = (perLang[canon] ?: 0) + 1
            out.add(SubTrack(canon, url, LinkNaming.subtitleMenuName(canon, raw)))
        }
        return out
    }

    /** Merge parsed groups priority-first, deduped by url, capped per language overall. Pure. */
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

    /** Fetch fallback subtitles for covering (canonical language names). Empty list on any failure/timeout - this must. never break playback. */
    suspend fun fetch(
        imdbId: String?,
        season: Int,
        episode: Int,
        missing: Set<String>,
        originalLang: String? = null,
    ): List<SubtitleFile> {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        if (missing.isEmpty()) return emptyList()
        val codes = codesFromLangs(missing)
        if (codes.isEmpty()) return emptyList()
        val originalCode = originalLang?.let { LinkNaming.languageTag(it) }?.let { CODES[it] }

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
            if (results.all { it.isEmpty() } && priority.isNotEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                // cold-start tail (measured 11. 5s): every group died inside the window - one more priority attempt with what is left.
                Log.d("SubtilesProvider", "all groups empty — one priority retry")
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

    /** Async fetch+parse of one addon group with its own timeout built). */
    private fun CoroutineScope.launchFetch(
        imdb: String, season: Int, episode: Int, codes: Set<String>, deadline: Long,
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
            Log.w("SubtilesProvider", "no response within budget — dropped (streams unaffected)")
            return emptyList()
        }
        return runCatching { parseOpenSubtitles(text, codes) }.getOrDefault(emptyList())
    }

    /** but taking pre-mapped ISO-1 codes (groups are already codes, not canonical names, once has run). */
    private fun buildUrlOf(imdbId: String, season: Int, episode: Int, codes: Set<String>): String {
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Best-effort SubSense top-up for (canonical names). Never throws, never blocks past -or-, and its failure is simply. an empty list - the primary. */
    private suspend fun fetchSubSense(
        imdb: String, season: Int, episode: Int, gaps: Set<String>, deadline: Long,
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
            Log.w("SubtilesProvider", "subsense: no response — top-up skipped")
            return emptyList()
        }
        return runCatching { parseSubSense(text) }.getOrDefault(emptyList())
    }

    private fun put(key: String, subs: List<SubtitleFile>) {
        if (cache.size >= CACHE_MAX) {
            val now = System.currentTimeMillis()
            cache.entries.filter { it.value.first <= now }.forEach { cache.remove(it.key) }
            if (cache.size >= CACHE_MAX) return // still full of fresh entries - skip caching.
        }
        cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
    }
}
