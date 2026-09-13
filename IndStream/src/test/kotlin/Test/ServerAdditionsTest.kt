package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the Sept 2026 farm expansion: VixSrc, ZXCStreams, DahmerMovies, VidAPI, 2Embed. */
class ServerAdditionsTest {

    @Test
    fun vixsrv_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vixsrc" }
        assertEquals("VixSrc", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.isJsonApi)
        assertEquals("https://vixsrc.to/", s.referer)
        assertEquals("https://vixsrc.to/api/movie/27205", ServerFarm.buildMovieUrl(s, "27205"))
        assertEquals("https://vixsrc.to/api/tv/1399/1/1", ServerFarm.buildTvUrl(s, "1399", 1, 1))
    }

    @Test
    fun zxcstreams_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "zxcstreams" }
        assertEquals("ZXCStreams", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(2160, s.maxQuality)
        assertEquals("https://zxcstream.xyz/player/movie/27205", ServerFarm.buildMovieUrl(s, "27205"))
        assertEquals("https://zxcstream.xyz/player/tv/1399/1/1", ServerFarm.buildTvUrl(s, "1399", 1, 1))
    }

    @Test
    fun dahmermovies_presentAndTitled() {
        val s = ServerFarm.allServers.first { it.id == "dahmermovies" }
        assertEquals("DahmerMovies", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(2160, s.maxQuality)
        assertEquals("https://a.111477.xyz/", s.referer)
    }

    @Test
    fun vidapi_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "vidapi" }
        assertEquals("VidAPI", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals("https://vaplayer.ru/embed/movie/27205", ServerFarm.buildMovieUrl(s, "27205"))
        assertEquals("https://vaplayer.ru/embed/tv/1399/1/1", ServerFarm.buildTvUrl(s, "1399", 1, 1))
    }

    @Test
    fun twoembed_presentAndImdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "twoembed" }
        assertEquals("2Embed", s.name)
        assertEquals(ServerIdType.IMDB, s.idType)
        assertEquals("https://2embed.cc/embed/tt1375666", ServerFarm.buildMovieUrl(s, "tt1375666"))
        assertEquals("https://2embed.cc/embedtv/tt0944947&s=1&e=1", ServerFarm.buildTvUrl(s, "tt0944947", 1, 1))
    }

    @Test
    fun vixsrcTokenData_extractsTriple() {
        val html = """{"token": "tok_abc", "expires": "1999999999", "url": "/play/master.m3u8"}"""
        val t = StreamEngine.vixsrcTokenData(html)
        assertNotNull(t)
        assertEquals("tok_abc", t.first)
        assertEquals("1999999999", t.second)
        assertEquals("/play/master.m3u8", t.third)
        assertNull(StreamEngine.vixsrcTokenData("<html>no player config</html>"))
        assertNull(StreamEngine.vixsrcTokenData("""{"token": "a", "expires": "b"}"""))
    }

    @Test
    fun vixsrcTokenFresh_graceWindow() {
        assertTrue(StreamEngine.vixsrcTokenFresh("1999999999", 1_700_000_000_000L))
        assertTrue(!StreamEngine.vixsrcTokenFresh("1000", 1_700_000_000_000L))
        assertTrue(!StreamEngine.vixsrcTokenFresh("not-a-number", 1_700_000_000_000L))
    }

    @Test
    fun zxcSha512Hex_knownVector() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            StreamEngine.zxcSha512Hex("abc"),
        )
    }

    @Test
    fun zxcHeightOf_ladderAndRaw() {
        assertEquals(1080, StreamEngine.zxcHeightOf(3))
        assertEquals(2160, StreamEngine.zxcHeightOf(4))
        assertEquals(360, StreamEngine.zxcHeightOf(0))
        assertEquals(1080, StreamEngine.zxcHeightOf(1080))
        assertEquals(2160, StreamEngine.zxcHeightOf("4K"))
        assertEquals(720, StreamEngine.zxcHeightOf("720p"))
        assertEquals(720, StreamEngine.zxcHeightOf("2"))
        assertEquals(1080, StreamEngine.zxcHeightOf("3"))
        assertEquals(0, StreamEngine.zxcHeightOf(null))
        assertEquals(0, StreamEngine.zxcHeightOf("default"))
    }

    @Test
    fun dahmerParseRows_filtersMediaFiles() {
        val html = "<table><tr><td><a href=\"../\">../</a></td></tr>" +
            "<tr><td><a href=\"Inception.2010.1080p.mkv\">Inception.2010.1080p.mkv</a></td>" +
            "<td>9.2 GB</td></tr>" +
            "<tr><td><a href=\"notes.nfo\">notes.nfo</a></td><td>1 KB</td></tr></table>"
        val rows = StreamEngine.dahmerParseRows(html)
        assertEquals(1, rows.size)
        assertEquals("Inception.2010.1080p.mkv", rows[0].second)
        assertEquals("9.2 GB", rows[0].third)
        assertTrue(StreamEngine.dahmerParseRows("<html>empty</html>").isEmpty())
    }

    @Test
    fun dahmerLanguageOf_releaseTags() {
        assertEquals("Hindi", StreamEngine.dahmerLanguageOf("Inception.2010.1080p.AMZN.WEB-DL.HINDI.DDP2.0.H.265-GTM.mkv"))
        assertEquals("Tamil", StreamEngine.dahmerLanguageOf("Inception.2010.720p.WEB-DL.TAMIL.DDP2.0.H.265-GTM.mkv"))
        assertEquals("Telugu", StreamEngine.dahmerLanguageOf("Inception.2010.720p.WEB-DL.TELUGU.DDP2.0.H.265-GTM.mkv"))
        assertEquals("Multi", StreamEngine.dahmerLanguageOf("Inception.2010.1080p.AMZN.WEB-DL.MULTI.DDP2.0.H.264-GTM.mkv"))
        assertEquals("Multi", StreamEngine.dahmerLanguageOf("Show.S01E02.DUAL-N3G4N.mkv"))
        // No language tag at all -> honest blank, never guessed.
        assertEquals("", StreamEngine.dahmerLanguageOf("Inception.2010.1080p.BluRay.x264.DTS-WiKi.mkv"))
        assertEquals("English", StreamEngine.dahmerLanguageOf("Movie.2024.1080p.WEB-DL.ENGLISH.DDP5.1.H.264.mkv"))
    }

    @Test
    fun dahmerResolutionOf_releaseTags() {
        assertEquals(2160, StreamEngine.dahmerResolutionOf("Inception.2010.2160p.UHD.BluRay.x265-CtrlHD.mkv"))
        assertEquals(2160, StreamEngine.dahmerResolutionOf("Movie.2024.4K.WEB-DL.mkv"))
        assertEquals(1080, StreamEngine.dahmerResolutionOf("Inception.2010.1080p.BluRay.x264.mkv"))
        assertEquals(720, StreamEngine.dahmerResolutionOf("Inception.2010.720p.HDTV.mkv"))
        assertEquals(0, StreamEngine.dahmerResolutionOf("Inception.2010.DVDSCR.mkv"))
    }

    @Test
    fun dahmerEpisodeMatch_forms() {
        assertTrue(StreamEngine.dahmerEpisodeMatch("Show.S01E02.1080p.WEB.mkv", 1, 2))
        assertTrue(StreamEngine.dahmerEpisodeMatch("Show.S1E2.720p.mkv", 1, 2))
        assertTrue(StreamEngine.dahmerEpisodeMatch("Show.E02.HDTV.mkv", 1, 2))
        assertTrue(StreamEngine.dahmerEpisodeMatch("Show.Episode.2.1080p.mkv", 1, 2))
        assertTrue(!StreamEngine.dahmerEpisodeMatch("Show.S01E03.1080p.WEB.mkv", 1, 2))
    }

    @Test
    fun dahmerEncodeUri_marksPassThrough() {
        assertEquals(
            "https://a.111477.xyz/movies/Inception%20(2010)/Inception.2010.1080p.mkv",
            StreamEngine.dahmerEncodeUri("https://a.111477.xyz/movies/Inception (2010)/Inception.2010.1080p.mkv"),
        )
        // Existing escapes survive (JS encodeURI parity); raw % never doubles.
        assertEquals(
            "https://a.111477.xyz/movies/Film%20X%2FY.mkv",
            StreamEngine.dahmerEncodeUri("https://a.111477.xyz/movies/Film%20X%2FY.mkv"),
        )
    }

    @Test
    fun indiaBoostRank_tiers() {
        assertEquals(4, StreamEngine.indiaBoostRank("Hindi"))
        assertEquals(4, StreamEngine.indiaBoostRank("hi"))
        assertEquals(3, StreamEngine.indiaBoostRank("Multi"))
        assertEquals(3, StreamEngine.indiaBoostRank("Hindi+English"))
        assertEquals(3, StreamEngine.indiaBoostRank("Tamil"))
        assertEquals(3, StreamEngine.indiaBoostRank("Telugu"))
        assertEquals(3, StreamEngine.indiaBoostRank("Bengali"))
        assertEquals(0, StreamEngine.indiaBoostRank("English"))
        assertEquals(0, StreamEngine.indiaBoostRank("Original"))
        assertEquals(0, StreamEngine.indiaBoostRank(""))
    }

    @Test
    fun emit_indiaBoost_hindiFirst_thenStable() = runBlocking {
        fun raw(id: String, label: String) = StreamEngine.RawStream(
            serverId = id, serverName = id,
            url = "https://x.example/$id.m3u8", isM3u8 = true, audioLabel = label,
        )
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                raw("eng", "English"),
                raw("unk", ""),
                raw("hin", "Hindi"),
                raw("mul", "Multi"),
                raw("tam", "Tamil"),
            ),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        assertEquals(
            listOf(
                "https://x.example/hin.m3u8",
                "https://x.example/mul.m3u8",
                "https://x.example/tam.m3u8",
                "https://x.example/eng.m3u8",
                "https://x.example/unk.m3u8",
            ),
            out.map { it.url },
        )
    }
}
