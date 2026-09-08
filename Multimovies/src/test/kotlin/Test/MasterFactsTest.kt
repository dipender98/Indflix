package Test

import com.multimovies.MultiSourcePuller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILE: MasterFactsTest.kt — guards the probed server-name labeling
 * (user spec: language + resolution ON the server name, derived from
 * probing/parsing the stream — NEVER guessed):
 *
 *  - [MultiSourcePuller.parseMasterFacts] reads REAL master playlists:
 *    tallest variant height (RESOLUTION=WxH) + #EXT-X-MEDIA audio languages
 *    (LANGUAGE/NAME/DEFAULT), pure JVM.
 *  - [MultiSourcePuller.qualityLabel] maps heights to display tokens.
 *  - [MultiSourcePuller.declaredHindi] accepts only host declarations
 *    (brand/URL tokens); a stream with no declaration is NOT Hindi.
 *  - [MultiSourcePuller.resolutionFromUrl] reads declared heights only.
 */
class MasterFactsTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,CHANNELS="2"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud2",NAME="हिन्दी",LANGUAGE="hi",DEFAULT=NO,AUTOSELECT=YES,CHANNELS="2"
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="aud1"
        v1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720,AUDIO="aud2"
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360,AUDIO="aud2"
        v360.m3u8
    """.trimIndent()

    @Test
    fun parseMasterFacts_readsHeightAndLanguages() {
        val facts = MultiSourcePuller.parseMasterFacts(master)!!
        assertEquals(1080, facts.bestHeight)
        assertEquals(2, facts.audio.size)
        val default = facts.audio.first { it.isDefault }
        assertEquals("en", default.language)
        assertEquals("English", default.name)
    }

    @Test
    fun parseMasterFacts_nonMaster_isNull() {
        assertNull(MultiSourcePuller.parseMasterFacts(null))
        assertNull(MultiSourcePuller.parseMasterFacts(""))
        // Media playlist (no variants) is not a master.
        assertNull(MultiSourcePuller.parseMasterFacts("#EXTM3U\n#EXTINF:4,\nseg.ts"))
    }

    @Test
    fun qualityLabel_tokens() {
        assertEquals("4K", MultiSourcePuller.qualityLabel(2160))
        assertEquals("1080p", MultiSourcePuller.qualityLabel(1080))
        assertEquals("720p", MultiSourcePuller.qualityLabel(720))
        assertEquals("360p", MultiSourcePuller.qualityLabel(360))
        assertEquals("", MultiSourcePuller.qualityLabel(0))
    }

    @Test
    fun declaredHindi_onlyFromDeclaration_neverGuessed() {
        assertTrue(MultiSourcePuller.declaredHindi("VidHindi", null))
        assertTrue(MultiSourcePuller.declaredHindi(null, "https://cdn/x/hindi/file.mp4"))
        assertFalse(MultiSourcePuller.declaredHindi("Cineverse", "https://vibuxer/x/playlist.m3u8"))
        assertFalse(MultiSourcePuller.declaredHindi(null, null))
    }

    @Test
    fun resolutionFromUrl_declaredTokensOnly() {
        assertEquals(1080, MultiSourcePuller.resolutionFromUrl("https://x/a.1080p.mp4"))
        assertEquals(720, MultiSourcePuller.resolutionFromUrl("https://x/movie_720p/index.m3u8"))
        assertEquals(0, MultiSourcePuller.resolutionFromUrl("https://x/playlist.m3u8"))
        assertEquals(0, MultiSourcePuller.resolutionFromUrl(null))
    }
}
