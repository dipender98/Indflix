package Test

import com.hdhub4u.B64
import com.hdhub4u.Card
import com.hdhub4u.cleanTitle
import com.hdhub4u.encodeQuery
import com.hdhub4u.isSeriesTitle
import com.hdhub4u.liveUrl
import com.hdhub4u.parseCards
import com.hdhub4u.parseHlsLinks
import com.hdhub4u.parsePostLinks
import com.hdhub4u.parseSubtitles
import com.hdhub4u.parseYear
import com.hdhub4u.passesQualityFloor
import com.hdhub4u.qualityFromLabel
import com.hdhub4u.resolutionFromUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for the HDHub4u pure parsers (no network, no Android).
 * Fixtures are snippets recorded from the live probes (tools/hdhub4u_probe.py,
 * verified against new5.hdhub4u.cl on 2026-09-11).
 */
class HDHub4uParsersTest {

    // ── base64 (host-API payload decoding) ──────────────────────────

    @Test
    fun `b64 decodes the gateway c payload to the live url`() {
        // h4.suncdn.org/host returned c=<this> on 2026-09-11
        val c = "aHR0cHM6Ly9uZXc1LmhkaHViNHUuY2wvP3V0bT1tbjE="
        assertEquals("https://new5.hdhub4u.cl/?utm=mn1", B64.decode(c))
    }

    @Test
    fun `b64 handles url-safe alphabet and junk`() {
        assertEquals("a+b/c", B64.decode("YStiL2M="))
        assertEquals("", B64.decode("!!!"))
        assertEquals("", B64.decode(""))
    }

    // ── domain rewriting (rotation self-heal) ───────────────────────

    @Test
    fun `liveUrl rewrites a stale hdhub4u host to the cached domain`() {
        // No resolve ran in the JVM test, so the cached domain is the SEED
        // (hdhub4u.ag) — a stale mirror URL must be rewritten onto it.
        val out = liveUrl("https://old5.hdhub4u.in/some-post/")
        assertEquals("https://hdhub4u.ag/some-post/", out)
    }

    @Test
    fun `liveUrl leaves foreign hosts alone`() {
        assertEquals("https://drive.google.com/file/d/123/view",
            liveUrl("https://drive.google.com/file/d/123/view"))
        assertEquals("https://hubcdn.sbs/file/abc123",
            liveUrl("https://hubcdn.sbs/file/abc123"))
    }

    // ── card grid (home / category / search) ────────────────────────

    @Test
    fun `parseCards extracts poster href and title from the li_thumb grid`() {
        val html = """
            <ul class="recent-movies">
            <li class="thumb col-md-2 col-sm-4 col-xs-6"><figure>
            <img src="https://i1.wp.com/image.tmdb.org/t/p/w342/abc.jpg?ssl=1" alt="x">
            <a href="https://new5.hdhub4u.cl/haiwaan-2026-hindi-line-hdtc-full-movie/" data-wpel-link="internal">
            <div class="thumb-hover"></div></a></figure><figcaption>
            <a href="https://new5.hdhub4u.cl/haiwaan-2026-hindi-line-hdtc-full-movie/" data-wpel-link="internal"><p>Haiwaan (2026) HQ-HDTC Hindi (LiNE) 1080p 720p &amp; 480p [x264/HEVC] | Full Movie</p></a>
            </figcaption></li></ul>
        """.trimIndent()
        val cards = parseCards(html)
        assertEquals(1, cards.size)
        val c: Card = cards[0]
        assertEquals("https://new5.hdhub4u.cl/haiwaan-2026-hindi-line-hdtc-full-movie/", c.href)
        assertTrue(c.poster.contains("w342/abc.jpg"))
        assertEquals(
            "Haiwaan (2026) HQ-HDTC Hindi (LiNE) 1080p 720p & 480p [x264/HEVC] | Full Movie",
            c.title,
        )
    }

    @Test
    fun `parseCards tolerates null and empty html`() {
        assertTrue(parseCards(null).isEmpty())
        assertTrue(parseCards("").isEmpty())
    }

    // ── titles ──────────────────────────────────────────────────────

    @Test
    fun `cleanTitle strips quality brackets and pipe trailer`() {
        assertEquals(
            "Haiwaan (2026) HQ-HDTC Hindi (LiNE) 1080p 720p & 480p",
            cleanTitle("Haiwaan (2026) HQ-HDTC Hindi (LiNE) 1080p 720p & 480p [x264/HEVC] | Full Movie"),
        )
    }

    @Test
    fun `cleanTitle keeps titles without a pipe`() {
        assertEquals("Salmokji (2026) WEB-DL", cleanTitle("Salmokji (2026) WEB-DL"))
    }

    @Test
    fun `isSeriesTitle detects season and episode markers`() {
        assertTrue(isSeriesTitle("The Revolutionaries (2026) Season 1 ALL Episodes"))
        assertTrue(isSeriesTitle("Panchayat S3 Web Series"))
        assertFalse(isSeriesTitle("Haiwaan (2026) Full Movie"))
    }

    @Test
    fun `parseYear finds the first year token`() {
        assertEquals(2026, parseYear("Haiwaan (2026) HDRip"))
        assertEquals(null, parseYear("No year here"))
        assertEquals(null, parseYear(null))
    }

