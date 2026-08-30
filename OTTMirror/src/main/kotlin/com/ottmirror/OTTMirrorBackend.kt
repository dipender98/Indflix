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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    private fun isFailCode(code: Int): Boolean =
        code == 429 || code == 403 || code == 502 || code == 503 || code == 520 || code == 521 || code == 522

    private fun limitedMessage(): String {
        val wait = HostThrottler.cooldownSeconds()
        return if (wait > 0) "NetMirror rate limit hit — auto-clears in ~${wait}s, try again then"
        else "NetMirror servers busy — retry in a minute"
    }


    // ------------------------------------------------------------------
    // Cookie / verify (t_hash_t) — singleflight: only one coroutine verifies
    // ------------------------------------------------------------------

    private val verifyMutex = Mutex()
    @Volatile private var inFlightVerify: kotlinx.coroutines.Deferred<String>? = null

    suspend fun verify(): String {
        // The cookie is backend-wide: any fresh copy is accepted regardless of
        // which mirror currently serves us.
        CookieBox.tHashT.takeIf { CookieBox.fresh() }?.let { return it }
        NetMirrorCookieStore.load()?.let { (cookie, host, _) ->
            CookieBox.put(cookie, host)
            return cookie
        }
        val existing = inFlightVerify
        if (existing != null && existing.isActive) {
            return existing.await()
        }
        return verifyMutex.withLock {
            inFlightVerify?.takeIf { it.isActive }?.let { return@withLock it.await() }
            val deferred = kotlinx.coroutines.coroutineScope {
                async(Dispatchers.IO) { doVerify() }
            }
            inFlightVerify = deferred
            deferred.await()
        }
    }

    private suspend fun doVerify(): String {
        // Try each live mirror ONCE. A 429 means the IP is limited, not that
        // the mirror is broken — wait the cooldown out and retry the same
        // host instead of burning through the whole list with more requests.
        val maxTries = DomainRotator.liveCount(Role.MOBILE).coerceAtLeast(1)
        var retried429 = false
        var attempt = 0
        while (attempt < maxTries) {
            attempt++
            val host = DomainRotator.current(Role.MOBILE) ?: break
            when (val result = tryVerifyHost(host)) {
                is VerifyResult.Success -> {
                    CookieBox.put(result.token, host)
                    NetMirrorCookieStore.save(result.token, host)
                    HostThrottler.recordSuccess()
                    return result.token
                }
                is VerifyResult.Limited -> {
                    if (!retried429) {
                        retried429 = true
                        HostThrottler.awaitCooldown()
                        continue // same host, same attempt budget
                    }
                }
                is VerifyResult.Dead -> DomainRotator.markFailed(Role.MOBILE, host)
            }
        }
        val wait = HostThrottler.cooldownSeconds()
        if (wait > 0) throw ErrorLoadingException("NetMirror rate-limited this connection — auto-clears in ~${wait}s, try again then")
        throw ErrorLoadingException("NetMirror is unreachable right now (all hosts blocked) — retry in a minute")
    }

    private sealed interface VerifyResult {
        data class Success(val token: String) : VerifyResult
        data object Limited : VerifyResult
        data object Dead : VerifyResult
    }

    private suspend fun tryVerifyHost(host: String): VerifyResult {
        // CNC Verse approach: POST /verify.php with redirects disabled, the
        // server answers 301 carrying Set-Cookie: t_hash_t=... on the redirect
        // response itself. Following the redirect (what app.post does) loses it.
        val body = "g-recaptcha-response=${UUID.randomUUID()}"
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        var sawNetworkError = false
        for (url in listOf("$host/verify.php", "$host/mobile/verify2.php")) {
            HostThrottler.gate()
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                .apply {
                    (mobileHeaders("$host/verify2") + mapOf("Origin" to host)).forEach { (k, v) -> header(k, v) }
                }
                .build()
            var saw429 = false
            val token = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        saw429 = true
                        HostThrottler.recordLimited(resp.header("Retry-After"))
                        return@use null
                    }
                    if (isFailCode(resp.code)) { saw429 = true; return@use null }
                    resp.headers.values("Set-Cookie").firstOrNull { it.startsWith("t_hash_t=") }
                        ?.substringAfter("t_hash_t=")?.substringBefore(";")
                }
            }.getOrNull()
            if (saw429) return VerifyResult.Limited
            if (!token.isNullOrBlank()) return VerifyResult.Success(token)

            HostThrottler.gate()
            val posted = runCatching {
                app.post(
                    url,
                    headers = mobileHeaders("$host/verify2") + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                    data = mapOf("g-recaptcha-response" to UUID.randomUUID().toString()),
                    timeout = 10,
                )
            }.getOrElse {
                sawNetworkError = true
                return@getOrElse null
            }
            if (posted == null) continue
            if (posted.code == 429) {
                HostThrottler.recordLimited(posted.headers["Retry-After"])
                return VerifyResult.Limited
            }
            if (isFailCode(posted.code)) return VerifyResult.Limited
            val html = posted.text
            val start = "data-addhash=\""
            val idx = html.indexOf(start)
            val bodyToken = if (idx >= 0) html.substring(idx + start.length).substringBefore("\"").takeIf { it.isNotBlank() } else null
            if (bodyToken != null) return VerifyResult.Success(bodyToken)
        }
        // No token and no 429: either the mirror is down or it changed shape.
        // Network errors mean the mirror is dead; silent 200s without a token
        // are treated the same so we move on.
        return VerifyResult.Dead
    }


    // warmUp() was removed: it fired 2-3 extra requests ahead of every real
    // one and was a primary contributor to the IP rate limit. Failures now
    // surface in the real request path, which already rotates + waits.


    // ------------------------------------------------------------------
    // Home page
    // ------------------------------------------------------------------

    suspend fun getHomeRows(ott: OttService): List<Pair<String, List<String>>> {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return emptyList()
        HostThrottler.gate()
        val resp = runCatching {
            app.get(
                "$host/mobile/home?app=1",
                headers = mobileHeaders("$host/home"),
                cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                referer = "$host/home",
                timeout = 10,
            )
        }.getOrNull() ?: return emptyList()
        if (resp.code == 429) {
            HostThrottler.recordLimited(resp.headers["Retry-After"])
            throw ErrorLoadingException(limitedMessage())
        }
        if (resp.code in 200..299) HostThrottler.recordSuccess()
        return runCatching { parseHomeRows(resp.document) }.getOrDefault(emptyList())

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
    // desktop endpoint. On failure the host rotates so the next attempt gets a
    // fresh cookie from the new host (see CookieBox.issuedHost).
    suspend fun search(ott: OttService, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val maxTries = DomainRotator.liveCount(Role.MOBILE).coerceAtLeast(1)

        var retried429 = false
        for (attempt in 1..maxTries) {
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: break
            val url = "$host/mobile${ott.mobilePrefix}/search.php?s=$encoded&t=${System.currentTimeMillis() / 1000}"
            Log.d("TEMP-OTTMIRROR", "search ott=${ott.id} query=$query host=$host path=/mobile${ott.mobilePrefix}/search.php ottCookie=${ott.ottCookie}")
            HostThrottler.gate()
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull()

            if (resp?.code == 429) {
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                if (!retried429) {
                    retried429 = true
                    HostThrottler.awaitCooldown()
                    continue
                }
                throw ErrorLoadingException(limitedMessage())
            }
            if (resp == null || isFailCode(resp.code)) {
                Log.d("TEMP-OTTMIRROR", "search fail host=$host code=${resp?.code} -> rotate")
                DomainRotator.markFailed(Role.MOBILE, host)
                continue
            }
            HostThrottler.recordSuccess()

            val hits = NetMirrorParsers.parseSearch(resp.text)
            Log.d("TEMP-OTTMIRROR", "search ok ott=${ott.id} host=$host code=${resp.code} rawLen=${resp.text.length} hits=${hits.size}")
            return hits
        }
        Log.d("TEMP-OTTMIRROR", "search exhausted ott=${ott.id} -> ErrorLoadingException")
        throw ErrorLoadingException("NetMirror is unreachable right now — retry in a minute")
    }

    suspend fun loadPost(ott: OttService, id: String): NetMirrorPost? {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return null
        var retried429 = false
        while (true) {
            HostThrottler.gate()
            val url = "$host/mobile${ott.mobilePrefix}/post.php?id=$id&t=${System.currentTimeMillis() / 1000}"
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: return null
            if (resp.code == 429) {
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                if (!retried429) {
                    retried429 = true
                    HostThrottler.awaitCooldown()
                    continue // same host — the limit is per-IP, rotation can't help
                }
                throw ErrorLoadingException(limitedMessage())
            }
            if (resp.code in 200..299) HostThrottler.recordSuccess()
            return NetMirrorParsers.parsePost(resp.text)
        }
    }


    suspend fun getEpisodes(ott: OttService, seriesId: String, seasonId: String): List<NetMirrorEpisode> {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return emptyList()
        val out = mutableListOf<NetMirrorEpisode>()
        var page = 1
        var retried429 = false
        while (true) {
            HostThrottler.gate()
            val url = "$host/mobile${ott.mobilePrefix}/episodes.php?s=$seasonId&series=$seriesId&t=${System.currentTimeMillis() / 1000}&page=$page"
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull() ?: break
            if (resp.code == 429) {
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                if (!retried429) { retried429 = true; HostThrottler.awaitCooldown(); continue }
                break
            }
            if (resp.code !in 200..299) break
            val (eps, next) = NetMirrorParsers.parseEpisodes(resp.text)
            out.addAll(eps)
            if (!next) break
            page++
        }
        return out
    }


    // ------------------------------------------------------------------
    // NewTV player API (primary stream source)
    // ------------------------------------------------------------------

    suspend fun resolveNewTvBase(probe: Boolean = false): String {
        NewTvBase.value.takeIf { it.isNotBlank() }?.let { return it }
        val maxTries = DomainRotator.liveCount(Role.NEWTV).coerceAtLeast(1)
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
            } else if (resp.code == 429) {
                // IP-wide limit: no other NewTV domain will behave differently.
                // Wait the cooldown out and retry the SAME host — never mark
                // it dead, that would burn the whole domain list for nothing.
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                HostThrottler.awaitCooldown()
            } else {
                // 5xx/403, or a 200 whose token doesn't parse (shape change).
                // Both mean this host is unhealthy — advance.
                DomainRotator.markFailed(Role.NEWTV, host)
            }
        }

        if (probe) return ""
        throw ErrorLoadingException("NetMirror NewTV servers unreachable — retry in a minute")
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

    private fun collect(ott: OttService, urls: List<String>, cookie: String): List<ExtractorLink> {
        val label = ottLabel(ott)
        return urls.distinct().map { u ->
            ExtractorLink(
                source = label, name = label, url = u, referer = u,
                quality = getQualityFromName(u), headers = streamHeaders(u, cookie, ott),
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

        var saw429 = false
        var newTvFailure: Throwable? = null

        val newTvLinks = try {
            val apiBase = resolveNewTvBase()
            val h = hostOf(apiBase)
            var resp = run {
                HostThrottler.gate()
                app.get(
                    "$apiBase/newtv/player.php?id=$contentId",
                    headers = NEWTV_HEADERS + mapOf("Ott" to ott.ottCookie),
                    timeout = 10,
                )
            }
            if (resp.code == 429) {
                // Wait out the limiter once — rotating to another NewTV domain
                // hits the same IP limit and just burns mirrors.
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                HostThrottler.awaitCooldown()
                HostThrottler.gate()
                resp = app.get(
                    "$apiBase/newtv/player.php?id=$contentId",
                    headers = NEWTV_HEADERS + mapOf("Ott" to ott.ottCookie),
                    timeout = 10,
                )
            }
            if (resp.code == 429) {
                saw429 = true
                HostThrottler.recordLimited(resp.headers["Retry-After"])
                emptyList()
            } else if (resp.code in 200..299) {

                HostThrottler.recordSuccess(h)
                val p = NetMirrorParsers.parseNewTvPlayer(resp.text)
                p?.videoLink?.takeIf { it.isNotBlank() }?.let { vlink ->
                    listOf(vlink to (p.referer ?: apiBase))
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            newTvFailure = e
            emptyList()
        }

        if (newTvLinks.isNotEmpty()) {
            newTvLinks.forEach { (vlink, ref) ->
                emit(ott, vlink, ref, getQualityFromName(vlink), cookie, subtitleCallback, callback)
            }
            LinkCache.put(contentId, collect(ott, newTvLinks.map { it.first }, cookie))
            return@withContext true
        }

        val nativeOk = runCatching { nativePlaylistFlow(ott, ld, cookie, subtitleCallback, callback) }.getOrDefault(false)
        if (nativeOk) return@withContext true

        newTvFailure?.let { throw it }
        if (saw429) throw ErrorLoadingException(limitedMessage())
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
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$playHost/home",
                    timeout = 10,
                )
            }.getOrNull()
        }
        var playResp = postPlay()
        if (playResp?.code == 429) {
            HostThrottler.recordLimited(playResp.headers["Retry-After"])
            HostThrottler.awaitCooldown()
            playResp = postPlay()
        }
        if (playResp == null || playResp.code == 429) return false
        val hToken = runCatching {
            JSONObject(playResp.text).optString("h").takeIf { it.isNotBlank() }
        }.getOrNull() ?: return false

        val playlistUrl = "$host/mobile${ott.mobilePrefix}/playlist.php?id=${ld.id}&t=${java.net.URLEncoder.encode(ld.title, "UTF-8")}&tm=${System.currentTimeMillis() / 1000}&h=${java.net.URLEncoder.encode(hToken, "UTF-8")}"
        suspend fun getPlaylist() = run {
            HostThrottler.gate()
            runCatching {
                app.get(
                    playlistUrl,
                    headers = mobileHeaders("$host/home"),
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull()
        }
        var playlistResp = getPlaylist() ?: return false
        if (playlistResp.code == 429) {
            HostThrottler.recordLimited(playlistResp.headers["Retry-After"])
            HostThrottler.awaitCooldown()
            playlistResp = getPlaylist() ?: return false
        }
        if (playlistResp.code == 429) return false


        val playlist = NetMirrorParsers.parsePlaylist(playlistResp.text) ?: return false
        val sources = playlist.sources.orEmpty().filter { !it.file.isBlank() }
        if (sources.isEmpty()) return false

        val referer = "$host/home"
        val emittedUrls = sources.mapNotNull { s ->
            val streamUrl = if (s.file.startsWith("http", ignoreCase = true)) s.file else "$host${s.file}"
            emit(ott, streamUrl, referer, qualityFromLabel(s.label), cookie, subtitleCallback, callback, tracks = playlist.tracks.orEmpty())
            streamUrl
        }
        if (emittedUrls.isEmpty()) return false
        LinkCache.put(ld.id, collect(ott, emittedUrls, cookie))
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