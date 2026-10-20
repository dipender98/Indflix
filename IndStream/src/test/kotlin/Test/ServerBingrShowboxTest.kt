package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.Settings
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerBingrShowboxTest {

    @Test
    fun bingr_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "bingr" }
        assertEquals("Bingr", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://bingr.one/", s.referer)
        assertEquals("https://api.bingr.one/api/stream", s.movieUrl)
        assertEquals("https://api.bingr.one/api/stream", s.tvUrl)
    }

    @Test
    fun bingr_servers_includeIndian() {
        assertTrue(StreamEngine.BINGR_SRVS.contains("s62"), "IN-flagged srv must be cascaded")
        assertTrue(StreamEngine.BINGR_SRVS.contains("s40"), "s40 drives the aphelion-tv TV path")
        assertTrue(StreamEngine.BINGR_SRVS.isNotEmpty())
    }

    @Test
    fun bingrBody_movieYears_nestedQuery() {
        val body = StreamEngine.bingrBody("s40", "movie", "27205", "Inception", "2010", 1, 1)
        assertEquals("s40", body["srv"])
        assertEquals("movie", body["t"])
        assertEquals("27205", body["id"])
        @Suppress("UNCHECKED_CAST")
        val query = body["query"] as Map<String, Any?>
        assertEquals("Inception", query["title"])
        assertEquals("2010", query["year"])
        assertTrue(!query.containsKey("season") && !query.containsKey("episode"))
    }

    @Test
    fun bingrBody_tv_putsSeasonEpisodeInsideQuery() {
        val body = StreamEngine.bingrBody("s62", "tv", "1399", "Game of Thrones", "2011", 3, 7)
        assertEquals("tv", body["t"])
        @Suppress("UNCHECKED_CAST")
        val query = body["query"] as Map<String, Any?>
        assertEquals(3, query["season"])
        assertEquals(7, query["episode"])
        assertTrue(!body.containsKey("season") && !body.containsKey("episode"))
    }

    @Test
    fun bingrSources_parsesAndFilters() {
        val json = """
            {"sources":[
                {"url":"https://cdn.example/master.m3u8","quality":"1080p","language":"Hindi"},
                {"url":"not-a-url","quality":"720p"},
                {"url":"https://cdn.example/2160.mp4","label":"4K"}
            ]}
        """.trimIndent()
        val src = StreamEngine.bingrSources(json)
        assertEquals(2, src.size)
        assertEquals("https://cdn.example/master.m3u8", src[0].url)
        assertEquals("Hindi", src[0].language)
        assertEquals("4K", src[1].label)
        assertTrue(StreamEngine.bingrSources("{\"error\":\"x\"}").isEmpty())
        assertTrue(StreamEngine.bingrSources("not json").isEmpty())
    }

    @Test
    fun heightHintOf_labels() {
        assertEquals(1080, StreamEngine.heightHintOf("1080p"))
        assertEquals(2160, StreamEngine.heightHintOf("2160p"))
        assertEquals(2160, StreamEngine.heightHintOf("4K"))
        assertEquals(720, StreamEngine.heightHintOf("720p"))
        assertEquals(0, StreamEngine.heightHintOf("ORG"))
        assertEquals(0, StreamEngine.heightHintOf(""))
    }

    @Test
    fun showbox_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "showbox" }
        assertEquals("ShowBox", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        assertEquals("https://showbox.media/", s.referer)
        assertTrue(s.movieUrl.contains("showbox.media/search"))
    }

    @Test
    fun normalizeShowboxToken_forms() {
        assertNull(StreamEngine.normalizeShowboxToken(null))
        assertNull(StreamEngine.normalizeShowboxToken("   "))
        assertEquals("ui=abc", StreamEngine.normalizeShowboxToken("abc"))
        assertEquals("ui=abc", StreamEngine.normalizeShowboxToken("ui=abc"))
        assertEquals("ui=eyJx", StreamEngine.normalizeShowboxToken("eyJx"))
    }

    @Test
    fun showboxSearchHref_andHeadingId() {
        val search = """<div class="film-name"><a href="/movie/4059" title="Inception">Inception</a></div>"""
        assertEquals("/movie/4059", StreamEngine.showboxSearchHref(search))
        val detail = """<h2 class="heading-name"><a href="https://showbox.media/movie/4059">Inception</a></h2>"""
        assertEquals(4059, StreamEngine.showboxHeadingId(detail))
        assertNull(StreamEngine.showboxHeadingId("<html>none</html>"))
    }

    @Test
    fun showboxShareKey_lastSegment() {
        assertEquals("Bp1Hw1MK", StreamEngine.showboxShareKey("""{"data":{"link":"https://www.febbox.com/share/Bp1Hw1MK"}}"""))
        assertNull(StreamEngine.showboxShareKey("""{"data":{}}"""))
        assertNull(StreamEngine.showboxShareKey("not json"))
    }

    @Test
    fun showboxFiles_parsesAndFilters() {
        val json = """{"data":{"file_list":[
            {"fid":2605016,"file_name":"Inception.mp4","is_dir":0},
            {"fid":2605014,"file_name":"Season 1","is_dir":1},
            {"fid":0,"file_name":"bad","is_dir":0}
        ]}}"""
        val files = StreamEngine.showboxFiles(json)
        assertEquals(2, files.size)
        assertEquals(2605016L, files[0].fid)
        assertTrue(files[1].isDir)
        assertTrue(StreamEngine.showboxFiles("{}").isEmpty())
    }

    @Test
    fun showboxSeasonAndEpisode_pick() {
        val root = listOf(
            StreamEngine.ShowboxFile(1, "Show.S01.1080p", true),
            StreamEngine.ShowboxFile(2, "Show.S02.1080p", true),
        )
        assertEquals(2L, StreamEngine.showboxSeasonFolder(root, 2)?.fid)
        val eps = listOf(
            StreamEngine.ShowboxFile(10, "Show.S02E03.mkv", false),
            StreamEngine.ShowboxFile(11, "Show.S02E04.mkv", false),
        )
        assertEquals(11L, StreamEngine.showboxEpisodeFile(eps, 4)?.fid)
        assertNull(StreamEngine.showboxSeasonFolder(root, 9))
        assertNull(StreamEngine.showboxEpisodeFile(eps, 9))
        val loneDir = listOf(StreamEngine.ShowboxFile(5, "Show.S01", true))
        assertEquals(5L, StreamEngine.showboxSeasonFolder(loneDir, 9)?.fid)
        val loneEp = listOf(StreamEngine.ShowboxFile(12, "Show.S01E01.mkv", false))
        assertEquals(12L, StreamEngine.showboxEpisodeFile(loneEp, 9)?.fid)
    }

    @Test
    fun showboxQualities_parsesDivs() {
        val html = """<div class="file_quality" data-url="https:\/\/cdn.example\/1080.m3u8" data-quality="1080p"></div>
            <div class="file_quality" data-url="https://cdn.example/720.mp4" data-quality="720p"></div>"""
        val q = StreamEngine.showboxQualities(html)
        assertEquals(2, q.size)
        assertEquals("https://cdn.example/1080.m3u8", q[0].first)
        assertEquals(1080, q[0].second)
        assertEquals(720, q[1].second)
        assertTrue(StreamEngine.showboxQualities("<html>none</html>").isEmpty())
    }

    @Test
    fun showboxAndBingr_areNotInDisabledList() {
        val disabled = setOf("primesrc", "vidrock", "videm", "vixsrc")
        for (id in disabled) assertNull(ServerFarm.allServers.firstOrNull { it.id == id })
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "bingr" })
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "showbox" })
    }

    @Test
    fun uiTokenFromCookie_pullsUiValue() {
        assertEquals("abc123", Settings.uiTokenFromCookie("ci=x; ui=abc123; theme=dark"))
        assertEquals("abc123", Settings.uiTokenFromCookie("ui=abc123"))
        assertEquals("eyJx.y.z", Settings.uiTokenFromCookie("UI=eyJx.y.z"))
        assertEquals("tok=late", Settings.uiTokenFromCookie("ui=tok=late"))
        assertNull(Settings.uiTokenFromCookie("ci=x; theme=dark"))
        assertNull(Settings.uiTokenFromCookie("ui="))
        assertNull(Settings.uiTokenFromCookie(null))
        assertNull(Settings.uiTokenFromCookie(""))
    }

    @Test
    fun showboxLogin_targetsHost() {
        assertTrue(Settings.SHOWBOX_LINK.startsWith("https://"))
        assertEquals("https://www.febbox.com", Settings.SHOWBOX_HOST)
    }
}
