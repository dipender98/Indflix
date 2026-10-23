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

object ImdbService {

    private const val SUGGEST_API = "https://v3.sg.media-imdb.com/suggestion/x"
    private const val CINEMETA_HOST = "https://v3-cinemeta.strem.io"
    private const val CINEMETA_API = "$CINEMETA_HOST/meta"
    private const val CINEMETA_CATALOG = "$CINEMETA_HOST/catalog"
    private const val CATALOG_PAGE = 100
    private const val CATALOG_TTL_MS = 10 * 60 * 1000L
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 64
    private const val WAIT_MS = 11_000L

    data class ImdbHit(
        val imdbId: String,
        val title: String,
        val year: Int?,
        val type: String,
        val poster: String?,
        val rank: Int?,
    )

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
        val runtime: Int? = null,
    )

    private data class Entry(val hits: List<ImdbHit>, val expiresAt: Long)
    private data class CatalogEntry(val items: List<TmdbService.TmdbItem>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val catalogCache = ConcurrentHashMap<String, CatalogEntry>()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<ImdbHit>>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metaCache = ConcurrentHashMap<String, MetaDetail>()
    private val photoCache = ConcurrentHashMap<String, String>()
    private val ratingCache = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val negative = ConcurrentHashMap<String, Long>()
    private const val NEGATIVE_TTL_MS = 60_000L

    suspend fun suggest(query: String): List<ImdbHit> {
        if (query.isBlank()) return emptyList()
        val key = query.trim().lowercase()
        cache[key]?.let {
            if (System.currentTimeMillis() <= it.expiresAt) return it.hits
            cache.remove(key)
        }
        val negHit = negative[key]
        if (negHit != null) {
            if (System.currentTimeMillis() <= negHit) return emptyList()
            negative.remove(key)
        }
        val mine = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val found = suggestRemote(query)
            if (found.isNotEmpty()) {
                if (cache.size >= CACHE_MAX) {
                    cache.entries.minByOrNull { e -> e.value.expiresAt }?.let { e -> cache.remove(e.key) }
                }
                cache[key] = Entry(found, System.currentTimeMillis() + CACHE_TTL_MS)
            } else {
                if (negative.size >= CACHE_MAX) {
                    negative.entries.minByOrNull { it.value }?.let { negative.remove(it.key) }
                }
                negative[key] = System.currentTimeMillis() + NEGATIVE_TTL_MS
            }
            found
        }
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing == null) {
            mine.invokeOnCompletion { inFlight.remove(key, mine) }
            mine.start()
        } else {
            mine.cancel()
        }
        val job = existing ?: mine
        return withTimeoutOrNull(WAIT_MS) {
            try {
                job.await()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                emptyList()
            }
        }.orEmpty()
    }

    private suspend fun suggestRemote(query: String): List<ImdbHit> =
        parseSuggest(suggestRaw(query))

    private suspend fun suggestRaw(query: String): String? {
        val slug = query.trim().lowercase().replace(Regex("""\s+"""), "_")
        val encoded = URLEncoder.encode(slug, "UTF-8")
        return withTimeoutOrNull(6000L) { getText("$SUGGEST_API/$encoded.json", 6) }
    }

    private suspend fun getText(url: String, timeoutSec: Long): String? = try {
        app.get(url, timeout = timeoutSec).text
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

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
                val type = if (qid == "tvSeries" || qid == "tvMiniSeries" || qid == "tvEpisode") "tv" else "movie"
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

    suspend fun fetchMeta(imdbId: String, type: String): MetaDetail? {
        if (!imdbId.startsWith("tt")) return null
        val kind = if (type == "movie") "movie" else "series"
        val key = "$kind|$imdbId"
        metaCache[key]?.let { return it }
        val text = withTimeoutOrNull(7000L) { getText("$CINEMETA_API/$kind/$imdbId.json", 7) } ?: return null
        val detail = parseCinemeta(text, imdbId)?.withCastPhotos() ?: return null
        if (metaCache.size >= CACHE_MAX) metaCache.keys.firstOrNull()?.let { metaCache.remove(it) }
        metaCache[key] = detail
        return detail
    }

    suspend fun cinemetaCatalog(
        type: String,
        catalogId: String,
        genre: String? = null,
        page: Int = 1,
    ): List<TmdbService.TmdbItem> {
        val kind = if (type == "movie") "movie" else "series"
        val cleanGenre = genre?.trim().orEmpty()
        val skip = ((page.coerceAtLeast(1) - 1) * CATALOG_PAGE).toString()
        val key = "$kind|$catalogId|$cleanGenre|$skip"
        catalogCache[key]?.let { entry ->
            if (System.currentTimeMillis() <= entry.expiresAt) return entry.items
            catalogCache.remove(key)
        }
        val extras = buildList {
            if (cleanGenre.isNotEmpty()) {
                add("genre=" + URLEncoder.encode(cleanGenre, "UTF-8").replace("+", "%20"))
            }
            add("skip=$skip")
        }
        val url = "$CINEMETA_CATALOG/$kind/$catalogId/${extras.joinToString("&")}.json"
        val raw = withTimeoutOrNull(7000L) { getText(url, 7) } ?: return emptyList()
        val items = parseCinemetaCatalog(raw)
        if (items.isNotEmpty()) {
            if (catalogCache.size >= CACHE_MAX) {
                catalogCache.entries.minByOrNull { e -> e.value.expiresAt }?.let { e -> catalogCache.remove(e.key) }
            }
            catalogCache[key] = CatalogEntry(items, System.currentTimeMillis() + CATALOG_TTL_MS)
        }
        return items
    }

    fun parseCinemetaCatalog(raw: String?): List<TmdbService.TmdbItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val metas = JSONObject(raw).optJSONArray("metas") ?: return emptyList()
            (0 until metas.length()).mapNotNull { i ->
                val m = metas.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id").trim().takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                val name = m.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = if (m.optString("type") == "series") "tv" else "movie"
                val year = yearOf(m.optString("releaseInfo")) ?: yearOf(m.optString("year"))
                TmdbService.TmdbItem(
                    tmdbId = m.optInt("moviedb_id", -1).takeIf { it > 0 },
                    imdbId = id,
                    type = type,
                    name = name,
                    year = year,
                    poster = m.optString("poster").takeIf { it.isNotBlank() }?.largerPoster(),
                    rating = m.optString("imdbRating").toDoubleOrNull()?.takeIf { it > 0 },
                    popularity = m.optDouble("popularity", 0.0),
                    overview = m.optString("description").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun yearOf(raw: String): String? =
        raw.trim().takeIf { it.length >= 4 && it.take(4).all { c -> c.isDigit() } }?.take(4)

    private fun String.largerPoster(): String =
        replace("/poster/small/", "/poster/medium/")

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
                year = m.optString("releaseInfo")?.take(4)?.takeIf { it.isNotEmpty() && it[0].isDigit() },
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

    fun parseRuntimeMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun parseCinemetaEpisodes(raw: String?): List<TmdbService.TmdbEpisode> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val videos = JSONObject(raw).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
            (0 until videos.length()).mapNotNull { i ->
                val v = videos.optJSONObject(i) ?: return@mapNotNull null
                val season = v.optInt("season", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val ep = v.optInt("episode", -1).takeIf { it > 0 }
                    ?: v.optInt("number", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val released = v.optString("released").takeIf { it.isNotBlank() }
                    ?: v.optString("firstAired").takeIf { it.isNotBlank() }
                TmdbService.TmdbEpisode(
                    seasonNumber = season,
                    episodeNumber = ep,
                    name = v.optString("name").takeIf { it.isNotBlank() }
                        ?: v.optString("title").takeIf { it.isNotBlank() },
                    overview = v.optString("overview").takeIf { it.isNotBlank() }
                        ?: v.optString("description").takeIf { it.isNotBlank() },
                    released = released?.take(10)?.takeIf { it.isNotBlank() },
                    thumbnail = v.optString("thumbnail").takeIf { it.isNotBlank() },
                    rating = null,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

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
            if (photoCache.size >= CACHE_MAX * 4) photoCache.keys.firstOrNull()?.let { photoCache.remove(it) }
            photoCache[n.lowercase()] = url
        }
        return done
    }

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
        val fetched = Collections.synchronizedMap(HashMap<String, Double>())
        withTimeoutOrNull(6000L) {
            coroutineScope {
                val sem = Semaphore(6)
                missing.map { (id, type) ->
                    async {
                        sem.acquire()
                        try {
                            val kind = if (type == "movie") "movie" else "series"
                            val text = getText("$CINEMETA_API/$kind/$id.json", 5)
                            parseCinemeta(text, id)?.rating?.let { fetched[id] = it }
                        } finally {
                            sem.release()
                        }
                    }
                }.awaitAll()
            }
        }
        val done = out.toMap()
        for ((id, r) in fetched) {
            if (ratingCache.size >= CACHE_MAX * 4) ratingCache.keys.firstOrNull()?.let { ratingCache.remove(it) }
            ratingCache[id] = r to (System.currentTimeMillis() + CACHE_TTL_MS)
        }
        return done + fetched
    }

    fun mergeCast(
        imdb: List<ActorData>?,
        tmdb: List<ActorData>?,
        maxTotal: Int = 15,
    ): List<ActorData>? {
        if (imdb.isNullOrEmpty()) return tmdb?.take(maxTotal)
        if (tmdb.isNullOrEmpty()) return imdb.take(maxTotal)
        val seen = imdb.map { it.actor.name.lowercase() }.toHashSet()
        return (imdb + tmdb.filter { seen.add(it.actor.name.lowercase()) }).take(maxTotal)
    }

    suspend fun fetchSeriesRaw(imdbId: String): String? {
        if (!imdbId.startsWith("tt")) return null
        return withTimeoutOrNull(7000L) { getText("$CINEMETA_API/series/$imdbId.json", 7) }
    }
}
