package Test

import com.indstream.LinkNaming
import com.indstream.StreamEngine.RawStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkNamingTest {

    private val IN = "\uD83C\uDDEE\uD83C\uDDF3"
    private val GB = "\uD83C\uDDEC\uD83C\uDDE7"


    @Test
    fun languageTag_hindiEnglishMulti() {
        assertEquals("Hindi", LinkNaming.languageTag("Hindi"))
        assertEquals("Hindi", LinkNaming.languageTag("hi"))
        assertEquals("Hindi", LinkNaming.languageTag("हिन्दी"))
        assertEquals("English", LinkNaming.languageTag("English"))
        assertEquals("English", LinkNaming.languageTag("en"))
        assertEquals("English", LinkNaming.languageTag("eng"))
        assertEquals("Multi", LinkNaming.languageTag("Hindi+English"))
        assertEquals("Multi", LinkNaming.languageTag("Dual Audio"))
        assertEquals("Multi", LinkNaming.languageTag("both"))
    }

    @Test
    fun languageTag_nativeScriptNames() {
        assertEquals("Urdu", LinkNaming.languageTag("اُردُو"))
        assertEquals("Urdu", LinkNaming.languageTag("urdu"))
        assertEquals("Bengali", LinkNaming.languageTag("বাংলা"))
        assertEquals("Arabic", LinkNaming.languageTag("العربية"))
        assertEquals("Russian", LinkNaming.languageTag("Русский"))
        assertEquals("Chinese", LinkNaming.languageTag("中文"))
        assertEquals("Japanese", LinkNaming.languageTag("日本語"))
    }

    @Test
    fun languageTag_blankIsUnknown() {
        assertEquals("Unknown", LinkNaming.languageTag(""))
        assertEquals("Unknown", LinkNaming.languageTag(null))
    }


    @Test
    fun languageTagFor_originalUsesTmdbCode() {
        assertEquals("Japanese", LinkNaming.languageTagFor("Original", "ja"))
        assertEquals("Unknown", LinkNaming.languageTagFor("", "hi"))
        assertEquals("Unknown", LinkNaming.languageTagFor(null, "en"))
    }

    @Test
    fun languageTagFor_explicitOverridesOriginal() {
        assertEquals("Hindi", LinkNaming.languageTagFor("Hindi", "ja"))
    }


    @Test
    fun displayName_firstRenditionKeepsLanguageFlagAndSize() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi", qualityHint = 1080,
            sizeLabel = "1.7GB",
        )
        assertEquals("VidLink ($IN Hindi) 1080p 1.7GB", name)
    }

    @Test
    fun displayName_englishNoResolution() {
        val name = LinkNaming.displayName(
            serverName = "VaPlayer", audioLabel = "English", qualityHint = 0,
        )
        assertEquals("VaPlayer ($GB English)", name)
    }

    @Test
    fun displayName_multiAudio() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Multi", qualityHint = 1080,
        )
        assertEquals("VidLink (Multi) 1080p", name)
    }

    @Test
    fun displayName_hindiBrand_noRedundantTag() {
        val name = LinkNaming.displayName(
            serverName = "MyFlixer Hindi", audioLabel = "Hindi", qualityHint = 1080,
            duplicateIndex = 1,
        )
        assertEquals("MyFlixer-1 ($IN Hindi) 1080p", name)
    }

    @Test
    fun displayName_englishSuffix_strippedFlagKept() {
        val name = LinkNaming.displayName(
            serverName = "AnimeWorld English", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("AnimeWorld ($GB English) 1080p", name)
    }

    @Test
    fun displayName_resolutionInName_strippedNotAppended() {
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "Original", qualityHint = 1080,
            subIndicator = "Nova 1080p",
        )
        assertEquals("PrimeSrc Nova (Original) 1080p", name)
    }

    @Test
    fun displayName_resolutionGluedStripped() {
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "Hindi", qualityHint = 1080,
            duplicateIndex = 1,
        )
        assertEquals("Server-1 ($IN Hindi) 1080p", name)
    }

    @Test
    fun displayName_fourKStrippedFromName() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English", qualityHint = 2160,
            duplicateIndex = 1,
        )
        assertEquals("MovieBox-1 ($GB English) 4K", name)
    }

    @Test
    fun displayName_numbered_fullAttributes() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "Hindi", qualityHint = 1080,
            duplicateIndex = 1, sizeLabel = "1.7GB",
            mediaLabel = "Film 2024 1080p WEB-DL HEVC 10bit Dolby 5.1",
        )
        assertEquals("MovieBox-1 WEB-DL HEVC 10bit Dolby 5.1 ($IN Hindi) 1080p 1.7GB", name)
    }

    @Test
    fun displayName_firstRenditionCarriesTokens() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "Hindi", qualityHint = 1080,
            mediaLabel = "HEVC DDP5.1", sizeLabel = "1.7GB",
        )
        assertEquals("MovieBox HEVC Dolby 5.1 ($IN Hindi) 1080p 1.7GB", name)
    }

    @Test
    fun displayName_unknownTokensStayBlank() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "English",
            duplicateIndex = 2, sizeLabel = "1.1GB",
            mediaLabel = "CAMRip",
        )
        assertEquals("MovieBox-2 CAMRip ($GB English) 1.1GB", name)
        assertEquals(
            "MovieBox-2 ($GB English) 1.1GB",
            LinkNaming.displayName("MovieBox", "English", duplicateIndex = 2, sizeLabel = "1.1GB"),
        )
    }

    @Test
    fun displayName_numbered_noAttributesAtAll() {
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "", qualityHint = 0,
            duplicateIndex = 2,
        )
        assertEquals("PrimeSrc-2 (Unknown)", name)
    }

    @Test
    fun displayName_numbered_multiNoFlag() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Multi", qualityHint = 1080,
            duplicateIndex = 1,
        )
        assertEquals("VidLink-1 (Multi) 1080p", name)
    }

    @Test
    fun displayName_numbered_camripVisionTokens() {
        val name = LinkNaming.displayName(
            serverName = "NetMirror", audioLabel = "Hindi", qualityHint = 720,
            duplicateIndex = 1,
            mediaLabel = "HDCAM DDP5.1 DV X264",
        )
        assertEquals("NetMirror-1 CAMRip H.264 Dolby Vision Dolby 5.1 ($IN Hindi) 720p", name)
    }


    @Test
    fun dedupe_uniqueKeepsNoNumber() {
        val s = listOf(raw("VidLink", "Hindi", 1080))
        val nums = LinkNaming.dedupeNames(s)
        assertEquals(0, nums[0], "unique stream must get index 0 (no number)")
    }


    @Test
    fun dedupe_sameServer_numbered0Onward() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(0, nums[0])
        assertEquals(1, nums[1])
    }


    @Test
    fun dedupe_fiveIdentical_numbered0to4() {
        val streams = (1..5).map { raw("PrimeSrc", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i, nums[i]) }
    }


    @Test
    fun dedupe_twentyIdentical_numbered0to19() {
        val streams = (1..20).map { raw("Server", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i, nums[i]) }
    }


    @Test
    fun dedupe_sameServerAcrossQualityAndLanguage_groupedTogether() {
        val streams = listOf(
            raw("MovieBox 1080p", "Hindi", 1080, serverId = "moviebox"),
            raw("MovieBox 720p", "English", 720, serverId = "moviebox"),
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(0, nums[0])
        assertEquals(1, nums[1])
    }


    @Test
    fun dedupe_mixedServers_independent() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
            raw("VaPlayer", "English", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(0, nums[0])
        assertEquals(1, nums[1])
        assertEquals(0, nums[2], "different server is independent")
    }


    @Test
    fun displayName_autoQualityFromServerName() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer 1440p", audioLabel = "Tamil", qualityHint = 0,
        )
        assertEquals("BrandNewServer ($IN Tamil) 1440p", name)
    }

    @Test
    fun displayName_autoTokensFromServerName_noDuplicates() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer WEB-DL HEVC", audioLabel = "Tamil", qualityHint = 1080,
        )
        assertEquals("BrandNewServer WEB-DL HEVC ($IN Tamil) 1080p", name)
    }

    @Test
    fun displayName_autoQuality2KFromServerName() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer 2K", audioLabel = "Tamil", qualityHint = 0,
        )
        assertEquals("BrandNewServer ($IN Tamil) 2K", name)
    }

    @Test
    fun displayName_withNumbering_firstPlainRestNumbered() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        val names = streams.mapIndexed { i, s ->
            LinkNaming.displayName(
                serverName = s.serverName, audioLabel = s.audioLabel,
                qualityHint = s.qualityHint, duplicateIndex = nums[i],
            )
        }
        assertEquals("VidLink ($IN Hindi) 1080p", names[0])
        assertEquals("VidLink-1 ($IN Hindi) 1080p", names[1])
    }

    @Test
    fun displayName_withNumbering_fiveMembers() {
        val streams = (1..5).map { raw("PrimeSrc", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, s ->
            val name = LinkNaming.displayName(
                serverName = s.serverName, audioLabel = s.audioLabel,
                qualityHint = s.qualityHint, duplicateIndex = nums[i],
            )
            assertEquals(
                if (i == 0) "PrimeSrc ($GB English) 720p" else "PrimeSrc-$i ($GB English) 720p",
                name,
            )
        }
    }


    @Test
    fun canonicalSubtitle_nativeScript() {
        assertEquals("Urdu", LinkNaming.canonicalSubtitleName("اُردُو"))
        assertEquals("Bengali", LinkNaming.canonicalSubtitleName("বাংলা"))
        assertEquals("Arabic", LinkNaming.canonicalSubtitleName("العربية"))
        assertEquals("Russian", LinkNaming.canonicalSubtitleName("Русский"))
        assertEquals("Chinese", LinkNaming.canonicalSubtitleName("中文"))
        assertEquals("Japanese", LinkNaming.canonicalSubtitleName("日本語"))
        assertEquals("Korean", LinkNaming.canonicalSubtitleName("한국어"))
        assertEquals("French", LinkNaming.canonicalSubtitleName("Français"))
        assertEquals("Portuguese", LinkNaming.canonicalSubtitleName("Português"))
        assertEquals("Spanish", LinkNaming.canonicalSubtitleName("Español"))
    }

    @Test
    fun canonicalSubtitle_englishCodes() {
        assertEquals("Hindi", LinkNaming.canonicalSubtitleName("hi"))
        assertEquals("English", LinkNaming.canonicalSubtitleName("en"))
        assertEquals("English", LinkNaming.canonicalSubtitleName("English"))
        assertEquals("Arabic", LinkNaming.canonicalSubtitleName("ara"))
        assertEquals("German", LinkNaming.canonicalSubtitleName("ger"))
        assertEquals("Hindi", LinkNaming.canonicalSubtitleName("hin"))
    }

    @Test
    fun canonicalSubtitle_blankFallback() {
        assertEquals("Subtitle", LinkNaming.canonicalSubtitleName(""))
        assertEquals("Subtitle", LinkNaming.canonicalSubtitleName(null))
    }


    @Test
    fun qualityLabel_commonHeights() {
        assertEquals("4K", LinkNaming.qualityLabel(2160))
        assertEquals("1080p", LinkNaming.qualityLabel(1080))
        assertEquals("720p", LinkNaming.qualityLabel(720))
        assertEquals("", LinkNaming.qualityLabel(0))
        assertEquals("Auto", LinkNaming.qualityLabel(-1))
    }

    @Test
    fun displayName_directUnknownShowsNoResolution() {
        val name = LinkNaming.displayName("VidRock", "Hindi", qualityHint = -1)
        assertEquals("VidRock ($IN Hindi)", name)
    }

    @Test
    fun renditionTokens_sourceAudioCodecOrder() {
        assertEquals(
            listOf("WEB-DL", "HEVC", "10bit", "Dolby 5.1"),
            LinkNaming.renditionTokens("WEB-DL HEVC 10bit Dolby 5.1"),
        )
        assertEquals(
            listOf("BluRay", "HEVC", "DTS-HD"),
            LinkNaming.renditionTokens("BluRay DTS-HD MA x265"),
        )
        assertEquals(
            listOf("CAMRip", "H.264", "AAC"),
            LinkNaming.renditionTokens("CamRip AAC x264"),
        )
    }

    @Test
    fun renditionTokens_knownVariantsAndCase() {
        assertEquals(listOf("WEBRip"), LinkNaming.renditionTokens("webrip"))
        assertEquals(listOf("WEB-DL"), LinkNaming.renditionTokens("Web-DL"))
        assertEquals(listOf("HDRip"), LinkNaming.renditionTokens("HdRip"))
        assertEquals(listOf("Dolby Vision", "HDR10+"), LinkNaming.renditionTokens("DV HDR10+"))
        assertEquals(listOf("AV1"), LinkNaming.renditionTokens("codec: av1"))
    }

    @Test
    fun renditionTokens_blankOrNoTokens() {
        assertEquals(emptyList(), LinkNaming.renditionTokens(null))
        assertEquals(emptyList(), LinkNaming.renditionTokens(""))
        assertEquals(emptyList(), LinkNaming.renditionTokens("Film 2024"))
    }

    @Test
    fun languageFlag_mapsKnown_skipsMultiUnknownOriginal() {
        assertEquals("\uD83C\uDDEE\uD83C\uDDF3", LinkNaming.languageFlag("Hindi"))
        assertEquals("\uD83C\uDDEC\uD83C\uDDE7", LinkNaming.languageFlag("English"))
        assertEquals("\uD83C\uDDEF\uD83C\uDDF5", LinkNaming.languageFlag("Japanese"))
        assertEquals("", LinkNaming.languageFlag("Multi"))
        assertEquals("", LinkNaming.languageFlag("Unknown"))
        assertEquals("", LinkNaming.languageFlag("Original"))
        assertEquals("", LinkNaming.languageFlag(null))
    }

    @Test
    fun taggedSubtitleName_tagsSource() {
        assertEquals("Hindi (VidLink)", LinkNaming.taggedSubtitleName("Hindi", "VidLink"))
        assertEquals("English (MovieBox)", LinkNaming.taggedSubtitleName("English", "MovieBox"))
        assertEquals("Hindi (Fallback)", LinkNaming.taggedSubtitleName("Hindi", "Fallback"))
    }

    @Test
    fun taggedSubtitleName_blankSourceUnchanged() {
        assertEquals("Hindi", LinkNaming.taggedSubtitleName("Hindi", null))
        assertEquals("Hindi", LinkNaming.taggedSubtitleName("Hindi", ""))
        assertEquals("Hindi", LinkNaming.taggedSubtitleName("Hindi", "  "))
    }

    @Test
    fun taggedSubtitleName_neverDuplicatesSource() {
        assertEquals("Hindi (VidLink)", LinkNaming.taggedSubtitleName("Hindi (VidLink)", "VidLink"))
    }

    private fun raw(
        serverName: String,
        audioLabel: String,
        qualityHint: Int,
        serverId: String = serverName.lowercase(),
    ) = RawStream(
        serverId = serverId, serverName = serverName,
        url = "https://example.com/${serverName.hashCode()}.m3u8",
        isM3u8 = true, qualityHint = qualityHint, audioLabel = audioLabel,
    )

    private fun countToken(label: String, tokenRegex: String) =
        Regex(tokenRegex, RegexOption.IGNORE_CASE).findAll(label).count()
}
