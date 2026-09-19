package Test

import com.multimovies.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Guards TMDB detail runtime parsing: movie minutes and series episode minutes. */
class TmdbDetailMmTest {

    @Test
    fun detailTakesMovieRuntime() {
        val raw = """{"id":809,"title":"Avengers: Endgame","release_date":"2019-04-26",
            "runtime":181,"vote_average":8.4}"""
        assertEquals(181, TmdbService.parseTmdbDetail(raw, "movie")?.runtime)
    }

    @Test
    fun detailTakesFirstEpisodeRuntimeForSeries() {
        val raw = """{"id":20795,"name":"Stranger Things","first_air_date":"2016-07-15",
            "episode_run_time":[51],"vote_average":8.6}"""
        assertEquals(51, TmdbService.parseTmdbDetail(raw, "series")?.runtime)
    }

    @Test
    fun detailNullRuntimeWhenAbsent() {
        val raw = """{"id":1,"title":"No Runtime","vote_average":5.0}"""
        assertNull(TmdbService.parseTmdbDetail(raw, "movie")?.runtime)
        assertNull(TmdbService.parseTmdbDetail(null, "movie"))
    }
}
