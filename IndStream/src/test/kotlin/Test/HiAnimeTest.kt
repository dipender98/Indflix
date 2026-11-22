package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiAnimeTest {

    @Test
    fun hiAnime_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "hianime" }
        assertEquals("HiAnime", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(45, s.timeoutSec)
        assertTrue(s.animeOnly)
        assertTrue(s.declaredLanguages.isEmpty(), "Japanese/English only - never declared as an Indian dub")
    }

    private val searchHtml = """
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
        val rows = StreamEngine.parseAnimeRows(searchHtml)
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
        val rows = StreamEngine.parseAnimeRows(searchHtml)
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
