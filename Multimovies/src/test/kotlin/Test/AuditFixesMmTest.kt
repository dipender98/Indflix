package Test

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.multimovies.LinkCache
import com.multimovies.MultiSourcePuller
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
        assertTrue(got != null && got.first.size == 1)
    }

    @Test
    fun bracketTag_trailingLanguageOnly() {
        assertEquals("Hindi", MultiSourcePuller.bracketTag(link("Server (Hindi)", "https://x/a.m3u8")))
        assertEquals("Multi", MultiSourcePuller.bracketTag(link("Server-2 (Multi)", "https://x/b.m3u8")))
        assertEquals("", MultiSourcePuller.bracketTag(link("Server Hindi", "https://x/c.m3u8")))
        assertEquals("", MultiSourcePuller.bracketTag(link("Server", "https://x/d.m3u8")))
    }
}
