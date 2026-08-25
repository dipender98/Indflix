package com.multimovies

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NxshaProtocolRulesTest {

    // ---- parseIdsFromUrl -------------------------------------------------

    @Test
    fun `parses movie embed path`() {
        val ids = NxshaProtocol.parseIdsFromUrl("https://nxsha.space/embed/movie/957")
        assertEquals("957", ids.tmdbId)
        assertNull(ids.imdbId)
        assertEquals("movie", ids.type)
        assertNull(ids.season)
        assertNull(ids.episode)
    }

    @Test
    fun `parses tv embed path with season episode`() {
        val ids = NxshaProtocol.parseIdsFromUrl("https://nxsha.space/embed/tv/1396/2/5")
        assertEquals("1396", ids.tmdbId)
        assertEquals("tv", ids.type)
        assertEquals(2, ids.season)
        assertEquals(5, ids.episode)
    }

    @Test
    fun `parses tmdb query form`() {
        val ids = NxshaProtocol.parseIdsFromUrl("https://nxsha.cc/embed?tmdb=1396&type=tv&s=1&e=1")
        assertEquals("1396", ids.tmdbId)
        assertEquals("tv", ids.type)
        assertEquals(1, ids.season)
        assertEquals(1, ids.episode)
    }

    @Test
    fun `parses imdb keyed embed`() {
        val ids = NxshaProtocol.parseIdsFromUrl("https://example.com/play/tt0468569?imdb=tt0468569")
        assertEquals("tt0468569", ids.imdbId)
        assertNull(ids.tmdbId)
    }

    @Test
    fun `keeps season episode for query embed without type`() {
        // Dooplayer-style imdb-keyed embed: no type=, but s/e present.
        val ids = NxshaProtocol.parseIdsFromUrl("https://nxsha.cc/embed?imdb=tt0903747&s=1&e=1")
        assertEquals("tt0903747", ids.imdbId)
        assertNull(ids.type)
        assertEquals(1, ids.season)
        assertEquals(1, ids.episode)
    }

    @Test
    fun `unrelated url parses to nulls`() {
        val ids = NxshaProtocol.parseIdsFromUrl("https://multimovies.motorcycles/movies/jaat-2025/")
        assertNull(ids.tmdbId)
        assertNull(ids.imdbId)
    }

    // ---- orderServers / parseServers ------------------------------------

    private fun server(
        name: String,
        scraper: String,
        position: Int = Int.MAX_VALUE,
        highPriority: Int = Int.MAX_VALUE,
    ) = NxshaServer(name, scraper, position, highPriority)

    @Test
    fun `nitro sorts first then high priority then position`() {
        val ordered = NxshaProtocol.orderServers(
            listOf(
                server("AwsPly", "awsind", position = 13, highPriority = 4),
                server("Citadel", "rive-citadel", position = 7, highPriority = 3),
                server("MbPly", "mbox", position = 1, highPriority = 2),
                server("Nitro - [Multi-Lang]", "nitro", position = 0, highPriority = 0),
                server("MhPly", "mhbox", position = 2, highPriority = 1),
            )
        )
        assertEquals(listOf("nitro", "mhbox", "mbox", "rive-citadel", "awsind"), ordered.map { it.scraper })
    }

    @Test
    fun `nitro is pulled forward even when listed late`() {
        val ordered = NxshaProtocol.orderServers(
            listOf(
                server("Lolly", "holly"),
                server("StremFx", "streamflix"),
                server("Nitro - [Multi-Lang]", "nitro"),
            )
        )
        assertEquals("nitro", ordered.first().scraper)
    }

    @Test
    fun `parseServers filters web_support disabled and wrong type`() {
        val arr = JSONArray(
            listOf(
                org.json.JSONObject(mapOf("name" to "Nitro - [Multi-Lang]", "scraper" to "nitro", "position" to 0, "web_support" to true, "types" to JSONArray(listOf("movie", "tv")))),
                org.json.JSONObject(mapOf("name" to "StreamX", "scraper" to "yomovies", "position" to 18, "web_support" to false)),
                org.json.JSONObject(mapOf("name" to "TamBlast", "scraper" to "tamilblasters", "position" to 27, "web_support" to true, "isDisable" to true)),
                org.json.JSONObject(mapOf("name" to "4k-bk", "scraper" to "hdhub4u", "position" to 34, "web_support" to true, "types" to JSONArray(listOf("movie")))),
                org.json.JSONObject(mapOf("name" to "VidHindi", "scraper" to "em-8", "web_support" to true)),
            )
        )
        // type=movie: nitro (pos 0) first, then no-types VidHindi; hdhub4u serves movies too.
        val servers = NxshaProtocol.parseServers(arr, "movie")
        assertEquals(listOf("nitro", "hdhub4u", "em-8"), servers.map { it.scraper })

        // type=tv: hdhub4u (movies only) drops out.
        val tvServers = NxshaProtocol.parseServers(arr, "tv")
        assertEquals(listOf("nitro", "em-8"), tvServers.map { it.scraper })
    }

    // ---- labels ----------------------------------------------------------

    @Test
    fun `shortServerName trims marketing suffixes`() {
        assertEquals("Nitro", NxshaProtocol.shortServerName("Nitro - [Multi-Lang]"))
        assertEquals("MbPly", NxshaProtocol.shortServerName("MbPly-[Multi-Lang]"))
        assertEquals("Citadel", NxshaProtocol.shortServerName("Citadel"))
    }

    @Test
    fun `hindi quality detection`() {
        assertTrue(NxshaProtocol.isHindiQuality("Hindi dub : 1080"))
        assertTrue(NxshaProtocol.isHindiQuality("[Hindi, English] - 720P"))
        assertTrue(NxshaProtocol.isHindiQuality("720p | Hindi"))
        assertFalse(NxshaProtocol.isHindiQuality("720p | English"))
        assertFalse(NxshaProtocol.isHindiQuality(null))
        assertFalse(NxshaProtocol.isHindiQuality(""))
    }

    // ---- baseUrlFor -------------------------------------------------------

    @Test
    fun `baseUrlFor keeps nxsha player origins and falls back otherwise`() {
        assertEquals("https://nxsha.space", NxshaProtocol.baseUrlFor("https://nxsha.space/embed?imdb=tt123", "https://fallback.example"))
        assertEquals("https://web.nxsha.app", NxshaProtocol.baseUrlFor("https://web.nxsha.app/embed/movie/1", "https://fallback.example"))
        // Non-player nxsha hosts (landing/status sites, live-verified 404/503).
        assertEquals("https://fallback.example", NxshaProtocol.baseUrlFor("https://nxsha.cc/embed?imdb=tt123", "https://fallback.example"))
        assertEquals("https://fallback.example", NxshaProtocol.baseUrlFor("https://nxsha.app/embed?imdb=tt123", "https://fallback.example"))
        assertEquals("https://fallback.example", NxshaProtocol.baseUrlFor("https://nxsha.xyz", "https://fallback.example"))
        // Non-nxsha host entirely.
        assertEquals("https://fallback.example", NxshaProtocol.baseUrlFor("https://gemma416okl.com/play/tt1", "https://fallback.example"))
        assertEquals("https://nxsha.space", NxshaProtocol.baseUrlFor("not a url", "https://nxsha.space"))
    }
}
