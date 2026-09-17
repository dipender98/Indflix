package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Pure checks for signed-cookie handling.
class MovieBoxCookieTest {

    private fun policyCookie(resource: String, unpadded: Boolean = false): String {
        val json = """{"Statement":[{"Resource":"$resource","Condition":{"DateLessThan":{"AWS:EpochTime":9999999999}}}]}"""
        var b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        b64 = b64.replace('+', '-').replace('/', '~').replace('=', '_')
        if (unpadded) b64 = b64.trimEnd('_')
        return "CloudFront-Policy=$b64; CloudFront-Signature=sig; CloudFront-Key-Pair-Id=KID"
    }

    @Test
    fun cookie_normalizesSeparators() {
        val got = StreamEngine.cloudFrontCookie(" CloudFront-Policy=aaa ;CloudFront-Signature=bbb;CloudFront-Key-Pair-Id=ccc ")
        assertEquals("CloudFront-Policy=aaa; CloudFront-Signature=bbb; CloudFront-Key-Pair-Id=ccc", got)
    }

    @Test
    fun referer_isAllowedMirror() {
        // File CDN returns 429 for stale mirrors; only this passes.
        assertEquals("https://movieboxonline.net/", StreamEngine.MOVIEBOX_REFERER)
    }

    @Test
    fun cookie_blankYieldsNull() {
        assertNull(StreamEngine.cloudFrontCookie(null))
        assertNull(StreamEngine.cloudFrontCookie("  "))
        assertNull(StreamEngine.cloudFrontCookie(";;;"))
    }

    @Test
    fun noticeUrl_detected() {
        assertTrue(StreamEngine.isMovieboxNoticeUrl("https://macdn.aoneroom.com/other/notice.mp4"))
        assertTrue(!StreamEngine.isMovieboxNoticeUrl("https://d111.cloudfront.net/videos/a/index.mpd"))
    }

    @Test
    fun manifest_rebuiltFromPolicy() {
        val cookie = policyCookie("https://d111111abcdef8.cloudfront.net/videos/abc123/*")
        assertEquals(
            "https://d111111abcdef8.cloudfront.net/videos/abc123/index.mpd",
            StreamEngine.dashManifestFromPolicy(cookie)
        )
    }

    @Test
    fun manifest_unpaddedPolicyOk() {
        val cookie = policyCookie("https://d111111abcdef8.cloudfront.net/videos/abc123/*", unpadded = true)
        assertEquals(
            "https://d111111abcdef8.cloudfront.net/videos/abc123/index.mpd",
            StreamEngine.dashManifestFromPolicy(cookie)
        )
    }

    @Test
    fun manifest_garbageYieldsNull() {
        assertNull(StreamEngine.dashManifestFromPolicy(null))
        assertNull(StreamEngine.dashManifestFromPolicy("CloudFront-Policy=!!!; CloudFront-Signature=x"))
        assertNull(StreamEngine.dashManifestFromPolicy("CloudFront-Signature=x; CloudFront-Key-Pair-Id=y"))
    }

    @Test
    fun base64_pureDecoderRoundTrip() {
        val raw = "hello-policy-123".toByteArray(Charsets.UTF_8)
        val std = java.util.Base64.getEncoder().encodeToString(raw)
        assertEquals("hello-policy-123", StreamEngine.decodeBase64Standard(std)?.let { String(it, Charsets.UTF_8) })
        assertNull(StreamEngine.decodeBase64Standard("!!!"))
    }
}
