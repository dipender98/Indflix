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

    /** Indian-market + global languages. */
    private val LANGS = listOf("en", "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "si")

    /** Canonical subtitle language -> addon code (subset of). */
    private val CODES = mapOf(
        "English" to "en", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Sinhala" to "si",
    )

    /** Canonical language -> SubSense ISO-3 codes (probe: the response `lang`/id carry ISO-3; the same request codes are. accepted). Only. */
    private val SENSE_ISO3 = mapOf(
        "English" to "eng", "Hindi" to "hin", "Tamil" to "tam", "Telugu" to "tel",
        "Malayalam" to "mal", "Bengali" to "ben", "Urdu" to "urd",
        "Marathi" to "mar", "Kannada" to "kan", "Sinhala" to "sin",
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

    /** The languages worth requesting. Everything else the user can live without. */
    fun desiredLanguages(originalLang: String?): Set<String> {
        val wanted = LinkedHashSet(setOf("Hindi", "English"))
        originalLang?.let { LinkNaming.languageTag(it) }?.let { wanted.add(it) }
        return wanted.filter { CODES.containsKey(it) }.toSet()
    }

    /** Which of none of the stream servers carried (= canonical names emitted by the server-owned subtitle pass). */
    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    /** Canonical names -> the addon's ISO-1 codes, requested order, unmapped names dropped. Pure; the empty-input fallback. lives in. */
    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())

    /** Names whose tracks decide whether a play "has subtitles" at all - measured: these two also fan out fastest on the. addon (short list = quick. */
    internal val PRIORITY_CODES: Set<String> = linkedSetOf("hi", "en")

    /** Split requested into the priority ({hi, en}) group and the rest. The addon is measurably slower serving one. many-language request than two small. */
    internal fun splitGroups(codes: Set<String>): Pair<Set<String>?, Set<String>?> {
        if (codes.isEmpty()) return null to null
        val priority = codes.filterTo(LinkedHashSet()) { it in PRIORITY_CODES }
        val rest = codes.filterTo(LinkedHashSet()) { it !in PRIORITY_CODES }
        return (priority.takeIf { it.isNotEmpty() }) to (rest.takeIf { it.isNotEmpty() })
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
    internal data class SubTrack(val lang: String, val url: String)

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
            out.add(SubTrack(LinkNaming.canonicalSubtitleName(s.optString("lang_code")
                .ifBlank { s.optString("lang") }), url))
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
            if (s.optString("lang").isNotBlank()) {
                canon = LinkNaming.canonicalSubtitleName(s.optString("lang"))
            } else {
                for (tok in s.optString("id").split('-')) {
                    val c = LinkNaming.canonicalSubtitleName(tok)
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
                // cold-start tail (measured 11. 5s): both groups died inside the window - one more priority attempt with what is left.
                Log.d("SubtilesProvider", "both groups empty — one priority retry")
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
