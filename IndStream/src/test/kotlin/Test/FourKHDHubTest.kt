package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FourKHDHubTest {

    @Test
    fun fourkhdhub_present() {
        val s = com.indstream.ServerFarm.allServers.first { it.id == "4khdhub" }
        assertEquals("4KHDHub", s.name)
        assertEquals(com.indstream.ServerIdType.TMDB, s.idType)
        assertEquals(70, s.timeoutSec)
        assertTrue(s.supports4k)
        assertTrue(s.downloadFriendly)
    }

    @Test
    fun fourkhdhub_hubParseCards_cards() {
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
    fun fourkhdhub_hubPostYearOk_gate() {
        val html = "<html><head><meta property=\"og:title\" content=\"Jawan 2023 Hindi\" /></head></html>"
        assertTrue(StreamEngine.hubPostYearOk(html, "2023"))
        assertTrue(!StreamEngine.hubPostYearOk(html, "2024"))
        assertTrue(StreamEngine.hubPostYearOk(html, null))
    }

    @Test
    fun fourkhdhub_hub4kItems_parsesEpisodeBlocks() {
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
    fun fourkhdhub_hub4kItems_parsesPostBlocks() {
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
    fun fourkhdhub_hubLangOf_dualAudio() {
        assertEquals("Multi", StreamEngine.hubLangOf("Hindi, English"))
        assertEquals("Multi", StreamEngine.hubLangOf("Hindi, Bengali"))
        assertEquals("Hindi", StreamEngine.hubLangOf("Hindi"))
        assertEquals("Multi", StreamEngine.hubLangOf("Dual Audio"))
        assertEquals("English", StreamEngine.hubLangOf("English"))
        assertEquals("", StreamEngine.hubLangOf("BluRay exclusive"))
    }

    @Test
    fun fourkhdhub_hubLangOf_namesSingleNonHindiLanguages() {
        assertEquals("Bengali", StreamEngine.hubLangOf("Bengali"))
        assertEquals("Marathi", StreamEngine.hubLangOf("Marathi"))
        assertEquals("Tamil", StreamEngine.hubLangOf("Tamil"))
        assertEquals("Hindi", StreamEngine.hubLangOf("हिन्दी"))
    }

    @Test
    fun fourkhdhub_rotatorTarget_liveVector() {
        val blob = "Y214WE0xWjNZbXRhVUdwMmIxQldObFo2ZFRCeFZVOXRRbmxxYVV0UU9XRndla2w1YjNveGFYRlVPV3h3YkRWM1RERnFhVzVVT1dkTlNtdDFiM3BGZVhCNWFtbFdkbXAyYjJ4V05sWjZVMVpJZDA5M1JsSXdNa2RWZURWdk1rVkxSbnBqZGtWdGVHdEtlRm94Y0ZSYWJVaExUVzVHVW1OcVRWUXhTWEY1ZUd4S1lXTk5XbmxqWkV4M1YwWmFTV05rUjNveGRVbFVhekpXWVQ5QQ=="
        val page = "<script>s('o','$blob?@',180*1000)</script>"
        assertEquals("https://hubcloud.ist/drive/cfscfcoduf3fi9o", StreamEngine.rotatorTarget(page))
        assertNull(StreamEngine.rotatorTarget("<html>no blob</html>"))
    }

    @Test
    fun fourkhdhub_hubFileButtons_pixeldrainConvert() {
        val html = "<div class=\"card-header\">Jawan 2023 720p</div><div class=\"card-body\">" +
            "<h2><a class=\"btn\" href=\"https://pixeldrain.dev/u/AbC123\">Download File</a></h2></div>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(1, out.size)
        assertEquals("https://pixeldrain.dev/api/file/AbC123", out[0].first)
        assertEquals("720p", out[0].second)
        assertEquals("", out[0].third)
    }

    @Test
    fun fourkhdhub_hubFileButtons_perFileLanguage() {
        val html = "<div class=\"card-header\">Show 2023 720p</div>" +
            "<a class=\"btn\" href=\"https://hubcloud.ist/drive/Hin\">Hindi 720p</a>" +
            "<a class=\"btn\" href=\"https://hubcloud.ist/drive/Eng\">English 720p</a>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(2, out.size)
        assertEquals("720p", out[0].second)
        assertEquals("720p", out[1].second)
        assertEquals("Hindi", out[0].third)
        assertEquals("English", out[1].third)
    }

    @Test
    fun fourkhdhub_hubFileButtons_keepsPixelGatewayForResolution() {
        val html = "<div class=\"card-header\">Jawan 2023 720p</div>" +
            "<a class=\"btn btn-success\" href=\"https://pixeldrain.dev/u/AbC123\">Download</a>" +
            "<a class=\"btn btn-primary\" href=\"https://hubcloud.ist/tg/go?id=x\">Telegram</a>" +
            "<a class=\"btn btn-danger\" href=\"https://pixel.hubcloud.ist/x\">Pixel</a>" +
            "<a class=\"btn btn-primary\" href=\"https://hubcloud.ist/drive/admin\">Admin</a>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(2, out.size)
        assertEquals("https://pixeldrain.dev/api/file/AbC123", out[0].first)
        assertEquals("720p", out[0].second)
        assertEquals("", out[0].third)
        assertEquals("https://pixel.hubcloud.ist/x", out[1].first)
        assertEquals("720p", out[1].second)
    }

    @Test
    fun fourkhdhub_hubFileButtons_fillsLanguageFromCardHeader() {
        val html = "<div class=\"card-header text-white bg-primary\">Fiim 2024 1080p Hindi English Tamil.mkv</div>" +
            "<a class=\"btn btn-danger btn-lg h6\" href=\"https://pixel.hubcloud.ist/x\">Download</a>"
        val out = StreamEngine.hubFileButtons(html)
        assertEquals(1, out.size)
        assertEquals("Hindi, English, Tamil", out[0].third)
        assertEquals("1080p", out[0].second)
    }

    @Test
    fun fourkhdhub_pixelDirectFromFinalUrl_decodesLinkParam() {
        val direct = "https://cdn.example/files/Inception.mkv?sig=y"
        val enc = java.net.URLEncoder.encode(direct, "UTF-8")
        assertEquals(direct, StreamEngine.pixelDirectFromFinalUrl("https://gw.example/dl.php?link=$enc"))
        assertEquals(
            direct,
            StreamEngine.pixelDirectFromFinalUrl("https://gw.example/dl.php?link=https://cdn.example/files/Inception.mkv?sig=y"),
        )
        assertEquals("https://cdn.example/direct.mkv", StreamEngine.pixelDirectFromFinalUrl("https://cdn.example/direct.mkv"))
        assertNull(StreamEngine.pixelDirectFromFinalUrl("https://gw.example/pixel.html?id=1"))
        assertNull(StreamEngine.pixelDirectFromFinalUrl("https://gw.example/dl.php?x=1"))
        assertNull(StreamEngine.pixelDirectFromFinalUrl(null))
    }
}
