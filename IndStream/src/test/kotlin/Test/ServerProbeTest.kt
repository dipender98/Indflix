package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live network probe: exercises every farm server's real chain entry and reports health. Manual run only (needs network). */
class ServerProbeTest {

    // Inception (TMDB 27205 / IMDB tt1375666), GoT S1E1 (TMDB 1399 / IMDB tt0944947).
    private val tmdbMovieId = "27205"
    private val imdbMovieId = "tt1375666"
    private val tmdbTvId = "1399"
    private val imdbTvId = "tt0944947"
    private val season = 1
    private val episode = 1

    private data class Fetch(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val ms: Long,
        val error: String? = null,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class ProbeResult(
        val serverId: String,
        val check: String,
        val ok: Boolean,
        val detail: String,
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private fun fetch(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): Fetch {
        val start = System.currentTimeMillis()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", ua)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = runCatching {
                stream?.bufferedReader()?.readText()?.take(8192) ?: ""
            }.getOrDefault("")
            val hdrs = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            conn.disconnect()
            Fetch(code, hdrs, body, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Fetch(0, emptyMap(), "", System.currentTimeMillis() - start,
                e.javaClass.simpleName + ": " + (e.message?.take(120) ?: ""))
        }
    }

    private fun alive(code: Int): Boolean = code in 200..399 || code == 416

    @Test
    fun probeAllServers_movieAndTv() {
        val results = mutableListOf<ProbeResult>()
        // Chain-entry checks for servers whose registry URL is not a plain GET target.
        val special = mapOf(
            "moviebox" to ::checkMovieBox,
            "vidnest" to ::checkVidNest,
            "castletv" to ::checkCastleTv,
            "videasy" to ::checkVideasy,
        )
        for (spec in ServerFarm.allServers) {
            val check = special[spec.id]
            if (check != null) {
                results += check(spec.id)
                continue
            }
            val id = if (spec.idType == ServerIdType.IMDB) imdbMovieId else tmdbMovieId
            val movieUrl = ServerFarm.buildMovieUrl(spec, id)
            val timeoutMs = spec.timeoutSec.coerceAtMost(15) * 1000
            val m = fetch(movieUrl, refererHeaders(spec.referer), timeoutMs)
            results += ProbeResult(spec.id, "movie",
                alive(m.code), "HTTP ${m.code} ${m.ms}ms ${m.error ?: ""}".trim())

            val tvId = if (spec.idType == ServerIdType.IMDB) imdbTvId else tmdbTvId
            val tvUrl = ServerFarm.buildTvUrl(spec, tvId, season, episode)
            val t = fetch(tvUrl, refererHeaders(spec.referer), timeoutMs)
            results += ProbeResult(spec.id, "tv",
                alive(t.code), "HTTP ${t.code} ${t.ms}ms ${t.error ?: ""}".trim())
        }

        println("\n" + "=".repeat(110))
        println("INDSTREAM SERVER PROBE RESULTS")
        println("=".repeat(110))
        println("%-14s %-8s %-4s %s".format("SERVER", "CHECK", "OK", "DETAIL"))
        println("-".repeat(110))
        for (r in results) {
            println("%-14s %-8s %-4s %s".format(r.serverId, r.check, if (r.ok) "yes" else "NO", r.detail.take(80)))
        }
        val okIds = results.filter { it.ok }.map { it.serverId }.toSet()
        val deadIds = ServerFarm.allServers.map { it.id }.toSet() - okIds
        println("-".repeat(110))
        println("Alive: ${okIds.size}/${ServerFarm.allServers.size}")
        if (deadIds.isNotEmpty()) println("Dead: ${deadIds.joinToString()}")
        println("=".repeat(110))

        // The four previously-failing servers must answer through their real chain entries.
        for (id in special.keys) {
            assertTrue(results.any { it.serverId == id && it.ok }, "$id must answer")
        }
        assertTrue(okIds.isNotEmpty(), "At least one server must respond")
    }

    private fun refererHeaders(referer: String?): Map<String, String> =
        if (referer.isNullOrBlank()) emptyMap() else mapOf("Referer" to referer)

    // Bearer-token endpoint answers 200 with the token in the x-user header; the title search is a POST needing it.
    private fun checkMovieBox(id: String): List<ProbeResult> {
        val url = ServerFarm.buildMovieUrl(ServerFarm.allServers.first { it.id == id }, tmdbMovieId)
        val f = fetch(url)
        val token = f.header("x-user")?.contains("token") == true
        return listOf(ProbeResult(id, "token", f.code == 200 && token,
            "HTTP ${f.code} x-user=${if (token) "present" else "MISSING"} ${f.error ?: ""}".trim()))
    }

    @Test
    fun vidnestShouldRetry_blankAndErrorPagesOnly() {
        // Blank answers and 502/error pages deserve the single delayed retry.
        assertTrue(StreamEngine.vidnestShouldRetry(null))
        assertTrue(StreamEngine.vidnestShouldRetry("  "))
        assertTrue(StreamEngine.vidnestShouldRetry("""{"error_name":"x","message":"Error 502: Bad gateway"}"""))
        assertTrue(StreamEngine.vidnestShouldRetry("<html>origin error</html>"))
        // Answered payloads never retry, even when they carry no streams.
        assertFalse(StreamEngine.vidnestShouldRetry("""{"sources":[]}"""))
        assertFalse(StreamEngine.vidnestShouldRetry("""{"encrypted":true,"data":"e30="}"""))
    }

    // Fan-out host: probe every sub-server's movie URL, mirroring the resolver's fan-out.
    private fun checkVidNest(id: String): List<ProbeResult> {
        val subs = listOf("moviebox", "allmovies", "klikxxi", "onehd", "hollymoviehd", "purstream", "vidlink")
        val headers = mapOf(
            "Referer" to "https://vidnest.fun/",
            "Origin" to "https://vidnest.fun",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        return subs.map { sub ->
            val f = fetch("https://new.vidnest.fun/$sub/movie/$tmdbMovieId", headers, 10000)
            ProbeResult(id, "sub:$sub", alive(f.code) && f.body.length > 20,
                "HTTP ${f.code} len=${f.body.length} ${f.error ?: ""}".trim())
        }
    }

    // Chain entry is the security-key fetch; search/detail/video are POSTs needing that key.
    private fun checkCastleTv(id: String): List<ProbeResult> {
        val url = ServerFarm.buildMovieUrl(ServerFarm.allServers.first { it.id == id }, tmdbMovieId)
        val f = fetch(url, mapOf("Accept" to "application/json"))
        return listOf(ProbeResult(id, "seckey", f.code == 200 && f.body.contains("\"data\""),
            "HTTP ${f.code} ${f.body.take(80)} ${f.error ?: ""}".trim()))
    }

    // Two-step chain: per-title seed fetch, then a seeded route query returning an encrypted payload.
    private fun checkVideasy(id: String): List<ProbeResult> {
        val out = mutableListOf<ProbeResult>()
        val seedUrl = ServerFarm.buildMovieUrl(ServerFarm.allServers.first { it.id == id }, tmdbMovieId)
        val s = fetch(seedUrl, videasyHeaders())
        val seed = Regex(""""seed"\s*:\s*"([^"]+)"""").find(s.body)?.groupValues?.get(1)
        out += ProbeResult(id, "seed", s.code == 200 && !seed.isNullOrBlank(),
            "HTTP ${s.code} seed=${seed?.take(20) ?: "MISSING"} ${s.error ?: ""}".trim())
        if (!seed.isNullOrBlank()) {
            val q = "https://api.speedracelight.com/cdn/sources-with-title?title=Inception" +
                "&mediaType=Movie&tmdbId=$tmdbMovieId&year=2010&enc=2&seed=" +
                java.net.URLEncoder.encode(seed, "UTF-8")
            val r = fetch(q, videasyHeaders())
            out += ProbeResult(id, "route:cdn", r.code == 200 && r.body.length > 100,
                "HTTP ${r.code} len=${r.body.length} ${r.error ?: ""}".trim())
        }
        return out
    }

    private fun videasyHeaders(): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Origin" to "https://player.videasy.net",
        "Referer" to "https://player.videasy.net/",
    )
}
