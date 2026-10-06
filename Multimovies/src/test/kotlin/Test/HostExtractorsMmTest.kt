package Test

import com.multimovies.bingrBody
import com.multimovies.isMediaUrl
import com.multimovies.parseFilmuSources
import com.multimovies.parseModiplayStreams
import com.multimovies.parseVidoutBody
import com.multimovies.resolveTwoEmbedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/** Guards the host extractors: modiplay, vidout, bingr, filmu, 2embed. */
class HostExtractorsMmTest {

    @Test
    fun modiplayFindsDirectAndRelay() {
        val html = """<script>var directSrc="https://s13-x.com/hls/abc/master.m3u8?token=1";</script>
            <script>var cfg={"src":"https://multiai.modiplay.xyz/stream_proxy.php?url=a&ref=b"};</script>
            <script>var EMBED_URL='https://vidara.to/e/abc';</script>"""
        val out = parseModiplayStreams(html, "https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=1")
        assertEquals(3, out.size)
        assertTrue(out[0].contains("master.m3u8"))
        assertTrue(out[1].contains("stream_proxy.php"))
    }

    @Test
    fun modiplayEmptyOnBlank() {
        assertTrue(parseModiplayStreams("", "https://x.com/").isEmpty())
        assertTrue(parseModiplayStreams("<html></html>", "https://x.com/").isEmpty())
    }

    @Test
    fun vidoutParsesJsonSources() {
        val body = """{"name":"Q","sources":[{"url":"https://cdn.com/a/master.m3u8","quality":"1080p"}]}"""
        val out = parseVidoutBody(body)
        assertEquals(listOf("https://cdn.com/a/master.m3u8"), out)
    }

    @Test
    fun vidoutParsesTextLines() {
        val body = "https://a.com/x/master.m3u8\nhttps://b.com/y.urlset/master.txt\nhttps://c.com/master.txt\nnot a url"
        val out = parseVidoutBody(body)
        assertEquals(listOf("https://a.com/x/master.m3u8", "https://b.com/y.urlset/master.txt"), out)
    }

    @Test
    fun bingrBodyHasFields() {
        val root = JSONObject(bingrBody("s40", "movie", "518497", "Sir", "2018", null, null))
        assertEquals("s40", root.optString("srv"))
        assertEquals("movie", root.optString("t"))
        assertEquals("518497", root.optString("id"))
        assertEquals("Sir", root.optJSONObject("query").optString("title"))
    }

    @Test
    fun filmuPrefixesProxyAndTagsHindi() {
        val root = JSONObject("""{"sources":[
            {"url":"/proxy/abc.m3u8","quality":"1080p Hindi"},
            {"url":"https://cdn.com/b.mp4","quality":"720p"}]}""")
        val out = parseFilmuSources(root, "Vaplayer")
        assertEquals(2, out.size)
        assertTrue(out[0].url.startsWith("https://box.filmu.in/proxy/"))
        assertTrue(out[0].name.contains("Hindi"))
        assertTrue(out[0].isM3u8)
    }

    @Test
    fun twoEmbedAppendsId() {
        val html = """<iframe id="iframesrc" src="about:blank" data-src="https://vidsrc.buzz/embed/movie/">"""
        assertEquals(
            "https://vidsrc.buzz/embed/movie/tt7142506",
            resolveTwoEmbedTarget(html, "tt7142506"),
        )
    }

    @Test
    fun mediaCheck() {
        assertTrue(isMediaUrl("https://x.com/a/master.m3u8?token=1"))
        assertTrue(!isMediaUrl("https://x.com/master.txt"))
    }
}
