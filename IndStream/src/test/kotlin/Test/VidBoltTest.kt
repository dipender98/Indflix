package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VidBoltTest {

    @Test
    fun vidbolt_usesJsonResolverRoute() {
        val s = ServerFarm.allServers.first { it.id == "vidbolt" }
        assertEquals("VidBolt", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.isJsonApi)
        assertTrue(s.hasSubtitles)
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=movie&id=27205",
            ServerFarm.buildMovieUrl(s, "27205"),
        )
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=tv&id=1399&season=1&episode=1",
            ServerFarm.buildTvUrl(s, "1399", 1, 1),
        )
    }

    @Test
    fun vidboltSources_parsesSources() {
        val json = """
            {"stream":{"server":"orion","sources":[
              {"url":"https://cdn.example/master.m3u8","kind":"hls","quality":"1080p",
               "language":"Hindi","master":"#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nv.m3u8"},
              {"url":"not-a-url","quality":"720p"},
              {"url":"https://cdn.example/2160.mp4","kind":"mp4","quality":"4K","label":"UHD"}
            ]}}
        """.trimIndent()
        val src = StreamEngine.vidboltSources(json)
        assertEquals(2, src.size)
        assertEquals("https://cdn.example/master.m3u8", src[0].url)
        assertEquals("hls", src[0].kind)
        assertEquals("1080p", src[0].quality)
        assertEquals("Hindi", src[0].language)
        assertTrue(src[0].master.contains("#EXTM3U"))
        assertEquals("4K", src[1].quality)
        assertEquals("UHD", src[1].label)
        assertTrue(StreamEngine.vidboltSources("""{"stream":{"sources":[]}}""").isEmpty())
        assertTrue(StreamEngine.vidboltSources("nope").isEmpty())
    }

    @Test
    fun vidboltSources_keepsInlineDashManifest() {
        val json = """
            {"stream":{"server":"orion","sources":[
              {"url":"https://cdn.example/dash.mpd","kind":"dash","quality":"1080p",
               "master":"<MPD><Period><AdaptationSet mimeType=\"audio/mp4\" xml:lang=\"hi\"><Representation codecs=\"mp4a.40.2\"/></AdaptationSet></Period></MPD>"}
            ]}}
        """.trimIndent()
        val src = StreamEngine.vidboltSources(json)
        assertEquals(1, src.size)
        assertEquals("dash", src[0].kind)
        assertTrue(src[0].master.contains("<MPD"))
        assertEquals("Hindi", StreamEngine.manifestAudioLabel(src[0].master))
    }
}
