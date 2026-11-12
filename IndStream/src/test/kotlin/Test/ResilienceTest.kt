package Test

import com.indstream.HealthMonitor
import com.indstream.ImdbService
import com.indstream.TmdbService
import com.indstream.mergeEpisodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun tmdb_season_episodes_parse_rating_and_runtime() {
        val json = """{"episodes":[
            {"episode_number":1,"name":"E1","air_date":"2008-01-20","runtime":59,"vote_average":8.5,"still_path":"/s1.jpg"},
            {"episode_number":2,"name":"E2","air_date":"","runtime":0,"vote_average":0}
        ]}"""
        val eps = TmdbService.parseSeasonEpisodes(json, 1)!!
        assertEquals(2, eps.size)
        assertEquals(1, eps[0].seasonNumber)
        assertEquals(8.5, eps[0].rating)
        assertEquals(59, eps[0].runtime)
        assertEquals("2008-01-20", eps[0].released)
        assertNull(eps[1].rating)
        assertNull(eps[1].runtime)
        assertNull(eps[1].released)
    }

    @Test
    fun merge_episodes_prefers_tmdb_and_keeps_ratings_and_runtime() {
        val tmdb = listOf(
            TmdbService.TmdbEpisode(1, 1, "Tmdb E1", "tmdb plot", "2008-01-20", "https://tmdb.jpg", 8.5, 59),
            TmdbService.TmdbEpisode(1, 2, "Tmdb E2", null, "2008-01-27", null, 9.0, 48),
        )
        val cine = listOf(
            TmdbService.TmdbEpisode(1, 1, "Cine E1", "cine plot", "2008-01-21", "https://cine.jpg", 7.7, null),
            TmdbService.TmdbEpisode(1, 2, "Cine E2", "cine plot 2", "2008-01-28", "https://cine2.jpg", 7.5, null),
            TmdbService.TmdbEpisode(1, 3, "Cine E3", "cine plot 3", "2008-02-03", "https://cine3.jpg", 6.5, null),
        )
        val merged = mergeEpisodes(tmdb, cine)
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), merged.map { it.seasonNumber to it.episodeNumber })
        assertEquals("Tmdb E1", merged[0].name)
        assertEquals("tmdb plot", merged[0].overview)
        assertEquals(8.5, merged[0].rating)
        assertEquals(59, merged[0].runtime)
        assertEquals("Tmdb E2", merged[1].name)
        assertEquals("cine plot 2", merged[1].overview)
        assertEquals("https://cine2.jpg", merged[1].thumbnail)
        assertEquals(9.0, merged[1].rating)
        assertEquals(48, merged[1].runtime)
        assertEquals("Cine E3", merged[2].name)
        assertEquals(6.5, merged[2].rating)
        assertNull(merged[2].runtime)
    }

    @Test
    fun merge_episodes_falls_back_to_cinemeta_when_tmdb_is_empty() {
        val cine = listOf(
            TmdbService.TmdbEpisode(1, 1, "Cine E1", "plot", "2008-01-21", "https://t.jpg", 7.7),
        )
        val merged = mergeEpisodes(emptyList(), cine)
        assertEquals(1, merged.size)
        assertEquals("Cine E1", merged[0].name)
        assertEquals(7.7, merged[0].rating)
        assertTrue(mergeEpisodes(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun merge_episodes_sorts_by_season_and_episode() {
        val merged = mergeEpisodes(listOf(
            TmdbService.TmdbEpisode(2, 1, "S2E1"),
            TmdbService.TmdbEpisode(1, 2, "S1E2"),
            TmdbService.TmdbEpisode(1, 1, "S1E1"),
        ), emptyList())
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), merged.map { it.seasonNumber to it.episodeNumber })
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
    fun health_monitor_records_failures_and_recovers_after_a_success() {
        HealthMonitor.resetTrips()
        repeat(5) { HealthMonitor.recordFailure("resilience-test-server") }
        assertTrue(!HealthMonitor.isHealthy("resilience-test-server"))
        HealthMonitor.recordSuccess("resilience-test-server")
        assertTrue(HealthMonitor.isHealthy("resilience-test-server"))
        HealthMonitor.resetTrips()
    }
}
