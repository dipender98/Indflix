package Test

import com.indstream.ManifestKit
import com.indstream.TitleMatch
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpec
import com.indstream.StreamEngine
import com.indstream.VidlinkSource
import com.indstream.VideasySource

/** FILE: ServerFarmHindiTest. kt â€” guards ServerRegistry. kt for the Hindi MyFlixerAPI entry. Verifies the SeedspÃ©c. + URL builders emit the. */

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
        // VidLink is multi-audio (Hindi + English dubs), NOT a Hindi-only host. A single declared language would blanket-bias.
// every stream's label even when.
        assertTrue(vidlink.declaredLanguages.isEmpty(), "VidLink is multi-audio, not Hindi-only — a declared language mislabels English playback")
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
        // Disabled (links resolve but playback errors) - assert the DISABLED state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    // NHD disabled (service broken, no streams + delays). Re-enable this test alongside the ServerSpec in ServerRegistry.
// kt. @Test fun.

    @Test
    fun myflixerHindi_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "myflixer-hindi" }
        // Disabled (captcha-walled embed + 404 ajax) - assert the DISABLED state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    @Test
    fun moviebox_presentAndTitled() {
        val s = ServerFarm.allServers.first { it.id == "moviebox" }
        assertEquals("MovieBox", s.name)
        // TMDB-keyed: the resolver is title-keyed and never reads the IMDB id, so IMDB keying only delayed its start behind.
// the id lookup.
        assertEquals(ServerIdType.TMDB, s.idType)
        // DASH ladders reach 2160p; nothing downstream may clamp quality.
        assertEquals(2160, s.maxQuality)
        assertEquals("https://fmoviesunblocked.net/", s.referer)
        assertTrue(s.hasSubtitles)
    }

    @Test
    fun primesrc_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.firstOrNull { it.id == "primesrc" }
        // Disabled (API returns no streams) - assert the DISABLED state so a re-enable is a conscious act.
        assertEquals(null, s)
    }

    @Test
    fun nhd_presentAndTmdbKeyed() {
        // Re-enabled (multi-language expansion probe): TV pipeline live (GoT S1E1 + Family Man S1E1 playable); the movie.
// pipeline is dead upstream and.
        val s = ServerFarm.allServers.first { it.id == "nhd" }
        assertEquals("NHD", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(1080, s.maxQuality)
        // F8 budget invariant: page 8s + extraction 10s = 18s must fit the kill.
        assertEquals(20, s.timeoutSec, "nhd farm kill must fit the resolver chain (8+10)")
        assertEquals("https://nhdapi.com/", s.referer)
        val m = ServerFarm.buildMovieUrl(s, "27205")
        assertEquals("https://nhdapi.com/movie/27205", m)
        val tv = ServerFarm.buildTvUrl(s, "1399", 1, 1)
        assertEquals("https://nhdapi.com/tv/1399/1/1", tv)
    }

    @Test
    fun netmirror_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "netmirror" }
        assertEquals("NetMirror", s.name)
        // TMDB-keyed embed-tmdb JSON API (Netflix-grade progressive MP4 ladders) + variants-tmdb dub fan-out. The default.
// ladder's audio is NOT.
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.declaredLanguages.isEmpty(), "default-ladder audio undeclared — never guessed")
        assertTrue(s.isJsonApi, "embed-tmdb JSON API")
        assertEquals(1080, s.maxQuality)
        assertTrue(s.hasSubtitles, "API captions carry language (incl. Bengali/Punjabi)")
        assertEquals("https://net27.cc/", s.referer)
        // Round-3: the resolve chain is default-embed ‖ variants (12s/6s) then a dub fan-out (dub embeds in parallel, 12s) →.
