package Test

import com.vegamovies.LinkNaming
import com.vegamovies.LinkPayload
import com.vegamovies.NexdriveResolver
import com.vegamovies.RawLink
import com.vegamovies.Servers
import com.vegamovies.VegamoviesProvider
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the Vegamovies parsing/naming helpers — JVM-only,
 * no network (gateway expansion + link resolution are covered by the probe
 * scripts in tools/; fixtures here mirror their verified payloads).
 */
class VegamoviesPluginTest {

    private val p = VegamoviesProvider()

    // ───────────────────── search JSON mapping ─────────────────────

    private val searchJson = """
    {"hits":[
      {"document":{
        "category":["2019","480p","720p","Action","Dual Audio Movies"],
        "id":"101","imdb_id":"tt4154796",
        "permalink":"/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/",
        "post_thumbnail":"https://vegamovies.example/wp/p/endgame.jpg",
        "post_title":"Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB] | 1080p [4.3GB] | 2160p 4K",
        "post_type":"post"}},
      {"document":{
        "category":["2026","Netflix","Web Series"],
        "id":"202",
        "permalink":"/download-reacher-2026-season-4-prime-video/",
        "post_title":"Download Reacher : Season 4 (2026) WEB-DL Dual Audio {Hindi-English} 480p | 720p | 1080p",
        "post_type":"post"}}
    ]}"""

    @Test
    fun searchHits_mapToTypedResponsesWithAbsoluteUrls() {
        val res = p.parseSearchHits(searchJson, "https://vegamovies.example")
        assertEquals(2, res.size)
        val movie = res.first { it.url.contains("endgame") }
        assertEquals("https://vegamovies.example/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/", movie.url)
        assertEquals("https://vegamovies.example/wp/p/endgame.jpg", movie.posterUrl)
        assertEquals(2019, (movie as com.lagradost.cloudstream3.MovieSearchResponse).year)
        assertEquals(com.lagradost.cloudstream3.TvType.TvSeries, res.first { it.url.contains("reacher") }.type)
    }

    @Test
    fun searchHits_malformedJsonIsSafe() {
        assertTrue(p.parseSearchHits("not json", "https://x").isEmpty())
        assertTrue(p.parseSearchHits("""{"nope":1}""", "https://x").isEmpty())
    }

    @Test
    fun cleanTitle_stripsPrefixQualitiesAndSizes() {
        val t = p.cleanSearchTitle(
            "Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB] | 1080p [4.3GB] | 2160p 4K"
        )
        assertEquals("Avengers: Endgame (2019)", t)
        assertEquals(
            "Reacher : Season 4 (2026)",
            p.cleanSearchTitle("Download Reacher : Season 4 (2026) WEB-DL Dual Audio {Hindi-English} 480p | 720p | 1080p"),
        )
    }

    @Test
    fun seriesHeuristics() {
        assertTrue(p.isSeries("/download-reacher-2026-season-4-prime-video/", emptyList(), "Reacher : Season 4 (2026)"))
        assertTrue(!p.isSeries("/download-avengers-endgame-2019-hindi-dubbed/", listOf("Action"), "Avengers: Endgame (2019)"))
        assertTrue(p.isSeries("/download-crew-girl-2026-season-1-netflix/", listOf("Web Series"), "Crew Girl (2026)"))
    }

    // ───────────────────── detail page sections ─────────────────────

    /** Movie post: one heading per quality, "Download Now" chips (real markup). */
    private val movieHtml = """
    <html><head><meta property="og:title" content="Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB]"/></head>
    <body><div class="entry-content">
      <p><a href="https://www.imdb.com/title/tt4154796/">👉 IMDb Rating:- 8.4/10</a></p>
      <h3>Movie-SYNOPSIS/PLOT:</h3><p>After the devastating events the universe is in ruins.</p>
      <h2>Screenshots: (Must See Before Downloading)</h2>
      <h5>Avengers: Endgame (2019) {Hindi-English} 480p BluRay [500MB]</h5>
      <p><a href="https://nexdrive.fit/genxfm7847765350/" rel="nofollow">Download Now</a></p>
      <h5>Avengers: Endgame (2019) {Hindi-English} 720p BluRay x264 [1.7GB]</h5>
      <p><a href="https://nexdrive.fit/genxfm7847765355/" rel="nofollow">Download Now</a></p>
      <h3>39 Comments</h3><h3>Leave a Comment</h3><h3>Search Movies &amp; Series</h3>
      <h3>Recent Updates</h3>
      <a href="https://nexdrive.fit/genxfm999000111/">Download Now</a>
    </div></body></html>"""

