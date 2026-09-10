package Test

import com.indstream.StreamEngine
import com.indstream.StreamEngine.AmNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILE: EpisodePickerTest.kt — tests for the Sept 2026 "wrong episode /
 * vanished server" audit fixes (DEBUGGING_PLAN.md RC-A/B/C/D/E, F1–F5):
 *
 *  - MovieBox season-coverage suffix must parse "S1-S4" → 4 (was 14 — the
 *    digit-filter collapse made the skip-guard never fire and the resolver
 *    requested out-of-range episodes).
 *  - VidNest 502 error pages (JSON or HTML) must classify as host-DOWN, not
 *    "answered" — and the episode-collapse guard keeps the pure classifier
 *    honest.
 *  - Allmovieland card finder: CURRENT live markup (new-short__title--link +
 *    h3 with the extra "hover-op" class) AND the legacy shape; slug guard
 *    rejects wrong-title fallback hits.
 *  - Allmovieland series picker: STRICT (season, episode) match — never the
 *    old silent `?: first()` fallback that played a different episode.
 *
 * All pure (no network, no org.json) per the repo's unit-test rule.
 */
class EpisodePickerTest {

    // ── MovieBox season coverage (F4 / RC-E) ───────────────────────────────

    @Test
    fun movieboxSeasonEnd_rangeParsesToLastNumber() {
        assertEquals(4, StreamEngine.movieboxSeasonEnd("Reacher [Hindi] S1-S4"))
        assertEquals(16, StreamEngine.movieboxSeasonEnd("Naruto: Shippuden [Tamil] S1-S16"))
        assertEquals(2, StreamEngine.movieboxSeasonEnd("The Gentlemen S1-S2"))
        assertEquals(3, StreamEngine.movieboxSeasonEnd("Show S3"))
        assertNull(StreamEngine.movieboxSeasonEnd("The Batman (2024)"))
        assertNull(StreamEngine.movieboxSeasonEnd("Reacher"))
    }

    // ── VidNest error-page classifier (F5 / RC-D) ──────────────────────────

    @Test
    fun vidnestErrorPage_cloudflare502JsonCountsAsDown() {
        assertTrue(StreamEngine.vidnestIsErrorPage(
            """{"title":"Error 502: Bad gateway","error_name":"origin_bad_gateway"}""",
        ))
        assertTrue(StreamEngine.vidnestIsErrorPage("<html><head><title>502 Bad Gateway</title>"))
        assertTrue(StreamEngine.vidnestIsErrorPage("""{"detail":"error 502"}"""))
        assertFalse(StreamEngine.vidnestIsErrorPage("""{"encrypted":true,"data":"RB0fpH8"}"""))
        assertFalse(StreamEngine.vidnestIsErrorPage("""{"url":[{"lang":"Hindi","link":"https://x/a.mp4"}]}"""))
        assertFalse(StreamEngine.vidnestIsErrorPage("""{"streams":[{"url":"https://x/a.m3u8"}]}"""))
        assertFalse(StreamEngine.vidnestIsErrorPage(""))
    }

    // ── Allmovieland card finder (F1/F2 / RC-A/RC-C) ───────────────────────

    private val liveCardHtml = """
        <article class="short-mid new-short">
            <a class="new-short__title--link" href="https://allmovieland.art/10556-reacher.html">
                <h3 class="new-short__title hover-op">Reacher</h3>
            </a>
            <div class='tipbubble new-short__info'>
                <div class="info__head"><strong class="info__title">Reacher</strong></div>
            </div>
        </article>
    """.trimIndent()

    private val legacyCardHtml = """
        <a href="https://allmovieland.one/10556-reacher.html">
            <h3 class="new-short__title">Reacher</h3>
        </a>
    """.trimIndent()

    @Test
    fun allmovielandCardUrl_matchesCurrentLiveMarkup() {
        // THE regression from the 2026-09-10 audit: the strict
        // `new-short__title"` closing-quote requirement matched NOTHING on
        // the current site (class gained "hover-op").
        assertEquals(
            "https://allmovieland.art/10556-reacher.html",
            StreamEngine.allmovielandCardUrl(liveCardHtml),
        )
    }

    @Test
    fun allmovielandCardUrl_stillMatchesLegacyShape() {
        assertEquals(
            "https://allmovieland.one/10556-reacher.html",
            StreamEngine.allmovielandCardUrl(legacyCardHtml),
        )
    }

