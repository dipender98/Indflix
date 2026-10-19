package com.indstream.foryou

import android.content.Context
import com.indstream.Settings
import com.indstream.TmdbService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject

/** Builds the personalised catalog rows from the account history. */
object ForYouEngine {

    /** How long a computed row stays valid. */
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PREFS = "indstream_foryou"
    /** Seeds (watched titles) used to seed the candidate pool. */
    private const val MAX_SEEDS = 6
    /** Candidates handed to the model. */
    private const val MAX_POOL = 40
    /** Items in one row. */
    private const val ROW_SIZE = 20
    /** Parallel catalog calls. */
    private const val CONCURRENCY = 4

    /** Titles for one row. "movie" or "tv". */
    suspend fun row(type: String, page: Int): List<TmdbService.TmdbItem> {
        if (page > 1) return emptyList()
        val kind = if (type == "movie") "movie" else "tv"
        cached(kind)?.let { return it }
        SimklHistory.refresh()
        if (!SimklHistory.isSignedIn()) return emptyList()
        val profile = SimklHistory.fetch()
        if (profile.isEmpty()) return emptyList()
        val items = build(kind, profile)
        if (items.isEmpty()) return emptyList()
        store(kind, items)
        return items
    }

    /** Build the row: pool from catalog, score locally, then let the model rerank. */
    private suspend fun build(kind: String, profile: List<ProfileTitle>): List<TmdbService.TmdbItem> =
        coroutineScope {
            val seeds = profile.asSequence()
                .filter { it.type == kind && (it.tmdbId ?: 0) > 0 }
                .sortedWith(
                    compareByDescending<ProfileTitle> { it.weight }.thenByDescending { it.lastActivityAt },
                )
                .take(MAX_SEEDS)
                .toList()
            if (seeds.isEmpty()) return@coroutineScope emptyList()

            val detailGate = Semaphore(CONCURRENCY)
            val details = seeds.map { seed ->
                async {
                    detailGate.acquire()
                    try {
                        TmdbService.fetchMeta(seed.tmdbId ?: 0, kind)
                    } finally {
                        detailGate.release()
                    }
                }
            }.awaitAll()

            // Genre picture of the user, weighted by how much each seed matters.
            val genreWeights = HashMap<Int, Double>()
            details.forEachIndexed { i, detail ->
                val w = seeds[i].weight
                detail?.genreIds?.forEach { gid -> genreWeights[gid] = (genreWeights[gid] ?: 0.0) + w }
            }
            val topGenres = genreWeights.entries.sortedByDescending { it.value }.take(3).map { it.key }

            val recGate = Semaphore(CONCURRENCY)
            val recommended = seeds.map { seed ->
                async {
                    recGate.acquire()
                    try {
                        TmdbService.recommendations(seed.tmdbId ?: 0, kind)
                    } finally {
                        recGate.release()
                    }
                }
            }.awaitAll()
            val pool = ArrayList<TmdbService.TmdbItem>()
            recommended.forEach { pool.addAll(it) }
            if (topGenres.isNotEmpty()) pool.addAll(TmdbService.discover(kind, topGenres))

            val seenTmdb = profile.mapNotNull { it.tmdbId }.toHashSet()
            val seenImdb = profile.mapNotNull { it.imdbId }.toHashSet()
            val hits = HashMap<Int, Int>()
            pool.forEach { item ->
                val id = item.tmdbId ?: return@forEach
                if (id in seenTmdb) return@forEach
                if (!item.imdbId.isNullOrBlank() && item.imdbId in seenImdb) return@forEach
                hits[id] = (hits[id] ?: 0) + 1
            }
            val byId = LinkedHashMap<Int, TmdbService.TmdbItem>()
            pool.forEach { item ->
                val id = item.tmdbId ?: return@forEach
                if (id in seenTmdb) return@forEach
                if (!item.imdbId.isNullOrBlank() && item.imdbId in seenImdb) return@forEach
                if (!byId.containsKey(id)) byId[id] = item
            }
            val scored = byId.values
                .map { item ->
                    val id = item.tmdbId ?: 0
                    item to score(item, genreWeights, hits[id] ?: 0)
                }
                .sortedByDescending { it.second }
                .take(MAX_POOL)
                .map { it.first }
                .toList()
            if (scored.isEmpty()) return@coroutineScope emptyList()

            val ordered = rerank(kind, profile, scored)
            ordered.take(ROW_SIZE)
        }

    /** Local score: genre fit, how many seeds pushed it, rating and popularity. */
    internal fun score(
        item: TmdbService.TmdbItem,
        genreWeights: Map<Int, Double>,
        seedHits: Int,
    ): Double {
        var s = 0.0
        s += item.genreIds.sumOf { genreWeights[it] ?: 0.0 } * 1.5
        s += seedHits * 2.0
        s += (item.rating ?: 0.0) / 10.0
        s += minOf(item.popularity / 50.0, 3.0)
        return s
    }

    /** Let the model order the pool. Falls back to the local order. */
    private suspend fun rerank(
        kind: String,
        profile: List<ProfileTitle>,
        scored: List<TmdbService.TmdbItem>,
    ): List<TmdbService.TmdbItem> {
        if (!AiService.isConfigured()) return scored
        val names = TmdbService.genreNames(kind)
        val candidates = scored.mapNotNull { item ->
            val id = item.tmdbId ?: return@mapNotNull null
            AiService.Candidate(
                id = id,
                title = item.name,
                year = item.year,
                rating = item.rating ?: 0.0,
                genres = item.genreIds.mapNotNull { names[it] }.joinToString(", "),
            )
        }
        val watched = profile.filter { it.type == kind }
        val picked = runCatching { AiService.rank(watched, candidates, ROW_SIZE) }.getOrDefault(emptyList())
        if (picked.isEmpty()) return scored
        val byId = scored.associateBy { it.tmdbId }
        val chosen = picked.mapNotNull { byId[it] }
        val chosenIds = chosen.mapNotNull { it.tmdbId }.toHashSet()
        return chosen + scored.filter { it.tmdbId != null && it.tmdbId !in chosenIds }
    }

    /** Cached row for a type, null when absent or stale. */
    private fun cached(kind: String): List<TmdbService.TmdbItem>? {
        val ctx = Settings.appContextOrNull() ?: return null
        val raw = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, null)
        }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val age = System.currentTimeMillis() - root.optLong("ts", 0L)
            if (age > CACHE_TTL_MS) return@runCatching null
            val arr = root.optJSONArray("items") ?: return@runCatching null
            val out = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                TmdbService.TmdbItem(
                    tmdbId = id,
                    imdbId = null,
                    type = kind,
                    name = o.optString("n"),
                    year = o.optString("y").takeIf { it.isNotBlank() },
                    poster = o.optString("p").takeIf { it.isNotBlank() },
                    rating = o.optDouble("r", 0.0).takeIf { it > 0 },
                )
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Persist a computed row so the next home load renders it instantly. */
    private fun store(kind: String, items: List<TmdbService.TmdbItem>) {
        val ctx = Settings.appContextOrNull() ?: return
        runCatching {
            val arr = org.json.JSONArray()
            items.forEach { item ->
                val o = JSONObject()
                o.put("id", item.tmdbId ?: 0)
                o.put("n", item.name)
                o.put("y", item.year ?: "")
                o.put("p", item.poster ?: "")
                o.put("r", item.rating ?: 0.0)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("ts", System.currentTimeMillis())
            root.put("items", arr)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(kind, root.toString()).apply()
        }
    }
}
