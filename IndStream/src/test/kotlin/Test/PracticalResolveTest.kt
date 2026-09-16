package Test

import com.indstream.CastleTvSource
import com.indstream.HttpKit
import com.indstream.StreamEngine
import com.indstream.TitleMatch
import com.indstream.TmdbService
import com.indstream.VideasySource
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Practical chain checks, sequential and deterministic: TMDB reliability, then each fixed server's real pipeline. Manual run only. */
class PracticalResolveTest {

    @Test
    fun tmdb_fetchMetaReliable() = runBlocking {
        // Title-keyed servers (moviebox/castletv/videasy) all depend on this lookup.
        repeat(3) { i ->
            val t = System.currentTimeMillis()
            val meta = TmdbService.fetchMeta(27205, "movie")
            println("TMDB attempt ${i + 1}: ${meta?.name} ${meta?.year} ${meta?.imdbId} ${System.currentTimeMillis() - t}ms")
        }
        val meta = TmdbService.fetchMeta(27205, "movie")
        assertNotNull(meta, "TMDB must answer")
        assertTrue(meta.name?.contains("Inception") == true, "title must match")
    }

    @Test
    fun castletv_fullChain() = runBlocking {
        val sec = CastleTvSource.securityKey()
        assertNotNull(sec, "seckey must issue")
        val rows = CastleTvSource.search(sec, "Inception 2010")
        println("CastleTV search rows: ${rows.size} ${rows.take(3)}")
        assertTrue(rows.isNotEmpty(), "search must hit")
        val best = rows.maxByOrNull { TitleMatch.titleDistance("Inception", it.second) }
        assertNotNull(best, "title match")
        val det = CastleTvSource.details(sec, best.first)
        assertNotNull(det, "details must load")
        val eps = det.optJSONArray("episodes")
        assertNotNull(eps, "episodes array")
        assertTrue(eps.length() > 0, "episode entries")
        val ep = eps.optJSONObject(0)
        val epId = ep.opt("id")?.toString()
        assertNotNull(epId, "episode id")
        val tracksJson = ep.optJSONArray("tracks")
        val tracks = (0 until (tracksJson?.length() ?: 0)).mapNotNull { i ->
            val t = tracksJson!!.optJSONObject(i) ?: return@mapNotNull null
            CastleTvSource.Track(t.optString("languageId"), t.optString("languageName"), t.optBoolean("existIndividualVideo"))
        }
        val pick = CastleTvSource.pickTrack(tracks)
        println("CastleTV tracks: ${tracks.map { it.languageName }} pick=${pick?.languageName}")
        val data = CastleTvSource.video(sec, best.first, epId, pick?.languageId?.takeIf { it.isNotBlank() }, 3)
        assertNotNull(data, "video must resolve")
        val vids = CastleTvSource.videosOf(data, 3)
        println("CastleTV videos: ${vids.map { it.quality to it.url.take(80) }}")
        assertTrue(vids.isNotEmpty(), "video urls")
        assertTrue(HttpKit.aliveCheck(vids.first().url) != false, "first video must be playable")
    }

    @Test
    fun videasy_fullChain() = runBlocking {
        val r = VideasySource.fetchAllSources(
            tmdbId = 27205, imdbId = "tt1375666", title = "Inception", year = 2010,
            mediaType = "movie", season = -1, episode = -1,
        )
        println("Videasy routes: ${r.sources.groupBy { it.route }.mapValues { it.value.size }}")
        assertTrue(r.httpOk, "routes must answer")
        assertTrue(r.sources.isNotEmpty(), "sources must decrypt")
        // Playback carries no Origin/Referer (CDN 403s the player Origin, mirror 403s the player Referer).
        val live = r.sources.map { it to (HttpKit.aliveCheck(it.url, null) != false) }
        live.forEach { (s, ok) -> println("  ${s.route} ${s.quality} alive=$ok ${s.url.take(70)}") }
        assertTrue(live.any { it.second }, "at least one source must be playable")
    }

    @Test
    fun moviebox_tokenAndSearch() = runBlocking {
        val token = StreamEngine.prewarmMovieBoxToken()
        assertNotNull(token, "bearer must issue")
        val base = "https://h5-api.aoneroom.com"
        val headers = mapOf(
            "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
            "Accept-Language" to "en-US,en;q=0.5",
            "Accept" to "application/json",
            "Referer" to base,
            "Host" to "h5-api.aoneroom.com",
            "Connection" to "keep-alive",
            "Authorization" to "Bearer $token",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        )
        val resp = app.post("$base/wefeed-h5api-bff/subject/search", timeout = 15, headers = headers,
            json = mapOf("keyword" to "Inception", "page" to 1, "perPage" to 24, "subjectType" to 1))
        println("MovieBox search: HTTP ${resp.code} ${resp.text.take(150)}")
        assertTrue(resp.code in 200..299, "search must be accepted")
        val root = org.json.JSONObject(resp.text)
        val data = root.optJSONObject("data")?.let { it.optJSONObject("data") ?: it } ?: root
        val items = data.optJSONArray("items")
        assertNotNull(items, "items array")
        println("MovieBox items: ${items.length()}")
        assertTrue(items.length() > 0, "search must hit")
    }

    @Test
    fun moviebox_fullChain() = runBlocking {
        // Real single-server resolve: token + search + detail + play/download.
        val spec = com.indstream.ServerFarm.allServers.first { it.id == "moviebox" }
        val out = StreamEngine.resolveOne(spec, 27205, "tt1375666", "movie", 1, 1, null)
        println("MovieBox streams: ${out.size} ${out.map { it.qualityHint to it.url.take(70) }}")
        assertTrue(out.isNotEmpty(), "moviebox must resolve streams")
        assertTrue(out.all { it.url.startsWith("http") && it.qualityHint > 0 }, "streams carry urls + heights")
        // Liveness is best-effort (CDN 429s test IPs); logged, not asserted.
        val live = out.map { it to (HttpKit.aliveCheck(it.url, it.referer, it.extraHeaders) != false) }
        live.forEach { (s, ok) -> println("  ${s.qualityHint}p alive=$ok ${s.url.take(70)}") }
    }

