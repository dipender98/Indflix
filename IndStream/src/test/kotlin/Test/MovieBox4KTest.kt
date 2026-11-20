package Test

import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieBox4KTest {

    private val spec = ServerFarm.allServers.first { it.id == "moviebox" }

    @Test
    fun moviebox_isMarked4kCapable() {
        assertTrue(spec.supports4k)
    }

    @Test
    fun canonicalUrl_sortsQueryParameters() {
        val url = "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get" +
            "?subjectId=123&se=1&ep=2"
        assertEquals(
            "/wefeed-mobile-bff/subject-api/get?ep=2&se=1&subjectId=123",
            StreamEngine.movieBoxCanonicalUrl(url),
        )
    }

    @Test
    fun mobileStreams_keepsOnlyFree720pAndAbove() {
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
    }

    @Test
    fun mobileStreams_ignoresMissingOrEmptyDetectors() {
        val empty = StreamEngine.movieBoxMobileStreams(org.json.JSONObject("""{"title":"x"}"""), spec, "")
        assertTrue(empty.first.isEmpty())
        assertTrue(empty.second.length() == 0)
    }
}
