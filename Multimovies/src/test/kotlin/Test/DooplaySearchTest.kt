package Test

import com.multimovies.extractDooplayNonce
import com.multimovies.parseDooplaySearchHits
import com.multimovies.upgradePosterUrl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the site live-search JSON parser: hit mapping, rating/year handling, error bodies, and nonce scraping. */
class DooplaySearchTest {

    private val payload = """
        {"809":{"title":"Avengers: Endgame","url":"https://multimovies.casa/movies/avengers-endgame/","img":"https://multimovies.casa/wp-content/uploads/2025/01/ulzhLuWrPK07P1YkdWQLZnQh1JL-90x135.jpg?wsr","extra":{"date":"2019","imdb":"8.4"}},
        "20795":{"title":"Stranger Things","url":"https://multimovies.casa/tvshows/stranger-things/","img":"https://image.tmdb.org/t/p/w92/cVxVGwHce6xnW8UaVUggaPXbmoE.jpg","extra":{"date":"2016","imdb":"8.6"}},
        "158559":{"title":"Tales Spin-off","url":"https://multimovies.casa/tvshows/tales-spin-off/","img":"https://multimovies.casa/wp-content/uploads/2025/11/poster-90x135.jpg?wsr","extra":{"date":"2026","imdb":false}}}
    """.trimIndent()

    @Test
    fun parsesHitsWithYearAndRating() {
        val hits = parseDooplaySearchHits(payload)

        assertEquals(3, hits.size)
        val first = hits.first { it.title == "Avengers: Endgame" }
        assertEquals("https://multimovies.casa/movies/avengers-endgame/", first.url)
        assertEquals("2019", first.year)
        assertEquals(8.4, first.rating)
    }

    @Test
    fun booleanImdbYieldsNullRating() {
        val hits = parseDooplaySearchHits(payload)

        assertNull(hits.first { it.title == "Tales Spin-off" }.rating)
    }

    @Test
    fun errorBodiesYieldEmptyList() {
        assertTrue(parseDooplaySearchHits("""{"error":"no_posts","title":"No results"}""").isEmpty())
        assertTrue(parseDooplaySearchHits("""{"error":"no_verify_nonce","title":"No data nonce"}""").isEmpty())
        assertTrue(parseDooplaySearchHits("").isEmpty())
        assertTrue(parseDooplaySearchHits(null).isEmpty())
        assertTrue(parseDooplaySearchHits("not json").isEmpty())
    }

    @Test
    fun skipsEntriesMissingTitleOrUrl() {
        val json = """{"1":{"title":"","url":"https://multimovies.casa/movies/x/","img":"i","extra":{"date":"2020","imdb":"7"}},
            "2":{"title":"No Url","url":"","img":"i","extra":{"date":"2020","imdb":"7"}},
            "3":{"title":"Kept","url":"https://multimovies.casa/movies/kept/","img":"i","extra":{"date":"2020","imdb":"7"}}}"""

        val hits = parseDooplaySearchHits(json)

        assertEquals(listOf("Kept"), hits.map { it.title })
    }

    @Test
    fun siteThumbsUpgradeToFullPosters() {
        val hits = parseDooplaySearchHits(payload)

        val site = upgradePosterUrl(hits.first { it.title == "Avengers: Endgame" }.poster)
        assertTrue(!site.orEmpty().contains("-90x135"))

        val tmdb = upgradePosterUrl(hits.first { it.title == "Stranger Things" }.poster)
        assertTrue(tmdb.orEmpty().contains("/original/"))
    }

    @Test
    fun extractsNonceFromHomepageScript() {
        val html = """<script id="live_search-js-extra">var dtGonza = {"api":"https://multimovies.casa\/wp-json\/dooplay\/search\/","glossary":"https://multimovies.casa\/wp-json\/dooplay\/glossary\/","nonce":"fe82d6f2ab","area":".live-search"};</script>"""

        assertEquals("fe82d6f2ab", extractDooplayNonce(html))
    }

    @Test
    fun nonceMissingYieldsNull() {
        assertNull(extractDooplayNonce("<html><body>no search block</body></html>"))
        assertNull(extractDooplayNonce(""))
    }
}
