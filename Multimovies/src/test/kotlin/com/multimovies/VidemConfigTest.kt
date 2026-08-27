package com.multimovies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Guards the videm.xyz embed-page config parser. */
class VidemConfigTest {

    private val sampleHtml = """
        <html><body>
        <script>
        var Q = {"type":"movie","id":"tt1979320","s":0,"e":0,"t":"eyJjIjoiIn0.sig","ap":false,"art":"https://image.tmdb.org/t/p/w1280/x.jpg","title":"Rush (2013)","st":0,"srv":"","hide":[],"pp":false,"ss":false,"swish":"/playerswish.php?imdb=tt1979320&t=swishtok","ssmin":0,"pv":true,"pvmax":40,"ad":"","wt":3000,"wmax":6000,"wmint":8000,"wpoll":2,"wpms":800,"hold":2500};
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses Q config from embed page`() {
        val q = VidemExtractor.parseQConfig(sampleHtml)
        assertNotNull(q)
        assertEquals("movie", q.optString("type"))
        assertEquals("tt1979320", q.optString("id"))
        assertEquals("eyJjIjoiIn0.sig", q.optString("t"))
        assertEquals(0, q.optInt("s"))
        assertEquals(0, q.optInt("e"))
        assertEquals("Rush (2013)", q.optString("title"))
    }

    @Test
    fun `handles backslash-escaped forward slashes`() {
        val html = """<script>var Q = {"url":"https:\/\/example.com\/path"};</script>"""
        val q = VidemExtractor.parseQConfig(html)
        assertNotNull(q)
        assertEquals("https://example.com/path", q.optString("url"))
    }

    @Test
    fun `returns null for blank html`() {
        assertEquals(null, VidemExtractor.parseQConfig(""))
    }

    @Test
    fun `returns null when no Q config`() {
        assertEquals(null, VidemExtractor.parseQConfig("<html><body>no config here</body></html>"))
    }
}