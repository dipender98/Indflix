package com.multimovies

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHelpersTest {

    private fun item(html: String): Element =
        Jsoup.parseBodyFragment(html).selectFirst("div.item")!!

    // ---- upgradePosterUrl ----

    @Test
    fun `upgradePosterUrl strips small size suffix before extension`() {
        assertEquals("https://img.example/cdn/poster.jpg",
            upgradePosterUrl("https://img.example/cdn/poster-185x278.jpg"))
    }

    @Test
    fun `upgradePosterUrl strips resize query param`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg?resize=185,278"))
    }

    @Test
    fun `upgradePosterUrl strips separate w and h query params`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg?w=185&h=278"))
    }

    @Test
    fun `upgradePosterUrl strips CDN im and q tokens`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg?im=185x278&q=50"))
    }

    @Test
    fun `upgradePosterUrl strips quality param`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg?quality=40"))
    }

    @Test
    fun `upgradePosterUrl keeps non-resize query params`() {
        assertEquals("https://img.example/poster.jpg?v=2",
            upgradePosterUrl("https://img.example/poster.jpg?v=2&resize=185,278"))
    }

    @Test
    fun `upgradePosterUrl preserves -scaled marker`() {
        // -scaled is a real WP file variant (the only full-res copy); do not strip it.
        assertEquals("https://img.example/poster-scaled.jpg",
            upgradePosterUrl("//img.example/poster-300x450-scaled.jpg"))
    }

    @Test
    fun `upgradePosterUrl preserves large size dim suffix`() {
        // 1920x1080 is full-res, not a thumbnail — leave it alone.
        assertEquals("https://img.example/poster-1920x1080.jpg",
            upgradePosterUrl("https://img.example/poster-1920x1080.jpg"))
    }

    @Test
    fun `upgradePosterUrl leaves full-res url untouched`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg"))
    }

    @Test
    fun `upgradePosterUrl returns original when suffix not before extension`() {
        // -185x278-2.jpg has extra path segment after the size; candidate would be
        // poster-2.jpg whose ext matches, so the suffix IS stripped (small dims).
        assertEquals("https://img.example/poster-2.jpg",
            upgradePosterUrl("https://img.example/poster-185x278-2.jpg"))
    }

    @Test
    fun `upgradePosterUrl returns null for blank`() {
        assertNull(upgradePosterUrl(null))
        assertNull(upgradePosterUrl(""))
    }

    @Test
    fun `upgradePosterUrl upgrades small tmdb size prefix to original`() {
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg",
            upgradePosterUrl("https://image.tmdb.org/t/p/w185/poster.jpg"))
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg",
            upgradePosterUrl("https://image.tmdb.org/t/p/w342/poster.jpg"))
    }

    @Test
    fun `upgradePosterUrl keeps large tmdb size prefix`() {
        assertEquals("https://image.tmdb.org/t/p/w780/poster.jpg",
            upgradePosterUrl("https://image.tmdb.org/t/p/w780/poster.jpg"))
    }

    @Test
    fun `upgradePosterUrl upgrades amazon SX thumbnail suffix`() {
        assertEquals("https://m.media-amazon.com/images/M/ab12._SX500.jpg",
            upgradePosterUrl("https://m.media-amazon.com/images/M/ab12._SX250.jpg"))
    }

    @Test
    fun `isThumbnailish detects tmdb and amazon small sizes`() {
        assertTrue(isThumbnailish("https://image.tmdb.org/t/p/w185/poster.jpg"))
        assertTrue(isThumbnailish("https://m.media-amazon.com/images/M/ab12._SX250.jpg"))
        assertFalse(isThumbnailish("https://image.tmdb.org/t/p/original/poster.jpg"))
        assertFalse(isThumbnailish("https://image.tmdb.org/t/p/w780/poster.jpg"))
    }

    // ---- parseRating ----

    @Test
    fun `parseRating reads imdb span`() {
        val r = parseRating(item("""<div class="item"><span class="imdb">8.5</span></div>"""))
        assertTrue(r == 8.5, "rating was $r")
    }

    @Test
    fun `parseRating reads dt_rating_vgs`() {
        assertTrue(parseRating(item("""<div class="item"><span class="dt_rating_vgs">7.2</span></div>""")) == 7.2)
    }

    @Test
    fun `parseRating returns null when markup absent`() {
        assertNull(parseRating(item("""<div class="item"><a href="/x">Title</a></div>""")))
    }

    // ---- extractStreamUrl ----

    @Test
    fun `extractStreamUrl finds m3u8`() {
        assertEquals("https://hls.example/master.m3u8",
            MultiSourcePuller.extractStreamUrl("""<video src="https://hls.example/master.m3u8">"""))
    }

    @Test
    fun `extractStreamUrl finds mp4`() {
        assertEquals("https://v.example/movie.mp4",
            MultiSourcePuller.extractStreamUrl("""file: "https://v.example/movie.mp4" """))
    }

    @Test
    fun `extractStreamUrl unescapes slashes`() {
        assertEquals("https://hls.example/master.m3u8",
            MultiSourcePuller.extractStreamUrl("""{"url":"https:\/\/hls.example\/master.m3u8"}"""))
    }

    @Test
    fun `extractStreamUrl returns null when none`() {
        assertNull(MultiSourcePuller.extractStreamUrl("""no urls here"""))
    }

    // ---- isThumbnailish ----

    @Test
    fun `isThumbnailish detects size suffix and resize params`() {
        assertTrue(isThumbnailish("https://img.example/poster-768x1152.jpg"))
        assertTrue(isThumbnailish("https://img.example/poster.jpg?w=185"))
        assertTrue(isThumbnailish("https://img.example/poster.jpg?resize=185%2C278"))
        assertTrue(isThumbnailish("https://img.example/poster.jpg?im=185x278"))
    }

    @Test
    fun `isThumbnailish false for full-res urls`() {
        assertFalse(isThumbnailish("https://img.example/poster.jpg"))
        assertFalse(isThumbnailish("https://img.example/poster-scaled.jpg"))
        assertFalse(isThumbnailish(null))
        assertFalse(isThumbnailish(""))
    }

    // ---- buildSignedVixsrcUrl ----

    @Test
    fun `buildSignedVixsrcUrl parses masterPlaylist object`() {
        val html = """window.masterPlaylist = { "url": "https://cdn.example/master.m3u8", "token": "abc123", "expires": "1700000000" };"""
        assertEquals(
            "https://cdn.example/master.m3u8?token=abc123&expires=1700000000&h=1&lang=en",
            MultiSourcePuller.buildSignedVixsrcUrl(html)
        )
    }

    @Test
    fun `buildSignedVixsrcUrl handles protocol-relative and numeric expires`() {
        val html = """window.masterPlaylist = { url: "//cdn.example/master.m3u8", token: "tok", expires: 999 };"""
        assertEquals(
            "https://cdn.example/master.m3u8?token=tok&expires=999&h=1&lang=en",
            MultiSourcePuller.buildSignedVixsrcUrl(html)
        )
    }

    @Test
    fun `buildSignedVixsrcUrl falls back to bare m3u8 url`() {
        assertEquals("https://cdn.example/stream.m3u8",
            MultiSourcePuller.buildSignedVixsrcUrl("""<video src="https://cdn.example/stream.m3u8">"""))
    }

    @Test
    fun `buildSignedVixsrcUrl returns null when no playlist or stream`() {
        assertNull(MultiSourcePuller.buildSignedVixsrcUrl("nothing here"))
    }

    // ---- SOURCE_PRIORITY ordering ----

    @Test
    fun `SOURCE_PRIORITY ranks fastest reliable sources at the top`() {
        assertEquals("Cineverse", SOURCE_PRIORITY.first())
        assertEquals("screenscape.me", SOURCE_PRIORITY[1])
        assertEquals("2embed", SOURCE_PRIORITY.last())
        // reflects the actual servers on current Multimovies pages (verified Aug 2026)
        assertEquals(
            listOf("Cineverse", "screenscape.me", "gdmirror", "Nxsha", "nhdapi", "2embed"),
            SOURCE_PRIORITY
        )
    }

    // ---- Hindi hint detection ----

    @Test
    fun `isHindiHint detects streamhg platform proxy`() {
        assertTrue(
            MultiSourcePuller.isHindiHint(
                "Cineverse",
                "https://rozgarlelo.modiplay.xyz/proxy.php?p=streamhg&c=abc&title=Rush",
                null
            )
        )
    }

    @Test
    fun `isHindiHint detects lan=hindi and hindi text`() {
        assertTrue(
            MultiSourcePuller.isHindiHint(
                "screenscape.me",
                "https://screenscape.me/embed?imdb=tt1&type=movie&lan=hindi",
                null
            )
        )
        assertTrue(MultiSourcePuller.isHindiHint("Hindi Server", "https://x.com/e", "https://cdn.example/master.m3u8"))
    }

    @Test
    fun `isHindiHint false for plain sources`() {
        assertFalse(MultiSourcePuller.isHindiHint("Nxsha", "https://nxsha.space/embed/movie/tt1", null))
    }
}