// ~24s worst; the kill sits 50s.
        assertEquals(50, s.timeoutSec, "netmirror farm kill must sit above the 24s dub fan-out chain")
        val m = ServerFarm.buildMovieUrl(s, "27205")
        assertEquals("https://net27.cc/api/embed-tmdb/27205", m)
        val tv = ServerFarm.buildTvUrl(s, "1399", 1, 1)
        assertEquals("https://net27.cc/api/embed-tmdb/1399?type=tv&se=1&ep=1", tv)
    }

    @Test
    fun netmirrorDubLabel_policyOriginalPlusEnglishPlusIndian() {
        // ), ENGLISH, and every official INDIAN dub - drop all other foreign dubs and the subtitle-only "* sub" rows.
        assertEquals("Original", StreamEngine.netmirrorDubLabel("Japanese dub", isOriginal = true))
        assertEquals("Original", StreamEngine.netmirrorDubLabel("Korean", isOriginal = true))
        assertEquals("English", StreamEngine.netmirrorDubLabel("English dub", isOriginal = false))
        assertEquals("Hindi", StreamEngine.netmirrorDubLabel("Hindi dub", isOriginal = false))
        assertEquals("Tamil", StreamEngine.netmirrorDubLabel("Tamil dub", isOriginal = false))
        assertEquals("Telugu", StreamEngine.netmirrorDubLabel("Telugu dub", isOriginal = false))
        assertEquals("Bengali", StreamEngine.netmirrorDubLabel("Bengali dub", isOriginal = false))
        assertEquals("Gujarati", StreamEngine.netmirrorDubLabel("Gujarati dub", isOriginal = false))
        // Foreign non-English dubs dropped.
        assertNull(StreamEngine.netmirrorDubLabel("ptbr dub", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel("esla dub", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel("Russian dub", isOriginal = false))
        assertNull(StreamEngine.netmirrorDubLabel("French dub", isOriginal = false))
        // so are subtitle-only variants (same audio as the default).
        assertNull(StreamEngine.netmirrorDubLabel("Arabic sub", isOriginal = false))
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
    fun allmovieland_presentWithDeclaredLanguages() {
        val s = ServerFarm.allServers.first { it.id == "allmovieland" }
        assertEquals("Allmovieland", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        // Multi-language host () - the declared set documents the coverage but must NOT be a single language (no blanket bias.
// per-entry resolver labels win).
        assertEquals(setOf("Hindi", "Tamil", "Telugu", "Bengali"), s.declaredLanguages)
        assertEquals(1080, s.maxQuality)
        // timeoutSec 30→60 (F8 budget-invariant): the documented chain ceilings - search 8s×2 hosts (IMDB pass) + 8s title.
// fallback + card 6 + 2×(play 5 +.
        assertEquals(60, s.timeoutSec, "allmovieland farm kill must fit the resolver chain")
        // DOMAIN MOVE (): . one 301s to. art - the spec pins the live host; allmovielandHosts() carries the fallback.
        val m = ServerFarm.buildMovieUrl(s, "tt1375666")
        assertTrue(m.contains("allmovieland.art") && m.contains("tt1375666"))
    }

    @Test
    fun allmovieland_hosts_fallbackOrderPinned() {
        // Pure: newest host first (. art live today), . one kept as the manual fallback if. art moves again (StreamEngine.
// resolveAllmovieland loops).
        assertEquals(
            listOf("https://allmovieland.art", "https://allmovieland.one"),
            StreamEngine.allmovielandHosts(),
        )
    }

    @Test
    fun moviebox_timeoutBudgetFitsFarmKill() {
        // latency). Budgets: bearer 8 (prewarmed→0) + search 15 + auth-only retry + detail 8 + dl/play 8 ⇒ single chain ≈ 39s.
// full reject chain ≈ 51s; the.
        val s = ServerFarm.allServers.first { it.id == "moviebox" }
        assertEquals(55, s.timeoutSec, "MovieBox latency-parity budgets (8/15[+8/12]/8/8) must fit this kill")
    }

    @Test
    fun bulletTrainServers_present() {
        // The fast direct-API servers (, no embed chain). mp4hydra, vidzee, vixsrc, streamprovider and 8stream are disabled ().
// kept out of the farm so.
        val ids = setOf("vidlink", "vaplayer", "vidrock", "videasy-hindi", "moviebox", "vidnest",
            "vidup", "vidcore", "allmovieland", "nhd", "netmirror",
            "vixsrc", "zxcstreams", "dahmermovies", "vidapi", "twoembed")
        for (id in ids) {
            assertNotNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id must be in the farm",
            )
        }
        // Disabled/dead servers must NOT be in the live farm (vixsrc re-enabled Sept 2026).
        val disabled = setOf("mp4hydra", "vidzee", "streamprovider", "primesrc",
            "myflixer-hindi", "videm", "8stream")
        for (id in disabled) {
            assertNull(
                ServerFarm.allServers.firstOrNull { it.id == id },
                "$id is dead/disabled and must not be in the farm",
            )
        }
    }

    @Test
    fun farm_withinExpandedCap() {
        // Sept 2026 expansion: 16 live servers, exactly at the cap.
        assertEquals(16, ServerFarm.allServers.size, "farm must stay within MAX_SERVERS cap")
    }
}
