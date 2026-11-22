package Test

import com.indstream.LinkNaming
import com.indstream.ManifestLanguageProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestLanguageProxyTest {

    private val base = "https://cdn.example.com/media/abc/index.m3u8?token=1"

    @Test
    fun addsLanguageFromDeclaredNameAndAbsolutizesUris() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="1. English",DEFAULT=YES,URI="track_en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="2. Hindi",DEFAULT=NO,URI="track_hi.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="sub_en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        val out = ManifestLanguageProxy.rewrittenManifest(master, base)
        assertTrue(out != null)
        assertTrue(out.contains("TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"1. English\",DEFAULT=YES,URI=\"https://cdn.example.com/media/abc/track_en.m3u8\",LANGUAGE=\"en\""))
        assertTrue(out.contains("LANGUAGE=\"hi\""))
        assertTrue(out.contains("TYPE=SUBTITLES") && out.contains("LANGUAGE=\"en\""))
        assertTrue(out.contains("\nhttps://cdn.example.com/media/abc/1080p.m3u8"))
    }

    @Test
    fun leavesExplicitLanguagesUntouched() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="English",URI="track_en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        assertNull(ManifestLanguageProxy.rewrittenManifest(master, base))
    }

    @Test
    fun replacesUndeterminedLanguageFromDeclaredName() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="und",NAME="Hindi",URI="track_hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        val out = ManifestLanguageProxy.rewrittenManifest(master, base)
        assertTrue(out != null)
        assertTrue(out.contains("LANGUAGE=\"hi\""))
        assertTrue(!out.contains("LANGUAGE=\"und\""))
    }

    @Test
    fun ignoresNamesWithoutADeclaredLanguage() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Track 1",URI="track_1.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        assertNull(ManifestLanguageProxy.rewrittenManifest(master, base))
    }

    @Test
    fun ignoresPlaylistsWithoutAudioRenditions() {
        val master = """
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6,
seg-1.m4s
        """.trimIndent()

        assertNull(ManifestLanguageProxy.rewrittenManifest(master, base))
    }

    @Test
    fun declaredLanguagesFollowsDeclarationOrder() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="track_en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",URI="track_hi.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",URI="track_hi_alt.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="sub.m3u8"
        """.trimIndent()

        assertEquals(listOf("en", "hi"), ManifestLanguageProxy.declaredLanguages(master))
    }

    @Test
    fun perLanguageManifestKeepsOnlyThatLanguage() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,URI="track_en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",DEFAULT=NO,URI="track_hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        val out = ManifestLanguageProxy.rewrittenManifest(master, base, "hi")
        assertTrue(out != null)
        assertTrue(out.contains("LANGUAGE=\"hi\""))
        assertTrue(!out.contains("track_en.m3u8"))
        assertTrue(out.contains("DEFAULT=YES"))
        assertTrue(out.contains("\nhttps://cdn.example.com/media/abc/1080p.m3u8"))
    }

    @Test
    fun servesRewrittenManifestOverLoopback() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",URI="track_hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        val url = ManifestLanguageProxy.urlFor(master, base)
        assertTrue(url != null)
        val served = java.net.URL(url).readText()
        assertTrue(served.contains("LANGUAGE=\"hi\""))
        assertTrue(served.contains("https://cdn.example.com/media/abc/1080p.m3u8"))
    }

    @Test
    fun servesPerLanguageManifestOverLoopback() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",DEFAULT=YES,URI="track_en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Hindi",DEFAULT=NO,URI="track_hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        val url = ManifestLanguageProxy.urlFor(master, base, "hi")
        assertTrue(url != null)
        val served = java.net.URL(url).readText()
        assertTrue(served.contains("LANGUAGE=\"hi\""))
        assertTrue(!served.contains("track_en.m3u8"))
    }

    @Test
    fun handlesQuotedEnumAndUnquotedValues() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE="AUDIO",GROUP-ID="aac",NAME=English,DEFAULT=YES,URI="track_en.m3u8"
#EXT-X-MEDIA:TYPE="AUDIO",GROUP-ID="aac",NAME=Hindi,URI="track_hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="aac"
1080p.m3u8
        """.trimIndent()

        assertEquals(listOf("en", "hi"), ManifestLanguageProxy.declaredLanguages(master))
        val out = ManifestLanguageProxy.rewrittenManifest(master, base)
        assertTrue(out != null)
        assertTrue(out.contains("LANGUAGE=\"en\""))
        assertTrue(out.contains("LANGUAGE=\"hi\""))
    }

    @Test
    fun languageCodeUsesDeclaredNames() {
        assertEquals("en", LinkNaming.languageCode("1. English"))
        assertEquals("hi", LinkNaming.languageCode("Hindi"))
        assertEquals("es", LinkNaming.languageCode("Spanish Latin"))
        assertEquals("pt", LinkNaming.languageCode("Portuguese Brazil"))
        assertNull(LinkNaming.languageCode("Track 1"))
    }
}
