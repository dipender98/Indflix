package Test

import com.indstream.ServerFarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerProbeUrlTest {

    @Test
    fun probe_url_falls_back_to_movie_endpoint() {
        val spec = ServerFarm.allServers.first { it.id == "netmirror" }
        assertEquals(
            "https://net27.cc/api/embed-tmdb/27205?type=movie",
            ServerFarm.buildProbeUrl(spec, "27205", "Inception", "2010"),
        )
    }

    @Test
    fun probe_url_uses_title_placeholder() {
        val spec = ServerFarm.allServers.first { it.id == "onetouchtv" }
        assertEquals(
            "https://api3.devcorp.me/vod/search?keyword=Inception",
            ServerFarm.buildProbeUrl(spec, "27205", "Inception", "2010"),
        )
    }

    @Test
    fun every_registry_endpoint_is_absolute() {
        ServerFarm.allServers.forEach { spec ->
            assertTrue(spec.movieUrl.startsWith("https://"), spec.id)
            assertTrue(spec.tvUrl.startsWith("https://"), spec.id)
            spec.probeUrl?.let { assertTrue(it.startsWith("https://"), spec.id) }
        }
    }
}
