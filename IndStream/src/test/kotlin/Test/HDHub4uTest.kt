package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated coverage for the HDHub4u download-farm provider.
 * The provider searches via aria-label cards, year-gates posts, and resolves
 * rotator gateway archives (JS/meta redirects) to file hosts.
 */
class HDHub4uTest {

    private val spec get() = ServerFarm.allServers.first { it.id == "hdhub4u" }

    @Test
    fun registry_presentExactlyOnce() {
        assertEquals(1, ServerFarm.allServers.count { it.id == "hdhub4u" }, "one toggle, one health record")
    }

    @Test
    fun registry_presentAndTmdbKeyed() {
        assertEquals("HDHub4u", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://new8.hdhub4u.cl/", spec.referer)
        assertEquals(60, spec.timeoutSec, "download-farm kill must fit the search + gateway chain")
        assertEquals(ServerSpeed.SLOW, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertTrue(spec.supports4k)
        assertFalse(spec.hasSubtitles)
        assertFalse(spec.animeOnly)
        assertTrue(spec.declaredLanguages.isEmpty(), "audio is read per-file, not declared")
    }

    @Test
    fun registry_urls_searchAtRoot() {
        assertEquals("https://new8.hdhub4u.cl/", ServerFarm.buildMovieUrl(spec, "872906"))
        assertEquals("https://new8.hdhub4u.cl/", ServerFarm.buildTvUrl(spec, "1399", 1, 1))
    }

    @Test
    fun search_ariaHits_onSearchPage() {
        val html = "<a href=\"/jawan-2023-hindi-webrip-full-movie/\" " +
            "aria-label=\"Jawan (2023) WEB-DL Hindi | Full Movie\">x</a>" +
            "<a href=\"/category/action/\" aria-label=\"Action\">x</a>"
        val hits = StreamEngine.ariaHits(html, "https://new8.hdhub4u.cl")
        assertEquals(1, hits.size)
        assertEquals("https://new8.hdhub4u.cl/jawan-2023-hindi-webrip-full-movie/", hits[0].first)
    }

    @Test
    fun postYearGate_rejectsWrongYear() {
        val html = "<html><head><meta property=\"og:title\" content=\"Jawan 2023 Hindi\" /></head></html>"
        assertTrue(StreamEngine.hubPostYearOk(html, "2023"))
        assertFalse(StreamEngine.hubPostYearOk(html, "2024"))
        assertTrue(StreamEngine.hubPostYearOk(html, null))
    }

    @Test
    fun jsRedirectTarget_locationHref() {
        val html = "<script>window.location.href = 'https://hubcloud.ist/drive/abc123';</script>"
        assertEquals("https://hubcloud.ist/drive/abc123", StreamEngine.jsRedirectTarget(html))
    }

    @Test
    fun jsRedirectTarget_metaRefresh() {
        val html = "<meta http-equiv=\"refresh\" content=\"0; url=https://hubcloud.ist/drive/def456\">"
        assertEquals("https://hubcloud.ist/drive/def456", StreamEngine.jsRedirectTarget(html))
    }

    @Test
    fun jsRedirectTarget_storedOBlob() {
        val blob = java.util.Base64.getEncoder().encodeToString(
            "https://hubcloud.ist/drive/ghi789".toByteArray(),
        )
        val html = "<script>s('o','$blob',180*1000)</script>"
        assertEquals("https://hubcloud.ist/drive/ghi789", StreamEngine.jsRedirectTarget(html))
    }

    @Test
    fun jsRedirectTarget_garbage() {
        assertNull(StreamEngine.jsRedirectTarget("<html>no redirect</html>"))
        assertNull(StreamEngine.jsRedirectTarget(""))
    }
}
