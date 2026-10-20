package com.indstream.foryou

import android.content.Context
import com.indstream.Settings
import com.indstream.TmdbService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

object ForYouEngine {

    private const val PREFS = "indstream_foryou"
    private const val MAX_SEEDS = 6
    private const val MAX_POOL = 40
    private const val ROW_SIZE = 7
    private const val CONCURRENCY = 4
    private const val DISCOVER_PAGES = 3

    suspend fun row(type: String, page: Int): List<TmdbService.TmdbItem> {
        if (page > 1) return emptyList()
        val kind = if (type == "movie") "movie" else "tv"
        cached(kind)?.let { return it }
        SimklHistory.refresh()
        if (!SimklHistory.isSignedIn()) return emptyList()
        val profile = SimklHistory.fetch()
        if (profile.isEmpty()) return storedRow(kind).orEmpty()
        val items = build(kind, profile, buildCount(kind))
        if (items.isEmpty()) return storedRow(kind).orEmpty()
        store(kind, items)
        return items
    }

    fun invalidate(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("movie").remove("tv").apply()
        }
    }

    private suspend fun build(
        kind: String,
        profile: List<ProfileTitle>,
        variant: Int,
    ): List<TmdbService.TmdbItem> = coroutineScope {
        val ranked = profile.asSequence()
            .filter { it.type == kind && (it.tmdbId ?: 0) > 0 }
            .sortedWith(
                compareByDescending<ProfileTitle> { it.weight }.thenByDescending { it.lastActivityAt },
            )
            .toList()
        if (ranked.isEmpty()) return@coroutineScope emptyList()
        val offset = if (ranked.size <= MAX_SEEDS) 0 else (variant * MAX_SEEDS) % ranked.size
        val seeds = (0 until minOf(MAX_SEEDS, ranked.size)).map { ranked[(offset + it) % ranked.size] }

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
        if (topGenres.isNotEmpty()) {
            val discoverPage = 1 + (variant % DISCOVER_PAGES)
            pool.addAll(TmdbService.discover(kind, topGenres, discoverPage))
        }
        pool.shuffle(Random(variant * 31L + kind.hashCode()))

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

    private suspend fun rerank(
        kind: String,
        profile: List<ProfileTitle>,
        scored: List<TmdbService.TmdbItem>,
    ): List<TmdbService.TmdbItem> {
        if (!AiService.isConfigured()) return scored.take(ROW_SIZE)
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
            .sortedWith(compareByDescending<ProfileTitle> { it.weight }.thenByDescending { it.lastActivityAt })
        val picked = runCatching { AiService.rank(watched, candidates, ROW_SIZE) }.getOrDefault(emptyList())
        if (picked.isEmpty()) return scored.take(ROW_SIZE)
        val byId = scored.associateBy { it.tmdbId }
        return picked.mapNotNull { byId[it] }.distinctBy { it.tmdbId }.take(ROW_SIZE)
    }

    private fun cached(kind: String): List<TmdbService.TmdbItem>? {
        val ctx = Settings.appContextOrNull() ?: return null
        val root = readRoot(ctx, kind) ?: return null
        val stale = ForYouRefresh.isStale(
            mode = Settings.forYouRefreshMode(),
            now = System.currentTimeMillis(),
            builtAt = root.optLong("ts", 0L),
            builtSession = root.optLong("sid", 0L),
            currentSession = AppSession.id,
            buildsInWindow = buildsInWindow(ctx, kind),
        )
        if (stale) return null
        return itemsOf(root, kind)
    }

    private fun storedRow(kind: String): List<TmdbService.TmdbItem>? {
        val ctx = Settings.appContextOrNull() ?: return null
        val root = readRoot(ctx, kind) ?: return null
        return itemsOf(root, kind)
    }

    private fun readRoot(ctx: Context, kind: String): JSONObject? {
        val raw = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind, null)
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun itemsOf(root: JSONObject, kind: String): List<TmdbService.TmdbItem>? = runCatching {
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
        out.take(ROW_SIZE).takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun store(kind: String, items: List<TmdbService.TmdbItem>) {
        val ctx = Settings.appContextOrNull() ?: return
        val now = System.currentTimeMillis()
        runCatching {
            val arr = JSONArray()
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
            root.put("ts", now)
            root.put("sid", AppSession.id)
            root.put("items", arr)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(kind, root.toString()).apply()
            prefs.edit().putString(buildsKey(kind), buildLog(ctx, kind, now).toString()).apply()
            prefs.edit().putInt(totalKey(kind), totalCount(ctx, kind) + 1).apply()
        }
    }

    private fun buildLog(ctx: Context, kind: String, now: Long = System.currentTimeMillis()): JSONArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = runCatching { prefs.getString(buildsKey(kind), null) }.getOrNull()
        val arr = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val ts = arr.optLong(i, 0L)
            if (ts > 0L && now - ts < ForYouRefresh.DAY_MS) out.put(ts)
        }
        out.put(now)
        return out
    }

    private fun buildsInWindow(ctx: Context, kind: String): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = runCatching { prefs.getString(buildsKey(kind), null) }.getOrNull() ?: return 0
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val cutoff = System.currentTimeMillis() - ForYouRefresh.DAY_MS
        var count = 0
        for (i in 0 until arr.length()) {
            val ts = arr.optLong(i, 0L)
            if (ts >= cutoff) count++
        }
        return count
    }

    private fun buildCount(kind: String): Int {
        val ctx = Settings.appContextOrNull() ?: return 0
        return totalCount(ctx, kind)
    }

    private fun totalCount(ctx: Context, kind: String): Int = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(totalKey(kind), 0)
    }.getOrDefault(0)

    private fun buildsKey(kind: String) = "builds_$kind"

    private fun totalKey(kind: String) = "total_$kind"
}
