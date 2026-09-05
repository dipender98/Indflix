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

    private val spec: ServerSpec
        get() = ServerFarm.allServers.first { it.id == "myflixer-hindi" }

    @Test
    fun hindiServer_presentAndFlagged() {
        val s = spec
        assertEquals("MyFlixer Hindi", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        assertTrue(s.hindi, "MyFlixer Hindi must be flagged Hindi so it biases to priority 4")
        assertEquals(1080, s.maxQuality)
        assertTrue(s.hasSubtitles)
        assertEquals("https://hindi.myflixerapi.com/", s.referer)
    }

    @Test
    fun hindiMovieUrl_usesImdbQuery() {
        val url = ServerFarm.buildMovieUrl(spec, "tt0111161")
        assertEquals("https://hindi.myflixerapi.com/embed/movie?imdb=tt0111161", url)
        assertTrue(url.contains("imdb=tt0111161"))
    }

    @Test
    fun hindiTvUrl_usesSeaEpiParams() {
        // TV uses sea/epi query params (not path form), confirmed live.
        val url = ServerFarm.buildTvUrl(spec, "tt0903747", 4, 9)
        assertEquals("https://hindi.myflixerapi.com/embed/series?imdb=tt0903747&sea=4&epi=9", url)
        assertTrue(url.contains("sea=4"), "TV url must use sea= param")
        assertTrue(url.contains("epi=9"), "TV url must use epi= param")
        assertTrue(!url.contains("/4/9"), "TV url must NOT use path form")
    }

    @Test
    fun hindiServer_hasUniqueId() {
        val ids = ServerFarm.allServers.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "server ids must be unique (HealthMonitor keys by id)")
    }

    @Test
    fun hindiServer_withinServerCap() {
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "myflixer-hindi" })
        assertTrue(ServerFarm.allServers.size <= 12, "farm must stay within MAX_SERVERS cap")
    }
}
