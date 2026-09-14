package Test

import com.multimovies.Settings
import com.multimovies.WyzieSubs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FILE: WyzieSubsMmTest.kt - guards Wyzie URL building, tolerant parsing, and key validation. */
class WyzieSubsMmTest {

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
        val url = WyzieSubs.buildUrl("tt1375666", null, null, null, linkedSetOf("hi", "en"), "wyzie-abc123")
        assertTrue(url!!.startsWith("https://sub.wyzie.io/search?id=tt1375666"), url)
        assertTrue(url.contains("language=hi,en"), url)
        assertTrue(!url.contains("source="), url)
        assertTrue(url.contains("key=wyzie-abc123"), url)
        assertTrue(!url.contains("season="), url)
    }

    @Test
    fun buildUrl_scopesToKeySources() {
        val url = WyzieSubs.buildUrl("tt1375666", null, null, null, setOf("en"), "wyzie-abc123", listOf("charlie", "lima"))
        assertTrue(url!!.contains("source=charlie,lima"), url)
        assertTrue(!url.contains("source=all"), url)
    }

    @Test
    fun buildUrl_series_addsSeasonEpisode() {
        val url = WyzieSubs.buildUrl("tt0944947", null, 1, 1, setOf("en"), "wyzie-abc123")
        assertTrue(url!!.contains("season=1&episode=1"), url)
    }

    @Test
    fun buildUrl_tmdbFallbackWhenNoImdb() {
        val url = WyzieSubs.buildUrl(null, "286217", null, null, setOf("en"), "wyzie-abc123")
        assertTrue(url!!.contains("id=286217"), url)
    }

    @Test
    fun buildUrl_seasonWithoutEpisode_staysMovie() {
        val url = WyzieSubs.buildUrl("tt0944947", null, 2, null, setOf("en"), "wyzie-abc123")
        assertTrue(!url!!.contains("season="), url)
    }

    @Test
    fun buildUrl_nullWithoutIdOrKey() {
        assertNull(WyzieSubs.buildUrl(null, null, null, null, setOf("en"), "wyzie-abc123"))
        assertNull(WyzieSubs.buildUrl("tt1375666", null, null, null, setOf("en"), "  "))
        assertNull(WyzieSubs.buildUrl("bad-id", "xx", null, null, setOf("en"), "wyzie-abc123"))
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
    fun parse_ignoresLive401ErrorBody() {
        val err = """{"code":401,"message":"API key required","details":"Include a valid API key"}"""
        assertEquals(emptyList(), WyzieSubs.parse(err, setOf("en", "hi")))
    }

    @Test
    fun failureReason_statusCodes_win() {
        assertEquals("key rejected", WyzieSubs.failureReason(401, ""))
        assertEquals("key rejected", WyzieSubs.failureReason(401, null))
        assertEquals("limit reached", WyzieSubs.failureReason(429, null))
        assertEquals("limit reached", WyzieSubs.failureReason(402, ""))
        assertEquals("server error (500)", WyzieSubs.failureReason(500, "boom"))
        assertEquals(null, WyzieSubs.failureReason(200, wyzieJson))
        assertEquals(null, WyzieSubs.failureReason(null, null))
    }

    @Test
    fun failureReason_live401Body_keyRejected() {
        val err = """{"code":401,"message":"API key required","details":"Include a valid API key"}"""
        assertEquals("key rejected", WyzieSubs.failureReason(null, err))
    }

    @Test
    fun failureReason_rateAndPaymentBodies_limitReached() {
        assertEquals("limit reached", WyzieSubs.failureReason(null, """{"code":429,"message":"Rate limit exceeded"}"""))
        assertEquals("limit reached", WyzieSubs.failureReason(null, """{"code":402,"message":"Top up required"}"""))
    }

    @Test
    fun failureReason_healthyBodies_null() {
        assertEquals(null, WyzieSubs.failureReason(null, wyzieJson))
        assertEquals(null, WyzieSubs.failureReason(null, """{"subtitles":[]}"""))
        assertEquals(null, WyzieSubs.failureReason(null, "  "))
    }

    @Test
    fun failureReason_htmlGarbage_serverError() {
        assertEquals("server error", WyzieSubs.failureReason(null, "<html>denied</html>"))
    }

    @Test
    fun failureReason_languageError_requestRejected() {
        assertEquals("request rejected", WyzieSubs.failureReason(null, """{"message":"Invalid language parameter"}"""))
    }

    @Test
    fun failureReason_noSubtitlesFound_isHealthyEmpty() {
        val noSubs = """{"code":400,"message":"No subtitles found","details":"No subtitles found, sorry"}"""
        assertEquals(null, WyzieSubs.failureReason(400, noSubs))
        assertEquals(null, WyzieSubs.failureReason(200, noSubs))
        assertEquals(null, WyzieSubs.failureReason(null, noSubs))
    }

    @Test
    fun failureReason_400And403Bodies_classified() {
        assertEquals("request rejected", WyzieSubs.failureReason(400, ""))
        assertEquals("request rejected", WyzieSubs.failureReason(400, """{"message":"No results"}"""))
        assertEquals("key rejected", WyzieSubs.failureReason(403, """{"code":403,"message":"Invalid API key"}"""))
        assertEquals("server error (500)", WyzieSubs.failureReason(500, "boom"))
    }

    @Test
    fun parseSources_availableList_allFreeFallback_invalidKey() {
        val scoped = """{"sources":["bravo","charlie","lima"],"available":["charlie","lima"],"allFree":false}"""
        assertEquals(listOf("charlie", "lima"), WyzieSubs.parseSources(scoped))
        val allFree = """{"sources":["charlie","lima"],"allFree":true}"""
        assertEquals(listOf("charlie", "lima"), WyzieSubs.parseSources(allFree))
        val badKey = """{"sources":["charlie","lima"],"key":{"valid":false,"reason":"not_found"}}"""
        assertEquals(emptyList<String>(), WyzieSubs.parseSources(badKey))
        assertEquals(emptyList<String>(), WyzieSubs.parseSources(null))
        assertEquals(emptyList<String>(), WyzieSubs.parseSources("not json"))
    }

    @Test
    fun keyValidation_trimsAndLengthGates() {
        assertTrue(Settings.isValidKey("wyzie-abc123"))
        assertTrue(Settings.isValidKey("  wyzie-abc123  "))
        assertTrue(!Settings.isValidKey("short"))
        assertTrue(!Settings.isValidKey(null))
        assertTrue(!Settings.isValidKey("   "))
        assertEquals("wyzie-abc123", Settings.normalizeKey("  wyzie-abc123 "))
    }

    @Test
    fun apiKey_nullWithoutInit_neverThrowsOnJvm() {
        assertNull(Settings.apiKey())
    }

    @Test
    fun tmdbKeyValidation_shortV3Ok_tokenRejected() {
        assertTrue(Settings.isValidTmdbKey("0123456789abcdef0123456789abcdef"))
        assertTrue(Settings.isValidTmdbKey("  0123456789abcdef0123456789abcdef  "))
        assertTrue(!Settings.isValidTmdbKey("eyJhbGciOiJIUzI1NiJ9.payload.sig"))
        assertTrue(!Settings.isValidTmdbKey("short"))
        assertTrue(!Settings.isValidTmdbKey(null))
        assertTrue(!Settings.isValidTmdbKey("   "))
        assertNull(Settings.tmdbApiKey())
    }

    @Test
    fun tmdbKeyProblem_namesExactIssue() {
        assertNull(Settings.tmdbKeyProblem("0123456789abcdef0123456789abcdef"))
        assertNull(Settings.tmdbKeyProblem("   "))
        assertTrue(Settings.tmdbKeyProblem("eyJhbGciOiJIUzI1NiJ9.x")!!.contains("Read Access Token"))
        assertTrue(Settings.tmdbKeyProblem("abc")!!.contains("32-char"))
    }
}
