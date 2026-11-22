package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VidlyTest {

    @Test
    fun vidly_usesEmbedRoutes() {
        val s = ServerFarm.allServers.first { it.id == "vidly" }
        assertEquals("Vidly", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.hasSubtitles)
        assertEquals(
            "https://vidly.pro/embed/movie/27205",
            ServerFarm.buildMovieUrl(s, "27205"),
        )
        assertEquals(
            "https://vidly.pro/embed/tv/1399/1/1",
            ServerFarm.buildTvUrl(s, "1399", 1, 1),
        )
    }

    @Test
    fun vidlyStreams_unescapesAndDedupes() {
        val html = """
            <link rel="preload" href="https://img.example/media/a/index.m3u8?api_key=x">
            <script>self.__next_f.push([1,"https:\/\/img.example\/media\/a\/index.m3u8?api_key=x"])</script>
            <script>self.__next_f.push([1,"https:\/\/img.example\/media\/b\/master.m3u8"])</script>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://img.example/media/a/index.m3u8?api_key=x",
                "https://img.example/media/b/master.m3u8",
            ),
            StreamEngine.vidlyStreams(html),
        )
        assertTrue(StreamEngine.vidlyStreams("<html>no stream</html>").isEmpty())
    }
}
