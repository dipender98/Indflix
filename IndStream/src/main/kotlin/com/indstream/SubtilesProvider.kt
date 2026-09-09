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

/**
 * FILE: SubtilesProvider.kt — THE subtitle provider (user spec Sept 2026
 * rewrite #3: two keyless community sources, no server captions, every play
 * gets its subtitles from here when the stream starts).
 *
 * PRIMARY — the OpenSubtitles v3+ Stremio community addon
 * (`opensubtitles.stremio.homes`), the same service CSX / Cineverse plugins
 * use. Verified live Sept 2026:
 *   GET /{langs}/ai-translated=true|from=all|auto-adjustment=true
 *       /subtitles/movie/{imdb}.json
 *       /subtitles/series/{imdb}:{season}:{episode}.json
 *   → {"subtitles":[{lang_code ("en"), lang ("eng"), title,
 *      url (…/sub.vtt/?lang_code=en&sub_id=…), …}]}
 * URL-encoded "|" is also accepted; the "|" codes are the addon's language
 * list (max 10 — a longer or unsupported list 400s; verified pa/gu rejected,
 * hi/en/ta/te/ml/bn/ur/mr/kn/si accepted).
 *
 * SECOND — SubSense (`subsense.nepiraw.com`, github.com/NepiRaw/Stremio-
 * SubSense), the other keyless source major community addons ship. Top-up
 * only: it returns mostly English/broad-language tracks (measured 2026-09-08:
 * hi=0 on probes, eng plentiful), serves SRT from dl.opensubtitles.org, and
 * answers in <1s warm. URL shape verified live — the config segment is the
 * URL-ENCODED JSON itself:
 *   /{URLEncode({"languages":["eng","hin"],"maxSubtitles":6})}
 *     /subtitles/movie/{imdb}.json | /subtitles/series/{imdb}:{s}:{e}.json
 * (CSX builds this WRONG — an `n0tcjfba-` addon-id prefix glued to the
 * urlencoded JSON 403s; there is no prefix. Un-encoded braces 301-strip the
 * segment, so the encoding is mandatory.)
 *
 * Budget rules (user: subtitle work must never delay playback long):
 *  - Streams/links are already delivered before this runs; it only adds
 *    subtitle entries, so worst case the user has one subtitle source
 *    instead of many.
 *  - Hard [FETCH_BUDGET_MS] window measured live 2026-09-08: the addon's
 *    cold call (no upstream cache warm) took 11.5s while the old 6.5s budget
 *    silently dropped everything — the verified root cause of "subtitles
 *    don't work". CSX runs THIS SAME service with NO timeout at all; 13s is
 *    still fully inside the LIVE_FILL loadLinks window, and loadLinks
 *    awaits subtitle pushes before returning, so a slow-but-real answer now
 *    reaches the player instead of being dropped.
 *  - Many language codes in one request measurably slow the addon's fan-out,
 *    so wanted codes split into two SMALL concurrent groups
 *    ([splitGroups]: {hi,en} priority + the rest) instead of one 10-lang
 *    request; groups merge back priority-first.
 *  - If BOTH groups fail inside the window the priority group gets exactly
 *    ONE retry while [FETCH_BUDGET_MS] still has time left; SubSense is the
 *    last-resort top-up for still-missing languages ([SENSE_CALL_MS],
 *    best-effort, failures ignored).
 *  - Per-language caps ([MAX_PER_LANG] / [SENSE_MAX_PER_LANG]) keep the
 *    subtitle menu readable.
 */
object SubtilesProvider {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val SENSE_BASE = "https://subsense.nepiraw.com"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    /** Wall-clock budget for the whole fetch. Measured live 2026-09-08: the
     *  addon answers 2–3s warm but 11.5s cold; the old 6.5s cap dropped
     *  those answers silently (the "subtitles don't work" bug). 13s stays
     *  inside the 90s LIVE_FILL window and under the 15.5s worst-case
     *  subsJob wait (2.5s IMDB resolve + this budget), so pushes still
     *  complete before loadLinks returns. */
    const val FETCH_BUDGET_MS = 13_000L
    /** SubSense second-source call: <1s warm (measured 2026-09-08), so 5s is
     *  the generous tail; fired only while the main window still has time. */
    private const val SENSE_CALL_MS = 5_000L
    /** How many tracks per language survive into the menu (addon returns
     *  dozens of near-duplicate releases otherwise). */
    internal const val MAX_PER_LANG = 3
    /** SubSense cap — user spec Sept 2026: this is a top-up source, five
     *  tracks per language keeps the menu readable either way. */
    internal const val SENSE_MAX_PER_LANG = 5
    /** Re-watching the same episode within the TTL reuses the parsed list. */
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64

