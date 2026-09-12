package Test

import com.vegamovies.NexdriveResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the Vegamovies parsing helpers. Mirrors the
 * Multimovies test style: JVM-only, no network, no Android runtime.
 *
 * NOTE: parse helpers are internal members of VegamoviesProvider; unit tests
 * compile in the same module so `internal` is visible.
 */
class VegamoviesParseTest {

    // ───── ts-search.php JSON → SearchResponse mapping ─────

    private val searchJson = """
    {"hits":[
      {"document":{
        "category":["2019","480p","720p","Action","Dual Audio Movies"],
        "id":"101","imdb_id":"tt4154796",
        "permalink":"/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/",
        "post_thumbnail":"https://img.example/endgame.jpg",
        "post_title":"Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB] | 1080p [4.3GB] | 2160p 4K",
        "post_type":"post"}},
      {"document":{
        "category":["2026","Netflix","Web Series"],
        "id":"202",
        "permalink":"/download-reacher-2026-season-4-prime-video/",
        "post_thumbnail":"https://img.example/reacher.jpg",
        "post_title":"Download Reacher : Season 4 (2026) WEB-DL Dual Audio {Hindi-English}",
        "post_type":"post"}}
    ]}"""

    // ───── detail page fixtures (condensed real structure) ─────

    private val movieDoc = """
    <html><head><meta property="og:image" content="https://img.example/poster.jpg"/></head>
    <body>
      <h1>Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB]</h1>
      <div class="entry-content">
        <p><a href="https://www.imdb.com/title/tt4154796/">👉 IMDb Rating:- 8.4/10</a></p>
        <p>Movie Name: Avengers: Endgame<br/>Release Year: 2019<br/>Language: Hindi ORG + English
        Subtitle: YES<br/>Size: 500MB || 1.7GB<br/>Quality: 480p || 720p<br/>Format: MKV</p>
        <h3>Movie-SYNOPSIS/PLOT:</h3>
        <p>After the devastating events, the universe is in ruins.</p>
        <h2>Screenshots: (Must See Before Downloading)</h2>
        <h5>Avengers: Endgame (2019) {Hindi-English} 480p BluRay [500MB]</h5>
        <a href="https://nexdrive.fit/genxfm7847765350/">Download Now</a>
        <h5>Avengers: Endgame (2019) {Hindi-English} 720p BluRay HEVC [750MB]</h5>
        <a href="https://nexdrive.fit/genxfm7847765353/">Download Now</a>
        <h5>Avengers: Endgame (2019) {Hindi-English} 1080p BluRay x264 [4.3GB]</h5>
        <a href="https://nexdrive.fit/genxfm784776107220/">Download Now</a>
      </div>
    </body></html>"""

    private val seriesDoc = """
    <html><head><meta property="og:image" content="https://img.example/crew.jpg"/></head>
    <body>
      <h1>Download Crew Girl (2026) Season 1 Dual-Audio {Hindi-English} NETFLiX-Series 480p 720p &amp; 1080p</h1>
      <div class="entry-content">
        <h3>Episode 01</h3>
        <h5>Crew Girl S01E01 1080p WEB-DL</h5>
        <a href="https://nexdrive.fit/genxfm9000000001/">Download Now</a>
        <h3>Episode 02</h3>
        <h5>Crew Girl S01E02 1080p WEB-DL</h5>
        <a href="https://nexdrive.fit/genxfm9000000002/">Download Now</a>
        <h5>Crew Girl Season 1 Pack 1080p</h5>
        <a href="https://nexdrive.fit/genxfm9000000009/">Download Now</a>
      </div>
    </body></html>"""

    private val provider = com.vegamovies.VegamoviesProvider()

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    fun `cleanSearchTitle strips download prefix and qualities`() {
        val clean = provider_cleanTitle(
            "Download Avengers: Endgame (2019) Dual Audio {Hindi-English} 480p [500MB] | 720p [1.7GB] | 1080p [4.3GB] | 2160p 4K"
        )
        assertTrue(clean.startsWith("Avengers: Endgame (2019)"), "got: $clean")
        assertTrue(!clean.contains("480p"), "qualities leaked: $clean")
    }

