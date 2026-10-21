package com.indstream.foryou

import android.content.Context
import com.indstream.Settings
import com.indstream.TmdbService
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

object ForYouEngine {

    private const val PREFS = "indstream_foryou"
    private const val CACHE_VERSION = 2
    private const val PROFILE_TITLES = 12
    private const val MAX_SEEDS = 6
    private const val MAX_DISLIKES = 3
    private const val MAX_POOL = 60
    private const val AI_CANDIDATES = 45
    private const val ROW_SIZE = 10
    private const val SHORTLIST_SIZE = ROW_SIZE * 2
    private const val CONCURRENCY = 4
    private const val DISCOVER_PAGES = 3
    private const val MIN_VOTES = 100
    private const val DIVERSITY = 0.28

    internal data class Taste(
        val genres: Map<Int, Double> = emptyMap(),
        val dislikes: Map<Int, Double> = emptyMap(),
        val languages: Map<String, Double> = emptyMap(),
        val era: Int = 0,
    )

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
        val positive = ranked.filter { it.weight > 0.05 }
            .ifEmpty { ranked.filter { it.weight > 0.0 } }
        if (positive.isEmpty()) return@coroutineScope emptyList()
        val profileTitles = positive.take(PROFILE_TITLES)
        val disliked = ranked.filter { it.weight <= -0.1 }.sortedBy { it.weight }.take(MAX_DISLIKES)
        val offset = if (profileTitles.size <= MAX_SEEDS) {
            0
        } else {
            (variant * MAX_SEEDS) % profileTitles.size
        }
        val seeds = (0 until minOf(MAX_SEEDS, profileTitles.size)).map {
            profileTitles[(offset + it) % profileTitles.size]
        }

        val gate = Semaphore(CONCURRENCY)
        val profileDetails = profileTitles.map { async { detailOf(it, kind, gate) } }
        val dislikeDetails = disliked.map { async { detailOf(it, kind, gate) } }
        val recommended = seeds.map { async { recommendationsOf(it, kind, gate) } }
        val similar = seeds.take(3).map { async { similarOf(it, kind, gate) } }

        val details = profileDetails.awaitAll()
        val dislikedDetails = dislikeDetails.awaitAll()
        val recommendations = recommended.awaitAll()
        val similars = similar.awaitAll()

        val genreWeights = HashMap<Int, Double>()
        val languageWeights = HashMap<String, Double>()
        var yearSum = 0.0
        var yearWeight = 0.0
        profileTitles.forEachIndexed { index, title ->
            val detail = details[index] ?: return@forEachIndexed
            val weight = title.weight
            detail.genreIds.forEach { id ->
                genreWeights[id] = (genreWeights[id] ?: 0.0) + weight
            }
            detail.originalLanguage?.let { language ->
                languageWeights[language] = (languageWeights[language] ?: 0.0) + weight
            }
            detail.year?.toIntOrNull()?.let { year ->
                yearSum += year * weight
                yearWeight += weight
            }
        }
        val dislikeWeights = HashMap<Int, Double>()
        disliked.forEachIndexed { index, title ->
            val detail = dislikedDetails[index] ?: return@forEachIndexed
            val weight = -title.weight
            detail.genreIds.forEach { id ->
                dislikeWeights[id] = (dislikeWeights[id] ?: 0.0) + weight
            }
        }
        val taste = Taste(
            genres = normalize(genreWeights),
            dislikes = normalize(dislikeWeights),
            languages = normalize(languageWeights),
            era = if (yearWeight > 0.0) (yearSum / yearWeight).toInt() else 0,
        )
        val topGenres = genreWeights.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val topLanguages = languageWeights.entries.sortedByDescending { it.value }.take(3).map { it.key }

        val pool = ArrayList<TmdbService.TmdbItem>()
        recommendations.forEach { pool.addAll(it) }
        similars.forEach { pool.addAll(it) }
        if (topGenres.isNotEmpty()) {
            val page = 1 + (variant % DISCOVER_PAGES)
            val extras = mutableListOf(
                async { TmdbService.discover(kind, topGenres, page) },
                async {
                    TmdbService.discover(
                        kind, topGenres, page, sortBy = "vote_average.desc", minVotes = MIN_VOTES,
                    )
                },
            )
            topLanguages.firstOrNull()?.let { language ->
                extras += async {
                    TmdbService.discover(kind, topGenres.take(2), page, language = language)
                }
            }
            extras.awaitAll().forEach { pool.addAll(it) }
        }
        pool.shuffle(Random(variant * 31L + kind.hashCode()))

