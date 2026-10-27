package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import com.indstream.SubtitleKit
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

    @Test
    fun bingr_spec_marksSubtitles() {
        assertTrue(ServerFarm.allServers.first { it.id == "bingr" }.hasSubtitles)
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

    @Test
    fun netmirror_captions_attachViaProxyUrls() {
        val json = """
            {"ok":true,"streams":[
                {"url":"https://cdn.example/video.mp4","resolution":1080}
            ],
             "captions":[
                {"lang":"ar","name":"العربية","url":"/api/proxy/video?url=https%3A%2F%2Fx%2Fa.srt"}
             ]}
        """.trimIndent()
        val streams = listOf(
            StreamEngine.RawStream(
                "netmirror", "NetMirror", "https://cdn.example/video.mp4", false,
                referer = "https://videodownloader.site/",
            ),
        )
        val attached = StreamEngine.withJsonSubtitles(streams, org.json.JSONObject(json), "https://net79.cc")
        assertEquals(1, attached.size)
        assertTrue(
            attached[0].subtitles.any { it.url.contains("/api/proxy/video") },
            "proxy-style caption urls must be accepted",
        )
    }

    @Test
    fun braflix_endpointSubtitles_areSplitAndAttachable() {
        val api = org.json.JSONObject(
            """
            {"status":"ready","result":{"file":{
                "stream":"/dash-proxy?url=x.mpd",
                "subtitles":"/api/subs/abc123"
            }}}
            """.trimIndent(),
        )
        val (cleaned, endpoints) = SubtitleKit.splitSubtitleEndpoints(listOf(api))
        assertEquals(listOf("/api/subs/abc123"), endpoints, "endpoint string must be detected")
        val tracks = SubtitleKit.fromJson(org.json.JSONArray().apply { cleaned.forEach { put(it) } }, "https://braflix.dev")
        assertTrue(tracks.none { it.url.contains("/api/subs/") }, "endpoint string must not become a track")

        val subsEndpoint = org.json.JSONArray(
            """
            [{"src":"/sub/abc/aHR0cHM6Ly9jYWNoZS52ZHJrLnNpdGUvdjEvdnR0L21vdmllLzI3MjA1L0VuZ2xpc2gudnR0.vtt","label":"English","lang":"en"}]
            """.trimIndent(),
        )
        val attached = StreamEngine.withJsonSubtitles(
            listOf(StreamEngine.RawStream("braflix", "Braflix", "https://braflix.dev/dash-proxy?url=x.mpd", true, isDash = true)),
            subsEndpoint,
            "https://braflix.dev",
        )
        assertTrue(attached[0].subtitles.any { it.url.startsWith("https://braflix.dev/sub/abc/") }, "braflix sub src must resolve")
        assertTrue(attached[0].subtitles.any { it.language == "English" }, "braflix label must become language")
    }
}
