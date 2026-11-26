package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SevenMoviesTest {

    @Test
    fun sevenmovies_registry_presentAndTmdbKeyed() {
        val spec = ServerFarm.allServers.first { it.id == "7movies" }
        assertEquals("7Movies", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://7movies.ws/", spec.referer)
        assertEquals(ServerSpeed.FAST, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertTrue(spec.supports4k)
        assertTrue(ServerFarm.buildMovieUrl(spec, "27205").contains("id=27205"))
        assertTrue(ServerFarm.buildTvUrl(spec, "66732", 1, 2).contains("season=1&episode=2"))
    }

    @Test
    fun sevenmovies_rows_parseAndSkipLowQuality() {
        val json = """{"rows":[
            {"file":"Film.2024.2160p.HEVC.Hindi.mkv","quality":"2160p","size":"10 GB","languages":["Hindi","English"],"token":"a"},
            {"file":"Film.2024.480p.mkv","quality":"480p","size":"1 GB","languages":["Hindi"],"token":"b"}
        ]}"""
        val rows = StreamEngine.apiDownloadRows(json)
        assertEquals(1, rows.size)
        assertEquals(2160, rows[0].height)
        assertEquals("10 GB", rows[0].size)
        assertEquals("Hindi, English", rows[0].languages)
        assertTrue(StreamEngine.apiDownloadRows("""{"rows":[]}""").isEmpty())
        assertTrue(StreamEngine.apiDownloadRows("not json").isEmpty())
    }
}
