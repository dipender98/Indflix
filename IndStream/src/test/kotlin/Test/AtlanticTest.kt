package Test

import com.indstream.ManifestKit
import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlanticTest {

    private val spec get() = ServerFarm.allServers.first { it.id == "atlantic" }

    @Test
    fun registry_presentExactlyOnce() {
        assertEquals(1, ServerFarm.allServers.count { it.id == "atlantic" }, "one toggle, one health record")
    }

    @Test
    fun registry_presentAndTmdbKeyed() {
        assertEquals("Atlantic", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://atlantic.st/", spec.referer)
        assertEquals(ServerSpeed.VERY_FAST, spec.speed)
        assertTrue(spec.reliable)
        assertTrue(spec.declaredLanguages.isEmpty(), "single-track audio is not declared")
        assertTrue(spec.isJsonApi, "cdn lookup is a JSON API")
        assertEquals(30, spec.timeoutSec, "covers primary + fallback chain")
    }

    @Test
    fun registry_urls_carryTmdbAndEpisode() {
        assertEquals(
            "https://zcdn.bluevelvet.space/cdn?tmdb=27205&type=movie",
            ServerFarm.buildMovieUrl(spec, "27205"),
        )
        assertEquals(
            "https://zcdn.bluevelvet.space/cdn?tmdb=1396&type=tv&season=2&episode=3",
            ServerFarm.buildTvUrl(spec, "1396", 2, 3),
        )
    }

    @Test
    fun cdnJson_readyUrl() {
        val json = """{"ready":true,"url":"https://cdn.example/play/abc/master.m3u8"}"""
        assertEquals("https://cdn.example/play/abc/master.m3u8", StreamEngine.atlanticCdnUrl(json))
    }

    @Test
    fun cdnJson_rejectsNotReadyAndGarbage() {
        assertNull(StreamEngine.atlanticCdnUrl("""{"ready":false,"url":"https://cdn.example/x.m3u8"}"""))
        assertNull(StreamEngine.atlanticCdnUrl("""{"ready":true}"""))
        assertNull(StreamEngine.atlanticCdnUrl("""{"ready":true,"url":"not-a-url"}"""))
        assertNull(StreamEngine.atlanticCdnUrl("<html>error</html>"))
        assertNull(StreamEngine.atlanticCdnUrl(null))
    }

    @Test
    fun sourceJson_readsNestedSources() {
        val json = """
            {"sources":{"Moscow":{"label":"AUTO","server":"Moscow","type":"hls","url":"ms_deadbeef"}}}
        """.trimIndent()
        assertEquals("ms_deadbeef", StreamEngine.atlanticSourceUrl(json))
    }

    @Test
    fun sourceJson_rejectsGarbage() {
        assertNull(StreamEngine.atlanticSourceUrl("""{"sources":{}}"""))
        assertNull(StreamEngine.atlanticSourceUrl("""{"sources":{"Moscow":{"url":""}}}"""))
        assertNull(StreamEngine.atlanticSourceUrl("forbidden"))
        assertNull(StreamEngine.atlanticSourceUrl(null))
    }

    @Test
    fun decrypt_knownVector() {
        // AES-256-GCM: iv(12) || ciphertext || tag(16), prefixed with ms_.
        val payload = "ms_000102030405060708090a0b9ef7f55340ef5d8f95099b6f44dd7dc5f73da7a2094a2b61015a9f08e047f434b6f455497d6cf10a4dc66d5ee812d304af61190813950d"
        assertEquals(
            "https://example.test/stream/master.m3u8",
            StreamEngine.atlanticDecryptSource(payload),
        )
    }

    @Test
    fun decrypt_rejectsGarbage() {
        assertNull(StreamEngine.atlanticDecryptSource("ms_0011"))
        assertNull(StreamEngine.atlanticDecryptSource("ms_zzzz"))
        assertNull(StreamEngine.atlanticDecryptSource(""))
        assertNull(StreamEngine.atlanticDecryptSource(null))
        // Tampered tag must fail authentication.
        val tampered = "ms_000102030405060708090a0b9ef7f55340ef5d8f95099b6f44dd7dc5f73da7a2094a2b61015a9f08e047f434b6f455497d6cf10a4dc66d5ee812d304af61190813950e"
        assertNull(StreamEngine.atlanticDecryptSource(tampered))
    }

    @Test
    fun primaryLadder_peaksAt4kAndPassesFloor() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=16186248,RESOLUTION=3840x2160
            v0/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6152556,RESOLUTION=1920x1080
            v1/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3121132,RESOLUTION=1280x720
            v2/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1322438,RESOLUTION=852x480
            v3/index.m3u8
        """.trimIndent()
        val parsed = ManifestKit.parseMaster(master, "https://cdn.example/master.m3u8")
        val height = ManifestKit.bestHeight(parsed!!.variants)
        assertEquals(2160, height)
        assertEquals(4, parsed.variants.size)
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = height))
        assertTrue(parsed.variants.all { it.url.startsWith("https://cdn.example/") })
    }

    @Test
    fun fallbackLadder_normalizesToHdAndDropsLowest() {
        assertEquals(360, ManifestKit.normalizeHeight(640, 266))
        assertEquals(720, ManifestKit.normalizeHeight(1280, 534))
        assertEquals(1080, ManifestKit.normalizeHeight(1920, 800))
        assertFalse(StreamEngine.passesQualityFloor(isAdaptive = true, height = ManifestKit.normalizeHeight(640, 266)))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = ManifestKit.normalizeHeight(1280, 534)))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = ManifestKit.normalizeHeight(1920, 800)))
    }

    @Test
    fun lowOnlyLadder_isRejected() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480
            v0/index.m3u8
        """.trimIndent()
        val parsed = ManifestKit.parseMaster(master, "https://cdn.example/master.m3u8")!!
        val height = ManifestKit.bestHeight(parsed.variants)
        assertEquals(480, height)
        assertFalse(StreamEngine.passesQualityFloor(isAdaptive = true, height = height))
    }
}
