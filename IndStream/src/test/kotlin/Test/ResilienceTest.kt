package Test

import com.indstream.HealthMonitor
import com.indstream.ImdbService
import com.indstream.OneTouchTvSource
import com.indstream.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the resilience behaviour of the metadata layer: parsing, fallbacks and the server breaker. */
class ResilienceTest {

    @Test
    fun tmdb_seasons_are_parsed_and_specials_dropped() {
        val json = """{"seasons":[{"season_number":0},{"season_number":1},{"season_number":2}]}"""
        assertEquals(listOf(1, 2), TmdbService.parseSeasons(json))
        assertEquals(emptyList(), TmdbService.parseSeasons("not json"))
    }

    @Test
    fun tmdb_find_prefers_the_movie_entry_then_the_series_entry() {
        val both = """{"movie_results":[{"id":11}],"tv_results":[{"id":22}]}"""
        assertEquals(11 to "movie", TmdbService.parseFindResult(both))
        val tvOnly = """{"movie_results":[],"tv_results":[{"id":22}]}"""
        assertEquals(22 to "series", TmdbService.parseFindResult(tvOnly))
        assertNull(TmdbService.parseFindResult("""{"movie_results":[],"tv_results":[]}"""))
    }

    @Test
    fun imdb_metadata_without_releaseInfo_still_parses() {
        val json = """{"meta":{"name":"Some Title","description":"Plot","runtime":"120 min",
            "imdbRating":"7.4","poster":"https://img/p.jpg","genres":["Drama"],
            "cast":["Actor One","Actor Two"]}}"""
        val detail = ImdbService.parseCinemeta(json, "tt1234567")
        assertNotNull(detail)
        assertEquals("Some Title", detail.name)
        assertEquals(120, detail.runtime)
        assertEquals(7.4, detail.rating)
        assertEquals(2, detail.cast?.size)
        // The missing releaseInfo must not take the whole payload down.
        assertNull(detail.year)
    }

    @Test
    fun imdb_suggest_types_a_tv_episode_as_a_series() {
        val json = """{"d":[{"id":"tt1","l":"Ep","qid":"tvEpisode","y":2020}]}"""
        val hits = ImdbService.parseSuggest(json)
        assertEquals(1, hits.size)
        assertEquals("tv", hits.first().type)
    }

    @Test
    fun onetouchtv_does_not_match_a_title_that_normalises_to_nothing() {
        val hits = listOf(OneTouchTvSource.Hit("1", "日本語", "2020", "movie"))
        assertNull(OneTouchTvSource.matchTitle(hits, "Inception", 2010, "movie"))
    }

    @Test
    fun onetouchtv_matches_on_a_normalised_title() {
        val hits = listOf(OneTouchTvSource.Hit("1", "Inception", "2010", "movie"))
        assertNotNull(OneTouchTvSource.matchTitle(hits, "Inception", 2010, "movie"))
    }

    @Test
    fun health_monitor_records_failures_and_recovers_after_a_success() {
        HealthMonitor.resetTrips()
        repeat(5) { HealthMonitor.recordFailure("resilience-test-server") }
        assertTrue(!HealthMonitor.isHealthy("resilience-test-server"))
        HealthMonitor.recordSuccess("resilience-test-server")
        assertTrue(HealthMonitor.isHealthy("resilience-test-server"))
        HealthMonitor.resetTrips()
    }
}
