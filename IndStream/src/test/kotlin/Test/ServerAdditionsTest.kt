package Test

import com.indstream.CastleTvSource
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the Sept 2026 farm expansion: VixSrc, ZXCStreams, VidAPI, 2Embed, CastleTV, StreamFlix, 4KHDHub. */
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
        assertEquals("https://zxcstream.xyz/player/movie/27205", ServerFarm.buildMovieUrl(s, "27205"))
        assertEquals("https://zxcstream.xyz/player/tv/1399/1/1", ServerFarm.buildTvUrl(s, "1399", 1, 1))
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
    fun fastBatch_presentAndTmdbKeyed() {
        // Fast embed batch: all TMDB-keyed (no id lookup wait), generic pipeline, 15s kill.
        val expected = mapOf(
            "vidfast" to Pair("https://vidfast.pro/movie/27205?autoPlay=true", "https://vidfast.pro/tv/1399/1/1?autoPlay=true"),
            "autoembed" to Pair("https://autoembed.co/movie/tmdb/27205", "https://autoembed.co/tv/tmdb/1399-1-1"),
            "vidphantom" to Pair("https://vidphantom.com/movie/27205", "https://vidphantom.com/tv/1399/1/1"),
            "vsembed" to Pair("https://vsembed.su/embed/movie/27205", "https://vsembed.su/embed/tv/1399/1/1"),
            "twoembed-skin" to Pair("https://www.2embed.skin/embed/27205", "https://www.2embed.skin/embedtv/1399&s=1&e=1"),
            "vidsrc-to" to Pair("https://vidsrc.to/embed/movie/27205", "https://vidsrc.to/embed/tv/1399/1/1"),
            "vidsrcme" to Pair("https://vidsrcme.su/embed/movie/27205", "https://vidsrcme.su/embed/tv/1399/1/1"),
            "nontongo" to Pair("https://www.nontongo.win/embed/movie/27205", "https://www.nontongo.win/embed/tv/1399/1/1"),
        )
        for ((id, urls) in expected) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertNotNull(s, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s.idType, "$id must be TMDB-keyed")
            assertEquals(15, s.timeoutSec, "$id farm kill must stay fast")
            assertEquals(urls.first, ServerFarm.buildMovieUrl(s, "27205"), "$id movie url")
            assertEquals(urls.second, ServerFarm.buildTvUrl(s, "1399", 1, 1), "$id tv url")
        }
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
    fun hindiBatch_presentAndTmdbKeyed() {
        val castle = ServerFarm.allServers.first { it.id == "castletv" }
        assertEquals("CastleTV", castle.name)
        assertEquals(ServerIdType.TMDB, castle.idType)
        assertEquals(30, castle.timeoutSec)
        assertEquals(setOf("Hindi", "Tamil", "Telugu"), castle.declaredLanguages)
        val flix = ServerFarm.allServers.first { it.id == "streamflix" }
        assertEquals("StreamFlix", flix.name)
        assertEquals(ServerIdType.TMDB, flix.idType)
        assertEquals(25, flix.timeoutSec)
        val hub = ServerFarm.allServers.first { it.id == "4khdhub" }
        assertEquals("4KHDHub", hub.name)
        assertEquals(ServerIdType.TMDB, hub.idType)
        assertEquals(60, hub.timeoutSec)
        assertEquals(setOf("Hindi"), hub.declaredLanguages)
    }

    @Test
    fun castleDecrypt_liveVector() {
        // Captured against the live API: secKey + cipher must round-trip.
        assertEquals(
            "hello-castle-1080p",
            CastleTvSource.decrypt("XP4abirPGczy5P0SITVv+4mF5GM4XmyP1UXyVmi8ouA=", "ZkpBVG0qa2dmSg=="),
        )
        assertNull(CastleTvSource.decrypt("!!!not-base64!!!", "ZkpBVG0qa2dmSg=="))
    }

    @Test
    fun castlePickTrack_hindiFirst() {
        fun t(id: String, name: String, v: Boolean) = CastleTvSource.Track(id, name, v)
        val tracks = listOf(t("1", "English", true), t("2", "Hindi", true), t("3", "Tamil", false))
        assertEquals("2", CastleTvSource.pickTrack(tracks)?.languageId)
        assertEquals("1", CastleTvSource.pickTrack(listOf(t("1", "English", true)))?.languageId)
        assertNull(CastleTvSource.pickTrack(emptyList()))
    }

    @Test
    fun castleQualityOf_labels() {
        assertEquals("1080p", CastleTvSource.qualityOf("FHD 1080p", 3))
        assertEquals("4K", CastleTvSource.qualityOf("UHD", 4))
        assertEquals("720p", CastleTvSource.qualityOf(null, 2))
        assertEquals("", CastleTvSource.qualityOf(null, 0))
    }

    @Test
    fun releaseLanguageOf_tags() {
        assertEquals("Hindi", StreamEngine.releaseLanguageOf("Jawan 2023 Hindi WEB-DL"))
        assertEquals("Tamil", StreamEngine.releaseLanguageOf("Film TAMIL HDRip"))
        assertEquals("Telugu", StreamEngine.releaseLanguageOf("Film TELUGU"))
        assertEquals("Hindi", StreamEngine.releaseLanguageOf("Film Dual Audio Hindi-English"))
        assertEquals("Multi", StreamEngine.releaseLanguageOf("Show S01 DUAL"))
        assertEquals("", StreamEngine.releaseLanguageOf("Inception 2010 BluRay"))
    }

    @Test
    fun releaseHeightOf_ladder() {
        assertEquals(2160, StreamEngine.releaseHeightOf("Film 2160p"))
        assertEquals(2160, StreamEngine.releaseHeightOf("Film 4K"))
        assertEquals(1080, StreamEngine.releaseHeightOf("1080p"))
        assertEquals(720, StreamEngine.releaseHeightOf("720p"))
        assertEquals(0, StreamEngine.releaseHeightOf("CAMRip"))
    }

    @Test
    fun hubParseCards_cards() {
        val html = "<div class=\"card-grid\">" +
            "<a href=\"/inception-movie-509/\" class=\"movie-card\" aria-label=\"Inception details\">" +
            "<a href=\"https://other.example/x\" class=\"movie-card\" aria-label=\"Other details\">"
        val cards = StreamEngine.hubParseCards(html, "https://4khdhub.one")
        assertEquals(2, cards.size)
        assertEquals("https://4khdhub.one/inception-movie-509/", cards[0].first)
        assertEquals("Inception", cards[0].second)
        assertTrue(StreamEngine.hubParseCards("<html>empty</html>", "https://4khdhub.one").isEmpty())
    }

    @Test
    fun hubPostYearOk_gate() {
        val html = "<html><head><meta property=\"og:title\" content=\"Jawan 2023 Hindi\" /></head></html>"
        assertTrue(StreamEngine.hubPostYearOk(html, "2023"))
        assertTrue(!StreamEngine.hubPostYearOk(html, "2024"))
        assertTrue(StreamEngine.hubPostYearOk(html, null))
    }

    @Test
    fun hubDriveLinks_extracts() {
        val html = "<a href=\"https://hubcloud.ist/drive/abc123\">HubCloud</a>" +
            "<a href=\"https://hubcloud.ist/drive/abc123\">dup</a>" +
            "<a href=\"https://example.com/x\">other</a>"
        assertEquals(listOf("https://hubcloud.ist/drive/abc123"), StreamEngine.hubDriveLinks(html))
    }

    @Test
    fun hubFileButtons_pixeldrainConvert() {
        val html = "<div class=\"card-header\">Jawan 2023 720p</div><div class=\"card-body\">" +
            "<h2><a class=\"btn\" href=\"https://pixeldrain.dev/u/AbC123\">Download File</a></h2></div>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(1, out.size)
        assertEquals("https://pixeldrain.dev/api/file/AbC123", out[0].first)
        assertEquals("720p", out[0].second)
    }

    @Test
    fun speedRankMs_unmeasuredLast_hindiDiscount() {
        // Unmeasured sorts after everything measured.
        assertEquals(Double.MAX_VALUE, StreamEngine.speedRankMs(null, "Hindi"))
        assertTrue(StreamEngine.speedRankMs(1000L, "English") < Double.MAX_VALUE)
        // Same latency: Hindi wins outright.
        assertTrue(StreamEngine.speedRankMs(1000L, "Hindi") < StreamEngine.speedRankMs(1000L, "English"))
        // Small bonus only: a 2x-faster English stream still beats Hindi.
        assertTrue(StreamEngine.speedRankMs(500L, "English") < StreamEngine.speedRankMs(1000L, "Hindi"))
        // Near-tie flips to Hindi: 1000/1.1 = 909 < 950.
        assertTrue(StreamEngine.speedRankMs(1000L, "Hindi") < StreamEngine.speedRankMs(950L, "English"))
        // Multi + Indian dubs get the smaller bonus.
        assertTrue(StreamEngine.speedRankMs(1000L, "Multi") < StreamEngine.speedRankMs(1000L, "English"))
        assertTrue(StreamEngine.speedRankMs(1000L, "Tamil") < StreamEngine.speedRankMs(1000L, "English"))
        // Zero-latency inline manifests top everything.
        assertEquals(0.0, StreamEngine.speedRankMs(0L, "Unknown"))
    }

    @Test
    fun emit_unmeasuredKeepsArrivalRegardlessOfLanguage() = runBlocking {
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
        // Nothing measured: stable arrival order wins over language.
        assertEquals(
            listOf(
                "https://x.example/eng.m3u8",
                "https://x.example/unk.m3u8",
                "https://x.example/hin.m3u8",
                "https://x.example/mul.m3u8",
                "https://x.example/tam.m3u8",
            ),
            out.map { it.url },
        )
    }
}
