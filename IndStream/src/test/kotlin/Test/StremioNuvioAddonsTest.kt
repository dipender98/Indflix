package Test

import com.indstream.StremioNuvioAddons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioNuvioAddonsTest {

    @Test
    fun addon_urls_are_normalized_to_a_base_url() {
        assertEquals(
            "https://torrentio.strem.fun",
            StremioNuvioAddons.normalizeUrl("https://torrentio.strem.fun/manifest.json"),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io",
            StremioNuvioAddons.normalizeUrl("stremio://v3-cinemeta.strem.io/manifest.json"),
        )
        assertEquals(
            "https://example.com/addon/path",
            StremioNuvioAddons.normalizeUrl("example.com/addon/path/manifest.json/"),
        )
        assertEquals(
            "https://example.com/configured",
            StremioNuvioAddons.normalizeUrl("https://example.com/configured/manifest.json"),
        )
        assertEquals(
            "https://example.com/sort=seeders|qualityfilter=1080p",
            StremioNuvioAddons.normalizeUrl(
                "https://example.com/sort=seeders|qualityfilter=1080p/manifest.json",
            ),
        )
    }

    @Test
    fun invalid_addon_urls_are_rejected() {
        assertNull(StremioNuvioAddons.normalizeUrl(null))
        assertNull(StremioNuvioAddons.normalizeUrl(""))
        assertNull(StremioNuvioAddons.normalizeUrl("ftp://example.com/manifest.json"))
        assertNull(StremioNuvioAddons.normalizeUrl("https://"))
    }

    @Test
    fun manifest_supports_string_and_object_resources() {
        val manifest = StremioNuvioAddons.parseManifest(
            """
            {
              "id": "org.example.addon",
              "name": "Example Addon",
              "types": ["movie", "series"],
              "resources": ["catalog", {"name": "stream"}],
              "idPrefixes": ["tt"]
            }
            """.trimIndent(),
            "https://example.com",
        )
        assertNotNull(manifest)
        assertEquals("org.example.addon", manifest.id)
        assertEquals("Example Addon", manifest.name)
        assertEquals(setOf("movie", "series"), manifest.types)
        assertEquals(setOf("catalog", "stream"), manifest.resources)
        assertEquals(setOf("tt"), manifest.idPrefixes)
        assertTrue(StremioNuvioAddons.supportsStream(manifest))
        assertTrue(StremioNuvioAddons.supportsType(manifest, "series"))
        assertTrue(StremioNuvioAddons.supportsId(manifest, "tt123:1:2"))
        assertFalse(StremioNuvioAddons.supportsId(manifest, "tmdb:123"))
    }

    @Test
    fun catalog_only_manifest_has_no_stream_resource() {
        val manifest = StremioNuvioAddons.parseManifest(
            """{"id":"catalog","name":"Catalog","types":["movie"],"resources":["catalog","meta"]}""",
            "https://catalog.example",
        )
        assertNotNull(manifest)
        assertFalse(StremioNuvioAddons.supportsStream(manifest))
    }

    @Test
    fun streams_separate_direct_links_from_torrents_and_external_links() {
        val payload = StremioNuvioAddons.parseStreamPayload(
            """
            {
              "streams": [
                {
                  "name": "1080p Hindi",
                  "title": "Example Movie 1.5 GB",
                  "url": "https://cdn.example/movie.m3u8?token=abc",
                  "behaviorHints": {
                    "proxyHeaders": {
                      "request": {"Referer": "https://example.com/", "User-Agent": "Test"}
                    }
                  }
                },
                {
                  "name": "720p",
                  "title": "Example Movie 700 MiB",
                  "url": "//cdn.example/movie.mp4"
                },
                {
                  "name": "Torrent",
                  "infoHash": "abc123",
                  "title": "Torrent source"
                },
                {
                  "name": "Magnet",
                  "url": "magnet:?xt=urn:btih:abc123"
                },
                {
                  "name": "External",
                  "externalUrl": "https://example.com/watch"
                },
                {
                  "name": "YouTube",
                  "ytId": "abc123"
                },
                {
                  "name": "Torrent file",
                  "url": "https://example.com/file.torrent"
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, payload.direct.size)
        assertEquals(2, payload.torrents)
        assertEquals(3, payload.other)

        val first = payload.direct.first()
        assertEquals("https://cdn.example/movie.m3u8?token=abc", first.url)
        assertEquals("https://example.com/", first.headers["Referer"])
        assertEquals("Test", first.headers["User-Agent"])
        assertEquals("https://cdn.example/movie.mp4", payload.direct[1].url)
    }

    @Test
    fun quality_is_read_from_stream_titles_and_urls() {
        assertEquals(2160, StremioNuvioAddons.qualityOf("4K HDR"))
        assertEquals(2160, StremioNuvioAddons.qualityOf("2160p WEB-DL"))
        assertEquals(1080, StremioNuvioAddons.qualityOf("WEB-DL", "https://cdn.example/1080p/file.mp4"))
        assertEquals(720, StremioNuvioAddons.qualityOf("720p"))
        assertEquals(480, StremioNuvioAddons.qualityOf("480p"))
        assertEquals(0, StremioNuvioAddons.qualityOf("Example Movie 2010"))
    }

    @Test
    fun size_is_normalized_for_display() {
        assertEquals("1.5 GB", StremioNuvioAddons.sizeOf("Example Movie 1.5 GB"))
        assertEquals("8.91 GB", StremioNuvioAddons.sizeOf("8.91 GiB"))
        assertEquals("700 MB", StremioNuvioAddons.sizeOf("700 MiB"))
        assertEquals("", StremioNuvioAddons.sizeOf("no size here"))
    }
}
