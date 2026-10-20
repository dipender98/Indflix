package Test

import com.indstream.StreamEngine
import com.indstream.StreamEngine.AmNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodePickerTest {


    @Test
    fun movieboxSeasonEnd_rangeParsesToLastNumber() {
        assertEquals(4, StreamEngine.movieboxSeasonEnd("Reacher [Hindi] S1-S4"))
        assertEquals(16, StreamEngine.movieboxSeasonEnd("Naruto: Shippuden [Tamil] S1-S16"))
        assertEquals(2, StreamEngine.movieboxSeasonEnd("The Gentlemen S1-S2"))
        assertEquals(3, StreamEngine.movieboxSeasonEnd("Show S3"))
        assertNull(StreamEngine.movieboxSeasonEnd("The Batman (2024)"))
        assertNull(StreamEngine.movieboxSeasonEnd("Reacher"))
    }


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
        assertEquals("Episode 3", ep13?.title)
        val s2e1 = StreamEngine.pickAllmovielandEpisodeNode(twoLevelTree, 2, 1)
        assertEquals("2-1", s2e1?.id)
    }

    @Test
    fun pick_noMatch_returnsNull_neverFirstEntry() {
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
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Part 3", 3))
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Season 3", 3))
        assertFalse(StreamEngine.allmovielandEpisodeTitleMatches("Episode 13", 3))
    }
}
