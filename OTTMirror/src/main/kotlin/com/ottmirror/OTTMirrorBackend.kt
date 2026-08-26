package com.ottmirror

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

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/").lowercase()

    private fun isFailCode(code: Int): Boolean =
        code == 429 || code == 403 || code == 502 || code == 503 || code == 520 || code == 521 || code == 522

    // ------------------------------------------------------------------
    // Cookie / verify (t_hash_t)
    // ------------------------------------------------------------------

    suspend fun verify(): String {
        CookieBox.tHashT.takeIf { CookieBox.fresh() }?.let { return it }
        return withContext(Dispatchers.IO) {
            val maxTries = DomainRotator.liveCount(Role.MOBILE).coerceAtLeast(1)
            for (attempt in 1..maxTries) {
                val host = DomainRotator.current(Role.MOBILE) ?: break
                val ok = tryVerifyHost(host)
                if (ok != null) {
                    CookieBox.put(ok)
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

    suspend fun search(ott: OttService, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: throw ErrorLoadingException("All NetMirror hosts dead")
        val h = hostOf(host)
        HostThrottler.throttle(h)
        val url = "$host/mobile${ott.mobilePrefix}/search.php?s=${java.net.URLEncoder.encode(query, "UTF-8")}&t=${System.currentTimeMillis() / 1000}"
        val resp = runCatching {
            app.get(
                url,
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
        return NetMirrorParsers.parseSearch(resp.text)
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
        callback(
            ExtractorLink(
                source = label, name = label, url = url,
                referer = referer ?: url, quality = quality,
                headers = mapOf("Referer" to (referer ?: url)),
                extractorData = null, type = type, audioTracks = emptyList(),
            )
        )
    }

    private fun collect(ott: OttService, urls: List<String>): List<ExtractorLink> {
        val label = ottLabel(ott)
        return urls.distinct().map { u ->
            ExtractorLink(
                source = label, name = label, url = u, referer = u,
                quality = getQualityFromName(u), headers = mapOf("Referer" to u),
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
                emit(ott, vlink, ref, getQualityFromName(vlink), subtitleCallback, callback)
            }
            LinkCache.put(contentId, collect(ott, newTvLinks.map { it.first }))
            return@withContext true
        }

        val nativeOk = runCatching { nativePlaylistFlow(ott, ld, subtitleCallback, callback) }.getOrDefault(false)
        if (nativeOk) return@withContext true

        newTvFailure?.let { throw it }
        if (saw429) throw ErrorLoadingException("NetMirror servers busy — retry in a minute")
        false
    }

    private suspend fun nativePlaylistFlow(
        ott: OttService,
        ld: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cookie = verify()
        val host = DomainRotator.current(Role.MOBILE) ?: return false
        val h = hostOf(host)

        HostThrottler.throttle(h)
        val playResp = runCatching {
            app.post(
                "$host/play.php",
                headers = mobileHeaders("$host/home") + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Origin" to host,
                ),
                data = mapOf("id" to ld.id),
                cookies = mapOf("t_hash_t" to cookie, "ott" to ott.ottCookie, "hd" to "on"),
                referer = "$host/home",
                timeout = 10,
            )
        }.getOrNull()
        if (playResp != null && playResp.code == 429) { HostThrottler.recordBackoff(h); return false }
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
            emit(ott, streamUrl, referer, qualityFromLabel(s.label), subtitleCallback, callback, tracks = playlist.tracks.orEmpty())
            streamUrl
        }
        if (emittedUrls.isEmpty()) return false
        LinkCache.put(ld.id, collect(ott, emittedUrls))
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