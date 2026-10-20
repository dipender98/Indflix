package Test

import com.indstream.StreamEngine
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeParsingTest {

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

    @Test
    fun hiAnime_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "hianime" }
        assertEquals("HiAnime", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(45, s.timeoutSec)
        assertTrue(s.animeOnly)
        assertTrue(s.declaredLanguages.isEmpty(), "Japanese/English only - never declared as an Indian dub")
    }

    @Test
    fun animeOnly_isOptIn() {
        val live = ServerFarm.allServers.filterNot { it.id in setOf("animeworld", "hianime") }
        assertTrue(live.none { it.animeOnly }, "only the anime catalogs may be anime-gated")
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

    private val hiAnimeSearchHtml = """
        <div class="flw-item">
            <div class="film-poster">
                <a href="https://hianime.at/watch/naruto-1335" class="film-poster-ahref" data-id="1335"></a>
            </div>
            <div class="film-detail">
                <h3 class="film-name">
                    <a href="https://hianime.at/naruto-1335" title="Naruto" class="dynamic-name"> Naruto </a>
                </h3>
                <div class="fd-infor"><span class="fdi-item">TV</span></div>
            </div>
        </div>
        <div class="flw-item">
            <div class="film-poster">
                <a href="https://hianime.at/watch/the-last-naruto-the-movie-886" class="film-poster-ahref" data-id="886"></a>
            </div>
            <div class="film-detail">
                <h3 class="film-name">
                    <a href="https://hianime.at/the-last-naruto-the-movie-886" title="The Last: Naruto the Movie" class="dynamic-name"> The Last: Naruto the Movie </a>
                </h3>
                <div class="fd-infor"><span class="fdi-item">MOVIE</span></div>
            </div>
        </div>
    """.trimIndent()

    @Test
    fun hiAnime_parseRows_readsIdFromSlug() {
        val rows = StreamEngine.parseAnimeRows(hiAnimeSearchHtml)
        assertEquals(2, rows.size)
        assertEquals("1335", rows[0].id)
        assertEquals("Naruto", rows[0].title)
        assertEquals("TV", rows[0].kind)
        assertEquals("886", rows[1].id)
        assertEquals("MOVIE", rows[1].kind)
        assertTrue(StreamEngine.parseAnimeRows("<html>empty</html>").isEmpty())
    }

    @Test
    fun hiAnime_pickRow_prefersMediaKind() {
        val rows = StreamEngine.parseAnimeRows(hiAnimeSearchHtml)
        assertEquals("1335", StreamEngine.pickAnimeRow(rows, "Naruto", movie = false)?.id)
        assertEquals("886", StreamEngine.pickAnimeRow(rows, "The Last: Naruto the Movie", movie = true)?.id)
        assertNull(StreamEngine.pickAnimeRow(emptyList(), "Naruto", movie = true))
        assertNull(StreamEngine.pickAnimeRow(rows, "One Piece", movie = false))
    }

    @Test
    fun hiAnime_parseEpisodes_readsNumberAndId() {
        val html = """
            <a title="Episode 1" class="ssl-item ep-item" data-number="1" data-id="22676" href="https://hianime.at/watch/naruto-1335?ep=22676"></a>
            <a title="Episode 2" class="ssl-item ep-item" data-number="2" data-id="22677" href="https://hianime.at/watch/naruto-1335?ep=22677"></a>
        """.trimIndent()
        assertEquals(listOf(1 to "22676", 2 to "22677"), StreamEngine.parseAnimeEpisodes(html))
        assertTrue(StreamEngine.parseAnimeEpisodes("<div>none</div>").isEmpty())
    }

    @Test
    fun hiAnime_parseServers_decodesHash() {
        val html = """
            <div class="ps_-block ps_-block-sub servers-sub">
                <div class="ps__-list">
                    <div class="item server-item" data-type="sub" data-server-name="ZokoAnime" data-hash="aHR0cHM6Ly96b2tvYW5pbWUudmlkZW8vc3RyZWFtL21hbC8yMC8xL3N1Yg==">
                        <a href="javascript:;" class="btn">ZokoAnime</a>
                    </div>
                    <div class="item server-item" data-type="sub" data-server-name="HD-1" data-hash="aHR0cHM6Ly9tZWdhcGxheS5idXp6L3N0cmVhbS9zLTIvMTIzNTIvc3ViP3M9dGNkbg==">
                        <a href="javascript:;" class="btn">HD-1</a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val servers = StreamEngine.parseAnimeServers(html)
        assertEquals(2, servers.size)
        assertEquals("ZokoAnime", servers[0].name)
        assertEquals("sub", servers[0].type)
        assertEquals("https://zokoanime.video/stream/mal/20/1/sub", servers[0].url)
        assertEquals("https://megaplay.buzz/stream/s-2/12352/sub?s=tcdn", servers[1].url)
        assertNull(StreamEngine.decodeAnimeHash("!!!not-base64!!!"))
        assertNull(StreamEngine.decodeAnimeHash("bm90LWEtdXJs"))
    }

    @Test
    fun hiAnime_xorDecode_liveVector() {
        val json = """{"download_url":"/download/mal/20/1/sub","src":"https://hls.dramahot.top/p/x/y/master.m3u8","subtitles":[{"lang":"en"}]}"""
        val blob = StreamEngine.xorEncode(json)
        assertEquals(json, StreamEngine.xorDecode(blob))
        val cfg = StreamEngine.animePlayerConfig(blob)
        assertNotNull(cfg)
        assertEquals("https://hls.dramahot.top/p/x/y/master.m3u8", cfg.optString("src"))
        assertNull(StreamEngine.xorDecode("!!!not-base64!!!"))
        assertNull(StreamEngine.xorDecode(blob, key = ""))
    }

    @Test
    fun hiAnime_playerBlob_extractsWindowP() {
        val page = """<script>window.__P="FFYFBAJDCQIDATtY";</script>"""
        assertEquals("FFYFBAJDCQIDATtY", StreamEngine.animePlayerBlob(page))
        assertNull(StreamEngine.animePlayerBlob("<html>no blob</html>"))
    }
}
