package Test

import com.cinevood.DomainResolver
import com.cinevood.PostParser
import com.cinevood.TitleParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CineVoodParserTest {

    @Test
    fun movieTitleParsesNameYearLangsQualities() {
        val t = TitleParser.parse(
            "Download Toxic (2026) V2 Hdtc Multi Audio (Hindi + Tamil + Telugu + Kannada) " +
                "Full Movie 480P [600Mb] | 720P [1.5Gb] | 1080P [2.5Gb] X264 – *No Ads*"
        )
        assertEquals("Toxic", t.name)
        assertEquals(2026, t.year)
        assertTrue(t.isSeries.not())
        assertEquals(4, t.languages.size)
        assertTrue("Hindi" in t.languages && "Kannada" in t.languages)
        assertEquals(listOf(1080, 720, 480), t.qualities)
        assertFalse(t.isAdult)
        assertFalse(t.isTrailer)
    }

    @Test
    fun dualAudioTitle() {
        val t = TitleParser.parse(
            "Download Dhamaal 4 (2026) Netflix Web-Dl {Hindi Dd5.1} Full Movie 480P [400Mb] | 720P [1.1Gb]"
        )
        assertEquals("Dhamaal 4", t.name)
        assertEquals(2026, t.year)
        assertEquals(listOf("Hindi"), t.languages)
        assertTrue(t.sourceTag!!.uppercase().contains("WEB-DL"))
    }

    @Test
    fun seriesTitleIsSeriesWithSeasons() {
        val t = TitleParser.parse(
            "Download Chumbak (Season 1) Hindi Netflix Complete Web Series 480P | 720P | 1080P Web-Dl"
        )
        assertEquals("Chumbak", t.name)
        assertTrue(t.isSeries)
        assertTrue(1 in t.seasons)
    }

    @Test
    fun multiSeasonTitle() {
        val t = TitleParser.parse(
            "Download Heroes Season 1 - 4 Hindi {Hindi-English} Complete WEB-DL 480P 720P 1080P"
        )
        assertEquals("Heroes", t.name)
        assertTrue(t.isSeries)
        assertTrue(t.seasons.containsAll(listOf(1, 4)))
        assertEquals(listOf("Hindi", "English"), t.languages)
    }

    @Test
    fun adultAndTrailerFiltered() {
        assertTrue(TitleParser.parse("[18+] Taking It All In (2026) English ORG HDRip 720P").isAdult)
        assertTrue(TitleParser.parse("Movie Official Trailer 2026 WEB-DL 1080P").isTrailer)
    }

    @Test
    fun groupLabelParsesQualityLangSeasonEpRange() {
        val g1 = TitleParser.parseGroup(
            "Chumbak S01 [Episode 01-08] Complete Hindi WEB-DL 480p x264 [460MB]",
            "https://mobilejsr.rest/genxfm784776507376/"
        )
        assertNotNull(g1)
        assertEquals(480, g1!!.quality)
        assertEquals(1, g1.season)
        assertEquals(1, g1.episodeFrom)
        assertEquals(8, g1.episodeTo)
        assertEquals(listOf("Hindi"), g1.languages)

        val g2 = TitleParser.parseGroup(
            "Toxic: A Fairytale for Grown-Ups (2026) (Hindi Multi-Audio) V2-HDTC 1080p x264 [4GB]",
            "https://mobilejsr.rest/genxfm784776504693/"
        )
        assertNotNull(g2)
        assertEquals(1080, g2!!.quality)
        assertTrue(g2.languages.isNotEmpty())
    }

    @Test
    fun groupWithoutResolutionIgnored() {
        assertNull(TitleParser.parseGroup("Some random text", "https://mobilejsr.rest/genx/"))
    }

    // ---- PostParser -----------------------------------------------------

    private val groupsHtml = """
        <h2 class="mfx-download-lable-title">Download Links:</h2>
        <div class="mfx-download-group">
        <h3 class="mfx-quality-title">Chumbak S01 [Episode 01-08] Complete Hindi WEB-DL 480p x264 [460MB]</h3>
        <div class="mfx-download-buttons"><a class="mfx-download-link" href="https://mobilejsr.rest/genxfm784776507376/" target="_blank" rel="nofollow noopener"><button class="mfx-download-btn mfx-btn-1"><span>Download Now</span></button></a>
        </div>
        </div>
        <div class="mfx-download-group">
        <h3 class="mfx-quality-title">Chumbak S01 [Episode 01-08] Complete Hindi WEB-DL 720p x264 [1.6GB]</h3>
        <div class="mfx-download-buttons"><a class="mfx-download-link" href="https://mobilejsr.rest/genxfm784776507377/" target="_blank" rel="nofollow noopener"><button class="mfx-download-btn mfx-btn-1"><span>Download Now</span></button></a>
        </div>
        </div>
        <div class="mfx-faq-wrap"><h2>FAQ</h2></div>
        <p>See on IMDb: <a href="https://www.imdb.com/title/tt0813715/">IMDb</a></p>
    """.trimIndent()

    @Test
    fun postGroupsAllExtracted() {
        val post = PostParser.parsePost(groupsHtml)
        assertEquals(2, post.groups.size)
        assertEquals(listOf("https://mobilejsr.rest/genxfm784776507376/", "https://mobilejsr.rest/genxfm784776507377/"),
            post.groups.map { it.url })
        assertEquals(480, post.groups[0].quality)
        assertEquals(720, post.groups[1].quality)
        assertEquals("tt0813715", post.imdbId)
    }

    @Test
    fun flatFallbackWhenClassesMissing() {
        val html = """<a href="https://mobilejsr.rest/genxfm78/">Mirzapur S01 1080p WEB-DL [1GB]</a>"""
        val post = PostParser.parsePost(html)
        assertEquals(1, post.groups.size)
        assertEquals(1080, post.groups[0].quality)
    }

    // ---- DomainResolver ---------------------------------------------------

    @Test
    fun bannerDomainsExtractedAndNormalized() {
        val html = """<p>Our New Domain is <b>CineVoodc.ltd</b> || use
            https://cinevoods.top always! Also <a href="https://www.cinevood.loan/">cinevood.loan</a></p>"""
        val doms = DomainResolver.bannerDomains(html)
        assertTrue(doms.contains("https://cinevoodc.ltd"))
        assertTrue(doms.contains("https://cinevoods.top"))
        assertTrue(doms.contains("https://cinevood.loan"))
    }

    @Test
    fun normalizeRejectsGarbage() {
        assertEquals("https://cinevood.loan", DomainResolver.normalizeDomain("WWW.CineVood.loan/"))
        assertNull(DomainResolver.normalizeDomain("not a domain"))
        assertTrue(DomainResolver.isNetworkHost("https://cinevood.loan/download-x/"))
        assertFalse(DomainResolver.isNetworkHost("https://evil.example.com/"))
    }

    @Test
    fun rewriteKeepsPath() {
        val to = DomainResolver.rewriteTo("https://cinevood.ltd/download-x/", "https://cinevood.loan")
        assertEquals("https://cinevood.loan/download-x/", to)
    }
}
