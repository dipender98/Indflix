package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewSourcesTest {

    @Test
    fun farm_presentAndTmdbKeyed() {
        for (id in listOf("vidrift", "rivestream", "movy", "vidspark", "braflix")) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertTrue(s != null, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s!!.idType)
        }
    }

    @Test
    fun downloadFarm_presentAndTmdbKeyed() {
        for (id in listOf("hindmoviez", "hdhub4u", "moviesdrive", "dudefilms", "filmycab")) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertTrue(s != null, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s!!.idType)
        }
    }

    @Test
    fun titleHits_parsesSearchMarkup() {
        val html = "<h2 class=\"entry-title\"><a href=\"/jawan-2023/\">Jawan 2023</a></h2>" +
            "<a href=\"/2026/07/31/film/\"><p>Film (2026)</p></a>" +
            "<h3 class=\"simple-grid-grid-post-title\"><a href=\"/dude-film/\">Dude Film</a></h3>"
        val hits = StreamEngine.titleHits(html, "https://site.example")
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.first == "https://site.example/jawan-2023/" })
        assertTrue(hits.any { it.second == "Film (2026)" })
    }

    @Test
    fun ariaHits_parsesSearchMarkup() {
        val html = "<a href=\"/jawan-2023-hindi-webrip-full-movie/\" " +
            "aria-label=\"Jawan (2023) WEB-DL Hindi | Full Movie\">x</a>" +
            "<a href=\"/category/action/\" aria-label=\"Action\">x</a>"
        val hits = StreamEngine.ariaHits(html, "https://site.example")
        assertEquals(1, hits.size)
        assertEquals("https://site.example/jawan-2023-hindi-webrip-full-movie/", hits[0].first)
    }

    @Test
    fun headingAnchorHits_usesNearestHeading() {
        val html = "<h3>Jawan 2023 720p [1.4GB]</h3>" +
            "<p><a href=\"https://links.example/1\">Download Links</a></p>"
        val hits = StreamEngine.headingAnchorHits(html, "https://site.example", Regex("links\\.example"))
        assertEquals(1, hits.size)
        assertEquals("Jawan 2023 720p [1.4GB]", hits[0].second)
    }

    @Test
    fun headingAnchorHits_mergesEpisodeHeadingWithFileLabel() {
        val html = "<h5>Show S01E02 720p [1GB]</h5>" +
            "<h5><a href=\"https://hubcloud.ist/drive/search-recover.php?from_ac=x\">720p 10bit [1GB]</a></h5>"
        val hits = StreamEngine.headingAnchorHits(html, "https://site.example", Regex("search-recover"))
        assertEquals(1, hits.size)
        assertTrue(hits[0].second.contains("S01E02"))
        assertTrue(hits[0].second.contains("720p"))
    }

    @Test
    fun fileHostLinks_keepsFilesAndSkipsGatewayNoise() {
        val html = "<a href=\"https://hubcloud.ist/drive/x\">Cloud</a>" +
            "<a href=\"https://share.example/0:/film.mkv\">Direct</a>" +
            "<a href=\"https://hubcloud.ist/tg/go?id=x\">Telegram</a>" +
            "<a href=\"https://pixel.hubcloud.ist/x\">Pixel</a>" +
            "<a href=\"https://pixeldrain.dev/u/AbC123\">PixelDrain</a>" +
            "<a href=\"https://host.example/hubcloud.php?token=x\">Token</a>"
        val hits = StreamEngine.fileHostLinks(html, "https://site.example")
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.first.endsWith(".mkv") })
        assertTrue(hits.any { it.first.contains("pixeldrain") })
    }

    @Test
    fun pixeldrainDirect_preservesHostAndConvertsPageUrls() {
        assertEquals(
            "https://pixeldrain.dev/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.dev/u/AbC123"),
        )
        assertEquals(
            "https://pixeldrain.com/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.com/api/file/AbC123"),
        )
        assertNull(StreamEngine.pixeldrainDirect("https://share.example/u/AbC123"))
    }

    @Test
    fun qualityText_and_fileNameMatches() {
        assertEquals("720p", StreamEngine.qualityText("720p 10Bit HEVC [1GB]"))
        assertEquals("4K", StreamEngine.qualityText("4K [2160p SDR]"))
        assertTrue(StreamEngine.fileNameMatches("Jawan", "2023", "Jawan.Extended.Cut.2023.720p.mkv"))
        assertTrue(!StreamEngine.fileNameMatches("Jawan", "2023", "Kuriyan.Jawan.Bapu.2025.720p.mkv"))
        assertTrue(StreamEngine.fileNameMatches("Dune Part Two", "2024", "Dune.Part.Two.2024.1080p.mkv"))
    }

    @Test
    fun rivestream_secretKey_pinnedVectors() {
        assertEquals("NDQzM2UwYzI=", StreamEngine.rivestreamSecretKey("550"))
        assertEquals("Nzg3ZmU5YTI=", StreamEngine.rivestreamSecretKey("1396"))
        assertEquals("LTZmMzMzMjM5", StreamEngine.rivestreamSecretKey("27205"))
        assertEquals("NGExYjUxOGY=", StreamEngine.rivestreamSecretKey("603"))
        assertEquals("LTIwYjFkMjc5", StreamEngine.rivestreamSecretKey("1399"))
    }

    @Test
    fun rivestream_parseSources() {
        val json = """
            {"data":{"sources":[
              {"quality":"720p | English","url":"https://img1.hscow.com/hls/78164859/720/index_287.m3u8?sig=a","source":"Citadel","format":"hls"},
              {"quality":"480p | English","url":"https://img1.hscow.com/hls_mps/78164859/480/index_287.m3u8?sig=b","source":"Citadel","format":"hls"}
            ],"captions":[{"file":"https://img1.fhxod.com/subtitle/78164859/English.vtt","label":"English - Citadel"}]}}
        """.trimIndent()
        val out = StreamEngine.rivestreamSources(json)
        assertEquals(2, out.size)
        assertEquals("720p | English", out[0].quality)
        assertTrue(out[0].url.startsWith("https://img1.hscow.com/"))
        assertTrue(StreamEngine.rivestreamSources("""{"data":{}}""").isEmpty())
    }

    @Test
    fun rivestream_servicesAudioProxy() {
        assertEquals(listOf("citadel", "apogee"), StreamEngine.rivestreamServicesFrom("""{"data":["citadel","apogee"]}"""))
        assertTrue(StreamEngine.rivestreamServicesFrom("not json").isEmpty())
        assertEquals("Hindi", StreamEngine.rivestreamAudio("720p | Hindi"))
        assertEquals("", StreamEngine.rivestreamAudio("1080p"))
        val proxy = "https://proxy.valhallastream.com/m3u8?url=https%3A%2F%2Fx&headers=" +
            "%7B%22Origin%22%3A%22https%3A%2F%2Fbingr.one%22%2C%22Referer%22%3A%22https%3A%2F%2Fbingr.one%2F%22%7D"
        val h = StreamEngine.rivestreamProxyHeaders(proxy)
        assertEquals("https://bingr.one/", h["Referer"])
        assertEquals("https://bingr.one", h["Origin"])
        assertTrue(StreamEngine.rivestreamProxyHeaders("https://x.example/a.m3u8").isEmpty())
    }

    @Test
    fun movy_decrypt_pinnedVector() {
        val seed = "59741865.p3--9ruju1S0dBTIaxZNN3"
        val cipher = "ZlQsjtYKRCJxWvBYqQ1a3hjSGQUgVNET0rce-3o6STkSpCAwbZQxmQ0bUQzMB6ub5oU" +
            "-OhcTSlmaoJvBS-VUZsLH5h_ZctHyUI2kZEA4C58fIVfFjQ"
        val expected = """{"sources":[{"quality":"1080p","url":"https://x.example/a.m3u8"}],"subtitles":[]}"""
        assertEquals(expected, StreamEngine.movyDecrypt(cipher, seed, 550))
        assertNull(StreamEngine.movyDecrypt(cipher, "wrong-seed", 550))
    }

    @Test
    fun movy_parseSources() {
        val out = StreamEngine.movySources("""{"sources":[{"quality":"Hindi","url":"https://x.example/h.m3u8"}],"subtitles":[]}""")
        assertEquals(1, out.size)
        assertEquals("Hindi", out[0].quality)
        assertTrue(StreamEngine.movySources("nope").isEmpty())
    }

    @Test
    fun vidrift_streams_pinnedConfig() {
        val html = "<script>var embedMeta = {\"tmdbId\":27205,\"provider\":\"orion\"," +
            "\"orionStreams\":[" +
            "{\"index\":0,\"url\":\"/api/orion/abc/master.m3u8\",\"type\":\"hls\",\"provider\":\"Orion\",\"name\":\"Orion A\u2022 Original\"}," +
            "{\"index\":1,\"url\":\"/api/orion/def/master.m3u8\",\"name\":\"Orion A\u2022 Hindi\"}" +
            "]};</script>"
        val out = StreamEngine.vidriftStreams(html)
        assertEquals(2, out.size)
        assertEquals("/api/orion/abc/master.m3u8" to "Original", out[0])
        assertEquals("/api/orion/def/master.m3u8" to "Hindi", out[1])
        assertTrue(StreamEngine.vidriftStreams("<html>no config</html>").isEmpty())
    }

    @Test
    fun vidspark_parseSources() {
        val json = """
            {"result":true,"type":"movie","sources":[
              {"file_code":"abc","url":"https://bx.netrocdn.site/hls2/1/master.m3u8?t=x","source":"vidora","quality":"1080p","tracks":[]},
              {"file_code":"def","url":"https://bx.netrocdn.site/hls2/2/master.m3u8?t=y","source":"vidora","quality":"720p","tracks":[]}
            ]}
        """.trimIndent()
        val out = StreamEngine.vidsparkSources(json)
        assertEquals(2, out.size)
        assertTrue(out[0].url.startsWith("https://bx.netrocdn.site/"))
        assertEquals("1080p", out[0].quality)
        assertTrue(StreamEngine.vidsparkSources("""{"result":true}""").isEmpty())
        assertTrue(StreamEngine.vidsparkSources("not json").isEmpty())
    }

    @Test
    fun braflix_parseSource() {
        val json = """
            {"status":"ready","result":{"token":"d0",
            "source":{"kind":"dash","quality":"720,480"},
            "file":{"playback":"dash","stream":"/dash-proxy?url=x&cookie=y"}}}
        """.trimIndent()
        val src = StreamEngine.braflixSource(json)
        assertTrue(src != null)
        assertEquals("/dash-proxy?url=x&cookie=y", src!!.stream)
        assertEquals("720,480", src.quality)
        assertNull(StreamEngine.braflixSource("""{"status":"pending"}"""))
        assertNull(StreamEngine.braflixSource("nope"))
    }
}
