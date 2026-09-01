package com.ottmirror

import kotlin.test.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LIVE end-to-end probe of the exact playback flow against the real backend.
 *
 * Run explicitly (network + ~15 requests):
 *   ./gradlew :OTTMirror:testDebugUnitTest --tests "*LiveBackendProbe*" -Dottmirror.live=true
 *
 * Prints a report of every hop the plugin makes at playback time:
 *   verify.php -> home -> post.php -> checknewtv.php -> player.php -> master
 *   + embed-tmdb (movie + tv). This is the diagnostic for "no link found".
 */
class LiveBackendProbeTest {

    private val client = okhttp3.OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var lastRequest = 0L

    private fun gate() {
        val wait = 1200L - (System.currentTimeMillis() - lastRequest)
        if (wait > 0) Thread.sleep(wait)
        lastRequest = System.currentTimeMillis()
    }

    private fun get(url: String, headers: Map<String, String>): Triple<Int, String, okhttp3.Headers> {
        gate()
        val req = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            return Triple(resp.code, body, resp.headers)
        }
    }

    private fun post(url: String, headers: Map<String, String>, form: String): Triple<Int, String, okhttp3.Headers> {
        gate()
        val req = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        client.newCall(req).execute().use { resp ->
            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
            return Triple(resp.code, body, resp.headers)
        }
    }

    private fun header(headers: okhttp3.Headers, name: String): String =
        headers.values(name).joinToString(" | ")

    private fun section(title: String) {
        log("\n===== $title =====")
    }

    private val logFile = java.io.File("E:/Project/Indflix/OTTMirror/build/ottmirror_probe.log")

    private fun log(line: String) {
        println(line)
        try {
            logFile.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    @Test
    fun probePlaybackFlow() {
        try { logFile.writeText("") } catch (_: Exception) {}
        log("===== LIVE BACKEND PROBE (Aug 2026) =====")

        // ---------------------------------------------------------------
        // 1. verify.php (CNCVerse-exact)
        // ---------------------------------------------------------------
        section("1. verify.php POST net52.cc (CNCVerse-exact headers)")
        val verifyHeaders = mapOf(
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
        )
        val (vCode, vBody, vResp) = post(
            "https://net52.cc/verify.php",
            verifyHeaders,
            "g-recaptcha-response=${java.util.UUID.randomUUID()}",
        )
        val tHashT = header(vResp, "Set-Cookie").substringAfter("t_hash_t=").substringBefore(";")
        log("HTTP $vCode  Set-Cookie(t_hash_t)=${tHashT.take(24)}...  body=${vBody.take(120)}")
        org.junit.jupiter.api.Assertions.assertTrue(tHashT.isNotBlank(), "verify.php must hand out t_hash_t")

        val cookie = "t_hash_t=$tHashT; ott=nf; hd=on"

        // ---------------------------------------------------------------
        // 2. home (mobile, full WebView headers)
        // ---------------------------------------------------------------
        section("2. GET /mobile/home?app=1 (mobile WebView profile)")
        val (hCode, hBody, hResp) = get(
            "https://net52.cc/mobile/home?app=1",
            mobileProbeHeaders("https://net52.cc/home") + mapOf("Cookie" to cookie),
        )
        log("HTTP $hCode  len=${hBody.length}")
        val limitedHere = NetMirrorGuard.isLimitedBody(hBody)
        log("limited-body=${limitedHere}  firstChars=${hBody.take(160).replace('\n', ' ')}")
        if (limitedHere) {
            log(">>> HOME IS RATE-LIMITED â€” this is the 'Too many request' source (IP bucket shared).")
            return
        }
        val doc = org.jsoup.Jsoup.parse(hBody)
        val rows = NetMirrorParsers.parseHomeRows(doc)
        log("home rows=${rows.size}  ids=${rows.take(3).flatMap { it.second }.take(6)}")
        org.junit.jupiter.api.Assertions.assertTrue(rows.isNotEmpty(), "home must return rows")
        val firstId = rows.firstNotNullOfOrNull { it.second.firstOrNull() }
            ?: run { log("no home ids"); return }

        // ---------------------------------------------------------------
        // 3. post.php for the first home title (movie or series)
        // ---------------------------------------------------------------
        section("3. GET /mobile/post.php?id=$firstId")
        val (pCode, pBody, _) = get(
            "https://net52.cc/mobile/post.php?id=$firstId&t=${System.currentTimeMillis() / 1000}",
            mobileProbeHeaders("https://net52.cc/home") + mapOf("Cookie" to cookie),
        )
        log("HTTP $pCode  len=${pBody.length}  firstChars=${pBody.take(160).replace('\n', ' ')}")
        if (NetMirrorGuard.isLimitedBody(pBody)) { log(">>> POST RATE-LIMITED"); return }
        val post = NetMirrorParsers.parsePost(pBody)
        if (post == null) { log("post parse failed — body: ${pBody.take(200)}"); return }
        val tmdbId = post.tmdbId
        log("post title=${post.title} tmdb_id=${tmdbId} imdb_id=${post.imdbId} type=${post.type} episodes=${post.episodes.size} seasons=${post.seasons.size}")
        val firstEpId = post.episodes.firstOrNull()?.id
        log("first episode id=$firstEpId  ep=${post.episodes.firstOrNull()?.episode} s=${post.episodes.firstOrNull()?.season}")
        val playbackId = firstEpId ?: firstId
        log(">>> using playbackId=$playbackId (episode id when series, else content id)")

        // ---------------------------------------------------------------
        // 3b. TMDB title search fallback (when post has no tmdb_id)
        // ---------------------------------------------------------------
        if (tmdbId == null) {
            section("3b. TMDB title search for '${post.title}' (embed key fallback)")
            val query = java.net.URLEncoder.encode(post.title, "UTF-8")
            val (tsCode, tsBody, _) = get(
                "https://api.themoviedb.org/3/search/multi?api_key=e6333b32409e02a4a6eba6fb7ff866bb&query=$query&language=en-US",
                mapOf("Accept" to "application/json"),
            )
            log("HTTP $tsCode  body=${tsBody.take(200)}")
            val searchTmdb = runCatching {
                val arr = org.json.JSONObject(tsBody).optJSONArray("results") ?: org.json.JSONArray()
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    val m = arr.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                    if (m.optString("title").equals(post.title, true) || m.optString("name").equals(post.title, true))
                        m.optInt("id") to m.optString("media_type")
                    else null
                }
            }.getOrNull()
            log("TMDB search hit: id=${searchTmdb?.first} type=${searchTmdb?.second}")
        }

        // ---------------------------------------------------------------
        // 3c. native play.php + playlist.php (the flow I removed — does it work?)
        //     Test on ALL mobile hosts since play.php may not live on net52.cc.
        // ---------------------------------------------------------------
        for (probeHost in listOf("https://net52.cc", "https://net77.cc", "https://net22.cc", "https://net27.cc", "https://netmirror.gg")) {
            section("3c. native play.php POST on $probeHost (id=$playbackId)")
            val (playCode, playBody, _) = post(
                "$probeHost/play.php",
                mobileProbeHeaders("$probeHost/home") + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to probeHost,
                    "Cookie" to cookie,
                ),
                "id=$playbackId",
            )
            log("HTTP $playCode  body=${playBody.take(120)}")
            if (NetMirrorGuard.isLimitedBody(playBody)) { log("    RATE-LIMITED"); continue }
            val hToken = runCatching { org.json.JSONObject(playBody).optString("h").takeIf { it.isNotBlank() } }.getOrNull()
            log("    h-token=${hToken?.take(12)}...")
            if (hToken != null) {
                // The h token IS an "in=" resource key. Test it on the NewTV
                // master URL — this may be the fix for the dead template!
                val masterWithKey = "https://tv.imgcdn.kim/newtv/hls/nf/$playbackId.m3u8?in=$hToken"
                section("3c2. NewTV master WITH the play.php in-key")
                val (mkCode, mkBody, _) = get(
                    masterWithKey,
                    NEWTV_HEADERS + mapOf("Referer" to "$probeHost/home", "Cookie" to "hd=on"),
                )
                log("HTTP $mkCode  len=${mkBody.length}  isDead=${NetMirrorParsers.newTvMasterIsDead(mkBody)}")
                log("=== FULL MASTER ===")
                log(mkBody.lines().joinToString("\n"))
                if (!NetMirrorParsers.newTvMasterIsDead(mkBody)) log(">>> MASTER ALIVE WITH IN-KEY — the fix!!")

                val playlistUrl = "$probeHost/mobile/playlist.php?id=$playbackId&t=${java.net.URLEncoder.encode(post.title, "UTF-8")}&tm=${System.currentTimeMillis() / 1000}&h=${java.net.URLEncoder.encode(hToken, "UTF-8")}"
                val (plUrlCode, plUrlBody, _) = get(
                    playlistUrl,
                    mobileProbeHeaders("$probeHost/home") + mapOf("Cookie" to cookie),
                )
                log("    playlist HTTP $plUrlCode  len=${plUrlBody.length}  body=${plUrlBody.take(200)}")
                val pl = NetMirrorParsers.parsePlaylist(plUrlBody)
                if (pl != null) {
                    val sources = pl.sources.orEmpty().filter { it.file.isNotBlank() }
                    log("    sources=${sources.size}  first=${sources.firstOrNull()?.file?.take(120)}")
                    if (sources.isNotEmpty()) log(">>> NATIVE OK on $probeHost — first=${sources.first().file.take(140)}")
                } else {
                    log("    playlist parse failed: ${plUrlBody.take(200)}")
                }
                break
            } else {
                log("    play.php ${playCode}: no h-token (${playBody.take(80)})")
            }
        }

        // ---------------------------------------------------------------
        // 4. NewTV resolution (checknewtv.php â€” first 4 domains)
        // ---------------------------------------------------------------
        section("4. resolveNewTvBase (checknewtv.php walk, first 4 domains)")
        var apiBase: String? = null
        for (encoded in NEWTV_DOMAINS.take(4)) {
            val host = Base64Decode.decodeUtf8(encoded)?.trimEnd('/') ?: continue
            val (c, b, _) = get("$host/checknewtv.php", NEWTV_HEADERS)
            val token = NetMirrorParsers.parseNewTvToken(b)?.tokenHash
            val decoded = token?.let { Base64Decode.decodeUtf8(it)?.trimEnd('/') }
            log("$host -> HTTP $c token=${token?.take(20)}... decoded=$decoded")
            if (decoded != null && decoded.startsWith("http")) { apiBase = decoded; break }
        }
        if (apiBase == null) { log(">>> NO NewTV base resolved (all probed domains failed)"); return }
        log("apiBase=$apiBase")

        // ---------------------------------------------------------------
        // 5. player.php — the make-or-break hop
        // ---------------------------------------------------------------
        section("5. GET $apiBase/newtv/player.php?id=$playbackId (Ott+Usertoken, no cookie)")
        val (plCode, plBody, _) = get(
            "$apiBase/newtv/player.php?id=$firstId",
            NEWTV_HEADERS + mapOf("Ott" to "nf", "Usertoken" to ""),
        )
        log("HTTP $plCode  body=${plBody.take(300)}")
        if (NetMirrorGuard.isLimitedBody(plBody)) { log(">>> PLAYER RATE-LIMITED"); return }
        val player = NetMirrorParsers.parseNewTvPlayer(plBody)
        val vlink = player?.videoLink?.takeIf { it.isNotBlank() }
        log("status=${player?.status}  videoLink=${vlink}")

        // 5b. Retry player.php WITH the session cookie (CNCVerse gets it via
        // the shared cookie jar) — does status change from "otp" to "ok"?
        section("5b. player.php WITH t_hash_t cookie")
        val (pl2Code, pl2Body, _) = get(
            "$apiBase/newtv/player.php?id=$playbackId",
            NEWTV_HEADERS + mapOf(
                "Ott" to "nf",
                "Usertoken" to "",
                "Cookie" to "t_hash_t=$tHashT; ott=nf; hd=on",
            ),
        )
        log("HTTP $pl2Code  body=${pl2Body.take(300)}")
        val player2 = NetMirrorParsers.parseNewTvPlayer(pl2Body)
        val vlink2 = player2?.videoLink?.takeIf { it.isNotBlank() }
        log("with-cookie status=${player2?.status}  videoLink=${vlink2}")

        // 5c. Whatever status we got, fetch the master with hd=on and see
        // if it is a REAL playlist or the dead in=unknown template.
        val masterToTest = vlink2 ?: vlink
        if (masterToTest != null) {
            section("5c. master m3u8 (hd=on cookie) — is it alive?")
            val (mCode, mBody, _) = get(
                masterToTest,
                NEWTV_HEADERS + mapOf("Referer" to (player2?.referer ?: player?.referer ?: apiBase), "Cookie" to "hd=on"),
            )
            log("HTTP $mCode  len=${mBody.length}  isDead=${NetMirrorParsers.newTvMasterIsDead(mBody)}")
            log(mBody.lines().take(8).joinToString("\n"))
            if (!NetMirrorParsers.newTvMasterIsDead(mBody)) log(">>> NewTV master looks ALIVE")
            else log(">>> NewTV master is DEAD template (in=unknown) — NewTV unplayable for this title")
        }

        // ---------------------------------------------------------------
        // 6. embed-tmdb â€” the primary path
        // ---------------------------------------------------------------
        if (tmdbId != null) {
            section("6. embed-tmdb movie (tmdb=$tmdbId)")
            val (eCode, eBody, _) = get(
                "https://net27.cc/api/embed-tmdb/$tmdbId?type=movie",
                mapOf(
                    "User-Agent" to MOBILE_UA,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to EMBED_REFERER,
                ),
            )
            log("HTTP $eCode  body=${eBody.take(200)}")
            val embed = NetMirrorParsers.parseEmbedTmdb(eBody)
            if (embed != null) {
                val best = NetMirrorParsers.pickEmbedStream(embed.streams)
                log("noSource=${embed.noSource} streams=${embed.streams.size} best=${best?.resolution}p captions=${embed.captions.size}")
                if (best != null) log(">>> EMBED OK â€” url=${best.url.take(120)}")
            } else {
                log(">>> embed-tmdb parse failed (may be rate-limited / no source)")
            }
        } else {
            println("post.php returned no tmdb_id â€” embed-tmdb path skipped")
        }

        // ---------------------------------------------------------------
        // 7. embed-tmdb TV episode (if this title is a series)
        // ---------------------------------------------------------------
        if (tmdbId != null && post.episodes.isNotEmpty()) {
            val firstEp = post.episodes.first()
            section("7. embed-tmdb tv s=${firstEp.season} e=${firstEp.episode}")
            val (eCode, eBody, _) = get(
                "https://net27.cc/api/embed-tmdb/$tmdbId?type=tv&s=${firstEp.season ?: 1}&e=${firstEp.episode ?: 1}",
                mapOf(
                    "User-Agent" to MOBILE_UA,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to EMBED_REFERER,
                ),
            )
            log("HTTP $eCode  body=${eBody.take(200)}")
            val embed = NetMirrorParsers.parseEmbedTmdb(eBody)
            if (embed != null) {
                val best = NetMirrorParsers.pickEmbedStream(embed.streams)
                log("noSource=${embed.noSource} streams=${embed.streams.size} best=${best?.resolution}p")
                if (best != null) log(">>> EMBED TV OK â€” url=${best.url.take(120)}")
            } else {
                log(">>> embed-tmdb tv parse failed")
            }
        }

        // ---------------------------------------------------------------
        // 8. MOVIE path — search a movie, trace post -> embed -> NewTV
        // ---------------------------------------------------------------
        section("8. MOVIE path trace (the 'no link in movies' bug)")
        val movieQuery = java.net.URLEncoder.encode("The Batman", "UTF-8")
        val (msCode, msBody, _) = get(
            "https://net52.cc/mobile/search.php?s=$movieQuery&t=${System.currentTimeMillis() / 1000}",
            mobileProbeHeaders("https://net52.cc/home") + mapOf("Cookie" to cookie),
        )
        log("search HTTP $msCode  body=${msBody.take(200)}")
        val movieHits = NetMirrorParsers.parseSearch(msBody)
        val movie = movieHits.firstOrNull()
        log("movie hits=${movieHits.size}  picked=${movie?.title} id=${movie?.id}")

        if (movie != null) {
            val (mpCode, mpBody, _) = get(
                "https://net52.cc/mobile/post.php?id=${movie.id}&t=${System.currentTimeMillis() / 1000}",
                mobileProbeHeaders("https://net52.cc/home") + mapOf("Cookie" to cookie),
            )
            log("movie post HTTP $mpCode  body=${mpBody.take(200)}")
            val mPost = NetMirrorParsers.parsePost(mpBody)
            if (mPost != null) {
                log("movie post: tmdb_id=${mPost.tmdbId} imdb=${mPost.imdbId} episodes=${mPost.episodes.size} type=${mPost.type}")
                val mtmdb = mPost.tmdbId ?: run {
                    // Inline TMDB title-search fallback (JVM-safe, no TmdbMeta)
                    val tsCode2 = try {
                        val q = java.net.URLEncoder.encode(mPost.title, "UTF-8")
                        val (c, b, _) = get(
                            "https://api.themoviedb.org/3/search/multi?api_key=e6333b32409e02a4a6eba6fb7ff866bb&query=$q&language=en-US&include_adult=false&page=1",
                            mapOf("Accept" to "application/json"),
                        )
                        val arr = org.json.JSONObject(b).optJSONArray("results") ?: org.json.JSONArray()
                        (0 until arr.length()).firstNotNullOfOrNull { i ->
                            val m = arr.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                            if (m.optString("title").equals(mPost.title, true) || m.optString("name").equals(mPost.title, true))
                                m.optInt("id") to m.optString("media_type")
                            else null
                        }
                    } catch (e: Exception) { null }
                    log("title-search fallback -> ${tsCode2?.first} ${tsCode2?.second}")
                    tsCode2?.first
                }
                if (mtmdb != null) {
                    section("8b. embed-tmdb movie (tmdb=$mtmdb)")
                    val (eCode, eBody, _) = get(
                        "https://net27.cc/api/embed-tmdb/$mtmdb?type=movie",
                        mapOf("User-Agent" to MOBILE_UA, "Accept" to "application/json, text/plain, */*", "Referer" to EMBED_REFERER),
                    )
                    log("HTTP $eCode  body=${eBody.take(200)}")
                    val embed = NetMirrorParsers.parseEmbedTmdb(eBody)
                    val best = embed?.let { NetMirrorParsers.pickEmbedStream(it.streams) }
                    log("embed noSource=${embed?.noSource} streams=${embed?.streams?.size} best=${best?.resolution}p")
                    if (best != null) log(">>> MOVIE EMBED OK url=${best.url.take(100)}")
                    else log(">>> MOVIE embed-tmdb has NO source (noSource=true) — need NewTV")
                } else {
                    log(">>> no tmdbId resolvable for movie — embed skipped")
                }

                section("8c. NewTV player for movie (id=${movie.id})")
                val (mvCode, mvBody, _) = get(
                    "https://tv.imgcdn.kim/newtv/player.php?id=${movie.id}",
                    NEWTV_HEADERS + mapOf("Ott" to "nf", "Usertoken" to ""),
                )
                log("HTTP $mvCode  body=${mvBody.take(300)}")
                val mv = NetMirrorParsers.parseNewTvPlayer(mvBody)
                val mvlink = mv?.videoLink?.takeIf { it.isNotBlank() }
                log("movie player status=${mv?.status}  videoLink=${mvlink}")
                if (mv?.status == "n" || mvlink == null) log(">>> MOVIE NewTV FAILED (status=${mv?.status})")
                else {
                    val (mvmCode, mvmBody, _) = get(
                        mvlink,
                        NEWTV_HEADERS + mapOf("Referer" to (mv?.referer ?: "https://tv.imgcdn.kim"), "Cookie" to "hd=on"),
                    )
                    log("movie master HTTP $mvmCode len=${mvmBody.length} isDead=${NetMirrorParsers.newTvMasterIsDead(mvmBody)}")
                    log(mvmBody.lines().filter { it.contains("STREAM-INF") || it.contains("files/") }.take(6).joinToString("\n"))
                    if (!NetMirrorParsers.newTvMasterIsDead(mvmBody)) log(">>> MOVIE NewTV master ALIVE")
                    else log(">>> MOVIE NewTV master DEAD (in=unknown) — both paths fail = NO LINK")
                }
            } else {
                log("movie post parse failed")
            }
        } else {
            log("no movie found in search results")
        }

        // ---------------------------------------------------------------
        // 9. Hindi audio in series master — count audio tracks
        // ---------------------------------------------------------------
        section("9. Hindi audio check on a series episode master")
        val (auCode, auBody, _) = get(
            "https://tv.imgcdn.kim/newtv/hls/nf/$playbackId.m3u8",
            NEWTV_HEADERS + mapOf("Referer" to "https://net52.cc/home", "Cookie" to "hd=on"),
        )
        log("series master HTTP $auCode len=${auBody.length}")
        val audioLines = auBody.lines().filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
        log("audio tracks: ${audioLines.size}")
        audioLines.take(6).forEach { log("  $it") }

        println("\n===== PROBE COMPLETE =====")
    }

    private fun mobileProbeHeaders(referer: String): Map<String, String> = mapOf(
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
}

