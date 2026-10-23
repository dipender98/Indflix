package com.indstream

object Catalogue {

    enum class Group(val id: String, val label: String) {
        GLOBAL("global", "Global"),
        PAN_INDIA("pan-india", "Pan India"),
        LANGUAGES("languages", "Languages"),
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
    )

    private const val ORIGIN = "IN"

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
    ): Category = Category("${group.id}.$slug", group, label, source)

    private fun genre(
        group: Group,
        slug: String,
        label: String,
        movieIds: List<Int>,
        tvIds: List<Int>,
        movieKeywords: List<Int> = emptyList(),
        tvKeywords: List<Int> = emptyList(),
    ): Category =
        category(group, slug, label, Source.Genre(movieIds, tvIds, movieKeywords, tvKeywords))

    val categories: List<Category> = buildList {
        add(category(Group.GLOBAL, "trending", "Trending", Source.Trending))
        add(category(Group.GLOBAL, "popular", "Popular", Source.Popular))
        add(genre(Group.GLOBAL, "drama", "Drama", listOf(18), listOf(18)))
        add(genre(Group.GLOBAL, "action", "Action", listOf(28), listOf(10759)))
        add(genre(Group.GLOBAL, "comedy", "Comedy", listOf(35), listOf(35)))
        add(genre(Group.GLOBAL, "thriller-crime", "Thriller/Crime", listOf(53, 80), listOf(80, 9648)))
        add(genre(Group.GLOBAL, "sci-fi-fantasy", "Sci-Fi & Fantasy", listOf(878), listOf(10765)))

        add(category(Group.PAN_INDIA, "trending", "Trending", Source.Trending))
        add(category(Group.PAN_INDIA, "popular", "Popular", Source.Popular))
        add(genre(Group.PAN_INDIA, "drama", "Drama", listOf(18), listOf(18)))
        add(genre(Group.PAN_INDIA, "action", "Action", listOf(28), listOf(10759)))
        add(genre(Group.PAN_INDIA, "comedy", "Comedy", listOf(35), listOf(35)))
        add(genre(Group.PAN_INDIA, "thriller-crime", "Thriller/Crime", listOf(53, 80), listOf(80, 9648)))
        add(genre(Group.PAN_INDIA, "romance", "Romance", listOf(10749), emptyList(), tvKeywords = listOf(9840)))

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
        return "$prefix \u00B7 $kind $media"
    }

    suspend fun fetch(category: Category, type: String, page: Int): List<TmdbService.TmdbItem> {
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
