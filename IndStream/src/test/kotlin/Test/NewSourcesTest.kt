package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the video-lane expansion: Vidrift, Rivestream, Movy. */
class NewSourcesTest {

    @Test
    fun farm_presentAndTmdbKeyed() {
        for (id in listOf("vidrift", "rivestream", "movy")) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertTrue(s != null, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s!!.idType)
        }
    }

    @Test
    fun rivestream_secretKey_pinnedVectors() {
        // Pinned against the live API (TMDB 550 / 1396 / 27205 / 603 / 1399).
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
        // Deterministic KDF vector: seed + mediaId + XOR ciphertext.
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
}
