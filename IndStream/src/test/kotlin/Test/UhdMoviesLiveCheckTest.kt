package Test

import com.indstream.HttpKit
import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class UhdMoviesLiveCheckTest {

    @Test
    fun uhdmovies_liveMovie() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "uhdmovies" }
        val out = StreamEngine.resolveOne(spec, 61202, null, "movie", 0, 0)
        println("uhdmovies movie -> ${out.size} streams")
        for (s in out) {
            val alive = HttpKit.aliveCheck(s.url, s.referer, s.extraHeaders) != false
            println("  ${s.qualityHint}p audio='${s.audioLabel}' size='${s.sizeLabel}' alive=$alive ${s.url.take(90)}")
        }
        assertTrue(out.isNotEmpty(), "uhdmovies must resolve live movie streams")
        assertTrue(out.all { it.url.startsWith("http") }, "urls are absolute")
        assertTrue(out.all { it.qualityHint >= 720 }, "never emits below 720p")
    }
}
