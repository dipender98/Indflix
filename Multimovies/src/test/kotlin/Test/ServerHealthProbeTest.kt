package Test

import com.multimovies.GdMirrorExtractor
import com.multimovies.HttpKit
import com.multimovies.ModiplayExtractor
import com.multimovies.MultiSourcePuller
import com.multimovies.NxshaExtractor
import com.multimovies.NxshaSubtitle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** One-off health sweep across every server chain; prints where each chain breaks. Manual run only. */
class ServerHealthProbeTest {

    private val tmdb = "27205"
    private val imdb = "tt1375666"

    @Test
    fun probeAllServers() = runBlocking {
        probeNxsha()
        probeGdMirror()
        probeSiteServers()
    }

    private suspend fun probeNxsha() {
        println("=== NXSHA ===")
        val src = MultiSourcePuller.Source(
            name = "Nxsha",
            url = "https://nxsha.space/embed/movie/$tmdb",
            tmdbId = tmdb,
            imdbId = imdb,
        )
        val out = NxshaExtractor.extract(src) { }
        println("  extract(): ${out.size} streams")
        out.take(5).forEach { println("   ${it.name} q=${it.quality} ${it.url.take(70)}") }
    }

    private suspend fun probeGdMirror() {
        println("=== GDMIRROR (with the site chip's key) ===")
        val page = "https://streams.iqsmartgames.com/embed/movie/tt7142506?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4"
        val out = GdMirrorExtractor.extract(page)
        println("  extract(): ${out.size} streams")
        out.take(5).forEach { println("   ${it.name} ${it.url.take(80)}") }

        println("=== GDMIRROR (keyless embed, fallback key) ===")
        val keyless = GdMirrorExtractor.extract("https://streams.iqsmartgames.com/embed/movie/tt7142506")
        println("  extract(): ${keyless.size} streams")
        keyless.take(5).forEach { println("   ${it.name} ${it.url.take(80)}") }
    }

    /** Site server chips for "Sir" (tmdb 518497 / tt7142506). */
    private suspend fun probeSiteServers() {
        val noSub: (NxshaSubtitle) -> Unit = { }
        println("=== CINEVERSE (modiplay) ===")
        val mod = ModiplayExtractor.extract("https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=518497")
        println("  extract(): ${mod.size} streams")
        mod.take(3).forEach { println("   ${it.name} ${it.url.take(90)}") }

        println("=== VIDOUT ===")
        val vo = com.multimovies.VidoutExtractor.extract("518497", "movie", null)
        println("  extract(): ${vo.size} streams")
        vo.take(3).forEach { println("   $it".take(95)) }

        println("=== BINGR ===")
        val bg = com.multimovies.BingrExtractor.extract("518497", "movie", "Sir", "2018", null, null, noSub)
        println("  extract(): ${bg.size} streams")
        bg.take(3).forEach { println("   ${it.name} ${it.url.take(80)}") }

        println("=== FILMU ===")
        val fu = com.multimovies.FilmuExtractor.extract("tt7142506", "518497", "movie", "Sir", "2018", null, null, noSub)
        println("  extract(): ${fu.size} streams")
        fu.take(3).forEach { println("   ${it.name} ${it.url.take(80)}") }

        println("=== VIDBOLT ===")
        val vb = com.multimovies.VidboltExtractor.extract("tt7142506", "518497", "movie", "Sir", "2018", null, null, noSub)
        println("  extract(): ${vb.size} streams")
        vb.take(3).forEach { println("   ${it.name} ${it.url.take(80)}") }
    }

    @Suppress("unused")
    private suspend fun unusedHttpKitRef() {
        HttpKit.get("https://example.com", budgetMs = 100)
    }
}
