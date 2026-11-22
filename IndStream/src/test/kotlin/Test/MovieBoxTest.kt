package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieBoxTest {

    private val spec get() = ServerFarm.allServers.first { it.id == "moviebox" }

    @Test
    fun moviebox_presentAndTitled() {
        val s = spec
        assertEquals("MovieBox", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://movieboxonline.net/", s.referer)
        assertTrue(s.hasSubtitles)
        assertTrue(s.supports4k)
        assertTrue(s.reliable)
        assertTrue(s.downloadFriendly)
    }

    @Test
    fun moviebox_rejectionClass() {
        assertTrue(!StreamEngine.movieboxAuthRejected(429, "429"))
        assertTrue(!StreamEngine.movieboxAuthRejected(429, "0"))
        assertTrue(!StreamEngine.movieboxAuthRejected(200, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(401, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(403, "0"))
        assertTrue(StreamEngine.movieboxAuthRejected(200, "400"))
        assertTrue(StreamEngine.movieboxAuthRejected(400, "400"))
    }

    @Test
    fun moviebox_timeoutBudgetFitsFarmKill() {
        assertEquals(65, spec.timeoutSec, "MovieBox chain ceilings (8/15[+12]/[10+2]/8/8) must fit this kill")
    }

    @Test
    fun moviebox_canonicalUrl_sortsQueryParameters() {
        val url = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get" +
            "?subjectId=123&se=1&ep=2"
        assertEquals(
            "/wefeed-mobile-bff/subject-api/get?ep=2&se=1&subjectId=123",
            StreamEngine.movieBoxCanonicalUrl(url),
        )
    }

    @Test
    fun moviebox_mobileStreams_keepsOnlyFree720pAndAbove() {
        val detail = org.json.JSONObject(
            """
            {
              "dubs": [
                {"subjectId":"s1","lanName":"Original Audio","lanCode":"en","original":true},
                {"subjectId":"s2","lanName":"Hindi dub","lanCode":"hi","original":false}
              ],
              "resourceDetectors": [
                {"resolutionList":[
                  {"resourceLink":"https://cdn.test/a480.mp4","resolution":480,"size":"100000000","requireMemberType":0},
                  {"resourceLink":"https://cdn.test/a720.mp4","resolution":720,"size":"1000000000","requireMemberType":0},
                  {"resourceLink":"https://cdn.test/a1080.mp4","resolution":1080,"size":"2000000000","requireMemberType":1},
                  {"resourceLink":"https://cdn.test/a2160.mp4","resolution":2160,"size":"8734054684","requireMemberType":0},
                  {"resourceLink":"https://macdn.aoneroom.com/other/notice.mp4","resolution":2160,"size":"1","requireMemberType":0},
                  {"resourceLink":"https://cdn.test/a720.mp4","resolution":720,"size":"1000000000","requireMemberType":0}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val (streams, subs) = StreamEngine.movieBoxMobileStreams(detail, spec, "Hindi")
        assertEquals(listOf(720, 2160), streams.map { it.qualityHint })
        assertEquals("Hindi", streams.first().audioLabel)
        assertEquals(4, streams.first().audioPriority)
        assertEquals("1.0GB", streams.first().sizeLabel)
        assertEquals("8.7GB", streams.last().sizeLabel)
        assertEquals(0, subs.length())
        assertTrue(streams.all { !it.isM3u8 && !it.isDash && it.url.startsWith("https://cdn.test/") })
        assertTrue(streams.all { it.extraHeaders["User-Agent"]?.startsWith("com.community.oneroom/") == true })
    }

    @Test
    fun moviebox_mobileStreams_detectsAdaptiveUrls() {
        val detail = org.json.JSONObject(
            """
            {
              "resourceDetectors": [
                {"resolutionList":[
                  {"resourceLink":"https://cdn.test/a720.m3u8","resolution":720,"requireMemberType":0},
                  {"resourceLink":"https://cdn.test/a1080.mpd","resolution":1080,"requireMemberType":0}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val (streams, _) = StreamEngine.movieBoxMobileStreams(detail, spec, "")
        assertEquals(2, streams.size)
        assertTrue(streams[0].isM3u8)
        assertTrue(!streams[0].isDash)
        assertTrue(streams[1].isM3u8)
        assertTrue(streams[1].isDash)
    }

    @Test
    fun moviebox_mobileStreams_ignoresMissingOrEmptyDetectors() {
        val empty = StreamEngine.movieBoxMobileStreams(org.json.JSONObject("""{"title":"x"}"""), spec, "")
        assertTrue(empty.first.isEmpty())
        assertTrue(empty.second.length() == 0)
    }

    @Test
    fun moviebox_cookie_normalizesSeparators() {
        val got = StreamEngine.cloudFrontCookie(" CloudFront-Policy=aaa ;CloudFront-Signature=bbb;CloudFront-Key-Pair-Id=ccc ")
        assertEquals("CloudFront-Policy=aaa; CloudFront-Signature=bbb; CloudFront-Key-Pair-Id=ccc", got)
    }

    @Test
    fun moviebox_referer_isAllowedMirror() {
        assertEquals("https://movieboxonline.net/", StreamEngine.MOVIEBOX_REFERER)
    }

    @Test
    fun moviebox_cookie_blankYieldsNull() {
        assertNull(StreamEngine.cloudFrontCookie(null))
        assertNull(StreamEngine.cloudFrontCookie("  "))
        assertNull(StreamEngine.cloudFrontCookie(";;;"))
    }

    @Test
    fun moviebox_noticeUrl_detected() {
        assertTrue(StreamEngine.isMovieboxNoticeUrl("https://macdn.aoneroom.com/other/notice.mp4"))
        assertTrue(!StreamEngine.isMovieboxNoticeUrl("https://d111.cloudfront.net/videos/a/index.mpd"))
    }

    private fun policyCookie(resource: String, unpadded: Boolean = false): String {
        val json = """{"Statement":[{"Resource":"$resource","Condition":{"DateLessThan":{"AWS:EpochTime":9999999999}}}]}"""
        var b64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        b64 = b64.replace('+', '-').replace('/', '~').replace('=', '_')
        if (unpadded) b64 = b64.trimEnd('_')
        return "CloudFront-Policy=$b64; CloudFront-Signature=sig; CloudFront-Key-Pair-Id=KID"
    }

    @Test
    fun moviebox_manifest_rebuiltFromPolicy() {
        val cookie = policyCookie("https://d111111abcdef8.cloudfront.net/videos/abc123/*")
        assertEquals(
            "https://d111111abcdef8.cloudfront.net/videos/abc123/index.mpd",
            StreamEngine.dashManifestFromPolicy(cookie),
        )
    }

    @Test
    fun moviebox_manifest_unpaddedPolicyOk() {
        val cookie = policyCookie("https://d111111abcdef8.cloudfront.net/videos/abc123/*", unpadded = true)
        assertEquals(
            "https://d111111abcdef8.cloudfront.net/videos/abc123/index.mpd",
            StreamEngine.dashManifestFromPolicy(cookie),
        )
    }

    @Test
    fun moviebox_manifest_garbageYieldsNull() {
        assertNull(StreamEngine.dashManifestFromPolicy(null))
        assertNull(StreamEngine.dashManifestFromPolicy("CloudFront-Policy=!!!; CloudFront-Signature=x"))
        assertNull(StreamEngine.dashManifestFromPolicy("CloudFront-Signature=x; CloudFront-Key-Pair-Id=y"))
    }

    @Test
    fun moviebox_base64_pureDecoderRoundTrip() {
        val raw = "hello-policy-123".toByteArray(Charsets.UTF_8)
        val std = java.util.Base64.getEncoder().encodeToString(raw)
        assertEquals("hello-policy-123", StreamEngine.decodeBase64Standard(std)?.let { String(it, Charsets.UTF_8) })
        assertNull(StreamEngine.decodeBase64Standard("!!!"))
    }
}
