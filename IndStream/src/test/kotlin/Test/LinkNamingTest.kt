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
 *  - Display name assembly (brackets, NO resolution token — CloudStream's
 *    quality badge is the only resolution print, user spec Sept 2026
 *    revision — server numbering).
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
    fun languageTag_blankIsUnknown() {
        // User spec Sept 2026: an audio we can't determine must read "Unknown",
        // never a guessed "Multi"/"English".
        assertEquals("Unknown", LinkNaming.languageTag(""))
        assertEquals("Unknown", LinkNaming.languageTag(null))
    }

    // ── languageTagFor ───────────────────────────────────────────

    @Test
    fun languageTagFor_originalUsesTmdbCode() {
        // EXPLICIT "Original" from a resolver maps to the title's language.
        assertEquals("Japanese", LinkNaming.languageTagFor("Original", "ja"))
        // BLANK is a genuine unknown — never inferred from TMDB (a blank stream of a
        // Telugu title may be a Hindi dub; guessing would mismatch it).
        assertEquals("Unknown", LinkNaming.languageTagFor("", "hi"))
        assertEquals("Unknown", LinkNaming.languageTagFor(null, "en"))
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
        assertEquals("VidLink (Hindi)", name)
    }

    @Test
    fun displayName_hindiBrand_noRedundantTag() {
        // "MyFlixer Hindi" already carries "hindi" → bracket is suppressed.
        val name = LinkNaming.displayName(
            serverName = "MyFlixer Hindi", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("MyFlixer Hindi", name)
    }

    @Test
    fun displayName_resolutionInName_strippedNotAppended() {
        // Sub-indicator carries "1080p": the token is STRIPPED and nothing is
        // appended — CloudStream's quality badge shows the resolution (user
        // spec Sept 2026 revision).
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
        assertEquals("VaPlayer (English)", name)
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
        // Resolution glued to a word ("Server1080p"): the strip regex catches
        // it — the name renders "Server" with NO resolution anywhere in it.
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "Hindi", qualityHint = 1080,
        )
        assertEquals("Server (Hindi)", name)
    }

    @Test
    fun displayName_fourKStrippedFromName() {
        // "4K" in the name is stripped; nothing is appended.
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("MovieBox (English)", name)
    }

    @Test
    fun displayName_height2160p_rawHeightTokenStripped() {
        // Server name carries the raw "2160p" token: stripped, nothing appended.
        val name = LinkNaming.displayName(
            serverName = "Movie 2160p", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("Movie (English)", name)
    }

    @Test
    fun displayName_noHeightInName_appendsNothing() {
        // Server name has no resolution token and a known height — still NO
        // token in the name (the player's badge shows it).
        val name = LinkNaming.displayName(
            serverName = "Movie", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("Movie (English)", name)
    }

    @Test
    fun displayName_height1080p_rawHeightTokenStripped() {
        // "Movie 1080p": the guessed token is stripped from the name.
        val name = LinkNaming.displayName(
            serverName = "Movie 1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Movie (English)", name)
    }

    // ── NO resolution token in names (user spec Sept 2026 revision) ──

    @Test
    fun displayName_revision_a_serverTokenStripped_nothingAppended() {
        // (a) "Server 1080p" → zero resolution tokens in the label.
        val name = LinkNaming.displayName(
            serverName = "Server 1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Server (English)", name)
        assertEquals(0, countToken(name, """\d{3,4}p|4K"""))
    }

    @Test
    fun displayName_revision_b_gluedToken_stripped() {
        // (b) glued "Server1080p" — the strip regex catches it.
        val name = LinkNaming.displayName(
            serverName = "Server1080p", audioLabel = "English", qualityHint = 1080,
        )
        assertEquals("Server (English)", name)
        assertEquals(0, countToken(name, """\d{3,4}p|4K"""), "no resolution in name: $name")
    }

    @Test
    fun displayName_revision_c_fourKNameToken_stripped() {
        // (c) "MovieBox 4K" → removed.
        val name = LinkNaming.displayName(
            serverName = "MovieBox 4K", audioLabel = "English", qualityHint = 2160,
        )
        assertEquals("MovieBox (English)", name)
        assertEquals(0, countToken(name, """4K"""))
    }

    @Test
    fun displayName_revision_d_staleWrongToken_neverShown() {
        // (d) name carries a STALE token ("1080p") while the measured height
        // is 720: the guess is stripped and the real height belongs to the
        // player's badge, not the label.
        val name = LinkNaming.displayName(
            serverName = "VidEm 1080p", audioLabel = "English", qualityHint = 720,
        )
        assertEquals("VidEm (English)", name)
    }

    @Test
    fun displayName_revision_e_stripKeepsDuplicateNumbering() {
        // (e) the "-N" duplicate suffix must survive the strip: two identical
        // "VidLink 1080p" members (hint 1080) → "VidLink-1 …"/"VidLink-2 …".
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
        assertEquals("VidLink-1 (English)", names[0])
        assertEquals("VidLink-2 (English)", names[1])
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
        assertEquals("VidLink-1 (Hindi)", names[0])
        assertEquals("VidLink-2 (Hindi)", names[1])
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
            assertEquals("PrimeSrc-${i + 1} (English)", name)
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
        // -1 sentinel: a DIRECT file whose real height could not be measured → "Auto"
        // (never a guessed 1080p). 0 stays blank (adaptive HLS).
        assertEquals("Auto", LinkNaming.qualityLabel(-1))
    }

    @Test
    fun displayName_directUnknownShowsNoResolution() {
        // Regression (revised spec): a measured-unknown direct file gets no
        // guessed token — and with the new rule NO name carries resolution at
        // all, so "Auto"/"1080p" can never appear in the label.
        val name = LinkNaming.displayName("VidRock", "Hindi", qualityHint = -1)
        assertEquals("VidRock (Hindi)", name)
    }

    // ── taggedSubtitleName ───────────────────────────────────────

    @Test
    fun taggedSubtitleName_tagsSource() {
        assertEquals("Hindi (VidLink)", LinkNaming.taggedSubtitleName("Hindi", "VidLink"))
        assertEquals("English (MovieBox)", LinkNaming.taggedSubtitleName("English", "MovieBox"))
        // Fallback provenance tag.
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
        // Already-tagged canonical name is not double-tagged.
        assertEquals("Hindi (VidLink)", LinkNaming.taggedSubtitleName("Hindi (VidLink)", "VidLink"))
    }

    // ── helpers ──────────────────────────────────────────────────

    /** Minimal RawStream for naming tests — url/quality are irrelevant. */
    private fun raw(serverName: String, audioLabel: String, qualityHint: Int) =
        RawStream(
            serverId = "test", serverName = serverName,
            url = "https://example.com/${serverName.hashCode()}.m3u8",
            isM3u8 = true, qualityHint = qualityHint, audioLabel = audioLabel,
        )

    /** How many times a (regex) resolution token occurs in a label. */
    private fun countToken(label: String, tokenRegex: String) =
        Regex(tokenRegex, RegexOption.IGNORE_CASE).findAll(label).count()
}
