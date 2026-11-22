package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated coverage for the MoviesDrive download-farm provider.
 * The provider searches via poster cards and resolves files from archive
 * pages or hubcloud search-recover APIs keyed by episode tags.
 */
class MoviesDriveTest {

    private val spec get() = ServerFarm.allServers.first { it.id == "moviesdrive" }

    @Test
    fun registry_presentExactlyOnce() {
        assertEquals(1, ServerFarm.allServers.count { it.id == "moviesdrive" }, "one toggle, one health record")
    }

    @Test
    fun registry_presentAndTmdbKeyed() {
        assertEquals("MoviesDrive", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://new3.moviesdrive.beer/", spec.referer)
        assertEquals(60, spec.timeoutSec, "download-farm kill must fit the search + recover chain")
        assertEquals(ServerSpeed.SLOW, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertFalse(spec.hasSubtitles)
        assertFalse(spec.animeOnly)
        assertFalse(spec.supports4k)
        assertTrue(spec.declaredLanguages.isEmpty(), "audio is read per-file, not declared")
    }

    @Test
    fun registry_urls_searchAtRoot() {
        assertEquals("https://new3.moviesdrive.beer/", ServerFarm.buildMovieUrl(spec, "872906"))
        assertEquals("https://new3.moviesdrive.beer/", ServerFarm.buildTvUrl(spec, "1399", 1, 1))
    }

    @Test
    fun search_posterHits_onSearchPage() {
        val html = "<a href=\"https://new3.moviesdrive.beer/jawan-2023/\" data-wpel-link=\"internal\">" +
            "<div class=\"poster-card\"><p class=\"poster-title\">Download Jawan (2023) Hindi 720p 1080p</p></div></a>" +
            "<a href=\"https://new3.moviesdrive.beer/search/\" data-wpel-link=\"internal\">" +
            "<p class=\"poster-title\">Search</p></a>"
        val hits = StreamEngine.posterHits(html, "https://new3.moviesdrive.beer")
        assertEquals(1, hits.size)
        assertEquals("https://new3.moviesdrive.beer/jawan-2023/", hits[0].first)
        assertTrue(hits[0].second.contains("Jawan"))
    }

    @Test
    fun archiveAnchors_mdrivePicked() {
        val html = "<h5>Show S01E02 720p [1GB]</h5>" +
            "<h5><a href=\"https://mdrive.lol/archive/xyz\">Archive</a></h5>" +
            "<h5><a href=\"https://hubcloud.ist/drive/search-recover.php?from_ac=abc\">720p 10bit [1GB]</a></h5>"
        val hits = StreamEngine.headingAnchorHits(
            html,
            "https://new3.moviesdrive.beer",
            Regex("mdrive\\.lol/archive|search-recover", RegexOption.IGNORE_CASE),
        )
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.first.contains("mdrive.lol/archive") })
        assertTrue(hits.any { it.first.contains("search-recover.php") })
        assertTrue(hits.all { it.second.contains("720p") })
    }

    @Test
    fun fileMatches_realNames() {
        assertTrue(StreamEngine.fileNameMatches("Jawan", "2023", "Jawan.2023.1080p.Hindi.mkv"))
        assertTrue(StreamEngine.fileNameMatches("Dune Part Two", "2024", "Dune.Part.Two.2024.1080p.mkv"))
        assertFalse(StreamEngine.fileNameMatches("Jawan", "2023", "Kuriyan.Jawan.Bapu.2025.720p.mkv"))
    }

    @Test
    fun qualityAndSize_fromLabels() {
        assertEquals("1080p", StreamEngine.qualityText("Jawan.2023.1080p.Hindi.mkv"))
        assertEquals(1080, StreamEngine.releaseHeightOf("Jawan 2023 1080p Hindi"))
        assertEquals("1.7GB", StreamEngine.normalizeSizeToken("Film (2024) 1080p [1.7GB]"))
        assertEquals("Hindi", StreamEngine.releaseLanguageOf("Jawan 2023 Hindi WEB-DL"))
    }

    @Test
    fun headingAnchorHits_mergesEpisodeHeadingWithFileLabel() {
        val html = "<h5>Show S01E02 720p [1GB]</h5>" +
            "<h5><a href=\"https://hubcloud.ist/drive/search-recover.php?from_ac=x\">720p 10bit [1GB]</a></h5>"
        val hits = StreamEngine.headingAnchorHits(html, "https://site.example", Regex("search-recover"))
        assertEquals(1, hits.size)
        assertTrue(hits[0].second.contains("S01E02"))
        assertTrue(hits[0].second.contains("720p"))
    }

    @Test
    fun fileHostLinks_keepsFilesAndSkipsGatewayNoise() {
        val html = "<a href=\"https://hubcloud.ist/drive/x\">Cloud</a>" +
            "<a href=\"https://share.example/0:/film.mkv\">Direct</a>" +
            "<a href=\"https://hubcloud.ist/tg/go?id=x\">Telegram</a>" +
            "<a href=\"https://pixel.hubcloud.ist/x\">Pixel</a>" +
            "<a href=\"https://pixeldrain.dev/u/AbC123\">PixelDrain</a>" +
            "<a href=\"https://host.example/hubcloud.php?token=x\">Token</a>"
        val hits = StreamEngine.fileHostLinks(html, "https://site.example")
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.first.endsWith(".mkv") })
        assertTrue(hits.any { it.first.contains("pixeldrain") })
    }

    @Test
    fun pixeldrainDirect_preservesHostAndConvertsPageUrls() {
        assertEquals(
            "https://pixeldrain.dev/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.dev/u/AbC123"),
        )
        assertEquals(
            "https://pixeldrain.com/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.com/api/file/AbC123"),
        )
        assertNull(StreamEngine.pixeldrainDirect("https://share.example/u/AbC123"))
    }

    @Test
    fun qualityText_and_fileNameMatches() {
        assertEquals("720p", StreamEngine.qualityText("720p 10Bit HEVC [1GB]"))
        assertEquals("4K", StreamEngine.qualityText("4K [2160p SDR]"))
        assertTrue(StreamEngine.fileNameMatches("Jawan", "2023", "Jawan.Extended.Cut.2023.720p.mkv"))
        assertTrue(!StreamEngine.fileNameMatches("Jawan", "2023", "Kuriyan.Jawan.Bapu.2025.720p.mkv"))
        assertTrue(StreamEngine.fileNameMatches("Dune Part Two", "2024", "Dune.Part.Two.2024.1080p.mkv"))
    }
}
