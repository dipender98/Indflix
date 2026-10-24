package com.indstream

import android.content.Context
import com.indstream.foryou.SimklHistory

object Catalogue {

    enum class Group(val id: String, val label: String) {
        GLOBAL("global", "Global"),
        PAN_INDIA("pan-india", "Pan India"),
        LANGUAGES("languages", "Language Wise"),
    }

    enum class Provider(val id: String, val label: String) {
        TMDB("tmdb", "TMDB"),
        CINEMETA("cinemeta", "IMDB / Cinemeta"),
        ;

        companion object {
            const val PREFERRED = "tmdb"

            fun from(id: String?): Provider =
                entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: TMDB
        }
    }

    sealed interface Source {
        data object Trending : Source
        data object Popular : Source
        data class Genre(
            val movieIds: List<Int>,
            val tvIds: List<Int>,
            val movieKeywords: List<Int> = emptyList(),
            val tvKeywords: List<Int> = emptyList(),
        ) : Source
        data class LanguageTrending(val code: String) : Source
    }

    data class Category(
        val id: String,
        val group: Group,
        val label: String,
        val source: Source,
        val cinemetaGenre: String? = null,
    )

    private const val ORIGIN = "IN"

    private const val FOR_YOU_MOVIE = "foryou|movie"
    private const val FOR_YOU_TV = "foryou|tv"

    val forYouRowKeys: List<String> = listOf(FOR_YOU_MOVIE, FOR_YOU_TV)

    private data class Language(val code: String, val label: String)

    private val languages = listOf(
        Language("hi", "Hindi"),
        Language("ta", "Tamil"),
        Language("te", "Telugu"),
        Language("ml", "Malayalam"),
        Language("kn", "Kannada"),
        Language("gu", "Gujarati"),
        Language("bn", "Bengali"),
    )

    private fun category(
        group: Group,
        slug: String,
        label: String,
        source: Source,
        cinemetaGenre: String? = null,
    ): Category = Category("${group.id}.$slug", group, label, source, cinemetaGenre)

    private fun genre(
        group: Group,
        slug: String,
        label: String,
        movieIds: List<Int>,
        tvIds: List<Int>,
        movieKeywords: List<Int> = emptyList(),
        tvKeywords: List<Int> = emptyList(),
        cinemetaGenre: String,
    ): Category =
        category(group, slug, label, Source.Genre(movieIds, tvIds, movieKeywords, tvKeywords), cinemetaGenre)

    val categories: List<Category> = buildList {
        add(category(Group.GLOBAL, "trending", "Trending", Source.Trending))
        add(category(Group.GLOBAL, "popular", "Popular", Source.Popular))
        add(
            genre(
                Group.GLOBAL, "drama", "Drama", listOf(18), listOf(18),
                cinemetaGenre = "Drama",
            ),
        )
        add(
            genre(
                Group.GLOBAL, "action", "Action", listOf(28), listOf(10759),
                cinemetaGenre = "Action",
            ),
        )
        add(
            genre(
                Group.GLOBAL, "comedy", "Comedy", listOf(35), listOf(35),
                cinemetaGenre = "Comedy",
            ),
        )
        add(
            genre(
                Group.GLOBAL, "thriller-crime", "Thriller/Crime", listOf(53, 80), listOf(80, 9648),
                cinemetaGenre = "Thriller",
            ),
        )
        add(
            genre(
                Group.GLOBAL, "sci-fi-fantasy", "Sci-Fi & Fantasy", listOf(878), listOf(10765),
                cinemetaGenre = "Sci-Fi",
            ),
        )

        add(category(Group.PAN_INDIA, "trending", "Trending", Source.Trending))
        add(category(Group.PAN_INDIA, "popular", "Popular", Source.Popular))
        add(
            genre(
                Group.PAN_INDIA, "drama", "Drama", listOf(18), listOf(18),
                cinemetaGenre = "Drama",
            ),
        )
        add(
            genre(
                Group.PAN_INDIA, "action", "Action", listOf(28), listOf(10759),
                cinemetaGenre = "Action",
            ),
        )
        add(
            genre(
                Group.PAN_INDIA, "comedy", "Comedy", listOf(35), listOf(35),
                cinemetaGenre = "Comedy",
            ),
        )
        add(
            genre(
                Group.PAN_INDIA, "thriller-crime", "Thriller/Crime", listOf(53, 80), listOf(80, 9648),
                cinemetaGenre = "Thriller",
            ),
        )
        add(
            genre(
                Group.PAN_INDIA, "romance", "Romance", listOf(10749), emptyList(),
                tvKeywords = listOf(9840),
                cinemetaGenre = "Romance",
            ),
        )

        languages.forEach { language ->
            add(
                category(
                    Group.LANGUAGES,
                    language.code,
                    language.label,
                    Source.LanguageTrending(language.code),
                ),
            )
        }
    }

    fun rowKey(category: Category, type: String): String =
        "cat|${category.id}|${normalizeType(type)}"

    fun parseRow(data: String): Pair<Category, String>? {
        val parts = data.split("|")
        if (parts.size != 3 || parts[0].trim() != "cat") return null
        val category = categories.firstOrNull { it.id == parts[1].trim() } ?: return null
        val type = when (parts[2].trim()) {
            "movie" -> "movie"
            "tv" -> "tv"
            else -> return null
        }
        return category to type
    }

