package com.ottmirror

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object OTTMirrorBackend {

    // CNCVerse-exact mobile request profile (verbatim from the working
    // reference): full WebView client-hint + Sec-Fetch set. The backend's
    // anti-abuse scores requests against its own WebView app fingerprint —
    // the stripped header set we sent before reads as a bot.
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

    private fun streamHeaders(referer: String, cookie: String, ott: OttService): Map<String, String> {
        val base = linkedMapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to referer,
            "Origin" to referer.substringBefore("/home").ifBlank { referer },
            "Accept" to "*/*",
            "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
            "X-Requested-With" to "XMLHttpRequest",
        )
        // The HLS CDN requires hd=on + the ott marker on stream requests
        // (CNCVerse's getVideoInterceptor injects "Cookie: hd=on" on every
        // .m3u8 request). t_hash_t is optional — only attached when live.
        base["Cookie"] = buildString {
            if (cookie.isNotBlank()) append("t_hash_t=$cookie; ")
            append("ott=${ott.ottCookie}; hd=on")
        }
        return base
    }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/").lowercase()

    private fun mobileCookies(ott: OttService, cookie: String): Map<String, String> =
        mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on")

    fun limitedMessage(): String {
        val wait = HostThrottler.cooldownSeconds()
        return if (wait > 0) {
            "NetMirror limit hit (shared IP/VPN) — switch to mobile data or retry in ~${wait}s"
        } else {
            "NetMirror servers busy (shared IP/VPN) — switch network or retry in a minute"
        }
    }

    /** True while the limiter cooldown is active — providers surface it instead of silent emptiness. */
    fun rateLimited(): Boolean = HostThrottler.isCoolingDown()


    // ------------------------------------------------------------------
    // Cookie / verify (t_hash_t)
    //
    // The server kills its side of the session ~4-5 minutes after issuing
    // it (live-probed). verify() therefore re-verifies proactively whenever
    // the 3-minute CookieBox window lapses. One raw POST per host; the
    // singleflight collapses every concurrent caller onto one verify.
    // ------------------------------------------------------------------

    private val verifyMutex = Mutex()
    @Volatile private var inFlightVerify: kotlinx.coroutines.Deferred<String>? = null

    suspend fun verify(): String {
        CookieBox.tHashT.takeIf { CookieBox.fresh() }?.let { return it }
        // Warm from the persisted 15 h cookie BEFORE any network call — the
        // reference never POSTs verify.php on app restart; it reuses the
        // stored cookie until it expires. Only a genuinely expired store (or
        // a server-side Invalid User) triggers a re-verify.
        NetMirrorCookieStore.load()?.let { (cookie, host, _) ->
            if (cookie.isNotBlank()) {
                CookieBox.put(cookie, host)
                return cookie
            }
        }
        val existing = inFlightVerify
        if (existing != null && existing.isActive) {
            return existing.await()
        }
        return verifyMutex.withLock {
            inFlightVerify?.takeIf { it.isActive }?.let { return@withLock it.await() }
            val deferred = coroutineScope {
                async(Dispatchers.IO) { doVerify() }
            }
            inFlightVerify = deferred
            try {
                deferred.await()
            } catch (e: Exception) {
                // Last resort: a stale persisted cookie is better than no
                // cookie at all — request paths detect the server's
                // "Invalid User" body and re-verify. Only reached when the
                // verify infrastructure itself is unreachable.
                NetMirrorCookieStore.load()?.let { (cookie, host, _) ->
                    CookieBox.put(cookie, host)
                    return@withLock cookie
                }
                throw e
            }
        }
    }

    private suspend fun doVerify(): String {
        // One raw POST per host, at most 3 hosts. A 429/anti-abuse body means
        // the IP is limited — wait the cooldown out and retry the SAME host;
        // never burn the mirror list feeding an already-saturated limiter.
        val maxHosts = 3
        var retriedLimited = false
        var hostsTried = 0
        while (hostsTried < maxHosts) {
            val host = DomainRotator.current(Role.MOBILE) ?: break
            when (val result = tryVerifyHost(host)) {
                is VerifyResult.Success -> {
                    CookieBox.put(result.token, host)
                    NetMirrorCookieStore.save(result.token, host)
                    HostThrottler.recordSuccess()
                    return result.token
                }
                is VerifyResult.Limited -> {
                    if (!retriedLimited) {
                        retriedLimited = true
                        HostThrottler.awaitCooldown()
                        continue // same host, same budget slot
                    }
                    DomainRotator.markFailed(Role.MOBILE, host)
                }
                is VerifyResult.Dead -> DomainRotator.markFailed(Role.MOBILE, host)
            }
            hostsTried++
        }
        throw ErrorLoadingException(
            if (HostThrottler.isCoolingDown()) limitedMessage()
            else "NetMirror is unreachable right now (all hosts blocked) — retry in a minute"
        )
    }

    private sealed interface VerifyResult {
        data class Success(val token: String) : VerifyResult
        data object Limited : VerifyResult
        data object Dead : VerifyResult
    }

    private suspend fun tryVerifyHost(host: String): VerifyResult {
        // CNCVerse-exact verify (verbatim from the working reference):
        // POST /verify.php with redirects disabled, DESKTOP Chrome UA,
        // net22.cc Origin/Referer decoys (net22/verify2 is never actually
        // fetched). The server answers 301 carrying Set-Cookie: t_hash_t=...
        // on the redirect response itself. Exactly ONE request per host.
        val body = "g-recaptcha-response=${UUID.randomUUID()}"
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        HostThrottler.gate()
        val req = Request.Builder()
            .url("$host/verify.php")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .apply {
                mapOf(
                    "Origin" to "https://net22.cc",
                    "Referer" to "https://net22.cc/verify2",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to DESKTOP_VERIFY_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
                    "Cache-Control" to "max-age=0",
                    "Connection" to "keep-alive",
                    "sec-ch-ua" to "\"Chromium\";v=\"147\", \"Not(A:Brand\";v=\"24\"",
                    "sec-ch-ua-mobile" to "?0",
                    "sec-ch-ua-platform" to "\"Windows\"",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                ).forEach { (k, v) -> header(k, v) }
            }
            .build()
        val (code, setCookie, respBody) = runCatching {
            client.newCall(req).execute().use { resp ->
                val cookie = resp.headers.values("Set-Cookie")
                    .firstOrNull { it.startsWith("t_hash_t=") }
                    ?.substringAfter("t_hash_t=")?.substringBefore(";").orEmpty()
                val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                Triple(resp.code, cookie, text)
            }
        }.getOrElse { return VerifyResult.Dead }

        // The 301 that carries Set-Cookie IS the success signal — read it
        // before classification, since a 3xx would otherwise be judged DEAD.
        val token = setCookie.takeIf { it.isNotBlank() }
        if (token != null) return VerifyResult.Success(token)

        // No cookie: the response is a real failure — distinguish an IP-wide
        // limit (wait + retry same host) from a dead host (advance).
        return when (NetMirrorGuard.classify(code, respBody)) {
            NetMirrorGuard.Verdict.LIMITED -> {
                HostThrottler.recordLimited(null)
                VerifyResult.Limited
            }
            else -> VerifyResult.Dead
        }
    }


    // ------------------------------------------------------------------
    // Home page
    // ------------------------------------------------------------------

    suspend fun getHomeRows(ott: OttService): List<Pair<String, List<String>>> {
        NetMirrorResponseCache.get<List<Pair<String, List<String>>>>("home|${ott.id}")?.let { return it }
        val cookie = verify()
        var attempts = 0
        while (attempts++ < 3) {
            val host = DomainRotator.current(Role.MOBILE) ?: return emptyList()
            HostThrottler.gate()
            val resp = runCatching {
                app.get(
                    "$host/mobile/home?app=1",
                    headers = mobileHeaders("$host/home"),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: return emptyList()
            when (NetMirrorGuard.classify(resp.code, resp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    val rows = runCatching { NetMirrorParsers.parseHomeRows(resp.document) }.getOrDefault(emptyList())
                    if (rows.isNotEmpty()) NetMirrorResponseCache.put("home|${ott.id}", rows)
                    return rows
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    // Fail fast — no retry storm on a saturated shared IP.
                    throw ErrorLoadingException(limitedMessage())
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    // /mobile/home works without a session; a dead-cookie
                    // body is just a transient server state — reuse the same
                    // cookie (CNCVerse blind-reuses its 15 h cookie).
                    if (attempts >= 3) return emptyList()
                }
                NetMirrorGuard.Verdict.DEAD -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // Search / load
    // ------------------------------------------------------------------

    // Per-OTT mobile search is the only path: with a valid t_hash_t cookie each
    // /mobile/{ott}/search.php returns OTT-scoped results in that OTT's own ID
    // namespace (base numeric for nf, alphanumeric for pv, distinct for hs).
    // Empty is a correct, scoped answer — never fall back to the unscoped
    // desktop endpoint. A dead cookie silently degrades to the server's
    // "Top Searches" fallback, which is detected and answered with a
    // re-verify + one retry so scoped quality holds.
    suspend fun search(ott: OttService, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        var attempts = 0
        while (attempts++ < 3) {
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: break
            val url = "$host/mobile${ott.mobilePrefix}/search.php?s=$encoded&t=${System.currentTimeMillis() / 1000}"
            HostThrottler.gate()
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: break

            when (NetMirrorGuard.classify(resp.code, resp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    val hits = NetMirrorParsers.parseSearch(resp.text)
                    // CNCVerse blind-reuses the cookie: if the server returns
                    // "Top Searches" instead of scoped results, it's a
                    // transient server-side state — just return what we got.
                    return hits
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    // Fail fast — no cooldown wait on a shared saturated IP.
                    throw ErrorLoadingException(limitedMessage())
                }
                // Dead cookie or dead host — try the next mirror.
                else -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
        throw ErrorLoadingException(
            if (HostThrottler.isCoolingDown()) limitedMessage()
            else "NetMirror is unreachable right now — retry in a minute"
        )
    }

    suspend fun loadPost(ott: OttService, id: String): NetMirrorPost? {
        NetMirrorResponseCache.get<NetMirrorPost>("post|${ott.id}|$id")?.let { return it }
        var attempts = 0
        while (attempts++ < 3) {
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: return null
            HostThrottler.gate()
            val url = "$host/mobile${ott.mobilePrefix}/post.php?id=$id&t=${System.currentTimeMillis() / 1000}"
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: return null

            when (NetMirrorGuard.classify(resp.code, resp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    val post = NetMirrorParsers.parsePost(resp.text)
                    if (post != null) NetMirrorResponseCache.put("post|${ott.id}|$id", post)
                    return post
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    // Fail fast — no retry or cooldown on a shared saturated IP.
                    throw ErrorLoadingException(limitedMessage())
                }
                // Dead cookie or dead host — try the next mirror.
                else -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
        return null
    }


    suspend fun getEpisodes(ott: OttService, seriesId: String, seasonId: String): List<NetMirrorEpisode> {
        NetMirrorResponseCache.get<List<NetMirrorEpisode>>("eps|${ott.id}|$seriesId|$seasonId")?.let { return it }
        val out = mutableListOf<NetMirrorEpisode>()
        var page = 1
        var sessionRetried = false
        var limitedAttempts = 0
        var emptyPages = 0
        while (true) {
            if (emptyPages >= 2) break
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: break
            HostThrottler.gate()
            val url = "$host/mobile${ott.mobilePrefix}/episodes.php?s=$seasonId&series=$seriesId&t=${System.currentTimeMillis() / 1000}&page=$page"
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: break

            when (NetMirrorGuard.classify(resp.code, resp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    val (eps, next) = NetMirrorParsers.parseEpisodes(resp.text)
                    out.addAll(eps)
                    if (!next) break
                    page++
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    if (sessionRetried) break
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) break
                }
                NetMirrorGuard.Verdict.DEAD -> break
            }
            if (out.isEmpty()) emptyPages++ else emptyPages = 0
        }
        if (out.isNotEmpty()) NetMirrorResponseCache.put("eps|${ott.id}|$seriesId|$seasonId", out.toList())
        return out
    }


    // ------------------------------------------------------------------
    // NewTV player API (primary stream source)
    // ------------------------------------------------------------------

    suspend fun resolveNewTvBase(probe: Boolean = false): String {
        if (NewTvBase.value.isBlank()) NewTvBase.warm()
        NewTvBase.value.takeIf { it.isNotBlank() }?.let { return it }
        // CNCVerse-exact walk: iterate ALL 24 domains in list order until one
        // answers checknewtv.php. This infra (mobidetect domains) is separate
        // from the net7x per-IP limiter — live probes show checknewtv.php
        // answers fine even while net7x is limited — so the old capped walk
        // only made resolution fail on dead mirrors. The result is cached
        // in-memory + persisted 24 h, so this walk is rare.
        for (host in NEWTV_DOMAINS) {
            val h = decodeBase64(host).trimEnd('/')
            HostThrottler.gate()
            val resp = runCatching {
                app.get("$h/checknewtv.php", headers = NEWTV_HEADERS, timeout = 8)
            }.getOrNull()

            var resolved: String? = null
            if (resp != null && resp.code in 200..299) {
                val token = NetMirrorParsers.parseNewTvToken(resp.text)?.tokenHash?.takeIf { it.isNotBlank() }
                if (token != null) {
                    val apiBase = Base64Decode.decodeUtf8(token)?.trimEnd('/')
                    if (!apiBase.isNullOrBlank() && apiBase.startsWith("http")) resolved = apiBase
                }
            }
            if (resolved != null) {
                NewTvBase.set(resolved)
                return resolved
            }
        }

        if (probe) return ""
        throw ErrorLoadingException(
            if (HostThrottler.isCoolingDown()) limitedMessage()
            else "NetMirror NewTV servers unreachable — retry in a minute"
        )
    }

    // ------------------------------------------------------------------
    // loadLinks: NewTV primary, native playlist fallback
    // ------------------------------------------------------------------

    internal fun ottLabel(ott: OttService): String = when (ott) {
        OttService.NETFLIX -> "Netflix"
        OttService.HOTSTAR -> "Hotstar"
        OttService.PRIME -> "Prime Video"
        OttService.DISNEY -> "Disney+"
    }

    // CNCVerse quirk: the NewTV player is addressed with Ott: hs for BOTH
    // Hotstar and Disney+ (dp/studio cookies are browse-only concerns).
    private fun newTvOttHeader(ott: OttService): String = when (ott) {
        OttService.DISNEY -> "hs"
        else -> ott.ottCookie
    }

    private fun emit(
        ott: OttService,
        url: String,
        referer: String?,
        quality: Int,
        cookie: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val label = ottLabel(ott)
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val ref = referer ?: url
        callback(
            ExtractorLink(
                source = label, name = label, url = url,
                referer = ref, quality = quality,
                headers = streamHeaders(ref, cookie, ott),
                extractorData = null, type = type, audioTracks = emptyList(),
            )
        )
    }

    // Cache entries must carry the SAME referer/headers as the live links, or
    // a replayed stream would be served with the wrong hotlink context. The
    // referer is the player page (…/home), never the stream URL itself.
    private fun collect(ott: OttService, urls: List<String>, referer: String, cookie: String): List<ExtractorLink> {
        val label = ottLabel(ott)
        return urls.distinct().map { u ->
            ExtractorLink(
                source = label, name = label, url = u, referer = referer,
                quality = getQualityFromName(u), headers = streamHeaders(referer, cookie, ott),
                extractorData = null,
                type = if (u.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                audioTracks = emptyList(),
            )
        }
    }

    suspend fun loadLinks(
        ott: OttService,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val ld = decodeLoadData(data) ?: return@withContext false
        val contentId = ld.id
        Log.d("OTTMirror", "loadLinks ott=${ott.id} id=$contentId title=${ld.title} tmdbId=${ld.tmdbId} s=${ld.season} e=${ld.episode}")

        LinkCache.get(contentId)?.let { cached ->
            if (cached.isNotEmpty()) {
                cached.forEach { runCatching { callback(it) } }
                return@withContext true
            }
        }
var sawLimited = false

        // ------------------------------------------------------------------
        // Path 1 — embed-tmdb (sessionless, TMDB-keyed, net27 backend).
        // PRIMARY: the only playback path verified working end-to-end in
        // live probes — direct signed MP4 (fast start, up to 1080p) with
        // Hindi + regional captions, ONE connection (no multi-variant HLS
        // connection storm that triggers the CDN overlay), zero net7x
        // traffic, zero session. If the title is covered it just works.
        // ------------------------------------------------------------------
        val embed = ld.tmdbId?.let { tmdbId ->
            runCatching { EmbedTmdb.resolve(tmdbId, ld.season, ld.episode) }.getOrNull()
        }
        if (embed == null) Log.d("OTTMirror", "embed-tmdb MISS for id=$contentId (tmdbId=${ld.tmdbId})")
        if (embed != null) {
            val link = ExtractorLink(
                source = ottLabel(ott), name = ottLabel(ott), url = embed.url,
                referer = EMBED_REFERER, quality = embed.quality,
                headers = streamHeaders(EMBED_REFERER, "", ott),
                extractorData = null,
                type = if (embed.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                audioTracks = emptyList(),
            )
            runCatching { callback(link) }
            embed.subs.forEach { c ->
                runCatching { subtitleCallback(SubtitleFile(c.name.ifBlank { c.lang.ifBlank { "subs" } }, c.url)) }
            }
            LinkCache.put(contentId, listOf(link))
            return@withContext true
        }

        // ------------------------------------------------------------------
        // Path 2 — NewTV player (CNCVerse-exact, Hindi audio). Fallback for
        // titles the embed API does not cover.
        // ------------------------------------------------------------------
        var newTvFailure: Throwable? = null
        var newTvOk = false
        try {
            val apiBase = resolveNewTvBase()
            val playerUrl = "$apiBase/newtv/player.php?id=$contentId"
            var limitedAttempts = 0
            var attempts = 0
            while (attempts++ < 4) {
                HostThrottler.gate()
                val resp = runCatching {
                    app.get(
                        playerUrl,
                        headers = NEWTV_HEADERS + mapOf(
                            "Ott" to newTvOttHeader(ott),
                            "Usertoken" to "",
                        ),
                        timeout = 10,
                    )
                }.getOrNull() ?: break

                when (NetMirrorGuard.classify(resp.code, resp.text)) {
                    NetMirrorGuard.Verdict.OK -> {
                        val p = NetMirrorParsers.parseNewTvPlayer(resp.text)
                        val vlink = p?.videoLink?.takeIf { it.isNotBlank() }
                        Log.d("OTTMirror", "player.php status=${p?.status} vlink=${vlink != null}")
                        // ORIGINAL WORKING BEHAVIOR: accept any non-"n" status
                        // with a real video_link ("ok" and "otp" both play on
                        // residential/mobile IPs). The strict status=="ok"
                        // check and the master-collapse fetch regressed
                        // playback — the CDN serves a dead in=unknown
                        // template to some IPs, and emitting the raw link is
                        // what the reference flow does.
                        if (vlink != null && p?.status != "n") {
                            val ref = p.referer ?: apiBase
                            emit(ott, vlink, ref, getQualityFromName(vlink), cookie = "", subtitleCallback, callback)
                            LinkCache.put(contentId, collect(ott, listOf(vlink), ref, ""))
                            newTvOk = true
                        }
                        break
                    }
                    NetMirrorGuard.Verdict.LIMITED -> {
                        sawLimited = true
                        limitedAttempts++
                        if (!NetMirrorGuard.onLimited(limitedAttempts)) break
                    }
                    // player.php needs no session; anything else final.
                    else -> break
                }
            }
        } catch (e: Exception) {
            newTvFailure = e
        }
        if (newTvOk) return@withContext true

        // ------------------------------------------------------------------
        // Path 3 — native play.php/playlist.php (net7x, session-bound). Last
        // resort when both sessionless paths miss. Restored because live
        // probes confirm NewTV/embed cover most but not all titles; the
        // native flow historically worked for movies on real devices.
        // ------------------------------------------------------------------
        val cookie = runCatching { verify() }.getOrDefault("")
        val nativeOk = if (cookie.isNotBlank()) {
            runCatching { nativePlaylistFlow(ott, contentId, ld.title, cookie, subtitleCallback, callback) }.getOrDefault(false)
        } else false
        if (nativeOk) return@withContext true

        // ------------------------------------------------------------------
        // Terminal
        // ------------------------------------------------------------------
        newTvFailure?.let { throw it }
        if (sawLimited) throw ErrorLoadingException(limitedMessage())
        false
    }

    private suspend fun nativePlaylistFlow(
        ott: OttService,
        id: String,
        title: String,
        cookie: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = DomainRotator.current(Role.MOBILE) ?: return false
        Log.d("OTTMirror", "native flow host=$host id=$id")
        val playHost = host

        suspend fun postPlay(): com.lagradost.nicehttp.NiceResponse? {
            HostThrottler.gate()
            return runCatching {
                app.post(
                    "$playHost/play.php",
                    headers = mobileHeaders("$playHost/home") + mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to playHost,
                    ),
                    data = mapOf("id" to id),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$playHost/home",
                    timeout = 10,
                )
            }.getOrNull()
        }

        var hToken: String? = null
        var sessionRetried = false
        var limitedAttempts = 0
        var attempts = 0
        while (attempts++ < 4) {
            val playResp = postPlay() ?: return false
            when (NetMirrorGuard.classify(playResp.code, playResp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    hToken = runCatching {
                        org.json.JSONObject(playResp.text).optString("h").takeIf { it.isNotBlank() }
                    }.getOrNull()
                    break
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) return false
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    if (sessionRetried) return false
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                    verify()
                }
                NetMirrorGuard.Verdict.DEAD -> return false
            }
        }
        val h = hToken ?: return false

        val playlistUrl = "$host/mobile${ott.mobilePrefix}/playlist.php?id=$id&t=${java.net.URLEncoder.encode(title, "UTF-8")}&tm=${System.currentTimeMillis() / 1000}&h=${java.net.URLEncoder.encode(h, "UTF-8")}"
        suspend fun getPlaylist() = run {
            HostThrottler.gate()
            runCatching {
                app.get(
                    playlistUrl,
                    headers = mobileHeaders("$host/home"),
                    cookies = mobileCookies(ott, cookie),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull()
        }

        var playlist: PlaylistResponse? = null
        sessionRetried = false
        limitedAttempts = 0
        attempts = 0
        while (attempts++ < 4) {
            val playlistResp = getPlaylist() ?: return false
            when (NetMirrorGuard.classify(playlistResp.code, playlistResp.text)) {
                NetMirrorGuard.Verdict.OK -> {
                    HostThrottler.recordSuccess()
                    playlist = NetMirrorParsers.parsePlaylist(playlistResp.text)
                    break
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) return false
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    if (sessionRetried) return false
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                    verify()
                }
                NetMirrorGuard.Verdict.DEAD -> return false
            }
        }

        val sources = playlist?.sources.orEmpty().filter { !it.file.isBlank() }
        if (sources.isEmpty()) return false

        val referer = "$host/home"
        val chosen = sources.firstOrNull { it.default.equals("true", ignoreCase = true) || it.default == "1" }
            ?: sources.maxByOrNull { qualityFromLabel(it.label) }
            ?: sources.first()
        val streamUrl = if (chosen.file.startsWith("http", ignoreCase = true)) chosen.file else "$host${chosen.file}"
        emit(ott, streamUrl, referer, qualityFromLabel(chosen.label), cookie, subtitleCallback, callback)
        LinkCache.put(id, collect(ott, listOf(streamUrl), referer, cookie))
        return true
    }

    private fun qualityFromLabel(label: String): Int = when {
        label.contains("1080", true) || label.contains("Full HD", true) -> 1080
        label.contains("720", true) || label.contains("Mid HD", true) -> 720
        label.contains("480", true) || label.contains("Low HD", true) -> 480
        label.contains("360", true) -> 360
        else -> getQualityFromName(label)
    }
}
