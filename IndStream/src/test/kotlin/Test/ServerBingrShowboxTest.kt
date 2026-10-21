package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerBingrShowboxTest {

    @Test
    fun bingr_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "bingr" }
        assertEquals("Bingr", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://bingr.one/", s.referer)
        assertEquals("https://api.bingr.one/api/stream", s.movieUrl)
        assertEquals("https://api.bingr.one/api/stream", s.tvUrl)
    }

    @Test
    fun bingr_servers_includeIndian() {
        assertTrue(StreamEngine.BINGR_SRVS.contains("s62"), "IN-flagged srv must be cascaded")
        assertTrue(StreamEngine.BINGR_SRVS.contains("s40"), "s40 drives the aphelion-tv TV path")
        assertTrue(StreamEngine.BINGR_SRVS.isNotEmpty())
    }

    @Test
    fun bingrBody_movieYears_nestedQuery() {
        val body = StreamEngine.bingrBody("s40", "movie", "27205", "Inception", "2010", 1, 1)
        assertEquals("s40", body["srv"])
        assertEquals("movie", body["t"])
        assertEquals("27205", body["id"])
        @Suppress("UNCHECKED_CAST")
        val query = body["query"] as Map<String, Any?>
        assertEquals("Inception", query["title"])
        assertEquals("2010", query["year"])
        assertTrue(!query.containsKey("season") && !query.containsKey("episode"))
    }

    @Test
    fun bingrBody_tv_putsSeasonEpisodeInsideQuery() {
        val body = StreamEngine.bingrBody("s62", "tv", "1399", "Game of Thrones", "2011", 3, 7)
        assertEquals("tv", body["t"])
        @Suppress("UNCHECKED_CAST")
        val query = body["query"] as Map<String, Any?>
        assertEquals(3, query["season"])
        assertEquals(7, query["episode"])
        assertTrue(!body.containsKey("season") && !body.containsKey("episode"))
    }

    @Test
    fun bingrSources_parsesAndFilters() {
        val json = """
            {"sources":[
                {"url":"https://cdn.example/master.m3u8","quality":"1080p","language":"Hindi"},
                {"url":"not-a-url","quality":"720p"},
                {"url":"https://cdn.example/2160.mp4","label":"4K"}
            ]}
        """.trimIndent()
        val src = StreamEngine.bingrSources(json)
        assertEquals(2, src.size)
        assertEquals("https://cdn.example/master.m3u8", src[0].url)
        assertEquals("Hindi", src[0].language)
        assertEquals("4K", src[1].label)
        assertTrue(StreamEngine.bingrSources("{\"error\":\"x\"}").isEmpty())
        assertTrue(StreamEngine.bingrSources("not json").isEmpty())
    }

    @Test
    fun heightHintOf_labels() {
        assertEquals(1080, StreamEngine.heightHintOf("1080p"))
        assertEquals(2160, StreamEngine.heightHintOf("2160p"))
        assertEquals(2160, StreamEngine.heightHintOf("4K"))
        assertEquals(720, StreamEngine.heightHintOf("720p"))
        assertEquals(0, StreamEngine.heightHintOf("ORG"))
        assertEquals(0, StreamEngine.heightHintOf(""))
    }

    @Test
    fun bingr_present_removedServersAbsent() {
        val gone = setOf(
            "primesrc", "vidrock", "videm", "vixsrc",
            "showbox", "vidspark", "hdmovie2", "olamovies", "filmycab",
        )
        for (id in gone) assertNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must be absent")
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "bingr" })
    }
}