    fun allRows(): List<Pair<String, String>> =
        categories.flatMap { category -> rowsFor(category) }

    fun rowsFor(group: Group): List<Pair<String, String>> =
        categories.filter { it.group == group }.flatMap { category -> rowsFor(category) }

    fun enabledRows(): List<Pair<String, String>> =
        allRows().filter { (key, _) -> Settings.catalogueEnabled(key) }

    fun orderedRows(): List<Pair<String, String>> =
        arrange(enabledRows(), Settings.catalogueOrder())

    fun forYouRows(): List<Pair<String, String>> =
        if (SimklHistory.isSignedIn()) {
            listOf(
                FOR_YOU_MOVIE to "Made for You: Movies",
                FOR_YOU_TV to "Made for You: Series",
            )
        } else {
            emptyList()
        }

    fun defaultHomeRows(): List<Pair<String, String>> = forYouRows() + enabledRows()

    fun homeRows(): List<Pair<String, String>> =
        arrange(defaultHomeRows(), Settings.catalogueOrder())

    // Ranked rows come first in the saved sequence; unknown rows keep their default spot after them.
    fun arrange(
        rows: List<Pair<String, String>>,
        order: List<String>,
    ): List<Pair<String, String>> {
        if (rows.size < 2 || order.isEmpty()) return rows
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, key ->
            val clean = key.trim()
            if (clean.isNotEmpty() && !rank.containsKey(clean)) rank[clean] = index
        }
        if (rank.isEmpty()) return rows
        return rows.sortedBy { rank[it.first] ?: Int.MAX_VALUE }
    }

    // Rows hidden by the current state (for example sign-in-only rows) keep their previous slots.
    fun mergeOrder(
        visible: List<String>,
        previous: List<String>,
        hidden: Set<String>,
    ): List<String> {
        if (hidden.isEmpty()) return visible
        val result = ArrayList<String>(visible.size + hidden.size)
        var next = 0
        previous.forEach { key ->
            if (key in hidden) result += key
            else if (next < visible.size) result += visible[next++]
        }
        while (next < visible.size) result += visible[next++]
        hidden.forEach { if (it !in result) result += it }
        return result
    }

    private fun rowsFor(category: Category): List<Pair<String, String>> = listOf(
        rowKey(category, "movie") to rowTitle(category, "movie"),
        rowKey(category, "tv") to rowTitle(category, "tv"),
    )

    private fun rowTitle(category: Category, type: String): String {
        val prefix = if (category.group == Group.LANGUAGES) category.label else category.group.label
        val kind = when (category.source) {
            Source.Trending, is Source.LanguageTrending -> "Trending"
            Source.Popular -> "Popular"
            is Source.Genre -> category.label
        }
        val media = if (normalizeType(type) == "movie") "Movies" else "Series"
        return "$prefix: $kind $media"
    }

    fun activeProvider(): Provider = Provider.from(Settings.catalogueProvider())

    fun setProvider(context: Context, provider: Provider) {
        Settings.saveCatalogueProvider(context, provider)
    }

    suspend fun fetch(category: Category, type: String, page: Int): List<TmdbService.TmdbItem> =
        when (activeProvider()) {
            Provider.TMDB -> fetchFromTmdb(category, type, page)
            Provider.CINEMETA ->
                if (category.group == Group.GLOBAL) fetchFromCinemeta(category, type, page)
                else fetchFromTmdb(category, type, page)
        }

    private suspend fun fetchFromCinemeta(
        category: Category,
        type: String,
        page: Int,
    ): List<TmdbService.TmdbItem> {
        val kind = normalizeType(type)
        return when (val source = category.source) {
            Source.Trending -> ImdbService.cinemetaCatalog(kind, "top", page = page)
            Source.Popular -> ImdbService.cinemetaCatalog(kind, "imdbRating", page = page)
            is Source.Genre -> ImdbService.cinemetaCatalog(kind, "top", category.cinemetaGenre, page)
            is Source.LanguageTrending -> emptyList()
        }
    }

    private suspend fun fetchFromTmdb(category: Category, type: String, page: Int): List<TmdbService.TmdbItem> {
        val kind = normalizeType(type)
        return when (val source = category.source) {
            Source.Trending -> if (category.group == Group.GLOBAL) {
                TmdbService.trending(kind, page)
            } else {
                TmdbService.discover(
                    kind,
                    emptyList(),
                    page,
                    originCountry = ORIGIN,
                    sortBy = "popularity.desc",
                    minVotes = 5,
                )
            }
            Source.Popular -> if (category.group == Group.GLOBAL) {
                TmdbService.popular(kind, page)
            } else {
                TmdbService.discover(
                    kind,
                    emptyList(),
                    page,
                    originCountry = ORIGIN,
                    sortBy = "popularity.desc",
                    minVotes = 100,
                )
            }
            is Source.Genre -> TmdbService.discover(
                kind,
                if (kind == "movie") source.movieIds else source.tvIds,
                page,
                originCountry = if (category.group == Group.GLOBAL) null else ORIGIN,
                keywordIds = if (kind == "movie") source.movieKeywords else source.tvKeywords,
            )
            is Source.LanguageTrending -> TmdbService.discover(
                kind,
                emptyList(),
                page,
                language = source.code,
                sortBy = "popularity.desc",
            )
        }
    }

    private fun normalizeType(type: String): String = if (type == "movie") "movie" else "tv"
}
