package com.indstream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Keyless suggest + metadata source backing the TMDB race. */
object ImdbService {

    private const val SUGGEST_API = "https://v3.sg.media-imdb.com/suggestion/x"
    private const val CINEMETA_API = "https://v3-cinemeta.strem.io/meta"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    private const val WAIT_MS = 11_000L

    /** One suggest hit. */
    data class ImdbHit(
        val imdbId: String,
        val title: String,
        val year: Int?,
        val type: String, // "movie" | "tv".
        val poster: String?,
        val rank: Int?,
    )

    /** Full detail merged from a metadata race. */
    data class MetaDetail(
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val name: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val year: String? = null,
        val rating: Double? = null,
        val overview: String? = null,
        val genres: List<String>? = null,
        val cast: List<ActorData>? = null,
        /** Runtime in minutes, parsed from Cinemeta's "179 min" form. */
        val runtime: Int? = null,
    )

    private data class Entry(val hits: List<ImdbHit>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<ImdbHit>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metaCache = ConcurrentHashMap<String, MetaDetail>()
    /** Actor name (lowercased) to headshot URL. */
    private val photoCache = ConcurrentHashMap<String, String>()
    /** IMDB id to (rating, expiresAt). */
    private val ratingCache = ConcurrentHashMap<String, Pair<Double, Long>>()

    /** Suggest lookup with in-memory cache. Never throws. */
    suspend fun suggest(query: String): List<ImdbHit> {
        if (query.isBlank()) return emptyList()
        val key = query.trim().lowercase()
        cache[key]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.hits
            cache.remove(key)
        }
        val mine = scope.async {
            val found = suggestRemote(query)
            if (found.isNotEmpty()) {
                if (cache.size >= CACHE_MAX) {
                    cache.entries.minByOrNull { e -> e.value.expiresAt }?.let { e -> cache.remove(e.key) }
                }
                cache[key] = Entry(found, System.currentTimeMillis() + CACHE_TTL_MS)
            }
            found
        }
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing == null) mine.invokeOnCompletion { inFlight.remove(key, mine) } else mine.cancel()
        return withTimeoutOrNull(WAIT_MS) { runCatching { (existing ?: mine).await() }.getOrNull() }.orEmpty()
    }

    private suspend fun suggestRemote(query: String): List<ImdbHit> =
        parseSuggest(suggestRaw(query))

    /** Raw suggest payload for a query. Never throws. */
    private suspend fun suggestRaw(query: String): String? {
        // Suggest keys collapse whitespace to underscores.
        val slug = query.trim().lowercase().replace(Regex("""\s+"""), "_")
        val encoded = URLEncoder.encode(slug, "UTF-8")
        return withTimeoutOrNull(6000L) {
            runCatching { app.get("$SUGGEST_API/$encoded.json", timeout = 6).text }.getOrNull()
        }
    }

    /** Map a suggest payload to hits. Name-kind entries are dropped. Pure. */
    fun parseSuggest(raw: String?): List<ImdbHit> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(raw).optJSONArray("d") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                val title = o.optString("l").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val qid = o.optString("qid")
                if (qid == "videoGame") return@mapNotNull null
                val type = if (qid == "tvSeries" || qid == "tvMiniSeries") "tv" else "movie"
                ImdbHit(
                    imdbId = id,
                    title = title,
                    year = o.optInt("y", -1).takeIf { it > 0 },
                    type = type,
                    poster = o.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() },
                    rank = o.optInt("rank", -1).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Headshot for a person query: exact name hit wins, else the top person hit. Pure. */
    fun parsePersonImage(raw: String?, want: String): String? {
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
        } catch (e: Exception) { null }
    }

    /** Keyless full metadata by IMDB id. Cast carries headshots. Never throws. */
    suspend fun fetchMeta(imdbId: String, type: String): MetaDetail? {
        if (!imdbId.startsWith("tt")) return null
        val kind = if (type == "movie") "movie" else "series"
        val key = "$kind|$imdbId"
        metaCache[key]?.let { return it }
        val text = withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/$kind/$imdbId.json", timeout = 7).text }.getOrNull()
        } ?: return null
        val detail = parseCinemeta(text, imdbId)?.withCastPhotos() ?: return null
        if (metaCache.size < CACHE_MAX) metaCache[key] = detail
        return detail
    }

    /** Fill cast headshots via person suggest (bounded, cached). Never throws. */
    private suspend fun MetaDetail.withCastPhotos(): MetaDetail {
        val names = cast.orEmpty().map { it.actor.name }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return this
        val photos = fetchCastPhotos(names)
        if (photos.isEmpty()) return this
        return copy(cast = cast?.map { a ->
            val url = photos[a.actor.name]
            if (url.isNullOrBlank()) a else ActorData(Actor(a.actor.name, url), roleString = a.roleString)
        })
    }

    /** Map a Cinemeta envelope to detail. Pure. */
    fun parseCinemeta(raw: String?, imdbId: String): MetaDetail? {
        if (raw.isNullOrBlank()) return null
        return try {
            val m = JSONObject(raw).optJSONObject("meta") ?: return null
            val cast = m.optJSONArray("cast")?.let { arr ->
                (0 until minOf(arr.length(), 20)).mapNotNull { i ->
                    val c = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ActorData(Actor(c, ""), roleString = null)
                }
            }
            MetaDetail(
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
                runtime = parseRuntimeMinutes(m.optString("runtime")),
            )
        } catch (e: Exception) { null }
    }

    /** Minutes from Cinemeta's "179 min" form, else null. Pure. */
    fun parseRuntimeMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Episode rows from a Cinemeta series payload. Pure. */
    fun parseCinemetaEpisodes(raw: String?): List<TmdbService.TmdbEpisode> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val videos = JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
            (0 until videos.length()).mapNotNull { i ->
                val v = videos.optJSONObject(i) ?: return@mapNotNull null
                val season = v.optInt("season", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val ep = v.optInt("episode", -1).takeIf { it > 0 } ?: return@mapNotNull null
                TmdbService.TmdbEpisode(
                    seasonNumber = season,
                    episodeNumber = ep,
                    name = v.optString("title").takeIf { it.isNotBlank() },
                    overview = v.optString("overview").takeIf { it.isNotBlank() },
                    released = v.optString("released").takeIf { it.isNotBlank() },
                    thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
                    rating = null,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Headshot per actor name via person suggest. Finished rows persist past the budget. Never throws. */
    suspend fun fetchCastPhotos(names: List<String>, max: Int = 15): Map<String, String> {
        val wanted = names.filter { it.isNotBlank() }.distinct().take(max)
        if (wanted.isEmpty()) return emptyMap()
        val out = Collections.synchronizedMap(HashMap<String, String>())
        val missing = ArrayList<String>()
        for (n in wanted) {
            photoCache[n.lowercase()]?.let { out[n] = it } ?: missing.add(n)
        }
        if (missing.isEmpty()) return out
        withTimeoutOrNull(6000L) {
            coroutineScope {
                val sem = Semaphore(6)
                missing.map { n ->
                    async {
                        sem.acquire()
                        try {
                            parsePersonImage(suggestRaw(n), n)?.let { out[n] = it }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll()
            }
        }
        val done = out.toMap()
        for ((n, url) in done) {
            if (photoCache.size < CACHE_MAX * 4) photoCache[n.lowercase()] = url
        }
        return done
    }

    /** IMDB ratings by id via Cinemeta (lightweight: no cast enrichment). Finished rows persist past the budget. */
    suspend fun fetchRatings(ids: List<Pair<String, String>>): Map<String, Double> {
        val wanted = ids.distinctBy { it.first }.filter { it.first.startsWith("tt") }
        if (wanted.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val out = Collections.synchronizedMap(HashMap<String, Double>())
        val missing = wanted.filter { (id, _) ->
            val e = ratingCache[id]
            if (e != null && now <= e.second) {
                out[id] = e.first
                false
            } else {
                if (e != null) ratingCache.remove(id)
                true
            }
        }
        if (missing.isEmpty()) return out.toMap()
        withTimeoutOrNull(6000L) {
            coroutineScope {
                val sem = Semaphore(6)
                missing.map { (id, type) ->
                    async {
                        sem.acquire()
                        try {
                            val kind = if (type == "movie") "movie" else "series"
                            val text = runCatching {
                                app.get("$CINEMETA_API/$kind/$id.json", timeout = 5).text
                            }.getOrNull()
                            parseCinemeta(text, id)?.rating?.let { out[id] = it }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll()
            }
        }
        val done = out.toMap()
        for ((id, r) in done) {
            if (ratingCache.size < CACHE_MAX * 4) ratingCache[id] = r to (System.currentTimeMillis() + CACHE_TTL_MS)
        }
        return done
    }

    /** Extend IMDB names with TMDB-exclusive cast rows. IMDB order and photos win. Pure. */
    fun mergeCast(
        imdb: List<ActorData>?,
        tmdb: List<ActorData>?,
        maxTotal: Int = 15,
    ): List<ActorData>? {
        if (imdb.isNullOrEmpty()) return tmdb?.take(maxTotal)
        if (tmdb.isNullOrEmpty()) return imdb
        val seen = imdb.map { it.actor.name.lowercase() }.toHashSet()
        return (imdb + tmdb.filter { seen.add(it.actor.name.lowercase()) }).take(maxTotal)
    }

    /** Raw series payload for episode listing. Never throws. */
    suspend fun fetchSeriesRaw(imdbId: String): String? {
        if (!imdbId.startsWith("tt")) return null
        return withTimeoutOrNull(7000L) {
            runCatching { app.get("$CINEMETA_API/series/$imdbId.json", timeout = 7).text }.getOrNull()
        }
    }
}
