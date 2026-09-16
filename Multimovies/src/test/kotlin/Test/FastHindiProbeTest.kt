package Test

import com.multimovies.GdMirrorExtractor
import com.multimovies.MultiSourcePuller
import com.multimovies.NxshaExtractor
import com.multimovies.NxshaSource
import com.multimovies.NxshaSubtitle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Live full-chain timing + Hindi/Indian-language audit for the fastest non-farm carriers. Manual run only. */
class FastHindiProbeTest {

    private fun hindiCount(srcs: List<NxshaSource>): Int {
        val hay = srcs.joinToString(" ") { "${it.name} ${it.url}".lowercase() }
        return if (hay.contains("hindi")) srcs.count { "${it.name} ${it.url}".lowercase().contains("hindi") } else 0
    }

    @Test
    fun nxsha_chain_timingAndHindi() = runBlocking {
        // Inception for comparability with PracticalResolveTest, plus a dubbed title.
        val cases = listOf(
            27205 to "tt1375666",
            299534 to "tt4154796",   // Avengers: Endgame (widely Hindi-dubbed)
            360814 to "tt5074352",   // Dangal (natively Hindi)
        )
        for ((tmdb, imdb) in cases) {
            val src = MultiSourcePuller.Source(
                name = "Nxsha",
                url = "https://nxsha.space/embed/movie/$tmdb",
                tmdbId = tmdb.toString(),
                imdbId = imdb,
            )
            val t0 = System.currentTimeMillis()
            val subs = mutableListOf<NxshaSubtitle>()
            val out = NxshaExtractor.extract(src) { subs += it }
            val ms = System.currentTimeMillis() - t0
            println("NXSHA tmdb=$tmdb: ${out.size} streams in ${ms}ms (subs=${subs.size})")
            out.take(8).forEach {
                val h = "${it.name} ${it.url}".lowercase().contains("hindi")
                println("   ${it.name} q=${it.quality} m3u8=${it.isM3u8} hindi=$h ${it.url.take(72)}")
            }
            println("   hindi-tagged: ${hindiCount(out)}")
        }
    }

    @Test
    fun gdmirror_chain_timingAndHindi() = runBlocking {
        val cases = listOf("tt1375666", "tt4154796", "tt5074352")
        for (imdb in cases) {
            val t0 = System.currentTimeMillis()
            val out = GdMirrorExtractor.extract("https://streams.iqsmartgames.com/embed/movie/$imdb")
            val ms = System.currentTimeMillis() - t0
            println("GDMIRROR imdb=$imdb: ${out.size} streams in ${ms}ms")
            out.take(6).forEach { println("   ${it.name} ${it.url.take(80)}") }
        }
    }
}
