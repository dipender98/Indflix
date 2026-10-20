package com.indstream

/** Genre rows of the home page, served by the catalog API. */
object GenreCatalog {

    /** One genre with its id per media type; zero means the type has no such genre. */
    data class Genre(val label: String, val movieId: Int, val tvId: Int = 0)

    /** The genres the catalog rows cover. */
    val genres = listOf(
        Genre("Action", 28, 10759),
        Genre("Comedy", 35, 35),
        Genre("Crime", 80, 80),
        Genre("Drama", 18, 18),
        Genre("Mystery", 9648, 9648),
        Genre("Sci-Fi & Fantasy", 878, 10765),
    )

    /** Genre id for one media type. */
    fun idFor(genre: Genre, type: String): Int =
        if (type == "movie") genre.movieId else genre.tvId

    /** Row data key: "genre|<id>|<movie|tv>". */
    fun rowData(genre: Genre, type: String): String =
        "genre|${idFor(genre, type)}|${if (type == "movie") "movie" else "tv"}"

    /** Row title, like "Action Movies". */
    fun rowTitle(genre: Genre, type: String): String =
        "${genre.label} ${if (type == "movie") "Movies" else "Series"}"

    /** Every row of the catalog: data key to title. */
    fun rows(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(genres.size * 2)
        genres.forEach { genre ->
            out += rowData(genre, "movie") to rowTitle(genre, "movie")
            if (genre.tvId > 0) out += rowData(genre, "tv") to rowTitle(genre, "tv")
        }
        return out
    }

    /** Genre id and media type of a row key, null when the key is not a genre row. */
    fun parseRow(data: String): Pair<Int, String>? {
        val parts = data.split("|")
        if (parts.size < 3 || parts[0].trim() != "genre") return null
        val id = parts[1].trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val type = if (parts[2].trim() == "tv") "tv" else "movie"
        return id to type
    }
}
