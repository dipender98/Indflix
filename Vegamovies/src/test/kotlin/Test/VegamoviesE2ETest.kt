package Test

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.vegamovies.LinkPayload
import com.vegamovies.VegamoviesProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class VegamoviesE2ETest {

    private val p = VegamoviesProvider()

    private fun List<ExtractorLink>.autoPick(): ExtractorLink? {
        val streamable = filter { it.quality > 0 }
        val best = streamable.maxOfOrNull { it.quality } ?: return null
        return streamable.first { it.quality == best }
    }

    @Test
    fun e2e_search_then_open_then_links() = runBlocking {
        val queries = listOf(
            "bahubali", "pathaan", "3 body problem", "avengers endgame", "kgf", "dune",
        )
        for (q in queries) {
            val res = runCatching { p.search(q) }.getOrNull()
            println("SEARCH '$q' -> ${res?.size ?: -1} hits")
            res?.take(3)?.forEach { println("    ${it.name}  ${it.url}") }
            val top = res?.firstOrNull() ?: continue
            val resp = runCatching { p.load(top.url) }.getOrNull()
            println("  LOAD ${top.url.take(70)} -> ${resp?.javaClass?.simpleName} name=${resp?.name}")
            when (resp) {
                is MovieLoadResponse -> {
                    val links = ArrayList<ExtractorLink>()
                    val ok = runCatching {
                        p.loadLinks(resp.dataUrl, false, { }, { links.add(it) })
                    }.getOrDefault(false)
                    println("  LINKS ok=$ok n=${links.size}")
                    links.take(6).forEach { println("      [q=${it.quality}] ${it.name.take(60)}") }
                    val chosen = links.autoPick()
                    val probe = chosen?.let { l ->
                        runCatching {
                            com.lagradost.cloudstream3.app.get(
                                l.url, timeout = 20,
                                headers = l.headers + mapOf("Range" to "bytes=0-1023"),
                            )
                        }.getOrNull()
                    }
                    println("  AUTOPICK=[q=${chosen?.quality}] PROBE code=${probe?.code} cr=${probe?.headers?.get("content-range")}")
                }
                is TvSeriesLoadResponse -> {
                    val e = resp.episodes.firstOrNull()
                    println("  EPISODES n=${resp.episodes.size} first=${e?.name}")
                    e?.let {
                        val links = ArrayList<ExtractorLink>()
                        val ok = runCatching {
                            p.loadLinks(it.data, false, { }, { links.add(it) })
                        }.getOrDefault(false)
                        println("  LINKS ok=$ok n=${links.size}")
                        val chosen = links.autoPick()
                        val probe = chosen?.let { l ->
                            runCatching {
                                com.lagradost.cloudstream3.app.get(
                                    l.url, timeout = 20,
                                    headers = l.headers + mapOf("Range" to "bytes=0-1023"),
                                )
                            }.getOrNull()
                        }
                        println("  AUTOPICK=[q=${chosen?.quality}] PROBE code=${probe?.code} cr=${probe?.headers?.get("content-range")}")
                    }
                }
                else -> Unit
            }
            println()
        }
    }
}
