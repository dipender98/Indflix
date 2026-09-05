package com.ottmirror.stream

/**
 * FILE: ServerFarmHindiTest.kt — guards ServerRegistry.kt for the Hindi
 * MyFlixerAPI entry. Verifies the Seedspéc + URL builders emit the expected
 * Hindi MyFlixerAPI URLs and that the `hindi` flag is set so [StreamEngine]
 * biases it to priority 4.
 */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerFarmHindiTest {

    private val vidlink: ServerSpec
        get() = ServerFarm.allServers.first { it.id == "vidlink" }

    @Test
    fun vidlink_presentAndFlaggedHindi() {
        assertEquals("VidLink", vidlink.name)
        assertEquals(ServerIdType.TMDB, vidlink.idType)
        assertTrue(vidlink.hindi, "VidLink must be flagged Hindi (multiLang=1 carries dubs)")
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
        val s = ServerFarm.allServers.first { it.id == "videm" }
        assertEquals("VidEm", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        val m = ServerFarm.buildMovieUrl(s, "tt0137523")
        assertEquals("https://videm.xyz/embed/movie/tt0137523", m)
        val tv = ServerFarm.buildTvUrl(s, "tt0944947", 1, 1)
        assertEquals("https://videm.xyz/embed/tv/tt0944947/1/1", tv)
    }

    @Test
    fun nhd_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "nhd" }
        assertEquals("NHD", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
    }

    @Test
    fun farm_hasUniqueIds() {
        val ids = ServerFarm.allServers.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "server ids must be unique (HealthMonitor keys by id)")
    }

    @Test
    fun farm_withinServerCap() {
        assertTrue(ServerFarm.allServers.size <= 12, "farm must stay within MAX_SERVERS cap")
        assertTrue(ServerFarm.allServers.isNotEmpty())
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "vidlink" })
    }
}
