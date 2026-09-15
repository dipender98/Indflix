package Test

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.multimovies.LinkCache
import com.multimovies.MultiSourcePuller
import kotlinx.coroutines.runBlocking
import com.multimovies.NxshaProtocol
import com.multimovies.NxshaServer
import com.multimovies.ResolvedEmbed
import com.multimovies.dooplayOptionKey
import com.multimovies.embedIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the audit fixes: cache blank-id guard, language-aware dedupe tag. */
class AuditFixesMmTest {

    private fun link(source: String, url: String) = ExtractorLink(
        source = source,
        name = source,
        url = url,
        referer = "",
        quality = 1080,
        headers = emptyMap(),
        extractorData = null,
        type = ExtractorLinkType.M3U8,
        audioTracks = emptyList(),
    )

    @Test
    fun linkCache_blankId_neverStoresNorReplays() {
        LinkCache.put("", 1, 1, listOf(link("A", "https://cdn.example/a.m3u8")))
        assertNull(LinkCache.get("", 1, 1))
        assertNull(LinkCache.get(null, 1, 1))
    }

    @Test
    fun linkCache_validId_roundTrips() {
        val links = listOf(link("A (Hindi)", "https://cdn.example/a.m3u8"))
        LinkCache.put("tt1375666", null, null, links)
        val got = LinkCache.get("tt1375666", null, null)
        assertTrue(got != null && got.size == 1)
    }

    @Test
    fun bracketTag_trailingLanguageOnly() {
        assertEquals("Hindi", MultiSourcePuller.bracketTag(link("Server (Hindi)", "https://x/a.m3u8")))
        assertEquals("Multi", MultiSourcePuller.bracketTag(link("Server-2 (Multi)", "https://x/b.m3u8")))
        assertEquals("", MultiSourcePuller.bracketTag(link("Server Hindi", "https://x/c.m3u8")))
        assertEquals("", MultiSourcePuller.bracketTag(link("Server", "https://x/d.m3u8")))
    }

    @Test
    fun nxshaIds_imdbInMoviePath() {
        val p = NxshaProtocol.parseIdsFromUrl("https://nxsha.space/embed/movie/tt1375666")
        assertEquals("tt1375666", p.imdbId)
        assertEquals("movie", p.type)
    }

    @Test
    fun nxshaIds_imdbInTvPathWithQuerySeasons() {
        val p = NxshaProtocol.parseIdsFromUrl("https://web.nxsha.app/embed/tv/tt0944947?s=1&e=1")
        assertEquals("tt0944947", p.imdbId)
        assertEquals("tv", p.type)
        assertEquals(1, p.season)
        assertEquals(1, p.episode)
    }

    @Test
    fun nxshaIds_tmdbPathStillWins() {
        val p = NxshaProtocol.parseIdsFromUrl("https://nxsha.space/embed/tv/1396/1/1")
        assertEquals("1396", p.tmdbId)
        assertEquals("tv", p.type)
    }

    @Test
    fun nxshaOrder_priorityThenPosition_noHardcodedTop() {
        val servers = listOf(
            NxshaServer("Nitro", "nitro", 0, Int.MAX_VALUE),
            NxshaServer("MbPly", "mbox", 1, 2),
            NxshaServer("MhPly", "mhbox", 2, 1),
        )
        assertEquals(listOf("MhPly", "MbPly", "Nitro"), NxshaProtocol.orderServers(servers).map { it.name })
    }

    @Test
    fun nxshaParse_dropsRetiredAndUnsupported() {
        val arr = org.json.JSONArray(
            """[{"name":"Vip-4K","scraper":"vidking","position":13,"web_support":true},""" +
                """{"name":"MbPly","scraper":"mbox","position":1,"high_priority":2,"web_support":true,"types":["movie","tv"]},""" +
                """{"name":"Off","scraper":"x","position":2,"web_support":false}]""",
        )
        val out = NxshaProtocol.parseServers(arr, "movie")
        assertEquals(listOf("MbPly"), out.map { it.name })
    }

    @Test
    fun dooplayDupNames_stayDistinctByKey() {
        val a = ResolvedEmbed("Nxsha", "u1", embedUrl = "https://nxsha.space/embed/tv/1396/1/1", key = dooplayOptionKey("165266", "5", "tv"))
        val b = ResolvedEmbed("Nxsha", "u2", embedUrl = "https://web.nxsha.app/embed/tv/1396/1/1", key = dooplayOptionKey("165266", "7", "tv"))
        assertTrue(a.embedIdentity() != b.embedIdentity())
        assertEquals(2, listOf(a, b).distinctBy { it.embedIdentity() }.size)
    }

    @Test
    fun enrichLabel_zeroBudget_skipsProbeKeepsUrlFacts() = runBlocking {
        // Zero budget: no master fetch, height from URL quality, language local-only.
        val l = link("Cineverse", "https://cdn.example/x/1080p/master.m3u8")
        val out = MultiSourcePuller.enrichLabel(l, probeBudgetMs = 0L)
        assertEquals(1080, out.quality)
        assertTrue(out.name.contains("Cineverse"))
    }
}
