package Test

import com.indstream.ServerFarm
import com.indstream.StreamEngine
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllProvidersNamingTest {

    @Test
    fun everyProvider_usesTheSharedNamingFormat() = runBlocking {
        val specs = ServerFarm.allServers
        assertTrue(specs.size >= 15, "expected the full provider farm, got ${specs.size}")

        val mismatches = mutableListOf<String>()
        for (spec in specs) {
            val raw = listOf(
                StreamEngine.RawStream(
                    serverId = spec.id,
                    serverName = spec.name,
                    url = "https://cdn.example/files/movie.2024.1080p.BluRay.HEVC.5.1.9000Kbps.mkv",
                    isM3u8 = false,
                    qualityHint = 1080,
                    audioLabel = "Hindi",
                    sizeLabel = "2.3GB",
                    mediaLabel = "movie.2024.1080p.BluRay.HEVC.5.1.9000Kbps.mkv",
                ),
            )
            val names = mutableListOf<String>()
            StreamEngine.emit(raw, { link: ExtractorLink -> names += link.name }, probeManifests = false)

            val expected = "${spec.name} · cdn · 💿BluRay · 🎞️HEVC · 🔊5.1 · ⚡9~Mbps · 💾2.3GB · (🇮🇳 Hindi)"
            if (names.size != 1 || names[0] != expected) {
                mismatches += "${spec.id}: ${names.joinToString(" ; ")}"
            }
        }
        assertEquals(emptyList<String>(), mismatches, "providers with wrong name format")
    }
}