    // ── quality tags ────────────────────────────────────────────────

    @Test
    fun `qualityFromLabel maps anchor labels to heights`() {
        assertEquals(2160, qualityFromLabel("2160p 4K [8.5GB]"))
        assertEquals(1080, qualityFromLabel("1080p x264 [3.1GB]"))
        assertEquals(720, qualityFromLabel("720p HEVC [950MB]"))
        assertEquals(480, qualityFromLabel("480p [560MB]"))
    }

    @Test
    fun `resolutionFromUrl reads a height from file names`() {
        assertEquals(1080, resolutionFromUrl("pub-abc.r2.dev/Some.Movie.1080p.Hindi.mkv"))
        assertEquals(0, resolutionFromUrl("pub-abc.r2.dev/86dca4a3"))
        assertEquals(0, resolutionFromUrl(null))
    }

    @Test
    fun `passesQualityFloor drops fixed sub-720p keeps adaptive and unknown`() {
        assertFalse(passesQualityFloor(isAdaptive = false, height = 480))
        assertTrue(passesQualityFloor(isAdaptive = false, height = 720))
        assertTrue(passesQualityFloor(isAdaptive = false, height = 0))
        assertTrue(passesQualityFloor(isAdaptive = true, height = 480))
    }

    // ── post link parsing (episode grouping) ────────────────────────

    @Test
    fun `parsePostLinks groups links by episode heading and skips meta hosts`() {
        val html = """
            <main class="page-body"><h1>Series (2026) Season 1</h1>
            <h4>EPISODE 1</h4>
            <h3><a href="https://hubdrive.tips/file/111">Drive</a></h3>
            <h3><a href="https://hubcdn.sbs/file/HyEwQWLlW9l4GUqDy6OVbWbDu">Instant</a></h3>
            <h4>EPISODE 2</h4>
            <h3><a href="https://hubdrive.tips/file/222">Drive</a></h3>
            <a href="https://catimages.org/screenshot.jpg"><img src="x"></a>
            <a href="https://imdb.com/title/tt32378175/">IMDb</a>
            </main>
        """.trimIndent()
        val links = parsePostLinks(html)
        // catimages/imdb anchors are skipped entirely
        assertEquals(3, links.size)
        assertEquals(1, links[0].episode)
        assertEquals("hubdrive.tips", links[0].host)
        assertEquals(1, links[1].episode)
        assertEquals(2, links[2].episode)
    }

    @Test
    fun `parsePostLinks keeps pack links before the first episode heading`() {
        val html = """
            <main class="page-body"><h2>DOWNLOAD LINKS</h2>
            <h3><a href="https://hubdrive.tips/file/999">1080p Pack [12GB]</a></h3>
            <h4>EPISODE 1</h4>
            <h3><a href="https://hubcdn.sbs/file/ep1">Instant</a></h3>
            </main>
        """.trimIndent()
        val links = parsePostLinks(html)
        assertEquals(2, links.size)
        assertEquals(-1, links[0].episode)
        assertEquals("1080p Pack [12GB]", links[0].label)
        assertEquals(1, links[1].episode)
    }

    // ── hdstream4u unpacked player parsing ─────────────────────────

    @Test
    fun `parseHlsLinks prefers hls4 then hls2 then hls3`() {
        val unpacked = """var links={"hls3":"https://cdn.example/hls3/master.txt","hls2":"https://cdn.example/hls2/master.m3u8?t=A","hls4":"https://cdn.example/hls4/master.m3u8?t=B"}"""
        assertEquals(
            listOf("https://cdn.example/hls4/master.m3u8?t=B", "https://cdn.example/hls2/master.m3u8?t=A", "https://cdn.example/hls3/master.txt"),
            parseHlsLinks(unpacked),
        )
    }

    @Test
    fun `parseHlsLinks falls back to hls2 when hls4 is absent`() {
        val unpacked = """var links={"hls3":"https://cdn.example/hls3/master.txt","hls2":"https://cdn.example/hls2/master.m3u8?t=A"}"""
        assertEquals("https://cdn.example/hls2/master.m3u8?t=A", parseHlsLinks(unpacked).first())
    }

    @Test
    fun `parseSubtitles extracts site-hosted vtt tracks`() {
        val unpacked = """tracks:[{file:"https://sub.acek-cdn.com/vtt/01/08594/6d9bu0i1qw8y_eng.vtt",label:"English"},{file:"https://sub.acek-cdn.com/vtt/01/08594/6d9bu0i1qw8y_hin.vtt",label:"Hindi"}]"""
        val subs = parseSubtitles(unpacked)
        assertEquals(2, subs.size)
        assertEquals("English" to "https://sub.acek-cdn.com/vtt/01/08594/6d9bu0i1qw8y_eng.vtt", subs[0])
        assertEquals("Hindi" to "https://sub.acek-cdn.com/vtt/01/08594/6d9bu0i1qw8y_hin.vtt", subs[1])
    }

    // ── typesense query builder ─────────────────────────────────────

    @Test
    fun `encodeQuery builds the site search url`() {
        val url = encodeQuery("https://search.example/search", mapOf("q" to "haiwaan", "page" to "1"))
        assertEquals("https://search.example/search?q=haiwaan&page=1", url)
    }
}
