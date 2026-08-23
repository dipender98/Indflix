package com.multimovies

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderHelpersTest {

    private fun item(html: String): Element =
        Jsoup.parseBodyFragment(html).selectFirst("div.item")!!

    // ---- upgradePosterUrl ----

    @Test
    fun `upgradePosterUrl strips Dash W x H suffix before extension`() {
        assertEquals("https://img.example/cdn/poster.jpg",
            upgradePosterUrl("https://img.example/cdn/poster-185x278.jpg"))
    }

    @Test
    fun `upgradePosterUrl strips suffix anywhere in path`() {
        // -185x278 is the size suffix (stripped); -2 is the real full-res filename variant.
        assertEquals("https://img.example/poster-2.jpg",
            upgradePosterUrl("https://img.example/poster-185x278-2.jpg"))
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
    fun `upgradePosterUrl handles protocol-relative and -scaled`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("//img.example/poster-300x450-scaled.jpg"))
    }

    @Test
    fun `upgradePosterUrl leaves full-res url untouched`() {
        assertEquals("https://img.example/poster.jpg",
            upgradePosterUrl("https://img.example/poster.jpg"))
    }

    @Test
    fun `upgradePosterUrl returns null for blank`() {
        assertNull(upgradePosterUrl(null))
        assertNull(upgradePosterUrl(""))
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

    // ---- SOURCE_PRIORITY ordering ----

    @Test
    fun `SOURCE_PRIORITY ranks GDMIRROR and screenscape_me at the top`() {
        val idxGdm = SOURCE_PRIORITY.indexOf("GDMIRROR")
        val idxScrn = SOURCE_PRIORITY.indexOf("screenscape.me")
        val idxVidZee = SOURCE_PRIORITY.indexOf("VidZee")
        assertTrue(idxGdm == 0 || idxGdm == 1, "GDMIRROR should be in first two positions, was $idxGdm")
        assertTrue(idxVidZee > idxScrn, "VidZee should be after screenscape.me")
    }
}