    @Test
    fun allmovielandCardUrl_slugGuardRejectsWrongShows() {
        val mixed = """
            <a class="new-short__title--link" href="https://allmovieland.art/413-manjhi-the-mountain-man-2015-hindi.html"><h3 class="new-short__title hover-op">Manjhi</h3></a>
            <a class="new-short__title--link" href="https://allmovieland.art/10556-reacher.html"><h3 class="new-short__title hover-op">Reacher</h3></a>
        """.trimIndent()
        // Title-fallback search results must be slug-verified (wrong-show defense).
        assertEquals(
            "https://allmovieland.art/10556-reacher.html",
            StreamEngine.allmovielandCardUrl(mixed, titleNorm = "reacher"),
        )
        assertEquals(
            "https://allmovieland.art/413-manjhi-the-mountain-man-2015-hindi.html",
            StreamEngine.allmovielandCardUrl(mixed, titleNorm = "manjhi the mountain man"),
        )
        assertNull(StreamEngine.allmovielandCardUrl(mixed, titleNorm = "stranger things"))
        assertNull(StreamEngine.allmovielandCardUrl("<html><body>no cards</body></html>"))
    }

    // ── Allmovieland strict series tree picker (F3 / RC-B) ─────────────────

    private fun leaf(lang: String) = AmNode(lang, "", "", "hash-$lang.txt")

    private val twoLevelTree = listOf(
        AmNode("Season 1", id = "1", episode = "", file = "", children = listOf(
            AmNode("Episode 1", id = "1-1", episode = "1", file = "", children = listOf(leaf("Hindi"), leaf("Tamil"))),
            AmNode("Episode 2", id = "1-2", episode = "2", file = "", children = listOf(leaf("Hindi"))),
            AmNode("Episode 3", id = "", episode = "", file = "", children = listOf(leaf("Hindi"), leaf("English"))),
        )),
        AmNode("Season 2", id = "2", episode = "", file = "", children = listOf(
            AmNode("Episode 1", id = "2-1", episode = "1", file = "", children = listOf(leaf("Hindi"))),
        )),
    )

    private val flatTree = listOf(
        AmNode("Episode 1", id = "", episode = "", file = "", children = listOf(leaf("Hindi"))),
        AmNode("E2", id = "", episode = "", file = "", children = listOf(leaf("Hindi"))),
    )

    @Test
    fun pick_twoLevelTree_exactMatches() {
        val ep11 = StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 1, 1)
        assertEquals("1-1", ep11?.id)
        val ep13 = StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 1, 3)
        assertEquals("Episode 3", ep13?.title) // title-form only, no episode field
        val s2e1 = StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 2, 1)
        assertEquals("2-1", s2e1?.id)
    }

    @Test
    fun pick_noMatch_returnsNull_neverFirstEntry() {
        // THE wrong-episode regression: missing episode must yield NO streams,
        // not season-1/episode-1 (the old `?: first()` fallbacks).
        assertNull(StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 1, 9))
        assertNull(StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 3, 1))
    }

    @Test
    fun pick_flatSingleSeasonTree() {
        val e1 = StreamEngine.pickAllmovielandEpisodeNode(flatTree, 1, 1)
        assertEquals("Episode 1", e1?.title)
        val e2 = StreamEngine.pickAllmovielandEpisodeNode(flatTree, 1, 2)
        assertEquals("E2", e2?.title)
        assertNull(StreamEngine.pickAllmovielandEpisodeNode(flatTree, 2, 1))
    }

    @Test
    fun seasonNode_neverConfusedWithEpisodeRows() {
        // A FLAT episode row's children are language LEAVES (file set) — it
        // must not be treated as a season container (which guards the strict
        // picker from matching "Episode <n>" against a SEASON number).
        val epRow = flatTree.first()
        assertFalse(StreamEngine.amNodeIsSeason(epRow, 1), "'Episode 1' row with leaf children is not a season")
        assertTrue(StreamEngine.amNodeIsSeason(twoLevelTree.first(), 1))
    }

    @Test
    fun episodeTitleForms_acceptedAndBareNumbersRejected() {
        assertTrue(StreamEngine.allmovielandEpisodeTitleMatches("E3", 3))
        assertTrue(StreamEngine.allmovielandEpisodeTitleMatches("Ep 03", 3))
        assertTrue(StreamEngine.allmovielandEpisodeTitleMatches("Episode 3", 3))
        assertTrue(StreamEngine.allmovielandEpisodeTitleMatches("S01E03", 3))
        assertTrue(StreamEngine.allmovielandEpisodeTitleMatches("1x03", 3))
        // Bare numbers are NOT episode labels (the false-match class):
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Part 3", 3))
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Season 3", 3))
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Episode 13", 3))
    }
}
