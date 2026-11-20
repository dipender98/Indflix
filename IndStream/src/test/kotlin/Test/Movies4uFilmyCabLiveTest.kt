package Test

import com.indstream.HttpKit
import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end checks for the two 4K multi-audio download sources.
 * Mirrors [PracticalResolveTest]: real network, fixed ids, real file hosts.
 */
class Movies4uFilmyCabLiveTest {

    private suspend fun report(id: String, label: String, out: List<StreamEngine.RawStream>) {
        println("$id $label -> ${out.size} streams")
        for (s in out) {
            val alive = HttpKit.aliveCheck(s.url, s.referer, s.extraHeaders) != false
            println("  ${s.qualityHint}p audio='${s.audioLabel}' size='${s.sizeLabel}' alive=$alive ${s.url.take(80)}")
        }
        assertTrue(out.isNotEmpty(), "$id must resolve live streams for $label")
        assertTrue(out.all { it.url.startsWith("http") }, "$id urls are absolute")
        assertTrue(out.all { it.qualityHint >= 720 }, "$id never emits below 720p")
    }

    @Test
    fun movies4u_liveMovie() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "movies4u" }
        val out = StreamEngine.resolveOne(spec, 872906, null, "movie", 1, 1)
        report("movies4u", "Jawan (2023)", out)
        assertTrue(out.any { it.qualityHint >= 2160 }, "movies4u advertises a real 4K file")
    }

    @Test
    fun movies4u_liveTv() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "movies4u" }
        val out = StreamEngine.resolveOne(spec, 76479, null, "tv", 5, 5)
        report("movies4u", "The Boys S05E05", out)
    }
}