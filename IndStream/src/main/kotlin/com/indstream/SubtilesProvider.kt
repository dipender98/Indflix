package com.indstream

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object SubtilesProvider {

    private const val BASE = "https://opensubtitles.stremio.homes"
    private const val SENSE_BASE = "https://subsense.nepiraw.com"
    private const val CONFIG = "ai-translated=true|from=all|auto-adjustment=true"
    const val FETCH_BUDGET_MS = 13_000L
    private const val SENSE_CALL_MS = 5_000L
    internal const val MAX_PER_LANG = 3
    internal const val SENSE_MAX_PER_LANG = 5
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    private const val EMPTY_TTL_MS = 90_000L

    internal const val GROUP_SIZE = 6

    private val CODES = mapOf(
        "Hindi" to "hi", "English" to "en", "Tamil" to "ta", "Telugu" to "te",
        "Malayalam" to "ml", "Bengali" to "bn", "Urdu" to "ur",
        "Marathi" to "mr", "Kannada" to "kn", "Punjabi" to "pa",
        "Gujarati" to "gu", "Nepali" to "ne", "Sinhala" to "si",
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

    private val cache = ConcurrentHashMap<String, Pair<Long, List<SubtitleFile>>>()
    private val empty = ConcurrentHashMap<String, Long>()

    fun desiredLanguages(originalLang: String?): Set<String> {
        val wanted = LinkedHashSet(CODES.keys)
        originalLang?.takeIf { it.length == 2 }?.lowercase()?.let { wanted.add(it) }
        return wanted
    }

    fun missingLanguages(covered: Set<String>, desired: Set<String>): Set<String> =
        desired - covered

    internal fun codesFromLangs(langs: Set<String>): Set<String> =
        langs.mapNotNull { lang ->
            if (lang.length == 2) lang.lowercase() else codeForLang(lang)
        }.toCollection(LinkedHashSet())

    internal fun codeForLang(name: String): String? = CODES[name]

    private val INDIAN_LANG_CODES: Set<String> = linkedSetOf(
        "hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "pa", "gu", "ne", "si",
    )

    internal val PRIORITY_CODES: Set<String> = linkedSetOf("en") + INDIAN_LANG_CODES

    internal fun groupRequests(codes: Set<String>, originalCode: String? = null): List<Set<String>> {
        if (codes.isEmpty()) return emptyList()
        val groups = mutableListOf<Set<String>>()
        codes.filterTo(LinkedHashSet()) { it == "hi" }
            .takeIf { it.isNotEmpty() }?.let { groups.add(it) }
        codes.filterTo(LinkedHashSet()) { it == "en" }
            .takeIf { it.isNotEmpty() }?.let { groups.add(it) }
        if (originalCode != null && originalCode != "en" && originalCode != "hi" && codes.contains(originalCode)) {
            groups.add(linkedSetOf(originalCode))
        }
        val grouped = groups.flatten().toSet()
        codes.filterTo(LinkedHashSet()) { it in INDIAN_LANG_CODES && it !in grouped }
            .chunked(GROUP_SIZE)
            .forEach { groups.add(it.toCollection(LinkedHashSet())) }
        codes.filterTo(LinkedHashSet()) { it !in groups.flatten().toSet() }
            .chunked(GROUP_SIZE)
            .forEach { groups.add(it.toCollection(LinkedHashSet())) }
        return groups
    }

    internal fun stillMissing(wanted: Set<String>, tracks: List<SubTrack>): Set<String> =
        wanted - tracks.map { it.lang }.toSet()

    internal fun subSenseLanguages(names: Set<String>): Set<String> {
        val mapped = names.mapNotNull { SENSE_ISO3[it] }.toCollection(LinkedHashSet())
        return mapped.ifEmpty { linkedSetOf("eng") }
    }

    internal fun buildUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val codes = codesFromLangs(langs)
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

    internal fun subSenseUrl(imdbId: String, season: Int, episode: Int, langs: Set<String>): String {
        val list = subSenseLanguages(langs).joinToString(",") { "\"$it\"" }
        val cfg = """{"languages":[$list],"maxSubtitles":${SENSE_MAX_PER_LANG + 1}}"""
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        return "$SENSE_BASE/" + URLEncoder.encode(cfg, "UTF-8") + "/subtitles/$idPart.json"
    }

    internal data class SubTrack(val lang: String, val url: String, val menu: String = lang)

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

    internal fun parseSubSense(text: String): List<SubTrack> {
        val arr = org.json.JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
        val perLang = HashMap<String, Int>()
        val seenUrls = HashSet<String>()
        val out = mutableListOf<SubTrack>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val url = s.optString("url").takeIf { it.startsWith("http") && seenUrls.add(it) }
                ?: continue
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

    suspend fun fetch(
        imdbId: String?,
        season: Int,
        episode: Int,
        missing: Set<String>,
        originalLang: String? = null,
    ): List<SubtitleFile> {
        val out = mutableListOf<SubtitleFile>()
        fetchAndDeliver(imdbId, season, episode, missing, originalLang) { out.add(it) }
        return out
    }

    suspend fun fetchAndDeliver(
        imdbId: String?,
        season: Int,
        episode: Int,
        missing: Set<String>,
        originalLang: String? = null,
        onTrack: suspend (SubtitleFile) -> Unit,
    ): Int {
        val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: return 0
        if (missing.isEmpty()) return 0
        val codes = codesFromLangs(missing)
        if (codes.isEmpty()) return 0
        val originalCode = originalLang?.takeIf { it.length == 2 }?.lowercase()

        val key = "$imdb|$season|$episode|${codes.sorted().joinToString(",")}"
        cache[key]?.let { (exp, subs) ->
            if (System.currentTimeMillis() < exp) {
                subs.forEach { onTrack(it) }
                return subs.size
            }
            cache.remove(key)
        }
        empty[key]?.let { if (System.currentTimeMillis() < it) return 0 }

        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        val groups = groupRequests(codes, originalCode)
        if (groups.isEmpty()) return 0
        val seen = HashSet<String>()
        val perLang = HashMap<String, Int>()
        val collected = mutableListOf<SubTrack>()
        val emitLock = Mutex()
        var delivered = 0
        suspend fun emit(tracks: List<SubTrack>) {
            val fresh = emitLock.withLock {
                tracks.filter { t ->
                    if (!seen.add(t.url)) false
                    else if ((perLang[t.lang] ?: 0) >= SENSE_MAX_PER_LANG) false
                    else {
                        perLang[t.lang] = (perLang[t.lang] ?: 0) + 1
                        collected.add(t)
                        delivered++
                        true
                    }
                }
            }
            fresh.forEach { onTrack(SubtitleFile(it.menu, it.url)) }
        }
        coroutineScope {
            val pending = groups.map { launchFetch(imdb, season, episode, it, deadline) } +
                async { fetchSubSense(imdb, season, episode, missing, deadline) }
            pending.forEach { emit(it.await()) }
            if (delivered == 0 && System.currentTimeMillis() < deadline) {
                Log.d("SubtilesProvider", "all groups empty — one priority retry")
                groups.firstOrNull()?.let { emit(launchFetch(imdb, season, episode, it, deadline).await()) }
            }
        }
        if (collected.isNotEmpty()) put(key, collected.map { SubtitleFile(it.menu, it.url) })
        else {
            if (empty.size >= CACHE_MAX) {
                empty.entries.minByOrNull { it.value }?.let { empty.remove(it.key) }
            }
            empty[key] = System.currentTimeMillis() + EMPTY_TTL_MS
        }
        Log.d("SubtilesProvider", "$delivered fallback subs for $imdb (codes=$codes)")
        return delivered
    }

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

    private fun buildUrlOf(imdbId: String, season: Int, episode: Int, codes: Set<String>): String {
        val langPath = if (codes.isEmpty()) "en|hi" else codes.joinToString("|")
        val idPart = if (season > 0 && episode > 0) "series/$imdbId:$season:$episode"
        else "movie/$imdbId"
        val pipe: (String) -> String = { it.replace("|", "%7C") }
        return "$BASE/" + pipe(langPath) + "/" + pipe(CONFIG) + "/subtitles/$idPart.json"
    }

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
            if (cache.size >= CACHE_MAX) return
        }
        cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to subs
    }
}
