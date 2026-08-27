package com.ottmirror

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    // Headers the stream CDN expects on every .m3u8 / segment request. A bare
    // Referer is enough for the first few segments, then the CDN rate-limits
    // the player (429 "Too many requests"). The mobile app sends the full set.
    private fun streamHeaders(referer: String, cookie: String, ott: OttService): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Referer" to referer,
        "Origin" to referer.substringBefore("/home").ifBlank { referer },
        "Accept" to "*/*",
        "Cookie" to "t_hash_t=$cookie; ott=${ott.ottCookie}; hd=on",
    )

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/").lowercase()

    private fun isFailCode(code: Int): Boolean =
        code == 429 || code == 403 || code == 502 || code == 503 || code == 520 || code == 521 || code == 522

    // ------------------------------------------------------------------
    // Cookie / verify (t_hash_t)
    // ------------------------------------------------------------------

    suspend fun verify(): String {
        // Fast path 1: in-memory cookie, fresh, still on the issuing host.
        CookieBox.tHashT.takeIf { CookieBox.fresh() && CookieBox.issuedHost == DomainRotator.current(Role.MOBILE) }
            ?.let { return it }
        // Fast path 2: cookie persisted across restarts (15 h TTL), same host.
        NetMirrorCookieStore.load()?.let { (cookie, host, _) ->
            if (host == DomainRotator.current(Role.MOBILE)) {
                CookieBox.put(cookie, host)
                return cookie
            }
        }
        return withContext(Dispatchers.IO) {
            val maxTries = DomainRotator.liveCount(Role.MOBILE).coerceAtLeast(1)
            for (attempt in 1..maxTries) {
                val host = DomainRotator.current(Role.MOBILE) ?: break
                val ok = tryVerifyHost(host)
                if (ok != null) {
                    CookieBox.put(ok, host)
                    NetMirrorCookieStore.save(ok, host)
                    HostThrottler.recordSuccess(host)
                    return@withContext ok
                }
                HostThrottler.recordBackoff(host)
                DomainRotator.markFailed(Role.MOBILE, host)
            }
            throw ErrorLoadingException("NetMirror is unreachable right now (all hosts blocked) — retry in a minute")
        }
    }

    private suspend fun tryVerifyHost(host: String): String? {
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
        for (url in listOf("$host/verify.php", "$host/mobile/verify2.php")) {
            val h = hostOf(url)
            HostThrottler.throttle(h)
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                .apply {
                    (mobileHeaders("$host/verify2") + mapOf("Origin" to host)).forEach { (k, v) -> header(k, v) }
                }
                .build()
            val token = runCatching {
                client.newCall(req).execute().use { resp ->
                    resp.headers.values("Set-Cookie").firstOrNull { it.startsWith("t_hash_t=") }
                        ?.substringAfter("t_hash_t=")?.substringBefore(";")
                }
            }.getOrNull()
            if (!token.isNullOrBlank()) return token
            val bodyToken = app.post(
                url,
                headers = mobileHeaders("$host/verify2") + mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                data = mapOf("g-recaptcha-response" to UUID.randomUUID().toString()),
                timeout = 10,
            ).text.let { html ->
                val start = "data-addhash=\""
                val idx = html.indexOf(start)
                if (idx >= 0) html.substring(idx + start.length).substringBefore("\"").takeIf { it.isNotBlank() } else null
            }
            if (bodyToken != null) return bodyToken
        }
        return null
    }

    // ------------------------------------------------------------------
    // Health probe
    // ------------------------------------------------------------------

    @Volatile private var warmed = false

    suspend fun warmUp() {
        if (warmed) return
        runCatching {
            coroutineScope {
                val mobile = async { probeMobile() }
                val newtv = async { probeNewTv() }
                mobile.await()
                newtv.await()
            }
        }
        warmed = true
    }

    private suspend fun probeMobile() {
        val host = DomainRotator.current(Role.MOBILE) ?: return
        val h = hostOf(host)
        HostThrottler.throttle(h)
        val resp = runCatching {
            app.get("$host/mobile/home?app=1", headers = mobileHeaders(host), timeout = 6)
        }.getOrNull() ?: run {
            DomainRotator.markFailed(Role.MOBILE, host)
            return
        }
        if (isFailCode(resp.code)) {
            HostThrottler.recordBackoff(host)
            DomainRotator.markFailed(Role.MOBILE, host)
        } else {
            HostThrottler.recordSuccess(host)
        }
    }

    private suspend fun probeNewTv() {
        val base = runCatching { resolveNewTvBase(probe = true) }.getOrNull() ?: return
        val h = hostOf(base)
        HostThrottler.throttle(h)
        val resp = runCatching { app.get("$base/newtv/player.php?id=warmup", headers = NEWTV_HEADERS, timeout = 6) }.getOrNull()
        if (resp == null || isFailCode(resp.code)) {
            HostThrottler.recordBackoff(h)
            DomainRotator.markFailed(Role.NEWTV, base)
        } else {
            HostThrottler.recordSuccess(h)
        }
    }

    // ------------------------------------------------------------------
    // Home page
    // ------------------------------------------------------------------

    suspend fun getHomeRows(ott: OttService): List<Pair<String, List<String>>> {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return emptyList()
        val h = hostOf(host)
        HostThrottler.throttle(h)
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
            HostThrottler.recordBackoff(h)
            DomainRotator.markFailed(Role.MOBILE, host)
            throw ErrorLoadingException("NetMirror servers busy — retry in a minute")
        }
        if (resp.code in 200..299) HostThrottler.recordSuccess(h)
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

        for (attempt in 1..maxTries) {
            val cookie = verify()
            val host = DomainRotator.current(Role.MOBILE) ?: break
            val h = hostOf(host)
            val url = "$host/mobile${ott.mobilePrefix}/search.php?s=$encoded&t=${System.currentTimeMillis() / 1000}"
            Log.d("TEMP-OTTMIRROR", "search ott=${ott.id} query=$query host=$host path=/mobile${ott.mobilePrefix}/search.php ottCookie=${ott.ottCookie}")
            val resp = runCatching {
                app.get(
                    url,
                    headers = mobileHeaders("$host/home"),
                    cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                    referer = "$host/home",
                    timeout = 10,
                )
            }.getOrNull()

            if (resp == null || isFailCode(resp.code)) {
                Log.d("TEMP-OTTMIRROR", "search fail host=$host code=${resp?.code} -> rotate")
                HostThrottler.recordBackoff(h)
                DomainRotator.markFailed(Role.MOBILE, host)
                continue
            }
            HostThrottler.recordSuccess(h)
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
        val h = hostOf(host)
        HostThrottler.throttle(h)
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
            HostThrottler.recordBackoff(h)
            DomainRotator.markFailed(Role.MOBILE, host)
            throw ErrorLoadingException("NetMirror servers busy — retry in a minute")
        }
        if (resp.code in 200..299) HostThrottler.recordSuccess(h)
        return NetMirrorParsers.parsePost(resp.text)
    }

    suspend fun getEpisodes(ott: OttService, seriesId: String, seasonId: String): List<NetMirrorEpisode> {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return emptyList()
        val h = hostOf(host)
        val out = mutableListOf<NetMirrorEpisode>()
        var page = 1
        while (true) {
            HostThrottler.throttle(h)
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
            if (resp.code == 429) { HostThrottler.recordBackoff(h); break }
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
            HostThrottler.throttle(h)
            val resp = runCatching {
                app.get("$host/checknewtv.php", headers = NEWTV_HEADERS, timeout = 8)
            }.getOrNull()
            if (resp != null && resp.code in 200..299) {
                val token = NetMirrorParsers.parseNewTvToken(resp.text)?.tokenHash?.takeIf { it.isNotBlank() }
                if (token != null) {
                    val apiBase = Base64Decode.decodeUtf8(token)?.trimEnd('/')
                    if (!apiBase.isNullOrBlank() && apiBase.startsWith("http")) {
                        NewTvBase.set(apiBase)
                        HostThrottler.recordSuccess(h)
                        return apiBase
                    }
                }
            }
            if (resp != null && isFailCode(resp.code)) HostThrottler.recordBackoff(h)
            DomainRotator.markFailed(Role.NEWTV, host)
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
            HostThrottler.throttle(h)
            val resp = app.get(
                "$apiBase/newtv/player.php?id=$contentId",
                headers = NEWTV_HEADERS + mapOf("Ott" to ott.ottCookie),
                timeout = 10,
            )
            if (resp.code == 429) {
                saw429 = true
                HostThrottler.recordBackoff(h)
                DomainRotator.markFailed(Role.NEWTV, apiBase)
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
        if (saw429) throw ErrorLoadingException("NetMirror servers busy — retry in a minute")
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
        val h = hostOf(host)
        // play.php lives on a mobile mirror, not net52.cc. Using the rotated
        // host instead of a hardcoded mirror spreads play requests across the
        // list, so one mirror can't absorb all the load and 429.
        val playHost = host

        HostThrottler.throttle(playHost)
        val playResp = runCatching {
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
        if (playResp != null && playResp.code == 429) { HostThrottler.recordBackoff(playHost); return false }
        val hToken = runCatching {
            JSONObject(playResp?.text ?: return false).optString("h").takeIf { it.isNotBlank() }
        }.getOrNull() ?: return false

        HostThrottler.throttle(h)
        val playlistUrl = "$host/mobile${ott.mobilePrefix}/playlist.php?id=${ld.id}&t=${java.net.URLEncoder.encode(ld.title, "UTF-8")}&tm=${System.currentTimeMillis() / 1000}&h=${java.net.URLEncoder.encode(hToken, "UTF-8")}"
        val playlistResp = runCatching {
            app.get(
                playlistUrl,
                headers = mobileHeaders("$host/home"),
                cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                referer = "$host/home",
                timeout = 10,
            )
        }.getOrNull() ?: return false
        if (playlistResp.code == 429) { HostThrottler.recordBackoff(h); return false }

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