package Test

import com.multimovies.extractImdbIdFromServerUrl
import com.multimovies.extractTmdbIdFromServerUrl
import com.multimovies.fixServerPlaceholder
import com.multimovies.parseV2SearchApi
import com.multimovies.parseV2WatchConfig
import com.multimovies.sanitizeV2Poster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the 2.0 site parsers: search API, inline servers, id extraction. */
class V2SearchMmTest {

    @Test
    fun apiParsesMoviesAndShows() {
        val raw = """{"results":[
            {"id":1,"title":"Sir","type":"movie","year":2018,"rating":7.1,"poster":"https://image.tmdb.org/t/p/w185/x.jpg","url":"/movie/sir"},
            {"id":2,"title":"Dark Desire","type":"tv","year":2020,"rating":0,"poster":"https://image.tmdb.org/t/p/w185/y.jpg","url":"/series/dark-desire-22708"},
            {"id":3,"name":"Tony Sirico","type":"person","url":"/person/231"}]}"""
        val hits = parseV2SearchApi(raw)
        assertEquals(2, hits.size)
        assertEquals("/movie/sir", hits.first { it.title == "Sir" }.url)
        assertEquals("movie", hits.first { it.title == "Sir" }.type)
    }

    @Test
    fun apiFallsBackToSplitArrays() {
        val raw = """{"movies":[{"id":1,"title":"Sirf Tum","type":"movie","year":1999,"url":"/movie/sirf-tum-131280"}],
            "tv_shows":[{"id":2,"title":"Maddam Sir","type":"tv","year":2020,"url":"/series/maddam-sir-154403"}]}"""
        val hits = parseV2SearchApi(raw)
        assertEquals(2, hits.size)
    }

    @Test
    fun apiEmptyOnBlank() {
        assertTrue(parseV2SearchApi(null).isEmpty())
        assertTrue(parseV2SearchApi("not json").isEmpty())
    }

    @Test
    fun watchConfigParsesServers() {
        val html = """<script>const watchConfig = {"titleId":177263,"initialServers":[
            {"name":"Cineverse","url":"https:\/\/rozgarlelo.modiplay.xyz\/embed\/tmdb\/movie?id=518497"},
            {"name":"GD mirror","url":"https:\/\/streams.iqsmartgames.com\/embed\/movie\/tt7142506?key=abc"}]};</script>"""
        val servers = parseV2WatchConfig(html)
        assertEquals(2, servers.size)
        assertEquals("Cineverse", servers[0].name)
    }

    @Test
    fun idsExtractFromServerUrls() {
        assertEquals("518497", extractTmdbIdFromServerUrl("https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=518497"))
        assertEquals("88640", extractTmdbIdFromServerUrl("https://bingr.one/watch/tv/88640/1/1"))
        assertEquals("tt7142506", extractImdbIdFromServerUrl("https://streams.iqsmartgames.com/embed/movie/tt7142506?key=abc"))
    }

    @Test
    fun placeholderFilled() {
        assertEquals(
            "https://vidsync.pro/embed/movie/518497",
            fixServerPlaceholder("https://vidsync.pro/embed/movie/{tmdbId}", "518497"),
        )
    }

    @Test
    fun posterFixesDoubledPrefix() {
        val fixed = sanitizeV2Poster("https://image.tmdb.org/t/p/w185https://www.themoviedb.org/t/p/w600/x.jpg")
        assertTrue(fixed.orEmpty().startsWith("https://www.themoviedb.org"))
    }
}