        val seenTmdb = profile.mapNotNull { it.tmdbId }.toHashSet()
        val seenImdb = profile.mapNotNull { it.imdbId }.toHashSet()
        val seenTitles = profile.mapNotNull { title ->
            title.year?.let { "${normTitle(title.title)}|$it" }
        }.toHashSet()
        val seedAffinity = HashMap<Int, Double>()
        recommendations.forEachIndexed { index, items ->
            val weight = seeds[index].weight.coerceAtLeast(0.05)
            items.forEach { item ->
                item.tmdbId?.let { id -> seedAffinity[id] = (seedAffinity[id] ?: 0.0) + weight }
            }
        }
        similars.forEachIndexed { index, items ->
            val weight = seeds[index].weight.coerceAtLeast(0.05) * 0.6
            items.forEach { item ->
                item.tmdbId?.let { id -> seedAffinity[id] = (seedAffinity[id] ?: 0.0) + weight }
            }
        }
        val byId = LinkedHashMap<Int, TmdbService.TmdbItem>()
        pool.forEach { item ->
            if (!eligible(item, seenTmdb, seenImdb, seenTitles)) return@forEach
            val id = item.tmdbId ?: return@forEach
            if (!byId.containsKey(id)) byId[id] = item
        }
        val scored = byId.values
            .map { item -> item to score(item, taste, seedAffinity[item.tmdbId ?: 0] ?: 0.0) }
            .sortedByDescending { it.second }
            .take(MAX_POOL)
            .map { it.first }
            .toList()
        if (scored.isEmpty()) return@coroutineScope emptyList()

