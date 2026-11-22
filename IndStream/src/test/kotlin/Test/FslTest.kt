package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FslTest {

    @Test
    fun fsl_present() {
        val s = com.indstream.ServerFarm.allServers.first { it.id == "fsl" }
        assertEquals("FSL", s.name)
        assertEquals(com.indstream.ServerIdType.TMDB, s.idType)
        assertEquals(60, s.timeoutSec)
        assertTrue(s.downloadFriendly)
        assertTrue(s.supports4k)
        assertEquals(setOf("Hindi", "Tamil", "Telugu"), s.declaredLanguages)
    }

    @Test
    fun fsl_fslSearchHits_parsesTypoProxyJson() {
        val json = """{"hits":[{"document":{"permalink":"https://site.example/p/m/","post_title":"Film (2024)"}}]}"""
        val hits = StreamEngine.fslSearchHits(json)
        assertEquals(1, hits.size)
        assertEquals("https://site.example/p/m/", hits[0].first)
        assertEquals("Film (2024)", hits[0].second)
        assertTrue(StreamEngine.fslSearchHits("<html>not json</html>").isEmpty())
    }

    @Test
    fun fsl_fslGroups_headingOwnedGateways() {
        val html = "<div class=\"entry-content\">" +
            "<h5>Film (2024) 1080p [1.7GB]</h5>" +
            "<a href=\"https://nexdrive.site/genxfm/film.zip/\">G</a>" +
            "<h5>Other (2023) 480p</h5>" +
            "<a href=\"https://nexdrive.site/genxfm/other.zip/\">G2</a></div>"
        val groups = StreamEngine.fslGroups(html)
        assertEquals(2, groups.size)
        assertEquals("Film (2024) 1080p [1.7GB]", groups[0].heading)
        assertEquals(1, groups[0].gateways.size)
        assertTrue(groups[0].gateways[0].endsWith("/"))
        assertEquals("1.7GB", StreamEngine.headingSizeOf(groups[0].heading))
    }

    @Test
    fun fsl_gatewayLinksOf_split() {
        val links = StreamEngine.gatewayLinksOf(
            """{\"o\":\"https:\\/\\/fastdl.site\\/embed?download=film_1080p\",\"l\":\"https://vcloud.site/AbCd1234\"}""",
        )
        assertEquals(1, links.cloud.size)
        assertEquals("https://vcloud.site/AbCd1234", links.cloud[0])
        assertTrue(links.fastdl.isEmpty())
    }

    @Test
    fun fsl_vcloudDoubleAtob_decode() {
        val inner = java.util.Base64.getEncoder().encodeToString("https://drive.example/f/x1".toByteArray())
        val blob = java.util.Base64.getEncoder().encodeToString(inner.toByteArray())
        assertEquals("https://drive.example/f/x1", StreamEngine.vcloudDoubleAtob(blob))
        assertNull(StreamEngine.vcloudDoubleAtob("!!!not-base64!!!"))
    }

    @Test
    fun fsl_vcloudPageMatchesEpisode_taggedPages() {
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02E05.1080p.mkv", "tv", 2, 5))
        assertFalse(StreamEngine.vcloudPageMatchesEpisode("Show.S02E01.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Film.2024.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02E01.1080p.mkv", "movie", 2, 5))
    }

    @Test
    fun fsl_episodeFirstOrder_rotatesToRequested() {
        assertEquals(
            listOf("e5", "e1", "e2"),
            StreamEngine.episodeFirstOrder(listOf("e1", "e2", "e5"), "tv", 2, 5),
        )
        assertEquals(
            listOf("e1", "e2", "e5"),
            StreamEngine.episodeFirstOrder(listOf("e1", "e2", "e5"), "movie", 2, 5),
        )
    }

    @Test
    fun fsl_fslIsLanding_parkingMarker() {
        assertTrue(StreamEngine.fslIsLanding("<a href=\"https://qikly.pro/host\">follow</a>"))
        assertTrue(StreamEngine.fslIsLanding("<div id=\"stx\">wait</div>"))
        assertFalse(StreamEngine.fslIsLanding("<div>posts live here</div>"))
    }

    @Test
    fun fsl_fslServerLabel_honorsSourceUniqueness() {
        assertEquals("FSLv2", StreamEngine.fslServerLabel("FSLv2 Server", "FSL"))
        assertEquals("FSL", StreamEngine.fslServerLabel("FSL Server", "FSL"))
        assertEquals("FSL", StreamEngine.fslServerLabel("Download File", "FSL"))
        assertEquals("FSL", StreamEngine.fslServerLabel("1080p", "FSL"))
        assertEquals("FSL", StreamEngine.fslServerLabel("Hindi", "FSL"))
        assertEquals("FSL", StreamEngine.fslServerLabel(null, "FSL"))
    }
}
