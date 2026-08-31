package com.ottmirror

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

    private fun mobileHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
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
                NetMirrorGuard.Verdict.DEAD -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
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
        if (NewTvBase.value.isBlank()) NewTvBase.warm()
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

        var sawLimited = false

        // ------------------------------------------------------------------
        // Path 1 — NewTV player (CNCVerse-proven primary flow).
        //
        // The reference extension that actually works (NivinCNC/CNCVerse)
        // does playback EXACTLY like this: player.php with the Ott + empty
        // Usertoken headers, require status:"ok", then emit video_link as-is
        // with referer = response.referer. It never fetches or validates the
        // master at link time — the player fetches it, and the hd=on cookie
        // we attach to every stream link (streamHeaders) is what makes the
        // CDN serve real rendition keys. A headerless probe of the master
        // shows a dead "?in=unknown" template, but that template is an
        // artifact of requesting without hd=on, not the real response.
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
                            "Ott" to ott.ottCookie,
                            "Usertoken" to "",
                        ),
                        timeout = 10,
                    )
                }.getOrNull() ?: break

                when (NetMirrorGuard.classify(resp.code, resp.text)) {
                    NetMirrorGuard.Verdict.OK -> {
                        val p = NetMirrorParsers.parseNewTvPlayer(resp.text)
                        val vlink = p?.videoLink?.takeIf { it.isNotBlank() }
                        // Accept any response that carries a real video_link
                        // and is not an explicit failure. Do NOT require
                        // status=="ok": player.php answers "otp" (one-time
                        // play) on some titles with a perfectly valid link,
                        // and the hd=on cookie we attach fixes the master.
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
        // Path 2 — embed-tmdb (sessionless, TMDB-keyed, different backend).
        // Bonus sessionless fallback when the NewTV player has no stream.
        // ------------------------------------------------------------------
        val embed = ld.tmdbId?.let { tmdbId ->
            runCatching { EmbedTmdb.resolve(tmdbId, ld.season, ld.episode) }.getOrNull()
        }
        if (embed != null) {
            // Sessionless MP4 CDN: the videodownloader.site referer is the
            // hotlink context the net27 CDN expects; no t_hash_t cookie.
            val link = ExtractorLink(
                source = ottLabel(ott), name = ottLabel(ott), url = embed.url,
                referer = EMBED_REFERER, quality = embed.quality,
                headers = streamHeaders(EMBED_REFERER, "", ott),
                extractorData = null,
                type = if (embed.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                audioTracks = emptyList(),
            )
            runCatching { callback(link) }
            // Captions come absolute (https://net27.cc/...); emit() only knows
            // PlaylistTrack, so forward them here.
            embed.subs.forEach { c ->
                runCatching { subtitleCallback(SubtitleFile(c.name.ifBlank { c.lang.ifBlank { "subs" } }, c.url)) }
            }
            LinkCache.put(contentId, listOf(link))
            return@withContext true
        }

        // ------------------------------------------------------------------
        // Path 3 — native play.php/playlist.php (net7x, session-bound). Last
        // resort: verify() runs lazily HERE, only when this path is actually
        // reached, so the common tap never burns a verify request.
        // ------------------------------------------------------------------
        val cookie = runCatching { verify() }.getOrDefault("")
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
