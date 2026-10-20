package Test

import com.indstream.LinkNaming
import com.indstream.SubtilesProvider
import com.indstream.SubtilesProvider.SubTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleProvidersTest {

    private val osJson = """
        {"subtitles":[
          {"id":"v3+|13105496|Inception.2010.1080p.BluRay.x265-YAWNTiC_eng SDH",
           "sub_id":13105496,"ai_translated":false,"from_trusted":false,
           "uploader_id":10358635,"lang_code":"en","lang":"eng",
           "title":"Inception.2010.1080p.BluRay.x265-YAWNTiC_eng SDH",
           "moviehash":null,
           "url":"https://opensubtitles.stremio.homes/sub.vtt/?lang_code=en&sub_id=13105496"},
          {"id":"v3+|13105494|dup","sub_id":13105494,"lang_code":"en","lang":"eng",
           "title":"dup","url":"https://opensubtitles.stremio.homes/sub.vtt/?lang_code=en&sub_id=13105496"},
          {"id":"v3+|7873591|Inception_Christopher_Nolan","sub_id":7873591,
           "lang_code":"hi","lang":"hin","title":"Inception_Christopher_Nolan",
           "url":"https://opensubtitles.stremio.homes/sub.vtt/?lang_code=hi&sub_id=7873591"},
          {"id":"v3+|4|spam","lang_code":"zz","lang":"zul","title":"spam",
           "url":"https://opensubtitles.stremio.homes/sub.vtt/?lang_code=zz&sub_id=4"}
        ]}
    """.trimIndent()

    private val senseJson = """
        {"subtitles":[
          {"id":"subsense-srt-opensubtitles-eng-0",
           "url":"https://dl.opensubtitles.org/en/download/src-api/vrf-19d90c5f/file/1952595684.srt",
           "lang":"eng",
           "label":"OpenSubtitles - [SRT] - timpe-inception.srt",
           "source":"opensubtitles",
           "fileName":"timpe-inception.srt",
           "releaseName":"DVDRip.XviD-MAXSPEED [and DvDrip[Eng]-FXG]"},
          {"id":"subsense-srt-opensubtitles-hin-0",
           "url":"https://dl.opensubtitles.org/en/download/src-api/vrf-19eb0c65/file/1955494788.srt",
           "lang":"hin",
           "label":"OpenSubtitles - [SRT] - Inception.2010.720p.x264.srt",
           "source":"opensubtitles",
           "fileName":"Inception.2010.720p.x264.srt",
           "releaseName":"720p"},
          {"id":"subsense-srt-opensubtitles-eng-1",
           "url":"https://dl.opensubtitles.org/en/download/src-api/vrf-19d90c5f/file/1952595684.srt",
           "lang":"eng","label":"dup","source":"opensubtitles",
           "fileName":"dup.srt","releaseName":"x"}
        ]}
    """.trimIndent()


    @Test
    fun fetchBudget_coversMeasuredColdLatency() {
        assertEquals(13_000L, SubtilesProvider.FETCH_BUDGET_MS)
        assertTrue(
            SubtilesProvider.FETCH_BUDGET_MS < com.indstream.StreamEngine.LIVE_FILL_MS,
            "subtitle pushes must complete inside the loadLinks live window",
        )
    }


    @Test
    fun codesFromLangs_mapsInOrder_dropsUnmapped() {
        val codes = SubtilesProvider.codesFromLangs(linkedSetOf("Hindi", "English", "Klingon"))
        assertEquals(linkedSetOf("hi", "en"), codes)
    }

    @Test
    fun originalLanguage_ridesAlongWithCode() {
        val withOrig = SubtilesProvider.desiredLanguages("ko")
        assertTrue(withOrig.contains("ko"))
        assertTrue(withOrig.contains("English"))
        assertTrue(withOrig.contains("Hindi"))
        assertTrue(SubtilesProvider.codesFromLangs(withOrig).contains("ko"))
    }

    @Test
    fun desiredLanguages_isIndianPlusEnglish() {
        val all = SubtilesProvider.desiredLanguages(null)
        assertTrue(all.containsAll(setOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Nepali", "Sinhala")))
        assertTrue("Japanese" !in all && "Arabic" !in all)
        val codes = SubtilesProvider.codesFromLangs(all)
        assertEquals(all.size, codes.size, "every desired name maps to a code")
    }

    @Test
    fun groupRequests_hindiFirst_thenEnglishOriginal_thenIndianChunked() {
        val all = SubtilesProvider.codesFromLangs(
            linkedSetOf("English", "Hindi", "Tamil", "Telugu", "ja"),
        )
        val groups = SubtilesProvider.groupRequests(all, "ja")
        assertEquals(linkedSetOf("hi"), groups[0])
        assertEquals(linkedSetOf("en"), groups[1])
        assertEquals(linkedSetOf("ja"), groups[2])
        assertEquals(linkedSetOf("ta", "te"), groups[3])
        assertEquals(4, groups.size)
    }

    @Test
    fun groupRequests_chunksIndianThenForeignByGroupSize() {
        val codes = SubtilesProvider.codesFromLangs(SubtilesProvider.desiredLanguages(null))
        val groups = SubtilesProvider.groupRequests(codes)
        assertEquals(linkedSetOf("hi"), groups.first())
        assertEquals(linkedSetOf("en"), groups[1])
        val indianCodes = setOf("hi", "ta", "te", "ml", "bn", "ur", "mr", "kn", "pa", "gu", "ne", "si")
        var idx = 2
        while (idx < groups.size && groups[idx].any { c -> c in indianCodes }) {
            assertTrue(groups[idx].size <= SubtilesProvider.GROUP_SIZE)
            idx++
        }
        assertTrue(groups.drop(idx).all { it.size <= SubtilesProvider.GROUP_SIZE })
        assertEquals(codes, groups.flatten().toSet(), "every code lands in exactly one group")
    }

    @Test
    fun groupRequests_emptyIsEmpty() {
        assertEquals(emptyList(), SubtilesProvider.groupRequests(emptySet()))
    }


    @Test
    fun buildUrl_movie_pipesEncoded() {
        val url = SubtilesProvider.buildUrl("tt1375666", 0, 0, setOf("Hindi", "English"))
        assertTrue(url.startsWith("https://opensubtitles.stremio.homes/"), url)
        assertTrue(url.endsWith("/subtitles/movie/tt1375666.json"), url)
        assertTrue(url.contains("/ai-translated=true%7Cfrom=all%7Cauto-adjustment=true/subtitles/"), url)
        val langSeg = url.removePrefix("https://opensubtitles.stremio.homes/").substringBefore("/")
        assertEquals(setOf("en", "hi"), langSeg.split("%7C").toSet())
    }

    @Test
    fun buildUrl_series_pathAndCodes() {
        val url = SubtilesProvider.buildUrl("tt0944947", 1, 1, setOf("Hindi"))
        assertTrue(url.contains("/subtitles/series/tt0944947:1:1.json"), url)
        assertTrue(url.contains("/hi/"), url)
    }

    @Test
    fun buildUrl_emptyLangs_fallsBackToEnHi() {
        val url = SubtilesProvider.buildUrl("tt1375666", -1, -1, setOf("Klingon"))
        assertTrue(url.contains("/en%7Chi/"), url)
    }


    @Test
    fun subSenseLanguages_mapsIso3_fallsBackToEng() {
        assertEquals(linkedSetOf("eng", "hin"), SubtilesProvider.subSenseLanguages(setOf("English", "Hindi")))
        assertEquals(setOf("eng"), SubtilesProvider.subSenseLanguages(setOf("Klingon")))
    }

    @Test
    fun subSenseUrl_movie_exactEncoding() {
        val url = SubtilesProvider.subSenseUrl("tt1375666", 0, 0, linkedSetOf("English", "Hindi"))
        assertEquals(
            "https://subsense.nepiraw.com/%7B%22languages%22%3A%5B%22eng%22%2C%22hin%22%5D%2C" +
                "%22maxSubtitles%22%3A6%7D/subtitles/movie/tt1375666.json",
            url,
        )
    }

    @Test
    fun subSenseUrl_series_pathSwitch() {
        val url = SubtilesProvider.subSenseUrl("tt0944947", 1, 1, setOf("English"))
        assertTrue(url.endsWith("/subtitles/series/tt0944947:1:1.json"), url)
        assertTrue(url.contains("%5B%22eng%22%5D"), url)
    }


    @Test
    fun parseOpenSubtitles_realShape_capFilterDedupe() {
        val tracks = SubtilesProvider.parseOpenSubtitles(osJson, setOf("en", "hi"))
        assertEquals(2, tracks.size)
        assertEquals("English", tracks[0].lang)
        assertEquals("Hindi", tracks[1].lang)
        assertTrue(tracks[0].url.startsWith("https://opensubtitles.stremio.homes/sub.vtt/"))
    }

    @Test
    fun parseOpenSubtitles_perLangCap() {
        val many = StringBuilder("""{"subtitles":[""")
        for (i in 1..6) {
            if (i > 1) many.append(",")
            many.append("""{"lang_code":"en","lang":"eng","title":"t$i","url":"https://x/sub.vtt/?sub_id=$i"}""")
        }
        many.append("]}")
        val tracks = SubtilesProvider.parseOpenSubtitles(many.toString(), setOf("en"))
        assertEquals(SubtilesProvider.MAX_PER_LANG, tracks.size)
    }

    @Test
    fun parseOpenSubtitles_missingArrayIsEmpty() {
        assertEquals(emptyList(), SubtilesProvider.parseOpenSubtitles("""{"junk":1}""", setOf("en")))
    }


    @Test
    fun parseSubSense_realShape_iso3LangsAndUrlDedupe() {
        val tracks = SubtilesProvider.parseSubSense(senseJson)
        assertEquals(2, tracks.size)
        assertEquals("English", tracks[0].lang)
        assertEquals("Hindi", tracks[1].lang)
        assertTrue(tracks[0].url.endsWith(".srt"))
        assertTrue(tracks[0].url.startsWith("https://dl.opensubtitles.org/"))
    }

    @Test
    fun parseSubSense_langFallsBackToIdToken() {
        val json = """{"subtitles":[{"id":"subsense-srt-opensubtitles-tam-0","url":"https://dl.opensubtitles.org/x.srt"}]}"""
        val tracks = SubtilesProvider.parseSubSense(json)
        assertEquals(1, tracks.size)
        assertEquals(LinkNaming.canonicalSubtitleName("tam"), tracks[0].lang)
    }

    @Test
    fun parseSubSense_capsPerLanguage() {
        val many = StringBuilder("""{"subtitles":[""")
        for (i in 1..8) {
            if (i > 1) many.append(",")
            many.append("""{"id":"x-$i","url":"https://dl.opensubtitles.org/$i.srt","lang":"eng"}""")
        }
        many.append("]}")
        val tracks = SubtilesProvider.parseSubSense(many.toString())
        assertEquals(SubtilesProvider.SENSE_MAX_PER_LANG, tracks.size)
    }


    @Test
    fun mergeGroups_priorityFirst_dedupeByUrl() {
        val priority = listOf(SubTrack("Hindi", "https://a/hi.vtt"), SubTrack("English", "https://a/en.vtt"))
        val rest = listOf(SubTrack("Tamil", "https://a/ta.vtt"))
        val retry = listOf(SubTrack("English", "https://a/en.vtt"), SubTrack("Urdu", "https://a/ur.vtt"))
        val merged = SubtilesProvider.mergeGroups(listOf(priority, retry, rest))
        assertEquals(listOf("Hindi", "English", "Urdu", "Tamil"), merged.map { it.lang })
        assertEquals(4, merged.map { it.url }.toSet().size, "url duplicate collapsed")
    }

    @Test
    fun mergeGroups_appliesPerLangCap() {
        val en = (1..8).map { SubTrack("English", "https://dl.opensubtitles.org/$it.srt") }
        val merged = SubtilesProvider.mergeGroups(listOf(en))
        assertEquals(SubtilesProvider.SENSE_MAX_PER_LANG, merged.size)
    }

    @Test
    fun stillMissing_removesCoveredNames() {
        val tracks = listOf(SubTrack("Hindi", "https://a/1"), SubTrack("English", "https://a/2"))
        val gaps = SubtilesProvider.stillMissing(setOf("Hindi", "English", "Tamil"), tracks)
        assertEquals(setOf("Tamil"), gaps)
    }


    @Test
    fun subtitleMenuName_indianCarriesNativeScript() {
        assertEquals("Tamil (\u0BA4\u0BAE\u0BB4\u0BCD)", LinkNaming.subtitleMenuName("Tamil", "tam"))
        assertEquals("Telugu (\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41)", LinkNaming.subtitleMenuName("Telugu", "te"))
    }

    @Test
    fun subtitleMenuName_hindiVariants() {
        assertEquals("Hindi (Hinglish)", LinkNaming.subtitleMenuName("Hindi", "Hinglish subs"))
        assertEquals("Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)", LinkNaming.subtitleMenuName("Hindi", "hi"))
        assertEquals("Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)", LinkNaming.subtitleMenuName("Hindi", "\u0939\u093F\u0928\u094D\u0926\u0940"))
    }

    @Test
    fun subtitleMenuName_otherLanguagesPlain() {
        assertEquals("Arabic", LinkNaming.subtitleMenuName("Arabic", "ar"))
        assertEquals("English", LinkNaming.subtitleMenuName("English", "eng"))
    }
}
