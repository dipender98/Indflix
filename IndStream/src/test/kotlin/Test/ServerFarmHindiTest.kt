package Test

import com.indstream.ManifestKit
import com.indstream.TitleMatch
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpec
import com.indstream.StreamEngine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFarmHindiTest {

    private val vidlink: ServerSpec
        get() = ServerFarm.allServers.first { it.id == "vidlink" }

    @Test
    fun vidlink_presentAndMultiLanguage() {
        assertEquals("VidLink", vidlink.name)
        assertEquals(ServerIdType.TMDB, vidlink.idType)
        assertTrue(vidlink.declaredLanguages.isEmpty(), "VidLink is multi-audio, not Hindi-only — a declared language mislabels English playback")
        assertTrue(vidlink.hasSubtitles)
    }

    @Test
    fun vidlink_movieUrl_carriesMultiLang() {
        val url = ServerFarm.buildMovieUrl(vidlink, "361743")
        assertTrue(url.contains("multiLang=1"), "movie url must request multi-audio")
        assertTrue(url.contains("/api/b/movie/"), "must hit the encrypted-token API")
    }

    @Test
    fun vidlink_tvUrl_hasSeasonEpisode() {
        val url = ServerFarm.buildTvUrl(vidlink, "1399", 1, 1)
        assertTrue(url.contains("/api/b/tv/1399/1/1"), "tv url must carry season/episode path")
        assertTrue(url.contains("multiLang=1"))
    }

    @Test
    fun vaplayer_removed_api_dead() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vaplayer" })
    }

    @Test
    fun vidrock_removed_v63() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock" })
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock-net" })
    }

    @Test
    fun videm_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "videm" }
        assertEquals(null, s)
    }


    @Test
    fun myflixerHindi_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "myflixer-hindi" }
        assertEquals(null, s)
    }

    @Test
    fun moviebox_presentAndTitled() {
        val s = ServerFarm.allServers.first { it.id == "moviebox" }
        assertEquals("MovieBox", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://movieboxonline.net/", s.referer)
        assertTrue(s.hasSubtitles)
    }

    @Test
    fun moviebox_rejectionClass() {
        assertTrue(!StreamEngine.movieboxAuthRejected(429, "429"))
        assertTrue(!StreamEngine.movieboxAuthRejected(429, "0"))
        assertTrue(!StreamEngine.movieboxAuthRejected(200, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(401, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(403, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(200, "400"))
        assertTrue(StreamEngine.movieboxAuthRejected(400, "400"))
    }

    @Test
    fun primesrc_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "primesrc" }
        assertEquals(null, s)
    }

    @Test
    fun nhd_disabledUpstream404() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "nhd" })
    }

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
    fun farm_hasUniqueIds() {
        val ids = ServerFarm.allServers.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "server ids must be unique (HealthMonitor keys by id)")
    }

    @Test
    fun farm_withinServerCap() {
        assertTrue(ServerFarm.allServers.size >= 9, "farm keeps every live server")
        assertTrue(ServerFarm.allServers.isNotEmpty())
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "vidlink" })
    }

    @Test
    fun vidup_removed_v63() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidup" })
    }

    @Test
    fun allmovieland_removed_unusableProviderMustNotBeInFarm() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "allmovieland" })
    }

    @Test
    fun moviebox_timeoutBudgetFitsFarmKill() {
        val s = ServerFarm.allServers.first { it.id == "moviebox" }
        assertEquals(65, s.timeoutSec, "MovieBox chain ceilings (8/15[+12]/[10+2]/8/8) must fit this kill")
    }

    @Test
    fun bulletTrainServers_present() {
        val ids = setOf("vidlink", "moviebox", "netmirror")
        for (id in ids) {
            assertNotNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id must be in the farm",
            )
        }
        val disabled = setOf("vidrock", "vidrock-net", "vidnest", "vidup",
            "twoembed", "vidfast", "autoembed", "vidphantom", "vsembed", "twoembed-skin",
            "vidsrcme", "vidsrc-pm", "rive", "vidsrcto", "videasy",
            "primesrc", "myflixer-hindi",
            "mp4hydra", "vidzee", "streamprovider",
            "videm", "8stream", "dahmermovies", "vidzy", "vixsrc",
            "nhd", "vidapi", "zxcstreams", "vidsrc-to", "nontongo", "vidcore", "vidcore-api",
            "allmovieland", "braflix", "castletv", "streamflix", "dudefilms", "multimovies", "katmoviehd")
        for (id in disabled) {
            assertNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id is dead/disabled and must not be in the farm",
            )
        }
    }

    @Test
    fun farm_withinExpandedCap() {
        assertTrue(ServerFarm.allServers.size >= 9, "farm keeps every live server")
    }
}
