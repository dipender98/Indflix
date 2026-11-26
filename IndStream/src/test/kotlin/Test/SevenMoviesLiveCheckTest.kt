package Test

import com.indstream.HttpKit
import com.indstream.ServerFarm
import com.indstream.StreamEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SevenMoviesLiveCheckTest {

    @Test
    fun sevenmovies_liveMovie() = runBlocking {
        val spec = ServerFarm.allServers.first { it.id == "7movies" }
        val out = StreamEngine.resolveOne(spec, 27205, null, "movie", 0, 0)
        println("7movies movie -> ${out.size} streams")
        for (s in out) {
            val alive = HttpKit.aliveCheck(s.url, s.referer, s.extraHeaders) != false
            println("  ${s.qualityHint}p audio='${s.audioLabel}' size='${s.sizeLabel}' alive=$alive ${s.url.take(70)}")
        }
        assertTrue(out.isNotEmpty(), "7movies must resolve live streams")
        assertTrue(out.all { it.qualityHint >= 720 }, "never emits below 720p")
        assertTrue(out.any { it.qualityHint >= 2160 }, "must expose a 4K stream")
        assertTrue(out.any { it.audioLabel.contains("Hindi") }, "must expose Hindi audio")
    }
}
