package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BingrTest {

    @Test
    fun bingr_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "bingr" }
        assertEquals("Bingr", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://bingr.one/", s.referer)
        assertEquals("https://bingr.one/watch/movie/{id}", s.movieUrl)
        assertEquals("https://bingr.one/watch/tv/{id}/{season}/{episode}", s.tvUrl)
        assertTrue(s.hasSubtitles)
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
    fun bingr_heightHintOf_labels() {
        assertEquals(1080, StreamEngine.heightHintOf("1080p"))
        assertEquals(2160, StreamEngine.heightHintOf("2160p"))
        assertEquals(2160, StreamEngine.heightHintOf("4K"))
        assertEquals(720, StreamEngine.heightHintOf("720p"))
        assertEquals(0, StreamEngine.heightHintOf("ORG"))
        assertEquals(0, StreamEngine.heightHintOf(""))
    }

    @Test
    fun bingr_payloadSubtitles_attachToStreams() {
        val json = """
            {"sources":[
                {"url":"https://cdn.example/master.m3u8","quality":"1080p","language":"Hindi",
                 "captions":[{"file":"/subs/hi.vtt","label":"Hindi"}]}
            ],
             "subtitles":[
                {"url":"https://sub.example/en.vtt","lang":"en"}
             ]}
        """.trimIndent()
        val streams = listOf(
            StreamEngine.RawStream(
                "bingr", "Bingr", "https://cdn.example/master.m3u8", true,
                referer = "https://bingr.one/",
            ),
        )
        val attached = StreamEngine.withJsonSubtitles(streams, org.json.JSONObject(json), "https://api.bingr.one")
        assertEquals(1, attached.size)
        assertTrue(
            attached[0].subtitles.any { it.url == "https://api.bingr.one/subs/hi.vtt" },
            "per-source captions must resolve against the api base",
        )
        assertTrue(
            attached[0].subtitles.any { it.url == "https://sub.example/en.vtt" },
            "top-level subtitles must be kept",
        )
    }
}
