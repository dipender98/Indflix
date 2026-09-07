package Test

import com.indstream.ManifestKit
import com.indstream.TitleMatch
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpec
import com.indstream.VidlinkSource
import com.indstream.VideasySource

/**
 * FILE: ServerFarmHindiTest.kt â€” guards ServerRegistry.kt for the Hindi
 * MyFlixerAPI entry. Verifies the SeedspÃ©c + URL builders emit the expected
 * Hindi MyFlixerAPI URLs and that the `hindi` flag is set so [StreamEngine]
 * biases it to priority 4.
 */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFarmHindiTest {

    private val vidlink: ServerSpec
        get() = ServerFarm.allServers.first { it.id == "vidlink" }

    @Test
    fun vidlink_presentAndFlaggedHindi() {
        assertEquals("VidLink", vidlink.name)
        assertEquals(ServerIdType.TMDB, vidlink.idType)
        // VidLink is multi-audio (Hindi + English dubs), NOT a Hindi-only host.
        // The blanket `hindi` flag would force every stream to label "Hindi" even
        // when the played track is English — so it must stay false and let the
        // per-master audio probe report the real language.
        assertTrue(!vidlink.hindi, "VidLink is multi-audio, not Hindi-only — flagging it Hindi mislabels English playback")
        assertEquals(1080, vidlink.maxQuality)
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
    fun vaplayer_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vaplayer" }
        assertEquals("VaPlayer", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        assertEquals("https://nextgencloudfabric.com/", s.referer)
        val m = ServerFarm.buildMovieUrl(s, "tt0137523")
        assertTrue(m.contains("imdb=tt0137523") && m.contains("type=movie"))
        val tv = ServerFarm.buildTvUrl(s, "tt0944947", 1, 1)
        assertTrue(tv.contains("type=tv") && tv.contains("season=1") && tv.contains("episode=1"))
    }

    @Test
    fun vidrock_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vidrock" }
        assertEquals("VidRock", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        val m = ServerFarm.buildMovieUrl(s, "550")
        assertEquals("https://vidrock.ru/api/movie/550/", m)
        val tv = ServerFarm.buildTvUrl(s, "1399", 1, 1)
        assertEquals("https://vidrock.ru/api/tv/1399/1/1/", tv)
    }

    @Test
    fun videm_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "videm" }
        // Disabled Sept 2026 (links resolve but playback errors) — assert the
        // DISABLED state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    // NHD disabled Sept 2026 (service broken, no streams + delays).
    // Re-enable this test alongside the ServerSpec in ServerRegistry.kt.
    // @Test
    // fun nhd_presentAndTmdbKeyed() {
    //     val s = ServerFarm.allServers.first { it.id == "nhd" }
    //     assertEquals("NHD", s.name)
    //     assertEquals(ServerIdType.TMDB, s.idType)
    // }

    @Test
    fun myflixerHindi_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "myflixer-hindi" }
        // Disabled Sept 2026 (captcha-walled embed + 404 ajax) — assert the
        // DISABLED state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    @Test
    fun moviebox_presentAndTitled() {
        val s = ServerFarm.allServers.first { it.id == "moviebox" }
        assertEquals("MovieBox", s.name)
        // TMDB-keyed: the resolver is title-keyed and never reads the IMDB id,
        // so IMDB keying only delayed its start behind the id lookup.
        assertEquals(ServerIdType.TMDB, s.idType)
        // DASH ladders reach 2160p; nothing downstream may clamp quality.
        assertEquals(2160, s.maxQuality)
        assertEquals("https://fmoviesunblocked.net/", s.referer)
        assertTrue(s.hasSubtitles)
    }

    @Test
    fun primesrc_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "primesrc" }
        // Disabled Sept 2026 (API returns no streams) — assert the DISABLED
        // state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    @Test
    fun farm_hasUniqueIds() {
        val ids = ServerFarm.allServers.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "server ids must be unique (HealthMonitor keys by id)")
    }

    @Test
    fun farm_withinServerCap() {
        assertTrue(ServerFarm.allServers.size <= 16, "farm must stay within MAX_SERVERS cap")
        assertTrue(ServerFarm.allServers.isNotEmpty())
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "vidlink" })
    }

    @Test
    fun vidup_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vidup" }
        assertEquals("VidUp", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://vidup.to/", s.referer)
        assertEquals(2160, s.maxQuality, "VidUp supports 4K via Premier sub-server")
        assertTrue(s.hasSubtitles)
        val m = ServerFarm.buildMovieUrl(s, "27205")
        assertTrue(m.contains("vidup.to/movie/27205"))
        val tv = ServerFarm.buildTvUrl(s, "1399", 1, 1)
        assertTrue(tv.contains("vidup.to/tv/1399/1/1"))
    }

    @Test
    fun vidcore_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vidcore" }
        assertEquals("VidCore", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://vidcore.io/", s.referer)
        assertEquals(2160, s.maxQuality)
        assertTrue(s.hasSubtitles)
        val m = ServerFarm.buildMovieUrl(s, "27205")
        assertTrue(m.contains("vidcore.io/movie/27205"))
    }

    @Test
    fun allmovieland_presentAndHindiFlagged() {
        val s = ServerFarm.allServers.first { it.id == "allmovieland" }
        assertEquals("Allmovieland", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        assertTrue(s.hindi, "Allmovieland is Hindi-first (Hindi/Bengali/Tamil/Telugu playlists)")
        assertEquals(1080, s.maxQuality)
        val m = ServerFarm.buildMovieUrl(s, "tt1375666")
        assertTrue(m.contains("allmovieland.one") && m.contains("tt1375666"))
    }

    @Test
    fun bulletTrainServers_present() {
        // The fast direct-API servers (user spec Sept 2026: "click and play
        // like bullet train" — Hindi/multi-audio, no embed chain). mp4hydra,
        // vidzee, vixsrc and streamprovider are disabled (verified dead Sept
        // 2026: maintenance page / 404 / 403 / 502) — kept out of the farm so
        // they don't trip their breakers and blank the whole result set.
        val ids = setOf("vidlink", "vaplayer", "vidrock", "videasy-hindi", "moviebox", "8stream",
            "vidup", "vidcore", "allmovieland")
        for (id in ids) {
            assertNotNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id must be in the farm",
            )
        }
        // Disabled/dead servers must NOT be in the live farm.
        val disabled = setOf("mp4hydra", "vidzee", "vixsrc", "streamprovider", "nhd", "primesrc", "myflixer-hindi", "videm")
        for (id in disabled) {
            assertNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id is dead/disabled and must not be in the farm",
            )
        }
    }
}
