package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UhdMoviesTest {

    @Test
    fun uhdmovies_registry_presentAndTmdbKeyed() {
        val spec = ServerFarm.allServers.first { it.id == "uhdmovies" }
        assertEquals("UHDMovies", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://uhdmovies.org/", spec.referer)
        assertEquals(60, spec.timeoutSec)
        assertEquals(ServerSpeed.SLOW, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertTrue(spec.supports4k)
        assertTrue(!spec.hasSubtitles)
    }

    @Test
    fun uhdmovies_downloadAnchors_labeledOnly() {
        val html = "<div>" +
            "<a href=\"https://secure.linkszilla.top/view/abc\">1080p [2.3GB]</a>" +
            "<a href=\"https://secure.linkszilla.top/view/def\">4K [6.1GB]</a>" +
            "<a href=\"https://mobilejsr.com/view/ghi\"><img src=\"x.png\"></a>" +
            "<a href=\"https://uhdmovies.org/self/\">1080p [1GB]</a>" +
            "<a href=\"https://secure.linkszilla.top/view/jkl.zip\">720p [1GB]</a>" +
            "</div>"
        val out = StreamEngine.uhdDownloadAnchors(html, "https://uhdmovies.org")
        assertEquals(2, out.size)
        assertEquals("https://secure.linkszilla.top/view/abc", out[0].first)
        assertEquals("1080p [2.3GB]", out[0].second)
        assertEquals("4K [6.1GB]", out[1].second)
    }

    @Test
    fun uhdmovies_hostLinks_keepsPlayableHosts() {
        val html = "<div>" +
            "<a href=\"https://hubcloud.mov/drive/rglhteeruzfzz33\">hub</a>" +
            "<a href=\"https://new10.gdflix.io/file/kudSMDBVKMZZuUC\">gdflix</a>" +
            "<a href=\"https://pixeldrain.dev/api/file/AbC123\">pixel</a>" +
            "<a href=\"https://gofile.io/d/GZKC1sP6\">gofile</a>" +
            "<a href=\"https://clicknupload.cam/196w0v8i8ln7\">clicknupload</a>" +
            "<a href=\"https://cdn.example/direct.mkv\">mkv</a>" +
            "</div>"
        val out = StreamEngine.uhdHostLinks(html, "https://secure.linkszilla.top/view/x")
        assertEquals(3, out.size)
        assertTrue(out.any { it.contains("hubcloud") })
        assertTrue(out.any { it.contains("pixeldrain") })
        assertTrue(out.any { it.endsWith(".mkv") })
    }
}
