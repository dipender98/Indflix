package Test

import com.cinevood.CineVoodGate
import com.cinevood.DomainResolver
import com.cinevood.PostParser
import com.cinevood.SharedServices
import com.cinevood.TitleParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    // ---- debug-plan fixes (v2) ---------------------------------------------

    @Test
    fun plotBoxParsed() {
        val html = """<div class="mfx-plot-box">Paul Atreides unites with the
            Fremen while on a warpath of revenge.</div><p>Download stuff</p>"""
        val plot = PostParser.parsePlot(html)
        assertNotNull(plot)
        assertTrue(plot!!.contains("Paul Atreides"))
        assertNull(PostParser.parsePlot("<div>short</div>"))
    }

    @Test
    fun firstImageParsedAsPosterFallback() {
        val html = """<div class="mfx-screenshots-grid"><img
            data-src="https://imghost.rest/x/s1.webp"></div>"""
        assertEquals("https://imghost.rest/x/s1.webp", PostParser.parseFirstImage(html))
        assertNull(PostParser.parseFirstImage("<p>no img</p>"))
    }

    @Test
    fun gateHrefFallbackWithoutMfxClasses() {
        val html = """<div class="dw-btn-box"><a href="https://newgate.rest/genxfm999/">
            Toxic 2026 Hindi V2-HDTC 720p x264 [990MB]</a></div>"""
        val groups = PostParser.parseDownloadGroups(html)
        assertEquals(1, groups.size)
        assertEquals(720, groups[0].quality)
    }

    @Test
    fun gateDetectedByPathPatternToo() {
        val gate = CineVoodGate({ "https://cinevood.loan" })
        assertTrue(gate.isGate("https://mobilejsr.rest/genxfm784776504721/"))
        assertTrue(gate.isGate("https://newgate.rest/genxfm999/")) // rotated domain
        assertFalse(gate.isGate("https://drive.google.com/file/d/abc1234567/view"))
    }

    @Test
    fun jsRedirectTargetsExtracted() {
        assertEquals(
            "https://gdflix.io/file/abc",
            CineVoodGate.jsRedirectUrl("""<script>window.location.href="https://gdflix.io/file/abc";</script>""")
        )
        assertEquals(
            "https://gdflix.io/file/xyz",
            CineVoodGate.redirectTarget("""<script>location.replace('https://gdflix.io/file/xyz')</script>""")
        )
        assertEquals(
            "https://ok.rest/go",
            CineVoodGate.redirectTarget("""<meta http-equiv="refresh" content="0;url=https://ok.rest/go">""")
        )
        assertNull(CineVoodGate.redirectTarget("<html><body>nothing here</body></html>"))
    }

    // ---- Cinemeta metadata parsing (F6) --------------------------------------

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "repo root not found" }
        return File(dir, rel)
    }

    @Test
    fun cinemetaMovieFixtureParses() {
        val f = repoFile("tools/fixtures/cinevood/cinemeta_movie_sample.json")
        assertTrue(f.exists(), "fixture missing: $f")
        val meta = SharedServices.parseCinemetaMeta("movie", f.readText())
        assertNotNull(meta)
        assertEquals("tt15239678", meta!!.imdbId)
        assertEquals(693134, meta.tmdbId)
        assertEquals(2024, meta.year)
        assertEquals(167, meta.runtimeMinutes)
        assertEquals(8.4, meta.rating10)
        assertTrue(meta.genres.contains("Action"))
        assertNotNull(meta.poster)
        assertNotNull(meta.backdrop)
        assertNotNull(meta.plot)
        assertTrue(meta.isMovie)
    }

    @Test
    fun cinemetaEmptyMetaIsMiss() {
        assertNull(SharedServices.parseCinemetaMeta("tv", "{}"))
        assertNull(SharedServices.parseCinemetaMeta("tv", "not json"))
    }
}
