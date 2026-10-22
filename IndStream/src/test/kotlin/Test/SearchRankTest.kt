package Test

import com.indstream.SearchRank
import com.indstream.TmdbService
import com.indstream.TmdbUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun rank_exactMatchBeatsMorePopularFranchiseVariants() {
        val main = TmdbService.TmdbItem(
            tmdbId = 1, imdbId = null, type = "tv", name = "Mirzapur", year = "2018", poster = null, rating = null,
            originalLanguage = "hi", originCountry = listOf("IN"),
            voteCount = 100, popularity = 10.0,
        )
        val regional = main.copy(
            tmdbId = 2, name = "Mirzapur Marathi", originalLanguage = "mr",
            voteCount = 5000, popularity = 60.0,
        )
        val spinOff = main.copy(
            tmdbId = 3, name = "Mirzapur OTT", originalLanguage = "hi",
            voteCount = 8000, popularity = 70.0,
        )
        val season = main.copy(tmdbId = 4, name = "Mirzapur Season 2", voteCount = 9000)
        val ranked = SearchRank.rank("mirzapur", listOf(season, spinOff, regional, main))
        assertEquals(1, ranked.first().tmdbId)
        assertTrue(SearchRank.score("mirzapur", main).tier >= 98.0)
    }

    @Test
    fun score_exactAcrossScriptsSpellingAndPunctuation() {
        val item = TmdbService.TmdbItem(
            tmdbId = 2, imdbId = null, type = "tv", name = "Ramayan", year = "2002", poster = null, rating = null,
            originalName = "रामायण", originalLanguage = "hi", originCountry = listOf("IN"),
        )
        assertEquals(100.0, SearchRank.score("ramayan", item).tier)
        assertEquals(100.0, SearchRank.score("Raamayan", item).tier)
        assertEquals(100.0, SearchRank.score("रामायण", item).tier)
        assertEquals(SearchRank.normalizeKey("Ramayan"), SearchRank.normalizeKey("रामायण"))
    }

    @Test
    fun recallQueries_latinAddsNativeScriptVariant() {
        val queries = SearchRank.recallQueries("bigg boss", max = 3)
        assertTrue(queries.contains("bigg boss"))
        assertTrue(queries.any { it.contains("बिग बॉस") || it.contains("बिग बोस") })
    }

    @Test
    fun queryLocale_detectsScriptBasedLanguageAcrossLangs() {
        assertEquals("hi", SearchRank.queryLocale("रामायण")?.language)
        assertEquals("IN", SearchRank.queryLocale("रामायण")?.country)
        assertEquals("ta", SearchRank.queryLocale("விக்ரம்")?.language)
        assertTrue(SearchRank.queryLocale("தமிழ்")?.language == "ta")
        assertEquals("bn", SearchRank.queryLocale("বিগ বোস")?.language)
        assertEquals("ur", SearchRank.queryLocale("پٹاخ")?.language)
        assertTrue(SearchRank.queryLocale("Dune") == null)
        assertTrue(SearchRank.queryLocale("Dune: Part Two 2021") == null)
    }

    @Test
    fun score_plainLatinQueryHasNoLanguageOrRegionBias() {
        val item = TmdbService.TmdbItem(
            tmdbId = 5, imdbId = null, type = "movie", name = "Dune", year = "2021", poster = null, rating = null,
            originalLanguage = "en", originCountry = listOf("US"),
        )
        val parts = SearchRank.score("dune", item).parts
        assertTrue(parts.none { it.label == "lang" })
        assertTrue(parts.none { it.label == "region" })
    }

    @Test
    fun score_languageKeywordInQueryBoostsOnlyMatchingLanguage() {
        val hindiItem = TmdbService.TmdbItem(
            tmdbId = 6, imdbId = null, type = "tv", name = "Mirzapur", year = "2018", poster = null, rating = null,
            originalLanguage = "hi", originCountry = listOf("IN"),
        )
        val otherItem = hindiItem.copy(
            tmdbId = 7, imdbId = "tt0000001", originalLanguage = "de", originCountry = listOf("DE"),
        )
        assertEquals("hi", SearchRank.queryLocale("mirzapur hindi")?.language)
        assertTrue(SearchRank.score("mirzapur hindi", hindiItem).parts.any { it.label == "lang" })
        assertTrue(SearchRank.score("mirzapur hindi", hindiItem).parts.any { it.label == "region" })
        assertTrue(SearchRank.score("mirzapur hindi", otherItem).parts.none { it.label == "lang" })
        assertTrue(SearchRank.score("mirzapur indian", hindiItem).parts.any { it.label == "region" })
    }

    @Test
    fun rank_nativeScriptQueryPrefersNativeTitleOverPopularForeignTitle() {
        val nativeItem = TmdbService.TmdbItem(
            tmdbId = 8, imdbId = null, type = "movie", name = "নবাগত", year = "2020", poster = null, rating = null,
            originalLanguage = "bn", originCountry = listOf("IN", "BD"), voteCount = 12,
        )
        val popularItem = TmdbService.TmdbItem(
            tmdbId = 9, imdbId = null, type = "movie", name = "Dune", year = "2021", poster = null, rating = null,
            originalLanguage = "en", originCountry = listOf("US"), voteCount = 9000, popularity = 99.0,
        )
        val ranked = SearchRank.rank("নবাগত", listOf(popularItem, nativeItem))
        assertEquals(8, ranked.first().tmdbId)
        assertEquals(100.0, SearchRank.score("নবাগত", nativeItem).tier)
    }

    @Test
    fun normalizeKey_preservesIndicMatrasButDeaccentsLatin() {
        assertTrue(SearchRank.normalizeKey("மா") != SearchRank.normalizeKey("மி"))
        assertTrue(SearchRank.normalizeKey("কি") != SearchRank.normalizeKey("কে"))
        assertEquals(
            SearchRank.normalizeKey("Amelie"),
            SearchRank.normalizeKey("Amélie"),
        )
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
