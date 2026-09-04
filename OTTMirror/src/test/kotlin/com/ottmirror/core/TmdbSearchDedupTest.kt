package com.ottmirror.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the TMDB multi-search parser: movie/tv filtering, junk duplicate
 * collapsing by (title, year), and highest-rated-entry retention.
 */
class TmdbSearchDedupTest {

    private fun resultJson(
        id: Int,
        mediaType: String,
        title: String,
        date: String,
        rating: Double,
    ): String = """{"id":$id,"media_type":"$mediaType","title":"$title","release_date":"$date","vote_average":$rating,"poster_path":null}"""

    @Test
    fun multiSearch_collapsesSameTitleAndYear() {
        // Mirrors TMDB's real response for "breaking bad": the genuine series
        // (tv/1396) plus a junk movie duplicate (movie/1762067) of the same
        // title and year.
        val json = """
            {"results":[
              ${resultJson(1396, "tv", "Breaking Bad", "2008-01-20", 8.951)},
              ${resultJson(1762067, "movie", "Breaking Bad", "2008-01-20", 0.0)}
            ]}
        """.trimIndent()

        val items = TmdbService.parseTmdbMultiSearch(json)

        assertEquals(1, items.size)
        assertEquals(1396, items.single().tmdbId)
        assertEquals("series", items.single().type)
    }

    @Test
    fun multiSearch_keepsHighestRatedOfGroup() {
        val json = """
            {"results":[
              ${resultJson(1, "movie", "Foo", "2020-01-01", 5.0)},
              ${resultJson(2, "tv", "Foo", "2020-01-01", 7.5)}
            ]}
        """.trimIndent()

        val items = TmdbService.parseTmdbMultiSearch(json)

        assertEquals(1, items.size)
        assertEquals(2, items.single().tmdbId)
    }

    @Test
    fun multiSearch_keepsSameTitleWithDifferentYear() {
        val json = """
            {"results":[
              ${resultJson(1, "movie", "Foo", "2020-01-01", 5.0)},
              ${resultJson(2, "movie", "Foo", "2023-01-01", 6.0)}
            ]}
        """.trimIndent()

        val items = TmdbService.parseTmdbMultiSearch(json)

        assertEquals(2, items.size)
    }

    @Test
    fun multiSearch_dedupKeepsFirstOrder() {
        val json = """
            {"results":[
              ${resultJson(1, "tv", "Alpha", "2020-01-01", 9.0)},
              ${resultJson(2, "movie", "Beta", "2021-01-01", 8.0)},
              ${resultJson(3, "movie", "Alpha", "2020-01-01", 1.0)}
            ]}
        """.trimIndent()

        val items = TmdbService.parseTmdbMultiSearch(json)

        assertEquals(listOf(1, 2), items.map { it.tmdbId })
    }

    @Test
    fun multiSearch_dedupTreatsCaseAndWhitespaceAsSame() {
        val json = """
            {"results":[
              ${resultJson(1, "tv", "breaking bad", "2008-01-20", 9.0)},
              ${resultJson(2, "movie", "  Breaking Bad ", "2008-01-20", 8.0)}
            ]}
        """.trimIndent()

        val items = TmdbService.parseTmdbMultiSearch(json)

        assertEquals(1, items.size)
        assertTrue(items.single().rating!! >= 9.0)
    }
}