    @Test
    fun `parseSearchHits maps movie and series with urls and posters`() {
        val res = provider_parseSearchHits(searchJson, "https://vegamovies.example")
        assertEquals(2, res.size, "hits lost")
        val movie = res.first { it.url.contains("endgame") }
        assertEquals("https://vegamovies.example/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/", movie.url)
        assertEquals("https://img.example/endgame.jpg", movie.posterUrl)
        assertTrue(movie.name.startsWith("Avengers: Endgame"), "name: ${movie.name}")
        val series = res.first { it.url.contains("reacher") }
        assertEquals(com.lagradost.cloudstream3.TvType.TvSeries, series.type)
    }

    @Test
    fun `parseDetail extracts links imdb year and movie flags`() {
        val doc = org.jsoup.Jsoup.parse(movieDoc)
        val d = provider_parseDetail(doc, "https://vegamovies.example/download-avengers-endgame/")!!
        assertEquals(3, d.links.size, "download groups lost: ${d.links.map { it.url }}")
        assertTrue(d.links.all { it.url.contains("genxfm") })
        assertTrue(d.links.first().label.contains("480p"), "label: ${d.links.first().label}")
        assertEquals("tt4154796", d.imdbId)
        assertEquals(8.4, d.imdbRating)
        assertEquals(2019, d.year)
        assertTrue(!d.isSeries)
        assertNotNull(d.plot)
        assertTrue(d.plot!!.contains("devastating"), "plot: ${d.plot}")
    }

    @Test
    fun `parseDetail groups episodes and packs`() {
        val doc = org.jsoup.Jsoup.parse(seriesDoc)
        val d = provider_parseDetail(doc, "https://vegamovies.example/download-crew-girl/")!!
        assertTrue(d.isSeries)
        assertEquals(2, d.episodes.size, "eps: ${d.episodes}")
        val (s1, e1, l1) = d.episodes.first()
        assertEquals(1, s1); assertEquals(1, e1)
        assertTrue(l1.first().url.contains("9000000001"))
        assertEquals(1, d.packLinks.size)
        assertTrue(d.packLinks.first().url.contains("9000000009"))
    }

    @Test
    fun `parseSearchHits ignores malformed json`() {
        assertTrue(provider_parseSearchHits("not json", "https://x").isEmpty())
        assertTrue(provider_parseSearchHits("""{"nope":1}""", "https://x").isEmpty())
    }

    @Test
    fun `parseDetail returns null for page without genxfm links`() {
        val doc = org.jsoup.Jsoup.parse("<html><body><h1>Nothing here</h1><a href='https://x/y'>Download Now</a></body></html>")
        assertNull(provider_parseDetail(doc, "https://x/y"))
    }

    @Test
    fun `reurl regex finds dl php link parameter`() {
        val sample = "var reurl = \"https://fastdl.zip/dl.php?link=https://video-downloads.googleusercontent.com/ABCdef123==\";"
        val m = NexdriveResolver.REURL_REGEX.find(sample)
        assertNotNull(m)
        assertEquals("https://video-downloads.googleusercontent.com/ABCdef123==", m!!.groupValues[1])
    }

    @Test
    fun `direct regex matches gdrive media only`() {
        assertTrue(NexdriveResolver.DIRECT_REGEX.matches("https://video-downloads.googleusercontent.com/Xy_9+=="))
        assertTrue(!NexdriveResolver.DIRECT_REGEX.containsMatchIn("https://drive.google.com/x"))
    }

    @Test
    fun `series detection heuristics`() {
        assertTrue(provider_isSeries("/download-reacher-2026-season-4/", emptyList(), "Reacher Season 4"))
        assertTrue(!provider_isSeries("/download-avengers-endgame-2019-hindi-dubbed/", listOf("Action", "2019"), "Avengers Endgame (2019)"))
        assertTrue(provider_isSeries("/x/", listOf("Web Series"), "Something"))
    }

    // ── thin wrappers: the helpers are internal on the provider ──
    private fun provider_cleanTitle(raw: String) = provider.cleanSearchTitle(raw)
    private fun provider_parseSearchHits(json: String, base: String) = provider.parseSearchHits(json, base)
    private fun provider_parseDetail(doc: org.jsoup.nodes.Document, url: String) = provider.parseDetail(doc, url)
    private fun provider_isSeries(p: String, c: List<String>, t: String) = provider.isSeries(p, c, t)
}
