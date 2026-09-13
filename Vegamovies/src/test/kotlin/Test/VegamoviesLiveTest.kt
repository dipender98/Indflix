package Test

import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegamovies.LinkPayload
import com.vegamovies.VegamoviesProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Live end-to-end checks for loading and link resolution. */
class VegamoviesLiveTest {

    private val p = VegamoviesProvider()

    /** The link CloudStream's player auto-selects: max quality, first registered. */
    private fun List<ExtractorLink>.autoPick(): ExtractorLink? {
        val streamable = filter { it.quality > 0 }
        val best = streamable.maxOfOrNull { it.quality } ?: return null
        return streamable.first { it.quality == best }
    }

    @Test
    fun live_series_loadAndEpisodeLinks() = runBlocking {
        try {
            val url = "https://new2.vegamovies.futbol/download-squid-game-the-challenge-hindi-english-series-480p-720p-1080p-web-dl/"
            val resp = runCatching { p.load(url) }.getOrNull()
            println("LOAD type=${resp?.javaClass?.simpleName} name=${resp?.name?.take(60)}")
            val ser = resp as? TvSeriesLoadResponse ?: return@runBlocking println("  (not series?)")
            println("EPISODES n=${ser.episodes.size}")
            ser.episodes.take(24).forEach { e ->
                val pl = LinkPayload.fromJson(e.data)
                println("  S${e.season}E${e.episode} name=${e.name?.take(28)} links=${pl?.links?.size ?: -1}")
            }
            for (idx in intArrayOf(0, 2)) {
                val e = ser.episodes.getOrNull(idx) ?: continue
                val links = ArrayList<ExtractorLink>()
                val ok = runCatching { p.loadLinks(e.data, false, { }, { links.add(it) }) }
                    .getOrDefault(false)
                println("LOADLINKS ep[${idx + 1}] ok=$ok n=${links.size}")
                links.forEach { println("    [q=${it.quality}] ${it.name.take(64)}") }
                val chosen = links.autoPick()
                println("    AUTOPICK: [q=${chosen?.quality}] ${chosen?.name?.take(60)}")
                val probe = chosen?.let { l ->
                    runCatching {
                        com.lagradost.cloudstream3.app.get(
                            l.url, timeout = 20, headers = l.headers + mapOf("Range" to "bytes=0-1023"),
                        )
                    }.getOrNull()
                }
                println("    PROBE: code=${probe?.code} cr=${probe?.headers?.get("content-range")}")
                if (probe?.code != 206 && chosen != null) {
                    println("    !!! auto-picked stream is NOT seekable")
                }
            }
        } catch (e: Throwable) {
            println("LIVE SERIES CHECK ISSUE (non-fatal): ${e.message?.take(200)}")
        }
    }

    @Test
    fun live_movie_loadAndLinks() = runBlocking {
        try {
            val url = "https://new2.vegamovies.futbol/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/"
            val resp = runCatching { p.load(url) }.getOrNull()
            val payload = (resp as? com.lagradost.cloudstream3.MovieLoadResponse)?.dataUrl
            println("MOVIE name=${resp?.name} data=${payload?.let { "${it.length}B" } ?: "NONE"}")
            if (payload.isNullOrEmpty()) return@runBlocking println("  (no payload?)")
            val links = ArrayList<ExtractorLink>()
            val ok = runCatching { p.loadLinks(payload, false, { }, { links.add(it) }) }
                .getOrDefault(false)
            println("MOVIE LOADLINKS ok=$ok n=${links.size}")
            links.forEach { println("  [q=${it.quality}] ${it.name.take(70)}") }
            val chosen = links.autoPick()
            println("MOVIE AUTOPICK: [q=${chosen?.quality}] ${chosen?.name?.take(60)}")
            val probe = chosen?.let { l ->
                runCatching {
                    com.lagradost.cloudstream3.app.get(
                        l.url, timeout = 20, headers = l.headers + mapOf("Range" to "bytes=0-1023"),
                    )
                }.getOrNull()
            }
            println("  PROBE: code=${probe?.code} cr=${probe?.headers?.get("content-range")}")
            if (probe?.code != 206 && chosen != null) println("  !!! auto-picked movie stream NOT seekable")
        } catch (e: Throwable) {
            println("LIVE MOVIE CHECK ISSUE (non-fatal): ${e.message?.take(200)}")
        }
    }
}
