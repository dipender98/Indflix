package Test

import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class NetmirrorLiveTest {

    private fun spec() = ServerFarm.allServers.first { it.id == "netmirror" }

    @Test
    fun movie_endToEnd() = runBlocking {
        val s = spec()
        println("NET79 referer=${s.referer} probe=${StreamEngine.probeServer(s)}")
        val streams = StreamEngine.resolveOne(s, 27205, null, "movie", 0, 0)
        println("NET79 MOVIE count=${streams.size} labels=${streams.map { it.audioLabel }.distinct()}")
        streams.forEach { println("  q=${it.qualityHint} lang='${it.audioLabel}' ${it.url.take(80)}") }
        assertTrue(streams.isNotEmpty(), "movie must resolve")
        assertTrue(streams.any { it.qualityHint >= 720 }, "at least one HD stream")
    }

    @Test
    fun tv_endToEnd() = runBlocking {
        val streams = StreamEngine.resolveOne(spec(), 1399, null, "tv", 1, 1)
        println("NET79 TV count=${streams.size}")
        streams.forEach { println("  q=${it.qualityHint} lang='${it.audioLabel}' ${it.url.take(80)}") }
        assertTrue(streams.isNotEmpty(), "tv must resolve")
    }
}
