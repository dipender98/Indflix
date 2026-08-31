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
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object OTTMirrorBackend {

    data class LoadData(val id: String, val title: String, val tmdbId: String? = null)

    fun encodeLoadData(d: LoadData): String = JSONObject()
        .put("id", d.id)
        .put("title", d.title)
        .apply { d.tmdbId?.let { put("tmdbId", it) } }
        .toString()

    fun decodeLoadData(data: String): LoadData? = try {
        val m = JSONObject(data)
        val id = m.optString("id").takeIf { it.isNotBlank() } ?: return null
        LoadData(id, m.optString("title"), m.optString("tmdbId").takeIf { it.isNotBlank() })
    } catch (e: Exception) {
        null
    }

    private fun mobileHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "User-Agent" to MOBILE_UA,
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to referer,
    )

    private fun streamHeaders(referer: String, cookie: String, ott: OttService): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to referer,
        "Origin" to referer.substringBefore("/home").ifBlank { referer },
        "Accept" to "*/*",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Cookie" to "t_hash_t=$cookie; ott=${ott.ottCookie}; hd=on",
    )

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
        // CNC Verse approach: POST /verify.php with redirects disabled — the
        // server answers 301 carrying Set-Cookie: t_hash_t=... on the redirect
        // response itself. Exactly ONE request per host (the old two-URL,
        // two-method fallback quadrupled the verify cost per host).
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
            .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
            .apply {
                (mobileHeaders("$host/verify2") + mapOf("Origin" to host)).forEach { (k, v) -> header(k, v) }
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
        val cookie = verify()
        var sessionRetried = false
        var limitedAttempts = 0
        var attempts = 0
        while (attempts++ < 5) {
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
                    return runCatching { parseHomeRows(resp.document) }.getOrDefault(emptyList())
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) throw ErrorLoadingException(limitedMessage())
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    // /mobile/home works without a session, but if the server
                    // says otherwise the cookie is dead — refresh it once.
                    if (sessionRetried) return emptyList()
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                    verify()
                }
                NetMirrorGuard.Verdict.DEAD -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
        return emptyList()
    }

    fun parseHomeRows(doc: Document): List<Pair<String, List<String>>> {
        val rows = doc.select(".tray-container, #top10")
        return rows.mapNotNull { tray ->
            val name = tray.selectFirst("h2, span")?.text()?.trim() ?: return@mapNotNull null
            val ids = tray.select("article, .top10-post").mapNotNull {
                it.selectFirst("a")?.attr("data-post") ?: it.attr("data-post")
            }.filter { it.isNotBlank() }
            if (ids.isEmpty()) null else name to ids
        }.filter { it.second.isNotEmpty() }
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
        var sessionRetried = false
        var limitedAttempts = 0
        var attempts = 0
        while (attempts++ < 5) {
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: break
            val url = "$host/mobile${ott.mobilePrefix}/search.php?s=$encoded&t=${System.currentTimeMillis() / 1000}"
            Log.d("TEMP-OTTMIRROR", "search ott=${ott.id} query=$query host=$host path=/mobile${ott.mobilePrefix}/search.php ottCookie=${ott.ottCookie}")
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
                    Log.d("TEMP-OTTMIRROR", "search ok ott=${ott.id} host=$host code=${resp.code} rawLen=${resp.text.length} hits=${hits.size}")
                    if (hits.isEmpty() && resp.text.contains("Top Searches") && !sessionRetried) {
                        // Scoped search degraded to the global fallback — the
                        // session is dead even though the response "worked".
                        sessionRetried = true
                        NetMirrorGuard.invalidateSession()
                        continue
                    }
                    return hits
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) throw ErrorLoadingException(limitedMessage())
                }
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    if (sessionRetried) break
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                }
                NetMirrorGuard.Verdict.DEAD -> {
                    Log.d("TEMP-OTTMIRROR", "search dead host=$host code=${resp.code} -> rotate")
                    DomainRotator.markFailed(Role.MOBILE, host)
                }
            }
        }
        Log.d("TEMP-OTTMIRROR", "search exhausted ott=${ott.id} -> ErrorLoadingException")
        throw ErrorLoadingException(
            if (HostThrottler.isCoolingDown()) limitedMessage()
            else "NetMirror is unreachable right now — retry in a minute"
        )
    }

    suspend fun loadPost(ott: OttService, id: String): NetMirrorPost? {
        NetMirrorResponseCache.get<NetMirrorPost>("post|${ott.id}|$id")?.let { return it }
        var sessionRetried = false
        var limitedAttempts = 0
        var attempts = 0
        while (attempts++ < 6) {
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
                NetMirrorGuard.Verdict.SESSION_DEAD -> {
                    // The normal case on a warm cache: the session aged out
                    // (server TTL ~4-5 min). Re-verify once and repeat the
                    // request with a live cookie instead of failing.
                    if (sessionRetried) return null
                    sessionRetried = true
                    NetMirrorGuard.invalidateSession()
                }
                NetMirrorGuard.Verdict.LIMITED -> {
                    limitedAttempts++
                    if (!NetMirrorGuard.onLimited(limitedAttempts)) throw ErrorLoadingException(limitedMessage())
                }
                NetMirrorGuard.Verdict.DEAD -> DomainRotator.markFailed(Role.MOBILE, host)
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
        NewTvBase.value.takeIf { it.isNotBlank() }?.let { return it }
        // Cap the domain sweep: from a limited IP every host answers the same
        // anti-abuse body, and walking all 24 domains only feeds the limiter.
        val maxTries = 3
        var limitedAttempts = 0
        for (attempt in 1..maxTries) {
            val host = DomainRotator.current(Role.NEWTV) ?: break
            val h = hostOf(host)
            HostThrottler.gate()
            val resp = runCatching {
                app.get("$host/checknewtv.php", headers = NEWTV_HEADERS, timeout = 8)
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
                HostThrottler.recordSuccess(h)
                return resolved
            }

            if (resp == null) {
                // Network error: this host is dead to us, advance to the next.
                DomainRotator.markFailed(Role.NEWTV, host)
            } else {
                when (NetMirrorGuard.classify(resp.code, resp.text)) {
                    NetMirrorGuard.Verdict.LIMITED -> {
                        // IP-wide limit: no other NewTV domain will behave
                        // differently. Wait it out and retry the SAME host —
                        // never mark it dead, that would burn the domain list.
                        limitedAttempts++
                        if (!NetMirrorGuard.onLimited(limitedAttempts)) {
                            if (probe) return ""
                            throw ErrorLoadingException(limitedMessage())
                        }
                    }
                    else -> DomainRotator.markFailed(Role.NEWTV, host)
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

    private fun emit(
        ott: OttService,
        url: String,
        referer: String?,
        quality: Int,
        cookie: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tracks: List<PlaylistTrack> = emptyList(),
    ) {
        val label = ottLabel(ott)
        tracks.forEach { t ->
            if (t.kind.equals("captions", ignoreCase = true) || t.kind.equals("subtitles", ignoreCase = true)) {
                val subUrl = when {
                    t.file.startsWith("//") -> "https:${t.file}"
                    t.file.startsWith("http", ignoreCase = true) -> t.file
                    else -> "https://subscdn.top${t.file}"
                }
                runCatching { subtitleCallback(SubtitleFile(t.label.ifBlank { "subs" }, subUrl)) }
            }
        }
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

    /**
     * Fetch the master playlist and collapse it to ONE rendition URL so the
     * player opens a single stream instead of concurrent connections for every
     * adaptive quality — the structural STOP-abuse trigger inside the player.
     * Best-effort: any failure returns the original master URL unchanged.
     * Skips the fetch entirely when already in cooldown to avoid adding an
     * extra request on a saturated IP.
     */
    private suspend fun collapseToSingleRendition(masterUrl: String, referer: String): String {
        if (HostThrottler.isCoolingDown()) return masterUrl
        HostThrottler.gate()
        val resp = runCatching {
            app.get(masterUrl, headers = NEWTV_HEADERS + mapOf("Referer" to referer), timeout = 10)
        }.getOrNull()
        if (resp == null) return masterUrl
        when (NetMirrorGuard.classify(resp.code, resp.text)) {
            NetMirrorGuard.Verdict.LIMITED -> {
                HostThrottler.recordLimited(null)
                return masterUrl
            }
            NetMirrorGuard.Verdict.OK -> {}
            else -> return masterUrl
        }
        return NetMirrorParsers.pickSingleVariant(masterUrl, resp.text) ?: masterUrl
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

        LinkCache.get(contentId)?.let { cached ->
            if (cached.isNotEmpty()) {
                cached.forEach { runCatching { callback(it) } }
                return@withContext true
            }
        }

        // Live t_hash_t for the stream CDN headers; NewTV links work without
        // it, so an unreachable verify host must not kill the primary path.
        val cookie = runCatching { verify() }.getOrDefault("")

        var sawLimited = false
        var newTvFailure: Throwable? = null

        val newTvLinks = try {
            val apiBase = resolveNewTvBase()
            val h = hostOf(apiBase)
            val playerUrl = "$apiBase/newtv/player.php?id=$contentId"

            var parsedLink: String? = null
            var parsedReferer: String? = null
            var limitedAttempts = 0
            var attempts = 0
            while (attempts++ < 4) {
                HostThrottler.gate()
                val resp = runCatching {
                    app.get(
                        playerUrl,
                        headers = NEWTV_HEADERS + mapOf("Ott" to ott.ottCookie),
                        timeout = 10,
                    )
                }.getOrNull() ?: break

                when (NetMirrorGuard.classify(resp.code, resp.text)) {
                    NetMirrorGuard.Verdict.OK -> {
                        HostThrottler.recordSuccess(h)
                        val p = NetMirrorParsers.parseNewTvPlayer(resp.text)
                        parsedLink = p?.videoLink?.takeIf { it.isNotBlank() }
                        parsedReferer = p?.referer
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

            parsedLink?.let { vlink -> listOf(vlink to (parsedReferer ?: apiBase)) } ?: emptyList()
        } catch (e: Exception) {
            newTvFailure = e
            emptyList()
        }

        if (newTvLinks.isNotEmpty()) {
            // Collapse the master playlist to ONE rendition first so the
            // player opens a single stream instead of concurrent connections
            // for every adaptive quality (the structural STOP-abuse trigger).
            // When the IP is already in cooldown or this flow already saw a
            // limit event, emit the master URL as-is — one more fetch on a
            // hot bucket can tip it and buys nothing on a cold replay.
            val collapsed = newTvLinks.map { (vlink, ref) ->
                (if (sawLimited || HostThrottler.isCoolingDown()) vlink else collapseToSingleRendition(vlink, ref)) to ref
            }
            collapsed.forEach { (vlink, ref) ->
                emit(ott, vlink, ref, getQualityFromName(vlink), cookie, subtitleCallback, callback)
            }
            LinkCache.put(contentId, collect(ott, collapsed.map { it.first }, collapsed.first().second, cookie))
            return@withContext true
        }

        val nativeOk = runCatching { nativePlaylistFlow(ott, ld, cookie, subtitleCallback, callback) }.getOrDefault(false)
        if (nativeOk) return@withContext true

        newTvFailure?.let { throw it }
        if (sawLimited) throw ErrorLoadingException(limitedMessage())
        false
    }

    private suspend fun nativePlaylistFlow(
        ott: OttService,
        ld: LoadData,
        cookie: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = DomainRotator.current(Role.MOBILE) ?: return false
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
                    data = mapOf("id" to ld.id),
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
                        JSONObject(playResp.text).optString("h").takeIf { it.isNotBlank() }
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

        val playlistUrl = "$host/mobile${ott.mobilePrefix}/playlist.php?id=${ld.id}&t=${java.net.URLEncoder.encode(ld.title, "UTF-8")}&tm=${System.currentTimeMillis() / 1000}&h=${java.net.URLEncoder.encode(h, "UTF-8")}"
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
        // Emit ONE source (the server-default, else the best quality) instead
        // of every variant: one stream = far fewer concurrent player requests,
        // which is what trips the CDN anti-abuse page on a shared IP.
        val chosen = sources.firstOrNull { it.default.equals("true", ignoreCase = true) || it.default == "1" }
            ?: sources.maxByOrNull { qualityFromLabel(it.label) }
            ?: sources.first()
        val streamUrl = if (chosen.file.startsWith("http", ignoreCase = true)) chosen.file else "$host${chosen.file}"
        emit(ott, streamUrl, referer, qualityFromLabel(chosen.label), cookie, subtitleCallback, callback, tracks = playlist?.tracks.orEmpty())
        LinkCache.put(ld.id, collect(ott, listOf(streamUrl), referer, cookie))
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
