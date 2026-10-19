package Test

import com.indstream.CastleTvSource
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

/** Guards the Sept 2026 farm expansion: 2Embed, CastleTV, StreamFlix, 4KHDHub. */
class ServerAdditionsTest {

    @Test
    fun vixsrc_disabledCloudflareChallenge() {
        // Disabled (whole domain serves a bot challenge to automated clients) - assert the DISABLED state so a re-enable is a conscious act.
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vixsrc" })
    }

    @Test
    fun deadServers_stayOut() {
        // v46 removals (verified dead): nhd 404, vidapi DNS, zxc sub DNS, vidsrc-to/nontongo Cloudflare.
        for (id in setOf("nhd", "vidapi", "zxcstreams", "vidsrc-to", "nontongo")) {
            assertNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must stay out")
        }
    }

    @Test
    fun videasy_removed_v63() {
        // Dropped in v62; assert it stays out of the farm.
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
        // Generic-pipeline embeds: 0 streams (players load via JS / lazy data-src).
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
        assertEquals(1080, com.indstream.OneTouchTvSource.heightOf("1080p"))
        assertEquals(720, com.indstream.OneTouchTvSource.heightOf("720p"))
        assertEquals(2160, com.indstream.OneTouchTvSource.heightOf("4K"))
        assertEquals(0, com.indstream.OneTouchTvSource.heightOf("Auto"))
        assertEquals(0, com.indstream.OneTouchTvSource.heightOf(null))
        val hits = listOf(
            com.indstream.OneTouchTvSource.Hit("a", "Dune Part Two (2024)", "2024", "movie"),
            com.indstream.OneTouchTvSource.Hit("b", "Dune (2021)", "2021", "movie"),
        )
        assertEquals("a", com.indstream.OneTouchTvSource.matchTitle(hits, "Dune Part Two", 2024, "movie")?.id)
        assertEquals(null, com.indstream.OneTouchTvSource.matchTitle(hits, "Dune Part Two", 2021, "movie")?.id)
        assertEquals(null, com.indstream.OneTouchTvSource.matchTitle(emptyList(), "Dune", 2024, "movie"))
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
        // Re-enabled: the rotator gateway is decoded client-side (v68), so the
        // hub-family resolver works again. A later removal must be conscious.
        assertNotNull(ServerFarm.allServers.firstOrNull { it.id == "4khdhub" })
        // New downloadable fast-file hosts land with the same expansion.
        for (id in setOf("fsl", "hdmovie2", "olamovies")) {
            assertNotNull(ServerFarm.allServers.firstOrNull { it.id == id }, "$id must be present")
        }
    }

    @Test
    fun vidrock_removed_v63() {
        // vidrock + mirror: /api/movie/{id}/ now returns SPA HTML, not JSON.
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock" })
        assertNull(ServerFarm.allServers.firstOrNull { it.id == "vidrock-net" })
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
        // Captured gateway blob (with trailing junk chars) -> drive url.
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
        // Blank size: name unchanged.
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
    fun hd2Anchors_downloadPaths() {
        val html = "<a href=\"/download/tt1-film-480p/\">480p</a>" +
            "<a href=\"/movies/tt1-film/\">post</a>" +
            "<a href=\"/episode/s01e03-x/\">E</a>"
        val anchors = StreamEngine.hd2Anchors(html, "https://idhost.example")
        assertEquals(2, anchors.size)
        assertTrue(anchors[0].first.contains("/download/"))
    }

    @Test
    fun olaDirectOf_pixeldrainApi() {
        assertEquals(
            "https://pixeldrain.com/api/file/AbC123",
            StreamEngine.olaDirectOf("https://pixeldrain.com/u/AbC123"),
        )
        assertNull(StreamEngine.olaDirectOf("https://gofile.io/d/AbC123"))
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
