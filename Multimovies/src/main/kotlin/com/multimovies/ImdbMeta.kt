package com.multimovies
/** Keyless IMDB metadata: suggest lookup plus Cinemeta detail, cast and episodes. */

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One suggest hit: IMDB id, title, year and "movie" | "series". */
internal data class ImdbSuggestHit(
    val imdbId: String,
    val title: String,
    val year: String?,
    val type: String,
)

/** Full title detail sourced from Cinemeta (names only for cast). */
internal data class ImdbDetail(
    val imdbId: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val cast: List<ActorData>? = null,
)

/** One episode row from a Cinemeta series payload. */
internal data class ImdbEpisode(
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String?,
    val released: String?,
    val thumbnail: String?,
)

internal object ImdbMeta {

    private const val SUGGEST_API = "https://v3.sg.media-imdb.com/suggestion/x"
    private const val CINEMETA_API = "https://v3-cinemeta.strem.io/meta"
    private const val CACHE_MAX = 64

    private val metaCache = ConcurrentHashMap<String, ImdbDetail>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, ImdbEpisode>>()
    /** Actor name (lowercased) to headshot URL. */
    private val photoCache = ConcurrentHashMap<String, String>()

    /** Suggest lookup by title. Never throws. */
    suspend fun suggest(query: String): List<ImdbSuggestHit> {
        if (query.isBlank()) return emptyList()
        return parseSuggest(suggestRaw(query))
    }

    /** Raw suggest payload for a query. Never throws. */
    private suspend fun suggestRaw(query: String): String? {
        val slug = query.trim().lowercase().replace(Regex("""\s+"""), "_")
        return withTimeoutOrNull(6000L) {
            runCatching {
                app.get("$SUGGEST_API/${URLEncoder.encode(slug, "UTF-8")}.json", timeout = 6).text
            }.getOrNull()
        }
    }

    /** Full detail by IMDB id, cast carries headshots. Cached. Never throws. */
    suspend fun fetchMeta(imdbId: String, type: String): ImdbDetail? {
        if (!imdbId.startsWith("tt")) return null
        val kind = if (type == "movie") "movie" else "series"
        metaCache["$kind|$imdbId"]?.let { return it }
        val text = withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/$kind/$imdbId.json", timeout = 7).text }.getOrNull()
        } ?: return null
        val detail = parseImdbMeta(text, imdbId)?.withCastPhotos() ?: return null
        if (metaCache.size < CACHE_MAX) metaCache["$kind|$imdbId"] = detail
        return detail
    }

    /** Fill cast headshots via person suggest (bounded, cached). Never throws. */
    private suspend fun ImdbDetail.withCastPhotos(): ImdbDetail {
        val names = cast.orEmpty().map { it.actor.name }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return this
        val photos = fetchCastPhotos(names)
        if (photos.isEmpty()) return this
        return copy(cast = cast?.map { a ->
            val url = photos[a.actor.name]
            if (url.isNullOrBlank()) a else ActorData(Actor(a.actor.name, url), roleString = a.roleString)
        })
    }

    /** Headshot per actor name via person suggest (bounded, cached). Never throws. */
    suspend fun fetchCastPhotos(names: List<String>, max: Int = 10): Map<String, String> {
        val wanted = names.filter { it.isNotBlank() }.distinct().take(max)
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val missing = ArrayList<String>()
        for (n in wanted) {
            photoCache[n.lowercase()]?.let { out[n] = it } ?: missing.add(n)
        }
        if (missing.isEmpty()) return out
        val found = withTimeoutOrNull(5000L) {
            coroutineScope {
                val sem = Semaphore(4)
                missing.map { n ->
                    async {
                        sem.acquire()
                        try {
                            parsePersonImage(suggestRaw(n), n)?.let { n to it }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }.orEmpty()
        for ((n, url) in found) {
            if (photoCache.size < CACHE_MAX * 4) photoCache[n.lowercase()] = url
            out[n] = url
        }
        return out
    }

    /** Episode map keyed by (season, episode), cached. Never throws. */
    suspend fun fetchEpisodes(imdbId: String): Map<Pair<Int, Int>, ImdbEpisode> {
        if (!imdbId.startsWith("tt")) return emptyMap()
        episodeCache[imdbId]?.let { return it }
        val text = withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/series/$imdbId.json", timeout = 7).text }.getOrNull()
        } ?: return emptyMap()
        val map = parseImdbEpisodes(text).associate { (it.season to it.episode) to it }
        if (episodeCache.size < CACHE_MAX) episodeCache[imdbId] = map
        return map
    }
}

/** Map a suggest payload to hits. Game entries are dropped. Pure. */
internal fun parseSuggest(raw: String?): List<ImdbSuggestHit> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray("d") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            val title = o.optString("l").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val qid = o.optString("qid")
            if (qid == "videoGame") return@mapNotNull null
            ImdbSuggestHit(
                imdbId = id,
                title = title,
                year = o.optInt("y", -1).takeIf { it > 0 }?.toString(),
                type = if (qid == "tvSeries" || qid == "tvMiniSeries") "series" else "movie",
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Map a Cinemeta envelope to detail. Pure. */
internal fun parseImdbMeta(raw: String?, imdbId: String): ImdbDetail? {
    if (raw.isNullOrBlank()) return null
    return try {
        val m = JSONObject(raw).optJSONObject("meta") ?: return null
        val cast = m.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                val c = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ActorData(Actor(c, ""), roleString = null)
            }
        }
        ImdbDetail(
            imdbId = imdbId,
            name = m.optString("name").takeIf { it.isNotBlank() },
            poster = m.optString("poster").takeIf { it.isNotBlank() },
            backdrop = m.optString("background").takeIf { it.isNotBlank() },
            year = m.optString("releaseInfo")?.take(4)?.takeIf { it[0].isDigit() },
            rating = m.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 },
            overview = m.optString("description").takeIf { it.isNotBlank() },
            genres = m.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            },
            cast = cast,
        )
    } catch (e: Exception) {
        null
    }
}
/** Map a Cinemeta series payload to episode rows. Pure. */
internal fun parseImdbEpisodes(raw: String?): List<ImdbEpisode> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val videos = JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
        (0 until videos.length()).mapNotNull { i ->
            val v = videos.optJSONObject(i) ?: return@mapNotNull null
            val season = v.optInt("season", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val ep = v.optInt("episode", -1).takeIf { it > 0 } ?: return@mapNotNull null
            ImdbEpisode(
                season = season,
                episode = ep,
                name = v.optString("title").takeIf { it.isNotBlank() },
                overview = v.optString("overview").takeIf { it.isNotBlank() },
                released = v.optString("released").takeIf { it.isNotBlank() },
                thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Headshot for a person query: exact name hit wins, else the top person hit. Pure. */
internal fun parsePersonImage(raw: String?, want: String): String? {
    if (raw.isNullOrBlank() || want.isBlank()) return null
    return try {
        val arr = JSONObject(raw).optJSONArray("d") ?: return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optString("id").startsWith("nm")) continue
            val img = o.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() } ?: continue
            if (fallback == null) fallback = img
            if (o.optString("l").equals(want.trim(), ignoreCase = true)) return img
        }
        fallback
    } catch (e: Exception) {
        null
    }
}
