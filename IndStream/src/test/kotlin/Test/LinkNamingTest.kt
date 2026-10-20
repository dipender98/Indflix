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
    fun displayName_uniqueServer_noNumber() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("VidLink ($IN Hindi)", name)
    }

    @Test
    fun displayName_hindiBrand_noRedundantTag() {
        val name = LinkNaming.displayName(
            serverName = "MyFlixer Hindi", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("MyFlixer Hindi", name)
    }

    @Test
    fun displayName_resolutionInName_strippedNotAppended() {
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "Original", qualityHint = 1080,
            subIndicator = "Nova 1080p",
        )
        assertEquals("PrimeSrc Nova (Original)", name)
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
        assertEquals("VidLink (Multi)", name)
    }

    @Test
    fun displayName_resolutionGluedStripped() {
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("Server ($IN Hindi)", name)
    }

    @Test
    fun displayName_fourKStrippedFromName() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("MovieBox ($GB English)", name)
    }

    @Test
    fun displayName_height2160p_rawHeightTokenStripped() {
        val name = LinkNaming.displayName(
            serverName = "Movie 2160p", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("Movie ($GB English)", name)
    }

    @Test
    fun displayName_noHeightInName_appendsNothing() {
        val name = LinkNaming.displayName(
            serverName = "Movie", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("Movie ($GB English)", name)
    }

    @Test
    fun displayName_height1080p_rawHeightTokenStripped() {
        val name = LinkNaming.displayName(
            serverName = "Movie 1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Movie ($GB English)", name)
    }


    @Test
    fun displayName_revision_a_serverTokenStripped_nothingAppended() {
        val name = LinkNaming.displayName(
            serverName = "Server 1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Server ($GB English)", name)
        assertEquals(0, countToken(name, """\d{3,4}p|4K"""))
    }

    @Test
    fun displayName_revision_b_gluedToken_stripped() {
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Server ($GB English)", name)
        assertEquals(0, countToken(name, """\d{3,4}p|4K"""), "no resolution in name: $name")
    }

    @Test
    fun displayName_revision_c_fourKNameToken_stripped() {
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("MovieBox ($GB English)", name)
        assertEquals(0, countToken(name, """4K"""))
    }

    @Test
    fun displayName_revision_d_staleWrongToken_neverShown() {
        val name = LinkNaming.displayName(
            serverName = "VidEm 1080p", audioLabel = "English", qualityHint = 720,
        )
        assertEquals("VidEm ($GB English)", name)
    }

    @Test
    fun displayName_revision_e_stripKeepsDuplicateNumbering() {
        val streams = listOf(
            raw("VidLink 1080p", "English", 1080),
            raw("VidLink 1080p", "English", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        val names = streams.mapIndexed { i, s ->
            LinkNaming.displayName(
                serverName = s.serverName, audioLabel = s.audioLabel,
                qualityHint = s.qualityHint, duplicateIndex = nums[i],
            )
        }
        assertEquals("VidLink-1 ($GB English)", names[0])
        assertEquals("VidLink-2 ($GB English)", names[1])
    }


    @Test
    fun dedupe_uniqueKeepsNoNumber() {
        val s = listOf(raw("VidLink", "Hindi", 1080))
        val nums = LinkNaming.dedupeNames(s)
        assertEquals(0, nums[0], "unique stream must get index 0 (no number)")
    }


    @Test
    fun dedupe_twoIdentical_numbered1and2() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(1, nums[0])
        assertEquals(2, nums[1])
    }


    @Test
    fun dedupe_fiveIdentical_numbered1to5() {
        val streams = (1..5).map { raw("VidLink", "Hindi", 1080) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i + 1, nums[i]) }
    }


    @Test
    fun dedupe_twentyIdentical_numbered1to20() {
        val streams = (1..20).map { raw("Server", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i + 1, nums[i]) }
    }


    @Test
    fun dedupe_mixedGroups_independent() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),
            raw("VidLink", "Hindi", 1080),
            raw("VaPlayer", "English", 1080),
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(1, nums[0])
        assertEquals(2, nums[1])
        assertEquals(0, nums[2], "different group is independent")
    }


    @Test
    fun displayName_withNumbering_twoIdentical() {
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
        assertEquals("VidLink-1 ($IN Hindi)", names[0])
        assertEquals("VidLink-2 ($IN Hindi)", names[1])
    }

    @Test
    fun displayName_withNumbering_fiveIdentical() {
        val streams = (1..5).map { raw("PrimeSrc", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, s ->
            val name = LinkNaming.displayName(
                serverName = s.serverName, audioLabel = s.audioLabel,
                qualityHint = s.qualityHint, duplicateIndex = nums[i],
            )
            assertEquals("PrimeSrc-${i + 1} ($GB English)", name)
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

    private fun raw(serverName: String, audioLabel: String, qualityHint: Int) =
        RawStream(
            serverId = "test", serverName = serverName,
            url = "https://example.com/${serverName.hashCode()}.m3u8",
            isM3u8 = true, qualityHint = qualityHint, audioLabel = audioLabel,
        )

    private fun countToken(label: String, tokenRegex: String) =
        Regex(tokenRegex, RegexOption.IGNORE_CASE).findAll(label).count()
}
