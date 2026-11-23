package Test

import com.indstream.HttpKit
import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class Movies4uLiveCheckTest {

    @Test
    fun movies4u_liveMovie() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "movies4u" }
        val out = StreamEngine.resolveOne(spec, 872906, null, "movie", 0, 0)
        println("movies4u movie -> ${out.size} streams")
        for (s in out) {
            val alive = HttpKit.aliveCheck(s.url, s.referer, s.extraHeaders) != false
            println("  ${s.qualityHint}p audio='${s.audioLabel}' size='${s.sizeLabel}' alive=$alive ${s.url.take(90)}")
        }
        assertTrue(out.isNotEmpty(), "movies4u must resolve live movie streams")
        assertTrue(out.all { it.url.startsWith("http") }, "urls are absolute")
        assertTrue(out.all { it.qualityHint >= 720 }, "never emits below 720p")
    }

    @Test
    fun movies4u_liveTv() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "movies4u" }
        val out = StreamEngine.resolveOne(spec, 1396, null, "tv", 1, 1)
        println("movies4u tv -> ${out.size} streams")
        for (s in out) {
            val alive = HttpKit.aliveCheck(s.url, s.referer, s.extraHeaders) != false
            println("  ${s.qualityHint}p audio='${s.audioLabel}' size='${s.sizeLabel}' alive=$alive ${s.url.take(90)}")
        }
        assertTrue(out.isNotEmpty(), "movies4u must resolve live tv streams")
        assertTrue(out.all { it.url.startsWith("http") }, "urls are absolute")
        assertTrue(out.all { it.qualityHint >= 720 }, "never emits below 720p")
    }
}