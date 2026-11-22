package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeWorldTest {

    @Test
    fun animeWorld_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "animeworld" }
        assertEquals("AnimeWorld", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(45, s.timeoutSec)
        assertTrue(s.animeOnly, "anime catalog must not run for live-action titles")
        assertEquals(
            setOf("Hindi", "Tamil", "Telugu", "Malayalam", "Bengali"),
            s.declaredLanguages,
        )
    }

    private val searchHtml = """
        <li id="post-314" class="post-314 series">
            <article class="post dfx fcl movies">
                <header class="entry-header">
                    <h2 class="entry-title" style="display: none;">Jujutsu Kaisen</h2>
                </header>
                <div class="post-thumbnail or-1"><img src="//image.tmdb.org/x.jpg" alt="Image"></div>
                <a href="https://watchanimeworld.one/series/jujutsu-kaisen/" class="lnk-blk"></a>
            </article>
        </li>
        <li id="post-313" class="post-313 movies">
            <article class="post dfx fcl movies">
                <header class="entry-header">
                    <h2 class="entry-title">Jujutsu Kaisen 0</h2>
                </header>
                <a href="https://watchanimeworld.one/movies/jujutsu-kaisen-0/" class="lnk-blk"></a>
            </article>
        </li>
        <li id="post-999" class="post-999 page">
            <article class="post dfx fcl page">
                <h2 class="entry-title">Contact Us</h2>
                <a href="https://watchanimeworld.one/contact/" class="lnk-blk"></a>
            </article>
        </li>
    """.trimIndent()

    @Test
    fun animeWorld_parseCards_keepsSeriesAndMoviesOnly() {
        val cards = StreamEngine.parseAnimeCards(searchHtml)
        assertEquals(2, cards.size)
        assertEquals("https://watchanimeworld.one/series/jujutsu-kaisen/", cards[0].url)
        assertEquals("Jujutsu Kaisen", cards[0].title)
        assertFalse(cards[0].movie)
        assertTrue(cards[1].movie)
        assertTrue(StreamEngine.parseAnimeCards("<html>empty</html>").isEmpty())
    }

    @Test
    fun animeWorld_pickCard_respectsMediaTypeAndYear() {
        val cards = StreamEngine.parseAnimeCards(searchHtml)
        assertEquals(
            "https://watchanimeworld.one/movies/jujutsu-kaisen-0/",
            StreamEngine.pickAnimeCard(cards, "Jujutsu Kaisen 0", 2021, movie = true)?.url,
        )
        assertEquals(
            "https://watchanimeworld.one/series/jujutsu-kaisen/",
            StreamEngine.pickAnimeCard(cards, "Jujutsu Kaisen", null, movie = false)?.url,
        )
        assertNull(StreamEngine.pickAnimeCard(cards, "Jujutsu Kaisen 0", null, movie = false))
        assertNull(StreamEngine.pickAnimeCard(emptyList(), "Jujutsu Kaisen", null, movie = true))
    }

    @Test
    fun animeWorld_titleCovers_needsEveryQueryWord() {
        assertTrue(StreamEngine.animeTitleCovers("Jujutsu Kaisen", "Jujutsu Kaisen (Hindi Dubbed)"))
        assertTrue(StreamEngine.animeTitleCovers("Naruto: Shippuden", "Naruto Shippuden"))
        assertFalse(StreamEngine.animeTitleCovers("Jujutsu Kaisen 0", "Jujutsu Kaisen"))
        assertFalse(StreamEngine.animeTitleCovers("Naruto", "Bleach"))
        assertFalse(StreamEngine.animeTitleCovers("", "Naruto"))
    }

    @Test
    fun animeWorld_pageIdsAndSeasonTabs() {
        val series = """
            <div class="aa-drp choose-season">
                <a data-post="352" data-season="1" href="javascript:void(0)"></a>
                <a data-post="352" data-season="2" href="javascript:void(0)"></a>
                <a data-post="352" data-season="16" href="javascript:void(0)"></a>
            </div>
        """.trimIndent()
        assertEquals("352", StreamEngine.animePostId(series))
        assertEquals(listOf(1, 2, 16), StreamEngine.animeSeasonNumbers(series))
        assertEquals(
            "314",
            StreamEngine.animePostId("""<li id="post-314" class="post-314 series">"""),
        )
        assertNull(StreamEngine.animePostId("<html>no ids</html>"))
    }

    private val bucket = """
        <a href="https://watchanimeworld.one/episode/one-piece-1x1/">1</a>
        <a href="https://watchanimeworld.one/episode/one-piece-1x2/">2</a>
        <a href="https://watchanimeworld.one/episode/one-piece-1x3/">3</a>
    """.trimIndent()

    @Test
    fun animeWorld_episodeLinkParsing() {
        val links = StreamEngine.animeEpisodeLinks(bucket)
        assertEquals(3, links.size)
        assertEquals(1, StreamEngine.animeEpisodeSeason(links[0]))
        assertEquals(2, StreamEngine.animeEpisodeNumber(links[1]))
        assertEquals(links[1], StreamEngine.pickAnimeEpisodeByNumber(links, 2))
        assertNull(StreamEngine.pickAnimeEpisodeByNumber(links, 9))
        assertNull(StreamEngine.animeEpisodeNumber("https://x/episode/no-number/"))
    }

    @Test
    fun animeWorld_pickByPosition_isRelativeToBucket() {
        val html = (25..36).joinToString("") {
            """<a href="https://watchanimeworld.one/episode/show-3x$it/">$it</a>"""
        }
        val links = StreamEngine.animeEpisodeLinks(html)
        assertEquals(
            "https://watchanimeworld.one/episode/show-3x29/",
            StreamEngine.pickAnimeEpisodeByPosition(links, 5),
        )
        assertNull(StreamEngine.pickAnimeEpisodeByPosition(links, 0))
        assertNull(StreamEngine.pickAnimeEpisodeByPosition(links, 99))
    }

    @Test
    fun animeWorld_bucketGuess_ladder() {
        assertEquals(16, StreamEngine.animeBucketGuess(360, 16, 360))
        assertEquals(1, StreamEngine.animeBucketGuess(1, 16, 360))
        assertEquals(1, StreamEngine.animeBucketGuess(360, 1, 360))
        assertTrue(StreamEngine.animeBucketGuess(180, 16, 360) in 8..9)
    }

    private val shippudenSeries = buildString {
        append("""<div class="aa-drp choose-season">""")
        for (s in 1..16) append("""<a data-post="352" data-season="$s" href="javascript:void(0)"></a>""")
        append("</div>")
        for (e in 349..360) append("""<a href="https://watchanimeworld.one/episode/naruto-shippuden-16x$e/">$e</a>""")
    }

    @Test
    fun animeWorld_findEpisode_absoluteNumberInRenderedBucket() = runBlocking {
        var fetches = 0
        val hit = StreamEngine.findAnimeEpisode(shippudenSeries, season = 1, episode = 360) {
            fetches++
            null
        }
        assertEquals("https://watchanimeworld.one/episode/naruto-shippuden-16x360/", hit)
        assertEquals(0, fetches, "the rendered bucket answers without any AJAX call")
    }

    @Test
    fun animeWorld_findEpisode_absoluteNumberNeedsOtherBucket() = runBlocking {
        val asked = mutableListOf<Int>()
        val hit = StreamEngine.findAnimeEpisode(shippudenSeries, season = 1, episode = 100) { s ->
            asked += s
            (89..112).joinToString("") {
                """<a href="https://watchanimeworld.one/episode/naruto-shippuden-5x$it/">$it</a>"""
            }
        }
        assertEquals("https://watchanimeworld.one/episode/naruto-shippuden-5x100/", hit)
        assertEquals(listOf(5), asked)
    }

    @Test
    fun animeWorld_findEpisode_splitSeasonUsesSlotPosition() = runBlocking {
        val series = buildString {
            append("""<div class="aa-drp choose-season">""")
            for (s in 1..4) append("""<a data-post="77" data-season="$s" href="javascript:void(0)"></a>""")
            append("</div>")
            for (e in 25..36) append("""<a href="https://watchanimeworld.one/episode/aot-3x$e/">$e</a>""")
        }
        val hit = StreamEngine.findAnimeEpisode(series, season = 3, episode = 5) { null }
        assertEquals("https://watchanimeworld.one/episode/aot-3x29/", hit)
    }

    @Test
    fun animeWorld_findEpisode_bailsOutOnMissWithinBudget() = runBlocking {
        var fetches = 0
        val hit = StreamEngine.findAnimeEpisode(shippudenSeries, season = 1, episode = 9999) {
            fetches++
            """<a href="https://watchanimeworld.one/episode/naruto-shippuden-1x1/">1</a>"""
        }
        assertNull(hit)
        assertTrue(fetches <= 4, "season AJAX probes must stay capped (was $fetches)")
    }

    @Test
    fun animeWorld_playerId_andUnescape() {
        val page = """<iframe src="https://play.zephyrix.org/video/68dad4509908e9a208274a1ca8135221"></iframe>"""
        assertEquals("68dad4509908e9a208274a1ca8135221", StreamEngine.animePlayerId(page))
        assertNull(StreamEngine.animePlayerId("<iframe src=\"/dub-player/t/1/\"></iframe>"))
        assertEquals("Kimi no Na wa", StreamEngine.animeUnescape("<b>Kimi no Na wa</b>"))
        assertEquals("A & B's Show", StreamEngine.animeUnescape("A &amp; B&#039;s Show"))
    }
}