    @Test
    fun farmDiagnose_missingServers() = runBlocking {
        // Per-server resolve with exception capture: WHY do servers land empty?
        val ids = listOf("vidrock", "vidnest", "vidcore",
            "twoembed", "autoembed", "vidphantom", "vsembed", "twoembed-skin",
            "vidsrcme", "vidsrc-pm", "rive", "onetouchtv")
        for (id in ids) {
            val spec = com.indstream.ServerFarm.allServers.first { it.id == id }
            val t0 = System.currentTimeMillis()
            try {
                val out = kotlinx.coroutines.withTimeoutOrNull(spec.timeoutSec * 1000L) {
                    StreamEngine.resolveOne(spec, 27205, "tt1375666", "movie", 1, 1, null)
                }
                println("DIAG $id: ${out?.size ?: "TIMEOUT"} streams in ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                println("DIAG $id: ${e.javaClass.simpleName}: ${e.message?.take(150)} in ${System.currentTimeMillis() - t0}ms")
            }
        }
    }

    @Test
    fun farmTiming_allServers() = runBlocking {
        // Full-farm timing: which servers land streams and how fast (diagnoses UI no-shows).
        val t0 = System.currentTimeMillis()
        val rows = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Int, Long>>())
        StreamEngine.resolveRealtime(27205, "movie", -1, -1, imdbIdProvider = { "tt1375666" }) { sid, streams ->
            rows += Triple(sid, streams.size, System.currentTimeMillis() - t0)
        }
        val total = System.currentTimeMillis() - t0
        println("FARM TIMING total=${total}ms servers=${rows.size}")
        for ((sid, n, ms) in rows.sortedBy { it.third }) println("  +${ms}ms $sid x$n")
        val landed = rows.map { it.first }.toSet()
        val missing = com.indstream.ServerFarm.allServers.map { it.id }.toSet() - landed
        println("MISSING: ${missing.joinToString()}")
    }

    @Test
    fun onetouchtv_fullChain() = runBlocking {
        // Dune Part Two (TMDB 693134) carries OneTouchTV; Inception does not (library gap, not a bug).
        val spec = com.indstream.ServerFarm.allServers.first { it.id == "onetouchtv" }
        val out = StreamEngine.resolveOne(spec, 693134, "tt15239678", "movie", 1, 1, null)
        println("OneTouchTV streams: ${out.size} ${out.map { it.qualityHint to it.url.take(70) }}")
        assertTrue(out.isNotEmpty(), "onetouchtv must resolve streams")
        val live = out.map { it to (HttpKit.aliveCheck(it.url, it.referer, it.extraHeaders) != false) }
        live.forEach { (s, ok) -> println("  ${s.qualityHint}p alive=$ok ${s.url.take(70)}") }
        assertTrue(live.any { it.second }, "at least one onetouchtv stream must be playable")
    }

    @Test
    fun vidnest_movieboxAdDropped() {
        // Synthetic ad payload (one static file under every label) must parse to zero streams.
        val spec = com.indstream.ServerFarm.allServers.first { it.id == "vidnest" }
        val m = StreamEngine::class.java.getDeclaredMethod(
            "parseVidnestSub", String::class.java, org.json.JSONObject::class.java,
            com.indstream.ServerSpec::class.java,
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        fun parse(sub: String, json: String) =
            m.invoke(StreamEngine, sub, org.json.JSONObject(json), spec) as List<com.indstream.StreamEngine.RawStream>
        val ad = """{"url":[
            {"lang":"English","link":"https://x.example/ad.mp4","resolution":"360p","type":"mp4"},
            {"lang":"English","link":"https://x.example/ad.mp4","resolution":"480p","type":"mp4"},
            {"lang":"English","link":"https://x.example/ad.mp4","resolution":"1080p","type":"mp4"}]}"""
        assertTrue(parse("moviebox", ad).isEmpty(), "single-file ad must drop")
        val real = """{"url":[
            {"lang":"English","link":"https://x.example/a360.mp4","resolution":"360p","type":"mp4"},
            {"lang":"English","link":"https://x.example/a480.mp4","resolution":"480p","type":"mp4"}]}"""
        assertEquals(2, parse("moviebox", real).size, "distinct files must pass")
    }

    @Test
    fun vidnest_livePayloadDecodes() = runBlocking {
        val headers = mapOf(
            "Referer" to "https://vidnest.fun/",
            "Origin" to "https://vidnest.fun",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        val raw = HttpKit.get("https://new.vidnest.fun/moviebox/movie/27205", timeout = 12)
            ?: throw AssertionError("sub-server must answer")
        assertTrue(raw.contains("\"data\""), "encrypted envelope")
        // Real decode path via reflection (kept private in main); catches upstream alphabet changes against a live payload.
        val m = StreamEngine::class.java.getDeclaredMethod("decodeVidnestPayload", String::class.java)
        m.isAccessible = true
        val payload = org.json.JSONObject(raw).optString("data")
        val json = m.invoke(StreamEngine, payload) as String?
        assertNotNull(json, "payload must decode")
        val urls = org.json.JSONObject(json).optJSONArray("url")
        assertNotNull(urls, "moviebox url array")
        println("VidNest moviebox entries: ${urls.length()} first=${urls.optJSONObject(0)?.optString("link")?.take(80)}")
        assertTrue(urls.length() > 0, "entries present")
    }
}
