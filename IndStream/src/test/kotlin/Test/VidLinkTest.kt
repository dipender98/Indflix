package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VidLinkTest {

    @Test
    fun vidlink_presentAndMultiLanguage() {
        val vidlink = ServerFarm.allServers.first { it.id == "vidlink" }
        assertEquals("VidLink", vidlink.name)
        assertEquals(ServerIdType.TMDB, vidlink.idType)
        assertTrue(vidlink.declaredLanguages.isEmpty(), "VidLink is multi-audio, not Hindi-only — a declared language mislabels English playback")
        assertTrue(vidlink.hasSubtitles)
        assertTrue(vidlink.supportsImdb("movie"))
        assertFalse(vidlink.supportsImdb("tv"))
    }

    @Test
    fun vidlink_movieUrl_carriesMultiLang() {
        val vidlink = ServerFarm.allServers.first { it.id == "vidlink" }
        val url = ServerFarm.buildMovieUrl(vidlink, "361743")
        assertTrue(url.contains("multiLang=1"), "movie url must request multi-audio")
        assertTrue(url.contains("/api/b/movie/"), "must hit the encrypted-token API")
    }

    @Test
    fun vidlink_tvUrl_hasSeasonEpisode() {
        val vidlink = ServerFarm.allServers.first { it.id == "vidlink" }
        val url = ServerFarm.buildTvUrl(vidlink, "1399", 1, 1)
        assertTrue(url.contains("/api/b/tv/1399/1/1"), "tv url must carry season/episode path")
        assertTrue(url.contains("multiLang=1"))
    }
}
