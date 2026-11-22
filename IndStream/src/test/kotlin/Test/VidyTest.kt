package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VidyTest {

    @Test
    fun vidy_usesDocumentedTmdbEmbedRoutes() {
        val s = ServerFarm.allServers.first { it.id == "vidy" }
        assertEquals("Vidy", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(40, s.timeoutSec)
        assertEquals(
            "https://www.vidy.st/movie/315162?color=DC2626",
            ServerFarm.buildMovieUrl(s, "315162"),
        )
        assertEquals(
            "https://www.vidy.st/tv/1396/1/1?color=DC2626",
            ServerFarm.buildTvUrl(s, "1396", 1, 1),
        )
        assertEquals("https://www.vidy.st/", s.referer)
        assertTrue(s.supports4k)
        assertFalse(s.animeOnly)
    }
}
