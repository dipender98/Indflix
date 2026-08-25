package com.multimovies

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostExtractorsTest {

    @Test
    fun `2embed global source movie and tv url builders`() {
        val embed = GlobalSources.list.first { it.name == "2embed.cc" }
        assertEquals("https://www.2embed.cc/embed/movie?imdb=tt0944947", embed.buildUrl("tt0944947", null, null))
        assertEquals(
            "https://www.2embed.cc/embed/tv?imdb=tt0944947&s=1&e=2",
            embed.buildUrl("tt0944947", 1, 2)
        )
    }

    @Test
    fun `buildProxyStreamUrl detects serve_m3u8 proxy endpoints`() {
        val text = """<script>var u="\/proxy.php?serve_m3u8=1&ref=https%3A%2F%2Fsite.com&url=https%3A%2F%2Fcdn.example.com%2Fmaster.m3u8%3Ft%3Dabc"</script>"""
        assertEquals(
            "https://player.example.com/proxy.php?serve_m3u8=1&ref=https%3A%2F%2Fsite.com&url=https%3A%2F%2Fcdn.example.com%2Fmaster.m3u8%3Ft%3Dabc",
            MultiSourcePuller.buildProxyStreamUrl(text, "https://player.example.com/embed")
        )
        assertNull(MultiSourcePuller.buildProxyStreamUrl("no proxy here", "https://x.com"))
    }

    @Test
    fun `buildProxyStreamUrl extracts modiplay relay with streamhg platform`() {
        // Real structure: the modiplay proxy player page embeds the serve_m3u8 relay
        // URL (with an encoded CDN master URL and an &ebd= embedder param).
        val text = """var src="/proxy.php?serve_m3u8=1&ref=https%3A%2F%2Fvibuxer.com%2Fe%2Fkbvcrpukfept&url=https%3A%2F%2Fvibuxer.com%2Fstream%2FxgrGOpqmjsvZ-QFncyF3XA%2Fkjhhiuahiuhgihdf%2F1787532716%2F64914574%2Fmaster.m3u8&ebd=https%3A%2F%2Fvibuxer.com";"""
        val base = "https://rozgarlelo.modiplay.xyz/proxy.php?p=streamhg&c=kbvcrpukfept&title=Jolly+LLB+3"
        val url = MultiSourcePuller.buildProxyStreamUrl(text, base)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://rozgarlelo.modiplay.xyz/proxy.php?serve_m3u8=1"))
        assertTrue(url.contains("url=https%3A%2F%2Fvibuxer.com%2Fstream%2F"))
        assertTrue(url.contains("&ebd="))
        // The streamhg platform (Hindi) should be detected from the base URL.
        assertTrue(MultiSourcePuller.isHindiHint("Cineverse", base, url))
    }

    @Test
    fun `extractVideoSourceUrl reads video and source tags`() {
        assertEquals(
            "https://cdn.example.com/video.mp4",
            MultiSourcePuller.extractVideoSourceUrl("<video src=\"https://cdn.example.com/video.mp4\"></video>", "https://x.com")
        )
        assertEquals(
            "https://cdn.example.com/master.m3u8",
            MultiSourcePuller.extractVideoSourceUrl("<video><source src=\"https://cdn.example.com/master.m3u8\"></source></video>", "https://x.com")
        )
        assertNull(MultiSourcePuller.extractVideoSourceUrl("<div>no video</div>", "https://x.com"))
    }

    @Test
    fun `extractFromJsConfig finds player config stream urls`() {
        assertEquals(
            "https://cdn.example.com/master.m3u8",
            MultiSourcePuller.extractFromJsConfig("sources: [{ file: \"https://cdn.example.com/master.m3u8\" }]")
        )
        assertEquals(
            "https://cdn.example.com/video.mp4",
            MultiSourcePuller.extractFromJsConfig("""hlsUrl = "https://cdn.example.com/video.mp4"""")
        )
        assertNull(MultiSourcePuller.extractFromJsConfig("var x = 1;"))
    }

    @Test
    fun `decodeEncodedStreamUrl decodes url-encoded m3u8`() {
        assertEquals(
            "https://cdn.example.com/hls/master.m3u8?t=abc",
            MultiSourcePuller.decodeEncodedStreamUrl(
                "url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fmaster.m3u8%3Ft%3Dabc&ebd=1"
            )
        )
        assertNull(MultiSourcePuller.decodeEncodedStreamUrl("url=https%3A%2F%2Fcdn.example.com%2Fvideo.mp4"))
    }

    @Test
    fun `extractImdbIdFromUrl finds tt ids anywhere in text`() {
        assertEquals(
            "tt1979320",
            extractImdbIdFromUrl("""{"embed_url":"https://streams.iqsmartgames.com/embed/movie/tt1979320?key=abc"}""")
        )
        assertNull(extractImdbIdFromUrl("no id here"))
    }

    @Test
    fun `SourceMetaCache round trips by data url`() {
        SourceMetaCache.clear()
        SourceMetaCache.put("http://example/episode/1x2", SourceMeta("tt0944947", "12345", 1, 2))
        val m = SourceMetaCache.get("http://example/episode/1x2")
        assertEquals("tt0944947", m?.imdbId)
        assertEquals("12345", m?.tmdbId)
        assertEquals(1, m?.season)
        assertEquals(2, m?.episode)
    }

    // ---- sourceKey normalization ----

    @Test
    fun `sourceKey strips indicator only`() {
        assertEquals("Cineverse", MultiSourcePuller.sourceKey("Cineverse (Multimovies)"))
        assertEquals("screenscape.me", MultiSourcePuller.sourceKey("screenscape.me (Multimovies)"))
    }

    @Test
    fun `sourceKey strips indicator and Hindi suffix`() {
        assertEquals("Cineverse", MultiSourcePuller.sourceKey("Cineverse (Multimovies) Hindi"))
        assertEquals("screenscape.me", MultiSourcePuller.sourceKey("screenscape.me (Multimovies) Hindi"))
    }

    @Test
    fun `sourceKey handles blank and null`() {
        assertEquals("", MultiSourcePuller.sourceKey(null))
        assertEquals("", MultiSourcePuller.sourceKey(""))
    }
}