        val names = if (AiService.isConfigured()) TmdbService.genreNames(kind) else emptyMap()
        rerank(kind, profile, scored, taste, names).take(ROW_SIZE)
    }

    internal fun score(
        item: TmdbService.TmdbItem,
        genreWeights: Map<Int, Double>,
        seedHits: Int,
    ): Double = score(item, Taste(genres = genreWeights), seedHits)

    internal fun score(
        item: TmdbService.TmdbItem,
        taste: Taste,
        seedHits: Int,
    ): Double = score(item, taste, seedHits.toDouble())

    internal fun score(
        item: TmdbService.TmdbItem,
        taste: Taste,
        seedAffinity: Double,
    ): Double {
        var value = 0.0
        value += item.genreIds.sumOf { taste.genres[it] ?: 0.0 } * 1.35
        value += seedAffinity * 1.8
        value += quality(item) * 1.2
        value += popularityScore(item.popularity)
        value += (item.originalLanguage?.let { taste.languages[it] } ?: 0.0) * 0.7
        value += eraScore(item.year, taste.era)
        val disliked = item.genreIds.sumOf { taste.dislikes[it] ?: 0.0 }
        value -= minOf(disliked, 1.0) * 2.0
        return value
    }

    private fun quality(item: TmdbService.TmdbItem): Double {
        val rating = item.rating ?: return 0.0
        if (rating <= 0.0) return 0.0
        val votes = item.voteCount
        val blended = if (votes > 0) {
            (votes * rating + 250 * 6.6) / (votes + 250)
        } else {
            rating
        }
        return ((blended - 5.5) / 4.5).coerceIn(-0.4, 1.0)
    }

    private fun popularityScore(popularity: Double): Double {
        if (popularity <= 0.0) return 0.0
        return (ln(1.0 + popularity) / ln(501.0)).coerceIn(0.0, 1.0) * 1.2
    }

    private fun eraScore(year: String?, era: Int): Double {
        val value = year?.toIntOrNull() ?: return 0.0
        if (era <= 0) return 0.0
        return (1.0 - abs(value - era) / 22.0).coerceIn(0.0, 1.0) * 0.5
    }

    private suspend fun rerank(
        kind: String,
        profile: List<ProfileTitle>,
        scored: List<TmdbService.TmdbItem>,
        taste: Taste,
        names: Map<Int, String>,
    ): List<TmdbService.TmdbItem> {
        if (!AiService.isConfigured()) return diversify(scored, ROW_SIZE)
        val candidates = scored.take(AI_CANDIDATES).mapNotNull { item ->
            val id = item.tmdbId ?: return@mapNotNull null
            AiService.Candidate(
                id = id,
                title = item.name,
                year = item.year,
                rating = item.rating ?: 0.0,
                genres = item.genreIds.mapNotNull { names[it] }.joinToString(", "),
                language = item.originalLanguage.orEmpty(),
                overview = item.overview.orEmpty(),
                votes = item.voteCount,
            )
        }
        val watched = profile.filter { it.type == kind }
            .sortedWith(compareByDescending<ProfileTitle> { it.weight }.thenByDescending { it.lastActivityAt })
        val summary = AiService.Taste(
            genres = taste.genres.entries.sortedByDescending { it.value }
                .mapNotNull { names[it.key] }.take(4),
            languages = taste.languages.entries.sortedByDescending { it.value }
                .map { it.key }.take(3),
            era = taste.era.takeIf { it > 0 },
            avoidGenres = taste.dislikes.entries.sortedByDescending { it.value }
                .mapNotNull { names[it.key] }.take(3),
        )
        val picked = try {
            AiService.rank(watched, candidates, SHORTLIST_SIZE, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val byId = scored.associateBy { it.tmdbId }
        val aiItems = picked.mapNotNull { byId[it] }.distinctBy { it.tmdbId }
        if (aiItems.isEmpty()) return diversify(scored, ROW_SIZE)

        val relevance = HashMap<Int, Double>()
        val localRanks = scored.withIndex().associate { (index, item) -> (item.tmdbId ?: 0) to index }
        aiItems.forEachIndexed { index, item ->
            val id = item.tmdbId ?: return@forEachIndexed
            val aiRank = 1.0 - 0.5 * index / aiItems.size.coerceAtLeast(1)
            val localRank = 1.0 - (localRanks[id] ?: scored.size).toDouble() / scored.size
            relevance[id] = aiRank * 0.7 + localRank * 0.3
        }
        scored.forEachIndexed { index, item ->
            val id = item.tmdbId ?: return@forEachIndexed
            if (id !in relevance) {
                relevance[id] = 0.55 * (1.0 - index.toDouble() / scored.size)
            }
        }
        return diversify(scored, ROW_SIZE, relevance)
    }

    internal fun diversify(
        items: List<TmdbService.TmdbItem>,
        count: Int,
        relevance: Map<Int, Double>? = null,
    ): List<TmdbService.TmdbItem> {
        if (items.isEmpty()) return emptyList()
        val ranks = items.withIndex().associate { (index, item) -> (item.tmdbId ?: 0) to index }
        val remaining = items.toMutableList()
        val picked = ArrayList<TmdbService.TmdbItem>(count)
        while (picked.size < count && remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { item ->
                val id = item.tmdbId ?: 0
                val relevanceScore = relevance?.get(id)
                    ?: (1.0 - (ranks[id] ?: 0).toDouble() / items.size)
                val similarity = picked.maxOfOrNull { similarity(item, it) } ?: 0.0
                relevanceScore - DIVERSITY * similarity
            } ?: break
            picked += next
            remaining.remove(next)
        }
        return picked
    }

    private fun similarity(a: TmdbService.TmdbItem, b: TmdbService.TmdbItem): Double {
        val genresA = a.genreIds.toSet()
        val genresB = b.genreIds.toSet()
        val union = genresA.union(genresB)
        val genre = if (union.isEmpty()) 0.0 else genresA.intersect(genresB).size.toDouble() / union.size
        val languageA = a.originalLanguage
        val languageB = b.originalLanguage
        val language = if (!languageA.isNullOrBlank() && languageA == languageB) 1.0 else 0.0
        val yearA = a.year?.toIntOrNull() ?: 0
        val yearB = b.year?.toIntOrNull() ?: 0
        val era = if (yearA > 0 && yearB > 0) {
            (1.0 - abs(yearA - yearB) / 25.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return genre * 0.7 + language * 0.2 + era * 0.1
    }

    private fun <K> normalize(values: Map<K, Double>): Map<K, Double> {
        val max = values.values.maxOrNull() ?: return emptyMap()
        if (max <= 0.0) return emptyMap()
        return values.mapValues { it.value / max }
    }

    private fun normTitle(title: String): String =
        title.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun eligible(
        item: TmdbService.TmdbItem,
        seenTmdb: Set<Int>,
        seenImdb: Set<String>,
        seenTitles: Set<String>,
    ): Boolean {
        if (item.name.isBlank()) return false
        val id = item.tmdbId ?: return false
        if (id in seenTmdb) return false
        if (!item.imdbId.isNullOrBlank() && item.imdbId in seenImdb) return false
        val year = item.year?.toIntOrNull()
        if (year != null && "${normTitle(item.name)}|$year" in seenTitles) return false
        return true
    }

    private suspend fun detailOf(
        title: ProfileTitle,
        kind: String,
        gate: Semaphore,
    ): TmdbService.TmdbDetail? {
        val id = title.tmdbId ?: return null
        gate.acquire()
        return try {
            TmdbService.fetchMeta(id, kind)
        } finally {
            gate.release()
        }
    }

    private suspend fun recommendationsOf(
        title: ProfileTitle,
        kind: String,
        gate: Semaphore,
    ): List<TmdbService.TmdbItem> {
        val id = title.tmdbId ?: return emptyList()
        gate.acquire()
        return try {
            TmdbService.recommendations(id, kind)
        } finally {
            gate.release()
        }
    }

    private suspend fun similarOf(
        title: ProfileTitle,
        kind: String,
        gate: Semaphore,
    ): List<TmdbService.TmdbItem> {
        val id = title.tmdbId ?: return emptyList()
        gate.acquire()
        return try {
            TmdbService.similar(id, kind)
        } finally {
            gate.release()
        }
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
        if (root.optInt("v", 0) != CACHE_VERSION) return@runCatching null
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
            root.put("v", CACHE_VERSION)
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
