package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetMirrorTest {

    @Test
    fun netmirror_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "netmirror" }
        assertEquals("NetMirror", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.declaredLanguages.isEmpty(), "default-ladder audio undeclared — never guessed")
        assertTrue(s.isJsonApi, "embed-tmdb JSON API")
        assertTrue(s.hasSubtitles, "API captions carry language (incl. Bengali/Punjabi)")
        assertEquals("https://net78.cc/", s.referer)
        assertEquals(50, s.timeoutSec, "netmirror farm kill must sit above the 24s dub fan-out chain")
        assertFalse(s.supportsImdb("movie"))
        assertFalse(s.supportsImdb("tv"))
        val m = ServerFarm.buildMovieUrl(s, "27205")
        assertEquals("https://net78.cc/api/embed-tmdb/27205?type=movie", m)
        val tv = ServerFarm.buildTvUrl(s, "1399", 1, 1)
        assertEquals("https://net78.cc/api/embed-tmdb/1399?type=tv&se=1&ep=1", tv)
    }

    @Test
    fun netmirrorDubLabel_policyOriginalPlusEnglishPlusIndian() {
        assertEquals("Original", StreamEngine.netmirrorDubLabel("Japanese dub", isOriginal = true))
        assertEquals("Original", StreamEngine.netmirrorDubLabel("Korean", isOriginal = true))
        assertEquals("English", StreamEngine.netmirrorDubLabel("English dub", isOriginal = false))
        assertEquals("Hindi", StreamEngine.netmirrorDubLabel("Hindi dub", isOriginal = false))
        assertEquals("Tamil", StreamEngine.netmirrorDubLabel("Tamil dub", isOriginal = false))
        assertEquals("Telugu", StreamEngine.netmirrorDubLabel("Telugu dub", isOriginal = false))
        assertEquals("Bengali", StreamEngine.netmirrorDubLabel("Bengali dub", isOriginal = false))
        assertEquals("Gujarati", StreamEngine.netmirrorDubLabel("Gujarati dub", isOriginal = false))
        // Any declared language is evidence, not just English + the 9 Indian dubs.
        assertEquals("Russian", StreamEngine.netmirrorDubLabel("Russian dub", isOriginal = false))
        assertEquals("French", StreamEngine.netmirrorDubLabel("French dub", isOriginal = false))
        assertEquals("Japanese", StreamEngine.netmirrorDubLabel("Japanese", isOriginal = false))
        assertEquals("Arabic", StreamEngine.netmirrorDubLabel("Arabic sub", isOriginal = false))
        // Undeclared junk / unparseable labels stay null.
        assertNull(StreamEngine.netmirrorDubLabel("ptbr dub", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel("esla dub", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel("Default", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel(null, isOriginal = false))
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
        val attached = StreamEngine.withJsonSubtitles(streams, org.json.JSONObject(json), "https://net78.cc")
        assertEquals(1, attached.size)
        assertTrue(
            attached[0].subtitles.any { it.url.contains("/api/proxy/video") },
            "proxy-style caption urls must be accepted",
        )
    }
}