    /** Indian-market + global languages, verified accepted by the addon. */
    private val LANGS = listOf("en", "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "si")

    /** Canonical subtitle language -> addon code (subset of [LANGS]). */
    private val CODES = mapOf(
        "English" to "en", "Hindi" to "hi", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Sinhala" to "si",
    )

    /** Canonical language -> SubSense ISO-3 codes (probe 2026-09-08: the
     *  response `lang`/id carry ISO-3; the same request codes are accepted).
     *  Only verified-supported entries — unknown codes are silently omitted
     *  from the request by [subSenseLanguages]. */
    private val SENSE_ISO3 = mapOf(
        "English" to "eng", "Hindi" to "hin", "Tamil" to "tam", "Telugu" to "tel",
        "Malayalam" to "mal", "Bengali" to "ben", "Urdu" to "urd",
        "Marathi" to "mar", "Kannada" to "kan", "Sinhala" to "sin",
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()

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

    /** Canonical names -> the addon's ISO-1 codes, requested order, unmapped
     *  names dropped. Pure; the empty-input fallback lives in [buildUrl]. */
    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { CODES[it] }.toCollection(LinkedHashSet())

    /** Names whose tracks decide whether a play "has subtitles" at all —
     *  measured 2026-09-08: these two also fan out fastest on the addon
     *  (short list = quick answer), so they form the priority group. */
    internal val PRIORITY_CODES: Set<String> = linkedSetOf("hi", "en")

    /** Split requested [codes] into the priority ({hi,en}) group and the
     *  rest. The addon is measurably slower serving one many-language
     *  request than two small concurrent ones (Sept 2026 probe note), so
     *  fetch runs the two groups in parallel. Empty group = null so callers
     *  can skip the async entirely. */
    internal fun splitGroups(codes: Set<String>): Pair<Set<String>?, Set<String>?> {
        if (codes.isEmpty()) return null to null
        val priority = codes.filterTo(LinkedHashSet()) { it in PRIORITY_CODES }
        val rest = codes.filterTo(LinkedHashSet()) { it !in PRIORITY_CODES }
        return (priority.takeIf { it.isNotEmpty() }) to (rest.takeIf { it.isNotEmpty() })
    }

    /** Names still uncovered after a batch of parsed tracks came back —
     *  drives the SubSense top-up request. Pure. */
    internal fun stillMissing(wanted: Set<String>, tracks: List<SubTrack>): Set<String> =
        wanted - tracks.map { it.lang }.toSet()

    /** SubSense wants ISO-3 for the languages we know it supports; unknown
     *  names are dropped. Empty -> request "eng" (its guaranteed language —
     *  an empty list would make the call pointless). Pure. */
    internal fun subSenseLanguages(names: Set<String>): Set<String> {
        val mapped = names.mapNotNull { SENSE_ISO3[it] }.toCollection(LinkedHashSet())
        return mapped.ifEmpty { linkedSetOf("eng") }
    }

    /** Build the addon URL for one imdb id. Movies + series paths verified
     *  live (200 with subtitles). season/episode <= 0 -> movie. [langs] are
     *  canonical language names ("Hindi", "English", …) — unmapped names are
     *  dropped; a fully-empty set falls back to "en|hi". */
    internal fun buildUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val codes = codesFromLangs(langs)
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Build the SubSense URL for one imdb id. The config segment is the
     *  US-ASCII URL-encoded JSON — probe 2026-09-08 verified this exact
     *  shape answers 200 in <1s and that raw (un-encoded) braces get the
     *  segment 301-stripped, so the encoding is mandatory. [langs] are
     *  canonical names mapped through [subSenseLanguages];
     *  season/episode <= 0 -> movie path. Pure. */
    internal fun subSenseUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val list = subSenseLanguages(langs).joinToString(",") { "\"$it\"" }
        val cfg = """{"languages":[$list],"maxSubtitles":${SENSE_MAX_PER_LANG + 1}}"""
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        return "$SENSE_BASE/" + URLEncoder.encode(cfg, "UTF-8") + "/subtitles/$idPart.json"
    }

    /** One parsed subtitle track (pure test boundary): canonical display
     *  language name + download url. Mapped to [SubtitleFile] only in
     *  [mergeTracks] so group parsing, dedupe and cap stay network-free. */
    internal data class SubTrack(val lang: String, val url: String)

    /** Parse the OpenSubtitles-addon response keeping [MAX_PER_LANG] per
     *  requested code. Real shape (probe 2026-09-08):
     *  {lang_code:"en", lang:"eng", title, url:"…/sub.vtt?…"}. */
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
            // lang_code is ISO1 ("hi"), lang may be ISO2/3 ("hin"/"per") —
            // accept only the languages we actually asked for.
            val code1 = code.take(2)
            if (code1 !in wantCodes && code !in wantCodes) continue
            if ((perLang[code1] ?: 0) >= MAX_PER_LANG) continue
            perLang[code1] = (perLang[code1] ?: 0) + 1
            out.add(SubTrack(LinkNaming.canonicalSubtitleName(s.optString("lang_code")
                .ifBlank { s.optString("lang") }), url))
        }
        return out
    }

    /** Parse the SubSense response keeping [SENSE_MAX_PER_LANG] per language.
     *  REAL verified shape (probe 2026-09-08, Inception + GOT s01e01):
     *  {"subtitles":[{"id":"subsense-srt-opensubtitles-eng-0",
     *    "url":"https://dl.opensubtitles.org/…/1952595684.srt",
     *    "lang":"eng", "label":"OpenSubtitles — [SRT] — …",
     *    "source":"opensubtitles", "fileName":"timpe-inception.srt",
     *    "releaseName":"DVDRip.XviD-MAXSPEED […]"}]}
     *  `lang` is ISO-3 (maps through canonicalSubtitleName); `id` carries the
     *  language as a fallback token. SubSense already caps server-side via
     *  maxSubtitles; the seen-urls dedupe is still ours. */
    internal fun parseSubSense(text: String): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
            // `lang` is authoritative; the id "subsense-srt-opensubtitles-<iso3>-<n>"
            // is only a fallback (its LAST token is a counter, so scan segments).
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

    /** Merge parsed groups priority-first, deduped by url, capped
     *  [SENSE_MAX_PER_LANG] per language overall. Pure. */
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

    /**
     * Fetch fallback subtitles for [imdbId] covering [missing] (canonical
     * language names). Empty list on any failure/timeout — this must never
     * break playback. Results cached per title+episode.
     *
     * Live schedule inside one [FETCH_BUDGET_MS] window (Sept 2026):
     *  1. the {hi,en} priority group and the rest group run CONCURRENTLY,
     *     each with the full remaining budget (slow group sets the wall);
     *  2. if BOTH come back empty while time remains, the priority group
     *     gets exactly ONE retry;
     *  3. languages still uncovered after all that get ONE best-effort
     *     SubSense call capped at [SENSE_CALL_MS] — any failure ignored.
     */
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
                // cold-start tail (measured 11.5s): both groups died inside
                // the window — one more priority attempt with what is left.
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

    /** Async fetch+parse of one addon group with its own timeout built from
     *  [deadline] (the full remainder of the window when launched at t0). */
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

    /** [buildUrl] but taking pre-mapped ISO-1 codes (groups are already
     *  codes, not canonical names, once [splitGroups] has run). */
    private fun buildUrlOf(imdbId: String, season: Int, episode: Int, codes: Set<String>): String {
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    /** Best-effort SubSense top-up for [gaps] (canonical names). Never
     *  throws, never blocks past [deadline]-or-[SENSE_CALL_MS], and its
     *  failure is simply an empty list — the primary source already spoke. */
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
            if (cache.size >= CACHE_MAX) return // still full of fresh entries — skip caching
        }
        cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
    }
}
