package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAdditionsTest {

    @Test
    fun vixsrc_disabledCloudflareChallenge() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vixsrc" })
    }

    @Test
    fun deadServers_stayOut() {
        for (id in setOf("nhd", "vidapi", "zxcstreams", "vidsrc-to", "nontongo")) {
            assertNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must stay out")
        }
    }

    @Test
    fun videasy_removed_v63() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "videasy" })
    }

    @Test
    fun onetouchtv_presentAndTmdbKeyed() {
        val s = ServerFarm.allServers.first { it.id == "onetouchtv" }
        assertEquals("OneTouchTV", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertEquals(30, s.timeoutSec)
        assertTrue(s.hasSubtitles)
    }

    @Test
    fun embedBatch_removed_v63() {
        for (id in setOf(
            "twoembed", "vidfast", "autoembed", "vidphantom", "vsembed",
            "twoembed-skin", "vidsrcme", "vidsrc-pm", "rive", "vidsrcto",
        )) {
            assertNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must stay out")
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
    fun onetouchtv_pureHelpers() {
        assertEquals(1080, StreamEngine.releaseHeightOf("1080p"))
        assertEquals(720, StreamEngine.releaseHeightOf("720p"))
        assertEquals(2160, StreamEngine.releaseHeightOf("4K"))
        assertEquals(0, StreamEngine.releaseHeightOf("Auto"))
        assertEquals(0, StreamEngine.releaseHeightOf(null))
        val hits = listOf(
            StreamEngine.VodHit("a", "Dune Part Two (2024)", "2024", "movie"),
            StreamEngine.VodHit("b", "Dune (2021)", "2021", "movie"),
        )
        assertEquals("a", StreamEngine.matchVodTitle(hits, "Dune Part Two", 2024, "movie")?.id)
        assertEquals(null, StreamEngine.matchVodTitle(hits, "Dune Part Two", 2021, "movie")?.id)
        assertEquals(null, StreamEngine.matchVodTitle(emptyList(), "Dune", 2024, "movie"))
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
    }

    @Test
    fun dualIdServers_declareSupport() {
        val vidlink = ServerFarm.allServers.first { it.id == "vidlink" }
        assertTrue(vidlink.supportsImdb("movie"))
        assertFalse(vidlink.supportsImdb("tv"))

        val braflix = ServerFarm.allServers.first { it.id == "braflix" }
        assertTrue(braflix.supportsImdb("movie"))
        assertTrue(braflix.supportsImdb("tv"))

        val streamflix = ServerFarm.allServers.first { it.id == "streamflix" }
        assertTrue(streamflix.supportsImdb("movie"))
        assertTrue(streamflix.supportsImdb("tv"))

        val netmirror = ServerFarm.allServers.first { it.id == "netmirror" }
        assertFalse(netmirror.supportsImdb("movie"))
        assertFalse(netmirror.supportsImdb("tv"))
    }

    @Test
    fun hubDisabledJsGateway() {
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "4khdhub" })
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "fsl" })
    }

    @Test
    fun vidrock_removed_v63() {
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock" })
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock-net" })
    }

    @Test
    fun castleDecrypt_liveVector() {
        assertEquals(
            "hello-castle-1080p",
            StreamEngine.decryptApiPayload("XP4abirPGczy5P0SITVv+4mF5GM4XmyP1UXyVmi8ouA=", "ZkpBVG0qa2dmSg=="),
        )
        assertNull(StreamEngine.decryptApiPayload("!!!not-base64!!!", "ZkpBVG0qa2dmSg=="))
    }

    @Test
    fun castlePickTrack_hindiFirst() {
        fun t(id: String, name: String, v: Boolean) = StreamEngine.AudioTrack(id, name, v)
        val tracks = listOf(t("1", "English", true), t("2", "Hindi", true), t("3", "Tamil", false))
        assertEquals("2", StreamEngine.pickAudioTrack(tracks)?.languageId)
        assertEquals("1", StreamEngine.pickAudioTrack(listOf(t("1", "English", true)))?.languageId)
        assertNull(StreamEngine.pickAudioTrack(emptyList()))
    }

    @Test
    fun castleQualityOf_labels() {
        assertEquals("1080p", StreamEngine.apiQualityLabel("FHD 1080p", 3))
        assertEquals("4K", StreamEngine.apiQualityLabel("UHD", 4))
        assertEquals("720p", StreamEngine.apiQualityLabel(null, 2))
        assertEquals("", StreamEngine.apiQualityLabel(null, 0))
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
    fun hub4kItems_parsesEpisodeBlocks() {
        val html = "<div class=\"episode-download-item\">" +
            "<div class=\"episode-file-title\">Show.S04E01.2160p.mkv</div>" +
            "<div class=\"episode-file-info\"><span class=\"badge-psa\">Episode-01</span>" +
            "<span class=\"badge-size\">6.26 GB</span></div>" +
            "<div class=\"episode-links\">" +
            "<a href=\"https://greenmotors.example/?id=drive\">Download HubDrive</a>" +
            "<a href=\"https://greenmotors.example/?id=cloud\">Download HubCloud</a></div></div>"
        val items = StreamEngine.hub4kItems(html)
        assertEquals(1, items.size)
        assertEquals("Show.S04E01.2160p.mkv", items[0].title)
        assertEquals("6.26GB", items[0].size)
        assertEquals(
            listOf("https://greenmotors.example/?id=cloud", "https://greenmotors.example/?id=drive"),
            items[0].gatewayUrls,
        )
    }

    @Test
    fun episodeOrSeason_fallsBackToPack() {
        assertTrue(StreamEngine.episodeMatches("Show.S04E02.2160p", 4, 2))
        assertTrue(!StreamEngine.episodeMatches("Show.S04E01.2160p", 4, 2))
        assertTrue(StreamEngine.seasonPackMatches("S04", 4))
        assertTrue(StreamEngine.seasonPackMatches("Season 1 Complete [260MB/E]", 1))
        assertEquals(
            listOf("Show S01E02"),
            StreamEngine.episodeOrSeason(
                listOf("Show S01E01", "Show S01E02", "Season 1 Complete"), { it }, "tv", 1, 2,
            ),
        )
        assertEquals(
            listOf("Season 1 Complete"),
            StreamEngine.episodeOrSeason(
                listOf("Show S01E01", "Season 1 Complete"), { it }, "tv", 1, 9,
            ),
        )
    }

    @Test
    fun hub4kItems_parsesPostBlocks() {
        val html = "<div class=\"download-item\"><div class=\"download-header\">" +
            "<div class=\"flex-1\">Show S01E02 (2024) 1080p BluRay</div>" +
            "<code><span class=\"badge\">1.4GB</span><span class=\"badge\">Hindi, English</span></code></div>" +
            "<div class=\"file-title\"><a class=\"btn\" href=\"https://greenmotors.example/?id=abc\" />" +
            "<a class=\"btn\" href=\"/relative\">rel</a></div></div>"
        val items = StreamEngine.hub4kItems(html)
        assertEquals(1, items.size)
        assertEquals("Show S01E02 (2024) 1080p BluRay", items[0].title)
        assertEquals("1.4GB", items[0].size)
        assertEquals("Hindi, English", items[0].languages)
        assertEquals(listOf("https://greenmotors.example/?id=abc"), items[0].gatewayUrls)
        assertTrue(StreamEngine.hub4kItems("<html>none</html>").isEmpty())
    }

    @Test
    fun normalizeSizeToken_compact() {
        assertEquals("73.73GB", StreamEngine.normalizeSizeToken("73.73 GB"))
        assertEquals("1.7MB", StreamEngine.normalizeSizeToken("size: 1.7 mb (size)"))
        assertEquals("", StreamEngine.normalizeSizeToken("BluRay"))
    }

    @Test
    fun hubLangOf_dualAudio() {
        assertEquals("Multi", StreamEngine.hubLangOf("Hindi, English"))
        assertEquals("Hindi", StreamEngine.hubLangOf("Hindi"))
        assertEquals("", StreamEngine.hubLangOf("BluRay exclusive"))
    }

    @Test
    fun rotatorTarget_liveVector() {
        val blob = "Y214WE0xWjNZbXRhVUdwMmIxQldObFo2ZFRCeFZVOXRRbmxxYVV0UU9XRndla2w1YjNveGFYRlVPV3h3YkRWM1RERnFhVzVVT1dkTlNtdDFiM3BGZVhCNWFtbFdkbXAyYjJ4V05sWjZVMVpJZDA5M1JsSXdNa2RWZURWdk1rVkxSbnBqZGtWdGVHdEtlRm94Y0ZSYWJVaExUVzVHVW1OcVRWUXhTWEY1ZUd4S1lXTk5XbmxqWkV4M1YwWmFTV05rUjNveGRVbFVhekpXWVQ5QQ=="
        val page = "<script>s('o','$blob?@',180*1000)</script>"
        assertEquals("https://hubcloud.ist/drive/cfscfcoduf3fi9o", StreamEngine.rotatorTarget(page))
        assertNull(StreamEngine.rotatorTarget("<html>no blob</html>"))
    }

    @Test
    fun displayName_sizeLast() {
        assertEquals(
            "4KHDHub (\uD83C\uDDEE\uD83C\uDDF3 Hindi) 1.4GB",
            com.indstream.LinkNaming.displayName("4KHDHub", "Hindi", sizeLabel = "1.4GB"),
        )
        assertEquals(
            "VidLink (Multi)",
            com.indstream.LinkNaming.displayName("VidLink", "Multi"),
        )
    }

    @Test
    fun fslSearchHits_parsesTypoProxyJson() {
        val json = """{"hits":[{"document":{"permalink":"https://site.example/p/m/","post_title":"Film (2024)"}}]}"""
        val hits = StreamEngine.fslSearchHits(json)
        assertEquals(1, hits.size)
        assertEquals("https://site.example/p/m/", hits[0].first)
        assertEquals("Film (2024)", hits[0].second)
        assertTrue(StreamEngine.fslSearchHits("<html>not json</html>").isEmpty())
    }

    @Test
    fun fslGroups_headingOwnedGateways() {
        val html = "<div class=\"entry-content\">" +
            "<h5>Film (2024) 1080p [1.7GB]</h5>" +
            "<a href=\"https://nexdrive.site/genxfm/film.zip/\">G</a>" +
            "<h5>Other (2023) 480p</h5>" +
            "<a href=\"https://nexdrive.site/genxfm/other.zip/\">G2</a></div>"
        val groups = StreamEngine.fslGroups(html)
        assertEquals(2, groups.size)
        assertEquals("Film (2024) 1080p [1.7GB]", groups[0].heading)
        assertEquals(1, groups[0].gateways.size)
        assertTrue(groups[0].gateways[0].endsWith("/"))
        assertEquals("1.7GB", StreamEngine.headingSizeOf(groups[0].heading))
    }

    @Test
    fun gatewayLinksOf_split() {
        val links = StreamEngine.gatewayLinksOf(
            """{\"o\":\"https:\\/\\/fastdl.site\\/embed?download=film_1080p\",\"l\":\"https://vcloud.site/AbCd1234\"}""",
        )
        assertEquals(1, links.cloud.size)
        assertEquals("https://vcloud.site/AbCd1234", links.cloud[0])
        assertTrue(links.fastdl.isEmpty())
    }

    @Test
    fun vcloudDoubleAtob_decode() {
        val inner = java.util.Base64.getEncoder().encodeToString("https://drive.example/f/x1".toByteArray())
        val blob = java.util.Base64.getEncoder().encodeToString(inner.toByteArray())
        assertEquals("https://drive.example/f/x1", StreamEngine.vcloudDoubleAtob(blob))
        assertNull(StreamEngine.vcloudDoubleAtob("!!!not-base64!!!"))
    }

    @Test
    fun vcloudPageMatchesEpisode_taggedPages() {
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02E05.1080p.mkv", "tv", 2, 5))
        assertFalse(StreamEngine.vcloudPageMatchesEpisode("Show.S02E01.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Film.2024.1080p.mkv", "tv", 2, 5))
        assertTrue(StreamEngine.vcloudPageMatchesEpisode("Show.S02E01.1080p.mkv", "movie", 2, 5))
    }

    @Test
    fun episodeFirstOrder_rotatesToRequested() {
        assertEquals(
            listOf("e5", "e1", "e2"),
            StreamEngine.episodeFirstOrder(listOf("e1", "e2", "e5"), "tv", 2, 5),
        )
        assertEquals(
            listOf("e1", "e2", "e5"),
            StreamEngine.episodeFirstOrder(listOf("e1", "e2", "e5"), "movie", 2, 5),
        )
    }

    @Test
    fun fslIsLanding_parkingMarker() {
        assertTrue(StreamEngine.fslIsLanding("<a href=\"https://qikly.pro/host\">follow</a>"))
        assertTrue(StreamEngine.fslIsLanding("<div id=\"stx\">wait</div>"))
        assertFalse(StreamEngine.fslIsLanding("<div>posts live here</div>"))
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
    fun hubFileButtons_skipsGatewayNoise() {
        val html = "<div class=\"card-header\">Jawan 2023 720p</div>" +
            "<a class=\"btn btn-success\" href=\"https://pixeldrain.dev/u/AbC123\">Download</a>" +
            "<a class=\"btn btn-primary\" href=\"https://hubcloud.ist/tg/go?id=x\">Telegram</a>" +
            "<a class=\"btn btn-danger\" href=\"https://pixel.hubcloud.ist/x\">Pixel</a>" +
            "<a class=\"btn btn-primary\" href=\"https://hubcloud.ist/drive/admin\">Admin</a>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(1, out.size)
        assertEquals("https://pixeldrain.dev/api/file/AbC123", out[0].first)
        assertEquals("720p", out[0].second)
    }

    @Test
    fun speedRankMs_unmeasuredLast_hindiDiscount() {
        assertEquals(Double.MAX_VALUE, StreamEngine.speedRankMs(null, "Hindi"))
        assertTrue(StreamEngine.speedRankMs(1000L, "English") < Double.MAX_VALUE)
        assertTrue(StreamEngine.speedRankMs(1000L, "Hindi") < StreamEngine.speedRankMs(1000L, "English"))
        assertTrue(StreamEngine.speedRankMs(500L, "English") < StreamEngine.speedRankMs(1000L, "Hindi"))
        assertTrue(StreamEngine.speedRankMs(1000L, "Hindi") < StreamEngine.speedRankMs(950L, "English"))
        assertTrue(StreamEngine.speedRankMs(1000L, "Multi") < StreamEngine.speedRankMs(1000L, "English"))
        assertTrue(StreamEngine.speedRankMs(1000L, "Tamil") < StreamEngine.speedRankMs(1000L, "English"))
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
