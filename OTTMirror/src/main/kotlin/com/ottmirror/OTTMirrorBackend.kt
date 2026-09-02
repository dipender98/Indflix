package com.ottmirror

import android.util.Log
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object OTTMirrorBackend {
    private val linkLocks = ConcurrentHashMap<String, Mutex>()

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
    @Volatile private var inFlightVerify: Deferred<String>? = null

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
            } finally {
                if (inFlightVerify === deferred) inFlightVerify = null
            }
        }
    }

    private suspend fun doVerify(): String {
        // One raw POST per host, at most 3 hosts. A 429/anti-abuse body means
        // the IP is limited — wait the cooldown out and retry the SAME host;
        // never burn the mirror list feeding an already-saturated limiter.
        val maxHosts = 3
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
                    throw ErrorLoadingException(limitedMessage())
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
        // Warm the session once for the whole paging walk — the cookie lives
        // in CookieBox for 15 h, so verify() is effectively a no-op on the
        // second and later page calls. This keeps a 10-season series from
        // burning verify calls into the per-IP limiter on every page.
        var cookie = verify()
        val out = mutableListOf<NetMirrorEpisode>()
        var page = 1
        var sessionRetried = false
        var limitedAttempts = 0
        var emptyPages = 0
        while (true) {
            if (emptyPages >= 2) break
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
                    // Refresh the locally-captured cookie so the next
                    // request carries the freshly-issued t_hash_t.
                    cookie = verify()
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    NetMirrorGuard.onLimited(1)
                    throw ErrorLoadingException(limitedMessage())
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

    private val newTvBaseMutex = Mutex()
    @Volatile private var inFlightNewTvBase: Deferred<String>? = null

    suspend fun resolveNewTvBase(probe: Boolean = false): String {
        if (NewTvBase.value.isBlank()) NewTvBase.warm()
        NewTvBase.value.takeIf { it.isNotBlank() }?.let { return it }
        inFlightNewTvBase?.takeIf { it.isActive }?.let { return it.await() }
        return newTvBaseMutex.withLock {
            NewTvBase.value.takeIf { it.isNotBlank() }?.let { return@withLock it }
            inFlightNewTvBase?.takeIf { it.isActive }?.let { return@withLock it.await() }
            val deferred = coroutineScope { async(Dispatchers.IO) { resolveNewTvBaseInternal(probe) } }
            inFlightNewTvBase = deferred
            try {
                deferred.await()
            } finally {
                if (inFlightNewTvBase === deferred) inFlightNewTvBase = null
            }
        }
    }

    private suspend fun resolveNewTvBaseInternal(probe: Boolean): String {
        if (NewTvBase.value.isBlank()) NewTvBase.warm()
        NewTvBase.value.takeIf { it.isNotBlank() }?.let { return it }
        // Probe at most three domains. A limited response is IP-wide, so
        // rotating through the remaining mirrors would only add traffic.
        var hostsTried = 0
        for (host in NEWTV_DOMAINS) {
            if (hostsTried++ >= 3) break
            val h = decodeBase64(host).trimEnd('/')
            HostThrottler.gate()
            val resp = runCatching {
                app.get("$h/checknewtv.php", headers = NEWTV_HEADERS, timeout = 8)
            }.getOrNull()

            if (resp != null) {
                when (NetMirrorGuard.classify(resp.code, resp.text)) {
                    NetMirrorGuard.Verdict.LIMITED -> {
                        NetMirrorGuard.onLimited(1)
                        if (probe) return ""
                        throw ErrorLoadingException(limitedMessage())
                    }
                    NetMirrorGuard.Verdict.OK -> {
                        val token = NetMirrorParsers.parseNewTvToken(resp.text)?.tokenHash?.takeIf { it.isNotBlank() }
                        if (token != null) {
                            val apiBase = Base64Decode.decodeUtf8(token)?.trimEnd('/')
                            if (!apiBase.isNullOrBlank() && apiBase.startsWith("http")) {
                                NewTvBase.set(apiBase)
                                return apiBase
                            }
                        }
                    }
                    else -> Unit
                }
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
        audioTracks: List<AudioFile> = emptyList(),
    ): ExtractorLink {
        val label = ottLabel(ott)
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val ref = referer ?: url
        return ExtractorLink(
            source = label, name = label, url = url,
            referer = ref, quality = quality,
            headers = streamHeaders(ref, cookie, ott),
            extractorData = null, type = type, audioTracks = audioTracks,
        )
    }

    /**
     * NewTV master link with the APP-FAITHFUL playback header set. These
     * headers are the auth the CDN validates lazily on every HLS request
     * (master + variants + segments): the /OS.GatuNewTV v1.0 UA fingerprint,
     * the player.php referer, Cookie hd=on, the app marker, and the
     * otp.php-issued Usertoken. This is what unlocks the labeled audio
     * groups (dual audio) — playback with the generic mobile UA gets the
     * degraded context instead.
     */
    private fun emitNewTv(
        ott: OttService,
        url: String,
        referer: String,
        quality: Int,
    ): ExtractorLink {
        val label = ottLabel(ott)
        return ExtractorLink(
            source = label, name = label, url = url,
            referer = referer, quality = quality,
            headers = newTvStreamHeaders(referer, ott, NewTvUserToken.cached(ott)),
            extractorData = null, type = ExtractorLinkType.M3U8, audioTracks = emptyList(),
        )
    }

    /**
     * Playback headers for NewTV HLS requests (master + variants + segments).
     * The CDN validates these lazily on every request — they ARE the auth:
     *   - User-Agent: the /OS.GatuNewTV v1.0 app fingerprint (NEWTV_UA);
     *   - Referer: the player.php response referer (hotlink context);
     *   - Cookie: hd=on (+ ott marker) — variants 404 without it (Zangetsu);
     *   - X-Requested-With: NetmirrorNewTV v1.0 (app marker);
     *   - Usertoken: the otp.php-issued session token (empty tolerated).
     */
    private fun newTvStreamHeaders(referer: String, ott: OttService, usertoken: String): Map<String, String> = mapOf(
        "User-Agent" to NEWTV_UA,
        "Referer" to referer,
        "Origin" to referer.substringBefore("/newtv").ifBlank { referer },
        "Accept" to "*/*",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "X-Requested-With" to "NetmirrorNewTV v1.0",
        "Usertoken" to usertoken,
        "Cookie" to "ott=${ott.ottCookie}; hd=on",
    )

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
        val linkKey = "${ott.id}:$contentId"
        val linkLock = linkLocks.computeIfAbsent(linkKey) { Mutex() }
        try {
            return@withContext linkLock.withLock {
                LinkCache.get(contentId)?.let { cached ->
                    if (cached.isNotEmpty()) {
                        cached.forEach { runCatching { callback(it) } }
                        return@withLock true
                    }
        // ------------------------------------------------------------------
        // Hard 30 s deadline: resolveNewTvBase can walk 24 dead domains and
        // every request carries a timeout — without a bound the load spinner
        // could run for minutes before CS3's own timeout fires (the "keeps
        // loading then crashes" report). On timeout the player gets a clean
        // "No link found".
        // ------------------------------------------------------------------
        val emittedCount = withTimeoutOrNull(30_000L) {
            coroutineScope {
                val embedDeferred = async {
                    ld.tmdbId?.let { tmdbId ->
                        softCatch { EmbedTmdb.resolve(tmdbId, ld.season, ld.episode) }
                    }
                }
                val newTvDeferred = async {
                    softCatch { fetchNewTvPlayerBody(ott, contentId) }
                }

                val embed = embedDeferred.await()
                if (embed == null) Log.d("OTTMirror", "embed-tmdb MISS for id=$contentId (tmdbId=${ld.tmdbId})")
                val newTv = newTvDeferred.await()

                // Embed MP4 quality tiers (muxed audio — plays safely for
                // everyone).
                val embedLinks = embed?.streams
                    ?.filter { it.resolution in 1..1080 }
                    ?.sortedByDescending { it.resolution }
                    ?.map { s -> emit(ott, s.url, EMBED_REFERER, s.resolution, cookie = "") }
                    .orEmpty()

                // NewTV master: fetched with the app-fingerprint playback
                // headers (GatuNewTV UA + hd=on + Usertoken) and emitted
                // VERBATIM — exactly like the four working reference
                // implementations. The CDN validates lazily per client
                // context, so the plugin neither rewrites the in=unknown
                // keys nor pre-flights variant/audio playlists from here
                // (the player's own HLS loader fetches master -> variants ->
                // segments in one consistent context; extra bursts only trip
                // the CDN). The master link carries the SAME header set so
                // the player's prepare inherits the app fingerprint, which
                // is what unlocks the labeled audio groups (dual audio).
                val allLinks = mutableListOf<ExtractorLink>()
                allLinks += embedLinks

                if (newTv != null) {
                    allLinks += emitNewTv(
                        ott,
                        url = newTv.vlink,
                        referer = newTv.referer,
                        quality = getQualityFromName(newTv.vlink),
                    )
                }

                if (allLinks.isNotEmpty()) {
                    allLinks.forEach { runCatching { callback(it) } }
                    embed?.subs?.forEach { c ->
                        runCatching { subtitleCallback(SubtitleFile(c.name.ifBlank { c.lang.ifBlank { "subs" } }, c.url)) }
                    }
                    LinkCache.put(contentId, allLinks.toList())
                }
                allLinks.size
            }
        } ?: 0

        // Nothing playable from either sessionless path → clean "No link
        // found". The native net7x flow (verify + play.php + playlist.php)
        // is deliberately NOT attempted — it is the only backend with the
        // per-IP limiter, and a rate-limit error at play is worse than a
        // silent miss.
        emittedCount > 0
            }
        } finally {
            linkLocks.remove(linkKey, linkLock)
        }
    }

    /**
     * otp.php-issued session token (mangayomi flow): GET {newtvBase}/otp.php
     * with header `otp: 111111` -> response field `usertoken`. This is the
     * privileged-mode marker behind player.php's `status:"otp"`. Cached ~55
     * min per ott; failures return "" and the flow continues (the nf path
     * works with an empty Usertoken — Sushan64 precedent).
     */
    private object NewTvUserToken {
        private const val TTL_MS = 55 * 60 * 1000L
        private val values = ConcurrentHashMap<String, Pair<String, Long>>()
        private val mutex = Mutex()
        private val inFlight = ConcurrentHashMap<String, Deferred<String>>()

        suspend fun refresh(ott: OttService): String {
            cached(ott).takeIf { it.isNotBlank() }?.let { return it }
            inFlight[ott.id]?.takeIf { it.isActive }?.let { return it.await() }
            return mutex.withLock {
                cached(ott).takeIf { it.isNotBlank() }?.let { return@withLock it }
                inFlight[ott.id]?.takeIf { it.isActive }?.let { return@withLock it.await() }
                val deferred = coroutineScope { async(Dispatchers.IO) { refreshInternal(ott) } }
                inFlight[ott.id] = deferred
                try {
                    deferred.await()
                } finally {
                    inFlight.remove(ott.id, deferred)
                }
            }
        }

        private suspend fun refreshInternal(ott: OttService): String {
            val apiBase = softCatch { resolveNewTvBase() } ?: return ""
            HostThrottler.gate()
            val resp = softCatch {
                app.get(
                    "$apiBase/newtv/otp.php",
                    headers = NEWTV_HEADERS + mapOf(
                        "Ott" to newTvOttHeader(ott),
                        "otp" to "111111",
                    ),
                    timeout = 8,
                )
            } ?: return ""
            if (NetMirrorGuard.classify(resp.code, resp.text) == NetMirrorGuard.Verdict.LIMITED) {
                NetMirrorGuard.onLimited(1)
                throw ErrorLoadingException(limitedMessage())
            }
            val token = runCatching {
                org.json.JSONObject(resp.text).optString("usertoken")
            }.getOrDefault("").takeIf { it.isNotBlank() } ?: ""
            if (token.isNotBlank()) values[ott.id] = token to System.currentTimeMillis()
            return token
        }

        fun cached(ott: OttService): String =
            values[ott.id]
                ?.takeIf { (v, at) -> v.isNotBlank() && System.currentTimeMillis() - at < TTL_MS }
                ?.first ?: ""
    }

    private data class NewTvPlayer(val vlink: String, val referer: String)

    /**
     * Fetch (or replay from the 10-min cache) the NewTV player.php body and
     * return the master URL + referer, or null when the title is not
     * covered / the answer is unusable / the retry ladder gave up. Never
     * throws — loadLinks treats null as "NewTV adds nothing" and falls back
     * to whatever embed produced (or a clean "No link found"). Warms the
     * otp.php usertoken first (cached in NewTvUserToken for header reuse).
     */
    private suspend fun fetchNewTvPlayerBody(ott: OttService, contentId: String): NewTvPlayer? {
        val apiBase = resolveNewTvBase()
        val usertoken = NewTvUserToken.refresh(ott)
        val playerUrl = "$apiBase/newtv/player.php?id=$contentId"
        // player.php answers are stable for the lifetime of the 60-min
        // LinkCache window — CDN-signed, no per-tap rotation. Cache the raw
        // body 10 min so a refresh-after-network-blip doesn't fan out another
        // newtv/player.php request into a draining IP bucket.
        val cached = NetMirrorResponseCache.get<String>("player|${ott.id}|$contentId")
        if (cached != null) return newTvPlayerFromBody(cached, apiBase)
        HostThrottler.gate()
        val resp = softCatch {
            app.get(
                playerUrl,
                headers = NEWTV_HEADERS + mapOf(
                    "Ott" to newTvOttHeader(ott),
                    "Usertoken" to usertoken,
                ),
                timeout = 10,
            )
        } ?: return null

        when (NetMirrorGuard.classify(resp.code, resp.text)) {
            NetMirrorGuard.Verdict.OK -> {
                NetMirrorResponseCache.put("player|${ott.id}|$contentId", resp.text)
                return newTvPlayerFromBody(resp.text, apiBase)
            }
            NetMirrorGuard.Verdict.LIMITED -> {
                NetMirrorGuard.onLimited(1)
                return null
            }
            // player.php needs no session; anything else final.
            else -> return null
        }
    }

    private fun newTvPlayerFromBody(body: String, apiBase: String): NewTvPlayer? {
        val p = NetMirrorParsers.parseNewTvPlayer(body)
        val vlink = p?.videoLink?.takeIf { it.isNotBlank() } ?: return null
        if (p?.status == "n") return null
        return NewTvPlayer(vlink, p.referer ?: apiBase)
    }
}