    @Test
    fun parseDetail_groupsAnchorsUnderQualityHeadings() {
        val doc = Jsoup.parse(movieHtml)
        val s = p.parseDetail(doc)
        assertEquals("tt4154796", s.imdbId)
        assertEquals(2, s.groups.size, "noise anchors after sidebar headings must not join groups")
        val g = s.groups.first()
        assertTrue(g.heading.contains("480p"), g.heading)
        assertEquals("https://nexdrive.fit/genxfm7847765350/", g.links.single().gatewayUrl)
        assertTrue(s.groups.none { it.links.any { l -> l.gatewayUrl.contains("999000") } })
    }

    /** Series post: quality headings with three chip anchors each (real markup). */
    private val seriesHtml = """
    <html><body><div class="entry-content">
      <h1>Download My Bias, My Boss (S01) Prime Video : Dual Audio {Hindi-Korean} K-Drama 480p 720p &amp; 1080p WEB-DL</h1>
      <h3>Season 1 {Hindi-Korean} 480p WEB-DL x264 [200MB/E]</h3>
      <p><a href="https://nexdrive.fit/genxfm784776500208/">⚡ G-Direct [Instant]</a>
         <a href="https://nexdrive.fit/genxfm784776500207/">⚡ V-Cloud [Resumable]</a>
         <a href="https://nexdrive.fit/genxfm784776507313/">⚡ Batch/Zip [2.3GB]</a></p>
      <h3>Season 1 {Hindi-Korean} 720p WEB-DL x264 [800MB/E]</h3>
      <p><a href="https://nexdrive.fit/genxfm784776500214/">⚡ G-Direct [Instant]</a>
         <a href="https://nexdrive.fit/genxfm784776500215/">⚡ V-Cloud [Resumable]</a></p>
      <h3>Leave a Comment</h3>
    </div></body></html>"""

    @Test
    fun parseDetail_seriesChipsTaggedWithServerKindAndSeason() {
        val s = p.parseDetail(Jsoup.parse(seriesHtml))
        assertEquals(2, s.groups.size)
        val g480 = s.groups.first()
        assertEquals(3, g480.links.size)
        assertEquals(1, g480.season, "heading carries Season 1")
        assertEquals(Servers.GDRIVE, g480.links[0].chip)
        assertEquals(Servers.VCLOUD, g480.links[1].chip)
        assertEquals(Servers.ZIP, g480.links[2].chip)
        // Anchor text is NOT a group label — the heading is.
        assertTrue(g480.links.all { it.heading.contains("480p") })
    }

    @Test
    fun parseDetail_pageWithoutLinksIsEmpty() {
        val s = p.parseDetail(Jsoup.parse("<html><body><h1>Nothing</h1><a href='https://x/y'>z</a></body></html>"))
        assertTrue(s.groups.isEmpty())
    }

    // ───────────────────── naming (user spec) ─────────────────────

    @Test
    fun tokens_parseQualityLanguageCodecSize() {
        val toks = LinkNaming.parseTokens(
            "Avengers: Endgame (2019) {Hindi-English} 1080p BluRay x264 [4.3GB]"
        )
        assertTrue(toks.contains("1080p"), toks.toString())
        assertTrue(toks.contains("{Hindi-English}"), toks.toString())
        assertTrue(toks.contains("BluRay") && toks.contains("x264"), toks.toString())
        assertTrue(toks.contains("4.3GB"), toks.toString())
    }

    @Test
    fun tokens_languageOnlyWhenPresent() {
        val noLang = LinkNaming.parseTokens("Movie (2019) 720p WEB-DL x265 [1.2GB]")
        assertTrue(noLang.none { it.startsWith("{") }, noLang.toString())
        assertTrue(noLang.contains("720p"))
    }

