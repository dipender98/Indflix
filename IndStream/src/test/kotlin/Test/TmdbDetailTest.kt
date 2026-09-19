package Test

import com.indstream.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Guards TMDB detail parsing: runtime minutes for movies and series. */
class TmdbDetailTest {

    @Test
    fun detailTakesMovieRuntime() {
        val raw = """{"id":27205,"title":"Inception","release_date":"2010-07-15",
            "runtime":148,"vote_average":8.4}"""
        assertEquals(148, TmdbService.parseTmdbDetail(raw, "movie")?.runtime)
    }

    @Test
    fun detailTakesFirstEpisodeRuntimeForSeries() {
        val raw = """{"id":1396,"name":"Breaking Bad","first_air_date":"2008-01-20",
            "episode_run_time":[49,51],"vote_average":9.0}"""
        assertEquals(49, TmdbService.parseTmdbDetail(raw, "series")?.runtime)
    }

    @Test
    fun detailNullRuntimeWhenAbsent() {
        val raw = """{"id":1,"title":"No Runtime","vote_average":5.0}"""
        assertNull(TmdbService.parseTmdbDetail(raw, "movie")?.runtime)
        assertNull(TmdbService.parseTmdbDetail(null, "movie"))
    }
}
