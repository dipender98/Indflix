package Test

import com.indstream.SearchRank
import com.indstream.TmdbUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards fuzzy ranking parity plus both-id URL parsing. */
class SearchRankTest {

    @Test
    fun variants_respellTypoQuery() {
        assertEquals(listOf("baahubali"), SearchRank.queryVariants("bahubali"))
        assertTrue(SearchRank.queryVariants("baahubali").isEmpty())
    }

    @Test
    fun relevance_typoReachesTitle() {
        assertTrue(SearchRank.relevance("bahubali", "Baahubali: The Beginning") > 0.7)
        assertTrue(SearchRank.relevance("bahubali", "Spider Man") == 0.0)
    }

    @Test
    fun combined_exactWinsAndPopularBreaksTies() {
        assertEquals(1.0, SearchRank.combined("dune", "Dune", 100, 9.0))
        val popular = SearchRank.combined("bahubali", "Baahubali Beginning", 500, null)
        val obscure = SearchRank.combined("bahubali", "Baahubali Beginning", 900000, null)
        assertTrue(popular > obscure)
    }

    @Test
    fun dedupeKey_collapsesCaseAndYear() {
        assertEquals(
            SearchRank.dedupeKey("Dune", 2021),
            SearchRank.dedupeKey("  dune ", 2021),
        )
        assertTrue(SearchRank.dedupeKey("Dune", 2021) != SearchRank.dedupeKey("Dune", 2024))
    }

    @Test
    fun parseTitleUrl_readsBothForms() {
        val tmdb = TmdbUrlParser.parseTitleUrl("https://www.themoviedb.org/movie/27205?imdb=tt1375666")
        assertEquals(27205, tmdb?.tmdbId)
        assertEquals("tt1375666", tmdb?.imdbId)
        assertEquals("movie", tmdb?.type)
        val imdb = TmdbUrlParser.parseTitleUrl("https://www.imdb.com/title/tt1375666/?tmdb=27205&type=movie")
        assertEquals(27205, imdb?.tmdbId)
        assertEquals("tt1375666", imdb?.imdbId)
        val bare = TmdbUrlParser.parseTitleUrl("https://www.imdb.com/title/tt1375666/")
        assertEquals(null, bare?.tmdbId)
        assertEquals("tt1375666", bare?.imdbId)
    }

    @Test
    fun parseSeasonEpisode_readsBothForms() {
        assertEquals(
            1 to 2,
            TmdbUrlParser.parseSeasonEpisode("https://www.themoviedb.org/tv/1396/season/1/episode/2"),
        )
        assertEquals(
            1 to 2,
            TmdbUrlParser.parseSeasonEpisode("https://www.imdb.com/title/tt0903747/season/1/episode/2/"),
        )
        assertEquals(-1 to -1, TmdbUrlParser.parseSeasonEpisode("https://www.themoviedb.org/movie/27205"))
    }
}
