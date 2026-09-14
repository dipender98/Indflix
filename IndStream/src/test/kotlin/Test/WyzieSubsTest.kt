package Test

import com.indstream.WyzieSettings
import com.indstream.WyzieSubs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FILE: WyzieSubsTest.kt - guards Wyzie URL building, tolerant parsing, and key validation. */
class WyzieSubsTest {

    // Real Wyzie /search shape (docs): a JSON array of subtitle objects.
    private val wyzieJson = """
        [{"id":"1955024019",
          "url":"https://sub.wyzie.io/c/198e0c4d/id/1955024019?format=srt&encoding=UTF-8",
          "flagUrl":"https://flagsapi.com/US/flat/24.png",
          "format":"srt","encoding":"UTF-8","display":"English","language":"en",
          "media":"The Martian","isHearingImpaired":false,"source":"opensubtitles"},
         {"id":"1955024020",
          "url":"https://sub.wyzie.io/c/198e0c4d/id/1955024020?format=srt&encoding=UTF-8",
          "flagUrl":"https://flagsapi.com/IN/flat/24.png",
          "format":"srt","encoding":"UTF-8","display":"Hindi","language":"hi",
          "media":"The Martian","isHearingImpaired":false,"source":"opensubtitles"}]
    """.trimIndent()

    @Test
    fun buildUrl_movie_imdbAndLangsAndKey() {
        val url = WyzieSubs.buildUrl("tt1375666", 0, 0, linkedSetOf("hi", "en"), "wyzie-abc123")
        assertTrue(url!!.startsWith("https://sub.wyzie.io/search?id=tt1375666"), url)
        assertTrue(url.contains("language=hi%2Cen"), url)
        assertTrue(url.contains("key=wyzie-abc123"), url)
        assertTrue(!url.contains("season="), url)
    }

    @Test
    fun buildUrl_series_addsSeasonEpisode() {
        val url = WyzieSubs.buildUrl("tt0944947", 1, 1, setOf("en"), "wyzie-abc123")
        assertTrue(url!!.contains("season=1&episode=1"), url)
    }

    @Test
    fun buildUrl_partialSeasonEpisode_staysMovie() {
        val url = WyzieSubs.buildUrl("tt0944947", 2, -1, setOf("en"), "wyzie-abc123")
        assertTrue(url!!.contains("id=tt0944947"), url)
        assertTrue(!url.contains("season="), url)
    }

    @Test
    fun buildUrl_nullWithoutIdOrKey() {
        assertNull(WyzieSubs.buildUrl(null, 0, 0, setOf("en"), "wyzie-abc123"))
        assertNull(WyzieSubs.buildUrl("tt1375666", 0, 0, setOf("en"), "  "))
        assertNull(WyzieSubs.buildUrl("bad-id", 0, 0, setOf("en"), "wyzie-abc123"))
    }

    @Test
    fun parse_arrayRoot_canonicalNamesAndMenus() {
        val tracks = WyzieSubs.parse(wyzieJson, setOf("en", "hi"))
        assertEquals(2, tracks.size)
        assertEquals("English", tracks[0].lang)
        assertEquals("Hindi", tracks[1].lang)
        assertTrue(tracks[0].url.startsWith("https://sub.wyzie.io/"))
        assertTrue(tracks[1].menu.contains("Hindi"))
    }

    @Test
    fun parse_objectRootWithSubtitlesArray() {
        val tracks = WyzieSubs.parse("""{"subtitles":$wyzieJson}""", setOf("en", "hi"))
        assertEquals(2, tracks.size)
    }

    @Test
    fun parse_filtersUnwanted_dedupesUrls_capsPerLang() {
        val many = StringBuilder("[")
        for (i in 1..8) {
            if (i > 1) many.append(",")
            many.append("""{"url":"https://sub.wyzie.io/$i.srt","language":"en","display":"English"}""")
        }
        many.append(""",{"url":"https://sub.wyzie.io/1.srt","language":"en","display":"English"}""")
        many.append(""",{"url":"https://sub.wyzie.io/zz.srt","language":"zz","display":"Zulu"}""")
        many.append("]")
        val tracks = WyzieSubs.parse(many.toString(), setOf("en"))
        assertEquals(WyzieSubs.MAX_PER_LANG, tracks.size)
    }

    @Test
    fun parse_garbageIsEmpty() {
        assertEquals(emptyList(), WyzieSubs.parse("not json", setOf("en")))
        assertEquals(emptyList(), WyzieSubs.parse("""{"junk":1}""", setOf("en")))
    }

    @Test
    fun keyValidation_trimsAndLengthGates() {
        assertTrue(WyzieSettings.isValidKey("wyzie-abc123"))
        assertTrue(WyzieSettings.isValidKey("  wyzie-abc123  "))
        assertTrue(!WyzieSettings.isValidKey("short"))
        assertTrue(!WyzieSettings.isValidKey(null))
        assertTrue(!WyzieSettings.isValidKey("   "))
        assertEquals("wyzie-abc123", WyzieSettings.normalizeKey("  wyzie-abc123 "))
    }

    @Test
    fun apiKey_nullWithoutInit_neverThrowsOnJvm() {
        assertNull(WyzieSettings.apiKey())
    }
}
