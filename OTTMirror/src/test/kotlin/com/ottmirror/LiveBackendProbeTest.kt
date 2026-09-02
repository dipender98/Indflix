package com.ottmirror

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LIVE end-to-end probe of the multi-audio delivery fix (dual emission +
 * always-extract-audio + audio pre-flight).
 *
 * Tests 2 series + 2 movies on Netflix OTT. Runs the exact same flow the
 * plugin uses: verify → search → post → embed-tmdb → NewTV player →
 * master → audio extraction → audio pre-flight → variant liveness.
 *
 * Run:
 *   ./gradlew :OTTMirror:testDebugUnitTest --tests "*LiveBackendProbe*" -Dottmirror.live=true
 *
 * Prints a report per title: embed coverage, audio groups, pre-flight
 * results, variantAlive, and the shouldEmitNewTvMaster decision.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveBackendProbeTest {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val lastRequest = AtomicLong(0L)

    private fun gate() {
        var wait = 1200L - (System.currentTimeMillis() - lastRequest.get())
        while (wait > 0) {
            Thread.sleep(wait.coerceAtMost(100L))
            wait = 1200L - (System.currentTimeMillis() - lastRequest.get())
        }
        lastRequest.set(System.currentTimeMillis())
    }

    private fun get(url: String, headers: Map<String, String>): Triple<Int, String, String> {
        gate()
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        val resp = client.newCall(req).execute()
        val code = resp.code
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        val setCookie = resp.headers.values("Set-Cookie").firstOrNull() ?: ""
        resp.close()
        return Triple(code, body, setCookie)
    }

    private fun post(url: String, headers: Map<String, String>, form: String): Triple<Int, String, String> {
        gate()
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val resp = client.newCall(req).execute()
        val code = resp.code
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        val setCookie = resp.headers.values("Set-Cookie").firstOrNull() ?: ""
        resp.close()
        return Triple(code, body, setCookie)
    }

    private fun section(title: String) {
        log("\n===== $title =====")
    }

    private val logFile = java.io.File("E:/Project/Indflix/OTTMirror/build/ottmirror_multi_audio_probe.log")

    private fun log(line: String) {
        println(line)
        try { logFile.appendText(line + "\n") } catch (_: Exception) {}
    }

    private lateinit var tHashT: String
    private val cookieBase: String get() = "t_hash_t=$tHashT; ott=nf; hd=on"

    private fun mobileHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to MOBILE_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
    )

    private fun newtvHeaders(referer: String): Map<String, String> = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "User-Agent" to MOBILE_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
        "Cookie" to "hd=on",
    )

    @BeforeAll
    fun setUp() {
        logFile.delete()
        section("LIVE MULTI-AUDIO PROBE (Sep 2026)")
        log("Machine IP: ${runCatching { java.net.InetAddress.getLocalHost().hostAddress }.getOrDefault("unknown")}")
        log("Timezone: ${java.util.TimeZone.getDefault().displayName}")

        // Verify once; shared across all title probes.
        section("1. verify.php (obtain t_hash_t)")
        val (vCode, vBody, vSetCookie) = post(
            "https://net52.cc/verify.php",
            mapOf(
                "Origin" to "https://net22.cc",
                "Referer" to "https://net22.cc/verify2",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to DESKTOP_VERIFY_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
                "sec-ch-ua" to "\"Chromium\";v=\"147\", \"Not(A:Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1",
            ),
            "g-recaptcha-response=${UUID.randomUUID()}",
        )
        tHashT = vSetCookie.substringAfter("t_hash_t=").substringBefore(";").trim()
        log("verify HTTP $vCode  t_hash_t=${tHashT.take(24)}...  body=${vBody.take(120)}")
        assertTrue(tHashT.isNotBlank(), "verify.php must hand out t_hash_t")

        // Verify the cookie works: search for a known title.
        section("1b. verify cookie works (search 'Breaking Bad')")
        val (sCode, sBody, _) = get(
            "https://net52.cc/mobile${OTT_NETFLIX.mobilePrefix}/search.php?s=Breaking+Bad&t=${System.currentTimeMillis() / 1000}",
            mobileHeaders("https://net52.cc/home") + mapOf("Cookie" to cookieBase),
        )
        log("search HTTP $sCode  len=${sBody.length}  limited=${NetMirrorGuard.isLimitedBody(sBody)}")
        val hits = NetMirrorParsers.parseSearch(sBody)
        log("hits=${hits.size}  first=${hits.firstOrNull()?.title} id=${hits.firstOrNull()?.id}")
        assertTrue(hits.isNotEmpty(), "search must return results for 'Breaking Bad'")
    }

    // Title data: (query, label, isSeries, expectedTmdbId)
    private data class Title(val query: String, val label: String, val isSeries: Boolean, val expectedTmdb: String? = null)

    private val OTT_NETFLIX = OttService.NETFLIX

    private val titles = listOf(
        Title("Breaking Bad", "Breaking Bad (Netflix, series)", true, "1396"),
        Title("Game of Thrones", "Game of Thrones (Netflix, series)", true, "1399"),
        Title("The Batman", "The Batman (Netflix, movie)", false, "414906"),
        Title("Interstellar", "Interstellar (Netflix, movie)", false, "157336"),
    )

    @Test
    fun probeMultiAudioFlow() = runBlocking {
        assumeTrue(System.getProperty("ottmirror.live") == "true",
            "Skipping live probe (set -Dottmirror.live=true)")

        withContext(Dispatchers.IO) {
            val apiBase = resolveNewTvBase() ?: run {
                log("CRITICAL: no NewTV base resolved. Aborting.")
                return@withContext
            }
            log("NewTV apiBase=$apiBase")

            for (title in titles) {
                section("=== ${title.label} ===")
                probeTitle(title, apiBase)
            }

            log("\n===== PROBE COMPLETE =====")
        }
    }

    private suspend fun resolveNewTvBase(): String? {
        for (encoded in NEWTV_DOMAINS) {
            val host = Base64Decode.decodeUtf8(encoded)?.trimEnd('/') ?: continue
            val (c, b, _) = get("$host/checknewtv.php", NEWTV_HEADERS)
            val token = NetMirrorParsers.parseNewTvToken(b)?.tokenHash
            val decoded = token?.let { Base64Decode.decodeUtf8(it)?.trimEnd('/') }
            log("$host -> HTTP $c token=${token?.take(20)}... decoded=$decoded")
            if (decoded != null && decoded.startsWith("http")) return decoded
        }
        return null
    }

    private suspend fun probeTitle(title: Title, apiBase: String) {
        val mobilePrefix = OTT_NETFLIX.mobilePrefix
        // 1. Search
        val encoded = java.net.URLEncoder.encode(title.query, "UTF-8")
        val (sCode, sBody, _) = get(
            "https://net52.cc/mobile$mobilePrefix/search.php?s=$encoded&t=${System.currentTimeMillis() / 1000}",
            mobileHeaders("https://net52.cc/home") + mapOf("Cookie" to cookieBase),
        )
        log("search HTTP $sCode  len=${sBody.length}  limited=${NetMirrorGuard.isLimitedBody(sBody)}")
        if (NetMirrorGuard.isLimitedBody(sBody)) { log("RATE-LIMITED — skipping"); return }
        val hits = NetMirrorParsers.parseSearch(sBody)
        val hit = hits.firstOrNull()
        if (hit == null) { log("No search hit"); return }
        log("hit: id=${hit.id} title=${hit.title} type=${hit.type}")

        // 2. Post
        val (pCode, pBody, _) = get(
            "https://net52.cc/mobile$mobilePrefix/post.php?id=${hit.id}&t=${System.currentTimeMillis() / 1000}",
            mobileHeaders("https://net52.cc/home") + mapOf("Cookie" to cookieBase),
        )
        log("post HTTP $pCode  len=${pBody.length}  limited=${NetMirrorGuard.isLimitedBody(pBody)}")
        if (NetMirrorGuard.isLimitedBody(pBody)) { log("RATE-LIMITED — skipping"); return }
        val post = NetMirrorParsers.parsePost(pBody)
        if (post == null) { log("post parse failed: ${pBody.take(200)}"); return }
        log("post: title=${post.title} tmdb=${post.tmdbId} imdb=${post.imdbId} type=${post.type} " +
                "seasons=${post.seasons.size} episodes=${post.episodes.size} " +
                "firstEp=${post.episodes.firstOrNull()?.id} " +
                "firstSeason=${post.seasons.firstOrNull()?.id}")
        val tmdbId = post.tmdbId
        if (title.expectedTmdb != null) {
            val match = tmdbId == title.expectedTmdb
            log("TMDB match: $match (expected=${title.expectedTmdb}, got=$tmdbId)")
        } else {
            log("tmdbId=$tmdbId")
        }

        // 3. Embed-tmdb coverage
        val playbackId = if (title.isSeries && post.episodes.isNotEmpty()) {
            post.episodes.first().id
        } else {
            hit.id
        }
        val firstEp = if (title.isSeries) post.episodes.firstOrNull() else null
        val embedUrl = if (tmdbId != null) {
            if (title.isSeries && firstEp != null) {
                "https://net27.cc/api/embed-tmdb/$tmdbId?type=tv&s=${firstEp.season ?: 1}&e=${firstEp.episode ?: 1}"
            } else {
                "https://net27.cc/api/embed-tmdb/$tmdbId?type=movie"
            }
        } else null
        if (embedUrl != null) {
            val (eCode, eBody, _) = get(embedUrl, mapOf(
                "User-Agent" to MOBILE_UA,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to EMBED_REFERER,
            ))
            log("embed HTTP $eCode")
            val embed = NetMirrorParsers.parseEmbedTmdb(eBody)
            if (embed != null) {
                val best = embed.streams.filter { it.resolution in 1..1080 }
                    .maxByOrNull { it.resolution }
                log("embed: noSource=${embed.noSource} streams=${embed.streams.size} " +
                        "best=${best?.resolution}p captions=${embed.captions.size}")
                if (best != null) log("  best URL: ${best.url.take(100)}")
            } else {
                log("embed: parse failed (noSource / rate-limited)")
            }
        } else {
            log("embed: no tmdbId — skipped")
        }

        // 4. NewTV player.php
        val (plCode, plBody, _) = get(
            "$apiBase/newtv/player.php?id=$playbackId",
            NEWTV_HEADERS + mapOf("Ott" to "nf", "Usertoken" to ""),
        )
        log("player.php HTTP $plCode")
        val player = NetMirrorParsers.parseNewTvPlayer(plBody)
        val vlink = player?.videoLink?.takeIf { it.isNotBlank() }
        val status = player?.status
        log("player: status=$status vlink=${vlink?.take(100)}")
        if (vlink == null) { log("No vlink — NewTV not available for this title"); return }

        // 5. Master fetch + audio extraction
        val (mCode, mBody, _) = get(
            vlink,
            newtvHeaders(player?.referer ?: apiBase),
        )
        log("master HTTP $mCode  len=${mBody.length}  playable=${NetMirrorParsers.newTvMasterPlayable(mBody)}")
        if (!mBody.startsWith("#EXTM3U")) { log("Not a valid playlist — aborting"); return }

        val audioTracks = NetMirrorParsers.parseMasterAudioTracks(mBody)
        log("audio tracks parsed: ${audioTracks.size}")
        audioTracks.forEachIndexed { i, (lang, name, uri) ->
            log("  [$i] lang=$lang name=$name uri=${uri.take(100)}")
        }

        // 6. Variant liveness (pre-flight default variant) — DIAGNOSTIC ONLY:
        // the plugin no longer gates on this (the CDN validates per client
        // context; a 404 from this probe does not mean the device will 404).
        val defaultVariantUrl = NetMirrorParsers.pickSingleVariant(vlink, mBody)
        log("default variant URL: ${defaultVariantUrl?.take(100)}")
        val variantAlive = if (defaultVariantUrl != null) {
            val (vCode, vBody, _) = get(defaultVariantUrl, newtvHeaders(player?.referer ?: apiBase))
            val alive = vCode in 200..299 && vBody.startsWith("#EXTM3U")
            log("variant liveness: HTTP $vCode alive=$alive")
            alive
        } else {
            log("variant liveness: no default variant URL")
            false
        }

        // 7. Audio pre-flight (GET each audio playlist) — diagnostic only.
        val audioPreFlight = audioTracks.map { (lang, name, uri) ->
            val (aCode, aBody, _) = get(uri, newtvHeaders(player?.referer ?: apiBase))
            val alive = aCode in 200..299 && aBody.startsWith("#EXTM3U")
            Triple(lang, name, alive)
        }
        val liveAudioCount = audioPreFlight.count { it.third }
        audioPreFlight.forEach { (lang, name, alive) ->
            log("  audio pre-flight: lang=$lang name=$name alive=$alive")
        }

        // 8. Decision — the plugin emits the master verbatim when the
        // structural gate passes (variants + host-bearing audio); the player
        // fetches everything in its own context.
        val playable = NetMirrorParsers.newTvMasterPlayable(mBody)
        log("=== DECISION ===")
        log("audioTracks.declared=${audioTracks.size}  audioTracks.live(from this context)=$liveAudioCount  variantAlive=$variantAlive")
        log("newTvMasterPlayable=$playable")
        if (playable) {
            log(">> Master link emitted verbatim (in=unknown ships through; the")
            log(">> player fetches master -> variants -> audio in its own context)")
            log(">> Audio picker: native from the master's EXT-X-MEDIA groups (${audioTracks.size} tracks)")
        } else {
            log(">> Broken stub — no master link; title falls back to embed or 'No link found'")
        }
        log("")
    }
}