    @Test
    fun displayName_streamableBothVersusDownloadable() {
        val both = LinkNaming.displayName(
            RawLink(
                "https://video-downloads.googleusercontent.com/abc",
                Servers.GDRIVE, "Title {Hindi-English} 720p BluRay x264 [1.7GB]", false,
            )
        )
        assertTrue(both.startsWith("G-Drive (Google Drive) [Streamable+Downloadable]"), both)
        assertTrue(both.contains("720p") && both.contains("{Hindi-English}") && both.contains("1.7GB"), both)

        val dl = LinkNaming.displayName(
            RawLink("https://vcloud.fit/abc123xyz", Servers.VCLOUD, "Season 1 {Hindi-Korean} 480p WEB-DL x264 [200MB/E]", true, 1, 2)
        )
        assertTrue(dl.startsWith("V-Cloud [Downloadable]"), dl)
        assertTrue(dl.contains("S01E02"), dl)
        assertTrue(dl.contains("480p"), dl)

        val pack = LinkNaming.displayName(
            RawLink("https://vcloud.fit/xyz789", Servers.ZIP, "Season 1 Batch 22.3GB", true, 1, null)
        )
        assertTrue(pack.startsWith("Batch/Zip (Archive) [Downloadable]"), pack)
    }

    @Test
    fun seasonEpisode_parsers() {
        assertEquals(1 to 2, LinkNaming.seasonEpisodeFrom("Crew Girl S01E02 1080p"))
        assertEquals(2 to 3, LinkNaming.seasonEpisodeFrom("show 2x03 720p"))
        assertEquals(1 to null, LinkNaming.seasonEpisodeFrom("Season 1 {Hindi-Korean} 480p WEB-DL x264 [200MB/E]"))
        assertEquals(null to null, LinkNaming.seasonEpisodeFrom("Avengers (2019) 480p BluRay"))
    }

    // ───────────────────── fastdl reurl extraction ─────────────────────

    @Test
    fun extractDirect_cleartextReurl() {
        val body = """var reurl = "https://fastdl.zip/dl.php?link=https://video-downloads.googleusercontent.com/ABCdef123";"""
        assertEquals(
            "https://video-downloads.googleusercontent.com/ABCdef123",
            NexdriveResolver.extractDirect(body),
        )
    }

    @Test
    fun extractDirect_stringArrayAndEncodedVariants() {
        val inArray = """['https://fastdl.zip/dl.php?link=https://video-downloads.googleusercontent.com/XyZ_9-+==']"""
        assertNotNull(NexdriveResolver.extractDirect(inArray))
        val encoded = "location='https://fastdl.eu/dl.php?link=https%3A%2F%2Fvideo-downloads.googleusercontent.com%2Ftok123'"
        assertEquals(
            "https://video-downloads.googleusercontent.com/tok123",
            NexdriveResolver.extractDirect(encoded),
        )
        assertNull(NexdriveResolver.extractDirect("<html>captcha wall</html>"))
    }

    // ───────────────────── payload round-trip ─────────────────────

    @Test
    fun payload_jsonRoundTripPreservesAllFields() {
        val payload = LinkPayload(
            "https://vegamovies.example/download-x/",
            listOf(
                com.vegamovies.PayloadLink("https://fastdl.zip/embed?download=T1", Servers.GDRIVE, "{Hindi} 720p x264 [1.7GB]", 0, false, 1, 2),
                com.vegamovies.PayloadLink("https://vcloud.fit/z", Servers.VCLOUD, "heading", 1, false),
                com.vegamovies.PayloadLink("https://nexdrive.fit/genxfm1/", Servers.GATE, "", 0, true, null, null),
            ),
        )
        val back = LinkPayload.fromJson(payload.toJson())!!
        assertEquals(payload.pageUrl, back.pageUrl)
        assertEquals(payload.links.size, back.links.size)
        val l0 = back.links[0]
        assertEquals(Servers.GDRIVE, l0.kind)
        assertEquals(1, l0.season)
        assertEquals(2, l0.episode)
        assertTrue(back.links[2].isGateway) // gateway flag survives the round-trip
    }
}
