package Test

import com.indstream.LinkNaming
import com.indstream.StreamEngine.RawStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FILE: LinkNamingTest.kt — guards the server-name formatting rules
 * defined in [LinkNaming] (Sept 2026 user spec).
 *
 *  - Language tag mapping (raw strings → canonical short tokens).
 *  - Display name assembly (brackets, resolution dedupe, server numbering).
 *  - Subtitle language normalisation (native-script → canonical English).
 *  - Group numbering: unique = no number; 2 identical → "-1"/"-2";
 *    5 identical → "-1"…"-5"; 20 identical → "-1"…"-20".
 */
class LinkNamingTest {

    // ── languageTag ──────────────────────────────────────────────

    @Test
    fun languageTag_hindiEnglishMulti() {
        // User spec Sept 2026: FULL names, capital initial — never "eng"/
        // "hindi" short forms.
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
    fun languageTag_blankDefaultsToMulti() {
        assertEquals("Multi", LinkNaming.languageTag(""))
        assertEquals("Multi", LinkNaming.languageTag(null))
    }

    // ── languageTagFor ───────────────────────────────────────────

    @Test
    fun languageTagFor_originalUsesTmdbCode() {
        assertEquals("Japanese", LinkNaming.languageTagFor("Original", "ja"))
        assertEquals("Hindi", LinkNaming.languageTagFor("", "hi"))
        assertEquals("English", LinkNaming.languageTagFor(null, "en"))
    }

    @Test
    fun languageTagFor_explicitOverridesOriginal() {
        // Explicit "Hindi" stays Hindi even if TMDB says Japanese.
        assertEquals("Hindi", LinkNaming.languageTagFor("Hindi", "ja"))
    }

    // ── displayName ──────────────────────────────────────────────

    @Test
    fun displayName_uniqueServer_noNumber() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("VidLink (Hindi) 1080p", name)
    }

    @Test
    fun displayName_hindiBrand_noRedundantTag() {
        // "MyFlixer Hindi" already carries "hindi" → bracket is suppressed.
        val name = LinkNaming.displayName(
            serverName = "MyFlixer Hindi", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("MyFlixer Hindi 1080p", name)
    }

    @Test
    fun displayName_resolutionInName_noDuplicate() {
        // "Server 1080p" has resolution in the indicator → quality NOT appended.
        val name = LinkNaming.displayName(
            serverName = "PrimeSrc", audioLabel = "Original", qualityHint = 1080,
            subIndicator = "Nova 1080p",
        )
        assertEquals("PrimeSrc Nova 1080p (Original)", name)
    }

    @Test
    fun displayName_englishNoResolution() {
        val name = LinkNaming.displayName(
            serverName = "VaPlayer", audioLabel = "English", qualityHint = 0,
        )
        assertEquals("VaPlayer (English)", name)
    }

    @Test
    fun displayName_multiAudio() {
        val name = LinkNaming.displayName(
            serverName = "VidLink", audioLabel = "Multi", qualityHint = 1080,
        )
        assertEquals("VidLink (Multi) 1080p", name)
    }

    // ── numbering: unique → no number ────────────────────────────

    @Test
    fun dedupe_uniqueKeepsNoNumber() {
        val s = listOf(raw("VidLink", "Hindi", 1080))
        val nums = LinkNaming.dedupeNames(s)
        assertEquals(0, nums[0], "unique stream must get index 0 (no number)")
    }

    // ── numbering: 2 identical → -1 / -2 ─────────────────────────

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

    // ── numbering: 5 identical → -1 … -5 ─────────────────────────

    @Test
    fun dedupe_fiveIdentical_numbered1to5() {
        val streams = (1..5).map { raw("VidLink", "Hindi", 1080) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i + 1, nums[i]) }
    }

    // ── numbering: 20 identical → -1 … -20 ───────────────────────

    @Test
    fun dedupe_twentyIdentical_numbered1to20() {
        val streams = (1..20).map { raw("Server", "eng", 720) }
        val nums = LinkNaming.dedupeNames(streams)
        streams.forEachIndexed { i, _ -> assertEquals(i + 1, nums[i]) }
    }

    // ── numbering: mixed groups independent ──────────────────────

    @Test
    fun dedupe_mixedGroups_independent() {
        val streams = listOf(
            raw("VidLink", "Hindi", 1080),   // [0]
            raw("VidLink", "Hindi", 1080),   // [1]
            raw("VaPlayer", "English", 1080),// [2]
        )
        val nums = LinkNaming.dedupeNames(streams)
        assertEquals(1, nums[0])
        assertEquals(2, nums[1])
        assertEquals(0, nums[2], "different group is independent")
    }

    // ── integrated displayName with numbering ────────────────────

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
        assertEquals("VidLink-1 (Hindi) 1080p", names[0])
        assertEquals("VidLink-2 (Hindi) 1080p", names[1])
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
            assertEquals("PrimeSrc-${i + 1} (English) 720p", name)
        }
    }

    // ── canonicalSubtitleName ─────────────────────────────────────

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
        // 3-letter ISO 639-2 codes (OpenSubtitles addon shape, verified live).
        assertEquals("Arabic", LinkNaming.canonicalSubtitleName("ara"))
        assertEquals("German", LinkNaming.canonicalSubtitleName("ger"))
        assertEquals("Hindi", LinkNaming.canonicalSubtitleName("hin"))
    }

    @Test
    fun canonicalSubtitle_blankFallback() {
        assertEquals("Subtitle", LinkNaming.canonicalSubtitleName(""))
        assertEquals("Subtitle", LinkNaming.canonicalSubtitleName(null))
    }

    // ── qualityLabel ──────────────────────────────────────────────

    @Test
    fun qualityLabel_commonHeights() {
        assertEquals("4K", LinkNaming.qualityLabel(2160))
        assertEquals("1080p", LinkNaming.qualityLabel(1080))
        assertEquals("720p", LinkNaming.qualityLabel(720))
        assertEquals("", LinkNaming.qualityLabel(0))
    }

    // ── helpers ──────────────────────────────────────────────────

    /** Minimal RawStream for naming tests — url/quality are irrelevant. */
    private fun raw(serverName: String, audioLabel: String, qualityHint: Int) =
        RawStream(
            serverId = "test", serverName = serverName,
            url = "https://example.com/${serverName.hashCode()}.m3u8",
            isM3u8 = true, qualityHint = qualityHint, audioLabel = audioLabel,
        )
}
