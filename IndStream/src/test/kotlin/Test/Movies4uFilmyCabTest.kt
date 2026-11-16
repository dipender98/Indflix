package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Movies4uFilmyCabTest {

    @Test
    fun farm_addsBoth4kMultiAudioDownloadSources() {
        for (id in listOf("movies4u", "filmycab")) {
            val spec = assertNotNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, spec.idType, "$id is TMDB-keyed")
            assertTrue(spec.supports4k, "$id must advertise 4K")
            assertTrue(spec.downloadFriendly, "$id is a download source")
            assertTrue(spec.declaredLanguages.size >= 2, "$id must declare multi audio")
            assertFalse(spec.isJsonApi, "$id resolves HTML pages, not a JSON API")
            assertTrue(spec.timeoutSec >= 45, "$id chain needs headroom")
        }
    }

    @Test
    fun movies4u_searchJson_parsesHits() {
        val json = """
            {"ok":true,"q":"Jawan","found":2,"hits":[
              {"id":"7639","post_title":"Jawan (2023) WEB-DL Multi Audio [Hindi + Tamil + Telugu] Full Movie",
               "permalink":"\/jawan-2023-web-dl-multi-audio-full-movie\/"},
              {"id":"1","post_title":"Jawan Purana (2024) Hindi Full Movie","permalink":"\/jawan-purana-2024\/"}]}
        """.trimIndent()
        val hits = StreamEngine.m4uSearchHits(json, "https://new8.movies4u.garden")
        assertEquals(2, hits.size)
        assertEquals("https://new8.movies4u.garden/jawan-2023-web-dl-multi-audio-full-movie/", hits[0].first)
        assertTrue(hits[0].second.contains("Multi Audio"))
        assertEquals("https://new8.movies4u.garden/jawan-purana-2024/", hits[1].first)
    }

    @Test
    fun movies4u_searchJson_ignoresGarbage() {
        assertTrue(StreamEngine.m4uSearchHits("<html>no json</html>", "https://new8.movies4u.garden").isEmpty())
        assertTrue(StreamEngine.m4uSearchHits("""{"ok":false}""", "https://new8.movies4u.garden").isEmpty())
    }

    @Test
    fun movies4u_groups_pairsHeadingWithButtons() {
        val html = """
            <h4>1080p HQ [6.1GB] </h4>
            <div class="downloads-btns-div">
              <a href="https://hubcloud.mov/drive/6ce1cjickkw61jg" class="btn">Hub-Cloud [DD]</a>
              <a href="https://gdlink.dev/file/5yXBcvPzOWdg0nK" class="btn">GDFlix</a>
            </div>
            <h4>2160p 4K [20.2GB] </h4>
            <div class="downloads-btns-div">
              <a href="https://hubcloud.mov/drive/xx62qg49zdqxzly" class="btn">Hub-Cloud [DD]</a>
            </div>
            <h4>Unrelated heading</h4>
            <p>no buttons here</p>
        """.trimIndent()
        val groups = StreamEngine.m4uGroups(html, "https://m4ulinks.sbs/number/7639")
        assertEquals(2, groups.size)
        assertEquals("1080p HQ [6.1GB]", groups[0].heading)
        assertEquals(2, groups[0].links.size)
        assertEquals("2160p 4K [20.2GB]", groups[1].heading)
        assertEquals(listOf("https://hubcloud.mov/drive/xx62qg49zdqxzly"), groups[1].links)
    }

    @Test
    fun movies4u_tvGroup_headingCarriesSeasonQualityAndAudio() {
        val groups = StreamEngine.m4uGroups(
            """
            <h4>Season 5 <span>[Hindi ORG. + Multi Audio]</span> 2160p 4K SDR [7.5GB/E]</h4>
            <div class="downloads-btns-div">
              <a href="https://m4ulinks.sbs/number/50078" class="btn">Download Links</a>
            </div>
            """.trimIndent(),
            "https://new8.movies4u.garden/the-boys/",
        )
        assertEquals(1, groups.size)
        assertTrue(groups[0].heading.contains("Season 5"))
        assertEquals(2160, StreamEngine.releaseHeightOf(groups[0].heading))
        assertEquals("Hindi", com.indstream.LinkNaming.languageLabel(groups[0].heading))
    }

    @Test
    fun postYearGate_moviesStrict_tvAllowsLaterSeasonYears() {
        assertTrue(StreamEngine.postYearMatches(2023, 2023, "movie"))
        assertFalse(StreamEngine.postYearMatches(2023, 2025, "movie"))
        assertTrue(StreamEngine.postYearMatches(2023, null, "movie"))
        assertTrue(StreamEngine.postYearMatches(2024, 2026, "tv"), "season 2 post labelled with its air year")
        assertTrue(StreamEngine.postYearMatches(2019, 2026, "tv"))
        assertFalse(StreamEngine.postYearMatches(2019, 2015, "tv"), "older post than the show")
        assertTrue(StreamEngine.postYearMatches(null, 2026, "tv"))
    }

    @Test
    fun movies4u_episodeHeading_parsesNumber() {
        assertEquals(1, StreamEngine.m4uEpisodeOf("-:Episodes: 1:-"))
        assertEquals(12, StreamEngine.m4uEpisodeOf("-:Episodes: 12:-"))
        assertEquals(4, StreamEngine.m4uEpisodeOf("Episode 4"))
        assertEquals(3, StreamEngine.m4uEpisodeOf("S02E03 1080p"))
        assertEquals(7, StreamEngine.m4uEpisodeOf("EP 07"))
        assertNull(StreamEngine.m4uEpisodeOf("Batch / Zip"))
    }

    @Test
    fun filmycab_search_keepsOnlyPostAnchors() {
        val html = """
            <a href="https://filmycab.store/2026/11/12/sardaar-ji-3-2026/"><p>Sardaar Ji 3 (2026) WEB-HDRip [Punjabi ORG DD 5.1] 4K</p></a>
            <a href="https://filmycab.store/2026/11/12/sardaar-ji-3-2026/"><div class="thumb-hover"><span>Download</span></div></a>
            <a href="https://filmycab.store/category/punjabi/">Punjabi</a>
            <a href="https://filmycab.store/2026/03/21/sardaar-ji-3-2025-punjabi-full-movie-predvd/"><p>Sardaar Ji 3 (2025) Punjabi Full Movie PreDVD</p></a>
        """.trimIndent()
        val hits = StreamEngine.fcHits(html, "https://filmycab.store")
        assertEquals(2, hits.size)
        assertEquals("https://filmycab.store/2026/11/12/sardaar-ji-3-2026/", hits[0].first)
        assertTrue(hits[0].second.contains("Punjabi ORG"))
    }

    @Test
    fun filmycab_boxes_readQualitySizeAndMipUrl() {
        val html = """
            <div class="multiple-downloads-container"><div class="download-flex-wrapper">
              <div class="quality-download-box">
                <div class="quality-name-white">2160p (4K)</div>
                <div class="quality-size-light">14.7 GB</div>
                <a href="https://filmycab.store/?mip_server=9811&#038;mip_entry=0" class="dlbtn-sm">DOWNLOAD</a>
              </div>
              <div class="quality-download-box">
                <div class="quality-name-white">1080p</div>
                <div class="quality-size-light">2.6 GB</div>
                <a href="https://filmycab.store/?mip_server=9811&#038;mip_entry=2" class="dlbtn-sm">DOWNLOAD</a>
              </div>
            </div></div>
        """.trimIndent()
        val boxes = StreamEngine.fcBoxes(html, "https://filmycab.store/2026/11/12/sardaar-ji-3-2026/")
        assertEquals(2, boxes.size)
        assertEquals("2160p (4K)", boxes[0].label)
        assertEquals("14.7 GB", boxes[0].size)
        assertEquals("https://filmycab.store/?mip_server=9811&mip_entry=0", boxes[0].url)
        assertEquals(2160, StreamEngine.releaseHeightOf(boxes[0].label))
        assertEquals(1080, StreamEngine.releaseHeightOf(boxes[1].label))
    }

    @Test
    fun filmycab_batchLabels_inheritEqualPrefix() {
        val boxes = listOf(
            StreamEngine.FcBox("EP 1 TO 3 1080p HQ", "", "https://filmycab.store/?mip_server=1&mip_entry=0"),
            StreamEngine.FcBox("= 1080p", "", "https://filmycab.store/?mip_server=1&mip_entry=1"),
            StreamEngine.FcBox("= 720p", "", "https://filmycab.store/?mip_server=1&mip_entry=2"),
            StreamEngine.FcBox("EP 4 TO 6 1080p HQ", "", "https://filmycab.store/?mip_server=1&mip_entry=3"),
            StreamEngine.FcBox("= 1080p", "", "https://filmycab.store/?mip_server=1&mip_entry=4"),
        )
        val resolved = StreamEngine.fcResolveBatchLabels(boxes)
        assertEquals("EP 1 TO 3 1080p", resolved[1].label)
        assertEquals("EP 1 TO 3 720p", resolved[2].label)
        assertEquals("EP 4 TO 6 1080p HQ", resolved[3].label)
        assertEquals("EP 4 TO 6 1080p", resolved[4].label)
        assertTrue(StreamEngine.fcEpisodeCovers(resolved[1].label, 2))
        assertFalse(StreamEngine.fcEpisodeCovers(resolved[1].label, 5))
        assertTrue(StreamEngine.fcEpisodeCovers(resolved[4].label, 5))
    }

    @Test
    fun filmycab_episodeRanges_coverRequestedEpisode() {
        val ep1to3 = "EP 1 TO 3 1080p HQ"
        assertTrue(StreamEngine.fcEpisodeCovers(ep1to3, 1))
        assertTrue(StreamEngine.fcEpisodeCovers(ep1to3, 3))
        assertFalse(StreamEngine.fcEpisodeCovers(ep1to3, 4))
        val ep4to6 = "EP 4 TO 6 1080p HQ"
        assertTrue(StreamEngine.fcEpisodeCovers(ep4to6, 5))
        assertFalse(StreamEngine.fcEpisodeCovers(ep4to6, 7))
        assertTrue(StreamEngine.fcEpisodeCovers("EP 7 1080p", 7))
        assertFalse(StreamEngine.fcEpisodeCovers("EP 7 1080p", 8))
        assertTrue(StreamEngine.fcEpisodeCovers("1080p HQ", 9), "no episode marker means a full pack")
        assertTrue(StreamEngine.fcEpisodeCovers("2160p (4K)", 1), "movie boxes carry no episode marker")
    }

    @Test
    fun filmycab_seasonFromTitle() {
        assertEquals(2, StreamEngine.fcSeasonOf("Black Doves (2026) [Season 2] WEB-HDRip [Dual Audio] 1080p"))
        assertEquals(5, StreamEngine.fcSeasonOf("The Boys S05 1080p"))
        assertEquals(3, StreamEngine.fcSeasonOf("Mirzapur Season 3 Hindi"))
        assertNull(StreamEngine.fcSeasonOf("Sardaar Ji 3 (2026) WEB-HDRip [Punjabi ORG DD 5.1] 4K"))
    }

    @Test
    fun filmycab_mipHosts_keepOnlyGatewayLinks() {
        val html = """
            <h1>Gajab Raat (2026) 4K</h1>
            <p class="meta">2160p [14.7 GB]</p>
            <div class="card">
              <div class="server"><div><span class="name">HubCloud</span><span class="host">hubcloud.ist</span></div>
                <a class="btn" href="https://hubcloud.ist/drive/lx7ma5lmo5wllbx">Download</a></div>
              <div class="server"><div><span class="name">MultiCloud</span><span class="host">new3.multicloudlinks.com</span></div>
                <a class="btn" href="https://new3.multicloudlinks.com/view/i7yw3m">Download</a></div>
              <div class="server"><div><span class="name">GDFlix</span><span class="host">gdflix.vip</span></div>
                <a class="btn" href="https://gdflix.vip/file/fPFSJK2NMWjTKar">Download</a></div>
              <div class="server"><div><span class="name">HubDrive</span><span class="host">hubdrive.bar</span></div>
                <a class="btn" href="https://hubdrive.bar/file/2529110837">Download</a></div>
            </div>
            <a class="back" href="https://filmycab.store/2026/10/23/gajab-raat/">Back to movie</a>
        """.trimIndent()
        val hosts = StreamEngine.fcMipHosts(html, "https://filmycab.store/?mip_server=9530&mip_entry=1")
        assertEquals(
            listOf("https://hubcloud.ist/drive/lx7ma5lmo5wllbx", "https://hubdrive.bar/file/2529110837"),
            hosts,
        )
    }
}
