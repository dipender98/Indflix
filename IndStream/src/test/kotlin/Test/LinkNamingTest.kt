package Test

import com.indstream.LinkNaming
import com.indstream.StreamEngine.RawStream
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkNamingTest {

    private val IN = "\uD83C\uDDEE\uD83C\uDDF3"
    private val GB = "\uD83C\uDDEC\uD83C\uDDE7"
    private val JP = "\uD83C\uDDEF\uD83C\uDDF5"


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
    fun languageLabel_twoLanguagesListsBoth() {
        assertEquals("Hindi, English", LinkNaming.languageLabel("Hindi+English"))
        assertEquals("Hindi, English", LinkNaming.languageLabel("Hindi-English"))
        assertEquals("Hindi, English", LinkNaming.languageLabel("Dual Audio Hindi & English"))
        assertEquals("Hindi", LinkNaming.languageLabel("Hindi Dubbed"))
    }

    @Test
    fun languageLabel_moreThanTwoIsMulti_singleStaysSingle() {
        assertEquals("Multi", LinkNaming.languageLabel("Hindi+English+Tamil"))
        assertEquals("Multi", LinkNaming.languageLabel("Dual Audio"))
        assertEquals("Tamil", LinkNaming.languageLabel("Tamil"))
        assertEquals("", LinkNaming.languageLabel("Show 2024 1080p"))
    }

    @Test
    fun displayName_twoLanguagesShowsBothFlags() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi+English",
        )
        assertEquals("VidLink ($IN Hindi, $GB English)", name)
    }

    @Test
    fun displayName_threeLanguagesCollapseToMulti() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi+English+Tamil",
        )
        assertEquals("VidLink (Multi)", name)
    }

    @Test
    fun displayName_conflictingServerLanguageIsDropped() {
        val name = LinkNaming.displayName(
            serverName = "AnimeWorld English", audioLabel = "Japanese",
        )
        assertEquals("AnimeWorld (\uD83C\uDDEF\uD83C\uDDF5 Japanese)", name)
    }

    @Test
    fun displayName_qualityFromBrandIsNotRepeated() {
        val name = LinkNaming.displayName(
            serverName = "4KHDHub", audioLabel = "Hindi",
        )
        assertEquals("4KHDHub ($IN Hindi)", name)
    }

    @Test
    fun languageTagFor_explicitOverridesOriginal() {
        assertEquals("Hindi", LinkNaming.languageTagFor("Hindi", "ja"))
    }


    @Test
    fun displayName_firstRenditionKeepsLanguageFlagAndSize() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi",
            sizeLabel = "1.7GB",
        )
        assertEquals("VidLink ($IN Hindi) 1.7GB", name)
    }

    @Test
    fun displayName_englishNoResolution() {
        val name = LinkNaming.displayName(
            serverName = "VaPlayer", audioLabel = "English",
        )
        assertEquals("VaPlayer ($GB English)", name)
    }

    @Test
    fun displayName_multiAudio() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Multi",
        )
        assertEquals("VidLink (Multi)", name)
    }

    @Test
    fun displayName_hindiBrand_noRedundantTag() {
        val name = LinkNaming.displayName(
            serverName = "MyFlixer Hindi", audioLabel = "Hindi",
        )
        assertEquals("MyFlixer ($IN Hindi)", name)
    }

    @Test
    fun displayName_englishSuffix_strippedFlagKept() {
        val name = LinkNaming.displayName(
            serverName = "AnimeWorld English", audioLabel = "English",
        )
        assertEquals("AnimeWorld ($GB English)", name)
    }

    @Test
    fun displayName_resolutionInName_strippedNotAppended() {
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "Original",
            subIndicator = "Nova 1080p",
        )
        assertEquals("PrimeSrc Nova (Original)", name)
    }

    @Test
    fun displayName_resolutionGluedStripped() {
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "Hindi",
        )
        assertEquals("Server ($IN Hindi)", name)
    }

    @Test
    fun displayName_fourKStrippedFromName() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English",
        )
        assertEquals("MovieBox ($GB English)", name)
    }

    @Test
    fun displayName_fullAttributes() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "Hindi",
            sizeLabel = "1.7GB",
            mediaLabel = "Film 2024 1080p WEB-DL HEVC 10bit Dolby 5.1",
        )
        assertEquals("MovieBox WEB-DL HEVC 10bit Dolby 5.1 ($IN Hindi) 1.7GB", name)
    }

    @Test
    fun displayName_firstRenditionCarriesTokens() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "Hindi",
            mediaLabel = "HEVC DDP5.1", sizeLabel = "1.7GB",
        )
        assertEquals("MovieBox HEVC Dolby 5.1 ($IN Hindi) 1.7GB", name)
    }

    @Test
    fun displayName_unknownTokensStayBlank() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "English",
            sizeLabel = "1.1GB",
            mediaLabel = "CAMRip",
        )
        assertEquals("MovieBox CAMRip ($GB English) 1.1GB", name)
        assertEquals(
            "MovieBox ($GB English) 1.1GB",
            LinkNaming.displayName("MovieBox", "English", sizeLabel = "1.1GB"),
        )
    }

    @Test
    fun displayName_noAttributesAtAll() {
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "",
        )
        assertEquals("PrimeSrc (Unknown)", name)
    }

    @Test
    fun displayName_multiNoFlag() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Multi",
        )
        assertEquals("VidLink (Multi)", name)
    }

    @Test
    fun displayName_camripVisionTokens() {
        val name = LinkNaming.displayName(
            serverName = "NetMirror", audioLabel = "Hindi",
            mediaLabel = "HDCAM DDP5.1 DV X264",
        )
        assertEquals("NetMirror CAMRip H.264 Dolby Vision Dolby 5.1 ($IN Hindi)", name)
    }


    @Test
    fun displayName_autoQualityFromServerName() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer 1440p", audioLabel = "Tamil",
        )
        assertEquals("BrandNewServer ($IN Tamil)", name)
        assertEquals(1440, LinkNaming.qualityHeight("BrandNewServer 1440p"))
    }

    @Test
    fun displayName_autoTokensFromServerName_noDuplicates() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer WEB-DL HEVC", audioLabel = "Tamil",
        )
        assertEquals("BrandNewServer WEB-DL HEVC ($IN Tamil)", name)
    }

    @Test
    fun displayName_autoQuality2KFromServerName() {
        val name = LinkNaming.displayName(
            serverName = "BrandNewServer 2K", audioLabel = "Tamil",
        )
        assertEquals("BrandNewServer ($IN Tamil)", name)
        assertEquals(1440, LinkNaming.qualityHeight("BrandNewServer 2K"))
    }

    @Test
    fun displayName_duplicatesIdentical_noNumbering() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
        )
        streams.forEach { s ->
            assertEquals(
                "VidLink ($IN Hindi)",
                LinkNaming.displayName(serverName = s.serverName, audioLabel = s.audioLabel),
            )
        }
    }

    @Test
    fun displayName_fiveSameServer_allShareTheSameLabel() {
        val expected = "PrimeSrc ($GB English)"
        (1..5).forEach {
            val s = raw("PrimeSrc", "eng", 720)
            assertEquals(
                expected,
                LinkNaming.displayName(serverName = s.serverName, audioLabel = s.audioLabel),
            )
        }
    }

    @Test
    fun displayName_qualityNeverRepeated() {
        // the resolution is never embedded in the label, so it cannot be rendered twice.
        val name = LinkNaming.displayName(
            serverName = "FSL 1080p", audioLabel = "Hindi",
        )
        assertEquals("FSL ($IN Hindi)", name)
        assertEquals(0, countToken(name, """(?i)\b(?:720p|1080p|1440p|2160p|4k)\b"""))

        val name2 = LinkNaming.displayName(
            serverName = "FSL 720p", audioLabel = "English",
        )
        assertEquals("FSL ($GB English)", name2)
        assertEquals(0, countToken(name2, """(?i)\b(?:720p|1080p|1440p|2160p|4k)\b"""))

        val name3 = LinkNaming.displayName(
            serverName = "MovieBox", audioLabel = "Tamil",
            mediaLabel = "Film 1080p WEB-DL HEVC",
        )
        assertEquals("MovieBox WEB-DL HEVC ($IN Tamil)", name3)
        assertEquals(0, countToken(name3, """(?i)\b(?:720p|1080p|1440p|2160p|4k)\b"""))
    }

    @Test
    fun displayName_explicitAudioLanguageWinsOverServerName() {
        // English-only audio must not be relabeled Hindi just because the server name says Hindi.
        val name = LinkNaming.displayName(
            serverName = "MovieServer Hindi", audioLabel = "English",
        )
        assertEquals("MovieServer ($GB English)", name)

        val name2 = LinkNaming.displayName(
            serverName = "MovieServer Hindi", audioLabel = "English",
            mediaLabel = "Film 1080p",
        )
        assertEquals("MovieServer ($GB English)", name2)
        assertEquals(1080, LinkNaming.qualityHeight("Film 1080p"))
    }

    @Test
    fun displayName_qualityVariantsNeverRepeat() {
        listOf(
            "FSL 1080 P",
            "FSL HD",
            "FSL 1080p60",
            "FSL UHD",
            "FSL 4K HD",
        ).forEach { server ->
            val name = LinkNaming.displayName(server, "Hindi")
            assertEquals("FSL ($IN Hindi)", name)
            assertEquals(0, countToken(name, """(?i)\b(?:720p|1080p|1440p|2160p|4k|uhd|fhd)\b"""))
        }
        assertEquals(1080, LinkNaming.qualityHeight("FSL 1080 P"))
        assertEquals(2160, LinkNaming.qualityHeight("FSL UHD"))
        assertEquals(2160, LinkNaming.qualityHeight("FSL 4K HD"))
    }

    @Test
    fun displayName_languageFromServerSuffixAddsFlag() {
        listOf(
            "MovieServer Hindi",
            "MovieServer (Hindi)",
            "MovieServer [Hindi]",
            "MovieServer Hindi 1080p",
            "MovieServer Hindi Dubbed",
        ).forEach { server ->
            assertEquals(
                "MovieServer ($IN Hindi)",
                LinkNaming.displayName(server, audioLabel = ""),
            )
        }
        assertEquals(
            "MovieServer ($GB English)",
            LinkNaming.displayName("MovieServer English", audioLabel = ""),
        )
    }

    @Test
    fun displayName_originalLangLabelGetsFlag() {
        // Original-language fallback resolves to the audio language and must still carry its flag.
        assertEquals(
            "AnimeWorld ($JP Japanese)",
            LinkNaming.displayName(
                "AnimeWorld", audioLabel = "", originalLang = "ja",
            ),
        )
    }

    @Test
    fun displayName_titleLanguageIsNotTreatedAsAudio() {
        val name = LinkNaming.displayName("Hindi Medium", audioLabel = "")
        assertEquals("Hindi Medium (Unknown)", name)
    }

    @Test
    fun displayName_explicitMultiOrOriginalIsNotOverriddenByServerName() {
        assertEquals(
            "MovieServer (Multi)",
            LinkNaming.displayName("MovieServer Hindi", audioLabel = "Multi"),
        )
        assertEquals(
            "MovieServer (Original)",
            LinkNaming.displayName("MovieServer Hindi", audioLabel = "Original"),
        )
    }

    @Test
    fun qualityHeight_parsesTextIntoNumericHeight() {
        assertEquals(2160, LinkNaming.qualityHeight("Movie 4K UHD"))
        assertEquals(2160, LinkNaming.qualityHeight("Movie 2160p"))
        assertEquals(1440, LinkNaming.qualityHeight("Movie 2K"))
        assertEquals(1440, LinkNaming.qualityHeight("Movie 1440p"))
        assertEquals(1080, LinkNaming.qualityHeight("Movie 1080p"))
        assertEquals(720, LinkNaming.qualityHeight("Movie 720p"))
        assertEquals(0, LinkNaming.qualityHeight("Movie"))
        assertEquals(0, LinkNaming.qualityHeight(null))
        assertEquals(1080, LinkNaming.qualityHeight("Movie", "1080p"))
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
    fun displayName_directUnknownShowsNoResolution() {
        val name = LinkNaming.displayName("VidRock", "Hindi")
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
