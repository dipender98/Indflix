package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dedicated coverage for the HindMoviez download-farm provider.
 * The provider search-scrapes a link index and resolves file hosts from
 * link-index posts (joshapp/mvlink anchors).
 */
class HindMoviezTest {

    private val spec get() = ServerFarm.allServers.first { it.id == "hindmoviez" }

    @Test
    fun registry_presentExactlyOnce() {
        assertEquals(1, ServerFarm.allServers.count { it.id == "hindmoviez" }, "one toggle, one health record")
    }

    @Test
    fun registry_presentAndTmdbKeyed() {
        assertEquals("HindMoviez", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://hindmovie.one/", spec.referer)
        assertEquals(60, spec.timeoutSec, "download-farm kill must fit the search + file chain")
        assertEquals(ServerSpeed.SLOW, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertFalse(spec.hasSubtitles, "index provider has no subtitle API")
        assertFalse(spec.animeOnly)
        assertFalse(spec.supports4k)
        assertTrue(spec.declaredLanguages.isEmpty(), "audio is read per-file, not declared")
    }

    @Test
    fun registry_urls_indexAtRoot() {
        assertEquals("https://hindmovie.one/", ServerFarm.buildMovieUrl(spec, "872906"))
        assertEquals("https://hindmovie.one/", ServerFarm.buildTvUrl(spec, "1399", 1, 1))
    }

    @Test
    fun search_titleHits_onIndexPage() {
        val html = "<h2 class=\"entry-title\"><a href=\"/jawan-2023/\">Jawan 2023</a></h2>" +
            "<a href=\"/2026/07/31/film/\"><p>Film (2026)</p></a>"
        val hits = StreamEngine.titleHits(html, "https://hindmovie.one")
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.first == "https://hindmovie.one/jawan-2023/" })
        assertTrue(hits.any { it.second == "Film (2026)" })
    }

    @Test
    fun linkIndexAnchors_joshappAndMvlink_picked() {
        val html = "<h5>Show S01E02 (2024) 1080p Hindi [1.4GB]</h5>" +
            "<p><a href=\"https://links.joshapp.blog/abc123\">Download Links</a></p>" +
            "<h5>Film (2023) 4K [8GB]</h5>" +
            "<p><a href=\"https://mvlink.blog/xyz789\">Download Links</a></p>"
        val hits = StreamEngine.headingAnchorHits(
            html,
            "https://hindmovie.one",
            Regex("links\\.joshapp\\.blog|mvlink\\.blog", RegexOption.IGNORE_CASE),
        )
        assertEquals(2, hits.size)
        assertEquals("https://links.joshapp.blog/abc123", hits[0].first)
        assertTrue(hits[0].second.contains("S01E02"))
        assertEquals("https://mvlink.blog/xyz789", hits[1].first)
        assertTrue(hits[1].second.contains("Film (2023)"))
    }

    @Test
    fun linkIndexAnchors_onlyIndexHosts() {
        val html = "<h5>Film (2023) 1080p Hindi</h5>" +
            "<p><a href=\"https://other.example/dl\">Download Links</a></p>"
        val hits = StreamEngine.headingAnchorHits(
            html,
            "https://hindmovie.one",
            Regex("links\\.joshapp\\.blog|mvlink\\.blog", RegexOption.IGNORE_CASE),
        )
        assertTrue(hits.isEmpty(), "non-index hosts must not match")
    }

    @Test
    fun headingLanguageAndSize_extracted() {
        assertEquals("Hindi", StreamEngine.releaseLanguageOf("Jawan 2023 Hindi WEB-DL"))
        assertEquals("1.4GB", StreamEngine.normalizeSizeToken("Show S01E02 (2024) 1080p Hindi [1.4GB]"))
        assertEquals(1080, StreamEngine.releaseHeightOf("Show S01E02 (2024) 1080p Hindi [1.4GB]"))
    }
}
